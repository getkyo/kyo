package kyo.internal

import java.time.Instant
import java.time.ZonedDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kyo.*
import kyo.internal.server.HttpDate

class HttpDateTest extends kyo.test.Test[Any]:

    "the epoch" in {
        assert(HttpDate.format(0L) == "Thu, 01 Jan 1970 00:00:00 GMT")
    }

    "the IMF-fixdate example of RFC 9110 section 5.6.7" in {
        assert(HttpDate.format(784111777L) == "Sun, 06 Nov 1994 08:49:37 GMT")
    }

    "leap days and century years" in {
        assert(HttpDate.format(951782400L) == "Tue, 29 Feb 2000 00:00:00 GMT")
        assert(HttpDate.format(4107542399L) == "Sun, 28 Feb 2100 23:59:59 GMT")
        assert(HttpDate.format(4107542400L) == "Mon, 01 Mar 2100 00:00:00 GMT")
        assert(HttpDate.format(253402300799L) == "Fri, 31 Dec 9999 23:59:59 GMT")
    }

    "every instant of a sweep formats as java.time does" in {
        val reference = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
        // A stride coprime with the seconds of a day, a week and a year, so the sweep walks every weekday, month
        // position and time of day across four centuries, leap years included.
        val mismatches = (0L until 20000L).map(_ * 631139L + 17L).filter { second =>
            HttpDate.format(second) != reference.format(ZonedDateTime.ofInstant(Instant.ofEpochSecond(second), ZoneOffset.UTC))
        }
        assert(mismatches.isEmpty, s"first mismatches: ${mismatches.take(5).map(s => (s, HttpDate.format(s)))}")
    }

end HttpDateTest
