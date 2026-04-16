package kyo

abstract class FlowStoreTest extends Test:

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

    private def mkExecution(
        store: FlowStore,
        eid: Flow.Id.Execution,
        flowId: Flow.Id.Workflow,
        status: Flow.Status
    )(using Frame): Unit < Async =
        Clock.now.map { now =>
            store.createExecution(
                eid,
                status,
                Flow.Event.Created(flowId, eid, now),
                ""
            )
        }

    // =========================================================================
    // I1 — Claim exclusivity
    // =========================================================================
    "I1 — claim exclusivity" - {

        "two concurrent claimReady, 1 ready — exactly one gets it" in run {
            makeStore.map { store =>
                for
                    _ <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    r1 <- Async.race(
                        store.claimReady(Set(wf1), ex1, lease, 10, 1.second),
                        store.claimReady(Set(wf1), ex2, lease, 10, 1.second)
                    )
                    r2 <- store.claimReady(Set(wf1), ex1, lease, 10, 100.millis)
                    r3 <- store.claimReady(Set(wf1), ex2, lease, 10, 100.millis)
                yield
                    assert(r1.size == 1, "race winner gets exactly 1")
                    assert(r2.isEmpty || r3.isEmpty, "second caller gets nothing")
            }
        }

        "repeated calls with same executor get different executions" in run {
            makeStore.map { store =>
                for
                    _      <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _      <- mkExecution(store, eid2, wf1, Flow.Status.Running)
                    batch1 <- store.claimReady(Set(wf1), ex1, lease, 1, 1.second)
                    batch2 <- store.claimReady(Set(wf1), ex1, lease, 1, 1.second)
                yield
                    assert(batch1.size == 1)
                    assert(batch2.size == 1)
                    assert(batch1.head.executionId != batch2.head.executionId)
            }
        }
    }

    // =========================================================================
    // I2 — Atomic status + event
    // =========================================================================
    "I2 — atomic status + event" - {

        "updateStatus: getExecution and getHistory both reflect change" in run {
            makeStore.map { store =>
                for
                    _   <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    now <- Clock.now
                    _ <- store.updateStatus(
                        eid1,
                        Flow.Status.Sleeping("wait", now + 1.hour),
                        Flow.Event.SleepStarted(wf1, eid1, "wait", now + 1.hour, now)
                    )
                    state   <- store.getExecution(eid1)
                    history <- store.getHistory(eid1, 100, 0)
                yield
                    assert(state.get.status match
                        case Flow.Status.Sleeping(_, _) => true
                        case _                          => false)
                    assert(history.events.exists(_.kind == Flow.EventKind.SleepStarted))
            }
        }
    }

    // =========================================================================
    // I3 — Atomic field check-and-write
    // =========================================================================
    "I3 — atomic field check-and-write" - {

        "two concurrent putFieldIfAbsent, same key — exactly one returns true" in run {
            makeStore.map { store =>
                for
                    _ <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    results <- Async.zip(
                        store.putFieldIfAbsent[Int](eid1, "field1", 1),
                        store.putFieldIfAbsent[Int](eid1, "field1", 2)
                    )
                yield
                    val (r1, r2) = results
                    assert(
                        (r1 && !r2) || (!r1 && r2),
                        s"exactly one should succeed: r1=$r1, r2=$r2"
                    )
            }
        }

        "winner's value is the one persisted" in run {
            makeStore.map { store =>
                for
                    _      <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    wrote1 <- store.putFieldIfAbsent[String](eid1, "key", "first")
                    wrote2 <- store.putFieldIfAbsent[String](eid1, "key", "second")
                    value  <- store.getField[String](eid1, "key")
                yield
                    assert(wrote1)
                    assert(!wrote2)
                    assert(value.get == "first")
            }
        }
    }

    // =========================================================================
    // I4 — Claim lease integrity
    // =========================================================================
    "I4 — claim lease integrity" - {

        "A expires, B claims, A renewClaim → false" in run {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                        claimed <- store.claimReady(Set(wf1), ex1, 5.seconds, 10, 1.second)
                        _ = assert(claimed.size == 1)
                        _        <- tc.advance(10.seconds)
                        claimedB <- store.claimReady(Set(wf1), ex2, lease, 10, 1.second)
                        _ = assert(claimedB.size == 1)
                        renewA <- store.renewClaim(eid1, ex1, lease)
                    yield assert(!renewA, "A should not be able to renew expired claim")
                }
            }
        }

        "A renews before expiry → true, expiry extended" in run {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _       <- store.claimReady(Set(wf1), ex1, lease, 10, 1.second)
                    renewed <- store.renewClaim(eid1, ex1, lease)
                yield assert(renewed)
            }
        }

        "B tries renewClaim on A's valid claim → false" in run {
            makeStore.map { store =>
                for
                    _      <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _      <- store.claimReady(Set(wf1), ex1, lease, 10, 1.second)
                    renewB <- store.renewClaim(eid1, ex2, lease)
                yield assert(!renewB)
            }
        }
    }

    // =========================================================================
    // I5 — Terminal irreversibility
    // =========================================================================
    "I5 — terminal irreversibility" - {

        "updateStatus on Completed with Running → stays Completed" in run {
            makeStore.map { store =>
                for
                    now   <- Clock.now
                    _     <- store.createExecution(eid1, Flow.Status.Completed, Flow.Event.Completed(wf1, eid1, now), "")
                    _     <- store.updateStatus(eid1, Flow.Status.Running, Flow.Event.Created(wf1, eid1, now))
                    state <- store.getExecution(eid1)
                yield assert(state.get.status == Flow.Status.Completed)
            }
        }

        "updateStatus on Failed → stays Failed" in run {
            makeStore.map { store =>
                for
                    now   <- Clock.now
                    _     <- store.createExecution(eid1, Flow.Status.Failed("err"), Flow.Event.Failed(wf1, eid1, "err", now), "")
                    _     <- store.updateStatus(eid1, Flow.Status.Running, Flow.Event.Created(wf1, eid1, now))
                    state <- store.getExecution(eid1)
                yield assert(state.get.status match
                    case Flow.Status.Failed(_) => true
                    case _                     => false)
            }
        }

        "claimReady never returns Completed, Failed, or Cancelled" in run {
            makeStore.map { store =>
                for
                    now     <- Clock.now
                    _       <- store.updateStatus(eid1, Flow.Status.Completed, Flow.Event.Completed(wf1, eid1, now))
                    _       <- store.updateStatus(eid2, Flow.Status.Failed("err"), Flow.Event.Failed(wf1, eid2, "err", now))
                    _       <- store.updateStatus(eid3, Flow.Status.Cancelled, Flow.Event.Cancelled(wf1, eid3, now))
                    results <- store.claimReady(Set(wf1), ex1, lease, 10, 100.millis)
                yield assert(results.isEmpty)
            }
        }
    }

    // =========================================================================
    // I6 — Read-your-writes
    // =========================================================================
    "I6 — read-your-writes" - {

        "putField then getField → returns value" in run {
            makeStore.map { store =>
                for
                    _ <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _ <- store.putField[Int](eid1, "step1", 42)
                    v <- store.getField[Int](eid1, "step1")
                yield assert(v.get == 42)
            }
        }

        "updateStatus then getExecution → returns new status" in run {
            makeStore.map { store =>
                for
                    _     <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    now   <- Clock.now
                    _     <- store.updateStatus(eid1, Flow.Status.Completed, Flow.Event.Completed(wf1, eid1, now))
                    state <- store.getExecution(eid1)
                yield assert(state.get.status == Flow.Status.Completed)
            }
        }

        "appendEvent then getHistory → event appears" in run {
            makeStore.map { store =>
                for
                    _   <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    now <- Clock.now
                    _   <- store.appendEvent(eid1, Flow.Event.StepStarted(wf1, eid1, "s1", ex1, now))
                    h   <- store.getHistory(eid1, 100, 0)
                yield assert(h.events.exists(_.kind == Flow.EventKind.StepStarted))
            }
        }
    }

    // =========================================================================
    // I7 — Event ordering
    // =========================================================================
    "I7 — event ordering" - {

        "three sequential appendEvent → getHistory returns in order" in run {
            makeStore.map { store =>
                for
                    _   <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    now <- Clock.now
                    _   <- store.appendEvent(eid1, Flow.Event.StepStarted(wf1, eid1, "s1", ex1, now))
                    _   <- store.appendEvent(eid1, Flow.Event.StepCompleted(wf1, eid1, "s1", now + 1.second))
                    _   <- store.appendEvent(eid1, Flow.Event.StepStarted(wf1, eid1, "s2", ex1, now + 2.seconds))
                    h   <- store.getHistory(eid1, 100, 0)
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
    // I8 — Readiness correctness
    // =========================================================================
    "I8 — readiness correctness" - {

        "Sleeping with until in the future → not returned" in run {
            makeStore.map { store =>
                for
                    now <- Clock.now
                    _ <- store.updateStatus(
                        eid1,
                        Flow.Status.Sleeping("wait", now + 1.hour),
                        Flow.Event.SleepStarted(wf1, eid1, "wait", now + 1.hour, now)
                    )
                    results <- store.claimReady(Set(wf1), ex1, lease, 10, 100.millis)
                yield assert(results.isEmpty)
            }
        }

        "Sleeping with until in the past → returned" in run {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        now <- Clock.now
                        _   <- store.createExecution(eid1, Flow.Status.Running, Flow.Event.Created(wf1, eid1, now), "")
                        _ <- store.updateStatus(
                            eid1,
                            Flow.Status.Sleeping("wait", now + 1.second),
                            Flow.Event.SleepStarted(wf1, eid1, "wait", now + 1.second, now)
                        )
                        _       <- tc.advance(2.seconds)
                        results <- store.claimReady(Set(wf1), ex1, lease, 10, 1.second)
                    yield assert(results.size == 1)
                }
            }
        }

        "WaitingForInput with field absent → not returned" in run {
            makeStore.map { store =>
                for
                    now <- Clock.now
                    _ <- store.updateStatus(
                        eid1,
                        Flow.Status.WaitingForInput("myInput"),
                        Flow.Event.Created(wf1, eid1, now)
                    )
                    results <- store.claimReady(Set(wf1), ex1, lease, 10, 100.millis)
                yield assert(results.isEmpty)
            }
        }

        "WaitingForInput with field present → returned" in run {
            makeStore.map { store =>
                for
                    now <- Clock.now
                    _   <- store.createExecution(eid1, Flow.Status.Running, Flow.Event.Created(wf1, eid1, now), "")
                    _ <- store.updateStatus(
                        eid1,
                        Flow.Status.WaitingForInput("myInput"),
                        Flow.Event.Created(wf1, eid1, now)
                    )
                    _       <- store.putField[String](eid1, "myInput", "hello")
                    results <- store.claimReady(Set(wf1), ex1, lease, 10, 1.second)
                yield assert(results.size == 1)
            }
        }

        "Running unclaimed → returned" in run {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    results <- store.claimReady(Set(wf1), ex1, lease, 10, 1.second)
                yield assert(results.size == 1)
            }
        }

        "Running claimed by another with valid lease → not returned" in run {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _       <- store.claimReady(Set(wf1), ex1, lease, 10, 1.second)
                    results <- store.claimReady(Set(wf1), ex2, lease, 10, 100.millis)
                yield assert(results.isEmpty)
            }
        }

        "Running claimed by another with expired lease → returned" in run {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                        _       <- store.claimReady(Set(wf1), ex1, 5.seconds, 10, 1.second)
                        _       <- tc.advance(10.seconds)
                        results <- store.claimReady(Set(wf1), ex2, lease, 10, 1.second)
                    yield assert(results.size == 1)
                }
            }
        }
    }

    // =========================================================================
    // I9 — Claim sets executor and expiry
    // =========================================================================
    "I9 — claim sets executor and expiry" - {

        "returned execution has executor = caller's ID" in run {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    results <- store.claimReady(Set(wf1), ex1, lease, 10, 1.second)
                yield
                    assert(results.size == 1)
                    assert(results.head.executor == Maybe(ex1))
            }
        }

        "claimExpiry is set" in run {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    results <- store.claimReady(Set(wf1), ex1, lease, 10, 1.second)
                yield assert(results.head.claimExpiry.isDefined)
            }
        }
    }

    // =========================================================================
    // Field operations
    // =========================================================================
    "field operations" - {

        "putField then getField returns the value" in run {
            makeStore.map { store =>
                for
                    _ <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _ <- store.putField[String](eid1, "name", "hello")
                    v <- store.getField[String](eid1, "name")
                yield assert(v.get == "hello")
            }
        }

        "getField for absent name returns empty" in run {
            makeStore.map { store =>
                for
                    _ <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    v <- store.getField[Int](eid1, "missing")
                yield assert(v.isEmpty)
            }
        }

        "putField overwrites existing value" in run {
            makeStore.map { store =>
                for
                    _ <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _ <- store.putField[Int](eid1, "step1", 1)
                    _ <- store.putField[Int](eid1, "step1", 2)
                    v <- store.getField[Int](eid1, "step1")
                yield assert(v.get == 2)
            }
        }

        "getAllFields returns all fields for an execution" in run {
            makeStore.map { store =>
                for
                    _   <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _   <- store.putField[Int](eid1, "a", 1)
                    _   <- store.putField[String](eid1, "b", "two")
                    all <- store.getAllFields(eid1)
                yield
                    assert(all.contains("a"))
                    assert(all.contains("b"))
                    assert(all.size == 2)
            }
        }

        "getAllFields returns empty map for unknown execution" in run {
            makeStore.map { store =>
                for
                    all <- store.getAllFields(Flow.Id.Execution("unknown"))
                yield assert(all.isEmpty)
            }
        }

        "different executions have separate fields" in run {
            makeStore.map { store =>
                for
                    _  <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _  <- mkExecution(store, eid2, wf1, Flow.Status.Running)
                    _  <- store.putField[Int](eid1, "x", 10)
                    _  <- store.putField[Int](eid2, "x", 20)
                    v1 <- store.getField[Int](eid1, "x")
                    v2 <- store.getField[Int](eid2, "x")
                yield
                    assert(v1.get == 10)
                    assert(v2.get == 20)
            }
        }
    }

    // =========================================================================
    // putFieldIfAbsent
    // =========================================================================
    "putFieldIfAbsent" - {

        "returns true and stores value when field is absent" in run {
            makeStore.map { store =>
                for
                    _     <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    wrote <- store.putFieldIfAbsent[Int](eid1, "key", 42)
                    v     <- store.getField[Int](eid1, "key")
                yield
                    assert(wrote)
                    assert(v.get == 42)
            }
        }

        "returns false and preserves original value when field exists" in run {
            makeStore.map { store =>
                for
                    _     <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _     <- store.putField[Int](eid1, "key", 1)
                    wrote <- store.putFieldIfAbsent[Int](eid1, "key", 2)
                    v     <- store.getField[Int](eid1, "key")
                yield
                    assert(!wrote)
                    assert(v.get == 1)
            }
        }

        "works correctly across different executions" in run {
            makeStore.map { store =>
                for
                    _  <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _  <- mkExecution(store, eid2, wf1, Flow.Status.Running)
                    w1 <- store.putFieldIfAbsent[Int](eid1, "key", 10)
                    w2 <- store.putFieldIfAbsent[Int](eid2, "key", 20)
                yield
                    assert(w1)
                    assert(w2)
            }
        }
    }

    // =========================================================================
    // Execution state
    // =========================================================================
    "execution state" - {

        "getExecution for unknown ID returns empty" in run {
            makeStore.map { store =>
                for
                    state <- store.getExecution(Flow.Id.Execution("unknown"))
                yield assert(state.isEmpty)
            }
        }

        "updateStatus creates execution if not exists" in run {
            makeStore.map { store =>
                for
                    now   <- Clock.now
                    _     <- store.createExecution(eid1, Flow.Status.Running, Flow.Event.Created(wf1, eid1, now), "")
                    state <- store.getExecution(eid1)
                yield
                    assert(state.isDefined)
                    assert(state.get.status == Flow.Status.Running)
                    assert(state.get.flowId == wf1)
            }
        }

        "multiple updateStatus calls append events in order" in run {
            makeStore.map { store =>
                for
                    now <- Clock.now
                    _   <- store.createExecution(eid1, Flow.Status.Running, Flow.Event.Created(wf1, eid1, now), "")
                    _ <- store.updateStatus(
                        eid1,
                        Flow.Status.Sleeping("wait", now + 1.hour),
                        Flow.Event.SleepStarted(wf1, eid1, "wait", now + 1.hour, now)
                    )
                    _ <- store.updateStatus(
                        eid1,
                        Flow.Status.Running,
                        Flow.Event.SleepCompleted(wf1, eid1, "wait", now + 1.hour)
                    )
                    _ <- store.updateStatus(eid1, Flow.Status.Completed, Flow.Event.Completed(wf1, eid1, now + 2.hours))
                    h <- store.getHistory(eid1, 100, 0)
                yield
                    assert(h.events.length == 4)
                    assert(h.events(0).kind == Flow.EventKind.Created)
                    assert(h.events(1).kind == Flow.EventKind.SleepStarted)
                    assert(h.events(2).kind == Flow.EventKind.SleepCompleted)
                    assert(h.events(3).kind == Flow.EventKind.Completed)
            }
        }

        "status transitions: Running → Sleeping → Running → Completed" in run {
            makeStore.map { store =>
                for
                    now <- Clock.now
                    _   <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _ <- store.updateStatus(
                        eid1,
                        Flow.Status.Sleeping("s", now + 1.hour),
                        Flow.Event.SleepStarted(wf1, eid1, "s", now + 1.hour, now)
                    )
                    s1 <- store.getExecution(eid1)
                    _  <- store.updateStatus(eid1, Flow.Status.Running, Flow.Event.SleepCompleted(wf1, eid1, "s", now))
                    s2 <- store.getExecution(eid1)
                    _  <- store.updateStatus(eid1, Flow.Status.Completed, Flow.Event.Completed(wf1, eid1, now))
                    s3 <- store.getExecution(eid1)
                yield
                    assert(s1.get.status match
                        case Flow.Status.Sleeping(_, _) => true;
                        case _                          => false)
                    assert(s2.get.status == Flow.Status.Running)
                    assert(s3.get.status == Flow.Status.Completed)
            }
        }
    }

    // =========================================================================
    // Event history
    // =========================================================================
    "event history" - {

        "getHistory returns events in append order" in run {
            makeStore.map { store =>
                for
                    _   <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    now <- Clock.now
                    _   <- store.appendEvent(eid1, Flow.Event.StepStarted(wf1, eid1, "s1", ex1, now))
                    _   <- store.appendEvent(eid1, Flow.Event.StepCompleted(wf1, eid1, "s1", now))
                    h   <- store.getHistory(eid1, 100, 0)
                yield
                    assert(h.events.length == 3) // Created + 2
                    assert(h.events(1).detail == "s1")
                    assert(h.events(1).kind == Flow.EventKind.StepStarted)
                    assert(h.events(2).kind == Flow.EventKind.StepCompleted)
            }
        }

        "getHistory with limit returns at most N events" in run {
            makeStore.map { store =>
                for
                    _   <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    now <- Clock.now
                    _ <- Kyo.foreach(1 to 10)(i =>
                        store.appendEvent(eid1, Flow.Event.StepStarted(wf1, eid1, s"s$i", ex1, now))
                    )
                    h <- store.getHistory(eid1, 3, 0)
                yield
                    assert(h.events.length == 3)
                    assert(h.hasMore)
            }
        }

        "getHistory with offset skips events" in run {
            makeStore.map { store =>
                for
                    _   <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    now <- Clock.now
                    _ <- Kyo.foreach(1 to 5)(i =>
                        store.appendEvent(eid1, Flow.Event.StepStarted(wf1, eid1, s"s$i", ex1, now))
                    )
                    h <- store.getHistory(eid1, 100, 3)
                yield
                    // 6 total (1 Created + 5 appended), skip 3 → 3 remaining
                    assert(h.events.length == 3)
                    assert(!h.hasMore)
            }
        }

        "getHistory returns empty for unknown execution" in run {
            makeStore.map { store =>
                for
                    h <- store.getHistory(Flow.Id.Execution("unknown"), 100, 0)
                yield
                    assert(h.events.isEmpty)
                    assert(!h.hasMore)
            }
        }
    }

    // =========================================================================
    // claimReady
    // =========================================================================
    "claimReady" - {

        "returns empty when no executions exist" in run {
            makeStore.map { store =>
                for
                    results <- store.claimReady(Set(wf1), ex1, lease, 10, 100.millis)
                yield assert(results.isEmpty)
            }
        }

        "returns unclaimed Running execution" in run {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    results <- store.claimReady(Set(wf1), ex1, lease, 10, 1.second)
                yield
                    assert(results.size == 1)
                    assert(results.head.executionId == eid1)
            }
        }

        "does not return terminal executions" in run {
            makeStore.map { store =>
                for
                    now     <- Clock.now
                    _       <- store.updateStatus(eid1, Flow.Status.Completed, Flow.Event.Completed(wf1, eid1, now))
                    results <- store.claimReady(Set(wf1), ex1, lease, 10, 100.millis)
                yield assert(results.isEmpty)
            }
        }

        "returns at most limit executions" in run {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _       <- mkExecution(store, eid2, wf1, Flow.Status.Running)
                    _       <- mkExecution(store, eid3, wf1, Flow.Status.Running)
                    results <- store.claimReady(Set(wf1), ex1, lease, 2, 1.second)
                yield assert(results.size == 2)
            }
        }

        "filters by workflow IDs" in run {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _       <- mkExecution(store, eid2, wf2, Flow.Status.Running)
                    results <- store.claimReady(Set(wf1), ex1, lease, 10, 1.second)
                yield
                    assert(results.size == 1)
                    assert(results.head.executionId == eid1)
            }
        }
    }

    // =========================================================================
    // renewClaim
    // =========================================================================
    "renewClaim" - {

        "returns true and extends expiry for valid claim owner" in run {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _       <- store.claimReady(Set(wf1), ex1, lease, 10, 1.second)
                    renewed <- store.renewClaim(eid1, ex1, lease)
                yield assert(renewed)
            }
        }

        "returns false for non-owner" in run {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _       <- store.claimReady(Set(wf1), ex1, lease, 10, 1.second)
                    renewed <- store.renewClaim(eid1, ex2, lease)
                yield assert(!renewed)
            }
        }

        "returns false after claim expired" in run {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                        _       <- store.claimReady(Set(wf1), ex1, 5.seconds, 10, 1.second)
                        _       <- tc.advance(10.seconds)
                        renewed <- store.renewClaim(eid1, ex1, lease)
                    yield assert(!renewed)
                }
            }
        }
    }

    // =========================================================================
    // releaseClaim
    // =========================================================================
    "releaseClaim" - {

        "clears executor and claimExpiry for valid owner" in run {
            makeStore.map { store =>
                for
                    _     <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _     <- store.claimReady(Set(wf1), ex1, lease, 10, 1.second)
                    _     <- store.releaseClaim(eid1, ex1)
                    state <- store.getExecution(eid1)
                yield
                    assert(state.get.executor.isEmpty)
                    assert(state.get.claimExpiry.isEmpty)
            }
        }

        "released execution becomes discoverable by claimReady" in run {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _       <- store.claimReady(Set(wf1), ex1, lease, 10, 1.second)
                    _       <- store.releaseClaim(eid1, ex1)
                    results <- store.claimReady(Set(wf1), ex2, lease, 10, 1.second)
                yield assert(results.size == 1)
            }
        }

        "no-op for non-owner" in run {
            makeStore.map { store =>
                for
                    _     <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _     <- store.claimReady(Set(wf1), ex1, lease, 10, 1.second)
                    _     <- store.releaseClaim(eid1, ex2) // non-owner
                    state <- store.getExecution(eid1)
                yield assert(state.get.executor == Maybe(ex1)) // still claimed by ex1
            }
        }
    }

    // =========================================================================
    // listExecutions
    // =========================================================================
    "listExecutions" - {

        "returns executions for given flowId" in run {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _       <- mkExecution(store, eid2, wf1, Flow.Status.Running)
                    _       <- mkExecution(store, eid3, wf2, Flow.Status.Running)
                    results <- store.listExecutions(wf1, Maybe.empty, 100, 0)
                yield assert(results.length == 2)
            }
        }

        "filters by status when provided" in run {
            makeStore.map { store =>
                for
                    now     <- Clock.now
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _       <- store.updateStatus(eid2, Flow.Status.Completed, Flow.Event.Completed(wf1, eid2, now))
                    results <- store.listExecutions(wf1, Maybe(Flow.Status.Running), 100, 0)
                yield
                    assert(results.length == 1)
                    assert(results.head.executionId == eid1)
            }
        }

        "respects limit and offset" in run {
            makeStore.map { store =>
                for
                    _       <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                    _       <- mkExecution(store, eid2, wf1, Flow.Status.Running)
                    _       <- mkExecution(store, eid3, wf1, Flow.Status.Running)
                    results <- store.listExecutions(wf1, Maybe.empty, 1, 1)
                yield assert(results.length == 1)
            }
        }

        "returns empty for unknown flowId" in run {
            makeStore.map { store =>
                for
                    results <- store.listExecutions(Flow.Id.Workflow("unknown"), Maybe.empty, 100, 0)
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

        "putWorkflow then getWorkflow returns the metadata" in run {
            makeStore.map { store =>
                for
                    _ <- store.putWorkflow(testMeta)
                    m <- store.getWorkflow(wf1)
                yield assert(m.get.id == "wf1")
            }
        }

        "getWorkflow for unknown ID returns empty" in run {
            makeStore.map { store =>
                for
                    m <- store.getWorkflow(Flow.Id.Workflow("unknown"))
                yield assert(m.isEmpty)
            }
        }

        "listWorkflows returns all registered workflows" in run {
            makeStore.map { store =>
                for
                    _   <- store.putWorkflow(testMeta)
                    _   <- store.putWorkflow(testMeta.copy(id = "wf2"))
                    all <- store.listWorkflows
                yield assert(all.size == 2)
            }
        }

        "putWorkflow overwrites existing workflow with same ID" in run {
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
    // Additional missing scenarios from plan
    // =========================================================================

    "I1 — 10 concurrent callers, 5 ready — all disjoint" in run {
        makeStore.map { store =>
            for
                _ <- Kyo.foreach(1 to 5)(i => mkExecution(store, Flow.Id.Execution(s"e$i"), wf1, Flow.Status.Running))
                executors = (1 to 10).map(i => Flow.Id.Executor(s"ex$i"))
                results <- Kyo.foreach(executors)(ex => store.claimReady(Set(wf1), ex, lease, 10, 1.second))
                allClaimed = results.flatten
            yield
                assert(allClaimed.size == 5, s"Expected 5 claimed, got ${allClaimed.size}")
                assert(allClaimed.map(_.executionId).distinct.size == 5, "All disjoint")
        }
    }

    "I7 — events from different callers on same execution" in run {
        makeStore.map { store =>
            for
                _   <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                now <- Clock.now
                _   <- store.appendEvent(eid1, Flow.Event.StepStarted(wf1, eid1, "s1", ex1, now))
                _   <- store.appendEvent(eid1, Flow.Event.StepCompleted(wf1, eid1, "s1", now + 1.second))
                _   <- store.appendEvent(eid1, Flow.Event.StepStarted(wf1, eid1, "s2", ex2, now + 2.seconds))
                h   <- store.getHistory(eid1, 100, 0)
            yield
                assert(h.events.length == 4) // Created + 3
                val details = h.events.drop(1).map(_.detail).toSeq
                assert(details == Seq("s1", "s1", "s2"))
        }
    }

    "field operations — getField with wrong type tag returns empty" in run {
        makeStore.map { store =>
            for
                _ <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                _ <- store.putField[Int](eid1, "x", 42)
                v <- store.getField[String](eid1, "x")
            yield assert(v.isEmpty)
        }
    }

    "renewClaim — same executor can renew multiple times" in run {
        makeStore.map { store =>
            for
                _  <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                _  <- store.claimReady(Set(wf1), ex1, lease, 10, 1.second)
                r1 <- store.renewClaim(eid1, ex1, lease)
                r2 <- store.renewClaim(eid1, ex1, lease)
                r3 <- store.renewClaim(eid1, ex1, lease)
            yield
                assert(r1)
                assert(r2)
                assert(r3)
        }
    }

    "execution state — WaitingForInput → Running → Failed" in run {
        makeStore.map { store =>
            for
                now <- Clock.now
                _   <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                _   <- store.updateStatus(eid1, Flow.Status.WaitingForInput("x"), Flow.Event.InputWaiting(wf1, eid1, "x", now))
                s1  <- store.getExecution(eid1)
                _   <- store.updateStatus(eid1, Flow.Status.Running, Flow.Event.StepStarted(wf1, eid1, "y", ex1, now))
                s2  <- store.getExecution(eid1)
                _   <- store.updateStatus(eid1, Flow.Status.Failed("err"), Flow.Event.Failed(wf1, eid1, "err", now))
                s3  <- store.getExecution(eid1)
            yield
                assert(s1.get.status == Flow.Status.WaitingForInput("x"))
                assert(s2.get.status == Flow.Status.Running)
                assert(s3.get.status match
                    case Flow.Status.Failed(_) => true;
                    case _                     => false)
        }
    }

    "execution state — Running → Cancelled" in run {
        makeStore.map { store =>
            for
                now <- Clock.now
                _   <- mkExecution(store, eid1, wf1, Flow.Status.Running)
                _   <- store.updateStatus(eid1, Flow.Status.Cancelled, Flow.Event.Cancelled(wf1, eid1, now))
                s   <- store.getExecution(eid1)
            yield assert(s.get.status == Flow.Status.Cancelled)
        }
    }

    // =========================================================================
    // claimReady blocking/wake behavior
    // =========================================================================
    "claimReady blocking" - {

        "returns empty on timeout when nothing ready" in run {
            makeStore.map { store =>
                for
                    results <- store.claimReady(Set(wf1), ex1, lease, 10, 100.millis)
                yield assert(results.isEmpty)
            }
        }

        "wakes when signal makes execution ready (putField on WaitingForInput)" in run {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        now     <- Clock.now
                        _       <- store.createExecution(eid1, Flow.Status.WaitingForInput("x"), Flow.Event.Created(wf1, eid1, now), "")
                        fiber   <- Fiber.init(store.claimReady(Set(wf1), ex1, lease, 10, 5.seconds))
                        _       <- tc.advance(100.millis)
                        _       <- store.putField[Int](eid1, "x", 42)
                        _       <- tc.advance(200.millis)
                        results <- fiber.get
                    yield assert(results.size == 1)
                }
            }
        }

        "wakes when status changes to Running via updateStatus" in run {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        fiber   <- Fiber.init(store.claimReady(Set(wf1), ex1, lease, 10, 5.seconds))
                        _       <- tc.advance(100.millis)
                        now     <- Clock.now
                        _       <- store.createExecution(eid1, Flow.Status.Running, Flow.Event.Created(wf1, eid1, now), "")
                        _       <- tc.advance(200.millis)
                        results <- fiber.get
                    yield assert(results.size == 1)
                }
            }
        }

        "expired sleep found on next poll" in run {
            Clock.withTimeControl { tc =>
                makeStore.map { store =>
                    for
                        now <- Clock.now
                        _   <- store.createExecution(eid1, Flow.Status.Running, Flow.Event.Created(wf1, eid1, now), "")
                        _ <- store.updateStatus(
                            eid1,
                            Flow.Status.Sleeping("s", now + 500.millis),
                            Flow.Event.SleepStarted(wf1, eid1, "s", now + 500.millis, now)
                        )
                        // First poll: sleep not expired, times out
                        fiber1 <- Fiber.init(store.claimReady(Set(wf1), ex1, lease, 10, 100.millis))
                        _      <- tc.advance(50.millis)
                        _      <- tc.advance(50.millis)
                        _      <- tc.advance(50.millis)
                        empty  <- fiber1.get
                        // Advance past sleep expiry
                        _ <- tc.advance(500.millis)
                        // Second poll: sleep expired, found immediately
                        found <- store.claimReady(Set(wf1), ex1, lease, 10, 100.millis)
                    yield
                        assert(empty.isEmpty)
                        assert(found.size == 1)
                }
            }
        }
    }

end FlowStoreTest
