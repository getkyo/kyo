package kyo.internal

import java.time.format.DateTimeParseException
import kyo.Instant
import kyo.Result
import kyo.discard

/** ISO-8601 text for [[kyo.Instant]] without java.time: exactly the output of `java.time.Instant.toString` and exactly the inputs
  * `java.time.Instant.parse` accepts, as JDK 25 defines them.
  *
  * Both directions follow the JDK's `DateTimeFormatterBuilder.InstantPrinterParser` step by step, including the corners a reimplementation
  * tends to drop: years past four digits carry a sign, `24:00:00` is the start of the next day, `23:59:60` reads as `23:59:59`, `T` and `Z`
  * match in either case, an offset converts to UTC, a fraction may have from zero to nine digits, and a rejected input reports the index the
  * JDK reports. `InstantJdkTest` compares both directions with the JDK over edge cases and generated inputs.
  *
  * The one deliberate difference is the message of an input that parses but lies outside the instant range: the JDK's names its internal
  * parse state, whose field order is not stable, so this one names the range instead. The index is the JDK's.
  */
private[kyo] object InstantText:

    inline val MinSecond = -31557014167219200L
    inline val MaxSecond = 31556889864403199L

    private inline val NanosPerSecond       = 1000000000L
    private inline val SecondsPerDay        = 86400L
    private inline val DaysPerCycle         = 146097L
    private inline val Days0000To1970       = 719528L
    private inline val Seconds0000To1970    = 62167219200L
    private inline val SecondsPer10000Years = 315569520000L
    private inline val MinEpochDay          = -365243219162L
    private inline val MaxEpochDay          = 365241780471L
    private inline val MaxYear              = 999999999L
    private inline val MaxOffsetSeconds     = 64800

    // --- Printing ---------------------------------------------------------------------------------------------------------------------

    /** `java.time.Instant.ofEpochSecond(seconds, nanos).toString`. */
    def format(seconds: Long, nanos: Int): String =
        val buf = new java.lang.StringBuilder(32)
        if seconds >= -Seconds0000To1970 then
            val zeroSecs = seconds - SecondsPer10000Years + Seconds0000To1970
            val hi       = Math.floorDiv(zeroSecs, SecondsPer10000Years) + 1
            val lo       = Math.floorMod(zeroSecs, SecondsPer10000Years)
            if hi > 0 then discard(buf.append('+').append(hi))
            discard(appendDateTime(buf, lo - Seconds0000To1970, nanos))
        else
            val zeroSecs = seconds + Seconds0000To1970
            val hi       = zeroSecs / SecondsPer10000Years
            val lo       = zeroSecs % SecondsPer10000Years
            val pos      = buf.length
            val year     = appendDateTime(buf, lo - Seconds0000To1970, nanos)
            if hi < 0 then
                if year == -10000 then discard(buf.replace(pos, pos + 2, java.lang.Long.toString(hi - 1)))
                else if lo == 0 then discard(buf.insert(pos, hi))
                else discard(buf.insert(pos + 1, Math.abs(hi)))
            end if
        end if
        buf.append('Z').toString
    end format

    /** Appends the UTC date and time of `epochSecond` as `LocalDateTime.toString` does, always with seconds, and returns the year. */
    private def appendDateTime(buf: java.lang.StringBuilder, epochSecond: Long, nano: Int): Long =
        val epochDay  = Math.floorDiv(epochSecond, SecondsPerDay)
        val secsOfDay = Math.floorMod(epochSecond, SecondsPerDay).toInt
        // LocalDate.ofEpochDay
        var zeroDay = epochDay + Days0000To1970 - 60
        var adjust  = 0L
        if zeroDay < 0 then
            val adjustCycles = (zeroDay + 1) / DaysPerCycle - 1
            adjust = adjustCycles * 400
            zeroDay += -adjustCycles * DaysPerCycle
        end if
        var yearEst = (400 * zeroDay + 591) / DaysPerCycle
        var doyEst  = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400)
        if doyEst < 0 then
            yearEst -= 1
            doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400)
        end if
        yearEst += adjust
        val marchDoy0   = doyEst.toInt
        val marchMonth0 = (marchDoy0 * 5 + 2) / 153
        val month       = if marchMonth0 + 3 > 12 then marchMonth0 - 9 else marchMonth0 + 3
        val day         = marchDoy0 - (marchMonth0 * 306 + 5) / 10 + 1
        val year        = if marchDoy0 >= 306 then yearEst + 1 else yearEst
        // LocalDate.toString
        val absYear = Math.abs(year)
        if absYear < 1000 then
            if year < 0 then discard(buf.append('-'))
            discard(buf.append(if absYear < 10 then "000" else if absYear < 100 then "00" else "0").append(absYear))
        else
            if year > 9999 then discard(buf.append('+'))
            discard(buf.append(year))
        end if
        discard(buf.append(if month < 10 then "-0" else "-").append(month).append(if day < 10 then "-0" else "-").append(day))
        // LocalTime.toString, with the seconds InstantPrinterParser always prints
        val hour   = secsOfDay / 3600
        val minute = (secsOfDay / 60) % 60
        val second = secsOfDay % 60
        discard(buf.append('T').append(if hour < 10 then "0" else "").append(hour).append(if minute < 10 then ":0" else ":").append(minute))
        discard(buf.append(if second < 10 then ":0" else ":").append(second))
        if nano > 0 then
            discard(buf.append('.'))
            if nano % 1000000 == 0 then appendPadded(buf, nano / 1000000, 3)
            else if nano % 1000 == 0 then appendPadded(buf, nano / 1000, 6)
            else appendPadded(buf, nano, 9)
        end if
        year
    end appendDateTime

    private def appendPadded(buf: java.lang.StringBuilder, value: Int, width: Int): Unit =
        val digits = java.lang.Integer.toString(value)
        var i      = digits.length
        while i < width do
            discard(buf.append('0'))
            i += 1
        discard(buf.append(digits))
    end appendPadded

    // --- Parsing ----------------------------------------------------------------------------------------------------------------------

    /** `java.time.Instant.parse(text)`, with its exception, message, and error index. */
    def parse(text: CharSequence): Result[DateTimeParseException, Instant] =
        parse(text, secondsOptional = false)

    /** With `secondsOptional`, an input may also stop at the minutes (`2024-01-01T10:15Z`), as `OffsetDateTime.parse` allows. Flag reading
      * accepts that form; it is otherwise the same parse, and an input without seconds cannot use `24:00`.
      */
    def parse(text: CharSequence, secondsOptional: Boolean): Result[DateTimeParseException, Instant] =
        val parser = new Parser(text, secondsOptional)
        val end    = parser.parse()
        if parser.outOfRangeOffset then Result.fail(unresolved(text, "Value out of range: Hour[0-23], Minute[0-59], Second[0-59]"))
        else if end < 0 then Result.fail(errorAt(text, ~end))
        else if end < text.length then Result.fail(unparsedAt(text, end))
        else if parser.instantSeconds < MinSecond || parser.instantSeconds > MaxSecond then
            Result.fail(unresolved(text, "Instant exceeds minimum or maximum instant"))
        else Result.succeed(Instant.fromEpoch(parser.instantSeconds, parser.nano))
        end if
    end parse

    private def abbreviate(text: CharSequence): String =
        if text.length > 64 then s"${text.subSequence(0, 64)}..." else text.toString

    private def errorAt(text: CharSequence, index: Int): DateTimeParseException =
        new DateTimeParseException(s"Text '${abbreviate(text)}' could not be parsed at index $index", text, index)

    private def unparsedAt(text: CharSequence, index: Int): DateTimeParseException =
        new DateTimeParseException(s"Text '${abbreviate(text)}' could not be parsed, unparsed text found at index $index", text, index)

    private def unresolved(text: CharSequence, reason: String): DateTimeParseException =
        new DateTimeParseException(s"Text '${abbreviate(text)}' could not be parsed: $reason", text, 0)

    /** One parse. Each step returns the position after what it read, or the bitwise complement of the index it rejected, as the JDK's
      * printer-parsers do, and a failed step ends the parse.
      */
    final private class Parser(text: CharSequence, secondsOptional: Boolean):
        private val length = text.length

        private var year: Long = 0L
        private var month      = 0
        private var day        = 0
        private var hour       = 0
        private var minute     = 0
        private var second     = 0
        private var hasSeconds = true
        private var offset     = 0
        private var lastValue  = 0
        var nano: Int          = 0
        var instantSeconds     = 0L
        var outOfRangeOffset   = false

        def parse(): Int =
            var pos = parseYear(0)
            if pos >= 0 then pos = parseLiteral(pos, '-')
            if pos >= 0 then
                pos = parseTwoDigits(pos)
                month = lastValue
            if pos >= 0 then pos = parseLiteral(pos, '-')
            if pos >= 0 then
                pos = parseTwoDigits(pos)
                day = lastValue
            if pos >= 0 then pos = parseLiteral(pos, 'T')
            if pos >= 0 then
                pos = parseTwoDigits(pos)
                hour = lastValue
            if pos >= 0 then pos = parseLiteral(pos, ':')
            if pos >= 0 then
                pos = parseTwoDigits(pos)
                minute = lastValue
            if pos >= 0 then pos = parseSeconds(pos)
            if pos >= 0 && hasSeconds then pos = parseFraction(pos)
            if pos >= 0 then pos = parseOffset(pos)
            if pos >= 0 && !outOfRangeOffset && !resolve() then ~0
            else pos
        end parse

        private def digit(c: Char): Int =
            if c >= '0' && c <= '9' then c - '0' else -1

        private def equalsIgnoreCase(c: Char, expected: Char): Boolean =
            c == expected || Character.toUpperCase(c) == Character.toUpperCase(expected) ||
                Character.toLowerCase(c) == Character.toLowerCase(expected)

        /** YEAR: four to ten digits; a sign is required past four digits and allowed before exactly four only when negative and not zero. */
        private def parseYear(position: Int): Int =
            if position == length then ~position
            else
                val sign     = text.charAt(position)
                val positive = sign == '+'
                val negative = sign == '-'
                val start    = if positive || negative then position + 1 else position
                val minEnd   = start + 4
                if minEnd > length then ~start
                else
                    val maxEnd = Math.min(start + 10, length)
                    var total  = 0L
                    var pos    = start
                    while pos < maxEnd && digit(text.charAt(pos)) >= 0 do
                        total = total * 10 + digit(text.charAt(pos))
                        pos += 1
                    val parsedLength = pos - start
                    if parsedLength < 4 then ~start
                    else if negative && total == 0 then ~(start - 1)
                    else if positive && parsedLength <= 4 then ~(start - 1)
                    else if !positive && !negative && parsedLength > 4 then ~start
                    else
                        year = if negative then -total else total
                        pos
                    end if
                end if
            end if
        end parseYear

        /** A two-digit field with no sign. */
        private def parseTwoDigits(position: Int): Int =
            if position + 2 > length then ~position
            else
                val d1 = digit(text.charAt(position))
                val d2 = digit(text.charAt(position + 1))
                if d1 < 0 || d2 < 0 then ~position
                else
                    lastValue = d1 * 10 + d2
                    position + 2
                end if
            end if
        end parseTwoDigits

        private def parseLiteral(position: Int, literal: Char): Int =
            if position == length || !equalsIgnoreCase(text.charAt(position), literal) then ~position
            else position + 1

        private def parseSeconds(position: Int): Int =
            val afterColon = parseLiteral(position, ':')
            val pos        = if afterColon >= 0 then parseTwoDigits(afterColon) else afterColon
            if pos >= 0 then
                second = lastValue
                pos
            else if secondsOptional then
                hasSeconds = false
                position
            else pos
            end if
        end parseSeconds

        /** An optional `.` and zero to nine digits. */
        private def parseFraction(position: Int): Int =
            if position == length || text.charAt(position) != '.' then position
            else
                val start  = position + 1
                val maxEnd = Math.min(start + 9, length)
                var total  = 0
                var pos    = start
                while pos < maxEnd && digit(text.charAt(pos)) >= 0 do
                    total = total * 10 + digit(text.charAt(pos))
                    pos += 1
                var scale = pos - start
                while scale < 9 do
                    total *= 10
                    scale += 1
                nano = total
                pos
            end if
        end parseFraction

        /** `Z` in either case, or `+HH:MM` with optional `:ss`. An hour from 24 to 59 is read and then rejected as out of range. */
        private def parseOffset(position: Int): Int =
            if position == length then ~position
            else if equalsIgnoreCase(text.charAt(position), 'Z') then position + 1
            else
                val sign = text.charAt(position)
                if sign != '+' && sign != '-' then ~position
                else
                    var pos     = position + 1
                    var hours   = 0
                    var minutes = 0
                    var seconds = 0
                    val h       = offsetDigits(pos, colon = false)
                    if h < 0 then ~position
                    else
                        hours = h
                        pos += 2
                        val m = offsetDigits(pos, colon = true)
                        if m < 0 then ~position
                        else
                            minutes = m
                            pos += 3
                            val s = offsetDigits(pos, colon = true)
                            if s >= 0 then
                                seconds = s
                                pos += 3
                            if hours > 23 then
                                outOfRangeOffset = true
                                pos
                            else
                                val total = hours * 3600 + minutes * 60 + seconds
                                offset = if sign == '-' then -total else total
                                pos
                            end if
                        end if
                    end if
                end if
            end if
        end parseOffset

        /** Two digits from 00 to 59 at `position`, preceded by `:` when `colon`, or -1. */
        private def offsetDigits(position: Int, colon: Boolean): Int =
            val start = if colon then position + 1 else position
            if (colon && (position + 1 > length || text.charAt(position) != ':')) || start + 2 > length then -1
            else
                val d1 = digit(text.charAt(start))
                val d2 = digit(text.charAt(start + 1))
                if d1 < 0 || d2 < 0 || d1 * 10 + d2 > 59 then -1
                else d1 * 10 + d2
            end if
        end offsetDigits

        /** The JDK's validation after a successful read: builds the local date-time, applies `24:00` and the leap second, and converts with
          * the offset. False where the JDK throws, which it reports at index 0.
          */
        private def resolve(): Boolean =
            if !hasSeconds then resolveWithoutSeconds()
            else
                var days = 0
                if hour == 24 && minute == 0 && second == 0 && nano == 0 then
                    hour = 0
                    days = 1
                else if hour == 23 && minute == 59 && second == 60 then second = 59
                end if
                // As the JDK computes it: the year within its 10,000-year cycle, then the cycles.
                val cycleYear = (year.toInt % 10000).toLong
                if !validLocal(cycleYear) then false
                else
                    val epochDay = toEpochDay(cycleYear, month, day) + days
                    if epochDay < MinEpochDay || epochDay > MaxEpochDay then false
                    else
                        instantSeconds = epochDay * SecondsPerDay + hour * 3600 + minute * 60 + second - offset +
                            (year / 10000) * SecondsPer10000Years
                        true
                    end if
                end if
            end if
        end resolve

        /** The `OffsetDateTime.parse` rules for a time without seconds: the whole year must be a `LocalDate` year and `24:00` is not a time. */
        private def resolveWithoutSeconds(): Boolean =
            if Math.abs(year) > MaxYear || !validLocal(year) then false
            else
                instantSeconds = toEpochDay(year, month, day) * SecondsPerDay + hour * 3600 + minute * 60 - offset
                true

        private def validLocal(localYear: Long): Boolean =
            month >= 1 && month <= 12 && day >= 1 && day <= monthLength(localYear, month) &&
                hour <= 23 && minute <= 59 && second <= 59 && Math.abs(offset) <= MaxOffsetSeconds

        private def isLeap(year: Long): Boolean =
            (year & 3) == 0 && (year % 100 != 0 || year % 400 == 0)

        private def monthLength(year: Long, month: Int): Int =
            month match
                case 2           => if isLeap(year) then 29 else 28
                case 4 | 6 | 9 | 11 => 30
                case _           => 31

        /** `LocalDate.toEpochDay`. */
        private def toEpochDay(year: Long, month: Int, day: Int): Long =
            var total = 365 * year
            if year >= 0 then total += (year + 3) / 4 - (year + 99) / 100 + (year + 399) / 400
            else total -= year / -4 - year / -100 + year / -400
            total += (367 * month - 362) / 12
            total += day - 1
            if month > 2 then
                total -= 1
                if !isLeap(year) then total -= 1
            end if
            total - Days0000To1970
        end toEpochDay
    end Parser
end InstantText
