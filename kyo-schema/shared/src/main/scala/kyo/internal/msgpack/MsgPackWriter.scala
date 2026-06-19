package kyo.internal.msgpack

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kyo.Codec.Writer
import kyo.MsgPack
import kyo.Span
import scala.annotation.tailrec

/** Writes values in MessagePack wire format.
  *
  * MessagePack containers are count-prefixed. Arrays and maps always receive an exact element count (the collection schemas never omit
  * elements), so their headers are written immediately. Object bodies are buffered: the derivation omits absent `Option`/`Maybe` case-class
  * fields, so the static field count handed to [[objectStart]] can exceed the number of entries actually written. The writer counts the
  * [[field]]/[[fieldBytes]] calls inside each object and emits the real map header on [[objectEnd]], appending the buffered body to the
  * parent. This mirrors `ProtobufWriter`'s buffer stack and keeps the count-prefixed header honest.
  *
  * Field keys: [[fieldBytes]] (the macro-generated case-class and sealed-variant path) honors [[MsgPack.KeyEncoding]]. [[field]] (dynamic
  * `Map` keys and the `Result`/`Either`/tuple discriminator keys) always writes a string, because those keys are not recoverable from a
  * hash.
  */
final class MsgPackWriter(config: MsgPack.Config) extends Writer:
    import MsgPackFormat.*

    private val root = new ByteArrayOutputStream(256)

    // Output-target stack: the root plus one buffer per open object. Arrays/maps do not push (they
    // write through to the current target). `current` is always the head.
    private var bufStack: List[ByteArrayOutputStream] = List(root)

    // Kind of each open container (object/array/map), for `field`'s count decision.
    private var kindStack: List[Int] = List.empty[Int]

    // Entry counters, one per open object, parallel to the object frames in `bufStack`.
    private var objCounts: List[Int] = List.empty[Int]

    private def current: ByteArrayOutputStream = bufStack.head

    private def writeByte(b: Int): Unit = current.write(b & 0xff)

    private def writeBytesRaw(bytes: Array[Byte]): Unit = current.write(bytes, 0, bytes.length)

    private def writeBE16(out: ByteArrayOutputStream, v: Int): Unit =
        out.write((v >>> 8) & 0xff)
        out.write(v & 0xff)

    private def writeBE32(out: ByteArrayOutputStream, v: Int): Unit =
        out.write((v >>> 24) & 0xff)
        out.write((v >>> 16) & 0xff)
        out.write((v >>> 8) & 0xff)
        out.write(v & 0xff)
    end writeBE32

    private def writeBE64(v: Long): Unit =
        @tailrec def loop(shift: Int): Unit =
            if shift >= 0 then
                writeByte((v >>> shift).toInt)
                loop(shift - 8)
        loop(56)
    end writeBE64

    private def writeMapHeaderTo(out: ByteArrayOutputStream, size: Int): Unit =
        if size <= FixMax then out.write(FixMapPrefix | size)
        else if size <= 0xffff then
            out.write(Map16); writeBE16(out, size)
        else
            out.write(Map32); writeBE32(out, size)

    private def writeArrayHeaderTo(out: ByteArrayOutputStream, size: Int): Unit =
        if size <= FixMax then out.write(FixArrayPrefix | size)
        else if size <= 0xffff then
            out.write(Array16); writeBE16(out, size)
        else
            out.write(Array32); writeBE32(out, size)

    private def writeStringBytes(bytes: Array[Byte]): Unit =
        val len = bytes.length
        if len <= FixStrMax then writeByte(FixStrPrefix | len)
        else if len <= 0xff then
            writeByte(Str8); writeByte(len)
        else if len <= 0xffff then
            writeByte(Str16); writeBE16(current, len)
        else
            writeByte(Str32); writeBE32(current, len)
        end if
        writeBytesRaw(bytes)
    end writeStringBytes

    private def writeLongValue(value: Long): Unit =
        if value >= 0 then
            if value <= PositiveFixIntMax then writeByte(value.toInt)
            else if value <= 0xffL then
                writeByte(UInt8); writeByte(value.toInt)
            else if value <= 0xffffL then
                writeByte(UInt16); writeBE16(current, value.toInt)
            else if value <= 0xffffffffL then
                writeByte(UInt32); writeBE32(current, value.toInt)
            else
                writeByte(UInt64); writeBE64(value)
        else if value >= -32 then writeByte(value.toInt) // negative fixint (0xe0..0xff)
        else if value >= -128 then
            writeByte(Int8); writeByte(value.toInt)
        else if value >= -32768 then
            writeByte(Int16); writeBE16(current, value.toInt)
        else if value >= Int.MinValue.toLong then
            writeByte(Int32); writeBE32(current, value.toInt)
        else
            writeByte(Int64); writeBE64(value)
    end writeLongValue

    def objectStart(name: String, size: Int): Unit =
        bufStack = new ByteArrayOutputStream(64) :: bufStack
        kindStack = KindObject :: kindStack
        objCounts = 0 :: objCounts
    end objectStart

    def objectEnd(): Unit =
        val body  = bufStack.head.toByteArray
        val count = objCounts.head
        bufStack = bufStack.tail
        objCounts = objCounts.tail
        kindStack = kindStack.tail
        writeMapHeaderTo(current, count)
        current.write(body, 0, body.length)
    end objectEnd

    def arrayStart(size: Int): Unit =
        writeArrayHeaderTo(current, size)
        kindStack = KindArray :: kindStack

    def arrayEnd(): Unit = kindStack = kindStack.tail

    def mapStart(size: Int): Unit =
        writeMapHeaderTo(current, size)
        kindStack = KindMap :: kindStack

    def mapEnd(): Unit = kindStack = kindStack.tail

    private def countObjectField(): Unit =
        if kindStack.nonEmpty && kindStack.head == KindObject then
            objCounts = (objCounts.head + 1) :: objCounts.tail

    // Macro-generated schema field / variant keys: honor the configured key encoding.
    def fieldBytes(nameBytes: Array[Byte], fieldId: Int): Unit =
        countObjectField()
        config.keyEncoding match
            case MsgPack.KeyEncoding.StringName => writeStringBytes(nameBytes)
            case MsgPack.KeyEncoding.FieldId    => writeLongValue(fieldId.toLong)
    end fieldBytes

    // Dynamic map keys and the hand-written sum discriminator keys: always string (not hash-recoverable).
    override def field(name: String, fieldId: Int): Unit =
        countObjectField()
        writeStringBytes(name.getBytes(StandardCharsets.UTF_8))
    end field

    def string(value: String): Unit = writeStringBytes(value.getBytes(StandardCharsets.UTF_8))

    def int(value: Int): Unit     = writeLongValue(value.toLong)
    def long(value: Long): Unit   = writeLongValue(value)
    def short(value: Short): Unit = writeLongValue(value.toLong)
    def byte(value: Byte): Unit   = writeLongValue(value.toLong)
    def char(value: Char): Unit   = writeLongValue(value.toInt.toLong) // Char is unsigned 0..65535

    def float(value: Float): Unit =
        writeByte(Float32); writeBE32(current, java.lang.Float.floatToIntBits(value))

    def double(value: Double): Unit =
        writeByte(Float64); writeBE64(java.lang.Double.doubleToLongBits(value))

    def boolean(value: Boolean): Unit = writeByte(if value then True else False)

    def nil(): Unit = writeByte(Nil)

    def bytes(value: Span[Byte]): Unit =
        val arr = value.toArray
        val len = arr.length
        if len <= 0xff then
            writeByte(Bin8); writeByte(len)
        else if len <= 0xffff then
            writeByte(Bin16); writeBE16(current, len)
        else
            writeByte(Bin32); writeBE32(current, len)
        end if
        writeBytesRaw(arr)
    end bytes

    def bigInt(value: BigInt): Unit         = string(value.toString)
    def bigDecimal(value: BigDecimal): Unit = string(value.toString)

    def instant(value: java.time.Instant): Unit =
        config.temporalEncoding match
            case MsgPack.TemporalEncoding.Primitive =>
                writeArrayHeaderTo(current, 2)
                writeLongValue(value.getEpochSecond)
                writeLongValue(value.getNano.toLong)
            case MsgPack.TemporalEncoding.Extension =>
                // MessagePack timestamp 96: ext8, length 12, type -1, 32-bit nanos + 64-bit seconds.
                writeByte(Ext8)
                writeByte(12)
                writeByte(ExtTypeTimestamp & 0xff)
                writeBE32(current, value.getNano)
                writeBE64(value.getEpochSecond)
    end instant

    def duration(value: java.time.Duration): Unit =
        config.temporalEncoding match
            case MsgPack.TemporalEncoding.Primitive =>
                writeArrayHeaderTo(current, 2)
                writeLongValue(value.getSeconds)
                writeLongValue(value.getNano.toLong)
            case MsgPack.TemporalEncoding.Extension =>
                // Kyo duration extension: ext8, length 12, type 1, 64-bit seconds + 32-bit nanos.
                writeByte(Ext8)
                writeByte(12)
                writeByte(ExtTypeDuration & 0xff)
                writeBE64(value.getSeconds)
                writeBE32(current, value.getNano)
    end duration

    /** Materialized output as a raw byte array (for tests and the `result()` bridge). */
    def resultBytes: Array[Byte] = root.toByteArray

    def result(): Span[Byte] = Span.from(resultBytes)

end MsgPackWriter
