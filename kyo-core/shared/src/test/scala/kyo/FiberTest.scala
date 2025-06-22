package kyo

import java.util.concurrent.atomic.AtomicInteger as JAtomicInteger
import org.scalatest.compatible.Assertion

class FiberTest extends Test:

    "promise" - {
        "initWith" in run {
            for
                result <- Promise.initWith[Nothing, Int] { p =>
                    p.complete(Result.succeed(42)).andThen(p.get)
                }
            yield assert(result == 42)
        }
        "complete" in run {
            for
                p <- Promise.init[Nothing, Int]
                a <- p.complete(Result.succeed(1))
                b <- p.done
                c <- p.get
            yield assert(a && b && c == 1)
        }
        "complete twice" in run {
            for
                p <- Promise.init[Nothing, Int]
                a <- p.complete(Result.succeed(1))
                b <- p.complete(Result.succeed(2))
                c <- p.done
                d <- p.get
            yield assert(a && !b && c && d == 1)
        }
        "complete null" in run {
            for
                p <- Promise.init[Nothing, AnyRef]
                b <- p.complete(null)
                r <- p.get
            yield assert(b && r == null)
        }
        "complete wrong type" in run {
            for
                p <- Promise.init[String, Int]
            yield
                typeCheckFailure("p.complete(Result.unit)")("Required: kyo.Result[String, Int]")
                typeCheckFailure("p.complete(Result.fail(1))")("Required: String")
        }
        "failure" in run {
            val ex = new Exception
            for
                p <- Promise.init[Exception, Int]
                a <- p.complete(Result.fail(ex))
                b <- p.done
                c <- p.getResult
            yield assert(a && b && c == Result.fail(ex))
            end for
        }

        "become" - {
            "succeed" in run {
                for
                    p1 <- Promise.init[Nothing, Int]
                    p2 <- Promise.init[Nothing, Int]
                    a  <- p2.complete(Result.succeed(42))
                    b  <- p1.become(p2)
                    c  <- p1.done
                    d  <- p1.get
                yield assert(a && b && c && d == 42)
            }

            "fail" in run {
                val ex = new Exception("fail")
                for
                    p1 <- Promise.init[Exception, Int]
                    p2 <- Promise.init[Exception, Int]
                    a  <- p2.complete(Result.fail(ex))
                    b  <- p1.become(p2)
                    c  <- p1.done
                    d  <- p1.getResult
                yield assert(a && b && c && d == Result.fail(ex))
                end for
            }

            "already completed" in run {
                for
                    p1 <- Promise.init[Nothing, Int]
                    p2 <- Promise.init[Nothing, Int]
                    a  <- p1.complete(Result.succeed(42))
                    b  <- p2.complete(Result.succeed(99))
                    c  <- p1.become(p2)
                    d  <- p1.get
                yield assert(a && b && !c && d == 42)
            }

            "done fiber" in run {
                for
                    p <- Promise.init[Nothing, Int]
                    a <- p.become(Fiber.success(42))
                    b <- p.done
                    c <- p.get
                yield assert(a && b && c == 42)
            }
        }

        "completeDiscard" in run {
            for
                p <- Promise.init[Nothing, Int]
                _ <- p.completeDiscard(Result.succeed(1))
                v <- p.get
            yield assert(v == 1)
        }

        "becomeDiscard" in run {
            for
                p1 <- Promise.init[Nothing, Int]
                p2 <- Promise.init[Nothing, Int]
                _  <- p2.complete(Result.succeed(42))
                _  <- p1.becomeDiscard(p2)
                v  <- p1.get
            yield assert(v == 42)
        }
    }

    "race" - {
        "zero" in run {
            typeCheckFailure("Fiber.race()")(
                "missing argument for parameter iterable of method race"
            )
        }
        "one" in run {
            Fiber.race(Seq(1)).map(_.get).map { r =>
                assert(r == 1)
            }
        }
        "n" in run {
            def loop(i: Int, s: String): String < (Abort[String] & Sync) =
                Sync {
                    if i == 80 && s == "a" then
                        Abort.fail("Loser")
                    else if i <= 0 then s
                    else
                        loop(i - 1, s)
                }

            Fiber.race(Seq(loop(100, "a"), loop(Int.MaxValue, "b"), loop(100, "c"))).map(_.getResult).map { r =>
                assert(r == Result.succeed("c"))
            }
        }
        "raceFirst" - {
            "zero" in run {
                typeCheckFailure("Fiber.raceFirst()")(
                    "missing argument for parameter iterable of method raceFirst"
                )
            }
            "one" in run {
                Fiber.raceFirst(Seq(1)).map(_.get).map { r =>
                    assert(r == 1)
                }
            }
            "n" in run {
                def loop(i: Int, s: String): String < (Abort[String] & Sync) =
                    Sync {
                        if i == 80 && s == "a" then
                            Abort.fail("Winner")
                        else if i <= 0 then s
                        else
                            loop(i - 1, s)
                    }
                Fiber.raceFirst(Seq(loop(100, "a"), loop(Int.MaxValue, "b"), loop(2000, "c"))).map(_.getResult).map { r =>
                    assert(r == Result.fail("Winner"))
                }
            }
            "returns first result regardless of success/failure" in run {
                val error = new Exception("test error")
                Fiber.raceFirst(Seq(
                    Async.delay(10.millis)(1),
                    Async.delay(1.millis)(Abort.fail[Exception](error))
                )).map(_.getResult).map { r =>
                    assert(r == Result.fail(error))
                }
            }
            "interrupts losers" in run {
                for
                    interruptCount <- AtomicInt.init
                    startLatch     <- Latch.init(3)
                    promise1       <- Promise.init[Nothing, Int]
                    promise2       <- Promise.init[Nothing, Int]
                    promise3       <- Promise.init[Nothing, Int]
                    _              <- promise1.onInterrupt(_ => interruptCount.incrementAndGet.unit)
                    _              <- promise2.onInterrupt(_ => interruptCount.incrementAndGet.unit)
                    _              <- promise3.onInterrupt(_ => interruptCount.incrementAndGet.unit)
                    fiber <- Fiber.raceFirst(Seq(
                        startLatch.release.andThen(promise1.get),
                        startLatch.release.andThen(promise2.get),
                        startLatch.release.andThen(promise3.get)
                    ))
                    _      <- startLatch.await
                    _      <- promise1.complete(Result.succeed(1))
                    _      <- Async.sleep(1.milli)
                    result <- fiber.get
                    _      <- untilTrue(interruptCount.get.map(_ == 2))
                yield assert(result == 1)
            }
        }
        "interrupts losers" - {
            "promise + plain value" in run {
                for
                    latch   <- Latch.init(1)
                    promise <- Promise.init[Nothing, Int]
                    _       <- promise.onInterrupt(_ => latch.release)
                    result  <- Fiber.race(Seq(promise.get, 42)).map(_.get)
                    _       <- latch.await
                yield assert(result == 42)
            }
            "promise + delayed" in run {
                for
                    latch   <- Latch.init(1)
                    promise <- Promise.init[Nothing, Int]
                    _       <- promise.onInterrupt(_ => latch.release)
                    result  <- Fiber.race(Seq(promise.get, Async.delay(5.millis)(42))).map(_.get)
                    _       <- latch.await
                yield assert(result == 42)
            }
            "slow + fast" in run {
                for
                    adder <- LongAdder.init
                    result <-
                        Fiber.race(Seq(
                            Async.delay(1.second)(adder.increment.andThen(24)),
                            Async.delay(1.millis)((adder.increment.andThen(42)))
                        )).map(_.get)
                    _        <- Async.sleep(50.millis)
                    executed <- adder.get
                yield
                    assert(result == 42)
                    assert(executed == 1)
            }
        }
    }

    "foreachIndexed" - {
        "empty sequence" in run {
            for
                fiber  <- Fiber.foreachIndexed(Seq())((idx, v) => (idx, v))
                result <- fiber.get
            yield assert(result == Seq())
        }

        "small collection + Sync" in run {
            for
                fiber  <- Fiber.foreachIndexed(Seq(1, 2, 3))((idx, v) => Sync((idx, v)))
                result <- fiber.get
            yield assert(result == Seq((0, 1), (1, 2), (2, 3)))
        }

        "error propagation" in run {
            val error = new Exception("test error")
            for
                fiber <- Sync {
                    def task(idx: Int, v: Int): Int < (Sync & Async & Abort[Throwable]) =
                        if v == 3 then Abort.fail(error)
                        else v

                    Fiber.foreachIndexed(1 to 5)(task)
                }
                result <- fiber.getResult
            yield assert(result == Result.fail(error))
            end for
        }
    }

    "fromFuture" - {
        import scala.concurrent.Future

        "success" in run {
            val future = Future.successful(42)
            for
                fiber  <- Fiber.fromFuture(future)
                result <- fiber.get
            yield assert(result == 42)
            end for
        }

        "failure" in run {
            val exception           = new RuntimeException("Test exception")
            val future: Future[Int] = Future.failed(exception)
            for
                fiber  <- Fiber.fromFuture(future)
                result <- Abort.run(fiber.get)
            yield assert(result.failure.contains(exception))
            end for
        }
    }

    "mapResult" - {
        "success" in run {
            val fiber = Fiber.success[Nothing, Int](42)
            for
                mappedFiber <- fiber.mapResult(r => r.map(_ * 2))
                result      <- mappedFiber.get
            yield assert(result == 84)
            end for
        }

        "failure" in run {
            val ex    = new Exception("Test exception")
            val fiber = Fiber.fail[Exception, Int](ex)
            for
                mappedFiber <- fiber.mapResult(r => r.mapFailure(_.getMessage))
                result      <- Abort.run(mappedFiber.get)
            yield assert(result == Result.fail("Test exception"))
            end for
        }

        "exception in mapping function" in run {
            val fiber = Fiber.success[Nothing, Int](42)
            for
                mappedFiber: Fiber[Nothing, Int] <- fiber.mapResult(_ => throw new RuntimeException("Mapping exception"))
                result                           <- Abort.run[Throwable](mappedFiber.get)
            yield assert(result.isPanic)
            end for
        }
    }

    "map" - {
        "success" in run {
            val fiber = Fiber.success[Nothing, Int](42)
            for
                mappedFiber <- fiber.map(_ * 2)
                result      <- mappedFiber.get
            yield assert(result == 84)
            end for
        }

        "exception in mapping function" in run {
            val fiber = Fiber.success[Nothing, Int](42)
            for
                mappedFiber: Fiber[Nothing, Int] <- fiber.map(_ => throw new RuntimeException("Mapping exception"))
                result                           <- Abort.run[Throwable](mappedFiber.get)
            yield assert(result.isPanic)
            end for
        }
    }

    "flatMap" - {
        "success" in run {
            val fiber = Fiber.success[Nothing, Int](42)
            for
                flatMappedFiber <- fiber.flatMap(x => Fiber.success(x.toString))
                result          <- flatMappedFiber.get
            yield assert(result == "42")
            end for
        }

        "failure" in run {
            val fiber = Fiber.success[Nothing, Int](42)
            val ex    = new Exception("Test exception")
            for
                flatMappedFiber <- fiber.flatMap(_ => Fiber.fail[Exception, String](ex))
                result          <- Abort.run[Throwable](flatMappedFiber.get)
            yield assert(result.failure.contains(ex))
            end for
        }

        "exception in mapping function" in run {
            val fiber = Fiber.success[Nothing, Int](42)
            for
                flatMappedFiber: Fiber[Nothing, Int] <- fiber.flatMap(_ => throw new RuntimeException("Mapping exception"))
                result                               <- Abort.run[Throwable](flatMappedFiber.get)
            yield assert(result.isPanic)
            end for
        }
    }

    "use" - {
        "success" in run {
            val fiber = Fiber.success[Nothing, Int](42)
            for
                result <- fiber.use(x => x * 2)
            yield assert(result == 84)
        }

        "failure" in run {
            val ex    = new Exception("Test exception")
            val fiber = Fiber.fail[Exception, Int](ex)
            for
                result <- Abort.run(fiber.use(x => x * 2))
            yield assert(result.failure.contains(ex))
        }

        "exception in use function" in run {
            val fiber = Fiber.success[Nothing, Int](42)
            for
                result <- Abort.run[Throwable](fiber.use(_ => throw new RuntimeException("Use exception")))
            yield assert(result.isPanic)
        }
    }

    "useResult" - {
        "success" in run {
            val fiber = Fiber.success[Nothing, Int](42)
            for
                result <- fiber.useResult(r => r.map(_ * 2))
            yield assert(result == Result.succeed(84))
        }

        "failure" in run {
            val ex    = new Exception("Test exception")
            val fiber = Fiber.fail[Exception, Int](ex)
            for
                result <- fiber.useResult(r => r.mapFailure(_.getMessage))
            yield assert(result == Result.fail("Test exception"))
        }

        "exception in useResult function" in run {
            val fiber = Fiber.success[Nothing, Int](42)
            for
                result <- Abort.run[Throwable](fiber.useResult(_ => throw new RuntimeException("UseResult exception")))
            yield assert(result.isFailure)
        }
    }

    "onComplete" - {
        "already completed" in run {
            var completed = false
            val fiber     = Fiber.success(42)
            for
                _ <- fiber.onComplete(_ => Sync { completed = true })
            yield assert(completed)
            end for
        }

        "pending" in run {
            var completed = Maybe.empty[Result[Any, Int]]
            for
                fiber <- Promise.init[Nothing, Int]
                _     <- fiber.onComplete(v => Sync { completed = Maybe(v) })
                notCompletedYet = completed
                _ <- fiber.complete(Result.succeed(42))
                completedAfterWait = completed
            yield
                assert(notCompletedYet.isEmpty)
                assert(completedAfterWait == Maybe(Result.succeed(42)))
            end for
        }
    }

    "onInterrupt" - {
        "called on interrupt" in run {
            var interrupted = false
            for
                fiber <- Promise.init[Nothing, Int]
                _     <- fiber.onInterrupt(_ => Sync { interrupted = true })
                _     <- fiber.interrupt
            yield assert(interrupted)
            end for
        }

        "not called on normal completion" in run {
            var interrupted = false
            for
                fiber <- Promise.init[Nothing, Int]
                _     <- fiber.onInterrupt(_ => Sync { interrupted = true })
                _     <- fiber.complete(Result.succeed(42))
                _     <- fiber.get
            yield assert(!interrupted)
            end for
        }

        "multiple callbacks" in run {
            var count = 0
            for
                fiber <- Promise.init[Nothing, Int]
                _     <- fiber.onInterrupt(_ => Sync { count += 1 })
                _     <- fiber.onInterrupt(_ => Sync { count += 1 })
                _     <- fiber.onInterrupt(_ => Sync { count += 1 })
                _     <- fiber.interrupt
            yield assert(count == 3)
            end for
        }
    }

    "block" - {
        "success" in run {
            val fiber = Fiber.success[Nothing, Int](42)
            for
                result <- fiber.block(Duration.Infinity)
            yield assert(result == Result.succeed(42))
        }

        "timeout" in runNotJS {
            for
                fiber  <- Async.run(Async.sleep(1.second).andThen(42))
                result <- fiber.block(1.millis)
            yield assert(result.isFailure)
        }
    }

    "mask" in run {
        for
            start  <- Latch.init(1)
            run    <- Latch.init(1)
            stop   <- Latch.init(1)
            result <- AtomicInt.init(0)
            fiber <-
                Async.run {
                    for
                        _ <- start.release
                        _ <- run.await
                        _ <- result.set(42)
                        _ <- stop.release
                    yield ()
                }
            masked <- fiber.mask
            _      <- masked.interrupt
            r1     <- result.get
            _      <- run.release
            _      <- stop.await
            r2     <- result.get
        yield assert(r1 == 0 && r2 == 42)
    }

    "variance" - {
        given [A, B]: CanEqual[A, B] = CanEqual.derived
        "covariance of A" in run {
            val f                          = Fiber.success[Nothing, String]("Hello")
            val f2: Fiber[Nothing, AnyRef] = f
            f2.get.map { result =>
                assert(result == "Hello")
            }
        }

        "contravariance of E" in run {
            val ex                            = new Exception("Test")
            val f                             = Fiber.fail[Exception, Nothing](ex)
            val f2: Fiber[Throwable, Nothing] = f
            Abort.run(f2.get).map { result =>
                assert(result == Result.fail(ex))
            }
        }

        "variance with map" in run {
            val f                          = Fiber.success[Nothing, String]("Hello")
            val f2: Fiber[Nothing, AnyRef] = f
            f2.get.map { result =>
                assert(result == "Hello")
            }
        }

        "variance with flatMap" in run {
            val f                          = Fiber.success[Nothing, String]("Hello")
            val f2: Fiber[Nothing, AnyRef] = f
            f2.flatMap(s => Fiber.success[Nothing, String](s.asInstanceOf[String])).map(_.get).map { result =>
                assert(result == "Hello")
            }
        }

        "variance with mapResult" in run {
            val f = Fiber.success[Exception, String]("Hello")
            f.mapResult(_.map(_.length)).map(_.get).map { result =>
                assert(result == 5)
            }
        }

        "variance with use" in run {
            val f                          = Fiber.success[Nothing, String]("Hello")
            val f2: Fiber[Nothing, AnyRef] = f
            f2.use(s => "!" + s).map { result =>
                assert(result == "!Hello")
            }
        }

        "variance with useResult" in run {
            val f = Fiber.success[Exception, String]("Hello")
            f.useResult(_.map(_.length)).map { result =>
                assert(result == Result.succeed(5))
            }
        }

        "variance with Promise" in run {
            for
                p <- Promise.init[Exception, String]
                _ <- p.complete(Result.succeed("Hello"))
                f: Fiber[Throwable, AnyRef] = p
                result <- f.get
            yield assert(result == "Hello")
        }
    }

    "unsafe" - {
        import AllowUnsafe.embrace.danger
        "Fiber" - {
            "init" in run {
                val fiber = Fiber.Unsafe.init[Nothing, Unit](Result.unit)
                assert(fiber.done())
            }

            "fromFuture" in run {
                import scala.concurrent.Future

                val future = Future.successful(42)
                val fiber  = Fiber.Unsafe.fromFuture(future)
                for
                    result <- fiber.safe.get
                yield assert(result == 42)
            }

            "map" in run {
                val fiber       = Promise.Unsafe.init[Nothing, Int]()
                val mappedFiber = fiber.map(_ * 2)
                discard(fiber.complete(Result.succeed(21)))
                for
                    result <- mappedFiber.safe.get
                yield assert(result == 42)
            }

            "flatMap" in run {
                val fiber           = Promise.Unsafe.init[Nothing, Int]()
                val flatMappedFiber = fiber.flatMap(x => Fiber.Unsafe.init[Nothing, String](Result.succeed(x.toString)))
                discard(fiber.complete(Result.succeed(42)))
                for
                    result <- flatMappedFiber.safe.get
                yield assert(result == "42")
            }

            "mapResult" in run {
                val fiber       = Promise.Unsafe.init[Nothing, Int]()
                val mappedFiber = fiber.mapResult(_.map(_ * 2))
                fiber.completeDiscard(Result.succeed(21))
                for
                    result <- mappedFiber.safe.get
                yield assert(result == 42)
            }
        }

        "Promise" - {
            "init" in run {
                val promise = Promise.Unsafe.init[Nothing, Int]()
                assert(promise.done() == false)
            }

            "complete" in run {
                val promise   = Promise.Unsafe.init[Nothing, Int]()
                val completed = promise.complete(Result.succeed(42))
                assert(completed)
                for
                    result <- promise.safe.get
                yield assert(result == 42)
            }

            "completeDiscard" in run {
                val promise = Promise.Unsafe.init[Nothing, Int]()
                promise.completeDiscard(Result.succeed(42))
                for
                    result <- promise.safe.get
                yield assert(result == 42)
            }

            "become" in run {
                val promise1 = Promise.Unsafe.init[Nothing, Int]()
                val promise2 = Promise.Unsafe.init[Nothing, Int]()
                promise2.completeDiscard(Result.succeed(42))
                val became = promise1.become(promise2.safe)
                assert(became)
                for
                    result <- promise1.safe.get
                yield assert(result == 42)
            }

            "becomeDiscard" in run {
                val promise1 = Promise.Unsafe.init[Nothing, Int]()
                val promise2 = Promise.Unsafe.init[Nothing, Int]()
                promise2.completeDiscard(Result.succeed(42))
                promise1.becomeDiscard(promise2.safe)
                for
                    result <- promise1.safe.get
                yield assert(result == 42)
            }
        }
    }

    "boundary inference with Abort" - {
        "same failures" in {
            val v: Int < Abort[Int]            = 1
            val _: Fiber[Int, Int] < Sync      = Fiber.race(Seq(v))
            val _: Fiber[Int, Seq[Int]] < Sync = Fiber.foreachIndexed(Seq(v))((_, v) => v)
            succeed
        }
        "additional failure" in {
            val v: Int < Abort[Int]                     = 1
            val _: Fiber[Int | String, Int] < Sync      = Fiber.race(Seq(v))
            val _: Fiber[Int | String, Seq[Int]] < Sync = Fiber.foreachIndexed(Seq(v))((_, v) => v)
            succeed
        }
    }

    "gather" - {

        val repeats = 100

        "empty sequence" in run {
            for
                fiber  <- Fiber.gather(1)(Seq.empty[Int < Async])
                result <- fiber.get
            yield assert(result.isEmpty)
        }

        "collects all successful results" in run {
            Loop.repeat(repeats) {
                for
                    fiber  <- Fiber.gather(3)(Seq(Sync(1), Sync(2), Sync(3)))
                    result <- fiber.get
                yield
                    assert(result == Chunk(1, 2, 3))
                    ()
            }.andThen(succeed)
        }

        "with max limit" in run {
            val seq = Seq(1, 2, 3)
            for
                fiber  <- Fiber.gather(2)(seq.map(Sync(_)))
                result <- fiber.get
            yield
                assert(result.distinct.size == 2)
                assert(result.forall(seq.contains))
            end for
        }

        "handles max=0" in run {
            for
                fiber  <- Fiber.gather(0)(Seq(Sync(1), Sync(2), Sync(3)))
                result <- fiber.get
            yield assert(result.isEmpty)
        }

        "handles max=1 with all failures except last" in run {
            val error = new Exception("test error")
            for
                fiber <- Fiber.gather(1)(Seq(
                    Abort.fail[Exception](error),
                    Abort.fail[Exception](error),
                    Sync(3)
                ))
                result <- fiber.get
            yield assert(result == Chunk(3))
            end for
        }

        "handles negative max" in run {
            for
                fiber  <- Fiber.gather(-1)(Seq(Sync(1), Sync(2), Sync(3)))
                result <- fiber.get
            yield assert(result.isEmpty)
        }

        "handles max > size" in run {
            val seq = Seq(1, 2, 3)
            for
                fiber  <- Fiber.gather(10)(seq.map(Sync(_)))
                result <- fiber.get
            yield assert(result == Chunk(1, 2, 3))
            end for
        }

        "handles max > size with failures" in run {
            val error = new Exception("test error")
            for
                fiber <- Fiber.gather(10)(Seq(
                    Sync(1),
                    Abort.fail[Exception](error),
                    Sync(3)
                ))
                result <- fiber.get
            yield assert(result == Chunk(1, 3))
            end for
        }

        "preserves original order" in run {
            for
                fiber <- Fiber.gather(10)(Seq(
                    Async.delay(3.millis)(1),
                    Async.delay(1.millis)(2),
                    Async.delay(2.millis)(3)
                ))
                result <- fiber.get
            yield assert(result == Chunk(1, 2, 3))
        }

        "preserves original order with max limit" in run {
            for
                fiber <- Fiber.gather(2)(Seq(
                    Async.delay(100.millis)(1),
                    Async.delay(1.millis)(2),
                    Async.delay(10.millis)(3)
                ))
                result <- fiber.get
            yield assert(result == Chunk(2, 3))
        }

        "handles concurrent completions" in run {
            for
                latch  <- Latch.init(1)
                fiber  <- Fiber.gather(20)(Seq.fill(20)(latch.await.andThen(42)))
                _      <- latch.release
                result <- fiber.get
            yield assert(result.size == 20 && result.forall(_ == 42))
        }

        val error = new Exception("test error")

        "filters out failures" in run {
            for
                fiber <- Fiber.gather(3)(Seq(
                    Sync(1),
                    Abort.fail[Exception](error),
                    Sync(3)
                ))
                result <- fiber.get
            yield assert(result == Chunk(1, 3))
            end for
        }

        "handles mixed success/failure with exact max" in run {
            val error = new Exception("test error")
            for
                fiber <- Fiber.gather(2)(Seq(
                    Async.delay(10.millis)(1),
                    Async.delay(1.millis)(Abort.fail[Exception](error)),
                    Async.delay(1.millis)(2),
                    Async.delay(2.millis)(Abort.fail[Exception](error)),
                    Async.delay(100.millis)(3)
                ))
                result <- fiber.get
            yield
                assert(result.size == 2)
                assert(result == Chunk(1, 2))
            end for
        }

        "handles race conditions in counter updates" in run {
            Loop.repeat(repeats) {
                for
                    latch1 <- Latch.init(1)
                    latch2 <- Latch.init(1)
                    fiber <- Fiber.gather(2)(Seq(
                        latch1.release.andThen(1),
                        latch2.release.andThen(2),
                        Async.delay(50.millis)(3)
                    ))
                    _      <- latch1.await
                    _      <- latch2.await
                    result <- fiber.get
                yield
                    assert(result == Chunk(1, 2))
                    ()
            }.andThen(succeed)
        }

        "race conditions in error propagation" in run {
            Loop.repeat(50) {
                for
                    latch <- Latch.init(1)
                    error1 = new Exception("error1")
                    error2 = new Exception("error2")
                    fiber <- Fiber.gather(2)(Seq(
                        latch.await.andThen(Abort.fail[Exception](error1)),
                        latch.await.andThen(Abort.fail[Exception](error2))
                    ))
                    _      <- latch.release
                    result <- Abort.run(fiber.get)
                yield
                    assert(result.isFailure)
                    assert(result.failure.exists(e => e == error1 || e == error2))
                    ()
            }.andThen(succeed)
        }

        "propagates error when all fail" in run {
            for
                fiber  <- Fiber.gather(2)(Seq[Int < Abort[Throwable]](Abort.fail(error), Abort.fail(error)))
                result <- Abort.run(fiber.get)
            yield assert(result.failure.contains(error))
            end for
        }

        "max limit with failures" in run {
            for
                fiber <- Fiber.gather(2)(Seq(
                    Sync(1),
                    Abort.fail[Exception](error),
                    Sync(3),
                    Sync(4)
                ))
                result <- fiber.get
            yield
                assert(result.size == 2)
                assert(result.forall(Seq(1, 3, 4).contains))
            end for
        }

        "completes early when max successful results reached" in run {
            Loop.repeat(repeats) {
                val seq = Seq(1, 2, 3)
                for
                    fiber  <- Fiber.gather(2)(seq.map(i => Async.delay(i.millis)(i)))
                    result <- fiber.get
                yield
                    assert(result.size == 2)
                    assert(result.forall(seq.contains))
                    ()
                end for
            }.andThen(succeed)
        }

        "interrupts all child fibers" in run {
            Loop.repeat(repeats) {
                for
                    interruptCount <- AtomicInt.init
                    startLatch     <- Latch.init(3)
                    promise1       <- Promise.init[Nothing, Int]
                    promise2       <- Promise.init[Nothing, Int]
                    promise3       <- Promise.init[Nothing, Int]
                    _              <- promise1.onInterrupt(_ => interruptCount.incrementAndGet.unit)
                    _              <- promise2.onInterrupt(_ => interruptCount.incrementAndGet.unit)
                    _              <- promise3.onInterrupt(_ => interruptCount.incrementAndGet.unit)
                    fiber <- Fiber.gather(3)(Seq(
                        startLatch.release.andThen(promise1.get),
                        startLatch.release.andThen(promise2.get),
                        startLatch.release.andThen(promise3.get)
                    ))
                    _ <- startLatch.await
                    _ <- Async.sleep(10.millis)
                    _ <- fiber.interrupt
                    _ <- untilTrue(interruptCount.get.map(_ == 3))
                yield ()
            }.andThen(succeed)
        }

        "interrupts remaining fibers when max results reached" in run {
            Loop.repeat(repeats) {
                for
                    interruptCount <- AtomicInt.init
                    startLatch     <- Latch.init(3)
                    promise1       <- Promise.init[Nothing, Int]
                    promise2       <- Promise.init[Nothing, Int]
                    promise3       <- Promise.init[Nothing, Int]
                    _              <- promise1.onInterrupt(_ => interruptCount.incrementAndGet.unit)
                    _              <- promise2.onInterrupt(_ => interruptCount.incrementAndGet.unit)
                    _              <- promise3.onInterrupt(_ => interruptCount.incrementAndGet.unit)
                    fiber <- Fiber.gather(2)(Seq(
                        startLatch.release.andThen(promise1.get),
                        startLatch.release.andThen(promise2.get),
                        startLatch.release.andThen(promise3.get)
                    ))
                    _      <- startLatch.await
                    done1  <- fiber.done
                    _      <- promise1.complete(Result.succeed(1))
                    _      <- Async.sleep(1.milli)
                    done2  <- fiber.done
                    _      <- promise2.complete(Result.succeed(1))
                    _      <- Async.sleep(1.milli)
                    result <- fiber.get
                    _      <- untilTrue(interruptCount.get.map(_ == 1))
                yield
                    assert(!done1 && !done2)
                    assert(result == Seq(1, 1))
                    ()
            }.andThen(succeed)
        }

        "quickSort" - {
            "empty array" in {
                val indices = Array[Int]()
                val results = Array[AnyRef]()
                Fiber.quickSort(indices, results, 0)
                assert(indices.isEmpty && results.isEmpty)
            }

            "single element" in {
                val indices = Array[Int](0)
                val results = Array[AnyRef]("a")
                Fiber.quickSort(indices, results, 1)
                assert(indices.sameElements(Array(0)) && results.sameElements(Array("a")))
            }

            "partial sort with items" - {
                "items = 0" in {
                    val indices  = Array[Int](3, 1, 4, 2)
                    val results  = Array[AnyRef]("c", "a", "d", "b")
                    val original = indices.clone()
                    Fiber.quickSort(indices, results, 0)
                    assert(indices.sameElements(original))
                }

                "items = 1" in {
                    val indices = Array[Int](3, 1, 4, 2)
                    val results = Array[AnyRef]("c", "a", "d", "b")
                    Fiber.quickSort(indices, results, 1)
                    assert(indices(0) == 3)
                }

                "items = n-1" in {
                    val indices = Array[Int](3, 1, 4, 2)
                    val results = Array[AnyRef]("c", "a", "d", "b")
                    Fiber.quickSort(indices, results, 3)
                    assert(indices.take(3).sorted.sameElements(indices.take(3)))
                    assert(indices(3) == 2)
                }
            }

            "full sort" - {
                "already sorted" in {
                    val indices = Array[Int](0, 1, 2, 3)
                    val results = Array[AnyRef]("a", "b", "c", "d")
                    Fiber.quickSort(indices, results, 4)
                    assert(indices.sameElements(Array(0, 1, 2, 3)))
                    assert(results.sameElements(Array("a", "b", "c", "d")))
                }

                "reverse sorted" in {
                    val indices = Array[Int](3, 2, 1, 0)
                    val results = Array[AnyRef]("d", "c", "b", "a")
                    Fiber.quickSort(indices, results, 4)
                    assert(indices.sameElements(Array(0, 1, 2, 3)))
                    assert(results.sameElements(Array("a", "b", "c", "d")))
                }

                "random order" in {
                    val indices = Array[Int](3, 1, 4, 2)
                    val results = Array[AnyRef]("c", "a", "d", "b")
                    Fiber.quickSort(indices, results, 4)
                    assert(indices.sameElements(Array(1, 2, 3, 4)))
                    assert(results.sameElements(Array("a", "b", "c", "d")))
                }
            }

            "can handle larger arrays" - {
                val size = 1000

                "sorted array" in {
                    val indices = Array.range(0, size)
                    val results = Array.fill[AnyRef](size)("x")
                    Fiber.quickSort(indices, results, size)
                    assert(indices.take(5).sameElements(Array(0, 1, 2, 3, 4)))
                    assert(indices.takeRight(5).sameElements(Array(size - 5, size - 4, size - 3, size - 2, size - 1)))
                }

                "reverse sorted array" in {
                    val indices = Array.range(0, size).reverse
                    val results = Array.fill[AnyRef](size)("x")
                    Fiber.quickSort(indices, results, size)
                    assert(indices.take(5).sameElements(Array(0, 1, 2, 3, 4)))
                    assert(indices.takeRight(5).sameElements(Array(size - 5, size - 4, size - 3, size - 2, size - 1)))
                }

                "random sorted array" in run {
                    val indices = Array.range(0, size).reverse
                    Random.shuffle(0 until size).map { seq =>
                        val indices = seq.toArray
                        val results = Array.fill[AnyRef](size)("x")
                        Fiber.quickSort(indices, results, size)
                        assert(indices.take(5).sameElements(Array(0, 1, 2, 3, 4)))
                        assert(indices.takeRight(5).sameElements(Array(size - 5, size - 4, size - 3, size - 2, size - 1)))
                    }
                }

                "array with repeated elements" in {
                    val indices = Array.fill(size)(42)
                    val results = Array.fill[AnyRef](size)("x")
                    Fiber.quickSort(indices, results, size)
                    assert(indices.forall(_ == 42))
                }
            }
        }
    }

end FiberTest
