package kyo.internal.sqlite

import kyo.*
import kyo.internal.SqlValueRender

/** That every parser is the exact inverse of the renderer that produced the text.
  *
  * This is the one property these parsers need, and it is stronger than parsing some reasonable format. SQLite has no temporal or decimal
  * type of its own: a TEXT-affinity column stores the characters the driver wrote and hands them back byte for byte, so the only format in
  * play is the one `SqlValueRender` chose. A parser that accepted a different one would be the driver disagreeing with itself.
  *
  * Each leaf therefore RENDERS a value and parses the result, rather than parsing a literal someone typed here. A literal would pin what the
  * author believed the renderer writes; rendering pins what it actually writes.
  */
class SqliteTextTest extends Test:

    "dates round-trip through their rendering" - {

        "an ordinary date" in {
            val rendered = SqlValueRender.date(2026, 8, 25, bc = false)
            assert(SqliteText.dateFields(rendered) == (2026, 8, 25, false))
        }

        "a date before the era boundary keeps its era" in {
            // 44 BC is written 0044-03-15 BC, the year counted WITHIN its era rather than as a negative number.
            val rendered = SqlValueRender.date(44, 3, 15, bc = true)
            assert(rendered == "0044-03-15 BC", s"the renderer wrote '$rendered'")
            assert(SqliteText.dateFields(rendered) == (44, 3, 15, true))
        }

        "a five-digit year is not given a plus sign" in {
            val rendered = SqlValueRender.date(10000, 1, 1, bc = false)
            assert(SqliteText.dateFields(rendered) == (10000, 1, 1, false))
        }
    }

    "times round-trip, including the endpoints a LocalTime cannot hold" - {

        "a time of day" in {
            val rendered = SqlValueRender.time(false, 14L, 30, 15, 0)
            assert(SqliteText.timeFields(rendered) == (14L, 30, 15, 0))
        }

        "the end of a day, which is past what a time of day holds" in {
            val rendered = SqlValueRender.time(false, 24L, 0, 0, 0)
            assert(SqliteText.timeFields(rendered) == (24L, 0, 0, 0))
        }

        "a negative span wider than a day" in {
            // One engine's TIME runs to -838:59:59, so the hours field is a signed span and grows past two digits.
            val rendered = SqlValueRender.time(true, 838L, 59, 59, 0)
            assert(rendered == "-838:59:59", s"the renderer wrote '$rendered'")
            val body = rendered.substring(1)
            assert(SqliteText.timeFields(body) == (838L, 59, 59, 0))
        }

        "a fraction whose trailing zeros the renderer drops is read back at full width" in {
            // The renderer writes `.5` for half a second. Read as an integer that is 5 microseconds, so the parser has
            // to pad back to six digits, which is the one place this inverse is not a plain split.
            val rendered = SqlValueRender.time(false, 12L, 0, 0, 500000)
            assert(rendered == "12:00:00.5", s"the renderer wrote '$rendered'")
            assert(SqliteText.timeFields(rendered) == (12L, 0, 0, 500000))
        }

        "a single microsecond keeps its leading zeros" in {
            val rendered = SqlValueRender.time(false, 12L, 0, 0, 1)
            assert(SqliteText.timeFields(rendered) == (12L, 0, 0, 1))
        }
    }

    "offsets round-trip in both directions and at second precision" - {

        "a positive offset" in {
            val rendered = "10:00:00" + SqlValueRender.offset(7200)
            assert(SqliteText.splitOffset(rendered) == ("10:00:00", 7200))
        }

        "a negative offset" in {
            val rendered = "10:00:00" + SqlValueRender.offset(-18000)
            assert(SqliteText.splitOffset(rendered) == ("10:00:00", -18000))
        }

        "an offset carrying seconds widens to three fields" in {
            val rendered = "10:00:00" + SqlValueRender.offset(3661)
            assert(SqliteText.splitOffset(rendered) == ("10:00:00", 3661))
        }

        "a date's own hyphens are not read as an offset sign" in {
            // The failure this guards is silent: finding a sign inside the date splits the value in the wrong place.
            assert(SqliteText.splitOffset("2026-08-25 10:00:00") == ("2026-08-25 10:00:00", 0))
        }
    }

    "date-times round-trip" - {

        "with a fraction" in {
            val rendered = SqlValueRender.dateTime(2026, 8, 25, bc = false, 10, 30, 0, 1)
            assert(SqliteText.dateTimeFields(rendered) == (2026, 8, 25, false, 10, 30, 0, 1))
        }

        "with an era trailing the whole value" in {
            val rendered = SqlValueRender.dateTime(44, 3, 15, bc = true, 10, 0, 0, 0)
            assert(SqliteText.dateTimeFields(rendered) == (44, 3, 15, true, 10, 0, 0, 0))
        }
    }

    "timestamps round-trip, with the era trailing the offset" - {

        "the epoch" in {
            val rendered = SqlValueRender.timestamp(0L, 0)
            assert(SqliteText.timestampFields(rendered) == (0L, 0))
        }

        "a recent instant with microseconds" in {
            val rendered = SqlValueRender.timestamp(1_800_000_000L, 123456)
            assert(SqliteText.timestampFields(rendered) == (1_800_000_000L, 123456))
        }

        "an instant before the era boundary" in {
            // The era trails the OFFSET here, not the date and time, which is why the parser strips it FIRST: looking
            // for the offset sign inside a trailing ' BC' finds the date's hyphen instead.
            val epochSecond = java.time.LocalDateTime.of(-43, 4, 15, 0, 0).toEpochSecond(java.time.ZoneOffset.UTC)
            val rendered    = SqlValueRender.timestamp(epochSecond, 0)
            assert(rendered.endsWith(" BC"), s"the renderer wrote '$rendered'")
            assert(SqliteText.timestampFields(rendered) == (epochSecond, 0))
        }
    }

    "intervals round-trip through ISO-8601" - {

        "a calendar part only" in {
            val rendered = SqlValueRender.interval(14L, 3L, 0L)
            assert(rendered == "P1Y2M3D", s"the renderer wrote '$rendered'")
            assert(SqliteText.intervalFields(rendered) == (14L, 3L, 0L))
        }

        "a time part only" in {
            val micros   = (3L * 3600 + 15L * 60 + 30L) * 1_000_000L
            val rendered = SqlValueRender.interval(0L, 0L, micros)
            assert(SqliteText.intervalFields(rendered) == (0L, 0L, micros))
        }

        "both parts together" in {
            val micros   = 90L * 1_000_000L
            val rendered = SqlValueRender.interval(13L, 2L, micros)
            assert(SqliteText.intervalFields(rendered) == (13L, 2L, micros))
        }

        "zero" in {
            val rendered = SqlValueRender.interval(0L, 0L, 0L)
            assert(rendered == "PT0S", s"the renderer wrote '$rendered'")
            assert(SqliteText.intervalFields(rendered) == (0L, 0L, 0L))
        }

        "a negative sub-second, whose sign the renderer puts on the seconds" in {
            val rendered = SqlValueRender.interval(0L, 0L, -500000L)
            assert(SqliteText.intervalFields(rendered) == (0L, 0L, -500000L))
        }

        "a fractional second" in {
            val micros   = 1_500_000L
            val rendered = SqlValueRender.interval(0L, 0L, micros)
            assert(SqliteText.intervalFields(rendered) == (0L, 0L, micros))
        }
    }

    "booleans read the integer SQLite actually stores" in {
        // A BOOLEAN declaration takes NUMERIC affinity, so what comes back is 0 or 1 rather than the rendered word.
        assert(SqliteText.boolean("1"))
        assert(!SqliteText.boolean("0"))
        assert(SqliteText.boolean("true"))
        assert(!SqliteText.boolean("false"))
    }

    "a value that is not a boolean is refused rather than read as false" in {
        val outcome = scala.util.Try(SqliteText.boolean("2"))
        assert(outcome.isFailure, "an out-of-range integer must not silently read as a boolean")
    }

end SqliteTextTest
