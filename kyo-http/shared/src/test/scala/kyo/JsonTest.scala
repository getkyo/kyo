package kyo

import kyo.*

class JsonTest extends Test:

    case class User(id: Int, name: String) derives Json, CanEqual
    case class Nested(user: User, active: Boolean) derives Json, CanEqual
    case class Empty() derives Json, CanEqual
    case class TestItem(id: Int, name: String, price: Double) derives Json, CanEqual
    case class SeqContainer(items: Seq[String]) derives Json, CanEqual

    "primitives" - {
        "Int" in {
            val s = Json[Int]
            assert(s.encode(42) == "42")
            assert(s.decode("42") == Result.succeed(42))
        }

        "Long" in {
            val s = Json[Long]
            assert(s.encode(123L) == "123")
            assert(s.decode("123") == Result.succeed(123L))
        }

        "Boolean" in {
            val s = Json[Boolean]
            assert(s.encode(true) == "true")
            assert(s.decode("true") == Result.succeed(true))
        }

        "Double" in {
            val s = Json[Double]
            assert(s.encode(3.14) == "3.14")
            assert(s.decode("3.14") == Result.succeed(3.14))
        }

        "Float" in {
            val s       = Json[Float]
            val encoded = s.encode(1.5f)
            assert(encoded == "1.5")
        }

        "Short" in {
            val s = Json[Short]
            assert(s.encode(10.toShort) == "10")
            assert(s.decode("10") == Result.succeed(10.toShort))
        }

        "Byte" in {
            val s = Json[Byte]
            assert(s.encode(5.toByte) == "5")
            assert(s.decode("5") == Result.succeed(5.toByte))
        }

        "Char" in {
            val s       = Json[Char]
            val encoded = s.encode('a')
            val decoded = s.decode(encoded)
            assert(decoded.contains('a'))
        }

        "Char specific value roundtrip" in {
            val s = Json[Char]
            assert(s.decode(s.encode('A')) == Result.succeed('A'))
        }
    }

    "String" - {
        "encode wraps in JSON quotes" in {
            assert(Json[String].encode("hello") == "\"hello\"")
        }

        "decode JSON string" in {
            assert(Json[String].decode("\"hello\"") == Result.succeed("hello"))
        }

        "decode rejects non-JSON text" in {
            assert(Json[String].decode("plain text").isFailure)
        }

        "decode JSON-encoded string" in {
            assert(Json[String].decode("\"hello world\"") == Result.succeed("hello world"))
        }

        "decode rejects empty string" in {
            assert(Json[String].decode("").isFailure)
        }
    }

    "Unit" - {
        "encode" in {
            val encoded = Json[Unit].encode(())
            assert(encoded.nonEmpty)
        }

        "decode empty string" in {
            assert(Json[Unit].decode("") == Result.succeed(()))
        }

        "decode whitespace" in {
            assert(Json[Unit].decode("   ") == Result.succeed(()))
        }

        "decode null" in {
            assert(Json[Unit].decode("null") == Result.succeed(()))
        }

        "decode invalid value returns failure" in {
            assert(Json[Unit].decode("invalid").isFailure)
        }
    }

    "derived case class" - {
        "encode" in {
            val json = Json[User].encode(User(1, "Alice"))
            assert(json.contains("\"id\":1"))
            assert(json.contains("\"name\":\"Alice\""))
        }

        "decode" in {
            val result = Json[User].decode("""{"id":1,"name":"Alice"}""")
            assert(result == Result.succeed(User(1, "Alice")))
        }

        "decode invalid JSON fails" in {
            val result = Json[User].decode("not json")
            assert(result.isFailure)
        }

        "decode missing field fails" in {
            val result = Json[User].decode("""{"id":1}""")
            assert(result.isFailure)
        }

        "nested case class" in {
            val nested = Nested(User(1, "Alice"), true)
            val json   = Json[Nested].encode(nested)
            val result = Json[Nested].decode(json)
            assert(result == Result.succeed(nested))
        }

        "round-trip" in {
            val user   = User(42, "Bob")
            val json   = Json[User].encode(user)
            val result = Json[User].decode(json)
            assert(result == Result.succeed(user))
        }
    }

    "collections" - {
        "List[Int]" in {
            val s      = Json[List[Int]]
            val json   = s.encode(List(1, 2, 3))
            val result = s.decode(json)
            assert(result == Result.succeed(List(1, 2, 3)))
        }

        "Vector[String]" in {
            val s      = Json[Vector[String]]
            val json   = s.encode(Vector("a", "b"))
            val result = s.decode(json)
            assert(result == Result.succeed(Vector("a", "b")))
        }

        "Vector[Boolean]" in {
            val s      = Json[Vector[Boolean]]
            val value  = Vector(true, false, true)
            val json   = s.encode(value)
            val result = s.decode(json)
            assert(result == Result.succeed(value))
        }

        "Set[Int]" in {
            val s      = Json[Set[Int]]
            val json   = s.encode(Set(1, 2, 3))
            val result = s.decode(json)
            assert(result.contains(Set(1, 2, 3)))
        }

        "Seq[Int]" in {
            val s      = Json[Seq[Int]]
            val json   = s.encode(Seq(1, 2))
            val result = s.decode(json)
            // Seq may come back as List, compare contents
            assert(result.map(_.toList) == Result.succeed(List(1, 2)))
        }

        "Map[String, Int]" in {
            val s      = Json[Map[String, Int]]
            val json   = s.encode(Map("a" -> 1, "b" -> 2))
            val result = s.decode(json)
            assert(result == Result.succeed(Map("a" -> 1, "b" -> 2)))
        }

        "empty list" in {
            val s    = Json[List[Int]]
            val json = s.encode(List.empty)
            assert(json == "[]")
            assert(s.decode(json) == Result.succeed(List.empty[Int]))
        }

        "list of case classes" in {
            val s      = Json[List[User]]
            val users  = List(User(1, "Alice"), User(2, "Bob"))
            val json   = s.encode(users)
            val result = s.decode(json)
            assert(result == Result.succeed(users))
        }

        "Option of case class" in {
            val s      = Json[Option[TestItem]]
            val value  = Some(TestItem(1, "a", 1.0))
            val json   = s.encode(value)
            val result = s.decode(json)
            assert(result == Result.succeed(value))
        }
    }

    "case class with Seq field" - {
        "encode/decode roundtrip" in {
            val s      = Json[SeqContainer]
            val value  = SeqContainer(Seq("a", "b", "c"))
            val json   = s.encode(value)
            val result = s.decode(json)
            assert(result == Result.succeed(value))
        }

        "empty Seq" in {
            val s      = Json[SeqContainer]
            val value  = SeqContainer(Seq.empty)
            val json   = s.encode(value)
            val result = s.decode(json)
            assert(result == Result.succeed(value))
        }
    }

    "case class with Double field" - {
        "encode/decode roundtrip" in {
            val s      = Json[TestItem]
            val value  = TestItem(1, "widget", 9.99)
            val json   = s.encode(value)
            val result = s.decode(json)
            assert(result == Result.succeed(value))
        }

        "encode produces JSON with all fields" in {
            val json = Json[TestItem].encode(TestItem(1, "test", 0.0))
            assert(json.contains("\"id\""))
            assert(json.contains("\"name\""))
            assert(json.contains("\"price\""))
        }

        "decode wrong shape returns failure" in {
            assert(Json[TestItem].decode("""{"wrong":"shape"}""").isFailure)
        }
    }

    "Option" - {
        "Some" in {
            val s      = Json[Option[Int]]
            val json   = s.encode(Some(42))
            val result = s.decode(json)
            assert(result == Result.succeed(Some(42)))
        }

        "None" in {
            val s      = Json[Option[Int]]
            val json   = s.encode(None)
            val result = s.decode(json)
            assert(result == Result.succeed(None))
        }
    }

    "Either" - {
        "Right" in {
            val s      = Json[Either[String, Int]]
            val json   = s.encode(Right(42))
            val result = s.decode(json)
            assert(result == Result.succeed(Right(42)))
        }

        "Left" in {
            val s      = Json[Either[String, Int]]
            val json   = s.encode(Left("error"))
            val result = s.decode(json)
            assert(result == Result.succeed(Left("error")))
        }
    }

    "Maybe" - {
        "Present" in {
            val s      = Json[Maybe[Int]]
            val json   = s.encode(Present(42))
            val result = s.decode(json)
            assert(result == Result.succeed(Present(42)))
        }

        "Absent" in {
            val s      = Json[Maybe[Int]]
            val json   = s.encode(Absent)
            val result = s.decode(json)
            assert(result == Result.succeed(Absent))
        }
    }

    "decode errors" - {
        "invalid JSON for Int" in {
            val result = Json[Int].decode("not_a_number")
            assert(result.isFailure)
        }

        "invalid JSON for case class" in {
            val result = Json[User].decode("{invalid}")
            assert(result.isFailure)
        }

        "error message contains context" in {
            val result = Json[Int].decode("abc")
            result match
                case Result.Failure(msg) => assert(msg.contains("JSON decode error"))
                case _                   => fail("Expected failure")
            end match
        }
    }

end JsonTest
