package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.Codec
import kyo.Frame
import kyo.Span
import kyo.SqlDecodeDurationException
import kyo.SqlDecodeEmptyStringForCharException
import kyo.SqlDecodeInstantException
import kyo.SqlDecodeInsufficientBytesException
import kyo.SqlDecodeNumericException
import kyo.SqlUnsupportedStructuralReadException
import kyo.internal.postgres.types.Format
import kyo.internal.postgres.types.PostgresDecoder

/** A [[Codec.Reader]] that decodes a single buffered column value for use by sum-codec patterns (e.g. sealed traits, `Result`, `Either`).
  *
  * Produced by [[kyo.internal.postgres.PostgresRowReader#captureValue]] and [[kyo.internal.mysql.MysqlRowReader#captureValue]]. It holds
  * the raw wire bytes for one SQL column and decodes primitive values from them using either the PostgreSQL binary-format decoders or the
  * MySQL little-endian protocol.
  *
  * Structural reads (arrayStart, mapStart) raise [[kyo.SqlDecodeException]] because nested structural reads through captureValue are not
  * supported, the captured value is always a flat scalar column.
  *
  * @param rawBytes
  *   the buffered wire bytes for the column
  * @param format
  *   the wire format used by the row (Binary for extended-query, Text for simple-query)
  * @param backend
  *   [[BufferedSqlReader.Backend.Postgres]] or [[BufferedSqlReader.Backend.Mysql]]
  * @param captureFrame
  *   the frame captured at the captureValue call site
  */
final class BufferedSqlReader(
    rawBytes: Span[Byte],
    format: Format,
    backend: BufferedSqlReader.Backend,
    captureFrame: Frame
) extends Codec.Reader:

    override def frame: Frame = captureFrame

    // --- Nil check ---

    override def isNil(): Boolean = rawBytes.isEmpty

    // --- Primitive reads ---

    override def string(): String =
        new String(rawBytes.toArray, StandardCharsets.UTF_8)

    override def int(): Int = backend match
        case BufferedSqlReader.Backend.Postgres =>
            PostgresDecoder.int4.read(format, rawBytes)(using captureFrame)
        case BufferedSqlReader.Backend.Mysql =>
            if rawBytes.size < 4 then throw SqlDecodeInsufficientBytesException("LONG", 4, rawBytes.size, 0)(using captureFrame)
            val b0 = rawBytes(0).toLong & 0xffL
            val b1 = rawBytes(1).toLong & 0xffL
            val b2 = rawBytes(2).toLong & 0xffL
            val b3 = rawBytes(3).toLong & 0xffL
            (b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)).toInt

    override def long(): Long = backend match
        case BufferedSqlReader.Backend.Postgres =>
            PostgresDecoder.int8.read(format, rawBytes)(using captureFrame)
        case BufferedSqlReader.Backend.Mysql =>
            if rawBytes.size < 8 then throw SqlDecodeInsufficientBytesException("LONGLONG", 8, rawBytes.size, 0)(using captureFrame)
            val b0 = rawBytes(0).toLong & 0xffL
            val b1 = rawBytes(1).toLong & 0xffL
            val b2 = rawBytes(2).toLong & 0xffL
            val b3 = rawBytes(3).toLong & 0xffL
            val b4 = rawBytes(4).toLong & 0xffL
            val b5 = rawBytes(5).toLong & 0xffL
            val b6 = rawBytes(6).toLong & 0xffL
            val b7 = rawBytes(7).toLong & 0xffL
            b0 | (b1 << 8) | (b2 << 16) | (b3 << 24) | (b4 << 32) | (b5 << 40) | (b6 << 48) | (b7 << 56)

    override def float(): Float = backend match
        case BufferedSqlReader.Backend.Postgres =>
            PostgresDecoder.float4.read(format, rawBytes)(using captureFrame)
        case BufferedSqlReader.Backend.Mysql =>
            if rawBytes.size < 4 then throw SqlDecodeInsufficientBytesException("FLOAT", 4, rawBytes.size, 0)(using captureFrame)
            val b0 = rawBytes(0).toLong & 0xffL
            val b1 = rawBytes(1).toLong & 0xffL
            val b2 = rawBytes(2).toLong & 0xffL
            val b3 = rawBytes(3).toLong & 0xffL
            java.lang.Float.intBitsToFloat((b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)).toInt)

    override def double(): Double = backend match
        case BufferedSqlReader.Backend.Postgres =>
            PostgresDecoder.float8.read(format, rawBytes)(using captureFrame)
        case BufferedSqlReader.Backend.Mysql =>
            if rawBytes.size < 8 then throw SqlDecodeInsufficientBytesException("DOUBLE", 8, rawBytes.size, 0)(using captureFrame)
            val b0 = rawBytes(0).toLong & 0xffL
            val b1 = rawBytes(1).toLong & 0xffL
            val b2 = rawBytes(2).toLong & 0xffL
            val b3 = rawBytes(3).toLong & 0xffL
            val b4 = rawBytes(4).toLong & 0xffL
            val b5 = rawBytes(5).toLong & 0xffL
            val b6 = rawBytes(6).toLong & 0xffL
            val b7 = rawBytes(7).toLong & 0xffL
            java.lang.Double.longBitsToDouble(
                b0 | (b1 << 8) | (b2 << 16) | (b3 << 24) | (b4 << 32) | (b5 << 40) | (b6 << 48) | (b7 << 56)
            )

    override def boolean(): Boolean = backend match
        case BufferedSqlReader.Backend.Postgres =>
            PostgresDecoder.bool.read(format, rawBytes)(using captureFrame)
        case BufferedSqlReader.Backend.Mysql =>
            if rawBytes.isEmpty then throw SqlDecodeInsufficientBytesException("TINY", 1, 0, 0)(using captureFrame)
            (rawBytes(0) & 0xff) != 0

    override def short(): Short = backend match
        case BufferedSqlReader.Backend.Postgres =>
            PostgresDecoder.int2.read(format, rawBytes)(using captureFrame)
        case BufferedSqlReader.Backend.Mysql =>
            if rawBytes.size < 2 then throw SqlDecodeInsufficientBytesException("SHORT", 2, rawBytes.size, 0)(using captureFrame)
            val lo = rawBytes(0) & 0xff
            val hi = rawBytes(1) & 0xff
            ((hi << 8) | lo).toShort

    override def byte(): Byte = backend match
        case BufferedSqlReader.Backend.Postgres =>
            PostgresDecoder.int2.read(format, rawBytes)(using captureFrame).toByte
        case BufferedSqlReader.Backend.Mysql =>
            if rawBytes.isEmpty then throw SqlDecodeInsufficientBytesException("TINY", 1, 0, 0)(using captureFrame)
            rawBytes(0)

    override def char(): Char =
        val s = string()
        if s.isEmpty then throw SqlDecodeEmptyStringForCharException(-1)(using captureFrame)
        else s.charAt(0)
    end char

    override def bytes(): Span[Byte] = backend match
        case BufferedSqlReader.Backend.Postgres =>
            PostgresDecoder.bytea.read(format, rawBytes)(using captureFrame)
        case BufferedSqlReader.Backend.Mysql =>
            rawBytes

    override def bigDecimal(): BigDecimal =
        val s = string()
        try BigDecimal(s)
        catch
            case _: NumberFormatException =>
                throw SqlDecodeNumericException(s, SqlDecodeNumericException.Subtype.Parse)(using captureFrame)
        end try
    end bigDecimal

    override def bigInt(): BigInt = bigDecimal().toBigInt

    override def instant(): java.time.Instant =
        val s = string()
        try java.time.Instant.parse(s)
        catch
            case e: java.time.format.DateTimeParseException =>
                throw SqlDecodeInstantException(s, e)(using captureFrame)
        end try
    end instant

    override def duration(): java.time.Duration =
        val s = string()
        try java.time.Duration.parse(s)
        catch
            case e: java.time.format.DateTimeParseException =>
                throw SqlDecodeDurationException(s, e)(using captureFrame)
        end try
    end duration

    override def skip(): Unit = ()

    // --- Field-iteration protocol, not applicable to a buffered scalar ---

    override def objectStart(): Int = 0
    override def objectEnd(): Unit  = ()

    override def hasNextField(): Boolean = false

    override def fieldParse(): Unit = ()

    override def matchField(nameBytes: Array[Byte]): Boolean = false

    override def lastFieldName(): String = ""

    override def captureValue(): Codec.Reader = this

    // --- Structural reads, not supported through captureValue ---

    private def structuralUnsupported(method: String): Nothing =
        throw SqlUnsupportedStructuralReadException(method)(using captureFrame)

    override def arrayStart(): Int         = structuralUnsupported("arrayStart")
    override def arrayEnd(): Unit          = structuralUnsupported("arrayEnd")
    override def hasNextElement(): Boolean = structuralUnsupported("hasNextElement")
    override def mapStart(): Int           = structuralUnsupported("mapStart")
    override def mapEnd(): Unit            = structuralUnsupported("mapEnd")
    override def hasNextEntry(): Boolean   = structuralUnsupported("hasNextEntry")
    override def field(): String           = structuralUnsupported("field")

end BufferedSqlReader

object BufferedSqlReader:
    sealed trait Backend derives CanEqual
    object Backend:
        case object Postgres extends Backend
        case object Mysql    extends Backend
    end Backend
end BufferedSqlReader
