package kyo.internal

import kyo.Codec.Writer
import kyo.Maybe
import kyo.Maybe.*
import kyo.Span
import scala.annotation.tailrec

/** High-performance JSON writer.
  *
  * Escape lookup table technique and digit-pair int/long writing inspired by jsoniter-scala by Andriy Plokhotnyuk. Double/float formatting
  * uses the Ryu algorithm by Ulf Adams.
  */
final class JsonWriter private (
    private var buf: Array[Byte],
    private var pos: Int,
    private var depth: Int,
    private var needsComma: Array[Long]
) extends Writer:

    private inline def getFlag(d: Int): Boolean = (needsComma(d >> 6) & (1L << (d & 63))) != 0L
    private inline def setFlag(d: Int): Unit    = needsComma(d >> 6) |= (1L << (d & 63))
    private inline def clearFlag(d: Int): Unit  = needsComma(d >> 6) &= ~(1L << (d & 63))

    def objectStart(name: String, size: Int): Unit =
        maybeComma()
        writeByte('{')
        push()
    end objectStart

    def objectEnd(): Unit =
        pop()
        writeByte('}')

    def arrayStart(size: Int): Unit =
        maybeComma()
        writeByte('[')
        push()
    end arrayStart

    def arrayEnd(): Unit =
        pop()
        writeByte(']')

    def fieldBytes(nameBytes: Array[Byte], index: Int): Unit =
        if getFlag(depth) then writeByte(',')
        clearFlag(depth)
        writeByte('"')
        writeAsciiLiteral(nameBytes)
        writeByte('"')
        writeByte(':')
    end fieldBytes

    // Override to properly escape field names (for Map keys with special characters)
    override def field(name: String, fieldId: Int): Unit =
        if getFlag(depth) then writeByte(',')
        clearFlag(depth)
        writeQuotedString(name)
        writeByte(':')
    end field

    def string(value: String): Unit =
        maybeComma()
        writeQuotedString(value)

    def int(value: Int): Unit =
        maybeComma()
        writeLong(value.toLong)

    def long(value: Long): Unit =
        maybeComma()
        writeLong(value)

    def float(value: Float): Unit =
        maybeComma()
        if value.isNaN then writeAsciiLiteral(JsonWriter.NaNStringBytes)
        else if value == Float.PositiveInfinity then writeAsciiLiteral(JsonWriter.PosInfStringBytes)
        else if value == Float.NegativeInfinity then writeAsciiLiteral(JsonWriter.NegInfStringBytes)
        else
            val i = value.toInt
            if i.toFloat == value && value != -0.0f && i > -10000000 && i < 10000000 then
                writeLong(i.toLong)
                writeAsciiLiteral(JsonWriter.DotZeroBytes)
            else
                val written = Ryu.RyuFloat.write(value, buf, pos, buf.length)
                if written < 0 then
                    // Buffer too small, grow and retry
                    ensure((-written) - pos)
                    val w2 = Ryu.RyuFloat.write(value, buf, pos, buf.length)
                    pos = w2
                else
                    pos = written
                end if
            end if
        end if
    end float

    def double(value: Double): Unit =
        maybeComma()
        if value.isNaN then writeAsciiLiteral(JsonWriter.NaNStringBytes)
        else if value == Double.PositiveInfinity then writeAsciiLiteral(JsonWriter.PosInfStringBytes)
        else if value == Double.NegativeInfinity then writeAsciiLiteral(JsonWriter.NegInfStringBytes)
        else
            val l = value.toLong
            if l.toDouble == value && value != -0.0d then
                writeLong(l)
                writeAsciiLiteral(JsonWriter.DotZeroBytes)
            else
                val written = Ryu.RyuDouble.write(value, buf, pos, buf.length)
                if written < 0 then
                    // Buffer too small, grow and retry
                    ensure((-written) - pos)
                    val w2 = Ryu.RyuDouble.write(value, buf, pos, buf.length)
                    pos = w2
                else
                    pos = written
                end if
            end if
        end if
    end double

    def boolean(value: Boolean): Unit =
        maybeComma()
        if value then writeAsciiLiteral(JsonWriter.TrueBytes)
        else writeAsciiLiteral(JsonWriter.FalseBytes)
    end boolean

    def short(value: Short): Unit =
        maybeComma()
        writeLong(value.toLong)

    def byte(value: Byte): Unit =
        maybeComma()
        writeLong(value.toLong)

    def char(value: Char): Unit =
        maybeComma()
        writeByte('"')
        // Single char — inline UTF-8 encoding
        if value < 0x80 then writeByte(value)
        else if value < 0x800 then
            ensure(2)
            buf(pos) = (0xc0 | (value >> 6)).toByte
            buf(pos + 1) = (0x80 | (value & 0x3f)).toByte
            pos += 2
        else
            ensure(3)
            buf(pos) = (0xe0 | (value >> 12)).toByte
            buf(pos + 1) = (0x80 | ((value >> 6) & 0x3f)).toByte
            buf(pos + 2) = (0x80 | (value & 0x3f)).toByte
            pos += 3
        end if
        writeByte('"')
    end char

    def nil(): Unit =
        maybeComma()
        writeAsciiLiteral(JsonWriter.NullBytes)

    def mapStart(size: Int): Unit = objectStart("", size)
    def mapEnd(): Unit            = objectEnd()

    def bytes(value: Span[Byte]): Unit =
        maybeComma()
        writeQuotedString(java.util.Base64.getEncoder.encodeToString(value.toArray))

    def bigInt(value: BigInt): Unit =
        maybeComma()
        writeQuotedString(value.toString)

    def bigDecimal(value: BigDecimal): Unit =
        maybeComma()
        writeQuotedString(value.toString)

    def instant(value: java.time.Instant): Unit =
        maybeComma()
        writeQuotedString(value.toString)

    def duration(value: java.time.Duration): Unit =
        maybeComma()
        writeQuotedString(value.toString)

    override def resultString: String =
        val s =
            if hasNonAscii(buf, pos) then
                // Non-ASCII: decode UTF-8 directly from buf → String allocates its internal byte[] once.
                new String(buf, 0, pos, java.nio.charset.StandardCharsets.UTF_8)
            else
                // ASCII: trim to exact size, then AsciiStringFactory zero-copy-adopts the byte[].
                AsciiStringFactory.fromAsciiBytes(java.util.Arrays.copyOf(buf, pos))
        release()
        s
    end resultString

    @tailrec
    private def hasNonAscii(bs: Array[Byte], len: Int, i: Int = 0): Boolean =
        if i >= len then false
        else if bs(i) < 0 then true
        else hasNonAscii(bs, len, i + 1)

    def result(): Span[Byte] =
        val bytes = java.util.Arrays.copyOf(buf, pos)
        release()
        Span.fromUnsafe(bytes)
    end result

    // Reset state and return to thread-local cache. Buffer stays, content cleared.
    private def release(): Unit =
        java.util.Arrays.fill(buf, 0, pos, 0.toByte)
        pos = 0
        depth = 0
        java.util.Arrays.fill(needsComma, 0L)
        JsonWriter.cache.set(this)
    end release

    // Zero-copy String: shares byte[] for ASCII on JVM, falls back to copy for non-ASCII
    private def newString(bytes: Array[Byte]): String =
        @tailrec def loop(i: Int): String =
            if i < bytes.length then
                if bytes(i) < 0 then new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                else loop(i + 1)
            else AsciiStringFactory.fromAsciiBytes(bytes)
        loop(0)
    end newString

    // Internal: comma tracking per nesting level
    private def maybeComma(): Unit =
        if getFlag(depth) then writeByte(',')
        else setFlag(depth)

    private def push(): Unit =
        depth += 1
        if (depth >> 6) >= needsComma.length then
            needsComma = java.util.Arrays.copyOf(needsComma, needsComma.length * 2)
        clearFlag(depth)
    end push

    private def pop(): Unit =
        depth -= 1

    private inline def ensure(n: Int): Unit =
        if pos + n > buf.length then
            buf = java.util.Arrays.copyOf(buf, math.max(buf.length * 2, pos + n))

    private inline def writeByte(b: Int): Unit =
        ensure(1)
        buf(pos) = b.toByte
        pos += 1
    end writeByte

    // Write a long value directly as ASCII digits — zero allocation.
    // Uses digit-pair lookup table to write 2 digits at a time.
    private def writeLong(v: Long): Unit =
        if v == 0L then
            writeByte('0')
        else if v < 0 then
            writeByte('-')
            if v == Long.MinValue then
                // Handle Long.MinValue: can't negate it
                writeAsciiLiteral(JsonWriter.MinLongBytes)
            else
                writeLongPositive(-v)
            end if
        else
            writeLongPositive(v)
        end if
    end writeLong

    private def writeLongPositive(value: Long): Unit =
        val digits = Ryu.digitCount(value)
        ensure(digits)
        val dp = Ryu.DigitPairs
        // Write 2 digits at a time from right to left
        @tailrec def loop(n: Long, p: Int): Unit =
            if n >= 100 then
                val q = (n % 100).toInt
                buf(p - 2) = dp(q * 2)
                buf(p - 1) = dp(q * 2 + 1)
                loop(n / 100, p - 2)
            else if n >= 10 then
                buf(p - 2) = dp(n.toInt * 2)
                buf(p - 1) = dp(n.toInt * 2 + 1)
            else
                buf(p - 1) = ('0' + n.toInt).toByte
        loop(value, pos + digits)
        pos += digits
    end writeLongPositive

    private def writeAscii(s: String): Unit =
        val len = s.length
        ensure(len)
        @tailrec def loop(i: Int): Unit =
            if i < len then
                buf(pos) = s.charAt(i).toByte
                pos += 1
                loop(i + 1)
        loop(0)
    end writeAscii

    private def writeAsciiLiteral(bytes: Array[Byte]): Unit =
        ensure(bytes.length)
        System.arraycopy(bytes, 0, buf, pos, bytes.length)
        pos += bytes.length
    end writeAsciiLiteral

    // String escaping per RFC 8259, with direct UTF-8 encoding.
    // Uses a pre-computed lookup table for ASCII escape decisions.
    //
    // Scan-first bulk-copy fast path: walks the input once looking for the first
    // index that is non-ASCII or requires escaping. The prefix [0, safeEnd) is
    // pure ASCII with no escaping, so every UTF-16 code unit maps to exactly one
    // UTF-8 byte — bulk-copy it with a tight `buf(pos + j) = charAt(j).toByte`
    // loop. If safeEnd < len, fall through to the per-char slow path starting
    // at safeEnd.
    private def writeQuotedString(s: String): Unit =
        writeByte('"')
        val escapeTable = JsonWriter.EscapeTable
        val len         = s.length

        // Scan for the first index that is non-ASCII or requires escaping.
        @tailrec def scan(i: Int): Int =
            if i >= len then i
            else
                val c = s.charAt(i)
                if c >= 0x80 then i
                else if escapeTable(c) != 0 then i
                else scan(i + 1)
        val safeEnd = scan(0)

        if safeEnd > 0 then
            // Bulk-copy the pure-ASCII, escape-free prefix (or entire string).
            // charAt returned < 0x80 for every index in [0, safeEnd), so each
            // UTF-16 code unit maps to exactly one UTF-8 byte.
            ensure(safeEnd)
            var j = 0
            while j < safeEnd do
                buf(pos + j) = s.charAt(j).toByte
                j += 1
            pos += safeEnd
        end if

        if safeEnd < len then
            writeQuotedStringSlow(s, safeEnd)

        writeByte('"')
    end writeQuotedString

    // Per-character escape/encode path. Handles everything the fast path cannot:
    // ASCII chars requiring escape, 2/3/4-byte UTF-8, and surrogate pairs.
    // Called with `start` = first index the fast-path scan stopped at.
    private def writeQuotedStringSlow(s: String, start: Int): Unit =
        val escapeTable = JsonWriter.EscapeTable
        val len         = s.length
        @tailrec def loop(i: Int): Unit =
            if i < len then
                val c = s.charAt(i)
                if c < 0x80 then
                    val esc = escapeTable(c)
                    if esc == 0 then
                        // No escape needed — fast path
                        writeByte(c)
                    else if esc == -1 then
                        // \uXXXX escape for control chars
                        writeByte('\\'); writeByte('u')
                        writeByte(JsonWriter.Hex.charAt((c >> 12) & 0xf))
                        writeByte(JsonWriter.Hex.charAt((c >> 8) & 0xf))
                        writeByte(JsonWriter.Hex.charAt((c >> 4) & 0xf))
                        writeByte(JsonWriter.Hex.charAt(c & 0xf))
                    else
                        // Named escape: \", \\, \n, \r, \t, \b, \f
                        writeByte('\\')
                        writeByte(esc)
                    end if
                    loop(i + 1)
                else if c < 0x800 then
                    // 2-byte UTF-8
                    ensure(2)
                    buf(pos) = (0xc0 | (c >> 6)).toByte
                    buf(pos + 1) = (0x80 | (c & 0x3f)).toByte
                    pos += 2
                    loop(i + 1)
                else if c >= 0xd800 && c <= 0xdbff && i + 1 < len then
                    // High surrogate — check for low surrogate
                    val lo = s.charAt(i + 1)
                    if lo >= 0xdc00 && lo <= 0xdfff then
                        // 4-byte UTF-8 for supplementary character
                        val cp = Character.toCodePoint(c, lo)
                        ensure(4)
                        buf(pos) = (0xf0 | (cp >> 18)).toByte
                        buf(pos + 1) = (0x80 | ((cp >> 12) & 0x3f)).toByte
                        buf(pos + 2) = (0x80 | ((cp >> 6) & 0x3f)).toByte
                        buf(pos + 3) = (0x80 | (cp & 0x3f)).toByte
                        pos += 4
                        loop(i + 2) // skip the low surrogate
                    else
                        // Lone high surrogate — encode as 3-byte UTF-8
                        ensure(3)
                        buf(pos) = (0xe0 | (c >> 12)).toByte
                        buf(pos + 1) = (0x80 | ((c >> 6) & 0x3f)).toByte
                        buf(pos + 2) = (0x80 | (c & 0x3f)).toByte
                        pos += 3
                        loop(i + 1)
                    end if
                else
                    // 3-byte UTF-8 (BMP or lone low surrogate)
                    ensure(3)
                    buf(pos) = (0xe0 | (c >> 12)).toByte
                    buf(pos + 1) = (0x80 | ((c >> 6) & 0x3f)).toByte
                    buf(pos + 2) = (0x80 | (c & 0x3f)).toByte
                    pos += 3
                    loop(i + 1)
                end if
        loop(start)
    end writeQuotedStringSlow

end JsonWriter

object JsonWriter:
    private val Hex = "0123456789abcdef"

    private val NullBytes                   = "null".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
    private val TrueBytes                   = "true".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
    private val FalseBytes                  = "false".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
    private val MinLongBytes                = "9223372036854775808".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
    private val DotZeroBytes                = ".0".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
    private[internal] val NaNStringBytes    = "\"NaN\"".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
    private[internal] val PosInfStringBytes = "\"Infinity\"".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
    private[internal] val NegInfStringBytes = "\"-Infinity\"".getBytes(java.nio.charset.StandardCharsets.US_ASCII)

    // Pre-computed escape lookup table for ASCII characters (0-127).
    // 0 = no escape needed, -1 = \uXXXX escape, positive = named escape byte
    private val EscapeTable: Array[Byte] =
        val table = new Array[Byte](128)
        table('"') = '"'
        table('\\') = '\\'
        table('\n') = 'n'
        table('\r') = 'r'
        table('\t') = 't'
        table('\b') = 'b'
        table('\f') = 'f'
        // 0x00-0x1F control chars (except the named ones above) need \uXXXX escape
        @tailrec def loop(i: Int): Unit =
            if i < 0x20 then
                if table(i) == 0 then table(i) = -1
                loop(i + 1)
        loop(0)
        table
    end EscapeTable

    private[internal] val cache = new ThreadLocal[JsonWriter]

    def apply(): JsonWriter =
        val cached = Maybe(cache.get()) // ThreadLocal.get() returns JVM null when absent
        cached match
            case Maybe.Present(w) =>
                cache.set(null) // Clear pool slot (Java API requires null)
                w
            case _ => new JsonWriter(new Array[Byte](256), 0, 0, new Array[Long](1))
        end match
    end apply

end JsonWriter
