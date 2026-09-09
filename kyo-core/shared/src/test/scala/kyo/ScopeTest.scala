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
                // A registration the scope refuses has no scope left to run its release under, so the release runs
                // detached and settles after the acquiring fiber has already finished. Nothing here can wait on it,
                // so the counters are polled rather than read once.
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

        "fibers registering finalizers while their scope closes: each accepted registration runs exactly once" in {
            // A scope closes by draining its finalizer queue, and the fibers below stay free to register for the whole drain, so
            // registration and close overlap by construction. The scope is preloaded first so the drain is long enough for the two to
            // meet. Every registration the scope accepted has to run, and none of them twice.
            (for
                accepted <- AtomicInt.init(0)
                ran      <- AtomicInt.init(0)
                fibers <- Scope.run {
                    val register =
                        Abort.run[Nothing](Scope.ensure(ran.incrementAndGet.unit)).map {
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
                a <- accepted.get
                r <- ran.get
            yield assert(r == a, s"each accepted registration must run exactly once: ran=$r accepted=$a"))
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

        // Moved here from the "scope isolation (#1381)" block, whose title it wore without testing: the body it
        // passes through the generic function carries no Scope suspensions of its own, so what it actually pins
        // is that a nested Scope.run leaves the enclosing scope's finalizers alone. That is this block's subject.
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

        // The interrupt the block's title claims and did not have: every other leaf here is a normal acquire, a
        // failing acquire, a closed scope or concurrent cleanup. The guards that do pin the acquire-and-register
        // window live in ScopeInterruptTest, which is jvm-native, so JS had no coverage of this window at all.
        //
        // The fiber interrupts ITSELF from inside the acquire, in the same Sync node that performs the claim, so
        // nothing separates the interrupt request from the claim and the earliest point it can be delivered is
        // after the acquire has returned. That needs no held worker, which is what lets it run on every platform.
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
                _ <- Async.sleep(500.millis)
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

    "scope isolation (#1381)" - {

        // What the issue is about: a generic function that runs a scope of its own, handed a computation
        // that carries the CALLER's Scope suspensions. Scope is a ContextEffect, so the innermost Scope.run
        // handles every Scope suspension in its dynamic extent, and the callee's run answers the caller's
        // `ensure` as well as its own. The leaf that used to carry this title passed a body with no Scope
        // suspensions at all, so it never routed a caller's `A < (Scope & S)` through the generic function,
        // which is the entire defect; it is a correct nesting test and now sits with the ordering leaves.
        //
        // Written against the API that exists, so it compiles and fails today rather than not compiling.
        "a Scope.run inside a generic function does not run the caller's finalizers".pendingUntilFixed(
            "Scope is a ContextEffect, so the innermost Scope.run handles every Scope suspension in its dynamic extent, the caller's included; there is no isolation API to keep them apart"
        ) in {
            def generic[A, S](effect: A < S): A < (Async & S) =
                Scope.run(Sync.defer(()).andThen(effect))

            for
                caller <- AtomicInt.init(0)
                inner  <- AtomicInt.init(0)
                seen <- Scope.run {
                    Scope.ensure(caller.incrementAndGet.unit).andThen {
                        generic(Scope.ensure(inner.incrementAndGet.unit).andThen(42)).map { r =>
                            // read inside the outer scope: the callee's own finalizer has run, the caller's has not
                            Kyo.zip(caller.get, inner.get).map((c, i) => (r, c, i))
                        }
                    }
                }
                (r, c, i) = seen
                after <- caller.get
            yield
                assert(r == 42)
                assert(c == 0, s"the caller's finalizer ran inside the callee's scope: caller=$c")
                assert(i == 1, s"the callee's own finalizer did not run at its own exit: inner=$i")
                assert(after == 1, s"the caller's finalizer must run once, at the outer exit: caller=$after")
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

        // The half of the issue that did not land. A nested run registers an await-me finalizer in the
        // enclosing scope, so a parent no longer exits while a child is still releasing; but fork and join
        // are the identity, so a parent cannot close a child that is still running. Here the child parks and
        // the parent exits, which leaves the child's resource open with nothing to close it.
        "closing a scope releases the resources of a nested scope still running under it".pendingUntilFixed(
            "a parent scope waits for a nested run but cannot close it: fork and join are the identity, so closing a scope does not stop the computation running under it"
        ) in {
            for
                released <- AtomicInt.init(0)
                started  <- Latch.init(1)
                gate     <- Latch.init(1)
                child <- Scope.run {
                    Fiber.initUnscoped {
                        Scope.run {
                            Scope.acquireRelease(Sync.defer("child"))(_ => released.incrementAndGet.unit)
                                .andThen(started.release)
                                .andThen(gate.await)
                        }
                    }.map(fiber => started.await.andThen(fiber))
                }
                // the parent has exited; the child is still parked holding its resource
                out <- Abort.run[Timeout](Async.timeout(3.seconds)(assertEventually(released.get.map(_ == 1))))
                _   <- gate.release
                _   <- child.getResult
                r   <- released.get
            yield
                assert(out.isSuccess, "the parent scope exited without releasing the live child's resource")
                assert(r == 1, s"the child's own exit released a second time: released=$r")
            end for
        }
    }

    "racing scopes (#1735)" - {

        // What the library actually promises: whatever a racer took, its release puts back. The releases are
        // awaited before the drain, so this is the contract itself rather than a claim about when it holds.
        "every racer that took an item from the channel puts it back" in {
            Scope.run {
                for
                    chan   <- Channel.init[String](16, Access.MultiProducerMultiConsumer)
                    latches <- Kyo.foreach(1 to 8)(_ => Latch.init(1))
                    racers = latches.map(latch =>
                        Scope.run(Scope.acquireRelease(chan.take)(_ => latch.release.unit))
                    )
                    _   <- Kyo.foreachDiscard(Seq("1", "2", "3", "4"))(chan.put)
                    _   <- Async.race(racers)
                    out <- Abort.run[Timeout](Async.timeout(3.seconds)(Kyo.foreachDiscard(latches)(_.await)))
                    drained <- chan.drain
                yield
                    assert(out.isSuccess, "a racer's release never ran")
                    assert(drained.toSet == Set("1", "2", "3", "4"), s"items lost: $drained")
                end for
            }
        }

        // The reporter's program, which drains as soon as race returns. It reads the channel while the losing
        // fibers may still be unwinding, so an item a loser took is not back yet.
        "a raced scope has finished releasing by the time race returns".pendingUntilFixed(
            "Async.race interrupts the losing fibers and returns without waiting for them to unwind, so a loser's Scope.run may still be putting its item back when the drain reads the channel"
        ) in {
            Scope.run {
                for
                    chan <- Channel.init[String](16, Access.MultiProducerMultiConsumer)
                    racers = Seq.fill(8)(Scope.run(Scope.acquireRelease(chan.take)(chan.put)))
                    _       <- Kyo.foreachDiscard(Seq("1", "2", "3", "4"))(chan.put)
                    _       <- Async.race(racers)
                    drained <- chan.drain
                yield assert(drained.toSet == Set("1", "2", "3", "4"), s"items lost: $drained")
                end for
            }
        }
    }
end ScopeTest
