package kyo

import java.nio.charset.StandardCharsets

class JsonLinesTest extends kyo.test.Test[Any]:

    private def bytes(s: String): Span[Byte] =
        Span.from(s.getBytes(StandardCharsets.UTF_8))

    private def text(span: Span[Byte]): String =
        new String(span.toArray, StandardCharsets.UTF_8)

    /** Feed `input` through a fresh framer in fixed-size chunks, returning every record. */
    private def frameAll(input: String, chunkSize: Int): Chunk[Json.Lines.Record] =
        val all = bytes(input)
        var f   = Json.Lines.Framer.init(Json.Lines.DefaultMaxLineBytes)
        var out = Chunk.empty[Json.Lines.Record]
        var i   = 0
        while i < all.size do
            val end             = math.min(i + chunkSize, all.size)
            val (next, records) = f.feed(all.slice(i, end)).getOrThrow
            f = next
            out = out ++ records
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
            val f = Json.Lines.Framer.init(8)
            val r = f.feed(bytes("{\"aaaaaaaaaaaa\":1}\n"))
            assert(r.isFailure)
            r match
                case Result.Failure(e: LimitExceededException) =>
                    assert(e.maximum == 8)
                case other => fail(s"Expected LimitExceededException, got $other")
            end match
        }

        "rejects a residual longer than maxLineBytes with no newline in sight" in {
            val f = Json.Lines.Framer.init(8)
            assert(f.feed(bytes("aaaaaaaaaaaaaaaaaaaa")).isFailure)
        }

        "accepts a record exactly at maxLineBytes" in {
            val f            = Json.Lines.Framer.init(8)
            val (_, records) = f.feed(bytes("12345678\n")).getOrThrow
            assert(records.size == 1)
        }
    }
end JsonLinesTest
