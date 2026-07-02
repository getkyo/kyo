package kyo

class JournalTest extends kyo.test.Test[Any]:

    private def valid[A](r: Result[JournalError.InvalidIdentifier, A]): A =
        r.getOrElse(throw new AssertionError("expected valid identifier"))

    private val streamId  = valid(StreamId("users-1"))
    private val eventId   = valid(EventId("event-1"))
    private val eventType = valid(EventType("UserRegistered"))

    private val envelope = EventEnvelope(
        id = eventId,
        eventType = eventType,
        payload = Span.from("""{"name":"Ada"}""".getBytes("UTF-8")),
        metadata = EventMetadata.empty
    )

    private val recorded = RecordedEvent(
        streamId = streamId,
        revision = StreamRevision.first,
        eventId = eventId,
        eventType = eventType,
        payload = envelope.payload,
        metadata = envelope.metadata
    )
    private val expectedInfo = StreamInfo.Existing(1L, StreamRevision.first)
    private val appendResult = AppendResult(streamId, StreamRevision.first, StreamRevision.first, expectedInfo)

    "operations expose the Journal capability in the row" in {
        val append: AppendResult < Journal =
            Journal.append(streamId, ExpectedRevision.NoStream, Chunk(envelope))
        val read: Chunk[RecordedEvent] < Journal =
            Journal.read(streamId, StreamRevision.first, 10)
        val info: StreamInfo < Journal =
            Journal.streamInfo(streamId)
        // the type ascriptions above are the assertion
        discard(append)
        discard(read)
        discard(info)
        succeed("type ascriptions verify the Journal operation rows")
    }

    "run delegates append, read, and streamInfo to the backend in call order" in {
        val backend = new RecordingBackend

        val program =
            for
                appended <- Journal.append(streamId, ExpectedRevision.NoStream, Chunk(envelope))
                events   <- Journal.read(streamId, StreamRevision.first, 10)
                info     <- Journal.streamInfo(streamId)
            yield (appended, events, info)

        Journal.run(backend)(program).map { (appended, events, info) =>
            assert(appended == appendResult)
            assert(events == Chunk(recorded))
            assert(info == expectedInfo)
            assert(backend.calls == List(
                Call.Append(streamId, ExpectedRevision.NoStream, Chunk(envelope)),
                Call.Read(streamId, StreamRevision.first, 10),
                Call.Info(streamId)
            ))
        }
    }

    "run preserves backend aborts" in {
        val backend = new FailingBackend(JournalError.EmptyAppend)
        Abort.run[JournalError] {
            Journal.run(backend)(Journal.append(streamId, ExpectedRevision.NoStream, Chunk.empty))
        }.map(result => assert(result == Result.fail(JournalError.EmptyAppend)))
    }

    "Backend.inMemory appends and reads through the capability" in {
        for
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalError] {
                Journal.run(backend) {
                    for
                        appended <- Journal.append(streamId, ExpectedRevision.NoStream, Chunk(envelope))
                        events   <- Journal.read(streamId, StreamRevision.first, 10)
                        info     <- Journal.streamInfo(streamId)
                    yield (appended, events, info)
                }
            }
        yield result match
            case Result.Success((appended, events, info)) =>
                assert(appended == appendResult)
                assert(events.length == 1)
                assert(events(0).streamId == streamId)
                assert(events(0).revision == StreamRevision.first)
                assert(events(0).eventId == eventId)
                assert(events(0).eventType == eventType)
                assert(events(0).payload.is(envelope.payload))
                assert(info == expectedInfo)
            case other =>
                fail(s"expected success, got: $other")
    }

    "separate inMemory backends do not share streams" in {
        for
            first  <- Journal.Backend.inMemory
            second <- Journal.Backend.inMemory
            _      <- Abort.run[JournalError](first.append(streamId, ExpectedRevision.NoStream, Chunk(envelope)))
            info   <- Abort.run[JournalError](second.streamInfo(streamId))
        yield assert(info == Result.succeed(StreamInfo.Absent))
    }

    private enum Call derives CanEqual:
        case Append(streamId: StreamId, expected: ExpectedRevision, events: Chunk[EventEnvelope])
        case Read(streamId: StreamId, from: StreamRevision, maxCount: Int)
        case Info(streamId: StreamId)
    end Call

    final private class RecordingBackend extends Journal.Backend:
        private var recordedCalls: List[Call] = Nil

        def calls: List[Call] = recordedCalls.reverse

        def append(streamId: StreamId, expected: ExpectedRevision, events: Chunk[EventEnvelope])
            : AppendResult < (Sync & Abort[JournalError]) =
            Sync.defer {
                recordedCalls = Call.Append(streamId, expected, events) :: recordedCalls
                appendResult
            }

        def read(streamId: StreamId, from: StreamRevision, maxCount: Int)
            : Chunk[RecordedEvent] < (Sync & Abort[JournalError]) =
            Sync.defer {
                recordedCalls = Call.Read(streamId, from, maxCount) :: recordedCalls
                Chunk(recorded)
            }

        def streamInfo(streamId: StreamId): StreamInfo < (Sync & Abort[JournalError]) =
            Sync.defer {
                recordedCalls = Call.Info(streamId) :: recordedCalls
                expectedInfo
            }
    end RecordingBackend

    final private class FailingBackend(error: JournalError) extends Journal.Backend:
        def append(streamId: StreamId, expected: ExpectedRevision, events: Chunk[EventEnvelope])
            : AppendResult < (Sync & Abort[JournalError]) =
            Abort.fail(error)
        def read(streamId: StreamId, from: StreamRevision, maxCount: Int)
            : Chunk[RecordedEvent] < (Sync & Abort[JournalError]) =
            Abort.fail(error)
        def streamInfo(streamId: StreamId): StreamInfo < (Sync & Abort[JournalError]) =
            Abort.fail(error)
    end FailingBackend
end JournalTest
