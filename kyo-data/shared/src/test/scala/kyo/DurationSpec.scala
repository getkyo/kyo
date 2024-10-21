package kyo

import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration as ScalaDuration
import zio.Duration as ZDuration
import zio.test.{Result as _, *}
import zio.test.Assertion.*

object DurationSpec extends ZIOSpecDefault:
    given CanEqual[ZDuration, ZDuration]         = CanEqual.derived
    given CanEqual[ScalaDuration, ScalaDuration] = CanEqual.derived
    given CanEqual[Duration, Duration]           = CanEqual.derived

    val maxNanos = 1_000_000L // TODO: is this enough??

    def spec = suite("Duration")(
        suite("Long Extension")(
            test("as.to")(
                check(Gen.long(0, maxNanos)) { i =>
                    TestResult.allSuccesses(
                        assertTrue(i.nanos.toNanos == i),
                        assertTrue(i.micros.toMicros == i),
                        assertTrue(i.millis.toMillis == i),
                        assertTrue(i.seconds.toSeconds == i),
                        assertTrue(i.minutes.toMinutes == i),
                        assertTrue(i.hours.toHours == i)
//                        assertTrue(i.days.toDays == i),
//                        assertTrue(i.weeks.toWeeks == i),
//                        assertTrue(i.months.toMonths == i)
                    )
                }
            ),
            test("conversion for value 1") {
                TestResult.allSuccesses(
                    assertTrue(1.nano.toNanos == 1),
                    assertTrue(1.micro.toMicros == 1),
                    assertTrue(1.milli.toMillis == 1),
                    assertTrue(1.second.toSeconds == 1),
                    assertTrue(1.minute.toMinutes == 1),
                    assertTrue(1.hour.toHours == 1),
                    assertTrue(1.day.toDays == 1),
                    assertTrue(1.week.toWeeks == 1),
                    assertTrue(1.month.toMonths == 1),
                    assertTrue(1.year.toYears == 1)
                )
            },
            test("invalid conversion shouldn't compile") {
                assertZIO(typeCheck("2.nano"))(isLeft)
                assertZIO(typeCheck("2.micro"))(isLeft)
                assertZIO(typeCheck("2.milli"))(isLeft)
                assertZIO(typeCheck("2.second"))(isLeft)
                assertZIO(typeCheck("2.minute"))(isLeft)
                assertZIO(typeCheck("2.hour"))(isLeft)
                assertZIO(typeCheck("2.day"))(isLeft)
                assertZIO(typeCheck("2.week"))(isLeft)
                assertZIO(typeCheck("2.month"))(isLeft)
                assertZIO(typeCheck("2.year"))(isLeft)
            },
            test("equality") {
                val zero = 0.nanos
                TestResult.allSuccesses(
                    assertTrue(Duration.Zero == zero),
                    assertTrue(Duration.Zero >= zero),
                    assertTrue(Duration.Zero <= zero),
                    assertTrue(Duration.Zero != Duration.Infinity),
                    assertTrue(Duration.Zero != Duration.Infinity)
                )
            },
            test("toJava")(
                check(Gen.long(0, maxNanos)) { i =>
                    assertTrue(i.nanos.toJava == ZDuration.fromNanos(i))
                }
            ),
            test("toScala")(
                check(Gen.long(0, maxNanos)) { i =>
                    assertTrue(i.nanos.toScala == ZDurations.fromNanosScala(i))
                }
            ),
            test("math")(
                check(Gen.long(0, maxNanos), Gen.long(0, maxNanos)) { (i, j) =>
                    val added  = i.nanos + j.nanos
                    val mult   = i.nanos * j.toDouble
                    val zadded = ZDuration.fromNanos(i + j)
                    val zmult  = ZDurations.multiplied(ZDuration.fromNanos(i), j.toDouble)

                    assertTrue(added.toJava == zadded)
                    assertTrue(mult.toJava == zmult)
                }
            ),
            test("overflow") {
                val multiplied = Duration.Infinity * 1.1
                val added      = Duration.Infinity + 1.nano
                val hours      = Long.MaxValue.nanos

                assertTrue(multiplied == Duration.Infinity)
                assertTrue(added == Duration.Infinity)
                assertTrue(hours == Duration.Infinity)
            },
            test("Long.to*") {
                for
                    result <- typeCheck("Long.MaxValue.toNanos")
                yield assertTrue(result.is(_.left).contains("value toNanos is not a member of Long"))
            },
            test("toString") {
                val d = 10.nanos.toString
                assertTrue(d != "10") // opaque type inherits toString method from Long
            } @@ TestAspect.failing
        ),
        suite("Duration.parse")(
            test("valid durations") {
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

                TestResult.allSuccesses(
                    testCases.map { case (input, expected) =>
                        assertTrue(Duration.parse(input) == Result.success(expected))
                    }
                )
            },
            test("invalid durations") {
                val testCases = List(
                    "invalid",
                    "1x",
                    "1 lightyear",
                    "-1s",
                    "2.5h"
                )

                TestResult.allSuccesses(
                    testCases.map { input =>
                        assertTrue(Duration.parse(input).isFail)
                    }
                )
            },
            test("case insensitivity") {
                TestResult.allSuccesses(
                    assertTrue(Duration.parse("1MS") == Result.success(1.millis)),
                    assertTrue(Duration.parse("2H") == Result.success(2.hours)),
                    assertTrue(Duration.parse("3D") == Result.success(3.days))
                )
            },
            test("whitespace handling") {
                TestResult.allSuccesses(
                    assertTrue(Duration.parse("  1  second  ") == Result.success(1.seconds)),
                    assertTrue(Duration.parse("5\tminutes") == Result.success(5.minutes))
                )
            }
        ),
        suite("Duration.fromNanos")(
            test("negative value") {
                assertTrue(Duration.fromNanos(-1) == Duration.Zero)
            }
        ),
        suite("Duration.show")(
            test("zero duration") {
                assertTrue(Duration.Zero.show == "Duration.Zero")
            },
            test("infinity duration") {
                assertTrue(Duration.Infinity.show == "Duration.Infinity")
            },
            test("nanoseconds") {
                TestResult.allSuccesses(
                    assertTrue(1.nano.show == "1.nanos"),
                    assertTrue(999.nanos.show == "999.nanos")
                )
            },
            test("microseconds") {
                TestResult.allSuccesses(
                    assertTrue(1.micro.show == "1.micros"),
                    assertTrue(999.micros.show == "999.micros")
                )
            },
            test("milliseconds") {
                TestResult.allSuccesses(
                    assertTrue(1.milli.show == "1.millis"),
                    assertTrue(999.millis.show == "999.millis")
                )
            },
            test("seconds") {
                TestResult.allSuccesses(
                    assertTrue(1.second.show == "1.seconds"),
                    assertTrue(59.seconds.show == "59.seconds")
                )
            },
            test("minutes") {
                TestResult.allSuccesses(
                    assertTrue(1.minute.show == "1.minutes"),
                    assertTrue(59.minutes.show == "59.minutes")
                )
            },
            test("hours") {
                TestResult.allSuccesses(
                    assertTrue(1.hour.show == "1.hours"),
                    assertTrue(23.hours.show == "23.hours")
                )
            },
            test("days") {
                TestResult.allSuccesses(
                    assertTrue(1.day.show == "1.days"),
                    assertTrue(6.days.show == "6.days")
                )
            },
            test("weeks") {
                TestResult.allSuccesses(
                    assertTrue(1.week.show == "1.weeks"),
                    assertTrue(3.weeks.show == "3.weeks")
                )
            },
            test("months") {
                TestResult.allSuccesses(
                    assertTrue(1.month.show == "1.months"),
                    assertTrue(11.months.show == "11.months")
                )
            },
            test("years") {
                TestResult.allSuccesses(
                    assertTrue(1.year.show == "1.years"),
                    assertTrue(5.years.show == "5.years")
                )
            },
            test("coarse resolution") {
                TestResult.allSuccesses(
                    assertTrue((1.day + 2.hours).show == "26.hours"),
                    assertTrue((1.year + 6.months).show == "18.months"),
                    assertTrue((1.minute + 30.seconds).show == "90.seconds"),
                    assertTrue((1000.millis).show == "1.seconds"),
                    assertTrue((1000.micros).show == "1.millis"),
                    assertTrue((1000.nanos).show == "1.micros")
                )
            }
        ),
        suite("Duration subtraction")(
            test("subtracting smaller from larger") {
                assertTrue(5.seconds - 2.seconds == 3.seconds)
            },
            test("subtracting larger from smaller") {
                assertTrue(2.seconds - 5.seconds == Duration.Zero)
            },
            test("subtracting equal durations") {
                assertTrue(3.minutes - 3.minutes == Duration.Zero)
            },
            test("subtracting from zero") {
                assertTrue(Duration.Zero - 1.second == Duration.Zero)
            },
            test("subtracting zero") {
                assertTrue(10.hours - Duration.Zero == 10.hours)
            }
        ),
        suite("Duration TimeUnit/ChronoUnit conversions")(
            test("TimeUnit conversions") {
                val duration = 1000.millis
                TestResult.allSuccesses(
                    assertTrue(duration.to(TimeUnit.NANOSECONDS) == 1_000_000_000L),
                    assertTrue(duration.to(TimeUnit.MICROSECONDS) == 1_000_000L),
                    assertTrue(duration.to(TimeUnit.MILLISECONDS) == 1000L),
                    assertTrue(duration.to(TimeUnit.SECONDS) == 1L),
                    assertTrue(duration.to(TimeUnit.MINUTES) == 0L),
                    assertTrue(duration.to(TimeUnit.HOURS) == 0L),
                    assertTrue(duration.to(TimeUnit.DAYS) == 0L)
                )
            },
            test("ChronoUnit conversions") {
                val duration = 24.hours
                TestResult.allSuccesses(
                    assertTrue(duration.to(ChronoUnit.NANOS) == 24L * 60 * 60 * 1_000_000_000L),
                    assertTrue(duration.to(ChronoUnit.MICROS) == 24L * 60 * 60 * 1_000_000L),
                    assertTrue(duration.to(ChronoUnit.MILLIS) == 24L * 60 * 60 * 1000L),
                    assertTrue(duration.to(ChronoUnit.SECONDS) == 24L * 60 * 60),
                    assertTrue(duration.to(ChronoUnit.MINUTES) == 24L * 60),
                    assertTrue(duration.to(ChronoUnit.HOURS) == 24L),
                    assertTrue(duration.to(ChronoUnit.DAYS) == 1L)
                )
            },
            test("unsupported ChronoUnit throws exception") {
                val result = Result.catching[UnsupportedOperationException](1.second.to(ChronoUnit.FOREVER))
                assertTrue(result.isFail)
            }
        )
    ) @@ TestAspect.exceptNative
end DurationSpec

object ZDurations:
    import zio.*
    def multiplied(duration: ZDuration, factor: Double): ZDuration =
        duration * factor

    def fromNanosScala(value: Long): ScalaDuration =
        ZDuration.fromNanos(value).asScala
end ZDurations
