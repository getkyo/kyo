package kyo.internal.ionbinary

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.Codec.IntrospectingReader
import kyo.Codec.Reader
import scala.annotation.tailrec
import scala.util.control.NonFatal

private[kyo] enum IonBinaryValue derives CanEqual:
    case NullValue(tid: Int)
    case BoolValue(value: Boolean)
    case IntValue(value: BigInt)
    case FloatValue(value: Double)
    case DecimalValue(value: BigDecimal)
    case TimestampValue(value: java.time.Instant)
    case StringValue(value: String)
    case SymbolValue(value: String)
    case BlobValue(value: Span[Byte])
    case ClobValue(value: Span[Byte])
    case ListValue(values: Vector[IonBinaryValue])
    case StructValue(fields: Vector[(String, IonBinaryValue)])

    def display: String =
        this match
            case NullValue(_)      => "null"
            case BoolValue(_)      => "bool"
            case IntValue(_)       => "int"
            case FloatValue(_)     => "float"
            case DecimalValue(_)   => "decimal"
            case TimestampValue(_) => "timestamp"
            case StringValue(_)    => "string"
            case SymbolValue(_)    => "symbol"
            case BlobValue(_)      => "blob"
            case ClobValue(_)      => "clob"
            case ListValue(_)      => "list"
            case StructValue(_)    => "struct"
end IonBinaryValue

final class IonBinaryReader private (
    private var root: IonBinaryValue,
    private var current: Maybe[IonBinaryValue],
    private var _frame: Frame
) extends IntrospectingReader:
    import IonBinaryValue.*

    // Nothing is left to check here: this reader walks a value parsed up front, and that parse
    // already refuses bytes after the root value.
    private[kyo] def requireEndOfInput(): Unit = ()

    private enum Context:
        case Obj(fields: Vector[(String, IonBinaryValue)], index: Int)
        case Arr(values: Vector[IonBinaryValue], index: Int)

    import Context.*

    override def frame: Frame = _frame

    private var stack: List[Context]       = Nil
    private var parsedField: Maybe[String] = Maybe.empty

    def objectStart(): Int =
        value match
            case StructValue(fields) =>
                checkDepth()
                checkCollectionSize(fields.size)
                stack = Obj(fields, 0) :: stack
                fields.size
            case other => mismatch("struct", other)
    end objectStart

    def objectEnd(): Unit =
        stack match
            case Obj(_, _) :: tail =>
                stack = tail
                decrementDepth()
            case _ => throw TypeMismatchException(Seq.empty, "struct end", "no active struct")(using _frame)
        end match
    end objectEnd

    def arrayStart(): Int =
        value match
            case ListValue(values) =>
                checkDepth()
                checkCollectionSize(values.size)
                stack = Arr(values, 0) :: stack
                values.size
            case other => mismatch("list", other)
    end arrayStart

    def arrayEnd(): Unit =
        stack match
            case Arr(_, _) :: tail =>
                stack = tail
                decrementDepth()
            case _ => throw TypeMismatchException(Seq.empty, "list end", "no active list")(using _frame)
        end match
    end arrayEnd

    def field(): String =
        stack match
            case Obj(fields, index) :: tail if index < fields.size =>
                val (name, fieldValue) = fields(index)
                stack = Obj(fields, index + 1) :: tail
                current = Maybe(fieldValue)
                parsedField = Maybe(name)
                name
            case Obj(_, _) :: _ => throw MissingFieldException(Seq.empty, "<next>")(using _frame)
            case _              => throw TypeMismatchException(Seq.empty, "field", "no active struct")(using _frame)
        end match
    end field

    def fieldParse(): Unit =
        discard(field())

    def matchField(nameBytes: Array[Byte]): Boolean =
        parsedField.exists(name => java.util.Arrays.equals(name.getBytes(StandardCharsets.UTF_8), nameBytes))

    def lastFieldName(): String = parsedField.getOrElse("")

    def hasNextField(): Boolean =
        stack match
            case Obj(fields, index) :: _ => index < fields.size
            case _                       => false

    def hasNextElement(): Boolean =
        stack match
            case Arr(values, index) :: tail if index < values.size =>
                current = Maybe(values(index))
                stack = Arr(values, index + 1) :: tail
                true
            case Arr(_, _) :: _ => false
            case _              => false
        end match
    end hasNextElement

    def string(): String =
        value match
            case StringValue(v) => v
            case SymbolValue(v) => v
            case other          => mismatch("string", other)

    def int(): Int =
        val v = integer()
        if v < BigInt(Int.MinValue) || v > BigInt(Int.MaxValue) then
            throw RangeException(if v.isValidLong then v.toLong else 0L, "Int", Int.MinValue.toLong, Int.MaxValue.toLong)(using _frame)
        v.toInt
    end int

    def long(): Long =
        val v = integer()
        if !v.isValidLong then throw ParseException(IonBinary(), v.toString, "Long")(using _frame)
        v.toLong
    end long

    def short(): Short =
        val v = int()
        if v < Short.MinValue || v > Short.MaxValue then
            throw RangeException(v.toLong, "Short", Short.MinValue.toLong, Short.MaxValue.toLong)(using _frame)
        v.toShort
    end short

    def byte(): Byte =
        val v = int()
        if v < Byte.MinValue || v > Byte.MaxValue then
            throw RangeException(v.toLong, "Byte", Byte.MinValue.toLong, Byte.MaxValue.toLong)(using _frame)
        v.toByte
    end byte

    def char(): Char =
        val s = string()
        if s.length != 1 then throw TypeMismatchException(Seq.empty, "single character", s"string length ${s.length}")(using _frame)
        s.charAt(0)
    end char

    def float(): Float = double().toFloat

    def double(): Double =
        value match
            case FloatValue(v)   => v
            case DecimalValue(v) => v.toDouble
            case IntValue(v)     => v.toDouble
            case other           => mismatch("float", other)

    def boolean(): Boolean =
        value match
            case BoolValue(v) => v
            case other        => mismatch("bool", other)

    def isNil(): Boolean =
        value match
            case NullValue(_) => true
            case _            => false

    def skip(): Unit =
        skipValue(value)

    def mapStart(): Int         = objectStart()
    def mapEnd(): Unit          = objectEnd()
    def hasNextEntry(): Boolean = hasNextField()

    def bytes(): Span[Byte] =
        value match
            case BlobValue(v) => v
            case ClobValue(v) => v
            case other        => mismatch("blob", other)

    def bigInt(): BigInt = integer()

    def bigDecimal(): BigDecimal =
        value match
            case DecimalValue(v) => v
            case IntValue(v)     => BigDecimal(v)
            case FloatValue(v) =>
                if v.isNaN || v.isInfinite then mismatch("finite decimal", FloatValue(v))
                else BigDecimal(v)
            case other => mismatch("decimal", other)

    def instant(): java.time.Instant =
        value match
            case TimestampValue(v) => v
            case other             => mismatch("timestamp", other)

    def duration(): java.time.Duration =
        value match
            case StructValue(fields) =>
                val seconds = fields.foldLeft(Maybe.empty[Long]) {
                    case (_, ("seconds", IntValue(v))) if v.isValidLong => Maybe(v.toLong)
                    case (found, _)                                     => found
                }
                val nanos = fields.foldLeft(Maybe.empty[Int]) {
                    case (_, ("nanos", IntValue(v))) if v.isValidInt => Maybe(v.toInt)
                    case (found, _)                                  => found
                }
                (seconds, nanos) match
                    case (Maybe.Present(s), Maybe.Present(n)) => java.time.Duration.ofSeconds(s, n.toLong)
                    case _                                    => mismatch("Duration struct", value)
            case other => mismatch("Duration struct", other)
        end match
    end duration

    override def captureValue(): Reader =
        skipValue(value)
        val captured = new IonBinaryReader(value, Maybe(value), _frame)
        captured.resetLimits(maxDepth, maxCollectionSize)
        captured
    end captureValue

    override def release(): Unit =
        root = NullValue(IonBinaryFormat.TidNull)
        current = Maybe.empty
        stack = Nil
        parsedField = Maybe.empty
    end release

    override def readStructure(): Structure.Value =
        def toValue(v: IonBinaryValue): Structure.Value =
            v match
                case NullValue(_)      => Structure.Value.Null
                case BoolValue(b)      => Structure.Value.Bool(b)
                case IntValue(i)       => if i.isValidLong then Structure.Value.Integer(i.toLong) else Structure.Value.BigNum(BigDecimal(i))
                case FloatValue(d)     => Structure.Value.Decimal(d)
                case DecimalValue(d)   => Structure.Value.BigNum(d)
                case TimestampValue(i) => Structure.Value.Instant(i)
                case StringValue(s)    => Structure.Value.Str(s)
                case SymbolValue(s)    => Structure.Value.Str(s)
                case BlobValue(b)      => Structure.Value.Bytes(b)
                case ClobValue(_)      => throw ParseException(IonBinary(), "clob", "Structure.Value")(using _frame)
                case ListValue(vs) =>
                    checkDepth()
                    checkCollectionSize(vs.size)
                    val out = Structure.Value.Sequence(Chunk.from(vs.map(toValue)))
                    decrementDepth()
                    out
                case StructValue(fs) =>
                    checkDepth()
                    checkCollectionSize(fs.size)
                    val out = Structure.Value.Record(Chunk.from(fs.map((k, x) => (k, toValue(x)))))
                    decrementDepth()
                    out
        toValue(value)
    end readStructure

    private def value: IonBinaryValue = current.getOrElse(root)

    private def integer(): BigInt =
        value match
            case IntValue(v) => v
            case other       => mismatch("int", other)

    private def mismatch(expected: String, actual: IonBinaryValue): Nothing =
        throw TypeMismatchException(Seq.empty, expected, actual.display)(using _frame)

    private def skipValue(v: IonBinaryValue): Unit =
        v match
            case ListValue(values) =>
                checkDepth()
                checkCollectionSize(values.size)
                values.foreach(skipValue)
                decrementDepth()
            case StructValue(fields) =>
                checkDepth()
                checkCollectionSize(fields.size)
                fields.foreach { case (_, child) => skipValue(child) }
                decrementDepth()
            case _ => ()
        end match
    end skipValue

end IonBinaryReader

object IonBinaryReader:
    def apply(input: Span[Byte])(using frame: Frame): IonBinaryReader =
        val value = Parser(input.toArray, frame).parseTopLevel()
        new IonBinaryReader(value, Maybe(value), frame)

    final private class Parser(data: Array[Byte], frame: Frame):
        import IonBinaryFormat.*
        import IonBinaryValue.*

        private var pos: Int                = 0
        private var symbols: Vector[String] = SystemSymbols

        final private case class VarInt(value: BigInt, isNegativeZero: Boolean)

        def parseTopLevel(): IonBinaryValue =
            readBvm()
            val value = readTopLevelValue(Maybe.empty)
            skipTopLevelNops()
            if pos < data.length then parseError("trailing data", "end of input")
            value
        end parseTopLevel

        @tailrec private def readTopLevelValue(found: Maybe[IonBinaryValue]): IonBinaryValue =
            skipTopLevelNops()
            if pos >= data.length then found.getOrElse(truncated("missing user value"))
            else if isBvmAt(pos) then
                if found.nonEmpty then parseError("version marker after user value", "end of input")
                readBvm()
                symbols = SystemSymbols
                readTopLevelValue(found)
            else
                val parsed = parseAnnotatedValue(data.length, topLevel = true)
                parsed match
                    case TopLevel.SymbolTable =>
                        if found.nonEmpty then parseError("symbol table after user value", "end of input")
                        readTopLevelValue(found)
                    case TopLevel.UserValue(value) =>
                        found match
                            case Maybe.Present(_) => parseError("multiple top-level values", "single value")
                            case Maybe.Absent     => readTopLevelValue(Maybe(value))
                end match
            end if
        end readTopLevelValue

        private enum TopLevel derives CanEqual:
            case UserValue(value: IonBinaryValue)
            case SymbolTable

        private def parseAnnotatedValue(limit: Int, topLevel: Boolean): TopLevel =
            val start      = pos
            val descriptor = readByte(limit)
            val tid        = descriptor >>> 4
            val ln         = descriptor & 0x0f
            if tid == TidAnnotation then
                if ln == NullNibble then parseError("null annotation wrapper", "annotation wrapper")
                val len             = readLength(ln, limit)
                val end             = checkedEnd(len, limit)
                val wrapperLenStart = pos
                val wrapperLen      = readVarUInt(end).toInt
                val wrapperEnd      = checkedEndFrom(wrapperLenStart, varUIntByteLength(wrapperLenStart, pos) + wrapperLen, end)
                if wrapperEnd != end then parseError("annotation wrapper length", "descriptor boundary")
                val count = readVarUInt(wrapperEnd).toInt
                if count <= 0 then parseError("annotation wrapper with no annotations", "annotation sid")
                val annotations = readAnnotationSids(count, wrapperEnd)
                val value       = parseValue(wrapperEnd)
                if pos != end then parseError("annotation wrapper length", "wrapped value boundary")
                if topLevel && annotations.contains(IonSymbolTableSid) then
                    updateLocalSymbols(value)
                    TopLevel.SymbolTable
                else TopLevel.UserValue(value)
                end if
            else
                pos = start
                TopLevel.UserValue(parseValue(limit))
            end if
        end parseAnnotatedValue

        private def parseValue(limit: Int): IonBinaryValue =
            val descriptor = readByte(limit)
            val tid        = descriptor >>> 4
            val ln         = descriptor & 0x0f
            if tid == TidAnnotation then
                pos -= 1
                parseAnnotatedValue(limit, topLevel = false) match
                    case TopLevel.UserValue(value) => value
                    case TopLevel.SymbolTable      => parseError("nested symbol table", "value")
            else if ln == NullNibble then parseTypedNull(tid)
            else
                tid match
                    case TidNull =>
                        if ln == 0 then NullValue(TidNull) else skipNopBody(readLength(ln, limit), limit)
                    case TidBool =>
                        ln match
                            case 0 => BoolValue(false)
                            case 1 => BoolValue(true)
                            case _ => parseError(s"bool length $ln", "bool")
                    case TidPosInt         => readInt(readLength(ln, limit), positive = true, limit)
                    case TidNegInt         => readInt(readLength(ln, limit), positive = false, limit)
                    case TidFloat          => readFloat(readLength(ln, limit), limit)
                    case TidDecimal        => readDecimal(readLength(ln, limit), limit)
                    case TidTimestamp      => readTimestamp(readLength(ln, limit), limit)
                    case TidSymbol         => readSymbol(readLength(ln, limit), limit)
                    case TidString         => StringValue(readUtf8(readLength(ln, limit), limit))
                    case TidClob           => ClobValue(readBytes(readLength(ln, limit), limit))
                    case TidBlob           => BlobValue(readBytes(readLength(ln, limit), limit))
                    case TidList | TidSexp => readList(readLength(ln, limit), limit)
                    case TidStruct         => readStruct(readLength(ln, limit), limit)
                    case other             => parseError(f"type 0x$other%x", "Ion value")
                end match
            end if
        end parseValue

        private def parseTypedNull(tid: Int): IonBinaryValue =
            tid match
                case TidNull | TidBool | TidPosInt | TidNegInt | TidFloat | TidDecimal | TidTimestamp | TidSymbol | TidString | TidClob | TidBlob =>
                    NullValue(tid)
                case _ => parseError(s"typed null for type $tid", "scalar typed null")
            end match
        end parseTypedNull

        private def skipNopBody(len: Int, limit: Int): IonBinaryValue =
            requireRemaining(len, limit)
            pos += len
            parseValue(limit)
        end skipNopBody

        private def readInt(len: Int, positive: Boolean, limit: Int): IonBinaryValue =
            if len == 0 then
                if positive then IntValue(BigInt(0)) else parseError("negative zero", "integer")
            else
                val bytes     = readByteVector(len, limit)
                val magnitude = BigInt(1, bytes)
                if magnitude == 0 then parseError("negative zero", "integer")
                IntValue(if positive then magnitude else -magnitude)
            end if
        end readInt

        private def readFloat(len: Int, limit: Int): IonBinaryValue =
            len match
                case 0 => FloatValue(0.0d)
                case 4 => FloatValue(java.lang.Float.intBitsToFloat(readBE32(limit)).toDouble)
                case 8 => FloatValue(java.lang.Double.longBitsToDouble(readBE64(limit)))
                case _ => parseError(s"float length $len", "float length 0, 4, or 8")
            end match
        end readFloat

        private def readDecimal(len: Int, limit: Int): IonBinaryValue =
            val end          = checkedEnd(len, limit)
            val exponentInfo = readVarInt(end)
            if exponentInfo.isNegativeZero then parseError("negative zero exponent", "decimal exponent")
            val exponent = exponentInfo.value
            if !exponent.isValidInt then parseError(exponent.toString, "decimal exponent")
            val coeff =
                if pos == end then BigInt(0)
                else readSignedMagnitude(readByteVector(end - pos, end))
            pos = end
            DecimalValue(BigDecimal(coeff, -exponent.toInt))
        end readDecimal

        private def readTimestamp(len: Int, limit: Int): IonBinaryValue =
            val end    = checkedEnd(len, limit)
            val offset = readVarInt(end)
            if offset.isNegativeZero then parseError("unknown local offset", "known timestamp offset")
            val year   = readVarUInt(end).toInt
            val month  = readRequiredVarUInt(end, "month").toInt
            val day    = readRequiredVarUInt(end, "day").toInt
            val hour   = readRequiredVarUInt(end, "hour").toInt
            val minute = readRequiredVarUInt(end, "minute").toInt
            val second = readRequiredVarUInt(end, "second").toInt
            val nanos =
                if pos < end then
                    readDecimal(end - pos, end) match
                        case DecimalValue(d) =>
                            val scaled = (d * BigDecimal(1000000000)).bigDecimal
                            try scaled.intValueExact()
                            catch case ex: ArithmeticException if NonFatal(ex) => parseError("timestamp fraction", "nanosecond precision")
                        case _ => 0
                else 0
            if pos != end then parseError("timestamp length", "timestamp boundary")
            try
                val ldt = java.time.LocalDateTime.of(year, month, day, hour, minute, second, nanos)
                TimestampValue(ldt.toInstant(java.time.ZoneOffset.ofTotalSeconds(offset.value.toInt * 60)))
            catch
                case ex: java.time.DateTimeException if NonFatal(ex) =>
                    parseError(ex.getMessage, "valid timestamp")
            end try
        end readTimestamp

        private def readSymbol(len: Int, limit: Int): IonBinaryValue =
            val sid = readUIntBytes(len, limit)
            if sid <= 0 || sid > symbols.size then parseError(s"symbol id $sid", "resolved symbol")
            SymbolValue(symbols(sid - 1))
        end readSymbol

        private def readList(len: Int, limit: Int): IonBinaryValue =
            val end = checkedEnd(len, limit)
            @tailrec def loop(acc: Vector[IonBinaryValue]): Vector[IonBinaryValue] =
                skipNops(end)
                if pos == end then acc
                else if pos > end then parseError("list boundary", "container boundary")
                else loop(acc :+ parseValue(end))
            end loop
            ListValue(loop(Vector.empty))
        end readList

        private def readStruct(len: Int, limit: Int): IonBinaryValue =
            val end = checkedEnd(len, limit)
            @tailrec def loop(acc: Vector[(String, IonBinaryValue)]): Vector[(String, IonBinaryValue)] =
                skipNops(end)
                if pos == end then acc
                else if pos > end then parseError("struct boundary", "container boundary")
                else
                    val sid = readVarUInt(end).toInt
                    if sid <= 0 || sid > symbols.size then parseError(s"field symbol id $sid", "resolved field symbol")
                    loop(acc :+ (symbols(sid - 1) -> parseValue(end)))
                end if
            end loop
            StructValue(loop(Vector.empty))
        end readStruct

        private def updateLocalSymbols(value: IonBinaryValue): Unit =
            value match
                case StructValue(fields) =>
                    fields.foreach {
                        case ("symbols", ListValue(values)) =>
                            values.foreach {
                                case StringValue(text) => symbols = symbols :+ text
                                case NullValue(_)      => symbols = symbols :+ ""
                                case other             => parseError(other.display, "symbol text")
                            }
                        case ("symbols", other)                               => parseError(other.display, "symbols list")
                        case ("imports", NullValue(_))                        => ()
                        case ("imports", ListValue(values)) if values.isEmpty => ()
                        case ("imports", _)                                   => parseError("imports", "unsupported empty imports")
                        case _                                                => ()
                    }
                case other => parseError(other.display, "local symbol table struct")
            end match
        end updateLocalSymbols

        private def readAnnotationSids(count: Int, limit: Int): Vector[Int] =
            @tailrec def loop(i: Int, acc: Vector[Int]): Vector[Int] =
                if i == count then acc
                else
                    val sid = readVarUInt(limit).toInt
                    if sid <= 0 || sid > symbols.size then parseError(s"annotation symbol id $sid", "resolved annotation symbol")
                    loop(i + 1, acc :+ sid)
            loop(0, Vector.empty)
        end readAnnotationSids

        private def skipTopLevelNops(): Unit =
            skipNops(data.length)

        private def skipNops(limit: Int): Unit =
            @tailrec def loop(): Unit =
                if pos < limit && !isBvmAt(pos) then
                    val start      = pos
                    val descriptor = data(pos) & 0xff
                    val tid        = descriptor >>> 4
                    val ln         = descriptor & 0x0f
                    if tid == TidNull && ln != NullNibble then
                        pos += 1
                        val len = readLength(ln, limit)
                        requireRemaining(len, limit)
                        pos += len
                        loop()
                    else pos = start
                    end if
            loop()
        end skipNops

        private def readBvm(): Unit =
            if !isBvmAt(pos) then parseError(IonBinaryFormat.preview(Span.from(data)), "Ion 1.0 version marker")
            pos += 4
        end readBvm

        private def isBvmAt(index: Int): Boolean =
            index + 3 < data.length &&
                (data(index) & 0xff) == 0xe0 &&
                (data(index + 1) & 0xff) == 0x01 &&
                (data(index + 2) & 0xff) == 0x00 &&
                (data(index + 3) & 0xff) == 0xea

        private def readLength(ln: Int, limit: Int): Int =
            if ln < VarLenNibble then ln
            else if ln == VarLenNibble then
                val v = readVarUInt(limit)
                if !v.isValidInt then parseError(v.toString, "addressable length")
                v.toInt
            else parseError("typed null length", "length")
        end readLength

        private def readVarUInt(limit: Int): BigInt =
            @tailrec def loop(acc: BigInt, bytes: Int): BigInt =
                val b    = readByte(limit)
                val next = (acc << 7) | BigInt(b & 0x7f)
                if (b & 0x80) != 0 then next
                else if bytes >= 9 then parseError("unterminated VarUInt", "terminated VarUInt")
                else loop(next, bytes + 1)
            end loop
            loop(BigInt(0), 1)
        end readVarUInt

        private def readVarInt(limit: Int): VarInt =
            val first    = readByte(limit)
            val negative = (first & 0x40) != 0
            val initial  = BigInt(first & 0x3f)
            if (first & 0x80) != 0 then VarInt(if negative then -initial else initial, negative && initial == 0)
            else
                @tailrec def loop(acc: BigInt, bytes: Int): BigInt =
                    val b    = readByte(limit)
                    val next = (acc << 7) | BigInt(b & 0x7f)
                    if (b & 0x80) != 0 then if negative then -next else next
                    else if bytes >= 9 then parseError("unterminated VarInt", "terminated VarInt")
                    else loop(next, bytes + 1)
                end loop
                val value = loop(initial, 1)
                VarInt(value, negative && value == 0)
            end if
        end readVarInt

        private def readSignedMagnitude(bytes: Array[Byte]): BigInt =
            val negative       = (bytes(0) & 0x80) != 0
            val magnitudeBytes = bytes.clone()
            magnitudeBytes(0) = (magnitudeBytes(0) & 0x7f).toByte
            val magnitude = BigInt(1, magnitudeBytes)
            if negative && magnitude == 0 then parseError("negative zero coefficient", "decimal coefficient")
            if negative then -magnitude else magnitude
        end readSignedMagnitude

        private def readRequiredVarUInt(limit: Int, name: String): BigInt =
            if pos >= limit then parseError(s"reduced timestamp missing $name", "complete timestamp")
            readVarUInt(limit)
        end readRequiredVarUInt

        private def readUIntBytes(len: Int, limit: Int): Int =
            if len == 0 then 0
            else
                val value = BigInt(1, readByteVector(len, limit))
                if !value.isValidInt then parseError(value.toString, "Int symbol id")
                value.toInt
            end if
        end readUIntBytes

        private def readUtf8(len: Int, limit: Int): String =
            new String(readByteVector(len, limit), StandardCharsets.UTF_8)

        private def readBytes(len: Int, limit: Int): Span[Byte] =
            Span.from(readByteVector(len, limit))

        private def readByteVector(len: Int, limit: Int): Array[Byte] =
            requireRemaining(len, limit)
            val out = java.util.Arrays.copyOfRange(data, pos, pos + len)
            pos += len
            out
        end readByteVector

        private def readBE32(limit: Int): Int =
            @tailrec def loop(i: Int, acc: Int): Int =
                if i == 4 then acc else loop(i + 1, (acc << 8) | readByte(limit))
            loop(0, 0)
        end readBE32

        private def readBE64(limit: Int): Long =
            @tailrec def loop(i: Int, acc: Long): Long =
                if i == 8 then acc else loop(i + 1, (acc << 8) | (readByte(limit).toLong & 0xffL))
            loop(0, 0L)
        end readBE64

        private def readByte(limit: Int): Int =
            if pos >= limit then truncated("unexpected end of input")
            val b = data(pos) & 0xff
            pos += 1
            b
        end readByte

        private def checkedEnd(len: Int, limit: Int): Int =
            checkedEndFrom(pos, len, limit)

        private def checkedEndFrom(start: Int, len: Int, limit: Int): Int =
            if len < 0 || start.toLong + len.toLong > limit.toLong then truncated(s"need $len bytes")
            start + len
        end checkedEndFrom

        private def requireRemaining(len: Int, limit: Int): Unit =
            if len < 0 || pos.toLong + len.toLong > limit.toLong then truncated(s"need $len bytes")

        private def varUIntByteLength(start: Int, end: Int): Int = end - start

        private def truncated(detail: String): Nothing =
            throw TruncatedInputException(IonBinary(), detail)(using frame)

        private def parseError(input: String, expected: String): Nothing =
            throw ParseException(IonBinary(), input, expected)(using frame)
    end Parser
end IonBinaryReader
