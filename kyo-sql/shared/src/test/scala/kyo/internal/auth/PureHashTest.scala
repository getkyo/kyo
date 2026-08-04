package kyo.internal.auth

import kyo.*

/** Cross-platform tests for [[PureHash]].
  *
  * These vectors are well-known RFC / NIST test vectors and must pass on JVM, Native, and JS. The PBKDF2 vector was computed offline with
  * Python's `hashlib.pbkdf2_hmac`; an in-test oracle would need `java.security.MessageDigest`, which is unavailable on Scala Native and
  * Scala.js, and would be circular besides.
  */
class PureHashTest extends kyo.Test:

    // Well-known SHA-256 of "abc" (FIPS 180-4 example B.1)
    private val sha256AbcHex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    // Well-known SHA-1 of "abc" (FIPS 180-1)
    private val sha1AbcHex = "a9993e364706816aba3e25717850c26c9cd0d89d"
    // Well-known MD5 of "abc" (RFC 1321 §A.5)
    private val md5AbcHex = "900150983cd24fb0d6963f7d28e17f72"

    private def toHex(bytes: Array[Byte]): String =
        bytes.map(b => f"${b & 0xff}%02x").mkString

    "PureHash.sha256(\"abc\") matches FIPS 180-4 vector" in {
        val input  = "abc".getBytes("UTF-8")
        val digest = PureHash.sha256(input)
        assert(digest.length == 32)
        assert(toHex(digest) == sha256AbcHex)
    }

    "PureHash.sha1(\"abc\") matches FIPS 180-1 vector" in {
        val input  = "abc".getBytes("UTF-8")
        val digest = PureHash.sha1(input)
        assert(digest.length == 20)
        assert(toHex(digest) == sha1AbcHex)
    }

    "PureHash.sha256 of empty string matches well-known value" in {
        val input  = Array.empty[Byte]
        val digest = PureHash.sha256(input)
        assert(toHex(digest) == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    "PureHash.sha1 of empty string matches well-known value" in {
        val input  = Array.empty[Byte]
        val digest = PureHash.sha1(input)
        assert(toHex(digest) == "da39a3ee5e6b4b0d3255bfef95601890afd80709")
    }

    "PureHash.md5(\"abc\") matches RFC 1321 vector" in {
        val digest = PureHash.md5("abc".getBytes("UTF-8"))
        assert(digest.length == 16)
        assert(toHex(digest) == md5AbcHex)
    }

    "PureHash.md5Hex renders the digest as lowercase hex" in {
        assert(PureHash.md5Hex("abc".getBytes("UTF-8")) == md5AbcHex)
        assert(PureHash.md5Hex(Array.empty[Byte]) == "d41d8cd98f00b204e9800998ecf8427e")
    }

    "PureHash.hmacSha256 matches RFC 4231 test case 1" in {
        // key = 20 bytes of 0x0b, data = "Hi There"
        val key      = Array.fill[Byte](20)(0x0b.toByte)
        val data     = "Hi There".getBytes("UTF-8")
        val expected = "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
        assert(toHex(PureHash.hmacSha256(key, data)) == expected)
    }

    "PureHash.hmacSha256 rehashes a key longer than the 64-byte block, RFC 4231 test case 6" in {
        // key = 131 bytes of 0xaa, exceeding the SHA-256 block size, so it must be hashed down first.
        val key      = Array.fill[Byte](131)(0xaa.toByte)
        val data     = "Test Using Larger Than Block-Size Key - Hash Key First".getBytes("UTF-8")
        val expected = "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"
        assert(toHex(PureHash.hmacSha256(key, data)) == expected)
    }

    "PureHash.pbkdf2HmacSha256 matches the RFC 7677 SCRAM salted-password parameters" in {
        // RFC 7677 §3 parameters: password = "pencil", salt = base64 "W22ZaJ0SNY7soEsUEjb6gQ==", 4096 iterations, 32-byte key.
        val salt     = java.util.Base64.getDecoder.decode("W22ZaJ0SNY7soEsUEjb6gQ==")
        val expected = "c4a49510323ab4f952cac1fa99441939e78ea74d6be81ddf7096e87513dc615d"
        assert(toHex(PureHash.pbkdf2HmacSha256("pencil".getBytes("UTF-8"), salt, 4096, 32)) == expected)
    }

    "PureHash.pbkdf2HmacSha256 spans multiple output blocks when keyLength exceeds 32 bytes" in {
        // 48 bytes needs two PBKDF2 blocks; the first 32 must equal the single-block derivation.
        val salt  = java.util.Base64.getDecoder.decode("W22ZaJ0SNY7soEsUEjb6gQ==")
        val short = PureHash.pbkdf2HmacSha256("pencil".getBytes("UTF-8"), salt, 4096, 32)
        val long  = PureHash.pbkdf2HmacSha256("pencil".getBytes("UTF-8"), salt, 4096, 48)
        assert(long.length == 48)
        assert(toHex(long.take(32)) == toHex(short))
    }

end PureHashTest
