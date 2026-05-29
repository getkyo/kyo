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
            if startsWith("---") then
                advance(3)
                skipToNextLine()
            run(c1, visitor.documentStart(_, mark())) { c2 =>
                parseNode(c2, 0, visitor).flatMap { c3 =>
                    skipIgnorable()
                    run(c3, visitor.documentEnd(_, mark())) { c4 =>
                        skipIgnorable()
                        if startsWith("---") then
                            Result.fail(error("Expected a single YAML document for this parser entry point"))
                        else visitor.streamEnd(c4, mark())
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
        else if currentLineText().dropWhile(_ == ' ').startsWith("[") || currentLineText().dropWhile(_ == ' ').startsWith("{") then
            parseScalarValue(context, visitor)
        else if startsWithAtIndent("- ") then parseBlockSequence(context, currentIndent(), visitor)
        else if isBlockMappingLine(currentLineText()) then parseBlockMapping(context, currentIndent(), visitor)
        else parseScalarValue(context, visitor)
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
                if pos >= input.length || currentIndent() < indent || !isBlockMappingLine(currentLineText()) then
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
                if pos >= input.length || currentIndent() < indent || !startsWithAtIndent("- ") then
                    visitor.nodeEnd(context, mark())
                else
                    consumeIndent(indent)
                    advance(1)
                    if peekChar(' ') then advance(1)
                    val valueMark = mark()
                    val rest      = stripComment(readRestOfLine()).trim
                    val parsed =
                        if rest.isEmpty then parseNode(context, indent + 2, visitor)
                        else if isInlineMappingText(rest) then parseCompactSequenceMapping(rest, context, indent, visitor)
                        else parseInlineScalar(rest, context, visitor, valueMark)
                    parsed.flatMap(loop)
                end if
            end loop
            loop(c0)
        }
    end parseBlockSequence

    private def parseScalarValue[Ctx, Err, A](context: Ctx, visitor: Visitor[Ctx, Err, A]): Result[Err | DecodeException, Ctx] =
        val valueMark = mark()
        parseInlineScalar(stripComment(readRestOfLine()).trim, context, visitor, valueMark)

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
                valueText.substring(1, valueText.length - 1).replace("''", "'"),
                ScalarMeta(anchor, tag, ScalarStyle.SingleQuoted, valueMark)
            )
        else if valueText.startsWith("\"") && valueText.endsWith("\"") && valueText.length >= 2 then
            visitor.scalar(
                context,
                unescapeDoubleQuoted(valueText.substring(1, valueText.length - 1)),
                ScalarMeta(anchor, tag, ScalarStyle.DoubleQuoted, valueMark)
            )
        else
            visitor.scalar(context, valueText, ScalarMeta(anchor, tag, ScalarStyle.Plain, valueMark))
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
        visitor.scalar(context, key.trim, ScalarMeta(Absent, Absent, ScalarStyle.Plain, keyMark)).flatMap { c1 =>
            val trimmed        = stripComment(afterColon).trim
            val firstValueChar = afterColon.indexWhere(ch => !ch.isWhitespace)
            val valueOffset    = if firstValueChar < 0 then 0 else firstValueChar
            val valueMark      = valueMarkBase.copy(index = valueMarkBase.index + valueOffset, column = valueMarkBase.column + valueOffset)
            val (anchor, tag, valueText) = readProperties(trimmed)
            if valueText == "|" || valueText == ">" then
                parseBlockScalar(c1, indent + 2, if valueText == "|" then ScalarStyle.Literal else ScalarStyle.Folded, anchor, tag, visitor)
            else if valueText.isEmpty && (anchor.nonEmpty || tag.nonEmpty) then
                withPending(anchor, tag) {
                    skipBlankAndCommentLines()
                    parseNode(c1, indent + 2, visitor)
                }
            else if valueText.isEmpty then
                skipBlankAndCommentLines()
                parseNode(c1, indent + 2, visitor)
            else
                parseInlineScalar(trimmed, c1, visitor, valueMark)
            end if
        }
    end parseBlockMappingEntry

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
                    if pos < input.length && currentIndent() >= fieldIndent && isBlockMappingLine(currentLineText()) then
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
                        parseInlineScalar(entry.trim, context, visitor).flatMap(loop(tail, _))
            loop(entries.filter(_.nonEmpty), c0)
        }
    end parseFlowSequence

    private def parseInlineMappingEntry[Ctx, Err, A](
        text: String,
        context: Ctx,
        visitor: Visitor[Ctx, Err, A]
    ): Result[Err | DecodeException, Ctx] =
        val idx = findTopLevel(text, ':')
        if idx < 0 then Result.fail(error(s"Expected ':' in mapping entry '$text'"))
        else
            val key   = text.substring(0, idx).trim
            val value = text.substring(idx + 1).trim
            visitor.scalar(context, unquoteKey(key), ScalarMeta(Absent, Absent, ScalarStyle.Plain, mark())).flatMap { c1 =>
                if value.isEmpty then visitor.scalar(c1, "", ScalarMeta(Absent, Absent, ScalarStyle.Plain, mark()))
                else parseInlineScalar(value, c1, visitor)
            }
        end if
    end parseInlineMappingEntry

    private def parseBlockScalar[Ctx, Err, A](
        context: Ctx,
        indent: Int,
        style: ScalarStyle,
        anchor: Maybe[String],
        tag: Maybe[String],
        visitor: Visitor[Ctx, Err, A]
    ): Result[Err | DecodeException, Ctx] =
        val lines = scala.collection.mutable.ListBuffer.empty[String]
        var done  = false
        while !done && pos < input.length do
            val n    = currentIndent()
            val text = currentLineText()
            if text.trim.isEmpty then
                val _ = readRestOfLine()
                lines += ""
            else if n >= indent then
                consumeIndent(indent)
                lines += readRestOfLine()
            else done = true
            end if
        end while
        val value =
            if style == ScalarStyle.Literal then lines.mkString("\n") + "\n"
            else lines.mkString(" ") + "\n"
        visitor.scalar(context, value, ScalarMeta(anchor, tag, style, mark()))
    end parseBlockScalar

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
        val start   = math.max(0, pos - 30)
        val end     = math.min(input.length, pos + 30)
        val snippet = input.substring(start, end)
        val caret   = " " * (pos - start) + "^"
        ParseException(Yaml(), snippet + "\n" + caret, s"$message at line $line, column $column", Nil, pos)
    end error

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
        val colon    = stripped.indexOf(':')
        colon >= 0 && (colon == stripped.length - 1 || stripped.charAt(colon + 1).isWhitespace)
    end isBlockMappingLine

    private def startsWithAtIndent(s: String): Boolean =
        val n = currentIndent()
        input.startsWith(s, pos + n)
    end startsWithAtIndent

    private def readUntilMappingColon(): String =
        val start = pos
        while pos < input.length && input.charAt(pos) != ':' do advance(1)
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
        findTopLevel(s, ':') >= 0

    private def unquoteKey(s: String): String =
        if s.startsWith("'") && s.endsWith("'") && s.length >= 2 then s.substring(1, s.length - 1).replace("''", "'")
        else if s.startsWith("\"") && s.endsWith("\"") && s.length >= 2 then unescapeDoubleQuoted(s.substring(1, s.length - 1))
        else s
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
            if pos < input.length && currentLineText().trim.startsWith("%") then
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

    private def unescapeDoubleQuoted(s: String): String =
        val b = new StringBuilder
        var i = 0
        while i < s.length do
            val ch = s.charAt(i)
            if ch == '\\' && i + 1 < s.length then
                i += 1
                s.charAt(i) match
                    case 'n'   => b.append('\n')
                    case 'r'   => b.append('\r')
                    case 't'   => b.append('\t')
                    case '"'   => b.append('"')
                    case '\\'  => b.append('\\')
                    case other => b.append(other)
                end match
            else b.append(ch)
            end if
            i += 1
        end while
        b.toString
    end unescapeDoubleQuoted

end YamlParser

object YamlParser:
    def apply(input: String)(using Frame): YamlParser = new YamlParser(input)

    def toJson(input: Span[Byte])(using Frame): Span[Byte] =
        val s       = new String(input.toArrayUnsafe, StandardCharsets.UTF_8)
        val visitor = JsonBuildingVisitor()
        apply(s).visit(())(visitor) match
            case Result.Success(json: String)       => Span.from(json.getBytes(StandardCharsets.UTF_8))
            case Result.Failure(e: DecodeException) => throw e
            case Result.Panic(e)                    => throw e
        end match
    end toJson

    final private class JsonBuildingVisitor(using Frame) extends Yaml.Visitor[Unit, DecodeException, String]:
        final private class JsonFrame(val isMapping: Boolean, var first: Boolean, var expectingKey: Boolean)
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
            stack = JsonFrame(true, true, true) :: stack
            Result.unit
        end mappingStart

        def sequenceStart(context: Unit, meta: Yaml.Meta): Result[DecodeException, Unit] =
            valuePrefix()
            startCapture(meta.anchor)
            out.append('[')
            stack = JsonFrame(false, true, false) :: stack
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
                appendResolvedScalar(value, meta.style)
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
            }

        private def valuePrefix(): Unit =
            if !inMapping then entryPrefix()

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

        private def appendResolvedScalar(value: String, style: Yaml.ScalarStyle): Unit =
            if style != Yaml.ScalarStyle.Plain then appendQuoted(value)
            else
                value match
                    case "" | "~" | "null" | "Null" | "NULL" => out.append("null")
                    case "true" | "True" | "TRUE"            => out.append("true")
                    case "false" | "False" | "FALSE"         => out.append("false")
                    case s if isJsonNumber(s)                => out.append(s)
                    case other                               => appendQuoted(other)
                end match
            end if
        end appendResolvedScalar

        private def isJsonNumber(s: String): Boolean =
            if s.isEmpty then false
            else
                try
                    val _ = BigDecimal(s)
                    true
                catch
                    case _: NumberFormatException => false
        end isJsonNumber

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
