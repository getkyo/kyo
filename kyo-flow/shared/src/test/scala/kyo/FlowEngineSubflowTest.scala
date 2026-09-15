package kyo

/** What a subflow makes durable: the child's inputs at its entry, and the child's own fields under its path.
  *
  * An aspect file of `FlowEngineTest`, sharing its helpers through [[FlowEngineSupport]], split off for the reason
  * [[FlowEngineForeachTest]] states: a test class carries the body of every leaf it registers in its constructor and the JVM caps how
  * large a class may be, so the engine suite sits at that cap again. The subflow leaves are the natural aspect to lift out, because
  * every one of them is about composition under a path: which instance a durable key belongs to, what an entry records before the
  * child runs, and what a re-entry does with what it finds.
  */
class FlowEngineSubflowTest extends FlowEngineSupport:

    // =========================================================================
    // Subflow execution
    // =========================================================================
    "subflow" - {

        "child output accessible downstream via nested record" in {
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

        "child fields do not leak into parent namespace" in {
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
                    // read for the contrast: the child's field is durable under `payment~b`, so the bare name is not a parent field
                    bField <- store.getField[Int](eid, "b")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == 6)
                    // the type system keeps the two apart ahead of the store: `ctx.b` does not compile, since `b` is not in the
                    // parent's Out
                    ()
                end for
            }
        }

        "subflow replays correctly after suspension" in {
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
                    _ <- pumpState(tc, store, eid, waitingFor("approval"))
                    // Child executed once
                    count1 = childBodyCount
                    _      <- engine.executions.signal[String](eid, "approval", "yes")
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[String](eid, "result")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == "yes: 50")
                    // Child body should have run exactly once, because replay skips it
                    assert(count1 == 1, s"Child body ran $count1 times before suspension")
                end for
            }
        }

        "multiple subflows don't interfere" in {
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

        "child failure propagates to parent" in {
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
                    case Flow.Status.Failed(_, _) => true;
                    case _                        => false)
                end for
            }
        }
    }

    // =========================================================================
    // Subflow advanced
    // =========================================================================
    "subflow advanced" - {

        "child with multiple outputs keeps its nested record accessible" in {
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

        "nested subflow (subflow within subflow)" in {
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

        "inputMapper throws, so the parent fails" in {
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
                    case Flow.Status.Failed(_, _) => true;
                    case _                        => false)
                end for
            }
        }

        /** A subflow's completed output is not re-executed when the parent resumes.
          *
          * The module's central promise, stated on [[FlowEngine]] itself: "completed steps are never re-executed, but an in-flight step
          * (started but not completed) may re-execute on a new engine". A subflow's inner nodes run through the same interpreter and
          * the same execution id, and the child's output records a field and a `StepCompleted` event exactly as a parent node does, so
          * a completed one is durably completed by every measure the store has.
          *
          * What makes it hold is what the child STARTS FROM. An `Output` node skips itself when its name is already in the record it
          * is handed, which is an in-memory lookup, so the child's starting record has to carry the child's own durable fields or
          * every completed output inside a subflow re-runs on every resume, once per resume, for the life of the execution. It does
          * carry them: `childRecord` reads everything the store holds under the instance's path and lets it win over anything the
          * entry supplied, so a resumed child skips exactly what a resumed parent skips. The parent's own nodes are never at risk,
          * because the parent's record is rebuilt from the store.
          *
          * The run count is the whole assertion: one before the suspension and one after, for a child output with a side effect.
          */
        "a subflow's completed output is not re-executed when the parent resumes" in {
            withEngine { (engine, store, tc) =>
                AtomicInt.init(0).map { runs =>
                    val child = Flow.input[Int]("amount")
                        .output("fee")(ctx => runs.incrementAndGet.map(_ => ctx.amount * 2))
                    val flow = Flow.input[Int]("x")
                        .subflow("payment", child)(ctx => "amount" ~ ctx.x)
                        .input[String]("gate")
                        .output("done")(ctx => ctx.payment.fee)
                    for
                        _      <- engine.register(wf1, flow)
                        handle <- engine.workflows.start(wf1)
                        eid = handle.executionId
                        _      <- engine.executions.signal[Int](eid, "x", 5)
                        _      <- pumpState(tc, store, eid, waitingFor("gate"))
                        before <- runs.get
                        _      <- engine.executions.signal[String](eid, "gate", "go")
                        status <- pump(tc, store, eid, _.isTerminal)
                        after  <- runs.get
                        done   <- store.getField[Int](eid, "done")
                    yield
                        assert(status == Flow.Status.Completed)
                        assert(done == Present(10), s"the child's output must survive the resume, got $done")
                        assert(before == 1, s"the child's output runs once before the suspension, got $before")
                        assert(
                            after == 1,
                            s"a completed subflow output must not re-execute when the parent resumes, ran $after times"
                        )
                    end for
                }
            }
        }

        /** Two instances of one subflow both run their steps, rather than one running for both.
          *
          * Durable node identity is the node's PATH, not its bare name: a field, a completion event, a wait row and a schema entry
          * are all keyed by `instance~name` (`kyo.internal.NodePath`). That is what embedding one child twice depends on, and the
          * bare name cannot carry it. A `Step` skips itself when its durable name is in the completed set, which is derived once per
          * attempt from history, so under bare names the two instances would both run on the FIRST attempt and then, after any
          * resume, the second would be skipped as work the first already did: a charge that must happen twice happening once, and
          * only after a crash, which is the shape that survives testing.
          *
          * The resume is therefore the load-bearing half of the fixture rather than decoration. Both instances charging on one clean
          * pass proves nothing; the assertion that matters is that the second still charges on the attempt AFTER the parent parked
          * and resumed, which is where a flat identity would collapse the two into one.
          */
        "two instances of one subflow both run their steps" in {
            withEngine { (engine, store, tc) =>
                AtomicInt.init(0).map { charges =>
                    val child = Flow.input[Int]("amount")
                        .step("charge")(_ => charges.incrementAndGet.unit)
                    val flow = Flow.input[Int]("x")
                        .subflow("first", child)(ctx => "amount" ~ ctx.x)
                        .input[String]("gate")
                        .subflow("second", child)(ctx => "amount" ~ ctx.x)
                        .output("done")(_ => "ok")
                    for
                        _      <- engine.register(wf1, flow)
                        handle <- engine.workflows.start(wf1)
                        eid = handle.executionId
                        _      <- engine.executions.signal[Int](eid, "x", 5)
                        _      <- pumpState(tc, store, eid, waitingFor("gate"))
                        first  <- charges.get
                        _      <- engine.executions.signal[String](eid, "gate", "go")
                        status <- pump(tc, store, eid, _.isTerminal)
                        total  <- charges.get
                    yield
                        assert(status == Flow.Status.Completed)
                        assert(first == 1, s"the first instance charges before the suspension, got $first")
                        assert(
                            total == 2,
                            s"each subflow instance must charge once, so two instances charge twice, got $total"
                        )
                    end for
                }
            }
        }

        /** Entering an instance writes each of the child's inputs under its own path, once, with its own event.
          *
          * The engine-side twin of the record the caller receives. The value is an ordinary field under `review~amount`, so replay
          * reads it back the way it reads any other and the assembly finds it under the prefix; the event is `InputSupplied`, which
          * is the third arrival shape and the only one a mapper produces.
          *
          * **No `InputDischarged` for the same path**, which is what keeps the arrival kinds apart. A discharge is a node's own
          * consumption of a value it waited for and its writer is guarded on the wait row, so a mapper-fed input, which never parks,
          * records none; writing one from the subflow's arm would put a consumption record before the node was even reached.
          */
        "a child input is recorded under its path at the subflow's entry" in {
            withEngine { (engine, store, tc) =>
                val child = Flow.input[Int]("amount").output("fee")(ctx => ctx.amount * 2)
                val flow = Flow.input[Int]("x")
                    .subflow("review", child)(ctx => "amount" ~ ctx.x)
                    .output("done")(ctx => ctx.review.fee)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _       <- engine.executions.signal[Int](eid, "x", 5)
                    status  <- pump(tc, store, eid, _.isTerminal)
                    amount  <- store.getField[Int](eid, "review~amount")
                    history <- store.getHistory(eid, Maybe.empty, 0)
                yield
                    assert(status == Flow.Status.Completed, s"the premise is that the execution completed, got $status")
                    assert(amount == Present(5), s"the mapper's value must be recorded under the child's path, got $amount")
                    val supplied = history.events.filter(_.kind == Flow.EventKind.InputSupplied).map(_.detail)
                    assert(
                        supplied == Chunk("review~amount"),
                        s"exactly one supply event, naming the child's path, got $supplied"
                    )
                    assert(
                        !history.events.exists(e =>
                            e.kind == Flow.EventKind.InputDischarged && e.detail == "review~amount"
                        ),
                        s"and no discharge for it, since the node never waited, got ${history.events.map(e => (e.kind, e.detail))}"
                    )
                end for
            }
        }

        /** A re-entered subflow runs its child against the inputs it recorded, and does not ask the mapper again.
          *
          * The resume rule, and the reason the mapper's determinism is not a correctness requirement. The child parks on its sleep, so
          * the parent's arm is entered a second time; the entry finds every declared input already recorded and re-enters the child
          * without running the mapper at all, exactly as a dispatch re-enters its recorded branch without re-asking its conditions.
          *
          * The mapper's source is moved between the two entries, so a second run would be VISIBLE: the child's remaining node would
          * compute its fee from 9 while the field says 5, which is the mixed record the recorded-input rule exists to prevent. One run
          * of the mapper and a fee of 10 is the whole assertion.
          */
        "a re-entered subflow runs its child against the inputs it recorded and does not run the mapper again" in {
            withEngine { (engine, store, tc) =>
                AtomicInt.init(5).map { source =>
                    AtomicInt.init(0).map { mapperRuns =>
                        val child = Flow.input[Int]("amount")
                            .sleep("hold", 500.millis)
                            .output("fee")(ctx => ctx.amount * 2)
                        val flow = Flow.init("resumed-subflow")
                            .subflow("review", child)(_ =>
                                mapperRuns.incrementAndGet.andThen(source.get).map(v => "amount" ~ v)
                            )
                            .output("done")(_ => "ok")
                        for
                            _      <- engine.register(wf1, flow)
                            handle <- engine.workflows.start(wf1)
                            eid = handle.executionId
                            _      <- pumpState(tc, store, eid, sleeping)
                            _      <- source.set(9)
                            status <- pump(tc, store, eid, _.isTerminal, 400)
                            amount <- store.getField[Int](eid, "review~amount")
                            fee    <- store.getField[Int](eid, "review~fee")
                            ran    <- mapperRuns.get
                        yield
                            assert(status == Flow.Status.Completed, s"the premise is that the execution completed, got $status")
                            assert(amount == Present(5), s"the recorded input stands across the resume, got $amount")
                            assert(fee == Present(10), s"and the child computes from it rather than from the moved source, got $fee")
                            assert(ran == 1, s"an entry that finds its inputs recorded does not run the mapper, ran $ran times")
                        end for
                    }
                }
            }
        }

        /** An entry that recorded only some of its inputs records the rest on re-entry, and keeps what it recorded.
          *
          * The one partial state this design admits, and it is benign because the writes precede the child's first node: nothing of
          * the child ran against the half-written seed, so no recorded child field was computed from a value the record does not
          * hold. The store fails the write for `b` once, on its own channel and marked retryable, so the attempt ends as
          * infrastructure with nothing else written and its claim left to lapse.
          *
          * The mapper answers from its own run count, so the two entries supply different values and which value each input kept is
          * observable: `a` is the FIRST run's, because a recorded input is never rewritten and never compared, and `b` is the
          * SECOND's, because it was still owed. The child then runs once, against that one record, and `sum` proves which pair it
          * saw. A short lease is what gets the second entry to happen at all, for the reason the retryable-store leaves state: an
          * attempt that could not reach the store leaves its claim to expire rather than releasing it.
          */
        "a subflow whose first entry recorded only some of its inputs records the rest on re-entry" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicInt.init(1).map { blips =>
                        AtomicInt.init(0).map { mapperRuns =>
                            val flaky = new FailingInputSupplyStore(store, "review~b", blips)
                            val child = Flow.input[Int]("a").input[Int]("b")
                                .output("sum")(ctx => ctx.a + ctx.b)
                            val flow = Flow.init("partial-seed")
                                .subflow("review", child)(_ =>
                                    mapperRuns.incrementAndGet.map(n => "a" ~ (n * 10) & "b" ~ (n * 100))
                                )
                                .output("done")(_ => "ok")
                            val config =
                                FlowEngine.Config(workerCount = 1, lease = 1.second, renewEvery = 500.millis, pollTimeout = 100.millis)
                            FlowEngine.init(flaky, config, flow).map { engine =>
                                for
                                    handle <- engine.workflows.start(Flow.Id.Workflow("partial-seed"))
                                    eid = handle.executionId
                                    status  <- pump(tc, store, eid, _.isTerminal, 400)
                                    a       <- store.getField[Int](eid, "review~a")
                                    b       <- store.getField[Int](eid, "review~b")
                                    sum     <- store.getField[Int](eid, "review~sum")
                                    ran     <- mapperRuns.get
                                    history <- store.getHistory(eid, Maybe.empty, 0)
                                yield
                                    assert(status == Flow.Status.Completed, s"a blip the next entry got past completes, got $status")
                                    assert(a == Present(10), s"the input the first entry recorded stands, got $a")
                                    assert(b == Present(200), s"and the one it owed is recorded by the second entry, got $b")
                                    assert(sum == Present(210), s"so the child runs once against that one record, got $sum")
                                    assert(ran == 2, s"the mapper runs once per entry that owed something, ran $ran times")
                                    val supplied = history.events.filter(_.kind == Flow.EventKind.InputSupplied).map(_.detail)
                                    assert(
                                        supplied == Chunk("review~a", "review~b"),
                                        s"one supply event per input, in the order the child declares them, got $supplied"
                                    )
                                end for
                            }
                        }
                    }
                }
            }
        }

        /** A settled subflow's inputs are recorded once, not once per attempt that walks past them.
          *
          * Write-once at the arm, and the reason it costs nothing to check: the entry consults the record before it writes, so a
          * re-entry whose inputs are all present writes nothing and the mapper never runs. Without that, a parent suspending after
          * the subflow would append a supply event per resume, which is a settled execution's history growing while it waits.
          */
        "a re-entered subflow does not record its inputs twice" in {
            withEngine { (engine, store, tc) =>
                val child = Flow.input[Int]("amount").output("fee")(ctx => ctx.amount * 2)
                val flow = Flow.input[Int]("x")
                    .subflow("payment", child)(ctx => "amount" ~ ctx.x)
                    .input[String]("gate")
                    .output("done")(ctx => ctx.payment.fee)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _       <- engine.executions.signal[Int](eid, "x", 5)
                    _       <- pumpState(tc, store, eid, waitingFor("gate"))
                    _       <- engine.executions.signal[String](eid, "gate", "go")
                    status  <- pump(tc, store, eid, _.isTerminal)
                    history <- store.getHistory(eid, Maybe.empty, 0)
                yield
                    assert(status == Flow.Status.Completed, s"the premise is that the execution completed, got $status")
                    val supplied = history.events.filter(_.kind == Flow.EventKind.InputSupplied).map(_.detail)
                    assert(
                        supplied == Chunk("payment~amount"),
                        s"the resume past a settled subflow records nothing new, got $supplied"
                    )
                end for
            }
        }

        /** A nested instance's inputs are recorded under the qualified path, at every depth.
          *
          * The entry writes under `childPath`, which is the path the instance occupies rather than its bare name, so the inner
          * instance's input lands at `outer~inner~ia` and the outer one's at `outer~oa`. That is the same key the schema carries for
          * it and the same prefix the assembly strips, which is what makes both levels readable from one execution's fields.
          */
        "a nested subflow records the inner instance's inputs under both paths" in {
            withEngine { (engine, store, tc) =>
                val inner = Flow.input[Int]("ia").output("ib")(ctx => ctx.ia + 1)
                val outer = Flow.input[Int]("oa")
                    .subflow("inner", inner)(ctx => "ia" ~ ctx.oa)
                    .output("ob")(ctx => ctx.inner.ib * 2)
                val flow = Flow.input[Int]("x").subflow("outer", outer)(ctx => "oa" ~ ctx.x)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 5)
                    status <- pump(tc, store, eid, _.isTerminal)
                    oa     <- store.getField[Int](eid, "outer~oa")
                    ia     <- store.getField[Int](eid, "outer~inner~ia")
                yield
                    assert(status == Flow.Status.Completed, s"the premise is that the execution completed, got $status")
                    assert(oa == Present(5), s"the outer instance's input sits under its own path, got $oa")
                    assert(ia == Present(5), s"and the inner instance's under the qualified one, got $ia")
                end for
            }
        }

        /** Two zipped instances record their children's inputs under their own paths, with their own values.
          *
          * One child flow embedded twice is two instances and therefore two sets of fields, which is what a subflow is for. The
          * mappers answer differently on purpose: a write that used the child's bare name, or one instance's path for both, would
          * leave one value where two belong, and asserting both values is what catches it.
          */
        "two zipped subflows record each child's inputs under its own path" in {
            withEngine { (engine, store, tc) =>
                val child = Flow.input[Int]("a").output("b")(ctx => ctx.a * 10)
                val left  = Flow.init("zipped").subflow("left", child)(_ => "a" ~ 1)
                val right = Flow.init("zipped").subflow("right", child)(_ => "a" ~ 2)
                val flow  = left.zip(right)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    status <- pump(tc, store, eid, _.isTerminal)
                    left   <- store.getField[Int](eid, "left~a")
                    right  <- store.getField[Int](eid, "right~a")
                    leftB  <- store.getField[Int](eid, "left~b")
                    rightB <- store.getField[Int](eid, "right~b")
                yield
                    assert(status == Flow.Status.Completed, s"the premise is that the execution completed, got $status")
                    assert(left == Present(1), s"the left instance's input is its own, got $left")
                    assert(right == Present(2), s"and so is the right one's, got $right")
                    assert(leftB == Present(10), s"each child computes from the input recorded under its path, got $leftB")
                    assert(rightB == Present(20), s"and the other from its own, got $rightB")
                end for
            }
        }

        /** A child that declares no inputs records nothing at entry, and its mapper never runs.
          *
          * The entry writes what the child declared and asks the mapper only for what it still owes, so a child owing nothing asks
          * nothing. The mapper's contract is to produce the child's inputs; with none declared there is nothing for it to produce and
          * its effects were never part of any record, which is why not running it is the rule rather than an omission.
          */
        "a subflow whose child declares no inputs never runs its mapper" in {
            withEngine { (engine, store, tc) =>
                AtomicInt.init(0).map { mapperRuns =>
                    val child = Flow.init("no-inputs").output("b")(_ => 7)
                    val flow = Flow.init("empty-mapper")
                        .subflow("review", child)(_ => mapperRuns.incrementAndGet.andThen(Record.empty))
                        .output("done")(_ => "ok")
                    for
                        _      <- engine.register(wf1, flow)
                        handle <- engine.workflows.start(wf1)
                        eid = handle.executionId
                        status  <- pump(tc, store, eid, _.isTerminal)
                        b       <- store.getField[Int](eid, "review~b")
                        ran     <- mapperRuns.get
                        history <- store.getHistory(eid, Maybe.empty, 0)
                    yield
                        assert(status == Flow.Status.Completed, s"the premise is that the execution completed, got $status")
                        assert(b == Present(7), s"the child's own work still lands, got $b")
                        assert(ran == 0, s"a child owing no input never asks its mapper, ran $ran times")
                        assert(
                            !history.events.exists(_.kind == Flow.EventKind.InputSupplied),
                            s"and nothing is recorded at its entry, got ${history.events.map(e => (e.kind, e.detail))}"
                        )
                    end for
                }
            }
        }

        /** Two race arms embedding one subflow name run their children against the first-recorded inputs.
          *
          * The corner the race exemption creates: two arms may share a written name, so both entries write `review~amount` and the
          * store answers the second already-recorded. That answer is not a failure, it is the exemption's own rule (the first
          * completion decides the field) reaching the moment of writing, so the arm that lost the write reads the recorded value back
          * and runs its child against it.
          *
          * Which arm wins is not asserted, because nothing decides it durably; what is asserted is that the two facts AGREE. Without
          * the read-back the loser's child would compute its fee from the value it mapped rather than the one recorded, and a fee of
          * 4 beside an amount of 1 is exactly the record the read-back exists to prevent. The relation `fee == amount * 2` is therefore
          * the discriminating assertion, and it holds whichever arm landed first.
          */
        "two race arms embedding one subflow name run their children against the first-recorded inputs" in {
            withEngine { (engine, store, tc) =>
                val child = Flow.input[Int]("amount").output("fee")(ctx => ctx.amount * 2)
                val left  = Flow.init("shared-name").subflow("review", child)(_ => "amount" ~ 1)
                val right = Flow.init("shared-name").subflow("review", child)(_ => "amount" ~ 2)
                val flow  = Flow.race(left, right)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    status  <- pump(tc, store, eid, _.isTerminal, 400)
                    amount  <- store.getField[Int](eid, "review~amount")
                    fee     <- store.getField[Int](eid, "review~fee")
                    history <- store.getHistory(eid, Maybe.empty, 0)
                yield
                    assert(status == Flow.Status.Completed, s"the premise is that the race completed, got $status")
                    assert(
                        amount == Present(1) || amount == Present(2),
                        s"one of the two arms records the input, got $amount"
                    )
                    assert(
                        fee == amount.map(_ * 2),
                        s"and both children run against the value that landed rather than the one they mapped, got $fee for $amount"
                    )
                    val supplied = history.events.filter(_.kind == Flow.EventKind.InputSupplied).map(_.detail)
                    assert(
                        supplied == Chunk("review~amount"),
                        s"the second write is refused rather than appended, so the path is recorded once, got $supplied"
                    )
                end for
            }
        }

        /** A child input is not signallable, by its bare name or by its path, and a refused signal writes nothing.
          *
          * A child input has exactly one source, the mapper, which is why its record needs no atomic seed the way a top-level input's
          * does: a top-level input can arrive from the start or from a later `signal`, so a missing field there is ambiguous, and a
          * missing child field never is. That rests on `signal` resolving names against the definition's own declared inputs, which do
          * not descend into a child, and this pins it by asking rather than by reading the resolver.
          *
          * Both spellings are refused with the same error, and the recorded value is untouched by either attempt: the bare name
          * belongs to no input this definition declares, and the qualified one is a durable path rather than a name.
          */
        "a child input cannot be signalled by either name" in {
            withEngine { (engine, store, tc) =>
                val child = Flow.input[Int]("amount").output("fee")(ctx => ctx.amount * 2)
                val flow = Flow.input[Int]("x")
                    .subflow("review", child)(ctx => "amount" ~ ctx.x)
                    .input[String]("gate")
                    .output("done")(_ => "ok")
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _         <- engine.executions.signal[Int](eid, "x", 5)
                    _         <- pumpState(tc, store, eid, waitingFor("gate"))
                    bare      <- Abort.run[FlowException](engine.executions.signal[Int](eid, "amount", 9))
                    qualified <- Abort.run[FlowException](engine.executions.signal[Int](eid, "review~amount", 9))
                    amount    <- store.getField[Int](eid, "review~amount")
                    stray     <- store.getField[Int](eid, "amount")
                yield
                    bare match
                        case Result.Failure(e: FlowSignalNotFoundException) =>
                            assert(e.inputName == "amount", s"the refusal names what was asked for, got ${e.inputName}")
                        case other => fail(s"the bare name must be refused as an unknown input, got $other")
                    end match
                    qualified match
                        case Result.Failure(e: FlowSignalNotFoundException) =>
                            assert(e.inputName == "review~amount", s"the refusal names what was asked for, got ${e.inputName}")
                        case other => fail(s"the qualified name must be refused too, got $other")
                    end match
                    assert(amount == Present(5), s"and the recorded value is untouched by either attempt, got $amount")
                    assert(stray == Absent, s"with nothing written under the bare name, got $stray")
                end for
            }
        }

        /** An attempt resuming an unwind records nothing at a subflow it never entered.
          *
          * The entry's writes are guarded like every other write, so an attempt that is re-entering an unwind reaches the arm and
          * leaves without writing. That matters because the alternative is a durable input for work that will never run: the
          * execution is on its way to the verdict a previous attempt recorded, and seeding a child it is abandoning would leave the
          * store holding a value nothing ever ran against, on an execution that ends anyway.
          *
          * **Reaching the arm at all takes care, and every simpler shape fails to.** A resumed forward pass raises at the first
          * node that would actually run, so a subflow AFTER a failing node is never reached (the failing node re-runs and its own
          * verb's guard raises first), and a subflow BEFORE one was entered on the earlier attempt and owes nothing. A `zip` sibling
          * holding an un-entered subflow does reach it, but the branches are concurrent and a failure decides the composition at
          * once, so whether the sibling walks that far is a scheduling race rather than a fact.
          *
          * The shape here drops the failure entirely: the verdict is a CANCELLATION, seeded as the row's recorded lifecycle, and the
          * subflow is the flow's only node. A subflow writes nothing under its own name, so the arm is entered on every attempt;
          * nothing precedes it, so no other guard can fire first; and the instance was never entered, so it owes its child's one
          * declared input.
          *
          * **The mapper's run count is the discriminating assertion.** Absent it, the leaf would pass just as well against an arm
          * that never reached the hook at all, or one whose owed-check wrongly found nothing owed. One run says the arm went the
          * whole way, built the child's record, and was stopped by the guard rather than by anything upstream of it.
          */
        "a resumed unwind that reaches an un-entered subflow writes no input" in {
            withEngine { (engine, store, tc) =>
                AtomicInt.init(0).map { mapperRuns =>
                    val child = Flow.input[Int]("amount").output("fee")(ctx => ctx.amount * 2)
                    val flow = Flow.init("resumed-unwind")
                        .subflow("review", child)(_ => mapperRuns.incrementAndGet.andThen("amount" ~ 1))
                    for
                        eid <- Sync.defer(Flow.Id.Execution("exec-resumed-unwind-subflow"))
                        // Seeded before the workflow is registered, for the reason the other seeded leaves state: a row nothing
                        // serves yet cannot be claimed out from under the fixture. The lifecycle IS the premise here, because
                        // `Compensating(cause)` is what the attempt reads to know it is re-entering an unwind.
                        hash = kyo.internal.WorkflowSchema.structuralHash(flow)
                        _       <- seedExecution(store, eid, wf1, Flow.Status.Compensating(Flow.Cause.Cancellation), hash)
                        _       <- engine.register(wf1, flow)
                        status  <- pump(tc, store, eid, _.isTerminal)
                        amount  <- store.getField[Int](eid, "review~amount")
                        ran     <- mapperRuns.get
                        history <- store.getHistory(eid, Maybe.empty, 0)
                    yield
                        assert(
                            status == Flow.Status.Cancelled,
                            s"the execution ends as the verdict it was resuming says, got $status"
                        )
                        assert(ran == 1, s"the arm ran the mapper, so it reached the hook rather than stopping short, ran $ran times")
                        assert(
                            !history.events.exists(_.kind == Flow.EventKind.InputSupplied),
                            s"and recorded no input for the instance it never entered, got " +
                                s"${history.events.map(e => (e.kind, e.detail))}"
                        )
                        assert(amount == Absent, s"with no field under its path either, got $amount")
                    end for
                }
            }
        }

        /** A name two race arms of one child read is recorded once, under one path.
          *
          * A child may declare one input twice, because two branches waiting on one name is the case registration allows outright:
          * two READS of one name are not a conflict, it is one wait and one field. The entry therefore has to be total over a
          * collector that sees the name twice, and this runs that child to completion through a subflow.
          *
          * **What it pins, stated precisely, because it is less than it looks.** It pins the PATH: one supply event and one field
          * for `review~k`, whichever way the collector is built. It does NOT discriminate the collector's deduplication: with the
          * dedup removed the leaf still passes, because the second write is answered already-recorded, the store appends no event on
          * a refusal, and the read-back substitutes the value that landed, which is the same value. So the dedup is a store round-trip
          * the entry avoids rather than a behaviour anything can observe, and this leaf is the coverage of the doubled-name path
          * rather than a guard on the dedup.
          */
        "a child whose two race arms read one input name records it once" in {
            withEngine { (engine, store, tc) =>
                val leftArm  = Flow.init("left-arm").input[Int]("k").output("lv")(ctx => ctx.k * 2)
                val rightArm = Flow.init("right-arm").input[Int]("k").output("rv")(ctx => ctx.k * 3)
                val child    = Flow.race(leftArm, rightArm)
                val flow     = Flow.init("shared-read").subflow("review", child)(_ => "k" ~ 5)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    status  <- pump(tc, store, eid, _.isTerminal)
                    k       <- store.getField[Int](eid, "review~k")
                    history <- store.getHistory(eid, Maybe.empty, 0)
                yield
                    assert(status == Flow.Status.Completed, s"the premise is that the execution completed, got $status")
                    assert(k == Present(5), s"the doubly-declared name carries the mapper's one value, got $k")
                    val supplied = history.events.filter(_.kind == Flow.EventKind.InputSupplied).map(_.detail)
                    assert(
                        supplied == Chunk("review~k"),
                        s"and is recorded once rather than once per arm that declares it, got $supplied"
                    )
                end for
            }
        }
    }

    /** A store that fails the write recording one child input's supplied value, the first `remaining` times.
      *
      * The sibling of [[FailingCompletionStore]] one node kind over, and what it stages is the one partial state a subflow's entry
      * can be left in: a child declaring two inputs whose first write landed and whose second did not. Nothing of the child has run
      * at that point, because the writes precede its first node, so the state is legitimate and the next entry has to complete it
      * rather than start over.
      */
    private class FailingInputSupplyStore(underlying: FlowStore, path: String, remaining: AtomicInt) extends DelegatingStore(underlying):
        override protected def wrapClaimed(claimed: FlowStore.Claimed)(using Frame): FlowStore.Claimed =
            new DelegatingClaimed(claimed):
                override def recordProgress[V](target: String, value: Maybe[V], event: Flow.Event)(using
                    Tag[V],
                    Schema[V],
                    Frame
                ): FlowStore.ProgressOutcome < (Async & Abort[FlowStoreException]) =
                    event match
                        case Flow.Event.InputSupplied(_, _, name, _) if name == path =>
                            remaining.getAndDecrement.map { left =>
                                if left > 0 then Abort.fail(FlowEngineTest.StoreUnavailable(s"blip supplying '$name'"))
                                else super.recordProgress[V](target, value, event)
                            }
                        case _ => super.recordProgress[V](target, value, event)
    end FailingInputSupplyStore

end FlowEngineSubflowTest
