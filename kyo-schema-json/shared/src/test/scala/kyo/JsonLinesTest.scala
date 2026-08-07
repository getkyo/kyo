package kyo

import java.nio.charset.StandardCharsets

class JsonLinesTest extends kyo.test.Test[Any]:

    private def bytes(s: String): Span[Byte] =
        Span.from(s.getBytes(StandardCharsets.UTF_8))

    private def text(span: Span[Byte]): String =
        new String(span.toArray, StandardCharsets.UTF_8)

    /** Every record among `lines`, dropping any line the framer skipped for exceeding its size ceiling. */
    private def kept(lines: Chunk[Json.Lines.Line]): Chunk[Json.Lines.Record] =
        lines.flatMap {
            case Json.Lines.Line.Kept(record) => Chunk(record)
            case Json.Lines.Line.Skipped(_)   => Chunk.empty
        }

    /** Feed `input` through a fresh framer in fixed-size chunks, returning every record.
      *
      * Every chunk is expected to frame cleanly (`DefaultMaxLineSize` is never exercised here), so a skipped line or a halt would
      * indicate a bug unrelated to what these tests are pinning. `require`, not `assert`, surfaces that: this helper is also called from a
      * group builder (outside any `in { ... }` leaf), where `kyo.test`'s `assert` has no `AssertScope` to report through.
      */
    private def frameAll(input: String, chunkSize: Int): Chunk[Json.Lines.Record] =
        frameAllBytes(bytes(input), chunkSize)

    /** The `Span[Byte]` form of [[frameAll]], for inputs that are not valid UTF-8 text. */
    private def frameAllBytes(all: Span[Byte], chunkSize: Int): Chunk[Json.Lines.Record] =
        var f   = Json.Lines.Framer.init(Json.Lines.DefaultMaxLineSize)
        var out = Chunk.empty[Json.Lines.Record]
        var i   = 0
        while i < all.size do
            val end = math.min(i + chunkSize, all.size)
            f.feed(all.slice(i, end)) match
                case Json.Lines.Framed.Continued(next, lines) =>
                    val records = kept(lines)
                    require(records.size == lines.size, s"frameAll: unexpected skipped line in $lines")
                    f = next
                    out = out ++ records
                case Json.Lines.Framed.Halted(_, breach) =>
                    require(false, s"frameAll: unexpected framing halt $breach")
            end match
            i = end
        end while
        f.finish match
            case Present(rec) => out :+ rec
            case Absent       => out
    end frameAllBytes

    /** The lines a single `feed` of `input` resolved, requiring that framing continued.
      *
      * Every caller below is pinning what framing does with a line rather than what it does at a halt, so a halt here means the test's own
      * fixture is wrong. The halting cases assert on `Framed.Halted` directly instead.
      */
    private def linesOf(framer: Json.Lines.Framer, input: String): Chunk[Json.Lines.Line] =
        framer.feed(bytes(input)) match
            case Json.Lines.Framed.Continued(_, lines) => lines
            case Json.Lines.Framed.Halted(_, breach)   => throw new AssertionError(s"unexpected framing halt $breach")

    /** Feeds `input` and returns the advanced framer, requiring that framing continued and resolved no line.
      *
      * The boundary tests below build a framer holding a partial record across several feeds, so a line resolved before the boundary under
      * test would mean the fixture, not the framer, is wrong.
      */
    private def holding(framer: Json.Lines.Framer, input: String): Json.Lines.Framer =
        framer.feed(bytes(input)) match
            case Json.Lines.Framed.Continued(next, lines) =>
                require(lines.isEmpty, s"holding: unexpected resolved line in $lines")
                next
            case Json.Lines.Framed.Halted(_, breach) => throw new AssertionError(s"unexpected framing halt $breach")

    /** Feeds `input` through a fresh framer of ceiling `maxLineSize` in `chunkSize` pieces.
      *
      * Returns every line resolved along the way and the breach that ended framing, if one did. Unlike [[frameAll]] this keeps skipped
      * lines and survives a halt, which is what the limit tests are pinning.
      */
    private def frameLines(
        input: String,
        chunkSize: Int,
        maxLineSize: ByteSize
    ): (Chunk[Json.Lines.Line], Maybe[LimitExceededException]) =
        val all  = bytes(input)
        var f    = Json.Lines.Framer.init(maxLineSize)
        var live = true
        var out  = Chunk.empty[Json.Lines.Line]
        var halt = Maybe.empty[LimitExceededException]
        var i    = 0
        while live && i < all.size do
            val end = math.min(i + chunkSize, all.size)
            f.feed(all.slice(i, end)) match
                case Json.Lines.Framed.Continued(next, lines) =>
                    f = next
                    out = out ++ lines
                case Json.Lines.Framed.Halted(lines, breach) =>
                    out = out ++ lines
                    halt = Maybe(breach)
                    live = false
            end match
            i = end
        end while
        (out, halt)
    end frameLines

    "framing" - {

        "splits records on newline" in {
            val r = frameAll("{\"a\":1}\n{\"a\":2}\n", 1024)
            assert(r.map(rec => text(rec.bytes)) == Chunk("{\"a\":1}", "{\"a\":2}"))
        }

        "strips a trailing carriage return" in {
            val r = frameAll("{\"a\":1}\r\n{\"a\":2}\r\n", 1024)
            assert(r.map(rec => text(rec.bytes)) == Chunk("{\"a\":1}", "{\"a\":2}"))
        }

        "skips a leading UTF-8 BOM" in {
            val r = frameAll("﻿{\"a\":1}\n", 1024)
            assert(r.map(rec => text(rec.bytes)) == Chunk("{\"a\":1}"))
        }

        "does not strip a BOM that is not at offset zero" in {
            val r = frameAll("{\"a\":1}\n﻿{\"a\":2}\n", 1024)
            assert(r.map(rec => text(rec.bytes)) == Chunk("{\"a\":1}", "﻿{\"a\":2}"))
        }

        "skips blank and whitespace-only lines without advancing the record index" in {
            val r = frameAll("{\"a\":1}\n\n   \n{\"a\":2}\n", 1024)
            assert(r.map(rec => text(rec.bytes)) == Chunk("{\"a\":1}", "{\"a\":2}"))
            assert(r.map(_.index) == Chunk(0L, 1L))
        }

        "emits an unterminated final record" in {
            val r = frameAll("{\"a\":1}\n{\"a\":2}", 1024)
            assert(r.map(rec => text(rec.bytes)) == Chunk("{\"a\":1}", "{\"a\":2}"))
        }

        "produces no records for empty input" in {
            assert(frameAll("", 1024).isEmpty)
        }

        "reports the byte offset of each record" in {
            val r = frameAll("{\"a\":1}\n{\"bb\":2}\n{\"c\":3}\n", 1024)
            assert(r.map(_.byteOffset) == Chunk(0L, 8L, 17L))
        }

        "reports byte offsets past a skipped blank line" in {
            val r = frameAll("{\"a\":1}\n\n{\"b\":2}\n", 1024)
            assert(r.map(_.byteOffset) == Chunk(0L, 9L))
        }

        "counts the BOM in subsequent byte offsets" in {
            val r = frameAll("﻿{\"a\":1}\n{\"b\":2}\n", 1024)
            assert(r.map(_.byteOffset) == Chunk(3L, 11L))
        }

        "exposes the record text as UTF-8" in {
            val r = frameAll("{\"name\":\"café\"}\n", 1024)
            assert(r.map(_.text) == Chunk("{\"name\":\"café\"}"))
        }

        // Pins the start-index walk in emitFrom: every record after the first is read from an
        // offset into the fed buffer rather than from a freshly sliced tail, so an off-by-one in
        // that index shows up as wrong content or a wrong byteOffset somewhere in the middle.
        // Record N is `{"i":N}`, so lines are 8 bytes for N < 10, 9 bytes for N < 100, 10 after.
        "frames many records from a single chunk" in {
            val count = 500
            val input = (0 until count).map(i => s"{\"i\":$i}").mkString("", "\n", "\n")
            val r     = frameAll(input, 1 << 20)
            assert(r.size == count)

            assert(r(0).text == "{\"i\":0}")
            assert(r(0).index == 0L)
            assert(r(0).byteOffset == 0L)

            assert(r(250).text == "{\"i\":250}")
            assert(r(250).index == 250L)
            assert(r(250).byteOffset == 2390L)

            assert(r(499).text == "{\"i\":499}")
            assert(r(499).index == 499L)
            assert(r(499).byteOffset == 4880L)
        }

        "frames many records identically when the chunk boundaries fall inside records" in {
            val count = 500
            val input = (0 until count).map(i => s"{\"i\":$i}").mkString("", "\n", "\n")
            val whole = frameAll(input, 1 << 20)
            val split = frameAll(input, 7)
            assert(split.map(_.text) == whole.map(_.text))
            assert(split.map(_.index) == whole.map(_.index))
            assert(split.map(_.byteOffset) == whole.map(_.byteOffset))
        }
    }

    "re-chunking produces identical records at every boundary" - {
        val input = "﻿{\"name\":\"café\"}\n{\"emoji\":\"🎉\"}\r\n\n{\"last\":true}"
        val whole = frameAll(input, 4096)

        "whole-input framing produces three records" in {
            assert(whole.size == 3)
        }

        for size <- Seq(1, 2, 3, 5, 7, 13, 64) do
            s"chunk size $size" in {
                val got = frameAll(input, size)
                assert(got.map(rec => text(rec.bytes)) == whole.map(rec => text(rec.bytes)))
                assert(got.map(_.index) == whole.map(_.index))
                assert(got.map(_.byteOffset) == whole.map(_.byteOffset))
            }
        end for
    }

    // A framer holds the bytes it has not resolved yet as pending pieces and joins them only when a record completes, so the pieces it
    // holds are never rescanned for a newline. The first six pin the four places where resolving a line still has to read across the
    // boundary between the pieces held and the chunk just fed: joining a record, stripping a '\r', testing a line for blankness, and
    // recognizing a byte order mark. The last two pin what the representation itself promises.
    "chunk boundaries" - {

        "joins a record spanning many chunks identically to the same record fed whole" in {
            val body  = (0 until 4000).map(i => ('a' + (i % 26)).toChar).mkString
            val input = "{\"head\":1}\n" + body + "\n{\"tail\":2}\n"
            val whole = frameAll(input, 1 << 20)
            val split = frameAll(input, 13)
            assert(whole.map(_.text) == Chunk("{\"head\":1}", body, "{\"tail\":2}"))
            assert(split.map(_.text) == whole.map(_.text))
            assert(split.map(_.index) == whole.map(_.index))
            assert(split.map(_.byteOffset) == whole.map(_.byteOffset))
        }

        "strips a carriage return ending one chunk when its newline opens the next" in {
            val framer = holding(holding(Json.Lines.Framer.init(Json.Lines.DefaultMaxLineSize), "{\"a\":1}"), "\r")
            val lines  = kept(linesOf(framer, "\n{\"b\":2}\n"))
            assert(lines.map(_.text) == Chunk("{\"a\":1}", "{\"b\":2}"))
            assert(lines.map(_.index) == Chunk(0L, 1L))
            assert(lines.map(_.byteOffset) == Chunk(0L, 9L))
        }

        "recognizes a blank line split across chunks as blank" in {
            val framer = holding(holding(Json.Lines.Framer.init(Json.Lines.DefaultMaxLineSize), "  "), " \t")
            val lines  = kept(linesOf(framer, " \n{\"a\":1}\n"))
            assert(lines.map(_.text) == Chunk("{\"a\":1}"))
            assert(lines.map(_.index) == Chunk(0L))
            assert(lines.map(_.byteOffset) == Chunk(6L))
        }

        // The counterpart of the test above: the blankness walk has to read every pending piece, not just the last one, so a non-blank
        // byte sitting in an earlier piece still makes the line a record.
        "keeps a split line whose only non-blank byte arrived in an earlier chunk" in {
            val framer = holding(holding(Json.Lines.Framer.init(Json.Lines.DefaultMaxLineSize), "  x"), "  ")
            val lines  = kept(linesOf(framer, " \n"))
            assert(lines.map(_.text) == Chunk("  x   "))
            assert(lines.map(_.index) == Chunk(0L))
            assert(lines.map(_.byteOffset) == Chunk(0L))
        }

        "strips a byte order mark fed one byte at a time and counts it in the offsets after it" in {
            val r = frameAll("﻿{\"a\":1}\n{\"b\":2}\n", 1)
            assert(r.map(_.text) == Chunk("{\"a\":1}", "{\"b\":2}"))
            assert(r.map(_.index) == Chunk(0L, 1L))
            assert(r.map(_.byteOffset) == Chunk(3L, 11L))
        }

        // Two bytes of the mark then a byte that is not the third: the framer must hand back every byte it was holding rather than
        // dropping a mark it never saw. The bytes are not valid UTF-8, so this frames raw bytes rather than text.
        "keeps a truncated byte order mark prefix that never completes" in {
            val input = Span(0xef.toByte, 0xbb.toByte, 'x'.toByte, '\n'.toByte)
            val r     = frameAllBytes(input, 1)
            assert(r.size == 1)
            assert(r(0).bytes.is(Span(0xef.toByte, 0xbb.toByte, 'x'.toByte)))
            assert(r(0).index == 0L)
            assert(r(0).byteOffset == 0L)
        }

        // The framer's own promise: feeding it never mutates it, so the receiver stays usable. That is what makes it safe to carry
        // through a Loop and to restore after a rewind. It holds the bytes it has not resolved yet as pending pieces, so this is the
        // assertion that rejects backing those pieces with a growable buffer a later feed writes into: the two continuations below would
        // then see each other's bytes.
        "leaves the receiver usable after a feed, so two continuations of one framer stay independent" in {
            val framer = holding(Json.Lines.Framer.init(Json.Lines.DefaultMaxLineSize), "{\"a\":1")
            val first  = kept(linesOf(framer, "}\n"))
            val second = kept(linesOf(framer, "23}\n"))
            assert(first.map(_.text) == Chunk("{\"a\":1}"))
            assert(second.map(_.text) == Chunk("{\"a\":123}"))
            assert(first.map(_.byteOffset) == Chunk(0L))
            assert(second.map(_.byteOffset) == Chunk(0L))
        }

        // The regression guard for the cost defect this representation exists to remove: joining the bytes held on every feed makes a
        // record spanning k chunks copy O(k^2) bytes, so a newline-free run large against the read buffer burns memory bandwidth for as
        // long as maxLineSize allows. Timing that would be flaky, so this pins the allocation-independent shape instead: a newline-free
        // feed adds one piece and joins nothing, the running total tracks what is held without joining to measure it, and the join
        // happens once, when the record completes. An implementation that concatenates eagerly leaves `pending` holding one piece.
        "holds one pending piece per newline-free feed and joins only when a record completes" in {
            val piece  = "0123456789"
            val feeds  = 64
            var framer = Json.Lines.Framer.init(Json.Lines.DefaultMaxLineSize)
            var n      = 0
            while n < feeds do
                framer = holding(framer, piece)
                n += 1
                assert(framer.pending.size == n)
                assert(framer.pendingSize == n * piece.length)
            end while
            framer.feed(bytes("\n")) match
                case Json.Lines.Framed.Continued(next, lines) =>
                    assert(kept(lines).map(_.text) == Chunk(piece * feeds))
                    assert(next.pending.isEmpty)
                    assert(next.pendingSize == 0)
                case other => fail(s"Expected framing to continue, got $other")
            end match
        }
    }

    "limits" - {

        "uses a 16 MiB default record ceiling" in {
            assert(Json.Lines.DefaultMaxLineSize == 16.mib)
        }

        "rejects a ceiling larger than a Span can address" in {
            val ex = intercept[IllegalArgumentException](
                Json.Lines.Framer.init((Int.MaxValue.toLong + 1L).bytes)
            )
            assert(ex.getMessage == "maxLineSize must not exceed 2147483647 bytes, got 2147483648")
        }

        "allows a zero-byte ceiling and rejects the first non-empty record" in {
            val lines = linesOf(Json.Lines.Framer.init(ByteSize.Zero), "\n1\n")
            assert(lines.size == 1)
            lines(0) match
                case Json.Lines.Line.Skipped(e) =>
                    assert(e.actual == 1)
                    assert(e.maximum == 0)
                case other => fail(s"Expected a skipped line, got $other")
            end match
        }

        "rejects a complete record longer than maxLineSize" in {
            val lines = linesOf(Json.Lines.Framer.init(8.bytes), "{\"aaaaaaaaaaaa\":1}\n")
            assert(lines.size == 1)
            lines(0) match
                case Json.Lines.Line.Skipped(e) => assert(e.maximum == 8)
                case other                      => fail(s"Expected a skipped line, got $other")
            end match
        }

        "rejects a residual longer than maxLineSize with no newline in sight" in {
            Json.Lines.Framer.init(8.bytes).feed(bytes("aaaaaaaaaaaaaaaaaaaa")) match
                case Json.Lines.Framed.Halted(lines, breach) =>
                    assert(lines.isEmpty)
                    assert(breach.actual == 20)
                    assert(breach.maximum == 8)
                case other => fail(s"Expected a halt, got $other")
        }

        "accepts a record exactly at maxLineSize" in {
            assert(kept(linesOf(Json.Lines.Framer.init(8.bytes), "12345678\n")).map(_.text) == Chunk("12345678"))
        }

        // The limit counts the record, not the line terminator, so a CRLF line carries two more
        // bytes on the wire than the limit allows for its record.
        "accepts a CRLF record whose content is exactly maxLineSize" in {
            assert(kept(linesOf(Json.Lines.Framer.init(8.bytes), "12345678\r\n")).map(_.text) == Chunk("12345678"))
        }

        "rejects a CRLF record whose content exceeds maxLineSize" in {
            val lines = linesOf(Json.Lines.Framer.init(8.bytes), "123456789\r\n")
            assert(lines.size == 1)
            lines(0) match
                case Json.Lines.Line.Skipped(e) =>
                    assert(e.actual == 9)
                    assert(e.maximum == 8)
                case other => fail(s"Expected a skipped line, got $other")
            end match
        }

        // The residual path and the completed-record path must agree: whether the '\n' arrives in
        // this chunk or the next one cannot change whether the record is accepted.
        "measures a pending CRLF residual the same way as a completed record" in {
            Json.Lines.Framer.init(8.bytes).feed(bytes("12345678\r")) match
                case Json.Lines.Framed.Continued(held, lines) =>
                    assert(lines.isEmpty)
                    assert(kept(linesOf(held, "\n")).map(_.text) == Chunk("12345678"))
                case other => fail(s"Expected framing to continue, got $other")
        }

        // The ceiling is enforced against the pending bytes as they accumulate, so where it fires cannot depend on how the record was
        // split. A record whose content is exactly the ceiling still frames when it arrives one byte per feed.
        "accepts a record exactly at maxLineSize when it arrives one byte at a time" in {
            val (lines, halt) = frameLines("12345678\n", 1, 8.bytes)
            assert(halt.isEmpty)
            assert(kept(lines).map(_.text) == Chunk("12345678"))
        }

        // One byte over, the pending bytes breach before the terminator arrives, so this is a halt rather than a skip: the framer cannot
        // know a boundary is coming. Feeding the same line whole instead yields a Line.Skipped, which "rejects a complete record longer
        // than maxLineSize" pins.
        "halts on a record one byte over maxLineSize when it arrives one byte at a time" in {
            val (lines, halt) = frameLines("123456789\n87654321\n", 1, 8.bytes)
            assert(lines.isEmpty)
            halt match
                case Present(e) =>
                    assert(e.actual == 9)
                    assert(e.maximum == 8)
                case Absent => fail("Expected framing to halt on the oversized pending bytes")
            end match
        }

        "measures a pending CRLF residual by the same rule when it arrives one byte at a time" in {
            val (lines, halt) = frameLines("12345678\r\n", 1, 8.bytes)
            assert(halt.isEmpty)
            assert(kept(lines).map(_.text) == Chunk("12345678"))
        }

        // A terminated over-long line has a known boundary, so framing skips to it and carries on.
        // What this pins is the records AFTER the skip: halting there instead drops every record in
        // the rest of the input, which on a live log means one bad line ends the follower forever.
        "resumes framing after a terminated oversized record" in {
            val lines = linesOf(Json.Lines.Framer.init(8.bytes), "12345678\n123456789\n87654321\n")
            assert(kept(lines).map(_.text) == Chunk("12345678", "87654321"))
            assert(lines.size == 3)
            lines(1) match
                case Json.Lines.Line.Skipped(e) =>
                    assert(e.actual == 9)
                    assert(e.maximum == 8)
                case other => fail(s"Expected the middle line to be skipped, got $other")
            end match
        }

        // A skipped line occupies its slot: it is a non-blank line, so it consumes an index exactly
        // as an undecodable record does, and its bytes and terminator advance the offset. Lines are
        // 9 and 10 bytes, so the record after the skip sits at index 2 and byte 19.
        "counts a skipped oversized record in the index and byte offset of the records after it" in {
            val records = kept(linesOf(Json.Lines.Framer.init(8.bytes), "12345678\n123456789\n87654321\n"))
            assert(records.map(_.index) == Chunk(0L, 2L))
            assert(records.map(_.byteOffset) == Chunk(0L, 19L))
        }

        // Regression test for a defect where a limit breach discarded every record already framed
        // in the same `feed` call. `emitFrom` used to fail the whole scan and drop its accumulator;
        // it must instead stop and return what it already collected.
        "preserves records framed before a mid-buffer breach in the same chunk" in {
            val lines = linesOf(Json.Lines.Framer.init(8.bytes), "12345678\n123456789\n")
            assert(kept(lines).map(_.text) == Chunk("12345678"))
            assert(lines.size == 2)
            lines(1) match
                case Json.Lines.Line.Skipped(e) =>
                    assert(e.actual == 9)
                    assert(e.maximum == 8)
                case other => fail(s"Expected a skipped line, got $other")
            end match
        }

        // A breach with a boundary and one without can land in the same chunk: the first is skipped and
        // the lines after it are framed, and only the unterminated tail halts. Reporting the halt must
        // not cost the skip that preceded it, nor the record framed between them.
        "reports a skipped record and then halts on an oversized residual in the same chunk" in {
            Json.Lines.Framer.init(8.bytes).feed(bytes("123456789\n12345678\naaaaaaaaaaaa")) match
                case Json.Lines.Framed.Halted(lines, breach) =>
                    assert(kept(lines).map(_.text) == Chunk("12345678"))
                    assert(lines.size == 2)
                    lines(0) match
                        case Json.Lines.Line.Skipped(e) => assert(e.actual == 9)
                        case other                      => fail(s"Expected the first line to be skipped, got $other")
                    end match
                    assert(breach.actual == 12)
                    assert(breach.maximum == 8)
                case other => fail(s"Expected a halt, got $other")
            end match
        }
    }

    case class Event(name: String, count: Int) derives Schema, CanEqual

    "decodeRecord" - {

        "decodes a well-formed record directly" in {
            val record = Json.Lines.Record(bytes("{\"name\":\"a\",\"count\":1}"), index = 0L, byteOffset = 0L)
            assert(Json.Lines.decodeRecord[Event](record).getOrThrow == Event("a", 1))
        }

        "wraps a decode failure with the record's own position, not the framer's" in {
            val record = Json.Lines.Record(bytes("{\"nope\":true}"), index = 7L, byteOffset = 99L)
            Json.Lines.decodeRecord[Event](record) match
                case Result.Failure(e: RecordDecodeException) =>
                    assert(e.recordIndex == 7L)
                    assert(e.byteOffset == 99L)
                    assert(e.record == "{\"nope\":true}")
                case other => fail(s"expected RecordDecodeException, got $other")
            end match
        }
    }

    "decodeAll" - {

        "decodes every record" in {
            val in = "{\"name\":\"a\",\"count\":1}\n{\"name\":\"b\",\"count\":2}\n"
            assert(Json.Lines.decodeAll[Event](in).getOrThrow == Chunk(Event("a", 1), Event("b", 2)))
        }

        "decodes an unterminated final record" in {
            val in = "{\"name\":\"a\",\"count\":1}\n{\"name\":\"b\",\"count\":2}"
            assert(Json.Lines.decodeAll[Event](in).getOrThrow == Chunk(Event("a", 1), Event("b", 2)))
        }

        "returns an empty chunk for empty input" in {
            assert(Json.Lines.decodeAll[Event]("").getOrThrow == Chunk.empty)
        }

        "fails on the first bad record with its position" in {
            val in     = "{\"name\":\"a\",\"count\":1}\n{\"nope\":true}\n{\"name\":\"c\",\"count\":3}\n"
            val result = Json.Lines.decodeAll[Event](in)
            assert(result.isFailure)
            result.foldError(
                _ => fail("expected a failure"),
                {
                    case Result.Failure(e: RecordDecodeException) =>
                        assert(e.recordIndex == 1L)
                        assert(e.byteOffset == 23L)
                        assert(e.record == "{\"nope\":true}")
                    case other => fail(s"unexpected error $other")
                }
            )
        }

        // Pins that a skipped blank line advances byteOffset without advancing recordIndex all the
        // way through to a failure's reported position, not just through a successful frameAll.
        "carries the correct position through a skipped blank line before a bad record" in {
            val in     = "{\"name\":\"a\",\"count\":1}\n\n{\"nope\":true}\n"
            val result = Json.Lines.decodeAll[Event](in)
            assert(result.isFailure)
            result match
                case Result.Failure(e: RecordDecodeException) =>
                    assert(e.recordIndex == 1L)
                    assert(e.byteOffset == 24L)
                    assert(e.record == "{\"nope\":true}")
                case other => fail(s"expected RecordDecodeException, got $other")
            end match
        }

        // The strict counterpart of the skip: `decodeAllBytesResults` reports the breach and decodes on,
        // and folding those results must stop at it rather than inheriting the recovery.
        "fails on a terminated oversized record rather than skipping it" in {
            val in = "{\"name\":\"a\",\"count\":1}\n" + ("x" * 40) + "\n{\"name\":\"c\",\"count\":3}\n"
            Json.Lines.decodeAll[Event](in, maxLineSize = 30.bytes) match
                case Result.Failure(e: LimitExceededException) =>
                    assert(e.actual == 40)
                    assert(e.maximum == 30)
                case other => fail(s"expected LimitExceededException, got $other")
            end match
        }

        "rejects trailing content after a complete value on one line" in {
            Json.Lines.decodeAll[Event]("{\"name\":\"a\",\"count\":1} {}\n") match
                case Result.Failure(e: RecordDecodeException) =>
                    e.cause match
                        case p: ParseException => assert(p.targetType == "Unexpected trailing content")
                        case other             => fail(s"expected a ParseException reporting trailing content, got $other")
                    end match
                case other => fail(s"expected a RecordDecodeException wrapping a trailing-content ParseException, got $other")
            end match
        }
    }

    "decodeAllBytesResults" - {

        "keeps going past a bad record" in {
            val in = "{\"name\":\"a\",\"count\":1}\n{\"nope\":true}\n{\"name\":\"c\",\"count\":3}\n"
            val rs = Json.Lines.decodeAllBytesResults[Event](Span.from(in.getBytes(StandardCharsets.UTF_8)))
            assert(rs.size == 3)
            assert(rs(0).getOrThrow == Event("a", 1))
            rs(1) match
                case Result.Failure(e: RecordDecodeException) =>
                    assert(e.recordIndex == 1L)
                    assert(e.byteOffset == 23L)
                    assert(e.record == "{\"nope\":true}")
                case other => fail(s"expected RecordDecodeException, got $other")
            end match
            assert(rs(2).getOrThrow == Event("c", 3))
        }

        "terminates after an oversized record because no boundary was found" in {
            val in = "aaaaaaaaaaaaaaaaaaaaaaaa"
            val rs = Json.Lines.decodeAllBytesResults[Event](
                Span.from(in.getBytes(StandardCharsets.UTF_8)),
                maxLineSize = 8.bytes
            )
            assert(rs.size == 1)
            rs(0) match
                case Result.Failure(e: LimitExceededException) =>
                    assert(e.maximum == 8)
                case other => fail(s"expected LimitExceededException, got $other")
            end match
        }

        // The lenient contract in full: one failure element per bad record, and the records after it
        // still arrive. A terminated oversized record has a boundary to resume at, so it costs the
        // one element it occupies and nothing else.
        "skips a terminated oversized record and decodes the records after it" in {
            val in = "{\"name\":\"a\",\"count\":1}\n" + ("x" * 40) + "\n{\"name\":\"c\",\"count\":3}\n"
            val rs = Json.Lines.decodeAllBytesResults[Event](
                Span.from(in.getBytes(StandardCharsets.UTF_8)),
                maxLineSize = 30.bytes
            )
            assert(rs.size == 3)
            assert(rs(0).getOrThrow == Event("a", 1))
            rs(1) match
                case Result.Failure(e: LimitExceededException) =>
                    assert(e.actual == 40)
                    assert(e.maximum == 30)
                case other => fail(s"expected LimitExceededException, got $other")
            end match
            assert(rs(2).getOrThrow == Event("c", 3))
        }

        // Regression test for a defect where decodeAllBytesResults, via Framer.feed, discarded every
        // already-decoded record when a later record in the same input breached maxLineSize. The
        // valid record must survive and precede the failure, not disappear with it.
        "keeps a valid record decoded before a later record breaches the limit" in {
            val validLine = "{\"name\":\"a\",\"count\":1}\n"
            val oversized = "x" * 40
            val in        = validLine + oversized
            val rs = Json.Lines.decodeAllBytesResults[Event](
                Span.from(in.getBytes(StandardCharsets.UTF_8)),
                maxLineSize = 30.bytes
            )
            assert(rs.size == 2)
            assert(rs(0).getOrThrow == Event("a", 1))
            rs(1) match
                case Result.Failure(e: LimitExceededException) =>
                    assert(e.actual == 40)
                    assert(e.maximum == 30)
                case other => fail(s"expected LimitExceededException, got $other")
            end match
        }
    }

    "encode" - {

        "encodeLine appends a newline" in {
            assert(Json.Lines.encodeLine(Event("a", 1)) == "{\"name\":\"a\",\"count\":1}\n")
        }

        "encodeAll writes one record per line" in {
            val out = Json.Lines.encodeAll(Seq(Event("a", 1), Event("b", 2)))
            assert(out == "{\"name\":\"a\",\"count\":1}\n{\"name\":\"b\",\"count\":2}\n")
        }

        "encodeAll of an empty sequence is empty" in {
            assert(Json.Lines.encodeAll(Seq.empty[Event]) == "")
        }

        "encodeAllBytes produces the exact expected bytes" in {
            val expected = "{\"name\":\"a\",\"count\":1}\n{\"name\":\"b\",\"count\":2}\n"
            assert(Json.Lines.encodeAllBytes(Seq(Event("a", 1), Event("b", 2))).is(bytes(expected)))
        }

        "encodeAllBytes of an empty sequence is empty" in {
            assert(Json.Lines.encodeAllBytes(Seq.empty[Event]).isEmpty)
        }

        // Regression test for a defect where encodeAllBytes concatenated onto a growing Span
        // accumulator (`acc ++ Json.encodeBytes(v) ++ newline`), copying the whole accumulator again
        // on every value: quadratic in the number of values. This does not measure timing, which
        // would be flaky; it pins the size accounting a copy-arithmetic bug would get wrong,
        // regardless of which linear-time scheme produces it.
        // Regression test for a defect where forcing the pieces to IndexedSeq before Span.concat was
        // skipped: Span.concat indexes its pieces (spans(i)), which is O(i) for a List, so a List
        // caller stayed quadratic (via pointer chases instead of byte copies) even after the round 2
        // fix. Both a List and an IndexedSeq input must produce the same exact byte length, pinning
        // the fix against a future refactor that reintroduces the type-preserving path.
        "encodeAllBytes produces exactly the sum of each value's encoded length plus one newline per value, for a List" in {
            val values         = (0 until 5000).map(i => Event(s"item$i", i)).toList
            val expectedLength = values.map(v => Json.encodeBytes(v).size + 1).sum
            assert(Json.Lines.encodeAllBytes(values).size == expectedLength)
        }

        "encodeAllBytes produces exactly the sum of each value's encoded length plus one newline per value, for an IndexedSeq" in {
            val values         = (0 until 5000).map(i => Event(s"item$i", i))
            val expectedLength = values.map(v => Json.encodeBytes(v).size + 1).sum
            assert(Json.Lines.encodeAllBytes(values).size == expectedLength)
        }

        "round trips through decodeAll" in {
            val values = Seq(Event("café", 1), Event("🎉", 2))
            assert(Json.Lines.decodeAll[Event](Json.Lines.encodeAll(values)).getOrThrow == Chunk.from(values))
        }

        "encodeLine escapes an embedded newline rather than emitting it raw" in {
            val line = Json.Lines.encodeLine(Event("a\nb", 1))
            assert(line.count(_ == '\n') == 1)
            assert(line.endsWith("\n"))
            assert(Json.Lines.decodeAll[Event](line).getOrThrow == Chunk(Event("a\nb", 1)))
        }
    }
end JsonLinesTest
