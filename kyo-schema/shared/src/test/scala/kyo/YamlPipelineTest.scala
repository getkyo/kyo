package kyo

class YamlPipelineTest extends kyo.test.Test[Any]:

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

        "builds source-backed CST without processors" in {
            val yaml = """# app
name: Alice
age: 30
"""

            assertResult(Result.succeed((source = Maybe(yaml), rendered = yaml))) {
                Yaml.pipeline.cst(yaml).map(doc => (source = doc.source, rendered = doc.render(using Yaml.WriterConfig.Default)))
            }
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

        "builds canonical CST from processor events" in {
            val yaml =
                """name: placeholder
                  |age: 30
                  |""".stripMargin

            val doc =
                Yaml.pipeline.through(scalarRewrite("placeholder", "Alice")).cst(yaml).getOrThrow

            assert(doc.source.isEmpty)
            assert(Yaml.decode[MTPerson](doc.render(using Yaml.WriterConfig.Default)) == Result.succeed(MTPerson("Alice", 30)))
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

        "selects document index before processor CST" in {
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

            val doc =
                Yaml.pipeline.reader(config).through(scalarRewrite("Robert", "Bob")).cst(yaml).getOrThrow

            assert(doc.source.isEmpty)
            assert(Yaml.decode[MTPerson](doc.render(using Yaml.WriterConfig.Default)) == Result.succeed(MTPerson("Bob", 25)))
        }

        "does not merge document streams for single-document CST" in {
            val yaml =
                """---
                  |name: Alice
                  |---
                  |age: 30
                  |""".stripMargin

            val config =
                Yaml.ReaderConfig.Default.copy(documentMode = Yaml.ReaderConfig.DocumentMode.MergeTopLevelMappings)
            val expected =
                cstFailure(Yaml.cst(yaml))
            val processed =
                cstFailure(Yaml.pipeline.reader(config).through(identityProcessor).cst(yaml))

            assert(expected.contains("Unexpected content after YAML document end"))
            assert(cstFailure(Yaml.pipeline.reader(config).cst(yaml)) == expected)
            assert(processed.contains("Expected a single YAML document"))
        }

        "builds source-backed CST stream without processors" in {
            val yaml =
                """# first
                  |---
                  |name: Alice
                  |age: 30
                  |---
                  |name: Bob
                  |age: 25
                  |""".stripMargin

            assertResult(Result.succeed((source = Maybe(yaml), count = 2, rendered = yaml))) {
                Yaml.pipeline.cstAll(yaml).map(stream =>
                    (
                        source = stream.source,
                        count = stream.documents.size,
                        rendered = stream.render(using Yaml.WriterConfig.Default)
                    )
                )
            }
        }

        "builds canonical CST stream from processor events" in {
            val yaml =
                """---
                  |name: placeholder
                  |age: 30
                  |---
                  |name: placeholder
                  |age: 25
                  |""".stripMargin

            val stream =
                Yaml.pipeline.through(scalarRewrite("placeholder", "Alice")).cstAll(yaml).getOrThrow
            val rendered =
                stream.render(using Yaml.WriterConfig.Default)

            assert(stream.source.isEmpty)
            assert(stream.documents.size == 2)
            assert(stream.documents.forall(_.source.isEmpty))
            assert(Yaml.decodeAll[MTPerson](rendered) == Result.succeed(Chunk(MTPerson("Alice", 30), MTPerson("Alice", 25))))
        }

        "builds a processor CST stream with the same document count as the source-backed stream" in {
            val yaml =
                """# first
                  |---
                  |name: Alice
                  |---
                  |name: Bob
                  |""".stripMargin

            val processed    = Yaml.pipeline.through(scalarRewrite("Alice", "Alicia")).cstAll(yaml).getOrThrow
            val sourceBacked = Yaml.pipeline.cstAll(yaml).getOrThrow

            assertResult((sameCount = true, rewritten = true)) {
                (
                    sameCount = processed.documents.size == sourceBacked.documents.size,
                    rewritten = processed.render(using Yaml.WriterConfig.Default).contains("Alicia")
                )
            }
        }

        "returns every document from cstAll ignoring the reader document index" in {
            val yaml =
                """---
                  |name: Alice
                  |---
                  |name: Bob
                  |""".stripMargin
            val config =
                Yaml.ReaderConfig.Default.copy(documentIndex = Maybe(Yaml.DocumentIndex(1)))

            assertResult((processed = Result.succeed(2), sourceBacked = Result.succeed(2))) {
                (
                    processed = Yaml.pipeline.reader(config).through(scalarRewrite("Alice", "Alicia")).cstAll(yaml).map(_.documents.size),
                    sourceBacked = Yaml.pipeline.reader(config).cstAll(yaml).map(_.documents.size)
                )
            }
        }

        "builds CST from UTF-8 bytes after source selection" in {
            val yaml =
                """---
                  |name: Alice
                  |age: 30
                  |---
                  |name: Café
                  |age: 25
                  |""".stripMargin
            val selected =
                """name: Café
                  |age: 25
                  |""".stripMargin
            val bytes =
                Span.from(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            val config =
                Yaml.ReaderConfig.Default.copy(documentIndex = Maybe(Yaml.DocumentIndex(1)))

            assertResult(Result.succeed((source = Maybe(selected), rendered = selected))) {
                Yaml.pipeline.reader(config).cstBytes(bytes).map(doc =>
                    (source = doc.source, rendered = doc.render(using Yaml.WriterConfig.Default))
                )
            }
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

        "returns processor failures from CST without building a document" in {
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
                Yaml.pipeline.through(reject).cst(yaml) ==
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

        "visits a CST document through the event API" in {
            val document =
                Yaml.cst("name: Alice\nage: 30\n").getOrThrow

            val scalars = new Yaml.Events.Handler[Chunk[String], Nothing]:
                override def scalar(
                    context: Chunk[String],
                    value: String,
                    meta: Yaml.ScalarMeta
                ): Result[Nothing, Chunk[String]] =
                    Result.succeed(context :+ value)
            end scalars

            assert(
                Yaml.pipeline.visit(document, Chunk.empty[String])(scalars) ==
                    Result.succeed(Chunk("name", "Alice", "age", "30"))
            )
        }

        "renders and parses a CST document" in {
            val document =
                Yaml.cst("name: Alice\nage: 30\n").getOrThrow

            assertResult((rendered = "name: Alice\nage: 30\n", parsedIsMapping = true)) {
                (
                    rendered = Yaml.pipeline.render(document).getOrThrow,
                    parsedIsMapping = Yaml.pipeline.parse(document).getOrThrow.isInstanceOf[Yaml.Node.Mapping]
                )
            }
        }

        "renders a CST stream and parses all its documents" in {
            val stream =
                Yaml.cstAll("---\nname: Alice\n---\nname: Bob\n").getOrThrow
            val nodes =
                Yaml.pipeline.parseAll(stream).getOrThrow

            assertResult((rendered = "---\nname: Alice\n---\nname: Bob\n", nodeCount = 2, node0IsMapping = true, node1IsMapping = true)) {
                (
                    rendered = Yaml.pipeline.render(stream).getOrThrow,
                    nodeCount = nodes.size,
                    node0IsMapping = nodes(0).isInstanceOf[Yaml.Node.Mapping],
                    node1IsMapping = nodes(1).isInstanceOf[Yaml.Node.Mapping]
                )
            }
        }

        "decodes a CST document into a schema value" in {
            val document =
                Yaml.cst("name: Alice\nage: 30\n").getOrThrow

            assert(Yaml.pipeline.decode[MTPerson](document) == Result.succeed(MTPerson("Alice", 30)))
        }

        "decodes every document of a CST stream" in {
            val stream =
                Yaml.cstAll("---\nname: Alice\nage: 30\n---\nname: Bob\nage: 25\n").getOrThrow

            assert(
                Yaml.pipeline.decodeAll[MTPerson](stream) ==
                    Result.succeed(Chunk(MTPerson("Alice", 30), MTPerson("Bob", 25)))
            )
        }

        "selects a CST stream document by reader document index" in {
            val stream =
                Yaml.cstAll("---\nname: Alice\nage: 30\n---\nname: Bob\nage: 25\n").getOrThrow
            val config =
                Yaml.ReaderConfig.Default.copy(documentIndex = Maybe(Yaml.DocumentIndex(1)))

            assert(Yaml.pipeline.reader(config).decode[MTPerson](stream) == Result.succeed(MTPerson("Bob", 25)))
        }

        "fails decoding a CST stream when the document index is out of range" in {
            val stream =
                Yaml.cstAll("---\nname: Alice\nage: 30\n").getOrThrow
            val config =
                Yaml.ReaderConfig.Default.copy(documentIndex = Maybe(Yaml.DocumentIndex(5)))

            Yaml.pipeline.reader(config).decode[MTPerson](stream) match
                case Result.Failure(e: DecodeException) =>
                    assert(e.getMessage.contains("out of range"))
                case other =>
                    fail(s"Expected out-of-range failure, got $other")
            end match
        }

        "merges CST stream top-level mappings when configured" in {
            val stream =
                Yaml.cstAll("---\nname: Alice\n---\nage: 30\n").getOrThrow
            val config =
                Yaml.ReaderConfig.Default.copy(documentMode = Yaml.ReaderConfig.DocumentMode.MergeTopLevelMappings)

            assert(Yaml.pipeline.reader(config).decode[MTPerson](stream) == Result.succeed(MTPerson("Alice", 30)))
        }

        "merges CST stream mappings while skipping a non-mapping document" in {
            val stream =
                Yaml.cstAll("---\n- 1\n- 2\n---\nname: Alice\n---\nage: 30\n").getOrThrow
            val config =
                Yaml.ReaderConfig.Default.copy(documentMode = Yaml.ReaderConfig.DocumentMode.MergeTopLevelMappings)

            assert(Yaml.pipeline.reader(config).decode[MTPerson](stream) == Result.succeed(MTPerson("Alice", 30)))
        }

        "decodes an empty CST stream to no values" in {
            val stream =
                Yaml.cstAll("").getOrThrow

            assert(Yaml.pipeline.decodeAll[MTPerson](stream) == Result.succeed(Chunk.empty))
        }

        "renders a source-less CST stream with StartAndEnd markers" in {
            // A processor-backed cstAll produces source-less documents, exercising the marker path.
            val stream =
                Yaml.pipeline.through(identityProcessor).cstAll("---\nname: Alice\n---\nname: Bob\n").getOrThrow
            val config =
                Yaml.WriterConfig.Default.copy(documentMarkers = Yaml.WriterConfig.DocumentMarkers.StartAndEnd)

            val rendered = Yaml.pipeline.writer(config).render(stream).getOrThrow

            assert(rendered.startsWith("---\n") && rendered.endsWith("...\n"))
        }

        def cstScalar(value: String): Yaml.Cst.Node =
            val mark = Yaml.Mark(0, 1, 1)
            Yaml.Cst.Node.Scalar(
                value,
                Yaml.Cst.ScalarSyntax.Canonical,
                Yaml.ScalarMeta(Absent, Absent, Yaml.ScalarStyle.Plain, mark),
                Yaml.Cst.SourceSpan(mark, mark),
                Absent
            )
        end cstScalar

        "decodes through a per-document CST transform stage" in {
            val yaml = "name: Alice\nage: 30\n"
            val rename =
                Yaml.pipeline.throughCst(_.replace(Yaml.Cst.Path.root / "name", cstScalar("Alicia")))

            assert(rename.decode[MTPerson](yaml) == Result.succeed(MTPerson("Alicia", 30)))
        }

        "preserves comments for the CST transform while feeding edited values to decode" in {
            val yaml = "# owner\nname: Alice\nage: 30\n"
            val edit =
                Yaml.pipeline.throughCst(_.replace(Yaml.Cst.Path.root / "age", cstScalar("31")))

            assertResult((comment = true, decoded = Result.succeed(MTPerson("Alice", 31)))) {
                (
                    comment = edit.render(yaml).getOrThrow.contains("# owner"),
                    decoded = edit.decode[MTPerson](yaml)
                )
            }
        }

        "runs the CST transform before event processors" in {
            val yaml = "name: placeholder\nage: 30\n"
            val pipeline =
                Yaml.pipeline
                    .throughCst(_.replace(Yaml.Cst.Path.root / "name", cstScalar("Alice")))
                    .through(scalarRewrite("Alice", "Alicia"))

            assert(pipeline.decode[MTPerson](yaml) == Result.succeed(MTPerson("Alicia", 30)))
        }

        "applies a stream transform before per-document handling" in {
            val yaml = "---\nname: Alice\nage: 30\n---\nname: Bob\nage: 25\n"
            val dropFirst =
                Yaml.pipeline.throughCstStream(stream => Result.succeed(stream.copy(documents = stream.documents.drop(1))))

            assertResult((hasBob = true, hasAlice = false)) {
                val rendered = dropFirst.render(yaml).getOrThrow
                (hasBob = rendered.contains("Bob"), hasAlice = rendered.contains("Alice"))
            }
        }

        "applies stream transform first then per-document transform on survivors" in {
            val yaml = "---\nname: Alice\nage: 30\n---\nname: Bob\nage: 25\n"
            val pipeline =
                Yaml.pipeline
                    .throughCstStream(stream => Result.succeed(stream.copy(documents = stream.documents.drop(1))))
                    .throughCst(_.replace(Yaml.Cst.Path.root / "name", cstScalar("Robert")))

            assertResult((hasRobert = true, hasAge25 = true, hasAlice = false)) {
                val rendered = pipeline.render(yaml).getOrThrow
                (hasRobert = rendered.contains("Robert"), hasAge25 = rendered.contains("age: 25"), hasAlice = rendered.contains("Alice"))
            }
        }

        "leaves String decode and render unchanged when no CST transform is set" in {
            val yaml = "name: Alice\nage: 30\n"

            assertResult((decoded = Result.succeed(MTPerson("Alice", 30)), rendered = Result.succeed(yaml), sameAsTopLevel = true)) {
                (
                    decoded = Yaml.pipeline.decode[MTPerson](yaml),
                    rendered = Yaml.pipeline.render(yaml),
                    sameAsTopLevel = Yaml.pipeline.decode[MTPerson](yaml) == Yaml.decode[MTPerson](yaml)
                )
            }
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

    private def cstFailure[Err](result: Result[Err | DecodeException, Yaml.Cst.Document]): String =
        result match
            case Result.Failure(e: ParseException) => e.getMessage
            case other                             => fail(s"Expected ParseException failure, got $other")
    end cstFailure
end YamlPipelineTest
