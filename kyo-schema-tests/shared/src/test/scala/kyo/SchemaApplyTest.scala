package kyo

class SchemaApplyTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "a builder-configured given in a companion nested in an object" - {

        "for a case class, compiles and applies its builder" in {
            val schema = summon[Schema[SchemaApplyNested.Configured]]
            val value  = SchemaApplyNested.Configured(1, "b")
            val wire   = schema.encodeString[Json](value)
            assert(wire == """{"x":1,"b":"b"}""", wire)
            assert(schema.decodeString[Json](wire) == Result.succeed(value))
        }

        "for a sealed trait, compiles and applies its builder" in {
            val schema                         = summon[Schema[SchemaApplyNested.Shape]]
            val value: SchemaApplyNested.Shape = SchemaApplyNested.Circle(1)
            val wire                           = schema.encodeString[Json](value)
            assert(wire == """{"t":"Circle","radius":1}""", wire)
            assert(schema.decodeString[Json](wire) == Result.succeed(value))
        }

        "for a structural type, compiles and carries its fields" in {
            assert(SchemaApplyNestedOpaque.Structural.schema.fieldNames == Set("a", "b"))
        }

        "for an opaque type over String, built from Schema[String], compiles and encodes" in {
            import SchemaApplyNestedString.Inner.Id
            assert(Json.encode(Id("a")) == "\"a\"")
            assert(Json.decode[Id]("\"a\"") == Result.succeed(Id("a")))
        }

        "for a transform of a case class that has no given, with a rename chained, compiles and encodes" in {
            import SchemaApplyNestedPlain.*
            assert(Json.encode(Wrapped(Plain(1))) == """{"w":1}""")
            assert(Json.decode[Wrapped]("""{"w":1}""") == Result.succeed(Wrapped(Plain(1))))
        }
    }

    "Schema[A] is the given Schema[A] where one exists" - {

        "Schema[Structure.Value] reads a plain JSON object" in {
            val result = Schema[Structure.Value].decodeString[Json]("""{"x":1}""")
            assert(result == Result.succeed(Structure.Value.Record(Chunk("x" -> Structure.Value.Integer(1)))), result.toString)
        }

        "Schema[String] encodes and decodes" in {
            assert(Schema[String].encodeString[Json]("x") == "\"x\"")
            assert(Schema[String].decodeString[Json]("\"x\"") == Result.succeed("x"))
        }

        "Schema[Int] encodes and decodes" in {
            assert(Schema[Int].encodeString[Json](7) == "7")
            assert(Schema[Int].decodeString[Json]("7") == Result.succeed(7))
        }

        "Schema[Int] keeps the type itself as its Focused type" in {
            typeCheck("val s: kyo.Schema[Int] { type Focused = Int } = kyo.Schema[Int]")
        }

        "the README's custom types, built on Schema[String], encode and decode" in {
            import SchemaApplyGivens.*
            val contact = SAContact(SchemaApplyCustomTypes.SAEmail("a@b.c"), SchemaApplyCustomTypes.SAUsername("Ann"))
            val wire    = Json.encode(contact)
            assert(wire == """{"email":"a@b.c","user":"ann"}""", wire)
            assert(Json.decode[SAContact](wire) == Result.succeed(contact))
        }

        "a builder-configured companion given is what Schema[A] returns" in {
            import SchemaApplyGivens.*
            assert(Schema[SAConfigured].encodeString[Json](SAConfigured(1)) == """{"the_value":1}""")
            assert(Schema[SAConfigured].decodeString[Json]("""{"the_value":1}""") == Result.succeed(SAConfigured(1)))
        }

        "an imported given whose static type refines Focused carries that Focused" in {
            import SchemaApplyGivens.*
            import SchemaApplyGivens.SARefinedGiven.given
            typeCheck(
                """val s: kyo.Schema[kyo.SchemaApplyGivens.SARefined] { type Focused = "id" ~ Int } = kyo.Schema[kyo.SchemaApplyGivens.SARefined]"""
            )
            assert(Schema[SARefined].focus(_.id).get(SARefined(1, "s")) == Maybe(1))
        }

        "a field the refined given dropped cannot be focused" in {
            import SchemaApplyGivens.SARefinedGiven.given
            typeCheckFailure("kyo.Schema[kyo.SchemaApplyGivens.SARefined].focus(_.secret)")("Field 'secret' not found")
        }

        "a companion given with a refined type is ambiguous with Schema.derived, and Schema[A] says so" in {
            typeCheckFailure("kyo.Schema[kyo.SchemaApplyGivens.SACompanionRefined]")("both given instance")
        }

        "a given without a Focused refinement does not advertise the derived fields" in {
            typeCheckFailure("kyo.Schema[kyo.SchemaApplyGivens.SAConfigured].focus(_.value)")("the schema's Focused type is abstract")
        }

        "a given defined through Schema[A] of its own type derives instead of referring to itself" - {

            "directly" in {
                assert(Json.encode(SchemaApplyGivens.SASelfDefined(1, "s")) == """{"id":1}""")
            }

            "through an intermediate val" in {
                assert(Json.encode(SchemaApplyGivens.SAViaVal(1, "s")) == """{"id":1}""")
            }

            "through a method" in {
                assert(Json.encode(SchemaApplyGivens.SAViaDef(1, "s")) == """{"id":1}""")
            }
        }
    }

end SchemaApplyTest

object SchemaApplyNested:
    case class Configured(a: Int, b: String) derives CanEqual
    object Configured:
        given Schema[Configured] = Schema[Configured].rename("a", "x")

    sealed trait Shape derives CanEqual
    case class Circle(radius: Int) extends Shape derives CanEqual
    object Shape:
        given Schema[Shape] = Schema[Shape].discriminator("t")

end SchemaApplyNested

object SchemaApplyNestedOpaque:
    opaque type Structural = Boolean
    object Structural:
        type Shape = Record.~["a", Int] & Record.~["b", String]
        val schema: Schema[Shape] { type Focused = Shape } = Schema[Shape]
end SchemaApplyNestedOpaque

object SchemaApplyNestedString:
    object Inner:
        opaque type Id = String
        object Id:
            def apply(s: String): Id = s
            given Schema[Id]         = Schema[String]
    end Inner
end SchemaApplyNestedString

object SchemaApplyNestedPlain:
    case class Plain(v: Int) derives CanEqual
    case class Wrapped(p: Plain) derives CanEqual
    object Wrapped:
        given Schema[Wrapped] = Schema[Plain].rename("v", "w").transform(Wrapped(_))(_.p)
end SchemaApplyNestedPlain

// README "Custom Types", as written there. Inside the scope that declares an opaque type over String, Tag[String] is not derivable,
// so the types live in an object of their own.
object SchemaApplyCustomTypes:
    opaque type SAEmail = String
    object SAEmail:
        def apply(s: String): SAEmail = s
        given Schema[SAEmail]         = Schema[String]

    opaque type SAUsername = String
    object SAUsername:
        def apply(s: String): SAUsername = s.toLowerCase
        given Schema[SAUsername]         =
            Schema[String].transform[SAUsername](SAUsername(_))(identity)
    end SAUsername
end SchemaApplyCustomTypes

object SchemaApplyGivens:
    import SchemaApplyCustomTypes.*

    case class SAContact(email: SAEmail, user: SAUsername) derives CanEqual, Schema

    case class SAConfigured(value: Int) derives CanEqual
    object SAConfigured:
        given Schema[SAConfigured] = Schema[SAConfigured].rename("value", "the_value")

    case class SARefined(id: Int, secret: String) derives CanEqual
    object SARefinedGiven:
        given refined: (Schema[SARefined] { type Focused = "id" ~ Int }) = Schema[SARefined].drop("secret")

    case class SACompanionRefined(id: Int, secret: String) derives CanEqual
    object SACompanionRefined:
        given refined: (Schema[SACompanionRefined] { type Focused = "id" ~ Int }) =
            Schema.derived[SACompanionRefined].asInstanceOf[Schema[SACompanionRefined] { type Focused = "id" ~ Int }]

    case class SASelfDefined(id: Int, secret: String) derives CanEqual
    object SASelfDefined:
        given Schema[SASelfDefined] = Schema[SASelfDefined].drop("secret")

    case class SAViaVal(id: Int, secret: String) derives CanEqual
    object SAViaVal:
        private val base       = Schema[SAViaVal].drop("secret")
        given Schema[SAViaVal] = base

    case class SAViaDef(id: Int, secret: String) derives CanEqual
    object SAViaDef:
        private def base: Schema[SAViaDef] = Schema[SAViaDef].drop("secret")
        given Schema[SAViaDef]             = base
end SchemaApplyGivens
