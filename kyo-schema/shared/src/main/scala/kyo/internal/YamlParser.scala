package kyo.internal

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
                            val (anchor, tag, valueText) = readProperties(rest)
                            blockScalarHeader(valueText) match
                                case Present((style, chomp, explicitIndent)) =>
                                    parseBlockScalar(context, indent, explicitIndent, style, chomp, anchor, tag, visitor)
                                case Absent =>
                                    readFlowText(rest, valueMark).flatMap { text =>
                                        if isInlineMappingText(text) then parseCompactSequenceMapping(text, context, indent, visitor)
                                        else parseInlineScalar(text, context, visitor, valueMark)
                                    }
                            end match
                    parsed.flatMap(loop)
                end if
            end loop
            loop(c0)
        }
    end parseBlockSequence

    private def parseScalarValue[Ctx, Err, A](context: Ctx, visitor: Visitor[Ctx, Err, A]): Result[Err | DecodeException, Ctx] =
        val valueMark                = mark()
        val indent                   = currentIndent()
        val text                     = stripComment(readRestOfLine()).trim
        val (anchor, tag, valueText) = readProperties(text)
        blockScalarHeader(valueText) match
            case Present((style, chomp, explicitIndent)) =>
                parseBlockScalar(context, indent, explicitIndent, style, chomp, anchor, tag, visitor)
            case Absent =>
                readFlowText(text, valueMark).flatMap(parseInlineScalar(_, context, visitor, valueMark))
        end match
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
        YamlSource.startsFlowCollection(text)
    end startsFlowCollection

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
            parseCompactMappingEntry(firstEntry, c0, sequenceIndent + 2, visitor).flatMap { c1 =>
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

    private def parseCompactMappingEntry[Ctx, Err, A](
        text: String,
        context: Ctx,
        indent: Int,
        visitor: Visitor[Ctx, Err, A]
    ): Result[Err | DecodeException, Ctx] =
        val idx = findFlowMappingSeparator(text)
        if idx < 0 then parseInlineMappingEntry(text, context, visitor)
        else
            val key     = text.substring(0, idx).trim
            val value   = text.substring(idx + 1).trim
            val keyMark = mark()
            unquoteKey(key, keyMark).flatMap { parsedKey =>
                visitor.scalar(context, parsedKey, ScalarMeta(Absent, Absent, ScalarStyle.Plain, keyMark))
            }.flatMap { c1 =>
                val (anchor, tag, valueText) = readProperties(value)
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
                        parseBlockMappingScalarValue(value, c1, indent, visitor, mark())
                end match
            }
        end if
    end parseCompactMappingEntry

    private def parseFlowMapping[Ctx, Err, A](
        text: String,
        context: Ctx,
        visitor: Visitor[Ctx, Err, A]
    ): Result[Err | DecodeException, Ctx] =
        val (anchor, tag) = takeProperties()
        run(context, visitor.mappingStart(_, Meta(anchor, tag, mark()))) { c0 =>
            val inner = text.substring(1, text.length - 1).trim
            foldTopLevel(inner, ',', c0)(parseInlineMappingEntry(_, _, visitor)).flatMap(visitor.nodeEnd(_, mark()))
        }
    end parseFlowMapping

    private def parseFlowSequence[Ctx, Err, A](
        text: String,
        context: Ctx,
        visitor: Visitor[Ctx, Err, A]
    ): Result[Err | DecodeException, Ctx] =
        val (anchor, tag) = takeProperties()
        run(context, visitor.sequenceStart(_, Meta(anchor, tag, mark()))) { c0 =>
            val inner = text.substring(1, text.length - 1).trim
            foldTopLevel(inner, ',', c0) { (entry, context) =>
                if isInlineMappingText(entry) then parseFlowMapping(s"{$entry}", context, visitor)
                else parseInlineScalar(entry, context, visitor)
            }.flatMap(visitor.nodeEnd(_, mark()))
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
        YamlSource.flowMappingSeparator(text)
    end findFlowMappingSeparator

    private def trimmedRange(text: String, start: Int, end: Int): (Int, Int) =
        YamlSource.trimmedRange(text, start, end)
    end trimmedRange

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
        YamlSource.indent(input, pos)
    end currentIndent

    private def consumeIndent(n: Int): Unit =
        var i = 0
        while i < n && peekChar(' ') do
            advance(1)
            i += 1
    end consumeIndent

    private def currentLineText(): String =
        YamlSource.line(input, pos)
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
        YamlSource.startsSequenceEntryAtIndent(input, pos)
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
        YamlSource.stripComment(s)
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
        YamlSource.findTopLevel(s, target)
    end findTopLevel

    private def findTopLevel(s: String, target: Char, from: Int): Int =
        YamlSource.findTopLevel(s, target, from)
    end findTopLevel

    private def foldTopLevel[Ctx, Err](
        s: String,
        delimiter: Char,
        context: Ctx
    )(parse: (String, Ctx) => Result[Err | DecodeException, Ctx]): Result[Err | DecodeException, Ctx] =
        var current = context
        var start   = 0
        var i       = 0
        var depth   = 0
        var single  = false
        var double  = false
        var escape  = false
        def emit(end: Int): Result[Err | DecodeException, Unit] =
            val (from, to) = trimmedRange(s, start, end)
            if from < to then
                parse(s.substring(from, to), current) match
                    case Result.Success(next) =>
                        current = next
                        Result.unit
                    case Result.Failure(e) => Result.fail(e)
                    case Result.Panic(e)   => Result.panic(e)
            else Result.unit
            end if
        end emit
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
                        emit(i) match
                            case Result.Success(_) => ()
                            case Result.Failure(e) => return Result.fail(e)
                            case Result.Panic(e)   => return Result.panic(e)
                        end match
                        start = i + 1
                    case _ => ()
            end if
            i += 1
        end while
        emit(s.length) match
            case Result.Success(_) => Result.succeed(current)
            case Result.Failure(e) => Result.fail(e)
            case Result.Panic(e)   => Result.panic(e)
        end match
    end foldTopLevel

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
        val next = YamlSource.skipBlankAndCommentLines(input, pos)
        advance(next - pos)
    end skipBlankAndCommentLines

    private def skipToNextLine(): Unit =
        while pos < input.length && input.charAt(pos) != '\n' do advance(1)
        if peekChar('\n') then advance(1)
    end skipToNextLine

    private def startsWith(s: String): Boolean = input.startsWith(s, pos)
    private def peekChar(c: Char): Boolean     = pos < input.length && input.charAt(pos) == c

    private def isDocumentMarker(marker: String): Boolean =
        YamlSource.isDocumentMarker(input, pos, marker)
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
end YamlParser
