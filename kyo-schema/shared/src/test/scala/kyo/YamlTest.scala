package kyo

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
    }

    "parse" - {

        "builds a YAML node tree only when requested" in {
            val parsed = Yaml.parse("name: Alice\nage: 30\n").getOrThrow

            parsed match
                case Yaml.Node.Mapping(entries, _) =>
                    assert(entries.map(_._1.asInstanceOf[Yaml.Node.Scalar].value) == Chunk("name", "age"))
                    assert(entries.map(_._2.asInstanceOf[Yaml.Node.Scalar].value) == Chunk("Alice", "30"))
                case other => fail(s"Expected mapping, got $other")
            end match
        }
    }

    "visit" - {

        "streams block mapping events without requiring a YAML node tree" in {
            val visitor = new Yaml.Visitor[List[String], String, List[String]]:
                def streamStart(context: List[String], mark: Yaml.Mark): Result[String, List[String]] =
                    Result.succeed("streamStart" :: context)

                def documentStart(context: List[String], mark: Yaml.Mark): Result[String, List[String]] =
                    Result.succeed("documentStart" :: context)

                def mappingStart(context: List[String], meta: Yaml.Meta): Result[String, List[String]] =
                    Result.succeed(s"mappingStart:${meta.anchor.getOrElse("")}:${meta.tag.getOrElse("")}" :: context)

                def sequenceStart(context: List[String], meta: Yaml.Meta): Result[String, List[String]] =
                    Result.succeed(s"sequenceStart:${meta.anchor.getOrElse("")}:${meta.tag.getOrElse("")}" :: context)

                def scalar(context: List[String], value: String, meta: Yaml.ScalarMeta): Result[String, List[String]] =
                    Result.succeed(s"scalar:$value:${meta.style}" :: context)

                def alias(context: List[String], name: String, mark: Yaml.Mark): Result[String, List[String]] =
                    Result.succeed(s"alias:$name" :: context)

                def nodeEnd(context: List[String], mark: Yaml.Mark): Result[String, List[String]] =
                    Result.succeed("nodeEnd" :: context)

                def documentEnd(context: List[String], mark: Yaml.Mark): Result[String, List[String]] =
                    Result.succeed("documentEnd" :: context)

                def streamEnd(context: List[String], mark: Yaml.Mark): Result[String, List[String]] =
                    Result.succeed(("streamEnd" :: context).reverse)
            end visitor

            val visited = Yaml.visit("name: Alice\nage: 30\n", Nil)(visitor)

            assert(
                visited == Result.succeed(List(
                    "streamStart",
                    "documentStart",
                    "mappingStart::",
                    "scalar:name:Plain",
                    "scalar:Alice:Plain",
                    "scalar:age:Plain",
                    "scalar:30:Plain",
                    "nodeEnd",
                    "documentEnd",
                    "streamEnd"
                ))
            )
        }

        "exposes anchors and tags as visitor metadata" in {
            val visitor = new Yaml.Visitor[List[String], String, List[String]]:
                def streamStart(context: List[String], mark: Yaml.Mark): Result[String, List[String]]   = Result.succeed(context)
                def documentStart(context: List[String], mark: Yaml.Mark): Result[String, List[String]] = Result.succeed(context)

                def mappingStart(context: List[String], meta: Yaml.Meta): Result[String, List[String]] =
                    Result.succeed(s"map:${meta.anchor.getOrElse("")}:${meta.tag.getOrElse("")}" :: context)

                def sequenceStart(context: List[String], meta: Yaml.Meta): Result[String, List[String]] =
                    Result.succeed(s"seq:${meta.anchor.getOrElse("")}:${meta.tag.getOrElse("")}" :: context)

                def scalar(context: List[String], value: String, meta: Yaml.ScalarMeta): Result[String, List[String]] =
                    Result.succeed(s"scalar:$value:${meta.anchor.getOrElse("")}:${meta.tag.getOrElse("")}" :: context)

                def alias(context: List[String], name: String, mark: Yaml.Mark): Result[String, List[String]] =
                    Result.succeed(s"alias:$name" :: context)

                def nodeEnd(context: List[String], mark: Yaml.Mark): Result[String, List[String]]     = Result.succeed(context)
                def documentEnd(context: List[String], mark: Yaml.Mark): Result[String, List[String]] = Result.succeed(context)
                def streamEnd(context: List[String], mark: Yaml.Mark): Result[String, List[String]]   = Result.succeed(context.reverse)
            end visitor

            val yaml =
                """value: !custom &id Alice
                  |again: *id
                  |""".stripMargin

            assert(
                Yaml.visit(yaml, Nil)(visitor) == Result.succeed(List(
                    "map::",
                    "scalar:value::",
                    "scalar:Alice:id:!custom",
                    "scalar:again::",
                    "alias:id"
                ))
            )
        }
    }

end YamlTest
