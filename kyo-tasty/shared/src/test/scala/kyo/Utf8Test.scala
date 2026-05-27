package kyo

import kyo.internal.tasty.binary.Utf8

class Utf8Test extends Test:

    // Test 15: ASCII decode
    "decode ASCII-only bytes produces correct String" in run {
        // "hello" = [0x68 0x65 0x6C 0x6C 0x6F]
        val bytes  = Array[Byte](0x68, 0x65, 0x6c, 0x6c, 0x6f)
        val result = Utf8.decode(bytes, 0, bytes.length)
        assert(result == "hello")
    }

    // Test 16: 2-byte UTF-8 sequence (U+00E9 e-acute)
    "decode 2-byte UTF-8 sequence produces correct character" in run {
        // U+00E9 e-acute: encoded as [0xC3, 0xA9]
        val bytes  = Array[Byte](0xc3.toByte, 0xa9.toByte)
        val result = Utf8.decode(bytes, 0, bytes.length)
        assert(result == "é") // "é"
        assert(result.length == 1)
    }

    // Test 17: 4-byte UTF-8 sequence (U+1F600 grinning face emoji)
    "decode 4-byte UTF-8 sequence produces correct character content" in run {
        // U+1F600 GRINNING FACE: encoded as [0xF0, 0x9F, 0x98, 0x80]
        val bytes  = Array[Byte](0xf0.toByte, 0x9f.toByte, 0x98.toByte, 0x80.toByte)
        val result = Utf8.decode(bytes, 0, bytes.length)
        // Content check: the emoji code point must be present.
        // String.length is platform-dependent (2 on JVM surrogate pair, 1 on JS/Native).
        // Assert content via codePointAt(0):
        assert(result.codePointAt(0) == 0x1f600)
    }

    // Test 18: decode with offset and length only decodes the sub-range
    "decode with offset and length only decodes the sub-range" in run {
        // Array: [0xFF, 0xE4, 0xB8, 0xAD, 0xFF], offset=1, length=3 -> "中" (U+4E2D)
        // U+4E2D encodes as [0xE4, 0xB8, 0xAD] (3-byte UTF-8)
        val bytes  = Array[Byte](0xff.toByte, 0xe4.toByte, 0xb8.toByte, 0xad.toByte, 0xff.toByte)
        val result = Utf8.decode(bytes, 1, 3)
        assert(result == "中") // "中"
        assert(result.length == 1)
    }

end Utf8Test
