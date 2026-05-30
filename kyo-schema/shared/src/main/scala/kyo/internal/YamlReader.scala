package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.Codec.Reader
import scala.collection.mutable.ArrayBuffer

final class YamlReader private (
    private var source: String,
    private val yamlVersion: Yaml.SpecVersion,
    private var events: Array[YamlReader.Event],
    private var pos: Int,
    private var end: Int,
    private val anchors: scala.collection.mutable.Map[String, YamlReader.Anchor],
    private val expansion: YamlReader.Expansion,
    private val allowSourcePull: Boolean
)(using _frame: Frame) extends Reader:
    import YamlReader.*

    private var prepared: Boolean               = events ne null
    private var stack: List[ContainerFrame]     = Nil
    private var delegate: Maybe[YamlReader]     = Absent
    private var delegateDepth: Int              = 0
    private var sourcePos: Int                  = 0
    private var sourceFrames: List[SourceFrame] = Nil
    private var _lastFieldName: String          = ""
    private var _lastFieldBytes: Array[Byte]    = Array.emptyByteArray
    private var fieldValues: Array[AnyRef]      = new Array[AnyRef](16)
    private var fieldDepth: Int                 = 0

    override def frame: Frame = _frame

    override private[kyo] def resetLimits(maxDepth: Int, maxCollectionSize: Int): Unit =
        super.resetLimits(maxDepth, maxCollectionSize)
        delegate.foreach(_.resetLimits(maxDepth, maxCollectionSize))
    end resetLimits

    def objectStart(): Int =
        withDelegateCollection(_.objectStart(), _ + 1) {
            if trySourceObjectStart() then if sourceMappingEmpty then 0 else -1
            else
                currentAliasOr { reader =>
                    val out = reader.objectStart()
                    delegateDepth = 1
                    out
                } {
                    peek match
                        case e: MappingStart =>
                            checkDepth()
                            pos += 1
                            stack = MappingFrame :: stack
                            if atNodeEnd then 0 else -1
                        case other => expected("mapping", other)
                }
        }
    end objectStart

    def objectEnd(): Unit =
        withDelegateEnd(_.objectEnd()) {
            sourceFrames match
                case (_: SourceMappingFrame) :: rest =>
                    sourceFrames = rest
                    decrementDepth()
                case _ =>
                    expectNodeEnd("mapping")
                    stack = stack match
                        case (_: MappingFrame.type) :: rest => rest
                        case _                              => error("Unexpected mapping end")
                    decrementDepth()
            end match
        }
    end objectEnd

    def arrayStart(): Int =
        withDelegateCollection(_.arrayStart(), _ + 1) {
            if trySourceArrayStart() then if sourceSequenceEmpty then 0 else -1
            else
                currentAliasOr { reader =>
                    val out = reader.arrayStart()
                    delegateDepth = 1
                    out
                } {
                    peek match
                        case e: SequenceStart =>
                            checkDepth()
                            pos += 1
                            stack = SequenceFrame :: stack
                            if atNodeEnd then 0 else -1
                        case other => expected("sequence", other)
                }
        }
    end arrayStart

    def arrayEnd(): Unit =
        withDelegateEnd(_.arrayEnd()) {
            sourceFrames match
                case (_: SourceSequenceFrame) :: rest =>
                    sourceFrames = rest
                    decrementDepth()
                case _ =>
                    expectNodeEnd("sequence")
                    stack = stack match
                        case (_: SequenceFrame.type) :: rest => rest
                        case _                               => error("Unexpected sequence end")
                    decrementDepth()
            end match
        }
    end arrayEnd

    def field(): String =
        fieldParse()
        _lastFieldName
    end field

    override def fieldParse(): Unit =
        delegate match
            case Present(reader) =>
                reader.fieldParse()
                _lastFieldName = reader.lastFieldName()
                _lastFieldBytes = _lastFieldName.getBytes(StandardCharsets.UTF_8)
                clearFinishedDelegate()
            case Absent if sourceFrames.headOption.exists(_.isInstanceOf[SourceMappingFrame]) =>
                sourceFieldParse()
            case Absent =>
                currentAliasOr { reader =>
                    reader.fieldParse()
                    _lastFieldName = reader.lastFieldName()
                    _lastFieldBytes = _lastFieldName.getBytes(StandardCharsets.UTF_8)
                } {
                    peek match
                        case Scalar(value, _, _) =>
                            pos += 1
                            _lastFieldName = value
                            _lastFieldBytes = value.getBytes(StandardCharsets.UTF_8)
                        case other => expected("field name", other)
                }
    end fieldParse

    override def matchField(nameBytes: Array[Byte]): Boolean =
        if nameBytes.length != _lastFieldBytes.length then false
        else
            var i = 0
            while i < nameBytes.length do
                if nameBytes(i) != _lastFieldBytes(i) then return false
                i += 1
            end while
            true
    end matchField

    override def lastFieldName(): String = _lastFieldName

    def hasNextField(): Boolean =
        withDelegate(_.hasNextField()) {
            sourceFrames match
                case (_: SourceMappingFrame) :: _ => sourceHasNextField()
                case _ =>
                    prepare()
                    !atNodeEnd
            end match
        }
    end hasNextField

    def hasNextElement(): Boolean =
        withDelegate(_.hasNextElement()) {
            sourceFrames match
                case (_: SourceSequenceFrame) :: _ => sourceHasNextElement()
                case _ =>
                    prepare()
                    !atNodeEnd
            end match
        }
    end hasNextElement

    def string(): String =
        scalarValue() match
            case ScalarValue.Null           => ""
            case ScalarValue.Bool(value)    => value.toString
            case ScalarValue.Number(value)  => value
            case ScalarValue.Special(value) => value
            case ScalarValue.Str(value)     => value
    end string

    def int(): Int =
        val value = numberString("Int")
        try value.toInt
        catch
            case _: NumberFormatException => error(s"Invalid Int value: '$value'")
    end int

    def long(): Long =
        val value = numberString("Long")
        try value.toLong
        catch
            case _: NumberFormatException => error(s"Invalid Long value: '$value'")
    end long

    def float(): Float =
        scalarValue() match
            case ScalarValue.Special("NaN")       => Float.NaN
            case ScalarValue.Special("Infinity")  => Float.PositiveInfinity
            case ScalarValue.Special("-Infinity") => Float.NegativeInfinity
            case ScalarValue.Number(value) =>
                checkNumericScalar(value)
                try value.toFloat
                catch
                    case _: NumberFormatException => error(s"Invalid Float value: '$value'")
            case other => error(s"Expected Float, got ${describeScalar(other)}")
    end float

    def double(): Double =
        scalarValue() match
            case ScalarValue.Special("NaN")       => Double.NaN
            case ScalarValue.Special("Infinity")  => Double.PositiveInfinity
            case ScalarValue.Special("-Infinity") => Double.NegativeInfinity
            case ScalarValue.Number(value) =>
                checkNumericScalar(value)
                try value.toDouble
                catch
                    case _: NumberFormatException => error(s"Invalid Double value: '$value'")
            case other => error(s"Expected Double, got ${describeScalar(other)}")
    end double

    def boolean(): Boolean =
        scalarValue() match
            case ScalarValue.Bool(value) => value
            case other                   => error(s"Expected Boolean, got ${describeScalar(other)}")
    end boolean

    def short(): Short =
        val value = int()
        if value < Short.MinValue || value > Short.MaxValue then
            throw RangeException(value, "Short", Short.MinValue, Short.MaxValue)
        value.toShort
    end short

    def byte(): Byte =
        val value = int()
        if value < Byte.MinValue || value > Byte.MaxValue then
            throw RangeException(value, "Byte", Byte.MinValue, Byte.MaxValue)
        value.toByte
    end byte

    def char(): Char =
        val value = string()
        if value.length != 1 then error(s"Expected single character, got string of length ${value.length}")
        value.charAt(0)
    end char

    def isNil(): Boolean =
        withDelegate(_.isNil()) {
            if allowSourcePull && !prepared && sourceFrames.nonEmpty then false
            else
                currentAliasOr(_.isNil()) {
                    peek match
                        case Scalar(value, meta, _) if resolveScalar(value, meta) == ScalarValue.Null =>
                            pos += 1
                            true
                        case _ => false
                }
        }
    end isNil

    def skip(): Unit =
        delegate match
            case Present(reader) =>
                reader.skip()
                clearFinishedDelegate()
            case Absent =>
                sourceFrames match
                    case (f: SourceSequenceFrame) :: _ if allowSourcePull && !prepared =>
                        sourceCaptureSequenceElement(f)
                        delegate = Absent
                    case _ =>
                        currentAliasOr(_.skip()) {
                            prepare()
                            pos = subtreeEnd(pos)
                        }
                end match
        end match
    end skip

    def mapStart(): Int         = objectStart()
    def mapEnd(): Unit          = objectEnd()
    def hasNextEntry(): Boolean = hasNextField()

    def bytes(): Span[Byte] =
        val value = string()
        try Span.from(java.util.Base64.getDecoder.decode(value))
        catch
            case e: IllegalArgumentException => error(s"Invalid Base64: ${e.getMessage}")
    end bytes

    def bigInt(): BigInt =
        val value = numberString("BigInt")
        try BigInt(value)
        catch
            case _: NumberFormatException => error(s"Invalid BigInt value: '$value'")
    end bigInt

    def bigDecimal(): BigDecimal =
        val value = numberString("BigDecimal")
        checkDecimalExponent(value)
        try BigDecimal(value)
        catch
            case _: NumberFormatException => error(s"Invalid BigDecimal value: '$value'")
    end bigDecimal

    def instant(): java.time.Instant =
        val value = string()
        try java.time.Instant.parse(value)
        catch
            case e: java.time.format.DateTimeParseException =>
                error(s"Invalid Instant value: '$value' (${e.getMessage})")
        end try
    end instant

    def duration(): java.time.Duration =
        val value = string()
        try java.time.Duration.parse(value)
        catch
            case e: java.time.format.DateTimeParseException =>
                error(s"Invalid Duration value: '$value' (${e.getMessage})")
        end try
    end duration

    override def initFields(n: Int): Array[AnyRef] =
        fieldDepth += 1
        if fieldDepth == 1 then
            if n > fieldValues.length then fieldValues = new Array[AnyRef](n)
            else java.util.Arrays.fill(fieldValues, 0, n, null)
            fieldValues
        else new Array[AnyRef](n)
        end if
    end initFields

    override def clearFields(n: Int): Unit =
        if fieldDepth == 1 then java.util.Arrays.fill(fieldValues, 0, n, null)
        fieldDepth -= 1
    end clearFields

    override def captureValue(): Reader =
        delegate match
            case Present(reader) if delegateDepth == 0 =>
                delegate = Absent
                reader
            case _ =>
                sourceFrames match
                    case (f: SourceSequenceFrame) :: _ if allowSourcePull && !prepared =>
                        sourceCaptureSequenceElement(f)
                        delegate match
                            case Present(reader) =>
                                delegate = Absent
                                reader
                            case Absent => error("Expected captured YAML value")
                        end match
                    case _ =>
                        withDelegate(_.captureValue()) {
                            prepare()
                            val start = pos
                            val stop  = subtreeEnd(pos)
                            pos = stop
                            child(start, stop)
                        }
                end match
        end match
    end captureValue

    private def scalarValue(): ScalarValue =
        withDelegate(_.scalarValue()) {
            trySourceScalarValue() match
                case Present(value) => value
                case Absent =>
                    currentAliasOr(_.scalarValue()) {
                        peek match
                            case Scalar(value, meta, _) =>
                                pos += 1
                                resolveScalarValue(value, meta)
                            case other => expected("scalar", other)
                    }
            end match
        }
    end scalarValue

    private def resolveScalarValue(value: String, meta: Yaml.ScalarMeta): ScalarValue =
        checkPotentialNumericScalar(value)
        val resolved = resolveScalar(value, meta)
        resolved match
            case ScalarValue.Number(number) => checkNumericScalar(number)
            case _                          => ()
        resolved
    end resolveScalarValue

    private def numberString(expected: String): String =
        scalarValue() match
            case ScalarValue.Number(value) => value
            case other                     => error(s"Expected $expected, got ${describeScalar(other)}")
    end numberString

    private def resolveScalar(value: String, meta: Yaml.ScalarMeta): ScalarValue =
        normalizeTag(meta.tag) match
            case Present("!")                              => ScalarValue.Str(value)
            case Present("tag:yaml.org,2002:str")          => ScalarValue.Str(value)
            case Present("tag:yaml.org,2002:null")         => ScalarValue.Null
            case Present("tag:yaml.org,2002:bool")         => taggedBool(value)
            case Present("tag:yaml.org,2002:int")          => taggedInt(value)
            case Present("tag:yaml.org,2002:float")        => taggedFloat(value)
            case _ if meta.style != Yaml.ScalarStyle.Plain => ScalarValue.Str(value)
            case _ =>
                YamlScalars.resolve(value, yamlVersion) match
                    case YamlScalars.Core.Null           => ScalarValue.Null
                    case YamlScalars.Core.Bool(value)    => ScalarValue.Bool(value)
                    case YamlScalars.Core.Number(value)  => ScalarValue.Number(value)
                    case YamlScalars.Core.Special(value) => ScalarValue.Special(value)
                    case YamlScalars.Core.Str(value)     => ScalarValue.Str(value)
        end match
    end resolveScalar

    private def taggedBool(value: String): ScalarValue =
        YamlScalars.parseBool(value, yamlVersion) match
            case Present(value) => ScalarValue.Bool(value)
            case Absent         => error(s"Invalid YAML boolean value: '$value'")
    end taggedBool

    private def taggedInt(value: String): ScalarValue =
        YamlScalars.parseInt(value, yamlVersion) match
            case Present(value) => ScalarValue.Number(value)
            case Absent         => error(s"Invalid YAML integer value: '$value'")
    end taggedInt

    private def taggedFloat(value: String): ScalarValue =
        YamlScalars.parseFloat(value, yamlVersion) match
            case Present(YamlScalars.Core.Number(value))  => ScalarValue.Number(value)
            case Present(YamlScalars.Core.Special(value)) => ScalarValue.Special(value)
            case _                                        => error(s"Invalid YAML float value: '$value'")
    end taggedFloat

    private def normalizeTag(tag: Maybe[String]): Maybe[String] =
        tag.map {
            case "!!str"   => "tag:yaml.org,2002:str"
            case "!!int"   => "tag:yaml.org,2002:int"
            case "!!bool"  => "tag:yaml.org,2002:bool"
            case "!!float" => "tag:yaml.org,2002:float"
            case "!!null"  => "tag:yaml.org,2002:null"
            case other     => other
        }
    end normalizeTag

    private def checkNumericScalar(value: String): Unit =
        val maximum = math.min(maxCollectionSize, MaxNumericScalarLength)
        if value.length > maximum then
            throw LimitExceededException("Numeric scalar length", value.length, maximum)
    end checkNumericScalar

    private def checkPotentialNumericScalar(value: String): Unit =
        if value.nonEmpty then
            val start =
                if value.charAt(0) == '-' || value.charAt(0) == '+' then 1
                else 0
            if start < value.length then
                val ch = value.charAt(start)
                if (ch >= '0' && ch <= '9') || ch == '.' then checkNumericScalar(value)
        end if
    end checkPotentialNumericScalar

    private def checkDecimalExponent(value: String): Unit =
        val exponentAt =
            val lower = value.indexOf('e')
            if lower >= 0 then lower else value.indexOf('E')
        if exponentAt >= 0 then
            var i = exponentAt + 1
            if i < value.length && (value.charAt(i) == '-' || value.charAt(i) == '+') then i += 1
            var exponent = 0
            while i < value.length && exponent <= MaxDecimalExponent do
                val ch = value.charAt(i)
                if ch >= '0' && ch <= '9' then exponent = exponent * 10 + (ch - '0')
                i += 1
            end while
            if exponent > MaxDecimalExponent then
                throw LimitExceededException("Numeric scalar exponent", exponent, MaxDecimalExponent)
        end if
    end checkDecimalExponent

    private def withDelegate[A](f: YamlReader => A)(body: => A): A =
        delegate match
            case Present(reader) =>
                val out = f(reader)
                clearFinishedDelegate()
                out
            case Absent => body
    end withDelegate

    private def withDelegateCollection[A](f: YamlReader => A, depth: Int => Int)(body: => A): A =
        delegate match
            case Present(reader) =>
                val out = f(reader)
                delegateDepth = depth(delegateDepth)
                out
            case Absent => body
    end withDelegateCollection

    private def withDelegateEnd(f: YamlReader => Unit)(body: => Unit): Unit =
        delegate match
            case Present(reader) =>
                f(reader)
                delegateDepth -= 1
                clearFinishedDelegate()
            case Absent => body
    end withDelegateEnd

    private def clearFinishedDelegate(): Unit =
        if delegateDepth == 0 then
            delegate match
                case Present(reader) if reader.finished =>
                    delegate = Absent
                case _ => ()
    end clearFinishedDelegate

    private def currentAliasOr[A](onAlias: YamlReader => A)(body: => A): A =
        prepare()
        peek match
            case Alias(name, mark) =>
                startAlias(name, mark)
                delegate match
                    case Present(reader) =>
                        val out = onAlias(reader)
                        clearFinishedDelegate()
                        out
                    case Absent => body
                end match
            case _ => body
        end match
    end currentAliasOr

    private def startAlias(name: String, mark: Yaml.Mark): Unit =
        anchors.get(name) match
            case Some(anchor) =>
                expansion.values += anchor.values
                expansion.depth = math.max(expansion.depth, anchor.maxDepth)
                checkCollectionSize(expansion.values)
                if expansion.depth > maxDepth then throw LimitExceededException("Nesting depth", expansion.depth, maxDepth)
                pos += 1
                val reader = anchor.source match
                    case Present(source) => sourceChild(source)
                    case Absent          => child(anchor.start, anchor.end)
                reader.resetLimits(maxDepth, maxCollectionSize)
                delegate = Maybe(reader)
                delegateDepth = 0
            case None =>
                throw ParseException(Yaml(), "", s"Unknown alias '$name' at line ${mark.line}, column ${mark.column}", Nil, mark.index)
        end match
    end startAlias

    private def child(start: Int, stop: Int): YamlReader =
        val reader = new YamlReader("", yamlVersion, events, start, stop, anchors, expansion, allowSourcePull = false)
        reader.resetLimits(maxDepth, maxCollectionSize)
        reader
    end child

    private def sourceChild(input: String): YamlReader =
        val reader = new YamlReader(input, yamlVersion, null, 0, 0, anchors, expansion, allowSourcePull)
        reader.resetLimits(math.max(0, maxDepth - sourceFrames.size), maxCollectionSize)
        reader
    end sourceChild

    private def trySourceObjectStart(): Boolean =
        if !allowSourcePull || prepared || source.isEmpty then false
        else
            initSourcePosition()
            if sourcePos >= source.length then false
            else
                val lineText = currentSourceLine()
                if sourceStartsFlowCollection then false
                else if isSourceBlockMappingLine(lineText) then
                    checkDepth()
                    sourceFrames = SourceMappingFrame(currentSourceIndent()) :: sourceFrames
                    true
                else false
                end if
            end if
        end if
    end trySourceObjectStart

    private def trySourceArrayStart(): Boolean =
        if !allowSourcePull || prepared || source.isEmpty then false
        else
            initSourcePosition()
            if sourcePos >= source.length || sourceStartsFlowCollection then false
            else if sourceStartsSequenceEntryAtIndent() then
                checkDepth()
                sourceFrames = SourceSequenceFrame(currentSourceIndent()) :: sourceFrames
                true
            else false
            end if
        end if
    end trySourceArrayStart

    private def initSourcePosition(): Unit =
        if sourcePos == 0 then
            skipSourceIgnorable()
            if sourceIsDocumentMarker("---") then
                val _ = readSourceRestOfLine()
                skipSourceIgnorable()
    end initSourcePosition

    private def sourceMappingEmpty: Boolean =
        sourceFrames match
            case (f: SourceMappingFrame) :: _ =>
                skipSourceBlankAndCommentLines()
                sourcePos >= source.length || currentSourceIndent() < f.indent || !isSourceBlockMappingLine(currentSourceLine())
            case _ => false
    end sourceMappingEmpty

    private def sourceSequenceEmpty: Boolean =
        sourceFrames match
            case (f: SourceSequenceFrame) :: _ =>
                skipSourceBlankAndCommentLines()
                sourcePos >= source.length || currentSourceIndent() < f.indent || !sourceStartsSequenceEntryAtIndent()
            case _ => false
    end sourceSequenceEmpty

    private def sourceHasNextField(): Boolean =
        sourceFrames match
            case (f: SourceMappingFrame) :: _ =>
                skipSourceBlankAndCommentLines()
                sourcePos < source.length && currentSourceIndent() >= f.indent && isSourceBlockMappingLine(currentSourceLine())
            case _ => false
    end sourceHasNextField

    private def sourceHasNextElement(): Boolean =
        sourceFrames match
            case (f: SourceSequenceFrame) :: _ =>
                if delegate.nonEmpty then true
                else
                    skipSourceBlankAndCommentLines()
                    if sourcePos < source.length && currentSourceIndent() >= f.indent && sourceStartsSequenceEntryAtIndent() then
                        sourceCaptureSequenceElement(f)
                        true
                    else false
                    end if
            case _ => false
    end sourceHasNextElement

    private def sourceFieldParse(): Unit =
        sourceFrames match
            case (f: SourceMappingFrame) :: _ =>
                skipSourceBlankAndCommentLines()
                f.count += 1
                checkCollectionSize(f.count)
                val lineStart = sourcePos
                consumeSourceIndent(f.indent)
                val keyStart = sourcePos
                val line     = currentSourceLine()
                val colon    = findSourceTopLevel(line, ':')
                if colon < 0 then error("Expected YAML mapping field")
                val keyText = source.substring(keyStart, keyStart + colon).trim
                _lastFieldName = unquoteSourceKey(keyText)
                _lastFieldBytes = _lastFieldName.getBytes(StandardCharsets.UTF_8)
                sourcePos = keyStart + colon + 1
                val restStart = sourcePos
                val rest      = readSourceRestOfLineFrom(restStart)
                val value     = sourceCaptureMappingValue(f, rest, lineNumberAt(lineStart))
                delegate = Maybe(sourceChild(value))
                delegateDepth = 0
            case _ => error("Expected YAML mapping field")
    end sourceFieldParse

    private def sourceCaptureMappingValue(frame: SourceMappingFrame, rest: String, lineNumber: Int): String =
        val trimmed = stripSourceComment(rest).trim
        if trimmed.nonEmpty then
            val (anchor, _, valueText) = sourceProperties(trimmed)
            val tailStart              = sourcePos
            val tailEnd                = captureFollowingIndentedBlock(frame.indent)
            if valueText.isEmpty && tailEnd > tailStart then
                val value = preserveSourceLine(normalizeBlock(tailStart, tailEnd, frame.indent + 2), lineNumberAt(tailStart))
                anchor.foreach(registerSourceAnchor(_, value))
                value
            else if tailEnd > tailStart then
                preserveSourceLine(trimmed + "\n" + normalizeBlock(tailStart, tailEnd, frame.indent), lineNumber)
            else preserveSourceLine(trimmed + "\n", lineNumber)
            end if
        else
            val start = sourcePos
            val end   = captureNestedBlock(frame.indent, includeIndentlessSequence = true)
            if end > start then preserveSourceLine(normalizeBlock(start, end, frame.indent + 2), lineNumberAt(start))
            else "\n"
        end if
    end sourceCaptureMappingValue

    private def sourceCaptureSequenceElement(frame: SourceSequenceFrame): Unit =
        frame.count += 1
        checkCollectionSize(frame.count)
        val lineNumber = currentSourceLineNumber()
        consumeSourceIndent(frame.indent)
        sourcePos += 1
        if sourcePos < source.length && source.charAt(sourcePos) == ' ' then sourcePos += 1
        val restStart = sourcePos
        val rest      = readSourceRestOfLineFrom(restStart)
        val trimmed   = stripSourceComment(rest).trim
        val value =
            if trimmed.nonEmpty then
                val tailStart = sourcePos
                val tailEnd   = captureFollowingIndentedBlock(frame.indent)
                val captured =
                    if tailEnd > tailStart then trimmed + "\n" + normalizeBlock(tailStart, tailEnd, frame.indent + 2)
                    else trimmed + "\n"
                preserveSourceLine(captured, lineNumber)
            else
                val start = sourcePos
                val end   = captureNestedBlock(frame.indent, includeIndentlessSequence = false)
                val captured =
                    if end > start then normalizeBlock(start, end, frame.indent + 2)
                    else "\n"
                preserveSourceLine(captured, lineNumber)
        delegate = Maybe(sourceChild(value))
        delegateDepth = 0
    end sourceCaptureSequenceElement

    private def trySourceScalarValue(): Maybe[ScalarValue] =
        if !allowSourcePull || prepared || sourceFrames.nonEmpty || source.isEmpty then Absent
        else
            initSourcePosition()
            if sourcePos >= source.length then Absent
            else
                val line    = currentSourceLine()
                val text    = source.substring(sourcePos)
                val trimmed = line.trim
                if trimmed.startsWith("*") ||
                    sourceStartsFlowCollection ||
                    sourceStartsSequenceEntryAtIndent() ||
                    isSourceBlockMappingLine(line)
                then Absent
                else
                    val built = EventBuilder.build("value: " + text, maxDepth, maxCollectionSize)
                    if built.events.length >= 3 then
                        built.events(2) match
                            case Scalar(value, meta, _) =>
                                sourcePos = source.length
                                Maybe(resolveScalarValue(value, meta))
                            case _ => Absent
                    else Absent
                    end if
                end if
            end if
        end if
    end trySourceScalarValue

    private def captureNestedBlock(parentIndent: Int, includeIndentlessSequence: Boolean): Int =
        val start = sourcePos
        var end   = sourcePos
        var done  = false
        while !done && sourcePos < source.length do
            val lineStart = sourcePos
            val line      = currentSourceLine()
            val trimmed   = line.trim
            val indent    = currentSourceIndent()
            if trimmed.isEmpty || trimmed.startsWith("#") then
                val _ = readSourceRestOfLine()
                end = sourcePos
            else if indent > parentIndent || (includeIndentlessSequence && indent == parentIndent && sourceStartsSequenceEntryAtIndent())
            then
                skipSourceNodeLine(parentIndent)
                end = sourcePos
            else done = true
            end if
        end while
        end
    end captureNestedBlock

    private def captureFollowingIndentedBlock(parentIndent: Int): Int =
        val start = sourcePos
        var end   = sourcePos
        var done  = false
        while !done && sourcePos < source.length do
            val line    = currentSourceLine()
            val trimmed = line.trim
            val indent  = currentSourceIndent()
            if trimmed.isEmpty || trimmed.startsWith("#") then
                val _ = readSourceRestOfLine()
                end = sourcePos
            else if indent > parentIndent then
                skipSourceNodeLine(parentIndent)
                end = sourcePos
            else done = true
            end if
        end while
        end
    end captureFollowingIndentedBlock

    private def skipSourceNodeLine(parentIndent: Int): Unit =
        val line    = currentSourceLine()
        val trimmed = line.trim
        if trimmed.startsWith("|") || trimmed.startsWith(">") || line.contains(": |") || line.contains(": >") then
            val _ = readSourceRestOfLine()
            while sourcePos < source.length && (currentSourceLine().trim.isEmpty || currentSourceIndent() > parentIndent) do
                val _ = readSourceRestOfLine()
        else
            val _ = readSourceRestOfLine()
        end if
    end skipSourceNodeLine

    private def normalizeBlock(start: Int, stop: Int, stripIndent: Int): String =
        val out = new StringBuilder
        var i   = start
        while i < stop do
            var removed = 0
            while removed < stripIndent && i < stop && source.charAt(i) == ' ' do
                i += 1
                removed += 1
            end while
            while i < stop && source.charAt(i) != '\n' do
                out.append(source.charAt(i))
                i += 1
            end while
            if i < stop && source.charAt(i) == '\n' then
                out.append('\n')
                i += 1
        end while
        out.toString
    end normalizeBlock

    private def skipSourceIgnorable(): Unit =
        var done = false
        while !done && sourcePos < source.length do
            skipSourceBlankAndCommentLines()
            val line = if sourcePos < source.length then currentSourceLine().trim else ""
            if line.startsWith("%") then
                val _ = readSourceRestOfLine()
            else done = true
        end while
    end skipSourceIgnorable

    private def skipSourceBlankAndCommentLines(): Unit =
        var done = false
        while !done && sourcePos < source.length do
            val trimmed = currentSourceLine().trim
            if trimmed.isEmpty || trimmed.startsWith("#") then
                val _ = readSourceRestOfLine()
            else done = true
        end while
    end skipSourceBlankAndCommentLines

    private def currentSourceIndent(): Int =
        var i = sourcePos
        var n = 0
        while i < source.length && source.charAt(i) == ' ' do
            n += 1
            i += 1
        n
    end currentSourceIndent

    private def consumeSourceIndent(n: Int): Unit =
        var i = 0
        while i < n && sourcePos < source.length && source.charAt(sourcePos) == ' ' do
            sourcePos += 1
            i += 1
    end consumeSourceIndent

    private def currentSourceLine(): String =
        val end = source.indexOf('\n', sourcePos) match
            case -1 => source.length
            case n  => n
        source.substring(sourcePos, end)
    end currentSourceLine

    private def currentSourceLineNumber(): Int =
        lineNumberAt(sourcePos)
    end currentSourceLineNumber

    private def lineNumberAt(position: Int): Int =
        var line = 1
        var i    = 0
        while i < position do
            if source.charAt(i) == '\n' then line += 1
            i += 1
        line
    end lineNumberAt

    private def preserveSourceLine(value: String, lineNumber: Int): String =
        if lineNumber <= 1 then value
        else
            val out = new StringBuilder(value.length + lineNumber)
            var i   = 1
            while i < lineNumber do
                out.append('\n')
                i += 1
            out.append(value)
            out.toString
        end if
    end preserveSourceLine

    private def readSourceRestOfLine(): String =
        val start = sourcePos
        readSourceRestOfLineFrom(start)
    end readSourceRestOfLine

    private def readSourceRestOfLineFrom(start: Int): String =
        while sourcePos < source.length && source.charAt(sourcePos) != '\n' do sourcePos += 1
        val out = source.substring(start, sourcePos)
        if sourcePos < source.length && source.charAt(sourcePos) == '\n' then sourcePos += 1
        out
    end readSourceRestOfLineFrom

    private def isSourceBlockMappingLine(lineText: String): Boolean =
        val stripped = lineText.dropWhile(_ == ' ')
        val colon    = findSourceTopLevel(stripped, ':')
        colon >= 0 && (colon == stripped.length - 1 || stripped.charAt(colon + 1).isWhitespace)
    end isSourceBlockMappingLine

    private def sourceStartsSequenceEntryAtIndent(): Boolean =
        val indent = currentSourceIndent()
        val idx    = sourcePos + indent
        idx < source.length && source.charAt(idx) == '-' && (
            idx + 1 >= source.length ||
                source.charAt(idx + 1) == ' ' ||
                source.charAt(idx + 1) == '\n' ||
                source.charAt(idx + 1) == '\r'
        )
    end sourceStartsSequenceEntryAtIndent

    private def sourceStartsFlowCollection: Boolean =
        val indent = currentSourceIndent()
        val idx    = sourcePos + indent
        idx < source.length && (source.charAt(idx) == '[' || source.charAt(idx) == '{')
    end sourceStartsFlowCollection

    private def sourceIsDocumentMarker(marker: String): Boolean =
        val lineText = currentSourceLine()
        currentSourceIndent() == 0 && lineText.startsWith(marker) && (
            lineText.length == marker.length ||
                lineText.charAt(marker.length).isWhitespace ||
                lineText.charAt(marker.length) == '#'
        )
    end sourceIsDocumentMarker

    private def stripSourceComment(s: String): String =
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
    end stripSourceComment

    private def unquoteSourceKey(text: String): String =
        if text.length >= 2 && text.charAt(0) == '\'' && text.charAt(text.length - 1) == '\'' then
            text.substring(1, text.length - 1).replace("''", "'")
        else if text.length >= 2 && text.charAt(0) == '"' && text.charAt(text.length - 1) == '"' then
            text.substring(1, text.length - 1)
        else text
    end unquoteSourceKey

    private def sourceProperties(text: String): (Maybe[String], Maybe[String], String) =
        var anchor: Maybe[String] = Absent
        var tag: Maybe[String]    = Absent
        var rest                  = text.trim
        var changed               = true
        while changed do
            changed = false
            if rest.startsWith("&") then
                val (token, next) = sourcePropertyToken(rest)
                anchor = Maybe(token.drop(1))
                rest = next
                changed = true
            else if rest.startsWith("!") then
                val (token, next) = sourcePropertyToken(rest)
                tag = Maybe(token)
                rest = next
                changed = true
            end if
        end while
        (anchor, tag, rest)
    end sourceProperties

    private def sourcePropertyToken(text: String): (String, String) =
        val end = text.indexWhere(_.isWhitespace) match
            case -1 => text.length
            case n  => n
        (text.substring(0, end), text.substring(end).trim)
    end sourcePropertyToken

    private def registerSourceAnchor(name: String, value: String): Unit =
        val built = EventBuilder.build(value, maxDepth, maxCollectionSize)
        anchors(name) =
            Anchor(0, 0, valueCount(built.events, 0, built.events.length), depth(built.events, 0, built.events.length), Maybe(value))
    end registerSourceAnchor

    private def findSourceTopLevel(s: String, target: Char): Int =
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
    end findSourceTopLevel

    private def prepare(): Unit =
        if !prepared then
            val built = EventBuilder.build(source, maxDepth, maxCollectionSize)
            events = built.events
            end = events.length
            anchors ++= built.anchors
            source = ""
            prepared = true
    end prepare

    private def peek: Event =
        prepare()
        if pos >= end then error("Unexpected end of YAML input")
        events(pos)
    end peek

    private def atNodeEnd: Boolean =
        prepare()
        pos < end && events(pos).isInstanceOf[NodeEnd]
    end atNodeEnd

    private def expectNodeEnd(expected: String): Unit =
        peek match
            case _: NodeEnd => pos += 1
            case other      => this.expected(s"$expected end", other)
    end expectNodeEnd

    private def subtreeEnd(start: Int): Int =
        prepare()
        events(start) match
            case _: Scalar | _: Alias => start + 1
            case _: MappingStart | _: SequenceStart =>
                var depth = 1
                var i     = start + 1
                while i < end && depth > 0 do
                    events(i) match
                        case _: MappingStart | _: SequenceStart => depth += 1
                        case _: NodeEnd                         => depth -= 1
                        case _                                  => ()
                    end match
                    i += 1
                end while
                if depth != 0 then error("Unterminated YAML collection")
                i
            case _: NodeEnd => start + 1
        end match
    end subtreeEnd

    private def finished: Boolean =
        delegate match
            case Present(reader) if !reader.finished => false
            case _ =>
                if allowSourcePull && !prepared then
                    skipSourceBlankAndCommentLines()
                    sourceFrames.isEmpty && (sourcePos >= source.length || sourceIsDocumentMarker("..."))
                else
                    prepare()
                    pos >= end
                end if
        end match
    end finished

    private def expected[A](expected: String, event: Event): A =
        error(s"Expected $expected, got ${describeEvent(event)}")
    end expected

    private def error[A](message: String): A =
        val position =
            if prepared && pos < end then events(pos).mark.index
            else -1
        throw ParseException(Yaml(), "", message, Nil, position)
    end error
end YamlReader

object YamlReader:
    private val MaxDecimalExponent     = 10000
    private val MaxNumericScalarLength = 4096

    sealed private[internal] trait Event:
        def mark: Yaml.Mark
        def anchor: Maybe[String]
    end Event

    final private[internal] case class MappingStart(meta: Yaml.Meta) extends Event:
        def mark: Yaml.Mark       = meta.mark
        def anchor: Maybe[String] = meta.anchor
    end MappingStart

    final private[internal] case class SequenceStart(meta: Yaml.Meta) extends Event:
        def mark: Yaml.Mark       = meta.mark
        def anchor: Maybe[String] = meta.anchor
    end SequenceStart

    final private[internal] case class Scalar(value: String, meta: Yaml.ScalarMeta, mark: Yaml.Mark) extends Event:
        def anchor: Maybe[String] = meta.anchor
    end Scalar

    final private[internal] case class Alias(name: String, mark: Yaml.Mark) extends Event:
        def anchor: Maybe[String] = Absent
    end Alias

    final private[internal] case class NodeEnd(mark: Yaml.Mark) extends Event:
        def anchor: Maybe[String] = Absent
    end NodeEnd

    final private case class Built(events: Array[Event], anchors: scala.collection.mutable.Map[String, Anchor])
    final private case class Anchor(start: Int, end: Int, values: Int, maxDepth: Int, source: Maybe[String])
    final private class Expansion:
        var values: Int = 0
        var depth: Int  = 0
    end Expansion

    sealed private trait ContainerFrame
    private case object MappingFrame  extends ContainerFrame
    private case object SequenceFrame extends ContainerFrame

    sealed private trait SourceFrame:
        def indent: Int
    end SourceFrame
    final private case class SourceMappingFrame(indent: Int, var count: Int = 0)  extends SourceFrame
    final private case class SourceSequenceFrame(indent: Int, var count: Int = 0) extends SourceFrame

    private enum ScalarValue derives CanEqual:
        case Null
        case Bool(value: Boolean)
        case Number(value: String)
        case Special(value: String)
        case Str(value: String)
    end ScalarValue

    private object EventBuilder:
        def build(
            source: String,
            maxDepth: Int,
            maxCollectionSize: Int
        )(using Frame): Built =
            val buffer  = ArrayBuffer.empty[Event]
            val visitor = BuilderVisitor(buffer, maxDepth, maxCollectionSize)
            YamlParser(source).visit(())(visitor) match
                case Result.Success(_) => ()
                case Result.Failure(e) => throw e
                case Result.Panic(e)   => throw e
            end match
            val arr     = buffer.toArray
            val anchors = collectAnchors(arr)
            Built(arr, anchors)
        end build
    end EventBuilder

    final private class BuilderVisitor(
        buffer: ArrayBuffer[Event],
        maxDepth: Int,
        maxCollectionSize: Int
    )(using frame: Frame) extends Yaml.Visitor[Unit, DecodeException, Unit]:
        private var stack: List[BuildFrame] = Nil

        def streamStart(context: Unit, mark: Yaml.Mark): Result[DecodeException, Unit]   = Result.unit
        def documentStart(context: Unit, mark: Yaml.Mark): Result[DecodeException, Unit] = Result.unit
        def documentEnd(context: Unit, mark: Yaml.Mark): Result[DecodeException, Unit]   = Result.unit
        def streamEnd(context: Unit, mark: Yaml.Mark): Result[DecodeException, Unit]     = Result.unit

        def mappingStart(context: Unit, meta: Yaml.Meta): Result[DecodeException, Unit] =
            countValue()
            if stack.length + 1 > maxDepth then throw LimitExceededException("Nesting depth", stack.length + 1, maxDepth)
            buffer += MappingStart(meta)
            stack = BuildFrame(mapping = true) :: stack
            Result.unit
        end mappingStart

        def sequenceStart(context: Unit, meta: Yaml.Meta): Result[DecodeException, Unit] =
            countValue()
            if stack.length + 1 > maxDepth then throw LimitExceededException("Nesting depth", stack.length + 1, maxDepth)
            buffer += SequenceStart(meta)
            stack = BuildFrame(mapping = false) :: stack
            Result.unit
        end sequenceStart

        def scalar(context: Unit, value: String, meta: Yaml.ScalarMeta): Result[DecodeException, Unit] =
            countValue()
            buffer += Scalar(value, meta, meta.mark)
            Result.unit
        end scalar

        def alias(context: Unit, name: String, mark: Yaml.Mark): Result[DecodeException, Unit] =
            countValue()
            buffer += Alias(name, mark)
            Result.unit
        end alias

        def nodeEnd(context: Unit, mark: Yaml.Mark): Result[DecodeException, Unit] =
            stack = stack.drop(1)
            buffer += NodeEnd(mark)
            Result.unit
        end nodeEnd

        private def countValue(): Unit =
            stack match
                case f :: _ if f.mapping =>
                    if f.expectingKey then
                        f.count += 1
                        if f.count > maxCollectionSize then
                            throw LimitExceededException("Collection size", f.count, maxCollectionSize)
                        f.expectingKey = false
                    else f.expectingKey = true
                case f :: _ =>
                    f.count += 1
                    if f.count > maxCollectionSize then
                        throw LimitExceededException("Collection size", f.count, maxCollectionSize)
                case Nil => ()
        end countValue
    end BuilderVisitor

    final private class BuildFrame(val mapping: Boolean):
        var expectingKey: Boolean = true
        var count: Int            = 0
    end BuildFrame

    private def collectAnchors(events: Array[Event]): scala.collection.mutable.Map[String, Anchor] =
        val out = scala.collection.mutable.Map.empty[String, Anchor]
        var i   = 0
        while i < events.length do
            events(i).anchor match
                case Present(name) =>
                    val stop = subtreeEnd(events, i)
                    out(name) = Anchor(i, stop, valueCount(events, i, stop), depth(events, i, stop), Absent)
                case Absent => ()
            end match
            i += 1
        end while
        out
    end collectAnchors

    private def subtreeEnd(events: Array[Event], start: Int): Int =
        events(start) match
            case _: Scalar | _: Alias => start + 1
            case _: MappingStart | _: SequenceStart =>
                var depth = 1
                var i     = start + 1
                while i < events.length && depth > 0 do
                    events(i) match
                        case _: MappingStart | _: SequenceStart => depth += 1
                        case _: NodeEnd                         => depth -= 1
                        case _                                  => ()
                    end match
                    i += 1
                end while
                i
            case _: NodeEnd => start + 1
        end match
    end subtreeEnd

    private def valueCount(events: Array[Event], start: Int, end: Int): Int =
        var count = 0
        var i     = start
        while i < end do
            events(i) match
                case _: NodeEnd => ()
                case _          => count += 1
            i += 1
        end while
        count
    end valueCount

    private def depth(events: Array[Event], start: Int, end: Int): Int =
        var max     = 0
        var current = 0
        var i       = start
        while i < end do
            events(i) match
                case _: MappingStart | _: SequenceStart =>
                    current += 1
                    max = math.max(max, current)
                case _: NodeEnd =>
                    current -= 1
                case _ => ()
            end match
            i += 1
        end while
        max
    end depth

    private def describeEvent(event: Event): String =
        event match
            case _: MappingStart  => "mapping"
            case _: SequenceStart => "sequence"
            case Scalar(_, _, _)  => "scalar"
            case Alias(name, _)   => s"alias '$name'"
            case _: NodeEnd       => "collection end"
    end describeEvent

    private def describeScalar(value: ScalarValue): String =
        value match
            case ScalarValue.Null           => "null"
            case ScalarValue.Bool(_)        => "boolean"
            case ScalarValue.Number(_)      => "number"
            case ScalarValue.Special(value) => value
            case ScalarValue.Str(_)         => "string"
    end describeScalar

    def apply(input: Span[Byte])(using Frame): YamlReader =
        apply(input, Yaml.SpecVersion.Yaml12)

    def apply(input: Span[Byte], yamlVersion: Yaml.SpecVersion)(using Frame): YamlReader =
        new YamlReader(
            String(input.toArray, StandardCharsets.UTF_8),
            yamlVersion,
            null,
            0,
            0,
            scala.collection.mutable.Map.empty,
            Expansion(),
            allowSourcePull = true
        )

    def apply(input: String)(using Frame): YamlReader =
        apply(input, Yaml.SpecVersion.Yaml12)

    def apply(input: String, yamlVersion: Yaml.SpecVersion)(using Frame): YamlReader =
        new YamlReader(input, yamlVersion, null, 0, 0, scala.collection.mutable.Map.empty, Expansion(), allowSourcePull = true)
end YamlReader
