package kyo

import scala.annotation.publicInBinary

final case class IonAnnotated(value: Int) derives CanEqual

final class IonRootAnnotation
final class IonFieldAnnotation
final case class IonSpecAnnotated(value: Int) derives CanEqual

final class IonSpecCustomTypeAnnotation
final class IonSpecInt32Annotation
final class IonSpecDegreesAnnotation
final class IonSpecCelsiusAnnotation

final case class IonNestedAnnotated(value: Int) derives CanEqual

object IonAnnotationMarkers:
    final class NestedRootMarker
end IonAnnotationMarkers

case object IonCaseFieldMarker

object IonAnnotated:

    private val fieldName    = "value"
    private val valueFieldId = kyo.internal.CodecMacro.fieldId(fieldName)

    private val rootAnnotations  = Chunk(IonRootAnnotation())
    private val fieldAnnotations = Chunk(IonFieldAnnotation())

    given Schema[IonAnnotated] =
        new Schema[IonAnnotated](Nil):
            type Focused = IonAnnotated

            private val annotatedStructure =
                Structure.Type.Product(
                    "IonAnnotated",
                    Tag[IonAnnotated].asInstanceOf[Tag[Any]],
                    Chunk.empty,
                    Chunk(Structure.Field(
                        fieldName,
                        summon[Schema[Int]].structure,
                        annotations = fieldAnnotations
                    )),
                    rootAnnotations
                )

            @publicInBinary override private[kyo] def serializeWrite(value: IonAnnotated, writer: Codec.Writer): Unit =
                val writeWriter = writerForAnnotations(writer)
                writeWriter.objectStart("IonAnnotated", 1)
                writeWriter.field(fieldName, valueFieldId)
                writeWriter.int(value.value)
                writeWriter.objectEnd()
            end serializeWrite

            @publicInBinary override private[kyo] def serializeRead(reader: Codec.Reader): IonAnnotated =
                reader.objectStart()
                var value = Maybe.empty[Int]
                while reader.hasNextField() do
                    reader.field() match
                        case `fieldName` => value = Maybe(reader.int())
                        case _           => reader.skip()
                    end match
                end while
                reader.objectEnd()
                value match
                    case Maybe.Present(v) => IonAnnotated(v)
                    case Maybe.Absent     => throw MissingFieldException(Seq.empty, fieldName)(using reader.frame)
            end serializeRead

            @publicInBinary override private[kyo] def getter(value: IonAnnotated): Maybe[Any] =
                Maybe(value)

            @publicInBinary override private[kyo] def setter(value: IonAnnotated, next: Any): IonAnnotated =
                next.asInstanceOf[IonAnnotated]

            override def structure: Structure.Type = annotatedStructure
        end new
    end given
end IonAnnotated

object IonSpecAnnotated:

    private val fieldName    = "value"
    private val valueFieldId = kyo.internal.CodecMacro.fieldId(fieldName)

    private val rootAnnotations  = Chunk(IonSpecCustomTypeAnnotation())
    private val fieldAnnotations = Chunk(
        IonSpecInt32Annotation(),
        IonSpecDegreesAnnotation(),
        IonSpecCelsiusAnnotation()
    )

    given Schema[IonSpecAnnotated] =
        new Schema[IonSpecAnnotated](Nil):
            type Focused = IonSpecAnnotated

            private val annotatedStructure =
                Structure.Type.Product(
                    "IonSpecAnnotated",
                    Tag[IonSpecAnnotated].asInstanceOf[Tag[Any]],
                    Chunk.empty,
                    Chunk(Structure.Field(
                        fieldName,
                        summon[Schema[Int]].structure,
                        annotations = fieldAnnotations
                    )),
                    rootAnnotations
                )

            @publicInBinary override private[kyo] def serializeWrite(value: IonSpecAnnotated, writer: Codec.Writer): Unit =
                val writeWriter = writerForAnnotations(writer)
                writeWriter.objectStart("IonSpecAnnotated", 1)
                writeWriter.field(fieldName, valueFieldId)
                writeWriter.int(value.value)
                writeWriter.objectEnd()
            end serializeWrite

            @publicInBinary override private[kyo] def serializeRead(reader: Codec.Reader): IonSpecAnnotated =
                reader.objectStart()
                var value = Maybe.empty[Int]
                while reader.hasNextField() do
                    reader.field() match
                        case `fieldName` => value = Maybe(reader.int())
                        case _           => reader.skip()
                    end match
                end while
                reader.objectEnd()
                value match
                    case Maybe.Present(v) => IonSpecAnnotated(v)
                    case Maybe.Absent     => throw MissingFieldException(Seq.empty, fieldName)(using reader.frame)
            end serializeRead

            @publicInBinary override private[kyo] def getter(value: IonSpecAnnotated): Maybe[Any] =
                Maybe(value)

            @publicInBinary override private[kyo] def setter(value: IonSpecAnnotated, next: Any): IonSpecAnnotated =
                next.asInstanceOf[IonSpecAnnotated]

            override def structure: Structure.Type = annotatedStructure
        end new
    end given
end IonSpecAnnotated

object IonNestedAnnotated:

    private val fieldName    = "value"
    private val valueFieldId = kyo.internal.CodecMacro.fieldId(fieldName)

    private val rootAnnotations  = Chunk(IonAnnotationMarkers.NestedRootMarker())
    private val fieldAnnotations = Chunk(IonCaseFieldMarker)

    given Schema[IonNestedAnnotated] =
        new Schema[IonNestedAnnotated](Nil):
            type Focused = IonNestedAnnotated

            private val annotatedStructure =
                Structure.Type.Product(
                    "IonNestedAnnotated",
                    Tag[IonNestedAnnotated].asInstanceOf[Tag[Any]],
                    Chunk.empty,
                    Chunk(Structure.Field(
                        fieldName,
                        summon[Schema[Int]].structure,
                        annotations = fieldAnnotations
                    )),
                    rootAnnotations
                )

            @publicInBinary override private[kyo] def serializeWrite(value: IonNestedAnnotated, writer: Codec.Writer): Unit =
                val writeWriter = writerForAnnotations(writer)
                writeWriter.objectStart("IonNestedAnnotated", 1)
                writeWriter.field(fieldName, valueFieldId)
                writeWriter.int(value.value)
                writeWriter.objectEnd()
            end serializeWrite

            @publicInBinary override private[kyo] def serializeRead(reader: Codec.Reader): IonNestedAnnotated =
                reader.objectStart()
                var value = Maybe.empty[Int]
                while reader.hasNextField() do
                    reader.field() match
                        case `fieldName` => value = Maybe(reader.int())
                        case _           => reader.skip()
                    end match
                end while
                reader.objectEnd()
                value match
                    case Maybe.Present(v) => IonNestedAnnotated(v)
                    case Maybe.Absent     => throw MissingFieldException(Seq.empty, fieldName)(using reader.frame)
            end serializeRead

            @publicInBinary override private[kyo] def getter(value: IonNestedAnnotated): Maybe[Any] =
                Maybe(value)

            @publicInBinary override private[kyo] def setter(value: IonNestedAnnotated, next: Any): IonNestedAnnotated =
                next.asInstanceOf[IonNestedAnnotated]

            override def structure: Structure.Type = annotatedStructure
        end new
    end given
end IonNestedAnnotated

class IonTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    private def assertFailure[A](
        result: Result[DecodeException, A],
        expectedClassName: String,
        messageFragment: String
    )(using kyo.test.AssertScope): Unit =
        if result.failure.isEmpty then fail(s"Expected $expectedClassName, got $result")
        val ex     = result.failure.get
        val actual = ex.getClass.getName
        assert(
            actual.endsWith(expectedClassName) || actual.contains(expectedClassName),
            s"Expected $expectedClassName, got $actual"
        )
        assert(ex.getMessage.contains(messageFragment))
    end assertFailure

    "encode/decode" - {

        "ion encode simple case class" in {
            val ion = Ion.encode[MTPerson](MTPerson("Alice", 30))
            assert(ion == """{name:"Alice",age:30}""")
        }

        "ion decode simple case class" in {
            val person = Ion.decode[MTPerson]("""{name:"Alice",age:30}""").getOrThrow
            assert(person == MTPerson("Alice", 30))
        }

        "ion round-trip case class" in {
            val person  = MTPerson("Bob", 25)
            val encoded = Ion.encodeBytes[MTPerson](person)
            val decoded = Ion.decodeBytes[MTPerson](encoded).getOrThrow
            assert(decoded == person)
        }

        "ion config defaults select text" in {
            assert(Ion.Config() == Ion.Config.Default)
            assert(Ion.Config().format == Ion.Format.Text)
            assert(Ion().config == Ion.Config.Default)
            assert(summon[Ion].config == Ion.Config.Default)
            assert(summon[Ion.Config] == Ion.Config.Default)
        }

        "ion no-config helpers force text with contextual binary ion" in {
            given Ion = Ion(Ion.Config(format = Ion.Format.Binary))

            val person  = MTPerson("Alice", 30)
            val encoded = """{name:"Alice",age:30}"""
            val bytes   = Span.from(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8))

            assert(Ion.encode[MTPerson](person) == encoded)
            assert(new String(Ion.encodeBytes[MTPerson](person).toArray, java.nio.charset.StandardCharsets.UTF_8) == encoded)
            assert(Ion.decode[MTPerson](encoded).getOrThrow == person)
            assert(Ion.decodeBytes[MTPerson](bytes).getOrThrow == person)
        }

        "ion text aliases match no-config helpers" in {
            val person  = MTPerson("Alice", 30)
            val encoded = Ion.encode[MTPerson](person)
            val bytes   = Ion.encodeBytes[MTPerson](person)

            assert(Ion.encodeText[MTPerson](person) == encoded)
            assert(CodecTestSupport.sameBytes(Ion.encodeTextBytes[MTPerson](person), bytes))
            assert(Ion.decodeText[MTPerson](encoded) == Ion.decode[MTPerson](encoded))
            assert(Ion.decodeTextBytes[MTPerson](bytes) == Ion.decodeBytes[MTPerson](bytes))
        }

        "ion configured text helpers match no-config helpers" in {
            val config  = Ion.Config(format = Ion.Format.Text)
            val person  = MTPerson("Alice", 30)
            val encoded = Ion.encode[MTPerson](person)
            val bytes   = Ion.encodeBytes[MTPerson](person)

            assert(Ion.encode[MTPerson](person, config) == encoded)
            assert(CodecTestSupport.sameBytes(Ion.encodeBytes[MTPerson](person, config), bytes))
            assert(Ion.decode[MTPerson](encoded, config) == Ion.decode[MTPerson](encoded))
            assert(Ion.decode[MTPerson](bytes, config) == Ion.decodeBytes[MTPerson](bytes))
            assert(Ion.decodeBytes[MTPerson](bytes, config) == Ion.decodeBytes[MTPerson](bytes))
        }

        "ion configured annotation writing wraps text output" in {
            val value = IonAnnotated(7)

            assert(Ion.encode(value) == "{value:7}")
            assert(Ion.encode(value, Ion.Config()) == "{value:7}")

            val config = Ion.Config(annotationEmissionMode = Ion.AnnotationEmissionMode.Emit)
            assert(Ion.encode(value, config) == "'kyo.IonRootAnnotation'::{value:'kyo.IonFieldAnnotation'::7}")
            assert(
                new String(Ion.encodeBytes(value, config).toArray, java.nio.charset.StandardCharsets.UTF_8) ==
                    "'kyo.IonRootAnnotation'::{value:'kyo.IonFieldAnnotation'::7}"
            )
            val decoded = Ion.decode[IonAnnotated](Ion.encode(value, config)).getOrThrow
            assert(decoded == value)

            val writer = Ion(config).newWriter()
            summon[Schema[IonAnnotated]].writeTo(value, writer)
            val codecBytes = writer.result()
            assert(CodecTestSupport.sameBytes(codecBytes, Ion.encodeBytes(value, config)))
        }

        "canWriteAnnotations is true only when annotation emission is enabled" in {
            val emitting = Ion(Ion.Config(annotationEmissionMode = Ion.AnnotationEmissionMode.Emit))
            assert(emitting.newWriter().canWriteAnnotations)
            assert(!Ion().newWriter().canWriteAnnotations)
        }

        "ion configured binary string helpers reject text-shaped calls" in {
            val config = Ion.Config(format = Ion.Format.Binary)

            Result.catching[SchemaException](Ion.encode[MTPerson](MTPerson("Alice", 30), config)) match
                case Result.Failure(ex: SchemaNotSerializableException) =>
                    assert(ex.getMessage.contains("Ion.encodeBytes"))
                case other => fail(s"Expected SchemaNotSerializableException, got $other")
            end match

            Ion.decode[MTPerson]("""{name:"Alice",age:30}""", config) match
                case Result.Failure(ex: DecodeException) =>
                    assert(ex.getMessage.contains("Span[Byte]"))
                    assert(ex.getMessage.contains("Ion.decodeBytes"))
                case other => fail(s"Expected DecodeException failure, got $other")
            end match
        }

        "ion binary helpers route through IonBinary internals" in {
            val config = Ion.Config(format = Ion.Format.Binary)
            val value  = MTPerson("Alice", 30)
            val bytes  = IonBinary.encode(value)

            assert(Ion.decodeBinary[MTPerson](bytes).getOrThrow == value)
            assert(Ion.decode[MTPerson](Ion.encodeBytes(value, config), config).getOrThrow == value)
            assert(Ion.decodeBytes[MTPerson](bytes, config).getOrThrow == value)

            given Ion = Ion(config)
            assert(Schema[MTPerson].decode[Ion](Schema[MTPerson].encode[Ion](value)).getOrThrow == value)
        }

        "ion works with nested case classes and collections" in {
            val team    = MTSmallTeam(MTPerson("Alice", 30), 5)
            val encoded = Ion.encode(team)
            assert(encoded == """{lead:{name:"Alice",age:30},size:5}""")
            assert(Ion.decode[MTSmallTeam](encoded).getOrThrow == team)

            val people = List(MTPerson("Alice", 30), MTPerson("Bob", 25))
            assert(Ion.decode[List[MTPerson]](Ion.encode(people)).getOrThrow == people)

            val scores = Map("a" -> 1, "spaced key" -> 2)
            assert(Ion.decode[Map[String, Int]](Ion.encode(scores)).getOrThrow == scores)
        }

        "ion encodes byte spans as blobs" in {
            val bytes   = Span.from("To infinity... and beyond!".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            val encoded = Ion.encode(bytes)
            assert(encoded == "{{VG8gaW5maW5pdHkuLi4gYW5kIGJleW9uZCE=}}")
            assert(CodecTestSupport.sameBytes(Ion.decode[Span[Byte]](encoded).getOrThrow, bytes))
        }

        "ion sealed traits use wrapper structs" in {
            val shape: MTShape = MTCircle(5.0)
            val encoded        = Ion.encode[MTShape](shape)
            assert(encoded == "{MTCircle:{radius:5.0e0}}")
            assert(Ion.decode[MTShape](encoded).getOrThrow == shape)
        }

        "ion discriminator schema reads annotated values" in {
            given Schema[MTShape] = Schema[MTShape].discriminator("type")

            val encoded = """shape::{type:"MTRectangle",width:3.0e0,height:4.0e0}"""
            val decoded = Ion.decode[MTShape](encoded).getOrThrow
            assert(decoded == MTRectangle(3.0, 4.0))
        }

        "ion decodes spec type annotation examples as metadata" in {
            assert(Ion.decode[Int]("int32::12").getOrThrow == 12)
            assert(Ion.decode[Int]("degrees::'celsius'::100").getOrThrow == 100)
            assert(Ion.decode[IonTest.Point]("'my.custom.type'::{x:12,y:-1}").getOrThrow == IonTest.Point(12, -1))
            assert(Ion.decode[Map[String, String]]("{field:some_annotation::value}").getOrThrow == Map("field" -> "value"))
        }

        "ion emits spec-shaped type annotation placements" in {
            val value   = IonSpecAnnotated(100)
            val config  = Ion.Config(annotationEmissionMode = Ion.AnnotationEmissionMode.Emit)
            val encoded = Ion.encode(value, config)

            assert(
                encoded ==
                    "'kyo.IonSpecCustomTypeAnnotation'::{value:'kyo.IonSpecInt32Annotation'::'kyo.IonSpecDegreesAnnotation'::'kyo.IonSpecCelsiusAnnotation'::100}"
            )
            assert(Ion.decode[IonSpecAnnotated](encoded).getOrThrow == value)
        }

        "ion emits the concrete symbol for a nested class marker and a case object marker" in {
            val value    = IonNestedAnnotated(7)
            val config   = Ion.Config(annotationEmissionMode = Ion.AnnotationEmissionMode.Emit)
            val expected = "'kyo.IonAnnotationMarkers$NestedRootMarker'::{value:'kyo.IonCaseFieldMarker'::7}"

            assert(Ion.encode(value, config) == expected)
            assert(Ion.decode[IonNestedAnnotated](Ion.encode(value, config)).getOrThrow == value)
        }
    }

    "amazon ion-tests snippets" - {

        "decodes struct spelling variants from iontestdata/good/structs.ion" in {
            val inputs = List(
                "{a:b,c:42,d:{e:f,},g:3}",
                "{'a':'b','c':42,'d':{'e':'f',},'g':3}",
                """{"a":"b","c":42,"d":{"e":"f",},"g":3}""",
                "{a: b, c: 42, d: {e: f, }, g: 3,   }"
            )

            inputs.foreach { input =>
                val decoded = Ion.decode[IonTest.UpstreamStruct](input).getOrThrow
                assert(decoded == IonTest.UpstreamStruct("b", 42, Map("e" -> "f"), 3))
            }
            succeed
        }

        "decodes long field names from iontestdata/good/structs.ion" in {
            val input =
                "{ '''123456789ABCDEF'''  '''123456789ABCDEF''' : v }"
            val decoded = Ion.decode[Map[String, String]](input).getOrThrow
            assert(decoded == Map("123456789ABCDEF123456789ABCDEF" -> "v"))
        }

        "decodes escaped and long strings from iontestdata/good/strings.ion" in {
            assert(Ion.decode[String]("\"\\uABCD\"").getOrThrow == "\uABCD")

            val concat = "'''concatenated'''  ''' from '''   '''a single line'''"
            assert(Ion.decode[String](concat).getOrThrow == "concatenated from a single line")

            val escaped = "\"\\0 \\a \\b \\t \\n \\f \\r \\v \\\" \\' \\? \\\\ \\/\""
            assert(Ion.decode[String](escaped).getOrThrow == "\u0000 \u0007 \b \t \n \f \r \u000b \" ' ? \\ /")
        }

        "decodes booleans from iontestdata/good/booleans.ion" in {
            val upstream = "true\nfalse\n"
            assert(upstream.linesIterator.toList == List("true", "false"))
            assert(Ion.decode[Boolean]("true").getOrThrow)
            assert(!Ion.decode[Boolean]("false").getOrThrow)
        }

        "decodes blobs from iontestdata/good/blobs.ion" in {
            val alphabet =
                """{{
                  |    YSBiIGMgZCBlIGYgZyBoIGkgaiBrIGwgbSBuIG8gcCBxIHIgcyB0IHUgdiB3IHggeSB6
                  |}}""".stripMargin
            val decoded = Ion.decode[Span[Byte]](alphabet).getOrThrow
            assert(new String(
                decoded.toArray,
                java.nio.charset.StandardCharsets.UTF_8
            ) == "a b c d e f g h i j k l m n o p q r s t u v w x y z")

            val withSlashes = "{{  //79/PsAAQIDBAU=  }}"
            assert(
                Ion.decode[Span[Byte]](withSlashes).getOrThrow.toArray.toSeq == java.util.Base64.getDecoder.decode("//79/PsAAQIDBAU=").toSeq
            )
        }

        "preserves high byte CLOB escapes from iontestdata/good/clobs.ion" in {
            val decoded = Ion.decode[Span[Byte]]("""{{"\xFf"}}""").getOrThrow
            assert(decoded.toArray.toSeq == Seq(0xff.toByte))
        }

        "ignores comments as whitespace like iontestdata/good/whitespace.ion" in {
            val input =
                """// before
                  |{
                  |  name: "Fido", /* between */
                  |  age: years::4,
                  |  toys: [ball, rope,],
                  |}
                  |""".stripMargin
            val decoded = Ion.decode[IonTest.Pet](input).getOrThrow
            assert(decoded == IonTest.Pet("Fido", 4, List("ball", "rope")))
        }
    }

    "scalar mappings" - {

        "typed nulls decode as absent optional values" in {
            assert(Ion.decode[Option[Int]]("null.int").getOrThrow == None)
            assert(Ion.decode[Maybe[String]]("null.string").getOrThrow == Maybe.empty)
            assert(Ion.decode[Option[Int]]("0").getOrThrow == Some(0))
        }

        "decodes Ion integer and decimal syntax" in {
            assert(Ion.decode[Int]("0").getOrThrow == 0)
            assert(Ion.decode[Int]("0x7b").getOrThrow == 123)
            assert(Ion.decode[Int]("0b1111011").getOrThrow == 123)
            assert(Ion.decode[BigDecimal]("1.25d2").getOrThrow == BigDecimal("125"))
        }

        "decodes Ion float specials" in {
            assert(Ion.decode[Double]("+inf").getOrThrow == Double.PositiveInfinity)
            assert(Ion.decode[Double]("-inf").getOrThrow == Double.NegativeInfinity)
            assert(Ion.decode[Double]("nan").getOrThrow.isNaN)
        }

        "rejects non-finite Ion floats for BigDecimal" in {
            assertFailure(
                Ion.decode[BigDecimal]("nan"),
                "TypeMismatchException",
                "expected finite decimal but got float"
            )

            assertFailure(
                Ion.decode[BigDecimal]("+inf"),
                "TypeMismatchException",
                "expected finite decimal but got float"
            )
        }

        "rejects trailing content after the decoded root value" in {
            assertFailure(
                Ion.decode[Int]("0 1"),
                "ParseException",
                "Unexpected trailing content"
            )
        }

        "rejects Unicode escapes above the valid code point range" in {
            assertFailure(
                Ion.decode[String]("\"\\UFFFFFFFF\""),
                "ParseException",
                "Unicode code point"
            )
        }

        "decodes timestamp tokens as instants" in {
            val instant = java.time.Instant.parse("2024-01-02T03:04:05Z")
            assert(Ion.decode[java.time.Instant]("2024-01-02T03:04:05Z").getOrThrow == instant)
        }

        "enforces decode limits" in {
            assertFailure(
                Ion.decode[List[List[Int]]]("[[1]]", maxDepth = 1),
                "LimitExceededException",
                "Nesting depth 2 exceeds maximum 1"
            )

            assertFailure(
                Ion.decode[List[Int]]("[1,2]", maxCollectionSize = 1),
                "LimitExceededException",
                "Collection size 2 exceeds maximum 1"
            )

            assertFailure(
                Ion.decode[List[List[Int]]]("[[1]]", 1, 2),
                "LimitExceededException",
                "Nesting depth 2 exceeds maximum 1"
            )

            assertFailure(
                Ion.decode[List[Int]]("[1,2]", 2, 1),
                "LimitExceededException",
                "Collection size 2 exceeds maximum 1"
            )
        }

        "schema decode applies contextual ion limits" in {
            given Ion = Ion(Ion.Config(maxDepth = 1))

            assertFailure(
                Schema[MTSmallTeam].decodeString[Ion]("""{lead:{name:"Alice",age:30},size:5}"""),
                "LimitExceededException",
                "Nesting depth 2 exceeds maximum 1"
            )
        }
    }

    // Non-String-key Dict round-trips through the real codec. Each entry is a two-field
    // {key, value} record.
    "dictSchema non-String-key Dict" - {

        "round-trips a non-String-key Dict" in {
            val holder  = MTIntStringDict(Dict(1 -> "one", 2 -> "two", 3 -> "three"))
            val encoded = Ion.encode(holder)
            val decoded = Ion.decode[MTIntStringDict](encoded).getOrThrow
            assert(decoded.d.get(1) == Maybe("one"))
            assert(decoded.d.get(2) == Maybe("two"))
            assert(decoded.d.get(3) == Maybe("three"))
            assert(decoded.d.size == 3)
        }

        "round-trips a non-String-key Dict with non-empty collection values" in {
            val holder  = MTIntChunkDict(Dict(1 -> Chunk("a", "b"), 2 -> Chunk("c")))
            val encoded = Ion.encode(holder)
            val decoded = Ion.decode[MTIntChunkDict](encoded).getOrThrow
            assert(decoded.d.get(1) == Maybe(Chunk("a", "b")))
            assert(decoded.d.get(2) == Maybe(Chunk("c")))
        }

    }

    // OrderedDict Schema given: insertion-order round-trip.
    "OrderedDict Schema given" - {

        "OrderedDict[String, V] field preserves insertion order across encode/decode" in {
            val holder =
                MTOrderedDictConfig(OrderedDict("zeta" -> 30, "alpha" -> 3, "mike" -> 8080, "bravo" -> 5, "yankee" -> 100, "delta" -> 42))
            val encoded = Ion.encode(holder)
            val decoded = Ion.decode[MTOrderedDictConfig](encoded).getOrThrow
            assert(decoded.settings.toChunk.map(_._1) == Chunk("zeta", "alpha", "mike", "bravo", "yankee", "delta"))
        }

    }

    // omitEmptyCollections on OrderedDict/Dict fields: an empty field must be dropped from the
    // wire and round-trip back to the empty value, matching Map/Chunk/List/Vector/Set/Seq behavior.
    "omitEmptyCollections on OrderedDict/Dict fields" - {

        "empty OrderedDict[String, V] field is omitted from the wire and round-trips" in {
            val omit    = Schema[MTOrderedDictRecord].omitEmptyCollections
            val value   = MTOrderedDictRecord("alice", OrderedDict.empty[String, Int], 7)
            val encoded = omit.encodeString[Ion](value)
            assert(encoded == """{name:"alice",count:7}""", s"empty String-key OrderedDict must be dropped from the wire: $encoded")
            val decoded = omit.decodeString[Ion](encoded).getOrThrow
            assert(decoded.name == value.name && decoded.count == value.count)
            assert(decoded.settings.is(value.settings))
        }

        "empty Dict[Int, V] field (non-String key) is omitted from the wire and round-trips" in {
            val omit    = Schema[MTIntStringDictRecord].omitEmptyCollections
            val value   = MTIntStringDictRecord("alice", Dict.empty[Int, String], 7)
            val encoded = omit.encodeString[Ion](value)
            assert(encoded == """{name:"alice",count:7}""", s"empty non-String-key Dict must be dropped from the wire: $encoded")
            val decoded = omit.decodeString[Ion](encoded).getOrThrow
            assert(decoded.name == value.name && decoded.count == value.count)
            assert(decoded.byId.is(value.byId))
        }

    }

    private def wirePin[A](value: A, wire: String, schema: Schema[A])(using Frame, kyo.test.AssertScope): Unit =
        wireDecodes(value, wire, schema)
        wireWrites(value, wire, schema)
    end wirePin

    private def wireDecodes[A](value: A, wire: String, schema: Schema[A])(using Frame, kyo.test.AssertScope): Unit =
        given Schema[A] = schema
        assert(Ion.decode[A](wire) == Result.succeed(value))
    end wireDecodes

    private def wireWrites[A](value: A, wire: String, schema: Schema[A])(using Frame, kyo.test.AssertScope): Unit =
        given Schema[A] = schema
        assert(Ion.encode(value) == wire)
    end wireWrites

    "wire pins" - {
        "a record with a collection and a nested record" in {
            wirePin(
                WCValues.record,
                "{name:\"Ann\",age:41,active:true,score:2.5e0,tags:[\"a\",\"b\"],inner:{x:3,label:\"in\"}}",
                summon[Schema[WCRecord]]
            )
        }
        "fields renamed and aliased by annotation: the renamed-last order decodes and declaration order is written" in {
            wireDecodes(WCValues.renamed, "{homeCity:\"Lisbon\",user_name:\"ann\"}", summon[Schema[WCRenamed]])
            wireWrites(WCValues.renamed, "{user_name:\"ann\",homeCity:\"Lisbon\"}", summon[Schema[WCRenamed]])
        }
        "fields under a naming convention, one renamed by annotation" in {
            wirePin(WCValues.cased, "{first_name:\"Ann\",ID:9}", summon[Schema[WCCased]])
        }
        "a flattened record: the nested form decodes and the flat form is written" in {
            wireDecodes(WCValues.person, "{name:\"Ann\",address:{street:\"Main St\",zipCode:\"97201\"}}", summon[Schema[WCPerson]])
            wireWrites(WCValues.person, "{name:\"Ann\",street:\"Main St\",zipCode:\"97201\"}", summon[Schema[WCPerson]])
        }
        "a flattened record under a naming convention: the nested form decodes" in {
            wireDecodes(
                WCValues.personC,
                "{full_name:\"Ann Lee\",home_address:{cityName:\"Portland\",ZIP:\"97201\"}}",
                summon[Schema[WCPersonCased]]
            )
        }
        "a variant under the wrapper form" in {
            wirePin(WCValues.circle: WCShape, "{WCCircle:{radius:1.5e0}}", summon[Schema[WCShape]])
        }
        "a case-object variant under the wrapper form" in {
            wirePin(WCValues.empty: WCShape, "{WCEmpty:{}}", summon[Schema[WCShape]])
        }
        "a variant under a discriminator" in {
            wirePin(WCValues.circle: WCShape, "{type:\"WCCircle\",radius:1.5e0}", WCShapes.discriminated)
        }
        "a case-object variant under a discriminator" in {
            wirePin(WCValues.empty: WCShape, "{type:\"WCEmpty\"}", WCShapes.discriminated)
        }
        "a variant under the adjacent form" in {
            wirePin(WCValues.square: WCShape, "{t:\"WCSquare\",c:{side:4}}", WCShapes.adjacent)
        }
        "a variant under tupleTagged" in {
            wirePin(WCValues.square: WCShape, "[\"WCSquare\",{side:4}]", WCShapes.tupleTagged)
        }
        "a variant under tupleFlat" in {
            wirePin(WCValues.square: WCShape, "[\"WCSquare\",4]", WCShapes.tupleFlat)
        }
        "a variant under untagged" in {
            wirePin(WCValues.square: WCShape, "{side:4}", WCShapes.untagged)
        }
        "a variant under a naming convention with an alias" in {
            wirePin(WCValues.circle: WCShape, "{type:\"wc_circle\",radius:1.5e0}", WCShapes.snake)
        }
        "a renamed variant under an annotated discriminator" in {
            wirePin(WCValues.opened: WCEvent, "{kind:\"opened\",id:1}", summon[Schema[WCEvent]])
        }
        "a variant under an annotated discriminator" in {
            wirePin(WCValues.closed: WCEvent, "{kind:\"WCClosed\",id:2,reason:\"done\"}", summon[Schema[WCEvent]])
        }
        "a record of maps of every key kind: the pair form decodes and the object form is written" in {
            wireDecodes(
                WCValues.maps,
                "{byName:{a:1,b:2},byInt:[{key:1,value:\"one\"},{key:2,value:\"two\"}],byLong:[{key:10,value:true}],byChar:[{key:\"x\",value:1}],byRecord:[{key:{x:1,label:\"k\"},value:5}],byId:[{key:\"id1\",value:7}]}",
                summon[Schema[WCMaps]]
            )
            wireWrites(
                WCValues.maps,
                "{byName:{a:1,b:2},byInt:[{key:1,value:\"one\"},{key:2,value:\"two\"}],byLong:[{key:10,value:true}],byChar:[{key:\"x\",value:1}],byRecord:[{key:{x:1,label:\"k\"},value:5}],byId:{id1:7}}",
                summon[Schema[WCMaps]]
            )
        }
        "a map keyed by String" in {
            wirePin(WCValues.mapByName, "{m:{a:1,b:2}}", summon[Schema[WCMapByName]])
        }
        "a map keyed by Int" in {
            wirePin(WCValues.mapByInt, "{m:[{key:1,value:\"one\"},{key:2,value:\"two\"}]}", summon[Schema[WCMapByInt]])
        }
        "a map keyed by Long" in {
            wirePin(WCValues.mapByLong, "{m:[{key:10,value:true}]}", summon[Schema[WCMapByLong]])
        }
        "a map keyed by Char" in {
            wirePin(WCValues.mapByChar, "{m:[{key:\"x\",value:1}]}", summon[Schema[WCMapByChar]])
        }
        "a map keyed by a record" in {
            wirePin(WCValues.mapByRecord, "{m:[{key:{x:1,label:\"k\"},value:5}]}", summon[Schema[WCMapByRecord]])
        }
        "a map keyed by a string-backed type: the pair form decodes and the object form is written" in {
            wireDecodes(WCValues.mapById, "{m:[{key:\"id1\",value:7},{key:\"id2\",value:8}]}", summon[Schema[WCMapById]])
            wireWrites(WCValues.mapById, "{m:{id1:7,id2:8}}", summon[Schema[WCMapById]])
        }
        "fields holding their defaults" in {
            wirePin(WCValues.defaultsAll, "{name:\"d\",count:7,label:\"x\",size:3}", summon[Schema[WCDefaults]])
        }
        "fields overriding their defaults: the bytes written" in {
            wireWrites(WCValues.defaultsSet, "{name:\"d\",count:1,label:\"y\",note:\"n\"}", summon[Schema[WCDefaults]])
        }
        "present optional fields" in {
            wirePin(WCValues.maybePresent, "{a:1,b:\"s\",c:{x:2,label:\"c\"},d:4}", summon[Schema[WCMaybe]])
        }
        "absent optional fields" in {
            wirePin(WCValues.maybeAbsent, "{}", summon[Schema[WCMaybe]])
        }
        "absent optional fields under omitNone" in {
            wirePin(WCValues.maybeAbsent, "{}", WCMaybes.omitNone)
        }
        "a numbered variant under a discriminator" in {
            wirePin(WCNumA(5): WCNumbered, "{type:1,x:5}", summon[Schema[WCNumbered]])
        }
        "a second numbered variant under a discriminator" in {
            wirePin(WCNumB("b"): WCNumbered, "{type:2,s:\"b\"}", summon[Schema[WCNumbered]])
        }
        "a numbered variant under the wrapper form, written by name" in {
            wirePin(WCWrapA(3): WCNumberedWrapped, "{WCWrapA:{x:3}}", summon[Schema[WCNumberedWrapped]])
        }
        "a numbered case object under the wrapper form, written by name" in {
            wirePin(WCWrapB: WCNumberedWrapped, "{WCWrapB:{}}", summon[Schema[WCNumberedWrapped]])
        }
        "a tagOnly variant" in {
            wirePin(WCLow: WCLevel, "\"WCLow\"", summon[Schema[WCLevel]])
        }
        "a renamed tagOnly variant" in {
            wirePin(WCHigh: WCLevel, "\"hi\"", summon[Schema[WCLevel]])
        }
        "a known variant beside a catch-all" in {
            wirePin(WCKnown(1): WCOpen, "{type:\"WCKnown\",x:1}", summon[Schema[WCOpen]])
        }
    }

    "an Absent field whose default is Present round-trips as Absent" in {
        val decoded = Ion.decode[WCDefaults](Ion.encode(WCValues.defaultsSet))
        assert(decoded == Result.succeed(WCValues.defaultsSet), s"decoded $decoded")
    }

    "a catch-all captures an integer as an Integer, as every other format does" in {
        val decoded = Ion.decode[WCOpen]("{type:\"zzz\",y:1}")
        assert(decoded == Result.succeed(WCValues.other), s"decoded $decoded")
    }

end IonTest

object IonTest:
    case class UpstreamStruct(a: String, c: Int, d: Map[String, String], g: Int) derives CanEqual
    case class Pet(name: String, age: Int, toys: List[String]) derives CanEqual
    case class Point(x: Int, y: Int) derives CanEqual
end IonTest
