package kyo.internal.yaml

import kyo.*
import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

private[kyo] object YamlCstParser:

    import Yaml.Cst

    def document(input: String)(using Frame): Result[DecodeException, Cst.Document] =
        val docs = YamlDocuments.split(input)
        val startIndex =
            firstDocumentIndex(input, docs)
        if docs.isEmpty && !hasDocumentContent(input) then
            Result.succeed(emptyDocument(input, Maybe(input)))
        else if docs.size - startIndex == 1 then
            val body = docs(startIndex)
            parseDocument(body, Maybe(input), sourceBodyStart(input, body))
        else
            Result.fail(ParseException(
                Yaml(),
                "",
                "Unexpected content after YAML document end",
                Nil,
                0
            ))
        end if
    end document

    def stream(input: String)(using Frame): Result[DecodeException, Cst.Stream] =
        val docs       = YamlDocuments.split(input)
        val streamSpan = spanFor(input)
        val startIndex = firstDocumentIndex(input, docs)

        @tailrec def loop(index: Int, acc: Chunk[Cst.Document]): Result[DecodeException, Chunk[Cst.Document]] =
            if index >= docs.size then Result.succeed(acc)
            else
                parseDocument(docs(index), Absent) match
                    case Result.Success(doc) => loop(index + 1, acc :+ doc)
                    case Result.Failure(e)   => Result.fail(e)
                    case Result.Panic(e)     => Result.panic(e)
                end match
            end if
        end loop

        loop(startIndex, Chunk.empty).map { documents =>
            Cst.Stream(documents, Chunk.empty, Chunk.empty, streamSpan, Maybe(input))
        }
    end stream

    /** Returns the document bodies of a YAML stream, dropping a leading marker-only segment so callers see the same documents the
      * source-backed stream parser produces.
      */
    def documentBodies(input: String): Chunk[String] =
        val docs = YamlDocuments.split(input)
        docs.drop(firstDocumentIndex(input, docs))
    end documentBodies

    private def firstDocumentIndex(input: String, docs: Chunk[String]): Int =
        if docs.nonEmpty && docs(0).nonEmpty && !hasDocumentContent(docs(0)) && hasLeadingDocumentMarker(input) then 1
        else 0
    end firstDocumentIndex

    private def parseDocument(body: String, source: Maybe[String], markOffset: Int = 0)(using
        Frame
    ): Result[DecodeException, Cst.Document] =
        val sourceText   = source.getOrElse(body)
        val bodyTrivia   = TriviaIndex(body)
        val sourceTrivia = TriviaIndex(sourceText)
        if !hasDocumentContent(body) then
            Result.succeed(emptyDocument(body, source, sourceTrivia))
        else
            Yaml.parse(body) match
                case Result.Success(node) =>
                    val adjusted = adjustNode(toCst(node, bodyTrivia), sourceTrivia, markOffset)
                    val root     = withRootSource(adjusted, spanFor(sourceText), sourceTrivia)
                    Result.succeed(Cst.Document(
                        Maybe(root),
                        sourceTrivia.documentLeading,
                        Chunk.empty,
                        spanFor(sourceText),
                        source
                    ))
                case Result.Failure(e) => Result.fail(e)
                case Result.Panic(e)   => Result.panic(e)
            end match
        end if
    end parseDocument

    private def emptyDocument(body: String, source: Maybe[String], trivia: TriviaIndex = TriviaIndex("")): Cst.Document =
        val span = spanFor(source.getOrElse(body))
        Cst.Document(Absent, trivia.documentLeading, Chunk.empty, span, source)
    end emptyDocument

    private def sourceBodyStart(source: String, body: String): Int =
        if body.isEmpty then 0
        else
            explicitDocumentBodyStart(source) match
                case Present(start) if start <= source.length => start
                case _ =>
                    val index = source.indexOf(body)
                    if index >= 0 then index else 0
            end match
        end if
    end sourceBodyStart

    private def explicitDocumentBodyStart(source: String): Maybe[Int] =
        @tailrec def loop(start: Int): Maybe[Int] =
            if start >= source.length then Absent
            else
                val lineEnd = YamlSource.lineEnd(source, start)
                val stop =
                    if lineEnd > start && source.charAt(lineEnd - 1) == '\r' then lineEnd - 1
                    else lineEnd
                if startsWithMarker(source, start, stop, "---") then
                    Maybe(if lineEnd < source.length then lineEnd + 1 else source.length)
                else loop(if lineEnd < source.length then lineEnd + 1 else source.length)
                end if
            end if
        end loop

        loop(0)
    end explicitDocumentBodyStart

    private def toCst(node: Yaml.Node, trivia: TriviaIndex): Cst.Node =
        node match
            case Yaml.Node.Mapping(entries, meta) =>
                val cstEntries = entries.map { case (key, value) =>
                    val cstKey   = toCst(key, trivia)
                    val cstValue = withEntryValueSyntax(toCst(value, trivia), start(cstKey), trivia)
                    Cst.MappingEntry(
                        cstKey,
                        cstValue,
                        Cst.SourceSpan(start(cstKey), end(cstValue)),
                        trivia.leadingFor(start(cstKey)),
                        trivia.trailingFor(start(cstKey))
                    )
                }
                Cst.Node.Mapping(
                    cstEntries,
                    mappingSyntax(meta.mark, trivia),
                    meta,
                    collectionSpan(meta.mark, cstEntries.map(_.span)),
                    Absent
                )
            case Yaml.Node.Sequence(elements, meta) =>
                val cstEntries = elements.map { value =>
                    val cstValue = toCst(value, trivia)
                    Cst.SequenceEntry(
                        cstValue,
                        span(cstValue),
                        trivia.leadingFor(start(cstValue)),
                        trivia.trailingFor(start(cstValue))
                    )
                }
                Cst.Node.Sequence(
                    cstEntries,
                    sequenceSyntax(meta.mark, trivia),
                    meta,
                    collectionSpan(meta.mark, cstEntries.map(_.span)),
                    Absent
                )
            case Yaml.Node.Scalar(value, meta) =>
                val nodeSpan = Cst.SourceSpan(meta.mark, meta.mark)
                Cst.Node.Scalar(value, scalarSyntax(meta.style), meta, nodeSpan, Absent)
            case Yaml.Node.Alias(name, mark) =>
                val nodeSpan = Cst.SourceSpan(mark, mark)
                Cst.Node.Alias(name, Cst.AliasSyntax.Canonical, nodeSpan, Absent)
        end match
    end toCst

    private def withRootSource(node: Cst.Node, span: Cst.SourceSpan, trivia: TriviaIndex): Cst.Node =
        val syntaxNode =
            trivia.firstContentChar.fold(node) { ch =>
                withCollectionSyntax(node, ch)
            }
        withSpan(syntaxNode, span)
    end withRootSource

    private def withEntryValueSyntax(node: Cst.Node, keyMark: Yaml.Mark, trivia: TriviaIndex): Cst.Node =
        trivia.valueIndicatorFor(keyMark).fold(node) { ch =>
            withCollectionSyntax(node, ch)
        }
    end withEntryValueSyntax

    private def withCollectionSyntax(node: Cst.Node, ch: Char): Cst.Node =
        node match
            case Cst.Node.Mapping(entries, _, meta, span, originalSource) if ch == '{' =>
                Cst.Node.Mapping(entries, Cst.MappingSyntax.Flow, meta, span, originalSource)
            case Cst.Node.Sequence(entries, _, meta, span, originalSource) if ch == '[' =>
                Cst.Node.Sequence(entries, Cst.SequenceSyntax.Flow, meta, span, originalSource)
            case _ =>
                node
        end match
    end withCollectionSyntax

    private def withSpan(node: Cst.Node, sourceSpan: Cst.SourceSpan): Cst.Node =
        node match
            case Cst.Node.Mapping(entries, syntax, meta, _, originalSource) =>
                Cst.Node.Mapping(entries, syntax, meta, sourceSpan, originalSource)
            case Cst.Node.Sequence(entries, syntax, meta, _, originalSource) =>
                Cst.Node.Sequence(entries, syntax, meta, sourceSpan, originalSource)
            case Cst.Node.Scalar(value, syntax, meta, _, originalSource) =>
                Cst.Node.Scalar(value, syntax, meta, sourceSpan, originalSource)
            case Cst.Node.Alias(name, syntax, _, originalSource) =>
                Cst.Node.Alias(name, syntax, sourceSpan, originalSource)
        end match
    end withSpan

    private def adjustNode(node: Cst.Node, sourceTrivia: TriviaIndex, offset: Int): Cst.Node =
        if offset == 0 then node
        else
            node match
                case Cst.Node.Mapping(entries, syntax, meta, span, originalSource) =>
                    Cst.Node.Mapping(
                        entries.map(adjustMappingEntry(_, sourceTrivia, offset)),
                        syntax,
                        adjustMeta(meta, sourceTrivia, offset),
                        adjustSpan(span, sourceTrivia, offset),
                        originalSource
                    )
                case Cst.Node.Sequence(entries, syntax, meta, span, originalSource) =>
                    Cst.Node.Sequence(
                        entries.map(adjustSequenceEntry(_, sourceTrivia, offset)),
                        syntax,
                        adjustMeta(meta, sourceTrivia, offset),
                        adjustSpan(span, sourceTrivia, offset),
                        originalSource
                    )
                case Cst.Node.Scalar(value, syntax, meta, span, originalSource) =>
                    Cst.Node.Scalar(
                        value,
                        syntax,
                        adjustScalarMeta(meta, sourceTrivia, offset),
                        adjustSpan(span, sourceTrivia, offset),
                        originalSource
                    )
                case Cst.Node.Alias(name, syntax, span, originalSource) =>
                    Cst.Node.Alias(name, syntax, adjustSpan(span, sourceTrivia, offset), originalSource)
            end match
        end if
    end adjustNode

    private def adjustMappingEntry(entry: Cst.MappingEntry, sourceTrivia: TriviaIndex, offset: Int): Cst.MappingEntry =
        Cst.MappingEntry(
            adjustNode(entry.key, sourceTrivia, offset),
            adjustNode(entry.value, sourceTrivia, offset),
            adjustSpan(entry.span, sourceTrivia, offset),
            entry.leadingTrivia.map(adjustTrivia(_, sourceTrivia, offset)),
            entry.trailingTrivia.map(adjustTrivia(_, sourceTrivia, offset))
        )
    end adjustMappingEntry

    private def adjustSequenceEntry(entry: Cst.SequenceEntry, sourceTrivia: TriviaIndex, offset: Int): Cst.SequenceEntry =
        Cst.SequenceEntry(
            adjustNode(entry.value, sourceTrivia, offset),
            adjustSpan(entry.span, sourceTrivia, offset),
            entry.leadingTrivia.map(adjustTrivia(_, sourceTrivia, offset)),
            entry.trailingTrivia.map(adjustTrivia(_, sourceTrivia, offset))
        )
    end adjustSequenceEntry

    private def adjustMeta(meta: Yaml.Meta, sourceTrivia: TriviaIndex, offset: Int): Yaml.Meta =
        meta.copy(mark = adjustMark(meta.mark, sourceTrivia, offset))
    end adjustMeta

    private def adjustScalarMeta(meta: Yaml.ScalarMeta, sourceTrivia: TriviaIndex, offset: Int): Yaml.ScalarMeta =
        meta.copy(mark = adjustMark(meta.mark, sourceTrivia, offset))
    end adjustScalarMeta

    private def adjustTrivia(trivia: Cst.Trivia, sourceTrivia: TriviaIndex, offset: Int): Cst.Trivia =
        Cst.Trivia(trivia.text, adjustSpan(trivia.span, sourceTrivia, offset))
    end adjustTrivia

    private def adjustSpan(span: Cst.SourceSpan, sourceTrivia: TriviaIndex, offset: Int): Cst.SourceSpan =
        Cst.SourceSpan(adjustMark(span.start, sourceTrivia, offset), adjustMark(span.end, sourceTrivia, offset))
    end adjustSpan

    private def adjustMark(mark: Yaml.Mark, sourceTrivia: TriviaIndex, offset: Int): Yaml.Mark =
        sourceTrivia.markAt(mark.index + offset)
    end adjustMark

    private def mappingSyntax(mark: Yaml.Mark, trivia: TriviaIndex): Cst.MappingSyntax =
        if trivia.firstNonWhitespaceAt(mark.index).contains('{') then Cst.MappingSyntax.Flow
        else Cst.MappingSyntax.Block
    end mappingSyntax

    private def sequenceSyntax(mark: Yaml.Mark, trivia: TriviaIndex): Cst.SequenceSyntax =
        if trivia.firstNonWhitespaceAt(mark.index).contains('[') then Cst.SequenceSyntax.Flow
        else Cst.SequenceSyntax.Block
    end sequenceSyntax

    private def scalarSyntax(style: Yaml.ScalarStyle): Cst.ScalarSyntax =
        style match
            case Yaml.ScalarStyle.Plain        => Cst.ScalarSyntax.Plain
            case Yaml.ScalarStyle.SingleQuoted => Cst.ScalarSyntax.SingleQuoted
            case Yaml.ScalarStyle.DoubleQuoted => Cst.ScalarSyntax.DoubleQuoted
            case Yaml.ScalarStyle.Literal      => Cst.ScalarSyntax.Literal
            case Yaml.ScalarStyle.Folded       => Cst.ScalarSyntax.Folded
    end scalarSyntax

    private def collectionSpan(start: Yaml.Mark, childSpans: Chunk[Cst.SourceSpan]): Cst.SourceSpan =
        if childSpans.isEmpty then Cst.SourceSpan(start, start)
        else Cst.SourceSpan(start, childSpans(childSpans.size - 1).end)
    end collectionSpan

    private def span(node: Cst.Node): Cst.SourceSpan =
        node match
            case Cst.Node.Mapping(_, _, _, span, _)  => span
            case Cst.Node.Sequence(_, _, _, span, _) => span
            case Cst.Node.Scalar(_, _, _, span, _)   => span
            case Cst.Node.Alias(_, _, span, _)       => span
    end span

    private def start(node: Cst.Node): Yaml.Mark =
        span(node).start

    private def end(node: Cst.Node): Yaml.Mark =
        span(node).end

    private def spanFor(input: String): Cst.SourceSpan =
        Cst.SourceSpan(Yaml.Mark(0, 1, 1), endMark(input))

    private def endMark(input: String): Yaml.Mark =
        @tailrec def loop(index: Int, line: Int, column: Int): Yaml.Mark =
            if index >= input.length then Yaml.Mark(index, line, column)
            else if input.charAt(index) == '\n' then loop(index + 1, line + 1, 1)
            else loop(index + 1, line, column + 1)
        end loop

        loop(0, 1, 1)
    end endMark

    private def hasDocumentContent(input: String): Boolean =
        @tailrec def loop(start: Int): Boolean =
            if start >= input.length then false
            else
                val lineEnd = YamlSource.lineEnd(input, start)
                val stop =
                    if lineEnd > start && input.charAt(lineEnd - 1) == '\r' then lineEnd - 1
                    else lineEnd
                if lineHasContent(input, start, stop) then true
                else loop(if lineEnd < input.length then lineEnd + 1 else input.length)
            end if
        end loop

        loop(0)
    end hasDocumentContent

    private def hasLeadingDocumentMarker(input: String): Boolean =
        @tailrec def loop(start: Int): Boolean =
            if start >= input.length then false
            else
                val lineEnd = YamlSource.lineEnd(input, start)
                val stop =
                    if lineEnd > start && input.charAt(lineEnd - 1) == '\r' then lineEnd - 1
                    else lineEnd
                val first = firstNonWhitespace(input, start, stop)
                if first >= stop || input.charAt(first) == '#' || input.charAt(first) == '%' then
                    loop(if lineEnd < input.length then lineEnd + 1 else input.length)
                else
                    startsWithMarker(input, start, stop, "---")
                end if
            end if
        end loop

        loop(0)
    end hasLeadingDocumentMarker

    private def startsWithMarker(input: String, start: Int, stop: Int, marker: String): Boolean =
        stop - start >= marker.length &&
            input.startsWith(marker, start) &&
            separated(input, start + marker.length, stop)
    end startsWithMarker

    private def separated(input: String, index: Int, stop: Int): Boolean =
        index >= stop || input.charAt(index).isWhitespace || input.charAt(index) == '#'
    end separated

    private def lineHasContent(input: String, start: Int, stop: Int): Boolean =
        val first = firstNonWhitespace(input, start, stop)
        first < stop && input.charAt(first) != '#' && input.charAt(first) != '%'
    end lineHasContent

    @tailrec private def firstNonWhitespace(input: String, index: Int, stop: Int): Int =
        if index < stop && input.charAt(index).isWhitespace then firstNonWhitespace(input, index + 1, stop)
        else index
    end firstNonWhitespace

    private case class SourceLine(number: Int, start: Int, end: Int, contentStart: Int):
        def indent: Int =
            contentStart - start
    end SourceLine

    final private class TriviaIndex(source: String, lines: Chunk[SourceLine], documentLeadingLines: Set[Int]):

        val documentLeading: Chunk[Cst.Trivia] =
            leadingDocumentTrivia(lines, 0, Chunk.empty)

        def firstContentChar: Maybe[Char] =
            firstContentLine(lines, 0).flatMap { line =>
                if line.contentStart < line.end then Maybe(source.charAt(line.contentStart))
                else Absent
            }
        end firstContentChar

        def firstNonWhitespaceAt(index: Int): Maybe[Char] =
            if index < 0 || index >= source.length then Absent
            else
                val lineEnd = YamlSource.lineEnd(source, index)
                val stop =
                    if lineEnd > index && source.charAt(lineEnd - 1) == '\r' then lineEnd - 1
                    else lineEnd
                val first = firstNonWhitespace(source, index, stop)
                if first < stop then Maybe(source.charAt(first))
                else Absent
            end if
        end firstNonWhitespaceAt

        def leadingFor(mark: Yaml.Mark): Chunk[Cst.Trivia] =
            lineAt(mark.line) match
                case Present(line) =>
                    @tailrec def loop(lineNumber: Int, acc: List[Cst.Trivia]): List[Cst.Trivia] =
                        lineAt(lineNumber) match
                            case Present(previous)
                                if isComment(previous) && previous.indent == line.indent && !documentLeadingLines.contains(
                                    previous.number
                                ) =>
                                loop(lineNumber - 1, triviaForComment(previous) :: acc)
                            case _ =>
                                acc
                        end match
                    end loop

                    Chunk.from(loop(line.number - 1, Nil))
                case Absent =>
                    Chunk.empty
            end match
        end leadingFor

        def trailingFor(mark: Yaml.Mark): Chunk[Cst.Trivia] =
            lineAt(mark.line) match
                case Present(line) =>
                    commentStart(line) match
                        case Present(start) if start > line.contentStart =>
                            Chunk(Cst.Trivia(source.substring(start, line.end), Cst.SourceSpan(markAt(start), markAt(line.end))))
                        case _ =>
                            Chunk.empty
                    end match
                case Absent =>
                    Chunk.empty
            end match
        end trailingFor

        def valueIndicatorFor(mark: Yaml.Mark): Maybe[Char] =
            lineAt(mark.line).flatMap { line =>
                mappingSeparator(line).flatMap { separator =>
                    val valueStart = firstNonWhitespace(source, separator + 1, line.end)
                    if valueStart < line.end then Maybe(source.charAt(valueStart))
                    else Absent
                }
            }
        end valueIndicatorFor

        private def lineAt(lineNumber: Int): Maybe[SourceLine] =
            if lineNumber <= 0 || lineNumber > lines.size then Absent
            else Maybe(lines(lineNumber - 1))
        end lineAt

        @tailrec private def leadingDocumentTrivia(
            lines: Chunk[SourceLine],
            index: Int,
            acc: Chunk[Cst.Trivia]
        ): Chunk[Cst.Trivia] =
            if index >= lines.size then acc
            else
                val line = lines(index)
                if isBlank(line) then leadingDocumentTrivia(lines, index + 1, acc)
                else if isComment(line) then leadingDocumentTrivia(lines, index + 1, acc :+ triviaForComment(line))
                else acc
            end if
        end leadingDocumentTrivia

        @tailrec private def firstContentLine(lines: Chunk[SourceLine], index: Int): Maybe[SourceLine] =
            if index >= lines.size then Absent
            else
                val line = lines(index)
                if isBlank(line) || isComment(line) || isDirective(line) then firstContentLine(lines, index + 1)
                else Maybe(line)
            end if
        end firstContentLine

        private def triviaForComment(line: SourceLine): Cst.Trivia =
            Cst.Trivia(source.substring(line.contentStart, line.end), Cst.SourceSpan(markAt(line.contentStart), markAt(line.end)))
        end triviaForComment

        def markAt(index: Int): Yaml.Mark =
            val target =
                if index <= 0 then 0
                else if index >= source.length then source.length
                else index

            @tailrec def loop(low: Int, high: Int): Yaml.Mark =
                if low > high then endMark(source)
                else
                    val mid  = low + ((high - low) / 2)
                    val line = lines(mid)
                    if target < line.start then loop(low, mid - 1)
                    else if target > line.end then loop(mid + 1, high)
                    else Yaml.Mark(target, line.number, target - line.start + 1)
                    end if
                end if
            end loop

            if lines.isEmpty then endMark(source)
            else loop(0, lines.size - 1)
        end markAt

        private def isBlank(line: SourceLine): Boolean =
            line.contentStart >= line.end
        end isBlank

        private def isComment(line: SourceLine): Boolean =
            line.contentStart < line.end && source.charAt(line.contentStart) == '#'
        end isComment

        private def isDirective(line: SourceLine): Boolean =
            line.contentStart < line.end && source.charAt(line.contentStart) == '%'
        end isDirective

        private def commentStart(line: SourceLine): Maybe[Int] =
            @tailrec def loop(index: Int, single: Boolean, double: Boolean, escape: Boolean): Maybe[Int] =
                if index >= line.end then Absent
                else
                    val ch = source.charAt(index)
                    if escape then loop(index + 1, single, double, false)
                    else if double && ch == '\\' then loop(index + 1, single, double, true)
                    else if !double && ch == '\'' then loop(index + 1, !single, double, false)
                    else if !single && ch == '"' then loop(index + 1, single, !double, false)
                    else if !single && !double && ch == '#' && (index == line.start || source.charAt(index - 1).isWhitespace) then
                        Maybe(index)
                    else loop(index + 1, single, double, false)
                    end if
                end if
            end loop

            loop(line.contentStart, single = false, double = false, escape = false)
        end commentStart

        private def mappingSeparator(line: SourceLine): Maybe[Int] =
            @tailrec def loop(index: Int, single: Boolean, double: Boolean, escape: Boolean): Maybe[Int] =
                if index >= line.end then Absent
                else
                    val ch = source.charAt(index)
                    if escape then loop(index + 1, single, double, false)
                    else if double && ch == '\\' then loop(index + 1, single, double, true)
                    else if !double && ch == '\'' then loop(index + 1, !single, double, false)
                    else if !single && ch == '"' then loop(index + 1, single, !double, false)
                    else if !single && !double && ch == ':' then Maybe(index)
                    else loop(index + 1, single, double, false)
                    end if
                end if
            end loop

            loop(line.contentStart, single = false, double = false, escape = false)
        end mappingSeparator
    end TriviaIndex

    private object TriviaIndex:

        def apply(source: String): TriviaIndex =
            val lines = ArrayBuffer.empty[SourceLine]

            @tailrec def loop(start: Int, number: Int): Unit =
                if start < source.length then
                    val lineEnd = YamlSource.lineEnd(source, start)
                    val stop =
                        if lineEnd > start && source.charAt(lineEnd - 1) == '\r' then lineEnd - 1
                        else lineEnd
                    val contentStart = firstNonWhitespace(source, start, stop)
                    lines += SourceLine(number, start, stop, contentStart)
                    loop(if lineEnd < source.length then lineEnd + 1 else source.length, number + 1)
                end if
            end loop

            loop(0, 1)
            val chunk                = Chunk.from(lines)
            val documentLeadingLines = collectDocumentLeadingLines(source, chunk, 0, Set.empty)
            new TriviaIndex(source, chunk, documentLeadingLines)
        end apply

        @tailrec private def collectDocumentLeadingLines(
            source: String,
            lines: Chunk[SourceLine],
            index: Int,
            acc: Set[Int]
        ): Set[Int] =
            if index >= lines.size then acc
            else
                val line = lines(index)
                if line.contentStart >= line.end then collectDocumentLeadingLines(source, lines, index + 1, acc)
                else if source.charAt(line.contentStart) == '#' then
                    collectDocumentLeadingLines(source, lines, index + 1, acc + line.number)
                else acc
                end if
            end if
        end collectDocumentLeadingLines
    end TriviaIndex
end YamlCstParser
