package kyo

import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger as JAtomicInteger
import org.scalatest.compatible.Assertion

class FiberTest extends Test:

    "promise" - {
        "complete" in run {
            for
                p <- Promise.init[Nothing, Int]
                a <- p.complete(Result.success(1))
                b <- p.done
                c <- p.get
            yield assert(a && b && c == 1)
        }
        "complete twice" in run {
            for
                p <- Promise.init[Nothing, Int]
                a <- p.complete(Result.success(1))
                b <- p.complete(Result.success(2))
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
                    a  <- p2.complete(Result.success(42))
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
                    a  <- p1.complete(Result.success(42))
                    b  <- p2.complete(Result.success(99))
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
                _ <- p.completeDiscard(Result.success(1))
                v <- p.get
            yield assert(v == 1)
        }

        "becomeDiscard" in run {
            for
                p1 <- Promise.init[Nothing, Int]
                p2 <- Promise.init[Nothing, Int]
                _  <- p2.complete(Result.success(42))
                _  <- p1.becomeDiscard(p2)
                v  <- p1.get
            yield assert(v == 42)
        }
    }

    "race" - {
        "zero" in runJVM {
            assertDoesNotCompile("Async.raceFiber()")
        }
        "one" in runJVM {
            Fiber.race(Seq(1)).map(_.get).map { r =>
                assert(r == 1)
            }
        }
        "n" in runJVM {
            val ac = new JAtomicInteger(0)
            val bc = new JAtomicInteger(0)
            def loop(i: Int, s: String): String < IO =
                IO {
                    if i > 0 then
                        if s.equals("a") then ac.incrementAndGet()
                        else bc.incrementAndGet()
                        loop(i - 1, s)
                    else
                        s
                }
            Fiber.race(Seq(loop(10, "a"), loop(Int.MaxValue, "b"))).map(_.get).map { r =>
                assert(r == "a")
                assert(ac.get() == 10)
                assert(bc.get() <= Int.MaxValue)
            }
        }
        "interrupts losers" - {
            "promise + plain value" in runJVM {
                for
                    flag        <- AtomicBoolean.init(false)
                    promise     <- Promise.init[Nothing, Int]
                    _           <- promise.onInterrupt(_ => flag.set(true))
                    result      <- Async.race(promise.get, 42)
                    interrupted <- flag.get
                yield
                    assert(result == 42)
                    assert(interrupted)
            }
            "promise + delayed" in runJVM {
                for
                    flag        <- AtomicBoolean.init(false)
                    promise     <- Promise.init[Nothing, Int]
                    _           <- promise.onInterrupt(_ => flag.set(true))
                    result      <- Async.race(promise.get, Async.delay(5.millis)(42))
                    interrupted <- flag.get
                yield
                    assert(result == 42)
                    assert(interrupted)
            }
            "slow + fast" in runJVM {
                for
                    adder <- LongAdder.init
                    result <-
                        Async.race(
                            Async.delay(15.millis)(adder.increment.andThen(24)),
                            Async.delay(5.millis)((adder.increment.andThen(42)))
                        )
                    _        <- Async.sleep(50.millis)
                    executed <- adder.get
                yield
                    assert(result == 42)
                    assert(executed == 1)
            }
        }
    }

    "parallel" - {
        "zero" in run {
            Fiber.parallel(Seq.empty[Int < Async]).map(_.get).map { r =>
                assert(r == Seq())
            }
        }
        "one" in run {
            Fiber.parallel(Seq(1)).map(_.get).map { r =>
                assert(r == Seq(1))
            }
        }
        "n" in run {
            val ac = new JAtomicInteger(0)
            val bc = new JAtomicInteger(0)
            def loop(i: Int, s: String): String < IO =
                IO {
                    if i > 0 then
                        if s.equals("a") then ac.incrementAndGet()
                        else bc.incrementAndGet()
                        loop(i - 1, s)
                    else
                        s
                }
            Fiber.parallel(List(loop(1, "a"), loop(5, "b"))).map(_.get).map { r =>
                assert(r == List("a", "b"))
                assert(ac.get() == 1)
                assert(bc.get() == 5)
            }
        }
    }

    "fromFuture" - {
        import scala.concurrent.Future
        import scala.concurrent.ExecutionContext.Implicits.global

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
                mappedFiber <- fiber.mapResult(r => r.mapFail(_.getMessage))
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
            yield assert(result == Result.success(84))
        }

        "failure" in run {
            val ex    = new Exception("Test exception")
            val fiber = Fiber.fail[Exception, Int](ex)
            for
                result <- fiber.useResult(r => r.mapFail(_.getMessage))
            yield assert(result == Result.fail("Test exception"))
        }

        "exception in useResult function" in run {
            val fiber = Fiber.success[Nothing, Int](42)
            for
                result <- Abort.run[Throwable](fiber.useResult(_ => throw new RuntimeException("UseResult exception")))
            yield assert(result.isFail)
        }
    }

    "onComplete" - {
        "already completed" in run {
            var completed = false
            val fiber     = Fiber.success(42)
            for
                _ <- fiber.onComplete(_ => IO { completed = true })
            yield assert(completed)
            end for
        }

        "pending" in run {
            var completed = Maybe.empty[Result[Any, Int]]
            for
                fiber <- Promise.init[Nothing, Int]
                _     <- fiber.onComplete(v => IO { completed = Maybe(v) })
                notCompletedYet = completed
                _ <- fiber.complete(Result.success(42))
                completedAfterWait = completed
            yield
                assert(notCompletedYet.isEmpty)
                assert(completedAfterWait == Maybe(Result.success(42)))
            end for
        }
    }

    "onInterrupt" - {
        "called on interrupt" in run {
            var interrupted = false
            for
                fiber <- Promise.init[Nothing, Int]
                _     <- fiber.onInterrupt(_ => IO { interrupted = true })
                _     <- fiber.interrupt
            yield assert(interrupted)
            end for
        }

        "not called on normal completion" in run {
            var interrupted = false
            for
                fiber <- Promise.init[Nothing, Int]
                _     <- fiber.onInterrupt(_ => IO { interrupted = true })
                _     <- fiber.complete(Result.success(42))
                _     <- fiber.get
            yield assert(!interrupted)
            end for
        }

        "multiple callbacks" in run {
            var count = 0
            for
                fiber <- Promise.init[Nothing, Int]
                _     <- fiber.onInterrupt(_ => IO { count += 1 })
                _     <- fiber.onInterrupt(_ => IO { count += 1 })
                _     <- fiber.onInterrupt(_ => IO { count += 1 })
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
            yield assert(result == Result.success(42))
        }

        "timeout" in runJVM {
            for
                fiber  <- Async.run(Async.sleep(1.second).andThen(42))
                result <- fiber.block(1.millis)
            yield assert(result.isFail)
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
                assert(result == Result.success(5))
            }
        }

        "variance with Promise" in run {
            for
                p <- Promise.init[Exception, String]
                _ <- p.complete(Result.success("Hello"))
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
                import scala.concurrent.ExecutionContext.Implicits.global

                val future = Future.successful(42)
                val fiber  = Fiber.Unsafe.fromFuture(future)
                for
                    result <- fiber.safe.get
                yield assert(result == 42)
            }

            "map" in run {
                val fiber       = Promise.Unsafe.init[Nothing, Int]()
                val mappedFiber = fiber.map(_ * 2)
                discard(fiber.complete(Result.success(21)))
                for
                    result <- mappedFiber.safe.get
                yield assert(result == 42)
            }

            "flatMap" in run {
                val fiber           = Promise.Unsafe.init[Nothing, Int]()
                val flatMappedFiber = fiber.flatMap(x => Fiber.Unsafe.init[Nothing, String](Result.success(x.toString)))
                discard(fiber.complete(Result.success(42)))
                for
                    result <- flatMappedFiber.safe.get
                yield assert(result == "42")
            }

            "mapResult" in run {
                val fiber       = Promise.Unsafe.init[Nothing, Int]()
                val mappedFiber = fiber.mapResult(_.map(_ * 2))
                fiber.completeDiscard(Result.success(21))
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
                val completed = promise.complete(Result.success(42))
                assert(completed)
                for
                    result <- promise.safe.get
                yield assert(result == 42)
            }

            "completeDiscard" in run {
                val promise = Promise.Unsafe.init[Nothing, Int]()
                promise.completeDiscard(Result.success(42))
                for
                    result <- promise.safe.get
                yield assert(result == 42)
            }

            "become" in run {
                val promise1 = Promise.Unsafe.init[Nothing, Int]()
                val promise2 = Promise.Unsafe.init[Nothing, Int]()
                promise2.completeDiscard(Result.success(42))
                val became = promise1.become(promise2.safe)
                assert(became)
                for
                    result <- promise1.safe.get
                yield assert(result == 42)
            }

            "becomeDiscard" in run {
                val promise1 = Promise.Unsafe.init[Nothing, Int]()
                val promise2 = Promise.Unsafe.init[Nothing, Int]()
                promise2.completeDiscard(Result.success(42))
                promise1.becomeDiscard(promise2.safe)
                for
                    result <- promise1.safe.get
                yield assert(result == 42)
            }
        }
    }

end FiberTest
