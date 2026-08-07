package kyo

import java.nio.charset.StandardCharsets

class JsonlTest extends kyo.test.Test[Any]:

    case class Event(name: String, count: Int) derives Schema, CanEqual

    /** A record nested two levels deep holding a ten-entry collection.
      *
      * Its depth and its collection size are different numbers, which is what lets a limit test set `maxDepth` and `maxCollectionSize` to
      * two different values and read back from the raised exception which of the two was applied.
      */
    case class Bag(items: Chunk[Int]) derives Schema, CanEqual

    private val bag     = Bag(Chunk(0, 1, 2, 3, 4, 5, 6, 7, 8, 9))
    private val bagLine = "{\"items\":[0,1,2,3,4,5,6,7,8,9]}"

    private def byteStream(s: String, chunkSize: Int = 4096): Stream[Byte, Any] =
        Stream.init(Chunk.from(s.getBytes(StandardCharsets.UTF_8)), chunkSize)

    /** A well-formed record of 61 bytes, over the 30-byte `maxLineBytes` every skip test below sets.
      *
      * It decodes cleanly at the default limit, so a test that rejects it is rejecting it for its size alone. Every call site terminates it
      * with a newline, which is what makes it a skippable breach rather than an oversized residual: the boundary is known, so framing
      * resumes after it.
      */
    private val oversizedLine = "{\"name\":\"" + ("b" * 40) + "\",\"count\":2}"

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

    /** Asserts that `result` is a record decode failure caused by breaching `limit` with the maximum `maximum`.
      *
      * Pins which of the two decode limits was applied and the value it was applied with, not merely that decoding failed. That is what
      * makes a limit test sensitive to `maxDepth` and `maxCollectionSize` being forwarded in the wrong order: both are `Int`, so a
      * transposition compiles, and it shows up here as the wrong limit name or the wrong maximum.
      */
    private def assertLimitBreach[A](result: Result[DecodeException, A], limit: String, maximum: Int)(using kyo.test.AssertScope): Unit =
        result.foldError(
            value => fail(s"expected a failure, got $value"),
            {
                case Result.Failure(e: RecordDecodeException) =>
                    e.cause match
                        case cause: LimitExceededException =>
                            assert(cause.limit == limit)
                            assert(cause.maximum == maximum)
                        case other => fail(s"unexpected cause $other")
                case other => fail(s"unexpected error $other")
            }
        )

    /** Asserts that `result` is the write failure a missing parent directory of `path` raises.
      *
      * Pins which write failure was raised and the file it names, not merely that writing failed. A test whose subject is the missing
      * folder would otherwise pass on a permission error or an I/O error against an unrelated path, which are the failures a wrong
      * `createFolders` path would substitute.
      */
    private def assertMissingParent[A](result: Result[FileWriteException, A], path: Path)(using kyo.test.AssertScope): Unit =
        result.foldError(
            value => fail(s"expected a failure, got $value"),
            {
                case Result.Failure(e: FileNotFoundException) => assert(e.path == path)
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

        "aborts on a terminated oversized record rather than skipping it" in {
            // The lenient variants skip a terminated breach and carry on; the strict ones must not.
            // The record after the breach is what separates the two: if it were emitted here, the
            // strict variant would have inherited the lenient recovery.
            val in = "{\"name\":\"a\",\"count\":1}\n" + oversizedLine + "\n{\"name\":\"c\",\"count\":3}\n"
            for
                seen <- AtomicRef.init(Chunk.empty[Event])
                r <- Abort.run[DecodeException](
                    byteStream(in).into(Jsonl.pipe[Event](maxLineBytes = 30)).foreach(e => seen.updateAndGet(_ :+ e))
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

        "applies maxDepth and maxCollectionSize to a terminated record" in {
            for
                depth <- Abort.run[DecodeException](
                    byteStream(bagLine + "\n").into(Jsonl.pipe[Bag](maxDepth = 1, maxCollectionSize = 50)).run
                )
                collection <- Abort.run[DecodeException](
                    byteStream(bagLine + "\n").into(Jsonl.pipe[Bag](maxDepth = 8, maxCollectionSize = 4)).run
                )
                within <- byteStream(bagLine + "\n").into(Jsonl.pipe[Bag](maxDepth = 8, maxCollectionSize = 50)).run
            yield
                assertLimitBreach(depth, "Nesting depth", 1)
                assertLimitBreach(collection, "Collection size", 4)
                assert(within == Chunk(bag))
            end for
        }

        "applies maxDepth and maxCollectionSize to an unterminated final record" in {
            // No trailing newline, so the record is completed by the framer's `finish` rather than by a
            // record boundary inside a chunk. That is a second decode call site with its own pair of limit
            // arguments to forward, and the terminated case above does not reach it.
            for
                depth <- Abort.run[DecodeException](
                    byteStream(bagLine).into(Jsonl.pipe[Bag](maxDepth = 1, maxCollectionSize = 50)).run
                )
                collection <- Abort.run[DecodeException](
                    byteStream(bagLine).into(Jsonl.pipe[Bag](maxDepth = 8, maxCollectionSize = 4)).run
                )
                within <- byteStream(bagLine).into(Jsonl.pipe[Bag](maxDepth = 8, maxCollectionSize = 50)).run
            yield
                assertLimitBreach(depth, "Nesting depth", 1)
                assertLimitBreach(collection, "Collection size", 4)
                assert(within == Chunk(bag))
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

        "emits the records before a bad one and then aborts" in {
            for
                dir <- Path.tempDir("kyo-jsonl-read")
                file = dir / "mixed.jsonl"
                _    <- file.write("{\"name\":\"a\",\"count\":1}\n{\"nope\":true}\n{\"name\":\"c\",\"count\":3}\n")
                seen <- AtomicRef.init(Chunk.empty[Event])
                r <- Abort.run[DecodeException](
                    Scope.run(Jsonl.read[Event](file).foreach(e => seen.updateAndGet(_ :+ e)))
                )
                emitted <- seen.get
                _       <- dir.removeAll
            yield
                assert(emitted == Chunk(Event("a", 1)))
                assertRecordFailure(r, 1L)
            end for
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

        "aborts on a terminated oversized record rather than skipping it" in {
            val in = "{\"name\":\"a\",\"count\":1}\n" + oversizedLine + "\n{\"name\":\"c\",\"count\":3}\n"
            for
                dir <- Path.tempDir("kyo-jsonl-read-skip-strict")
                file = dir / "oversized.jsonl"
                _    <- file.write(in)
                seen <- AtomicRef.init(Chunk.empty[Event])
                r <- Abort.run[DecodeException](
                    Scope.run(Jsonl.read[Event](file, maxLineBytes = 30).foreach(e => seen.updateAndGet(_ :+ e)))
                )
                emitted <- seen.get
                _       <- dir.removeAll
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

        "readResults skips a terminated oversized record and keeps decoding the records after it" in {
            // The file-backed lenient surface must agree with `pipeResults` and `followResults`: one
            // failure element for the over-long record, and the records behind it still delivered.
            val in = "{\"name\":\"a\",\"count\":1}\n" + oversizedLine + "\n{\"name\":\"c\",\"count\":3}\n"
            for
                dir <- Path.tempDir("kyo-jsonl-read-results-skip")
                file = dir / "oversized.jsonl"
                _  <- file.write(in)
                rs <- Scope.run(Jsonl.readResults[Event](file, maxLineBytes = 30).run)
                _  <- dir.removeAll
            yield
                assert(rs.size == 3)
                assert(rs(0).getOrThrow == Event("a", 1))
                rs(1).foldError(
                    _ => fail("expected a failure"),
                    {
                        case Result.Failure(e: LimitExceededException) => assert(e.maximum == 30)
                        case other                                     => fail(s"unexpected error $other")
                    }
                )
                assert(rs(2).getOrThrow == Event("c", 3))
            end for
        }

        "read applies maxDepth and maxCollectionSize" in {
            for
                dir <- Path.tempDir("kyo-jsonl-read-limits")
                file = dir / "bag.jsonl"
                _     <- file.write(bagLine + "\n")
                depth <- Abort.run[DecodeException](Scope.run(Jsonl.read[Bag](file, maxDepth = 1, maxCollectionSize = 50).run))
                collection <- Abort.run[DecodeException](
                    Scope.run(Jsonl.read[Bag](file, maxDepth = 8, maxCollectionSize = 4).run)
                )
                within <- Scope.run(Jsonl.read[Bag](file, maxDepth = 8, maxCollectionSize = 50).run)
                _      <- dir.removeAll
            yield
                assertLimitBreach(depth, "Nesting depth", 1)
                assertLimitBreach(collection, "Collection size", 4)
                assert(within == Chunk(bag))
            end for
        }

        "readResults applies maxDepth and maxCollectionSize" in {
            for
                dir <- Path.tempDir("kyo-jsonl-read-results-limits")
                file = dir / "bag.jsonl"
                _          <- file.write(bagLine + "\n")
                depth      <- Scope.run(Jsonl.readResults[Bag](file, maxDepth = 1, maxCollectionSize = 50).run)
                collection <- Scope.run(Jsonl.readResults[Bag](file, maxDepth = 8, maxCollectionSize = 4).run)
                within     <- Scope.run(Jsonl.readResults[Bag](file, maxDepth = 8, maxCollectionSize = 50).run)
                _          <- dir.removeAll
            yield
                assert(depth.size == 1)
                assertLimitBreach(depth(0), "Nesting depth", 1)
                assert(collection.size == 1)
                assertLimitBreach(collection(0), "Collection size", 4)
                assert(within.size == 1)
                assert(within(0).getOrThrow == bag)
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

        "skips a terminated oversized record and keeps decoding the records after it" in {
            // "one failure element per bad record, carry on" applies to a breach with a boundary too.
            // Ending at the breach instead would drop Event("c", 3) and everything behind it.
            val in = "{\"name\":\"a\",\"count\":1}\n" + oversizedLine + "\n{\"name\":\"c\",\"count\":3}\n"
            for rs <- byteStream(in).into(Jsonl.pipeResults[Event](maxLineBytes = 30)).run
            yield
                assert(rs.size == 3)
                assert(rs(0).getOrThrow == Event("a", 1))
                rs(1).foldError(
                    _ => fail("expected a failure"),
                    {
                        case Result.Failure(e: LimitExceededException) => assert(e.maximum == 30)
                        case other                                     => fail(s"unexpected error $other")
                    }
                )
                assert(rs(2).getOrThrow == Event("c", 3))
            end for
        }

        "reports the position of a record after a skipped oversized one" in {
            // The skipped line occupies index 1 and its bytes advance the offset, so the record after
            // it is record 2 and sits past both earlier lines. A skip that left the index alone, or
            // one that dropped the skipped line's bytes from the offset, misreports both here.
            val first = "{\"name\":\"a\",\"count\":1}\n"
            val in    = first + oversizedLine + "\n{\"nope\":true}\n"
            val badAt = (first + oversizedLine + "\n").getBytes(StandardCharsets.UTF_8).length.toLong
            for rs <- byteStream(in).into(Jsonl.pipeResults[Event](maxLineBytes = 30)).run
            yield
                assert(rs.size == 3)
                assert(badAt == 85L)
                rs(2).foldError(
                    _ => fail("expected a failure"),
                    {
                        case Result.Failure(e: RecordDecodeException) =>
                            assert(e.recordIndex == 2L)
                            assert(e.byteOffset == badAt)
                        case other => fail(s"unexpected error $other")
                    }
                )
            end for
        }

        "applies maxDepth and maxCollectionSize to terminated and unterminated records" in {
            // The unterminated case reaches the framer's `finish`, which is a separate decode call site from
            // the one a record boundary inside a chunk reaches, with its own pair of limit arguments.
            for
                terminated   <- byteStream(bagLine + "\n").into(Jsonl.pipeResults[Bag](maxDepth = 8, maxCollectionSize = 4)).run
                unterminated <- byteStream(bagLine).into(Jsonl.pipeResults[Bag](maxDepth = 1, maxCollectionSize = 50)).run
                within       <- byteStream(bagLine).into(Jsonl.pipeResults[Bag](maxDepth = 8, maxCollectionSize = 50)).run
            yield
                assert(terminated.size == 1)
                assertLimitBreach(terminated(0), "Collection size", 4)
                assert(unterminated.size == 1)
                assertLimitBreach(unterminated(0), "Nesting depth", 1)
                assert(within.size == 1)
                assert(within(0).getOrThrow == bag)
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

        /** `condition`, but already satisfied once `fiber` has finished.
          *
          * The driver loops above wait for something the follower must produce. A follower that instead stops early, by aborting, never
          * produces it, and the loop would spin forever. Treating "the follower finished" as an exit releases the loop so that the
          * assertion afterwards reports the abort rather than the test hanging on it.
          */
        def doneOr[A, S](fiber: Fiber[A, S])(condition: Boolean < Async)(using Frame): Boolean < Async =
            fiber.done.map(done => if done then true else condition)

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

        "emits the records before a bad one and then aborts" in {
            // Every record is already on disk, so the first read carries all three and the follower reaches
            // the abort without ever polling: no wakeup is needed and none is offered.
            val in = "{\"name\":\"a\",\"count\":1}\n{\"nope\":true}\n{\"name\":\"c\",\"count\":3}\n"
            for
                dir <- Path.tempDir("kyo-jsonl-follow-bad")
                file = dir / "t.jsonl"
                _    <- file.write(in)
                seen <- AtomicRef.init(Chunk.empty[Event])
                r <- Abort.run[DecodeException](
                    Scope.run(
                        Jsonl.follow[Event](file, pollDelay = pollDelay)
                            .take(3)
                            .foreach(e => seen.updateAndGet(_ :+ e))
                    )
                )
                emitted <- seen.get
                _       <- dir.removeAll
            yield
                // The prefix belongs to the consumer even though the read that produced it also produced the
                // failure: decoding the whole read and aborting before emitting would deliver nothing here.
                assert(emitted == Chunk(Event("a", 1)))
                assertRecordFailure(r, 1L)
            end for
        }

        "followResults reports a bad record as one failure and carries on" in {
            val in = "{\"name\":\"a\",\"count\":1}\n{\"nope\":true}\n{\"name\":\"c\",\"count\":3}\n"
            for
                dir <- Path.tempDir("kyo-jsonl-follow-results-bad")
                file = dir / "t.jsonl"
                _  <- file.write(in)
                rs <- Scope.run(Jsonl.followResults[Event](file, pollDelay = pollDelay).take(3).run)
                _  <- dir.removeAll
            yield
                assert(rs.size == 3)
                assert(rs(0).getOrThrow == Event("a", 1))
                assertRecordFailure(rs(1), 1L)
                assert(rs(2).getOrThrow == Event("c", 3))
            end for
        }

        "emits a record written in two partial appends exactly once, whole" in {
            Clock.withTimeControl { control =>
                // One complete record plus the first half of a second, written before the follower starts:
                // reading from Origin.Start makes the follower's start time irrelevant, and the single
                // write means the read that frames the first record necessarily also buffers the partial.
                //
                // The assumption this test's discriminating power rests on: these 37 bytes sit far below
                // the follower's 8192-byte read buffer, so one read delivers the complete first record and
                // the partial second one together. The partial is therefore state the framer has to carry
                // between reads, not an artifact of how the bytes happened to be chunked.
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
                    _ <- advanceUntil(control)(doneOr(fiber)(seen.get.map(_.nonEmpty)))
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

        "followResults replays a truncation that cut a record in half without splicing it" in {
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
                    _ <- advanceUntil(control)(doneOr(fiber)(seen.get.map(_.nonEmpty)))
                    // A single truncating write: the file never grows back past the follower's position.
                    _   <- file.write(replaced)
                    _   <- advanceUntil(control)(fiber.done)
                    _   <- fiber.get
                    got <- seen.get
                    _   <- dir.removeAll
                yield
                    // The rewind carries no marker in the byte stream, so nothing downstream of the follow
                    // loop could tell that `{"nam` went stale. The framer is rebuilt at the rewind instead,
                    // which makes the splice impossible rather than merely unlikely.
                    assert(got.size == 2)
                    assert(got(0).getOrThrow == Event("a", 1))
                    assert(got(1).getOrThrow == Event("b", 2))
                end for
            }
        }

        "follow does not abort when a truncation cuts a record in half" in {
            Clock.withTimeControl { control =>
                // 28 bytes: one complete record and the opening of a second one.
                val existing = "{\"name\":\"a\",\"count\":1}\n{\"nam"
                // 23 bytes, below the follower's position, so the rewind fires on the next poll.
                val replaced = "{\"name\":\"b\",\"count\":2}\n"
                for
                    dir <- Path.tempDir("kyo-jsonl-follow-truncate-strict")
                    file = dir / "t.jsonl"
                    _    <- file.write(existing)
                    seen <- AtomicRef.init(Chunk.empty[Event])
                    fiber <- Fiber.initUnscoped(
                        Abort.run[DecodeException](
                            Scope.run(
                                Jsonl.follow[Event](file, pollDelay = pollDelay)
                                    .take(2)
                                    .foreach(e => seen.updateAndGet(_ :+ e))
                            )
                        )
                    )
                    // The first value proves the read that framed it also buffered `{"nam`.
                    _       <- advanceUntil(control)(doneOr(fiber)(seen.get.map(_.nonEmpty)))
                    _       <- file.write(replaced)
                    _       <- advanceUntil(control)(fiber.done)
                    outcome <- fiber.get
                    got     <- seen.get
                    _       <- dir.removeAll
                yield
                    // The strict variant is where a splice used to be worst: a stale fragment that fails to
                    // parse ends the stream outright, and one that happens to parse ends it with a wrong value.
                    assert(outcome == Result.succeed(()))
                    assert(got == Chunk(Event("a", 1), Event("b", 2)))
                end for
            }
        }

        "follow replays a truncation that fell on a record boundary" in {
            Clock.withTimeControl { control =>
                // 46 bytes: two complete records, so the follower's residual is empty when the file shrinks.
                val existing = "{\"name\":\"a\",\"count\":1}\n{\"name\":\"b\",\"count\":2}\n"
                // 23 bytes, below the follower's position, so the rewind fires on the next poll.
                val replaced = "{\"name\":\"c\",\"count\":3}\n"
                for
                    dir <- Path.tempDir("kyo-jsonl-follow-truncate-boundary")
                    file = dir / "t.jsonl"
                    _    <- file.write(existing)
                    seen <- AtomicRef.init(Chunk.empty[Event])
                    fiber <- Fiber.initUnscoped(
                        Scope.run(
                            Jsonl.follow[Event](file, pollDelay = pollDelay)
                                .take(3)
                                .foreach(e => seen.updateAndGet(_ :+ e))
                        )
                    )
                    // Both existing records emitted, so the follower's cursor sits at the end of the file.
                    _   <- advanceUntil(control)(doneOr(fiber)(seen.get.map(_.size >= 2)))
                    _   <- file.write(replaced)
                    _   <- advanceUntil(control)(fiber.done)
                    _   <- fiber.get
                    got <- seen.get
                    _   <- dir.removeAll
                // Nothing was pending when the file shrank, so this is the case a rebuilt framer must leave
                // exactly as it was: the replayed record decodes on its own and carries the fresh index.
                yield assert(got == Chunk(Event("a", 1), Event("b", 2), Event("c", 3)))
                end for
            }
        }

        "followResults skips a terminated oversized record and keeps following" in {
            Clock.withTimeControl { control =>
                // The record and the oversized line are both on disk before the follower starts, so the
                // first read carries the skip. The third element can then only come from a later poll,
                // which is the point: a follower that ended at the breach never reaches it, and one
                // bad line would end a live log's reader permanently.
                val existing = "{\"name\":\"a\",\"count\":1}\n" + oversizedLine + "\n"
                for
                    dir <- Path.tempDir("kyo-jsonl-follow-skip")
                    file = dir / "t.jsonl"
                    _    <- file.write(existing)
                    seen <- AtomicRef.init(Chunk.empty[Result[DecodeException, Event]])
                    fiber <- Fiber.initUnscoped(
                        Scope.run(
                            Jsonl.followResults[Event](file, pollDelay = pollDelay, maxLineBytes = 30)
                                .take(3)
                                .foreach(r => seen.updateAndGet(_ :+ r))
                        )
                    )
                    // Each append adds one complete record, so any number of them leaves the third
                    // element Event("c", 3) and the assertion holds whenever the follower gets there.
                    _   <- writeUntilDone(fiber, control, file.append("{\"name\":\"c\",\"count\":3}\n"))
                    _   <- fiber.get
                    got <- seen.get
                    _   <- dir.removeAll
                yield
                    assert(got.size == 3)
                    assert(got(0).getOrThrow == Event("a", 1))
                    got(1).foldError(
                        _ => fail("expected a failure"),
                        {
                            case Result.Failure(e: LimitExceededException) => assert(e.maximum == 30)
                            case other                                     => fail(s"unexpected error $other")
                        }
                    )
                    assert(got(2).getOrThrow == Event("c", 3))
                end for
            }
        }

        "follow aborts on a terminated oversized record after emitting the prefix" in {
            // Everything is on disk before the follower starts, so the first read frames the record,
            // skips the oversized line, and reaches the abort without polling. The record after the
            // breach is on disk too, and `take(2)` is what a follower that skipped the breach would
            // satisfy with it: the assertion then reports the wrong prefix rather than waiting forever.
            val in = "{\"name\":\"a\",\"count\":1}\n" + oversizedLine + "\n{\"name\":\"c\",\"count\":3}\n"
            for
                dir <- Path.tempDir("kyo-jsonl-follow-skip-strict")
                file = dir / "t.jsonl"
                _    <- file.write(in)
                seen <- AtomicRef.init(Chunk.empty[Event])
                r <- Abort.run[DecodeException](
                    Scope.run(
                        Jsonl.follow[Event](file, pollDelay = pollDelay, maxLineBytes = 30)
                            .take(2)
                            .foreach(e => seen.updateAndGet(_ :+ e))
                    )
                )
                emitted <- seen.get
                _       <- dir.removeAll
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

        "followResults keeps the records framed before a limit breach, then ends" in {
            Clock.withTimeControl { control =>
                // One record of 22 bytes, then 49 bytes with no terminator: no record boundary can be found
                // for the pending bytes, which is the one framing failure that has nothing to skip to.
                val existing = "{\"name\":\"a\",\"count\":1}\n" + "{\"name\":\"" + ("b" * 40)
                // 23 bytes, below the follower's position, so a rewind would fire on the next poll if the
                // follower were still reading. It is written precisely to prove that it is not.
                val replaced = "{\"name\":\"c\",\"count\":3}\n"
                for
                    dir <- Path.tempDir("kyo-jsonl-follow-breach")
                    file = dir / "t.jsonl"
                    _    <- file.write(existing)
                    seen <- AtomicRef.init(Chunk.empty[Result[DecodeException, Event]])
                    fiber <- Fiber.initUnscoped(
                        Scope.run(
                            Jsonl.followResults[Event](file, pollDelay = pollDelay, maxLineBytes = 30)
                                .take(3)
                                .foreach(r => seen.updateAndGet(_ :+ r))
                        )
                    )
                    // The record framed before the breach and the breach itself both arrive in the first read.
                    _   <- advanceUntil(control)(doneOr(fiber)(seen.get.map(_.size >= 2)))
                    _   <- file.write(replaced)
                    _   <- advanceUntil(control)(fiber.done)
                    _   <- fiber.get
                    got <- seen.get
                    _   <- dir.removeAll
                yield
                    // `take(3)` never reaches a third element: the breach ends the stream after the records
                    // framed before it, so the truncation written afterwards yields nothing at all. A stream
                    // that stayed open would have replayed Event("c", 3) here instead.
                    assert(got.size == 2)
                    assert(got(0).getOrThrow == Event("a", 1))
                    got(1).foldError(
                        _ => fail("expected a failure"),
                        {
                            case Result.Failure(e: LimitExceededException) => assert(e.maximum == 30)
                            case other                                     => fail(s"unexpected error $other")
                        }
                    )
                end for
            }
        }

        "follow keeps the records framed before a limit breach, then aborts" in {
            // One record of 22 bytes, then 49 bytes with no terminator, all on disk before the follower
            // starts: one read frames the record and hits the breach, so the abort is reached without
            // polling and no wakeup is needed.
            val in = "{\"name\":\"a\",\"count\":1}\n" + "{\"name\":\"" + ("b" * 40)
            for
                dir <- Path.tempDir("kyo-jsonl-follow-breach-strict")
                file = dir / "t.jsonl"
                _    <- file.write(in)
                seen <- AtomicRef.init(Chunk.empty[Event])
                r <- Abort.run[DecodeException](
                    Scope.run(
                        Jsonl.follow[Event](file, pollDelay = pollDelay, maxLineBytes = 30)
                            .take(2)
                            .foreach(e => seen.updateAndGet(_ :+ e))
                    )
                )
                emitted <- seen.get
                _       <- dir.removeAll
            yield
                // The framing route into the abort, which "follow emits the records before a bad one and then
                // aborts" does not reach: that one covers the decode route. The breach arrives at the strict
                // variant as a failure element, and the record framed before it is still delivered.
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

        "follow applies maxDepth and maxCollectionSize" in {
            // The whole record is on disk before the follower starts, so the first read reaches the decode
            // without ever polling and no wakeup is needed.
            for
                dir <- Path.tempDir("kyo-jsonl-follow-limits")
                file = dir / "bag.jsonl"
                _ <- file.write(bagLine + "\n")
                depth <- Abort.run[DecodeException](
                    Scope.run(Jsonl.follow[Bag](file, pollDelay = pollDelay, maxDepth = 1, maxCollectionSize = 50).take(1).run)
                )
                collection <- Abort.run[DecodeException](
                    Scope.run(Jsonl.follow[Bag](file, pollDelay = pollDelay, maxDepth = 8, maxCollectionSize = 4).take(1).run)
                )
                within <- Scope.run(
                    Jsonl.follow[Bag](file, pollDelay = pollDelay, maxDepth = 8, maxCollectionSize = 50).take(1).run
                )
                _ <- dir.removeAll
            yield
                assertLimitBreach(depth, "Nesting depth", 1)
                assertLimitBreach(collection, "Collection size", 4)
                assert(within == Chunk(bag))
            end for
        }

        "followResults applies maxDepth and maxCollectionSize" in {
            for
                dir <- Path.tempDir("kyo-jsonl-follow-results-limits")
                file = dir / "bag.jsonl"
                _ <- file.write(bagLine + "\n")
                depth <- Scope.run(
                    Jsonl.followResults[Bag](file, pollDelay = pollDelay, maxDepth = 1, maxCollectionSize = 50).take(1).run
                )
                collection <- Scope.run(
                    Jsonl.followResults[Bag](file, pollDelay = pollDelay, maxDepth = 8, maxCollectionSize = 4).take(1).run
                )
                within <- Scope.run(
                    Jsonl.followResults[Bag](file, pollDelay = pollDelay, maxDepth = 8, maxCollectionSize = 50).take(1).run
                )
                _ <- dir.removeAll
            yield
                assert(depth.size == 1)
                assertLimitBreach(depth(0), "Nesting depth", 1)
                assert(collection.size == 1)
                assertLimitBreach(collection(0), "Collection size", 4)
                assert(within.size == 1)
                assert(within(0).getOrThrow == bag)
            end for
        }
    }

    "encode" - {

        "turns a value stream into jsonl bytes" in {
            for bytes <- Jsonl.encode(Stream.init(Chunk(Event("a", 1)))).run
            yield assert(new String(bytes.toArray, StandardCharsets.UTF_8) == "{\"name\":\"a\",\"count\":1}\n")
        }

        "emits nothing for an empty stream" in {
            for bytes <- Jsonl.encode(Stream.init(Chunk.empty[Event])).run
            yield assert(bytes == Chunk.empty[Byte])
        }

        "terminates every record with a newline whatever the source chunking" in {
            // One newline per record, never one per chunk: the bytes are a function of the values alone,
            // which is the property [[Jsonl.pipe]] holds on the way in.
            val values   = Chunk(Event("a", 1), Event("b", 2), Event("c", 3))
            val expected = "{\"name\":\"a\",\"count\":1}\n{\"name\":\"b\",\"count\":2}\n{\"name\":\"c\",\"count\":3}\n"
            for
                whole <- Jsonl.encode(Stream.init(values, 4096)).run
                byOne <- Jsonl.encode(Stream.init(values, 1)).run
                byTwo <- Jsonl.encode(Stream.init(values, 2)).run
            yield
                assert(new String(whole.toArray, StandardCharsets.UTF_8) == expected)
                assert(byOne == whole)
                assert(byTwo == whole)
            end for
        }

        "encodes non-ascii text as utf-8" in {
            val expected = "{\"name\":\"café\",\"count\":1}\n{\"name\":\"🎉\",\"count\":2}\n"
            for bytes <- Jsonl.encode(Stream.init(Chunk(Event("café", 1), Event("🎉", 2)))).run
            yield
                assert(new String(bytes.toArray, StandardCharsets.UTF_8) == expected)
                assert(bytes.size == expected.getBytes(StandardCharsets.UTF_8).length)
            end for
        }

        "round trips through pipe" in {
            val values = Chunk(Event("café", 1), Event("🎉", 2), Event("a\nb", 3))
            for got <- Jsonl.encode(Stream.init(values)).into(Jsonl.pipe[Event]()).run
            yield assert(got == values)
        }

        "emits one byte chunk per source chunk" in {
            // Each record here encodes to 23 bytes, so two source chunks of two values and one value become
            // byte chunks of 46 and 23. That is the bounded-memory property stated as an observable fact: an
            // encoder accumulating across chunks would emit one chunk of 69 and one encoding per value would
            // emit three of 23, and both are visible here without measuring a heap.
            for
                sizes <- AtomicRef.init(Chunk.empty[Int])
                _ <- Jsonl.encode(Stream.init(Chunk(Event("a", 1), Event("b", 2), Event("c", 3)), 2))
                    .foreachChunk(bytes => sizes.updateAndGet(_ :+ bytes.size))
                got <- sizes.get
            yield assert(got == Chunk(46, 23))
            end for
        }
    }

    "write" - {

        "writes a stream of values as jsonl" in {
            for
                dir <- Path.tempDir("kyo-jsonl-write")
                file = dir / "out.jsonl"
                _    <- Jsonl.write(file, Stream.init(Chunk(Event("a", 1), Event("b", 2))))
                text <- file.read
                _    <- dir.removeAll
            yield assert(text == "{\"name\":\"a\",\"count\":1}\n{\"name\":\"b\",\"count\":2}\n")
        }

        "replaces the existing content rather than adding to it" in {
            // The existing content is longer than what replaces it, so a write that only overwrote from the
            // start would leave the tail of the old content behind and the assertion would see it.
            for
                dir <- Path.tempDir("kyo-jsonl-write-truncate")
                file = dir / "out.jsonl"
                _    <- Jsonl.write(file, Stream.init(Chunk(Event("old", 1), Event("older", 2), Event("oldest", 3))))
                _    <- Jsonl.write(file, Stream.init(Chunk(Event("new", 9))))
                text <- file.read
                _    <- dir.removeAll
            yield assert(text == "{\"name\":\"new\",\"count\":9}\n")
        }

        "writing an empty stream produces an empty file" in {
            for
                dir <- Path.tempDir("kyo-jsonl-write-empty")
                file = dir / "empty.jsonl"
                _    <- Jsonl.write(file, Stream.init(Chunk.empty[Event]))
                text <- file.read
                _    <- dir.removeAll
            yield assert(text == "")
        }

        "writing an empty stream over an existing file empties it" in {
            // The emptying is part of the call rather than of the first chunk, so a stream that never
            // produces one still leaves an empty file instead of the previous content.
            for
                dir <- Path.tempDir("kyo-jsonl-write-empty-over")
                file = dir / "out.jsonl"
                _    <- Jsonl.write(file, Stream.init(Chunk(Event("a", 1))))
                _    <- Jsonl.write(file, Stream.init(Chunk.empty[Event]))
                text <- file.read
                _    <- dir.removeAll
            yield assert(text == "")
        }

        "creates missing parent directories by default" in {
            for
                dir <- Path.tempDir("kyo-jsonl-write-folders")
                file = dir / "nested" / "deeper" / "out.jsonl"
                _    <- Jsonl.write(file, Stream.init(Chunk(Event("a", 1))))
                text <- file.read
                _    <- dir.removeAll
            yield assert(text == "{\"name\":\"a\",\"count\":1}\n")
        }

        "fails when createFolders is false and a parent is missing" in {
            for
                dir <- Path.tempDir("kyo-jsonl-write-no-folders")
                file = dir / "nested" / "out.jsonl"
                r      <- Abort.run[FileWriteException](Jsonl.write(file, Stream.init(Chunk(Event("a", 1))), createFolders = false))
                exists <- file.exists
                _      <- dir.removeAll
            yield
                assertMissingParent(r, file)
                assert(!exists)
            end for
        }

        "round trips through read" in {
            val values = Chunk(Event("café", 1), Event("🎉", 2))
            for
                dir <- Path.tempDir("kyo-jsonl-write-round-trip")
                file = dir / "rt.jsonl"
                _   <- Jsonl.write(file, Stream.init(values))
                got <- Scope.run(Jsonl.read[Event](file).run)
                _   <- dir.removeAll
            yield assert(got == values)
            end for
        }

        "keeps the records already written when the stream fails part way" in {
            // A prefix of a JSONL file is still a valid JSONL file, so the records that completed belong to
            // the consumer. Removing the partial file instead would lose all of them to the record that failed.
            val failing = Stream[Event, Abort[String]](Emit.valueWith(Chunk(Event("a", 1)))(Abort.fail("boom")))
            for
                dir <- Path.tempDir("kyo-jsonl-write-failure")
                file = dir / "partial.jsonl"
                r      <- Abort.run[String](Jsonl.write(file, failing))
                exists <- file.exists
                text   <- file.read
                _      <- dir.removeAll
            yield
                // The stream's own failure reaches the caller unchanged: the write surface neither swallows it
                // nor rewrites it into a file failure of its own once the handle has been closed.
                r.foldError(
                    value => fail(s"expected a failure, got $value"),
                    {
                        case Result.Failure(e) => assert(e == "boom")
                        case other             => fail(s"unexpected error $other")
                    }
                )
                assert(exists)
                assert(text == "{\"name\":\"a\",\"count\":1}\n")
            end for
        }

        "writes each chunk before pulling the next one" in {
            // The bounded-memory property as an observable fact rather than a claim about the heap: the file's
            // size at the moment a chunk is pulled counts the bytes of every chunk written before it, so the
            // write happens between pulls and nothing accumulates across the stream. A fold that buffered the
            // whole stream and wrote once at the end would report 0 at every pull. Each record here encodes to
            // 23 bytes and the source hands out two at a time, so the second pull must see 46.
            val values   = Chunk(Event("a", 1), Event("b", 2), Event("c", 3), Event("d", 4))
            val lineSize = "{\"name\":\"a\",\"count\":1}\n".getBytes(StandardCharsets.UTF_8).length.toLong
            for
                dir <- Path.tempDir("kyo-jsonl-write-incremental")
                file = dir / "out.jsonl"
                sizes <- AtomicRef.init(Chunk.empty[Long])
                source = Stream.init(values, 2).mapChunk(chunk =>
                    file.size.map(size => sizes.updateAndGet(_ :+ size)).andThen(chunk)
                )
                _   <- Jsonl.write(file, source)
                got <- sizes.get
                _   <- dir.removeAll
            yield assert(got == Chunk(0L, 2 * lineSize))
            end for
        }

        "writes a large stream to exactly the expected byte length and record count" in {
            // Nothing here observes memory; what it pins is the byte arithmetic over a stream long enough that
            // the arithmetic errors buffering hides have somewhere to accumulate: a chunk written twice, a chunk
            // dropped, or a newline emitted per chunk instead of per record. Reading the file back pins the
            // record count independently of the length. The chunk-at-a-time write is pinned by the test above.
            val n = 50000
            val expectedSize =
                (0 until n).foldLeft(0L)((acc, i) => acc + Json.encode(Event("e", i)).getBytes(StandardCharsets.UTF_8).length + 1L)
            for
                dir <- Path.tempDir("kyo-jsonl-write-large")
                file = dir / "large.jsonl"
                _     <- Jsonl.write(file, Stream.range(0, n).mapPure(i => Event("e", i)))
                size  <- file.size
                count <- Scope.run(Jsonl.read[Event](file).fold(0)((acc, _) => acc + 1))
                _     <- dir.removeAll
            yield
                assert(size == expectedSize)
                assert(count == n)
            end for
        }
    }

    "append" - {

        "adds to an existing file without truncating it" in {
            for
                dir <- Path.tempDir("kyo-jsonl-append")
                file = dir / "out.jsonl"
                _    <- Jsonl.write(file, Stream.init(Chunk(Event("a", 1))))
                _    <- Jsonl.append(file, Stream.init(Chunk(Event("b", 2))))
                text <- file.read
                _    <- dir.removeAll
            yield assert(text == "{\"name\":\"a\",\"count\":1}\n{\"name\":\"b\",\"count\":2}\n")
        }

        "creates the file when it does not exist" in {
            for
                dir <- Path.tempDir("kyo-jsonl-append-create")
                file = dir / "nested" / "out.jsonl"
                _    <- Jsonl.append(file, Stream.init(Chunk(Event("a", 1))))
                text <- file.read
                _    <- dir.removeAll
            yield assert(text == "{\"name\":\"a\",\"count\":1}\n")
        }

        "appending an empty stream creates a file that does not exist" in {
            // The file's existence is a fact about the call, not about whether the stream produced anything,
            // so an append of nothing leaves the same empty log an append of nothing to an empty file leaves.
            for
                dir <- Path.tempDir("kyo-jsonl-append-empty-create")
                file = dir / "out.jsonl"
                _      <- Jsonl.append(file, Stream.init(Chunk.empty[Event]))
                exists <- file.exists
                text   <- file.read
                _      <- dir.removeAll
            yield
                assert(exists)
                assert(text == "")
            end for
        }

        "appending an empty stream leaves the file unchanged" in {
            for
                dir <- Path.tempDir("kyo-jsonl-append-empty")
                file = dir / "out.jsonl"
                _    <- Jsonl.write(file, Stream.init(Chunk(Event("a", 1))))
                _    <- Jsonl.append(file, Stream.init(Chunk.empty[Event]))
                text <- file.read
                _    <- dir.removeAll
            yield assert(text == "{\"name\":\"a\",\"count\":1}\n")
        }

        "splices onto a last record that carries no trailing newline" in {
            // The documented hazard, pinned as bytes. Appending starts at the file's current end and writes no
            // separator, so a file whose last line was never terminated gets the appended record stuck onto it.
            // The state is reachable rather than hypothetical: `pipe` accepts an unterminated final record, so
            // `read` reads such a file without complaint and nothing before the append reports anything wrong.
            // A future change that "helpfully" inserted a newline first would fail here, which is the point:
            // the separator is the caller's to write, and this is the behavior the scaladoc promises.
            for
                dir <- Path.tempDir("kyo-jsonl-append-splice")
                file = dir / "unterminated.jsonl"
                _    <- file.write("{\"name\":\"a\",\"count\":1}")
                _    <- Jsonl.append(file, Stream.init(Chunk(Event("b", 2))))
                text <- file.read
                r    <- Abort.run[DecodeException](Scope.run(Jsonl.read[Event](file).run))
                _    <- dir.removeAll
            yield
                assert(text == "{\"name\":\"a\",\"count\":1}{\"name\":\"b\",\"count\":2}\n")
                // Two records became one line, and that line is not a record: the splice is a corruption
                // rather than a cosmetic join, and it surfaces only on the read that follows it.
                assertRecordFailure(r, 0L)
            end for
        }

        "fails when createFolders is false and a parent is missing" in {
            for
                dir <- Path.tempDir("kyo-jsonl-append-no-folders")
                file = dir / "nested" / "out.jsonl"
                r      <- Abort.run[FileWriteException](Jsonl.append(file, Stream.init(Chunk(Event("a", 1))), createFolders = false))
                exists <- file.exists
                _      <- dir.removeAll
            yield
                assertMissingParent(r, file)
                assert(!exists)
            end for
        }

        "repeated appends build a log readable in one pass" in {
            val rounds = 200
            for
                dir <- Path.tempDir("kyo-jsonl-append-rounds")
                file = dir / "log.jsonl"
                _   <- Kyo.foreachDiscard(Chunk.from(0 until rounds))(i => Jsonl.append(file, Stream.init(Chunk(Event("e", i)))))
                got <- Scope.run(Jsonl.read[Event](file).run)
                _   <- dir.removeAll
            yield assert(got == Chunk.from((0 until rounds).map(i => Event("e", i))))
            end for
        }
    }
end JsonlTest
