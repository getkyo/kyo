package kyo

class YamlCstTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

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

            assert(rendered == "Alice\n---\nname: Bob\n")
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
    }
end YamlCstTest
