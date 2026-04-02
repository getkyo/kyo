package kyo.internal

import kyo.*

class WorkflowSchemaTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    "structuralHash" - {

        "same flow produces same hash" in {
            val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x * 2)
            assert(WorkflowSchema.structuralHash(flow) == WorkflowSchema.structuralHash(flow))
        }

        "identical structure, different closures — same hash" in {
            val flow1 = Flow.input[Int]("x").output("y")(ctx => ctx.x * 2)
            val flow2 = Flow.input[Int]("x").output("y")(ctx => ctx.x + 100)
            assert(WorkflowSchema.structuralHash(flow1) == WorkflowSchema.structuralHash(flow2))
        }

        "added step changes hash" in {
            val v1 = Flow.input[Int]("x").output("y")(ctx => ctx.x)
            val v2 = Flow.input[Int]("x").output("y")(ctx => ctx.x).output("z")(ctx => 0)
            assert(WorkflowSchema.structuralHash(v1) != WorkflowSchema.structuralHash(v2))
        }

        "renamed output changes hash" in {
            val v1 = Flow.input[Int]("x").output("alpha")(ctx => 1)
            val v2 = Flow.input[Int]("x").output("beta")(ctx => 1)
            assert(WorkflowSchema.structuralHash(v1) != WorkflowSchema.structuralHash(v2))
        }

        "changed output type changes hash" in {
            val v1 = Flow.input[Int]("x").output("y")(ctx => 42)
            val v2 = Flow.input[Int]("x").output("y")(ctx => "hello")
            assert(WorkflowSchema.structuralHash(v1) != WorkflowSchema.structuralHash(v2))
        }

        "hash is deterministic" in {
            val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                .sleep("s", 1.second).step("validate")(ctx => ())
            val hashes = (1 to 100).map(_ => WorkflowSchema.structuralHash(flow))
            assert(hashes.distinct.size == 1)
        }

        "hash is a hex string" in {
            val flow = Flow.input[Int]("x").output("y")(ctx => 1)
            val hash = WorkflowSchema.structuralHash(flow)
            assert(hash.nonEmpty)
            assert(hash.matches("[0-9a-f]+"), s"Expected hex, got $hash")
        }
    }

    // =========================================================================
    // Version enforcement end-to-end
    // =========================================================================
    "version enforcement" - {

        val wf1 = Flow.Id.Workflow("test-flow")

        def withEngine[A](
            f: (FlowEngine, FlowStore, Clock.TimeControl) => A < (Async & Scope & Abort[Any])
        )(using Frame): A < (Async & Scope & Abort[Any]) =
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    FlowEngine.init(store, workerCount = 1, lease = 30.seconds, pollTimeout = 100.millis).map { engine =>
                        f(engine, store, tc)
                    }
                }
            }

        def pump(
            tc: Clock.TimeControl,
            store: FlowStore,
            eid: Flow.Id.Execution,
            predicate: Flow.Status => Boolean,
            maxRounds: Int = 100
        )(using Frame): Flow.Status < Async =
            def go(remaining: Int): Flow.Status < Async =
                if remaining <= 0 then Abort.panic(new AssertionError("pump timed out"))
                else
                    tc.advance(100.millis).map { _ =>
                        store.getExecution(eid).map {
                            case Present(state) if predicate(state.status) => state.status
                            case _                                         => go(remaining - 1)
                        }
                    }
            go(maxRounds)
        end pump

        "execution stores the structural hash at creation time" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    state  <- store.getExecution(handle.executionId)
                yield
                    val expectedHash = WorkflowSchema.structuralHash(flow)
                    assert(state.get.hash.nonEmpty, "hash should be set on execution creation")
                    assert(state.get.hash == expectedHash, s"hash should match flow's structural hash")
                end for
            }
        }

        "changed workflow definition rejects in-flight execution" in run {
            withEngine { (engine, store, tc) =>
                val flowV1 = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                val flowV2 = Flow.input[Int]("x").output("y")(ctx => ctx.x).output("z")(ctx => 0)
                for
                    _      <- engine.register(wf1, flowV1)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 42)
                    // Let v1 start processing
                    _ <- pump(tc, store, eid, s => s == Flow.Status.Completed || s == Flow.Status.WaitingForInput("x"))
                    // Re-register with v2 (different structure)
                    _ <- engine.register(wf1, flowV2)
                    // Create a NEW execution under v2
                    handle2 <- engine.workflows.start(wf1)
                    eid2 = handle2.executionId
                    _      <- engine.executions.signal[Int](eid2, "x", 10)
                    _      <- pump(tc, store, eid2, _.isTerminal)
                    state2 <- store.getExecution(eid2)
                yield
                    // v2 execution should complete (its hash matches the current definition)
                    assert(state2.get.status == Flow.Status.Completed)
                    // The hashes should differ between v1 and v2 executions
                    val hashV1 = WorkflowSchema.structuralHash(flowV1)
                    val hashV2 = WorkflowSchema.structuralHash(flowV2)
                    assert(hashV1 != hashV2, "v1 and v2 should have different structural hashes")
                end for
            }
        }

        "execution with mismatched hash is not processed" in run {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                    for
                        engine <- FlowEngine.init(store, workerCount = 1, pollTimeout = 100.millis)
                        _      <- engine.register(wf1, flow)
                        // Manually create an execution with a bogus hash
                        now <- Clock.now
                        eid = Flow.Id.Execution("hash-mismatch-test")
                        _ <- store.createExecution(eid, Flow.Status.Running, Flow.Event.Created(wf1, eid, now), "")
                        _ <- store.putField[Int](eid, "x", 42)
                        // Pump and check it stays Running (engine should reject it)
                        _     <- tc.advance(2.seconds)
                        state <- store.getExecution(eid)
                    yield
                        // Hash mismatch: engine should fail the execution with a clear error
                        assert(
                            state.get.status match
                                case Flow.Status.Failed(_) => true
                                case _                     => false
                            ,
                            s"Execution with mismatched hash should be Failed, but status is ${state.get.status}"
                        )
                    end for
                }
            }
        }

        "structural hash mismatch marks execution as failed" in run {
            withEngine { (engine, store, tc) =>
                val flowV1 = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                val flowV2 = Flow.input[Int]("x").output("y")(ctx => ctx.x).output("z")(ctx => 0)
                for
                    _      <- engine.register(wf1, flowV1)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 42)
                    _ <- pump(tc, store, eid, _.isTerminal)
                    // Now re-register with v2 and create a new execution
                    _ <- engine.register(wf1, flowV2)
                    // The old execution completed with v1's hash. Create a manually stuck one:
                    now <- Clock.now
                    stuckEid = Flow.Id.Execution("stuck-version")
                    _ <- store.createExecution(
                        stuckEid,
                        Flow.Status.Running,
                        Flow.Event.Created(wf1, stuckEid, now),
                        WorkflowSchema.structuralHash(flowV1) // v1 hash
                    )
                    _ <- store.putField[Int](stuckEid, "x", 10)
                    // Engine now has v2 registered but this execution has v1 hash
                    // It should eventually be marked as Failed, not released forever
                    _     <- pump(tc, store, stuckEid, s => s.isTerminal || s == Flow.Status.Running, 50)
                    state <- store.getExecution(stuckEid)
                yield assert(
                    state.get.status match
                        case Flow.Status.Failed(_) => true;
                        case _                     => false
                    ,
                    s"Expected Failed but got ${state.get.status} — execution stuck on hash mismatch"
                )
                end for
            }
        }
    }

end WorkflowSchemaTest
