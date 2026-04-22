package kyo.internal

import kyo.*
import kyo.Codec.Reader
import kyo.Span
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

    /** Set field name mapping for the current message level. */
    def withFieldNames(names: Map[Int, String]): this.type =
        fieldNames = names
        this

    def objectStart(): Int =
        checkDepth()
        // If the most recently parsed tag has LengthDelimited wire type, we're
        // entering a nested message: consume its length prefix and push a limit
        // so hasNextField() stops at the end of the nested payload. At the
        // top-level call (no preceding tag), currentWireType is 0 (Varint), so
        // we leave limits untouched — the top-level limit (data.length) set at
        // construction already bounds the message.
        if currentWireType == LengthDelimited then
            val len = readVarint().toInt
            if len < 0 || pos + len > data.length then
                throw TruncatedInputException(Protobuf(), s"message length $len exceeds remaining data")
            limits = (pos + len) :: limits
            currentWireType = Varint // reset so nested objectStart calls don't re-consume a length
        end if
        -1 // unknown field count — callers drive the loop via hasNextField()
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
        val tag = readVarint().toInt
        currentFieldNumber = tag >>> 3
        currentWireType = tag & 0x7
        pendingTag = false
        // If this is a length-delimited field and we're at the message level,
        // push a limit for nested message reading
        if currentWireType == LengthDelimited then
            // peek: could be string, bytes, or nested message
            // We don't push limit here; the caller decides (objectStart vs string)
            ()
        end if
        fieldNames.getOrElse(currentFieldNumber, currentFieldNumber.toString)
    end field

    override def fieldParse(): Unit =
        // Advance past the tag without allocating a String. matchField and
        // lastFieldName read from currentFieldNumber directly.
        val _ = field()
    end fieldParse

    override def matchField(nameBytes: Array[Byte]): Boolean =
        val name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)
        currentFieldNumber == CodecMacro.fieldId(name)
    end matchField

    override def lastFieldName(): String =
        fieldNames.getOrElse(currentFieldNumber, currentFieldNumber.toString)

    def hasNextField(): Boolean =
        pos < limits.head

    def hasNextElement(): Boolean = hasNextField()

    def int(): Int =
        if currentWireType == Varint then
            decodeZigZag32(readVarint())
        else if currentWireType == Fixed32 then
            readFixedInt()
        else
            readVarint().toInt

    def long(): Long =
        if currentWireType == Varint then
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
        java.lang.Double.longBitsToDouble(readFixedLong())

    def float(): Float =
        java.lang.Float.intBitsToFloat(readFixedInt())

    def boolean(): Boolean = readVarint() != 0L

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
        checkDepth(); -1
    def arrayEnd(): Unit = decrementDepth()

    def mapStart(): Int         = objectStart()
    def mapEnd(): Unit          = objectEnd()
    def hasNextEntry(): Boolean = hasNextField()

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
