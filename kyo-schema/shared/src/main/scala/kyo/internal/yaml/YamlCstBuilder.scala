package kyo.internal.yaml

import kyo.*
import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

private[kyo] object YamlCstBuilder:

    import Yaml.Cst
    import Yaml.Events.CollectionKind
    import Yaml.Events.Event

    def fromEvents(events: Chunk[Event])(using Frame): Result[DecodeException, Cst.Document] =
        val builder = new Builder()
        replay(events, 0, builder).flatMap(_ => builder.result)
    end fromEvents

    def fromValue[A](
        value: A
    )(using schema: Schema[A], writerConfig: Yaml.WriterConfig, frame: Frame): Result[DecodeException, Cst.Document] =
        val builder = new Builder()
        Yaml.Events.write(value, ())(builder).flatMap(_ => builder.result)
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

    @tailrec private def replay(events: Chunk[Event], index: Int, builder: Builder): Result[DecodeException, Unit] =
        if index >= events.size then
            Result.unit
        else
            builder.event((), events(index)) match
                case Result.Success(_) => replay(events, index + 1, builder)
                case Result.Failure(e) => Result.fail(e)
                case Result.Panic(e)   => Result.panic(e)
            end match
        end if
    end replay

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

    sealed private trait FrameState
    final private class MappingState(
        val meta: Yaml.Meta,
        var entries: Chunk[Cst.MappingEntry],
        var pendingKey: Maybe[Cst.Node]
    ) extends FrameState
    final private class SequenceState(
        val meta: Yaml.Meta,
        var entries: Chunk[Cst.SequenceEntry]
    ) extends FrameState

    final private class Builder(using Frame) extends Yaml.Events.Handler[Unit, DecodeException]:
        private var root: Maybe[Cst.Node]           = Absent
        private var stack: List[FrameState]         = Nil
        private var documentStart: Maybe[Yaml.Mark] = Absent
        private var documentEnd: Maybe[Yaml.Mark]   = Absent
        private var lastMark: Yaml.Mark             = Yaml.Mark(0, 1, 1)

        def result: Result[DecodeException, Cst.Document] =
            stack match
                case _ :: _ =>
                    Result.fail(parseError("Unclosed YAML collection", lastMark))
                case Nil =>
                    root match
                        case Present(node) =>
                            val nodeSpan = span(node)
                            val docSpan = Cst.SourceSpan(
                                documentStart.getOrElse(nodeSpan.start),
                                documentEnd.getOrElse(nodeSpan.end)
                            )
                            Result.succeed(Cst.Document(Maybe(node), Chunk.empty, Chunk.empty, docSpan, Absent))
                        case Absent =>
                            val mark    = documentStart.orElse(documentEnd).getOrElse(lastMark)
                            val docSpan = Cst.SourceSpan(mark, mark)
                            Result.succeed(Cst.Document(Absent, Chunk.empty, Chunk.empty, docSpan, Absent))
                    end match
            end match
        end result

        override def streamStart(context: Unit, mark: Yaml.Mark): Result[DecodeException, Unit] =
            lastMark = mark
            Result.unit
        end streamStart

        override def documentStart(context: Unit, mark: Yaml.Mark): Result[DecodeException, Unit] =
            documentStart = Maybe(mark)
            lastMark = mark
            Result.unit
        end documentStart

        override def documentEnd(context: Unit, mark: Yaml.Mark): Result[DecodeException, Unit] =
            documentEnd = Maybe(mark)
            lastMark = mark
            Result.unit
        end documentEnd

        override def streamEnd(context: Unit, mark: Yaml.Mark): Result[DecodeException, Unit] =
            lastMark = mark
            Result.unit
        end streamEnd

        override def mappingStart(context: Unit, meta: Yaml.Meta, size: Maybe[Int]): Result[DecodeException, Unit] =
            lastMark = meta.mark
            stack = MappingState(meta, Chunk.empty, Absent) :: stack
            Result.unit
        end mappingStart

        override def sequenceStart(context: Unit, meta: Yaml.Meta, size: Maybe[Int]): Result[DecodeException, Unit] =
            lastMark = meta.mark
            stack = SequenceState(meta, Chunk.empty) :: stack
            Result.unit
        end sequenceStart

        override def scalar(context: Unit, value: String, meta: Yaml.ScalarMeta): Result[DecodeException, Unit] =
            lastMark = meta.mark
            val nodeSpan = Cst.SourceSpan(meta.mark, meta.mark)
            addNode(Cst.Node.Scalar(value, Cst.ScalarSyntax.Canonical, meta, nodeSpan, Absent))
        end scalar

        override def alias(context: Unit, name: Yaml.Anchor, mark: Yaml.Mark): Result[DecodeException, Unit] =
            lastMark = mark
            val nodeSpan = Cst.SourceSpan(mark, mark)
            addNode(Cst.Node.Alias(name, Cst.AliasSyntax.Canonical, nodeSpan, Absent))
        end alias

        override def collectionEnd(context: Unit, kind: CollectionKind, mark: Yaml.Mark): Result[DecodeException, Unit] =
            lastMark = mark
            stack match
                case (state: MappingState) :: rest =>
                    if kind != CollectionKind.Mapping then
                        Result.fail(parseError("Unexpected YAML sequence end", mark))
                    else
                        state.pendingKey match
                            case Present(_) =>
                                Result.fail(parseError("Expected YAML mapping value", mark))
                            case Absent =>
                                stack = rest
                                val nodeSpan = Cst.SourceSpan(state.meta.mark, mark)
                                addNode(Cst.Node.Mapping(
                                    state.entries,
                                    Cst.MappingSyntax.Canonical,
                                    state.meta,
                                    nodeSpan,
                                    Absent
                                ))
                        end match
                    end if
                case (state: SequenceState) :: rest =>
                    if kind != CollectionKind.Sequence then
                        Result.fail(parseError("Unexpected YAML mapping end", mark))
                    else
                        stack = rest
                        val nodeSpan = Cst.SourceSpan(state.meta.mark, mark)
                        addNode(Cst.Node.Sequence(
                            state.entries,
                            Cst.SequenceSyntax.Canonical,
                            state.meta,
                            nodeSpan,
                            Absent
                        ))
                    end if
                case Nil =>
                    Result.fail(parseError("Unexpected YAML collection end", mark))
            end match
        end collectionEnd

        private def addNode(node: Cst.Node): Result[DecodeException, Unit] =
            stack match
                case (state: MappingState) :: _ =>
                    state.pendingKey match
                        case Present(key) =>
                            state.entries = state.entries :+ Cst.MappingEntry(key, node, Cst.SourceSpan(span(key).start, span(node).end))
                            state.pendingKey = Absent
                        case Absent =>
                            state.pendingKey = Maybe(node)
                    end match
                    Result.unit
                case (state: SequenceState) :: _ =>
                    state.entries = state.entries :+ Cst.SequenceEntry(node, span(node))
                    Result.unit
                case Nil =>
                    root match
                        case Absent =>
                            root = Maybe(node)
                            Result.unit
                        case Present(_) =>
                            Result.fail(parseError("Unexpected YAML node after document root", span(node).start))
                    end match
            end match
        end addNode
    end Builder

    private def span(node: Cst.Node): Cst.SourceSpan =
        node match
            case Cst.Node.Mapping(_, _, _, span, _)  => span
            case Cst.Node.Sequence(_, _, _, span, _) => span
            case Cst.Node.Scalar(_, _, _, span, _)   => span
            case Cst.Node.Alias(_, _, span, _)       => span
    end span

    private def parseError(message: String, mark: Yaml.Mark)(using Frame): ParseException =
        ParseException(Yaml(), "", message, Nil, mark.index)
    end parseError

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
