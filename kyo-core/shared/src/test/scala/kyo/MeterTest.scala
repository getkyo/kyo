package kyo

class MeterTest extends kyo.test.Test[Any]:

    "mutex" - {
        "init" in {
            Scope.run(Meter.initMutex).map: meter =>
                meter.closed.map: isClosed =>
                    assert(isClosed)
        }

        "use" in {
            Meter.useMutex(meter => Kyo.zip(meter.closed, meter)).map:
                case (isClosed1, meter) =>
                    meter.closed.map: isClosed2 =>
                        assert(!isClosed1 && isClosed2)
        }

        "ok" in {
            for
                t <- Meter.initMutex
                v <- t.run(2)
            yield assert(v == 2)
        }

        "run" in {
            for
                t  <- Meter.initMutex
                p  <- Promise.init[Int, Any]
                b1 <- Promise.init[Unit, Any]
                f1 <- Fiber.initUnscoped(t.run(b1.completeUnit.map(_ => p.getResult)))
                _  <- b1.get
                a1 <- t.availablePermits
                w1 <- t.pendingWaiters
                b2 <- Promise.init[Unit, Any]
                f2 <- Fiber.initUnscoped(b2.completeUnit.map(_ => t.run(2)))
                _  <- b2.get.andThen(assertEventually(t.pendingWaiters.map(_ > 0)))
                a2 <- t.availablePermits
                w2 <- t.pendingWaiters
                d1 <- f1.done
                d2 <- f2.done
                _  <- p.complete(Result.succeed(1))
                v1 <- f1.get
                v2 <- f2.get
                a3 <- t.availablePermits
                w3 <- t.pendingWaiters
            yield assert(
                a1 == 0 && w1 == 0 && !d1 && !d2 && a2 == 0 && w2 == 1 && v1.contains(1) && v2 == 2 && a3 == 1 && w3 == 0
            )
        }

        "tryRun" in {
            for
                sem <- Meter.initMutex
                p   <- Promise.init[Int, Any]
                b1  <- Promise.init[Unit, Any]
                f1  <- Fiber.initUnscoped(sem.tryRun(b1.completeUnit.map(_ => p.getResult)))
                _   <- b1.get
                a1  <- sem.availablePermits
                w1  <- sem.pendingWaiters
                b1  <- sem.tryRun(2)
                b2  <- f1.done
                _   <- p.complete(Result.succeed(1))
                v1  <- f1.get
            yield assert(a1 == 0 && w1 == 0 && b1.isEmpty && !b2 && v1.contains(Result.succeed(1)))
        }
    }

    "semaphore" - {
        "init" in {
            Scope.run(Meter.initSemaphore(3)).map: meter =>
                meter.closed.map: isClosed =>
                    assert(isClosed)
        }

        "use" in {
            Meter.useSemaphore(3)(meter => Kyo.zip(meter.closed, meter)).map:
                case (isClosed1, meter) =>
                    meter.closed.map: isClosed2 =>
                        assert(!isClosed1 && isClosed2)
        }

        "ok" in {
            for
                t  <- Meter.initSemaphore(2)
                v1 <- t.run(2)
                v2 <- t.run(3)
            yield assert(v1 == 2 && v2 == 3)
        }

        "run" in {
            for
                t  <- Meter.initSemaphore(2)
                p  <- Promise.init[Int, Any]
                b1 <- Promise.init[Unit, Any]
                f1 <- Fiber.initUnscoped(t.run(b1.completeUnit.andThen(p.getResult)))
                _  <- b1.get
                b2 <- Promise.init[Unit, Any]
                f2 <- Fiber.initUnscoped(t.run(b2.completeUnit.andThen(p.getResult)))
                _  <- b2.get
                a1 <- t.availablePermits
                w1 <- t.pendingWaiters
                b3 <- Promise.init[Unit, Any]
                f3 <- Fiber.initUnscoped(b3.completeUnit.andThen(t.run(2)))
                _  <- b3.get.andThen(assertEventually(t.pendingWaiters.map(_ > 0)))
                a2 <- t.availablePermits
                w2 <- t.pendingWaiters
                d1 <- f1.done
                d2 <- f2.done
                d3 <- f3.done
                _  <- p.complete(Result.succeed(1))
                v1 <- f1.get
                v2 <- f2.get
                v3 <- f3.get
                a3 <- t.availablePermits
                w3 <- t.pendingWaiters
            yield assert(a1 == 0 && w1 == 0 && !d1 && !d2 && !d3 && a2 == 0 && w2 == 1 &&
                v1.contains(1) && v2.contains(1) && v3 == 2 && a3 == 2 && w3 == 0)
        }

        "tryRun" in {
            for
                sem <- Meter.initSemaphore(2)
                p   <- Promise.init[Int, Any]
                b1  <- Promise.init[Unit, Any]
                f1  <- Fiber.initUnscoped(sem.tryRun(b1.completeUnit.map(_ => p.getResult)))
                _   <- b1.get
                a1  <- sem.availablePermits
                w1  <- sem.pendingWaiters
                b2  <- Promise.init[Unit, Any]
                f2  <- Fiber.initUnscoped(sem.tryRun(b2.completeUnit.map(_ => p.getResult)))
                _   <- b2.get
                a2  <- sem.availablePermits
                w2  <- sem.pendingWaiters
                b3  <- sem.tryRun(2)
                b4  <- f1.done
                b5  <- f2.done
                _   <- p.complete(Result.succeed(1))
                v1  <- f1.get
                v2  <- f2.get
            yield assert(a1 == 1 && w1 == 0 && b3.isEmpty && !b4 && !b5 &&
                v1.contains(Result.succeed(1)) && v2.contains(Result.succeed(1)))
        }

        "concurrency" - {

            val repeats = 10

            "run" in {
                (for
                    size    <- Choice.eval(1, 2, 3, 50, 100)
                    meter   <- Meter.initSemaphore(size)
                    counter <- AtomicInt.init(0)
                    results <-
                        Async.foreach(1 to 100, 100)(_ =>
                            Abort.run(meter.run(counter.incrementAndGet))
                        )
                    count   <- counter.get
                    permits <- meter.availablePermits
                yield
                    assert(results.count(_.isFailure) == 0)
                    assert(count == 100)
                    assert(permits == size)
                )
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .unit
            }

            "close" in {
                (for
                    size    <- Choice.eval(1, 2, 3, 50, 100)
                    meter   <- Meter.initSemaphore(size)
                    latch   <- Latch.init(1)
                    counter <- AtomicInt.init(0)
                    runFiber <- Fiber.initUnscoped(
                        latch.await.andThen(Async.fill(100, 100)(
                            Abort.run(meter.run(counter.incrementAndGet))
                        ))
                    )
                    closeFiber <- Fiber.initUnscoped(latch.await.andThen(meter.close))
                    _          <- latch.release
                    closed     <- closeFiber.get
                    completed  <- runFiber.get
                    count      <- counter.get
                    available  <- Abort.run(meter.availablePermits)
                yield
                    assert(closed)
                    assert(completed.count(_.isSuccess) <= 100)
                    assert(count <= 100)
                    assert(available.isFailure)
                )
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .unit
            }

            // A caller whose reservation is lost to a concurrent update leaves its promise queued for the
            // instant it takes to retire it. A releaser polling the queue in that instant hands the permit
            // to a promise no reservation stands behind, so the waiter it was owed to keeps waiting and the
            // meter runs one permit short from then on. The last release then sees free permits in the
            // ledger, skips the handoff, and leaves that waiter parked for good.
            "sustained contention never strands a queued waiter".onlyJvm in {
                val permits    = 2
                val callers    = 8
                val iterations = 10000
                (for
                    meter   <- Meter.initSemaphore(permits)
                    counter <- AtomicInt.init(0)
                    settled <- Abort.run[Timeout](Async.timeout(30.seconds)(
                        Async.foreach(1 to callers, callers)(_ =>
                            Loop.indexed(idx =>
                                if idx == iterations then Loop.done
                                else meter.run(counter.incrementAndGet).map(_ => Loop.continue)
                            )
                        )
                    ))
                    count <- counter.get
                yield assert(
                    settled.isSuccess,
                    s"a queued waiter was never handed a permit: $count of ${callers * iterations} calls completed"
                ))
                    .handle(Loop.repeat(20))
                    .unit
            }

            "with interruptions".onlyJvm in {
                (for
                    size    <- Choice.eval(1, 2, 3, 50)
                    meter   <- Meter.initSemaphore(size)
                    started <- Latch.init(100)
                    latch   <- Latch.init(1)
                    counter <- AtomicInt.init(0)
                    runFibers <- Kyo.foreach(1 to 100)(_ =>
                        Fiber.initUnscoped(started.release.andThen(latch.await.andThen(meter.run(counter.incrementAndGet))))
                    )
                    interruptFiber <- Fiber.initUnscoped(latch.await.andThen(
                        Async.foreach(runFibers.take(50), 50)(_.interrupt(panic))
                    ))
                    _           <- started.await
                    _           <- Async.sleep(100.millis)
                    _           <- latch.release
                    interrupted <- interruptFiber.get
                    completed   <- Kyo.foreach(runFibers)(_.getResult)
                    count       <- counter.get
                yield assert(interrupted.count(identity) + completed.count(_.isSuccess) == 100))
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .unit
            }
        }
    }

    "semaphore interrupt invariants" - {

        // A permit is held for the whole body, so no more bodies may run concurrently than the
        // meter has permits. The case under test is a waiter interrupted while parked: it never
        // acquired a permit, so withdrawing it must not admit anyone.
        "concurrency never exceeds permits while parked waiters are interrupted".onlyJvm in {
            val permits = 1
            val waiters = 32
            (for
                meter      <- Meter.initSemaphore(permits)
                live       <- AtomicInt.init(0)
                violations <- AtomicInt.init(0)
                gate       <- Latch.init(1)
                body =
                    for
                        n <- live.incrementAndGet
                        _ <- if n > permits then violations.incrementAndGet.map(_ => ()) else Sync.defer(())
                        _ <- Async.sleep(1.millis)
                        _ <- live.decrementAndGet
                    yield ()
                holder <- Fiber.initUnscoped(meter.run(gate.await.andThen(body)))
                _      <- Async.sleep(20.millis)
                parked <- Kyo.foreach(1 to waiters)(_ => Fiber.initUnscoped(meter.run(body)))
                _      <- Async.sleep(20.millis)
                _      <- Async.foreach(parked.take(waiters / 2), waiters / 2)(_.interrupt(panic))
                _      <- gate.release
                _      <- holder.getResult
                _      <- Kyo.foreach(parked)(_.getResult)
                bad    <- violations.get
            yield assert(bad == 0, s"observed $bad moments with more than $permits concurrent holders"))
                .handle(Loop.repeat(20))
                .unit
        }

        // Closing must complete EVERY parked waiter, including ones queued behind a waiter that was
        // interrupted first. A close that drains a count derived from `state` stops on the retired
        // promise the interrupted waiter left behind and strands the live ones, which hangs here.
        "close completes waiters queued behind an interrupted waiter".onlyJvm in {
            val permits = 1
            val waiters = 8
            (for
                meter  <- Meter.initSemaphore(permits)
                gate   <- Latch.init(1)
                holder <- Fiber.initUnscoped(meter.run(gate.await))
                _      <- Async.sleep(20.millis)
                parked <- Kyo.foreach(1 to waiters)(_ => Fiber.initUnscoped(meter.run(Async.sleep(1.millis))))
                _      <- Async.sleep(20.millis)
                // Interrupt the FIRST waiter, so its retired promise sits at the head of the queue
                // ahead of every still-parked waiter behind it.
                _       <- parked.head.interrupt(panic)
                _       <- Async.sleep(10.millis)
                _       <- meter.close
                _       <- gate.release
                _       <- holder.getResult
                settled <- Kyo.foreach(parked)(_.getResult)
            yield assert(settled.size == waiters, s"expected all $waiters waiters to settle, got ${settled.size}"))
                .handle(Loop.repeat(20))
                .unit
        }

        // After every fiber has settled, the permit ledger must be back to full. An interrupted
        // waiter that over-returns would show up here as more free permits than the meter has.
        "permits return to full after parked waiters are interrupted".onlyJvm in {
            val permits = 2
            val waiters = 16
            (for
                meter   <- Meter.initSemaphore(permits)
                gate    <- Latch.init(1)
                holders <- Kyo.foreach(1 to permits)(_ => Fiber.initUnscoped(meter.run(gate.await)))
                // Both states the setup depends on are readable from the meter, so wait for them
                // rather than for a duration: every permit taken, then every waiter queued behind them.
                _      <- assertEventually(Abort.run(meter.availablePermits).map(_ == Result.succeed(0)))
                parked <- Kyo.foreach(1 to waiters)(_ => Fiber.initUnscoped(meter.run(gate.await)))
                _      <- assertEventually(Abort.run(meter.pendingWaiters).map(_ == Result.succeed(waiters)))
                _      <- Async.foreach(parked, waiters)(_.interrupt(panic))
                _      <- gate.release
                _      <- Kyo.foreach(holders)(_.getResult)
                _      <- Kyo.foreach(parked)(_.getResult)
                // A settled fiber does not mean a settled ledger: the teardown that returns a permit
                // runs as the fiber unwinds, so read until it comes to rest instead of once.
                _ <- assertEventually(Abort.run(meter.availablePermits).map(_ == Result.succeed(permits)))
            yield ())
                .handle(Loop.repeat(20))
                .unit
        }

        // A caller interrupted between reserving a slot and parking used to leave its pending promise
        // queued: a later handoff would "grant" the freed permit to the dead caller and stop, so the
        // live waiter behind it was never woken. Many contenders are interrupted mid-approach (racing
        // the reserve-before-park window), then settled; the victim queued behind them must always be
        // granted the permit the holder frees.
        "interrupt racing the acquisition never strands a later waiter".onlyJvm in {
            val contenders = 30
            (for
                meter   <- Meter.initSemaphore(1)
                gate    <- Latch.init(1)
                holder  <- Fiber.initUnscoped(meter.run(gate.await))
                _       <- assertEventually(Abort.run(meter.availablePermits).map(_ == Result.succeed(0)))
                cs      <- Kyo.foreach(1 to contenders)(_ => Fiber.initUnscoped(meter.run(())))
                _       <- Async.foreach(cs, contenders)(_.interrupt(panic))
                _       <- Kyo.foreach(cs)(_.getResult)
                victim  <- Fiber.initUnscoped(meter.run(()))
                _       <- assertEventually(Abort.run(meter.pendingWaiters).map(_.exists(_ >= 1)))
                _       <- gate.release
                _       <- holder.getResult
                granted <- Abort.run[Timeout](Async.timeout(5.seconds)(victim.getResult))
            yield assert(granted.isSuccess, "the waiter behind interrupted callers was never handed the permit"))
                .handle(Loop.repeat(200))
                .unit
        }
    }

    def loop(meter: Meter, ch: Channel[Unit]): Unit < (Async & Abort[Closed]) =
        meter.run(ch.put(())).andThen(loop(meter, ch))

    val panic = Result.Panic(new Exception)

    "rate limiter" - {
        "init" in {
            Scope.run(Meter.initRateLimiter(2, 1.milli)).map: meter =>
                meter.closed.map: isClosed =>
                    assert(isClosed)
        }

        "use" in {
            Meter.useRateLimiter(2, 1.milli)(meter => Kyo.zip(meter.closed, meter)).map:
                case (isClosed1, meter) =>
                    meter.closed.map: isClosed2 =>
                        assert(!isClosed1 && isClosed2)
        }

        "ok" in {
            for
                t  <- Meter.initRateLimiter(2, 1.milli)
                v1 <- t.run(2)
                v2 <- t.run(3)
            yield assert(v1 == 2 && v2 == 3)
        }
        "one loop" in {
            // A long replenish period means no permit is replenished during the test, so the limiter
            // grants exactly its initial `rate` permits and then parks the loop. takeExactly is a
            // happens-before on those runs (no wall-clock window, so no scheduler-starvation flake);
            // the empty poll proves the limiter parked the loop instead of running unbounded.
            for
                meter <- Meter.initRateLimiter(10, 1.hour)
                ch    <- Channel.init[Unit](1000)
                f1    <- Fiber.initUnscoped(loop(meter, ch))
                runs  <- ch.takeExactly(10)
                extra <- ch.poll
                _     <- f1.interrupt(panic)
            yield assert(runs.size == 10 && extra.isEmpty)
        }
        "two loops" in {
            // Two fibers share one limiter: together they consume exactly the initial `rate` permits,
            // then both park (the long period means no replenishment happens during the test).
            for
                meter <- Meter.initRateLimiter(10, 1.hour)
                ch    <- Channel.init[Unit](1000)
                f1    <- Fiber.initUnscoped(loop(meter, ch))
                f2    <- Fiber.initUnscoped(loop(meter, ch))
                runs  <- ch.takeExactly(10)
                extra <- ch.poll
                _     <- f1.interrupt(panic)
                _     <- f2.interrupt(panic)
            yield assert(runs.size == 10 && extra.isEmpty)
        }
        "replenish doesn't overflow" in {
            for
                meter     <- Meter.initRateLimiter(5, 5.millis)
                _         <- Async.sleep(32.millis)
                available <- meter.availablePermits
            yield assert(available == 5)
        }
    }

    "pipeline" - {

        "run" in {
            // The pipeline acquires the rate limiter (2 permits) then the mutex; throughput is capped
            // by the rate limiter, so the two fibers together run exactly twice, then park (long period).
            for
                meter <- Meter.pipeline(Meter.initRateLimiter(2, 1.hour), Meter.initMutex)
                ch    <- Channel.init[Unit](1000)
                f1    <- Fiber.initUnscoped(loop(meter, ch))
                f2    <- Fiber.initUnscoped(loop(meter, ch))
                runs  <- ch.takeExactly(2)
                extra <- ch.poll
                _     <- f1.interrupt(panic)
                _     <- f2.interrupt(panic)
            yield assert(runs.size == 2 && extra.isEmpty)
        }

        "tryRun" in {
            for
                meter <- Meter.pipeline(Meter.initRateLimiter(2, 10.millis), Meter.initMutex)
                f1    <- Fiber.initUnscoped(meter.run(Async.never))
                _     <- assertEventually(meter.tryRun(()).map(_.isEmpty))
                _     <- f1.interrupt(panic)
            yield ()
        }
    }

    "reentrancy" - {
        "mutex" - {
            "reentrant by default" in {
                for
                    mutex <- Meter.initMutex
                    result <- mutex.run {
                        mutex.run {
                            mutex.run(42)
                        }
                    }
                yield assert(result == 42)
            }

            "non-reentrant" in {
                for
                    meter  <- Meter.initMutex(reentrant = false)
                    p      <- Promise.init[Int, Any]
                    f      <- Fiber.initUnscoped(meter.run(meter.run(42)))
                    _      <- Async.sleep(5.millis)
                    done   <- f.done
                    _      <- f.interrupt
                    result <- f.getResult
                yield assert(!done && result.isPanic)
            }

            "nested forked fiber can't reenter" in {
                for
                    meter <- Meter.initMutex
                    (done, result) <- meter.run {
                        meter.run {
                            for
                                f      <- Fiber.initUnscoped(meter.run(42))
                                _      <- Async.sleep(5.millis)
                                done   <- f.done
                                _      <- f.interrupt
                                result <- f.getResult
                            yield (done, result)
                        }
                    }
                yield assert(!done && result.isPanic)
            }
        }

        "semaphore" - {
            "reentrant by default" in {
                for
                    sem <- Meter.initSemaphore(1)
                    result <- sem.run {
                        sem.run {
                            sem.run(42)
                        }
                    }
                yield assert(result == 42)
            }

            "non-reentrant" in {
                for
                    meter  <- Meter.initSemaphore(1, reentrant = false)
                    p      <- Promise.init[Int, Any]
                    f      <- Fiber.initUnscoped(meter.run(meter.run(42)))
                    _      <- Async.sleep(5.millis)
                    done   <- f.done
                    _      <- f.interrupt
                    result <- f.getResult
                yield assert(!done && result.isPanic)
            }

            "nested forked fiber can't reenter" in {
                for
                    meter <- Meter.initSemaphore(1)
                    (done, result) <- meter.run {
                        meter.run {
                            for
                                f      <- Fiber.initUnscoped(meter.run(42))
                                _      <- Async.sleep(5.millis)
                                done   <- f.done
                                _      <- f.interrupt
                                result <- f.getResult
                            yield (done, result)
                        }
                    }
                yield assert(!done && result.isPanic)
            }
        }

        "rate limiter" - {
            "reentrant by default" in {
                for
                    rateLimiter <- Meter.initRateLimiter(1, 60.seconds)
                    result <- rateLimiter.run {
                        rateLimiter.run {
                            rateLimiter.run(42)
                        }
                    }
                yield assert(result == 42)
            }

            "non-reentrant" in {
                for
                    meter  <- Meter.initRateLimiter(1, 60.seconds, reentrant = false)
                    p      <- Promise.init[Int, Any]
                    f      <- Fiber.initUnscoped(meter.run(meter.run(42)))
                    _      <- Async.sleep(5.millis)
                    done   <- f.done
                    _      <- f.interrupt
                    result <- f.getResult
                yield assert(!done && result.isPanic)
            }

            "nested forked fiber can't reenter" in {
                for
                    meter <- Meter.initRateLimiter(1, 60.seconds)
                    (done, result) <- meter.run {
                        meter.run {
                            for
                                f      <- Fiber.initUnscoped(meter.run(42))
                                _      <- Async.sleep(5.millis)
                                done   <- f.done
                                _      <- f.interrupt
                                result <- f.getResult
                            yield (done, result)
                        }
                    }
                yield assert(!done && result.isPanic)
            }
        }

        "pipeline" - {
            "reentrant when all components are reentrant" in {
                for
                    mutex       <- Meter.initMutex
                    sem         <- Meter.initSemaphore(1)
                    rateLimiter <- Meter.initRateLimiter(1, 60.seconds)
                    pipeline    <- Meter.pipeline(mutex, sem, rateLimiter)
                    result <- pipeline.run {
                        pipeline.run {
                            pipeline.run(42)
                        }
                    }
                yield assert(result == 42)
            }

            "non-reentrant when any component is non-reentrant" in {
                for
                    mutex       <- Meter.initMutex
                    sem         <- Meter.initSemaphore(1, reentrant = false)
                    rateLimiter <- Meter.initRateLimiter(1, 60.seconds)
                    pipeline    <- Meter.pipeline(mutex, sem, rateLimiter)
                    p           <- Promise.init[Int, Any]
                    f <- Fiber.initUnscoped(pipeline.run {
                        pipeline.run(42)
                    })
                    _      <- Async.sleep(5.millis)
                    done   <- f.done
                    _      <- f.interrupt
                    result <- f.getResult
                yield assert(!done && result.isPanic)
            }

            "nested forked fiber can't reenter" in {
                for
                    mutex       <- Meter.initMutex
                    sem         <- Meter.initSemaphore(1)
                    rateLimiter <- Meter.initRateLimiter(1, 60.seconds)
                    meter       <- Meter.pipeline(mutex, sem, rateLimiter)
                    (done, result) <- meter.run {
                        meter.run {
                            for
                                f      <- Fiber.initUnscoped(meter.run(42))
                                _      <- Async.sleep(5.millis)
                                done   <- f.done
                                _      <- f.interrupt
                                result <- f.getResult
                            yield (done, result)
                        }
                    }
                yield assert(!done && result.isPanic)
            }
        }
    }

end MeterTest
