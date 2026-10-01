package kyo

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import kyo.internal.Ascii

/** An HTTP date (RFC 9110 section 5.6.7): the IMF-fixdate a sender writes, `Sun, 06 Nov 1994 08:49:37 GMT`, and the two obsolete forms a
  * recipient must also accept, RFC 850 (`Sunday, 06-Nov-94 08:49:37 GMT`) and asctime (`Sun Nov  6 08:49:37 1994`).
  *
  * `parse` reads the three forms with OWS around them. The names are case-insensitive here, as they are for the JDK's RFC 1123 parser and
  * for browsers, though the RFC writes them in one case; `render` writes the canonical case. RFC 850's two-digit year is read the way RFC
  * 6265 section 5.1.1 fixes it: `70` to `99` are 1970 to 1999, `00` to `69` are 2000 to 2069. The day name is checked to be a day name,
  * not against the date. A leap second, `23:59:60`, is the second after `23:59:59`. A date that does not exist, `31 Feb`, is `Absent`.
  *
  * Every form carries a four-digit year, so an HTTP date names an instant from 0000-01-01 to 9999-12-31; `render` has no text for an
  * instant outside that range, and `parse(render(i))` is `i` at second precision for every instant inside it.
  */
object HttpDate:

    private val DayNames     = Array("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val LongDayNames = Array("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val MonthNames   = Array("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private val FirstSecond = LocalDateTime.of(0, 1, 1, 0, 0, 0).toEpochSecond(ZoneOffset.UTC)
    private val LastSecond  = LocalDateTime.of(9999, 12, 31, 23, 59, 59).toEpochSecond(ZoneOffset.UTC)

    /** The instant `text` names, in any of the three forms with OWS around it; `Absent` for anything else. */
    def parse(text: String): Maybe[Instant] =
        var from = 0
        var to   = text.length
        while from < to && isOws(text.charAt(from)) do from += 1
        while to > from && isOws(text.charAt(to - 1)) do to -= 1
        val t     = text.substring(from, to)
        val comma = t.indexOf(',')
        if comma == 3 then parseImfFixdate(t)
        else if comma > 3 then parseRfc850(t, comma)
        else if comma < 0 then parseAsctime(t)
        else Absent
        end if
    end parse

    /** `instant` as an IMF-fixdate, at second precision, always in `GMT`; `Absent` for an instant outside years 0000 to 9999. */
    def render(instant: Instant): Maybe[String] =
        val second = instant.toJava.getEpochSecond
        if second < FirstSecond || second > LastSecond then Absent
        else
            val dt = LocalDateTime.ofEpochSecond(second, 0, ZoneOffset.UTC)
            val sb = new StringBuilder(29)
            discard(sb.append(DayNames(dt.getDayOfWeek.getValue % 7)).append(", "))
            discard(twoDigits(sb, dt.getDayOfMonth).append(' ').append(MonthNames(dt.getMonthValue - 1)).append(' '))
            val year = dt.getYear
            discard(twoDigits(sb, year / 100))
            discard(twoDigits(sb, year % 100).append(' '))
            discard(twoDigits(sb, dt.getHour).append(':'))
            discard(twoDigits(sb, dt.getMinute).append(':'))
            discard(twoDigits(sb, dt.getSecond).append(" GMT"))
            Present(sb.toString)
        end if
    end render

    // IMF-fixdate: day-name "," SP 2DIGIT SP month SP 4DIGIT SP time-of-day SP "GMT", 29 characters at fixed positions.
    private def parseImfFixdate(t: String): Maybe[Instant] =
        if t.length != 29 || nameAt(DayNames, t, 0, 3) < 0 || t.charAt(4) != ' ' || t.charAt(7) != ' ' || t.charAt(11) != ' ' ||
            t.charAt(16) != ' ' || t.charAt(25) != ' ' || !gmtAt(t, 26)
        then Absent
        else instant(digits(t, 12, 4), nameAt(MonthNames, t, 8, 3) + 1, digits(t, 5, 2), t, 17)

    // rfc850-date: day-name-l "," SP 2DIGIT "-" month "-" 2DIGIT SP time-of-day SP "GMT"; the day name's length varies.
    private def parseRfc850(t: String, comma: Int): Maybe[Instant] =
        val p = comma + 2
        if nameAt(LongDayNames, t, 0, comma) < 0 || t.length != p + 22 || t.charAt(comma + 1) != ' ' || t.charAt(p + 2) != '-' ||
            t.charAt(p + 6) != '-' || t.charAt(p + 9) != ' ' || t.charAt(p + 18) != ' ' || !gmtAt(t, p + 19)
        then Absent
        else
            val yy   = digits(t, p + 7, 2)
            val year = if yy < 0 then -1 else if yy >= 70 then 1900 + yy else 2000 + yy
            instant(year, nameAt(MonthNames, t, p + 3, 3) + 1, digits(t, p, 2), t, p + 10)
        end if
    end parseRfc850

    // asctime-date: day-name SP month SP ( 2DIGIT / ( SP 1DIGIT ) ) SP time-of-day SP 4DIGIT, 24 characters at fixed positions.
    private def parseAsctime(t: String): Maybe[Instant] =
        if t.length != 24 || nameAt(DayNames, t, 0, 3) < 0 || t.charAt(3) != ' ' || t.charAt(7) != ' ' || t.charAt(10) != ' ' ||
            t.charAt(19) != ' '
        then Absent
        else
            val day = if t.charAt(8) == ' ' then digits(t, 9, 1) else digits(t, 8, 2)
            instant(digits(t, 20, 4), nameAt(MonthNames, t, 4, 3) + 1, day, t, 11)

    // The instant of a civil date and the HH:MM:SS at `timeAt`; a negative or out-of-range part, or a date that does not exist, is Absent.
    private def instant(year: Int, month: Int, day: Int, t: String, timeAt: Int): Maybe[Instant] =
        val hour   = digits(t, timeAt, 2)
        val minute = digits(t, timeAt + 3, 2)
        val second = digits(t, timeAt + 6, 2)
        if year < 0 || month < 1 || day < 1 || t.charAt(timeAt + 2) != ':' || t.charAt(timeAt + 5) != ':' ||
            hour < 0 || hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 60
        then Absent
        else
            try
                val base = LocalDateTime.of(year, month, day, hour, minute, math.min(second, 59)).toEpochSecond(ZoneOffset.UTC)
                Present(Instant.fromJava(java.time.Instant.ofEpochSecond(if second == 60 then base + 1 else base)))
            catch case _: DateTimeException => Absent
        end if
    end instant

    private def isOws(c: Char): Boolean = c == ' ' || c == '\t'

    // The value of `count` ASCII digits at `at`, or -1.
    private def digits(t: String, at: Int, count: Int): Int =
        if at + count > t.length then -1
        else
            var i   = 0
            var acc = 0
            while i < count && acc >= 0 do
                val c = t.charAt(at + i)
                acc = if Ascii.isDigit(c) then acc * 10 + (c - '0') else -1
                i += 1
            end while
            acc
    end digits

    // The index of the name `t` spells at `at` over `len` characters, ASCII case-insensitively, or -1.
    private def nameAt(names: Array[String], t: String, at: Int, len: Int): Int =
        if at + len > t.length then -1
        else
            val spelled = t.substring(at, at + len)
            names.indexWhere(name => Ascii.equalsIgnoreCase(name, spelled))

    private def gmtAt(t: String, at: Int): Boolean =
        at + 3 == t.length && Ascii.equalsIgnoreCase(t.substring(at), "GMT")

    private def twoDigits(sb: StringBuilder, n: Int): StringBuilder =
        if n < 10 then sb.append('0')
        sb.append(n)

end HttpDate
