package kyo.internal.email.mime

import kyo.*
import kyo.EmailRfc5322Examples.*

class DateCodecTest extends kyo.test.Test[Any]:

    // The epoch second of a date and time at an offset, by java.time, independently of the module.
    private def epoch(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, offsetSeconds: Int): Long =
        java.time.LocalDateTime.of(year, month, day, hour, minute, second).toEpochSecond(java.time.ZoneOffset.ofTotalSeconds(offsetSeconds))

    private def seconds(value: String): Maybe[Long] = DateCodec.parse(value).map(DateCodec.epochSecondOf)

    private def instant(epochSecond: Long): Instant = Instant.fromJava(java.time.Instant.ofEpochSecond(epochSecond))

    private def dateOf(message: String): String =
        HeaderCodec.readSection(Span.from(message.getBytes("UTF-8")), 0).fields.find(_.name.trim == "Date").map(_.value).getOrElse("")

    "RFC 5322 appendix A" - {
        "A.1.1, A.1.2, A.1.3: numeric zones, east and west, and a half-hour zone" in {
            assert(seconds(dateOf(simple)) == Present(epoch(1997, 11, 21, 9, 55, 6, -6 * 3600)))
            assert(seconds(dateOf(mailboxes)) == Present(epoch(2003, 7, 1, 10, 52, 37, 2 * 3600)))
            assert(seconds(dateOf(groups)) == Present(epoch(1969, 2, 13, 23, 32, 54, -(3 * 3600 + 30 * 60))))
        }
        "A.5: folded over six lines, no seconds, a comment after the zone" in {
            assert(seconds(dateOf(oddities)) == Present(epoch(1969, 2, 13, 23, 32, 0, -(3 * 3600 + 30 * 60))))
        }
        "A.6.2: a two-digit year and GMT" in {
            assert(seconds(dateOf(obsoleteDate)) == Present(epoch(1997, 11, 21, 9, 55, 6, 0)))
        }
        "A.6.3: comments and white space between the parts of the time" in {
            assert(seconds(dateOf(obsoleteWhiteSpace)) == Present(epoch(1997, 11, 21, 9, 55, 6, -6 * 3600)))
        }
    }

    "reading" - {
        "the day of week is optional, and a wrong one is ignored" in {
            assert(seconds("1 Jul 2003 10:52:37 +0200") == Present(epoch(2003, 7, 1, 10, 52, 37, 7200)))
            assert(seconds("Sun, 1 Jul 2003 10:52:37 +0200") == Present(epoch(2003, 7, 1, 10, 52, 37, 7200)))
        }
        "a day name is accepted without its comma" in {
            assert(seconds("Tue 1 Jul 2003 10:52:37 +0200") == Present(epoch(2003, 7, 1, 10, 52, 37, 7200)))
        }
        "month names in any ASCII case, and a day that must exist in its month and year" in {
            assert(seconds("1 jUL 2003 00:00:00 +0000") == Present(epoch(2003, 7, 1, 0, 0, 0, 0)))
            assert(seconds("29 Feb 2024 00:00:00 +0000") == Present(epoch(2024, 2, 29, 0, 0, 0, 0)))
            assert(seconds("29 Feb 2023 00:00:00 +0000") == Absent)
            assert(seconds("31 Apr 2023 00:00:00 +0000") == Absent)
            assert(seconds("0 Apr 2023 00:00:00 +0000") == Absent)
            assert(seconds("1 Juı 2003 00:00:00 +0000") == Absent)
        }
        "two-digit years: 00 to 49 add 2000, 50 to 99 add 1900; three digits add 1900" in {
            assert(seconds("1 Jan 49 00:00:00 +0000") == Present(epoch(2049, 1, 1, 0, 0, 0, 0)))
            assert(seconds("1 Jan 50 00:00:00 +0000") == Present(epoch(1950, 1, 1, 0, 0, 0, 0)))
            assert(seconds("1 Jan 103 00:00:00 +0000") == Present(epoch(2003, 1, 1, 0, 0, 0, 0)))
            assert(seconds("1 Jan 5 00:00:00 +0000") == Absent)
        }
        "years of four or more digits as written, up to nine digits" in {
            assert(seconds("1 Jan 0999 00:00:00 +0000") == Present(epoch(999, 1, 1, 0, 0, 0, 0)))
            assert(seconds("1 Jan 12345 00:00:00 +0000") == Present(epoch(12345, 1, 1, 0, 0, 0, 0)))
            assert(seconds("1 Jan 1234567890 00:00:00 +0000") == Absent)
        }
        "seconds are optional; hour 24, minute 60 and second 61 are unreadable; second 60 is the instant of second 59" in {
            assert(seconds("1 Jan 2020 10:11 +0000") == Present(epoch(2020, 1, 1, 10, 11, 0, 0)))
            assert(seconds("1 Jan 2020 24:00:00 +0000") == Absent)
            assert(seconds("1 Jan 2020 10:60:00 +0000") == Absent)
            assert(seconds("1 Jan 2020 10:00:61 +0000") == Absent)
            assert(seconds("31 Dec 2016 23:59:60 +0000") == Present(epoch(2016, 12, 31, 23, 59, 59, 0)))
        }
        "numeric zones: minutes up to 59, and -0000 is UTC" in {
            assert(seconds("1 Jan 2020 00:00:00 -0000") == Present(epoch(2020, 1, 1, 0, 0, 0, 0)))
            assert(seconds("1 Jan 2020 00:00:00 +1345") == Present(epoch(2020, 1, 1, 0, 0, 0, 13 * 3600 + 45 * 60)))
            assert(seconds("1 Jan 2020 00:00:00 +2359") == Present(epoch(2020, 1, 1, 0, 0, 0, 0) - (23 * 3600 + 59 * 60)))
            assert(seconds("1 Jan 2020 00:00:00 -9959") == Present(epoch(2020, 1, 1, 0, 0, 0, 0) + (99 * 3600 + 59 * 60)))
            assert(seconds("1 Jan 2020 00:00:00 +0160") == Absent)
            assert(seconds("1 Jan 2020 00:00:00 + 0100") == Absent)
            assert(seconds("1 Jan 2020 00:00:00 +130") == Absent)
        }
        "UT, GMT and the eight North American zones" in {
            val zones = Seq(
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
            zones.foreach { (zone, hours) =>
                assert(seconds(s"1 Jan 2020 12:00:00 $zone") == Present(epoch(2020, 1, 1, 12, 0, 0, hours * 3600)), zone)
                assert(seconds(s"1 Jan 2020 12:00:00 ${zone.toLowerCase}") == Present(epoch(2020, 1, 1, 12, 0, 0, hours * 3600)), zone)
            }
        }
        "military zones in either case, any other alphabetic zone, and no zone at all are UTC" in {
            Seq("A", "z", "N", "ZZZZ", "CEST", "").foreach { zone =>
                assert(seconds(s"1 Jan 2020 12:00:00 $zone") == Present(epoch(2020, 1, 1, 12, 0, 0, 0)), zone)
            }
        }
        "anything but CFWS after the date makes it unreadable, a zone of letters and digits among them" in {
            Seq("1 Jan 2020 12:00:00 +0000 junk", "1 Jan 2020 12:00:00 EST5EDT", "no date here", "", "1 Jan", "1 Jan 2020").foreach {
                value => assert(seconds(value) == Absent, value)
            }
            assert(seconds("1 Jan 2020 12:00:00 +0000 (comment) ") == Present(epoch(2020, 1, 1, 12, 0, 0, 0)))
            Seq("1" * 100000, "(" * 10000, "Mon," * 1000).foreach { value =>
                assert(seconds(value) == Absent, value.take(20))
            }
            assert(seconds("1 Jan 2020 12:00:00 (an unclosed comment runs to the end") == Present(epoch(2020, 1, 1, 12, 0, 0, 0)))
        }
    }

    "arithmetic" - {
        "10,000 dates from year 0 to 9999 at quarter-hour offsets within java.time's +-18:00 read as java.time computes them" in {
            val months = Seq("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            val drawn  =
                for
                    year   <- Random.nextInt(10000)
                    month  <- Random.nextInt(12).map(_ + 1)
                    day    <- Random.nextInt(java.time.YearMonth.of(year, month).lengthOfMonth()).map(_ + 1)
                    h      <- Random.nextInt(24)
                    m      <- Random.nextInt(60)
                    s      <- Random.nextInt(60)
                    offset <- Random.nextInt(18 * 60 * 2 + 1).map(n => (n - 18 * 60) / 15 * 15)
                yield (year, month, day, h, m, s, offset)
            Random.withSeed(0x64617465)(Kyo.fill(10000)(drawn)).map { dates =>
                dates.foreach { (year, month, day, h, m, s, offset) =>
                    val sign = if offset < 0 then "-" else "+"
                    val zone = f"$sign${math.abs(offset) / 60}%02d${math.abs(offset) % 60}%02d"
                    val text = f"$day ${months(month - 1)} $year%04d $h%02d:$m%02d:$s%02d $zone"
                    assert(seconds(text) == Present(epoch(year, month, day, h, m, s, offset * 60)), text)
                }
                succeed
            }
        }
    }

    "rendering" - {
        "UTC with the day of week, a zero-padded day and a four-digit year" in {
            assert(DateCodec.render(instant(epoch(2024, 1, 1, 12, 34, 56, 0))) == Result.succeed("Mon, 01 Jan 2024 12:34:56 +0000"))
            assert(DateCodec.render(instant(epoch(999, 3, 5, 0, 0, 0, 0))) == Result.succeed("Tue, 05 Mar 0999 00:00:00 +0000"))
        }
        "a year from 10000 is written with its digits, and a year below 0000 fails" in {
            assert(DateCodec.render(instant(epoch(12345, 6, 7, 8, 9, 10, 0))) == Result.succeed("Thu, 07 Jun 12345 08:09:10 +0000"))
            assert(DateCodec.render(instant(epoch(-1, 12, 31, 23, 59, 59, 0))) == Result.fail(HeaderCodec.WriteFailure.DateOutOfRange))
            assert(DateCodec.render(instant(epoch(0, 1, 1, 0, 0, 0, 0))) == Result.succeed("Sat, 01 Jan 0000 00:00:00 +0000"))
        }
        "a fraction of a second is truncated" in {
            val withNanos = Instant.fromJava(java.time.Instant.ofEpochSecond(epoch(2024, 1, 1, 0, 0, 1, 0), 999999999))
            assert(DateCodec.render(withNanos) == Result.succeed("Mon, 01 Jan 2024 00:00:01 +0000"))
        }
        "10,000 instants from year 0 to 12000 render with java.time's day of week and read back equal" in {
            val low  = epoch(0, 1, 1, 0, 0, 0, 0)
            val high = epoch(12000, 1, 1, 0, 0, 0, 0)
            Random.withSeed(0x72656e64)(Kyo.fill(10000)(Random.nextDouble.map(d => low + (d * (high - low)).toLong))).map { drawn =>
                drawn.foreach { second =>
                    val written = DateCodec.render(instant(second)).getOrElse("")
                    val day     = java.time.Instant.ofEpochSecond(second).atZone(java.time.ZoneOffset.UTC).getDayOfWeek
                    assert(written.take(3).toUpperCase == day.toString.take(3), written)
                    assert(seconds(written) == Present(second), written)
                }
                succeed
            }
        }
    }

    "Stalwart date.json" - {
        import DateCodecStalwart.*

        lazy val listed = parse(EmbeddedStalwartDateDifferencesTsv.text)

        "every vector is read" in {
            assert(vectors.size == 40)
        }
        "every difference from Stalwart is listed, with its reason" in {
            val unlisted = observed.filterNot(listed.contains)
            assert(unlisted.isEmpty, s"${unlisted.size} differences are not listed:\n${render(unlisted)}")
        }
        "every listed difference still occurs" in {
            val stale = listed.filterNot(observed.contains)
            assert(stale.isEmpty, s"${stale.size} listed differences no longer occur:\n${render(stale)}")
        }
        "the list has no duplicate rows, and every other vector agrees" in {
            assert(listed.distinct.size == listed.size)
            val agreeing = vectors.zipWithIndex.filterNot((_, i) => listed.exists(_.index == i))
            assert(agreeing.forall((v, _) => moduleReading(v) == referenceReading(v)))
            assert(agreeing.size + listed.size == 40)
        }
    }

end DateCodecTest
