package kyo.internal

import kyo.*

class CivilTest extends kyo.test.Test[Any]:

    "of" - {
        "names the calendar fields of a second" in {
            // Each expected value verified independently against the UTC calendar.
            val cases = Seq(
                // epoch second      year  month day hour min sec dayOfWeek (0 = Monday)
                (0L, Civil(1970, 1, 1, 0, 0, 0, 3)),             // the epoch, a Thursday
                (784111777L, Civil(1994, 11, 6, 8, 49, 37, 6)),  // the RFC 9110 example, a Sunday
                (1705312245L, Civil(2024, 1, 15, 9, 50, 45, 0)), // a Monday
                (951825600L, Civil(2000, 2, 29, 12, 0, 0, 1)),   // a leap day in a century that has one
                (1709208000L, Civil(2024, 2, 29, 12, 0, 0, 3)),  // and the next leap day
                (-1L, Civil(1969, 12, 31, 23, 59, 59, 2)),       // one second before the epoch
                (-86400L, Civil(1969, 12, 31, 0, 0, 0, 2)),      // a whole day before it
                (-2208988800L, Civil(1900, 1, 1, 0, 0, 0, 0)),   // 1900 has no leap day
                (253402300799L, Civil(9999, 12, 31, 23, 59, 59, 4))
            )
            cases.foreach { (epochSecond, expected) =>
                assert(Civil.of(epochSecond) == expected, s"epoch second $epochSecond")
            }
            succeed
        }

        "advances the day of the week by one each day" in {
            val start = Civil.of(0L).dayOfWeek
            (0 until 21).foreach { day =>
                val civil = Civil.of(day * 86400L)
                assert(civil.dayOfWeek == (start + day) % 7, s"day $day")
            }
            succeed
        }
    }

    "epochSecondOf" - {
        "inverts of, over a span that crosses leap days and centuries" in {
            // Every six hours across four years from 1899, so February 1900, 1904 and the turn of the century are all in it.
            var second = -2240524800L // 1899-01-01T00:00:00Z
            val end    = -2114380800L // 1903-01-01T00:00:00Z
            while second < end do
                val c = Civil.of(second)
                assert(
                    Civil.epochSecondOf(c.year, c.month, c.day, c.hour, c.minute, c.second) == Present(second),
                    s"epoch second $second read back as ${Civil.epochSecondOf(c.year, c.month, c.day, c.hour, c.minute, c.second)}"
                )
                second += 21600L
            end while
            succeed
        }

        "refuses fields that name no real time" in {
            assert(Civil.epochSecondOf(2024, 0, 1, 0, 0, 0) == Absent, "month 0")
            assert(Civil.epochSecondOf(2024, 13, 1, 0, 0, 0) == Absent, "month 13")
            assert(Civil.epochSecondOf(2024, 1, 0, 0, 0, 0) == Absent, "day 0")
            assert(Civil.epochSecondOf(2024, 1, 32, 0, 0, 0) == Absent, "day 32")
            assert(Civil.epochSecondOf(2023, 2, 29, 0, 0, 0) == Absent, "29 February of a common year")
            assert(Civil.epochSecondOf(1900, 2, 29, 0, 0, 0) == Absent, "29 February 1900")
            assert(Civil.epochSecondOf(2024, 4, 31, 0, 0, 0) == Absent, "31 April")
            assert(Civil.epochSecondOf(2024, 1, 1, 24, 0, 0) == Absent, "hour 24")
            assert(Civil.epochSecondOf(2024, 1, 1, 0, 60, 0) == Absent, "minute 60")
            assert(Civil.epochSecondOf(2024, 1, 1, 0, 0, 60) == Absent, "second 60")
        }

        "accepts the leap days that exist" in {
            assert(Civil.epochSecondOf(2024, 2, 29, 0, 0, 0) == Present(1709164800L))
            assert(Civil.epochSecondOf(2000, 2, 29, 0, 0, 0) == Present(951782400L))
        }
    }

    "isLeapYear" - {
        "follows the Gregorian rule at each of its three steps" in {
            assert(Civil.isLeapYear(2024), "every fourth year")
            assert(!Civil.isLeapYear(2023), "and no other")
            assert(!Civil.isLeapYear(1900), "except centuries")
            assert(Civil.isLeapYear(2000), "except every fourth century")
        }
    }

end CivilTest
