package kyo

import kyo.AllowUnsafe.embrace.danger

/** Scenario coverage for [[Journal.Backend.fileOver]] composed over the two zip/archive
  * [[FileSystem]] backends: a journal replayed read-only from [[FileSystem.zipReadOnly]] over a
  * migrated archive, and a journal batch-appended into [[FileSystem.zip]]'s staged upper, durable
  * only after an explicit commit.
  */
class JournalZipOverTest extends kyo.test.Test[Any]:

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("expected valid identifier"))

    private val streamId = valid(Event.StreamId("zip-over-stream"))

    private def envelope(n: Int): Event.New =
        Event.New(
            id = valid(Event.Id(s"zip-over-event-$n")),
            eventType = valid(Event.Type("ZipOverEvent")),
            payload = Span.from(s"""{"n":$n}""".getBytes("UTF-8")),
            metadata = Event.Metadata.empty
        )

    private def configuration(name: String)(using
        Frame
    ): FileJournal.Configuration[Span[Byte]] < Abort[EventCodecConfigurationError] =
        EventLogCodecs.bytes().map { codecs =>
            FileJournal.Binary.configuration(journalId(name), codecs, FileJournal.Options.default)
        }

    private def journalId(name: String): JournalId =
        JournalId.validate(name)(using Frame.internal).getOrElse(throw new AssertionError("valid journal id"))

    /** A fresh real host temp dir, removed at the enclosing [[Scope]]'s exit. */
    private def hostTempDir(prefix: String): Path < (Sync & Scope & Abort[FileException]) =
        Scope.acquireRelease(FileSystem.host.tempDir(prefix))(h =>
            // Unsafe: service-vended recursive cleanup at Scope exit, mirroring Path.tempDir.
            Sync.Unsafe.defer(h.remove())
        ).map(_.path)

    // Copies every regular file under `root` (recursively) from `from` into `to`, at the identical
    // Path, creating parent folders as needed: the item-8 file->zip migrate shape a journal root
    // written via Journal.Backend.file/fileOver(FileSystem.host, ...) needs to move onto an archive.
    private def migrateAll(from: FileSystem[Sync], to: FileSystem[Sync], root: Path): Unit < (Sync & Abort[FileException]) =
        from.openWalk(root, Int.MaxValue, followLinks = false).map { handle =>
            Loop(()) { _ =>
                Sync.Unsafe.defer(handle.next()).map {
                    case Absent => Sync.Unsafe.defer(handle.close()).andThen(Loop.done(()))
                    case Present(p) =>
                        from.isDirectory(p).map { isDir =>
                            if isDir then Loop.continue(())
                            else from.readBytes(p).map(bytes => to.writeBytes(p, bytes, createFolders = true).andThen(Loop.continue(())))
                        }
                }
            }
        }

    "fileOver(FileSystem.zipReadOnly(archive), dir, config) replays previously committed journal history read-only" in {
        configuration("zip-over-readonly-replay").map { config =>
            Scope.run {
                hostTempDir("kyo-journal-zip-readonly").map { dir =>
                    val journalRoot = dir / "journal-root"
                    val archive     = dir / "archive.zip"
                    Scope.run {
                        Journal.Backend.fileOver(FileSystem.host, journalRoot, config).map { backend =>
                            Abort.run[JournalError](backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(0), envelope(1))))
                        }
                    }.map { writeResult =>
                        assert(writeResult.isSuccess)
                        FileSystem.zip(archive).map { zipHandle =>
                            migrateAll(FileSystem.host, zipHandle, journalRoot).andThen(zipHandle.commit)
                        }.andThen {
                            FileSystem.zipReadOnly(archive).map { zr =>
                                Journal.Backend.fileOver(zr, journalRoot, config).map { readBackend =>
                                    Abort.run[JournalError](readBackend.read(streamId, Event.StreamOffset.first, 10)).map {
                                        case Result.Success(events) =>
                                            assert(events.length == 2)
                                            assert(events(0).id == valid(Event.Id("zip-over-event-0")))
                                            assert(events(1).id == valid(Event.Id("zip-over-event-1")))
                                            Abort.run[JournalError](readBackend.append(
                                                streamId,
                                                ExpectedOffset.Any,
                                                Chunk(envelope(2))
                                            )).map {
                                                case Result.Failure(e) => assert(e.getMessage.nonEmpty)
                                                case other => assert(
                                                        false,
                                                        s"expected append over a read-only zip-backed journal to fail typed, got $other"
                                                    )
                                            }
                                        case other => assert(false, s"expected success, got $other")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "fileOver(FileSystem.zip(archive), dir, config) batch-appends into the staged upper and only durably materializes on an explicit commit" in {
        val root = Path("zip-over-batch-root")
        configuration("zip-over-batch").map { config =>
            Scope.run {
                hostTempDir("kyo-journal-zip-batch").map { dir =>
                    val archive = dir / "archive.zip"
                    // Scope-closes the write-side backend (releasing its process-wide heldRoots
                    // registration on the same root name) before the read-side backend below opens.
                    Scope.run {
                        FileSystem.zip(archive).map { zipHandle =>
                            Journal.Backend.fileOver(zipHandle, root, config).map { backend =>
                                Abort.run[JournalError](backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(0), envelope(1))))
                                    .map { appendResult =>
                                        assert(appendResult.isSuccess)
                                        FileSystem.host.exists(archive).map { beforeCommit =>
                                            assert(!beforeCommit)
                                            zipHandle.commit
                                        }
                                    }
                            }
                        }
                    }.andThen {
                        Scope.run {
                            FileSystem.zipReadOnly(archive).map { zr =>
                                Journal.Backend.fileOver(zr, root, config).map { readBackend =>
                                    Abort.run[JournalError](readBackend.read(streamId, Event.StreamOffset.first, 10)).map { readResult =>
                                        assert(readResult.map(_.length) == Result.succeed(2))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end JournalZipOverTest
