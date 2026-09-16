package kyo.internal

import kyo.Absent
import kyo.Maybe
import kyo.Present

/** The UTC calendar fields of a point in time, and the point in time of a set of calendar fields.
  *
  * A UTC timestamp has no zone rules and no locale in it, so nothing here needs a date-time library. That matters off the JVM, where
  * `java.time` is a linked dependency rather than the platform: a `ZonedDateTime` reaches `IsoChronology`, which names
  * `DateTimeFormatter`, which names `java.util.Locale`, which loads a locale database. Any code that only wants to know which day a second
  * falls on pays all of it.
  *
  * Kept together rather than copied into each caller: the HTTP `Date` header, Ion's binary timestamp and the ISO-8601 text of
  * [[kyo.Instant]] all ask the same question.
  *
  * @param year
  *   The proleptic Gregorian year, negative before year 1.
  * @param month
  *   The month of the year, 1 to 12.
  * @param day
  *   The day of the month, 1 to 31.
  * @param hour
  *   The hour of the day, 0 to 23.
  * @param minute
  *   The minute of the hour, 0 to 59.
  * @param second
  *   The second of the minute, 0 to 59.
  * @param dayOfWeek
  *   The day of the week, 0 for Monday through 6 for Sunday.
  */
final private[kyo] case class Civil(
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int,
    dayOfWeek: Int
)

private[kyo] object Civil:

    private inline val SecondsPerDay = 86400L

    /** The day number of 0000-03-01 counted from 1970-01-01, which is where the shifted era below starts. */
    private inline val EraShift = 719468L

    /** Days in one 400-year Gregorian cycle, exactly. */
    private inline val DaysPerEra = 146097L

    /** The calendar fields of a second counted from 1970-01-01T00:00:00Z, negative before it. */
    def of(epochSecond: Long): Civil =
        val epochDay        = Math.floorDiv(epochSecond, SecondsPerDay)
        val secondOfDay     = Math.floorMod(epochSecond, SecondsPerDay).toInt
        val shifted         = epochDay + EraShift
        val era             = Math.floorDiv(shifted, DaysPerEra)
        val dayOfEra        = shifted - era * DaysPerEra                                                    // [0, 146096]
        val yearOfEra       = (dayOfEra - dayOfEra / 1460L + dayOfEra / 36524L - dayOfEra / 146096L) / 365L // [0, 399]
        val dayOfYear       = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)             // [0, 365], from 1 March
        val monthsFromMarch = (5L * dayOfYear + 2L) / 153L                                                  // [0, 11]
        val day             = (dayOfYear - (153L * monthsFromMarch + 2L) / 5L + 1L).toInt                   // [1, 31]
        val month           = (if monthsFromMarch < 10L then monthsFromMarch + 3L else monthsFromMarch - 9L).toInt
        val year            = (yearOfEra + era * 400L + (if month <= 2 then 1L else 0L)).toInt
        Civil(
            year = year,
            month = month,
            day = day,
            hour = secondOfDay / 3600,
            minute = (secondOfDay / 60) % 60,
            second = secondOfDay        % 60,
            // Epoch day 0 is a Thursday, which is index 3 counting Monday as 0.
            dayOfWeek = Math.floorMod(epochDay + 3L, 7L).toInt
        )
    end of

    /** The second counted from 1970-01-01T00:00:00Z of a set of UTC calendar fields, or `Absent` when they do not name a real time.
      *
      * Absent rather than an exception because every caller is parsing something it did not write, where invalid fields are input rather
      * than a bug.
      */
    def epochSecondOf(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Maybe[Long] =
        if month < 1 || month > 12 || day < 1 || day > daysInMonth(year, month) ||
            hour < 0 || hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 59
        then Absent
        else
            val shiftedYear     = if month <= 2 then year - 1L else year.toLong
            val era             = Math.floorDiv(shiftedYear, 400L)
            val yearOfEra       = shiftedYear - era * 400L                                         // [0, 399]
            val monthsFromMarch = if month > 2 then month - 3L else month + 9L                     // [0, 11]
            val dayOfYear       = (153L * monthsFromMarch + 2L) / 5L + day - 1L                    // [0, 365]
            val dayOfEra        = yearOfEra * 365L + yearOfEra / 4L - yearOfEra / 100L + dayOfYear // [0, 146096]
            val epochDay        = era * DaysPerEra + dayOfEra - EraShift
            Present(epochDay * SecondsPerDay + hour * 3600L + minute * 60L + second)
        end if
    end epochSecondOf

    /** The number of days in a month of a proleptic Gregorian year. */
    def daysInMonth(year: Int, month: Int): Int =
        month match
            case 1 | 3 | 5 | 7 | 8 | 10 | 12 => 31
            case 4 | 6 | 9 | 11              => 30
            case 2                           => if isLeapYear(year) then 29 else 28
            case _                           => 0

    /** Whether a proleptic Gregorian year carries a leap day: every fourth, except centuries, except every fourth century. */
    def isLeapYear(year: Int): Boolean =
        (year % 4 == 0) && (year % 100 != 0 || year % 400 == 0)

end Civil
