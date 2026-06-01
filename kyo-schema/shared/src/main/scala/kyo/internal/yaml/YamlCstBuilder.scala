package kyo.internal.yaml

import kyo.*
import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

private[kyo] object YamlCstBuilder:

    import Yaml.Cst
    import Yaml.Events.CollectionKind
    import Yaml.Events.Event

    def fromEvents(events: Chunk[Event])(using Frame): Result[DecodeException, Cst.Document] =
        rootEvent(events).flatMap {
            case Event.Scalar(value, meta) =>
                val span = Cst.SourceSpan(meta.mark, meta.mark)
                Result.succeed(Cst.Document(
                    Maybe(Cst.Node.Scalar(value, scalarSyntax(meta.style), meta, span, Absent)),
                    Chunk.empty,
                    Chunk.empty,
                    span,
                    Absent
                ))
            case Event.Alias(name, mark) =>
                val span = Cst.SourceSpan(mark, mark)
                Result.succeed(Cst.Document(
                    Maybe(Cst.Node.Alias(name, Cst.AliasSyntax.Canonical, span, Absent)),
                    Chunk.empty,
                    Chunk.empty,
                    span,
                    Absent
                ))
            case event =>
                Result.fail(ParseException(Yaml(), "", "Unsupported YAML CST event stream", Nil, eventMark(event).index))
        }
    end fromEvents

    def fromValue[A](
        value: A
    )(using schema: Schema[A], writerConfig: Yaml.WriterConfig, frame: Frame): Result[DecodeException, Cst.Document] =
        val buffer    = ArrayBuffer.empty[Event]
        val collector = Collector[DecodeException](buffer)
        Yaml.Events.write(value, ())(collector).flatMap(_ => fromEvents(Chunk.from(buffer)))
    end fromValue

    def events(document: Cst.Document): Chunk[Event] =
        val buffer    = ArrayBuffer.empty[Event]
        val collector = Collector[Nothing](buffer)
        val _         = emitDocument(document, ())(collector).getOrThrow
        Chunk.from(buffer)
    end events

    def emitDocument[Ctx, Err](
        document: Cst.Document,
        context: Ctx
    )(handler: Yaml.Events.Handler[Ctx, Err]): Result[Err, Ctx] =
        val start = document.span.start
        val end   = document.span.end
        handler.streamStart(context, start).flatMap { c1 =>
            handler.documentStart(c1, start).flatMap { c2 =>
                document.root match
                    case Present(root) => emitNode(root, c2)(handler)
                    case Absent        => Result.succeed(c2)
                end match
            }.flatMap { c3 =>
                handler.documentEnd(c3, end)
            }.flatMap { c4 =>
                handler.streamEnd(c4, end)
            }
        }
    end emitDocument

    private def emitNode[Ctx, Err](
        node: Cst.Node,
        context: Ctx
    )(handler: Yaml.Events.Handler[Ctx, Err]): Result[Err, Ctx] =
        node match
            case Cst.Node.Scalar(value, _, meta, _, _) =>
                handler.scalar(context, value, meta)
            case Cst.Node.Alias(name, _, span, _) =>
                handler.alias(context, name, span.start)
            case Cst.Node.Mapping(entries, _, meta, span, _) =>
                handler.mappingStart(context, meta, Maybe(entries.size)).flatMap { c1 =>
                    emitMappingEntries(entries, 0, c1)(handler).flatMap { c2 =>
                        handler.collectionEnd(c2, CollectionKind.Mapping, span.end)
                    }
                }
            case Cst.Node.Sequence(entries, _, meta, span, _) =>
                handler.sequenceStart(context, meta, Maybe(entries.size)).flatMap { c1 =>
                    emitSequenceEntries(entries, 0, c1)(handler).flatMap { c2 =>
                        handler.collectionEnd(c2, CollectionKind.Sequence, span.end)
                    }
                }
        end match
    end emitNode

    private def rootEvent(events: Chunk[Event])(using Frame): Result[DecodeException, Event] =
        loopRoot(events, 0, Absent)
    end rootEvent

    @tailrec private def loopRoot(events: Chunk[Event], index: Int, root: Maybe[Event])(using Frame): Result[DecodeException, Event] =
        if index >= events.size then
            root match
                case Present(event) => Result.succeed(event)
                case Absent         => Result.fail(ParseException(Yaml(), "", "Unsupported YAML CST event stream", Nil, 0))
        else
            val event = events(index)
            event match
                case Event.StreamStart(_) | Event.DocumentStart(_) | Event.DocumentEnd(_) | Event.StreamEnd(_) =>
                    loopRoot(events, index + 1, root)
                case Event.Scalar(_, _) | Event.Alias(_, _) =>
                    root match
                        case Absent =>
                            loopRoot(events, index + 1, Maybe(event))
                        case Present(_) =>
                            Result.fail(ParseException(Yaml(), "", "Unsupported YAML CST event stream", Nil, eventMark(event).index))
                    end match
                case _ =>
                    Result.fail(ParseException(Yaml(), "", "Unsupported YAML CST event stream", Nil, eventMark(event).index))
            end match
        end if
    end loopRoot

    @tailrec private def emitMappingEntries[Ctx, Err](
        entries: Chunk[Cst.MappingEntry],
        index: Int,
        context: Ctx
    )(handler: Yaml.Events.Handler[Ctx, Err]): Result[Err, Ctx] =
        if index >= entries.size then Result.succeed(context)
        else
            val entry = entries(index)
            emitNode(entry.key, context)(handler) match
                case Result.Success(c1) =>
                    emitNode(entry.value, c1)(handler) match
                        case Result.Success(next) => emitMappingEntries(entries, index + 1, next)(handler)
                        case Result.Failure(e)    => Result.fail(e)
                        case Result.Panic(e)      => Result.panic(e)
                case Result.Failure(e) => Result.fail(e)
                case Result.Panic(e)   => Result.panic(e)
            end match
    end emitMappingEntries

    @tailrec private def emitSequenceEntries[Ctx, Err](
        entries: Chunk[Cst.SequenceEntry],
        index: Int,
        context: Ctx
    )(handler: Yaml.Events.Handler[Ctx, Err]): Result[Err, Ctx] =
        if index >= entries.size then Result.succeed(context)
        else
            emitNode(entries(index).value, context)(handler) match
                case Result.Success(next) => emitSequenceEntries(entries, index + 1, next)(handler)
                case Result.Failure(e)    => Result.fail(e)
                case Result.Panic(e)      => Result.panic(e)
            end match
    end emitSequenceEntries

    private def scalarSyntax(style: Yaml.ScalarStyle): Cst.ScalarSyntax =
        style match
            case Yaml.ScalarStyle.Plain        => Cst.ScalarSyntax.Plain
            case Yaml.ScalarStyle.SingleQuoted => Cst.ScalarSyntax.SingleQuoted
            case Yaml.ScalarStyle.DoubleQuoted => Cst.ScalarSyntax.DoubleQuoted
            case Yaml.ScalarStyle.Literal      => Cst.ScalarSyntax.Literal
            case Yaml.ScalarStyle.Folded       => Cst.ScalarSyntax.Folded
    end scalarSyntax

    private def eventMark(event: Event): Yaml.Mark =
        event match
            case Event.StreamStart(mark)      => mark
            case Event.DocumentStart(mark)    => mark
            case Event.MappingStart(meta, _)  => meta.mark
            case Event.SequenceStart(meta, _) => meta.mark
            case Event.Scalar(_, meta)        => meta.mark
            case Event.Alias(_, mark)         => mark
            case Event.CollectionEnd(_, mark) => mark
            case Event.DocumentEnd(mark)      => mark
            case Event.StreamEnd(mark)        => mark
    end eventMark

    final private class Collector[Err](buffer: ArrayBuffer[Event]) extends Yaml.Events.Handler[Unit, Err]:

        override def streamStart(context: Unit, mark: Yaml.Mark): Result[Err, Unit] =
            buffer += Event.StreamStart(mark)
            Result.unit
        end streamStart

        override def documentStart(context: Unit, mark: Yaml.Mark): Result[Err, Unit] =
            buffer += Event.DocumentStart(mark)
            Result.unit
        end documentStart

        override def mappingStart(context: Unit, meta: Yaml.Meta, size: Maybe[Int]): Result[Err, Unit] =
            buffer += Event.MappingStart(meta, size)
            Result.unit
        end mappingStart

        override def sequenceStart(context: Unit, meta: Yaml.Meta, size: Maybe[Int]): Result[Err, Unit] =
            buffer += Event.SequenceStart(meta, size)
            Result.unit
        end sequenceStart

        override def scalar(context: Unit, value: String, meta: Yaml.ScalarMeta): Result[Err, Unit] =
            buffer += Event.Scalar(value, meta)
            Result.unit
        end scalar

        override def alias(context: Unit, name: Yaml.Anchor, mark: Yaml.Mark): Result[Err, Unit] =
            buffer += Event.Alias(name, mark)
            Result.unit
        end alias

        override def collectionEnd(context: Unit, kind: CollectionKind, mark: Yaml.Mark): Result[Err, Unit] =
            buffer += Event.CollectionEnd(kind, mark)
            Result.unit
        end collectionEnd

        override def documentEnd(context: Unit, mark: Yaml.Mark): Result[Err, Unit] =
            buffer += Event.DocumentEnd(mark)
            Result.unit
        end documentEnd

        override def streamEnd(context: Unit, mark: Yaml.Mark): Result[Err, Unit] =
            buffer += Event.StreamEnd(mark)
            Result.unit
        end streamEnd
    end Collector
end YamlCstBuilder
