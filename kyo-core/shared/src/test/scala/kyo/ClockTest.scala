package kyo

import java.time.temporal.ChronoUnit
import kyo.Clock.Deadline
import kyo.Clock.Stopwatch
import kyo.Clock.stopwatch

class ClockTest extends Test:

    "Clock" - {
        def javaNow() = Instant.fromJava(java.time.Instant.now())

        "now" in run {
            Clock.now.map { now =>
                assert(now - javaNow() < 1.milli)
            }
        }

        "unsafe now" in {
            import AllowUnsafe.embrace.danger
            val now = Clock.live.unsafe.now()
            assert(now - javaNow() < 1.milli)
        }

        "now at epoch" in run {
            Clock.withTimeControl { control =>
                for
                    _   <- control.set(Instant.Epoch)
                    now <- Clock.now
                yield assert(now == Instant.Epoch)
            }
        }

        "now at max instant" in run {
            Clock.withTimeControl { control =>
                for
                    _   <- control.set(Instant.Max)
                    now <- Clock.now
                yield assert(now == Instant.Max)
            }
        }
    }

    "Stopwatch" - {
        "elapsed time" in run {
            Clock.withTimeControl { control =>
                for
                    stopwatch <- Clock.stopwatch
                    _         <- control.advance(5.seconds)
                    elapsed   <- stopwatch.elapsed
                yield assert(elapsed == 5.seconds)
                end for
            }
        }

        "unsafe elapsed time" in run {
            import AllowUnsafe.embrace.danger
            Clock.withTimeControl { control =>
                for
                    clock     <- Clock.get
                    stopwatch <- IO.Unsafe(clock.unsafe.stopwatch())
                    _         <- control.advance(5.seconds)
                yield assert(stopwatch.elapsed() == 5.seconds)
                end for
            }
        }

        "zero elapsed time" in run {
            Clock.withTimeControl { control =>
                for
                    stopwatch <- Clock.stopwatch
                    elapsed   <- stopwatch.elapsed
                yield assert(elapsed == 0.seconds)
                end for
            }
        }
    }

    "Deadline" - {
        "timeLeft" in run {
            Clock.withTimeControl { control =>
                for
                    deadline <- Clock.deadline(10.seconds)
                    _        <- control.advance(3.seconds)
                    timeLeft <- deadline.timeLeft
                yield assert(timeLeft == 7.seconds)
                end for
            }
        }

        "isOverdue" in run {
            Clock.withTimeControl { control =>
                for
                    deadline   <- Clock.deadline(5.seconds)
                    notOverdue <- deadline.isOverdue
                    _          <- control.advance(6.seconds)
                    overdue    <- deadline.isOverdue
                yield assert(!notOverdue && overdue)
            }
        }

        "unsafe timeLeft" in run {
            import AllowUnsafe.embrace.danger
            Clock.withTimeControl { control =>
                for
                    clock    <- Clock.get
                    deadline <- IO.Unsafe(clock.unsafe.deadline(10.seconds))
                    _        <- control.advance(3.seconds)
                yield assert(deadline.timeLeft() == 7.seconds)
            }
        }

        "unsafe isOverdue" in run {
            import AllowUnsafe.embrace.danger
            Clock.withTimeControl { control =>
                for
                    deadline <- Clock.deadline(5.seconds)
                    _        <- IO.Unsafe(assert(!deadline.unsafe.isOverdue()))
                    _        <- control.advance(6.seconds)
                yield assert(deadline.unsafe.isOverdue())
            }
        }

        "zero duration deadline" in run {
            Clock.withTimeControl { control =>
                for
                    deadline  <- Clock.deadline(Duration.Zero)
                    isOverdue <- deadline.isOverdue
                    timeLeft  <- deadline.timeLeft
                yield assert(!isOverdue && timeLeft == Duration.Zero)
            }
        }

        "deadline exactly at expiration" in run {
            Clock.withTimeControl { control =>
                for
                    deadline  <- Clock.deadline(5.seconds)
                    _         <- control.advance(5.seconds)
                    isOverdue <- deadline.isOverdue
                    timeLeft  <- deadline.timeLeft
                yield assert(!isOverdue && timeLeft == Duration.Zero)
            }
        }

        "handle Zero timeLeft" in run {
            Clock.withTimeControl { control =>
                for
                    deadline  <- Clock.deadline(1.second)
                    _         <- control.advance(1.second)
                    timeLeft  <- deadline.timeLeft
                    isOverdue <- deadline.isOverdue
                yield
                    assert(timeLeft == Duration.Zero)
                    assert(!isOverdue)
            }
        }

        "handle Infinity timeLeft" in run {
            Clock.withTimeControl { control =>
                for
                    deadline  <- Clock.deadline(Duration.Infinity)
                    timeLeft  <- deadline.timeLeft
                    isOverdue <- deadline.isOverdue
                yield
                    assert(timeLeft == Duration.Infinity)
                    assert(!isOverdue)
            }
        }

        "handle Duration.Zero and Duration.Infinity" - {
            "deadline with Zero duration" in run {
                Clock.withTimeControl { control =>
                    for
                        deadline  <- Clock.deadline(Duration.Zero)
                        isOverdue <- deadline.isOverdue
                        timeLeft  <- deadline.timeLeft
                    yield
                        assert(!isOverdue)
                        assert(timeLeft == Duration.Zero)
                }
            }

            "deadline with Infinity duration" in run {
                Clock.withTimeControl { control =>
                    for
                        deadline  <- Clock.deadline(Duration.Infinity)
                        isOverdue <- deadline.isOverdue
                        timeLeft  <- deadline.timeLeft
                    yield
                        assert(!isOverdue)
                        assert(timeLeft == Duration.Infinity)
                }
            }
        }
    }

    "Integration" - {
        "using stopwatch with deadline" in run {
            Clock.withTimeControl { control =>
                for
                    stopwatch <- Clock.stopwatch
                    deadline  <- Clock.deadline(10.seconds)
                    _         <- control.advance(7.seconds)
                    elapsed   <- stopwatch.elapsed
                    timeLeft  <- deadline.timeLeft
                yield assert(elapsed == 7.seconds && timeLeft == 3.seconds)
            }
        }

        "multiple stopwatches and deadlines" in run {
            Clock.withTimeControl { control =>
                for
                    stopwatch1 <- Clock.stopwatch
                    deadline1  <- Clock.deadline(10.seconds)
                    _          <- control.advance(3.seconds)
                    stopwatch2 <- Clock.stopwatch
                    deadline2  <- Clock.deadline(5.seconds)
                    _          <- control.advance(4.seconds)
                    elapsed1   <- stopwatch1.elapsed
                    elapsed2   <- stopwatch2.elapsed
                    timeLeft1  <- deadline1.timeLeft
                    timeLeft2  <- deadline2.timeLeft
                yield
                    assert(elapsed1 == 7.seconds)
                    assert(elapsed2 == 4.seconds)
                    assert(timeLeft1 == 3.seconds)
                    assert(timeLeft2 == 1.second)
            }
        }
    }

    "Sleep" - {
        "sleep for specified duration" in run {
            for
                clock <- Clock.get
                start <- Clock.now
                fiber <- clock.sleep(1.millis)
                _     <- fiber.get
                end   <- Clock.now
            yield
                val elapsed = end - start
                assert(elapsed >= 1.millis && elapsed < 10.millis)
        }

        "multiple sequential sleeps" in run {
            for
                clock  <- Clock.get
                start  <- Clock.now
                fiber1 <- clock.sleep(2.millis)
                _      <- fiber1.get
                mid    <- Clock.now
                fiber2 <- clock.sleep(2.millis)
                _      <- fiber2.get
                end    <- Clock.now
            yield
                assert(mid - start >= 2.millis && mid - start < 10.millis)
                assert(end - start >= 4.millis && end - start < 15.millis)
        }

        "sleep with zero duration" in run {
            for
                clock <- Clock.get
                start <- Clock.now
                fiber <- clock.sleep(Duration.Zero)
                _     <- fiber.get
                end   <- Clock.now
            yield assert(end - start < 2.millis)
        }

        "concurrency" in run {
            for
                clock  <- Clock.get
                start  <- Clock.now
                fibers <- Kyo.fill(100)(clock.sleep(1.millis))
                _      <- Kyo.foreachDiscard(fibers)(_.get)
                end    <- Clock.now
            yield
                val elapsed = end - start
                assert(elapsed >= 1.millis && elapsed < 15.millis)
        }
    }

    "TimeShift" - {
        "speed up time" in run {
            for
                wallStart  <- Clock.now
                shiftedEnd <- Clock.withTimeShift(2)(Clock.sleep(10.millis).map(_.get.andThen(Clock.now)))
                wallEnd    <- Clock.now
            yield
                val elapsedWall    = wallEnd - wallStart
                val elapsedShifted = shiftedEnd - wallStart
                assert(elapsedWall >= 5.millis && elapsedWall < 10.millis)
                assert(elapsedShifted > elapsedWall)
        }

        "slow down time" in run {
            for
                wallStart  <- Clock.now
                shiftedEnd <- Clock.withTimeShift(0.1)(Clock.sleep(2.millis).map(_.get.andThen(Clock.now)))
                wallEnd    <- Clock.now
            yield
                val elapsedWall    = wallEnd - wallStart
                val elapsedShifted = shiftedEnd - wallStart
                assert(elapsedWall >= 20.millis && elapsedWall < 50.millis)
                assert(elapsedShifted < elapsedWall)
        }

        "with time control" in run {
            Clock.withTimeControl { control =>
                Clock.withTimeShift(2.0) {
                    for
                        start <- Clock.now
                        _     <- control.advance(5.seconds)
                        end   <- Clock.now
                    yield
                        val elapsed = end - start
                        assert(elapsed == 10.seconds)
                }
            }
        }
    }
end ClockTest
