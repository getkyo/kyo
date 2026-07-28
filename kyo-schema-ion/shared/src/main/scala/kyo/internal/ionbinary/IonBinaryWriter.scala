package kyo.internal.ionbinary

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kyo.*
import kyo.Codec.Writer
import scala.annotation.tailrec

final class IonBinaryWriter private (private val config: Ion.Config) extends Writer:
    import IonBinaryFormat.*

    override def canWriteTopLevelNonObject: Boolean = true
    override def canWriteAnnotations: Boolean       = config.annotationEmissionMode == Ion.AnnotationEmissionMode.Emit
    override def codecName: String                  = CodecName

    private enum Value derives CanEqual:
        case NullValue
        case Bool(value: Boolean)
        case IntValue(value: BigInt)
        case FloatValue(value: Double, width: Int)
        case DecimalValue(value: BigDecimal)
        case TimestampValue(value: java.time.Instant)
        case StringValue(value: String)
        case BlobValue(value: Array[Byte])
        case ListValue(values: Vector[Value])
        case StructValue(fields: Vector[(String, Value)])
        case AnnotatedValue(annotations: Vector[String], value: Value)
    end Value

    private enum Frame:
        case ListFrame(values: Vector[Value])
        case StructFrame(fields: Vector[(String, Value)], pending: Maybe[String])

    import Frame.*
    import Value.*

    private var root: Maybe[Value]                 = Maybe.empty
    private var stack: List[Frame]                 = Nil
    private var pendingAnnotations: Vector[String] = Vector.empty

    override def annotations(values: Chunk[Any]): Unit =
        if canWriteAnnotations && values.nonEmpty then
            pendingAnnotations = pendingAnnotations ++ values.map(annotationName).toSeq
    end annotations

    def objectStart(name: String, size: Int): Unit =
        stack = StructFrame(Vector.empty, Maybe.empty) :: stack

    def objectEnd(): Unit =
        stack match
            case StructFrame(fields, pending) :: tail =>
                if pending.nonEmpty then invalid("field has no value")
                stack = tail
                appendValue(StructValue(fields))
            case other => invalid(s"objectEnd without object context: $other")
        end match
    end objectEnd

    def arrayStart(size: Int): Unit =
        stack = ListFrame(Vector.empty) :: stack

    def arrayEnd(): Unit =
        stack match
            case ListFrame(values) :: tail =>
                stack = tail
                appendValue(ListValue(values))
            case other => invalid(s"arrayEnd without array context: $other")
        end match
    end arrayEnd

    def mapStart(size: Int): Unit = objectStart("", size)
    def mapEnd(): Unit            = objectEnd()

    def fieldBytes(nameBytes: Array[Byte], fieldId: Int): Unit =
        field(new String(nameBytes, StandardCharsets.UTF_8), fieldId)

    override def field(name: String, fieldId: Int): Unit =
        stack match
            case StructFrame(fields, pending) :: tail =>
                if pending.nonEmpty then invalid("field has no value")
                stack = StructFrame(fields, Maybe(name)) :: tail
            case other => invalid(s"field without object context: $other")
        end match
    end field

    def string(value: String): Unit             = appendValue(StringValue(value))
    def int(value: Int): Unit                   = appendValue(IntValue(BigInt(value)))
    def long(value: Long): Unit                 = appendValue(IntValue(BigInt(value)))
    def short(value: Short): Unit               = appendValue(IntValue(BigInt(value.toInt)))
    def byte(value: Byte): Unit                 = appendValue(IntValue(BigInt(value.toInt)))
    def char(value: Char): Unit                 = appendValue(StringValue(value.toString))
    def float(value: Float): Unit               = appendValue(FloatValue(value.toDouble, 4))
    def double(value: Double): Unit             = appendValue(FloatValue(value, 8))
    def boolean(value: Boolean): Unit           = appendValue(Bool(value))
    def nil(): Unit                             = appendValue(NullValue)
    def bytes(value: Span[Byte]): Unit          = appendValue(BlobValue(value.toArray))
    def bigInt(value: BigInt): Unit             = appendValue(IntValue(value))
    def bigDecimal(value: BigDecimal): Unit     = appendValue(DecimalValue(value))
    def instant(value: java.time.Instant): Unit = appendValue(TimestampValue(value))
    def duration(value: java.time.Duration): Unit =
        appendValue(StructValue(Vector(
            "seconds" -> IntValue(BigInt(value.getSeconds)),
            "nanos"   -> IntValue(BigInt(value.getNano))
        )))
    end duration

    def result(): Span[Byte] =
        if stack.nonEmpty then invalid("unclosed container")
        val value   = root.getOrElse(NullValue)
        val symbols = collectLocalSymbols(value)
        val out     = new ByteArrayOutputStream(256)
        out.write(VersionMarker.toArray)
        if symbols.nonEmpty then writeLocalSymbolTable(out, symbols)
        writeValue(out, value, symbols)
        Span.from(out.toByteArray)
    end result

    private def appendValue(value: Value): Unit =
        val next =
            if pendingAnnotations.isEmpty then value
            else AnnotatedValue(pendingAnnotations, value)
        pendingAnnotations = Vector.empty
        stack match
            case StructFrame(fields, pending) :: tail =>
                val name = pending.getOrElse(invalid("field name required before struct value"))
                stack = StructFrame(fields :+ (name -> next), Maybe.empty) :: tail
            case ListFrame(values) :: tail =>
                stack = ListFrame(values :+ next) :: tail
            case Nil =>
                if root.nonEmpty then invalid("multiple top-level values")
                root = Maybe(next)
        end match
    end appendValue

    private def collectLocalSymbols(value: Value): Vector[String] =
        def add(acc: Vector[String], text: String): Vector[String] =
            if SystemSymbols.contains(text) || acc.contains(text) then acc else acc :+ text
        def loop(v: Value, acc: Vector[String]): Vector[String] =
            v match
                case StructValue(fields) =>
                    fields.foldLeft(acc) { case (next, (name, child)) => loop(child, add(next, name)) }
                case ListValue(values) =>
                    values.foldLeft(acc)((next, child) => loop(child, next))
                case AnnotatedValue(annotations, child) =>
                    loop(child, annotations.foldLeft(acc)(add))
                case _ => acc
        loop(value, Vector.empty)
    end collectLocalSymbols

    private def sid(text: String, locals: Vector[String]): Int =
        val system = SystemSymbols.indexOf(text)
        if system >= 0 then system + 1
        else
            val local = locals.indexOf(text)
            if local < 0 then invalid(s"unregistered symbol $text") else SystemSymbols.size + local + 1
        end if
    end sid

    private def writeLocalSymbolTable(out: ByteArrayOutputStream, locals: Vector[String]): Unit =
        val body = new ByteArrayOutputStream(128)
        writeVarUInt(body, BigInt(SymbolsSid))
        val list = new ByteArrayOutputStream(128)
        locals.foreach(text => writeString(list, text))
        writeDescriptor(body, TidList, list.size())
        body.write(list.toByteArray)
        val struct = new ByteArrayOutputStream(128)
        writeDescriptor(struct, TidStruct, body.size())
        struct.write(body.toByteArray)
        val wrapper = new ByteArrayOutputStream(128)
        writeVarUInt(wrapper, BigInt(1 + varUIntSize(IonSymbolTableSid) + struct.size()))
        writeVarUInt(wrapper, BigInt(1))
        writeVarUInt(wrapper, BigInt(IonSymbolTableSid))
        wrapper.write(struct.toByteArray)
        out.write((TidAnnotation << 4) | VarLenNibble)
        writeVarUInt(out, BigInt(wrapper.size()))
        out.write(wrapper.toByteArray)
    end writeLocalSymbolTable

    private def writeValue(out: ByteArrayOutputStream, value: Value, locals: Vector[String]): Unit =
        value match
            case NullValue =>
                writeTypedNull(out, TidNull)
            case Bool(v) =>
                out.write((TidBool << 4) | (if v then 1 else 0))
            case IntValue(v) =>
                writeInt(out, v)
            case FloatValue(v, 4) =>
                writeDescriptor(out, TidFloat, 4)
                writeBE32(out, java.lang.Float.floatToIntBits(v.toFloat))
            case FloatValue(v, _) =>
                writeDescriptor(out, TidFloat, 8)
                writeBE64(out, java.lang.Double.doubleToLongBits(v))
            case DecimalValue(v) =>
                writeDecimal(out, v)
            case TimestampValue(v) =>
                writeTimestamp(out, v)
            case StringValue(v) =>
                writeString(out, v)
            case BlobValue(v) =>
                writeDescriptor(out, TidBlob, v.length)
                out.write(v)
            case ListValue(values) =>
                val body = new ByteArrayOutputStream(128)
                values.foreach(writeValue(body, _, locals))
                writeDescriptor(out, TidList, body.size())
                out.write(body.toByteArray)
            case StructValue(fields) =>
                val body = new ByteArrayOutputStream(128)
                fields.foreach { case (name, child) =>
                    writeVarUInt(body, BigInt(sid(name, locals)))
                    writeValue(body, child, locals)
                }
                writeDescriptor(out, TidStruct, body.size())
                out.write(body.toByteArray)
            case AnnotatedValue(annotations, child) =>
                val body = new ByteArrayOutputStream(128)
                writeValue(body, child, locals)
                writeAnnotatedValue(out, annotations.map(sid(_, locals)), body.toByteArray)
        end match
    end writeValue

    private def writeAnnotatedValue(out: ByteArrayOutputStream, annotationSids: Vector[Int], valueBytes: Array[Byte]): Unit =
        val payload    = new ByteArrayOutputStream(128)
        val wrapperLen = varUIntSize(annotationSids.size) + annotationSids.map(varUIntSize).sum + valueBytes.length
        writeVarUInt(payload, BigInt(wrapperLen))
        writeVarUInt(payload, BigInt(annotationSids.size))
        annotationSids.foreach(sid => writeVarUInt(payload, BigInt(sid)))
        payload.write(valueBytes)
        out.write((TidAnnotation << 4) | VarLenNibble)
        writeVarUInt(out, BigInt(payload.size()))
        out.write(payload.toByteArray)
    end writeAnnotatedValue

    private def writeInt(out: ByteArrayOutputStream, value: BigInt): Unit =
        if value == 0 then out.write(TidPosInt << 4)
        else
            val bytes = unsignedMagnitude(value.abs)
            writeDescriptor(out, if value < 0 then TidNegInt else TidPosInt, bytes.length)
            out.write(bytes)
        end if
    end writeInt

    private def writeString(out: ByteArrayOutputStream, value: String): Unit =
        val bytes = value.getBytes(StandardCharsets.UTF_8)
        writeDescriptor(out, TidString, bytes.length)
        out.write(bytes)
    end writeString

    private def writeDecimal(out: ByteArrayOutputStream, value: BigDecimal): Unit =
        val exponent = -value.scale
        val coeff    = value.bigDecimal.unscaledValue
        val body     = new ByteArrayOutputStream(32)
        writeVarInt(body, BigInt(exponent))
        body.write(signedMagnitude(BigInt(coeff)))
        writeDescriptor(out, TidDecimal, body.size())
        out.write(body.toByteArray)
    end writeDecimal

    private def writeTimestamp(out: ByteArrayOutputStream, value: java.time.Instant): Unit =
        val zdt = java.time.ZonedDateTime.ofInstant(value, java.time.ZoneOffset.UTC)
        if zdt.getYear < 0 then
            invalid(
                s"Ion binary timestamp cannot encode Instant $value: proleptic year ${zdt.getYear} is negative and Ion binary's timestamp year field is an unsigned VarUInt"
            )
        end if
        val body = new ByteArrayOutputStream(32)
        writeVarInt(body, BigInt(0))
        writeVarUInt(body, BigInt(zdt.getYear))
        writeVarUInt(body, BigInt(zdt.getMonthValue))
        writeVarUInt(body, BigInt(zdt.getDayOfMonth))
        writeVarUInt(body, BigInt(zdt.getHour))
        writeVarUInt(body, BigInt(zdt.getMinute))
        writeVarUInt(body, BigInt(zdt.getSecond))
        if value.getNano != 0 then
            val frac = BigDecimal(BigInt(value.getNano), 9)
            val f    = new ByteArrayOutputStream(16)
            writeVarInt(f, BigInt(-frac.scale))
            f.write(signedMagnitude(BigInt(frac.bigDecimal.unscaledValue)))
            body.write(f.toByteArray)
        end if
        writeDescriptor(out, TidTimestamp, body.size())
        out.write(body.toByteArray)
    end writeTimestamp

    private def varUIntSize(value: Int): Int =
        @tailrec def loop(v: Int, n: Int): Int =
            if v < 0x80 then n else loop(v >>> 7, n + 1)
        loop(value, 1)
    end varUIntSize

    private def annotationName(value: Any): String =
        value.getClass.getName.stripSuffix("$")

    private def invalid(message: String): Nothing =
        throw SchemaNotSerializableException(message)(using kyo.Frame.internal)
    end invalid

end IonBinaryWriter

object IonBinaryWriter:
    def apply(config: Ion.Config = Ion.Config.Default): IonBinaryWriter = new IonBinaryWriter(config)
