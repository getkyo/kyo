package kyo

import java.io.Closeable
import kyo.*
import scala.util.control.NoStackTrace

class ResourceTest extends Test:

    case class TestResource(id: Int, var closes: Int = 0) extends Closeable derives CanEqual:
        var acquires = 0
        def apply() =
            acquires += 1
            this
        def close() = closes += 1
    end TestResource

    case class EffectfulResource(id: Int, closes: AtomicInt):
        def close: Unit < IO =
            closes.incrementAndGet.unit

    end EffectfulResource
    object EffectfulResource:
        def apply(id: Int): EffectfulResource < IO =
            for
                cl <- AtomicInt.init(0)
            yield EffectfulResource(id, cl)
    end EffectfulResource

    "acquire + tranform + close" in run {
        val r1 = TestResource(1)
        val r2 = TestResource(2)
        Resource.acquire(r1()).map(_ => assert(r1.closes == 0))
            .pipe(Resource.run)
            .map { _ =>
                assert(r1.closes == 1)
                assert(r2.closes == 0)
                assert(r1.acquires == 1)
                assert(r2.acquires == 0)
            }
    }

    "acquire + effectful tranform + close" taggedAs jvmOnly in {
        import AllowUnsafe.embrace.danger
        val r1 = TestResource(1)
        val r2 = TestResource(2)
        val r =
            Resource.acquire(r1()).map { _ =>
                assert(r1.closes == 0)
                Env.get[Int]
            }.pipe(Resource.run)
                .pipe(IO.Unsafe.run)
        assert(r1.closes == 0)
        assert(r2.closes == 0)
        assert(r1.acquires == 1)
        assert(r2.acquires == 0)
        Async.runAndBlock(timeout)(r)
            .pipe(Env.run(1))
            .pipe(Abort.run(_))
            .pipe(IO.Unsafe.evalOrThrow)
        assert(r1.closes == 1)
        assert(r2.closes == 0)
        assert(r1.acquires == 1)
        assert(r2.acquires == 0)
    }

    "two acquires + close" in run {
        val r1 = TestResource(1)
        val r2 = TestResource(2)
        Resource.acquire(r1()).map(_ => Resource.acquire(r2()))
            .pipe(Resource.run)
            .map { _ =>
                assert(r1.closes == 1)
                assert(r2.closes == 1)
                assert(r1.acquires == 1)
                assert(r2.acquires == 1)
            }
    }

    "two acquires + for-comp + close" in run {
        val r1 = TestResource(1)
        val r2 = TestResource(2)
        val io =
            for
                r1 <- Resource.acquire(r1())
                i1 = r1.id * 3
                r2 <- Resource.acquire(r2())
                i2 = r2.id * 5
            yield i1 + i2
        io.pipe(Resource.run)
            .map { r =>
                assert(r == 13)
                assert(r1.closes == 1)
                assert(r2.closes == 1)
                assert(r1.acquires == 1)
                assert(r2.acquires == 1)
            }
    }

    "two acquires + effectful for-comp + close" taggedAs jvmOnly in {
        import AllowUnsafe.embrace.danger
        val r1 = TestResource(1)
        val r2 = TestResource(2)
        val io =
            for
                r1 <- Resource.acquire(r1())
                i1 <- Env.get[Int].map(_ * r1.id)
                r2 <- Resource.acquire(r2())
                i2 <- Env.get[Int].map(_ * r2.id)
            yield i1 + i2
        val r =
            io.pipe(Resource.run)
                .pipe(IO.Unsafe.run)
        assert(r1.closes == 0)
        assert(r2.closes == 0)
        assert(r1.acquires == 1)
        assert(r2.acquires == 0)
        r.pipe(Env.run(3))
            .pipe(Async.runAndBlock(timeout))
            .pipe(Abort.run(_))
            .pipe(IO.Unsafe.evalOrThrow)
        assert(r1.closes == 1)
        assert(r2.closes == 1)
        assert(r1.acquires == 1)
        assert(r2.acquires == 1)
    }

    "nested" in run {
        val r1 = TestResource(1)
        Resource.acquire(r1())
            .pipe(Resource.run)
            .pipe(Resource.run)
            .map { r =>
                assert(r == r1)
                assert(r1.acquires == 1)
                assert(r1.closes == 1)
            }
    }

    "effectful acquireRelease" taggedAs jvmOnly in run {
        val io =
            for
                r          <- Resource.acquireRelease(EffectfulResource(1))(_.close)
                closeCount <- r.closes.get
            yield
                assert(closeCount == 0)
                r
        io.pipe(Resource.run)
            .pipe(Async.runAndBlock(timeout))
            .pipe(Abort.run(_))
            .map { finalizedResource =>
                finalizedResource.fold(_ => ???)(_.closes.get.map(i => assert(i == 1)))
            }
    }

    "integration with other effects" - {

        "ensure" taggedAs jvmOnly in run {
            var closes = 0
            Resource.ensure(Async.run(closes += 1).map(_.get).unit)
                .pipe(Resource.run)
                .pipe(Async.runAndBlock(timeout))
                .pipe(Abort.run(_))
                .map { _ =>
                    assert(closes == 1)
                }
        }

        "acquireRelease" taggedAs jvmOnly in run {
            var closes = 0
            // any effects in acquire
            val acquire = Abort.get(Some(42))
            // only Async in release
            def release(i: Int) =
                Async.run {
                    assert(i == 42)
                    closes += 1
                }.map(_.get)
            Resource.acquireRelease(acquire)(release)
                .pipe(Resource.run)
                .pipe(Async.runAndBlock(timeout))
                .pipe(Abort.run[Timeout](_))
                .pipe(Abort.run[Absent](_))
                .map { _ =>
                    assert(closes == 1)
                }
        }

        "acquire" taggedAs jvmOnly in run {
            val r = TestResource(1)
            Resource.acquire(Async.run(r).map(_.get))
                .pipe(Resource.run)
                .pipe(Async.runAndBlock(timeout))
                .pipe(Abort.run(_))
                .map { _ =>
                    assert(r.closes == 1)
                }
        }
    }

    "failures" - {
        case object TestException extends NoStackTrace

        "acquire fails" taggedAs jvmOnly in run {
            val io = Resource.acquireRelease(IO[Int, Any](throw TestException))(_ => IO.unit)
            Resource.run(io)
                .pipe(Async.runAndBlock(timeout))
                .pipe(Abort.run(_))
                .map {
                    case Result.Panic(t) => assert(t eq TestException)
                    case _               => fail("Expected panic")
                }
        }

        "release fails" taggedAs jvmOnly in run {
            var acquired = false
            var released = false
            val io = Resource.acquireRelease(IO { acquired = true; "resource" }) { _ =>
                IO {
                    released = true
                    throw TestException
                }
            }
            Resource.run(io)
                .pipe(Async.runAndBlock(timeout))
                .pipe(Abort.run(_))
                .map { _ =>
                    assert(acquired && released)
                }
        }

        "ensure fails" in run {
            var ensureCalled = false
            val io           = Resource.ensure(IO { ensureCalled = true; throw TestException })
            Resource.run(io)
                .map(_ => assert(ensureCalled))

        }

        "fiber escapes the scope of Resource.run" in run {
            var called = false
            val io =
                for
                    l <- Latch.init(1)
                    f <- Async.run(l.await.andThen(Resource.ensure { called = true }))
                yield (l, f)
            for
                (l, f) <- Resource.run(io)
                _      <- l.release
                result <- f.getResult
            yield assert(result.panic.exists(_.isInstanceOf[Closed]))
            end for
        }
    }

    "parallel close" - {

        "cleans up resources in parallel" taggedAs jvmOnly in run {
            Latch.init(3).map { latch =>
                def makeResource(id: Int) =
                    Resource.acquireRelease(IO(id))(_ => latch.release)

                val resources = Kyo.collect((1 to 3).map(makeResource))

                for
                    close <- Async.run(resources.pipe(Resource.run(3)))
                    _     <- latch.await
                    ids   <- close.get
                yield assert(ids == (1 to 3))
                end for
            }
        }

        "respects parallelism limit" taggedAs jvmOnly in run {
            AtomicInt.init.map { counter =>
                def makeResource(id: Int) =
                    Resource.acquireRelease(IO(id)) { _ =>
                        for
                            current <- counter.getAndIncrement
                            _       <- Async.sleep(1.millis)
                            _       <- counter.decrementAndGet
                        yield
                            assert(current < 3)
                            ()
                    }

                val resources = Kyo.collect((1 to 10).map(makeResource))

                for
                    close <- Async.run(resources.pipe(Resource.run(3)))
                    ids   <- close.get
                yield assert(ids == (1 to 10))
                end for
            }
        }
    }
end ResourceTest
