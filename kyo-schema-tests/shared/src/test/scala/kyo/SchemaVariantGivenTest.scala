package kyo

class SchemaVariantGivenTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    private def assertRoundTrip[A](schema: Schema[A], value: A, expected: String)(using Frame, kyo.test.AssertScope): Unit =
        val wire = schema.encodeString[Json](value)
        assert(wire == expected, wire)
        val decoded = schema.decodeString[Json](wire)
        assert(decoded == Result.succeed(value), s"${schema.representation}: $decoded")
    end assertRoundTrip

    "recursion through a variant's own given" - {

        "a recursive sum whose variants derive Schema" in {
            val tree: VGTree = VGNode(List(VGLeaf(1), VGNode(Nil)))
            assertRoundTrip(
                summon[Schema[VGTree]],
                tree,
                """{"VGNode":{"children":[{"VGLeaf":{"v":1}},{"VGNode":{"children":[]}}]}}"""
            )
        }

        "a recursive sum derived at the use site, whose variant's given configures a builder" in {
            val tree: VGSnakeTree = VGSnakeNode(List(VGSnakeLeaf(1), VGSnakeNode(Nil)))
            assertRoundTrip(
                Schema.derived[VGSnakeTree],
                tree,
                """{"VGSnakeNode":{"child_nodes":[{"VGSnakeLeaf":{"leafValue":1}},{"VGSnakeNode":{"child_nodes":[]}}]}}"""
            )
        }

        "a sum with a sealed intermediate" in {
            assertRoundTrip(summon[Schema[VGOuter]], VGInnerCase(1): VGOuter, """{"VGInner":{"VGInnerCase":{"x":1}}}""")
            assertRoundTrip(summon[Schema[VGOuter]], VGOuterCase(2): VGOuter, """{"VGOuterCase":{"y":2}}""")
        }
    }

    "a variant's builder-configured given under every representation" in {
        val joined: SWGEvent = SWGUserJoined(1, "t")
        val base             = Schema.derived[SWGEvent]
        Chunk(
            base                    -> """{"type":"SWGUserJoined","user_id":1,"chat_title":"t"}""",
            base.adjacent("t", "c") -> """{"t":"SWGUserJoined","c":{"user_id":1,"chat_title":"t"}}""",
            base.tupleTagged        -> """["SWGUserJoined",{"user_id":1,"chat_title":"t"}]""",
            base.tupleFlat          -> """["SWGUserJoined",1,"t"]""",
            base.untagged           -> """{"user_id":1,"chat_title":"t"}"""
        ).foreach((schema, expected) => assertRoundTrip(schema, joined, expected))
    }

    "a variant's derivedVia given rejects through every tagged representation" in {
        val base = Schema.derived[SWGPay]
        Chunk(
            base                    -> """{"type":"SWGCard","last4":"12"}""",
            base.adjacent("t", "c") -> """{"t":"SWGCard","c":{"last4":"12"}}""",
            base.tupleTagged        -> """["SWGCard",{"last4":"12"}]""",
            base.tupleFlat          -> """["SWGCard","12"]"""
        ).foreach { (schema, wire) =>
            val result = schema.decodeString[Json](wire)
            assert(result.failure.exists(_.isInstanceOf[ConstructorRejectedException]), s"${schema.representation}: $result")
        }
    }

    "under untagged, a variant's rejection is no match, and the next variant is tried" in {
        val schema = Schema.derived[SWGPay].untagged
        val result = schema.decodeString[Json]("""{"last4":"12"}""")
        assert(result.failure.exists(_.isInstanceOf[NoVariantMatchException]), result.toString)
        assert(schema.decodeString[Json]("""{"last4":"1234"}""") == Result.succeed(SWGCard("1234")))
        assert(schema.decodeString[Json]("""{"amount":5}""") == Result.succeed(SWGCash(5)))
    }

    "a variant with a private constructor and a derivedVia given, under every representation" in {
        val token: SWGTokenized = SWGToken.make("abc").getOrElse(fail("valid token rejected"))
        val base                = Schema.derived[SWGTokenized]
        Chunk(
            base                    -> """{"SWGToken":{"value":"abc"}}""",
            base.discriminator("t") -> """{"t":"SWGToken","value":"abc"}""",
            base.adjacent("t", "c") -> """{"t":"SWGToken","c":{"value":"abc"}}""",
            base.tupleFlat          -> """["SWGToken","abc"]"""
        ).foreach((schema, expected) => assertRoundTrip(schema, token, expected))
    }

    "a sealed intermediate with a discriminator of its own, under a discriminated root" - {
        val root = summon[Schema[VGNRoot]]

        "round-trips each nested variant with both tags beside its fields" in {
            assertRoundTrip(root, VGNCard("2", "v"): VGNRoot, """{"type":"invoke","name":"card","id":"2","value":"v"}""")
            assertRoundTrip(root, VGNMessage("hi"): VGNRoot, """{"type":"message","text":"hi"}""")
        }

        "decodes a nested variant whatever the order of its keys" in {
            assert(root.decodeString[Json]("""{"value":"v","name":"card","id":"2","type":"invoke"}""") == Result.succeed(VGNCard("2", "v")))
            assert(root.decodeString[Json]("""{"id":"2","type":"invoke","value":"v","name":"card"}""") == Result.succeed(VGNCard("2", "v")))
        }

        "decodes into the nested catch-all, which receives the object without the root's tag" in {
            val decoded = root.decodeString[Json]("""{"type":"invoke","name":"task/fetch","id":"3"}""")
            val payload = Json.decode[Structure.Value]("""{"name":"task/fetch","id":"3"}""").getOrThrow
            assert(decoded == Result.succeed(VGNOtherCall("task/fetch", payload)))
            assert(root.encodeString[Json](decoded.getOrThrow) == """{"type":"invoke","name":"task/fetch","id":"3"}""")
        }

        "the root's own catch-all still receives an unknown root tag" in {
            val decoded = root.decodeString[Json]("""{"type":"typing","id":"4"}""")
            val whole   = Json.decode[Structure.Value]("""{"type":"typing","id":"4"}""").getOrThrow
            assert(decoded == Result.succeed(VGNUnknown("typing", whole)))
        }

        "a nested catch-all writes the root's tag once, even when its payload holds that key" in {
            val payload = Json.decode[Structure.Value]("""{"type":"stale","name":"task/fetch","id":"3"}""").getOrThrow
            assert(root.encodeString[Json](VGNOtherCall("task/fetch", payload)) == """{"type":"invoke","name":"task/fetch","id":"3"}""")
        }

        "a nested variant missing its own tag fails naming that tag" in {
            root.decodeString[Json]("""{"type":"invoke","id":"3"}""") match
                case Result.Failure(e: MissingFieldException) => assert(e.fieldName == "name", e.fieldName)
                case other                                    => fail(s"expected a MissingFieldException, got $other")
        }

        "without a catch-all at either level, a nested variant round-trips and an unknown nested tag fails" in {
            val plain = summon[Schema[VGPRoot]]
            assertRoundTrip(plain, VGPCard("v"): VGPRoot, """{"type":"invoke","name":"card","value":"v"}""")
            assert(plain.decodeString[Json]("""{"name":"card","value":"v","type":"invoke"}""") == Result.succeed(VGPCard("v")))
            assert(plain.decodeString[Json]("""{"type":"invoke","name":"other","value":"v"}""").isFailure)
        }

        "round-trips on every self-describing codec that writes a discriminator" in {
            val card: VGNRoot = VGNCard("2", "v")
            assert(root.decodeString[Yaml](root.encodeString[Yaml](card)) == Result.succeed(card))
            assert(root.decodeString[Ion](root.encodeString[Ion](card)) == Result.succeed(card))
            assert(root.decode[MsgPack](root.encode[MsgPack](card)) == Result.succeed(card))
            assert(root.decode[Bson](root.encode[Bson](card)) == Result.succeed(card))
        }
    }

    "an ambiguous given for a variant is a compile error naming the variant" in {
        typeCheckFailure(
            """{
                given a: kyo.Schema[kyo.VGAmbigCase] = kyo.Schema.derived[kyo.VGAmbigCase].renameAllFields(kyo.Schema.NameCase.SnakeCase)
                given b: kyo.Schema[kyo.VGAmbigCase] = kyo.Schema.derived[kyo.VGAmbigCase].renameAllFields(kyo.Schema.NameCase.KebabCase)
                kyo.Schema.derived[kyo.VGAmbig]
            }"""
        )("the given Schema[kyo.VGAmbigCase] for its variant VGAmbigCase is ambiguous")
    }

end SchemaVariantGivenTest

sealed trait VGTree derives CanEqual, Schema
case class VGNode(children: List[VGTree]) extends VGTree derives CanEqual, Schema
case class VGLeaf(v: Int)                 extends VGTree derives CanEqual, Schema

sealed trait VGSnakeTree derives CanEqual
case class VGSnakeNode(childNodes: List[VGSnakeTree]) extends VGSnakeTree derives CanEqual
object VGSnakeNode:
    given Schema[VGSnakeNode] = Schema.derived[VGSnakeNode].renameAllFields(Schema.NameCase.SnakeCase)
case class VGSnakeLeaf(leafValue: Int) extends VGSnakeTree derives CanEqual, Schema

sealed trait VGOuter derives CanEqual, Schema
sealed trait VGInner           extends VGOuter derives CanEqual, Schema
case class VGInnerCase(x: Int) extends VGInner derives CanEqual
case class VGOuterCase(y: Int) extends VGOuter derives CanEqual

@kyo.schema.discriminator("type")
sealed trait VGNRoot derives CanEqual, Schema
@kyo.schema.rename("message")
final case class VGNMessage(text: String) extends VGNRoot derives CanEqual
@kyo.schema.catchAll()
final case class VGNUnknown(tag: String, payload: Structure.Value) extends VGNRoot derives CanEqual

@kyo.schema.rename("invoke")
sealed trait VGNCall extends VGNRoot derives CanEqual
object VGNCall:
    given Schema[VGNCall] = Schema[VGNCall].discriminator("name").catchAll("VGNOtherCall")
@kyo.schema.rename("card")
final case class VGNCard(id: String, value: String)                   extends VGNCall derives CanEqual
final case class VGNOtherCall(name: String, payload: Structure.Value) extends VGNCall derives CanEqual

@kyo.schema.discriminator("type")
sealed trait VGPRoot derives CanEqual, Schema
@kyo.schema.rename("message")
final case class VGPMessage(text: String) extends VGPRoot derives CanEqual
@kyo.schema.rename("invoke")
sealed trait VGPCall extends VGPRoot derives CanEqual
object VGPCall:
    given Schema[VGPCall] = Schema[VGPCall].discriminator("name")
@kyo.schema.rename("card")
final case class VGPCard(value: String) extends VGPCall derives CanEqual

sealed trait VGAmbig derives CanEqual
case class VGAmbigCase(aB: Int) extends VGAmbig derives CanEqual
