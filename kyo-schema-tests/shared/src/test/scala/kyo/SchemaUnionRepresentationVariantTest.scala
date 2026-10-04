package kyo

class SchemaUnionRepresentationVariantTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    // =========================================================================
    // Group: a variant's own field annotations
    // =========================================================================

    "a variant field's @rename sets its wire key under a discriminator, on encode and decode" in {
        val value: SWRRx = SWRCustomEmoji("e1")
        val wire         = """{"type":"custom_emoji","custom_emoji_id":"e1"}"""
        assert(Json.encode(value) == wire)
        assert(Json.decode[SWRRx](wire) == Result.succeed(value))
        assert(Json.decode[SWRRx]("""{"type":"custom_emoji","id":"e1"}""").isFailure)
    }

    "a variant field's @rename, @alias and @transform all apply together" in {
        val value: SWRElement = SWRButton("go", "a1")
        assert(Json.encode(value) == """{"type":"button","text":"GO","action_id":"a1"}""")
        assert(Json.decode[SWRElement]("""{"type":"button","text":"GO","action_id":"a1","hint_text":"h"}""") ==
            Result.succeed(SWRButton("go", "a1", Present("h"))))
    }

    "a variant field's @rename applies under the external, untagged and adjacent representations" in {
        assert(Json.encode(SWRExternalCase("e1"): SWRExternal) == """{"SWRExternalCase":{"custom_emoji_id":"e1"}}""")
        assert(Json.decode[SWRExternal]("""{"SWRExternalCase":{"custom_emoji_id":"e1"}}""") == Result.succeed(SWRExternalCase("e1")))
        assert(Json.encode(SWRUntaggedCase("e1"): SWRUntagged) == """{"custom_emoji_id":"e1"}""")
        assert(Json.decode[SWRUntagged]("""{"custom_emoji_id":"e1"}""") == Result.succeed(SWRUntaggedCase("e1")))
        assert(Json.encode(SWRAdjacentCase("e1"): SWRAdjacent) == """{"type":"SWRAdjacentCase","content":{"custom_emoji_id":"e1"}}""")
        assert(Json.decode[SWRAdjacent]("""{"type":"SWRAdjacentCase","content":{"custom_emoji_id":"e1"}}""") ==
            Result.succeed(SWRAdjacentCase("e1")))
    }

    "a variant's renamed field keeps its declaration position under tupleFlat and tupleTagged" in {
        val point: SWPShape = SWPPoint(1, 2, 3)
        Chunk(
            Schema[SWPShape].tupleFlat   -> """["SWPPoint",1,2,3]""",
            Schema[SWPShape].tupleTagged -> """["SWPPoint",{"x_coord":1,"y":2,"z":3}]"""
        ).foreach { (schema, expected) =>
            val wire = schema.encodeString[Json](point)
            assert(wire == expected, wire)
            val decoded = schema.decodeString[Json](wire)
            assert(decoded == Result.succeed(point), s"${schema.representation}: $decoded")
        }
    }

    "a variant's renamed field keeps its declaration position under the keyed representations" in {
        val point: SWPShape = SWPPoint(1, 2, 3)
        Chunk(
            Schema[SWPShape]                    -> """{"SWPPoint":{"x_coord":1,"y":2,"z":3}}""",
            Schema[SWPShape].discriminator("t") -> """{"t":"SWPPoint","x_coord":1,"y":2,"z":3}""",
            Schema[SWPShape].adjacent("t", "c") -> """{"t":"SWPPoint","c":{"x_coord":1,"y":2,"z":3}}""",
            Schema[SWPShape].untagged           -> """{"x_coord":1,"y":2,"z":3}"""
        ).foreach { (schema, expected) =>
            val wire = schema.encodeString[Json](point)
            assert(wire == expected, wire)
            val decoded = schema.decodeString[Json](wire)
            assert(decoded == Result.succeed(point), s"${schema.representation}: $decoded")
        }
    }

    "a variant's renamed field round-trips on Protobuf under every object-shaped representation" in {
        val point: SWPShape = SWPPoint(1, 2, 3)
        Chunk(
            Schema[SWPShape],
            Schema[SWPShape].discriminator("t"),
            Schema[SWPShape].adjacent("t", "c")
        ).foreach { schema =>
            val decoded = schema.decode[Protobuf](schema.encode[Protobuf](point))
            assert(decoded == Result.succeed(point), s"${schema.representation}: $decoded")
        }
    }

    "a variant's renamed field is numbered by its wire name on Protobuf; pinning the old number reads older data" in {
        val older = Schema[SWPOldShape].encode[Protobuf](SWPOldPoint(1, 2, 3))
        // the older bytes carry the field under the Scala name's number, so it reads as absent: proto3's default, 0
        val renamed = Schema[SWPShape].discriminator("type").decode[Protobuf](older)
        assert(renamed == Result.succeed(SWPPoint(0, 2, 3)), renamed.toString)
        assert(kyo.internal.CodecMacro.fieldId("x") == 701810)
        val pinned = Schema[SWPPinnedShape].decode[Protobuf](older)
        assert(pinned == Result.succeed(SWPPinnedPoint(1, 2, 3)), pinned.toString)
    }

    "a variant's own given, configured with a builder, is the schema the sum uses" in {
        val value: SWGEvent = SWGUserJoined(1, "t")
        val wire            = """{"type":"SWGUserJoined","user_id":1,"chat_title":"t"}"""
        assert(Json.encode(value) == wire)
        assert(Json.decode[SWGEvent](wire) == Result.succeed(value))
    }

    "a variant's derivedVia given rejects through the sum as it does standalone" in {
        val result = Json.decode[SWGPay]("""{"type":"SWGCard","last4":"12"}""")
        assert(result.failure.exists(_.isInstanceOf[ConstructorRejectedException]), result.toString)
        assert(Json.decode[SWGPay]("""{"type":"SWGCard","last4":"1234"}""") == Result.succeed(SWGCard("1234")))
        assert(Json.decode[SWGPay]("""{"type":"SWGCash","amount":5}""") == Result.succeed(SWGCash(5)))
    }

    "a sum derives when a variant has a private constructor and a derivedVia given" in {
        val token: SWGTokenized = SWGToken.make("abc") match
            case Result.Success(t) => t
            case other             => fail(s"expected a token, got $other")
        val wire = Json.encode(token)
        assert(wire == """{"SWGToken":{"value":"abc"}}""")
        assert(Json.decode[SWGTokenized](wire) == Result.succeed(token))
        assert(Json.decode[SWGTokenized]("""{"SWGToken":{"value":""}}""").failure.exists(_.isInstanceOf[ConstructorRejectedException]))
    }

    "transformField on a variant path is rejected, pointing at the variant's own schema" in {
        typeCheckFailure("""Schema[kyo.SWRRx].transformField(_.SWRCustomEmoji.id)((v, w) => w.string(v))(r => r.string())""")(
            "Apply .transformField to a Schema of a specific case class variant instead"
        )
    }

    "transformFieldWrite and transformFieldRead on a variant path are rejected, pointing at the variant's own schema" in {
        typeCheckFailure("""Schema[kyo.SWRRx].transformFieldWrite(_.SWRCustomEmoji.id)((v, w) => w.string(v))""")(
            "Apply .transformFieldWrite to a Schema of a specific case class variant instead"
        )
        typeCheckFailure("""Schema[kyo.SWRRx].transformFieldRead(_.SWRCustomEmoji.id)(r => r.string())""")(
            "Apply .transformFieldRead to a Schema of a specific case class variant instead"
        )
    }

    "a field transform on a path that crosses a sum is rejected" in {
        typeCheckFailure("""Schema[kyo.SWFHolder].transformField(_.shape.SSRCircle.radius)((v, w) => w.double(v))(r => r.double())""")(
            "Schema.transformField takes a field of the schema's own type; `_.shape.SSRCircle.radius` names a nested field. " +
                "Apply .transformField to a Schema of the nested field's type instead."
        )
    }

    "a field transform on a multi-segment path is rejected, for each of the three builders" in {
        typeCheckFailure("""Schema[kyo.MTPersonAddr].transformField(_.address.city)((v, w) => w.string(v))(r => r.string())""")(
            "Schema.transformField takes a field of the schema's own type; `_.address.city` names a nested field."
        )
        typeCheckFailure("""Schema[kyo.MTPersonAddr].transformFieldWrite(_.address.city)((v, w) => w.string(v))""")(
            "Schema.transformFieldWrite takes a field of the schema's own type; `_.address.city` names a nested field."
        )
        typeCheckFailure("""Schema[kyo.MTPersonAddr].transformFieldRead(_.address.city)(r => r.string())""")(
            "Schema.transformFieldRead takes a field of the schema's own type; `_.address.city` names a nested field."
        )
    }

    "a variant field's own @omit applies under every representation and decodes back" in {
        val bag: SWOBag = SWOItems(Nil, "a")
        Chunk(
            Schema[SWOBag]                    -> """{"SWOItems":{"label":"a"}}""",
            Schema[SWOBag].discriminator("t") -> """{"t":"SWOItems","label":"a"}""",
            Schema[SWOBag].adjacent("t", "c") -> """{"t":"SWOItems","c":{"label":"a"}}""",
            Schema[SWOBag].tupleTagged        -> """["SWOItems",{"label":"a"}]""",
            Schema[SWOBag].untagged           -> """{"label":"a"}"""
        ).foreach { (schema, expected) =>
            val wire = schema.encodeString[Json](bag)
            assert(wire == expected, wire)
            val decoded = schema.decodeString[Json](wire)
            assert(decoded == Result.succeed(bag), s"${schema.representation}: $decoded")
        }
    }

    "an omitted or absent variant field keeps its position under tupleFlat" in {
        val schema = Schema[SWOBag].tupleFlat
        Chunk[(SWOBag, String)](
            SWOItems(Nil, "a")        -> """["SWOItems",[],"a"]""",
            SWONote(Maybe.empty, "a") -> """["SWONote",null,"a"]""",
            SWONote(Maybe("n"), "a")  -> """["SWONote","n","a"]"""
        ).foreach { (bag, expected) =>
            val wire = schema.encodeString[Json](bag)
            assert(wire == expected, wire)
            val decoded = schema.decodeString[Json](wire)
            assert(decoded == Result.succeed(bag), decoded.toString)
        }
    }

    "an absent field nested in a tupleFlat payload keeps its keyed form" in {
        val schema = Schema[SWONested].tupleFlat
        val value  = SWONestedCase(SWONote(Maybe.empty, "a"))
        val wire   = schema.encodeString[Json](value)
        assert(wire == """["SWONestedCase",{"SWONote":{"label":"a"}}]""", wire)
        assert(schema.decodeString[Json](wire) == Result.succeed(value))
    }

    "a variant field whose wire key is the discriminator key is refused, never dropped" - {

        "under @discriminator, when the sum is derived" in {
            val errors = scala.compiletime.testing.typeCheckErrors(
                """{
                    @kyo.schema.discriminator("type") sealed trait Clash
                    case class ClashCase(`type`: String, x: Int) extends Clash
                    kyo.Schema.derived[Clash]
                }"""
            )
            assert(errors.exists(e => e.message.contains("ClashCase") && e.message.contains("type")), errors.map(_.message).toString)
        }

        "under the discriminator builder, before anything is written" in {
            val schema              = Schema[SSRTypeField].discriminator("type")
            val value: SSRTypeField = SSRTypeFieldCase("a", 1)
            Result.catching[FieldNameCollisionException](schema.encodeString[Json](value)) match
                case Result.Failure(_) => succeed("the collision is refused")
                case other             => fail(s"the variant's `type` value was not refused: $other")
        }
    }

end SchemaUnionRepresentationVariantTest
