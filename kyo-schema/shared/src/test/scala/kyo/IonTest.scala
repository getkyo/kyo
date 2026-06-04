package kyo

class IonTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    "encode/decode" - {

        "ion encode simple case class" in {
            val ion = Ion.encode[MTPerson](MTPerson("Alice", 30))
            assert(ion == """{name:"Alice",age:30}""")
        }

        "ion decode simple case class" in {
            val person = Ion.decode[MTPerson]("""{name:"Alice",age:30}""").getOrThrow
            assert(person == MTPerson("Alice", 30))
        }

        "ion round-trip case class" in {
            val person  = MTPerson("Bob", 25)
            val encoded = Ion.encodeBytes[MTPerson](person)
            val decoded = Ion.decodeBytes[MTPerson](encoded).getOrThrow
            assert(decoded == person)
        }

        "ion works with nested case classes and collections" in {
            val team    = MTSmallTeam(MTPerson("Alice", 30), 5)
            val encoded = Ion.encode(team)
            assert(encoded == """{lead:{name:"Alice",age:30},size:5}""")
            assert(Ion.decode[MTSmallTeam](encoded).getOrThrow == team)

            val people = List(MTPerson("Alice", 30), MTPerson("Bob", 25))
            assert(Ion.decode[List[MTPerson]](Ion.encode(people)).getOrThrow == people)

            val scores = Map("a" -> 1, "spaced key" -> 2)
            assert(Ion.decode[Map[String, Int]](Ion.encode(scores)).getOrThrow == scores)
        }

        "ion encodes byte spans as blobs" in {
            val bytes   = Span.from("To infinity... and beyond!".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            val encoded = Ion.encode(bytes)
            assert(encoded == "{{VG8gaW5maW5pdHkuLi4gYW5kIGJleW9uZCE=}}")
            assert(Ion.decode[Span[Byte]](encoded).getOrThrow.toArray.toSeq == bytes.toArray.toSeq)
        }

        "ion sealed traits use wrapper structs" in {
            val shape: MTShape = MTCircle(5.0)
            val encoded        = Ion.encode[MTShape](shape)
            assert(encoded == "{MTCircle:{radius:5.0e0}}")
            assert(Ion.decode[MTShape](encoded).getOrThrow == shape)
        }

        "ion discriminator schema reads annotated values" in {
            given Schema[MTShape] = Schema[MTShape].discriminator("type")

            val encoded = """shape::{type:"MTRectangle",width:3.0e0,height:4.0e0}"""
            val decoded = Ion.decode[MTShape](encoded).getOrThrow
            assert(decoded == MTRectangle(3.0, 4.0))
        }
    }

    "amazon ion-tests snippets" - {

        "decodes struct spelling variants from iontestdata/good/structs.ion" in {
            val inputs = List(
                "{a:b,c:42,d:{e:f,},g:3}",
                "{'a':'b','c':42,'d':{'e':'f',},'g':3}",
                """{"a":"b","c":42,"d":{"e":"f",},"g":3}""",
                "{a: b, c: 42, d: {e: f, }, g: 3,   }"
            )

            inputs.foreach { input =>
                val decoded = Ion.decode[IonTest.UpstreamStruct](input).getOrThrow
                assert(decoded == IonTest.UpstreamStruct("b", 42, Map("e" -> "f"), 3))
            }
            succeed
        }

        "decodes long field names from iontestdata/good/structs.ion" in {
            val input =
                "{ '''123456789ABCDEF'''  '''123456789ABCDEF''' : v }"
            val decoded = Ion.decode[Map[String, String]](input).getOrThrow
            assert(decoded == Map("123456789ABCDEF123456789ABCDEF" -> "v"))
        }

        "decodes escaped and long strings from iontestdata/good/strings.ion" in {
            assert(Ion.decode[String]("\"\\uABCD\"").getOrThrow == "\uABCD")

            val concat = "'''concatenated'''  ''' from '''   '''a single line'''"
            assert(Ion.decode[String](concat).getOrThrow == "concatenated from a single line")

            val escaped = "\"\\0 \\a \\b \\t \\n \\f \\r \\v \\\" \\' \\? \\\\ \\/\""
            assert(Ion.decode[String](escaped).getOrThrow == "\u0000 \u0007 \b \t \n \f \r \u000b \" ' ? \\ /")
        }

        "decodes booleans from iontestdata/good/booleans.ion" in {
            val upstream = "true\nfalse\n"
            assert(upstream.linesIterator.toList == List("true", "false"))
            assert(Ion.decode[Boolean]("true").getOrThrow)
            assert(!Ion.decode[Boolean]("false").getOrThrow)
        }

        "decodes blobs from iontestdata/good/blobs.ion" in {
            val alphabet =
                """{{
                  |    YSBiIGMgZCBlIGYgZyBoIGkgaiBrIGwgbSBuIG8gcCBxIHIgcyB0IHUgdiB3IHggeSB6
                  |}}""".stripMargin
            val decoded = Ion.decode[Span[Byte]](alphabet).getOrThrow
            assert(new String(
                decoded.toArray,
                java.nio.charset.StandardCharsets.UTF_8
            ) == "a b c d e f g h i j k l m n o p q r s t u v w x y z")

            val withSlashes = "{{  //79/PsAAQIDBAU=  }}"
            assert(
                Ion.decode[Span[Byte]](withSlashes).getOrThrow.toArray.toSeq == java.util.Base64.getDecoder.decode("//79/PsAAQIDBAU=").toSeq
            )
        }

        "ignores comments as whitespace like iontestdata/good/whitespace.ion" in {
            val input =
                """// before
                  |{
                  |  name: "Fido", /* between */
                  |  age: years::4,
                  |  toys: [ball, rope,],
                  |}
                  |""".stripMargin
            val decoded = Ion.decode[IonTest.Pet](input).getOrThrow
            assert(decoded == IonTest.Pet("Fido", 4, List("ball", "rope")))
        }
    }

    "scalar mappings" - {

        "typed nulls decode as absent optional values" in {
            assert(Ion.decode[Option[Int]]("null.int").getOrThrow == None)
            assert(Ion.decode[Maybe[String]]("null.string").getOrThrow == Maybe.empty)
            assert(Ion.decode[Option[Int]]("0").getOrThrow == Some(0))
        }

        "decodes Ion integer and decimal syntax" in {
            assert(Ion.decode[Int]("0").getOrThrow == 0)
            assert(Ion.decode[Int]("0x7b").getOrThrow == 123)
            assert(Ion.decode[Int]("0b1111011").getOrThrow == 123)
            assert(Ion.decode[BigDecimal]("1.25d2").getOrThrow == BigDecimal("125"))
        }

        "decodes Ion float specials" in {
            assert(Ion.decode[Double]("+inf").getOrThrow == Double.PositiveInfinity)
            assert(Ion.decode[Double]("-inf").getOrThrow == Double.NegativeInfinity)
            assert(Ion.decode[Double]("nan").getOrThrow.isNaN)
        }

        "rejects non-finite Ion floats for BigDecimal" in {
            assert(Ion.decode[BigDecimal]("nan").isFailure)
            assert(Ion.decode[BigDecimal]("+inf").isFailure)
        }

        "rejects trailing content after the decoded root value" in {
            assert(Ion.decode[Int]("0 1").isFailure)
        }

        "decodes timestamp tokens as instants" in {
            val instant = java.time.Instant.parse("2024-01-02T03:04:05Z")
            assert(Ion.decode[java.time.Instant]("2024-01-02T03:04:05Z").getOrThrow == instant)
        }

        "enforces decode limits" in {
            assert(Ion.decode[List[List[Int]]]("[[1]]", maxDepth = 1).isFailure)
            assert(Ion.decode[List[Int]]("[1,2]", maxCollectionSize = 1).isFailure)
        }
    }

end IonTest

object IonTest:
    case class UpstreamStruct(a: String, c: Int, d: Map[String, String], g: Int) derives CanEqual
    case class Pet(name: String, age: Int, toys: List[String]) derives CanEqual
end IonTest
