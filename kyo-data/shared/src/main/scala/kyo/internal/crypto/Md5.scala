package kyo.internal.crypto

/** MD5 (RFC 1321) in pure Scala, the one implementation every platform runs.
  *
  * `java.security.MessageDigest` is unavailable on Scala.js, Scala Native and Wasm, so the algorithm is written here once: pad the message,
  * split it into 512-bit blocks read as little-endian words, and run the 64 rounds over each. The output is 16 bytes.
  *
  * WARNING: MD5 is broken for collision resistance and for much else. It exists here only for a legacy password exchange that a protocol
  * fixes by specification (PostgreSQL's `md5` authentication), which relies on none of the properties MD5 has lost. It is not a choice for
  * new code.
  */
private[kyo] object Md5:

    /** The per-round left-rotation amounts of RFC 1321 section 3.4. */
    private val S: Array[Int] = Array(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
    )

    /** `T[i] = floor(2^32 * abs(sin(i + 1)))`, RFC 1321 section 3.4. */
    private val T: Array[Int] = Array(
        0xd76aa478.toInt,
        0xe8c7b756.toInt,
        0x242070db,
        0xc1bdceee.toInt,
        0xf57c0faf.toInt,
        0x4787c62a,
        0xa8304613.toInt,
        0xfd469501.toInt,
        0x698098d8,
        0x8b44f7af.toInt,
        0xffff5bb1.toInt,
        0x895cd7be.toInt,
        0x6b901122,
        0xfd987193.toInt,
        0xa679438e.toInt,
        0x49b40821,
        0xf61e2562.toInt,
        0xc040b340.toInt,
        0x265e5a51,
        0xe9b6c7aa.toInt,
        0xd62f105d.toInt,
        0x02441453,
        0xd8a1e681.toInt,
        0xe7d3fbc8.toInt,
        0x21e1cde6,
        0xc33707d6.toInt,
        0xf4d50d87.toInt,
        0x455a14ed,
        0xa9e3e905.toInt,
        0xfcefa3f8.toInt,
        0x676f02d9,
        0x8d2a4c8a.toInt,
        0xfffa3942.toInt,
        0x8771f681.toInt,
        0x6d9d6122,
        0xfde5380c.toInt,
        0xa4beea44.toInt,
        0x4bdecfa9,
        0xf6bb4b60.toInt,
        0xbebfbc70.toInt,
        0x289b7ec6,
        0xeaa127fa.toInt,
        0xd4ef3085.toInt,
        0x04881d05,
        0xd9d4d039.toInt,
        0xe6db99e5.toInt,
        0x1fa27cf8,
        0xc4ac5665.toInt,
        0xf4292244.toInt,
        0x432aff97,
        0xab9423a7.toInt,
        0xfc93a039.toInt,
        0x655b59c3,
        0x8f0ccc92.toInt,
        0xffeff47d.toInt,
        0x85845dd1.toInt,
        0x6fa87e4f,
        0xfe2ce6e0.toInt,
        0xa3014314.toInt,
        0x4e0811a1,
        0xf7537e82.toInt,
        0xbd3af235.toInt,
        0x2ad7d2bb,
        0xeb86d391.toInt
    )

    /** The 16-byte MD5 digest of `input`, which is not modified. */
    def hash(input: Array[Byte]): Array[Byte] =
        var a0 = 0x67452301
        var b0 = 0xefcdab89.toInt
        var c0 = 0x98badcfe.toInt
        var d0 = 0x10325476

        val msgLen = input.length
        val bitLen = msgLen.toLong * 8L

        val padded =
            val padLen = ((55 - msgLen % 64 + 64) % 64) + 1
            val total  = msgLen + padLen + 8
            val buf    = new Array[Byte](total)
            java.lang.System.arraycopy(input, 0, buf, 0, msgLen)
            buf(msgLen) = 0x80.toByte
            var i    = total - 8
            var bits = bitLen
            while i < total do
                buf(i) = (bits & 0xff).toByte
                bits >>>= 8
                i += 1
            end while
            buf
        end padded

        val M = new Array[Int](16)

        var blockOffset = 0
        while blockOffset < padded.length do
            var j = 0
            while j < 16 do
                M(j) = ((padded(blockOffset + j * 4) & 0xff)) |
                    ((padded(blockOffset + j * 4 + 1) & 0xff) << 8) |
                    ((padded(blockOffset + j * 4 + 2) & 0xff) << 16) |
                    ((padded(blockOffset + j * 4 + 3) & 0xff) << 24)
                j += 1
            end while

            var A = a0
            var B = b0
            var C = c0
            var D = d0

            j = 0
            while j < 64 do
                val fval: Int =
                    if j < 16 then (B & C) | (~B & D)
                    else if j < 32 then (D & B) | (~D & C)
                    else if j < 48 then B ^ C ^ D
                    else C ^ (B | ~D)
                val gidx: Int =
                    if j < 16 then j
                    else if j < 32 then (5 * j + 1) % 16
                    else if j < 48 then (3 * j + 5) % 16
                    else (7 * j)                    % 16
                val dtemp = D
                D = C
                C = B
                B = B + Integer.rotateLeft(A + fval + T(j) + M(gidx), S(j))
                A = dtemp
                j += 1
            end while

            a0 += A
            b0 += B
            c0 += C
            d0 += D

            blockOffset += 64
        end while

        val result = new Array[Byte](16)
        writeIntLE(result, 0, a0)
        writeIntLE(result, 4, b0)
        writeIntLE(result, 8, c0)
        writeIntLE(result, 12, d0)
        result
    end hash

    private def writeIntLE(buf: Array[Byte], offset: Int, value: Int): Unit =
        buf(offset) = (value & 0xff).toByte
        buf(offset + 1) = ((value >>> 8) & 0xff).toByte
        buf(offset + 2) = ((value >>> 16) & 0xff).toByte
        buf(offset + 3) = ((value >>> 24) & 0xff).toByte
    end writeIntLE

end Md5
