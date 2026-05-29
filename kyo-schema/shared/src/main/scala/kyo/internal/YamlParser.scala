package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import scala.annotation.tailrec

final class YamlParser private (private val input: String)(using frame: Frame):
    import Yaml.*

    private var pos: Int                     = 0
    private var line: Int                    = 1
    private var column: Int                  = 1
    private var pendingAnchor: Maybe[String] = Absent
    private var pendingTag: Maybe[String]    = Absent

    def visit[Ctx, Err, A](context: Ctx)(visitor: Visitor[Ctx, Err, A]): Result[Err | DecodeException, A] =
        run(context, visitor.streamStart(_, mark())) { c1 =>
            skipIgnorable()
            if isDocumentMarker("---") then
                val _ = readRestOfLine()
            run(c1, visitor.documentStart(_, mark())) { c2 =>
                parseNode(c2, 0, visitor).flatMap { c3 =>
                    skipIgnorable()
                    run(c3, visitor.documentEnd(_, mark())) { c4 =>
                        skipIgnorable()
                        if isDocumentMarker("...") then
                            val endMark = mark()
                            val _       = readRestOfLine()
                            skipIgnorable()
                            if pos < input.length then
                                Result.fail(errorAt(endMark, "Unexpected content after YAML document end"))
                            else visitor.streamEnd(c4, mark())
                        else if isDocumentMarker("---") then
                            Result.fail(error("Expected a single YAML document for this parser entry point"))
                        else if pos < input.length then
                            Result.fail(error("Unexpected content after YAML document"))
                        else visitor.streamEnd(c4, mark())
                        end if
                    }
                }
            }
        }
    end visit

    private def parseNode[Ctx, Err, A](context: Ctx, indent: Int, visitor: Visitor[Ctx, Err, A]): Result[Err | DecodeException, Ctx] =
        skipIgnorable()
        if pos >= input.length then
            val (anchor, tag) = takeProperties()
            visitor.scalar(context, "", ScalarMeta(anchor, tag, ScalarStyle.Plain, mark()))
        else if currentIndent() < indent then
            Result.fail(error(s"Expected indentation of at least $indent spaces"))
        else
            val lineText = currentLineText()
            val stripped = lineText.dropWhile(_ == ' ')
            if stripped.startsWith("[") || stripped.startsWith("{") then
                parseScalarValue(context, visitor)
            else if startsWithSequenceEntryAtIndent() then parseBlockSequence(context, currentIndent(), visitor)
            else if isBlockMappingLine(lineText) then parseBlockMapping(context, currentIndent(), visitor)
            else parseScalarValue(context, visitor)
            end if
        end if
    end parseNode

    private def parseBlockMapping[Ctx, Err, A](
        context: Ctx,
        indent: Int,
        visitor: Visitor[Ctx, Err, A]
    ): Result[Err | DecodeException, Ctx] =
        val (anchor, tag) = takeProperties()
        run(context, visitor.mappingStart(_, Meta(anchor, tag, mark()))) { c0 =>
            def loop(context: Ctx): Result[Err | DecodeException, Ctx] =
                skipBlankAndCommentLines()
                val lineText = if pos < input.length then currentLineText() else ""
                if pos >= input.length || currentIndent() < indent || !isBlockMappingLine(lineText) then
                    visitor.nodeEnd(context, mark())
                else
                    parseBlockMappingEntry(context, indent, visitor).flatMap(loop)
                end if
            end loop
            loop(c0)
        }
    end parseBlockMapping

    private def parseBlockSequence[Ctx, Err, A](
        context: Ctx,
        indent: Int,
        visitor: Visitor[Ctx, Err, A]
    ): Result[Err | DecodeException, Ctx] =
        val (anchor, tag) = takeProperties()
        run(context, visitor.sequenceStart(_, Meta(anchor, tag, mark()))) { c0 =>
            def loop(context: Ctx): Result[Err | DecodeException, Ctx] =
                skipBlankAndCommentLines()
                if pos >= input.length || currentIndent() < indent || !startsWithSequenceEntryAtIndent() then
                    visitor.nodeEnd(context, mark())
                else
                    consumeIndent(indent)
                    advance(1)
                    if peekChar(' ') then advance(1)
                    val valueMark = mark()
                    val rest      = stripComment(readRestOfLine()).trim
                    val parsed =
                        if rest.isEmpty then parseNode(context, indent + 2, visitor)
                        else
                            readFlowText(rest, valueMark).flatMap { text =>
                                if isInlineMappingText(text) then parseCompactSequenceMapping(text, context, indent, visitor)
                                else parseInlineScalar(text, context, visitor, valueMark)
                            }
                    parsed.flatMap(loop)
                end if
            end loop
            loop(c0)
        }
    end parseBlockSequence

    private def parseScalarValue[Ctx, Err, A](context: Ctx, visitor: Visitor[Ctx, Err, A]): Result[Err | DecodeException, Ctx] =
        val valueMark = mark()
        val text      = stripComment(readRestOfLine()).trim
        readFlowText(text, valueMark).flatMap(parseInlineScalar(_, context, visitor, valueMark))
    end parseScalarValue

    private def parseInlineScalar[Ctx, Err, A](
        text: String,
        context: Ctx,
        visitor: Visitor[Ctx, Err, A],
        valueMark: Mark = mark()
    ): Result[Err | DecodeException, Ctx] =
        val (anchor, tag, valueText) = readProperties(text)
        if valueText.startsWith("*") && !valueText.exists(_.isWhitespace) then
            visitor.alias(context, valueText.drop(1), valueMark)
        else if valueText.startsWith("[") && valueText.endsWith("]") then
            withPending(anchor, tag)(parseFlowSequence(valueText, context, visitor))
        else if valueText.startsWith("{") && valueText.endsWith("}") then
            withPending(anchor, tag)(parseFlowMapping(valueText, context, visitor))
        else if valueText.startsWith("'") && valueText.endsWith("'") && valueText.length >= 2 then
            visitor.scalar(
                context,
                foldFlowScalarText(valueText.substring(1, valueText.length - 1)).replace("''", "'"),
                ScalarMeta(anchor, tag, ScalarStyle.SingleQuoted, valueMark)
            )
        else if valueText.startsWith("\"") && valueText.endsWith("\"") && valueText.length >= 2 then
            unescapeDoubleQuoted(
                foldFlowScalarText(valueText.substring(1, valueText.length - 1)),
                valueMark.copy(index = valueMark.index + 1, column = valueMark.column + 1)
            )
                .flatMap { value =>
                    visitor.scalar(
                        context,
                        value,
                        ScalarMeta(anchor, tag, ScalarStyle.DoubleQuoted, valueMark)
                    )
                }
        else
            visitor.scalar(context, foldFlowScalarText(valueText), ScalarMeta(anchor, tag, ScalarStyle.Plain, valueMark))
        end if
    end parseInlineScalar

    private def parseBlockMappingEntry[Ctx, Err, A](
        context: Ctx,
        indent: Int,
        visitor: Visitor[Ctx, Err, A]
    ): Result[Err | DecodeException, Ctx] =
        consumeIndent(indent)
        val keyMark       = mark()
        val key           = readUntilMappingColon()
        val valueMarkBase = mark()
        val afterColon    = readRestOfLine()
        unquoteKey(key.trim, keyMark).flatMap { parsedKey =>
            visitor.scalar(context, parsedKey, ScalarMeta(Absent, Absent, ScalarStyle.Plain, keyMark)).flatMap { c1 =>
                val trimmed        = stripComment(afterColon).trim
                val firstValueChar = afterColon.indexWhere(ch => !ch.isWhitespace)
                val valueOffset    = if firstValueChar < 0 then 0 else firstValueChar
                val valueMark = valueMarkBase.copy(index = valueMarkBase.index + valueOffset, column = valueMarkBase.column + valueOffset)
                val (anchor, tag, valueText) = readProperties(trimmed)
                blockScalarHeader(valueText) match
                    case Present((style, chomp, explicitIndent)) =>
                        parseBlockScalar(c1, indent, explicitIndent, style, chomp, anchor, tag, visitor)
                    case Absent if valueText.isEmpty && (anchor.nonEmpty || tag.nonEmpty) =>
                        withPending(anchor, tag) {
                            parseEmptyBlockMappingValue(c1, indent, visitor)
                        }
                    case Absent if valueText.isEmpty =>
                        parseEmptyBlockMappingValue(c1, indent, visitor)
                    case Absent =>
                        parseBlockMappingScalarValue(trimmed, c1, indent, visitor, valueMark)
                end match
            }
        }
    end parseBlockMappingEntry

    private def parseEmptyBlockMappingValue[Ctx, Err, A](
        context: Ctx,
        indent: Int,
        visitor: Visitor[Ctx, Err, A]
    ): Result[Err | DecodeException, Ctx] =
        skipBlankAndCommentLines()
        if pos >= input.length || (currentIndent() <= indent && !startsWithSequenceEntryAtIndent()) then
            visitor.scalar(context, "", ScalarMeta(Absent, Absent, ScalarStyle.Plain, mark()))
        else if currentIndent() == indent && startsWithSequenceEntryAtIndent() then
            parseNode(context, indent, visitor)
        else
            parseNode(context, indent + 2, visitor)
        end if
    end parseEmptyBlockMappingValue

    private def blockScalarHeader(valueText: String): Maybe[(ScalarStyle, Char, Maybe[Int])] =
        if valueText.nonEmpty && (valueText.charAt(0) == '|' || valueText.charAt(0) == '>') then
            val style          = if valueText.charAt(0) == '|' then ScalarStyle.Literal else ScalarStyle.Folded
            var explicitIndent = Maybe.empty[Int]
            valueText.drop(1).foreach { ch =>
                if ch >= '1' && ch <= '9' then explicitIndent = Maybe(ch - '0')
            }
            val chomp =
                if valueText.contains("-") then '-'
                else if valueText.contains("+") then '+'
                else ' '
            Maybe((style, chomp, explicitIndent))
        else Absent
    end blockScalarHeader

    private def parseBlockMappingScalarValue[Ctx, Err, A](
        text: String,
        context: Ctx,
        indent: Int,
        visitor: Visitor[Ctx, Err, A],
        valueMark: Mark
    ): Result[Err | DecodeException, Ctx] =
        val (anchor, tag, valueText) = readProperties(text)
        if valueText.startsWith("'") && !closedSingleQuoted(valueText) then
            collectQuotedMultiline(valueText, indent, '\'', valueMark).flatMap { value =>
                visitor.scalar(context, value.replace("''", "'"), ScalarMeta(anchor, tag, ScalarStyle.SingleQuoted, valueMark))
            }
        else if valueText.startsWith("\"") && !closedDoubleQuoted(valueText) then
            collectQuotedMultiline(valueText, indent, '"', valueMark).flatMap { value =>
                unescapeDoubleQuoted(value, valueMark.copy(index = valueMark.index + 1, column = valueMark.column + 1)).flatMap { decoded =>
                    visitor.scalar(context, decoded, ScalarMeta(anchor, tag, ScalarStyle.DoubleQuoted, valueMark))
                }
            }
        else if startsFlowCollection(valueText) then
            readFlowText(valueText, valueMark).flatMap { flowText =>
                withPending(anchor, tag)(parseInlineScalar(flowText, context, visitor, valueMark))
            }
        else if shouldCollectPlainContinuation(indent) then
            val lines = collectPlainContinuation(valueText, indent)
            visitor.scalar(context, foldBlockScalarLines(lines), ScalarMeta(anchor, tag, ScalarStyle.Plain, valueMark))
        else parseInlineScalar(text, context, visitor, valueMark)
        end if
    end parseBlockMappingScalarValue

    private def readFlowText(text: String, valueMark: Mark): Result[DecodeException, String] =
        if !startsFlowCollection(text) then Result.succeed(text)
        else
            val out      = new StringBuilder
            var depth    = 0
            var single   = false
            var double   = false
            var escape   = false
            var started  = false
            var complete = false

            def scan(part: String): Unit =
                val _ = out.append(part)
                var i = 0
                while i < part.length && !complete do
                    val ch = part.charAt(i)
                    if escape then escape = false
                    else if double && ch == '\\' then escape = true
                    else if !double && ch == '\'' then single = !single
                    else if !single && ch == '"' then double = !double
                    else if !single && !double then
                        ch match
                            case '[' | '{' =>
                                depth += 1
                                started = true
                            case ']' | '}' =>
                                depth -= 1
                                if started && depth == 0 then complete = true
                            case _ => ()
                    end if
                    i += 1
                end while
            end scan

            scan(text)
            while !complete && pos < input.length do
                val next = stripComment(readRestOfLine()).trim
                val _    = out.append('\n')
                scan(next)
            end while
            if complete then Result.succeed(out.toString)
            else Result.fail(errorAt(valueMark, "Unterminated flow collection"))
        end if
    end readFlowText

    private def startsFlowCollection(text: String): Boolean =
        text.startsWith("[") || text.startsWith("{")

    private def foldFlowScalarText(text: String): String =
        if !text.contains('\n') then text
        else text.linesIterator.map(_.trim).filter(_.nonEmpty).mkString(" ")
    end foldFlowScalarText

    private def closedSingleQuoted(valueText: String): Boolean =
        var i = 1
        while i < valueText.length do
            if valueText.charAt(i) == '\'' then
                if i + 1 < valueText.length && valueText.charAt(i + 1) == '\'' then i += 2
                else return true
            else i += 1
        end while
        false
    end closedSingleQuoted

    private def closedDoubleQuoted(valueText: String): Boolean =
        var i      = 1
        var escape = false
        while i < valueText.length do
            val ch = valueText.charAt(i)
            if escape then escape = false
            else if ch == '\\' then escape = true
            else if ch == '"' then return true
            i += 1
        end while
        false
    end closedDoubleQuoted

    private def collectQuotedMultiline(valueText: String, indent: Int, quote: Char, valueMark: Mark): Result[DecodeException, String] =
        val lines = scala.collection.mutable.ListBuffer.empty[BlockScalarLine]
        lines += BlockScalarLine(valueText.drop(1), false)
        var done = false
        while !done && pos < input.length do
            if currentLineText().trim.isEmpty then
                val _ = readRestOfLine()
                lines += BlockScalarLine("", false)
            else if currentIndent() <= indent then
                return Result.fail(errorAt(valueMark, s"Unterminated ${if quote == '\'' then "single" else "double"} quoted scalar"))
            else
                val lineText = readContinuationText(indent + 2)
                closingQuoteIndex(lineText, quote) match
                    case Present(idx) =>
                        lines += BlockScalarLine(lineText.substring(0, idx), false)
                        done = true
                    case Absent =>
                        lines += BlockScalarLine(lineText, false)
                end match
            end if
        end while
        if done then Result.succeed(foldBlockScalarLines(lines.toList))
        else Result.fail(errorAt(valueMark, s"Unterminated ${if quote == '\'' then "single" else "double"} quoted scalar"))
    end collectQuotedMultiline

    private def closingQuoteIndex(text: String, quote: Char): Maybe[Int] =
        if quote == '\'' then
            var i = 0
            while i < text.length do
                if text.charAt(i) == '\'' then
                    if i + 1 < text.length && text.charAt(i + 1) == '\'' then i += 2
                    else return Maybe(i)
                else i += 1
            end while
            Absent
        else
            var i      = 0
            var escape = false
            while i < text.length do
                val ch = text.charAt(i)
                if escape then escape = false
                else if ch == '\\' then escape = true
                else if ch == '"' then return Maybe(i)
                i += 1
            end while
            Absent
        end if
    end closingQuoteIndex

    private def shouldCollectPlainContinuation(indent: Int): Boolean =
        if pos >= input.length then false
        else
            val lineText = currentLineText()
            lineText.trim.isEmpty ||
            (currentIndent() > indent && !isBlockMappingLine(lineText) && !startsWithSequenceEntryAtIndent())
    end shouldCollectPlainContinuation

    private def collectPlainContinuation(valueText: String, indent: Int): List[BlockScalarLine] =
        val lines = scala.collection.mutable.ListBuffer(BlockScalarLine(valueText, false))
        while shouldCollectPlainContinuation(indent) do
            if currentLineText().trim.isEmpty then
                val _ = readRestOfLine()
                lines += BlockScalarLine("", false)
            else lines += BlockScalarLine(readContinuationText(indent + 2), false)
        end while
        lines.toList.reverse.dropWhile(_.isBlank).reverse
    end collectPlainContinuation

    private def readContinuationText(contentIndent: Int): String =
        val n = math.min(currentIndent(), contentIndent)
        consumeIndent(n)
        readRestOfLine()
    end readContinuationText

    private def parseCompactSequenceMapping[Ctx, Err, A](
        firstEntry: String,
        context: Ctx,
        sequenceIndent: Int,
        visitor: Visitor[Ctx, Err, A]
    ): Result[Err | DecodeException, Ctx] =
        val (anchor, tag) = takeProperties()
        run(context, visitor.mappingStart(_, Meta(anchor, tag, mark()))) { c0 =>
            parseInlineMappingEntry(firstEntry, c0, visitor).flatMap { c1 =>
                def loop(context: Ctx): Result[Err | DecodeException, Ctx] =
                    skipBlankAndCommentLines()
                    val fieldIndent = sequenceIndent + 2
                    val lineText    = if pos < input.length then currentLineText() else ""
                    if pos < input.length && currentIndent() >= fieldIndent && isBlockMappingLine(lineText) then
                        parseBlockMappingEntry(context, fieldIndent, visitor).flatMap(loop)
                    else visitor.nodeEnd(context, mark())
                end loop
                loop(c1)
            }
        }
    end parseCompactSequenceMapping

    private def parseFlowMapping[Ctx, Err, A](
        text: String,
        context: Ctx,
        visitor: Visitor[Ctx, Err, A]
    ): Result[Err | DecodeException, Ctx] =
        val (anchor, tag) = takeProperties()
        run(context, visitor.mappingStart(_, Meta(anchor, tag, mark()))) { c0 =>
            val inner   = text.substring(1, text.length - 1).trim
            val entries = splitTopLevel(inner, ',')
            def loop(rest: List[String], context: Ctx): Result[Err | DecodeException, Ctx] =
                rest match
                    case Nil => visitor.nodeEnd(context, mark())
                    case entry :: tail =>
                        parseInlineMappingEntry(entry.trim, context, visitor).flatMap(loop(tail, _))
            loop(entries.filter(_.nonEmpty), c0)
        }
    end parseFlowMapping

    private def parseFlowSequence[Ctx, Err, A](
        text: String,
        context: Ctx,
        visitor: Visitor[Ctx, Err, A]
    ): Result[Err | DecodeException, Ctx] =
        val (anchor, tag) = takeProperties()
        run(context, visitor.sequenceStart(_, Meta(anchor, tag, mark()))) { c0 =>
            val inner   = text.substring(1, text.length - 1).trim
            val entries = splitTopLevel(inner, ',')
            def loop(rest: List[String], context: Ctx): Result[Err | DecodeException, Ctx] =
                rest match
                    case Nil => visitor.nodeEnd(context, mark())
                    case entry :: tail =>
                        val trimmed = entry.trim
                        val parsed =
                            if isInlineMappingText(trimmed) then parseFlowMapping(s"{$trimmed}", context, visitor)
                            else parseInlineScalar(trimmed, context, visitor)
                        parsed.flatMap(loop(tail, _))
            loop(entries.filter(_.nonEmpty), c0)
        }
    end parseFlowSequence

    private def parseInlineMappingEntry[Ctx, Err, A](
        text: String,
        context: Ctx,
        visitor: Visitor[Ctx, Err, A]
    ): Result[Err | DecodeException, Ctx] =
        val idx = findFlowMappingSeparator(text)
        if idx < 0 then
            val keyMark = mark()
            parseInlineScalar(text, context, visitor, keyMark).flatMap { c1 =>
                visitor.scalar(c1, "", ScalarMeta(Absent, Absent, ScalarStyle.Plain, mark()))
            }
        else
            val key     = text.substring(0, idx).trim
            val value   = text.substring(idx + 1).trim
            val keyMark = mark()
            unquoteKey(key, keyMark).flatMap { parsedKey =>
                visitor.scalar(context, parsedKey, ScalarMeta(Absent, Absent, ScalarStyle.Plain, keyMark))
            }.flatMap { c1 =>
                if value.isEmpty then visitor.scalar(c1, "", ScalarMeta(Absent, Absent, ScalarStyle.Plain, mark()))
                else parseInlineScalar(value, c1, visitor)
            }
        end if
    end parseInlineMappingEntry

    private def findFlowMappingSeparator(text: String): Int =
        var start = 0
        var idx   = findTopLevel(text.substring(start), ':')
        while idx >= 0 do
            val colon = start + idx
            val key   = text.substring(0, colon).trim
            if colon == text.length - 1 || text.charAt(colon + 1).isWhitespace || quotedScalar(key) then return colon
            start = colon + 1
            idx = findTopLevel(text.substring(start), ':')
        end while
        -1
    end findFlowMappingSeparator

    private def quotedScalar(text: String): Boolean =
        text.length >= 2 && (
            (text.startsWith("\"") && text.endsWith("\"")) ||
                (text.startsWith("'") && text.endsWith("'"))
        )
    end quotedScalar

    private def parseBlockScalar[Ctx, Err, A](
        context: Ctx,
        parentIndent: Int,
        explicitIndent: Maybe[Int],
        style: ScalarStyle,
        chomp: Char,
        anchor: Maybe[String],
        tag: Maybe[String],
        visitor: Visitor[Ctx, Err, A]
    ): Result[Err | DecodeException, Ctx] =
        val indent = explicitIndent match
            case Present(n) => parentIndent + n
            case Absent     => inferredBlockScalarIndent(parentIndent)
        val lines = scala.collection.mutable.ListBuffer.empty[BlockScalarLine]
        var done  = false
        while !done && pos < input.length do
            val n    = currentIndent()
            val text = currentLineText()
            if text.trim.isEmpty then
                val _ = readRestOfLine()
                lines += BlockScalarLine("", false)
            else if n >= indent then
                consumeIndent(indent)
                lines += BlockScalarLine(readRestOfLine(), n > indent)
            else if n > parentIndent then
                return Result.fail(error(s"Expected block scalar indentation of at least $indent spaces"))
            else done = true
            end if
        end while
        val contentLines =
            if chomp == '+' then lines.toList
            else lines.toList.reverse.dropWhile(_.isBlank).reverse
        val base =
            if style == ScalarStyle.Literal then contentLines.map(_.text).mkString("\n")
            else foldBlockScalarLines(contentLines)
        val value =
            if chomp == '-' || base.isEmpty then base
            else base + "\n"
        visitor.scalar(context, value, ScalarMeta(anchor, tag, style, mark()))
    end parseBlockScalar

    private def inferredBlockScalarIndent(parentIndent: Int): Int =
        var i = pos
        while i < input.length do
            var indent = 0
            while i < input.length && input.charAt(i) == ' ' do
                indent += 1
                i += 1
            end while
            val lineStart = i
            while i < input.length && input.charAt(i) != '\n' do i += 1
            val line = input.substring(lineStart, i)
            if line.trim.nonEmpty then return indent
            if i < input.length && input.charAt(i) == '\n' then i += 1
        end while
        parentIndent + 1
    end inferredBlockScalarIndent

    private case class BlockScalarLine(text: String, moreIndented: Boolean):
        def isBlank: Boolean = text.isEmpty

    private def foldBlockScalarLines(lines: List[BlockScalarLine]): String =
        val out           = new StringBuilder
        var previousText  = Maybe.empty[BlockScalarLine]
        var pendingBlanks = 0
        lines.foreach { line =>
            if line.isBlank then pendingBlanks += 1
            else
                previousText match
                    case Absent =>
                        if pendingBlanks > 0 then out.append("\n" * pendingBlanks)
                    case Present(previous) =>
                        if pendingBlanks > 0 then
                            val preservedBreak = if previous.moreIndented || line.moreIndented then 1 else 0
                            out.append("\n" * (pendingBlanks + preservedBreak))
                        else if previous.moreIndented || line.moreIndented then out.append('\n')
                        else out.append(' ')
                end match
                out.append(line.text)
                previousText = Maybe(line)
                pendingBlanks = 0
            end if
        }
        if pendingBlanks > 0 then out.append("\n" * pendingBlanks)
        out.toString
    end foldBlockScalarLines

    private def withPending[A](anchor: Maybe[String], tag: Maybe[String])(body: => A): A =
        val prevAnchor = pendingAnchor
        val prevTag    = pendingTag
        pendingAnchor = anchor
        pendingTag = tag
        try body
        finally
            pendingAnchor = prevAnchor
            pendingTag = prevTag
        end try
    end withPending

    private def takeProperties(): (Maybe[String], Maybe[String]) =
        val out = (pendingAnchor, pendingTag)
        pendingAnchor = Absent
        pendingTag = Absent
        out
    end takeProperties

    private def readProperties(text: String): (Maybe[String], Maybe[String], String) =
        var anchor: Maybe[String] = Absent
        var tag: Maybe[String]    = Absent
        var rest                  = text.trim
        var changed               = true
        while changed do
            changed = false
            if rest.startsWith("&") then
                val (token, next) = readPropertyToken(rest)
                anchor = Maybe(token.drop(1))
                rest = next
                changed = true
            else if rest.startsWith("!") then
                val (token, next) = readPropertyToken(rest)
                tag = Maybe(token)
                rest = next
                changed = true
            end if
        end while
        (anchor, tag, rest)
    end readProperties

    private def readPropertyToken(text: String): (String, String) =
        val end = text.indexWhere(_.isWhitespace) match
            case -1 => text.length
            case n  => n
        (text.substring(0, end), text.substring(end).trim)
    end readPropertyToken

    private def run[Ctx, Err, A, B](
        context: Ctx,
        next: Ctx => Result[Err, Ctx]
    )(continue: Ctx => Result[Err | DecodeException, B]): Result[Err | DecodeException, B] =
        next(context) match
            case Result.Success(c) => continue(c)
            case Result.Failure(e) => Result.fail(e)
            case Result.Panic(e)   => Result.panic(e)
    end run

    private def mark(): Mark = Mark(pos, line, column)

    private def error(message: String): ParseException =
        errorAt(mark(), message)
    end error

    private def errorAt(location: Mark, message: String): ParseException =
        val start   = math.max(0, location.index - 30)
        val end     = math.min(input.length, location.index + 30)
        val snippet = input.substring(start, end)
        val caret   = " " * (location.index - start) + "^"
        ParseException(Yaml(), snippet + "\n" + caret, s"$message at line ${location.line}, column ${location.column}", Nil, location.index)
    end errorAt

    private def currentIndent(): Int =
        var i = pos
        var n = 0
        while i < input.length && input.charAt(i) == ' ' do
            n += 1
            i += 1
        n
    end currentIndent

    private def consumeIndent(n: Int): Unit =
        var i = 0
        while i < n && peekChar(' ') do
            advance(1)
            i += 1
    end consumeIndent

    private def currentLineText(): String =
        val end = input.indexOf('\n', pos) match
            case -1 => input.length
            case n  => n
        input.substring(pos, end)
    end currentLineText

    private def isBlockMappingLine(lineText: String): Boolean =
        val stripped = lineText.dropWhile(_ == ' ')
        val colon    = findTopLevel(stripped, ':')
        colon >= 0 && (colon == stripped.length - 1 || stripped.charAt(colon + 1).isWhitespace)
    end isBlockMappingLine

    private def startsWithAtIndent(s: String): Boolean =
        val n = currentIndent()
        input.startsWith(s, pos + n)
    end startsWithAtIndent

    private def startsWithSequenceEntryAtIndent(): Boolean =
        val n   = currentIndent()
        val idx = pos + n
        idx < input.length && input.charAt(idx) == '-' && (
            idx + 1 >= input.length ||
                input.charAt(idx + 1) == ' ' ||
                input.charAt(idx + 1) == '\n' ||
                input.charAt(idx + 1) == '\r'
        )
    end startsWithSequenceEntryAtIndent

    private def readUntilMappingColon(): String =
        val line     = currentLineText()
        val colon    = findTopLevel(line, ':')
        val keyEnd   = if colon < 0 then line.length else colon
        val start    = pos
        val keyStart = pos
        while pos < keyStart + keyEnd do advance(1)
        val out = input.substring(start, pos)
        if peekChar(':') then advance(1)
        out
    end readUntilMappingColon

    private def readRestOfLine(): String =
        val start = pos
        while pos < input.length && input.charAt(pos) != '\n' do advance(1)
        val out = input.substring(start, pos)
        if peekChar('\n') then advance(1)
        out
    end readRestOfLine

    private def stripComment(s: String): String =
        var i      = 0
        var single = false
        var double = false
        var escape = false
        while i < s.length do
            val ch = s.charAt(i)
            if escape then escape = false
            else if double && ch == '\\' then escape = true
            else if !double && ch == '\'' then single = !single
            else if !single && ch == '"' then double = !double
            else if !single && !double && ch == '#' && (i == 0 || s.charAt(i - 1).isWhitespace) then
                return s.substring(0, i)
            end if
            i += 1
        end while
        s
    end stripComment

    private def isInlineMappingText(s: String): Boolean =
        val colon = findTopLevel(s, ':')
        colon >= 0 && (colon == s.length - 1 || s.charAt(colon + 1).isWhitespace)

    private def unquoteKey(s: String, keyMark: Mark): Result[DecodeException, String] =
        if s.startsWith("'") && s.endsWith("'") && s.length >= 2 then Result.succeed(s.substring(1, s.length - 1).replace("''", "'"))
        else if s.startsWith("\"") && s.endsWith("\"") && s.length >= 2 then
            unescapeDoubleQuoted(s.substring(1, s.length - 1), keyMark.copy(index = keyMark.index + 1, column = keyMark.column + 1))
        else Result.succeed(s)
    end unquoteKey

    private def findTopLevel(s: String, target: Char): Int =
        var i      = 0
        var depth  = 0
        var single = false
        var double = false
        var escape = false
        while i < s.length do
            val ch = s.charAt(i)
            if escape then escape = false
            else if double && ch == '\\' then escape = true
            else if !double && ch == '\'' then single = !single
            else if !single && ch == '"' then double = !double
            else if !single && !double then
                ch match
                    case '[' | '{'                      => depth += 1
                    case ']' | '}'                      => depth -= 1
                    case c if c == target && depth == 0 => return i
                    case _                              => ()
            end if
            i += 1
        end while
        -1
    end findTopLevel

    private def splitTopLevel(s: String, delimiter: Char): List[String] =
        val out    = scala.collection.mutable.ListBuffer.empty[String]
        var start  = 0
        var i      = 0
        var depth  = 0
        var single = false
        var double = false
        var escape = false
        while i < s.length do
            val ch = s.charAt(i)
            if escape then escape = false
            else if double && ch == '\\' then escape = true
            else if !double && ch == '\'' then single = !single
            else if !single && ch == '"' then double = !double
            else if !single && !double then
                ch match
                    case '[' | '{' => depth += 1
                    case ']' | '}' => depth -= 1
                    case c if c == delimiter && depth == 0 =>
                        out += s.substring(start, i)
                        start = i + 1
                    case _ => ()
            end if
            i += 1
        end while
        if start <= s.length then out += s.substring(start)
        out.toList
    end splitTopLevel

    private def skipIgnorable(): Unit =
        var done = false
        while !done do
            skipBlankAndCommentLines()
            val lineText = if pos < input.length then currentLineText() else ""
            if pos < input.length && lineText.trim.startsWith("%") then
                val _ = readRestOfLine()
            else done = true
        end while
    end skipIgnorable

    private def skipBlankAndCommentLines(): Unit =
        var done = false
        while !done && pos < input.length do
            val line    = currentLineText()
            val trimmed = line.trim
            if trimmed.isEmpty || trimmed.startsWith("#") then
                val _ = readRestOfLine()
            else done = true
        end while
    end skipBlankAndCommentLines

    private def skipToNextLine(): Unit =
        while pos < input.length && input.charAt(pos) != '\n' do advance(1)
        if peekChar('\n') then advance(1)
    end skipToNextLine

    private def startsWith(s: String): Boolean = input.startsWith(s, pos)
    private def peekChar(c: Char): Boolean     = pos < input.length && input.charAt(pos) == c

    private def isDocumentMarker(marker: String): Boolean =
        val lineText = currentLineText()
        currentIndent() == 0 && lineText.startsWith(marker) && (
            lineText.length == marker.length ||
                lineText.charAt(marker.length).isWhitespace ||
                lineText.charAt(marker.length) == '#'
        )
    end isDocumentMarker

    private def advance(n: Int): Unit =
        var i = 0
        while i < n do
            if pos < input.length then
                val ch = input.charAt(pos)
                pos += 1
                if ch == '\n' then
                    line += 1
                    column = 1
                else column += 1
                end if
            end if
            i += 1
        end while
    end advance

    private def unescapeDoubleQuoted(s: String, valueMark: Mark): Result[DecodeException, String] =
        val b = new StringBuilder
        var i = 0
        while i < s.length do
            val ch = s.charAt(i)
            if ch == '\\' && i + 1 < s.length then
                i += 1
                s.charAt(i) match
                    case '0'  => b.append('\u0000')
                    case 'a'  => b.append('\u0007')
                    case 'b'  => b.append('\b')
                    case 't'  => b.append('\t')
                    case '\t' => b.append('\t')
                    case 'n'  => b.append('\n')
                    case 'v'  => b.append('\u000b')
                    case 'f'  => b.append('\f')
                    case 'r'  => b.append('\r')
                    case 'e'  => b.append('\u001b')
                    case ' '  => b.append(' ')
                    case '"'  => b.append('"')
                    case '/'  => b.append('/')
                    case '\\' => b.append('\\')
                    case 'N'  => b.append('\u0085')
                    case '_'  => b.append('\u00a0')
                    case 'L'  => b.append('\u2028')
                    case 'P'  => b.append('\u2029')
                    case 'x' =>
                        readHexEscape(s, i, 2, valueMark) match
                            case Result.Success(value) =>
                                b.append(value.toChar)
                                i += 2
                            case Result.Failure(e) => return Result.fail(e)
                            case Result.Panic(e)   => return Result.panic(e)
                    case 'u' =>
                        readHexEscape(s, i, 4, valueMark) match
                            case Result.Success(value) =>
                                b.append(value.toChar)
                                i += 4
                            case Result.Failure(e) => return Result.fail(e)
                            case Result.Panic(e)   => return Result.panic(e)
                    case 'U' =>
                        readHexEscape(s, i, 8, valueMark) match
                            case Result.Success(value) =>
                                try b.appendAll(Character.toChars(value))
                                catch
                                    case _: IllegalArgumentException =>
                                        return Result.fail(errorAt(
                                            valueMark.copy(index = valueMark.index + i - 1, column = valueMark.column + i - 1),
                                            s"Invalid escape sequence \\${s.charAt(i)}${s.substring(i + 1, math.min(s.length, i + 9))}"
                                        ))
                                end try
                                i += 8
                            case Result.Failure(e) => return Result.fail(e)
                            case Result.Panic(e)   => return Result.panic(e)
                    case other =>
                        return Result.fail(errorAt(
                            valueMark.copy(index = valueMark.index + i - 1, column = valueMark.column + i - 1),
                            s"Invalid escape sequence \\$other"
                        ))
                end match
            else if ch == '\\' then
                return Result.fail(errorAt(
                    valueMark.copy(index = valueMark.index + i, column = valueMark.column + i),
                    "Invalid escape sequence \\"
                ))
            else b.append(ch)
            end if
            i += 1
        end while
        Result.succeed(b.toString)
    end unescapeDoubleQuoted

    private def readHexEscape(s: String, escapeIndex: Int, digits: Int, valueMark: Mark): Result[DecodeException, Int] =
        val start = escapeIndex + 1
        val end   = start + digits
        if end > s.length then
            Result.fail(errorAt(
                valueMark.copy(index = valueMark.index + escapeIndex - 1, column = valueMark.column + escapeIndex - 1),
                s"Invalid escape sequence \\${s.charAt(escapeIndex)}${s.substring(start)}"
            ))
        else
            val hex = s.substring(start, end)
            if hex.exists(ch => Character.digit(ch, 16) < 0) then
                Result.fail(errorAt(
                    valueMark.copy(index = valueMark.index + escapeIndex - 1, column = valueMark.column + escapeIndex - 1),
                    s"Invalid escape sequence \\${s.charAt(escapeIndex)}$hex"
                ))
            else Result.succeed(java.lang.Long.parseLong(hex, 16).toInt)
            end if
        end if
    end readHexEscape

end YamlParser

object YamlParser:
    def apply(input: String)(using Frame): YamlParser = new YamlParser(input)

    def toJson(input: Span[Byte])(using Frame): Span[Byte] =
        toJson(input, Yaml.DefaultMaxDepth, Yaml.DefaultMaxCollectionSize)

    def toJson(input: Span[Byte], maxDepth: Int, maxCollectionSize: Int)(using Frame): Span[Byte] =
        val s       = new String(input.toArrayUnsafe, StandardCharsets.UTF_8)
        val visitor = JsonBuildingVisitor(maxDepth, maxCollectionSize)
        apply(s).visit(())(visitor) match
            case Result.Success(json: String)       => Span.from(json.getBytes(StandardCharsets.UTF_8))
            case Result.Failure(e: DecodeException) => throw e
            case Result.Panic(e)                    => throw e
        end match
    end toJson

    final private class JsonBuildingVisitor(maxDepth: Int, maxCollectionSize: Int)(using Frame)
        extends Yaml.Visitor[Unit, DecodeException, String]:
        final private class JsonFrame(val isMapping: Boolean, var first: Boolean, var expectingKey: Boolean, var count: Int)
        final private class Capture(val name: String, val start: Int, val depth: Int)

        private val out                       = new StringBuilder
        private val anchors                   = scala.collection.mutable.Map.empty[String, String]
        private var stack: List[JsonFrame]    = Nil
        private var captures: List[Capture]   = Nil
        private def current: Maybe[JsonFrame] = Maybe.fromOption(stack.headOption)
        private def inMapping: Boolean        = current.exists(_.isMapping)
        private def expectingKey: Boolean     = current.exists(f => f.isMapping && f.expectingKey)

        def streamStart(context: Unit, mark: Yaml.Mark): Result[DecodeException, Unit]   = Result.unit
        def documentStart(context: Unit, mark: Yaml.Mark): Result[DecodeException, Unit] = Result.unit
        def documentEnd(context: Unit, mark: Yaml.Mark): Result[DecodeException, Unit]   = Result.unit
        def streamEnd(context: Unit, mark: Yaml.Mark): Result[DecodeException, String]   = Result.succeed(out.toString)

        def mappingStart(context: Unit, meta: Yaml.Meta): Result[DecodeException, Unit] =
            valuePrefix()
            startCapture(meta.anchor)
            out.append('{')
            stack = JsonFrame(true, true, true, 0) :: stack
            checkDepth()
            Result.unit
        end mappingStart

        def sequenceStart(context: Unit, meta: Yaml.Meta): Result[DecodeException, Unit] =
            valuePrefix()
            startCapture(meta.anchor)
            out.append('[')
            stack = JsonFrame(false, true, false, 0) :: stack
            checkDepth()
            Result.unit
        end sequenceStart

        def scalar(context: Unit, value: String, meta: Yaml.ScalarMeta): Result[DecodeException, Unit] =
            if inMapping && expectingKey then
                entryPrefix()
                appendQuoted(value)
                out.append(':')
                current.foreach(_.expectingKey = false)
            else
                valuePrefix()
                startCapture(meta.anchor)
                appendResolvedScalar(value, meta.style, meta.tag)
                finishScalarCapture()
                current.foreach { f =>
                    if f.isMapping then f.expectingKey = true
                }
            end if
            Result.unit
        end scalar

        def alias(context: Unit, name: String, mark: Yaml.Mark): Result[DecodeException, Unit] =
            anchors.get(name) match
                case Some(value) =>
                    valuePrefix()
                    out.append(value)
                    current.foreach { f =>
                        if f.isMapping then f.expectingKey = true
                    }
                    Result.unit
                case None =>
                    Result.fail(ParseException(
                        Yaml(),
                        name,
                        s"Unknown alias '$name' at line ${mark.line}, column ${mark.column}",
                        Nil,
                        mark.index
                    ))
            end match
        end alias

        def nodeEnd(context: Unit, mark: Yaml.Mark): Result[DecodeException, Unit] =
            if stack.nonEmpty then
                val ended = stack.head
                stack = stack.tail
                out.append(if ended.isMapping then '}' else ']')
                finishCollectionCapture()
                current.foreach { parent =>
                    if parent.isMapping && !parent.expectingKey then parent.expectingKey = true
                }
            end if
            Result.unit
        end nodeEnd

        private def entryPrefix(): Unit =
            current.foreach { f =>
                if !f.first then out.append(',')
                f.first = false
                f.count += 1
                if f.count > maxCollectionSize then
                    throw LimitExceededException("Collection size", f.count, maxCollectionSize)
            }

        private def valuePrefix(): Unit =
            if !inMapping then entryPrefix()

        private def checkDepth(): Unit =
            val depth = stack.size
            if depth > maxDepth then
                throw LimitExceededException("Nesting depth", depth, maxDepth)
        end checkDepth

        private def startCapture(anchor: Maybe[String]): Unit =
            anchor.foreach { name =>
                captures = Capture(name, out.length, stack.size) :: captures
            }

        private def finishScalarCapture(): Unit =
            captures match
                case capture :: rest if capture.depth == stack.size =>
                    anchors(capture.name) = out.substring(capture.start)
                    captures = rest
                case _ => ()

        private def finishCollectionCapture(): Unit =
            captures match
                case capture :: rest if capture.depth == stack.size =>
                    anchors(capture.name) = out.substring(capture.start)
                    captures = rest
                case _ => ()

        private def appendResolvedScalar(value: String, style: Yaml.ScalarStyle, tag: Maybe[String]): Unit =
            standardScalarTag(tag) match
                case Present("str") =>
                    appendQuoted(value)
                case Present("int") =>
                    appendTaggedInt(value)
                case Present("bool") =>
                    appendTaggedBool(value)
                case Present("float") =>
                    appendTaggedFloat(value)
                case Present("null") =>
                    out.append("null")
                case _ if style != Yaml.ScalarStyle.Plain =>
                    appendQuoted(value)
                case _ =>
                    appendCoreScalar(value)
            end match
        end appendResolvedScalar

        private def appendCoreScalar(value: String): Unit =
            YamlScalars.resolveCore(value) match
                case YamlScalars.Core.Null          => out.append("null")
                case YamlScalars.Core.Bool(value)   => out.append(if value then "true" else "false")
                case YamlScalars.Core.Number(value) => out.append(value)
                case YamlScalars.Core.Special(value) =>
                    appendQuoted(value)
                case YamlScalars.Core.Str(value) =>
                    appendQuoted(value)
            end match
        end appendCoreScalar

        private def standardScalarTag(tag: Maybe[String]): Maybe[String] =
            tag match
                case Present("!" | "!!str" | "tag:yaml.org,2002:str" | "!<tag:yaml.org,2002:str>") =>
                    Maybe("str")
                case Present("!!int" | "tag:yaml.org,2002:int" | "!<tag:yaml.org,2002:int>") =>
                    Maybe("int")
                case Present("!!bool" | "tag:yaml.org,2002:bool" | "!<tag:yaml.org,2002:bool>") =>
                    Maybe("bool")
                case Present("!!float" | "tag:yaml.org,2002:float" | "!<tag:yaml.org,2002:float>") =>
                    Maybe("float")
                case Present("!!null" | "tag:yaml.org,2002:null" | "!<tag:yaml.org,2002:null>") =>
                    Maybe("null")
                case _ =>
                    Absent
            end match
        end standardScalarTag

        private def appendTaggedInt(value: String): Unit =
            YamlScalars.parseCoreInt(value) match
                case Present(number) => out.append(number)
                case Absent          => appendQuoted(value)
        end appendTaggedInt

        private def appendTaggedBool(value: String): Unit =
            value match
                case "true" | "True" | "TRUE"    => out.append("true")
                case "false" | "False" | "FALSE" => out.append("false")
                case other                       => appendQuoted(other)
            end match
        end appendTaggedBool

        private def appendTaggedFloat(value: String): Unit =
            YamlScalars.parseCoreFloat(value) match
                case Present(YamlScalars.Core.Number(number))   => out.append(number)
                case Present(YamlScalars.Core.Special(special)) => appendQuoted(special)
                case _                                          => appendQuoted(value)
        end appendTaggedFloat

        private def appendQuoted(value: String): Unit =
            out.append('"')
            value.foreach {
                case '"'  => out.append("\\\"")
                case '\\' => out.append("\\\\")
                case '\n' => out.append("\\n")
                case '\r' => out.append("\\r")
                case '\t' => out.append("\\t")
                case c    => out.append(c)
            }
            out.append('"')
        end appendQuoted
    end JsonBuildingVisitor
end YamlParser
