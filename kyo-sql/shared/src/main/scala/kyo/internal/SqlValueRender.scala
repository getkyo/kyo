package kyo.internal

import kyo.Chunk
import kyo.Maybe
import kyo.Span
import kyo.discard

/** The one rendering `SqlRow.text` answers with, for every backend and every wire format.
  *
  * The string a column reads as is a function of the decoded value alone: not the engine, not the wire format, not a session setting. Each
  * backend decodes its wire into the neutral values below and hands them here, under BOTH formats, so a text-protocol value is parsed and
  * re-rendered rather than passed through. That is not redundant: an engine's own output is chosen by settings the connection is never told
  * about (`extra_float_digits`, `TimeZone`, `bytea_output`, `DateStyle`, `IntervalStyle`).
  *
  * Forms are chosen on three grounds in order: determined by the value alone, unambiguous to read back, and where one spelling is a literal
  * on every engine it wins. An engine is named below as evidence that a choice had to be made, never as the source of the form.
  */
private[kyo] object SqlValueRender:

    // ── Integer ─────────────────────────────────────────────────────────────────

    /** `BigInt` because `BIGINT UNSIGNED` reaches 2^64-1, which a signed `Long` does not span. */
    def integer(value: BigInt): String = value.toString

    // ── Decimal ─────────────────────────────────────────────────────────────────

    /** `toPlainString`: `toString` takes exponent notation below adjusted exponent -6, and no engine's fixed-point output does. */
    def decimal(value: BigDecimal): String = value.bigDecimal.toPlainString

    // ── Float ───────────────────────────────────────────────────────────────────

    /** Where plain notation gives way to the exponent form: the decimal digits each width carries exactly. Past that a plain rendering runs on
      * with figures the width never held. The widths do not share the band, so a four-byte 1e6 renders `1e+06` and an eight-byte one
      * `1000000`.
      */
    private val Float4Digits = 6
    private val Float8Digits = 15

    /** The precisions a shortest-decimal search walks, allocated once: a `float` needs at most 9 significant digits to round-trip and a
      * `double` at most 17.
      */
    private val precisions: Array[java.math.MathContext] =
        Array.tabulate(18)(p => new java.math.MathContext(if p == 0 then 1 else p))

    /** Whether a value carries a minus sign, negative zero included, which a comparison against zero misses. */
    private def isNegative(value: Double): Boolean =
        value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)

    /** A 4-byte float. */
    def float4(value: Float): String =
        if value.isNaN then "NaN"
        else if value.isInfinite then (if value > 0 then "Infinity" else "-Infinity")
        else
            floating(
                shortest(new java.math.BigDecimal(value.toDouble), 9, c => c.floatValue == value),
                isNegative(value.toDouble),
                Float4Digits
            )

    /** An 8-byte float. */
    def float8(value: Double): String =
        if value.isNaN then "NaN"
        else if value.isInfinite then (if value > 0 then "Infinity" else "-Infinity")
        else floating(shortest(new java.math.BigDecimal(value), 17, c => c.doubleValue == value), isNegative(value), Float8Digits)

    /** The shortest decimal that reads back as the same value.
      *
      * `Float.toString` answers this on the JVM but not everywhere: Scala.js widens a float to a double before printing, so 12345.6f arrives
      * as `12345.599609375`, and it spells -0.0 as `0`. Searching the round-trip directly is the same answer on every platform.
      */
    private def shortest(exact: java.math.BigDecimal, maxDigits: Int, roundTrips: java.math.BigDecimal => Boolean): java.math.BigDecimal =
        @annotation.tailrec
        def search(p: Int): java.math.BigDecimal =
            if p > maxDigits then exact
            else
                val candidate = exact.round(precisions(p))
                if roundTrips(candidate) then candidate else search(p + 1)
        search(1).stripTrailingZeros
    end shortest

    /** Plain notation while the decimal exponent is at least -4 and below `digits`, `d.ddde[+-]NN` outside it, exponent padded to two digits. */
    private def floating(value: java.math.BigDecimal, negative: Boolean, digits: Int): String =
        if value.signum == 0 then (if negative then "-0" else "0")
        else
            // The power of ten of the leading digit, which is what the band is measured against.
            val exponent = value.precision - value.scale - 1
            if exponent >= -4 && exponent < digits then value.toPlainString
            else
                val sign = if exponent < 0 then "-" else "+"
                f"${value.movePointLeft(exponent).toPlainString}%se$sign%s${Math.abs(exponent)}%02d"
            end if
    end floating

    // ── Bool ────────────────────────────────────────────────────────────────────

    /** `true` or `false`, which is both engines' own boolean literal. PostgreSQL's `t`/`f` is engine identity rather than the value. */
    def bool(value: Boolean): String = if value then "true" else "false"

    // ── Date ────────────────────────────────────────────────────────────────────

    /** `YYYY-MM-DD`, with the year counted within its era and `BC` appended for the one before it.
      *
      * Wire fields rather than a `java.time.LocalDate`, which rules itself out twice: it numbers 44 BC as -43 where an engine writes
      * `0044 BC`, and it prefixes a `+` to any year of five digits or more, which no engine writes and one engine's range reaches.
      */
    def date(year: Int, month: Int, day: Int, bc: Boolean): String =
        val body = f"$year%04d-$month%02d-$day%02d"
        if bc then s"$body BC" else body

    // ── Time ────────────────────────────────────────────────────────────────────

    /** `[-]HH:MM:SS[.ffffff]`, a signed SPAN rather than a time of day: one engine's `TIME` runs to -838:59:59 and the other's `time` reaches
      * 24:00:00, and `java.time.LocalTime` holds neither endpoint. The hours field grows past two digits as needed.
      */
    def time(negative: Boolean, hours: Long, minutes: Int, seconds: Int, micros: Int): String =
        val sign = if negative then "-" else ""
        val base = f"$sign%s$hours%02d:$minutes%02d:$seconds%02d"
        if micros == 0 then base else s"$base.${fraction(micros)}"
    end time

    /** Trailing zeros dropped, so the rendering follows the value rather than the scale a column declares. */
    private def fraction(micros: Int): String =
        f"${Math.abs(micros)}%06d".reverse.dropWhile(_ == '0').reverse

    // ── Offset ──────────────────────────────────────────────────────────────────

    /** `+HH:MM`, widening to `+HH:MM:SS` for a zone carrying seconds. Never the bare `+HH` one engine writes, which the other rejects in a
      * temporal literal.
      */
    def offset(totalSeconds: Int): String =
        val sign  = if totalSeconds < 0 then "-" else "+"
        val abs   = Math.abs(totalSeconds)
        val hours = abs / 3600
        val mins  = (abs % 3600) / 60
        val secs  = abs  % 60
        if secs != 0 then f"$sign%s$hours%02d:$mins%02d:$secs%02d" else f"$sign%s$hours%02d:$mins%02d"
    end offset

    // ── Composed temporals ──────────────────────────────────────────────────────

    /** `YYYY-MM-DD HH:MM:SS[.ffffff][ BC]`: a space rather than `T`, the seconds always present, and the era trailing the whole value,
      * which is where an engine puts it.
      */
    def dateTime(year: Int, month: Int, day: Int, bc: Boolean, hours: Int, minutes: Int, seconds: Int, micros: Int): String =
        val body = f"${date(year, month, day, bc = false)}%s ${time(false, hours.toLong, minutes, seconds, micros)}%s"
        if bc then s"$body BC" else body

    /** A time of day with a zone, for a column that carries one. */
    def timeWithOffset(hours: Int, minutes: Int, seconds: Int, micros: Int, offsetSeconds: Int): String =
        time(false, hours.toLong, minutes, seconds, micros) + offset(offsetSeconds)

    /** An instant, always at UTC: it has no zone of its own, and the session's must not be the one chosen. The era trails the OFFSET, which is
      * where an engine puts it.
      */
    def timestamp(epochSecond: Long, micros: Int): String =
        val instant = java.time.Instant.ofEpochSecond(epochSecond)
        val utc     = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC)
        val bc      = utc.getYear <= 0
        val year    = if bc then 1 - utc.getYear else utc.getYear
        // The era trails the OFFSET, not the date and time: `0044-04-15 00:00:00+00:00 BC`. Composing `dateTime` with
        // the era already appended and adding the offset after it puts them the other way round, an ordering no engine
        // writes or reads back.
        val body = dateTime(year, utc.getMonthValue, utc.getDayOfMonth, bc = false, utc.getHour, utc.getMinute, utc.getSecond, micros) +
            offset(0)
        if bc then s"$body BC" else body
    end timestamp

    // ── Interval ────────────────────────────────────────────────────────────────

    private val MicrosPerSecond = 1_000_000L
    private val MicrosPerMinute = 60L * MicrosPerSecond
    private val MicrosPerHour   = 60L * MicrosPerMinute

    /** ISO-8601, which is one spelling for a value an engine offers four of, chosen by a session setting. */
    def interval(months: Long, days: Long, micros: Long): String =
        val years     = months / 12
        val monthPart = months  % 12
        val hours     = micros / MicrosPerHour
        val minutes   = (micros % MicrosPerHour) / MicrosPerMinute
        val subMinute = micros  % MicrosPerMinute
        val seconds   = subMinute / MicrosPerSecond
        val frac      = Math.abs(subMinute % MicrosPerSecond)
        def unit(value: Long, suffix: Char): String =
            if value == 0 then "" else s"$value$suffix"
        val secondsPart =
            if seconds == 0 && frac == 0 then ""
            else
                // The sign lives on the seconds when they are zero and the fraction is not, since `0.5` and `-0.5`
                // share a whole part and an interval's fields are negative together.
                val sign = if seconds == 0 && subMinute < 0 then "-" else ""
                val f    = if frac == 0 then "" else "." + fraction(frac.toInt)
                s"$sign$seconds${f}S"
        val datePart = unit(years, 'Y') + unit(monthPart, 'M') + unit(days, 'D')
        val timePart = unit(hours, 'H') + unit(minutes, 'M') + secondsPart
        if datePart.isEmpty && timePart.isEmpty then "PT0S"
        else if timePart.isEmpty then s"P$datePart"
        else s"P${datePart}T$timePart"
    end interval

    // ── Bytes ───────────────────────────────────────────────────────────────────

    private val hexDigits = "0123456789abcdef"

    /** `\x` then two lowercase hex digits per byte.
      *
      * A byte column has no text rendering to pass through, so a form has to be chosen rather than found. No spelling is a literal on every
      * engine, so the prefix is kept for the one thing it buys: telling a rendered byte string from a rendered string.
      */
    def bytes(value: Array[Byte]): String =
        val sb = new StringBuilder(2 + value.length * 2)
        discard(sb.append("\\x"))
        var i = 0
        while i < value.length do
            val b = value(i) & 0xff
            discard(sb.append(hexDigits.charAt(b >>> 4)).append(hexDigits.charAt(b & 0x0f)))
            i += 1
        end while
        sb.toString
    end bytes

    // ── Array ───────────────────────────────────────────────────────────────────

    /** `{a,b,NULL}`, each element already rendered by its own kind.
      *
      * A composite kind needs a grammar rather than a spelling, and this one is fixed here for every backend, so an engine whose own array
      * syntax differs still renders this way. An absent element renders `NULL`, which the typed reads refuse because no Scala element type
      * holds one.
      */
    def array(elements: Chunk[Maybe[String]]): String =
        elements.map {
            case Maybe.Present(rendered) => quoteElement(rendered)
            case Maybe.Absent            => "NULL"
        }.mkString("{", ",", "}")

    /** Quoted where leaving it bare would change how the whole reads back: structural characters, whitespace, the empty string, and the
      * literal `NULL`.
      */
    private def quoteElement(value: String): String =
        val needsQuote =
            value.isEmpty || value.equalsIgnoreCase("NULL") ||
                value.exists(c => c == '{' || c == '}' || c == ',' || c == '"' || c == '\\' || c.isWhitespace)
        if !needsQuote then value
        else
            val sb = new StringBuilder(value.length + 2)
            discard(sb.append('"'))
            var i = 0
            while i < value.length do
                val c = value.charAt(i)
                if c == '"' || c == '\\' then discard(sb.append('\\'))
                discard(sb.append(c))
                i += 1
            end while
            sb.append('"').toString
        end if
    end quoteElement

    // ── The one entry point ─────────────────────────────────────────────────────

    /** The single entry point, so a decoded value has exactly one way to become a string. */
    def render(value: kyo.SqlValue): String =
        import kyo.SqlValue.*
        value match
            case Integer(v)            => integer(v)
            case Decimal(v)            => decimal(v)
            case Float4(v)             => float4(v)
            case Float8(v)             => float8(v)
            case NonFiniteNumber(kind) => nonFinite(kind)
            case Bool(v)               => bool(v)
            case Text(v)               => v
            case Json(v)               => v
            // `UUID.toString` is specified to emit the canonical lowercase hyphenated form, which is the rendering.
            case Uuid(v)                                   => v.toString
            case Date(y, m, d, bc)                         => date(y, m, d, bc)
            case Time(neg, h, mi, s, us)                   => time(neg, h, mi, s, us)
            case TimeWithOffset(h, mi, s, us, os)          => timeWithOffset(h, mi, s, us, os)
            case DateTime(y, mo, d, bc, h, mi, s, us)      => dateTime(y, mo, d, bc, h, mi, s, us)
            case Timestamp(epochSecond, us)                => timestamp(epochSecond, us)
            case TemporalInfinity(negative)                => if negative then "-infinity" else "infinity"
            case Interval(months, days, us)                => interval(months, days, us)
            case Bytes(v)                                  => bytes(v.toArray)
            case NetworkAddress(family, maskBits, address) => networkAddress(family, maskBits, address)
            case Elements(values)                          => array(values.map(_.map(render)))
            case ServerRendering(v)                        => v
        end match
    end render

    /** Spelled the way both engines' own output spells them, which is also what each accepts back as a literal. */
    def nonFinite(kind: kyo.SqlValue.NonFinite): String =
        kind match
            case kyo.SqlValue.NonFinite.NaN              => "NaN"
            case kyo.SqlValue.NonFinite.PositiveInfinity => "Infinity"
            case kyo.SqlValue.NonFinite.NegativeInfinity => "-Infinity"

    // ── Network address ─────────────────────────────────────────────────────────

    /** The address a wire struct describes. Here rather than in a backend because one IPv6 value has many legal spellings, and choosing among
      * them is a rendering decision. The mask is written only when it is not the full width.
      */
    def networkAddress(family: Int, maskBits: Int, address: Span[Byte]): String =
        if family == Ipv4Family then
            val quad = (0 until address.size).map(i => (address(i) & 0xff).toString).mkString(".")
            if maskBits == 32 then quad else s"$quad/$maskBits"
        else
            val groups   = (0 until 8).map(g => ((address(g * 2) & 0xff) << 8) | (address(g * 2 + 1) & 0xff))
            val rendered = compressIpv6(groups)
            if maskBits == 128 then rendered else s"$rendered/$maskBits"
        end if
    end networkAddress

    val Ipv4Family: Int = 2

    /** RFC 5952: lowercase, no leading zeros, longest run of zero groups replaced by `::`, leftmost on a tie, never a run of one. */
    private def compressIpv6(groups: IndexedSeq[Int]): String =
        var bestStart = -1
        var bestLen   = 0
        var i         = 0
        while i < 8 do
            if groups(i) != 0 then i += 1
            else
                var end = i
                while end < 8 && groups(end) == 0 do end += 1
                if end - i > bestLen then
                    bestLen = end - i
                    bestStart = i
                end if
                i = end
        end while
        def hex(from: Int, until: Int): String =
            (from until until).map(g => Integer.toHexString(groups(g))).mkString(":")
        if bestLen < 2 then hex(0, 8)
        else s"${hex(0, bestStart)}::${hex(bestStart + bestLen, 8)}"
    end compressIpv6

end SqlValueRender
