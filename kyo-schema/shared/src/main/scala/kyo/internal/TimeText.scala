package kyo.internal

import kyo.*
import scala.annotation.tailrec

/** The text forms of `java.time.Instant` and `java.time.Duration` every reader parses, so each codec and the `Structure.Value` reader
  * accept the same input on every platform. A failure is the reason, for the caller to raise as its own typed decode error.
  *
  * An instant is an RFC 3339 date-time: `YYYY-MM-DDTHH:MM:SS`, an optional fraction of 1 to 9 digits, then `Z` or a `+HH:MM` / `-HH:MM`
  * offset. `java.time.Instant.parse` cannot serve: scala-java-time, which backs it on JS, Native and Wasm, accepts only `Z`. `T` and `Z`
  * may be lower case (RFC 3339 section 5.6). The year may also take the sign and extra digits `Instant.toString` writes outside 0000 to
  * 9999, so every instant written reads back. A leap second, 23:59:60, reads as 23:59:59, as the JDK reads it.
  */
private[kyo] object TimeText:

    def instant(text: String): Result[String, java.time.Instant] =
        def digits(from: Int, count: Int): Long =
            if count <= 0 || from + count > text.length then -1
            else
                @tailrec def loop(i: Int, acc: Long): Long =
                    if i == from + count then acc
                    else
                        val c = text.charAt(i)
                        if c < '0' || c > '9' then -1 else loop(i + 1, acc * 10 + (c - '0'))
                loop(from, 0)
        def at(i: Int, c: Char): Boolean    = i < text.length && text.charAt(i) == c
        @tailrec def digitsEnd(i: Int): Int =
            if i < text.length && text.charAt(i) >= '0' && text.charAt(i) <= '9' then digitsEnd(i + 1) else i
        val yearSign   = if at(0, '-') then -1 else 1
        val yearStart  = if at(0, '-') || at(0, '+') then 1 else 0
        val yearEnd    = digitsEnd(yearStart)
        val yearDigits = yearEnd - yearStart
        val yearValid  = if yearStart == 0 then yearDigits == 4 else yearDigits >= 4 && yearDigits <= 9
        val year       = yearSign * digits(yearStart, yearDigits)
        // every later position is relative to the end of the year
        val p          = yearEnd
        val month      = digits(p + 1, 2)
        val day        = digits(p + 4, 2)
        val hour       = digits(p + 7, 2)
        val minute     = digits(p + 10, 2)
        val second     = digits(p + 13, 2)
        val separators =
            at(p, '-') && at(p + 3, '-') && (at(p + 6, 'T') || at(p + 6, 't')) && at(p + 9, ':') && at(p + 12, ':')
        val hasFraction    = at(p + 15, '.')
        val fractionStart  = p + 16
        val fractionEnd    = if hasFraction then digitsEnd(fractionStart) else p + 15
        val fractionDigits = if hasFraction then fractionEnd - fractionStart else 0
        val fraction       = if fractionDigits == 0 then 0L else digits(fractionStart, fractionDigits)
        // 0 for Z, 1 or -1 for a numeric offset, 2 for neither
        val offsetSign =
            if fractionEnd == text.length - 1 && (at(fractionEnd, 'Z') || at(fractionEnd, 'z')) then 0
            else if fractionEnd == text.length - 6 && at(fractionEnd + 3, ':') then
                if at(fractionEnd, '+') then 1 else if at(fractionEnd, '-') then -1 else 2
            else 2
        val offsetHours   = if offsetSign == 0 then 0L else digits(fractionEnd + 1, 2)
        val offsetMinutes = if offsetSign == 0 then 0L else digits(fractionEnd + 4, 2)
        val leapSecond    = hour == 23 && minute == 59 && second == 60
        val valid         =
            yearValid && separators && month >= 1 && month <= 12 && day >= 1 && day <= daysInMonth(year, month.toInt) &&
                hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59 && second >= 0 &&
                (second <= 59 || leapSecond) &&
                (!hasFraction || (fractionDigits >= 1 && fractionDigits <= 9 && fraction >= 0)) && offsetSign != 2 &&
                offsetHours >= 0 && offsetHours <= 23 && offsetMinutes >= 0 && offsetMinutes <= 59
        if !valid then Result.fail("not an RFC 3339 date-time, such as 2016-04-30T11:18:25.796Z or 2016-04-30T11:18:25+05:30")
        else
            @tailrec def scale(n: Long, digitsLeft: Int): Long = if digitsLeft == 0 then n else scale(n * 10, digitsLeft - 1)
            val nanos                                          = if fractionDigits == 0 then 0L else scale(fraction, 9 - fractionDigits)
            val offset                                         = offsetSign * (offsetHours * 3600 + offsetMinutes * 60)
            val seconds                                        = if leapSecond then 59 else second
            val epochSeconds = daysFromCivil(year, month.toInt, day.toInt) * 86400L + hour * 3600L + minute * 60L + seconds - offset
            Result.catching[java.time.DateTimeException](java.time.Instant.ofEpochSecond(epochSeconds, nanos))
                .mapFailure(_ => "outside the range of java.time.Instant")
        end if
    end instant

    /** An ISO 8601 duration, such as `PT1.5S` or `P2DT3H`, as `java.time.Duration.parse` reads it. */
    def duration(text: String): Result[String, java.time.Duration] =
        Result.catching[java.time.format.DateTimeParseException](java.time.Duration.parse(text))
            .mapFailure(_ => "not an ISO 8601 duration, such as PT1.5S or P2DT3H")

    private def daysInMonth(year: Long, month: Int): Int =
        month match
            case 2              => if (year % 4 == 0 && year % 100 != 0) || year % 400 == 0 then 29 else 28
            case 4 | 6 | 9 | 11 => 30
            case _              => 31

    /** Days from 1970-01-01 to the civil date, Howard Hinnant's `days_from_civil` over the proleptic Gregorian calendar. */
    private def daysFromCivil(year: Long, month: Int, day: Int): Long =
        val y   = if month <= 2 then year - 1 else year
        val era = (if y >= 0 then y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if month > 2 then month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        era * 146097 + doe - 719468
    end daysFromCivil

end TimeText
