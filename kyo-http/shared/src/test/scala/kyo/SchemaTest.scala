package kyo

class SchemaTest extends Test:

    "primitives" - {
        "Int encode/decode" in {
            val schema = Schema[Int]
            assert(schema.decode(schema.encode(42)) == Result.succeed(42))
        }

        "Long encode/decode" in {
            val schema = Schema[Long]
            assert(schema.decode(schema.encode(123456789L)) == Result.succeed(123456789L))
        }

        "Boolean encode/decode" in {
            val schema = Schema[Boolean]
            assert(schema.decode(schema.encode(true)) == Result.succeed(true))
            assert(schema.decode(schema.encode(false)) == Result.succeed(false))
        }

        "Double encode/decode" in {
            val schema = Schema[Double]
            assert(schema.decode(schema.encode(3.14)) == Result.succeed(3.14))
        }

        "Float encode/decode" in {
            val schema = Schema[Float]
            assert(schema.decode(schema.encode(1.5f)) == Result.succeed(1.5f))
        }

        "Short encode/decode" in {
            val schema = Schema[Short]
            assert(schema.decode(schema.encode(42.toShort)) == Result.succeed(42.toShort))
        }

        "Byte encode/decode" in {
            val schema = Schema[Byte]
            assert(schema.decode(schema.encode(7.toByte)) == Result.succeed(7.toByte))
        }

        "Char encode/decode" in {
            val schema = Schema[Char]
            assert(schema.decode(schema.encode('A')) == Result.succeed('A'))
        }
    }

    "String" - {
        "encode/decode JSON string" in {
            val schema  = Schema[String]
            val encoded = schema.encode("hello")
            assert(schema.decode(encoded) == Result.succeed("hello"))
        }

        "decode falls back to raw text" in {
            val schema = Schema[String]
            assert(schema.decode("not a json string") == Result.succeed("not a json string"))
        }

        "decode JSON-encoded string" in {
            val schema = Schema[String]
            assert(schema.decode("\"hello world\"") == Result.succeed("hello world"))
        }
    }

    "Unit" - {
        "decode empty string" in {
            val schema = Schema[Unit]
            assert(schema.decode("") == Result.succeed(()))
        }

        "decode whitespace-only string" in {
            val schema = Schema[Unit]
            assert(schema.decode("   ") == Result.succeed(()))
        }

        "decode JSON null" in {
            val schema = Schema[Unit]
            assert(schema.decode("null") == Result.succeed(()))
        }

        "decode invalid value returns failure" in {
            val schema = Schema[Unit]
            assert(schema.decode("invalid").isFailure)
        }
    }

    "collections" - {
        "List[Int] encode/decode" in {
            val schema = Schema[List[Int]]
            val value  = List(1, 2, 3)
            assert(schema.decode(schema.encode(value)) == Result.succeed(value))
        }

        "Seq[String] encode/decode" in {
            val schema = Schema[Seq[String]]
            val value  = Seq("a", "b", "c")
            val result = schema.decode(schema.encode(value))
            assert(result == Result.succeed(value))
        }

        "Vector[Boolean] encode/decode" in {
            val schema = Schema[Vector[Boolean]]
            val value  = Vector(true, false, true)
            assert(schema.decode(schema.encode(value)) == Result.succeed(value))
        }

        "Set[Int] encode/decode" in {
            val schema = Schema[Set[Int]]
            val value  = Set(1, 2, 3)
            assert(schema.decode(schema.encode(value)) == Result.succeed(value))
        }

        "Map[String, Int] encode/decode" in {
            val schema = Schema[Map[String, Int]]
            val value  = Map("a" -> 1, "b" -> 2)
            assert(schema.decode(schema.encode(value)) == Result.succeed(value))
        }

        "empty List" in {
            val schema = Schema[List[Int]]
            assert(schema.decode(schema.encode(List.empty[Int])) == Result.succeed(List.empty[Int]))
        }
    }

    "Option" - {
        "Some encode/decode" in {
            val schema = Schema[Option[Int]]
            assert(schema.decode(schema.encode(Some(42))) == Result.succeed(Some(42)))
        }

        "None encode/decode" in {
            val schema = Schema[Option[Int]]
            assert(schema.decode(schema.encode(None)) == Result.succeed(None))
        }
    }

    "Either" - {
        "Right encode/decode" in {
            val schema                     = Schema[Either[String, Int]]
            val value: Either[String, Int] = Right(42)
            assert(schema.decode(schema.encode(value)) == Result.succeed(value))
        }

        "Left encode/decode" in {
            val schema                     = Schema[Either[String, Int]]
            val value: Either[String, Int] = Left("error")
            assert(schema.decode(schema.encode(value)) == Result.succeed(value))
        }
    }

    "Maybe" - {
        "Present encode/decode" in {
            val schema            = Schema[Maybe[Int]]
            val value: Maybe[Int] = Present(42)
            assert(schema.decode(schema.encode(value)) == Result.succeed(value))
        }

        "Absent encode/decode" in {
            val schema            = Schema[Maybe[Int]]
            val value: Maybe[Int] = Absent
            assert(schema.decode(schema.encode(value)) == Result.succeed(value))
        }
    }

    "derived case class" - {
        "encode/decode roundtrip" in {
            val schema = Schema[TestItem]
            val value  = TestItem(1, "widget", 9.99)
            assert(schema.decode(schema.encode(value)) == Result.succeed(value))
        }

        "encode produces JSON" in {
            val schema  = Schema[TestItem]
            val encoded = schema.encode(TestItem(1, "test", 0.0))
            assert(encoded.contains("\"id\""))
            assert(encoded.contains("\"name\""))
        }

        "decode invalid JSON returns failure" in {
            val schema = Schema[TestItem]
            assert(schema.decode("not json").isFailure)
        }

        "decode wrong shape returns failure" in {
            val schema = Schema[TestItem]
            assert(schema.decode("""{"wrong":"shape"}""").isFailure)
        }
    }

    "nested types" - {
        "List of case classes" in {
            val schema = Schema[List[TestItem]]
            val value  = List(TestItem(1, "a", 1.0), TestItem(2, "b", 2.0))
            assert(schema.decode(schema.encode(value)) == Result.succeed(value))
        }

        "Option of case class" in {
            val schema = Schema[Option[TestItem]]
            val value  = Some(TestItem(1, "a", 1.0))
            assert(schema.decode(schema.encode(value)) == Result.succeed(value))
        }
    }

    "case class with Seq field" - {
        "encode/decode roundtrip" in {
            val schema = Schema[SeqContainer]
            val value  = SeqContainer(Seq("a", "b", "c"))
            assert(schema.decode(schema.encode(value)) == Result.succeed(value))
        }

        "empty Seq" in {
            val schema = Schema[SeqContainer]
            val value  = SeqContainer(Seq.empty)
            assert(schema.decode(schema.encode(value)) == Result.succeed(value))
        }
    }

    case class TestItem(id: Int, name: String, price: Double) derives Schema, CanEqual
    case class SeqContainer(items: Seq[String]) derives Schema, CanEqual

end SchemaTest
