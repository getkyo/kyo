package kyo.internal

import kyo.Absent
import kyo.Instant
import kyo.Maybe

/** The HTTP-date of RFC 9110 section 5.6.7, in both directions.
  *
  * A timestamp on the wire is UTC and carries fixed English field names, so neither direction needs a calendar library or a locale:
  * [[Civil]] does the arithmetic and the day and month names are written out. That matters off the JVM, where java.time is a linked
  * dependency rather than the platform: a `DateTimeFormatter` names `java.util.Locale`, which loads a locale database into every program
  * that reaches it, a client that only reads a `Retry-After` header included.
  *
  * [[format]] writes the IMF-fixdate, the one form a sender may produce. [[parse]] reads all three forms a recipient must accept.
  */
private[kyo] object HttpDate:

    /** The day and month names an HTTP-date carries. RFC 9110 section 5.6.7 fixes them to these English abbreviations for every sender in
      * every locale, which is what lets them be written out and matched literally.
      */
    private val dayNames   = Array("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val monthNames =
        Array("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** The IMF-fixdate of an epoch second, as RFC 9110 section 5.6.7 requires it: `Sun, 06 Nov 1994 08:49:37 GMT`. */
    def format(epochSecond: Long): String =
        val civil = Civil.of(epochSecond)
        val sb    = new java.lang.StringBuilder(29)
        sb.append(dayNames(civil.dayOfWeek)).append(", ")
        appendPadded(sb, civil.day, 2).append(' ')
        sb.append(monthNames(civil.month - 1)).append(' ')
        appendPadded(sb, civil.year, 4).append(' ')
        appendPadded(sb, civil.hour, 2).append(':')
        appendPadded(sb, civil.minute, 2).append(':')
        appendPadded(sb, civil.second, 2).append(" GMT")
        sb.toString
    end format

    /** The instant an HTTP-date names, or `Absent` when the text is none of the three forms RFC 9110 section 5.6.7 defines: the
      * IMF-fixdate `Sun, 06 Nov 1994 08:49:37 GMT`, the obsolete RFC 850 `Sunday, 06-Nov-94 08:49:37 GMT`, and the asctime
      * `Sun Nov  6 08:49:37 1994`.
      *
      * `now` resolves the two-digit year of the RFC 850 form: a year that would land more than 50 years after `now` reads as the most
      * recent past year ending in those digits, which is the rule section 5.6.7 gives. The other two forms carry a full year and ignore
      * it.
      *
      * The day-of-week name is redundant with the date, so it is accepted without being checked against it, and a zone other than `GMT`
      * is rejected rather than converted: an HTTP-date is UTC by definition.
      *
      * Absent rather than an exception because every caller is reading a header it did not write, where a malformed value is input rather
      * than a bug.
      */
    def parse(text: String, now: Instant): Maybe[Instant] =
        val trimmed = text.trim
        val comma   = trimmed.indexOf(',')
        if comma < 0 then parseAsctime(trimmed)
        else parseDated(trimmed.substring(comma + 1), now)
    end parse

    /** The two comma-bearing forms, whose day-name prefix the caller has already dropped. Both spell the date before the time and end in
      * the zone, and the RFC 850 form differs only in separating its fields with `-` and carrying two year digits, so replacing the
      * separator reduces it to the same five fields.
      */
    private def parseDated(afterDayName: String, now: Instant): Maybe[Instant] =
        val fields = split(afterDayName.replace('-', ' '))
        if fields.length != 5 || fields(4) != "GMT" then Absent
        else instantOf(yearOf(fields(2), now), monthOf(fields(1)), number(fields(0), 1, 2), fields(3))
    end parseDated

    /** The asctime form, which names the day of the week without a comma, spells the day of the month space-padded rather than
      * zero-padded, puts the year last, and states no zone.
      */
    private def parseAsctime(text: String): Maybe[Instant] =
        val fields = split(text)
        if fields.length != 5 then Absent
        else instantOf(number(fields(4), 4, 4), monthOf(fields(1)), number(fields(2), 1, 2), fields(3))
    end parseAsctime

    /** The instant of a date and an `HH:MM:SS` field, or `Absent` when any field is malformed or the whole names no real time. A negative
      * year, month or day is the failure the readers below report.
      */
    private def instantOf(year: Int, month: Int, day: Int, time: String): Maybe[Instant] =
        if year < 0 || month < 0 || day < 0 then Absent
        else if time.length != 8 || time.charAt(2) != ':' || time.charAt(5) != ':' then Absent
        else
            val hour   = number(time.substring(0, 2), 2, 2)
            val minute = number(time.substring(3, 5), 2, 2)
            val second = number(time.substring(6, 8), 2, 2)
            if hour < 0 || minute < 0 || second < 0 then Absent
            else Civil.epochSecondOf(year, month, day, hour, minute, second).map(s => Instant.ofEpochSecond(s))
    end instantOf

    /** The year a year field names, or -1 when it is not one. Four digits are the year itself; two are the RFC 850 form, centered on
      * `now` so that the most recent past year wins over one more than 50 years ahead.
      */
    private def yearOf(field: String, now: Instant): Int =
        if field.length == 4 then number(field, 4, 4)
        else if field.length != 2 then -1
        else
            val twoDigits = number(field, 2, 2)
            if twoDigits < 0 then -1
            else
                val nowYear     = Civil.of(now.epochSecond).year
                val sameCentury = nowYear - Math.floorMod(nowYear, 100) + twoDigits
                if sameCentury > nowYear + 50 then sameCentury - 100 else sameCentury
            end if
    end yearOf

    /** The month a month field names, 1 to 12, or -1 when it is not one of the twelve names. */
    private def monthOf(field: String): Int =
        var i = 0
        while i < monthNames.length && monthNames(i) != field do i += 1
        if i < monthNames.length then i + 1 else -1
    end monthOf

    /** The non-negative number a field of between `minDigits` and `maxDigits` decimal digits names, or -1 when it is anything else. The
      * forms here have no sign and no field wider than four digits, so the result always fits an `Int` and -1 is unreachable from input.
      */
    private def number(field: String, minDigits: Int, maxDigits: Int): Int =
        if field.length < minDigits || field.length > maxDigits then -1
        else
            var value = 0
            var i     = 0
            var ok    = true
            while ok && i < field.length do
                val c = field.charAt(i)
                if c < '0' || c > '9' then ok = false
                else
                    value = value * 10 + (c - '0')
                    i += 1
                end if
            end while
            if ok then value else -1
    end number

    /** The fields of `text` separated by runs of spaces, which is what the asctime form's space-padded day of the month needs. */
    private def split(text: String): Array[String] =
        text.split(' ').filter(_.nonEmpty)

    /** Appends `value` right-aligned in `width` digits, zero-padded, which is the fixed width every IMF-fixdate field has. */
    private def appendPadded(sb: java.lang.StringBuilder, value: Int, width: Int): java.lang.StringBuilder =
        val text = Integer.toString(value)
        var pad  = width - text.length
        while pad > 0 do
            sb.append('0')
            pad -= 1
        sb.append(text)
    end appendPadded

end HttpDate
