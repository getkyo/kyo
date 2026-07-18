package kyo

/** Scenario coverage for [[EventLog.migrate]], [[EventLog.migrateWith]], [[EventLog.Reader#upcast]],
  * and [[EventLog.MigrationReport]]: copying and rewrite-copying event streams across journal
  * media (host file, in-memory, a staged zip archive) and across on-disk formats (Binary,
  * JSONL), and adapting a typed reader's decoded values without touching stored bytes.
  */
class EventLogMigrationTest extends kyo.test.Test[Any]:

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("expected valid identifier"))

    private def journalId(name: String): JournalId =
        JournalId.validate(name)(using Frame.internal).getOrElse(throw new AssertionError("valid journal id"))

    private def offset(value: Long): Event.StreamOffset =
        Event.StreamOffset(value).getOrElse(throw new AssertionError("valid offset"))

    private def envelope(id: String, n: Int, eventType: String = "MigrateEvent"): Event.Pending =
        Event.Pending(
            id = valid(Event.Id(id)),
            eventType = valid(Event.Type(eventType)),
            payload = Span.from(s"""{"n":$n}""".getBytes("UTF-8")),
            metadata = Event.Metadata.empty
        )

    /** A fresh real host temp dir, removed at the enclosing [[Scope]]'s exit. */
    private def hostTempDir(prefix: String): Path < (Sync & Scope & Abort[FileException]) =
        Scope.acquireRelease(FileSystem.host.tempDir(prefix))(h =>
            // Unsafe: service-vended recursive cleanup at Scope exit, mirroring Path.tempDir.
            Sync.Unsafe.defer(h.remove())
        ).map(_.path)

    private def binaryConfig(name: String)(using
        Frame
    ): FileJournal.Configuration[Span[Byte]] < Abort[EventCodecConfigurationError | FileJournal.ConfigurationError] =
        for
            codecs <- EventLogCodecs.bytes()
            config <- FileJournal.Binary.configuration(journalId(name), codecs, FileJournal.Options.default)
        yield config

    private def jsonlConfig(name: String)(using
        Frame
    ): FileJournal.Configuration[Span[Byte]] < Abort[EventCodecConfigurationError | FileJournal.ConfigurationError] =
        for
            codecs <- EventLogCodecs.bytes()
            config <- FileJournal.Jsonl.configuration(journalId(name), codecs, FileJournal.Options.default)
        yield config

    "migrate copies a stream from a Binary-profile host journal to a Jsonl-profile host journal with no type obstacle, and MigrationReport reports the copy" in {
        val streamId = valid(Event.StreamId("migrate-cross-format"))
        Scope.run {
            hostTempDir("kyo-eventlog-migrate-cross-format").map { dir =>
                for
                    sourceConfig <- binaryConfig("migrate-cross-format-source")
                    targetConfig <- jsonlConfig("migrate-cross-format-target")
                    source       <- Journal.Backend.file(dir / "source", sourceConfig)
                    target       <- Journal.Backend.file(dir / "target", targetConfig)
                    _ <- Abort.run[JournalError](source.append(
                        streamId,
                        ExpectedOffset.NoStream,
                        Chunk(envelope("cross-format-0", 0), envelope("cross-format-1", 1))
                    ))
                    migrated   <- Abort.run[JournalError](EventLog.migrate(source, target, Chunk(streamId)))
                    targetRead <- Abort.run[JournalError](target.read(streamId, Event.StreamOffset.first, 10))
                yield
                    assert(migrated == Result.succeed(EventLog.MigrationReport(Chunk(
                        EventLog.MigrationReport.StreamSummary(streamId, 2L, Present(offset(1)))
                    ))))
                    targetRead match
                        case Result.Success(events) =>
                            assert(events.length == 2)
                            assert(events(0).id == valid(Event.Id("cross-format-0")))
                            assert(events(0).payload.is(Span.from("""{"n":0}""".getBytes("UTF-8"))))
                            assert(events(1).id == valid(Event.Id("cross-format-1")))
                            assert(events(1).payload.is(Span.from("""{"n":1}""".getBytes("UTF-8"))))
                        case other => fail(s"expected success, got: $other")
                    end match
            }
        }
    }

    "migrate copies a stream from a host-file journal to a fresh in-memory journal" in {
        val streamId = valid(Event.StreamId("migrate-file-to-memory"))
        Scope.run {
            hostTempDir("kyo-eventlog-migrate-file-to-memory").map { dir =>
                for
                    sourceConfig <- binaryConfig("migrate-file-to-memory-source")
                    source       <- Journal.Backend.file(dir, sourceConfig)
                    target       <- Journal.Backend.inMemory
                    _ <- Abort.run[JournalError](source.append(streamId, ExpectedOffset.NoStream, Chunk(envelope("file-to-memory-0", 0))))
                    migrated   <- Abort.run[JournalError](EventLog.migrate(source, target, Chunk(streamId)))
                    targetRead <- Abort.run[JournalError](target.read(streamId, Event.StreamOffset.first, 10))
                yield
                    assert(migrated == Result.succeed(EventLog.MigrationReport(Chunk(
                        EventLog.MigrationReport.StreamSummary(streamId, 1L, Present(offset(0)))
                    ))))
                    assert(targetRead.map(_.length) == Result.succeed(1))
            }
        }
    }

    "migrate copies a stream from a host-file journal into a FileSystem.zip staged archive target, durable only after an explicit commit" in {
        val streamId = valid(Event.StreamId("migrate-file-to-zip"))
        val zipRoot  = Path("migrate-zip-root")
        Scope.run {
            hostTempDir("kyo-eventlog-migrate-file-to-zip").map { dir =>
                val sourceDir = dir / "source"
                val archive   = dir / "archive.zip"
                for
                    sourceConfig <- binaryConfig("migrate-file-to-zip-source")
                    targetConfig <- binaryConfig("migrate-file-to-zip-target")
                    source       <- Journal.Backend.file(sourceDir, sourceConfig)
                    _ <- Abort.run[JournalError](source.append(
                        streamId,
                        ExpectedOffset.NoStream,
                        Chunk(envelope("file-to-zip-0", 0), envelope("file-to-zip-1", 1))
                    ))
                    migrated <- Scope.run {
                        for
                            zipHandle     <- FileSystem.zip(archive)
                            target        <- Journal.Backend.fileOver(zipHandle, zipRoot, targetConfig)
                            migrateResult <- Abort.run[JournalError](EventLog.migrate(source, target, Chunk(streamId)))
                            beforeCommit  <- FileSystem.host.exists(archive)
                            _             <- zipHandle.commit
                        yield
                            assert(!beforeCommit)
                            migrateResult
                    }
                    afterRead <- Scope.run {
                        for
                            zr          <- FileSystem.zipReadOnly(archive)
                            readBackend <- Journal.Backend.fileOver(zr, zipRoot, targetConfig)
                            result      <- Abort.run[JournalError](readBackend.read(streamId, Event.StreamOffset.first, 10))
                        yield result
                    }
                yield
                    assert(migrated == Result.succeed(EventLog.MigrationReport(Chunk(
                        EventLog.MigrationReport.StreamSummary(streamId, 2L, Present(offset(1)))
                    ))))
                    afterRead match
                        case Result.Success(events) =>
                            assert(events.length == 2)
                            assert(events(0).id == valid(Event.Id("file-to-zip-0")))
                            assert(events(1).id == valid(Event.Id("file-to-zip-1")))
                        case other => fail(s"expected success, got: $other")
                    end match
                end for
            }
        }
    }

    "MigrationReport reports a caller-named stream with zero events honestly: copied 0 and an absent lastOffset when the target also has no prior data" in {
        val streamId = valid(Event.StreamId("migrate-empty-stream"))
        for
            source     <- Journal.Backend.inMemory
            target     <- Journal.Backend.inMemory
            migrated   <- Abort.run[JournalError](EventLog.migrate(source, target, Chunk(streamId)))
            targetInfo <- Abort.run[JournalError](target.streamInfo(streamId))
        yield
            assert(migrated == Result.succeed(EventLog.MigrationReport(Chunk(
                EventLog.MigrationReport.StreamSummary(streamId, 0L, Absent)
            ))))
            assert(targetInfo == Result.succeed(StreamInfo.Absent))
        end for
    }

    "migrate against a target whose named stream already holds prior, unrelated data seeds ExpectedOffset from the target's current state and extends it contiguously" in {
        val streamId = valid(Event.StreamId("migrate-seed-prior"))
        for
            source <- Journal.Backend.inMemory
            target <- Journal.Backend.inMemory
            _      <- Abort.run[JournalError](target.append(streamId, ExpectedOffset.NoStream, Chunk(envelope("seed-prior-pre", 0))))
            _ <- Abort.run[JournalError](source.append(
                streamId,
                ExpectedOffset.NoStream,
                Chunk(envelope("seed-prior-0", 10), envelope("seed-prior-1", 11))
            ))
            migrated   <- Abort.run[JournalError](EventLog.migrate(source, target, Chunk(streamId)))
            targetRead <- Abort.run[JournalError](target.read(streamId, Event.StreamOffset.first, 10))
        yield
            assert(migrated == Result.succeed(EventLog.MigrationReport(Chunk(
                EventLog.MigrationReport.StreamSummary(streamId, 2L, Present(offset(2)))
            ))))
            targetRead match
                case Result.Success(events) =>
                    assert(events.length == 3)
                    assert(events(0).id == valid(Event.Id("seed-prior-pre")))
                    assert(events(0).offset == offset(0))
                    assert(events(1).id == valid(Event.Id("seed-prior-0")))
                    assert(events(1).offset == offset(1))
                    assert(events(2).id == valid(Event.Id("seed-prior-1")))
                    assert(events(2).offset == offset(2))
                case other => fail(s"expected success, got: $other")
            end match
        end for
    }

    "EventLog.Reader#upcast applies f on every read without touching stored bytes: the underlying journal is still decodable under its original reader afterward" in {
        val streamId = valid(Event.StreamId("migrate-upcast"))
        Scope.run {
            hostTempDir("kyo-eventlog-migrate-upcast").map { dir =>
                for
                    intCodecs <- EventLogCodecs.schema[Int]()
                    intConfig <- FileJournal.Binary.configuration(journalId("migrate-upcast"), intCodecs, FileJournal.Options.default)
                    backend   <- Journal.Backend.file(dir, intConfig)
                    encoded0  <- EventLogCodecs.encodeValue(intCodecs.value, 1)
                    encoded1  <- EventLogCodecs.encodeValue(intCodecs.value, 2)
                    _ <- Abort.run[JournalError](backend.append(
                        streamId,
                        ExpectedOffset.NoStream,
                        Chunk(
                            Event.Pending(valid(Event.Id("upcast-0")), valid(Event.Type("UpcastEvent")), encoded0, Event.Metadata.empty),
                            Event.Pending(valid(Event.Id("upcast-1")), valid(Event.Type("UpcastEvent")), encoded1, Event.Metadata.empty)
                        )
                    ))
                    intReader <- EventLog.reader(backend)
                    upcasted = intReader.upcast[String](n => n.toString)
                    upcastedRead <- Abort.run[JournalReadFailure](upcasted.read(streamId, Event.StreamOffset.first, 10))
                    intReadAgain <- Abort.run[JournalReadFailure](intReader.read(streamId, Event.StreamOffset.first, 10))
                yield
                    upcastedRead match
                        case Result.Success(events) =>
                            assert(events.length == 2)
                            assert(events(0).payload == "1")
                            assert(events(1).payload == "2")
                        case other => fail(s"expected success, got: $other")
                    end match
                    intReadAgain match
                        case Result.Success(events) =>
                            assert(events.length == 2)
                            assert(events(0).payload == 1)
                            assert(events(1).payload == 2)
                        case other => fail(s"expected success, got: $other")
                    end match
            }
        }
    }

    "migrateWith rewrite-migrates: the target's raw stored bytes genuinely change from the source's Int encoding to a String encoding, and a String-typed reader decodes the transformed values" in {
        val streamId = valid(Event.StreamId("migrate-with-rewrite"))
        Scope.run {
            hostTempDir("kyo-eventlog-migrate-with-rewrite").map { dir =>
                val sourceDir = dir / "source"
                val targetDir = dir / "target"
                for
                    intCodecs    <- EventLogCodecs.schema[Int]()
                    stringCodecs <- EventLogCodecs.schema[String]()
                    sourceConfig <-
                        FileJournal.Binary.configuration(journalId("migrate-with-source"), intCodecs, FileJournal.Options.default)
                    targetConfig <-
                        FileJournal.Binary.configuration(journalId("migrate-with-target"), stringCodecs, FileJournal.Options.default)
                    sourceBackend <- Journal.Backend.file(sourceDir, sourceConfig)
                    targetBackend <- Journal.Backend.file(targetDir, targetConfig)
                    encoded7      <- EventLogCodecs.encodeValue(intCodecs.value, 7)
                    encoded8      <- EventLogCodecs.encodeValue(intCodecs.value, 8)
                    _ <- Abort.run[JournalError](sourceBackend.append(
                        streamId,
                        ExpectedOffset.NoStream,
                        Chunk(
                            Event.Pending(
                                valid(Event.Id("migrate-with-0")),
                                valid(Event.Type("MigrateWithEvent")),
                                encoded7,
                                Event.Metadata.empty
                            ),
                            Event.Pending(
                                valid(Event.Id("migrate-with-1")),
                                valid(Event.Type("MigrateWithEvent")),
                                encoded8,
                                Event.Metadata.empty
                            )
                        )
                    ))
                    sourceReader <- EventLog.reader(sourceBackend)
                    migrated <- Abort.run[JournalError](EventLog.migrateWith[Int, String, Sync, Sync](
                        sourceReader,
                        targetBackend,
                        stringCodecs,
                        Chunk(streamId),
                        n => n.toString
                    ))
                    targetReader  <- EventLog.reader(targetBackend)
                    decodedRead   <- Abort.run[JournalError](targetReader.read(streamId, Event.StreamOffset.first, 10))
                    sourceRawRead <- Abort.run[JournalError](sourceBackend.read(streamId, Event.StreamOffset.first, 10))
                    targetRawRead <- Abort.run[JournalError](targetBackend.read(streamId, Event.StreamOffset.first, 10))
                yield
                    assert(migrated == Result.succeed(EventLog.MigrationReport(Chunk(
                        EventLog.MigrationReport.StreamSummary(streamId, 2L, Present(offset(1)))
                    ))))
                    decodedRead match
                        case Result.Success(events) =>
                            assert(events.length == 2)
                            assert(events(0).payload == "7")
                            assert(events(1).payload == "8")
                        case other => fail(s"expected success, got: $other")
                    end match
                    (sourceRawRead, targetRawRead) match
                        case (Result.Success(sourceEvents), Result.Success(targetEvents)) =>
                            assert(sourceEvents.length == 2)
                            assert(targetEvents.length == 2)
                            assert(!sourceEvents(0).payload.is(targetEvents(0).payload))
                            assert(!sourceEvents(1).payload.is(targetEvents(1).payload))
                        case other => fail(s"expected both reads to succeed, got: $other")
                    end match
                end for
            }
        }
    }

    "migrate copies multiple named streams in one call, attributing each StreamSummary to its own stream with no cross-stream bleed" in {
        val streamA = valid(Event.StreamId("migrate-multi-a"))
        val streamB = valid(Event.StreamId("migrate-multi-b"))
        for
            source <- Journal.Backend.inMemory
            target <- Journal.Backend.inMemory
            _ <- Abort.run[JournalError](source.append(
                streamA,
                ExpectedOffset.NoStream,
                Chunk(envelope("multi-a-0", 0), envelope("multi-a-1", 1))
            ))
            _ <- Abort.run[JournalError](source.append(
                streamB,
                ExpectedOffset.NoStream,
                Chunk(envelope("multi-b-0", 0), envelope("multi-b-1", 1), envelope("multi-b-2", 2))
            ))
            migrated <- Abort.run[JournalError](EventLog.migrate(source, target, Chunk(streamA, streamB)))
            targetA  <- Abort.run[JournalError](target.read(streamA, Event.StreamOffset.first, 10))
            targetB  <- Abort.run[JournalError](target.read(streamB, Event.StreamOffset.first, 10))
        yield
            assert(migrated == Result.succeed(EventLog.MigrationReport(Chunk(
                EventLog.MigrationReport.StreamSummary(streamA, 2L, Present(offset(1))),
                EventLog.MigrationReport.StreamSummary(streamB, 3L, Present(offset(2)))
            ))))
            targetA match
                case Result.Success(events) =>
                    assert(events.length == 2)
                    assert(events(0).id == valid(Event.Id("multi-a-0")))
                    assert(events(1).id == valid(Event.Id("multi-a-1")))
                case other => fail(s"expected success, got: $other")
            end match
            targetB match
                case Result.Success(events) =>
                    assert(events.length == 3)
                    assert(events(0).id == valid(Event.Id("multi-b-0")))
                    assert(events(1).id == valid(Event.Id("multi-b-1")))
                    assert(events(2).id == valid(Event.Id("multi-b-2")))
                case other => fail(s"expected success, got: $other")
            end match
        end for
    }

end EventLogMigrationTest
