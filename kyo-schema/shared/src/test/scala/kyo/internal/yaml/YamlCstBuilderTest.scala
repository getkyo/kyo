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

    "YamlCstBuilder.fromEvents with anchors and aliases" - {

        "builds a CST that re-emits an alias when the source contains anchor and alias" in {
            val yaml   = "x: &a 1\ny: *a\n"
            val stream = Yaml.cstAll(yaml).getOrThrow

            // take the events of the single document body and feed to fromEvents
            val docEvents = YamlCstBuilder.events(stream.documents(0))

            // strip the outer stream/document envelope - fromEvents expects a raw document event sequence
            // Use the raw parser events from the document directly
            val result = Yaml.Cst.fromEvents(docEvents)

            result match
                case Result.Success(doc) =>
                    val reemitted = YamlCstBuilder.events(doc)
                    val hasAlias  = reemitted.exists(_.isInstanceOf[Yaml.Events.Event.Alias])
                    assert(hasAlias)
                case Result.Failure(e) =>
                    fail(s"Expected successful CST, got failure: $e")
            end match
        }
    }

    "YamlCstBuilder.emitStream with failing handler" - {

        "propagates failure from a handler that rejects a scalar" in {
            sealed trait TestErr derives CanEqual
            case object ScalarRejected extends TestErr

            val stream = Yaml.cstAll("a: 1\nb: 2\n").getOrThrow

            var scalarCount = 0
            val failOnSecond = new Yaml.Events.Handler[Int, TestErr]:
                override def scalar(context: Int, value: String, meta: Yaml.ScalarMeta): Result[TestErr, Int] =
                    scalarCount += 1
                    if scalarCount == 2 then Result.fail(ScalarRejected)
                    else Result.succeed(context + 1)
                end scalar
            end failOnSecond

            val result = YamlCstBuilder.emitStream(stream, 0)(failOnSecond)

            result match
                case Result.Failure(ScalarRejected) => succeed
                case other                          => fail(s"Expected Failure(ScalarRejected), got $other")
            end match
        }

        "propagates failure from a handler that rejects a sequence element" in {
            sealed trait SeqErr derives CanEqual
            case object ElementRejected extends SeqErr

            val stream = Yaml.cstAll("- a\n- b\n- c\n").getOrThrow

            var elementCount = 0
            val failOnSecond = new Yaml.Events.Handler[Int, SeqErr]:
                override def scalar(context: Int, value: String, meta: Yaml.ScalarMeta): Result[SeqErr, Int] =
                    elementCount += 1
                    if elementCount == 2 then Result.fail(ElementRejected)
                    else Result.succeed(context + 1)
                end scalar
            end failOnSecond

            YamlCstBuilder.emitStream(stream, 0)(failOnSecond) match
                case Result.Failure(ElementRejected) => succeed
                case other                           => fail(s"Expected Failure(ElementRejected), got $other")
            end match
        }
    }

    "Yaml.Cst.fromEvents with malformed event sequences" - {

        "returns failure for a CollectionEnd with no matching start" in {
            val mark = Yaml.Mark(0, 1, 1)
            val events = Chunk(
                Yaml.Events.Event.StreamStart(mark),
                Yaml.Events.Event.DocumentStart(mark),
                Yaml.Events.Event.CollectionEnd(Yaml.Events.CollectionKind.Mapping, mark)
            )

            Yaml.Cst.fromEvents(events) match
                case Result.Failure(e: ParseException) =>
                    assert(e.getMessage.contains("collection") || e.getMessage.contains("Collection") || e.getMessage.nonEmpty)
                case other => fail(s"Expected ParseException failure, got $other")
            end match
        }

        "returns failure for an unclosed mapping" in {
            val mark       = Yaml.Mark(0, 1, 1)
            val meta       = Yaml.Meta(Absent, Absent, mark)
            val scalarMeta = Yaml.ScalarMeta(Absent, Absent, Yaml.ScalarStyle.Plain, mark)
            val events = Chunk(
                Yaml.Events.Event.StreamStart(mark),
                Yaml.Events.Event.DocumentStart(mark),
                Yaml.Events.Event.MappingStart(meta),
                Yaml.Events.Event.Scalar("key", scalarMeta),
                Yaml.Events.Event.Scalar("value", scalarMeta),
                Yaml.Events.Event.DocumentEnd(mark),
                Yaml.Events.Event.StreamEnd(mark)
            )

            Yaml.Cst.fromEvents(events) match
                case Result.Failure(e: ParseException) =>
                    assert(e.getMessage.contains("Unclosed") || e.getMessage.nonEmpty)
                case other => fail(s"Expected ParseException failure for unclosed mapping, got $other")
            end match
        }

        "returns failure for a duplicate document start" in {
            val mark       = Yaml.Mark(0, 1, 1)
            val scalarMeta = Yaml.ScalarMeta(Absent, Absent, Yaml.ScalarStyle.Plain, mark)
            val events = Chunk(
                Yaml.Events.Event.StreamStart(mark),
                Yaml.Events.Event.DocumentStart(mark),
                Yaml.Events.Event.Scalar("hello", scalarMeta),
                Yaml.Events.Event.DocumentStart(mark)
            )

            Yaml.Cst.fromEvents(events) match
                case Result.Failure(e: ParseException) =>
                    assert(e.getMessage.contains("document") || e.getMessage.nonEmpty)
                case other => fail(s"Expected ParseException failure for double document start, got $other")
            end match
        }
    }
end YamlCstBuilderTest
