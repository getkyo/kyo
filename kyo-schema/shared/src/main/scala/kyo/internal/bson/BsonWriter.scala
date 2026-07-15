package kyo.internal.bson

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kyo.Codec
import kyo.OrderedMap
import kyo.OrderedMapBuilder
import kyo.SchemaNotSerializableException
import kyo.Span
import kyo.discard
import scala.collection.mutable

final class BsonWriter(config: kyo.Bson.Config) extends Codec.Writer:
    import BsonFormat.*
    import BsonValue.*

    private val KindDocument: 1 = 1
    private val KindArray: 2    = 2
    private type FrameKind = KindDocument.type | KindArray.type

    override def codecName: String = "Bson"

    final private class WriteFrame(val kind: FrameKind):
        val fields: OrderedMapBuilder[String, BsonValue] = OrderedMapBuilder.init[String, BsonValue]
        val values: mutable.ArrayBuffer[BsonValue]       = mutable.ArrayBuffer.empty
        var pendingField: Option[String]                 = None
    end WriteFrame

    private var stack: List[WriteFrame] = Nil
    private var root: Option[BsonValue] = None

    def objectStart(name: String, size: Int): Unit =
        stack = WriteFrame(KindDocument) :: stack
    end objectStart

    def objectEnd(): Unit =
        val frame = popFrame(KindDocument)
        if frame.pendingField.nonEmpty then invalid("BSON document field is missing a value")
        pushValue(DocumentValue(frame.fields.result()))
    end objectEnd

    def arrayStart(size: Int): Unit =
        if stack.isEmpty then invalid("BSON requires a top-level document")
        stack = WriteFrame(KindArray) :: stack
    end arrayStart

    def arrayEnd(): Unit =
        val frame = popFrame(KindArray)
        pushValue(ArrayValue(frame.values.toVector))
    end arrayEnd

    def mapStart(size: Int): Unit = objectStart("", size)

    def mapEnd(): Unit = objectEnd()

    override def field(name: String, fieldId: Int): Unit =
        setField(name)
    end field

    def fieldBytes(nameBytes: Array[Byte], fieldId: Int): Unit =
        setField(new String(nameBytes, StandardCharsets.UTF_8))
    end fieldBytes

    def string(value: String): Unit = pushValue(StringValue(value))

    def int(value: Int): Unit = pushValue(Int32Value(value))

    def long(value: Long): Unit = pushValue(Int64Value(value))

    def float(value: Float): Unit = pushValue(DoubleValue(value.toDouble))

    def double(value: Double): Unit = pushValue(DoubleValue(value))

    def boolean(value: Boolean): Unit = pushValue(BooleanValue(value))

    def short(value: Short): Unit = pushValue(Int32Value(value.toInt))

    def byte(value: Byte): Unit = pushValue(Int32Value(value.toInt))

    def char(value: Char): Unit = pushValue(StringValue(value.toString))

    def nil(): Unit = pushValue(NullValue)

    def bytes(value: Span[Byte]): Unit = pushValue(BinaryValue(value, BinarySubtypeGeneric))

    def bigInt(value: BigInt): Unit =
        if value.isValidLong then pushValue(Int64Value(value.toLong))
        else invalid(s"BigInt $value cannot be represented as BSON int64")
    end bigInt

    def bigDecimal(value: BigDecimal): Unit =
        pushValue(Decimal128Value(value))
    end bigDecimal

    def instant(value: java.time.Instant): Unit =
        if value.getNano % 1000000 != 0 then
            invalid("BSON UTC datetime has millisecond precision and cannot encode an Instant with sub-millisecond nanos")
        try pushValue(DateTimeValue(java.time.Instant.ofEpochMilli(value.toEpochMilli)))
        catch
            case _: ArithmeticException => invalid("Instant is outside BSON UTC datetime millisecond range")
    end instant

    def duration(value: java.time.Duration): Unit =
        pushValue(DocumentValue(OrderedMap(
            "seconds" -> Int64Value(value.getSeconds),
            "nanos"   -> Int32Value(value.getNano)
        )))
    end duration

    def result(): Span[Byte] =
        root match
            case Some(DocumentValue(fields)) =>
                Span.from(writeDocument(fields))
            case Some(_) =>
                invalid("BSON requires a top-level document")
            case None =>
                invalid("BSON writer has no root document")
    end result

    private def setField(name: String): Unit =
        if stack.isEmpty || stack.head.kind != KindDocument then invalid("BSON fields can only be written inside a document")
        validateCString(name)
        if stack.head.pendingField.nonEmpty then invalid("BSON document field is missing a value")
        stack.head.pendingField = Some(name)
    end setField

    private def pushValue(value: BsonValue): Unit =
        stack match
            case Nil =>
                root match
                    case None =>
                        value match
                            case _: DocumentValue => root = Some(value)
                            case _                => invalid("BSON requires a top-level document")
                    case Some(_) =>
                        invalid("BSON writer received multiple root values")
                end match
            case frame :: _ if frame.kind == KindArray =>
                frame.values += value
            case frame :: _ =>
                frame.pendingField match
                    case Some(name) =>
                        discard(frame.fields.add(name, value))
                        frame.pendingField = None
                    case None =>
                        invalid("BSON document value has no field name")
                end match
        end match
    end pushValue

    private def popFrame(expectedKind: FrameKind): WriteFrame =
        stack match
            case frame :: tail if frame.kind == expectedKind =>
                stack = tail
                frame
            case _ =>
                invalid("BSON writer container stack is inconsistent")
    end popFrame

    private def writeDocument(fields: OrderedMap[String, BsonValue]): Array[Byte] =
        val body = new ByteArrayOutputStream(128)
        fields.foreach { (name, value) =>
            writeElement(body, name, value)
        }
        body.write(0)
        val payload = body.toByteArray
        val out     = new ByteArrayOutputStream(payload.length + 4)
        writeLE32(out, payload.length + 4)
        out.write(payload)
        out.toByteArray
    end writeDocument

    private def writeElement(out: ByteArrayOutputStream, name: String, value: BsonValue): Unit =
        validateCString(name)
        out.write(typeTag(value))
        writeCString(out, name)
        writePayload(out, value)
    end writeElement

    private def typeTag(value: BsonValue): Int =
        value match
            case DoubleValue(_)     => Double
            case StringValue(_)     => String
            case DocumentValue(_)   => Document
            case ArrayValue(_)      => Array
            case BinaryValue(_, _)  => Binary
            case BooleanValue(_)    => Boolean
            case DateTimeValue(_)   => DateTime
            case NullValue          => Null
            case Int32Value(_)      => Int32
            case Int64Value(_)      => Int64
            case Decimal128Value(_) => Decimal128
    end typeTag

    private def writePayload(out: ByteArrayOutputStream, value: BsonValue): Unit =
        value match
            case DoubleValue(value) =>
                writeLE64(out, java.lang.Double.doubleToLongBits(value))
            case StringValue(value) =>
                writeString(out, value)
            case DocumentValue(fields) =>
                val bytes = writeDocument(fields)
                out.write(bytes, 0, bytes.length)
            case ArrayValue(values) =>
                val builder = OrderedMapBuilder.init[String, BsonValue]
                values.zipWithIndex.foreach { (value, index) => discard(builder.add(index.toString, value)) }
                val bytes = writeDocument(builder.result())
                out.write(bytes, 0, bytes.length)
            case BinaryValue(value, subtype) =>
                val bytes = value.toArray
                writeLE32(out, bytes.length)
                out.write(subtype)
                out.write(bytes, 0, bytes.length)
            case BooleanValue(value) =>
                out.write(if value then 1 else 0)
            case DateTimeValue(value) =>
                writeLE64(out, value.toEpochMilli)
            case NullValue =>
                ()
            case Int32Value(value) =>
                writeLE32(out, value)
            case Int64Value(value) =>
                writeLE64(out, value)
            case Decimal128Value(value) =>
                val bytes = BsonDecimal128.encode(value)
                out.write(bytes, 0, bytes.length)
        end match
    end writePayload

    private def writeString(out: ByteArrayOutputStream, value: String): Unit =
        val bytes = value.getBytes(StandardCharsets.UTF_8)
        writeLE32(out, bytes.length + 1)
        out.write(bytes, 0, bytes.length)
        out.write(0)
    end writeString

    private def writeCString(out: ByteArrayOutputStream, value: String): Unit =
        val bytes = value.getBytes(StandardCharsets.UTF_8)
        out.write(bytes, 0, bytes.length)
        out.write(0)
    end writeCString

    private def validateCString(value: String): Unit =
        if value.indexOf('\u0000') >= 0 then invalid("BSON cstring field names cannot contain NUL")
    end validateCString

    private def writeLE32(out: ByteArrayOutputStream, value: Int): Unit =
        out.write(value & 0xff)
        out.write((value >>> 8) & 0xff)
        out.write((value >>> 16) & 0xff)
        out.write((value >>> 24) & 0xff)
    end writeLE32

    private def writeLE64(out: ByteArrayOutputStream, value: Long): Unit =
        var shift = 0
        while shift < 64 do
            out.write(((value >>> shift) & 0xff).toInt)
            shift += 8
        end while
    end writeLE64

    private def invalid(message: String): Nothing =
        throw SchemaNotSerializableException(message)(using kyo.Frame.internal)
    end invalid

end BsonWriter

object BsonWriter:
    def apply(config: kyo.Bson.Config = kyo.Bson.Config.Default): BsonWriter = new BsonWriter(config)
