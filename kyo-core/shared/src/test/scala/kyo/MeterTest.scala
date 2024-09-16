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
                a1 <- t.isAvailable
                b2 <- Promise.init[Nothing, Unit]
                f2 <- Async.run(b2.complete(Result.unit).map(_ => t.run(2)))
                _  <- b2.get
                a2 <- t.isAvailable
                d1 <- f1.isDone
                d2 <- f2.isDone
                _  <- p.complete(Result.success(1))
                v1 <- f1.get
                v2 <- f2.get
                a3 <- t.isAvailable
            yield assert(!a1 && !d1 && !d2 && !a2 && v1 == Result.success(1) && v2 == 2 && a3)
        }

        "tryRun" in runJVM {
            for
                sem <- Meter.initSemaphore(1)
                p   <- Promise.init[Nothing, Int]
                b1  <- Promise.init[Nothing, Unit]
                f1  <- Async.run(sem.tryRun(b1.complete(Result.unit).map(_ => p.block(Duration.Infinity))))
                _   <- b1.get
                a1  <- sem.isAvailable
                b1  <- sem.tryRun(2)
                b2  <- f1.isDone
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
                a1 <- t.isAvailable
                b3 <- Promise.init[Nothing, Unit]
                f2 <- Async.run(b3.complete(Result.unit).map(_ => t.run(2)))
                _  <- b3.get
                a2 <- t.isAvailable
                d1 <- f1.isDone
                d2 <- f2.isDone
                _  <- p.complete(Result.success(1))
                v1 <- f1.get
                v2 <- f2.get
                a3 <- t.isAvailable
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
                a1  <- sem.isAvailable
                b3  <- sem.tryRun(2)
                b4  <- f1.isDone
                b5  <- f2.isDone
                _   <- p.complete(Result.success(1))
                v1  <- f1.get
                v2  <- f2.get
            yield assert(!a1 && b3.isEmpty && !b4 && !b5 && v1.contains(Result.success(1)) && v2.contains(Result.success(1)))
        }
    }

    def loop(meter: Meter, counter: AtomicInt): Unit < Async =
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
                _       <- untilTrue(meter.isAvailable.map(!_))
                _       <- Async.sleep(5.millis)
                r       <- meter.tryRun(())
                _       <- f1.interrupt(panic)
            yield assert(r.isEmpty)
        }
    }
end MeterTest
