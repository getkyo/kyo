package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.Codec.Writer

final class YamlWriter private extends Writer:

    sealed private trait Frame
    final private class MappingFrame(val indent: Int, var first: Boolean, var inlineFirst: Boolean) extends Frame
    final private class SequenceFrame(val indent: Int)                                              extends Frame
    final private class EmptyFrame                                                                  extends Frame

    private enum Scalar derives CanEqual:
        case Plain(value: String)
        case Quoted(value: String)
        case Literal(value: String)
    end Scalar

    private val out                       = new StringBuilder
    private var stack: List[Frame]        = Nil
    private var pendingField: Boolean     = false
    private var released: Boolean         = false
    private var lastWriteWasLine: Boolean = false

    def objectStart(name: String, size: Int): Unit = startMapping(size)
    def objectEnd(): Unit                          = endCollection()
    def arrayStart(size: Int): Unit                = startSequence(size)
    def arrayEnd(): Unit                           = endCollection()

    def fieldBytes(nameBytes: Array[Byte], fieldId: Int): Unit =
        field(new String(nameBytes, StandardCharsets.UTF_8), fieldId)

    override def field(name: String, fieldId: Int): Unit =
        stack match
            case (frame: MappingFrame) :: _ =>
                if frame.first then
                    if frame.inlineFirst then frame.inlineFirst = false
                    else writeIndent(frame.indent)
                    frame.first = false
                else writeIndent(frame.indent)
                end if
                writeKey(name)
                out.append(':')
                pendingField = true
                lastWriteWasLine = false
            case _ =>
                ()
    end field

    def string(value: String): Unit               = writeScalar(stringScalar(value))
    def int(value: Int): Unit                     = writeScalar(Scalar.Plain(value.toString))
    def long(value: Long): Unit                   = writeScalar(Scalar.Plain(value.toString))
    def float(value: Float): Unit                 = writeScalar(floatScalar(value))
    def double(value: Double): Unit               = writeScalar(doubleScalar(value))
    def boolean(value: Boolean): Unit             = writeScalar(Scalar.Plain(if value then "true" else "false"))
    def short(value: Short): Unit                 = writeScalar(Scalar.Plain(value.toString))
    def byte(value: Byte): Unit                   = writeScalar(Scalar.Plain(value.toString))
    def char(value: Char): Unit                   = writeScalar(Scalar.Quoted(value.toString))
    def nil(): Unit                               = writeScalar(Scalar.Plain("null"))
    def mapStart(size: Int): Unit                 = startMapping(size)
    def mapEnd(): Unit                            = endCollection()
    def bytes(value: Span[Byte]): Unit            = writeScalar(Scalar.Quoted(java.util.Base64.getEncoder.encodeToString(value.toArray)))
    def bigInt(value: BigInt): Unit               = writeScalar(Scalar.Quoted(value.toString))
    def bigDecimal(value: BigDecimal): Unit       = writeScalar(Scalar.Quoted(value.toString))
    def instant(value: java.time.Instant): Unit   = writeScalar(Scalar.Quoted(value.toString))
    def duration(value: java.time.Duration): Unit = writeScalar(Scalar.Quoted(value.toString))

    def result(): Span[Byte] =
        Span.from(resultString.getBytes(StandardCharsets.UTF_8))

    override def resultString: String =
        if !released && !lastWriteWasLine then out.append('\n')
        val result = out.toString
        out.clear()
        stack = Nil
        pendingField = false
        lastWriteWasLine = false
        released = true
        result
    end resultString

    private def startMapping(size: Int): Unit =
        if size == 0 then
            writeEmpty("{}")
            stack = EmptyFrame() :: stack
        else
            val indent = startContainer(isMapping = true)
            stack = MappingFrame(indent, first = true, inlineFirst = startsInlineMapping) :: stack
        end if
    end startMapping

    private def startSequence(size: Int): Unit =
        if size == 0 then
            writeEmpty("[]")
            stack = EmptyFrame() :: stack
        else
            val indent = startContainer(isMapping = false)
            stack = SequenceFrame(indent) :: stack
        end if
    end startSequence

    private def startContainer(isMapping: Boolean): Int =
        released = false
        stack match
            case (_: MappingFrame) :: _ if pendingField =>
                out.append('\n')
                pendingField = false
                lastWriteWasLine = true
                currentIndent + 2
            case (frame: SequenceFrame) :: _ =>
                writeIndent(frame.indent)
                out.append("-")
                if isMapping then out.append(' ')
                else
                    out.append('\n')
                    lastWriteWasLine = true
                end if
                frame.indent + 2
            case _ =>
                0
        end match
    end startContainer

    private def startsInlineMapping: Boolean =
        out.nonEmpty && out.charAt(out.length - 1) == ' '

    private def endCollection(): Unit =
        stack match
            case _ :: rest => stack = rest
            case Nil       => ()
        end match
    end endCollection

    private def writeEmpty(value: String): Unit =
        writeScalar(Scalar.Plain(value))

    private def writeScalar(scalar: Scalar): Unit =
        released = false
        stack match
            case (_: MappingFrame) :: _ if pendingField =>
                out.append(' ')
                if !appendScalar(scalar, currentIndent + 2) then out.append('\n')
                pendingField = false
                lastWriteWasLine = true
            case (frame: SequenceFrame) :: _ =>
                writeIndent(frame.indent)
                out.append("- ")
                if !appendScalar(scalar, frame.indent + 2) then out.append('\n')
                lastWriteWasLine = true
            case _ =>
                if !appendScalar(scalar, 2) then out.append('\n')
                lastWriteWasLine = true
        end match
    end writeScalar

    private def appendScalar(scalar: Scalar, contentIndent: Int): Boolean =
        scalar match
            case Scalar.Plain(value) =>
                out.append(value)
                false
            case Scalar.Quoted(value) =>
                appendQuoted(value)
                false
            case Scalar.Literal(value) =>
                appendLiteral(value, contentIndent)
                true
    end appendScalar

    private def stringScalar(value: String): Scalar =
        if value.indexOf('\n') >= 0 && value.indexOf('\r') < 0 then Scalar.Literal(value)
        else if isPlainSafe(value) then Scalar.Plain(value)
        else Scalar.Quoted(value)
    end stringScalar

    private def floatScalar(value: Float): Scalar =
        if value.isNaN then Scalar.Plain(".nan")
        else if value == Float.PositiveInfinity then Scalar.Plain(".inf")
        else if value == Float.NegativeInfinity then Scalar.Plain("-.inf")
        else Scalar.Plain(value.toString)
    end floatScalar

    private def doubleScalar(value: Double): Scalar =
        if value.isNaN then Scalar.Plain(".nan")
        else if value == Double.PositiveInfinity then Scalar.Plain(".inf")
        else if value == Double.NegativeInfinity then Scalar.Plain("-.inf")
        else Scalar.Plain(value.toString)
    end doubleScalar

    private def currentIndent: Int =
        stack match
            case (frame: MappingFrame) :: _  => frame.indent
            case (frame: SequenceFrame) :: _ => frame.indent
            case _                           => 0
    end currentIndent

    private def writeIndent(indent: Int): Unit =
        var i = 0
        while i < indent do
            out.append(' ')
            i += 1
        end while
        lastWriteWasLine = false
    end writeIndent

    private def writeKey(value: String): Unit =
        if isPlainSafeKey(value) then out.append(value)
        else appendQuoted(value)
    end writeKey

    private def appendQuoted(value: String): Unit =
        out.append('"')
        value.foreach {
            case '"'  => out.append("\\\"")
            case '\\' => out.append("\\\\")
            case '\n' => out.append("\\n")
            case '\r' => out.append("\\r")
            case '\t' => out.append("\\t")
            case c if c < ' ' =>
                val hex = Integer.toHexString(c.toInt)
                out.append("\\u")
                var i = hex.length
                while i < 4 do
                    out.append('0')
                    i += 1
                out.append(hex)
            case c => out.append(c)
        }
        out.append('"')
    end appendQuoted

    private def appendLiteral(value: String, contentIndent: Int): Unit =
        out.append(literalHeader(value))
        out.append('\n')
        val end =
            if value.endsWith("\n") then value.length - 1
            else value.length
        var start = 0
        while start <= end && start < value.length do
            val next = value.indexOf('\n', start) match
                case -1 => end
                case n  => math.min(n, end)
            if next > start then writeIndent(contentIndent)
            var i = start
            while i < next do
                out.append(value.charAt(i))
                i += 1
            out.append('\n')
            start = next + 1
        end while
        lastWriteWasLine = true
    end appendLiteral

    private def literalHeader(value: String): String =
        if !value.endsWith("\n") then "|-"
        else
            var i = value.length - 1
            while i >= 0 && value.charAt(i) == '\n' do i -= 1
            if value.length - 1 - i > 1 then "|+"
            else "|"
    end literalHeader

    private def isPlainSafeKey(value: String): Boolean =
        isPlainSafe(value) && value.indexOf(':') < 0

    private def isPlainSafe(value: String): Boolean =
        if value.isEmpty || value.trim != value then false
        else if resolvesAsCoreScalar(value) then false
        else
            val first = value.charAt(0)
            if first == '-' || first == '?' || first == ':' || first == ',' || first == '[' || first == ']' ||
                first == '{' || first == '}' || first == '#' || first == '&' || first == '*' || first == '!' ||
                first == '|' || first == '>' || first == '\'' || first == '"' || first == '@' || first == '`' ||
                first == '%'
            then false
            else if value == "---" || value == "..." then false
            else
                var i    = 0
                var safe = true
                while safe && i < value.length do
                    value.charAt(i) match
                        case '\n' | '\r' | '\t' | '[' | ']' | '{' | '}' | ',' | '#' | ':' => safe = false
                        case c if c < ' '                                                 => safe = false
                        case _                                                            => ()
                    end match
                    i += 1
                end while
                safe
            end if
        end if
    end isPlainSafe

    private def resolvesAsCoreScalar(value: String): Boolean =
        YamlScalars.resolvesAsCore(value)
end YamlWriter

object YamlWriter:
    def apply(): YamlWriter = new YamlWriter()
end YamlWriter
