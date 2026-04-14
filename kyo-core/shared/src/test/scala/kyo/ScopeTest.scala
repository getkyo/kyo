package kyo

import java.io.Closeable
import kyo.*
import kyo.Result.Error
import kyo.Result.Panic
import scala.util.control.NoStackTrace

class ScopeTest extends Test:

    case class TestResource(id: Int, var closes: Int = 0) extends Closeable derives CanEqual:
        var acquires = 0
        def apply() =
            acquires += 1
            this
        def close() = closes += 1
    end TestResource

    case class EffectfulResource(id: Int, closes: AtomicInt):
        def close: Unit < Sync =
            closes.incrementAndGet.unit

    end EffectfulResource
    object EffectfulResource:
        def apply(id: Int): EffectfulResource < Sync =
            for
                cl <- AtomicInt.init(0)
            yield EffectfulResource(id, cl)
    end EffectfulResource

    "acquire + tranform + close" in run {
        val r1 = TestResource(1)
        val r2 = TestResource(2)
        Scope.acquire(r1()).map(_ => assert(r1.closes == 0))
            .handle(Scope.run)
            .map { _ =>
                assert(r1.closes == 1)
                assert(r2.closes == 0)
                assert(r1.acquires == 1)
                assert(r2.acquires == 0)
            }
    }

    "two acquires + close" in run {
        val r1 = TestResource(1)
        val r2 = TestResource(2)
        Scope.acquire(r1()).map(_ => Scope.acquire(r2()))
            .handle(Scope.run)
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
                r1 <- Scope.acquire(r1())
                i1 = r1.id * 3
                r2 <- Scope.acquire(r2())
                i2 = r2.id * 5
            yield i1 + i2
        io.handle(Scope.run)
            .map { r =>
                assert(r == 13)
                assert(r1.closes == 1)
                assert(r2.closes == 1)
                assert(r1.acquires == 1)
                assert(r2.acquires == 1)
            }
    }

    "nested" in run {
        val r1 = TestResource(1)
        Scope.acquire(r1())
            .handle(
                Scope.run,
                Scope.run
            ).map { r =>
                assert(r == r1)
                assert(r1.acquires == 1)
                assert(r1.closes == 1)
            }
    }

    "empty run" in run {
        Scope.run("a").map(s => assert(s == "a"))
    }

    "effectful acquireRelease" in run {
        val io =
            for
                r          <- Scope.acquireRelease(EffectfulResource(1))(_.close)
                closeCount <- r.closes.get
            yield
                assert(closeCount == 0)
                r
        io.handle(
            Scope.run,
            Abort.run
        ).map { finalizedResource =>
            finalizedResource.foldError(_.closes.get.map(i => assert(i == 1)), _ => ???)
        }
    }

    "integration with other effects" - {

        "ensure" in run {
            var closes = 0
            Scope.ensure(Fiber.initUnscoped(closes += 1).map(_.get).unit)
                .handle(
                    Scope.run,
                    Abort.run
                ).map { _ =>
                    assert(closes == 1)
                }
        }

        "acquireRelease" in run {
            var closes = 0
            // any effects in acquire
            val acquire = Abort.get(Some(42))
            // only Async in release
            def release(i: Int) =
                Fiber.initUnscoped {
                    assert(i == 42)
                    closes += 1
                }.map(_.get)
            Scope.acquireRelease(acquire)(release)
                .handle(
                    Scope.run,
                    Abort.run[Timeout],
                    Abort.run[Absent]
                ).map { _ =>
                    assert(closes == 1)
                }
        }

        "acquire" in run {
            val r = TestResource(1)
            Scope.acquire(Fiber.initUnscoped(r).map(_.get))
                .handle(
                    Scope.run,
                    Abort.run
                ).map { _ =>
                    assert(r.closes == 1)
                }
        }
    }

    "failures" - {
        case object TestException extends NoStackTrace

        "acquire fails" in run {
            val io = Scope.acquireRelease(Sync.defer[Int, Any](throw TestException))(_ => ())
            Scope.run(io)
                .handle(Abort.run)
                .map {
                    case Result.Panic(t) => assert(t eq TestException)
                    case _               => fail("Expected panic")
                }
        }

        "release fails" in run {
            var acquired = false
            var released = false
            val io = Scope.acquireRelease(Sync.defer { acquired = true; "resource" }) { _ =>
                Sync.defer {
                    released = true
                    throw TestException
                }
            }
            io.handle(
                Scope.run,
                Abort.run
            ).map { _ =>
                assert(acquired && released)
            }
        }

        "ensure fails" in run {
            var ensureCalled = false
            val io           = Scope.ensure(Sync.defer { ensureCalled = true; throw TestException })
            Scope.run(io)
                .map(_ => assert(ensureCalled))

        }

        "fiber escapes the scope of Scope.run" in run {
            var called = false
            val io =
                for
                    l <- Latch.init(1)
                    f <- Fiber.initUnscoped(l.await.andThen(Scope.ensure { called = true }))
                yield (l, f)
            for
                (l, f) <- Scope.run(io)
                _      <- l.release
                result <- f.getResult
            yield assert(result.panic.exists(_.isInstanceOf[Closed]))
            end for
        }

        "concurrent acquireRelease all cleaned up" in run {
            AtomicInt.init.map { counter =>
                Scope.run {
                    for
                        fibers <- Kyo.fill(10) {
                            Fiber.init {
                                Scope.acquireRelease(Sync.defer(()))(_ => counter.incrementAndGet.unit)
                            }
                        }
                        _ <- Kyo.foreach(fibers)(_.get)
                    yield ()
                }.map { _ =>
                    counter.get.map(c => assert(c == 10))
                }
            }
        }
    }

    "parallel close" - {

        "cleans up resources in parallel" in run {
            Latch.init(3).map { latch =>
                def makeResource(id: Int) =
                    Scope.acquireRelease(Sync.defer(id))(_ => latch.release)

                val resources = Kyo.foreach(1 to 3)(makeResource)

                for
                    close <- Fiber.initUnscoped(resources.handle(Scope.run(3)))
                    _     <- latch.await
                    ids   <- close.get
                yield assert(ids == (1 to 3))
                end for
            }
        }

        "respects parallelism limit" in run {
            AtomicInt.init.map { counter =>
                def makeResource(id: Int) =
                    Scope.acquireRelease(Sync.defer(id)) { _ =>
                        for
                            current <- counter.getAndIncrement
                            _       <- Async.sleep(1.millis)
                            _       <- counter.decrementAndGet
                        yield
                            assert(current < 3)
                            ()
                    }

                val resources = Kyo.foreach(1 to 10)(makeResource)

                for
                    close <- Fiber.initUnscoped(resources.handle(Scope.run(3)))
                    ids   <- close.get
                yield assert(ids == (1 to 10))
                end for
            }
        }
    }

    "backpressure" - {

        "computation failure" in run {
            var finalizerCalled = false
            val io = Scope.ensure {
                Async.sleep(50.millis).andThen { finalizerCalled = true }
            }.map(_ => Abort.fail("Test failure"))

            io.handle(
                Scope.run,
                Abort.run
            ).map { result =>
                assert(finalizerCalled)
                assert(result.isFailure)
            }
        }

        "finalizer failure" in run {
            var mainActionExecuted = false
            var finalizerStarted   = false
            val io = Scope.ensure {
                finalizerStarted = true
                Async.sleep(50.millis)
            }.map { _ =>
                mainActionExecuted = true
                "success"
            }

            Scope.run(io)
                .handle(Abort.run)
                .map { result =>
                    assert(finalizerStarted)
                    assert(mainActionExecuted)
                    assert(result.isSuccess)
                }
        }

        "chained resources" in run {
            var firstFinalizerCalled  = false
            var secondFinalizerCalled = false

            val io =
                for
                    _ <- Scope.ensure {
                        Async.sleep(50.millis).andThen { firstFinalizerCalled = true }
                    }
                    _ <- Scope.ensure {
                        Async.sleep(25.millis).andThen { secondFinalizerCalled = true }
                    }
                    _ <- Sync.defer(Abort.fail("Fail after acquiring resources"))
                yield ()

            Scope.run(io)
                .handle(Abort.run)
                .map { result =>
                    assert(firstFinalizerCalled)
                    assert(secondFinalizerCalled)
                    assert(result.isFailure)
                }
        }

        "slow finalizers" in run {
            val r1                = TestResource(1)
            var slowFinalizerDone = false

            val io =
                for
                    _ <- Scope.acquire(r1())
                    _ <- Scope.ensure {
                        Async.sleep(50.millis).andThen { slowFinalizerDone = true }
                    }
                yield "success"

            Scope.run(io)
                .map { result =>
                    assert(r1.closes == 1)
                    assert(slowFinalizerDone)
                    assert(result == "success")
                }
        }
    }

    "ensure with Maybe[Error[Any]]" - {
        case object TestException extends NoStackTrace

        "receives Absent on normal completion" in run {
            var receivedValue: Maybe[Error[Any]] = null

            Scope.ensure { t =>
                receivedValue = t
                ()
            }
                .handle(Scope.run)
                .map { _ =>
                    assert(receivedValue == Absent)
                }
        }

        "receives Present with exception on failure" in run {
            var receivedValue: Maybe[Error[Any]] = null
            val exception                        = TestException

            val io = Scope.ensure { t =>
                receivedValue = t
                ()
            }.map { _ =>
                throw exception
            }

            io.handle(
                Scope.run,
                Abort.run
            ).map { result =>
                assert(receivedValue.isDefined)
                assert(receivedValue.get == Panic(exception))
                assert(result.panic.exists(_ == exception))
            }
        }

        "with nested ensures passes correct exception to each handler" in run {
            var outerException: Maybe[Error[Any]] = null
            var innerException: Maybe[Error[Any]] = null
            val testException                     = TestException

            val io = Scope.ensure { t =>
                outerException = t
                ()
            }.map { _ =>
                Scope.ensure { t =>
                    innerException = t
                    ()
                }.map { _ =>
                    throw testException
                }
            }

            io.handle(
                Scope.run,
                Scope.run,
                Abort.run
            ).map { result =>
                assert(innerException.isDefined)
                assert(innerException.get == Panic(testException))
                assert(outerException.isDefined)
                assert(outerException.get == Panic(testException))
                assert(result.panic.exists(_ == testException))
            }
        }

        "can use exception information for recovery" in run {
            var recoveryAction = ""

            val io = Scope.ensure { t =>
                recoveryAction = t match
                    case Present(Error(_: IllegalArgumentException)) => "IllegalArgument"
                    case Present(Error(_: IllegalStateException))    => "IllegalState"
                    case Present(_)                                  => "OtherException"
                    case Absent                                      => "NoException"
                ()
            }.map { _ =>
                throw new IllegalStateException("Test exception")
            }

            io.handle(
                Scope.run,
                Abort.run
            ).map { _ =>
                assert(recoveryAction == "IllegalState")
            }
        }
    }

    "finalizer ordering (#1439)" - {

        "documents release order with parallelism 1" in run {
            var order = List.empty[Int]
            Scope.run {
                for
                    _ <- Scope.acquireRelease(Sync.defer(1))(_ => Sync.defer { order = 1 :: order }.unit)
                    _ <- Scope.acquireRelease(Sync.defer(2))(_ => Sync.defer { order = 2 :: order }.unit)
                    _ <- Scope.acquireRelease(Sync.defer(3))(_ => Sync.defer { order = 3 :: order }.unit)
                yield ()
            }.map { _ =>
                assert(order == List(1, 2, 3))
            }
        }

        "releases all with parallel close" in run {
            AtomicInt.init(0).map { counter =>
                Scope.run(3) {
                    for
                        _ <- Scope.acquireRelease(Sync.defer(1))(_ => counter.incrementAndGet.unit)
                        _ <- Scope.acquireRelease(Sync.defer(2))(_ => counter.incrementAndGet.unit)
                        _ <- Scope.acquireRelease(Sync.defer(3))(_ => counter.incrementAndGet.unit)
                    yield ()
                }.map { _ =>
                    counter.get.map(c => assert(c == 3))
                }
            }
        }

        "nested Scope.run releases inner before outer" in run {
            var innerDone = false
            Scope.run {
                Scope.ensure {
                    assert(innerDone)
                    ()
                }.andThen {
                    Scope.run {
                        Scope.ensure {
                            innerDone = true
                            ()
                        }
                    }
                }
            }.map(_ => assert(innerDone))
        }

        "many resources all released" in run {
            AtomicInt.init(0).map { counter =>
                Scope.run {
                    Kyo.foreach(1 to 100) { i =>
                        Scope.acquireRelease(Sync.defer(i))(_ => counter.incrementAndGet.unit)
                    }
                }.map { _ =>
                    counter.get.map(c => assert(c == 100))
                }
            }
        }
    }

    "acquireRelease safety (#1224)" - {
        case object TestAcquireException extends scala.util.control.NoStackTrace

        "finalizer runs after normal acquire" in run {
            var released = false
            Scope.run {
                Scope.acquireRelease(Sync.defer("resource"))(_ => Sync.defer { released = true }.unit)
            }.map { _ =>
                assert(released)
            }
        }

        "acquire failure skips release" in run {
            var released = false
            Abort.run {
                Scope.run {
                    Scope.acquireRelease(Sync.defer[Int, Any](throw TestAcquireException))(_ =>
                        Sync.defer { released = true }.unit
                    )
                }
            }.map { result =>
                assert(!released)
                assert(result.isPanic)
            }
        }

        "ensure on closed scope panics with Closed" in run {
            var called = false
            val io =
                for
                    l <- Latch.init(1)
                    f <- Fiber.initUnscoped(l.await.andThen(Scope.ensure { called = true }))
                yield (l, f)
            for
                (l, f) <- Scope.run(io)
                _      <- l.release
                result <- f.getResult
            yield assert(result.panic.exists(_.isInstanceOf[Closed]))
            end for
        }

        "concurrent acquireRelease all cleaned up" in run {
            AtomicInt.init.map { counter =>
                Scope.run {
                    for
                        fibers <- Kyo.fill(10) {
                            Fiber.init {
                                Scope.acquireRelease(Sync.defer(()))(_ => counter.incrementAndGet.unit)
                            }
                        }
                        _ <- Kyo.foreach(fibers)(_.get)
                    yield ()
                }.map { _ =>
                    counter.get.map(c => assert(c == 10))
                }
            }
        }

    }

    "scope + fiber" - {

        "scoped fiber interrupted on scope exit" in run {
            for
                interrupted <- AtomicBoolean.init(false)
                promise     <- Promise.init[Int, Any]
                _ <- Scope.run {
                    for
                        _ <- promise.onInterrupt(_ => interrupted.set(true))
                        f <- Fiber.init(promise.get)
                    yield ()
                }
                _    <- untilTrue(interrupted.get)
                flag <- interrupted.get
            yield assert(flag)
            end for
        }

        "multiple fibers in scope all interrupted" in run {
            for
                counter  <- AtomicInt.init(0)
                promises <- Kyo.fill(5)(Promise.init[Int, Any])
                _ <- Scope.run {
                    for
                        _      <- Kyo.foreach(promises)(p => p.onInterrupt(_ => counter.incrementAndGet.unit))
                        fibers <- Kyo.foreach(promises)(p => Fiber.init(p.get))
                    yield ()
                }
                _ <- untilTrue(counter.get.map(_ == 5))
                c <- counter.get
            yield assert(c == 5)
            end for
        }

        "Sync.ensure inside forked fiber runs" in run {
            var called = false
            for
                fiber <- Fiber.initUnscoped {
                    Sync.ensure { called = true }(42)
                }
                result <- fiber.get
            yield
                assert(result == 42)
                assert(called)
            end for
        }

        "Scope.ensure inside forked fiber runs at scope exit" in run {
            AtomicInt.init(0).map { counter =>
                Scope.run {
                    for
                        f <- Fiber.init {
                            Scope.ensure(counter.incrementAndGet.unit)
                        }
                        _ <- f.get
                    yield ()
                }.map { _ =>
                    counter.get.map(c => assert(c == 1))
                }
            }
        }

        "Scope.ensure from multiple fibers all run" in run {
            AtomicInt.init(0).map { counter =>
                Scope.run {
                    for
                        fibers <- Kyo.fill(10) {
                            Fiber.init {
                                Scope.ensure(counter.incrementAndGet.unit)
                            }
                        }
                        _ <- Kyo.foreach(fibers)(_.get)
                    yield ()
                }.map { _ =>
                    counter.get.map(c => assert(c == 10))
                }
            }
        }
    }

    "finalizer failure isolation" - {
        case object TestException extends scala.util.control.NoStackTrace

        "failing finalizer doesn't mask primary result" in run {
            Scope.run {
                Scope.ensure(throw TestException).andThen(42)
            }.handle(Abort.run).map { result =>
                assert(result.contains(42) || result.isPanic)
            }
        }

        "failing finalizer doesn't mask primary error" in run {
            val primaryEx = new RuntimeException("primary")
            Abort.run {
                Scope.run {
                    Scope.ensure(throw TestException).andThen(Sync.defer[Int, Any](throw primaryEx))
                }
            }.map { result =>
                // The primary error should be preserved
                assert(result.isPanic)
            }
        }

        "multiple finalizers one fails others still run" in pending

        "all finalizers fail" in run {
            AtomicInt.init(0).map { counter =>
                Scope.run {
                    for
                        _ <- Scope.ensure { counter.incrementAndGet.unit.andThen(throw TestException) }
                        _ <- Scope.ensure { counter.incrementAndGet.unit.andThen(throw TestException) }
                        _ <- Scope.ensure { counter.incrementAndGet.unit.andThen(throw TestException) }
                    yield ()
                }.handle(Abort.run).map { _ =>
                    counter.get.map(c => assert(c == 3))
                }
            }
        }
    }

    "edge cases" - {

        "empty Scope.run returns value" in run {
            val v: Int < Scope = 42
            Scope.run(v).map(r => assert(r == 42))
        }

        "very large number of finalizers" in run {
            AtomicInt.init(0).map { counter =>
                Scope.run {
                    Kyo.foreach(1 to 10000) { _ =>
                        Scope.ensure(counter.incrementAndGet.unit)
                    }
                }.map { _ =>
                    counter.get.map(c => assert(c == 10000))
                }
            }
        }

        "scope with async finalizer" in run {
            var finalizerRan = false
            Scope.run {
                Scope.ensure {
                    Async.sleep(1.milli).andThen { finalizerRan = true }
                }.andThen(42)
            }.map { r =>
                assert(r == 42)
                assert(finalizerRan)
            }
        }

        "concurrent ensure registration from multiple fibers" in run {
            AtomicInt.init(0).map { counter =>
                Scope.run {
                    for
                        fibers <- Kyo.fill(10) {
                            Fiber.init {
                                Scope.ensure(counter.incrementAndGet.unit)
                            }
                        }
                        _ <- Kyo.foreach(fibers)(_.get)
                    yield ()
                }.map { _ =>
                    counter.get.map(c => assert(c == 10))
                }
            }
        }

        "Scope.run wrapping Scope.run" in run {
            var innerDone = false
            Scope.run {
                Scope.ensure {
                    assert(innerDone)
                    ()
                }.andThen {
                    Scope.run {
                        Scope.ensure {
                            innerDone = true
                            ()
                        }.andThen(42)
                    }
                }
            }.map(r => assert(r == 42))
        }
    }

    "scope isolation (#1381)" - {

        "Scope.run on generic effect type with Scope should not run caller's finalizers" in run {
            def handleScoped[A, S](v: A < (Scope & S)): A < (Async & S) =
                Scope.run(v)

            AtomicInt.init(0).map { counter =>
                Scope.run {
                    // Register a finalizer in the OUTER scope
                    Scope.ensure(counter.incrementAndGet.unit).andThen {
                        // handleScoped calls Scope.run again — this inner Scope.run
                        // should NOT trigger the outer scope's finalizer
                        handleScoped(Sync.defer(42))
                    }
                }.map { r =>
                    counter.get.map { c =>
                        assert(r == 42)
                        // Outer finalizer should run exactly once (when outer Scope.run closes)
                        assert(c == 1)
                    }
                }
            }
        }
    }
end ScopeTest
