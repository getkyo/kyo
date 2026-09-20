package kyo.internal

import kyo.*

/** Unit coverage for the HTTP-date of RFC 9110 section 5.6.7, in both directions.
  *
  * The values here are the ones the java.time path produced, so the arithmetic that replaced it is pinned to them, including the cases
  * where flooring and the Gregorian century rules decide the answer. The replacement exists because a `DateTimeFormatter` names
  * `java.util.Locale`, which off the JVM loads a locale database into every program that reaches it.
  */
class HttpDateTest extends kyo.BaseHttpTest:

    /** A fixed present for the two-digit year rule, so the pivot is decided by the parameter rather than by the real clock. */
    private val now = Instant.ofEpochSecond(1705312245L) // Mon, 15 Jan 2024 09:50:45 GMT

    "format" - {
        "writes the IMF-fixdate of the second it names" in {
            val cases = Seq(
                0L            -> "Thu, 01 Jan 1970 00:00:00 GMT", // the epoch, a Thursday
                784111777L    -> "Sun, 06 Nov 1994 08:49:37 GMT", // the example in RFC 9110 section 5.6.7
                1705312245L   -> "Mon, 15 Jan 2024 09:50:45 GMT",
                951782400L    -> "Tue, 29 Feb 2000 00:00:00 GMT", // midnight, where the seconds of the day are all zero
                951825600L    -> "Tue, 29 Feb 2000 12:00:00 GMT", // a leap day in a century that has one
                1709208000L   -> "Thu, 29 Feb 2024 12:00:00 GMT", // and the next one
                -1L           -> "Wed, 31 Dec 1969 23:59:59 GMT", // one second before the epoch: the day floors, not truncates
                -86400L       -> "Wed, 31 Dec 1969 00:00:00 GMT", // a whole day before it
                -2208988800L  -> "Mon, 01 Jan 1900 00:00:00 GMT", // 1900 is not a leap year: a century not divisible by 400
                253402300799L -> "Fri, 31 Dec 9999 23:59:59 GMT"  // the last second the four-digit year field can carry
            )
            cases.foreach { (epochSecond, expected) =>
                val got = HttpDate.format(epochSecond)
                assert(got == expected, s"epoch second $epochSecond read as '$got', expected '$expected'")
            }
            succeed
        }
    }

    "parse" - {
        "reads the three forms RFC 9110 section 5.6.7 defines" in {
            val cases = Seq(
                "Sun, 06 Nov 1994 08:49:37 GMT"  -> 784111777L, // IMF-fixdate
                "Sunday, 06-Nov-94 08:49:37 GMT" -> 784111777L, // the obsolete RFC 850 form
                "Sun Nov  6 08:49:37 1994"       -> 784111777L  // the asctime form, day of month space-padded
            )
            cases.foreach { (text, expected) =>
                val got = HttpDate.parse(text, now)
                assert(got.contains(Instant.ofEpochSecond(expected)), s"'$text' read as $got, expected epoch second $expected")
            }
            succeed
        }

        "round-trips every second format writes" in {
            val seconds = Seq(0L, 784111777L, 1705312245L, 951782400L, 1709208000L, -1L, -2208988800L, 253402300799L)
            seconds.foreach { epochSecond =>
                val text = HttpDate.format(epochSecond)
                val got  = HttpDate.parse(text, now)
                assert(got.contains(Instant.ofEpochSecond(epochSecond)), s"'$text' read back as $got, expected epoch second $epochSecond")
            }
            succeed
        }

        "accepts a one-digit day of the month" in {
            val got = HttpDate.parse("Sun, 6 Nov 1994 08:49:37 GMT", now)
            assert(got.contains(Instant.ofEpochSecond(784111777L)))
        }

        "does not check the day-of-week name against the date" in {
            // The name is redundant with the date, and RFC 9110 does not ask a recipient to validate it.
            val got = HttpDate.parse("Mon, 06 Nov 1994 08:49:37 GMT", now)
            assert(got.contains(Instant.ofEpochSecond(784111777L)))
        }

        "reads a two-digit year as the most recent past year once it would land more than 50 years ahead" in {
            // now is 2024, so 2074 is still ahead and stands, and 2075 is more than 50 years ahead and falls back to 1975.
            val cases = Seq(
                "Sun, 01-Jan-74 00:00:00 GMT" -> 2074,
                "Sun, 01-Jan-75 00:00:00 GMT" -> 1975,
                "Sun, 06-Nov-94 08:49:37 GMT" -> 1994,
                "Sun, 01-Jan-24 00:00:00 GMT" -> 2024
            )
            cases.foreach { (text, expectedYear) =>
                val got = HttpDate.parse(text, now)
                assert(got.isDefined, s"'$text' did not parse")
                val year = Civil.of(got.get.epochSecond).year
                assert(year == expectedYear, s"'$text' read as year $year, expected $expectedYear")
            }
            succeed
        }

        "rejects what is not an HTTP-date" in {
            val cases = Seq(
                ""                                -> "empty",
                "not a date"                      -> "not a date at all",
                "Sun, 06 Nov 1994 08:49:37 PST"   -> "a zone other than GMT, which an HTTP-date never carries",
                "Sun, 06 Nov 1994 08:49:37"       -> "no zone",
                "Sun, 06 Foo 1994 08:49:37 GMT"   -> "a month name that is not one of the twelve",
                "Sun, 32 Nov 1994 08:49:37 GMT"   -> "a day past the end of the month",
                "Sun, 29 Feb 2023 00:00:00 GMT"   -> "a leap day in a year that has none",
                "Sun, 06 Nov 1994 24:00:00 GMT"   -> "an hour past the end of the day",
                "Sun, 06 Nov 1994 08:60:00 GMT"   -> "a minute past the end of the hour",
                "Sun, 06 Nov 1994 08:49 GMT"      -> "a time field that is not HH:MM:SS",
                "Sun, 06 Nov 1994 0x:49:37 GMT"   -> "a time field that is not all digits",
                "Sun, 06 Nov 199 08:49:37 GMT"    -> "a year that is neither four digits nor two",
                "Sun, 06 Nov 1994 08:49:37 GMT x" -> "trailing text"
            )
            cases.foreach { (text, why) =>
                val got = HttpDate.parse(text, now)
                assert(got.isEmpty, s"'$text' should not parse ($why), read as $got")
            }
            succeed
        }
    }

end HttpDateTest
