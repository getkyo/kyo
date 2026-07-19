package kyo

class JournalEventTest extends kyo.test.Test[Any]:

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("expected valid identifier"))

    "Event.StreamId" - {
        "accepts a non-empty value" in {
            assert(Event.StreamId("users-1").map(_.value) == Result.succeed("users-1"))
        }
        "rejects an empty value" in {
            assert(Event.StreamId("") == Result.fail(JournalInvalidIdentifierError("StreamId", "")))
        }
    }

    "Event.Id" - {
        "accepts a non-empty value" in {
            assert(Event.Id("event-1").map(_.value) == Result.succeed("event-1"))
        }
        "rejects an empty value" in {
            assert(Event.Id("") == Result.fail(JournalInvalidIdentifierError("EventId", "")))
        }
    }

    "Event.Type" - {
        "accepts a non-empty value" in {
            assert(Event.Type("UserRegistered").map(_.value) == Result.succeed("UserRegistered"))
        }
        "rejects an empty value" in {
            assert(Event.Type("") == Result.fail(JournalInvalidIdentifierError("EventType", "")))
        }
    }

    "Event.StreamOffset" - {
        "first is zero" in {
            assert(Event.StreamOffset.first.value == 0L)
        }
        "accepts values in [0, Long.MaxValue)" in {
            assert(Event.StreamOffset(0L).map(_.value) == Result.succeed(0L))
            assert(Event.StreamOffset(41L).map(_.value) == Result.succeed(41L))
            assert(Event.StreamOffset(Long.MaxValue - 1L).map(_.value) == Result.succeed(Long.MaxValue - 1L))
        }
        "rejects negative values and Long.MaxValue" in {
            assert(Event.StreamOffset(-1L) == Result.fail(JournalInvalidIdentifierError("StreamOffset", "-1")))
            assert(Event.StreamOffset(Long.MaxValue) ==
                Result.fail(JournalInvalidIdentifierError("StreamOffset", Long.MaxValue.toString)))
        }
    }

    "Event.StreamVersion" - {
        "initial is zero" in {
            assert(Event.StreamVersion.initial.value == 0L)
        }
        "rejects negative values" in {
            assert(Event.StreamVersion(-1L) == Result.fail(JournalInvalidIdentifierError("StreamVersion", "-1")))
        }
        "after is offset + 1" in {
            val o = Event.StreamOffset(4L).getOrElse(throw new AssertionError("valid offset"))
            assert(Event.StreamVersion.after(o).value == 5L)
        }
    }

    "StreamInfo" - {
        "Absent does not exist" in {
            assert(!StreamInfo.Absent.exists)
        }
        "Existing exists" in {
            assert(StreamInfo.Existing(valid(Event.StreamVersion(3L)), Event.StreamOffset.first).exists)
        }
    }

    "Event.New and Event.Recorded" - {
        "carry payload bytes by value" in {
            val payload = Span.from("""{"name":"Ada"}""".getBytes("UTF-8"))
            val envelope = Event.New(
                id = valid(Event.Id("event-1")),
                eventType = valid(Event.Type("UserRegistered")),
                payload = payload,
                metadata = Event.Metadata.empty
            )
            val recorded = Event.Recorded(
                streamId = valid(Event.StreamId("users-1")),
                offset = Event.StreamOffset.first,
                id = envelope.id,
                eventType = envelope.eventType,
                payload = envelope.payload,
                metadata = envelope.metadata
            )
            // Span equality via == is reference-based; payload contents compare with Span#is.
            assert(recorded.payload.is(Span.from("""{"name":"Ada"}""".getBytes("UTF-8"))))
            assert(recorded.streamId.value == "users-1")
            assert(recorded.offset == Event.StreamOffset.first)
            assert(recorded.id == envelope.id)
            assert(recorded.eventType == envelope.eventType)
            assert(recorded.metadata == Event.Metadata.empty)
        }
    }

    "AppendResult" - {
        "reports the appended range and post-append state" in {
            val sid = Event.StreamId("users-1").getOrElse(throw new AssertionError("valid stream id"))
            val result =
                AppendResult(
                    sid,
                    Event.StreamOffset.first,
                    Event.StreamOffset.first,
                    StreamInfo.Existing(valid(Event.StreamVersion(1L)), Event.StreamOffset.first)
                )
            assert(result.firstOffset == Event.StreamOffset.first)
            assert(result.lastOffset == Event.StreamOffset.first)
            assert(result.streamInfo == StreamInfo.Existing(valid(Event.StreamVersion(1L)), Event.StreamOffset.first))
        }
    }

    "Event (sealed trait)" - {
        "pattern-match over Event.New/Event.Recorded reaches the shared id/eventType/metadata/payload members without narrowing" in {
            val payload = Span.from("""{"name":"Ada"}""".getBytes("UTF-8"))
            val pending = Event.New(
                id = valid(Event.Id("event-1")),
                eventType = valid(Event.Type("UserRegistered")),
                payload = payload,
                metadata = Event.Metadata.empty
            )
            val committed = Event.Recorded(
                streamId = valid(Event.StreamId("users-1")),
                offset = Event.StreamOffset.first,
                id = pending.id,
                eventType = pending.eventType,
                payload = pending.payload,
                metadata = pending.metadata
            )
            val events: Chunk[Event] = Chunk(pending, committed)

            val matchedIds: Chunk[Event.Id] = events.map {
                case p: Event.New      => p.id
                case c: Event.Recorded => c.id
            }
            assert(matchedIds == Chunk(pending.id, committed.id))
            assert(matchedIds(0) == matchedIds(1))

            // No case split: reads the trait's shared members directly through the Event type.
            val sharedEventTypes: Chunk[Event.Type]   = events.map(e => e.eventType)
            val sharedMetadata: Chunk[Event.Metadata] = events.map(e => e.metadata)
            assert(sharedEventTypes(0) == sharedEventTypes(1))
            assert(sharedMetadata(0) == sharedMetadata(1))
            assert(events(0).payload.is(payload))
            assert(events(1).payload.is(payload))
        }
    }

    "Event.Recorded.id" - {
        "equals the appended Event.New's id after a Journal.run(inMemory) append/read round-trip" in {
            val streamId = valid(Event.StreamId("users-1"))
            val pending = Event.New(
                id = valid(Event.Id("event-1")),
                eventType = valid(Event.Type("UserRegistered")),
                payload = Span.from("""{"name":"Ada"}""".getBytes("UTF-8")),
                metadata = Event.Metadata.empty
            )
            val program =
                for
                    _      <- Journal.append(streamId, ExpectedOffset.NoStream, Chunk(pending))
                    events <- Journal.read(streamId, Event.StreamOffset.first, 10)
                yield events(0)

            for
                backend   <- Journal.Backend.inMemory
                committed <- Journal.run(backend)(program)
            yield assert(committed.id == pending.id)
            end for
        }

        "has no eventId member" in {
            val errors = scala.compiletime.testing.typeCheckErrors(
                """
                val committed: Event.Recorded = ???
                val _: Event.Id = committed.eventId
                """
            )
            assert(errors.nonEmpty)
        }
    }
end JournalEventTest
