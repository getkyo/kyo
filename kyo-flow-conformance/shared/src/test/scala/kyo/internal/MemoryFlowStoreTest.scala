package kyo.internal

import kyo.*

class MemoryFlowStoreTest extends FlowStoreConformanceTest:
    def makeStore(using Frame): FlowStore < (Async & Scope) = FlowStore.initMemory

    // A 5s blocking poll, woken at 1s by a write it cannot claim, after the wall clock stepped by `shift`. The wake is what makes
    // the poll judge the time it has left, so it is where a deadline read from the wall would move with the step. Answers the
    // poll's answer with the monotonic time it answered at, or Absent if it was still waiting 400s later.
    private def pollAcrossWallStep(shift: Instant => Instant)(using
        Frame
    ): Maybe[(Seq[FlowStore.Claimed], Duration)] < (Async & Scope & Abort[Any]) =
        Clock.withTimeControl { control =>
            Clock.get.map { controlled =>
                val stepped = new java.util.concurrent.atomic.AtomicBoolean(false)
                val clock   = Clock(Clock.Unsafe.withWall(controlled.unsafe) { () =>
                    val now = controlled.unsafe.now()(using AllowUnsafe.embrace.danger)
                    if stepped.get() then shift(now) else now
                })
                Clock.let(clock) {
                    for
                        store <- makeStore
                        poll  <- Fiber.initUnscoped(
                            store.claimReady(served, ex1, lease, 10, 5.seconds).map(claimed => Clock.nowMonotonic.map((claimed, _)))
                        )
                        _   <- control.awaitPendingSleepers(1)
                        _   <- Sync.defer(stepped.set(true))
                        _   <- control.advance(1.second)
                        now <- Clock.now
                        _   <- store.createExecutionIfAbsent(eid2, Flow.Status.Running, Flow.Event.Created(wf2, eid2, now), "", Dict.empty)
                        _   <- Loop(0) { ticks =>
                            settledOrRearmed(control, Seq(poll)).map { finished =>
                                if finished || ticks >= 400 then Loop.done(())
                                else control.advance(1.second).andThen(Loop.continue(ticks + 1))
                            }
                        }
                        done   <- poll.done
                        answer <-
                            if done then poll.get.map(Maybe(_))
                            else Kyo.lift(Maybe.empty[(Seq[FlowStore.Claimed], Duration)])
                    yield answer
                }
            }
        }

    // Due at 5s. On a single-threaded runtime the driver can advance one more tick before the poll's fiber records its answer, so
    // the bound is "not early, and nowhere near the hour-long step": a wall-clock deadline answers at 1s forward and not within 400s back.
    private def answeredOnTime(answer: Maybe[(Seq[FlowStore.Claimed], Duration)]): Boolean =
        answer.exists((claimed, at) => claimed.isEmpty && at >= 5.seconds && at < 1.minute)

    "a blocking claimReady answers on monotonic time when the wall clock steps back" in {
        pollAcrossWallStep(_ - 1.hour).map(answer => assert(answeredOnTime(answer), s"answered $answer"))
    }

    "a blocking claimReady answers on monotonic time when the wall clock steps forward" in {
        pollAcrossWallStep(_ + 1.hour).map(answer => assert(answeredOnTime(answer), s"answered $answer"))
    }

    // `controlled`, counting every wall reading. A poll reads the wall once per time it asks the database, and only then, so the
    // count is how many times a poll running under this clock asked.
    private def countingAsks(controlled: Clock, asks: java.util.concurrent.atomic.AtomicInteger): Clock =
        Clock(Clock.Unsafe.withWall(controlled.unsafe) { () =>
            discard(asks.incrementAndGet())
            controlled.unsafe.now()(using AllowUnsafe.embrace.danger)
        })

    "a write made before a poll starts does not wake that poll" in {
        Clock.withTimeControl { control =>
            val asks = new java.util.concurrent.atomic.AtomicInteger(0)
            Clock.get.map(controlled => makeStore.map(store => (controlled, store))).map { (controlled, store) =>
                for
                    // A row the poll does not serve, so the write changes nothing the poll could claim.
                    _    <- mkExecution(store, eid2, wf2, Flow.Status.Running)
                    poll <- Fiber.init(Clock.let(countingAsks(controlled, asks))(store.claimReady(served, ex1, lease, 10, 2.seconds)))
                    _    <- control.awaitPendingSleepers(1)
                    done <- advanceUntilAnswered(control, 2.seconds, Seq(poll))
                    got  <- poll.get
                yield
                    assert(done, "the poll never answered")
                    assert(got.isEmpty)
                    assert(
                        asks.get() == 2,
                        s"the write landed before the poll began and its first ask saw it, so nothing changed while the poll waited: it " +
                            s"asks on entry and once at its deadline, and asked ${asks.get()} times"
                    )
                end for
            }
        }
    }

    "one write wakes every blocked poll, not just one of them" in {
        Clock.withTimeControl { control =>
            makeStore.map { store =>
                for
                    first <- Fiber.init(store.claimReady(served, ex1, lease, 10, 1.hour))
                    _     <- control.awaitPendingSleepers(1)
                    // Blocked second, so a wake handed to one waiter at a time reaches the first poll, which has nothing to claim.
                    second <- Fiber.init(store.claimReady(Set((wf2, "")), ex2, lease, 10, 1.hour))
                    _      <- control.awaitPendingSleepers(2)
                    _      <- mkExecution(store, eid2, wf2, Flow.Status.Running)
                    // The clock never moves, so only the write can end the second poll's hour-long wait.
                    got       <- second.get
                    firstDone <- first.done
                yield
                    assert(got.map(_.state.executionId) == Seq(eid2))
                    assert(!firstDone, "the first poll serves nothing the write created, so it keeps waiting")
                end for
            }
        }
    }

    "a poll that finds its time up after a wake asks once more before answering empty" in {
        Clock.withTimeControl { control =>
            // The poll's clock doubles `control`'s and lets the reading jump. Doubled because a scaled clock derives its monotonic
            // time from its wall reading, so the one hook below moves both of the poll's clocks at once. Armed, the reading jumps
            // right after the next one is taken: time passes between the poll's first two reads after the wake and at no other
            // point, which is the interleaving a busy scheduler produces and a single-fiber test otherwise cannot.
            val armed                     = new java.util.concurrent.atomic.AtomicInteger(0)
            def hooked(controlled: Clock) = Clock(Clock.Unsafe.withWall(controlled.unsafe) { () =>
                val now = controlled.unsafe.now()(using AllowUnsafe.embrace.danger)
                if armed.compareAndSet(1, 2) then now
                else if armed.get() == 2 then now + 1.second
                else now
            })
            Clock.get.map(controlled => makeStore.map(store => (controlled, store))).map { (controlled, store) =>
                for
                    _ <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    // Due one second into the poll's two, on the poll's doubled clock.
                    _      <- mkWait(store, eid1, wf1, "timer", Flow.Wake.At(Instant.Epoch + 1.second))
                    caller <- Fiber.init(Clock.let(hooked(controlled))(Clock.withTimeShift(2)(store.claimReady(
                        served,
                        ex1,
                        lease,
                        10,
                        2.seconds
                    ))))
                    _ <- control.awaitPendingSleepers(1)
                    _ <- Sync.defer(armed.set(1))
                    // A write the poll cannot claim, so it wakes, looks again, and learns the deadline has passed.
                    _   <- mkExecution(store, eid2, wf2, Flow.Status.Running)
                    got <- caller.get
                yield assert(
                    got.map(_.state.executionId) == Seq(eid1),
                    s"the row was due when the poll learned its time was up, so its empty answer must follow an ask made after " +
                        s"that, got ${got.map(_.state.executionId)}"
                )
                end for
            }
        }
    }

end MemoryFlowStoreTest
