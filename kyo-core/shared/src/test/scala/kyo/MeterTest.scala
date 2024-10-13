package kyo

import kyo.debug.Debug

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
                a1 <- t.available
                b2 <- Promise.init[Nothing, Unit]
                f2 <- Async.run(b2.complete(Result.unit).map(_ => t.run(2)))
                _  <- b2.get
                a2 <- t.available
                d1 <- f1.done
                d2 <- f2.done
                _  <- p.complete(Result.success(1))
                v1 <- f1.get
                v2 <- f2.get
                a3 <- t.available
            yield assert(!a1 && !d1 && !d2 && !a2 && v1 == Result.success(1) && v2 == 2 && a3)
        }

        "tryRun" in runJVM {
            for
                sem <- Meter.initSemaphore(1)
                p   <- Promise.init[Nothing, Int]
                b1  <- Promise.init[Nothing, Unit]
                f1  <- Async.run(sem.tryRun(b1.complete(Result.unit).map(_ => p.block(Duration.Infinity))))
                _   <- b1.get
                a1  <- sem.available
                b1  <- sem.tryRun(2)
                b2  <- f1.done
                _   <- p.complete(Result.success(1))
                v1  <- f1.get
            yield assert(!a1 && b1.isEmpty && !b2 && v1.contains(Result.success(1)))
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
                a1 <- t.available
                b3 <- Promise.init[Nothing, Unit]
                f2 <- Async.run(b3.complete(Result.unit).map(_ => t.run(2)))
                _  <- b3.get
                a2 <- t.available
                d1 <- f1.done
                d2 <- f2.done
                _  <- p.complete(Result.success(1))
                v1 <- f1.get
                v2 <- f2.get
                a3 <- t.available
            yield assert(!a1 && !d1 && !d2 && !a2 && v1 == Result.success(1) && v2 == 2 && a3)
        }

        "tryRun" in runJVM {
            for
                sem <- Meter.initSemaphore(2)
                p   <- Promise.init[Nothing, Int]
                b1  <- Promise.init[Nothing, Unit]
                f1  <- Async.run(sem.tryRun(b1.complete(Result.unit).map(_ => p.block(Duration.Infinity))))
                _   <- b1.get
                b2  <- Promise.init[Nothing, Unit]
                f2  <- Async.run(sem.tryRun(b2.complete(Result.unit).map(_ => p.block(Duration.Infinity))))
                _   <- b2.get
                a1  <- sem.available
                b3  <- sem.tryRun(2)
                b4  <- f1.done
                b5  <- f2.done
                _   <- p.complete(Result.success(1))
                v1  <- f1.get
                v2  <- f2.get
            yield assert(!a1 && b3.isEmpty && !b4 && !b5 && v1.contains(Result.success(1)) && v2.contains(Result.success(1)))
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
                meter   <- Meter.pipeline(Meter.initRateLimiter(2, 10.millis), Meter.initMutex)
                counter <- AtomicInt.init(0)
                f1      <- Async.run(loop(meter, counter))
                _       <- Async.sleep(5.millis)
                _       <- untilTrue(meter.available.map(!_))
                _       <- Async.sleep(5.millis)
                r       <- meter.tryRun(())
                _       <- f1.interrupt(panic)
            yield assert(r.isEmpty)
        }
    }

    "concurrency" - {

        val repeats = 100

        "semaphore concurrency" in run {
            (for
                size    <- Choice.get(Seq(1, 2, 32, 128))
                meter   <- Meter.initSemaphore(size)
                counter <- AtomicInt.init(0)
                results <-
                    Async.parallel((1 to 100).map(_ =>
                        Abort.run(meter.run(counter.incrementAndGet))
                    ))
                count   <- counter.get
                permits <- meter.permits
            yield
                assert(results.count(_.isFail) == 0)
                assert(count == 100)
                assert(permits == size)
            )
                .pipe(Choice.run).unit
                .repeat(repeats)
                .as(succeed)
        }

        "semaphore close" in run {
            (for
                size    <- Choice.get(Seq(1, 2, 32, 128))
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
                available  <- Abort.run(meter.available)
            yield
                assert(closed)
                assert(completed.count(_.isSuccess) <= 100)
                assert(count <= 100)
                assert(available.isFail)
            )
                .pipe(Choice.run).unit
                .repeat(repeats)
                .as(succeed)
        }

        "semaphore with interruptions" in run {
            (for
                size    <- Choice.get(Seq(1, 2, 32, 128))
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
                .pipe(Choice.run).unit
                .repeat(repeats)
                .as(succeed)
        }
    }

end MeterTest
