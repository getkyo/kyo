package kyo

object YamlOpaqueUserId:
    opaque type Type = String

    def apply(value: String): Type = value

    extension (id: Type) def value: String = id

    given Schema[Type] = Schema.stringSchema.transform[Type](apply)(_.value)
end YamlOpaqueUserId

final case class YamlAnyValUserId(value: String) extends AnyVal derives CanEqual
final case class YamlValueObject(value: String) derives CanEqual
final case class YamlValueHolder(
    opaqueId: YamlOpaqueUserId.Type,
    anyValId: YamlAnyValUserId,
    valueObject: YamlValueObject
) derives CanEqual
final case class YamlUnicodeField(café: Int) derives CanEqual

class YamlTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    case class Pair(primary: MTPerson, backup: MTPerson) derives CanEqual
    case class Notes(literal: String, folded: String) derives CanEqual
    case class AdtHolder(shape: MTShape, expr: Expr, pair: (Int, String)) derives CanEqual

    "decode" - {

        "decodes a block mapping into a case class" in {
            val yaml =
                """name: Alice
                  |age: 30
                  |""".stripMargin

            val decoded = Yaml.decode[MTPerson](yaml)

            assert(decoded == Result.succeed(MTPerson("Alice", 30)))
        }

        "decodes nested block mappings" in {
            val yaml =
                """lead:
                  |  name: Alice
                  |  age: 30
                  |size: 5
                  |""".stripMargin

            val decoded = Yaml.decode[MTSmallTeam](yaml)

            assert(decoded == Result.succeed(MTSmallTeam(MTPerson("Alice", 30), 5)))
        }

        "decodes block sequences of mappings" in {
            val yaml =
                """- name: Alice
                  |  age: 30
                  |- name: Bob
                  |  age: 25
                  |""".stripMargin

            val decoded = Yaml.decode[List[MTPerson]](yaml)

            assert(decoded == Result.succeed(List(MTPerson("Alice", 30), MTPerson("Bob", 25))))
        }

        "decodes flow mappings and flow sequences" in {
            assert(Yaml.decode[MTPerson]("{name: Alice, age: 30}") == Result.succeed(MTPerson("Alice", 30)))
            assert(Yaml.decode[List[Int]]("[1, 2, 3]") == Result.succeed(List(1, 2, 3)))
        }

        "keeps comment markers inside quoted scalars" in {
            val yaml =
                """name: "Alice #1"
                  |age: 30 # comment
                  |""".stripMargin

            assert(Yaml.decode[MTPerson](yaml) == Result.succeed(MTPerson("Alice #1", 30)))
        }

        "rejects multi-document streams for single-document decode" in {
            val yaml =
                """---
                  |name: Alice
                  |age: 30
                  |---
                  |name: Bob
                  |age: 25
                  |""".stripMargin

            assert(Yaml.decode[MTPerson](yaml).isFailure)
        }

        "resolves anchors and aliases during schema decode" in {
            val yaml =
                """primary: &alice
                  |  name: Alice
                  |  age: 30
                  |backup: *alice
                  |""".stripMargin

            assert(Yaml.decode[Pair](yaml) == Result.succeed(Pair(MTPerson("Alice", 30), MTPerson("Alice", 30))))
        }

        "decodes literal and folded block scalars" in {
            val yaml =
                """literal: |
                  |  line one
                  |  line two
                  |folded: >
                  |  line one
                  |  line two
                  |""".stripMargin

            assert(Yaml.decode[Notes](yaml) == Result.succeed(Notes("line one\nline two\n", "line one line two\n")))
        }

        "decodeAll decodes explicit document streams" in {
            val yaml =
                """---
                  |name: Alice
                  |age: 30
                  |---
                  |name: Bob
                  |age: 25
                  |""".stripMargin

            assert(Yaml.decodeAll[MTPerson](yaml) == Result.succeed(Chunk(MTPerson("Alice", 30), MTPerson("Bob", 25))))
        }

        "decode targets a document by zero-based index" in {
            val yaml =
                """---
                  |name: Alice
                  |age: 30
                  |---
                  |name: Bob
                  |age: 25
                  |---
                  |name: Charlie
                  |age: 35
                  |""".stripMargin

            assert(Yaml.decode[MTPerson](yaml, Yaml.DocumentIndex(1)) == Result.succeed(MTPerson("Bob", 25)))
        }

        "decode reports an invalid document index" in {
            val yaml =
                """---
                  |name: Alice
                  |age: 30
                  |""".stripMargin

            val decoded = Yaml.decode[MTPerson](yaml, Yaml.DocumentIndex(1))

            decoded match
                case Result.Failure(e: ParseException) =>
                    assert(e.getMessage.contains("document index 1"))
                    assert(e.getMessage.contains("found 1"))
                case other => fail(s"Expected ParseException failure, got $other")
            end match
        }

        "decodeBytes targets a document by zero-based index" in {
            val yaml =
                """---
                  |name: Alice
                  |age: 30
                  |---
                  |name: Bob
                  |age: 25
                  |""".stripMargin

            val bytes = Span.from(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))

            assert(Yaml.decodeBytes[MTPerson](bytes, Yaml.DocumentIndex(1)) == Result.succeed(MTPerson("Bob", 25)))
        }

        "decode config combines document index and limits" in {
            val yaml =
                """---
                  |value: 0
                  |children: []
                  |---
                  |value: 1
                  |children:
                  |  - value: 2
                  |    children:
                  |      - value: 3
                  |        children: []
                  |""".stripMargin

            val config = Yaml.ReaderConfig(
                maxDepth = Yaml.DefaultMaxDepth,
                maxCollectionSize = Yaml.DefaultMaxCollectionSize,
                documentIndex = Maybe(Yaml.DocumentIndex(1))
            )

            val expected = TreeNode(1, List(TreeNode(2, List(TreeNode(3, Nil)))))

            assert(Yaml.decode[TreeNode](yaml, config) == Result.succeed(expected))
        }

        "decode uses a contextual reader config for single-argument decode" in {
            val yaml =
                """---
                  |name: Alice
                  |age: 30
                  |---
                  |name: Bob
                  |age: 25
                  |""".stripMargin

            given Yaml.ReaderConfig = Yaml.ReaderConfig(documentIndex = Maybe(Yaml.DocumentIndex(1)))

            assert(Yaml.decode[MTPerson](yaml) == Result.succeed(MTPerson("Bob", 25)))
        }

        "accepts YAML directives before the document marker" in {
            val yaml =
                """%YAML 1.2
                  |---
                  |name: Alice
                  |age: 30
                  |""".stripMargin

            assert(Yaml.decode[MTPerson](yaml) == Result.succeed(MTPerson("Alice", 30)))
        }

        "decodeAll ignores stream directives before the first document" in {
            val yaml =
                """%YAML 1.2
                  |---
                  |name: Alice
                  |age: 30
                  |---
                  |name: Bob
                  |age: 25
                  |""".stripMargin

            assert(Yaml.decodeAll[MTPerson](yaml) == Result.succeed(Chunk(MTPerson("Alice", 30), MTPerson("Bob", 25))))
        }

        "decodeAll handles YAML document markers with comments and empty documents" in {
            val yaml =
                """--- # explicit empty document
                  |...
                  |---
                  |present
                  |""".stripMargin

            assert(Yaml.decodeAll[Option[String]](yaml) == Result.succeed(Chunk(None, Some("present"))))
        }

        "document selection ignores indented markers inside block scalars" in {
            val yaml =
                """---
                  |text: |
                  |  ---
                  |  literal marker
                  |...
                  |--- # next document
                  |text: next
                  |""".stripMargin

            assert(Yaml.decode[Map[String, String]](yaml, Yaml.DocumentIndex(0)) == Result.succeed(Map(
                "text" -> "---\nliteral marker\n"
            )))
            assert(Yaml.decode[Map[String, String]](yaml, Yaml.DocumentIndex(1)) == Result.succeed(Map("text" -> "next")))
        }

        "single document decode rejects trailing content after an end marker" in {
            val yaml =
                """name: Alice
                  |age: 30
                  |...
                  |name: Bob
                  |age: 25
                  |""".stripMargin

            Yaml.decode[MTPerson](yaml) match
                case Result.Failure(e: ParseException) =>
                    assert(e.getMessage.contains("Unexpected content after YAML document end"))
                case other => fail(s"Expected ParseException failure, got $other")
            end match
        }

        "reports line and column in parser failures" in {
            val result = Yaml.decode[MTPerson]("name: Alice\nage: *missing\n")

            result match
                case Result.Failure(e: ParseException) =>
                    assert(e.getMessage.contains("line 2"))
                    assert(e.getMessage.contains("column"))
                case other => fail(s"Expected ParseException failure, got $other")
            end match
        }

        "decodes all-no-arg enum variants distinctly" in {
            val first  = Yaml.decode[AllNoArgEnumA]("First: {}\n")
            val second = Yaml.decode[AllNoArgEnumA]("Second: {}\n")
            val third  = Yaml.decode[AllNoArgEnumA]("Third: {}\n")

            assert(first == Result.succeed(AllNoArgEnumA.First))
            assert(second == Result.succeed(AllNoArgEnumA.Second))
            assert(third == Result.succeed(AllNoArgEnumA.Third))
        }

        "decodes mixed enum cases with parameterized and no-arg variants" in {
            val alpha =
                """Alpha:
                  |  x: 7
                  |""".stripMargin

            assert(Yaml.decode[MixedArityEnum](alpha) == Result.succeed(MixedArityEnum.Alpha(7)))
            assert(Yaml.decode[MixedArityEnum]("Beta: {}\n") == Result.succeed(MixedArityEnum.Beta))
            assert(Yaml.decode[MixedArityEnum]("Gamma: {}\n") == Result.succeed(MixedArityEnum.Gamma))
        }

        "decodes sealed trait hierarchies including case objects" in {
            val labeled =
                """Labeled:
                  |  name: release
                  |""".stripMargin

            assert(Yaml.decode[SealedNoArgVariants](labeled) == Result.succeed(SealedNoArgVariants.Labeled("release")))
            assert(Yaml.decode[SealedNoArgVariants]("Unit2: {}\n") == Result.succeed(SealedNoArgVariants.Unit2))
        }

        "decodes recursive sealed trait hierarchies" in {
            val yaml =
                """Add:
                  |  left:
                  |    Lit:
                  |      value: 1
                  |  right:
                  |    Neg:
                  |      inner:
                  |        Lit:
                  |          value: 2
                  |""".stripMargin

            assert(Yaml.decode[Expr](yaml) == Result.succeed(Add(Lit(1), Neg(Lit(2)))))
        }

        "decodes tuples using derived tuple field names" in {
            val yaml =
                """_1: 42
                  |_2: hello
                  |""".stripMargin

            assert(Yaml.decode[(Int, String)](yaml) == Result.succeed((42, "hello")))
        }

        "decodes ADTs and tuples nested in a product" in {
            val yaml =
                """shape:
                  |  MTRectangle:
                  |    width: 3.0
                  |    height: 4.0
                  |expr:
                  |  Lit:
                  |    value: 9
                  |pair:
                  |  _1: 5
                  |  _2: five
                  |""".stripMargin

            assert(Yaml.decode[AdtHolder](yaml) == Result.succeed(AdtHolder(MTRectangle(3.0, 4.0), Lit(9), (5, "five"))))
        }

        "decodes opaque types through their underlying scalar schema" in {
            val decoded = Yaml.decode[YamlOpaqueUserId.Type]("user-123\n")

            assert(decoded == Result.succeed(YamlOpaqueUserId("user-123")))
        }

        "decodes AnyVal value classes as single-field products" in {
            val decoded = Yaml.decode[YamlAnyValUserId]("value: user-123\n")

            assert(decoded == Result.succeed(YamlAnyValUserId("user-123")))
        }

        "decodes old single-field value objects as products" in {
            val decoded = Yaml.decode[YamlValueObject]("value: user-123\n")

            assert(decoded == Result.succeed(YamlValueObject("user-123")))
        }

        "decodes opaque and value wrappers nested in a product" in {
            val yaml =
                """opaqueId: user-123
                  |anyValId:
                  |  value: user-456
                  |valueObject:
                  |  value: user-789
                  |""".stripMargin

            assert(
                Yaml.decode[YamlValueHolder](yaml) ==
                    Result.succeed(
                        YamlValueHolder(
                            YamlOpaqueUserId("user-123"),
                            YamlAnyValUserId("user-456"),
                            YamlValueObject("user-789")
                        )
                    )
            )
        }

        "captures deferred values as YAML readers" in {
            val reader = kyo.internal.YamlReader("value: 42\n")

            discard(reader.objectStart())
            reader.fieldParse()
            val captured = reader.captureValue()

            assert(captured.isInstanceOf[kyo.internal.YamlReader])
            assert(captured.int() == 42)
        }

        "reader can pull a requested prefix without parsing later malformed content" in {
            val reader =
                kyo.internal.YamlReader(
                    """value: 42
                      |later: [unterminated
                      |""".stripMargin
                )

            discard(reader.objectStart())
            reader.fieldParse()

            assert(reader.matchField("value".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            assert(reader.int() == 42)
        }

        "captureValue buffers only the requested subtree" in {
            val reader =
                kyo.internal.YamlReader(
                    """value:
                      |  count: 7
                      |later: [unterminated
                      |""".stripMargin
                )

            discard(reader.objectStart())
            reader.fieldParse()
            val captured = reader.captureValue()

            discard(captured.objectStart())
            captured.fieldParse()
            val count = captured.int()
            captured.objectEnd()
            assert(count == 7)
        }

        "matches UTF-8 field names without lossy byte comparisons" in {
            assert(Yaml.decode[YamlUnicodeField]("café: 7\n") == Result.succeed(YamlUnicodeField(7)))
        }

    }

end YamlTest
