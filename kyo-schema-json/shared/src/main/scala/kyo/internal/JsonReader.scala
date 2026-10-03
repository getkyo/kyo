package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.Codec.IntrospectingReader
import kyo.Codec.Reader
import scala.annotation.tailrec

// Parses UTF-8 byte input directly, avoiding String intermediary on the decode path.
final class JsonReader private (private var input: Span[Byte], private var _frame: Frame) extends IntrospectingReader:
    override def frame: Frame = _frame
    private var pos           = 0

    // Reusable field values array: avoids per-decode allocation in macro-generated code.
    // Only reused for top-level (depth 0) object. Nested objects allocate fresh arrays.
    private var fieldValues: Array[AnyRef] = new Array[AnyRef](16)
    private var fieldDepth: Int            = 0

    // Field matching state: start/len of last parsed field name (for zero-alloc matching)
    private var lastFieldStart: Int = 0
    private var lastFieldLen: Int   = 0

    // A field name holding an escape cannot be compared byte for byte against the input, so its decoded UTF-8 is held
    // here and matched instead.
    private var lastFieldEscaped: Boolean     = false
    private var lastFieldDecoded: Array[Byte] = Array.emptyByteArray

    // One bit per container open while skipping, set for an object.
    private var skipKinds: Array[Long] = new Array[Long](1)

    // Restored to the defaults on reuse, since a pooled reader would otherwise carry one caller's limits into the next decode.
    private var maxNumberDigits: Int = Json.DefaultMaxNumberDigits
    private var maxExponent: Int     = Json.DefaultMaxExponent

    private[kyo] def resetNumberLimits(maxNumberDigits: Int, maxExponent: Int): Unit =
        this.maxNumberDigits = maxNumberDigits
        this.maxExponent = maxExponent

    def objectStart(): Int =
        checkDepth()
        skipWhitespace()
        if pos >= input.size || input(pos) != '{' then wrongKind("object", "Expected '{'")
        advance()
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
        if pos >= input.size || input(pos) != '[' then wrongKind("array", "Expected '['")
        advance()
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
        if pos >= input.size || input(pos) != '"' then error("Expected a field name")
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
        skipPlainStringBytes()
        if pos >= input.size then error("Unterminated field name")
        if input(pos) == '"' then
            lastFieldEscaped = false
            lastFieldLen = pos - lastFieldStart
            pos += 1 // skip closing quote
        else
            pos = lastFieldStart
            lastFieldEscaped = true
            lastFieldDecoded = readQuotedStringWithEscapes().getBytes(StandardCharsets.UTF_8)
        end if
        skipWhitespace()
        expectByte(':')
    end fieldParse

    /** Compare last parsed field name bytes against pre-encoded name bytes. */
    override def matchField(nameBytes: Array[Byte]): Boolean =
        if lastFieldEscaped then java.util.Arrays.equals(lastFieldDecoded, nameBytes)
        else if nameBytes.length != lastFieldLen then false
        else
            @tailrec def loop(i: Int): Boolean =
                if i >= lastFieldLen then true
                else if input(lastFieldStart + i) != nameBytes(i) then false
                else loop(i + 1)
            loop(0)
    end matchField

    override private[kyo] def matchesKeyBytes: Boolean = true

    override def lastFieldName(): String =
        if lastFieldEscaped then new String(lastFieldDecoded, StandardCharsets.UTF_8)
        else if lastFieldLen <= 0 then ""
        else
            val buf                         = new Array[Byte](lastFieldLen)
            @tailrec def copy(i: Int): Unit =
                if i < lastFieldLen then
                    buf(i) = input(lastFieldStart + i)
                    copy(i + 1)
            copy(0)
            new String(buf, java.nio.charset.StandardCharsets.UTF_8)
    end lastFieldName

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

    def hasNextField(): Boolean = hasNext('}')

    def hasNextElement(): Boolean = hasNext(']')

    /** Whether another member follows inside the current object or array, consuming the comma that separates it.
      *
      * Whether a comma is owed is read from the last significant byte before the cursor rather than from reader state:
      * `{`, `[` or an already consumed `,` means no value has been read since, and any other byte ends a value. That
      * keeps the call idempotent, so a caller may ask twice at the same position.
      */
    private def hasNext(close: Byte): Boolean =
        skipWhitespace()
        // Answering false here would let a record decoder read a cut-off object as a complete one and report its fields missing.
        if pos >= input.size then error(s"Expected '${close.toChar}' but reached end of input")
        else
            val next = input(pos)
            previousSignificantByte match
                case '{' | '[' =>
                    if next == ',' then error("Expected a value")
                    next != close
                case ',' =>
                    if next == ',' || next == close then error("Expected a value")
                    true
                case _ =>
                    if next == close then false
                    else if next == ',' then
                        advance()
                        skipWhitespace()
                        true
                    else error(s"Expected ',' or '${close.toChar}'")
            end match
        end if
    end hasNext

    private def previousSignificantByte: Byte =
        @tailrec def loop(i: Int): Byte =
            if i < 0 then 0
            else
                val b = input(i)
                if b == ' ' || b == '\t' || b == '\n' || b == '\r' then loop(i - 1) else b
        loop(pos - 1)
    end previousSignificantByte

    def string(): String =
        skipWhitespace()
        if pos >= input.size || input(pos) != '"' then wrongKind("string", "Expected '\"'")
        pos += 1
        val start = pos
        skipPlainStringBytes()
        if pos < input.size && input(pos) == '"' then
            // No escapes: bulk convert from underlying array
            val s = new String(input.toArrayUnsafe, start, pos - start, StandardCharsets.UTF_8)
            pos += 1
            s
        else
            // An escape, a control character or the end of input: the escape-aware path decodes the first and
            // reports the other two
            pos = start
            readQuotedStringWithEscapes()
        end if
    end string

    /** Advances over string content that needs no decoding: anything but a quote, a backslash or a control character. */
    private def skipPlainStringBytes(): Unit =
        @tailrec def loop(): Unit =
            if pos < input.size then
                val b = input(pos)
                if b != '"' && b != '\\' && (b & 0xff) >= 0x20 then
                    pos += 1
                    loop()
        loop()
    end skipPlainStringBytes

    def int(): Int =
        skipWhitespace()
        val start = pos
        val neg   = pos < input.size && input(pos) == '-'
        if neg then pos += 1
        if pos >= input.size || input(pos) < '0' || input(pos) > '9' then
            if neg then error("Expected number") else wrongKind("number", "Expected number")
        val digitsStart                                                          = pos
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
        requireNoLeadingZero(digitsStart)
        if pos - digitsStart > maxNumberDigits then tooManyDigits(start)
        // If followed by '.', 'e', or 'E': not a valid JSON integer, fall back
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
        if pos >= input.size || input(pos) < '0' || input(pos) > '9' then
            if neg then error("Expected number") else wrongKind("number", "Expected number")
        val digitsStart                                                            = pos
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
        requireNoLeadingZero(digitsStart)
        if pos - digitsStart > maxNumberDigits then tooManyDigits(start)
        // If followed by '.', 'e', or 'E': not a valid JSON integer, fall back
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
            requireNumberStart()
            val start = pos
            scanNumber()
            val end      = pos
            val inputArr = input.toArrayUnsafe
            val bits     = FastFloat.parseFloat(inputArr, start, end)
            if bits == FastFloat.FloatBailOut then
                pos = start
                parseNumberStr("Float")(_.toFloat)
            else
                pos = end
                java.lang.Float.intBitsToFloat(bits)
            end if
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
            requireNumberStart()
            val start = pos
            scanNumber()
            val end      = pos
            val inputArr = input.toArrayUnsafe
            val bits     = FastFloat.parseDouble(inputArr, start, end)
            if bits == FastFloat.DoubleBailOut then
                pos = start
                parseNumberStr("Double")(_.toDouble)
            else
                pos = end
                java.lang.Double.longBitsToDouble(bits)
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
        else wrongKind("boolean", "Expected boolean")
        end if
    end boolean

    def short(): Short =
        skipWhitespace()
        requireNumberStart()
        parseNumberStr("Short")(_.toShort)
    end short

    def byte(): Byte =
        skipWhitespace()
        requireNumberStart()
        parseNumberStr("Byte")(_.toByte)
    end byte

    // A number starts with a digit or a minus sign; anything else is a value of another kind or malformed input.
    private def requireNumberStart(): Unit =
        if pos >= input.size || !(input(pos) == '-' || (input(pos) >= '0' && input(pos) <= '9')) then
            wrongKind("number", "Expected number")

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

    /** Advances over one value, holding it to the same grammar a read would, without building it.
      *
      * A skipped value builds nothing, so its nesting is not bound by `maxDepth`: containers are walked in a loop over a bit
      * stack of their kinds rather than by recursion, and an ignored field nested any depth is skipped in constant stack.
      */
    def skip(): Unit =
        skipWhitespace()
        if pos >= input.size then error("Unexpected end of input")
        peek() match
            case '{' | '[' => skipContainer()
            case _         => skipScalar()
    end skip

    private def skipScalar(): Unit =
        peek() match
            case '"'       => skipString()
            case 't' | 'f' => discard(boolean())
            case 'n'       => if !isNil() then error("Expected 'null'")
            case _         => scanNumber()
    end skipScalar

    private def skipContainer(): Unit =
        def open(depth: Int): Unit =
            val word = depth >>> 6
            if word >= skipKinds.length then skipKinds = java.util.Arrays.copyOf(skipKinds, skipKinds.length * 2)
            val bit = 1L << (depth & 63)
            if input(pos) == '{' then skipKinds(word) |= bit else skipKinds(word) &= ~bit
            pos += 1
        end open
        @tailrec def loop(depth: Int): Unit =
            if depth > 0 then
                val top      = depth - 1
                val isObject = (skipKinds(top >>> 6) & (1L << (top & 63))) != 0
                val close    = if isObject then '}' else ']'
                if hasNext(close.toByte) then
                    if isObject then
                        skipString()
                        skipWhitespace()
                        expect(':')
                        skipWhitespace()
                    end if
                    if pos >= input.size then error("Unexpected end of input")
                    if input(pos) == '{' || input(pos) == '[' then
                        open(depth)
                        loop(depth + 1)
                    else
                        skipScalar()
                        loop(depth)
                    end if
                else
                    expect(close)
                    loop(depth - 1)
                end if
        open(0)
        loop(1)
    end skipContainer

    def mapStart(): Int         = objectStart()
    def mapEnd(): Unit          = objectEnd()
    def hasNextEntry(): Boolean = hasNextField()

    def bytes(): Span[Byte] =
        val s = string()
        try Span.fromUnsafe(kyo.internal.Base64s.decodeExact(s))
        catch
            case e: IllegalArgumentException =>
                error(s"Invalid Base64: ${e.getMessage}")
        end try
    end bytes

    def bigInt(): BigInt =
        skipWhitespace()
        val start = pos + 1
        val s     = string()
        requireWithinNumberBounds(s, start)
        try BigInt(s)
        catch
            case _: NumberFormatException =>
                error(s"Invalid BigInt value: '$s'")
        end try
    end bigInt

    def bigDecimal(): BigDecimal =
        skipWhitespace()
        val start = pos + 1
        val s     = string()
        requireWithinNumberBounds(s, start)
        try BigDecimal(s)
        catch
            case _: NumberFormatException =>
                error(s"Invalid BigDecimal value: '$s'")
        end try
    end bigDecimal

    def instant(): java.time.Instant =
        val s = string()
        TimeText.instant(s).foldOrThrow(identity, reason => error(s"Invalid Instant value: '$s' ($reason)"))
    end instant

    def duration(): java.time.Duration =
        val s = string()
        TimeText.duration(s).foldOrThrow(identity, reason => error(s"Invalid Duration value: '$s' ($reason)"))
    end duration

    // Internal parsing methods

    /** Read a quoted string when we know there are escapes (backslash found during fast scan). */
    private def readQuotedStringWithEscapes(): String =
        val sb                    = new StringBuilder
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
                        case 'u'  =>
                            pos += 1
                            if pos + 4 > input.size then error("Unexpected end of input in unicode escape")
                            val cp = parseHex4(pos)
                            pos += 4
                            if cp >= 0xd800 && cp <= 0xdbff then
                                // High surrogate: must be followed by \uDC00 through \uDFFF
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
                        case _ => error("Invalid escape")
                    end match
                else
                    // Decode UTF-8 byte(s) to char(s)
                    val b = input(pos) & 0xff
                    if b < 0x20 then error("Unescaped control character in string")
                    else if b < 0x80 then
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

    private def parseHex4(p: Int): Int = (hexDigit(input(p)) << 12) | (hexDigit(input(p + 1)) << 8) |
        (hexDigit(input(p + 2)) << 4) | hexDigit(input(p + 3))

    private def hexDigit(b: Byte): Int =
        if b >= '0' && b <= '9' then b - '0'
        else if b >= 'a' && b <= 'f' then b - 'a' + 10
        else if b >= 'A' && b <= 'F' then b - 'A' + 10
        else error(s"Invalid hex digit in unicode escape: '${b.toChar}'")

    private def readNumber(): String =
        val start = pos
        scanNumber()
        // Number bytes are always ASCII, copy only the needed range
        val len                              = pos - start
        val arr                              = new Array[Byte](len)
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

    /** Advances over one RFC 8259 number. Fails at the first byte that breaks the grammar, or at the number's first byte when it
      * exceeds `maxNumberDigits` or `maxExponent`.
      */
    private def scanNumber(): Unit =
        val start = pos
        if pos < input.size && input(pos) == '-' then pos += 1
        val intStart  = pos
        val intDigits = scanDigits()
        if intDigits == 0 then error("Expected digit")
        requireNoLeadingZero(intStart)
        val fracDigits =
            if pos < input.size && input(pos) == '.' then
                pos += 1
                val n = scanDigits()
                if n == 0 then error("Expected digit after '.'")
                n
            else 0
        if intDigits + fracDigits > maxNumberDigits then tooManyDigits(start)
        if pos < input.size && (input(pos) == 'e' || input(pos) == 'E') then
            pos += 1
            if pos < input.size && (input(pos) == '+' || input(pos) == '-') then pos += 1
            val expStart = pos
            if scanDigits() == 0 then error("Expected exponent digit")
            if exponentMagnitude(expStart, pos) > maxExponent then exponentTooLarge(start)
        end if
    end scanNumber

    private def scanDigits(): Int =
        val start                 = pos
        @tailrec def loop(): Unit =
            if pos < input.size && input(pos) >= '0' && input(pos) <= '9' then
                pos += 1
                loop()
        loop()
        pos - start
    end scanDigits

    /** Fails at the second digit when the integer part starting at `digitsStart` and ending at the cursor is a zero followed by more digits. */
    private def requireNoLeadingZero(digitsStart: Int): Unit =
        if pos - digitsStart > 1 && input(digitsStart) == '0' then
            pos = digitsStart + 1
            error("Leading zero in number")

    /** The value of the exponent digits in `[from, until)`, saturating at `Long.MaxValue`. */
    private def exponentMagnitude(from: Int, until: Int): Long =
        @tailrec def loop(i: Int, acc: Long): Long =
            if i >= until then acc
            else if acc > (Long.MaxValue - 9) / 10 then Long.MaxValue
            else loop(i + 1, acc * 10 + (input(i) - '0'))
        loop(from, 0L)
    end exponentMagnitude

    private def tooManyDigits(start: Int): Nothing =
        pos = start
        error(s"Number exceeds $maxNumberDigits significant digits")

    private def exponentTooLarge(start: Int): Nothing =
        pos = start
        error(s"Number exceeds an exponent magnitude of $maxExponent")

    /** Holds a quoted big number to the unquoted bounds, failing at `start`. Its grammar is left to the conversion. */
    private def requireWithinNumberBounds(s: String, start: Int): Unit =
        val e        = s.indexWhere(c => c == 'e' || c == 'E')
        val digits   = (if e < 0 then s else s.substring(0, e)).count(c => c >= '0' && c <= '9')
        val exponent = if e < 0 then "" else s.substring(e + 1).dropWhile(c => c == '+' || c == '-' || c == '0')
        if digits > maxNumberDigits then tooManyDigits(start)
        if exponent.length > 18 || exponent.toLongOption.exists(_ > maxExponent) then exponentTooLarge(start)
    end requireWithinNumberBounds

    /** The exact value of a scanned number. The exponent is handed over without its leading zeros, which `BigDecimal`'s parsers on
      * some platforms count against their limit.
      */
    private def exactNumber(numStr: String): BigDecimal =
        val e = numStr.indexWhere(c => c == 'e' || c == 'E')
        if e < 0 then BigDecimal(numStr)
        else
            val sign   = if numStr.charAt(e + 1) == '-' then "-" else ""
            val digits = numStr.substring(e + 1).dropWhile(c => c == '+' || c == '-' || c == '0')
            BigDecimal(s"${numStr.substring(0, e)}e$sign${if digits.isEmpty then "0" else digits}")
        end if
    end exactNumber

    /** Advances over one string without building it, holding it to the grammar `string()` applies. */
    private def skipString(): Unit =
        skipWhitespace()
        expectByte('"')
        val start = pos
        skipPlainStringBytes()
        if pos < input.size && input(pos) == '"' then pos += 1
        else
            pos = start
            discard(readQuotedStringWithEscapes())
        end if
    end skipString

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

    /** Fails a read that expected `expected` at `pos`: a type mismatch when a value of another kind starts there, since the input is
      * well-formed JSON and only its shape is wrong, and a parse failure otherwise.
      */
    private def wrongKind(expected: String, parseMessage: String): Nothing =
        def literalAt(text: String): Boolean =
            pos + text.length <= input.size && text.indices.forall(i => input(pos + i) == text.charAt(i))
        val actual =
            if pos >= input.size then ""
            else
                input(pos) match
                    case '"'                                                                           => "string"
                    case '{'                                                                           => "object"
                    case '['                                                                           => "array"
                    case b if b >= '0' && b <= '9'                                                     => "number"
                    case '-' if pos + 1 < input.size && input(pos + 1) >= '0' && input(pos + 1) <= '9' => "number"
                    case 't' if literalAt("true")                                                      => "boolean"
                    case 'f' if literalAt("false")                                                     => "boolean"
                    case 'n' if literalAt("null")                                                      => "null"
                    case _                                                                             => ""
        if actual.isEmpty then error(parseMessage)
        else throw TypeMismatchException(Nil, expected, actual)(using _frame)
    end wrongKind

    private[kyo] def requireEndOfInput(): Unit =
        skipWhitespace()
        if pos < input.size then error("Unexpected trailing content")

    private def error(msg: String): Nothing =
        given Frame                          = _frame
        val contextRadius                    = 30
        val start                            = math.max(0, pos - contextRadius)
        val end                              = math.min(input.size, pos + contextRadius)
        val len                              = end - start
        val arr                              = new Array[Byte](len)
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
        this.lastFieldEscaped = false
        this.fieldDepth = 0
        resetNumberLimits(Json.DefaultMaxNumberDigits, Json.DefaultMaxExponent)
    end reset

    override def captureValue(): Reader =
        skipWhitespace()
        val start = pos
        skip()
        val end      = pos
        val captured = new JsonReader(input.slice(start, end), _frame)
        captured.resetNumberLimits(maxNumberDigits, maxExponent)
        captured
    end captureValue

    override def readStructure(): Structure.Value =
        skipWhitespace()
        if pos >= input.size then error("Unexpected end of input while reading Structure.Value")
        peek() match
            case '{' =>
                val size                  = objectStart()
                val n                     = if size >= 0 then size else 4
                val acc                   = scala.collection.mutable.ArrayBuffer.empty[(String, Structure.Value)]
                @tailrec def loop(): Unit =
                    if hasNextField() then
                        val name  = field()
                        val value = readStructure()
                        discard(acc.addOne((name, value)))
                        loop()
                loop()
                discard(n)
                objectEnd()
                Structure.Value.Record(Chunk.from(acc.toSeq))
            case '[' =>
                val size                  = arrayStart()
                val n                     = if size >= 0 then size else 4
                val acc                   = scala.collection.mutable.ArrayBuffer.empty[Structure.Value]
                @tailrec def loop(): Unit =
                    if hasNextElement() then
                        discard(acc.addOne(readStructure()))
                        loop()
                loop()
                discard(n)
                arrayEnd()
                Structure.Value.Sequence(Chunk.from(acc.toSeq))
            case '"'       => Structure.Value.Str(string())
            case 't' | 'f' => Structure.Value.Bool(boolean())
            case 'n'       =>
                if !isNil() then error("Expected 'null'")
                Structure.Value.Null
            case _ =>
                val numStr = readNumber()
                val exact  = exactNumber(numStr)
                if numStr.indexOf('.') < 0 && numStr.indexOf('e') < 0 && numStr.indexOf('E') < 0 then
                    if exact.isValidLong then Structure.Value.Integer(exact.toLong)
                    else Structure.Value.BigNum(exact)
                else
                    val decimal = numStr.toDouble
                    if decimal.isFinite && BigDecimal(decimal.toString) == exact then Structure.Value.Decimal(decimal)
                    else Structure.Value.BigNum(exact)
                end if
        end match
    end readStructure

    // Release this reader back to the thread-local pool
    override def release(): Unit =
        this.input = Span.empty[Byte]
        this.pos = 0
        this.lastFieldStart = 0
        this.lastFieldLen = 0
        this.lastFieldEscaped = false
        this.lastFieldDecoded = Array.emptyByteArray
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
                    val len                              = pos - start
                    val arr                              = new Array[Byte](len)
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
