package kyo

import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger as JAtomicInteger
import org.scalatest.compatible.Assertion

class AsyncTest extends Test:

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
            Kyo.fill(100)(Async.sleep(1.milli)).andThen(1)
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

    "fromFuture" - {
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

    "mask" in run {
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
                        _ <- result.set(42)
                        _ <- stop.release
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

    "boundary inference with Abort" - {
        "same failures" in {
            val v: Int < Abort[Int]                            = 1
            val _: Fiber[Int, Int] < IO                        = Async.run(v)
            val _: Int < (Abort[Int | Timeout] & IO)           = Async.runAndBlock(1.second)(v)
            val _: Int < (Abort[Int] & Async)                  = Async.mask(v)
            val _: Int < (Abort[Int | Timeout] & Async)        = Async.timeout(1.second)(v)
            val _: Int < (Abort[Int] & Async)                  = Async.race(Seq(v))
            val _: Int < (Abort[Int] & Async)                  = Async.race(v, v)
            val _: Seq[Int] < (Abort[Int] & Async)             = Async.parallel(Seq(v))
            val _: (Int, Int) < (Abort[Int] & Async)           = Async.parallel(v, v)
            val _: (Int, Int, Int) < (Abort[Int] & Async)      = Async.parallel(v, v, v)
            val _: (Int, Int, Int, Int) < (Abort[Int] & Async) = Async.parallel(v, v, v, v)
            succeed
        }
        "additional failure" in {
            val v: Int < Abort[Int]                                     = 1
            val _: Fiber[Int | String, Int] < IO                        = Async.run(v)
            val _: Int < (Abort[Int | Timeout | String] & IO)           = Async.runAndBlock(1.second)(v)
            val _: Int < (Abort[Int | String] & Async)                  = Async.mask(v)
            val _: Int < (Abort[Int | Timeout | String] & Async)        = Async.timeout(1.second)(v)
            val _: Int < (Abort[Int | String] & Async)                  = Async.race(Seq(v))
            val _: Int < (Abort[Int | String] & Async)                  = Async.race(v, v)
            val _: Seq[Int] < (Abort[Int | String] & Async)             = Async.parallel(Seq(v))
            val _: (Int, Int) < (Abort[Int | String] & Async)           = Async.parallel(v, v)
            val _: (Int, Int, Int) < (Abort[Int | String] & Async)      = Async.parallel(v, v, v)
            val _: (Int, Int, Int, Int) < (Abort[Int | String] & Async) = Async.parallel(v, v, v, v)
            succeed
        }
        "nested" - {
            "run" in pendingUntilFixed {
                val v: Int < Abort[Int] = 1
                assertCompiles("Async.run(Async.run(v))")
                assertCompiles("Async.run(Async.runAndBlock(1.second)(v))")
                assertCompiles("Async.run(Async.mask(v))")
                assertCompiles("Async.run(Async.timeout(1.second)(v))")
                assertCompiles("Async.run(Async.race(Seq(v)))")
                assertCompiles("Async.run(Async.race(v, v))")
                assertCompiles("Async.run(Async.parallel(Seq(v)))")
                assertCompiles("Async.run(Async.parallel(v, v))")
                assertCompiles("Async.run(Async.parallel(v, v, v))")
                assertCompiles("Async.run(Async.parallel(v, v, v, v))")
            }

            "parallel" in pendingUntilFixed {
                val v: Int < Abort[Int] = 1
                assertCompiles("Async.parallel(Async.run(v), Async.run(v))")
                assertCompiles("Async.parallel(Async.runAndBlock(1.second)(v), Async.runAndBlock(1.second)(v))")
                assertCompiles("Async.parallel(Async.mask(v), Async.mask(v))")
                assertCompiles("Async.parallel(Async.timeout(1.second)(v), Async.timeout(1.second)(v))")
                assertCompiles("Async.parallel(Async.race(v, v), Async.race(v, v))")
                assertCompiles("Async.parallel(Async.parallel(v, v), Async.parallel(v, v))")
            }

            "race" in pendingUntilFixed {
                val v: Int < Abort[Int] = 1
                assertCompiles("Async.race(Async.run(v), Async.run(v))")
                assertCompiles("Async.race(Async.runAndBlock(1.second)(v), Async.runAndBlock(1.second)(v))")
                assertCompiles("Async.race(Async.mask(v), Async.mask(v))")
                assertCompiles("Async.race(Async.timeout(1.second)(v), Async.timeout(1.second)(v))")
                assertCompiles("Async.race(Async.race(v, v), Async.race(v, v))")
                assertCompiles("Async.race(Async.parallel(v, v), Async.parallel(v, v))")
            }

            "mask" in pendingUntilFixed {
                val v: Int < Abort[Int] = 1
                assertCompiles("Async.mask(Async.run(v))")
                assertCompiles("Async.mask(Async.runAndBlock(1.second)(v))")
                assertCompiles("Async.mask(Async.mask(v))")
                assertCompiles("Async.mask(Async.timeout(1.second)(v))")
                assertCompiles("Async.mask(Async.race(v, v))")
                assertCompiles("Async.mask(Async.parallel(v, v))")
            }

            "timeout" in pendingUntilFixed {
                val v: Int < Abort[Int] = 1
                assertCompiles("Async.timeout(1.second)(Async.run(v))")
                assertCompiles("Async.timeout(1.second)(Async.runAndBlock(1.second)(v))")
                assertCompiles("Async.timeout(1.second)(Async.mask(v))")
                assertCompiles("Async.timeout(1.second)(Async.timeout(1.second)(v))")
                assertCompiles("Async.timeout(1.second)(Async.race(v, v))")
                assertCompiles("Async.timeout(1.second)(Async.parallel(v, v))")
            }

            "runAndBlock" in pendingUntilFixed {
                val v: Int < Abort[Int] = 1
                assertCompiles("Async.runAndBlock(1.second)(Async.run(v))")
                assertCompiles("Async.runAndBlock(1.second)(Async.runAndBlock(1.second)(v))")
                assertCompiles("Async.runAndBlock(1.second)(Async.mask(v))")
                assertCompiles("Async.runAndBlock(1.second)(Async.timeout(1.second)(v))")
                assertCompiles("Async.runAndBlock(1.second)(Async.race(v, v))")
                assertCompiles("Async.runAndBlock(1.second)(Async.parallel(v, v))")
            }
        }
    }

    "timeout" - {
        "completes before timeout" in run {
            for
                result <- Async.timeout(1.second)(42)
            yield assert(result == 42)
        }

        "times out" in run {
            val result =
                for
                    value <- Async.timeout(5.millis)(Async.sleep(1.second).andThen(42))
                yield value

            Abort.run[Timeout](result).map {
                case Result.Fail(Timeout(_)) => succeed
                case other                   => fail(s"Expected Timeout, got $other")
            }
        }

        "infinite duration doesn't timeout" in run {
            for
                result <- Async.timeout(Duration.Infinity)(42)
            yield assert(result == 42)
        }

        "interrupts computation" in runJVM {
            for
                flag  <- AtomicBoolean.init(false)
                fiber <- Promise.init[Nothing, Int]
                _     <- fiber.onInterrupt(_ => flag.set(true))
                // TODO Boundary inference issue
                v = Async.timeout(1.millis)(fiber.get)
                result <- Async.run(v)
                result <- fiber.getResult
                _      <- untilTrue(flag.get)
            yield assert(result.isFail)
        }
    }

    "isolated locals inheritance" - {
        val isolatedInt    = Local.initIsolated(10)
        val isolatedString = Local.initIsolated("initial")
        val regularLocal   = Local.init("regular")

        "run" in run {
            for
                (i, s, r) <- isolatedInt.let(20) {
                    isolatedString.let("modified") {
                        regularLocal.let("modified") {
                            Async.run {
                                for
                                    i <- isolatedInt.get
                                    s <- isolatedString.get
                                    r <- regularLocal.get
                                yield (i, s, r)
                            }.map(_.get)
                        }
                    }
                }
            yield assert(i == 10 && s == "initial" && r == "modified")
        }

        "parallel" in run {
            for
                (i, s, r) <- isolatedInt.let(30) {
                    isolatedString.let("parallel") {
                        regularLocal.let("parallel") {
                            Async.parallel(
                                Async.run(isolatedInt.get).map(_.get),
                                Async.run(isolatedString.get).map(_.get),
                                Async.run(regularLocal.get).map(_.get)
                            )
                        }
                    }
                }
            yield assert(i == 10 && s == "initial" && r == "parallel")
        }

        "nested operations" in run {
            for
                (i, s, r) <- isolatedInt.let(50) {
                    isolatedString.let("outer") {
                        regularLocal.let("outer") {
                            Async.run {
                                isolatedInt.let(60) {
                                    isolatedString.let("inner") {
                                        regularLocal.let("inner") {
                                            Async.parallel(
                                                Async.run(isolatedInt.get).map(_.get),
                                                Async.run(isolatedString.get).map(_.get),
                                                Async.run(regularLocal.get).map(_.get)
                                            )
                                        }
                                    }
                                }
                            }.map(_.get)
                        }
                    }
                }
            yield assert(i == 10 && s == "initial" && r == "inner")
        }
    }

    "Async includes Abort[Nothing]" in run {
        val a: Int < Abort[Nothing] = 42
        val b: Int < Async          = a
        succeed
    }

end AsyncTest
