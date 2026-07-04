package kyo

/** Behavioral contract every [[Journal.Backend]] implementation must satisfy: offset assignment, expected-offset checks, batch atomicity, bounded reads, stream inspection, and append serialization.
  *
  * Extend with a factory for the backend under test; every backend must pass unchanged.
  */
abstract class JournalBackendTest(newBackend: => Journal.Backend[Sync] < (Sync & Scope)) extends kyo.test.Test[Any]:

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("expected valid identifier"))

    private val streamId = valid(StreamId("users-1"))
    private val otherId  = valid(StreamId("users-2"))

    private def envelope(n: Int): EventEnvelope =
        EventEnvelope(
            id = valid(EventId(s"event-$n")),
            eventType = valid(EventType("UserRegistered")),
            payload = Span.from(s"""{"n":$n}""".getBytes("UTF-8")),
            metadata = EventMetadata.empty
        )

    private def offset(value: Long): StreamOffset =
        StreamOffset(value).getOrElse(throw new AssertionError("valid offset"))

    "append" - {
        "assigns consecutive zero-based offsets from the first event" in {
            for
                backend <- newBackend
                first   <- Abort.run[JournalError](backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(0), envelope(1))))
                second  <- Abort.run[JournalError](backend.append(streamId, ExpectedOffset.Exact(offset(1)), Chunk(envelope(2))))
            yield
                assert(first.map(_.firstOffset) == Result.succeed(offset(0)))
                assert(first.map(_.lastOffset) == Result.succeed(offset(1)))
                assert(second.map(_.firstOffset) == Result.succeed(offset(2)))
                assert(second.map(_.streamInfo) == Result.succeed(StreamInfo.Existing(3L, offset(2))))
        }

        "NoStream succeeds only when the stream is absent" in {
            for
                backend <- newBackend
                _       <- Abort.run[JournalError](backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(0))))
                again   <- Abort.run[JournalError](backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(1))))
            yield again match
                case Result.Failure(JournalConflictError(sid, expected, actual)) =>
                    assert(sid == streamId)
                    assert(expected == ExpectedOffset.NoStream)
                    assert(actual == StreamInfo.Existing(1L, offset(0)))
                case other =>
                    fail(s"expected Conflict, got: $other")
        }

        "Exact succeeds only on the live last offset" in {
            for
                backend <- newBackend
                _       <- Abort.run[JournalError](backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(0))))
                stale   <- Abort.run[JournalError](backend.append(streamId, ExpectedOffset.Exact(offset(7)), Chunk(envelope(1))))
                live    <- Abort.run[JournalError](backend.append(streamId, ExpectedOffset.Exact(offset(0)), Chunk(envelope(1))))
            yield
                assert(stale == Result.fail(
                    JournalConflictError(streamId, ExpectedOffset.Exact(offset(7)), StreamInfo.Existing(1L, offset(0)))
                ))
                assert(live.map(_.firstOffset) == Result.succeed(offset(1)))
        }

        "Exact on an absent stream conflicts" in {
            for
                backend <- newBackend
                result  <- Abort.run[JournalError](backend.append(streamId, ExpectedOffset.Exact(offset(0)), Chunk(envelope(0))))
            yield assert(result == Result.fail(
                JournalConflictError(streamId, ExpectedOffset.Exact(offset(0)), StreamInfo.Absent)
            ))
        }

        "Any skips the offset check" in {
            for
                backend <- newBackend
                fresh   <- Abort.run[JournalError](backend.append(streamId, ExpectedOffset.Any, Chunk(envelope(0))))
                onTop   <- Abort.run[JournalError](backend.append(streamId, ExpectedOffset.Any, Chunk(envelope(1))))
            yield
                assert(fresh.map(_.firstOffset) == Result.succeed(offset(0)))
                assert(onTop.map(_.firstOffset) == Result.succeed(offset(1)))
                assert(onTop.map(_.streamInfo) == Result.succeed(StreamInfo.Existing(2L, offset(1))))
        }

        "an empty batch fails with EmptyAppend and leaves the stream unchanged" in {
            for
                backend <- newBackend
                result  <- Abort.run[JournalError](backend.append(streamId, ExpectedOffset.Any, Chunk.empty))
                info    <- Abort.run[JournalError](backend.streamInfo(streamId))
            yield
                assert(result == Result.fail(JournalEmptyAppendError()))
                assert(info == Result.succeed(StreamInfo.Absent))
        }

        "a conflicting batch is all-or-nothing: the stream is unchanged" in {
            for
                backend <- newBackend
                _       <- Abort.run[JournalError](backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(0))))
                _       <- Abort.run[JournalError](backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(1), envelope(2))))
                info    <- Abort.run[JournalError](backend.streamInfo(streamId))
                events  <- Abort.run[JournalError](backend.read(streamId, StreamOffset.first, 10))
            yield
                assert(info == Result.succeed(StreamInfo.Existing(1L, offset(0))))
                assert(events.map(_.length) == Result.succeed(1))
        }
    }

    "read" - {
        "a missing stream returns an empty chunk" in {
            for
                backend <- newBackend
                events  <- Abort.run[JournalError](backend.read(streamId, StreamOffset.first, 10))
            yield assert(events == Result.succeed(Chunk.empty[RecordedEvent]))
        }

        "a non-positive maxCount returns an empty chunk" in {
            for
                backend <- newBackend
                _       <- Abort.run[JournalError](backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(0))))
                zero    <- Abort.run[JournalError](backend.read(streamId, StreamOffset.first, 0))
                neg     <- Abort.run[JournalError](backend.read(streamId, StreamOffset.first, -1))
            yield
                assert(zero == Result.succeed(Chunk.empty[RecordedEvent]))
                assert(neg == Result.succeed(Chunk.empty[RecordedEvent]))
        }

        "a from at or past the event count returns an empty chunk" in {
            for
                backend <- newBackend
                _       <- Abort.run[JournalError](backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(0), envelope(1))))
                at      <- Abort.run[JournalError](backend.read(streamId, offset(2), 10))
                past    <- Abort.run[JournalError](backend.read(streamId, offset(9), 10))
            yield
                assert(at == Result.succeed(Chunk.empty[RecordedEvent]))
                assert(past == Result.succeed(Chunk.empty[RecordedEvent]))
        }

        "returns events in offset order from the requested position, bounded by maxCount" in {
            for
                backend <- newBackend
                _ <- Abort.run[JournalError](backend.append(
                    streamId,
                    ExpectedOffset.NoStream,
                    Chunk(envelope(0), envelope(1), envelope(2), envelope(3))
                ))
                slice <- Abort.run[JournalError](backend.read(streamId, offset(1), 2))
            yield slice match
                case Result.Success(events) =>
                    assert(events.length == 2)
                    assert(events(0).offset == offset(1))
                    assert(events(1).offset == offset(2))
                    assert(events(0).eventId == valid(EventId("event-1")))
                    assert(events(1).eventId == valid(EventId("event-2")))
                case other =>
                    fail(s"expected success, got: $other")
        }

        "preserves payload content through append and read" in {
            for
                backend <- newBackend
                _       <- Abort.run[JournalError](backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(0), envelope(1))))
                events  <- Abort.run[JournalError](backend.read(streamId, StreamOffset.first, 10))
            yield events match
                case Result.Success(events) =>
                    assert(events.length == 2)
                    assert(events(0).payload.is(envelope(0).payload))
                    assert(events(1).payload.is(envelope(1).payload))
                    assert(events(0).metadata == EventMetadata.empty)
                case other =>
                    fail(s"expected success, got: $other")
        }

        "streams are independent" in {
            for
                backend <- newBackend
                _       <- Abort.run[JournalError](backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(0))))
                other   <- Abort.run[JournalError](backend.read(otherId, StreamOffset.first, 10))
            yield assert(other == Result.succeed(Chunk.empty[RecordedEvent]))
        }
    }

    "streamInfo" - {
        "reports Absent for a missing stream" in {
            for
                backend <- newBackend
                info    <- Abort.run[JournalError](backend.streamInfo(streamId))
            yield assert(info == Result.succeed(StreamInfo.Absent))
        }

        "reports count and last offset after appends" in {
            for
                backend <- newBackend
                _       <- Abort.run[JournalError](backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(0), envelope(1))))
                info    <- Abort.run[JournalError](backend.streamInfo(streamId))
            yield assert(info == Result.succeed(StreamInfo.Existing(2L, offset(1))))
        }
    }

    "concurrency" - {
        "exactly one of two racing Exact appends wins" in {
            for
                backend <- newBackend
                _       <- Abort.run[JournalError](backend.append(streamId, ExpectedOffset.NoStream, Chunk(envelope(0))))
                latch   <- Latch.init(1)
                fiber1 <- Fiber.initUnscoped(
                    latch.await.andThen(Abort.run[JournalError](
                        backend.append(streamId, ExpectedOffset.Exact(offset(0)), Chunk(envelope(1)))
                    ))
                )
                fiber2 <- Fiber.initUnscoped(
                    latch.await.andThen(Abort.run[JournalError](
                        backend.append(streamId, ExpectedOffset.Exact(offset(0)), Chunk(envelope(2)))
                    ))
                )
                _    <- latch.release
                r1   <- fiber1.get
                r2   <- fiber2.get
                info <- Abort.run[JournalError](backend.streamInfo(streamId))
            yield
                val outcomes = List(r1, r2)
                assert(outcomes.count(_.isSuccess) == 1)
                assert(outcomes.count {
                    case Result.Failure(JournalConflictError(_, _, _)) => true
                    case _                                             => false
                } == 1)
                assert(info == Result.succeed(StreamInfo.Existing(2L, offset(1))))
        }
    }
end JournalBackendTest

/** Runs the backend contract against the in-memory backend. */
class InMemoryJournalBackendTest extends JournalBackendTest(Journal.Backend.inMemory)
