package kyo.internal

import kyo.*

/** Cross-platform smoke tests for [[Hash]].
  *
  * These vectors are well-known RFC / NIST test vectors and must pass on JVM, Native, and JS.
  */
class HashTest extends kyo.Test:

    // Well-known SHA-256 of "abc" (FIPS 180-4 example B.1)
    private val sha256AbcHex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    // Well-known SHA-1 of "abc" (FIPS 180-1)
    private val sha1AbcHex = "a9993e364706816aba3e25717850c26c9cd0d89d"

    private def toHex(bytes: Array[Byte]): String =
        bytes.map(b => f"${b & 0xff}%02x").mkString

    "Hash.sha256(\"abc\") matches FIPS 180-4 vector" in {
        val input  = "abc".getBytes("UTF-8")
        val digest = Hash.sha256(input)
        assert(digest.length == 32)
        assert(toHex(digest) == sha256AbcHex)
    }

    "Hash.sha1(\"abc\") matches FIPS 180-1 vector" in {
        val input  = "abc".getBytes("UTF-8")
        val digest = Hash.sha1(input)
        assert(digest.length == 20)
        assert(toHex(digest) == sha1AbcHex)
    }

    "Hash.sha256 of empty string matches well-known value" in {
        val input  = Array.empty[Byte]
        val digest = Hash.sha256(input)
        assert(toHex(digest) == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    "Hash.sha1 of empty string matches well-known value" in {
        val input  = Array.empty[Byte]
        val digest = Hash.sha1(input)
        assert(toHex(digest) == "da39a3ee5e6b4b0d3255bfef95601890afd80709")
    }

end HashTest
