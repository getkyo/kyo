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

    "MetadataValue.metadataValueSchema" - {
        // Exercises the given Schema[MetadataValue] (not the backend's direct write/read) through the MsgPack
        // codec the file backend uses. All ten Structure.Value constructors must survive, covering the three the
        // kyo-schema identity codec drops on its own: VariantCase, all-string-keyed MapEntries, and BigNum. The
        // codec's open/dynamic tag-keyed shape round-trips through MsgPack's introspecting reader; the text
        // codecs (JSON, Ion) do not read the array-of-arrays MapEntries encoding back, so the schema's claim is
        // scoped to the self-describing binary codec and is not asserted for the text codecs.
        "round-trips all ten Structure.Value constructors through the MsgPack codec" in {
            val bigNum = BigDecimal("123456789012345678901234567890.0123456789")
            val values = List[Structure.Value](
                Structure.Value.Str("hello"),
                Structure.Value.Integer(42L),
                Structure.Value.Bool(true),
                Structure.Value.Decimal(3.14),
                Structure.Value.BigNum(bigNum),
                Structure.Value.Null,
                Structure.Value.Sequence(Chunk(Structure.Value.Str("a"), Structure.Value.Integer(1L))),
                Structure.Value.Record(Chunk("x" -> Structure.Value.Bool(false))),
                Structure.Value.MapEntries(Chunk(Structure.Value.Str("k") -> Structure.Value.Str("v"))),
                Structure.Value.VariantCase("MyCase", Structure.Value.Str("payload"))
            )
            val roundTripped = values.map { sv =>
                val v = MetadataValue(sv)
                val viaMsgPack =
                    MsgPack.decode[MetadataValue](MsgPack.encode(v)).getOrElse(throw new AssertionError(s"msgpack decode failed for $sv"))
                viaMsgPack.value == sv
            }
            assert(values.length == 10)
            assert(roundTripped.forall(identity))
        }
    }
end JournalMetadataTest
