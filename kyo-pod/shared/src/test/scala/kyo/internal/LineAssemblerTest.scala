package kyo.internal

import kyo.*

/** [[LineAssembler]] splits container output into lines on the raw bytes, then decodes each line as UTF-8.
  *
  * Splitting on bytes is what makes a multi-byte character safe across chunk boundaries: `\n` (0x0A) never appears inside a UTF-8 sequence, so
  * a line boundary is unambiguous in the byte stream, while decoding each chunk on its own would turn a character split across two chunks into
  * replacement characters. The leaves below feed the fragments a runtime really delivers (a line split anywhere, a character split mid-sequence)
  * and assert the assembled text.
  *
  * A trailing fragment with no newline is emitted when the stream ends: a process that writes its last line without a newline (or is killed
  * mid-line) still has that output delivered, rather than dropped.
  */
class LineAssemblerTest extends kyo.BasePodTest:

    private def span(bytes: Array[Byte]): Span[Byte] = Span.fromUnsafe(bytes)

    private def utf8(text: String): Span[Byte] = span(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))

    private def runPipe(chunks: Span[Byte]*)(using Frame): Chunk[String] < Sync =
        Stream.init(chunks).into(LineAssembler.pipe).run

    private def runText(texts: String*)(using Frame): Chunk[String] < Sync =
        runPipe(texts.map(utf8)*)

    enum Src derives CanEqual:
        case A, B

    private def runPart(pairs: (String, Src)*)(using Frame): Chunk[(String, Src)] < Sync =
        Stream.init(pairs.map((text, key) => (utf8(text), key))).into(LineAssembler.partitionedPipe[Src]).run

    "LineAssembler.pipe" - {

        "empty input returns empty chunk" in {
            runPipe().map(r => assert(r.isEmpty))
        }

        "single complete line" in {
            runText("hello\n").map(r => assert(r == Chunk("hello")))
        }

        "a last line without a newline is emitted when the stream ends" in {
            runText("hello").map(r => assert(r == Chunk("hello")))
        }

        "multi-line in one chunk" in {
            runText("a\nb\nc\n").map(r => assert(r == Chunk("a", "b", "c")))
        }

        "line straddling two chunks" in {
            runText("hello, ", "world!\n").map(r => assert(r == Chunk("hello, world!")))
        }

        "line straddling three chunks" in {
            runText("one ", "two ", "three\n").map(r => assert(r == Chunk("one two three")))
        }

        "trailing newline after complete and partial lines" in {
            // First chunk: "a\nb" — complete "a", partial "b" carried
            // Second chunk: "c\n" — combined "bc\n" emits "bc"
            runText("a\nb", "c\n").map(r => assert(r == Chunk("a", "bc")))
        }

        "consecutive newlines preserved as empty lines" in {
            runText("\n\nhello\n").map(r => assert(r == Chunk("", "", "hello")))
        }

        "a multi-byte character split across chunks is decoded intact" in {
            // The 4-byte sequence for U+1F680 split after its first byte: decoding each chunk on its own would yield replacement characters.
            val rocket = "🚀".getBytes(java.nio.charset.StandardCharsets.UTF_8)
            runPipe(span(rocket.take(1)), span(rocket.drop(1) ++ "\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))).map { r =>
                assert(r == Chunk("🚀"))
            }
        }

        "a multi-byte character split at the end of the stream is decoded intact" in {
            val accented = "café".getBytes(java.nio.charset.StandardCharsets.UTF_8)
            runPipe(span(accented.dropRight(1)), span(accented.takeRight(1))).map(r => assert(r == Chunk("café")))
        }

        "a line split between the carriage return and the newline keeps both" in {
            runText("first\r", "\nsecond\n").map(r => assert(r == Chunk("first\r", "second")))
        }
    }

    "LineAssembler.partitionedPipe" - {

        "single complete line per key" in {
            runPart(("hello\n", Src.A), ("world\n", Src.B)).map { r =>
                assert(r == Chunk(("hello", Src.A), ("world", Src.B)))
            }
        }

        "interleaved partial+complete frames stitch per key" in {
            // A: "hello, " + "world!\n" = "hello, world!"
            // B: "foo " + "bar\n" = "foo bar"
            // Each key threads its own residual independently across interleaved fragments.
            runPart(
                ("hello, ", Src.A),
                ("foo ", Src.B),
                ("world!\n", Src.A),
                ("bar\n", Src.B)
            ).map { r =>
                assert(r == Chunk(("hello, world!", Src.A), ("foo bar", Src.B)))
            }
        }

        "a last line per key without a newline is emitted when the stream ends, in first-seen key order" in {
            runPart(("partial-a", Src.A), ("partial-b", Src.B)).map { r =>
                assert(r == Chunk(("partial-a", Src.A), ("partial-b", Src.B)))
            }
        }

        "multi-line content in a single frame emits all lines under same key" in {
            runPart(("a\nb\nc\n", Src.A)).map(r => assert(r == Chunk(("a", Src.A), ("b", Src.A), ("c", Src.A))))
        }
    }

end LineAssemblerTest
