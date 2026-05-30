package kyo

import java.nio.charset.StandardCharsets
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

    // Test 19 (T4): 4-byte supplementary character U+1F600 produces a surrogate pair
    // kyo-tasty targets pure UTF-8 via StandardCharsets.UTF_8 on JVM/Native and TextDecoder
    // on JS. All three runtimes represent U+1F600 as a two-code-unit UTF-16 sequence
    // internally, so String.length == 2 on every platform.
    "decode 4-byte supplementary U+1F600 returns surrogate pair of length 2" in run {
        val bytes  = Array[Byte](0xf0.toByte, 0x9f.toByte, 0x98.toByte, 0x80.toByte)
        val result = Utf8.decode(bytes, 0, 4)
        assert(result.length == 2)
        assert(result.codePointAt(0) == 0x1f600)
    }

    // Test 20 (T4): modified-UTF-8 overlong null [0xC0, 0x80] is invalid pure UTF-8.
    // kyo-tasty uses StandardCharsets.UTF_8 (pure UTF-8), which does not accept
    // overlong-encoded null as U+0000. Both JVM and Scala Native replace each invalid
    // continuation byte with U+FFFD. TextDecoder on JS behaves identically. The result
    // is therefore two replacement characters, confirming the pure-UTF-8 dialect is
    // enforced at the replacement level rather than by rejection. Documenting this as the
    // expected (and tested) behavior.
    "decode modified-UTF-8 overlong null [0xC0, 0x80] produces replacement characters" in run {
        val bytes  = Array[Byte](0xc0.toByte, 0x80.toByte)
        val result = Utf8.decode(bytes, 0, 2)
        // Each invalid byte is replaced by U+FFFD under pure UTF-8.
        // The result is NOT U+0000 (which modified-UTF-8 would produce).
        assert(result.length == 2)
        assert(result.charAt(0) == '�')
        assert(result.charAt(1) == '�')
    }

    // Test 21 (T4): 4-byte sequence for U+10FFFF (highest valid Unicode code point)
    // [0xF4, 0x8F, 0xBF, 0xBF] is the canonical pure UTF-8 encoding of U+10FFFF.
    // All platforms encode it internally as a UTF-16 surrogate pair, so String.length == 2.
    "decode 4-byte U+10FFFF highest valid code point returns surrogate pair" in run {
        val bytes  = Array[Byte](0xf4.toByte, 0x8f.toByte, 0xbf.toByte, 0xbf.toByte)
        val result = Utf8.decode(bytes, 0, 4)
        assert(result.length == 2)
        assert(result.codePointAt(0) == 0x10ffff)
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

    // T5 JS parity: JS Utf8.decode path (TextDecoder) produces the same result as the JVM
    // reference for a plain ASCII string. Pins T5.
    "T5 JS parity: decode 'hello world' bytes returns 'hello world' on JS" taggedAs jsOnly in run {
        // "hello world" ASCII bytes
        val bytes  = "hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val result = Utf8.decode(bytes, 0, bytes.length)
        assert(result == "hello world")
        assert(result.length == 11)
    }

    // T5 Native parity: Native Utf8.decode path (String constructor via StandardCharsets.UTF_8)
    // produces the same result as the JVM reference for a plain ASCII string. Pins T5.
    "T5 Native parity: decode 'hello world' bytes returns 'hello world' on Native" taggedAs nativeOnly in run {
        // "hello world" ASCII bytes
        val bytes  = "hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val result = Utf8.decode(bytes, 0, bytes.length)
        assert(result == "hello world")
        assert(result.length == 11)
    }

    // Test (Phase 25b T6-2): seeded generative round-trip for UTF-8 encode/decode.
    // Utf8 exposes only decode; encode is s.getBytes(StandardCharsets.UTF_8), which is the same
    // underlying platform UTF-8 codec used by each Utf8 platform implementation. Round-tripping
    // through getBytes + Utf8.decode therefore exercises the decode path against well-formed input.
    // 100 strings with length 0-128, drawn from BMP + 10% supplementary code points.
    // Supplementary characters are generated as low-surrogate + high-surrogate pairs via
    // Character.toChars so that String construction is well-formed (no unpaired surrogates).
    "Utf8.decode round-trips 100 seeded random strings via getBytes encode" in run {
        val rng    = new scala.util.Random(0L)
        val trials = 100
        val failures = (0 until trials).flatMap { i =>
            val len = rng.nextInt(129) // 0..128 inclusive
            val sb  = new java.lang.StringBuilder
            var j   = 0
            while j < len do
                if rng.nextInt(10) == 0 then
                    // 10% chance: supplementary code point in range U+10000..U+10FFFF
                    val cp = 0x10000 + rng.nextInt(0x10ffff - 0x10000 + 1)
                    sb.append(new String(Character.toChars(cp)))
                    j += 1
                else
                    // BMP code point in range U+0020..U+D7FF (avoids surrogates and control codes)
                    val cp = 0x0020 + rng.nextInt(0xd7ff - 0x0020 + 1)
                    sb.appendCodePoint(cp)
                    j += 1
                end if
            end while
            val s      = sb.toString
            val bytes  = s.getBytes(StandardCharsets.UTF_8)
            val result = Utf8.decode(bytes, 0, bytes.length)
            if result == s then None
            else Some(s"trial=$i: input length=${s.length} result length=${result.length}")
        }
        assert(
            failures.isEmpty,
            s"Utf8 encode/decode round-trip failures: ${failures.mkString(", ")}"
        )
    }

end Utf8Test
