package kyo

import kyo.schema.rename

class SchemaDerivedTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "a derivation in an opaque type's scope is refused" - {

        "for a field whose declared type is the opaque type's underlying type" in {
            typeCheckFailure(
                """object E { opaque type Code = String; case class Item(code: String, n: Int); given kyo.Schema[Item] = kyo.Schema.derived[Item] }"""
            )("Field 'code' of type")
        }

        "for a field whose type is a type argument applied there" in {
            typeCheckFailure(
                """object E { opaque type Code = String; case class Box[A](value: A); given kyo.Schema[Box[String]] = kyo.Schema.derived[Box[String]] }"""
            )("Field 'value' of type")
        }
    }

    "a product whose field type is an opaque type declared in a sibling object round-trips its renames" - {

        "through Schema.derived" in {
            val value = SDBeside.Item(SDBeside.Code("a"), 1)
            val wire  = Json.encode(value)
            assert(wire == """{"code":"a","count":1}""", wire)
            assert(Json.decode[SDBeside.Item](wire) == Result.succeed(value))
        }

        "through Schema.derivedVia" in {
            val value = SDBeside.Checked(SDBeside.Code("a"), 1)
            val wire  = Json.encode(value)
            assert(wire == """{"code":"a","count":1}""", wire)
            assert(Json.decode[SDBeside.Checked](wire) == Result.succeed(value))
        }

        "through a variant of a derived sum" in {
            val value: SDBeside.Event = SDBeside.Ping(SDBeside.Code("a"), 1)
            val wire                  = Json.encode(value)
            assert(wire == """{"Ping":{"code":"a","count":1}}""", wire)
            assert(Json.decode[SDBeside.Event](wire) == Result.succeed(value))
        }
    }

    "a product whose field type is an opaque type declared in an object of its own round-trips its renames" in {
        val value = SchemaDerivedEnclosing.Item(SchemaDerivedCodes.Code("a"), 1)
        val wire  = Json.encode(value)
        assert(wire == """{"code":"a","count":1}""", wire)
        assert(Json.decode[SchemaDerivedEnclosing.Item](wire) == Result.succeed(value))
    }

    "a generic product" - {

        "a field's @rename reaches the wire" in {
            val value = SDEnvelope(true, 1)
            val wire  = Json.encode(value)
            assert(wire == """{"ok":true,"the_result":1}""", wire)
            assert(Json.decode[SDEnvelope[Int]](wire) == Result.succeed(value))
        }

        "a missing Maybe field of a type parameter decodes as Absent" in {
            assert(Json.decode[SDOptional[Int]]("{}") == Result.succeed(SDOptional[Int](Maybe.empty)))
        }

        "a tuple keeps its positional field names" in {
            assert(Json.encode((1, "a")) == """{"_1":1,"_2":"a"}""")
            assert(Json.decode[(Int, String)]("""{"_1":1,"_2":"a"}""") == Result.succeed((1, "a")))
        }
    }

    "a sum whose cases fix its type parameter derives at a wildcard, a sealed sub-sum with its own parameter included" in {
        val schema              = summon[Schema[SDRequest[?]]]
        val ping: SDRequest[?]  = SDPing
        val get: SDRequest[?]   = SDGet("k")
        val count: SDRequest[?] = SDCount(3)
        val values              = Chunk(ping, get, count)
        val wires               = values.map(schema.encodeString[Json](_))
        assert(
            wires == Chunk(
                """{"op":"SDPing"}""",
                """{"op":"SDQuery","body":{"kind":"SDGet","key":"k"}}""",
                """{"op":"SDQuery","body":{"kind":"SDCount","limit":3}}"""
            ),
            wires.toString
        )
        val decoded = wires.map(schema.decodeString[Json](_))
        assert(
            decoded == values.map(Result.succeed(_)),
            decoded.map(_.failure.map(_.getMessage.linesIterator.toList.takeRight(3))).toString
        )
    }

end SchemaDerivedTest

sealed trait SDRequest[A] derives CanEqual
case object SDPing                   extends SDRequest[Unit]
sealed trait SDQuery[A]              extends SDRequest[A]
final case class SDGet(key: String)  extends SDQuery[String]
final case class SDCount(limit: Int) extends SDQuery[Int]
object SDQuery:
    given Schema[SDQuery[?]] = Schema.derived[SDQuery[?]].discriminator("kind")
object SDRequest:
    given Schema[SDRequest[?]] = Schema.derived[SDRequest[?]].adjacent("op", "body")

case class SDEnvelope[A](ok: Boolean, @rename("the_result") result: A) derives CanEqual, Schema
case class SDOptional[A](value: Maybe[A]) derives CanEqual, Schema

object SchemaDerivedCodes:
    opaque type Code = String
    object Code:
        def apply(s: String): Code = s
        given Schema[Code]         = Schema[String]
end SchemaDerivedCodes

object SDBeside:
    object Codes:
        opaque type Code = String
        object Code:
            def apply(s: String): Code = s
            given Schema[Code]         = Schema[String]
    end Codes
    export Codes.Code

    case class Item(code: Code, @rename("count") n: Int) derives CanEqual
    object Item:
        given Schema[Item] = Schema.derived[Item]

    case class Checked(code: Code, @rename("count") n: Int) derives CanEqual
    object Checked:
        given Schema[Checked] = Schema.derivedVia((code: Code, n: Int) => Checked(code, n))

    sealed trait Event derives CanEqual
    case class Ping(code: Code, @rename("count") n: Int) extends Event
    object Event:
        given Schema[Event] = Schema.derived[Event]
end SDBeside

object SchemaDerivedEnclosing:
    type Code = SchemaDerivedCodes.Code

    case class Item(code: Code, @rename("count") n: Int) derives CanEqual
    object Item:
        given Schema[Item] = Schema.derived[Item]
end SchemaDerivedEnclosing
