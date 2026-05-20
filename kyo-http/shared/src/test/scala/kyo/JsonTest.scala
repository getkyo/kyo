package kyo

import kyo.*

class JsonTest extends Test:

    case class User(id: Int, name: String) derives Schema, CanEqual
    case class Nested(user: User, active: Boolean) derives Schema, CanEqual
    case class Empty() derives Schema, CanEqual
    case class TestItem(id: Int, name: String, price: Double) derives Schema, CanEqual
    case class SeqContainer(items: Seq[String]) derives Schema, CanEqual

    "primitives" - {
        "Int" in {
            assert(Json.encode[Int](42) == "42")
            assert(Json.decode[Int]("42") == Result.succeed(42))
        }

        "Long" in {
            assert(Json.encode[Long](123L) == "123")
            assert(Json.decode[Long]("123") == Result.succeed(123L))
        }

        "Boolean" in {
            assert(Json.encode[Boolean](true) == "true")
            assert(Json.decode[Boolean]("true") == Result.succeed(true))
        }

        "Double" in {
            assert(Json.encode[Double](3.14) == "3.14")
            assert(Json.decode[Double]("3.14") == Result.succeed(3.14))
        }

        "Float" in {
            val encoded = Json.encode[Float](1.5f)
            assert(encoded == "1.5")
        }

        "Short" in {
            assert(Json.encode[Short](10.toShort) == "10")
            assert(Json.decode[Short]("10") == Result.succeed(10.toShort))
        }

        "Byte" in {
            assert(Json.encode[Byte](5.toByte) == "5")
            assert(Json.decode[Byte]("5") == Result.succeed(5.toByte))
        }

        "Char" in {
            val encoded = Json.encode[Char]('a')
            val decoded = Json.decode[Char](encoded)
            assert(decoded.contains('a'))
        }

        "Char specific value roundtrip" in {
            assert(Json.decode[Char](Json.encode[Char]('A')) == Result.succeed('A'))
        }
    }

    "String" - {
        "encode wraps in JSON quotes" in {
            assert(Json.encode[String]("hello") == "\"hello\"")
        }

        "decode JSON string" in {
            assert(Json.decode[String]("\"hello\"") == Result.succeed("hello"))
        }

        "decode rejects non-JSON text" in {
            assert(Json.decode[String]("plain text").isFailure)
        }

        "decode JSON-encoded string" in {
            assert(Json.decode[String]("\"hello world\"") == Result.succeed("hello world"))
        }

        "decode rejects empty string" in {
            assert(Json.decode[String]("").isFailure)
        }
    }

    "Unit" - {
        "encode" in {
            val encoded = Json.encode[Unit](())
            assert(encoded.nonEmpty)
        }

        "decode empty string returns failure (HTTP layer handles empty body, not raw decode)" in {
            assert(Json.decode[Unit]("").isFailure)
        }

        "decode whitespace returns failure (HTTP layer handles empty body, not raw decode)" in {
            assert(Json.decode[Unit]("   ").isFailure)
        }

        "decode null" in {
            assert(Json.decode[Unit]("null") == Result.succeed(()))
        }

        "decode invalid value returns failure" in {
            assert(Json.decode[Unit]("invalid").isFailure)
        }
    }

    "derived case class" - {
        "encode" in {
            val json = Json.encode[User](User(1, "Alice"))
            assert(json.contains("\"id\":1"))
            assert(json.contains("\"name\":\"Alice\""))
        }

        "decode" in {
            val result = Json.decode[User]("""{"id":1,"name":"Alice"}""")
            assert(result == Result.succeed(User(1, "Alice")))
        }

        "decode invalid JSON fails" in {
            val result = Json.decode[User]("not json")
            assert(result.isFailure)
        }

        "decode missing field fails" in {
            val result = Json.decode[User]("""{"id":1}""")
            assert(result.isFailure)
        }

        "nested case class" in {
            val nested = Nested(User(1, "Alice"), true)
            val json   = Json.encode[Nested](nested)
            val result = Json.decode[Nested](json)
            assert(result == Result.succeed(nested))
        }

        "round-trip" in {
            val user   = User(42, "Bob")
            val json   = Json.encode[User](user)
            val result = Json.decode[User](json)
            assert(result == Result.succeed(user))
        }
    }

    "collections" - {
        "List[Int]" in {
            val json   = Json.encode[List[Int]](List(1, 2, 3))
            val result = Json.decode[List[Int]](json)
            assert(result == Result.succeed(List(1, 2, 3)))
        }

        "Vector[String]" in {
            val json   = Json.encode[Vector[String]](Vector("a", "b"))
            val result = Json.decode[Vector[String]](json)
            assert(result == Result.succeed(Vector("a", "b")))
        }

        "Vector[Boolean]" in {
            val value  = Vector(true, false, true)
            val json   = Json.encode[Vector[Boolean]](value)
            val result = Json.decode[Vector[Boolean]](json)
            assert(result == Result.succeed(value))
        }

        "Set[Int]" in {
            val json   = Json.encode[Set[Int]](Set(1, 2, 3))
            val result = Json.decode[Set[Int]](json)
            assert(result.contains(Set(1, 2, 3)))
        }

        "Seq[Int]" in {
            val json   = Json.encode[Seq[Int]](Seq(1, 2))
            val result = Json.decode[Seq[Int]](json)
            // Seq may come back as List, compare contents
            assert(result.map(_.toList) == Result.succeed(List(1, 2)))
        }

        "Map[String, Int]" in {
            val json   = Json.encode[Map[String, Int]](Map("a" -> 1, "b" -> 2))
            val result = Json.decode[Map[String, Int]](json)
            assert(result == Result.succeed(Map("a" -> 1, "b" -> 2)))
        }

        "empty list" in {
            val json = Json.encode[List[Int]](List.empty)
            assert(json == "[]")
            assert(Json.decode[List[Int]](json) == Result.succeed(List.empty[Int]))
        }

        "list of case classes" in {
            val users  = List(User(1, "Alice"), User(2, "Bob"))
            val json   = Json.encode[List[User]](users)
            val result = Json.decode[List[User]](json)
            assert(result == Result.succeed(users))
        }

        "Option of case class" in {
            val value  = Some(TestItem(1, "a", 1.0))
            val json   = Json.encode[Option[TestItem]](value)
            val result = Json.decode[Option[TestItem]](json)
            assert(result == Result.succeed(value))
        }
    }

    "case class with Seq field" - {
        "encode/decode roundtrip" in {
            val value  = SeqContainer(Seq("a", "b", "c"))
            val json   = Json.encode[SeqContainer](value)
            val result = Json.decode[SeqContainer](json)
            assert(result == Result.succeed(value))
        }

        "empty Seq" in {
            val value  = SeqContainer(Seq.empty)
            val json   = Json.encode[SeqContainer](value)
            val result = Json.decode[SeqContainer](json)
            assert(result == Result.succeed(value))
        }
    }

    "case class with Double field" - {
        "encode/decode roundtrip" in {
            val value  = TestItem(1, "widget", 9.99)
            val json   = Json.encode[TestItem](value)
            val result = Json.decode[TestItem](json)
            assert(result == Result.succeed(value))
        }

        "encode produces JSON with all fields" in {
            val json = Json.encode[TestItem](TestItem(1, "test", 0.0))
            assert(json.contains("\"id\""))
            assert(json.contains("\"name\""))
            assert(json.contains("\"price\""))
        }

        "decode wrong shape returns failure" in {
            assert(Json.decode[TestItem]("""{"wrong":"shape"}""").isFailure)
        }
    }

    "Option" - {
        "Some" in {
            val json   = Json.encode[Option[Int]](Some(42))
            val result = Json.decode[Option[Int]](json)
            assert(result == Result.succeed(Some(42)))
        }

        "None" in {
            val json   = Json.encode[Option[Int]](None)
            val result = Json.decode[Option[Int]](json)
            assert(result == Result.succeed(None))
        }
    }

    "Either" - {
        "Right" in {
            val json   = Json.encode[Either[String, Int]](Right(42))
            val result = Json.decode[Either[String, Int]](json)
            assert(result == Result.succeed(Right(42)))
        }

        "Left" in {
            val json   = Json.encode[Either[String, Int]](Left("error"))
            val result = Json.decode[Either[String, Int]](json)
            assert(result == Result.succeed(Left("error")))
        }
    }

    "Maybe" - {
        "Present" in {
            val json   = Json.encode[Maybe[Int]](Present(42))
            val result = Json.decode[Maybe[Int]](json)
            assert(result == Result.succeed(Present(42)))
        }

        "Absent" in {
            val json   = Json.encode[Maybe[Int]](Absent)
            val result = Json.decode[Maybe[Int]](json)
            assert(result == Result.succeed(Absent))
        }
    }

    "decode errors" - {
        "invalid JSON for Int" in {
            val result = Json.decode[Int]("not_a_number")
            assert(result.isFailure)
        }

        "invalid JSON for case class" in {
            val result = Json.decode[User]("{invalid}")
            assert(result.isFailure)
        }

        "error message contains context" in {
            val result = Json.decode[Int]("abc")
            result match
                case Result.Failure(e) => assert(e.getMessage.contains("Cannot parse"))
                case _                 => fail("Expected failure")
            end match
        }
    }

end JsonTest
