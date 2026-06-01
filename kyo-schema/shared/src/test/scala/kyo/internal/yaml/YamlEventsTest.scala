package kyo.internal.yaml

import kyo.*

class YamlEventsTest extends kyo.test.Test[Any]:

    import YamlEventsTest.*

    given CanEqual[Any, Any] = CanEqual.derived

    "YamlEvents" - {

        "allows events to be transformed before reaching a terminal collector" in {
            val events = Chunk(
                Yaml.Events.Event.StreamStart(mark),
                Yaml.Events.Event.DocumentStart(mark),
                Yaml.Events.Event.MappingStart(Yaml.Meta(Absent, Absent, mark)),
                Yaml.Events.Event.Scalar("name", Yaml.ScalarMeta(Absent, Absent, Yaml.ScalarStyle.Plain, mark)),
                Yaml.Events.Event.Scalar("alice", Yaml.ScalarMeta(Absent, Absent, Yaml.ScalarStyle.Plain, mark)),
                Yaml.Events.Event.CollectionEnd(Yaml.Events.CollectionKind.Mapping, mark),
                Yaml.Events.Event.DocumentEnd(mark),
                Yaml.Events.Event.StreamEnd(mark)
            )

            val collector = new Yaml.Events.EventHandler[Chunk[String], Nothing]:
                override def event(context: Chunk[String], event: Yaml.Events.Event): Result[Nothing, Chunk[String]] =
                    Result.succeed(context :+ label(event))
            end collector

            val uppercaseScalars =
                Yaml.Events.Processor.mapScalars[Nothing]((value, meta) => Result.succeed((value.toUpperCase, meta)))

            val result = runEvents(events, Chunk.empty, uppercaseScalars.andThen(collector))

            assert(
                result == Result.succeed(Chunk(
                    "streamStart",
                    "documentStart",
                    "mappingStart::",
                    "scalar:NAME:Plain",
                    "scalar:ALICE:Plain",
                    "collectionEnd:Mapping",
                    "documentEnd",
                    "streamEnd"
                ))
            )
        }

        "lets the parser drive internal events without exposing a public API" in {
            val collector = new Yaml.Events.EventHandler[Chunk[String], Nothing]:
                override def event(context: Chunk[String], event: Yaml.Events.Event): Result[Nothing, Chunk[String]] =
                    Result.succeed(context :+ label(event))
            end collector

            val yaml =
                """name: Alice
                  |items:
                  |  - one
                  |  - two
                  |again: *name
                  |""".stripMargin

            val result = YamlParser(yaml).visitEvents(Chunk.empty)(collector)

            assert(
                result == Result.succeed(Chunk(
                    "streamStart",
                    "documentStart",
                    "mappingStart::",
                    "scalar:name:Plain",
                    "scalar:Alice:Plain",
                    "scalar:items:Plain",
                    "sequenceStart::",
                    "scalar:one:Plain",
                    "scalar:two:Plain",
                    "collectionEnd:Sequence",
                    "scalar:again:Plain",
                    "alias:name",
                    "collectionEnd:Mapping",
                    "documentEnd",
                    "streamEnd"
                ))
            )
        }

        "emits folded multiline quoted scalar values as parser events" in {
            val yaml =
                """single: 'line one
                  |  line two
                  |
                  |  line three'
                  |double: "line one
                  |  line two\nline three"
                  |""".stripMargin

            assertResult(
                Result.succeed(Chunk(
                    ("single", Yaml.ScalarStyle.Plain),
                    ("line one line two\nline three", Yaml.ScalarStyle.SingleQuoted),
                    ("double", Yaml.ScalarStyle.Plain),
                    ("line one line two\nline three", Yaml.ScalarStyle.DoubleQuoted)
                ))
            )(collectScalarEvents(yaml))
        }

        "emits inferred and explicit block scalar values as parser events" in {
            val yaml =
                """literal: |-
                  |    first
                  |      deeper
                  |folded: >2
                  |  one
                  |  two
                  |
                  |  three
                  |""".stripMargin

            assertResult(
                Result.succeed(Chunk(
                    ("literal", Yaml.ScalarStyle.Plain),
                    ("first\n  deeper", Yaml.ScalarStyle.Literal),
                    ("folded", Yaml.ScalarStyle.Plain),
                    ("one two\nthree\n", Yaml.ScalarStyle.Folded)
                ))
            )(collectScalarEvents(yaml))
        }

        "emits compact sequence mappings with continuation fields as nested parser events" in {
            val yaml =
                """steps:
                  |  - name: Build
                  |    run: sbt test
                  |  - name: Deploy
                  |    env:
                  |      REGION: us
                  |""".stripMargin

            assertResult(
                Result.succeed(Chunk(
                    "streamStart",
                    "documentStart",
                    "mappingStart::",
                    "scalar:steps:Plain",
                    "sequenceStart::",
                    "mappingStart::",
                    "scalar:name:Plain",
                    "scalar:Build:Plain",
                    "scalar:run:Plain",
                    "scalar:sbt test:Plain",
                    "collectionEnd:Mapping",
                    "mappingStart::",
                    "scalar:name:Plain",
                    "scalar:Deploy:Plain",
                    "scalar:env:Plain",
                    "mappingStart::",
                    "scalar:REGION:Plain",
                    "scalar:us:Plain",
                    "collectionEnd:Mapping",
                    "collectionEnd:Mapping",
                    "collectionEnd:Sequence",
                    "collectionEnd:Mapping",
                    "documentEnd",
                    "streamEnd"
                ))
            )(collectEventLabels(yaml))
        }

        "emits directives explicit document starts and empty documents as parser events" in {
            val yaml =
                """%YAML 1.2
                  |--- # explicit empty document
                  |""".stripMargin

            assertResult(
                Result.succeed(Chunk(
                    "streamStart",
                    "documentStart",
                    "scalar::Plain",
                    "documentEnd",
                    "streamEnd"
                ))
            )(collectEventLabels(yaml))
        }

        "emits anchors and tags on inline collection parser events" in {
            val yaml =
                """items: !!seq &items [one, two]
                  |settings: &settings !!map { mode: "fast", retries: 3 }
                  |copy: *items
                  |""".stripMargin

            assertResult(
                Result.succeed(Chunk(
                    "streamStart",
                    "documentStart",
                    "mappingStart::",
                    "scalar:items:Plain",
                    "sequenceStart:items:!!seq",
                    "scalar:one:Plain",
                    "scalar:two:Plain",
                    "collectionEnd:Sequence",
                    "scalar:settings:Plain",
                    "mappingStart:settings:!!map",
                    "scalar:mode:Plain",
                    "scalar:fast:DoubleQuoted",
                    "scalar:retries:Plain",
                    "scalar:3:Plain",
                    "collectionEnd:Mapping",
                    "scalar:copy:Plain",
                    "alias:items",
                    "collectionEnd:Mapping",
                    "documentEnd",
                    "streamEnd"
                ))
            )(collectEventLabels(yaml))
        }

        "emits empty mapping values and indentless sequence values as parser events" in {
            val yaml =
                """empty:
                  |items:
                  |- one
                  |- two
                  |""".stripMargin

            assertResult(
                Result.succeed(Chunk(
                    "streamStart",
                    "documentStart",
                    "mappingStart::",
                    "scalar:empty:Plain",
                    "scalar::Plain",
                    "scalar:items:Plain",
                    "sequenceStart::",
                    "scalar:one:Plain",
                    "scalar:two:Plain",
                    "collectionEnd:Sequence",
                    "collectionEnd:Mapping",
                    "documentEnd",
                    "streamEnd"
                ))
            )(collectEventLabels(yaml))
        }

        "emits block scalar sequence entries with scalar metadata" in {
            val yaml =
                """notes:
                  |  - &intro |-
                  |    hello
                  |      code
                  |  - !summary >+
                  |    folded
                  |    text
                  |
                  |""".stripMargin

            assertResult(
                Result.succeed(Chunk(
                    ("notes", Yaml.ScalarStyle.Plain, "", ""),
                    ("hello\n  code", Yaml.ScalarStyle.Literal, "intro", ""),
                    ("folded text\n\n", Yaml.ScalarStyle.Folded, "", "!summary")
                ))
            )(collectScalarDetails(yaml))
        }

        "emits flow mapping entries with URL-like keys empty values and quoted JSON-style keys" in {
            val yaml =
                """flow: {
                  |  url: https://example.com/a:b,
                  |  literal: "not } done, still quoted",
                  |  "json":1,
                  |  https://example.com,
                  |  present:
                  |}
                  |""".stripMargin

            assertResult(
                Result.succeed(Chunk(
                    "streamStart",
                    "documentStart",
                    "mappingStart::",
                    "scalar:flow:Plain",
                    "mappingStart::",
                    "scalar:url:Plain",
                    "scalar:https://example.com/a:b:Plain",
                    "scalar:literal:Plain",
                    "scalar:not } done, still quoted:DoubleQuoted",
                    "scalar:json:Plain",
                    "scalar:1:Plain",
                    "scalar:https://example.com:Plain",
                    "scalar::Plain",
                    "scalar:present:Plain",
                    "scalar::Plain",
                    "collectionEnd:Mapping",
                    "collectionEnd:Mapping",
                    "documentEnd",
                    "streamEnd"
                ))
            )(collectEventLabels(yaml))
        }

        "decodes YAML double quoted escape repertoire through parser events" in {
            val yaml =
                """escaped: "\0\a\b\t\n\v\f\r\e \"\/\\\N\_\L\P\x41\u263A\U0001F600"
                  |""".stripMargin

            assertResult(
                Result.succeed(Chunk(
                    ("escaped", Yaml.ScalarStyle.Plain),
                    ("\u0000\u0007\b\t\n\u000b\f\r\u001b \"/\\\u0085\u00a0\u2028\u2029A\u263a\uD83D\uDE00", Yaml.ScalarStyle.DoubleQuoted)
                ))
            )(collectScalarEvents(yaml))
        }

        "reports invalid double quoted parser escapes with source context" in {
            val yaml =
                """bad: "\q"
                  |""".stripMargin

            val observed = collectEventLabels(yaml) match
                case Result.Failure(e: ParseException) =>
                    (invalidEscape = e.getMessage.contains("Invalid escape sequence \\q"), hasLocation = e.getMessage.contains("line 1"))
                case other =>
                    fail(s"Expected ParseException failure, got $other")

            assertResult((invalidEscape = true, hasLocation = true))(observed)
        }

        "lets parser events render empty collections without a node tree" in {
            val renderer = YamlEvents.Renderer(Yaml.WriterConfig.Default)
            val yaml =
                """items: []
                  |labels: {}
                  |""".stripMargin

            val result = YamlParser(yaml).visitEvents(())(renderer).map(_ => renderer.resultString)

            assert(
                result == Result.succeed(
                    """items: []
                      |labels: {}
                      |""".stripMargin
                )
            )
        }

        "lets parser events be transformed before rendering without a node tree" in {
            val renderer = YamlEvents.Renderer(Yaml.WriterConfig.Default)
            val uppercaseScalars =
                Yaml.Events.Processor.mapScalars[DecodeException]((value, meta) => Result.succeed((value.toUpperCase, meta)))
            val yaml =
                """name: Alice
                  |city: Paris
                  |""".stripMargin

            val result = YamlParser(yaml).visitEvents(())(uppercaseScalars.andThen(renderer)).map(_ => renderer.resultString)

            assert(
                result == Result.succeed(
                    """NAME: ALICE
                      |CITY: PARIS
                      |""".stripMargin
                )
            )
        }

        "renders synthetic events as YAML without a node tree" in {
            val nameAnchor = Yaml.Anchor("name")
            val renderer   = YamlEvents.Renderer(Yaml.WriterConfig.Default)
            val events = Chunk(
                Yaml.Events.Event.StreamStart(mark),
                Yaml.Events.Event.DocumentStart(mark),
                Yaml.Events.Event.MappingStart(Yaml.Meta(Absent, Absent, mark)),
                Yaml.Events.Event.Scalar("name", scalarMeta),
                Yaml.Events.Event.Scalar("Alice", scalarMeta.copy(anchor = Maybe(nameAnchor))),
                Yaml.Events.Event.Scalar("items", scalarMeta),
                Yaml.Events.Event.SequenceStart(Yaml.Meta(Absent, Absent, mark)),
                Yaml.Events.Event.Scalar("one", scalarMeta),
                Yaml.Events.Event.Alias(nameAnchor, mark),
                Yaml.Events.Event.CollectionEnd(Yaml.Events.CollectionKind.Sequence, mark),
                Yaml.Events.Event.CollectionEnd(Yaml.Events.CollectionKind.Mapping, mark),
                Yaml.Events.Event.DocumentEnd(mark),
                Yaml.Events.Event.StreamEnd(mark)
            )

            val result = runEvents(events, (), renderer).map(_ => renderer.resultString)

            assert(
                result == Result.succeed(
                    """name: &name Alice
                      |items:
                      |  - one
                      |  - *name
                      |""".stripMargin
                )
            )
        }

        "renders properties on explicit empty collection events" in {
            val renderer = YamlEvents.Renderer(Yaml.WriterConfig.Default)
            val events = Chunk(
                Yaml.Events.Event.StreamStart(mark),
                Yaml.Events.Event.DocumentStart(mark),
                Yaml.Events.Event.MappingStart(Yaml.Meta(Absent, Absent, mark)),
                Yaml.Events.Event.Scalar("items", scalarMeta),
                Yaml.Events.Event.SequenceStart(
                    Yaml.Meta(Maybe(Yaml.Anchor("items")), Maybe(Yaml.YamlTag("!!seq")), mark),
                    size = Maybe(0)
                ),
                Yaml.Events.Event.CollectionEnd(Yaml.Events.CollectionKind.Sequence, mark),
                Yaml.Events.Event.CollectionEnd(Yaml.Events.CollectionKind.Mapping, mark),
                Yaml.Events.Event.DocumentEnd(mark),
                Yaml.Events.Event.StreamEnd(mark)
            )

            val result = runEvents(events, (), renderer).map(_ => renderer.resultString)

            assert(result == Result.succeed("items: !!seq &items []\n"))
        }

        "renders properties on non-empty collection events" in {
            val renderer = YamlEvents.Renderer(Yaml.WriterConfig.Default)
            val events = Chunk(
                Yaml.Events.Event.StreamStart(mark),
                Yaml.Events.Event.DocumentStart(mark),
                Yaml.Events.Event.MappingStart(Yaml.Meta(Absent, Absent, mark)),
                Yaml.Events.Event.Scalar("items", scalarMeta),
                Yaml.Events.Event.SequenceStart(Yaml.Meta(Maybe(Yaml.Anchor("items")), Maybe(Yaml.YamlTag("!!seq")), mark)),
                Yaml.Events.Event.Scalar("one", scalarMeta),
                Yaml.Events.Event.CollectionEnd(Yaml.Events.CollectionKind.Sequence, mark),
                Yaml.Events.Event.CollectionEnd(Yaml.Events.CollectionKind.Mapping, mark),
                Yaml.Events.Event.DocumentEnd(mark),
                Yaml.Events.Event.StreamEnd(mark)
            )

            val result = runEvents(events, (), renderer).map(_ => renderer.resultString)

            assert(
                result == Result.succeed(
                    """items: !!seq &items
                      |  - one
                      |""".stripMargin
                )
            )
        }

        "can be driven by schema encoding through a Codec writer facade" in {
            val writer = YamlEvents.Writer(Yaml.WriterConfig.Default)

            Schema[MTPerson].writeTo(MTPerson("Alice", 30), writer)

            assert(
                writer.resultString ==
                    """name: Alice
                      |age: 30
                    |""".stripMargin
            )
        }

        "matches production YAML writer for nested products sequences and maps" in {
            val value = YamlWriterNested(
                MTSmallTeam(MTPerson("Alice", 30), 5),
                List(MTPerson("Alice", 30), MTPerson("Bob", 25)),
                Map("env" -> "prod", "feature:flag" -> "on"),
                YamlValueHolder(
                    YamlOpaqueUserId("user-123"),
                    YamlAnyValUserId("user-456"),
                    YamlValueObject("user-789")
                )
            )
            val writer = YamlEvents.Writer(Yaml.WriterConfig.Default)

            summon[Schema[YamlWriterNested]].writeTo(value, writer)

            assert(
                writer.resultString ==
                    """team:
                      |  lead:
                      |    name: Alice
                      |    age: 30
                      |  size: 5
                      |people:
                      |  -
                      |    name: Alice
                      |    age: 30
                      |  -
                      |    name: Bob
                      |    age: 25
                      |labels:
                      |  env: prod
                      |  "feature:flag": on
                      |wrappers:
                      |  opaqueId: user-123
                      |  anyValId:
                      |    value: user-456
                      |  valueObject:
                      |    value: user-789
                    |""".stripMargin
            )
        }

        "matches production YAML writer for ambiguous and multiline strings" in {
            val value = YamlWriterStrings(
                plain = "Alice",
                truthy = "true",
                comment = "Alice #1",
                url = "https://example.com",
                multiline = "line one\nline two\n",
                empty = ""
            )
            val writer = YamlEvents.Writer(Yaml.WriterConfig.Default)

            summon[Schema[YamlWriterStrings]].writeTo(value, writer)

            assertResult(
                (
                    resultString =
                        """plain: Alice
                          |truthy: "true"
                          |comment: "Alice #1"
                          |url: "https://example.com"
                          |multiline: |
                          |  line one
                          |  line two
                          |empty: ""
                          |""".stripMargin,
                    controlChar = "\"\\u0001\"\n"
                )
            ) {
                (
                    resultString = writer.resultString,
                    controlChar = write("\u0001")
                )
            }
        }

        "matches production YAML writer for empty collections and scalar roots" in {
            assertResult(
                (
                    emptyList = "[]\n",
                    emptyMap = "{}\n",
                    intList = "- 1\n- 2\n- 3\n",
                    trueStr = "\"true\"\n",
                    hex = "\"0x3A\"\n",
                    octal = "\"0o7\"\n",
                    dotFive = "\".5\"\n",
                    expNum = "\"+12e03\"\n",
                    infStr = "\".inf\"\n"
                )
            ) {
                (
                    emptyList = write(List.empty[Int]),
                    emptyMap = write(Map.empty[String, Int]),
                    intList = write(List(1, 2, 3)),
                    trueStr = write("true"),
                    hex = write("0x3A"),
                    octal = write("0o7"),
                    dotFive = write(".5"),
                    expNum = write("+12e03"),
                    infStr = write(".inf")
                )
            }
        }

        "honors compact sequence mapping layout from writer config" in {
            val writer = YamlEvents.Writer(Yaml.WriterConfig.Compact)

            summon[Schema[List[MTPerson]]].writeTo(List(MTPerson("Alice", 30)), writer)

            assert(writer.resultString == "- name: Alice\n  age: 30\n")
        }

        "honors small and fast flow writer profiles" in {
            val value = MTPerson("Alice", 30)

            assertResult(
                (
                    smallPerson = "{name: Alice, age: 30}",
                    fastPerson = "{\"name\":\"Alice\",\"age\":30}",
                    smallScalar = "Alice",
                    smallEmptyList = "[]",
                    fastEmptyMap = "{}",
                    flowMultiline = "[\"line one\\nline two\\n\"]"
                )
            ) {
                (
                    smallPerson = write(value, Yaml.WriterConfig.Small),
                    fastPerson = write(value, Yaml.WriterConfig.Fast),
                    smallScalar = write("Alice", Yaml.WriterConfig.Small),
                    smallEmptyList = write(List.empty[Int], Yaml.WriterConfig.Small),
                    fastEmptyMap = write(Map.empty[String, Int], Yaml.WriterConfig.Fast),
                    flowMultiline = write(
                        List("line one\nline two\n"),
                        Yaml.WriterConfig.Readable.copy(collectionStyle = Yaml.WriterConfig.CollectionStyle.Flow)
                    )
                )
            }
        }

        "matches production YAML writer for finite and special floating point values" in {
            assertResult(
                (
                    bigExp = "1.23E100\n",
                    minPositive = "5.0E-324\n",
                    floatMax = "3.4028235E38\n",
                    doubleSpecials = "- .nan\n- .inf\n- -.inf\n",
                    floatSpecials = """[!!float ".nan",!!float ".inf",!!float "-.inf"]"""
                )
            ) {
                (
                    bigExp = write(1.23e100d),
                    minPositive = write(Double.MinPositiveValue),
                    floatMax = write(Float.MaxValue),
                    doubleSpecials = write(List(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity)),
                    floatSpecials = write(List(Float.NaN, Float.PositiveInfinity, Float.NegativeInfinity), Yaml.WriterConfig.Fast)
                )
            }
        }

        "honors scalar quoting folded multiline and YAML version config" in {
            val minimal      = Yaml.WriterConfig.Readable.copy(scalarQuoting = Yaml.WriterConfig.ScalarQuoting.WhenNeeded)
            val singleQuoted = Yaml.WriterConfig.Readable.copy(quoteStyle = Yaml.WriterConfig.QuoteStyle.Single)
            val legacy       = Yaml.WriterConfig.Readable.copy(yamlVersion = Yaml.SpecVersion.Yaml11)
            val folded       = Yaml.WriterConfig.Readable.copy(multilineStyle = Yaml.WriterConfig.MultilineStyle.Folded)

            assertResult(
                (
                    minimalTrue = "true\n",
                    defaultTrue = "\"true\"\n",
                    singleQuoted = "'Alice #1'\n",
                    plainNo = "NO\n",
                    legacyNo = "\"NO\"\n",
                    legacyOctal = "\"010\"\n",
                    foldedMultiline = ">\n  line one\n\n  line two\n"
                )
            ) {
                (
                    minimalTrue = write("true", minimal),
                    defaultTrue = write("true"),
                    singleQuoted = write("Alice #1", singleQuoted),
                    plainNo = write("NO"),
                    legacyNo = write("NO", legacy),
                    legacyOctal = write("010", legacy),
                    foldedMultiline = write("line one\nline two\n", folded)
                )
            }
        }

        "honors document marker config" in {
            val start = Yaml.WriterConfig.Readable.copy(documentMarkers = Yaml.WriterConfig.DocumentMarkers.Start)
            val both  = Yaml.WriterConfig.Readable.copy(documentMarkers = Yaml.WriterConfig.DocumentMarkers.StartAndEnd)

            assertResult(
                (
                    startMarker = "---\nname: Alice\nage: 30\n",
                    bothMarkers = "---\nname: Alice\nage: 30\n...\n"
                )
            ) {
                (
                    startMarker = write(MTPerson("Alice", 30), start),
                    bothMarkers = write(MTPerson("Alice", 30), both)
                )
            }
        }

        "matches production YAML writer direct scalar methods" in {
            assertResult(
                (
                    bytes = "\"AQID\"\n",
                    bigInt = "\"12345678901234567890\"\n",
                    bigDecimal = "\"1234567890.0987654321\"\n",
                    instant = "\"2026-05-30T12:34:56Z\"\n",
                    duration = "\"PT1H2M3S\"\n"
                )
            ) {
                (
                    bytes = direct(_.bytes(Span.fromUnsafe(Array[Byte](1, 2, 3)))),
                    bigInt = direct(_.bigInt(BigInt("12345678901234567890"))),
                    bigDecimal = direct(_.bigDecimal(BigDecimal("1234567890.0987654321"))),
                    instant = direct(_.instant(java.time.Instant.parse("2026-05-30T12:34:56Z"))),
                    duration = direct(_.duration(java.time.Duration.parse("PT1H2M3S")))
                )
            }
        }

        "releases writer state after materializing results" in {
            val stringWriter = YamlEvents.Writer(Yaml.WriterConfig.Default)
            stringWriter.string("one")
            assert(stringWriter.resultString == "one\n")
            stringWriter.string("two")
            assert(stringWriter.resultString == "two\n")

            val byteWriter = YamlEvents.Writer(Yaml.WriterConfig.Default)
            byteWriter.string("one")
            assert(new String(byteWriter.result().toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8) == "one\n")
            byteWriter.string("two")
            assert(new String(byteWriter.result().toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8) == "two\n")
        }
    }
end YamlEventsTest

private object YamlEventsTest:

    val mark: Yaml.Mark = Yaml.Mark(0, 1, 1)

    val scalarMeta: Yaml.ScalarMeta =
        Yaml.ScalarMeta(Absent, Absent, Yaml.ScalarStyle.Plain, mark)

    def runEvents[Ctx, Err](
        events: Chunk[Yaml.Events.Event],
        context: Ctx,
        handler: Yaml.Events.Handler[Ctx, Err]
    ): Result[Err, Ctx] =
        events.foldLeft(Result.succeed(context)) { (result, event) =>
            result.flatMap(handler.event(_, event))
        }
    end runEvents

    def write[A](value: A)(using schema: Schema[A]): String =
        write(value, Yaml.WriterConfig.Default)

    def write[A](value: A, config: Yaml.WriterConfig)(using schema: Schema[A]): String =
        val writer = YamlEvents.Writer(config)
        schema.writeTo(value, writer)
        writer.resultString
    end write

    def direct(write: YamlEvents.Writer => Unit): String =
        val writer = YamlEvents.Writer(Yaml.WriterConfig.Default)
        write(writer)
        writer.resultString
    end direct

    def collectEventLabels(yaml: String): Result[DecodeException, Chunk[String]] =
        val collector = new Yaml.Events.EventHandler[Chunk[String], Nothing]:
            override def event(context: Chunk[String], event: Yaml.Events.Event): Result[Nothing, Chunk[String]] =
                Result.succeed(context :+ label(event))
        end collector
        YamlParser(yaml).visitEvents(Chunk.empty)(collector)
    end collectEventLabels

    def collectScalarEvents(yaml: String): Result[DecodeException, Chunk[(String, Yaml.ScalarStyle)]] =
        val collector = new Yaml.Events.Handler[Chunk[(String, Yaml.ScalarStyle)], Nothing]:
            override def scalar(
                context: Chunk[(String, Yaml.ScalarStyle)],
                value: String,
                meta: Yaml.ScalarMeta
            ): Result[Nothing, Chunk[(String, Yaml.ScalarStyle)]] =
                Result.succeed(context :+ ((value, meta.style)))
        end collector
        YamlParser(yaml).visitEvents(Chunk.empty)(collector)
    end collectScalarEvents

    def collectScalarDetails(yaml: String): Result[DecodeException, Chunk[(String, Yaml.ScalarStyle, String, String)]] =
        val collector = new Yaml.Events.Handler[Chunk[(String, Yaml.ScalarStyle, String, String)], Nothing]:
            override def scalar(
                context: Chunk[(String, Yaml.ScalarStyle, String, String)],
                value: String,
                meta: Yaml.ScalarMeta
            ): Result[Nothing, Chunk[(String, Yaml.ScalarStyle, String, String)]] =
                Result.succeed(context :+ ((value, meta.style, anchorValue(meta.anchor), tagValue(meta.tag))))
        end collector
        YamlParser(yaml).visitEvents(Chunk.empty)(collector)
    end collectScalarDetails

    def label(event: Yaml.Events.Event): String =
        event match
            case Yaml.Events.Event.StreamStart(_)         => "streamStart"
            case Yaml.Events.Event.DocumentStart(_)       => "documentStart"
            case Yaml.Events.Event.MappingStart(meta, _)  => s"mappingStart:${anchorValue(meta.anchor)}:${tagValue(meta.tag)}"
            case Yaml.Events.Event.SequenceStart(meta, _) => s"sequenceStart:${anchorValue(meta.anchor)}:${tagValue(meta.tag)}"
            case Yaml.Events.Event.Scalar(value, meta)    => s"scalar:$value:${meta.style}"
            case Yaml.Events.Event.Alias(name, _)         => s"alias:${name.value}"
            case Yaml.Events.Event.CollectionEnd(kind, _) => s"collectionEnd:$kind"
            case Yaml.Events.Event.DocumentEnd(_)         => "documentEnd"
            case Yaml.Events.Event.StreamEnd(_)           => "streamEnd"
    end label

    def anchorValue(anchor: Maybe[Yaml.Anchor]): String =
        anchor.map(_.value).getOrElse("")

    def tagValue(tag: Maybe[Yaml.YamlTag]): String =
        tag.map(_.value).getOrElse("")
end YamlEventsTest
