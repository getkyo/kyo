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

    private def validStreamName(value: String): EventLog.StreamName =
        Abort.run[EventLog.PreparationFailure](EventLog.StreamName(value)).eval match
            case Result.Success(name) => name
            case other                => throw new AssertionError(s"expected a valid stream name, got: $other")

    // Wraps a backend, counting every append call, so a batch's grouping into contiguous
    // same-stream runs can be asserted by call count rather than only by final stream state.
    private def countingBackend(inner: Journal.Backend[Sync], counter: AtomicInt)(using Frame): Journal.Backend[Sync] =
        new Journal.Backend[Sync]:
            def append(streamId: StreamId, expected: ExpectedOffset, events: Chunk[EventEnvelope])
                : AppendResult < (Sync & Abort[JournalAppendFailure]) =
                counter.incrementAndGet.andThen(inner.append(streamId, expected, events))
            def read(streamId: StreamId, from: StreamOffset, maxCount: Int): Chunk[RecordedEvent] < (Sync & Abort[JournalReadFailure]) =
                inner.read(streamId, from, maxCount)
            def streamInfo(streamId: StreamId): StreamInfo < (Sync & Abort[JournalStreamInfoFailure]) =
                inner.streamInfo(streamId)

    private val testStreamId: StreamId         = valid(StreamId("log-test-stream"))
    private val questStartedStreamId: StreamId = valid(StreamId("quest-started-stream"))
    private val partyJoinedStreamId: StreamId  = valid(StreamId("party-joined-stream"))
    private val colorEventStreamId: StreamId   = valid(StreamId("color-event-stream"))
    private val shapeEventStreamId: StreamId   = valid(StreamId("shape-event-stream"))

    private val testStream: EventLog.StreamSelector[LogTestEvent] = EventLog.StreamSelector.constant(testStreamId)

    private given EventLog.EventDefinition[LogTestEvent, LogTestEvent] =
        EventLog.EventDefinition.schema[LogTestEvent, LogTestEvent](testStream)

    // QuestStarted and PartyJoined route to two distinct streams so a heterogeneous batch exercises
    // real per-member routing instead of one shared stream.
    private given EventLog.EventDefinition[QuestEvent, QuestStarted] =
        EventLog.EventDefinition.schema[QuestEvent, QuestStarted](EventLog.StreamSelector.constant(questStartedStreamId))
    private given EventLog.EventDefinition[QuestEvent, PartyJoined] =
        EventLog.EventDefinition.schema[QuestEvent, PartyJoined](EventLog.StreamSelector.constant(partyJoinedStreamId))

    private given EventLog.EventDefinition[ColorEvent, ColorEvent.Red] =
        EventLog.EventDefinition.schema[ColorEvent, ColorEvent.Red](EventLog.StreamSelector.constant(colorEventStreamId))
    private given EventLog.EventDefinition[ColorEvent, ColorEvent.Green] =
        EventLog.EventDefinition.schema[ColorEvent, ColorEvent.Green](EventLog.StreamSelector.constant(colorEventStreamId))

    private given EventLog.EventDefinition[ShapeEvent, ShapeEvent.Circle] =
        EventLog.EventDefinition.schema[ShapeEvent, ShapeEvent.Circle](EventLog.StreamSelector.constant(shapeEventStreamId))
    private given EventLog.EventDefinition[ShapeEvent, ShapeEvent.Square] =
        EventLog.EventDefinition.schema[ShapeEvent, ShapeEvent.Square](EventLog.StreamSelector.constant(shapeEventStreamId))

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
        val sid        = testStreamId
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
        val sid       = testStreamId
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
        val sid       = testStreamId
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
        // traits, FileJournal.segmented), the deleted codec traits (EventPayloadCodec,
        // EventMetadataCodec), and the removed phantom profile apparatus (Profile, ProfileName,
        // the two-parameter Configuration form) are gone, not merely private; every reference
        // below must fail to type-check.
        val errors = scala.compiletime.testing.typeCheckErrors(
            """
            val a: kyo.FileJournal.SegmentedComponents[kyo.FileJournal.Binary] = ???
            val b: kyo.EventPayloadCodec = ???
            val c: kyo.EventMetadataCodec = ???
            val d = kyo.FileJournal.SegmentFormat.Binary
            val e = kyo.FileJournal.segmented
            val f: kyo.FileJournal.Profile = ???
            val g: kyo.FileJournal.ProfileName[kyo.FileJournal.Binary] = ???
            val h: kyo.FileJournal.Configuration[Int, kyo.FileJournal.Binary] = ???
            """
        ).map(_.message)
        assert(errors.nonEmpty)
        assert(errors.exists(_.contains("SegmentedComponents")))
        assert(errors.exists(_.contains("EventPayloadCodec")))
        assert(errors.exists(_.contains("EventMetadataCodec")))
        assert(errors.exists(_.contains("SegmentFormat")))
        assert(errors.exists(_.contains("segmented")))
        assert(errors.exists(_.contains("Profile")))
        assert(errors.exists(_.contains("ProfileName")))
        assert(errors.exists(_.contains("Configuration")))
    }

    // --- plan-mandated acceptance leaves (design/05-plan.yaml tests.leaves) ---------------------

    "union member direct append inside Journal.run" in {
        val journalId = freshJournalId("union")
        val sid       = questStartedStreamId
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
        val sid       = colorEventStreamId
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
        val sid       = shapeEventStreamId
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
        // future Chunk path is evidenced structurally, not by a second call shape. All three
        // commands resolve to the same stream (testStream is a single fixed constant), so they
        // group into one contiguous run and one Journal.append call: the returned Chunk mirrors
        // that one run's single AppendResult back across all three original positions.
        val journalId = freshJournalId("appendall")
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
                assert(results.size == 3)
                assert(results.map(_.firstOffset.value) == Chunk(0L, 0L, 0L))
                assert(results.map(_.lastOffset.value) == Chunk(2L, 2L, 2L))
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
        val sid       = testStreamId
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

    // --- real StreamSelector / EventIdPolicy / Metadata resolution --------------------------------

    "StreamSelector.constant resolves to the fixed StreamId regardless of event content" in {
        val fixed    = valid(StreamId("constant-target"))
        val selector = EventLog.StreamSelector.constant[LogTestEvent](fixed)
        for
            r1 <- Abort.run[EventLog.PreparationFailure](selector.resolve(LogTestEvent("a", 1)))
            r2 <- Abort.run[EventLog.PreparationFailure](selector.resolve(LogTestEvent("b", 2)))
        yield
            assert(r1 == Result.succeed(fixed))
            assert(r2 == Result.succeed(fixed))
        end for
    }

    "StreamSelector.by resolves name+components into the length-prefixed canonical form" in {
        for
            name <- EventLog.StreamName("quest")
            resolved <- Abort.run[EventLog.PreparationFailure](
                EventLog.StreamSelector.by[QuestStarted](name)(e => Chunk(e.id)).resolve(QuestStarted("q-1"))
            )
            emptyKeyResult <- Abort.run[EventLog.PreparationFailure](
                EventLog.StreamSelector.by[QuestStarted](name)(_ => Chunk.empty).resolve(QuestStarted("q-1"))
            )
        yield
            assert(resolved == Result.succeed(valid(StreamId("5:quest/3:q-1"))))
            assert(emptyKeyResult match
                case Result.Failure(_: EventLog.PreparationFailure) => true
                case _                                              => false)
    }

    "StreamSelector.canonical resolves name+StreamKey into the same canonical form as by" in {
        val key = EventLog.StreamKey[QuestStarted](e => Chunk(e.id))
        for
            name <- EventLog.StreamName("quest")
            byResolved <- Abort.run[EventLog.PreparationFailure](
                EventLog.StreamSelector.by[QuestStarted](name)(e => Chunk(e.id)).resolve(QuestStarted("q-1"))
            )
            canonicalResolved <- Abort.run[EventLog.PreparationFailure](
                EventLog.StreamSelector.canonical(name, key).resolve(QuestStarted("q-1"))
            )
        yield assert(byResolved == canonicalResolved)
        end for
    }

    "per-member routing: a heterogeneous QuestStarted/PartyJoined batch routes each member to its own resolved stream, not one journalId-derived stream" in {
        val journalId = freshJournalId("per-member-routing")
        for
            codecs  <- EventLogCodecs.schema[QuestEvent]()
            log     <- EventLog.init(codecs, journalId)
            counter <- AtomicInt.init
            inner   <- Journal.Backend.inMemory
            backend = countingBackend(inner, counter)
            result <- Abort.run[JournalError | EventLog.PreparationFailure] {
                Journal.run(backend) {
                    for
                        c1      <- log.prepare(QuestStarted("q-a"))
                        c2      <- log.prepare(PartyJoined("p-a", "alice"))
                        c3      <- log.prepare(QuestStarted("q-b"))
                        results <- log.appendAll(c1, c2, c3)
                        infoA   <- Journal.streamInfo(questStartedStreamId)
                        infoB   <- Journal.streamInfo(partyJoinedStreamId)
                    yield (results, infoA, infoB)
                }
            }
            calls <- counter.get
        yield result match
            case Result.Success((results, infoA, infoB)) =>
                assert(calls == 3, s"expected one Journal.append call per contiguous same-stream run, got $calls")
                assert(results.size == 3)
                assert(results.map(_.streamId) == Chunk(questStartedStreamId, partyJoinedStreamId, questStartedStreamId))
                infoA match
                    case StreamInfo.Existing(_, last) =>
                        assert(last.value == 1L, s"expected streamA's last offset to be 1, got ${last.value}")
                    case other => fail(s"expected streamA to exist with two committed events, got $other")
                end match
                infoB match
                    case StreamInfo.Existing(_, last) =>
                        assert(last.value == 0L, s"expected streamB's last offset to be 0, got ${last.value}")
                    case other => fail(s"expected streamB to exist with one committed event, got $other")
                end match
            case other => fail(s"expected success, got: $other")
        end for
    }

    "EventIdPolicy.generated produces distinct monotonic tokens across successive next calls" in {
        val policy = EventLog.EventIdPolicy.generated[LogTestEvent]
        val event  = LogTestEvent("gen", 1)
        for
            id1 <- Abort.run[EventLog.PreparationFailure](policy.next(event, testStreamId, eventType, EventMetadata.empty))
            id2 <- Abort.run[EventLog.PreparationFailure](policy.next(event, testStreamId, eventType, EventMetadata.empty))
        yield assert(id1 != id2, s"expected two successive generated ids to differ, got $id1 and $id2")
        end for
    }

    "EventIdPolicy.deterministic produces the same id for equal inputs and repeats for equal f output" in {
        val event = LogTestEvent("det", 1)
        for
            policy <- EventLog.EventIdPolicy.deterministic[LogTestEvent]((e, _, _, _) => s"det-${e.toString}")
            id1    <- Abort.run[EventLog.PreparationFailure](policy.next(event, testStreamId, eventType, EventMetadata.empty))
            id2    <- Abort.run[EventLog.PreparationFailure](policy.next(event, testStreamId, eventType, EventMetadata.empty))
        yield assert(id1 == id2, s"expected two calls with identical inputs to produce equal ids, got $id1 and $id2")
        end for
    }

    "EventIdPolicy.callerSupplied propagates a valid id and aborts PreparationFailure on an empty one" in {
        val event = LogTestEvent("caller", 1)
        for
            validPolicy   <- EventLog.EventIdPolicy.callerSupplied[LogTestEvent](_ => "caller-id-1")
            validResult   <- Abort.run[EventLog.PreparationFailure](validPolicy.next(event, testStreamId, eventType, EventMetadata.empty))
            invalidPolicy <- EventLog.EventIdPolicy.callerSupplied[LogTestEvent](_ => "")
            invalidResult <- Abort.run[EventLog.PreparationFailure](invalidPolicy.next(event, testStreamId, eventType, EventMetadata.empty))
        yield
            assert(validResult == Result.succeed(valid(EventId("caller-id-1"))))
            assert(invalidResult match
                case Result.Failure(_: EventLog.PreparationFailure) => true
                case _                                              => false)
        end for
    }

    "QuestParty union exercises real per-member routing and a non-generated id policy end to end" in {
        val journalId = freshJournalId("quest-party-e2e")
        for
            streamName  <- EventLog.StreamName("quest")
            questPolicy <- EventLog.EventIdPolicy.deterministic[QuestStarted]((e, _, _, _) => e.id)
            partyPolicy <- EventLog.EventIdPolicy.deterministic[PartyJoined]((e, _, _, _) => e.id)
            result <-
                given EventLog.EventDefinition[QuestEvent, QuestStarted] =
                    EventLog.EventDefinition.schema[QuestEvent, QuestStarted](
                        EventLog.StreamSelector.canonical(streamName, EventLog.StreamKey[QuestStarted](e => Chunk(e.id))),
                        questPolicy
                    )
                given EventLog.EventDefinition[QuestEvent, PartyJoined] =
                    EventLog.EventDefinition.schema[QuestEvent, PartyJoined](
                        EventLog.StreamSelector.constant(partyJoinedStreamId),
                        partyPolicy
                    )
                // QuestStarted's single field is a structural subset of PartyJoined's two fields,
                // so the default untagged union schema cannot always tell them apart on decode; an
                // adjacent (tag + content) representation disambiguates by construction.
                given Schema[QuestEvent] = summon[Schema[QuestEvent]].adjacent("type", "content")
                for
                    codecs  <- EventLogCodecs.schema[QuestEvent]()
                    log     <- EventLog.init(codecs, journalId)
                    backend <- Journal.Backend.inMemory
                    appended <- Abort.run[JournalError | EventLog.PreparationFailure] {
                        Journal.run(backend) {
                            for
                                r1          <- log.append(QuestStarted("q-1"))
                                r2          <- log.append(PartyJoined("p-1", "alice"))
                                questEvents <- log.read(r1.streamId, StreamOffset.first, 10)
                                partyEvents <- log.read(r2.streamId, StreamOffset.first, 10)
                            yield (r1, r2, questEvents, partyEvents)
                        }
                    }
                yield appended
                end for
        yield result match
            case Result.Success((r1, r2, questEvents, partyEvents)) =>
                assert(r1.streamId != r2.streamId, "expected QuestStarted and PartyJoined to resolve to distinct streams")
                assert(questEvents.size == 1)
                assert(partyEvents.size == 1)
                assert(questEvents(0).eventId == valid(EventId("q-1")))
                assert(partyEvents(0).eventId == valid(EventId("p-1")))
                assert(questEvents(0).payload match
                    case q: QuestStarted => q == QuestStarted("q-1")
                    case _               => false)
                assert(partyEvents(0).payload match
                    case p: PartyJoined => p == PartyJoined("p-1", "alice")
                    case _              => false)
            case other => fail(s"expected success, got: $other")
        end for
    }

    "EventDefinition.metadata.values(event) is honored at prepare time (Metadata[E] real case class, not a stubbed marker)" in {
        val sourceKey        = valid(MetadataKey("source"))
        val expectedMetadata = EventMetadata(Map(sourceKey -> MetadataValue(Structure.Value.Str("test-harness"))))
        given EventLog.EventDefinition[LogTestEvent, LogTestEvent] =
            EventLog.EventDefinition.schema[LogTestEvent, LogTestEvent](
                testStream,
                metadata = EventLog.Metadata.from(_ => expectedMetadata)
            )
        val journalId = freshJournalId("metadata-values")
        for
            codecs <- EventLogCodecs.schema[LogTestEvent]()
            log    <- EventLog.init(codecs, journalId)
            cmd    <- Abort.run[EventLog.PreparationFailure](log.prepare(LogTestEvent("with-metadata", 1)))
        yield cmd match
            case Result.Success(command) => assert(command.envelope.metadata == expectedMetadata)
            case other                   => fail(s"expected successful prepare, got: $other")
        end for
    }

    // --- typed attribute metadata, AppendDirective replacement overrides, per-command
    // expectedOffset validation ------------------------------------------------------------

    "EventLog.Metadata.of builds Metadata[E] from const/from AttributeBindings, producing the expected EventMetadata for a concrete event" in {
        val metadata = EventLog.Metadata.of[QuestStarted](
            EventLog.Attributes.CorrelationId.const("req-42"),
            EventLog.Attributes.SourceSystem.from(_.id)
        )
        val result = metadata.values(QuestStarted("q-1"))
        assert(result.get(EventLog.Attributes.CorrelationId) == Maybe("req-42"))
        assert(result.get(EventLog.Attributes.SourceSystem) == Maybe("q-1"))
    }

    "AppendDirective.replaceStreamId replaces StreamSelector.resolve outright (probe selector asserts zero calls)" in {
        // Unsafe: AtomicInt.Unsafe.init for a synchronous call counter; StreamSelector.by's
        // components function carries no effect row, so a Sync-based AtomicInt cannot be
        // threaded through it (mirrors the eventIdSeq counter precedent in
        // EventLogSupport.scala).
        val probeCalls = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        val probeStream: EventLog.StreamSelector[LogTestEvent] =
            EventLog.StreamSelector.by[LogTestEvent](validStreamName("probe-stream-a")) { _ =>
                discard(probeCalls.incrementAndGet()(using AllowUnsafe.embrace.danger))
                Chunk("probed")
            }
        val overrideStreamId = valid(StreamId("replace-stream-b"))
        given EventLog.EventDefinition[LogTestEvent, LogTestEvent] =
            EventLog.EventDefinition.schema[LogTestEvent, LogTestEvent](probeStream)
        val journalId = freshJournalId("replace-stream-id")
        for
            codecs <- EventLogCodecs.schema[LogTestEvent]()
            log    <- EventLog.init(codecs, journalId)
            cmd <- Abort.run[EventLog.PreparationFailure](
                log.prepare(LogTestEvent("probe-test", 1), EventLog.AppendDirective.replaceStreamId(overrideStreamId))
            )
        yield cmd match
            case Result.Success(command) =>
                assert(command.streamId == overrideStreamId)
                val calls = probeCalls.get()(using AllowUnsafe.embrace.danger)
                assert(calls == 0, s"expected the probe selector to never be consulted, got $calls calls")
            case other => fail(s"expected successful prepare, got: $other")
        end for
    }

    "AppendDirective.replaceEventId replaces EventIdPolicy.next outright (probe policy asserts zero calls)" in {
        // Unsafe: AtomicInt.Unsafe.init for a synchronous call counter; EventIdPolicy.callerSupplied's
        // f carries no effect row, so a Sync-based AtomicInt cannot be threaded through it.
        val probeCalls = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        val overrideId = valid(EventId("replace-event-id"))
        val journalId  = freshJournalId("replace-event-id")
        for
            probePolicy <- EventLog.EventIdPolicy.callerSupplied[LogTestEvent] { _ =>
                discard(probeCalls.incrementAndGet()(using AllowUnsafe.embrace.danger))
                "probed-id"
            }
            cmd <-
                given EventLog.EventDefinition[LogTestEvent, LogTestEvent] =
                    EventLog.EventDefinition.schema[LogTestEvent, LogTestEvent](testStream, probePolicy)
                for
                    codecs <- EventLogCodecs.schema[LogTestEvent]()
                    log    <- EventLog.init(codecs, journalId)
                    result <- Abort.run[EventLog.PreparationFailure](
                        log.prepare(LogTestEvent("probe-test", 1), EventLog.AppendDirective.replaceEventId(overrideId))
                    )
                yield result
                end for
        yield cmd match
            case Result.Success(command) =>
                assert(command.envelope.id == overrideId)
                val calls = probeCalls.get()(using AllowUnsafe.embrace.danger)
                assert(calls == 0, s"expected the probe policy to never be consulted, got $calls calls")
            case other => fail(s"expected successful prepare, got: $other")
        end for
    }

    "AppendDirective.withMetadata right-biased-merges over member-produced metadata" in {
        given EventLog.EventDefinition[LogTestEvent, LogTestEvent] =
            EventLog.EventDefinition.schema[LogTestEvent, LogTestEvent](
                testStream,
                metadata = EventLog.Metadata.of(EventLog.Attributes.SourceSystem.const("member-system"))
            )
        val journalId = freshJournalId("with-metadata-merge")
        for
            codecs <- EventLogCodecs.schema[LogTestEvent]()
            log    <- EventLog.init(codecs, journalId)
            additiveCmd <- Abort.run[EventLog.PreparationFailure](
                log.prepare(
                    LogTestEvent("additive", 1),
                    EventLog.AppendDirective.withMetadata(
                        EventMetadata.of(EventLog.Attribute(EventLog.Attributes.CorrelationId, "req-99"))
                    )
                )
            )
            collidingCmd <- Abort.run[EventLog.PreparationFailure](
                log.prepare(
                    LogTestEvent("colliding", 2),
                    EventLog.AppendDirective.withMetadata(
                        EventMetadata.of(EventLog.Attribute(EventLog.Attributes.SourceSystem, "directive-system"))
                    )
                )
            )
        yield
            additiveCmd match
                case Result.Success(command) =>
                    assert(command.envelope.metadata.get(EventLog.Attributes.SourceSystem) == Maybe("member-system"))
                    assert(command.envelope.metadata.get(EventLog.Attributes.CorrelationId) == Maybe("req-99"))
                case other => fail(s"expected successful prepare, got: $other")
            end match
            collidingCmd match
                case Result.Success(command) =>
                    assert(command.envelope.metadata.get(EventLog.Attributes.SourceSystem) == Maybe("directive-system"))
                case other => fail(s"expected successful prepare, got: $other")
            end match
        end for
    }

    "appendValidated aborts PreparationFailure when a non-head command in a contiguous run carries a non-absent expectedOffset" in {
        val journalId = freshJournalId("non-head-expected")
        for
            codecs  <- EventLogCodecs.schema[LogTestEvent]()
            log     <- EventLog.init(codecs, journalId)
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalError | EventLog.PreparationFailure] {
                Journal.run(backend) {
                    for
                        c1 <- log.prepare(LogTestEvent("c1", 1))
                        c2 <- log.prepare(
                            LogTestEvent("c2", 2),
                            EventLog.AppendDirective.expected(ExpectedOffset.Exact(StreamOffset.first))
                        )
                        r <- log.appendAll(c1, c2)
                    yield r
                }
            }
        yield
            val isPreparationFailure = result match
                case Result.Failure(_: EventLog.PreparationFailure) => true
                case _                                              => false
            assert(isPreparationFailure, s"expected PreparationFailure for a non-head expectedOffset, got: $result")
        end for
    }

    "appendValidated succeeds and checks OCC against the head's expectedOffset when only the head command carries one" in {
        val journalId = freshJournalId("head-expected-only")
        for
            codecs  <- EventLogCodecs.schema[LogTestEvent]()
            log     <- EventLog.init(codecs, journalId)
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalError | EventLog.PreparationFailure] {
                Journal.run(backend) {
                    for
                        c1   <- log.prepare(LogTestEvent("c1", 1), EventLog.AppendDirective.expected(ExpectedOffset.NoStream))
                        c2   <- log.prepare(LogTestEvent("c2", 2))
                        _    <- log.appendAll(c1, c2)
                        info <- Journal.streamInfo(testStreamId)
                    yield info
                }
            }
        yield result match
            case Result.Success(StreamInfo.Existing(_, lastOffset)) =>
                assert(lastOffset.value == 1L, s"expected last offset 1 for two committed events, got ${lastOffset.value}")
            case other => fail(s"expected success with an existing stream, got: $other")
        end for
    }

    "a replaceStreamId directive mid-batch splits an otherwise-contiguous run into two Journal.append calls" in {
        val journalId = freshJournalId("replace-stream-id-split")
        val streamB   = valid(StreamId("replace-split-stream-b"))
        for
            codecs  <- EventLogCodecs.schema[LogTestEvent]()
            log     <- EventLog.init(codecs, journalId)
            counter <- AtomicInt.init
            inner   <- Journal.Backend.inMemory
            backend = countingBackend(inner, counter)
            result <- Abort.run[JournalError | EventLog.PreparationFailure] {
                Journal.run(backend) {
                    for
                        c1      <- log.prepare(LogTestEvent("c1", 1))
                        c2      <- log.prepare(LogTestEvent("c2", 2), EventLog.AppendDirective.replaceStreamId(streamB))
                        c3      <- log.prepare(LogTestEvent("c3", 3))
                        results <- log.appendAll(c1, c2, c3)
                        infoA   <- Journal.streamInfo(testStreamId)
                        infoB   <- Journal.streamInfo(streamB)
                    yield (results, infoA, infoB)
                }
            }
            calls <- counter.get
        yield result match
            case Result.Success((results, infoA, infoB)) =>
                assert(calls == 3, s"expected three Journal.append calls (one per contiguous run), got $calls")
                assert(results.size == 3)
                infoA match
                    case StreamInfo.Existing(_, last) =>
                        assert(last.value == 1L, s"expected streamA's last offset to be 1, got ${last.value}")
                    case other => fail(s"expected streamA to exist with two committed events, got $other")
                end match
                infoB match
                    case StreamInfo.Existing(_, last) =>
                        assert(last.value == 0L, s"expected streamB's last offset to be 0, got ${last.value}")
                    case other => fail(s"expected streamB to exist with one committed event, got $other")
                end match
            case other => fail(s"expected success, got: $other")
        end for
    }

    "QuestParty command combines a typed Metadata.of attribute with an AppendDirective.withMetadata override, both observed on the persisted record" in {
        val journalId = freshJournalId("quest-party-typed-metadata")
        for
            result <-
                given EventLog.EventDefinition[QuestEvent, QuestStarted] =
                    EventLog.EventDefinition.schema[QuestEvent, QuestStarted](
                        EventLog.StreamSelector.constant(questStartedStreamId),
                        metadata = EventLog.Metadata.of(EventLog.Attributes.CorrelationId.const("req-1"))
                    )
                given Schema[QuestEvent] = summon[Schema[QuestEvent]].adjacent("type", "content")
                for
                    codecs  <- EventLogCodecs.schema[QuestEvent]()
                    log     <- EventLog.init(codecs, journalId)
                    backend <- Journal.Backend.inMemory
                    appended <- Abort.run[JournalError | EventLog.PreparationFailure] {
                        Journal.run(backend) {
                            for
                                _ <- log.append(
                                    QuestStarted("q-typed-metadata"),
                                    EventLog.AppendDirective.withMetadata(
                                        EventMetadata.of(EventLog.Attribute(EventLog.Attributes.SourceSystem, "directive-system"))
                                    )
                                )
                                records <- log.read(questStartedStreamId, StreamOffset.first, 10)
                            yield records
                        }
                    }
                yield appended
                end for
        yield result match
            case Result.Success(records) =>
                assert(records.size == 1)
                val metadata = records(0).metadata
                assert(metadata.get(EventLog.Attributes.CorrelationId) == Maybe("req-1"))
                assert(metadata.get(EventLog.Attributes.SourceSystem) == Maybe("directive-system"))
            case other => fail(s"expected success, got: $other")
        end for
    }

end EventLogTest
