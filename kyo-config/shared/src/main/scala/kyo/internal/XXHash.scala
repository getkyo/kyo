package kyo.internal

/** Internal xxHash32 and xxHash64 helpers.
  *
  * This is a Scala port of the official Java XXH32 and XXH64 implementations from xxHash 0.8.x. The port keeps the same constants, round
  * functions, tail processing, and avalanche steps, while replacing platform-specific memory access with explicit little-endian array reads.
  *
  * Return values preserve the exact xxHash bit pattern in signed Scala `Int` and `Long` values. Callers that need unsigned formatting should
  * format the returned bits explicitly.
  */
private[kyo] object XXHash {

    final private val Prime32_1 = 0x9e3779b1
    final private val Prime32_2 = 0x85ebca77
    final private val Prime32_3 = 0xc2b2ae3d
    final private val Prime32_4 = 0x27d4eb2f
    final private val Prime32_5 = 0x165667b1

    final private val Prime64_1 = java.lang.Long.parseUnsignedLong("9E3779B185EBCA87", 16)
    final private val Prime64_2 = java.lang.Long.parseUnsignedLong("C2B2AE3D27D4EB4F", 16)
    final private val Prime64_3 = 0x165667b19e3779f9L
    final private val Prime64_4 = java.lang.Long.parseUnsignedLong("85EBCA77C2B2AE63", 16)
    final private val Prime64_5 = 0x27d4eb2f165667c5L

    /** Hashes all bytes with XXH32 and seed `0`.
      */
    def hash32(bytes: Array[Byte]): Int =
        hash32(bytes, 0, bytes.length, 0)

    /** Hashes all bytes with XXH32 and the supplied seed.
      */
    def hash32(bytes: Array[Byte], seed: Int): Int =
        hash32(bytes, 0, bytes.length, seed)

    /** Hashes a byte-array slice with XXH32.
      *
      * The slice is interpreted exactly as the official xxHash byte input: bytes are read little-endian in 4-byte lanes and any remainder is
      * processed byte by byte. The method throws `IndexOutOfBoundsException` when `offset` or `length` is negative, or when the slice extends
      * past the input array.
      */
    def hash32(bytes: Array[Byte], offset: Int, length: Int, seed: Int): Int = {
        checkRange(bytes.length, offset, length)
        val end = offset + length
        var i   = offset
        var h   = 0
        if (length >= 16) {
            var v1    = seed + Prime32_1 + Prime32_2
            var v2    = seed + Prime32_2
            var v3    = seed
            var v4    = seed - Prime32_1
            val limit = end - 16
            while (i <= limit) {
                v1 = round32(v1, readIntLE(bytes, i))
                i += 4
                v2 = round32(v2, readIntLE(bytes, i))
                i += 4
                v3 = round32(v3, readIntLE(bytes, i))
                i += 4
                v4 = round32(v4, readIntLE(bytes, i))
                i += 4
            }
            h = rotateLeft(v1, 1) + rotateLeft(v2, 7) + rotateLeft(v3, 12) + rotateLeft(v4, 18)
        } else {
            h = seed + Prime32_5
        }
        h += length
        avalanche32(processTail32(h, bytes, i, end))
    }

    /** Hashes a string as UTF-8 bytes with XXH32 and seed `0`.
      */
    def hash32(input: String): Int =
        hash32(input, 0)

    /** Hashes a string as UTF-8 bytes with XXH32 and the supplied seed.
      *
      * The implementation streams UTF-8 bytes into the XXH32 state instead of first allocating a byte array. Valid surrogate pairs are encoded
      * as their Unicode code point. Unpaired surrogates are encoded as `?`, matching Java's default UTF-8 replacement behavior.
      */
    def hash32(input: String, seed: Int): Int = {
        val state = new Utf8State32(seed)
        var i     = 0
        while (i < input.length) {
            val c = input.charAt(i)
            if (c < 0x80) {
                state.add(c)
            } else if (c < 0x800) {
                state.add(0xc0 | (c >>> 6))
                state.add(0x80 | (c & 0x3f))
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < input.length && Character.isLowSurrogate(input.charAt(i + 1))) {
                    val codePoint = Character.toCodePoint(c, input.charAt(i + 1))
                    state.add(0xf0 | (codePoint >>> 18))
                    state.add(0x80 | ((codePoint >>> 12) & 0x3f))
                    state.add(0x80 | ((codePoint >>> 6) & 0x3f))
                    state.add(0x80 | (codePoint & 0x3f))
                    i += 1
                } else {
                    state.add(0x3f)
                }
            } else if (Character.isLowSurrogate(c)) {
                state.add(0x3f)
            } else {
                state.add(0xe0 | (c >>> 12))
                state.add(0x80 | ((c >>> 6) & 0x3f))
                state.add(0x80 | (c & 0x3f))
            }
            i += 1
        }
        state.digest()
    }

    /** Hashes an integer as its four little-endian bytes with XXH32 and seed `0`.
      */
    def hashInt(value: Int): Int = {
        var h = Prime32_5 + 4
        h = rotateLeft(h + value * Prime32_3, 17) * Prime32_4
        avalanche32(h)
    }

    /** Hashes all bytes with XXH64 and seed `0`.
      */
    def hash64(bytes: Array[Byte]): Long =
        hash64(bytes, 0, bytes.length, 0L)

    /** Hashes all bytes with XXH64 and the supplied seed.
      */
    def hash64(bytes: Array[Byte], seed: Long): Long =
        hash64(bytes, 0, bytes.length, seed)

    /** Hashes a byte-array slice with XXH64.
      *
      * The slice is interpreted exactly as the official xxHash byte input: bytes are read little-endian in 8-byte lanes, then 4-byte lanes,
      * then byte by byte. The method throws `IndexOutOfBoundsException` when `offset` or `length` is negative, or when the slice extends past
      * the input array.
      */
    def hash64(bytes: Array[Byte], offset: Int, length: Int, seed: Long): Long = {
        checkRange(bytes.length, offset, length)
        val end = offset + length
        var i   = offset
        var h   = 0L
        if (length >= 32) {
            var v1    = seed + Prime64_1 + Prime64_2
            var v2    = seed + Prime64_2
            var v3    = seed
            var v4    = seed - Prime64_1
            val limit = end - 32
            while (i <= limit) {
                v1 = round64(v1, readLongLE(bytes, i))
                i += 8
                v2 = round64(v2, readLongLE(bytes, i))
                i += 8
                v3 = round64(v3, readLongLE(bytes, i))
                i += 8
                v4 = round64(v4, readLongLE(bytes, i))
                i += 8
            }
            h = rotateLeft(v1, 1) + rotateLeft(v2, 7) + rotateLeft(v3, 12) + rotateLeft(v4, 18)
            h = mergeRound64(h, v1)
            h = mergeRound64(h, v2)
            h = mergeRound64(h, v3)
            h = mergeRound64(h, v4)
        } else {
            h = seed + Prime64_5
        }
        h += length.toLong
        avalanche64(processTail64(h, bytes, i, end))
    }

    final private class Utf8State32(seed: Int) {

        private var total     = 0
        private var buffered  = 0
        private var processed = false
        private var m0        = 0
        private var m1        = 0
        private var m2        = 0
        private var m3        = 0
        private var v1        = seed + Prime32_1 + Prime32_2
        private var v2        = seed + Prime32_2
        private var v3        = seed
        private var v4        = seed - Prime32_1

        def add(byte: Int): Unit = {
            val b     = byte & 0xff
            val shift = (buffered & 3) << 3
            (buffered >> 2) match {
                case 0 => m0 |= b << shift
                case 1 => m1 |= b << shift
                case 2 => m2 |= b << shift
                case _ => m3 |= b << shift
            }
            buffered += 1
            total += 1
            if (buffered == 16) {
                v1 = round32(v1, m0)
                v2 = round32(v2, m1)
                v3 = round32(v3, m2)
                v4 = round32(v4, m3)
                m0 = 0
                m1 = 0
                m2 = 0
                m3 = 0
                buffered = 0
                processed = true
            }
        }

        def digest(): Int = {
            var h =
                if (processed) rotateLeft(v1, 1) + rotateLeft(v2, 7) + rotateLeft(v3, 12) + rotateLeft(v4, 18)
                else seed + Prime32_5
            h += total
            h = processWord(h, m0, buffered, 0)
            h = processWord(h, m1, buffered, 4)
            h = processWord(h, m2, buffered, 8)
            h = processWord(h, m3, buffered, 12)
            avalanche32(h)
        }

        private def processWord(hash: Int, word: Int, size: Int, base: Int): Int = {
            var h         = hash
            val available = size - base
            if (available >= 4) {
                h = rotateLeft(h + word * Prime32_3, 17) * Prime32_4
            } else if (available > 0) {
                var i = 0
                while (i < available) {
                    h = rotateLeft(h + ((word >>> (i << 3)) & 0xff) * Prime32_5, 11) * Prime32_1
                    i += 1
                }
            }
            h
        }
    }

    private def processTail32(hash: Int, bytes: Array[Byte], offset: Int, end: Int): Int = {
        var h = hash
        var i = offset
        while (i <= end - 4) {
            h = rotateLeft(h + readIntLE(bytes, i) * Prime32_3, 17) * Prime32_4
            i += 4
        }
        while (i < end) {
            h = rotateLeft(h + (bytes(i) & 0xff) * Prime32_5, 11) * Prime32_1
            i += 1
        }
        h
    }

    private def processTail64(hash: Long, bytes: Array[Byte], offset: Int, end: Int): Long = {
        var h = hash
        var i = offset
        while (i <= end - 8) {
            h ^= round64(0L, readLongLE(bytes, i))
            h = rotateLeft(h, 27) * Prime64_1 + Prime64_4
            i += 8
        }
        if (i <= end - 4) {
            h ^= (readIntLE(bytes, i) & 0xffffffffL) * Prime64_1
            h = rotateLeft(h, 23) * Prime64_2 + Prime64_3
            i += 4
        }
        while (i < end) {
            h ^= (bytes(i) & 0xffL) * Prime64_5
            h = rotateLeft(h, 11) * Prime64_1
            i += 1
        }
        h
    }

    private def round32(acc: Int, input: Int): Int =
        rotateLeft(acc + input * Prime32_2, 13) * Prime32_1

    private def round64(acc: Long, input: Long): Long =
        rotateLeft(acc + input * Prime64_2, 31) * Prime64_1

    private def mergeRound64(hash: Long, value: Long): Long = {
        var h = hash ^ round64(0L, value)
        h = h * Prime64_1 + Prime64_4
        h
    }

    private def avalanche32(hash: Int): Int = {
        var h = hash
        h ^= h >>> 15
        h *= Prime32_2
        h ^= h >>> 13
        h *= Prime32_3
        h ^= h >>> 16
        h
    }

    private def avalanche64(hash: Long): Long = {
        var h = hash
        h ^= h >>> 33
        h *= Prime64_2
        h ^= h >>> 29
        h *= Prime64_3
        h ^= h >>> 32
        h
    }

    private def readIntLE(bytes: Array[Byte], offset: Int): Int =
        (bytes(offset) & 0xff) |
            ((bytes(offset + 1) & 0xff) << 8) |
            ((bytes(offset + 2) & 0xff) << 16) |
            (bytes(offset + 3) << 24)

    private def readLongLE(bytes: Array[Byte], offset: Int): Long =
        (bytes(offset) & 0xffL) |
            ((bytes(offset + 1) & 0xffL) << 8) |
            ((bytes(offset + 2) & 0xffL) << 16) |
            ((bytes(offset + 3) & 0xffL) << 24) |
            ((bytes(offset + 4) & 0xffL) << 32) |
            ((bytes(offset + 5) & 0xffL) << 40) |
            ((bytes(offset + 6) & 0xffL) << 48) |
            (bytes(offset + 7).toLong << 56)

    private def rotateLeft(value: Int, distance: Int): Int =
        (value << distance) | (value >>> (32 - distance))

    private def rotateLeft(value: Long, distance: Int): Long =
        (value << distance) | (value >>> (64 - distance))

    private def checkRange(size: Int, offset: Int, length: Int): Unit = {
        if (offset < 0 || length < 0 || offset > size - length)
            throw new IndexOutOfBoundsException(s"offset=$offset, length=$length, size=$size")
    }
}
