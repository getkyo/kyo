package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.Codec.Writer

final class YamlWriter private (private var config: Yaml.WriterConfig) extends Writer:
    import Yaml.WriterConfig.*

    sealed private trait Frame
    final private class MappingFrame(val indent: Int, var first: Boolean, var inlineFirst: Boolean, val flow: Boolean) extends Frame
    final private class SequenceFrame(val indent: Int, val flow: Boolean, var first: Boolean)                          extends Frame
    final private class EmptyFrame                                                                                     extends Frame

    private enum Scalar derives CanEqual:
        case Plain(value: String)
        case Quoted(value: String)
        case Literal(value: String)
        case Tagged(tag: String, scalar: Scalar)
        case FloatValue(value: Float)
        case DoubleValue(value: Double)
    end Scalar

    private val out                       = new StringBuilder
    private var numericBuffer             = new Array[Byte](32)
    private var stack: List[Frame]        = Nil
    private var pendingField: Boolean     = false
    private var released: Boolean         = false
    private var lastWriteWasLine: Boolean = false
    private var flowDepth: Int            = 0

    def objectStart(name: String, size: Int): Unit = startMapping(size)
    def objectEnd(): Unit                          = endCollection()
    def arrayStart(size: Int): Unit                = startSequence(size)
    def arrayEnd(): Unit                           = endCollection()

    def fieldBytes(nameBytes: Array[Byte], fieldId: Int): Unit =
        field(new String(nameBytes, StandardCharsets.UTF_8), fieldId)

    override def field(name: String, fieldId: Int): Unit =
        stack match
            case (frame: MappingFrame) :: _ if frame.flow =>
                if frame.first then frame.first = false
                else appendFlowSeparator()
                writeFlowKey(name)
                out.append(':')
                if config.collectionStyle == CollectionStyle.Flow then out.append(' ')
                pendingField = true
                lastWriteWasLine = false
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
        val bytes = finishString().getBytes(StandardCharsets.UTF_8)
        release()
        Span.fromUnsafe(bytes)
    end result

    override def resultString: String =
        val result = finishString()
        release()
        result
    end resultString

    private def finishString(): String =
        if !released && shouldAppendTrailingNewline then out.append('\n')
        withDocumentMarkers(out.toString)
    end finishString

    private def release(): Unit =
        val cacheable = out.capacity <= YamlWriter.MaxCachedOutputCapacity
        out.clear()
        stack = Nil
        pendingField = false
        lastWriteWasLine = false
        flowDepth = 0
        released = true
        if cacheable then YamlWriter.cache.set(this)
        else YamlWriter.cache.set(null)
    end release

    private def startMapping(size: Int): Unit =
        if size == 0 then
            writeEmpty("{}")
            stack = EmptyFrame() :: stack
        else
            val flow   = flowCollections
            val indent = startContainer(isMapping = true, flow)
            if flow then out.append('{')
            if flow then flowDepth += 1
            stack = MappingFrame(indent, first = true, inlineFirst = !flow && startsInlineMapping, flow) :: stack
        end if
    end startMapping

    private def startSequence(size: Int): Unit =
        if size == 0 then
            writeEmpty("[]")
            stack = EmptyFrame() :: stack
        else
            val flow   = flowCollections
            val indent = startContainer(isMapping = false, flow)
            if flow then out.append('[')
            if flow then flowDepth += 1
            stack = SequenceFrame(indent, flow, first = true) :: stack
        end if
    end startSequence

    private def startContainer(isMapping: Boolean, childFlow: Boolean): Int =
        released = false
        stack match
            case (_: MappingFrame) :: _ if pendingField && currentMappingFlow =>
                pendingField = false
                currentIndent
            case (_: MappingFrame) :: _ if pendingField =>
                out.append('\n')
                pendingField = false
                lastWriteWasLine = true
                currentIndent + indentSize
            case (frame: SequenceFrame) :: _ if frame.flow =>
                flowSequenceElement(frame)
                frame.indent
            case (frame: SequenceFrame) :: _ =>
                writeIndent(frame.indent)
                out.append("-")
                if isMapping && config.sequenceMappingStyle == SequenceMappingStyle.Compact && !childFlow then out.append(' ')
                else
                    out.append('\n')
                    lastWriteWasLine = true
                end if
                frame.indent + indentSize
            case _ =>
                0
        end match
    end startContainer

    private def startsInlineMapping: Boolean =
        out.nonEmpty && out.charAt(out.length - 1) == ' '

    private def endCollection(): Unit =
        stack match
            case (frame: MappingFrame) :: rest =>
                stack = rest
                if frame.flow then
                    flowDepth -= 1
                    out.append('}')
            case (frame: SequenceFrame) :: rest =>
                stack = rest
                if frame.flow then
                    flowDepth -= 1
                    out.append(']')
            case _ :: rest => stack = rest
            case Nil       => ()
        end match
    end endCollection

    private def writeEmpty(value: String): Unit =
        writeScalar(Scalar.Plain(value))

    private def writeScalar(scalar: Scalar): Unit =
        released = false
        stack match
            case (_: MappingFrame) :: _ if pendingField && currentMappingFlow =>
                val _ = appendScalar(scalar, currentIndent + indentSize)
                pendingField = false
                lastWriteWasLine = false
            case (_: MappingFrame) :: _ if pendingField =>
                out.append(' ')
                if !appendScalar(scalar, currentIndent + indentSize) then out.append('\n')
                pendingField = false
                lastWriteWasLine = true
            case (frame: SequenceFrame) :: _ if frame.flow =>
                flowSequenceElement(frame)
                val _ = appendScalar(scalar, frame.indent + indentSize)
                lastWriteWasLine = false
            case (frame: SequenceFrame) :: _ =>
                writeIndent(frame.indent)
                out.append("- ")
                if !appendScalar(scalar, frame.indent + indentSize) then out.append('\n')
                lastWriteWasLine = true
            case _ =>
                if !appendScalar(scalar, indentSize) then out.append('\n')
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
            case Scalar.Tagged(tag, scalar) =>
                out.append(tag)
                out.append(' ')
                appendScalar(scalar, contentIndent)
            case Scalar.FloatValue(value) =>
                appendFloat(value)
                false
            case Scalar.DoubleValue(value) =>
                appendDouble(value)
                false
    end appendScalar

    private def stringScalar(value: String): Scalar =
        if value.indexOf('\n') >= 0 && value.indexOf('\r') < 0 && config.multilineStyle != MultilineStyle.DoubleQuoted && !flowContext then
            Scalar.Literal(value)
        else if config.scalarQuoting != ScalarQuoting.QuoteAllStrings && isPlainSafe(value) && !jsonCompatibleFlow then Scalar.Plain(value)
        else Scalar.Quoted(value)
    end stringScalar

    private def floatScalar(value: Float): Scalar =
        if value.isNaN then specialFloat(".nan")
        else if value == Float.PositiveInfinity then specialFloat(".inf")
        else if value == Float.NegativeInfinity then specialFloat("-.inf")
        else Scalar.FloatValue(value)
    end floatScalar

    private def doubleScalar(value: Double): Scalar =
        if value.isNaN then specialFloat(".nan")
        else if value == Double.PositiveInfinity then specialFloat(".inf")
        else if value == Double.NegativeInfinity then specialFloat("-.inf")
        else Scalar.DoubleValue(value)
    end doubleScalar

    private def appendFloat(value: Float): Unit =
        val written = Ryu.RyuFloat.write(value, numericBuffer, 0, numericBuffer.length)
        val end =
            if written < 0 then
                numericBuffer = java.util.Arrays.copyOf(numericBuffer, -written)
                Ryu.RyuFloat.write(value, numericBuffer, 0, numericBuffer.length)
            else written
        appendAsciiBytes(numericBuffer, end)
    end appendFloat

    private def appendDouble(value: Double): Unit =
        val written = Ryu.RyuDouble.write(value, numericBuffer, 0, numericBuffer.length)
        val end =
            if written < 0 then
                numericBuffer = java.util.Arrays.copyOf(numericBuffer, -written)
                Ryu.RyuDouble.write(value, numericBuffer, 0, numericBuffer.length)
            else written
        appendAsciiBytes(numericBuffer, end)
    end appendDouble

    private def appendAsciiBytes(bytes: Array[Byte], end: Int): Unit =
        var i = 0
        while i < end do
            out.append(bytes(i).toChar)
            i += 1
        end while
    end appendAsciiBytes

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

    private def writeFlowKey(value: String): Unit =
        if jsonCompatibleFlow then appendDoubleQuoted(value)
        else writeKey(value)
    end writeFlowKey

    private def appendQuoted(value: String): Unit =
        if config.quoteStyle == QuoteStyle.Single && !value.exists(c => c < ' ' || c == '\n' || c == '\r' || c == '\t') then
            appendSingleQuoted(value)
        else appendDoubleQuoted(value)
    end appendQuoted

    private def appendDoubleQuoted(value: String): Unit =
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
    end appendDoubleQuoted

    private def appendSingleQuoted(value: String): Unit =
        out.append('\'')
        value.foreach {
            case '\'' => out.append("''")
            case c    => out.append(c)
        }
        out.append('\'')
    end appendSingleQuoted

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
        val marker =
            config.multilineStyle match
                case MultilineStyle.Folded => ">"
                case _                     => "|"
        marker + (
            config.chomping match
                case Chomping.Strip => "-"
                case Chomping.Keep  => "+"
                case Chomping.Clip  => ""
                case Chomping.Preserve =>
                    if !value.endsWith("\n") then "-"
                    else
                        var i = value.length - 1
                        while i >= 0 && value.charAt(i) == '\n' do i -= 1
                        if value.length - 1 - i > 1 then "+"
                        else ""
        )
    end literalHeader

    private def isPlainSafeKey(value: String): Boolean =
        config.scalarQuoting != ScalarQuoting.QuoteAllStrings &&
            isPlainSyntaxSafe(value) && value.indexOf(':') < 0 && !quotesAmbiguousScalar(value)

    private def isPlainSafe(value: String): Boolean =
        isPlainSyntaxSafe(value) && !quotesAmbiguousScalar(value)

    private def quotesAmbiguousScalar(value: String): Boolean =
        config.scalarQuoting == ScalarQuoting.QuoteAmbiguous && resolvesAsCoreScalar(value)

    private def isPlainSyntaxSafe(value: String): Boolean =
        if value.isEmpty || value.trim != value then false
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
    end isPlainSyntaxSafe

    private def resolvesAsCoreScalar(value: String): Boolean =
        YamlScalars.resolvesAsCore(value, config.yamlVersion)

    private def flowCollections: Boolean =
        config.collectionStyle != CollectionStyle.Block

    private def jsonCompatibleFlow: Boolean =
        config.collectionStyle == CollectionStyle.JsonCompatibleFlow

    private def flowContext: Boolean =
        flowDepth > 0

    private def currentMappingFlow: Boolean =
        stack match
            case (frame: MappingFrame) :: _ => frame.flow
            case _                          => false

    private def appendFlowSeparator(): Unit =
        out.append(',')
        if config.collectionStyle == CollectionStyle.Flow then out.append(' ')

    private def flowSequenceElement(frame: SequenceFrame): Unit =
        if frame.first then frame.first = false
        else appendFlowSeparator()

    private def specialFloat(yaml: String): Scalar =
        config.specialFloatStyle match
            case SpecialFloatStyle.TaggedYamlCore => Scalar.Tagged("!!float", Scalar.Quoted(yaml))
            case SpecialFloatStyle.YamlCore       => Scalar.Plain(yaml)

    private def indentSize: Int =
        math.max(1, config.indent)

    private def shouldAppendTrailingNewline: Boolean =
        config.trailingNewline && !lastWriteWasLine

    private def withDocumentMarkers(body: String): String =
        config.documentMarkers match
            case DocumentMarkers.None =>
                body
            case DocumentMarkers.Start =>
                "---\n" + body
            case DocumentMarkers.StartAndEnd =>
                val middle =
                    if body.endsWith("\n") then body
                    else body + "\n"
                "---\n" + middle + "...\n"
end YamlWriter

object YamlWriter:
    final private val MaxCachedOutputCapacity = 262144
    private[internal] val cache               = new ThreadLocal[YamlWriter]

    def apply(): YamlWriter = apply(Yaml.WriterConfig.Default)

    def apply(config: Yaml.WriterConfig): YamlWriter =
        val cached = cache.get()
        if cached == null then new YamlWriter(config)
        else
            cache.set(null)
            cached.config = config
            cached
        end if
    end apply
end YamlWriter
