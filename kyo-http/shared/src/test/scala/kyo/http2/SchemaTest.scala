package kyo.http2

import kyo.Absent
import kyo.Maybe
import kyo.Present
import kyo.Result
import kyo.Test

class SchemaTest extends Test:

    override protected def useTestClient: Boolean = false

    case class User(id: Int, name: String) derives Schema, CanEqual
    case class Nested(user: User, active: Boolean) derives Schema, CanEqual
    case class Empty() derives Schema, CanEqual
    case class TestItem(id: Int, name: String, price: Double) derives Schema, CanEqual
    case class SeqContainer(items: Seq[String]) derives Schema, CanEqual

    "primitives" - {
        "Int" in {
            val s = Schema[Int]
            assert(s.encode(42) == "42")
            assert(s.decode("42") == Result.succeed(42))
        }

        "Long" in {
            val s = Schema[Long]
            assert(s.encode(123L) == "123")
            assert(s.decode("123") == Result.succeed(123L))
        }

        "Boolean" in {
            val s = Schema[Boolean]
            assert(s.encode(true) == "true")
            assert(s.decode("true") == Result.succeed(true))
        }

        "Double" in {
            val s = Schema[Double]
            assert(s.encode(3.14) == "3.14")
            assert(s.decode("3.14") == Result.succeed(3.14))
        }

        "Float" in {
            val s       = Schema[Float]
            val encoded = s.encode(1.5f)
            assert(encoded == "1.5")
        }

        "Short" in {
            val s = Schema[Short]
            assert(s.encode(10.toShort) == "10")
            assert(s.decode("10") == Result.succeed(10.toShort))
        }

        "Byte" in {
            val s = Schema[Byte]
            assert(s.encode(5.toByte) == "5")
            assert(s.decode("5") == Result.succeed(5.toByte))
        }

        "Char" in {
            val s       = Schema[Char]
            val encoded = s.encode('a')
            val decoded = s.decode(encoded)
            assert(decoded.contains('a'))
        }

        "Char specific value roundtrip" in {
            val s = Schema[Char]
            assert(s.decode(s.encode('A')) == Result.succeed('A'))
        }
    }

    "String" - {
        "encode wraps in JSON quotes" in {
            assert(Schema[String].encode("hello") == "\"hello\"")
        }

        "decode JSON string" in {
            assert(Schema[String].decode("\"hello\"") == Result.succeed("hello"))
        }

        "decode falls back to raw text on JSON failure" in {
            assert(Schema[String].decode("plain text") == Result.succeed("plain text"))
        }

        "decode JSON-encoded string" in {
            assert(Schema[String].decode("\"hello world\"") == Result.succeed("hello world"))
        }

        "decode empty string" in {
            assert(Schema[String].decode("") == Result.succeed(""))
        }
    }

    "Unit" - {
        "encode" in {
            val encoded = Schema[Unit].encode(())
            assert(encoded.nonEmpty)
        }

        "decode empty string" in {
            assert(Schema[Unit].decode("") == Result.succeed(()))
        }

        "decode whitespace" in {
            assert(Schema[Unit].decode("   ") == Result.succeed(()))
        }

        "decode null" in {
            assert(Schema[Unit].decode("null") == Result.succeed(()))
        }

        "decode invalid value returns failure" in {
            assert(Schema[Unit].decode("invalid").isFailure)
        }
    }

    "derived case class" - {
        "encode" in {
            val json = Schema[User].encode(User(1, "Alice"))
            assert(json.contains("\"id\":1"))
            assert(json.contains("\"name\":\"Alice\""))
        }

        "decode" in {
            val result = Schema[User].decode("""{"id":1,"name":"Alice"}""")
            assert(result == Result.succeed(User(1, "Alice")))
        }

        "decode invalid JSON fails" in {
            val result = Schema[User].decode("not json")
            assert(result.isFailure)
        }

        "decode missing field fails" in {
            val result = Schema[User].decode("""{"id":1}""")
            assert(result.isFailure)
        }

        "nested case class" in {
            val nested = Nested(User(1, "Alice"), true)
            val json   = Schema[Nested].encode(nested)
            val result = Schema[Nested].decode(json)
            assert(result == Result.succeed(nested))
        }

        "round-trip" in {
            val user   = User(42, "Bob")
            val json   = Schema[User].encode(user)
            val result = Schema[User].decode(json)
            assert(result == Result.succeed(user))
        }
    }

    "collections" - {
        "List[Int]" in {
            val s      = Schema[List[Int]]
            val json   = s.encode(List(1, 2, 3))
            val result = s.decode(json)
            assert(result == Result.succeed(List(1, 2, 3)))
        }

        "Vector[String]" in {
            val s      = Schema[Vector[String]]
            val json   = s.encode(Vector("a", "b"))
            val result = s.decode(json)
            assert(result == Result.succeed(Vector("a", "b")))
        }

        "Vector[Boolean]" in {
            val s      = Schema[Vector[Boolean]]
            val value  = Vector(true, false, true)
            val json   = s.encode(value)
            val result = s.decode(json)
            assert(result == Result.succeed(value))
        }

        "Set[Int]" in {
            val s      = Schema[Set[Int]]
            val json   = s.encode(Set(1, 2, 3))
            val result = s.decode(json)
            assert(result.contains(Set(1, 2, 3)))
        }

        "Seq[Int]" in {
            val s      = Schema[Seq[Int]]
            val json   = s.encode(Seq(1, 2))
            val result = s.decode(json)
            // Seq may come back as List, compare contents
            assert(result.map(_.toList) == Result.succeed(List(1, 2)))
        }

        "Map[String, Int]" in {
            val s      = Schema[Map[String, Int]]
            val json   = s.encode(Map("a" -> 1, "b" -> 2))
            val result = s.decode(json)
            assert(result == Result.succeed(Map("a" -> 1, "b" -> 2)))
        }

        "empty list" in {
            val s    = Schema[List[Int]]
            val json = s.encode(List.empty)
            assert(json == "[]")
            assert(s.decode(json) == Result.succeed(List.empty[Int]))
        }

        "list of case classes" in {
            val s      = Schema[List[User]]
            val users  = List(User(1, "Alice"), User(2, "Bob"))
            val json   = s.encode(users)
            val result = s.decode(json)
            assert(result == Result.succeed(users))
        }

        "Option of case class" in {
            val s      = Schema[Option[TestItem]]
            val value  = Some(TestItem(1, "a", 1.0))
            val json   = s.encode(value)
            val result = s.decode(json)
            assert(result == Result.succeed(value))
        }
    }

    "case class with Seq field" - {
        "encode/decode roundtrip" in {
            val s      = Schema[SeqContainer]
            val value  = SeqContainer(Seq("a", "b", "c"))
            val json   = s.encode(value)
            val result = s.decode(json)
            assert(result == Result.succeed(value))
        }

        "empty Seq" in {
            val s      = Schema[SeqContainer]
            val value  = SeqContainer(Seq.empty)
            val json   = s.encode(value)
            val result = s.decode(json)
            assert(result == Result.succeed(value))
        }
    }

    "case class with Double field" - {
        "encode/decode roundtrip" in {
            val s      = Schema[TestItem]
            val value  = TestItem(1, "widget", 9.99)
            val json   = s.encode(value)
            val result = s.decode(json)
            assert(result == Result.succeed(value))
        }

        "encode produces JSON with all fields" in {
            val json = Schema[TestItem].encode(TestItem(1, "test", 0.0))
            assert(json.contains("\"id\""))
            assert(json.contains("\"name\""))
            assert(json.contains("\"price\""))
        }

        "decode wrong shape returns failure" in {
            assert(Schema[TestItem].decode("""{"wrong":"shape"}""").isFailure)
        }
    }

    "Option" - {
        "Some" in {
            val s      = Schema[Option[Int]]
            val json   = s.encode(Some(42))
            val result = s.decode(json)
            assert(result == Result.succeed(Some(42)))
        }

        "None" in {
            val s      = Schema[Option[Int]]
            val json   = s.encode(None)
            val result = s.decode(json)
            assert(result == Result.succeed(None))
        }
    }

    "Either" - {
        "Right" in {
            val s      = Schema[Either[String, Int]]
            val json   = s.encode(Right(42))
            val result = s.decode(json)
            assert(result == Result.succeed(Right(42)))
        }

        "Left" in {
            val s      = Schema[Either[String, Int]]
            val json   = s.encode(Left("error"))
            val result = s.decode(json)
            assert(result == Result.succeed(Left("error")))
        }
    }

    "Maybe" - {
        "Present" in {
            val s      = Schema[Maybe[Int]]
            val json   = s.encode(Present(42))
            val result = s.decode(json)
            assert(result == Result.succeed(Present(42)))
        }

        "Absent" in {
            val s      = Schema[Maybe[Int]]
            val json   = s.encode(Absent)
            val result = s.decode(json)
            assert(result == Result.succeed(Absent))
        }
    }

    "decode errors" - {
        "invalid JSON for Int" in {
            val result = Schema[Int].decode("not_a_number")
            assert(result.isFailure)
        }

        "invalid JSON for case class" in {
            val result = Schema[User].decode("{invalid}")
            assert(result.isFailure)
        }

        "error message contains context" in {
            val result = Schema[Int].decode("abc")
            result match
                case Result.Failure(msg) => assert(msg.contains("JSON decode error"))
                case _                   => fail("Expected failure")
            end match
        }
    }

end SchemaTest
