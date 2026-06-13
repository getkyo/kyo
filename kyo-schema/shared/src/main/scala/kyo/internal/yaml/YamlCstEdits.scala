package kyo.internal.yaml

import kyo.*

private[kyo] object YamlCstEdits:

    import Yaml.Cst

    def replace(document: Cst.Document, path: Cst.Path, node: Cst.Node): Result[Cst.Error, Cst.Document] =
        val segments = path.segments
        if segments.isEmpty then
            Result.succeed(document.copy(root = Maybe(clearSource(node)), originalSource = Absent))
        else
            document.root match
                case Present(root) =>
                    replaceNode(root, segments, 0, path, clearSource(node)).map { updated =>
                        document.copy(root = Maybe(updated), originalSource = Absent)
                    }
                case Absent =>
                    fail("Cannot replace a path in an empty YAML document", path, Absent)
            end match
        end if
    end replace

    def insert(document: Cst.Document, path: Cst.Path, node: Cst.Node): Result[Cst.Error, Cst.Document] =
        val segments = path.segments
        if segments.isEmpty then
            fail("Cannot insert at the YAML document root", path, Absent)
        else
            document.root match
                case Present(root) =>
                    insertNode(root, segments, 0, path, clearSource(node)).map { updated =>
                        document.copy(root = Maybe(updated), originalSource = Absent)
                    }
                case Absent =>
                    fail("Cannot insert into an empty YAML document", path, Absent)
            end match
        end if
    end insert

    def remove(document: Cst.Document, path: Cst.Path): Result[Cst.Error, Cst.Document] =
        val segments = path.segments
        if segments.isEmpty then
            Result.succeed(document.copy(root = Absent, originalSource = Absent))
        else
            document.root match
                case Present(root) =>
                    removeNode(root, segments, 0, path).map { updated =>
                        document.copy(root = Maybe(updated), originalSource = Absent)
                    }
                case Absent =>
                    fail("Cannot remove a path from an empty YAML document", path, Absent)
            end match
        end if
    end remove

    private def replaceNode(
        node: Cst.Node,
        segments: Chunk[Cst.Path.Segment],
        index: Int,
        path: Cst.Path,
        replacement: Cst.Node
    ): Result[Cst.Error, Cst.Node] =
        segments(index) match
            case Cst.Path.Segment.Key(key) =>
                node match
                    case Cst.Node.Mapping(entries, syntax, meta, span, _) =>
                        mappingEntryIndex(entries, key, path, Maybe(span.start)).flatMap {
                            case Present(entryIndex) =>
                                val entry = entries(entryIndex)
                                if index == segments.size - 1 then
                                    val updated = update(entries, entryIndex, entry.copy(value = replacement))
                                    Result.succeed(Cst.Node.Mapping(updated, syntax, meta, span, Absent))
                                else
                                    replaceNode(entry.value, segments, index + 1, path, replacement).map { child =>
                                        val updated = update(entries, entryIndex, entry.copy(value = child))
                                        Cst.Node.Mapping(updated, syntax, meta, span, Absent)
                                    }
                                end if
                            case Absent =>
                                fail(s"Missing mapping key '$key'", path, Maybe(span.start))
                        }
                    case _ =>
                        fail(s"Expected mapping while replacing '${path.show}'", path, Maybe(spanOf(node).start))
                end match
            case Cst.Path.Segment.Index(elementIndex) =>
                node match
                    case Cst.Node.Sequence(entries, syntax, meta, span, _) =>
                        if elementIndex < 0 || elementIndex >= entries.size then
                            fail(s"Sequence index $elementIndex is out of range", path, Maybe(span.start))
                        else
                            val entry = entries(elementIndex)
                            if index == segments.size - 1 then
                                val updated = update(entries, elementIndex, entry.copy(value = replacement))
                                Result.succeed(Cst.Node.Sequence(updated, syntax, meta, span, Absent))
                            else
                                replaceNode(entry.value, segments, index + 1, path, replacement).map { child =>
                                    val updated = update(entries, elementIndex, entry.copy(value = child))
                                    Cst.Node.Sequence(updated, syntax, meta, span, Absent)
                                }
                            end if
                        end if
                    case _ =>
                        fail(s"Expected sequence while replacing '${path.show}'", path, Maybe(spanOf(node).start))
                end match
        end match
    end replaceNode

    private def insertNode(
        node: Cst.Node,
        segments: Chunk[Cst.Path.Segment],
        index: Int,
        path: Cst.Path,
        inserted: Cst.Node
    ): Result[Cst.Error, Cst.Node] =
        segments(index) match
            case Cst.Path.Segment.Key(key) =>
                node match
                    case Cst.Node.Mapping(entries, syntax, meta, span, _) =>
                        if index == segments.size - 1 then
                            mappingEntryIndex(entries, key, path, Maybe(span.start)).flatMap {
                                case Present(_) =>
                                    fail(s"Mapping key '$key' already exists", path, Maybe(span.start))
                                case Absent =>
                                    val updated = entries :+ Cst.MappingEntry(
                                        scalarKey(key, span.start),
                                        inserted,
                                        Cst.SourceSpan(span.start, spanOf(inserted).end)
                                    )
                                    Result.succeed(Cst.Node.Mapping(updated, syntax, meta, span, Absent))
                            }
                        else
                            mappingEntryIndex(entries, key, path, Maybe(span.start)).flatMap {
                                case Present(entryIndex) =>
                                    val entry = entries(entryIndex)
                                    insertNode(entry.value, segments, index + 1, path, inserted).map { child =>
                                        Cst.Node.Mapping(update(entries, entryIndex, entry.copy(value = child)), syntax, meta, span, Absent)
                                    }
                                case Absent =>
                                    fail(s"Missing mapping key '$key'", path, Maybe(span.start))
                            }
                        end if
                    case _ =>
                        fail(s"Expected mapping while inserting '${path.show}'", path, Maybe(spanOf(node).start))
                end match
            case Cst.Path.Segment.Index(elementIndex) =>
                node match
                    case Cst.Node.Sequence(entries, syntax, meta, span, _) =>
                        if index == segments.size - 1 then
                            if elementIndex < 0 || elementIndex > entries.size then
                                fail(s"Sequence index $elementIndex is out of range", path, Maybe(span.start))
                            else
                                val entry   = Cst.SequenceEntry(inserted, spanOf(inserted))
                                val updated = insertAt(entries, elementIndex, entry)
                                Result.succeed(Cst.Node.Sequence(updated, syntax, meta, span, Absent))
                            end if
                        else if elementIndex < 0 || elementIndex >= entries.size then
                            fail(s"Sequence index $elementIndex is out of range", path, Maybe(span.start))
                        else
                            val entry = entries(elementIndex)
                            insertNode(entry.value, segments, index + 1, path, inserted).map { child =>
                                Cst.Node.Sequence(update(entries, elementIndex, entry.copy(value = child)), syntax, meta, span, Absent)
                            }
                        end if
                    case _ =>
                        fail(s"Expected sequence while inserting '${path.show}'", path, Maybe(spanOf(node).start))
                end match
        end match
    end insertNode

    private def removeNode(
        node: Cst.Node,
        segments: Chunk[Cst.Path.Segment],
        index: Int,
        path: Cst.Path
    ): Result[Cst.Error, Cst.Node] =
        segments(index) match
            case Cst.Path.Segment.Key(key) =>
                node match
                    case Cst.Node.Mapping(entries, syntax, meta, span, _) =>
                        mappingEntryIndex(entries, key, path, Maybe(span.start)).flatMap {
                            case Present(entryIndex) =>
                                if index == segments.size - 1 then
                                    Result.succeed(Cst.Node.Mapping(removeAt(entries, entryIndex), syntax, meta, span, Absent))
                                else
                                    val entry = entries(entryIndex)
                                    removeNode(entry.value, segments, index + 1, path).map { child =>
                                        Cst.Node.Mapping(update(entries, entryIndex, entry.copy(value = child)), syntax, meta, span, Absent)
                                    }
                                end if
                            case Absent =>
                                fail(s"Missing mapping key '$key'", path, Maybe(span.start))
                        }
                    case _ =>
                        fail(s"Expected mapping while removing '${path.show}'", path, Maybe(spanOf(node).start))
                end match
            case Cst.Path.Segment.Index(elementIndex) =>
                node match
                    case Cst.Node.Sequence(entries, syntax, meta, span, _) =>
                        if elementIndex < 0 || elementIndex >= entries.size then
                            fail(s"Sequence index $elementIndex is out of range", path, Maybe(span.start))
                        else if index == segments.size - 1 then
                            Result.succeed(Cst.Node.Sequence(removeAt(entries, elementIndex), syntax, meta, span, Absent))
                        else
                            val entry = entries(elementIndex)
                            removeNode(entry.value, segments, index + 1, path).map { child =>
                                Cst.Node.Sequence(update(entries, elementIndex, entry.copy(value = child)), syntax, meta, span, Absent)
                            }
                        end if
                    case _ =>
                        fail(s"Expected sequence while removing '${path.show}'", path, Maybe(spanOf(node).start))
                end match
        end match
    end removeNode

    private def mappingEntryIndex(
        entries: Chunk[Cst.MappingEntry],
        key: String,
        path: Cst.Path,
        mark: Maybe[Yaml.Mark]
    ): Result[Cst.Error, Maybe[Int]] =
        @scala.annotation.tailrec
        def loop(index: Int, found: Maybe[Int]): Result[Cst.Error, Maybe[Int]] =
            if index >= entries.size then Result.succeed(found)
            else
                entries(index).key match
                    case Cst.Node.Scalar(value, _, _, _, _) if value == key =>
                        if found.isDefined then fail(s"Ambiguous mapping key '$key'", path, mark)
                        else loop(index + 1, Maybe(index))
                    case _ =>
                        loop(index + 1, found)
                end match
            end if
        end loop

        loop(0, Absent)
    end mappingEntryIndex

    private def update[A](values: Chunk[A], index: Int, value: A): Chunk[A] =
        values.take(index) ++ Chunk(value) ++ values.drop(index + 1)
    end update

    private def insertAt[A](values: Chunk[A], index: Int, value: A): Chunk[A] =
        values.take(index) ++ Chunk(value) ++ values.drop(index)
    end insertAt

    private def removeAt[A](values: Chunk[A], index: Int): Chunk[A] =
        values.take(index) ++ values.drop(index + 1)
    end removeAt

    private def scalarKey(value: String, mark: Yaml.Mark): Cst.Node =
        val span = Cst.SourceSpan(mark, mark)
        Cst.Node.Scalar(
            value,
            Cst.ScalarSyntax.Canonical,
            Yaml.ScalarMeta(Absent, Absent, Yaml.ScalarStyle.Plain, mark),
            span,
            Absent
        )
    end scalarKey

    private def clearSource(node: Cst.Node): Cst.Node =
        if hasSource(node) then clearSourceDeep(node)
        else node
    end clearSource

    private def hasSource(node: Cst.Node): Boolean =
        node match
            case Cst.Node.Mapping(entries, _, _, _, source) =>
                source.isDefined || entries.exists(entry => hasSource(entry.key) || hasSource(entry.value))
            case Cst.Node.Sequence(entries, _, _, _, source) =>
                source.isDefined || entries.exists(entry => hasSource(entry.value))
            case Cst.Node.Scalar(_, _, _, _, source) =>
                source.isDefined
            case Cst.Node.Alias(_, _, _, source) =>
                source.isDefined
        end match
    end hasSource

    private def clearSourceDeep(node: Cst.Node): Cst.Node =
        node match
            case Cst.Node.Mapping(entries, syntax, meta, span, _) =>
                Cst.Node.Mapping(
                    entries.map(entry => entry.copy(key = clearSource(entry.key), value = clearSource(entry.value))),
                    syntax,
                    meta,
                    span,
                    Absent
                )
            case Cst.Node.Sequence(entries, syntax, meta, span, _) =>
                Cst.Node.Sequence(
                    entries.map(entry => entry.copy(value = clearSource(entry.value))),
                    syntax,
                    meta,
                    span,
                    Absent
                )
            case Cst.Node.Scalar(value, syntax, meta, span, _) =>
                Cst.Node.Scalar(value, syntax, meta, span, Absent)
            case Cst.Node.Alias(name, syntax, span, _) =>
                Cst.Node.Alias(name, syntax, span, Absent)
        end match
    end clearSourceDeep

    private def spanOf(node: Cst.Node): Cst.SourceSpan =
        node match
            case Cst.Node.Mapping(_, _, _, span, _)  => span
            case Cst.Node.Sequence(_, _, _, span, _) => span
            case Cst.Node.Scalar(_, _, _, span, _)   => span
            case Cst.Node.Alias(_, _, span, _)       => span
        end match
    end spanOf

    private def fail(message: String, path: Cst.Path, mark: Maybe[Yaml.Mark]): Result[Cst.Error, Nothing] =
        Result.fail(Cst.EditException(message, path, mark))
    end fail
end YamlCstEdits
