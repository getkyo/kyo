package kyo

import java.util.concurrent.atomic.AtomicBoolean as JAtomicBoolean

/** Scope's behaviour when an interrupt lands inside an acquire, and when a scope exits over a child still holding a resource.
  *
  * The acquire leaves interrupt their own fiber from inside the acquire and then produce the value, so delivery lands at the next
  * safepoint, after the acquire and at or before the registration. That is the window the tests are about, and reaching it that way needs
  * no second thread: no spin, no latch, no wall clock, and it runs on every platform. `ScopeTest`'s "acquire-time registration (#1820)"
  * block pins the same window over many rounds for `Scope.acquireRelease`; the leaves here cover the sibling acquire surfaces once each.
  *
  * A release that runs through a fiber's abandonment is asynchronous, so each test waits for it with `assertEventually` rather than
  * asserting on anything the clock reports.
  */
class ScopeInterruptTest extends kyo.test.Test[Any]:

    /** A closeable whose close is observable, standing in for a file or a socket. */
    final class Handle extends java.lang.AutoCloseable:
        val closed            = new JAtomicBoolean(false)
        def close(): Unit     = closed.set(true)
        def isClosed: Boolean = closed.get()
    end Handle

    /** Spawns `body` on its own fiber and hands the fiber back to it, so the body can interrupt itself.
      *
      * The handoff is what makes the interrupt land inside the acquire instead of around it: the body waits for its own fiber before it
      * runs, so by the time the acquire executes there is something for it to interrupt.
      */
    def selfInterrupting(body: Fiber[Unit, Any] => Unit < (Sync & Async))(using Frame): Result[Nothing, Unit] < (Sync & Async) =
        for
            handoff <- Promise.init[Fiber[Unit, Any], Any]
            fiber   <- Fiber.initUnscoped(handoff.get.map(body))
            _       <- handoff.complete(Result.succeed(fiber))
            res     <- fiber.getResult
        yield res

    "an interrupt landing while the acquire's last step runs" - {

        // The control: Sync's bracket builds its region as the acquire is applied, so an abandonment
        // that finds the acquired value has something to release it with.
        "Sync.acquireReleaseWith still releases what the acquire produced" in {
            for
                acquired <- AtomicInt.init(0)
                released <- AtomicInt.init(0)
                res <- selfInterrupting { self =>
                    Sync.acquireReleaseWith {
                        Sync.defer {
                            // Unsafe: the interrupt has to be requested from inside the acquire, before it
                            // returns, which is not an effectful position, and the count has to be taken in
                            // the same node so it reports the acquire producing a value.
                            import AllowUnsafe.embrace.danger
                            discard(self.unsafe.interrupt())
                            discard(acquired.unsafe.incrementAndGet())
                            "token"
                        }
                    }(_ => released.incrementAndGet.unit)(_ => Sync.defer(()))
                }
                _ <- assertEventually(released.get.map(_ == 1))
                a <- acquired.get
                r <- released.get
            yield
                assert(res.isPanic, s"$res")
                assert(a == 1)
                assert(r == 1, s"acquired $a, released $r: the value the acquire produced was never released")
            end for
        }

        "Scope.acquireRelease releases what the acquire produced" in {
            for
                acquired <- AtomicInt.init(0)
                released <- AtomicInt.init(0)
                res <- selfInterrupting { self =>
                    Scope.run {
                        Scope.acquireRelease {
                            Sync.defer {
                                // Unsafe: see the leaf above.
                                import AllowUnsafe.embrace.danger
                                discard(self.unsafe.interrupt())
                                discard(acquired.unsafe.incrementAndGet())
                                "token"
                            }
                        }(_ => released.incrementAndGet.unit).andThen(Sync.defer(()))
                    }
                }
                _ <- assertEventually(released.get.map(_ == 1))
                a <- acquired.get
                r <- released.get
            yield
                assert(res.isPanic, s"$res")
                assert(a == 1)
                assert(r == 1, s"acquired $a, released $r: the value the acquire produced was never released")
            end for
        }

        "Scope.acquire closes the handle it opened" in {
            val handle = new Handle
            for
                res <- selfInterrupting { self =>
                    Scope.run {
                        Scope.acquire {
                            Sync.defer {
                                // Unsafe: see the first leaf in this block.
                                import AllowUnsafe.embrace.danger
                                discard(self.unsafe.interrupt())
                                handle
                            }
                        }.andThen(Sync.defer(()))
                    }
                }
                _ <- assertEventually(Sync.defer(handle.isClosed))
            yield
                assert(res.isPanic, s"$res")
                assert(handle.isClosed, "the opened handle was never closed")
            end for
        }
    }

    "Scope.run waits for a scoped fiber to release the bracket it is inside" in {
        // The child has to be holding the bracket when the scope starts exiting, or the release happens for the
        // wrong reason and this reports the bug fixed when it is not. It parks rather than spinning: the region is
        // installed before the body runs, so the bracket is held from the first instant either way, and a park
        // ends when the scope's exit interrupts it.
        for
            entered  <- AtomicBoolean.init(false)
            log      <- AtomicRef.init(Chunk.empty[String])
            released <- Latch.init(1)
            _ <- Scope.run {
                Fiber.init {
                    Sync.ensure(log.updateAndGet(_.append("released")).andThen(released.release)) {
                        entered.set(true).andThen(Async.never)
                    }
                }.andThen(assertEventually(entered.get))
            }
            _   <- log.updateAndGet(_.append("scope exited"))
            _   <- released.await
            seq <- log.get
        yield assert(seq == Chunk("released", "scope exited"), s"order was $seq")
        end for
    }

    // Fiber.init routes through Scope.acquireRelease and so is covered by the pins above. Fiber.use does
    // not: it is `initUnscoped(v).map(fiber => Sync.ensure(fiber.interrupt)(f(fiber)))`, so the fiber is
    // already running while the ensure that would interrupt it is still one dispatch away. An interrupt
    // landing in that gap leaves nothing behind to interrupt the child, and nothing an abandonment can
    // walk either, since the ensure's region does not exist until the map runs.
    //
    // The gap is inside kyo's own spawn, so it cannot be held open the way the acquire thunks above are.
    // It is raced instead: each round interrupts the parent as close to the spawn as possible, and the
    // round fails only if a child is left running with nobody to stop it. One escape is a real one, so
    // the assertion is on the count, not on a proportion.
    "Fiber.use interrupts the fiber it spawned when an interrupt lands on the spawn" in {
        val rounds = 40
        for
            exercised <- AtomicInt.init(0)
            orphaned  <- AtomicInt.init(0)
            // Half the rounds wait for the child to be running before interrupting, and half interrupt at the
            // spawn. Racing alone proved nothing: under a full-suite run the child never won the race once in
            // forty rounds, so the leaf passed having orphaned nothing because it had started nothing. The
            // waiting half guarantees the orphan property is actually exercised; the racing half still probes
            // the window between the spawn and the cleanup that interrupts it.
            _ <- Kyo.foreachDiscard(1 to rounds) { round =>
                for
                    started    <- Promise.init[Unit, Any]
                    torn       <- Latch.init(1)
                    gate       <- Latch.init(1)
                    childAlive <- AtomicBoolean.init(false)
                    child = (Sync.ensure(childAlive.set(false).andThen(torn.release)) {
                        childAlive.set(true).andThen(started.completeUnitDiscard).andThen(gate.await)
                    }: Unit < (Sync & Async))
                    parent <- Fiber.initUnscoped(Fiber.use[Nothing, Unit, Any, Any](child)(_ => started.get))
                    _      <- if round % 2 == 0 then started.get else Kyo.unit
                    _      <- parent.interrupt
                    _      <- parent.getResult
                    // Most rounds interrupt the parent before the child ever runs, and a child that never started
                    // holds nothing and tears nothing down, so waiting for `torn` there only pays the timeout: the
                    // whole leaf spent 40 of them, twelve seconds, on rounds proving nothing. Only a round whose
                    // child actually started has a teardown to wait for.
                    ran <- started.done
                    out <- (
                        if ran then Abort.run[Timeout](Async.timeout(300.millis)(torn.await))
                        else Result.succeed(())
                    ): Result[Timeout, Unit] < Async
                    left <- childAlive.get
                    _    <- gate.release
                    _    <- if ran then exercised.incrementAndGet.unit else Kyo.unit
                    _    <- if out.isFailure && left then orphaned.incrementAndGet.unit else Kyo.unit
                yield ()
                end for
            }
            leaked <- orphaned.get
            hit    <- exercised.get
        yield
            assert(leaked == 0, s"$leaked of $rounds rounds left the spawned fiber running with nothing to interrupt it")
            // A round whose child never started proves nothing, so the leaf has to know the race lands sometimes.
            assert(hit > 0, s"none of the $rounds rounds got the child running, so nothing was raced against the spawn")
        end for
    }

    // Same gap as the leaf above, at the other spawn. `Fiber.init` goes through Scope.acquireRelease, so the
    // interrupt that stops the child is registered as the acquire's value arrives; what is not covered anywhere
    // is an interrupt landing on the spawn itself. Raced the same way, and one escape is a real one.
    "Fiber.init interrupts and awaits the fiber it spawned when the interrupt lands on the spawn" in {
        val rounds = 40
        for
            exercised <- AtomicInt.init(0)
            orphaned  <- AtomicInt.init(0)
            // Half the rounds wait for the child to be running before interrupting, and half interrupt at the
            // spawn. Racing alone proved nothing: under a full-suite run the child never won the race once in
            // forty rounds, so the leaf passed having orphaned nothing because it had started nothing. The
            // waiting half guarantees the orphan property is actually exercised; the racing half still probes
            // the window between the spawn and the cleanup that interrupts it.
            _ <- Kyo.foreachDiscard(1 to rounds) { round =>
                for
                    started    <- Promise.init[Unit, Any]
                    torn       <- Latch.init(1)
                    gate       <- Latch.init(1)
                    childAlive <- AtomicBoolean.init(false)
                    child = (Sync.ensure(childAlive.set(false).andThen(torn.release)) {
                        childAlive.set(true).andThen(started.completeUnitDiscard).andThen(gate.await)
                    }: Unit < (Sync & Async))
                    parent <- Fiber.initUnscoped(Scope.run(Fiber.init(child).andThen(started.get)))
                    _      <- if round % 2 == 0 then started.get else Kyo.unit
                    _      <- parent.interrupt
                    _      <- parent.getResult
                    // Only a round whose child actually started has a teardown to wait for; see the leaf above.
                    ran <- started.done
                    out <- (
                        if ran then Abort.run[Timeout](Async.timeout(300.millis)(torn.await))
                        else Result.succeed(())
                    ): Result[Timeout, Unit] < Async
                    left <- childAlive.get
                    _    <- gate.release
                    _    <- if ran then exercised.incrementAndGet.unit else Kyo.unit
                    _    <- if out.isFailure && left then orphaned.incrementAndGet.unit else Kyo.unit
                yield ()
                end for
            }
            leaked <- orphaned.get
            hit    <- exercised.get
        yield
            assert(leaked == 0, s"$leaked of $rounds rounds left the spawned fiber running with nothing to interrupt it")
            assert(hit > 0, s"none of the $rounds rounds got the child running, so nothing was raced against the spawn")
        end for
    }

    // A fiber abandoned while parked runs its finalizers through the abandonment walk, not through being
    // resumed. Every other interrupt test here parks on a promise the interrupt cascades to, so all of them
    // are answered by the resumption path and stay green even if the abandonment path is deleted. Making
    // the promise uninterruptible turns the cascade into a no-op, which leaves the walk as the only thing
    // that can save the finalizer, and completing the promise after the interrupt proves the queued
    // continuation stays dead.
    "a fiber interrupted while parked on an uninterruptible promise still runs its scope finalizers" in {
        for
            finalized <- AtomicInt.init(0)
            resumed   <- AtomicBoolean.init(false)
            promise   <- Sync.Unsafe.defer(Promise.Unsafe.initUninterruptible[Unit, Any]().safe)
            fiber <- Fiber.initUnscoped {
                Scope.run {
                    Scope.ensure(finalized.incrementAndGet.unit).andThen(promise.get.andThen(resumed.set(true)))
                }
            }
            // parked for real: the interrupt has to find the fiber on the promise, not on its way there
            _ <- assertEventually(promise.waiters.map(_ == 1))
            _ <- Sync.Unsafe.defer {
                discard(fiber.unsafe.interrupt())
                promise.unsafe.completeUnitDiscard()
            }
            // the release runs on the abandonment walk, so wait for it
            _    <- assertEventually(finalized.get.map(_ == 1))
            fin  <- finalized.get
            woke <- resumed.get
        yield
            assert(fin == 1, "the scope finalizer never ran for a fiber abandoned while parked")
            assert(!woke, "the interrupted continuation ran after the promise was completed")
        end for
    }

end ScopeInterruptTest
