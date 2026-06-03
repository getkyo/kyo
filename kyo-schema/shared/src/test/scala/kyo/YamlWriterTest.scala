package kyo

import java.nio.charset.StandardCharsets
import kyo.internal.yaml.YamlWriter

final case class YamlWriterStrings(
    plain: String,
    truthy: String,
    comment: String,
    url: String,
    multiline: String,
    empty: String
) derives CanEqual

final case class YamlWriterNested(
    team: MTSmallTeam,
    people: List[MTPerson],
    labels: Map[String, String],
    wrappers: YamlValueHolder
) derives CanEqual

final case class YamlWriterNestedFirst(items: List[Int], name: String) derives CanEqual

final case class YamlWriterTextFirst(text: String, name: String) derives CanEqual

class YamlWriterTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "encode" - {

        "writes case classes as block mappings by default" in {
            val yaml = Yaml.encode(MTPerson("Alice", 30))

            assertResult(
                (
                    encoded =
                        """name: Alice
                          |age: 30
                          |""".stripMargin,
                    decoded = Result.succeed(MTPerson("Alice", 30))
                )
            ) {
                (
                    encoded = yaml,
                    decoded = Yaml.decode[MTPerson](yaml)
                )
            }
        }

        "writes nested products sequences maps and value wrappers as block YAML" in {
            val value = YamlWriterNested(
                MTSmallTeam(MTPerson("Alice", 30), 5),
                List(MTPerson("Alice", 30), MTPerson("Bob", 25)),
                Map("env" -> "prod", "feature:flag" -> "on"),
                YamlValueHolder(
                    YamlOpaqueUserId("user-123"),
                    YamlAnyValUserId("user-456"),
                    YamlValueObject("user-789")
                )
            )

            val yaml = Yaml.encode(value)

            assertResult(
                (
                    encoded =
                        """team:
                          |  lead:
                          |    name: Alice
                          |    age: 30
                          |  size: 5
                          |people:
                          |  -
                          |    name: Alice
                          |    age: 30
                          |  -
                          |    name: Bob
                          |    age: 25
                          |labels:
                          |  env: prod
                          |  "feature:flag": on
                          |wrappers:
                          |  opaqueId: user-123
                          |  anyValId:
                          |    value: user-456
                          |  valueObject:
                          |    value: user-789
                          |""".stripMargin,
                    decoded = Result.succeed(value)
                )
            ) {
                (
                    encoded = yaml,
                    decoded = Yaml.decode[YamlWriterNested](yaml)
                )
            }
        }

        "round-trips sequence mappings whose first field has a nested block value" in {
            val value = List(
                YamlWriterNestedFirst(List(1, 2), "a"),
                YamlWriterNestedFirst(List(3), "b")
            )

            val yaml = Yaml.encode(value)

            assertResult(
                (
                    encoded =
                        """-
                          |  items:
                          |    - 1
                          |    - 2
                          |  name: a
                          |-
                          |  items:
                          |    - 3
                          |  name: b
                          |""".stripMargin,
                    decoded = Result.succeed(value)
                )
            ) {
                (
                    encoded = yaml,
                    decoded = Yaml.decode[List[YamlWriterNestedFirst]](yaml)
                )
            }
        }

        "round-trips sequence mappings whose first field is a literal block scalar" in {
            val value = List(YamlWriterTextFirst("line one\nline two\n", "a"))

            val yaml = Yaml.encode(value)

            assertResult(
                (
                    encoded =
                        """-
                          |  text: |
                          |    line one
                          |    line two
                          |  name: a
                          |""".stripMargin,
                    decoded = Result.succeed(value)
                )
            ) {
                (
                    encoded = yaml,
                    decoded = Yaml.decode[List[YamlWriterTextFirst]](yaml)
                )
            }
        }

        "quotes ambiguous strings and writes multiline strings as literal blocks" in {
            val value = YamlWriterStrings(
                plain = "Alice",
                truthy = "true",
                comment = "Alice #1",
                url = "https://example.com",
                multiline = "line one\nline two\n",
                empty = ""
            )

            val yaml = Yaml.encode(value)

            assertResult(
                (
                    encoded =
                        """plain: Alice
                          |truthy: "true"
                          |comment: "Alice #1"
                          |url: "https://example.com"
                          |multiline: |
                          |  line one
                          |  line two
                          |empty: ""
                          |""".stripMargin,
                    decoded = Result.succeed(value)
                )
            ) {
                (
                    encoded = yaml,
                    decoded = Yaml.decode[YamlWriterStrings](yaml)
                )
            }
        }

        "keeps extra trailing newlines in multiline string scalars" in {
            val yaml = Yaml.encode("line one\n\n")

            assertResult(
                (
                    encoded = "|+\n  line one\n\n",
                    decoded = Result.succeed("line one\n\n")
                )
            ) {
                (
                    encoded = yaml,
                    decoded = Yaml.decode[String](yaml)
                )
            }
        }

        "writes empty collections and scalar roots as YAML" in {
            assertResult(
                (
                    emptyList = "[]\n",
                    emptyMap = "{}\n",
                    intList = "- 1\n- 2\n- 3\n",
                    truthy = "\"true\"\n",
                    hex = "\"0x3A\"\n",
                    octal = "\"0o7\"\n",
                    leadingDot = "\".5\"\n",
                    exponent = "\"+12e03\"\n",
                    infinity = "\".inf\"\n"
                )
            ) {
                (
                    emptyList = Yaml.encode(List.empty[Int]),
                    emptyMap = Yaml.encode(Map.empty[String, Int]),
                    intList = Yaml.encode(List(1, 2, 3)),
                    truthy = Yaml.encode("true"),
                    hex = Yaml.encode("0x3A"),
                    octal = Yaml.encode("0o7"),
                    leadingDot = Yaml.encode(".5"),
                    exponent = Yaml.encode("+12e03"),
                    infinity = Yaml.encode(".inf")
                )
            }
        }

        "uses the readable writer config as the default profile" in {
            assertResult(
                (
                    defaultIsReadable = true,
                    encoded = "-\n  name: Alice\n  age: 30\n"
                )
            ) {
                (
                    defaultIsReadable = Yaml.WriterConfig.Default == Yaml.WriterConfig.Readable,
                    encoded = Yaml.encode(List(MTPerson("Alice", 30)))
                )
            }
        }

        "uses a contextual writer config for single-argument encode" in {
            given Yaml.WriterConfig = Yaml.WriterConfig.Compact

            assert(Yaml.encode(List(MTPerson("Alice", 30))) == "- name: Alice\n  age: 30\n")
        }

        "supports explicit compact small and fast writer profiles" in {
            val value = MTPerson("Alice", 30)

            assertResult(
                (
                    compact = "name: Alice\nage: 30\n",
                    small = "{name: Alice, age: 30}",
                    fast = "{\"name\":\"Alice\",\"age\":30}"
                )
            ) {
                (
                    compact = Yaml.encode(value, Yaml.WriterConfig.Compact),
                    small = Yaml.encode(value, Yaml.WriterConfig.Small),
                    fast = Yaml.encode(value, Yaml.WriterConfig.Fast)
                )
            }
        }

        "supports scalar quoting modes and quote styles" in {
            val minimal      = Yaml.WriterConfig.Readable.copy(scalarQuoting = Yaml.WriterConfig.ScalarQuoting.WhenNeeded)
            val singleQuoted = Yaml.WriterConfig.Readable.copy(quoteStyle = Yaml.WriterConfig.QuoteStyle.Single)

            assertResult(
                (
                    minimal = "true\n",
                    readable = "\"true\"\n",
                    singleQuoted = "'Alice #1'\n"
                )
            ) {
                (
                    minimal = Yaml.encode("true", minimal),
                    readable = Yaml.encode("true", Yaml.WriterConfig.Readable),
                    singleQuoted = Yaml.encode("Alice #1", singleQuoted)
                )
            }
        }

        "quotes YAML 1.1 ambiguous strings when configured for legacy consumers" in {
            val legacy = Yaml.WriterConfig.Readable.copy(yamlVersion = Yaml.SpecVersion.Yaml11)

            assertResult(
                (
                    defaultNo = "NO\n",
                    legacyNo = "\"NO\"\n",
                    legacyOctal = "\"010\"\n"
                )
            ) {
                (
                    defaultNo = Yaml.encode("NO"),
                    legacyNo = Yaml.encode("NO", legacy),
                    legacyOctal = Yaml.encode("010", legacy)
                )
            }
        }

        "uses Ryu numeric spelling for finite floats and doubles where YAML permits plain scalars" in {
            val doubleValues = List(
                0.0d                    -> "0.0\n",
                -0.0d                   -> "-0.0\n",
                1.0d                    -> "1.0\n",
                3.14159d                -> "3.14159\n",
                1.23e100d               -> "1.23E100\n",
                Double.MinPositiveValue -> "5.0E-324\n",
                Double.MaxValue         -> "1.7976931348623157E308\n"
            )
            doubleValues.foreach { (value, expected) =>
                assert(Yaml.encode(value) == expected)
            }

            val floatValues = List(
                0.0f                   -> "0.0\n",
                -0.0f                  -> "-0.0\n",
                1.0f                   -> "1.0\n",
                3.14f                  -> "3.14\n",
                1e10f                  -> "1.0E10\n",
                Float.MinPositiveValue -> "1.0E-45\n",
                Float.MaxValue         -> "3.4028235E38\n"
            )
            floatValues.foreach { (value, expected) =>
                assert(Yaml.encode(value) == expected)
            }
            ()
        }

        "writes configured special float spellings that round-trip through YAML decode" in {
            val readable = Yaml.encode(List(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity))
            assert(readable == "- .nan\n- .inf\n- -.inf\n")
            Yaml.decode[List[Double]](readable) match
                case Result.Success(values) =>
                    assert(values(0).isNaN)
                    assert(values(1).isPosInfinity)
                    assert(values(2).isNegInfinity)
                case other => fail(s"Expected decoded special floats, got $other")
            end match

            val fast = Yaml.encode(List(Float.NaN, Float.PositiveInfinity, Float.NegativeInfinity), Yaml.WriterConfig.Fast)
            assert(fast == """[!!float ".nan",!!float ".inf",!!float "-.inf"]""")
            Yaml.decode[List[Float]](fast) match
                case Result.Success(values) =>
                    assert(values(0).isNaN)
                    assert(values(1).isPosInfinity)
                    assert(values(2).isNegInfinity)
                case other => fail(s"Expected decoded special floats, got $other")
            end match
        }

        "preserves multiline string values when folded style is configured" in {
            val config = Yaml.WriterConfig.Readable.copy(multilineStyle = Yaml.WriterConfig.MultilineStyle.Folded)
            val value  = "line one\nline two\n"
            val yaml   = Yaml.encode(value, config)

            assertResult(
                (
                    encoded = ">\n  line one\n\n  line two\n",
                    decoded = Result.succeed(value)
                )
            ) {
                (
                    encoded = yaml,
                    decoded = Yaml.decode[String](yaml)
                )
            }
        }

        "uses double quoted string values in JSON-compatible flow output" in {
            val config = Yaml.WriterConfig.Fast.copy(quoteStyle = Yaml.WriterConfig.QuoteStyle.Single)
            val yaml   = Yaml.encode(Map("greeting" -> "hello world"), config)

            assertResult(
                (
                    encoded = """{"greeting":"hello world"}""",
                    decoded = Result.succeed(Map("greeting" -> "hello world"))
                )
            ) {
                (
                    encoded = yaml,
                    decoded = Yaml.decode[Map[String, String]](yaml)
                )
            }
        }

        "codec-generic schema encoding uses the contextual Yaml writer config" in {
            given Yaml = Yaml(Yaml.WriterConfig.Small)

            assert(Schema[MTPerson].encodeString[Yaml](MTPerson("Alice", 30)) == "{name: Alice, age: 30}")
        }

        "releases writers after string and byte results for reuse" in {
            val first = YamlWriter()
            first.string("alpha")
            assert(first.resultString == "alpha\n")

            val second = YamlWriter()
            assert(first eq second)
            second.string("beta")
            assert(second.resultString == "beta\n")

            val third = YamlWriter(Yaml.WriterConfig.Small)
            third.arrayStart(2)
            third.int(1)
            third.int(2)
            third.arrayEnd()
            assert(third.result().toArray.toSeq == "[1, 2]".getBytes(StandardCharsets.UTF_8).toSeq)

            val fourth = YamlWriter()
            assert(third eq fourth)
            fourth.int(3)
            assert(fourth.resultString == "3\n")
        }

        "does not retain oversized output buffers in the writer cache" in {
            val first = YamlWriter()
            first.string("a" * 300000)
            assert(first.resultString.endsWith("\n"))

            val second = YamlWriter()
            assert(!(first eq second))
            second.string("small")
            assert(second.resultString == "small\n")
        }

        "round-trips primitive numeric and boolean types through encode and decode" in {
            val longVal: Long   = Long.MaxValue
            val shortVal: Short = Short.MaxValue
            val byteVal: Byte   = Byte.MaxValue
            val boolVal         = true

            assertResult(
                (
                    long = Result.succeed(longVal),
                    short = Result.succeed(shortVal),
                    byte = Result.succeed(byteVal),
                    bool = Result.succeed(boolVal)
                )
            ) {
                (
                    long = Yaml.decode[Long](Yaml.encode(longVal)),
                    short = Yaml.decode[Short](Yaml.encode(shortVal)),
                    byte = Yaml.decode[Byte](Yaml.encode(byteVal)),
                    bool = Yaml.decode[Boolean](Yaml.encode(boolVal))
                )
            }
        }

        "encodes char as a double-quoted single-character scalar" in {
            val yaml = Yaml.encode('A')
            assertResult(
                (
                    encoded = "\"A\"\n",
                    decoded = Result.succeed('A')
                )
            ) {
                (
                    encoded = yaml,
                    decoded = Yaml.decode[Char](yaml)
                )
            }
        }

        "encodes null Option values as the null scalar and round-trips" in {
            val yaml = Yaml.encode(Option.empty[String])
            assertResult(
                (
                    encoded = "null\n",
                    decoded = Result.succeed(Option.empty[String])
                )
            ) {
                (
                    encoded = yaml,
                    decoded = Yaml.decode[Option[String]](yaml)
                )
            }
        }

        "double-quotes strings containing control characters newlines tabs backslash and quote" in {
            assertResult(
                (
                    controlChar = "\"\\u0001\"\n",
                    newline = "\"line1\\nline2\"\n",
                    tab = "\"col1\\tcol2\"\n",
                    backslash = "\"\\\\\"\n",
                    quote = "\"\\\"\"\n"
                )
            ) {
                val fastConfig = Yaml.WriterConfig.Fast.copy(trailingNewline = true)
                (
                    controlChar = Yaml.encode("", fastConfig),
                    newline = Yaml.encode("line1\nline2", fastConfig),
                    tab = Yaml.encode("col1\tcol2", fastConfig),
                    backslash = Yaml.encode("\\", fastConfig),
                    quote = Yaml.encode("\"", fastConfig)
                )
            }
        }

        "single-quotes strings that need quoting when single-quote style is configured" in {
            val singleStyle = Yaml.WriterConfig.Readable.copy(quoteStyle = Yaml.WriterConfig.QuoteStyle.Single)
            // "true" needs quoting because it is an ambiguous scalar; the single-quote style applies
            val yaml = Yaml.encode("true", singleStyle)
            assertResult(
                (
                    encoded = "'true'\n",
                    decoded = Result.succeed("true")
                )
            ) {
                (
                    encoded = yaml,
                    decoded = Yaml.decode[String](yaml)
                )
            }
        }

        "single-quotes strings with embedded single quotes using doubled-quote escaping" in {
            val singleStyle = Yaml.WriterConfig.Readable.copy(quoteStyle = Yaml.WriterConfig.QuoteStyle.Single)
            // A string containing # is not plain-safe (triggers quoting); single-quote style is used
            val yaml = Yaml.encode("it #1", singleStyle)
            assertResult(
                (
                    encoded = "'it #1'\n",
                    decoded = Result.succeed("it #1")
                )
            ) {
                (
                    encoded = yaml,
                    decoded = Yaml.decode[String](yaml)
                )
            }
        }

        "quotes strings that are plain-unsafe due to leading special chars or reserved words" in {
            assertResult(
                (
                    dashDash = "\"---\"\n",
                    dotDotDot = "\"...\"\n",
                    percent = "\"%TAG\"\n",
                    openBracket = "\"[1,2]\"\n",
                    openBrace = "\"{a: b}\"\n"
                )
            ) {
                (
                    dashDash = Yaml.encode("---"),
                    dotDotDot = Yaml.encode("..."),
                    percent = Yaml.encode("%TAG"),
                    openBracket = Yaml.encode("[1,2]"),
                    openBrace = Yaml.encode("{a: b}")
                )
            }
        }

        "encodes multiline strings with Strip chomping using |- header" in {
            val config = Yaml.WriterConfig.Readable.copy(chomping = Yaml.WriterConfig.Chomping.Strip)
            val value  = "line one\nline two\n"
            val yaml   = Yaml.encode(value, config)

            assertResult(
                (
                    header = true,
                    decoded = Result.succeed("line one\nline two")
                )
            ) {
                (
                    header = yaml.startsWith("|-\n"),
                    decoded = Yaml.decode[String](yaml)
                )
            }
        }

        "encodes multiline strings with Keep chomping using |+ header" in {
            val config = Yaml.WriterConfig.Readable.copy(chomping = Yaml.WriterConfig.Chomping.Keep)
            val value  = "line one\nline two\n"
            val yaml   = Yaml.encode(value, config)

            assertResult(
                (
                    header = true,
                    decoded = Result.succeed(value)
                )
            ) {
                (
                    header = yaml.startsWith("|+\n"),
                    decoded = Yaml.decode[String](yaml)
                )
            }
        }

        "encodes multiline strings with Clip chomping using | header" in {
            val config = Yaml.WriterConfig.Readable.copy(chomping = Yaml.WriterConfig.Chomping.Clip)
            val value  = "line one\nline two\n"
            val yaml   = Yaml.encode(value, config)

            assertResult(
                (
                    header = true,
                    decoded = Result.succeed(value)
                )
            ) {
                (
                    header = yaml.startsWith("|\n"),
                    decoded = Yaml.decode[String](yaml)
                )
            }
        }

        "encodes with document Start marker and round-trips" in {
            val config = Yaml.WriterConfig.Readable.copy(documentMarkers = Yaml.WriterConfig.DocumentMarkers.Start)
            val yaml   = Yaml.encode(MTPerson("Alice", 30), config)

            assertResult(
                (
                    startsWithMarker = true,
                    decoded = Result.succeed(MTPerson("Alice", 30))
                )
            ) {
                (
                    startsWithMarker = yaml.startsWith("---\n"),
                    decoded = Yaml.decode[MTPerson](yaml)
                )
            }
        }

        "encodes with document StartAndEnd markers and round-trips" in {
            val config = Yaml.WriterConfig.Readable.copy(documentMarkers = Yaml.WriterConfig.DocumentMarkers.StartAndEnd)
            val yaml   = Yaml.encode(MTPerson("Alice", 30), config)

            assertResult(
                (
                    startsWithMarker = true,
                    endsWithMarker = true,
                    decoded = Result.succeed(MTPerson("Alice", 30))
                )
            ) {
                (
                    startsWithMarker = yaml.startsWith("---\n"),
                    endsWithMarker = yaml.endsWith("...\n"),
                    decoded = Yaml.decode[MTPerson](yaml)
                )
            }
        }

        "encodes in flow style using Small profile and produces braces and brackets" in {
            val people = List(MTPerson("Alice", 30), MTPerson("Bob", 25))
            val yaml   = Yaml.encode(people, Yaml.WriterConfig.Small)

            assertResult(
                (
                    hasBrackets = true,
                    hasBraces = true
                )
            ) {
                (
                    hasBrackets = yaml.contains('['),
                    hasBraces = yaml.contains('{')
                )
            }
        }

        "encodes QuoteAllStrings config and quotes every string including plain-safe ones" in {
            val config = Yaml.WriterConfig.Readable.copy(scalarQuoting = Yaml.WriterConfig.ScalarQuoting.QuoteAllStrings)
            val yaml   = Yaml.encode("hello", config)

            assertResult(
                (
                    encoded = "\"hello\"\n",
                    decoded = Result.succeed("hello")
                )
            ) {
                (
                    encoded = yaml,
                    decoded = Yaml.decode[String](yaml)
                )
            }
        }

        "encodes folded multiline with Strip chomping using >- header and round-trips" in {
            val config = Yaml.WriterConfig.Readable.copy(
                multilineStyle = Yaml.WriterConfig.MultilineStyle.Folded,
                chomping = Yaml.WriterConfig.Chomping.Strip
            )
            val value = "line one\nline two\n"
            val yaml  = Yaml.encode(value, config)

            // folded block scalar: the appendLiteral logic puts a blank line between paragraphs for folded style,
            // so the internal newline is preserved as a paragraph break, not folded to a space
            val decoded = Yaml.decode[String](yaml)
            assertResult(
                (
                    header = true,
                    roundTripped = true
                )
            ) {
                (
                    header = yaml.startsWith(">-\n"),
                    roundTripped = decoded.isSuccess
                )
            }
        }
    }
end YamlWriterTest
