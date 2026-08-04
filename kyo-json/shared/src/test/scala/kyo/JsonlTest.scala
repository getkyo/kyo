package kyo

import java.nio.charset.StandardCharsets

class JsonlTest extends kyo.test.Test[Any]:

    case class Event(name: String, count: Int) derives Schema, CanEqual

    private def byteStream(s: String, chunkSize: Int = 4096): Stream[Byte, Any] =
        Stream.init(Chunk.from(s.getBytes(StandardCharsets.UTF_8)), chunkSize)

    /** Asserts that `result` is the decode failure for the record at `index`.
      *
      * Pins the exception type and the record's position, not just that something failed, so a failure attributed to the wrong record or
      * raised as the wrong type cannot pass. Only ever called from inside a leaf, where an `AssertScope` exists to report through.
      */
    private def assertRecordFailure[A](result: Result[DecodeException, A], index: Long)(using kyo.test.AssertScope): Unit =
        result.foldError(
            value => fail(s"expected a failure, got $value"),
            {
                case Result.Failure(e: RecordDecodeException) => assert(e.recordIndex == index)
                case other                                    => fail(s"unexpected error $other")
            }
        )

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

        "emits the same prefix before aborting at any chunk size" in {
            // The prefix a consumer receives before the abort must depend on where the bad record sits,
            // never on how the upstream source chunked its bytes. One chunk size alone would not catch a
            // regression here, because the defect is precisely that the answer varies with chunk size.
            val in =
                "{\"name\":\"a\",\"count\":1}\n{\"name\":\"b\",\"count\":2}\n" +
                    "{\"nope\":true}\n{\"name\":\"d\",\"count\":4}\n"
            def emittedBeforeAbort(chunkSize: Int)(using kyo.test.AssertScope) =
                for
                    seen <- AtomicRef.init(Chunk.empty[Event])
                    r <- Abort.run[DecodeException](
                        byteStream(in, chunkSize).into(Jsonl.pipe[Event]()).foreach(e => seen.updateAndGet(_ :+ e))
                    )
                    emitted <- seen.get
                yield
                    assertRecordFailure(r, 2L)
                    emitted
                end for
            end emittedBeforeAbort
            for
                byOne    <- emittedBeforeAbort(1)
                byThree  <- emittedBeforeAbort(3)
                oneChunk <- emittedBeforeAbort(4096)
            yield
                assert(byOne == Chunk(Event("a", 1), Event("b", 2)))
                assert(byThree == byOne)
                assert(oneChunk == byOne)
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

    "read" - {

        "reads a jsonl file into values" in {
            for
                dir <- Path.tempDir("kyo-jsonl-read")
                file = dir / "events.jsonl"
                _      <- file.write("{\"name\":\"a\",\"count\":1}\n{\"name\":\"b\",\"count\":2}\n")
                values <- Scope.run(Jsonl.read[Event](file).run)
                _      <- dir.removeAll
            yield assert(values == Chunk(Event("a", 1), Event("b", 2)))
        }

        "reads an empty file as no values" in {
            for
                dir <- Path.tempDir("kyo-jsonl-read")
                file = dir / "empty.jsonl"
                _      <- file.write("")
                values <- Scope.run(Jsonl.read[Event](file).run)
                _      <- dir.removeAll
            yield assert(values == Chunk.empty)
        }

        "reads a file whose final record has no trailing newline" in {
            for
                dir <- Path.tempDir("kyo-jsonl-read")
                file = dir / "unterminated.jsonl"
                _      <- file.write("{\"name\":\"a\",\"count\":1}\n{\"name\":\"b\",\"count\":2}")
                values <- Scope.run(Jsonl.read[Event](file).run)
                _      <- dir.removeAll
            yield assert(values == Chunk(Event("a", 1), Event("b", 2)))
        }

        "readResults survives a bad record" in {
            for
                dir <- Path.tempDir("kyo-jsonl-read")
                file = dir / "mixed.jsonl"
                _  <- file.write("{\"name\":\"a\",\"count\":1}\n{\"nope\":true}\n{\"name\":\"c\",\"count\":3}\n")
                rs <- Scope.run(Jsonl.readResults[Event](file).run)
                _  <- dir.removeAll
            yield
                assert(rs.size == 3)
                assert(rs(0).getOrThrow == Event("a", 1))
                assertRecordFailure(rs(1), 1L)
                assert(rs(2).getOrThrow == Event("c", 3))
        }
    }

    "pipeResults" - {

        "keeps going past a bad record" in {
            val in = "{\"name\":\"a\",\"count\":1}\n{\"nope\":true}\n{\"name\":\"c\",\"count\":3}\n"
            for rs <- byteStream(in).into(Jsonl.pipeResults[Event]()).run
            yield
                assert(rs.size == 3)
                assert(rs(0).getOrThrow == Event("a", 1))
                assertRecordFailure(rs(1), 1L)
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
                    assertRecordFailure(rs(1), 1L)
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

    "follow" - {

        val pollDelay = 50.millis

        /** Repeats `write` until `fiber` completes, waking the follower between writes.
          *
          * Ordering never depends on how quickly the forked fiber reaches its first poll: every caller below appends a payload whose
          * repetition leaves the assertion unchanged, so a follower that starts late simply observes a later copy of the same records.
          */
        def writeUntilDone[A, S](
            fiber: Fiber[A, S],
            control: Clock.TimeControl,
            write: Unit < (Async & Abort[FileWriteException])
        )(using Frame): Unit < (Async & Abort[FileWriteException]) =
            Loop.foreach {
                fiber.done.map { done =>
                    if done then Loop.done
                    else write.andThen(control.advance(pollDelay)).andThen(Loop.continue)
                }
            }

        /** Advances the controlled clock until `condition` holds, writing nothing.
          *
          * The exit condition is an observed fact rather than a fixed number of advances, so the loop cannot run out of wakeups on a
          * loaded machine.
          */
        def advanceUntil(control: Clock.TimeControl)(condition: Boolean < Async)(using Frame): Unit < Async =
            Loop.foreach {
                condition.map { done =>
                    if done then Loop.done
                    else control.advance(pollDelay).andThen(Loop.continue)
                }
            }

        "replays existing records then emits appended ones" in {
            Clock.withTimeControl { control =>
                for
                    dir <- Path.tempDir("kyo-jsonl-follow")
                    file = dir / "t.jsonl"
                    _ <- file.write("{\"name\":\"a\",\"count\":1}\n")
                    fiber <- Fiber.initUnscoped(
                        Scope.run(Jsonl.follow[Event](file, pollDelay = pollDelay).take(2).run)
                    )
                    // Each append writes one complete record, so any number of them leaves the first
                    // two records of the file unchanged and the assertion holds whenever the follower starts.
                    _   <- writeUntilDone(fiber, control, file.append("{\"name\":\"b\",\"count\":2}\n"))
                    got <- fiber.get
                    _   <- dir.removeAll
                yield assert(got == Chunk(Event("a", 1), Event("b", 2)))
                end for
            }
        }

        "Origin.End skips existing records" in {
            Clock.withTimeControl { control =>
                for
                    dir <- Path.tempDir("kyo-jsonl-follow-end")
                    file = dir / "t.jsonl"
                    _ <- file.write("{\"name\":\"old\",\"count\":0}\n")
                    fiber <- Fiber.initUnscoped(
                        Scope.run(Jsonl.follow[Event](file, Path.Origin.End, pollDelay).take(1).run)
                    )
                    // The file at rest is always a whole number of records, so wherever Origin.End
                    // lands it lands on a record boundary and the next record read is a "new" one.
                    _   <- writeUntilDone(fiber, control, file.append("{\"name\":\"new\",\"count\":1}\n"))
                    got <- fiber.get
                    _   <- dir.removeAll
                yield assert(got == Chunk(Event("new", 1)))
                end for
            }
        }

        "Origin.Offset resumes at a recorded byte offset" in {
            val first = "{\"name\":\"a\",\"count\":1}\n"
            for
                dir <- Path.tempDir("kyo-jsonl-follow-offset")
                file = dir / "t.jsonl"
                _ <- file.write(first + "{\"name\":\"b\",\"count\":2}\n")
                got <- Scope.run(
                    Jsonl.follow[Event](file, Path.Origin.Offset(first.length.toLong), pollDelay).take(1).run
                )
                _ <- dir.removeAll
            yield assert(got == Chunk(Event("b", 2)))
            end for
        }

        "emits a record written in two partial appends exactly once, whole" in {
            Clock.withTimeControl { control =>
                // One complete record plus the first half of a second, written before the follower starts:
                // reading from Origin.Start makes the follower's start time irrelevant, and the single
                // write means the read that frames the first record necessarily also buffers the partial.
                val existing   = "{\"name\":\"a\",\"count\":1}\n{\"name\":\"split"
                val completion = "\",\"count\":7}\n{\"name\":\"split"
                for
                    dir <- Path.tempDir("kyo-jsonl-follow-split")
                    file = dir / "t.jsonl"
                    _    <- file.write(existing)
                    seen <- AtomicRef.init(Chunk.empty[Event])
                    fiber <- Fiber.initUnscoped(
                        Scope.run(
                            Jsonl.follow[Event](file, pollDelay = pollDelay)
                                .take(2)
                                .foreach(e => seen.updateAndGet(_ :+ e))
                        )
                    )
                    // Emitting the first record is the observable proof that the partial second record
                    // has been read and buffered, so the completing append below cannot land too early.
                    _ <- advanceUntil(control)(seen.get.map(_.nonEmpty))
                    // Each append closes the pending record and opens a new partial one, so repeating it
                    // only ever adds more Event("split", 7) records and never a malformed one.
                    _   <- writeUntilDone(fiber, control, file.append(completion))
                    _   <- fiber.get
                    got <- seen.get
                    _   <- dir.removeAll
                // A framer rebuilt between polls would have dropped the buffered `{"name":"split` and
                // tried to decode `","count":7}` on its own.
                yield assert(got == Chunk(Event("a", 1), Event("split", 7)))
                end for
            }
        }

        "splices a half-written record onto the replayed file after a truncation" in {
            Clock.withTimeControl { control =>
                // 28 bytes: one complete record and the opening of a second one.
                val existing = "{\"name\":\"a\",\"count\":1}\n{\"nam"
                // 23 bytes, below the follower's position, so the rewind fires on the next poll.
                val replaced = "{\"name\":\"b\",\"count\":2}\n"
                for
                    dir <- Path.tempDir("kyo-jsonl-follow-truncate")
                    file = dir / "t.jsonl"
                    _    <- file.write(existing)
                    seen <- AtomicRef.init(Chunk.empty[Result[DecodeException, Event]])
                    fiber <- Fiber.initUnscoped(
                        Scope.run(
                            Jsonl.followResults[Event](file, pollDelay = pollDelay)
                                .take(2)
                                .foreach(r => seen.updateAndGet(_ :+ r))
                        )
                    )
                    // The first result proves the read that framed it also buffered `{"nam`.
                    _ <- advanceUntil(control)(seen.get.map(_.nonEmpty))
                    // A single truncating write: the file never grows back past the follower's position.
                    _   <- file.write(replaced)
                    _   <- advanceUntil(control)(fiber.done)
                    _   <- fiber.get
                    got <- seen.get
                    _   <- dir.removeAll
                yield
                    // The rewind carries no marker in the byte stream, so the buffered `{"nam` is spliced
                    // onto the replayed record. This is documented behavior, not an accident: `followResults`
                    // reports the splice as one failure and keeps going, while `follow` would abort on it.
                    assert(got.size == 2)
                    assert(got(0).getOrThrow == Event("a", 1))
                    assertRecordFailure(got(1), 1L)
                end for
            }
        }
    }
end JsonlTest
