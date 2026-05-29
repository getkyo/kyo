package kyo

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

class YamlWriterTest extends Test:

    "encode" - {

        "writes case classes as block mappings by default" in {
            val yaml = Yaml.encode(MTPerson("Alice", 30))

            assert(
                yaml ==
                    """name: Alice
                      |age: 30
                      |""".stripMargin
            )
            assert(Yaml.decode[MTPerson](yaml) == Result.succeed(MTPerson("Alice", 30)))
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

            assert(
                yaml ==
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
                      |""".stripMargin
            )
            assert(Yaml.decode[YamlWriterNested](yaml) == Result.succeed(value))
        }

        "round-trips sequence mappings whose first field has a nested block value" in {
            val value = List(
                YamlWriterNestedFirst(List(1, 2), "a"),
                YamlWriterNestedFirst(List(3), "b")
            )

            val yaml = Yaml.encode(value)

            assert(
                yaml ==
                    """-
                      |  items:
                      |    - 1
                      |    - 2
                      |  name: a
                      |-
                      |  items:
                      |    - 3
                      |  name: b
                      |""".stripMargin
            )
            assert(Yaml.decode[List[YamlWriterNestedFirst]](yaml) == Result.succeed(value))
        }

        "round-trips sequence mappings whose first field is a literal block scalar" in {
            val value = List(YamlWriterTextFirst("line one\nline two\n", "a"))

            val yaml = Yaml.encode(value)

            assert(
                yaml ==
                    """-
                      |  text: |
                      |    line one
                      |    line two
                      |  name: a
                      |""".stripMargin
            )
            assert(Yaml.decode[List[YamlWriterTextFirst]](yaml) == Result.succeed(value))
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

            assert(
                yaml ==
                    """plain: Alice
                      |truthy: "true"
                      |comment: "Alice #1"
                      |url: "https://example.com"
                      |multiline: |
                      |  line one
                      |  line two
                      |empty: ""
                      |""".stripMargin
            )
            assert(Yaml.decode[YamlWriterStrings](yaml) == Result.succeed(value))
        }

        "keeps extra trailing newlines in multiline string scalars" in {
            val yaml = Yaml.encode("line one\n\n")

            assert(
                yaml ==
                    "|+\n  line one\n\n"
            )
            assert(Yaml.decode[String](yaml) == Result.succeed("line one\n\n"))
        }

        "writes empty collections and scalar roots as YAML" in {
            assert(Yaml.encode(List.empty[Int]) == "[]\n")
            assert(Yaml.encode(Map.empty[String, Int]) == "{}\n")
            assert(Yaml.encode(List(1, 2, 3)) == "- 1\n- 2\n- 3\n")
            assert(Yaml.encode("true") == "\"true\"\n")
            assert(Yaml.encode("0x3A") == "\"0x3A\"\n")
            assert(Yaml.encode("0o7") == "\"0o7\"\n")
            assert(Yaml.encode(".5") == "\".5\"\n")
            assert(Yaml.encode("+12e03") == "\"+12e03\"\n")
            assert(Yaml.encode(".inf") == "\".inf\"\n")
        }

        "uses the readable writer config as the default profile" in {
            assert(Yaml.WriterConfig.Default == Yaml.WriterConfig.Readable)
            assert(Yaml.encode(List(MTPerson("Alice", 30))) == "-\n  name: Alice\n  age: 30\n")
        }

        "uses a contextual writer config for single-argument encode" in {
            given Yaml.WriterConfig = Yaml.WriterConfig.Compact

            assert(Yaml.encode(List(MTPerson("Alice", 30))) == "- name: Alice\n  age: 30\n")
        }

        "supports explicit compact small and fast writer profiles" in {
            val value = MTPerson("Alice", 30)

            assert(Yaml.encode(value, Yaml.WriterConfig.Compact) == "name: Alice\nage: 30\n")
            assert(Yaml.encode(value, Yaml.WriterConfig.Small) == "{name: Alice, age: 30}")
            assert(Yaml.encode(value, Yaml.WriterConfig.Fast) == "{\"name\":\"Alice\",\"age\":30}")
        }

        "supports scalar quoting modes and quote styles" in {
            val minimal      = Yaml.WriterConfig.Readable.copy(scalarQuoting = Yaml.WriterConfig.ScalarQuoting.WhenNeeded)
            val singleQuoted = Yaml.WriterConfig.Readable.copy(quoteStyle = Yaml.WriterConfig.QuoteStyle.Single)

            assert(Yaml.encode("true", minimal) == "true\n")
            assert(Yaml.encode("true", Yaml.WriterConfig.Readable) == "\"true\"\n")
            assert(Yaml.encode("Alice #1", singleQuoted) == "'Alice #1'\n")
        }

        "quotes YAML 1.1 ambiguous strings when configured for legacy consumers" in {
            val legacy = Yaml.WriterConfig.Readable.copy(yamlVersion = Yaml.SpecVersion.Yaml11)

            assert(Yaml.encode("NO") == "NO\n")
            assert(Yaml.encode("NO", legacy) == "\"NO\"\n")
            assert(Yaml.encode("010", legacy) == "\"010\"\n")
        }
    }
end YamlWriterTest
