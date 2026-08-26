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
import kyo.SqlDecodeColumnTypeMismatchException
import kyo.SqlDecodeException
import kyo.SqlDecodeInstantException
import kyo.SqlDecodeIntervalException
import kyo.SqlDecodeNumericException
import kyo.SqlDecodeUuidException
import kyo.SqlDecodeValueRangeException
import kyo.internal.SqlNumericDecode.parseDecimalText
import kyo.internal.SqlNumericDecode.wholeOf
import kyo.internal.postgres.PostgresArrayReader
import kyo.internal.postgres.PostgresDialect
import kyo.internal.postgres.PostgresRowCodec

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
    /** OIDs this decoder recognises. Read once per guarded column, so implementations hold it rather than rebuilding it. */
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
        val oids: Set[Int] = Set(OID_BOOL)
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
        val oids: Set[Int] = Set(OID_INT2)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): Short = format match
            case Format.Binary => narrowToShort(integralValueOf(bytes, columnOid, "Short"), "numeric column")
            case Format.Text   => narrowToShort(wholeOf(parseDecimalText(text(bytes)), "Short", "text"), "text")

    // --- Int ---

    val int4: PostgresDecoder[Int] = new PostgresDecoder[Int]:
        val oids: Set[Int] = Set(OID_INT4)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): Int = format match
            case Format.Binary => narrowToInt(integralValueOf(bytes, columnOid, "Int"), "numeric column")
            case Format.Text   => narrowToInt(wholeOf(parseDecimalText(text(bytes)), "Int", "text"), "text")

    // --- Long ---

    val int8: PostgresDecoder[Long] = new PostgresDecoder[Long]:
        val oids: Set[Int] = Set(OID_INT8)
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
        val oids: Set[Int] = Set(OID_FLOAT4)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): Float = format match
            case Format.Binary => approximateValueOf(bytes, columnOid, NumericWire.Float4, "Float").toFloat
            case Format.Text   => text(bytes).toFloat

    // --- Float8 ---

    val float8: PostgresDecoder[Double] = new PostgresDecoder[Double]:
        val oids: Set[Int] = Set(OID_FLOAT8)
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
        val oids: Set[Int] = Set(OID_NUMERIC)
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

    /** The PostgreSQL name of `columnOid` when it names a type whose bytes a text read would reinterpret rather than render.
      *
      * A text read of such a column returns the protocol buffer reinterpreted as UTF-8: `int4` 42 is `00 00 00 2A`, `date` is an `int4`
      * day count, `jsonb` carries a leading version byte. None of that is the value's rendering, and none of it fails on its own, since
      * every byte sequence is some string.
      *
      * The answer comes from [[kyo.internal.postgres.PostgresRowCodec]]'s one type table rather than a second list here, so what this
      * refuses and what [[kyo.SqlRow.columnKind]] and [[kyo.SqlRow.columnTypeName]] report cannot drift apart. `json` (OID 114) is
      * text-readable there and so is not refused: its wire form is the document text in both formats. An OID the table does not name is
      * not refused either, for the reason [[numericWireOf]] does not refuse one: the dynamic OIDs (`citext`, an enum type, a domain over
      * text) are text-shaped and read correctly today, and a token with no known meaning is not evidence of a mismatch.
      */
    private[kyo] def nonTextColumnType(columnOid: Int): Maybe[String] =
        PostgresRowCodec.nonTextColumnType(columnOid)

    /** Refuses a text read of a column whose type is not text, naming the Scala type that asked.
      *
      * The guard the text decode owes its caller: a text read is the one every byte sequence satisfies, so without it the target type
      * cannot refuse anything.
      */
    private[kyo] def requireTextColumn(scalaType: String, columnOid: Int, columnName: Maybe[String] = Maybe.empty)(using Frame): Unit =
        nonTextColumnType(columnOid).foreach { columnType =>
            throw SqlDecodeColumnTypeMismatchException(scalaType, PostgresDialect.id, columnType, columnOid.toString, columnName)
        }

    /** The column types a numeric read may take.
      *
      * Wider than one decoder's own OID, deliberately: an `Int` field is satisfied by every integral width, a `Double` by both float
      * widths, and any of them by the text family, whose values ARE their digits under both wire formats. Those widenings are the reason
      * the numeric reads are not checked against `decoder.oids` the way the declared reads are.
      *
      * `varchar` (1043) and `bpchar` (1042) are spelled as literals for the same reason they are in the decoders: nothing encodes to them,
      * so they have no [[PostgresEncoder]] constant.
      */
    private[kyo] val numericFamilyOids: Set[Int] =
        Set(OID_INT2, OID_INT4, OID_INT8, OID_FLOAT4, OID_FLOAT8, OID_NUMERIC, OID_TEXT, 1043, 1042)

    /** The same set plus `bool`, for a `Boolean` read: its own column type is the one target outside the numeric family. Held rather
      * than unioned at the call, which would allocate on every read.
      */
    private[kyo] val numericOrBoolOids: Set[Int] = numericFamilyOids + OID_BOOL

    /** Refuses a numeric read of a NAMED column outside the numeric and text families.
      *
      * The wire dispatch resolves an OID it does not recognise to the target's own family, which is what keeps the by-width reads
      * (`oid`, `xid`, `regclass`) working and what the two-argument `read` overload relies on. For a column this backend names, that
      * fallback is not a widening but a misread: a `date` is four big-endian bytes exactly as an `int4` is, so an `Int` over one answers
      * the day count since the PostgreSQL epoch rather than failing. Unnamed OIDs stay permissive for the reason
      * [[requireAcceptedColumn]] documents.
      *
      * `accepted` is [[numericFamilyOids]], or [[numericOrBoolOids]] for a `Boolean` read, whose own column type is the one target
      * outside that family.
      */
    private[kyo] def requireNumericColumn(scalaType: String, columnOid: Int, columnName: Maybe[String], accepted: Set[Int])(
        using Frame
    ): Unit =
        requireAcceptedColumn(scalaType, accepted, columnOid, columnName)

    /** Refuses a read whose column is a type this backend names and the decoder does not accept.
      *
      * The same guard as [[requireTextColumn]] for every read that is not a text read. Those resolve their wire layout from the target
      * type rather than from the column, so a `date` decoder handed an `int4` column reads the four bytes as a day count and answers a
      * well-formed wrong date: 42 becomes 2000-02-12, with nothing for the caller to notice. Checking the column against what the
      * decoder claims is what turns that into a typed refusal.
      *
      * Silent on two inputs, deliberately. `OID_UNSPECIFIED` is what a nested value carries (an array element, a range bound), where the
      * column's own OID describes the container rather than the element. An OID this backend does not name is not refused either, for the
      * reason [[numericWireOf]] does not refuse one: a token with no known meaning is not evidence of a mismatch, and the dynamic OIDs
      * (`citext`, an enum type, a domain) live there.
      *
      * NOT used by the numeric reads. Those widen on purpose, an `Int` field over a `sum(int4)` that comes back `int8` being the ordinary
      * case, and [[numericWireOf]] already resolves their representation from the column.
      */
    private[kyo] def requireAcceptedColumn(
        scalaType: String,
        accepted: Set[Int],
        columnOid: Int,
        columnName: Maybe[String] = Maybe.empty
    )(using Frame): Unit =
        if columnOid != PostgresEncoder.OID_UNSPECIFIED && !accepted.contains(columnOid) then
            PostgresRowCodec.typeNameOf(columnOid).foreach { columnType =>
                throw SqlDecodeColumnTypeMismatchException(scalaType, PostgresDialect.id, columnType, columnOid.toString, columnName)
            }

    val textDecoder: PostgresDecoder[String] = new PostgresDecoder[String]:
        // Accepts text OID, varchar OID (1043), and bpchar OID (1042).
        val oids: Set[Int] = Set(OID_TEXT, 1043, 1042)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using Frame): String =
            requireTextColumn("String", columnOid)
            text(bytes)
        end read

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
        val oids: Set[Int] = Set(OID_JSON, OID_JSONB)
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
        val oids: Set[Int] = Set(OID_BYTEA)
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
        val oids: Set[Int] = Set(OID_TIMESTAMPTZ)
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
        val oids: Set[Int] = Set(OID_DATE)
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
        val oids: Set[Int] = Set(OID_TIMESTAMP)
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
        val oids: Set[Int] = Set(OID_TIME)
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
        val oids: Set[Int] = Set(OID_TIMETZ)
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
        val oids: Set[Int] = Set(OID_INTERVAL)
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
        val oids: Set[Int] = Set(OID_INTERVAL)
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

    // --- INTERVAL, rendered as ISO-8601 text ---
    // Wire: the same 16-byte struct the two decoders above read, rendered rather than converted.
    // Text format: the server already sent a rendering, whichever IntervalStyle it was configured for, so it is the answer.

    /** Any INTERVAL as ISO-8601 text, which is the one reading every interval has.
      *
      * [[interval]] reads the value as a `java.time.Duration` and [[intervalPeriod]] as a `java.time.Period`, and each refuses what the
      * other carries: a `Duration` has no lane for months or calendar days, a `Period` none for a time part, and neither holds an
      * interval with both, which `interval '1 day 3 hours'` is. Rendering the wire fields is what lets [[kyo.SqlRow.text]] answer for
      * every interval rather than for two halves of the range, and it is a rendering rather than a decode, so nothing has to be dropped.
      *
      * The rendering is the one PostgreSQL's own `iso_8601` IntervalStyle produces: a sign per component, `PT0S` for the zero interval,
      * and fractional seconds only when there are any.
      */
    val intervalText: PostgresDecoder[String] = new PostgresDecoder[String]:
        val oids: Set[Int] = Set(OID_INTERVAL)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using frame: Frame): String = format match
            case Format.Binary =>
                renderIso(readBigEndianInt(bytes, 12).toLong, readBigEndianInt(bytes, 8).toLong, readBigEndianLong(bytes, 0))
            case Format.Text =>
                // The one column whose text rendering is NOT simply handed back. Which of the four renderings the
                // server sends is a session setting, so passing the bytes through would make the same stored value
                // read differently under the two protocols, and differently again after someone changes
                // `IntervalStyle`. Parsing and re-rendering is what keeps one value reading as one string.
                val fields = readIntervalText(text(bytes))
                renderIso(fields.months, fields.days, fields.micros)

    /** The ISO-8601 rendering of an INTERVAL's three fields, as PostgreSQL's `iso_8601` IntervalStyle writes them. */
    private def renderIso(months: Long, days: Long, micros: Long): String =
        val years     = months / 12
        val monthPart = months  % 12
        val hours     = micros / MicrosPerHour
        val minutes   = (micros % MicrosPerHour) / MicrosPerMinute
        val subMinute = micros  % MicrosPerMinute
        val seconds   = subMinute / MicrosPerSecond
        val fraction  = Math.abs(subMinute % MicrosPerSecond)
        def unit(value: Long, suffix: Char): String =
            if value == 0 then "" else s"$value$suffix"
        val secondsPart =
            if seconds == 0 && fraction == 0 then ""
            else
                // The sign lives on the seconds when they are zero and the fraction is not, since `0.5` and `-0.5`
                // share a whole part and an interval's fields are negative together.
                val sign = if seconds == 0 && subMinute < 0 then "-" else ""
                val frac = if fraction == 0 then "" else "." + f"$fraction%06d".reverse.dropWhile(_ == '0').reverse
                s"$sign$seconds${frac}S"
        val date = unit(years, 'Y') + unit(monthPart, 'M') + unit(days, 'D')
        val time = unit(hours, 'H') + unit(minutes, 'M') + secondsPart
        if date.isEmpty && time.isEmpty then "PT0S"
        else if time.isEmpty then s"P$date"
        else s"P${date}T$time"
    end renderIso

    // --- INET, rendered as the address it holds ---
    // Wire: family (2 = IPv4, 3 = IPv6), netmask bits, is_cidr, address length in bytes, then the address.
    // Text format: the server already sent its own rendering, so it is the answer.

    private val AF_INET = 2

    /** An INET as the address it holds, in PostgreSQL's own output form.
      *
      * `inet` has no Scala type in this module, so a caller with no row type reaches it through [[kyo.SqlRow.text]], and a text decode
      * refuses it because the wire value is a struct rather than characters. Rendering that struct is what makes the column readable at
      * all: the alternative, handing back its bytes as UTF-8, is mojibake for every address.
      *
      * The mask is written only when it is not the full one, and an IPv6 address is compressed at its longest run of zero groups
      * (leftmost on a tie, and never a run of one), which is RFC 5952 and is what the server's own text rendering of the same value says.
      */
    val inetText: PostgresDecoder[String] = new PostgresDecoder[String]:
        val oids: Set[Int] = Set(OID_INET)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using frame: Frame): String = format match
            case Format.Binary => renderInet(bytes)
            case Format.Text   => text(bytes)

    /** The address an INET's wire struct describes, masked as PostgreSQL writes it. */
    private def renderInet(bytes: Span[Byte]): String =
        val family = bytes(0) & 0xff
        val bits   = bytes(1) & 0xff
        val length = bytes(3) & 0xff
        if family == AF_INET then
            val quad = (0 until length).map(i => (bytes(4 + i) & 0xff).toString).mkString(".")
            if bits == 32 then quad else s"$quad/$bits"
        else
            val groups = (0 until 8).map(g => ((bytes(4 + g * 2) & 0xff) << 8) | (bytes(4 + g * 2 + 1) & 0xff))
            val text   = compressIpv6(groups)
            if bits == 128 then text else s"$text/$bits"
        end if
    end renderInet

    /** The eight groups of an IPv6 address as RFC 5952 writes them: lowercase, no leading zeros, and the longest run of zero groups
      * replaced by `::`, leftmost on a tie. A run of ONE is left alone, since `::` is no shorter than `0` and the RFC forbids it.
      */
    private def compressIpv6(groups: IndexedSeq[Int]): String =
        var bestStart = -1
        var bestLen   = 0
        var i         = 0
        while i < 8 do
            if groups(i) != 0 then i += 1
            else
                var end = i
                while end < 8 && groups(end) == 0 do end += 1
                if end - i > bestLen then
                    bestLen = end - i
                    bestStart = i
                end if
                i = end
        end while
        def hex(from: Int, until: Int): String =
            (from until until).map(g => Integer.toHexString(groups(g))).mkString(":")
        if bestLen < 2 then hex(0, 8)
        else s"${hex(0, bestStart)}::${hex(bestStart + bestLen, 8)}"
    end compressIpv6

    // --- Server-shaped text renderings ---
    //
    // `SqlRow.text` answers one string for one stored value whichever protocol carried the row. Under the text protocol the server
    // already sent its own rendering and the bytes are handed back; under the binary protocol the value has to be rendered here, and the
    // rendering that agrees is the server's own, not the JDK's. They differ for whole column types rather than at the edges: PostgreSQL
    // writes a bool `t`, a timestamp `2026-08-25 10:00:00`, and a float8 1e10 `10000000000`, where Java writes `true`,
    // `2026-08-25T10:00`, and `1.0E10`.
    //
    // The special values are here for the same reason. `numeric 'NaN'` and `date 'infinity'` have no Scala counterpart, so decoding at a
    // type either refuses them (numeric) or answers a plausible wrong value (a date near year 5881610, which is what `plusDays` on
    // Int.MaxValue lands on), while the server renders each as a word.

    /** `t` or `f`, which is what `boolout` writes. */
    val boolText: PostgresDecoder[String] = new PostgresDecoder[String]:
        val oids: Set[Int] = Set(OID_BOOL)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using frame: Frame): String = format match
            case Format.Binary => if bool.read(format, bytes, columnOid) then "t" else "f"
            case Format.Text   => text(bytes)

    /** A `date`, with `infinity` and `-infinity` rendered as the server writes them rather than decoded. */
    val dateText: PostgresDecoder[String] = new PostgresDecoder[String]:
        val oids: Set[Int] = Set(OID_DATE)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using frame: Frame): String = format match
            case Format.Binary =>
                readBigEndianInt(bytes, 0) match
                    case Int.MaxValue => "infinity"
                    case Int.MinValue => "-infinity"
                    case _            => renderLocalDate(date.read(format, bytes, columnOid))
            case Format.Text => text(bytes)

    /** A `timestamp`, space-separated with the seconds always written and the fraction trimmed, which is `timestamp_out`. */
    val timestampText: PostgresDecoder[String] = new PostgresDecoder[String]:
        val oids: Set[Int] = Set(OID_TIMESTAMP)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using frame: Frame): String = format match
            case Format.Binary =>
                readBigEndianLong(bytes, 0) match
                    case Long.MaxValue => "infinity"
                    case Long.MinValue => "-infinity"
                    case _             => renderLocalDateTime(timestamp.read(format, bytes, columnOid))
            case Format.Text => text(bytes)

    /** A `timestamptz`, rendered at UTC with the `+00` offset the server writes when its TimeZone is UTC. */
    val timestamptzText: PostgresDecoder[String] = new PostgresDecoder[String]:
        val oids: Set[Int] = Set(OID_TIMESTAMPTZ)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using frame: Frame): String = format match
            case Format.Binary =>
                readBigEndianLong(bytes, 0) match
                    case Long.MaxValue => "infinity"
                    case Long.MinValue => "-infinity"
                    case _ =>
                        val instant = timestamptz.read(format, bytes, columnOid).toJava
                        val utc     = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC)
                        renderLocalDateTime(utc) + "+00"
            case Format.Text => text(bytes)

    /** A `time`, seconds always written, which is `time_out`. */
    val timeText: PostgresDecoder[String] = new PostgresDecoder[String]:
        val oids: Set[Int] = Set(OID_TIME)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using frame: Frame): String = format match
            case Format.Binary => renderLocalTime(time.read(format, bytes, columnOid))
            case Format.Text   => text(bytes)

    /** A `timetz`, whose offset the server writes in hours, and in hours and minutes only when the minutes are not zero. */
    val timetzText: PostgresDecoder[String] = new PostgresDecoder[String]:
        val oids: Set[Int] = Set(OID_TIMETZ)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using frame: Frame): String = format match
            case Format.Binary =>
                val value = timetz.read(format, bytes, columnOid)
                renderLocalTime(value.toLocalTime) + renderOffset(value.getOffset)
            case Format.Text => text(bytes)

    /** A `numeric`, with the three special values rendered rather than refused. */
    val numericText: PostgresDecoder[String] = new PostgresDecoder[String]:
        val oids: Set[Int] = Set(OID_NUMERIC)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using frame: Frame): String = format match
            case Format.Binary =>
                // The sign field of the wire header carries the special values, so they are read before the digits are.
                val sign = if bytes.size >= 8 then readBigEndianShort(bytes, 4).toInt & 0xffff else 0
                sign match
                    case 0xc000 => "NaN"
                    case 0xd000 => "Infinity"
                    case 0xf000 => "-Infinity"
                    // toPlainString, not toString: BigDecimal takes exponent notation once the adjusted exponent is
                    // below -6, and `numeric_out` never does, so a numeric holding 0.0000001 rendered `1E-7` under
                    // the binary protocol against the server's own `0.0000001`.
                    case _ => numeric.read(format, bytes, columnOid).bigDecimal.toPlainString
                end match
            case Format.Text => text(bytes)

    /** A `float4`, read at its own width so the value is not widened before it is rendered. */
    val float4Text: PostgresDecoder[String] = new PostgresDecoder[String]:
        val oids: Set[Int] = Set(OID_FLOAT4)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using frame: Frame): String = format match
            case Format.Binary =>
                val value = float4.read(format, bytes, columnOid)
                if value.isNaN then "NaN"
                else if value.isInfinite then (if value > 0 then "Infinity" else "-Infinity")
                else renderFloating(shortestFloat4(value), isNegative(value.toDouble), Float4Digits)
            case Format.Text => text(bytes)

    /** A `float8`. */
    val float8Text: PostgresDecoder[String] = new PostgresDecoder[String]:
        val oids: Set[Int] = Set(OID_FLOAT8)
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using frame: Frame): String = format match
            case Format.Binary =>
                val value = float8.read(format, bytes, columnOid)
                if value.isNaN then "NaN"
                else if value.isInfinite then (if value > 0 then "Infinity" else "-Infinity")
                else renderFloating(shortestFloat8(value), isNegative(value), Float8Digits)
            case Format.Text => text(bytes)

    /** `YYYY-MM-DD HH:MM:SS[.ffffff]`, the shape `timestamp_out` writes: a space rather than `T`, the seconds always present, and the
      * fraction only when it is not zero, with its trailing zeros trimmed.
      */
    private def renderLocalDateTime(value: java.time.LocalDateTime): String =
        val date = value.toLocalDate
        val body = f"${renderYmd(date)}%s ${renderLocalTime(value.toLocalTime)}%s"
        // The era trails the whole value, after the time, which is where `timestamp_out` puts it.
        if date.getYear <= 0 then s"$body BC" else body
    end renderLocalDateTime

    /** `YYYY-MM-DD`, with the year counted within its era rather than proleptically: `date_out` spells 1 BC as `0001`, where
      * `LocalDate` numbers that year 0 and 44 BC as -43.
      *
      * `LocalDate.toString` also prefixes a `+` to any year of five digits or more, which the server does not write, so a year-10000
      * date rendered `+10000-01-01` against the server's `10000-01-01`. Both are legal values: PostgreSQL's date range runs from
      * 4713 BC to 5874897 AD.
      */
    private def renderYmd(value: java.time.LocalDate): String =
        val year = value.getYear
        val era  = if year <= 0 then 1 - year else year
        f"$era%04d-${value.getMonthValue}%02d-${value.getDayOfMonth}%02d"
    end renderYmd

    /** `YYYY-MM-DD`, with `BC` appended for a year in that era, which is how `date_out` writes one. */
    private def renderLocalDate(value: java.time.LocalDate): String =
        if value.getYear <= 0 then s"${renderYmd(value)} BC" else renderYmd(value)

    /** `HH:MM:SS[.ffffff]`. Java omits the seconds when they are zero and pads the fraction to a multiple of three digits; the server
      * does neither.
      */
    private def renderLocalTime(value: java.time.LocalTime): String =
        val base  = f"${value.getHour}%02d:${value.getMinute}%02d:${value.getSecond}%02d"
        val nanos = value.getNano
        if nanos == 0 then base
        else
            // The server keeps microsecond resolution, so the fraction is six digits before its trailing zeros go.
            val micros = f"${nanos / 1000}%06d".reverse.dropWhile(_ == '0').reverse
            s"$base.$micros"
        end if
    end renderLocalTime

    /** `+HH`, widening to `+HH:MM` and then `+HH:MM:SS` as each field turns out to be needed, which is how the server writes a `timetz`
      * offset.
      *
      * The seconds field is not vestigial: PostgreSQL accepts a second-precision zone and `timetz_out` writes it back, so
      * `12:00:00+05:30:33` is a value a column can hold and dropping its `:33` would render a different instant's offset.
      */
    private def renderOffset(offset: java.time.ZoneOffset): String =
        val total = offset.getTotalSeconds
        val sign  = if total < 0 then "-" else "+"
        val abs   = Math.abs(total)
        val hours = abs / 3600
        val mins  = (abs % 3600) / 60
        val secs  = abs  % 60
        if secs != 0 then f"$sign%s$hours%02d:$mins%02d:$secs%02d"
        else if mins != 0 then f"$sign%s$hours%02d:$mins%02d"
        else f"$sign%s$hours%02d"
        end if
    end renderOffset

    /** Where the server leaves plain notation for each float width: the type's significant-digit count, `FLT_DIG` and `DBL_DIG`. */
    private val Float4Digits = 6
    private val Float8Digits = 15

    /** The precisions a shortest-decimal search walks, allocated once: a `float` needs at most 9 significant digits to round-trip and a
      * `double` at most 17.
      */
    private val precisions: Array[java.math.MathContext] =
        Array.tabulate(18)(p => new java.math.MathContext(if p == 0 then 1 else p))

    /** Whether a value carries a minus sign, negative zero included, which is the case a comparison against zero misses. */
    private def isNegative(value: Double): Boolean =
        value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)

    /** The shortest decimal that reads back as the same `float`.
      *
      * `Float.toString` answers this on the JVM but not on every platform this module builds for: Scala.js widens to a double first and
      * prints that, so 12345.6f arrives as `12345.599609375` and 1e-4f as `9.999999747378752e-05`. Searching upward from one significant
      * digit for the first that round-trips is the same answer on every platform, and it is the answer the server's own shortest-decimal
      * output is computed from.
      */
    private def shortestFloat4(value: Float): java.math.BigDecimal =
        val exact = new java.math.BigDecimal(value.toDouble)
        var p     = 1
        var found = null: java.math.BigDecimal
        while found == null && p <= 9 do
            val candidate = exact.round(precisions(p))
            if candidate.floatValue == value then found = candidate
            p += 1
        end while
        (if found == null then exact else found).stripTrailingZeros
    end shortestFloat4

    /** The shortest decimal that reads back as the same `double`, by the same search [[shortestFloat4]] runs. */
    private def shortestFloat8(value: Double): java.math.BigDecimal =
        val exact = new java.math.BigDecimal(value)
        var p     = 1
        var found = null: java.math.BigDecimal
        while found == null && p <= 17 do
            val candidate = exact.round(precisions(p))
            if candidate.doubleValue == value then found = candidate
            p += 1
        end while
        (if found == null then exact else found).stripTrailingZeros
    end shortestFloat8

    /** Renders a floating value the way `float4out` and `float8out` do: plain notation while the decimal exponent is at least -4 and
      * below `digits`, and `d.ddde[+-]NN` outside that band, with the exponent padded to at least two digits.
      *
      * `digits` is the type's significant-digit count, which is where the server switches: [[Float4Digits]] for a `float4` and
      * [[Float8Digits]] for a `float8`. The two widths do not share it, so a `float4` 1e6 renders `1e+06` where a `float8` 1e6 renders
      * `1000000`.
      *
      * `value` carries the shortest round-tripping digits and `negative` its sign, which are taken from the value rather than from a
      * `toString`: the two platforms this module builds for do not spell a float the same way, and one of them drops the sign of a
      * negative zero entirely.
      *
      * The digits are the shortest that round-trip, and for a handful of values the server writes different ones for the same stored
      * double: it renders 1e23 as `9.999999999999999e+22` under the default `extra_float_digits`, and as `1e+23` under
      * `extra_float_digits = 0`. Which of the two it picks is a session setting, and the server does not report that setting to the
      * connection, so no rendering computed here agrees with it for every session. The values it separates are those whose shortest
      * decimal form falls on an exact representable midpoint: six of the 5409 `d x 10^e` doubles across the type's whole exponent range.
      */
    private def renderFloating(value: java.math.BigDecimal, negative: Boolean, digits: Int): String =
        if value.signum == 0 then (if negative then "-0" else "0")
        else
            // The power of ten of the leading digit, which is what the server compares against `digits`.
            val exponent = value.precision - value.scale - 1
            if exponent >= -4 && exponent < digits then value.toPlainString
            else
                val sign = if exponent < 0 then "-" else "+"
                f"${value.movePointLeft(exponent).toPlainString}%se$sign%s${Math.abs(exponent)}%02d"
            end if
    end renderFloating

    // --- UUID ---
    // Binary: 16 bytes big-endian (mostSignificantBits Int64, leastSignificantBits Int64).
    // Text: canonical 36-character hyphenated form (e.g. "550e8400-e29b-41d4-a716-446655440000").

    val uuid: PostgresDecoder[java.util.UUID] = new PostgresDecoder[java.util.UUID]:
        val oids: Set[Int] = Set(OID_UUID)
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
    private def arrayDecoder[A](elemDecoder: PostgresDecoder[A], typeName: String, arrayOids: Set[Int]): PostgresDecoder[Chunk[A]] =
        new PostgresDecoder[Chunk[A]]:
            val oids: Set[Int] = arrayOids
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
    val int4Array: PostgresDecoder[Chunk[Int]] =
        // 1005 `int2[]` and 1016 `int8[]`: the element decode reads each element at the width the array header
        // names and range-checks it into an Int, so the narrower type is exact and the wider one refuses a value
        // that does not fit rather than truncating. `array_agg` over a bigint produces an int8[], which no other
        // read in the vocabulary covers.
        arrayDecoder(int4, "Int", Set(PostgresEncoder.OID_INT4_ARRAY, 1005, 1016))

    /** Decodes a PostgreSQL `text[]` (OID 1009) column into a [[Chunk[String]]]. */
    val textArray: PostgresDecoder[Chunk[String]] =
        // 1015 `varchar[]`, 1014 `bpchar[]` and 1003 `name[]`: all three carry their elements as the same UTF-8
        // bytes a `text` element carries, which is what `textDecoder` reads, so refusing them would refuse a
        // column that decodes exactly. They are spelled as literals here for the reason `varchar` and `bpchar`
        // are in the scalar decoders: nothing encodes to them, so they have no PostgresEncoder constant.
        arrayDecoder(textDecoder, "String", Set(PostgresEncoder.OID_TEXT_ARRAY, 1015, 1014, 1003))

    /** Decodes a PostgreSQL `jsonb[]` (OID 3807) column into a [[Chunk[String]]]. Each element is decoded by [[jsonDecoder]], the 1-byte
      * JSONB version prefix (0x01) is stripped and the remainder returned as a UTF-8 JSON-text string.
      */
    val jsonbArray: PostgresDecoder[Chunk[String]] =
        // 199 `json[]`: `jsonDecoder` strips the JSONB version byte only when the element actually carries one,
        // and a `json` element is the bare document, so both containers read through it unchanged.
        arrayDecoder(jsonDecoder, "String", Set(PostgresEncoder.OID_JSONB_ARRAY, 199))

end PostgresDecoder
