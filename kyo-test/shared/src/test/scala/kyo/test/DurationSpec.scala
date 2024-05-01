package kyo.test

import kyo.*
import zio.Duration as ZDuration
import zio.test.*

object DurationSpec extends ZIOSpecDefault:
    given CanEqual[ZDuration, ZDuration] = CanEqual.derived
    given CanEqual[Duration, Duration]   = CanEqual.derived

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
            test("toJava")(
                check(Gen.long(0, maxNanos)) { i =>
                    assertTrue(i.nanos.toJava == ZDuration.fromNanos(i))
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
                val added      = Duration.Infinity + 1.nanos
                val hours      = Long.MaxValue.nanos

                assertTrue(multiplied == Duration.Infinity)
                assertTrue(added == Duration.Infinity)
                assertTrue(hours == Duration.Infinity)
            },
            test("Long.to*") {
                for
                    result <- typeCheck("Long.MaxValue.toNanos")
                yield assertTrue(result.is(_.left).contains("Required: kyo.Duration"))
            }
        )
    )
end DurationSpec

object ZDurations:
    import zio.*
    def multiplied(duration: ZDuration, factor: Double): ZDuration =
        duration * factor
end ZDurations
