package kyo

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean as JAtomicBoolean

/** Scope's behaviour when an interrupt lands inside an acquire, and when a scope exits over a child still holding a resource.
  *
  * jvm-native rather than shared: pinning the moment an interrupt lands inside the acquire's own step needs the acquiring worker held
  * there until the interrupt has been sent, and a held worker has no meaning on the single-threaded JS runtime. The hold is a plain JDK
  * handshake on purpose, bounded, and it stands for what the acquire really is: the user's opaque side effect, a socket or a file being
  * opened, running while an interrupt arrives from outside. The scheduler is adaptive, so a worker parked this way is compensated.
  *
  * A release that runs through a fiber's abandonment is asynchronous, so each test waits on a latch the release opens, bounded by a
  * live-clock timeout. That bound is a failure detector only: a `Timeout` is the evidence that the release never ran, and no assertion
  * passes because of elapsed time.
  */
class ScopeInterruptTest extends kyo.test.Test[Any]:

    /** A closeable whose close is observable, standing in for a file or a socket. */
    final class Handle extends java.lang.AutoCloseable:
        val closed            = new JAtomicBoolean(false)
        def close(): Unit     = closed.set(true)
        def isClosed: Boolean = closed.get()
    end Handle

    /** Holds this worker until the parent says it has sent the interrupt, so the interrupt is pending when the step completes.
      *
      * `entered` is set as the hold begins. A parent that waits for it knows this worker is inside the hold rather than on
      * its way there, which is the difference between a resource genuinely still held and one whose release happened to win
      * a race.
      */
    def untilInterrupted(sent: AtomicBoolean, entered: Maybe[AtomicBoolean] = Absent): Unit =
        // Unsafe: this is the opaque side effect itself, a synchronous hold on the worker, so it cannot
        // suspend to read or write through the effectful tier.
        import AllowUnsafe.embrace.danger
        entered.foreach(_.unsafe.set(true))
        val deadline = java.lang.System.nanoTime() + 5.seconds.toNanos
        while !sent.unsafe.get() && java.lang.System.nanoTime() < deadline do Thread.onSpinWait()
    end untilInterrupted

    "an interrupt landing while the acquire's last step runs" - {

        // The control: Sync's bracket builds its region as the acquire is applied, so an abandonment
        // that finds the acquired value has something to release it with.
        "Sync.acquireReleaseWith still releases what the acquire produced" in {
            val inAcquire = new CountDownLatch(1)
            for
                interruptSent <- AtomicBoolean.init(false)
                acquired      <- AtomicInt.init(0)
                released      <- AtomicInt.init(0)
                relDone       <- Latch.init(1)
                fiber <- Fiber.initUnscoped {
                    Sync.acquireReleaseWith {
                        acquired.incrementAndGet.andThen(Sync.defer {
                            inAcquire.countDown()
                            untilInterrupted(interruptSent)
                            "token"
                        })
                    }(_ => released.incrementAndGet.unit.andThen(relDone.release))(_ => Sync.defer(()))
                }
                _   <- Sync.defer(discard(inAcquire.await(5, TimeUnit.SECONDS)))
                _   <- fiber.interrupt
                _   <- interruptSent.set(true)
                res <- fiber.getResult
                out <- Abort.run[Timeout](Async.timeout(3.seconds)(relDone.await))
                a   <- acquired.get
                r   <- released.get
            yield
                assert(res.isPanic, s"$res")
                assert(a == 1)
                assert(out.isSuccess && r == 1, s"acquired $a, released $r ($out)")
            end for
        }

        // Scope used to register its finalizer in a suspension that follows the acquire, so an
        // interrupt pending when the acquire completed parked the eval before that registration was
        // dispatched and the acquired value was never released. The finalizer now goes in first and
        // a bracket records what the acquire produced, which is atomic with the acquire's own exit.
        "Scope.acquireRelease releases what the acquire produced" in {
            val inAcquire = new CountDownLatch(1)
            for
                interruptSent <- AtomicBoolean.init(false)
                acquired      <- AtomicInt.init(0)
                released      <- AtomicInt.init(0)
                relDone       <- Latch.init(1)
                fiber <- Fiber.initUnscoped {
                    Scope.run {
                        Scope.acquireRelease {
                            acquired.incrementAndGet.andThen(Sync.defer {
                                inAcquire.countDown()
                                untilInterrupted(interruptSent)
                                "token"
                            })
                        }(_ => released.incrementAndGet.unit.andThen(relDone.release)).andThen(Sync.defer(()))
                    }
                }
                _   <- Sync.defer(discard(inAcquire.await(5, TimeUnit.SECONDS)))
                _   <- fiber.interrupt
                _   <- interruptSent.set(true)
                res <- fiber.getResult
                out <- Abort.run[Timeout](Async.timeout(3.seconds)(relDone.await))
                a   <- acquired.get
                r   <- released.get
            yield
                assert(res.isPanic, s"$res")
                assert(a == 1)
                assert(out.isSuccess && r == 1, s"acquired $a, released $r: the value the acquire produced was never released ($out)")
            end for
        }

        "Scope.acquire closes the handle it opened" in {
            val inAcquire = new CountDownLatch(1)
            val handle    = new Handle
            for
                interruptSent <- AtomicBoolean.init(false)
                fiber <- Fiber.initUnscoped {
                    Scope.run {
                        Scope.acquire {
                            Sync.defer {
                                inAcquire.countDown()
                                untilInterrupted(interruptSent)
                                handle
                            }
                        }.andThen(Sync.defer(()))
                    }
                }
                _   <- Sync.defer(discard(inAcquire.await(5, TimeUnit.SECONDS)))
                _   <- fiber.interrupt
                _   <- interruptSent.set(true)
                res <- fiber.getResult
                out <- Abort.run[Timeout](Async.timeout(3.seconds)(Loop.foreach {
                    Sync.defer(handle.isClosed).map(c => if c then Loop.done else Async.sleep(10.millis).andThen(Loop.continue))
                }))
            yield
                assert(res.isPanic, s"$res")
                assert(out.isSuccess && handle.isClosed, s"the opened handle was never closed ($out)")
            end for
        }
    }

    // `Fiber.init` interrupts the child and then waits for it to have released, so the scope's exit
    // comes after the child's release rather than racing it. The child is held past the point the
    // parent would otherwise have exited, so the order below is the scope's guarantee, not a race:
    // signalling alone would let the scope record its exit first.
    "Scope.run waits for a scoped fiber to release the bracket it is inside" in {
        // The child has to be holding the bracket when the scope starts exiting, or the release happens for the
        // wrong reason and this reports the bug fixed when it is not. It parks rather than spinning: the region is
        // installed before the body runs, so the bracket is held from the first instant either way, and a park
        // ends when the scope's exit interrupts it. Spinning cost five seconds a run, because that hold could only
        // end at its own deadline: the flag that would have released it early is set after `Scope.run` returns,
        // and `Scope.run` does not return until the child has released.
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
    // are answered by the resumption path and stay green even if the abandonment path is deleted. Masking
    // the promise makes the cascade a no-op, which leaves the walk as the only thing that can save the
    // finalizer, and completing the promise after the interrupt proves the queued continuation stays dead.
    "a fiber interrupted while parked on a masked promise still runs its scope finalizers" in {
        for
            finalized <- Latch.init(1)
            resumed   <- AtomicBoolean.init(false)
            promise   <- Sync.Unsafe.defer(Promise.Unsafe.initMasked[Unit, Any]().safe)
            fiber <- Fiber.initUnscoped {
                Scope.run {
                    Scope.ensure(finalized.release).andThen(promise.get.andThen(resumed.set(true)))
                }
            }
            // parked for real: the interrupt has to find the fiber on the promise, not on its way there
            _ <- assertEventually(promise.waiters.map(_ == 1))
            _ <- Sync.Unsafe.defer {
                discard(fiber.unsafe.interrupt())
                promise.unsafe.completeUnitDiscard()
            }
            // the release runs on the abandonment walk, so wait for it; the timeout is the failure detector
            out  <- Abort.run[Timeout](Async.timeout(3.seconds)(finalized.await))
            woke <- resumed.get
        yield
            assert(out.isSuccess, "the scope finalizer never ran for a fiber abandoned while parked")
            assert(!woke, "the interrupted continuation ran after the promise was completed")
        end for
    }

end ScopeInterruptTest
