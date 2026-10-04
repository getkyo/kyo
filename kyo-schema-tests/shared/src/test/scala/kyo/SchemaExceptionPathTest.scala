package kyo

import kyo.schema.*

class SchemaExceptionPathTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "a decode failure carries the path of the value that failed" - {

        "the README's wrong-kind field" in {
            val result = Json.decode[SEPUser]("""{"id":1,"name": 42}""")
            assert(result == Result.fail(TypeMismatchException(List("name"), "string", "number")), result.toString)
        }

        "a field of a nested record" in {
            val result = Json.decode[SEPOuter]("""{"inner":{"ok":true,"result":"x"}}""")
            assert(result == Result.fail(TypeMismatchException(List("inner", "result"), "boolean", "string")), result.toString)
        }

        "a missing field of a nested record names its parent" in {
            val result = Json.decode[SEPOuter]("""{"inner":{"ok":true}}""")
            assert(result == Result.fail(MissingFieldException(List("inner"), "result")), result.toString)
        }

        "a missing field of a nested record names its parent on Yaml" in {
            val result = Yaml.decode[SEPOuter]("inner:\n  ok: true\n")
            assert(result == Result.fail(MissingFieldException(List("inner"), "result")), result.toString)
        }

        "a transformer's own failure names the field it reads" in {
            val result = Json.decode[SEPHolder]("""{"user":{}}""")
            assert(result == Result.fail(MissingFieldException(List("user"), "id")), result.toString)
        }

        "an element of a sequence adds its index" in {
            val result = Json.decode[SEPList]("""{"items":[{"n":1},{"n":"x"}]}""")
            assert(result == Result.fail(TypeMismatchException(List("items", "1", "n"), "number", "string")), result.toString)
        }

        "a value of a string-keyed map adds its key" in {
            val result = Json.decode[SEPMap]("""{"counts":{"a":1,"b":"x"}}""")
            assert(result == Result.fail(TypeMismatchException(List("counts", "b"), "number", "string")), result.toString)
        }

        "an entry of a map written as pairs adds its index and its side" in {
            val result = Json.decode[SEPPairs]("""{"byId":[{"key":1,"value":"a"},{"key":2,"value":3}]}""")
            assert(result == Result.fail(TypeMismatchException(List("byId", "1", "value"), "string", "number")), result.toString)
        }

        "an annotation-renamed field is named by its wire key" in {
            val result = Json.decode[SEPAnnotated]("""{"app_id":"x"}""")
            assert(result == Result.fail(TypeMismatchException(List("app_id"), "number", "string")), result.toString)
        }

        "a builder-renamed field is named by its wire key" in {
            val result = Schema[SEPPlain].rename("appId", "app").decodeString[Json]("""{"app":"x"}""")
            assert(result == Result.fail(TypeMismatchException(List("app"), "number", "string")), result.toString)
        }

        "a sum adds no segment of its own" in {
            val result = Json.decode[SEPShapeHolder]("""{"shape":{"SEPCircle":{"r":"x"}}}""")
            assert(result == Result.fail(TypeMismatchException(List("shape", "r"), "number", "string")), result.toString)
        }

        "a failure of the top-level value has an empty path" in {
            val result = Json.decode[Int]("\"x\"")
            assert(result == Result.fail(TypeMismatchException(Nil, "number", "string")), result.toString)
        }

        "malformed JSON stays a ParseException" in {
            val result = Json.decode[SEPUser]("""{"id":1,"name": x}""")
            result match
                case Result.Failure(_: ParseException) => succeed("malformed input is a parse failure")
                case other                             => fail(s"expected a ParseException, got $other")
        }

        "input that ends inside an object or an array is truncated input, not a missing field" in {
            val records = Chunk("{", """{"id":1""", """{"id":1,""", """{"id":1,"name":"a"""").map(Json.decode[SEPUser](_))
            val array   = Json.decode[Chunk[Int]]("[1,2")
            val nested  = Json.decode[SEPOuter]("""{"inner":{"ok":true""")
            def kind(result: Result[DecodeException, Any]): String =
                result match
                    case Result.Failure(e) => e.getClass.getSimpleName
                    case other             => s"not a failure: $other"
            val kinds = (records :+ array :+ nested).map(kind)
            assert(kinds == Chunk.fill(6)("TruncatedInputException"), kinds.toString)
        }
    }

    "a missing field is named by its wire key" - {

        "an annotation rename" in {
            val result = Json.decode[SEPHello]("{}")
            assert(result == Result.fail(MissingFieldException(Nil, "app_id")), result.toString)
        }

        "a naming convention" in {
            val result = Schema[SEPCamel].renameAllFields(Schema.NameCase.SnakeCase).decodeString[Json]("{}")
            assert(result == Result.fail(MissingFieldException(Nil, "app_id")), result.toString)
        }

        "a builder rename" in {
            val result = Schema[SEPCamel].rename("appId", "app").decodeString[Json]("{}")
            assert(result == Result.fail(MissingFieldException(Nil, "app")), result.toString)
        }

        "a variant's annotation rename" in {
            val result = Json.decode[SEPEvent]("""{"SEPPing":{}}""")
            assert(result == Result.fail(MissingFieldException(Nil, "app_id")), result.toString)
        }

        "a nested record's annotation rename, under its parent's path" in {
            val result = Json.decode[SEPHelloHolder]("""{"hello":{}}""")
            assert(result == Result.fail(MissingFieldException(List("hello"), "app_id")), result.toString)
        }
    }

    "a failure names each segment by the key the input holds" - {

        "a field cased by a naming convention" in {
            val result = Schema[SEPCamel].renameAllFields(Schema.NameCase.SnakeCase).decodeString[Json]("""{"app_id":1}""")
            assert(result == Result.fail(TypeMismatchException(List("app_id"), "string", "number")), result.toString)
        }

        "a field of a flattened record, at the parent level" in {
            val result = Schema[SEPPerson].flatten.decodeString[Json]("""{"name":"a","zip":"x","city":"c"}""")
            assert(result == Result.fail(TypeMismatchException(List("zip"), "number", "string")), result.toString)
        }

        "a flattened record's own rename, kept under the parent's convention" in {
            val schema = Schema[SEPCasedPerson].flatten.renameAllFields(Schema.NameCase.SnakeCase)
            val result = schema.decodeString[Json]("""{"full_name":"a","ZIP":"x","city_name":"c"}""")
            assert(result == Result.fail(TypeMismatchException(List("ZIP"), "number", "string")), result.toString)
        }

        "a missing field of a flattened record, at the parent level" in {
            val result = Schema[SEPPerson].flatten.decodeString[Json]("""{"name":"a","city":"c"}""")
            assert(result == Result.fail(MissingFieldException(Nil, "zip")), result.toString)
        }

        "a field of a record held under a renamed field" in {
            val result = Json.decode[SEPRenamedHolder]("""{"in":{"s":"x"},"s":1}""")
            assert(result == Result.fail(TypeMismatchException(List("in", "s"), "number", "string")), result.toString)
        }

        "a nested field named like a renamed parent field keeps its own key" in {
            val schema = Schema[SEPRenamedHolder].rename("s", "outer_s")
            val result = schema.decodeString[Json]("""{"in":{"s":"x"},"outer_s":1}""")
            assert(result == Result.fail(TypeMismatchException(List("in", "s"), "number", "string")), result.toString)
        }
    }

    "input cut off before its end fails with the same exception type in JSON as in Protobuf" in {
        val bytes                                                  = Protobuf.encode(SEPUser(1, "abcdef"))
        def kind(result: Result[DecodeException, SEPUser]): String =
            result match
                case Result.Failure(e) => e.getClass.getSimpleName
                case other             => s"not a failure: $other"
        val protobuf = kind(Protobuf.decode[SEPUser](bytes.slice(0, bytes.size - 2)))
        val json     = kind(Json.decode[SEPUser]("""{"id":1"""))
        assert(json == protobuf, s"json: $json, protobuf: $protobuf")
    }

    "a field's value out of range carries the field's path" in {
        Yaml.decode[SEPShortHolder]("inner:\n  s: 99999\n") match
            case Result.Failure(e: RangeException) =>
                assert(e.path == Seq("inner", "s"), e.path.toString)
                assert(e.getMessage.contains("Value 99999 out of range for Short (-32768 to 32767) at inner.s"), e.getMessage)
            case other => fail(s"expected a RangeException, got $other")
    }

end SchemaExceptionPathTest

case class SEPShort(s: Short) derives CanEqual, Schema
case class SEPAddress(zip: Int, city: String) derives CanEqual, Schema
case class SEPPerson(name: String, address: SEPAddress) derives CanEqual, Schema
case class SEPCasedAddress(@rename("ZIP") zip: Int, cityName: String) derives CanEqual, Schema
case class SEPCasedPerson(fullName: String, homeAddress: SEPCasedAddress) derives CanEqual, Schema
case class SEPInnerInt(s: Int) derives CanEqual, Schema
case class SEPRenamedHolder(@rename("in") inner: SEPInnerInt, s: Int) derives CanEqual, Schema
case class SEPShortHolder(inner: SEPShort) derives CanEqual, Schema

case class SEPHello(@rename("app_id") appId: String) derives CanEqual, Schema
case class SEPHelloHolder(hello: SEPHello) derives CanEqual, Schema
case class SEPCamel(appId: String) derives CanEqual, Schema
sealed trait SEPEvent derives CanEqual, Schema
case class SEPPing(@rename("app_id") appId: String) extends SEPEvent derives CanEqual

case class SEPUser(id: Int, name: String) derives CanEqual, Schema

case class SEPEnvelope[A](ok: Boolean, result: A) derives CanEqual, Schema
case class SEPOuter(inner: SEPEnvelope[Boolean]) derives CanEqual, Schema

case class SEPUserRef(id: Int) derives CanEqual, Schema
object SEPUserRefReader extends Transformer.Full[SEPUserRef]:
    def write(value: SEPUserRef, writer: Codec.Writer): Unit = Schema[SEPUserRef].serializeWrite(value, writer)
    def read(reader: Codec.Reader): SEPUserRef               =
        reader.skip()
        throw MissingFieldException(Nil, "id")(using reader.frame)
end SEPUserRefReader
case class SEPHolder(@transform(SEPUserRefReader) user: SEPUserRef) derives CanEqual, Schema

case class SEPItem(n: Int) derives CanEqual, Schema
case class SEPList(items: List[SEPItem]) derives CanEqual, Schema
case class SEPMap(counts: Map[String, Int]) derives CanEqual, Schema
case class SEPPairs(byId: Map[Int, String]) derives CanEqual, Schema

case class SEPAnnotated(@rename("app_id") appId: Int) derives CanEqual, Schema
case class SEPPlain(appId: Int) derives CanEqual, Schema

sealed trait SEPShape derives CanEqual, Schema
case class SEPCircle(r: Int) extends SEPShape derives CanEqual
case class SEPShapeHolder(shape: SEPShape) derives CanEqual, Schema
