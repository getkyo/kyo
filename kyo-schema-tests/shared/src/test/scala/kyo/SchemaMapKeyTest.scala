package kyo

class SchemaMapKeyTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    import SMKTypes.*

    private val ids = Map(SMKUserId("u1") -> 1, SMKUserId("u2") -> 2)

    "a map keyed by a type whose schema is a string" - {

        "encodes as an object" in {
            assert(Json.encode(SMKScores(ids)) == """{"scores":{"u1":1,"u2":2}}""")
        }

        "decodes the object" in {
            assert(Json.decode[SMKScores]("""{"scores":{"u1":1,"u2":2}}""") == Result.succeed(SMKScores(ids)))
        }

        "decodes the array of key and value records written before" in {
            val old = """{"scores":[{"key":"u1","value":1},{"key":"u2","value":2}]}"""
            assert(Json.decode[SMKScores](old) == Result.succeed(SMKScores(ids)))
        }

        "Dict and OrderedDict do the same" in {
            val dict = SMKDicts(Dict(SMKUserId("u1") -> 1), OrderedDict(SMKUserId("u2") -> 2))
            val wire = Json.encode(dict)
            assert(wire == """{"dict":{"u1":1},"ordered":{"u2":2}}""", wire)
            // Dict and OrderedDict compare by content through `is`; the case class's equality compares them by reference.
            def same(decoded: Result[DecodeException, SMKDicts]): Boolean =
                decoded.exists(d => d.dict.is(dict.dict) && d.ordered.is(dict.ordered))
            assert(same(Json.decode[SMKDicts](wire)), Json.decode[SMKDicts](wire).toString)
            val old = """{"dict":[{"key":"u1","value":1}],"ordered":[{"key":"u2","value":2}]}"""
            assert(same(Json.decode[SMKDicts](old)), Json.decode[SMKDicts](old).toString)
        }

        "a key the key's schema rejects is a decode failure naming the key" in {
            assert(
                Json.decode[SMKChecked]("""{"byId":{"x1":1}}""") ==
                    Result.fail(ConstructorRejectedException(List("byId", "x1"), "SMKCheckedId", "not a user id: x1"))
            )
        }

        "round-trips on Yaml and Protobuf" in {
            val value = SMKScores(ids)
            assert(Schema[SMKScores].decodeString[Yaml](Schema[SMKScores].encodeString[Yaml](value)) == Result.succeed(value))
            assert(Schema[SMKScores].decode[Protobuf](Schema[SMKScores].encode[Protobuf](value)) == Result.succeed(value))
        }
    }

    "a map keyed by a type whose schema is not a string keeps the array of records" in {
        assert(Json.encode(SMKCounts(Map(1 -> 2))) == """{"counts":[{"key":1,"value":2}]}""")
    }

    "a map keyed by Char is described in the JSON Schema in the form it is written" in {
        val wire = Json.encode(SMKChars(Map('a' -> 1)))
        assert(wire == """{"chars":[{"key":"a","value":1}]}""", wire)
        Json.jsonSchema[SMKChars] match
            case obj: Json.JsonSchema.Obj =>
                obj.properties.collectFirst { case ("chars", s) => s } match
                    case Some(_: Json.JsonSchema.Arr) => succeed("described as the array it is written as")
                    case other                        => fail(s"the array-written map is described as $other")
            case other => fail(s"expected an object schema, got $other")
        end match
    }

end SchemaMapKeyTest

object SMKTypes:
    opaque type SMKUserId = String
    object SMKUserId:
        def apply(s: String): SMKUserId = s
        given Schema[SMKUserId]         = Schema[String]

    opaque type SMKCheckedId = String
    object SMKCheckedId:
        def parse(s: String): Either[String, SMKCheckedId] = if s.startsWith("u") then Right(s) else Left(s"not a user id: $s")
        given Schema[SMKCheckedId]                         = Schema[String].transformVia(parse)(identity)
end SMKTypes

case class SMKScores(scores: Map[SMKTypes.SMKUserId, Int]) derives CanEqual, Schema
case class SMKDicts(dict: Dict[SMKTypes.SMKUserId, Int], ordered: OrderedDict[SMKTypes.SMKUserId, Int]) derives CanEqual, Schema
case class SMKChecked(byId: Map[SMKTypes.SMKCheckedId, Int]) derives CanEqual, Schema
case class SMKCounts(counts: Map[Int, Int]) derives CanEqual, Schema
case class SMKChars(chars: Map[Char, Int]) derives CanEqual, Schema
