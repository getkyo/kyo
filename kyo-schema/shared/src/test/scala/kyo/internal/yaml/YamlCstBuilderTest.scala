package kyo.internal.yaml

import kyo.*

class YamlCstBuilderTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private def collect(
        run: Yaml.Events.Handler[Chunk[Yaml.Events.Event], DecodeException] => Result[DecodeException, Chunk[Yaml.Events.Event]]
    ): Chunk[Yaml.Events.Event] =
        val handler = new Yaml.Events.EventHandler[Chunk[Yaml.Events.Event], DecodeException]:
            override def event(
                context: Chunk[Yaml.Events.Event],
                event: Yaml.Events.Event
            ): Result[DecodeException, Chunk[Yaml.Events.Event]] =
                Result.succeed(context :+ event)
        run(handler).getOrThrow
    end collect

    "YamlCstBuilder.emitStream" - {

        "emits one stream frame around every document body" in {
            val stream =
                Yaml.cstAll("---\nname: Alice\n---\nname: Bob\n").getOrThrow

            val events =
                collect(h => YamlCstBuilder.emitStream(stream, Chunk.empty[Yaml.Events.Event])(h))

            assertResult(
                (
                    streamStarts = 1,
                    streamEnds = 1,
                    documentStarts = 2,
                    documentEnds = 2,
                    scalars = Chunk("name", "Alice", "name", "Bob")
                )
            ) {
                (
                    streamStarts = events.count(_.isInstanceOf[Yaml.Events.Event.StreamStart]),
                    streamEnds = events.count(_.isInstanceOf[Yaml.Events.Event.StreamEnd]),
                    documentStarts = events.count(_.isInstanceOf[Yaml.Events.Event.DocumentStart]),
                    documentEnds = events.count(_.isInstanceOf[Yaml.Events.Event.DocumentEnd]),
                    scalars = events.collect { case Yaml.Events.Event.Scalar(value, _) => value }
                )
            }
        }
    }

    "YamlCstBuilder.emitDocument" - {

        "frames a single document with one stream boundary" in {
            val document =
                Yaml.cst("name: Alice\n").getOrThrow

            val events =
                collect(h => YamlCstBuilder.emitDocument(document, Chunk.empty[Yaml.Events.Event])(h))

            assertResult((streamStarts = 1, streamEnds = 1, documentStarts = 1, documentEnds = 1)) {
                (
                    streamStarts = events.count(_.isInstanceOf[Yaml.Events.Event.StreamStart]),
                    streamEnds = events.count(_.isInstanceOf[Yaml.Events.Event.StreamEnd]),
                    documentStarts = events.count(_.isInstanceOf[Yaml.Events.Event.DocumentStart]),
                    documentEnds = events.count(_.isInstanceOf[Yaml.Events.Event.DocumentEnd])
                )
            }
        }
    }
end YamlCstBuilderTest
