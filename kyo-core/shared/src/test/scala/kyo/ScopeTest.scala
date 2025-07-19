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
end ScopeTest
