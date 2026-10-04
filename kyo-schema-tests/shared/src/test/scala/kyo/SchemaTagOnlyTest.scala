package kyo

import kyo.schema.*

class SchemaTagOnlyTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "the tagOnly builder" - {

        "writes each variant as its wire name and reads it back" in {
            val schema = Schema[STOColor].tagOnly
            assert(schema.encodeString[Json](STOColor.Green) == "\"Green\"")
            assert(schema.decodeString[Json]("\"Blue\"") == Result.succeed(STOColor.Blue))
        }

        "takes variant names from variantNames and renameAllVariants" in {
            val named = Schema[STOColor].tagOnly.variantNames("Red" -> "crimson")
            assert(named.encodeString[Json](STOColor.Red) == "\"crimson\"")
            assert(named.decodeString[Json]("\"crimson\"") == Result.succeed(STOColor.Red))
            val cased = Schema[STOLevelName].tagOnly.renameAllVariants(Schema.NameCase.SnakeCase)
            assert(cased.encodeString[Json](STOLevelName.VeryHigh) == "\"very_high\"")
            assert(cased.decodeString[Json]("\"very_high\"") == Result.succeed(STOLevelName.VeryHigh))
        }

        "accepts a variantAlias on decode" in {
            val schema = Schema[STOColor].tagOnly.variantAlias("Red", "rouge")
            assert(schema.decodeString[Json]("\"rouge\"") == Result.succeed(STOColor.Red))
            assert(schema.encodeString[Json](STOColor.Red) == "\"Red\"")
        }

        "rejects a sum with a field-bearing variant at compile time" in {
            typeCheckFailure("kyo.Schema[kyo.STOWithFields].tagOnly")("STOBox")
        }
    }

    "the @tagOnly() annotation" - {

        "on a sealed trait of case objects" in {
            val value: STOStatus = STOActive
            assert(Json.encode(value) == "\"STOActive\"")
            assert(Json.decode[STOStatus]("\"STOActive\"") == Result.succeed(STOActive))
        }

        "on an enum" in {
            assert(Json.encode(STOLevel.High) == "\"High\"")
            assert(Json.decode[STOLevel]("\"Low\"") == Result.succeed(STOLevel.Low))
        }

        "a case's @rename is its wire name, and its Scala name is not accepted" in {
            val value: STOStatus = STOInactive
            assert(Json.encode(value) == "\"off\"")
            assert(Json.decode[STOStatus]("\"off\"") == Result.succeed(STOInactive))
            Json.decode[STOStatus]("\"STOInactive\"") match
                case Result.Failure(e: UnknownVariantException) => assert(e.variantName == "STOInactive")
                case other                                      => fail(s"expected UnknownVariantException, got $other")
        }

        "a case's @alias is accepted on decode" in {
            assert(Json.decode[STOStatus]("\"gone\"") == Result.succeed(STOArchived()))
        }

        "a case class without fields is a variant like a case object" in {
            val value: STOStatus = STOArchived()
            assert(Json.encode(value) == "\"STOArchived\"")
            assert(Json.decode[STOStatus]("\"STOArchived\"") == Result.succeed(STOArchived()))
        }

        "as a field of a record" in {
            val account = STOAccount(1, STOInactive)
            assert(Json.encode(account) == """{"id":1,"status":"off"}""")
            assert(Json.decode[STOAccount]("""{"id":1,"status":"off"}""") == Result.succeed(account))
        }

        "rejects a sum with a field-bearing variant at compile time" in {
            typeCheckFailure("kyo.Schema.derived[kyo.STOAnnotatedWithFields]")("STOAnnotatedBox")
        }
    }

    "decode failures" - {

        "an unknown name is UnknownVariantException" in {
            Json.decode[STOStatus]("\"Purple\"") match
                case Result.Failure(e: UnknownVariantException) => assert(e.variantName == "Purple")
                case other                                      => fail(s"expected UnknownVariantException, got $other")
        }

        "a value that is not a string is TypeMismatchException" in {
            assert(Json.decode[STOStatus]("{}") == Result.fail(TypeMismatchException(Nil, "string", "object")))
            assert(Json.decode[STOStatus]("1") == Result.fail(TypeMismatchException(Nil, "string", "number")))
        }

        "a failure inside a record names the field" in {
            Json.decode[STOAccount]("""{"id":1,"status":"nope"}""") match
                case Result.Failure(e: UnknownVariantException) => assert(e.path == List("status"))
                case other                                      => fail(s"expected UnknownVariantException, got $other")
        }
    }

    "codecs" - {

        "round-trips on Yaml, Ion and MsgPack" in {
            val value: STOStatus = STOInactive
            val schema           = Schema[STOStatus]
            assert(schema.decodeString[Yaml](schema.encodeString[Yaml](value)) == Result.succeed(value))
            assert(schema.decodeString[Ion](schema.encodeString[Ion](value)) == Result.succeed(value))
            assert(schema.decode[MsgPack](schema.encode[MsgPack](value)) == Result.succeed(value))
        }

        "Protobuf cannot express it and fails before writing" in {
            val ex = intercept[RepresentationUnsupportedException] {
                Schema[STOStatus].encode[Protobuf](STOActive)
            }
            assert(ex.codec == "Protobuf")
            assert(ex.representation == "TagOnly")
        }
    }

    "an enum without tagOnly keeps the wrapper object" in {
        assert(Json.encode(STOColor.Red) == """{"Red":{}}""")
    }

end SchemaTagOnlyTest

enum STOColor derives CanEqual, Schema:
    case Red, Green, Blue

enum STOLevelName derives CanEqual, Schema:
    case Low, VeryHigh

@tagOnly() enum STOLevel derives CanEqual, Schema:
    case Low, High

@tagOnly() sealed trait STOStatus derives CanEqual, Schema
case object STOActive                   extends STOStatus
@rename("off") case object STOInactive  extends STOStatus
@alias("gone") case class STOArchived() extends STOStatus derives CanEqual

case class STOAccount(id: Int, status: STOStatus) derives CanEqual, Schema

sealed trait STOWithFields derives CanEqual, Schema
case object STOEmpty         extends STOWithFields
case class STOBox(size: Int) extends STOWithFields derives CanEqual

@tagOnly() sealed trait STOAnnotatedWithFields derives CanEqual
case object STOAnnotatedEmpty         extends STOAnnotatedWithFields
case class STOAnnotatedBox(size: Int) extends STOAnnotatedWithFields derives CanEqual
