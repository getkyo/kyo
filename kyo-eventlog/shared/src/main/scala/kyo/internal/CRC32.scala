package kyo.internal

/** Table-driven CRC32 with the reflected IEEE 802.3 polynomial (0xEDB88320). Produces bit-identical
  * output to `java.util.zip.CRC32` for any input. Defined in shared so every platform (jvm-native,
  * js-wasm) uses one implementation, making segment byte-identity hold by construction rather than
  * by "two standard implementations agree".
  *
  * API mirrors the `java.util.zip.CRC32` call pattern used in the shipped codec: create an instance,
  * call `update(bytes, off, len)` one or more times, then read `value` as an unsigned 32-bit Long.
  * The `of` helper covers the common single-call case.
  */
final private[kyo] class CRC32:
    private var crc: Int = 0xffffffff

    def update(bytes: Array[Byte], off: Int, len: Int): Unit =
        var i   = off
        val end = off + len
        while i < end do
            crc = (crc >>> 8) ^ CRC32.Table((crc ^ bytes(i)) & 0xff)
            i += 1
    end update

    def update(bytes: Array[Byte]): Unit = update(bytes, 0, bytes.length)

    /** The accumulated CRC32 value, as an unsigned 32-bit integer in a Long. */
    def value: Long = (~crc) & 0xffffffffL

    def reset(): Unit = crc = 0xffffffff

end CRC32

private[kyo] object CRC32:

    /** Precomputed 256-entry lookup table, reflected IEEE 802.3 polynomial 0xEDB88320. */
    val Table: Array[Int] =
        val t = new Array[Int](256)
        var i = 0
        while i < 256 do
            var c = i
            var j = 0
            while j < 8 do
                if (c & 1) != 0 then c = 0xedb88320 ^ (c >>> 1)
                else c = c >>> 1
                j += 1
            end while
            t(i) = c
            i += 1
        end while
        t
    end Table

    /** One-shot CRC32 of `bytes`. Equivalent to creating an instance, calling `update(bytes)`, and
      * reading `value.toInt`.
      */
    def of(bytes: Array[Byte]): Int =
        val crc = new CRC32()
        crc.update(bytes)
        crc.value.toInt
    end of

end CRC32
