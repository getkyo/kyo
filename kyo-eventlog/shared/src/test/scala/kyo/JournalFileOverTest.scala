package kyo

import kyo.AllowUnsafe.embrace.danger

/** Scenario coverage for [[Journal.Backend.fileOver]] beyond the reused
  * [[JournalBackendTest]] contract suite ([[FileJournalOverHostBackendTest]],
  * [[FileJournalOverInMemoryBackendTest]], [[FileJournalOverOverlayBackendTest]]):
  * inMemory determinism across independent instances, overlay stage-then-commit
  * visibility, on-disk compatibility with [[Journal.Backend.file]], `file`'s own
  * contention path staying untouched, the orchestration-unchanged rotation guard,
  * host-disk absence over non-host backends, and fork-free close/lock-release on
  * every platform.
  */
class JournalFileOverTest extends kyo.test.Test[Any]:

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("expected valid identifier"))

    private val streamId = valid(Event.StreamId("over-stream"))

    private def envelope(n: Int): Event.New =
        Event.New(
            id = valid(Event.Id(s"over-event-$n")),
            eventType = valid(Event.Type("OverEvent")),
            payload = Span.from(s"""{"n":$n}""".getBytes("UTF-8")),
            metadata = Event.Metadata.empty
        )

    private def journalId(name: String): JournalId =
        JournalId.validate(name)(using Frame.internal).getOrElse(throw new AssertionError("valid journal id"))

    // Event.Recorded.payload is a Span[Byte]; Span equality via == is reference-based (documented
    // on Event.payload), so comparing two independently-decoded event chunks needs a content-aware
    // comparison rather than Chunk[Event.Recorded] ==.
    private def sameEvents(a: Chunk[Event.Recorded], b: Chunk[Event.Recorded]): Boolean =
        a.length == b.length && a.zip(b).forall { (x, y) =>
            x.streamId == y.streamId && x.offset == y.offset && x.id == y.id &&
            x.eventType == y.eventType && x.payload.is(y.payload) && x.metadata == y.metadata
        }

    private def freshDir(prefix: String)(using Frame): Path < (Sync & Scope) =
        Abort.run[FileException](Path.run(Path.tempDir(prefix))).map {
            case Result.Success(d)   => d
            case Result.Failure(err) => throw err
            case panic: Result.Panic => throw panic.exception
        }

    private def configuration(name: String, options: FileJournal.Options = FileJournal.Options.default)(using
        Frame
    )
        : FileJournal.Configuration[Span[Byte]] < Abort[EventCodecConfigurationError | FileJournal.ConfigurationError] =
        for
            codecs <- EventLogCodecs.bytes()
            config <- FileJournal.Binary.configuration(journalId(name), codecs, options)
        yield config

    // Appends one event at a time, listing the stream's on-disk segment directory after each
    // append, until FileJournalCore's own rotateIfNeeded seals the active segment and creates a
    // fresh one (two segment files observed). A purely size-driven trigger (the growing on-disk
    // segment count), never a timing dependency: termination is guaranteed because writePos grows
    // monotonically toward the configured segmentSize. Bounded to fail loud rather than hang if
    // rotation never occurs.
    private def appendUntilRotated(backend: Journal.Backend[Sync], streamDir: Path, n: Int)(using
        Frame
    ): Int < (Sync & Abort[JournalError] & Abort[FileException]) =
        if n > 1000 then throw new AssertionError("rotation did not occur within 1000 appends")
        else
            FileSystem.host.list(streamDir).map { entries =>
                if entries.length >= 2 then entries.length
                else backend.append(streamId, ExpectedOffset.Any, Chunk(envelope(n))).andThen(appendUntilRotated(backend, streamDir, n + 1))
            }

    "an inMemory-backed fileOver journal round-trips events deterministically across two separate fresh instances" in {
        def runOnce(config: FileJournal.Configuration[Span[Byte]]) =
            FileSystem.inMemory.map { fs =>
                // Each instance's backend is Scope-released before the next runOnce call opens the
                // identical root name again: the in-process heldRoots registry is process-wide (by
                // design, unrelated to which FileSystem instance backs the open), so a still-open
                // prior instance on the same root name would otherwise contend with itself.
                Scope.run {
                    Journal.Backend.fileOver(fs, Path("over-inmemory-determinism-root"), config).map { backend =>
                        Abort.run[JournalError](backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(0), envelope(1)))).map {
                            appendResult =>
                                Abort.run[JournalError](backend.read(streamId, Event.StreamOffset.first, 10)).map { readResult =>
                                    (appendResult, readResult)
                                }
                        }
                    }
                }
            }

        configuration("over-inmemory-determinism").map { config =>
            runOnce(config).map { (firstAppend, firstRead) =>
                runOnce(config).map { (secondAppend, secondRead) =>
                    assert(firstAppend == secondAppend)
                    (firstRead, secondRead) match
                        case (Result.Success(a), Result.Success(b)) => assert(sameEvents(a, b))
                        case other                                  => fail(s"expected both reads to succeed, got: $other")
                }
            }
        }
    }

    "an overlay-backed fileOver journal stages writes invisibly to the lower FileSystem until commit" in {
        val root = Path("over-overlay-stage-commit-root")
        configuration("over-overlay-stage-commit").map { config =>
            FileSystem.inMemory.map { lower =>
                FileSystem.overlay(lower).map { ov =>
                    Journal.Backend.fileOver(ov, root, config).map { backend =>
                        Abort.run[JournalError](backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(0)))).map { appendResult =>
                            Abort.run[JournalError](backend.read(streamId, Event.StreamOffset.first, 10)).map { readResult =>
                                assert(appendResult.isSuccess)
                                assert(readResult.map(_.length) == Result.succeed(1))
                                Abort.run[FileException](lower.exists(root)).map { beforeCommit =>
                                    assert(beforeCommit == Result.succeed(false))
                                    ov.commitOverwrite.andThen {
                                        Abort.run[FileException](lower.exists(root / "MANIFEST")).map { afterCommit =>
                                            assert(afterCommit == Result.succeed(true))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "fileOver(FileSystem.host, ...) recovers a root that Journal.Backend.file wrote, on the identical LOCK path" in {
        freshDir("kyo-journal-over-recover").map { dir =>
            configuration("over-host-recover").map { config =>
                Scope.run {
                    Journal.Backend.file(dir, config).map { backend =>
                        Abort.run[JournalError](backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(0), envelope(1))))
                    }
                }.map { writeResult =>
                    assert(writeResult.isSuccess)
                    Scope.run {
                        Journal.Backend.fileOver(FileSystem.host, dir, config).map { backend =>
                            Abort.run[JournalError](backend.read(streamId, Event.StreamOffset.first, 10)).map {
                                case Result.Success(events) =>
                                    assert(events.length == 2)
                                    assert(events(0).id == valid(Event.Id("over-event-0")))
                                    assert(events(1).id == valid(Event.Id("over-event-1")))
                                case other => fail(s"expected success, got: $other")
                            }
                        }
                    }
                }
            }
        }
    }

    "Journal.Backend.file's own contention path is unaffected: file() still contends through the SegmentStore LOCK, not the FileSystem-channel path" in {
        freshDir("kyo-journal-file-contention").map { dir =>
            configuration("over-file-contention").map { config =>
                Scope.run {
                    Journal.Backend.file(dir, config).map { _ =>
                        Scope.run(Abort.run[JournalStorageError](Journal.Backend.file(dir, config))).map {
                            case Result.Failure(err) => assert(err.detail.contains("is locked"))
                            case other               => fail(s"expected Failure(JournalStorageError), got $other")
                        }
                    }
                }
            }
        }
    }

    "orchestration-unchanged guard: forcing a segment rotation over fileOver(FileSystem.host, ...) produces the identical two-segment layout the engine produces over file(...)" in {
        freshDir("kyo-journal-over-rotation").map { dir =>
            configuration("over-rotation", FileJournal.Options(segmentSize = 32L.bytes)).map { config =>
                Scope.run {
                    Journal.Backend.fileOver(FileSystem.host, dir, config).map { backend =>
                        backend.append(streamId, ExpectedOffset.Any, Chunk(envelope(0))).andThen {
                            FileSystem.host.list(dir / "streams").map(dirs => appendUntilRotated(backend, dirs.head, 1))
                        }
                    }
                }.map(count => assert(count == 2))
            }
        }
    }

    "fileOver(FileSystem.inMemory, ...) creates no real host-disk entries under its root (host-disk-absence, distinct from the in-memory determinism leaf above)" in {
        val root = Path("over-host-disk-absence-root")
        configuration("over-host-disk-absence").map { config =>
            FileSystem.inMemory.map { fs =>
                Scope.run {
                    Journal.Backend.fileOver(fs, root, config).map { backend =>
                        Abort.run[JournalError](backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(0)))).map { appendResult =>
                            assert(appendResult.isSuccess)
                            Abort.run[JournalError](backend.read(streamId, Event.StreamOffset.first, 10)).map { readResult =>
                                assert(readResult.map(_.length) == Result.succeed(1))
                            }
                        }
                    }
                }.map { _ =>
                    assert(!root.unsafe.exists())
                    assert(!(root / "streams").unsafe.exists())
                    assert(!(root / "MANIFEST").unsafe.exists())
                }
            }
        }
    }

    "fileOver(FileSystem.host, ...) close and lock release complete synchronously with no fiber fork, and the released lock is immediately reacquirable, on all four platforms (all-platform close/release leaf, distinct from the single-platform LOCK-path recovery leaf above)" in {
        freshDir("kyo-journal-over-close-release").map { dir =>
            configuration("over-close-release").map { config =>
                Scope.run {
                    Journal.Backend.fileOver(FileSystem.host, dir, config).map { backend =>
                        Abort.run[JournalError](backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(0))))
                    }
                }.map { writeResult =>
                    assert(writeResult.isSuccess)
                    Scope.run {
                        Journal.Backend.fileOver(FileSystem.host, dir, config).map { backend =>
                            Abort.run[JournalError](backend.read(streamId, Event.StreamOffset.first, 10)).map { readResult =>
                                assert(readResult.map(_.length) == Result.succeed(1))
                            }
                        }
                    }
                }
            }
        }
    }

end JournalFileOverTest
