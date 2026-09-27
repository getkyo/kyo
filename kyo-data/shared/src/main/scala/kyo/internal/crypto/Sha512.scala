package kyo.internal.crypto

/** SHA-512 (FIPS 180-4 section 6.4): 64-bit words, 1024-bit blocks, a 64-byte digest.
  *
  * Ed25519 hashes `R || A || M` and the key's seed with it, so the multi-chunk form streams several arrays through one digest without
  * concatenating a message the caller owns.
  *
  * The padding ends in a 128-bit big-endian message length in bits. A message is at most `Long.MaxValue` bytes, whose bit length needs 66
  * bits, so the upper 64-bit word of that field is `byteLength >>> 61` (the three bits that the shift into bits pushes out of the lower
  * word) rather than a constant zero. No array reaches that size, but the field stays exact for every length the counter can hold.
  */
private[kyo] object Sha512:

    private val BlockSize = 128

    private val k: Array[Long] = Array(
        0x428a2f98d728ae22L, 0x7137449123ef65cdL, 0xb5c0fbcfec4d3b2fL, 0xe9b5dba58189dbbcL,
        0x3956c25bf348b538L, 0x59f111f1b605d019L, 0x923f82a4af194f9bL, 0xab1c5ed5da6d8118L,
        0xd807aa98a3030242L, 0x12835b0145706fbeL, 0x243185be4ee4b28cL, 0x550c7dc3d5ffb4e2L,
        0x72be5d74f27b896fL, 0x80deb1fe3b1696b1L, 0x9bdc06a725c71235L, 0xc19bf174cf692694L,
        0xe49b69c19ef14ad2L, 0xefbe4786384f25e3L, 0x0fc19dc68b8cd5b5L, 0x240ca1cc77ac9c65L,
        0x2de92c6f592b0275L, 0x4a7484aa6ea6e483L, 0x5cb0a9dcbd41fbd4L, 0x76f988da831153b5L,
        0x983e5152ee66dfabL, 0xa831c66d2db43210L, 0xb00327c898fb213fL, 0xbf597fc7beef0ee4L,
        0xc6e00bf33da88fc2L, 0xd5a79147930aa725L, 0x06ca6351e003826fL, 0x142929670a0e6e70L,
        0x27b70a8546d22ffcL, 0x2e1b21385c26c926L, 0x4d2c6dfc5ac42aedL, 0x53380d139d95b3dfL,
        0x650a73548baf63deL, 0x766a0abb3c77b2a8L, 0x81c2c92e47edaee6L, 0x92722c851482353bL,
        0xa2bfe8a14cf10364L, 0xa81a664bbc423001L, 0xc24b8b70d0f89791L, 0xc76c51a30654be30L,
        0xd192e819d6ef5218L, 0xd69906245565a910L, 0xf40e35855771202aL, 0x106aa07032bbd1b8L,
        0x19a4c116b8d2d0c8L, 0x1e376c085141ab53L, 0x2748774cdf8eeb99L, 0x34b0bcb5e19b48a8L,
        0x391c0cb3c5c95a63L, 0x4ed8aa4ae3418acbL, 0x5b9cca4f7763e373L, 0x682e6ff3d6b2b8a3L,
        0x748f82ee5defb2fcL, 0x78a5636f43172f60L, 0x84c87814a1f0ab72L, 0x8cc702081a6439ecL,
        0x90befffa23631e28L, 0xa4506cebde82bde9L, 0xbef9a3f7b2c67915L, 0xc67178f2e372532bL,
        0xca273eceea26619cL, 0xd186b8c721c0c207L, 0xeada7dd6cde0eb1eL, 0xf57d4f7fee6ed178L,
        0x06f067aa72176fbaL, 0x0a637dc5a2c898a6L, 0x113f9804bef90daeL, 0x1b710b35131c471bL,
        0x28db77f523047d84L, 0x32caab7b40c72493L, 0x3c9ebe0a15c9bebcL, 0x431d67c49c100d4cL,
        0x4cc5d4becb3e42b6L, 0x597f299cfc657e2aL, 0x5fcb6fab3ad6faecL, 0x6c44198c4a475817L
    )

    private val initial: Array[Long] = Array(
        0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
        0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L
    )

    def hash(input: Array[Byte]): Array[Byte] =
        hashChunks(Seq(input))

    /** The digest of the concatenation of `inputs`, without building it. No input is modified. */
    def hashChunks(inputs: Seq[Array[Byte]]): Array[Byte] =
        val state      = initial.clone()
        val w          = new Array[Long](80)
        val block      = new Array[Byte](BlockSize)
        var blockSize  = 0
        var byteLength = 0L

        def update(input: Array[Byte]): Unit =
            byteLength += input.length.toLong
            var offset = 0
            if blockSize > 0 then
                val copied = math.min(BlockSize - blockSize, input.length)
                java.lang.System.arraycopy(input, 0, block, blockSize, copied)
                blockSize += copied
                offset += copied
                if blockSize == BlockSize then
                    compress(state, w, block, 0)
                    blockSize = 0
            end if
            while offset <= input.length - BlockSize do
                compress(state, w, input, offset)
                offset += BlockSize
            val remaining = input.length - offset
            if remaining > 0 then
                java.lang.System.arraycopy(input, offset, block, 0, remaining)
                blockSize = remaining
        end update

        inputs.foreach(update)

        block(blockSize) = 0x80.toByte
        blockSize += 1
        if paddingSize(byteLength) > BlockSize then
            java.util.Arrays.fill(block, blockSize, BlockSize, 0.toByte)
            compress(state, w, block, 0)
            blockSize = 0
        end if
        java.util.Arrays.fill(block, blockSize, BlockSize - 16, 0.toByte)
        writeLong(block, BlockSize - 16, bitLengthHigh(byteLength))
        writeLong(block, BlockSize - 8, bitLengthLow(byteLength))
        compress(state, w, block, 0)

        val result = new Array[Byte](64)
        state.indices.foreach(i => writeLong(result, i * 8, state(i)))
        result
    end hashChunks

    /** Bytes appended after a message of `byteLength` bytes: the `0x80` marker, zeros, and the 16-byte length field. */
    private[kyo] def paddingSize(byteLength: Long): Int =
        val remainder = (byteLength & 127L).toInt
        if remainder < 112 then BlockSize - remainder
        else 2 * BlockSize - remainder
    end paddingSize

    private[kyo] def bitLengthHigh(byteLength: Long): Long = byteLength >>> 61

    private[kyo] def bitLengthLow(byteLength: Long): Long = byteLength << 3

    private def compress(state: Array[Long], w: Array[Long], input: Array[Byte], offset: Int): Unit =
        var t = 0
        while t < 16 do
            w(t) = readLong(input, offset + t * 8)
            t += 1
        while t < 80 do
            val w15 = w(t - 15)
            val w2  = w(t - 2)
            val s0  = java.lang.Long.rotateRight(w15, 1) ^ java.lang.Long.rotateRight(w15, 8) ^ (w15 >>> 7)
            val s1  = java.lang.Long.rotateRight(w2, 19) ^ java.lang.Long.rotateRight(w2, 61) ^ (w2 >>> 6)
            w(t) = w(t - 16) + s0 + w(t - 7) + s1
            t += 1
        end while
        var a = state(0)
        var b = state(1)
        var c = state(2)
        var d = state(3)
        var e = state(4)
        var f = state(5)
        var g = state(6)
        var h = state(7)
        t = 0
        while t < 80 do
            val s1 = java.lang.Long.rotateRight(e, 14) ^ java.lang.Long.rotateRight(e, 18) ^ java.lang.Long.rotateRight(e, 41)
            val ch = (e & f) ^ (~e & g)
            val t1 = h + s1 + ch + k(t) + w(t)
            val s0 = java.lang.Long.rotateRight(a, 28) ^ java.lang.Long.rotateRight(a, 34) ^ java.lang.Long.rotateRight(a, 39)
            val mj = (a & b) ^ (a & c) ^ (b & c)
            h = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + s0 + mj
            t += 1
        end while
        state(0) += a
        state(1) += b
        state(2) += c
        state(3) += d
        state(4) += e
        state(5) += f
        state(6) += g
        state(7) += h
    end compress

    private def readLong(buf: Array[Byte], offset: Int): Long =
        var value = 0L
        var i     = 0
        while i < 8 do
            value = (value << 8) | (buf(offset + i) & 0xffL)
            i += 1
        value
    end readLong

    private def writeLong(buf: Array[Byte], offset: Int, value: Long): Unit =
        var i = 0
        while i < 8 do
            buf(offset + i) = (value >>> (56 - i * 8)).toByte
            i += 1
    end writeLong

end Sha512
