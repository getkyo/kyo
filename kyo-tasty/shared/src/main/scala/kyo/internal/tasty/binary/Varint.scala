package kyo.internal.tasty.binary

/** Thrown when a varint continuation run exceeds the allowed byte count for the target type. */
class MalformedVarintException(val byteOffset: Long, msg: String) extends RuntimeException(msg)

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
  * NOT zigzag encoding. The plan's "(n>>>1)^-(n&1)" formula is incorrect; the actual dotty TastyReader.readLongInt uses sign extension as
  * implemented below.
  *
  * See: dotty/tools/tasty/TastyReader.scala, readLongNat (lines ~68-77), readLongInt (lines ~81-89).
  */

object Varint:

    /** Read an unsigned big-endian base-128 Nat from `view` as Int.
      *
      * Verbatim semantics from TastyReader.readLongNat, narrowed to Int. The loop continues while the continuation bit (0x80) is CLEAR,
      * stopping at the byte where 0x80 is SET (the terminating byte). Throws MalformedVarintException if more than 5 continuation bytes are
      * consumed (Int overflow guard).
      */
    def readNat(view: ByteView): Int =
        var b     = 0
        var x     = 0
        var bytes = 0
        while
            if bytes >= 5 then
                throw new MalformedVarintException(view.position.toLong, "varint: continuation runs past 5 bytes (Int overflow)")
            b = view.readByte() & 0xff
            x = (x << 7) | (b & 0x7f)
            bytes += 1
            (b & 0x80) == 0
        do ()
        end while
        x
    end readNat

    /** Read an unsigned big-endian base-128 Nat from `view` as Long.
      *
      * Verbatim from TastyReader.readLongNat: var b = 0L; var x = 0L while { b = bytes(bp); x = (x << 7) | (b & 0x7f); bp += 1; (b & 0x80) ==
      * 0 } () x. Throws MalformedVarintException if more than 10 continuation bytes are consumed (Long overflow guard).
      */
    def readLongNat(view: ByteView): Long =
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
    def readInt(view: ByteView): Int =
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
    def readLongInt(view: ByteView): Long =
        var b       = view.readByte() & 0xff
        var x: Long = ((b << 1).toByte >> 1).toLong
        while (b & 0x80) == 0 do
            b = view.readByte() & 0xff
            x = (x << 7) | (b & 0x7f).toLong
        x
    end readLongInt

end Varint
