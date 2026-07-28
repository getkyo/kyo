package kyo

import kyo.internal.FileJournalCore

class JournalMetadataTest extends kyo.test.Test[Any]:

    "Event.Metadata.Key" - {
        "accepts a simple key" in {
            assert(Event.Metadata.Key("session").map(_.value) == Result.succeed("session"))
        }
        "accepts a dotted path" in {
            assert(Event.Metadata.Key("trace.correlation_id").map(_.value) == Result.succeed("trace.correlation_id"))
        }
        "splits segments on dots" in {
            val key = Event.Metadata.Key("a.b.c").getOrElse(throw new AssertionError("valid key"))
            assert(key.segments == Chunk("a", "b", "c"))
        }
        "rejects an empty key" in {
            assert(Event.Metadata.Key("") == Result.fail(JournalInvalidIdentifierError("MetadataKey", "")))
        }
        "rejects a leading dot" in {
            assert(Event.Metadata.Key(".foo") == Result.fail(JournalInvalidIdentifierError("MetadataKey", ".foo")))
        }
        "rejects a trailing dot" in {
            assert(Event.Metadata.Key("foo.") == Result.fail(JournalInvalidIdentifierError("MetadataKey", "foo.")))
        }
        "rejects an empty segment" in {
            assert(Event.Metadata.Key("foo..bar") == Result.fail(JournalInvalidIdentifierError("MetadataKey", "foo..bar")))
        }
    }

    "Event.Metadata" - {
        "empty has no entries" in {
            assert(Event.Metadata.empty.values.isEmpty)
        }
        "carries structural values" in {
            val key      = Event.Metadata.Key("event.number").getOrElse(throw new AssertionError("valid key"))
            val metadata = Event.Metadata(Map(key -> Event.Metadata.Value(Structure.Value.Integer(42L))))
            assert(metadata.values(key).value == Structure.Value.Integer(42L))
        }
    }

    "Event.Metadata typed facade (get/put/contains/of, Event.AttributeKey/Attribute)" - {
        "Event.Metadata.of/get typed round-trip for a String attribute; wire form unaffected" in {
            val metadata = Event.Metadata.of(Event.Attribute(Event.Attributes.CorrelationId, "req-42"))
            assert(metadata.get(Event.Attributes.CorrelationId) == Maybe("req-42"))
            assert(!metadata.contains(Event.Attributes.SourceSystem))
            assert(metadata.values.size == 1)
        }

        "Event.Metadata.get on a present key with a mismatched schema throws TypeMismatchException, never a silent Absent" in {
            val sharedKey = Event.Metadata.Key("num").getOrElse(throw new AssertionError("valid key"))
            val intKey    = Event.AttributeKey[Int](sharedKey)
            val stringKey = Event.AttributeKey[String](sharedKey)
            val metadata  = Event.Metadata.of(Event.Attribute(intKey, 42))
            val ex        = intercept[TypeMismatchException] { metadata.get(stringKey) }
            assert(ex.expected == "String")
        }

        "Event.Metadata.put overwrites an existing key's value; contains reflects presence" in {
            val key       = Event.AttributeKey[String](Event.Metadata.Key("k").getOrElse(throw new AssertionError("valid key")))
            val untouched = Event.AttributeKey[String](Event.Metadata.Key("other").getOrElse(throw new AssertionError("valid key")))
            val metadata  = Event.Metadata.empty.put(Event.Attribute(key, "v1")).put(Event.Attribute(key, "v2"))
            assert(metadata.get(key) == Maybe("v2"))
            assert(metadata.contains(key))
            assert(!metadata.contains(untouched))
        }

        "two distinct String-valued AttributeKeys coexist in one Event.Metadata without collision (key-string indexing, not a type-map)" in {
            val keyA     = Event.AttributeKey[String](Event.Metadata.Key("a").getOrElse(throw new AssertionError("valid key")))
            val keyB     = Event.AttributeKey[String](Event.Metadata.Key("b").getOrElse(throw new AssertionError("valid key")))
            val metadata = Event.Metadata.of(Event.Attribute(keyA, "va"), Event.Attribute(keyB, "vb"))
            assert(metadata.get(keyA) == Maybe("va"))
            assert(metadata.get(keyB) == Maybe("vb"))
        }
    }

    "JournalEntryRef" - {
        "uri renders and parse round-trips the (journalId, streamId, offset) triple" in {
            val journalId = JournalId.validate("fleet-main").getOrElse(throw new AssertionError("valid journal id"))
            val streamId  = Event.StreamId("quest-party").getOrElse(throw new AssertionError("valid stream id"))
            val offset    = Event.StreamOffset(7L).getOrElse(throw new AssertionError("valid offset"))
            val ref       = JournalEntryRef(journalId, streamId, offset)

            val parsed = Abort.run[JournalIdentityError](JournalEntryRef.parse(ref.uri)).eval
            assert(parsed == Result.succeed(ref))
        }

        "parse/render round trips a logical journal: URI carrying only logical ids, no path or segment" in {
            val journalId = JournalId.validate("orders").getOrElse(throw new AssertionError("valid journal id"))
            val streamId  = Event.StreamId("s1").getOrElse(throw new AssertionError("valid stream id"))
            val offset    = Event.StreamOffset(7L).getOrElse(throw new AssertionError("valid offset"))
            val ref       = JournalEntryRef(journalId, streamId, offset)

            assert(ref.uri == "journal:orders/s1/7")
            val parsed = Abort.run[JournalIdentityError](JournalEntryRef.parse(ref.uri)).eval
            assert(parsed == Result.succeed(ref))
        }

        "parse/render round trips a hierarchical (slash-containing) stream id" in {
            val journalId = JournalId.validate("orders").getOrElse(throw new AssertionError("valid journal id"))
            val streamId  = Event.StreamId("orders/2024").getOrElse(throw new AssertionError("valid stream id"))
            val offset    = Event.StreamOffset(7L).getOrElse(throw new AssertionError("valid offset"))
            val ref       = JournalEntryRef(journalId, streamId, offset)

            assert(ref.uri == "journal:orders/orders/2024/7")
            val parsed = Abort.run[JournalIdentityError](JournalEntryRef.parse(ref.uri)).eval
            assert(parsed == Result.succeed(ref))
        }

        "a uri with too few slashes fails with a typed identity error" in {
            val result = Abort.run[JournalIdentityError](JournalEntryRef.parse("journal:orders/7")).eval
            result match
                case Result.Failure(_: JournalIdentityError) => succeed("malformed uri never produces a ref")
                case other                                   => fail(s"expected JournalIdentityError failure, got: $other")
        }

        "a uri with an empty stream segment fails with a typed identity error" in {
            val result = Abort.run[JournalIdentityError](JournalEntryRef.parse("journal:orders//7")).eval
            result match
                case Result.Failure(_: JournalIdentityError) => succeed("empty stream segment never produces a ref")
                case other                                   => fail(s"expected JournalIdentityError failure, got: $other")
        }

        "a physical file:// URI is rejected at parse" in {
            val result = Abort.run[JournalIdentityError](JournalEntryRef.parse("file:///var/journal/orders/000007.seg")).eval
            result match
                case Result.Failure(_: JournalIdentityError) => succeed("physical URI form never produces a ref")
                case other                                   => fail(s"expected JournalIdentityError failure, got: $other")
        }
    }

    "Event.Metadata.Value.metadataValueSchema" - {
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
            val key   = Event.Metadata.Key("entries").getOrElse(throw new AssertionError("valid key"))
            val sv    = Structure.Value.MapEntries(Chunk(Structure.Value.Integer(1L) -> Structure.Value.Str("v")))
            val md    = Event.Metadata(Map(key -> Event.Metadata.Value(sv)))
            val bytes = FileJournalCore.encodeMetadata(ionBinaryCodec, md)
            FileJournalCore.decodeMetadata(ionBinaryCodec, bytes)(using Frame.internal) match
                case Result.Success(decoded) => assert(decoded.values(key).value == sv)
                case other                   => fail(s"ionBinary Integer-key MapEntries failed: $other")
        }
        "msgPack round-trips MapEntries with Integer keys (array-of-pairs path)" in {
            val key   = Event.Metadata.Key("entries").getOrElse(throw new AssertionError("valid key"))
            val sv    = Structure.Value.MapEntries(Chunk(Structure.Value.Integer(1L) -> Structure.Value.Str("v")))
            val md    = Event.Metadata(Map(key -> Event.Metadata.Value(sv)))
            val bytes = FileJournalCore.encodeMetadata(msgPackCodec, md)
            FileJournalCore.decodeMetadata(msgPackCodec, bytes)(using Frame.internal) match
                case Result.Success(decoded) => assert(decoded.values(key).value == sv)
                case other                   => fail(s"msgPack Integer-key MapEntries failed: $other")
        }
        "ionBinary round-trips all ten constructors in one Event.Metadata map" in {
            assert(roundTripCombinedMetadata(ionBinaryCodec, FileJournalCore.MetadataVersionCurrent))
        }
        "msgPack round-trips all ten constructors in one Event.Metadata map" in {
            assert(roundTripCombinedMetadata(msgPackCodec, FileJournalCore.MetadataVersionMsgPack))
        }
        "ionBinary encodes version 0x02 and round-trips all ten constructors" in {
            assert(roundTripViaMetadataCodec(ionBinaryCodec, FileJournalCore.MetadataVersionCurrent))
        }
        "ionBinary decode accepts legacy 0x01 MsgPack bodies" in {
            val key    = Event.Metadata.Key("k").getOrElse(throw new AssertionError("valid key"))
            val md     = Event.Metadata(Map(key -> Event.Metadata.Value(Structure.Value.Str("legacy"))))
            val legacy = FileJournalCore.encodeMetadata(msgPackCodec, md)
            assert(legacy(0) == FileJournalCore.MetadataVersionMsgPack)
            FileJournalCore.decodeMetadata(ionBinaryCodec, legacy)(using Frame.internal) match
                case Result.Success(decoded) =>
                    assert(decoded.values(key).value == Structure.Value.Str("legacy"))
                case other => fail(s"legacy MsgPack read failed: $other")
            end match
        }
        "msgPack encodes version 0x01 only" in {
            val bytes = FileJournalCore.encodeMetadata(msgPackCodec, Event.Metadata.empty)
            assert(bytes(0) == FileJournalCore.MetadataVersionMsgPack)
        }
        // "msgPack rejects non-0x01 version bytes" has no equivalent: FileJournalCore.decodeMetadata
        // dispatches on the leading version BYTE, not on the caller's chosen wrapped codec, so a
        // 0x02-framed body always decodes through whatever codec THIS call was given (there is no
        // "wrong codec for this version" case left to reject).
    }

    private def combinedMetadataMap: Map[Event.Metadata.Key, Event.Metadata.Value] =
        val bigDecimalVal = BigDecimal("123456789012345678901234567890.0123456789")
        Map(
            Event.Metadata.Key("str").getOrElse(throw new AssertionError("valid key")) ->
                Event.Metadata.Value(Structure.Value.Str("hello")),
            Event.Metadata.Key("int").getOrElse(throw new AssertionError("valid key")) ->
                Event.Metadata.Value(Structure.Value.Integer(42L)),
            Event.Metadata.Key("bool").getOrElse(throw new AssertionError("valid key")) ->
                Event.Metadata.Value(Structure.Value.Bool(true)),
            Event.Metadata.Key("decimal").getOrElse(throw new AssertionError("valid key")) ->
                Event.Metadata.Value(Structure.Value.Decimal(3.14)),
            Event.Metadata.Key("bignum").getOrElse(throw new AssertionError("valid key")) ->
                Event.Metadata.Value(Structure.Value.BigNum(bigDecimalVal)),
            Event.Metadata.Key("null").getOrElse(throw new AssertionError("valid key")) ->
                Event.Metadata.Value(Structure.Value.Null),
            Event.Metadata.Key("seq").getOrElse(throw new AssertionError("valid key")) ->
                Event.Metadata.Value(Structure.Value.Sequence(Chunk(
                    Structure.Value.Str("a"),
                    Structure.Value.Integer(1L)
                ))),
            Event.Metadata.Key("record").getOrElse(throw new AssertionError("valid key")) ->
                Event.Metadata.Value(Structure.Value.Record(Chunk("x" -> Structure.Value.Bool(false)))),
            Event.Metadata.Key("entries").getOrElse(throw new AssertionError("valid key")) ->
                Event.Metadata.Value(Structure.Value.MapEntries(Chunk(Structure.Value.Integer(1L) -> Structure.Value.Str("v")))),
            Event.Metadata.Key("variant").getOrElse(throw new AssertionError("valid key")) ->
                Event.Metadata.Value(Structure.Value.VariantCase("MyCase", Structure.Value.Str("payload")))
        )
    end combinedMetadataMap

    private def roundTripCombinedMetadata(codec: EventLogCodecs.MetadataCodec, expectedVersion: Byte): Boolean =
        val md    = Event.Metadata(combinedMetadataMap)
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
            val key   = Event.Metadata.Key(s"field.$idx").getOrElse(throw new AssertionError("valid key"))
            val md    = Event.Metadata(Map(key -> Event.Metadata.Value(sv)))
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
            val v = Event.Metadata.Value(sv)
            val w = codec.newWriter()
            summon[Schema[Event.Metadata.Value]].writeTo(v, w)
            val reader  = codec.newReader(w.result())
            val decoded = summon[Schema[Event.Metadata.Value]].readFrom(reader)
            decoded.value == sv
        }
        values.length == 10 && roundTripped.forall(identity)
    end roundTripAllConstructors
end JournalMetadataTest
