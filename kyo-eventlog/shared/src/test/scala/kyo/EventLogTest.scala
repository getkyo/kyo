package kyo

import kyo.AllowUnsafe.embrace.danger

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

    private val streamId  = valid(Event.StreamId("log-stream-1"))
    private val eventId   = valid(Event.Id("event-a"))
    private val eventType = valid(Event.Type("LogRecord"))

    private def freshJournalId(suffix: String)(using Frame): JournalId =
        JournalId.validate(s"log-test-$suffix").getOrElse(throw new AssertionError("valid journal id"))

    private def validStreamName(value: String): Event.StreamName =
        Abort.run[EventLog.PreparationFailure](Event.StreamName(value)).eval match
            case Result.Success(name) => name
            case other                => throw new AssertionError(s"expected a valid stream name, got: $other")

    // Wraps a backend, counting every append call, so a batch's grouping into contiguous
    // same-stream runs can be asserted by call count rather than only by final stream state.
    private def countingBackend(inner: Journal.Backend[Sync], counter: AtomicInt)(using Frame): Journal.Backend[Sync] =
        new Journal.Backend[Sync]:
            def append(streamId: Event.StreamId, expected: ExpectedOffset, events: Chunk[Event.Pending])
                : AppendResult < (Sync & Abort[JournalAppendFailure]) =
                counter.incrementAndGet.andThen(inner.append(streamId, expected, events))
            def read(
                streamId: Event.StreamId,
                from: Event.StreamOffset,
                maxCount: Int
            ): Chunk[Event.Committed] < (Sync & Abort[JournalReadFailure]) =
                inner.read(streamId, from, maxCount)
            def streamInfo(streamId: Event.StreamId): StreamInfo < (Sync & Abort[JournalStreamInfoFailure]) =
                inner.streamInfo(streamId)

    private val testStreamId: Event.StreamId         = valid(Event.StreamId("log-test-stream"))
    private val questStartedStreamId: Event.StreamId = valid(Event.StreamId("quest-started-stream"))
    private val partyJoinedStreamId: Event.StreamId  = valid(Event.StreamId("party-joined-stream"))
    private val colorEventStreamId: Event.StreamId   = valid(Event.StreamId("color-event-stream"))
    private val shapeEventStreamId: Event.StreamId   = valid(Event.StreamId("shape-event-stream"))

    private val testStream: Event.StreamSelector[LogTestEvent] = Event.StreamSelector.constant(testStreamId)

    private given Event.Definition[LogTestEvent, LogTestEvent] =
        Event.Definition.schema[LogTestEvent, LogTestEvent](testStream)

    // QuestStarted and PartyJoined route to two distinct streams so a heterogeneous batch exercises
    // real per-member routing instead of one shared stream.
    private given Event.Definition[QuestEvent, QuestStarted] =
        Event.Definition.schema[QuestEvent, QuestStarted](Event.StreamSelector.constant(questStartedStreamId))
    private given Event.Definition[QuestEvent, PartyJoined] =
        Event.Definition.schema[QuestEvent, PartyJoined](Event.StreamSelector.constant(partyJoinedStreamId))

    private given Event.Definition[ColorEvent, ColorEvent.Red] =
        Event.Definition.schema[ColorEvent, ColorEvent.Red](Event.StreamSelector.constant(colorEventStreamId))
    private given Event.Definition[ColorEvent, ColorEvent.Green] =
        Event.Definition.schema[ColorEvent, ColorEvent.Green](Event.StreamSelector.constant(colorEventStreamId))

    private given Event.Definition[ShapeEvent, ShapeEvent.Circle] =
        Event.Definition.schema[ShapeEvent, ShapeEvent.Circle](Event.StreamSelector.constant(shapeEventStreamId))
    private given Event.Definition[ShapeEvent, ShapeEvent.Square] =
        Event.Definition.schema[ShapeEvent, ShapeEvent.Square](Event.StreamSelector.constant(shapeEventStreamId))

    "EventLog operations expose Journal capability and per-op Abort rows" in {
        for
            codecs  <- EventLogCodecs.schema[LogTestEvent]()
            log     <- EventLog.init(codecs, freshJournalId("rows"))
            backend <- Journal.Backend.inMemory
            // Compile-time row checks: each operation carries its own effect row.
            _: (AppendResult < (Journal & Sync & Abort[EventLog.PreparationFailure] & Abort[JournalAppendFailure])) =
                log.append(LogTestEvent("x", 1))
            _: (Chunk[Event.Record[LogTestEvent]] < (Journal & Abort[JournalReadFailure])) =
                log.read(streamId, Event.StreamOffset.first, 10)
            result <- Abort.run[JournalStreamInfoFailure](Journal.run(backend)(Journal.streamInfo(streamId)))
        yield assert(result == Result.succeed(StreamInfo.Absent), s"expected Absent for a new stream, got $result")
    }

    "read returns an empty chunk when no events have been written to the stream" in {
        for
            codecs  <- EventLogCodecs.schema[LogTestEvent]()
            log     <- EventLog.init(codecs, freshJournalId("empty"))
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalReadFailure] {
                Journal.run(backend)(log.read(streamId, Event.StreamOffset.first, 10))
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
                        events <- log.read(sid, Event.StreamOffset.first, 10)
                    yield events
                }
            }
        yield result match
            case Result.Success(events) =>
                assert(events.size == 1)
                assert(events(0).payload == event)
                assert(events(0).eventType.value == schemaName)
                assert(events(0).eventId.value.nonEmpty)
                assert(events(0).ref == JournalEntryRef(journalId, sid, Event.StreamOffset.first))
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
                        events <- log.read(sid, Event.StreamOffset.first, 10)
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
                        events <- log.read(sid, Event.StreamOffset.first, 10)
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
        val sid       = valid(Event.StreamId(journalId.value))
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
                            Chunk(Event.Pending(eventId, eventType, garbage, Event.Metadata.empty))
                        )
                        events <- log.read(sid, Event.StreamOffset.first, 10)
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

    "Event.Record carries all fields from the recorded event plus the decoded payload" in {
        val meta      = Event.Metadata.empty
        val event     = LogTestEvent("startup", 0)
        val journalId = freshJournalId("record-fields")
        val ref       = JournalEntryRef(journalId, streamId, Event.StreamOffset.first)
        val record = Event.Record(
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
                        events <- log.read(sid, Event.StreamOffset.first, 10)
                    yield (r, events)
                }
            }
        yield result match
            case Result.Success((r, events)) =>
                assert(r.firstOffset == Event.StreamOffset.first)
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
                        events <- log.read(sid, Event.StreamOffset.first, 10)
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
                        events <- log.read(sid, Event.StreamOffset.first, 10)
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
        val sid       = valid(Event.StreamId(journalId.value))
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
                    records    <- logReader.read(sid, Event.StreamOffset.first, 10)
                yield records
            }
        yield
            assert(records.size == 2, s"expected 2 committed records, got: $records")
            assert(records.map(_.payload) == Chunk(event1, event2))
            assert(appendErrors.nonEmpty, "expected reader.append to not resolve (no append member on EventLog.Reader)")
        end for
    }

    "Event.Definition carries no codec slot (positive guard, now that EventPayloadCodec/EventMetadataCodec are deleted)" in {
        val errors = external.EventLogCodecsAccessibilityFixture.eventDefinitionErrorMessages
        assert(errors.nonEmpty)
        assert(errors.exists(_.contains("Definition")))
    }

    // --- real StreamSelector / Event.IdPolicy / Metadata resolution --------------------------------

    "StreamSelector.constant resolves to the fixed Event.StreamId regardless of event content" in {
        val fixed    = valid(Event.StreamId("constant-target"))
        val selector = Event.StreamSelector.constant[LogTestEvent](fixed)
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
            name <- Event.StreamName("quest")
            resolved <- Abort.run[EventLog.PreparationFailure](
                Event.StreamSelector.by[QuestStarted](name)(e => Chunk(e.id)).resolve(QuestStarted("q-1"))
            )
            emptyKeyResult <- Abort.run[EventLog.PreparationFailure](
                Event.StreamSelector.by[QuestStarted](name)(_ => Chunk.empty).resolve(QuestStarted("q-1"))
            )
        yield
            assert(resolved == Result.succeed(valid(Event.StreamId("5:quest/3:q-1"))))
            assert(emptyKeyResult match
                case Result.Failure(_: EventLog.PreparationFailure) => true
                case _                                              => false)
    }

    "StreamSelector.canonical resolves name+StreamKey into the same canonical form as by" in {
        val key = Event.StreamKey[QuestStarted](e => Chunk(e.id))
        for
            name <- Event.StreamName("quest")
            byResolved <- Abort.run[EventLog.PreparationFailure](
                Event.StreamSelector.by[QuestStarted](name)(e => Chunk(e.id)).resolve(QuestStarted("q-1"))
            )
            canonicalResolved <- Abort.run[EventLog.PreparationFailure](
                Event.StreamSelector.canonical(name, key).resolve(QuestStarted("q-1"))
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

    "Event.IdPolicy.generated produces distinct monotonic tokens across successive next calls" in {
        val policy = Event.IdPolicy.generated[LogTestEvent]
        val event  = LogTestEvent("gen", 1)
        for
            id1 <- Abort.run[EventLog.PreparationFailure](policy.next(event, testStreamId, eventType, Event.Metadata.empty))
            id2 <- Abort.run[EventLog.PreparationFailure](policy.next(event, testStreamId, eventType, Event.Metadata.empty))
        yield assert(id1 != id2, s"expected two successive generated ids to differ, got $id1 and $id2")
        end for
    }

    "Event.IdPolicy.deterministic produces the same id for equal inputs and repeats for equal f output" in {
        val event = LogTestEvent("det", 1)
        for
            policy <- Event.IdPolicy.deterministic[LogTestEvent]((e, _, _, _) => s"det-${e.toString}")
            id1    <- Abort.run[EventLog.PreparationFailure](policy.next(event, testStreamId, eventType, Event.Metadata.empty))
            id2    <- Abort.run[EventLog.PreparationFailure](policy.next(event, testStreamId, eventType, Event.Metadata.empty))
        yield assert(id1 == id2, s"expected two calls with identical inputs to produce equal ids, got $id1 and $id2")
        end for
    }

    "Event.IdPolicy.callerSupplied propagates a valid id and aborts PreparationFailure on an empty one" in {
        val event = LogTestEvent("caller", 1)
        for
            validPolicy   <- Event.IdPolicy.callerSupplied[LogTestEvent](_ => "caller-id-1")
            validResult   <- Abort.run[EventLog.PreparationFailure](validPolicy.next(event, testStreamId, eventType, Event.Metadata.empty))
            invalidPolicy <- Event.IdPolicy.callerSupplied[LogTestEvent](_ => "")
            invalidResult <-
                Abort.run[EventLog.PreparationFailure](invalidPolicy.next(event, testStreamId, eventType, Event.Metadata.empty))
        yield
            assert(validResult == Result.succeed(valid(Event.Id("caller-id-1"))))
            assert(invalidResult match
                case Result.Failure(_: EventLog.PreparationFailure) => true
                case _                                              => false)
        end for
    }

    "QuestParty union exercises real per-member routing and a non-generated id policy end to end" in {
        val journalId = freshJournalId("quest-party-e2e")
        for
            streamName  <- Event.StreamName("quest")
            questPolicy <- Event.IdPolicy.deterministic[QuestStarted]((e, _, _, _) => e.id)
            partyPolicy <- Event.IdPolicy.deterministic[PartyJoined]((e, _, _, _) => e.id)
            result <-
                given Event.Definition[QuestEvent, QuestStarted] =
                    Event.Definition.schema[QuestEvent, QuestStarted](
                        Event.StreamSelector.canonical(streamName, Event.StreamKey[QuestStarted](e => Chunk(e.id))),
                        questPolicy
                    )
                given Event.Definition[QuestEvent, PartyJoined] =
                    Event.Definition.schema[QuestEvent, PartyJoined](
                        Event.StreamSelector.constant(partyJoinedStreamId),
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
                                questEvents <- log.read(r1.streamId, Event.StreamOffset.first, 10)
                                partyEvents <- log.read(r2.streamId, Event.StreamOffset.first, 10)
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
                assert(questEvents(0).eventId == valid(Event.Id("q-1")))
                assert(partyEvents(0).eventId == valid(Event.Id("p-1")))
                assert(questEvents(0).payload match
                    case q: QuestStarted => q == QuestStarted("q-1")
                    case _               => false)
                assert(partyEvents(0).payload match
                    case p: PartyJoined => p == PartyJoined("p-1", "alice")
                    case _              => false)
            case other => fail(s"expected success, got: $other")
        end for
    }

    "Event.Definition.metadata.values(event) is honored at prepare time (Metadata[E] real case class, not a stubbed marker)" in {
        val sourceKey        = valid(Event.Metadata.Key("source"))
        val expectedMetadata = Event.Metadata(Map(sourceKey -> Event.Metadata.Value(Structure.Value.Str("test-harness"))))
        given Event.Definition[LogTestEvent, LogTestEvent] =
            Event.Definition.schema[LogTestEvent, LogTestEvent](
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

    "EventLog.Metadata.of builds Metadata[E] from const/from AttributeBindings, producing the expected Event.Metadata for a concrete event" in {
        val metadata = EventLog.Metadata.of[QuestStarted](
            Event.Attributes.CorrelationId.const("req-42"),
            Event.Attributes.SourceSystem.from(_.id)
        )
        val result = metadata.values(QuestStarted("q-1"))
        assert(result.get(Event.Attributes.CorrelationId) == Maybe("req-42"))
        assert(result.get(Event.Attributes.SourceSystem) == Maybe("q-1"))
    }

    "AppendDirective.replaceStreamId replaces StreamSelector.resolve outright (probe selector asserts zero calls)" in {
        // Unsafe: AtomicInt.Unsafe.init for a synchronous call counter; StreamSelector.by's
        // components function carries no effect row, so a Sync-based AtomicInt cannot be
        // threaded through it (mirrors the eventIdSeq counter precedent in
        // EventLogSupport.scala).
        val probeCalls = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        val probeStream: Event.StreamSelector[LogTestEvent] =
            Event.StreamSelector.by[LogTestEvent](validStreamName("probe-stream-a")) { _ =>
                discard(probeCalls.incrementAndGet()(using AllowUnsafe.embrace.danger))
                Chunk("probed")
            }
        val overrideStreamId = valid(Event.StreamId("replace-stream-b"))
        given Event.Definition[LogTestEvent, LogTestEvent] =
            Event.Definition.schema[LogTestEvent, LogTestEvent](probeStream)
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

    "AppendDirective.replaceEventId replaces Event.IdPolicy.next outright (probe policy asserts zero calls)" in {
        // Unsafe: AtomicInt.Unsafe.init for a synchronous call counter; Event.IdPolicy.callerSupplied's
        // f carries no effect row, so a Sync-based AtomicInt cannot be threaded through it.
        val probeCalls = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        val overrideId = valid(Event.Id("replace-event-id"))
        val journalId  = freshJournalId("replace-event-id")
        for
            probePolicy <- Event.IdPolicy.callerSupplied[LogTestEvent] { _ =>
                discard(probeCalls.incrementAndGet()(using AllowUnsafe.embrace.danger))
                "probed-id"
            }
            cmd <-
                given Event.Definition[LogTestEvent, LogTestEvent] =
                    Event.Definition.schema[LogTestEvent, LogTestEvent](testStream, probePolicy)
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
        given Event.Definition[LogTestEvent, LogTestEvent] =
            Event.Definition.schema[LogTestEvent, LogTestEvent](
                testStream,
                metadata = EventLog.Metadata.of(Event.Attributes.SourceSystem.const("member-system"))
            )
        val journalId = freshJournalId("with-metadata-merge")
        for
            codecs <- EventLogCodecs.schema[LogTestEvent]()
            log    <- EventLog.init(codecs, journalId)
            additiveCmd <- Abort.run[EventLog.PreparationFailure](
                log.prepare(
                    LogTestEvent("additive", 1),
                    EventLog.AppendDirective.withMetadata(
                        Event.Metadata.of(Event.Attribute(Event.Attributes.CorrelationId, "req-99"))
                    )
                )
            )
            collidingCmd <- Abort.run[EventLog.PreparationFailure](
                log.prepare(
                    LogTestEvent("colliding", 2),
                    EventLog.AppendDirective.withMetadata(
                        Event.Metadata.of(Event.Attribute(Event.Attributes.SourceSystem, "directive-system"))
                    )
                )
            )
        yield
            additiveCmd match
                case Result.Success(command) =>
                    assert(command.envelope.metadata.get(Event.Attributes.SourceSystem) == Maybe("member-system"))
                    assert(command.envelope.metadata.get(Event.Attributes.CorrelationId) == Maybe("req-99"))
                case other => fail(s"expected successful prepare, got: $other")
            end match
            collidingCmd match
                case Result.Success(command) =>
                    assert(command.envelope.metadata.get(Event.Attributes.SourceSystem) == Maybe("directive-system"))
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
                            EventLog.AppendDirective.expected(ExpectedOffset.Exact(Event.StreamOffset.first))
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
        val streamB   = valid(Event.StreamId("replace-split-stream-b"))
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
                given Event.Definition[QuestEvent, QuestStarted] =
                    Event.Definition.schema[QuestEvent, QuestStarted](
                        Event.StreamSelector.constant(questStartedStreamId),
                        metadata = EventLog.Metadata.of(Event.Attributes.CorrelationId.const("req-1"))
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
                                        Event.Metadata.of(Event.Attribute(Event.Attributes.SourceSystem, "directive-system"))
                                    )
                                )
                                records <- log.read(questStartedStreamId, Event.StreamOffset.first, 10)
                            yield records
                        }
                    }
                yield appended
                end for
        yield result match
            case Result.Success(records) =>
                assert(records.size == 1)
                val metadata = records(0).metadata
                assert(metadata.get(Event.Attributes.CorrelationId) == Maybe("req-1"))
                assert(metadata.get(Event.Attributes.SourceSystem) == Maybe("directive-system"))
            case other => fail(s"expected success, got: $other")
        end for
    }

    // Unsafe: JVM-only self-audits of the shipped guides, sources, and campaign diff on disk (a
    // dev-time grep, not a runtime capability); mirrors JournalEventTest and DemoValidationTest's
    // repo-root walk, recursive file listing, and bare-token helpers. These leaves are `.onlyJvm`
    // because `user.dir`, the synchronous file reads, and the `git` invocation are JVM-only.
    private def guardRepoRoot(): Path =
        @scala.annotation.tailrec
        def loop(dir: Path): Path =
            if (dir / "build.sbt").unsafe.exists() then dir
            else
                dir.parent match
                    case Maybe.Present(parent) => loop(parent)
                    case Maybe.Absent          => throw new RuntimeException("repo root with build.sbt not found")
        loop(Path(java.lang.System.getProperty("user.dir").nn))
    end guardRepoRoot

    private def guardFilesUnder(dir: Path): List[Path] =
        if !dir.unsafe.exists() then List.empty
        else
            dir.unsafe.list().getOrThrow.toList.flatMap { entry =>
                if entry.unsafe.isDirectory() && !entry.unsafe.isSymbolicLink() then guardFilesUnder(entry)
                else List(entry)
            }

    private def guardRead(path: Path): String = path.unsafe.read().getOrThrow

    // A whole-word token scan: the token is not immediately preceded by '.' or a word char (so
    // `Event.StreamId` never trips a bare `StreamId` ban and `FileJournal.Configuration` never
    // trips a `FileJournal.Config` ban) and not immediately followed by a word char.
    private def guardBannedHits(text: String, token: String): List[String] =
        val escaped = java.util.regex.Pattern.quote(token)
        val pattern = s"(?<![.\\w])$escaped(?![\\w])".r
        text.linesIterator.filter(line => pattern.findFirstIn(line).isDefined).toList
    end guardBannedHits

    // Lines that are not `//`, `*`, or `/*` comment/scaladoc lines: a JVM file-API token that
    // appears only in prose (describing what jvm-native uses, or what is NOT referenced) is not a
    // portability violation, only an actual code reference is.
    private def guardCodeLines(lines: List[String]): List[String] =
        lines.filter { line =>
            val t = line.trim
            !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*"))
        }

    "eventlog guide and shipped-surface guards" - {

        "final eventlog guides carry the r6 names and no superseded name or reader-repair claim".onlyJvm in {
            val root         = guardRepoRoot()
            val contributing = guardRead(root / "kyo-eventlog" / "CONTRIBUTING.md")
            val readme       = guardRead(root / "kyo-eventlog" / "README.md")
            val combined     = contributing + "\n" + readme

            val requiredNames =
                List("EventLog.Codecs", "Event.Definition", "FileJournal.Configuration", "FileJournal.Options", "Journal.run(backend)")
            requiredNames.foreach(name => assert(combined.contains(name), s"the r6 name '$name' is missing from the eventlog guides"))
            assert(combined.contains("committed frontier"), "the committed-reader rule is missing from the eventlog guides")
            assert(
                contributing.contains("never mutates or truncates"),
                "CONTRIBUTING.md must state the SWMR reader never mutates or truncates"
            )

            val supersededNames =
                List("EventPayloadCodec", "FileJournal.Config", "SegmentFormat", "EventLog.Member", "EventLog.from")
            List("CONTRIBUTING.md" -> contributing, "README.md" -> readme).foreach { case (doc, text) =>
                supersededNames.foreach { name =>
                    val hits = guardBannedHits(text, name)
                    assert(hits.isEmpty, s"$doc carries the superseded name '$name': $hits")
                }
                assert(!text.contains("nominally read-only"), s"$doc carries the removed reader-truncation claim")
                assert(!text.contains("read-only call can perform"), s"$doc carries the removed reader-truncation claim")
            }
        }

        "attempting browser persistence fails loud by design: no browser-persistence or JVM file APIs in shared/JS-Wasm main".onlyJvm in {
            val root = guardRepoRoot()
            val trees = List(
                root / "kyo-eventlog" / "shared" / "src" / "main",
                root / "kyo-eventlog" / "js-wasm" / "src" / "main"
            )
            val allLines = trees.flatMap(guardFilesUnder)
                .filter(_.toString.endsWith(".scala"))
                .flatMap(p => guardRead(p).linesIterator.toList.map(line => p.toString -> line))

            val browserSymbols = List("indexeddb", "opfs", "navigator.storage")
            val browserHits = allLines.filter { case (_, line) =>
                val low = line.toLowerCase
                browserSymbols.exists(low.contains)
            }
            assert(browserHits.isEmpty, s"browser-persistence symbol in shared/JS-Wasm main: ${browserHits.take(10)}")

            val jvmFileApis = List("java.nio.file", "java.io.File", "RandomAccessFile", "FileChannel", "java.util.zip", "java.nio.channels")
            val codeHits = allLines
                .filter { case (_, line) => guardCodeLines(List(line)).nonEmpty && jvmFileApis.exists(line.contains) }
            assert(codeHits.isEmpty, s"JVM file API in shared/JS-Wasm main code: ${codeHits.take(10)}")
        }

        "no process leakage or local skill residuals in the shipped surface".onlyJvm in {
            val root  = guardRepoRoot()
            val roots = List("kyo-eventlog", "kyo-website", "kyo-system", "kyo-examples")
            // Tokens assembled from fragments so this guard file, which is itself under a scanned
            // root, is never flagged by the scan it runs.
            val residuals = List(
                "." + "agents",
                "." + "agent/",
                "readme" + "-skill",
                "dev" + "-state",
                "fresh" + "-eyes",
                "gate" + "-design",
                "ship" + "-scope"
            )
            val files = roots.flatMap(r => guardFilesUnder(root / r))
                .filter(p => p.toString.endsWith(".scala") || p.toString.endsWith(".md"))
                .filterNot(p => p.toString.contains("/.dev/") || p.toString.contains("/target/") || p.toString.contains("/node_modules/"))
            val hits = files.flatMap { p =>
                val text = guardRead(p)
                residuals.filter(text.contains).map(token => s"${p.toString} [$token]")
            }
            assert(hits.isEmpty, s"process/skill residual in shipped surface: ${hits.take(10)}")
        }

        "no em or en dashes, attribution trailers, or weakened-suite deferral framing on campaign-added shipped lines".onlyJvm in {
            val root = guardRepoRoot()
            // Rename-aware diff against the campaign base scoped to the shipped roots plus the sole
            // rename-origin root (kyo-core): the kyo-system Command/Process sources are renames from
            // kyo-core, so keeping kyo-core in scope pairs them by rename detection and never counts
            // their relocated pre-existing lines as added. Scoping to a shipped root alone would drop
            // the kyo-core origin and false-positive on relocated project content; a whole-tree diff
            // is correct but too slow. Only lines under the shipped roots are collected below.
            // `textWithExitCode` drains stdout and stderr concurrently, so the large diff does not
            // deadlock the process pipe the way `text` (single-drain) does.
            Command("git", "merge-base", "HEAD", "main").cwd(root).textWithExitCode.flatMap { case (mergeBaseRaw, _) =>
                val mergeBase = mergeBaseRaw.trim
                Command(
                    "git",
                    "diff",
                    "-M",
                    "--find-renames",
                    s"$mergeBase..HEAD",
                    "--",
                    "kyo-core",
                    "kyo-eventlog",
                    "kyo-website",
                    "kyo-system",
                    "kyo-examples",
                    "build.sbt"
                ).cwd(root).textWithExitCode.map { case (diff, _) =>
                    val shippedRoots = List("kyo-eventlog/", "kyo-website/", "kyo-system/", "kyo-examples/")
                    def shipped(f: String): Boolean =
                        (f == "build.sbt" || shippedRoots.exists(f.startsWith)) && !f.startsWith(".dev/")

                    val (_, revAdded) =
                        diff.linesIterator.foldLeft((Maybe.Absent: Maybe[String], List.empty[String])) { case ((cur, acc), line) =>
                            if line.startsWith("+++ b/") then (Maybe(line.substring(6)), acc)
                            else if line.startsWith("+++ ") then (Maybe.Absent, acc)
                            else if line.startsWith("+") then
                                cur match
                                    case Maybe.Present(f) if shipped(f) => (cur, line.substring(1) :: acc)
                                    case _                              => (cur, acc)
                            else (cur, acc)
                        }
                    val added = revAdded.reverse

                    // Tokens assembled from code points and fragments so this guard file, which is
                    // itself in the diff, is never flagged by the scan it runs.
                    val emDash             = 0x2014.toChar.toString
                    val enDash             = 0x2013.toChar.toString
                    val attributionTrailer = List("Co", "Authored", "By").mkString("-")
                    val deferralPhrases = List(
                        List("known", "issue:"),
                        List("known", "limitation"),
                        List("good", "enough", "for", "now"),
                        List("out", "of", "scope"),
                        List("requires", "deeper", "investigation"),
                        List("documented", "as", "a", "known"),
                        List("for", "follow-up")
                    ).map(_.mkString(" "))

                    val violations = added.filter { line =>
                        line.contains(emDash) ||
                        line.contains(enDash) ||
                        line.contains(attributionTrailer) || {
                            val low = line.toLowerCase
                            deferralPhrases.exists(low.contains)
                        }
                    }
                    assert(violations.isEmpty, s"campaign-added shipped line carries a banned tell: ${violations.take(10)}")
                }
            }
        }
    }

end EventLogTest
