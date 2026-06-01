package kyo.internal.yaml

import kyo.*
import scala.annotation.tailrec

private[kyo] object YamlCstParser:

    import Yaml.Cst

    def document(input: String)(using Frame): Result[DecodeException, Cst.Document] =
        val docs = YamlDocuments.split(input)
        val startIndex =
            firstDocumentIndex(input, docs)
        if docs.isEmpty && !hasDocumentContent(input) then
            Result.succeed(emptyDocument(input, input))
        else if docs.size - startIndex == 1 then
            parseDocument(docs(startIndex), input)
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
                parseDocument(docs(index), docs(index)) match
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

    private def firstDocumentIndex(input: String, docs: Chunk[String]): Int =
        if docs.nonEmpty && !hasDocumentContent(docs(0)) && hasLeadingDocumentMarker(input) then 1
        else 0
    end firstDocumentIndex

    private def parseDocument(body: String, source: String)(using Frame): Result[DecodeException, Cst.Document] =
        if !hasDocumentContent(body) then
            Result.succeed(emptyDocument(body, source))
        else
            Yaml.parse(body) match
                case Result.Success(node) =>
                    val root = toCst(node)
                    Result.succeed(Cst.Document(
                        Maybe(root),
                        Chunk.empty,
                        Chunk.empty,
                        spanFor(source),
                        Maybe(source)
                    ))
                case Result.Failure(e) => Result.fail(e)
                case Result.Panic(e)   => Result.panic(e)
            end match
        end if
    end parseDocument

    private def emptyDocument(body: String, source: String): Cst.Document =
        val span = spanFor(body)
        Cst.Document(Absent, Chunk.empty, Chunk.empty, span, Maybe(source))
    end emptyDocument

    private def toCst(node: Yaml.Node): Cst.Node =
        node match
            case Yaml.Node.Mapping(entries, meta) =>
                val cstEntries = entries.map { case (key, value) =>
                    val cstKey   = toCst(key)
                    val cstValue = toCst(value)
                    Cst.MappingEntry(cstKey, cstValue, Cst.SourceSpan(start(cstKey), end(cstValue)))
                }
                Cst.Node.Mapping(
                    cstEntries,
                    Cst.MappingSyntax.Canonical,
                    meta,
                    collectionSpan(meta.mark, cstEntries.map(_.span)),
                    Absent
                )
            case Yaml.Node.Sequence(elements, meta) =>
                val cstEntries = elements.map { value =>
                    val cstValue = toCst(value)
                    Cst.SequenceEntry(cstValue, span(cstValue))
                }
                Cst.Node.Sequence(
                    cstEntries,
                    Cst.SequenceSyntax.Canonical,
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
end YamlCstParser
