package kyo

import kyo.Clock.Deadline
import kyo.Clock.Stopwatch

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
                    stopwatch <- Sync.Unsafe(clock.unsafe.stopwatch())
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
                    deadline <- Sync.Unsafe(clock.unsafe.deadline(10.seconds))
                    _        <- control.advance(3.seconds)
                yield assert(deadline.timeLeft() == 7.seconds)
            }
        }

        "unsafe isOverdue" in run {
            import AllowUnsafe.embrace.danger
            Clock.withTimeControl { control =>
                for
                    deadline <- Clock.deadline(5.seconds)
                    _        <- Sync.Unsafe(assert(!deadline.unsafe.isOverdue()))
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
                clock     <- Clock.get
                stopwatch <- Clock.stopwatch
                fiber     <- clock.sleep(5.millis)
                _         <- fiber.get
                elapsed   <- stopwatch.elapsed
            yield assert(elapsed >= 3.millis && elapsed < 100.millis)
        }

        "multiple sequential sleeps" in run {
            for
                clock     <- Clock.get
                stopwatch <- Clock.stopwatch
                fiber1    <- clock.sleep(5.millis)
                _         <- fiber1.get
                mid       <- stopwatch.elapsed
                fiber2    <- clock.sleep(5.millis)
                _         <- fiber2.get
                end       <- stopwatch.elapsed
            yield
                assert(mid >= 3.millis)
                assert(end >= 8.millis)
        }

        "sleep with zero duration" in run {
            for
                clock     <- Clock.get
                stopwatch <- Clock.stopwatch
                fiber     <- clock.sleep(Duration.Zero)
                _         <- fiber.get
                elapsed   <- stopwatch.elapsed
            yield assert(elapsed < 10.millis)
        }

        "concurrency" in run {
            for
                clock     <- Clock.get
                stopwatch <- Clock.stopwatch
                fibers    <- Kyo.fill(100)(clock.sleep(5.millis))
                _         <- Kyo.foreachDiscard(fibers)(_.get)
                elapsed   <- stopwatch.elapsed
            yield assert(elapsed >= 3.millis && elapsed < 100.millis)
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
                assert(elapsedWall >= 4.millis && elapsedWall < 40.millis)
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
                assert(elapsedWall >= 18.millis && elapsedWall < 50.millis)
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

    "TimeControl wallClockDelay" - {
        "custom delay" in run {
            Clock.withTimeControl { control =>
                for
                    executed    <- AtomicBoolean.init(false)
                    fiber       <- Clock.sleep(1.milli).map(_.onComplete(_ => executed.set(true)))
                    _           <- control.advance(5.millis, 10.millis)
                    wasExecuted <- executed.get
                yield assert(wasExecuted)
            }
        }

        "default behavior" in run {
            Clock.withTimeControl { control =>
                for
                    executed    <- AtomicBoolean.init(false)
                    fiber       <- Clock.sleep(1.milli).map(_.onComplete(_ => executed.set(true)))
                    _           <- control.advance(10.millis)
                    wasExecuted <- executed.get
                yield assert(wasExecuted)
            }
        }
    }

    def intervals(instants: Seq[Instant]): Seq[Duration] =
        instants.drop(1).sliding(2, 1).filter(_.size == 2).map(seq => seq(1) - seq(0)).toSeq

    "repeatAtInterval" - {
        "executes function at interval" in run {
            for
                channel  <- Channel.init[Instant](10)
                task     <- Clock.repeatAtInterval(5.millis)(Clock.now.map(channel.put))
                instants <- Kyo.fill(10)(channel.take)
                _        <- task.interrupt
            yield
                val avgInterval = intervals(instants).reduce(_ + _) * (1.toDouble / (instants.size - 2))
                assert(avgInterval >= 4.millis && avgInterval < 20.millis)
        }
        "respects interrupt" in run {
            for
                channel  <- Channel.init[Instant](10)
                task     <- Clock.repeatAtInterval(1.millis)(Clock.now.map(channel.put))
                instants <- Kyo.fill(10)(channel.take)
                _        <- task.interrupt
                _        <- Async.sleep(2.millis)
                _        <- untilTrue(channel.poll.map(_.isEmpty))
            yield succeed
        }
        "with Schedule parameter" in run {
            for
                channel  <- Channel.init[Instant](10)
                task     <- Clock.repeatAtInterval(Schedule.fixed(5.millis))(Clock.now.map(channel.put))
                instants <- Kyo.fill(10)(channel.take)
                _        <- task.interrupt
            yield
                val avgInterval = intervals(instants).reduce(_ + _) * (1.toDouble / (instants.size - 2))
                assert(avgInterval >= 4.millis && avgInterval < 40.millis)
        }
        "with Schedule and state" in run {
            for
                channel <- Channel.init[Int](10)
                task    <- Clock.repeatAtInterval(Schedule.fixed(1.millis), 0)(st => channel.put(st).andThen(st + 1))
                numbers <- Kyo.fill(10)(channel.take)
                _       <- task.interrupt
            yield assert(numbers.toSeq == (0 until 10))
        }
        "completes when schedule completes" in run {
            for
                channel <- Channel.init[Int](10)
                task    <- Clock.repeatAtInterval(Schedule.fixed(1.millis).maxDuration(10.millis), 0)(st => channel.put(st).andThen(st + 1))
                lastState <- task.get
                numbers   <- channel.drain
            yield assert(lastState == 10 && numbers.toSeq == (0 until 10))
        }
    }

    "repeatWithDelay" - {
        "executes function with delay" in run {
            for
                channel  <- Channel.init[Instant](10)
                task     <- Clock.repeatWithDelay(5.millis)(Clock.now.map(channel.put))
                instants <- Kyo.fill(10)(channel.take)
                _        <- task.interrupt
            yield
                val avgDelay = intervals(instants).reduce(_ + _) * (1.toDouble / (instants.size - 2))
                assert(avgDelay >= 4.millis && avgDelay < 20.millis)
        }

        "respects interrupt" in run {
            for
                channel  <- Channel.init[Instant](10)
                task     <- Clock.repeatWithDelay(1.millis)(Clock.now.map(channel.put))
                instants <- Kyo.fill(10)(channel.take)
                _        <- task.interrupt
                _        <- untilTrue(channel.poll.map(_.isEmpty))
            yield succeed
        }

        "with time control" in runNotJS {
            Clock.withTimeControl { control =>
                for
                    running  <- Latch.init(1)
                    queue    <- Queue.Unbounded.init[Instant]()
                    task     <- Clock.repeatWithDelay(1.milli)(Clock.now.map(queue.add).andThen(running.release))
                    _        <- control.advance(1.milli)
                    _        <- running.await
                    _        <- queue.drain
                    _        <- Loop.repeat(10)(control.advance(1.milli))
                    _        <- task.interrupt
                    instants <- queue.drain
                yield
                    intervals(instants).foreach(v => assert(v <= 2.millis))
                    succeed
            }
        }

        "works with Schedule parameter" in run {
            for
                channel  <- Channel.init[Instant](10)
                task     <- Clock.repeatWithDelay(Schedule.fixed(5.millis))(Clock.now.map(channel.put))
                instants <- Kyo.fill(10)(channel.take)
                _        <- task.interrupt
            yield
                val avgDelay = intervals(instants).reduce(_ + _) * (1.toDouble / (instants.size - 2))
                assert(avgDelay >= 4.millis && avgDelay < 20.millis)
        }

        "works with Schedule and state" in run {
            for
                channel <- Channel.init[Int](10)
                task <- Clock.repeatWithDelay(Schedule.fixed(1.millis), 0) { state =>
                    channel.put(state).andThen(state + 1)
                }
                numbers <- Kyo.fill(10)(channel.take)
                _       <- task.interrupt
            yield assert(numbers.toSeq == (0 until 10))
            end for
        }

        "completes when schedule completes" in run {
            for
                channel <- Channel.init[Int](10)
                task    <- Clock.repeatWithDelay(Schedule.fixed(1.millis).maxDuration(10.millis), 0)(st => channel.put(st).andThen(st + 1))
                lastState <- task.get
                numbers   <- channel.drain
            yield assert(lastState == 10 && numbers.toSeq == (0 until 10))
        }
    }

    "Monotonic Time" - {
        "nowMonotonic" in run {
            for
                time1 <- Clock.nowMonotonic
                _     <- Clock.sleep(5.millis).map(_.get)
                time2 <- Clock.nowMonotonic
            yield
                assert(time2 > time1)
                assert(time2 - time1 >= 4.millis)
                assert(time2 - time1 < 40.millis)
        }

        "with time control" in run {
            Clock.withTimeControl { control =>
                for
                    time1 <- Clock.nowMonotonic
                    _     <- control.advance(5.seconds)
                    time2 <- Clock.nowMonotonic
                yield assert(time2 - time1 == 5.seconds)
            }
        }

        "with time shift" in run {
            Clock.withTimeShift(2.0) {
                for
                    time1 <- Clock.nowMonotonic
                    _     <- Clock.sleep(10.millis).map(_.get)
                    time2 <- Clock.nowMonotonic
                yield
                    assert(time2 - time1 >= 4.millis)
                    assert(time2 - time1 < 40.millis)
            }
        }
    }

end ClockTest
