package kyo

class MeterTest extends Test:

    "mutex" - {
        "ok" in runJVM {
            for
                t <- Meter.initMutex
                v <- t.run(2)
            yield assert(v == 2)
        }

        "run" in runJVM {
            for
                t  <- Meter.initMutex
                p  <- Promise.init[Nothing, Int]
                b1 <- Promise.init[Nothing, Unit]
                f1 <- Async.run(t.run(b1.complete(Result.unit).map(_ => p.block(Duration.Infinity))))
                _  <- b1.get
                a1 <- t.availablePermits
                w1 <- t.pendingWaiters
                b2 <- Promise.init[Nothing, Unit]
                f2 <- Async.run(b2.complete(Result.unit).map(_ => t.run(2)))
                _  <- b2.get
                a2 <- t.availablePermits
                w2 <- t.pendingWaiters
                d1 <- f1.done
                d2 <- f2.done
                _  <- p.complete(Result.success(1))
                v1 <- f1.get
                v2 <- f2.get
                a3 <- t.availablePermits
                w3 <- t.pendingWaiters
            yield assert(a1 == 0 && w1 == 0 && !d1 && !d2 && a2 == 0 && w2 == 1 && v1 == Result.success(1) && v2 == 2 && a3 == 1 && w3 == 0)
        }

        "tryRun" in runJVM {
            for
                sem <- Meter.initMutex
                p   <- Promise.init[Nothing, Int]
                b1  <- Promise.init[Nothing, Unit]
                f1  <- Async.run(sem.tryRun(b1.complete(Result.unit).map(_ => p.block(Duration.Infinity))))
                _   <- b1.get
                a1  <- sem.availablePermits
                w1  <- sem.pendingWaiters
                b1  <- sem.tryRun(2)
                b2  <- f1.done
                _   <- p.complete(Result.success(1))
                v1  <- f1.get
            yield assert(a1 == 0 && w1 == 0 && b1.isEmpty && !b2 && v1.contains(Result.success(1)))
        }
    }

    "semaphore" - {
        "ok" in runJVM {
            for
                t  <- Meter.initSemaphore(2)
                v1 <- t.run(2)
                v2 <- t.run(3)
            yield assert(v1 == 2 && v2 == 3)
        }

        "run" in runJVM {
            for
                t  <- Meter.initSemaphore(2)
                p  <- Promise.init[Nothing, Int]
                b1 <- Promise.init[Nothing, Unit]
                f1 <- Async.run(t.run(b1.complete(Result.unit).map(_ => p.block(Duration.Infinity))))
                _  <- b1.get
                b2 <- Promise.init[Nothing, Unit]
                f2 <- Async.run(t.run(b2.complete(Result.unit).map(_ => p.block(Duration.Infinity))))
                _  <- b2.get
                a1 <- t.availablePermits
                w1 <- t.pendingWaiters
                b3 <- Promise.init[Nothing, Unit]
                f3 <- Async.run(b3.complete(Result.unit).map(_ => t.run(2)))
                _  <- b3.get
                a2 <- t.availablePermits
                w2 <- t.pendingWaiters
                d1 <- f1.done
                d2 <- f2.done
                d3 <- f3.done
                _  <- p.complete(Result.success(1))
                v1 <- f1.get
                v2 <- f2.get
                v3 <- f3.get
                a3 <- t.availablePermits
                w3 <- t.pendingWaiters
            yield assert(a1 == 0 && w1 == 0 && !d1 && !d2 && !d3 && a2 == 0 && w2 == 1 &&
                v1 == Result.success(1) && v2 == Result.success(1) && v3 == 2 && a3 == 2 && w3 == 0)
        }

        "tryRun" in runJVM {
            for
                sem <- Meter.initSemaphore(2)
                p   <- Promise.init[Nothing, Int]
                b1  <- Promise.init[Nothing, Unit]
                f1  <- Async.run(sem.tryRun(b1.complete(Result.unit).map(_ => p.block(Duration.Infinity))))
                _   <- b1.get
                a1  <- sem.availablePermits
                w1  <- sem.pendingWaiters
                b2  <- Promise.init[Nothing, Unit]
                f2  <- Async.run(sem.tryRun(b2.complete(Result.unit).map(_ => p.block(Duration.Infinity))))
                _   <- b2.get
                a2  <- sem.availablePermits
                w2  <- sem.pendingWaiters
                b3  <- sem.tryRun(2)
                b4  <- f1.done
                b5  <- f2.done
                _   <- p.complete(Result.success(1))
                v1  <- f1.get
                v2  <- f2.get
            yield assert(a1 == 1 && w1 == 0 && b3.isEmpty && !b4 && !b5 && v1.contains(Result.success(1)) && v2.contains(Result.success(1)))
        }

        "concurrency" - {

            val repeats = 100

            "run" in run {
                (for
                    size    <- Choice.get(Seq(1, 2, 3, 50, 100))
                    meter   <- Meter.initSemaphore(size)
                    counter <- AtomicInt.init(0)
                    results <-
                        Async.parallel((1 to 100).map(_ =>
                            Abort.run(meter.run(counter.incrementAndGet))
                        ))
                    count   <- counter.get
                    permits <- meter.availablePermits
                yield
                    assert(results.count(_.isFail) == 0)
                    assert(count == 100)
                    assert(permits == size)
                )
                    .pipe(Choice.run, _.unit, Loop.repeat(repeats))
                    .andThen(succeed)
            }

            "close" in run {
                (for
                    size    <- Choice.get(Seq(1, 2, 3, 50, 100))
                    meter   <- Meter.initSemaphore(size)
                    latch   <- Latch.init(1)
                    counter <- AtomicInt.init(0)
                    runFiber <- Async.run(
                        latch.await.andThen(Async.parallel((1 to 100).map(_ =>
                            Abort.run(meter.run(counter.incrementAndGet))
                        )))
                    )
                    closeFiber <- Async.run(latch.await.andThen(meter.close))
                    _          <- latch.release
                    closed     <- closeFiber.get
                    completed  <- runFiber.get
                    count      <- counter.get
                    available  <- Abort.run(meter.availablePermits)
                yield
                    assert(closed)
                    assert(completed.count(_.isSuccess) <= 100)
                    assert(count <= 100)
                    assert(available.isFail)
                )
                    .pipe(Choice.run, _.unit, Loop.repeat(repeats))
                    .andThen(succeed)
            }

            "with interruptions" in run {
                (for
                    size    <- Choice.get(Seq(1, 2, 3, 50, 100))
                    meter   <- Meter.initSemaphore(size)
                    latch   <- Latch.init(1)
                    counter <- AtomicInt.init(0)
                    runFibers <- Kyo.foreach(1 to 100)(_ =>
                        Async.run(latch.await.andThen(meter.run(counter.incrementAndGet)))
                    )
                    interruptFiber <- Async.run(latch.await.andThen(Async.parallel(
                        runFibers.take(50).map(_.interrupt(panic))
                    )))
                    _           <- latch.release
                    interrupted <- interruptFiber.get
                    completed   <- Kyo.foreach(runFibers)(_.getResult)
                    count       <- counter.get
                yield assert(interrupted.count(identity) + completed.count(_.isSuccess) == 100))
                    .pipe(Choice.run, _.unit, Loop.repeat(repeats))
                    .andThen(succeed)
            }
        }
    }

    def loop(meter: Meter, counter: AtomicInt): Unit < (Async & Abort[Closed]) =
        meter.run(counter.incrementAndGet).map(_ => loop(meter, counter))

    val panic = Result.Panic(new Exception)

    "rate limiter" - {
        "ok" in runJVM {
            for
                t  <- Meter.initRateLimiter(2, 1.milli)
                v1 <- t.run(2)
                v2 <- t.run(3)
            yield assert(v1 == 2 && v2 == 3)
        }
        "one loop" in runJVM {
            for
                meter   <- Meter.initRateLimiter(10, 1.milli)
                counter <- AtomicInt.init(0)
                f1      <- Async.run(loop(meter, counter))
                _       <- Async.sleep(5.millis)
                _       <- f1.interrupt(panic)
                v1      <- counter.get
            yield assert(v1 >= 2 && v1 <= 200)
        }
        "two loops" in runJVM {
            for
                meter   <- Meter.initRateLimiter(10, 1.milli)
                counter <- AtomicInt.init(0)
                f1      <- Async.run(loop(meter, counter))
                f2      <- Async.run(loop(meter, counter))
                _       <- Async.sleep(5.millis)
                _       <- f1.interrupt(panic)
                _       <- f2.interrupt(panic)
                v1      <- counter.get
            yield assert(v1 >= 2 && v1 <= 200)
        }
        "replenish doesn't overflow" in runJVM {
            for
                meter     <- Meter.initRateLimiter(5, 5.millis)
                _         <- Async.sleep(32.millis)
                available <- meter.availablePermits
            yield assert(available == 5)
        }
    }

    "pipeline" - {

        "run" in runJVM {
            for
                meter   <- Meter.pipeline(Meter.initRateLimiter(2, 1.milli), Meter.initMutex)
                counter <- AtomicInt.init(0)
                f1      <- Async.run(loop(meter, counter))
                f2      <- Async.run(loop(meter, counter))
                _       <- Async.sleep(5.millis)
                _       <- f1.interrupt(panic)
                _       <- f2.interrupt(panic)
                v1      <- counter.get
            yield assert(v1 >= 0 && v1 < 200)
        }

        "tryRun" in runJVM {
            for
                meter   <- Meter.pipeline(Meter.initRateLimiter(2, 5.millis), Meter.initMutex)
                counter <- AtomicInt.init(0)
                f1      <- Async.run(loop(meter, counter))
                _       <- Async.sleep(5.millis)
                _       <- untilTrue(meter.availablePermits.map(_ == 0))
                _       <- Async.sleep(5.millis)
                r       <- meter.tryRun(())
                _       <- f1.interrupt(panic)
            yield assert(r.isEmpty)
        }
    }

    "reentrancy" - {
        "mutex" - {
            "reentrant by default" in runJVM {
                for
                    mutex <- Meter.initMutex
                    result <- mutex.run {
                        mutex.run {
                            mutex.run(42)
                        }
                    }
                yield assert(result == 42)
            }

            "non-reentrant" in runJVM {
                for
                    meter  <- Meter.initMutex(reentrant = false)
                    p      <- Promise.init[Nothing, Int]
                    f      <- Async.run(meter.run(meter.run(42)))
                    _      <- Async.sleep(5.millis)
                    done   <- f.done
                    _      <- f.interrupt
                    result <- f.getResult
                yield assert(!done && result.isPanic)
            }

            "nested forked fiber can't reenter" in runJVM {
                for
                    meter <- Meter.initMutex
                    (done, result) <- meter.run {
                        meter.run {
                            for
                                f      <- Async.run(meter.run(42))
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
            "reentrant by default" in runJVM {
                for
                    sem <- Meter.initSemaphore(1)
                    result <- sem.run {
                        sem.run {
                            sem.run(42)
                        }
                    }
                yield assert(result == 42)
            }

            "non-reentrant" in runJVM {
                for
                    meter  <- Meter.initSemaphore(1, reentrant = false)
                    p      <- Promise.init[Nothing, Int]
                    f      <- Async.run(meter.run(meter.run(42)))
                    _      <- Async.sleep(5.millis)
                    done   <- f.done
                    _      <- f.interrupt
                    result <- f.getResult
                yield assert(!done && result.isPanic)
            }

            "nested forked fiber can't reenter" in runJVM {
                for
                    meter <- Meter.initSemaphore(1)
                    (done, result) <- meter.run {
                        meter.run {
                            for
                                f      <- Async.run(meter.run(42))
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
            "reentrant by default" in runJVM {
                for
                    rateLimiter <- Meter.initRateLimiter(1, 60.seconds)
                    result <- rateLimiter.run {
                        rateLimiter.run {
                            rateLimiter.run(42)
                        }
                    }
                yield assert(result == 42)
            }

            "non-reentrant" in runJVM {
                for
                    meter  <- Meter.initRateLimiter(1, 60.seconds, reentrant = false)
                    p      <- Promise.init[Nothing, Int]
                    f      <- Async.run(meter.run(meter.run(42)))
                    _      <- Async.sleep(5.millis)
                    done   <- f.done
                    _      <- f.interrupt
                    result <- f.getResult
                yield assert(!done && result.isPanic)
            }

            "nested forked fiber can't reenter" in runJVM {
                for
                    meter <- Meter.initRateLimiter(1, 60.seconds)
                    (done, result) <- meter.run {
                        meter.run {
                            for
                                f      <- Async.run(meter.run(42))
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
            "reentrant when all components are reentrant" in runJVM {
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

            "non-reentrant when any component is non-reentrant" in runJVM {
                for
                    mutex       <- Meter.initMutex
                    sem         <- Meter.initSemaphore(1, reentrant = false)
                    rateLimiter <- Meter.initRateLimiter(1, 60.seconds)
                    pipeline    <- Meter.pipeline(mutex, sem, rateLimiter)
                    p           <- Promise.init[Nothing, Int]
                    f <- Async.run(pipeline.run {
                        pipeline.run(42)
                    })
                    _      <- Async.sleep(5.millis)
                    done   <- f.done
                    _      <- f.interrupt
                    result <- f.getResult
                yield assert(!done && result.isPanic)
            }

            "nested forked fiber can't reenter" in runJVM {
                for
                    mutex       <- Meter.initMutex
                    sem         <- Meter.initSemaphore(1)
                    rateLimiter <- Meter.initRateLimiter(1, 60.seconds)
                    meter       <- Meter.pipeline(mutex, sem, rateLimiter)
                    (done, result) <- meter.run {
                        meter.run {
                            for
                                f      <- Async.run(meter.run(42))
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
