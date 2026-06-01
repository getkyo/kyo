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

        "captures leading and trailing comments as trivia" in {
            val yaml =
                """# document comment
                  |name: Alice # owner
                  |# service comment
                  |service:
                  |  image: app:v1
                  |""".stripMargin

            val doc = YamlCstParser.document(yaml).getOrThrow

            assertResult(
                (
                    leading = Chunk("# document comment"),
                    trailingOwner = Chunk("# owner"),
                    serviceLeading = Chunk("# service comment")
                )
            ) {
                val Present(root: Yaml.Cst.Node.Mapping) = doc.node: @unchecked
                val nameEntry                            = root.entries(0)
                val serviceEntry                         = root.entries(1)
                (
                    leading = doc.leadingTrivia.collect { case Yaml.Cst.Trivia(text, _) => text },
                    trailingOwner = nameEntry.trailingTrivia.collect { case Yaml.Cst.Trivia(text, _) => text.trim },
                    serviceLeading = serviceEntry.leadingTrivia.collect { case Yaml.Cst.Trivia(text, _) => text }
                )
            }
        }

        "captures document leading comments before explicit document start" in {
            val yaml =
                """# document comment
                  |---
                  |name: Alice
                  |""".stripMargin

            val doc = YamlCstParser.document(yaml).getOrThrow

            assertResult(
                (
                    leading = Chunk("# document comment"),
                    rendered = yaml,
                    rootMark = Yaml.Mark(23, 3, 1),
                    keyStart = Yaml.Mark(23, 3, 1),
                    commentSpans = Chunk((Yaml.Mark(0, 1, 1), 18))
                )
            ) {
                val Present(root: Yaml.Cst.Node.Mapping) = doc.node: @unchecked
                val key                                  = root.entries(0).key.asInstanceOf[Yaml.Cst.Node.Scalar]
                (
                    leading = doc.leadingTrivia.collect { case Yaml.Cst.Trivia(text, _) => text },
                    rendered = doc.render(using Yaml.WriterConfig.Default),
                    rootMark = root.meta.mark,
                    keyStart = key.span.start,
                    commentSpans = doc.leadingTrivia.map(comment => (comment.span.start, comment.span.end.index))
                )
            }
        }

        "records scalar syntax for flow quoted and block scalar values" in {
            val yaml =
                """plain: hello
                  |single: 'hello'
                  |double: "hello"
                  |literal: |
                  |  hello
                  |folded: >
                  |  hello
                  |flowSeq: [1, 2]
                  |flowMap: {a: 1}
                  |""".stripMargin

            val doc                                  = YamlCstParser.document(yaml).getOrThrow
            val Present(root: Yaml.Cst.Node.Mapping) = doc.node: @unchecked
            val plainValue                           = root.entries(0).value.asInstanceOf[Yaml.Cst.Node.Scalar]
            val singleValue                          = root.entries(1).value.asInstanceOf[Yaml.Cst.Node.Scalar]
            val doubleValue                          = root.entries(2).value.asInstanceOf[Yaml.Cst.Node.Scalar]
            val literalValue                         = root.entries(3).value.asInstanceOf[Yaml.Cst.Node.Scalar]
            val foldedValue                          = root.entries(4).value.asInstanceOf[Yaml.Cst.Node.Scalar]
            val flowSequence                         = root.entries(5).value.asInstanceOf[Yaml.Cst.Node.Sequence]
            val flowMapping                          = root.entries(6).value.asInstanceOf[Yaml.Cst.Node.Mapping]

            assertResult(
                (
                    plain = true,
                    single = true,
                    double = true,
                    literal = true,
                    folded = true,
                    flowSeq = true,
                    flowMap = true
                )
            ) {
                (
                    plain = plainValue.syntax == Yaml.Cst.ScalarSyntax.Plain,
                    single = singleValue.syntax == Yaml.Cst.ScalarSyntax.SingleQuoted,
                    double = doubleValue.syntax == Yaml.Cst.ScalarSyntax.DoubleQuoted,
                    literal = literalValue.syntax == Yaml.Cst.ScalarSyntax.Literal,
                    folded = foldedValue.syntax == Yaml.Cst.ScalarSyntax.Folded,
                    flowSeq = flowSequence.syntax == Yaml.Cst.SequenceSyntax.Flow,
                    flowMap = flowMapping.syntax == Yaml.Cst.MappingSyntax.Flow
                )
            }
        }

        "records a source-backed root span across the full input" in {
            val yaml =
                """name: Alice
                  |""".stripMargin

            val doc                                  = YamlCstParser.document(yaml).getOrThrow
            val Present(root: Yaml.Cst.Node.Mapping) = doc.node: @unchecked

            assertResult((start = 0, end = yaml.length, rendered = yaml)) {
                (
                    start = root.span.start.index,
                    end = root.span.end.index,
                    rendered = doc.render(using Yaml.WriterConfig.Default)
                )
            }
        }
    }
end YamlCstParserTest
