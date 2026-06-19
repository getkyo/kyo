package kyo.internal.msgpack

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.Codec.IntrospectingReader
import kyo.Codec.Reader
import kyo.internal.CodecMacro
import scala.annotation.tailrec

/** Reads values from MessagePack wire format.
  *
  * MessagePack containers carry an explicit element count, so iteration is count-driven: `objectStart`/`arrayStart`/`mapStart` read the
  * header count and push it on a stack, and `hasNextField`/`hasNextElement`/`hasNextEntry` decrement the top of that stack. This is simpler
  * and more robust than a sentinel-scanning text reader.
  *
  * MessagePack is self-describing, so this reader is a [[Codec.IntrospectingReader]]: [[readStructure]] materializes an arbitrary wire value
  * into a [[Structure.Value]], enabling open-shaped wire protocols.
  *
  * Key dispatch follows the [[MsgPackWriter]] split: macro-generated case-class reads use [[fieldParse]]/[[matchField]], which accept either
  * a string key (byte comparison) or an integer `fieldId` key (compared against [[CodecMacro.fieldId]]), so bytes written under either
  * [[MsgPack.KeyEncoding]] decode correctly. Dynamic map and sum-discriminator reads use [[field]], which the writer always emitted as a
  * string.
  */
final class MsgPackReader(data: Array[Byte], config: MsgPack.Config)(using _frame: Frame) extends IntrospectingReader:
    import MsgPackFormat.*

    override def frame: Frame = _frame

    private val self     = MsgPack(config)
    private var pos: Int = 0

    // Remaining-element counts for the open containers, indexed [0, depthTop).
    private var counts   = new Array[Int](16)
    private var depthTop = 0

    // Last key parsed by fieldParse/field, for matchField/lastFieldName.
    private var keyIsInt      = false
    private var keyInt: Long  = 0L
    private var keyStart: Int = 0
    private var keyLen: Int   = 0

    // --- low-level byte access ---

    private def truncated(detail: String): Nothing =
        throw TruncatedInputException(self, detail)(using _frame)

    private def readByte(): Int =
        if pos >= data.length then truncated("unexpected end of input")
        val b = data(pos) & 0xff
        pos += 1
        b
    end readByte

    private def peekByte(): Int =
        if pos >= data.length then truncated("unexpected end of input")
        data(pos) & 0xff

    private def readS8(): Int  = readByte().toByte.toInt
    private def readU16(): Int = (readByte() << 8) | readByte()
    private def readS16(): Int = readU16().toShort.toInt

    private def readBE32(): Int =
        (readByte() << 24) | (readByte() << 16) | (readByte() << 8) | readByte()

    private def readU32Long(): Long = readBE32().toLong & 0xffffffffL

    private def readBE64(): Long =
        @tailrec def loop(i: Int, acc: Long): Long =
            if i < 8 then loop(i + 1, (acc << 8) | (readByte().toLong & 0xffL)) else acc
        loop(0, 0L)
    end readBE64

    /** Reads a 32-bit length field, rejecting values that do not fit a non-negative Int (which JVM arrays cannot address). */
    private def readLen32(): Int =
        val v = readBE32()
        if v < 0 then truncated(s"length $v exceeds addressable range")
        v
    end readLen32

    private def requireRemaining(n: Int): Unit =
        // Widen to Long so a large positive `n` plus `pos` cannot overflow Int and slip past the bound,
        // which would let a raw IndexOutOfBounds escape the Result[DecodeException, A] contract.
        if n < 0 || pos.toLong + n.toLong > data.length.toLong then truncated(s"need $n more bytes")

    private def readUtf8(len: Int): String =
        requireRemaining(len)
        val s = new String(data, pos, len, StandardCharsets.UTF_8)
        pos += len
        s
    end readUtf8

    private def formatName(b: Int): String =
        if isInt(b) then "integer"
        else if isStr(b) then "string"
        else if isMap(b) then "map"
        else if isArray(b) then "array"
        else if isBin(b) then "binary"
        else if isExt(b) then "extension"
        else if isFloat(b) then "float"
        else if b == Nil then "nil"
        else if b == True || b == False then "boolean"
        else f"unknown(0x$b%02x)"

    private def mismatch(expected: String, b: Int): Nothing =
        throw TypeMismatchException(Seq.empty, expected, formatName(b))(using _frame)

    // --- container count stack ---

    private def push(n: Int): Unit =
        if depthTop >= counts.length then counts = java.util.Arrays.copyOf(counts, counts.length * 2)
        counts(depthTop) = n
        depthTop += 1
    end push

    private def pop(): Unit = depthTop -= 1

    private def decTop(): Boolean =
        val i = depthTop - 1
        if i >= 0 && counts(i) > 0 then
            counts(i) -= 1
            true
        else false
        end if
    end decTop

    private def readMapHeader(): Int =
        val b = readByte()
        val n =
            if isFixMap(b) then b & 0x0f
            else if b == Map16 then readU16()
            else if b == Map32 then readLen32()
            else mismatch("map", b)
        checkCollectionSize(n)
        n
    end readMapHeader

    private def readArrayHeader(): Int =
        val b = readByte()
        val n =
            if isFixArray(b) then b & 0x0f
            else if b == Array16 then readU16()
            else if b == Array32 then readLen32()
            else mismatch("array", b)
        checkCollectionSize(n)
        n
    end readArrayHeader

    def objectStart(): Int =
        checkDepth()
        val n = readMapHeader()
        push(n)
        n
    end objectStart

    def objectEnd(): Unit =
        pop()
        decrementDepth()

    def arrayStart(): Int =
        checkDepth()
        val n = readArrayHeader()
        push(n)
        n
    end arrayStart

    def arrayEnd(): Unit =
        pop()
        decrementDepth()

    def mapStart(): Int = objectStart()
    def mapEnd(): Unit  = objectEnd()

    def hasNextField(): Boolean   = decTop()
    def hasNextElement(): Boolean = decTop()
    def hasNextEntry(): Boolean   = decTop()

    // --- key parsing ---

    private def parseKey(): Unit =
        val b = peekByte()
        if isStr(b) then
            val hb = readByte()
            val len =
                if isFixStr(hb) then hb & 0x1f
                else if hb == Str8 then readByte()
                else if hb == Str16 then readU16()
                else readLen32()
            requireRemaining(len)
            keyIsInt = false
            keyStart = pos
            keyLen = len
            pos += len
        else if isInt(b) then
            keyIsInt = true
            keyInt = readLongValue()
        else mismatch("map key (string or integer)", b)
        end if
    end parseKey

    def fieldParse(): Unit = parseKey()

    def matchField(nameBytes: Array[Byte]): Boolean =
        if keyIsInt then keyInt == CodecMacro.fieldId(new String(nameBytes, StandardCharsets.UTF_8)).toLong
        else
            keyLen == nameBytes.length && {
                @tailrec def eq(i: Int): Boolean =
                    if i >= keyLen then true
                    else if data(keyStart + i) != nameBytes(i) then false
                    else eq(i + 1)
                eq(0)
            }
    end matchField

    def lastFieldName(): String =
        if keyIsInt then keyInt.toString
        else new String(data, keyStart, keyLen, StandardCharsets.UTF_8)

    def field(): String =
        parseKey()
        lastFieldName()

    // --- scalar reads ---

    private def readLongValue(): Long =
        val b = readByte()
        if (b & 0x80) == 0 then b.toLong                // positive fixint
        else if (b & 0xe0) == 0xe0 then b.toByte.toLong // negative fixint
        else if b == UInt8 then readByte().toLong
        else if b == UInt16 then readU16().toLong
        else if b == UInt32 then readU32Long()
        // A uint64 >= 2^63 (only reachable from foreign input; this writer never emits one) returns as a
        // negative Long, the inherent JVM limitation shared by every JVM MessagePack library.
        else if b == UInt64 then readBE64()
        else if b == Int8 then readS8().toLong
        else if b == Int16 then readS16().toLong
        else if b == Int32 then readBE32().toLong
        else if b == Int64 then readBE64()
        else mismatch("integer", b)
        end if
    end readLongValue

    def long(): Long = readLongValue()

    def int(): Int =
        val v = readLongValue()
        if v < Int.MinValue.toLong || v > Int.MaxValue.toLong then
            throw RangeException(v, "Int", Int.MinValue.toLong, Int.MaxValue.toLong)(using _frame)
        v.toInt
    end int

    def short(): Short =
        val v = readLongValue()
        if v < Short.MinValue.toLong || v > Short.MaxValue.toLong then
            throw RangeException(v, "Short", Short.MinValue.toLong, Short.MaxValue.toLong)(using _frame)
        v.toShort
    end short

    def byte(): Byte =
        val v = readLongValue()
        if v < Byte.MinValue.toLong || v > Byte.MaxValue.toLong then
            throw RangeException(v, "Byte", Byte.MinValue.toLong, Byte.MaxValue.toLong)(using _frame)
        v.toByte
    end byte

    def char(): Char =
        val v = readLongValue()
        if v < Char.MinValue.toInt.toLong || v > Char.MaxValue.toInt.toLong then
            throw RangeException(v, "Char", Char.MinValue.toInt.toLong, Char.MaxValue.toInt.toLong)(using _frame)
        v.toChar
    end char

    private def readDoubleValue(): Double =
        val b = readByte()
        if b == Float64 then java.lang.Double.longBitsToDouble(readBE64())
        else if b == Float32 then java.lang.Float.intBitsToFloat(readBE32()).toDouble
        else mismatch("float", b)
    end readDoubleValue

    def double(): Double = readDoubleValue()

    def float(): Float =
        val b = readByte()
        if b == Float32 then java.lang.Float.intBitsToFloat(readBE32())
        else if b == Float64 then java.lang.Double.longBitsToDouble(readBE64()).toFloat
        else mismatch("float", b)
    end float

    def boolean(): Boolean =
        val b = readByte()
        if b == True then true
        else if b == False then false
        else mismatch("boolean", b)
    end boolean

    private def readStringValue(): String =
        val b = readByte()
        val len =
            if isFixStr(b) then b & 0x1f
            else if b == Str8 then readByte()
            else if b == Str16 then readU16()
            else if b == Str32 then readLen32()
            else mismatch("string", b)
        readUtf8(len)
    end readStringValue

    def string(): String = readStringValue()

    def isNil(): Boolean =
        if pos < data.length && (data(pos) & 0xff) == Nil then
            pos += 1
            true
        else false
    end isNil

    def bytes(): Span[Byte] =
        val b = readByte()
        val len =
            if b == Bin8 then readByte()
            else if b == Bin16 then readU16()
            else if b == Bin32 then readLen32()
            else mismatch("binary", b)
        requireRemaining(len)
        val arr = java.util.Arrays.copyOfRange(data, pos, pos + len)
        pos += len
        Span.from(arr)
    end bytes

    def bigInt(): BigInt =
        val s = readStringValue()
        try BigInt(s)
        catch case _: NumberFormatException => throw ParseException(self, s, "BigInt")(using _frame)
    end bigInt

    def bigDecimal(): BigDecimal =
        val s = readStringValue()
        try BigDecimal(s)
        catch case _: NumberFormatException => throw ParseException(self, s, "BigDecimal")(using _frame)
    end bigDecimal

    // --- extension header ---

    private def readExtHeader(): (Int, Int) =
        val b = readByte()
        val len =
            if b == FixExt1 then 1
            else if b == FixExt2 then 2
            else if b == FixExt4 then 4
            else if b == FixExt8 then 8
            else if b == FixExt16 then 16
            else if b == Ext8 then readByte()
            else if b == Ext16 then readU16()
            else if b == Ext32 then readLen32()
            else mismatch("extension", b)
        val tpe = readS8()
        (tpe, len)
    end readExtHeader

    def instant(): java.time.Instant =
        val b = peekByte()
        if isExt(b) then
            val (tpe, len) = readExtHeader()
            if tpe != ExtTypeTimestamp.toInt then
                throw ParseException(self, s"ext type $tpe", "Instant")(using _frame)
            len match
                case 4 => java.time.Instant.ofEpochSecond(readU32Long())
                case 8 =>
                    val d     = readBE64()
                    val nanos = (d >>> 34).toInt
                    val secs  = d & 0x3ffffffffL
                    java.time.Instant.ofEpochSecond(secs, nanos.toLong)
                case 12 =>
                    val nanos = readBE32()
                    val secs  = readBE64()
                    java.time.Instant.ofEpochSecond(secs, nanos.toLong)
                case other =>
                    throw ParseException(self, s"timestamp ext length $other", "Instant")(using _frame)
            end match
        else if isArray(b) then
            val n     = readArrayHeader()
            val secs  = readLongValue()
            val nanos = if n > 1 then readLongValue() else 0L
            java.time.Instant.ofEpochSecond(secs, nanos)
        else if isInt(b) then java.time.Instant.ofEpochMilli(readLongValue())
        else mismatch("Instant (array, timestamp ext, or integer)", b)
        end if
    end instant

    def duration(): java.time.Duration =
        val b = peekByte()
        if isExt(b) then
            val (tpe, len) = readExtHeader()
            if tpe != ExtTypeDuration.toInt then
                throw ParseException(self, s"ext type $tpe", "Duration")(using _frame)
            if len != 12 then
                throw ParseException(self, s"duration ext length $len", "Duration")(using _frame)
            val secs  = readBE64()
            val nanos = readBE32()
            java.time.Duration.ofSeconds(secs, nanos.toLong)
        else if isArray(b) then
            val n     = readArrayHeader()
            val secs  = readLongValue()
            val nanos = if n > 1 then readLongValue() else 0L
            java.time.Duration.ofSeconds(secs, nanos)
        else if isInt(b) then java.time.Duration.ofMillis(readLongValue())
        else mismatch("Duration (array, ext, or integer)", b)
        end if
    end duration

    // --- skip & capture ---

    private def skipBytes(n: Int): Unit =
        requireRemaining(n)
        pos += n

    // Skipped containers enforce the same depth and collection-size limits as the typed read path, so a
    // malformed unknown field (reachable via the case-class decoder's `skip` and `captureValue`) cannot
    // exceed `maxDepth`/`maxCollectionSize`. Recursion depth is therefore bounded by `maxDepth`, matching
    // the typed path, which recurses through schema reads.
    private def skipElems(n: Int): Unit =
        checkCollectionSize(n)
        checkDepth()
        var i = 0
        while i < n do
            skip()
            i += 1
        decrementDepth()
    end skipElems

    private def skipEntries(n: Int): Unit =
        checkCollectionSize(n)
        checkDepth()
        var i = 0
        while i < n do
            skip()
            skip()
            i += 1
        end while
        decrementDepth()
    end skipEntries

    def skip(): Unit =
        val b = readByte()
        if (b & 0x80) == 0 then ()         // positive fixint
        else if (b & 0xe0) == 0xe0 then () // negative fixint
        else if isFixStr(b) then skipBytes(b & 0x1f)
        else if isFixArray(b) then skipElems(b & 0x0f)
        else if isFixMap(b) then skipEntries(b & 0x0f)
        else
            b match
                case Nil | True | False       => ()
                case UInt8 | Int8             => skipBytes(1)
                case UInt16 | Int16           => skipBytes(2)
                case UInt32 | Int32 | Float32 => skipBytes(4)
                case UInt64 | Int64 | Float64 => skipBytes(8)
                case Str8 | Bin8              => skipBytes(readByte())
                case Str16 | Bin16            => skipBytes(readU16())
                case Str32 | Bin32            => skipBytes(readLen32())
                case Array16                  => skipElems(readU16())
                case Array32                  => skipElems(readLen32())
                case Map16                    => skipEntries(readU16())
                case Map32                    => skipEntries(readLen32())
                case FixExt1                  => skipBytes(1 + 1)
                case FixExt2                  => skipBytes(1 + 2)
                case FixExt4                  => skipBytes(1 + 4)
                case FixExt8                  => skipBytes(1 + 8)
                case FixExt16                 => skipBytes(1 + 16)
                case Ext8                     => skipBytes(1 + readByte())
                case Ext16                    => skipBytes(1 + readU16())
                case Ext32                    => skipBytes(1 + readLen32())
                case _                        => mismatch("value", b)
        end if
    end skip

    def captureValue(): Reader =
        val start = pos
        skip()
        val slice = java.util.Arrays.copyOfRange(data, start, pos)
        new MsgPackReader(slice, config)(using _frame)
    end captureValue

    // --- introspection (self-describing open value) ---

    override def readStructure(): Structure.Value =
        val b = peekByte()
        if isMap(b) then readStructureMap()
        else if isArray(b) then readStructureArray()
        else if isStr(b) then Structure.Value.Str(readStringValue())
        else if b == Nil then
            pos += 1
            Structure.Value.Null
        else if b == True || b == False then Structure.Value.Bool(boolean())
        else if isInt(b) then Structure.Value.Integer(readLongValue())
        else if isFloat(b) then Structure.Value.Decimal(readDoubleValue())
        else if isBin(b) then
            throw ParseException(self, formatName(b), "Structure.Value (MessagePack binary is not representable)")(using _frame)
        else if isExt(b) then
            throw ParseException(self, formatName(b), "Structure.Value (MessagePack extension is not representable)")(using _frame)
        else mismatch("Structure.Value", b)
        end if
    end readStructure

    private def readStructureMap(): Structure.Value =
        checkDepth()
        val n       = readMapHeader()
        val entries = new Array[(Structure.Value, Structure.Value)](n)
        var allStr  = true
        var i       = 0
        while i < n do
            val k = readStructure()
            val v = readStructure()
            if !k.isInstanceOf[Structure.Value.Str] then allStr = false
            entries(i) = (k, v)
            i += 1
        end while
        decrementDepth()
        if allStr then
            Structure.Value.Record(Chunk.from(entries.toIndexedSeq.map {
                case (Structure.Value.Str(name), v) => (name, v)
                case _                              => ("", Structure.Value.Null) // unreachable: allStr guarded
            }))
        else Structure.Value.MapEntries(Chunk.from(entries.toIndexedSeq))
        end if
    end readStructureMap

    private def readStructureArray(): Structure.Value =
        checkDepth()
        val n   = readArrayHeader()
        val arr = new Array[Structure.Value](n)
        var i   = 0
        while i < n do
            arr(i) = readStructure()
            i += 1
        decrementDepth()
        Structure.Value.Sequence(Chunk.from(arr.toIndexedSeq))
    end readStructureArray

end MsgPackReader
