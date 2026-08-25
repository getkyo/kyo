package kyo.internal.postgres.types

import java.nio.charset.StandardCharsets
import kyo.<
import kyo.Chunk
import kyo.Frame
import kyo.Instant
import kyo.Maybe
import kyo.Span
import kyo.SqlCodec.Format
import kyo.SqlDecodeArrayAbsentElementException
import kyo.SqlDecodeByteaException
import kyo.SqlDecodeException
import kyo.SqlDecodeInstantException
import kyo.SqlDecodeIntervalException
import kyo.SqlDecodeNumericException
import kyo.SqlDecodeUuidException
import kyo.SqlDecodeValueRangeException
import kyo.internal.SqlNumericDecode.parseDecimalText
import kyo.internal.SqlNumericDecode.wholeOf
import kyo.internal.postgres.PostgresArrayReader

/** Decodes raw PostgreSQL wire bytes into a Scala value.
  *
  * Decoders handle both Text and Binary formats; the `format` argument at decode time tells the decoder which encoding the server used.
  * Text format is always accepted as a fallback so that results from SimpleQueryExchange (which uses text format exclusively) can be
  * decoded using the same codec layer.
  *
  * Throw contract: `read` throws a [[SqlDecodeException]] leaf directly for:
  *   - the NaN / +Infinity / -Infinity NUMERIC cases (the NUMERIC wire protocol has no Scala representation for these);
  *   - the INTERVAL wire-format cases with non-zero `months` or `days` components (Duration has no calendar-arithmetic representation,
  *     callers needing `java.time.Period` semantics should use a different schema);
  *   - INTERVAL text-format renderings carrying a component the target type has no lane for: a `java.time.Duration` over
  *     `'1 year 2 mons 00:01:02'` (no calendar arithmetic), a `java.time.Period` over a rendering whose time part is non-zero;
  *   - UUID binary buffers whose length is not exactly 16 bytes;
  *   - a numeric-family column whose value the requested Scala type cannot carry, or whose wire width that type has no exact reading for
  *     ([[kyo.SqlDecodeValueRangeException]]). The numeric decoders resolve the wire representation from the column OID rather than assuming
  *     the width their own Scala type would have written, so a narrower type over a wider column is an error rather than a high-word read.
  *
  * All other decode failures (e.g. `NumberFormatException` from `.toInt`) propagate as unchecked exceptions; callers (specifically
  * `PostgresRowReader`) catch them and wrap them in a [[SqlDecodeException]] leaf.
  *
  * @tparam A
  *   the Scala type this decoder produces
  */
trait PostgresDecoder[A]:
    /** OIDs this decoder recognises. */
    def oids: Set[Int]

    /** Decodes `bytes` from the given `format` into an `A`.
      *
      * `columnOid` is the OID the server reported for the column these bytes came from. It is what tells a numeric decoder whether an 8-byte
      * payload is an `int8`, a `float8`, or a zero-digit `numeric`, all three of which are eight bytes and none of which reads correctly as
      * either of the others. Every decoder outside the numeric family ignores it.
      */
    def read(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): A

    /** Decodes `bytes` with no column metadata, for a caller that has none: a value nested inside another value's payload, or a column
      * buffered away from its row. The numeric decoders then resolve the wire representation from the byte width alone and assume their own
      * family for a width their family shares, so a cross-family mismatch is invisible on this overload where the three-argument form would
      * catch it.
      */
    final def read(format: Format, bytes: Span[Byte])(using Frame): A =
        read(format, bytes, PostgresEncoder.OID_UNSPECIFIED)
end PostgresDecoder

object PostgresDecoder:

    import PostgresEncoder.*

    // --- Helpers ---

    private def text(bytes: Span[Byte]): String =
        new String(bytes.toArray, StandardCharsets.UTF_8)

    private def readBigEndianLong(bytes: Span[Byte], offset: Int): Long =
        ((bytes(offset) & 0xffL) << 56) |
            ((bytes(offset + 1) & 0xffL) << 48) |
            ((bytes(offset + 2) & 0xffL) << 40) |
            ((bytes(offset + 3) & 0xffL) << 32) |
            ((bytes(offset + 4) & 0xffL) << 24) |
            ((bytes(offset + 5) & 0xffL) << 16) |
            ((bytes(offset + 6) & 0xffL) << 8) |
            (bytes(offset + 7) & 0xffL)

    private def readBigEndianInt(bytes: Span[Byte], offset: Int): Int =
        ((bytes(offset) & 0xff) << 24) |
            ((bytes(offset + 1) & 0xff) << 16) |
            ((bytes(offset + 2) & 0xff) << 8) |
            (bytes(offset + 3) & 0xff)

    private def readBigEndianShort(bytes: Span[Byte], offset: Int): Short =
        (((bytes(offset) & 0xff) << 8) | (bytes(offset + 1) & 0xff)).toShort

    // --- Numeric-family wire resolution ---
    //
    // Every extended-protocol result column is requested in binary format, so a Scala numeric type is routinely handed the bytes of a
    // DIFFERENT numeric column type: an `Int` field over `count(*)`, `sum(int4)`, or `array_length`, all of which are `int8`; a `Double`
    // field over `numeric`; a `BigDecimal` field over `int8`. Reading a fixed number of bytes chosen by the Scala type returns the high word
    // of a wider big-endian value, which for any int8 below 2^32 is a plausible zero rather than an error, so each numeric decoder resolves
    // what the bytes actually are before reading them.
    //
    // Resolution uses the OID the server reported for the column, and the value's byte width where no OID is available. The exact targets
    // (Short, Int, Long, BigInt, BigDecimal, and Byte) widen silently and raise SqlDecodeValueRangeException on a value they cannot carry;
    // the approximate targets (Float, Double) round, because rounding is what those types are for.

    /** Which wire representation a column value carries, from the point of view of a numeric decode. */
    private enum NumericWire derives CanEqual:
        case Integer, Float4, Float8, Numeric, Rendering

    /** Resolves the wire representation of a numeric column from the OID the server reported.
      *
      * `whenUnknown` covers two cases that behave the same: [[PostgresEncoder.OID_UNSPECIFIED]], where there is no column metadata at all, and
      * any OID this does not name. The second is deliberately not a rejection. The OID-alias types (`oid`, `xid`, `regclass`) and `money` are
      * all fixed-width big-endian integers that read correctly by width, so refusing an unrecognised OID here would break decodes that work
      * today for no gain against the mismatch class this dispatch exists to close. Falling back to the decoder's own family is what keeps the
      * two-argument `read` overload deciding the representation by byte width alone.
      */
    private def numericWireOf(columnOid: Int, whenUnknown: NumericWire): NumericWire =
        columnOid match
            case OID_INT2 | OID_INT4 | OID_INT8 => NumericWire.Integer
            case OID_FLOAT4                     => NumericWire.Float4
            case OID_FLOAT8                     => NumericWire.Float8
            case OID_NUMERIC                    => NumericWire.Numeric
            // The text family, which `textDecoder` also claims: a number stored in one of these is its own rendering in
            // both wire formats, so a numeric field over one parses the digits rather than reading them as a big-endian
            // integer. Same three OIDs as `textDecoder.oids`.
            case OID_TEXT | 1043 | 1042 => NumericWire.Rendering
            case _                      => whenUnknown

    /** Reads a PostgreSQL integer column at any of its three wire widths as the `Long` that carries all three. */
    private def readIntegerBinary(bytes: Span[Byte], scalaType: String)(using Frame): Long =
        bytes.size match
            case 2 => readBigEndianShort(bytes, 0).toLong
            case 4 => readBigEndianInt(bytes, 0).toLong
            case 8 => readBigEndianLong(bytes, 0)
            case n => throw SqlDecodeValueRangeException(scalaType, s"$n bytes", "integer column of unrecognised wire width")

    private def readFloat4Binary(bytes: Span[Byte], scalaType: String)(using Frame): Float =
        if bytes.size != 4 then throw SqlDecodeValueRangeException(scalaType, s"${bytes.size} bytes", "float4 column")
        else java.lang.Float.intBitsToFloat(readBigEndianInt(bytes, 0))

    private def readFloat8Binary(bytes: Span[Byte], scalaType: String)(using Frame): Double =
        if bytes.size != 8 then throw SqlDecodeValueRangeException(scalaType, s"${bytes.size} bytes", "float8 column")
        else java.lang.Double.longBitsToDouble(readBigEndianLong(bytes, 0))

    /** The exact integral value a numeric-family column carries.
      *
      * A `float4`, `float8`, or `numeric` column reaches here when a schema asks for an integral field over one, and only a value with no
      * fractional part can be carried: rounding it would be exactly the silent value change this dispatch removes.
      */
    private def integralValueOf(bytes: Span[Byte], columnOid: Int, scalaType: String)(using Frame): Long =
        numericWireOf(columnOid, NumericWire.Integer) match
            case NumericWire.Integer   => readIntegerBinary(bytes, scalaType)
            case NumericWire.Float4    => wholeOf(BigDecimal(readFloat4Binary(bytes, scalaType).toDouble), scalaType, "float4 column")
            case NumericWire.Float8    => wholeOf(BigDecimal(readFloat8Binary(bytes, scalaType)), scalaType, "float8 column")
            case NumericWire.Numeric   => wholeOf(readNumericBinary(bytes, scalaType), scalaType, "numeric column")
            case NumericWire.Rendering => wholeOf(parseDecimalText(text(bytes)), scalaType, "text column")

    /** The approximate value a numeric-family column carries, for the `Float` and `Double` targets. */
    private def approximateValueOf(bytes: Span[Byte], columnOid: Int, whenUnknown: NumericWire, scalaType: String)(using Frame): Double =
        numericWireOf(columnOid, whenUnknown) match
            case NumericWire.Integer   => readIntegerBinary(bytes, scalaType).toDouble
            case NumericWire.Float4    => readFloat4Binary(bytes, scalaType).toDouble
            case NumericWire.Float8    => readFloat8Binary(bytes, scalaType)
            case NumericWire.Numeric   => readNumericBinary(bytes, scalaType).toDouble
            case NumericWire.Rendering => text(bytes).toDouble

    /** The exact decimal value a numeric-family column carries, for the `BigDecimal`, `BigInt` and `Boolean` targets. */
    private def decimalValueOf(bytes: Span[Byte], columnOid: Int, whenUnknown: NumericWire, scalaType: String)(using Frame): BigDecimal =
        numericWireOf(columnOid, whenUnknown) match
            case NumericWire.Integer   => BigDecimal(readIntegerBinary(bytes, scalaType))
            case NumericWire.Float4    => BigDecimal(readFloat4Binary(bytes, scalaType).toDouble)
            case NumericWire.Float8    => BigDecimal(readFloat8Binary(bytes, scalaType))
            case NumericWire.Numeric   => readNumericBinary(bytes, scalaType)
            case NumericWire.Rendering => parseDecimalText(text(bytes))

    // --- Exact narrowing, one per integral target ---

    private def narrowToByte(value: Long, wire: String)(using Frame): Byte =
        if value >= Byte.MinValue.toLong && value <= Byte.MaxValue.toLong then value.toByte
        else throw SqlDecodeValueRangeException("Byte", value.toString, wire)

    private def narrowToShort(value: Long, wire: String)(using Frame): Short =
        if value >= Short.MinValue.toLong && value <= Short.MaxValue.toLong then value.toShort
        else throw SqlDecodeValueRangeException("Short", value.toString, wire)

    private def narrowToInt(value: Long, wire: String)(using Frame): Int =
        if value >= Int.MinValue.toLong && value <= Int.MaxValue.toLong then value.toInt
        else throw SqlDecodeValueRangeException("Int", value.toString, wire)

    // --- Boolean ---

    /** The renderings PostgreSQL accepts for a `bool` literal, which no numeric parse would read, matched case-insensitively against the
      * text-format column below: PostgreSQL's own `bool` input parser is case-insensitive, so `TRUE` and `True` are the same literal and
      * must decode the same. The server's own text-format OUTPUT is always the single byte `t` or `f`; the longer renderings exist for
      * defensive acceptance of the same literal syntax PostgreSQL accepts on input, not because the wire ever sends them.
      */
    private val BoolTrueRenderings  = Set("t", "true", "yes", "on")
    private val BoolFalseRenderings = Set("f", "false", "no", "off")

    /** True when a binary payload is a `bool` column's single wire byte rather than a numeric column's value.
      *
      * [[PostgresEncoder.OID_UNSPECIFIED]] covers the callers with no column metadata (a value nested inside another value's payload, a
      * column buffered away from its row), where the byte width is the only signal and one byte is `bool`'s alone among the types a
      * `Boolean` field is pointed at.
      */
    private def isBoolBinary(columnOid: Int, bytes: Span[Byte]): Boolean =
        bytes.size == 1 && (columnOid == OID_BOOL || columnOid == OID_UNSPECIFIED)

    /** Decodes a column's truthiness: any nonzero numeric value is true.
      *
      * A `bool` column is one byte on the wire and reads as that byte. Every other column a `Boolean` field is pointed at is a numeric one,
      * where truthiness is a property of the column's VALUE, so the bytes resolve through the same OID dispatch every other numeric target
      * uses. Reading byte 0 unconditionally would misread every wider numeric: a big-endian `int4` holding 1 is `00 00 00 01`, so every
      * `int4` below 2^24 would read as false; a `numeric`'s first byte is its `ndigits` header, so every value under 256 base-10000 digits
      * would read as false; and a `float8`'s top byte is its sign, so `-0.0` would read as true. The same statement through `simpleQuery`
      * answers off the text rendering and would disagree with all three.
      *
      * The unknown-wire fallback is [[NumericWire.Integer]]: with no OID the byte width is the only signal, and guessing `numeric` there
      * would read an integer's bytes as a numeric header.
      *
      * The text arm's fallback, a rendering that is neither a recognised boolean literal nor a valid decimal, raises
      * [[SqlDecodeNumericException]] rather than answering `false`. PostgreSQL only ever renders `t` or `f` in text format, so that arm is
      * unreachable through the driver's own read paths; a typed decode failure is the correct answer for an unreachable case, an untyped
      * `false` would silently misreport a column value the driver has no basis for calling falsy.
      */
    val bool: PostgresDecoder[Boolean] = new PostgresDecoder[Boolean]:
        def oids: Set[Int] = Set(OID_BOOL)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): Boolean = format match
            case Format.Binary =>
                if isBoolBinary(columnOid, bytes) then bytes(0) != 0.toByte
                else decimalValueOf(bytes, columnOid, NumericWire.Integer, "Boolean").signum != 0
            case Format.Text =>
                val s = text(bytes)
                // The server emits lowercase `t` and `f` and nothing else, so try the sets before lowercasing: the
                // overwhelmingly common read then costs two set lookups and no allocation, and only a rendering that
                // did not come from the server pays for the copy. The sets hold lowercase, so this is a pure fast
                // path and the arm below still accepts every casing.
                if BoolTrueRenderings.contains(s) then true
                else if BoolFalseRenderings.contains(s) then false
                else
                    val lower = s.toLowerCase
                    if BoolTrueRenderings.contains(lower) then true
                    else if BoolFalseRenderings.contains(lower) then false
                    else parseDecimalText(s).signum != 0
                end if

    // --- Short ---

    val int2: PostgresDecoder[Short] = new PostgresDecoder[Short]:
        def oids: Set[Int] = Set(OID_INT2)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): Short = format match
            case Format.Binary => narrowToShort(integralValueOf(bytes, columnOid, "Short"), "numeric column")
            case Format.Text   => narrowToShort(wholeOf(parseDecimalText(text(bytes)), "Short", "text"), "text")

    // --- Int ---

    val int4: PostgresDecoder[Int] = new PostgresDecoder[Int]:
        def oids: Set[Int] = Set(OID_INT4)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): Int = format match
            case Format.Binary => narrowToInt(integralValueOf(bytes, columnOid, "Int"), "numeric column")
            case Format.Text   => narrowToInt(wholeOf(parseDecimalText(text(bytes)), "Int", "text"), "text")

    // --- Long ---

    val int8: PostgresDecoder[Long] = new PostgresDecoder[Long]:
        def oids: Set[Int] = Set(OID_INT8)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): Long = format match
            case Format.Binary => integralValueOf(bytes, columnOid, "Long")
            case Format.Text   => wholeOf(parseDecimalText(text(bytes)), "Long", "text")

    // --- Byte ---
    // PostgreSQL has no single-byte integer type, so a `Byte` schema field travels as `int2`
    // (`PostgresParamWriter.byte`) and comes back from a column at least twice as wide as the field.

    /** Decodes a numeric-family column as a `Byte`, aborting rather than wrapping on a value outside `[-128, 127]`. */
    private[kyo] def readByte(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): Byte = format match
        case Format.Binary => narrowToByte(integralValueOf(bytes, columnOid, "Byte"), "numeric column")
        case Format.Text   => narrowToByte(wholeOf(parseDecimalText(text(bytes)), "Byte", "text"), "text")

    // --- Float4 ---

    val float4: PostgresDecoder[Float] = new PostgresDecoder[Float]:
        def oids: Set[Int] = Set(OID_FLOAT4)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): Float = format match
            case Format.Binary => approximateValueOf(bytes, columnOid, NumericWire.Float4, "Float").toFloat
            case Format.Text   => text(bytes).toFloat

    // --- Float8 ---

    val float8: PostgresDecoder[Double] = new PostgresDecoder[Double]:
        def oids: Set[Int] = Set(OID_FLOAT8)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): Double = format match
            case Format.Binary => approximateValueOf(bytes, columnOid, NumericWire.Float8, "Double")
            case Format.Text   => text(bytes).toDouble

    // --- Numeric ---

    /** Decodes PostgreSQL NUMERIC from both Text and Binary wire formats.
      *
      * Binary format layout (PostgreSQL `numeric.c`):
      * {{{
      *   Int16  ndigits    -- number of base-10000 digits
      *   Int16  weight     -- weight of first digit (may be negative)
      *   UInt16 sign       -- 0x0000 = positive, 0x4000 = negative,
      *                        0xC000 = NaN, 0xD000 = +Inf, 0xF000 = -Inf
      *   UInt16 dscale     -- display scale
      *   Int16  digits[]   -- each in [0..9999], most-significant first
      * }}}
      *
      * Value reconstruction: sum_i(digits[i] * 10000^(weight - i)), then apply dscale.
      */
    val numeric: PostgresDecoder[BigDecimal] = new PostgresDecoder[BigDecimal]:
        def oids: Set[Int] = Set(OID_NUMERIC)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using frame: Frame): BigDecimal = format match
            case Format.Text   => parseDecimalText(text(bytes))
            case Format.Binary => decimalValueOf(bytes, columnOid, NumericWire.Numeric, "BigDecimal")

    /** Decodes a numeric-family column as a `BigInt`, aborting on a fractional value rather than truncating it. */
    private[kyo] def readBigInt(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): BigInt = format match
        case Format.Text   => wholeDecimalOf(parseDecimalText(text(bytes)), "text").toBigInt
        case Format.Binary => wholeDecimalOf(decimalValueOf(bytes, columnOid, NumericWire.Numeric, "BigInt"), "numeric column").toBigInt

    /** A `BigInt` target has no magnitude limit, so only the fractional part can make a value uncarryable. */
    private def wholeDecimalOf(value: BigDecimal, wire: String)(using Frame): BigDecimal =
        if value.isWhole then value
        else throw SqlDecodeValueRangeException("BigInt", value.toString, wire)

    /** Parses the PostgreSQL NUMERIC binary wire form.
      *
      * `scalaType` names the type the caller asked for, so a malformed or too-short payload reports which decode was attempted rather than
      * reading past the end of the span.
      */
    private def readNumericBinary(bytes: Span[Byte], scalaType: String)(using Frame): BigDecimal =
        // The wire form is a 4-field header of 2-byte fields followed by one 2-byte digit each, so every valid payload
        // is an even count of at least 8 bytes. Checking that before the header read is what keeps a wider or narrower
        // column's bytes from being reinterpreted as a header, and keeps a short one from reading past the span.
        if bytes.size < 8 || bytes.size % 2 != 0 then
            throw SqlDecodeValueRangeException(scalaType, s"${bytes.size} bytes", "numeric column")
        end if

        // Read the 4-field fixed header directly (8 bytes total).
        // ndigits: UInt16 BE at offset 0
        // weight:  Int16 BE (signed) at offset 2
        // sign:    UInt16 BE at offset 4
        // dscale:  UInt16 BE at offset 6
        val ndigits = readBigEndianShort(bytes, 0).toInt & 0xffff
        val weight  = readBigEndianShort(bytes, 2).toInt // signed
        val sign    = readBigEndianShort(bytes, 4).toInt & 0xffff
        val dscale  = readBigEndianShort(bytes, 6).toInt & 0xffff

        sign match
            case 0xc000 =>
                throw SqlDecodeNumericException("NaN", SqlDecodeNumericException.Subtype.NaN)
            case 0xd000 =>
                throw SqlDecodeNumericException("+Infinity", SqlDecodeNumericException.Subtype.PosInf)
            case 0xf000 =>
                throw SqlDecodeNumericException("-Infinity", SqlDecodeNumericException.Subtype.NegInf)
            case _ => ()
        end match

        if 8 + ndigits * 2 > bytes.size then
            throw SqlDecodeValueRangeException(scalaType, s"${bytes.size} bytes for $ndigits digits", "numeric column")
        end if

        if ndigits == 0 then BigDecimal(0).setScale(dscale)
        else
            // Read all digits; each is an unsigned Int16 BE at offsets 8, 10, 12, ...
            val digits = Array.tabulate(ndigits)(i => readBigEndianShort(bytes, 8 + i * 2).toInt & 0xffff)

            // Reconstruct the integer formed by concatenating digits in base-10000.
            val unscaled = digits.foldLeft(BigInt(0)) { (acc, d) => acc * 10000 + d }

            // Digit[0] is at base-10000 position `weight`, digit[k] at `weight - k`.
            // The last digit is at position `weight - ndigits + 1`.
            // Multiplying `unscaled` by 10000^(weight - ndigits + 1) gives the actual value.
            val base10000exp = weight - ndigits + 1

            val unsigned: BigDecimal =
                if base10000exp >= 0 then
                    BigDecimal(unscaled) * BigDecimal(10).pow(4 * base10000exp)
                else
                    // Negative exponent: construct BigDecimal with scale = 4 * |base10000exp|.
                    BigDecimal(unscaled, -(4 * base10000exp))

            // Apply display scale to recover trailing zeros stripped during encoding.
            val scaled = unsigned.setScale(dscale)

            if sign == 0x4000 then -scaled else scaled
        end if
    end readNumericBinary

    // --- Text / Varchar ---

    val textDecoder: PostgresDecoder[String] = new PostgresDecoder[String]:
        // Accepts text OID, varchar OID (1043), and bpchar OID (1042).
        def oids: Set[Int]                                                               = Set(OID_TEXT, 1043, 1042)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): String = text(bytes)

    // --- JSON / JSONB ---
    // Handles both json (OID 114) and jsonb (OID 3802).
    //
    // Binary format (JSONB, OID 3802): first byte is the JSONB version (0x01); remaining bytes are UTF-8 JSON text.
    // Text format (JSON, OID 114) and JSONB text fallback: raw UTF-8 JSON text with no prefix.
    //
    // This decoder is registered for both OIDs so the same instance handles either column type.
    // If the server sends the value in Binary format and the OID is JSONB, the version byte is stripped.
    // For all Text-format values the full byte span is decoded as UTF-8 (no prefix to strip).

    val jsonDecoder: PostgresDecoder[String] = new PostgresDecoder[String]:
        def oids: Set[Int] = Set(OID_JSON, OID_JSONB)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): String =
            format match
                case Format.Binary if bytes.size > 0 && bytes(0) == 0x01.toByte =>
                    // JSONB binary: strip the version byte, decode the rest as UTF-8.
                    text(bytes.slice(1, bytes.size))
                case _ =>
                    // JSON text (OID 114), JSONB text fallback, or unrecognised binary: decode as-is.
                    text(bytes)

    // --- Bytea ---

    // Text format for bytea depends on the session's `bytea_output`, which is settable per session, per
    // database and per role, so both of its values are decoded here rather than only the default:
    //   - `hex` (the default since PG 9.0): `\x` followed by two hex digits per byte.
    //   - `escape`: printable bytes literally, everything else as a three-digit octal escape `\nnn`, and a
    //     backslash doubled. Returning those bytes as-is would decode `\001` as four ASCII characters
    //     rather than the one byte 0x01.
    val bytea: PostgresDecoder[Span[Byte]] = new PostgresDecoder[Span[Byte]]:
        def oids: Set[Int] = Set(OID_BYTEA)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): Span[Byte] = format match
            case Format.Binary => bytes
            case Format.Text   =>
                // The `\x` prefix separates the two renderings unambiguously: under `escape` a leading
                // backslash means the first byte was itself a backslash or was unprintable, and both of
                // those render with a backslash or a digit next, never an `x`.
                val s = text(bytes)
                if s.startsWith("\\x") then decodeByteaHex(s.drop(2))
                else decodeByteaEscape(s)

    /** Decodes the `bytea_output = hex` rendering, two hex digits per byte after the `\x` prefix.
      *
      * An odd digit count and a non-hex digit are both rejected: `grouped(2)` turns an odd payload into a final one-character group that
      * parses as a value half the byte's width, and `Integer.parseInt` raises an untyped `NumberFormatException` that no caller can match.
      */
    private def decodeByteaHex(hex: String)(using Frame): Span[Byte] =
        if hex.length % 2 != 0 then
            throw SqlDecodeByteaException(hex.length, SqlDecodeByteaException.Subtype.OddHexLength)
        end if
        val out = new Array[Byte](hex.length / 2)
        var i   = 0
        while i < out.length do
            val hi = hexDigit(hex.charAt(i * 2), hex.length)
            val lo = hexDigit(hex.charAt(i * 2 + 1), hex.length)
            out(i) = ((hi << 4) | lo).toByte
            i += 1
        end while
        Span.from(out)
    end decodeByteaHex

    private def hexDigit(c: Char, payloadLength: Int)(using Frame): Int =
        if c >= '0' && c <= '9' then c - '0'
        else if c >= 'a' && c <= 'f' then c - 'a' + 10
        else if c >= 'A' && c <= 'F' then c - 'A' + 10
        else throw SqlDecodeByteaException(payloadLength, SqlDecodeByteaException.Subtype.HexDigit)

    /** Decodes the `bytea_output = escape` rendering: `\\` is one backslash, `\nnn` is the byte with octal value `nnn`, and every other
      * character stands for its own UTF-8 bytes.
      *
      * The escapes are resolved over the UTF-8 BYTES rather than over the string's characters, because a non-ASCII literal byte in an
      * `escape` payload is not printable and therefore always arrives escaped, while the surrounding literal text may be multi-byte.
      */
    private def decodeByteaEscape(s: String)(using Frame): Span[Byte] =
        val in  = s.getBytes(StandardCharsets.UTF_8)
        val out = new Array[Byte](in.length)
        var i   = 0
        var n   = 0
        while i < in.length do
            if in(i) == '\\'.toByte then
                if i + 1 < in.length && in(i + 1) == '\\'.toByte then
                    out(n) = '\\'.toByte
                    n += 1
                    i += 2
                else if i + 3 < in.length && isOctalDigit(in(i + 1)) && isOctalDigit(in(i + 2)) && isOctalDigit(in(i + 3)) then
                    val value = (in(i + 1) - '0') * 64 + (in(i + 2) - '0') * 8 + (in(i + 3) - '0')
                    out(n) = value.toByte
                    n += 1
                    i += 4
                else
                    throw SqlDecodeByteaException(in.length, SqlDecodeByteaException.Subtype.EscapeSequence)
                end if
            else
                out(n) = in(i)
                n += 1
                i += 1
            end if
        end while
        Span.from(java.util.Arrays.copyOf(out, n))
    end decodeByteaEscape

    private def isOctalDigit(b: Byte): Boolean = b >= '0'.toByte && b <= '7'.toByte

    // --- Timestamptz, kyo.Instant ---
    // Uses kyo.Instant (preferred over java.time.Instant).

    val timestamptz: PostgresDecoder[kyo.Instant] = new PostgresDecoder[kyo.Instant]:
        def oids: Set[Int] = Set(OID_TIMESTAMPTZ)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): kyo.Instant = format match
            case Format.Binary =>
                val pgMicros    = readBigEndianLong(bytes, 0)
                val epochMicros = pgMicros + PostgresEncoder.PG_EPOCH_MICROS
                val secs        = epochMicros / 1_000_000L
                val nanos       = ((epochMicros % 1_000_000L) * 1_000L).toInt
                kyo.Instant.fromJava(java.time.Instant.ofEpochSecond(secs, nanos))
            case Format.Text =>
                val s = text(bytes)
                // PostgreSQL text format for timestamptz: "YYYY-MM-DD HH:MM:SS.ffffff+TZ"
                // Normalise to ISO-8601 by replacing the space separator with T.
                val iso = s.replace(" ", "T")
                // Handle PG offset format like "+00" → "+00:00"
                val fixedIso = if iso.matches(".*[+-]\\d{2}$") then iso + ":00" else iso
                // The parse is caught here rather than left to the row codec's catch-all, which reports an
                // untyped throw as SqlDecodeColumnDecodeException with no column index and no value in the
                // message. The typed leaf names the text that failed.
                try kyo.Instant.fromJava(java.time.OffsetDateTime.parse(fixedIso).toInstant)
                catch
                    case e: java.time.format.DateTimeParseException =>
                        throw SqlDecodeInstantException(s, e)
                end try

    // --- Date, java.time.LocalDate ---
    // No Kyo equivalent for LocalDate; java.time.LocalDate is used.

    val date: PostgresDecoder[java.time.LocalDate] = new PostgresDecoder[java.time.LocalDate]:
        def oids: Set[Int] = Set(OID_DATE)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): java.time.LocalDate = format match
            case Format.Binary =>
                val pgDays  = readBigEndianInt(bytes, 0)
                val pgEpoch = java.time.LocalDate.of(2000, 1, 1)
                pgEpoch.plusDays(pgDays.toLong)
            case Format.Text =>
                java.time.LocalDate.parse(text(bytes))

    // --- Timestamp (no tz), java.time.LocalDateTime ---
    // No Kyo equivalent for LocalDateTime; java.time.LocalDateTime is used.

    val timestamp: PostgresDecoder[java.time.LocalDateTime] = new PostgresDecoder[java.time.LocalDateTime]:
        def oids: Set[Int] = Set(OID_TIMESTAMP)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): java.time.LocalDateTime = format match
            case Format.Binary =>
                val pgMicros = readBigEndianLong(bytes, 0)
                val pgEpoch  = java.time.LocalDateTime.of(2000, 1, 1, 0, 0, 0)
                val secs     = pgMicros / 1_000_000L
                val nanos    = ((pgMicros % 1_000_000L) * 1_000L).toInt
                pgEpoch.plusSeconds(secs).plusNanos(nanos)
            case Format.Text =>
                val s = text(bytes).replace(" ", "T")
                java.time.LocalDateTime.parse(s)

    // --- Time, java.time.LocalTime ---
    // No Kyo equivalent for LocalTime; java.time.LocalTime is used.

    val time: PostgresDecoder[java.time.LocalTime] = new PostgresDecoder[java.time.LocalTime]:
        def oids: Set[Int] = Set(OID_TIME)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): java.time.LocalTime = format match
            case Format.Binary =>
                val micros = readBigEndianLong(bytes, 0)
                java.time.LocalTime.ofNanoOfDay(micros * 1_000L)
            case Format.Text =>
                java.time.LocalTime.parse(text(bytes))

    // --- Timetz, java.time.OffsetTime ---
    // Wire: 12-byte big-endian struct: Int64 microseconds-of-day, Int32 offset_seconds (negated).
    // PG wire convention: the offset field is the *negated* total seconds of the ZoneOffset so that
    // a UTC-05:00 value is stored as +18000.  We negate the wire value to recover the Java offset.
    // Text format: ISO-8601 extended, e.g. "13:45:30.123456+05:30"; parsed via OffsetTime.parse.

    val timetz: PostgresDecoder[java.time.OffsetTime] = new PostgresDecoder[java.time.OffsetTime]:
        def oids: Set[Int] = Set(OID_TIMETZ)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): java.time.OffsetTime = format match
            case Format.Binary =>
                val micros        = readBigEndianLong(bytes, 0)
                val offsetNegated = readBigEndianInt(bytes, 8)
                val localTime     = java.time.LocalTime.ofNanoOfDay(micros * 1_000L)
                val offset        = java.time.ZoneOffset.ofTotalSeconds(-offsetNegated)
                java.time.OffsetTime.of(localTime, offset)
            case Format.Text =>
                java.time.OffsetTime.parse(text(bytes))

    // --- INTERVAL, java.time.Duration ---
    // Wire: 16-byte big-endian struct: Int64 microseconds, Int32 days, Int32 months.
    // Months != 0 or days != 0 raise a SqlDecodeIntervalException; java.time.Duration cannot represent
    // calendar-relative components without data loss (e.g. DST-sensitive calendar days).
    // Text format: try ISO-8601 parse (java.time.Duration.parse); PG verbose format with
    // months/years raises a SqlDecodeIntervalException directing the caller to cast to ISO-formatted text.

    val interval: PostgresDecoder[java.time.Duration] = new PostgresDecoder[java.time.Duration]:
        def oids: Set[Int] = Set(OID_INTERVAL)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using frame: Frame): java.time.Duration = format match
            case Format.Binary =>
                val micros = readBigEndianLong(bytes, 0)
                val days   = readBigEndianInt(bytes, 8)
                val months = readBigEndianInt(bytes, 12)
                if months != 0 then
                    throw SqlDecodeIntervalException("months", months.toString)
                end if
                if days != 0 then
                    throw SqlDecodeIntervalException("days", days.toString)
                end if
                java.time.Duration.ofSeconds(micros / 1_000_000L, (micros % 1_000_000L) * 1_000L)
            case Format.Text =>
                val s = text(bytes)
                // Attempt ISO-8601 parse first (e.g. "PT1H", "PT-30S").
                // PG verbose format (e.g. "01:00:00", "1 year 2 mons ...") may also be received
                // in the simple-query path.
                try java.time.Duration.parse(s)
                catch
                    case _: java.time.format.DateTimeParseException =>
                        // PG verbose INTERVAL text (e.g. "01:30:00" or "1 year 2 mons 00:01:02").
                        // Attempt to parse hh:mm:ss as a fallback (no months/days, those would be
                        // data-losing for Duration). If the text does not match hh:mm:ss, raise a
                        // typed error suggesting the caller cast to ISO format.
                        val hhmmss = """^(-?)(\d+):(\d{2}):(\d{2})(?:\.(\d+))?$""".r
                        s match
                            case hhmmss(sign, hh, mm, ss, fracOrNull) =>
                                val totalSecs = hh.toLong * 3600L + mm.toLong * 60L + ss.toLong
                                val fracStr   = if fracOrNull == null then "" else fracOrNull
                                val nanos =
                                    if fracStr.isEmpty then 0L
                                    else
                                        // Pad or truncate to 9 digits for nanoseconds.
                                        val padded = (fracStr + "000000000").take(9)
                                        padded.toLong
                                val posDuration = java.time.Duration.ofSeconds(totalSecs, nanos)
                                if sign == "-" then posDuration.negated() else posDuration
                            case _ =>
                                throw SqlDecodeIntervalException("text", s)
                        end match
                end try

    // --- INTERVAL text renderings ---
    //
    // `IntervalStyle` decides which rendering the server writes a text-format INTERVAL in. It is settable per session,
    // per database and per role, and its four values write the same value four ways:
    //
    //   postgres (the default)  `-1 year -2 mons +3 days -04:05:06`
    //   postgres_verbose        `@ 1 year 2 mons 3 days 4 hours 5 mins 6 secs ago`
    //   sql_standard            `-1-2 +3 -4:05:06`
    //   iso_8601                `P1Y2M3DT4H5M6S`
    //
    // Every column of a simple query comes back in text format, so a `java.time.Period` field over an INTERVAL column
    // reads one of these on every `simpleQuery`. All four renderings are read here: reading only the ISO-8601 one
    // through `java.time.Period.parse` would refuse the default rendering of a well-formed value, and would have no lane
    // for the `T` part that writes the zero interval as `PT0S` under `iso_8601`.

    /** The components a PostgreSQL INTERVAL carries, whichever rendering spelled them out. */
    final private class IntervalFields(val months: Long, val days: Long, val micros: Long)

    private val MicrosPerSecond = 1_000_000L
    private val MicrosPerMinute = 60_000_000L
    private val MicrosPerHour   = 3_600_000_000L

    /** The `iso_8601` rendering: case-insensitive, a sign per component and an optional leading sign that negates every one
      * of them, all as `java.time.Period.parse` reads them, plus the `T` part that carries the time components.
      */
    private val IsoIntervalPattern =
        ("""(?i)([-+]?)P(?:([-+]?\d+)Y)?(?:([-+]?\d+)M)?(?:([-+]?\d+)W)?(?:([-+]?\d+)D)?""" +
            """(?:T(?:([-+]?\d+)H)?(?:([-+]?\d+)M)?(?:([-+]?\d+(?:\.\d+)?)S)?)?""").r

    /** The `HH:MM:SS[.ffffff]` time part the `postgres` and `sql_standard` renderings write the sub-day components as. */
    private val IntervalTimeToken = """([-+]?)(\d+):(\d{1,2})(?::(\d{1,2})(?:\.(\d+))?)?""".r

    /** The `sql_standard` year-month field, `1-2`, whose single sign covers both numbers. */
    private val IntervalYearMonthToken = """([-+]?)(\d+)-(\d+)""".r

    /** A bare count: the value half of every `<count> <unit>` pair, and the `sql_standard` day field, which has no unit word. */
    private val IntervalCountToken = """[-+]?\d+(?:\.\d+)?""".r

    /** One whole-number token of an INTERVAL rendering, `0` for a component the rendering left out. */
    private def intervalCount(token: String, s: String)(using Frame): Long =
        if token == null then 0L
        else
            try token.toLong
            catch case _: NumberFormatException => throw SqlDecodeIntervalException("text", s)

    /** The microseconds a fractional-seconds digit string carries, padded or truncated to the six digits an INTERVAL keeps. */
    private def intervalFractionMicros(digits: String): Long =
        if digits == null || digits.isEmpty then 0L
        else (digits + "000000").substring(0, 6).toLong

    /** The microseconds a seconds count carries, `6`, `6.5` and `-0.000001` alike.
      *
      * The sign is taken off before the whole and fractional halves are split, because the whole half of `-0.5` is the
      * unsigned zero `toLong` reads and the sign would be lost with it.
      */
    private def intervalSecondsMicros(token: String, s: String)(using Frame): Long =
        val negative  = token.startsWith("-")
        val unsigned  = if negative || token.startsWith("+") then token.substring(1) else token
        val dot       = unsigned.indexOf('.')
        val whole     = if dot < 0 then unsigned else unsigned.substring(0, dot)
        val fraction  = if dot < 0 then 0L else intervalFractionMicros(unsigned.substring(dot + 1))
        val magnitude = Math.addExact(Math.multiplyExact(intervalCount(whole, s), MicrosPerSecond), fraction)
        if negative then Math.negateExact(magnitude) else magnitude
    end intervalSecondsMicros

    /** Reads a text-format INTERVAL into the months, days and sub-day microseconds it carries, in any rendering
      * `IntervalStyle` produces.
      *
      * The arithmetic is exact throughout, so no total wraps into a plausible wrong value, and the `ArithmeticException`
      * an overflow raises instead becomes the typed leaf here rather than escaping as the unchecked JDK exception no
      * caller can match.
      */
    private def readIntervalText(s: String)(using Frame): IntervalFields =
        try
            s.trim match
                case IsoIntervalPattern(sign, y, mo, w, d, h, mi, sec)
                    if y != null || mo != null || w != null || d != null || h != null || mi != null || sec != null =>
                    val totalMonths = Math.addExact(Math.multiplyExact(intervalCount(y, s), 12L), intervalCount(mo, s))
                    val totalDays   = Math.addExact(intervalCount(d, s), Math.multiplyExact(intervalCount(w, s), 7L))
                    val micros = Math.addExact(
                        Math.addExact(
                            Math.multiplyExact(intervalCount(h, s), MicrosPerHour),
                            Math.multiplyExact(intervalCount(mi, s), MicrosPerMinute)
                        ),
                        if sec == null then 0L else intervalSecondsMicros(sec, s)
                    )
                    if sign == "-" then
                        new IntervalFields(Math.negateExact(totalMonths), Math.negateExact(totalDays), Math.negateExact(micros))
                    else new IntervalFields(totalMonths, totalDays, micros)
                case trimmed => readIntervalWords(trimmed, s)
        catch case _: ArithmeticException => throw SqlDecodeIntervalException("text", s)
        end try
    end readIntervalText

    /** Reads the three renderings that spell their components out: `postgres`, `postgres_verbose` and `sql_standard`.
      *
      * They share one token stream: a `<count> <unit>` pair per component, a bare count for the `sql_standard` day field,
      * an `HH:MM:SS` time part, the `1-2` year-month field, and the `@` and `ago` markers `postgres_verbose` wraps the
      * whole value in. Unit words are read in both the plural and the singular form the server writes, `2 mons` and
      * `1 mon` alike, and each component carries its own sign, which is how the default style renders a mixed-sign value.
      */
    private def readIntervalWords(trimmed: String, s: String)(using Frame): IntervalFields =
        val tokens = trimmed.toLowerCase.split("\\s+")
        var months = 0L
        var days   = 0L
        var micros = 0L
        var negate = false
        var i      = 0
        while i < tokens.length do
            tokens(i) match
                case "@" =>
                    i += 1
                case "ago" =>
                    // `postgres_verbose` renders a negative value as a positive one closed by `ago`.
                    negate = true
                    i += 1
                case IntervalTimeToken(sign, hh, mm, ss, frac) =>
                    val magnitude = Math.addExact(
                        Math.addExact(
                            Math.multiplyExact(intervalCount(hh, s), MicrosPerHour),
                            Math.multiplyExact(intervalCount(mm, s), MicrosPerMinute)
                        ),
                        Math.addExact(Math.multiplyExact(intervalCount(ss, s), MicrosPerSecond), intervalFractionMicros(frac))
                    )
                    micros = Math.addExact(micros, if sign == "-" then Math.negateExact(magnitude) else magnitude)
                    i += 1
                case IntervalYearMonthToken(sign, years, mons) =>
                    val magnitude = Math.addExact(Math.multiplyExact(intervalCount(years, s), 12L), intervalCount(mons, s))
                    months = Math.addExact(months, if sign == "-" then Math.negateExact(magnitude) else magnitude)
                    i += 1
                case count if IntervalCountToken.matches(count) =>
                    val unit = if i + 1 < tokens.length then tokens(i + 1) else ""
                    unit match
                        case "year" | "years" =>
                            months = Math.addExact(months, Math.multiplyExact(intervalCount(count, s), 12L))
                            i += 2
                        case "mon" | "mons" | "month" | "months" =>
                            months = Math.addExact(months, intervalCount(count, s))
                            i += 2
                        case "day" | "days" =>
                            days = Math.addExact(days, intervalCount(count, s))
                            i += 2
                        case "hour" | "hours" =>
                            micros = Math.addExact(micros, Math.multiplyExact(intervalCount(count, s), MicrosPerHour))
                            i += 2
                        case "min" | "mins" | "minute" | "minutes" =>
                            micros = Math.addExact(micros, Math.multiplyExact(intervalCount(count, s), MicrosPerMinute))
                            i += 2
                        case "sec" | "secs" | "second" | "seconds" =>
                            micros = Math.addExact(micros, intervalSecondsMicros(count, s))
                            i += 2
                        case _ =>
                            // `sql_standard` writes its day field as a bare count with no unit word after it.
                            days = Math.addExact(days, intervalCount(count, s))
                            i += 1
                    end match
                case _ =>
                    throw SqlDecodeIntervalException("text", s)
            end match
        end while
        if negate then new IntervalFields(Math.negateExact(months), Math.negateExact(days), Math.negateExact(micros))
        else new IntervalFields(months, days, micros)
    end readIntervalWords

    /** The `java.time.Period` an INTERVAL's fields describe, in the same normalised year/month split the binary arm returns.
      *
      * A non-zero time component is refused exactly as the binary arm refuses a non-zero `microseconds` field: `Period` has
      * no lane for it, and dropping it would silently change the value. A months or days total past what a `Period` field
      * holds is refused rather than wrapped, which leaves `Period.of(0, months, days).normalized()` with no overflow of its
      * own, its years argument being zero.
      */
    private def periodOf(fields: IntervalFields)(using Frame): java.time.Period =
        if fields.micros != 0L then throw SqlDecodeIntervalException("microseconds", fields.micros.toString)
        else if fields.months < Int.MinValue.toLong || fields.months > Int.MaxValue.toLong then
            throw SqlDecodeIntervalException("months", fields.months.toString)
        else if fields.days < Int.MinValue.toLong || fields.days > Int.MaxValue.toLong then
            throw SqlDecodeIntervalException("days", fields.days.toString)
        else java.time.Period.of(0, fields.months.toInt, fields.days.toInt).normalized()

    // --- INTERVAL, java.time.Period ---
    // Wire: 16-byte big-endian struct: Int64 microseconds, Int32 days, Int32 months.
    // Period has no time component, microseconds must be zero; non-zero raises a SqlDecodeIntervalException.
    // Text format: every rendering IntervalStyle produces is read (see the notes above), and a non-zero time part
    // raises the same SqlDecodeIntervalException the binary arm raises for a non-zero microseconds field.

    val intervalPeriod: PostgresDecoder[java.time.Period] = new PostgresDecoder[java.time.Period]:
        def oids: Set[Int] = Set(OID_INTERVAL)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using frame: Frame): java.time.Period = format match
            case Format.Binary =>
                val micros = readBigEndianLong(bytes, 0)
                val days   = readBigEndianInt(bytes, 8)
                val months = readBigEndianInt(bytes, 12)
                if micros != 0L then
                    throw SqlDecodeIntervalException("microseconds", micros.toString)
                end if
                java.time.Period.of(0, months, days).normalized()
            case Format.Text =>
                val s = text(bytes)
                periodOf(readIntervalText(s))

    // --- UUID ---
    // Binary: 16 bytes big-endian (mostSignificantBits Int64, leastSignificantBits Int64).
    // Text: canonical 36-character hyphenated form (e.g. "550e8400-e29b-41d4-a716-446655440000").

    val uuid: PostgresDecoder[java.util.UUID] = new PostgresDecoder[java.util.UUID]:
        def oids: Set[Int] = Set(OID_UUID)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using frame: Frame): java.util.UUID = format match
            case Format.Binary =>
                if bytes.size != 16 then
                    throw SqlDecodeUuidException(bytes.size)
                end if
                val msb = readBigEndianLong(bytes, 0)
                val lsb = readBigEndianLong(bytes, 8)
                new java.util.UUID(msb, lsb)
            case Format.Text =>
                java.util.UUID.fromString(text(bytes))

    // --- PG array decoders ---
    //
    // These parse a PostgreSQL array column value with [[PostgresArrayReader]] and return a [[Chunk]] of
    // decoded elements. Both wire formats are handled, by the reader and by the element decodes alike:
    // `arr.elementFormat` is the array's own format, and passing a hardcoded Binary there would feed a text
    // rendering's digits to a big-endian integer read. The element OID likewise comes from the array's
    // header rather than from the element type this decoder happens to be for.

    /** Builds an array decoder that reads each element through `elemDecoder` in the array's own wire format, mirroring
      * [[PostgresEncoder.arrayEncoder]]. `typeName` names the element type in an absent-element error; `arrayOid` is the OID the decoder
      * claims. The element format and OID both come from the array header the reader parsed, never from `elemDecoder`.
      */
    private def arrayDecoder[A](elemDecoder: PostgresDecoder[A], typeName: String, arrayOid: Int): PostgresDecoder[Chunk[A]] =
        new PostgresDecoder[Chunk[A]]:
            def oids: Set[Int] = Set(arrayOid)
            def read(format: Format, bytes: Span[Byte], columnOid: Int)(using frame: Frame): Chunk[A] =
                val arr     = new PostgresArrayReader(bytes, format, frame)
                val count   = arr.openArray()
                val builder = Chunk.newBuilder[A]
                var i       = 0
                while i < count do
                    arr.nextElement() match
                        case Maybe.Present(elemBytes) =>
                            builder += elemDecoder.read(arr.elementFormat, elemBytes, arr.elementOid)
                        case Maybe.Absent =>
                            throw SqlDecodeArrayAbsentElementException(typeName, i)
                    end match
                    i += 1
                end while
                builder.result()
            end read

    /** Decodes a PostgreSQL `int4[]` (OID 1007) column into a [[Chunk[Int]]]. */
    val int4Array: PostgresDecoder[Chunk[Int]] = arrayDecoder(int4, "Int", PostgresEncoder.OID_INT4_ARRAY)

    /** Decodes a PostgreSQL `text[]` (OID 1009) column into a [[Chunk[String]]]. */
    val textArray: PostgresDecoder[Chunk[String]] = arrayDecoder(textDecoder, "String", PostgresEncoder.OID_TEXT_ARRAY)

    /** Decodes a PostgreSQL `jsonb[]` (OID 3807) column into a [[Chunk[String]]]. Each element is decoded by [[jsonDecoder]], the 1-byte
      * JSONB version prefix (0x01) is stripped and the remainder returned as a UTF-8 JSON-text string.
      */
    val jsonbArray: PostgresDecoder[Chunk[String]] = arrayDecoder(jsonDecoder, "String", PostgresEncoder.OID_JSONB_ARRAY)

end PostgresDecoder
