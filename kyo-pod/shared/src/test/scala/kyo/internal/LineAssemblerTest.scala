package kyo.internal

import kyo.*

class LineAssemblerTest extends kyo.Test:

    "LineAssembler" - {

        "empty feed returns empty chunk" in {
            val la = new LineAssembler
            assert(la.feed("") == Chunk.empty[String])
        }

        "single complete line returns one entry" in {
            val la = new LineAssembler
            assert(la.feed("hello\n") == Chunk("hello"))
        }

        "partial line held in buffer" in {
            val la = new LineAssembler
            assert(la.feed("hello") == Chunk.empty[String])
        }

        "multi-line in one feed" in {
            val la = new LineAssembler
            assert(la.feed("a\nb\nc\n") == Chunk("a", "b", "c"))
        }

        "line straddling two feeds" in {
            val la = new LineAssembler
            assert(la.feed("hello, ") == Chunk.empty[String])
            assert(la.feed("world!\n") == Chunk("hello, world!"))
        }

        "line straddling three feeds" in {
            val la = new LineAssembler
            assert(la.feed("one ") == Chunk.empty[String])
            assert(la.feed("two ") == Chunk.empty[String])
            assert(la.feed("three\n") == Chunk("one two three"))
        }

        "trailing newline after complete and partial lines" in {
            val la = new LineAssembler
            // complete "a", then partial "b" held
            assert(la.feed("a\nb") == Chunk("a"))
            // add "c\n" to partial → "bc" complete
            assert(la.feed("c\n") == Chunk("bc"))
        }

        "flush returns residual when buffer is non-empty" in {
            val la = new LineAssembler
            assert(la.feed("partial-no-newline") == Chunk.empty[String])
            assert(la.flush == Present("partial-no-newline"))
            // After flush, buffer is empty
            assert(la.flush == Absent)
        }
    }
end LineAssemblerTest
