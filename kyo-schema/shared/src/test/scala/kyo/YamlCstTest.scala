package kyo

class YamlCstTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private def collectEvents(yaml: String): Chunk[Yaml.Events.Event] =
        val events = new Yaml.Events.EventHandler[Chunk[Yaml.Events.Event], DecodeException]:
            override def event(
                context: Chunk[Yaml.Events.Event],
                event: Yaml.Events.Event
            ): Result[DecodeException, Chunk[Yaml.Events.Event]] =
                Result.succeed(context :+ event)
        end events

        Yaml.Events.visit(yaml, Chunk.empty[Yaml.Events.Event])(events).getOrThrow
    end collectEvents

    private def scalarSyntaxFromEvents(yaml: String): Yaml.Cst.ScalarSyntax =
        Yaml.Cst.fromEvents(collectEvents(yaml)).getOrThrow.root match
            case Present(Yaml.Cst.Node.Scalar(_, syntax, _, _, _)) => syntax
            case other                                             => fail(s"Expected scalar root, found $other")
    end scalarSyntaxFromEvents

    private def parseFailure(result: Result[DecodeException, Yaml.Cst.Document]): String =
        result match
            case Result.Failure(e: ParseException) => e.getMessage
            case other                             => fail(s"Expected ParseException failure, got $other")
    end parseFailure

    "Yaml.Cst public model" - {

        "builds structural paths with mapping keys and sequence indexes" in {
            val path =
                Yaml.Cst.Path.root / "services" / "api" / "environment" / 0

            assertResult(
                (
                    size = 4,
                    first = Yaml.Cst.Path.Segment.Key("services"),
                    last = Yaml.Cst.Path.Segment.Index(0),
                    text = "services.api.environment[0]"
                )
            ) {
                (
                    size = path.segments.size,
                    first = path.segments(0),
                    last = path.segments(3),
                    text = path.show
                )
            }
        }

        "constructs canonical scalar documents" in {
            val mark = Yaml.Mark(0, 1, 1)
            val span = Yaml.Cst.SourceSpan(mark, mark)
            val scalar =
                Yaml.Cst.Node.Scalar(
                    "Alice",
                    Yaml.Cst.ScalarSyntax.Canonical,
                    Yaml.ScalarMeta(Absent, Absent, Yaml.ScalarStyle.Plain, mark),
                    span,
                    Absent
                )
            val doc =
                Yaml.Cst.Document(Maybe(scalar), Chunk.empty, Chunk.empty, span, Absent)

            assert(doc.render(using Yaml.WriterConfig.Default) == "Alice\n")
        }

        "builds canonical mapping CST from parser events" in {
            val yaml =
                """name: Alice
                  |age: 30
                  |""".stripMargin

            val doc =
                Yaml.Cst.fromEvents(collectEvents(yaml)).getOrThrow

            assertResult(
                (
                    rendered = "name: Alice\nage: 30\n",
                    rootSyntax = Yaml.Cst.MappingSyntax.Canonical,
                    rootSource = Absent,
                    entries = Chunk(
                        (key = "name", value = "Alice"),
                        (key = "age", value = "30")
                    ),
                    decoded = Result.succeed(MTPerson("Alice", 30))
                )
            ) {
                val rendered = doc.render(using Yaml.WriterConfig.Default)
                val mapping =
                    doc.root match
                        case Present(Yaml.Cst.Node.Mapping(entries, syntax, _, _, source)) =>
                            (
                                syntax = syntax,
                                source = source,
                                entries = entries.map {
                                    case Yaml.Cst.MappingEntry(
                                            Yaml.Cst.Node.Scalar(key, _, _, _, _),
                                            Yaml.Cst.Node.Scalar(value, _, _, _, _),
                                            _,
                                            _,
                                            _
                                        ) =>
                                        (key = key, value = value)
                                    case other =>
                                        fail(s"Expected scalar mapping entry, found $other")
                                }
                            )
                        case other =>
                            fail(s"Expected canonical mapping root, found $other")
                    end match
                end mapping
                (
                    rendered = rendered,
                    rootSyntax = mapping.syntax,
                    rootSource = mapping.source,
                    entries = mapping.entries,
                    decoded = Yaml.decode[MTPerson](rendered)
                )
            }
        }

        "builds canonical CST from schema values" in {
            val doc =
                Yaml.Cst.from(MTPerson("Alice", 30)).getOrThrow

            assertResult(
                (
                    documentSource = Absent,
                    rootSyntax = Yaml.Cst.MappingSyntax.Canonical,
                    rootSource = Absent,
                    entries = Chunk(
                        (
                            key = "name",
                            keySyntax = Yaml.Cst.ScalarSyntax.Canonical,
                            keySource = Absent,
                            value = "Alice",
                            valueSyntax = Yaml.Cst.ScalarSyntax.Canonical,
                            valueSource = Absent
                        ),
                        (
                            key = "age",
                            keySyntax = Yaml.Cst.ScalarSyntax.Canonical,
                            keySource = Absent,
                            value = "30",
                            valueSyntax = Yaml.Cst.ScalarSyntax.Canonical,
                            valueSource = Absent
                        )
                    ),
                    decoded = Result.succeed(MTPerson("Alice", 30))
                )
            ) {
                val mapping =
                    doc.root match
                        case Present(Yaml.Cst.Node.Mapping(entries, syntax, _, _, source)) =>
                            (
                                syntax = syntax,
                                source = source,
                                entries = entries.map {
                                    case Yaml.Cst.MappingEntry(
                                            Yaml.Cst.Node.Scalar(key, keySyntax, _, _, keySource),
                                            Yaml.Cst.Node.Scalar(value, valueSyntax, _, _, valueSource),
                                            _,
                                            _,
                                            _
                                        ) =>
                                        (
                                            key = key,
                                            keySyntax = keySyntax,
                                            keySource = keySource,
                                            value = value,
                                            valueSyntax = valueSyntax,
                                            valueSource = valueSource
                                        )
                                    case other =>
                                        fail(s"Expected scalar mapping entry, found $other")
                                }
                            )
                        case other =>
                            fail(s"Expected canonical mapping root, found $other")
                    end match
                end mapping
                (
                    documentSource = doc.originalSource,
                    rootSyntax = mapping.syntax,
                    rootSource = mapping.source,
                    entries = mapping.entries,
                    decoded = Yaml.decode[MTPerson](doc.render(using Yaml.WriterConfig.Default))
                )
            }
        }

        "preserves scalar syntax from parser events" in {
            assertResult(
                Chunk(
                    Yaml.Cst.ScalarSyntax.SingleQuoted,
                    Yaml.Cst.ScalarSyntax.DoubleQuoted,
                    Yaml.Cst.ScalarSyntax.Literal,
                    Yaml.Cst.ScalarSyntax.Folded
                )
            ) {
                Chunk(
                    scalarSyntaxFromEvents("'Alice'\n"),
                    scalarSyntaxFromEvents("\"Alice\"\n"),
                    scalarSyntaxFromEvents("|\n  Alice\n"),
                    scalarSyntaxFromEvents(">\n  Alice\n")
                )
            }
        }

        "builds canonical CST from schema scalar values" in {
            val doc =
                Yaml.Cst.from("Alice").getOrThrow

            assertResult(
                (
                    documentSource = Absent,
                    value = "Alice",
                    syntax = Yaml.Cst.ScalarSyntax.Canonical,
                    source = Absent,
                    decoded = Result.succeed("Alice")
                )
            ) {
                doc.root match
                    case Present(Yaml.Cst.Node.Scalar(value, syntax, _, _, source)) =>
                        (
                            documentSource = doc.originalSource,
                            value = value,
                            syntax = syntax,
                            source = source,
                            decoded = Yaml.decode[String](doc.render(using Yaml.WriterConfig.Default))
                        )
                    case other =>
                        fail(s"Expected scalar root, found $other")
                end match
            }
        }

        "fails to build CST from a second document in an event stream" in {
            val mark = Yaml.Mark(0, 1, 1)
            val events = Chunk(
                Yaml.Events.Event.StreamStart(mark),
                Yaml.Events.Event.DocumentStart(mark),
                Yaml.Events.Event.Scalar("Alice", Yaml.ScalarMeta(Absent, Absent, Yaml.ScalarStyle.Plain, mark)),
                Yaml.Events.Event.DocumentEnd(mark),
                Yaml.Events.Event.DocumentStart(mark),
                Yaml.Events.Event.DocumentEnd(mark),
                Yaml.Events.Event.StreamEnd(mark)
            )

            assert(parseFailure(Yaml.Cst.fromEvents(events)).contains("Unexpected YAML document start"))
        }

        "fails to build CST when a document ends before a collection is closed" in {
            val mark = Yaml.Mark(0, 1, 1)
            val events = Chunk(
                Yaml.Events.Event.StreamStart(mark),
                Yaml.Events.Event.DocumentStart(mark),
                Yaml.Events.Event.MappingStart(Yaml.Meta(Absent, Absent, mark)),
                Yaml.Events.Event.DocumentEnd(mark),
                Yaml.Events.Event.CollectionEnd(Yaml.Events.CollectionKind.Mapping, mark),
                Yaml.Events.Event.StreamEnd(mark)
            )

            assert(parseFailure(Yaml.Cst.fromEvents(events)).contains("Unclosed YAML collection"))
        }

        "fails to build CST when a node appears after document end" in {
            val mark = Yaml.Mark(0, 1, 1)
            val events = Chunk(
                Yaml.Events.Event.StreamStart(mark),
                Yaml.Events.Event.DocumentStart(mark),
                Yaml.Events.Event.DocumentEnd(mark),
                Yaml.Events.Event.Scalar("Alice", Yaml.ScalarMeta(Absent, Absent, Yaml.ScalarStyle.Plain, mark)),
                Yaml.Events.Event.StreamEnd(mark)
            )

            assert(parseFailure(Yaml.Cst.fromEvents(events)).contains("Unexpected YAML node after document end"))
        }

        "builds canonical nested sequence and mapping CST from parser events" in {
            val yaml =
                """- name: Alice
                  |  age: 30
                  |- name: Bob
                  |  age: 25
                  |""".stripMargin

            val doc =
                Yaml.Cst.fromEvents(collectEvents(yaml)).getOrThrow

            doc.root match
                case Present(Yaml.Cst.Node.Sequence(entries, Yaml.Cst.SequenceSyntax.Canonical, _, _, Absent)) =>
                    assert(entries.size == 2)
                    entries(0).value match
                        case Yaml.Cst.Node.Mapping(_, Yaml.Cst.MappingSyntax.Canonical, _, _, Absent) =>
                        case other =>
                            fail(s"Expected canonical mapping entry, found $other")
                    end match
                case other =>
                    fail(s"Expected canonical sequence root, found $other")
            end match

            assertResult(Result.succeed(List(MTPerson("Alice", 30), MTPerson("Bob", 25)))) {
                Yaml.decode[List[MTPerson]](doc.render(using Yaml.WriterConfig.Default))
            }
        }

        "renders canonical streams with document separators" in {
            val mark       = Yaml.Mark(0, 1, 1)
            val span       = Yaml.Cst.SourceSpan(mark, mark)
            val scalarMeta = Yaml.ScalarMeta(Absent, Absent, Yaml.ScalarStyle.Plain, mark)
            val meta       = Yaml.Meta(Absent, Absent, mark)

            val alice =
                Yaml.Cst.Node.Scalar("Alice", Yaml.Cst.ScalarSyntax.Canonical, scalarMeta, span, Absent)
            val name =
                Yaml.Cst.Node.Scalar("name", Yaml.Cst.ScalarSyntax.Canonical, scalarMeta, span, Absent)
            val bob =
                Yaml.Cst.Node.Scalar("Bob", Yaml.Cst.ScalarSyntax.Canonical, scalarMeta, span, Absent)
            val mapping =
                Yaml.Cst.Node.Mapping(
                    Chunk(Yaml.Cst.MappingEntry(name, bob, span)),
                    Yaml.Cst.MappingSyntax.Block,
                    meta,
                    span,
                    Absent
                )

            val stream =
                Yaml.Cst.Stream(
                    Chunk(
                        Yaml.Cst.Document(Maybe(alice), Chunk.empty, Chunk.empty, span, Absent),
                        Yaml.Cst.Document(Maybe(mapping), Chunk.empty, Chunk.empty, span, Absent)
                    ),
                    Chunk.empty,
                    Chunk.empty,
                    span,
                    Absent
                )
            val rendered = stream.render(using Yaml.WriterConfig.Default)

            assert(rendered == "---\nAlice\n---\nname: Bob\n")
            Yaml.parseAll(rendered) match
                case Result.Success(parsed) =>
                    assert(parsed.size == 2)
                    parsed(0) match
                        case Yaml.Node.Scalar(value, _) =>
                            assert(value == "Alice")
                        case other =>
                            fail(s"Expected scalar document, found $other")
                    end match
                    parsed(1) match
                        case Yaml.Node.Mapping(entries, _) =>
                            assert(entries.size == 1)
                            entries(0) match
                                case (Yaml.Node.Scalar(key, _), Yaml.Node.Scalar(value, _)) =>
                                    assert(key == "name")
                                    assert(value == "Bob")
                                case other =>
                                    fail(s"Expected scalar mapping entry, found $other")
                            end match
                        case other =>
                            fail(s"Expected mapping document, found $other")
                    end match
                case Result.Failure(e) =>
                    fail(e.getMessage())
                case Result.Panic(e) =>
                    fail(e.getMessage())
            end match
        }

        "renders canonical streams with leading empty documents" in {
            val mark     = Yaml.Mark(0, 1, 1)
            val span     = Yaml.Cst.SourceSpan(mark, mark)
            val emptyDoc = Yaml.Cst.Document(Absent, Chunk.empty, Chunk.empty, span, Absent)
            val scalarDoc =
                Yaml.Cst.Document(
                    Maybe(Yaml.Cst.Node.Scalar(
                        "Alice",
                        Yaml.Cst.ScalarSyntax.Canonical,
                        Yaml.ScalarMeta(Absent, Absent, Yaml.ScalarStyle.Plain, mark),
                        span,
                        Absent
                    )),
                    Chunk.empty,
                    Chunk.empty,
                    span,
                    Absent
                )
            val stream   = Yaml.Cst.Stream(Chunk(emptyDoc, scalarDoc), Chunk.empty, Chunk.empty, span, Absent)
            val rendered = stream.render(using Yaml.WriterConfig.Default)

            assert(rendered == "---\n---\nAlice\n")
            assert(Yaml.decodeAll[Option[String]](rendered) == Result.succeed(Chunk(None, Some("Alice"))))
        }

        "renders canonical streams once with start document markers" in {
            val mark     = Yaml.Mark(0, 1, 1)
            val span     = Yaml.Cst.SourceSpan(mark, mark)
            val emptyDoc = Yaml.Cst.Document(Absent, Chunk.empty, Chunk.empty, span, Absent)
            val scalarDoc =
                Yaml.Cst.Document(
                    Maybe(Yaml.Cst.Node.Scalar(
                        "Alice",
                        Yaml.Cst.ScalarSyntax.Canonical,
                        Yaml.ScalarMeta(Absent, Absent, Yaml.ScalarStyle.Plain, mark),
                        span,
                        Absent
                    )),
                    Chunk.empty,
                    Chunk.empty,
                    span,
                    Absent
                )
            val config =
                Yaml.WriterConfig.Default.copy(documentMarkers = Yaml.WriterConfig.DocumentMarkers.Start)
            val stream   = Yaml.Cst.Stream(Chunk(emptyDoc, scalarDoc), Chunk.empty, Chunk.empty, span, Absent)
            val rendered = stream.render(using config)

            assert(rendered == "---\n---\nAlice\n")
            assert(Yaml.decodeAll[Option[String]](rendered) == Result.succeed(Chunk(None, Some("Alice"))))
        }

        "renders canonical streams once with start and end document markers" in {
            val mark     = Yaml.Mark(0, 1, 1)
            val span     = Yaml.Cst.SourceSpan(mark, mark)
            val emptyDoc = Yaml.Cst.Document(Absent, Chunk.empty, Chunk.empty, span, Absent)
            val scalarDoc =
                Yaml.Cst.Document(
                    Maybe(Yaml.Cst.Node.Scalar(
                        "Alice",
                        Yaml.Cst.ScalarSyntax.Canonical,
                        Yaml.ScalarMeta(Absent, Absent, Yaml.ScalarStyle.Plain, mark),
                        span,
                        Absent
                    )),
                    Chunk.empty,
                    Chunk.empty,
                    span,
                    Absent
                )
            val config =
                Yaml.WriterConfig.Default.copy(documentMarkers = Yaml.WriterConfig.DocumentMarkers.StartAndEnd)
            val stream   = Yaml.Cst.Stream(Chunk(emptyDoc, scalarDoc), Chunk.empty, Chunk.empty, span, Absent)
            val rendered = stream.render(using config)

            assert(rendered == "---\n---\nAlice\n...\n")
            assert(Yaml.decodeAll[Option[String]](rendered) == Result.succeed(Chunk(None, Some("Alice"))))
        }

        "renders canonical streams with consecutive empty documents" in {
            val mark = Yaml.Mark(0, 1, 1)
            val span = Yaml.Cst.SourceSpan(mark, mark)
            val meta = Yaml.ScalarMeta(Absent, Absent, Yaml.ScalarStyle.Plain, mark)
            def scalarDoc(value: String): Yaml.Cst.Document =
                Yaml.Cst.Document(
                    Maybe(Yaml.Cst.Node.Scalar(value, Yaml.Cst.ScalarSyntax.Canonical, meta, span, Absent)),
                    Chunk.empty,
                    Chunk.empty,
                    span,
                    Absent
                )
            end scalarDoc
            val emptyDoc = Yaml.Cst.Document(Absent, Chunk.empty, Chunk.empty, span, Absent)
            val stream = Yaml.Cst.Stream(
                Chunk(scalarDoc("Alice"), emptyDoc, emptyDoc, scalarDoc("Bob")),
                Chunk.empty,
                Chunk.empty,
                span,
                Absent
            )
            val rendered = stream.render(using Yaml.WriterConfig.Default)

            assert(rendered == "---\nAlice\n---\n---\n---\nBob\n")
            assert(Yaml.decodeAll[Option[String]](rendered) == Result.succeed(Chunk(Some("Alice"), None, None, Some("Bob"))))
        }

        "renders canonical streams with trailing empty documents" in {
            val mark = Yaml.Mark(0, 1, 1)
            val span = Yaml.Cst.SourceSpan(mark, mark)
            val scalarDoc =
                Yaml.Cst.Document(
                    Maybe(Yaml.Cst.Node.Scalar(
                        "Alice",
                        Yaml.Cst.ScalarSyntax.Canonical,
                        Yaml.ScalarMeta(Absent, Absent, Yaml.ScalarStyle.Plain, mark),
                        span,
                        Absent
                    )),
                    Chunk.empty,
                    Chunk.empty,
                    span,
                    Absent
                )
            val emptyDoc = Yaml.Cst.Document(Absent, Chunk.empty, Chunk.empty, span, Absent)
            val stream   = Yaml.Cst.Stream(Chunk(scalarDoc, emptyDoc), Chunk.empty, Chunk.empty, span, Absent)
            val rendered = stream.render(using Yaml.WriterConfig.Default)

            assert(rendered == "---\nAlice\n---\n")
            assert(Yaml.decodeAll[Option[String]](rendered) == Result.succeed(Chunk(Some("Alice"), None)))
        }
    }
end YamlCstTest
