package kyo

class SchemaContextualTest extends kyo.test.Test[Any]:

    "a constructor with only a non-Frame contextual parameter can derive a schema" in {
        assert(scala.compiletime.testing.typeChecks("""
            import kyo.*
            case class ContextualValue(value: Int)(using ordering: Ordering[Int]):
                def compare(other: Int): Int = ordering.compare(value, other)
            val schema = Schema.derived[ContextualValue]
        """))
    }

    "retained context comes from derivation scope and is absent from every codec" in {
        given SCContext = SCContext(7)
        val schema      = Schema.derived[SCValue]
        val source      = SCValue(3)(using SCContext(99))
        val codecs      = Seq(summon[Json], summon[Protobuf], summon[MsgPack], summon[Bson], summon[Ion], summon[Yaml])
        codecs.foreach { codec =>
            val decoded = schema.decode(schema.encode(source)(using codec))(using codec).getOrThrow
            assert(decoded.value == 3)
            assert(decoded.context.id == 7)
        }
        assert(Json.encode(source)(using schema) == """{"value":3}""")
        assert(Structure.encode(source)(using schema) == Structure.Value.Record(Chunk("value" -> Structure.Value.Integer(3))))
    }

    "multiple contextual clauses resolve generic arguments" in {
        given SCContext = SCContext(7)
        val schema      = Schema.derived[SCGeneric[Int]]
        val decoded     = Json.decode[SCGeneric[Int]]("""{"value":3}""")(using summon[Json], schema).getOrThrow
        assert(decoded.value == 3)
        assert(decoded.context.id == 7)
        assert(decoded.ordering.compare(1, 2) == -1)
        val built = Builder[SCGeneric[Int]].value(4).result
        assert(built.value == 4)
        assert(built.context.id == 7)
        assert(built.ordering.compare(1, 2) == -1)
    }

    "a missing contextual given fails derivation" in {
        typeCheckFailure("kyo.Schema.derived[kyo.SCValue]")("SCContext")
    }

    "focus setters resolve context at their expansion site" in {
        given SCContext = SCContext(7)
        val schema      = Schema[SCValue]
        val source      = SCValue(3)(using SCContext(99))
        val direct      = schema.focus(_.value).set(source, 4)
        val renamed     = schema.rename("value", "number").focus(_.number).set(source, 5)
        assert(direct.value == 4)
        assert(direct.context.id == 7)
        assert(renamed.value == 5)
        assert(renamed.context.id == 7)
    }

    "builders and converters resolve context without copying it from the source" in {
        given SCContext = SCContext(7)
        val built       = Builder[SCValue].value(3).result
        val converted   = Convert[SCPlain, SCValue].apply(SCPlain(4))
        val transformed = Schema[SCPlain].rename("value", "number").convert[SCRenamed](SCPlain(5))
        assert(built.value == 3)
        assert(built.context.id == 7)
        assert(converted.value == 4)
        assert(converted.context.id == 7)
        assert(transformed.number == 5)
        assert(transformed.context.id == 7)
    }

    "Frame context uses the decode site and never creates a reserved wire key" in {
        val schema = Schema.derived[SCFramed]
        val source = SCFramed(3)
        val site   = summon[Frame]
        assert(Json.encode(source)(using schema) == """{"frame":3}""")
        Seq(summon[Json], summon[Protobuf], summon[MsgPack], summon[Bson], summon[Ion], summon[Yaml]).foreach { codec =>
            val decoded = schema.decode(schema.encode(source)(using codec))(using codec, site).getOrThrow
            assert(decoded.frame == 3)
            assert(decoded.context == site)
            assert(decoded.other == site)
        }
        succeed
    }

    "an ordinary Frame field is serialized while its contextual Frame comes from the reader" in {
        val schema = Schema.derived[SCFrameField]
        val source = SCFrameField(summon[Frame])
        val site   = summon[Frame]
        val wire   = Json.encode(source)(using schema)
        assert(wire == s"""{"origin":${Json.encode(source.origin)}}""")
        val decoded = Json.decode[SCFrameField](wire)(using summon[Json], schema, site).getOrThrow
        assert(decoded.origin == source.origin)
        assert(decoded.context == site)
    }

    "a contextual catch-all receives both its given and decode frame" in {
        given SCContext = SCContext(7)
        val schema      = Schema.derived[SCDynamic]
        val site        = summon[Frame]
        Json.decode[SCDynamic]("""{"future":{"code":3}}""")(using summon[Json], schema, site).getOrThrow match
            case value: SCUnknown =>
                assert(value.tag == "future")
                assert(value.payload == Structure.Value.Record(Chunk("code" -> Structure.Value.Integer(3))))
                assert(value.context.id == 7)
                assert(value.frame == site)
            case other => fail(s"expected the catch-all, got $other")
        end match
    }

end SchemaContextualTest

final case class SCContext(id: Int)
final case class SCValue(value: Int)(using val context: SCContext)
final case class SCGeneric[A](value: A)(using val ordering: Ordering[A])(using val context: SCContext)
final case class SCPlain(value: Int) derives Schema
final case class SCRenamed(number: Int)(using val context: SCContext)
final case class SCFramed(frame: Int)(using val context: Frame):
    val other: Frame = context
final case class SCFrameField(origin: Frame)(using val context: Frame)

sealed trait SCDynamic
final case class SCKnown(code: Int) extends SCDynamic
@kyo.schema.catchAll()
final case class SCUnknown(tag: String, payload: Structure.Value)(using val context: SCContext, val frame: Frame) extends SCDynamic
