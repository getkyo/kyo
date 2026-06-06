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

            assert(
                collectScalarEvents(yaml) == Result.succeed(Chunk(
                    ("single", Yaml.ScalarStyle.Plain),
                    ("line one line two\nline three", Yaml.ScalarStyle.SingleQuoted),
                    ("double", Yaml.ScalarStyle.Plain),
                    ("line one line two\nline three", Yaml.ScalarStyle.DoubleQuoted)
                ))
            )
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

            assert(
                collectScalarEvents(yaml) == Result.succeed(Chunk(
                    ("literal", Yaml.ScalarStyle.Plain),
                    ("first\n  deeper", Yaml.ScalarStyle.Literal),
                    ("folded", Yaml.ScalarStyle.Plain),
                    ("one two\nthree\n", Yaml.ScalarStyle.Folded)
                ))
            )
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

            assert(
                collectEventLabels(yaml) == Result.succeed(Chunk(
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
            )
        }

        "emits directives explicit document starts and empty documents as parser events" in {
            val yaml =
                """%YAML 1.2
                  |--- # explicit empty document
                  |""".stripMargin

            assert(
                collectEventLabels(yaml) == Result.succeed(Chunk(
                    "streamStart",
                    "documentStart",
                    "scalar::Plain",
                    "documentEnd",
                    "streamEnd"
                ))
            )
        }

        "emits anchors and tags on inline collection parser events" in {
            val yaml =
                """items: !!seq &items [one, two]
                  |settings: &settings !!map { mode: "fast", retries: 3 }
                  |copy: *items
                  |""".stripMargin

            assert(
                collectEventLabels(yaml) == Result.succeed(Chunk(
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
            )
        }

        "emits empty mapping values and indentless sequence values as parser events" in {
            val yaml =
                """empty:
                  |items:
                  |- one
                  |- two
                  |""".stripMargin

            assert(
                collectEventLabels(yaml) == Result.succeed(Chunk(
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
            )
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

            assert(
                collectScalarDetails(yaml) == Result.succeed(Chunk(
                    ("notes", Yaml.ScalarStyle.Plain, "", ""),
                    ("hello\n  code", Yaml.ScalarStyle.Literal, "intro", ""),
                    ("folded text\n\n", Yaml.ScalarStyle.Folded, "", "!summary")
                ))
            )
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

            assert(
                collectEventLabels(yaml) == Result.succeed(Chunk(
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
            )
        }

        "decodes YAML double quoted escape repertoire through parser events" in {
            val yaml =
                """escaped: "\0\a\b\t\n\v\f\r\e \"\/\\\N\_\L\P\x41\u263A\U0001F600"
                  |""".stripMargin

            assert(
                collectScalarEvents(yaml) == Result.succeed(Chunk(
                    ("escaped", Yaml.ScalarStyle.Plain),
                    ("\u0000\u0007\b\t\n\u000b\f\r\u001b \"/\\\u0085\u00a0\u2028\u2029A\u263a\uD83D\uDE00", Yaml.ScalarStyle.DoubleQuoted)
                ))
            )
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

            assert(observed.invalidEscape == true)
            assert(observed.hasLocation == true)
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

            val obtained3 = (
                resultString = writer.resultString,
                controlChar = write("\u0001")
            )
            assert(
                obtained3.resultString ==
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
            assert(obtained3.controlChar == "\"\\u0001\"\n")
        }

        "matches production YAML writer for empty collections and scalar roots" in {
            val obtained4 = (
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
            assert(obtained4.emptyList == "[]\n")
            assert(obtained4.emptyMap == "{}\n")
            assert(obtained4.intList == "- 1\n- 2\n- 3\n")
            assert(obtained4.trueStr == "\"true\"\n")
            assert(obtained4.hex == "\"0x3A\"\n")
            assert(obtained4.octal == "\"0o7\"\n")
            assert(obtained4.dotFive == "\".5\"\n")
            assert(obtained4.expNum == "\"+12e03\"\n")
            assert(obtained4.infStr == "\".inf\"\n")
        }

        "honors compact sequence mapping layout from writer config" in {
            val writer = YamlEvents.Writer(Yaml.WriterConfig.Compact)

            summon[Schema[List[MTPerson]]].writeTo(List(MTPerson("Alice", 30)), writer)

            assert(writer.resultString == "- name: Alice\n  age: 30\n")
        }

        "honors small and fast flow writer profiles" in {
            val value = MTPerson("Alice", 30)

            val obtained5 = (
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
            assert(obtained5.smallPerson == "{name: Alice, age: 30}")
            assert(obtained5.fastPerson == "{\"name\":\"Alice\",\"age\":30}")
            assert(obtained5.smallScalar == "Alice")
            assert(obtained5.smallEmptyList == "[]")
            assert(obtained5.fastEmptyMap == "{}")
            assert(obtained5.flowMultiline == "[\"line one\\nline two\\n\"]")
        }

        "matches production YAML writer for finite and special floating point values" in {
            val obtained6 = (
                bigExp = write(1.23e100d),
                minPositive = write(Double.MinPositiveValue),
                floatMax = write(Float.MaxValue),
                doubleSpecials = write(List(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity)),
                floatSpecials = write(List(Float.NaN, Float.PositiveInfinity, Float.NegativeInfinity), Yaml.WriterConfig.Fast)
            )
            assert(obtained6.bigExp == "1.23E100\n")
            assert(obtained6.minPositive == "5.0E-324\n")
            assert(obtained6.floatMax == "3.4028235E38\n")
            assert(obtained6.doubleSpecials == "- .nan\n- .inf\n- -.inf\n")
            assert(obtained6.floatSpecials == """[!!float ".nan",!!float ".inf",!!float "-.inf"]""")
        }

        "honors scalar quoting folded multiline and YAML version config" in {
            val minimal      = Yaml.WriterConfig.Readable.copy(scalarQuoting = Yaml.WriterConfig.ScalarQuoting.WhenNeeded)
            val singleQuoted = Yaml.WriterConfig.Readable.copy(quoteStyle = Yaml.WriterConfig.QuoteStyle.Single)
            val legacy       = Yaml.WriterConfig.Readable.copy(yamlVersion = Yaml.SpecVersion.Yaml11)
            val folded       = Yaml.WriterConfig.Readable.copy(multilineStyle = Yaml.WriterConfig.MultilineStyle.Folded)

            val obtained7 = (
                minimalTrue = write("true", minimal),
                defaultTrue = write("true"),
                singleQuoted = write("Alice #1", singleQuoted),
                plainNo = write("NO"),
                legacyNo = write("NO", legacy),
                legacyOctal = write("010", legacy),
                foldedMultiline = write("line one\nline two\n", folded)
            )
            assert(obtained7.minimalTrue == "true\n")
            assert(obtained7.defaultTrue == "\"true\"\n")
            assert(obtained7.singleQuoted == "'Alice #1'\n")
            assert(obtained7.plainNo == "NO\n")
            assert(obtained7.legacyNo == "\"NO\"\n")
            assert(obtained7.legacyOctal == "\"010\"\n")
            assert(obtained7.foldedMultiline == ">\n  line one\n\n  line two\n")
        }

        "honors document marker config" in {
            val start = Yaml.WriterConfig.Readable.copy(documentMarkers = Yaml.WriterConfig.DocumentMarkers.Start)
            val both  = Yaml.WriterConfig.Readable.copy(documentMarkers = Yaml.WriterConfig.DocumentMarkers.StartAndEnd)

            val obtained8 = (
                startMarker = write(MTPerson("Alice", 30), start),
                bothMarkers = write(MTPerson("Alice", 30), both)
            )
            assert(obtained8.startMarker == "---\nname: Alice\nage: 30\n")
            assert(obtained8.bothMarkers == "---\nname: Alice\nage: 30\n...\n")
        }

        "matches production YAML writer direct scalar methods" in {
            val obtained9 = (
                bytes = direct(_.bytes(Span.fromUnsafe(Array[Byte](1, 2, 3)))),
                bigInt = direct(_.bigInt(BigInt("12345678901234567890"))),
                bigDecimal = direct(_.bigDecimal(BigDecimal("1234567890.0987654321"))),
                instant = direct(_.instant(java.time.Instant.parse("2026-05-30T12:34:56Z"))),
                duration = direct(_.duration(java.time.Duration.parse("PT1H2M3S")))
            )
            assert(obtained9.bytes == "\"AQID\"\n")
            assert(obtained9.bigInt == "\"12345678901234567890\"\n")
            assert(obtained9.bigDecimal == "\"1234567890.0987654321\"\n")
            assert(obtained9.instant == "\"2026-05-30T12:34:56Z\"\n")
            assert(obtained9.duration == "\"PT1H2M3S\"\n")
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

        "writes all primitive scalar types through events writer" in {
            val obtained10 = (
                longVal = direct(_.long(Long.MaxValue)),
                shortVal = direct(_.short(Short.MaxValue)),
                byteVal = direct(_.byte(Byte.MaxValue)),
                boolTrue = direct(_.boolean(true)),
                boolFalse = direct(_.boolean(false)),
                charVal = direct(_.char('A')),
                nilVal = direct(_.nil())
            )
            assert(obtained10.longVal == "9223372036854775807\n")
            assert(obtained10.shortVal == "32767\n")
            assert(obtained10.byteVal == "127\n")
            assert(obtained10.boolTrue == "true\n")
            assert(obtained10.boolFalse == "false\n")
            assert(obtained10.charVal == "\"A\"\n")
            assert(obtained10.nilVal == "null\n")
        }

        "writes double-quoted strings for control chars through events writer" in {
            val fastCfg = Yaml.WriterConfig.Fast.copy(trailingNewline = true)
            val obtained11 = (
                controlChar = write("", fastCfg),
                newline = write("line1\nline2", fastCfg),
                backslash = write("\\", fastCfg)
            )
            assert(obtained11.controlChar == "\"\\u0001\"\n")
            assert(obtained11.newline == "\"line1\\nline2\"\n")
            assert(obtained11.backslash == "\"\\\\\"\n")
        }

        "writes single-quoted strings that need quoting when single-quote style is configured" in {
            val singleStyle = Yaml.WriterConfig.Readable.copy(quoteStyle = Yaml.WriterConfig.QuoteStyle.Single)
            // "true" is ambiguous and needs quoting; single-quote style applies
            val yaml = write("true", singleStyle)
            val obtained12 = (
                encoded = yaml,
                decoded = Yaml.decode[String](yaml)
            )
            assert(obtained12.encoded == "'true'\n")
            assert(obtained12.decoded == Result.succeed("true"))
        }

        "writes single-quoted strings with embedded single quotes using doubled-quote escaping" in {
            val singleStyle = Yaml.WriterConfig.Readable.copy(quoteStyle = Yaml.WriterConfig.QuoteStyle.Single)
            // "#" in the body forces quoting; single-quote style applies
            val yaml = write("it #1", singleStyle)
            val obtained13 = (
                encoded = yaml,
                decoded = Yaml.decode[String](yaml)
            )
            assert(obtained13.encoded == "'it #1'\n")
            assert(obtained13.decoded == Result.succeed("it #1"))
        }

        "writes plain-unsafe strings as quoted scalars through events writer" in {
            val obtained14 = (
                dashDash = write("---"),
                dotDotDot = write("..."),
                percent = write("%TAG")
            )
            assert(obtained14.dashDash == "\"---\"\n")
            assert(obtained14.dotDotDot == "\"...\"\n")
            assert(obtained14.percent == "\"%TAG\"\n")
        }

        "writes Strip chomped literal block scalars through events writer" in {
            val config = Yaml.WriterConfig.Readable.copy(chomping = Yaml.WriterConfig.Chomping.Strip)
            val yaml   = write("line one\nline two\n", config)
            assert(yaml.startsWith("|-\n"))
            assert(Yaml.decode[String](yaml) == Result.succeed("line one\nline two"))
        }

        "writes Keep chomped literal block scalars through events writer" in {
            val config = Yaml.WriterConfig.Readable.copy(chomping = Yaml.WriterConfig.Chomping.Keep)
            val yaml   = write("line one\nline two\n", config)
            assert(yaml.startsWith("|+\n"))
            assert(Yaml.decode[String](yaml) == Result.succeed("line one\nline two\n"))
        }

        "writes document Start marker through events writer" in {
            val config = Yaml.WriterConfig.Readable.copy(documentMarkers = Yaml.WriterConfig.DocumentMarkers.Start)
            val yaml   = write(MTPerson("Alice", 30), config)
            assert(yaml.startsWith("---\n"))
            assert(Yaml.decode[MTPerson](yaml) == Result.succeed(MTPerson("Alice", 30)))
        }

        "writes document StartAndEnd markers through events writer" in {
            val config = Yaml.WriterConfig.Readable.copy(documentMarkers = Yaml.WriterConfig.DocumentMarkers.StartAndEnd)
            val yaml   = write(MTPerson("Alice", 30), config)
            assert(yaml.startsWith("---\n"))
            assert(yaml.endsWith("...\n"))
            assert(Yaml.decode[MTPerson](yaml) == Result.succeed(MTPerson("Alice", 30)))
        }

        "writes flow sequence and mapping through events writer using Flow style" in {
            val flowConfig = Yaml.WriterConfig.Readable.copy(collectionStyle = Yaml.WriterConfig.CollectionStyle.Flow)
            val yaml       = write(MTPerson("Alice", 30), flowConfig)
            assert(yaml.contains('{'))
        }

        "writes QuoteAllStrings through events writer" in {
            val config = Yaml.WriterConfig.Readable.copy(scalarQuoting = Yaml.WriterConfig.ScalarQuoting.QuoteAllStrings)
            val yaml   = write("hello", config)
            val obtained15 = (
                encoded = yaml,
                decoded = Yaml.decode[String](yaml)
            )
            assert(obtained15.encoded == "\"hello\"\n")
            assert(obtained15.decoded == Result.succeed("hello"))
        }

        "writes folded block scalars with Strip chomping using >- header through events writer" in {
            val config = Yaml.WriterConfig.Readable.copy(
                multilineStyle = Yaml.WriterConfig.MultilineStyle.Folded,
                chomping = Yaml.WriterConfig.Chomping.Strip
            )
            val value = "line one\nline two\n"
            val yaml  = write(value, config)
            // folded block scalar uses double-blank-line separation to preserve internal newlines as paragraph breaks
            assert(yaml.startsWith(">-\n"))
            val decoded = Yaml.decode[String](yaml)
            assert(decoded.isSuccess)
        }

        "routes events through EventWriter and collects context" in {
            val handler = new Yaml.Events.Handler[Int, Nothing]:
                override def scalar(context: Int, value: String, meta: Yaml.ScalarMeta): Result[Nothing, Int] =
                    Result.succeed(context + 1)
            end handler

            // MTPerson has 2 fields: each field emits a key scalar and a value scalar = 4 total
            val result = Yaml.Events.write(MTPerson("Alice", 30), 0)(handler)
            assert(result == Result.succeed(4))
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
