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
        "executes in a different thread" in runNotJS {
            val t1 = Thread.currentThread()
            for
                t2 <- Async.run(Thread.currentThread()).map(_.get)
            yield assert(t1 ne t2)
        }
        "multiple" in run {
            for
                v0               <- Async.run(0).map(_.get)
                (v1, v2)         <- Async.zip(1, 2)
                (v3, v4, v5)     <- Async.zip(3, 4, 5)
                (v6, v7, v8, v9) <- Async.zip(6, 7, 8, 9)
            yield assert(v0 + v1 + v2 + v3 + v4 + v5 + v6 + v7 + v8 + v9 == 45)
        }
        "nested" in runNotJS {
            val t1 = Thread.currentThread()
            for
                t2 <- Async.run(Sync(Async.run(Thread.currentThread()).map(_.get))).map(_.get)
            yield assert(t1 ne t2)
        }

        "with Sync-based effects" - {
            "Resource" in run {
                var closes = 0
                val a      = Resource.ensure(closes += 1).andThen(42)
                val b      = Async.run(a)
                Resource.run(b.map(_.get.map(v => assert(v == 42))))
            }
        }
        "non Sync-based effect" in run {
            typeCheckFailure("Async.run(Var.get[Int])")(
                "This operation requires Contextual isolation for effects"
            )
        }
    }

    "sleep" in run {
        for
            start <- Sync(java.lang.System.currentTimeMillis())
            _     <- Async.sleep(10.millis)
            end   <- Sync(java.lang.System.currentTimeMillis())
        yield assert(end - start >= 8)
    }

    "delay" in run {
        for
            start <- Sync(java.lang.System.currentTimeMillis())
            res   <- Async.delay(5.millis)(42)
            end   <- Sync(java.lang.System.currentTimeMillis())
        yield
            assert(end - start >= 4)
            assert(res == 42)
    }

    "runAndBlock" - {

        "timeout" in runNotJS {
            Async.sleep(1.day).andThen(1)
                .handle(
                    Async.timeout(10.millis),
                    Async.runAndBlock(Duration.Infinity),
                    Abort.run[Timeout]
                ).map {
                    case Result.Failure(_: Timeout) => succeed
                    case v                          => fail(v.toString())
                }
        }

        "block timeout" in runNotJS {
            Async.sleep(1.day).andThen(1)
                .handle(
                    Async.runAndBlock(10.millis),
                    Abort.run[Timeout]
                ).map {
                    case Result.Failure(_: Timeout) => succeed
                    case v                          => fail(v.toString())
                }
        }

        "multiple fibers timeout" in runNotJS {
            Kyo.fill(100)(Async.sleep(1.milli)).andThen(1)
                .handle(
                    Async.runAndBlock(10.millis),
                    Abort.run[Timeout]
                ).map {
                    case Result.Failure(_: Timeout) => succeed
                    case v                          => fail(v.toString())
                }
        }
    }

    val panic = Result.Panic(new Exception)

    "interrupt" - {

        def loop(ref: AtomicInt): Unit < Sync =
            ref.incrementAndGet.map(_ => loop(ref))

        def runLoop(started: Latch, done: Latch) =
            Resource.run {
                Resource.ensure(done.release).map { _ =>
                    started.release.map(_ => AtomicInt.init(0).map(loop))
                }
            }

        "one fiber" in run {
            for
                started     <- Latch.init(1)
                done        <- Latch.init(1)
                fiber       <- Async.run(runLoop(started, done))
                _           <- started.await
                interrupted <- fiber.interrupt(panic)
                _           <- done.await
            yield assert(interrupted)
        }
        "multiple fibers" in run {
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
        "zero" in run {
            typeCheckFailure("Async.race()")(
                "None of the overloaded alternatives of method race in object Async"
            )
        }
        "one" in run {
            Async.race(1).map { r =>
                assert(r == 1)
            }
        }
        "multiple" in run {
            val ac = new JAtomicInteger(0)
            val bc = new JAtomicInteger(0)
            def loop(i: Int, s: String): String < Sync =
                Sync {
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
        "waits for the first success" in run {
            val ex = new Exception
            Async.race(
                Async.sleep(10.millis).andThen(42),
                Abort.panic[Exception](ex)
            ).map { r =>
                assert(r == 42)
            }
        }
        "returns the last failure if all fibers fail" in run {
            val ex1 = new Exception
            val ex2 = new Exception
            val race =
                Async.race(
                    Async.sleep(10.millis).andThen(Abort.panic[Int](ex1)),
                    Abort.panic[Int](ex2)
                )
            Abort.run(race).map {
                r => assert(r == Result.panic(ex1))
            }
        }
        "never" in run {
            Async.race(Async.never, 1).map { r =>
                assert(r == 1)
            }
        }
    }

    "collectAll" - {
        "zero" in run {
            Async.collectAll(Seq()).map { r =>
                assert(r == Seq())
            }
        }
        "one" in run {
            Async.collectAll(Seq(1)).map { r =>
                assert(r == Seq(1))
            }
        }
        "n" in run {
            val ac = new JAtomicInteger(0)
            val bc = new JAtomicInteger(0)
            def loop(i: Int, s: String): String < Sync =
                Sync {
                    if i > 0 then
                        if s.equals("a") then ac.incrementAndGet()
                        else bc.incrementAndGet()
                        loop(i - 1, s)
                    else
                        s
                }
            Async.collectAll(List(loop(1, "a"), loop(5, "b"))).map { r =>
                assert(r == List("a", "b"))
                assert(ac.get() == 1)
                assert(bc.get() == 5)
            }
        }
        "three arguments" in run {
            for
                (v1, v2, v3) <- Async.zip(Sync(1), Sync(2), Sync(3))
            yield assert(v1 == 1 && v2 == 2 && v3 == 3)
        }
        "four arguments" in run {
            for
                (v1, v2, v3, v4) <- Async.zip(Sync(1), Sync(2), Sync(3), Sync(4))
            yield assert(v1 == 1 && v2 == 2 && v3 == 3 && v4 == 4)
        }
    }

    "transform" in run {
        for
            v1       <- Async.run(1).map(_.get)
            (v2, v3) <- Async.zip(2, 3)
            l        <- Async.collectAll(List[Int < Any](4, 5))
        yield assert(v1 + v2 + v3 + l.sum == 15)
    }

    "interrupt" in run {
        def loop(ref: AtomicInt): Unit < Async =
            ref.incrementAndGet.map(_ => loop(ref))

        def task(started: Latch, done: Latch): Unit < Async =
            Resource.run {
                Resource.ensure(done.release).map { _ =>
                    started.release.andThen(AtomicInt.init(0).map(loop))
                }
            }

        for
            started     <- Latch.init(1)
            done        <- Latch.init(1)
            fiber       <- Async.run(task(started, done))
            _           <- started.await
            interrupted <- fiber.interrupt(panic)
            _           <- done.await
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
                    v1 <- Sync(r.incrementAndGet())
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
            Async.zip(
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
                    v1 <- Sync(r.incrementAndGet())
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
                Async.collectAll(List(l.get, l.get)).map(v => assert(v == List(10, 10)))
            }
            "let" in run {
                l.let(20)(Async.collectAll(List(l.get, l.get)).map(v => assert(v == List(20, 20))))
            }
        }
    }

    "fromFuture" - {
        import scala.concurrent.Future

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
            val _: Fiber[Int, Int] < Sync                      = Async.run(v)
            val _: Int < (Abort[Int | Timeout] & Sync)         = Async.runAndBlock(1.second)(v)
            val _: Int < (Abort[Int] & Async)                  = Async.mask(v)
            val _: Int < (Abort[Int | Timeout] & Async)        = Async.timeout(1.second)(v)
            val _: Int < (Abort[Int] & Async)                  = Async.race(Seq(v))
            val _: Int < (Abort[Int] & Async)                  = Async.race(v, v)
            val _: Seq[Int] < (Abort[Int] & Async)             = Async.collectAll(Seq(v))
            val _: (Int, Int) < (Abort[Int] & Async)           = Async.zip(v, v)
            val _: (Int, Int, Int) < (Abort[Int] & Async)      = Async.zip(v, v, v)
            val _: (Int, Int, Int, Int) < (Abort[Int] & Async) = Async.zip(v, v, v, v)
            succeed
        }
        "additional failure" in {
            val v: Int < Abort[Int]                                     = 1
            val _: Fiber[Int | String, Int] < Sync                      = Async.run(v)
            val _: Int < (Abort[Int | Timeout | String] & Sync)         = Async.runAndBlock(1.second)(v)
            val _: Int < (Abort[Int | String] & Async)                  = Async.mask(v)
            val _: Int < (Abort[Int | Timeout | String] & Async)        = Async.timeout(1.second)(v)
            val _: Int < (Abort[Int | String] & Async)                  = Async.race(Seq(v))
            val _: Int < (Abort[Int | String] & Async)                  = Async.race(v, v)
            val _: Seq[Int] < (Abort[Int | String] & Async)             = Async.collectAll(Seq(v))
            val _: (Int, Int) < (Abort[Int | String] & Async)           = Async.zip(v, v)
            val _: (Int, Int, Int) < (Abort[Int | String] & Async)      = Async.zip(v, v, v)
            val _: (Int, Int, Int, Int) < (Abort[Int | String] & Async) = Async.zip(v, v, v, v)
            succeed
        }
        "nested" - {
            "run" in {
                val v: Int < Abort[Int] = 1

                val _: Fiber[Nothing, Fiber[Int, Int]] < Sync  = Async.run(Async.run(v))
                val _: Fiber[Int | Timeout, Int] < Sync        = Async.run(Async.runAndBlock(1.second)(v))
                val _: Fiber[Int, Int] < Sync                  = Async.run(Async.mask(v))
                val _: Fiber[Int | Timeout, Int] < Sync        = Async.run(Async.timeout(1.second)(v))
                val _: Fiber[Int, Int] < Sync                  = Async.run(Async.race(Seq(v)))
                val _: Fiber[Int, Int] < Sync                  = Async.run(Async.race(v, v))
                val x: Fiber[Int, Seq[Int]] < Sync             = Async.run(Async.collectAll(Seq(v)))
                val _: Fiber[Int, (Int, Int)] < Sync           = Async.run(Async.zip(v, v))
                val _: Fiber[Int, (Int, Int, Int)] < Sync      = Async.run(Async.zip(v, v, v))
                val _: Fiber[Int, (Int, Int, Int, Int)] < Sync = Async.run(Async.zip(v, v, v, v))
                succeed
            }

            "zip" in run {
                val v: Int < Abort[Int] = 1

                val _: (Fiber[Int, Int], Fiber[Int, Int]) < Async = Async.zip(Async.run(v), Async.run(v))
                val _: (Int, Int) < (Abort[Int | Timeout] & Async) =
                    Async.zip(Async.runAndBlock(1.second)(v), Async.runAndBlock(1.second)(v))
                val _: (Int, Int) < (Abort[Int] & Async)               = Async.zip(Async.mask(v), Async.mask(v))
                val _: (Int, Int) < (Abort[Int | Timeout] & Async)     = Async.zip(Async.timeout(1.second)(v), Async.timeout(1.second)(v))
                val _: (Int, Int) < (Abort[Int] & Async)               = Async.zip(Async.race(v, v), Async.race(v, v))
                val _: ((Int, Int), (Int, Int)) < (Abort[Int] & Async) = Async.zip(Async.zip(v, v), Async.zip(v, v))
                succeed
            }

            "race" in {
                val v: Int < Abort[Int] = 1

                val _: Fiber[Int, Int] < Async              = Async.race(Async.run(v), Async.run(v))
                val _: Int < (Abort[Int | Timeout] & Async) = Async.race(Async.runAndBlock(1.second)(v), Async.runAndBlock(1.second)(v))
                val _: Int < (Abort[Int] & Async)           = Async.race(Async.mask(v), Async.mask(v))
                val _: Int < (Abort[Int | Timeout] & Async) = Async.race(Async.timeout(1.second)(v), Async.timeout(1.second)(v))
                val _: Int < (Abort[Int] & Async)           = Async.race(Async.race(v, v), Async.race(v, v))
                val _: (Int, Int) < (Abort[Int] & Async)    = Async.race(Async.zip(v, v), Async.zip(v, v))
                succeed
            }

            "mask" in {
                val v: Int < Abort[Int] = 1

                val _: Fiber[Int, Int] < Async              = Async.mask(Async.run(v))
                val _: Int < (Abort[Int | Timeout] & Async) = Async.mask(Async.runAndBlock(1.second)(v))
                val _: Int < (Abort[Int] & Async)           = Async.mask(Async.mask(v))
                val _: Int < (Abort[Int | Timeout] & Async) = Async.mask(Async.timeout(1.second)(v))
                val _: Int < (Abort[Int] & Async)           = Async.mask(Async.race(v, v))
                val _: (Int, Int) < (Abort[Int] & Async)    = Async.mask(Async.zip(v, v))
                succeed
            }

            "timeout" in {
                val v: Int < Abort[Int] = 1

                val _: Fiber[Int, Int] < (Abort[Timeout] & Async)  = Async.timeout(1.second)(Async.run(v))
                val _: Int < (Abort[Int | Timeout] & Async)        = Async.timeout(1.second)(Async.runAndBlock(1.second)(v))
                val _: Int < (Abort[Int | Timeout] & Async)        = Async.timeout(1.second)(Async.mask(v))
                val _: Int < (Abort[Int | Timeout] & Async)        = Async.timeout(1.second)(Async.timeout(1.second)(v))
                val _: Int < (Abort[Int | Timeout] & Async)        = Async.timeout(1.second)(Async.race(v, v))
                val _: (Int, Int) < (Abort[Int | Timeout] & Async) = Async.timeout(1.second)(Async.zip(v, v))
                succeed
            }

            "runAndBlock" in {
                val v: Int < Abort[Int] = 1

                val _: Fiber[Int, Int] < (Abort[Timeout] & Sync)  = Async.runAndBlock(1.second)(Async.run(v))
                val _: Int < (Abort[Int | Timeout] & Sync)        = Async.runAndBlock(1.second)(Async.runAndBlock(1.second)(v))
                val _: Int < (Abort[Int | Timeout] & Sync)        = Async.runAndBlock(1.second)(Async.mask(v))
                val _: Int < (Abort[Int | Timeout] & Sync)        = Async.runAndBlock(1.second)(Async.timeout(1.second)(v))
                val _: Int < (Abort[Int | Timeout] & Sync)        = Async.runAndBlock(1.second)(Async.race(v, v))
                val _: (Int, Int) < (Abort[Int | Timeout] & Sync) = Async.runAndBlock(1.second)(Async.zip(v, v))
                succeed
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
                case Result.Failure(_: Timeout) => succeed
                case other                      => fail(s"Expected Timeout, got $other")
            }
        }

        "infinite duration doesn't timeout" in run {
            for
                result <- Async.timeout(Duration.Infinity)(42)
            yield assert(result == 42)
        }

        "interrupts computation" in run {
            for
                flag   <- AtomicBoolean.init(false)
                fiber  <- Promise.init[Nothing, Int]
                _      <- fiber.onInterrupt(_ => flag.set(true))
                result <- Async.run(Async.timeout(1.millis)(fiber.get))
                result <- fiber.getResult
                _      <- untilTrue(flag.get)
            yield assert(result.isPanic)
        }
    }

    "isolated locals inheritance" - {
        val isolatedInt    = Local.initNoninheritable(10)
        val isolatedString = Local.initNoninheritable("initial")
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
                            Async.zip(
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
                                            Async.zip(
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

    "collectAll concurrency" - {
        "empty sequence" in run {
            Async.collectAll(Seq(), 2).map { r =>
                assert(r == Seq())
            }
        }

        "sequence smaller than parallelism" in run {
            Async.collectAll(Seq(1, 2, 3), 5).map { r =>
                assert(r == Seq(1, 2, 3))
            }
        }

        "sequence larger than parallelism" in run {
            AtomicInt.init.map { counter =>
                def task(i: Int): Int < (Sync & Async) =
                    for
                        current <- counter.getAndIncrement
                        _       <- Async.sleep(1.millis)
                        _       <- counter.decrementAndGet
                    yield
                        assert(current < 2)
                        i

                Async.collectAll((1 to 20).map(task), 2).map { r =>
                    counter.get.map { counter =>
                        assert(r == (1 to 20))
                        assert(counter == 0)
                    }
                }
            }
        }

        "parallelism of 1 executes sequentially" in run {
            AtomicInt.init.map { counter =>
                def task(i: Int): Int < (Sync & Async) =
                    for
                        current <- counter.getAndIncrement
                        _       <- Async.sleep(1.millis)
                        _       <- counter.decrementAndGet
                    yield
                        assert(current == 0)
                        i

                Async.collectAll((1 to 5).map(task), 1).map { r =>
                    counter.get.map { counter =>
                        assert(r == (1 to 5))
                        assert(counter == 0)
                    }
                }
            }
        }
    }

    "with isolates" - {
        "mask with isolate" in run {
            Var.runTuple(1) {
                for
                    start <- Var.get[Int]
                    _ <-
                        Var.isolate.update[Int].use {
                            Async.mask {
                                for
                                    _ <- Var.set(2)
                                    _ <- Async.sleep(1.millis)
                                    _ <- Var.set(3)
                                yield ()
                            }
                        }
                    end <- Var.get[Int]
                yield (start, end)
            }.map { result =>
                assert(result == (3, (1, 3)))
            }
        }

        "timeout with isolate" in run {

            Emit.run {
                Emit.isolate.merge[Int].use {
                    Async.timeout(1.hour) {
                        for
                            _ <- Emit.value(1)
                            _ <- Async.sleep(1.millis)
                            _ <- Emit.value(2)
                        yield "done"
                    }
                }
            }.map { result =>
                assert(result == (Chunk(1, 2), "done"))
            }
        }

        "race with isolate" in run {

            Var.runTuple(0) {
                Var.isolate.update[Int].use {
                    Async.race(
                        Seq(
                            for
                                _ <- Var.set(1)
                                _ <- Async.sleep(1.millis)
                                v <- Var.get[Int]
                            yield v,
                            for
                                _ <- Var.set(2)
                                _ <- Async.sleep(50.millis)
                                v <- Var.get[Int]
                            yield v
                        )
                    )
                }
            }.map { result =>
                assert(result == (1, 1))
            }
        }

        "collectAll with concurrency limit + isolate" in run {
            var count = 0
            val f = Memo[Int, Int, Any] { x =>
                count += 1
                x * 2
            }

            Memo.run {
                Kyo.zip(
                    Async.collectAll(
                        Seq(
                            f(1),
                            f(1),
                            f(1)
                        ),
                        3
                    ),
                    f(1)
                )
            }.map { result =>
                assert(result == (Seq(2, 2, 2), 2))
                assert(count <= 3)
            }
        }

        "collectAll with isolate" in run {

            Emit.run {
                Emit.isolate.merge[String].use {
                    Async.collectAll(
                        Seq(
                            for
                                _ <- Emit.value("a1")
                                _ <- Async.sleep(2.millis)
                                _ <- Emit.value("a2")
                            yield 1,
                            for
                                _ <- Emit.value("b1")
                                _ <- Async.sleep(1.millis)
                                _ <- Emit.value("b2")
                            yield 2
                        )
                    )
                }
            }.map { result =>
                assert(result._1.size == 4)
                assert(result._2 == Seq(1, 2))
            }
        }
    }

    "filter" - {
        "filters elements" in run {
            Async.filter(1 to 10)(_ % 2 == 0).map { r =>
                assert(r == Chunk(2, 4, 6, 8, 10))
            }
        }
    }

    "collect" - {
        "transforms and filters elements" in run {
            Async.collect(1 to 5) { i =>
                Maybe.when(i % 2 == 0)(i * 2)
            }.map { r =>
                assert(r == Chunk(4, 8))
            }
        }
    }

    "repeat" - {
        "concurrently repeats computation n times" in run {
            for
                counter <- AtomicInt.init(0)
                results <- Async.fill(3) {
                    counter.incrementAndGet
                }
                count <- counter.get
            yield
                assert(results.toSet == Set(1, 2, 3))
                assert(count == 3)
        }
    }

    "foreachDiscard" - {
        "executes side effects" in run {
            for
                counter <- AtomicInt.init(0)
                _ <- Async.foreachDiscard(1 to 3) { _ =>
                    counter.incrementAndGet
                }
                count <- counter.get
            yield assert(count == 3)
        }
    }

    "collectAllDiscard" - {
        "executes all effects" in run {
            for
                counter <- AtomicInt.init(0)
                _ <- Async.collectAllDiscard(
                    List(
                        counter.incrementAndGet,
                        counter.incrementAndGet,
                        counter.incrementAndGet
                    )
                )
                count <- counter.get
            yield assert(count == 3)
        }
    }

    "gather" - {
        "sequence" - {
            "delegates to Fiber.gather" in run {
                for
                    result <- Async.gather(Seq(Sync(1), Sync(2), Sync(3)))
                yield assert(result == Chunk(1, 2, 3))
            }

            "with max limit delegates to Fiber.gather" in run {
                for
                    result <- Async.gather(2)(Seq(Sync(1), Sync(2), Sync(3)))
                yield
                    assert(result.size == 2)
                    assert(result.forall(Seq(1, 2, 3).contains))
            }
        }

        "varargs" - {
            "delegates to sequence-based gather" in run {
                for
                    result <- Async.gather(Sync(1), Sync(2), Sync(3))
                yield assert(result == Chunk(1, 2, 3))
            }

            "with max limit delegates to sequence-based gather" in run {
                for
                    result <- Async.gather(2)(Sync(1), Sync(2), Sync(3))
                yield
                    assert(result.size == 2)
                    assert(result.forall(Seq(1, 2, 3).contains))
            }
        }

        "with isolate" - {
            "sequence-based" in run {

                Emit.run {
                    Emit.isolate.merge[String].use {
                        Async.gather(
                            Seq(
                                for
                                    _ <- Emit.value("a1")
                                    _ <- Async.sleep(2.millis)
                                    _ <- Emit.value("a2")
                                yield 1,
                                for
                                    _ <- Emit.value("b1")
                                    _ <- Async.sleep(1.millis)
                                    _ <- Emit.value("b2")
                                yield 2
                            )
                        )
                    }
                }.map { result =>
                    assert(result._1.size == 4)
                    assert(result._2 == Chunk(1, 2))
                }
            }

            "sequence-based with max" in run {

                Emit.run {
                    Emit.isolate.merge[String].use {
                        Latch.initWith(1) { latch =>
                            Async.gather(1)(
                                Seq(
                                    for
                                        _ <- Emit.value("a1")
                                        _ <- latch.await
                                        _ <- Emit.value("a2")
                                    yield 1,
                                    for
                                        _ <- Emit.value("b1")
                                        _ <- latch.release
                                        _ <- Emit.value("b2")
                                    yield 2
                                )
                            )
                        }
                    }
                }.map { (emitted, result) =>
                    assert(emitted.size == 2)
                    assert(result.size == 1)
                    assert(result.head == 1 || result.head == 2)
                }
            }

            "varargs-based" in run {
                Emit.run {
                    Emit.isolate.merge[String].use {
                        Async.gather(
                            for
                                _ <- Emit.value("a1")
                                _ <- Async.sleep(2.millis)
                                _ <- Emit.value("a2")
                            yield 1,
                            for
                                _ <- Emit.value("b1")
                                _ <- Async.sleep(1.millis)
                                _ <- Emit.value("b2")
                            yield 2
                        )
                    }
                }.map { result =>
                    assert(result._1.size == 4)
                    assert(result._2 == Chunk(1, 2))
                }
            }

            "varargs-based with max" in run {
                Emit.run {
                    Emit.isolate.merge[String].use {
                        Async.gather(1)(
                            for
                                _ <- Emit.value("a1")
                                _ <- Async.sleep(50.millis)
                                _ <- Emit.value("a2")
                            yield 1,
                            for
                                _ <- Emit.value("b1")
                                _ <- Async.sleep(1.millis)
                                _ <- Emit.value("b2")
                            yield 2
                        )
                    }
                }.map { result =>
                    assert(result._1.size == 2)
                    assert(result._2.size == 1)
                    assert(result._2.head == 2)
                }
            }

            "handles failures" in run {
                val error = new RuntimeException("test error")

                Emit.run {
                    Abort.run {
                        Emit.isolate.merge[String].use {
                            Async.gather(
                                for
                                    _ <- Emit.value("a1")
                                    _ <- Abort.fail(error)
                                    _ <- Emit.value("a2")
                                yield 1,
                                for
                                    _ <- Emit.value("b1")
                                    _ <- Async.sleep(1.millis)
                                    _ <- Emit.value("b2")
                                yield 2
                            )
                        }
                    }
                }.map { case (emitted, result) =>
                    assert(emitted == Seq("b1", "b2"))
                    assert(result == Result.succeed(Chunk(2)))
                }
            }

            "handles multiple failures" in run {
                val error1 = new RuntimeException("error 1")
                val error2 = new RuntimeException("error 2")

                Emit.run {
                    Abort.run {
                        Emit.isolate.merge[String].use {
                            Async.gather(
                                for
                                    _ <- Emit.value("a1")
                                    _ <- Abort.fail(error1)
                                yield 1,
                                for
                                    _ <- Emit.value("b1")
                                    _ <- Abort.fail(error2)
                                yield 2,
                                for
                                    _ <- Emit.value("c1")
                                    _ <- Async.sleep(1.millis)
                                    _ <- Emit.value("c2")
                                yield 3
                            )
                        }
                    }
                }.map { case (emitted, result) =>
                    assert(emitted == Chunk("c1", "c2"))
                    assert(result == Result.succeed(Chunk(3)))
                }
            }

            "with max limit handles partial failures" in run {
                val error = new RuntimeException("test error")

                Emit.run {
                    Emit.isolate.merge[String].use {
                        Async.gather(2)(
                            for
                                _ <- Emit.value("a1")
                                _ <- Abort.fail(error)
                            yield 1,
                            for
                                _ <- Emit.value("b1")
                                _ <- Async.sleep(2.millis)
                                _ <- Emit.value("b2")
                            yield 2,
                            for
                                _ <- Emit.value("c1")
                                _ <- Async.sleep(1.millis)
                                _ <- Emit.value("c2")
                            yield 3
                        )
                    }
                }.map { case (emitted, results) =>
                    assert(emitted == Chunk("b1", "b2", "c1", "c2"))
                    assert(results == Chunk(2, 3))
                }
            }

            "preserves state isolation during failures" in run {
                val error = new RuntimeException("test error")

                Var.runTuple(21) {
                    Abort.run {
                        Var.isolate.update[Int].use {
                            Async.gather(
                                for
                                    _ <- Var.update[Int](_ + 2)
                                    _ <- Abort.fail(error)
                                yield "a",
                                for
                                    _ <- Var.update[Int](_ * 2)
                                    _ <- Async.sleep(1.millis)
                                yield "b"
                            )
                        }
                    }
                }.map { case (finalState, result) =>
                    assert(finalState == 42)
                    assert(result == Result.succeed(Chunk("b")))
                }
            }
        }
    }

    "preemption is properly handled in nested Async computations" - {
        "simple" in run {
            Async.run(Async.run(Async.delay(100.millis)(42))).map(_.get).map(_.get).map { result =>
                assert(result == 42)
            }
        }
        "with nested eval" in run {
            import AllowUnsafe.embrace.danger
            val task = Sync.Unsafe.evalOrThrow(Async.run(Async.delay(100.millis)(42)))
            Async.run(task).map(_.get).map(_.get).map { result =>
                assert(result == 42)
            }
        }
        "with multiple nested evals" in run {
            import AllowUnsafe.embrace.danger
            val innerTask  = Sync.Unsafe.evalOrThrow(Async.run(Async.delay(100.millis)(42)))
            val middleTask = Sync.Unsafe.evalOrThrow(Async.run(innerTask))
            val outerTask  = Sync.Unsafe.evalOrThrow(Async.run(middleTask))
            Async.run(outerTask).map(_.get).map(_.get).map(_.get).map(_.get).map { result =>
                assert(result == 42)
            }
        }
        "with eval inside async computation" in run {
            import AllowUnsafe.embrace.danger
            Async.run {
                Async.delay(100.millis) {
                    Sync.Unsafe.evalOrThrow(Async.run(42)).get
                }
            }.map(_.get).map { result =>
                assert(result == 42)
            }
        }
        "with interleaved evals and delays" in run {
            import AllowUnsafe.embrace.danger
            val task1 = Sync.Unsafe.evalOrThrow(Async.run(Async.delay(100.millis)(1)))
            val task2 = Async.delay(100.millis) {
                Sync.Unsafe.evalOrThrow(Async.run(task1)).get
            }
            val task3 = Sync.Unsafe.evalOrThrow(Async.run(task2))
            Async.run(task3).map(_.get).map(_.get).map(_.get).map { result =>
                assert(result == 1)
            }
        }
        "with race" in run {
            Async.run {
                Latch.initWith(1) { latch =>
                    Async.race(
                        Async.run(Async.delay(100.millis)(latch.release.andThen(1))).map(_.get),
                        Async.run(latch.await.andThen(Async.delay(100.millis)(2))).map(_.get)
                    )
                }
            }.map(_.get).map { result =>
                assert(result == 1)
            }
        }
    }

    "fillIndexed" - {
        "creates n computations with index access" in run {
            for
                results <- Async.fillIndexed(5) { i =>
                    i * 2
                }
            yield assert(results == Chunk(0, 2, 4, 6, 8))
        }

        "handles empty input" in run {
            for
                results <- Async.fillIndexed(0) { i =>
                    fail(s"Should not be called with i=$i")
                }
            yield assert(results.isEmpty)
        }
    }

    "memoize" - {
        "caches successful results" in run {
            for
                counter <- AtomicInt.init(0)
                memoized <- Async.memoize {
                    counter.incrementAndGet.map(_ => 42)
                }
                v1    <- memoized
                v2    <- memoized
                v3    <- memoized
                count <- counter.get
            yield
                assert(v1 == 42)
                assert(v2 == 42)
                assert(v3 == 42)
                assert(count == 1)
        }

        "retries after failure" in run {
            for
                counter <- AtomicInt.init(0)
                memoized <- Async.memoize {
                    counter.incrementAndGet.map { count =>
                        if count == 1 then throw new RuntimeException("First attempt fails")
                        else 42
                    }
                }
                r1    <- Abort.run(memoized)
                v2    <- memoized
                v3    <- memoized
                count <- counter.get
            yield
                assert(r1.isPanic)
                assert(v2 == 42)
                assert(v3 == 42)
                assert(count == 2)
        }

        "works with async operations" in run {
            for
                counter <- AtomicInt.init(0)
                memoized <- Async.memoize {
                    for
                        _     <- Async.sleep(1.millis)
                        count <- counter.incrementAndGet
                    yield count
                }
                v1    <- memoized
                v2    <- memoized
                v3    <- memoized
                count <- counter.get
            yield
                assert(v1 == 1)
                assert(v2 == 1)
                assert(v3 == 1)
                assert(count == 1)
        }

        "handles concurrent access" in run {
            for
                counter <- AtomicInt.init(0)
                memoized <- Async.memoize {
                    for
                        _     <- Async.sleep(1.millis)
                        count <- counter.incrementAndGet
                    yield count
                }
                results <- Async.zip(
                    memoized,
                    memoized,
                    memoized
                )
                count <- counter.get
            yield
                assert(results._1 == 1)
                assert(results._2 == 1)
                assert(results._3 == 1)
                assert(count == 1)
        }

        "handles interruption during initialization" in run {
            for
                counter  <- AtomicInt.init(0)
                started  <- Latch.init(1)
                sleeping <- Latch.init(1)
                done     <- Latch.init(1)
                memoized <- Async.memoize {
                    Sync.ensure(done.release) {
                        for
                            _     <- started.release
                            _     <- Async.sleep(50.millis)
                            count <- counter.incrementAndGet
                        yield count
                    }
                }
                fiber <- Async.run(memoized)
                _     <- started.await
                _     <- fiber.interrupt
                _     <- done.await
                v2    <- memoized
                count <- counter.get
            yield
                assert(v2 == 1)
                assert(count == 1)
        }
    }

    "apply" - {
        "suspends computation" in run {
            var counter = 0
            val computation = Async {
                counter += 1
                counter
            }
            for
                v1 <- computation
                v2 <- computation
                v3 <- computation
            yield
                assert(v1 == 1)
                assert(v2 == 2)
                assert(v3 == 3)
                assert(counter == 3)
            end for
        }

        "preserves effects" in run {
            var executed = false
            for
                started <- Latch.init(1)
                done    <- Latch.init(1)
                fiber <- Async.run {
                    started.release.andThen {
                        Async { executed = true }.andThen {
                            done.release
                        }
                    }
                }
                _ <- started.await
                _ <- done.await
            yield assert(executed)
            end for
        }
    }

    "zip" - {
        "executes nine computations in parallel" in run {
            for
                result <- Async.zip(
                    Sync(1),
                    Sync(2),
                    Sync(3),
                    Sync(4),
                    Sync(5),
                    Sync(6),
                    Sync(7),
                    Sync(8),
                    Sync(9)
                )
            yield assert(result == (1, 2, 3, 4, 5, 6, 7, 8, 9))
        }

        "executes ten computations in parallel" in run {
            for
                result <- Async.zip(
                    Sync(1),
                    Sync(2),
                    Sync(3),
                    Sync(4),
                    Sync(5),
                    Sync(6),
                    Sync(7),
                    Sync(8),
                    Sync(9),
                    Sync(10)
                )
            yield assert(result == (1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        }
    }

    "fiber with multiple children" - {
        "immediate" in run {
            for
                done <- Latch.init(1)
                exit <- Latch.init(1)
                fiber <- Async.run {
                    Kyo.fill(100) {
                        Promise.init[Nothing, Int].map { p2 =>
                            p2.completeDiscard(Result.succeed(1)).andThen(p2.get)
                        }
                    }.andThen(done.release).andThen(exit.await)
                }
                _       <- done.await
                waiters <- fiber.waiters
                _       <- exit.release
            yield assert(waiters == 1)
        }
        "with delay" in run {
            for
                done <- Latch.init(1)
                exit <- Latch.init(1)
                fiber <- Async.run {
                    Kyo.fill(100) {
                        Async.sleep(1.nanos)
                    }.andThen(done.release).andThen(exit.await)
                }
                _       <- done.await
                waiters <- fiber.waiters
                _       <- exit.release
            yield assert(waiters == 1)
        }
    }

    "effect nesting" - {
        "basic nesting and cancellation" in run {
            val nested =
                Async.sleep(1.millis).andThen {
                    Kyo.lift(Async.sleep(1.millis).andThen(42))
                }

            for
                res1 <- nested.flatten.handle(Async.run).map(_.get)
                res2 <- nested.map(_.handle(Async.run)).handle(Async.run).map(_.get).map(_.get)
            yield
                assert(res1 == 42)
                assert(res2 == 42)
            end for
        }

        "parallel composition" in run {
            val nested = Kyo.lift {
                val comp1 = Async.sleep(1.millis).andThen(1)
                val comp2 = Async.sleep(1.millis).andThen(2)

                Async.zip(comp1, comp2).map { case (a, b) =>
                    Kyo.lift(Async.sleep(5.millis).andThen(a + b))
                }
            }

            nested.flatten.flatten.map { result =>
                assert(result == 3)
            }
        }
    }

end AsyncTest
