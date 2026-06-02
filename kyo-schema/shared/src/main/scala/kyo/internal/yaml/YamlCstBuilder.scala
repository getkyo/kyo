package kyo.internal.yaml

import kyo.*
import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

private[kyo] object YamlCstBuilder:

    import Yaml.Cst
    import Yaml.Events.CollectionKind
    import Yaml.Events.Event

    /** Merges the non-empty top-level mappings of a CST stream into a single document, mirroring
      * [[YamlDocuments.mergeTopLevelMappings]] semantics for the source-backed path. Documents whose root is not a mapping
      * are skipped. Entry order is preserved across documents.
      */
    def mergeTopLevelMappings(stream: Cst.Stream): Cst.Document =
        val entries =
            stream.documents.foldLeft(Chunk.empty[Cst.MappingEntry]) { (acc, document) =>
                document.root match
                    case Present(Cst.Node.Mapping(mappingEntries, _, _, _, _)) => acc ++ mappingEntries
                    case _                                                     => acc
            }
        val mark = stream.span.start
        val span = Cst.SourceSpan(mark, stream.span.end)
        val mapping =
            Cst.Node.Mapping(entries, Cst.MappingSyntax.Canonical, Yaml.Meta(Absent, Absent, mark), span, Absent)
        Cst.Document(Maybe(mapping), Chunk.empty, Chunk.empty, span, Absent)
    end mergeTopLevelMappings

    def fromEvents(events: Chunk[Event])(using Frame): Result[DecodeException, Cst.Document] =
        val builder = new Builder(BuilderMode.PreserveSyntax)
        replay(events, 0, builder).flatMap(_ => builder.result)
    end fromEvents

    def fromValue[A](
        value: A
    )(using schema: Schema[A], writerConfig: Yaml.WriterConfig, frame: Frame): Result[DecodeException, Cst.Document] =
        val builder = new Builder(BuilderMode.Canonical)
        Yaml.Events.write(value, ())(builder).flatMap(_ => builder.result)
    end fromValue

    def events(document: Cst.Document): Chunk[Event] =
        val buffer    = ArrayBuffer.empty[Event]
        val collector = Collector[Nothing](buffer)
        // Collector's error type is Nothing, so emitDocument cannot fail; getOrThrow only surfaces an unexpected panic.
        val _ = emitDocument(document, ())(collector).getOrThrow
        Chunk.from(buffer)
    end events

    def emitDocument[Ctx, Err](
        document: Cst.Document,
        context: Ctx
    )(handler: Yaml.Events.Handler[Ctx, Err]): Result[Err, Ctx] =
        handler.streamStart(context, document.span.start).flatMap { c1 =>
            emitDocumentBody(document, c1)(handler).flatMap { c2 =>
                handler.streamEnd(c2, document.span.end)
            }
        }
    end emitDocument

    /** Emits documentStart, the document body, and documentEnd to the handler. Does not emit stream boundaries. */
    def emitDocumentBody[Ctx, Err](
        document: Cst.Document,
        context: Ctx
    )(handler: Yaml.Events.Handler[Ctx, Err]): Result[Err, Ctx] =
        handler.documentStart(context, document.span.start).flatMap { c1 =>
            (document.root match
                case Present(root) => emitNode(root, c1)(handler)
                case Absent        => Result.succeed(c1)
            ).flatMap { c2 =>
                handler.documentEnd(c2, document.span.end)
            }
        }
    end emitDocumentBody

    /** Emits one streamStart, then documentStart/body/documentEnd for each document in the stream, then one streamEnd. */
    def emitStream[Ctx, Err](
        stream: Cst.Stream,
        context: Ctx
    )(handler: Yaml.Events.Handler[Ctx, Err]): Result[Err, Ctx] =
        handler.streamStart(context, stream.span.start).flatMap { c1 =>
            emitStreamDocuments(stream, handler, 0, c1).flatMap { last =>
                handler.streamEnd(last, stream.span.end)
            }
        }
    end emitStream

    @tailrec private def emitStreamDocuments[Ctx, Err](
        stream: Cst.Stream,
        handler: Yaml.Events.Handler[Ctx, Err],
        index: Int,
        context: Ctx
    ): Result[Err, Ctx] =
        if index >= stream.documents.size then Result.succeed(context)
        else
            emitDocumentBody(stream.documents(index), context)(handler) match
                case Result.Success(next) => emitStreamDocuments(stream, handler, index + 1, next)
                case Result.Failure(e)    => Result.fail(e)
                case Result.Panic(e)      => Result.panic(e)
            end match
    end emitStreamDocuments

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
                // The CST always knows its entry count, so it emits a known size; consumers treat collection size as advisory.
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
        val entries: ArrayBuffer[Cst.MappingEntry],
        var pendingKey: Maybe[Cst.Node]
    ) extends FrameState
    final private class SequenceState(
        val meta: Yaml.Meta,
        val entries: ArrayBuffer[Cst.SequenceEntry]
    ) extends FrameState

    private enum BuilderMode derives CanEqual:
        case PreserveSyntax
        case Canonical
    end BuilderMode

    private enum DocumentState derives CanEqual:
        case BeforeDocument
        case InDocument
        case AfterDocument
    end DocumentState

    final private class Builder(mode: BuilderMode)(using Frame) extends Yaml.Events.Handler[Unit, DecodeException]:
        private var root: Maybe[Cst.Node]           = Absent
        private var stack: List[FrameState]         = Nil
        private var documentStart: Maybe[Yaml.Mark] = Absent
        private var documentEnd: Maybe[Yaml.Mark]   = Absent
        private var documentState: DocumentState    = DocumentState.BeforeDocument
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
            lastMark = mark
            documentState match
                case DocumentState.BeforeDocument =>
                    documentStart = Maybe(mark)
                    documentState = DocumentState.InDocument
                    Result.unit
                case DocumentState.InDocument | DocumentState.AfterDocument =>
                    Result.fail(parseError("Unexpected YAML document start", mark))
            end match
        end documentStart

        override def documentEnd(context: Unit, mark: Yaml.Mark): Result[DecodeException, Unit] =
            lastMark = mark
            stack match
                case _ :: _ =>
                    Result.fail(parseError("Unclosed YAML collection", mark))
                case Nil =>
                    documentState match
                        case DocumentState.AfterDocument =>
                            Result.fail(parseError("Unexpected YAML document end", mark))
                        case DocumentState.BeforeDocument | DocumentState.InDocument =>
                            documentEnd = Maybe(mark)
                            documentState = DocumentState.AfterDocument
                            Result.unit
                    end match
            end match
        end documentEnd

        override def streamEnd(context: Unit, mark: Yaml.Mark): Result[DecodeException, Unit] =
            lastMark = mark
            Result.unit
        end streamEnd

        override def mappingStart(context: Unit, meta: Yaml.Meta, size: Maybe[Int]): Result[DecodeException, Unit] =
            lastMark = meta.mark
            ensureNodeAllowed(meta.mark).map { _ =>
                stack = MappingState(meta, ArrayBuffer.empty[Cst.MappingEntry], Absent) :: stack
            }
        end mappingStart

        override def sequenceStart(context: Unit, meta: Yaml.Meta, size: Maybe[Int]): Result[DecodeException, Unit] =
            lastMark = meta.mark
            ensureNodeAllowed(meta.mark).map { _ =>
                stack = SequenceState(meta, ArrayBuffer.empty[Cst.SequenceEntry]) :: stack
            }
        end sequenceStart

        override def scalar(context: Unit, value: String, meta: Yaml.ScalarMeta): Result[DecodeException, Unit] =
            lastMark = meta.mark
            val nodeSpan = Cst.SourceSpan(meta.mark, meta.mark)
            addNode(Cst.Node.Scalar(value, scalarSyntax(meta.style), meta, nodeSpan, Absent))
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
                                    Chunk.from(state.entries),
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
                            Chunk.from(state.entries),
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
            ensureNodeAllowed(span(node).start).flatMap { _ =>
                stack match
                    case (state: MappingState) :: _ =>
                        state.pendingKey match
                            case Present(key) =>
                                state.entries += Cst.MappingEntry(key, node, Cst.SourceSpan(span(key).start, span(node).end))
                                state.pendingKey = Absent
                            case Absent =>
                                state.pendingKey = Maybe(node)
                        end match
                        Result.unit
                    case (state: SequenceState) :: _ =>
                        state.entries += Cst.SequenceEntry(node, span(node))
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
            }
        end addNode

        private def ensureNodeAllowed(mark: Yaml.Mark): Result[DecodeException, Unit] =
            documentState match
                case DocumentState.AfterDocument =>
                    Result.fail(parseError("Unexpected YAML node after document end", mark))
                case DocumentState.BeforeDocument =>
                    documentState = DocumentState.InDocument
                    Result.unit
                case DocumentState.InDocument =>
                    Result.unit
            end match
        end ensureNodeAllowed

        private def scalarSyntax(style: Yaml.ScalarStyle): Cst.ScalarSyntax =
            mode match
                case BuilderMode.Canonical =>
                    Cst.ScalarSyntax.Canonical
                case BuilderMode.PreserveSyntax =>
                    style match
                        case Yaml.ScalarStyle.Plain        => Cst.ScalarSyntax.Plain
                        case Yaml.ScalarStyle.SingleQuoted => Cst.ScalarSyntax.SingleQuoted
                        case Yaml.ScalarStyle.DoubleQuoted => Cst.ScalarSyntax.DoubleQuoted
                        case Yaml.ScalarStyle.Literal      => Cst.ScalarSyntax.Literal
                        case Yaml.ScalarStyle.Folded       => Cst.ScalarSyntax.Folded
            end match
        end scalarSyntax
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
