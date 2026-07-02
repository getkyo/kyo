package kyo

class JournalEventTest extends kyo.test.Test[Any]:

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
end JournalEventTest
