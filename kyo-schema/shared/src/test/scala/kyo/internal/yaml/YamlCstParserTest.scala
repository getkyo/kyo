package kyo.internal.yaml

import kyo.*

class YamlCstParserTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    "YamlCstParser" - {

        "preserves source exactly for an unchanged document" in {
            val yaml =
                """# app config
                  |name: Alice # owner
                  |
                  |services:
                  |  api: &api
                  |    image: "app:v1"
                  |    ports: [8080, 8081]
                  |  worker: *api
                  |message: |-
                  |  hello
                  |  world
                  |""".stripMargin

            val doc = YamlCstParser.document(yaml).getOrThrow

            assertResult(
                (
                    rendered = yaml,
                    sourcePresent = true,
                    hasRoot = true
                )
            ) {
                (
                    rendered = doc.render(using Yaml.WriterConfig.Default),
                    sourcePresent = doc.source.isDefined,
                    hasRoot = doc.node.isDefined
                )
            }
        }

        "preserves a multi-document stream exactly" in {
            val yaml =
                """# first
                  |---
                  |name: Alice
                  |...
                  |# second
                  |---
                  |name: Bob
                  |""".stripMargin

            val stream = YamlCstParser.stream(yaml).getOrThrow

            assertResult((count = 2, rendered = yaml)) {
                (count = stream.documents.size, rendered = stream.render(using Yaml.WriterConfig.Default))
            }
        }
    }
end YamlCstParserTest
