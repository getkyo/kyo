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
        "round-trips all ten Structure.Value constructors through MsgPack" in {
            assert(roundTripAllConstructors(MsgPack()))
        }
        "round-trips all ten Structure.Value constructors through Ion Binary" in {
            assert(roundTripAllConstructors(IonBinary()))
        }
    }

    "EventMetadataCodec" - {
        "ionBinary round-trips MapEntries with Integer keys (array-of-pairs path)" in {
            val key   = MetadataKey("entries").getOrElse(throw new AssertionError("valid key"))
            val sv    = Structure.Value.MapEntries(Chunk(Structure.Value.Integer(1L) -> Structure.Value.Str("v")))
            val md    = EventMetadata(Map(key -> MetadataValue(sv)))
            val bytes = EventMetadataCodec.ionBinary.encode(md)
            EventMetadataCodec.ionBinary.decode(bytes)(using Frame.internal) match
                case Result.Success(decoded) => assert(decoded.values(key).value == sv)
                case other                   => fail(s"ionBinary Integer-key MapEntries failed: $other")
        }
        "msgPack round-trips MapEntries with Integer keys (array-of-pairs path)" in {
            val key   = MetadataKey("entries").getOrElse(throw new AssertionError("valid key"))
            val sv    = Structure.Value.MapEntries(Chunk(Structure.Value.Integer(1L) -> Structure.Value.Str("v")))
            val md    = EventMetadata(Map(key -> MetadataValue(sv)))
            val bytes = EventMetadataCodec.msgPack.encode(md)
            EventMetadataCodec.msgPack.decode(bytes)(using Frame.internal) match
                case Result.Success(decoded) => assert(decoded.values(key).value == sv)
                case other                   => fail(s"msgPack Integer-key MapEntries failed: $other")
        }
        "ionBinary round-trips all ten constructors in one EventMetadata map" in {
            assert(roundTripCombinedMetadata(EventMetadataCodec.ionBinary, EventMetadataCodec.MetadataVersionIonBinary))
        }
        "msgPack round-trips all ten constructors in one EventMetadata map" in {
            assert(roundTripCombinedMetadata(EventMetadataCodec.msgPack, EventMetadataCodec.MetadataVersionMsgPack))
        }
        "ionBinary encodes version 0x02 and round-trips all ten constructors" in {
            assert(roundTripViaMetadataCodec(EventMetadataCodec.ionBinary, EventMetadataCodec.MetadataVersionIonBinary))
        }
        "ionBinary decode accepts legacy 0x01 MsgPack bodies" in {
            val key    = MetadataKey("k").getOrElse(throw new AssertionError("valid key"))
            val md     = EventMetadata(Map(key -> MetadataValue(Structure.Value.Str("legacy"))))
            val legacy = EventMetadataCodec.msgPack.encode(md)
            assert(legacy(0) == EventMetadataCodec.MetadataVersionMsgPack)
            EventMetadataCodec.ionBinary.decode(legacy)(using Frame.internal) match
                case Result.Success(decoded) =>
                    assert(decoded.values(key).value == Structure.Value.Str("legacy"))
                case other => fail(s"legacy MsgPack read failed: $other")
            end match
        }
        "msgPack encodes version 0x01 only" in {
            val bytes = EventMetadataCodec.msgPack.encode(EventMetadata.empty)
            assert(bytes(0) == EventMetadataCodec.MetadataVersionMsgPack)
        }
        "msgPack rejects non-0x01 version bytes" in {
            val ionBytes = EventMetadataCodec.ionBinary.encode(EventMetadata.empty)
            val result   = EventMetadataCodec.msgPack.decode(ionBytes)(using Frame.internal)
            assert(result.isFailure)
        }
        "default is ionBinary" in {
            assert(EventMetadataCodec.default eq EventMetadataCodec.ionBinary)
        }
    }

    private def combinedMetadataMap: Map[MetadataKey, MetadataValue] =
        val bigDecimalVal = BigDecimal("123456789012345678901234567890.0123456789")
        Map(
            MetadataKey("str").getOrElse(throw new AssertionError("valid key")) ->
                MetadataValue(Structure.Value.Str("hello")),
            MetadataKey("int").getOrElse(throw new AssertionError("valid key")) ->
                MetadataValue(Structure.Value.Integer(42L)),
            MetadataKey("bool").getOrElse(throw new AssertionError("valid key")) ->
                MetadataValue(Structure.Value.Bool(true)),
            MetadataKey("decimal").getOrElse(throw new AssertionError("valid key")) ->
                MetadataValue(Structure.Value.Decimal(3.14)),
            MetadataKey("bignum").getOrElse(throw new AssertionError("valid key")) ->
                MetadataValue(Structure.Value.BigNum(bigDecimalVal)),
            MetadataKey("null").getOrElse(throw new AssertionError("valid key")) ->
                MetadataValue(Structure.Value.Null),
            MetadataKey("seq").getOrElse(throw new AssertionError("valid key")) ->
                MetadataValue(Structure.Value.Sequence(Chunk(
                    Structure.Value.Str("a"),
                    Structure.Value.Integer(1L)
                ))),
            MetadataKey("record").getOrElse(throw new AssertionError("valid key")) ->
                MetadataValue(Structure.Value.Record(Chunk("x" -> Structure.Value.Bool(false)))),
            MetadataKey("entries").getOrElse(throw new AssertionError("valid key")) ->
                MetadataValue(Structure.Value.MapEntries(Chunk(Structure.Value.Integer(1L) -> Structure.Value.Str("v")))),
            MetadataKey("variant").getOrElse(throw new AssertionError("valid key")) ->
                MetadataValue(Structure.Value.VariantCase("MyCase", Structure.Value.Str("payload")))
        )
    end combinedMetadataMap

    private def roundTripCombinedMetadata(codec: EventMetadataCodec, expectedVersion: Byte): Boolean =
        val md    = EventMetadata(combinedMetadataMap)
        val bytes = codec.encode(md)
        if bytes(0) != expectedVersion then false
        else
            codec.decode(bytes)(using Frame.internal) match
                case Result.Success(decoded) => decoded == md
                case _                       => false
        end if
    end roundTripCombinedMetadata

    private def roundTripViaMetadataCodec(codec: EventMetadataCodec, expectedVersion: Byte): Boolean =
        allStructureValues.zipWithIndex.forall { (sv, idx) =>
            val key   = MetadataKey(s"field.$idx").getOrElse(throw new AssertionError("valid key"))
            val md    = EventMetadata(Map(key -> MetadataValue(sv)))
            val bytes = codec.encode(md)
            if bytes(0) != expectedVersion then false
            else
                codec.decode(bytes)(using Frame.internal) match
                    case Result.Success(decoded) => decoded.values(key).value == sv
                    case _                       => false
            end if
        }

    private def allStructureValues: List[Structure.Value] =
        val bigNum = BigDecimal("123456789012345678901234567890.0123456789")
        List(
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
    end allStructureValues

    private def roundTripAllConstructors(codec: Codec): Boolean =
        val values = allStructureValues
        val roundTripped = values.map { sv =>
            val v = MetadataValue(sv)
            val w = codec.newWriter()
            summon[Schema[MetadataValue]].writeTo(v, w)
            val reader  = codec.newReader(w.result())
            val decoded = summon[Schema[MetadataValue]].readFrom(reader)
            decoded.value == sv
        }
        values.length == 10 && roundTripped.forall(identity)
    end roundTripAllConstructors
end JournalMetadataTest
