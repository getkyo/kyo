package kyo.internal.crypto

/** What the digests over 64-byte blocks share: the buffer that feeds whole blocks to a compression function and pads the tail with
  * `0x80`, zeros and the 64-bit bit length, the byte-order writers of the output words, and the block count of a derived length.
  */
private[kyo] object Blocks:

    /** `ceil(length / size)` for `length >= 0` and `size > 0`, without forming `length + size - 1`, which wraps for a `length` within
      * `size` of `Int.MaxValue`.
      */
    def count(length: Int, size: Int): Int =
        length / size + (if length % size == 0 then 0 else 1)

    def writeIntBigEndian(buf: Array[Byte], offset: Int, value: Int): Unit =
        buf(offset) = (value >>> 24).toByte
        buf(offset + 1) = (value >>> 16).toByte
        buf(offset + 2) = (value >>> 8).toByte
        buf(offset + 3) = value.toByte
    end writeIntBigEndian

    def writeLongBigEndian(buf: Array[Byte], offset: Int, value: Long): Unit =
        var i = 0
        while i < 8 do
            buf(offset + i) = (value >>> (56 - i * 8)).toByte
            i += 1
    end writeLongBigEndian

    def writeIntLittleEndian(buf: Array[Byte], offset: Int, value: Int): Unit =
        buf(offset) = value.toByte
        buf(offset + 1) = (value >>> 8).toByte
        buf(offset + 2) = (value >>> 16).toByte
        buf(offset + 3) = (value >>> 24).toByte
    end writeIntLittleEndian

    def writeLongLittleEndian(buf: Array[Byte], offset: Int, value: Long): Unit =
        var i = 0
        while i < 8 do
            buf(offset + i) = (value >>> (i * 8)).toByte
            i += 1
    end writeLongLittleEndian

    /** A 64-byte block buffer over `process`, which compresses the 64 bytes at an offset of an array into the caller's state. Whole
      * blocks of an input are compressed in place; only a partial tail is copied. The byte count is a `Long`, so the padding is right for
      * inputs whose total length passes the array limit.
      */
    final class Buffer64(process: (Array[Byte], Int) => Unit):
        private val block      = new Array[Byte](64)
        private var size       = 0
        private var byteLength = 0L

        def update(input: Array[Byte]): Unit =
            byteLength += input.length.toLong
            var offset = 0
            if size > 0 then
                val copied = math.min(64 - size, input.length)
                java.lang.System.arraycopy(input, 0, block, size, copied)
                size += copied
                offset += copied
                if size == 64 then
                    process(block, 0)
                    size = 0
            end if
            while offset <= input.length - 64 do
                process(input, offset)
                offset += 64
            val remaining = input.length - offset
            if remaining > 0 then
                java.lang.System.arraycopy(input, offset, block, 0, remaining)
                size = remaining
        end update

        /** Pads and compresses the tail: `0x80`, zeros to byte 56 of this block or, when fewer than 8 bytes remain after the `0x80`, of
          * the next, then the 64-bit bit length written by `writeLength` at offset 56.
          */
        def finish(writeLength: (Array[Byte], Int, Long) => Unit): Unit =
            block(size) = 0x80.toByte
            size += 1
            if size > 56 then
                java.util.Arrays.fill(block, size, 64, 0.toByte)
                process(block, 0)
                size = 0
            end if
            java.util.Arrays.fill(block, size, 56, 0.toByte)
            writeLength(block, 56, byteLength << 3)
            process(block, 0)
        end finish
    end Buffer64

end Blocks
