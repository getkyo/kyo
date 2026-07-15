package kyo.internal.bson

import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kyo.Chunk
import kyo.Codec
import kyo.OrderedMapBuilder
import kyo.ParseException
import kyo.RangeException
import kyo.Span
import kyo.Structure
import kyo.TruncatedInputException
import kyo.TypeMismatchException
import kyo.discard

final class BsonReader private (
    root: BsonValue,
    val frame: kyo.Frame,
    config: kyo.Bson.Config
) extends Codec.IntrospectingReader:
    import BsonValue.*

    private val KindDocument: 1 = 1
    private val KindArray: 2    = 2
    private type FrameKind = KindDocument.type | KindArray.type

    final private class ReadFrame(
        val kind: FrameKind,
        val fields: Chunk[(String, BsonValue)],
        val values: Vector[BsonValue],
        var index: Int
    )

    private var stack: List[ReadFrame]      = Nil
    private var current: Option[BsonValue]  = None
    private var parsedField: Option[String] = None

    resetLimits(config.maxDepth, config.maxCollectionSize)

    private def value: BsonValue = current.getOrElse(root)

    def objectStart(): Int =
        value match
            case DocumentValue(fields) =>
                checkDepth()
                checkCollectionSize(fields.size)
                stack = ReadFrame(KindDocument, fields.toChunk, Vector.empty, 0) :: stack
                current = None
                fields.size
            case other =>
                mismatch("document", other)
    end objectStart

    def objectEnd(): Unit =
        stack match
            case frame :: tail if frame.kind == KindDocument =>
                stack = tail
                decrementDepth()
                consumeCurrent()
            case _ =>
                parseError("document end")
    end objectEnd

    def arrayStart(): Int =
        value match
            case ArrayValue(values) =>
                checkDepth()
                checkCollectionSize(values.size)
                stack = ReadFrame(KindArray, Chunk.empty, values, 0) :: stack
                current = None
                values.size
            case other =>
                mismatch("array", other)
    end arrayStart

    def arrayEnd(): Unit =
        stack match
            case frame :: tail if frame.kind == KindArray =>
                stack = tail
                decrementDepth()
                consumeCurrent()
            case _ =>
                parseError("array end")
    end arrayEnd

    def field(): String =
        fieldParse()
        lastFieldName()
    end field

    def hasNextField(): Boolean =
        stack match
            case frame :: _ if frame.kind == KindDocument =>
                frame.index < frame.fields.size
            case _ => false
        end match
    end hasNextField

    def fieldParse(): Unit =
        stack match
            case frame :: _ if frame.kind == KindDocument && frame.index < frame.fields.size =>
                val (name, next) = frame.fields(frame.index)
                frame.index += 1
                parsedField = Some(name)
                current = Some(next)
            case _ =>
                parseError("field")
        end match
    end fieldParse

    def matchField(nameBytes: Array[Byte]): Boolean =
        parsedField match
            case Some(name) => java.util.Arrays.equals(name.getBytes(StandardCharsets.UTF_8), nameBytes)
            case None       => false
    end matchField

    def lastFieldName(): String = parsedField.getOrElse("")

    def hasNextElement(): Boolean =
        stack match
            case frame :: _ if frame.kind == KindArray =>
                if current.nonEmpty then true
                else if frame.index < frame.values.size then
                    current = Some(frame.values(frame.index))
                    frame.index += 1
                    true
                else false
            case _ => false
        end match
    end hasNextElement

    def string(): String =
        val result =
            value match
                case StringValue(value) => value
                case other              => mismatch("string", other)
        consumeCurrent()
        result
    end string

    def int(): Int =
        val result =
            value match
                case Int32Value(value) => value
                case Int64Value(value) =>
                    if value < Int.MinValue || value > Int.MaxValue then
                        throw RangeException(value, "Int", Int.MinValue.toLong, Int.MaxValue.toLong)(using frame)
                    value.toInt
                case other => mismatch("int", other)
        consumeCurrent()
        result
    end int

    def long(): Long =
        val result =
            value match
                case Int32Value(value) => value.toLong
                case Int64Value(value) => value
                case other             => mismatch("long", other)
        consumeCurrent()
        result
    end long

    def float(): Float =
        val result =
            value match
                case DoubleValue(value) => value.toFloat
                case other              => mismatch("float", other)
        consumeCurrent()
        result
    end float

    def double(): Double =
        val result =
            value match
                case DoubleValue(value) => value
                case other              => mismatch("double", other)
        consumeCurrent()
        result
    end double

    def boolean(): Boolean =
        val result =
            value match
                case BooleanValue(value) => value
                case other               => mismatch("boolean", other)
        consumeCurrent()
        result
    end boolean

    def short(): Short =
        val value = int()
        if value < Short.MinValue || value > Short.MaxValue then
            throw RangeException(value.toLong, "Short", Short.MinValue.toLong, Short.MaxValue.toLong)(using frame)
        value.toShort
    end short

    def byte(): Byte =
        val value = int()
        if value < Byte.MinValue || value > Byte.MaxValue then
            throw RangeException(value.toLong, "Byte", Byte.MinValue.toLong, Byte.MaxValue.toLong)(using frame)
        value.toByte
    end byte

    def char(): Char =
        val value = string()
        if value.length != 1 then mismatch("char", StringValue(value))
        value.charAt(0)
    end char

    def isNil(): Boolean =
        value match
            case NullValue =>
                consumeCurrent()
                true
            case _ => false
    end isNil

    def skip(): Unit =
        skipValue(value)
        consumeCurrent()
    end skip

    def mapStart(): Int = objectStart()

    def mapEnd(): Unit = objectEnd()

    def hasNextEntry(): Boolean = hasNextField()

    def bytes(): Span[Byte] =
        val result =
            value match
                case BinaryValue(value, _) => value
                case other                 => mismatch("binary", other)
        consumeCurrent()
        result
    end bytes

    def bigInt(): BigInt =
        val result =
            value match
                case Int32Value(value) => BigInt(value)
                case Int64Value(value) => BigInt(value)
                case StringValue(value) =>
                    try BigInt(value)
                    catch
                        case _: NumberFormatException =>
                            throw ParseException(kyo.Bson(config), value, "BigInt")(using frame)
                case other => mismatch("big integer", other)
        consumeCurrent()
        result
    end bigInt

    def bigDecimal(): BigDecimal =
        val result =
            value match
                case StringValue(value) =>
                    try BigDecimal(value)
                    catch
                        case _: NumberFormatException =>
                            throw ParseException(kyo.Bson(config), value, "BigDecimal")(using frame)
                case Int32Value(value) => BigDecimal(value)
                case Int64Value(value) => BigDecimal(value)
                case DoubleValue(value) =>
                    if !java.lang.Double.isFinite(value) then
                        throw ParseException(kyo.Bson(config), value.toString, "finite BigDecimal")(using frame)
                    BigDecimal(value)
                case Decimal128Value(value) => value
                case other                  => mismatch("decimal", other)
        consumeCurrent()
        result
    end bigDecimal

    def instant(): java.time.Instant =
        val result =
            value match
                case DateTimeValue(value) => value
                case other                => mismatch("datetime", other)
        consumeCurrent()
        result
    end instant

    def duration(): java.time.Duration =
        val result =
            value match
                case DocumentValue(fields) =>
                    val map = fields.toMap
                    (map.get("seconds"), map.get("nanos")) match
                        case (Some(Int64Value(seconds)), Some(Int32Value(nanos))) =>
                            java.time.Duration.ofSeconds(seconds, nanos.toLong)
                        case (Some(Int32Value(seconds)), Some(Int32Value(nanos))) =>
                            java.time.Duration.ofSeconds(seconds.toLong, nanos.toLong)
                        case _ =>
                            mismatch("duration document", value)
                    end match
                case Int64Value(nanos) =>
                    java.time.Duration.ofNanos(nanos)
                case other =>
                    mismatch("duration", other)
        consumeCurrent()
        result
    end duration

    override def captureValue(): Codec.Reader =
        val captured = value
        consumeCurrent()
        new BsonReader(captured, frame, config.copy(maxDepth = maxDepth, maxCollectionSize = maxCollectionSize))
    end captureValue

    def readStructure(): Structure.Value =
        val result = toStructure(value)
        consumeCurrent()
        result
    end readStructure

    private def consumeCurrent(): Unit =
        current = None
    end consumeCurrent

    private def skipValue(value: BsonValue): Unit =
        value match
            case DocumentValue(fields) =>
                checkDepth()
                checkCollectionSize(fields.size)
                fields.foreach((_, value) => skipValue(value))
                decrementDepth()
            case ArrayValue(values) =>
                checkDepth()
                checkCollectionSize(values.size)
                values.foreach(skipValue)
                decrementDepth()
            case _ =>
                ()
    end skipValue

    private def toStructure(value: BsonValue): Structure.Value =
        value match
            case DocumentValue(fields) =>
                checkDepth()
                checkCollectionSize(fields.size)
                val result = Structure.Value.Record(fields.toChunk.map((name, value) => name -> toStructure(value)))
                decrementDepth()
                result
            case ArrayValue(values) =>
                checkDepth()
                checkCollectionSize(values.size)
                val result = Structure.Value.Sequence(kyo.Chunk.from(values.map(toStructure)))
                decrementDepth()
                result
            case StringValue(value) =>
                Structure.Value.Str(value)
            case DoubleValue(value) =>
                Structure.Value.Decimal(value)
            case BinaryValue(value, _) =>
                Structure.Value.Bytes(value)
            case BooleanValue(value) =>
                Structure.Value.Bool(value)
            case DateTimeValue(value) =>
                Structure.Value.Instant(value)
            case NullValue =>
                Structure.Value.Null
            case Int32Value(value) =>
                Structure.Value.Integer(value.toLong)
            case Int64Value(value) =>
                Structure.Value.Integer(value)
            case Decimal128Value(value) =>
                Structure.Value.BigNum(value)
        end match
    end toStructure

    private def mismatch(expected: String, actual: BsonValue): Nothing =
        throw TypeMismatchException(Seq.empty, expected, actualName(actual))(using frame)
    end mismatch

    private def parseError(expected: String): Nothing =
        throw ParseException(kyo.Bson(config), "", expected)(using frame)
    end parseError

    private def actualName(value: BsonValue): String =
        value match
            case DocumentValue(_)   => "document"
            case ArrayValue(_)      => "array"
            case StringValue(_)     => "string"
            case DoubleValue(_)     => "double"
            case BinaryValue(_, _)  => "binary"
            case BooleanValue(_)    => "boolean"
            case DateTimeValue(_)   => "datetime"
            case NullValue          => "null"
            case Int32Value(_)      => "int32"
            case Int64Value(_)      => "int64"
            case Decimal128Value(_) => "decimal128"
    end actualName

end BsonReader

object BsonReader:
    def apply(input: Span[Byte], config: kyo.Bson.Config)(using frame: kyo.Frame): BsonReader =
        val parser = Parser(input, config, frame)
        new BsonReader(parser.parse(), frame, config)
    end apply

    final private class Parser(input: Span[Byte], config: kyo.Bson.Config, frame: kyo.Frame):
        import BsonFormat.*
        import BsonValue.*

        private var pos = 0

        def parse(): BsonValue =
            val root = parseDocument(depth = 1, array = false, limit = input.size)
            if pos != input.size then fail("end of BSON document")
            root
        end parse

        private def parseDocument(depth: Int, array: Boolean, limit: Int): BsonValue =
            if depth > config.maxDepth then
                throw kyo.LimitExceededException("Nesting depth", depth, config.maxDepth)(using frame)
            val start = pos
            requireRemaining(4, "document length", limit)
            val len = readInt32(limit)
            if len < MinDocumentLength then failAt("document length", start)
            val end = start + len
            if end < start || end > limit || end > input.size then truncated("document body")
            if input(end - 1) != 0 then failAt("document terminator", end - 1)

            if array then
                val values = Vector.newBuilder[BsonValue]
                var index  = 0
                while pos < end - 1 do
                    val tag = readByte(input.size)
                    val key = readCString(end)
                    if key != index.toString then failAt("array index " + index, pos)
                    index += 1
                    if index > config.maxCollectionSize then
                        throw kyo.LimitExceededException("Collection size", index, config.maxCollectionSize)(using frame)
                    values += parseElement(tag, depth + 1, end - 1)
                end while
                pos += 1
                ArrayValue(values.result())
            else
                val fields = OrderedMapBuilder.init[String, BsonValue]
                var count  = 0
                while pos < end - 1 do
                    val tag  = readByte(input.size)
                    val name = readCString(end)
                    count += 1
                    if count > config.maxCollectionSize then
                        throw kyo.LimitExceededException("Collection size", count, config.maxCollectionSize)(using frame)
                    discard(fields.add(name, parseElement(tag, depth + 1, end - 1)))
                end while
                pos += 1
                DocumentValue(fields.result())
            end if
        end parseDocument

        private def parseElement(tag: Int, depth: Int, limit: Int): BsonValue =
            tag match
                case Double =>
                    DoubleValue(java.lang.Double.longBitsToDouble(readInt64(limit)))
                case String =>
                    StringValue(readString(limit))
                case Document =>
                    parseDocument(depth, array = false, limit = limit)
                case Array =>
                    parseDocument(depth, array = true, limit = limit)
                case Binary =>
                    val len = readInt32(limit)
                    if len < 0 then fail("binary length")
                    val subtype = readByte(limit)
                    if subtype == BinarySubtypeOld then
                        requireRemaining(len, "old binary data", limit)
                        if len < 4 then fail("old binary length")
                        val payloadEnd = pos + len
                        val innerLen   = readInt32(payloadEnd)
                        if innerLen != len - 4 then fail("old binary inner length")
                        requireRemaining(innerLen, "old binary payload", payloadEnd)
                        val bytes = input.slice(pos, pos + innerLen)
                        pos += innerLen
                        BinaryValue(bytes, subtype)
                    else if subtype == BinarySubtypeGeneric then
                        requireRemaining(len, "binary data", limit)
                        val bytes = input.slice(pos, pos + len)
                        pos += len
                        BinaryValue(bytes, subtype)
                    else
                        fail("a supported BSON binary subtype (rejected 0x" + java.lang.Integer.toHexString(subtype) + ")")
                    end if
                case Boolean =>
                    readByte(limit) match
                        case 0 => BooleanValue(false)
                        case 1 => BooleanValue(true)
                        case _ => fail("boolean byte")
                case DateTime =>
                    DateTimeValue(java.time.Instant.ofEpochMilli(readInt64(limit)))
                case Null =>
                    NullValue
                case Int32 =>
                    Int32Value(readInt32(limit))
                case Int64 =>
                    Int64Value(readInt64(limit))
                case Decimal128 =>
                    requireRemaining(16, "decimal128", limit)
                    val bytes = input.slice(pos, pos + 16).toArrayUnsafe
                    pos += 16
                    Decimal128Value(BsonDecimal128.decode(bytes, kyo.Bson(config), preview)(using frame))
                case other =>
                    fail("supported BSON element type 0x" + java.lang.Integer.toHexString(other))
        end parseElement

        private def readString(limit: Int): String =
            val len = readInt32(limit)
            if len <= 0 then fail("string length")
            requireRemaining(len, "string bytes", limit)
            if input(pos + len - 1) != 0 then failAt("string terminator", pos + len - 1)
            val value = decodeUtf8(pos, len - 1)
            pos += len
            value
        end readString

        private def readCString(limit: Int): String =
            val start = pos
            while pos < limit && input(pos) != 0 do pos += 1
            if pos >= limit then truncated("cstring terminator")
            val value = decodeUtf8(start, pos - start)
            pos += 1
            value
        end readCString

        private def readByte(limit: Int): Int =
            requireRemaining(1, "byte", limit)
            val result = input(pos) & 0xff
            pos += 1
            result
        end readByte

        private def readInt32(limit: Int = input.size): Int =
            requireRemaining(4, "int32", limit)
            val result =
                (input(pos) & 0xff) |
                    ((input(pos + 1) & 0xff) << 8) |
                    ((input(pos + 2) & 0xff) << 16) |
                    ((input(pos + 3) & 0xff) << 24)
            pos += 4
            result
        end readInt32

        private def readInt64(limit: Int): Long =
            requireRemaining(8, "int64", limit)
            var result = 0L
            var shift  = 0
            while shift < 64 do
                result |= (input(pos) & 0xff).toLong << shift
                pos += 1
                shift += 8
            end while
            result
        end readInt64

        private def requireRemaining(count: Int, detail: String, limit: Int): Unit =
            if count < 0 || limit < pos || count > limit - pos || count > input.size - pos then truncated(detail)
        end requireRemaining

        private def decodeUtf8(start: Int, length: Int): String =
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            try decoder.decode(java.nio.ByteBuffer.wrap(input.toArrayUnsafe, start, length)).toString
            catch
                case _: java.nio.charset.CharacterCodingException =>
                    throw ParseException(kyo.Bson(config), preview, "valid UTF-8", position = start)(using frame)
            end try
        end decodeUtf8

        private def fail(expected: String): Nothing =
            failAt(expected, pos)
        end fail

        private def failAt(expected: String, position: Int): Nothing =
            throw ParseException(kyo.Bson(config), preview, expected, position = position)(using frame)
        end failAt

        private def truncated(detail: String): Nothing =
            throw TruncatedInputException(kyo.Bson(config), detail)(using frame)
        end truncated

        private def preview: String =
            input.take(32).map(b => f"${b & 0xff}%02x").mkString(" ")
        end preview
    end Parser
end BsonReader
