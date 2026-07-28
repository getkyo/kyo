package kyo.internal.yaml

import java.util.Base64
import kyo.*
import scala.NamedTuple.*

class YamlEventReaderTest extends kyo.test.Test[Any]:

    import Yaml.Events.CollectionKind
    import Yaml.Events.Event

    given CanEqual[Any, Any] = CanEqual.derived

    private val mark       = Yaml.Mark(0, 1, 1)
    private val meta       = Yaml.Meta(Absent, Absent, mark)
    private val scalarMeta = Yaml.ScalarMeta(Absent, Absent, Yaml.ScalarStyle.Plain, mark)

    private def document(events: Event*): Chunk[Event] =
        Chunk(Event.StreamStart(mark), Event.DocumentStart(mark)) ++
            Chunk.from(events) ++
            Chunk(Event.DocumentEnd(mark), Event.StreamEnd(mark))
    end document

    private def scalar(value: String): Event =
        Event.Scalar(value, scalarMeta)
    end scalar

    private def scalarDocument(value: String): Chunk[Event] =
        document(scalar(value))
    end scalarDocument

    private def decode[A](
        events: Chunk[Event],
        yamlVersion: Yaml.SpecVersion = Yaml.SpecVersion.Yaml12
    )(using schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        Result.catching[DecodeException] {
            schema.readFrom(YamlEventReader(events, yamlVersion))
        }
    end decode

    "YamlEventReader" - {

        "decodes a case class from mapping events" in {
            val events = document(
                Event.MappingStart(meta),
                scalar("name"),
                scalar("Alice"),
                scalar("age"),
                scalar("30"),
                Event.CollectionEnd(CollectionKind.Mapping, mark)
            )

            assert(decode[MTPerson](events) == Result.succeed(MTPerson("Alice", 30)))
        }

        "decodes sequence events" in {
            val events = document(
                Event.SequenceStart(meta),
                scalar("1"),
                scalar("2"),
                scalar("3"),
                Event.CollectionEnd(CollectionKind.Sequence, mark)
            )

            assert(decode[List[Int]](events) == Result.succeed(List(1, 2, 3)))
        }

        "decodes nested event values" in {
            val events = document(
                Event.MappingStart(meta),
                scalar("lead"),
                Event.MappingStart(meta),
                scalar("name"),
                scalar("Alice"),
                scalar("age"),
                scalar("30"),
                Event.CollectionEnd(CollectionKind.Mapping, mark),
                scalar("size"),
                scalar("5"),
                Event.CollectionEnd(CollectionKind.Mapping, mark)
            )

            assert(decode[MTSmallTeam](events) == Result.succeed(MTSmallTeam(MTPerson("Alice", 30), 5)))
        }

        "reads fields and map entries directly from mapping events" in {
            val reader = YamlEventReader(document(
                Event.MappingStart(meta),
                scalar("name"),
                scalar("Alice"),
                scalar("city"),
                scalar("Paris"),
                Event.CollectionEnd(CollectionKind.Mapping, mark)
            ))

            val observed = (
                mapStart = reader.mapStart(),
                hasNameEntry = reader.hasNextEntry(),
                nameField = reader.field(),
                nameValue = reader.string(),
                hasCityEntry = reader.hasNextEntry(),
                cityField = reader.field(),
                cityValue = reader.string(),
                hasMoreEntries = reader.hasNextEntry()
            )
            reader.mapEnd()

            assert(observed.toSeqMap == (
                mapStart = -1,
                hasNameEntry = true,
                nameField = "name",
                nameValue = "Alice",
                hasCityEntry = true,
                cityField = "city",
                cityValue = "Paris",
                hasMoreEntries = false
            ).toSeqMap)
        }

        "reads scalar primitives directly from event values" in {
            def reader(value: String): YamlEventReader =
                YamlEventReader(scalarDocument(value))

            val bytes = Array[Byte](1, 2, 3)
            val observed = (
                longValue = reader("9007199254740993").long(),
                floatValue = reader("1.25").float(),
                shortValue = reader("123").short(),
                byteValue = reader("12").byte(),
                charValue = reader("K").char(),
                bytesValue = reader(Base64.getEncoder.encodeToString(bytes)).bytes().toArray.toSeq,
                bigIntValue = reader("123456789012345678901234567890").bigInt(),
                bigDecimalValue = reader("12345.6789").bigDecimal(),
                instantValue = reader("2026-06-01T12:34:56Z").instant(),
                durationValue = reader("PT2H3M").duration()
            )

            assert(observed.toSeqMap == (
                longValue = 9007199254740993L,
                floatValue = 1.25f,
                shortValue = 123.toShort,
                byteValue = 12.toByte,
                charValue = 'K',
                bytesValue = bytes.toSeq,
                bigIntValue = BigInt("123456789012345678901234567890"),
                bigDecimalValue = BigDecimal("12345.6789"),
                instantValue = java.time.Instant.parse("2026-06-01T12:34:56Z"),
                durationValue = java.time.Duration.parse("PT2H3M")
            ).toSeqMap)
        }

        "checks nil without consuming non-null scalar values" in {
            val nullReader  = YamlEventReader(scalarDocument("~"))
            val valueReader = YamlEventReader(scalarDocument("present"))

            assert((
                nullIsNil = nullReader.isNil(),
                valueIsNil = valueReader.isNil(),
                value = valueReader.string()
            ).toSeqMap == (nullIsNil = true, valueIsNil = false, value = "present").toSeqMap)
        }

        "skips one sequence element and continues with the next event value" in {
            val reader = YamlEventReader(document(
                Event.SequenceStart(meta),
                scalar("first"),
                scalar("second"),
                Event.CollectionEnd(CollectionKind.Sequence, mark)
            ))

            val start    = reader.arrayStart()
            val hasFirst = reader.hasNextElement()
            reader.skip()
            val observed = (
                start = start,
                hasFirst = hasFirst,
                hasSecond = reader.hasNextElement(),
                second = reader.string(),
                hasMore = reader.hasNextElement()
            )
            reader.arrayEnd()

            assert(observed.toSeqMap == (start = -1, hasFirst = true, hasSecond = true, second = "second", hasMore = false).toSeqMap)
        }

        "resolves YAML 1.2 scalar values" in {
            val observed = (
                octal = decode[Int](scalarDocument("0o7")),
                hex = decode[Int](scalarDocument("0x3A")),
                leadingDot = decode[Double](scalarDocument(".5")),
                norway = decode[String](scalarDocument("NO"))
            )

            assert(observed.toSeqMap == (
                octal = Result.succeed(7),
                hex = Result.succeed(58),
                leadingDot = Result.succeed(0.5d),
                norway = Result.succeed("NO")
            ).toSeqMap)
        }

        "resolves YAML 1.1 scalar values" in {
            val observed = (
                legacyBool = decode[Boolean](scalarDocument("NO"), Yaml.SpecVersion.Yaml11),
                binary = decode[Int](scalarDocument("0b1010"), Yaml.SpecVersion.Yaml11)
            )

            assert(observed.toSeqMap == (legacyBool = Result.succeed(false), binary = Result.succeed(10)).toSeqMap)
        }

        "captures a subtree reader and advances the parent" in {
            val reader = YamlEventReader(document(
                Event.MappingStart(meta),
                scalar("value"),
                Event.SequenceStart(meta),
                scalar("1"),
                scalar("2"),
                Event.CollectionEnd(CollectionKind.Sequence, mark),
                scalar("next"),
                scalar("done"),
                Event.CollectionEnd(CollectionKind.Mapping, mark)
            ))

            discard(reader.objectStart())
            reader.fieldParse()

            val captured = reader.captureValue()

            val observed = (
                arrayStart = captured.arrayStart(),
                hasFirstElement = captured.hasNextElement(),
                firstElement = captured.int(),
                hasSecondElement = captured.hasNextElement(),
                secondElement = captured.int(),
                hasMoreElements = captured.hasNextElement()
            )
            captured.arrayEnd()

            val hasNextField = reader.hasNextField()
            reader.fieldParse()
            val parentObserved = (
                parentHasNextField = hasNextField,
                parentField = reader.lastFieldName(),
                parentValue = reader.string()
            )

            assert((
                arrayStart = observed.arrayStart,
                hasFirstElement = observed.hasFirstElement,
                firstElement = observed.firstElement,
                hasSecondElement = observed.hasSecondElement,
                secondElement = observed.secondElement,
                hasMoreElements = observed.hasMoreElements,
                parentHasNextField = parentObserved.parentHasNextField,
                parentField = parentObserved.parentField,
                parentValue = parentObserved.parentValue
            ).toSeqMap == (
                arrayStart = -1,
                hasFirstElement = true,
                firstElement = 1,
                hasSecondElement = true,
                secondElement = 2,
                hasMoreElements = false,
                parentHasNextField = true,
                parentField = "next",
                parentValue = "done"
            ).toSeqMap)
        }
    }
end YamlEventReaderTest
