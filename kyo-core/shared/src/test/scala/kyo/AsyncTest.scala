package kyo

import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger as JAtomicInteger
import org.scalatest.compatible.Assertion

class AsyncTest extends Test:

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

        "completeUnit" in run {
            for
                p <- Promise.init[Nothing, Int]
                _ <- p.completeUnit(Result.success(1))
                v <- p.get
            yield assert(v == 1)
        }

        "becomeUnit" in run {
            for
                p1 <- Promise.init[Nothing, Int]
                p2 <- Promise.init[Nothing, Int]
                _  <- p2.complete(Result.success(42))
                _  <- p1.becomeUnit(p2)
                v  <- p1.get
            yield assert(v == 42)
        }
    }

    "run" - {
        "value" in run {
            for
                v <- Async.run(1).map(_.get)
            yield assert(v == 1)
        }
        "executes in a different thread" in runJVM {
            val t1 = Thread.currentThread()
            for
                t2 <- Async.run(Thread.currentThread()).map(_.get)
            yield assert(t1 ne t2)
        }
        "multiple" in run {
            for
                v0               <- Async.run(0).map(_.get)
                (v1, v2)         <- Async.parallel(1, 2)
                (v3, v4, v5)     <- Async.parallel(3, 4, 5)
                (v6, v7, v8, v9) <- Async.parallel(6, 7, 8, 9)
            yield assert(v0 + v1 + v2 + v3 + v4 + v5 + v6 + v7 + v8 + v9 == 45)
        }
        "nested" in runJVM {
            val t1 = Thread.currentThread()
            for
                t2 <- Async.run(IO(Async.run(Thread.currentThread()).map(_.get))).map(_.get)
            yield assert(t1 ne t2)
        }

        "with IO-based effects" - {
            "Resource" in run {
                var closes = 0
                val a      = Resource.ensure(closes += 1).andThen(42)
                val b      = Async.run(a)
                Resource.run(b.map(_.get.map(v => assert(v == 42))))
            }
        }
        "non IO-based effect" in run {
            assertDoesNotCompile("Async.run(Vars[Int].get)")
        }
    }

    "sleep" in run {
        for
            start <- IO(java.lang.System.currentTimeMillis())
            _     <- Async.sleep(10.millis)
            end   <- IO(java.lang.System.currentTimeMillis())
        yield assert(end - start >= 8)
    }

    "delay" in run {
        for
            start <- IO(java.lang.System.currentTimeMillis())
            res   <- Async.delay(5.millis)(42)
            end   <- IO(java.lang.System.currentTimeMillis())
        yield
            assert(end - start >= 4)
            assert(res == 42)
    }

    "runAndBlock" - {

        "timeout" in runJVM {
            Async.sleep(1.day).andThen(1)
                .pipe(Async.timeout(10.millis))
                .pipe(Async.runAndBlock(Duration.Infinity))
                .pipe(Abort.run[Timeout](_))
                .map {
                    case Result.Fail(Timeout(_)) => succeed
                    case v                       => fail(v.toString())
                }
        }

        "block timeout" in runJVM {
            Async.sleep(1.day).andThen(1)
                .pipe(Async.runAndBlock(10.millis))
                .pipe(Abort.run[Timeout](_))
                .map {
                    case Result.Fail(Timeout(_)) => succeed
                    case v                       => fail(v.toString())
                }
        }

        "multiple fibers timeout" in runJVM {
            Kyo.fill(100)(Async.sleep(1.milli)).as(1)
                .pipe(Async.runAndBlock(10.millis))
                .pipe(Abort.run[Timeout](_))
                .map {
                    case Result.Fail(Timeout(_)) => succeed
                    case v                       => fail(v.toString())
                }
        }
    }

    val panic = Result.Panic(new Exception)

    "interrupt" - {

        def loop(ref: AtomicInt): Unit < IO =
            ref.incrementAndGet.map(_ => loop(ref))

        def runLoop(started: Latch, done: Latch) =
            Resource.run {
                Resource.ensure(done.release).map { _ =>
                    started.release.map(_ => AtomicInt.init(0).map(loop))
                }
            }

        "one fiber" in runJVM {
            for
                started     <- Latch.init(1)
                done        <- Latch.init(1)
                fiber       <- Async.run(runLoop(started, done))
                _           <- started.await
                interrupted <- fiber.interrupt(panic)
                _           <- done.await
            yield assert(interrupted)
        }
        "multiple fibers" in runJVM {
            for
                started      <- Latch.init(3)
                done         <- Latch.init(3)
                fiber1       <- Async.run(runLoop(started, done))
                fiber2       <- Async.run(runLoop(started, done))
                fiber3       <- Async.run(runLoop(started, done))
                _            <- started.await
                interrupted1 <- fiber1.interrupt(panic)
                interrupted2 <- fiber2.interrupt(panic)
                interrupted3 <- fiber3.interrupt(panic)
                _            <- done.await
            yield assert(interrupted1 && interrupted2 && interrupted3)
        }
    }

    "race" - {
        "zero" in runJVM {
            assertDoesNotCompile("Async.race()")
        }
        "one" in runJVM {
            Async.race(1).map { r =>
                assert(r == 1)
            }
        }
        "multiple" in runJVM {
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
            Async.race(loop(10, "a"), loop(Int.MaxValue, "b")).map { r =>
                assert(r == "a")
                assert(ac.get() == 10)
                assert(bc.get() <= Int.MaxValue)
            }
        }
        "waits for the first success" in runJVM {
            val ex = new Exception
            Async.race(
                Async.sleep(1.milli).andThen(42),
                Abort.panic[Exception](ex)
            ).map { r =>
                assert(r == 42)
            }
        }
        "returns the last failure if all fibers fail" in runJVM {
            val ex1 = new Exception
            val ex2 = new Exception
            val race =
                Async.race(
                    Async.sleep(1.milli).andThen(Abort.panic[Int](ex1)),
                    Abort.panic[Int](ex2)
                )
            Abort.run(race).map {
                r => assert(r == Result.panic(ex1))
            }
        }
    }

    "Fiber.race" - {
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
    }

    "parallel" - {
        "zero" in run {
            Async.parallel(Seq()).map { r =>
                assert(r == Seq())
            }
        }
        "one" in run {
            Async.parallel(Seq(1)).map { r =>
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
            Async.parallel(List(loop(1, "a"), loop(5, "b"))).map { r =>
                assert(r == List("a", "b"))
                assert(ac.get() == 1)
                assert(bc.get() == 5)
            }
        }
        "three arguments" in run {
            for
                (v1, v2, v3) <- Async.parallel(IO(1), IO(2), IO(3))
            yield assert(v1 == 1 && v2 == 2 && v3 == 3)
        }
        "four arguments" in run {
            for
                (v1, v2, v3, v4) <- Async.parallel(IO(1), IO(2), IO(3), IO(4))
            yield assert(v1 == 1 && v2 == 2 && v3 == 3 && v4 == 4)
        }
    }

    "Fiber.parallel" - {
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

    "transform" in run {
        for
            v1       <- Async.run(1).map(_.get)
            (v2, v3) <- Async.parallel(2, 3)
            l        <- Async.parallel(List[Int < Any](4, 5))
        yield assert(v1 + v2 + v3 + l.sum == 15)
    }

    "interrupt" in runJVM {
        def loop(ref: AtomicInt): Unit < IO =
            ref.incrementAndGet.map(_ => loop(ref))

        def task(l: Latch): Unit < Async =
            Resource.run[Unit, IO] {
                Resource.ensure(l.release).map { _ =>
                    AtomicInt.init(0).map(loop)
                }
            }

        for
            l           <- Latch.init(1)
            fiber       <- Async.run(task(l))
            _           <- Async.sleep(10.millis)
            interrupted <- fiber.interrupt(panic)
            _           <- l.await
        yield assert(interrupted)
        end for
    }

    "with Resource" - {
        class TestResource extends JAtomicInteger with Closeable:
            def close(): Unit =
                set(-1)
        "outer" in run {
            val resource1 = new TestResource
            val io1: (JAtomicInteger & Closeable, Set[Int]) < (Resource & Async) =
                for
                    r  <- Resource.acquire(resource1)
                    v1 <- IO(r.incrementAndGet())
                    v2 <- Async.run(r.incrementAndGet()).map(_.get)
                yield (r, Set(v1, v2))
            Resource.run(io1).map {
                case (r, v) =>
                    assert(v == Set(1, 2))
                    assert(r.get() == -1)
            }
        }
        "inner" in run {
            val resource1 = new TestResource
            Async.run(Resource.run(Resource.acquire(resource1).map(_.incrementAndGet())))
                .map(_.get).map { r =>
                    assert(r == 1)
                    assert(resource1.get() == -1)
                }
        }
        "multiple" in run {
            val resource1 = new TestResource
            val resource2 = new TestResource
            Async.parallel(
                Resource.run(Resource.acquire(resource1).map(_.incrementAndGet())),
                Resource.run(Resource.acquire(resource2).map(_.incrementAndGet()))
            ).map { r =>
                assert(r == (1, 1))
                assert(resource1.get() == -1)
                assert(resource2.get() == -1)
            }
        }
        "mixed" in run {
            val resource1 = new TestResource
            val resource2 = new TestResource
            val io1: Set[Int] < (Resource & Async) =
                for
                    r  <- Resource.acquire(resource1)
                    v1 <- IO(r.incrementAndGet())
                    v2 <- Async.run(r.incrementAndGet()).map(_.get)
                    v3 <- Resource.run(Resource.acquire(resource2).map(_.incrementAndGet()))
                yield Set(v1, v2, v3)
            Resource.run(io1).map { r =>
                assert(r == Set(1, 2))
                assert(resource1.get() == -1)
                assert(resource2.get() == -1)
            }
        }
    }

    "locals" - {
        val l = Local.init(10)
        "fork" - {
            "default" in run {
                Async.run(l.get).map(_.get).map(v => assert(v == 10))
            }
            "let" in run {
                l.let(20)(Async.run(l.get).map(_.get)).map(v => assert(v == 20))
            }
        }
        "race" - {
            "default" in run {
                Async.race(l.get, l.get).map(v => assert(v == 10))
            }
            "let" in run {
                l.let(20)(Async.race(l.get, l.get)).map(v => assert(v == 20))
            }
        }
        "collect" - {
            "default" in run {
                Async.parallel(List(l.get, l.get)).map(v => assert(v == List(10, 10)))
            }
            "let" in run {
                l.let(20)(Async.parallel(List(l.get, l.get)).map(v => assert(v == List(20, 20))))
            }
        }
    }

    "Fiber.fromFuture" - {
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

    "Async.fromFuture" - {
        import scala.concurrent.Future
        import scala.concurrent.ExecutionContext.Implicits.global

        "success" in run {
            val future = Future.successful(42)
            for
                result <- Async.fromFuture(future)
            yield assert(result == 42)
            end for
        }

        "failure" in run {
            val exception           = new RuntimeException("Test exception")
            val future: Future[Int] = Future.failed(exception)
            for
                result <- Abort.run(Async.fromFuture(future))
            yield assert(result.failure.contains(exception))
            end for
        }
    }

    "stack safety" in run {
        def loop(i: Int): Assertion < Async =
            if i > 0 then
                Async.run(()).map(_ => loop(i - 1))
            else
                succeed
        loop(10000)
    }

    "Fiber" - {

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
                var completed = Maybe.empty[Result[Nothing, Int]]
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
    }

    "masking" - {
        "Async.mask" in run {
            for
                start  <- Latch.init(1)
                run    <- Latch.init(1)
                stop   <- Latch.init(1)
                result <- AtomicInt.init(0)
                masked =
                    Async.mask {
                        for
                            _ <- start.release
                            _ <- run.await
                            _ <- stop.release
                            _ <- result.set(42)
                        yield ()
                    }
                fiber <- Async.run(masked)
                _     <- start.await
                _     <- fiber.interrupt
                r1    <- result.get
                _     <- run.release
                _     <- stop.await
                r2    <- result.get
            yield assert(r1 == 0 && r2 == 42)
        }

        "Fiber.mask" in run {
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
                            _ <- stop.release
                            _ <- result.set(42)
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
    }

end AsyncTest
