package kyo

import java.util.concurrent.atomic.AtomicInteger as JAtomicInteger

/** Interrupts landing inside an acquire, and scope exit over a child still holding a resource.
  *
  * Each acquire leaf interrupts its own fiber and then takes one more step before producing its value, so the interrupt is pending when
  * that step completes. Both parts are required: an acquire whose value arrives in the same node as the interrupt request always
  * releases, and the window a multi-node acquire opens is a race, so the leaves run rounds rather than once.
  *
  * `ScopeTest`'s "acquire-time registration (#1820)" block covers `Scope.acquireRelease`; these cover `Sync.acquireReleaseWith` and
  * `Scope.acquire`.
  */
class ScopeInterruptTest extends kyo.test.Test[Any]:

    /** A closeable that records its own close. */
    final class Handle(closes: JAtomicInteger) extends java.lang.AutoCloseable:
        def close(): Unit = discard(closes.incrementAndGet())

    /** Runs `body` on its own fiber `rounds` times, handing each fiber to its own body so the acquire can interrupt itself. */
    def selfInterrupting(rounds: Int)(body: Fiber[Unit, Any] => Unit < (Sync & Async))(using Frame): Unit < (Sync & Async) =
        Loop.indexed { i =>
            if i >= rounds then Loop.done
            else
                Promise.init[Fiber[Unit, Any], Any].map { handoff =>
                    Fiber.initUnscoped(handoff.get.map(body)).map { fiber =>
                        handoff.complete(Result.succeed(fiber)).andThen(fiber.getResult)
                    }.andThen(Loop.continue)
                }
        }

    "an interrupt landing while the acquire's last step runs" - {

        // Bracket installs its region as the acquire is applied, so an abandonment that finds the acquired
        // value has something to release it with.
        "Sync.acquireReleaseWith still releases what the acquire produced" in {
            val rounds = 200
            for
                acquired <- AtomicInt.init(0)
                released <- AtomicInt.init(0)
                _ <- selfInterrupting(rounds) { self =>
                    Sync.acquireReleaseWith {
                        Sync.defer {
                            // Unsafe: the interrupt has to be requested from inside the acquire, before it
                            // returns, which is not an effectful position.
                            import AllowUnsafe.embrace.danger
                            discard(self.unsafe.interrupt())
                        }.andThen(acquired.incrementAndGet)
                    }(_ => released.incrementAndGet.unit)(_ => Sync.defer(()))
                }
                _   <- assertEventually(Kyo.zip(acquired.get, released.get).map((a, r) => a == r))
                acq <- acquired.get
                rel <- released.get
            yield assert(acq == rel && acq > 0, s"$acq acquires ran to their end and $rel of them were released")
            end for
        }

        // Scope.acquire is acquireRelease(resource)(_.close()); what it adds is the close path, the release
        // the caller never wrote.
        "Scope.acquire closes the handle it opened" in {
            val rounds = 200
            val opened = new JAtomicInteger(0)
            val closed = new JAtomicInteger(0)
            for
                _ <- selfInterrupting(rounds) { self =>
                    Scope.run {
                        Scope.acquire {
                            Sync.defer {
                                // Unsafe: see the leaf above.
                                import AllowUnsafe.embrace.danger
                                discard(self.unsafe.interrupt())
                            }.andThen(Sync.defer {
                                discard(opened.incrementAndGet())
                                new Handle(closed)
                            })
                        }.andThen(Sync.defer(()))
                    }
                }
                _ <- assertEventually(Sync.defer(opened.get() == closed.get()))
                o <- Sync.defer(opened.get())
                c <- Sync.defer(closed.get())
            yield assert(o == c && o > 0, s"$o handles were opened and $c of them were closed")
            end for
        }
    }

    "Scope.run waits for a scoped fiber to release the bracket it is inside" in {
        // The child must hold the bracket when the scope starts exiting, or the release happens for the wrong
        // reason. It parks rather than spinning: the region is installed before the body runs, and the park ends
        // when the scope's exit interrupts it.
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

    // Fiber.use is `initUnscoped(v).map(fiber => Sync.ensure(fiber.interrupt)(f(fiber)))`: the fiber is already
    // running while the ensure that would interrupt it is one dispatch away. An interrupt landing in that gap
    // leaves nothing to interrupt the child and nothing for an abandonment to walk, since the ensure's region
    // does not exist until the map runs. The gap is inside the spawn, so it is raced rather than held open.
    "Fiber.use interrupts the fiber it spawned when an interrupt lands on the spawn" in {
        val rounds = 40
        for
            exercised <- AtomicInt.init(0)
            // Half the rounds wait for the child to be running before interrupting, so the orphan property is
            // exercised; the other half interrupt at the spawn to probe the gap.
            _ <- Kyo.foreachDiscard(1 to rounds) { round =>
                for
                    started    <- Promise.init[Unit, Any]
                    gate       <- Latch.init(1)
                    childAlive <- AtomicBoolean.init(false)
                    child = (Sync.ensure(childAlive.set(false)) {
                        childAlive.set(true).andThen(started.completeUnitDiscard).andThen(gate.await)
                    }: Unit < (Sync & Async))
                    parent <- Fiber.initUnscoped(Fiber.use[Nothing, Unit, Any, Any](child)(_ => started.get))
                    _      <- if round % 2 == 0 then started.get else Kyo.unit
                    _      <- parent.interrupt
                    _      <- parent.getResult
                    // A child that never started holds nothing and tears nothing down.
                    ran <- started.done
                    // The orphan check. A deadline here would be an input to the verdict rather than a test
                    // timeout, so a slow worker would be recorded as a leak; an orphan never clears the flag.
                    _ <- if ran then assertEventually(childAlive.get.map(!_)) else Kyo.unit
                    _ <- gate.release
                    _ <- if ran then exercised.incrementAndGet.unit else Kyo.unit
                yield ()
                end for
            }
            hit <- exercised.get
        // A round whose child never started proves nothing, so the leaf has to know the race lands sometimes.
        yield assert(hit > 0, s"none of the $rounds rounds got the child running, so nothing was raced against the spawn")
        end for
    }

    // The same gap at the other spawn. Fiber.init registers the interrupt that stops the child as the acquire's
    // value arrives, which leaves an interrupt landing on the spawn itself uncovered.
    "Fiber.init interrupts and awaits the fiber it spawned when the interrupt lands on the spawn" in {
        val rounds = 40
        for
            exercised <- AtomicInt.init(0)
            // Half the rounds wait for the child to be running before interrupting, so the orphan property is
            // exercised; the other half interrupt at the spawn to probe the gap.
            _ <- Kyo.foreachDiscard(1 to rounds) { round =>
                for
                    started    <- Promise.init[Unit, Any]
                    gate       <- Latch.init(1)
                    childAlive <- AtomicBoolean.init(false)
                    child = (Sync.ensure(childAlive.set(false)) {
                        childAlive.set(true).andThen(started.completeUnitDiscard).andThen(gate.await)
                    }: Unit < (Sync & Async))
                    parent <- Fiber.initUnscoped(Scope.run(Fiber.init(child).andThen(started.get)))
                    _      <- if round % 2 == 0 then started.get else Kyo.unit
                    _      <- parent.interrupt
                    _      <- parent.getResult
                    // A child that never started holds nothing and tears nothing down.
                    ran <- started.done
                    _   <- if ran then assertEventually(childAlive.get.map(!_)) else Kyo.unit
                    _   <- gate.release
                    _   <- if ran then exercised.incrementAndGet.unit else Kyo.unit
                yield ()
                end for
            }
            hit <- exercised.get
        yield assert(hit > 0, s"none of the $rounds rounds got the child running, so nothing was raced against the spawn")
        end for
    }

    // A fiber abandoned while parked runs its finalizers through the abandonment walk, not by being resumed.
    // An uninterruptible promise makes the interrupt cascade a no-op, leaving the walk as the only thing that
    // can run the finalizer; completing the promise afterwards proves the queued continuation stays dead.
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
