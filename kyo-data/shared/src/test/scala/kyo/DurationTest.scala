package kyo

import java.time.Duration as JavaDuration
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration as ScalaDuration

class DurationTest extends Test:

    given CanEqual[ScalaDuration, ScalaDuration] = CanEqual.derived
    given CanEqual[JavaDuration, JavaDuration]   = CanEqual.derived

    def genLong(min: Long, max: Long): List[Long] =
        val rand = new scala.util.Random(42)
        min :: max :: List.fill(30)(min + math.abs(rand.nextLong()) % (max - min + 1))

    "Long Extension" - {
        "as.to" in {
            genLong(0, 1_000_000L).foreach { i =>
                assert(i.nanos.toNanos == i)
                assert(i.micros.toMicros == i)
                assert(i.millis.toMillis == i)
                assert(i.seconds.toSeconds == i)
                assert(i.minutes.toMinutes == i)
                assert(i.hours.toHours == i)
            }
            succeed
        }

        "conversion for value 1" in {
            assert(1.nano.toNanos == 1)
            assert(1.micro.toMicros == 1)
            assert(1.milli.toMillis == 1)
            assert(1.second.toSeconds == 1)
            assert(1.minute.toMinutes == 1)
            assert(1.hour.toHours == 1)
            assert(1.day.toDays == 1)
            assert(1.week.toWeeks == 1)
            assert(1.month.toMonths == 1)
            assert(1.year.toYears == 1)
        }

        "invalid conversion shouldn't compile" in {
            assertDoesNotCompile("2.nano")
            assertDoesNotCompile("2.micro")
            assertDoesNotCompile("2.milli")
            assertDoesNotCompile("2.second")
            assertDoesNotCompile("2.minute")
            assertDoesNotCompile("2.hour")
            assertDoesNotCompile("2.day")
            assertDoesNotCompile("2.week")
            assertDoesNotCompile("2.month")
            assertDoesNotCompile("2.year")
        }

        "equality" in {
            val zero = 0.nanos
            assert(Duration.Zero == zero)
            assert(Duration.Zero >= zero)
            assert(Duration.Zero <= zero)
            assert(Duration.Zero != Duration.Infinity)
        }

        "toJava" in {
            genLong(0, maxNanos).foreach { i =>
                assert(i.nanos.toJava == java.time.Duration.ofNanos(i))
            }
            succeed
        }

        "toScala" in {
            genLong(0, maxNanos).foreach { i =>
                assert(i.nanos.toScala == scala.concurrent.duration.Duration.fromNanos(i))
            }
            succeed
        }

        "math" in {
            val values = genLong(0, maxNanos)
            values.zip(values).foreach { case (i, j) =>
                val added        = i.nanos + j.nanos
                val mult         = i.nanos * j.toDouble
                val expectedAdd  = java.time.Duration.ofNanos(i).plus(java.time.Duration.ofNanos(j))
                val expectedMult = java.time.Duration.ofNanos(i).multipliedBy(j)

                assert(added.toJava == expectedAdd)
                assert(mult.toJava == expectedMult)
            }
            succeed
        }

        "overflow" in {
            val multiplied = Duration.Infinity * 1.1
            val added      = Duration.Infinity + 1.nano
            val hours      = Long.MaxValue.nanos

            assert(multiplied == Duration.Infinity)
            assert(added == Duration.Infinity)
            assert(hours == Duration.Infinity)
        }

        "Long.to* shouldn't compile" in {
            assertDoesNotCompile("Long.MaxValue.toNanos")
        }
    }

    "Duration.parse" - {
        "valid durations" in {
            val testCases = List(
                "1ns"       -> 1.nano,
                "1 ns"      -> 1.nano,
                "500ms"     -> 500.millis,
                "2s"        -> 2.seconds,
                "3 minutes" -> 3.minutes,
                "4h"        -> 4.hours,
                "1 day"     -> 1.day,
                "2 weeks"   -> 2.weeks,
                "6 months"  -> 6.months,
                "1 year"    -> 1.year,
                "infinity"  -> Duration.Infinity,
                "INF"       -> Duration.Infinity
            )

            testCases.foreach { case (input, expected) =>
                assert(Duration.parse(input) == Result.success(expected))
            }
            succeed
        }

        "invalid durations" in {
            val testCases = List(
                "invalid",
                "1x",
                "1 lightyear",
                "-1s",
                "2.5h"
            )

            testCases.foreach { input =>
                assert(Duration.parse(input).isFail)
            }
            succeed
        }

        "case insensitivity" in {
            assert(Duration.parse("1MS") == Result.success(1.millis))
            assert(Duration.parse("2H") == Result.success(2.hours))
            assert(Duration.parse("3D") == Result.success(3.days))
        }

        "whitespace handling" in {
            assert(Duration.parse("  1  second  ") == Result.success(1.second))
            assert(Duration.parse("5\tminutes") == Result.success(5.minutes))
        }
    }

    private val maxNanos = 1_000_000L

    "Duration.fromNanos" - {
        "negative value" in {
            assert(Duration.fromNanos(-1) == Duration.Zero)
        }
    }

    "Duration.show" - {
        "zero duration" in {
            assert(Duration.Zero.show == "Duration.Zero")
        }

        "infinity duration" in {
            assert(Duration.Infinity.show == "Duration.Infinity")
        }

        "time units" - {
            "nanoseconds" in {
                assert(1.nano.show == "1.nanos")
                assert(999.nanos.show == "999.nanos")
            }

            "microseconds" in {
                assert(1.micro.show == "1.micros")
                assert(999.micros.show == "999.micros")
            }

            "milliseconds" in {
                assert(1.milli.show == "1.millis")
                assert(999.millis.show == "999.millis")
            }

            "seconds" in {
                assert(1.second.show == "1.seconds")
                assert(59.seconds.show == "59.seconds")
            }

            "minutes" in {
                assert(1.minute.show == "1.minutes")
                assert(59.minutes.show == "59.minutes")
            }

            "hours" in {
                assert(1.hour.show == "1.hours")
                assert(23.hours.show == "23.hours")
            }

            "days" in {
                assert(1.day.show == "1.days")
                assert(6.days.show == "6.days")
            }

            "weeks" in {
                assert(1.week.show == "1.weeks")
                assert(3.weeks.show == "3.weeks")
            }

            "months" in {
                assert(1.month.show == "1.months")
                assert(11.months.show == "11.months")
            }

            "years" in {
                assert(1.year.show == "1.years")
                assert(5.years.show == "5.years")
            }
        }

        "coarse resolution" in {
            assert((1.day + 2.hours).show == "26.hours")
            assert((1.year + 6.months).show == "18.months")
            assert((1.minute + 30.seconds).show == "90.seconds")
            assert((1000.millis).show == "1.seconds")
            assert((1000.micros).show == "1.millis")
            assert((1000.nanos).show == "1.micros")
        }
    }

    "Duration subtraction" - {
        "subtracting smaller from larger" in {
            assert(5.seconds - 2.seconds == 3.seconds)
        }

        "subtracting larger from smaller" in {
            assert(2.seconds - 5.seconds == Duration.Zero)
        }

        "subtracting equal durations" in {
            assert(3.minutes - 3.minutes == Duration.Zero)
        }

        "subtracting from zero" in {
            assert(Duration.Zero - 1.second == Duration.Zero)
        }

        "subtracting zero" in {
            assert(10.hours - Duration.Zero == 10.hours)
        }
    }

    "Duration conversion operations" - {
        "TimeUnit conversions" in {
            val duration = 1000.millis
            assert(duration.to(TimeUnit.NANOSECONDS) == 1_000_000_000L)
            assert(duration.to(TimeUnit.MICROSECONDS) == 1_000_000L)
            assert(duration.to(TimeUnit.MILLISECONDS) == 1000L)
            assert(duration.to(TimeUnit.SECONDS) == 1L)
            assert(duration.to(TimeUnit.MINUTES) == 0L)
            assert(duration.to(TimeUnit.HOURS) == 0L)
            assert(duration.to(TimeUnit.DAYS) == 0L)
        }

        "ChronoUnit conversions" in {
            val duration = 24.hours
            assert(duration.to(ChronoUnit.NANOS) == 24L * 60 * 60 * 1_000_000_000L)
            assert(duration.to(ChronoUnit.MICROS) == 24L * 60 * 60 * 1_000_000L)
            assert(duration.to(ChronoUnit.MILLIS) == 24L * 60 * 60 * 1000L)
            assert(duration.to(ChronoUnit.SECONDS) == 24L * 60 * 60)
            assert(duration.to(ChronoUnit.MINUTES) == 24L * 60)
            assert(duration.to(ChronoUnit.HOURS) == 24L)
            assert(duration.to(ChronoUnit.DAYS) == 1L)
        }

        "unsupported ChronoUnit throws exception" in {
            val result = Result.catching[UnsupportedOperationException](1.second.to(ChronoUnit.FOREVER))
            assert(result.isFail)
        }
    }
end DurationTest
