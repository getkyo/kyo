package kyo.internal.yaml

import kyo.*

class YamlCstParserTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private def parseFailure(result: Result[DecodeException, Yaml.Cst.Document]): String =
        result match
            case Result.Failure(e: ParseException) => e.getMessage
            case other                             => fail(s"Expected ParseException failure, got $other")
    end parseFailure

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

        "uses stream source without assigning split bodies as child document source" in {
            val yaml =
                """# first
                  |---
                  |name: Alice
                  |---
                  |name: Bob
                  |""".stripMargin

            val stream = YamlCstParser.stream(yaml).getOrThrow

            assertResult(
                (
                    streamSource = Maybe(yaml),
                    rendered = yaml,
                    childSources = Chunk(false, false)
                )
            ) {
                (
                    streamSource = stream.source,
                    rendered = stream.render(using Yaml.WriterConfig.Default),
                    childSources = stream.documents.map(_.source.isDefined)
                )
            }
        }

        "preserves source and source span for an explicit empty document" in {
            val yaml =
                """---
                  |""".stripMargin

            val doc = YamlCstParser.document(yaml).getOrThrow

            assertResult((source = Maybe(yaml), end = yaml.length, rendered = yaml)) {
                (
                    source = doc.source,
                    end = doc.span.end.index,
                    rendered = doc.render(using Yaml.WriterConfig.Default)
                )
            }
        }

        "rejects an explicit empty document followed by another document as a single document" in {
            val yaml =
                """---
                  |---
                  |Alice
                  |""".stripMargin

            assert(parseFailure(YamlCstParser.document(yaml)).contains("Unexpected content after YAML document end"))
        }

        "preserves an explicit empty document at the start of a stream" in {
            val yaml =
                """---
                  |---
                  |Alice
                  |""".stripMargin

            val stream = YamlCstParser.stream(yaml).getOrThrow

            assertResult((count = 2, firstEmpty = true, rendered = yaml)) {
                (
                    count = stream.documents.size,
                    firstEmpty = stream.documents(0).node.isEmpty,
                    rendered = stream.render(using Yaml.WriterConfig.Default)
                )
            }
        }

        "preserves an explicit empty document before a mapping document" in {
            val yaml =
                """---
                  |---
                  |name: Alice
                  |""".stripMargin

            val stream = YamlCstParser.stream(yaml).getOrThrow

            assertResult((count = 2, firstEmpty = true, secondRoot = true, rendered = yaml)) {
                (
                    count = stream.documents.size,
                    firstEmpty = stream.documents(0).node.isEmpty,
                    secondRoot = stream.documents(1).node.isDefined,
                    rendered = stream.render(using Yaml.WriterConfig.Default)
                )
            }
        }
    }
end YamlCstParserTest
