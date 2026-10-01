package kyo

import kyo.*

class HttpDateTest extends BaseHttpTest:

    private val rfcExample = Instant.parse("1994-11-06T08:49:37Z").getOrThrow

    "parse" - {
        "reads the three forms of RFC 9110 section 5.6.7 to the same instant" in {
            assert(HttpDate.parse("Sun, 06 Nov 1994 08:49:37 GMT") == Present(rfcExample))
            assert(HttpDate.parse("Sunday, 06-Nov-94 08:49:37 GMT") == Present(rfcExample))
            assert(HttpDate.parse("Sun Nov  6 08:49:37 1994") == Present(rfcExample))
        }

        "reads a two-digit asctime day" in {
            assert(HttpDate.parse("Wed Nov 16 08:49:37 1994") == Instant.parse("1994-11-16T08:49:37Z").toMaybe)
        }

        "folds the day and month names and GMT over ASCII case" in {
            assert(HttpDate.parse("SUN, 06 NOV 1994 08:49:37 gmt") == Present(rfcExample))
            assert(HttpDate.parse("sunday, 06-nov-94 08:49:37 GMT") == Present(rfcExample))
        }

        "ignores the OWS around the value" in {
            assert(HttpDate.parse("  Sun, 06 Nov 1994 08:49:37 GMT\t") == Present(rfcExample))
        }

        "checks the day name without matching it to the date" in {
            assert(HttpDate.parse("Mon, 06 Nov 1994 08:49:37 GMT") == Present(rfcExample))
            assert(HttpDate.parse("Xyz, 06 Nov 1994 08:49:37 GMT") == Absent)
        }

        "resolves an RFC 850 year as RFC 6265 does: 70 to 99 in the 1900s, 00 to 69 in the 2000s" in {
            assert(HttpDate.parse("Thursday, 01-Jan-70 00:00:00 GMT") == Present(Instant.Epoch))
            assert(HttpDate.parse("Tuesday, 31-Dec-69 23:59:59 GMT") == Instant.parse("2069-12-31T23:59:59Z").toMaybe)
            assert(HttpDate.parse("Friday, 31-Dec-99 23:59:59 GMT") == Instant.parse("1999-12-31T23:59:59Z").toMaybe)
        }

        "reads a leap second as the second after 23:59:59" in {
            assert(HttpDate.parse("Sat, 31 Dec 2016 23:59:60 GMT") == Instant.parse("2017-01-01T00:00:00Z").toMaybe)
        }

        "rejects a date that does not exist, an out-of-range time and a malformed value" in {
            assert(HttpDate.parse("Thu, 31 Feb 1994 08:49:37 GMT") == Absent)
            assert(HttpDate.parse("Sun, 06 Nov 1994 24:00:00 GMT") == Absent)
            assert(HttpDate.parse("Sun, 06 Nov 1994 08:60:00 GMT") == Absent)
            assert(HttpDate.parse("Sun, 06 Nov 1994 08:49:61 GMT") == Absent)
            assert(HttpDate.parse("Sun, 006 Nov 1994 08:49:37 GMT") == Absent)
            assert(HttpDate.parse("Sun, 06 Nov 1994 08:49:37") == Absent)
            assert(HttpDate.parse("Sun, 06 Nov 1994 08:49:37 UTC") == Absent)
            assert(HttpDate.parse("Sun, 06 Nov 1994 08:49:37 +0000") == Absent)
            assert(HttpDate.parse("Sun, 06 Nov 1994 08:49:37 GMT extra") == Absent)
            assert(HttpDate.parse("Sun, 06 Nov 1994 08:49:37 GMT, Sun, 06 Nov 1994 08:49:37 GMT") == Absent)
            assert(HttpDate.parse("Sun, 06 Xyz 1994 08:49:37 GMT") == Absent)
            assert(HttpDate.parse("Sun, 06 Nov 1994 08:49:37 GMT".replace('0', '٠')) == Absent)
            assert(HttpDate.parse("1994-11-06T08:49:37Z") == Absent)
            assert(HttpDate.parse("tomorrow") == Absent)
            assert(HttpDate.parse("120") == Absent)
            assert(HttpDate.parse("") == Absent)
        }
    }

    "render" - {
        "writes the IMF-fixdate" in {
            assert(HttpDate.render(rfcExample) == Present("Sun, 06 Nov 1994 08:49:37 GMT"))
            assert(HttpDate.render(Instant.Epoch) == Present("Thu, 01 Jan 1970 00:00:00 GMT"))
            assert(HttpDate.render(Instant.parse("2021-06-09T10:18:14Z").getOrThrow) == Present("Wed, 09 Jun 2021 10:18:14 GMT"))
        }

        "drops the sub-second part" in {
            assert(HttpDate.render(Instant.parse("1994-11-06T08:49:37.999Z").getOrThrow) == Present("Sun, 06 Nov 1994 08:49:37 GMT"))
        }

        "writes the first and last instants a four-digit year names" in {
            assert(HttpDate.render(Instant.parse("0000-01-01T00:00:00Z").getOrThrow) == Present("Sat, 01 Jan 0000 00:00:00 GMT"))
            assert(HttpDate.render(Instant.parse("0999-03-04T05:06:07Z").getOrThrow).exists(_.contains(" 0999 ")))
            assert(HttpDate.render(Instant.parse("9999-12-31T23:59:59.999Z").getOrThrow) == Present("Fri, 31 Dec 9999 23:59:59 GMT"))
        }

        "an instant outside years 0000 to 9999 renders nothing, since the IMF-fixdate year is four digits" in {
            val outside = Seq(
                Instant.Max,
                Instant.Min,
                Instant.parse("+10000-01-01T00:00:00Z").getOrThrow,
                Instant.parse("-0001-12-31T23:59:59Z").getOrThrow,
                Instant.parse("-5000-06-01T00:00:00Z").getOrThrow
            )
            outside.foreach(i => assert(HttpDate.render(i) == Absent, s"$i"))
            succeed
        }

        "round-trips through parse at second precision, the ends of the range included" in {
            val instants = Seq(
                rfcExample,
                Instant.Epoch,
                Instant.parse("2038-01-19T03:14:07Z").getOrThrow,
                Instant.parse("0000-01-01T00:00:00Z").getOrThrow,
                Instant.parse("0044-02-29T12:00:00Z").getOrThrow,
                Instant.parse("9999-12-31T23:59:59Z").getOrThrow
            )
            instants.foreach(i => assert(HttpDate.render(i).flatMap(HttpDate.parse) == Present(i), s"$i"))
            succeed
        }
    }

end HttpDateTest
