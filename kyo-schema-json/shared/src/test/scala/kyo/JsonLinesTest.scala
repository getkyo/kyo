package kyo

import java.nio.charset.StandardCharsets

class JsonLinesTest extends kyo.test.Test[Any]:

    private def bytes(s: String): Span[Byte] =
        Span.from(s.getBytes(StandardCharsets.UTF_8))

    private def text(span: Span[Byte]): String =
        new String(span.toArray, StandardCharsets.UTF_8)

    /** Feed `input` through a fresh framer in fixed-size chunks, returning every record.
      *
      * Every chunk is expected to frame cleanly (`DefaultMaxLineBytes` is never exercised here), so a breach would indicate a bug
      * unrelated to what these tests are pinning. `require`, not `assert`, surfaces that: this helper is also called from a group
      * builder (outside any `in { ... }` leaf), where `kyo.test`'s `assert` has no `AssertScope` to report through.
      */
    private def frameAll(input: String, chunkSize: Int): Chunk[Json.Lines.Record] =
        val all = bytes(input)
        var f   = Json.Lines.Framer.init(Json.Lines.DefaultMaxLineBytes)
        var out = Chunk.empty[Json.Lines.Record]
        var i   = 0
        while i < all.size do
            val end    = math.min(i + chunkSize, all.size)
            val framed = f.feed(all.slice(i, end))
            require(framed.error.isEmpty, s"frameAll: unexpected framing failure ${framed.error}")
            f = framed.framer
            out = out ++ framed.records
            i = end
        end while
        f.finish match
            case Present(rec) => out :+ rec
            case Absent       => out
    end frameAll

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

    "limits" - {

        "rejects a complete record longer than maxLineBytes" in {
            val f      = Json.Lines.Framer.init(8)
            val framed = f.feed(bytes("{\"aaaaaaaaaaaa\":1}\n"))
            framed.error match
                case Present(e: LimitExceededException) =>
                    assert(e.maximum == 8)
                case other => fail(s"Expected LimitExceededException, got $other")
            end match
        }

        "rejects a residual longer than maxLineBytes with no newline in sight" in {
            val f      = Json.Lines.Framer.init(8)
            val framed = f.feed(bytes("aaaaaaaaaaaaaaaaaaaa"))
            assert(framed.error.isDefined)
        }

        "accepts a record exactly at maxLineBytes" in {
            val f      = Json.Lines.Framer.init(8)
            val framed = f.feed(bytes("12345678\n"))
            assert(framed.error.isEmpty)
            assert(framed.records.size == 1)
        }

        // The limit counts the record, not the line terminator, so a CRLF line carries one more
        // byte on the wire than the limit allows for its record.
        "accepts a CRLF record whose content is exactly maxLineBytes" in {
            val f      = Json.Lines.Framer.init(8)
            val framed = f.feed(bytes("12345678\r\n"))
            assert(framed.error.isEmpty)
            assert(framed.records.map(_.text) == Chunk("12345678"))
        }

        "rejects a CRLF record whose content exceeds maxLineBytes" in {
            val f = Json.Lines.Framer.init(8)
            f.feed(bytes("123456789\r\n")).error match
                case Present(e: LimitExceededException) =>
                    assert(e.actual == 9)
                    assert(e.maximum == 8)
                case other => fail(s"Expected LimitExceededException, got $other")
            end match
        }

        // The residual path and the completed-record path must agree: whether the '\n' arrives in
        // this chunk or the next one cannot change whether the record is accepted.
        "measures a pending CRLF residual the same way as a completed record" in {
            val f    = Json.Lines.Framer.init(8)
            val held = f.feed(bytes("12345678\r"))
            assert(held.error.isEmpty)
            assert(held.records.isEmpty)
            val framed = held.framer.feed(bytes("\n"))
            assert(framed.error.isEmpty)
            assert(framed.records.map(_.text) == Chunk("12345678"))
        }

        // Regression test for a defect where a limit breach discarded every record already framed
        // in the same `feed` call. `emitFrom` used to fail the whole scan and drop its accumulator;
        // it must instead stop and return what it already collected.
        "preserves records framed before a mid-buffer breach in the same chunk" in {
            val f      = Json.Lines.Framer.init(8)
            val framed = f.feed(bytes("12345678\n123456789\n"))
            assert(framed.records.map(_.text) == Chunk("12345678"))
            framed.error match
                case Present(e: LimitExceededException) =>
                    assert(e.actual == 9)
                    assert(e.maximum == 8)
                case other => fail(s"Expected LimitExceededException, got $other")
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

    "decodeAllResults" - {

        "keeps going past a bad record" in {
            val in = "{\"name\":\"a\",\"count\":1}\n{\"nope\":true}\n{\"name\":\"c\",\"count\":3}\n"
            val rs = Json.Lines.decodeAllResults[Event](Span.from(in.getBytes(StandardCharsets.UTF_8)))
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
            val rs = Json.Lines.decodeAllResults[Event](
                Span.from(in.getBytes(StandardCharsets.UTF_8)),
                maxLineBytes = 8
            )
            assert(rs.size == 1)
            rs(0) match
                case Result.Failure(e: LimitExceededException) =>
                    assert(e.maximum == 8)
                case other => fail(s"expected LimitExceededException, got $other")
            end match
        }

        // Regression test for a defect where decodeAllResults, via Framer.feed, discarded every
        // already-decoded record when a later record in the same input breached maxLineBytes. The
        // valid record must survive and precede the failure, not disappear with it.
        "keeps a valid record decoded before a later record breaches the limit" in {
            val validLine = "{\"name\":\"a\",\"count\":1}\n"
            val oversized = "x" * 40
            val in        = validLine + oversized
            val rs = Json.Lines.decodeAllResults[Event](
                Span.from(in.getBytes(StandardCharsets.UTF_8)),
                maxLineBytes = 30
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
