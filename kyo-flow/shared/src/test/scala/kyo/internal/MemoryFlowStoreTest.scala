package kyo.internal

import kyo.*

class MemoryFlowStoreTest extends FlowStoreTest:
    def makeStore(using Frame): FlowStore < (Async & Scope) = FlowStore.initMemory

    // Whether `fiber` finished rather than re-arming a sleep. The race awaits a fresh copy of the fiber's completion, since
    // interrupting the losing arm would otherwise interrupt what it awaits.
    private def finishedOrRearmed[A, S](control: Clock.TimeControl, fiber: Fiber[A, S])(using Frame): Boolean < Async =
        Fiber.Promise.init[Unit, Any].map { finished =>
            fiber.onComplete(_ => finished.completeUnitDiscard).andThen {
                Async.race(control.awaitPendingSleepers(1).andThen(false), finished.get.andThen(true))
            }
        }

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
                            finishedOrRearmed(control, poll).map { finished =>
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

end MemoryFlowStoreTest
