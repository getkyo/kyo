package kyo.internal.server

import kyo.discard

/** The IMF-fixdate of RFC 9110 section 5.6.7 (`Sun, 06 Nov 1994 08:49:37 GMT`), the form of the `Date` header every response carries.
  *
  * Computed from epoch seconds with the proleptic Gregorian civil-from-days conversion rather than a `java.time` formatter. On Scala Native
  * the first `java.time` format initializes its locale and zone data, measured at 1021 ms cold against 0.7 ms warm, and that allocation
  * set off collections that stalled the first response of every server process past a 5 s client timeout under parallel suites.
  */
private[kyo] object HttpDate:

    // Epoch day 0, 1970-01-01, was a Thursday.
    private val DayNames   = Array("Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed")
    private val MonthNames = Array("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** The IMF-fixdate of `epochSecond`, in GMT. */
    def format(epochSecond: Long): String =
        val epochDay    = Math.floorDiv(epochSecond, 86400L)
        val secondOfDay = Math.floorMod(epochSecond, 86400L).toInt
        // Civil from days over 400-year eras of 146097 days, with years starting on March 1 so the leap day is last.
        val shifted    = epochDay + 719468L
        val era        = Math.floorDiv(shifted, 146097L)
        val dayOfEra   = (shifted - era * 146097L).toInt
        val yearOfEra  = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
        val dayOfYear  = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val monthIndex = (5 * dayOfYear + 2) / 153
        val dayOfMonth = dayOfYear - (153 * monthIndex + 2) / 5 + 1
        val month      = if monthIndex < 10 then monthIndex + 3 else monthIndex - 9
        val year       = yearOfEra + era * 400 + (if month <= 2 then 1 else 0)
        val out        = new java.lang.StringBuilder(29)
        out.append(DayNames(Math.floorMod(epochDay, 7L).toInt)).append(", ")
        pad(out, dayOfMonth, 2)
        out.append(' ').append(MonthNames(month - 1)).append(' ')
        pad(out, year, 4)
        out.append(' ')
        pad(out, secondOfDay / 3600, 2)
        out.append(':')
        pad(out, secondOfDay / 60 % 60, 2)
        out.append(':')
        pad(out, secondOfDay % 60, 2)
        out.append(" GMT").toString
    end format

    private def pad(out: java.lang.StringBuilder, value: Long, width: Int): Unit =
        val digits = value.toString
        discard(out.append("0" * (width - digits.length)).append(digits))

end HttpDate
