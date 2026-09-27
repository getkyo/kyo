package kyo

import kyo.Clock.Deadline
import kyo.Clock.Stopwatch

class ClockTest extends kyo.test.Test[Any]:

    // The `now`/`nowWith`/`unsafe now` leaves assert live `Clock.now` stays within a sub-millisecond window of
    // `java.time.Instant.now()`, which cannot tolerate preemption by concurrent leaves, so run this suite sequentially.
    override def config = super.config.sequential

    "Clock" - {
        def javaNow() = Instant.fromJava(java.time.Instant.now())

        "now" in {
            Clock.now.map { now =>
                assert(now.minusOrZero(javaNow()) < 1.milli)
            }
        }

        "nowWith" in {
            Clock.nowWith { now =>
                assert(now.minusOrZero(javaNow()) < 1.milli)
            }
        }

        "unsafe now" in {
            import AllowUnsafe.embrace.danger
            val now = Clock.live.unsafe.now()
            assert(now.minusOrZero(javaNow()) < 1.milli)
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
                    _      <- outer.set(Instant.Epoch)
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

        "a wall-clock step" - {
            import AllowUnsafe.embrace.danger

            val start = Instant.Epoch + 1000.days

            def stepped[A](f: (Clock.TimeControl, Clock, java.util.concurrent.atomic.AtomicReference[Instant]) => A < (Async & Abort[Any]))(
                using Frame
            ): A < (Async & Abort[Any]) =
                Clock.withTimeControl { control =>
                    Clock.get.map { controlled =>
                        val wall = new java.util.concurrent.atomic.AtomicReference(start)
                        f(control, Clock(Clock.Unsafe.withWall(controlled.unsafe)(() => wall.get())), wall)
                    }
                }

            "forward leaves timeLeft and isOverdue alone" in {
                stepped { (control, clock, wall) =>
                    for
                        deadline <- clock.deadline(10.seconds)
                        _        <- Sync.defer(wall.set(start + 1.hour))
                        left     <- deadline.timeLeft
                        overdue  <- deadline.isOverdue
                    yield
                        assert(left == 10.seconds)
                        assert(!overdue)
                }
            }

            "backward leaves timeLeft alone" in {
                stepped { (control, clock, wall) =>
                    for
                        deadline <- clock.deadline(10.seconds)
                        _        <- Sync.defer(wall.set(start - 1.hour))
                        left     <- deadline.timeLeft
                    yield assert(left == 10.seconds)
                }
            }

            "backward leaves an overdue deadline overdue" in {
                stepped { (control, clock, wall) =>
                    for
                        deadline <- clock.deadline(5.seconds)
                        _        <- control.advance(6.seconds)
                        _        <- Sync.defer(wall.set(start - 1.hour))
                        overdue  <- deadline.isOverdue
                        left     <- deadline.timeLeft
                    yield
                        assert(overdue)
                        assert(left == Duration.Zero)
                }
            }

            "monotonic progress still drains the deadline" in {
                stepped { (control, clock, wall) =>
                    for
                        deadline <- clock.deadline(10.seconds)
                        _        <- Sync.defer(wall.set(start + 1.day))
                        _        <- control.advance(4.seconds)
                        left     <- deadline.timeLeft
                    yield assert(left == 6.seconds)
                }
            }

            "an infinite deadline is never overdue" in {
                stepped { (control, clock, wall) =>
                    for
                        deadline <- clock.deadline(Duration.Infinity)
                        _        <- Sync.defer(wall.set(Instant.Max))
                        overdue  <- deadline.isOverdue
                        left     <- deadline.timeLeft
                    yield
                        assert(!overdue)
                        assert(left == Duration.Infinity)
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
            Clock.withTimeControl { control =>
                for
                    clock     <- Clock.get
                    stopwatch <- Clock.stopwatch
                    fiber     <- clock.sleep(5.millis)
                    _         <- control.advance(5.millis)
                    _         <- fiber.get
                    elapsed   <- stopwatch.elapsed
                yield assert(elapsed == 5.millis)
            }
        }

        "multiple sequential sleeps" in {
            Clock.withTimeControl { control =>
                for
                    clock     <- Clock.get
                    stopwatch <- Clock.stopwatch
                    fiber1    <- clock.sleep(5.millis)
                    _         <- control.advance(5.millis)
                    _         <- fiber1.get
                    mid       <- stopwatch.elapsed
                    fiber2    <- clock.sleep(5.millis)
                    _         <- control.advance(5.millis)
                    _         <- fiber2.get
                    end       <- stopwatch.elapsed
                yield
                    assert(mid == 5.millis)
                    assert(end == 10.millis)
            }
        }

        "sleep with zero duration" in {
            Clock.withTimeControl { control =>
                for
                    clock     <- Clock.get
                    stopwatch <- Clock.stopwatch
                    fiber     <- clock.sleep(Duration.Zero)
                    _         <- fiber.get
                    elapsed   <- stopwatch.elapsed
                yield assert(elapsed == Duration.Zero)
            }
        }

        // An infinite sleep is a park, and on Node it silently was not one. Duration.Infinity is 9223372036854 ms, past the 32-bit signed
        // millisecond argument the host's timers take, so the delay overflowed and Node clamped it to 1 ms. The sleep returned at once, the
        // run block completed, the application's Scope closed, and every Scope-managed resource shut down, while unscoped fibers kept the
        // process alive and logging: an HTTP server with no listener behind a process whose every liveness signal said it was healthy.
        "sleep with an infinite duration parks instead of returning" in {
            for
                fiber   <- Clock.sleep(Duration.Infinity)
                settled <- Async.timeout(200.millis)(fiber.get).handle(Abort.run[Timeout])
            // A failure here is the timeout firing, which is the sleep still parked. A success is the sleep having returned.
            yield assert(settled.isFailure, s"an infinite sleep completed: $settled")
        }

        "concurrency" in {
            Clock.withTimeControl { control =>
                for
                    clock     <- Clock.get
                    stopwatch <- Clock.stopwatch
                    fibers    <- Kyo.fill(100)(clock.sleep(5.millis))
                    _         <- control.advance(5.millis)
                    _         <- Kyo.foreachDiscard(fibers)(_.get)
                    elapsed   <- stopwatch.elapsed
                yield assert(elapsed == 5.millis)
            }
        }
    }

    "TimeShift" - {
        "speed up time" in {
            Clock.withTimeControl { control =>
                Clock.withTimeShift(2.0) {
                    for
                        start <- Clock.now
                        fiber <- Clock.sleep(80.millis)
                        _     <- control.advance(40.millis)
                        _     <- fiber.get
                        end   <- Clock.now
                    yield assert(end.minus(start) == Present(80.millis))
                }
            }
        }

        "slow down time" in {
            Clock.withTimeControl { control =>
                Clock.withTimeShift(0.1) {
                    for
                        start <- Clock.now
                        fiber <- Clock.sleep(2.millis)
                        _     <- control.advance(20.millis)
                        _     <- fiber.get
                        end   <- Clock.now
                    yield assert(end.minus(start) == Present(2.millis))
                }
            }
        }

        "with time control" in {
            Clock.withTimeControl { control =>
                Clock.withTimeShift(2.0) {
                    for
                        start <- Clock.now
                        _     <- control.advance(5.seconds)
                        end   <- Clock.now
                    yield assert(end.minus(start) == Present(10.seconds))
                }
            }
        }
    }

    "TimeOffset" - {
        import Clock.TimeOffset

        val start = Instant.Epoch + 1000.days

        "ahead and behind are signed nanoseconds" in {
            assert(TimeOffset.ahead(5.seconds).toNanos == 5.seconds.toNanos)
            assert(TimeOffset.behind(5.seconds).toNanos == -(5.seconds.toNanos))
            assert(TimeOffset.ahead(Duration.Zero) == TimeOffset.Zero)
            assert(TimeOffset.behind(Duration.Zero) == TimeOffset.Zero)
        }

        "between is to minus from" in {
            assert(TimeOffset.between(start, start + 3.seconds) == TimeOffset.ahead(3.seconds))
            assert(TimeOffset.between(start + 3.seconds, start) == TimeOffset.behind(3.seconds))
            assert(TimeOffset.between(start, start) == TimeOffset.Zero)
        }

        "fromNanos round-trips toNanos" in {
            val nanos = Seq(0L, 1L, -1L, 7.days.toNanos, -(7.days.toNanos), Long.MaxValue, -Long.MaxValue)
            assert(nanos.map(n => TimeOffset.fromNanos(n).toNanos) == nanos)
        }

        "fromNanos clamps Long.MinValue so the offset has a negation" in {
            assert(TimeOffset.fromNanos(Long.MinValue).toNanos == -Long.MaxValue)
        }
    }

    "withTimeOffset" - {
        import Clock.TimeOffset

        val start = Instant.Epoch + 1000.days

        "zero offset keeps the current clock" in {
            for
                outer <- Clock.get
                inner <- Clock.withTimeOffset(TimeOffset.Zero)(Clock.get)
            yield assert(inner eq outer)
        }

        "ahead displaces now forward" in {
            Clock.withTimeControl { control =>
                for
                    _   <- control.set(start)
                    now <- Clock.withTimeOffset(TimeOffset.ahead(5.minutes))(Clock.now)
                yield assert(now == start + 5.minutes)
            }
        }

        "behind displaces now backward" in {
            Clock.withTimeControl { control =>
                for
                    _   <- control.set(start)
                    now <- Clock.withTimeOffset(TimeOffset.behind(5.minutes))(Clock.now)
                yield assert(now == start - 5.minutes)
            }
        }

        "now follows the control plus the offset" in {
            Clock.withTimeControl { control =>
                for
                    _        <- control.set(start)
                    readings <- Clock.withTimeOffset(TimeOffset.behind(1.hour)) {
                        for
                            before <- Clock.now
                            _      <- control.advance(10.seconds)
                            after  <- Clock.now
                        yield (before, after)
                    }
                yield assert(readings == (start - 1.hour, start - 1.hour + 10.seconds))
            }
        }

        "nowMonotonic is the underlying clock's" in {
            Clock.withTimeControl { control =>
                for
                    _       <- control.set(start)
                    outside <- Clock.nowMonotonic
                    inside  <- Clock.withTimeOffset(TimeOffset.ahead(1.day))(Clock.nowMonotonic)
                    behind  <- Clock.withTimeOffset(TimeOffset.behind(1.day))(Clock.nowMonotonic)
                yield
                    assert(inside == outside)
                    assert(behind == outside)
            }
        }

        "nested offsets sum" in {
            Clock.withTimeControl { control =>
                for
                    _      <- control.set(start)
                    nested <- Clock.withTimeOffset(TimeOffset.behind(3.seconds)) {
                        Clock.withTimeOffset(TimeOffset.ahead(5.seconds))(Clock.now)
                    }
                    cancelled <- Clock.withTimeOffset(TimeOffset.ahead(2.hours)) {
                        Clock.withTimeOffset(TimeOffset.behind(2.hours))(Clock.now)
                    }
                yield
                    assert(nested == start + 2.seconds)
                    assert(cancelled == start)
            }
        }

        "nested offsets sum before saturating" in {
            Clock.withTimeControl { control =>
                for
                    _   <- control.set(start)
                    now <- Clock.withTimeOffset(TimeOffset.behind(Duration.Infinity)) {
                        Clock.withTimeOffset(TimeOffset.ahead(Duration.Infinity))(Clock.now)
                    }
                yield assert(now == start)
            }
        }

        "an infinite offset pins now at the instant bounds" in {
            Clock.withTimeControl { control =>
                for
                    _      <- control.set(start)
                    ahead  <- Clock.withTimeOffset(TimeOffset.ahead(Duration.Infinity))(Clock.now)
                    behind <- Clock.withTimeOffset(TimeOffset.behind(Duration.Infinity))(Clock.now)
                yield
                    assert(ahead == Instant.Max)
                    assert(behind == Instant.Min)
            }
        }

        "a sleep fires when the control advances past its duration" in {
            Clock.withTimeControl { control =>
                Clock.withTimeOffset(TimeOffset.behind(1.hour)) {
                    for
                        fiber <- Fiber.initUnscoped(Async.sleep(5.seconds))
                        _     <- control.awaitPendingSleepers(1)
                        _     <- control.advance(4.seconds)
                        early <- fiber.done
                        _     <- control.advance(1.second)
                        _     <- fiber.get
                    yield assert(!early)
                }
            }
        }

        "a sleep is not scaled by the offset" in {
            Clock.withTimeControl { control =>
                Clock.withTimeOffset(TimeOffset.ahead(1.day)) {
                    for
                        clock     <- Clock.get
                        stopwatch <- Clock.stopwatch
                        fiber     <- clock.sleep(5.millis)
                        _         <- control.advance(5.millis)
                        _         <- fiber.get
                        elapsed   <- stopwatch.elapsed
                    yield assert(elapsed == 5.millis)
                }
            }
        }

        "stopwatch measures the underlying duration" in {
            Clock.withTimeControl { control =>
                Clock.withTimeOffset(TimeOffset.behind(1.day)) {
                    for
                        stopwatch <- Clock.stopwatch
                        _         <- control.advance(3.seconds)
                        elapsed   <- stopwatch.elapsed
                    yield assert(elapsed == 3.seconds)
                }
            }
        }

        "deadline measures the underlying duration" in {
            Clock.withTimeControl { control =>
                Clock.withTimeOffset(TimeOffset.ahead(1.day)) {
                    for
                        deadline <- Clock.deadline(10.seconds)
                        _        <- control.advance(3.seconds)
                        left     <- deadline.timeLeft
                        pending  <- deadline.isOverdue
                        _        <- control.advance(8.seconds)
                        overdue  <- deadline.isOverdue
                    yield
                        assert(left == 7.seconds)
                        assert(!pending)
                        assert(overdue)
                }
            }
        }

        "Async.timeout fires by duration" in {
            Clock.withTimeControl { control =>
                Clock.withTimeOffset(TimeOffset.behind(1.day)) {
                    for
                        fiber   <- Fiber.initUnscoped(Abort.run[Timeout](Async.timeout(5.seconds)(Async.never[Int])))
                        _       <- control.awaitPendingSleepers(1)
                        _       <- control.advance(4.seconds)
                        early   <- fiber.done
                        _       <- control.advance(1.second)
                        outcome <- fiber.get
                    yield
                        assert(!early)
                        assert(outcome.isFailure)
                }
            }
        }

        "Async.timeout lets a body that finishes in time complete" in {
            Clock.withTimeControl { control =>
                Clock.withTimeOffset(TimeOffset.ahead(1.day)) {
                    for
                        fiber   <- Fiber.initUnscoped(Abort.run[Timeout](Async.timeout(5.seconds)(Async.delay(2.seconds)(42))))
                        _       <- control.awaitPendingSleepers(2)
                        _       <- control.advance(2.seconds)
                        outcome <- fiber.get
                    yield assert(outcome == Result.succeed(42))
                }
            }
        }

        "forked fibers read the displaced clock" in {
            Clock.withTimeControl { control =>
                for
                    _   <- control.set(start)
                    now <- Clock.withTimeOffset(TimeOffset.behind(2.hours)) {
                        Fiber.initUnscoped(Clock.now).map(_.get)
                    }
                yield assert(now == start - 2.hours)
            }
        }

        "withTimeControl inside the offset starts a new control" in {
            Clock.withTimeControl { outer =>
                for
                    _      <- outer.set(start)
                    result <- Clock.withTimeOffset(TimeOffset.ahead(1.hour)) {
                        Clock.withTimeControl { inner =>
                            for
                                initial <- Clock.now
                                _       <- inner.advance(1.second)
                                after   <- Clock.now
                            yield (outer.asInstanceOf[AnyRef] eq inner.asInstanceOf[AnyRef], initial, after)
                        }
                    }
                    outerNow <- Clock.now
                yield
                    assert(result == (false, Instant.Epoch, Instant.Epoch + 1.second))
                    assert(outerNow == start)
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
        instants.drop(1).sliding(2, 1).filter(_.size == 2).map(seq => seq(1).minusOrZero(seq(0))).toSeq

    "repeatAtInterval" - {
        "with time control".notJs in {
            Clock.withTimeControl { control =>
                val ticks = 12
                for
                    queue <- Queue.Unbounded.init[Instant]()
                    task  <- Clock.repeatAtInterval(5.millis)(Clock.now.map(queue.add))
                    // startAfter is zero, so the first execution fires at Epoch during startup. Fence on the fiber re-arming its next sleep
                    // before each advance, so it fires exactly once per interval: the Epoch fire plus one per tick makes the count exact.
                    _        <- control.awaitPendingSleepers(1)
                    _        <- Loop.repeat(ticks)(control.advance(5.millis).andThen(control.awaitPendingSleepers(1)))
                    _        <- task.interrupt
                    instants <- queue.drain
                yield
                    val expected = (0 to ticks).map(i => Instant.Epoch + (5 * i).millis)
                    assert(instants.size == ticks + 1)
                    assert(instants.toSeq == expected)
                end for
            }
        }
        "respects interrupt" in {
            for
                channel  <- Channel.init[Instant](10)
                task     <- Clock.repeatAtInterval(1.millis)(Clock.now.map(channel.put))
                instants <- Kyo.fill(10)(channel.take)
                _        <- task.interrupt
                _        <- assertEventually(channel.poll.map(_.isEmpty))
            yield ()
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

        "works with Schedule and state" in {
            for
                channel <- Channel.init[Int](10)
                task    <- Clock.repeatWithDelay(Schedule.fixed(1.millis), 0) { state =>
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
            Clock.withTimeControl { control =>
                for
                    time1 <- Clock.nowMonotonic
                    fiber <- Clock.sleep(5.millis)
                    _     <- control.advance(5.millis)
                    _     <- fiber.get
                    time2 <- Clock.nowMonotonic
                yield
                    assert(time2 > time1)
                    assert(time2.minus(time1) == Present(5.millis))
            }
        }

        "with time control" in {
            Clock.withTimeControl { control =>
                for
                    time1 <- Clock.nowMonotonic
                    _     <- control.advance(5.seconds)
                    time2 <- Clock.nowMonotonic
                yield assert(time2.minus(time1) == Present(5.seconds))
            }
        }

        "with time shift" in {
            Clock.withTimeControl { control =>
                Clock.withTimeShift(2.0) {
                    for
                        time1 <- Clock.nowMonotonic
                        fiber <- Clock.sleep(10.millis)
                        _     <- control.advance(5.millis)
                        _     <- fiber.get
                        time2 <- Clock.nowMonotonic
                    yield assert(time2.minus(time1) == Present(10.millis))
                }
            }
        }
    }

end ClockTest
