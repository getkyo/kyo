package kyo.internal.yaml

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.Ryu
import scala.annotation.tailrec

private[kyo] object YamlEvents:

    import Yaml.Events.CollectionKind

    final class Renderer private[YamlEvents] (config: Yaml.WriterConfig) extends Yaml.Events.Handler[Unit, DecodeException]:

        sealed private trait Frame
        final private class MappingFrame(
            val indent: Int,
            var key: String,
            var inlineFirst: Boolean,
            val flow: Boolean,
            var first: Boolean,
            val unknownSize: Boolean,
            var empty: Boolean
        ) extends Frame
        final private class SequenceFrame(
            val indent: Int,
            val flow: Boolean,
            var first: Boolean,
            val unknownSize: Boolean,
            var empty: Boolean
        ) extends Frame
        final private class EmptyFrame extends Frame

        private val out                = new StringBuilder
        private var stack: List[Frame] = Nil
        private var finalized: Boolean = false

        override def mappingStart(context: Unit, meta: Yaml.Meta, size: Maybe[Int]): Result[DecodeException, Unit] =
            startCollection(isMapping = true, size, properties(meta.anchor, meta.tag))
            Result.unit
        end mappingStart

        override def sequenceStart(context: Unit, meta: Yaml.Meta, size: Maybe[Int]): Result[DecodeException, Unit] =
            startCollection(isMapping = false, size, properties(meta.anchor, meta.tag))
            Result.unit
        end sequenceStart

        override def scalar(context: Unit, value: String, meta: Yaml.ScalarMeta): Result[DecodeException, Unit] =
            writeScalar(value, meta)
            Result.unit
        end scalar

        override def alias(context: Unit, name: Yaml.Anchor, mark: Yaml.Mark): Result[DecodeException, Unit] =
            writeRenderedScalar(s"*${name.value}")
            Result.unit
        end alias

        override def collectionEnd(context: Unit, kind: CollectionKind, mark: Yaml.Mark): Result[DecodeException, Unit] =
            stack match
                case (frame: MappingFrame) :: rest =>
                    stack = rest
                    if frame.flow then out.append('}')
                    else if frame.unknownSize && frame.empty && frame.key.isEmpty then appendEmptyCollection("{}")
                case (frame: SequenceFrame) :: rest =>
                    stack = rest
                    if frame.flow then out.append(']')
                    else if frame.unknownSize && frame.empty then appendEmptyCollection("[]")
                case _ :: rest => stack = rest
                case Nil       => ()
            end match
            Result.unit
        end collectionEnd

        def resultString: String =
            if !finalized then
                appendDocumentMarkers()
                finalized = true
            end if
            out.toString
        end resultString

        private def appendDocumentMarkers(): Unit =
            import Yaml.WriterConfig.DocumentMarkers.*
            config.documentMarkers match
                case None =>
                    ()
                case Start =>
                    out.insert(0, "---\n")
                case StartAndEnd =>
                    val bodyLength = out.length
                    out.insert(0, "---\n")
                    if bodyLength == 0 || out.charAt(out.length - 1) != '\n' then out.append('\n')
                    out.append("...\n")
            end match
        end appendDocumentMarkers

        private def startCollection(isMapping: Boolean, size: Maybe[Int], prefix: String): Unit =
            if size.contains(0) then
                writeRenderedScalar(prefixed(prefix, if isMapping then "{}" else "[]"))
                stack = EmptyFrame() :: stack
            else if flowCollections then startFlowCollection(isMapping, prefix, size.isEmpty)
            else startBlockCollection(isMapping, prefix, size.isEmpty)
        end startCollection

        private def startFlowCollection(isMapping: Boolean, prefix: String, unknownSize: Boolean): Unit =
            val open = if isMapping then '{' else '['
            stack match
                case (frame: MappingFrame) :: _ if frame.flow && frame.key.nonEmpty =>
                    frame.empty = false
                    appendFlowMappingPrefix(frame)
                    appendPropertyPrefix(prefix)
                    out.append(open)
                    stack = childFrame(isMapping, 0, unknownSize) :: stack
                    frame.key = ""
                case (frame: SequenceFrame) :: _ if frame.flow =>
                    frame.empty = false
                    appendFlowSequencePrefix(frame)
                    appendPropertyPrefix(prefix)
                    out.append(open)
                    stack = childFrame(isMapping, 0, unknownSize) :: stack
                case (frame: MappingFrame) :: _ if frame.key.nonEmpty =>
                    frame.empty = false
                    writeIndent(frame.indent)
                    out.append(frame.key)
                    out.append(": ")
                    appendPropertyPrefix(prefix)
                    out.append(open)
                    stack = childFrame(isMapping, frame.indent + indent, unknownSize) :: stack
                    frame.key = ""
                case (frame: SequenceFrame) :: _ =>
                    frame.empty = false
                    writeIndent(frame.indent)
                    out.append("- ")
                    appendPropertyPrefix(prefix)
                    out.append(open)
                    stack = childFrame(isMapping, frame.indent + indent, unknownSize) :: stack
                case _ =>
                    appendPropertyPrefix(prefix)
                    out.append(open)
                    stack = childFrame(isMapping, 0, unknownSize) :: stack
            end match
        end startFlowCollection

        private def startBlockCollection(isMapping: Boolean, prefix: String, unknownSize: Boolean): Unit =
            stack match
                case (frame: MappingFrame) :: _ if frame.key.nonEmpty =>
                    frame.empty = false
                    writeIndent(frame.indent)
                    out.append(frame.key)
                    out.append(':')
                    if prefix.nonEmpty then
                        out.append(' ')
                        out.append(prefix)
                    end if
                    out.append('\n')
                    stack = childFrame(isMapping, frame.indent + indent, unknownSize) :: stack
                    frame.key = ""
                case (frame: SequenceFrame) :: _ =>
                    frame.empty = false
                    writeIndent(frame.indent)
                    out.append('-')
                    if isMapping && config.sequenceMappingStyle == Yaml.WriterConfig.SequenceMappingStyle.Compact then
                        out.append(' ')
                        if prefix.nonEmpty then
                            out.append(prefix)
                            out.append(' ')
                        end if
                        stack =
                            MappingFrame(
                                frame.indent + indent,
                                "",
                                inlineFirst = true,
                                flow = false,
                                first = true,
                                unknownSize,
                                empty = true
                            ) :: stack
                    else
                        if prefix.nonEmpty then
                            out.append(' ')
                            out.append(prefix)
                        end if
                        out.append('\n')
                        stack = childFrame(isMapping, frame.indent + indent, unknownSize) :: stack
                    end if
                case _ =>
                    if prefix.nonEmpty then
                        out.append(prefix)
                        out.append('\n')
                    end if
                    stack = childFrame(isMapping, 0, unknownSize) :: stack
            end match
        end startBlockCollection

        private def childFrame(isMapping: Boolean, indent: Int, unknownSize: Boolean): Frame =
            if isMapping then MappingFrame(indent, "", inlineFirst = false, flow = flowCollections, first = true, unknownSize, empty = true)
            else SequenceFrame(indent, flow = flowCollections, first = true, unknownSize, empty = true)

        private def writeScalar(value: String, meta: Yaml.ScalarMeta): Unit =
            stack match
                case (frame: MappingFrame) :: _ if frame.flow && frame.key.isEmpty =>
                    frame.empty = false
                    frame.key = renderKey(value, meta, flow = true)
                case (frame: MappingFrame) :: _ if frame.flow =>
                    frame.empty = false
                    appendFlowMappingPrefix(frame)
                    appendScalar(value, meta, frame.indent + indent)
                    frame.key = ""
                case (frame: SequenceFrame) :: _ if frame.flow =>
                    frame.empty = false
                    appendFlowSequencePrefix(frame)
                    appendScalar(value, meta, frame.indent + indent)
                case (frame: MappingFrame) :: _ if frame.key.isEmpty =>
                    frame.empty = false
                    frame.key = renderKey(value, meta, flow = false)
                case (frame: MappingFrame) :: _ =>
                    frame.empty = false
                    if frame.inlineFirst then frame.inlineFirst = false
                    else writeIndent(frame.indent)
                    out.append(frame.key)
                    out.append(": ")
                    appendScalarLine(value, meta, frame.indent + indent)
                    frame.key = ""
                case (frame: SequenceFrame) :: _ =>
                    frame.empty = false
                    writeIndent(frame.indent)
                    out.append("- ")
                    appendScalarLine(value, meta, frame.indent + indent)
                case (_: EmptyFrame) :: _ =>
                    ()
                case Nil =>
                    appendRootScalar(value, meta, indent)
            end match
        end writeScalar

        private def writeRenderedScalar(value: String): Unit =
            stack match
                case (frame: MappingFrame) :: _ if frame.flow && frame.key.isEmpty =>
                    frame.empty = false
                    frame.key = value
                case (frame: MappingFrame) :: _ if frame.flow =>
                    frame.empty = false
                    appendFlowMappingPrefix(frame)
                    out.append(value)
                    frame.key = ""
                case (frame: SequenceFrame) :: _ if frame.flow =>
                    frame.empty = false
                    appendFlowSequencePrefix(frame)
                    out.append(value)
                case (frame: MappingFrame) :: _ if frame.key.isEmpty =>
                    frame.empty = false
                    frame.key = value
                case (frame: MappingFrame) :: _ =>
                    frame.empty = false
                    if frame.inlineFirst then frame.inlineFirst = false
                    else writeIndent(frame.indent)
                    out.append(frame.key)
                    out.append(": ")
                    appendRenderedScalar(value)
                    frame.key = ""
                case (frame: SequenceFrame) :: _ =>
                    frame.empty = false
                    writeIndent(frame.indent)
                    out.append("- ")
                    appendRenderedScalar(value)
                case (_: EmptyFrame) :: _ =>
                    ()
                case Nil =>
                    appendRootScalar(value)
            end match
        end writeRenderedScalar

        private def appendRenderedScalar(value: String): Unit =
            out.append(value)
            if !value.endsWith("\n") then out.append('\n')
        end appendRenderedScalar

        private def appendRootScalar(value: String): Unit =
            out.append(value)
            if config.trailingNewline && !value.endsWith("\n") then out.append('\n')
        end appendRootScalar

        private def appendEmptyCollection(value: String): Unit =
            if out.nonEmpty && out.charAt(out.length - 1) == '\n' then out.setLength(out.length - 1)
            if out.nonEmpty then
                out.charAt(out.length - 1) match
                    case ':' | '-' => out.append(' ')
                    case _         => ()
                end match
                appendRenderedScalar(value)
            else
                appendRootScalar(value)
            end if
        end appendEmptyCollection

        private def appendScalarLine(value: String, meta: Yaml.ScalarMeta, contentIndent: Int): Unit =
            appendScalar(value, meta, contentIndent)
            if out.isEmpty || out.charAt(out.length - 1) != '\n' then out.append('\n')
        end appendScalarLine

        private def appendRootScalar(value: String, meta: Yaml.ScalarMeta, contentIndent: Int): Unit =
            appendScalar(value, meta, contentIndent)
            if config.trailingNewline && (out.isEmpty || out.charAt(out.length - 1) != '\n') then out.append('\n')
        end appendRootScalar

        private def appendScalar(value: String, meta: Yaml.ScalarMeta, contentIndent: Int): Unit =
            appendPropertyPrefix(properties(meta.anchor, meta.tag))
            meta.style match
                case Yaml.ScalarStyle.Plain        => out.append(value)
                case Yaml.ScalarStyle.SingleQuoted => Scalar.appendSingleQuoted(out, value)
                case Yaml.ScalarStyle.DoubleQuoted => Scalar.appendDoubleQuoted(out, value)
                case Yaml.ScalarStyle.Literal      => Scalar.appendBlock(out, value, config, contentIndent, folded = false)
                case Yaml.ScalarStyle.Folded       => Scalar.appendBlock(out, value, config, contentIndent, folded = true)
            end match
        end appendScalar

        private def renderKey(value: String, meta: Yaml.ScalarMeta, flow: Boolean): String =
            val rendered =
                if flow && config.collectionStyle == Yaml.WriterConfig.CollectionStyle.JsonCompatibleFlow then Scalar.doubleQuoted(value)
                else if Scalar.plainKeySafe(value, config) then value
                else Scalar.doubleQuoted(value)
            val prefix = properties(meta.anchor, meta.tag)
            prefixed(prefix, rendered)
        end renderKey

        private def appendFlowMappingPrefix(frame: MappingFrame): Unit =
            if frame.first then frame.first = false
            else appendFlowSeparator()
            out.append(frame.key)
            out.append(':')
            if config.collectionStyle == Yaml.WriterConfig.CollectionStyle.Flow then out.append(' ')
        end appendFlowMappingPrefix

        private def appendFlowSequencePrefix(frame: SequenceFrame): Unit =
            if frame.first then frame.first = false
            else appendFlowSeparator()
        end appendFlowSequencePrefix

        private def appendFlowSeparator(): Unit =
            out.append(',')
            if config.collectionStyle == Yaml.WriterConfig.CollectionStyle.Flow then out.append(' ')
        end appendFlowSeparator

        private def appendPropertyPrefix(prefix: String): Unit =
            if prefix.nonEmpty then
                out.append(prefix)
                out.append(' ')
        end appendPropertyPrefix

        private def properties(anchor: Maybe[Yaml.Anchor], tag: Maybe[Yaml.YamlTag]): String =
            if anchor.isEmpty && tag.isEmpty then ""
            else
                val out = new StringBuilder
                tag.foreach { value =>
                    out.append(value.value)
                }
                anchor.foreach { value =>
                    if out.nonEmpty then out.append(' ')
                    out.append('&')
                    out.append(value.value)
                }
                out.toString
        end properties

        private def prefixed(prefix: String, rendered: String): String =
            if prefix.isEmpty then rendered
            else prefix + " " + rendered
        end prefixed

        private def indent: Int =
            math.max(1, config.indent)

        private def writeIndent(n: Int): Unit =
            Scalar.appendIndent(out, n)

        private def flowCollections: Boolean =
            config.collectionStyle != Yaml.WriterConfig.CollectionStyle.Block
    end Renderer

    object Renderer:
        def apply(config: Yaml.WriterConfig): Renderer =
            new Renderer(config)
    end Renderer

    abstract class EventCodecWriter(config: Yaml.WriterConfig) extends Codec.Writer:

        private var started: Boolean  = false
        private var finished: Boolean = false
        private var numericBuffer     = new Array[Byte](32)
        private var flowDepth: Int    = 0
        private val eventMark         = Yaml.Mark(0, 1, 1)

        private[YamlEvents] def emitStreamStart(mark: Yaml.Mark): Unit

        private[YamlEvents] def emitDocumentStart(mark: Yaml.Mark): Unit

        private[YamlEvents] def emitMappingStart(meta: Yaml.Meta, size: Maybe[Int]): Unit

        private[YamlEvents] def emitSequenceStart(meta: Yaml.Meta, size: Maybe[Int]): Unit

        private[YamlEvents] def emitScalar(value: String, meta: Yaml.ScalarMeta): Unit

        private[YamlEvents] def emitCollectionEnd(kind: CollectionKind, mark: Yaml.Mark): Unit

        private[YamlEvents] def emitDocumentEnd(mark: Yaml.Mark): Unit

        private[YamlEvents] def emitStreamEnd(mark: Yaml.Mark): Unit

        def objectStart(name: String, size: Int): Unit =
            ensureStarted()
            emitMappingStart(Yaml.Meta(Absent, Absent, mark), knownSize(size))
            startFlowContext()
        end objectStart

        def objectEnd(): Unit =
            emitCollectionEnd(CollectionKind.Mapping, mark)
            endFlowContext()

        def arrayStart(size: Int): Unit =
            ensureStarted()
            emitSequenceStart(Yaml.Meta(Absent, Absent, mark), knownSize(size))
            startFlowContext()
        end arrayStart

        def arrayEnd(): Unit =
            emitCollectionEnd(CollectionKind.Sequence, mark)
            endFlowContext()

        def fieldBytes(nameBytes: Array[Byte], fieldId: Int): Unit =
            field(new String(nameBytes, StandardCharsets.UTF_8), fieldId)

        override def field(name: String, fieldId: Int): Unit =
            ensureStarted()
            emitScalar(name, scalarMeta(Yaml.ScalarStyle.Plain))
        end field

        def string(value: String): Unit =
            ensureStarted()
            emitScalar(value, scalarMeta(Scalar.stringStyle(value, config, flowDepth > 0)))

        def int(value: Int): Unit =
            scalar(value.toString)

        def long(value: Long): Unit =
            scalar(value.toString)

        def float(value: Float): Unit =
            if value.isNaN then specialFloat(".nan")
            else if value == Float.PositiveInfinity then specialFloat(".inf")
            else if value == Float.NegativeInfinity then specialFloat("-.inf")
            else scalar(ryuFloat(value))

        def double(value: Double): Unit =
            if value.isNaN then specialFloat(".nan")
            else if value == Double.PositiveInfinity then specialFloat(".inf")
            else if value == Double.NegativeInfinity then specialFloat("-.inf")
            else scalar(ryuDouble(value))

        def boolean(value: Boolean): Unit =
            scalar(if value then "true" else "false")

        def short(value: Short): Unit =
            scalar(value.toString)

        def byte(value: Byte): Unit =
            scalar(value.toString)

        def char(value: Char): Unit =
            quoted(value.toString)

        def nil(): Unit =
            scalar("null")

        def mapStart(size: Int): Unit =
            objectStart("Map", size)

        def mapEnd(): Unit =
            objectEnd()

        def bytes(value: Span[Byte]): Unit =
            quoted(java.util.Base64.getEncoder.encodeToString(value.toArray))

        def bigInt(value: BigInt): Unit =
            quoted(value.toString)

        def bigDecimal(value: BigDecimal): Unit =
            quoted(value.toString)

        def instant(value: java.time.Instant): Unit =
            quoted(value.toString)

        def duration(value: java.time.Duration): Unit =
            quoted(value.toString)

        private def startFlowContext(): Unit =
            if config.collectionStyle != Yaml.WriterConfig.CollectionStyle.Block then flowDepth += 1
        end startFlowContext

        private def endFlowContext(): Unit =
            if config.collectionStyle != Yaml.WriterConfig.CollectionStyle.Block && flowDepth > 0 then flowDepth -= 1
        end endFlowContext

        private def scalar(value: String): Unit =
            ensureStarted()
            emitScalar(value, scalarMeta(Yaml.ScalarStyle.Plain))
        end scalar

        private def quoted(value: String): Unit =
            ensureStarted()
            emitScalar(value, scalarMeta(Yaml.ScalarStyle.DoubleQuoted))
        end quoted

        private def specialFloat(value: String): Unit =
            import Yaml.WriterConfig.SpecialFloatStyle
            ensureStarted()
            val meta =
                config.specialFloatStyle match
                    case SpecialFloatStyle.TaggedYamlCore => scalarMeta(Yaml.ScalarStyle.DoubleQuoted, Maybe(Yaml.YamlTag("!!float")))
                    case SpecialFloatStyle.YamlCore       => scalarMeta(Yaml.ScalarStyle.Plain)
            emitScalar(value, meta)
        end specialFloat

        private def ryuFloat(value: Float): String =
            val end = writeRyu(Ryu.RyuFloat.write(value, numericBuffer, 0, numericBuffer.length)) {
                Ryu.RyuFloat.write(value, numericBuffer, 0, numericBuffer.length)
            }
            new String(numericBuffer, 0, end, StandardCharsets.US_ASCII)
        end ryuFloat

        private def ryuDouble(value: Double): String =
            val end = writeRyu(Ryu.RyuDouble.write(value, numericBuffer, 0, numericBuffer.length)) {
                Ryu.RyuDouble.write(value, numericBuffer, 0, numericBuffer.length)
            }
            new String(numericBuffer, 0, end, StandardCharsets.US_ASCII)
        end ryuDouble

        private def writeRyu(written: Int)(retry: => Int): Int =
            if written < 0 then
                numericBuffer = java.util.Arrays.copyOf(numericBuffer, -written)
                retry
            else written
        end writeRyu

        private def ensureStarted(): Unit =
            if !started then
                started = true
                emitStreamStart(mark)
                emitDocumentStart(mark)
            end if
        end ensureStarted

        private[YamlEvents] def finishEvents(): Unit =
            if started && !finished then
                emitDocumentEnd(mark)
                emitStreamEnd(mark)
                finished = true
            end if
        end finishEvents

        private[YamlEvents] def resetEvents(): Unit =
            started = false
            finished = false
            flowDepth = 0
        end resetEvents

        private def scalarMeta(style: Yaml.ScalarStyle, tag: Maybe[Yaml.YamlTag] = Absent): Yaml.ScalarMeta =
            Yaml.ScalarMeta(Absent, tag, style, mark)

        private def knownSize(size: Int): Maybe[Int] =
            if size >= 0 then Maybe(size) else Absent

        private def mark: Yaml.Mark =
            eventMark
    end EventCodecWriter

    final class EventWriter[Ctx, Err] private[YamlEvents] (
        context: Ctx,
        handler: Yaml.Events.Handler[Ctx, Err],
        config: Yaml.WriterConfig
    ) extends EventCodecWriter(config):

        private var current: Result[Err, Ctx] = Result.succeed(context)

        def result(): Span[Byte] =
            val _ = resultContext
            Span.empty
        end result

        override def resultString: String =
            val _ = resultContext
            ""
        end resultString

        def resultContext: Result[Err, Ctx] =
            finishEvents()
            current
        end resultContext

        private[YamlEvents] def emitStreamStart(mark: Yaml.Mark): Unit =
            current = current.flatMap(handler.streamStart(_, mark))

        private[YamlEvents] def emitDocumentStart(mark: Yaml.Mark): Unit =
            current = current.flatMap(handler.documentStart(_, mark))

        private[YamlEvents] def emitMappingStart(meta: Yaml.Meta, size: Maybe[Int]): Unit =
            current = current.flatMap(handler.mappingStart(_, meta, size))

        private[YamlEvents] def emitSequenceStart(meta: Yaml.Meta, size: Maybe[Int]): Unit =
            current = current.flatMap(handler.sequenceStart(_, meta, size))

        private[YamlEvents] def emitScalar(value: String, meta: Yaml.ScalarMeta): Unit =
            current = current.flatMap(handler.scalar(_, value, meta))

        private[YamlEvents] def emitCollectionEnd(kind: CollectionKind, mark: Yaml.Mark): Unit =
            current = current.flatMap(handler.collectionEnd(_, kind, mark))

        private[YamlEvents] def emitDocumentEnd(mark: Yaml.Mark): Unit =
            current = current.flatMap(handler.documentEnd(_, mark))

        private[YamlEvents] def emitStreamEnd(mark: Yaml.Mark): Unit =
            current = current.flatMap(handler.streamEnd(_, mark))
    end EventWriter

    object EventWriter:
        def apply[Ctx, Err](
            context: Ctx,
            handler: Yaml.Events.Handler[Ctx, Err],
            config: Yaml.WriterConfig
        ): EventWriter[Ctx, Err] =
            new EventWriter(context, handler, config)
    end EventWriter

    final class Writer private[YamlEvents] (config: Yaml.WriterConfig) extends EventCodecWriter(config):

        private var renderer = Renderer(config)

        def result(): Span[Byte] =
            Span.fromUnsafe(resultString.getBytes(StandardCharsets.UTF_8))

        override def resultString: String =
            val result = finishString()
            release()
            result
        end resultString

        private def finishString(): String =
            finishEvents()
            renderer.resultString
        end finishString

        private def release(): Unit =
            renderer = Renderer(config)
            resetEvents()
        end release

        private[YamlEvents] def emitStreamStart(mark: Yaml.Mark): Unit =
            handle(renderer.streamStart((), mark))

        private[YamlEvents] def emitDocumentStart(mark: Yaml.Mark): Unit =
            handle(renderer.documentStart((), mark))

        private[YamlEvents] def emitMappingStart(meta: Yaml.Meta, size: Maybe[Int]): Unit =
            handle(renderer.mappingStart((), meta, size))

        private[YamlEvents] def emitSequenceStart(meta: Yaml.Meta, size: Maybe[Int]): Unit =
            handle(renderer.sequenceStart((), meta, size))

        private[YamlEvents] def emitScalar(value: String, meta: Yaml.ScalarMeta): Unit =
            handle(renderer.scalar((), value, meta))

        private[YamlEvents] def emitCollectionEnd(kind: CollectionKind, mark: Yaml.Mark): Unit =
            handle(renderer.collectionEnd((), kind, mark))

        private[YamlEvents] def emitDocumentEnd(mark: Yaml.Mark): Unit =
            handle(renderer.documentEnd((), mark))

        private[YamlEvents] def emitStreamEnd(mark: Yaml.Mark): Unit =
            handle(renderer.streamEnd((), mark))

        private def handle(result: Result[DecodeException, Unit]): Unit =
            result match
                case Result.Success(_) => ()
                case Result.Failure(e) => throw e
                case Result.Panic(e)   => throw e
        end handle
    end Writer

    object Writer:
        def apply(config: Yaml.WriterConfig): Writer =
            new Writer(config)
    end Writer

    private object Scalar:

        def stringStyle(value: String, config: Yaml.WriterConfig, flowContext: Boolean): Yaml.ScalarStyle =
            import Yaml.ScalarStyle.*
            import Yaml.WriterConfig.*
            if value.indexOf('\n') >= 0 && value.indexOf('\r') < 0 && config.multilineStyle != MultilineStyle.DoubleQuoted && !flowContext
            then
                config.multilineStyle match
                    case MultilineStyle.Folded => Folded
                    case _                     => Literal
            else if config.scalarQuoting != ScalarQuoting.QuoteAllStrings &&
                plainSyntaxSafe(value) &&
                !(config.scalarQuoting == ScalarQuoting.QuoteAmbiguous && YamlScalars.resolvesAsCore(value, config.yamlVersion))
            then Plain
            else if config.quoteStyle == QuoteStyle.Single &&
                config.collectionStyle != CollectionStyle.JsonCompatibleFlow &&
                canSingleQuote(value)
            then SingleQuoted
            else DoubleQuoted
            end if
        end stringStyle

        def plainKeySafe(value: String, config: Yaml.WriterConfig): Boolean =
            import Yaml.WriterConfig.ScalarQuoting
            config.scalarQuoting != ScalarQuoting.QuoteAllStrings &&
            plainSyntaxSafe(value) &&
            value.indexOf(':') < 0 &&
            !(config.scalarQuoting == ScalarQuoting.QuoteAmbiguous && YamlScalars.resolvesAsCore(value, config.yamlVersion))
        end plainKeySafe

        def plainSyntaxSafe(value: String): Boolean =
            if value.isEmpty || value.charAt(0).isWhitespace || value.charAt(value.length - 1).isWhitespace then false
            else
                value.charAt(0) match
                    case '-' | '?' | ':' | ',' | '[' | ']' | '{' | '}' | '#' | '&' | '*' | '!' | '|' | '>' | '\'' | '"' | '@' |
                        '`' | '%' =>
                        false
                    case _ if value == "---" || value == "..." => false
                    case _                                     => plainBodySafe(value, 0)
            end if
        end plainSyntaxSafe

        @tailrec def plainBodySafe(value: String, i: Int): Boolean =
            if i >= value.length then true
            else
                value.charAt(i) match
                    case '\n' | '\r' | '\t' | '[' | ']' | '{' | '}' | ',' | '#' | ':' => false
                    case c if c < ' '                                                 => false
                    case _                                                            => plainBodySafe(value, i + 1)

        def doubleQuoted(value: String): String =
            val out = new StringBuilder
            appendDoubleQuoted(out, value)
            out.toString
        end doubleQuoted

        def appendDoubleQuoted(out: StringBuilder, value: String): Unit =
            out.append('"')

            @tailrec def loop(i: Int): Unit =
                if i < value.length then
                    value.charAt(i) match
                        case '"'  => out.append("\\\"")
                        case '\\' => out.append("\\\\")
                        case '\n' => out.append("\\n")
                        case '\r' => out.append("\\r")
                        case '\t' => out.append("\\t")
                        case c if c < ' ' =>
                            out.append("\\u")
                            out.append(Hex.charAt((c >> 12) & 0xf))
                            out.append(Hex.charAt((c >> 8) & 0xf))
                            out.append(Hex.charAt((c >> 4) & 0xf))
                            out.append(Hex.charAt(c & 0xf))
                        case c => out.append(c)
                    end match
                    loop(i + 1)
            end loop

            loop(0)
            out.append('"')
        end appendDoubleQuoted

        def singleQuoted(value: String): String =
            val out = new StringBuilder
            appendSingleQuoted(out, value)
            out.toString
        end singleQuoted

        def appendSingleQuoted(out: StringBuilder, value: String): Unit =
            out.append('\'')

            @tailrec def loop(i: Int): Unit =
                if i < value.length then
                    value.charAt(i) match
                        case '\'' => out.append("''")
                        case c    => out.append(c)
                    end match
                    loop(i + 1)
            end loop

            loop(0)
            out.append('\'')
        end appendSingleQuoted

        def canSingleQuote(value: String): Boolean =
            @tailrec def loop(i: Int): Boolean =
                if i >= value.length then true
                else
                    value.charAt(i) match
                        case c if c < ' ' || c == '\n' || c == '\r' || c == '\t' => false
                        case _                                                   => loop(i + 1)
            loop(0)
        end canSingleQuote

        def block(value: String, config: Yaml.WriterConfig, contentIndent: Int, folded: Boolean): String =
            val out = new StringBuilder
            appendBlock(out, value, config, contentIndent, folded)
            out.toString
        end block

        def appendBlock(
            out: StringBuilder,
            value: String,
            config: Yaml.WriterConfig,
            contentIndent: Int,
            folded: Boolean
        ): Unit =
            out.append(if folded then ">" else "|")
            out.append(chompingIndicator(value, config))
            out.append('\n')
            val end =
                if value.endsWith("\n") then value.length - 1
                else value.length

            @tailrec def appendChars(i: Int, stop: Int): Unit =
                if i < stop then
                    out.append(value.charAt(i))
                    appendChars(i + 1, stop)
            end appendChars

            @tailrec def appendLines(start: Int): Unit =
                if start <= end && start < value.length then
                    val next =
                        value.indexOf('\n', start) match
                            case -1 => end
                            case n  => math.min(n, end)
                    if next > start then appendIndent(out, contentIndent)
                    appendChars(start, next)
                    out.append('\n')
                    if folded && next < end then out.append('\n')
                    appendLines(next + 1)
            end appendLines

            appendLines(0)
        end appendBlock

        def appendIndent(out: StringBuilder, n: Int): Unit =
            @tailrec def loop(i: Int): Unit =
                if i < n then
                    out.append(' ')
                    loop(i + 1)
            loop(0)
        end appendIndent

        private def chompingIndicator(value: String, config: Yaml.WriterConfig): String =
            import Yaml.WriterConfig.Chomping.*
            config.chomping match
                case Strip => "-"
                case Keep  => "+"
                case Clip  => ""
                case Preserve =>
                    if !value.endsWith("\n") then "-"
                    else if trailingNewlineCount(value) > 1 then "+"
                    else ""
            end match
        end chompingIndicator

        private def trailingNewlineCount(value: String): Int =
            @tailrec def loop(i: Int, count: Int): Int =
                if i >= 0 && value.charAt(i) == '\n' then loop(i - 1, count + 1)
                else count
            loop(value.length - 1, 0)
        end trailingNewlineCount

        private val Hex = "0123456789abcdef"
    end Scalar
end YamlEvents
