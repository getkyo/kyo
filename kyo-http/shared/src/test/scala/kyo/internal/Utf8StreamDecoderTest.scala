package kyo.internal

import kyo.*

class Utf8StreamDecoderTest extends Test:

    "Utf8StreamDecoder" - {

        "ASCII text" - {
            "single chunk" in {
                val decoder = Utf8StreamDecoder()
                val result  = decoder.decode(Chunk.from("hello world".getBytes("UTF-8")))
                assert(result == "hello world")
            }

            "multiple chunks" in {
                val decoder = Utf8StreamDecoder()
                val r1      = decoder.decode(Chunk.from("hello ".getBytes("UTF-8")))
                val r2      = decoder.decode(Chunk.from("world".getBytes("UTF-8")))
                assert(r1 == "hello ")
                assert(r2 == "world")
            }

            "empty chunk" in {
                val decoder = Utf8StreamDecoder()
                val result  = decoder.decode(Chunk.empty[Byte])
                assert(result == "")
            }
        }

        "multi-byte characters within chunk" - {
            "2-byte characters (Latin)" in {
                val decoder = Utf8StreamDecoder()
                val text    = "café"
                val result  = decoder.decode(Chunk.from(text.getBytes("UTF-8")))
                assert(result == text)
            }

            "3-byte characters (Chinese)" in {
                val decoder = Utf8StreamDecoder()
                val text    = "你好世界"
                val result  = decoder.decode(Chunk.from(text.getBytes("UTF-8")))
                assert(result == text)
            }

            "4-byte characters (emoji)" in {
                val decoder = Utf8StreamDecoder()
                val text    = "Hello 🌍🎉"
                val result  = decoder.decode(Chunk.from(text.getBytes("UTF-8")))
                assert(result == text)
            }

            "mixed characters" in {
                val decoder = Utf8StreamDecoder()
                val text    = "Hello 世界 🌍 café"
                val result  = decoder.decode(Chunk.from(text.getBytes("UTF-8")))
                assert(result == text)
            }
        }

        "multi-byte characters split across chunks" - {
            "2-byte character split" in {
                val decoder = Utf8StreamDecoder()
                // é is 2 bytes: 0xC3 0xA9
                val bytes = "café".getBytes("UTF-8")
                // Split after 'caf' and first byte of 'é'
                val chunk1 = Chunk.from(bytes.take(4)) // "caf" + first byte of é
                val chunk2 = Chunk.from(bytes.drop(4)) // second byte of é

                val r1 = decoder.decode(chunk1)
                val r2 = decoder.decode(chunk2)

                assert(r1 == "caf")
                assert(r2 == "é")
            }

            "3-byte character split after first byte" in {
                val decoder = Utf8StreamDecoder()
                // 中 is 3 bytes: 0xE4 0xB8 0xAD
                val bytes  = "中".getBytes("UTF-8")
                val chunk1 = Chunk.from(bytes.take(1))
                val chunk2 = Chunk.from(bytes.drop(1))

                val r1 = decoder.decode(chunk1)
                val r2 = decoder.decode(chunk2)

                assert(r1 == "")
                assert(r2 == "中")
            }

            "3-byte character split after second byte" in {
                val decoder = Utf8StreamDecoder()
                val bytes   = "中".getBytes("UTF-8")
                val chunk1  = Chunk.from(bytes.take(2))
                val chunk2  = Chunk.from(bytes.drop(2))

                val r1 = decoder.decode(chunk1)
                val r2 = decoder.decode(chunk2)

                assert(r1 == "")
                assert(r2 == "中")
            }

            "4-byte character split" in {
                val decoder = Utf8StreamDecoder()
                // 🌍 is 4 bytes: 0xF0 0x9F 0x8C 0x8D
                val bytes = "🌍".getBytes("UTF-8")

                // Split after 2 bytes
                val chunk1 = Chunk.from(bytes.take(2))
                val chunk2 = Chunk.from(bytes.drop(2))

                val r1 = decoder.decode(chunk1)
                val r2 = decoder.decode(chunk2)

                assert(r1 == "")
                assert(r2 == "🌍")
            }

            "multiple split characters in sequence" in {
                val decoder = Utf8StreamDecoder()
                val text    = "你好"
                val bytes   = text.getBytes("UTF-8") // 6 bytes total

                // Split in the middle of first character
                val r1 = decoder.decode(Chunk.from(bytes.take(2)))
                // Complete first char and split second
                val r2 = decoder.decode(Chunk.from(bytes.slice(2, 5)))
                // Complete second char
                val r3 = decoder.decode(Chunk.from(bytes.drop(5)))

                assert(r1 == "")
                assert(r2 == "你")
                assert(r3 == "好")
            }
        }

        "flush" - {
            "empty buffer" in {
                val decoder = Utf8StreamDecoder()
                decoder.decode(Chunk.from("hello".getBytes("UTF-8")))
                val result = decoder.flush()
                assert(result == "")
            }

            "with leftover bytes" in {
                val decoder = Utf8StreamDecoder()
                // Decode partial 3-byte character
                val bytes = "中".getBytes("UTF-8")
                decoder.decode(Chunk.from(bytes.take(2)))

                // Flush should fail or return replacement for malformed sequence
                // The behavior depends on CharsetDecoder configuration
                assertThrows[java.nio.charset.MalformedInputException] {
                    decoder.flush()
                }
            }
        }

        "edge cases" - {
            "very small chunks (1 byte each)" in {
                val decoder = Utf8StreamDecoder()
                val text    = "Hi 🌍"
                val bytes   = text.getBytes("UTF-8")

                val results  = bytes.map(b => decoder.decode(Chunk(b)))
                val combined = results.mkString + decoder.flush()

                assert(combined == text)
            }

            "chunk boundary at every position of 4-byte char" in {
                val decoder = Utf8StreamDecoder()
                val emoji   = "🎉"
                val bytes   = emoji.getBytes("UTF-8")
                assert(bytes.length == 4)

                // Test split at position 1
                val d1 = Utf8StreamDecoder()
                assert(d1.decode(Chunk.from(bytes.take(1))) == "")
                assert(d1.decode(Chunk.from(bytes.drop(1))) == emoji)

                // Test split at position 2
                val d2 = Utf8StreamDecoder()
                assert(d2.decode(Chunk.from(bytes.take(2))) == "")
                assert(d2.decode(Chunk.from(bytes.drop(2))) == emoji)

                // Test split at position 3
                val d3 = Utf8StreamDecoder()
                assert(d3.decode(Chunk.from(bytes.take(3))) == "")
                assert(d3.decode(Chunk.from(bytes.drop(3))) == emoji)
            }

            "alternating ASCII and multi-byte" in {
                val decoder = Utf8StreamDecoder()
                val text    = "a中b好c"
                val bytes   = text.getBytes("UTF-8")

                // Split at various points
                val r1 = decoder.decode(Chunk.from(bytes.take(1)))     // "a"
                val r2 = decoder.decode(Chunk.from(bytes.slice(1, 3))) // partial 中
                val r3 = decoder.decode(Chunk.from(bytes.slice(3, 5))) // rest of 中 + "b"
                val r4 = decoder.decode(Chunk.from(bytes.drop(5)))     // 好 + "c"

                assert((r1 + r2 + r3 + r4) == text)
            }
        }
    }

end Utf8StreamDecoderTest
