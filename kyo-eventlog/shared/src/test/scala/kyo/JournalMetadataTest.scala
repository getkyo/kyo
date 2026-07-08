package kyo

class JournalMetadataTest extends kyo.test.Test[Any]:

    "MetadataKey" - {
        "accepts a simple key" in {
            assert(MetadataKey("session").map(_.value) == Result.succeed("session"))
        }
        "accepts a dotted path" in {
            assert(MetadataKey("trace.correlation_id").map(_.value) == Result.succeed("trace.correlation_id"))
        }
        "splits segments on dots" in {
            val key = MetadataKey("a.b.c").getOrElse(throw new AssertionError("valid key"))
            assert(key.segments == Chunk("a", "b", "c"))
        }
        "rejects an empty key" in {
            assert(MetadataKey("") == Result.fail(JournalInvalidIdentifierError("MetadataKey", "")))
        }
        "rejects a leading dot" in {
            assert(MetadataKey(".foo") == Result.fail(JournalInvalidIdentifierError("MetadataKey", ".foo")))
        }
        "rejects a trailing dot" in {
            assert(MetadataKey("foo.") == Result.fail(JournalInvalidIdentifierError("MetadataKey", "foo.")))
        }
        "rejects an empty segment" in {
            assert(MetadataKey("foo..bar") == Result.fail(JournalInvalidIdentifierError("MetadataKey", "foo..bar")))
        }
    }

    "EventMetadata" - {
        "empty has no entries" in {
            assert(EventMetadata.empty.values.isEmpty)
        }
        "carries structural values" in {
            val key      = MetadataKey("event.number").getOrElse(throw new AssertionError("valid key"))
            val metadata = EventMetadata(Map(key -> MetadataValue(Structure.Value.Integer(42L))))
            assert(metadata.values(key).value == Structure.Value.Integer(42L))
        }
    }
end JournalMetadataTest
