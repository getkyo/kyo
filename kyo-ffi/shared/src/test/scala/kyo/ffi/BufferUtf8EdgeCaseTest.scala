package kyo.ffi

/** Additional edge-case tests for [[Buffer.fromUtf8]].
  *
  * [[BufferTest]] already covers ASCII, empty string, and the 2-byte é sequence. This test adds: the 3-byte Euro sign (€), the 4-byte
  * musical symbol G-clef (𝄞), a round-trip of a mixed multi-byte string, and a string containing only ASCII characters to verify
  * byte-exact encoding.
  */
class BufferUtf8EdgeCaseTest extends Test:

    "fromUtf8 with 3-byte UTF-8 (€)" in {
        // € is U+20AC, encoded as 0xE2 0x82 0xAC in UTF-8, plus NUL terminator.
        val b = Buffer.fromUtf8("€")
        try
            assert(b.size == 4)
            assert(b.get(0) == 0xe2.toByte)
            assert(b.get(1) == 0x82.toByte)
            assert(b.get(2) == 0xac.toByte)
            assert(b.get(3) == (0: Byte))
        finally b.close()
        end try
    }

    "fromUtf8 with 4-byte UTF-8 (𝄞)" in {
        // 𝄞 is U+1D11E (MUSICAL SYMBOL G CLEF), encoded as 0xF0 0x9D 0x84 0x9E in UTF-8, plus NUL.
        val b = Buffer.fromUtf8("\uD834\uDD1E") // surrogate pair for U+1D11E in Scala string literals
        try
            assert(b.size == 5)
            assert(b.get(0) == 0xf0.toByte)
            assert(b.get(1) == 0x9d.toByte)
            assert(b.get(2) == 0x84.toByte)
            assert(b.get(3) == 0x9e.toByte)
            assert(b.get(4) == (0: Byte))
        finally b.close()
        end try
    }

    "fromUtf8 round-trip: mixed ASCII and multi-byte characters" in {
        val original = "héllo€wörld"
        val b        = Buffer.fromUtf8(original)
        try
            // Buffer must be null-terminated.
            assert(b.get(b.size - 1) == (0: Byte))
            // Collect bytes (without the trailing NUL) and decode back.
            val bytes   = Array.tabulate[Byte](b.size - 1)(i => b.get(i))
            val decoded = new String(bytes, "UTF-8")
            assert(decoded == original)
        finally b.close()
        end try
    }

    "fromUtf8: byte count equals UTF-8 encoding byte length plus 1 for the NUL terminator" in {
        val strings = Seq(
            "a",           // 1 byte
            "ab",          // 2 bytes
            "é",           // 2 bytes (0xC3 0xA9)
            "€",           // 3 bytes
            "\uD834\uDD1E" // 4 bytes (U+1D11E)
        )
        strings.foreach { s =>
            val expectedUtf8Bytes = s.getBytes("UTF-8").length
            val b                 = Buffer.fromUtf8(s)
            try assert(b.size == (expectedUtf8Bytes + 1))
            finally b.close()
            end try
        }
    }

    "fromUtf8 with ASCII-only input stores exact code-unit bytes" in {
        val input = "Hello, World!"
        val b     = Buffer.fromUtf8(input)
        try
            assert(b.size == (input.length + 1))
            input.zipWithIndex.foreach { case (ch, i) =>
                assert(b.get(i) == ch.toByte)
            }
            assert(b.get(input.length) == (0: Byte))
        finally b.close()
        end try
    }
end BufferUtf8EdgeCaseTest
