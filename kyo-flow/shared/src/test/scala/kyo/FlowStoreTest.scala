package kyo

abstract class FlowStoreTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    def makeStore(using Frame): FlowStore < (Async & Scope)

    val wf1  = Flow.Id.Workflow("wf1")
    val wf2  = Flow.Id.Workflow("wf2")
    val ex1  = Flow.Id.Executor("executor-1")
    val ex2  = Flow.Id.Executor("executor-2")
    val eid1 = Flow.Id.Execution("e1")
    val eid2 = Flow.Id.Execution("e2")
    val eid3 = Flow.Id.Execution("e3")
    val eid4 = Flow.Id.Execution("e4")
    val eid5 = Flow.Id.Execution("e5")

    val lease = 30.seconds

    /** The versions a poll in this suite serves.
      *
      * Readiness gates on `(workflowId, hash)`, and every fixture here is created under the empty hash, so serving that pair is what
      * "this caller can interpret these executions" means for the suite. A store that ignored the gate would pass nothing extra, and one
      * that got it wrong returns nothing at all.
      */
    val served: Set[(Flow.Id.Workflow, String)]     = Set((wf1, ""))
    val servedBoth: Set[(Flow.Id.Workflow, String)] = Set((wf1, ""), (wf2, ""))

    /** One execution row, in whatever lifecycle the fixture needs it.
      *
      * `hash` defaults to the empty one the suite serves, so a fixture that says nothing about versions gets an execution every claim
      * in this file can take. A leaf about the version gate passes one this caller does not serve.
      */
    private def mkExecution(
        store: FlowStore,
        eid: Flow.Id.Execution,
        flowId: Flow.Id.Workflow,
        status: Flow.Status,
        hash: String = ""
    )(using Frame): Unit < (Async & Abort[FlowStoreException]) =
        Clock.now.map { now =>
            store.createExecutionIfAbsent(
                eid,
                status,
                Flow.Event.Created(flowId, eid, now),
                hash,
                Dict.empty
            ).unit
        }

    /** Takes the claim on one execution, the way a poll does, for a fixture that needs to write to it.
      *
      * Every run-time write is a method on the claim, so a fixture that writes to a row has to hold one first. A poll claims a BATCH,
      * and a fixture wants one row, so the others go back exactly as they were found: an ending that keeps precisely the rows they
      * already held, which is the one shape that neither retires a row nor blesses one.
      */
    private def claim(
        store: FlowStore,
        eid: Flow.Id.Execution,
        executor: Flow.Id.Executor = ex1,
        servedSet: Set[(Flow.Id.Workflow, String)] = servedBoth,
        leaseFor: Duration = lease
    )(using Frame): FlowStore.Claimed < (Async & Abort[FlowStoreException]) =
        store.claimReady(servedSet, executor, leaseFor, 10, Duration.Zero).map { batch =>
            Kyo.foreachDiscard(batch.filter(_.state.executionId != eid)) { other =>
                other.finish(FlowStore.Claimed.Outcome.Suspended(other.state.waits.toChunk.map(_._1).toSet)).unit
            }.andThen {
                Maybe.fromOption(batch.find(_.state.executionId == eid)) match
                    case Present(claimed) => claimed
                    case Absent =>
                        Abort.panic(new IllegalStateException(
                            s"the fixture could not claim ${eid.value}: readiness handed back ${batch.map(_.state.executionId.value)}"
                        ))
            }
        }

    private def waitEvent(flowId: Flow.Id.Workflow, eid: Flow.Id.Execution, path: String, wake: Flow.Wake, now: Instant): Flow.Event =
        wake match
            case Flow.Wake.At(instant)   => Flow.Event.SleepStarted(flowId, eid, path, instant, now)
            case Flow.Wake.OnField(name) => Flow.Event.InputWaiting(flowId, eid, name, now)

    /** Seeds a parked execution the way the system parks one: claim the row, record each branch's wait, end the attempt on them.
      *
      * The rows a fixture wants are exactly the rows a suspending ending blesses, so seeding through the public recipe is what makes the
      * seeded state one a real attempt could have produced. A row written and left under a live claim is a different state entirely, and
      * readiness reads the difference.
      */
    private def mkWaits(
        store: FlowStore,
        eid: Flow.Id.Execution,
        flowId: Flow.Id.Workflow,
        waits: (String, Flow.Wake)*
    )(using Frame): Unit < (Async & Abort[FlowStoreException]) =
        claim(store, eid).map { claimed =>
            Kyo.foreachDiscard(waits) { (path, wake) =>
                Clock.nowWith(now => claimed.recordWait(path, wake, waitEvent(flowId, eid, path, wake, now)))
            }.andThen(claimed.finish(FlowStore.Claimed.Outcome.Suspended(waits.map(_._1).toSet)))
        }.unit

    private def mkWait(
        store: FlowStore,
        eid: Flow.Id.Execution,
        flowId: Flow.Id.Workflow,
        path: String,
        wake: Flow.Wake
    )(using Frame): Unit < (Async & Abort[FlowStoreException]) =
        mkWaits(store, eid, flowId, (path, wake))

    /** Records one node's value the way a step does: field, completion event and clearing in one transition, then end the attempt. */
    private def mkField[V: Tag: Schema](
        store: FlowStore,
        eid: Flow.Id.Execution,
        flowId: Flow.Id.Workflow,
        name: String,
        value: V
    )(using Frame): Unit < (Async & Abort[FlowStoreException]) =
        claim(store, eid).map { claimed =>
            Clock.nowWith(ts => claimed.recordProgress[V](name, Maybe(value), Flow.Event.StepCompleted(flowId, eid, name, ts)))
                .andThen(claimed.finish(FlowStore.Claimed.Outcome.Suspended(claimed.state.waits.toChunk.map(_._1).toSet)))
        }.unit

    /** Delivers a value to a named input, which is how a field an execution WAITS for arrives. */
    private def deliver[V: Tag: Schema](
        store: FlowStore,
        eid: Flow.Id.Execution,
        flowId: Flow.Id.Workflow,
        name: String,
        value: V
    )(using Frame): FlowStore.SignalOutcome < (Async & Abort[FlowStoreException]) =
        Clock.nowWith(ts => store.signal[V](eid, name, value, Flow.Event.InputReceived(flowId, eid, name, ts)))

    /** A row carrying nothing but the wait ledger under test, for the equality leaves. */
    private def stateWith(waits: Dict[String, Flow.Wake]): FlowStore.ExecutionState =
        FlowStore.ExecutionState(
            eid1,
            wf1,
            Flow.Status.Running,
            hash = "",
            created = Instant.Epoch,
            updated = Instant.Epoch,
            waits = waits
        )

    private val sleeping = Flow.Wake.At(Instant.Epoch + 1.hour)
    private val waiting  = Flow.Wake.OnField("x")

    /** Ends an attempt terminally, which is the only way a claimed execution reaches a terminal status. */
    private def terminate(
        store: FlowStore,
        eid: Flow.Id.Execution,
        flowId: Flow.Id.Workflow,
        status: Flow.Status,
        event: Instant => Flow.Event
    )(using Frame): Unit < (Async & Abort[FlowStoreException]) =
        claim(store, eid).map { claimed =>
            Clock.nowWith(ts => claimed.finish(FlowStore.Claimed.Outcome.Terminal(status, event(ts))))
        }.unit

    // =========================================================================
    // I1: claim exclusivity
    // =========================================================================
    "I1: claim exclusivity" - {

        "two concurrent claimReady, 1 ready, exactly one gets it" in {
            makeStore.map { store =>
                for
                    _ <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    r1 <- Async.race(
                        store.claimReady(served, ex1, lease, 10, 1.second),
                        store.claimReady(served, ex2, lease, 10, 1.second)
                    )
                    r2 <- store.claimReady(served, ex1, lease, 10, 100.millis)
                    r3 <- store.claimReady(served, ex2, lease, 10, 100.millis)
                yield
                    assert(r1.size == 1, "race winner gets exactly 1")
                    assert(r2.isEmpty || r3.isEmpty, "second caller gets nothing")
            }
        }

        /** Two concurrent polls by the SAME executor must not both be handed one execution.
          *
          * I1 says "never returns the same execution to two concurrent callers" without qualifying who the callers are, and the engine
          * makes this the ordinary case rather than an exotic one: every worker fiber of an engine shares one `executorId`
          * (`FlowEngine.init` derives a single random id), and the default tuning runs two workers. So two workers of one engine polling
          * at the same time are two concurrent callers with equal ids, and an execution handed to both is executed twice concurrently.
          */
        "two concurrent claimReady from the SAME executor, exactly one gets it" in {
            makeStore.map { store =>
                for
                    _ <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    pair <- Async.zip(
                        store.claimReady(served, ex1, lease, 10, Duration.Zero),
                        store.claimReady(served, ex1, lease, 10, Duration.Zero)
                    )
                yield
                    val handed = (pair._1.map(_.state.executionId) ++ pair._2.map(_.state.executionId)).count(_ == eid1)
                    assert(
                        handed <= 1,
                        s"one execution must not be handed to two concurrent callers sharing an executor id, handed $handed times"
                    )
            }
        }

        /** Two callers BLOCKED in `claimReady`, woken by the same execution, and only one may get it.
          *
          * The leaf above runs two `claimReady` calls under `Async.zip` and asserts at most one was handed the row. It cannot tell
          * the store being atomic from the two calls never having overlapped, and with a ready row and a zero timeout they do not
          * overlap: `tryOnce` returns without suspending, so the first call finishes before the second starts and `handed <= 1`
          * passes having measured nothing. A leaf that can pass without the two calls ever overlapping hides a non-atomic store
          * behind a green.
          *
          * **This one forces the overlap without needing to interpose on the store.** Both callers start with NO ready execution
          * and a real timeout, so both block on the store's wake path; the execution is created while they are both inside
          * `claimReady`; and the clock is advanced to let the wake and the timeout resolve. Whatever the store does about waking,
          * both callers were genuinely inside it when the row appeared.
          *
          * The premise is asserted first: at least one caller must have been handed the row, or the run says nothing about
          * exclusivity, it just says nobody was woken.
          */
        "two callers blocked in claimReady, one execution appears, exactly one gets it" in {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        one    <- Fiber.init(store.claimReady(served, ex1, lease, 10, 2.seconds))
                        two    <- Fiber.init(store.claimReady(served, ex2, lease, 10, 2.seconds))
                        _      <- tc.advance(50.millis)
                        _      <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                        _      <- tc.advance(3.seconds)
                        first  <- one.get
                        second <- two.get
                    yield
                        val handed = (first.map(_.state.executionId) ++ second.map(_.state.executionId)).count(_ == eid1)
                        assert(
                            handed >= 1,
                            "the premise is that the execution reached one of the two blocked callers; neither was woken"
                        )
                        assert(
                            handed == 1,
                            s"one execution must reach exactly one of two callers blocked inside claimReady, it reached $handed"
                        )
                    end for
                }
            }
        }

        /** A wait that came due during the wait is handed over at the deadline, not dropped because time ran out.
          *
          * **Nothing writes when a sleep comes due**, which is what separates this from every other way a row becomes
          * ready. A create, a signal and a cancel request all wake a blocked caller; a `Wake.At` instant simply passes, and so
          * does a claim's expiry. A caller that answered empty on its deadline without asking one more time would leave such a
          * row sitting ready until somebody polled again, which is a whole poll cycle of latency on the one path that has no
          * writer to announce it. The rule the call is held to admits no exception for its last instant: either it hands the row
          * over or it leaves the row as it found it.
          *
          * **The scenario is repeated rather than run once, and that is what makes the failure reliable rather than the leaf
          * flaky.** Each iteration is deterministic under a correct store, so a correct store answers the row every time; against
          * one that answers empty on its deadline the result turns on whether the caller had reached its wait before the clock
          * jumped, which one run samples and twenty do not. The loop is sized to catch that, not to tolerate it.
          */
        "a row that came due while a caller waited is handed over at the deadline" in {
            def once(round: Int)(using Frame): Unit < (Async & Abort[FlowStoreException] & Scope) =
                Clock.withTimeControl { tc =>
                    makeStore.map { store =>
                        for
                            now <- Clock.now
                            _   <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                            // Due one second in, while a caller that starts now is still waiting out its two.
                            _      <- mkWait(store, eid1, wf1, "timer", Flow.Wake.At(now + 1.second))
                            caller <- Fiber.init(store.claimReady(served, ex1, lease, 10, 2.seconds))
                            // A small step first, so the caller reaches its wait while nothing is due yet. Without it the
                            // jump below lands before the caller has looked once, and it finds the row on its way in
                            // rather than at the deadline, which is not the moment this leaf is about.
                            _   <- tc.advance(10.millis)
                            _   <- tc.advance(3.seconds)
                            got <- caller.get
                        yield assert(
                            got.map(_.state.executionId) == Seq(eid1),
                            s"round $round: the sleep came due while the caller waited, and nothing writes when one does, so " +
                                s"the deadline must ask once more rather than answer empty over a ready row, got " +
                                s"${got.map(_.state.executionId)}"
                        )
                        end for
                    }
                }
            Kyo.foreachDiscard(1 to 20)(once).andThen(assert(true))
        }

        "repeated calls with same executor get different executions" in {
            makeStore.map { store =>
                for
                    _  <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _  <- mkExecution(store, eid2, wf1, Flow.Status.Running)
                    r1 <- store.claimReady(served, ex1, lease, 1, 1.second)
                    r2 <- store.claimReady(served, ex1, lease, 1, 1.second)
                yield
                    assert(r1.size == 1)
                    assert(r2.size == 1)
                    assert(r1.head.state.executionId != r2.head.state.executionId)
            }
        }
    }

    // =========================================================================
    // I2: atomic status + event
    // =========================================================================
    "I2: atomic status + event" - {

        "updateStatus: getExecution and getHistory both reflect change" in {
            makeStore.map { store =>
                val cause = Flow.Cause.Failure("boom", Maybe("Domain"))
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    claimed <- claim(store, eid1)
                    now     <- Clock.now
                    _ <- claimed.updateStatus(
                        Flow.Status.Compensating(cause),
                        Flow.Event.CompensationStarted(wf1, eid1, cause, now)
                    )
                    state   <- store.getExecution(eid1)
                    history <- store.getHistory(eid1, Maybe(100), 0)
                yield
                    assert(state.get.status match
                        case Flow.Status.Compensating(_) => true
                        case _                           => false)
                    assert(history.events.exists(_.kind == Flow.EventKind.CompensationStarted))
                end for
            }
        }

        /** Recording a wait writes its row and its event together, the same atomicity I2 demands of every transition.
          *
          * The rule reaches wider than `updateStatus`: a reader must never see the row without the event or the event without the row.
          * A store that wrote them separately would let a poll see an execution as ready for a wait no history explains, or hold an
          * execution against a wait nothing recorded.
          */
        "recordWait: getExecution and getHistory both reflect the wait" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    now     <- Clock.now
                    _       <- mkWait(store, eid1, wf1, "pause", Flow.Wake.At(now + 1.hour))
                    state   <- store.getExecution(eid1)
                    history <- store.getHistory(eid1, Maybe(100), 0)
                yield
                    assert(
                        state.get.waits.get("pause").contains(Flow.Wake.At(now + 1.hour)),
                        s"the row must carry the deadline it was recorded with, got ${state.get.waits}"
                    )
                    assert(history.events.exists(_.kind == Flow.EventKind.SleepStarted))
                    assert(
                        state.get.status == Flow.Status.Running,
                        s"waiting is not a lifecycle change, got ${state.get.status}"
                    )
            }
        }

        /** A wait re-recorded on a later attempt keeps the deadline it was first given.
          *
          * Put-if-absent in shape, and load-bearing rather than a convenience: a replay recomputes a sleep's deadline as `now +
          * duration`, so a store that overwrote would push the deadline forward on every attempt and turn a finite sleep into one that
          * never fires.
          *
          * Both writes ride ONE claim, because that is the whole of what the store can tell apart: a re-record is a second
          * `recordWait` on a path that already has a row, and which attempt issued it is not a fact the store holds.
          */
        "recordWait leaves an existing row's wake alone" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    claimed <- claim(store, eid1)
                    now     <- Clock.now
                    first  = now + 1.hour
                    second = now + 5.hours
                    _     <- claimed.recordWait("pause", Flow.Wake.At(first), waitEvent(wf1, eid1, "pause", Flow.Wake.At(first), now))
                    _     <- claimed.recordWait("pause", Flow.Wake.At(second), waitEvent(wf1, eid1, "pause", Flow.Wake.At(second), now))
                    state <- store.getExecution(eid1)
                yield assert(
                    state.get.waits.get("pause").contains(Flow.Wake.At(first)),
                    s"a re-recorded wait must keep its original deadline of $first, got ${state.get.waits}"
                )
            }
        }
    }

    // =========================================================================
    // I3: atomic delivery check-and-write
    // =========================================================================
    "I3: atomic delivery check-and-write" - {

        "two concurrent signals, same input, exactly one is Delivered" in {
            makeStore.map { store =>
                for
                    _ <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    results <- Async.zip(
                        deliver[Int](store, eid1, wf1, "field1", 1),
                        deliver[Int](store, eid1, wf1, "field1", 2)
                    )
                yield
                    val (r1, r2) = results
                    val wrote1   = r1 == FlowStore.SignalOutcome.Delivered
                    val wrote2   = r2 == FlowStore.SignalOutcome.Delivered
                    assert(
                        (wrote1 && !wrote2) || (!wrote1 && wrote2),
                        s"exactly one should succeed: r1=$r1, r2=$r2"
                    )
                    assert(
                        r1 == FlowStore.SignalOutcome.AlreadyDelivered || r2 == FlowStore.SignalOutcome.AlreadyDelivered,
                        s"the loser must be told the input already has a value rather than merely told no: r1=$r1, r2=$r2"
                    )
            }
        }

        "winner's value is the one persisted" in {
            makeStore.map { store =>
                for
                    _      <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    wrote1 <- deliver[String](store, eid1, wf1, "key", "first")
                    wrote2 <- deliver[String](store, eid1, wf1, "key", "second")
                    value  <- store.getField[String](eid1, "key")
                yield
                    assert(wrote1 == FlowStore.SignalOutcome.Delivered)
                    assert(wrote2 == FlowStore.SignalOutcome.AlreadyDelivered)
                    assert(value.get == "first")
            }
        }
    }

    // =========================================================================
    // I4: claim lease integrity
    // =========================================================================
    "I4: claim lease integrity" - {

        "A expires, B claims, A renewClaim → false" in {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                        claimed <- store.claimReady(served, ex1, 5.seconds, 10, 1.second)
                        _ = assert(claimed.size == 1)
                        _        <- tc.advance(10.seconds)
                        claimedB <- store.claimReady(served, ex2, lease, 10, 1.second)
                        _ = assert(claimedB.size == 1)
                        renewA <- claimed.head.renewClaim(lease)
                    yield assert(!renewA, "A should not be able to renew expired claim")
                }
            }
        }

        "A renews before expiry → true, expiry extended" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    claimed <- store.claimReady(served, ex1, lease, 10, 1.second)
                    renewed <- claimed.head.renewClaim(lease)
                yield assert(renewed)
            }
        }

        /** A renewal presented on a dead generation is refused while the row's claim is live, and the executor id proves nothing.
          *
          * A caller that never claimed holds no capability to present, so a non-owner's renewal is unexpressible and the generation is
          * the only thing left to judge a renewal on. The case staged here is the stronger one, because the two workers of one engine
          * share an executor id: the same executor claims, finishes, and claims again, and its FIRST handle is refused against a claim
          * that is active, unexpired, and bears its own name. An implementation that judged a renewal by the executor would accept it
          * and run the execution twice at once.
          */
        "a renewal on a superseded generation → false, even for the same executor" in {
            makeStore.map { store =>
                for
                    _     <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    first <- store.claimReady(served, ex1, lease, 10, 1.second)
                    _ = assert(first.size == 1, "the premise is that the first claim was granted")
                    _      <- first.head.finish(FlowStore.Claimed.Outcome.Suspended(Set.empty))
                    second <- store.claimReady(served, ex1, lease, 10, 1.second)
                    _ = assert(second.size == 1, "and that the same executor took the row again")
                    renewB <- first.head.renewClaim(lease)
                yield assert(!renewB)
            }
        }
    }

    // =========================================================================
    // I5: terminal irreversibility
    // =========================================================================
    "I5: terminal irreversibility" - {

        "updateStatus on Completed with Running → stays Completed" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    claimed <- claim(store, eid1)
                    now     <- Clock.now
                    _     <- claimed.finish(FlowStore.Claimed.Outcome.Terminal(Flow.Status.Completed, Flow.Event.Completed(wf1, eid1, now)))
                    _     <- claimed.updateStatus(Flow.Status.Running, Flow.Event.Created(wf1, eid1, now))
                    state <- store.getExecution(eid1)
                yield assert(state.get.status == Flow.Status.Completed)
            }
        }

        "updateStatus on Failed → stays Failed" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    claimed <- claim(store, eid1)
                    now     <- Clock.now
                    _ <- claimed.finish(FlowStore.Claimed.Outcome.Terminal(
                        Flow.Status.Failed("err"),
                        Flow.Event.Failed(wf1, eid1, "err", now)
                    ))
                    _     <- claimed.updateStatus(Flow.Status.Running, Flow.Event.Created(wf1, eid1, now))
                    state <- store.getExecution(eid1)
                yield assert(state.get.status match
                    case Flow.Status.Failed(_, _) => true
                    case _                        => false)
            }
        }

        "claimReady never returns Completed, Failed, or Cancelled" in {
            makeStore.map { store =>
                for
                    now     <- Clock.now
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Completed)
                    _       <- mkExecution(store, eid2, wf1, Flow.Status.Failed("err"))
                    _       <- mkExecution(store, eid3, wf1, Flow.Status.Cancelled)
                    results <- store.claimReady(served, ex1, lease, 10, 100.millis)
                yield assert(results.isEmpty)
            }
        }

        /** The two status-writing verbs partition the lifecycle, and each refuses the other's side.
          *
          * `finish` is the only verb that makes a claim absent and the only one that retires every row, so a terminal status written
          * through `updateStatus` would be terminal WITHOUT either, leaving a finished execution holding rows and a claim. The refusal
          * has to leave nothing behind, event included: history is append-only, and a status write that lands its event anyway is how a
          * refused verdict reaches the surfaces that read history as authoritative.
          */
        "updateStatus refuses a terminal status and writes nothing" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _       <- mkWait(store, eid1, wf1, "x", Flow.Wake.OnField("x"))
                    _       <- deliver[String](store, eid1, wf1, "x", "here")
                    claimed <- claim(store, eid1)
                    before  <- store.getHistory(eid1, Maybe.empty, 0)
                    now     <- Clock.now
                    outcome <- claimed.updateStatus(Flow.Status.Completed, Flow.Event.Completed(wf1, eid1, now))
                    state   <- store.getExecution(eid1)
                    after   <- store.getHistory(eid1, Maybe.empty, 0)
                yield
                    assert(
                        outcome == FlowStore.StatusOutcome.WrongSideOfTerminal,
                        s"a terminal status belongs to finish, and updateStatus must say so, got $outcome"
                    )
                    assert(state.get.status == Flow.Status.Running, s"the refused write must not move the status, got ${state.get.status}")
                    assert(
                        after.events.length == before.events.length,
                        s"a refused write leaves no event behind, history went from ${before.events.length} to ${after.events.length}"
                    )
                    assert(
                        state.get.waits.toChunk.map(_._1).toSet == Set("x"),
                        s"and it retires nothing, got ${state.get.waits.toChunk.map(_._1).toSet}"
                    )
            }
        }

        "finish refuses a non-terminal status and writes nothing" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _       <- mkWait(store, eid1, wf1, "x", Flow.Wake.OnField("x"))
                    _       <- deliver[String](store, eid1, wf1, "x", "here")
                    claimed <- claim(store, eid1)
                    before  <- store.getHistory(eid1, Maybe.empty, 0)
                    cause = Flow.Cause.Failure("boom")
                    now <- Clock.now
                    outcome <- claimed.finish(FlowStore.Claimed.Outcome.Terminal(
                        Flow.Status.Compensating(cause),
                        Flow.Event.CompensationStarted(wf1, eid1, cause, now)
                    ))
                    state <- store.getExecution(eid1)
                    after <- store.getHistory(eid1, Maybe.empty, 0)
                yield
                    assert(
                        outcome == FlowStore.StatusOutcome.WrongSideOfTerminal,
                        s"a non-terminal status belongs to updateStatus, and finish must say so, got $outcome"
                    )
                    assert(state.get.status == Flow.Status.Running, s"the refused write must not move the status, got ${state.get.status}")
                    assert(
                        after.events.length == before.events.length,
                        s"a refused write leaves no event behind, history went from ${before.events.length} to ${after.events.length}"
                    )
                    assert(
                        state.get.waits.toChunk.map(_._1).toSet == Set("x"),
                        s"it retires nothing, got ${state.get.waits.toChunk.map(_._1).toSet}"
                    )
                    assert(state.get.executor.isDefined, "and it does not consume the claim either")
            }
        }
    }

    // =========================================================================
    // I6: read-your-writes
    // =========================================================================
    "I6: read-your-writes" - {

        "recordProgress then getField → returns value" in {
            makeStore.map { store =>
                for
                    _ <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _ <- mkField[Int](store, eid1, wf1, "step1", 42)
                    v <- store.getField[Int](eid1, "step1")
                yield assert(v.get == 42)
            }
        }

        "updateStatus then getExecution → returns new status" in {
            makeStore.map { store =>
                val cause = Flow.Cause.Failure("boom")
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    claimed <- claim(store, eid1)
                    now     <- Clock.now
                    _ <- claimed.updateStatus(
                        Flow.Status.Compensating(cause),
                        Flow.Event.CompensationStarted(wf1, eid1, cause, now)
                    )
                    state <- store.getExecution(eid1)
                yield assert(state.get.status == Flow.Status.Compensating(cause))
                end for
            }
        }

        "appendEvent then getHistory → event appears" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    claimed <- claim(store, eid1)
                    now     <- Clock.now
                    _       <- claimed.appendEvent(Flow.Event.StepStarted(wf1, eid1, "s1", ex1, now))
                    h       <- store.getHistory(eid1, Maybe(100), 0)
                yield assert(h.events.exists(_.kind == Flow.EventKind.StepStarted))
            }
        }
    }

    // =========================================================================
    // I7: event ordering
    // =========================================================================
    "I7: event ordering" - {

        "three sequential appendEvent → getHistory returns in order" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    claimed <- claim(store, eid1)
                    now     <- Clock.now
                    _       <- claimed.appendEvent(Flow.Event.StepStarted(wf1, eid1, "s1", ex1, now))
                    _       <- claimed.appendEvent(Flow.Event.StepCompleted(wf1, eid1, "s1", now + 1.second))
                    _       <- claimed.appendEvent(Flow.Event.StepStarted(wf1, eid1, "s2", ex1, now + 2.seconds))
                    h       <- store.getHistory(eid1, Maybe(100), 0)
                yield
                    // +1 for the Created event from mkExecution
                    assert(h.events.length == 4)
                    assert(h.events(1).kind == Flow.EventKind.StepStarted)
                    assert(h.events(2).kind == Flow.EventKind.StepCompleted)
                    assert(h.events(3).kind == Flow.EventKind.StepStarted)
            }
        }
    }

    // =========================================================================
    // I8: readiness correctness
    // =========================================================================
    "I8: readiness correctness" - {

        "Sleeping with until in the future → not returned" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    now     <- Clock.now
                    _       <- mkWait(store, eid1, wf1, "wait", Flow.Wake.At(now + 1.hour))
                    results <- store.claimReady(served, ex1, lease, 10, 100.millis)
                yield assert(results.isEmpty)
            }
        }

        "Sleeping with until in the past → returned" in {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                        now     <- Clock.now
                        _       <- mkWait(store, eid1, wf1, "wait", Flow.Wake.At(now + 1.second))
                        _       <- tc.advance(2.seconds)
                        results <- store.claimReady(served, ex1, lease, 10, 1.second)
                    yield assert(results.size == 1)
                }
            }
        }

        "WaitingForInput with field absent → not returned" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _       <- mkWait(store, eid1, wf1, "myInput", Flow.Wake.OnField("myInput"))
                    results <- store.claimReady(served, ex1, lease, 10, 100.millis)
                yield assert(results.isEmpty)
            }
        }

        "WaitingForInput with field present → returned" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _       <- mkWait(store, eid1, wf1, "myInput", Flow.Wake.OnField("myInput"))
                    _       <- deliver[String](store, eid1, wf1, "myInput", "hello")
                    results <- store.claimReady(served, ex1, lease, 10, 1.second)
                yield assert(results.size == 1)
            }
        }

        /** The claim carries the rows the store judged satisfied, so the caller discharges what it was woken for.
          *
          * An engine that re-judged the rows against its own clock would make one decision on two clocks: against a store whose clock
          * runs ahead, readiness returns the execution while the caller finds nothing due, re-parks, and is handed the row again at
          * full speed until it catches up. Saying which rows were satisfied is what removes the second judgement.
          */
        "a claim says which of its rows the store judged satisfied" in {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        _   <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                        now <- Clock.now
                        _ <- mkWaits(
                            store,
                            eid1,
                            wf1,
                            ("due", Flow.Wake.At(now + 1.second)),
                            ("later", Flow.Wake.At(now + 1.hour)),
                            ("input", Flow.Wake.OnField("input"))
                        )
                        _       <- tc.advance(2.seconds)
                        results <- store.claimReady(served, ex1, lease, 10, 1.second)
                    yield
                        assert(results.size == 1, s"the due sleep must make it ready, got ${results.size}")
                        assert(
                            results.head.satisfied == Set("due"),
                            s"exactly the rows the store judged satisfied ride the claim, got ${results.head.satisfied}"
                        )
                }
            }
        }

        /** The version gate stands on its own, outside the disjunction over wait rows and cancel requests.
          *
          * An execution nobody serves is not ready for anybody, whatever its rows say, because a caller that cannot resolve the
          * definition can neither run it nor unwind it while holding a perfectly valid claim. Inside the disjunction the gate leaks
          * twice, for an execution with no rows and for one with a cancel request, which is why it is stated as a separate clause.
          */
        "an execution whose version the caller does not serve → not returned" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    results <- store.claimReady(Set((wf1, "some-other-version")), ex1, lease, 10, 100.millis)
                yield assert(
                    results.isEmpty,
                    s"an execution held for a version this caller does not serve must not be handed over, got $results"
                )
            }
        }

        /** A cancel request overrides every wait row, because a suspended execution nobody claims never runs its handlers.
          *
          * Cancelling runs the compensations, and the terminal `Cancelled` is written only when they have run, so an execution parked on
          * an input nobody will ever deliver has to become claimable the moment somebody asks for it to stop. Without the arm the
          * request is outstanding forever and the handlers never fire.
          */
        "a cancel request makes a parked execution ready whatever it waits for" in {
            makeStore.map { store =>
                for
                    _      <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _      <- mkWait(store, eid1, wf1, "never", Flow.Wake.OnField("never"))
                    before <- store.claimReady(served, ex1, lease, 10, 100.millis)
                    _ = assert(before.isEmpty, s"the premise is that an unsatisfied wait holds it, got $before")
                    outcome <- store.requestCancel(eid1)
                    after   <- store.claimReady(served, ex1, lease, 10, 100.millis)
                yield
                    assert(outcome == FlowStore.CancelOutcome.Accepted, s"the request must be taken, got $outcome")
                    assert(
                        after.map(_.state.executionId) == Seq(eid1),
                        s"a cancel request must make a parked execution claimable, got ${after.map(_.state.executionId)}"
                    )
                    assert(after.head.state.cancelRequested, "and the claimed row must carry the request")
            }
        }

        /** A satisfied wait that was DISCHARGED does not keep handing the execution back.
          *
          * The rows are what readiness reads, so a row that outlives the branch that wrote it keeps the execution permanently ready:
          * the poll claims it, the replay finds nothing to do, the claim is released, and the next poll claims it again at full speed.
          * The input kind is the one that matters here, because an input's discharge is the one progress a node records with no value
          * of its own, so it is the discharge a store is most likely to leave out.
          */
        "an execution whose satisfied wait was discharged → not returned" in {
            makeStore.map { store =>
                for
                    _     <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _     <- mkWait(store, eid1, wf1, "first", Flow.Wake.OnField("first"))
                    _     <- deliver[String](store, eid1, wf1, "first", "hello")
                    now   <- Clock.now
                    ready <- store.claimReady(served, ex1, lease, 10, 100.millis)
                    _ = assert(ready.size == 1, s"the premise is that the satisfied wait made it ready, got $ready")
                    // The node consumed the value and went on, which is the transition that clears the row it was waiting on, and
                    // then the execution waited on something else. Nothing retires the first row here: only the clearing can remove
                    // it, so a store that left it behind answers the next poll with a satisfied row nobody is waiting on.
                    _ <- ready.head.recordProgress("first", Flow.Event.InputDischarged(wf1, eid1, "first", now))
                    _ <- ready.head.recordWait(
                        "second",
                        Flow.Wake.OnField("second"),
                        Flow.Event.InputWaiting(wf1, eid1, "second", now)
                    )
                    state <- store.getExecution(eid1)
                    _ = assert(
                        !state.get.waits.contains("first"),
                        s"the discharged row must be gone, got ${state.get.waits}"
                    )
                    again <- store.claimReady(served, ex2, lease, 10, 100.millis)
                yield assert(
                    again.isEmpty,
                    s"a discharged wait must not keep the execution ready, it was handed over again as $again"
                )
            }
        }

        /** A claim that is still named and has expired means the last attempt died without ending, so the rows cannot be trusted.
          *
          * Rows gate wake-up only when they are a finished attempt's statement of what the execution waits for, and only the transition
          * that ends an attempt makes a claim absent. Demanding a row's satisfaction here is circular and permanent: the rows can only
          * be retired by an attempt, and the attempt needs the claim the rule would refuse.
          */
        "a claim that expired without ending the attempt → returned regardless of its rows" in {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                        claimed <- store.claimReady(served, ex1, 5.seconds, 10, Duration.Zero)
                        _ = assert(claimed.size == 1, "the premise is that an executor took the execution")
                        // The attempt records a wait nothing will ever satisfy, then dies: no release, no retirement.
                        now <- Clock.now
                        _ <- claimed.head.recordWait(
                            "never",
                            Flow.Wake.OnField("never"),
                            Flow.Event.InputWaiting(wf1, eid1, "never", now)
                        )
                        _       <- tc.advance(10.seconds)
                        results <- store.claimReady(served, ex2, lease, 10, Duration.Zero)
                    yield assert(
                        results.map(_.state.executionId) == Seq(eid1),
                        s"an execution whose attempt died mid-flight must be recoverable whatever rows it left, got " +
                            s"${results.map(_.state.executionId)}"
                    )
                }
            }
        }

        /** The transition that ends an attempt keeps exactly the rows the execution is still waiting on, and retires the rest.
          *
          * A race decided by one branch leaves the losing branch's row behind, and nothing discharged it: the branch was abandoned
          * rather than satisfied, so no progress write clears it. A row that outlives the branch that wrote it keeps the execution
          * permanently ready the moment it happens to be satisfiable.
          */
        "ending an attempt retires the rows the execution is no longer waiting on" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    claimed <- store.claimReady(served, ex1, lease, 10, Duration.Zero)
                    _ = assert(claimed.size == 1, "the premise is that an executor took the execution")
                    now <- Clock.now
                    _ <- claimed.head.recordWait(
                        "loser",
                        Flow.Wake.OnField("loser"),
                        Flow.Event.InputWaiting(wf1, eid1, "loser", now)
                    )
                    _ <- claimed.head.recordWait(
                        "winner",
                        Flow.Wake.OnField("winner"),
                        Flow.Event.InputWaiting(wf1, eid1, "winner", now)
                    )
                    // The attempt ended still waiting on one of them, so the other is not part of its final statement.
                    _     <- claimed.head.finish(FlowStore.Claimed.Outcome.Suspended(Set("winner")))
                    state <- store.getExecution(eid1)
                yield
                    assert(
                        state.get.waits.toChunk.map(_._1).toSet == Set("winner"),
                        s"the attempt's ending must keep exactly what it still waits on, got ${state.get.waits.toChunk.map(_._1).toSet}"
                    )
                    assert(state.get.executor.isEmpty, "and it must give the claim back")
            }
        }

        "Running unclaimed → returned" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    results <- store.claimReady(served, ex1, lease, 10, 1.second)
                yield assert(results.size == 1)
            }
        }

        "Running claimed by another with valid lease → not returned" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _       <- store.claimReady(served, ex1, lease, 10, 1.second)
                    results <- store.claimReady(served, ex2, lease, 10, 100.millis)
                yield assert(results.isEmpty)
            }
        }

        "Running claimed by another with expired lease → returned" in {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                        _       <- store.claimReady(served, ex1, 5.seconds, 10, 1.second)
                        _       <- tc.advance(10.seconds)
                        results <- store.claimReady(served, ex2, lease, 10, 1.second)
                    yield assert(results.size == 1)
                }
            }
        }

        /** An execution left mid-unwind by a stopped executor must be claimable.
          *
          * `Compensating` is not terminal, and the terminal `Failed` is written only after every handler has run, so an executor that
          * stops between those two writes leaves the row in it. The readiness predicate has no case for `Compensating` and falls
          * through to not-ready, so nothing can ever claim it again: the execution is neither finished nor recoverable, and its
          * compensations are half-done. Reachable without a crash, since a handler that runs longer than the lease loses the claim and
          * is interrupted. I8's own text never mentions `Compensating`, so the reference store is stricter here than the invariant it
          * documents, and an implementor following the prose would diverge.
          */
        "Compensating → returned (an executor stopped mid-unwind must be recoverable)" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Compensating(Flow.Cause.Failure("boom")))
                    results <- store.claimReady(served, ex1, lease, 10, Duration.Zero)
                yield assert(
                    results.map(_.state.executionId) == Seq(eid1),
                    s"a compensating execution whose executor stopped must be claimable, got ${results.map(_.state.executionId)}"
                )
            }
        }

        /** An executor whose OWN claim expired is given the execution back.
          *
          * The case above covers reclaim by a different executor. This is the same expiry seen from the side of the executor that
          * still owns the row, and it is the ordinary state of any execution whose holder was starved for longer than its lease:
          * nothing in the SPI clears an owner on expiry, so the row still names it. A store that treats "already mine" as "nothing
          * to do here" strands the execution, because its owner is by definition not working on it any more.
          */
        "Running whose own claim expired → returned to the same executor" in {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        _     <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                        first <- store.claimReady(served, ex1, 5.seconds, 10, Duration.Zero)
                        _     <- tc.advance(10.seconds)
                        again <- store.claimReady(served, ex1, 5.seconds, 10, Duration.Zero)
                    yield
                        assert(
                            first.map(_.state.executionId) == Seq(eid1),
                            s"the first claim must hand over the execution, got ${first.map(_.state.executionId)}"
                        )
                        assert(
                            again.map(_.state.executionId) == Seq(eid1),
                            s"an executor whose own claim expired must be given the execution back, got ${again.map(_.state.executionId)}"
                        )
                }
            }
        }

        /** The row is never invisible to everyone, and no poll holds it past one lease without renewing.
          *
          * The damaging half of the case above, and the pair has to be asserted together because each half alone admits a store that
          * strands the execution the other way. If the owner's poll hands the expired row back but nothing then makes the row lapse
          * again, the owner has a claim it will not renew and nobody else may take, which strands the execution just as thoroughly. If
          * the row lapses to everyone but the owner never gets it, the store is running the exclusion heuristic this pair rules out:
          * re-claim the row on every poll, push its expiry forward by a full lease, and filter it out of the answer, so nobody receives
          * it and from outside the lease never lapses.
          *
          * **Why the pair, rather than the narrower reading that a poll returning NOTHING leaves the row claimable by another
          * executor.** That reading has no scenario against a correct store: the owner's poll returns the row, so there is no
          * withholding poll to observe, and I9 makes a returned row carry `claimExpiry = now + lease`, so a competitor asking at
          * that instant is right to get nothing. Only the passage of a second unrenewed lease separates a lapse from a withhold,
          * which is what the pair states.
          */
        "a reclaimed row that is never renewed lapses to another executor" in {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        _     <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                        _     <- store.claimReady(served, ex1, 5.seconds, 10, Duration.Zero)
                        _     <- tc.advance(10.seconds)
                        mine  <- store.claimReady(served, ex1, 5.seconds, 10, Duration.Zero)
                        _     <- tc.advance(10.seconds)
                        other <- store.claimReady(served, ex2, 5.seconds, 10, Duration.Zero)
                    yield
                        assert(
                            mine.map(_.state.executionId) == Seq(eid1),
                            s"an executor whose own claim expired must be given the execution back rather than have it withheld, " +
                                s"got ${mine.map(_.state.executionId)}"
                        )
                        assert(
                            other.map(_.state.executionId) == Seq(eid1),
                            s"a claim nobody renewed must lapse to the next executor to ask, got ${other.map(_.state.executionId)}"
                        )
                }
            }
        }
    }

    // =========================================================================
    // I9: claim records its holder, its deadline and its generation
    // =========================================================================
    "I9: claim records its holder, its deadline and its generation" - {

        /** The deadline is measured on the store's clock, and the caller can predict it.
          *
          * I9 says a returned execution carries `claimExpiry = now + lease`, which only means something if "now" is a clock both
          * sides agree on. The engine compares that instant against its own clock when it decides whether it still holds a claim, so
          * an implementation that stamped the deadline from a database's clock while the engine read a process clock would put the
          * whole lease mechanism at the mercy of skew between them, silently. Pinning the caller's prediction is what makes that a
          * contract rather than an accident of both running in one process.
          */
        "the claim deadline is one lease past the caller's own now" in {
            Clock.withTimeControl { _ =>
                makeStore.map { store =>
                    for
                        _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                        before  <- Clock.now
                        results <- store.claimReady(served, ex1, 30.seconds, 10, Duration.Zero)
                        after   <- Clock.now
                    yield
                        assert(results.size == 1, s"the execution should be claimed, got ${results.size}")
                        val expiry = results.head.state.claimExpiry
                        assert(
                            expiry.exists(e => !(e < (before + 30.seconds)) && !((after + 30.seconds) < e)),
                            s"the deadline must sit one lease past a now the caller can observe: got $expiry, " +
                                s"expected between ${before + 30.seconds} and ${after + 30.seconds}"
                        )
                }
            }
        }

        "returned execution has executor = caller's ID" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    results <- store.claimReady(served, ex1, lease, 10, 1.second)
                yield
                    assert(results.size == 1)
                    assert(results.head.state.executor == Maybe(ex1))
            }
        }

        "claimExpiry is set" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    results <- store.claimReady(served, ex1, lease, 10, 1.second)
                yield assert(results.head.state.claimExpiry.isDefined)
            }
        }

        /** Each claim's generation is strictly greater than any the row has carried, which is what a write is judged against.
          *
          * The recording obligation, stated as a leaf because a rule nothing exercises is a rule a store can quietly not have. It is
          * the token and not the executor that decides a write: an executor that claims, lapses and reclaims the same row is a
          * different generation, and the two workers of one engine share an id, so nothing about who holds a claim can tell a dead
          * generation from a live one.
          */
        "each claim carries a generation greater than the last" in {
            makeStore.map { store =>
                for
                    _     <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    first <- store.claimReady(served, ex1, lease, 10, 1.second)
                    _ = assert(first.size == 1, "the premise is that the first claim was granted")
                    _      <- first.head.finish(FlowStore.Claimed.Outcome.Suspended(Set.empty))
                    second <- store.claimReady(served, ex1, lease, 10, 1.second)
                    _ = assert(second.size == 1, "and that the row was claimable again")
                    released <- store.getExecution(eid1)
                yield
                    val before = first.head.state.claimToken
                    val after  = second.head.state.claimToken
                    assert(before.isDefined, s"an active claim must record its generation, got $before")
                    assert(
                        after.exists(a => before.exists(b => a > b)),
                        s"a new claim must carry a generation strictly greater than the last, got $before then $after"
                    )
                    assert(
                        released.exists(_.claimToken.isDefined),
                        "and the row carries the live generation, which is what a write is judged against"
                    )
            }
        }
    }

    // =========================================================================
    // Field operations
    // =========================================================================
    "field operations" - {

        /** A store author can use the whole of the vocabulary the SPI hands them, on both dictionaries it hands back.
          *
          * `getAllFields` answers a `Dict[String, FieldData]` and `ExecutionState.waits` carries a `Dict[String, Flow.Wake]`, and
          * filtering or searching one is the first thing an implementation does with either: which of these fields is mine, which of
          * these rows is a sleep. Both are a cast hazard. Under its threshold a `Dict` is a `Span` over an `Object[]`, and `Dict`'s
          * inline operations cast that array to `Array[K | V]`, a cast emitted at the CALLER's site against the erased lub of the
          * caller's concrete types. `String` with a case class or an enum case is `Serializable` on both sides, so an unguarded
          * `exists`, `filter` or sibling throws `[Ljava.lang.Object; cannot be cast to [Ljava.io.Serializable;` on exactly the two
          * types this SPI publishes.
          *
          * Nothing in this module calls them, so the engine's own coverage says nothing about them: the surface a store author writes
          * against is not the surface the engine happens to exercise. The guard is in kyo-data, in `Dict`'s own `reduce`, with its own
          * leaves; this pins the consequence at the boundary that publishes the type.
          */
        "the dictionaries the SPI answers with support the operations a store author writes" in {
            makeStore.map { store =>
                for
                    _ <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _ <- mkField[Int](store, eid1, wf1, "a", 1)
                    _ <- mkField[Int](store, eid1, wf1, "b", 2)
                    _ <- mkWaits(
                        store,
                        eid1,
                        wf1,
                        "sleeping" -> Flow.Wake.At(Instant.Epoch + 1.hour),
                        "waiting"  -> Flow.Wake.OnField("x")
                    )
                    fields <- store.getAllFields(eid1)
                    state  <- store.getExecution(eid1)
                yield
                    assert(fields.exists((name, _) => name == "a"), s"a field search must answer, got ${fields.toChunk}")
                    assert(
                        fields.filter((name, _) => name == "a").toChunk.map(_._1) == Chunk("a"),
                        s"and a field filter must answer, got ${fields.filter((name, _) => name == "a").toChunk}"
                    )
                    assert(!fields.forall((name, _) => name == "a"), "and so must the predicate over all of them")
                    val waits = state.map(_.waits).getOrElse(Dict.empty)
                    assert(
                        waits.exists((_, wake) =>
                            wake match
                                case _: Flow.Wake.At => true
                                case _               => false
                        ),
                        s"a wait search must answer, got ${waits.toChunk}"
                    )
                    assert(
                        waits.filter((path, _) => path == "waiting").toChunk.map(_._1) == Chunk("waiting"),
                        s"and a wait filter must answer, got ${waits.filter((path, _) => path == "waiting").toChunk}"
                    )
                    assert(waits.count((_, _) => true) == 2, s"and so must a count, got ${waits.toChunk}")
                end for
            }
        }

        "recordProgress then getField returns the value" in {
            makeStore.map { store =>
                for
                    _ <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _ <- mkField[String](store, eid1, wf1, "name", "hello")
                    v <- store.getField[String](eid1, "name")
                yield assert(v.get == "hello")
            }
        }

        "getField for absent name returns empty" in {
            makeStore.map { store =>
                for
                    _ <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    v <- store.getField[Int](eid1, "missing")
                yield assert(v.isEmpty)
            }
        }

        /** Progress is WRITE-ONCE per path.
          *
          * The first writer decides the field, and a second write on a path that already carries one is answered already-recorded and
          * changes nothing at all. It is what makes the race exemption safe, since two live branches completing a shared output name
          * resolve to the first writer at the store rather than to whichever write the scheduler landed last, and it is the same answer
          * replay already derives from the field's presence. A store implementing this as an upsert would look correct until two
          * branches raced.
          *
          * **Changing nothing at all is three things, and the third one needs a row to be about.** The answer, the standing value
          * and the unchanged history are read from what the writes touch, but "retires nothing" is a statement about the wait ledger,
          * and an execution with no rows satisfies it however wrongly a store behaves. So a row is recorded at a SECOND path before
          * the refused write, one the writes never name: a store that treated a refusal as an ending and swept the ledger would clear
          * it, and the assertion below is what notices.
          */
        "a second recordProgress on a present path is answered already-recorded" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    claimed <- claim(store, eid1)
                    now     <- Clock.now
                    _ <- claimed.recordWait(
                        "gate",
                        Flow.Wake.OnField("gate"),
                        waitEvent(wf1, eid1, "gate", Flow.Wake.OnField("gate"), now)
                    )
                    first <- claimed.recordProgress[Int](
                        "step1",
                        Maybe(1),
                        Flow.Event.StepCompleted(wf1, eid1, "step1", now)
                    )
                    before <- store.getHistory(eid1, Maybe.empty, 0)
                    second <- claimed.recordProgress[Int](
                        "step1",
                        Maybe(2),
                        Flow.Event.StepCompleted(wf1, eid1, "step1", now + 1.second)
                    )
                    v     <- store.getField[Int](eid1, "step1")
                    after <- store.getHistory(eid1, Maybe.empty, 0)
                    state <- store.getExecution(eid1)
                yield
                    assert(first == FlowStore.ProgressOutcome.Recorded, s"the first write must land, got $first")
                    assert(
                        second == FlowStore.ProgressOutcome.AlreadyRecorded,
                        s"a second write on a present path must be answered already-recorded, got $second"
                    )
                    assert(v.get == 1, s"the first writer's value stands, got $v")
                    assert(
                        after.events.length == before.events.length,
                        s"and the refused write leaves no event, history went from ${before.events.length} to ${after.events.length}"
                    )
                    assert(
                        state.get.waits.get("gate").contains(Flow.Wake.OnField("gate")),
                        s"and it retires nothing: the row waiting at another path stands exactly as it was recorded, got " +
                            s"${state.get.waits}"
                    )
            }
        }

        /** A valueless progress is refused only by its own recorded completion, never by the field on its path.
          *
          * The input discharge is by construction a valueless progress at a path the signalled value already occupies, so a store that
          * refused it on field presence would silently drop the discharge event AND the row clearing, which is the producer the
          * clearing rule exists for. The second one is still refused, because the node recorded its completion the first time.
          */
        "a valueless recordProgress lands on a path that already carries a field, once" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _       <- mkWait(store, eid1, wf1, "answer", Flow.Wake.OnField("answer"))
                    _       <- deliver[String](store, eid1, wf1, "answer", "yes")
                    claimed <- claim(store, eid1)
                    now     <- Clock.now
                    first   <- claimed.recordProgress("answer", Flow.Event.InputDischarged(wf1, eid1, "answer", now))
                    state   <- store.getExecution(eid1)
                    second  <- claimed.recordProgress("answer", Flow.Event.InputDischarged(wf1, eid1, "answer", now + 1.second))
                    value   <- store.getField[String](eid1, "answer")
                yield
                    assert(
                        first == FlowStore.ProgressOutcome.Recorded,
                        s"a valueless progress must not be refused by the field it discharges, got $first"
                    )
                    assert(state.get.waits.isEmpty, s"and it must clear the row, got ${state.get.waits}")
                    assert(
                        second == FlowStore.ProgressOutcome.AlreadyRecorded,
                        s"a second valueless progress on the same path is its own completion twice, got $second"
                    )
                    assert(value == Present("yes"), s"and the signalled value is untouched throughout, got $value")
            }
        }

        "getAllFields returns all fields for an execution" in {
            makeStore.map { store =>
                for
                    _   <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _   <- mkField[Int](store, eid1, wf1, "a", 1)
                    _   <- mkField[String](store, eid1, wf1, "b", "two")
                    all <- store.getAllFields(eid1)
                yield
                    assert(all.contains("a"))
                    assert(all.contains("b"))
                    assert(all.size == 2)
            }
        }

        "getAllFields returns empty map for unknown execution" in {
            makeStore.map { store =>
                for
                    all <- store.getAllFields(Flow.Id.Execution("unknown"))
                yield assert(all.isEmpty)
            }
        }

        "different executions have separate fields" in {
            makeStore.map { store =>
                for
                    _  <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _  <- mkExecution(store, eid2, wf1, Flow.Status.Running)
                    _  <- mkField[Int](store, eid1, wf1, "x", 10)
                    _  <- mkField[Int](store, eid2, wf1, "x", 20)
                    v1 <- store.getField[Int](eid1, "x")
                    v2 <- store.getField[Int](eid2, "x")
                yield
                    assert(v1.get == 10)
                    assert(v2.get == 20)
            }
        }
    }

    // =========================================================================
    // signal
    // =========================================================================
    "signal" - {

        "returns Delivered and stores value when the input is absent" in {
            makeStore.map { store =>
                for
                    _     <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    wrote <- deliver[Int](store, eid1, wf1, "key", 42)
                    v     <- store.getField[Int](eid1, "key")
                yield
                    assert(wrote == FlowStore.SignalOutcome.Delivered)
                    assert(v.get == 42)
            }
        }

        "returns AlreadyDelivered and preserves the original value when the input has one" in {
            makeStore.map { store =>
                for
                    _     <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _     <- deliver[Int](store, eid1, wf1, "key", 1)
                    wrote <- deliver[Int](store, eid1, wf1, "key", 2)
                    v     <- store.getField[Int](eid1, "key")
                yield
                    assert(wrote == FlowStore.SignalOutcome.AlreadyDelivered)
                    assert(v.get == 1)
            }
        }

        "works correctly across different executions" in {
            makeStore.map { store =>
                for
                    _  <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _  <- mkExecution(store, eid2, wf1, Flow.Status.Running)
                    w1 <- deliver[Int](store, eid1, wf1, "key", 10)
                    w2 <- deliver[Int](store, eid2, wf1, "key", 20)
                yield
                    assert(w1 == FlowStore.SignalOutcome.Delivered)
                    assert(w2 == FlowStore.SignalOutcome.Delivered)
            }
        }

        /** A delivery records its arrival in the same transition that writes the field.
          *
          * A value that is durable and invisible is the shape an operator cannot diagnose: the execution holds an input nothing in its
          * history says arrived, so "when did this come in" has no answer and "was it ever delivered" is a field read rather than a
          * story.
          */
        "a delivery records its arrival in history" in {
            makeStore.map { store =>
                for
                    _ <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _ <- deliver[Int](store, eid1, wf1, "key", 42)
                    h <- store.getHistory(eid1, Maybe.empty, 0)
                yield assert(
                    h.events.exists(e => e.kind == Flow.EventKind.InputReceived && e.detail == "key"),
                    s"the delivery must be in the history that records it, got ${h.events.map(_.kind)}"
                )
            }
        }

        /** A delivery to an execution that is over is refused, and says so with what it ended as.
          *
          * A boolean answer cannot express this refusal: put-if-absent says `false` for an input that already carries a value and
          * `true` for one written onto a finished execution, which tells a caller its delivery succeeded when nothing will ever
          * consume it. The two refusals are different instructions, and the caller can act on the difference: stop retrying this
          * name, or stop entirely and read the result.
          */
        "a delivery to a terminal execution is refused and writes nothing" in {
            makeStore.map { store =>
                for
                    _      <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    now    <- Clock.now
                    _      <- terminate(store, eid1, wf1, Flow.Status.Completed, ts => Flow.Event.Completed(wf1, eid1, ts))
                    before <- store.getHistory(eid1, Maybe.empty, 0)
                    wrote  <- deliver[Int](store, eid1, wf1, "key", 42)
                    v      <- store.getField[Int](eid1, "key")
                    after  <- store.getHistory(eid1, Maybe.empty, 0)
                yield
                    assert(
                        wrote == FlowStore.SignalOutcome.AlreadyTerminal(Flow.Status.Completed),
                        s"a delivery to a finished execution must be refused with what it ended as, got $wrote"
                    )
                    assert(v.isEmpty, s"and the field must not land, got $v")
                    assert(
                        after.events.length == before.events.length,
                        s"and nothing is recorded, history went from ${before.events.length} to ${after.events.length}"
                    )
            }
        }
    }

    // =========================================================================
    // Execution state
    // =========================================================================
    "execution state" - {

        "getExecution for unknown ID returns empty" in {
            makeStore.map { store =>
                for
                    state <- store.getExecution(Flow.Id.Execution("unknown"))
                yield assert(state.isEmpty)
            }
        }

        "createExecutionIfAbsent creates the execution when the id is free" in {
            makeStore.map { store =>
                for
                    now   <- Clock.now
                    _     <- store.createExecutionIfAbsent(eid1, Flow.Status.Running, Flow.Event.Created(wf1, eid1, now), "", Dict.empty)
                    state <- store.getExecution(eid1)
                yield
                    assert(state.isDefined)
                    assert(state.get.status == Flow.Status.Running)
                    assert(state.get.flowId == wf1)
            }
        }

        "multiple status writes append events in order" in {
            makeStore.map { store =>
                for
                    now <- Clock.now
                    _   <- store.createExecutionIfAbsent(eid1, Flow.Status.Running, Flow.Event.Created(wf1, eid1, now), "", Dict.empty)
                    // The sleep is already due, so the row that records it is satisfied and the next attempt can take the claim,
                    // which is the shape the walk below needs: park, wake, discharge, finish.
                    _ <- mkWait(store, eid1, wf1, "wait", Flow.Wake.At(now))
                    _ <- claim(store, eid1, leaseFor = lease).map { claimed =>
                        claimed.recordProgress("wait", Flow.Event.SleepCompleted(wf1, eid1, "wait", now + 1.hour))
                            .andThen(claimed.finish(FlowStore.Claimed.Outcome.Terminal(
                                Flow.Status.Completed,
                                Flow.Event.Completed(wf1, eid1, now + 2.hours)
                            )))
                    }
                    h <- store.getHistory(eid1, Maybe(100), 0)
                yield
                    assert(h.events.length == 4)
                    assert(h.events(0).kind == Flow.EventKind.Created)
                    assert(h.events(1).kind == Flow.EventKind.SleepStarted)
                    assert(h.events(2).kind == Flow.EventKind.SleepCompleted)
                    assert(h.events(3).kind == Flow.EventKind.Completed)
            }
        }

        /** An execution that sleeps and wakes walks two facts, not one: the lifecycle it keeps, and the wait it holds and discharges. */
        "status transitions: Running → waiting on a sleep → Running → Completed" in {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        now   <- Clock.now
                        _     <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                        _     <- mkWait(store, eid1, wf1, "s", Flow.Wake.At(now + 1.hour))
                        s1    <- store.getExecution(eid1)
                        _     <- tc.advance(2.hours)
                        woken <- claim(store, eid1)
                        _     <- woken.recordProgress("s", Flow.Event.SleepCompleted(wf1, eid1, "s", now))
                        s2    <- store.getExecution(eid1)
                        _ <- woken.finish(FlowStore.Claimed.Outcome.Terminal(
                            Flow.Status.Completed,
                            Flow.Event.Completed(wf1, eid1, now)
                        ))
                        s3 <- store.getExecution(eid1)
                    yield
                        assert(s1.get.status == Flow.Status.Running, s"sleeping is not a lifecycle change, got ${s1.get.status}")
                        assert(s1.get.waits.contains("s"), s"the sleep must be recorded as a wait row, got ${s1.get.waits}")
                        assert(s2.get.status == Flow.Status.Running)
                        assert(!s2.get.waits.contains("s"), s"the discharged sleep must be gone, got ${s2.get.waits}")
                        assert(s3.get.status == Flow.Status.Completed)
                }
            }
        }
    }

    // =========================================================================
    // Event history
    // =========================================================================
    "event history" - {

        "getHistory returns events in append order" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    claimed <- claim(store, eid1)
                    now     <- Clock.now
                    _       <- claimed.appendEvent(Flow.Event.StepStarted(wf1, eid1, "s1", ex1, now))
                    _       <- claimed.appendEvent(Flow.Event.StepCompleted(wf1, eid1, "s1", now))
                    h       <- store.getHistory(eid1, Maybe(100), 0)
                yield
                    assert(h.events.length == 3) // Created + 2
                    assert(h.events(1).detail == "s1")
                    assert(h.events(1).kind == Flow.EventKind.StepStarted)
                    assert(h.events(2).kind == Flow.EventKind.StepCompleted)
            }
        }

        "getHistory with limit returns at most N events" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    claimed <- claim(store, eid1)
                    now     <- Clock.now
                    _ <- Kyo.foreach(1 to 10)(i =>
                        claimed.appendEvent(Flow.Event.StepStarted(wf1, eid1, s"s$i", ex1, now))
                    )
                    h <- store.getHistory(eid1, Maybe(3), 0)
                yield
                    assert(h.events.length == 3)
                    assert(h.hasMore)
            }
        }

        "getHistory with offset skips events" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    claimed <- claim(store, eid1)
                    now     <- Clock.now
                    _ <- Kyo.foreach(1 to 5)(i =>
                        claimed.appendEvent(Flow.Event.StepStarted(wf1, eid1, s"s$i", ex1, now))
                    )
                    h <- store.getHistory(eid1, Maybe(100), 3)
                yield
                    // 6 total (1 Created + 5 appended), skip 3 → 3 remaining
                    assert(h.events.length == 3)
                    assert(!h.hasMore)
            }
        }

        /** A second create for an id that already exists must not erase what is there.
          *
          * The SPI does not say what `createExecutionIfAbsent` does for an existing id, and a duplicate guard built as a read followed
          * by a write with nothing between them lets two concurrent starts on the same explicit id both pass. A store that answered by
          * replacing the row AND resetting the entire event chunk to a single `Created` would let the loser's create wipe a running
          * execution's history. Replay reads that history to decide what is already done, so the execution would silently re-run every
          * completed step and repeat their side effects. Either outcome is defensible, refusing the create or leaving the row alone;
          * destroying the history is not.
          */
        "createExecution on an existing id does not destroy the history" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    claimed <- claim(store, eid1)
                    now     <- Clock.now
                    _       <- claimed.appendEvent(Flow.Event.StepCompleted(wf1, eid1, "s1", now))
                    _       <- Abort.run[FlowStoreException](mkExecution(store, eid1, wf1, Flow.Status.Running))
                    h       <- store.getHistory(eid1, Maybe.empty, 0)
                yield assert(
                    h.events.exists(_.kind == Flow.EventKind.StepCompleted),
                    s"a second create must not erase a completed step from the history, got ${h.events.map(_.kind)}"
                )
            }
        }

        /** The last page says it is the last page, even at the largest page size a caller can express.
          *
          * The SPI warns an implementor in as many words that the obvious way to answer `hasMore`, adding the limit to the offset,
          * overflows to a negative number, and `Int.MaxValue` is where that happens: at any positive offset, `offset + limit` wraps
          * and every page compares as "more follow". A `hasMore` of true on the final page is an infinite pagination loop for any
          * client that trusts it, and the HTTP surface passes `limit` and `offset` through from query parameters.
          *
          * **The bound is passed as `Present`, which is the arm that can overflow.** `Absent` says "every event" and is answered
          * without arithmetic at all, so a leaf asking through it cannot fail on any implementation that special-cases it, and the
          * store's own way of staying safe (bounding on what is left rather than adding to the limit) would go unpinned. `Absent`
          * is what the ENGINE passes for every event; `Int.MaxValue` is what a caller passing a very large number passes, and it is
          * the value this leaf is named for.
          */
        "getHistory reports no more pages on the last one, with limit = Int.MaxValue" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    claimed <- claim(store, eid1)
                    now     <- Clock.now
                    _       <- claimed.appendEvent(Flow.Event.StepStarted(wf1, eid1, "s1", ex1, now))
                    h       <- store.getHistory(eid1, Maybe(Int.MaxValue), 1)
                yield
                    assert(h.events.length == 1, s"one event should remain after skipping one, got ${h.events.length}")
                    assert(!h.hasMore, "the last page must not claim there is another")
            }
        }

        /** A negative page size does not produce an empty page that claims more follow.
          *
          * The HTTP surface passes `limit` and `offset` straight through from query parameters without validating either, so a
          * client can reach this. An empty page with `hasMore` set is an infinite pagination loop for anything that trusts the flag,
          * which is what the flag is for. Rejecting the argument is equally acceptable to this leaf; producing that pair is not.
          */
        "getHistory does not answer an empty page that claims more, for a negative limit" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    claimed <- claim(store, eid1)
                    now     <- Clock.now
                    _       <- claimed.appendEvent(Flow.Event.StepStarted(wf1, eid1, "s1", ex1, now))
                    r       <- Abort.run[FlowStoreException](store.getHistory(eid1, Maybe(-1), 0))
                yield
                    // Refusing the argument and answering a sane page are both acceptable; answering an empty page that claims
                    // another follows is not, because that is the pair a paging client loops on forever.
                    val acceptable = r match
                        case Result.Success(h) => !(h.events.isEmpty && h.hasMore)
                        case _                 => true
                    assert(
                        acceptable,
                        s"a negative limit must not answer an empty page that claims more follow, got $r"
                    )
            }
        }

        /** `listExecutions` validates its page arguments, rather than inheriting a paragraph that describes a different method.
          *
          * `listExecutions`'s scaladoc defines `limit` and `offset` entirely by reference: "as [[getHistory]] documents them". That
          * paragraph is about `getHistory`'s `hasMore` overflow, which `listExecutions` cannot have, because it answers a bare
          * `Chunk` with no more-pages flag. So the one method that DOES document its pagination says nothing that applies here, and
          * what is left undocumented is the case a caller can actually reach: a negative limit.
          *
          * Scala's `take(-1)` answers an empty collection and `drop(-1)` is a no-op, so a negative limit silently reports that a
          * workflow has no executions at all. That is the worst available answer, because "none" is a legitimate result the caller
          * has no way to distinguish from "your argument was nonsense". Refusing is equally acceptable to this leaf; answering an
          * empty page for a workflow that demonstrably has executions is not.
          */
        "listExecutions does not answer an empty page for a negative limit" in {
            makeStore.map { store =>
                for
                    _ <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _ <- mkExecution(store, eid2, wf1, Flow.Status.Running)
                    r <- Abort.run[FlowStoreException](store.listExecutions(wf1, Maybe.empty, Maybe(-1), 0))
                yield
                    // Refusing the argument and clamping to a sane page are both acceptable; reporting an empty workflow is not,
                    // because the caller cannot tell that answer from a workflow that genuinely has nothing in it.
                    val acceptable = r match
                        case Result.Success(execs) => execs.nonEmpty
                        case _                     => true
                    assert(
                        acceptable,
                        s"a negative limit must not report an empty workflow that has two executions, got $r"
                    )
            }
        }

        /** Paging exactly to the end reports that it is the end. */
        "getHistory reports no more pages when the offset lands exactly at the end" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    claimed <- claim(store, eid1)
                    now     <- Clock.now
                    _       <- claimed.appendEvent(Flow.Event.StepStarted(wf1, eid1, "s1", ex1, now))
                    h       <- store.getHistory(eid1, Maybe(10), 2)
                yield
                    assert(h.events.isEmpty, s"there are two events, so an offset of two is past the end, got ${h.events.length}")
                    assert(!h.hasMore, "an offset at the end must not claim another page follows")
            }
        }

        "getHistory returns empty for unknown execution" in {
            makeStore.map { store =>
                for
                    h <- store.getHistory(Flow.Id.Execution("unknown"), Maybe(100), 0)
                yield
                    assert(h.events.isEmpty)
                    assert(!h.hasMore)
            }
        }
    }

    // =========================================================================
    // The acceptance rule: which writes a claim carries
    // =========================================================================
    "the acceptance rule" - {

        /** A write presented under a claim the row no longer carries is refused, in every shape that can produce one.
          *
          * Three shapes, and each defeats an implementation that has only half the rule. SUPERSEDED: somebody else claimed the row, so
          * the generation is dead while the row's claim is live. EXPIRED: nobody else claimed it, so the dead generation is still the
          * highest one the row has carried, and a store that recorded only the highest token would accept the write. RELEASED: the
          * attempt ended, so the claim is absent, and the write was issued before that ending and delivered after it, which is neither
          * superseded nor expired: a store that checks only those two accepts it.
          */
        "a write presented under a stale claim is refused" in {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        _     <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                        stale <- store.claimReady(served, ex1, 5.seconds, 10, Duration.Zero)
                        _ = assert(stale.size == 1, "the premise is that the first executor took the execution")
                        _     <- tc.advance(10.seconds)
                        fresh <- store.claimReady(served, ex2, 30.seconds, 10, Duration.Zero)
                        // Superseded: the row's claim is live and belongs to somebody else.
                        superseded <- stale.head.recordProgress[String](
                            "receipt",
                            Maybe("paid"),
                            Flow.Event.StepCompleted(wf1, eid1, "receipt", Instant.Epoch)
                        )
                        receipt <- store.getField[String](eid1, "receipt")

                        // Expired with no competitor: the dead generation is still the highest the row has carried.
                        _      <- mkExecution(store, eid2, wf1, Flow.Status.Running)
                        lapsed <- store.claimReady(served, ex1, 5.seconds, 10, Duration.Zero)
                        _ = assert(lapsed.exists(_.state.executionId == eid2), "the premise is that the second row was claimed")
                        _ <- tc.advance(10.seconds)
                        expired <- lapsed.head.recordProgress[String](
                            "receipt",
                            Maybe("paid"),
                            Flow.Event.StepCompleted(wf1, eid2, "receipt", Instant.Epoch)
                        )
                        expiredField <- store.getField[String](eid2, "receipt")

                        // Released: the attempt ended, so the claim is absent whenever the write was issued.
                        _        <- mkExecution(store, eid3, wf1, Flow.Status.Running)
                        released <- store.claimReady(served, ex1, 30.seconds, 10, Duration.Zero)
                        _ = assert(released.exists(_.state.executionId == eid3), "the premise is that the third row was claimed")
                        _ <- released.head.finish(FlowStore.Claimed.Outcome.Suspended(Set.empty))
                        afterRelease <- released.head.recordProgress[String](
                            "receipt",
                            Maybe("paid"),
                            Flow.Event.StepCompleted(wf1, eid3, "receipt", Instant.Epoch)
                        )
                        releasedField <- store.getField[String](eid3, "receipt")
                    yield
                        assert(fresh.size == 1, "the second executor should have taken the expired claim")
                        assert(
                            superseded == FlowStore.ProgressOutcome.ClaimLost,
                            "a write under a claim the store no longer holds must be refused"
                        )
                        assert(receipt.isEmpty, s"and it must write nothing, got $receipt")
                        assert(
                            expired == FlowStore.ProgressOutcome.ClaimLost,
                            "a write under a lapsed claim must be refused even when nobody else took the row"
                        )
                        assert(expiredField.isEmpty, s"and it must write nothing, got $expiredField")
                        assert(
                            afterRelease == FlowStore.ProgressOutcome.ClaimLost,
                            "a write delivered after the attempt released must be refused, whenever it was issued"
                        )
                        assert(releasedField.isEmpty, s"and it must write nothing, got $releasedField")
                    end for
                }
            }
        }

        /** A lapsed executor cannot drive an execution another executor now owns to a terminal status.
          *
          * The refusal has to leave NOTHING, and the event is the half that matters: history is append-only and the operator surfaces
          * read it as authoritative for failure, so a `Failed` event written by an executor that no longer held the row sits in the
          * history of an execution its owner completed and cannot be taken back.
          */
        "a stale executor cannot mark an execution failed after another took it" in {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        _     <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                        stale <- store.claimReady(served, ex1, 5.seconds, 10, Duration.Zero)
                        _ = assert(stale.size == 1, "the premise is that the first executor took the execution")
                        _   <- tc.advance(10.seconds)
                        _   <- store.claimReady(served, ex2, 30.seconds, 10, Duration.Zero)
                        now <- Clock.now
                        refused <- stale.head.finish(FlowStore.Claimed.Outcome.Terminal(
                            Flow.Status.Failed("the stale executor's verdict"),
                            Flow.Event.Failed(wf1, eid1, "the stale executor's verdict", now)
                        ))
                        state   <- store.getExecution(eid1)
                        history <- store.getHistory(eid1, Maybe.empty, 0)
                    yield
                        assert(refused == FlowStore.StatusOutcome.ClaimLost, "a terminal write under a lapsed claim must be refused")
                        assert(!state.exists(_.status.isTerminal), "the execution must still belong to its new owner")
                        assert(
                            !history.events.exists(_.kind == Flow.EventKind.Failed),
                            "a refused write must not leave its event in the history either"
                        )
                    end for
                }
            }
        }
    }

    // =========================================================================
    // claimReady
    // =========================================================================
    "claimReady" - {

        "returns empty when no executions exist" in {
            makeStore.map { store =>
                for
                    results <- store.claimReady(served, ex1, lease, 10, 100.millis)
                yield assert(results.isEmpty)
            }
        }

        "returns unclaimed Running execution" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    results <- store.claimReady(served, ex1, lease, 10, 1.second)
                yield
                    assert(results.size == 1)
                    assert(results.head.state.executionId == eid1)
            }
        }

        "does not return terminal executions" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Completed)
                    results <- store.claimReady(served, ex1, lease, 10, 100.millis)
                yield assert(results.isEmpty)
            }
        }

        "returns at most limit executions" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _       <- mkExecution(store, eid2, wf1, Flow.Status.Running)
                    _       <- mkExecution(store, eid3, wf1, Flow.Status.Running)
                    results <- store.claimReady(served, ex1, lease, 2, 1.second)
                yield assert(results.size == 2)
            }
        }

        "filters by workflow IDs" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _       <- mkExecution(store, eid2, wf2, Flow.Status.Running)
                    results <- store.claimReady(served, ex1, lease, 10, 1.second)
                yield
                    assert(results.size == 1)
                    assert(results.head.state.executionId == eid1)
            }
        }
    }

    // =========================================================================
    // renewClaim
    // =========================================================================
    "renewClaim" - {

        "returns true and extends expiry for valid claim owner" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    claimed <- store.claimReady(served, ex1, lease, 10, 1.second)
                    renewed <- claimed.head.renewClaim(lease)
                yield assert(renewed)
            }
        }

        /** A renewal presented after the attempt ended is refused.
          *
          * The verb has no non-owner shape: a caller that never claimed holds no capability to present. What it does have is a
          * capability that STOPS being current, and a release is the way that happens without a clock: the claim is absent, so the
          * rule's first half fails whatever the token says.
          */
        "returns false once the attempt has ended" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    claimed <- store.claimReady(served, ex1, lease, 10, 1.second)
                    _       <- claimed.head.finish(FlowStore.Claimed.Outcome.Suspended(Set.empty))
                    renewed <- claimed.head.renewClaim(lease)
                yield assert(!renewed)
            }
        }

        "returns false after claim expired" in {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                        claimed <- store.claimReady(served, ex1, 5.seconds, 10, 1.second)
                        _       <- tc.advance(10.seconds)
                        renewed <- claimed.head.renewClaim(lease)
                    yield assert(!renewed)
                }
            }
        }
    }

    // =========================================================================
    // finish
    // =========================================================================
    "finish" - {

        "clears executor and claimExpiry for valid owner" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    claimed <- store.claimReady(served, ex1, lease, 10, 1.second)
                    _       <- claimed.head.finish(FlowStore.Claimed.Outcome.Suspended(Set.empty))
                    state   <- store.getExecution(eid1)
                yield
                    assert(state.get.executor.isEmpty)
                    assert(state.get.claimExpiry.isEmpty)
            }
        }

        "released execution becomes discoverable by claimReady" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    claimed <- store.claimReady(served, ex1, lease, 10, 1.second)
                    _       <- claimed.head.finish(FlowStore.Claimed.Outcome.Suspended(Set.empty))
                    results <- store.claimReady(served, ex2, lease, 10, 1.second)
                yield assert(results.size == 1)
            }
        }

        /** An ending presented on a claim the row no longer carries changes nothing.
          *
          * A release by somebody who never claimed has no expression under the capability split, so the shape that matters is an
          * ending arriving twice, or arriving from a generation the row has moved past: it must not give away a claim its successor
          * is holding.
          */
        "an ending presented twice leaves the second claim alone" in {
            makeStore.map { store =>
                for
                    _     <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    first <- store.claimReady(served, ex1, lease, 10, 1.second)
                    _     <- first.head.finish(FlowStore.Claimed.Outcome.Suspended(Set.empty))
                    // The row is claimed again, this time by somebody else, and the first attempt's ending arrives late.
                    second <- store.claimReady(served, ex2, lease, 10, 1.second)
                    _      <- first.head.finish(FlowStore.Claimed.Outcome.Suspended(Set.empty))
                    state  <- store.getExecution(eid1)
                yield
                    assert(second.size == 1, "the premise is that a second executor took the row")
                    assert(state.get.executor == Maybe(ex2)) // still claimed by ex2
            }
        }
    }

    // =========================================================================
    // requestCancel
    // =========================================================================
    "requestCancel" - {

        "the first request is Accepted and rides the row" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    outcome <- store.requestCancel(eid1)
                    state   <- store.getExecution(eid1)
                yield
                    assert(outcome == FlowStore.CancelOutcome.Accepted, s"the first ask must be taken, got $outcome")
                    assert(state.get.cancelRequested, "and the row must carry the request")
                    assert(
                        state.get.status == Flow.Status.Running,
                        s"a request is not a terminal write: the handlers run first, got ${state.get.status}"
                    )
            }
        }

        "a repeat is AlreadyRequested" in {
            makeStore.map { store =>
                for
                    _      <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _      <- store.requestCancel(eid1)
                    repeat <- store.requestCancel(eid1)
                yield assert(
                    repeat == FlowStore.CancelOutcome.AlreadyRequested,
                    s"an idempotent repeat must be distinguishable from the first ask, got $repeat"
                )
            }
        }

        "a request on a finished execution answers what it ended as" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Completed)
                    outcome <- store.requestCancel(eid1)
                    state   <- store.getExecution(eid1)
                yield
                    assert(
                        outcome == FlowStore.CancelOutcome.AlreadyTerminal(Flow.Status.Completed),
                        s"there is nothing to cancel, and the answer carries what it ended as, got $outcome"
                    )
                    assert(!state.get.cancelRequested, "and nothing is written to a finished execution")
            }
        }
    }

    // =========================================================================
    // listExecutions
    // =========================================================================
    "listExecutions" - {

        "returns executions for given flowId" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _       <- mkExecution(store, eid2, wf1, Flow.Status.Running)
                    _       <- mkExecution(store, eid3, wf2, Flow.Status.Running)
                    results <- store.listExecutions(wf1, Maybe.empty, Maybe(100), 0)
                yield assert(results.length == 2)
            }
        }

        "filters by status when provided" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _       <- mkExecution(store, eid2, wf1, Flow.Status.Completed)
                    results <- store.listExecutions(wf1, Maybe(FlowStore.ExecutionFilter.Running), Maybe(100), 0)
                yield
                    assert(results.length == 1)
                    assert(results.head.executionId == eid1)
            }
        }

        /** A wait-kind filter asks what an execution is waiting FOR, which no lifecycle status can answer.
          *
          * A caller asking for the sleeping executions has no `until` to guess and no name to guess either, so the question is about the
          * kind of wait rather than about a value, and it is answered from the rows: an execution matches when ANY of its rows does.
          */
        "filters by the kind of wait, whatever its deadline" in {
            makeStore.map { store =>
                for
                    now      <- Clock.now
                    _        <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _        <- mkExecution(store, eid2, wf1, Flow.Status.Running)
                    _        <- mkExecution(store, eid3, wf1, Flow.Status.Running)
                    _        <- mkWait(store, eid2, wf1, "wait", Flow.Wake.At(now + 30.seconds))
                    _        <- mkWait(store, eid3, wf1, "settle", Flow.Wake.At(now + 90.seconds))
                    sleeping <- store.listExecutions(wf1, Maybe(FlowStore.ExecutionFilter.Sleeping), Maybe(100), 0)
                    running  <- store.listExecutions(wf1, Maybe(FlowStore.ExecutionFilter.Running), Maybe(100), 0)
                yield
                    assert(
                        sleeping.map(_.executionId).toSet == Set(eid2, eid3),
                        s"both sleeping executions must match, whatever deadline each holds, got ${sleeping.map(_.executionId)}"
                    )
                    assert(
                        running.length == 3,
                        s"the lifecycle filter is a different question and all three are Running, got ${running.length}"
                    )
            }
        }

        /** An input wait can be asked for by name, because an input's name is the caller's own vocabulary. */
        "filters input waits by name" in {
            makeStore.map { store =>
                for
                    _     <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _     <- mkExecution(store, eid2, wf1, Flow.Status.Running)
                    _     <- mkWait(store, eid1, wf1, "approval", Flow.Wake.OnField("approval"))
                    _     <- mkWait(store, eid2, wf1, "receipt", Flow.Wake.OnField("receipt"))
                    named <- store.listExecutions(wf1, Maybe(FlowStore.ExecutionFilter.WaitingForInput(Maybe("approval"))), Maybe(100), 0)
                    any   <- store.listExecutions(wf1, Maybe(FlowStore.ExecutionFilter.WaitingForInput(Maybe.empty)), Maybe(100), 0)
                yield
                    assert(
                        named.map(_.executionId) == Chunk(eid1),
                        s"a named input filter must narrow to that row, got ${named.map(_.executionId)}"
                    )
                    assert(
                        any.map(_.executionId).toSet == Set(eid1, eid2),
                        s"an unnamed input filter must match any input wait, got ${any.map(_.executionId)}"
                    )
            }
        }

        /** An execution somebody asked to cancel reads as cancelling until it has finished unwinding. */
        "filters the executions somebody asked to cancel" in {
            makeStore.map { store =>
                for
                    _          <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _          <- mkExecution(store, eid2, wf1, Flow.Status.Running)
                    _          <- store.requestCancel(eid1)
                    cancelling <- store.listExecutions(wf1, Maybe(FlowStore.ExecutionFilter.Cancelling), Maybe(100), 0)
                    _          <- terminate(store, eid1, wf1, Flow.Status.Cancelled, ts => Flow.Event.Cancelled(wf1, eid1, ts))
                    after      <- store.listExecutions(wf1, Maybe(FlowStore.ExecutionFilter.Cancelling), Maybe(100), 0)
                yield
                    assert(
                        cancelling.map(_.executionId) == Chunk(eid1),
                        s"an outstanding request must be visible while the execution is still unwinding, got ${cancelling.map(_.executionId)}"
                    )
                    assert(after.isEmpty, s"and it stops being outstanding once the execution is over, got ${after.map(_.executionId)}")
            }
        }

        /** The completed lifecycle answers its own filter, and only it.
          *
          * One leaf per lifecycle arm rather than one leaf over all of them, so a store that collapsed two of them is named by the
          * failure rather than by a set comparison a reader has to decode. Each asserts the exact result rather than membership: a
          * store that ignored the filter and answered everything passes a membership check.
          */
        "filters the executions that completed" in {
            makeStore.map { store =>
                for
                    _         <- mkExecution(store, eid1, wf1, Flow.Status.Completed)
                    _         <- mkExecution(store, eid2, wf1, Flow.Status.Running)
                    _         <- mkExecution(store, eid3, wf1, Flow.Status.Cancelled)
                    completed <- store.listExecutions(wf1, Maybe(FlowStore.ExecutionFilter.Completed), Maybe(100), 0)
                yield assert(
                    completed.map(_.executionId) == Chunk(eid1),
                    s"only the completed execution answers the completed filter, got ${completed.map(_.executionId)}"
                )
            }
        }

        /** The cancelled lifecycle answers its own filter, and is not the same question as an outstanding cancel request. */
        "filters the executions that were cancelled" in {
            makeStore.map { store =>
                for
                    _         <- mkExecution(store, eid1, wf1, Flow.Status.Cancelled)
                    _         <- mkExecution(store, eid2, wf1, Flow.Status.Completed)
                    _         <- mkExecution(store, eid3, wf1, Flow.Status.Running)
                    cancelled <- store.listExecutions(wf1, Maybe(FlowStore.ExecutionFilter.Cancelled), Maybe(100), 0)
                yield assert(
                    cancelled.map(_.executionId) == Chunk(eid1),
                    s"only the cancelled execution answers the cancelled filter, got ${cancelled.map(_.executionId)}"
                )
            }
        }

        /** An unwinding execution answers the compensating filter whatever cause it is unwinding for.
          *
          * The arm carries no payload while the status does, so a store matching on the case rather than on the whole value is what
          * the rule asks for: a failure-caused unwind and a cancellation-caused one are both compensating.
          */
        "filters the executions that are unwinding" in {
            makeStore.map { store =>
                for
                    _ <- mkExecution(store, eid1, wf1, Flow.Status.Compensating(Flow.Cause.Failure("boom", Maybe("ChargeDeclined"))))
                    _ <- mkExecution(store, eid2, wf1, Flow.Status.Compensating(Flow.Cause.Cancellation))
                    _ <- mkExecution(store, eid3, wf1, Flow.Status.Running)
                    unwinding <- store.listExecutions(wf1, Maybe(FlowStore.ExecutionFilter.Compensating), Maybe(100), 0)
                yield assert(
                    unwinding.map(_.executionId).toSet == Set(eid1, eid2),
                    s"both unwinds answer the compensating filter, whatever each is unwinding for, got ${unwinding.map(_.executionId)}"
                )
            }
        }

        /** An unnarrowed failure filter matches every failure, including one that carries no kind.
          *
          * A panic has no declared kind, and it is still a failed execution. A store reading the absent ask as "failures whose kind is
          * absent", or as a narrowing on the empty string, hides exactly the executions an operator sweeping for failures most needs.
          */
        "an unnarrowed failure filter matches every failure, including one with no kind" in {
            makeStore.map { store =>
                for
                    _      <- mkExecution(store, eid1, wf1, Flow.Status.Failed("declined", Maybe("ChargeDeclined")))
                    _      <- mkExecution(store, eid2, wf1, Flow.Status.Failed("boom", Maybe.empty))
                    _      <- mkExecution(store, eid3, wf1, Flow.Status.Running)
                    failed <- store.listExecutions(wf1, Maybe(FlowStore.ExecutionFilter.Failed(Maybe.empty)), Maybe(100), 0)
                yield assert(
                    failed.map(_.executionId).toSet == Set(eid1, eid2),
                    s"an unnarrowed ask must match a kind-less failure too, got ${failed.map(_.executionId)}"
                )
            }
        }

        /** A narrowed failure filter matches that kind and nothing else.
          *
          * The kind exists so an operator can ask "how many failed for payment reasons", and a store that accepted the parameter and
          * ignored it makes the field decorative while answering 200 to every ask. Both directions are here: the kind that exists
          * narrows to its own execution, and a kind nobody carries answers nothing rather than everything.
          */
        "a narrowed failure filter matches only that kind" in {
            makeStore.map { store =>
                for
                    _        <- mkExecution(store, eid1, wf1, Flow.Status.Failed("declined", Maybe("ChargeDeclined")))
                    _        <- mkExecution(store, eid2, wf1, Flow.Status.Failed("boom", Maybe.empty))
                    _        <- mkExecution(store, eid3, wf1, Flow.Status.Failed("late", Maybe("FlowStepTimeoutException")))
                    declined <- store.listExecutions(wf1, Maybe(FlowStore.ExecutionFilter.Failed(Maybe("ChargeDeclined"))), Maybe(100), 0)
                    absent   <- store.listExecutions(wf1, Maybe(FlowStore.ExecutionFilter.Failed(Maybe("NoSuchKind"))), Maybe(100), 0)
                yield
                    assert(
                        declined.map(_.executionId) == Chunk(eid1),
                        s"a narrowed ask must answer only that kind, got ${declined.map(_.executionId)}"
                    )
                    assert(
                        absent.isEmpty,
                        s"and a kind nobody carries must answer nothing, not everything, got ${absent.map(_.executionId)}"
                    )
            }
        }

        /** The orphan predicate is two halves, and a store that drops either one answers the wrong population.
          *
          * It names the non-terminal executions whose version the caller does not serve, which is what `FlowEngine.parked` reports.
          * Without the non-terminal half every execution that ever ran under a retired version is reported as stuck forever; without
          * the hash half the report is every execution the caller is running perfectly well.
          */
        "filters the non-terminal executions no caller serves" in {
            makeStore.map { store =>
                for
                    _        <- mkExecution(store, eid1, wf1, Flow.Status.Running, hash = "v2")
                    _        <- mkExecution(store, eid2, wf1, Flow.Status.Completed, hash = "v2")
                    _        <- mkExecution(store, eid3, wf1, Flow.Status.Running)
                    orphaned <- store.listExecutions(wf1, Maybe(FlowStore.ExecutionFilter.Orphaned(Set(""))), Maybe(100), 0)
                yield assert(
                    orphaned.map(_.executionId) == Chunk(eid1),
                    s"only the non-terminal execution on an unserved version is orphaned: a terminal one needs nobody to serve it, " +
                        s"and a served one is not stranded, got ${orphaned.map(_.executionId)}"
                )
            }
        }

        /** An execution waiting on two kinds of row at once answers both wait filters.
          *
          * `race(input("x"), sleep(t))` is the idiom the wait ledger exists for, and it holds an `At` row and an `OnField` row at the
          * same time. A filter derived from a single "current wait" would have to pick one, which rebuilds inside the query path the
          * single-slot cell the ledger exists to replace: whichever it picked, the execution would be invisible to the other ask.
          */
        "an execution waiting on two kinds of row matches both wait filters" in {
            makeStore.map { store =>
                for
                    now <- Clock.now
                    _   <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _   <- mkWaits(store, eid1, wf1, ("timer", Flow.Wake.At(now + 30.seconds)), ("gate", Flow.Wake.OnField("approval")))
                    sleeping <- store.listExecutions(wf1, Maybe(FlowStore.ExecutionFilter.Sleeping), Maybe(100), 0)
                    waiting  <- store.listExecutions(wf1, Maybe(FlowStore.ExecutionFilter.WaitingForInput(Maybe.empty)), Maybe(100), 0)
                    named <- store.listExecutions(wf1, Maybe(FlowStore.ExecutionFilter.WaitingForInput(Maybe("approval"))), Maybe(100), 0)
                yield
                    assert(
                        sleeping.map(_.executionId) == Chunk(eid1),
                        s"the sleep row must answer the sleeping filter, got ${sleeping.map(_.executionId)}"
                    )
                    assert(
                        waiting.map(_.executionId) == Chunk(eid1),
                        s"and the same execution's input row must answer the waiting filter, got ${waiting.map(_.executionId)}"
                    )
                    assert(
                        named.map(_.executionId) == Chunk(eid1),
                        s"and it must still be reachable by that input's name, got ${named.map(_.executionId)}"
                    )
            }
        }

        "respects limit and offset" in {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _       <- mkExecution(store, eid2, wf1, Flow.Status.Running)
                    _       <- mkExecution(store, eid3, wf1, Flow.Status.Running)
                    results <- store.listExecutions(wf1, Maybe.empty, Maybe(1), 1)
                yield assert(results.length == 1)
            }
        }

        "returns empty for unknown flowId" in {
            makeStore.map { store =>
                for
                    results <- store.listExecutions(Flow.Id.Workflow("unknown"), Maybe.empty, Maybe(100), 0)
                yield assert(results.isEmpty)
            }
        }
    }

    // =========================================================================
    // Workflow metadata
    // =========================================================================
    "workflow metadata" - {

        val testMeta = FlowEngine.WorkflowInfo(
            id = "wf1",
            meta = Flow.Meta(version = "1.0"),
            nodes = Seq.empty,
            inputs = Seq.empty,
            outputs = Seq.empty,
            structuralHash = "abc123"
        )

        "putWorkflow then getWorkflow returns the metadata" in {
            makeStore.map { store =>
                for
                    _ <- store.putWorkflow(testMeta)
                    m <- store.getWorkflow(wf1)
                yield assert(m.get.id == "wf1")
            }
        }

        "getWorkflow for unknown ID returns empty" in {
            makeStore.map { store =>
                for
                    m <- store.getWorkflow(Flow.Id.Workflow("unknown"))
                yield assert(m.isEmpty)
            }
        }

        "listWorkflows returns all registered workflows" in {
            makeStore.map { store =>
                for
                    _   <- store.putWorkflow(testMeta)
                    _   <- store.putWorkflow(testMeta.copy(id = "wf2"))
                    all <- store.listWorkflows
                yield assert(all.size == 2)
            }
        }

        "putWorkflow overwrites existing workflow with same ID" in {
            makeStore.map { store =>
                for
                    _ <- store.putWorkflow(testMeta)
                    _ <- store.putWorkflow(testMeta.copy(meta = Flow.Meta(version = "2.0")))
                    m <- store.getWorkflow(wf1)
                yield assert(m.get.meta.version == "2.0")
            }
        }
    }

    // =========================================================================
    // Cross-cutting scenarios, each spanning more than one SPI method
    // =========================================================================

    "I1: 10 concurrent callers, 5 ready, all disjoint" in {
        makeStore.map { store =>
            for
                _ <- Kyo.foreach(1 to 5)(i => mkExecution(store, Flow.Id.Execution(s"e$i"), wf1, Flow.Status.Running))
                executors = (1 to 10).map(i => Flow.Id.Executor(s"ex$i"))
                results <- Kyo.foreach(executors)(ex => store.claimReady(served, ex, lease, 10, 1.second))
                allClaimed = results.flatten
            yield
                assert(allClaimed.size == 5, s"Expected 5 claimed, got ${allClaimed.size}")
                assert(allClaimed.map(_.state.executionId).distinct.size == 5, "All disjoint")
        }
    }

    /** One claim's events keep their append order whatever executor id each of them names.
      *
      * There is one caller here, which is the only shape the claimed capability admits: a second caller cannot write to an execution
      * it does not hold, so "events from different callers" is not a state this SPI can be in. What the three appends do vary is the
      * `executorId` each EVENT carries, and the pin is that the ordering is the store's, not the payloads'.
      */
    "I7: events keep their append order across the executor ids they name" in {
        makeStore.map { store =>
            for
                _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                claimed <- claim(store, eid1)
                now     <- Clock.now
                _       <- claimed.appendEvent(Flow.Event.StepStarted(wf1, eid1, "s1", ex1, now))
                _       <- claimed.appendEvent(Flow.Event.StepCompleted(wf1, eid1, "s1", now + 1.second))
                _       <- claimed.appendEvent(Flow.Event.StepStarted(wf1, eid1, "s2", ex2, now + 2.seconds))
                h       <- store.getHistory(eid1, Maybe(100), 0)
            yield
                assert(h.events.length == 4) // Created + 3
                val details = h.events.drop(1).map(_.detail).toSeq
                assert(details == Seq("s1", "s1", "s2"))
        }
    }

    "field operations: getField with wrong type tag returns empty" in {
        makeStore.map { store =>
            for
                _ <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                _ <- mkField[Int](store, eid1, wf1, "x", 42)
                v <- store.getField[String](eid1, "x")
            yield assert(v.isEmpty)
        }
    }

    "renewClaim: same executor can renew multiple times" in {
        makeStore.map { store =>
            for
                _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                claimed <- store.claimReady(served, ex1, lease, 10, 1.second)
                r1      <- claimed.head.renewClaim(lease)
                r2      <- claimed.head.renewClaim(lease)
                r3      <- claimed.head.renewClaim(lease)
            yield
                assert(r1)
                assert(r2)
                assert(r3)
        }
    }

    /** Waiting for an input is two facts, and the round trip asserts both: the lifecycle it keeps and the row that records the node. */
    "execution state: waiting on an input, then Running, then Failed" in {
        makeStore.map { store =>
            for
                now   <- Clock.now
                _     <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                _     <- mkWait(store, eid1, wf1, "x", Flow.Wake.OnField("x"))
                s1    <- store.getExecution(eid1)
                _     <- deliver[String](store, eid1, wf1, "x", "here")
                woken <- claim(store, eid1)
                _     <- woken.recordProgress("x", Flow.Event.InputDischarged(wf1, eid1, "x", now))
                s2    <- store.getExecution(eid1)
                _ <- woken.finish(FlowStore.Claimed.Outcome.Terminal(
                    Flow.Status.Failed("err"),
                    Flow.Event.Failed(wf1, eid1, "err", now)
                ))
                s3 <- store.getExecution(eid1)
            yield
                assert(s1.get.status == Flow.Status.Running, s"waiting is not a lifecycle change, got ${s1.get.status}")
                assert(
                    s1.get.waits.get("x").contains(Flow.Wake.OnField("x")),
                    s"the node it waits on must be recorded as a row, got ${s1.get.waits}"
                )
                assert(s2.get.status == Flow.Status.Running)
                assert(s2.get.waits.isEmpty, s"the discharged wait must be gone, got ${s2.get.waits}")
                assert(s3.get.status match
                    case Flow.Status.Failed(_, _) => true;
                    case _                        => false)
        }
    }

    "execution state: Running → Cancelled" in {
        makeStore.map { store =>
            for
                _ <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                _ <- terminate(store, eid1, wf1, Flow.Status.Cancelled, ts => Flow.Event.Cancelled(wf1, eid1, ts))
                s <- store.getExecution(eid1)
            yield assert(s.get.status == Flow.Status.Cancelled)
        }
    }

    // =========================================================================
    // claimReady blocking/wake behavior
    // =========================================================================
    "claimReady blocking" - {

        "returns empty on timeout when nothing ready" in {
            makeStore.map { store =>
                for
                    results <- store.claimReady(served, ex1, lease, 10, 100.millis)
                yield assert(results.isEmpty)
            }
        }

        "wakes when signal makes execution ready" in {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        now   <- Clock.now
                        _     <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                        _     <- mkWait(store, eid1, wf1, "x", Flow.Wake.OnField("x"))
                        fiber <- Fiber.init(store.claimReady(served, ex1, lease, 10, 5.seconds))
                        // Load-bearing, not padding: it is what lets the caller reach its wait before the delivery lands. Without
                        // it the spawned fiber has not run when the field is written, so it finds the value on its way IN and the
                        // leaf passes having measured a plain poll rather than a wake. Removing it does not fail this leaf; it
                        // silently changes what the leaf is about.
                        _       <- tc.advance(100.millis)
                        _       <- deliver[Int](store, eid1, wf1, "x", 42)
                        _       <- tc.advance(200.millis)
                        results <- fiber.get
                    yield assert(results.size == 1)
                }
            }
        }

        "wakes when an execution is created" in {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        fiber <- Fiber.init(store.claimReady(served, ex1, lease, 10, 5.seconds))
                        // Load-bearing for the same reason as the leaf above: without it the caller has not reached its wait when
                        // the row is created, so it finds the row on its way in and the leaf measures a plain poll, not a wake.
                        _   <- tc.advance(100.millis)
                        now <- Clock.now
                        _   <- store.createExecutionIfAbsent(eid1, Flow.Status.Running, Flow.Event.Created(wf1, eid1, now), "", Dict.empty)
                        _   <- tc.advance(200.millis)
                        results <- fiber.get
                    yield assert(results.size == 1)
                }
            }
        }

        "expired sleep found on next poll" in {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        now <- Clock.now
                        _   <- store.createExecutionIfAbsent(eid1, Flow.Status.Running, Flow.Event.Created(wf1, eid1, now), "", Dict.empty)
                        _   <- mkWait(store, eid1, wf1, "s", Flow.Wake.At(now + 500.millis))
                        // First poll: sleep not expired, times out
                        fiber1 <- Fiber.init(store.claimReady(served, ex1, lease, 10, 100.millis))
                        _      <- tc.advance(50.millis)
                        _      <- tc.advance(50.millis)
                        _      <- tc.advance(50.millis)
                        empty  <- fiber1.get
                        // Advance past sleep expiry
                        _ <- tc.advance(500.millis)
                        // Second poll: sleep expired, found immediately
                        found <- store.claimReady(served, ex1, lease, 10, 100.millis)
                    yield
                        assert(empty.isEmpty)
                        assert(found.size == 1)
                }
            }
        }
    }

    // =========================================================================
    // Execution state equality
    // =========================================================================
    /** Two rows describing the same execution are equal, which is what `CanEqual` on the row advertises.
      *
      * A conformance suite compares states: it reads a row, does something it expects to change nothing, reads again and asserts the
      * two are the same. That comparison has to mean what it says, and the wait ledger is where it can quietly stop meaning it. Under
      * its threshold a `Dict` is a span over an array and its `==` is the array's, so two ledgers holding the same rows would be equal
      * only when they happened to share the array, which a store that rebuilds the row (any store over a database, decoding it afresh)
      * never does. A store author's own test would fail for a reason that has nothing to do with their store.
      *
      * `Dict.is` is the structural test, and the row uses it. The hash goes with it: equal rows hash alike, and because the ledger's
      * equality does not depend on the order its entries were added, the hash may not either.
      */
    "execution state equality" - {

        "two rows with the same wait ledger are equal and hash alike" in {
            // Built separately and in opposite orders, so neither the array identity nor the insertion order can carry the answer.
            val a = stateWith(Dict("sleeping" -> sleeping, "waiting" -> waiting))
            val b = stateWith(Dict("waiting" -> waiting, "sleeping" -> sleeping))
            assert(a == b, s"same rows, so the states are the same: ${a.waits.toChunk} against ${b.waits.toChunk}")
            assert(a.hashCode == b.hashCode, s"and equal states hash alike, got ${a.hashCode} and ${b.hashCode}")
        }

        "two rows with different wait ledgers are not equal" in {
            val a = stateWith(Dict("waiting" -> waiting))
            val b = stateWith(Dict("waiting" -> Flow.Wake.OnField("y")))
            val c = stateWith(Dict("waiting" -> waiting, "sleeping" -> sleeping))
            assert(a != b, "a row waiting on another input is another state")
            assert(a != c, "and so is one waiting on more than it does")
        }

        /** Every field the override compares by hand, varied one at a time.
          *
          * A hand-written `equals` is only as complete as its author's attention, and the failure it invites is silent: a dropped
          * field makes two different rows equal, which no other leaf here would notice. So each field gets its own variant and its
          * own message, and the arithmetic at the end is what keeps the leaf honest as the row grows. A field added to
          * `ExecutionState` fails this leaf until somebody adds a case for it, which is the moment to notice the override needs an
          * arm too, rather than discovering it from a comparison that quietly answered true.
          */
        "a row differing outside its wait ledger is not equal either" in {
            val base = stateWith(Dict("waiting" -> waiting))
            val varied = Seq(
                "executionId"     -> base.copy(executionId = eid2),
                "flowId"          -> base.copy(flowId = wf2),
                "status"          -> base.copy(status = Flow.Status.Completed),
                "executor"        -> base.copy(executor = Maybe(ex1)),
                "claimExpiry"     -> base.copy(claimExpiry = Maybe(Instant.Epoch + 1.hour)),
                "hash"            -> base.copy(hash = "other"),
                "created"         -> base.copy(created = Instant.Epoch + 1.second),
                "updated"         -> base.copy(updated = Instant.Epoch + 1.second),
                "cancelRequested" -> base.copy(cancelRequested = true),
                "claimToken"      -> base.copy(claimToken = Maybe(1L))
            )
            varied.foreach { (field, other) =>
                assert(
                    base != other,
                    s"a row differing only in '$field' is another state, and the override must compare it"
                )
            }
            assert(
                base.productArity == varied.size + 1,
                s"ExecutionState carries ${base.productArity} fields and this leaf varies ${varied.size} of them beside the " +
                    "ledger, so one is unguarded: give it a case here and an arm in the override"
            )
        }
    }

end FlowStoreTest
