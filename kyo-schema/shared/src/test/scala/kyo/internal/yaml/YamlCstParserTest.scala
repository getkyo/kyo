package kyo.internal.yaml

import kyo.*

class YamlCstParserTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    private def parseFailure(result: Result[DecodeException, Yaml.Cst.Document])(using kyo.test.AssertScope): String =
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

            val obtained = (
                rendered = doc.render(using Yaml.WriterConfig.Default),
                sourcePresent = doc.source.isDefined,
                hasRoot = doc.node.isDefined
            )
            assert(obtained.rendered == yaml)
            assert(obtained.sourcePresent == true)
            assert(obtained.hasRoot == true)
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

            val obtained = (count = stream.documents.size, rendered = stream.render(using Yaml.WriterConfig.Default))
            assert(obtained.count == 2)
            assert(obtained.rendered == yaml)
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

            val obtained = (
                streamSource = stream.source,
                rendered = stream.render(using Yaml.WriterConfig.Default),
                childSources = stream.documents.map(_.source.isDefined)
            )
            assert(obtained.streamSource == Maybe(yaml))
            assert(obtained.rendered == yaml)
            assert(obtained.childSources == Chunk(false, false))
        }

        "preserves source and source span for an explicit empty document" in {
            val yaml =
                """---
                  |""".stripMargin

            val doc = YamlCstParser.document(yaml).getOrThrow

            val obtained = (
                source = doc.source,
                end = doc.span.end.index,
                rendered = doc.render(using Yaml.WriterConfig.Default)
            )
            assert(obtained.source == Maybe(yaml))
            assert(obtained.end == yaml.length)
            assert(obtained.rendered == yaml)
        }

        "rejects an explicit empty document followed by another document as a single document" in {
            val yaml =
                """---
                  |---
                  |Alice
                  |""".stripMargin

            assert(parseFailure(YamlCstParser.document(yaml)).contains("Unexpected content after YAML document end"))
        }

        "rejects an unclosed flow sequence" in {
            val yaml =
                """items: [one, two
                  |""".stripMargin

            assert(parseFailure(YamlCstParser.document(yaml)).contains("Unterminated flow collection"))
        }

        "rejects invalid block scalar indentation" in {
            val yaml =
                """text: |
                  |    first
                  |  second
                  |""".stripMargin

            val message = parseFailure(YamlCstParser.document(yaml))

            assert(message.contains("Expected block scalar indentation"))
            assert(message.contains("line 3"))
        }

        "rejects a multi-document stream as a single CST document" in {
            val yaml =
                """name: Alice
                  |---
                  |name: Bob
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

            val obtained = (
                count = stream.documents.size,
                firstEmpty = stream.documents(0).node.isEmpty,
                rendered = stream.render(using Yaml.WriterConfig.Default)
            )
            assert(obtained.count == 2)
            assert(obtained.firstEmpty == true)
            assert(obtained.rendered == yaml)
        }

        "preserves an explicit empty document before a mapping document" in {
            val yaml =
                """---
                  |---
                  |name: Alice
                  |""".stripMargin

            val stream = YamlCstParser.stream(yaml).getOrThrow

            val obtained = (
                count = stream.documents.size,
                firstEmpty = stream.documents(0).node.isEmpty,
                secondRoot = stream.documents(1).node.isDefined,
                rendered = stream.render(using Yaml.WriterConfig.Default)
            )
            assert(obtained.count == 2)
            assert(obtained.firstEmpty == true)
            assert(obtained.secondRoot == true)
            assert(obtained.rendered == yaml)
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

            val Present(root: Yaml.Cst.Node.Mapping) = doc.node: @unchecked
            val nameEntry                            = root.entries(0)
            val serviceEntry                         = root.entries(1)
            val obtained = (
                leading = doc.leadingTrivia.collect { case Yaml.Cst.Trivia(text, _) => text },
                trailingOwner = nameEntry.trailingTrivia.collect { case Yaml.Cst.Trivia(text, _) => text.trim },
                serviceLeading = serviceEntry.leadingTrivia.collect { case Yaml.Cst.Trivia(text, _) => text }
            )
            assert(obtained.leading == Chunk("# document comment"))
            assert(obtained.trailingOwner == Chunk("# owner"))
            assert(obtained.serviceLeading == Chunk("# service comment"))
        }

        "captures document leading comments before explicit document start" in {
            val yaml =
                """# document comment
                  |---
                  |name: Alice
                  |""".stripMargin

            val doc = YamlCstParser.document(yaml).getOrThrow

            val Present(root: Yaml.Cst.Node.Mapping) = doc.node: @unchecked
            val key                                  = root.entries(0).key.asInstanceOf[Yaml.Cst.Node.Scalar]
            val obtained = (
                leading = doc.leadingTrivia.collect { case Yaml.Cst.Trivia(text, _) => text },
                rendered = doc.render(using Yaml.WriterConfig.Default),
                rootMark = root.meta.mark,
                keyStart = key.span.start,
                commentSpans = doc.leadingTrivia.map(comment => (comment.span.start, comment.span.end.index))
            )
            assert(obtained.leading == Chunk("# document comment"))
            assert(obtained.rendered == yaml)
            assert(obtained.rootMark == Yaml.Mark(23, 3, 1))
            assert(obtained.keyStart == Yaml.Mark(23, 3, 1))
            assert(obtained.commentSpans == Chunk((Yaml.Mark(0, 1, 1), 18)))
        }

        "captures CRLF document leading comments before explicit document start" in {
            val yaml = "# document comment\r\n---\r\nname: Alice\r\n"

            val doc       = YamlCstParser.document(yaml).getOrThrow
            val nameIndex = yaml.indexOf("name")

            val Present(root: Yaml.Cst.Node.Mapping) = doc.node: @unchecked
            val key                                  = root.entries(0).key.asInstanceOf[Yaml.Cst.Node.Scalar]
            val obtained = (
                leading = doc.leadingTrivia.collect { case Yaml.Cst.Trivia(text, _) => text },
                rendered = doc.render(using Yaml.WriterConfig.Default),
                rootMark = root.meta.mark,
                keyStart = key.span.start
            )
            assert(obtained.leading == Chunk("# document comment"))
            assert(obtained.rendered == yaml)
            assert(obtained.rootMark == Yaml.Mark(nameIndex, 3, 1))
            assert(obtained.keyStart == Yaml.Mark(nameIndex, 3, 1))
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

            assert(plainValue.syntax == Yaml.Cst.ScalarSyntax.Plain)
            assert(singleValue.syntax == Yaml.Cst.ScalarSyntax.SingleQuoted)
            assert(doubleValue.syntax == Yaml.Cst.ScalarSyntax.DoubleQuoted)
            assert(literalValue.syntax == Yaml.Cst.ScalarSyntax.Literal)
            assert(foldedValue.syntax == Yaml.Cst.ScalarSyntax.Folded)
            assert(flowSequence.syntax == Yaml.Cst.SequenceSyntax.Flow)
            assert(flowMapping.syntax == Yaml.Cst.MappingSyntax.Flow)
        }

        "records a source-backed root span across the full input" in {
            val yaml =
                """name: Alice
                  |""".stripMargin

            val doc                                  = YamlCstParser.document(yaml).getOrThrow
            val Present(root: Yaml.Cst.Node.Mapping) = doc.node: @unchecked

            val obtained = (
                start = root.span.start.index,
                end = root.span.end.index,
                rendered = doc.render(using Yaml.WriterConfig.Default)
            )
            assert(obtained.start == 0)
            assert(obtained.end == yaml.length)
            assert(obtained.rendered == yaml)
        }

        "records scalar source marks without claiming token extents" in {
            val yaml =
                """name: Alice
                  |""".stripMargin

            val doc                                  = YamlCstParser.document(yaml).getOrThrow
            val Present(root: Yaml.Cst.Node.Mapping) = doc.node: @unchecked
            val key                                  = root.entries(0).key.asInstanceOf[Yaml.Cst.Node.Scalar]
            val value                                = root.entries(0).value.asInstanceOf[Yaml.Cst.Node.Scalar]

            assert(key.span.start == Yaml.Mark(0, 1, 1))
            assert(key.span.end == Yaml.Mark(0, 1, 1))
            assert(value.span.start == Yaml.Mark(6, 1, 7))
            assert(value.span.end == Yaml.Mark(6, 1, 7))
        }
    }
end YamlCstParserTest
