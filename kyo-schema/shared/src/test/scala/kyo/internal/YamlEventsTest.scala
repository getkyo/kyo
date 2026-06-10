package kyo.internal

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

            assert(
                writer.resultString ==
                    """plain: Alice
                      |truthy: "true"
                      |comment: "Alice #1"
                      |url: "https://example.com"
                      |multiline: |
                      |  line one
                      |  line two
                      |empty: ""
                      |""".stripMargin
            )
            assert(write("\u0001") == "\"\\u0001\"\n")
        }

        "matches production YAML writer for empty collections and scalar roots" in {
            assert(write(List.empty[Int]) == "[]\n")
            assert(write(Map.empty[String, Int]) == "{}\n")
            assert(write(List(1, 2, 3)) == "- 1\n- 2\n- 3\n")
            assert(write("true") == "\"true\"\n")
            assert(write("0x3A") == "\"0x3A\"\n")
            assert(write("0o7") == "\"0o7\"\n")
            assert(write(".5") == "\".5\"\n")
            assert(write("+12e03") == "\"+12e03\"\n")
            assert(write(".inf") == "\".inf\"\n")
        }

        "honors compact sequence mapping layout from writer config" in {
            val writer = YamlEvents.Writer(Yaml.WriterConfig.Compact)

            summon[Schema[List[MTPerson]]].writeTo(List(MTPerson("Alice", 30)), writer)

            assert(writer.resultString == "- name: Alice\n  age: 30\n")
        }

        "honors small and fast flow writer profiles" in {
            val value = MTPerson("Alice", 30)

            assert(write(value, Yaml.WriterConfig.Small) == "{name: Alice, age: 30}")
            assert(write(value, Yaml.WriterConfig.Fast) == "{\"name\":\"Alice\",\"age\":30}")
            assert(write("Alice", Yaml.WriterConfig.Small) == "Alice")
            assert(write(List.empty[Int], Yaml.WriterConfig.Small) == "[]")
            assert(write(Map.empty[String, Int], Yaml.WriterConfig.Fast) == "{}")
            assert(write(
                List("line one\nline two\n"),
                Yaml.WriterConfig.Readable.copy(collectionStyle = Yaml.WriterConfig.CollectionStyle.Flow)
            ) ==
                "[\"line one\\nline two\\n\"]")
        }

        "matches production YAML writer for finite and special floating point values" in {
            assert(write(1.23e100d) == "1.23E100\n")
            assert(write(Double.MinPositiveValue) == "5.0E-324\n")
            assert(write(Float.MaxValue) == "3.4028235E38\n")

            assert(write(List(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity)) == "- .nan\n- .inf\n- -.inf\n")
            assert(
                write(List(Float.NaN, Float.PositiveInfinity, Float.NegativeInfinity), Yaml.WriterConfig.Fast) ==
                    """[!!float ".nan",!!float ".inf",!!float "-.inf"]"""
            )
        }

        "honors scalar quoting folded multiline and YAML version config" in {
            val minimal      = Yaml.WriterConfig.Readable.copy(scalarQuoting = Yaml.WriterConfig.ScalarQuoting.WhenNeeded)
            val singleQuoted = Yaml.WriterConfig.Readable.copy(quoteStyle = Yaml.WriterConfig.QuoteStyle.Single)
            val legacy       = Yaml.WriterConfig.Readable.copy(yamlVersion = Yaml.SpecVersion.Yaml11)
            val folded       = Yaml.WriterConfig.Readable.copy(multilineStyle = Yaml.WriterConfig.MultilineStyle.Folded)

            assert(write("true", minimal) == "true\n")
            assert(write("true") == "\"true\"\n")
            assert(write("Alice #1", singleQuoted) == "'Alice #1'\n")
            assert(write("NO") == "NO\n")
            assert(write("NO", legacy) == "\"NO\"\n")
            assert(write("010", legacy) == "\"010\"\n")
            assert(write("line one\nline two\n", folded) == ">\n  line one\n\n  line two\n")
        }

        "honors document marker config" in {
            val start = Yaml.WriterConfig.Readable.copy(documentMarkers = Yaml.WriterConfig.DocumentMarkers.Start)
            val both  = Yaml.WriterConfig.Readable.copy(documentMarkers = Yaml.WriterConfig.DocumentMarkers.StartAndEnd)

            assert(write(MTPerson("Alice", 30), start) == "---\nname: Alice\nage: 30\n")
            assert(write(MTPerson("Alice", 30), both) == "---\nname: Alice\nage: 30\n...\n")
        }

        "matches production YAML writer direct scalar methods" in {
            assert(direct(_.bytes(Span.fromUnsafe(Array[Byte](1, 2, 3)))) == "\"AQID\"\n")
            assert(direct(_.bigInt(BigInt("12345678901234567890"))) == "\"12345678901234567890\"\n")
            assert(direct(_.bigDecimal(BigDecimal("1234567890.0987654321"))) == "\"1234567890.0987654321\"\n")
            assert(direct(_.instant(java.time.Instant.parse("2026-05-30T12:34:56Z"))) == "\"2026-05-30T12:34:56Z\"\n")
            assert(direct(_.duration(java.time.Duration.parse("PT1H2M3S"))) == "\"PT1H2M3S\"\n")
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
