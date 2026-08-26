package kyo.internal.mysql

import java.nio.charset.StandardCharsets
import kyo.Frame
import kyo.Span
import kyo.SqlCodec.Format
import kyo.SqlDecodeInsufficientBytesException
import kyo.SqlDecodeNumericException
import kyo.SqlDecodeValueRangeException
import kyo.internal.SqlNumericDecode
import kyo.internal.SqlNumericDecode.wholeOf
import kyo.internal.mysql.types.MysqlEncoder

/** Decodes a MySQL numeric column value by what the column carries, never by what the Scala type asked for.
  *
  * Three things decide how the bytes read, and getting any of them wrong is silent rather than an error:
  *
  *   - The [[Format]]. The binary protocol writes a fixed-width little-endian integer or IEEE-754 field; the text protocol writes the value's
  *     ASCII rendering. Parsing the ASCII digits of `1234` as a little-endian `LONG` yields 875770417, and the ASCII `0` of a false boolean is
  *     byte 0x30, which is nonzero as a byte and zero as a number.
  *   - The column type, under the binary protocol, because widths collide: `LONG` and `FLOAT` are both four bytes, `LONGLONG` and `DOUBLE` are
  *     both eight, and `DECIMAL` / `NEWDECIMAL` arrive as ASCII text even there.
  *   - The UNSIGNED flag, which says whether the value's top bit is a sign or part of the magnitude.
  *
  * The last two arrive as a [[MysqlColumnToken]]. An [[MysqlColumnToken.Unspecified]] token leaves the value's byte width as the only signal
  * and the column treated as signed.
  *
  * `BIT(n)` is the one type that escapes the format branch entirely: it is a big-endian byte string under both protocols, so a text-protocol
  * `BIT` is raw bytes rather than digits and a binary-protocol one is big-endian rather than little.
  *
  * The exact targets ([[byte]], [[short]], [[int]], [[long]], [[bigInt]], [[bigDecimal]]) widen silently and raise
  * [[kyo.SqlDecodeValueRangeException]] on a value they cannot carry, because a wrap or a truncation would be a plausible wrong number. The
  * approximate targets ([[float]], [[double]]) round, which is what those types are for.
  */
private[kyo] object MysqlNumericDecoder:

    // --- Public surface, one per Scala target ---

    /** MySQL's own truthiness: any nonzero numeric value is true.
      *
      * Reads through [[decimalValueOf]] rather than through the integer reader, because truthiness is a property of the column's VALUE and
      * every other target already resolves the wire kind before reading. Going straight to the little-endian integer reader would reinterpret
      * three kinds: a binary `DECIMAL`, which arrives as its ASCII rendering, would make `0.00` read as 0x30302E30 and answer true; a `FLOAT`
      * or `DOUBLE` would be read as its bit pattern, so `-0.0` would answer true; and a `BIGINT UNSIGNED` above 2^63 would abort as out of
      * `Long` range for a question whose answer has room for every value.
      *
      * The unknown-wire fallback is [[Wire.Integer]]: an unspecified token leaves the byte width as the only signal, and guessing
      * `DECIMAL` there would parse binary integer bytes as ASCII.
      */
    def boolean(bytes: Span[Byte], format: Format, token: Int)(using Frame): Boolean =
        decimalValueOf(bytes, format, token, Wire.Integer, "Boolean").signum != 0

    def byte(bytes: Span[Byte], format: Format, token: Int)(using Frame): Byte =
        narrow(integralValueOf(bytes, format, token, "Byte"), Byte.MinValue.toLong, Byte.MaxValue.toLong, "Byte").toByte

    def short(bytes: Span[Byte], format: Format, token: Int)(using Frame): Short =
        narrow(integralValueOf(bytes, format, token, "Short"), Short.MinValue.toLong, Short.MaxValue.toLong, "Short").toShort

    def int(bytes: Span[Byte], format: Format, token: Int)(using Frame): Int =
        narrow(integralValueOf(bytes, format, token, "Int"), Int.MinValue.toLong, Int.MaxValue.toLong, "Int").toInt

    def long(bytes: Span[Byte], format: Format, token: Int)(using Frame): Long =
        integralValueOf(bytes, format, token, "Long")

    def float(bytes: Span[Byte], format: Format, token: Int)(using Frame): Float =
        approximateValueOf(bytes, format, token, Wire.Float4, "Float").toFloat

    def double(bytes: Span[Byte], format: Format, token: Int)(using Frame): Double =
        approximateValueOf(bytes, format, token, Wire.Float8, "Double")

    def bigDecimal(bytes: Span[Byte], format: Format, token: Int)(using Frame): BigDecimal =
        decimalValueOf(bytes, format, token, Wire.Decimal, "BigDecimal")

    def bigInt(bytes: Span[Byte], format: Format, token: Int)(using Frame): BigInt =
        val value = decimalValueOf(bytes, format, token, Wire.Decimal, "BigInt")
        if value.isWhole then value.toBigInt
        else throw SqlDecodeValueRangeException("BigInt", value.toString, "numeric column")
    end bigInt

    // --- Wire resolution ---

    /** Which representation a numeric column value carries under the binary protocol. */
    private enum Wire derives CanEqual:
        case Integer, Float4, Float8, Decimal, Rendering

    /** Resolves a binary-protocol column's representation from its type byte.
      *
      * A string-family column resolves to [[Wire.Rendering]]: a number stored in one is its own text rendering under both protocols, so a
      * numeric field over one parses the digits. Without that case the digits are read as a little-endian integer of whatever width the value
      * happens to have, which for `'hello'` is a five-byte value no integer width recognises. It is also what makes the failure one a caller
      * can act on: parsing reports [[kyo.SqlDecodeNumericException]] naming the offending value, which is what the same read reports on
      * PostgreSQL.
      *
      * `whenUnknown` covers an [[MysqlColumnToken.Unspecified]] token and any type byte in neither set, both of which leave only the value's
      * byte width to go on. It is deliberately not a rejection: a `BIT` column reads correctly by width today and would start failing for no
      * gain against the mismatch class this resolution closes.
      */
    private def wireOf(token: Int, whenUnknown: Wire): Wire =
        if !MysqlColumnToken.isSpecified(token) then whenUnknown
        else if MysqlColumnToken.isTextColumn(token) then Wire.Rendering
        else
            MysqlColumnToken.columnType(token) match
                case MysqlEncoder.TYPE_TINY | MysqlEncoder.TYPE_SHORT | MysqlEncoder.TYPE_INT24 | MysqlEncoder.TYPE_LONG |
                    MysqlEncoder.TYPE_LONGLONG | MysqlEncoder.TYPE_YEAR =>
                    Wire.Integer
                case MysqlEncoder.TYPE_FLOAT                                  => Wire.Float4
                case MysqlEncoder.TYPE_DOUBLE                                 => Wire.Float8
                case MysqlEncoder.TYPE_DECIMAL | MysqlEncoder.TYPE_NEWDECIMAL => Wire.Decimal
                case _                                                        => whenUnknown

    /** The exact integral value a numeric column carries, in either wire format. */
    private def integralValueOf(bytes: Span[Byte], format: Format, token: Int, scalaType: String)(using Frame): Long =
        if isBits(token) then readBitsBigEndian(bytes, scalaType)
        else integralValueByFormat(bytes, format, token, scalaType)

    private def integralValueByFormat(bytes: Span[Byte], format: Format, token: Int, scalaType: String)(using Frame): Long = format match
        case Format.Text => wholeOf(parseDecimalText(bytes), scalaType, "text column")
        case Format.Binary =>
            wireOf(token, Wire.Integer) match
                case Wire.Integer   => readIntegerBinary(bytes, token, scalaType)
                case Wire.Float4    => wholeOf(BigDecimal(readFloat4LE(bytes).toDouble), scalaType, "FLOAT column")
                case Wire.Float8    => wholeOf(BigDecimal(readFloat8LE(bytes)), scalaType, "DOUBLE column")
                case Wire.Decimal   => wholeOf(parseDecimalText(bytes), scalaType, "DECIMAL column")
                case Wire.Rendering => wholeOf(parseDecimalText(bytes), scalaType, "text column")

    /** The approximate value a numeric column carries, for the `Float` and `Double` targets. */
    private def approximateValueOf(bytes: Span[Byte], format: Format, token: Int, whenUnknown: Wire, scalaType: String)(using
        Frame
    ): Double =
        if isBits(token) then readBitsBigEndian(bytes, scalaType).toDouble
        else approximateValueByFormat(bytes, format, token, whenUnknown, scalaType)

    private def approximateValueByFormat(bytes: Span[Byte], format: Format, token: Int, whenUnknown: Wire, scalaType: String)(using
        Frame
    ): Double = format match
        case Format.Text => parseDoubleText(bytes)
        case Format.Binary =>
            wireOf(token, whenUnknown) match
                case Wire.Integer   => unsignedDecimalOf(bytes, token, scalaType).toDouble
                case Wire.Float4    => readFloat4LE(bytes).toDouble
                case Wire.Float8    => readFloat8LE(bytes)
                case Wire.Decimal   => parseDecimalText(bytes).toDouble
                case Wire.Rendering => parseDoubleText(bytes)

    /** The exact decimal value a numeric column carries, for the `BigDecimal`, `BigInt` and `Boolean` targets.
      *
      * `whenUnknown` is the wire kind assumed for an [[MysqlColumnToken.Unspecified]] token, where the byte width is the only signal. The
      * exact-decimal targets guess `DECIMAL`, because that is the column type they are usually asked about; [[boolean]] guesses `Integer`,
      * because guessing `DECIMAL` for a truthiness read would parse an integer's bytes as an ASCII rendering.
      */
    private def decimalValueOf(bytes: Span[Byte], format: Format, token: Int, whenUnknown: Wire, scalaType: String)(using
        Frame
    ): BigDecimal =
        if isBits(token) then BigDecimal(readBitsBigEndian(bytes, scalaType))
        else decimalValueByFormat(bytes, format, token, whenUnknown, scalaType)

    private def decimalValueByFormat(bytes: Span[Byte], format: Format, token: Int, whenUnknown: Wire, scalaType: String)(using
        Frame
    ): BigDecimal =
        format match
            case Format.Text => parseDecimalText(bytes)
            case Format.Binary =>
                wireOf(token, whenUnknown) match
                    case Wire.Integer   => unsignedDecimalOf(bytes, token, scalaType)
                    case Wire.Float4    => BigDecimal(readFloat4LE(bytes).toDouble)
                    case Wire.Float8    => BigDecimal(readFloat8LE(bytes))
                    case Wire.Decimal   => parseDecimalText(bytes)
                    case Wire.Rendering => parseDecimalText(bytes)

    // --- BIT, the one column type that is neither an ASCII rendering nor a little-endian integer ---

    private def isBits(token: Int): Boolean =
        MysqlColumnToken.isSpecified(token) && MysqlColumnToken.columnType(token) == MysqlEncoder.TYPE_BIT

    /** Reads a `BIT(n)` column, whose value is a big-endian byte string of `ceil(n / 8)` bytes under BOTH wire formats.
      *
      * It is resolved before the format branch for exactly that reason. A text-protocol `BIT` is raw bytes and not digits, so parsing it as an
      * ASCII rendering fails on the zero byte, and a binary-protocol `BIT` wider than one byte read little-endian gives the byte-reversed value.
      */
    private def readBitsBigEndian(bytes: Span[Byte], scalaType: String)(using Frame): Long =
        if bytes.isEmpty || bytes.size > 8 then
            throw SqlDecodeValueRangeException(scalaType, s"${bytes.size} bytes", "BIT column")
        end if
        var acc = 0L
        var i   = 0
        while i < bytes.size do
            acc = (acc << 8) | (bytes(i) & 0xffL)
            i += 1
        end while
        acc
    end readBitsBigEndian

    // --- Binary reads, width from the column ---

    /** Reads a binary-protocol integer column at whatever width its type gave it, as the `Long` that carries all of them.
      *
      * Little-endian truncation to a narrower Scala type takes the LOW bytes, so unlike a big-endian high-word read it is correct for values
      * that fit and wraps modulo 2^32 or 2^16 for those that do not. Both outcomes are silent, which is why the read widens to `Long` rather
      * than truncating.
      *
      * A `BIGINT UNSIGNED` above 2^63 has no `Long` to widen into, so it is reported rather than wrapped negative. [[unsignedDecimalOf]] is the
      * path for the targets with room to carry it.
      *
      * The width the value is read AT is still its own byte count, which is what makes an unspecified column readable at all;
      * [[checkDeclaredWidth]] is what stops that count from standing in for a width the column never had.
      */
    private def readIntegerBinary(bytes: Span[Byte], token: Int, scalaType: String)(using Frame): Long =
        checkDeclaredWidth(bytes, token, scalaType)
        val unsigned = MysqlColumnToken.isUnsigned(token)
        bytes.size match
            case 1 => if unsigned then bytes(0) & 0xffL else bytes(0).toLong
            case 2 => if unsigned then readInt2LE(bytes) & 0xffffL else readInt2LE(bytes).toLong
            case 4 => if unsigned then readInt4LE(bytes) & 0xffffffffL else readInt4LE(bytes).toLong
            case 8 =>
                val value = readInt8LE(bytes)
                if unsigned && value < 0L then
                    throw SqlDecodeValueRangeException(scalaType, unsignedMagnitude(value).toString, "BIGINT UNSIGNED column")
                else value
            case n =>
                throw SqlDecodeValueRangeException(scalaType, s"$n bytes", "integer column of unrecognised wire width")
        end match
    end readIntegerBinary

    /** Refuses a value whose byte count is not the width the binary protocol gives its column's declared type.
      *
      * Dispatching on the byte count alone cannot tell a payload of the wrong width from a column of another type, so without this check the
      * two halves of one wire corruption behave differently on a column the server declared `SHORT`: a one-byte value would land on the
      * `TINY` arm and answer a number, while a three-byte one would land on no arm and be reported. `BinaryResultsetRowUnmarshaller` reads a
      * fixed count per column type (1 for `TINY`, 2 for `SHORT` and `YEAR`, 4 for `INT24` and `LONG`, 8 for `LONGLONG`), so neither payload
      * is one that column can carry, and the column's own type is the only thing that separates them from a value of the type whose width
      * they happen to match.
      *
      * A column whose declared type is not a fixed-width integer, and an [[MysqlColumnToken.Unspecified]] one, name no width to check
      * against and leave the value's own byte count as the only signal, which is the fallback [[wireOf]] describes.
      */
    private def checkDeclaredWidth(bytes: Span[Byte], token: Int, scalaType: String)(using Frame): Unit =
        if MysqlColumnToken.isSpecified(token) then
            MysqlColumnToken.columnType(token) match
                case MysqlEncoder.TYPE_TINY                           => requireWidth(bytes, 1, scalaType)
                case MysqlEncoder.TYPE_SHORT | MysqlEncoder.TYPE_YEAR => requireWidth(bytes, 2, scalaType)
                case MysqlEncoder.TYPE_INT24 | MysqlEncoder.TYPE_LONG => requireWidth(bytes, 4, scalaType)
                case MysqlEncoder.TYPE_LONGLONG                       => requireWidth(bytes, 8, scalaType)
                case _                                                => ()

    private def requireWidth(bytes: Span[Byte], declared: Int, scalaType: String)(using Frame): Unit =
        if bytes.size != declared then
            throw SqlDecodeValueRangeException(scalaType, s"${bytes.size} bytes", s"integer column declared $declared bytes wide")

    /** The full magnitude of an unsigned integer column, for the targets with room for it.
      *
      * The eight-byte arm reads the value itself rather than going through [[readIntegerBinary]], so it runs the declared-width check on its
      * own; every other width reaches the check through that reader.
      */
    private def unsignedDecimalOf(bytes: Span[Byte], token: Int, scalaType: String)(using Frame): BigDecimal =
        if MysqlColumnToken.isUnsigned(token) && bytes.size == 8 then
            checkDeclaredWidth(bytes, token, scalaType)
            BigDecimal(unsignedMagnitude(readInt8LE(bytes)))
        else BigDecimal(readIntegerBinary(bytes, token, scalaType))

    private val TwoToThe64 = BigInt(1) << 64

    /** Reinterprets a 64-bit wire value as unsigned. `BigInt` is the only Scala integral type with room for the top of the range, and the
      * arithmetic is spelled out rather than delegated so it behaves identically on the JVM, on Node, and on Native.
      */
    private def unsignedMagnitude(value: Long): BigInt =
        if value >= 0L then BigInt(value) else TwoToThe64 + BigInt(value)

    private def readInt2LE(bytes: Span[Byte])(using Frame): Short =
        if bytes.size < 2 then throw SqlDecodeInsufficientBytesException("SHORT", 2, bytes.size, 0)
        val lo = bytes(0) & 0xff
        val hi = bytes(1) & 0xff
        ((hi << 8) | lo).toShort
    end readInt2LE

    private def readInt4LE(bytes: Span[Byte])(using Frame): Int =
        if bytes.size < 4 then throw SqlDecodeInsufficientBytesException("LONG", 4, bytes.size, 0)
        val b0 = bytes(0).toLong & 0xffL
        val b1 = bytes(1).toLong & 0xffL
        val b2 = bytes(2).toLong & 0xffL
        val b3 = bytes(3).toLong & 0xffL
        (b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)).toInt
    end readInt4LE

    private def readInt8LE(bytes: Span[Byte])(using Frame): Long =
        if bytes.size < 8 then throw SqlDecodeInsufficientBytesException("LONGLONG", 8, bytes.size, 0)
        val b0 = bytes(0).toLong & 0xffL
        val b1 = bytes(1).toLong & 0xffL
        val b2 = bytes(2).toLong & 0xffL
        val b3 = bytes(3).toLong & 0xffL
        val b4 = bytes(4).toLong & 0xffL
        val b5 = bytes(5).toLong & 0xffL
        val b6 = bytes(6).toLong & 0xffL
        val b7 = bytes(7).toLong & 0xffL
        b0 | (b1 << 8) | (b2 << 16) | (b3 << 24) | (b4 << 32) | (b5 << 40) | (b6 << 48) | (b7 << 56)
    end readInt8LE

    private def readFloat4LE(bytes: Span[Byte])(using Frame): Float =
        if bytes.size != 4 then throw SqlDecodeInsufficientBytesException("FLOAT", 4, bytes.size, 0)
        else java.lang.Float.intBitsToFloat(readInt4LE(bytes))

    private def readFloat8LE(bytes: Span[Byte])(using Frame): Double =
        if bytes.size != 8 then throw SqlDecodeInsufficientBytesException("DOUBLE", 8, bytes.size, 0)
        else java.lang.Double.longBitsToDouble(readInt8LE(bytes))

    // --- Text reads ---

    /** Parses the ASCII rendering the text protocol uses for every value. */
    private def parseDecimalText(bytes: Span[Byte])(using Frame): BigDecimal =
        SqlNumericDecode.parseDecimalText(new String(bytes.toArray, StandardCharsets.UTF_8))

    /** Parses a text-format value for an approximate target, which unlike [[parseDecimalText]] accepts the special values MySQL renders for a
      * `DOUBLE`.
      */
    private def parseDoubleText(bytes: Span[Byte])(using Frame): Double =
        val s = new String(bytes.toArray, StandardCharsets.UTF_8)
        try s.toDouble
        catch
            case _: NumberFormatException =>
                throw SqlDecodeNumericException(s, SqlDecodeNumericException.Subtype.Parse)
        end try
    end parseDoubleText

    // --- Exact narrowing ---

    private def narrow(value: Long, min: Long, max: Long, scalaType: String)(using Frame): Long =
        if value >= min && value <= max then value
        else throw SqlDecodeValueRangeException(scalaType, value.toString, "numeric column")

end MysqlNumericDecoder
