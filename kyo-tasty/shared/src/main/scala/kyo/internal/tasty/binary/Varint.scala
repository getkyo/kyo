package kyo.internal.tasty.binary

import kyo.AllowUnsafe

/** TASTy LEB128 varint decoding.
  *
  * TASTy uses big-endian base-128 encoding, which is the OPPOSITE of standard little-endian LEB128:
  *   - Continuation bytes have bit 0x80 CLEAR (not set).
  *   - The terminating (last) byte has 0x80 SET.
  *   - Bytes are accumulated by shifting left 7 bits and OR-ing in the low 7 bits.
  *
  * Reference: dotty TastyReader.scala readLongNat / readLongInt.
  *
  * IMPORTANT: Signed integers (readInt / readLongInt) use 2's complement encoding with sign extension on bit 6 of the first byte. This is
  * NOT zigzag encoding. A zigzag formula "(n>>>1)^-(n&1)" would be incorrect; the actual dotty TastyReader.readLongInt uses sign extension as
  * implemented below.
  *
  * See: dotty/tools/tasty/TastyReader.scala, readLongNat (lines ~68-77), readLongInt (lines ~81-89).
  */

object Varint:

    /** Read an unsigned big-endian base-128 Nat from `view` as Int.
      *
      * Matches dotty's TastyReader.readNat: delegates to readLongNat and truncates the Long result to Int. No separate per-byte
      * Int-range check is applied; dotty's TastyReader.readNat does readLongNat().toInt without a range check, and kyo matches that behavior
      * so that large-section offsets encoded in 6-10 bytes are accepted (a 5-byte cap would be too strict).
      */
    def readNat(view: ByteView)(using AllowUnsafe): Int =
        readLongNat(view).toInt
    end readNat

    /** Read an unsigned big-endian base-128 Nat from `view` as Long.
      *
      * Verbatim from TastyReader.readLongNat: var b = 0L; var x = 0L while { b = bytes(bp); x = (x << 7) | (b & 0x7f); bp += 1; (b & 0x80) ==
      * 0 } () x. Throws MalformedVarintException if more than 10 continuation bytes are consumed (Long overflow guard).
      */
    def readLongNat(view: ByteView)(using AllowUnsafe): Long =
        var b     = 0L
        var x     = 0L
        var bytes = 0
        while
            if bytes >= 10 then
                throw new MalformedVarintException(view.position.toLong, "varint: continuation runs past 10 bytes (Long overflow)")
            b = view.readByte() & 0xffL
            x = (x << 7) | (b & 0x7fL)
            bytes += 1
            (b & 0x80L) == 0L
        do ()
        end while
        x
    end readLongNat

    /** Read a signed big-endian base-128 Int from `view`.
      *
      * Uses 2's complement encoding with sign extension on bit 6 of the first byte. NOT zigzag. Verbatim from TastyReader.readLongInt
      * (narrowed to Int):
      *
      * var b = bytes(bp) var x: Long = (b << 1).toByte >> 1 // sign extend with bit 6 bp += 1 while ((b & 0x80) == 0) { b = bytes(bp) x =
      * (x << 7) | (b & 0x7f) bp += 1 } x
      *
      * The first byte's bit 6 (value 0x40) carries the sign. The expression `(b << 1).toByte >> 1` shifts the byte left 1 (discarding bit 7
      * which is the continuation bit), then sign-extends as a Byte by shifting right arithmetically. This propagates bit 6 into all higher
      * positions of the Long accumulator.
      */
    def readInt(view: ByteView)(using AllowUnsafe): Int =
        var b       = view.readByte() & 0xff
        var x: Long = ((b << 1).toByte >> 1).toLong
        while (b & 0x80) == 0 do
            b = view.readByte() & 0xff
            x = (x << 7) | (b & 0x7f).toLong
        x.toInt
    end readInt

    /** Read a signed big-endian base-128 Long from `view`.
      *
      * Same 2's complement semantics as readInt but accumulates into Long. Verbatim from TastyReader.readLongInt.
      */
    def readLongInt(view: ByteView)(using AllowUnsafe): Long =
        var b       = view.readByte() & 0xff
        var x: Long = ((b << 1).toByte >> 1).toLong
        while (b & 0x80) == 0 do
            b = view.readByte() & 0xff
            x = (x << 7) | (b & 0x7f).toLong
        x
    end readLongInt

    /** Write an unsigned big-endian base-128 Nat to `out` as Int.
      *
      * Encodes `v` in the same big-endian base-128 format that `readNat` decodes: groups of 7 bits from most-significant to
      * least-significant, continuation bytes have 0x80 CLEAR, the terminating (last) byte has 0x80 SET.
      */
    private[kyo] def writeNat(out: scala.collection.mutable.ArrayBuffer[Byte], v: Int)(using AllowUnsafe): Unit =
        val buf   = new Array[Byte](5)
        var pos   = 4
        var value = v
        buf(pos) = (value & 0x7f | 0x80).toByte
        value = value >>> 7
        while value != 0 do
            pos -= 1
            buf(pos) = (value & 0x7f).toByte
            value = value >>> 7
        end while
        var i = pos
        while i < 5 do
            out += buf(i)
            i += 1
        end while
    end writeNat

    /** Write an unsigned big-endian base-128 Nat to `out` as Long.
      *
      * Same encoding as `writeNat` but accepts a Long value. The terminating byte has 0x80 SET, continuation bytes have 0x80 CLEAR.
      */
    private[kyo] def writeLongNat(out: scala.collection.mutable.ArrayBuffer[Byte], v: Long)(using AllowUnsafe): Unit =
        val buf   = new Array[Byte](10)
        var pos   = 9
        var value = v
        buf(pos) = ((value & 0x7fL) | 0x80L).toByte
        value = value >>> 7
        while value != 0L do
            pos -= 1
            buf(pos) = (value & 0x7fL).toByte
            value = value >>> 7
        end while
        var i = pos
        while i < 10 do
            out += buf(i)
            i += 1
        end while
    end writeLongNat

end Varint
