package kyo

import java.time.temporal.ChronoUnit
import kyo.Clock.Deadline
import kyo.Clock.Stopwatch

class ClockTest extends Test:

    given CanEqual[Instant, Instant] = CanEqual.derived

    class TestClock extends Clock:
        var currentTime = Instant.fromJava(java.time.Instant.now())

        val unsafe: Clock.Unsafe = new Clock.Unsafe:
            def now()(using AllowUnsafe): Instant = currentTime
        def now(using Frame) = IO(currentTime)

        def advance(duration: Duration): Unit =
            currentTime = currentTime + duration

        def set(instant: Instant): Unit =
            currentTime = instant
    end TestClock

    "Clock" - {
        "now" in run {
            val testClock = new TestClock
            val instant   = testClock.currentTime
            for
                result <- Clock.let(testClock)(Clock.now)
            yield assert(result == instant)
        }

        "unsafe now" in {
            import AllowUnsafe.embrace.danger
            val testClock = new TestClock
            val instant   = testClock.currentTime
            assert(testClock.unsafe.now() == instant)
        }

        "now at epoch" in run {
            val testClock = new TestClock
            testClock.set(Instant.Epoch)
            for
                result <- Clock.let(testClock)(Clock.now)
            yield assert(result == Instant.Epoch)
        }

        "now at max instant" in run {
            val testClock = new TestClock
            testClock.set(Instant.Max)
            for
                result <- Clock.let(testClock)(Clock.now)
            yield assert(result == Instant.Max)
        }

        "handle Duration.Zero and Duration.Infinity" - {
            "deadline with Zero duration" in run {
                val testClock = new TestClock
                for
                    deadline  <- Clock.let(testClock)(Clock.deadline(Duration.Zero))
                    isOverdue <- deadline.isOverdue
                    timeLeft  <- deadline.timeLeft
                yield
                    assert(!isOverdue)
                    assert(timeLeft == Duration.Zero)
                end for
            }

            "deadline with Infinity duration" in run {
                val testClock = new TestClock
                for
                    deadline  <- Clock.let(testClock)(Clock.deadline(Duration.Infinity))
                    isOverdue <- deadline.isOverdue
                    timeLeft  <- deadline.timeLeft
                yield
                    assert(!isOverdue)
                    assert(timeLeft == Duration.Infinity)
                end for
            }
        }
    }

    "Stopwatch" - {
        "elapsed time" in run {
            val testClock = new TestClock
            for
                stopwatch <- Clock.let(testClock)(Clock.stopwatch)
                _ = testClock.advance(5.seconds)
                elapsed <- stopwatch.elapsed
            yield assert(elapsed == 5.seconds)
            end for
        }

        "unsafe elapsed time" in {
            import AllowUnsafe.embrace.danger
            val testClock = new TestClock
            val stopwatch = testClock.unsafe.stopwatch()
            testClock.advance(5.seconds)
            assert(stopwatch.elapsed() == 5.seconds)
        }

        "zero elapsed time" in run {
            val testClock = new TestClock
            for
                stopwatch <- Clock.let(testClock)(Clock.stopwatch)
                elapsed   <- stopwatch.elapsed
            yield assert(elapsed == Duration.Zero)
            end for
        }

        "measure Zero duration" in run {
            val testClock = new TestClock
            for
                stopwatch <- Clock.let(testClock)(Clock.stopwatch)
                elapsed   <- stopwatch.elapsed
            yield assert(elapsed == Duration.Zero)
            end for
        }
    }

    "Deadline" - {
        "timeLeft" in run {
            val testClock = new TestClock
            for
                deadline <- Clock.let(testClock)(Clock.deadline(10.seconds))
                _ = testClock.advance(3.seconds)
                timeLeft <- deadline.timeLeft
            yield assert(timeLeft == 7.seconds)
            end for
        }

        "isOverdue" in run {
            val testClock = new TestClock
            for
                deadline   <- Clock.let(testClock)(Clock.deadline(5.seconds))
                notOverdue <- deadline.isOverdue
                _ = testClock.advance(6.seconds)
                overdue <- deadline.isOverdue
            yield assert(!notOverdue && overdue)
            end for
        }

        "unsafe timeLeft" in {
            import AllowUnsafe.embrace.danger
            val testClock = new TestClock
            val deadline  = testClock.unsafe.deadline(10.seconds)
            testClock.advance(3.seconds)
            assert(deadline.timeLeft() == 7.seconds)
        }

        "unsafe isOverdue" in {
            import AllowUnsafe.embrace.danger
            val testClock = new TestClock
            val deadline  = testClock.unsafe.deadline(5.seconds)
            assert(!deadline.isOverdue())
            testClock.advance(6.seconds)
            assert(deadline.isOverdue())
        }

        "zero duration deadline" in run {
            val testClock = new TestClock
            for
                deadline  <- Clock.let(testClock)(Clock.deadline(Duration.Zero))
                isOverdue <- deadline.isOverdue
                timeLeft  <- deadline.timeLeft
            yield assert(!isOverdue && timeLeft == Duration.Zero)
            end for
        }

        "deadline exactly at expiration" in run {
            val testClock = new TestClock
            for
                deadline <- Clock.let(testClock)(Clock.deadline(5.seconds))
                _ = testClock.advance(5.seconds)
                isOverdue <- deadline.isOverdue
                timeLeft  <- deadline.timeLeft
            yield assert(!isOverdue && timeLeft == Duration.Zero)
            end for
        }

        "handle Zero timeLeft" in run {
            val testClock = new TestClock
            for
                deadline  <- Clock.let(testClock)(Clock.deadline(1.second))
                _         <- Clock.let(testClock)(IO { testClock.advance(1.second) })
                timeLeft  <- deadline.timeLeft
                isOverdue <- deadline.isOverdue
            yield
                assert(timeLeft == Duration.Zero)
                assert(!isOverdue)
            end for
        }

        "handle Infinity timeLeft" in run {
            val testClock = new TestClock
            for
                deadline  <- Clock.let(testClock)(Clock.deadline(Duration.Infinity))
                timeLeft  <- deadline.timeLeft
                isOverdue <- deadline.isOverdue
            yield
                assert(timeLeft == Duration.Infinity)
                assert(!isOverdue)
            end for
        }
    }

    "Integration" - {
        "using stopwatch with deadline" in run {
            val testClock = new TestClock
            for
                stopwatch <- Clock.let(testClock)(Clock.stopwatch)
                deadline  <- Clock.let(testClock)(Clock.deadline(10.seconds))
                _ = testClock.advance(7.seconds)
                elapsed  <- stopwatch.elapsed
                timeLeft <- deadline.timeLeft
            yield assert(elapsed == 7.seconds && timeLeft == 3.seconds)
            end for
        }

        "multiple stopwatches and deadlines" in run {
            val testClock = new TestClock
            for
                stopwatch1 <- Clock.let(testClock)(Clock.stopwatch)
                deadline1  <- Clock.let(testClock)(Clock.deadline(10.seconds))
                _ = testClock.advance(3.seconds)
                stopwatch2 <- Clock.let(testClock)(Clock.stopwatch)
                deadline2  <- Clock.let(testClock)(Clock.deadline(5.seconds))
                _ = testClock.advance(4.seconds)
                elapsed1  <- stopwatch1.elapsed
                elapsed2  <- stopwatch2.elapsed
                timeLeft1 <- deadline1.timeLeft
                timeLeft2 <- deadline2.timeLeft
            yield
                assert(elapsed1 == 7.seconds)
                assert(elapsed2 == 4.seconds)
                assert(timeLeft1 == 3.seconds)
                assert(timeLeft2 == 1.second)
            end for
        }
    }
end ClockTest
