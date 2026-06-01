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

    private def assertSourceRoundTrip[A](yaml: String, expected: A)(using Schema[A]) =
        val rendered = Yaml.cst(yaml).getOrThrow.render(using Yaml.WriterConfig.Default)

        assertResult((source = yaml, decoded = Result.succeed(expected))) {
            (source = rendered, decoded = Yaml.decode[A](rendered))
        }
    end assertSourceRoundTrip

    private def scalar(value: String): Yaml.Cst.Node =
        val mark = Yaml.Mark(0, 1, 1)
        val span = Yaml.Cst.SourceSpan(mark, mark)
        Yaml.Cst.Node.Scalar(
            value,
            Yaml.Cst.ScalarSyntax.Canonical,
            Yaml.ScalarMeta(Absent, Absent, Yaml.ScalarStyle.Plain, mark),
            span,
            Absent
        )
    end scalar

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

        "parses CST through the public helper" in {
            val yaml =
                """name: Alice
                  |age: 30
                  |""".stripMargin

            assert(Yaml.cst(yaml).map(_.render(using Yaml.WriterConfig.Default)) == Result.succeed(yaml))
        }

        "preserves GitHub workflow source and decodes rendered YAML" in {
            val yaml =
                """name: CI
                  |on:
                  |  push:
                  |    branches: [main, release]
                  |  pull_request:
                  |jobs:
                  |  build:
                  |    runs-on: ubuntu-latest
                  |    steps:
                  |      - name: Checkout
                  |        uses: actions/checkout@v4
                  |      - name: Test
                  |        run: |-
                  |          sbt 'kyo-schema/test'
                  |          echo done
                  |        with:
                  |          cache: sbt
                  |""".stripMargin

            assertSourceRoundTrip(
                yaml,
                YamlGithubWorkflow(
                    "CI",
                    YamlGithubOn(YamlGithubPush(List("main", "release")), None),
                    Map(
                        "build" -> YamlGithubJob(
                            "ubuntu-latest",
                            List(
                                YamlGithubStep(Some("Checkout"), Some("actions/checkout@v4"), None, None),
                                YamlGithubStep(
                                    Some("Test"),
                                    None,
                                    Some("sbt 'kyo-schema/test'\necho done"),
                                    Some(Map("cache" -> "sbt"))
                                )
                            )
                        )
                    )
                )
            )
        }

        "preserves OpenAPI source and decodes rendered YAML" in {
            val yaml =
                """openapi: 3.1.0
                  |info:
                  |  title: Users API
                  |  version: 1.0.0
                  |  description: >-
                  |    Users API for
                  |    client apps.
                  |paths:
                  |  /users/{id}:
                  |    get:
                  |      summary: Get a user
                  |      operationId: getUser
                  |      parameters:
                  |        - name: id
                  |          in: path
                  |          required: true
                  |          schema: {type: string, format: uuid}
                  |      responses:
                  |        "200":
                  |          description: ok
                  |components:
                  |  schemas:
                  |    UserId: {type: string, format: uuid}
                  |""".stripMargin

            assertSourceRoundTrip(
                yaml,
                YamlOpenApiSpec(
                    "3.1.0",
                    YamlOpenApiInfo("Users API", "1.0.0", "Users API for client apps."),
                    Map(
                        "/users/{id}" -> YamlOpenApiPathItem(
                            YamlOpenApiOperation(
                                "Get a user",
                                "getUser",
                                List(YamlOpenApiParameter("id", "path", true, YamlOpenApiSchema("string", Some("uuid")))),
                                Map("200" -> YamlOpenApiResponse("ok"))
                            )
                        )
                    ),
                    YamlOpenApiComponents(Map("UserId" -> YamlOpenApiSchema("string", Some("uuid"))))
                )
            )
        }

        "preserves Docker Compose source and decodes rendered YAML" in {
            val yaml =
                """version: "3.9"
                  |x-logging: &default-logging
                  |  driver: json-file
                  |  options:
                  |    max-size: 10m
                  |    max-file: "3"
                  |services:
                  |  api:
                  |    image: ghcr.io/acme/api:1.2.3
                  |    ports:
                  |      - "8080:80"
                  |    environment:
                  |      APP_ENV: production
                  |      FEATURE_FLAG: "true"
                  |      REGION: NO
                  |    depends_on: [db, redis]
                  |    volumes:
                  |      - "app-data:/var/lib/app"
                  |      - "./config:/app/config:ro"
                  |    networks:
                  |      - backend
                  |    healthcheck:
                  |      test: ["CMD-SHELL", "curl -f http://localhost:8080/health || exit 1"]
                  |      interval: 30s
                  |      timeout: 10s
                  |      retries: 3
                  |    logging: *default-logging
                  |    restart: unless-stopped
                  |  db:
                  |    image: postgres:16
                  |    environment:
                  |      POSTGRES_DB: app
                  |      POSTGRES_PASSWORD: secret
                  |    volumes:
                  |      - "db-data:/var/lib/postgresql/data"
                  |    networks: [backend]
                  |volumes:
                  |  app-data:
                  |    driver: local
                  |  db-data:
                  |    driver: local
                  |networks:
                  |  backend:
                  |    driver: bridge
                  |""".stripMargin

            val logging = YamlComposeLogging("json-file", Map("max-size" -> "10m", "max-file" -> "3"))
            assertSourceRoundTrip(
                yaml,
                YamlDockerCompose(
                    "3.9",
                    logging,
                    Map(
                        "api" -> YamlComposeService(
                            "ghcr.io/acme/api:1.2.3",
                            Some(List("8080:80")),
                            Some(Map("APP_ENV" -> "production", "FEATURE_FLAG" -> "true", "REGION" -> "NO")),
                            Some(List("db", "redis")),
                            Some(List("app-data:/var/lib/app", "./config:/app/config:ro")),
                            Some(List("backend")),
                            Some(YamlComposeHealthcheck(
                                List("CMD-SHELL", "curl -f http://localhost:8080/health || exit 1"),
                                "30s",
                                "10s",
                                3
                            )),
                            Some(logging),
                            Some("unless-stopped")
                        ),
                        "db" -> YamlComposeService(
                            "postgres:16",
                            None,
                            Some(Map("POSTGRES_DB" -> "app", "POSTGRES_PASSWORD" -> "secret")),
                            None,
                            Some(List("db-data:/var/lib/postgresql/data")),
                            Some(List("backend")),
                            None,
                            None,
                            None
                        )
                    ),
                    Map("app-data" -> YamlComposeVolume(Some("local")), "db-data" -> YamlComposeVolume(Some("local"))),
                    Map("backend"  -> YamlComposeNetwork(Some("bridge")))
                )
            )
        }

        "preserves multiline scalar source forms and decodes rendered YAML" in {
            val yaml =
                """literalStrip: |-
                  |  line one
                  |  line two
                  |
                  |literalClip: |
                  |  line one
                  |  line two
                  |
                  |literalKeep: |+
                  |  line one
                  |  line two
                  |
                  |
                  |literalIndent: |4
                  |    indented
                  |      deeper
                  |
                  |foldedStrip: >2-
                  |  line one
                  |  line two
                  |
                  |  line three
                  |foldedKeep: >+2
                  |  line one
                  |  line two
                  |
                  |
                  |singleQuoted: 'line one
                  |  line two
                  |
                  |  line three'
                  |doubleQuoted: "line one
                  |  line two\nline three"
                  |plain: line one
                  |  line two
                  |
                  |  line three
                  |""".stripMargin

            assertSourceRoundTrip(
                yaml,
                YamlMultilineScalars(
                    literalStrip = "line one\nline two",
                    literalClip = "line one\nline two\n",
                    literalKeep = "line one\nline two\n\n\n",
                    literalIndent = "indented\n  deeper\n",
                    foldedStrip = "line one line two\nline three",
                    foldedKeep = "line one line two\n\n\n",
                    singleQuoted = "line one line two\nline three",
                    doubleQuoted = "line one line two\nline three",
                    plain = "line one line two\nline three"
                )
            )
        }

        "preserves yaml document from hell gotcha source and decodes rendered YAML" in {
            val yaml =
                """server_config:
                  |  port_mapping:
                  |    - 22:22
                  |    - 80:80
                  |    - 443:443
                  |  serve:
                  |    - /robots.txt
                  |    - /favicon.ico
                  |    - "*.html"
                  |    - "*.png"
                  |    - "!.git"
                  |  geoblock_regions:
                  |    - dk
                  |    - fi
                  |    - is
                  |    - no
                  |    - se
                  |  flush_cache:
                  |    on: [push, memory_pressure]
                  |    priority: background
                  |  allow_postgres_versions:
                  |    - 9.5.25
                  |    - 9.6.24
                  |    - 10.23
                  |    - 12.13
                  |""".stripMargin

            assertSourceRoundTrip(
                yaml,
                YamlHellConfig(
                    YamlHellServerConfig(
                        List("22:22", "80:80", "443:443"),
                        List("/robots.txt", "/favicon.ico", "*.html", "*.png", "!.git"),
                        List("dk", "fi", "is", "no", "se"),
                        YamlHellFlushCache(List("push", "memory_pressure"), "background")
                    )
                )
            )
        }

        "preserves YAML 1.1 Norway problem text exactly in CST source" in {
            val yaml =
                """norway: NO
                  |on: on
                  |off: off
                  |yes: yes
                  |no: no
                  |trueValue: true
                  |falseValue: FALSE
                  |nullValue: ~
                  |""".stripMargin
            val doc      = Yaml.cst(yaml).getOrThrow
            val rendered = doc.render(using Yaml.WriterConfig.Default)

            val scalarText =
                doc.root match
                    case Present(Yaml.Cst.Node.Mapping(entries, _, _, _, _)) =>
                        entries.map {
                            case Yaml.Cst.MappingEntry(
                                    Yaml.Cst.Node.Scalar(key, _, _, _, _),
                                    Yaml.Cst.Node.Scalar(value, _, _, _, _),
                                    _,
                                    _,
                                    _
                                ) =>
                                key -> value
                            case other =>
                                fail(s"Expected scalar mapping entry, found $other")
                        }.toMap
                    case other =>
                        fail(s"Expected mapping root, found $other")
                end match
            end scalarText

            assertResult(
                (
                    rendered = yaml,
                    norway = "NO",
                    on = "on",
                    off = "off",
                    yes = "yes",
                    no = "no",
                    decoded = Result.succeed(
                        YamlCoreScalars("NO", "on", "off", "yes", "no", true, false, None)
                    )
                )
            ) {
                (
                    rendered = rendered,
                    norway = scalarText("norway"),
                    on = scalarText("on"),
                    off = scalarText("off"),
                    yes = scalarText("yes"),
                    no = scalarText("no"),
                    decoded = Yaml.decode[YamlCoreScalars](rendered)
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

            assertResult(
                (
                    rendered = "---\n---\nAlice\n",
                    decoded = Result.succeed(Chunk(None, Some("Alice")))
                )
            ) {
                (
                    rendered = rendered,
                    decoded = Yaml.decodeAll[Option[String]](rendered)
                )
            }
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

            assertResult(
                (
                    rendered = "---\n---\nAlice\n",
                    decoded = Result.succeed(Chunk(None, Some("Alice")))
                )
            ) {
                (
                    rendered = rendered,
                    decoded = Yaml.decodeAll[Option[String]](rendered)
                )
            }
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

            assertResult(
                (
                    rendered = "---\n---\nAlice\n...\n",
                    decoded = Result.succeed(Chunk(None, Some("Alice")))
                )
            ) {
                (
                    rendered = rendered,
                    decoded = Yaml.decodeAll[Option[String]](rendered)
                )
            }
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

            assertResult(
                (
                    rendered = "---\nAlice\n---\n---\n---\nBob\n",
                    decoded = Result.succeed(Chunk(Some("Alice"), None, None, Some("Bob")))
                )
            ) {
                (
                    rendered = rendered,
                    decoded = Yaml.decodeAll[Option[String]](rendered)
                )
            }
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

            assertResult(
                (
                    rendered = "---\nAlice\n---\n",
                    decoded = Result.succeed(Chunk(Some("Alice"), None))
                )
            ) {
                (
                    rendered = rendered,
                    decoded = Yaml.decodeAll[Option[String]](rendered)
                )
            }
        }
    }

    "Yaml.Cst event interop" - {

        "emits scalar and structural events equivalent to CST document" in {
            val doc = Yaml.cst("name: Alice\nage: 30\n").getOrThrow

            def shape(
                event: String,
                size: Maybe[Int] = Absent,
                value: Maybe[String] = Absent,
                collection: Maybe[String] = Absent
            ) =
                (
                    event = event,
                    size = size,
                    value = value,
                    collection = collection
                )
            end shape

            assertResult(
                Chunk(
                    shape("StreamStart"),
                    shape("DocumentStart"),
                    shape("MappingStart", size = Maybe(2)),
                    shape("Scalar", value = Maybe("name")),
                    shape("Scalar", value = Maybe("Alice")),
                    shape("Scalar", value = Maybe("age")),
                    shape("Scalar", value = Maybe("30")),
                    shape("CollectionEnd", collection = Maybe("Mapping")),
                    shape("DocumentEnd"),
                    shape("StreamEnd")
                )
            ) {
                doc.events.map {
                    case Yaml.Events.Event.StreamStart(_) =>
                        shape("StreamStart")
                    case Yaml.Events.Event.DocumentStart(_) =>
                        shape("DocumentStart")
                    case Yaml.Events.Event.MappingStart(_, size) =>
                        shape("MappingStart", size = size)
                    case Yaml.Events.Event.SequenceStart(_, size) =>
                        shape("SequenceStart", size = size)
                    case Yaml.Events.Event.Scalar(value, _) =>
                        shape("Scalar", value = Maybe(value))
                    case Yaml.Events.Event.Alias(name, _) =>
                        shape("Alias", value = Maybe(name.value))
                    case Yaml.Events.Event.CollectionEnd(kind, _) =>
                        shape("CollectionEnd", collection = Maybe(kind.toString))
                    case Yaml.Events.Event.DocumentEnd(_) =>
                        shape("DocumentEnd")
                    case Yaml.Events.Event.StreamEnd(_) =>
                        shape("StreamEnd")
                }
            }
        }

        "decodes a transformed CST through events without rendering" in {
            val doc = Yaml.cst("name: Alice\nage: 30\n").getOrThrow
                .replace(Yaml.Cst.Path.root / "name", scalar("Bob"))
                .getOrThrow
            val reader = kyo.internal.yaml.YamlEventReader(doc.events, Yaml.SpecVersion.Yaml12)
            reader.resetLimits(Yaml.DefaultMaxDepth, Yaml.DefaultMaxCollectionSize)

            assertResult(MTPerson("Bob", 30))(summon[Schema[MTPerson]].readFrom(reader))
        }

        "emits anchors and aliases from CST events" in {
            val doc = Yaml.cst("value: &name Alice\ncopy: *name\n").getOrThrow

            assertResult((anchors = Chunk("name"), aliases = Chunk("name"))) {
                val anchors = doc.events.collect {
                    case Yaml.Events.Event.Scalar(_, Yaml.ScalarMeta(Present(anchor), _, _, _)) => anchor.value
                }
                val aliases = doc.events.collect {
                    case Yaml.Events.Event.Alias(name, _) => name.value
                }

                (anchors = anchors, aliases = aliases)
            }
        }

        "emits collection metadata tags and anchors from CST events" in {
            val doc = Yaml.cst("items: &items !!seq\n  - one\n").getOrThrow

            val metadata = doc.events.collect {
                case Yaml.Events.Event.SequenceStart(Yaml.Meta(anchor, tag, _), size) =>
                    (
                        anchor = anchor.map(_.value),
                        tag = tag.map(_.value),
                        size = size
                    )
            }

            assertResult(
                Chunk((anchor = Maybe("items"), tag = Maybe("!!seq"), size = Maybe(1)))
            )(metadata)
        }

        "emits mapping metadata tags and anchors from CST events" in {
            val doc = Yaml.cst("value: !local &map\n  a: 1\ncopy: *map\n").getOrThrow

            val metadata = doc.events.collect {
                case Yaml.Events.Event.MappingStart(Yaml.Meta(Present(anchor), tag, _), size) =>
                    (
                        anchor = Maybe(anchor),
                        tag = tag,
                        size = size
                    )
            }
            val aliases = doc.events.collect {
                case Yaml.Events.Event.Alias(name, _) => name
            }

            assertResult(
                (
                    mappings = Chunk((
                        anchor = Maybe(Yaml.Anchor("map")),
                        tag = Maybe(Yaml.YamlTag("!local")),
                        size = Maybe(1)
                    )),
                    aliases = Chunk(Yaml.Anchor("map"))
                )
            ) {
                (mappings = metadata, aliases = aliases)
            }
        }

        "emits only stream and document boundaries for empty documents" in {
            val doc = Yaml.cst("").getOrThrow

            assertResult(
                Chunk(
                    "StreamStart",
                    "DocumentStart",
                    "DocumentEnd",
                    "StreamEnd"
                )
            ) {
                doc.events.map {
                    case Yaml.Events.Event.StreamStart(_)      => "StreamStart"
                    case Yaml.Events.Event.DocumentStart(_)    => "DocumentStart"
                    case Yaml.Events.Event.MappingStart(_, _)  => "MappingStart"
                    case Yaml.Events.Event.SequenceStart(_, _) => "SequenceStart"
                    case Yaml.Events.Event.Scalar(_, _)        => "Scalar"
                    case Yaml.Events.Event.Alias(_, _)         => "Alias"
                    case Yaml.Events.Event.CollectionEnd(_, _) => "CollectionEnd"
                    case Yaml.Events.Event.DocumentEnd(_)      => "DocumentEnd"
                    case Yaml.Events.Event.StreamEnd(_)        => "StreamEnd"
                }
            }
        }
    }

    "Yaml.Cst edits" - {

        def scalar(value: String): Yaml.Cst.Node =
            val mark = Yaml.Mark(0, 1, 1)
            val span = Yaml.Cst.SourceSpan(mark, mark)
            Yaml.Cst.Node.Scalar(
                value,
                Yaml.Cst.ScalarSyntax.Canonical,
                Yaml.ScalarMeta(Absent, Absent, Yaml.ScalarStyle.Plain, mark),
                span,
                Absent
            )
        end scalar

        def mapping(entries: (String, Yaml.Cst.Node)*): Yaml.Cst.Node =
            val mark = Yaml.Mark(0, 1, 1)
            val span = Yaml.Cst.SourceSpan(mark, mark)
            Yaml.Cst.Node.Mapping(
                Chunk.from(entries).map { case (key, value) =>
                    Yaml.Cst.MappingEntry(scalar(key), value, span)
                },
                Yaml.Cst.MappingSyntax.Canonical,
                Yaml.Meta(Absent, Absent, mark),
                span,
                Absent
            )
        end mapping

        "replaces scalar by structural path while preserving leading comment in rendered output" in {
            val yaml = """# app
                         |services:
                         |  api:
                         |    # image comment
                         |    image: app:v1
                         |    ports: [8080]
                         |""".stripMargin
            val replacement = scalar("app:v2")
            val edited =
                Yaml.cst(yaml).getOrThrow.replace(Yaml.Cst.Path.root / "services" / "api" / "image", replacement).getOrThrow
            val rendered = edited.render(using Yaml.WriterConfig.Default)
            val expected = """# app
                             |services:
                             |  api:
                             |    # image comment
                             |    image: app:v2
                             |    ports:
                             |      - 8080
                             |""".stripMargin

            assertResult((sourceCleared = true, rendered = expected)) {
                (sourceCleared = edited.source.isEmpty, rendered = rendered)
            }
            Yaml.parse(rendered) match
                case Result.Success(Yaml.Node.Mapping(servicesRoot, _)) =>
                    servicesRoot(0) match
                        case (Yaml.Node.Scalar("services", _), Yaml.Node.Mapping(serviceEntries, _)) =>
                            serviceEntries(0) match
                                case (Yaml.Node.Scalar("api", _), Yaml.Node.Mapping(apiEntries, _)) =>
                                    assert(apiEntries(0) match
                                        case (Yaml.Node.Scalar("image", _), Yaml.Node.Scalar("app:v2", _)) => true
                                        case _                                                             => false)
                                    assert(apiEntries(1) match
                                        case (Yaml.Node.Scalar("ports", _), Yaml.Node.Sequence(ports, _)) =>
                                            ports.size == 1 && (ports(0) match
                                                case Yaml.Node.Scalar("8080", _) => true
                                                case _                           => false)
                                        case _ =>
                                            false)
                                case other =>
                                    fail(s"Expected api mapping, got $other")
                            end match
                        case other =>
                            fail(s"Expected services mapping, got $other")
                    end match
                case other =>
                    fail(s"Expected rendered YAML to parse, got $other")
            end match
        }

        "replaces sequence element while preserving leading item comment" in {
            val yaml = """items:
                         |  # keep
                         |  - old
                         |  - next
                         |""".stripMargin
            val edited =
                Yaml.cst(yaml).getOrThrow.replace(Yaml.Cst.Path.root / "items" / 0, scalar("new")).getOrThrow
            val rendered = edited.render(using Yaml.WriterConfig.Default)
            val expected = """items:
                             |  # keep
                             |  - new
                             |  - next
                             |""".stripMargin

            assertResult(
                (
                    sourceCleared = true,
                    rendered = expected,
                    decoded = Result.succeed(Map("items" -> List("new", "next")))
                )
            ) {
                (
                    sourceCleared = edited.source.isEmpty,
                    rendered = rendered,
                    decoded = Yaml.decode[Map[String, List[String]]](rendered)
                )
            }
        }

        "replaces mapping value while preserving trailing field comment" in {
            val yaml = """services:
                         |  api:
                         |    image: app:v1 # image trailing
                         |""".stripMargin
            val edited =
                Yaml.cst(yaml).getOrThrow.replace(Yaml.Cst.Path.root / "services" / "api" / "image", scalar("app:v2")).getOrThrow
            val rendered = edited.render(using Yaml.WriterConfig.Default)
            val expected = """services:
                             |  api:
                             |    image: app:v2 # image trailing
                             |""".stripMargin

            assertResult(
                (
                    sourceCleared = true,
                    rendered = expected,
                    decoded = Result.succeed(Map("services" -> Map("api" -> Map("image" -> "app:v2"))))
                )
            ) {
                (
                    sourceCleared = edited.source.isEmpty,
                    rendered = rendered,
                    decoded = Yaml.decode[Map[String, Map[String, Map[String, String]]]](rendered)
                )
            }
        }

        "replaces sequence element while preserving trailing item comment" in {
            val yaml = """items:
                         |  - old # item trailing
                         |  - next
                         |""".stripMargin
            val edited =
                Yaml.cst(yaml).getOrThrow.replace(Yaml.Cst.Path.root / "items" / 0, scalar("new")).getOrThrow
            val rendered = edited.render(using Yaml.WriterConfig.Default)
            val expected = """items:
                             |  - new # item trailing
                             |  - next
                             |""".stripMargin

            assertResult(
                (
                    sourceCleared = true,
                    rendered = expected,
                    decoded = Result.succeed(Map("items" -> List("new", "next")))
                )
            ) {
                (
                    sourceCleared = edited.source.isEmpty,
                    rendered = rendered,
                    decoded = Yaml.decode[Map[String, List[String]]](rendered)
                )
            }
        }

        "preserves scalar anchors and aliases when rendering changed documents with comments" in {
            val yaml = """value: &name Alice # keep
                         |other: old
                         |copy: *name
                         |""".stripMargin
            val edited =
                Yaml.cst(yaml).getOrThrow.replace(Yaml.Cst.Path.root / "other", scalar("new")).getOrThrow
            val rendered = edited.render(using Yaml.WriterConfig.Default)

            assertResult(
                (
                    anchorKept = true,
                    aliasKept = true,
                    decoded = Result.succeed(Map("value" -> "Alice", "other" -> "new", "copy" -> "Alice"))
                )
            ) {
                (
                    anchorKept = rendered.contains("value: &name Alice # keep"),
                    aliasKept = rendered.contains("copy: *name"),
                    decoded = Yaml.decode[Map[String, String]](rendered)
                )
            }
        }

        "preserves scalar tags when rendering changed documents with comments" in {
            val yaml = """value: !!str true # keep
                         |other: old
                         |""".stripMargin
            val edited =
                Yaml.cst(yaml).getOrThrow.replace(Yaml.Cst.Path.root / "other", scalar("new")).getOrThrow
            val rendered = edited.render(using Yaml.WriterConfig.Default)

            assertResult(
                (
                    tagKept = true,
                    decoded = Result.succeed(Map("value" -> "true", "other" -> "new"))
                )
            ) {
                (
                    tagKept = rendered.contains("value: !!str true # keep"),
                    decoded = Yaml.decode[Map[String, String]](rendered)
                )
            }
        }

        "escapes control characters when rendering changed documents with comments" in {
            val yaml        = "value: old # keep\n"
            val replacement = scalar("a" + 1.toChar + "b")
            val edited =
                Yaml.cst(yaml).getOrThrow.replace(Yaml.Cst.Path.root / "value", replacement).getOrThrow
            val rendered = edited.render(using Yaml.WriterConfig.Default)

            assertResult(
                (
                    escaped = true,
                    decoded = Result.succeed(Map("value" -> ("a" + 1.toChar + "b")))
                )
            ) {
                (
                    escaped = rendered.contains("\\u0001"),
                    decoded = Yaml.decode[Map[String, String]](rendered)
                )
            }
        }

        "respects disabled trailing newline when rendering changed documents with comments" in {
            val yaml = "# keep\nvalue: old\n"
            val edited =
                Yaml.cst(yaml).getOrThrow.replace(Yaml.Cst.Path.root / "value", scalar("new")).getOrThrow
            val rendered = edited.render(using Yaml.WriterConfig.Default.copy(trailingNewline = false))

            assertResult(
                (
                    noTrailingNewline = true,
                    rendered = "# keep\nvalue: new"
                )
            ) {
                (
                    noTrailingNewline = !rendered.endsWith("\n"),
                    rendered = rendered
                )
            }
        }

        "preserves mapping anchors and aliases when rendering changed documents with comments" in {
            val yaml = """value: &map
                         |  a: 1 # keep
                         |copy: *map
                         |""".stripMargin
            val edited =
                Yaml.cst(yaml).getOrThrow.replace(Yaml.Cst.Path.root / "value" / "a", scalar("2")).getOrThrow
            val rendered = edited.render(using Yaml.WriterConfig.Default)

            assertResult(
                (
                    anchorKept = true,
                    valueKept = true,
                    aliasKept = true,
                    decoded = Result.succeed(Map("value" -> Map("a" -> "2"), "copy" -> Map("a" -> "2")))
                )
            ) {
                (
                    anchorKept = rendered.contains("value: &map"),
                    valueKept = rendered.contains("a: 2 # keep"),
                    aliasKept = rendered.contains("copy: *map"),
                    decoded = Yaml.decode[Map[String, Map[String, String]]](rendered)
                )
            }
        }

        "preserves sequence anchors and aliases when rendering changed documents with comments" in {
            val yaml = """items: &items
                         |  - old # keep
                         |copy: *items
                         |""".stripMargin
            val edited =
                Yaml.cst(yaml).getOrThrow.replace(Yaml.Cst.Path.root / "items" / 0, scalar("new")).getOrThrow
            val rendered = edited.render(using Yaml.WriterConfig.Default)

            assertResult(
                (
                    anchorKept = true,
                    itemKept = true,
                    aliasKept = true,
                    decoded = Result.succeed(Map("items" -> List("new"), "copy" -> List("new")))
                )
            ) {
                (
                    anchorKept = rendered.contains("items: &items"),
                    itemKept = rendered.contains("- new # keep"),
                    aliasKept = rendered.contains("copy: *items"),
                    decoded = Yaml.decode[Map[String, List[String]]](rendered)
                )
            }
        }

        "preserves collection tags when rendering changed documents with comments" in {
            val yaml = """value: !!map
                         |  a: 1 # keep
                         |""".stripMargin
            val edited =
                Yaml.cst(yaml).getOrThrow.replace(Yaml.Cst.Path.root / "value" / "a", scalar("2")).getOrThrow
            val rendered = edited.render(using Yaml.WriterConfig.Default)

            assertResult((tagKept = true, valueKept = true)) {
                (
                    tagKept = rendered.contains("value: !!map"),
                    valueKept = rendered.contains("a: 2 # keep")
                )
            }
        }

        "inserts, removes, and renames mapping entries at root" in {
            val base =
                Yaml.cst("name: Alice\nactive: true\n").getOrThrow
            val edited =
                base
                    .insert(Yaml.Cst.Path.root / "age", scalar("30"))
                    .getOrThrow
                    .insert(Yaml.Cst.Path.root / "displayName", scalar("Alice"))
                    .getOrThrow
                    .remove(Yaml.Cst.Path.root / "name")
                    .getOrThrow
                    .remove(Yaml.Cst.Path.root / "active")
                    .getOrThrow
            val rendered = edited.render(using Yaml.WriterConfig.Default)

            assertResult(
                (
                    sourceCleared = true,
                    decoded = Result.succeed(Map("age" -> "30", "displayName" -> "Alice"))
                )
            ) {
                (
                    sourceCleared = edited.source.isEmpty,
                    decoded = Yaml.decode[Map[String, String]](rendered)
                )
            }
        }

        "inserts and removes sequence elements under a mapping key" in {
            val base =
                Yaml.cst("ports:\n- 8080\n- 8082\n").getOrThrow
            val edited =
                base
                    .insert(Yaml.Cst.Path.root / "ports" / 1, scalar("8081"))
                    .getOrThrow
                    .remove(Yaml.Cst.Path.root / "ports" / 0)
                    .getOrThrow
            val rendered = edited.render(using Yaml.WriterConfig.Default)

            assertResult(
                (
                    sourceCleared = true,
                    decoded = Result.succeed(Map("ports" -> List(8081, 8082)))
                )
            ) {
                (
                    sourceCleared = edited.source.isEmpty,
                    decoded = Yaml.decode[Map[String, List[Int]]](rendered)
                )
            }
        }

        "renders complex collection keys as valid flow in the trivia path" in {
            val mark = Yaml.Mark(0, 1, 1)
            val span = Yaml.Cst.SourceSpan(mark, mark)
            val seqKey =
                Yaml.Cst.Node.Sequence(
                    Chunk(
                        Yaml.Cst.SequenceEntry(scalar("a"), span),
                        Yaml.Cst.SequenceEntry(scalar("b"), span)
                    ),
                    Yaml.Cst.SequenceSyntax.Canonical,
                    Yaml.Meta(Absent, Absent, mark),
                    span,
                    Absent
                )
            val entry =
                Yaml.Cst.MappingEntry(seqKey, scalar("value"), span, Chunk(Yaml.Cst.Trivia("# keep", span)))
            val root =
                Yaml.Cst.Node.Mapping(Chunk(entry), Yaml.Cst.MappingSyntax.Canonical, Yaml.Meta(Absent, Absent, mark), span, Absent)
            val doc      = Yaml.Cst.Document(Maybe(root), Chunk.empty, Chunk.empty, span, Absent)
            val rendered = doc.render(using Yaml.WriterConfig.Default)

            assertResult((noToString = true, flowKey = true, roundTrips = true)) {
                (
                    noToString = !rendered.contains("Sequence("),
                    flowKey = rendered.contains("[a, b]: value"),
                    roundTrips = Yaml.cst(rendered).isSuccess
                )
            }
        }

        "fails editing through an ambiguous duplicate mapping key" in {
            val mark = Yaml.Mark(0, 1, 1)
            val span = Yaml.Cst.SourceSpan(mark, mark)
            val duplicate =
                Yaml.Cst.Document(
                    Maybe(mapping("name" -> scalar("a"), "name" -> scalar("b"))),
                    Chunk.empty,
                    Chunk.empty,
                    span,
                    Absent
                )

            duplicate.replace(Yaml.Cst.Path.root / "name", scalar("c")) match
                case Result.Failure(e: Yaml.Cst.EditException) =>
                    assertResult((message = true, path = "name")) {
                        (message = e.getMessage.contains("Ambiguous mapping key 'name'"), path = e.path.show)
                    }
                case other =>
                    fail(s"Expected ambiguity failure, got $other")
            end match
        }

        "fails inserting a mapping key that already exists" in {
            Yaml.cst("name: Alice\n").getOrThrow.insert(Yaml.Cst.Path.root / "name", scalar("Bob")) match
                case Result.Failure(e: Yaml.Cst.EditException) =>
                    assertResult((message = true, path = "name")) {
                        (message = e.getMessage.contains("already exists"), path = e.path.show)
                    }
                case other =>
                    fail(s"Expected collision failure, got $other")
            end match
        }

        "fails removing a missing path with the concrete path" in {
            val result =
                Yaml.cst("name: Alice\n").getOrThrow.remove(Yaml.Cst.Path.root / "missing")

            result match
                case Result.Failure(e: Yaml.Cst.EditException) =>
                    assert(e.path.show == "missing")
                case other =>
                    fail(s"Expected EditException failure, got $other")
            end match
        }

        "replaces root and renders the new node" in {
            val base = Yaml.cst("name: Alice\n").getOrThrow
            val edited =
                base.replace(Yaml.Cst.Path.root, mapping("name" -> scalar("Bob"))).getOrThrow
            val rendered = edited.render(using Yaml.WriterConfig.Default)

            assertResult(
                (
                    sourceCleared = true,
                    rendered = "name: Bob\n",
                    decoded = Result.succeed(Map("name" -> "Bob"))
                )
            ) {
                (
                    sourceCleared = edited.source.isEmpty,
                    rendered = rendered,
                    decoded = Yaml.decode[Map[String, String]](rendered)
                )
            }
        }

        "preserves literal block scalars when rendering changed documents with comments" in {
            val yaml = """# config
                         |name: app
                         |message: |-
                         |  hello
                         |  world
                         |""".stripMargin
            val edited =
                Yaml.cst(yaml).getOrThrow.replace(Yaml.Cst.Path.root / "name", scalar("app2")).getOrThrow
            val rendered = edited.render(using Yaml.WriterConfig.Default)

            assertResult(
                (
                    sourceCleared = true,
                    comment = true,
                    blockHeader = true,
                    content = true,
                    decoded = Result.succeed(Map("name" -> "app2", "message" -> "hello\nworld"))
                )
            ) {
                (
                    sourceCleared = edited.source.isEmpty,
                    comment = rendered.contains("# config"),
                    blockHeader = rendered.contains("message: |-"),
                    content = rendered.contains("\n  hello\n  world"),
                    decoded = Yaml.decode[Map[String, String]](rendered)
                )
            }
        }

        "preserves folded block scalars when rendering changed documents with comments" in {
            val yaml = """# config
                         |name: app
                         |message: >-
                         |  hello
                         |  world
                         |""".stripMargin
            val edited =
                Yaml.cst(yaml).getOrThrow.replace(Yaml.Cst.Path.root / "name", scalar("app2")).getOrThrow
            val rendered = edited.render(using Yaml.WriterConfig.Default)

            assertResult(
                (
                    sourceCleared = true,
                    blockHeader = true,
                    decoded = Result.succeed(Map("name" -> "app2", "message" -> "hello world"))
                )
            ) {
                (
                    sourceCleared = edited.source.isEmpty,
                    blockHeader = rendered.contains("message: >-"),
                    decoded = Yaml.decode[Map[String, String]](rendered)
                )
            }
        }
    }
end YamlCstTest
