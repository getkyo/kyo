package kyo

import kyo.internal.FileJournalCore

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

    "EventMetadata typed facade (get/put/contains/of, EventLog.AttributeKey/Attribute)" - {
        "EventMetadata.of/get typed round-trip for a String attribute; wire form unaffected" in {
            val metadata = EventMetadata.of(EventLog.Attribute(EventLog.Attributes.CorrelationId, "req-42"))
            assert(metadata.get(EventLog.Attributes.CorrelationId) == Maybe("req-42"))
            assert(!metadata.contains(EventLog.Attributes.SourceSystem))
            assert(metadata.values.size == 1)
        }

        "EventMetadata.get on a present key with a mismatched schema throws TypeMismatchException, never a silent Absent" in {
            val sharedKey = MetadataKey("num").getOrElse(throw new AssertionError("valid key"))
            val intKey    = EventLog.AttributeKey[Int](sharedKey)
            val stringKey = EventLog.AttributeKey[String](sharedKey)
            val metadata  = EventMetadata.of(EventLog.Attribute(intKey, 42))
            val ex        = intercept[TypeMismatchException] { metadata.get(stringKey) }
            assert(ex.expected == "String")
        }

        "EventMetadata.put overwrites an existing key's value; contains reflects presence" in {
            val key       = EventLog.AttributeKey[String](MetadataKey("k").getOrElse(throw new AssertionError("valid key")))
            val untouched = EventLog.AttributeKey[String](MetadataKey("other").getOrElse(throw new AssertionError("valid key")))
            val metadata  = EventMetadata.empty.put(EventLog.Attribute(key, "v1")).put(EventLog.Attribute(key, "v2"))
            assert(metadata.get(key) == Maybe("v2"))
            assert(metadata.contains(key))
            assert(!metadata.contains(untouched))
        }

        "two distinct String-valued AttributeKeys coexist in one EventMetadata without collision (key-string indexing, not a type-map)" in {
            val keyA     = EventLog.AttributeKey[String](MetadataKey("a").getOrElse(throw new AssertionError("valid key")))
            val keyB     = EventLog.AttributeKey[String](MetadataKey("b").getOrElse(throw new AssertionError("valid key")))
            val metadata = EventMetadata.of(EventLog.Attribute(keyA, "va"), EventLog.Attribute(keyB, "vb"))
            assert(metadata.get(keyA) == Maybe("va"))
            assert(metadata.get(keyB) == Maybe("vb"))
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

    "FileJournalCore metadata version-byte framing" - {
        val ionBinaryCodec = EventLogCodecs.MetadataCodec(IonBinary())
        val msgPackCodec   = EventLogCodecs.MetadataCodec(MsgPack())

        "ionBinary round-trips MapEntries with Integer keys (array-of-pairs path)" in {
            val key   = MetadataKey("entries").getOrElse(throw new AssertionError("valid key"))
            val sv    = Structure.Value.MapEntries(Chunk(Structure.Value.Integer(1L) -> Structure.Value.Str("v")))
            val md    = EventMetadata(Map(key -> MetadataValue(sv)))
            val bytes = FileJournalCore.encodeMetadata(ionBinaryCodec, md)
            FileJournalCore.decodeMetadata(ionBinaryCodec, bytes)(using Frame.internal) match
                case Result.Success(decoded) => assert(decoded.values(key).value == sv)
                case other                   => fail(s"ionBinary Integer-key MapEntries failed: $other")
        }
        "msgPack round-trips MapEntries with Integer keys (array-of-pairs path)" in {
            val key   = MetadataKey("entries").getOrElse(throw new AssertionError("valid key"))
            val sv    = Structure.Value.MapEntries(Chunk(Structure.Value.Integer(1L) -> Structure.Value.Str("v")))
            val md    = EventMetadata(Map(key -> MetadataValue(sv)))
            val bytes = FileJournalCore.encodeMetadata(msgPackCodec, md)
            FileJournalCore.decodeMetadata(msgPackCodec, bytes)(using Frame.internal) match
                case Result.Success(decoded) => assert(decoded.values(key).value == sv)
                case other                   => fail(s"msgPack Integer-key MapEntries failed: $other")
        }
        "ionBinary round-trips all ten constructors in one EventMetadata map" in {
            assert(roundTripCombinedMetadata(ionBinaryCodec, FileJournalCore.MetadataVersionCurrent))
        }
        "msgPack round-trips all ten constructors in one EventMetadata map" in {
            assert(roundTripCombinedMetadata(msgPackCodec, FileJournalCore.MetadataVersionMsgPack))
        }
        "ionBinary encodes version 0x02 and round-trips all ten constructors" in {
            assert(roundTripViaMetadataCodec(ionBinaryCodec, FileJournalCore.MetadataVersionCurrent))
        }
        "ionBinary decode accepts legacy 0x01 MsgPack bodies" in {
            val key    = MetadataKey("k").getOrElse(throw new AssertionError("valid key"))
            val md     = EventMetadata(Map(key -> MetadataValue(Structure.Value.Str("legacy"))))
            val legacy = FileJournalCore.encodeMetadata(msgPackCodec, md)
            assert(legacy(0) == FileJournalCore.MetadataVersionMsgPack)
            FileJournalCore.decodeMetadata(ionBinaryCodec, legacy)(using Frame.internal) match
                case Result.Success(decoded) =>
                    assert(decoded.values(key).value == Structure.Value.Str("legacy"))
                case other => fail(s"legacy MsgPack read failed: $other")
            end match
        }
        "msgPack encodes version 0x01 only" in {
            val bytes = FileJournalCore.encodeMetadata(msgPackCodec, EventMetadata.empty)
            assert(bytes(0) == FileJournalCore.MetadataVersionMsgPack)
        }
        // "msgPack rejects non-0x01 version bytes" has no equivalent: FileJournalCore.decodeMetadata
        // dispatches on the leading version BYTE, not on the caller's chosen wrapped codec, so a
        // 0x02-framed body always decodes through whatever codec THIS call was given (there is no
        // "wrong codec for this version" case left to reject).
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

    private def roundTripCombinedMetadata(codec: EventLogCodecs.MetadataCodec, expectedVersion: Byte): Boolean =
        val md    = EventMetadata(combinedMetadataMap)
        val bytes = FileJournalCore.encodeMetadata(codec, md)
        if bytes(0) != expectedVersion then false
        else
            FileJournalCore.decodeMetadata(codec, bytes)(using Frame.internal) match
                case Result.Success(decoded) => decoded == md
                case _                       => false
        end if
    end roundTripCombinedMetadata

    private def roundTripViaMetadataCodec(codec: EventLogCodecs.MetadataCodec, expectedVersion: Byte): Boolean =
        allStructureValues.zipWithIndex.forall { (sv, idx) =>
            val key   = MetadataKey(s"field.$idx").getOrElse(throw new AssertionError("valid key"))
            val md    = EventMetadata(Map(key -> MetadataValue(sv)))
            val bytes = FileJournalCore.encodeMetadata(codec, md)
            if bytes(0) != expectedVersion then false
            else
                FileJournalCore.decodeMetadata(codec, bytes)(using Frame.internal) match
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
