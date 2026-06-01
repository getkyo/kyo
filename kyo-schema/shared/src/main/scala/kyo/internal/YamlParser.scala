package kyo.internal

import kyo.*
import scala.annotation.tailrec

final private[kyo] class YamlParser private (private val input: String)(using frame: Frame):
    import Yaml.*

    private var pos: Int                     = 0
    private var line: Int                    = 1
    private var column: Int                  = 1
    private var pendingAnchor: Maybe[Anchor] = Absent
    private var pendingTag: Maybe[YamlTag]   = Absent

    def visitEvents[Ctx, Err](context: Ctx)(handler: Yaml.Events.Handler[Ctx, Err]): Result[Err | DecodeException, Ctx] =
        run(context, handler.streamStart(_, mark())) { c1 =>
            skipIgnorable()
            if isDocumentMarker("---") then
                val _ = readRestOfLine()
            run(c1, handler.documentStart(_, mark())) { c2 =>
                parseNode(c2, 0, handler).flatMap { c3 =>
                    skipIgnorable()
                    run(c3, handler.documentEnd(_, mark())) { c4 =>
                        skipIgnorable()
                        if isDocumentMarker("...") then
                            val endMark = mark()
                            val _       = readRestOfLine()
                            skipIgnorable()
                            if pos < input.length then
                                Result.fail(errorAt(endMark, "Unexpected content after YAML document end"))
                            else handler.streamEnd(c4, mark())
                        else if isDocumentMarker("---") then
                            Result.fail(error("Expected a single YAML document for this parser entry point"))
                        else if pos < input.length then
                            Result.fail(error("Unexpected content after YAML document"))
                        else handler.streamEnd(c4, mark())
                        end if
                    }
                }
            }
        }
    end visitEvents

    private def parseNode[Ctx, Err](
        context: Ctx,
        indent: Int,
        visitor: Yaml.Events.Handler[Ctx, Err]
    ): Result[Err | DecodeException, Ctx] =
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

    private def parseBlockMapping[Ctx, Err](
        context: Ctx,
        indent: Int,
        visitor: Yaml.Events.Handler[Ctx, Err]
    ): Result[Err | DecodeException, Ctx] =
        val (anchor, tag) = takeProperties()
        run(context, visitor.mappingStart(_, Meta(anchor, tag, mark()), Absent)) { c0 =>
            def loop(context: Ctx): Result[Err | DecodeException, Ctx] =
                skipBlankAndCommentLines()
                val lineText = if pos < input.length then currentLineText() else ""
                if pos >= input.length || currentIndent() < indent || !isBlockMappingLine(lineText) then
                    visitor.collectionEnd(context, Events.CollectionKind.Mapping, mark())
                else
                    parseBlockMappingEntry(context, indent, visitor).flatMap(loop)
                end if
            end loop
            loop(c0)
        }
    end parseBlockMapping

    private def parseBlockSequence[Ctx, Err](
        context: Ctx,
        indent: Int,
        visitor: Yaml.Events.Handler[Ctx, Err]
    ): Result[Err | DecodeException, Ctx] =
        val (anchor, tag) = takeProperties()
        run(context, visitor.sequenceStart(_, Meta(anchor, tag, mark()), Absent)) { c0 =>
            def loop(context: Ctx): Result[Err | DecodeException, Ctx] =
                skipBlankAndCommentLines()
                if pos >= input.length || currentIndent() < indent || !startsWithSequenceEntryAtIndent() then
                    visitor.collectionEnd(context, Events.CollectionKind.Sequence, mark())
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

    private def parseScalarValue[Ctx, Err](
        context: Ctx,
        visitor: Yaml.Events.Handler[Ctx, Err]
    ): Result[Err | DecodeException, Ctx] =
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

    private def parseInlineScalar[Ctx, Err](
        text: String,
        context: Ctx,
        visitor: Yaml.Events.Handler[Ctx, Err],
        valueMark: Mark = mark()
    ): Result[Err | DecodeException, Ctx] =
        val (anchor, tag, valueText) = readProperties(text)
        if valueText.startsWith("*") && !valueText.exists(_.isWhitespace) then
            visitor.alias(context, Anchor(valueText.drop(1)), valueMark)
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

    private def parseBlockMappingEntry[Ctx, Err](
        context: Ctx,
        indent: Int,
        visitor: Yaml.Events.Handler[Ctx, Err]
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

    private def parseEmptyBlockMappingValue[Ctx, Err](
        context: Ctx,
        indent: Int,
        visitor: Yaml.Events.Handler[Ctx, Err]
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

    private def parseBlockMappingScalarValue[Ctx, Err](
        text: String,
        context: Ctx,
        indent: Int,
        visitor: Yaml.Events.Handler[Ctx, Err],
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
                @tailrec def loop(i: Int): Unit =
                    if i < part.length && !complete then
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
                        loop(i + 1)
                end loop
                loop(0)
            end scan

            scan(text)
            @tailrec def collect(): Result[DecodeException, String] =
                if complete then Result.succeed(out.toString)
                else if pos < input.length then
                    val next = stripComment(readRestOfLine()).trim
                    val _    = out.append('\n')
                    scan(next)
                    collect()
                else Result.fail(errorAt(valueMark, "Unterminated flow collection"))
            end collect
            collect()
        end if
    end readFlowText

    private def startsFlowCollection(text: String): Boolean =
        YamlSource.startsFlowCollection(text)
    end startsFlowCollection

    private def foldFlowScalarText(text: String): String =
        YamlSource.foldFlowScalarText(text)
    end foldFlowScalarText

    private def closedSingleQuoted(valueText: String): Boolean =
        @tailrec def loop(i: Int): Boolean =
            if i >= valueText.length then false
            else
                if valueText.charAt(i) == '\'' then
                    if i + 1 < valueText.length && valueText.charAt(i + 1) == '\'' then loop(i + 2)
                    else true
                else loop(i + 1)
                end if
        end loop
        loop(1)
    end closedSingleQuoted

    private def closedDoubleQuoted(valueText: String): Boolean =
        @tailrec def loop(i: Int, escape: Boolean): Boolean =
            if i >= valueText.length then false
            else
                val ch = valueText.charAt(i)
                if escape then loop(i + 1, escape = false)
                else if ch == '\\' then loop(i + 1, escape = true)
                else if ch == '"' then true
                else loop(i + 1, escape = false)
                end if
        end loop
        loop(1, escape = false)
    end closedDoubleQuoted

    private def collectQuotedMultiline(valueText: String, indent: Int, quote: Char, valueMark: Mark): Result[DecodeException, String] =
        val lines = scala.collection.mutable.ListBuffer.empty[BlockScalarLine]
        lines += BlockScalarLine(valueText.drop(1), false)

        @tailrec def loop(): Result[DecodeException, String] =
            if pos >= input.length then
                Result.fail(errorAt(valueMark, s"Unterminated ${if quote == '\'' then "single" else "double"} quoted scalar"))
            else if currentLineText().trim.isEmpty then
                val _ = readRestOfLine()
                lines += BlockScalarLine("", false)
                loop()
            else if currentIndent() <= indent then
                Result.fail(errorAt(valueMark, s"Unterminated ${if quote == '\'' then "single" else "double"} quoted scalar"))
            else
                val lineText = readContinuationText(indent + 2)
                closingQuoteIndex(lineText, quote) match
                    case Present(idx) =>
                        lines += BlockScalarLine(lineText.substring(0, idx), false)
                        Result.succeed(foldBlockScalarLines(lines.toList))
                    case Absent =>
                        lines += BlockScalarLine(lineText, false)
                        loop()
                end match
            end if
        end loop

        loop()
    end collectQuotedMultiline

    private def closingQuoteIndex(text: String, quote: Char): Maybe[Int] =
        if quote == '\'' then
            @tailrec def loop(i: Int): Maybe[Int] =
                if i >= text.length then Absent
                else
                    if text.charAt(i) == '\'' then
                        if i + 1 < text.length && text.charAt(i + 1) == '\'' then loop(i + 2)
                        else Maybe(i)
                    else loop(i + 1)
                    end if
            end loop
            loop(0)
        else
            @tailrec def loop(i: Int, escape: Boolean): Maybe[Int] =
                if i >= text.length then Absent
                else
                    val ch = text.charAt(i)
                    if escape then loop(i + 1, escape = false)
                    else if ch == '\\' then loop(i + 1, escape = true)
                    else if ch == '"' then Maybe(i)
                    else loop(i + 1, escape = false)
                    end if
            end loop
            loop(0, escape = false)
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
        @tailrec def loop(): Unit =
            if shouldCollectPlainContinuation(indent) then
                if currentLineText().trim.isEmpty then
                    val _ = readRestOfLine()
                    lines += BlockScalarLine("", false)
                else lines += BlockScalarLine(readContinuationText(indent + 2), false)
                end if
                loop()
        end loop
        loop()
        lines.toList.reverse.dropWhile(_.isBlank).reverse
    end collectPlainContinuation

    private def readContinuationText(contentIndent: Int): String =
        val n = math.min(currentIndent(), contentIndent)
        consumeIndent(n)
        readRestOfLine()
    end readContinuationText

    private def parseCompactSequenceMapping[Ctx, Err](
        firstEntry: String,
        context: Ctx,
        sequenceIndent: Int,
        visitor: Yaml.Events.Handler[Ctx, Err]
    ): Result[Err | DecodeException, Ctx] =
        val (anchor, tag) = takeProperties()
        run(context, visitor.mappingStart(_, Meta(anchor, tag, mark()), Absent)) { c0 =>
            parseCompactMappingEntry(firstEntry, c0, sequenceIndent + 2, visitor).flatMap { c1 =>
                def loop(context: Ctx): Result[Err | DecodeException, Ctx] =
                    skipBlankAndCommentLines()
                    val fieldIndent = sequenceIndent + 2
                    val lineText    = if pos < input.length then currentLineText() else ""
                    if pos < input.length && currentIndent() >= fieldIndent && isBlockMappingLine(lineText) then
                        parseBlockMappingEntry(context, fieldIndent, visitor).flatMap(loop)
                    else visitor.collectionEnd(context, Events.CollectionKind.Mapping, mark())
                end loop
                loop(c1)
            }
        }
    end parseCompactSequenceMapping

    private def parseCompactMappingEntry[Ctx, Err](
        text: String,
        context: Ctx,
        indent: Int,
        visitor: Yaml.Events.Handler[Ctx, Err]
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

    private def parseFlowMapping[Ctx, Err](
        text: String,
        context: Ctx,
        visitor: Yaml.Events.Handler[Ctx, Err]
    ): Result[Err | DecodeException, Ctx] =
        val (anchor, tag) = takeProperties()
        run(context, visitor.mappingStart(_, Meta(anchor, tag, mark()), Absent)) { c0 =>
            val inner = text.substring(1, text.length - 1).trim
            foldTopLevel(inner, ',', c0)(parseInlineMappingEntry(_, _, visitor))
                .flatMap(visitor.collectionEnd(_, Events.CollectionKind.Mapping, mark()))
        }
    end parseFlowMapping

    private def parseFlowSequence[Ctx, Err](
        text: String,
        context: Ctx,
        visitor: Yaml.Events.Handler[Ctx, Err]
    ): Result[Err | DecodeException, Ctx] =
        val (anchor, tag) = takeProperties()
        run(context, visitor.sequenceStart(_, Meta(anchor, tag, mark()), Absent)) { c0 =>
            val inner = text.substring(1, text.length - 1).trim
            foldTopLevel(inner, ',', c0) { (entry, context) =>
                if isInlineMappingText(entry) then parseFlowMapping(s"{$entry}", context, visitor)
                else parseInlineScalar(entry, context, visitor)
            }.flatMap(visitor.collectionEnd(_, Events.CollectionKind.Sequence, mark()))
        }
    end parseFlowSequence

    private def parseInlineMappingEntry[Ctx, Err](
        text: String,
        context: Ctx,
        visitor: Yaml.Events.Handler[Ctx, Err]
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

    private def parseBlockScalar[Ctx, Err](
        context: Ctx,
        parentIndent: Int,
        explicitIndent: Maybe[Int],
        style: ScalarStyle,
        chomp: Char,
        anchor: Maybe[Anchor],
        tag: Maybe[YamlTag],
        visitor: Yaml.Events.Handler[Ctx, Err]
    ): Result[Err | DecodeException, Ctx] =
        val indent = explicitIndent match
            case Present(n) => parentIndent + n
            case Absent     => inferredBlockScalarIndent(parentIndent)
        val lines = scala.collection.mutable.ListBuffer.empty[BlockScalarLine]

        @tailrec def loop(): Result[DecodeException, Unit] =
            if pos >= input.length then Result.unit
            else
                val n    = currentIndent()
                val text = currentLineText()
                if text.trim.isEmpty then
                    val _ = readRestOfLine()
                    lines += BlockScalarLine("", false)
                    loop()
                else if n >= indent then
                    consumeIndent(indent)
                    lines += BlockScalarLine(readRestOfLine(), n > indent)
                    loop()
                else if n > parentIndent then
                    Result.fail(error(s"Expected block scalar indentation of at least $indent spaces"))
                else Result.unit
                end if
        end loop

        loop().flatMap { _ =>
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
        }
    end parseBlockScalar

    private def inferredBlockScalarIndent(parentIndent: Int): Int =
        @tailrec def skipSpaces(i: Int, indent: Int): (Int, Int) =
            if i < input.length && input.charAt(i) == ' ' then skipSpaces(i + 1, indent + 1)
            else (i, indent)
        end skipSpaces

        @tailrec def lineEnd(i: Int): Int =
            if i < input.length && input.charAt(i) != '\n' then lineEnd(i + 1)
            else i
        end lineEnd

        @tailrec def lineHasText(i: Int, stop: Int): Boolean =
            if i >= stop then false
            else if input.charAt(i).isWhitespace then lineHasText(i + 1, stop)
            else true
        end lineHasText

        @tailrec def loop(i: Int): Int =
            if i >= input.length then parentIndent + 1
            else
                val (lineStart, indent) = skipSpaces(i, 0)
                val stop                = lineEnd(lineStart)
                if lineHasText(lineStart, stop) then indent
                else loop(if stop < input.length then stop + 1 else input.length)
                end if
            end if
        end loop

        loop(pos)
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

    private def withPending[A](anchor: Maybe[Anchor], tag: Maybe[YamlTag])(body: => A): A =
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

    private def takeProperties(): (Maybe[Anchor], Maybe[YamlTag]) =
        val out = (pendingAnchor, pendingTag)
        pendingAnchor = Absent
        pendingTag = Absent
        out
    end takeProperties

    private def readProperties(text: String): (Maybe[Anchor], Maybe[YamlTag], String) =
        @tailrec def loop(anchor: Maybe[Anchor], tag: Maybe[YamlTag], rest: String): (Maybe[Anchor], Maybe[YamlTag], String) =
            if rest.startsWith("&") then
                val (token, next) = readPropertyToken(rest)
                loop(Maybe(Anchor(token.drop(1))), tag, next)
            else if rest.startsWith("!") then
                val (token, next) = readPropertyToken(rest)
                loop(anchor, Maybe(YamlTag(token)), next)
            else (anchor, tag, rest)
            end if
        end loop
        loop(Absent, Absent, text.trim)
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
        @tailrec def loop(i: Int): Unit =
            if i < n && peekChar(' ') then
                advance(1)
                loop(i + 1)
        end loop
        loop(0)
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
        @tailrec def loop(): Unit =
            if pos < keyStart + keyEnd then
                advance(1)
                loop()
        end loop
        loop()
        val out = input.substring(start, pos)
        if peekChar(':') then advance(1)
        out
    end readUntilMappingColon

    private def readRestOfLine(): String =
        val start = pos
        @tailrec def loop(): Unit =
            if pos < input.length && input.charAt(pos) != '\n' then
                advance(1)
                loop()
        end loop
        loop()
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
        def emit(start: Int, end: Int, current: Ctx): Result[Err | DecodeException, Ctx] =
            val (from, to) = trimmedRange(s, start, end)
            if from < to then parse(s.substring(from, to), current)
            else Result.succeed(current)
            end if
        end emit

        @tailrec def loop(
            i: Int,
            start: Int,
            depth: Int,
            single: Boolean,
            double: Boolean,
            escape: Boolean,
            current: Ctx
        ): Result[Err | DecodeException, Ctx] =
            if i >= s.length then emit(start, s.length, current)
            else
                val ch = s.charAt(i)
                if escape then loop(i + 1, start, depth, single, double, escape = false, current)
                else if double && ch == '\\' then loop(i + 1, start, depth, single, double, escape = true, current)
                else if !double && ch == '\'' then loop(i + 1, start, depth, !single, double, escape = false, current)
                else if !single && ch == '"' then loop(i + 1, start, depth, single, !double, escape = false, current)
                else if !single && !double then
                    ch match
                        case '[' | '{' =>
                            loop(i + 1, start, depth + 1, single, double, escape = false, current)
                        case ']' | '}' =>
                            loop(i + 1, start, depth - 1, single, double, escape = false, current)
                        case c if c == delimiter && depth == 0 =>
                            emit(start, i, current) match
                                case Result.Success(next) => loop(i + 1, i + 1, depth, single, double, escape = false, next)
                                case Result.Failure(e)    => Result.fail(e)
                                case Result.Panic(e)      => Result.panic(e)
                            end match
                        case _ =>
                            loop(i + 1, start, depth, single, double, escape = false, current)
                    end match
                else loop(i + 1, start, depth, single, double, escape = false, current)
                end if
            end if
        end loop

        loop(0, 0, 0, single = false, double = false, escape = false, context)
    end foldTopLevel

    private def skipIgnorable(): Unit =
        @tailrec def loop(): Unit =
            skipBlankAndCommentLines()
            val lineText = if pos < input.length then currentLineText() else ""
            if pos < input.length && lineText.trim.startsWith("%") then
                val _ = readRestOfLine()
                loop()
        end loop
        loop()
    end skipIgnorable

    private def skipBlankAndCommentLines(): Unit =
        val next = YamlSource.skipBlankAndCommentLines(input, pos)
        advance(next - pos)
    end skipBlankAndCommentLines

    private def skipToNextLine(): Unit =
        @tailrec def loop(): Unit =
            if pos < input.length && input.charAt(pos) != '\n' then
                advance(1)
                loop()
        end loop
        loop()
        if peekChar('\n') then advance(1)
    end skipToNextLine

    private def startsWith(s: String): Boolean = input.startsWith(s, pos)
    private def peekChar(c: Char): Boolean     = pos < input.length && input.charAt(pos) == c

    private def isDocumentMarker(marker: String): Boolean =
        YamlSource.isDocumentMarker(input, pos, marker)
    end isDocumentMarker

    private def advance(n: Int): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < n then
                if pos < input.length then
                    val ch = input.charAt(pos)
                    pos += 1
                    if ch == '\n' then
                        line += 1
                        column = 1
                    else column += 1
                    end if
                end if
                loop(i + 1)
        end loop
        loop(0)
    end advance

    private def unescapeDoubleQuoted(s: String, valueMark: Mark): Result[DecodeException, String] =
        val b = new StringBuilder

        def invalidEscape(escapeIndex: Int, text: String): Result[DecodeException, String] =
            Result.fail(errorAt(
                valueMark.copy(index = valueMark.index + escapeIndex - 1, column = valueMark.column + escapeIndex - 1),
                text
            ))
        end invalidEscape

        @tailrec def loop(i: Int): Result[DecodeException, String] =
            if i >= s.length then Result.succeed(b.toString)
            else
                val ch = s.charAt(i)
                if ch == '\\' && i + 1 < s.length then
                    val escapeIndex = i + 1
                    s.charAt(escapeIndex) match
                        case '0' =>
                            b.append('\u0000')
                            loop(i + 2)
                        case 'a' =>
                            b.append('\u0007')
                            loop(i + 2)
                        case 'b' =>
                            b.append('\b')
                            loop(i + 2)
                        case 't' | '\t' =>
                            b.append('\t')
                            loop(i + 2)
                        case 'n' =>
                            b.append('\n')
                            loop(i + 2)
                        case 'v' =>
                            b.append('\u000b')
                            loop(i + 2)
                        case 'f' =>
                            b.append('\f')
                            loop(i + 2)
                        case 'r' =>
                            b.append('\r')
                            loop(i + 2)
                        case 'e' =>
                            b.append('\u001b')
                            loop(i + 2)
                        case ' ' =>
                            b.append(' ')
                            loop(i + 2)
                        case '"' =>
                            b.append('"')
                            loop(i + 2)
                        case '/' =>
                            b.append('/')
                            loop(i + 2)
                        case '\\' =>
                            b.append('\\')
                            loop(i + 2)
                        case 'N' =>
                            b.append('\u0085')
                            loop(i + 2)
                        case '_' =>
                            b.append('\u00a0')
                            loop(i + 2)
                        case 'L' =>
                            b.append('\u2028')
                            loop(i + 2)
                        case 'P' =>
                            b.append('\u2029')
                            loop(i + 2)
                        case 'x' =>
                            readHexEscape(s, escapeIndex, 2, valueMark) match
                                case Result.Success(value) =>
                                    b.append(value.toChar)
                                    loop(i + 4)
                                case Result.Failure(e) => Result.fail(e)
                                case Result.Panic(e)   => Result.panic(e)
                        case 'u' =>
                            readHexEscape(s, escapeIndex, 4, valueMark) match
                                case Result.Success(value) =>
                                    b.append(value.toChar)
                                    loop(i + 6)
                                case Result.Failure(e) => Result.fail(e)
                                case Result.Panic(e)   => Result.panic(e)
                        case 'U' =>
                            readHexEscape(s, escapeIndex, 8, valueMark) match
                                case Result.Success(value) =>
                                    if Character.isValidCodePoint(value) then
                                        b.appendAll(Character.toChars(value))
                                        loop(i + 10)
                                    else
                                        Result.fail(errorAt(
                                            valueMark.copy(
                                                index = valueMark.index + escapeIndex - 1,
                                                column = valueMark.column + escapeIndex - 1
                                            ),
                                            s"Invalid escape sequence \\${s.charAt(escapeIndex)}${s.substring(escapeIndex + 1, math.min(s.length, escapeIndex + 9))}"
                                        ))
                                case Result.Failure(e) => Result.fail(e)
                                case Result.Panic(e)   => Result.panic(e)
                        case other =>
                            invalidEscape(escapeIndex, s"Invalid escape sequence \\$other")
                    end match
                else if ch == '\\' then
                    Result.fail(errorAt(
                        valueMark.copy(index = valueMark.index + i, column = valueMark.column + i),
                        "Invalid escape sequence \\"
                    ))
                else
                    b.append(ch)
                    loop(i + 1)
                end if
        end loop

        loop(0)
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
