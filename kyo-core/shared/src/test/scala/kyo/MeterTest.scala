package kyo

class MeterTest extends kyo.test.Test[Any]:

    // A permanently blocked acquisition never completes, so any window `Async.timeout` reports it did not finish in proves
    // the block. Short since the assertion is on the failure outcome, not the elapsed time.
    val blockedWindow = 100.millis

    "mutex" - {
        "init" in {
            Scope.run(Meter.initMutex).map: meter =>
                meter.closed.map: isClosed =>
                    assert(isClosed)
        }

        "use" in {
            Meter.useMutex(meter => Kyo.zip(meter.closed, meter)).map:
                case (isClosed1, meter) =>
                    meter.closed.map: isClosed2 =>
                        assert(!isClosed1 && isClosed2)
        }

        "ok" in {
            for
                t <- Meter.initMutex
                v <- t.run(2)
            yield assert(v == 2)
        }

        "run" in {
            for
                t  <- Meter.initMutex
                p  <- Promise.init[Int, Any]
                b1 <- Promise.init[Unit, Any]
                f1 <- Fiber.initUnscoped(t.run(b1.completeUnit.map(_ => p.getResult)))
                _  <- b1.get
                a1 <- t.availablePermits
                w1 <- t.pendingWaiters
                b2 <- Promise.init[Unit, Any]
                f2 <- Fiber.initUnscoped(b2.completeUnit.map(_ => t.run(2)))
                _  <- b2.get.andThen(assertEventually(t.pendingWaiters.map(_ > 0)))
                a2 <- t.availablePermits
                w2 <- t.pendingWaiters
                d1 <- f1.done
                d2 <- f2.done
                _  <- p.complete(Result.succeed(1))
                v1 <- f1.get
                v2 <- f2.get
                a3 <- t.availablePermits
                w3 <- t.pendingWaiters
            yield assert(
                a1 == 0 && w1 == 0 && !d1 && !d2 && a2 == 0 && w2 == 1 && v1.contains(1) && v2 == 2 && a3 == 1 && w3 == 0
            )
        }

        "tryRun" in {
            for
                sem <- Meter.initMutex
                p   <- Promise.init[Int, Any]
                b1  <- Promise.init[Unit, Any]
                f1  <- Fiber.initUnscoped(sem.tryRun(b1.completeUnit.map(_ => p.getResult)))
                _   <- b1.get
                a1  <- sem.availablePermits
                w1  <- sem.pendingWaiters
                b1  <- sem.tryRun(2)
                b2  <- f1.done
                _   <- p.complete(Result.succeed(1))
                v1  <- f1.get
            yield assert(a1 == 0 && w1 == 0 && b1.isEmpty && !b2 && v1.contains(Result.succeed(1)))
        }
    }

    "semaphore" - {
        "init" in {
            Scope.run(Meter.initSemaphore(3)).map: meter =>
                meter.closed.map: isClosed =>
                    assert(isClosed)
        }

        "use" in {
            Meter.useSemaphore(3)(meter => Kyo.zip(meter.closed, meter)).map:
                case (isClosed1, meter) =>
                    meter.closed.map: isClosed2 =>
                        assert(!isClosed1 && isClosed2)
        }

        "ok" in {
            for
                t  <- Meter.initSemaphore(2)
                v1 <- t.run(2)
                v2 <- t.run(3)
            yield assert(v1 == 2 && v2 == 3)
        }

        "run" in {
            for
                t  <- Meter.initSemaphore(2)
                p  <- Promise.init[Int, Any]
                b1 <- Promise.init[Unit, Any]
                f1 <- Fiber.initUnscoped(t.run(b1.completeUnit.andThen(p.getResult)))
                _  <- b1.get
                b2 <- Promise.init[Unit, Any]
                f2 <- Fiber.initUnscoped(t.run(b2.completeUnit.andThen(p.getResult)))
                _  <- b2.get
                a1 <- t.availablePermits
                w1 <- t.pendingWaiters
                b3 <- Promise.init[Unit, Any]
                f3 <- Fiber.initUnscoped(b3.completeUnit.andThen(t.run(2)))
                _  <- b3.get.andThen(assertEventually(t.pendingWaiters.map(_ > 0)))
                a2 <- t.availablePermits
                w2 <- t.pendingWaiters
                d1 <- f1.done
                d2 <- f2.done
                d3 <- f3.done
                _  <- p.complete(Result.succeed(1))
                v1 <- f1.get
                v2 <- f2.get
                v3 <- f3.get
                a3 <- t.availablePermits
                w3 <- t.pendingWaiters
            yield assert(a1 == 0 && w1 == 0 && !d1 && !d2 && !d3 && a2 == 0 && w2 == 1 &&
                v1.contains(1) && v2.contains(1) && v3 == 2 && a3 == 2 && w3 == 0)
        }

        "tryRun" in {
            for
                sem <- Meter.initSemaphore(2)
                p   <- Promise.init[Int, Any]
                b1  <- Promise.init[Unit, Any]
                f1  <- Fiber.initUnscoped(sem.tryRun(b1.completeUnit.map(_ => p.getResult)))
                _   <- b1.get
                a1  <- sem.availablePermits
                w1  <- sem.pendingWaiters
                b2  <- Promise.init[Unit, Any]
                f2  <- Fiber.initUnscoped(sem.tryRun(b2.completeUnit.map(_ => p.getResult)))
                _   <- b2.get
                a2  <- sem.availablePermits
                w2  <- sem.pendingWaiters
                b3  <- sem.tryRun(2)
                b4  <- f1.done
                b5  <- f2.done
                _   <- p.complete(Result.succeed(1))
                v1  <- f1.get
                v2  <- f2.get
            yield assert(a1 == 1 && w1 == 0 && b3.isEmpty && !b4 && !b5 &&
                v1.contains(Result.succeed(1)) && v2.contains(Result.succeed(1)))
        }

        "concurrency" - {

            val repeats = 10

            "run" in {
                (for
                    size    <- Choice.eval(1, 2, 3, 50, 100)
                    meter   <- Meter.initSemaphore(size)
                    counter <- AtomicInt.init(0)
                    results <-
                        Async.foreach(1 to 100, 100)(_ =>
                            Abort.run(meter.run(counter.incrementAndGet))
                        )
                    count   <- counter.get
                    permits <- meter.availablePermits
                yield
                    assert(results.count(_.isFailure) == 0)
                    assert(count == 100)
                    assert(permits == size)
                )
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .unit
            }

            "close" in {
                (for
                    size    <- Choice.eval(1, 2, 3, 50, 100)
                    meter   <- Meter.initSemaphore(size)
                    latch   <- Latch.init(1)
                    counter <- AtomicInt.init(0)
                    runFiber <- Fiber.initUnscoped(
                        latch.await.andThen(Async.fill(100, 100)(
                            Abort.run(meter.run(counter.incrementAndGet))
                        ))
                    )
                    closeFiber <- Fiber.initUnscoped(latch.await.andThen(meter.close))
                    _          <- latch.release
                    closed     <- closeFiber.get
                    completed  <- runFiber.get
                    count      <- counter.get
                    available  <- Abort.run(meter.availablePermits)
                yield
                    assert(closed)
                    assert(completed.count(_.isSuccess) <= 100)
                    assert(count <= 100)
                    assert(available.isFailure)
                )
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .unit
            }

            // A caller whose reservation is lost to a concurrent update leaves its promise queued for the
            // instant it takes to retire it. A releaser polling the queue in that instant hands the permit
            // to a promise no reservation stands behind, so the waiter it was owed to keeps waiting and the
            // meter runs one permit short from then on. The last release then sees free permits in the
            // ledger, skips the handoff, and leaves that waiter parked for good.
            "sustained contention never strands a queued waiter".notJs.notWasm in {
                val permits    = 2
                val callers    = 8
                val iterations = 10000
                (for
                    meter   <- Meter.initSemaphore(permits)
                    counter <- AtomicInt.init(0)
                    settled <- Abort.run[Timeout](Async.timeout(30.seconds)(
                        Async.foreach(1 to callers, callers)(_ =>
                            Loop.indexed(idx =>
                                if idx == iterations then Loop.done
                                else meter.run(counter.incrementAndGet).map(_ => Loop.continue)
                            )
                        )
                    ))
                    count <- counter.get
                yield assert(
                    settled.isSuccess,
                    s"a queued waiter was never handed a permit: $count of ${callers * iterations} calls completed"
                ))
                    .handle(Loop.repeat(20))
                    .unit
            }

            "with interruptions".notJs.notWasm in {
                (for
                    size    <- Choice.eval(1, 2, 3, 50)
                    meter   <- Meter.initSemaphore(size)
                    started <- Latch.init(100)
                    latch   <- Latch.init(1)
                    counter <- AtomicInt.init(0)
                    runFibers <- Kyo.foreach(1 to 100)(_ =>
                        Fiber.initUnscoped(started.release.andThen(latch.await.andThen(meter.run(counter.incrementAndGet))))
                    )
                    interruptFiber <- Fiber.initUnscoped(latch.await.andThen(
                        Async.foreach(runFibers.take(50), 50)(_.interrupt(panic))
                    ))
                    _           <- started.await
                    _           <- Async.sleep(100.millis)
                    _           <- latch.release
                    interrupted <- interruptFiber.get
                    completed   <- Kyo.foreach(runFibers)(_.getResult)
                    count       <- counter.get
                yield assert(interrupted.count(identity) + completed.count(_.isSuccess) == 100))
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .unit
            }
        }
    }

    "semaphore interrupt invariants" - {

        // A permit is held for the whole body, so no more bodies may run concurrently than the
        // meter has permits. The case under test is a waiter interrupted while parked: it never
        // acquired a permit, so withdrawing it must not admit anyone.
        "concurrency never exceeds permits while parked waiters are interrupted".notJs.notWasm in {
            val permits = 1
            val waiters = 32
            (for
                meter      <- Meter.initSemaphore(permits)
                entered    <- AtomicInt.init(0)
                live       <- AtomicInt.init(0)
                violations <- AtomicInt.init(0)
                gate       <- Latch.init(1)
                body =
                    for
                        _ <- entered.incrementAndGet
                        n <- live.incrementAndGet
                        _ <- if n > permits then violations.incrementAndGet.map(_ => ()) else Sync.defer(())
                        _ <- live.decrementAndGet
                    yield ()
                holder <- Fiber.initUnscoped(meter.run(gate.await.andThen(body)))
                // The holder owns the permit before anything else moves (counter, not a sleep).
                _      <- assertEventually(Abort.run(meter.availablePermits).map(_ == Result.succeed(0)))
                parked <- Kyo.foreach(1 to waiters)(_ => Fiber.initUnscoped(meter.run(body)))
                // Every waiter is parked before any interrupt (counter, not a sleep).
                _ <- assertEventually(Abort.run(meter.pendingWaiters).map(_ == Result.succeed(waiters)))
                _ <- Async.foreach(parked.take(waiters / 2), waiters / 2)(_.interrupt(panic))
                // The interrupted waiters' give-backs have landed: the exact window in which a handoff
                // through a dead entry would admit a live waiter while the permit is still held.
                _ <- assertEventually(Abort.run(meter.pendingWaiters).map(_ == Result.succeed(waiters - waiters / 2)))
                // Deterministic arbiter: no waiter body may have run while the holder holds the only
                // permit. Read before releasing the gate. (`violations` stays a best-effort ceiling
                // for the drain phase, its dwell removed.)
                held <- entered.get
                _    <- gate.release
                _    <- holder.getResult
                _    <- Kyo.foreach(parked)(_.getResult)
                bad  <- violations.get
            yield assert(
                held == 0 && bad == 0,
                s"held-phase admissions=$held (must be 0); over-$permits concurrency moments=$bad"
            ))
                .handle(Loop.repeat(20))
                .unit
        }

        // Closing must complete EVERY parked waiter, including ones queued behind a waiter that was
        // interrupted first. A close that drains a count derived from `state` stops on the retired
        // promise the interrupted waiter left behind and strands the live ones, which hangs here.
        "close completes waiters queued behind an interrupted waiter".notJs.notWasm in {
            val permits = 1
            val waiters = 8
            (for
                meter  <- Meter.initSemaphore(permits)
                gate   <- Latch.init(1)
                holder <- Fiber.initUnscoped(meter.run(gate.await))
                _      <- assertEventually(Abort.run(meter.availablePermits).map(_ == Result.succeed(0)))
                parked <- Kyo.foreach(1 to waiters)(_ => Fiber.initUnscoped(meter.run(())))
                _      <- assertEventually(Abort.run(meter.pendingWaiters).map(_ == Result.succeed(waiters)))
                // Interrupt the FIRST waiter (verified parked) so its retired promise sits at the head
                // of the queue ahead of every still-parked waiter behind it.
                _ <- assertEventually(parked.head.waiters.map(_ == 1))
                _ <- parked.head.interrupt(panic)
                // Its give-back has landed: the queue now holds a retired head plus waiters-1 live
                // entries, the exact state a count-derived drain (waiters-1 counted vs waiters entries)
                // stops short on, stranding a live waiter.
                _       <- assertEventually(Abort.run(meter.pendingWaiters).map(_ == Result.succeed(waiters - 1)))
                _       <- meter.close
                _       <- gate.release
                _       <- holder.getResult
                settled <- Kyo.foreach(parked)(_.getResult)
                closed  <- Abort.run(meter.availablePermits)
            yield assert(
                settled.size == waiters && settled.drop(1).forall(_.isFailure) && closed.isFailure,
                s"expected all $waiters waiters to settle behind the interrupted head, got ${settled.size}"
            ))
                .handle(Loop.repeat(20))
                .unit
        }

        // After every fiber has settled, the permit ledger must be back to full. An interrupted
        // waiter that over-returns would show up here as more free permits than the meter has.
        "permits return to full after parked waiters are interrupted".notJs.notWasm in {
            val permits = 2
            val waiters = 16
            (for
                meter   <- Meter.initSemaphore(permits)
                gate    <- Latch.init(1)
                holders <- Kyo.foreach(1 to permits)(_ => Fiber.initUnscoped(meter.run(gate.await)))
                // Both states the setup depends on are readable from the meter, so wait for them
                // rather than for a duration: every permit taken, then every waiter queued behind them.
                _      <- assertEventually(Abort.run(meter.availablePermits).map(_ == Result.succeed(0)))
                parked <- Kyo.foreach(1 to waiters)(_ => Fiber.initUnscoped(meter.run(gate.await)))
                _      <- assertEventually(Abort.run(meter.pendingWaiters).map(_ == Result.succeed(waiters)))
                _      <- Async.foreach(parked, waiters)(_.interrupt(panic))
                _      <- gate.release
                _      <- Kyo.foreach(holders)(_.getResult)
                _      <- Kyo.foreach(parked)(_.getResult)
                // A settled fiber does not mean a settled ledger: the teardown that returns a permit
                // runs as the fiber unwinds, so read until it comes to rest instead of once.
                _ <- assertEventually(
                    Abort.run(Kyo.zip(meter.availablePermits, meter.pendingWaiters)).map(_ == Result.succeed((permits, 0)))
                )
            yield ())
                .handle(Loop.repeat(20))
                .unit
        }

        // A caller interrupted between reserving a slot and parking used to leave its pending promise
        // queued: a later handoff would "grant" the freed permit to the dead caller and stop, so the
        // live waiter behind it was never woken. Many contenders are interrupted mid-approach (racing
        // the reserve-before-park window), then settled; the victim queued behind them must always be
        // granted the permit the holder frees.
        "interrupt racing the acquisition never strands a later waiter".notJs.notWasm in {
            val contenders = 30
            (for
                meter   <- Meter.initSemaphore(1)
                gate    <- Latch.init(1)
                holder  <- Fiber.initUnscoped(meter.run(gate.await))
                _       <- assertEventually(Abort.run(meter.availablePermits).map(_ == Result.succeed(0)))
                cs      <- Kyo.foreach(1 to contenders)(_ => Fiber.initUnscoped(meter.run(())))
                _       <- Async.foreach(cs, contenders)(_.interrupt(panic))
                _       <- Kyo.foreach(cs)(_.getResult)
                victim  <- Fiber.initUnscoped(meter.run(()))
                _       <- assertEventually(Abort.run(meter.pendingWaiters).map(_.exists(_ >= 1)))
                _       <- gate.release
                _       <- holder.getResult
                granted <- Abort.run[Timeout](Async.timeout(10.seconds)(victim.getResult))
                // If the victim was granted, the storm must also have left the ledger at rest: a
                // mid-acquisition regression that leaks or doubles a registration without stranding
                // the victim shows here. Skipped on a strand so it never hangs on the buggy build.
                _ <- if granted.isSuccess then
                    assertEventually(
                        Abort.run(Kyo.zip(meter.availablePermits, meter.pendingWaiters)).map(_ == Result.succeed((1, 0)))
                    )
                else Sync.defer(())
            yield assert(granted.isSuccess, "the waiter behind interrupted callers was never handed the permit"))
                .handle(Loop.repeat(200))
                .unit
        }

        // A permit released while an interrupted waiter's ledger give-back is still pending must
        // reach the next waiter. This forces (via a finalizer gate, no sleeps) the one ordering the
        // public API cannot: the interrupted waiter's give-back runs AFTER the holder's release AND
        // after a fresh victim has registered. On the current impl the release drops the permit into
        // the dead entry and the victim strands forever; a correct impl hands it to the victim.
        "a permit released while an interrupted waiter's teardown is pending reaches the next waiter".notJs.notWasm in {
            (for
                meter      <- Meter.initSemaphore(1)
                gateOpen   <- AtomicInt.init(0)
                holderGate <- Latch.init(1)
                holder     <- Fiber.initUnscoped(meter.run(holderGate.await))
                _          <- assertEventually(Abort.run(meter.availablePermits).map(_ == Result.succeed(0)))
                // The waiter's own finalizer spins on the gate. Finalizers run FIFO, so this outer
                // ensure runs strictly before Meter's internal settle, holding the give-back open.
                w <- Fiber.initUnscoped(Sync.ensure(spinUntilOpen(gateOpen))(meter.run(())))
                // Reserved (pendingWaiters) AND parked (the fiber registered a join), so the interrupt
                // kills a queued promise rather than a not-yet-parked one.
                _ <- assertEventually(Abort.run(meter.pendingWaiters).map(_ == Result.succeed(1)))
                _ <- assertEventually(w.waiters.map(_ == 1))
                _ <- w.interrupt(panic)
                // Safety net: however the window exits, open the gate so w can settle and the leaf
                // fails on its real assertion instead of wedging on the spin.
                granted <- Sync.ensure(gateOpen.set(1)) {
                    for
                        _      <- holderGate.release
                        _      <- holder.getResult
                        victim <- Fiber.initUnscoped(meter.run(()))
                        // The victim engaged the meter before the gate opens: parked (pendingWaiters
                        // == 1) on the current impl, or already done if a future fix lets it fast-path.
                        // Either way it registered first, and this never hangs on a correct fix.
                        _ <- assertEventually(
                            for
                                pw <- Abort.run(meter.pendingWaiters)
                                d  <- victim.done
                            yield pw == Result.succeed(1) || d
                        )
                        _ <- gateOpen.set(1)
                        g <- Abort.run[Timeout](Async.timeout(10.seconds)(victim.getResult))
                    yield g
                }
            yield assert(granted.isSuccess, "the waiter behind an interrupted caller was never handed the released permit"))
                .handle(Loop.repeat(3))
                .unit
        }

        // The take-then-install class. run's fast path and its woken claim (and tryRun) take a permit
        // by a single CAS, but the finalizer that returns it installs one effect-step later; an
        // interrupt, or a preemption whose queue-wait an interrupt then lands in, hitting that gap
        // strands the permit. At permits=1 the meter then dies: no later acquire ever completes. A
        // storm of interrupts over a ring of fibers churning acquire/release on one permit samples
        // every such gap at uniform phase (paced by a channel take per strike, no sleeps), so the
        // ledger must always return to full. Reliably red on any build that leaves a window open.
        "an interrupt storm over churning acquisitions never leaks a permit".notJs.notWasm in {
            val loopers = 4
            val strikes = 10000
            (for
                meter <- Meter.initSemaphore(1)
                ch    <- Channel.initUnscoped[Unit](64)
                count <- AtomicInt.init(0)
                slots <- Kyo.foreach(0 until loopers)(_ => Fiber.initUnscoped(churn(meter, ch, count)))
                ring0 = (0 until loopers).map(i => i -> slots(i)).toMap
                // Bound the whole scenario: a leaked permit either stalls the storm's next take or
                // leaves the ledger short of full, and the timeout turns either into a red Timeout
                // instead of a framework-length hang. A correct build finishes in a few seconds.
                outcome <- Abort.run[Timeout](Async.timeout(30.seconds) {
                    for
                        finalRing <- Loop.indexed(ring0) { (i, ring) =>
                            if i >= strikes then Loop.done(ring)
                            else
                                val slot = i % loopers
                                // One completed body (a take) means the ring is live; then strike a slot
                                // and fork its replacement so the ring keeps cycling under one permit.
                                for
                                    _ <- ch.take
                                    _ <- ring(slot).interrupt(panic)
                                    f <- Fiber.initUnscoped(churn(meter, ch, count))
                                yield Loop.continue(ring.updated(slot, f))
                                end for
                        }
                        _ <- Async.foreach(finalRing.values.toSeq, loopers)(_.interrupt(panic))
                        _ <- Kyo.foreach(finalRing.values.toSeq)(_.getResult)
                        _ <- assertEventually(
                            Abort.run(Kyo.zip(meter.availablePermits, meter.pendingWaiters)).map(_ == Result.succeed((1, 0)))
                        )
                    yield ()
                })
                n <- count.get
            yield assert(outcome.isSuccess && n > 0, s"permit leaked: storm stalled or ledger never returned to full (bodies=$n)"))
                .handle(Loop.repeat(2))
                .unit
        }
    }

    def loop(meter: Meter, ch: Channel[Unit]): Unit < (Async & Abort[Closed]) =
        meter.run(ch.put(())).andThen(loop(meter, ch))

    // A fiber that churns the meter: acquire, run a body that puts one token (so a storm can pace on
    // it) and bumps a completed-body counter, release, repeat. Used by the take-then-install storm leaf.
    def churn(meter: Meter, ch: Channel[Unit], count: AtomicInt): Unit < (Async & Abort[Closed]) =
        meter.run(ch.put(()).andThen(count.incrementAndGet.unit)).andThen(churn(meter, ch, count))

    val panic = Result.Panic(new Exception)

    // Test-only synchronous gate: holds a fiber's finalizer open until `flag` flips to 1, so a test
    // can force the interrupted waiter's ledger give-back to run after a release. JVM-only: it spins
    // a scheduler worker and needs a second worker to make progress.
    def spinUntilOpen(flag: AtomicInt)(using Frame): Unit < Sync =
        flag.get.map(v => if v == 1 then () else spinUntilOpen(flag))

    "rate limiter" - {
        "init" in {
            Scope.run(Meter.initRateLimiter(2, 1.milli)).map: meter =>
                meter.closed.map: isClosed =>
                    assert(isClosed)
        }

        // A rate-limiter waiter interrupted while parked must return NOTHING to the rate: it never
        // held a permit, so its teardown must not mint one early. A regression that returned the
        // registration (semaphore-style) would leave a phantom permit or drop the pending count here.
        // Red is near-certain rather than forced: the buggy give-back runs in the waiter's wrap-up
        // slice with no edge to gate strictly after it, so Loop.repeat samples the post-give-back
        // state; green is sound (on correct code the asserted values are the stable initial state).
        "interrupting a parked waiter returns nothing to the rate".notJs.notWasm in {
            (for
                meter <- Meter.initRateLimiter(1, 1.hour)
                _     <- meter.run(())
                _     <- assertEventually(Abort.run(meter.availablePermits).map(_ == Result.succeed(0)))
                w     <- Fiber.initUnscoped(meter.run(()))
                _     <- assertEventually(Abort.run(meter.pendingWaiters).map(_ == Result.succeed(1)))
                _     <- assertEventually(w.waiters.map(_ == 1))
                _     <- w.interrupt(panic)
                _     <- w.getResult
                // The registration give-back runs in the interrupted waiter's finalizer slice; wait for
                // it to settle to 0. Crucially no rate is minted: availablePermits and tryRun below stay
                // at their initial values (the give-back touches only the waiter field, never a permit).
                _  <- assertEventually(Abort.run(meter.pendingWaiters).map(_ == Result.succeed(0)))
                ap <- Abort.run(meter.availablePermits)
                tr <- meter.tryRun(())
            yield assert(
                ap == Result.succeed(0) && tr.isEmpty,
                s"interrupted rate waiter minted rate: availablePermits=$ap tryRun=$tr"
            ))
                .handle(Loop.repeat(20))
                .unit
        }

        "use" in {
            Meter.useRateLimiter(2, 1.milli)(meter => Kyo.zip(meter.closed, meter)).map:
                case (isClosed1, meter) =>
                    meter.closed.map: isClosed2 =>
                        assert(!isClosed1 && isClosed2)
        }

        "ok" in {
            for
                t  <- Meter.initRateLimiter(2, 1.milli)
                v1 <- t.run(2)
                v2 <- t.run(3)
            yield assert(v1 == 2 && v2 == 3)
        }
        "one loop" in {
            // A long replenish period means no permit is replenished during the test, so the limiter
            // grants exactly its initial `rate` permits and then parks the loop. takeExactly is a
            // happens-before on those runs (no wall-clock window, so no scheduler-starvation flake);
            // the empty poll proves the limiter parked the loop instead of running unbounded.
            for
                meter <- Meter.initRateLimiter(10, 1.hour)
                ch    <- Channel.init[Unit](1000)
                f1    <- Fiber.initUnscoped(loop(meter, ch))
                runs  <- ch.takeExactly(10)
                extra <- ch.poll
                _     <- f1.interrupt(panic)
            yield assert(runs.size == 10 && extra.isEmpty)
        }
        "two loops" in {
            // Two fibers share one limiter: together they consume exactly the initial `rate` permits,
            // then both park (the long period means no replenishment happens during the test).
            for
                meter <- Meter.initRateLimiter(10, 1.hour)
                ch    <- Channel.init[Unit](1000)
                f1    <- Fiber.initUnscoped(loop(meter, ch))
                f2    <- Fiber.initUnscoped(loop(meter, ch))
                runs  <- ch.takeExactly(10)
                extra <- ch.poll
                _     <- f1.interrupt(panic)
                _     <- f2.interrupt(panic)
            yield assert(runs.size == 10 && extra.isEmpty)
        }
        "replenish doesn't overflow".notJs in {
            // Consume every permit, then advance virtual time past several replenish periods. `release()` is capped at `rate`, so no number
            // of firings pushes availablePermits past it: it refills to exactly `rate`. Excluded on JS: manual-time periodic loops need interleaving the single thread lacks.
            Clock.withTimeControl { control =>
                for
                    meter     <- Meter.initRateLimiter(5, 5.millis)
                    _         <- Loop.repeat(5)(meter.run(()))
                    drained   <- meter.availablePermits
                    _         <- Loop.repeat(20)(control.advance(5.millis))
                    available <- meter.availablePermits
                yield assert(drained == 0 && available == 5)
            }
        }
    }

    "pipeline" - {

        "run" in {
            // The pipeline acquires the rate limiter (2 permits) then the mutex; throughput is capped
            // by the rate limiter, so the two fibers together run exactly twice, then park (long period).
            for
                meter <- Meter.pipeline(Meter.initRateLimiter(2, 1.hour), Meter.initMutex)
                ch    <- Channel.init[Unit](1000)
                f1    <- Fiber.initUnscoped(loop(meter, ch))
                f2    <- Fiber.initUnscoped(loop(meter, ch))
                runs  <- ch.takeExactly(2)
                extra <- ch.poll
                _     <- f1.interrupt(panic)
                _     <- f2.interrupt(panic)
            yield assert(runs.size == 2 && extra.isEmpty)
        }

        "tryRun" in {
            for
                meter <- Meter.pipeline(Meter.initRateLimiter(2, 10.millis), Meter.initMutex)
                f1    <- Fiber.initUnscoped(meter.run(Async.never))
                _     <- assertEventually(meter.tryRun(()).map(_.isEmpty))
                _     <- f1.interrupt(panic)
            yield ()
        }
    }

    "reentrancy" - {
        "mutex" - {
            "reentrant by default" in {
                for
                    mutex <- Meter.initMutex
                    result <- mutex.run {
                        mutex.run {
                            mutex.run(42)
                        }
                    }
                yield assert(result == 42)
            }

            "non-reentrant" in {
                for
                    meter <- Meter.initMutex(reentrant = false)
                    f     <- Fiber.initUnscoped(meter.run(meter.run(42)))
                    // the outer run holds the permit; the inner run cannot reenter and never completes
                    blocked <- Abort.run[Timeout](Async.timeout(blockedWindow)(f.get))
                    _       <- f.interrupt
                    result  <- f.getResult
                yield assert(blocked.isFailure && result.isPanic)
            }

            "nested forked fiber can't reenter" in {
                for
                    meter <- Meter.initMutex
                    (blocked, result) <- meter.run {
                        meter.run {
                            for
                                f <- Fiber.initUnscoped(meter.run(42))
                                // the forked fiber cannot reenter the held meter and never completes
                                blocked <- Abort.run[Timeout](Async.timeout(blockedWindow)(f.get))
                                _       <- f.interrupt
                                result  <- f.getResult
                            yield (blocked, result)
                        }
                    }
                yield assert(blocked.isFailure && result.isPanic)
            }
        }

        "semaphore" - {
            "reentrant by default" in {
                for
                    sem <- Meter.initSemaphore(1)
                    result <- sem.run {
                        sem.run {
                            sem.run(42)
                        }
                    }
                yield assert(result == 42)
            }

            "non-reentrant" in {
                for
                    meter <- Meter.initSemaphore(1, reentrant = false)
                    f     <- Fiber.initUnscoped(meter.run(meter.run(42)))
                    // the outer run holds the permit; the inner run cannot reenter and never completes
                    blocked <- Abort.run[Timeout](Async.timeout(blockedWindow)(f.get))
                    _       <- f.interrupt
                    result  <- f.getResult
                yield assert(blocked.isFailure && result.isPanic)
            }

            "nested forked fiber can't reenter" in {
                for
                    meter <- Meter.initSemaphore(1)
                    (blocked, result) <- meter.run {
                        meter.run {
                            for
                                f <- Fiber.initUnscoped(meter.run(42))
                                // the forked fiber cannot reenter the held meter and never completes
                                blocked <- Abort.run[Timeout](Async.timeout(blockedWindow)(f.get))
                                _       <- f.interrupt
                                result  <- f.getResult
                            yield (blocked, result)
                        }
                    }
                yield assert(blocked.isFailure && result.isPanic)
            }
        }

        "rate limiter" - {
            "reentrant by default" in {
                for
                    rateLimiter <- Meter.initRateLimiter(1, 60.seconds)
                    result <- rateLimiter.run {
                        rateLimiter.run {
                            rateLimiter.run(42)
                        }
                    }
                yield assert(result == 42)
            }

            "non-reentrant" in {
                for
                    meter <- Meter.initRateLimiter(1, 60.seconds, reentrant = false)
                    f     <- Fiber.initUnscoped(meter.run(meter.run(42)))
                    // the outer run holds the permit; the inner run cannot reenter and never completes
                    blocked <- Abort.run[Timeout](Async.timeout(blockedWindow)(f.get))
                    _       <- f.interrupt
                    result  <- f.getResult
                yield assert(blocked.isFailure && result.isPanic)
            }

            "nested forked fiber can't reenter" in {
                for
                    meter <- Meter.initRateLimiter(1, 60.seconds)
                    (blocked, result) <- meter.run {
                        meter.run {
                            for
                                f <- Fiber.initUnscoped(meter.run(42))
                                // the forked fiber cannot reenter the held meter and never completes
                                blocked <- Abort.run[Timeout](Async.timeout(blockedWindow)(f.get))
                                _       <- f.interrupt
                                result  <- f.getResult
                            yield (blocked, result)
                        }
                    }
                yield assert(blocked.isFailure && result.isPanic)
            }
        }

        "pipeline" - {
            "reentrant when all components are reentrant" in {
                for
                    mutex       <- Meter.initMutex
                    sem         <- Meter.initSemaphore(1)
                    rateLimiter <- Meter.initRateLimiter(1, 60.seconds)
                    pipeline    <- Meter.pipeline(mutex, sem, rateLimiter)
                    result <- pipeline.run {
                        pipeline.run {
                            pipeline.run(42)
                        }
                    }
                yield assert(result == 42)
            }

            "non-reentrant when any component is non-reentrant" in {
                for
                    mutex       <- Meter.initMutex
                    sem         <- Meter.initSemaphore(1, reentrant = false)
                    rateLimiter <- Meter.initRateLimiter(1, 60.seconds)
                    pipeline    <- Meter.pipeline(mutex, sem, rateLimiter)
                    f <- Fiber.initUnscoped(pipeline.run {
                        pipeline.run(42)
                    })
                    // the non-reentrant component blocks the inner run, which never completes
                    blocked <- Abort.run[Timeout](Async.timeout(blockedWindow)(f.get))
                    _       <- f.interrupt
                    result  <- f.getResult
                yield assert(blocked.isFailure && result.isPanic)
            }

            "nested forked fiber can't reenter" in {
                for
                    mutex       <- Meter.initMutex
                    sem         <- Meter.initSemaphore(1)
                    rateLimiter <- Meter.initRateLimiter(1, 60.seconds)
                    meter       <- Meter.pipeline(mutex, sem, rateLimiter)
                    (blocked, result) <- meter.run {
                        meter.run {
                            for
                                f <- Fiber.initUnscoped(meter.run(42))
                                // the forked fiber cannot reenter the held meter and never completes
                                blocked <- Abort.run[Timeout](Async.timeout(blockedWindow)(f.get))
                                _       <- f.interrupt
                                result  <- f.getResult
                            yield (blocked, result)
                        }
                    }
                yield assert(blocked.isFailure && result.isPanic)
            }
        }
    }

end MeterTest
