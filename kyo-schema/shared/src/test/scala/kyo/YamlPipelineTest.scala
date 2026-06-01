package kyo

class YamlPipelineTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

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
    }

    private def scalarRewrite(from: String, to: String): Yaml.Events.Processor[Nothing] =
        Yaml.Events.Processor.mapScalars[Nothing] { (value, meta) =>
            if value == from then Result.succeed((to, meta))
            else Result.succeed((value, meta))
        }
    end scalarRewrite
end YamlPipelineTest
