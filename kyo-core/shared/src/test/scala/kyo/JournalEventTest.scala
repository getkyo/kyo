package kyo

class JournalEventTest extends kyo.test.Test[Any]:

    private def valid[A](r: Result[JournalError.InvalidIdentifier, A]): A =
        r.getOrElse(throw new AssertionError("expected valid identifier"))

    "StreamId" - {
        "accepts a non-empty value" in {
            assert(StreamId("users-1").map(_.value) == Result.succeed("users-1"))
        }
        "rejects an empty value" in {
            assert(StreamId("") == Result.fail(JournalError.InvalidIdentifier("StreamId", "")))
        }
    }

    "EventId" - {
        "accepts a non-empty value" in {
            assert(EventId("event-1").map(_.value) == Result.succeed("event-1"))
        }
        "rejects an empty value" in {
            assert(EventId("") == Result.fail(JournalError.InvalidIdentifier("EventId", "")))
        }
    }

    "EventType" - {
        "accepts a non-empty value" in {
            assert(EventType("UserRegistered").map(_.value) == Result.succeed("UserRegistered"))
        }
        "rejects an empty value" in {
            assert(EventType("") == Result.fail(JournalError.InvalidIdentifier("EventType", "")))
        }
    }

    "StreamRevision" - {
        "first is zero" in {
            assert(StreamRevision.first.value == 0L)
        }
        "accepts values in [0, Long.MaxValue)" in {
            assert(StreamRevision(0L).map(_.value) == Result.succeed(0L))
            assert(StreamRevision(41L).map(_.value) == Result.succeed(41L))
            assert(StreamRevision(Long.MaxValue - 1L).map(_.value) == Result.succeed(Long.MaxValue - 1L))
        }
        "rejects negative values and Long.MaxValue" in {
            assert(StreamRevision(-1L) == Result.fail(JournalError.InvalidIdentifier("StreamRevision", "-1")))
            assert(StreamRevision(Long.MaxValue) ==
                Result.fail(JournalError.InvalidIdentifier("StreamRevision", Long.MaxValue.toString)))
        }
    }

    "StreamVersion" - {
        "initial is zero" in {
            assert(StreamVersion.initial.value == 0L)
        }
        "rejects negative values" in {
            assert(StreamVersion(-1L) == Result.fail(JournalError.InvalidIdentifier("StreamVersion", "-1")))
        }
        "after is revision + 1" in {
            val r = StreamRevision(4L).getOrElse(throw new AssertionError("valid revision"))
            assert(StreamVersion.after(r).value == 5L)
        }
    }

    "StreamInfo" - {
        "Absent does not exist" in {
            assert(!StreamInfo.Absent.exists)
        }
        "Existing exists" in {
            assert(StreamInfo.Existing(3L, StreamRevision.first).exists)
        }
    }

    "EventEnvelope and RecordedEvent" - {
        "carry payload bytes by value" in {
            val payload = Span.from("""{"name":"Ada"}""".getBytes("UTF-8"))
            val envelope = EventEnvelope(
                id = valid(EventId("event-1")),
                eventType = valid(EventType("UserRegistered")),
                payload = payload,
                metadata = EventMetadata.empty
            )
            val recorded = RecordedEvent(
                streamId = valid(StreamId("users-1")),
                revision = StreamRevision.first,
                eventId = envelope.id,
                eventType = envelope.eventType,
                payload = envelope.payload,
                metadata = envelope.metadata
            )
            // Span equality via == is reference-based; payload contents compare with Span#is.
            assert(recorded.payload.is(Span.from("""{"name":"Ada"}""".getBytes("UTF-8"))))
            assert(recorded.streamId.value == "users-1")
            assert(recorded.revision == StreamRevision.first)
            assert(recorded.eventId == envelope.id)
            assert(recorded.eventType == envelope.eventType)
            assert(recorded.metadata == EventMetadata.empty)
        }
    }

    "AppendResult" - {
        "reports the appended range and post-append state" in {
            val sid    = StreamId("users-1").getOrElse(throw new AssertionError("valid stream id"))
            val result = AppendResult(sid, StreamRevision.first, StreamRevision.first, StreamInfo.Existing(1L, StreamRevision.first))
            assert(result.firstRevision == StreamRevision.first)
            assert(result.lastRevision == StreamRevision.first)
            assert(result.streamInfo == StreamInfo.Existing(1L, StreamRevision.first))
        }
    }
end JournalEventTest
