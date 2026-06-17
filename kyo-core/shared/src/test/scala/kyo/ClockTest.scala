package kyo

import kyo.Clock.Deadline
import kyo.Clock.Stopwatch

class ClockTest extends kyo.test.Test[Any]:

    // Wall-clock timing assertions (Clock.sleep/withTimeShift bounds) cannot tolerate being
    // preempted by concurrent leaves, so run this suite's leaves sequentially.
    override def config = super.config.sequential

    "Clock" - {
        def javaNow() = Instant.fromJava(java.time.Instant.now())

        "now" in {
            Clock.now.map { now =>
                assert(now - javaNow() < 1.milli)
            }
        }

        "nowWith" in {
            Clock.nowWith { now =>
                assert(now - javaNow() < 1.milli)
            }
        }

        "unsafe now" in {
            import AllowUnsafe.embrace.danger
            val now = Clock.live.unsafe.now()
            assert(now - javaNow() < 1.milli)
        }

        "now at epoch" in {
            Clock.withTimeControl { control =>
                for
                    _   <- control.set(Instant.Epoch)
                    now <- Clock.now
                yield assert(now == Instant.Epoch)
            }
        }

        "now at max instant" in {
            Clock.withTimeControl { control =>
                for
                    _   <- control.set(Instant.Max)
                    now <- Clock.now
                yield assert(now == Instant.Max)
            }
        }

        "nested time control reuses current control" in {
            Clock.withTimeControl { outer =>
                for
                    _ <- outer.set(Instant.Epoch)
                    result <- Clock.withTimeControl { inner =>
                        for
                            _   <- inner.advance(1.second)
                            now <- Clock.now
                        yield (outer.asInstanceOf[AnyRef] eq inner.asInstanceOf[AnyRef], now)
                    }
                    now <- Clock.now
                yield
                    assert(result == (true, Instant.Epoch + 1.second))
                    assert(now == Instant.Epoch + 1.second)
            }
        }
    }

    "Stopwatch" - {
        "elapsed time" in {
            Clock.withTimeControl { control =>
                for
                    stopwatch <- Clock.stopwatch
                    _         <- control.advance(5.seconds)
                    elapsed   <- stopwatch.elapsed
                yield assert(elapsed == 5.seconds)
                end for
            }
        }

        "unsafe elapsed time" in {
            import AllowUnsafe.embrace.danger
            Clock.withTimeControl { control =>
                for
                    clock     <- Clock.get
                    stopwatch <- Sync.Unsafe.defer(clock.unsafe.stopwatch())
                    _         <- control.advance(5.seconds)
                yield assert(stopwatch.elapsed() == 5.seconds)
                end for
            }
        }

        "zero elapsed time" in {
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
        "timeLeft" in {
            Clock.withTimeControl { control =>
                for
                    deadline <- Clock.deadline(10.seconds)
                    _        <- control.advance(3.seconds)
                    timeLeft <- deadline.timeLeft
                yield assert(timeLeft == 7.seconds)
                end for
            }
        }

        "isOverdue" in {
            Clock.withTimeControl { control =>
                for
                    deadline   <- Clock.deadline(5.seconds)
                    notOverdue <- deadline.isOverdue
                    _          <- control.advance(6.seconds)
                    overdue    <- deadline.isOverdue
                yield assert(!notOverdue && overdue)
            }
        }

        "unsafe timeLeft" in {
            import AllowUnsafe.embrace.danger
            Clock.withTimeControl { control =>
                for
                    clock    <- Clock.get
                    deadline <- Sync.Unsafe.defer(clock.unsafe.deadline(10.seconds))
                    _        <- control.advance(3.seconds)
                yield assert(deadline.timeLeft() == 7.seconds)
            }
        }

        "unsafe isOverdue" in {
            import AllowUnsafe.embrace.danger
            Clock.withTimeControl { control =>
                for
                    deadline <- Clock.deadline(5.seconds)
                    _        <- Sync.Unsafe.defer(assert(!deadline.unsafe.isOverdue()))
                    _        <- control.advance(6.seconds)
                yield assert(deadline.unsafe.isOverdue())
            }
        }

        "zero duration deadline" in {
            Clock.withTimeControl { control =>
                for
                    deadline  <- Clock.deadline(Duration.Zero)
                    isOverdue <- deadline.isOverdue
                    timeLeft  <- deadline.timeLeft
                yield assert(!isOverdue && timeLeft == Duration.Zero)
            }
        }

        "deadline exactly at expiration" in {
            Clock.withTimeControl { control =>
                for
                    deadline  <- Clock.deadline(5.seconds)
                    _         <- control.advance(5.seconds)
                    isOverdue <- deadline.isOverdue
                    timeLeft  <- deadline.timeLeft
                yield assert(!isOverdue && timeLeft == Duration.Zero)
            }
        }

        "handle Zero timeLeft" in {
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

        "handle Infinity timeLeft" in {
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
            "deadline with Zero duration" in {
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

            "deadline with Infinity duration" in {
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
        "using stopwatch with deadline" in {
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

        "multiple stopwatches and deadlines" in {
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
        "sleep for specified duration" in {
            for
                clock     <- Clock.get
                stopwatch <- Clock.stopwatch
                fiber     <- clock.sleep(5.millis)
                _         <- fiber.get
                elapsed   <- stopwatch.elapsed
            yield assert(elapsed >= 3.millis && elapsed < 100.millis)
        }

        "multiple sequential sleeps" in {
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

        "sleep with zero duration" in {
            for
                clock     <- Clock.get
                stopwatch <- Clock.stopwatch
                fiber     <- clock.sleep(Duration.Zero)
                _         <- fiber.get
                elapsed   <- stopwatch.elapsed
            yield assert(elapsed < 10.millis)
        }

        "concurrency" in {
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
        "speed up time" in {
            for
                wallStart <- Clock.now
                // 80ms shifted -> ~40ms wall. elapsedWall captures wallEnd AFTER the shifted block
                // exits, so it includes the post-sleep scheduling gap (which elapsedShifted does not);
                // a short sleep makes `elapsedShifted > elapsedWall` flip whenever that gap exceeds the
                // wall sleep. config.sequential only orders this suite's leaves, not other suites
                // sharing the process-global pool, so under CI contention the gap can be large. Sleeping
                // long enough keeps the wall sleep well above the gap.
                shiftedEnd <- Clock.withTimeShift(2)(Clock.sleep(80.millis).map(_.get.andThen(Clock.now)))
                wallEnd    <- Clock.now
            yield
                val elapsedWall    = wallEnd - wallStart
                val elapsedShifted = shiftedEnd - wallStart
                assert(elapsedWall >= 25.millis && elapsedWall < 400.millis)
                assert(elapsedShifted > elapsedWall)
        }

        "slow down time" in {
            for
                wallStart  <- Clock.now
                shiftedEnd <- Clock.withTimeShift(0.1)(Clock.sleep(2.millis).map(_.get.andThen(Clock.now)))
                wallEnd    <- Clock.now
            yield
                val elapsedWall    = wallEnd - wallStart
                val elapsedShifted = shiftedEnd - wallStart
                assert(elapsedWall >= 18.millis && elapsedWall < 200.millis)
                assert(elapsedShifted < elapsedWall)
        }

        "with time control" in {
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
        "custom delay" in {
            Clock.withTimeControl { control =>
                for
                    executed    <- AtomicBoolean.init(false)
                    fiber       <- Clock.sleep(1.milli).map(_.onComplete(_ => executed.set(true)))
                    _           <- control.advance(5.millis, 10.millis)
                    wasExecuted <- executed.get
                yield assert(wasExecuted)
            }
        }

        "default behavior" in {
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
        "executes function at interval" in {
            for
                channel  <- Channel.init[Instant](10)
                task     <- Clock.repeatAtInterval(5.millis)(Clock.now.map(channel.put))
                instants <- Kyo.fill(10)(channel.take)
                _        <- task.interrupt
            yield
                val avgInterval = intervals(instants).reduce(_ + _) * (1.toDouble / (instants.size - 2))
                assert(avgInterval >= 4.millis && avgInterval < 100.millis)
        }
        "respects interrupt" in {
            for
                channel  <- Channel.init[Instant](10)
                task     <- Clock.repeatAtInterval(1.millis)(Clock.now.map(channel.put))
                instants <- Kyo.fill(10)(channel.take)
                _        <- task.interrupt
                _        <- Async.sleep(2.millis)
                _        <- assertEventually(channel.poll.map(_.isEmpty))
            yield ()
        }
        "with Schedule parameter" in {
            for
                channel  <- Channel.init[Instant](10)
                task     <- Clock.repeatAtInterval(Schedule.fixed(5.millis))(Clock.now.map(channel.put))
                instants <- Kyo.fill(10)(channel.take)
                _        <- task.interrupt
            yield
                val avgInterval = intervals(instants).reduce(_ + _) * (1.toDouble / (instants.size - 2))
                assert(avgInterval >= 4.millis && avgInterval < 100.millis)
        }
        "with Schedule and state" in {
            for
                channel <- Channel.init[Int](10)
                task    <- Clock.repeatAtInterval(Schedule.fixed(1.millis), 0)(st => channel.put(st).andThen(st + 1))
                numbers <- Kyo.fill(10)(channel.take)
                _       <- task.interrupt
            yield assert(numbers.toSeq == (0 until 10))
        }
        "completes when schedule completes" in {
            for
                channel <- Channel.init[Int](10)
                task    <- Clock.repeatAtInterval(Schedule.fixed(1.millis).maxDuration(10.millis), 0)(st => channel.put(st).andThen(st + 1))
                lastState <- task.get
                numbers   <- channel.drain
            yield assert(lastState == 10 && numbers.toSeq == (0 until 10))
        }
    }

    "repeatWithDelay" - {
        "executes function with delay" in {
            for
                channel  <- Channel.init[Instant](10)
                task     <- Clock.repeatWithDelay(5.millis)(Clock.now.map(channel.put))
                instants <- Kyo.fill(10)(channel.take)
                _        <- task.interrupt
            yield
                val avgDelay = intervals(instants).reduce(_ + _) * (1.toDouble / (instants.size - 2))
                assert(avgDelay >= 4.millis && avgDelay < 100.millis)
        }

        "respects interrupt" in {
            for
                channel  <- Channel.init[Instant](10)
                task     <- Clock.repeatWithDelay(1.millis)(Clock.now.map(channel.put))
                instants <- Kyo.fill(10)(channel.take)
                _        <- task.interrupt
                _        <- assertEventually(channel.poll.map(_.isEmpty))
            yield ()
        }

        "with time control".notJs in {
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
                    ()
            }
        }

        "works with Schedule parameter" in {
            for
                channel  <- Channel.init[Instant](10)
                task     <- Clock.repeatWithDelay(Schedule.fixed(5.millis))(Clock.now.map(channel.put))
                instants <- Kyo.fill(10)(channel.take)
                _        <- task.interrupt
            yield
                val avgDelay = intervals(instants).reduce(_ + _) * (1.toDouble / (instants.size - 2))
                assert(avgDelay >= 4.millis && avgDelay < 100.millis)
        }

        "works with Schedule and state" in {
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

        "completes when schedule completes" in {
            for
                channel <- Channel.init[Int](10)
                task    <- Clock.repeatWithDelay(Schedule.fixed(1.millis).maxDuration(10.millis), 0)(st => channel.put(st).andThen(st + 1))
                lastState <- task.get
                numbers   <- channel.drain
            yield assert(lastState == 10 && numbers.toSeq == (0 until 10))
        }
    }

    "Monotonic Time" - {
        "nowMonotonic" in {
            for
                time1 <- Clock.nowMonotonic
                _     <- Clock.sleep(5.millis).map(_.get)
                time2 <- Clock.nowMonotonic
            yield
                assert(time2 > time1)
                assert(time2 - time1 >= 4.millis)
                assert(time2 - time1 < 40.millis)
        }

        "with time control" in {
            Clock.withTimeControl { control =>
                for
                    time1 <- Clock.nowMonotonic
                    _     <- control.advance(5.seconds)
                    time2 <- Clock.nowMonotonic
                yield assert(time2 - time1 == 5.seconds)
            }
        }

        "with time shift" in {
            Clock.withTimeShift(2.0) {
                for
                    time1 <- Clock.nowMonotonic
                    _     <- Clock.sleep(10.millis).map(_.get)
                    time2 <- Clock.nowMonotonic
                yield
                    assert(time2 - time1 >= 4.millis)
                    assert(time2 - time1 < 500.millis)
            }
        }
    }

end ClockTest
