package kyo

import Json.JsonSchema
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

    "a record derives as the product of its fields" - {

        type Pet   = Record["name" ~ String & "age" ~ Int & "nick" ~ Maybe[String]]
        type Owner = Record["name" ~ String & "pet" ~ Pet]

        val rex: Pet = "name" ~ "Rex" & "age" ~ 3 & "nick" ~ Maybe("R")

        "its fields are written in the order its type declares them, and read back in any order" in {
            val wire = Json.encode(rex)
            assert(wire == """{"name":"Rex","age":3,"nick":"R"}""", wire)
            assert(Json.decode[Pet]("""{"nick":"R","age":3,"name":"Rex"}""") == Result.succeed(rex))
        }

        "a missing Maybe field reads as Absent, and a missing required field fails naming it" in {
            val absent: Pet = "name" ~ "Rex" & "age" ~ 3 & "nick" ~ Maybe.empty[String]
            assert(Json.decode[Pet]("""{"name":"Rex","age":3}""") == Result.succeed(absent))
            Json.decode[Pet]("""{"name":"Rex"}""") match
                case Result.Failure(e: MissingFieldException) => assert((e.path, e.fieldName) == (Seq.empty, "age"))
                case other                                    => fail(other.toString)
        }

        "a record nested in a record round-trips, and its JSON schema keeps the nested record's properties" in {
            val owner: Owner = "name" ~ "Ann" & "pet" ~ rex
            val wire         = Json.encode(owner)
            assert(wire == """{"name":"Ann","pet":{"name":"Rex","age":3,"nick":"R"}}""", wire)
            assert(Json.decode[Owner](wire) == Result.succeed(owner))
            val pet = JsonSchema.Obj(
                List("name" -> JsonSchema.Str(), "age" -> JsonSchema.Integer(), "nick" -> JsonSchema.Nullable(JsonSchema.Str())),
                List("name", "age")
            )
            assert(JsonSchema.from[Owner] == JsonSchema.Obj(List("name" -> JsonSchema.Str(), "pet" -> pet), List("name", "pet")))
        }

        "the empty record is the empty object" in {
            assert(Json.encode(Record.empty) == "{}")
            assert(Json.decode[Record[Any]]("{}") == Result.succeed(Record.empty))
        }

        "Schema.apply gives the same schema" in {
            assert(Schema[Pet].encodeString[Json](rex) == Json.encode(rex))
        }

        "the same record schema round-trips through Protobuf" in {
            assert(Protobuf.decode[Pet](Protobuf.encode(rex)) == Result.succeed(rex))
        }

        "a record's declared fields support wire renames" in {
            val schema = Schema[Pet].rename("name", "pet_name")
            val wire   = """{"pet_name":"Rex","age":3,"nick":"R"}"""
            assert(schema.encodeString[Json](rex) == wire)
            assert(schema.decodeString[Json](wire) == Result.succeed(rex))
        }

        "a field declared twice is refused" in {
            typeCheckFailure("""kyo.Schema.derived[kyo.Record["a" ~ Int & "a" ~ String]]""")("declared more than once")
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

    "a generic derived given preserves the argument in every case" in {
        val ints                           = summon[Schema[SDGeneric[Int]]]
        val strings                        = summon[Schema[SDGeneric[String]]]
        val intValue: SDGeneric[Int]       = SDGenericValue(1)
        val stringValue: SDGeneric[String] = SDGenericValue("one")
        assert(ints.encodeString[Json](intValue) == """{"SDGenericValue":{"value":1}}""")
        assert(strings.encodeString[Json](stringValue) == """{"SDGenericValue":{"value":"one"}}""")
        assert(ints.decodeString[Json]("""{"SDGenericValue":{"value":1}}""") == Result.succeed(intValue))
        assert(strings.decodeString[Json]("""{"SDGenericValue":{"value":"one"}}""") == Result.succeed(stringValue))
    }

    "a generic given cannot decode fixed Int cases for every type argument" in {
        typeCheckFailure("""
            object FixedCases {
                sealed trait Value[A] derives kyo.Schema { def value: A }
                case class IntValue(value: Int) extends Value[Int]
            }
        """)("not a subtype of")
    }

    "a repeated case parameter must satisfy every argument of its sum" in {
        val schema = Schema.derived[SDRepeated[Int, Int]]
        assert(schema.decodeString[Json]("""{"SDShared":{"value":1}}""") == Result.succeed(SDShared(1)))
        typeCheckFailure("kyo.Schema.derived[kyo.SDRepeated[Int, String]]")("not a subtype of")
    }

    "a generic record keeps its field names without requiring the value's Tag" in {
        def schema[A](using Schema[A]): Schema[Record["label" ~ String & "value" ~ A]] = Schema.derived
        val ints                                                                       = schema[Int]
        val value                                                                      = "label" ~ "count" & "value" ~ 42
        val wire                                                                       = """{"label":"count","value":42}"""
        assert(ints.encodeString[Json](value) == wire)
        assert(ints.decodeString[Json](wire) == Result.succeed(value))
    }

end SchemaDerivedTest

sealed trait SDGeneric[A] derives Schema, CanEqual
case class SDGenericValue[A](value: A) extends SDGeneric[A] derives CanEqual

sealed trait SDRepeated[+A, +B] derives CanEqual
case class SDShared[A](value: A) extends SDRepeated[A, A] derives CanEqual

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
