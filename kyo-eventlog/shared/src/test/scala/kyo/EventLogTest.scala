package kyo

final case class LogTestEvent(name: String, value: Int) derives Schema, CanEqual

// Union-member parity fixture.
final case class QuestStarted(id: String) derives Schema, CanEqual
final case class PartyJoined(id: String, member: String) derives Schema, CanEqual
type QuestEvent = QuestStarted | PartyJoined

// Enum-member parity fixture.
enum ColorEvent derives Schema, CanEqual:
    case Red(shade: Int)
    case Green(shade: Int)
end ColorEvent

// Sealed-trait-member parity fixture.
sealed trait ShapeEvent derives Schema, CanEqual
object ShapeEvent:
    final case class Circle(radius: Int) extends ShapeEvent derives Schema, CanEqual
    final case class Square(side: Int)   extends ShapeEvent derives Schema, CanEqual
end ShapeEvent

class EventLogTest extends kyo.test.Test[Any]:

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("expected valid identifier"))

    private val streamId  = valid(StreamId("log-stream-1"))
    private val eventId   = valid(EventId("event-a"))
    private val eventType = valid(EventType("LogRecord"))

    private def freshJournalId(suffix: String)(using Frame): JournalId =
        JournalId.validate(s"log-test-$suffix").getOrElse(throw new AssertionError("valid journal id"))

    private val testStream: EventLog.StreamSelector[LogTestEvent] = new EventLog.StreamSelector[LogTestEvent] {}

    private given EventLog.EventDefinition[LogTestEvent, LogTestEvent] =
        EventLog.EventDefinition.schema[LogTestEvent, LogTestEvent](testStream)

    private given EventLog.EventDefinition[QuestEvent, QuestStarted] =
        EventLog.EventDefinition.schema[QuestEvent, QuestStarted](new EventLog.StreamSelector[QuestStarted] {})
    private given EventLog.EventDefinition[QuestEvent, PartyJoined] =
        EventLog.EventDefinition.schema[QuestEvent, PartyJoined](new EventLog.StreamSelector[PartyJoined] {})

    private given EventLog.EventDefinition[ColorEvent, ColorEvent.Red] =
        EventLog.EventDefinition.schema[ColorEvent, ColorEvent.Red](new EventLog.StreamSelector[ColorEvent.Red] {})
    private given EventLog.EventDefinition[ColorEvent, ColorEvent.Green] =
        EventLog.EventDefinition.schema[ColorEvent, ColorEvent.Green](new EventLog.StreamSelector[ColorEvent.Green] {})

    private given EventLog.EventDefinition[ShapeEvent, ShapeEvent.Circle] =
        EventLog.EventDefinition.schema[ShapeEvent, ShapeEvent.Circle](new EventLog.StreamSelector[ShapeEvent.Circle] {})
    private given EventLog.EventDefinition[ShapeEvent, ShapeEvent.Square] =
        EventLog.EventDefinition.schema[ShapeEvent, ShapeEvent.Square](new EventLog.StreamSelector[ShapeEvent.Square] {})

    "EventLog operations expose Journal capability and per-op Abort rows" in {
        for
            codecs  <- EventLogCodecs.schema[LogTestEvent]()
            log     <- EventLog.init(codecs, freshJournalId("rows"))
            backend <- Journal.Backend.inMemory
            // Compile-time row checks: each operation carries its own effect row.
            _: (AppendResult < (Journal & Sync & Abort[EventLog.PreparationFailure] & Abort[JournalAppendFailure])) =
                log.append(LogTestEvent("x", 1))
            _: (Chunk[EventLog.Record[LogTestEvent]] < (Journal & Abort[JournalReadFailure])) =
                log.read(streamId, StreamOffset.first, 10)
            result <- Abort.run[JournalStreamInfoFailure](Journal.run(backend)(Journal.streamInfo(streamId)))
        yield assert(result == Result.succeed(StreamInfo.Absent), s"expected Absent for a new stream, got $result")
    }

    "read returns an empty chunk when no events have been written to the stream" in {
        for
            codecs  <- EventLogCodecs.schema[LogTestEvent]()
            log     <- EventLog.init(codecs, freshJournalId("empty"))
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalReadFailure] {
                Journal.run(backend)(log.read(streamId, StreamOffset.first, 10))
            }
        yield result match
            case Result.Success(chunk) => assert(chunk.isEmpty)
            case other                 => fail(s"expected empty chunk, got: $other")
    }

    "append then read returns a decoded Record with schema-derived event type and opaque event id" in {
        val schemaName = summon[Schema[LogTestEvent]].structure.name
        val event      = LogTestEvent("alice", 1)
        val journalId  = freshJournalId("append-read")
        val sid        = valid(StreamId(journalId.value))
        for
            codecs  <- EventLogCodecs.schema[LogTestEvent]()
            log     <- EventLog.init(codecs, journalId)
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalError] {
                Journal.run(backend) {
                    for
                        _      <- log.append(event)
                        events <- log.read(sid, StreamOffset.first, 10)
                    yield events
                }
            }
        yield result match
            case Result.Success(events) =>
                assert(events.size == 1)
                assert(events(0).payload == event)
                assert(events(0).eventType.value == schemaName)
                assert(events(0).eventId.value.nonEmpty)
                assert(events(0).ref == JournalEntryRef(journalId, sid, StreamOffset.first))
            case other => fail(s"expected success, got: $other")
        end for
    }

    "second append produces distinct event ids and sequential offsets" in {
        val e1        = LogTestEvent("first", 1)
        val e2        = LogTestEvent("second", 2)
        val journalId = freshJournalId("two-appends")
        val sid       = valid(StreamId(journalId.value))
        for
            codecs  <- EventLogCodecs.schema[LogTestEvent]()
            log     <- EventLog.init(codecs, journalId)
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalError] {
                Journal.run(backend) {
                    for
                        _      <- log.append(e1)
                        _      <- log.append(e2)
                        events <- log.read(sid, StreamOffset.first, 10)
                    yield events
                }
            }
        yield result match
            case Result.Success(events) =>
                assert(events.size == 2)
                assert(events(0).eventId.value.nonEmpty)
                assert(events(1).eventId.value.nonEmpty)
                assert(events(0).eventId.value != events(1).eventId.value)
                assert(events(0).ref.offset.value == 0L)
                assert(events(1).ref.offset.value == 1L)
                assert(events(0).payload == e1)
                assert(events(1).payload == e2)
            case other => fail(s"expected success, got: $other")
        end for
    }

    "append with AppendDirective.expected succeeds when the offset matches" in {
        val e1        = LogTestEvent("first", 1)
        val e2        = LogTestEvent("exact", 2)
        val journalId = freshJournalId("exact-offset")
        val sid       = valid(StreamId(journalId.value))
        for
            codecs  <- EventLogCodecs.schema[LogTestEvent]()
            log     <- EventLog.init(codecs, journalId)
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalError] {
                Journal.run(backend) {
                    for
                        r1     <- log.append(e1)
                        _      <- log.append(e2, EventLog.AppendDirective.expected(ExpectedOffset.Exact(r1.lastOffset)))
                        events <- log.read(sid, StreamOffset.first, 10)
                    yield events
                }
            }
        yield result match
            case Result.Success(events) =>
                assert(events.size == 2)
                assert(events(0).payload == e1)
                assert(events(1).payload == e2)
            case other => fail(s"expected success, got: $other")
        end for
    }

    "decode corruption on EventLog.read folds into JournalCorruptedError, never a codec-specific error (INV-014)" in {
        // Writes deliberately-corrupted payload bytes directly through the raw Journal effect
        // (bypassing EventLogCodecs.encodeValue entirely, so the corruption is genuine and not
        // merely a wrong-type value that happens to decode), then reads it back through
        // EventLog.read and asserts the failure folds into JournalCorruptedError, never a
        // codec-specific error type.
        val journalId = freshJournalId("corrupt")
        val sid       = valid(StreamId(journalId.value))
        val garbage   = Span.from(Array[Byte](0xff.toByte, 0x00.toByte, 0x01.toByte, 0x02.toByte))
        for
            codecs  <- EventLogCodecs.schema[LogTestEvent]()
            log     <- EventLog.init(codecs, journalId)
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalReadFailure] {
                Journal.run(backend) {
                    for
                        _ <- Journal.append(
                            sid,
                            ExpectedOffset.NoStream,
                            Chunk(EventEnvelope(eventId, eventType, garbage, EventMetadata.empty))
                        )
                        events <- log.read(sid, StreamOffset.first, 10)
                    yield events
                }
            }
        yield
            val isCorrupt = result match
                case Result.Failure(_: JournalCorruptedError) => true
                case _                                        => false
            assert(isCorrupt, s"expected JournalCorruptedError, got: $result")
        end for
    }

    "EventLog.Record carries all fields from the recorded event plus the decoded payload" in {
        val meta      = EventMetadata.empty
        val event     = LogTestEvent("startup", 0)
        val journalId = freshJournalId("record-fields")
        val ref       = JournalEntryRef(journalId, streamId, StreamOffset.first)
        val record = EventLog.Record(
            ref = ref,
            eventId = eventId,
            eventType = eventType,
            metadata = meta,
            payload = event
        )
        assert(record.ref == ref)
        assert(record.eventId == eventId)
        assert(record.eventType == eventType)
        assert(record.metadata == meta)
        assert(record.payload == event)
    }

    "apparatus-absent stale-symbol guard: the removed FileJournal component apparatus does not resolve" in {
        // The component-assembly apparatus (SegmentedComponents, the twelve component marker
        // traits, FileJournal.segmented) and the deleted codec traits (EventPayloadCodec,
        // EventMetadataCodec) are gone, not merely private; every reference below must fail to
        // type-check.
        val errors = scala.compiletime.testing.typeCheckErrors(
            """
            val a: kyo.FileJournal.SegmentedComponents[kyo.FileJournal.Binary] = ???
            val b: kyo.EventPayloadCodec = ???
            val c: kyo.EventMetadataCodec = ???
            val d = kyo.FileJournal.SegmentFormat.Binary
            val e = kyo.FileJournal.segmented
            """
        ).map(_.message)
        assert(errors.nonEmpty)
        assert(errors.exists(_.contains("SegmentedComponents")))
        assert(errors.exists(_.contains("EventPayloadCodec")))
        assert(errors.exists(_.contains("EventMetadataCodec")))
        assert(errors.exists(_.contains("SegmentFormat")))
        assert(errors.exists(_.contains("segmented")))
    }

    // --- plan-mandated acceptance leaves (design/05-plan.yaml tests.leaves) ---------------------

    "union member direct append inside Journal.run" in {
        val journalId = freshJournalId("union")
        val sid       = valid(StreamId(journalId.value))
        for
            codecs  <- EventLogCodecs.schema[QuestEvent]()
            log     <- EventLog.init(codecs, journalId)
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalError] {
                Journal.run(backend) {
                    for
                        r      <- log.append(QuestStarted("q1"))
                        events <- log.read(sid, StreamOffset.first, 10)
                    yield (r, events)
                }
            }
        yield result match
            case Result.Success((r, events)) =>
                assert(r.firstOffset == StreamOffset.first)
                assert(events.size == 1)
                assert(events(0).payload match
                    case q: QuestStarted => q == QuestStarted("q1")
                    case _               => false)
            case other => fail(s"expected success, got: $other")
        end for
    }

    "enum member direct append parity" in {
        val journalId = freshJournalId("enum")
        val sid       = valid(StreamId(journalId.value))
        val cases     = Chunk[ColorEvent](ColorEvent.Red(1), ColorEvent.Green(2))
        for
            codecs  <- EventLogCodecs.schema[ColorEvent]()
            log     <- EventLog.init(codecs, journalId)
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalError] {
                Journal.run(backend) {
                    for
                        _      <- log.append(ColorEvent.Red(1): ColorEvent.Red)
                        _      <- log.append(ColorEvent.Green(2): ColorEvent.Green)
                        events <- log.read(sid, StreamOffset.first, 10)
                    yield events
                }
            }
        yield result match
            case Result.Success(events) =>
                assert(events.size == 2)
                assert(events.map(_.payload) == cases)
            case other => fail(s"expected success, got: $other")
        end for
    }

    "sealed-trait member direct append parity" in {
        val journalId = freshJournalId("sealed")
        val sid       = valid(StreamId(journalId.value))
        val cases     = Chunk[ShapeEvent](ShapeEvent.Circle(3), ShapeEvent.Square(4))
        for
            codecs  <- EventLogCodecs.schema[ShapeEvent]()
            log     <- EventLog.init(codecs, journalId)
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalError] {
                Journal.run(backend) {
                    for
                        _      <- log.append(ShapeEvent.Circle(3))
                        _      <- log.append(ShapeEvent.Square(4))
                        events <- log.read(sid, StreamOffset.first, 10)
                    yield events
                }
            }
        yield result match
            case Result.Success(events) =>
                assert(events.size == 2)
                assert(events.map(_.payload) == cases)
            case other => fail(s"expected success, got: $other")
        end for
    }

    "appendAll(first, rest*) varargs is nonempty and shares the Chunk validator" in {
        // The locked surface has exactly one appendAll(first, rest*) implementation (no separate
        // Chunk-taking overload this phase); the single appendValidated helper it shares with a
        // future Chunk path is evidenced structurally, not by a second call shape.
        val journalId = freshJournalId("appendall")
        val sid       = valid(StreamId(journalId.value))
        for
            codecs  <- EventLogCodecs.schema[LogTestEvent]()
            log     <- EventLog.init(codecs, journalId)
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalError] {
                Journal.run(backend) {
                    for
                        c1      <- log.prepare(LogTestEvent("a", 1))
                        c2      <- log.prepare(LogTestEvent("b", 2))
                        c3      <- log.prepare(LogTestEvent("c", 3))
                        results <- log.appendAll(c1, c2, c3)
                    yield results
                }
            }
        yield result match
            case Result.Success(results) =>
                assert(results.map(_.firstOffset.value) == Chunk(0L, 1L, 2L))
            case other => fail(s"expected success, got: $other")
        end for
    }

    "empty varargs batch is unrepresentable (compile-negative)" in {
        val errors = scala.compiletime.testing.typeCheckErrors(
            """
            val log: kyo.EventLog[kyo.LogTestEvent] = ???
            log.appendAll()
            """
        ).map(_.message)
        assert(errors.nonEmpty, "expected log.appendAll() with no arguments to fail to type-check")
    }

    "cross-log command is rejected (compile-negative)" in {
        val errors = scala.compiletime.testing.typeCheckErrors(
            """
            val log1: kyo.EventLog[kyo.LogTestEvent] = ???
            val log2: kyo.EventLog[kyo.LogTestEvent] = ???
            val cmd: log1.Command = ???
            log2.appendAll(cmd)
            """
        ).map(_.message)
        assert(errors.nonEmpty, "expected a Command prepared from log1 to be rejected by log2.appendAll")
    }

    "AppendDirective.expected sets the canonical expected offset and withExpected is absent" in {
        val journalId = freshJournalId("directive")
        val sid       = valid(StreamId(journalId.value))
        for
            codecs  <- EventLogCodecs.schema[LogTestEvent]()
            log     <- EventLog.init(codecs, journalId)
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalError] {
                Journal.run(backend) {
                    for
                        _ <- log.append(LogTestEvent("first", 1))
                        // The stream now exists at last offset 0; a NoStream expectation on the
                        // second append is stale and must conflict, proving AppendDirective.expected
                        // threads through to the enforced ExpectedOffset check.
                        r <- log.append(
                            LogTestEvent("second", 2),
                            EventLog.AppendDirective.expected(ExpectedOffset.NoStream)
                        )
                    yield r
                }
            }
        yield
            val isConflict = result match
                case Result.Failure(_: JournalConflictError) => true
                case _                                       => false
            assert(isConflict, s"expected a stale NoStream directive against a one-event stream to conflict, got: $result")
            val withExpectedErrors = scala.compiletime.testing.typeCheckErrors(
                """
                kyo.EventLog.AppendDirective.withExpected
                """
            ).map(_.message)
            assert(withExpectedErrors.nonEmpty, "expected AppendDirective.withExpected to not resolve")
        end for
    }

    "EventLog captures no backend (source-scan guard)" in {
        val errors = scala.compiletime.testing.typeCheckErrors(
            """
            val log: kyo.EventLog[kyo.LogTestEvent] = ???
            log.backend
            """
        ).map(_.message)
        assert(errors.nonEmpty, "expected EventLog[A] to carry no backend field or accessor")
        assert(errors.exists(_.contains("backend")))
    }

    "EventLog.reader reads committed records and the Reader has no append (compile-negative)" in {
        val appendErrors = scala.compiletime.testing.typeCheckErrors(
            """
            val reader: kyo.EventLog.Reader[kyo.LogTestEvent, kyo.Sync] = ???
            reader.append(???)
            """
        ).map(_.message)
        val event1    = LogTestEvent("r1", 1)
        val event2    = LogTestEvent("r2", 2)
        val journalId = freshJournalId("reader")
        val sid       = valid(StreamId(journalId.value))
        for
            dir <- Abort.run[FileException](Path.run(Path.tempDir("log-test-reader"))).map {
                case Result.Success(d)   => d
                case Result.Failure(err) => throw err
                case panic: Result.Panic => throw panic.exception
            }
            codecs        <- EventLogCodecs.schema[LogTestEvent]()
            configuration <- FileJournal.Binary.configuration(journalId, codecs)
            log           <- EventLog.init(codecs, journalId)
            _ <- Scope.run {
                for
                    backend <- Journal.Backend.file(dir, configuration)
                    _ <- Journal.run(backend) {
                        for
                            _ <- log.append(event1)
                            _ <- log.append(event2)
                        yield ()
                    }
                yield ()
            }
            records <- Scope.run {
                for
                    fileReader <- Journal.Reader.file(dir, configuration)
                    logReader  <- EventLog.reader(fileReader)
                    records    <- logReader.read(sid, StreamOffset.first, 10)
                yield records
            }
        yield
            assert(records.size == 2, s"expected 2 committed records, got: $records")
            assert(records.map(_.payload) == Chunk(event1, event2))
            assert(appendErrors.nonEmpty, "expected reader.append to not resolve (no append member on EventLog.Reader)")
        end for
    }

    "EventLog.EventDefinition carries no codec slot (positive guard, now that EventPayloadCodec/EventMetadataCodec are deleted)" in {
        val errors = external.EventLogCodecsAccessibilityFixture.eventDefinitionErrorMessages
        assert(errors.nonEmpty)
        assert(errors.exists(_.contains("EventDefinition")))
    }

end EventLogTest
