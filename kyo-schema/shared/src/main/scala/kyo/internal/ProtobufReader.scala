package kyo.internal

import kyo.*
import kyo.Codec.Reader
import scala.annotation.tailrec

/** Reads values from Protocol Buffers wire format.
  *
  * Field numbers are mapped back to 0-based indices by subtracting 1. The reader tracks field numbers internally and returns field names
  * from a provided mapping, or falls back to the field number as a string.
  *
  * Nested messages are handled by pushing a limit (end position) onto a stack when entering a length-delimited message field.
  */
final class ProtobufReader(data: Array[Byte])(using _frame: Frame) extends Reader:
    override def frame: Frame = _frame

    // Wire type constants
    private val Varint          = 0
    private val Fixed64         = 1
    private val LengthDelimited = 2
    private val Fixed32         = 5

    private var pos: Int                     = 0
    private var currentWireType: Int         = 0
    private var currentFieldNumber: Int      = 0
    private var limits: List[Int]            = List(data.length)
    private var fieldNames: Map[Int, String] = Map.empty
    private var pendingTag: Boolean          = false
    // Leaf-name field-id overrides for read/write symmetry under functional pinning. Empty in the
    // common case; threaded from Schema.fieldIdNameOverrides by Protobuf.decode when present.
    private var fieldIdOverrides: Map[String, Int] = Map.empty

    // Per-array decode frame for unpacked (packed=false) repeated fields. Protobuf writes
    // each repeated element as its own tag+value, so the elements of one field are separate
    // wire fields sharing a field number. The collection read loop calls hasNextElement()
    // before every element; the first element's tag was already consumed by the enclosing
    // field(), so a fresh frame starts with firstPending=true. For later elements
    // hasNextElement() peeks the next tag and consumes it only when it carries this field's
    // number, leaving any other tag for the enclosing message loop. Frames stack because
    // arrays nest across message boundaries (e.g. List[Nested] whose Nested holds a List[Int]).
    // packed/packedLimit track a packed scalar run: a scalar reader called inside this frame whose
    // consumed entry tag is wire type 2 (LengthDelimited) reads the length once, sets packedLimit,
    // flips packed=true, then reads bare values until packedLimit. A mixed producer (some packed,
    // some unpacked under the same field) is handled by resetting packed when the run is exhausted.
    final private class RepeatedFrame(val fieldNumber: Int, var firstPending: Boolean, var packed: Boolean, var packedLimit: Int)
    private var repeatedFrames: List[RepeatedFrame] = Nil

    // Per-map decode frame. A map is a repeated MapEntry message under the map field number
    // (see ProtobufWriter), key at field 1, value at field 2. The first entry's tag was
    // consumed by the enclosing field(), so firstPending starts true; hasNextEntry() enters
    // each entry message (pushing its limit) and field() reads the key and advances past the
    // value tag. field() is a map-key read only when an entry limit is pushed and we are at
    // the entry level (baseLimitDepth + 1); a field() nested inside an entry value is ordinary.
    // Frames stack for a map-valued map.
    final private class MapFrame(val fieldNumber: Int, val baseLimitDepth: Int, var firstPending: Boolean, var entryLimitPushed: Boolean)
    private var mapFrames: List[MapFrame] = Nil

    /** Set field name mapping for the current message level. */
    def withFieldNames(names: Map[Int, String]): this.type =
        fieldNames = names
        this

    /** Configures leaf-name field-id overrides so matchField resolves a pinned field by its
      * override number, mirroring the writer's resolution (read/write symmetry).
      */
    def withFieldIdOverrides(overrides: Map[String, Int]): this.type =
        fieldIdOverrides = overrides
        this

    def objectStart(): Int =
        checkDepth()
        // If the most recently parsed tag has LengthDelimited wire type, we're
        // entering a nested message: consume its length prefix and push a limit
        // so hasNextField() stops at the end of the nested payload. At the
        // top-level call (no preceding tag), currentWireType is 0 (Varint), so
        // we leave limits untouched: the top-level limit (data.length) set at
        // construction already bounds the message.
        if currentWireType == LengthDelimited then
            val len = readVarint().toInt
            if len < 0 || pos + len > data.length then
                throw TruncatedInputException(Protobuf(), s"message length $len exceeds remaining data")
            limits = (pos + len) :: limits
            currentWireType = Varint // reset so nested objectStart calls don't re-consume a length
        end if
        -1 // unknown field count: callers drive the loop via hasNextField()
    end objectStart

    def objectEnd(): Unit =
        decrementDepth()
        if limits.size > 1 then
            val limit = limits.head
            pos = limit // skip any unread bytes in the nested message
            limits = limits.tail
        end if
    end objectEnd

    def field(): String =
        mapFrames match
            case f :: _ if f.entryLimitPushed && limits.size == f.baseLimitDepth + 1 =>
                // Map entry: read the key at field 1, then advance past the value's field-2 tag
                // so the following value read sees the right wire type. Returns the decoded key.
                val keyTag = readVarint().toInt
                currentWireType = keyTag & 0x7
                val key    = string()
                val valTag = readVarint().toInt
                currentFieldNumber = valTag >>> 3
                currentWireType = valTag & 0x7
                pendingTag = false
                key
            case _ =>
                val tag = readVarint().toInt
                currentFieldNumber = tag >>> 3
                currentWireType = tag & 0x7
                pendingTag = false
                fieldNames.getOrElse(currentFieldNumber, currentFieldNumber.toString)
        end match
    end field

    override def fieldParse(): Unit =
        // Advance past the tag without allocating a String. matchField and
        // lastFieldName read from currentFieldNumber directly.
        val _ = field()
    end fieldParse

    override def matchField(nameBytes: Array[Byte]): Boolean =
        val name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)
        val resolved =
            if fieldIdOverrides.isEmpty then CodecMacro.fieldId(name)
            else fieldIdOverrides.getOrElse(name, CodecMacro.fieldId(name))
        currentFieldNumber == resolved
    end matchField

    override def lastFieldName(): String =
        fieldNames.getOrElse(currentFieldNumber, currentFieldNumber.toString)

    def hasNextField(): Boolean =
        pos < limits.head

    override def absentDefaultedFieldsMask(n: Int, defaultableFieldsMask: Long): Long =
        defaultableFieldsMask

    def hasNextElement(): Boolean =
        repeatedFrames match
            case frame :: _ =>
                if frame.packed && pos >= frame.packedLimit then
                    frame.packed = false // packed run exhausted; a mixed producer may continue
                if frame.packed then
                    true // still inside the current packed run, no tag to peek
                else if frame.firstPending then
                    frame.firstPending = false
                    true
                else if pos >= limits.head then false
                else
                    // Peek the next tag: another element only if it repeats this field's number.
                    val start = pos
                    val tag   = readVarint().toInt
                    if (tag >>> 3) == frame.fieldNumber then
                        currentFieldNumber = tag >>> 3
                        currentWireType = tag & 0x7
                        true
                    else
                        pos = start // un-read; the tag belongs to the enclosing message loop
                        false
                    end if
                end if
            case Nil =>
                hasNextField()
    end hasNextElement

    // Establishes (or continues) a packed scalar run for the current repeated frame. Returns true
    // when the current element is a bare packed value. On first call of a packed run, consumes the
    // record length and sets packedLimit; subsequent calls return true until the limit is reached.
    private def packedScalar(): Boolean =
        repeatedFrames match
            case frame :: _ =>
                if frame.packed then true
                else if currentWireType == LengthDelimited then
                    val len = readVarint().toInt
                    if len < 0 || pos + len > data.length then
                        throw TruncatedInputException(Protobuf(), s"packed field length $len exceeds remaining data")
                    frame.packedLimit = pos + len
                    frame.packed = true
                    true
                else false
            case Nil => false
    end packedScalar

    def int(): Int =
        if packedScalar() then
            decodeZigZag32(readVarint())
        else if currentWireType == Varint then
            decodeZigZag32(readVarint())
        else if currentWireType == Fixed32 then
            readFixedInt()
        else
            readVarint().toInt

    def long(): Long =
        if packedScalar() then
            decodeZigZag64(readVarint())
        else if currentWireType == Varint then
            decodeZigZag64(readVarint())
        else if currentWireType == Fixed64 then
            readFixedLong()
        else
            readVarint()

    def string(): String =
        val len = readVarint().toInt
        if len < 0 || pos + len > data.length then
            throw TruncatedInputException(Protobuf(), s"string length $len exceeds remaining data")
        val s = new String(data, pos, len, "UTF-8")
        pos += len
        s
    end string

    def double(): Double =
        val _ = packedScalar()
        java.lang.Double.longBitsToDouble(readFixedLong())

    def float(): Float =
        val _ = packedScalar()
        java.lang.Float.intBitsToFloat(readFixedInt())

    def boolean(): Boolean =
        val _ = packedScalar()
        readVarint() != 0L

    def short(): Short =
        val v = int()
        if v < Short.MinValue || v > Short.MaxValue then
            throw RangeException(v.toLong, "Short", Short.MinValue.toLong, Short.MaxValue.toLong)
        v.toShort
    end short

    def byte(): Byte =
        val v = int()
        if v < Byte.MinValue || v > Byte.MaxValue then
            throw RangeException(v.toLong, "Byte", Byte.MinValue.toLong, Byte.MaxValue.toLong)
        v.toByte
    end byte

    def char(): Char =
        val v = int()
        if v < Char.MinValue.toInt || v > Char.MaxValue.toInt then
            throw RangeException(v.toLong, "Char", Char.MinValue.toInt.toLong, Char.MaxValue.toInt.toLong)
        end if
        v.toChar
    end char

    def isNil(): Boolean = false // protobuf has no null

    def skip(): Unit =
        currentWireType match
            case 0 => discard(readVarint()) // varint
            case 1 =>
                if pos + 8 > data.length then
                    throw TruncatedInputException(Protobuf(), "not enough data to skip fixed64")
                pos += 8 // 64-bit
            case 2 =>
                val len = readVarint().toInt // length-delimited
                if len < 0 || pos + len > data.length then
                    throw TruncatedInputException(Protobuf(), s"skip length $len exceeds remaining data")
                end if
                pos += len
            case 5 =>
                if pos + 4 > data.length then
                    throw TruncatedInputException(Protobuf(), "not enough data to skip fixed32")
                pos += 4 // 32-bit
            case _ => ()

    override def captureValue(): Reader =
        val savedWireType = currentWireType
        val startPos      = pos
        skip()
        val endPos = pos
        val len    = endPos - startPos
        val slice  = new Array[Byte](len)
        java.lang.System.arraycopy(data, startPos, slice, 0, len)
        val sub = new ProtobufReader(slice)
        sub.currentWireType = savedWireType
        sub
    end captureValue

    def arrayStart(): Int =
        checkDepth()
        // The enclosing field() consumed the first element's tag; start the frame with it pending.
        repeatedFrames = new RepeatedFrame(currentFieldNumber, true, false, 0) :: repeatedFrames
        -1
    end arrayStart

    def arrayEnd(): Unit =
        decrementDepth()
        repeatedFrames match
            case _ :: rest => repeatedFrames = rest
            case Nil       => ()
    end arrayEnd

    def mapStart(): Int =
        checkDepth()
        // The enclosing field() consumed the first entry's MapEntry tag (currentWireType == LD);
        // entries are read by hasNextEntry() / field(). No wrapper message, so no limit is pushed here.
        mapFrames = new MapFrame(currentFieldNumber, limits.size, true, false) :: mapFrames
        -1
    end mapStart

    def mapEnd(): Unit =
        decrementDepth()
        mapFrames match
            case f :: rest =>
                if f.entryLimitPushed then
                    pos = limits.head
                    limits = limits.tail
                    f.entryLimitPushed = false
                end if
                mapFrames = rest
            case Nil => ()
        end match
    end mapEnd

    def hasNextEntry(): Boolean =
        mapFrames match
            case f :: _ =>
                if f.entryLimitPushed then
                    // Finished the previous entry: drop its limit and return to the map level.
                    pos = limits.head
                    limits = limits.tail
                    f.entryLimitPushed = false
                end if
                val more =
                    if f.firstPending then
                        f.firstPending = false
                        true
                    else if pos >= limits.head then false
                    else
                        val start = pos
                        val tag   = readVarint().toInt
                        if (tag >>> 3) == f.fieldNumber then
                            currentWireType = tag & 0x7
                            true
                        else
                            pos = start // un-read; the tag belongs to the enclosing message loop
                            false
                        end if
                if more then
                    // Enter the MapEntry message: consume its length prefix and push a limit.
                    val len = readVarint().toInt
                    if len < 0 || pos + len > data.length then
                        throw TruncatedInputException(Protobuf(), s"map entry length $len exceeds remaining data")
                    limits = (pos + len) :: limits
                    f.entryLimitPushed = true
                    true
                else false
                end if
            case Nil =>
                hasNextField()
    end hasNextEntry

    def bytes(): Span[Byte] =
        val len = readVarint().toInt
        if len < 0 || pos + len > data.length then
            throw TruncatedInputException(Protobuf(), s"bytes length $len exceeds remaining data")
        val arr = new Array[Byte](len)
        java.lang.System.arraycopy(data, pos, arr, 0, len)
        pos += len
        Span.from(arr)
    end bytes

    def bigInt(): BigInt =
        val s = string()
        try BigInt(s)
        catch
            case _: NumberFormatException =>
                throw ParseException(Protobuf(), s, "BigInt")
        end try
    end bigInt

    def bigDecimal(): BigDecimal =
        val s = string()
        try BigDecimal(s)
        catch
            case _: NumberFormatException =>
                throw ParseException(Protobuf(), s, "BigDecimal")
        end try
    end bigDecimal

    def instant(): java.time.Instant =
        java.time.Instant.ofEpochMilli(long())

    def duration(): java.time.Duration =
        java.time.Duration.ofMillis(long())

    // --- Internal helpers ---

    private def readVarint(): Long =
        @tailrec def loop(result: Long, shift: Int): Long =
            if pos >= data.length then
                throw TruncatedInputException(Protobuf(), "unexpected end of data in varint")
            val b = data(pos) & 0xff
            pos += 1
            val updated = result | ((b & 0x7f).toLong << shift)
            if (b & 0x80) != 0 then loop(updated, shift + 7)
            else updated
        end loop
        loop(0L, 0)
    end readVarint

    private def readFixedLong(): Long =
        if pos + 8 > data.length then
            throw TruncatedInputException(Protobuf(), "not enough data for fixed64")
        @tailrec def loop(i: Int, result: Long): Long =
            if i < 8 then
                loop(i + 1, result | ((data(pos + i) & 0xffL) << (i * 8)))
            else result
        val result = loop(0, 0L)
        pos += 8
        result
    end readFixedLong

    private def readFixedInt(): Int =
        if pos + 4 > data.length then
            throw TruncatedInputException(Protobuf(), "not enough data for fixed32")
        @tailrec def loop(i: Int, result: Int): Int =
            if i < 4 then
                loop(i + 1, result | ((data(pos + i) & 0xff) << (i * 8)))
            else result
        val result = loop(0, 0)
        pos += 4
        result
    end readFixedInt

    private def decodeZigZag32(n: Long): Int =
        val v = n.toInt
        ((v >>> 1) ^ -(v & 1))

    private def decodeZigZag64(n: Long): Long =
        ((n >>> 1) ^ -(n & 1))

end ProtobufReader
