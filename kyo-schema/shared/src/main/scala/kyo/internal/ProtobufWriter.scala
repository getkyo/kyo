package kyo.internal

import kyo.Codec.Writer
import kyo.Span
import scala.annotation.tailrec

/** Writes values in Protocol Buffers wire format.
  *
  * Field numbers are stable hash-based IDs computed from field names using MurmurHash3. This provides schema evolution compatibility -
  * adding or removing fields doesn't affect existing field numbers.
  *
  * Field ID overrides can be configured via `withFieldIdOverrides` for interoperability with existing `.proto` definitions.
  *
  * Nested messages are handled with a stack of byte buffers. When a nested object starts, a fresh buffer is pushed. When it ends, the
  * nested bytes are length-prefixed and written to the parent buffer.
  *
  * Repeated fields (arrays) write each element with its own tag, following standard protobuf packed=false encoding.
  */
final class ProtobufWriter extends Writer:

    // Wire type constants
    private val Varint          = 0
    private val Fixed64         = 1
    private val LengthDelimited = 2
    private val Fixed32         = 5

    private var bufferStack: List[java.io.ByteArrayOutputStream] =
        List(new java.io.ByteArrayOutputStream(256))
    private var currentFieldNumber: Int  = 0
    private var repeatedFieldNumber: Int = 0
    private var inArray: Boolean         = false

    // Field ID overrides for interop with existing .proto definitions
    private var fieldIdOverrides: Map[String, Int] = Map.empty

    /** Configures field ID overrides for encoding.
      *
      * When a field's name matches an override, that ID is used instead of the hash-based default. This enables interoperability with
      * existing Protocol Buffer schemas that use specific field numbers.
      *
      * @param overrides
      *   Map from field name to custom field ID
      * @return
      *   this writer for chaining
      */
    def withFieldIdOverrides(overrides: Map[String, Int]): this.type =
        fieldIdOverrides = overrides
        this

    private def current: java.io.ByteArrayOutputStream = bufferStack.head

    def objectStart(name: String, size: Int): Unit =
        if currentFieldNumber > 0 then
            // Nested message: push a new buffer. We'll length-prefix it at objectEnd.
            bufferStack = new java.io.ByteArrayOutputStream(128) :: bufferStack
    end objectStart

    def objectEnd(): Unit =
        if bufferStack.size > 1 then
            val nested = current.toByteArray
            bufferStack = bufferStack.tail
            // Write tag for nested message field
            writeTag(currentFieldNumber, LengthDelimited)
            writeVarint(nested.length.toLong)
            current.write(nested)
    end objectEnd

    def fieldBytes(nameBytes: Array[Byte], fieldId: Int): Unit =
        // Resolve field ID: check overrides only if configured (avoids allocation in common case)
        currentFieldNumber =
            if fieldIdOverrides.isEmpty then fieldId
            else
                val name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)
                fieldIdOverrides.getOrElse(name, fieldId)

    def arrayStart(size: Int): Unit =
        repeatedFieldNumber = currentFieldNumber
        inArray = true

    def arrayEnd(): Unit =
        inArray = false

    def int(value: Int): Unit =
        val fn = if inArray then repeatedFieldNumber else currentFieldNumber
        writeTag(fn, Varint)
        writeVarint(encodeZigZag32(value))
    end int

    def long(value: Long): Unit =
        val fn = if inArray then repeatedFieldNumber else currentFieldNumber
        writeTag(fn, Varint)
        writeVarint(encodeZigZag64(value))
    end long

    def string(value: String): Unit =
        val fn    = if inArray then repeatedFieldNumber else currentFieldNumber
        val bytes = value.getBytes("UTF-8")
        writeTag(fn, LengthDelimited)
        writeVarint(bytes.length.toLong)
        current.write(bytes)
    end string

    def double(value: Double): Unit =
        val fn = if inArray then repeatedFieldNumber else currentFieldNumber
        writeTag(fn, Fixed64)
        writeFixedLong(java.lang.Double.doubleToLongBits(value))
    end double

    def float(value: Float): Unit =
        val fn = if inArray then repeatedFieldNumber else currentFieldNumber
        writeTag(fn, Fixed32)
        writeFixedInt(java.lang.Float.floatToIntBits(value))
    end float

    def boolean(value: Boolean): Unit =
        val fn = if inArray then repeatedFieldNumber else currentFieldNumber
        writeTag(fn, Varint)
        writeVarint(if value then 1L else 0L)
    end boolean

    def short(value: Short): Unit = int(value.toInt)
    def byte(value: Byte): Unit   = int(value.toInt)
    def char(value: Char): Unit   = int(value.toInt)

    def nil(): Unit = () // protobuf has no null representation; omit the field

    def mapStart(size: Int): Unit = objectStart("", size)
    def mapEnd(): Unit            = objectEnd()

    def bytes(value: Span[Byte]): Unit =
        val fn  = if inArray then repeatedFieldNumber else currentFieldNumber
        val arr = value.toArray
        writeTag(fn, LengthDelimited)
        writeVarint(arr.length.toLong)
        current.write(arr)
    end bytes

    def bigInt(value: BigInt): Unit         = string(value.toString)
    def bigDecimal(value: BigDecimal): Unit = string(value.toString)

    def instant(value: java.time.Instant): Unit =
        val fn = if inArray then repeatedFieldNumber else currentFieldNumber
        writeTag(fn, Varint)
        writeVarint(encodeZigZag64(value.toEpochMilli))
    end instant

    def duration(value: java.time.Duration): Unit =
        val fn = if inArray then repeatedFieldNumber else currentFieldNumber
        writeTag(fn, Varint)
        writeVarint(encodeZigZag64(value.toMillis))
    end duration

    def resultBytes: Array[Byte] = bufferStack.last.toByteArray

    def result(): Span[Byte] = Span.from(resultBytes)

    // --- Internal helpers ---

    private def writeTag(fieldNumber: Int, wireType: Int): Unit =
        writeVarint(((fieldNumber.toLong) << 3) | wireType.toLong)

    private def writeVarint(value: Long): Unit =
        @tailrec def loop(v: Long): Unit =
            if (v & ~0x7fL) != 0L then
                current.write(((v & 0x7f) | 0x80).toInt)
                loop(v >>> 7)
            else
                current.write((v & 0x7f).toInt)
        loop(value)
    end writeVarint

    private def writeFixedLong(value: Long): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < 8 then
                current.write(((value >>> (i * 8)) & 0xff).toInt)
                loop(i + 1)
        loop(0)
    end writeFixedLong

    private def writeFixedInt(value: Int): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < 4 then
                current.write(((value >>> (i * 8)) & 0xff).toInt)
                loop(i + 1)
        loop(0)
    end writeFixedInt

    private def encodeZigZag32(n: Int): Long =
        ((n << 1) ^ (n >> 31)).toLong & 0xffffffffL

    private def encodeZigZag64(n: Long): Long =
        (n << 1) ^ (n >> 63)

end ProtobufWriter
