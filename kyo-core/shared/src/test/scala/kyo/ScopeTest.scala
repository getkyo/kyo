package kyo

import java.io.Closeable
import kyo.*
import kyo.Result.Error
import kyo.Result.Panic
import scala.util.control.NoStackTrace

class ScopeTest extends kyo.test.Test[Any]:

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

    "acquire + tranform + close" in {
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

    "two acquires + close" in {
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

    "two acquires + for-comp + close" in {
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

    "nested" in {
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

    "empty run" in {
        Scope.run("a").map(s => assert(s == "a"))
    }

    "effectful acquireRelease" in {
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

        "ensure" in {
            var closes = 0
            Scope.ensure(Fiber.initUnscoped(closes += 1).map(_.get).unit)
                .handle(
                    Scope.run,
                    Abort.run
                ).map { _ =>
                    assert(closes == 1)
                }
        }

        "acquireRelease" in {
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

        "acquire" in {
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

        "acquire fails" in {
            val io = Scope.acquireRelease(Sync.defer[Int, Any](throw TestException))(_ => ())
            Scope.run(io)
                .handle(Abort.run)
                .map {
                    case Result.Panic(t) => assert(t eq TestException)
                    case _               => fail("Expected panic")
                }
        }

        "release fails" in {
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

        "ensure fails" in {
            var ensureCalled = false
            val io           = Scope.ensure(Sync.defer { ensureCalled = true; throw TestException })
            Scope.run(io)
                .map(_ => assert(ensureCalled))

        }

        "fiber escapes the scope of Scope.run" in {
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

        "a resource acquired after the scope closed is released rather than leaked" in {
            for
                acquired <- AtomicInt.init(0)
                released <- AtomicInt.init(0)
                gate     <- Latch.init(1)
                fiber <- Scope.run {
                    Fiber.initUnscoped(
                        gate.await.andThen(
                            Scope.acquireRelease(acquired.incrementAndGet)(_ => released.incrementAndGet.unit)
                        )
                    )
                }
                // The gate opens only once Scope.run has returned, so the acquisition below is guaranteed to find the scope closed.
                _      <- gate.release
                result <- fiber.getResult
                // A refused registration runs its release detached, settling after the acquiring fiber has
                // finished, so the counters are polled rather than read once.
                _ <- assertEventually {
                    for
                        a <- acquired.get
                        r <- released.get
                    yield a == 1 && r == 1
                }
            yield
                // The acquire runs, so the refused registration is what has to release: one acquisition, one release. Comparing the two
                // counters instead would hold at zero, before the fiber has acquired anything.
                assert(
                    result.isSuccess || result.panic.exists(_.isInstanceOf[Closed]),
                    s"registering on a closed scope must either succeed or panic Closed: $result"
                )
            end for
        }

        "fibers registering finalizers while their scope closes: each registration runs exactly once" in {
            // A scope closes by draining its finalizer queue, and the fibers below stay free to register for the whole drain, so
            // registration and close overlap by construction. The scope is preloaded first so the drain is long enough for the two to
            // meet. Every registration has to run, and none of them twice: a registration the scope refuses still runs its finalizer,
            // off the scope, rather than dropping it, so the count to match is what was attempted and not what was accepted.
            (for
                attempted <- AtomicInt.init(0)
                accepted  <- AtomicInt.init(0)
                ran       <- AtomicInt.init(0)
                fibers <- Scope.run {
                    val register =
                        attempted.incrementAndGet.andThen(Abort.run[Nothing](Scope.ensure(ran.incrementAndGet.unit))).map {
                            case Result.Success(_) => accepted.incrementAndGet.andThen(true)
                            case _                 => false
                        }
                    for
                        _       <- Loop.repeat(8192)(register)
                        started <- Latch.init(8)
                        fibers <- Kyo.fill(8) {
                            Fiber.initUnscoped(
                                started.release.andThen(
                                    Loop.indexed { i =>
                                        if i == 100000 then Loop.done
                                        else register.map(ok => if ok then Loop.continue else Loop.done)
                                    }
                                )
                            )
                        }
                        _ <- started.await
                    yield fibers
                    end for
                }
                _ <- Kyo.foreachDiscard(fibers)(_.get)
                // a refused registration runs its finalizer off the scope, so the last of them can land after
                // the fibers have finished
                _   <- assertEventually(Kyo.zip(attempted.get, ran.get).map((att, r) => r == att))
                att <- attempted.get
                acc <- accepted.get
                r   <- ran.get
            yield assert(r == att, s"each registration must run exactly once: ran=$r attempted=$att accepted=$acc"))
                .handle(Loop.repeat(20))
        }

        "concurrent acquireRelease all cleaned up" in {
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

        "cleans up resources in parallel" in {
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

        "respects parallelism limit" in {
            AtomicInt.init.map { counter =>
                def makeResource(id: Int) =
                    Scope.acquireRelease(Sync.defer(id)) { _ =>
                        for
                            current <- counter.getAndIncrement
                            _       <- Async.sleep(1.millis)
                            _       <- counter.decrementAndGet
                        yield assert(current < 3)
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

        "computation failure" in {
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

        "finalizer failure" in {
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

        "chained resources" in {
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

        "slow finalizers" in {
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

        "receives Absent on normal completion" in {
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

        "receives Present with exception on failure" in {
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

        "with nested ensures passes correct exception to each handler" in {
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

        "can use exception information for recovery" in {
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

        "documents release order with parallelism 1" in {
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

        "releases all with parallel close" in {
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

        "nested Scope.run releases inner before outer" in {
            var innerDone = false
            Scope.run {
                Scope.ensure {
                    assert(innerDone)
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

        "a nested Scope.run does not run the enclosing scope's finalizers" in {
            def handleScoped[A, S](v: A < (Scope & S)): A < (Async & S) =
                Scope.run(v)

            AtomicInt.init(0).map { counter =>
                Scope.run {
                    Scope.ensure(counter.incrementAndGet.unit).andThen {
                        handleScoped(Sync.defer(42))
                    }
                }.map { r =>
                    counter.get.map { c =>
                        assert(r == 42)
                        assert(c == 1)
                    }
                }
            }
        }

        "many resources all released" in {
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

        // The fiber interrupts itself from inside the acquire, in the same Sync node that performs the claim,
        // so the earliest the interrupt can be delivered is after the acquire has returned. No held worker, so
        // it runs on every platform.
        "a self-interrupt inside the acquire still releases what the acquire produced" in {
            val rounds = 500
            Kyo.foreach(1 to rounds) { _ =>
                for
                    handoff  <- Promise.init[Fiber[Unit, Any], Any]
                    claimed  <- AtomicBoolean.init(false)
                    released <- AtomicBoolean.init(false)
                    fiber <- Fiber.initUnscoped {
                        handoff.get.map { self =>
                            Scope.run {
                                Scope.acquireRelease {
                                    // Unsafe: the interrupt and the claim have to be one indivisible step, which
                                    // rules out suspending between them to reach the effectful tier.
                                    import AllowUnsafe.embrace.danger
                                    Sync.Unsafe.defer {
                                        discard(self.unsafe.interrupt())
                                        claimed.unsafe.set(true)
                                        "resource"
                                    }
                                } { _ =>
                                    import AllowUnsafe.embrace.danger
                                    Sync.Unsafe.defer(released.unsafe.set(true))
                                }.unit
                            }
                        }
                    }
                    _ <- handoff.complete(Result.succeed(fiber))
                    _ <- fiber.getResult
                    a <- claimed.get
                    // Waited for rather than read: the finalizers drain on a detached fiber, so a release can
                    // land after the result. Waiting is what separates "released late" from "leaked".
                    _ <- assertEventually(Kyo.zip(claimed.get, released.get).map((acquired, freed) => !acquired || freed))
                    r <- released.get
                yield (a, r)
                end for
            }.map { outcomes =>
                val leaked = outcomes.count((acquired, freed) => acquired && !freed)
                assert(leaked == 0, s"$leaked of $rounds rounds acquired a resource that was never released")
            }
        }

        "finalizer runs after normal acquire" in {
            var released = false
            Scope.run {
                Scope.acquireRelease(Sync.defer("resource"))(_ => Sync.defer { released = true }.unit)
            }.map { _ =>
                assert(released)
            }
        }

        "acquire failure skips release" in {
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

        "ensure on closed scope panics with Closed" in {
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

        "concurrent acquireRelease all cleaned up" in {
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

        "scoped fiber interrupted on scope exit" in {
            for
                interrupted <- AtomicBoolean.init(false)
                promise     <- Promise.init[Int, Any]
                _ <- Scope.run {
                    for
                        _ <- promise.onInterrupt(_ => interrupted.set(true))
                        f <- Fiber.init(promise.get)
                    yield ()
                }
                _    <- assertEventually(interrupted.get)
                flag <- interrupted.get
            yield assert(flag)
            end for
        }

        // An interrupt completes a fiber's promise at once, but the body it was running still has to unwind
        // for what it bracketed to be released, and a parked fiber has no slice in flight to notice. The
        // task is rescheduled for exactly that reason, so the release happens; this pins it, because
        // nothing else covers a fiber interrupted while parked rather than while running.
        "an interrupted parked fiber runs its brackets" in {
            for
                ran     <- AtomicInt.init(0)
                started <- Latch.init(1)
                p       <- Promise.init[Int, Any]
                fiber <- Fiber.initUnscoped(
                    Sync.ensure(ran.incrementAndGet.unit)(started.release.andThen(p.get))
                )
                _ <- started.await
                _ <- fiber.interrupt
                // the bracket runs on the abandonment walk, which the interrupt starts without waiting for
                _ <- assertEventually(ran.get.map(_ == 1))
                n <- ran.get
            yield assert(n == 1, s"bracket ran $n times")
            end for
        }

        "multiple fibers in scope all interrupted" in {
            for
                counter  <- AtomicInt.init(0)
                promises <- Kyo.fill(5)(Promise.init[Int, Any])
                _ <- Scope.run {
                    for
                        _      <- Kyo.foreach(promises)(p => p.onInterrupt(_ => counter.incrementAndGet.unit))
                        fibers <- Kyo.foreach(promises)(p => Fiber.init(p.get))
                    yield ()
                }
                _ <- assertEventually(counter.get.map(_ == 5))
                c <- counter.get
            yield assert(c == 5)
            end for
        }

        // Concurrent because a single scope passes even when the linkage is broken: the defect is losing
        // the race between a fiber's first slice and the interrupt, so it only shows in bulk.
        "interrupting scoped fibers reaches the promises they are parked on" in {
            Async.foreachDiscard(1 to 20, 20) { _ =>
                for
                    counter  <- AtomicInt.init(0)
                    promises <- Kyo.fill(5)(Promise.init[Int, Any])
                    _ <- Scope.run {
                        for
                            _ <- Kyo.foreach(promises)(p => p.onInterrupt(_ => counter.incrementAndGet.unit))
                            _ <- Kyo.foreach(promises)(p => Fiber.init(p.get))
                        yield ()
                    }
                    _ <- assertEventually(counter.get.map(_ == 5))
                    c <- counter.get
                yield assert(c == 5, s"only $c of 5 onInterrupt callbacks fired")
                end for
            }.andThen(assert(true))
        }

        "Sync.ensure inside forked fiber runs" in {
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

        "Scope.ensure inside forked fiber runs at scope exit" in {
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

        "Scope.ensure from multiple fibers all run" in {
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

        "failing finalizer doesn't mask primary result" in {
            Scope.run {
                Scope.ensure(throw TestException).andThen(42)
            }.handle(Abort.run).map { result =>
                assert(result.contains(42) || result.isPanic)
            }
        }

        "failing finalizer doesn't mask primary error" in {
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

        "multiple finalizers one fails others still run" in {
            AtomicInt.init(0).map { counter =>
                Scope.run {
                    for
                        _ <- Scope.ensure(counter.incrementAndGet.unit)
                        _ <- Scope.ensure { counter.incrementAndGet.unit.andThen(throw TestException) }
                        _ <- Scope.ensure(counter.incrementAndGet.unit)
                    yield ()
                }.handle(Abort.run).map { _ =>
                    counter.get.map(c => assert(c == 3))
                }
            }
        }

        "all finalizers fail" in {
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

        "empty Scope.run returns value" in {
            val v: Int < Scope = 42
            Scope.run(v).map(r => assert(r == 42))
        }

        "very large number of finalizers" in {
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

        "scope with async finalizer" in {
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

        "concurrent ensure registration from multiple fibers" in {
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

        "Scope.run wrapping Scope.run" in {
            var innerDone = false
            Scope.run {
                Scope.ensure {
                    assert(innerDone)
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

    "drain completeness" - {

        // One finalizer failing must not cost the others theirs: the drain folds each release's error and logs
        // it rather than raising, so the loop continues.
        "a finalizer that aborts does not stop the ones registered before it" in {
            for
                ran <- AtomicRef.init(Chunk.empty[String])
                _ <- Scope.run {
                    Scope.ensure(ran.updateAndGet(_.append("first")).unit)
                        .andThen(Scope.ensure(Abort.fail(new RuntimeException("boom"))))
                        .andThen(Scope.ensure(ran.updateAndGet(_.append("third")).unit))
                }
                seq <- ran.get
            // Reverse registration order, and the failure in the middle is absent rather than fatal.
            yield assert(seq == Chunk("third", "first"), s"order was $seq")
            end for
        }

        "a finalizer that throws does not stop the ones registered before it" in {
            for
                ran <- AtomicRef.init(Chunk.empty[String])
                _ <- Scope.run {
                    Scope.ensure(ran.updateAndGet(_.append("first")).unit)
                        .andThen(Scope.ensure(Sync.defer[Unit, Any](throw new RuntimeException("boom"))))
                        .andThen(Scope.ensure(ran.updateAndGet(_.append("third")).unit))
                }
                seq <- ran.get
            yield assert(seq == Chunk("third", "first"), s"order was $seq")
            end for
        }

        // The parallel drain groups the finalizers, so a failure in one group must not cost another its
        // releases either. Counted rather than ordered, since parallelism is what makes order undefined.
        "every finalizer runs when the close is parallel and one of them fails" in {
            for
                ran <- AtomicInt.init(0)
                _ <- Scope.run(3) {
                    Kyo.foreachDiscard(1 to 6) { i =>
                        if i == 4 then Scope.ensure(Abort.fail(new RuntimeException("boom")))
                        else Scope.ensure(ran.incrementAndGet.unit)
                    }
                }
                count <- ran.get
            yield assert(count == 5, s"5 of the 6 finalizers should have run and $count did")
            end for
        }

        // A release that suspends is still a release. The drain runs on a fiber `close` spawns, and the
        // computation that closed the scope parks on `await`; interrupting it must not reach the drain, or
        // the releases the interrupt was meant to trigger are the ones lost. #1928 from the other side: that
        // leaf races the interrupt against the close, this one lands it squarely on the waiter.
        //
        // The interrupt has to land while the release is in flight, which means waiting for the finalizer to
        // say it is running rather than for the fiber to be not-done: a fiber is not-done from its first
        // instant, so waiting on that interrupts before the scope's body has even registered the finalizer,
        // and the leaf passes or hangs for reasons that have nothing to do with the release.
        "a finalizer that suspends still completes when the closing computation is interrupted" in {
            for
                entered  <- Promise.init[Unit, Any]
                gate     <- Latch.init(1)
                released <- AtomicInt.init(0)
                closer <- Fiber.initUnscoped {
                    Scope.run {
                        Scope.ensure(
                            entered.complete(Result.succeed(()))
                                .andThen(gate.await)
                                .andThen(released.incrementAndGet.unit)
                        )
                    }
                }
                // The drain is running the finalizer and parked on the gate.
                _ <- entered.get
                _ <- closer.interrupt
                _ <- gate.release
                _ <- assertEventually(released.get.map(_ == 1))
                r <- released.get
            yield assert(r == 1, s"the suspended finalizer ran $r times")
            end for
        }

        // Teardown that acquires: a finalizer opening a scope of its own during the drain. The nested run
        // closes itself, so what this pins is that it gets to, rather than being cut short by the drain it
        // is running under.
        "a finalizer that opens a scope of its own releases what it acquires" in {
            for
                outer <- AtomicInt.init(0)
                inner <- AtomicInt.init(0)
                _ <- Scope.run {
                    Scope.ensure {
                        Scope.run {
                            Scope.acquireRelease(Sync.defer("nested"))(_ => inner.incrementAndGet.unit)
                        }.andThen(outer.incrementAndGet.unit)
                    }
                }
                _ <- assertEventually(Kyo.zip(outer.get, inner.get).map((o, i) => o == 1 && i == 1))
                o <- outer.get
                i <- inner.get
            yield assert(o == 1 && i == 1, s"the finalizer ran $o times and its own resource was released $i times")
            end for
        }

        // A fatal error is what the scheduler gates on with NonFatal, so it takes a different path out of a
        // finalizer than an ordinary failure. The releases registered before it are owed either way.
        // JVM-only for the same reason IOTaskTest's fatal leaf is: it relies on worker-thread semantics the
        // single-worker Native and single-threaded JS runtimes do not provide.
        "a finalizer that throws a fatal error does not stop the ones registered before it".onlyJvm in {
            for
                ran <- AtomicRef.init(Chunk.empty[String])
                _ <- Abort.run[Any] {
                    Scope.run {
                        Scope.ensure(ran.updateAndGet(_.append("first")).unit)
                            .andThen(Scope.ensure(Sync.defer[Unit, Any](throw new LinkageError("fatal"))))
                            .andThen(Scope.ensure(ran.updateAndGet(_.append("third")).unit))
                    }
                }
                _   <- assertEventually(ran.get.map(_.size == 2))
                seq <- ran.get
            yield assert(seq == Chunk("third", "first"), s"order was $seq")
            end for
        }

        // Both of Scope.run's close paths can fire for the same scope: the abandonment backstop and the
        // one that runs when the body settles. Whichever wins the queue's close drains it, so a finalizer
        // must run once, never twice. The abort is what makes the settled path the one that closes.
        "a finalizer runs exactly once when the body aborts" in {
            for
                ran <- AtomicInt.init(0)
                _ <- Abort.run[String] {
                    Scope.run {
                        Scope.ensure(ran.incrementAndGet.unit).andThen(Abort.fail("boom"))
                    }
                }
                count <- ran.get
            yield assert(count == 1, s"the finalizer ran $count times")
            end for
        }
    }

    "finalizers lost under interrupt (#1928)" - {

        // The interrupt races the scope's own close rather than landing in the body. `close` hands the
        // drain to a fiber and `become`s the finalizer's promise with it, and `await` is what the closing
        // computation parks on, so an interrupt arriving there travelled through the promise into the
        // drain and stopped the finalizers halfway. The releases the interrupt was meant to trigger were
        // exactly the ones lost, which is why a stranded resource outlives the fiber that held it.
        //
        // Rounds rather than one shot because the window is small: it lost one finalizer in 200 rounds,
        // and adding logging to the close path was enough to stop it reproducing at all.
        "an interrupt racing the close does not stop the drain" in {
            val rounds = 1000
            for
                registered <- AtomicInt.init(0)
                released   <- AtomicInt.init(0)
                _ <- Loop.indexed { i =>
                    if i >= rounds then Loop.done
                    else
                        Promise.init[Unit, Any].map { ready =>
                            Fiber.initUnscoped {
                                Scope.run {
                                    Scope.ensure(released.incrementAndGet.unit)
                                        .andThen(registered.incrementAndGet)
                                        .andThen(ready.complete(Result.succeed(())))
                                        .unit
                                }
                            }.map(fiber => ready.get.andThen(fiber.interrupt)).andThen(Loop.continue)
                        }
                }
                // The drains are detached, so the counts are polled rather than read once.
                _   <- assertEventually(Kyo.zip(registered.get, released.get).map((reg, rel) => reg == rounds && rel == rounds))
                reg <- registered.get
                rel <- released.get
            yield assert(reg == rounds && rel == rounds, s"registered $reg finalizers and ran $rel of them")
            end for
        }
    }

    "acquire-time registration (#1820)" - {

        // The window the issue names: with the release registered in a suspension that follows the
        // acquire, an interrupt pending when the acquire completes parks the evaluation before that
        // registration is dispatched, and the value the acquire produced is held by nobody.
        // `acquireRelease` records the release in the same step the value arrives in, which is what
        // `ensureMap` is for.
        //
        // ScopeInterruptTest pins the same property, but by spinning inside the acquire until an
        // interrupt is sent and waiting on a CountDownLatch, which blocks a thread and so lives in
        // jvm-native and leaves JS and Native uncovered. Here the acquire interrupts its own fiber and
        // then produces its value, so delivery lands at the next safepoint, after the acquire and at or
        // before the registration. No spin, no latch, no wall clock, and it runs everywhere.
        //
        // Rounds rather than one shot, because #1928 taught that one shot misses a window that opens
        // once in a couple of hundred tries.
        "an interrupt requested inside the acquire still releases what it produced" in {
            val rounds = 1000
            for
                acquired <- AtomicInt.init(0)
                released <- AtomicInt.init(0)
                _ <- Loop.indexed { i =>
                    if i >= rounds then Loop.done
                    else
                        Promise.init[Fiber[Unit, Any], Any].map { handoff =>
                            Fiber.initUnscoped {
                                handoff.get.map { self =>
                                    Scope.run {
                                        Scope.acquireRelease {
                                            Sync.defer {
                                                // Unsafe: the interrupt has to be requested from inside the
                                                // acquire, before it returns, which is not an effectful position,
                                                // and the count has to be taken in the same node so it reports the
                                                // acquire producing a value rather than a step before it.
                                                import AllowUnsafe.embrace.danger
                                                discard(self.unsafe.interrupt())
                                                discard(acquired.unsafe.incrementAndGet())
                                                "token"
                                            }
                                        }(_ => released.incrementAndGet.unit).andThen(Sync.defer(()))
                                    }
                                }
                            }.map { fiber =>
                                handoff.complete(Result.succeed(fiber)).andThen(fiber.getResult)
                            }.andThen(Loop.continue)
                        }
                }
                _   <- assertEventually(Kyo.zip(acquired.get, released.get).map((a, r) => a == r && a == rounds))
                acq <- acquired.get
                rel <- released.get
            yield assert(acq == rel && acq > 0, s"$acq acquires produced a value and $rel of them were released")
            end for
        }

        // The same window, with one more suspension in the acquire after the interrupt is requested. The
        // acquire still runs to its end, so a real acquire would have opened its handle by now, but the
        // interrupt parks the computation before `ensureMap` applies, and an unapplied `Arrow.Ensure`
        // sitting in a continuation is not something the abandonment walk descends into. So the value the
        // acquire produced is registered nowhere and released by nobody.
        //
        // Measured, not inferred: with the acquire's value arriving in the same node as the interrupt
        // request, 1000 of 1000 are released; with one suspension after it, 1000 acquires complete and
        // ZERO are released. `ensureMap` closes the window only for a single-node acquire.
        "an acquire whose last step follows the interrupt is still released" in {
            val rounds = 200
            for
                acquired <- AtomicInt.init(0)
                released <- AtomicInt.init(0)
                _ <- Loop.indexed { i =>
                    if i >= rounds then Loop.done
                    else
                        Promise.init[Fiber[Unit, Any], Any].map { handoff =>
                            Fiber.initUnscoped {
                                handoff.get.map { self =>
                                    Scope.run {
                                        Scope.acquireRelease {
                                            Sync.defer {
                                                // Unsafe: the interrupt is requested from inside the acquire.
                                                import AllowUnsafe.embrace.danger
                                                discard(self.unsafe.interrupt())
                                            }.andThen(acquired.incrementAndGet)
                                        }(_ => released.incrementAndGet.unit).andThen(Sync.defer(()))
                                    }
                                }
                            }.map { fiber =>
                                handoff.complete(Result.succeed(fiber)).andThen(fiber.getResult)
                            }.andThen(Loop.continue)
                        }
                }
                _   <- assertEventually(Kyo.zip(acquired.get, released.get).map((a, r) => a == r))
                acq <- acquired.get
                rel <- released.get
            yield assert(acq == rel, s"$acq acquires ran to their end and $rel of them were released")
            end for
        }

        "a single interrupt requested inside the acquire releases what it produced" in {
            for
                released <- AtomicInt.init(0)
                handoff  <- Promise.init[Fiber[Unit, Any], Any]
                fiber <- Fiber.initUnscoped {
                    handoff.get.map { self =>
                        Scope.run {
                            Scope.acquireRelease {
                                Sync.defer {
                                    // Unsafe: the interrupt has to be requested from inside the acquire,
                                    // before it returns, which is not an effectful position.
                                    import AllowUnsafe.embrace.danger
                                    discard(self.unsafe.interrupt())
                                    "token"
                                }
                            }(_ => released.incrementAndGet.unit).andThen(Sync.defer(()))
                        }
                    }
                }
                _   <- handoff.complete(Result.succeed(fiber))
                res <- fiber.getResult
                // The interrupt starts the release without waiting for it, so the count is polled.
                _ <- assertEventually(released.get.map(_ == 1))
                r <- released.get
            yield
                assert(res.isPanic, s"expected the interrupt to abort the body but got $res")
                assert(r == 1, s"the value the acquire produced was released $r times")
            end for
        }
    }

    "scope isolation (#1381)" - {

        // What the issue is about: a generic function that runs a scope of its own, handed a computation
        // that carries the CALLER's Scope suspensions. Scope is a ContextEffect, so the innermost Scope.run
        // handles every Scope suspension in its dynamic extent, and the callee's run answers the caller's
        // `ensure` as well as its own. The leaf that used to carry this title passed a body with no Scope
        // suspensions at all, so it never routed a caller's `A < (Scope & S)` through the generic function,
        // which is the entire defect; it is a correct nesting test and now sits with the ordering leaves.
        //
        // Written against the API that exists, so it compiles and fails today rather than not compiling.
        // The caller masks `Scope` before handing the computation over and unmasks after, so the callee's own
        // `Scope.run` has no `Scope` suspension of the caller's left to answer. That is the alternative the
        // issue states, spelled in the API that exists; the caller opts in, and a caller that does not mask
        // still sees the innermost run answer everything in its extent.
        "a Scope.run inside a generic function does not run the caller's finalizers" in {
            import kyo.kernel.ArrowEffect.Mask

            def generic[A, S](effect: A < S): A < (Async & S) =
                Scope.run(Sync.defer(()).andThen(effect))

            for
                caller <- AtomicInt.init(0)
                inner  <- AtomicInt.init(0)
                seen <- Scope.run {
                    Scope.ensure(caller.incrementAndGet.unit).andThen {
                        Mask.run[Scope](generic(Mask[Scope](Scope.ensure(inner.incrementAndGet.unit).andThen(42)))).map { r =>
                            // read inside the outer scope: the callee's own finalizer has run, the caller's has not
                            Kyo.zip(caller.get, inner.get).map((c, i) => (r, c, i))
                        }
                    }
                }
                (r, c, i) = seen
                afterCaller   <- caller.get
                afterSupplied <- inner.get
            yield
                assert(r == 42)
                // Both finalizers below belong to the caller: one registered directly in the outer scope, one
                // written at the call site and handed to `generic` inside its argument. Neither is the callee's,
                // so the callee's own Scope.run must leave both alone and both must run at the outer exit.
                assert(c == 0, s"the caller's own finalizer ran inside the callee's scope: caller=$c")
                assert(i == 0, s"the callee's scope claimed a finalizer the caller supplied: supplied=$i")
                assert(afterCaller == 1, s"the caller's own finalizer must run once, at the outer exit: caller=$afterCaller")
                assert(afterSupplied == 1, s"the caller-supplied finalizer must run once, at the outer exit: supplied=$afterSupplied")
            end for
        }
    }

    "forks" - {

        "a resource acquired outside a fork is live inside it" in {
            val r        = TestResource(1)
            var seenOpen = -1
            Scope.acquire(r).map { res =>
                Fiber.initUnscoped(Sync.defer { seenOpen = res.closes; res.id }).map(_.get)
            }.handle(Scope.run).map { id =>
                assert(id == 1)
                // the fork runs inside the extent that owes the release, so it must not observe one that
                // already ran: a child seeing a closed resource is the escape the Closed abort reports
                assert(seenOpen == 0)
                assert(r.closes == 1)
            }
        }

        "a resource outlives a fork that joins inside its extent" in {
            val r = TestResource(1)
            Scope.acquire(r).map { res =>
                Fiber.initUnscoped(Sync.defer(res.id)).map(_.get).map { id =>
                    // still inside the extent after the child completed: the child ending is not the
                    // extent ending, so nothing is owed yet
                    assert(res.closes == 0)
                    id
                }
            }.handle(Scope.run).map { id =>
                assert(id == 1)
                assert(r.closes == 1)
            }
        }

        "a resource is released once when several forks used it" in {
            val r = TestResource(1)
            Scope.acquire(r).map { res =>
                Kyo.foreach(Seq(1, 2, 3))(_ => Fiber.initUnscoped(Sync.defer(res.id)).map(_.get))
            }.handle(Scope.run).map { ids =>
                assert(ids == Seq(1, 1, 1))
                // one extent, one release, however many children read the resource
                assert(r.closes == 1)
            }
        }

        "a resource acquired inside a fork is released with the enclosing extent" in {
            val r = TestResource(1)
            Fiber.initUnscoped(Scope.acquire(r).map(_.id)).map(_.get)
                .handle(Scope.run).map { id =>
                    assert(id == 1)
                    assert(r.closes == 1)
                }
        }
    }

    "release ordering under an outer handler (#1723)" - {

        // The issue's program, with a log in place of Console. BracketTest pins this ordering for a bare
        // bracket, but nothing pins it through Scope.run: when an outer handler discards Scope.run's
        // continuation, the only thing left to close the scope is the Sync.ensure backstop, and
        // Finalizer.close hands its backlog to a detached fiber that nothing awaits. So the release is
        // started before the next effect but not finished before it.
        //
        // Left pending deliberately. What is missing is backpressure rather than the release itself: nothing
        // is lost, but the computation carries on while the cleanup is still running, so a loop that keeps
        // failing acquires again before the previous release has finished. Scope.run does await on the paths
        // it controls, a normal return and an Abort raised inside it, so the exposure is this one and fiber
        // abandonment, which is where every raced loser and timed-out computation goes.
        //
        // Fixing it is not a matter of the kernel learning about Async: ArrowHandler.recover already hands a
        // computation back to the evaluator (Eval.scala:413), and a handler-driven unwind can afford to park
        // because the fiber is alive. The obstacle is that this path is drainDiscarded, which returns Unit at
        // four handler-completion sites plus contextExit, arrowExit and the eval exit, all in the hot loop.
        "a scope short-circuited by an outer handler has released before the next effect runs".pendingUntilFixed(
            "the outer handler discards Scope.run's continuation, so only the Sync.ensure backstop fires and Finalizer.close runs the finalizers on a detached fiber that nothing awaits"
        ) in {
            for
                log <- AtomicRef.init(Chunk.empty[String])
                write = (s: String) => log.updateAndGet(_.append(s)).unit
                _ <- Abort.run {
                    Check.runAbort {
                        Scope.run {
                            Scope.acquireRelease(write("acquire"))(_ => write("release"))
                                .map(_ => Check.require(false, "boom"))
                        }
                    }
                }.andThen(write("after"))
                seq <- log.get
            yield assert(seq == Chunk("acquire", "release", "after"), s"order was $seq")
            end for
        }
    }

    "hierarchical scopes (#1131)" - {

        // The relationship the issue asks for, on the fiber that has one: a scoped fiber is interrupted by the
        // scope it was spawned in, and the run nested inside it releases before that scope releases anything of
        // its own. The mailbox in the issue's actor example closes on the parent's close by this route.
        //
        // The parent is what ends the child here, which is why the child can park for good: `Fiber.init`
        // interrupts it and then waits for it to have released.
        "a scoped fiber's nested run releases when the scope it was spawned in closes" in {
            for
                released <- AtomicInt.init(0)
                started  <- Latch.init(1)
                _ <- Scope.run {
                    Fiber.init {
                        Scope.run {
                            Scope.acquireRelease(Sync.defer("child"))(_ => released.incrementAndGet.unit)
                                .andThen(started.release)
                                .andThen(Async.never)
                        }
                    }.map(fiber => started.await.andThen(fiber))
                }
                _ <- assertEventually(released.get.map(_ == 1))
                r <- released.get
            yield assert(r == 1, s"the child's resource was released $r times")
            end for
        }

        // The ordering half, on the fiber that has a governing scope. A run nested inside a SCOPED fiber has
        // to release before the scope that spawned that fiber releases anything of its own, or an inner
        // resource outlives the outer one it was borrowed from. Membership does not carry this any more,
        // since a fork withholds it, so what carries it is the scope `Fiber.init` gives its fiber: the run
        // below is a child of THAT, and the release interrupts the fiber, closes that scope and waits for
        // it before the enclosing scope moves on.
        "a scoped fiber's nested run releases before the enclosing scope's own finalizers" in {
            for
                order <- AtomicRef.init(Chunk.empty[String])
                ready <- Promise.init[Unit, Any]
                _ <- Scope.run {
                    Scope.ensure(order.updateAndGet(_.append("outer")).unit).andThen {
                        Fiber.init {
                            Scope.run {
                                Scope.ensure(order.updateAndGet(_.append("inner")).unit)
                                    .andThen(ready.complete(Result.succeed(())))
                                    .andThen(Async.never)
                            }
                        }.map(fiber => ready.get.andThen(fiber))
                    }
                }
                _   <- assertEventually(order.get.map(_.size == 2))
                seq <- order.get
            yield assert(seq == Chunk("inner", "outer"), s"order was $seq")
            end for
        }

        // The other half of the same rule, and the one a fork decides: a run opened inside a fork is a root,
        // because the enclosing scope is not what ends the fiber carrying it. Closing it from there takes a
        // resource away from an owner still using it.
        //
        // This is the shape of a service started lazily under `Fiber.initUnscoped` inside whatever scope first
        // asked for it, holding its scope open with a park. kyo-browser's shared Chrome is one: launched once
        // per run and shared by every test, it was destroyed by the close of the first test scope to touch it,
        // and the next test reconnected to a dead port. Registration still reaches the scope the fork was made
        // in, which `StreamCoreExtensionsTest:890` pins; membership is what a fork withholds.
        "a run opened inside an unscoped fiber outlives the scope the fiber was spawned in" in {
            for
                released <- AtomicInt.init(0)
                started  <- Latch.init(1)
                gate     <- Latch.init(1)
                service <- Scope.run {
                    Fiber.initUnscoped {
                        Scope.run {
                            Scope.acquireRelease(Sync.defer("service"))(_ => released.incrementAndGet.unit)
                                .andThen(started.release)
                                .andThen(gate.await)
                        }
                    }.map(fiber => started.await.andThen(fiber))
                }
                // the scope that spawned the fiber has closed; the service is still parked holding its resource
                onParentExit <- released.get
                _            <- gate.release
                _            <- service.getResult
                onOwnExit    <- released.get
            yield
                assert(onParentExit == 0, s"the enclosing scope released the unscoped fiber's resource: released=$onParentExit")
                assert(onOwnExit == 1, s"the fiber's own exit did not release its resource: released=$onOwnExit")
            end for
        }

        // What membership is for, and the path that needs it. On an unwind only `Sync.ensure` runs, and `close`
        // hands the drain to a detached fiber without waiting for it, so without the child link the enclosing
        // scope's own finalizers would run alongside the nested run's rather than after them.
        "a nested run releases before the enclosing scope's own finalizers when the enclosing run is aborted" in {
            for
                order <- AtomicRef.init(Chunk.empty[String])
                _ <- Abort.run[String] {
                    Scope.run {
                        Scope.ensure(order.updateAndGet(_.append("outer")).unit).andThen {
                            Scope.run {
                                Scope.ensure(order.updateAndGet(_.append("inner")).unit)
                                    .andThen(Abort.fail("boom"))
                            }
                        }
                    }
                }
                seq <- order.get
            yield assert(seq == Chunk("inner", "outer"), s"order was $seq")
            end for
        }
    }

    "racing scopes (#1735)" - {

        // The reporter's own program, kept close to what they ran: four items, a resource that is an item
        // taken from the channel and released by putting it back, and four concurrent users of it. The leaf
        // below states the guarantee in the general case; this states it in the case that was reported, so a
        // regression that only shows at their shape has somewhere to show.
        "the reporter's program leaves every item in the channel" in {
            val expected = (1 to 4).map(_.toString).toSet
            Scope.run {
                Channel.init[String](capacity = 16, access = Access.MultiProducerMultiConsumer).map { chan =>
                    Kyo.foreachDiscard(expected.toSeq)(chan.put).andThen {
                        val acqRel = Scope.acquireRelease(chan.take)(chan.put)
                        Async.foreachDiscard(1 to 4)(_ => Scope.run(acqRel.unit)).andThen {
                            // Drained after the users have finished, so this reads what their releases put
                            // back rather than racing them.
                            assertEventually(chan.size.map(_ == expected.size)).andThen {
                                Kyo.foreach(1 to expected.size)(_ => chan.take).map { drained =>
                                    assert(drained.toSet == expected, s"expected $expected but the channel held ${drained.toSet}")
                                }
                            }
                        }
                    }
                }
            }
        }

        // The guarantee the reporter states, and the whole of it: whatever a racer took, its release puts
        // back. More racers than items on purpose, so some are interrupted while parked on `take` and the
        // rest after taking. Counted rather than latched per racer, because which racers get an item is
        // exactly what the race decides. The counts are awaited before the drain: the reporter's own program
        // drained the moment `race` returned, which reads the channel while the losers are still unwinding,
        // and that timing is not part of what acquireRelease promises.
        "every racer that took an item from the channel puts it back" in {
            Scope.run {
                for
                    chan     <- Channel.init[String](16, Access.MultiProducerMultiConsumer)
                    taken    <- AtomicInt.init(0)
                    returned <- AtomicInt.init(0)
                    // Opened by the fourth taker. The takers themselves never finish, so the race can only be
                    // won by the extra racer below, and only once every item is held by a taker: that makes
                    // the four losers-holding-an-item the scenario every run exercises, rather than however
                    // many the scheduler happened to produce. Without it the counts could only be compared to
                    // each other, which holds trivially at nothing taken and nothing returned.
                    allTaken <- Latch.init(4)
                    takers = Seq.fill(8) {
                        Scope.run {
                            Scope.acquireRelease(
                                chan.take.map(v => taken.incrementAndGet.andThen(allTaken.release).andThen(v))
                            ) { v =>
                                chan.put(v).andThen(returned.incrementAndGet.unit)
                            }.andThen(Async.never)
                        }
                    }
                    _ <- Kyo.foreachDiscard(Seq("1", "2", "3", "4"))(chan.put)
                    _ <- Async.race(allTaken.await +: takers)
                    // the losers unwind on their own fibers, so the returns land after race returns
                    _       <- assertEventually(returned.get.map(_ == 4))
                    drained <- chan.drain
                    t       <- taken.get
                    r       <- returned.get
                yield
                    assert(t == 4, s"the four items were not all taken before the race ended: took $t")
                    assert(r == 4, s"racers took $t items and only $r came back")
                    assert(drained.toSet == Set("1", "2", "3", "4"), s"items lost: $drained")
                end for
            }
        }
    }
end ScopeTest
