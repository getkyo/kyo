package kyo

class YamlPipelineTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    case class PipelinePair(primary: MTPerson, backup: MTPerson) derives CanEqual

    enum PipelineError derives CanEqual:
        case Rejected
    end PipelineError

    "Yaml.pipeline" - {

        "decodes with the direct schema path when no processors are configured" in {
            val yaml =
                """name: Alice
                  |age: 30
                  |""".stripMargin

            assert(Yaml.pipeline.decode[MTPerson](yaml) == Result.succeed(MTPerson("Alice", 30)))
        }

        "renders a YAML source through the event renderer" in {
            val yaml =
                """name: Alice
                  |age: 30
                  |""".stripMargin

            assert(Yaml.pipeline.render(yaml) == Result.succeed(yaml))
        }

        "visits a YAML source through the event API" in {
            val yaml =
                """name: Alice
                  |age: 30
                  |""".stripMargin

            val scalars = new Yaml.Events.Handler[Chunk[String], Nothing]:
                override def scalar(
                    context: Chunk[String],
                    value: String,
                    meta: Yaml.ScalarMeta
                ): Result[Nothing, Chunk[String]] =
                    Result.succeed(context :+ value)
            end scalars

            assert(
                Yaml.pipeline.visit(yaml, Chunk.empty[String])(scalars) ==
                    Result.succeed(Chunk("name", "Alice", "age", "30"))
            )
        }

        "encodes with the direct schema path when no processors are configured" in {
            val value = MTPerson("Alice", 30)

            assert(Yaml.pipeline.encode(value) == Result.succeed(Yaml.encode(value)))
        }

        "decodes transformed scalar values directly from processor events" in {
            val yaml =
                """name: placeholder
                  |age: 30
                  |""".stripMargin

            val rewrite =
                scalarRewrite("placeholder", "a: b")

            assert(
                Yaml.pipeline.through(rewrite).decode[MTPerson](yaml) ==
                    Result.succeed(MTPerson("a: b", 30))
            )
        }

        "decodes transformed field names before case-class dispatch" in {
            val yaml =
                """fullName: Alice
                  |age: 30
                  |""".stripMargin

            val rename =
                scalarRewrite("fullName", "name")

            assert(
                Yaml.pipeline.through(rename).decode[MTPerson](yaml) ==
                    Result.succeed(MTPerson("Alice", 30))
            )
        }

        "decodes transformed Scala 3 enum variant keys before dispatch" in {
            val yaml =
                """LegacyAlpha:
                  |  x: 7
                  |""".stripMargin

            val rename =
                scalarRewrite("LegacyAlpha", "Alpha")

            assert(
                Yaml.pipeline.through(rename).decode[MixedArityEnum](yaml) ==
                    Result.succeed(MixedArityEnum.Alpha(7))
            )
        }

        "decodes transformed sealed-trait variant keys before dispatch" in {
            val yaml =
                """LegacyLabeled:
                  |  name: release
                  |""".stripMargin

            val rename =
                scalarRewrite("LegacyLabeled", "Labeled")

            assert(
                Yaml.pipeline.through(rename).decode[SealedNoArgVariants](yaml) ==
                    Result.succeed(SealedNoArgVariants.Labeled("release"))
            )
        }

        "replays transformed anchored mappings through aliases" in {
            val yaml =
                """primary: &person
                  |  name: placeholder
                  |  age: 30
                  |backup: *person
                  |""".stripMargin

            val rewrite =
                scalarRewrite("placeholder", "Alice")

            assert(
                Yaml.pipeline.through(rewrite).decode[PipelinePair](yaml) ==
                    Result.succeed(PipelinePair(MTPerson("Alice", 30), MTPerson("Alice", 30)))
            )
        }

        "selects document index before processor decode" in {
            val yaml =
                """---
                  |name: Alice
                  |age: 30
                  |---
                  |name: Robert
                  |age: 25
                  |""".stripMargin

            val config =
                Yaml.ReaderConfig.Default.copy(documentIndex = Maybe(Yaml.DocumentIndex(1)))

            assert(
                Yaml.pipeline.reader(config).through(scalarRewrite("Robert", "Bob")).decode[MTPerson](yaml) ==
                    Result.succeed(MTPerson("Bob", 25))
            )
        }

        "merges document stream fragments for case-class processor decode" in {
            val yaml =
                """---
                  |name: Alice
                  |---
                  |age: 30
                  |""".stripMargin

            val config =
                Yaml.ReaderConfig.Default.copy(documentMode = Yaml.ReaderConfig.DocumentMode.MergeTopLevelMappings)

            assert(
                Yaml.pipeline.reader(config).through(identityProcessor).decode[MTPerson](yaml) ==
                    Result.succeed(MTPerson("Alice", 30))
            )
        }

        "merges document stream fragments for ADT processor decode" in {
            val yaml =
                """---
                  |Alpha:
                  |---
                  |  x: 7
                  |""".stripMargin

            val config =
                Yaml.ReaderConfig.Default.copy(documentMode = Yaml.ReaderConfig.DocumentMode.MergeTopLevelMappings)

            assert(
                Yaml.pipeline.reader(config).through(identityProcessor).decode[MixedArityEnum](yaml) ==
                    Result.succeed(MixedArityEnum.Alpha(7))
            )
        }

        "returns processor failures without continuing schema decode" in {
            val yaml =
                """name: reject
                  |age: 30
                  |""".stripMargin

            val reject =
                Yaml.Events.Processor.mapScalars[PipelineError] { (value, meta) =>
                    if value == "reject" then Result.fail(PipelineError.Rejected)
                    else Result.succeed((value, meta))
                }

            assert(
                Yaml.pipeline.through(reject).decode[MTPerson](yaml) ==
                    Result.fail(PipelineError.Rejected)
            )
        }

        "enforces max depth during processor decode" in {
            val yaml =
                """lead:
                  |  name: Alice
                  |  age: 30
                  |size: 5
                  |""".stripMargin

            val config =
                Yaml.ReaderConfig.Default.copy(maxDepth = 1)

            assert(
                Yaml.pipeline.reader(config).through(identityProcessor).decode[MTSmallTeam](yaml) ==
                    Result.fail(LimitExceededException("Nesting depth", 2, 1))
            )
        }

        "enforces max collection size during processor decode" in {
            val yaml =
                """name: Alice
                  |age: 3
                  |""".stripMargin

            val config =
                Yaml.ReaderConfig.Default.copy(maxCollectionSize = 1)

            assert(
                Yaml.pipeline.reader(config).through(identityProcessor).decode[MTPerson](yaml) ==
                    Result.fail(LimitExceededException("Collection size", 2, 1))
            )
        }
    }

    private val identityProcessor: Yaml.Events.Processor[Nothing] =
        Yaml.Events.Processor.mapScalars[Nothing]((value, meta) => Result.succeed((value, meta)))

    private def scalarRewrite(from: String, to: String): Yaml.Events.Processor[Nothing] =
        Yaml.Events.Processor.mapScalars[Nothing] { (value, meta) =>
            if value == from then Result.succeed((to, meta))
            else Result.succeed((value, meta))
        }
    end scalarRewrite
end YamlPipelineTest
