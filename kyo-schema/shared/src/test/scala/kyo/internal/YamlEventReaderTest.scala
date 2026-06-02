package kyo.internal

import kyo.*

class YamlEventReaderTest extends Test:

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

        "resolves YAML 1.2 scalar values" in {
            assert(decode[Int](scalarDocument("0o7")) == Result.succeed(7))
            assert(decode[Int](scalarDocument("0x3A")) == Result.succeed(58))
            assert(decode[Double](scalarDocument(".5")) == Result.succeed(0.5d))
            assert(decode[String](scalarDocument("NO")) == Result.succeed("NO"))
        }

        "resolves YAML 1.1 scalar values" in {
            assert(decode[Boolean](scalarDocument("NO"), Yaml.SpecVersion.Yaml11) == Result.succeed(false))
            assert(decode[Int](scalarDocument("0b1010"), Yaml.SpecVersion.Yaml11) == Result.succeed(10))
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

            discard(captured.arrayStart())
            assert(captured.hasNextElement())
            assert(captured.int() == 1)
            assert(captured.hasNextElement())
            assert(captured.int() == 2)
            assert(!captured.hasNextElement())
            captured.arrayEnd()

            assert(reader.hasNextField())
            reader.fieldParse()
            assert(reader.lastFieldName() == "next")
            assert(reader.string() == "done")
        }
    }
end YamlEventReaderTest
