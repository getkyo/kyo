package kyo

class JournalEventTest extends kyo.test.Test[Any]:

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("expected valid identifier"))

    "StreamId" - {
        "accepts a non-empty value" in {
            assert(StreamId("users-1").map(_.value) == Result.succeed("users-1"))
        }
        "rejects an empty value" in {
            assert(StreamId("") == Result.fail(JournalInvalidIdentifierError("StreamId", "")))
        }
    }

    "EventId" - {
        "accepts a non-empty value" in {
            assert(EventId("event-1").map(_.value) == Result.succeed("event-1"))
        }
        "rejects an empty value" in {
            assert(EventId("") == Result.fail(JournalInvalidIdentifierError("EventId", "")))
        }
    }

    "EventType" - {
        "accepts a non-empty value" in {
            assert(EventType("UserRegistered").map(_.value) == Result.succeed("UserRegistered"))
        }
        "rejects an empty value" in {
            assert(EventType("") == Result.fail(JournalInvalidIdentifierError("EventType", "")))
        }
    }

    "StreamOffset" - {
        "first is zero" in {
            assert(StreamOffset.first.value == 0L)
        }
        "accepts values in [0, Long.MaxValue)" in {
            assert(StreamOffset(0L).map(_.value) == Result.succeed(0L))
            assert(StreamOffset(41L).map(_.value) == Result.succeed(41L))
            assert(StreamOffset(Long.MaxValue - 1L).map(_.value) == Result.succeed(Long.MaxValue - 1L))
        }
        "rejects negative values and Long.MaxValue" in {
            assert(StreamOffset(-1L) == Result.fail(JournalInvalidIdentifierError("StreamOffset", "-1")))
            assert(StreamOffset(Long.MaxValue) ==
                Result.fail(JournalInvalidIdentifierError("StreamOffset", Long.MaxValue.toString)))
        }
    }

    "StreamVersion" - {
        "initial is zero" in {
            assert(StreamVersion.initial.value == 0L)
        }
        "rejects negative values" in {
            assert(StreamVersion(-1L) == Result.fail(JournalInvalidIdentifierError("StreamVersion", "-1")))
        }
        "after is offset + 1" in {
            val o = StreamOffset(4L).getOrElse(throw new AssertionError("valid offset"))
            assert(StreamVersion.after(o).value == 5L)
        }
    }

    "StreamInfo" - {
        "Absent does not exist" in {
            assert(!StreamInfo.Absent.exists)
        }
        "Existing exists" in {
            assert(StreamInfo.Existing(3L, StreamOffset.first).exists)
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
                offset = StreamOffset.first,
                eventId = envelope.id,
                eventType = envelope.eventType,
                payload = envelope.payload,
                metadata = envelope.metadata
            )
            // Span equality via == is reference-based; payload contents compare with Span#is.
            assert(recorded.payload.is(Span.from("""{"name":"Ada"}""".getBytes("UTF-8"))))
            assert(recorded.streamId.value == "users-1")
            assert(recorded.offset == StreamOffset.first)
            assert(recorded.eventId == envelope.id)
            assert(recorded.eventType == envelope.eventType)
            assert(recorded.metadata == EventMetadata.empty)
        }
    }

    "AppendResult" - {
        "reports the appended range and post-append state" in {
            val sid    = StreamId("users-1").getOrElse(throw new AssertionError("valid stream id"))
            val result = AppendResult(sid, StreamOffset.first, StreamOffset.first, StreamInfo.Existing(1L, StreamOffset.first))
            assert(result.firstOffset == StreamOffset.first)
            assert(result.lastOffset == StreamOffset.first)
            assert(result.streamInfo == StreamInfo.Existing(1L, StreamOffset.first))
        }
    }
end JournalEventTest
