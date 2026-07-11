package kyo

import java.nio.charset.StandardCharsets

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
final case class YamlCountOnly(count: Int) derives CanEqual
final case class YamlAnchoredCount(value: YamlCountOnly) derives CanEqual
final case class YamlScalarAlias(first: String, second: String) derives CanEqual
final case class YamlSequenceAlias(name: String, items: List[String]) derives CanEqual
final case class YamlStringItems(items: List[String]) derives CanEqual
final case class YamlAnchoredStringItems(first: YamlStringItems, second: YamlStringItems) derives CanEqual
final case class YamlPeopleAlias(items: List[MTPerson], duplicate: List[MTPerson]) derives CanEqual
final case class YamlUnicodeField(café: Int) derives CanEqual
final case class YamlMultiDocumentConfig(
    primary: MTPerson,
    people: List[MTPerson],
    shape: MTShape,
    status: MixedArityEnum,
    unit: SealedNoArgVariants
) derives CanEqual

sealed trait YamlDiscSource derives CanEqual
case class YamlRabbit(host: String, queue: Maybe[String]) extends YamlDiscSource derives CanEqual, Schema
case class YamlKafka(broker: String)                      extends YamlDiscSource derives CanEqual, Schema
given Schema[YamlDiscSource] = Schema.derived[YamlDiscSource].discriminator("type")
case class YamlDiscTable(name: String, source: YamlDiscSource) derives CanEqual, Schema
case class YamlDiscRoot(tables: List[YamlDiscTable]) derives CanEqual, Schema
case class YamlDiscGroup(name: String, tables: List[YamlDiscTable]) derives CanEqual, Schema
case class YamlDiscDeep(groups: List[YamlDiscGroup]) derives CanEqual, Schema

sealed trait YamlAdjSource derives CanEqual
case class YamlAdjRabbit(host: String) extends YamlAdjSource derives CanEqual, Schema
given Schema[YamlAdjSource] = Schema.derived[YamlAdjSource].adjacent("type", "content")
case class YamlAdjRoot(sources: List[YamlAdjSource]) derives CanEqual, Schema

case class YamlBytesHolder(data: Span[Byte]) derives CanEqual, Schema
case class YamlTimesHolder(at: java.time.Instant, span: java.time.Duration) derives CanEqual, Schema

class YamlTest extends kyo.test.Test[Any]:

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

        "decodes flow mapping fields with YAML colon edge cases" in {
            val yaml = """{a:1, https://example.com, b: 2, "c":3}"""

            assert(
                Yaml.decode[Map[String, Option[Int]]](yaml) ==
                    Result.succeed(Map("a:1" -> None, "https://example.com" -> None, "b" -> Some(2), "c" -> Some(3)))
            )
        }

        "keeps comment markers inside quoted scalars" in {
            val yaml =
                """name: "Alice #1"
                  |age: 30 # comment
                  |""".stripMargin

            assert(Yaml.decode[MTPerson](yaml) == Result.succeed(MTPerson("Alice #1", 30)))
        }

        "decodes YAML double-quoted escapes through schema decode" in {
            val yaml =
                """nul: "\0"
                  |alarm: "\a"
                  |backspace: "\b"
                  |tab: "\t"
                  |lineFeed: "\n"
                  |verticalTab: "\v"
                  |formFeed: "\f"
                  |carriageReturn: "\r"
                  |escape: "\e"
                  |space: "\ "
                  |quote: "\""
                  |slash: "\/"
                  |backslash: "\\"
                  |nextLine: "\N"
                  |nonBreakingSpace: "\_"
                  |lineSeparator: "\L"
                  |paragraphSeparator: "\P"
                  |hex8: "\x41"
                  |hex16: "\u263A"
                  |hex32: "\U0001D11E"
                  |""".stripMargin +
                    "escapedTab: \"\\" + "\t" + "\"\n"

            assert(
                Yaml.decode[Map[String, String]](yaml) ==
                    Result.succeed(Map(
                        "nul"                -> "\u0000",
                        "alarm"              -> "\u0007",
                        "backspace"          -> "\b",
                        "tab"                -> "\t",
                        "lineFeed"           -> "\n",
                        "verticalTab"        -> "\u000b",
                        "formFeed"           -> "\f",
                        "carriageReturn"     -> "\r",
                        "escape"             -> "\u001b",
                        "space"              -> " ",
                        "quote"              -> "\"",
                        "slash"              -> "/",
                        "backslash"          -> "\\",
                        "nextLine"           -> "\u0085",
                        "nonBreakingSpace"   -> "\u00a0",
                        "lineSeparator"      -> "\u2028",
                        "paragraphSeparator" -> "\u2029",
                        "hex8"               -> "A",
                        "hex16"              -> "\u263a",
                        "hex32"              -> new String(Character.toChars(0x1d11e)),
                        "escapedTab"         -> "\t"
                    ))
            )
        }

        "reports source double-quoted escape errors with line and column context" in {
            import scala.NamedTuple.*

            def message(input: String): String =
                Yaml.decode[String](input) match
                    case Result.Failure(e: ParseException) => e.getMessage
                    case other                             => fail(s"Expected ParseException failure, got $other")

            val observed = (
                unknown = message("\"\\q\"\n"),
                shortHex = message("\"\\xF\"\n"),
                badHex = message("\"\\u12xz\"\n"),
                invalidCodePoint = message("\"\\UFFFFFFFF\"\n"),
                trailingSlash = message("\"abc\\\"\n")
            )

            assert((
                unknown = observed.unknown.contains("Invalid escape sequence \\q") && observed.unknown.contains("line 1"),
                shortHex = observed.shortHex.contains("Invalid escape sequence \\xF") && observed.shortHex.contains("line 1"),
                badHex = observed.badHex.contains("Invalid escape sequence \\u12xz") && observed.badHex.contains("line 1"),
                invalidCodePoint = observed.invalidCodePoint.contains("Invalid escape sequence \\UFFFFFFFF") &&
                    observed.invalidCodePoint.contains("line 1"),
                trailingSlash = observed.trailingSlash.contains("Unterminated double quoted scalar") &&
                    observed.trailingSlash.contains("line")
            ).toSeqMap == (
                unknown = true,
                shortHex = true,
                badHex = true,
                invalidCodePoint = true,
                trailingSlash = true
            ).toSeqMap)
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

        "decodeAll uses contextual reader config" in {
            given Yaml.ReaderConfig = Yaml.ReaderConfig.Default.copy(yamlVersion = Yaml.SpecVersion.Yaml11)

            val yaml =
                """---
                  |NO
                  |---
                  |yes
                  |""".stripMargin

            assert(Yaml.decodeAll[Boolean](yaml) == Result.succeed(Chunk(false, true)))
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

        "decodeBytes rejects multi-document streams for single-document decode" in {
            val yaml =
                """---
                  |name: Alice
                  |age: 30
                  |---
                  |name: Bob
                  |age: 25
                  |""".stripMargin
            val bytes = Span.from(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))

            assert(Yaml.decodeBytes[MTPerson](bytes).isFailure)
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

            assert(Yaml.decode[Map[String, String]](yaml, Yaml.DocumentIndex(0)) == Result.succeed(Map("text" -> "---\nliteral marker\n")))
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

        "decode merges document stream mapping fragments when configured" in {
            val yaml =
                """---
                  |primary:
                  |  name: Alice
                  |  age: 30
                  |---
                  |people:
                  |  - name: Bob
                  |    age: 25
                  |  - name: Charlie
                  |    age: 35
                  |---
                  |shape:
                  |  MTRectangle:
                  |    width: 3.0
                  |    height: 4.0
                  |---
                  |status:
                  |  Alpha:
                  |    x: 7
                  |---
                  |unit:
                  |  Unit2: {}
                  |""".stripMargin

            val config =
                Yaml.ReaderConfig.Default.copy(documentMode = Yaml.ReaderConfig.DocumentMode.MergeTopLevelMappings)

            assert(
                Yaml.decode[YamlMultiDocumentConfig](yaml, config) ==
                    Result.succeed(
                        YamlMultiDocumentConfig(
                            MTPerson("Alice", 30),
                            List(MTPerson("Bob", 25), MTPerson("Charlie", 35)),
                            MTRectangle(3.0, 4.0),
                            MixedArityEnum.Alpha(7),
                            SealedNoArgVariants.Unit2
                        )
                    )
            )
        }

        "decode merges document stream fragments for top-level sealed traits when configured" in {
            val yaml =
                """---
                  |Labeled:
                  |---
                  |  name: release
                  |""".stripMargin

            val config =
                Yaml.ReaderConfig.Default.copy(documentMode = Yaml.ReaderConfig.DocumentMode.MergeTopLevelMappings)

            assert(Yaml.decode[SealedNoArgVariants](yaml, config) == Result.succeed(SealedNoArgVariants.Labeled("release")))
        }

        "decode merges document stream fragments for top-level Scala 3 enums when configured" in {
            val yaml =
                """---
                  |Alpha:
                  |---
                  |  x: 7
                  |""".stripMargin

            val config =
                Yaml.ReaderConfig.Default.copy(documentMode = Yaml.ReaderConfig.DocumentMode.MergeTopLevelMappings)

            assert(Yaml.decode[MixedArityEnum](yaml, config) == Result.succeed(MixedArityEnum.Alpha(7)))
        }

        "decodeBytes merges document stream fragments when configured" in {
            val yaml =
                """---
                  |Alpha:
                  |---
                  |  x: 7
                  |""".stripMargin
            val bytes = Span.from(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            val config =
                Yaml.ReaderConfig.Default.copy(documentMode = Yaml.ReaderConfig.DocumentMode.MergeTopLevelMappings)

            assert(Yaml.decodeBytes[MixedArityEnum](bytes, config) == Result.succeed(MixedArityEnum.Alpha(7)))
        }

        "document index takes precedence over document stream merging" in {
            val yaml =
                """---
                  |name: Alice
                  |age: 30
                  |---
                  |name: Bob
                  |age: 25
                  |""".stripMargin

            val config = Yaml.ReaderConfig.Default.copy(
                documentIndex = Maybe(Yaml.DocumentIndex(1)),
                documentMode = Yaml.ReaderConfig.DocumentMode.MergeTopLevelMappings
            )

            assert(Yaml.decode[MTPerson](yaml, config) == Result.succeed(MTPerson("Bob", 25)))
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

        "decodes root scalars without spending nesting depth on synthetic nodes" in {
            val decoded = Yaml.decode[Int]("42\n", Yaml.ReaderConfig(maxDepth = 0))

            assert(decoded == Result.succeed(42))
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

        // Direct YamlReader tests below exercise Codec.Reader cursor semantics. Some inputs
        // intentionally contain a valid YAML node prefix followed by unrelated or malformed text;
        // those cases are not assertions that the entire source is a valid YAML document.
        "captures deferred values as YAML readers" in {
            val reader = kyo.internal.yaml.YamlReader("value: 42\n")

            discard(reader.objectStart())
            reader.fieldParse()
            val captured = reader.captureValue()

            assert(captured.isInstanceOf[kyo.internal.yaml.YamlReader])
            assert(captured.int() == 42)
        }

        "reader can pull a requested prefix without parsing later malformed content" in {
            val reader =
                kyo.internal.yaml.YamlReader(
                    """value: 42
                      |later: [unterminated
                      |""".stripMargin
                )

            discard(reader.objectStart())
            reader.fieldParse()

            val matchedField = reader.matchField("value".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            assert(matchedField == true)
            assert(reader.int() == 42)
        }

        "reader folds a requested plain scalar prefix without parsing later malformed content" in {
            val reader =
                kyo.internal.yaml.YamlReader(
                    """value: hello
                      |  world
                      |later: [unterminated
                      |""".stripMargin
                )

            discard(reader.objectStart())
            reader.fieldParse()

            val matchedField = reader.matchField("value".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            assert(matchedField == true)
            assert(reader.string() == "hello world")
        }

        "captureValue buffers only the requested subtree" in {
            val reader =
                kyo.internal.yaml.YamlReader(
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

        "captureValue pulls the current sequence element without parsing later malformed content" in {
            val reader =
                kyo.internal.yaml.YamlReader(
                    """- count: 7
                      |- [unterminated
                      |""".stripMargin
                )

            discard(reader.arrayStart())
            val captured = reader.captureValue()

            discard(captured.objectStart())
            captured.fieldParse()
            val count = captured.int()
            captured.objectEnd()
            assert(count == 7)
        }

        "captureValue buffers a root source value without parsing later malformed content" in {
            val reader = kyo.internal.yaml.YamlReader("[1, [unterminated")

            val captured = reader.captureValue()

            discard(captured.arrayStart())
            val hasElement = captured.hasNextElement()
            assert(hasElement == true)
            assert(captured.int() == 1)
        }

        "captureValue advances root source by one scalar node" in {
            val reader = kyo.internal.yaml.YamlReader("Alice\nBob\n")

            val first  = reader.captureValue()
            val second = reader.captureValue()
            assert(first.string() == "Alice")
            assert(second.string() == "Bob")
        }

        "captureValue advances root source by one flow collection" in {
            val reader = kyo.internal.yaml.YamlReader("[1]\n[2]\n")

            val first = reader.captureValue()
            discard(first.arrayStart())
            val firstHasElement = first.hasNextElement()
            val firstValue      = first.int()
            first.arrayEnd()

            val second = reader.captureValue()
            discard(second.arrayStart())
            val secondHasElement = second.hasNextElement()
            val secondValue      = second.int()
            second.arrayEnd()

            assert(firstHasElement == true)
            assert(firstValue == 1)
            assert(secondHasElement == true)
            assert(secondValue == 2)
        }

        "captureValue advances root source by one block collection" in {
            val reader =
                kyo.internal.yaml.YamlReader(
                    """name: Alice
                      |[2]
                      |""".stripMargin
                )

            val first = reader.captureValue()
            discard(first.objectStart())
            first.fieldParse()
            val firstMatchedField = first.matchField("name".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            val firstValue        = first.string()
            first.objectEnd()

            val second = reader.captureValue()
            discard(second.arrayStart())
            val secondHasElement = second.hasNextElement()
            val secondValue      = second.int()
            second.arrayEnd()

            assert(firstMatchedField == true)
            assert(firstValue == "Alice")
            assert(secondHasElement == true)
            assert(secondValue == 2)
        }

        "reader can pull a flow sequence prefix without parsing later malformed content" in {
            val reader = kyo.internal.yaml.YamlReader("[1, [unterminated")

            val size = reader.arrayStart()

            val hasElement = reader.hasNextElement()
            assert(size == -1)
            assert(hasElement == true)
            assert(reader.int() == 1)
        }

        "reader can pull a flow mapping prefix without parsing later malformed content" in {
            val reader = kyo.internal.yaml.YamlReader("{name: Alice, later: [unterminated")

            val size = reader.objectStart()

            val hasField = reader.hasNextField()
            reader.fieldParse()
            val matchedField = reader.matchField("name".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            assert(size == -1)
            assert(hasField == true)
            assert(matchedField == true)
            assert(reader.string() == "Alice")
        }

        "reader registers anchored mapping sources without parsing later malformed fields" in {
            val yaml =
                """value: &thing
                  |  count: 7
                  |  later: [unterminated
                  |""".stripMargin

            assert(Yaml.decode[YamlAnchoredCount](yaml) == Result.succeed(YamlAnchoredCount(YamlCountOnly(7))))
        }

        "reader registers inline scalar anchors without building an event tape" in {
            val yaml =
                """first: &name Alice
                  |second: *name
                  |""".stripMargin

            assert(Yaml.decode[YamlScalarAlias](yaml) == Result.succeed(YamlScalarAlias("Alice", "Alice")))
        }

        "reader replays anchored block mappings that contain flow collections" in {
            val yaml =
                """first: &thing
                  |  items: ["a, b", "c: d", plain]
                  |second: *thing
                  |""".stripMargin

            assert(
                Yaml.decode[YamlAnchoredStringItems](yaml) ==
                    Result.succeed(
                        YamlAnchoredStringItems(
                            YamlStringItems(List("a, b", "c: d", "plain")),
                            YamlStringItems(List("a, b", "c: d", "plain"))
                        )
                    )
            )
        }

        "reader applies source anchor expansion limits" in {
            val yaml =
                """first: &thing
                  |  items: ["a, b", "c: d", plain]
                  |second: *thing
                  |""".stripMargin

            Yaml.decode[YamlAnchoredStringItems](yaml, Yaml.ReaderConfig(maxCollectionSize = 3)) match
                case Result.Failure(e: LimitExceededException) =>
                    assert(e.getMessage.contains("Collection size"))
                case other => fail(s"Expected LimitExceededException failure, got $other")
            end match
        }

        "reader replays anchored block sequences with compact mapping entries" in {
            val yaml =
                """items: &people
                  |  - name: Alice
                  |    age: 30
                  |  - name: Bob
                  |    age: 25
                  |duplicate: *people
                  |""".stripMargin

            assert(
                Yaml.decode[YamlPeopleAlias](yaml) ==
                    Result.succeed(
                        YamlPeopleAlias(
                            List(MTPerson("Alice", 30), MTPerson("Bob", 25)),
                            List(MTPerson("Alice", 30), MTPerson("Bob", 25))
                        )
                    )
            )
        }

        "reader resolves source aliases before unrelated malformed fields" in {
            val yaml =
                """first: &name Alice
                  |second: *name
                  |later: [unterminated
                  |""".stripMargin

            assert(Yaml.decode[YamlScalarAlias](yaml) == Result.succeed(YamlScalarAlias("Alice", "Alice")))
        }

        "reader resolves source aliases in sequences before malformed elements" in {
            val yaml =
                """name: &name Alice
                  |items:
                  |  - *name
                  |later: [unterminated
                  |""".stripMargin

            assert(Yaml.decode[YamlSequenceAlias](yaml) == Result.succeed(YamlSequenceAlias("Alice", List("Alice"))))
        }

        "reader reports unknown source aliases with context" in {
            Yaml.decode[String]("*missing\n") match
                case Result.Failure(e: ParseException) =>
                    assert(e.getMessage.contains("Unknown alias 'missing'"))
                    assert(e.getMessage.contains("line 1"))
                case other => fail(s"Expected ParseException failure, got $other")
            end match
        }

        "reader reports malformed source aliases with context" in {
            import scala.NamedTuple.*

            def message(input: String): String =
                Yaml.decode[String](input) match
                    case Result.Failure(e: ParseException) => e.getMessage
                    case other                             => fail(s"Expected ParseException failure, got $other")

            val observed = (
                missingName = message("*\n"),
                trailingContent = message("*name trailing\n")
            )

            assert((
                missingName = observed.missingName.contains("Expected YAML alias name") && observed.missingName.contains("line 1"),
                trailingContent = observed.trailingContent.contains("Unexpected content after YAML alias") &&
                    observed.trailingContent.contains("line 1")
            ).toSeqMap == (
                missingName = true,
                trailingContent = true
            ).toSeqMap)
        }

        "isNil reads root source nulls without parsing later malformed content" in {
            List("null", "~", "!!null ignored").foreach { value =>
                val reader = kyo.internal.yaml.YamlReader(s"$value\nlater: [unterminated")

                assert(reader.isNil())
            }

            val empty = kyo.internal.yaml.YamlReader("\n")
            assert(empty.isNil())
        }

        "isNil preserves non-null source scalars for later reads" in {
            val reader = kyo.internal.yaml.YamlReader("Alice\nlater: [unterminated")

            val notNil = !reader.isNil()
            assert(notNil == true)
            assert(reader.string() == "Alice")
        }

        "captureValue advances root source by one block sequence" in {
            import scala.NamedTuple.*

            val reader =
                kyo.internal.yaml.YamlReader(
                    """- name: Alice
                      |  age: 30
                      |- name: Bob
                      |  age: 25
                      |next: done
                      |""".stripMargin
                )

            val captured = reader.captureValue()
            val decoded = Result.catching[DecodeException] {
                summon[Schema[List[MTPerson]]].readFrom(captured)
            }
            val next = reader.captureValue()
            discard(next.objectStart())
            next.fieldParse()
            val observed = (
                decoded = decoded,
                nextField = next.lastFieldName(),
                nextValue = next.string()
            )
            next.objectEnd()

            assert(observed.toSeqMap == (
                decoded = Result.succeed(List(MTPerson("Alice", 30), MTPerson("Bob", 25))),
                nextField = "next",
                nextValue = "done"
            ).toSeqMap)
        }

        "captureValue advances root source by one flow mapping" in {
            import scala.NamedTuple.*

            val reader = kyo.internal.yaml.YamlReader("{name: Alice, text: 'brace } inside'}\n[1]\n")

            val captured = reader.captureValue()
            val decoded = Result.catching[DecodeException] {
                summon[Schema[scala.collection.immutable.Map[String, String]]].readFrom(captured)
            }
            val next = reader.captureValue()
            discard(next.arrayStart())
            val hasNext = next.hasNextElement()
            val value   = next.int()
            val hasMore = next.hasNextElement()
            next.arrayEnd()

            assert((
                decoded = decoded,
                hasNext = hasNext,
                value = value,
                hasMore = hasMore
            ).toSeqMap == (
                decoded = Result.succeed(scala.collection.immutable.Map("name" -> "Alice", "text" -> "brace } inside")),
                hasNext = true,
                value = 1,
                hasMore = false
            ).toSeqMap)
        }

        "captureValue preserves quoted delimiters inside a root flow collection" in {
            import scala.NamedTuple.*

            val reader =
                kyo.internal.yaml.YamlReader(
                    """["a ] # not close", [1, 2]]
                      |name: next
                      |""".stripMargin
                )

            val captured = reader.captureValue()
            discard(captured.arrayStart())
            val hasText   = captured.hasNextElement()
            val text      = captured.string()
            val hasValues = captured.hasNextElement()
            discard(captured.arrayStart())
            val hasFirstValue  = captured.hasNextElement()
            val firstValue     = captured.int()
            val hasSecondValue = captured.hasNextElement()
            val secondValue    = captured.int()
            val hasMoreValues  = captured.hasNextElement()
            captured.arrayEnd()
            val hasMoreElements = captured.hasNextElement()
            captured.arrayEnd()

            val next = reader.captureValue()
            discard(next.objectStart())
            next.fieldParse()
            val observed = (
                hasText = hasText,
                text = text,
                hasValues = hasValues,
                hasFirstValue = hasFirstValue,
                firstValue = firstValue,
                hasSecondValue = hasSecondValue,
                secondValue = secondValue,
                hasMoreValues = hasMoreValues,
                hasMoreElements = hasMoreElements,
                nextField = next.lastFieldName(),
                nextValue = next.string()
            )
            next.objectEnd()

            assert(observed.toSeqMap == (
                hasText = true,
                text = "a ] # not close",
                hasValues = true,
                hasFirstValue = true,
                firstValue = 1,
                hasSecondValue = true,
                secondValue = 2,
                hasMoreValues = false,
                hasMoreElements = false,
                nextField = "name",
                nextValue = "next"
            ).toSeqMap)
        }

        "skip advances the current sequence element without parsing later malformed content" in {
            val reader =
                kyo.internal.yaml.YamlReader(
                    """- ignored:
                      |    count: 7
                      |- [unterminated
                      |""".stripMargin
                )

            discard(reader.arrayStart())
            reader.skip()
            assert(reader.hasNextElement())
        }

        "matches UTF-8 field names without lossy byte comparisons" in {
            assert(Yaml.decode[YamlUnicodeField]("café: 7\n") == Result.succeed(YamlUnicodeField(7)))
        }

        "reader accepts UTF-8 byte input directly" in {
            val bytes  = Span.from("café\n".getBytes(StandardCharsets.UTF_8))
            val reader = kyo.internal.yaml.YamlReader(bytes)

            assert(reader.string() == "café")
        }

        "decodes UTF-8 bytes with explicit limits and document selection" in {
            val stream = Span.from("---\nname: Alice\nage: 30\n---\nname: Bob\nage: 25\n".getBytes(StandardCharsets.UTF_8))
            val single = Span.from("name: Alice\nage: 30\n".getBytes(StandardCharsets.UTF_8))

            assert(Yaml.decodeBytes[MTPerson](single, 64, 1024) == Result.succeed(MTPerson("Alice", 30)))
            assert(Yaml.decodeBytes[MTPerson](stream, Yaml.DocumentIndex(1)) == Result.succeed(MTPerson("Bob", 25)))
        }

        "decodes every document of a stream with explicit limits" in {
            val yaml = "---\nname: Alice\nage: 30\n---\nname: Bob\nage: 25\n"

            assert(
                Yaml.decodeAll[MTPerson](yaml, 64, 1024) ==
                    Result.succeed(Chunk(MTPerson("Alice", 30), MTPerson("Bob", 25)))
            )
        }

        "fails decodeAll when a stream document is missing fields" in {
            val yaml = "---\nname: Alice\nage: 30\n---\nname: Bob\n"

            assert(Yaml.decodeAll[MTPerson](yaml).isFailure)
        }

        "parses an empty source as an empty scalar" in {
            val obtained = Yaml.parse("") match
                case Result.Success(Yaml.Node.Scalar(value, _)) => value.isEmpty
                case _                                          => false
            assert(obtained)
        }

        "decodes a single-document CST stream through the top-level helper" in {
            val stream = Yaml.cstAll("name: Alice\nage: 30\n").getOrThrow

            assert(Yaml.decode[MTPerson](stream) == Result.succeed(MTPerson("Alice", 30)))
        }

        "visits scalars and aliases through a handler that overrides only mappingStart" in {
            val countingMappings =
                new Yaml.Events.Handler[Int, Nothing]:
                    override def mappingStart(context: Int, meta: Yaml.Meta, size: Maybe[Int]): Result[Nothing, Int] =
                        Result.succeed(context + 1)

            assert(Yaml.Events.visit("x: &a 1\ny: *a\n", 0)(countingMappings) == Result.succeed(1))
        }

        "discriminated source in single-element list decodes to Success" in {
            val yaml =
                """tables:
                  |  - name: t1
                  |    source:
                  |      type: YamlRabbit
                  |      host: localhost
                  |""".stripMargin
            val result = Yaml.decode[YamlDiscRoot](yaml)
            assert(result == Result.succeed(YamlDiscRoot(List(YamlDiscTable("t1", YamlRabbit("localhost", Absent))))))
        }

        // guards StringIndexOutOfBoundsException regression
        "discriminated source decode in single-element list is not a Panic" in {
            val yaml =
                """tables:
                  |  - name: t1
                  |    source:
                  |      type: YamlRabbit
                  |      host: localhost
                  |""".stripMargin
            val result = Yaml.decode[YamlDiscRoot](yaml)
            assert(!result.isPanic)
            assert(result == Result.succeed(YamlDiscRoot(List(YamlDiscTable("t1", YamlRabbit("localhost", Absent))))))
        }

        "unknown discriminator variant in list element is typed failure not panic" in {
            val yaml =
                """tables:
                  |  - name: t1
                  |    source:
                  |      type: Unknown
                  |      host: localhost
                  |""".stripMargin
            val result = Yaml.decode[YamlDiscRoot](yaml)
            assert(result.isFailure)
            assert(!result.isPanic)
        }

        "multi-element list with discriminated source decodes all elements correctly" in {
            val yaml =
                """tables:
                  |  - name: t1
                  |    source:
                  |      type: YamlRabbit
                  |      host: h1
                  |  - name: t2
                  |    source:
                  |      type: YamlKafka
                  |      broker: b2
                  |""".stripMargin
            val result = Yaml.decode[YamlDiscRoot](yaml)
            assert(result == Result.succeed(YamlDiscRoot(List(
                YamlDiscTable("t1", YamlRabbit("h1", Absent)),
                YamlDiscTable("t2", YamlKafka("b2"))
            ))))
        }

        "depth: nested list with discriminated source, inner list exhausted at EOF" in {
            val yaml =
                """groups:
                  |  - name: g1
                  |    tables:
                  |      - name: t1
                  |        source:
                  |          type: YamlRabbit
                  |          host: h1
                  |""".stripMargin
            val result = Yaml.decode[YamlDiscDeep](yaml)
            assert(result == Result.succeed(YamlDiscDeep(List(
                YamlDiscGroup("g1", List(YamlDiscTable("t1", YamlRabbit("h1", Absent))))
            ))))
        }

        "depth: nested list with outer sibling group after inner exhausted list" in {
            val yaml =
                """groups:
                  |  - name: g1
                  |    tables:
                  |      - name: t1
                  |        source:
                  |          type: YamlRabbit
                  |          host: h1
                  |  - name: g2
                  |    tables:
                  |      - name: t2
                  |        source:
                  |          type: YamlKafka
                  |          broker: b2
                  |""".stripMargin
            val result = Yaml.decode[YamlDiscDeep](yaml)
            assert(result == Result.succeed(YamlDiscDeep(List(
                YamlDiscGroup("g1", List(YamlDiscTable("t1", YamlRabbit("h1", Absent)))),
                YamlDiscGroup("g2", List(YamlDiscTable("t2", YamlKafka("b2"))))
            ))))
        }

        "flow-sequence: inline flow list and nested-flow decode to Success" in {
            val yaml1    = "tables: [{name: t1, source: {type: YamlRabbit, host: localhost}}]"
            val yaml2    = "{tables: [{name: t1, source: {type: YamlRabbit, host: localhost}}]}"
            val expected = Result.succeed(YamlDiscRoot(List(YamlDiscTable("t1", YamlRabbit("localhost", Absent)))))
            assert(Yaml.decode[YamlDiscRoot](yaml1) == expected)
            assert(Yaml.decode[YamlDiscRoot](yaml2) == expected)
        }

        "EOF-adjacent: trailing blank lines and no trailing newline both decode to the same value" in {
            val yamlNoTrail = "tables:\n  - name: t1\n    source:\n      type: YamlRabbit\n      host: localhost"
            val yamlTrail =
                """tables:
                  |  - name: t1
                  |    source:
                  |      type: YamlRabbit
                  |      host: localhost
                  |
                  |
                  |""".stripMargin
            val expected = Result.succeed(YamlDiscRoot(List(YamlDiscTable("t1", YamlRabbit("localhost", Absent)))))
            assert(Yaml.decode[YamlDiscRoot](yamlNoTrail) == expected)
            assert(Yaml.decode[YamlDiscRoot](yamlTrail) == expected)
        }

        "direct decode and list-nested decode of a discriminated source agree" in {
            val directYaml =
                """type: YamlRabbit
                  |host: localhost
                  |
                  |""".stripMargin
            val listYaml =
                """tables:
                  |  - name: t1
                  |    source:
                  |      type: YamlRabbit
                  |      host: localhost
                  |
                  |""".stripMargin
            val directResult = Yaml.decode[YamlDiscSource](directYaml)
            val listResult   = Yaml.decode[YamlDiscRoot](listYaml).map(_.tables.head.source)
            assert(directResult == listResult)
            assert(directResult == Result.succeed(YamlRabbit("localhost", Absent)))
        }

        "sibling-key-after-block and comment-in-block both decode to Success" in {
            val yamlSibling =
                """tables:
                  |  - name: t1
                  |    source:
                  |      type: YamlRabbit
                  |      host: localhost
                  |    extra: ignored
                  |""".stripMargin
            val yamlComment =
                """tables:
                  |  - name: t1
                  |    source:
                  |      # note
                  |      type: YamlRabbit
                  |      host: localhost
                  |""".stripMargin
            val expected = Result.succeed(YamlDiscRoot(List(YamlDiscTable("t1", YamlRabbit("localhost", Absent)))))
            assert(Yaml.decode[YamlDiscRoot](yamlSibling) == expected)
            assert(Yaml.decode[YamlDiscRoot](yamlComment) == expected)
        }

        "adjacent-tagged union in list element decodes correctly" in {
            val yaml =
                """sources:
                  |  - type: YamlAdjRabbit
                  |    content:
                  |      host: localhost
                  |""".stripMargin
            val result = Yaml.decode[YamlAdjRoot](yaml)
            assert(result == Result.succeed(YamlAdjRoot(List(YamlAdjRabbit("localhost")))))
        }

        "non-regression: direct YAML decode and JSON list decode still succeed" in {
            val yamlDirect =
                """type: YamlRabbit
                  |host: localhost
                  |queue: q1
                  |""".stripMargin
            val jsonList = """{"tables":[{"name":"t1","source":{"type":"YamlRabbit","host":"localhost"}}]}"""
            assert(Yaml.decode[YamlDiscSource](yamlDirect) == Result.succeed(YamlRabbit("localhost", Present("q1"))))
            assert(Json.decode[YamlDiscRoot](jsonList) == Result.succeed(YamlDiscRoot(List(YamlDiscTable(
                "t1",
                YamlRabbit("localhost", Absent)
            )))))
        }

        "round trips Span[Byte] as a base64 scalar" in {
            val value   = YamlBytesHolder(Span.from(Array[Byte](1, 2, 3)))
            val yaml    = Yaml.encode(value)
            val decoded = Yaml.decode[YamlBytesHolder](yaml)
            assert(CodecTestSupport.sameBytes(decoded.getOrThrow.data, value.data))
        }

        "round trips Instant and Duration as quoted scalars" in {
            val value = YamlTimesHolder(
                java.time.Instant.parse("2026-07-09T12:00:00.500Z"),
                java.time.Duration.ofSeconds(12, 345)
            )
            val yaml = Yaml.encode(value)
            assert(Yaml.decode[YamlTimesHolder](yaml) == Result.succeed(value))
        }

    }

end YamlTest
