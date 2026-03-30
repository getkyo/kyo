package kyo.internal

/** Pure Scala SHA-1 implementation. Used by WsCodec for WebSocket accept key computation. Avoids java.security.MessageDigest dependency
  * which is unavailable on Scala Native.
  */
private[internal] object Sha1:

    def hash(input: Array[Byte]): Array[Byte] =
        var h0 = 0x67452301
        var h1 = 0xefcdab89.toInt
        var h2 = 0x98badcfe.toInt
        var h3 = 0x10325476
        var h4 = 0xc3d2e1f0.toInt

        // Pre-processing: pad message
        val msgLen   = input.length
        val bitLen   = msgLen.toLong * 8
        val padLen   = (55 - msgLen % 64 + 64) % 64 + 1
        val totalLen = msgLen + padLen + 8
        val padded   = new Array[Byte](totalLen)
        java.lang.System.arraycopy(input, 0, padded, 0, msgLen)
        padded(msgLen) = 0x80.toByte
        // Append length in bits as big-endian 64-bit
        var shift = 56
        var i     = totalLen - 8
        while shift >= 0 do
            padded(i) = ((bitLen >>> shift) & 0xff).toByte
            shift -= 8
            i += 1
        end while

        // Process 512-bit blocks
        val w      = new Array[Int](80)
        var offset = 0
        while offset < totalLen do
            // Load 16 words
            var j = 0
            while j < 16 do
                w(j) = ((padded(offset + j * 4) & 0xff) << 24) |
                    ((padded(offset + j * 4 + 1) & 0xff) << 16) |
                    ((padded(offset + j * 4 + 2) & 0xff) << 8) |
                    (padded(offset + j * 4 + 3) & 0xff)
                j += 1
            end while
            // Extend
            while j < 80 do
                w(j) = Integer.rotateLeft(w(j - 3) ^ w(j - 8) ^ w(j - 14) ^ w(j - 16), 1)
                j += 1
            end while

            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4

            j = 0
            while j < 80 do
                val (f, k) =
                    if j < 20 then ((b & c) | (~b & d), 0x5a827999)
                    else if j < 40 then (b ^ c ^ d, 0x6ed9eba1)
                    else if j < 60 then ((b & c) | (b & d) | (c & d), 0x8f1bbcdc.toInt)
                    else (b ^ c ^ d, 0xca62c1d6.toInt)
                val temp = Integer.rotateLeft(a, 5) + f + e + k + w(j)
                e = d
                d = c
                c = Integer.rotateLeft(b, 30)
                b = a
                a = temp
                j += 1
            end while

            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e

            offset += 64
        end while

        // Produce hash
        val result = new Array[Byte](20)
        writeInt(result, 0, h0)
        writeInt(result, 4, h1)
        writeInt(result, 8, h2)
        writeInt(result, 12, h3)
        writeInt(result, 16, h4)
        result
    end hash

    private def writeInt(buf: Array[Byte], offset: Int, value: Int): Unit =
        buf(offset) = ((value >>> 24) & 0xff).toByte
        buf(offset + 1) = ((value >>> 16) & 0xff).toByte
        buf(offset + 2) = ((value >>> 8) & 0xff).toByte
        buf(offset + 3) = (value & 0xff).toByte
    end writeInt

end Sha1
