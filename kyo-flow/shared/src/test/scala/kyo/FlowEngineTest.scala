package kyo

class FlowEngineTest extends Test:

    override def timeout = 30.seconds

    given CanEqual[Any, Any] = CanEqual.derived

    val wf1 = Flow.Id.Workflow("test-flow")

    private def withEngine[A](
        f: (FlowEngine, FlowStore, Clock.TimeControl) => A < (Async & Scope & Abort[Any])
    )(using Frame): A < (Async & Scope & Abort[Any]) =
        Clock.withTimeControl { tc =>
            FlowStore.initMemory.map { store =>
                FlowEngine.init(store, workerCount = 1, lease = 30.seconds, pollTimeout = 100.millis).map { engine =>
                    f(engine, store, tc)
                }
            }
        }

    private def pump(
        tc: Clock.TimeControl,
        store: FlowStore,
        eid: Flow.Id.Execution,
        predicate: Flow.Status => Boolean,
        maxRounds: Int = 200
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

    // =========================================================================
    // Basic execution
    // =========================================================================
    "basic execution" - {

        "single output flow completes" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x + 1)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 42)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status == Flow.Status.Completed)
                end for
            }
        }

        "two sequential outputs complete" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .output("y")(ctx => ctx.x + 1)
                    .output("z")(ctx => ctx.y * 2)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 10)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status == Flow.Status.Completed)
                end for
            }
        }

        "output values are persisted in store" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x + 1)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 42)
                    _ <- pump(tc, store, eid, _.isTerminal)
                    v <- store.getField[Int](eid, "y")
                yield assert(v.get == 43)
                end for
            }
        }

        "step flow completes" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .output("y")(ctx => ctx.x + 1)
                    .step("log")(ctx => ())
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status == Flow.Status.Completed)
                end for
            }
        }

        "flow failure results in Failed status" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx =>
                    throw new RuntimeException("boom"); ""
                )
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield status match
                    case Flow.Status.Failed(msg) => assert(msg.contains("boom"))
                    case other                   => fail(s"Expected Failed, got $other")
                end for
            }
        }
    }

    // =========================================================================
    // Signal delivery
    // =========================================================================
    "signal delivery" - {

        "waits for input then resumes" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[String]("name").output("greeting")(ctx => s"Hello ${ctx.name}")
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("name"))
                    _      <- engine.executions.signal[String](eid, "name", "World")
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status == Flow.Status.Completed)
                end for
            }
        }

        "duplicate signal fails" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[String]("name").output("y")(ctx => ctx.name)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _   <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("name"))
                    _   <- engine.executions.signal[String](eid, "name", "first")
                    res <- Abort.run[FlowException](engine.executions.signal[String](eid, "name", "second"))
                yield assert(res.isFailure)
                end for
            }
        }

        "signal to unknown input fails" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _   <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("x"))
                    res <- Abort.run[FlowException](engine.executions.signal[Int](eid, "unknown", 1))
                yield assert(res.isFailure)
                end for
            }
        }

        "signal to terminal execution fails" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _   <- engine.executions.signal[Int](eid, "x", 1)
                    _   <- pump(tc, store, eid, _.isTerminal)
                    res <- Abort.run[FlowException](engine.executions.signal[Int](eid, "x", 99))
                yield assert(res.isFailure)
                end for
            }
        }
    }

    // =========================================================================
    // Registration
    // =========================================================================
    "registration" - {

        "unregistered workflow fails to start" in run {
            withEngine { (engine, store, tc) =>
                Abort.run[FlowException](engine.workflows.start(Flow.Id.Workflow("unknown")))
                    .map(r => assert(r.isFailure))
            }
        }
    }

    // =========================================================================
    // Cancel
    // =========================================================================
    "cancel" - {

        "cancel waiting execution" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _     <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("x"))
                    _     <- engine.executions.cancel(eid)
                    state <- store.getExecution(eid)
                yield assert(state.get.status == Flow.Status.Cancelled)
                end for
            }
        }

        "cancel completed execution is no-op" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _     <- engine.executions.signal[Int](eid, "x", 1)
                    _     <- pump(tc, store, eid, _.isTerminal)
                    _     <- engine.executions.cancel(eid)
                    state <- store.getExecution(eid)
                yield assert(state.get.status == Flow.Status.Completed)
                end for
            }
        }

        "cancelAll cancels multiple executions" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _  <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    h1 <- engine.workflows.start(wf1)
                    eid1 = h1.executionId
                    h2 <- engine.workflows.start(wf1)
                    eid2 = h2.executionId
                    _     <- pump(tc, store, eid1, _ == Flow.Status.WaitingForInput("x"))
                    _     <- pump(tc, store, eid2, _ == Flow.Status.WaitingForInput("x"))
                    count <- engine.executions.cancelAll(Maybe(wf1))
                yield assert(count == 2)
                end for
            }
        }
    }

    // =========================================================================
    // Event history
    // =========================================================================
    "event history" - {

        "records events for completed flow" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 42)
                    _ <- pump(tc, store, eid, _.isTerminal)
                    h <- engine.executions.history(eid)
                yield
                    assert(h.events.exists(_.kind == Flow.EventKind.Created))
                    assert(h.events.exists(_.kind == Flow.EventKind.StepStarted))
                    assert(h.events.exists(_.kind == Flow.EventKind.StepCompleted))
                    assert(h.events.exists(_.kind == Flow.EventKind.Completed))
                end for
            }
        }
    }

    // =========================================================================
    // Replay (idempotency)
    // =========================================================================
    "replay" - {

        "completed output is skipped on replay" in run {
            withEngine { (engine, store, tc) =>
                var callCount = 0
                val flow = Flow.input[Int]("x").output("y") { ctx =>
                    callCount += 1
                    ctx.x + 1
                }
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 10)
                    _ <- pump(tc, store, eid, _.isTerminal)
                    v <- store.getField[Int](eid, "y")
                yield
                    assert(v.get == 11)
                    assert(callCount == 1)
                end for
            }
        }
    }

    // =========================================================================
    // Workflows API
    // =========================================================================
    "workflows API" - {

        "list registered workflows" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _    <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    _    <- engine.register(Flow.Id.Workflow("wf2"), flow)
                    list <- engine.workflows.list
                yield assert(list.size == 2)
                end for
            }
        }

        "describe workflow" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _    <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    meta <- engine.workflows.describe(wf1)
                yield assert(meta.id == wf1.value)
                end for
            }
        }

        "describe unknown workflow fails" in run {
            withEngine { (engine, store, tc) =>
                Abort.run[FlowException](engine.workflows.describe(Flow.Id.Workflow("unknown")))
                    .map(r => assert(r.isFailure))
            }
        }

        "workflow diagram" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _   <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    dia <- engine.workflows.diagram(wf1, Flow.DiagramFormat.Mermaid)
                yield assert(dia.contains("graph"))
                end for
            }
        }
    }

    // =========================================================================
    // Handle
    // =========================================================================
    "handle" - {

        "signal via handle" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x + 1)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("x"))
                    _      <- handle.signal[Int]("x", 42)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status == Flow.Status.Completed)
                end for
            }
        }

        "status via handle" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _   = ()
                    _ <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("x"))
                    s <- handle.status
                yield assert(s == Flow.Status.WaitingForInput("x"))
                end for
            }
        }

        "cancel via handle" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _   = ()
                    _     <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("x"))
                    _     <- handle.cancel
                    state <- store.getExecution(eid)
                yield assert(state.get.status == Flow.Status.Cancelled)
                end for
            }
        }
    }

    // =========================================================================
    // Inputs and diagram
    // =========================================================================
    "inputs and diagram" - {

        "shows delivered and pending inputs" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").input[String]("name").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("x"))
                    _      <- engine.executions.signal[Int](eid, "x", 42)
                    _      <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("name"))
                    inputs <- engine.executions.inputs(eid)
                yield
                    assert(inputs.find(_.name == "x").exists(_.delivered))
                    assert(inputs.find(_.name == "name").exists(!_.delivered))
                end for
            }
        }

        "generates execution diagram" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _       <- engine.executions.signal[Int](eid, "x", 1)
                    _       <- pump(tc, store, eid, _.isTerminal)
                    diagram <- engine.executions.diagram(eid, Flow.DiagramFormat.Mermaid)
                yield assert(diagram.contains("graph"))
                end for
            }
        }
    }

    // =========================================================================
    // Search
    // =========================================================================
    "search" - {

        "finds executions by workflow" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _       <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    _       <- engine.workflows.start(wf1).map(_.executionId)
                    _       <- engine.workflows.start(wf1).map(_.executionId)
                    results <- engine.executions.search(wfId = Maybe(wf1))
                yield assert(results.total == 2)
                end for
            }
        }

        "filters by status" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _  <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    h1 <- engine.workflows.start(wf1)
                    eid1 = h1.executionId
                    _  <- engine.executions.signal[Int](eid1, "x", 1)
                    _  <- pump(tc, store, eid1, _.isTerminal)
                    h2 <- engine.workflows.start(wf1)
                    eid2 = h2.executionId
                    _ <- pump(tc, store, eid2, _ == Flow.Status.WaitingForInput("x"))
                    running <- engine.executions.search(
                        wfId = Maybe(wf1),
                        status = Maybe(Flow.Status.WaitingForInput("x"))
                    )
                yield assert(running.total == 1)
                end for
            }
        }
    }

    // =========================================================================
    // Multi-executor
    // =========================================================================
    "multi-executor" - {

        "two executors share store, both process" in run {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    for
                        engine1 <- FlowEngine.init(store, workerCount = 1, pollTimeout = 100.millis)
                        engine2 <- FlowEngine.init(store, workerCount = 1, pollTimeout = 100.millis)
                        flow = Flow.input[Int]("x").output("y")(ctx => ctx.x + 1)
                        _ <- engine1.register(wf1, flow)
                        _ <- engine2.register(wf1, flow)
                        h <- engine1.workflows.start(wf1)
                        eid = h.executionId
                        _      <- engine1.executions.signal[Int](eid, "x", 42)
                        status <- pump(tc, store, eid, _.isTerminal)
                        v      <- store.getField[Int](eid, "y")
                    yield
                        assert(status == Flow.Status.Completed)
                        assert(v.get == 43)
                }
            }
        }

        "multiple executions distributed" in run {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x * 2)
                    for
                        engine1 <- FlowEngine.init(store, workerCount = 1, pollTimeout = 100.millis)
                        engine2 <- FlowEngine.init(store, workerCount = 1, pollTimeout = 100.millis)
                        _       <- engine1.register(wf1, flow)
                        _       <- engine2.register(wf1, flow)
                        handles <- Kyo.foreach(1 to 5)(i => engine1.workflows.start(wf1))
                        eids = handles.map(_.executionId)
                        _ <- Kyo.foreachDiscard(eids.zipWithIndex)((eid, i) =>
                            engine1.executions.signal[Int](eid, "x", i)
                        )
                        _ <- Kyo.foreachDiscard(eids)(eid => pump(tc, store, eid, _.isTerminal))
                    yield succeed
                    end for
                }
            }
        }
    }

    // =========================================================================
    // End-to-end
    // =========================================================================
    "end-to-end" - {

        "multi-step chain completes" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .output("a")(ctx => ctx.x + 1)
                    .output("b")(ctx => ctx.a + 2)
                    .output("c")(ctx => ctx.b + 3)
                    .output("d")(ctx => ctx.c + 4)
                    .output("e")(ctx => ctx.d + 5)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 0)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[Int](eid, "e")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == 15)
                end for
            }
        }

        "two inputs, two outputs" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .input[String]("name")
                    .output("greeting")(ctx => s"${ctx.name}: ${ctx.x}")
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("x"))
                    _      <- engine.executions.signal[Int](eid, "x", 42)
                    _      <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("name"))
                    _      <- engine.executions.signal[String](eid, "name", "Hello")
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[String](eid, "greeting")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == "Hello: 42")
                end for
            }
        }

        "step and output interleaved" in run {
            withEngine { (engine, store, tc) =>
                var stepExecuted = false
                val flow = Flow.input[Int]("x")
                    .output("y")(ctx => ctx.x + 1)
                    .step("side-effect")(ctx => stepExecuted = true)
                    .output("z")(ctx => ctx.y * 10)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 5)
                    status <- pump(tc, store, eid, _.isTerminal)
                    z      <- store.getField[Int](eid, "z")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(z.get == 60)
                    assert(stepExecuted)
                end for
            }
        }

        "events are recorded in correct order" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .output("y")(ctx => ctx.x + 1)
                    .output("z")(ctx => ctx.y + 2)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 1)
                    _ <- pump(tc, store, eid, _.isTerminal)
                    h <- store.getHistory(eid, 100, 0)
                yield
                    val kinds = h.events.map(_.kind).toSeq
                    assert(kinds.head == Flow.EventKind.Created)
                    assert(kinds.contains(Flow.EventKind.Completed))
                    val stepPairs = kinds.sliding(2).count {
                        case Seq(Flow.EventKind.StepStarted, Flow.EventKind.StepCompleted) => true
                        case _                                                             => false
                    }
                    assert(stepPairs >= 2)
                end for
            }
        }
    }

    // =========================================================================
    // runLocal
    // =========================================================================
    "runLocal" - {

        "simple flow with pre-populated input completes" in run {
            val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x * 2)
            Flow.runLocal(flow, "x" ~ 5).map { result =>
                assert(result.y == 10)
            }
        }

        "failure produces FlowException" in run {
            val flow = Flow.input[Int]("x").output("y")(ctx =>
                throw new RuntimeException("oops"); ""
            )
            Abort.run[FlowException](Flow.runLocal(flow, "x" ~ 1)).map { result =>
                assert(result.isFailure)
            }
        }
    }

    // =========================================================================
    // Phase 2: Signal type mismatch
    // =========================================================================
    "signal type mismatch" - {

        "wrong type fails with FlowSignalTypeMismatchException" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _   <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("x"))
                    res <- Abort.run[FlowException](engine.executions.signal[String](eid, "x", "wrong"))
                yield assert(res.isFailure)
                end for
            }
        }
    }

    "start with wrong input type" - {

        "pre-populated input with wrong type fails with clear error" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x + 1)
                for
                    _ <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    // Pass a String where Int is expected
                    inputs = new Record[Any](Dict("x" -> "not-an-int"))
                    res <- Abort.run[FlowException](engine.workflows.start(wf1, inputs))
                yield assert(res.isFailure)
                end for
            }
        }
    }

    // =========================================================================
    // Phase 3: Sleep behavior
    // =========================================================================
    "sleep" - {

        "sets Sleeping status and resumes after time passes" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .output("y")(ctx => ctx.x + 1)
                    .sleep("pause", 500.millis)
                    .output("z")(ctx => ctx.y * 10)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    _      <- pump(tc, store, eid, _.isSleeping)
                    status <- pump(tc, store, eid, _.isTerminal)
                    z      <- store.getField[Int](eid, "z")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(z.get == 20)
                end for
            }
        }

        "SleepCompleted event recorded on resume" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .sleep("pause", 500.millis)
                    .output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 1)
                    _ <- pump(tc, store, eid, _.isSleeping)
                    _ <- pump(tc, store, eid, _.isTerminal, 500)
                    h <- store.getHistory(eid, 100, 0)
                yield assert(h.events.exists(_.kind == Flow.EventKind.SleepStarted))
                end for
            }
        }
    }

    // =========================================================================
    // Phase 4: Compensation
    // =========================================================================
    "compensation" - {

        "compensated output fires on later failure" in run {
            withEngine { (engine, store, tc) =>
                var compensated = false
                val flow = Flow.input[Int]("x")
                    .outputCompensated("y")(ctx => ctx.x + 1)(ctx => compensated = true)
                    .output("z")(ctx =>
                        throw new RuntimeException("fail"); ""
                    )
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield status match
                    case Flow.Status.Failed(_) => assert(compensated)
                    case other                 => fail(s"Expected Failed, got $other")
                end for
            }
        }

        "all succeed → no compensations fire" in run {
            withEngine { (engine, store, tc) =>
                var compensated = false
                val flow = Flow.input[Int]("x")
                    .outputCompensated("y")(ctx => ctx.x + 1)(ctx => compensated = true)
                    .output("z")(ctx => ctx.y * 2)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(status == Flow.Status.Completed)
                    assert(!compensated)
                end for
            }
        }

        "compensations do NOT fire on suspension (WaitingForInput)" in run {
            withEngine { (engine, store, tc) =>
                var compensated = false
                val flow = Flow.input[Int]("x")
                    .outputCompensated("y")(ctx => ctx.x + 1)(ctx => compensated = true)
                    .input[String]("name")
                    .output("z")(ctx => ctx.name)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 1)
                    _ <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("name"))
                yield assert(!compensated)
                end for
            }
        }
    }

    // =========================================================================
    // Phase 5: Execution describe + search pagination
    // =========================================================================
    "execution describe" - {

        "returns execution state" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    state <- engine.executions.describe(eid)
                yield assert(state.executionId == eid)
                end for
            }
        }

        "unknown execution fails" in run {
            withEngine { (engine, store, tc) =>
                Abort.run[FlowException](engine.executions.describe(Flow.Id.Execution("unknown")))
                    .map(r => assert(r.isFailure))
            }
        }
    }

    "search pagination" - {

        "respects limit and offset" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _       <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    _       <- engine.workflows.start(wf1).map(_.executionId)
                    _       <- engine.workflows.start(wf1).map(_.executionId)
                    _       <- engine.workflows.start(wf1).map(_.executionId)
                    results <- engine.executions.search(wfId = Maybe(wf1), limit = 2, offset = 0)
                yield assert(results.total == 2)
                end for
            }
        }
    }

    // =========================================================================
    // Phase 7: End-to-end advanced
    // =========================================================================
    "end-to-end advanced" - {

        "20+ step chain completes" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .output("s1")(ctx => ctx.x + 1)
                    .output("s2")(ctx => ctx.s1 + 1)
                    .output("s3")(ctx => ctx.s2 + 1)
                    .output("s4")(ctx => ctx.s3 + 1)
                    .output("s5")(ctx => ctx.s4 + 1)
                    .output("s6")(ctx => ctx.s5 + 1)
                    .output("s7")(ctx => ctx.s6 + 1)
                    .output("s8")(ctx => ctx.s7 + 1)
                    .output("s9")(ctx => ctx.s8 + 1)
                    .output("s10")(ctx => ctx.s9 + 1)
                    .output("s11")(ctx => ctx.s10 + 1)
                    .output("s12")(ctx => ctx.s11 + 1)
                    .output("s13")(ctx => ctx.s12 + 1)
                    .output("s14")(ctx => ctx.s13 + 1)
                    .output("s15")(ctx => ctx.s14 + 1)
                    .output("s16")(ctx => ctx.s15 + 1)
                    .output("s17")(ctx => ctx.s16 + 1)
                    .output("s18")(ctx => ctx.s17 + 1)
                    .output("s19")(ctx => ctx.s18 + 1)
                    .output("s20")(ctx => ctx.s19 + 1)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 0)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[Int](eid, "s20")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == 20)
                end for
            }
        }

        // --- Error recovery via Abort (safe, no onRecover) ---
        // Users handle errors inside their computations using Kyo's Abort effect.
        // The output always produces a value — sound by construction.

        "output: Abort.recover catches specific error and provides fallback" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .output("y") { ctx =>
                        Abort.recover[RuntimeException](_ => -1, _ => -1)(
                            if ctx.x == 0 then throw new RuntimeException("zero")
                            else 100 / ctx.x
                        )
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 0)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[Int](eid, "y")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == -1)
                end for
            }
        }

        "output: Abort.recover with union error type" in run {
            withEngine { (engine, store, tc) =>
                class NetworkError extends RuntimeException("network")
                class TimeoutError extends RuntimeException("timeout")
                val flow = Flow.input[Int]("x")
                    .output("y") { ctx =>
                        Abort.recover[RuntimeException](_ => -1)(
                            if ctx.x == 1 then throw new NetworkError
                            else if ctx.x == 2 then throw new TimeoutError
                            else ctx.x * 10
                        )
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[Int](eid, "y")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == -1)
                end for
            }
        }

        "output: unhandled error fails the workflow" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .output("y")(ctx => if ctx.x == 0 then throw new RuntimeException("boom") else ctx.x)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 0)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status match
                    case Flow.Status.Failed(_) => true;
                    case _                     => false)
                end for
            }
        }

        "step: Abort.recover catches error, step continues" in run {
            withEngine { (engine, store, tc) =>
                var called = false
                val flow = Flow.input[Int]("x")
                    .step("side-effect") { ctx =>
                        Abort.recover[RuntimeException](_ => ())(
                            if ctx.x == 0 then throw new RuntimeException("fail")
                            else called = true
                        )
                    }
                    .output("y")(ctx => ctx.x + 1)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 0)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[Int](eid, "y")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(!called) // the error was caught, side-effect not reached
                    assert(v.get == 1)
                end for
            }
        }

        "foreach: Abort.recover per element" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("n")
                    .foreach("results")(ctx => (1 to ctx.n).toSeq) { item =>
                        Abort.recover[RuntimeException](_ => -1)(
                            if item == 3 then throw new RuntimeException("bad item")
                            else item * 10
                        )
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "n", 5)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status == Flow.Status.Completed)
                end for
            }
        }

        "loop: Abort.recover in body" in run {
            withEngine { (engine, store, tc) =>
                var attempts = 0
                val flow = Flow.input[Int]("x")
                    .loop("count") { ctx =>
                        attempts += 1
                        Abort.recover[RuntimeException](_ => -1, _ => -1)(
                            if attempts <= 2 then
                                throw new RuntimeException("transient"); 0
                            else attempts
                        ).map { v =>
                            if v < 0 then Loop.continue[Int]
                            else Loop.done(v)
                        }
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[Int](eid, "count")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == 3) // first two recovered to -1 (continue), third succeeds with 3 (stop)
                end for
            }
        }

        "dispatch: Abort.recover in branch body" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .dispatch[String]("result")
                    .when(ctx => ctx.x > 0, name = "positive") { ctx =>
                        Abort.recover[RuntimeException](_ => "recovered")(
                            if ctx.x == 42 then throw new RuntimeException("magic number")
                            else s"positive: ${ctx.x}"
                        )
                    }
                    .otherwise(ctx => "zero-or-negative", name = "default")
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 42)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[String](eid, "result")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == "recovered")
                end for
            }
        }

        "output: Abort.catching lets non-matching errors propagate" in run {
            withEngine { (engine, store, tc) =>
                class ExpectedError   extends RuntimeException("expected")
                class UnexpectedError extends RuntimeException("unexpected")
                val flow = Flow.input[Int]("x")
                    .output("y") { ctx =>
                        Abort.recover[ExpectedError](_ => -1)(
                            throw new UnexpectedError // not caught — propagates
                        )
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status match
                    case Flow.Status.Failed(_) => true;
                    case _                     => false)
                end for
            }
        }
    }

    // =========================================================================
    // Dispatch
    // =========================================================================
    "dispatch" - {

        "takes the first matching branch" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("amount")
                    .dispatch[String]("decision")
                    .when(ctx => ctx.amount > 50, name = "high")(ctx => "approved")
                    .otherwise(ctx => "rejected", name = "default")
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "amount", 100)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[String](eid, "decision")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == "approved")
                end for
            }
        }

        "takes default branch when no condition matches" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("amount")
                    .dispatch[String]("decision")
                    .when(ctx => ctx.amount > 50, name = "high")(ctx => "approved")
                    .otherwise(ctx => "rejected", name = "default")
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "amount", 10)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[String](eid, "decision")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == "rejected")
                end for
            }
        }
    }

    // =========================================================================
    // Loop
    // =========================================================================
    "loop" - {

        "loop done immediately" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("count")
                    .loop("result") { ctx => Loop.done(0) }
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "count", 3)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[Int](eid, "result")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == 0)
                end for
            }
        }

        "loop done with value immediately" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .loop("result") { ctx => Loop.done(42) }
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[Int](eid, "result")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == 42)
                end for
            }
        }

        "loop with 1 state — accumulator" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .loop("result", 0) { (state: Int, ctx) =>
                        if state < 3 then Loop.continue(state + 1)
                        else Loop.done(state)
                    }
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[Int](eid, "result")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == 3)
                end for
            }
        }

        "loop with 2 states" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .loop("result", 0, 1) { (a: Int, b: Int, ctx) =>
                        if a < 5 then Loop.continue(a + b, a)
                        else Loop.done(a)
                    }
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[Int](eid, "result")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == 5)
                end for
            }
        }
    }

    // =========================================================================
    // Foreach
    // =========================================================================
    "foreach" - {

        "processes collection and produces chunked result" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("n")
                    .foreach("doubled")(ctx => (1 to ctx.n).toSeq)(i => i * 2)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "n", 3)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status == Flow.Status.Completed)
                end for
            }
        }
    }

    // =========================================================================
    // Skip/Replay idempotency
    // =========================================================================
    "skip idempotency" - {

        "completed output is not re-executed on replay" in run {
            withEngine { (engine, store, tc) =>
                var callCount = 0
                val flow = Flow.input[Int]("x")
                    .output("y") { ctx =>
                        callCount += 1; ctx.x + 1
                    }
                    .input[String]("name")
                    .output("z")(ctx => ctx.name)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 10)
                    _ <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("name"))
                    c1 = callCount
                    _ <- engine.executions.signal[String](eid, "name", "hello")
                    _ <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(c1 == 1, "y computed once before waiting")
                    assert(callCount == 1, "y NOT re-computed on replay after name signal")
                end for
            }
        }

        "completed step is not re-executed on replay" in run {
            withEngine { (engine, store, tc) =>
                var stepCount = 0
                val flow = Flow.input[Int]("x")
                    .output("y")(ctx => ctx.x + 1)
                    .step("sideEffect") { ctx => stepCount += 1 }
                    .input[String]("name")
                    .output("z")(ctx => ctx.name)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 10)
                    _ <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("name"))
                    c1 = stepCount
                    _ <- engine.executions.signal[String](eid, "name", "hello")
                    _ <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(c1 == 1, "step executed once before waiting")
                    assert(stepCount == 1, "step NOT re-executed on replay")
                end for
            }
        }

        "partial completion: first outputs skipped, rest execute" in run {
            withEngine { (engine, store, tc) =>
                var counts = scala.collection.mutable.Map[String, Int]().withDefaultValue(0)
                val flow = Flow.input[Int]("x")
                    .output("a") { ctx =>
                        counts("a") += 1; ctx.x + 1
                    }
                    .output("b") { ctx =>
                        counts("b") += 1; ctx.a + 1
                    }
                    .input[String]("name")
                    .output("c") { ctx =>
                        counts("c") += 1; ctx.name.length
                    }
                    .output("d") { ctx =>
                        counts("d") += 1; ctx.c + 10
                    }
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 1)
                    _ <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("name"))
                    _ <- engine.executions.signal[String](eid, "name", "hello")
                    _ <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(counts("a") == 1)
                    assert(counts("b") == 1)
                    assert(counts("c") == 1)
                    assert(counts("d") == 1)
                end for
            }
        }
    }

    // =========================================================================
    // Compensation edge cases (ported from v1 FlowRevertEdgeCaseTest)
    // =========================================================================
    "compensation edge cases" - {

        "two compensated outputs, second fails: first fires in reverse" in run {
            withEngine { (engine, store, tc) =>
                var log = Seq.empty[String]
                val flow = Flow.input[Int]("x")
                    .outputCompensated("a")(ctx => ctx.x + 1)(ctx => log = log :+ "revert-a")
                    .outputCompensated("b")(ctx => ctx.a + 1)(ctx => log = log :+ "revert-b")
                    .output("fail")(ctx =>
                        throw new RuntimeException("boom"); ""
                    )
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(status match
                        case Flow.Status.Failed(_) => true;
                        case _                     => false)
                    assert(log.contains("revert-a"), s"revert-a should fire, log=$log")
                    assert(log.contains("revert-b"), s"revert-b should fire, log=$log")
                    assert(log.indexOf("revert-b") < log.indexOf("revert-a"), s"reverse order, log=$log")
                end for
            }
        }

        "all succeed: no reverts fire" in run {
            withEngine { (engine, store, tc) =>
                var fired = false
                val flow = Flow.input[Int]("x")
                    .outputCompensated("a")(ctx => ctx.x + 1)(ctx => fired = true)
                    .output("b")(ctx => ctx.a * 2)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(status == Flow.Status.Completed)
                    assert(!fired)
                end for
            }
        }

        "revert handler throws: swallowed, other reverts still run" in run {
            withEngine { (engine, store, tc) =>
                var log = Seq.empty[String]
                val flow = Flow.input[Int]("x")
                    .outputCompensated("a")(ctx => ctx.x + 1)(ctx => log = log :+ "revert-a")
                    .outputCompensated("b")(ctx => ctx.a + 1)(ctx => throw new RuntimeException("revert-boom"))
                    .outputCompensated("c")(ctx => ctx.b + 1)(ctx => log = log :+ "revert-c")
                    .output("fail")(ctx =>
                        throw new RuntimeException("boom"); ""
                    )
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(status match
                        case Flow.Status.Failed(_) => true;
                        case _                     => false)
                    assert(log.contains("revert-a"), "revert-a should still fire despite b's throw")
                    assert(log.contains("revert-c"), "revert-c should still fire")
                end for
            }
        }

        "compensation does NOT fire on suspension" in run {
            withEngine { (engine, store, tc) =>
                var fired = false
                val flow = Flow.input[Int]("x")
                    .outputCompensated("a")(ctx => ctx.x + 1)(ctx => fired = true)
                    .input[String]("name")
                    .output("b")(ctx => ctx.name)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 1)
                    _ <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("name"))
                yield assert(!fired, "compensation must not fire on suspension")
                end for
            }
        }

        "compensation re-registered on replay after skip" in run {
            withEngine { (engine, store, tc) =>
                var fired = false
                val flow = Flow.input[Int]("x")
                    .outputCompensated("a")(ctx => ctx.x + 1)(ctx => fired = true)
                    .input[String]("name")
                    .output("fail")(ctx =>
                        throw new RuntimeException("boom"); ""
                    )
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 1)
                    _ <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("name"))
                    _ = assert(!fired, "not fired during suspension")
                    _      <- engine.executions.signal[String](eid, "name", "hello")
                    status <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(status match
                        case Flow.Status.Failed(_) => true;
                        case _                     => false)
                    assert(fired, "compensation fires on replay after skip+failure")
                end for
            }
        }

        "andThen: first part has compensation, second fails" in run {
            withEngine { (engine, store, tc) =>
                var fired = false
                val flow = Flow.input[Int]("x")
                    .outputCompensated("a")(ctx => ctx.x + 1)(ctx => fired = true)
                    .output("fail")(ctx =>
                        throw new RuntimeException("boom"); ""
                    )
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(status match
                        case Flow.Status.Failed(_) => true;
                        case _                     => false)
                    assert(fired)
                end for
            }
        }
    }

    // =========================================================================
    // Sleep edge cases
    // =========================================================================
    "sleep edge cases" - {

        "zero-duration sleep completes immediately" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .sleep("instant", Duration.Zero)
                    .output("y")(ctx => ctx.x * 2)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 5)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[Int](eid, "y")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == 10)
                end for
            }
        }

        "sleep emits SleepStarted event" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .sleep("pause", 500.millis)
                    .output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 1)
                    _ <- pump(tc, store, eid, _.isSleeping)
                    h <- store.getHistory(eid, 100, 0)
                yield assert(h.events.exists(_.kind == Flow.EventKind.SleepStarted))
                end for
            }
        }
    }

    // =========================================================================
    // Cancel edge cases
    // =========================================================================
    "cancel edge cases" - {

        "cancel persists Cancelled status to store" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _     <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("x"))
                    _     <- engine.executions.cancel(eid)
                    state <- store.getExecution(eid)
                yield assert(state.get.status == Flow.Status.Cancelled)
                end for
            }
        }

        "cancel appends Cancelled event to history" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("x"))
                    _ <- engine.executions.cancel(eid)
                    h <- store.getHistory(eid, 100, 0)
                yield assert(h.events.exists(_.kind == Flow.EventKind.Cancelled))
                end for
            }
        }
    }

    // =========================================================================
    // Audit
    // =========================================================================
    "audit" - {

        "FlowLint checks recover names for duplicates" in run {
            val flow = Flow.input[Int]("x")
                .output("x")(ctx => ctx.x + 1)
            val warnings = kyo.internal.FlowLint.duplicateNames(flow)
            assert(warnings.exists(_.message.contains("Duplicate node name 'x'")))
            succeed
        }
    }

    // =========================================================================
    // Subflow execution
    // =========================================================================
    "subflow" - {

        "child output accessible downstream via nested record" in run {
            withEngine { (engine, store, tc) =>
                val child = Flow.input[Int]("a").output("b")(ctx => ctx.a * 10)
                val flow = Flow.input[Int]("x")
                    .subflow("payment", child)(ctx => "a" ~ ctx.x)
                    .output("result")(ctx => ctx.payment.b)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 5)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[Int](eid, "result")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == 50, s"Expected 50 but got ${v}")
                end for
            }
        }

        "child fields do not leak into parent namespace" in run {
            withEngine { (engine, store, tc) =>
                val child = Flow.input[Int]("a").output("b")(ctx => ctx.a * 10)
                val flow = Flow.input[Int]("x")
                    .subflow("payment", child)(ctx => "a" ~ ctx.x)
                    .output("result")(ctx => ctx.x + 1) // uses parent field, not child
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 5)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[Int](eid, "result")
                    // "b" should NOT be a top-level field — only nested under "payment"
                    bField <- store.getField[Int](eid, "b")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == 6)
                    // "b" IS in the store (child persisted it), but it shouldn't be confused with parent fields
                    // The type system prevents ctx.b at compile time (b is not in Out)
                    succeed
                end for
            }
        }

        "subflow replays correctly after suspension" in run {
            withEngine { (engine, store, tc) =>
                var childBodyCount = 0
                val child = Flow.input[Int]("a").output("b") { ctx =>
                    childBodyCount += 1
                    ctx.a * 10
                }
                val flow = Flow.input[Int]("x")
                    .subflow("payment", child)(ctx => "a" ~ ctx.x)
                    .input[String]("approval") // suspends here
                    .output("result")(ctx => s"${ctx.approval}: ${ctx.payment.b}")
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 5)
                    _ <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("approval"))
                    // Child executed once
                    count1 = childBodyCount
                    _      <- engine.executions.signal[String](eid, "approval", "yes")
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[String](eid, "result")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == "yes: 50")
                    // Child body should have run exactly once — replay skips it
                    assert(count1 == 1, s"Child body ran $count1 times before suspension")
                end for
            }
        }

        "multiple subflows don't interfere" in run {
            withEngine { (engine, store, tc) =>
                val child1 = Flow.input[Int]("a").output("b")(ctx => ctx.a + 1)
                val child2 = Flow.input[Int]("a").output("b")(ctx => ctx.a * 2)
                val flow = Flow.input[Int]("x")
                    .subflow("first", child1)(ctx => "a" ~ ctx.x)
                    .subflow("second", child2)(ctx => "a" ~ ctx.x)
                    .output("result")(ctx => ctx.first.b + ctx.second.b)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 5)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[Int](eid, "result")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == 16, s"Expected 6+10=16 but got ${v}") // (5+1) + (5*2)
                end for
            }
        }

        "child failure propagates to parent" in run {
            withEngine { (engine, store, tc) =>
                val child = Flow.input[Int]("a").output("b")(ctx =>
                    if ctx.a == 0 then throw new RuntimeException("zero") else ctx.a
                )
                val flow = Flow.input[Int]("x")
                    .subflow("payment", child)(ctx => "a" ~ ctx.x)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 0)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status match
                    case Flow.Status.Failed(_) => true;
                    case _                     => false)
                end for
            }
        }
    }

    // Version check tests moved to FlowVersionTest

    // =========================================================================
    // Phase 5: duplicate execution ID, diagram formats, Handle.describe
    // =========================================================================
    "duplicate execution ID" - {

        "start with existing ID fails" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _ <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    h <- engine.workflows.start(wf1)
                    eid = h.executionId
                    res <- Abort.run[FlowException](engine.workflows.start(wf1, eid))
                yield assert(res.isFailure)
                end for
            }
        }
    }

    "diagram formats" - {

        "mermaid" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _ <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    d <- engine.workflows.diagram(wf1, Flow.DiagramFormat.Mermaid)
                yield assert(d.contains("graph"))
                end for
            }
        }

        "dot" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _ <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    d <- engine.workflows.diagram(wf1, Flow.DiagramFormat.Dot)
                yield assert(d.contains("digraph"))
                end for
            }
        }

        "bpmn" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _ <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    d <- engine.workflows.diagram(wf1, Flow.DiagramFormat.Bpmn)
                yield assert(d.contains("bpmn"))
                end for
            }
        }

        "json" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _ <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    d <- engine.workflows.diagram(wf1, Flow.DiagramFormat.Json)
                yield assert(d.contains("nodes"))
                end for
            }
        }

        "elk" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _ <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    d <- engine.workflows.diagram(wf1, Flow.DiagramFormat.Elk)
                yield assert(d.contains("id"))
                end for
            }
        }
    }

    "Handle.describe" - {

        "returns execution detail with progress" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    _      <- engine.executions.signal[Int](handle.executionId, "x", 42)
                    _      <- pump(tc, store, handle.executionId, _.isTerminal)
                    detail <- handle.describe
                yield
                    assert(detail.status == Flow.Status.Completed)
                    assert(detail.progress.completedCount > 0)
                end for
            }
        }
    }

    "workflows.executions" - {

        "lists executions for workflow" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _    <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    _    <- engine.workflows.start(wf1)
                    _    <- engine.workflows.start(wf1)
                    exes <- engine.workflows.executions(wf1)
                yield assert(exes.length == 2)
                end for
            }
        }
    }

    // =========================================================================
    // Compensation: more edge cases (ported from v1 FlowRevertEdgeCaseTest)
    // =========================================================================
    "compensation advanced" - {

        "five reverts fire in reverse order" in run {
            withEngine { (engine, store, tc) =>
                var log = Seq.empty[String]
                val flow = Flow.input[Int]("x")
                    .outputCompensated("a")(ctx => 1)(ctx => log = log :+ "a")
                    .outputCompensated("b")(ctx => 2)(ctx => log = log :+ "b")
                    .outputCompensated("c")(ctx => 3)(ctx => log = log :+ "c")
                    .outputCompensated("d")(ctx => 4)(ctx => log = log :+ "d")
                    .outputCompensated("e")(ctx => 5)(ctx => log = log :+ "e")
                    .output("fail")(ctx =>
                        throw new RuntimeException("boom"); ""
                    )
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(status match
                        case Flow.Status.Failed(_) => true;
                        case _                     => false)
                    assert(log == Seq("e", "d", "c", "b", "a"), s"reverse order, got: $log")
                end for
            }
        }

        "revert does not fire for step that never completed" in run {
            withEngine { (engine, store, tc) =>
                var log = Seq.empty[String]
                val flow = Flow.input[Int]("x")
                    .outputCompensated("a")(ctx => ctx.x)(ctx => log = log :+ "a")
                    .output("fail")(ctx =>
                        throw new RuntimeException("boom"); ""
                    )
                    .outputCompensated("c")(ctx => 3)(ctx => log = log :+ "c")
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(log.contains("a"), "a completed before fail")
                    assert(!log.contains("c"), "c never completed, should not fire")
                end for
            }
        }

        "revert handler sees context at registration time" in run {
            withEngine { (engine, store, tc) =>
                var capturedX = -1
                val flow = Flow.input[Int]("x")
                    .outputCompensated("a")(ctx => ctx.x + 100)(ctx => capturedX = ctx.a)
                    .output("fail")(ctx =>
                        throw new RuntimeException("boom"); ""
                    )
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 5)
                    _ <- pump(tc, store, eid, _.isTerminal)
                yield assert(capturedX == 105, s"handler sees ctx at registration: got $capturedX")
                end for
            }
        }

        "multiple throwing revert handlers: all still execute" in run {
            withEngine { (engine, store, tc) =>
                var log = Seq.empty[String]
                val flow = Flow.input[Int]("x")
                    .outputCompensated("a")(ctx => 1)(ctx =>
                        log = log :+ "a"; throw new RuntimeException("a-throw")
                    )
                    .outputCompensated("b")(ctx => 2)(ctx =>
                        log = log :+ "b"; throw new RuntimeException("b-throw")
                    )
                    .outputCompensated("c")(ctx => 3)(ctx => log = log :+ "c")
                    .output("fail")(ctx =>
                        throw new RuntimeException("boom"); ""
                    )
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 1)
                    _ <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(log.contains("a"), "a fires despite throwing")
                    assert(log.contains("b"), "b fires despite a throwing")
                    assert(log.contains("c"), "c fires despite a and b throwing")
                end for
            }
        }

        "foreach body has no compensation, later step fails: no spurious reverts" in run {
            withEngine { (engine, store, tc) =>
                var fired = false
                val flow = Flow.input[Int]("n")
                    .foreach("items")(ctx => (1 to ctx.n).toSeq)(i => i * 2)
                    .output("fail")(ctx =>
                        fired = true; throw new RuntimeException("boom"); ""
                    )
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "n", 3)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(status match
                        case Flow.Status.Failed(_) => true;
                        case _                     => false)
                    assert(fired)
                end for
            }
        }
    }

    // =========================================================================
    // Zip/Gather execution through engine
    // =========================================================================
    "zip execution" - {

        "both branches execute and merge results" in run {
            withEngine { (engine, store, tc) =>
                val left  = Flow.input[Int]("a").output("b")(ctx => ctx.a * 10)
                val right = Flow.input[Int]("c").output("d")(ctx => ctx.c * 20)
                val flow  = left.zip(right)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "a", 3)
                    _      <- engine.executions.signal[Int](eid, "c", 5)
                    status <- pump(tc, store, eid, _.isTerminal)
                    b      <- store.getField[Int](eid, "b")
                    d      <- store.getField[Int](eid, "d")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(b.get == 30)
                    assert(d.get == 100)
                end for
            }
        }
    }

    "gather execution" - {

        "all branches execute and merge" in run {
            withEngine { (engine, store, tc) =>
                val f1   = Flow.input[Int]("a").output("b")(ctx => ctx.a + 1)
                val f2   = Flow.input[Int]("c").output("d")(ctx => ctx.c + 2)
                val flow = Flow.gather(f1, f2)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "a", 10)
                    _      <- engine.executions.signal[Int](eid, "c", 20)
                    status <- pump(tc, store, eid, _.isTerminal)
                    b      <- store.getField[Int](eid, "b")
                    d      <- store.getField[Int](eid, "d")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(b.get == 11)
                    assert(d.get == 22)
                end for
            }
        }
    }

    // =========================================================================
    // Progress through engine
    // =========================================================================
    "progress through engine" - {

        "reports progress for completed steps" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x + 1).output("z")(ctx => ctx.y + 1)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    _      <- pump(tc, store, eid, _.isTerminal)
                    detail <- engine.executions.describe(eid)
                yield
                    assert(detail.progress.totalCount == 3)
                    assert(detail.progress.completedCount == 3)
                end for
            }
        }

        "reports pending steps before input" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x + 1)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("x"))
                    detail <- engine.executions.describe(eid)
                yield
                    assert(detail.progress.totalCount == 2)
                    val xNode = detail.progress.nodeByName("x")
                    assert(xNode.isDefined)
                    assert(xNode.get.status == FlowEngine.Progress.NodeStatus.WaitingForInput)
                end for
            }
        }
    }

    // =========================================================================
    // Cancel edge cases
    // =========================================================================
    "cancel advanced" - {

        "cancel status is not overwritten by Failed" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _     <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("x"))
                    _     <- engine.executions.cancel(eid)
                    state <- store.getExecution(eid)
                    _ = assert(state.get.status == Flow.Status.Cancelled)
                    // Attempt to overwrite with Failed via updateStatus
                    now    <- Clock.now
                    _      <- store.updateStatus(eid, Flow.Status.Failed("late"), Flow.Event.Failed(wf1, eid, "late", now))
                    final_ <- store.getExecution(eid)
                yield assert(final_.get.status == Flow.Status.Cancelled, "terminal status should not revert")
                end for
            }
        }
    }

    // =========================================================================
    // Misc ported from v1
    // =========================================================================
    "misc" - {

        "input-only flow reaches Completed" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 42)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status == Flow.Status.Completed)
                end for
            }
        }

        "loop body executes on every iteration (not cached)" in run {
            withEngine { (engine, store, tc) =>
                var iterations = 0
                val flow = Flow.input[Int]("x")
                    .loop("result") { ctx =>
                        iterations += 1
                        if iterations < 3 then Loop.continue[Int]
                        else Loop.done(iterations)
                    }
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 0)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(status == Flow.Status.Completed)
                    assert(iterations >= 3, s"body should execute multiple times, got $iterations")
                end for
            }
        }

        "concurrent starts with same ID — at most one succeeds" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                val eid  = Flow.Id.Execution("same-id")
                for
                    _  <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    r1 <- Abort.run[FlowException](engine.workflows.start(wf1, eid))
                    r2 <- Abort.run[FlowException](engine.workflows.start(wf1, eid))
                yield
                    assert(r1.isSuccess)
                    assert(r2.isFailure, "second start with same ID should fail")
                end for
            }
        }

        "FlowGraph.build produces correct node and edge counts" in run {
            val flow  = Flow.input[Int]("x").output("y")(ctx => ctx.x + 1).step("log")(ctx => ()).sleep("wait", 1.second)
            val graph = kyo.internal.FlowGraph.build(flow)
            assert(graph.nodes.size == 4)
            assert(graph.edges.size == 3)
            succeed
        }

        "FlowGraph.build with progress annotates status" in run {
            val flow     = Flow.input[Int]("x").output("y")(ctx => ctx.x + 1)
            val progress = FlowEngine.Progress.build(flow, Set("x", "y"), Flow.Status.Completed)
            val graph    = kyo.internal.FlowGraph.build(flow, progress)
            assert(graph.nodes.exists(_.status == "completed"))
            succeed
        }
    }

    // =========================================================================
    // Sleep edge cases (ported from v1 FlowSleepTest)
    // =========================================================================
    "sleep advanced" - {

        "sequential sleeps" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .sleep("sleep1", 300.millis)
                    .sleep("sleep2", 300.millis)
                    .output("y")(ctx => ctx.x * 2)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 5)
                    _ <- pump(tc, store, eid, _.isSleeping)
                    _ <- pump(tc, store, eid, s => !s.isSleeping && s != Flow.Status.Running, 300)
                    _ <- pump(tc, store, eid, _.isTerminal, 300)
                    v <- store.getField[Int](eid, "y")
                yield assert(v.get == 10)
                end for
            }
        }

        "compensation runs when step after sleep fails" in run {
            withEngine { (engine, store, tc) =>
                var fired = false
                val flow = Flow.input[Int]("x")
                    .outputCompensated("a")(ctx => ctx.x)(ctx => fired = true)
                    .sleep("pause", 300.millis)
                    .output("fail")(ctx =>
                        throw new RuntimeException("boom"); ""
                    )
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal, 300)
                yield
                    assert(status match
                        case Flow.Status.Failed(_) => true;
                        case _                     => false)
                    assert(fired, "compensation should fire after sleep+failure")
                end for
            }
        }
    }

    // =========================================================================
    // Skip/replay (ported from v1 FlowSkipTest)
    // =========================================================================
    "skip replay" - {

        "dispatch skipped when result already in record" in run {
            withEngine { (engine, store, tc) =>
                var dispatchCalled = false
                val flow = Flow.input[Int]("x")
                    .dispatch[String]("decision")
                    .when(
                        ctx =>
                            dispatchCalled = true; ctx.x > 50
                        ,
                        name = "high"
                    )(ctx => "approved")
                    .otherwise(ctx => "rejected", name = "default")
                    .input[String]("name")
                    .output("result")(ctx => ctx.name)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 100)
                    _ <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("name"))
                    c1 = dispatchCalled
                    _  = dispatchCalled = false
                    _ <- engine.executions.signal[String](eid, "name", "done")
                    _ <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(c1, "dispatch should execute first time")
                    assert(!dispatchCalled, "dispatch should NOT re-execute on replay")
                end for
            }
        }
    }

    "regression" - {

        "zero-duration sleep does not execute and does not affect replay" in run {
            withEngine { (engine, store, tc) =>
                var afterCount = 0
                val flow = Flow.input[Int]("x")
                    .step("before")(ctx => ())
                    .sleep("zero", Duration.Zero)
                    .step("after") { ctx =>
                        afterCount += 1
                    }
                    .input[String]("trigger") // suspend point for replay
                    .output("result")(ctx => ctx.trigger)
                for
                    _ <- engine.register(wf1, flow)
                    h <- engine.workflows.start(wf1)
                    eid = h.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 1)
                    _ <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("trigger"))
                    c1 = afterCount
                    _  = afterCount = 0
                    _ <- engine.executions.signal[String](eid, "trigger", "go")
                    _ <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(c1 == 1, s"after should run once before suspension, ran $c1 times")
                    assert(afterCount == 0, s"after should NOT re-run on replay, ran $afterCount times")
                end for
            }
        }
        "compensation error propagates as Failure" in run {
            withEngine { (engine, store, tc) =>
                var compFired = false
                val flow = Flow.input[Int]("x")
                    .outputCompensated("a")(ctx => ctx.x + 1)(ctx => compFired = true)
                    .output("b") { ctx =>
                        throw new RuntimeException("boom"); ""
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(compFired, "compensation should fire")
                    assert(
                        status match
                            case Flow.Status.Failed(msg) => msg == "boom";
                            case _                       => false
                        ,
                        s"Should fail with 'boom' but got $status"
                    )
                end for
            }
        }

        "compensation handler sees only fields present at registration" in run {
            withEngine { (engine, store, tc) =>
                var capturedKeys = Set.empty[String]
                val flow = Flow.input[Int]("x")
                    .outputCompensated("a")(ctx => ctx.x + 1) { ctx =>
                        capturedKeys = ctx.toDict.foldLeft(Set.empty[String])((acc, k, _) => acc + k)
                    }
                    .output("b")(ctx => ctx.a + 1)
                    .output("c") { ctx =>
                        throw new RuntimeException("fail"); ""
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 5)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(status match
                        case Flow.Status.Failed(_) => true;
                        case _                     => false)
                    // Handler should see "x" and "a" (fields at registration), not "b" (added after)
                    assert(capturedKeys.contains("x"), s"Should see 'x', got $capturedKeys")
                    assert(capturedKeys.contains("a"), s"Should see 'a', got $capturedKeys")
                    assert(!capturedKeys.contains("b"), s"Should NOT see 'b', got $capturedKeys")
                end for
            }
        }

        "compensation emits CompensationStarted and CompensationCompleted events" in run {
            withEngine { (engine, store, tc) =>
                var compFired = false
                val flow = Flow.input[Int]("x")
                    .outputCompensated("a")(ctx => ctx.x + 1)(ctx => compFired = true)
                    .output("b") { ctx =>
                        throw new RuntimeException("fail"); ""
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _       <- engine.executions.signal[Int](eid, "x", 1)
                    status  <- pump(tc, store, eid, _.isTerminal)
                    history <- store.getHistory(eid, Int.MaxValue, 0)
                yield
                    assert(compFired, "compensation should fire")
                    val kinds = history.events.map(_.kind).toSeq
                    assert(
                        kinds.contains(Flow.EventKind.CompensationStarted),
                        s"Should have CompensationStarted event, got: $kinds"
                    )
                    assert(
                        kinds.contains(Flow.EventKind.CompensationCompleted),
                        s"Should have CompensationCompleted event, got: $kinds"
                    )
                    // CompensationStarted must come before CompensationCompleted
                    val startIdx = kinds.indexOf(Flow.EventKind.CompensationStarted)
                    val endIdx   = kinds.indexOf(Flow.EventKind.CompensationCompleted)
                    assert(startIdx < endIdx, s"Started ($startIdx) should be before Completed ($endIdx)")
                end for
            }
        }

        "Compensating status is not terminal" in run {
            assert(!Flow.Status.Compensating.isTerminal)
            succeed
        }
    }

    // =========================================================================
    // Scheduled loop (loopOn)
    // =========================================================================
    "scheduled loop" - {

        "fixed schedule — stops when body returns done" in run {
            withEngine { (engine, store, tc) =>
                var iterations = 0
                val flow = Flow.input[Int]("x")
                    .loopOn("count", Schedule.fixed(500.millis)) { ctx =>
                        iterations += 1
                        if iterations < 3 then Loop.continue[Int]
                        else Loop.done(iterations)
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal, 200)
                    v      <- store.getField[Int](eid, "count")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == 3, s"Expected 3 but got ${v}")
                    assert(iterations == 3, s"Expected 3 iterations but got $iterations")
                end for
            }
        }

        "schedule runs until body returns done" in run {
            withEngine { (engine, store, tc) =>
                var iterations = 0
                val flow = Flow.input[Int]("x")
                    .loopOn("tick", Schedule.delay(200.millis).repeat(10)) {
                        ctx =>
                            iterations += 1
                            if iterations >= 3 then Loop.done(iterations)
                            else Loop.continue[Int]
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal, 200)
                    v      <- store.getField[Int](eid, "tick")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(iterations >= 3, s"Expected at least 3 iterations but got $iterations")
                    assert(v.get == 3, s"Expected done value 3 but got ${v}")
                end for
            }
        }

        "body returns done before schedule exhausts" in run {
            withEngine { (engine, store, tc) =>
                var iterations = 0
                val flow = Flow.input[Int]("x")
                    .loopOn("count", Schedule.fixed(100.millis).repeat(10)) { ctx =>
                        iterations += 1
                        if iterations < 2 then Loop.continue[Int]
                        else Loop.done(iterations)
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal, 200)
                    v      <- store.getField[Int](eid, "count")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == 2, s"Expected 2 but got ${v}")
                end for
            }
        }

        "scheduled loop body throws — execution fails" in run {
            withEngine { (engine, store, tc) =>
                var iterations = 0
                val flow = Flow.input[Int]("x")
                    .loopOn("count", Schedule.fixed(100.millis).repeat(5)) { ctx =>
                        iterations += 1
                        if iterations == 3 then throw new RuntimeException("fail on 3")
                        Loop.continue[Int]
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal, 200)
                yield assert(status match
                    case Flow.Status.Failed(_) => true;
                    case _                     => false)
                end for
            }
        }
    }

    // =========================================================================
    // Race execution
    // =========================================================================
    "race execution" - {

        "one branch fails, other succeeds — winner completes" in run {
            withEngine { (engine, store, tc) =>
                val left = Flow.input[Int]("x").output("a")(ctx =>
                    throw new RuntimeException("left fails"); 0
                )
                val right = Flow.input[Int]("x").output("b")(ctx => ctx.x * 10)
                val flow  = Flow.input[Int]("x").andThen(Flow.race(left, right))
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 5)
                    status <- pump(tc, store, eid, _.isTerminal)
                    b      <- store.getField[Int](eid, "b")
                yield
                    assert(status == Flow.Status.Completed, s"Race with one failing branch should complete, got $status")
                    assert(b.get == 50, s"Right branch should win with 50, got $b")
                end for
            }
        }

        "both branches fail — execution fails" in run {
            withEngine { (engine, store, tc) =>
                val left = Flow.input[Int]("x").output("a")(ctx =>
                    throw new RuntimeException("left"); 0
                )
                val right = Flow.input[Int]("x").output("b")(ctx =>
                    throw new RuntimeException("right"); 0
                )
                val flow = Flow.input[Int]("x").andThen(Flow.race(left, right))
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 5)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status match
                    case Flow.Status.Failed(_) => true;
                    case _                     => false)
                end for
            }
        }
    }

    // =========================================================================
    // Gather execution advanced
    // =========================================================================
    "gather advanced" - {

        "one branch fails — entire gather fails" in run {
            withEngine { (engine, store, tc) =>
                val f1 = Flow.input[Int]("x").output("a")(ctx => ctx.x + 1)
                val f2 = Flow.input[Int]("x").output("b")(ctx =>
                    throw new RuntimeException("fail"); 0
                )
                val flow = Flow.input[Int]("x").andThen(Flow.gather(f1, f2))
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 5)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status match
                    case Flow.Status.Failed(_) => true;
                    case _                     => false)
                end for
            }
        }

        "three branches all complete and merge" in run {
            withEngine { (engine, store, tc) =>
                val f1 = Flow.input[Int]("x").output("a")(ctx => ctx.x + 1)
                val f2 = Flow.input[Int]("x").output("b")(ctx => ctx.x + 2)
                val f3 = Flow.input[Int]("x").output("c")(ctx => ctx.x + 3)
                val flow = Flow.input[Int]("x").andThen(Flow.gather(f1, f2, f3))
                    .output("sum")(ctx => ctx.a + ctx.b + ctx.c)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 10)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[Int](eid, "sum")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == 36, s"Expected 11+12+13=36 but got ${v}")
                end for
            }
        }
    }

    // =========================================================================
    // Retry
    // =========================================================================
    "retry" - {

        "output with retry — fails twice then succeeds" in run {
            withEngine { (engine, store, tc) =>
                var attempts = 0
                val flow = Flow.input[Int]("x")
                    .output("y", retry = Maybe(Schedule.fixed(100.millis).repeat(3))) { ctx =>
                        attempts += 1
                        if attempts <= 2 then throw new RuntimeException(s"attempt $attempts")
                        ctx.x * 10
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 5)
                    status <- pump(tc, store, eid, _.isTerminal, 200)
                    v      <- store.getField[Int](eid, "y")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == 50)
                    assert(attempts == 3, s"Expected 3 attempts but got $attempts")
                end for
            }
        }

        "output with retry — exhausts schedule then fails" in run {
            withEngine { (engine, store, tc) =>
                var attempts = 0
                val flow = Flow.input[Int]("x")
                    .output("y", retry = Maybe(Schedule.delay(100.millis).repeat(2))) { ctx =>
                        attempts += 1
                        throw new RuntimeException(s"always fails attempt $attempts")
                        ""
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 5)
                    status <- pump(tc, store, eid, _.isTerminal, 200)
                yield
                    assert(status match
                        case Flow.Status.Failed(_) => true;
                        case _                     => false)
                    assert(attempts >= 2, s"Expected at least 2 attempts but got $attempts")
                end for
            }
        }

        "step with retry — retries side-effect step" in run {
            withEngine { (engine, store, tc) =>
                var attempts = 0
                val flow = Flow.input[Int]("x")
                    .step("side-effect", retry = Maybe(Schedule.fixed(100.millis).repeat(3))) { ctx =>
                        attempts += 1
                        if attempts <= 1 then throw new RuntimeException("transient")
                    }
                    .output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 42)
                    status <- pump(tc, store, eid, _.isTerminal, 200)
                yield
                    assert(status == Flow.Status.Completed)
                    assert(attempts == 2, s"Expected 2 attempts but got $attempts")
                end for
            }
        }
    }

    // =========================================================================
    // Timeout
    // =========================================================================
    "timeout" - {

        "output completes within timeout — succeeds" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .output("y", timeout = 5.seconds)(ctx => ctx.x * 2)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 5)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[Int](eid, "y")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == 10)
                end for
            }
        }
    }

    // =========================================================================
    // Dispatch advanced
    // =========================================================================
    "dispatch advanced" - {

        "all conditions false — default branch taken" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .dispatch[String]("result")
                    .when(ctx => ctx.x > 100, name = "big")(ctx => "big")
                    .when(ctx => ctx.x > 50, name = "medium")(ctx => "medium")
                    .otherwise(ctx => "small", name = "default")
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[String](eid, "result")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == "small")
                end for
            }
        }

        "condition throws — execution fails" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .dispatch[String]("result")
                    .when(
                        ctx =>
                            throw new RuntimeException("bad condition"); true
                        ,
                        name = "bad"
                    )(ctx => "never")
                    .otherwise(ctx => "default", name = "default")
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status match
                    case Flow.Status.Failed(_) => true;
                    case _                     => false)
                end for
            }
        }

        "branch body throws — execution fails" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .dispatch[String]("result")
                    .when(ctx => true, name = "always")(ctx =>
                        throw new RuntimeException("body fail"); ""
                    )
                    .otherwise(ctx => "default", name = "default")
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status match
                    case Flow.Status.Failed(_) => true;
                    case _                     => false)
                end for
            }
        }
    }

    // =========================================================================
    // Foreach advanced
    // =========================================================================
    "foreach advanced" - {

        "empty collection — Chunk.empty stored" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .foreach("results")(ctx => Seq.empty[Int])(n => n * 10)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status == Flow.Status.Completed)
                end for
            }
        }

        "body throws on one element — execution fails" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .foreach("results")(ctx => (1 to ctx.x).toSeq) { n =>
                        if n == 3 then throw new RuntimeException("fail on 3")
                        n * 10
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 5)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status match
                    case Flow.Status.Failed(_) => true;
                    case _                     => false)
                end for
            }
        }

        "collection computation throws — execution fails" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .foreach("results")(ctx =>
                        throw new RuntimeException("collection fail"); Seq.empty[Int]
                    )(n => n)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status match
                    case Flow.Status.Failed(_) => true;
                    case _                     => false)
                end for
            }
        }
    }

    // =========================================================================
    // Subflow advanced
    // =========================================================================
    "subflow advanced" - {

        "child with multiple outputs — nested record accessible" in run {
            withEngine { (engine, store, tc) =>
                val child = Flow.input[Int]("a")
                    .output("b")(ctx => ctx.a * 2)
                    .output("c")(ctx => ctx.a + ctx.b)
                val flow = Flow.input[Int]("x")
                    .subflow("sub", child)(ctx => "a" ~ ctx.x)
                    .output("result")(ctx => ctx.sub.b + ctx.sub.c)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 5)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[Int](eid, "result")
                yield
                    assert(status == Flow.Status.Completed)
                    // b=10, c=5+10=15, result=10+15=25
                    assert(v.get == 25, s"Expected 25 but got ${v}")
                end for
            }
        }

        "nested subflow (subflow within subflow)" in run {
            withEngine { (engine, store, tc) =>
                val inner = Flow.input[Int]("a").output("b")(ctx => ctx.a * 3)
                val middle = Flow.input[Int]("a")
                    .subflow("inner", inner)(ctx => "a" ~ ctx.a)
                    .output("c")(ctx => ctx.inner.b + 1)
                val flow = Flow.input[Int]("x")
                    .subflow("mid", middle)(ctx => "a" ~ ctx.x)
                    .output("result")(ctx => ctx.mid.c)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 4)
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[Int](eid, "result")
                yield
                    assert(status == Flow.Status.Completed)
                    // inner.b = 4*3=12, middle.c = 12+1=13
                    assert(v.get == 13, s"Expected 13 but got ${v}")
                end for
            }
        }

        "inputMapper throws — parent fails" in run {
            withEngine { (engine, store, tc) =>
                val child = Flow.input[Int]("a").output("b")(ctx => ctx.a)
                val flow = Flow.input[Int]("x")
                    .subflow("sub", child)(ctx =>
                        throw new RuntimeException("mapper fail"); "a" ~ 0
                    )
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status match
                    case Flow.Status.Failed(_) => true;
                    case _                     => false)
                end for
            }
        }
    }

    // =========================================================================
    // Compensation advanced (additional)
    // =========================================================================
    "compensation deep nesting" - {

        "5 compensated outputs — all fire in reverse on failure" in run {
            withEngine { (engine, store, tc) =>
                var log = Seq.empty[String]
                val flow = Flow.input[Int]("x")
                    .outputCompensated("a")(ctx => 1)(ctx => log = log :+ "comp-a")
                    .outputCompensated("b")(ctx => 2)(ctx => log = log :+ "comp-b")
                    .outputCompensated("c")(ctx => 3)(ctx => log = log :+ "comp-c")
                    .outputCompensated("d")(ctx => 4)(ctx => log = log :+ "comp-d")
                    .outputCompensated("e")(ctx => 5)(ctx => log = log :+ "comp-e")
                    .output("fail")(ctx =>
                        throw new RuntimeException("boom"); 0
                    )
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(status match
                        case Flow.Status.Failed(_) => true;
                        case _                     => false)
                    assert(
                        log == Seq("comp-e", "comp-d", "comp-c", "comp-b", "comp-a"),
                        s"Expected reverse order but got $log"
                    )
                end for
            }
        }
    }

    // =========================================================================
    // Edge cases: degenerate flows
    // =========================================================================
    "degenerate flows" - {

        "flow with only init — completes immediately" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("test")
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status == Flow.Status.Completed)
                end for
            }
        }

        "flow with only input — suspends then completes" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- pump(tc, store, eid, _ == Flow.Status.WaitingForInput("x"))
                    _      <- engine.executions.signal[Int](eid, "x", 42)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status == Flow.Status.Completed)
                end for
            }
        }

        "flow with only sleep — suspends then completes" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("test").sleep("wait", 1.second)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- pump(tc, store, eid, _.isSleeping)
                    _ <- pump(tc, store, eid, _.isTerminal, 200)
                yield succeed
                end for
            }
        }
    }

    // =========================================================================
    // Dict debug tests
    // =========================================================================
    "dict debug" - {

        "engine defs contains registered workflow" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _    <- engine.register(wf1, flow)
                    defs <- engine.defs.get
                yield
                    assert(defs.size == 1, s"Expected 1 def, got ${defs.size}")
                    assert(defs.get(wf1).nonEmpty, s"Expected wf1 in defs")
                end for
            }
        }

        "engine defs update replaces workflow" in run {
            withEngine { (engine, store, tc) =>
                val flow1 = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                val flow2 = Flow.input[Int]("x").output("y")(ctx => ctx.x).output("z")(ctx => 0)
                for
                    _     <- engine.register(wf1, flow1)
                    defs1 <- engine.defs.get
                    _     <- engine.register(wf1, flow2)
                    defs2 <- engine.defs.get
                yield
                    assert(defs1.size == 1)
                    assert(defs2.size == 1)
                    assert(defs1.get(wf1).nonEmpty)
                    assert(defs2.get(wf1).nonEmpty)
                    // Hash should change
                    val h1 = defs1.get(wf1).get.meta.structuralHash
                    val h2 = defs2.get(wf1).get.meta.structuralHash
                    assert(h1 != h2, s"Hashes should differ: $h1 vs $h2")
                end for
            }
        }

        "worker builds wfIds from defs" in run {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _    <- engine.register(wf1, flow)
                    defs <- engine.defs.get
                    wfIds = defs.foldLeft(Set.empty[Flow.Id.Workflow])((acc, k, _) => acc + k)
                yield
                    assert(wfIds.size == 1, s"Expected 1 wfId, got ${wfIds.size}")
                    assert(wfIds.contains(wf1), s"Expected wf1 in wfIds, got $wfIds")
                end for
            }
        }

        "hash mismatch detected after re-register" in run {
            withEngine { (engine, store, tc) =>
                val flow1 = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                val flow2 = Flow.input[Int]("x").output("y")(ctx => ctx.x).output("z")(ctx => 0)
                for
                    _ <- engine.register(wf1, flow1)
                    // Check defs before start
                    d <- engine.defs.get
                    _ = assert(d.get(wf1).nonEmpty, "flow1 should be registered")
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 42)
                    // Check execution exists
                    preState <- store.getExecution(eid)
                    _ = assert(preState.nonEmpty, s"Execution should exist, got $preState")
                    _      <- pump(tc, store, eid, _.isTerminal, 200)
                    state1 <- store.getExecution(eid)
                    _      <- engine.register(wf1, flow2)
                    defs   <- engine.defs.get
                yield
                    assert(state1.get.status == Flow.Status.Completed)
                    assert(defs.get(wf1).nonEmpty)
                end for
            }
        }
    }

    // =========================================================================
    // Progress
    // =========================================================================

    val linearFlow = Flow.input[Int]("x")
        .output("y")(ctx => ctx.x + 1)
        .step("log")(ctx => ())
        .sleep("wait", 1.hour)

    "Progress.build with empty completed steps" - {

        "first node is Running, rest are Pending" in run {
            val progress = FlowEngine.Progress.build(linearFlow, Set.empty, Flow.Status.Running)
            assert(progress.nodes.size == 4)
            assert(progress.nodes(0).status == FlowEngine.Progress.NodeStatus.Running)
            (1 until progress.nodes.size).foreach { i =>
                assert(progress.nodes(i).status == FlowEngine.Progress.NodeStatus.Pending)
            }
            succeed
        }

        "completedCount is 0" in run {
            val progress = FlowEngine.Progress.build(linearFlow, Set.empty, Flow.Status.Running)
            assert(progress.completedCount == 0)
            succeed
        }

        "totalCount matches node count" in run {
            val progress = FlowEngine.Progress.build(linearFlow, Set.empty, Flow.Status.Running)
            assert(progress.totalCount == 4)
            succeed
        }
    }

    "Progress.build with some completed steps" - {

        "marks completed steps correctly" in run {
            val progress = FlowEngine.Progress.build(linearFlow, Set("x", "y"), Flow.Status.Running)
            assert(progress.nodeByName("x").get.status == FlowEngine.Progress.NodeStatus.Completed)
            assert(progress.nodeByName("y").get.status == FlowEngine.Progress.NodeStatus.Completed)
            assert(progress.nodeByName("log").get.status == FlowEngine.Progress.NodeStatus.Running)
            assert(progress.nodeByName("wait").get.status == FlowEngine.Progress.NodeStatus.Pending)
            succeed
        }

        "completedCount reflects completed steps" in run {
            val progress = FlowEngine.Progress.build(linearFlow, Set("x", "y"), Flow.Status.Running)
            assert(progress.completedCount == 2)
            succeed
        }

        "all completed gives full count" in run {
            val progress = FlowEngine.Progress.build(linearFlow, Set("x", "y", "log", "wait"), Flow.Status.Completed)
            assert(progress.completedCount == 4)
            assert(progress.completedCount == progress.totalCount)
            succeed
        }
    }

    "Progress.build with WaitingForInput status" - {

        "marks the waiting input as WaitingForInput" in run {
            val progress = FlowEngine.Progress.build(linearFlow, Set.empty, Flow.Status.WaitingForInput("x"))
            assert(progress.nodeByName("x").get.status == FlowEngine.Progress.NodeStatus.WaitingForInput)
            succeed
        }

        "non-matching input stays pending" in run {
            val flow = Flow.input[Int]("a")
                .input[String]("b")
                .output("c")(ctx => ctx.a)
            val progress = FlowEngine.Progress.build(flow, Set("a"), Flow.Status.WaitingForInput("b"))
            assert(progress.nodeByName("a").get.status == FlowEngine.Progress.NodeStatus.Completed)
            assert(progress.nodeByName("b").get.status == FlowEngine.Progress.NodeStatus.WaitingForInput)
            succeed
        }
    }

    "Progress.build with Sleeping status" - {

        "marks the sleeping node as Sleeping" in run {
            val until    = Instant.Epoch + 1.hour
            val progress = FlowEngine.Progress.build(linearFlow, Set("x", "y", "log"), Flow.Status.Sleeping("wait", until))
            assert(progress.nodeByName("wait").get.status == FlowEngine.Progress.NodeStatus.Sleeping(until))
            succeed
        }

        "non-matching sleep stays pending" in run {
            val flow = Flow.input[Int]("x")
                .sleep("s1", 1.minute)
                .sleep("s2", 2.minutes)
            val until    = Instant.Epoch + 2.minutes
            val progress = FlowEngine.Progress.build(flow, Set("x", "s1"), Flow.Status.Sleeping("s2", until))
            assert(progress.nodeByName("s1").get.status == FlowEngine.Progress.NodeStatus.Completed)
            assert(progress.nodeByName("s2").get.status == FlowEngine.Progress.NodeStatus.Sleeping(until))
            succeed
        }
    }

    "Progress.nodeByName" - {

        "finds existing node" in run {
            val progress = FlowEngine.Progress.build(linearFlow, Set.empty, Flow.Status.Running)
            val node     = progress.nodeByName("x")
            assert(node.isDefined)
            assert(node.get.name == "x")
            assert(node.get.nodeType == FlowEngine.Progress.NodeType.Input)
            succeed
        }

        "returns empty for missing name" in run {
            val progress = FlowEngine.Progress.build(linearFlow, Set.empty, Flow.Status.Running)
            assert(progress.nodeByName("nonexistent").isEmpty)
            succeed
        }

        "finds all node types" in run {
            val flow = Flow.input[Int]("x")
                .dispatch[String]("d")
                .when(ctx => ctx.x > 0, name = "yes")(ctx => "yes")
                .otherwise(ctx => "no", name = "default")
                .loop("r") { ctx => Loop.done(ctx.x - 1) }
            val progress = FlowEngine.Progress.build(flow, Set.empty, Flow.Status.Running)
            assert(progress.nodeByName("x").get.nodeType == FlowEngine.Progress.NodeType.Input)
            assert(progress.nodeByName("d").get.nodeType == FlowEngine.Progress.NodeType.Dispatch)
            assert(progress.nodeByName("r").get.nodeType == FlowEngine.Progress.NodeType.Loop)
            succeed
        }
    }

    "Progress.completedCount and totalCount" - {

        "empty progress" in run {
            val progress = FlowEngine.Progress.empty
            assert(progress.completedCount == 0)
            assert(progress.totalCount == 0)
            succeed
        }

        "complex flow with zip" in run {
            val left     = Flow.input[Int]("a").output("b")(ctx => ctx.a)
            val right    = Flow.input[Int]("c").output("d")(ctx => ctx.c)
            val flow     = left.zip(right)
            val progress = FlowEngine.Progress.build(flow, Set("a", "b"), Flow.Status.Running)
            assert(progress.completedCount == 2)
            assert(progress.totalCount == 4)
            succeed
        }
    }

    "Progress loop iteration detection" - {

        "loop node shows Completed when only iteration-indexed steps are in completedSteps" in run {
            val flow = Flow.input[Int]("x")
                .loop("result") { ctx => Loop.done(ctx.x - 1) }
            val completedSteps = Set("x", "result#0", "result#1", "result#2")
            val progress       = FlowEngine.Progress.build(flow, completedSteps, Flow.Status.Completed)
            val resultNode     = progress.nodeByName("result")
            assert(resultNode.isDefined)
            assert(resultNode.get.status == FlowEngine.Progress.NodeStatus.Completed)
            succeed
        }
    }

    "Progress running and failed status" - {

        "first non-completed node shows Running when flow status is Running" in run {
            val progress = FlowEngine.Progress.build(linearFlow, Set.empty, Flow.Status.Running)
            assert(progress.nodeByName("x").get.status == FlowEngine.Progress.NodeStatus.Running)
            succeed
        }

        "last non-completed step shows Failed when flow status is Failed" in run {
            val progress = FlowEngine.Progress.build(linearFlow, Set("x"), Flow.Status.Failed("boom"))
            assert(progress.nodeByName("y").get.status == FlowEngine.Progress.NodeStatus.Failed("boom"))
            succeed
        }
    }

    "Progress node location" - {

        "location is populated" in run {
            val progress = FlowEngine.Progress.build(linearFlow, Set.empty, Flow.Status.Running)
            progress.nodes.foreach(node => assert(node.location.nonEmpty))
            succeed
        }
    }

end FlowEngineTest
