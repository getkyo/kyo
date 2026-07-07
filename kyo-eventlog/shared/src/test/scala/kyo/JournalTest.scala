package kyo

class JournalTest extends kyo.test.Test[Any]:

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
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
        offset = StreamOffset.first,
        eventId = eventId,
        eventType = eventType,
        payload = envelope.payload,
        metadata = envelope.metadata
    )
    private val expectedInfo = StreamInfo.Existing(StreamVersion.after(StreamOffset.first), StreamOffset.first)
    private val appendResult = AppendResult(streamId, StreamOffset.first, StreamOffset.first, expectedInfo)

    "operations expose the Journal capability and per-op Abort in the row" in {
        val _: AppendResult < (Journal & Abort[JournalAppendFailure]) =
            Journal.append(streamId, ExpectedOffset.NoStream, Chunk(envelope))
        val _: Chunk[RecordedEvent] < (Journal & Abort[JournalReadFailure]) =
            Journal.read(streamId, StreamOffset.first, 10)
        val _: StreamInfo < (Journal & Abort[JournalStreamInfoFailure]) =
            Journal.streamInfo(streamId)
        succeed("type ascriptions verify the Journal operation rows")
    }

    "run residual is A < (S & S2) with the umbrella absent" in {
        for
            b <- Journal.Backend.inMemory
        yield
            val P1: AppendResult < (Journal & Abort[JournalAppendFailure]) =
                Journal.append(streamId, ExpectedOffset.NoStream, Chunk(envelope))
            val _: AppendResult < (Sync & Abort[JournalAppendFailure]) =
                Journal.run(b)(P1)

            val P2: StreamInfo < (Journal & Abort[JournalAppendFailure] & Abort[JournalReadFailure] & Abort[
                JournalStreamInfoFailure
            ] & Abort[String]) =
                for
                    _      <- Journal.append(streamId, ExpectedOffset.NoStream, Chunk(envelope))
                    _      <- Journal.read(streamId, StreamOffset.first, 10)
                    info   <- Journal.streamInfo(streamId)
                    result <- Abort.get[String](Result.succeed(info))
                yield result
            val _: StreamInfo < (Sync & Abort[JournalAppendFailure] & Abort[JournalReadFailure] & Abort[JournalStreamInfoFailure] & Abort[
                String
            ]) =
                Journal.run(b)(P2)

            discard(b)
            succeed("type ascriptions verify the run residual shape")
    }

    "run delegates append, read, and streamInfo to the backend in call order" in {
        val backend = new RecordingBackend

        val program =
            for
                appended <- Journal.append(streamId, ExpectedOffset.NoStream, Chunk(envelope))
                events   <- Journal.read(streamId, StreamOffset.first, 10)
                info     <- Journal.streamInfo(streamId)
            yield (appended, events, info)

        Journal.run(backend)(program).map { (appended, events, info) =>
            assert(appended == appendResult)
            assert(events == Chunk(recorded))
            assert(info == expectedInfo)
            assert(backend.calls == Chunk(
                Call.Append(streamId, ExpectedOffset.NoStream, Chunk(envelope)),
                Call.Read(streamId, StreamOffset.first, 10),
                Call.Info(streamId)
            ))
        }
    }

    "run preserves backend aborts" in {
        val backend = new FailingBackend(appendError = JournalEmptyAppendError())
        Abort.run[JournalError] {
            Journal.run(backend)(Journal.append(streamId, ExpectedOffset.NoStream, Chunk.empty))
        }.map(result => assert(result == Result.fail(JournalEmptyAppendError())))
    }

    "run preserves backend read aborts" in {
        val backend = new FailingBackend(
            readError = JournalStorageError("test read failure", Maybe.empty)
        )
        Abort.run[JournalError] {
            Journal.run(backend)(Journal.read(streamId, StreamOffset.first, 10))
        }.map(result => assert(result == Result.fail(JournalStorageError("test read failure", Maybe.empty))))
    }

    "run preserves backend streamInfo aborts" in {
        val backend = new FailingBackend(
            streamInfoError = JournalStorageError("test streamInfo failure", Maybe.empty)
        )
        Abort.run[JournalError] {
            Journal.run(backend)(Journal.streamInfo(streamId))
        }.map(result => assert(result == Result.fail(JournalStorageError("test streamInfo failure", Maybe.empty))))
    }

    "Backend.inMemory appends and reads through the capability" in {
        for
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalError] {
                Journal.run(backend) {
                    for
                        appended <- Journal.append(streamId, ExpectedOffset.NoStream, Chunk(envelope))
                        events   <- Journal.read(streamId, StreamOffset.first, 10)
                        info     <- Journal.streamInfo(streamId)
                    yield (appended, events, info)
                }
            }
        yield result match
            case Result.Success((appended, events, info)) =>
                assert(appended == appendResult)
                assert(events.length == 1)
                assert(events(0).streamId == streamId)
                assert(events(0).offset == StreamOffset.first)
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
            _      <- Abort.run[JournalError](first.append(streamId, ExpectedOffset.NoStream, Chunk(envelope)))
            info   <- Abort.run[JournalError](second.streamInfo(streamId))
        yield assert(info == Result.succeed(StreamInfo.Absent))
    }

    "Journal.Unsafe.append produces the same result as Journal.run + append" in {
        for
            b  <- Journal.Backend.inMemory
            b2 <- Journal.Backend.inMemory
            unsafeResult <- Abort.run[JournalError](
                Journal.Unsafe.append(b)(streamId, ExpectedOffset.NoStream, Chunk(envelope))(using AllowUnsafe.embrace.danger)
            )
            runResult <- Abort.run[JournalError](
                Journal.run(b2)(Journal.append(streamId, ExpectedOffset.NoStream, Chunk(envelope)))
            )
        yield
            assert(unsafeResult.map(_.firstOffset) == Result.succeed(StreamOffset.first))
            assert(unsafeResult.map(_.lastOffset) == Result.succeed(StreamOffset.first))
            assert(unsafeResult.map(_.firstOffset) == runResult.map(_.firstOffset))
            assert(unsafeResult.map(_.lastOffset) == runResult.map(_.lastOffset))
    }

    "Journal.Unsafe ops ascribe to per-op Abort rows" in {
        for
            b <- Journal.Backend.inMemory
        yield
            val _: AppendResult < (Sync & Abort[JournalAppendFailure]) =
                Journal.Unsafe.append(b)(streamId, ExpectedOffset.NoStream, Chunk(envelope))(using AllowUnsafe.embrace.danger)
            val _: Chunk[RecordedEvent] < (Sync & Abort[JournalReadFailure]) =
                Journal.Unsafe.read(b)(streamId, StreamOffset.first, 10)(using AllowUnsafe.embrace.danger)
            val _: StreamInfo < (Sync & Abort[JournalStreamInfoFailure]) =
                Journal.Unsafe.streamInfo(b)(streamId)(using AllowUnsafe.embrace.danger)
            succeed("type ascriptions verify the Unsafe per-op rows")
    }

    "Abort.run[JournalAppendFailure] recovers a Conflict inside Journal.run" in {
        for
            b <- Journal.Backend.inMemory
            _ <- Abort.run[JournalError](Journal.run(b)(Journal.append(streamId, ExpectedOffset.NoStream, Chunk(envelope))))
            wrongOffset = StreamOffset(999L).getOrElse(throw new AssertionError("valid offset"))
            result <- Journal.run(b) {
                Abort.run[JournalAppendFailure](
                    Journal.append(streamId, ExpectedOffset.Exact(wrongOffset), Chunk(envelope))
                ).map {
                    case Result.Failure(_: JournalConflictError) => appendResult
                    case Result.Success(r)                       => r
                }
            }
        yield assert(result == appendResult)
    }

    "Retry[JournalAppendFailure](Journal.append(...)) compiles and introduces Async" in {
        for
            b <- Journal.Backend.inMemory
        yield
            val _: AppendResult < (Async & Abort[JournalAppendFailure] & Journal) =
                Retry[JournalAppendFailure](Journal.append(streamId, ExpectedOffset.NoStream, Chunk(envelope)))
            val _: AppendResult < (Sync & Async & Abort[JournalAppendFailure]) =
                Journal.run(b)(Retry[JournalAppendFailure](Journal.append(streamId, ExpectedOffset.NoStream, Chunk(envelope))))
            succeed("type ascriptions verify Retry expressibility")
    }

    private enum Call derives CanEqual:
        case Append(streamId: StreamId, expected: ExpectedOffset, events: Chunk[EventEnvelope])
        case Read(streamId: StreamId, from: StreamOffset, maxCount: Int)
        case Info(streamId: StreamId)
    end Call

    final private class RecordingBackend extends Journal.Backend[Sync]:
        private var recordedCalls: Chunk[Call] = Chunk.empty

        def calls: Chunk[Call] = recordedCalls

        def append(streamId: StreamId, expected: ExpectedOffset, events: Chunk[EventEnvelope])
            : AppendResult < (Sync & Abort[JournalAppendFailure]) =
            Sync.defer {
                recordedCalls = recordedCalls.append(Call.Append(streamId, expected, events))
                appendResult
            }

        def read(streamId: StreamId, from: StreamOffset, maxCount: Int)
            : Chunk[RecordedEvent] < (Sync & Abort[JournalReadFailure]) =
            Sync.defer {
                recordedCalls = recordedCalls.append(Call.Read(streamId, from, maxCount))
                Chunk(recorded)
            }

        def streamInfo(streamId: StreamId): StreamInfo < (Sync & Abort[JournalStreamInfoFailure]) =
            Sync.defer {
                recordedCalls = recordedCalls.append(Call.Info(streamId))
                expectedInfo
            }
    end RecordingBackend

    final private class FailingBackend(
        appendError: JournalAppendFailure = JournalEmptyAppendError(),
        readError: JournalReadFailure = JournalStorageError("test read failure", Maybe.empty),
        streamInfoError: JournalStreamInfoFailure = JournalStorageError("test streamInfo failure", Maybe.empty)
    ) extends Journal.Backend[Sync]:
        def append(streamId: StreamId, expected: ExpectedOffset, events: Chunk[EventEnvelope])
            : AppendResult < (Sync & Abort[JournalAppendFailure]) =
            Abort.fail(appendError)
        def read(streamId: StreamId, from: StreamOffset, maxCount: Int)
            : Chunk[RecordedEvent] < (Sync & Abort[JournalReadFailure]) =
            Abort.fail(readError)
        def streamInfo(streamId: StreamId): StreamInfo < (Sync & Abort[JournalStreamInfoFailure]) =
            Abort.fail(streamInfoError)
    end FailingBackend
end JournalTest
