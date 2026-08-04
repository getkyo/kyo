package kyo

import java.nio.charset.StandardCharsets

class JsonlTest extends kyo.test.Test[Any]:

    case class Event(name: String, count: Int) derives Schema, CanEqual

    private def byteStream(s: String, chunkSize: Int = 4096): Stream[Byte, Any] =
        Stream.init(Chunk.from(s.getBytes(StandardCharsets.UTF_8)), chunkSize)

    "pipe" - {

        "decodes a byte stream into values" in {
            val in = "{\"name\":\"a\",\"count\":1}\n{\"name\":\"b\",\"count\":2}\n"
            for values <- byteStream(in).into(Jsonl.pipe[Event]()).run
            yield assert(values == Chunk(Event("a", 1), Event("b", 2)))
        }

        "emits an unterminated final record" in {
            val in = "{\"name\":\"a\",\"count\":1}\n{\"name\":\"b\",\"count\":2}"
            for values <- byteStream(in).into(Jsonl.pipe[Event]()).run
            yield assert(values == Chunk(Event("a", 1), Event("b", 2)))
        }

        "emits nothing for an empty stream" in {
            for values <- byteStream("").into(Jsonl.pipe[Event]()).run
            yield assert(values == Chunk.empty[Event])
        }

        "skips blank lines" in {
            val in = "\n{\"name\":\"a\",\"count\":1}\n\n\n{\"name\":\"b\",\"count\":2}\n\n"
            for values <- byteStream(in).into(Jsonl.pipe[Event]()).run
            yield assert(values == Chunk(Event("a", 1), Event("b", 2)))
        }

        "is independent of chunk boundaries" in {
            val in = "{\"name\":\"café\",\"count\":1}\n{\"name\":\"🎉\",\"count\":2}\n"
            for
                whole    <- byteStream(in, 4096).into(Jsonl.pipe[Event]()).run
                byOne    <- byteStream(in, 1).into(Jsonl.pipe[Event]()).run
                byThree  <- byteStream(in, 3).into(Jsonl.pipe[Event]()).run
                unending <- byteStream(in.dropRight(1), 1).into(Jsonl.pipe[Event]()).run
            yield
                assert(whole == Chunk(Event("café", 1), Event("🎉", 2)))
                assert(byOne == whole)
                assert(byThree == whole)
                assert(unending == whole)
            end for
        }

        "aborts on the first bad record" in {
            val in = "{\"name\":\"a\",\"count\":1}\n{\"nope\":true}\n{\"name\":\"c\",\"count\":3}\n"
            for r <- Abort.run[DecodeException](byteStream(in).into(Jsonl.pipe[Event]()).run)
            yield
                assert(r.isFailure)
                r.foldError(
                    _ => fail("expected a failure"),
                    {
                        case Result.Failure(e: RecordDecodeException) => assert(e.recordIndex == 1L)
                        case other                                    => fail(s"unexpected error $other")
                    }
                )
            end for
        }

        "keeps the records framed before a limit breach, then aborts" in {
            val in = "{\"name\":\"a\",\"count\":1}\n{\"name\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"count\":2}\n"
            for
                seen <- AtomicRef.init(Chunk.empty[Event])
                r <- Abort.run[DecodeException](
                    byteStream(in).into(Jsonl.pipe[Event](maxLineBytes = 30)).foreach(e =>
                        seen.updateAndGet(_ :+ e)
                    )
                )
                emitted <- seen.get
            yield
                assert(emitted == Chunk(Event("a", 1)))
                r.foldError(
                    _ => fail("expected a failure"),
                    {
                        case Result.Failure(e: LimitExceededException) => assert(e.maximum == 30)
                        case other                                     => fail(s"unexpected error $other")
                    }
                )
            end for
        }
    }

    "pipeResults" - {

        "keeps going past a bad record" in {
            val in = "{\"name\":\"a\",\"count\":1}\n{\"nope\":true}\n{\"name\":\"c\",\"count\":3}\n"
            for rs <- byteStream(in).into(Jsonl.pipeResults[Event]()).run
            yield
                assert(rs.size == 3)
                assert(rs(0).getOrThrow == Event("a", 1))
                assert(rs(1).isFailure)
                assert(rs(2).getOrThrow == Event("c", 3))
            end for
        }

        "is independent of chunk boundaries" in {
            val in = "{\"name\":\"café\",\"count\":1}\n{\"nope\":true}\n{\"name\":\"🎉\",\"count\":2}"
            for
                whole   <- byteStream(in, 4096).into(Jsonl.pipeResults[Event]()).run
                byOne   <- byteStream(in, 1).into(Jsonl.pipeResults[Event]()).run
                byThree <- byteStream(in, 3).into(Jsonl.pipeResults[Event]()).run
            yield
                def check(rs: Chunk[Result[DecodeException, Event]]): Unit =
                    assert(rs.size == 3)
                    assert(rs(0).getOrThrow == Event("café", 1))
                    assert(rs(1).isFailure)
                    assert(rs(2).getOrThrow == Event("🎉", 2))
                end check
                check(whole)
                check(byOne)
                check(byThree)
            end for
        }

        "keeps the records framed before a limit breach, then ends" in {
            val in = "{\"name\":\"a\",\"count\":1}\n{\"name\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"count\":2}\n"
            for rs <- byteStream(in).into(Jsonl.pipeResults[Event](maxLineBytes = 30)).run
            yield
                assert(rs.size == 2)
                assert(rs(0).getOrThrow == Event("a", 1))
                rs(1).foldError(
                    _ => fail("expected a failure"),
                    {
                        case Result.Failure(e: LimitExceededException) => assert(e.maximum == 30)
                        case other                                     => fail(s"unexpected error $other")
                    }
                )
            end for
        }
    }
end JsonlTest
