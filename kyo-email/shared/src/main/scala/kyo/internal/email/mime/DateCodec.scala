package kyo.internal.email.mime

import kyo.*
import kyo.internal.Ascii
import kyo.internal.mime.Grammar

/** The `Date` field (RFC 5322 section 3.3 with the obsolete forms of section 4.3), reading and writing.
  *
  * Reading takes an optional day name, with or without its comma and ignored even when wrong for the date, then a day, a month name in
  * any ASCII case, a year, `hh:mm` with optional seconds, and an optional zone, CFWS allowed between every two of them. A two-digit year
  * below 50 adds 2000 and any other two- or three-digit year adds 1900; a year of four to nine digits is read as written. Second 60 is read
  * as second 59, since an [[Instant]] has no leap seconds. A zone is `+hhmm` or `-hhmm`, or letters: `UT`, `GMT` and the eight North
  * American zones have their offsets, and every other alphabetic zone, the military ones included, is UTC, as is a date with no zone
  * (section 4.3). Anything but CFWS after the zone makes the date unreadable, as does a day that does not exist in its month and year.
  *
  * The epoch second is computed in integer arithmetic over the proleptic Gregorian calendar, so no zone database is involved.
  *
  * Writing gives `Wed, 01 Jan 2024 12:34:56 +0000`: UTC, the year with at least four digits, a fraction of a second dropped.
  */
private[kyo] object DateCodec:

    def parse(value: String): Maybe[Instant] =
        val c       = new Cursor(value)
        val dayName =
            if !c.atLetter then true
            else
                val name = c.letters()
                discard(c.take(','))
                DayNames.exists(Ascii.equalsIgnoreCase(_, name))
        if !dayName then Absent
        else
            for
                day    <- c.digits(1, 2)
                month  <- monthOf(c.letters())
                year   <- c.year()
                hour   <- c.digits(1, 2).filter(_ <= 23)
                minute <- if c.take(':') then c.digits(1, 2).filter(_ <= 59) else Absent
                second <- if c.take(':') then c.digits(1, 2).filter(_ <= 60).map(math.min(_, 59L)) else Present(0L)
                offset <- c.zone()
                if c.atEnd && day >= 1 && day <= daysIn(year, month)
            yield instantOf(daysFromCivil(year, month, day.toInt) * 86400L + hour * 3600L + minute * 60L + second - offset)
            end for
        end if
    end parse

    // Instant.of and toDuration go through a Duration, nanoseconds in a Long, which reaches only about 292 years either side of the
    // epoch; a date's year runs from 0000 to 9999 and beyond, so the epoch second goes through the java.time.Instant that kyo.Instant is.
    def instantOf(epochSecond: Long): Instant = Instant.fromJava(java.time.Instant.ofEpochSecond(epochSecond))

    def epochSecondOf(instant: Instant): Long = instant.toJava.getEpochSecond

    /** `instant` in UTC, or `DateOutOfRange` for an instant before year 0000. */
    def render(instant: Instant): Result[HeaderCodec.WriteFailure, String] =
        val epoch              = epochSecondOf(instant)
        val days               = Math.floorDiv(epoch, 86400L)
        val time               = Math.floorMod(epoch, 86400L).toInt
        val (year, month, day) = civilFromDays(days)
        if year < 0 then Result.fail(HeaderCodec.WriteFailure.DateOutOfRange)
        else
            val yearText = year.toString
            val out      = new java.lang.StringBuilder(27 + math.max(4, yearText.length))
            discard(out.append(DayNames(Math.floorMod(days + 4, 7L).toInt)).append(", "))
            discard(twoDigits(out, day).append(' ').append(MonthNames(month - 1)).append(' '))
            var pad = 4 - yearText.length
            while pad > 0 do
                discard(out.append('0'))
                pad -= 1
            discard(out.append(yearText).append(' '))
            discard(twoDigits(twoDigits(twoDigits(out, time / 3600).append(':'), time / 60 % 60).append(':'), time % 60))
            Result.succeed(out.append(" +0000").toString)
        end if
    end render

    // A read position over a date's text. Every read first skips CFWS, then advances past what it read or answers Absent; the sign and
    // digits of a numeric zone are read with no CFWS between them.
    final private class Cursor(text: String):
        private var at = 0

        private def skip(): Unit = at = Grammar.skipCfws(text, at)

        def atEnd: Boolean =
            skip()
            at >= text.length

        def atLetter: Boolean =
            skip()
            at < text.length && Ascii.isAlpha(text.charAt(at))

        def take(c: Char): Boolean =
            skip()
            if at < text.length && text.charAt(at) == c then
                at += 1
                true
            else false
            end if
        end take

        def letters(): String =
            skip()
            val start = at
            while at < text.length && Ascii.isAlpha(text.charAt(at)) do at += 1
            text.substring(start, at)
        end letters

        def digits(min: Int, max: Int): Maybe[Long] =
            skip()
            run(min, max)

        def year(): Maybe[Long] =
            skip()
            val start = at
            run(2, 9).map { value =>
                at - start match
                    case 2 => if value < 50 then 2000 + value else 1900 + value
                    case 3 => 1900 + value
                    case _ => value
            }
        end year

        // The offset in seconds, 0 when there is no zone.
        def zone(): Maybe[Long] =
            if atEnd then Present(0L)
            else if atLetter then
                val name = letters()
                Present(Maybe.fromOption(Zones.find((zone, _) => Ascii.equalsIgnoreCase(zone, name))).fold(0L)(_._2 * 3600L))
            else
                val sign = if take('+') then 1L else if take('-') then -1L else 0L
                if sign == 0L then Absent
                else run(4, 4).filter(_ % 100 <= 59).map(v => sign * (v / 100 * 3600 + v % 100 * 60))
            end if
        end zone

        // A run of `min` to `max` digits, read whole: a longer run is unreadable, not cut.
        private def run(min: Int, max: Int): Maybe[Long] =
            val start = at
            var value = 0L
            while at < text.length && Ascii.isDigit(text.charAt(at)) && at - start <= max do
                value = value * 10 + (text.charAt(at) - '0')
                at += 1
            val count = at - start
            if count >= min && count <= max then Present(value) else Absent
        end run
    end Cursor

    private val DayNames = Chunk("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    private[email] val MonthNames = Chunk("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private val Zones =
        Chunk(
            "UT"  -> 0,
            "GMT" -> 0,
            "EST" -> -5,
            "EDT" -> -4,
            "CST" -> -6,
            "CDT" -> -5,
            "MST" -> -7,
            "MDT" -> -6,
            "PST" -> -8,
            "PDT" -> -7
        )

    private[email] def monthOf(name: String): Maybe[Int] =
        val index = MonthNames.indexWhere(Ascii.equalsIgnoreCase(_, name))
        if index < 0 then Absent else Present(index + 1)

    private[email] def daysIn(year: Long, month: Int): Int =
        month match
            case 2              => if year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) then 29 else 28
            case 4 | 6 | 9 | 11 => 30
            case _              => 31

    // Days since 1970-01-01 of a proleptic Gregorian date, with the year counted from March so February's length does not matter.
    private[email] def daysFromCivil(year: Long, month: Int, day: Int): Long =
        val y   = if month <= 2 then year - 1 else year
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400
        val doy = (153 * ((month + 9) % 12) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        era * 146097 + doe - 719468
    end daysFromCivil

    private[email] def civilFromDays(days: Long): (Long, Int, Int) =
        val z     = days + 719468
        val era   = Math.floorDiv(z, 146097L)
        val doe   = z - era * 146097
        val yoe   = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy   = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp    = ((5 * doy + 2) / 153).toInt
        val day   = (doy - (153 * mp + 2) / 5 + 1).toInt
        val month = if mp < 10 then mp + 3 else mp - 9
        (yoe + era * 400 + (if month <= 2 then 1 else 0), month, day)
    end civilFromDays

    private def twoDigits(out: java.lang.StringBuilder, value: Int): java.lang.StringBuilder =
        out.append(('0' + value / 10).toChar).append(('0' + value % 10).toChar)

end DateCodec
