package kyo

final case class LogTestEvent(name: String, value: Int) derives Schema, CanEqual

class EventLogTest extends kyo.test.Test[Any]:

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("expected valid identifier"))

    private val streamId  = valid(StreamId("log-stream-1"))
    private val eventId   = valid(EventId("event-a"))
    private val eventType = valid(EventType("LogRecord"))

    "EventLog operations expose Journal capability and per-op Abort rows" in {
        import AllowUnsafe.embrace.danger
        val log = EventLog.init[LogTestEvent]
        // Compile-time row checks: each operation carries its own per-op Abort row.
        val _: AppendResult < (Journal & Abort[JournalAppendFailure]) =
            log.append(streamId, ExpectedOffset.NoStream, Chunk(LogTestEvent("x", 1)))
        val _: Chunk[EventLog.Typed[LogTestEvent]] < (Journal & Abort[JournalReadFailure]) =
            log.read(streamId, StreamOffset.first, 10)
        val _: StreamInfo < (Journal & Abort[JournalStreamInfoFailure]) =
            log.streamInfo(streamId)
        // Runtime check: streamInfo on a fresh backend returns Absent for an unseen stream.
        for
            backend <- Journal.Backend.inMemory
            result  <- Abort.run[JournalStreamInfoFailure](Journal.run(backend)(log.streamInfo(streamId)))
        yield assert(result == Result.succeed(StreamInfo.Absent), s"expected Absent for a new stream, got $result")
        end for
    }

    "read returns an empty chunk when no events have been written to the stream" in {
        for
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalReadFailure] {
                Journal.run(backend) {
                    for
                        log    <- EventLog[LogTestEvent]
                        events <- log.read(streamId, StreamOffset.first, 10)
                    yield events
                }
            }
        yield result match
            case Result.Success(chunk) => assert(chunk.isEmpty)
            case other                 => fail(s"expected empty chunk, got: $other")
    }

    "append then read returns decoded payload with schema-derived event type and opaque event id" in {
        val schemaName = summon[Schema[LogTestEvent]].structure.name
        val event      = LogTestEvent("alice", 1)
        for
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalError] {
                Journal.run(backend) {
                    for
                        log    <- EventLog[LogTestEvent]
                        _      <- log.append(streamId, ExpectedOffset.NoStream, Chunk(event))
                        events <- log.read(streamId, StreamOffset.first, 10)
                    yield events
                    end for
                }
            }
        yield result match
            case Result.Success(events) =>
                assert(events.size == 1)
                assert(events(0).payload == event)
                assert(events(0).eventType.value == schemaName)
                assert(events(0).eventId.value.nonEmpty)
                assert(events(0).offset == StreamOffset.first)
            case other => fail(s"expected success, got: $other")
        end for
    }

    "second append produces distinct event ids and sequential offsets" in {
        val e1 = LogTestEvent("first", 1)
        val e2 = LogTestEvent("second", 2)
        for
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalError] {
                Journal.run(backend) {
                    for
                        log    <- EventLog[LogTestEvent]
                        _      <- log.append(streamId, ExpectedOffset.NoStream, Chunk(e1))
                        _      <- log.append(streamId, ExpectedOffset.Any, Chunk(e2))
                        events <- log.read(streamId, StreamOffset.first, 10)
                    yield events
                    end for
                }
            }
        yield result match
            case Result.Success(events) =>
                assert(events.size == 2)
                assert(events(0).eventId.value.nonEmpty)
                assert(events(1).eventId.value.nonEmpty)
                assert(events(0).eventId.value != events(1).eventId.value)
                assert(events(0).offset.value == 0L)
                assert(events(1).offset.value == 1L)
                assert(events(0).payload == e1)
                assert(events(1).payload == e2)
            case other => fail(s"expected success, got: $other")
        end for
    }

    "append with ExpectedOffset.Exact succeeds when the offset matches" in {
        val e1 = LogTestEvent("first", 1)
        val e2 = LogTestEvent("exact", 2)
        for
            backend <- Journal.Backend.inMemory
            result <- Abort.run[JournalError] {
                Journal.run(backend) {
                    for
                        log    <- EventLog[LogTestEvent]
                        r1     <- log.append(streamId, ExpectedOffset.NoStream, Chunk(e1))
                        _      <- log.append(streamId, ExpectedOffset.Exact(r1.lastOffset), Chunk(e2))
                        events <- log.read(streamId, StreamOffset.first, 10)
                    yield events
                    end for
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

    "EventLog.Typed carries all fields from the recorded event plus the decoded payload" in {
        val meta  = EventMetadata.empty
        val event = LogTestEvent("startup", 0)
        val typed = EventLog.Typed(
            offset = StreamOffset.first,
            eventId = eventId,
            eventType = eventType,
            metadata = meta,
            payload = event
        )
        assert(typed.offset == StreamOffset.first)
        assert(typed.eventId == eventId)
        assert(typed.eventType == eventType)
        assert(typed.metadata == meta)
        assert(typed.payload == event)
    }

end EventLogTest
