package kyo.internal

import kyo.*

class LineAssemblerTest extends kyo.Test:

    private def runPipe(strings: String*)(using Frame): Chunk[String] < Sync =
        Stream.init(strings).into(LineAssembler.pipe).run

    enum Src derives CanEqual:
        case A, B

    private def runPart(pairs: (String, Src)*)(using Frame): Chunk[(String, Src)] < Sync =
        Stream.init(pairs).into(LineAssembler.partitionedPipe[Src]).run

    "LineAssembler.pipe" - {

        "empty input returns empty chunk" in run {
            runPipe().map(r => assert(r.isEmpty))
        }

        "single complete line" in run {
            runPipe("hello\n").map(r => assert(r == Chunk("hello")))
        }

        "partial line is dropped on stream end" in run {
            runPipe("hello").map(r => assert(r.isEmpty))
        }

        "multi-line in one chunk" in run {
            runPipe("a\nb\nc\n").map(r => assert(r == Chunk("a", "b", "c")))
        }

        "line straddling two chunks" in run {
            runPipe("hello, ", "world!\n").map(r => assert(r == Chunk("hello, world!")))
        }

        "line straddling three chunks" in run {
            runPipe("one ", "two ", "three\n").map(r => assert(r == Chunk("one two three")))
        }

        "trailing newline after complete and partial lines" in run {
            // First chunk: "a\nb" — complete "a", partial "b" carried
            // Second chunk: "c\n" — combined "bc\n" emits "bc"
            runPipe("a\nb", "c\n").map(r => assert(r == Chunk("a", "bc")))
        }

        "consecutive newlines preserved as empty lines" in run {
            runPipe("\n\nhello\n").map(r => assert(r == Chunk("", "", "hello")))
        }
    }

    "LineAssembler.partitionedPipe" - {

        "single complete line per key" in run {
            runPart(("hello\n", Src.A), ("world\n", Src.B)).map { r =>
                assert(r == Chunk(("hello", Src.A), ("world", Src.B)))
            }
        }

        "interleaved partial+complete frames stitch per key" in run {
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

        "partial residual per key dropped on stream end" in run {
            runPart(("partial-a", Src.A), ("partial-b", Src.B)).map(r => assert(r.isEmpty))
        }

        "multi-line content in a single frame emits all lines under same key" in run {
            runPart(("a\nb\nc\n", Src.A)).map(r => assert(r == Chunk(("a", Src.A), ("b", Src.A), ("c", Src.A))))
        }
    }

end LineAssemblerTest
