package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.Codec.Reader
import kyo.Maybe
import kyo.Span
import scala.annotation.tailrec

// Parses UTF-8 byte input directly, avoiding String intermediary on the decode path.
final class JsonReader private (private var input: Span[Byte], private var _frame: Frame) extends Reader:
    override def frame: Frame = _frame
    private var pos           = 0

    // Reusable field values array — avoids per-decode allocation in macro-generated code.
    // Only reused for top-level (depth 0) object. Nested objects allocate fresh arrays.
    private var fieldValues: Array[AnyRef] = new Array[AnyRef](16)
    private var fieldDepth: Int            = 0

    // Field matching state: start/len of last parsed field name (for zero-alloc matching)
    private var lastFieldStart: Int = 0
    private var lastFieldLen: Int   = 0

    def objectStart(): Int =
        checkDepth()
        skipWhitespace()
        expect('{')
        skipWhitespace()
        if pos < input.size && peek() == '}' then 0 else -1
    end objectStart

    def objectEnd(): Unit =
        decrementDepth()
        skipWhitespace()
        expect('}')
    end objectEnd

    def arrayStart(): Int =
        checkDepth()
        skipWhitespace()
        expect('[')
        skipWhitespace()
        if pos < input.size && peek() == ']' then 0 else -1
    end arrayStart

    def arrayEnd(): Unit =
        decrementDepth()
        skipWhitespace()
        expect(']')
    end arrayEnd

    def field(): String =
        skipWhitespace()
        val name = string()
        skipWhitespace()
        expect(':')
        name
    end field

    /** Parse field name and store position/length for zero-alloc matching via matchField. */
    override def fieldParse(): Unit =
        skipWhitespace()
        expectByte('"')
        lastFieldStart = pos
        // Fast scan for closing quote — field names are typically ASCII with no escapes
        @tailrec def scanClose(): Unit =
            if pos < input.size && input(pos) != '"' then
                pos += 1
                scanClose()
        scanClose()
        if pos >= input.size then error("Unterminated field name")
        lastFieldLen = pos - lastFieldStart
        pos += 1 // skip closing quote
        skipWhitespace()
        expectByte(':')
    end fieldParse

    /** Compare last parsed field name bytes against pre-encoded name bytes. */
    override def matchField(nameBytes: Array[Byte]): Boolean =
        if nameBytes.length != lastFieldLen then false
        else
            @tailrec def loop(i: Int): Boolean =
                if i >= lastFieldLen then true
                else if input(lastFieldStart + i) != nameBytes(i) then false
                else loop(i + 1)
            loop(0)
    end matchField

    /** Initialize reusable field values array for n fields. Returns the array. Only reuses the pooled array at depth 0; nested objects
      * allocate fresh.
      */
    override def initFields(n: Int): Array[AnyRef] =
        fieldDepth += 1
        if fieldDepth == 1 then
            // Top-level: reuse pooled array
            if n > fieldValues.length then
                fieldValues = new Array[AnyRef](n)
            else
                java.util.Arrays.fill(fieldValues, 0, n, null)
            end if
            fieldValues
        else
            // Nested: allocate fresh to avoid clobbering outer level
            new Array[AnyRef](n)
        end if
    end initFields

    /** Clear field values and decrement depth. At depth 0, clears refs to avoid leaks. */
    override def clearFields(n: Int): Unit =
        if fieldDepth == 1 then
            // Top-level: clear refs so pooled reader doesn't retain objects
            java.util.Arrays.fill(fieldValues, 0, n, null)
        fieldDepth -= 1
    end clearFields

    def hasNextField(): Boolean =
        skipWhitespace()
        if pos >= input.size then false
        else
            peek() match
                case '}' => false
                case ',' => advance(); skipWhitespace(); true
                case _   => true // first field, no comma
        end if
    end hasNextField

    def hasNextElement(): Boolean =
        skipWhitespace()
        if pos >= input.size then false
        else
            peek() match
                case ']' => false
                case ',' => advance(); skipWhitespace(); true
                case _   => true // first element, no comma
        end if
    end hasNextElement

    def string(): String =
        skipWhitespace()
        if pos >= input.size || input(pos) != '"' then error("Expected '\"'")
        pos += 1
        val start = pos
        // Fast scan: no escapes?
        @tailrec def fastScan(): Unit =
            if pos < input.size && input(pos) != '"' && input(pos) != '\\' then
                pos += 1
                fastScan()
        fastScan()
        if pos < input.size && input(pos) == '"' then
            // No escapes — bulk convert from underlying array
            val s = new String(input.toArrayUnsafe, start, pos - start, StandardCharsets.UTF_8)
            pos += 1
            s
        else if pos < input.size && input(pos) == '\\' then
            // Has escapes — fall back to StringBuilder path
            pos = start
            readQuotedStringWithEscapes()
        else
            error("Unterminated string")
        end if
    end string

    def int(): Int =
        skipWhitespace()
        val start = pos
        val neg   = pos < input.size && input(pos) == '-'
        if neg then pos += 1
        if pos >= input.size || input(pos) < '0' || input(pos) > '9' then error("Expected number")
        @tailrec def parseDigits(result: Int, overflow: Boolean): (Int, Boolean) =
            if pos < input.size && input(pos) >= '0' && input(pos) <= '9' then
                val digit       = input(pos) - '0'
                val newOverflow = overflow || result > (Int.MaxValue - digit) / 10
                val newResult   = if newOverflow then result else result * 10 + digit
                pos += 1
                parseDigits(newResult, newOverflow)
            else
                (result, overflow)
        val (result, overflow) = parseDigits(0, false)
        // If followed by '.', 'e', or 'E' — not a valid JSON integer, fall back
        if pos < input.size && (input(pos) == '.' || input(pos) == 'e' || input(pos) == 'E') then
            pos = start
            parseNumberStr("Int")(_.toInt)
        else if overflow then
            pos = start
            parseNumberStr("Int")(_.toInt)
        else if neg then -result
        else result
        end if
    end int

    def long(): Long =
        skipWhitespace()
        val start = pos
        val neg   = pos < input.size && input(pos) == '-'
        if neg then pos += 1
        if pos >= input.size || input(pos) < '0' || input(pos) > '9' then error("Expected number")
        @tailrec def parseDigits(result: Long, overflow: Boolean): (Long, Boolean) =
            if pos < input.size && input(pos) >= '0' && input(pos) <= '9' then
                val digit       = input(pos) - '0'
                val newOverflow = overflow || result > (Long.MaxValue - digit) / 10
                val newResult   = if newOverflow then result else result * 10 + digit
                pos += 1
                parseDigits(newResult, newOverflow)
            else
                (result, overflow)
        val (result, overflow) = parseDigits(0L, false)
        // If followed by '.', 'e', or 'E' — not a valid JSON integer, fall back
        if pos < input.size && (input(pos) == '.' || input(pos) == 'e' || input(pos) == 'E') then
            pos = start
            parseNumberStr("Long")(_.toLong)
        else if overflow then
            pos = start
            parseNumberStr("Long")(_.toLong)
        else if neg then -result
        else result
        end if
    end long

    def float(): Float =
        skipWhitespace()
        if pos < input.size && input(pos) == '"' then
            val s = string()
            if s == "NaN" then Float.NaN
            else if s == "Infinity" then Float.PositiveInfinity
            else if s == "-Infinity" then Float.NegativeInfinity
            else error(s"Invalid Float value: '$s'")
            end if
        else
            parseNumberStr("Float")(_.toFloat)
        end if
    end float

    def double(): Double =
        skipWhitespace()
        // Check for quoted special values: "NaN", "Infinity", "-Infinity"
        if pos < input.size && input(pos) == '"' then
            val s = string()
            if s == "NaN" then Double.NaN
            else if s == "Infinity" then Double.PositiveInfinity
            else if s == "-Infinity" then Double.NegativeInfinity
            else error(s"Invalid Double value: '$s'")
            end if
        else
            // Try integer fast path first
            val start = pos
            val neg   = pos < input.size && input(pos) == '-'
            if neg then pos += 1
            if pos < input.size && input(pos) >= '0' && input(pos) <= '9' then
                @tailrec def parseDigits(result: Long, overflow: Boolean): (Long, Boolean) =
                    if pos < input.size && input(pos) >= '0' && input(pos) <= '9' then
                        val digit       = input(pos) - '0'
                        val newOverflow = overflow || result > (Long.MaxValue - digit) / 10
                        val newResult   = if newOverflow then result else result * 10 + digit
                        pos += 1
                        parseDigits(newResult, newOverflow)
                    else
                        (result, overflow)
                val (result, overflow) = parseDigits(0L, false)
                // Check if it's a pure integer (no decimal point or exponent)
                if !overflow && (pos >= input.size || (input(pos) != '.' && input(pos) != 'e' && input(pos) != 'E')) then
                    val v = if neg then -result else result
                    v.toDouble
                else
                    // Fall back to general path
                    pos = start
                    parseNumberStr("Double")(_.toDouble)
                end if
            else
                // Fall back to general path
                pos = start
                parseNumberStr("Double")(_.toDouble)
            end if
        end if
    end double

    def boolean(): Boolean =
        skipWhitespace()
        if pos + 4 <= input.size &&
            input(pos) == 't' &&
            input(pos + 1) == 'r' &&
            input(pos + 2) == 'u' &&
            input(pos + 3) == 'e'
        then
            pos += 4; true
        else if pos + 5 <= input.size &&
            input(pos) == 'f' &&
            input(pos + 1) == 'a' &&
            input(pos + 2) == 'l' &&
            input(pos + 3) == 's' &&
            input(pos + 4) == 'e'
        then
            pos += 5; false
        else error("Expected boolean")
        end if
    end boolean

    def short(): Short =
        skipWhitespace()
        parseNumberStr("Short")(_.toShort)

    def byte(): Byte =
        skipWhitespace()
        parseNumberStr("Byte")(_.toByte)

    def char(): Char =
        skipWhitespace()
        val s = string()
        if s.length != 1 then error(s"Expected single character, got string of length ${s.length}")
        s.charAt(0)
    end char

    def isNil(): Boolean =
        skipWhitespace()
        if pos + 4 <= input.size &&
            input(pos) == 'n' &&
            input(pos + 1) == 'u' &&
            input(pos + 2) == 'l' &&
            input(pos + 3) == 'l'
        then
            pos += 4; true
        else false
        end if
    end isNil

    def skip(): Unit =
        skipWhitespace()
        if pos >= input.size then error("Unexpected end of input")
        peek() match
            case '"'       => discard(string())
            case '{'       => skipObject()
            case '['       => skipArray()
            case 't' | 'f' => discard(boolean())
            case 'n' =>
                if !(pos + 4 <= input.size &&
                        input(pos) == 'n' &&
                        input(pos + 1) == 'u' &&
                        input(pos + 2) == 'l' &&
                        input(pos + 3) == 'l')
                then error("Expected 'null'")
                end if
                pos += 4
            case _ => discard(readNumber())
        end match
    end skip

    def mapStart(): Int         = objectStart()
    def mapEnd(): Unit          = objectEnd()
    def hasNextEntry(): Boolean = hasNextField()

    def bytes(): Span[Byte] =
        val s = string()
        try
            val arr = java.util.Base64.getDecoder.decode(s)
            Span.from(arr)
        catch
            case e: IllegalArgumentException =>
                error(s"Invalid Base64: ${e.getMessage}")
        end try
    end bytes

    def bigInt(): BigInt =
        val s = string()
        try BigInt(s)
        catch
            case _: NumberFormatException =>
                error(s"Invalid BigInt value: '$s'")
        end try
    end bigInt

    def bigDecimal(): BigDecimal =
        val s = string()
        try BigDecimal(s)
        catch
            case _: NumberFormatException =>
                error(s"Invalid BigDecimal value: '$s'")
        end try
    end bigDecimal

    def instant(): java.time.Instant =
        val s = string()
        try java.time.Instant.parse(s)
        catch
            case e: java.time.format.DateTimeParseException =>
                error(s"Invalid Instant value: '$s' (${e.getMessage})")
        end try
    end instant

    def duration(): java.time.Duration =
        val s = string()
        try java.time.Duration.parse(s)
        catch
            case e: java.time.format.DateTimeParseException =>
                error(s"Invalid Duration value: '$s' (${e.getMessage})")
        end try
    end duration

    // Internal parsing methods

    /** Read a quoted string when we know there are escapes (backslash found during fast scan). */
    private def readQuotedStringWithEscapes(): String =
        val sb = new StringBuilder
        @tailrec def loop(): Unit =
            if pos < input.size && input(pos) != '"' then
                if input(pos) == '\\' then
                    pos += 1
                    if pos >= input.size then error("Unexpected end of input in string escape")
                    input(pos) match
                        case '"'  => sb.append('"'); pos += 1
                        case '\\' => sb.append('\\'); pos += 1
                        case '/'  => sb.append('/'); pos += 1
                        case 'n'  => sb.append('\n'); pos += 1
                        case 'r'  => sb.append('\r'); pos += 1
                        case 't'  => sb.append('\t'); pos += 1
                        case 'b'  => sb.append('\b'); pos += 1
                        case 'f'  => sb.append('\f'); pos += 1
                        case 'u' =>
                            pos += 1
                            if pos + 4 > input.size then error("Unexpected end of input in unicode escape")
                            val cp = parseHex4(pos)
                            pos += 4
                            if cp >= 0xd800 && cp <= 0xdbff then
                                // High surrogate — must be followed by \uDC00–\uDFFF
                                if pos + 6 > input.size || input(pos) != '\\' || input(pos + 1) != 'u' then
                                    error(s"Lone high surrogate: \\u${Integer.toHexString(cp)}")
                                pos += 2
                                val lo = parseHex4(pos)
                                pos += 4
                                if lo < 0xdc00 || lo > 0xdfff then
                                    error(s"Expected low surrogate after \\u${Integer.toHexString(cp)}, got \\u${Integer.toHexString(lo)}")
                                sb.appendAll(Character.toChars(Character.toCodePoint(cp.toChar, lo.toChar)))
                            else if cp >= 0xdc00 && cp <= 0xdfff then
                                error(s"Lone low surrogate: \\u${Integer.toHexString(cp)}")
                            else
                                sb.append(cp.toChar)
                            end if
                        case c => sb.append(c.toChar); pos += 1
                    end match
                else
                    // Decode UTF-8 byte(s) to char(s)
                    val b = input(pos) & 0xff
                    if b < 0x80 then
                        sb.append(b.toChar)
                        pos += 1
                    else if (b & 0xe0) == 0xc0 then
                        if pos + 2 > input.size then error("Truncated UTF-8 sequence")
                        val cp = ((b & 0x1f) << 6) | (input(pos + 1) & 0x3f)
                        sb.append(cp.toChar)
                        pos += 2
                    else if (b & 0xf0) == 0xe0 then
                        if pos + 3 > input.size then error("Truncated UTF-8 sequence")
                        val cp = ((b & 0x0f) << 12) | ((input(pos + 1) & 0x3f) << 6) | (input(pos + 2) & 0x3f)
                        sb.append(cp.toChar)
                        pos += 3
                    else if (b & 0xf8) == 0xf0 then
                        if pos + 4 > input.size then error("Truncated UTF-8 sequence")
                        val cp = ((b & 0x07) << 18) | ((input(pos + 1) & 0x3f) << 12) |
                            ((input(pos + 2) & 0x3f) << 6) | (input(pos + 3) & 0x3f)
                        sb.appendAll(Character.toChars(cp))
                        pos += 4
                    else
                        error(s"Invalid UTF-8 byte: 0x${(b & 0xff).toHexString}")
                    end if
                end if
                loop()
        loop()
        if pos >= input.size then error("Unterminated string")
        pos += 1 // skip closing quote
        sb.toString
    end readQuotedStringWithEscapes

    private def parseHex4(p: Int): Int =
        (hexDigit(input(p)) << 12) | (hexDigit(input(p + 1)) << 8) |
            (hexDigit(input(p + 2)) << 4) | hexDigit(input(p + 3))

    private def hexDigit(b: Byte): Int =
        if b >= '0' && b <= '9' then b - '0'
        else if b >= 'a' && b <= 'f' then b - 'a' + 10
        else if b >= 'A' && b <= 'F' then b - 'A' + 10
        else error(s"Invalid hex digit in unicode escape: '${b.toChar}'")

    private def readNumber(): String =
        val start = pos
        if pos < input.size && peek() == '-' then advance()
        @tailrec def loop(): Unit =
            if pos < input.size then
                val b = peek()
                if (b >= '0' && b <= '9') || b == '.' || b == 'e' || b == 'E' || b == '+' || b == '-' then
                    advance()
                    loop()
        loop()
        if pos == start then error("Expected number")
        // Number bytes are always ASCII, copy only the needed range
        val len = pos - start
        val arr = new Array[Byte](len)
        @tailrec def copyBytes(i: Int): Unit =
            if i < len then
                arr(i) = input(start + i)
                copyBytes(i + 1)
        copyBytes(0)
        new String(arr, StandardCharsets.US_ASCII)
    end readNumber

    /** Parse number as String and convert, used for types where direct parsing is complex (short, byte, float). */
    private def parseNumberStr[A](expected: String)(convert: String => A): A =
        val s = readNumber()
        try convert(s)
        catch
            case _: NumberFormatException =>
                error(s"Invalid $expected value: '$s'")
        end try
    end parseNumberStr

    private def skipObject(): Unit =
        expect('{')
        @tailrec def loop(depth: Int): Unit =
            if depth > 0 then
                if pos >= input.size then error("Unterminated object")
                peek() match
                    case '{' => advance(); loop(depth + 1)
                    case '}' => advance(); loop(depth - 1)
                    case '"' => skipQuotedString(); loop(depth)
                    case _   => advance(); loop(depth)
                end match
        loop(1)
    end skipObject

    private def skipArray(): Unit =
        expect('[')
        @tailrec def loop(depth: Int): Unit =
            if depth > 0 then
                if pos >= input.size then error("Unterminated array")
                peek() match
                    case '[' => advance(); loop(depth + 1)
                    case ']' => advance(); loop(depth - 1)
                    case '"' => skipQuotedString(); loop(depth)
                    case _   => advance(); loop(depth)
                end match
        loop(1)
    end skipArray

    /** Skip a quoted string without building a result. Handles escape sequences. */
    private def skipQuotedString(): Unit =
        pos += 1 // skip opening quote
        @tailrec def loop(): Unit =
            if pos < input.size && input(pos) != '"' then
                if input(pos) == '\\' then
                    pos += 2 // skip escape char + escaped char
                else
                    pos += 1
                end if
                loop()
        loop()
        if pos >= input.size then error("Unterminated string")
        pos += 1 // skip closing quote
    end skipQuotedString

    // Fast path: check if next byte is not whitespace before entering the loop
    private def skipWhitespace(): Unit =
        if !(pos < input.size && input(pos) > ' ') then
            @tailrec def loop(): Unit =
                if pos < input.size then
                    val b = input(pos)
                    if b == ' ' || b == '\t' || b == '\n' || b == '\r' then
                        pos += 1
                        loop()
            loop()
    end skipWhitespace

    private inline def peek(): Byte =
        if pos >= input.size then error("Unexpected end of input")
        input(pos)

    private inline def advance(): Unit = pos += 1

    private def expect(c: Byte): Unit =
        if pos >= input.size then error(s"Expected '${c.toChar}' but reached end of input")
        if peek() != c then error(s"Expected '${c.toChar}', got '${peek().toChar}'")
        advance()
    end expect

    private def expect(c: Char): Unit = expect(c.toByte)

    /** Expect a specific byte without the boxing overhead of Char conversion. */
    private inline def expectByte(b: Byte): Unit =
        if pos >= input.size then error(s"Expected '${b.toChar}' but reached end of input")
        if input(pos) != b then error(s"Expected '${b.toChar}', got '${input(pos).toChar}'")
        pos += 1
    end expectByte

    private def error(msg: String): Nothing =
        given Frame       = _frame
        val contextRadius = 30
        val start         = math.max(0, pos - contextRadius)
        val end           = math.min(input.size, pos + contextRadius)
        val len           = end - start
        val arr           = new Array[Byte](len)
        @tailrec def copyBytes(i: Int): Unit =
            if i < len then
                arr(i) = input(start + i)
                copyBytes(i + 1)
        copyBytes(0)
        val snippet        = new String(arr, StandardCharsets.UTF_8)
        val caretPos       = pos - start
        val caret          = " " * caretPos + "^"
        val contextSnippet = s"$snippet\n  $caret"
        throw ParseException(Json(), contextSnippet, msg, Nil, pos)
    end error

    // Reset state for reuse from pool
    private def reset(newInput: Span[Byte], newFrame: Frame): Unit =
        this.input = newInput
        this._frame = newFrame
        this.pos = 0
        this.lastFieldStart = 0
        this.lastFieldLen = 0
        this.fieldDepth = 0
    end reset

    override def captureValue(): Reader =
        skipWhitespace()
        val start = pos
        skip()
        val end = pos
        new JsonReader(input.slice(start, end), _frame)
    end captureValue

    // Release this reader back to the thread-local pool
    override def release(): Unit =
        this.input = Span.empty[Byte]
        this.pos = 0
        this.lastFieldStart = 0
        this.lastFieldLen = 0
        this.fieldDepth = 0
        JsonReader.cache.set(this)
    end release

    // Package-private: extract a raw JSON substring for a named field in the current object.
    // Returns the raw JSON bytes as a String for the field value.
    private[kyo] def extractField(fieldName: String): String =
        skipWhitespace()
        expect('{')
        skipWhitespace()
        @tailrec def loop(): String =
            if pos < input.size && peek() != '}' then
                val name = field() // reads quoted string + colon
                skipWhitespace()
                if name == fieldName then
                    val start = pos
                    skip()
                    val len = pos - start
                    val arr = new Array[Byte](len)
                    @tailrec def copyBytes(j: Int): Unit =
                        if j < len then
                            arr(j) = input(start + j)
                            copyBytes(j + 1)
                    copyBytes(0)
                    new String(arr, StandardCharsets.UTF_8)
                else
                    skip()
                    skipWhitespace()
                    if pos < input.size && peek() == ',' then
                        advance()
                        skipWhitespace()
                    loop()
                end if
            else
                error(s"Field '$fieldName' not found in JSON object")
        loop()
    end extractField

end JsonReader

object JsonReader:
    private[internal] val cache = new ThreadLocal[JsonReader]

    /** Create a JsonReader from Span[Byte] (primary path - zero copy from network). Pooled. */
    def apply(input: Span[Byte])(using f: Frame): JsonReader =
        val cached = Maybe(cache.get()) // ThreadLocal.get() returns JVM null when absent
        cached match
            case Maybe.Present(r) =>
                cache.set(null) // Clear pool slot (Java API requires null)
                r.reset(input, f)
                r
            case _ => new JsonReader(input, f)
        end match
    end apply

    /** Create a JsonReader from a String (backward-compatible convenience). */
    def apply(input: String)(using Frame): JsonReader =
        apply(Span.from(input.getBytes(StandardCharsets.UTF_8)))

    /** Release a reader back to the pool. Called by Codec.decode after read completes. */
    def release(reader: JsonReader): Unit =
        reader.release()
end JsonReader
