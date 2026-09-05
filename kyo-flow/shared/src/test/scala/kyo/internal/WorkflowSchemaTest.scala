package kyo.internal

import kyo.*

class WorkflowSchemaTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    /** One field written the way starting an execution writes it, which is the writer that leaves no event behind.
      *
      * A fixture that wants a value present without a story about how it arrived seeds it with the row. Delivering it instead would
      * record the arrival, which is correct for a delivery and wrong for a fixture whose leaf counts the history.
      */
    private def seeded[V: Tag: Schema](name: String, value: V): Dict[String, FlowStore.FieldData] =
        Dict(name -> FlowStore.FieldData(summon[Schema[V]].encodeString[Json](value), Tag[V]))

    "structuralHash" - {

        "same flow produces same hash" in {
            val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x * 2)
            assert(WorkflowSchema.structuralHash(flow) == WorkflowSchema.structuralHash(flow))
        }

        /** Two flows that run the same nodes in different SHAPES are different structures.
          *
          * The hash contributes a string at each leaf and folds every combinator with the same separator, so sequencing two steps,
          * zipping them, and racing them all produce one hash. Those are not the same workflow: they differ in what runs
          * concurrently, in what the execution waits for, and in how replay reconstructs it. The whole parking and version
          * enforcement mechanism keys on this hash, so an execution started under one shape resumes under another without anything
          * noticing that the definition changed.
          */
        "sequencing, zipping, and racing the same nodes hash differently" in {
            val a       = Flow.init("f").step("a")(_ => ())
            val b       = Flow.init("f").step("b")(_ => ())
            val seq     = Flow.init("f").step("a")(_ => ()).step("b")(_ => ())
            val zipped  = a.zip(b)
            val raced   = Flow.race(a, b)
            val seqHash = WorkflowSchema.structuralHash(seq)
            val zipHash = WorkflowSchema.structuralHash(zipped)
            val racHash = WorkflowSchema.structuralHash(raced)
            assert(seqHash != zipHash, s"a sequence and a zip of the same nodes must differ, both hashed $seqHash")
            assert(seqHash != racHash, s"a sequence and a race of the same nodes must differ, both hashed $seqHash")
            assert(zipHash != racHash, s"a zip and a race of the same nodes must differ, both hashed $zipHash")
        }

        /** A GUARD: gathering is a fourth shape, distinct from all three above.
          *
          * `gather` runs every branch and keeps every result, which is not a zip (arity aside, a zip is the two-branch pairing),
          * not a race (a race keeps one), and not a sequence. The hash gives it its own bracket, and every combinator the hash
          * brackets separately needs one assertion that the bracket matters, or a later simplification can quietly fold two
          * shapes back together.
          */
        "gathering the same nodes hashes differently from sequencing, zipping, and racing them" in {
            val a        = Flow.init("f").step("a")(_ => ())
            val b        = Flow.init("f").step("b")(_ => ())
            val seq      = Flow.init("f").step("a")(_ => ()).step("b")(_ => ())
            val gathered = Flow.gather(a, b)
            val gatHash  = WorkflowSchema.structuralHash(gathered)
            assert(
                gatHash != WorkflowSchema.structuralHash(seq),
                s"a gather and a sequence of the same nodes must differ, both hashed $gatHash"
            )
            assert(
                gatHash != WorkflowSchema.structuralHash(a.zip(b)),
                s"a gather and a zip of the same nodes must differ, both hashed $gatHash"
            )
            assert(
                gatHash != WorkflowSchema.structuralHash(Flow.race(a, b)),
                s"a gather and a race of the same nodes must differ, both hashed $gatHash"
            )
        }

        /** A change inside a subflow changes the parent's hash.
          *
          * The shape walk descends into the child: a subflow contributes its body's whole shape under its own bracket, not just
          * its name. Rewriting a child, adding a step or renaming one, therefore re-keys every parent that embeds it, and an
          * in-flight execution parks instead of replaying against a definition that changed underneath it.
          */
        "a change inside a subflow changes the parent's hash" in {
            val childV1  = Flow.input[Int]("a").output("b")(ctx => ctx.a)
            val childV2  = Flow.input[Int]("a").step("extra")(_ => ()).output("b")(ctx => ctx.a)
            val parentV1 = Flow.input[Int]("x").subflow("sub", childV1)(ctx => "a" ~ ctx.x)
            val parentV2 = Flow.input[Int]("x").subflow("sub", childV2)(ctx => "a" ~ ctx.x)
            assert(
                WorkflowSchema.structuralHash(parentV1) != WorkflowSchema.structuralHash(parentV2),
                "a parent embedding a changed subflow must not keep its hash"
            )
        }

        /** A GUARD: the stored-TYPES half of the identity descends into subflows too.
          *
          * The leaf above cannot pin this: it changes the child's structure, which the shape walk catches on its own, so it
          * stays green even if the types walk stops at the subflow's name. Here the two parents' shape strings are
          * byte-identical (a loop contributes only its name to the shape) and ONLY the descending types walk separates them.
          * This is the leaf that catches a later unification of the two walks dropping descent from the types half.
          */
        "a change to a child loop's value type changes the parent's hash" in {
            val childV1  = Flow.input[Int]("a").loop("acc")(_ => Loop.done(0))
            val childV2  = Flow.input[Int]("a").loop("acc")(_ => Loop.done("zero"))
            val parentV1 = Flow.input[Int]("x").subflow("sub", childV1)(ctx => "a" ~ ctx.x)
            val parentV2 = Flow.input[Int]("x").subflow("sub", childV2)(ctx => "a" ~ ctx.x)
            val h1       = WorkflowSchema.structuralHash(parentV1)
            val h2       = WorkflowSchema.structuralHash(parentV2)
            assert(
                h1 != h2,
                s"a child loop storing Int and one storing String must not share the parent's version, both hashed $h1"
            )
        }

        /** A node whose stored value changes type changes the hash.
          *
          * The identity's second half lists the type of every value the flow persists, one entry per field replay decodes:
          * inputs, outputs, dispatch choices, loop values with the state they carry between iterations, and foreach collections.
          * Changing a loop's value type from `Int` to `String` therefore changes the hash even though the composition shape is
          * byte-identical, and an in-flight execution parks instead of resuming against a schema its persisted fields no longer
          * decode under, which is what would silently re-run the node, side effects included.
          */
        "a loop whose value type changes does not keep its hash" in {
            val v1 = Flow.init("f").loop("acc")(_ => Loop.done(0))
            val v2 = Flow.init("f").loop("acc")(_ => Loop.done("zero"))
            assert(
                WorkflowSchema.structuralHash(v1) != WorkflowSchema.structuralHash(v2),
                s"a loop storing Int and one storing String must not share a version, both hashed " +
                    s"${WorkflowSchema.structuralHash(v1)}"
            )
        }

        /** A dispatch's branch names are structure; its predicates are code.
          *
          * Changing which branch a row takes changes what the flow does, but a predicate is a lambda, and the hash deliberately
          * ignores lambdas so that a bug fix inside a step body does not park every in-flight execution. The price of that choice
          * is what this leaf pins: two flows that route rows differently share a version, so a predicate edit reaches in-flight
          * executions instead of parking them.
          */
        "a changed dispatch predicate leaves the hash alone" in {
            val v1 = Flow.input[Int]("x")
                .dispatch[String]("route")
                .when(ctx => ctx.x > 0, name = "positive")(ctx => "pos")
                .otherwise(ctx => "other", name = "default")
            val v2 = Flow.input[Int]("x")
                .dispatch[String]("route")
                .when(ctx => ctx.x > 100, name = "positive")(ctx => "pos")
                .otherwise(ctx => "other", name = "default")
            assert(
                WorkflowSchema.structuralHash(v1) == WorkflowSchema.structuralHash(v2),
                "a dispatch predicate is a lambda, and the hash ignores lambdas by design"
            )
        }

        /** A renamed dispatch branch does change the hash, because a branch name IS structure. */
        "a renamed dispatch branch changes the hash" in {
            val v1 = Flow.input[Int]("x")
                .dispatch[String]("route")
                .when(ctx => ctx.x > 0, name = "positive")(ctx => "pos")
                .otherwise(ctx => "other", name = "default")
            val v2 = Flow.input[Int]("x")
                .dispatch[String]("route")
                .when(ctx => ctx.x > 0, name = "renamed")(ctx => "pos")
                .otherwise(ctx => "other", name = "default")
            assert(
                WorkflowSchema.structuralHash(v1) != WorkflowSchema.structuralHash(v2),
                "a branch name is part of the flow's shape and must be part of its version"
            )
        }

        "identical structure with different closures keeps the same hash" in {
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
    // Subflow child field inclusion
    // =========================================================================

    "schema includes child flow fields from subflows" - {

        "child output field is present in schema" in {
            val child = Flow.input[Int]("a").output("b")(ctx => ctx.a * 10)
            val flow = Flow.input[Int]("x")
                .subflow("payment", child)(ctx => "a" ~ ctx.x)
                .output("result")(ctx => ctx.payment.b)

            val schema = WorkflowSchema.of(flow)
            assert(schema.fromStoreName("x").nonEmpty, "parent input 'x' should be in schema")
            assert(schema.fromStoreName("result").nonEmpty, "parent output 'result' should be in schema")
            // A child's field is keyed by its path, because that is what the store holds it under.
            assert(schema.fromStoreName("payment~b").nonEmpty, "child output 'payment~b' should be in schema")
        }

        "child input field is present in schema" in {
            val child = Flow.input[Int]("a").output("b")(ctx => ctx.a)
            val flow = Flow.input[Int]("x")
                .subflow("s", child)(ctx => "a" ~ ctx.x)
                .output("y")(ctx => 1)

            val schema = WorkflowSchema.of(flow)
            assert(schema.fromStoreName("s~a").nonEmpty, "child input 's~a' should be in schema")
        }

        "nested subflow fields are present in schema" in {
            val inner = Flow.input[Int]("ia").output("ib")(ctx => ctx.ia)
            val outer = Flow.input[Int]("oa")
                .subflow("inner", inner)(ctx => "ia" ~ ctx.oa)
                .output("ob")(ctx => ctx.inner.ib)
            val flow = Flow.input[Int]("x")
                .subflow("outer", outer)(ctx => "oa" ~ ctx.x)
                .output("result")(ctx => ctx.outer.ob)

            val schema = WorkflowSchema.of(flow)
            assert(schema.fromStoreName("x").nonEmpty, "top-level input 'x' should be in schema")
            // Nested paths compose, so the inner child's field is keyed under both instances that contain it.
            assert(schema.fromStoreName("outer~inner~ib").nonEmpty, "inner child output 'outer~inner~ib' should be in schema")
            assert(schema.fromStoreName("outer~ob").nonEmpty, "outer child output 'outer~ob' should be in schema")
        }
    }

    "rebuildRecord preserves child flow fields" - {

        "child output field survives rebuild" in {
            val child = Flow.input[Int]("a").output("b")(ctx => ctx.a * 10)
            val flow = Flow.input[Int]("x")
                .subflow("payment", child)(ctx => "a" ~ ctx.x)
                .output("result")(ctx => ctx.payment.b)

            val schema = WorkflowSchema.of(flow)

            val fields = Dict(
                "x"         -> FlowStore.FieldData(Json.encode(5), Tag[Int].erased),
                "payment~b" -> FlowStore.FieldData(Json.encode(50), Tag[Int].erased)
            )

            val record = rebuildRecord(fields, schema)
            val dict   = record.toDict

            assert(dict.get("x").nonEmpty, "parent field 'x' should be in rebuilt record")
            assert(dict.get("payment~b").nonEmpty, "child field 'payment~b' should survive rebuild")
        }
    }

    private def rebuildRecord(fields: Dict[String, FlowStore.FieldData], schema: WorkflowSchema): Record[Any] =
        val dict = fields.foldLeft(Dict.empty[String, Any]) { (acc, name, fd) =>
            schema.fromStoreName(name) match
                case Present(entry) =>
                    entry.decode(fd) match
                        case Present(v) => acc.update(name, v)
                        case _          => acc
                case _ => acc
        }
        new Record[Any](dict)
    end rebuildRecord

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
            maxRounds: Int = 200
        )(using Frame): Flow.Status < (Async & Abort[FlowStoreException]) =
            def go(remaining: Int): Flow.Status < (Async & Abort[FlowStoreException]) =
                if remaining <= 0 then Abort.panic(new AssertionError("pump timed out"))
                else
                    tc.advance(10.millis).map { _ =>
                        store.getExecution(eid).map {
                            case Present(state) if predicate(state.status) => state.status
                            case _                                         => go(remaining - 1)
                        }
                    }
            go(maxRounds)
        end pump

        "execution stores the structural hash at creation time" in {
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

        "changed workflow definition rejects in-flight execution" in {
            withEngine { (engine, store, tc) =>
                val flowV1 = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                val flowV2 = Flow.input[Int]("x").output("y")(ctx => ctx.x).output("z")(ctx => 0)
                for
                    _      <- engine.register(wf1, flowV1)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 42)
                    // Let v1 start processing
                    _ <- pump(tc, store, eid, s => s == Flow.Status.Completed || s == Flow.Status.Running)
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

        /** A hash mismatch holds the execution rather than failing it.
          *
          * The property these two leaves pin is that such an execution is neither served nor lost sight of. Failing it would satisfy
          * the visible half and destroy the rest: `Failed` is terminal, so a version bump would terminalise every execution it
          * touched and skip their compensations on the way out, with no way back. Being held keeps the execution recoverable, and it
          * is a report rather than a status: the engine names the executions it does not serve, and writes nothing about them.
          */
        "execution with mismatched hash is parked, not processed" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                    for
                        engine <- FlowEngine.init(store, workerCount = 1, pollTimeout = 100.millis)
                        _      <- engine.register(wf1, flow)
                        // Manually create an execution whose stored hash ("") cannot match the registered flow.
                        now <- Clock.now
                        eid = Flow.Id.Execution("hash-mismatch-test")
                        _ <- store.createExecutionIfAbsent(
                            eid,
                            Flow.Status.Running,
                            Flow.Event.Created(wf1, eid, now),
                            "",
                            seeded[Int]("x", 42)
                        )
                        // A held execution is written to by nobody, so one advance is a stable read rather than a race.
                        _     <- tc.advance(2.seconds)
                        state <- store.getExecution(eid)
                        held  <- engine.parked(wf1)
                    yield
                        // Hash mismatch: the engine holds the execution and names it, rather than running it or ignoring it.
                        assert(
                            held.exists(e => e.executionId == eid && e.hash.isEmpty),
                            s"an execution with a mismatched hash must be reported as held, got ${held.map(e => (e.executionId, e.hash))}"
                        )
                        assert(
                            state.get.status == Flow.Status.Running,
                            s"a held execution must be left exactly as it was, but status is ${state.get.status}"
                        )
                    end for
                }
            }
        }

        "structural hash mismatch parks the execution rather than leaving it stuck" in {
            withEngine { (engine, store, tc) =>
                val flowV1 = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                val flowV2 = Flow.input[Int]("x").output("y")(ctx => ctx.x).output("z")(ctx => 0)
                for
                    // Only v2 is registered, so v1 is a version this engine does not serve. An engine that had registered v1 too
                    // would go on serving it, which is what keeps a rolling deployment's in-flight executions running.
                    _   <- engine.register(wf1, flowV2)
                    now <- Clock.now
                    stuckEid = Flow.Id.Execution("stuck-version")
                    _ <- store.createExecutionIfAbsent(
                        stuckEid,
                        Flow.Status.Running,
                        Flow.Event.Created(wf1, stuckEid, now),
                        WorkflowSchema.structuralHash(flowV1), // v1 hash
                        seeded[Int]("x", 10)
                    )
                    // Two full poll cycles' worth of virtual time, so a poller that claimed and re-released would have done it twice.
                    _       <- Kyo.foreachDiscard(1 to 60)(_ => tc.advance(10.millis))
                    before  <- store.getHistory(stuckEid, Maybe.empty, 0)
                    _       <- Kyo.foreachDiscard(1 to 60)(_ => tc.advance(10.millis))
                    history <- store.getHistory(stuckEid, Maybe.empty, 0)
                    held    <- engine.parked(wf1)
                yield
                    assert(
                        held.exists(e =>
                            e.executionId == stuckEid && e.hash == WorkflowSchema.structuralHash(flowV1)
                        ),
                        s"the held execution must be reported with the hash it was started under, got " +
                            s"${held.map(e => (e.executionId, e.hash))}"
                    )
                    // Stronger than counting one park event: under the version gate no executor ever claims the execution, so it
                    // accumulates NOTHING while it is held, where a claim-and-re-park loop would leave a trail of events per poll.
                    assert(
                        history.events.size == 1 && history.events.head.kind == Flow.EventKind.Created,
                        s"a held execution must accumulate no events at all, got ${history.events.map(_.kind)}"
                    )
                    assert(
                        history.events.size == before.events.size,
                        s"and it must still accumulate none over the next poll cycle, grew from ${before.events.size} to " +
                            s"${history.events.size}"
                    )
                end for
            }
        }
    }

end WorkflowSchemaTest
