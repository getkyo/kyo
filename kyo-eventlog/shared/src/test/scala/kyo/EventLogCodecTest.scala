package kyo

import java.nio.charset.StandardCharsets
import kyo.internal.ionbinary.IonBinaryFormat

final case class QuestParty(name: String, members: Chunk[String], rating: Int) derives Schema, CanEqual

/** Tests for [[EventLogCodecs]], the single value and metadata codec authority. Covers the schema
  * factory's defaults, opt-in codec choices, construction-time compatibility validation, the
  * private[kyo] authority guard, and the [[EventLogCodecs.bytes]] identity factory.
  */
class EventLogCodecTest extends kyo.test.Test[Any]:

    private val party = QuestParty("Fellowship", Chunk("Frodo", "Sam", "Aragorn"), 5)

    "schema() defaults value and metadata to Ion Binary" in {
        for
            codecs  <- EventLogCodecs.schema[QuestParty]()
            encoded <- EventLogCodecs.encodeValue(codecs.value, party)
            decoded <- EventLogCodecs.decodeValue(codecs.value, encoded)
        yield
            assert(decoded == party)
            assert(encoded.take(IonBinaryFormat.VersionMarker.size).is(IonBinaryFormat.VersionMarker))
            assert(codecs.metadata.codec.isInstanceOf[IonBinary])
    }

    "explicit MsgPack value codec opt-in round-trips" in {
        for
            codecs  <- EventLogCodecs.schema[QuestParty](binary = MsgPack())
            encoded <- EventLogCodecs.encodeValue(codecs.value, party)
            decoded <- EventLogCodecs.decodeValue(codecs.value, encoded)
        yield
            assert(decoded == party)
            // MsgPack frames a 3-field object as a fixmap: the leading byte is in the 0x80-0x8f
            // range (FixMap, low nibble = field count), never the Ion Binary version-marker byte.
            val leading = encoded.toArray(0) & 0xff
            assert(leading >= 0x80 && leading <= 0x8f)
            assert(!encoded.take(1).is(IonBinaryFormat.VersionMarker.take(1)))
    }

    "JSON value codec embeds JSON payloads for the JSONL lane" in {
        // schema[A](json = ...) stores the JSONL-lane descriptor on SchemaValue.json (consumed by the
        // JSONL segment codec later, not by encodeValue, which always frames through
        // SchemaValue.binary). Exercising the JSON embedding itself requires a SchemaValue
        // whose binary field IS the json codec under test.
        val jsonValueCodec = EventLogCodecs.ValueCodec.SchemaValue(summon[Schema[QuestParty]], Json(), Json())
        for
            codecs  <- EventLogCodecs.schema[QuestParty](json = Json())
            encoded <- EventLogCodecs.encodeValue(jsonValueCodec, party)
            decoded <- EventLogCodecs.decodeValue(jsonValueCodec, encoded)
        yield
            codecs.value match
                case EventLogCodecs.ValueCodec.SchemaValue(schema, binary, json) =>
                    assert(schema eq summon[Schema[QuestParty]])
                    assert(binary.isInstanceOf[IonBinary])
                    assert(json.isInstanceOf[Json])
                case other => fail(s"expected SchemaValue, got: $other")
            end match
            val text = new String(encoded.toArray, StandardCharsets.UTF_8)
            assert(text.startsWith("{"))
            assert(text.endsWith("}"))
            assert(text.contains("Fellowship"))
            assert(decoded == party)
        end for
    }

    "incompatible codec choice aborts EventCodecConfigurationError before construction" in {
        for
            failed  <- Abort.run[EventCodecConfigurationError](EventLogCodecs.schema[Int](binary = Protobuf()))
            succeed <- Abort.run[EventCodecConfigurationError](EventLogCodecs.schema[Int]())
        yield
            failed match
                case Result.Failure(error) =>
                    assert(error.reason.nonEmpty)
                    assert(error.getMessage.contains("Incompatible codec configuration"))
                case other =>
                    fail(s"expected EventCodecConfigurationError, got: $other")
            end match
            succeed match
                case Result.Success(_) => ()
                case other             => fail(s"expected the default IonBinary pairing to succeed, got: $other")
    }

    "the schema factory is the only public Codecs[A] constructor, and Event.Definition carries no codec slot" in {
        // EventLogCodecs.Codecs's primary constructor is private[kyo];
        // external.EventLogCodecsAccessibilityFixture lives in a package with no relation to kyo, so
        // its attempted direct construction genuinely fails to type-check (this leaf verifies
        // inaccessibility from a real external package, not merely a caveat about accessibility from
        // within kyo).
        val errors = external.EventLogCodecsAccessibilityFixture.errorMessages
        assert(errors.nonEmpty)
        assert(errors.exists(_.toLowerCase.contains("private")))

        // Positive guard: Event.Definition[A, E] carries exactly eventType/stream/eventId/metadata
        // and no codec-typed member, so reading a `.codec` member off a constructed value fails
        // to type-check.
        val eventDefinitionErrors = external.EventLogCodecsAccessibilityFixture.eventDefinitionErrorMessages
        assert(eventDefinitionErrors.nonEmpty)
        assert(eventDefinitionErrors.exists(_.contains("Definition")))
    }

    "bytes() builds identity Span[Byte] codecs that round-trip payloads verbatim" in {
        val arbitrary = Span(0x00.toByte, 0xff.toByte, 0x7f.toByte, 0x80.toByte)
        for
            codecs       <- EventLogCodecs.bytes()
            encodedBytes <- EventLogCodecs.encodeValue(codecs.value, arbitrary)
            decodedBytes <- EventLogCodecs.decodeValue(codecs.value, encodedBytes)
            encodedEmpty <- EventLogCodecs.encodeValue(codecs.value, Span.empty[Byte])
            decodedEmpty <- EventLogCodecs.decodeValue(codecs.value, encodedEmpty)
        yield
            codecs.value match
                case _: EventLogCodecs.ValueCodec.BytesValue.type => ()
                case other                                        => fail(s"expected BytesValue, got: $other")
            assert(codecs.metadata.codec.isInstanceOf[IonBinary])
            assert(encodedBytes.is(arbitrary))
            assert(decodedBytes.is(arbitrary))
            assert(encodedEmpty.is(Span.empty[Byte]))
            assert(decodedEmpty.is(Span.empty[Byte]))
        end for
    }

end EventLogCodecTest
