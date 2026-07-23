package kyo.internal

import kyo.Codec.Writer
import kyo.Span
import scala.annotation.tailrec

/** Writes values in Protocol Buffers wire format.
  *
  * Field numbers are stable hash-based IDs computed from field names (XXH32 applied to the name's JLS string hash). This provides schema evolution compatibility -
  * adding or removing fields doesn't affect existing field numbers.
  *
  * Field ID overrides can be configured via `withFieldIdOverrides` for interoperability with existing `.proto` definitions.
  *
  * Nested messages are handled with a stack of byte buffers. When a nested object starts, a fresh buffer is pushed. When it ends, the
  * nested bytes are length-prefixed and written to the parent buffer.
  *
  * Repeated fields write each element with its own tag (standard protobuf packed=false). A map is written as a repeated `MapEntry`
  * message under the map field number, with the key at field 1 and the value at field 2 (standard proto3 `map<K, V>`).
  */
final class ProtobufWriter extends Writer:

    override def codecName: String = "Protobuf"

    // Wire type constants
    private val Varint          = 0
    private val Fixed64         = 1
    private val LengthDelimited = 2
    private val Fixed32         = 5

    private var bufferStack: List[java.io.ByteArrayOutputStream] =
        List(new java.io.ByteArrayOutputStream(256))
    private var currentFieldNumber: Int = 0
    // Stack of field numbers captured at each nested objectStart. Pops in objectEnd
    // and is used to emit the outer message's tag, so writes to inner fields don't
    // clobber the nested-message field number before we length-prefix its bytes.
    private var fieldNumberStack: List[Int] = Nil
    private var repeatedFieldNumber: Int    = 0
    private var inArray: Boolean            = false

    // Saved (inArray, repeatedFieldNumber) pairs. A nested objectStart and a nested
    // arrayStart each push the current array state and the matching objectEnd / arrayEnd
    // restores it, so a repeated message's own fields are written under their own field
    // numbers (not the enclosing list's) and nested arrays restore the outer array context.
    private var arrayStateStack: List[(Boolean, Int)] = Nil

    // Per-array packed accumulator for repeated SCALAR fields. arrayStart pushes a fresh buffer,
    // each scalar element appends its raw value bytes (no per-element tag), and arrayEnd flushes
    // the buffer as one length-delimited record under the repeated field number. Stacked so nested
    // scalar arrays are isolated. A null head means the current array is not a packed-scalar array
    // (its elements are strings/bytes/messages, which stay length-delimited per element).
    private var packedBufferStack: List[java.io.ByteArrayOutputStream] = Nil

    // Map encoding state. A map is a repeated MapEntry message under the map field number.
    // mapStart records that field number and the buffer depth at the map level; a field(key)
    // call at that depth opens a new entry message (key at field 1), the value writes at
    // field 2, and the entry is length-prefixed on the next key or on mapEnd. Frames stack
    // for a map-valued map.
    final private class MapFrame(val fieldNumber: Int, val baseDepth: Int, var entryOpen: Boolean)
    private var mapFrames: List[MapFrame] = Nil

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
    override def withFieldIdOverrides(overrides: Map[String, Int]): this.type =
        fieldIdOverrides = overrides
        this

    override def supportsFieldIdOverrides: Boolean = true

    /** The current field-id override map, read by a caller that is about to replace it with a
      * nested schema's own overrides, so the prior value can be restored once that nested write
      * completes.
      */
    override private[kyo] def fieldIdOverridesSnapshot: Map[String, Int] = fieldIdOverrides

    private def current: java.io.ByteArrayOutputStream = bufferStack.head

    def objectStart(name: String, size: Int): Unit =
        if currentFieldNumber > 0 then
            // Nested message: push a new buffer and remember the outer field number
            // so objectEnd can length-prefix the nested bytes under the correct tag,
            // even after inner fieldBytes calls overwrite currentFieldNumber. Save the
            // array context and reset it: the message's own fields are not array elements.
            fieldNumberStack = currentFieldNumber :: fieldNumberStack
            arrayStateStack = (inArray, repeatedFieldNumber) :: arrayStateStack
            inArray = false
            bufferStack = new java.io.ByteArrayOutputStream(128) :: bufferStack
    end objectStart

    def objectEnd(): Unit =
        if bufferStack.size > 1 then
            val nested = current.toByteArray
            bufferStack = bufferStack.tail
            val outerFieldNumber = fieldNumberStack.head
            fieldNumberStack = fieldNumberStack.tail
            // Write tag for nested message field using the field number captured at
            // objectStart, not the currentFieldNumber (which may have been clobbered
            // by inner fieldBytes calls during the nested write).
            writeTag(outerFieldNumber, LengthDelimited)
            writeVarint(nested.length.toLong)
            current.write(nested)
            // Restore the outer field number so sibling writes after this objectEnd
            // continue to use the parent message's currentFieldNumber (e.g. repeated
            // nested messages in a List field share the same field number).
            currentFieldNumber = outerFieldNumber
            arrayStateStack match
                case (ia, rfn) :: rest =>
                    inArray = ia
                    repeatedFieldNumber = rfn
                    arrayStateStack = rest
                case Nil => ()
            end match
    end objectEnd

    def fieldBytes(nameBytes: Array[Byte], fieldId: Int): Unit =
        mapFrames match
            case f :: _ if bufferStack.size == f.baseDepth + (if f.entryOpen then 1 else 0) =>
                // Map key: close the previous entry, open a new MapEntry message, write key = field 1.
                if f.entryOpen then closeMapEntry(f)
                openMapEntry(f, nameBytes)
            case _ =>
                // Resolve field ID: check overrides only if configured (avoids allocation in common case)
                currentFieldNumber =
                    if fieldIdOverrides.isEmpty then fieldId
                    else
                        val name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)
                        fieldIdOverrides.getOrElse(name, fieldId)
        end match
    end fieldBytes

    private def openMapEntry(f: MapFrame, keyBytes: Array[Byte]): Unit =
        // The entry message is emitted under the map field number; objectStart captures it.
        currentFieldNumber = f.fieldNumber
        objectStart("", 2)
        // key = field 1, length-delimited
        writeTag(1, LengthDelimited)
        writeVarint(keyBytes.length.toLong)
        current.write(keyBytes)
        // value -> field 2
        currentFieldNumber = 2
        f.entryOpen = true
    end openMapEntry

    private def closeMapEntry(f: MapFrame): Unit =
        objectEnd()
        f.entryOpen = false
    end closeMapEntry

    def arrayStart(size: Int): Unit =
        arrayStateStack = (inArray, repeatedFieldNumber) :: arrayStateStack
        packedBufferStack = new java.io.ByteArrayOutputStream(64) :: packedBufferStack
        repeatedFieldNumber = currentFieldNumber
        inArray = true
    end arrayStart

    def arrayEnd(): Unit =
        packedBufferStack match
            case buf :: bufRest =>
                if buf.size() > 0 then
                    // Flush the accumulated scalars as one packed length-delimited record.
                    val packed = buf.toByteArray
                    writeTag(repeatedFieldNumber, LengthDelimited)
                    writeVarint(packed.length.toLong)
                    current.write(packed)
                end if
                packedBufferStack = bufRest
            case Nil => ()
        end match
        arrayStateStack match
            case (ia, rfn) :: rest =>
                inArray = ia
                repeatedFieldNumber = rfn
                arrayStateStack = rest
            case Nil => inArray = false
        end match
    end arrayEnd

    private def packedTarget: java.io.ByteArrayOutputStream =
        // Non-null head buffer means the current array packs its scalar elements.
        if inArray then packedBufferStack.headOption.orNull else null

    def int(value: Int): Unit =
        val buf = packedTarget
        if buf != null then writeVarintTo(buf, encodeZigZag32(value))
        else
            writeTag(currentFieldNumber, Varint)
            writeVarint(encodeZigZag32(value))
        end if
    end int

    def long(value: Long): Unit =
        val buf = packedTarget
        if buf != null then writeVarintTo(buf, encodeZigZag64(value))
        else
            writeTag(currentFieldNumber, Varint)
            writeVarint(encodeZigZag64(value))
        end if
    end long

    def string(value: String): Unit =
        val fn    = if inArray then repeatedFieldNumber else currentFieldNumber
        val bytes = value.getBytes("UTF-8")
        writeTag(fn, LengthDelimited)
        writeVarint(bytes.length.toLong)
        current.write(bytes)
    end string

    def double(value: Double): Unit =
        val buf = packedTarget
        if buf != null then writeFixedLongTo(buf, java.lang.Double.doubleToLongBits(value))
        else
            writeTag(currentFieldNumber, Fixed64)
            writeFixedLong(java.lang.Double.doubleToLongBits(value))
        end if
    end double

    def float(value: Float): Unit =
        val buf = packedTarget
        if buf != null then writeFixedIntTo(buf, java.lang.Float.floatToIntBits(value))
        else
            writeTag(currentFieldNumber, Fixed32)
            writeFixedInt(java.lang.Float.floatToIntBits(value))
        end if
    end float

    def boolean(value: Boolean): Unit =
        val buf = packedTarget
        if buf != null then writeVarintTo(buf, if value then 1L else 0L)
        else
            writeTag(currentFieldNumber, Varint)
            writeVarint(if value then 1L else 0L)
        end if
    end boolean

    def short(value: Short): Unit = int(value.toInt)
    def byte(value: Byte): Unit   = int(value.toInt)
    def char(value: Char): Unit   = int(value.toInt)

    def nil(): Unit = () // protobuf has no null representation; omit the field

    def mapStart(size: Int): Unit =
        mapFrames = new MapFrame(currentFieldNumber, bufferStack.size, false) :: mapFrames
    end mapStart

    def mapEnd(): Unit =
        mapFrames match
            case f :: rest =>
                if f.entryOpen then closeMapEntry(f)
                mapFrames = rest
            case Nil => ()
    end mapEnd

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
        val buf = packedTarget
        if buf != null then writeVarintTo(buf, encodeZigZag64(value.toEpochMilli))
        else
            writeTag(currentFieldNumber, Varint)
            writeVarint(encodeZigZag64(value.toEpochMilli))
        end if
    end instant

    def duration(value: java.time.Duration): Unit =
        val buf = packedTarget
        if buf != null then writeVarintTo(buf, encodeZigZag64(value.toMillis))
        else
            writeTag(currentFieldNumber, Varint)
            writeVarint(encodeZigZag64(value.toMillis))
        end if
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

    // OutputStream-targeted raw writers used to accumulate packed scalar elements (no tag).
    private def writeVarintTo(out: java.io.ByteArrayOutputStream, value: Long): Unit =
        @tailrec def loop(v: Long): Unit =
            if (v & ~0x7fL) != 0L then
                out.write(((v & 0x7f) | 0x80).toInt)
                loop(v >>> 7)
            else
                out.write((v & 0x7f).toInt)
        loop(value)
    end writeVarintTo

    private def writeFixedLongTo(out: java.io.ByteArrayOutputStream, value: Long): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < 8 then
                out.write(((value >>> (i * 8)) & 0xff).toInt)
                loop(i + 1)
        loop(0)
    end writeFixedLongTo

    private def writeFixedIntTo(out: java.io.ByteArrayOutputStream, value: Int): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < 4 then
                out.write(((value >>> (i * 8)) & 0xff).toInt)
                loop(i + 1)
        loop(0)
    end writeFixedIntTo

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
