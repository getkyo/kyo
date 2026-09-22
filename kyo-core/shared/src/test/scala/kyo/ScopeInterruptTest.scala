package kyo

import java.util.concurrent.atomic.AtomicInteger as JAtomicInteger
import kyo.kernel.Bracket

/** Interrupts landing inside an acquire, and scope exit over a child still holding a resource.
  *
  * Each acquire leaf interrupts its own fiber and then takes one more step before producing its value, so the interrupt is pending when
  * that step completes. An acquire whose value arrives in the same node as the interrupt request always releases, and the window a
  * multi-node acquire opens is a race, so the leaves run rounds rather than once.
  *
  * `ScopeTest`'s "acquire-time registration (#1820)" block covers `Scope.acquireRelease`; these cover `Sync.acquireReleaseWith` and
  * `Scope.acquire`.
  */
class ScopeInterruptTest extends kyo.test.Test[Any]:

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

        // A bracket is the one owner that takes what its acquire produced with nothing schedulable in between, so a
        // resource an acquire holds until the step that hands it on is itself a bracket, nested as the acquire: its
        // end runs in place as the value flows to the outer bracket. `Sync.ensure` and `Sync.acquireReleaseWith`
        // bind after their body, so in that position the interrupt would park at the bind, with the value in front
        // of it and nothing owning it.
        "a bracket nested as the acquire of another hands what its use produced to the outer bracket" in {
            val rounds = 200
            for
                acquired <- AtomicInt.init(0)
                released <- AtomicInt.init(0)
                freed    <- AtomicInt.init(0)
                _        <- selfInterrupting(rounds) { self =>
                    Bracket(
                        Bracket(Sync.defer(0)) { _ =>
                            Sync.defer {
                                // Unsafe: the interrupt is requested from inside the step that produces
                                // the acquire's value.
                                import AllowUnsafe.embrace.danger
                                discard(self.unsafe.interrupt())
                                acquired.unsafe.incrementAndGet()
                            }
                        }((_, _) => discard(freed.unsafe.incrementAndGet()(using AllowUnsafe.embrace.danger)))
                    )(_ => Sync.defer(()))((_, _) => discard(released.unsafe.incrementAndGet()(using AllowUnsafe.embrace.danger)))
                }
                _   <- assertEventually(Kyo.zip(acquired.get, released.get).map((a, r) => a == r))
                acq <- acquired.get
                rel <- released.get
                fin <- freed.get
            yield assert(
                acq == rel && acq == fin && acq > 0,
                s"$acq acquires ran to their end, $fin inner brackets released and $rel outer releases ran"
            )
            end for
        }

        // The acquire runs under a Sync.ensure of its own and interrupts itself as its inner value arrives. The
        // inner Sync.ensure is a region from the start, so its finalizer always runs (fin == acq) on every platform.
        // Whether the interrupt stops the acquire before its value reaches the outer bracket is a race, and the
        // outcome differs by platform and by run: the JVM and Native preempt finely, so the value usually stops
        // short and the bracket owns nothing (rel near 0); JS usually lets the acquire complete, so the bracket
        // takes it and releases it (rel near acq), and a run may land anywhere between.
        "an acquire under its own Sync.ensure runs that finalizer, and the bracket never over-releases" in {
            val rounds = 200
            for
                acquired <- AtomicInt.init(0)
                released <- AtomicInt.init(0)
                ended    <- AtomicInt.init(0)
                _        <- selfInterrupting(rounds) { self =>
                    Sync.acquireReleaseWith {
                        Sync.ensure(ended.incrementAndGet.unit) {
                            Sync.defer {
                                // Unsafe: the interrupt is requested from inside the step that produces
                                // the acquire's value.
                                import AllowUnsafe.embrace.danger
                                discard(self.unsafe.interrupt())
                                acquired.unsafe.incrementAndGet()
                            }
                        }
                    }(_ => released.incrementAndGet.unit)(_ => Sync.defer(()))
                }
                _   <- assertEventually(Kyo.zip(acquired.get, ended.get).map((a, e) => a == e))
                acq <- acquired.get
                rel <- released.get
                fin <- ended.get
            yield assert(
                acq == fin && rel <= acq && acq > 0,
                s"$acq acquires ran to their end, $fin of their own regions released and $rel bracket releases ran"
            )
            end for
        }

        "an acquire interrupted a step before its value produces nothing and releases nothing" in {
            val rounds = 200
            for
                acquired <- AtomicInt.init(0)
                released <- AtomicInt.init(0)
                _        <- selfInterrupting(rounds) { self =>
                    Sync.acquireReleaseWith {
                        Sync.defer {
                            import AllowUnsafe.embrace.danger
                            discard(self.unsafe.interrupt())
                        }.andThen(acquired.incrementAndGet)
                    }(_ => released.incrementAndGet.unit)(_ => Sync.defer(()))
                }
                acq <- acquired.get
                rel <- released.get
            yield assert(acq == 0 && rel == 0, s"$acq acquires ran after their interrupt and $rel releases ran")
            end for
        }

        "Sync.acquireReleaseWith still releases what the acquire produced (value in the same node as the interrupt)" in {
            val rounds = 200
            for
                acquired <- AtomicInt.init(0)
                released <- AtomicInt.init(0)
                _        <- selfInterrupting(rounds) { self =>
                    Sync.acquireReleaseWith {
                        Sync.defer {
                            // Unsafe: the interrupt must be requested from inside the acquire, in the step that
                            // produces its value, which is not an effectful position.
                            import AllowUnsafe.embrace.danger
                            discard(self.unsafe.interrupt())
                            acquired.unsafe.incrementAndGet()
                        }
                    }(_ => released.incrementAndGet.unit)(_ => Sync.defer(()))
                }
                _   <- assertEventually(Kyo.zip(acquired.get, released.get).map((a, r) => a == r))
                acq <- acquired.get
                rel <- released.get
            yield assert(acq == rel && acq > 0, s"$acq acquires ran to their end and $rel of them were released")
            end for
        }

        "Scope.acquire closes the handle it opened (value in the same node as the interrupt)" in {
            val rounds = 200
            val opened = new JAtomicInteger(0)
            val closed = new JAtomicInteger(0)
            for
                _ <- selfInterrupting(rounds) { self =>
                    Scope.run {
                        Scope.acquire {
                            Sync.defer {
                                // Unsafe: the interrupt is requested from inside the step that produces
                                // the acquire's value.
                                import AllowUnsafe.embrace.danger
                                discard(self.unsafe.interrupt())
                                discard(opened.incrementAndGet())
                                new Handle(closed)
                            }
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

    // The acquire is a join. Its value arrives in the promise while the acquiring fiber is parked, and the
    // interrupt lands before the fiber resumes: the promise's callbacks run last-registered first, so one
    // registered after the park runs before the fiber's own wakeup.
    "an acquire abandoned before it resumed with its value owns nothing" in {
        for
            released <- AtomicInt.init(0)
            child    <- Promise.init[Int, Any]
            parent   <- Fiber.initUnscoped {
                Scope.run {
                    Scope.acquireRelease(child.get)(_ => released.incrementAndGet.unit).andThen(Async.never)
                }
            }
            _   <- assertEventually(child.waiters.map(_ >= 1))
            _   <- child.onComplete(_ => parent.interrupt.unit)
            _   <- child.complete(Result.succeed(42))
            res <- parent.getResult
            r   <- released.get
        yield
            assert(res.isPanic, s"the abandonment did not settle the fiber with the interrupt: $res")
            assert(r == 0, s"a release ran for a value the acquire never took: $r")
        end for
    }

    "an acquire joining a fiber, abandoned before it resumed with the fiber's value, owns nothing" in {
        for
            released <- AtomicInt.init(0)
            child    <- Promise.init[Int, Any]
            inner    <- Fiber.initUnscoped(child.get)
            parent   <- Fiber.initUnscoped {
                Scope.run {
                    Scope.acquireRelease(inner.get)(_ => released.incrementAndGet.unit).andThen(Async.never)
                }
            }
            // `inner` is a fiber parked on `child`, and a fiber's own join link counts as a waiter on its promise, so one
            // waiter is there before the parent parks.
            _   <- assertEventually(inner.waiters.map(_ >= 2))
            _   <- inner.onComplete(_ => parent.interrupt.unit)
            _   <- child.complete(Result.succeed(42))
            res <- parent.getResult
            r   <- released.get
        yield
            assert(res.isPanic, s"the abandonment did not settle the fiber with the interrupt: $res")
            assert(r == 0, s"a release ran for a value the acquire never took: $r")
        end for
    }

    "a permit a child takes after its owner was abandoned is returned through the owner's closed scope" in {
        for
            permits <- Channel.init[Unit](1)
            _       <- permits.put(())
            gate    <- Promise.init[Unit, Any]
            parent  <- Fiber.initUnscoped {
                Scope.run {
                    Fiber.initUnscoped(gate.get.andThen(Scope.acquireRelease(permits.take)(_ => permits.put(())).unit))
                        .andThen(Async.never)
                }
            }
            _ <- assertEventually(gate.waiters.map(_ >= 1))
            // The owner completes once its scope has closed, so the child's value arrives when nobody owns it.
            _ <- parent.onComplete(_ => gate.completeUnitDiscard)
            _ <- parent.interrupt
            _ <- parent.getResult
            _ <- assertEventually(Abort.run[Closed](permits.size).map(_.exists(_ == 1)))
        yield succeed
        end for
    }

    // The supported shape for a resource produced on one fiber and owned by another: the producing fiber registers
    // the release in the step the value arrives in, into the owner's scope, which it reaches through the context.
    "the producing fiber's registration returns the permit when the owner is abandoned after the value" in {
        for
            permits    <- Channel.init[Unit](1)
            _          <- permits.put(())
            registered <- Latch.init(1)
            parent     <- Fiber.initUnscoped {
                Scope.run {
                    Async.timeout(1.hour) {
                        Scope.acquireRelease(permits.take)(_ => permits.put(())).andThen(registered.release)
                    }.andThen(Async.never)
                }
            }
            _ <- registered.await
            _ <- parent.interrupt
            _ <- parent.getResult
            _ <- assertEventually(Abort.run[Closed](permits.size).map(_.exists(_ == 1)))
        yield succeed
        end for
    }

    "the producing fiber's registration returns the permit when the owner's scope closed first" in {
        for
            permits <- Channel.init[Unit](1)
            _       <- permits.put(())
            gate    <- Promise.init[Unit, Any]
            parent  <- Fiber.initUnscoped {
                Scope.run {
                    Async.timeout(1.hour) {
                        gate.get.andThen(Scope.acquireRelease(permits.take)(_ => permits.put(())))
                    }.andThen(Async.never)
                }
            }
            _ <- assertEventually(gate.waiters.map(_ >= 1))
            _ <- parent.onComplete(_ => gate.completeUnitDiscard)
            _ <- parent.interrupt
            _ <- parent.getResult
            _ <- assertEventually(Abort.run[Closed](permits.size).map(_.exists(_ == 1)))
        yield succeed
        end for
    }

    "Scope.run waits for a scoped fiber to release the bracket it is inside" in {
        // The child must hold the bracket when the scope starts exiting, or the release happens for the wrong
        // reason.
        for
            entered  <- AtomicBoolean.init(false)
            log      <- AtomicRef.init(Chunk.empty[String])
            released <- Latch.init(1)
            _        <- Scope.run {
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
    // running while the ensure that would interrupt it is one dispatch away, and an interrupt landing in that gap
    // leaves nothing to interrupt the child. The gap is inside the spawn, so it is raced rather than held open.
    "Fiber.use interrupts the fiber it spawned when an interrupt lands on the spawn" in {
        val rounds = 40
        for
            exercised <- AtomicInt.init(0)
            // Half the rounds wait for the child to be running, exercising the orphan property; the other half
            // interrupt at the spawn to probe the gap.
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
                    // A deadline here would make a slow worker read as a leak, while an orphan
                    // never clears the flag.
                    _ <- if ran then assertEventually(childAlive.get.map(!_)) else Kyo.unit
                    _ <- gate.release
                    _ <- if ran then exercised.incrementAndGet.unit else Kyo.unit
                yield ()
                end for
            }
            hit <- exercised.get
        yield assert(hit > 0, s"none of the $rounds rounds got the child running, so nothing was raced against the spawn")
        end for
    }

    "Fiber.init interrupts and awaits the fiber it spawned when the interrupt lands on the spawn" in {
        val rounds = 40
        for
            exercised <- AtomicInt.init(0)
            _         <- Kyo.foreachDiscard(1 to rounds) { round =>
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
                    ran    <- started.done
                    _      <- if ran then assertEventually(childAlive.get.map(!_)) else Kyo.unit
                    _      <- gate.release
                    _      <- if ran then exercised.incrementAndGet.unit else Kyo.unit
                yield ()
                end for
            }
            hit <- exercised.get
        yield assert(hit > 0, s"none of the $rounds rounds got the child running, so nothing was raced against the spawn")
        end for
    }

    // A fiber abandoned while parked runs its finalizers through the abandonment walk, not by being resumed. The
    // uninterruptible promise makes the interrupt cascade a no-op, leaving the walk as the only thing that can run
    // the finalizer; completing it afterwards proves the queued continuation stays dead.
    "a fiber interrupted while parked on an uninterruptible promise still runs its scope finalizers" in {
        for
            finalized <- AtomicInt.init(0)
            resumed   <- AtomicBoolean.init(false)
            promise   <- Sync.Unsafe.defer(Promise.Unsafe.initUninterruptible[Unit, Any]().safe)
            fiber     <- Fiber.initUnscoped {
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
            _    <- assertEventually(finalized.get.map(_ == 1))
            fin  <- finalized.get
            woke <- resumed.get
        yield
            assert(fin == 1, "the scope finalizer never ran for a fiber abandoned while parked")
            assert(!woke, "the interrupted continuation ran after the promise was completed")
        end for
    }

    // A remainder handed out by a peel (`Emit.runFirst`) carries the regions the peeled body had installed. Handed
    // to a child fiber and run there while the peeling scope ends, the resource stays with the child's run: the
    // child either completes its use with the resource still held, releasing it at its own exit, or is refused
    // with Closed.
    "a peeled remainder running on a child fiber is not released under it when the peeling scope ends" in {
        for
            released <- AtomicBoolean.init(false)
            entered  <- Latch.init(1)
            gate     <- Latch.init(1)
            child    <- Scope.run {
                Emit.runFirst[Int] {
                    Sync.ensure(released.set(true)) {
                        Emit.value(1).andThen(entered.release).andThen(gate.await).andThen(released.get)
                    }
                }.map { case (_, rest) =>
                    Fiber.initUnscoped(Abort.run[Closed](Emit.run[Int](rest(())).map(_._2))).map { child =>
                        entered.await.andThen(child)
                    }
                }
            }
            _      <- gate.release
            result <- child.getResult
        yield result match
            case Result.Success(Result.Success(true)) =>
                fail("the child ran its use after the peeling scope released the resource under it")
            case Result.Success(Result.Success(false)) | Result.Success(Result.Failure(_: Closed)) | Result.Panic(_: Closed) =>
                succeed
            case other =>
                fail(s"unexpected outcome $other")
        end for
    }

end ScopeInterruptTest
