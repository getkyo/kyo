package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.Codec.Reader
import scala.annotation.tailrec

private[kyo] enum IonValue derives CanEqual:
    case NullValue
    case Bool(value: Boolean)
    case IntNum(value: BigInt)
    case DecNum(value: BigDecimal)
    case FloatNum(value: Double)
    case Str(value: String)
    case Symbol(value: String)
    case Timestamp(value: String)
    case Blob(value: Span[Byte])
    case ListVal(values: Vector[IonValue])
    case StructVal(fields: Vector[(String, IonValue)])

    def display: String =
        this match
            case NullValue    => "null"
            case Bool(_)      => "bool"
            case IntNum(_)    => "int"
            case DecNum(_)    => "decimal"
            case FloatNum(_)  => "float"
            case Str(_)       => "string"
            case Symbol(_)    => "symbol"
            case Timestamp(_) => "timestamp"
            case Blob(_)      => "blob"
            case ListVal(_)   => "list"
            case StructVal(_) => "struct"
end IonValue

final class IonReader private (
    private var raw: String,
    private var parsed: Maybe[IonValue],
    private var current: Maybe[IonValue],
    private var _frame: Frame
) extends Reader:
    import IonValue.*

    private enum Context:
        case Obj(fields: Vector[(String, IonValue)], index: Int)
        case Arr(values: Vector[IonValue], index: Int)

    import Context.*

    override def frame: Frame = _frame

    private var stack: List[Context]       = Nil
    private var parsedField: Maybe[String] = Maybe.empty

    def objectStart(): Int =
        value match
            case StructVal(fields) =>
                checkDepth()
                stack = Obj(fields, 0) :: stack
                fields.size
            case other => mismatch("struct", other)
    end objectStart

    def objectEnd(): Unit =
        stack match
            case Obj(_, _) :: tail =>
                decrementDepth()
                stack = tail
            case _ =>
                throw TypeMismatchException(Seq.empty, "struct end", "no active struct")(using _frame)
    end objectEnd

    def arrayStart(): Int =
        value match
            case ListVal(values) =>
                checkDepth()
                stack = Arr(values, 0) :: stack
                values.size
            case other => mismatch("list", other)
    end arrayStart

    def arrayEnd(): Unit =
        stack match
            case Arr(_, _) :: tail =>
                decrementDepth()
                stack = tail
            case _ =>
                throw TypeMismatchException(Seq.empty, "list end", "no active list")(using _frame)
    end arrayEnd

    def field(): String =
        stack match
            case Obj(fields, index) :: tail if index < fields.length =>
                val (name, fieldValue) = fields(index)
                stack = Obj(fields, index + 1) :: tail
                current = Maybe(fieldValue)
                parsedField = Maybe(name)
                name
            case Obj(_, _) :: _ =>
                throw MissingFieldException(Seq.empty, "<next>")(using _frame)
            case _ =>
                throw TypeMismatchException(Seq.empty, "field", "no active struct")(using _frame)
    end field

    override def fieldParse(): Unit =
        discard(field())

    override def matchField(nameBytes: Array[Byte]): Boolean =
        parsedField.exists { name =>
            java.util.Arrays.equals(name.getBytes(StandardCharsets.UTF_8), nameBytes)
        }

    override def lastFieldName(): String =
        parsedField.getOrElse("")

    def hasNextField(): Boolean =
        stack match
            case Obj(fields, index) :: _ => index < fields.length
            case _                       => false
    end hasNextField

    def hasNextElement(): Boolean =
        stack match
            case Arr(values, index) :: tail if index < values.length =>
                current = Maybe(values(index))
                stack = Arr(values, index + 1) :: tail
                true
            case Arr(_, _) :: _ => false
            case _              => false
    end hasNextElement

    def string(): String =
        value match
            case Str(v)       => v
            case Symbol(v)    => v
            case Timestamp(v) => v
            case other        => mismatch("string", other)

    def int(): Int =
        val v = integer()
        if v < BigInt(Int.MinValue) || v > BigInt(Int.MaxValue) then
            throw RangeException(v.toLong, "Int", Int.MinValue.toLong, Int.MaxValue.toLong)(using _frame)
        v.toInt
    end int

    def long(): Long =
        val v = integer()
        if v < BigInt(Long.MinValue) || v > BigInt(Long.MaxValue) then
            throw ParseException(Ion(), v.toString, "Long")(using _frame)
        v.toLong
    end long

    def float(): Float =
        double().toFloat

    def double(): Double =
        value match
            case FloatNum(v) => v
            case DecNum(v)   => v.toDouble
            case IntNum(v)   => v.toDouble
            case other       => mismatch("float", other)

    def boolean(): Boolean =
        value match
            case Bool(v) => v
            case other   => mismatch("bool", other)

    def short(): Short =
        val v = int()
        if v < Short.MinValue || v > Short.MaxValue then
            throw RangeException(v.toLong, "Short", Short.MinValue.toLong, Short.MaxValue.toLong)(using _frame)
        v.toShort
    end short

    def byte(): Byte =
        val v = int()
        if v < Byte.MinValue || v > Byte.MaxValue then
            throw RangeException(v.toLong, "Byte", Byte.MinValue.toLong, Byte.MaxValue.toLong)(using _frame)
        v.toByte
    end byte

    def char(): Char =
        val s = string()
        if s.length != 1 then
            throw TypeMismatchException(Seq.empty, "single character", s"string length ${s.length}")(using _frame)
        s.charAt(0)
    end char

    def isNil(): Boolean =
        value match
            case NullValue => true
            case _         => false

    def skip(): Unit = ()

    def mapStart(): Int         = objectStart()
    def mapEnd(): Unit          = objectEnd()
    def hasNextEntry(): Boolean = hasNextField()

    def bytes(): Span[Byte] =
        value match
            case Blob(v) => v
            case Str(v) =>
                try Span.from(java.util.Base64.getDecoder.decode(v))
                catch
                    case e: IllegalArgumentException =>
                        throw ParseException(Ion(), v, s"Base64 (${e.getMessage})")(using _frame)
            case other => mismatch("blob", other)
        end match
    end bytes

    def bigInt(): BigInt =
        integer()

    def bigDecimal(): BigDecimal =
        value match
            case DecNum(v) => v
            case IntNum(v) => BigDecimal(v)
            case FloatNum(v) =>
                if v.isNaN || v.isInfinite then mismatch("finite decimal", FloatNum(v))
                else BigDecimal(v)
            case other => mismatch("decimal", other)

    def instant(): java.time.Instant =
        val text =
            value match
                case Timestamp(v) => v
                case Str(v)       => v
                case Symbol(v)    => v
                case other        => mismatch("timestamp", other)
        try java.time.Instant.parse(text)
        catch
            case e: java.time.format.DateTimeParseException =>
                throw ParseException(Ion(), text, s"Instant (${e.getMessage})")(using _frame)
        end try
    end instant

    def duration(): java.time.Duration =
        val text = string()
        try java.time.Duration.parse(text)
        catch
            case e: java.time.format.DateTimeParseException =>
                throw ParseException(Ion(), text, s"Duration (${e.getMessage})")(using _frame)
        end try
    end duration

    override def captureValue(): Reader =
        val captured = new IonReader("", Maybe(value), Maybe(value), _frame)
        captured.resetLimits(maxDepth, maxCollectionSize)
        captured
    end captureValue

    override def release(): Unit =
        raw = ""
        parsed = Maybe.empty
        current = Maybe.empty
        stack = Nil
        parsedField = Maybe.empty
    end release

    private def integer(): BigInt =
        value match
            case IntNum(v) => v
            case other     => mismatch("int", other)

    private def value: IonValue =
        current.getOrElse {
            val root = parsed.getOrElse {
                val p = IonTextParser(raw, maxDepth, maxCollectionSize, _frame).parse()
                parsed = Maybe(p)
                raw = ""
                p
            }
            current = Maybe(root)
            root
        }
    end value

    private def mismatch(expected: String, actual: IonValue): Nothing =
        throw TypeMismatchException(Seq.empty, expected, actual.display)(using _frame)

end IonReader

object IonReader:
    def apply(input: Span[Byte])(using frame: Frame): IonReader =
        new IonReader(new String(input.toArrayUnsafe, StandardCharsets.UTF_8), Maybe.empty, Maybe.empty, frame)

    def apply(input: String)(using frame: Frame): IonReader =
        new IonReader(input, Maybe.empty, Maybe.empty, frame)
end IonReader

final private class IonTextParser(
    input: String,
    maxDepth: Int,
    maxCollectionSize: Int,
    frame: Frame
):
    import IonValue.*

    private var pos: Int = 0

    def parse(): IonValue =
        skipWhitespace()
        consumeVersionMarker()
        skipWhitespace()
        if pos >= input.length then error("Expected Ion value")
        val value = parseAnnotatedValue(0)
        skipWhitespace()
        if pos < input.length then error("Unexpected trailing content")
        value
    end parse

    private def parseAnnotatedValue(depth: Int): IonValue =
        skipWhitespace()
        @tailrec
        def loop(): IonValue =
            skipWhitespace()
            val start = pos
            parseAnnotationToken() match
                case Some((token, tokenStart, quoted)) =>
                    skipWhitespace()
                    if consume("::") then
                        validateAnnotationToken(token, tokenStart, quoted)
                        loop()
                    else
                        pos = start
                        parsePlainValue(depth)
                    end if
                case None =>
                    pos = start
                    parsePlainValue(depth)
            end match
        end loop
        loop()
    end parseAnnotatedValue

    private def parsePlainValue(depth: Int): IonValue =
        skipWhitespace()
        if pos >= input.length then error("Unexpected end of input")
        peek match
            case '{' =>
                if startsWith("{{") then parseLob()
                else parseStruct(depth)
            case '[' => parseList(depth)
            case '(' => parseSexp(depth)
            case '"' => Str(parseQuoted('"'))
            case '\'' =>
                if startsWith("'''") then Str(parseLongStringConcat())
                else Symbol(parseQuoted('\''))
            case _ => parseTokenValue()
        end match
    end parsePlainValue

    private def parseStruct(depth: Int): IonValue =
        checkDepth(depth)
        expect('{')
        skipWhitespace()
        val builder = Vector.newBuilder[(String, IonValue)]
        var count   = 0
        if consume('}') then StructVal(builder.result())
        else
            var done = false
            while !done do
                val name = parseFieldName()
                skipWhitespace()
                expect(':')
                val v = parseAnnotatedValue(depth + 1)
                builder += ((name, v))
                count += 1
                checkCollection(count)
                skipWhitespace()
                if consume(',') then
                    skipWhitespace()
                    if consume('}') then done = true
                else
                    expect('}')
                    done = true
                end if
            end while
            StructVal(builder.result())
        end if
    end parseStruct

    private def parseList(depth: Int): IonValue =
        checkDepth(depth)
        expect('[')
        parseSequence(']', depth)
    end parseList

    private def parseSexp(depth: Int): IonValue =
        checkDepth(depth)
        expect('(')
        val builder = Vector.newBuilder[IonValue]
        var count   = 0
        skipWhitespace()
        while !consume(')') do
            builder += parseAnnotatedValue(depth + 1)
            count += 1
            checkCollection(count)
            skipWhitespace()
            discard(consume(','))
            skipWhitespace()
        end while
        ListVal(builder.result())
    end parseSexp

    private def parseSequence(end: Char, depth: Int): IonValue =
        val builder = Vector.newBuilder[IonValue]
        var count   = 0
        skipWhitespace()
        if consume(end) then ListVal(builder.result())
        else
            var done = false
            while !done do
                builder += parseAnnotatedValue(depth + 1)
                count += 1
                checkCollection(count)
                skipWhitespace()
                if consume(',') then
                    skipWhitespace()
                    if consume(end) then done = true
                else
                    expect(end)
                    done = true
                end if
            end while
            ListVal(builder.result())
        end if
    end parseSequence

    private def parseLob(): IonValue =
        expect('{')
        expect('{')
        skipLobWhitespace()
        if startsWith("'''") || peekOption.contains('"') then
            val text =
                if startsWith("'''") then parseLongStringConcat()
                else parseQuoted('"')
            skipLobWhitespace()
            expect('}')
            expect('}')
            Blob(Span.from(text.getBytes(StandardCharsets.US_ASCII)))
        else
            val start = pos
            val end   = input.indexOf("}}", pos)
            if end < 0 then error("Unterminated blob")
            pos = end + 2
            val base64 = input.substring(start, end).filterNot(isIonWhitespace)
            try Blob(Span.from(java.util.Base64.getDecoder.decode(base64)))
            catch
                case e: IllegalArgumentException =>
                    throw ParseException(Ion(), base64, s"blob (${e.getMessage})", Nil, start)(using frame)
            end try
        end if
    end parseLob

    private def parseFieldName(): String =
        skipWhitespace()
        if pos >= input.length then error("Expected field name")
        if peek == '"' then parseQuoted('"')
        else if startsWith("'''") then parseLongStringConcat()
        else if peek == '\'' then parseQuoted('\'')
        else
            val start = pos
            while pos < input.length && !isIonWhitespace(input.charAt(pos)) && input.charAt(pos) != ':' do
                pos += 1
            if pos == start then error("Expected field name")
            input.substring(start, pos)
        end if
    end parseFieldName

    private def parseTokenValue(): IonValue =
        val start = pos
        while pos < input.length && !isValueDelimiter(input.charAt(pos)) do
            pos += 1
        if pos == start then error("Expected value")
        val token = input.substring(start, pos)
        val lower = token.toLowerCase(java.util.Locale.ROOT)
        lower match
            case t if isNullToken(t)             => NullValue
            case t if t.startsWith("null.")      => invalidToken(start, s"Invalid typed null '$token'")
            case "true"                          => Bool(true)
            case "false"                         => Bool(false)
            case "nan"                           => FloatNum(Double.NaN)
            case "+inf"                          => FloatNum(Double.PositiveInfinity)
            case "-inf"                          => FloatNum(Double.NegativeInfinity)
            case _ if isTimestampToken(token)    => Timestamp(token)
            case _ if isNumericTokenStart(token) => parseNumber(token, start)
            case _                               => Symbol(token)
        end match
    end parseTokenValue

    private def parseNumber(token: String, start: Int): IonValue =
        if !isValidNumberToken(token) then
            invalidToken(start, s"Invalid numeric token '$token'")
        val cleaned = token.replace("_", "")
        val lower   = cleaned.toLowerCase(java.util.Locale.ROOT)
        try
            if isRadixIntegerToken(lower) then IntNum(parseBigInt(cleaned))
            else if lower.contains("e") then FloatNum(cleaned.toDouble)
            else if lower.contains("d") || lower.contains(".") then
                DecNum(BigDecimal(cleaned.replace('d', 'E').replace('D', 'E')))
            else IntNum(parseBigInt(cleaned))
        catch
            case _: NumberFormatException =>
                invalidToken(start, s"Invalid numeric token '$token'")
        end try
    end parseNumber

    private def parseBigInt(cleaned: String): BigInt =
        val sign =
            if cleaned.startsWith("-") then -1
            else 1
        val body =
            if cleaned.startsWith("-") || cleaned.startsWith("+") then cleaned.substring(1)
            else cleaned
        val lower = body.toLowerCase(java.util.Locale.ROOT)
        val parsed =
            if lower.startsWith("0x") then BigInt(lower.drop(2), 16)
            else if lower.startsWith("0b") then BigInt(lower.drop(2), 2)
            else BigInt(body)
        if sign < 0 then -parsed else parsed
    end parseBigInt

    private def parseAnnotationToken(): Option[(String, Int, Boolean)] =
        skipWhitespace()
        if pos >= input.length then None
        else if startsWith("'''") then None
        else if peek == '\'' then
            val start = pos
            Some((parseQuoted('\''), start, true))
        else if isIdentifierStart(peek) || peek == '$' then
            val start = pos
            while pos < input.length && !isValueDelimiter(input.charAt(pos)) && input.charAt(pos) != ':' do
                pos += 1
            if pos > start then Some((input.substring(start, pos), start, false))
            else None
        else None
        end if
    end parseAnnotationToken

    private def validateAnnotationToken(token: String, start: Int, quoted: Boolean): Unit =
        val lower = token.toLowerCase(java.util.Locale.ROOT)
        if !quoted then
            if isNullToken(lower) ||
                lower == "true" ||
                lower == "false" ||
                lower == "nan"
            then invalidToken(start, s"Invalid annotation token '$token'")
            end if
        end if
    end validateAnnotationToken

    private def isRadixIntegerToken(token: String): Boolean =
        val start =
            if token.startsWith("-") then 1
            else 0
        token.startsWith("0x", start) || token.startsWith("0b", start)
    end isRadixIntegerToken

    private def isValidNumberToken(token: String): Boolean =
        if token.isEmpty then false
        else
            val body =
                if token.charAt(0) == '-' then token.substring(1)
                else if token.charAt(0) == '+' then ""
                else token
            if body.isEmpty then false
            else
                val lower = body.toLowerCase(java.util.Locale.ROOT)
                if lower.startsWith("0x") then validDigits(body.substring(2), isHexDigit)
                else if lower.startsWith("0b") then validDigits(body.substring(2), isBinaryDigit)
                else if containsExponent(body, 'e') || containsExponent(body, 'd') || body.indexOf('.') >= 0 then
                    validRealBody(body)
                else validDecimalDigits(body, allowLeadingZeros = false)
                end if
            end if
        end if
    end isValidNumberToken

    private def validRealBody(body: String): Boolean =
        val eIndex = exponentIndex(body, 'e')
        val dIndex = exponentIndex(body, 'd')
        if eIndex >= 0 && dIndex >= 0 then false
        else
            val expIndex = math.max(eIndex, dIndex)
            val mantissa =
                if expIndex >= 0 then body.substring(0, expIndex)
                else body
            val exponentOk =
                if expIndex < 0 then true
                else
                    val exponent = body.substring(expIndex + 1)
                    val digits =
                        if exponent.startsWith("+") || exponent.startsWith("-") then exponent.substring(1)
                        else exponent
                    validDigits(digits, isDecimalDigit)
            val dotIndex = mantissa.indexOf('.')
            val mantissaOk =
                if dotIndex >= 0 then
                    if mantissa.indexOf('.', dotIndex + 1) >= 0 then false
                    else
                        val whole = mantissa.substring(0, dotIndex)
                        val frac  = mantissa.substring(dotIndex + 1)
                        validDecimalDigits(whole, allowLeadingZeros = false) &&
                        (frac.isEmpty || validDigits(frac, isDecimalDigit))
                else validDecimalDigits(mantissa, allowLeadingZeros = false)
            mantissaOk && exponentOk
        end if
    end validRealBody

    private def containsExponent(body: String, marker: Char): Boolean =
        exponentIndex(body, marker) >= 0

    private def exponentIndex(body: String, marker: Char): Int =
        val lower = marker.toLower
        var index = -1
        var i     = 0
        while i < body.length do
            val c = body.charAt(i).toLower
            if c == lower then
                if index >= 0 then return -2
                index = i
            i += 1
        end while
        if index == -2 then -1 else index
    end exponentIndex

    private def validDecimalDigits(text: String, allowLeadingZeros: Boolean): Boolean =
        validDigits(text, isDecimalDigit) &&
            (allowLeadingZeros || {
                val cleaned = text.replace("_", "")
                cleaned.length == 1 || cleaned.charAt(0) != '0'
            })
    end validDecimalDigits

    private def validDigits(text: String, validDigit: Char => Boolean): Boolean =
        if text.isEmpty then false
        else
            var i                  = 0
            var ok                 = true
            var previousDigit      = false
            var previousUnderscore = false
            while ok && i < text.length do
                val c = text.charAt(i)
                if validDigit(c) then
                    previousDigit = true
                    previousUnderscore = false
                else if c == '_' then
                    if !previousDigit then ok = false
                    else
                        previousDigit = false
                        previousUnderscore = true
                    end if
                else ok = false
                end if
                i += 1
            end while
            ok && !previousUnderscore
        end if
    end validDigits

    private def isDecimalDigit(c: Char): Boolean =
        c >= '0' && c <= '9'

    private def isBinaryDigit(c: Char): Boolean =
        c == '0' || c == '1'

    private def isHexDigit(c: Char): Boolean =
        (c >= '0' && c <= '9') ||
            (c >= 'a' && c <= 'f') ||
            (c >= 'A' && c <= 'F')

    private def parseLongStringConcat(): String =
        val sb       = new StringBuilder
        var continue = true
        while continue do
            sb.append(parseLongString())
            val saved = pos
            skipWhitespace()
            if !startsWith("'''") then
                pos = saved
                continue = false
        end while
        sb.toString
    end parseLongStringConcat

    private def parseLongString(): String =
        expect('\'')
        expect('\'')
        expect('\'')
        val sb = new StringBuilder
        while !startsWith("'''") do
            if pos >= input.length then error("Unterminated long string")
            val c = input.charAt(pos)
            if c == '\\' then
                pos += 1
                appendEscape(sb)
            else
                sb.append(c)
                pos += 1
            end if
        end while
        expect('\'')
        expect('\'')
        expect('\'')
        sb.toString
    end parseLongString

    private def parseQuoted(quote: Char): String =
        expect(quote)
        val sb = new StringBuilder
        while pos < input.length && peek != quote do
            val c = input.charAt(pos)
            if c == '\\' then
                pos += 1
                appendEscape(sb)
            else
                sb.append(c)
                pos += 1
            end if
        end while
        if pos >= input.length then error("Unterminated quoted value")
        expect(quote)
        sb.toString
    end parseQuoted

    private def appendEscape(sb: StringBuilder): Unit =
        if pos >= input.length then error("Unexpected end of escape")
        input.charAt(pos) match
            case '0'  => sb.append('\u0000'); pos += 1
            case 'a'  => sb.append('\u0007'); pos += 1
            case 'b'  => sb.append('\b'); pos += 1
            case 't'  => sb.append('\t'); pos += 1
            case 'n'  => sb.append('\n'); pos += 1
            case 'f'  => sb.append('\f'); pos += 1
            case 'r'  => sb.append('\r'); pos += 1
            case 'v'  => sb.append('\u000b'); pos += 1
            case '"'  => sb.append('"'); pos += 1
            case '\'' => sb.append('\''); pos += 1
            case '?'  => sb.append('?'); pos += 1
            case '\\' => sb.append('\\'); pos += 1
            case '/'  => sb.append('/'); pos += 1
            case 'x' =>
                pos += 1
                sb.append(readHex(2).toChar)
            case 'u' =>
                pos += 1
                sb.append(readHex(4).toChar)
            case 'U' =>
                pos += 1
                sb.appendAll(Character.toChars(readHex(8)))
            case '\n' =>
                pos += 1
            case '\r' =>
                pos += 1
                if pos < input.length && input.charAt(pos) == '\n' then pos += 1
            case other =>
                error(s"Invalid escape \\$other")
        end match
    end appendEscape

    private def readHex(digits: Int): Int =
        if pos + digits > input.length then error("Truncated hex escape")
        var value = 0
        var i     = 0
        while i < digits do
            value = (value << 4) | hex(input.charAt(pos + i))
            i += 1
        pos += digits
        value
    end readHex

    private def hex(c: Char): Int =
        if c >= '0' && c <= '9' then c - '0'
        else if c >= 'a' && c <= 'f' then c - 'a' + 10
        else if c >= 'A' && c <= 'F' then c - 'A' + 10
        else error(s"Invalid hex digit '$c'")

    private def consumeVersionMarker(): Unit =
        val saved = pos
        if startsWith("$ion_1_0") then
            pos += "$ion_1_0".length
            if pos >= input.length || isValueDelimiter(input.charAt(pos)) then ()
            else pos = saved
        end if
    end consumeVersionMarker

    private def skipWhitespace(): Unit =
        var continue = true
        while continue && pos < input.length do
            if isIonWhitespace(input.charAt(pos)) then pos += 1
            else if startsWith("//") then
                pos += 2
                while pos < input.length && input.charAt(pos) != '\n' && input.charAt(pos) != '\r' do
                    pos += 1
            else if startsWith("/*") then
                val end = input.indexOf("*/", pos + 2)
                if end < 0 then error("Unterminated block comment")
                pos = end + 2
            else continue = false
        end while
    end skipWhitespace

    private def skipLobWhitespace(): Unit =
        while pos < input.length && isIonWhitespace(input.charAt(pos)) do
            pos += 1

    private def checkDepth(depth: Int): Unit =
        if depth + 1 > maxDepth then
            throw LimitExceededException("Nesting depth", depth + 1, maxDepth)(using frame)

    private def checkCollection(count: Int): Unit =
        if count > maxCollectionSize then
            throw LimitExceededException("Collection size", count, maxCollectionSize)(using frame)

    private def isTimestampToken(token: String): Boolean =
        token.length >= 10 &&
            token.charAt(4) == '-' &&
            token.charAt(7) == '-' &&
            token.charAt(0).isDigit &&
            token.charAt(1).isDigit &&
            token.charAt(2).isDigit &&
            token.charAt(3).isDigit

    private def isNullToken(token: String): Boolean =
        token == "null" || isTypedNullToken(token)

    private def isTypedNullToken(token: String): Boolean =
        token.length > 5 &&
            token.charAt(0) == 'n' &&
            token.charAt(1) == 'u' &&
            token.charAt(2) == 'l' &&
            token.charAt(3) == 'l' &&
            token.charAt(4) == '.' &&
            isNullTypeSuffix(token, 5)
    end isTypedNullToken

    private def isNullTypeSuffix(token: String, offset: Int): Boolean =
        token.length - offset match
            case 3 => token.startsWith("int", offset)
            case 4 => token.startsWith("null", offset) || token.startsWith("bool", offset) || token.startsWith("blob", offset) ||
                token.startsWith("clob", offset) || token.startsWith("list", offset) || token.startsWith("sexp", offset)
            case 5 => token.startsWith("float", offset)
            case 6 => token.startsWith("string", offset) || token.startsWith("symbol", offset) || token.startsWith("struct", offset)
            case 7 => token.startsWith("decimal", offset)
            case 9 => token.startsWith("timestamp", offset)
            case _ => false
    end isNullTypeSuffix

    private def isNumericTokenStart(token: String): Boolean =
        token.nonEmpty && (
            token.charAt(0).isDigit ||
                ((token.charAt(0) == '-' || token.charAt(0) == '+') && token.length > 1 && token.charAt(1).isDigit)
        )

    private def isValueDelimiter(c: Char): Boolean =
        isIonWhitespace(c) || c == ',' || c == ']' || c == '}' || c == ')'

    private def isIdentifierStart(c: Char): Boolean =
        (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$'

    private def isIonWhitespace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\u000b'

    private def consume(s: String): Boolean =
        if startsWith(s) then
            pos += s.length
            true
        else false

    private def consume(c: Char): Boolean =
        if pos < input.length && input.charAt(pos) == c then
            pos += 1
            true
        else false

    private def expect(c: Char): Unit =
        if pos >= input.length then error(s"Expected '$c' but reached end of input")
        if input.charAt(pos) != c then error(s"Expected '$c', got '${input.charAt(pos)}'")
        pos += 1
    end expect

    private def startsWith(s: String): Boolean =
        input.startsWith(s, pos)

    private def peek: Char =
        if pos >= input.length then error("Unexpected end of input")
        input.charAt(pos)

    private def peekOption: Option[Char] =
        if pos >= input.length then None else Some(input.charAt(pos))

    private def error(message: String): Nothing =
        val start   = math.max(0, pos - 30)
        val end     = math.min(input.length, pos + 30)
        val snippet = input.substring(start, end)
        throw ParseException(Ion(), snippet, message, Nil, pos)(using frame)
    end error

    private def invalidToken(start: Int, message: String): Nothing =
        pos = start
        error(message)
    end invalidToken
end IonTextParser
