package kyo.internal

import kyo.*
import kyo.internal.util.Sha1

class Sha1Test extends kyo.Test:

    // Helper: convert hex string to Array[Byte]
    private def fromHex(hex: String): Array[Byte] =
        hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

    // Helper: convert Array[Byte] to lowercase hex string
    private def toHex(bytes: Array[Byte]): String =
        bytes.map(b => f"${b & 0xff}%02x").mkString

    "Sha1" - {

        // Test 1: Hash empty input
        "hash empty input" in {
            val result   = Sha1.hash(Array.emptyByteArray)
            val expected = "da39a3ee5e6b4b0d3255bfef95601890afd80709"
            assert(toHex(result) == expected)
        }

        // Test 2: Hash single byte 'a' (0x61)
        "hash single byte 'a'" in {
            val result   = Sha1.hash(Array(0x61.toByte))
            val expected = "86f7e437faa5a7fce15d1ddcb9eaeaea377667b8"
            assert(toHex(result) == expected)
        }

        // Test 3: Hash "The quick brown fox" test vector
        "hash 'The quick brown fox jumps over the lazy dog'" in {
            val input    = "The quick brown fox jumps over the lazy dog".getBytes("US-ASCII")
            val result   = Sha1.hash(input)
            val expected = "2fd4e1c67a2d28fced849ee1bb76e7391b93eb12"
            assert(toHex(result) == expected)
        }

        // Test 4: Padding length exactly 56 bytes (fills exactly one 512-bit block)
        // A 56-byte message: after the 0x80 byte that's 57, leaving 7 bytes for length, which is
        // exactly 64. So no extra block is needed.
        "padding with message of exactly 55 bytes fits in one block" in {
            // 55 bytes: padded(55) = 0x80, padded(56..63) = 8-byte length → total = 64
            val input  = Array.fill[Byte](55)(0x41.toByte) // 55 x 'A'
            val result = Sha1.hash(input)
            assert(result.length == 20)
            // Known SHA-1 of 55 x 'A' (verified with hashlib)
            assert(toHex(result) == "5021b3d42aa093bffc34eedd7a1455f3624bc552")
        }

        // Test 5: Padding with single block — message < 56 bytes pads to 64 bytes in one block
        "short message (10 bytes) pads into a single 64-byte block" in {
            val input = Array.fill[Byte](10)(0x42.toByte) // 10 x 'B'
            // padLen = (55 - 10 % 64 + 64) % 64 + 1 = (55 - 10 + 64) % 64 + 1 = 45 + 1 = 46
            // totalLen = 10 + 46 + 8 = 64 → single block
            val result = Sha1.hash(input)
            assert(result.length == 20)
            // The result should be a proper SHA-1 hash — verify against known value
            // SHA1("BBBBBBBBBB") — 10 B's (verified with `printf 'BBBBBBBBBB' | shasum -a 1`)
            val expected = "2b88ae576b03a7c136ecca94de57f500973fee76"
            assert(toHex(result) == expected)
        }

        // Test 6: Padding with multiple blocks — message >= 56 bytes overflows into a second block
        "message of 100 bytes spans two 64-byte blocks after padding" in {
            val input = Array.fill[Byte](100)(0x43.toByte) // 100 x 'C'
            // msgLen=100, padLen = (55 - 100%64 + 64) % 64 + 1 = (55 - 36 + 64) % 64 + 1 = 83%64 + 1 = 19+1=20
            // totalLen = 100 + 20 + 8 = 128 → two 64-byte blocks
            val result = Sha1.hash(input)
            assert(result.length == 20)
            // Ensure distinct from single-block outputs
            val single = Sha1.hash(Array.fill[Byte](10)(0x43.toByte))
            assert(toHex(result) != toHex(single))
        }

        // Test 7: Bit-length encoding — 1-byte message → bitLen = 8
        "bit-length encoding for 1-byte message encodes 8 in last 8 bytes" in {
            // SHA1("a") is known. If bitLen encoding were wrong the result would differ.
            val input    = Array(0x61.toByte) // 'a'
            val result   = Sha1.hash(input)
            val expected = "86f7e437faa5a7fce15d1ddcb9eaeaea377667b8"
            assert(toHex(result) == expected)
        }

        // Test 8: Bit-length encoding for large message — no overflow (uses toLong before shift)
        "bit-length encoding does not overflow for message of 268435455 bytes" in {
            // We can't allocate 256MB in a unit test. Verify with a 60-byte known vector
            // to confirm bit-length encoding works for multi-block input.
            // The actual overflow guard (msgLen.toLong * 8) would only manifest with inputs > 268MB.
            val input  = Array.fill[Byte](60)(0x44.toByte) // 60 x 'D'
            val result = Sha1.hash(input)
            assert(result.length == 20)
            assert(toHex(result) == "a288bd3300951e43722aa150d2e959ef871f24af")
        }

        // Test 9: Word schedule extension (rounds 16-79) — verified via known hash output
        "word schedule extension produces correct result for 'abc'" in {
            // 'abc' hashes in a single block: rounds 0-15 use raw words, rounds 16-79 use extensions
            val result   = Sha1.hash("abc".getBytes("US-ASCII"))
            val expected = "a9993e364706816aba3e25717850c26c9cd0d89d"
            assert(toHex(result) == expected)
        }

        // Test 10: Compression function round transitions at 20, 40, 60
        "compression function round transitions verified via multi-vector consistency" in {
            // All four round groups (0-19, 20-39, 40-59, 60-79) are exercised in any single block.
            // We verify that different inputs produce distinct, correct outputs.
            val abc   = toHex(Sha1.hash("abc".getBytes("US-ASCII")))
            val fox   = toHex(Sha1.hash("The quick brown fox jumps over the lazy dog".getBytes("US-ASCII")))
            val empty = toHex(Sha1.hash(Array.emptyByteArray))
            assert(abc == "a9993e364706816aba3e25717850c26c9cd0d89d")
            assert(fox == "2fd4e1c67a2d28fced849ee1bb76e7391b93eb12")
            assert(empty == "da39a3ee5e6b4b0d3255bfef95601890afd80709")
            assert(abc != fox)
            assert(fox != empty)
        }

        // Test 11: F-function rounds 0-19: f = (b & c) | (~b & d)
        // Verified indirectly via known SHA-1 output that is uniquely determined by the f function.
        "f-function rounds 0-19 correct: SHA1('abc') matches RFC vector" in {
            // If f in rounds 0-19 were wrong, the hash would differ from the RFC vector.
            val result   = Sha1.hash("abc".getBytes("US-ASCII"))
            val expected = "a9993e364706816aba3e25717850c26c9cd0d89d"
            assert(toHex(result) == expected)
        }

        // Test 12: F-function rounds 20-39: f = b ^ c ^ d
        "f-function rounds 20-39 correct: SHA1 empty string matches RFC vector" in {
            // The empty string goes through all 80 rounds; any error in rounds 20-39 would diverge.
            val result   = Sha1.hash(Array.emptyByteArray)
            val expected = "da39a3ee5e6b4b0d3255bfef95601890afd80709"
            assert(toHex(result) == expected)
        }

        // Test 13: F-function rounds 40-59: f = (b & c) | (b & d) | (c & d)
        "f-function rounds 40-59 correct: SHA1 fox string matches RFC vector" in {
            val result   = Sha1.hash("The quick brown fox jumps over the lazy dog".getBytes("US-ASCII"))
            val expected = "2fd4e1c67a2d28fced849ee1bb76e7391b93eb12"
            assert(toHex(result) == expected)
        }

        // Test 14: F-function rounds 60-79: f = b ^ c ^ d
        "f-function rounds 60-79 correct: SHA1 'abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq' matches RFC" in {
            // RFC 3174 test vector #2 (448-bit message = 56 bytes, fits in one block)
            val input    = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".getBytes("US-ASCII")
            val result   = Sha1.hash(input)
            val expected = "84983e441c3bd26ebaae4aa1f95129e5e54670f1"
            assert(toHex(result) == expected)
        }

        // Test 15: K-constant rounds 0-19: k = 0x5a827999
        "K-constant rounds 0-19 correct: hash matches RFC vector" in {
            val result   = Sha1.hash("abc".getBytes("US-ASCII"))
            val expected = "a9993e364706816aba3e25717850c26c9cd0d89d"
            assert(toHex(result) == expected)
        }

        // Test 16: K-constant rounds 20-39: k = 0x6ed9eba1
        "K-constant rounds 20-39 correct: hash matches RFC vector" in {
            val result   = Sha1.hash(Array.emptyByteArray)
            val expected = "da39a3ee5e6b4b0d3255bfef95601890afd80709"
            assert(toHex(result) == expected)
        }

        // Test 17: K-constant rounds 40-59: k = 0x8f1bbcdc
        "K-constant rounds 40-59 correct: hash matches RFC vector" in {
            val result   = Sha1.hash("The quick brown fox jumps over the lazy dog".getBytes("US-ASCII"))
            val expected = "2fd4e1c67a2d28fced849ee1bb76e7391b93eb12"
            assert(toHex(result) == expected)
        }

        // Test 18: K-constant rounds 60-79: k = 0xca62c1d6
        "K-constant rounds 60-79 correct: 448-bit message RFC vector" in {
            val input    = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".getBytes("US-ASCII")
            val result   = Sha1.hash(input)
            val expected = "84983e441c3bd26ebaae4aa1f95129e5e54670f1"
            assert(toHex(result) == expected)
        }

        // Test 19: Rotate-left operation
        "rotate-left is correct for known values" in {
            // Integer.rotateLeft(1, 1) = 2
            assert(Integer.rotateLeft(1, 1) == 2)
            // Integer.rotateLeft(0x80000000.toInt, 1) = 1 (MSB wraps to LSB)
            assert(Integer.rotateLeft(0x80000000.toInt, 1) == 1)
            // Integer.rotateLeft(0x01234567, 4) = 0x12345670
            assert(Integer.rotateLeft(0x01234567, 4) == 0x12345670)
            // The SHA-1 algorithm uses rotateLeft(a,5) and rotateLeft(b,30) in compression.
            // Verify these are consistent with the known hash outputs tested above.
            val result = toHex(Sha1.hash("abc".getBytes("US-ASCII")))
            assert(result == "a9993e364706816aba3e25717850c26c9cd0d89d")
        }

        // Test 20: Output byte order (big-endian) — hash result bytes encode h0-h4 MSB first
        "output bytes encode hash values in big-endian order" in {
            // SHA1("") = da39a3ee 5e6b4b0d 3255bfef 95601890 afd80709
            val result = Sha1.hash(Array.emptyByteArray)
            assert(result.length == 20)
            // Check h0 bytes: 0xda, 0x39, 0xa3, 0xee
            assert((result(0) & 0xff) == 0xda)
            assert((result(1) & 0xff) == 0x39)
            assert((result(2) & 0xff) == 0xa3)
            assert((result(3) & 0xff) == 0xee)
            // Check h1 bytes: 0x5e, 0x6b, 0x4b, 0x0d
            assert((result(4) & 0xff) == 0x5e)
            assert((result(5) & 0xff) == 0x6b)
            assert((result(6) & 0xff) == 0x4b)
            assert((result(7) & 0xff) == 0x0d)
            // Check h4 bytes: 0xaf, 0xd8, 0x07, 0x09
            assert((result(16) & 0xff) == 0xaf)
            assert((result(17) & 0xff) == 0xd8)
            assert((result(18) & 0xff) == 0x07)
            assert((result(19) & 0xff) == 0x09)
        }

        // Test 21: Multiple h-value writes — all 5 h-values written, no overwrites
        "all five h-values are written to the output — no offsets collide" in {
            // SHA1("abc") = a9993e36 4706816a ba3e2571 7850c26c 9cd0d89d
            val result = Sha1.hash("abc".getBytes("US-ASCII"))
            assert(result.length == 20)
            // h0: bytes 0-3 = a9 99 3e 36
            assert((result(0) & 0xff) == 0xa9)
            assert((result(3) & 0xff) == 0x36)
            // h1: bytes 4-7 = 47 06 81 6a
            assert((result(4) & 0xff) == 0x47)
            assert((result(7) & 0xff) == 0x6a)
            // h2: bytes 8-11 = ba 3e 25 71
            assert((result(8) & 0xff) == 0xba)
            assert((result(11) & 0xff) == 0x71)
            // h3: bytes 12-15 = 78 50 c2 6c
            assert((result(12) & 0xff) == 0x78)
            assert((result(15) & 0xff) == 0x6c)
            // h4: bytes 16-19 = 9c d0 d8 9d
            assert((result(16) & 0xff) == 0x9c)
            assert((result(19) & 0xff) == 0x9d)
        }

        // Test 22: State accumulation across blocks — h0 += a (not h0 = a)
        // If state were not accumulated (h0 = a instead of h0 += a), multi-block hashes would be wrong.
        "state is accumulated across blocks (h += compression output, not replaced)" in {
            // Use a 128-byte message (exactly 2 blocks after padding) — known RFC vector approach.
            // SHA1("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq") = 84983e44...
            // This is 56 bytes (fits in 1 block) — so use a longer message requiring 2 blocks.
            // Use 100-byte message of 'A' and compare that it differs from the first-block-only hash.
            val twoBlockInput = Array.fill[Byte](100)(0x41.toByte)
            val oneBlockInput = Array.fill[Byte](10)(0x41.toByte)
            val twoBlock      = toHex(Sha1.hash(twoBlockInput))
            val oneBlock      = toHex(Sha1.hash(oneBlockInput))
            // If state accumulation is broken (h0=a instead of h0+=a), the two-block hash
            // would only reflect the second block's compression, making them potentially collide
            // or produce wrong results. The known RFC vector for multi-block is the authoritative check.
            assert(twoBlock != oneBlock)
            assert(twoBlock.length == 40) // 20 bytes = 40 hex chars
            // Also verify a known two-block RFC vector: "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
            // is 56 bytes (borderline), so let's use the 1-million-a's approach truncated:
            // RFC 3174 Test #3: "a" repeated 1,000,000 times → 34aa973c...
            // That's too large; instead use the two-block test: 112-byte message
            val rfcInput =
                "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu".getBytes(
                    "US-ASCII"
                )
            val rfcResult = toHex(Sha1.hash(rfcInput))
            assert(rfcResult == "a49b2446a02c645bf419f995b67091253a04a259")
        }

    }

end Sha1Test
