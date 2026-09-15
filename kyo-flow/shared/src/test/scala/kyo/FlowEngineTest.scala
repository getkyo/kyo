package kyo

object FlowEngineTest:
    /** A step's own domain failure: not an engine error, and the reason FlowException has an open branch. */
    case class ChargeDeclined(orderId: String, cents: Long)(using Frame)
        extends FlowDomainException(s"charge declined for $orderId: $cents cents over limit")

    /** A store's own failure, the way a store over a database reports one: typed, and marked retryable when the backend says so. */
    case class StoreUnavailable(detail: String)(using Frame)
        extends FlowStoreException(s"the store could not be reached: $detail") with FlowStoreException.Retryable
end FlowEngineTest

/** What every engine suite needs to drive an execution: an engine, a controlled clock, and the pumps that move one forward.
  *
  * The engine's leaves are spread over more than one class out of necessity: a test class carries the body of every leaf it registers
  * in its constructor, and the JVM caps how large a class may be. `FlowEngineForeachTest` is one such aspect file. The helpers live
  * here rather than being copied, so a fixture that changes shape changes in one place.
  */
abstract private[kyo] class FlowEngineSupport extends kyo.test.Test[Any]:

    override def timeout = 30.seconds

    given CanEqual[Any, Any] = CanEqual.derived

    val wf1 = Flow.Id.Workflow("test-flow")

    protected def withEngine[A](
        f: (FlowEngine, FlowStore, Clock.TimeControl) => A < (Async & Scope & Abort[Any])
    )(using Frame): A < (Async & Scope & Abort[Any]) =
        Clock.withTimeControl { tc =>
            FlowStore.initMemory.map { store =>
                FlowEngine.init(store, workerCount = 1, lease = 30.seconds, pollTimeout = 100.millis).map { engine =>
                    f(engine, store, tc)
                }
            }
        }

    protected def pump(
        tc: Clock.TimeControl,
        store: FlowStore,
        eid: Flow.Id.Execution,
        predicate: Flow.Status => Boolean,
        maxRounds: Int = 200
    )(using Frame): Flow.Status < (Async & Abort[FlowStoreException]) =
        def go(remaining: Int): Flow.Status < (Async & Abort[FlowStoreException]) =
            if remaining <= 0 then
                store.getExecution(eid).map { state =>
                    Abort.panic(new AssertionError(s"pump timed out, last state: $state"))
                }
            else
                tc.advance(10.millis).map { _ =>
                    store.getExecution(eid).map {
                        case Present(state) if predicate(state.status) => state.status
                        case _                                         => go(remaining - 1)
                    }
                }
        go(maxRounds)
    end pump

    /** [[pump]] over the whole execution row rather than its status, for the questions the status cannot answer.
      *
      * What an execution is waiting for is a row per waiting node, so a predicate about waiting reads `waits`; the status says `Running`
      * for a working execution and a waiting one alike.
      */
    protected def pumpState(
        tc: Clock.TimeControl,
        store: FlowStore,
        eid: Flow.Id.Execution,
        predicate: FlowStore.ExecutionState => Boolean,
        maxRounds: Int = 200
    )(using Frame): FlowStore.ExecutionState < (Async & Abort[FlowStoreException]) =
        def go(remaining: Int): FlowStore.ExecutionState < (Async & Abort[FlowStoreException]) =
            if remaining <= 0 then
                store.getExecution(eid).map { state =>
                    Abort.panic(new AssertionError(s"pumpState timed out, last state: $state"))
                }
            else
                tc.advance(10.millis).map { _ =>
                    store.getExecution(eid).map {
                        case Present(state) if predicate(state) => state
                        case _                                  => go(remaining - 1)
                    }
                }
        go(maxRounds)
    end pumpState

    /** The execution holds an outstanding wait for the input named `name`. */
    protected def waitingFor(name: String)(state: FlowStore.ExecutionState): Boolean =
        state.waits.get(name).exists {
            case Flow.Wake.OnField(_) => true
            case _                    => false
        }

    /** The execution holds at least one outstanding sleep. */
    protected def sleeping(state: FlowStore.ExecutionState): Boolean =
        state.waits.values.exists {
            case Flow.Wake.At(_) => true
            case _               => false
        }

    /** The deadline of the execution's wait row at `path`, for a leaf that asserts which deadline stands. */
    protected def deadlineOf(state: FlowStore.ExecutionState, path: String): Maybe[Instant] =
        state.waits.get(path) match
            case Present(Flow.Wake.At(instant)) => Maybe(instant)
            case _                              => Maybe.empty

    /** Takes the claim on one execution the way a poll does, for a fixture that needs to write to it.
      *
      * Every run-time write is a method on the claim, so a fixture that writes to a row has to be handed one first. A poll
      * claims a batch, and a fixture wants one row, so the others go back exactly as they were found: an ending that keeps precisely
      * the rows they already held, which neither retires a row nor blesses one.
      *
      * **The claim is asked for exactly once, and a leaf that must seed makes sure nothing else can be asking.** A claim belongs to
      * its holder until that holder's attempt ends, so a fixture that loses one has no way to win it back: it cannot wait for an
      * attempt it did not start, and asking again in a loop is worse than useless, because a fixture re-asks as fast as the store
      * answers and spends every attempt inside the moment the holder needed to make progress. What keeps the fixture and a worker
      * from competing is ORDER, and the leaves here use it: they seed before the workflow is registered, so readiness serves no pair
      * this row could be claimed under while the fixture writes.
      */
    protected def seedClaim(
        store: FlowStore,
        eid: Flow.Id.Execution,
        flowId: Flow.Id.Workflow,
        hash: String,
        executor: Flow.Id.Executor = Flow.Id.Executor("seeder")
    )(using Frame): FlowStore.Claimed < (Async & Abort[FlowStoreException]) =
        store.claimReady(Set((flowId, hash)), executor, 30.seconds, 10, Duration.Zero).map { batch =>
            Kyo.foreachDiscard(batch.filter(_.state.executionId != eid)) { other =>
                other.finish(FlowStore.Claimed.Outcome.Suspended(other.state.waits.toChunk.map(_._1).toSet)).unit
            }.andThen {
                Maybe.fromOption(batch.find(_.state.executionId == eid)) match
                    case Present(claimed) => claimed
                    case Absent =>
                        store.getExecution(eid).map { row =>
                            Abort.panic(new IllegalStateException(
                                s"the fixture could not claim ${eid.value}: it is held by ${row.flatMap(_.executor)}, and " +
                                    s"readiness handed back ${batch.map(_.state.executionId.value)}. Seed before the workflow " +
                                    s"is registered, or before the engine exists."
                            ))
                        }
            }
        }
    end seedClaim

    /** Seeds wait rows the way the system parks an execution: claim the row, record each branch, end the attempt parked on them.
      *
      * The rows a fixture wants are exactly the rows a suspending ending blesses, so seeding through the public recipe is what makes
      * the seeded state one a real attempt could have produced. A row written under a claim that is still live is a different state,
      * and readiness reads the difference.
      *
      * **Seed while nothing else can claim the row, which for these leaves means before the engine is built.** A freshly created
      * execution with no rows yet is READY, so a worker already polling for its pair will take it, and a fixture that loses the claim
      * cannot get it back: see [[seedClaim]] for why asking again in a loop is not a fix. Building the engine after the seeding
      * closes it completely, because the row is parked on deadlines that have not arrived by the time any worker first looks at it.
      */
    protected def seedWaits(
        store: FlowStore,
        eid: Flow.Id.Execution,
        flowId: Flow.Id.Workflow,
        hash: String,
        waits: (String, Flow.Wake)*
    )(using Frame): Unit < (Async & Abort[FlowStoreException]) =
        def record(claimed: FlowStore.Claimed): Unit < (Async & Abort[FlowStoreException]) =
            Kyo.foreachDiscard(waits) { (path, wake) =>
                Clock.nowWith { now =>
                    val event = wake match
                        case Flow.Wake.At(instant)   => Flow.Event.SleepStarted(flowId, eid, path, instant, now)
                        case Flow.Wake.OnField(name) => Flow.Event.InputWaiting(flowId, eid, name, now)
                    claimed.recordWait(path, wake, event)
                }
            }.andThen(claimed.finish(FlowStore.Claimed.Outcome.Suspended(waits.map(_._1).toSet))).unit

        store.claimReady(Set((flowId, hash)), Flow.Id.Executor("seeder"), 30.seconds, 10, Duration.Zero).map { batch =>
            Kyo.foreachDiscard(batch.filter(_.state.executionId != eid)) { other =>
                other.finish(FlowStore.Claimed.Outcome.Suspended(other.state.waits.toChunk.map(_._1).toSet)).unit
            }.andThen {
                Maybe.fromOption(batch.find(_.state.executionId == eid)) match
                    case Present(claimed) => record(claimed)
                    case Absent =>
                        store.getExecution(eid).map { row =>
                            Abort.panic(new IllegalStateException(
                                s"the fixture could not claim ${eid.value} to seed its waits: it is held by " +
                                    s"${row.flatMap(_.executor)}, row is $row. Seed before the engine is built."
                            ))
                        }
            }
        }
    end seedWaits

    /** Creates an execution the way a start does, for a fixture that needs a row the engine did not make. */
    protected def seedExecution(
        store: FlowStore,
        eid: Flow.Id.Execution,
        flowId: Flow.Id.Workflow,
        status: Flow.Status,
        hash: String
    )(using Frame): Unit < (Async & Abort[FlowStoreException]) =
        Clock.nowWith { now =>
            store.createExecutionIfAbsent(eid, status, Flow.Event.Created(flowId, eid, now), hash, Dict.empty).unit
        }

    /** Advances the controlled clock until `cond` holds, and answers whether it ever did.
      *
      * The engine makes progress when its fibers run, and its fibers run when virtual time moves, so a leaf that advances a fixed
      * number of rounds and then asserts is really asserting that the work happened to be scheduled inside that window, which is a
      * coin flip dressed as a test. Driving the advance from the condition removes the dependence, and a leaf that wants to assert
      * something did NOT happen still gets a definite answer, because the condition is re-checked after every step rather than once
      * at the end.
      */
    protected def settle(
        tc: Clock.TimeControl,
        step: Duration = 10.millis,
        maxRounds: Int = 500
    )(cond: => Boolean < (Async & Abort[FlowStoreException]))(using Frame): Boolean < (Async & Abort[FlowStoreException]) =
        def go(remaining: Int): Boolean < (Async & Abort[FlowStoreException]) =
            cond.map {
                case true  => true
                case false => if remaining <= 0 then false else tc.advance(step).andThen(go(remaining - 1))
            }
        go(maxRounds)
    end settle

    /** Forwards every method of one claimed row to `underlying`, so a double that changes one of them says only what it changes.
      *
      * The run-time write verbs live on the claim rather than on the store, so a decorator intercepts a write by wrapping the CLAIM
      * the poll was handed. That is a second decorator layer, and it is the only place those verbs can be reached.
      */
    protected class DelegatingClaimed(underlying: FlowStore.Claimed) extends FlowStore.Claimed:
        def state: FlowStore.ExecutionState = underlying.state
        def satisfied: Set[String]          = underlying.satisfied
        def appendEvent(event: Flow.Event)(using Frame): FlowStore.WriteOutcome < (Async & Abort[FlowStoreException]) =
            underlying.appendEvent(event)
        def recordWait(path: String, wake: Flow.Wake, event: Flow.Event)(using
            Frame
        ): FlowStore.WriteOutcome < (Async & Abort[FlowStoreException]) =
            underlying.recordWait(path, wake, event)
        def renewClaim(lease: Duration)(using Frame): Boolean < (Async & Abort[FlowStoreException]) =
            underlying.renewClaim(lease)
        def updateStatus(status: Flow.Status, event: Flow.Event)(using
            Frame
        ): FlowStore.StatusOutcome < (Async & Abort[FlowStoreException]) =
            underlying.updateStatus(status, event)
        def recordProgress[V](path: String, value: Maybe[V], event: Flow.Event)(using
            Tag[V],
            Schema[V],
            Frame
        ): FlowStore.ProgressOutcome < (Async & Abort[FlowStoreException]) =
            underlying.recordProgress[V](path, value, event)
        def finish(outcome: FlowStore.Claimed.Outcome)(using Frame): FlowStore.StatusOutcome < (Async & Abort[FlowStoreException]) =
            underlying.finish(outcome)
    end DelegatingClaimed

    /** Forwards every SPI method to `underlying`, so a double that changes one of them says only what it changes. */
    protected class DelegatingStore(underlying: FlowStore) extends FlowStore:

        /** How a claim handed back by `claimReady` is decorated. Subclasses that intercept a run-time write override this. */
        protected def wrapClaimed(claimed: FlowStore.Claimed)(using Frame): FlowStore.Claimed =
            new DelegatingClaimed(claimed)

        def claimReady(
            served: Set[(Flow.Id.Workflow, String)],
            executorId: Flow.Id.Executor,
            lease: Duration,
            limit: Int,
            timeout: Duration
        )(using Frame): Seq[FlowStore.Claimed] < (Async & Abort[FlowStoreException]) =
            underlying.claimReady(served, executorId, lease, limit, timeout).map(_.map(wrapClaimed))

        def createExecutionIfAbsent(
            executionId: Flow.Id.Execution,
            status: Flow.Status,
            event: Flow.Event,
            hash: String,
            fields: Dict[String, FlowStore.FieldData]
        )(using Frame): Boolean < (Async & Abort[FlowStoreException]) =
            underlying.createExecutionIfAbsent(executionId, status, event, hash, fields)
        def signal[V: Tag: Schema](executionId: Flow.Id.Execution, name: String, value: V, event: Flow.Event)(using
            Frame
        ): FlowStore.SignalOutcome < (Async & Abort[FlowStoreException]) =
            underlying.signal[V](executionId, name, value, event)
        def requestCancel(executionId: Flow.Id.Execution)(using
            Frame
        ): FlowStore.CancelOutcome < (Async & Abort[FlowStoreException]) =
            underlying.requestCancel(executionId)
        def getExecution(executionId: Flow.Id.Execution)(using
            Frame
        ): Maybe[FlowStore.ExecutionState] < (Async & Abort[FlowStoreException]) =
            underlying.getExecution(executionId)
        def listExecutions(flowId: Flow.Id.Workflow, filter: Maybe[FlowStore.ExecutionFilter], limit: Maybe[Int], offset: Int)(using
            Frame
        ): Chunk[FlowStore.ExecutionState] < (Async & Abort[FlowStoreException]) =
            underlying.listExecutions(flowId, filter, limit, offset)
        def getField[V: Tag: Schema](executionId: Flow.Id.Execution, name: String)(using
            Frame
        ): Maybe[V] < (Async & Abort[FlowStoreException]) =
            underlying.getField[V](executionId, name)
        def getAllFields(executionId: Flow.Id.Execution)(using
            Frame
        ): Dict[String, FlowStore.FieldData] < (Async & Abort[FlowStoreException]) =
            underlying.getAllFields(executionId)
        def getHistory(executionId: Flow.Id.Execution, limit: Maybe[Int], offset: Int)(using
            Frame
        ): FlowStore.HistoryPage < (Async & Abort[FlowStoreException]) =
            underlying.getHistory(executionId, limit, offset)
        def putWorkflow(meta: FlowEngine.WorkflowInfo)(using Frame): Unit < (Async & Abort[FlowStoreException]) =
            underlying.putWorkflow(meta)
        def getWorkflow(id: Flow.Id.Workflow)(using Frame): Maybe[FlowEngine.WorkflowInfo] < (Async & Abort[FlowStoreException]) =
            underlying.getWorkflow(id)
        def listWorkflows(using Frame): Seq[FlowEngine.WorkflowInfo] < (Async & Abort[FlowStoreException]) = underlying.listWorkflows
    end DelegatingStore

end FlowEngineSupport

class FlowEngineTest extends FlowEngineSupport:

    // =========================================================================
    // Basic execution
    // =========================================================================
    "basic execution" - {

        "single output flow completes" in {
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

        "two sequential outputs complete" in {
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

        "output values are persisted in store" in {
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

        "step flow completes" in {
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

        "flow failure results in Failed status" in {
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
                    case Flow.Status.Failed(msg, _) => assert(msg.contains("boom"))
                    case other                      => fail(s"Expected Failed, got $other")
                end for
            }
        }
    }

    // =========================================================================
    // Signal delivery
    // =========================================================================
    "signal delivery" - {

        "waits for input then resumes" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[String]("name").output("greeting")(ctx => s"Hello ${ctx.name}")
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- pumpState(tc, store, eid, waitingFor("name"))
                    _      <- engine.executions.signal[String](eid, "name", "World")
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status == Flow.Status.Completed)
                end for
            }
        }

        /** A start-seeded input and a signalled one leave histories a reader can tell apart.
          *
          * What distinguishes them is the ABSENCE of input events on the seeded side, not a discharge event on both. The discharge
          * write is guarded on the wait row, and a seeded input never parks: its value is in the record before the first attempt
          * reads it, the satisfied branch takes it, and nothing about waiting is recorded. An unguarded discharge would append one
          * `InputDischarged` per attempt on every replay past that input, which is a settled execution's history growing while it
          * waits, so making the two shapes identical is the one way to make them indistinguishable in practice.
          *
          * The signalled twin is asserted as an ordered triple rather than a set, because the order is the arrival story: the
          * execution parked, the value landed, the node consumed it and gave the row back.
          */
        "a seeded input and a signalled input leave different histories" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x + 1)
                def inputEvents(eid: Flow.Id.Execution) =
                    store.getHistory(eid, Maybe.empty, 0).map(_.events.map(_.kind).filter { kind =>
                        kind == Flow.EventKind.InputWaiting ||
                        kind == Flow.EventKind.InputReceived ||
                        kind == Flow.EventKind.InputDischarged
                    })
                for
                    _      <- engine.register(wf1, flow)
                    seeded <- engine.workflows.start(wf1, new Record[Any](Dict("x" -> 1)))
                    seededId = seeded.executionId
                    _         <- pump(tc, store, seededId, _.isTerminal)
                    signalled <- engine.workflows.start(wf1)
                    signalledId = signalled.executionId
                    _             <- pumpState(tc, store, signalledId, waitingFor("x"))
                    _             <- engine.executions.signal[Int](signalledId, "x", 1)
                    _             <- pump(tc, store, signalledId, _.isTerminal)
                    seededKinds   <- inputEvents(seededId)
                    signalledKind <- inputEvents(signalledId)
                yield
                    assert(
                        seededKinds.isEmpty,
                        s"an input that never waited records nothing about waiting, got $seededKinds"
                    )
                    assert(
                        signalledKind == Chunk(
                            Flow.EventKind.InputWaiting,
                            Flow.EventKind.InputReceived,
                            Flow.EventKind.InputDischarged
                        ),
                        s"a signalled input records parking, arrival and discharge in that order, got $signalledKind"
                    )
                end for
            }
        }

        "duplicate signal fails" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[String]("name").output("y")(ctx => ctx.name)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _   <- pumpState(tc, store, eid, waitingFor("name"))
                    _   <- engine.executions.signal[String](eid, "name", "first")
                    res <- Abort.run[FlowException](engine.executions.signal[String](eid, "name", "second"))
                yield assert(res.isFailure)
                end for
            }
        }

        /** A signal that RACES completion must not land on a terminal execution.
          *
          * The sequential case, "signal to terminal execution fails", pumps to terminal first and then signals, and it is the easy
          * one: the check sees a terminal status and refuses. The racing case is the one a read-check-write engine cannot handle,
          * because nothing makes the read, the check and the write atomic. `FlowStore.signal` is one store verb that checks and
          * writes in a single transition and answers `AlreadyTerminal` for an execution that is over.
          *
          * **The interleaving is forced, not sampled.** The store completes the execution at the moment the delivery's write
          * arrives, which is exactly the window between the check and the write. Every answer the store gives is truthful: the
          * status really is terminal by the time the field lands, which is the state a concurrent completion produces.
          *
          * What must not happen is a field, and an `InputReceived` after `Completed`, landing on an execution that is over. Either
          * the delivery is refused or it leaves nothing behind, and one verb makes it both at once.
          */
        "a signal racing completion does not land on a terminal execution" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { plain =>
                    AtomicInt.init(0).map { completions =>
                        val store = new CompleteOnDeliveryStore(plain, "x", completions, tc)
                        FlowEngine.init(store, workerCount = 1, lease = 30.seconds, pollTimeout = 100.millis).map { engine =>
                            val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                            for
                                _      <- engine.register(wf1, flow)
                                handle <- engine.workflows.start(wf1)
                                eid = handle.executionId
                                _     <- pumpState(tc, plain, eid, waitingFor("x"))
                                res   <- Abort.run[FlowException](engine.executions.signal[Int](eid, "x", 7))
                                raced <- completions.get
                                state <- plain.getExecution(eid)
                                field <- plain.getField[Int](eid, "x")
                                page  <- plain.getHistory(eid, Maybe.empty, 0)
                                after = page.events.dropWhile(_.kind != Flow.EventKind.Completed).map(_.kind)
                            yield
                                assert(raced == 1, s"the premise is that the store completed the execution under the delivery, $raced")
                                assert(
                                    state.exists(_.status.isTerminal),
                                    s"the premise is that the execution is terminal when the delivery lands, got ${state.map(_.status)}"
                                )
                                assert(
                                    res.isFailure || field.isEmpty,
                                    s"a delivery that loses the race to completion must be refused or leave nothing behind, " +
                                        s"got result $res with the field at $field"
                                )
                                assert(
                                    after.count(_ == Flow.EventKind.InputReceived) == 0,
                                    s"no delivery may be recorded after the execution completed, got $after"
                                )
                            end for
                        }
                    }
                }
            }
        }

        "signal to unknown input fails" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _   <- pumpState(tc, store, eid, waitingFor("x"))
                    res <- Abort.run[FlowException](engine.executions.signal[Int](eid, "unknown", 1))
                yield assert(res.isFailure)
                end for
            }
        }

        "signal to terminal execution fails" in {
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

        "unregistered workflow fails to start" in {
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

        "cancel waiting execution" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- pumpState(tc, store, eid, waitingFor("x"))
                    _ <- engine.executions.cancel(eid)
                    // The request comes back before the execution stops, because cancelling runs its compensations: the terminal
                    // write happens at the end of the unwind rather than in the caller's own call.
                    _     <- pump(tc, store, eid, _.isTerminal)
                    state <- store.getExecution(eid)
                yield assert(state.get.status == Flow.Status.Cancelled)
                end for
            }
        }

        "cancel completed execution is no-op" in {
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

        /** A cancel the store took says so, through the handle the caller already holds.
          *
          * Cancelling is a request, so nothing about it is observable on return except the answer: the execution keeps running until
          * an executor reaches a node boundary, unwinds it and writes the terminal status. The answer is therefore the only thing
          * separating a request that was taken from one that fell on an execution already over, and `Unit` would separate nothing.
          */
        "a cancel taken on a live execution answers accepted" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _       <- engine.register(wf1, flow)
                    handle  <- engine.workflows.start(wf1)
                    _       <- pumpState(tc, store, handle.executionId, waitingFor("x"))
                    outcome <- handle.cancel
                    state   <- store.getExecution(handle.executionId)
                yield
                    assert(
                        outcome == FlowStore.CancelOutcome.Accepted,
                        s"a request the store took must answer accepted, got $outcome"
                    )
                    assert(
                        state.exists(_.cancelRequested),
                        s"and the answer must be about a request that is now outstanding, got ${state.map(_.cancelRequested)}"
                    )
                end for
            }
        }

        /** A second ask says somebody asked first, which is a different instruction to the caller than the first answer.
          *
          * Staged on an execution this engine does not serve, so nothing can claim it and terminalise it between the two asks:
          * the leaf is about which answer a repeat gets, and racing an unwind would make it about scheduling instead.
          */
        "a repeated cancel answers already-requested" in {
            withEngine { (engine, store, tc) =>
                val unserved = Flow.Id.Execution("cancel-twice")
                for
                    _      <- seedExecution(store, unserved, Flow.Id.Workflow("unserved-flow"), Flow.Status.Running, "")
                    first  <- engine.executions.cancel(unserved)
                    second <- engine.executions.cancel(unserved)
                yield
                    assert(
                        first == FlowStore.CancelOutcome.Accepted,
                        s"the premise is that the first ask was taken, got $first"
                    )
                    assert(
                        second == FlowStore.CancelOutcome.AlreadyRequested,
                        s"a repeat must say the request already stands, got $second"
                    )
                end for
            }
        }

        /** Cancelling an execution that is over is an answer carrying what it ended as, not a failure.
          *
          * The caller got the outcome it asked for, so there is nothing to refuse; what it does not have is the result, and the
          * status is what tells it to go and read one rather than wait for an unwind that will never run.
          */
        "a cancel on a finished execution answers already-terminal with its status" in {
            withEngine { (engine, store, tc) =>
                val finished = Flow.Id.Execution("cancel-finished")
                for
                    _       <- seedExecution(store, finished, Flow.Id.Workflow("unserved-flow"), Flow.Status.Completed, "")
                    outcome <- engine.executions.cancel(finished)
                    state   <- store.getExecution(finished)
                yield
                    assert(
                        outcome == FlowStore.CancelOutcome.AlreadyTerminal(Flow.Status.Completed),
                        s"the answer must carry what the execution ended as, got $outcome"
                    )
                    assert(
                        !state.exists(_.cancelRequested),
                        s"and nothing may be written on a terminal row, got ${state.map(_.cancelRequested)}"
                    )
                end for
            }
        }

        "cancelAll cancels multiple executions" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _  <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    h1 <- engine.workflows.start(wf1)
                    eid1 = h1.executionId
                    h2 <- engine.workflows.start(wf1)
                    eid2 = h2.executionId
                    _     <- pumpState(tc, store, eid1, waitingFor("x"))
                    _     <- pumpState(tc, store, eid2, waitingFor("x"))
                    count <- engine.executions.cancelAll(Maybe(wf1))
                yield assert(count == 2)
                end for
            }
        }

        /** "Cancel everything" reaches an execution whose workflow this engine does not have registered.
          *
          * Enumerating `defs.keys`, the workflows registered in THIS engine, and asking only the executions belonging to them is the
          * tempting implementation of an unfiltered `cancelAll` and the wrong one. The store is shared and the engine is one of
          * several, so an execution written by another engine, or one left behind by a workflow that has since been unregistered, is
          * invisible to that sweep.
          *
          * That is the wrong population to be blind to. An execution whose definition this engine cannot resolve is exactly the one
          * an operator is trying to stop: nothing claims it, because `claimReady` is also called with the registered ids only, so it
          * cannot make progress and could not be cancelled either. The count is the damaging part: an operator reads "cancelled 3"
          * as "nothing is running", and the one execution that actually needed stopping is the one left out of the count.
          *
          * **What the sweep can promise is the request, which is why this asserts the request and the count.** Cancelling is a
          * request that an executor observes and unwinds, and the terminal write needs a claim-holding executor; the execution this
          * leaf is about is unclaimable here BY CONSTRUCTION, since readiness gates on the served set, so no engine in this test can
          * ever write `Cancelled` on it. Asserting a terminal status would be asserting something no correct engine could produce,
          * and would hide the two facts that are the leaf's actual subject.
          */
        "cancelAll requests cancellation of an execution whose workflow is not registered here" in {
            withEngine { (engine, store, tc) =>
                val flow    = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                val orphan  = Flow.Id.Workflow("unregistered-flow")
                val orphanE = Flow.Id.Execution("orphan-cancel")
                for
                    _ <- engine.register(wf1, flow)
                    // The other engine's registration, which is what makes the workflow a fact about the STORE while this engine
                    // serves nothing under it. Its execution is written the same way that engine would have written it.
                    _     <- store.putWorkflow(FlowEngine.WorkflowInfo.of(orphan.value, flow))
                    now   <- Clock.now
                    _     <- seedExecution(store, orphanE, orphan, Flow.Status.Running, "")
                    count <- engine.executions.cancelAll()
                    state <- store.getExecution(orphanE)
                yield
                    assert(
                        state.exists(_.cancelRequested),
                        s"the request must reach an execution this engine cannot resolve, got ${state.map(_.cancelRequested)}"
                    )
                    assert(
                        count == 1,
                        s"and it must be counted, since a count that leaves it out reads as nothing being left running, got $count"
                    )
                end for
            }
        }
    }

    // =========================================================================
    // Event history
    // =========================================================================
    "event history" - {

        "records events for completed flow" in {
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

        "completed output is skipped on replay" in {
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

        /** A node whose executor died mid-step is re-run by the executor that reclaims the lease.
          *
          * The durable state below is exactly what a crash leaves behind: a `StepStarted` for a node with no `StepCompleted` after it, on
          * an execution nobody holds. It is built through the store rather than by killing a process, so the test states the condition it
          * is about instead of racing to produce it.
          *
          * Reading that event as "another executor is working on this node" and waiting for a completion is bounded only by the node's own
          * `timeout`, which defaults to `Duration.Infinity`, so the wait never ends: the claim lapses, another executor claims, resumes,
          * waits again, and the execution sits at `Running` forever with nothing reporting a problem.
          */
        "a step in flight when its executor died is re-run on resume" in {
            withEngine { (engine, store, tc) =>
                val flow      = Flow.init("fulfillment").step("charge")(_ => ()).output("receipt")(_ => "paid")
                val deadOne   = Flow.Id.Executor("executor-that-died")
                val leaseSpan = 2.seconds
                for
                    eid <- Sync.defer(Flow.Id.Execution("exec-crashed-mid-step"))
                    now <- Clock.now
                    // The hash comes from the flow itself rather than from a registration, so the execution exists
                    // BEFORE this engine polls for its workflow: that is what lets the dying executor take the lease
                    // first, deterministically, instead of racing the engine's own worker for it.
                    hash = kyo.internal.WorkflowSchema.structuralHash(flow)
                    _ <- seedExecution(store, eid, wf1, Flow.Status.Running, hash)
                    // The executor that is about to die holds the lease, which is the state a crash interrupts, and its
                    // half-finished step is written UNDER that claim because every run-time write is. Its claim then
                    // has to LAPSE before anyone else may touch the execution, so the test waits it out rather than
                    // starting from an execution that is conveniently unheld.
                    claimed <- store.claimReady(Set((wf1, hash)), deadOne, leaseSpan, 10, Duration.Zero)
                    _       <- Kyo.foreachDiscard(claimed)(_.appendEvent(Flow.Event.StepStarted(wf1, eid, "charge", deadOne, now)))
                    held    <- store.getExecution(eid)
                    _       <- engine.register(wf1, flow)
                    _       <- tc.advance(leaseSpan + 1.second)
                    status  <- pump(tc, store, eid, _.isTerminal)
                    receipt <- store.getField[String](eid, "receipt")
                    history <- store.getHistory(eid, Maybe.empty, 0)
                yield
                    assert(claimed.map(_.state.executionId) == Seq(eid), s"the dying executor must have held it, claimed ${claimed.size}")
                    assert(held.exists(_.executor == Present(deadOne)), s"the lease must have been held, state was $held")
                    assert(status == Flow.Status.Completed, s"expected the reclaimed execution to complete, got $status")
                    assert(receipt == Present("paid"), s"expected the flow to have produced its output, got $receipt")
                    val chargeStarts = history.events.count {
                        case Flow.Event.StepStarted(_, _, "charge", _, _) => true
                        case _                                            => false
                    }
                    val chargeCompletions = history.events.count {
                        case Flow.Event.StepCompleted(_, _, "charge", _) => true
                        case _                                           => false
                    }
                    assert(chargeStarts == 2, s"expected the dead executor's start plus the re-run, got $chargeStarts")
                    assert(chargeCompletions == 1, s"expected the re-run to record one completion, got $chargeCompletions")
                end for
            }
        }

        "a step already recorded as completed is not re-run when its start is also present" in {
            withEngine { (engine, store, tc) =>
                var charges = 0
                val flow = Flow.init("fulfillment").step("charge") { _ =>
                    charges += 1
                }.output("receipt")(_ => "paid")
                for
                    eid <- Sync.defer(Flow.Id.Execution("exec-charge-already-done"))
                    now <- Clock.now
                    // The whole history is durable BEFORE the workflow is registered, for the same reason as the leaf
                    // above: the engine's worker polls for registered workflows only, so writing the start and the
                    // completion first is what keeps it from claiming the execution between the two appends and
                    // re-running a step whose completion had not been written yet.
                    hash = kyo.internal.WorkflowSchema.structuralHash(flow)
                    _       <- seedExecution(store, eid, wf1, Flow.Status.Running, hash)
                    claimed <- seedClaim(store, eid, wf1, hash, Flow.Id.Executor("executor-that-died"))
                    _ <- claimed.appendEvent(
                        Flow.Event.StepStarted(wf1, eid, "charge", Flow.Id.Executor("executor-that-died"), now)
                    )
                    _      <- claimed.appendEvent(Flow.Event.StepCompleted(wf1, eid, "charge", now))
                    _      <- claimed.finish(FlowStore.Claimed.Outcome.Suspended(Set.empty))
                    _      <- engine.register(wf1, flow)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(status == Flow.Status.Completed, s"expected the execution to complete, got $status")
                    assert(charges == 0, s"a completed step must stay skipped on replay, ran $charges times")
                end for
            }
        }
    }

    // =========================================================================
    // A step's own failures and its compensation's own effects
    // =========================================================================
    "domain failures" - {

        /** A step declines for a domain reason, and the execution records what kind of failure that was.
          *
          * `FlowDomainException` is the open branch of `FlowException` for exactly this. With nowhere typed to put a domain failure it
          * becomes a panic, and the persisted status keeps a message and nothing else, which makes "how many failed for payment
          * reasons" a LIKE over free text.
          */
        "a domain failure is a failure, and the status names its kind" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("charging").step("charge") { _ =>
                    Abort.fail(FlowEngineTest.ChargeDeclined("ord-200", 54000L))
                }
                for
                    _      <- engine.register(Flow.Id.Workflow("charging"), flow)
                    handle <- engine.workflows.start(Flow.Id.Workflow("charging"))
                    eid = handle.executionId
                    status <- pump(tc, store, eid, _.isTerminal)
                yield status match
                    case Flow.Status.Failed(error, kind) =>
                        assert(error.contains("54000"), s"the message must survive, got $error")
                        assert(kind == Present("ChargeDeclined"), s"the failure's kind must be recorded, got $kind")
                    case other => assert(false, s"expected Failed, got $other")
                end for
            }
        }

        "a panic carries no kind, since nothing declared one" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("panicking").step("fall-over")(_ => throw new RuntimeException("the process fell over"))
                for
                    _      <- engine.register(Flow.Id.Workflow("panicking"), flow)
                    handle <- engine.workflows.start(Flow.Id.Workflow("panicking"))
                    eid = handle.executionId
                    status <- pump(tc, store, eid, _.isTerminal)
                yield status match
                    case Flow.Status.Failed(error, kind) =>
                        assert(error.contains("fell over"), s"the message must survive, got $error")
                        assert(kind == Absent, s"an escaped throwable declares no kind, got $kind")
                    case other => assert(false, s"expected Failed, got $other")
                end for
            }
        }

        /** A compensation uses the effects its forward step used, discharged by the same runner.
          *
          * Pinning the handler's row to `Async & Abort[FlowException]` would stop a compensation that has to undo a write from asking the
          * engine for the resource the write used, and push the flow into carrying a second dependency-injection mechanism.
          */
        "a compensation uses the forward step's own effects" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicRef.init(Chunk.empty[String]).map { audit =>
                        val flow = Flow.init("saga")
                            .outputCompensated("reserve")(_ => Env.use[String](w => s"reserved in $w"))(ctx =>
                                Env.use[String](w => audit.getAndUpdate(_ :+ s"released in $w").unit)
                            )
                            .step("charge")(_ => Abort.fail(FlowEngineTest.ChargeDeclined("ord-1", 1L)))
                        val config = FlowEngine.Config(workerCount = 1, pollTimeout = 100.millis)
                        FlowEngine.init(store, config, flow)([v] =>
                            (c: v < (Env[String] & Abort[FlowEngineTest.ChargeDeclined])) =>
                                Abort.recover[FlowEngineTest.ChargeDeclined](e => Abort.fail(e: FlowException))(
                                    Env.run("warehouse")(c)
                            )).map { engine =>
                            for
                                handle <- engine.workflows.start(Flow.Id.Workflow("saga"))
                                eid = handle.executionId
                                status  <- pump(tc, store, eid, _.isTerminal)
                                entries <- audit.get
                            yield
                                assert(status.isInstanceOf[Flow.Status.Failed], s"expected the saga to fail, got $status")
                                assert(
                                    entries == Chunk("released in warehouse"),
                                    s"the compensation must have run with the runner's effect, got $entries"
                                )
                            end for
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // A store that fails under the worker
    // =========================================================================
    "store failures" - {

        /** A store that fails the claim keeps the engine alive, records the failure, and processes what follows.
          *
          * A failure raised while EXECUTING has an execution to record it against: the engine catches it, writes it on the row, and
          * releases the claim. A failure raised while CLAIMING has no such row, and letting it escape kills every worker fiber with
          * nothing logged and no status changed, leaving a process that answers its health check while no execution ever progresses.
          */
        "a claim that fails does not kill the worker" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicInt.init(3).map { failuresLeft =>
                        val flaky = new FailingClaimStore(store, failuresLeft)
                        val flow  = Flow.init("resilient").output("y")(_ => 42)
                        FlowEngine.init(flaky, FlowEngine.Config(workerCount = 1, pollTimeout = 100.millis), flow).map { engine =>
                            for
                                handle <- engine.workflows.start(Flow.Id.Workflow("resilient"))
                                eid = handle.executionId
                                status <- pump(tc, store, eid, _.isTerminal, maxRounds = 2000)
                                y      <- store.getField[Int](eid, "y")
                                health <- engine.health
                            yield
                                assert(status == Flow.Status.Completed, s"the engine must recover and run it, got $status")
                                assert(y == Present(42), s"the flow must have produced its output, got $y")
                                assert(health.workersAlive == 1, s"the worker must still be polling, got ${health.workersAlive}")
                                assert(health.isHealthy, s"every configured worker must be alive, got $health")
                                assert(health.pollFailures >= 3L, s"the failures must be counted, got ${health.pollFailures}")
                                assert(
                                    health.lastPollFailure.exists(_.contains("claimReady")),
                                    s"the last failure must be reported, got ${health.lastPollFailure}"
                                )
                            end for
                        }
                    }
                }
            }
        }

        /** A store that FAILS the claim, rather than panicking, is handled the same way and keeps its message.
          *
          * This is what the SPI's error channel buys: a store over a database has typed, classified failures, and reporting one by
          * panicking throws the classification away at the boundary.
          */
        "a typed store failure on the claim path is retried and reported" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicInt.init(3).map { failuresLeft =>
                        val flaky = new FailingClaimStore(store, failuresLeft, ClaimFailure.Typed)
                        val flow  = Flow.init("typed-store-failure").output("y")(_ => 42)
                        FlowEngine.init(flaky, FlowEngine.Config(workerCount = 1, pollTimeout = 100.millis), flow).map { engine =>
                            for
                                handle <- engine.workflows.start(Flow.Id.Workflow("typed-store-failure"))
                                eid = handle.executionId
                                status <- pump(tc, store, eid, _.isTerminal, maxRounds = 2000)
                                health <- engine.health
                            yield
                                assert(status == Flow.Status.Completed, s"the engine must recover and run it, got $status")
                                assert(health.workersAlive == 1, s"the worker must still be polling, got ${health.workersAlive}")
                                assert(health.pollFailures >= 3L, s"the failures must be counted, got ${health.pollFailures}")
                                assert(
                                    health.lastPollFailure.exists(_.contains("could not be reached")),
                                    s"the store's own message must survive, got ${health.lastPollFailure}"
                                )
                            end for
                        }
                    }
                }
            }
        }

        "a healthy engine reports every worker alive" in {
            Clock.withTimeControl { _ =>
                FlowStore.initMemory.map { store =>
                    val flow = Flow.init("healthy").output("y")(_ => 1)
                    FlowEngine.init(store, FlowEngine.Config(workerCount = 2, pollTimeout = 100.millis), flow).map { engine =>
                        engine.health.map { health =>
                            assert(health.workersConfigured == 2, s"got ${health.workersConfigured}")
                            assert(health.workersAlive == 2, s"got ${health.workersAlive}")
                            assert(health.pollFailures == 0L, s"got ${health.pollFailures}")
                            assert(health.lastPollFailure == Absent, s"got ${health.lastPollFailure}")
                        }
                    }
                }
            }
        }

        /** A store failure raised while an execution is running is charged to the store's health, never to the execution.
          *
          * A terminal `Failed` carrying the store's kind is the wrong answer, and irreversibly so: terminal status cannot be
          * reverted, so a transient outage recorded as the workflow having failed is a permanent verdict on work that never failed.
          * Under the attempt classification a store failure is `Infra`: it writes nothing about the execution, the row stays exactly
          * as claimable as it was (the retryable-store leaf pins the recovery when the store heals), and the failure surfaces where
          * infrastructure failures belong, on `Health`, with the store's own message intact so an operator still sees what the store
          * said.
          */
        "a store failure while running an execution is charged to health, not to the execution" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    val failing = new FailingFieldStore(store, "receipt")
                    val flow    = Flow.init("unwritable").output("receipt")(_ => "paid")
                    FlowEngine.init(failing, FlowEngine.Config(workerCount = 1, pollTimeout = 100.millis), flow).map { engine =>
                        for
                            handle <- engine.workflows.start(Flow.Id.Workflow("unwritable"))
                            eid = handle.executionId
                            _       <- Kyo.foreachDiscard(1 to 100)(_ => tc.advance(10.millis))
                            state   <- store.getExecution(eid)
                            history <- store.getHistory(eid, Maybe.empty, 0)
                            health  <- engine.health
                        yield
                            assert(
                                state.exists(!_.status.isTerminal),
                                s"a store failure must not terminalise the execution, got ${state.map(_.status)}"
                            )
                            assert(
                                !history.events.exists(_.kind == Flow.EventKind.Failed),
                                s"a store failure must leave no Failed event in the execution's history, got " +
                                    s"${history.events.map(_.kind)}"
                            )
                            assert(
                                health.lastPollFailure.exists(_.contains("deadlock writing 'receipt'")),
                                s"the store's own message must survive on the health surface, got ${health.lastPollFailure}"
                            )
                        end for
                    }
                }
            }
        }

        /** The same fact on a flow that has compensation handlers, where the unwind gives a store failure somewhere wrong to go.
          *
          * `Flow.run` discharges the whole walk with one `Abort.run[Throwable]`, so handing every failure to the unwind would send a
          * store that could not be reached down it: `Compensating(Failure(<the store's own message>))` written through the claim,
          * every handler the walk registered run, the unwind's events appended, and only then a re-raise on the store's channel. The
          * attempt then ends `Infra` and writes nothing more, which is correct and far too late, because the row already says
          * `Compensating`, so the next attempt reads it as a resumed unwind and terminalises the saga `Failed` carrying the store's
          * message, with its refunds issued. One transient blip, one reversed saga.
          *
          * A store failure says nothing about the work, so it is not a verdict and the unwind is not entered at all: no status write,
          * no handler, no event, and the execution stays exactly as claimable as it was for the next poll to try again.
          */
        "a store failure on a flow with handlers starts no unwind and runs no handler" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicInt.init(0).map { compensations =>
                        val failing = new FailingFieldStore(store, "receipt")
                        val flow =
                            Flow.init("compensated-outage")
                                .outputCompensated("reserve")(_ => "held")(_ => compensations.incrementAndGet.unit)
                                .output("receipt")(_ => "paid")
                        FlowEngine.init(failing, FlowEngine.Config(workerCount = 1, pollTimeout = 100.millis), flow).map { engine =>
                            for
                                handle <- engine.workflows.start(Flow.Id.Workflow("compensated-outage"))
                                eid = handle.executionId
                                _       <- Kyo.foreachDiscard(1 to 100)(_ => tc.advance(10.millis))
                                state   <- store.getExecution(eid)
                                history <- store.getHistory(eid, Maybe.empty, 0)
                                ran     <- compensations.get
                                health  <- engine.health
                            yield
                                assert(
                                    state.exists(!_.status.isTerminal),
                                    s"a store failure must not terminalise a compensated execution, got ${state.map(_.status)}"
                                )
                                assert(
                                    !state.exists(_.status match
                                        case Flow.Status.Compensating(_) => true
                                        case _                           => false),
                                    s"nor put it into an unwind nothing asked for, got ${state.map(_.status)}"
                                )
                                assert(
                                    !history.events.exists(e =>
                                        e.kind == Flow.EventKind.CompensationStarted || e.kind == Flow.EventKind.Failed
                                    ),
                                    s"and must record no unwind in its history, got ${history.events.map(_.kind)}"
                                )
                                assert(ran == 0, s"no handler may have run, got $ran")
                                assert(
                                    health.lastPollFailure.exists(_.contains("deadlock writing 'receipt'")),
                                    s"the store's own message must survive on the health surface, got ${health.lastPollFailure}"
                                )
                            end for
                        }
                    }
                }
            }
        }

        /** And when the store heals, the execution finishes the work rather than undoing it.
          *
          * The other half of the leaf above, on the store failure that lasts one attempt. What the next attempt finds decides whether
          * the first one was a verdict: with the unwind never entered, the row is still `Running`, the completed step is read back
          * from its field, the blipped step runs again and the saga COMPLETES. Had the blip written `Compensating` and run the
          * refund, the same execution could only end `Failed`.
          */
        "an execution whose store blip healed completes, with its handler never run" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicInt.init(1).map { blips =>
                        AtomicInt.init(0).map { compensations =>
                            val flaky = new FailingCompletionStore(store, "receipt", blips)
                            val flow =
                                Flow.init("compensated-blip")
                                    .outputCompensated("reserve")(_ => "held")(_ => compensations.incrementAndGet.unit)
                                    .output("receipt")(_ => "paid")
                            // A short lease, for the reason the retryable-store leaf states: an attempt that could not reach the
                            // store leaves its claim to expire rather than releasing it, so nothing re-runs until the lease lapses.
                            val config =
                                FlowEngine.Config(workerCount = 1, lease = 1.second, renewEvery = 500.millis, pollTimeout = 100.millis)
                            FlowEngine.init(flaky, config, flow).map { engine =>
                                for
                                    handle <- engine.workflows.start(Flow.Id.Workflow("compensated-blip"))
                                    eid = handle.executionId
                                    status  <- pump(tc, store, eid, _.isTerminal, 400)
                                    ran     <- compensations.get
                                    history <- store.getHistory(eid, Maybe.empty, 0)
                                yield
                                    assert(
                                        status == Flow.Status.Completed,
                                        s"a blip the next attempt got past must leave the execution Completed, got $status"
                                    )
                                    assert(ran == 0, s"and its compensation must never have run, got $ran")
                                    assert(
                                        !history.events.exists(_.kind == Flow.EventKind.CompensationStarted),
                                        s"nor may an unwind appear in its history, got ${history.events.map(_.kind)}"
                                    )
                                end for
                            }
                        }
                    }
                }
            }
        }

        /** An interrupt raised inside the poll stops the worker rather than being retried as a store failure.
          *
          * The retry arm catches panics as well as failures, which is what keeps a store's defect from stopping the engine for good. An
          * interrupt is not that: `Interrupted` is an ordinary exception, so it arrives on the same arm and would be logged, counted
          * against the store's health, slept off, and tried again, for as long as the store kept raising it. Nothing about the engine
          * would say why. The interrupt reaches this arm as a value rather than at the worker's own safepoint when the store raised it
          * internally, from a race or a timeout of its own.
          */
        "an interrupt raised inside the poll stops the worker" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicInt.init(Int.MaxValue).map { always =>
                        val interrupting = new FailingClaimStore(store, always, ClaimFailure.Interrupt)
                        val flow         = Flow.init("interrupted-poll").output("y")(_ => 42)
                        FlowEngine.init(interrupting, FlowEngine.Config(workerCount = 1, pollTimeout = 100.millis), flow).map { engine =>
                            for
                                _      <- Kyo.foreachDiscard(1 to 50)(_ => tc.advance(10.millis))
                                health <- engine.health
                            yield
                                assert(
                                    health.workersAlive == 0,
                                    s"the interrupted worker must have stopped, ${health.workersAlive} still polling"
                                )
                                assert(
                                    health.pollFailures == 0L,
                                    s"an interrupt is not a store failure and must not be counted as one, got ${health.pollFailures}"
                                )
                                assert(
                                    health.lastPollFailure.isEmpty,
                                    s"an interrupt must not be reported as a store failure, got ${health.lastPollFailure}"
                                )
                            end for
                        }
                    }
                }
            }
        }

        /** An executor whose lease has expired must not go on writing to the execution.
          *
          * A monotonic generation alone does not close this. A generation refuses a SUPERSEDED writer, one whose row somebody else
          * claimed; it says nothing about an executor whose lease ran out while nobody else claimed the row, which still holds the
          * highest generation the row has carried. This is exactly that shape, a single worker with no competitor anywhere, so it is
          * the half of the acceptance rule the token cannot supply: a write is applied only if the claim is active, the token matches,
          * AND the claim has not expired.
          *
          * **The lapse is real rather than reported.** Faking it by making the row's own read answer an expiry in the past proves
          * nothing, because the store judges expiry against its own recorded claim at write time, and there the claim is perfectly
          * valid. So the leaf produces the state instead: the step blocks, the renewals stop arriving, and the clock passes the lease,
          * which is what a starved or partitioned executor looks like from the store's side. The renewal PARKS rather than being
          * refused, because a refusal would interrupt the attempt and the writes would then stop because the work stopped rather than
          * because the store refused them.
          */
        "an executor whose lease has expired does not keep writing" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicBoolean.init(false).map { armed =>
                        AtomicInt.init(0).map { refusals =>
                            Channel.init[Unit](1).map { parked =>
                                val leaseSpan = 5.seconds
                                val expired = new LapsingClaimStore(store, tc, leaseSpan, armed, refusals, parked)({
                                    case _: Flow.Event.StepStarted => true
                                    case _                         => false
                                })
                                val flow = Flow.init("expired-lease").step("reserve")(_ => ()).output("receipt")(_ => "paid")
                                val config = FlowEngine.Config(
                                    workerCount = 1,
                                    lease = leaseSpan,
                                    renewEvery = 1.second,
                                    pollTimeout = 100.millis
                                )
                                for
                                    eid <- Scope.run {
                                        FlowEngine.init(expired, config, flow).map { engine =>
                                            for
                                                handle <- engine.workflows.start(Flow.Id.Workflow("expired-lease"))
                                                eid = handle.executionId
                                                // The refusal is what this leaf is about, so it waits for one to have fired
                                                // rather than for a number of rounds. The engine's scope then closes: an
                                                // executor whose lease lapsed with nothing renewing it is an executor that is
                                                // gone, and one still polling would simply take the row again under a fresh
                                                // generation, which is recovery working rather than the window under test.
                                                refused <- settle(tc)(refusals.get.map(_ > 0))
                                                _ = assert(
                                                    refused,
                                                    "the premise is that the lapsed executor presented a write and was refused"
                                                )
                                            yield eid
                                        }
                                    }
                                    state   <- store.getExecution(eid)
                                    receipt <- store.getField[String](eid, "receipt")
                                yield
                                    assert(
                                        !state.exists(_.status.isTerminal),
                                        s"an executor past its deadline must not carry the execution to a terminal status, got ${state.map(_.status)}"
                                    )
                                    assert(
                                        receipt.isEmpty,
                                        s"an executor past its deadline must not write a step's result, got $receipt"
                                    )
                                end for
                            }
                        }
                    }
                }
            }
        }

        /** An executor that lost its claim writes nothing further about the execution.
          *
          * The leaf below asserts the step STOPPED, which is the presence of a stop rather than the absence of writes, and that holds
          * even while the release path appends into the history of an execution somebody else now owns: a release is a no-op for a
          * non-owner, but the event accompanying it can still go through an unfenced append. Both halves ride the claim, so a test
          * for this area has to assert that the loser wrote nothing rather than that it noticed.
          */
        "an executor that has lost its claim writes nothing more" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicBoolean.init(false).map { started =>
                        AtomicInt.init(1).map { grants =>
                            val losing = new LosingRenewalStore(store, grants)
                            val flow = Flow.init("losing-writes").step[Async]("work") { _ =>
                                started.set(true).andThen(Async.never)
                            }.output("done")(_ => Async.delay(Duration.Zero)("ok"))
                            val config = FlowEngine.Config(
                                workerCount = 1,
                                lease = 5.seconds,
                                renewEvery = 100.millis,
                                pollTimeout = 100.millis
                            )
                            FlowEngine.init(losing, config, flow)([v] => (c: v < Async) => c).map { engine =>
                                for
                                    handle <- engine.workflows.start(Flow.Id.Workflow("losing-writes"))
                                    eid = handle.executionId
                                    _        <- Kyo.foreachDiscard(1 to 200)(_ => tc.advance(10.millis))
                                    didStart <- started.get
                                    history  <- store.getHistory(eid, Maybe.empty, 0)
                                yield
                                    assert(didStart, "the step must have begun, or this leaf proves nothing")
                                    assert(
                                        !history.events.exists(_.kind == Flow.EventKind.ExecutionReleased),
                                        s"an executor that lost the claim must not write a release into the history of an " +
                                            s"execution it no longer owns, got ${history.events.map(_.kind)}"
                                    )
                                end for
                            }
                        }
                    }
                }
            }
        }

        /** An attempt that ended without a verdict leaves the waits it recorded exactly where they are.
          *
          * The release transition retires every wait the ending does not name, which is right for an attempt that finished: what it
          * ended parked on is a trustworthy statement of what the execution waits for, and everything else is a race loser or an
          * abandoned branch. An interrupted attempt states nothing, so retiring against it deletes rows the execution is still in,
          * and the next attempt inherits a ledger that says the execution waits for less than it does. The damage is not a lost
          * claim, it is a half-written ledger blessed as a finished one.
          *
          * **The retired rows are what makes a wrong release visible at all.** The claim itself is not: the moment a row is freed
          * this engine's own poll re-claims it, so a release and no release look the same in the row a test can read afterwards. The
          * rows do not come back, because the re-claim's confirmation renewal is refused too and no replay ever runs. The race is
          * what puts a recorded wait and a live attempt in the execution at the same time, which is the only shape in which an
          * ending can be interrupted while the ledger is non-empty.
          */
        "an ending with no verdict keeps the waits the attempt recorded" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicInt.init(1).map { grants =>
                        val losing  = new LosingRenewalStore(store, grants)
                        val wfId    = Flow.Id.Workflow("unfinished-ledger")
                        val parking = Flow.init("unfinished-ledger").input[String]("approval").output("answer")(ctx => ctx.approval)
                        val working = Flow.init("unfinished-ledger").step[Async]("work")(_ => Async.never)
                            .output("answer")(_ => "worked")
                        val flow = Flow.race(parking, working)
                        val config =
                            FlowEngine.Config(workerCount = 1, lease = 5.seconds, renewEvery = 100.millis, pollTimeout = 100.millis)
                        FlowEngine.init(losing, config, flow).map { engine =>
                            for
                                handle <- engine.workflows.start(wfId)
                                eid = handle.executionId
                                parked  <- settle(tc)(store.getExecution(eid).map(_.exists(_.waits.contains("approval"))))
                                refused <- settle(tc)(grants.get.map(_ <= 0))
                                _       <- Kyo.foreachDiscard(1 to 100)(_ => tc.advance(10.millis))
                                state   <- store.getExecution(eid)
                            yield
                                assert(
                                    parked,
                                    "the premise is that one branch recorded its wait while the other was still running"
                                )
                                assert(refused, "the premise is that the executor then lost its claim mid-attempt")
                                assert(
                                    state.exists(_.waits.contains("approval")),
                                    s"an attempt that reached no verdict has nothing to bless, so the waits it recorded must " +
                                        s"survive it, got ${state.map(_.waits)}"
                                )
                            end for
                        }
                    }
                }
            }
        }

        /** An executor that has lost its claim stops the work it is doing, rather than running on outside its lease.
          *
          * The lease is what authorises an executor to work on an execution, and the renewal loop is what keeps it. When a renewal is
          * refused the claim is already someone else's, so everything this executor does from that moment is work on an execution it
          * does not hold. A renewal loop that merely exits and leaves the step running has noticed without acting.
          *
          * **Refusing the writes is not enough on its own, which is why the engine has to stop the work.** The store judges what an
          * attempt presents to it, and a step whose `timeout` defaults to `Duration.Infinity` presents nothing ever again: it holds a
          * worker and the memory behind it for as long as the process lives, and no refusal is reached to end it.
          *
          * The step here blocks forever and records its own interruption, so the assertion is that the engine stopped it, not that the
          * engine noticed.
          */
        "an executor that has lost its claim stops the step it is running" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicBoolean.init(false).map { started =>
                        AtomicBoolean.init(false).map { stopped =>
                            AtomicInt.init(1).map { grants =>
                                val losing = new LosingRenewalStore(store, grants)
                                val flow = Flow.init("long-step").step[Async]("work") { _ =>
                                    Sync.ensure(stopped.set(true))(started.set(true).andThen(Async.never))
                                }.output("done")(_ => Async.delay(Duration.Zero)("ok"))
                                val config =
                                    FlowEngine.Config(workerCount = 1, lease = 5.seconds, renewEvery = 100.millis, pollTimeout = 100.millis)
                                FlowEngine.init(losing, config, flow)([v] => (c: v < Async) => c).map { engine =>
                                    for
                                        _        <- engine.workflows.start(Flow.Id.Workflow("long-step"))
                                        _        <- Kyo.foreachDiscard(1 to 200)(_ => tc.advance(10.millis))
                                        didStart <- started.get
                                        didStop  <- stopped.get
                                    yield
                                        assert(didStart, "the step must have begun, or this leaf proves nothing")
                                        assert(didStop, "an executor whose renewal was refused must stop the step it is running")
                                    end for
                                }
                            }
                        }
                    }
                }
            }
        }

        /** An interrupt the store raises while an attempt's outcome is being written is not a store failure.
          *
          * The recovery around that write is `Abort.run[Throwable]`, wide on purpose: a store is third-party code and is free to
          * panic even though the SPI gives it somewhere typed to put a failure. An `Interrupted` is an ordinary throwable, so it
          * arrives on that same arm, and the three other broad recoveries in the engine (the worker loop, the renewal loop, the
          * supervise wrapper) each pick it out and re-raise it rather than counting it. Without the same arm here, an interrupt
          * raised inside a store call, by a race or a timeout the store ran internally, is charged to the store's health and logged
          * as a store that could not be reached: the engine blames the store for having been stopped.
          *
          * The health charge is the observable that separates the two, so it is what this leaf reads. What follows the classification
          * is the same either way in the moment: the write did not land, so the execution stays non-terminal and its claim lapses.
          */
        "an interrupt raised inside the outcome write is not charged to the store" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicInt.init(1).map { interrupts =>
                        val interrupting = new InterruptingOutcomeStore(store, interrupts)
                        val wfId         = Flow.Id.Workflow("interrupt-vs-outcome")
                        val flow         = Flow.init("interrupt-vs-outcome").output("receipt")(_ => "paid")
                        // The lease outlasts the whole run, so nothing reclaims the row while the health signal is being
                        // watched and the only thing that can move the counter is the classification under test.
                        val config = FlowEngine.Config(
                            workerCount = 1,
                            lease = 30.seconds,
                            renewEvery = 5.seconds,
                            pollTimeout = 100.millis
                        )
                        FlowEngine.init(interrupting, config, flow).map { engine =>
                            for
                                handle <- engine.workflows.start(wfId)
                                eid = handle.executionId
                                raised <- settle(tc, step = 100.millis, maxRounds = 40)(interrupts.get.map(_ <= 0))
                                // Bounded room for a charged interrupt to show itself: under the defect the counter turns
                                // over the moment the write is recovered, and with the arm it never moves at all.
                                charged <- settle(tc, step = 100.millis, maxRounds = 20)(engine.health.map(_.pollFailures > 0))
                                health  <- engine.health
                                state   <- store.getExecution(eid)
                                history <- store.getHistory(eid, Maybe.empty, 0)
                            yield
                                assert(raised, "the premise is that the store raised an interrupt from the outcome write")
                                assert(
                                    !charged && health.pollFailures == 0L,
                                    s"an interrupt is not a store failure and must not be counted as one, got " +
                                        s"${health.pollFailures} recorded failures"
                                )
                                assert(
                                    health.lastPollFailure.isEmpty,
                                    s"an interrupt must not be reported as a store failure, got ${health.lastPollFailure}"
                                )
                                assert(
                                    !history.events.exists(_.kind == Flow.EventKind.Failed),
                                    s"an interrupt is not a verdict on the work, got ${history.events.map(_.kind)}"
                                )
                                assert(
                                    !state.exists(_.status.isTerminal),
                                    s"an interrupt stops this attempt and writes nothing, so the execution is left for its " +
                                        s"claim to lapse rather than carried to a terminal status, got ${state.map(_.status)}"
                                )
                            end for
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // Scheduled loop exhaustion
    // =========================================================================
    "scheduled loop exhaustion" - {

        /** A scheduled loop whose schedule runs out fails the execution, naming the loop.
          *
          * A flow declaring `N ~ V` promises a `V`, and a `loopOn` whose schedule is spent has none: the last state it carried is
          * the loop's state type and the loop's name is typed for its value, so the two coincide only by accident. Nothing is
          * written under the loop's name and the execution reaches a terminal `Failed` that says which loop ran out.
          *
          * Writing anything under the loop's name on the exhaustion path is worse than useless. A `()` written under the loop
          * VALUE's own schema raises a cast error inside the field write, and where that error goes splits the platforms in two: the
          * JVM and Native catch it and record a failed execution with `BoxedUnit cannot be cast to Integer`, while JS and Wasm let it
          * escape and the host exits with code 1, taking `FlowEngineTest` and `MemoryFlowStoreTest` down with it so both report
          * nothing at all.
          *
          * The KIND is the load-bearing assertion, and it is spelled exactly as the sibling timeout leaf spells it. Running a
          * schedule out is a verdict the engine reaches about the flow, so it is raised as a declared failure and arrives carrying
          * its own class name, which is what makes "how many executions ran their loop out" a query over a field rather than a
          * search over message text. Raised as a panic instead, it would arrive with the kind stripped and this leaf would fail,
          * which is the point of asserting the kind rather than only the status.
          */
        "a scheduled loop that exhausts its schedule records a defined outcome" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("exhausted")
                    .loopOn("count", Schedule.fixed(100.millis).repeat(2)) { ctx =>
                        Loop.continue[Int]
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    done  <- settle(tc, maxRounds = 200)(store.getExecution(eid).map(_.exists(_.status.isTerminal)))
                    count <- store.getField[Int](eid, "count")
                    state <- store.getExecution(eid)
                yield
                    assert(done, s"an exhausted loop must reach a terminal status, got ${state.map(_.status)}")
                    val failure = state.map(_.status).collect { case f: Flow.Status.Failed => f }
                    assert(
                        failure.exists(f => f.error.contains("count") && f.error.contains("schedule")),
                        s"an exhausted schedule must fail the execution and name the loop that ran out, got ${state.map(_.status)}"
                    )
                    val kindOf = state.map(_.status) match
                        case Present(Flow.Status.Failed(_, kind)) => kind
                        case other                                => Maybe(s"not-failed: $other")
                    assert(
                        kindOf == Maybe("FlowLoopExhaustedException"),
                        s"exhaustion is a declared failure of the flow, not an escaped one, so it must terminalise as Failed " +
                            s"carrying its own kind, got status ${state.map(_.status)}"
                    )
                    assert(
                        count.isEmpty,
                        s"nothing is written under the loop's name when it produces no value, got $count"
                    )
                end for
            }
        }

        /** A loop that ran its schedule out is reported as the failed node, by name.
          *
          * Exhaustion is a verdict the ENGINE reaches rather than one a step threw, and it reaches the store through the interpreter's
          * failure verb. That verb has to record the node: with nothing recorded, the execution terminalises `Failed` carrying the
          * message and the kind while every node on the progress view reads pending, so the surface cannot say which node ended the
          * run. The record is written under the loop's own durable name before the failure is raised.
          *
          * The node's message is asserted to be the execution's own, rather than a string spelled twice: one failure, one wording,
          * whichever surface a reader is looking at.
          */
        "a loop that exhausted its schedule is the node reported failed" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("exhausted-node")
                    .loopOn("count", Schedule.fixed(100.millis).repeat(2)) { ctx =>
                        Loop.continue[Int]
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    done   <- settle(tc, maxRounds = 200)(store.getExecution(eid).map(_.exists(_.status.isTerminal)))
                    state  <- store.getExecution(eid)
                    detail <- engine.executions.describe(eid)
                yield
                    assert(done, s"the premise is that the loop ran out and the execution is over, got ${state.map(_.status)}")
                    val error = state.map(_.status) match
                        case Present(Flow.Status.Failed(message, _)) => Maybe(message)
                        case _                                       => Maybe.empty
                    assert(error.isDefined, s"the premise is a failed execution, got ${state.map(_.status)}")
                    assert(
                        detail.progress.nodeByName("count").map(_.status) ==
                            Maybe(FlowEngine.Progress.NodeStatus.Failed(error.get)),
                        s"the loop that ran out must be the node reported failed, carrying the execution's own message, got " +
                            s"${detail.progress.nodeByName("count").map(_.status)}"
                    )
                end for
            }
        }

        /** A scheduled loop whose BODY failed reports the loop, not the iteration nobody can see.
          *
          * An iteration records under the loop's name with its number appended, so the failure of iteration 0 of `tally` lands under
          * `tally#0`, and no node on the progress view is called that: the iterations are the loop's internal bookkeeping. Attributing
          * the record to the parent is the completion side's asymmetry the other way up. One iteration completing does not complete
          * the loop, and one iteration failing does fail it, so the parent is the honest owner of the failure and the only node a
          * reader can act on.
          */
        "a scheduled loop whose body failed reports the loop as the failed node" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("loop-body-fails")
                    .loopOn("tally", Schedule.fixed(100.millis).repeat(5), 0) { (state: Int, ctx) =>
                        throw new RuntimeException("tally broke"); Loop.done(state)
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    done   <- settle(tc, maxRounds = 200)(store.getExecution(eid).map(_.exists(_.status.isTerminal)))
                    state  <- store.getExecution(eid)
                    detail <- engine.executions.describe(eid)
                yield
                    assert(done, s"the premise is that the failing body ended the execution, got ${state.map(_.status)}")
                    val error = state.map(_.status) match
                        case Present(Flow.Status.Failed(message, _)) => Maybe(message)
                        case _                                       => Maybe.empty
                    assert(error.isDefined, s"the premise is a failed execution, got ${state.map(_.status)}")
                    assert(
                        detail.progress.nodeByName("tally").map(_.status) ==
                            Maybe(FlowEngine.Progress.NodeStatus.Failed(error.get)),
                        s"an iteration's failure belongs to the loop, which is the node a reader has, got " +
                            s"${detail.progress.nodeByName("tally").map(_.status)}"
                    )
                end for
            }
        }
    }

    /** How a [[FailingClaimStore]] reports its failure.
      *
      * `Typed` is the SPI's own error channel, which is what a store over a database uses. `Panic` is what a store with nothing typed
      * to report reaches for, and what a decode failure does. `Interrupt` is neither: it is the caller being told to stop.
      */
    private enum ClaimFailure derives CanEqual:
        case Panic, Typed, Interrupt

    /** A store whose `claimReady` fails the first `remaining` times it is called, in the manner `mode` names. */
    private class FailingClaimStore(underlying: FlowStore, remaining: AtomicInt, mode: ClaimFailure = ClaimFailure.Panic)
        extends DelegatingStore(underlying):
        override def claimReady(
            served: Set[(Flow.Id.Workflow, String)],
            executorId: Flow.Id.Executor,
            lease: Duration,
            limit: Int,
            timeout: Duration
        )(using Frame): Seq[FlowStore.Claimed] < (Async & Abort[FlowStoreException]) =
            remaining.getAndDecrement.map { left =>
                if left <= 0 then super.claimReady(served, executorId, lease, limit, timeout)
                else
                    mode match
                        case ClaimFailure.Typed     => Abort.fail(FlowEngineTest.StoreUnavailable("connection reset by peer"))
                        case ClaimFailure.Interrupt => Abort.panic(Interrupted(summon[Frame]))
                        case ClaimFailure.Panic     => Abort.panic(new RuntimeException("claimReady: the store could not decode a row"))
            }
    end FailingClaimStore

    /** A store that raises an interrupt from the write recording a completed attempt's outcome, once, and takes every other call.
      *
      * The shape a store has when something it runs internally, a race or a timeout of its own, is interrupted while it is serving a
      * call. `Interrupted` is an ordinary throwable, so it reaches the caller's broad recovery on the same arm as any other panic and
      * only an explicit classification tells the two apart.
      */
    private class InterruptingOutcomeStore(underlying: FlowStore, remaining: AtomicInt) extends DelegatingStore(underlying):
        override protected def wrapClaimed(claimed: FlowStore.Claimed)(using Frame): FlowStore.Claimed =
            new DelegatingClaimed(claimed):
                override def finish(outcome: FlowStore.Claimed.Outcome)(using
                    Frame
                ): FlowStore.StatusOutcome < (Async & Abort[FlowStoreException]) =
                    outcome match
                        case FlowStore.Claimed.Outcome.Terminal(_, _: Flow.Event.Completed) =>
                            remaining.getAndDecrement.map { left =>
                                if left > 0 then Abort.panic(Interrupted(summon[Frame]))
                                else super.finish(outcome)
                            }
                        case _ => super.finish(outcome)
    end InterruptingOutcomeStore

    /** A store that fails every write recording the named node's progress, on the SPI's own channel and marked retryable. */
    private class FailingFieldStore(underlying: FlowStore, field: String) extends DelegatingStore(underlying):
        override protected def wrapClaimed(claimed: FlowStore.Claimed)(using Frame): FlowStore.Claimed =
            new DelegatingClaimed(claimed):
                override def recordProgress[V](path: String, value: Maybe[V], event: Flow.Event)(using
                    Tag[V],
                    Schema[V],
                    Frame
                ): FlowStore.ProgressOutcome < (Async & Abort[FlowStoreException]) =
                    if path == field then Abort.fail(FlowEngineTest.StoreUnavailable(s"deadlock writing '$path'"))
                    else super.recordProgress[V](path, value, event)
    end FailingFieldStore

    /** A store that fails the transition recording the named node's completion, the first `remaining` times.
      *
      * One transition writes the field, the event and the clearing, so there is no window between them for a decorator to sit in. What
      * this stages is that transition failing whole, and the leaf below asserts that the two halves agree either way.
      */
    private class FailingCompletionStore(underlying: FlowStore, node: String, remaining: AtomicInt) extends DelegatingStore(underlying):
        override protected def wrapClaimed(claimed: FlowStore.Claimed)(using Frame): FlowStore.Claimed =
            new DelegatingClaimed(claimed):
                override def recordProgress[V](path: String, value: Maybe[V], event: Flow.Event)(using
                    Tag[V],
                    Schema[V],
                    Frame
                ): FlowStore.ProgressOutcome < (Async & Abort[FlowStoreException]) =
                    event match
                        case Flow.Event.StepCompleted(_, _, name, _) if name == node =>
                            remaining.getAndDecrement.map { left =>
                                if left > 0 then Abort.fail(FlowEngineTest.StoreUnavailable(s"blip completing '$name'"))
                                else super.recordProgress[V](path, value, event)
                            }
                        case _ => super.recordProgress[V](path, value, event)
    end FailingCompletionStore

    /** A store that refuses every append of a `StepTimedOut` event, on the SPI's own channel and marked retryable.
      *
      * The append sits inside the retry helper's catch, so refusing it is how the leaf below observes whether a store failure is
      * routed to the engine as infra or consumed by the step's own schedule.
      */
    private class FailingTimeoutEventStore(underlying: FlowStore, refusals: AtomicInt) extends DelegatingStore(underlying):
        override protected def wrapClaimed(claimed: FlowStore.Claimed)(using Frame): FlowStore.Claimed =
            new DelegatingClaimed(claimed):
                override def appendEvent(event: Flow.Event)(using
                    Frame
                ): FlowStore.WriteOutcome < (Async & Abort[FlowStoreException]) =
                    event match
                        case _: Flow.Event.StepTimedOut =>
                            refusals.incrementAndGet.andThen(
                                Abort.fail(FlowEngineTest.StoreUnavailable("blip recording the timeout"))
                            )
                        case _ => super.appendEvent(event)
    end FailingTimeoutEventStore

    /** A store that holds the first two readers of one execution id until both have arrived, so both see the same snapshot.
      *
      * A check-then-act race cannot be measured by running it and hoping: the two callers serialise more often than not, and a green
      * run says nothing about the interleaving that matters. This forces the interleaving instead of sampling it. Only the first two
      * reads of the target id rendezvous; every other read, including the engine's own, passes straight through.
      */
    private class RendezvousStore(
        underlying: FlowStore,
        target: Flow.Id.Execution,
        barrier: Latch,
        remaining: AtomicInt,
        arrived: AtomicInt,
        absent: AtomicInt
    ) extends DelegatingStore(underlying):
        override def createExecutionIfAbsent(
            executionId: Flow.Id.Execution,
            status: Flow.Status,
            event: Flow.Event,
            hash: String,
            fields: Dict[String, FlowStore.FieldData]
        )(using Frame): Boolean < (Async & Abort[FlowStoreException]) =
            if executionId != target then super.createExecutionIfAbsent(executionId, status, event, hash, fields)
            else
                remaining.getAndDecrement.map { left =>
                    if left > 0 then
                        // Both callers are held on the doorstep of the deciding write, after each has looked and found nothing.
                        // The probe is the decorator's own, not the engine's: the engine does not look before it creates, which is
                        // the whole point, so the interleaving has to be forced at the verb that decides rather than at a read
                        // nobody makes.
                        super.getExecution(executionId).map { seen =>
                            Kyo.when(seen.isEmpty)(absent.incrementAndGet)
                                .andThen(barrier.release)
                                .andThen(barrier.await)
                                .andThen(arrived.incrementAndGet)
                                .andThen(super.createExecutionIfAbsent(executionId, status, event, hash, fields))
                        }
                    else super.createExecutionIfAbsent(executionId, status, event, hash, fields)
                }
    end RendezvousStore

    /** A store whose claim genuinely lapses the moment a chosen event is written, and whose renewals stop arriving from then on.
      *
      * **There is no read to lie to.** Authority is the generation a write presents and the deadline the store recorded, not anything
      * a caller reads back, so a decorator cannot stage a lost claim by reporting somebody else as the owner. The only way to stage one
      * is to lose one: at the trigger the clock is advanced past the lease, and every write the executor issues after that is refused
      * by the store's own rule rather than by anything this decorator decides.
      *
      * The renewal PARKS rather than failing, which is the one shape that leaves a stale executor still running: a refusal would
      * interrupt the attempt at its next safepoint, and the writes under test would then stop because the work stopped rather than
      * because the store refused them.
      *
      * `refusals` counts the writes the underlying store turned away, so a leaf can wait for the refusal to have actually fired
      * instead of waiting a number of rounds and hoping.
      */
    private class LapsingClaimStore(
        underlying: FlowStore,
        tc: Clock.TimeControl,
        lease: Duration,
        armed: AtomicBoolean,
        refusals: AtomicInt,
        parked: Channel[Unit]
    )(trigger: Flow.Event => Boolean) extends DelegatingStore(underlying):
        override protected def wrapClaimed(claimed: FlowStore.Claimed)(using Frame): FlowStore.Claimed =
            new DelegatingClaimed(claimed):
                private def counted(outcome: FlowStore.WriteOutcome): FlowStore.WriteOutcome < Sync =
                    outcome match
                        case FlowStore.WriteOutcome.ClaimLost => refusals.incrementAndGet.andThen(outcome)
                        case _                                => outcome

                override def appendEvent(event: Flow.Event)(using
                    Frame
                ): FlowStore.WriteOutcome < (Async & Abort[FlowStoreException]) =
                    super.appendEvent(event).map(counted).map { outcome =>
                        // The trigger event itself lands, and the lease lapses behind it, so what the leaf observes is every
                        // write the executor makes AFTER the moment its claim died.
                        if trigger(event) then armed.set(true).andThen(tc.advance(lease + 1.second)).andThen(outcome)
                        else outcome
                    }

                override def recordWait(path: String, wake: Flow.Wake, event: Flow.Event)(using
                    Frame
                ): FlowStore.WriteOutcome < (Async & Abort[FlowStoreException]) =
                    super.recordWait(path, wake, event).map(counted)

                override def recordProgress[V](path: String, value: Maybe[V], event: Flow.Event)(using
                    Tag[V],
                    Schema[V],
                    Frame
                ): FlowStore.ProgressOutcome < (Async & Abort[FlowStoreException]) =
                    super.recordProgress[V](path, value, event).map { outcome =>
                        outcome match
                            case FlowStore.ProgressOutcome.ClaimLost => refusals.incrementAndGet.andThen(outcome)
                            case _                                   => outcome
                    }

                override def renewClaim(renewLease: Duration)(using Frame): Boolean < (Async & Abort[FlowStoreException]) =
                    armed.get.map { a =>
                        if a then Abort.run[Closed](parked.take).andThen(super.renewClaim(renewLease))
                        else super.renewClaim(renewLease)
                    }
    end LapsingClaimStore

    /** A store whose field read fails the first `remaining` times, on the SPI's channel and marked retryable.
      *
      * The marker exists so a store can tell the engine "this one is worth trying again", which is the distinction between a database
      * that was briefly unreachable and a row that will never decode.
      */
    private class TransientFieldReadStore(underlying: FlowStore, remaining: AtomicInt) extends DelegatingStore(underlying):
        override def getAllFields(executionId: Flow.Id.Execution)(using
            Frame
        ): Dict[String, FlowStore.FieldData] < (Async & Abort[FlowStoreException]) =
            remaining.getAndDecrement.map { left =>
                if left > 0 then Abort.fail(FlowEngineTest.StoreUnavailable("deadlock reading fields"))
                else super.getAllFields(executionId)
            }
    end TransientFieldReadStore

    /** A store that stores a field but cannot read it back, the way one holding a value that no longer decodes behaves.
      *
      * The store's readiness test for a waiting execution is field PRESENCE; the interpreter's read is a typed decode that answers
      * `Absent` for a tag mismatch or a decode failure just as it does for a missing field. The two disagree exactly here.
      */
    private class UndecodableFieldStore(underlying: FlowStore, field: String) extends DelegatingStore(underlying):
        override def getField[V: Tag: Schema](executionId: Flow.Id.Execution, name: String)(using
            Frame
        ): Maybe[V] < (Async & Abort[FlowStoreException]) =
            if name == field then Maybe.empty
            else super.getField[V](executionId, name)
    end UndecodableFieldStore

    /** A store whose second `renewClaim` fails on the SPI's own channel, and which takes every other call.
      *
      * The engine renews once before it starts executing, so failing the second call is failing the renewal loop's first attempt,
      * which is the one that guards a live execution.
      */
    private class FlakyRenewalStore(underlying: FlowStore, calls: AtomicInt) extends DelegatingStore(underlying):
        override protected def wrapClaimed(claimed: FlowStore.Claimed)(using Frame): FlowStore.Claimed =
            new DelegatingClaimed(claimed):
                override def renewClaim(lease: Duration)(using Frame): Boolean < (Async & Abort[FlowStoreException]) =
                    calls.incrementAndGet.map { n =>
                        if n == 2 then Abort.fail(FlowEngineTest.StoreUnavailable("connection reset while renewing"))
                        else super.renewClaim(lease)
                    }
    end FlakyRenewalStore

    /** A store whose `renewClaim`, once armed, parks without answering.
      *
      * The one instrument that produces a stale RUNNING executor. A renewal FAILURE does not: the renewal loop survives blips,
      * retries, gets a refusal once the row is taken, and the refusal interrupts the lapsed executor at its next safepoint, so an
      * executor built that way never comes back. A PARKED renewal is different in kind: the fiber is suspended
      * awaiting the store's answer, so it neither renews nor refuses, no interrupt is ever issued, the lease lapses store-side,
      * and the executor keeps running under a claim it no longer holds.
      */
    private class ParkedRenewalStore(underlying: FlowStore, armed: AtomicBoolean, parked: Channel[Unit])
        extends DelegatingStore(underlying):
        override protected def wrapClaimed(claimed: FlowStore.Claimed)(using Frame): FlowStore.Claimed =
            new DelegatingClaimed(claimed):
                override def renewClaim(lease: Duration)(using Frame): Boolean < (Async & Abort[FlowStoreException]) =
                    armed.get.map { a =>
                        if a then Abort.run[Closed](parked.take).andThen(super.renewClaim(lease))
                        else super.renewClaim(lease)
                    }
    end ParkedRenewalStore

    /** A store that panics when the resume event is written, and takes every other call.
      *
      * The site matters. A panic raised inside the handler that records an execution's failure is caught and recorded; this one is
      * raised on the way IN to that handler, which is the window the engine has nothing watching.
      */
    private class PanickingResumeStore(underlying: FlowStore) extends DelegatingStore(underlying):
        override protected def wrapClaimed(claimed: FlowStore.Claimed)(using Frame): FlowStore.Claimed =
            new DelegatingClaimed(claimed):
                override def appendEvent(event: Flow.Event)(using
                    Frame
                ): FlowStore.WriteOutcome < (Async & Abort[FlowStoreException]) =
                    event match
                        case _: Flow.Event.ExecutionResumed =>
                            Abort.panic(new RuntimeException("appendEvent: the store could not encode the event"))
                        case _ => super.appendEvent(event)
    end PanickingResumeStore

    /** A store that refuses the write recording a wait the first `remaining` times, and takes every other write.
      *
      * The shape a database blip has: one statement fails, the next identical one succeeds.
      */
    private class FailingWaitStore(underlying: FlowStore, remaining: AtomicInt) extends DelegatingStore(underlying):
        override protected def wrapClaimed(claimed: FlowStore.Claimed)(using Frame): FlowStore.Claimed =
            new DelegatingClaimed(claimed):
                override def recordWait(path: String, wake: Flow.Wake, event: Flow.Event)(using
                    Frame
                ): FlowStore.WriteOutcome < (Async & Abort[FlowStoreException]) =
                    remaining.getAndDecrement.map { left =>
                        if left <= 0 then super.recordWait(path, wake, event)
                        else Abort.fail(FlowEngineTest.StoreUnavailable("deadlock recording the wait"))
                    }
    end FailingWaitStore

    /** A store whose SECOND `recordWait` parks without answering, and which takes every other call.
      *
      * The instrument for an engine that goes away BETWEEN two parking branches. A failure at the second call cannot stage it: the
      * attempt then ends with a verdict of its own, and the window under test is the one where no verdict is ever reached. Parking is
      * different in kind, exactly as it is for `ParkedRenewalStore`: the branch is suspended inside the store with its row unwritten,
      * so the attempt is still live when the engine's scope closes and the close is what ends it, which is the shutdown this stages.
      *
      * Only the second call parks, so the recovery that follows records both branches normally: an instrument that kept parking would
      * stage the shutdown and then prevent the replay it exists to observe.
      */
    private class ParkedWaitStore(underlying: FlowStore, calls: AtomicInt, parked: Channel[Unit]) extends DelegatingStore(underlying):
        override protected def wrapClaimed(claimed: FlowStore.Claimed)(using Frame): FlowStore.Claimed =
            new DelegatingClaimed(claimed):
                override def recordWait(path: String, wake: Flow.Wake, event: Flow.Event)(using
                    Frame
                ): FlowStore.WriteOutcome < (Async & Abort[FlowStoreException]) =
                    calls.incrementAndGet.map { n =>
                        if n == 2 then Abort.run[Closed](parked.take).andThen(super.recordWait(path, wake, event))
                        else super.recordWait(path, wake, event)
                    }
    end ParkedWaitStore

    /** A store that lets one claimed batch outlive its own lease before the caller can renew it.
      *
      * The engine renews the moment `claimReady` returns, so a conforming store cannot refuse that renewal as superseded and cannot
      * refuse it as expired either, unless time really has passed in between. This decorator makes it pass, once, by advancing the
      * virtual clock past the lease on the first non-empty batch. Every answer the store gives is still computed from the real row
      * against the real clock, which is what separates this from `LosingRenewalStore`.
      */
    private class LateFirstRenewalStore(
        underlying: FlowStore,
        tc: Clock.TimeControl,
        lease: Duration,
        skews: AtomicInt,
        refusals: AtomicInt,
        polls: AtomicInt,
        firstClaimAt: AtomicRef[Maybe[Instant]]
    ) extends DelegatingStore(underlying):
        override def claimReady(
            served: Set[(Flow.Id.Workflow, String)],
            executorId: Flow.Id.Executor,
            claimLease: Duration,
            limit: Int,
            timeout: Duration
        )(using Frame): Seq[FlowStore.Claimed] < (Async & Abort[FlowStoreException]) =
            polls.incrementAndGet.andThen {
                super.claimReady(served, executorId, claimLease, limit, timeout).map { batch =>
                    Kyo.when(batch.nonEmpty) {
                        skews.getAndIncrement.map { n =>
                            Kyo.when(n == 0) {
                                Clock.nowWith(now => firstClaimAt.set(Present(now))).andThen(tc.advance(lease + 1.second))
                            }
                        }
                    }.andThen(batch)
                }
            }

        override protected def wrapClaimed(claimed: FlowStore.Claimed)(using Frame): FlowStore.Claimed =
            new DelegatingClaimed(claimed):
                override def renewClaim(renewLease: Duration)(using Frame): Boolean < (Async & Abort[FlowStoreException]) =
                    super.renewClaim(renewLease).map { ok =>
                        if ok then true else refusals.incrementAndGet.andThen(false)
                    }
    end LateFirstRenewalStore

    /** A store that lets the execution finish under the caller's delivery, before that delivery reaches the store.
      *
      * Forces the window a delivery leaves open between checking the status and writing the field: the caller's check has already run
      * and seen a live execution, and by the time its write lands the execution is over.
      *
      * **Every answer this store gives is truthful, which is what separates it from a decorator that lies about a row.** It stages
      * the completion by letting it HAPPEN: the value goes in, the parked execution wakes, consumes it and finishes, and only then does
      * the caller's own call reach the store. A terminal status cannot be conjured from outside, because writing one needs a claim, and
      * that is the rule this leaf exists beside rather than around.
      */
    private class CompleteOnDeliveryStore(underlying: FlowStore, field: String, completions: AtomicInt, tc: Clock.TimeControl)
        extends DelegatingStore(underlying):
        override def signal[V: Tag: Schema](executionId: Flow.Id.Execution, name: String, value: V, event: Flow.Event)(using
            Frame
        ): FlowStore.SignalOutcome < (Async & Abort[FlowStoreException]) =
            def untilTerminal(remaining: Int): Unit < (Async & Abort[FlowStoreException]) =
                underlying.getExecution(executionId).map {
                    case Present(state) if state.status.isTerminal => ()
                    case _ => if remaining <= 0 then () else tc.advance(10.millis).andThen(untilTerminal(remaining - 1))
                }
            completions.get.map { seen =>
                Kyo.when(name == field && seen == 0) {
                    completions.incrementAndGet
                        .andThen(super.signal[V](executionId, name, value, event))
                        .andThen(untilTerminal(500))
                }.andThen(super.signal[V](executionId, name, value, event))
            }
        end signal
    end CompleteOnDeliveryStore

    /** A store that grants the claim once and refuses every renewal after it, which is what an executor whose lease was taken sees.
      *
      * The first `renewClaim` has to succeed because the engine calls it once before it starts executing; refusing that one would mean
      * the execution never begins and the test would prove nothing.
      *
      * **Valid only for what the ENGINE does with a refusal, never for what the STORE then holds.** The answer comes from a counter,
      * not from the claim row, so the underlying row stays claimed by a live, unexpired lease no matter what this store returns: a
      * conforming store does not refuse a renewal it has just granted. Assertions about the engine's reaction (it stops the step, it
      * writes nothing more, it does not terminalise the execution) are sound, because the refusal the engine sees is real. Assertions
      * about recovery are NOT: nothing can pick the row up under this store, so an execution that never re-enters here says nothing
      * about the module. Recovery needs a real expiry, which `LateFirstRenewalStore` above is built to produce.
      */
    private class LosingRenewalStore(underlying: FlowStore, grants: AtomicInt) extends DelegatingStore(underlying):
        override protected def wrapClaimed(claimed: FlowStore.Claimed)(using Frame): FlowStore.Claimed =
            new DelegatingClaimed(claimed):
                override def renewClaim(lease: Duration)(using Frame): Boolean < (Async & Abort[FlowStoreException]) =
                    grants.getAndDecrement.map(remaining => remaining > 0)
    end LosingRenewalStore

    /** A store whose renewals stop arriving once an execution's unwind has begun.
      *
      * A lease bounds how long one executor may work on a row unwatched, and the renewal loop exists precisely so that live work can
      * outlive a single lease. An engine that keeps renewing across a ten minute compensation handler is therefore doing the correct
      * thing, and under a conforming store the claim never lapses while the handler runs, so the state this leaf is about cannot be
      * staged by letting the engine run. What produces it is the renewal not arriving at all, which is what a starved or partitioned
      * executor looks like from the store's side.
      *
      * The refusal reads the execution's status and nothing else, and it touches no row: no expiry is moved, no owner is cleared, no
      * event is written. The claim therefore lapses at the deadline the last honoured renewal set, which is what separates this from
      * `LosingRenewalStore` above and makes recovery assertions sound here.
      */
    private class UnrenewedCompensationStore(underlying: FlowStore, refusals: AtomicInt) extends DelegatingStore(underlying):
        override protected def wrapClaimed(claimed: FlowStore.Claimed)(using Frame): FlowStore.Claimed =
            new DelegatingClaimed(claimed):
                override def renewClaim(lease: Duration)(using Frame): Boolean < (Async & Abort[FlowStoreException]) =
                    underlying.getExecution(claimed.state.executionId).map { state =>
                        val unwinding = state.exists {
                            _.status match
                                case _: Flow.Status.Compensating => true
                                case _                           => false
                        }
                        if unwinding then refusals.incrementAndGet.andThen(false)
                        else super.renewClaim(lease)
                    }
    end UnrenewedCompensationStore

    // =========================================================================
    // Definition changes under a live execution
    // =========================================================================
    "definition changes" - {

        /** A structural change to a deployed flow holds its in-flight executions instead of killing them.
          *
          * Failing them is terminal and unrecoverable, and it skips every compensation on the way out, so a version bump would silently
          * leak exactly the resources a saga exists to protect. Compensating on the way out is not available either: the handlers live
          * in the definition the execution can no longer be matched to, so there is nothing to run. Holding is what keeps both options
          * open, and re-registering the definition the execution was started under is the rollback that recovers it.
          *
          * Being held is a report rather than a status: nothing is written about it in either direction, so the assertions are that the
          * execution is untouched and that `parked` names it with the hash it was started under.
          */
        "a structural change parks an in-flight execution instead of failing it" in {
            withEngine { (engine, store, tc) =>
                val v1 = Flow.init("fulfillment").step("reserve")(_ => ()).output("receipt")(_ => "paid")
                val v2 = Flow.init("fulfillment").step("reserve")(_ => ()).step("audit")(_ => ()).output("receipt")(_ => "paid")
                // Only v2 is registered here, so v1 is a version this engine cannot interpret. An engine that had registered v1 too
                // would keep serving it, which is what makes a rolling deployment work and is not the case under test.
                val v1Hash = kyo.internal.WorkflowSchema.structuralHash(v1)
                for
                    _ <- engine.register(wf1, v2)
                    eid = Flow.Id.Execution("exec-version-bumped")
                    _       <- seedExecution(store, eid, wf1, Flow.Status.Running, v1Hash)
                    _       <- Kyo.foreachDiscard(1 to 50)(_ => tc.advance(10.millis))
                    state   <- store.getExecution(eid)
                    held    <- engine.parked(wf1)
                    history <- store.getHistory(eid, Maybe.empty, 0)
                yield
                    assert(
                        state.exists(_.status == Flow.Status.Running),
                        s"nothing is written about being held, so the lifecycle is untouched, got ${state.map(_.status)}"
                    )
                    assert(
                        state.exists(_.executor.isEmpty),
                        s"an execution no engine serves must not be claimed, got ${state.map(_.executor)}"
                    )
                    assert(
                        held.exists(e => e.executionId == eid && e.hash == v1Hash),
                        s"`parked` must list the execution with the hash it was started under, got ${held.map(e => (e.executionId, e.hash))}"
                    )
                    assert(
                        !history.events.exists(_.kind == Flow.EventKind.Failed),
                        "a version mismatch must not fail the execution"
                    )
                    assert(
                        !history.events.exists(_.kind == Flow.EventKind.StepStarted),
                        "an execution no engine serves must not run a step"
                    )
                end for
            }
        }

        /** A start after re-registration runs the definition registered last.
          *
          * Registration is the retention policy: a NEW start always resolves the definition registered last, never an older version
          * that happens to share the workflow id. With definitions keyed by `(workflowId, hash)` a wrong resolution (oldest, or an
          * arbitrary key-order pick) passes every other leaf, because in-flight executions pin their own hash and only a fresh start
          * exercises the choice. The two versions differ in behaviour (x * 10 against x + 1) precisely so the assertion cannot pass
          * by accident of shape.
          */
        "a start after re-registration runs the definition registered last" in {
            withEngine { (engine, store, tc) =>
                val v1 = Flow.input[Int]("x").output("answer")(ctx => ctx.x + 1)
                val v2 = Flow.input[Int]("x").step("audit")(_ => ()).output("answer")(ctx => ctx.x * 10)
                for
                    _      <- engine.register(wf1, v1)
                    _      <- engine.register(wf1, v2)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 4)
                    status <- pump(tc, store, eid, _.isTerminal)
                    answer <- store.getField[Int](eid, "answer")
                yield
                    assert(status == Flow.Status.Completed, s"the started execution must complete, got $status")
                    assert(
                        answer == Maybe(40),
                        s"a start after re-registration must run the latest definition (4 * 10), got $answer"
                    )
                end for
            }
        }

        /** A store that blips while recording a wait must not destroy the execution.
          *
          * Recording a durable wait is the write on the suspension path, and it sits inside the handler that fails an execution when
          * running it raises. So a transient store failure there is recorded as terminal `Failed`, which is exactly the outcome
          * suspending exists to survive, and terminal status cannot be reverted. A blip has to leave the execution where the next
          * poll finds it, the way every other transient store failure on this path is retried rather than made permanent.
          */
        "a store failure while parking does not fail the execution" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicInt.init(1).map { blips =>
                        val failing = new FailingWaitStore(store, blips)
                        val flow    = Flow.init("fulfillment").step("reserve")(_ => ()).input[String]("approval")
                        // A short lease, because the blipped attempt could not reach the store and so leaves its claim to expire
                        // rather than releasing it; the recovery is the next poll after the lapse.
                        val config = FlowEngine.Config(workerCount = 1, lease = 1.second, renewEvery = 500.millis, pollTimeout = 100.millis)
                        FlowEngine.init(failing, config).map { engine =>
                            for
                                _      <- engine.register(wf1, flow)
                                handle <- engine.workflows.start(wf1)
                                eid = handle.executionId
                                state   <- pumpState(tc, store, eid, s => waitingFor("approval")(s) || s.status.isTerminal, 2000)
                                history <- store.getHistory(eid, Maybe.empty, 0)
                            yield
                                assert(
                                    waitingFor("approval")(state),
                                    s"a store blip while recording a wait must leave the execution recoverable, got $state"
                                )
                                assert(
                                    !history.events.exists(_.kind == Flow.EventKind.Failed),
                                    "a store blip while recording a wait must not record the execution as failed"
                                )
                            end for
                        }
                    }
                }
            }
        }

        /** Registering a definition wakes a poller that is already blocked, rather than leaving it to time out.
          *
          * Which versions an engine serves is decided when it asks the store, so a poll already inside `claimReady` is asking a
          * question that a registration has just made out of date. Nothing else can recover an execution held for a version nobody
          * served: it is not ready for any caller, so no write about the EXECUTION will ever wake a poller for it, and the only event
          * that changes the answer is the registration itself. Without a wake, the execution waits out a whole poll timeout, which is
          * thirty seconds by default and unbounded by anything a caller controls.
          *
          * The engine is given a long poll timeout precisely so that timing out cannot be what recovers the execution: the assertion is
          * that it finishes after strictly less virtual time than one poll.
          */
        "an execution parked after its definition was registered is still recovered" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    val wfId    = Flow.Id.Workflow("rolling")
                    val v1      = Flow.init("rolling").step("reserve")(_ => ())
                    val v2      = Flow.init("rolling").step("reserve")(_ => ()).output("receipt")(_ => "paid")
                    val hashV2  = kyo.internal.WorkflowSchema.structuralHash(v2)
                    val pollFor = 60.seconds
                    // v1 is given at init rather than registered afterwards, so the worker's very first poll already has something to
                    // serve and blocks inside `claimReady`. Registering it after init races the worker's first read of the map: lose
                    // that race and the worker sleeps out a whole poll timeout with nothing registered, which is a different scenario.
                    FlowEngine.init(store, FlowEngine.Config(workerCount = 1, pollTimeout = pollFor), v1).map { engine =>
                        val eid = Flow.Id.Execution("rolling-1")
                        for
                            _ <- seedExecution(store, eid, wfId, Flow.Status.Running, hashV2)
                            // Long enough for the poller to reach its blocking wait, and far short of one poll timeout.
                            _    <- Kyo.foreachDiscard(1 to 20)(_ => tc.advance(10.millis))
                            held <- store.getExecution(eid)
                            _ = assert(
                                held.exists(_.status == Flow.Status.Running),
                                s"the premise is an execution no engine serves yet, got ${held.map(_.status)}"
                            )
                            _ <- engine.register(wfId, v2)
                            reached <- settle(tc, 10.millis, 500) {
                                store.getExecution(eid).map(_.exists(_.status.isTerminal))
                            }
                            elapsed <- Clock.now
                        yield
                            assert(
                                reached,
                                "registering the definition must recover the execution it was held for"
                            )
                            assert(
                                elapsed - Instant.Epoch < pollFor,
                                s"the recovery must come from the registration rather than from the poll timing out, took ${elapsed - Instant.Epoch}"
                            )
                        end for
                    }
                }
            }
        }

        /** A parked execution costs nothing while it waits.
          *
          * Parking is open-ended: an execution can sit in it for as long as it takes someone to notice the version bump and roll the
          * definition back, which is hours, not seconds. So the cost of waiting has to be zero, and it is only zero if the store stops
          * offering the execution to the poll loop. An engine that kept claiming it would spin at full speed, since a claim that can
          * change nothing releases at once and the execution is ready again immediately, and it would write two history events per turn
          * on top of that: the wait would grow the history without bound and burn a worker for the whole of it.
          *
          * The test measures over two equal stretches rather than against the moment of parking, so an event still in flight when the
          * status flips cannot be read as a spin.
          */
        "a parked execution is left alone while it waits" in {
            withEngine { (engine, store, tc) =>
                val v1     = Flow.init("fulfillment").step("reserve")(_ => ()).output("receipt")(_ => "paid")
                val v2     = Flow.init("fulfillment").step("reserve")(_ => ()).step("audit")(_ => ()).output("receipt")(_ => "paid")
                val v1Hash = kyo.internal.WorkflowSchema.structuralHash(v1)
                def idle(using Frame): Unit < (Async & Abort[FlowStoreException]) =
                    Kyo.foreachDiscard(1 to 50)(_ => tc.advance(10.millis))
                for
                    _ <- engine.register(wf1, v2)
                    eid = Flow.Id.Execution("exec-parked-idles")
                    _      <- seedExecution(store, eid, wf1, Flow.Status.Running, v1Hash)
                    _      <- settle(tc)(engine.parked(wf1).map(_.exists(_.executionId == eid)))
                    _      <- idle
                    first  <- store.getHistory(eid, Maybe.empty, 0)
                    _      <- idle
                    second <- store.getHistory(eid, Maybe.empty, 0)
                    state  <- store.getExecution(eid)
                yield
                    assert(
                        second.events.size == first.events.size,
                        s"a parked execution must not be re-claimed: history grew from ${first.events.size} to ${second.events.size}"
                    )
                    assert(
                        state.exists(_.status == Flow.Status.Running),
                        s"the execution must still be held and untouched, got ${state.map(_.status)}"
                    )
                    assert(
                        state.exists(_.executor.isEmpty),
                        s"nothing must be holding a claim on it, got ${state.map(_.executor)}"
                    )
                end for
            }
        }

        "re-registering the definition an execution was started under recovers it" in {
            withEngine { (engine, store, tc) =>
                val v1     = Flow.init("fulfillment").step("reserve")(_ => ()).output("receipt")(_ => "paid")
                val v2     = Flow.init("fulfillment").step("reserve")(_ => ()).step("audit")(_ => ()).output("receipt")(_ => "paid")
                val v1Hash = kyo.internal.WorkflowSchema.structuralHash(v1)
                for
                    _ <- engine.register(wf1, v2)
                    eid = Flow.Id.Execution("exec-rolled-back")
                    _ <- seedExecution(store, eid, wf1, Flow.Status.Running, v1Hash)
                    _ <- settle(tc)(engine.parked(wf1).map(_.exists(_.executionId == eid)))
                    // The rollback: the definition the execution was started under is registered again.
                    _       <- engine.register(wf1, v1)
                    status  <- pump(tc, store, eid, _.isTerminal)
                    receipt <- store.getField[String](eid, "receipt")
                yield
                    assert(status == Flow.Status.Completed, s"expected the recovered execution to complete, got $status")
                    assert(receipt == Present("paid"), s"expected the recovered execution to produce its output, got $receipt")
                end for
            }
        }
    }

    // =========================================================================
    // Workflows API
    // =========================================================================
    "workflows API" - {

        "list registered workflows" in {
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

        "describe workflow" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .output("y")(ctx => ctx.x)
                    .loop("acc")(_ => Loop.done("done"))
                for
                    _    <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    meta <- engine.workflows.describe(wf1)
                yield
                    assert(meta.id == wf1.value)
                    // A loop persists a value like any other node, and the description says which type. Without evidence carried on
                    // the walk the description is built from, a dispatch, a loop and a foreach all read `Any`.
                    val loopNode = meta.nodes.find(_.name == "acc")
                    assert(loopNode.nonEmpty, s"the loop must be described, got ${meta.nodes.map(_.name)}")
                    assert(
                        loopNode.get.tag.show == Tag[String].show,
                        s"a loop's described type must be the type it stores, got ${loopNode.get.tag.show}"
                    )
                end for
            }
        }

        "describe unknown workflow fails" in {
            withEngine { (engine, store, tc) =>
                Abort.run[FlowException](engine.workflows.describe(Flow.Id.Workflow("unknown")))
                    .map(r => assert(r.isFailure))
            }
        }

        "workflow diagram" in {
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

        "signal via handle" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x + 1)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- pumpState(tc, store, eid, waitingFor("x"))
                    _      <- handle.signal[Int]("x", 42)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status == Flow.Status.Completed)
                end for
            }
        }

        "status via handle" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _   = ()
                    _ <- pumpState(tc, store, eid, waitingFor("x"))
                    s <- handle.status
                yield assert(s == Flow.Status.Running)
                end for
            }
        }

        "cancel via handle" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _   = ()
                    _ <- pumpState(tc, store, eid, waitingFor("x"))
                    _ <- handle.cancel
                    // A cancel is a request, so the terminal write lands once the unwind has run rather than in this call.
                    _     <- pump(tc, store, eid, _.isTerminal)
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

        "shows delivered and pending inputs" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").input[String]("name").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- pumpState(tc, store, eid, waitingFor("x"))
                    _      <- engine.executions.signal[Int](eid, "x", 42)
                    _      <- pumpState(tc, store, eid, waitingFor("name"))
                    inputs <- engine.executions.inputs(eid)
                yield
                    assert(inputs.find(_.name == "x").exists(_.delivered))
                    assert(inputs.find(_.name == "name").exists(!_.delivered))
                end for
            }
        }

        "generates execution diagram" in {
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

        "finds executions by workflow" in {
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

        "filters by status" in {
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
                    _ <- pumpState(tc, store, eid2, waitingFor("x"))
                    running <- engine.executions.search(
                        wfId = Maybe(wf1),
                        filter = Maybe(FlowStore.ExecutionFilter.WaitingForInput(Maybe("x")))
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

        "two executors share store, both process" in {
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

        "multiple executions distributed" in {
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
                        statuses <- Kyo.foreach(eids)(eid => pump(tc, store, eid, _.isTerminal))
                    yield assert(statuses.size == 5 && statuses.forall(_.isTerminal))
                    end for
                }
            }
        }
    }

    // =========================================================================
    // End-to-end
    // =========================================================================
    "end-to-end" - {

        "multi-step chain completes" in {
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

        "two inputs, two outputs" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .input[String]("name")
                    .output("greeting")(ctx => s"${ctx.name}: ${ctx.x}")
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- pumpState(tc, store, eid, waitingFor("x"))
                    _      <- engine.executions.signal[Int](eid, "x", 42)
                    _      <- pumpState(tc, store, eid, waitingFor("name"))
                    _      <- engine.executions.signal[String](eid, "name", "Hello")
                    status <- pump(tc, store, eid, _.isTerminal)
                    v      <- store.getField[String](eid, "greeting")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(v.get == "Hello: 42")
                end for
            }
        }

        "step and output interleaved" in {
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

        "events are recorded in correct order" in {
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
                    h <- store.getHistory(eid, Maybe(100), 0)
                yield
                    val evs   = h.events.toSeq
                    val kinds = evs.map(_.kind)
                    assert(kinds.head == Flow.EventKind.Created)
                    assert(kinds.contains(Flow.EventKind.Completed))
                    // Each output records StepStarted before its StepCompleted. Lifecycle events
                    // (claim/resume/input) interleave around steps in the shared audit log, so this
                    // asserts per-step start-before-complete ordering, not global event adjacency.
                    def startIdx(name: String) = evs.indexWhere { case Flow.Event.StepStarted(_, _, n, _, _) => n == name; case _ => false }
                    def completeIdx(name: String) =
                        evs.indexWhere { case Flow.Event.StepCompleted(_, _, n, _) => n == name; case _ => false }
                    Seq("y", "z").foreach { name =>
                        assert(startIdx(name) >= 0, s"missing StepStarted($name): ${kinds.mkString(", ")}")
                        assert(completeIdx(name) > startIdx(name), s"StepCompleted($name) before its StepStarted: ${kinds.mkString(", ")}")
                    }
                    assert(startIdx("y") < startIdx("z"), s"steps out of flow order: ${kinds.mkString(", ")}")
                end for
            }
        }
    }

    // =========================================================================
    // runLocal
    // =========================================================================
    "runLocal" - {

        "simple flow with pre-populated input completes" in {
            val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x * 2)
            Flow.runLocal(flow, "x" ~ 5).map { result =>
                assert(result.y == 10)
            }
        }

        "failure produces FlowException" in {
            val flow = Flow.input[Int]("x").output("y")(ctx =>
                throw new RuntimeException("oops"); ""
            )
            Abort.run[FlowException](Flow.runLocal(flow, "x" ~ 1)).map { result =>
                assert(result.isFailure)
            }
        }

        /** A subflow's own field is promised by the return type and never assembled.
          *
          * `.subflow("result", child)(...)` types the parent's `Out` with `"result" ~ Record[Out2]`, and `runLocal` returns
          * `Record[In & Out]`. The decode loop builds that record from `getAllFields` through `WorkflowSchema.fromStoreName`
          * (`Flow.scala:434-447`), and `WorkflowSchema.of` recurses into a subflow's child and creates no entry for the
          * subflow's own name (`internal/WorkflowSchema.scala:84`), while the subflow node persists nothing of its own. Decoding
          * the child's outputs under their own names and stopping there leaves `result` absent from a record whose type says it
          * is there, so reading it fails at run time on a field the compiler was told is total. The rebuild rule ("a subflow's
          * result is rebuilt from its children's durable fields under its path") covers the replay path, so assembling the
          * result for the record a caller receives is a separate obligation.
          */
        "a subflow's result field is present on the record runLocal returns" in {
            val child = Flow.input[Int]("a").output("b")(ctx => ctx.a * 10)
            val flow  = Flow.input[Int]("x").subflow("result", child)(ctx => "a" ~ ctx.x)
            Flow.runLocal(flow, "x" ~ 5).map { record =>
                val sub = record.result
                assert(sub.b == 50, s"the subflow's field must carry the child's outputs, got $sub")
            }
        }

        /** A child's INPUT is a field of the record the parent's type promises, so it is on the record a caller receives.
          *
          * `.input[V]("a")` refines the child's `Out2` with `"a" ~ V` exactly as `.output` does, so `Record[Out2]` promises `a` and
          * the parent's `"result" ~ Record[Out2]` carries it through to what `runLocal` hands back. `Record.selectDynamic` is a
          * `Dict.apply`, which throws `NoSuchElementException` on a key it does not hold, so a promised field that is not there is
          * not a compile error and not an absent value: it is a throw from a read the compiler was told is total.
          *
          * The value asserted is the one the mapper supplied, beside the child's own output, because an assembly that carried the
          * key with the wrong value would satisfy presence alone.
          */
        "a child input field the type promises is present on the record runLocal returns" in {
            val child = Flow.input[Int]("a").output("b")(ctx => ctx.a * 10)
            val flow  = Flow.input[Int]("x").subflow("result", child)(ctx => "a" ~ ctx.x)
            Flow.runLocal(flow, "x" ~ 5).map { record =>
                val sub = record.result
                assert(sub.a == 5, s"the child's input must be on the record its type promises it on, got $sub")
                assert(sub.b == 50, s"beside the child's own output, got $sub")
            }
        }

        /** The assembly recurses, because a subflow inside a subflow is a path inside a path.
          *
          * The leaf above is satisfied by an assembly that walks one level: it groups everything under `result~` into one record and
          * stops. That answer is wrong for a child that embeds a child, because the inner instance's fields arrive under
          * `outer~inner~ib` and a one-level walk either flattens them into the outer record under the name `inner~ib`, which no
          * accessor can read, or drops them. Both leave `record.outer.inner` missing on a record whose type says it is there, which
          * is the same defect one level down.
          *
          * Field VALUES are asserted at both levels rather than presence, so an assembly that produces an empty record at either
          * level fails here instead of passing on a field that exists and is blank.
          */
        "a subflow inside a subflow assembles both levels of the record runLocal returns" in {
            val inner = Flow.input[Int]("ia").output("ib")(ctx => ctx.ia + 1)
            val outer = Flow.input[Int]("oa")
                .subflow("inner", inner)(ctx => "ia" ~ ctx.oa)
                .output("ob")(ctx => ctx.inner.ib * 2)
            val flow = Flow.input[Int]("x").subflow("outer", outer)(ctx => "oa" ~ ctx.x)
            Flow.runLocal(flow, "x" ~ 5).map { record =>
                val out = record.outer
                assert(out.ob == 12, s"the outer instance's own output must be on its assembled record, got $out")
                assert(
                    out.inner.ib == 6,
                    s"the inner instance's record must be assembled under the outer one rather than flattened into it, got ${out.inner}"
                )
            }
        }

        /** A nested instance's inputs reach the caller's record at the level they belong to.
          *
          * The inputs compose the way the outputs do, because they are stored the same way: `outer~oa` is the outer child's own field
          * and `outer~inner~ia` belongs to the instance the child declares, so the assembly puts one on `record.outer` and the other
          * on `record.outer.inner` rather than flattening either. Both values are asserted, so an assembly that produced the key at
          * the wrong level, or produced it empty, fails here.
          */
        "a nested subflow's inputs are on the record runLocal returns at both levels" in {
            val inner = Flow.input[Int]("ia").output("ib")(ctx => ctx.ia + 1)
            val outer = Flow.input[Int]("oa")
                .subflow("inner", inner)(ctx => "ia" ~ ctx.oa)
                .output("ob")(ctx => ctx.inner.ib * 2)
            val flow = Flow.input[Int]("x").subflow("outer", outer)(ctx => "oa" ~ ctx.x)
            Flow.runLocal(flow, "x" ~ 5).map { record =>
                val out = record.outer
                assert(out.oa == 5, s"the outer child's input must be on its own assembled record, got $out")
                assert(out.inner.ia == 5, s"and the inner instance's under it rather than flattened, got ${out.inner}")
            }
        }

        /** A fan-out inside a subflow reaches the caller's record through the instance's field.
          *
          * The other nesting the assembly has to get right, and the one where a fan-out's own durable keys look exactly like a
          * subflow's. The items of `doubled` inside instance `batch` are stored as `batch~doubled~0` and so on, one separator deeper
          * than the node's own `batch~doubled`, so an assembly that treats every deeper name as a nested instance would build a
          * record with an `doubled` field holding item keys rather than the fan-out's `Chunk`.
          *
          * The asserted value is the whole chunk, in collection order, read through the subflow's field rather than out of the store.
          */
        "a foreach inside a subflow reaches the record runLocal returns through the subflow's field" in {
            val child = Flow.input[Int]("n").foreach("doubled")(ctx => (1 to ctx.n).toSeq)(item => item * 2)
            val flow  = Flow.input[Int]("x").subflow("batch", child)(ctx => "n" ~ ctx.x)
            Flow.runLocal(flow, "x" ~ 3).map { record =>
                assert(
                    record.batch.doubled == Chunk(2, 4, 6),
                    s"the fan-out's own results must reach the caller through the subflow's field, got ${record.batch}"
                )
            }
        }
    }

    // =========================================================================
    // Phase 2: Signal type mismatch
    // =========================================================================
    "signal type mismatch" - {

        "wrong type fails with FlowSignalTypeMismatchException" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _   <- pumpState(tc, store, eid, waitingFor("x"))
                    res <- Abort.run[FlowException](engine.executions.signal[String](eid, "x", "wrong"))
                yield assert(res.isFailure)
                end for
            }
        }
    }

    "start with wrong input type" - {

        "pre-populated input with wrong type fails with clear error" in {
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

        /** A start that is refused does not leave an execution behind.
          *
          * Writing the execution row and its `Created` event FIRST, then walking the declared inputs validating each against its
          * schema and aborting on the first that does not encode, leaves the row exactly where it was written: live, at a
          * non-terminal status, with a history.
          *
          * The leaf above asserts only that the caller is told, which is the half that works. The caller is told the start failed
          * and is given no handle, so it has no execution id and no reason to look for one, while the engine claims the row and
          * parks it on an input that by construction can never arrive: the value the caller tried to supply is the one just
          * rejected as undeliverable. Every such call leaks one permanently waiting execution, and the surface that would find it
          * is a search the caller has no id to search for.
          *
          * Either order is defensible on its own. Validating before creating works, and so does creating first and cleaning up on
          * refusal. Reporting failure while leaving the execution running does not.
          */
        "a refused start does not leave an execution behind" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x + 1)
                for
                    _ <- engine.register(wf1, flow)
                    inputs = new Record[Any](Dict("x" -> "not-an-int"))
                    res  <- Abort.run[FlowException](engine.workflows.start(wf1, inputs))
                    _    <- Kyo.foreachDiscard(1 to 50)(_ => tc.advance(10.millis))
                    live <- store.listExecutions(wf1, Maybe.empty, Maybe.empty, 0)
                yield
                    assert(res.isFailure, "the start must be refused")
                    assert(
                        live.isEmpty,
                        s"a refused start must not leave an execution behind, got ${live.map(e => (e.executionId, e.status))}"
                    )
                end for
            }
        }

        /** Seeding reports every failure of the encode as a type mismatch, because it cannot tell them apart.
          *
          * The leaf above is the case where that answer is right: the value really is the wrong type. This one is the case where it is
          * wrong, and it holds the behaviour still rather than blessing it. The value supplied is of exactly the declared type, so no
          * encoder could call it a mismatch; what fails is the schema's own write, and the caller is told its value had a type it did
          * not have, while what actually happened is discarded.
          *
          * **The width of that catch is forced by kyo-schema.** `Schema.encodeString` answers a `String` or throws, with no total
          * form, so the only way to learn a value does not match its schema is to attempt the write. What the attempt raises is
          * platform-dependent: `ClassCastException` on the JVM, and on JS and Wasm a linker `UndefinedBehaviorError`. Catching only
          * the JVM's exception kills the JS and Wasm hosts outright, and `Abort.catching` over `Throwable` misses it for a second
          * reason: `UndefinedBehaviorError` extends `VirtualMachineError`, `NonFatal` rejects it, and `Abort.catching` filters on
          * `NonFatal`. Only a plain `try`/`catch`, which is what `Result.catching` compiles to, reaches it on every platform.
          *
          * **It closes upstream, not here.** Give encoding a total form in kyo-schema and the failure becomes a value on every
          * platform; seeding then reports the encoder's verdict and only that, and the leaf to hold that is one asserting that a
          * throwable the encoding did not raise reaches the caller as itself.
          */
        "seeding cannot tell the encoder's verdict from anything else that passes through it" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[SeedProbe]("x").output("y")(ctx => ctx.x.value + 1)
                for
                    _ <- engine.register(wf1, flow)
                    inputs = new Record[Any](Dict("x" -> SeedProbe(1)))
                    res  <- Abort.run[FlowException](engine.workflows.start(wf1, inputs))
                    live <- store.listExecutions(wf1, Maybe.empty, Maybe.empty, 0)
                yield
                    assert(
                        res.failure.exists(_.isInstanceOf[FlowSignalTypeMismatchException]),
                        s"until encoding is total, a failed write is reported as a mismatch whatever it was, got $res"
                    )
                    assert(
                        res.failure.exists(_.getMessage.contains("x")),
                        s"and the refusal names the input it was seeding, got ${res.failure.map(_.getMessage)}"
                    )
                    assert(
                        live.isEmpty,
                        s"and the start still leaves no execution behind, got ${live.map(e => (e.executionId, e.status))}"
                    )
                end for
            }
        }

        /** A create the store refuses does not hand back a handle to somebody else's execution.
          *
          * `createExecutionIfAbsent` answers whether THIS call created the row, and a start that discards that answer is broken. A
          * false there says the id was already taken, so the `Handle` returned would address an execution this caller never started:
          * every later `signal`, `cancel` and `describe` through it would reach a stranger's execution, while the one the caller
          * asked for does not exist and never will.
          *
          * The staging is a store that refuses the create, because a random id cannot be made to collide on purpose and the engine
          * has no injection point for one. What the leaf is about is what the engine does with the answer, which is the same whether
          * the id collided or the store refused for a reason of its own.
          */
        "a start whose create is refused does not return a handle" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x + 1)
                for
                    _ <- engine.register(wf1, flow)
                    refusing = new DelegatingStore(store):
                        override def createExecutionIfAbsent(
                            executionId: Flow.Id.Execution,
                            status: Flow.Status,
                            event: Flow.Event,
                            hash: String,
                            fields: Dict[String, FlowStore.FieldData]
                        )(using Frame): Boolean < (Async & Abort[FlowStoreException]) = false
                    res <- Abort.run[Throwable] {
                        Scope.run {
                            FlowEngine.init(refusing, FlowEngine.Config(workerCount = 1, pollTimeout = 100.millis)).map { blocked =>
                                // Registered here rather than through `init`, which derives the id from the flow's own name: the
                                // leaf is about what `start` does with a refused create, and a start refused for being
                                // unregistered would pass the same assertion having reached none of it.
                                blocked.register(wf1, flow).andThen(blocked.workflows.start(wf1))
                            }
                        }
                    }
                yield
                    val raised = res match
                        case Result.Panic(_) => true
                        case _               => false
                    assert(
                        raised,
                        s"a create the store refused must be raised, not answered with a handle to an execution this call did " +
                            s"not create, got $res"
                    )
                end for
            }
        }
    }

    // =========================================================================
    // Phase 3: Sleep behavior
    // =========================================================================
    "sleep" - {

        "sets Sleeping status and resumes after time passes" in {
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
                    _      <- pumpState(tc, store, eid, sleeping)
                    status <- pump(tc, store, eid, _.isTerminal)
                    z      <- store.getField[Int](eid, "z")
                yield
                    assert(status == Flow.Status.Completed)
                    assert(z.get == 20)
                end for
            }
        }

        "SleepCompleted event recorded on resume" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .sleep("pause", 500.millis)
                    .output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 1)
                    _ <- pumpState(tc, store, eid, sleeping)
                    _ <- pump(tc, store, eid, _.isTerminal, 500)
                    h <- store.getHistory(eid, Maybe(100), 0)
                yield assert(h.events.exists(_.kind == Flow.EventKind.SleepStarted))
                end for
            }
        }
    }

    // =========================================================================
    // Phase 4: Compensation
    // =========================================================================
    "compensation" - {

        "compensated output fires on later failure" in {
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
                    case Flow.Status.Failed(_, _) => assert(compensated)
                    case other                    => fail(s"Expected Failed, got $other")
                end for
            }
        }

        /** A node whose compensation handler has run reads as compensated, not as merely completed.
          *
          * The per-handler completion records exist for the unwind's own sake, so a resumed unwind does not run a handler twice, and
          * they answer the question an operator watching an unwind actually has: which compensations have landed, and therefore how
          * far the unwind has got. Without an arm of its own a node that has been undone renders exactly like one whose work still
          * stands, which is the progress view reporting the opposite of what happened.
          *
          * Rendering the active node of an unwinding execution as `Failed("compensating")` is the alternative to avoid: it fabricates
          * a failure for a node that did not fail. The unwind is a fact about the flow, which the lifecycle carries, and the
          * node-level fact worth having is the one recorded per handler.
          */
        "a node whose compensation ran reads as compensated on the progress surface" in {
            withEngine { (engine, store, tc) =>
                var compensated = false
                val flow = Flow.input[Int]("x")
                    .outputCompensated("y")(ctx => ctx.x + 1)(ctx => compensated = true)
                    .output("z")(ctx =>
                        throw new RuntimeException("fail"); ""
                    )
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                    detail <- engine.executions.describe(eid)
                yield
                    assert(compensated, s"the premise is that the handler ran, and the execution ended $status")
                    assert(
                        detail.progress.nodeByName("y").map(_.status) ==
                            Maybe(FlowEngine.Progress.NodeStatus.Compensated),
                        s"an undone node must read as compensated, got ${detail.progress.nodeByName("y").map(_.status)}"
                    )
                    assert(
                        detail.progress.nodeByName("x").map(_.status) == Maybe(FlowEngine.Progress.NodeStatus.Completed),
                        s"and a node nothing undid must still read as completed, got ${detail.progress.nodeByName("x").map(_.status)}"
                    )
                end for
            }
        }

        /** The node that failed reads as failed while the execution is still unwinding, not only once it is over.
          *
          * An unwind is when an operator is watching hardest, and painting the failure on terminalisation blinds exactly that window,
          * because the whole compensation runs before then. The record is written when the step fails, so the answer is available
          * from that instant and does not change when the status does.
          *
          * The handler is held open on a gate so the assertion happens with the row genuinely `Compensating` rather than in a race
          * with the unwind finishing, and the premise is asserted rather than assumed.
          */
        "a node that failed reads as failed while the execution is still unwinding" in {
            withEngine { (engine, store, tc) =>
                Channel.init[Unit](1).map { release =>
                    val flow = Flow.init("unwind-view")
                        .outputCompensated("reserve")(_ => "held")(_ => Abort.run[Closed](release.take).unit)
                        .output("charge")(_ =>
                            throw new RuntimeException("charge declined"); ""
                        )
                    for
                        _      <- engine.register(wf1, flow)
                        handle <- engine.workflows.start(wf1)
                        eid = handle.executionId
                        unwinding <- settle(tc)(store.getExecution(eid).map(_.exists(_.status match
                            case _: Flow.Status.Compensating => true
                            case _                           => false)))
                        detail <- engine.executions.describe(eid)
                        _      <- release.put(())
                    yield
                        assert(unwinding, "the premise is that the row is Compensating with the handler still running")
                        assert(
                            detail.progress.nodeByName("charge").map(_.status) ==
                                Maybe(FlowEngine.Progress.NodeStatus.Failed("charge declined")),
                            s"the failing node must say so mid-unwind, got ${detail.progress.nodeByName("charge").map(_.status)}"
                        )
                        assert(
                            detail.progress.nodeByName("reserve").map(_.status) ==
                                Maybe(FlowEngine.Progress.NodeStatus.Completed),
                            s"and the node whose handler has not landed yet still reads completed, got " +
                                s"${detail.progress.nodeByName("reserve").map(_.status)}"
                        )
                    end for
                }
            }
        }

        /** A race loser's completed work is still compensated when the saga later fails.
          *
          * A branch that loses a race is not a branch that did nothing. It can have completed a step, written its field, and registered
          * the handler that undoes it, and only then lost, because a race is decided by the first branch to SUCCEED and a branch that
          * parks resolves nothing (`Fiber.internal.race` is `Race.success`). Whatever it reserved is reserved for real.
          *
          * When the race resolves, `filterComps` (`Flow.scala:877-885`) keeps a compensation only if the keys of the context it was
          * pushed with are a SUBSET of the winner's keys. A loser's handler is pushed with the loser's own output in scope, since
          * `outputCompensated` hands the handler `Record[Out & (N ~ V)]`, so its key set is never a subset of the winner's and it is
          * dropped. The reservation stays, and nothing will ever undo it.
          *
          * The filter is wrong in the other direction too, which this leaf does not exercise: a loser's handler pushed BEFORE that
          * branch wrote anything of its own has the shared prefix as its key set, passes the subset test, and survives to compensate
          * work whose branch lost. The mechanism that would have scoped this by branch identity, `restoreCompsForBranch`
          * (`Flow.scala:872-875`), has no call site, and the `snapshot` the race binds for it (`:1134`) is unused.
          *
          * **The premise is forced rather than sampled.** Left to itself, which branch reserves before the race is decided is a
          * scheduling fact: the winner has one step and the loser has a step plus an input, so the winner can finish first and the
          * loser be interrupted with nothing written, and the leaf then measures a race that never had a loser. The winner is held
          * until the loser's reservation is durably in the store, which is the state the leaf is about, and only then released to win.
          */
        "a race loser's completed work is still compensated when the saga fails" in {
            withEngine { (engine, store, tc) =>
                Channel.init[Unit](1).map { release =>
                    var reserveUndone = false
                    var answerUndone  = false
                    val reserving = Flow.init("race-comp")
                        .outputCompensated("reserve")(_ => "held")(_ => reserveUndone = true)
                        .input[String]("never")
                        .output("answer")(ctx => ctx.never)
                    val answering = Flow.init("race-comp")
                        .outputCompensated("answer") { _ =>
                            val body: String < Async = Abort.run[Closed](release.take).andThen("quick")
                            body
                        }(_ => answerUndone = true)
                    val flow = Flow.race(reserving, answering)
                        .output("boom")(_ =>
                            throw new RuntimeException("fail"); ""
                        )
                    for
                        _      <- engine.register(wf1, flow)
                        handle <- engine.workflows.start(wf1)
                        eid = handle.executionId
                        reserved <- settle(tc)(store.getField[String](eid, "reserve").map(_.isDefined))
                        _ = assert(reserved, "the premise is that the losing branch reserved before the race was decided")
                        _      <- Abort.run[Closed](release.put(()))
                        status <- pump(tc, store, eid, _.isTerminal)
                        held   <- store.getField[String](eid, "reserve")
                    yield
                        assert(status.isTerminal, s"the saga must terminate, got $status")
                        assert(held == Present("held"), s"the losing branch really did reserve, got $held")
                        assert(answerUndone, "the winner's own compensation must run")
                        assert(reserveUndone, "the loser completed a step and must be compensated, but its handler was filtered out")
                    end for
                }
            }
        }

        "all succeed → no compensations fire" in {
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

        "compensations do NOT fire on suspension (WaitingForInput)" in {
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
                    _ <- pumpState(tc, store, eid, waitingFor("name"))
                yield assert(!compensated)
                end for
            }
        }
    }

    // =========================================================================
    // Phase 5: Execution describe + search pagination
    // =========================================================================
    "execution describe" - {

        "returns execution state" in {
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

        "unknown execution fails" in {
            withEngine { (engine, store, tc) =>
                Abort.run[FlowException](engine.executions.describe(Flow.Id.Execution("unknown")))
                    .map(r => assert(r.isFailure))
            }
        }
    }

    "search pagination" - {

        /** `total` answers how many executions matched, not how many fit on this page.
          *
          * Asserting `total == 2` for a limit of two over three executions would pin the implementation
          * (`SearchResult(r.toSeq, r.length)`) rather than any property a caller could want, and it is wrong rather than merely
          * weak: a field named `total`, declared beside `items`, and returned from a method taking `limit` and `offset` means the
          * size of the result set being paged through. Answering `items.length` makes it a second name for
          * something the caller already has, and every paging client that divides `total` by its page size to size a control, or
          * compares it against what it has collected to decide whether to ask for more, reads it as the match count and gets one
          * page forever.
          */
        "reports the match count, not the length of the page" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _       <- engine.register(wf1, flow)
                    _       <- engine.workflows.start(wf1)
                    _       <- engine.workflows.start(wf1)
                    _       <- engine.workflows.start(wf1)
                    results <- engine.executions.search(wfId = Maybe(wf1), limit = 2, offset = 0)
                yield
                    assert(results.items.length == 2, s"a limit of two must answer two items, got ${results.items.length}")
                    assert(
                        results.total == 3,
                        s"total must count the executions that matched, not the ones on this page, got ${results.total}"
                    )
                end for
            }
        }

        /** Searching across workflows applies its offset once.
          *
          * With no workflow filter, asking every registered workflow for `(limit, offset)` and then applying
          * `.drop(offset).take(limit)` to the merged result applies the offset twice. Six executions across two workflows, paged at
          * two with an offset of two, then answer nothing at all: each workflow contributes the one row past its own offset, and the
          * second drop discards both.
          *
          * The per-workflow truncation is the deeper half. Taking `limit` rows from each workflow BEFORE the merge discards rows
          * that belong in the global page whenever one workflow's executions are newer than another's, so even the first page can
          * be wrong. A correct implementation fetches `offset + limit` from each, merges, sorts, and pages once.
          */
        "applies its offset once when searching across workflows" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                val wf2  = Flow.Id.Workflow("second-flow")
                for
                    _    <- engine.register(wf1, flow)
                    _    <- engine.register(wf2, flow)
                    _    <- Kyo.foreachDiscard(1 to 3)(_ => engine.workflows.start(wf1).andThen(tc.advance(1.second)))
                    _    <- Kyo.foreachDiscard(1 to 3)(_ => engine.workflows.start(wf2).andThen(tc.advance(1.second)))
                    page <- engine.executions.search(limit = 2, offset = 2)
                yield
                    assert(
                        page.items.length == 2,
                        s"six executions paged at two with an offset of two must answer two, got ${page.items.length}"
                    )
                    assert(
                        page.items.map(_.executionId).distinct.length == 2,
                        s"a page must not repeat an execution, got ${page.items.map(_.executionId)}"
                    )
                end for
            }
        }

        /** Searching every workflow finds an execution whose workflow this engine does not have registered.
          *
          * Enumerating `defs.keys` and asking each registered workflow for its executions leaves an execution belonging to a
          * workflow this engine has not registered out of every result. The same blindness as `cancelAll`, in the surface an
          * operator reaches for first.
          *
          * It is worst exactly where it matters most. An execution parked on a definition nobody has registered is unclaimable,
          * because `claimReady` is called with the registered ids too, so it makes no progress and emits no events. Search is how
          * an operator would find it, and search is looking through the registry that does not contain it. The execution is not
          * merely hard to find: by every surface the engine offers, it does not exist.
          */
        "search across workflows finds an execution whose workflow is not registered here" in {
            withEngine { (engine, store, tc) =>
                val flow    = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                val orphan  = Flow.Id.Workflow("unregistered-flow")
                val orphanE = Flow.Id.Execution("orphan-search")
                for
                    _ <- engine.register(wf1, flow)
                    // The other engine's registration, which is what makes the workflow a fact about the STORE while this engine
                    // serves nothing under it. Its execution is written the same way that engine would have written it.
                    _    <- store.putWorkflow(FlowEngine.WorkflowInfo.of(orphan.value, flow))
                    now  <- Clock.now
                    _    <- seedExecution(store, orphanE, orphan, Flow.Status.Running, "")
                    page <- engine.executions.search()
                yield assert(
                    page.items.exists(_.executionId == orphanE),
                    s"an execution this engine cannot resolve must still be findable, got ${page.items.map(_.executionId)}"
                )
                end for
            }
        }
    }

    // =========================================================================
    // Phase 7: End-to-end advanced
    // =========================================================================
    "end-to-end advanced" - {

        "20+ step chain completes" in {
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
        // The output always produces a value, sound by construction.

        "output: Abort.recover catches specific error and provides fallback" in {
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

        "output: Abort.recover with union error type" in {
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

        "output: unhandled error fails the workflow" in {
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
                    case Flow.Status.Failed(_, _) => true;
                    case _                        => false)
                end for
            }
        }

        "step: Abort.recover catches error, step continues" in {
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

        "foreach: Abort.recover per element" in {
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

        "loop: Abort.recover in body" in {
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

        "dispatch: Abort.recover in branch body" in {
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

        "output: Abort.catching lets non-matching errors propagate" in {
            withEngine { (engine, store, tc) =>
                class ExpectedError   extends RuntimeException("expected")
                class UnexpectedError extends RuntimeException("unexpected")
                val flow = Flow.input[Int]("x")
                    .output("y") { ctx =>
                        Abort.recover[ExpectedError](_ => -1)(
                            throw new UnexpectedError // not caught, propagates
                        )
                    }
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
    }

    // =========================================================================
    // Dispatch
    // =========================================================================
    "dispatch" - {

        "takes the first matching branch" in {
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

        "takes default branch when no condition matches" in {
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

        /** A loop's `timeout` bounds one iteration, not the sum of all of them.
          *
          * The unscheduled loop is interpreted as ONE computation under the node's `Meta`, so `timeout = 1.second` is a bound
          * on the whole convergence: a loop whose every iteration is comfortably inside the bound still dies when enough of
          * them add up, which makes the knob unusable for the thing a user bounds, the single slow iteration. A
          * whole-computation bound cannot be sized at all for a loop that legitimately converges over many iterations.
          */
        "a loop's timeout bounds the iteration, not the whole loop" in {
            withEngine { (engine, store, tc) =>
                var iterations = 0
                val flow = Flow.input[Int]("x")
                    .loop("work", timeout = 1.second) { ctx =>
                        iterations += 1
                        Async.sleep(400.millis).andThen {
                            if iterations >= 3 then Loop.done(iterations) else Loop.continue[Int]
                        }
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal, 400)
                    v      <- store.getField[Int](eid, "work")
                yield
                    assert(iterations >= 3, s"the premise is that every single iteration fit the bound, got $iterations")
                    assert(
                        status == Flow.Status.Completed,
                        s"iterations of 400 millis each must fit a 1 second per-iteration bound, got $status"
                    )
                    assert(v == Maybe(3), s"the loop's value must be stored, got $v")
                end for
            }
        }

        "loop done immediately" in {
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

        "loop done with value immediately" in {
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

        "loop with 1 state, an accumulator" in {
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

        "loop with 2 states" in {
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

        "processes collection and produces chunked result" in {
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

        "completed output is not re-executed on replay" in {
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
                    _ <- pumpState(tc, store, eid, waitingFor("name"))
                    c1 = callCount
                    _ <- engine.executions.signal[String](eid, "name", "hello")
                    _ <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(c1 == 1, "y computed once before waiting")
                    assert(callCount == 1, "y NOT re-computed on replay after name signal")
                end for
            }
        }

        "completed step is not re-executed on replay" in {
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
                    _ <- pumpState(tc, store, eid, waitingFor("name"))
                    c1 = stepCount
                    _ <- engine.executions.signal[String](eid, "name", "hello")
                    _ <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(c1 == 1, "step executed once before waiting")
                    assert(stepCount == 1, "step NOT re-executed on replay")
                end for
            }
        }

        "partial completion: first outputs skipped, rest execute" in {
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
                    _ <- pumpState(tc, store, eid, waitingFor("name"))
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

        "two compensated outputs, second fails: first fires in reverse" in {
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
                        case Flow.Status.Failed(_, _) => true;
                        case _                        => false)
                    assert(log.contains("revert-a"), s"revert-a should fire, log=$log")
                    assert(log.contains("revert-b"), s"revert-b should fire, log=$log")
                    assert(log.indexOf("revert-b") < log.indexOf("revert-a"), s"reverse order, log=$log")
                end for
            }
        }

        "all succeed: no reverts fire" in {
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

        "revert handler throws: swallowed, other reverts still run" in {
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
                        case Flow.Status.Failed(_, _) => true;
                        case _                        => false)
                    assert(log.contains("revert-a"), "revert-a should still fire despite b's throw")
                    assert(log.contains("revert-c"), "revert-c should still fire")
                end for
            }
        }

        "compensation does NOT fire on suspension" in {
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
                    _ <- pumpState(tc, store, eid, waitingFor("name"))
                yield assert(!fired, "compensation must not fire on suspension")
                end for
            }
        }

        "compensation re-registered on replay after skip" in {
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
                    _ <- pumpState(tc, store, eid, waitingFor("name"))
                    _ = assert(!fired, "not fired during suspension")
                    _      <- engine.executions.signal[String](eid, "name", "hello")
                    status <- pump(tc, store, eid, _.isTerminal)
                yield
                    assert(status match
                        case Flow.Status.Failed(_, _) => true;
                        case _                        => false)
                    assert(fired, "compensation fires on replay after skip+failure")
                end for
            }
        }

        "andThen: first part has compensation, second fails" in {
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
                        case Flow.Status.Failed(_, _) => true;
                        case _                        => false)
                    assert(fired)
                end for
            }
        }
    }

    // =========================================================================
    // Sleep edge cases
    // =========================================================================
    "sleep edge cases" - {

        "zero-duration sleep completes immediately" in {
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

        "sleep emits SleepStarted event" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .sleep("pause", 500.millis)
                    .output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- engine.executions.signal[Int](eid, "x", 1)
                    _ <- pumpState(tc, store, eid, sleeping)
                    h <- store.getHistory(eid, Maybe(100), 0)
                yield assert(h.events.exists(_.kind == Flow.EventKind.SleepStarted))
                end for
            }
        }
    }

    // =========================================================================
    // Cancel edge cases
    // =========================================================================
    "cancel edge cases" - {

        "cancel persists Cancelled status to store" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- pumpState(tc, store, eid, waitingFor("x"))
                    _ <- engine.executions.cancel(eid)
                    // The terminal write lands at the end of the unwind, which is what lets a cancel run compensations at all.
                    _     <- pump(tc, store, eid, _.isTerminal)
                    state <- store.getExecution(eid)
                yield assert(state.get.status == Flow.Status.Cancelled)
                end for
            }
        }

        /** An execution reads as cancelling from the moment somebody asks until it has finished unwinding.
          *
          * The window is the whole point of making a cancel a REQUEST: the terminal status is written after the handlers have run, so
          * between the ask and the ending there is a state an operator can see and a caller can act on. A surface that does not report
          * that state leaves the flag free to be written and then dropped on the way out, with nobody able to observe either.
          */
        "an execution reads as cancelling until it is cancelled" in {
            withEngine { (engine, store, tc) =>
                Channel.init[Unit](1).map { gate =>
                    // The compensated step runs BEFORE the park, so the replay that observes the cancel has a handler
                    // registered to unwind: a cancel raised with nothing to undo terminalises at once and leaves no window.
                    val flow = Flow.init("cancelling-view")
                        .outputCompensated("y")(_ => "held")(_ => Abort.run[Closed](gate.take).unit)
                        .input[Int]("x")
                        .output("z")(ctx => ctx.x)
                    for
                        _      <- engine.register(wf1, flow)
                        handle <- engine.workflows.start(wf1)
                        eid = handle.executionId
                        // Parked on an input nobody will deliver, so nothing but the cancel can move it.
                        _ <- pumpState(tc, store, eid, waitingFor("x"))
                        _ <- engine.executions.cancel(eid)
                        // Held mid-unwind behind the gate, which is where the request is outstanding and the status is not
                        // terminal yet. That is the state the promise is about.
                        unwinding <- pumpState(
                            tc,
                            store,
                            eid,
                            s =>
                                s.status match
                                    case _: Flow.Status.Compensating => true
                                    case _                           => false
                        )
                        detail <- engine.executions.describe(eid)
                        _      <- Abort.run[Closed](gate.put(()))
                        status <- pump(tc, store, eid, _.isTerminal)
                    yield
                        assert(
                            unwinding.cancelRequested,
                            s"an execution that has been asked to stop must read as cancelling, got $unwinding"
                        )
                        assert(
                            detail.state.cancelRequested,
                            s"and the request must reach the surface a caller reads, got ${detail.state}"
                        )
                        assert(
                            !detail.status.isTerminal,
                            s"the premise is that it had not terminalised yet, got ${detail.status}"
                        )
                        assert(status == Flow.Status.Cancelled, s"and it must end cancelled once the unwind is done, got $status")
                    end for
                }
            }
        }

        "cancel appends Cancelled event to history" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _ <- pumpState(tc, store, eid, waitingFor("x"))
                    _ <- engine.executions.cancel(eid)
                    _ <- pump(tc, store, eid, _.isTerminal)
                    h <- store.getHistory(eid, Maybe(100), 0)
                yield assert(h.events.exists(_.kind == Flow.EventKind.Cancelled))
                end for
            }
        }
    }

    // =========================================================================
    // Audit
    // =========================================================================
    "audit" - {

        "FlowLint checks recover names for duplicates" in {
            val flow = Flow.input[Int]("x")
                .output("x")(ctx => ctx.x + 1)
            val warnings = kyo.internal.FlowLint.duplicateNames(flow)
            assert(warnings.exists(_.message.contains("Duplicate node name 'x'")))
        }
    }

    // Version check tests moved to FlowVersionTest

    // =========================================================================
    // Phase 5: duplicate execution ID, diagram formats, Handle.describe
    // =========================================================================
    "duplicate execution ID" - {

        "start with existing ID fails" in {
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

        "mermaid" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _ <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    d <- engine.workflows.diagram(wf1, Flow.DiagramFormat.Mermaid)
                yield assert(d.contains("graph"))
                end for
            }
        }

        "dot" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _ <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    d <- engine.workflows.diagram(wf1, Flow.DiagramFormat.Dot)
                yield assert(d.contains("digraph"))
                end for
            }
        }

        "bpmn" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _ <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    d <- engine.workflows.diagram(wf1, Flow.DiagramFormat.Bpmn)
                yield assert(d.contains("bpmn"))
                end for
            }
        }

        "json" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _ <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    d <- engine.workflows.diagram(wf1, Flow.DiagramFormat.Json)
                yield assert(d.contains("nodes"))
                end for
            }
        }

        "elk" in {
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

        "returns execution detail with progress" in {
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

        "lists executions for workflow" in {
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

        "five reverts fire in reverse order" in {
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
                        case Flow.Status.Failed(_, _) => true;
                        case _                        => false)
                    assert(log == Seq("e", "d", "c", "b", "a"), s"reverse order, got: $log")
                end for
            }
        }

        "revert does not fire for step that never completed" in {
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

        "revert handler sees context at registration time" in {
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

        "multiple throwing revert handlers: all still execute" in {
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

        "foreach body has no compensation, later step fails: no spurious reverts" in {
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
                        case Flow.Status.Failed(_, _) => true;
                        case _                        => false)
                    assert(fired)
                end for
            }
        }
    }

    // =========================================================================
    // Zip/Gather execution through engine
    // =========================================================================
    "zip execution" - {

        "both branches execute and merge results" in {
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

        "all branches execute and merge" in {
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

        "reports progress for completed steps" in {
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

        "reports pending steps before input" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x + 1)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- pumpState(tc, store, eid, waitingFor("x"))
                    detail <- engine.executions.describe(eid)
                yield
                    assert(detail.progress.totalCount == 2)
                    val xNode = detail.progress.nodeByName("x")
                    assert(xNode.isDefined)
                    assert(xNode.get.status == FlowEngine.Progress.NodeStatus.WaitingForInput)
                end for
            }
        }

        /** A twice-embedded subflow's child nodes are distinguishable in the progress list.
          *
          * `NodeProgress.name` carries the composition path (`instance~node`, the separator the design uses), rather than the
          * child's bare name or nothing at all: a subflow can be embedded twice, and a progress list that either omits the children
          * or lists two entries under one bare name gives a renderer no way to say which instance did what. The progress visitor
          * descends into a child flow (`FlowEngine.scala`'s `onSubflow`) and qualifies every node the child contributes with the
          * name of the instance that embedded it, which is also the name the store knows those nodes by.
          */
        "a twice-embedded subflow's progress names its nodes by path" in {
            withEngine { (engine, store, tc) =>
                val child = Flow.input[Int]("amount").step("charge")(_ => ())
                val flow = Flow.input[Int]("x")
                    .subflow("first", child)(ctx => "amount" ~ ctx.x)
                    .subflow("second", child)(ctx => "amount" ~ ctx.x)
                    .output("done")(_ => "ok")
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 5)
                    _      <- pump(tc, store, eid, _.isTerminal)
                    detail <- engine.executions.describe(eid)
                yield
                    val names = detail.progress.nodes.map(_.name)
                    assert(
                        names.contains("first~charge") && names.contains("second~charge"),
                        s"each embedded instance's child node must appear in progress under its own path, got $names"
                    )
                end for
            }
        }

        /** A subflow and its child's inputs finish when the work under them has, and until then neither is the node that is running.
          *
          * The subflow's own name records nothing, by design: its children write under their qualified paths and the subflow arm
          * calls no verb for the instance itself. Reading its completion from the recorded set therefore never completes it, and the
          * surface pays for that twice: the instance takes the active marker whenever it comes first, so an operator watching a
          * running execution sees the subflow `running` and the node actually executing `pending`, and once the execution finishes
          * the instance reads `pending` forever, so `completedCount` never reaches `totalCount` on any workflow with a subflow in it
          * and every renderer that colours by status draws it that way. Completion is derived from the recorded facts around it
          * instead: the instance is done when the nodes under it are.
          *
          * The child's INPUT is not derived. Entering the instance records each declared input under its own path
          * (`review~amount`) before the child's first node runs, so the input is read from its record like every other node, and the
          * subflow's derivation rests on a recorded fact rather than on the walk having moved past it.
          *
          * **There is no failed arm, and its absence is the design rather than an omission.** A child that failed leaves its
          * subflow `Pending` on a terminal execution, because painting the subflow `Failed` too would put the same message on two
          * nodes and a reader asking which node failed would get two answers. That is exactly what the exact-match-first resolution
          * rule exists to prevent: a recorded path is attributed to the node that owns it and to nothing above it, which the leaf
          * "a failure keyed by an iteration paints its loop, and one keyed by a subflow's child paints the child" pins directly.
          * Adding the arm would invert that leaf, so it is not an improvement waiting to be made.
          */
        "a subflow and its child's input read completed once the work under them has" in {
            withEngine { (engine, store, tc) =>
                val child = Flow.input[Int]("amount").step("charge")(_ => ())
                val flow = Flow.input[Int]("x")
                    .subflow("review", child)(ctx => "amount" ~ ctx.x)
                    .input[String]("approval")
                    .output("done")(_ => "ok")
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _        <- engine.executions.signal[Int](eid, "x", 5)
                    _        <- pumpState(tc, store, eid, waitingFor("approval"))
                    parked   <- engine.executions.describe(eid)
                    _        <- engine.executions.signal[String](eid, "approval", "yes")
                    _        <- pump(tc, store, eid, _.isTerminal)
                    finished <- engine.executions.describe(eid)
                yield
                    assert(
                        parked.progress.nodeByName("review").map(_.status) ==
                            Maybe(FlowEngine.Progress.NodeStatus.Completed),
                        s"the subflow's nodes are done, so the subflow is done, got " +
                            s"${parked.progress.nodes.map(n => (n.name, n.status.show))}"
                    )
                    assert(
                        parked.progress.nodeByName("review~amount").map(_.status) ==
                            Maybe(FlowEngine.Progress.NodeStatus.Completed),
                        s"and the input the mapper filled in is done with it, got " +
                            s"${parked.progress.nodeByName("review~amount").map(_.status)}"
                    )
                    assert(
                        parked.progress.nodeByName("approval").map(_.status) ==
                            Maybe(FlowEngine.Progress.NodeStatus.WaitingForInput),
                        s"and the node the execution is actually at wears its own status, got " +
                            s"${parked.progress.nodeByName("approval").map(_.status)}"
                    )
                    assert(
                        finished.progress.completedCount == finished.progress.totalCount,
                        s"a completed execution has no node left unfinished, got " +
                            s"${finished.progress.completedCount} of ${finished.progress.totalCount} in " +
                            s"${finished.progress.nodes.map(n => (n.name, n.status.show))}"
                    )
                end for
            }
        }

        /** A child that computes nothing still finishes, on the record its own entry wrote.
          *
          * The subflow's derivation rests on recorded facts one level out, its own child nodes, so a child whose nodes are ALL inputs
          * is the case where that has to work with nothing but inputs. Leave a mapper-supplied input unpersisted and such a child
          * records nothing at any level: no node under the instance can leave `Pending`, both read `Pending` forever on a `Completed`
          * execution, and `completedCount` never reaches `totalCount`. Falling back to the LIFECYCLE, counting an execution that
          * completed as having entered every instance it declares, is a stand-in for evidence rather than evidence.
          *
          * Entering the instance records `review~amount` under its own path, so an inputs-only child produces exactly the record any
          * other child does and the instance is derived from it. The lifecycle is not consulted at all, which is what makes the same
          * question answerable for an execution that did NOT complete: the leaf below pins the losing arm of a race, where the
          * instance was never entered and reads `Pending` for that reason.
          */
        "a subflow whose child only declares inputs still completes" in {
            withEngine { (engine, store, tc) =>
                val child = Flow.input[Int]("amount")
                val flow = Flow.input[Int]("x")
                    .subflow("review", child)(ctx => "amount" ~ ctx.x)
                    .output("done")(_ => "ok")
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 5)
                    status <- pump(tc, store, eid, _.isTerminal)
                    detail <- engine.executions.describe(eid)
                yield
                    assert(status == Flow.Status.Completed, s"the premise is that the execution completed, got $status")
                    assert(
                        detail.progress.nodeByName("review~amount").map(_.status) ==
                            Maybe(FlowEngine.Progress.NodeStatus.Completed),
                        s"a child input the mapper filled in is done once the execution is, got " +
                            s"${detail.progress.nodeByName("review~amount").map(_.status)}"
                    )
                    assert(
                        detail.progress.nodeByName("review").map(_.status) ==
                            Maybe(FlowEngine.Progress.NodeStatus.Completed),
                        s"and so is the instance it belongs to, got ${detail.progress.nodeByName("review").map(_.status)}"
                    )
                    assert(
                        detail.progress.completedCount == detail.progress.totalCount,
                        s"a completed execution has no node left unfinished, got ${detail.progress.completedCount} of " +
                            s"${detail.progress.totalCount} in ${detail.progress.nodes.map(n => (n.name, n.status.show))}"
                    )
                end for
            }
        }

        /** An inputs-only child in a race's LOSING arm reads Pending, on the evidence rather than by a rule.
          *
          * Deriving an instance from the LIFECYCLE, so that a `Completed` execution counts as having entered every instance it
          * declares, is exact for `zip` and `gather`, which wait for every arm, and wrong for a `race`, whose losing arm is
          * interrupted the moment the winner finishes. Here the loser is interrupted before it reaches the subflow at all, so that
          * derivation paints an instance it never entered `Completed` with nothing recorded to say so.
          *
          * Entering an instance records the child's declared inputs under their own paths before the child's first node runs. The
          * loser never entered, so `review~amount` has no field, no event and no row; it reads `Pending` by evidence, and `review`
          * derives `Pending` from the one node under it. A node the loser DID complete is recorded exactly as a winner's is, so what
          * the record buys is telling the un-entered case from the entered one rather than guessing at it.
          *
          * The loser is decided by a latch nobody releases, not by a duration, so which arm wins is not a scheduling race: the
          * winning arm has no suspension point and the losing arm cannot pass its first one.
          */
        "an inputs-only subflow in a race's losing arm reads pending, because nothing under it was recorded" in {
            withEngine { (engine, store, tc) =>
                Latch.init(1).map { held =>
                    val child = Flow.input[Int]("amount")
                    val losing = Flow.init("race-derivation")
                        .step("blocked")(_ => held.await)
                        .subflow("review", child)(_ => "amount" ~ 1)
                    val winning = Flow.init("race-derivation").output("winner")(_ => "quick")
                    val flow    = Flow.race(winning, losing)
                    for
                        _      <- engine.register(wf1, flow)
                        handle <- engine.workflows.start(wf1)
                        eid = handle.executionId
                        status <- pump(tc, store, eid, _.isTerminal, 400)
                        detail <- engine.executions.describe(eid)
                        winner <- store.getField[String](eid, "winner")
                    yield
                        assert(status == Flow.Status.Completed, s"the premise is that the race completed, got $status")
                        assert(winner == Present("quick"), s"and that the arm with no suspension won it, got $winner")
                        assert(
                            detail.progress.nodeByName("review~amount").map(_.status) ==
                                Maybe(FlowEngine.Progress.NodeStatus.Pending),
                            s"the losing arm's child input reads pending, because the entry that would record it never ran, got " +
                                s"${detail.progress.nodeByName("review~amount").map(_.status)}"
                        )
                        assert(
                            detail.progress.nodeByName("review").map(_.status) ==
                                Maybe(FlowEngine.Progress.NodeStatus.Pending),
                            s"and so does the instance it belongs to, got ${detail.progress.nodeByName("review").map(_.status)}"
                        )
                        assert(
                            detail.progress.nodeByName("blocked").map(_.status) ==
                                Maybe(FlowEngine.Progress.NodeStatus.Pending),
                            s"while the node the loser was actually interrupted on records nothing and reads pending, got " +
                                s"${detail.progress.nodeByName("blocked").map(_.status)}"
                        )
                    end for
                }
            }
        }
    }

    // =========================================================================
    // Cancel edge cases
    // =========================================================================
    "cancel advanced" - {

        "cancel status is not overwritten by Failed" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _      <- engine.register(Flow.Id.Workflow("test-flow"), flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- pumpState(tc, store, eid, waitingFor("x"))
                    _      <- engine.executions.cancel(eid)
                    status <- pump(tc, store, eid, _.isTerminal)
                    _ = assert(status == Flow.Status.Cancelled)
                    // The value the execution was parked on arrives after it was cancelled, which is the one thing left that
                    // could still drive it forward. A status write of its own is not expressible: every one carries a claim,
                    // and a finished execution has none to carry.
                    _      <- Abort.run[FlowException](engine.executions.signal[Int](eid, "x", 1))
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

        "input-only flow reaches Completed" in {
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

        "loop body executes on every iteration (not cached)" in {
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

        "concurrent starts with same ID, at most one succeeds" in {
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

        "FlowGraph.build produces correct node and edge counts" in {
            val flow  = Flow.input[Int]("x").output("y")(ctx => ctx.x + 1).step("log")(ctx => ()).sleep("wait", 1.second)
            val graph = kyo.internal.FlowGraph.build(flow)
            assert(graph.nodes.size == 4)
            assert(graph.edges.size == 3)
        }

        "FlowGraph.build with progress annotates status" in {
            val flow     = Flow.input[Int]("x").output("y")(ctx => ctx.x + 1)
            val progress = FlowEngine.Progress.build(flow, Set("x", "y"), Flow.Status.Completed, Dict.empty)
            val graph    = kyo.internal.FlowGraph.build(flow, progress)
            assert(graph.nodes.exists(_.status == "completed"))
        }
    }

    // =========================================================================
    // Sleep edge cases (ported from v1 FlowSleepTest)
    // =========================================================================
    "sleep advanced" - {

        "sequential sleeps" in {
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
                    _ <- pumpState(tc, store, eid, sleeping)
                    _ <- pumpState(tc, store, eid, s => !sleeping(s), 300)
                    _ <- pump(tc, store, eid, _.isTerminal, 300)
                    v <- store.getField[Int](eid, "y")
                yield assert(v.get == 10)
                end for
            }
        }

        "compensation runs when step after sleep fails" in {
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
                        case Flow.Status.Failed(_, _) => true;
                        case _                        => false)
                    assert(fired, "compensation should fire after sleep+failure")
                end for
            }
        }
    }

    // =========================================================================
    // Skip/replay (ported from v1 FlowSkipTest)
    // =========================================================================
    "skip replay" - {

        "dispatch skipped when result already in record" in {
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
                    _ <- pumpState(tc, store, eid, waitingFor("name"))
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

        "zero-duration sleep does not execute and does not affect replay" in {
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
                    _ <- pumpState(tc, store, eid, waitingFor("trigger"))
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
        "compensation error propagates as Failure" in {
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
                            case Flow.Status.Failed(msg, _) => msg == "boom";
                            case _                          => false
                        ,
                        s"Should fail with 'boom' but got $status"
                    )
                end for
            }
        }

        "compensation handler sees only fields present at registration" in {
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
                        case Flow.Status.Failed(_, _) => true;
                        case _                        => false)
                    // Handler should see "x" and "a" (fields at registration), not "b" (added after)
                    assert(capturedKeys.contains("x"), s"Should see 'x', got $capturedKeys")
                    assert(capturedKeys.contains("a"), s"Should see 'a', got $capturedKeys")
                    assert(!capturedKeys.contains("b"), s"Should NOT see 'b', got $capturedKeys")
                end for
            }
        }

        "compensation emits CompensationStarted and CompensationCompleted events" in {
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
                    history <- store.getHistory(eid, Maybe.empty, 0)
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

        "Compensating status is not terminal" in {
            assert(!Flow.Status.Compensating(Flow.Cause.Failure("boom")).isTerminal)
        }
    }

    // =========================================================================
    // Scheduled loop (loopOn)
    // =========================================================================
    "scheduled loop" - {

        "fixed schedule stops when body returns done" in {
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

        "schedule runs until body returns done" in {
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

        "body returns done before schedule exhausts" in {
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

        /** A scheduled loop's delay is honoured across the resume between iterations.
          *
          * Each iteration ends in a durable sleep, so every iteration boundary is a suspend and a replay. If the schedule's position
          * were lost across that boundary the loop would either fire immediately on each resume, turning a paced loop into a spin, or
          * restart the interval from scratch. Measured in virtual time, three iterations at a hundred milliseconds have to take at
          * least the two intervals that separate them.
          */
        "a scheduled loop's delays survive the resume between iterations" in {
            withEngine { (engine, store, tc) =>
                var iterations = 0
                val flow = Flow.init("paced")
                    .loopOn("count", Schedule.fixed(100.millis).repeat(10)) { ctx =>
                        iterations += 1
                        if iterations < 3 then Loop.continue[Int]
                        else Loop.done(iterations)
                    }
                for
                    _      <- engine.register(wf1, flow)
                    before <- Clock.now
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    status <- pump(tc, store, eid, _.isTerminal, 400)
                    after  <- Clock.now
                yield
                    assert(status == Flow.Status.Completed, s"expected Completed, got $status")
                    assert(
                        (after - before) >= 200.millis,
                        s"three iterations separated by 100ms cannot finish in ${after - before}"
                    )
                end for
            }
        }

        /** A loop with no schedule does not lose the iterations it already ran.
          *
          * The unscheduled `loop` never suspends, so it has no iteration checkpoints at all: the whole loop is one node, recorded
          * complete only at the end. That is defensible, and it means the body must be idempotent across a crash, but the property
          * that has to hold either way is that a completed loop is not re-run on a later resume. Everything after it depends on its
          * recorded value, not on running it again.
          */
        "a completed unscheduled loop is not re-run on a later resume" in {
            withEngine { (engine, store, tc) =>
                AtomicInt.init(0).map { bodyRuns =>
                    val flow = Flow.init("unscheduled-loop")
                        .loop("total", 0) { (state: Int, ctx) =>
                            val outcome: Loop.Outcome[Int, Int] =
                                if state < 3 then Loop.continue(state + 1)
                                else Loop.done[Int, Int](state)
                            bodyRuns.incrementAndGet.andThen(outcome)
                        }
                        .input[String]("gate")
                        .output("done")(ctx => ctx.gate)
                    for
                        _      <- engine.register(wf1, flow)
                        handle <- engine.workflows.start(wf1)
                        eid = handle.executionId
                        _            <- pumpState(tc, store, eid, waitingFor("gate"))
                        beforeSignal <- bodyRuns.get
                        _            <- engine.executions.signal[String](eid, "gate", "go")
                        status       <- pump(tc, store, eid, _.isTerminal, 400)
                        after        <- bodyRuns.get
                        total        <- store.getField[Int](eid, "total")
                    yield
                        assert(status == Flow.Status.Completed, s"expected Completed, got $status")
                        assert(total == Present(3), s"the loop's recorded value should survive, got $total")
                        assert(
                            after == beforeSignal,
                            s"a completed loop must not re-run on resume: the body ran $beforeSignal times before the " +
                                s"input arrived and $after times after"
                        )
                    end for
                }
            }
        }

        /** An unscheduled loop interrupted part-way re-runs every iteration it already ran, and the cost is measured here.
          *
          * The leaf above pins the COMPLETED case. This is the other half, and it is an accepted limitation rather than a defect: an
          * unscheduled `loop` is one node with no iteration checkpoints, so an execution that dies at iteration 40 of 50 starts again
          * at 1. What is wrong with an accepted limitation is its invisibility, which is the argument for measuring it rather than
          * describing it.
          *
          * The handoff is real: the first engine's scope is closed while the loop is running, the clock passes the lease, and a
          * second engine on the same store claims the row the ordinary way. Nothing is seeded.
          *
          * Two things are asserted. The one that must hold whatever the limitation is: a loop that restarted still records the value
          * it would have recorded without the interruption, which is what makes the restart survivable rather than merely documented.
          * And the limitation itself, loosely enough that it cannot be flaky: the body runs MORE times than the loop has iterations,
          * which can only happen if iterations were repeated. How many more depends on where the interrupt landed, which is a
          * scheduling fact and is reported rather than asserted.
          *
          * **A failure of the second assertion is not a regression.** It means iteration checkpointing has arrived, and the design's
          * statement of the limitation has to change with this leaf. That coupling is the point of pinning an accepted limitation: it
          * cannot be quietly outgrown.
          *
          * The premises come first: the first engine really had begun looping, and the second really did finish the execution.
          */
        "an unscheduled loop interrupted part-way re-runs the iterations it already ran" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicInt.init(0).map { bodyRuns =>
                        val iterations = 6
                        val wfId       = Flow.Id.Workflow("loop-handoff")
                        val flow = Flow.init("loop-handoff")
                            .loop("total", 0) { (state: Int, ctx) =>
                                val outcome: Loop.Outcome[Int, Int] =
                                    if state < iterations then Loop.continue(state + 1)
                                    else Loop.done[Int, Int](state)
                                bodyRuns.incrementAndGet.andThen(outcome)
                            }
                            .output("done")(ctx => ctx.total)
                        val config = FlowEngine.Config(
                            workerCount = 1,
                            lease = 2.seconds,
                            renewEvery = 500.millis,
                            pollTimeout = 100.millis
                        )
                        for
                            eid <- Scope.run {
                                FlowEngine.init(store, config, flow).map { first =>
                                    first.workflows.start(wfId).map { handle =>
                                        settle(tc, step = 10.millis, maxRounds = 40)(
                                            bodyRuns.get.map(_ >= 1)
                                        ).andThen(handle.executionId)
                                    }
                                }
                            }
                            before <- bodyRuns.get
                            _      <- tc.advance(10.seconds)
                            _ <- Scope.run {
                                FlowEngine.init(store, config, flow).map { second =>
                                    settle(tc, step = 250.millis, maxRounds = 80)(
                                        store.getExecution(eid).map(_.exists(_.status.isTerminal))
                                    )
                                }
                            }
                            after <- bodyRuns.get
                            state <- store.getExecution(eid)
                            total <- store.getField[Int](eid, "total")
                        yield
                            assert(
                                before >= 1,
                                s"the premise is that the first engine had begun looping when it stopped, and it ran $before times"
                            )
                            assert(
                                state.exists(_.status == Flow.Status.Completed),
                                s"the premise is that the second engine finished the execution, got ${state.map(_.status)}"
                            )
                            assert(
                                total == Present(iterations),
                                s"a loop that restarted must still record the value it would have recorded without the " +
                                    s"interruption, got $total after $after body runs"
                            )
                            assert(
                                after > iterations,
                                s"D12 says an interrupted unscheduled loop restarts from the beginning: the body ran $after " +
                                    s"times for a $iterations iteration loop ($before of them before the handoff), so nothing " +
                                    s"was repeated. If that is now false, the non-goal has been FIXED and D12 plus section 6 " +
                                    s"of the design have to change with it"
                            )
                        end for
                    }
                }
            }
        }

        /** A scheduled loop that carries state gives each iteration the previous one's value.
          *
          * `loopOn(name, schedule, init)` takes a state and requires `Tag` and `Schema` for it, which is only meaningful if the state
          * is persisted across the durable sleep between iterations. Checkpointing a continue iteration with a step that stores no
          * value, and advancing the schedule on replay while passing the ORIGINAL `init` forward, loses it. Every scheduled iteration
          * suspends through that sleep, so this is not a crash-recovery case: it is what a stateful scheduled loop does in normal
          * operation, and the stateless overload with a local `var` that the other leaves in this group use never exercises it.
          */
        "a stateful scheduled loop carries its state across iterations" in {
            withEngine { (engine, store, tc) =>
                // The schedule is deliberately longer than this leaf watches. A short one lets the never-advancing state run the
                // loop to exhaustion, and the exhaustion path encodes `()` under the loop value's own schema, which raises a cast
                // error while writing the field. On the JVM that is caught and recorded; on a JS runtime it escapes and takes the
                // process down, so a short schedule here makes the whole suite unrunnable on that platform. The exhaustion path has
                // its own leaf; this one is about the state.
                val flow = Flow.init("acc-loop")
                    .loopOn("acc", Schedule.fixed(100.millis).repeat(1000), 0) { (state: Int, ctx) =>
                        if state >= 2 then Loop.done(state)
                        else Loop.continue(state + 1)
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    done <- settle(tc, maxRounds = 200)(store.getExecution(eid).map(_.exists(_.status.isTerminal)))
                    acc  <- store.getField[Int](eid, "acc")
                yield
                    assert(
                        acc == Present(2),
                        s"each iteration must see the previous iteration's state, got $acc"
                    )
                    assert(done, "the loop should reach its done condition and the execution should finish")
                end for
            }
        }

        "scheduled loop body throws, so the execution fails" in {
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
                    case Flow.Status.Failed(_, _) => true;
                    case _                        => false)
                end for
            }
        }

        /** The loop's declared `retry` schedule never sees the body, because the body runs outside the bracket.
          *
          * `loopOn` declares `retry` in its signature and `executeIteration` computes the body FIRST, then brackets `Kyo.unit`
          * with the node's `Meta` (`Flow.scala:1073-1077`): the schedule the user configured wraps a computation that cannot
          * fail, and the body it was configured for runs outside it, outside the timeout, and outside the claim check. So a
          * transient failure in an iteration terminalises the execution on the spot, with the retry budget untouched. The
          * iteration is the unit the knobs govern, and a schedule that can never fire is a feature the signature promises and the
          * interpreter does not have.
          */
        "a scheduled loop iteration that throws is retried by the loop's schedule" in {
            withEngine { (engine, store, tc) =>
                var calls = 0
                val flow = Flow.input[Int]("x")
                    .loopOn("poll", Schedule.fixed(100.millis).repeat(5), retry = Maybe(Schedule.fixed(100.millis).repeat(3))) {
                        ctx =>
                            calls += 1
                            if calls == 1 then throw new RuntimeException("transient")
                            Loop.done(42)
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal, 400)
                    v      <- store.getField[Int](eid, "poll")
                yield
                    assert(calls >= 1, s"the premise is that the body ran and its first call threw, got $calls calls")
                    assert(
                        status == Flow.Status.Completed,
                        s"a transient iteration failure with a retry schedule must be re-asked, not terminal, got $status " +
                            s"after $calls call(s) of a body whose schedule allowed three more"
                    )
                    assert(v == Maybe(42), s"the retried iteration's result must be stored, got $v")
                end for
            }
        }
    }

    // =========================================================================
    // Race execution
    // =========================================================================
    "race execution" - {

        /** A race branch's failure does not decide a race that can still be won.
          *
          * The fallback idiom is the argument: `race(notify-via-flaky-api, input("manual-ack"))` exists precisely so the
          * acknowledgement can rescue a failed notification. Completing the race on the FIRST success but on the LAST error makes
          * the outcome scheduling-dependent, because a suspension travels as an error: with the input branch parked first and the
          * flaky branch failing after it, the failure lands last, the race completes with it, and the flow terminally fails while
          * the acknowledgement it exists to wait for is still deliverable. The join reads every branch's outcome and a park
          * outranks a failure among them (`internal/StoreInterpreter.scala`'s `onRace`, whose `decide` answers the merged suspension
          * whenever any branch parked), so only an all-failed race fails. Failure-wins is the right ranking for `zip`, whose result
          * is unreachable once any branch fails; a race's result is still reachable through the parked branch, which is why the two
          * compositions rank the same two outcomes in opposite orders.
          */
        "a race branch that fails beside a parked sibling does not decide the race" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    val wfId    = Flow.Id.Workflow("fallback-race")
                    val waiting = Flow.init("fallback-race").input[String]("ack").output("answer")(ctx => ctx.ack)
                    val flaky = Flow.init("fallback-race").step("notify") { _ =>
                        Async.sleep(500.millis).andThen {
                            throw new RuntimeException("notify endpoint down"); ()
                        }
                    }.output("answer")(_ => "notified")
                    val flow   = Flow.race(waiting, flaky)
                    val config = FlowEngine.Config(workerCount = 1, lease = 30.seconds, pollTimeout = 100.millis)
                    FlowEngine.init(store, config, flow).map { engine =>
                        for
                            handle <- engine.workflows.start(wfId)
                            eid = handle.executionId
                            // Let the input branch park, then the flaky branch fail 500ms later: the failure lands last.
                            settled <- settle(tc, step = 100.millis, maxRounds = 30)(
                                store.getExecution(eid).map(_.exists(_.status.isTerminal))
                            )
                            afterFailure <- store.getExecution(eid)
                            _            <- Abort.run[FlowException](engine.executions.signal[String](eid, "ack", "rescued"))
                            _ <- settle(tc, step = 100.millis, maxRounds = 40)(
                                store.getExecution(eid).map(_.exists(_.status == Flow.Status.Completed))
                            )
                            finalState <- store.getExecution(eid)
                            answer     <- store.getField[String](eid, "answer")
                        yield
                            assert(
                                !afterFailure.exists(_.status.isTerminal),
                                s"a race with a live sibling must not be decided by a branch failure, but the flow " +
                                    s"terminalised as ${afterFailure.map(_.status)} while the acknowledgement was still " +
                                    s"deliverable"
                            )
                            assert(
                                finalState.exists(_.status == Flow.Status.Completed) && answer == Maybe("rescued"),
                                s"the acknowledgement must rescue the race, got status ${finalState.map(_.status)} and " +
                                    s"answer $answer"
                            )
                        end for
                    }
                }
            }
        }

        "one branch fails, other succeeds, so the winner completes" in {
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

        "both branches fail, so the execution fails" in {
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
                    case Flow.Status.Failed(_, _) => true;
                    case _                        => false)
                end for
            }
        }
    }

    // =========================================================================
    // Gather execution advanced
    // =========================================================================
    "gather advanced" - {

        "one branch fails, so the entire gather fails" in {
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
                    case Flow.Status.Failed(_, _) => true;
                    case _                        => false)
                end for
            }
        }

        "three branches all complete and merge" in {
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

        /** A step's retry schedule resumes where it stopped, instead of restarting on every resume.
          *
          * Entering `withTimeoutAndRetry` at the first attempt no matter what came before, while every
          * retry appends its own attempt number, writes the position durably and never reads it. A step
          * that fails deterministically then spends its whole schedule once per resume without ever
          * exhausting it, which turns a bounded retry policy into an unbounded one exactly when the
          * system is already unhealthy. The position is read
          * (`internal/StoreInterpreter.scala`'s `resume`, which walks the schedule's `next` once per
          * retry the history counted), so a resumed attempt continues the schedule it inherited.
          *
          * **The assertion is the shape of the sequence, not a budget.** Reading `attempt` numbers out
          * of history and requiring them to increase says exactly "the second executor continued the
          * schedule" without this leaf having to know how many retries `repeat(n)` allows or which of
          * them the handoff interrupted. A regression that restarted the schedule shows up as numbers
          * that begin again at 1 after the handoff.
          *
          * **The instrument is a real handoff.** The first engine's scope is closed while the step is
          * mid-retry, so it stops polling and stops renewing; the clock then passes the lease, and a
          * second engine on the same store claims the row the ordinary way. Nothing here forges durable
          * state and nothing refuses a renewal that a conforming store would grant: a fixture doing
          * either proves nothing, because no conforming store behaves that way.
          *
          * Both premises are asserted before the outcome: the first engine must really have retried at
          * least once before it went, and the second must really have retried after taking over. A run
          * where the execution is never picked up again fails on the second premise and says so, rather
          * than passing because no contradicting event was ever written.
          */
        "a step's retry schedule resumes at its recorded position across a handoff" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    val wfId = Flow.Id.Workflow("retry-budget")
                    val flow = Flow.init("retry-budget")
                        .output("charge", retry = Maybe(Schedule.fixed(1.second).repeat(6)))(_ =>
                            throw new RuntimeException("keeps failing"); ""
                        )
                    val config = FlowEngine.Config(
                        workerCount = 1,
                        lease = 2.seconds,
                        renewEvery = 500.millis,
                        pollTimeout = 100.millis
                    )
                    def retriesOf(eid: Flow.Id.Execution) =
                        store.getHistory(eid, Maybe.empty, 0).map(_.events.collect {
                            case Flow.Event.StepRetried(_, _, _, _, n, _, _) => n
                        })
                    for
                        eid <- Scope.run {
                            FlowEngine.init(store, config, flow).map { first =>
                                first.workflows.start(wfId).map { handle =>
                                    settle(tc, step = 250.millis, maxRounds = 40)(
                                        retriesOf(handle.executionId).map(_.size >= 2)
                                    ).andThen(handle.executionId)
                                }
                            }
                        }
                        before <- retriesOf(eid)
                        _      <- tc.advance(10.seconds)
                        _ <- Scope.run {
                            FlowEngine.init(store, config, flow).map { second =>
                                settle(tc, step = 250.millis, maxRounds = 80)(
                                    retriesOf(eid).map(_.size > before.size)
                                )
                            }
                        }
                        after <- retriesOf(eid)
                    yield
                        assert(
                            before.size >= 2,
                            s"the premise is that the first engine was mid-retry when it stopped, got $before"
                        )
                        assert(
                            after.size > before.size,
                            s"the premise is that the second engine took the execution over and retried, got $after " +
                                s"against $before: the execution was never picked up again"
                        )
                        assert(
                            after == after.sorted && after.distinct == after,
                            s"a retry position that resumes must produce strictly increasing attempt numbers, got $after " +
                                s"(the first engine wrote $before before it stopped)"
                        )
                    end for
                }
            }
        }

        /** A GUARD: losing a lease mid-step leaves the execution recoverable, rather than failing it.
          *
          * The code reads as though the opposite were true. `Abort.recover[Throwable] { ... updateStatus(eid,
          * Flow.Status.Failed(...), ...) }(executeOne(eid))` (`FlowEngine.scala:456-464`) beside the renewal fiber's
          * `execFiber.interrupt` (`:471`) suggests an executor which discovers it no longer owns the row uses that discovery to
          * write the most final thing it can. It does not: after the lease is lost mid-retry the execution is NOT terminal. The
          * guard keeps it that way, because the write is one refactor away from happening and losing a lease is not a verdict on
          * the work.
          *
          * The premise is asserted first: the step must really have been mid-retry when the lease went, or the run says nothing
          * about the case the leaf is named for.
          *
          * **What this leaf does NOT establish.** The run also shows the execution never being RE-ENTERED, one `StepStarted` across
          * 30 virtual seconds against a 2 second lease, and that observation is worthless: `LosingRenewalStore` refuses renewals
          * from a counter without consulting the claim row, so the UNDERLYING row stays claimed by a live, unexpired lease and no
          * competitor could have taken it whatever the engine did. The refusal the engine sees is real, which is why the assertion
          * above stands; the absence of a resume is the decorator's doing. Re-entry is a question only a real expiry and a second
          * engine can answer, which is the shape the handoff leaves use.
          */
        "an execution whose lease is lost mid-step is recoverable, not failed" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { plain =>
                    AtomicInt.init(1).map { grants =>
                        val store = new LosingRenewalStore(plain, grants)
                        val wfId  = Flow.Id.Workflow("lease-lost")
                        val flow = Flow.init("lease-lost")
                            .output("y", retry = Maybe(Schedule.fixed(1.second).repeat(5)))(_ =>
                                throw new RuntimeException("keeps failing"); ""
                            )
                        val config = FlowEngine.Config(
                            workerCount = 1,
                            lease = 2.seconds,
                            renewEvery = 500.millis,
                            pollTimeout = 100.millis
                        )
                        FlowEngine.init(store, config, flow).map { engine =>
                            for
                                handle <- engine.workflows.start(wfId)
                                eid = handle.executionId
                                _ <- settle(tc, step = 250.millis, maxRounds = 40)(
                                    plain.getExecution(eid).map(_.exists(_.status.isTerminal))
                                )
                                state <- plain.getExecution(eid)
                                page  <- plain.getHistory(eid, Maybe.empty, 0)
                                retried = page.events.count(_.kind == Flow.EventKind.StepRetried)
                            yield
                                assert(
                                    retried > 0,
                                    s"the premise is that the step was mid-retry when the lease was lost, got $retried retries"
                                )
                                assert(
                                    !state.exists(_.status.isTerminal),
                                    s"losing a lease is not a verdict on the work: the execution must stay recoverable, got " +
                                        s"${state.map(_.status)}"
                                )
                            end for
                        }
                    }
                }
            }
        }

        /** A lapsed executor's interrupt must not be consumed by a step's retry schedule.
          *
          * The renewal fiber's `execFiber.interrupt` is the ONE mechanism that stops a lapsed executor's work, and the retry
          * loop's catch is `Abort.run[Throwable]`, the kind that CATCHES panics where `Abort.recover` re-raises them. An
          * `Interrupted` is an ordinary throwable ("`Result.Panic` carries every throwable `NonFatal` admits", the worker
          * loop's own comment), so a schedule that classifies it an accident swallows the stop, sleeps the backoff, and
          * re-runs the side-effecting body under a claim the store may have given to somebody else; the renewal fiber fired
          * once and is done, so nothing ever interrupts again. This leaf measures which of the two happens: the body's call
          * count must stop growing after the refusal that triggers the interrupt.
          */
        "a lapsed executor's interrupt is not consumed by a step's retry schedule" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { plain =>
                    AtomicInt.init(1).map { grants =>
                        val store = new LosingRenewalStore(plain, grants)
                        val wfId  = Flow.Id.Workflow("interrupt-vs-retry")
                        var calls = 0
                        val flow = Flow.init("interrupt-vs-retry")
                            .output("slow", retry = Maybe(Schedule.fixed(1.second).repeat(5))) { _ =>
                                calls += 1
                                Async.sleep(10.seconds).andThen("done")
                            }
                        val config = FlowEngine.Config(
                            workerCount = 1,
                            lease = 2.seconds,
                            renewEvery = 500.millis,
                            pollTimeout = 100.millis
                        )
                        FlowEngine.init(store, config, flow).map { engine =>
                            for
                                handle <- engine.workflows.start(wfId)
                                eid = handle.executionId
                                started <- settle(tc, step = 250.millis, maxRounds = 40)(Sync.defer(calls >= 1))
                                refused <- settle(tc, step = 250.millis, maxRounds = 40)(grants.get.map(_ <= 0))
                                countAtRefusal = calls
                                // Bounded room for a swallowed interrupt to show itself: under the defect the schedule
                                // catches the interrupt, sleeps its backoff, and re-runs the body, so the count grows;
                                // under the correct classification it never grows again.
                                _ <- settle(tc, step = 250.millis, maxRounds = 40)(Sync.defer(calls > countAtRefusal))
                            yield
                                assert(
                                    started && refused,
                                    s"the premise is that the body ran and the renewal was then refused, got calls=$calls, " +
                                        s"grants=${grants}"
                                )
                                assert(
                                    calls == countAtRefusal,
                                    s"the interrupt is the mechanism that stops a lapsed executor: the schedule must not " +
                                        s"consume it and re-run the body, but the body ran ${calls - countAtRefusal} more " +
                                        s"time(s) after the refusal"
                                )
                            end for
                        }
                    }
                }
            }
        }

        /** A GUARD: an engine closed mid-step writes nothing terminal, because a shutdown is not a verdict on the work.
          *
          * It holds by a rule rather than by an accident of shape: every attempt ends through one transition function, and
          * `Interrupted` is one of its endings, the one that writes NOTHING, no status, no event, no release. The rule is not
          * decoration. An interrupt is a `Throwable` that is not a `FlowException`, so an ending function
          * without an explicit arm for it classifies a shutdown as a panic and terminalises every execution the engine was carrying,
          * each under a claim that is still perfectly VALID: the scope closes while the lease stands, so no store-side fence can
          * refuse a write the engine decides to make. The fence protects an execution from an executor that lost it, never from its
          * own.
          *
          * **What actually reaches the store on this path is nothing at all, and the reason is worth keeping.** Closing the scope
          * interrupts the supervisor along with the attempt, so no ending transition of any kind runs at shutdown; the
          * `Interrupted` arm's reachable producer is a refused renewal instead. This guard therefore pins the outcome, an execution
          * left claimable and unjudged, rather than the mechanism that delivers it.
          */
        "engine shutdown mid-step does not fail the execution" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    val wfId = Flow.Id.Workflow("shutdown-mid-step")
                    val flow = Flow.init("shutdown-mid-step")
                        .output("slow")(_ => Async.sleep(1.hour).andThen("done"))
                    val config = FlowEngine.Config(
                        workerCount = 1,
                        lease = 2.seconds,
                        renewEvery = 500.millis,
                        pollTimeout = 100.millis
                    )
                    def startedCount(eid: Flow.Id.Execution) =
                        store.getHistory(eid, Maybe.empty, 0).map(_.events.count(_.kind == Flow.EventKind.StepStarted))
                    for
                        eid <- Scope.run {
                            FlowEngine.init(store, config, flow).map { engine =>
                                engine.workflows.start(wfId).map { handle =>
                                    settle(tc, step = 250.millis, maxRounds = 40)(
                                        startedCount(handle.executionId).map(_ >= 1)
                                    ).andThen(handle.executionId)
                                }
                            }
                        }
                        stateAfterClose <- store.getExecution(eid)
                        before          <- startedCount(eid)
                        _               <- tc.advance(10.seconds)
                        _ <- Scope.run {
                            FlowEngine.init(store, config, flow).map { _ =>
                                settle(tc, step = 250.millis, maxRounds = 40)(startedCount(eid).map(_ > before))
                            }
                        }
                        after <- startedCount(eid)
                    yield
                        assert(
                            before >= 1,
                            s"the premise is that a step was mid-flight when the engine closed, got $before StepStarted"
                        )
                        assert(
                            !stateAfterClose.exists(_.status.isTerminal),
                            s"a shutdown is not a verdict on the work: the execution must not be terminal after the engine " +
                                s"closes, got ${stateAfterClose.map(_.status)}"
                        )
                        // The row half of the same rule. A closing scope interrupts the supervising fiber along with the work, so
                        // no ending transition runs at all: nothing is written and nothing is handed back, and what frees the row
                        // is the lease running out, which is what the recovery below waits for. The classification gate's own
                        // Interrupted arm is pinned where it is reachable, on "an executor that has lost its claim writes nothing
                        // more", because there the supervisor outlives the work it stopped.
                        assert(
                            stateAfterClose.exists(s => s.executor.isDefined && s.claimExpiry.isDefined),
                            s"an interrupted ending releases nothing: the claim must still be held after the engine closes, got " +
                                s"executor=${stateAfterClose.map(_.executor)} expiry=${stateAfterClose.map(_.claimExpiry)}"
                        )
                        assert(
                            after > before,
                            s"the execution must be claimable by the next engine once the lease lapses, got $after StepStarted " +
                                s"against $before before the close"
                        )
                    end for
                }
            }
        }

        /** An execution whose immediate renewal is refused is still recovered.
          *
          * The poll loop renews the moment it claims, and skipping the execution on a refusal with `case false => ()`
          * (`FlowEngine.scala:445-447`) writes no release, so the execution is never recovered at all: the polls keep discarding it
          * while the status stays `Running` and the claim expiry walks forward ahead of them.
          *
          * **The mechanism is in the store, which is where the fix goes.** `claimReady` returns only rows "not already owned by
          * us", and decides that from the previous snapshot's `executor` without asking whether that claim was still live
          * (`internal/MemoryFlowStore.scala:66-75`). So each poll re-claims the row, renews its lease by a full period, and then
          * filters it out of its own answer. The executor never receives it and no competitor can take it, because from outside the
          * lease never lapses. `FlowStoreTest` pins both halves deterministically ("Running whose own claim expired → returned to
          * the same executor", "a poll that returns nothing leaves the execution claimable by another executor"); this leaf shows
          * what the pair costs a user, which is one execution that never runs again.
          *
          * **The instrument is the point.** Refusing renewals from a counter cannot answer this, because such a store refuses a
          * renewal of a claim it has just granted and the underlying row then stays claimed by a live lease no matter what the engine
          * does (see `LosingRenewalStore`'s note). Here the store answers from the real row against the real clock at all times: the
          * decorator only advances virtual time past the lease once, between the claim and the immediate renewal, which is what a
          * batch slow enough to outlive its own lease does. `MemoryFlowStore.renewClaim` then refuses because `now < e` is false
          * (`internal/MemoryFlowStore.scala:106`), exactly as it would in production.
          *
          * **Skipping is not free, which is where the obvious argument goes wrong.** "An expired claim is claimable, so the skip
          * costs nothing" holds for every executor except the one whose name is on the claim, and that is the one that skipped. A
          * refused renewal has to release the claim it is refusing.
          *
          * The premise is asserted first: the renewal must really have been refused, or the run says nothing about the case.
          */
        "an execution whose immediate renewal is refused is still recovered" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { plain =>
                    AtomicInt.init(0).map { skews =>
                        AtomicInt.init(0).map { refusals =>
                            AtomicInt.init(0).map { polls =>
                                AtomicRef.init(Maybe.empty[Instant]).map { firstClaimAt =>
                                    val lease = 2.seconds
                                    val store = new LateFirstRenewalStore(plain, tc, lease, skews, refusals, polls, firstClaimAt)
                                    val wfId  = Flow.Id.Workflow("refused-renewal")
                                    val flow  = Flow.init("refused-renewal").output("done")(_ => "ok")
                                    val config = FlowEngine.Config(
                                        workerCount = 1,
                                        lease = lease,
                                        renewEvery = 500.millis,
                                        pollTimeout = 100.millis
                                    )
                                    FlowEngine.init(store, config, flow).map { engine =>
                                        for
                                            handle <- engine.workflows.start(wfId)
                                            eid = handle.executionId
                                            done <- settle(tc, step = 250.millis, maxRounds = 80)(
                                                plain.getExecution(eid).map(_.exists(_.status == Flow.Status.Completed))
                                            )
                                            refused <- refusals.get
                                            state   <- plain.getExecution(eid)
                                            page    <- plain.getHistory(eid, Maybe.empty, 0)
                                            claims = page.events.count(_.kind == Flow.EventKind.ExecutionClaimed)
                                            batches   <- skews.get
                                            pollCount <- polls.get
                                            claimedAt <- firstClaimAt.get
                                        yield
                                            assert(
                                                refused > 0,
                                                "the premise is that the immediate renewal was refused at least once; it never was"
                                            )
                                            assert(
                                                done,
                                                s"an execution skipped by the renewal gate must be claimable again and must run, got " +
                                                    s"${state.map(_.status)} after $refused refusals, $claims claim events, " +
                                                    s"$batches non-empty batches out of $pollCount polls, first claim at " +
                                                    s"$claimedAt, executor=${state.flatMap(_.executor)}, " +
                                                    s"expiry=${state.flatMap(_.claimExpiry)}, events=${page.events.map(_.kind)}"
                                            )
                                        end for
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        "output with retry fails twice then succeeds" in {
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

        "output with retry exhausts its schedule then fails" in {
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
                        case Flow.Status.Failed(_, _) => true;
                        case _                        => false)
                    assert(attempts >= 2, s"Expected at least 2 attempts but got $attempts")
                end for
            }
        }

        "step with retry retries a side-effecting step" in {
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

        "output completes within its timeout and succeeds" in {
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

        /** A configured timeout is a policy the user declared in the step's own `Meta`, and the failure it produces must carry a
          * kind a caller can group by.
          *
          * Catching the typed `Timeout`, appending `StepTimedOut` and then panicking an anonymous `RuntimeException` leaves the
          * terminal status `Failed(message, Absent)`: the one failure the engine itself classifies arrives with its classification
          * stripped, and "how many executions timed out" becomes a LIKE over message text. The recovery raises
          * `FlowStepTimeoutException` instead (`internal/StoreInterpreter.scala`'s `withTimeoutAndRetry`), a declared `FlowException`
          * that travels the typed channel and reaches the status carrying its own class name. The hierarchy's own rationale
          * (`FlowException.scala`, on `FlowDomainException`: "turning them into panics threw away the type on the way to a status
          * that keeps a string") is the argument this leaf holds the timeout path to.
          */
        "a step that times out is recorded as a timeout, not an anonymous panic" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .output("slow", timeout = 1.second)(ctx => Async.sleep(1.hour).andThen(ctx.x * 2))
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _       <- engine.executions.signal[Int](eid, "x", 5)
                    status  <- pump(tc, store, eid, _.isTerminal, 600)
                    history <- store.getHistory(eid, Maybe.empty, 0)
                yield
                    val timedOut = history.events.collect { case e: Flow.Event.StepTimedOut => e }
                    assert(timedOut.nonEmpty, "the premise is that the step timed out, and no StepTimedOut event was recorded")
                    val kindOf = status match
                        case Flow.Status.Failed(_, kind) => kind
                        case other                       => Maybe(s"not-failed: $other")
                    assert(
                        kindOf == Maybe("FlowStepTimeoutException"),
                        s"a configured timeout must terminalise as Failed with its own kind, got status $status"
                    )
                end for
            }
        }

        /** A store failure while the interpreter records `StepTimedOut` must ride the store's own channel to the engine, not be
          * consumed by the step's retry schedule.
          *
          * The schedule's catch is `Abort.run[Throwable]` (`internal/StoreInterpreter.scala:79`), which swallows the
          * `FlowStoreException` raised by that append: the step's bounded budget is spent on the store's failure, and the
          * `StepRetried` events it writes into history say the step failed when the store did. The engine separates the two at the
          * attempt level (a store failure is infra: health, claimable, no terminal write); the schedule erases the separation one
          * level down.
          */
        "a store failure while recording a timeout does not charge the step's retry budget" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { mem =>
                    AtomicInt.init(0).map { refusals =>
                        val store = FailingTimeoutEventStore(mem, refusals)
                        val wfId  = Flow.Id.Workflow("timeout-vs-infra")
                        val flow = Flow.init("timeout-vs-infra")
                            .output("slow", timeout = 1.second, retry = Maybe(Schedule.fixed(1.second).repeat(3)))(_ =>
                                Async.sleep(1.hour).andThen("done")
                            )
                        def retriedOf(eid: Flow.Id.Execution) =
                            mem.getHistory(eid, Maybe.empty, 0).map(_.events.collect {
                                case Flow.Event.StepRetried(_, _, name, error, _, _, _) => (name, error)
                            })
                        val config = FlowEngine.Config(workerCount = 1, lease = 30.seconds, pollTimeout = 100.millis)
                        for
                            outcome <- Scope.run {
                                FlowEngine.init(store, config, flow).map { engine =>
                                    engine.workflows.start(wfId).map { handle =>
                                        settle(tc, step = 250.millis, maxRounds = 40)(refusals.get.map(_ >= 1)).map { refused =>
                                            // Bounded room for the schedule to act on the refusal: under the defect the
                                            // condition turns true at the first StepRetried; under the fix it burns the
                                            // rounds and answers false, which is the definite no the assertion needs.
                                            settle(tc, step = 250.millis, maxRounds = 20)(retriedOf(handle.executionId).map(_.nonEmpty))
                                                .andThen((handle.executionId, refused))
                                        }
                                    }
                                }
                            }
                            (eid, refused) = outcome
                            retried <- retriedOf(eid)
                            n       <- refusals.get
                        yield
                            assert(refused && n >= 1, s"the premise is that the StepTimedOut append was refused, refusals=$n")
                            assert(
                                retried.isEmpty,
                                s"a store failure is infra, not a step failure: the schedule must not be charged for it, but " +
                                    s"StepRetried was written ${retried.size} time(s): $retried"
                            )
                        end for
                    }
                }
            }
        }
    }

    // =========================================================================
    // Dispatch advanced
    // =========================================================================
    "dispatch advanced" - {

        /** A dispatch persists only the branch's VALUE, so a crash between the branch's side effects and its field write lets
          * replay re-ask the conditions, and a condition that answers differently runs a DIFFERENT branch's side effects beside
          * the first's, with no durable trace that either ran. `foreach` guards this class of obligation with its persisted count
          * and `subflow` guards it with the mapper's purity requirement. The rule for `dispatch`: entering a branch records the
          * choice first, and a replay that finds the record re-enters that branch without evaluating any condition, because the
          * record is the truth of what ran and running a second branch beside it is the one outcome worse than either branch
          * alone.
          */
        "a dispatch replayed after a crash re-enters the branch that ran" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    var aCount = 0
                    var bCount = 0
                    var flag   = true
                    val wfId   = Flow.Id.Workflow("dispatch-replay")
                    val flow = Flow.init("dispatch-replay")
                        .dispatch[String]("route")
                        .when(_ => flag, name = "a") { ctx =>
                            aCount += 1
                            if aCount == 1 then Async.sleep(1.hour).andThen("A") else "A"
                        }
                        .otherwise(
                            ctx =>
                                bCount += 1
                                "B"
                            ,
                            name = "b"
                        )
                    val config = FlowEngine.Config(
                        workerCount = 1,
                        lease = 2.seconds,
                        renewEvery = 500.millis,
                        pollTimeout = 100.millis
                    )
                    for
                        eid <- Scope.run {
                            FlowEngine.init(store, config, flow).map { engine =>
                                engine.workflows.start(wfId).map { handle =>
                                    settle(tc, step = 250.millis, maxRounds = 40)(Sync.defer(aCount >= 1))
                                        .andThen(handle.executionId)
                                }
                            }
                        }
                        routeAtClose <- store.getField[String](eid, "route")
                        _ = flag = false
                        _ <- tc.advance(10.seconds)
                        _ <- Scope.run {
                            FlowEngine.init(store, config, flow).map { _ =>
                                settle(tc, step = 250.millis, maxRounds = 60)(
                                    store.getExecution(eid).map(_.exists(_.status.isTerminal))
                                )
                            }
                        }
                        route <- store.getField[String](eid, "route")
                    yield
                        assert(aCount >= 1, s"the premise is that branch a's side effects ran before the crash, got $aCount")
                        assert(
                            routeAtClose.isEmpty,
                            s"the premise is that the crash landed before the branch's field write, got $routeAtClose"
                        )
                        assert(
                            bCount == 0,
                            s"replay must re-enter the branch that ran, not the branch the condition now picks: branch b's " +
                                s"side effects ran $bCount time(s) beside branch a's"
                        )
                        assert(route == Maybe("A"), s"the recorded choice's value must be the one stored, got $route")
                    end for
                }
            }
        }

        "all conditions false, so the default branch is taken" in {
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

        "condition throws, so the execution fails" in {
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
                    case Flow.Status.Failed(_, _) => true;
                    case _                        => false)
                end for
            }
        }

        "branch body throws, so the execution fails" in {
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
                    case Flow.Status.Failed(_, _) => true;
                    case _                        => false)
                end for
            }
        }
    }

    // =========================================================================
    // Foreach advanced
    // =========================================================================
    "foreach advanced" - {

        "empty collection stores Chunk.empty" in {
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

        "body throws on one element, so the execution fails" in {
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
                    case Flow.Status.Failed(_, _) => true;
                    case _                        => false)
                end for
            }
        }

        "collection computation throws, so the execution fails" in {
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
                    case Flow.Status.Failed(_, _) => true;
                    case _                        => false)
                end for
            }
        }
    }

    // =========================================================================
    // Compensation advanced (additional)
    // =========================================================================
    "compensation deep nesting" - {

        "5 compensated outputs all fire in reverse on failure" in {
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
                        case Flow.Status.Failed(_, _) => true;
                        case _                        => false)
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

        "flow with only init completes immediately" in {
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

        "flow with only input suspends then completes" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- pumpState(tc, store, eid, waitingFor("x"))
                    _      <- engine.executions.signal[Int](eid, "x", 42)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield assert(status == Flow.Status.Completed)
                end for
            }
        }

        "flow with only sleep suspends then completes" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("test").sleep("wait", 1.second)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- pumpState(tc, store, eid, sleeping)
                    status <- pump(tc, store, eid, _.isTerminal, 200)
                yield assert(status == Flow.Status.Completed)
                end for
            }
        }
    }

    // =========================================================================
    // Dict debug tests
    // =========================================================================
    "dict debug" - {

        "engine defs contains registered workflow" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _    <- engine.register(wf1, flow)
                    defs <- engine.defs.get
                yield
                    assert(defs.byVersion.size == 1, s"Expected 1 def, got ${defs.byVersion.size}")
                    assert(defs.latest.get(wf1).nonEmpty, s"Expected wf1 in defs")
                end for
            }
        }

        "engine defs update replaces workflow" in {
            withEngine { (engine, store, tc) =>
                val flow1 = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                val flow2 = Flow.input[Int]("x").output("y")(ctx => ctx.x).output("z")(ctx => 0)
                for
                    _     <- engine.register(wf1, flow1)
                    defs1 <- engine.defs.get
                    _     <- engine.register(wf1, flow2)
                    defs2 <- engine.defs.get
                yield
                    // What a fresh start runs is what a re-registration replaces. Every version registered stays servable, which is
                    // what keeps an in-flight execution running while a new one is rolled out.
                    assert(defs1.latest.size == 1)
                    assert(defs2.latest.size == 1)
                    assert(defs1.latest.get(wf1).nonEmpty)
                    assert(defs2.latest.get(wf1).nonEmpty)
                    // Hash should change
                    val h1 = defs1.latest.get(wf1).get.meta.structuralHash
                    val h2 = defs2.latest.get(wf1).get.meta.structuralHash
                    assert(h1 != h2, s"Hashes should differ: $h1 vs $h2")
                end for
            }
        }

        "worker builds wfIds from defs" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                for
                    _    <- engine.register(wf1, flow)
                    defs <- engine.defs.get
                    wfIds = defs.workflowIds.toSet
                yield
                    assert(wfIds.size == 1, s"Expected 1 wfId, got ${wfIds.size}")
                    assert(wfIds.contains(wf1), s"Expected wf1 in wfIds, got $wfIds")
                end for
            }
        }

        "hash mismatch detected after re-register" in {
            withEngine { (engine, store, tc) =>
                val flow1 = Flow.input[Int]("x").output("y")(ctx => ctx.x)
                val flow2 = Flow.input[Int]("x").output("y")(ctx => ctx.x).output("z")(ctx => 0)
                for
                    _ <- engine.register(wf1, flow1)
                    // Check defs before start
                    d <- engine.defs.get
                    _ = assert(d.latest.get(wf1).nonEmpty, "flow1 should be registered")
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
                    assert(defs.latest.get(wf1).nonEmpty)
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

        "first node is Running, rest are Pending" in {
            val progress = FlowEngine.Progress.build(linearFlow, Set.empty, Flow.Status.Running, Dict.empty)
            assert(progress.nodes.size == 4)
            assert(progress.nodes(0).status == FlowEngine.Progress.NodeStatus.Running)
            (1 until progress.nodes.size).foreach { i =>
                assert(progress.nodes(i).status == FlowEngine.Progress.NodeStatus.Pending)
            }
            ()
        }

        "completedCount is 0" in {
            val progress = FlowEngine.Progress.build(linearFlow, Set.empty, Flow.Status.Running, Dict.empty)
            assert(progress.completedCount == 0)
        }

        "totalCount matches node count" in {
            val progress = FlowEngine.Progress.build(linearFlow, Set.empty, Flow.Status.Running, Dict.empty)
            assert(progress.totalCount == 4)
        }
    }

    "Progress.build with some completed steps" - {

        "marks completed steps correctly" in {
            val progress = FlowEngine.Progress.build(linearFlow, Set("x", "y"), Flow.Status.Running, Dict.empty)
            assert(progress.nodeByName("x").get.status == FlowEngine.Progress.NodeStatus.Completed)
            assert(progress.nodeByName("y").get.status == FlowEngine.Progress.NodeStatus.Completed)
            assert(progress.nodeByName("log").get.status == FlowEngine.Progress.NodeStatus.Running)
            assert(progress.nodeByName("wait").get.status == FlowEngine.Progress.NodeStatus.Pending)
        }

        "completedCount reflects completed steps" in {
            val progress = FlowEngine.Progress.build(linearFlow, Set("x", "y"), Flow.Status.Running, Dict.empty)
            assert(progress.completedCount == 2)
        }

        "all completed gives full count" in {
            val progress = FlowEngine.Progress.build(linearFlow, Set("x", "y", "log", "wait"), Flow.Status.Completed, Dict.empty)
            assert(progress.completedCount == 4)
            assert(progress.completedCount == progress.totalCount)
        }
    }

    "Progress.build with WaitingForInput status" - {

        "marks the waiting input as WaitingForInput" in {
            val progress =
                FlowEngine.Progress.build(linearFlow, Set.empty, Flow.Status.Running, Dict("x" -> Flow.Wake.OnField("x")))
            assert(progress.nodeByName("x").get.status == FlowEngine.Progress.NodeStatus.WaitingForInput)
        }

        "non-matching input stays pending" in {
            val flow = Flow.input[Int]("a")
                .input[String]("b")
                .output("c")(ctx => ctx.a)
            val progress = FlowEngine.Progress.build(flow, Set("a"), Flow.Status.Running, Dict("b" -> Flow.Wake.OnField("b")))
            assert(progress.nodeByName("a").get.status == FlowEngine.Progress.NodeStatus.Completed)
            assert(progress.nodeByName("b").get.status == FlowEngine.Progress.NodeStatus.WaitingForInput)
        }
    }

    "Progress.build with Sleeping status" - {

        "marks the sleeping node as Sleeping" in {
            val until = Instant.Epoch + 1.hour
            val progress =
                FlowEngine.Progress.build(linearFlow, Set("x", "y", "log"), Flow.Status.Running, Dict("wait" -> Flow.Wake.At(until)))
            assert(progress.nodeByName("wait").get.status == FlowEngine.Progress.NodeStatus.Sleeping(until))
        }

        "non-matching sleep stays pending" in {
            val flow = Flow.input[Int]("x")
                .sleep("s1", 1.minute)
                .sleep("s2", 2.minutes)
            val until = Instant.Epoch + 2.minutes
            val progress =
                FlowEngine.Progress.build(flow, Set("x", "s1"), Flow.Status.Running, Dict("s2" -> Flow.Wake.At(until)))
            assert(progress.nodeByName("s1").get.status == FlowEngine.Progress.NodeStatus.Completed)
            assert(progress.nodeByName("s2").get.status == FlowEngine.Progress.NodeStatus.Sleeping(until))
        }
    }

    "Progress.nodeByName" - {

        "finds existing node" in {
            val progress = FlowEngine.Progress.build(linearFlow, Set.empty, Flow.Status.Running, Dict.empty)
            val node     = progress.nodeByName("x")
            assert(node.isDefined)
            assert(node.get.name == "x")
            assert(node.get.nodeType == FlowEngine.Progress.NodeType.Input)
        }

        "returns empty for missing name" in {
            val progress = FlowEngine.Progress.build(linearFlow, Set.empty, Flow.Status.Running, Dict.empty)
            assert(progress.nodeByName("nonexistent").isEmpty)
        }

        "finds all node types" in {
            val flow = Flow.input[Int]("x")
                .dispatch[String]("d")
                .when(ctx => ctx.x > 0, name = "yes")(ctx => "yes")
                .otherwise(ctx => "no", name = "default")
                .loop("r") { ctx => Loop.done(ctx.x - 1) }
            val progress = FlowEngine.Progress.build(flow, Set.empty, Flow.Status.Running, Dict.empty)
            assert(progress.nodeByName("x").get.nodeType == FlowEngine.Progress.NodeType.Input)
            assert(progress.nodeByName("d").get.nodeType == FlowEngine.Progress.NodeType.Dispatch)
            assert(progress.nodeByName("r").get.nodeType == FlowEngine.Progress.NodeType.Loop)
        }
    }

    "Progress.completedCount and totalCount" - {

        "empty progress" in {
            val progress = FlowEngine.Progress.empty
            assert(progress.completedCount == 0)
            assert(progress.totalCount == 0)
        }

        "complex flow with zip" in {
            val left     = Flow.input[Int]("a").output("b")(ctx => ctx.a)
            val right    = Flow.input[Int]("c").output("d")(ctx => ctx.c)
            val flow     = left.zip(right)
            val progress = FlowEngine.Progress.build(flow, Set("a", "b"), Flow.Status.Running, Dict.empty)
            assert(progress.completedCount == 2)
            assert(progress.totalCount == 4)
        }
    }

    "Progress loop completion" - {

        /** A loop is done when the loop's own completion was recorded, and never because an iteration was.
          *
          * Treating three recorded iterations with no completion of the loop's own as `Completed` draws a loop on iteration 1 of 50
          * as finished and pushes `Running` onto the node after it, one that has not started.
          *
          * Both directions are pinned here rather than only the negative, since an implementation that never completes a loop node
          * at all would satisfy the negative half alone. The end-to-end half is a leaf of its own, driving a real scheduled loop
          * through `describe`; this one pins `Progress.build` itself.
          */
        "a loop's iterations do not complete the loop node" in {
            val flow = Flow.input[Int]("x")
                .loop("result") { ctx => Loop.done(ctx.x - 1) }
            val iterationsOnly = FlowEngine.Progress.build(
                flow,
                Set("x", "result#0", "result#1", "result#2"),
                Flow.Status.Completed,
                Dict.empty
            ).nodeByName("result")
            val ownCompletion = FlowEngine.Progress.build(
                flow,
                Set("x", "result"),
                Flow.Status.Completed,
                Dict.empty
            ).nodeByName("result")
            assert(iterationsOnly.isDefined)
            assert(
                iterationsOnly.map(_.status) != Maybe(FlowEngine.Progress.NodeStatus.Completed),
                s"iterations of a loop must not complete the loop, got ${iterationsOnly.map(_.status)}"
            )
            assert(
                ownCompletion.map(_.status) == Maybe(FlowEngine.Progress.NodeStatus.Completed),
                s"and the loop's own completion must, got ${ownCompletion.map(_.status)}"
            )
        }
    }

    "Progress running and failed status" - {

        "first non-completed node shows Running when flow status is Running" in {
            val progress = FlowEngine.Progress.build(linearFlow, Set.empty, Flow.Status.Running, Dict.empty)
            assert(progress.nodeByName("x").get.status == FlowEngine.Progress.NodeStatus.Running)
        }

        /** The node painted Failed is the node the record names, and no node is painted without one.
          *
          * Painting the FIRST node that is neither completed nor waiting with the execution's error is right for a straight line and
          * wrong the moment a flow is not one: a guess about which node failed reads exactly like a fact. The scenario here is the
          * one where the guess and the record agree, so what it discriminates is which of the two is being asked.
          *
          * The second half covers an execution whose history carries no failure record at all. It is painted NOWHERE: an absent
          * record is not evidence about some other node, and pending is the honest answer for a fact nobody wrote down.
          */
        "the node a failure record names shows Failed, and no node does without one" in {
            val recorded = FlowEngine.Progress.build(
                linearFlow,
                Set("x"),
                Flow.Status.Failed("boom"),
                Dict.empty,
                failed = Map("y" -> "boom")
            )
            val unrecorded = FlowEngine.Progress.build(linearFlow, Set("x"), Flow.Status.Failed("boom"), Dict.empty)
            assert(
                recorded.nodeByName("y").map(_.status) == Maybe(FlowEngine.Progress.NodeStatus.Failed("boom")),
                s"the recorded node must carry its failure, got ${recorded.nodeByName("y").map(_.status)}"
            )
            assert(
                !unrecorded.nodes.exists(_.status.isInstanceOf[FlowEngine.Progress.NodeStatus.Failed]),
                s"and with nothing recorded no node may claim the failure, got ${unrecorded.nodes.map(n => (n.name, n.status))}"
            )
        }

        /** A record keyed under a path no node carries walks up to the node that owns it, and one keyed under a node stops there.
          *
          * `Progress.build` resolves a failure's path against the nodes the flow actually has: exact match first, then up through
          * the last `#` or `~` until a node is reached, and nowhere if none is. Two rules meet in that sentence and this leaf pins
          * both, because `acc#3` and `review~step` are indistinguishable by shape and the walk alone would treat them alike.
          *
          * **No writer produces an iteration-keyed failure today.** A loop's body runs through `onIteration` under the loop's own
          * name, so a body that throws is already recorded as `acc`, and the `#` branch of the walk has no live producer. It is kept
          * and covered here rather than deleted as unreachable: the moment a writer records an iteration under its own path, which
          * is what a checkpointed iteration would do, the reader already paints the loop instead of painting nothing at all.
          *
          * The second half is the exact-match rule standing beside the walk. A subflow's child is qualified under its parent's path,
          * so `review~step` IS a node, and it wears its own failure while `review` does not wear its child's. Strip the suffix
          * without asking the node set first and the parent takes the paint, which is the failure a reader cannot see past: a
          * subflow of twenty nodes reports only that something inside it broke.
          */
        "a failure keyed by an iteration paints its loop, and one keyed by a subflow's child paints the child" in {
            val child = Flow.input[Int]("a").output("step")(ctx => ctx.a * 2)
            val flow = Flow.input[Int]("x")
                .loopOn("acc", Schedule.fixed(1.hour), 0) { (state: Int, ctx) => Loop.done(state) }
                .subflow("review", child)(ctx => "a" ~ ctx.x)
            val iterationKeyed = FlowEngine.Progress.build(
                flow,
                Set("x"),
                Flow.Status.Failed("boom"),
                Dict.empty,
                failed = Map("acc#3" -> "boom")
            )
            val subflowChild = FlowEngine.Progress.build(
                flow,
                Set("x"),
                Flow.Status.Failed("child broke"),
                Dict.empty,
                failed = Map("review~step" -> "child broke")
            )
            assert(
                iterationKeyed.nodeByName("acc").map(_.status) == Maybe(FlowEngine.Progress.NodeStatus.Failed("boom")),
                s"an iteration's failure belongs to its loop, got ${iterationKeyed.nodeByName("acc").map(_.status)}"
            )
            assert(
                !iterationKeyed.nodes.exists(n =>
                    n.name != "acc" && n.status.isInstanceOf[FlowEngine.Progress.NodeStatus.Failed]
                ),
                s"and to no other node, got ${iterationKeyed.nodes.map(n => (n.name, n.status))}"
            )
            assert(
                subflowChild.nodeByName("review~step").map(_.status) ==
                    Maybe(FlowEngine.Progress.NodeStatus.Failed("child broke")),
                s"a child's failure belongs to the child, got ${subflowChild.nodeByName("review~step").map(_.status)}"
            )
            assert(
                !subflowChild.nodeByName("review").exists(_.status.isInstanceOf[FlowEngine.Progress.NodeStatus.Failed]),
                s"and not to the subflow that contains it, got ${subflowChild.nodeByName("review").map(_.status)}"
            )
        }
    }

    "Progress node location" - {

        "location is populated" in {
            val progress = FlowEngine.Progress.build(linearFlow, Set.empty, Flow.Status.Running, Dict.empty)
            progress.nodes.foreach(node => assert(node.location.nonEmpty))
            ()
        }
    }

    /** An execution has one status cell, and concurrent branches each write it.
      *
      * `zip`, `gather` and `race` run their branches concurrently, and every suspending branch records itself in the execution's
      * single `Flow.Status`: a sleep writes `Sleeping(name, until)`, an input wait writes `WaitingForInput(name)`. Two suspending
      * branches therefore race, the store keeps whichever landed last, and readiness is computed from that one cell. What the other
      * branch was waiting for is no longer recorded anywhere the engine consults.
      *
      * Every leaf here is driven from durable state the test writes directly, rather than from whichever branch happens to win the
      * race. That is deliberate: the write order is genuinely nondeterministic, so seeding the state that order produces is the only
      * way to assert on the consequence without a flaky test.
      */
    "concurrent suspensions" - {

        /** A race records which branch won, in the history.
          *
          * Replay reconstructs an execution from its events, and a race is the one construct whose outcome is not a function of the
          * inputs: which branch won is a scheduling fact, and if it is not written down it cannot be recovered. `Flow.Event` has no
          * case for it. The winner's own output event is the closest thing, so this leaf accepts that as the record, and fails only
          * if nothing in the history distinguishes the branches at all.
          */
        "a race records which branch won" in {
            withEngine { (engine, store, tc) =>
                val quick = Flow.init("racer").output("winner")(_ => "quick")
                val slow  = Flow.init("racer").sleep("wait", 1.hour).output("winner")(_ => "slow")
                val flow  = Flow.race(quick, slow)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _       <- pump(tc, store, eid, _.isTerminal, 400)
                    history <- store.getHistory(eid, Maybe.empty, 0)
                    winner  <- store.getField[String](eid, "winner")
                yield
                    assert(winner == Present("quick"), s"the branch that does not sleep should win, got $winner")
                    val names = history.events.collect {
                        case Flow.Event.StepCompleted(_, _, name, _)   => name
                        case Flow.Event.SleepStarted(_, _, name, _, _) => name
                    }
                    assert(
                        names.nonEmpty,
                        s"the history must record something that distinguishes the branches, got ${history.events.map(_.kind)}"
                    )
                end for
            }
        }

        /** A race really replayed, by a different engine, keeps the winner the first engine picked.
          *
          * The sibling leaf "a race resumed from durable state keeps its original winner" pumps to TERMINAL, reads the field,
          * advances the clock while nothing is running, and reads it again, so what it establishes is that a finished execution's
          * field does not change on its own: **no replay and no crash happen in it**. This leaf supplies both.
          *
          * The handoff is real: the first engine's scope closes while the execution is parked on an input after the race, the clock
          * passes the lease, and a second engine on the same store claims the row and finishes it. The winner is read on both sides
          * of that.
          *
          * **Why the property is not obvious.** A replay re-races: the winner completes instantly from durable state, but the
          * loser starts again too (see "a race loser's re-execution across resumes stays bounded and does not change the result"),
          * so the two branches are genuinely in a race again, on a machine whose scheduling nobody controls. What makes the outcome
          * stable is that the winner's field is already present, so its branch completes without running anything.
          */
        "a race replayed by a second engine keeps the first engine's winner" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicInt.init(0).map { slowRuns =>
                        val wfId  = Flow.Id.Workflow("replay-handoff")
                        val quick = Flow.init("replay-handoff").output("winner")(_ => "quick")
                        val slow = Flow.init("replay-handoff").step[Async]("stall") { _ =>
                            slowRuns.incrementAndGet.andThen(Async.never)
                        }.output("winner")(_ => "slow")
                        val flow = Flow.race(quick, slow).andThen(Flow.input[Int]("go")).output("done")(ctx => ctx.go)
                        val config = FlowEngine.Config(
                            workerCount = 1,
                            lease = 2.seconds,
                            renewEvery = 500.millis,
                            pollTimeout = 100.millis
                        )
                        for
                            eid <- Scope.run {
                                FlowEngine.init(store, config, flow).map { first =>
                                    first.workflows.start(wfId).map { handle =>
                                        settle(tc, step = 250.millis, maxRounds = 40)(
                                            store.getField[String](handle.executionId, "winner").map(_.nonEmpty)
                                        ).andThen(handle.executionId)
                                    }
                                }
                            }
                            before <- store.getField[String](eid, "winner")
                            _      <- tc.advance(10.seconds)
                            _ <- Scope.run {
                                FlowEngine.init(store, config, flow).map { second =>
                                    second.executions.signal[Int](eid, "go", 5).andThen(
                                        settle(tc, step = 250.millis, maxRounds = 80)(
                                            store.getExecution(eid).map(_.exists(_.status.isTerminal))
                                        )
                                    )
                                }
                            }
                            after <- store.getField[String](eid, "winner")
                            state <- store.getExecution(eid)
                            done  <- store.getField[Int](eid, "done")
                        yield
                            assert(before.nonEmpty, s"the premise is that the race resolved on the first engine, got $before")
                            assert(
                                state.exists(_.status == Flow.Status.Completed),
                                s"the premise is that the second engine replayed and finished it, got ${state.map(_.status)}"
                            )
                            assert(done == Present(5), s"the premise is that the replay ran the rest of the flow, got $done")
                            assert(
                                after == before,
                                s"a race replayed by another engine must keep the winner the first one picked: it went from " +
                                    s"$before to $after"
                            )
                        end for
                    }
                }
            }
        }

        /** A race resumed from durable state picks the same winner it picked before.
          *
          * An execution whose race already resolved carries the winner's completion in its history, so a resume must honour it
          * rather than re-running the race and possibly choosing the other branch, which would contradict whatever the first winner
          * already did to the outside world.
          */
        "a race resumed from durable state keeps its original winner" in {
            withEngine { (engine, store, tc) =>
                AtomicInt.init(0).map { slowRuns =>
                    val quick = Flow.init("replay-race").output("winner")(_ => "quick")
                    val slow = Flow.init("replay-race").output("winner") { _ =>
                        val body: String < Sync = slowRuns.incrementAndGet.andThen("slow")
                        body
                    }
                    val flow = Flow.race(quick, slow)
                    for
                        _      <- engine.register(wf1, flow)
                        handle <- engine.workflows.start(wf1)
                        eid = handle.executionId
                        _      <- pump(tc, store, eid, _.isTerminal, 400)
                        first  <- store.getField[String](eid, "winner")
                        _      <- Kyo.foreachDiscard(1 to 50)(_ => tc.advance(10.millis))
                        second <- store.getField[String](eid, "winner")
                    yield assert(
                        first == second,
                        s"a resolved race must keep its winner, it went from $first to $second"
                    )
                    end for
                }
            }
        }

        /** A GUARD: a race loser's re-execution on resume stays bounded and does not corrupt the outcome.
          *
          * A decided race re-runs its losing branch on later resumes, because a race records no durable decision, and that is an
          * accepted behaviour on the grounds that idempotency makes it survivable. This leaf measures the behaviour rather than
          * trusting the argument.
          *
          * **Re-execution is REAL and INTERMITTENT.** Whether the loser is scheduled at all before the winner's replay resolves the
          * race is a scheduling fact rather than a property, so "re-runs on every later resume" overstates it: across three resumes
          * of one execution the loser's body may run twice, three times, or not at all. What is stable is that the count is bounded
          * by the resumes that cause it and the result is unaffected.
          *
          * **So the assertions are the two things that hold.** The execution still finishes with the right value after its loser has
          * re-executed, which is what the survivability argument rests on and is worth pinning permanently; and the re-execution
          * stays bounded by the resume count rather than growing on its own, which is what catches a regression into a spin. "At
          * most once" is false and "the loser had started before the first resume" is flaky, because both assert a scheduling fact.
          *
          * **What the loser's node is.** A step that increments and then never returns, so if it starts it never completes, which is
          * the shape the accepted behaviour describes. A loser whose body completes instantly measures nothing: it is gone before
          * the first replay, and the counter then reads 0 on both sides of the resume.
          */
        "a race loser's re-execution across resumes stays bounded and does not change the result" in {
            withEngine { (engine, store, tc) =>
                AtomicInt.init(0).map { slowRuns =>
                    val quick = Flow.init("race-resume").output("winner")(_ => "quick")
                    // The loser's node STARTS and never COMPLETES, which is the shape under test: an incomplete
                    // node, with nothing durable to skip it on the next pass.
                    val slow = Flow.init("race-resume").step[Async]("stall") { _ =>
                        slowRuns.incrementAndGet.andThen(Async.never)
                    }.output("winner")(_ => "slow")
                    val flow = Flow.race(quick, slow)
                        .andThen(Flow.input[Int]("go1"))
                        .andThen(Flow.input[Int]("go2"))
                        .andThen(Flow.input[Int]("go3"))
                        .output("done")(ctx => ctx.go1 + ctx.go2 + ctx.go3)
                    def parkThenSignal(eid: Flow.Id.Execution, name: String, value: Int) =
                        pumpState(tc, store, eid, s => !s.status.isTerminal && waitingFor(name)(s), 400)
                            .andThen(engine.executions.signal[Int](eid, name, value))
                    for
                        _      <- engine.register(wf1, flow)
                        handle <- engine.workflows.start(wf1)
                        eid = handle.executionId
                        _      <- parkThenSignal(eid, "go1", 10)
                        before <- slowRuns.get
                        _      <- parkThenSignal(eid, "go2", 15)
                        _      <- parkThenSignal(eid, "go3", 17)
                        status <- pump(tc, store, eid, _.isTerminal, 400)
                        after  <- slowRuns.get
                        done   <- store.getField[Int](eid, "done")
                    yield
                        assert(
                            status == Flow.Status.Completed,
                            s"the premise is that the execution resumed three times and finished, got $status"
                        )
                        assert(
                            done == Present(42),
                            s"a resumed execution whose race loser re-executes must still finish with the right value, got " +
                                s"$done after the loser's body ran $after times ($before of them before the second resume)"
                        )
                        assert(
                            after <= 4,
                            s"the losing branch's re-execution must stay bounded by the resumes that cause it: three resumes " +
                                s"of one execution ran it $after times"
                        )
                    end for
                }
            }
        }

        /** A sleep far beyond the representable range of an instant is refused, not silently wrapped.
          *
          * A durable sleep is recorded as an absolute deadline, so a duration large enough to overflow that instant produces a
          * deadline in the past, and an execution that meant to wait a very long time wakes immediately. Refusing the duration and
          * saturating at the maximum instant are both defensible; wrapping is not, because it turns the longest possible wait into
          * the shortest.
          */
        "a sleep beyond the representable range does not wake immediately" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("far-future").sleep("epoch", Duration.Infinity).output("done")(_ => "ok")
                for
                    _       <- engine.register(wf1, flow)
                    started <- Abort.run[Throwable](engine.workflows.start(wf1))
                    // Advanced in steps near the poll interval rather than in hour-long jumps. A single jump far past the poll
                    // timeout makes the engine's timer fire once per interval inside it, so advancing an hour at a time against a
                    // 100ms poll schedules tens of thousands of callbacks per step and hundreds of thousands over the leaf, which a
                    // single-threaded runtime does not survive. Ten simulated seconds is ample for a sleep that should never wake.
                    settled <- Kyo.foreachDiscard(1 to 100)(_ => tc.advance(100.millis)).andThen(Kyo.unit)
                    finalState <- started match
                        case Result.Success(h) => store.getExecution(h.executionId)
                        case _                 => Maybe.empty[FlowStore.ExecutionState]: Maybe[FlowStore.ExecutionState] < Any
                yield
                    // Refusing the duration outright and accepting it while never waking are both acceptable; waking early is not,
                    // because that turns the longest possible wait into the shortest.
                    val acceptable = started match
                        case Result.Success(_) => !finalState.exists(_.status.isTerminal)
                        case _                 => true
                    assert(
                        acceptable,
                        s"a sleep of an unbounded duration must not complete, got ${finalState.map(_.status)}"
                    )
                end for
            }
        }

        /** Racing an input against a sleep is a durable timeout, and completes when the sleep expires.
          *
          * The obvious way to write "wait for approval, but give up after five minutes", and the shape most exposed to the single
          * status cell. Both branches suspend, so both write the status in an order nobody controls, and only the branch named in the
          * status can wake the execution. When the input wait lands last the deadline becomes invisible and the timeout never fires.
          *
          * Driven from seeded state rather than from a live race, and that is not a stylistic choice. Against a live race the leaf
          * measures which write happened to land last, so it passes or fails by scheduling. Seeding the losing order is what makes
          * the failure reproduce every time instead of half the time.
          */
        "racing an input against a sleep completes when the sleep expires" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    val wfId    = Flow.Id.Workflow("timeout-race")
                    val waiting = Flow.init("timeout-race").input[String]("approval").output("answer")(ctx => ctx.approval)
                    val timeout = Flow.init("timeout-race").sleep("deadline", 5.seconds).output("answer")(_ => "timed out")
                    val flow    = Flow.race(waiting, timeout)
                    Clock.now.map { t0 =>
                        val eid      = Flow.Id.Execution("timeout-race-1")
                        val deadline = t0 + 5.seconds
                        val theHash  = kyo.internal.WorkflowSchema.structuralHash(flow)
                        for
                            // Seeded with BOTH parked branches' rows, which is what a finished attempt of this race leaves
                            // behind. Seeding one and leaving the other as a bare history event would describe a state no
                            // correct system produces, and readiness would rightly refuse to wake the branch with no row.
                            _ <- seedExecution(store, eid, wfId, Flow.Status.Running, theHash)
                            _ <- seedWaits(
                                store,
                                eid,
                                wfId,
                                theHash,
                                ("approval", Flow.Wake.OnField("approval")),
                                ("deadline", Flow.Wake.At(deadline))
                            )
                            // The engine is built once the row is already parked. See [[seedWaits]].
                            _ <- FlowEngine.init(store, FlowEngine.Config(workerCount = 1, pollTimeout = 100.millis), flow)
                            resolved <- settle(tc, step = 100.millis, maxRounds = 100)(
                                store.getExecution(eid).map(_.exists(_.status.isTerminal))
                            )
                            state <- store.getExecution(eid)
                        yield assert(
                            resolved,
                            s"the deadline passed, so the race should have resolved, got ${state.map(_.status)}"
                        )
                        end for
                    }
                }
            }
        }

        /** A GUARD: a crash after a race is decided leaves the execution claimable.
          *
          * The wait ledger is what makes this delicate. Rows are cleared by progress and retired by `finish`, an attempt that dies
          * calls neither, and a readiness rule that always gated on rows would demand satisfaction of the decided race's loser row
          * (an `OnField` for an input the flow has already chosen not to wait for) before granting the claim that is the only way
          * to retire it: circular and permanent, from an ordinary crash. The readiness rule's active-and-expired arm is what keeps
          * that from happening: a claim that expired without `finish` returns the execution regardless of its rows, and the
          * recovering attempt's replay and `finish` heal the ledger. The scenario is seeded like the leaf above, and for the same
          * reason: the premise is that the race was DECIDED by its deadline, which a live race only delivers on the runs where the
          * sleep won the slot.
          */
        "a crash after a race is decided leaves the execution claimable" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    val wfId    = Flow.Id.Workflow("decided-race")
                    val waiting = Flow.init("decided-race").input[String]("approval").output("answer")(ctx => ctx.approval)
                    val timeout = Flow.init("decided-race").sleep("deadline", 2.seconds).output("answer")(_ => "timed out")
                    val flow    = Flow.race(waiting, timeout).step("escalate")(_ => Async.sleep(1.hour))
                    val config = FlowEngine.Config(
                        workerCount = 1,
                        lease = 2.seconds,
                        renewEvery = 500.millis,
                        pollTimeout = 100.millis
                    )
                    def escalations(eid: Flow.Id.Execution) =
                        store.getHistory(eid, Maybe.empty, 0).map(_.events.count {
                            case Flow.Event.StepStarted(_, _, name, _, _) => name == "escalate"
                            case _                                        => false
                        })
                    Clock.now.map { t0 =>
                        val eid      = Flow.Id.Execution("decided-race-1")
                        val deadline = t0 + 2.seconds
                        val theHash  = kyo.internal.WorkflowSchema.structuralHash(flow)
                        for
                            _ <- seedExecution(store, eid, wfId, Flow.Status.Running, theHash)
                            _ <- seedWaits(
                                store,
                                eid,
                                wfId,
                                theHash,
                                ("approval", Flow.Wake.OnField("approval")),
                                ("deadline", Flow.Wake.At(deadline))
                            )
                            _ <- Scope.run {
                                FlowEngine.init(store, config, flow).map { _ =>
                                    settle(tc, step = 250.millis, maxRounds = 60)(escalations(eid).map(_ >= 1))
                                }
                            }
                            answer          <- store.getField[String](eid, "answer")
                            before          <- escalations(eid)
                            stateAfterClose <- store.getExecution(eid)
                            _               <- tc.advance(10.seconds)
                            _ <- Scope.run {
                                FlowEngine.init(store, config, flow).map { _ =>
                                    settle(tc, step = 250.millis, maxRounds = 60)(escalations(eid).map(_ > before))
                                }
                            }
                            after <- escalations(eid)
                        yield
                            assert(
                                answer == Maybe("timed out") && before >= 1,
                                s"the premise is that the race was decided by its deadline and the next step was mid-flight " +
                                    s"at the crash, got answer=$answer, escalate starts=$before"
                            )
                            assert(
                                !stateAfterClose.exists(_.status.isTerminal),
                                s"a crash is not a verdict: the execution must not be terminal, got ${stateAfterClose.map(_.status)}"
                            )
                            assert(
                                after > before,
                                s"a crashed execution with a decided race must be claimable once its lease lapses, whatever " +
                                    s"rows the race left behind: escalate ran $after time(s) against $before before the crash"
                            )
                        end for
                    }
                }
            }
        }

        /** A GUARD, not a defect: when both branches of a live race park, both waits reach the store.
          *
          * The leaf above seeds its durable state and says why in its own comment. This one drives the live path, and it exists
          * because the property it pins is load-bearing for the wait-ledger design and is not obvious from reading `onRace`.
          *
          * Reading `onRace` suggests the opposite. `onZip` wraps each branch in `Abort.run[FlowSuspension]` INSIDE that branch's own
          * fiber (`internal/StoreInterpreter.scala:184-188`), so a suspension is a value there and cannot disturb a sibling.
          * `onRace` has no such wrapper (`:212-222`), which reads as: the first branch to suspend finishes its fiber, wins, and gets
          * its sibling interrupted before the sibling records anything. That reading is WRONG, and this leaf is what makes the
          * correct one checkable. `Fiber.internal.race` is `Race.success` (`kyo-core/.../Fiber.scala:780`), whose completion rule
          * gives a success the race immediately but completes on an error only `if last` (`:817-824`). A suspension is an error, so
          * it resolves nothing while a sibling is pending: the sibling runs on and records its own wait.
          *
          * So the durable-timeout idiom is not broken here, and the design needs no interpreter rule to make a parked branch survive
          * a race, because the runtime already gives it one. The other half is a separate obligation: when a race is decided, the
          * loser's recorded wait has to be retired. Under one status cell that is invisible, because the winner's next write
          * overwrites it; under a ledger it is a row that outlives its branch.
          *
          * Looped eight times because it is an interleaving, and asserted as a COUNT so a regression that breaks it half the time
          * reports how partial it is rather than passing on a lucky run. It asserts on the durable RECORD rather than on a wake-up:
          * asserting "the deadline eventually wakes it" would also depend on the claim cycle for a non-progressing execution being
          * rate-limited, which it is not, so a leaf written that way advances enough virtual time to trip that second defect and
          * dies on the suite's thirty second limit, reporting a timeout that names nothing.
          */
        "a race driven live records the wait of every branch that parks" in {
            withEngine { (engine, store, tc) =>
                val rounds  = 8
                val wfId    = Flow.Id.Workflow("live-race")
                val waiting = Flow.init("live-race").input[String]("approval").output("answer")(ctx => ctx.approval)
                val timeout = Flow.init("live-race").sleep("deadline", 5.seconds).output("answer")(_ => "timed out")
                val flow    = Flow.race(waiting, timeout)
                def kinds(page: FlowStore.HistoryPage): Set[Flow.EventKind] = page.events.map(_.kind).toSet
                for
                    _ <- engine.register(wfId, flow)
                    both <- Kyo.foldLeft(Seq.range(0, rounds))(0) { (count, _) =>
                        for
                            handle <- engine.workflows.start(wfId)
                            eid = handle.executionId
                            _ <- settle(tc, step = 50.millis, maxRounds = 20)(
                                store.getHistory(eid, Maybe.empty, 0).map(p =>
                                    kinds(p).contains(Flow.EventKind.InputWaiting) ||
                                        kinds(p).contains(Flow.EventKind.SleepStarted)
                                )
                            )
                            page <- store.getHistory(eid, Maybe.empty, 0)
                            recorded = kinds(page)
                        yield count + (if recorded.contains(Flow.EventKind.InputWaiting) && recorded.contains(Flow.EventKind.SleepStarted)
                                       then 1
                                       else 0)
                    }
                yield assert(
                    both == rounds,
                    s"both branches park, so both waits must be recorded, got $both of $rounds runs recording both"
                )
                end for
            }
        }

        /** A failing branch beside a suspended one runs its retry schedule once.
          *
          * If a zip's suspension outranked its sibling's failure, the failure would be deferred until the other branch finishes, and
          * the failing branch would be re-executed from the start on every intermediate resume, with its full retry schedule and
          * every side effect that goes with it, rather than once.
          */
        "a failing branch beside a suspended one runs its retry schedule once" in {
            withEngine { (engine, store, tc) =>
                AtomicInt.init(0).map { attempts =>
                    val failing = Flow.init("zip-failure")
                        .output("charge", retry = Maybe(Schedule.fixed(10.millis).repeat(2))) { _ =>
                            val body: String < (Sync & Abort[FlowException]) =
                                attempts.incrementAndGet.andThen(Abort.fail(FlowEngineTest.ChargeDeclined("o1", 1L)))
                            body
                        }
                    val waiting = Flow.init("zip-failure").input[String]("approval").output("answer")(ctx => ctx.approval)
                    val flow    = failing.zip(waiting)
                    for
                        _      <- engine.register(wf1, flow)
                        handle <- engine.workflows.start(wf1)
                        eid = handle.executionId
                        _     <- Kyo.foreachDiscard(1 to 100)(_ => tc.advance(10.millis))
                        first <- attempts.get
                        _     <- Abort.run[Throwable](engine.executions.signal[String](eid, "approval", "yes"))
                        _     <- Kyo.foreachDiscard(1 to 100)(_ => tc.advance(10.millis))
                        total <- attempts.get
                    yield assert(
                        total <= 3,
                        s"the failing branch should run its schedule once (3 attempts), it ran $total times " +
                            s"($first of them before the input arrived)"
                    )
                    end for
                }
            }
        }

        /** A zip branch's failure decides the zip immediately.
          *
          * `zip` requires every branch, so once one branch fails the composition's result is unreachable and waiting on the other
          * branch buys nothing: the flow must reach terminal `Failed` while the sibling's input is still pending, not park until
          * the answer to a question that no longer matters arrives. Let suspension outrank failure in the join and the failed
          * branch is discarded and re-run from the start on every resume while the execution sits waiting on the input forever. The
          * join decides on the failure, and the parked sibling's rows are retired by the transition that ends the attempt
          * (`internal/StoreInterpreter.scala`'s `onZip`). The rule is the opposite of the race's, where a parked sibling can still
          * win the composition; here it cannot.
          */
        "a zip branch that fails beside a parked sibling fails the flow" in {
            withEngine { (engine, store, tc) =>
                val failing = Flow.init("zip-fail-fast").output("charge") { _ =>
                    throw new RuntimeException("charge declined"); 0
                }
                val waiting = Flow.init("zip-fail-fast").input[String]("approval").output("answer")(ctx => ctx.approval)
                val flow    = failing.zip(waiting)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    settled <- settle(tc)(store.getExecution(eid).map(_.exists(_.status.isTerminal)))
                    after   <- store.getExecution(eid)
                    _       <- Abort.run[Throwable](engine.executions.signal[String](eid, "approval", "too late"))
                    _       <- Kyo.foreachDiscard(1 to 50)(_ => tc.advance(10.millis))
                    finalSt <- store.getExecution(eid)
                yield
                    assert(
                        settled && after.exists(s =>
                            s.status match
                                case Flow.Status.Failed(_, _) => true
                                case _                        => false
                        ),
                        s"a zip whose branch failed is unreachable and must fail without waiting on the sibling, " +
                            s"got ${after.map(_.status)}"
                    )
                    assert(
                        !finalSt.exists(_.status == Flow.Status.Completed),
                        s"a late input must not resurrect a zip a failure already decided, got ${finalSt.map(_.status)}"
                    )
                end for
            }
        }

        /** The failure is painted on the branch that failed, not on whichever node the walk reaches first.
          *
          * **The discriminating order is the whole point of the fixture.** The waiting branch is zipped FIRST, so its `approval`
          * input is the first node that is neither completed nor waiting once the terminal ending retires every row. A surface that
          * decided the failed node by position would paint that input, telling an operator a node failed that never ran, while the
          * node that actually threw reads pending beside it. Reversing the two branches would hide the defect, because then the guess
          * and the record agree by accident.
          *
          * Both directions are asserted, since painting the right node matters only if the wrong one stays unpainted.
          */
        "a failed zip branch is the node reported failed, not the sibling the walk reaches first" in {
            withEngine { (engine, store, tc) =>
                val failing = Flow.init("zip-fail-paint").output("charge") { _ =>
                    throw new RuntimeException("charge declined"); 0
                }
                val waiting = Flow.init("zip-fail-paint").input[String]("approval").output("answer")(ctx => ctx.approval)
                val flow    = waiting.zip(failing)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    settled <- settle(tc)(store.getExecution(eid).map(_.exists(_.status.isTerminal)))
                    detail  <- engine.executions.describe(eid)
                yield
                    assert(settled, "the premise is that the zip failed and the execution is over")
                    assert(
                        detail.progress.nodeByName("charge").map(_.status) ==
                            Maybe(FlowEngine.Progress.NodeStatus.Failed("charge declined")),
                        s"the branch that threw must be the one reported failed, got ${detail.progress.nodeByName("charge").map(_.status)}"
                    )
                    assert(
                        detail.progress.nodeByName("approval").map(_.status) == Maybe(FlowEngine.Progress.NodeStatus.Pending),
                        s"and the input nobody delivered must not be reported failed, got " +
                            s"${detail.progress.nodeByName("approval").map(_.status)}"
                    )
                end for
            }
        }

        /** A sleep that was not the one woken keeps its original deadline.
          *
          * A resumed attempt replays every branch, and the branch that was not woken reaches its sleep node again with no completion
          * event to skip on, so it re-records the wait. Recomputing `until` as `now + duration` there would make a branch sleeping an
          * hour alongside a branch sleeping a minute wait an hour from the minute mark rather than an hour from the start, compounding
          * on every pass of a loop.
          *
          * What prevents it is a rule of the row rather than of the caller: a wait row is written only if the path has none
          * (`internal/MemoryFlowStore.scala`'s `recordWait`), so the deadline a sleep is first given is the deadline it keeps, however
          * many attempts replay past it. The event is appended either way, because a replay that re-records is something the history
          * should show.
          */
        "a sleep that was not woken keeps its original deadline" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    val wfId  = Flow.Id.Workflow("two-sleeps")
                    val left  = Flow.init("two-sleeps").sleep("a", 10.seconds).output("l")(_ => "l")
                    val right = Flow.init("two-sleeps").sleep("b", 1.seconds).output("r")(_ => "r")
                    val flow  = left.zip(right)
                    Clock.now.map { t0 =>
                        val eid     = Flow.Id.Execution("two-sleeps-1")
                        val untilA  = t0 + 10.seconds
                        val untilB  = t0 + 1.seconds
                        val theHash = kyo.internal.WorkflowSchema.structuralHash(flow)
                        for
                            // Both branches' rows, because both branches parked: seeding one and leaving the other as a bare
                            // history event describes a state no correct attempt produces.
                            _ <- seedExecution(store, eid, wfId, Flow.Status.Running, theHash)
                            _ <- seedWaits(store, eid, wfId, theHash, ("a", Flow.Wake.At(untilA)), ("b", Flow.Wake.At(untilB)))
                            // The engine is built once the row is already parked. See [[seedWaits]]: a poller and this fixture are
                            // two claimants of one row, and the order is what keeps them from being competitors.
                            _ <- FlowEngine.init(store, FlowEngine.Config(workerCount = 1, pollTimeout = 100.millis), flow)
                            // The woken branch's own progress, which is the observable that survives: the row it was woken by is
                            // discharged and gone, while the branch it woke runs on to its output.
                            reached <- settle(tc, step = 100.millis, maxRounds = 50) {
                                store.getField[String](eid, "r").map(_.isDefined)
                            }
                            state <- store.getExecution(eid)
                        yield
                            assert(reached, s"the shorter sleep should have woken its branch, got ${state.map(_.waits)}")
                            deadlineOf(state.get, "a") match
                                case Present(until) =>
                                    assert(
                                        until == untilA,
                                        s"the sleep that was not woken must keep the deadline it was started with, " +
                                            s"expected $untilA but the execution now sleeps until $until"
                                    )
                                case Absent =>
                                    assert(false, s"expected the execution to still be sleeping on 'a', got ${state.map(_.waits)}")
                            end match
                        end for
                    }
                }
            }
        }

        /** An execution waiting on an input AND a sleep is woken by the sleep.
          *
          * With both branches suspended and the input wait recorded last, the sleep's deadline is no longer anything the store can
          * see: readiness for `WaitingForInput` is the presence of the field, so the execution waits for an input that may never
          * come while its own timer passes unnoticed.
          */
        "a sleep still wakes an execution whose status records an input wait" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    val wfId  = Flow.Id.Workflow("input-and-sleep")
                    val left  = Flow.init("input-and-sleep").input[String]("x").output("l")(ctx => ctx.x)
                    val right = Flow.init("input-and-sleep").sleep("b", 1.seconds).output("r")(_ => "r")
                    val flow  = left.zip(right)
                    Clock.now.map { t0 =>
                        val eid     = Flow.Id.Execution("input-and-sleep-1")
                        val untilB  = t0 + 1.seconds
                        val theHash = kyo.internal.WorkflowSchema.structuralHash(flow)
                        for
                            // Both branches' rows, for the same reason as the leaf above.
                            _ <- seedExecution(store, eid, wfId, Flow.Status.Running, theHash)
                            _ <- seedWaits(store, eid, wfId, theHash, ("x", Flow.Wake.OnField("x")), ("b", Flow.Wake.At(untilB)))
                            // The engine is built once the row is already parked. See [[seedWaits]].
                            _ <- FlowEngine.init(store, FlowEngine.Config(workerCount = 1, pollTimeout = 100.millis), flow)
                            // The sleeping branch's own progress: the output past the sleep it was woken by.
                            woken <- settle(tc, step = 100.millis, maxRounds = 50)(
                                store.getField[String](eid, "r").map(_.isDefined)
                            )
                            state <- store.getExecution(eid)
                        yield assert(
                            woken,
                            s"a sleep whose deadline passed must still wake the execution, which is stuck at " +
                                s"${state.map(_.waits)} with no input coming"
                        )
                        end for
                    }
                }
            }
        }

        /** The mirror of the leaf above: an execution waiting on an input AND a sleep is woken by the input.
          *
          * With the sleep's wait recorded last, a signal that arrives on day one writes its field and wakes nothing: readiness
          * for `Sleeping` is the deadline and only the deadline, so the answer sits delivered while the execution sleeps out
          * its full timeout, then resolves the race for the input it should have taken days earlier. The result is right and
          * the wake is silently late, which is why this direction needs its own leaf: the loud direction (the timeout never
          * firing at all) is the leaf above, and a fix that carries only the winning branch's wait passes exactly one of the
          * two.
          */
        "an input still wakes an execution whose status records a sleep" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    val wfId  = Flow.Id.Workflow("sleep-and-input")
                    val left  = Flow.init("sleep-and-input").input[String]("x").output("l")(ctx => ctx.x)
                    val right = Flow.init("sleep-and-input").sleep("b", 5.days).output("r")(_ => "r")
                    val flow  = left.zip(right)
                    Clock.now.map { t0 =>
                        val eid     = Flow.Id.Execution("sleep-and-input-1")
                        val untilB  = t0 + 5.days
                        val theHash = kyo.internal.WorkflowSchema.structuralHash(flow)
                        for
                            // Both branches' rows, for the same reason as the leaf above.
                            _         <- seedExecution(store, eid, wfId, Flow.Status.Running, theHash)
                            _         <- seedWaits(store, eid, wfId, theHash, ("x", Flow.Wake.OnField("x")), ("b", Flow.Wake.At(untilB)))
                            now       <- Clock.now
                            delivered <- store.signal(eid, "x", "answered", Flow.Event.InputReceived(wfId, eid, "x", now))
                            // The engine is built once the row is already parked. See [[seedWaits]].
                            _ <- FlowEngine.init(store, FlowEngine.Config(workerCount = 1, pollTimeout = 100.millis), flow)
                            // The input branch's own progress: the output past the input it was woken by.
                            woken <- settle(tc, step = 100.millis, maxRounds = 50)(
                                store.getField[String](eid, "l").map(_.isDefined)
                            )
                            state <- store.getExecution(eid)
                        yield
                            assert(
                                delivered == FlowStore.SignalOutcome.Delivered,
                                "the premise is that the input was delivered while the sleep was pending"
                            )
                            assert(
                                woken,
                                s"a delivered input must wake the execution promptly, not after the sleep runs out: " +
                                    s"stuck at ${state.map(_.waits)} with its answer sitting delivered"
                            )
                        end for
                    }
                }
            }
        }

        /** An engine that goes away between two parking branches still honours the sleep the second one was about to record.
          *
          * The window only exists because each branch records its own wait: the first branch's row is written, and the engine's scope
          * closes before the second's is. The rows the attempt left are therefore NOT a finished statement of what the execution waits
          * for, and the claim says so, because the transition that ends an attempt is the only thing that frees one. Readiness reads
          * that: an active claim that has expired returns the execution regardless of its rows, so the replay re-records the sleep and
          * the durable timeout still fires. A rule that gated on the rows instead would wait forever on an input nobody is going to
          * send, with the deadline it was racing lost in the window.
          */
        "an engine shut down between two parking branches still honours the sleep" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    val wfId    = Flow.Id.Workflow("half-parked")
                    val waiting = Flow.init("half-parked").input[String]("approval").output("answer")(ctx => ctx.approval)
                    val timeout = Flow.init("half-parked").sleep("deadline", 30.seconds).output("answer")(_ => "timed out")
                    val flow    = Flow.race(waiting, timeout)
                    val theHash = kyo.internal.WorkflowSchema.structuralHash(flow)
                    val config  = FlowEngine.Config(workerCount = 1, lease = 5.seconds, renewEvery = 1.second, pollTimeout = 100.millis)
                    Clock.now.map { t0 =>
                        val eid = Flow.Id.Execution("half-parked-1")
                        for
                            // The first engine's attempt died with the input's row written, the sleep's not, and the claim still
                            // named: exactly what a shutdown between the two branches leaves behind.
                            _ <- seedExecution(store, eid, wfId, Flow.Status.Running, theHash)
                            claimed <-
                                store.claimReady(Set((wfId, theHash)), Flow.Id.Executor("first-engine"), 5.seconds, 10, Duration.Zero)
                            _ = assert(claimed.size == 1, "the premise is that an executor held the execution when it died")
                            now <- Clock.now
                            _ <- claimed.head.recordWait(
                                "approval",
                                Flow.Wake.OnField("approval"),
                                Flow.Event.InputWaiting(wfId, eid, "approval", now)
                            )
                            // The first engine's claim lapses, which is what tells the next one its ledger was never finished.
                            _ <- tc.advance(10.seconds)
                            _ <- Scope.run {
                                FlowEngine.init(store, config, flow).map { _ =>
                                    settle(tc, step = 1.second, maxRounds = 120)(
                                        store.getExecution(eid).map(_.exists(_.status.isTerminal))
                                    )
                                }
                            }
                            state  <- store.getExecution(eid)
                            answer <- store.getField[String](eid, "answer")
                        yield
                            assert(
                                answer == Maybe("timed out"),
                                s"the sleep the shutdown swallowed must still fire, got $answer with ${state.map(_.waits)}"
                            )
                            assert(
                                state.exists(_.status == Flow.Status.Completed),
                                s"and the execution must finish on it, got ${state.map(_.status)}"
                            )
                        end for
                    }
                }
            }
        }

        /** The same window, driven rather than seeded, and what the engine writes when it closes on one: nothing.
          *
          * The leaf above seeds the state a mid-park shutdown leaves and asserts that readiness heals it. This one produces the state:
          * a live engine stopped with one branch's row written and the other's call suspended inside the store, so the window is
          * driven rather than described, and the recovery that follows is the same recovery a real deployment gets.
          *
          * What the shutdown itself writes is nothing, in both halves. The closing scope interrupts the fiber supervising the attempt
          * along with the attempt, so no ending transition runs: no status, no event, and no release, which leaves the claim named and
          * unexpired at the moment the engine goes away. That is what makes the leftover row untrustworthy and the lapsed claim the
          * only thing that says so, which is the rule the recovery below depends on. The gate that keeps a VERDICT-less ending from
          * releasing is pinned separately, on "an executor that has lost its claim writes nothing more", where the supervisor outlives
          * the work it stopped and a release would therefore be reachable.
          */
        "an engine stopped between two parking branches releases nothing and still honours the sleep" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { plain =>
                    AtomicInt.init(0).map { calls =>
                        Channel.init[Unit](1).map { parked =>
                            val store   = new ParkedWaitStore(plain, calls, parked)
                            val wfId    = Flow.Id.Workflow("half-parked-live")
                            val waiting = Flow.init("half-parked-live").input[String]("approval").output("answer")(ctx => ctx.approval)
                            val timeout = Flow.init("half-parked-live").sleep("deadline", 30.seconds).output("answer")(_ => "timed out")
                            val flow    = Flow.race(waiting, timeout)
                            val config =
                                FlowEngine.Config(workerCount = 1, lease = 5.seconds, renewEvery = 1.second, pollTimeout = 100.millis)
                            for
                                eid <- Scope.run {
                                    FlowEngine.init(store, config, flow).map { engine =>
                                        for
                                            handle <- engine.workflows.start(wfId)
                                            eid = handle.executionId
                                            // One branch's row is written and the other's call is suspended in the store, so the
                                            // scope closing below is what ends the attempt, with its ledger half stated.
                                            reached <- settle(tc)(calls.get.map(_ >= 2))
                                            _ = assert(
                                                reached,
                                                "the premise is that one branch recorded its wait and the second reached the store"
                                            )
                                        yield eid
                                    }
                                }
                                held <- store.getExecution(eid)
                                _    <- tc.advance(10.seconds)
                                _ <- Scope.run {
                                    FlowEngine.init(store, config, flow).map { _ =>
                                        settle(tc, step = 1.second, maxRounds = 120)(
                                            store.getExecution(eid).map(_.exists(_.status.isTerminal))
                                        )
                                    }
                                }
                                state  <- store.getExecution(eid)
                                answer <- store.getField[String](eid, "answer")
                            yield
                                assert(
                                    held.exists(_.waits.size == 1),
                                    s"the premise is one branch's row written and one never landed, got ${held.map(_.waits)}"
                                )
                                assert(
                                    held.exists(s => s.executor.isDefined && s.claimExpiry.isDefined),
                                    s"an interrupted ending releases nothing, so the claim must still be held when the engine " +
                                        s"closes, got executor=${held.map(_.executor)} expiry=${held.map(_.claimExpiry)}"
                                )
                                assert(
                                    answer == Maybe("timed out"),
                                    s"the sleep the shutdown swallowed must still fire, got $answer with ${state.map(_.waits)}"
                                )
                                assert(
                                    state.exists(_.status == Flow.Status.Completed),
                                    s"and the execution must finish on it, got ${state.map(_.status)}"
                                )
                            end for
                        }
                    }
                }
            }
        }

        /** A race's losing branch does not leave a row behind the attempt that re-recorded it.
          *
          * Nothing discharges a branch that was decided against: it was abandoned rather than satisfied, so no progress write clears
          * its row, and the general clearing rule does not reach it. What removes it is the transition that ends the attempt, which
          * keeps exactly the parks the execution is still waiting on. A leftover row is not inert: the moment it becomes satisfiable
          * it makes the execution ready again with nothing to do, which is the poll spin the ledger exists to close.
          */
        "a decided race's loser row does not outlive the attempt that re-recorded it" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    val wfId    = Flow.Id.Workflow("loser-row")
                    val waiting = Flow.init("loser-row").input[String]("approval").output("answer")(ctx => ctx.approval)
                    val timeout = Flow.init("loser-row").sleep("deadline", 5.seconds).output("answer")(_ => "timed out")
                    // A race decided by its deadline, and then a wait downstream, so the attempt ends parked on something ELSE:
                    // the loser's row can only be removed by the ending's retirement, never by the next park's own write.
                    val downstream = Flow.init("loser-row").input[String]("ack").output("done")(ctx => ctx.ack)
                    val flow       = Flow.race(waiting, timeout).andThen(downstream)
                    val theHash    = kyo.internal.WorkflowSchema.structuralHash(flow)
                    Clock.now.map { t0 =>
                        val eid = Flow.Id.Execution("loser-row-1")
                        for
                            _ <- seedExecution(store, eid, wfId, Flow.Status.Running, theHash)
                            _ <- seedWaits(
                                store,
                                eid,
                                wfId,
                                theHash,
                                ("approval", Flow.Wake.OnField("approval")),
                                ("deadline", Flow.Wake.At(t0 + 5.seconds))
                            )
                            // The engine is built once the row is already parked. See [[seedWaits]].
                            _ <- FlowEngine.init(store, FlowEngine.Config(workerCount = 1, pollTimeout = 100.millis), flow)
                            parked <- settle(tc, step = 100.millis, maxRounds = 100)(
                                store.getExecution(eid).map(_.exists(waitingFor("ack")))
                            )
                            state <- store.getExecution(eid)
                        yield
                            assert(
                                parked,
                                s"the premise is that the race resolved and the execution parked downstream, got ${state.map(_.waits)}"
                            )
                            assert(
                                !state.exists(_.waits.contains("approval")),
                                s"the losing branch's row must not outlive the attempt that decided against it, got ${state.map(_.waits)}"
                            )
                        end for
                    }
                }
            }
        }

        /** Every parked branch of a nested composition wakes on its own condition.
          *
          * Two branches are not enough to see this: a merge that flattens one level satisfies the two-branch leaves above and still
          * loses a branch one level down, because the composition that reaches the engine is a race of a zip. Three branches with
          * three different conditions, asserted in both directions, is what distinguishes a recursive merge from a single-level one.
          */
        "every parked branch of a nested composition wakes on its own condition" in {
            withEngine { (engine, store, tc) =>
                val wfId       = Flow.Id.Workflow("nested-parks")
                val innerInput = Flow.init("nested-parks").input[String]("inner").output("answer")(ctx => ctx.inner)
                val innerSleep = Flow.init("nested-parks").sleep("deadline", 5.seconds).output("slept")(_ => "slept")
                val outerInput = Flow.init("nested-parks").input[String]("outer").output("answer")(ctx => ctx.outer)
                val flow       = Flow.race(innerInput.zip(innerSleep), outerInput)
                def parkedOnAll(eid: Flow.Id.Execution)(using Frame) =
                    settle(tc, step = 100.millis, maxRounds = 100) {
                        store.getExecution(eid).map(_.exists(s =>
                            waitingFor("inner")(s) && waitingFor("outer")(s) && sleeping(s)
                        ))
                    }
                def start(using Frame) =
                    engine.workflows.start(wfId).map(_.executionId).map(eid => parkedOnAll(eid).map(all => (eid, all)))
                for
                    _ <- engine.register(wfId, flow)
                    // One execution per direction, because a wake resolves the composition it woke.
                    innerRun <- start
                    sleepRun <- start
                    outerRun <- start
                    _ = assert(
                        innerRun._2 && sleepRun._2 && outerRun._2,
                        "the premise is that all three branches parked, each recording its own wait"
                    )
                    // The zip's input branch, one level down inside the race.
                    _ <- engine.executions.signal[String](innerRun._1, "inner", "from-inner")
                    innerWoke <- settle(tc, step = 100.millis, maxRounds = 100)(
                        store.getField[String](innerRun._1, "answer").map(_.contains("from-inner"))
                    )
                    // The zip's sleep branch, the other one level down.
                    _ <- Kyo.foreachDiscard(1 to 100)(_ => tc.advance(100.millis))
                    sleepWoke <- settle(tc, step = 100.millis, maxRounds = 100)(
                        store.getField[String](sleepRun._1, "slept").map(_.isDefined)
                    )
                    // The race's own branch, at the top level.
                    _ <- engine.executions.signal[String](outerRun._1, "outer", "from-outer")
                    outerWoke <- settle(tc, step = 100.millis, maxRounds = 100)(
                        store.getExecution(outerRun._1).map(_.exists(_.status == Flow.Status.Completed))
                    )
                    innerState <- store.getExecution(innerRun._1)
                    sleepState <- store.getExecution(sleepRun._1)
                    outerState <- store.getExecution(outerRun._1)
                yield
                    assert(
                        innerWoke,
                        s"the input nested inside the zip must wake its own branch, got ${innerState.map(_.waits)}"
                    )
                    assert(
                        sleepWoke,
                        s"the sleep nested inside the zip must wake its own branch, got ${sleepState.map(_.waits)}"
                    )
                    assert(
                        outerWoke,
                        s"the race's own input branch must wake the composition, got ${outerState.map(_.status)}"
                    )
                end for
            }
        }

        /** A settled execution costs nothing while it waits.
          *
          * The end-to-end half of the clearing rule. A satisfied wait that is never removed keeps the execution ready forever after the
          * branch that recorded it has moved on: every poll claims it, the replay finds nothing to do, the claim is released, and the
          * next poll claims it again at full speed, writing a claim and a release into the history each time. The execution looks
          * perfectly healthy the whole while, which is why the observable is the history's own size across two equal idle stretches
          * rather than anything about the rows.
          *
          * The input kind is what this drives, because an input's discharge is the one progress a node records with no value of its
          * own: a satisfied input proceeds with the value already in its record, so the row it wrote while parking is cleared by that
          * transition or by nothing at all.
          */
        "a settled execution's history does not grow while it waits" in {
            withEngine { (engine, store, tc) =>
                val wfId = Flow.Id.Workflow("settled")
                val flow = Flow.init("settled")
                    .input[String]("first")
                    .step("between")(_ => ())
                    .input[String]("second")
                    .output("done")(ctx => ctx.second)
                def idle(using Frame): Unit < (Async & Abort[FlowStoreException]) =
                    Kyo.foreachDiscard(1 to 100)(_ => tc.advance(10.millis))
                for
                    _      <- engine.register(wfId, flow)
                    handle <- engine.workflows.start(wfId)
                    eid = handle.executionId
                    _ <- pumpState(tc, store, eid, waitingFor("first"))
                    // The first wait is satisfied and consumed; the execution then settles on the second.
                    _      <- engine.executions.signal[String](eid, "first", "go")
                    _      <- pumpState(tc, store, eid, waitingFor("second"))
                    _      <- idle
                    first  <- store.getHistory(eid, Maybe.empty, 0)
                    _      <- idle
                    second <- store.getHistory(eid, Maybe.empty, 0)
                    state  <- store.getExecution(eid)
                yield
                    assert(
                        second.events.size == first.events.size,
                        s"a settled execution must not be re-claimed: history grew from ${first.events.size} to " +
                            s"${second.events.size}, ending ${second.events.drop(first.events.size).map(_.kind)}"
                    )
                    assert(
                        state.exists(_.waits.toChunk.map(_._1).toSet == Set("second")),
                        s"the discharged wait must be gone and the outstanding one kept, got ${state.map(_.waits)}"
                    )
                    assert(
                        state.exists(_.executor.isEmpty),
                        s"and nothing must be holding a claim on it, got ${state.map(_.executor)}"
                    )
                end for
            }
        }
    }

    /** Defects reachable through the ordinary public API that no group above covers.
      *
      * Each leaf here pins one behaviour the module documents or implies and does not deliver. They are grouped together because they
      * cut across the engine, the interpreter, and the flow DSL rather than belonging to any single one.
      */
    "contract gaps" - {

        /** A wait is recorded only by an executor that holds the claim.
          *
          * Neither durable-wait writer decides this for itself: `onInput` and `onSleep` both record through `claimed.recordWait`
          * under `fenced` (`internal/StoreInterpreter.scala`), so the write is applied only under a claim the store still regards as
          * active, unexpired, and of the generation presented, and a refusal ends the attempt instead. A writer that checked the
          * claim itself, or read the row and decided from what it saw, would be deciding on a snapshot the store may already have
          * superseded. It is also the write that most directly steers the future, which is why it is the one pinned here: a wait row
          * is what readiness reads.
          */
        "an executor that does not hold the claim does not record a wait" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { plain =>
                    AtomicBoolean.init(false).map { armed =>
                        AtomicInt.init(0).map { refusals =>
                            Channel.init[Unit](1).map { parked =>
                                val leaseSpan = 5.seconds
                                // The claim dies the moment the attempt announces itself, so everything the interpreter writes
                                // from there on is presented under a generation the store has stopped honouring. There is no read
                                // left to lie to, so the lapse is produced rather than reported.
                                val store = new LapsingClaimStore(plain, tc, leaseSpan, armed, refusals, parked)({
                                    case _: Flow.Event.ExecutionResumed => true
                                    case _                              => false
                                })
                                val wfId = Flow.Id.Workflow("unfenced-wait")
                                val flow = Flow.init("unfenced-wait").input[String]("x").output("done")(ctx => ctx.x)
                                val config = FlowEngine.Config(
                                    workerCount = 1,
                                    lease = leaseSpan,
                                    renewEvery = 1.second,
                                    pollTimeout = 100.millis
                                )
                                for
                                    eid <- Scope.run {
                                        FlowEngine.init(store, config, flow).map { engine =>
                                            for
                                                handle <- engine.workflows.start(wfId)
                                                eid = handle.executionId
                                                refused <- settle(tc)(refusals.get.map(_ > 0))
                                                _ = assert(
                                                    refused,
                                                    "the premise is that the lapsed executor presented a write and was refused"
                                                )
                                            yield eid
                                        }
                                    }
                                    page <- plain.getHistory(eid, Maybe.empty, 0)
                                    waits = page.events.count(_.kind == Flow.EventKind.InputWaiting)
                                yield assert(
                                    waits == 0,
                                    s"the store reports the row as another executor's, so no wait may be recorded under this one, got $waits"
                                )
                                end for
                            }
                        }
                    }
                }
            }
        }

        /** An executor that has lost its claim mid-step records nothing further, retries included.
          *
          * Bracketing a step with a claim check leaves everything between the two checks unguarded, and `withTimeoutAndRetry` lives
          * exactly there, appending `StepTimedOut` and `StepRetried` from a helper with no claim to present and no check to fail. An
          * executor whose lease is taken while its step is running then keeps writing its retry schedule into another executor's
          * execution, each retry re-running the step body with whatever side effects it has, and the trailing check catches it only
          * once the schedule is exhausted.
          *
          * Those events go through the claim like every other write: `withTimeoutAndRetry` appends them with `appendEvent`,
          * which is `claimed.appendEvent` under `fenced` (`internal/StoreInterpreter.scala`), so the FIRST refusal ends the attempt
          * rather than the last one. What this leaf pins is the outcome that follows, which is what an operator reads: a claim taken
          * as the step announces itself leaves that step's retries out of the history entirely.
          */
        "an executor that lost its claim mid-step does not record its retries" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { plain =>
                    AtomicBoolean.init(false).map { armed =>
                        AtomicInt.init(0).map { refusals =>
                            Channel.init[Unit](1).map { parked =>
                                val leaseSpan = 5.seconds
                                // The claim dies as the step starts, which is the window everything the retry helper writes lives
                                // in. The renewal parks rather than being refused, so the body keeps running under a lease it no
                                // longer holds and the writes are the only thing being refused.
                                val store = new LapsingClaimStore(plain, tc, leaseSpan, armed, refusals, parked)({
                                    case _: Flow.Event.StepStarted => true
                                    case _                         => false
                                })
                                val wfId = Flow.Id.Workflow("unfenced-retry")
                                val flow = Flow.init("unfenced-retry")
                                    .output("y", retry = Maybe(Schedule.fixed(10.millis).repeat(3)))(_ =>
                                        throw new RuntimeException("boom"); ""
                                    )
                                val config = FlowEngine.Config(
                                    workerCount = 1,
                                    lease = leaseSpan,
                                    renewEvery = 1.second,
                                    pollTimeout = 100.millis
                                )
                                for
                                    eid <- Scope.run {
                                        FlowEngine.init(store, config, flow).map { engine =>
                                            for
                                                handle <- engine.workflows.start(wfId)
                                                eid = handle.executionId
                                                refused <- settle(tc)(refusals.get.map(_ > 0))
                                                _ = assert(
                                                    refused,
                                                    "the premise is that the lapsed executor presented a write and was refused"
                                                )
                                            yield eid
                                        }
                                    }
                                    page <- plain.getHistory(eid, Maybe.empty, 0)
                                    retries = page.events.count(_.kind == Flow.EventKind.StepRetried)
                                yield assert(
                                    retries == 0,
                                    s"the claim was taken as the step started, so no retry may be recorded under it, got $retries"
                                )
                                end for
                            }
                        }
                    }
                }
            }
        }

        /** A step's value and the record that it completed are written together, or neither is written.
          *
          * Writing them as two separate SPI calls with a clock read between them, a field write and then a status write carrying the
          * completion event, lets a store fail in that window and leave the value durably present with the history saying the step
          * never finished.
          *
          * That combination is not merely untidy, it is unrecoverable in the direction that matters. `Output` decides it has already
          * run by looking for its FIELD, so the step would never re-run and never emit the missing event; but `Progress.build`
          * decides a node is completed from the HISTORY, so the operator surface would report that node as unfinished forever, for an
          * execution that in fact ran it. Two surfaces, two sources of truth, and a store contract that did not say they must agree.
          *
          * One verb carries it: `claimed.recordProgress` writes the field, appends the completion event and clears the rows under
          * that path in a single transition (`internal/StoreInterpreter.scala`'s `onOutput`). This leaf asserts the invariant rather
          * than the mechanism: after the blip, the field exists if and only if the completion event does.
          */
        "a step's field and its completion event are written together or not at all" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicInt.init(1).map { blips =>
                        val flaky = new FailingCompletionStore(store, "y", blips)
                        val flow  = Flow.init("atomic-step").output("y")(_ => 42).output("z")(ctx => ctx.y + 1)
                        FlowEngine.init(flaky, FlowEngine.Config(workerCount = 1, pollTimeout = 100.millis), flow).map { engine =>
                            for
                                handle <- engine.workflows.start(Flow.Id.Workflow("atomic-step"))
                                eid = handle.executionId
                                _ <- settle(tc, step = 100.millis, maxRounds = 50)(
                                    store.getExecution(eid).map(_.exists(_.status.isTerminal))
                                )
                                field <- store.getField[Int](eid, "y")
                                page  <- store.getHistory(eid, Maybe.empty, 0)
                                completed = page.events.exists {
                                    case Flow.Event.StepCompleted(_, _, name, _) => name == "y"
                                    case _                                       => false
                                }
                            yield assert(
                                field.isEmpty == !completed,
                                s"the value and its completion record must agree: field=$field, StepCompleted(y)=$completed"
                            )
                            end for
                        }
                    }
                }
            }
        }

        /** A stale executor's write must not land on top of the new owner's result.
          *
          * Two owners at once needs a lease that lapses WHILE its holder keeps running, which is a state a store that lies about a
          * row cannot produce: an expired executor still writing is not the same thing as one overwriting somebody else's work.
          *
          * **The instrument is a renewal that PARKS, which is the one shape that produces a stale running executor.** A renewal
          * that FAILS does not: the renewal fiber survives blips, and a failed or refused renewal interrupts the lapsed executor at
          * its next safepoint, so no stale executor built that way ever comes back. `ParkedRenewalStore` suspends the renewal
          * round-trip without answering instead: no renewal, no refusal, no interrupt, so the first engine's lease lapses
          * store-side while its step keeps running. A second engine, on the plain store, claims the row the ordinary way and
          * completes the flow. When the first engine's step finally returns, it writes its own result over the new owner's, and the
          * value is derived from having resumed past the gate precisely so a landed stale write reads "stale" rather than
          * coinciding with the owner's value.
          *
          * The assertion is the user-visible harm rather than the mechanism: the field a reader sees must be the one written by the
          * executor that actually owned the execution.
          *
          * **What keeps the owner's data safe is the STORE.** `recordProgress` answers `ClaimLost` for a write presented under a
          * generation the row has stopped honouring, and it compares GENERATIONS rather than executor identity, so it refuses a
          * second worker of the same engine just as firmly. A client-side check that read the row and compared its executor against
          * this one's would refuse the write staged here and miss that case entirely.
          *
          * **So this is a GUARD on an ordering, and the most valuable thing it can do is fail.** If the fence ever weakens, to an
          * identity comparison, to "the highest token wins", or to a check that forgets expiry, this leaf goes red and says exactly
          * what was lost.
          *
          * The premises come first, because a run where the second engine never took over, or where the stale executor's step never
          * came back, would measure nothing. The second of those needs its own counter: the entry count is satisfied by the new
          * owner's own entry, so it cannot show that the STALE executor resumed.
          */
        "a stale executor's write does not land on top of the new owner's result" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicBoolean.init(false).map { armed =>
                        AtomicInt.init(0).map { entries =>
                            AtomicInt.init(0).map { unblocked =>
                                Channel.init[Unit](1).map { gate =>
                                    Channel.init[Unit](1).map { renewals =>
                                        val parking = new ParkedRenewalStore(store, armed, renewals)
                                        val wfId    = Flow.Id.Workflow("stale-overwrite")
                                        val flow = Flow.init("stale-overwrite")
                                            .step[Async]("work") { _ =>
                                                entries.incrementAndGet.map { n =>
                                                    if n == 1 then
                                                        Abort.run[Closed](gate.take).andThen(unblocked.incrementAndGet).unit
                                                    else Kyo.unit
                                                }
                                            }
                                            .output("result") { _ =>
                                                unblocked.get.map(u => if u > 0 then "stale" else "owner")
                                            }
                                        val config = FlowEngine.Config(
                                            workerCount = 1,
                                            lease = 2.seconds,
                                            renewEvery = 100.millis,
                                            pollTimeout = 100.millis
                                        )
                                        for
                                            eid <- FlowEngine.init(parking, config, flow)([v] => (c: v < Async) => c).map { first =>
                                                first.workflows.start(wfId).map { handle =>
                                                    settle(tc, step = 50.millis, maxRounds = 60)(entries.get.map(_ >= 1))
                                                        .andThen(handle.executionId)
                                                }
                                            }
                                            _ <- armed.set(true)
                                            _ <- tc.advance(10.seconds)
                                            _ <- FlowEngine.init(store, config, flow)([v] => (c: v < Async) => c).map { second =>
                                                settle(tc, step = 250.millis, maxRounds = 80)(
                                                    store.getExecution(eid).map(_.exists(_.status.isTerminal))
                                                )
                                            }
                                            ownerValue <- store.getField[String](eid, "result")
                                            _          <- Abort.run[Closed](gate.put(()))
                                            _          <- settle(tc, step = 100.millis, maxRounds = 80)(unblocked.get.map(_ >= 1))
                                            finalValue <- store.getField[String](eid, "result")
                                            seen       <- entries.get
                                            resumed    <- unblocked.get
                                        yield
                                            assert(
                                                ownerValue == Present("owner"),
                                                s"the premise is that a second engine took the execution over and finished it, got $ownerValue"
                                            )
                                            assert(
                                                seen >= 2,
                                                s"the premise is that both executors entered the step, it was entered $seen times"
                                            )
                                            assert(
                                                resumed >= 1,
                                                s"the premise is that the STALE executor's step came back past its block, and it did not; " +
                                                    s"this counter is separate from the entry count because the second engine's own entry " +
                                                    s"satisfies that one on its own"
                                            )
                                            assert(
                                                finalValue == Present("owner"),
                                                s"a write from an executor whose lease lapsed must not replace the owner's result: the " +
                                                    s"field went from $ownerValue to $finalValue"
                                            )
                                        end for
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        /** A stale executor's failure must not appear in the history of an execution somebody else completed.
          *
          * The other side of the write fence, and the half a recover arm makes reachable: wrapping the whole resume in a recover arm
          * and writing terminal `Failed` from it puts that write outside every claim check the interpreter makes, so a step that
          * THREW reaches it directly where a step that returned normally is stopped on its way to the field.
          *
          * **Both halves are closed by one rule.** A verdict is not a free write: the ending goes through the claim
          * (`FlowEngine.scala`'s `finish`, which appends `ExecutionReleased` and then calls `claimed.finish`), so a stale executor's
          * `Failed` is judged by the acceptance rule like everything else and lands nowhere at all. `FlowStoreTest` pins the
          * store-level half: a terminal status is refused WHOLE, status, history and rows together, rather than the status being
          * refused and the event appended anyway. That half is worth its own leaf because history is what the operator surfaces
          * read, and a failed step has to be identifiable from history alone.
          *
          * The instrument is the same conforming one the sibling leaf uses, and for the same reason: a renewal that PARKS is the one
          * shape that still produces a stale RUNNING executor. A refused renewal interrupts the attempt at its next safepoint, so
          * the blocked step never comes back at all and the leaf would measure nothing; a parked one neither renews nor refuses, the
          * lease lapses store-side, and the executor keeps going under a claim it no longer holds. A second engine then claims and
          * completes, and only then is the first engine's step released, this time to throw.
          *
          * The premises come first: the second engine really completed it, and the stale executor's step really came back past its
          * block, which needs its own counter because the new owner's own entry satisfies the entry count.
          */
        "a stale executor's failure does not appear in the history of a completed execution" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicBoolean.init(false).map { armed =>
                        AtomicInt.init(0).map { entries =>
                            AtomicInt.init(0).map { unblocked =>
                                Channel.init[Unit](1).map { gate =>
                                    Channel.init[Unit](1).map { renewals =>
                                        val parking = new ParkedRenewalStore(store, armed, renewals)
                                        val wfId    = Flow.Id.Workflow("stale-verdict")
                                        val flow = Flow.init("stale-verdict")
                                            .step[Async]("work") { _ =>
                                                entries.incrementAndGet.map { n =>
                                                    if n == 1 then
                                                        Abort.run[Closed](gate.take)
                                                            .andThen(unblocked.incrementAndGet)
                                                            .andThen {
                                                                throw new RuntimeException("the stale executor's verdict"); ()
                                                            }
                                                    else Kyo.unit
                                                }
                                            }
                                            .output("result")(_ => entries.get.map(_ => "owner"))
                                        val config = FlowEngine.Config(
                                            workerCount = 1,
                                            lease = 2.seconds,
                                            renewEvery = 100.millis,
                                            pollTimeout = 100.millis
                                        )
                                        for
                                            eid <- FlowEngine.init(parking, config, flow)([v] => (c: v < Async) => c).map { first =>
                                                first.workflows.start(wfId).map { handle =>
                                                    settle(tc, step = 50.millis, maxRounds = 60)(entries.get.map(_ >= 1))
                                                        .andThen(handle.executionId)
                                                }
                                            }
                                            _ <- armed.set(true)
                                            _ <- tc.advance(10.seconds)
                                            _ <- FlowEngine.init(store, config, flow)([v] => (c: v < Async) => c).map { second =>
                                                settle(tc, step = 250.millis, maxRounds = 80)(
                                                    store.getExecution(eid).map(_.exists(_.status.isTerminal))
                                                )
                                            }
                                            ownerState <- store.getExecution(eid)
                                            _          <- Abort.run[Closed](gate.put(()))
                                            _          <- settle(tc, step = 100.millis, maxRounds = 80)(unblocked.get.map(_ >= 1))
                                            resumed    <- unblocked.get
                                            page       <- store.getHistory(eid, Maybe.empty, 0)
                                            finalState <- store.getExecution(eid)
                                        yield
                                            assert(
                                                ownerState.exists(_.status == Flow.Status.Completed),
                                                s"the premise is that a second engine completed it, got ${ownerState.map(_.status)}"
                                            )
                                            assert(
                                                resumed >= 1,
                                                s"the premise is that the stale executor's step came back past its block, and it did not"
                                            )
                                            assert(
                                                finalState.exists(_.status == Flow.Status.Completed),
                                                s"the store must keep a terminal status, got ${finalState.map(_.status)}"
                                            )
                                            assert(
                                                !page.events.exists(_.kind == Flow.EventKind.Failed),
                                                s"an execution completed by its owner must not carry a Failed event written by an " +
                                                    s"executor that no longer held it, got ${page.events.map(_.kind)}"
                                            )
                                        end for
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        /** The renewal loop survives a transient store failure, the way the poll loop does.
          *
          * Leaving `renewClaim`'s error channel unhandled inside the renewal loop ends the fiber on one failed round-trip, and
          * nothing reads its outcome, so there is no retry, no log, and nothing on `health`. The execution then runs on with a lease
          * nobody is extending until it lapses, at which point another engine claims the same execution and both write. The poll
          * loop, which guards nothing but an idle wait, retries and records; the renewal loop guards a live execution and cannot be
          * less protected than that.
          */
        "a renewal that fails once keeps renewing" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicInt.init(0).map { calls =>
                        Channel.init[Unit](1).map { gate =>
                            val flaky = new FlakyRenewalStore(store, calls)
                            val flow = Flow.init("long-renew")
                                .step[Async]("work")(_ => Abort.run[Closed](gate.take).unit)
                                .output("done")(_ => Async.delay(Duration.Zero)("ok"))
                            val config = FlowEngine.Config(
                                workerCount = 1,
                                lease = 5.seconds,
                                renewEvery = 100.millis,
                                pollTimeout = 100.millis
                            )
                            FlowEngine.init(flaky, config, flow)([v] => (c: v < Async) => c).map { engine =>
                                for
                                    _    <- engine.workflows.start(Flow.Id.Workflow("long-renew"))
                                    kept <- settle(tc)(calls.get.map(_ >= 3))
                                    seen <- calls.get
                                    _    <- Abort.run[Closed](gate.put(()))
                                yield assert(
                                    kept,
                                    s"the renewal loop must survive one transient store failure, renewClaim was called $seen times"
                                )
                                end for
                            }
                        }
                    }
                }
            }
        }

        /** A slow execution in a batch does not hold up the rest of it.
          *
          * One poll claims up to `batchSize` executions and then runs them one after another, awaiting each before starting the next.
          * Everything past the first sits claimed with no renewal fiber until its turn comes, so a long first item lets the others'
          * leases lapse while they wait. With a second engine that means those rows are reclaimed and re-executed, repeating their
          * side effects; on the reference store it feeds the expired-self-claim starvation instead. When the worker finally reaches a
          * lapsed item, the refused renewal makes it skip the item WITHOUT releasing the claim.
          *
          * **Two flows, and the difference between them is the instrument.** Giving both executions the SAME flow, whose only body
          * takes from one empty channel, blocks the "fast" execution exactly as the slow one is blocked, and no engine, serial or
          * concurrent, could finish it: the leaf would then be red for its instrument rather than for the property it names. One
          * flow blocks on the gate, the other cannot block at all.
          *
          * **Both premises are asserted, because either one silently voids the result.** The property is head-of-line blocking
          * WITHIN one claimed batch, so a setup whose two executions are claimed by two different polls proves nothing: it would pass
          * on a serial walk too. The store therefore records the widest batch it ever handed back, and the leaf asserts that batch
          * reached two, which is what makes "in a batch" true rather than assumed. The batch spans two workflows on purpose, since
          * one poll claims across every registered workflow, and both rows exist before the engine does so the first poll cannot see
          * one of them and come back for the other. The second premise is that the slow execution really is mid-step when the fast
          * one finishes; without it the leaf would pass on an engine that simply ran them in sequence, quickly.
          */
        "a slow execution in a batch does not hold up the others" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { plain =>
                    Channel.init[Unit](1).map { gate =>
                        AtomicInt.init(0).map { widestBatch =>
                            val store = new DelegatingStore(plain):
                                override def claimReady(
                                    served: Set[(Flow.Id.Workflow, String)],
                                    executorId: Flow.Id.Executor,
                                    claimLease: Duration,
                                    limit: Int,
                                    timeout: Duration
                                )(using Frame): Seq[FlowStore.Claimed] < (Async & Abort[FlowStoreException]) =
                                    super.claimReady(served, executorId, claimLease, limit, timeout).map { batch =>
                                        widestBatch.updateAndGet(_ max batch.size).andThen(batch)
                                    }
                            val slowId = Flow.Id.Workflow("batched-slow")
                            val fastId = Flow.Id.Workflow("batched-fast")
                            val slowFlow = Flow.init("batched-slow")
                                .output("done") { _ =>
                                    val body: String < Async = Abort.run[Closed](gate.take).unit.andThen("blocked")
                                    body
                                }
                            val fastFlow = Flow.init("batched-fast")
                                .output("done") { _ =>
                                    val body: String < Async = "ok"
                                    body
                                }
                            val config = FlowEngine.Config(
                                workerCount = 1,
                                lease = 1.second,
                                renewEvery = 200.millis,
                                batchSize = 2,
                                pollTimeout = 100.millis
                            )
                            val slow = Flow.Id.Execution("batched-slow-1")
                            val fast = Flow.Id.Execution("batched-fast-1")
                            for
                                _ <- seedExecution(
                                    plain,
                                    slow,
                                    slowId,
                                    Flow.Status.Running,
                                    kyo.internal.WorkflowSchema.structuralHash(slowFlow)
                                )
                                _ <- seedExecution(
                                    plain,
                                    fast,
                                    fastId,
                                    Flow.Status.Running,
                                    kyo.internal.WorkflowSchema.structuralHash(fastFlow)
                                )
                                outcome <- FlowEngine.init(store, config, slowFlow, fastFlow)([v] => (c: v < Async) => c).map { _ =>
                                    for
                                        fastDone  <- settle(tc)(plain.getExecution(fast).map(_.exists(_.status.isTerminal)))
                                        claimed   <- widestBatch.get
                                        slowState <- plain.getExecution(slow)
                                        fastState <- plain.getExecution(fast)
                                    yield (fastDone, claimed, slowState, fastState)
                                }
                                _ <- Abort.run[Closed](gate.put(()))
                            yield
                                val (fastDone, claimed, slowState, fastState) = outcome
                                assert(
                                    claimed >= 2,
                                    s"the premise is that both executions were claimed in ONE batch; the widest batch was $claimed"
                                )
                                assert(
                                    slowState.exists(!_.status.isTerminal),
                                    s"the premise is that the first execution is still mid-step, it is ${slowState.map(_.status)}"
                                )
                                assert(
                                    fastDone,
                                    s"a second execution in the batch must not wait on the first: it is still " +
                                        s"${fastState.map(_.status)} while the first is blocked"
                                )
                            end for
                        }
                    }
                }
            }
        }

        /** A compensation that outlives the lease does not strand the execution.
          *
          * The store-level leaf for this says a `Compensating` row is unclaimable. This is how a flow reaches that state without any
          * crash at all: compensation handlers get no timeout of their own, so a handler that runs longer than the lease loses the
          * claim and is interrupted, leaving the row at `Compensating` with the terminal status never written. Nothing can pick it up
          * afterwards, so the execution is neither finished nor recoverable and its compensations are half-done. The README documents
          * the path as Running to Compensating to Failed.
          *
          * The lapse comes from `UnrenewedCompensationStore`, because a correct engine renews for as long as the handler runs and a
          * conforming store honours it: the executor that stops being able to renew is the only shape in which this state exists.
          */
        "a compensation that outlives the lease does not strand the execution" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { plain =>
                    AtomicInt.init(0).map { refusals =>
                        val store = new UnrenewedCompensationStore(plain, refusals)
                        val wfId  = Flow.Id.Workflow("slow-comp")
                        val flow = Flow.init("slow-comp")
                            .outputCompensated("a")(_ => 1)(_ => Async.sleep(10.minutes))
                            .output("b") { _ =>
                                throw new RuntimeException("boom"); ""
                            }
                        val config = FlowEngine.Config(
                            workerCount = 1,
                            lease = 5.seconds,
                            renewEvery = 1.second,
                            pollTimeout = 100.millis
                        )
                        for
                            eid <- Scope.run {
                                FlowEngine.init(store, config, flow).map { engine =>
                                    for
                                        handle <- engine.workflows.start(wfId)
                                        eid = handle.executionId
                                        _ <- pump(
                                            tc,
                                            store,
                                            eid,
                                            {
                                                case _: Flow.Status.Compensating => true
                                                case s                           => s.isTerminal
                                            },
                                            600
                                        )
                                        // The refusal is what stops the handler, so the leaf waits for it rather than for a
                                        // number of rounds: reaching it is the premise that the claim was lost mid-unwind. The
                                        // engine's scope then closes, because an executor that can no longer renew is an
                                        // executor that is gone, and one still polling would be racing the rescuer below for a
                                        // row it has already proven it cannot carry.
                                        lost <- settle(tc)(refusals.get.map(_ > 0))
                                        _ = assert(
                                            lost,
                                            "the premise is that the unwind lost its claim while its handler was still running"
                                        )
                                    yield eid
                                }
                            }
                            _     <- Kyo.foreachDiscard(1 to 200)(_ => tc.advance(100.millis))
                            state <- store.getExecution(eid)
                            rescued <- store.claimReady(
                                Set((wfId, kyo.internal.WorkflowSchema.structuralHash(flow))),
                                Flow.Id.Executor("rescuer"),
                                30.seconds,
                                10,
                                Duration.Zero
                            )
                        yield assert(
                            state.exists(_.status.isTerminal) || rescued.map(_.state.executionId) == Seq(eid),
                            s"an execution left mid-unwind must either finish or be claimable by another executor, " +
                                s"got ${state.map(_.status)} held by ${state.map(_.executor)} until " +
                                s"${state.map(_.claimExpiry)} and rescuer claimed ${rescued.map(_.state.executionId)}"
                        )
                        end for
                    }
                }
            }
        }

        /** A compensation that already ran must not run again when a stopped executor's execution is recovered.
          *
          * The property that decides whether compensation handlers need to be idempotent or merely re-entrant. Reaching it at all
          * takes three things: readiness hands a `Compensating` execution back, which is the premise here; a per-handler completion
          * record lets the resumed unwind tell a handler that already ran from one that did not; and the re-entry takes a resumed
          * `Compensating` execution back into its unwind rather than replaying it forward.
          *
          * The assertion is deliberately closed on the count. Recovery re-runs only the handlers with no recorded completion, and
          * asserting the count is what stops this passing on either reading of the contract.
          */
        "a recovered compensation does not re-run handlers that already ran" in {
            withEngine { (engine, store, tc) =>
                AtomicInt.init(0).map { firstHandler =>
                    val flow = Flow.init("resumed-unwind")
                        .outputCompensated("a")(_ => "held")(_ => firstHandler.incrementAndGet.unit)
                        .output("b") { _ =>
                            throw new RuntimeException("boom"); ""
                        }
                    val theHash = kyo.internal.WorkflowSchema.structuralHash(flow)
                    val cause   = Flow.Cause.Failure("boom")
                    for
                        eid <- Sync.defer(Flow.Id.Execution("resumed-unwind-1"))
                        now <- Clock.now
                        // The durable state a stopped executor leaves behind: the forward step ran, the unwind began carrying the
                        // verdict that started it, and that step's own handler already completed. The seed goes in BEFORE the
                        // workflow is registered, so the engine serves no version this row could be claimed under and the fixture
                        // takes the claim without racing a worker for it.
                        _       <- seedExecution(store, eid, wf1, Flow.Status.Compensating(cause), theHash)
                        claimed <- seedClaim(store, eid, wf1, theHash)
                        _ <- claimed.recordProgress[String](
                            "a",
                            Maybe("held"),
                            Flow.Event.StepCompleted(wf1, eid, "a", now)
                        )
                        _ <- claimed.appendEvent(Flow.Event.CompensationStarted(wf1, eid, cause, now))
                        // The per-handler completion, which is what "no recorded completion" reads. It is a progress record of its
                        // own key rather than an event beside the node's, because the node's own path already carries the forward
                        // completion and progress is write-once per path.
                        _ <- claimed.recordProgress(
                            kyo.internal.FlowInterpreter.compensatedKey("a"),
                            Flow.Event.NodeCompensated(wf1, eid, "a", now)
                        )
                        _         <- claimed.finish(FlowStore.Claimed.Outcome.Suspended(Set.empty))
                        _         <- engine.register(wf1, flow)
                        recovered <- settle(tc)(store.getExecution(eid).map(_.exists(_.status.isTerminal)))
                        ran       <- firstHandler.get
                    yield
                        assert(recovered, "a compensating execution left by a stopped executor must be recoverable")
                        assert(ran == 0, "a handler whose completion is already recorded must not run a second time")
                    end for
                }
            }
        }

        /** An execution that crashed mid-unwind still ends terminal, even when the step that failed would now succeed.
          *
          * **The one outcome nobody would choose.** A resumed `Compensating` execution that knows only that it is unwinding, not
          * what from, has no recovery left but to replay forward and hope the failing step fails again. For a TRANSIENT failure
          * that is a silent correctness bug rather than a slow path: the step succeeds the second time, the execution
          * carries on and COMPLETES, with some of its compensations already run and the work they undid still undone. A reservation
          * released and the order shipped anyway. It also produces `Compensating` to `Completed`, which this module's own README
          * says cannot happen, so the first symptom is a transition the documentation forbids.
          *
          * The leaf asserts the OUTCOME rather than the mechanism: the step is rigged to succeed on its second entry, so a forward
          * replay would complete the execution and only a re-entry into the unwind can keep it terminal. The counter is a premise,
          * not the property: it says the rigged step was never re-entered at all.
          */
        "an execution resumed mid-unwind does not complete when its failing step would now succeed" in {
            withEngine { (engine, store, tc) =>
                AtomicInt.init(0).map { entries =>
                    AtomicInt.init(0).map { undone =>
                        val flow = Flow.init("resumed-verdict")
                            .outputCompensated("a")(_ => "held")(_ => undone.incrementAndGet.unit)
                            .output("b") { _ =>
                                // Fails once and succeeds ever after, which is what a transient failure looks like on the way back.
                                entries.incrementAndGet.map { n =>
                                    if n == 1 then throw new RuntimeException("boom") else "recovered"
                                }
                            }
                        val theHash = kyo.internal.WorkflowSchema.structuralHash(flow)
                        val cause   = Flow.Cause.Failure("boom", Maybe.empty)
                        for
                            eid <- Sync.defer(Flow.Id.Execution("resumed-verdict-1"))
                            now <- Clock.now
                            // What an executor interrupted mid-unwind leaves: the forward step done, the unwind entered carrying
                            // the verdict that started it, and no handler recorded yet. Seeded before the workflow is registered,
                            // so the engine serves no version this row could be claimed under while the fixture writes it.
                            _       <- seedExecution(store, eid, wf1, Flow.Status.Compensating(cause), theHash)
                            claimed <- seedClaim(store, eid, wf1, theHash)
                            _ <- claimed.recordProgress[String](
                                "a",
                                Maybe("held"),
                                Flow.Event.StepCompleted(wf1, eid, "a", now)
                            )
                            _      <- claimed.appendEvent(Flow.Event.CompensationStarted(wf1, eid, cause, now))
                            _      <- claimed.finish(FlowStore.Claimed.Outcome.Suspended(Set.empty))
                            _      <- engine.register(wf1, flow)
                            status <- pump(tc, store, eid, _.isTerminal, 400)
                            ran    <- entries.get
                            undoes <- undone.get
                        yield
                            assert(
                                ran == 0,
                                s"the premise is that the resumed attempt never replayed forward into the step that failed, it entered it $ran times"
                            )
                            assert(
                                undoes == 1,
                                s"the handler with no recorded completion must run on the resumed unwind, it ran $undoes times"
                            )
                            assert(
                                status != Flow.Status.Completed,
                                "an execution that was unwinding must never complete: its compensations have already half run"
                            )
                            assert(
                                status match
                                    case Flow.Status.Failed(error, _) => error == "boom"
                                    case _                            => false,
                                s"it must end on the verdict its interrupted attempt recorded, got $status"
                            )
                        end for
                    }
                }
            }
        }

        /** Two workers of one engine never run the same execution.
          *
          * Every worker fiber of an engine shares one executor id, so the store's exclusivity guarantee, which is stated per caller,
          * has to hold between callers that are indistinguishable to it. Eight executions, two workers, a batch of one: each step
          * body increments a counter, so a total above eight means an execution was handed to both workers and its side effect ran
          * twice.
          */
        "two workers of one engine never run the same execution" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicInt.init(0).map { runs =>
                        val wfId   = Flow.Id.Workflow("shared-workers")
                        val flow   = Flow.init("shared-workers").output("done")(_ => runs.incrementAndGet)
                        val config = FlowEngine.Config(workerCount = 2, batchSize = 1, pollTimeout = 100.millis)
                        FlowEngine.init(store, config, flow).map { engine =>
                            for
                                handles <- Kyo.foreach(1 to 8)(_ => engine.workflows.start(wfId))
                                allDone <- settle(tc) {
                                    Kyo.foreach(handles)(h => store.getExecution(h.executionId))
                                        .map(_.count(_.exists(_.status == Flow.Status.Completed)) == 8)
                                }
                                total  <- runs.get
                                states <- Kyo.foreach(handles)(h => store.getExecution(h.executionId))
                                completed = states.count(_.exists(_.status == Flow.Status.Completed))
                            yield
                                assert(allDone, s"every execution should finish, $completed of 8 did")
                                assert(total == 8, s"no execution may run twice: the step body ran $total times for 8 executions")
                            end for
                        }
                    }
                }
            }
        }

        /** Two concurrent starts on one explicit execution id produce exactly one execution.
          *
          * Reading the execution and then creating it, with nothing making the pair atomic (`FlowEngine.scala:114-125`), leaves the
          * outcome to scheduling, and the SPI does not say what `createExecution` does for an id that already exists. The suite's
          * other leaf for this runs the two starts one after the other, so it passes on ordering rather than on a guarantee.
          *
          * **The interleaving is forced, not sampled.** A single round of a check-then-act race passes about half the time, on an
          * idle machine as much as a busy one, which is a coin flip rather than a guarantee. Looping it twenty times is worse than
          * either, because twenty green rounds report the scheduler's mood and call it evidence.
          *
          * So the store holds the first two readers of this id until both have arrived (`RendezvousStore`). Both then read `Absent`,
          * both create, and the window is open on every run rather than on some of them. The leaf passes only when the read and the
          * create are atomic, which is what `createExecutionIfAbsent` is for.
          *
          * **The premise is asserted before the outcome, and that ordering is the point.** A barrier that fails to engage turns this
          * leaf back into a sample, and a sample of a race passes about half the time; a `Gate` whose `Closed` is swallowed is
          * exactly such a barrier, a no-op with nothing saying so. The leaf counts arrivals and fails with "this run measured
          * nothing" when they are not two, so a green can only mean the read and the create were atomic.
          *
          * Note what is deliberately NOT asserted: that the history holds one `Created` event. `createExecution` overwrites the row
          * and REPLACES the history with a single event (`internal/MemoryFlowStore.scala:135-155`), so a double create leaves exactly
          * one `Created` too, and asserting it would look like coverage while detecting nothing.
          *
          * Both halves matter, because a second `createExecution` on a live id does not merely duplicate: it resets the row and the
          * history, which the store's own suite pins separately.
          */
        "two concurrent starts on the same execution id yield exactly one execution" in {
            Clock.withTimeControl { _ =>
                FlowStore.initMemory.map { plain =>
                    val wfId = Flow.Id.Workflow("dup-start")
                    val eid  = Flow.Id.Execution("dup-start-1")
                    val flow = Flow.init("dup-start").output("done")(_ => "ok")
                    Latch.init(2).map { barrier =>
                        AtomicInt.init(2).map { rendezvous =>
                            AtomicInt.init(0).map { arrived =>
                                AtomicInt.init(0).map { absent =>
                                    val store = new RendezvousStore(plain, eid, barrier, rendezvous, arrived, absent)
                                    FlowEngine.init(store, FlowEngine.Config(workerCount = 1, pollTimeout = 100.millis), flow).map {
                                        engine =>
                                            for
                                                pair <- Async.zip(
                                                    Abort.run[Throwable](engine.workflows.start(wfId, eid)),
                                                    Abort.run[Throwable](engine.workflows.start(wfId, eid))
                                                )
                                                wins = Seq(pair._1, pair._2).count(_.isSuccess)
                                                both      <- arrived.get
                                                sawAbsent <- absent.get
                                            yield
                                                assert(
                                                    both == 2 && sawAbsent == 2,
                                                    s"the premise is that both reads complete, and both see nothing, before either " +
                                                        s"write: $both reached the rendezvous and $sawAbsent read Absent, so this run " +
                                                        "measured nothing and the result below is not evidence"
                                                )
                                                assert(
                                                    wins == 1,
                                                    s"exactly one of two concurrent starts on one id may succeed, $wins did"
                                                )
                                            end for
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        /** An input whose stored value cannot be read back does not spin the poll loop.
          *
          * Readiness for a waiting execution is decided by whether the field is PRESENT, and the interpreter decides whether to carry
          * on by whether it DECODES. A stored value that no longer decodes, after a type changed or a row was written by an older
          * version, satisfies the first and fails the second, so the execution is permanently ready and permanently unable to
          * progress. Each cycle claims it, resumes it, finds no usable input, and releases at once, at full poll speed, writing
          * several events every time. This is the same shape as the parked-execution spin the group above already guards against.
          */
        "an input whose stored value cannot be decoded does not spin the poll loop" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    val undecodable = new UndecodableFieldStore(store, "x")
                    val flow        = Flow.input[Int]("x").output("y")(ctx => ctx.x * 2)
                    def idle(using Frame): Unit < (Async & Abort[FlowStoreException]) =
                        Kyo.foreachDiscard(1 to 50)(_ => tc.advance(10.millis))
                    FlowEngine.init(undecodable, FlowEngine.Config(workerCount = 1, pollTimeout = 100.millis), flow).map { engine =>
                        for
                            handle <- engine.workflows.start(Flow.Id.Workflow("x"))
                            eid = handle.executionId
                            _      <- engine.executions.signal[Int](eid, "x", 7)
                            _      <- idle
                            first  <- store.getHistory(eid, Maybe.empty, 0)
                            _      <- idle
                            second <- store.getHistory(eid, Maybe.empty, 0)
                        yield assert(
                            second.events.size == first.events.size,
                            s"an execution that cannot progress must not be re-claimed on every poll: " +
                                s"history grew from ${first.events.size} to ${second.events.size}"
                        )
                        end for
                    }
                }
            }
        }

        /** A fan-out asked to run concurrently does not run one item at a time.
          *
          * `foreach` takes a `concurrency` parameter, hashes it into the structural hash, and renders it in the generated diagrams,
          * so a reader of either is told the items run in parallel. The interpreter folds over the collection sequentially and never
          * reads the value. Either the parameter does something or it should not be offered.
          */
        "a foreach with concurrency runs more than one item at a time" in {
            withEngine { (engine, store, tc) =>
                AtomicInt.init(0).map { inFlight =>
                    AtomicInt.init(0).map { maxSeen =>
                        val flow = Flow.init("fanout")
                            .foreach[
                                "items",
                                Int,
                                Int,
                                Async
                            ]("items", concurrency = 4)(_ => Seq(1, 2, 3, 4)) { item =>
                                for
                                    n <- inFlight.incrementAndGet
                                    _ <- maxSeen.updateAndGet(m => if n > m then n else m)
                                    _ <- Async.sleep(10.millis)
                                    _ <- inFlight.decrementAndGet
                                yield item
                            }
                        for
                            _      <- engine.register(wf1, flow)([v] => (c: v < Async) => c)
                            handle <- engine.workflows.start(wf1)
                            eid = handle.executionId
                            status <- pump(tc, store, eid, _.isTerminal, 400)
                            peak   <- maxSeen.get
                        yield
                            assert(status == Flow.Status.Completed, s"expected Completed, got $status")
                            assert(peak > 1, s"a foreach with concurrency 4 must overlap its items, peak in flight was $peak")
                        end for
                    }
                }
            }
        }

        /** A panic on the resume path is reported somewhere, rather than looping in silence.
          *
          * The handler that records an execution's failure is `Abort.recover`, which handles failures and re-raises panics, and the
          * outcome of the fiber it wraps is discarded. So a deterministic panic before that handler leaves the execution at Running:
          * it is reclaimed, panics again, and repeats forever, adding a claim/resume/release triplet to the history each time, while
          * `health` shows nothing and no status ever changes. Either outcome is acceptable to this leaf, failing the execution or
          * recording it on health; silence with unbounded history growth is not.
          */
        "a panic on the resume path is not swallowed forever" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    val panicking = new PanickingResumeStore(store)
                    val flow      = Flow.init("panics").output("done")(_ => "ok")
                    FlowEngine.init(panicking, FlowEngine.Config(workerCount = 1, pollTimeout = 100.millis), flow).map { engine =>
                        for
                            handle <- engine.workflows.start(Flow.Id.Workflow("panics"))
                            eid = handle.executionId
                            // Deliberately a short window. The defect is an unbounded loop, so the leaf pays for every round it
                            // watches: a hundred rounds of it produced over a quarter of a million history events, which is more
                            // than a single-threaded runtime survives. A handful of rounds already shows the loop turning without
                            // the status moving or the health signal registering, which is the whole claim.
                            noticed <- settle(tc, maxRounds = 5) {
                                store.getExecution(eid).map(_.exists(_.status.isTerminal)).map {
                                    case true  => true
                                    case false => engine.health.map(_.pollFailures > 0)
                                }
                            }
                            state   <- store.getExecution(eid)
                            health  <- engine.health
                            history <- store.getHistory(eid, Maybe.empty, 0)
                        yield assert(
                            noticed,
                            s"a repeating panic must reach a status or the health signal, got ${state.map(_.status)}, " +
                                s"${health.pollFailures} recorded failures, and ${history.events.length} history events"
                        )
                        end for
                    }
                }
            }
        }

        /** Two nodes sharing a name are refused at registration, because no outcome of accepting them is defensible.
          *
          * Replay decides what is already done from the set of NAMES with a completion event, so two nodes sharing a name share one
          * marker: the first one's completion makes the second look already done, and after any suspension the second is skipped
          * forever. They share one field too, so the second's result overwrites the first's. Neither node is addressable, and there
          * is no contract under which "both run" and "the record is readable" are true at once: whichever way the tie is broken, one
          * of the two nodes does not mean what it says. `FlowLint` detects this and `Flow.lint` reports it, but a registration that
          * does not ask accepts the flow and lets the corruption stay silent. Refusing the definition is the only answer that leaves
          * nothing ambiguous.
          */
        "two nodes sharing a name are refused at registration" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("dup-names")
                    .step("dup")(_ => ())
                    .input[String]("gate")
                    .step("dup")(_ => ())
                    .output("done")(_ => "ok")
                Abort.run[FlowException](engine.register(wf1, flow)).map { res =>
                    assert(
                        res.isFailure,
                        "two nodes sharing a name cannot both be addressable, so registration must refuse the definition " +
                            s"rather than accept one whose second node is skipped forever, got $res"
                    )
                }
            }
        }

        /** The race exemption's boundary: a race's shared name is exempt only WITHIN the race's branches.
          *
          * The lint exempts a name shared by a race's branches because the union result type forces the sharing. It does not
          * exempt the same name written by a node OUTSIDE the race: with the field already present when the race replays,
          * both branches skip-complete on it instantly and neither body ever runs, a dead race accepted at registration.
          * The exemption is only-among-race-branches, so a share that reaches outside them is refused.
          */
        "a race's shared name also written outside the race is refused at registration" in {
            withEngine { (engine, store, tc) =>
                val left  = Flow.init("outside-share").output("answer")(_ => "left")
                val right = Flow.init("outside-share").output("answer")(_ => "right")
                val flow  = Flow.init("outside-share").output("answer")(_ => "pre").andThen(Flow.race(left, right))
                Abort.run[FlowException](engine.register(Flow.Id.Workflow("outside-share"), flow)).map { res =>
                    assert(
                        res.isFailure,
                        "a name written both outside a race and by its branches makes the race dead on replay " +
                            "(both branches skip on the pre-existing field), and registration must refuse it"
                    )
                }
            }
        }

        /** A name both written and read is a written name, and the sequential read-write share is refused.
          *
          * `input("x") ... output("x")`: the output's write lands on the path the input's field already occupies, so the
          * output forever skips on the input's value and the record's `x` silently aliases the raw input, the corruption
          * class the leaf above documents for two writers. "Reads are exempt" must not un-refuse this: the exemption is for
          * names ONLY read.
          */
        "an output named after an input is refused at registration" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("read-write-share").input[String]("x").output("x")(ctx => ctx.x)
                Abort.run[FlowException](engine.register(Flow.Id.Workflow("read-write-share"), flow)).map { res =>
                    assert(
                        res.isFailure,
                        "an output named after an input forever skips on the input's field and silently aliases it; " +
                            "registration must refuse the share"
                    )
                }
            }
        }

        /** An in-flight execution can be signalled through the definition it was started under.
          *
          * Signals are resolved against the CURRENTLY registered definition, not against the one the execution is running. So
          * replacing a definition strands every execution already waiting on an input that the new one renamed: the value can never
          * be delivered, and the execution waits forever for something no caller is able to send. The reverse is true too, an input
          * only the new definition declares would be accepted for an execution whose own definition has no such field.
          *
          * Note the execution here is NOT parked, and cannot be: an execution waiting for an input is not ready until its field
          * arrives, so nothing claims it and nothing notices the version changed. It sits in the old definition's world while the
          * only method that could feed it looks exclusively at the new one.
          */
        "an in-flight execution can be signalled through the definition it was started under" in {
            withEngine { (engine, store, tc) =>
                val v1 = Flow.init("renamed-input").input[String]("approval").output("done")(ctx => ctx.approval)
                val v2 = Flow.init("renamed-input").input[String]("signoff").output("done")(ctx => ctx.signoff)
                for
                    _      <- engine.register(wf1, v1)
                    v1Info <- store.getWorkflow(wf1)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _         <- pumpState(tc, store, eid, waitingFor("approval"))
                    _         <- engine.register(wf1, v2)
                    delivered <- Abort.run[Throwable](engine.executions.signal[String](eid, "approval", "yes"))
                yield assert(
                    delivered.isSuccess,
                    s"an input the execution's own definition declares must be deliverable to it, got $delivered"
                )
                end for
            }
        }

        /** A loop that has finished one iteration of many is not reported as a completed node.
          *
          * Deciding a loop is done with `completedSteps.exists(IterationName.isIteration(_, name))` lets ANY recorded iteration mark
          * the whole loop `Completed`, so a scheduled loop on iteration 1 of 50 is drawn as finished, in `describe` and in the
          * rendered diagram both.
          *
          * The error compounds rather than staying local. `assignStatuses` walks the nodes in order and gives the FIRST node that
          * is neither completed nor suspended the `Running` status. A loop wrongly marked `Completed` is skipped, so `Running`
          * lands on the node after it, which has not started and may never start. The operator sees a finished loop and a running
          * downstream step, and the truth is a mid-flight loop and a downstream step that has not been reached.
          *
          * The same rule applied to `ForEach` is merely dead rather than wrong: a fan-out records one event under its own name,
          * never `name#n`, so nothing ever matches.
          */
        "a loop that has completed one iteration is not shown as a completed node" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("progress-loop")
                    .loopOn("acc", Schedule.fixed(100.millis).repeat(1000), 0) { (state: Int, ctx) =>
                        if state >= 50 then Loop.done(state)
                        else Loop.continue(state + 1)
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    ran <- settle(tc, maxRounds = 200)(
                        store.getHistory(eid, Maybe.empty, 0).map(_.events.exists {
                            case Flow.Event.StepCompleted(_, _, n, _) => n == kyo.internal.IterationName.step("acc", 0)
                            case _                                    => false
                        })
                    )
                    state  <- store.getExecution(eid)
                    detail <- engine.executions.describe(eid)
                yield
                    assert(ran, "the loop should have recorded its first iteration")
                    assert(
                        !state.exists(_.status.isTerminal),
                        s"the loop needs 50 iterations and must still be running, got ${state.map(_.status)}"
                    )
                    val node = detail.progress.nodeByName("acc")
                    assert(
                        node.exists(_.status != FlowEngine.Progress.NodeStatus.Completed),
                        s"a loop on its first of fifty iterations must not be reported completed, got ${node.map(_.status)}"
                    )
                end for
            }
        }

        /** `describe` reports the execution's own definition, not whichever one is registered now.
          *
          * Resolving the definition with `defs.use(_.get(state.flowId))`, the CURRENTLY registered one, and building both halves of
          * the answer from it, the `inputs` list and `Progress.build(defn.flow, ...)`, describes every in-flight execution against a
          * flow it is not running once a redeployment has happened.
          *
          * This is the same version-blindness as the signal leaf above, in a surface where it does more damage. `describe` is the
          * operator's primary diagnostic, the thing reached for when an execution is stuck, and here it is stuck precisely BECAUSE
          * the definition changed. Answering with the new flow's input names leaves the input the execution is actually waiting on
          * out of the list, and rendering the new flow's node graph with the old execution's completed events mapped onto it is a
          * progress diagram of a flow that never ran. An operator reading it concludes the execution is waiting for something nobody
          * can send, which is true, but the reason is invisible and the evidence points at the wrong flow.
          *
          * Note the execution is NOT parked and cannot be: it waits on an input, so it is never ready, so nothing claims it and
          * nothing notices the version changed.
          */
        "describe reports the inputs of the definition the execution was started under" in {
            withEngine { (engine, store, tc) =>
                val v1 = Flow.init("renamed-input").input[String]("approval").output("done")(ctx => ctx.approval)
                val v2 = Flow.init("renamed-input").input[String]("signoff").output("done")(ctx => ctx.signoff)
                for
                    _      <- engine.register(wf1, v1)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _       <- pumpState(tc, store, eid, waitingFor("approval"))
                    _       <- engine.register(wf1, v2)
                    detail  <- engine.executions.describe(eid)
                    diagram <- engine.executions.diagram(eid)
                yield
                    val names = detail.inputs.map(_.name)
                    assert(
                        names.contains("approval"),
                        s"describe must report the input this execution is actually waiting on, got $names"
                    )
                    // Same lookup, same defect, different surface. Asserted in one leaf rather than two because one fix closes
                    // both: a fix that keyed describe by the execution's own version but left diagram alone would be caught here.
                    assert(
                        diagram.contains("approval"),
                        s"the execution's diagram must render the flow it is running, and it does not mention approval: $diagram"
                    )
                end for
            }
        }

        /** A fan-out's declared concurrency shows up as elapsed time.
          *
          * The companion to the in-flight count: four items that each sleep a second finish in about a second when they overlap and
          * about four when they do not, and virtual time makes that exact rather than a benchmark. Asserting both shapes is what
          * distinguishes "concurrency does something" from "the counter happened to move".
          */
        "a foreach's declared concurrency shows up as elapsed time" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("timed-fanout")
                    .foreach("items", concurrency = 4)(_ => Seq(1, 2, 3, 4)) { item =>
                        val body: Int < Async = Async.sleep(1.second).andThen(item)
                        body
                    }
                for
                    _      <- engine.register(wf1, flow)([v] => (c: v < Async) => c)
                    before <- Clock.now
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    status <- pump(tc, store, eid, _.isTerminal, 2000)
                    after  <- Clock.now
                yield
                    assert(status == Flow.Status.Completed, s"expected Completed, got $status")
                    val elapsed = after - before
                    assert(
                        elapsed < 3.seconds,
                        s"four one-second items at concurrency 4 should overlap, they took $elapsed"
                    )
                end for
            }
        }

        /** A signal that arrives after the execution finished is refused, not absorbed.
          *
          * The race a caller cannot avoid: an approval sent at the moment the execution completes on its own. Accepting it would
          * write a field into a terminal execution that nothing will ever read, and would tell the caller their value was delivered
          * when it had no effect. The refusal has to be visible, because "delivered" and "arrived too late" are different answers
          * for whoever sent it.
          */
        "a signal to a finished execution is refused" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("signal-after").input[String]("gate").output("done")(ctx => ctx.gate)
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- pumpState(tc, store, eid, waitingFor("gate"))
                    _      <- engine.executions.signal[String](eid, "gate", "yes")
                    status <- pump(tc, store, eid, _.isTerminal, 400)
                    late   <- Abort.run[Throwable](engine.executions.signal[String](eid, "gate", "too late"))
                    value  <- store.getField[String](eid, "gate")
                yield
                    assert(status == Flow.Status.Completed, s"expected Completed, got $status")
                    assert(!late.isSuccess, s"a signal to a finished execution must be refused, got $late")
                    assert(value == Present("yes"), s"the late value must not overwrite the delivered one, got $value")
                end for
            }
        }

        /** A wedged execution is findable by an operator.
          *
          * Where no recovery path reaches a stranded compensating execution, the observability path is the only thing standing
          * between an operator and an execution that has silently stopped existing as far as the engine is concerned. If searching
          * by status cannot even list it, nobody learns it is there.
          */
        "an execution stuck mid-unwind can be found by searching for its status" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("findable").output("done")(_ => "ok")
                for
                    _ <- engine.register(wf1, flow)
                    eid = Flow.Id.Execution("stuck-compensating")
                    _     <- seedExecution(store, eid, wf1, Flow.Status.Compensating(Flow.Cause.Failure("boom")), "")
                    found <- engine.executions.search(filter = Maybe(FlowStore.ExecutionFilter.Compensating))
                yield assert(
                    found.items.exists(_.executionId == eid),
                    s"an operator must be able to find a compensating execution, search returned " +
                        s"${found.items.map(_.executionId)}"
                )
                end for
            }
        }

        /** A failed step is identifiable from the history alone.
          *
          * The status carries one message for the whole execution, so the only place that records WHICH step failed is the event
          * stream. An operator triaging a failure, or any tool built over the HTTP surface, reads the history: if the failure event
          * does not name the step, the answer to "where did this stop" is not recoverable from durable state at all.
          */
        "a failed step is identifiable from the history alone" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("which-step")
                    .step("first")(_ => ())
                    .output("second") { _ =>
                        throw new RuntimeException("boom"); ""
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    status  <- pump(tc, store, eid, _.isTerminal, 400)
                    history <- store.getHistory(eid, Maybe.empty, 0)
                yield
                    assert(status.isTerminal, s"the execution should have failed, got $status")
                    val started    = history.events.collect { case Flow.Event.StepStarted(_, _, name, _, _) => name }
                    val completed  = history.events.collect { case Flow.Event.StepCompleted(_, _, name, _) => name }
                    val unfinished = started.filterNot(completed.contains)
                    assert(
                        unfinished.contains("second"),
                        s"the history must show which step did not finish, started=$started completed=$completed"
                    )
                end for
            }
        }

        /** Bookkeeping does not outweigh the work in an execution's history.
          *
          * Every claim writes `ExecutionClaimed`, `ExecutionResumed`, and `ExecutionReleased` around whatever the execution actually
          * did. For a flow that suspends often, or for any execution reclaimed repeatedly, those three can outnumber the events that
          * describe the work, and the history is the audit trail and the replay input at once. This leaf keeps the ratio honest for a
          * simple flow, so a change that adds another bookkeeping write per cycle is visible.
          */
        "bookkeeping events do not outnumber the work in a simple execution" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("ratio")
                    .step("one")(_ => ())
                    .step("two")(_ => ())
                    .output("done")(_ => "ok")
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _       <- pump(tc, store, eid, _.isTerminal, 400)
                    history <- store.getHistory(eid, Maybe.empty, 0)
                yield
                    val bookkeeping = history.events.count(e =>
                        e.kind == Flow.EventKind.ExecutionClaimed ||
                            e.kind == Flow.EventKind.ExecutionResumed ||
                            e.kind == Flow.EventKind.ExecutionReleased
                    )
                    val work = history.events.count(e =>
                        e.kind == Flow.EventKind.StepStarted ||
                            e.kind == Flow.EventKind.StepCompleted
                    )
                    assert(
                        bookkeeping <= work,
                        s"bookkeeping should not outweigh the work: $bookkeeping bookkeeping events against $work step " +
                            s"events, in ${history.events.map(_.kind)}"
                    )
                end for
            }
        }

        /** An engine configured to claim nothing per poll is refused.
          *
          * `batchSize = 0` builds an engine whose polls ask the store for zero executions, so it spins forever finding nothing. It is
          * one of a family of tunings: unchecked, a value that makes the engine inert is indistinguishable at construction from one
          * that works.
          */
        "an engine configured to claim nothing per poll is refused at init" in {
            Clock.withTimeControl { _ =>
                FlowStore.initMemory.map { store =>
                    Abort.run[Throwable] {
                        FlowEngine.init(store, FlowEngine.Config(batchSize = 0))
                    }.map { result =>
                        assert(!result.isSuccess, "an engine that claims nothing per poll can never run anything")
                    }
                }
            }
        }

        /** A negative lease is refused.
          *
          * The last of the unchecked tuning values, and the one whose consequence is least obvious: a negative lease makes every
          * claim expire before it is written, so an execution is claimed and instantly reclaimable, by this engine or any other.
          */
        "an engine configured with a negative lease is refused at init" in {
            Clock.withTimeControl { _ =>
                FlowStore.initMemory.map { store =>
                    Abort.run[Throwable] {
                        FlowEngine.init(store, FlowEngine.Config(lease = -1.seconds, renewEvery = -2.seconds))
                    }.map { result =>
                        assert(!result.isSuccess, "a negative lease expires before it is written and must not be accepted")
                    }
                }
            }
        }

        /** A change inside a subflow parks the parent's in-flight executions.
          *
          * The engine-level consequence of the hash ignoring subflow internals. The top-level case is already covered: change a
          * parent's own steps and its in-flight executions park rather than replaying against a definition they were not started
          * under. Change a CHILD flow's steps and nothing parks, because the parent's hash is unchanged, so the execution replays
          * against a definition that moved underneath it.
          */
        "a change inside a subflow parks the parent's in-flight executions" in {
            withEngine { (engine, store, tc) =>
                val childV1 = Flow.input[Int]("a").output("b")(ctx => ctx.a)
                val childV2 = Flow.input[Int]("a").step("extra")(_ => ()).output("b")(ctx => ctx.a)
                val parentV1 = Flow.input[Int]("x")
                    .subflow("sub", childV1)(ctx => "a" ~ ctx.x)
                    .output("done")(_ => "ok")
                val parentV2 = Flow.input[Int]("x")
                    .subflow("sub", childV2)(ctx => "a" ~ ctx.x)
                    .output("done")(_ => "ok")
                val v1Hash = kyo.internal.WorkflowSchema.structuralHash(parentV1)
                for
                    _ <- engine.register(wf1, parentV2)
                    eid = Flow.Id.Execution("subflow-changed")
                    _    <- seedExecution(store, eid, wf1, Flow.Status.Running, v1Hash)
                    _    <- Kyo.foreachDiscard(1 to 200)(_ => tc.advance(10.millis))
                    held <- engine.parked(wf1)
                yield assert(
                    held.exists(_.executionId == eid),
                    s"an execution started under the previous subflow must be held, got ${held.map(_.executionId)}"
                )
                end for
            }
        }

        /** A saga cancelled while it waits still runs its compensations.
          *
          * Whether cancelling runs compensations is a contract decision, and writing the terminal status from the caller's own call
          * answers it differently depending on where the execution happens to be. Cancel while it is actively replaying and the
          * cancellation arrives as a `FlowException`, so the unwind runs and the handlers fire. Cancel while it is SUSPENDED, which
          * is the common case for a saga waiting on an approval or a timer, and the row goes straight to a terminal status that is
          * never claimed again, so no handler ever runs and the reserved resources are held forever.
          *
          * **One answer, because cancelling is a REQUEST.** `requestCancel` records the request against the row, readiness hands
          * that row back whatever it was waiting for, and the attempt that claims it unwinds and writes the terminal status at the
          * END of the unwind. A parked saga and a running one therefore take the same path to the same place, which is what makes
          * the answer a contract rather than a scheduling outcome. This leaf asserts the compensating answer, which is the defensible
          * one for a saga: the whole point of registering a compensation is that the resource is released when the flow does not
          * complete.
          */
        "a saga cancelled while it waits still runs its compensations" in {
            withEngine { (engine, store, tc) =>
                AtomicInt.init(0).map { compensated =>
                    val flow = Flow.init("cancel-saga")
                        .outputCompensated("reserved")(_ => "held")(_ => compensated.incrementAndGet.unit)
                        .input[String]("approval")
                        .output("done")(ctx => ctx.approval)
                    for
                        _      <- engine.register(wf1, flow)
                        handle <- engine.workflows.start(wf1)
                        eid = handle.executionId
                        _     <- pumpState(tc, store, eid, waitingFor("approval"))
                        _     <- engine.executions.cancel(eid)
                        _     <- Kyo.foreachDiscard(1 to 100)(_ => tc.advance(10.millis))
                        ran   <- compensated.get
                        state <- store.getExecution(eid)
                    yield
                        assert(state.exists(_.status == Flow.Status.Cancelled), s"expected Cancelled, got ${state.map(_.status)}")
                        assert(
                            ran == 1,
                            s"a cancelled saga must release what it reserved, the compensation ran $ran times"
                        )
                    end for
                }
            }
        }

        /** A sleep whose deadline has not passed is not treated as done.
          *
          * The store decides a sleeping execution is ready by comparing its recorded deadline against now, and the engine writes the
          * completion on resume. A leaf asserting the negative case is what keeps a readiness predicate from being loosened into
          * "any sleeping execution", which would wake every timer immediately and is the kind of change that passes every other
          * test in this suite.
          */
        "a sleep whose deadline has not passed is not woken" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("still-sleeping").sleep("nap", 1.hour).output("done")(_ => "ok")
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _       <- pumpState(tc, store, eid, sleeping)
                    _       <- Kyo.foreachDiscard(1 to 100)(_ => tc.advance(1.second))
                    state   <- store.getExecution(eid)
                    history <- store.getHistory(eid, Maybe.empty, 0)
                yield
                    assert(
                        state.exists(sleeping),
                        s"a sleep with an hour to run must still be sleeping after 100 seconds, got ${state.map(_.waits)}"
                    )
                    assert(
                        !history.events.exists(_.kind == Flow.EventKind.SleepCompleted),
                        "a sleep whose deadline has not passed must not be recorded complete"
                    )
                end for
            }
        }

        /** Two engines sharing one store never run an execution twice.
          *
          * Two engines polling one store is the arrangement the claim lease exists for, and the only one where its guarantees are
          * observable end to end rather than through durable state a fixture wrote itself. This is the control for any fencing work:
          * it has to hold before the writes are fenced and after.
          */
        "two engines on one store never run an execution twice" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicInt.init(0).map { runs =>
                        val wfId = Flow.Id.Workflow("two-engines")
                        val flow = Flow.init("two-engines").output("done")(_ => runs.incrementAndGet)
                        val config =
                            FlowEngine.Config(workerCount = 1, lease = 2.seconds, renewEvery = 500.millis, pollTimeout = 100.millis)
                        for
                            e1      <- FlowEngine.init(store, config, flow)
                            e2      <- FlowEngine.init(store, config, flow)
                            handles <- Kyo.foreach(1 to 6)(_ => e1.workflows.start(wfId))
                            allDone <- settle(tc) {
                                Kyo.foreach(handles)(h => store.getExecution(h.executionId))
                                    .map(_.count(_.exists(_.status == Flow.Status.Completed)) == 6)
                            }
                            total  <- runs.get
                            states <- Kyo.foreach(handles)(h => store.getExecution(h.executionId))
                            completed = states.count(_.exists(_.status == Flow.Status.Completed))
                        yield
                            assert(allDone, s"every execution should finish, $completed of 6 did")
                            assert(
                                total == 6,
                                s"no execution may run on both engines: the step body ran $total times for 6 executions"
                            )
                        end for
                    }
                }
            }
        }

        /** A long step's claim keeps being renewed while it runs.
          *
          * The renewal loop is what keeps an executor authorised for the whole of a step, and the deadline advancing is the only
          * observable that says it is still running. A recovery leaf driven from state a fixture wrote, rather than from a lease
          * that really lapsed, passes whether or not the renewal loop is alive, so this one watches the deadline directly.
          */
        "a long step's claim keeps being renewed" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    Channel.init[Unit](1).map { gate =>
                        val wfId = Flow.Id.Workflow("long-lease")
                        val flow = Flow.init("long-lease").output("done") { _ =>
                            val body: String < Async = Abort.run[Closed](gate.take).unit.andThen("ok")
                            body
                        }
                        val config = FlowEngine.Config(
                            workerCount = 1,
                            lease = 2.seconds,
                            renewEvery = 200.millis,
                            pollTimeout = 100.millis
                        )
                        FlowEngine.init(store, config, flow)([v] => (c: v < Async) => c).map { engine =>
                            for
                                handle <- engine.workflows.start(wfId)
                                eid = handle.executionId
                                _     <- settle(tc)(store.getExecution(eid).map(_.exists(_.claimExpiry.nonEmpty)))
                                early <- store.getExecution(eid)
                                advanced <- settle(tc) {
                                    store.getExecution(eid).map { s =>
                                        (s.flatMap(_.claimExpiry), early.flatMap(_.claimExpiry)) match
                                            case (Present(now), Present(then0)) => then0 < now
                                            case _                              => false
                                    }
                                }
                                late <- store.getExecution(eid)
                                _    <- Abort.run[Closed](gate.put(()))
                            yield
                                val earlyExpiry = early.flatMap(_.claimExpiry)
                                val lateExpiry  = late.flatMap(_.claimExpiry)
                                assert(earlyExpiry.nonEmpty, s"the execution should be claimed while its step runs, got $early")
                                assert(
                                    advanced,
                                    s"the claim deadline must advance while a long step runs, it went from $earlyExpiry to $lateExpiry"
                                )
                            end for
                        }
                    }
                }
            }
        }

        /** A fan-out's results can be read back, in collection order.
          *
          * Two properties in one leaf because the same read establishes both. The ordering half is the regression guard this suite
          * needs the day `concurrency` starts doing something, since collecting results as they complete rather than as they were
          * ordered is the obvious way to break it.
          *
          * The readability half is what a status-only assertion misses. Persisting a fan-out's results through `onOutput` under
          * `Tag[Any]` and a `Schema[Seq[Any]]` cast, rather than under the element type the caller declared, stores the values
          * correctly and in order while leaving no typed read able to match them, because a typed read compares the reader's tag
          * against the stored one: a caller cannot get their own fan-out results back out of the store.
          */
        "a foreach's results can be read back, in collection order" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("ordered")
                    .foreach("items", concurrency = 4)(_ => Seq(1, 2, 3, 4)) { item =>
                        val body: Int < Async = Async.sleep((5 - item).millis).andThen(item * 10)
                        body
                    }
                for
                    _      <- engine.register(wf1, flow)([v] => (c: v < Async) => c)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    status  <- pump(tc, store, eid, _.isTerminal, 400)
                    asChunk <- store.getField[Chunk[Int]](eid, "items")
                    asSeq   <- store.getField[Seq[Int]](eid, "items")
                    all     <- store.getAllFields(eid)
                yield
                    assert(status == Flow.Status.Completed, s"expected Completed, got $status")
                    assert(
                        all.contains("items"),
                        s"the fan-out's results must be persisted, the stored field count is ${all.size}"
                    )
                    val items = asChunk.map(_.toSeq).orElse(asSeq)
                    assert(
                        items == Present(Seq(10, 20, 30, 40)),
                        s"results must be readable and in collection order, got chunk=$asChunk seq=$asSeq " +
                            s"while the raw stored value is ${all.get("items").map(_.value)}"
                    )
                end for
            }
        }

        /** A GUARD for the regression the leaf above invites.
          *
          * Persisting a `foreach` node under `Tag[Any]` with a `Schema[Seq[Any]]` cast leaves no typed read able to match it, and
          * the obvious fix is to persist under the element type's real evidence. Done alone, that fix breaks something no other leaf
          * watches: `WorkflowSchema` builds the schema's `byName` entry for a `ForEach` node under `Tag[Any]` with a `Seq` schema
          * too, and replay rebuilds an execution's record by matching stored fields against those entries. Change one side and the
          * tags stop agreeing, so `rebuildRecord` silently drops the field, and a resumed execution's downstream nodes stop seeing
          * results that are sitting in the store.
          *
          * The leaf above cannot detect that, because it reads through `store.getField` rather than through a resume. This one
          * forces the resume: the fan-out completes, the execution suspends on an input, and only after the signal does a
          * downstream node read the results out of the rebuilt record. If it goes red, the write side moved without the schema
          * entry.
          */
        "a foreach's results survive a resume and reach a downstream node" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.init("foreach-resume")
                    .foreach("items")(_ => Seq(1, 2, 3))(item => item * 10)
                    .input[String]("gate")
                    .output("total")(ctx => ctx.items.foldLeft(0)(_ + _))
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- pumpState(tc, store, eid, waitingFor("gate"))
                    _      <- engine.executions.signal[String](eid, "gate", "go")
                    status <- pump(tc, store, eid, _.isTerminal, 400)
                    total  <- store.getField[Int](eid, "total")
                yield
                    assert(status == Flow.Status.Completed, s"expected Completed, got $status")
                    assert(
                        total == Present(60),
                        s"a resumed execution's downstream node must still see the fan-out's results, got $total"
                    )
                end for
            }
        }

        /** A store failure the store itself marked retryable does not destroy the execution.
          *
          * `FlowStoreException.Retryable` is the marker a store puts on a failure its backend classifies as transient, a deadlock or
          * a dropped connection, so the engine can tell "try again" from "this will fail identically forever" without knowing what a
          * backend's error codes mean. An engine that does not consult it routes every store failure on the resume path to the
          * handler that writes terminal `Failed`, which cannot be reverted, so one transient blip destroys a durable execution in an
          * engine whose whole purpose is surviving transient failure.
          */
        "a retryable store failure does not permanently fail the execution" in {
            Clock.withTimeControl { tc =>
                FlowStore.initMemory.map { store =>
                    AtomicInt.init(1).map { blips =>
                        val flaky = new TransientFieldReadStore(store, blips)
                        val flow  = Flow.init("transient").output("done")(_ => "ok")
                        // A short lease, because an attempt that could not reach the store leaves the claim to expire rather than
                        // releasing it: an ending that never reached a verdict must not bless its ledger as a finished statement.
                        val config = FlowEngine.Config(workerCount = 1, lease = 1.second, renewEvery = 500.millis, pollTimeout = 100.millis)
                        FlowEngine.init(flaky, config, flow).map { engine =>
                            for
                                handle <- engine.workflows.start(Flow.Id.Workflow("transient"))
                                eid = handle.executionId
                                status <- pump(tc, store, eid, _.isTerminal, 400)
                            yield assert(
                                status == Flow.Status.Completed,
                                s"a transient store failure the store marked retryable must not fail the execution, got $status"
                            )
                            end for
                        }
                    }
                }
            }
        }

        /** An engine configured with no workers is refused.
          *
          * `workerCount = 0` builds an engine that starts nothing, so every execution it is asked to run sits at Running forever and
          * the only signal is that nothing happens. It belongs with the renewal-interval check as one more tuning that cannot work.
          */
        "an engine configured with no workers is refused at init" in {
            Clock.withTimeControl { _ =>
                FlowStore.initMemory.map { store =>
                    Abort.run[Throwable] {
                        FlowEngine.init(store, FlowEngine.Config(workerCount = 0))
                    }.map { result =>
                        assert(!result.isSuccess, "an engine with no workers can never run anything, and must not be accepted")
                    }
                }
            }
        }

        /** An unnamed flow's refusal travels the typed channel.
          *
          * The module advertises precise `Abort` union types, "so you can handle exactly the errors each method can produce".
          * Refusing a flow with an empty name through `Flow.init`'s `require` makes that refusal a thrown
          * `IllegalArgumentException`, which arrives as a panic no union names; the engine-side guard for a missing `Init` node is
          * unreachable from the public API, since every public constructor roots one. A refusal the API is right to make still has
          * to arrive the way the API says errors arrive: as a typed `FlowException` failure a caller can handle, not as an untyped
          * crash.
          */
        "an unnamed flow is refused at init with a typed error" in {
            Clock.withTimeControl { _ =>
                FlowStore.initMemory.map { store =>
                    Abort.run[Throwable] {
                        Sync.defer(Flow.init("").output("y")(_ => 1)).map { unnamed =>
                            FlowEngine.init(store, FlowEngine.Config(workerCount = 1), unnamed)
                        }
                    }.map { result =>
                        assert(!result.isSuccess, "a flow with no name cannot be registered and must be refused")
                        val typed = result match
                            case Result.Failure(_: FlowException) => true
                            case _                                => false
                        assert(typed, s"the refusal must arrive as a typed FlowException failure, not a panic, got $result")
                    }
                }
            }
        }

        /** Cancelling does not append step events after the execution is already cancelled.
          *
          * Separable from whether cancel stops the steps: a partial fix that stops new steps but lets the running one finish still
          * has to keep its events out of a terminal execution's history. Nothing downstream filters them out, so the two rules that
          * keep them out are both upstream, and both are the store's: a write presented after the attempt ended is refused with the
          * claim it was presented under, and a status write on the wrong side of the partition is refused WHOLE, its event included.
          */
        "cancelling does not append step events after the cancellation" in {
            withEngine { (engine, store, tc) =>
                Channel.init[Unit](1).map { gate =>
                    val flow = Flow.init("cancel-history")
                        .step[Async]("a")(_ => Abort.run[Closed](gate.take).unit)
                        .step[Async]("b")(_ => Async.delay(Duration.Zero)(()))
                        .output("done")(_ => Async.delay(Duration.Zero)("ok"))
                    for
                        _      <- engine.register(wf1, flow)([v] => (c: v < Async) => c)
                        handle <- engine.workflows.start(wf1)
                        eid = handle.executionId
                        _       <- Kyo.foreachDiscard(1 to 20)(_ => tc.advance(10.millis))
                        _       <- engine.executions.cancel(eid)
                        _       <- Abort.run[Closed](gate.put(()))
                        _       <- Kyo.foreachDiscard(1 to 50)(_ => tc.advance(10.millis))
                        history <- store.getHistory(eid, Maybe.empty, 0)
                    yield
                        val kinds       = history.events.map(_.kind)
                        val cancelledAt = kinds.indexWhere(_ == Flow.EventKind.Cancelled)
                        val stepsAfter =
                            if cancelledAt < 0 then Chunk.empty
                            else
                                kinds.drop(cancelledAt + 1).filter(k =>
                                    k == Flow.EventKind.StepStarted || k == Flow.EventKind.StepCompleted
                                )
                        assert(cancelledAt >= 0, s"the execution should have been cancelled, history is $kinds")
                        assert(
                            stepsAfter.isEmpty,
                            s"no step event may be appended after the cancellation, got $stepsAfter in $kinds"
                        )
                    end for
                }
            }
        }

        /** A step's declared failure is not put through the retry schedule.
          *
          * `Meta`'s own scaladoc calls `retry` a schedule "for transient failures", and a `FlowDomainException` is the opposite of
          * one: a charge declined for being over a limit is a decision, and running it three more times re-fires the step's side
          * effects to reach the same answer. A wrapper that catches every `Throwable` cannot tell the two apart, and the code then
          * contradicts that promise. The sort is explicit (`internal/StoreInterpreter.scala`'s `isAccident`, which answers true for
          * a timeout and false for anything the flow declared), and a declared failure goes straight out rather than round the
          * schedule.
          */
        "a step's domain failure is not retried" in {
            withEngine { (engine, store, tc) =>
                AtomicInt.init(0).map { attempts =>
                    val flow = Flow.init("declines")
                        .output("charge", retry = Maybe(Schedule.fixed(10.millis).repeat(3))) { _ =>
                            val body: String < (Sync & Abort[FlowException]) =
                                attempts.incrementAndGet.andThen(Abort.fail(FlowEngineTest.ChargeDeclined("o1", 100L)))
                            body
                        }
                    for
                        _      <- engine.register(wf1, flow)
                        handle <- engine.workflows.start(wf1)
                        eid = handle.executionId
                        _     <- pump(tc, store, eid, _.isTerminal, 400)
                        tries <- attempts.get
                    yield assert(
                        tries == 1,
                        s"a deterministic domain failure must not be retried, the step body ran $tries times"
                    )
                    end for
                }
            }
        }

        /** A tuning whose renewal never precedes its lease is refused.
          *
          * `renewEvery >= lease` means the claim expires before the first renewal is ever attempted, so every renewal is refused. A
          * refused renewal interrupts the execution, so any step longer than the lease is interrupted, released, reclaimed and
          * re-run on a permanent loop that makes no progress and writes several history events per turn. It belongs to the same
          * family of unusable tunings as zero workers, zero batch size, and negative durations.
          */
        "a renewal interval at least as long as the lease is refused at init" in {
            Clock.withTimeControl { _ =>
                FlowStore.initMemory.map { store =>
                    Abort.run[Throwable] {
                        FlowEngine.init(store, FlowEngine.Config(lease = 1.second, renewEvery = 5.seconds))
                    }.map { result =>
                        assert(
                            !result.isSuccess,
                            "a renewal interval no shorter than the lease can never renew, and must not be accepted"
                        )
                    }
                }
            }
        }

        /** A refused tuning arrives typed, and names every setting at fault rather than the first one found.
          *
          * The four leaves above ask only that the engine is refused, which a thrown `IllegalArgumentException` would satisfy just
          * as well. The module advertises precise `Abort` unions "so you can handle exactly the errors each method can produce", and
          * a refusal the API is right to make still has to arrive the way the API says errors arrive, which is the same property the
          * unnamed-flow leaf pins for definitions.
          *
          * Reporting all of them at once is the other half. A caller fixing one setting per run pays a process start per problem,
          * and the settings here are exactly the ones a deployment gets wrong together, out of one configuration file.
          */
        "a refused tuning names every setting at fault" in {
            Clock.withTimeControl { _ =>
                FlowStore.initMemory.map { store =>
                    Abort.run[FlowInvalidConfigException] {
                        FlowEngine.init(store, FlowEngine.Config(workerCount = 0, batchSize = 0, lease = -1.seconds))
                    }.map { result =>
                        val settings = result match
                            case Result.Failure(e) => e.problems.map(_.setting).toSet
                            case _                 => Set.empty[String]
                        assert(
                            result.isFailure,
                            s"the refusal must arrive on the typed channel a caller can handle, got $result"
                        )
                        assert(
                            settings == Set("workerCount", "batchSize", "lease"),
                            s"every setting at fault must be named, and only those, got $settings"
                        )
                    }
                }
            }
        }

        /** Cancelling an execution stops the steps that have not started.
          *
          * `cancel`'s own documentation says "In-flight steps complete; no new steps start". Consulting the cancelled flag only
          * between scheduled-loop iterations and at input nodes leaves the second half unmet for a straight-line flow: every
          * remaining step runs, side effects included, and only the final status write is suppressed.
          */
        "cancelling an execution stops the steps that have not started" in {
            withEngine { (engine, store, tc) =>
                AtomicInt.init(0).map { bRuns =>
                    Channel.init[Unit](1).map { gate =>
                        val flow = Flow.init("cancel-midway")
                            .step[Async]("a")(_ => Abort.run[Closed](gate.take).unit)
                            .step[Async]("b")(_ => bRuns.incrementAndGet.unit)
                            .output("done")(_ => Async.delay(Duration.Zero)("ok"))
                        for
                            _      <- engine.register(wf1, flow)([v] => (c: v < Async) => c)
                            handle <- engine.workflows.start(wf1)
                            eid = handle.executionId
                            _ <- pump(
                                tc,
                                store,
                                eid,
                                _ => true
                            ).andThen(Kyo.foreachDiscard(1 to 20)(_ => tc.advance(10.millis)))
                            _     <- engine.executions.cancel(eid)
                            _     <- gate.put(())
                            _     <- Kyo.foreachDiscard(1 to 50)(_ => tc.advance(10.millis))
                            after <- bRuns.get
                        yield assert(
                            after == 0,
                            s"a step that had not started when the execution was cancelled must not run, ran $after times"
                        )
                        end for
                    }
                }
            }
        }
    }

    "runner" - {

        "runner handles Env effect in step body" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .output("y") { ctx =>
                        Env.use[String](s => s.length + ctx.x)
                    }
                for
                    _      <- engine.register(wf1, flow)([v] => c => Env.run("hello")(c))
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 10)
                    status <- pump(tc, store, eid, _.isTerminal)
                    y      <- store.getField[Int](eid, "y")
                yield
                    assert(status == Flow.Status.Completed, s"Expected Completed, got $status")
                    // "hello".length = 5, + x = 10, so y = 15
                    assert(y.get == 15, s"Expected 15, got ${y.get}")
                end for
            }
        }

        "runner handles Var effect in step body" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .output("y") { ctx =>
                        val r: Int < Var[Int] = Var.update[Int](_ + ctx.x).andThen(Var.get[Int])
                        r
                    }
                for
                    _ <- engine.register(wf1, flow)(
                        [v] => c => Var.run(10)(c)
                    )
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 5)
                    status <- pump(tc, store, eid, _.isTerminal)
                    y      <- store.getField[Int](eid, "y")
                yield
                    assert(status == Flow.Status.Completed, s"Expected Completed, got $status")
                    // Var starts at 10, adds x=5, so result = 15
                    assert(y.get == 15, s"Expected 15, got ${y.get}")
                end for
            }
        }

        /** A step that allocates a scoped resource completes, and the resource is released.
          *
          * The runner's declared result carries `Scope`, so a step body is allowed to acquire one, and a flow whose steps touch a
          * resource is the reason a runner exists at all. A runner that casts that row away rather than handling it
          * (`FlowRunner.erased`) leaves the suspension pending inside an execution typed as though it were not, and where the
          * execution runs then decides whether anything is left to handle it. The other two runner leaves discharge their effect
          * completely (`Env.run`, `Var.run`) and so never reach this.
          */
        "runner handles a scoped resource in step body" in {
            withEngine { (engine, store, tc) =>
                AtomicInt.init(0).map { acquired =>
                    AtomicInt.init(0).map { released =>
                        val flow = Flow.init("scoped").output("y") { _ =>
                            Scope.acquireRelease(acquired.incrementAndGet.andThen("resource"))(_ => released.incrementAndGet.unit)
                        }
                        for
                            _      <- engine.register(wf1, flow)([v] => c => c)
                            handle <- engine.workflows.start(wf1)
                            eid = handle.executionId
                            status <- pump(tc, store, eid, _.isTerminal)
                            y      <- store.getField[String](eid, "y")
                            a      <- acquired.get
                            r      <- released.get
                        yield
                            assert(status == Flow.Status.Completed, s"expected Completed, got $status")
                            assert(y == Present("resource"), s"expected the step's value, got $y")
                            assert(a == 1, s"expected exactly one acquire, got $a")
                            assert(r == 1, s"expected the resource to be released, got $r")
                        end for
                    }
                }
            }
        }
    }

    "isolate in parallel branches" - {

        "zip branches should preserve Var state via Isolate" in {
            withEngine { (engine, store, tc) =>
                val left = Flow.input[Int]("x").output("left") { ctx =>
                    val r: Int < Var[Int] = Var.update[Int](_ + ctx.x).andThen(Var.get[Int])
                    r
                }
                val right = Flow.input[Int]("x").output("right") { ctx =>
                    val r: Int < Var[Int] = Var.update[Int](_ * ctx.x).andThen(Var.get[Int])
                    r
                }
                val flow = Var.isolate.update[Int].use { left.zip(right) }
                for
                    _ <- engine.register(wf1, flow)(
                        [v] => c => Var.run(10)(c)
                    )
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _        <- engine.executions.signal[Int](eid, "x", 5)
                    status   <- pump(tc, store, eid, _.isTerminal)
                    leftVal  <- store.getField[Int](eid, "left")
                    rightVal <- store.getField[Int](eid, "right")
                yield
                    assert(status == Flow.Status.Completed, s"Expected Completed, got $status")
                    // Left branch: 10 + 5 = 15
                    assert(leftVal.get == 15, s"Left branch should see Var start=10, add x=5, got ${leftVal.get}")
                    // Right branch: 10 * 5 = 50
                    assert(rightVal.get == 50, s"Right branch should see Var start=10, mul x=5, got ${rightVal.get}")
                end for
            }
        }

        "race branches should preserve Var state via Isolate" in {
            withEngine { (engine, store, tc) =>
                val fast = Flow.input[Int]("x").output("winner") { ctx =>
                    val r: Int < Var[Int] = Var.update[Int](_ + 100).andThen(Var.get[Int])
                    r
                }
                val slow = Flow.input[Int]("x")
                    .sleep("wait", 10.seconds)
                    .output("winner") { ctx =>
                        val r: Int < Var[Int] = Var.update[Int](_ + 999).andThen(Var.get[Int])
                        r
                    }
                val flow = Var.isolate.update[Int].use { Flow.input[Int]("x").andThen(Flow.race(fast, slow)) }
                for
                    _ <- engine.register(wf1, flow)(
                        [v] => c => Var.run(0)(c)
                    )
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 1)
                    status <- pump(tc, store, eid, _.isTerminal)
                    winner <- store.getField[Int](eid, "winner")
                yield
                    assert(status == Flow.Status.Completed, s"Expected Completed, got $status")
                    // The fast branch wins with 0 + 100 = 100
                    assert(winner.get == 100, s"Winner should be fast branch (100), got ${winner.get}")
                end for
            }
        }
    }

    "worker fiber resilience" - {

        "step that throws RuntimeException marks execution as Failed" in {
            withEngine { (engine, store, tc) =>
                val flow = Flow.input[Int]("x")
                    .output("y") { ctx =>
                        throw new RuntimeException("boom")
                        ctx.x
                    }
                for
                    _      <- engine.register(wf1, flow)
                    handle <- engine.workflows.start(wf1)
                    eid = handle.executionId
                    _      <- engine.executions.signal[Int](eid, "x", 42)
                    status <- pump(tc, store, eid, _.isTerminal)
                yield status match
                    case Flow.Status.Failed(msg, _) =>
                        assert(msg.contains("boom"), s"Error message should contain 'boom', got: '$msg'")
                    case other =>
                        fail(s"Expected Failed containing 'boom' but got $other")
                end for
            }
        }

    }

    // =========================================================================
    // Node compensation
    // =========================================================================
    //
    // `Dispatch` and `LoopNode` register a compensation handler the way `Output` and `Step` do. Without one, work done inside a
    // dispatch branch or a loop cannot be undone at all, because no interpreter arm has a handler to push for it.
    //
    // The fan-out's per-item handler is NOT here. It needs per-item durable identity, and a node-level handler on a fan-out fails on
    // its own terms: it would receive the node's stored value, which exists only once every item finished, so a fan-out that failed
    // part-way would hand its handler nothing at the one moment unwinding matters.
    "node compensation" - {

        /** A dispatch branch that charges is undone when a later step fails.
          *
          * The handler is node level, and on a dispatch that is exactly the right level: one branch runs and produces one value, so
          * the handler receives the record that value is in and undoes what the branch that ran did. The branch that did not run
          * wrote nothing, so there is no per-branch identity question to answer. What the handler reads to decide is the stored
          * value itself, which is the whole of what a dispatch records about its choice of work.
          */
        "a dispatch branch's work is undone when a later step fails" in {
            withEngine { (engine, store, tc) =>
                AtomicInt.init(0).map { charged =>
                    AtomicInt.init(0).map { refunded =>
                        val flow = Flow.init("premium-route")
                            .output("amount")(_ => 500)
                            .dispatch[String]("route")
                            .when(ctx => ctx.amount > 100, name = "premium")(_ => charged.incrementAndGet.andThen("premium-fee"))
                            .otherwiseCompensated(_ => "no-fee", name = "standard") { ctx =>
                                if ctx.route == "premium-fee" then refunded.incrementAndGet.unit else ()
                            }
                            .step("ship")(_ => throw new RuntimeException("carrier refused"))
                        for
                            _      <- engine.register(wf1, flow)
                            handle <- engine.workflows.start(wf1)
                            eid = handle.executionId
                            status <- pump(tc, store, eid, _.isTerminal, 400)
                            paid   <- charged.get
                            back   <- refunded.get
                        yield
                            val failed = status match
                                case Flow.Status.Failed(_, _) => true
                                case _                        => false
                            assert(paid == 1, s"the premise is that the premium branch ran and charged, it ran $paid time(s)")
                            assert(failed, s"the premise is that a later step failed the execution, got $status")
                            assert(
                                back == 1,
                                s"a dispatch's handler must undo the branch that ran, and it ran $back time(s)"
                            )
                        end for
                    }
                }
            }
        }

        /** A dispatch's handler is registered when its value is replayed rather than computed.
          *
          * The half that is easy to get wrong. An execution that suspends after the dispatch and fails after resuming never computes
          * the branch a second time, because the field is already stored and the node is skipped, so a handler pushed only on the
          * forward path would silently not exist on the attempt that actually unwinds. The work the branch did is just as done
          * either way. This is the same rule `output` already follows, pinned here because the dispatch arm is new.
          */
        "a dispatch's compensation runs after the execution resumed past it" in {
            withEngine { (engine, store, tc) =>
                AtomicInt.init(0).map { charged =>
                    AtomicInt.init(0).map { refunded =>
                        val flow = Flow.init("route-then-wait")
                            .dispatch[String]("route")
                            .when(_ => true, name = "premium")(_ => charged.incrementAndGet.andThen("premium-fee"))
                            .otherwiseCompensated(_ => "no-fee", name = "standard")(_ => refunded.incrementAndGet.unit)
                            .input[String]("gate")
                            .step("ship")(_ => throw new RuntimeException("carrier refused"))
                        for
                            _      <- engine.register(wf1, flow)
                            handle <- engine.workflows.start(wf1)
                            eid = handle.executionId
                            _      <- pumpState(tc, store, eid, waitingFor("gate"))
                            _      <- engine.executions.signal[String](eid, "gate", "go")
                            status <- pump(tc, store, eid, _.isTerminal, 400)
                            paid   <- charged.get
                            back   <- refunded.get
                        yield
                            assert(
                                paid == 1,
                                s"the premise is that the branch ran once, before the suspension and not again after it, and it " +
                                    s"ran $paid time(s)"
                            )
                            assert(status != Flow.Status.Completed, s"the premise is that the step after the resume failed, got $status")
                            assert(
                                back == 1,
                                s"a dispatch whose value was replayed rather than computed must still be undone, and its handler " +
                                    s"ran $back time(s)"
                            )
                        end for
                    }
                }
            }
        }

        /** A loop's handler undoes the value it converged on.
          *
          * The loop's handler is node level too, and what it receives is the loop's own result: one accumulated value, meaningful
          * whenever it exists. It is pushed only once that value exists, which is why a loop that stopped between iterations
          * registers nothing: there is no result to undo, and a handler handed an absent one could only guess.
          */
        "a loop's compensation undoes the value it converged on" in {
            withEngine { (engine, store, tc) =>
                AtomicInt.init(0).map { booked =>
                    AtomicInt.init(0).map { released =>
                        val flow = Flow.init("book-then-confirm")
                            .loopCompensated("total") { _ =>
                                val next: Loop.Outcome[Unit, Int] < Sync =
                                    booked.incrementAndGet.map(n =>
                                        if n >= 3 then Loop.done[Unit, Int](n) else Loop.continue[Int]
                                    )
                                next
                            } { ctx =>
                                released.set(ctx.total)
                            }
                            .step("confirm")(_ => throw new RuntimeException("no capacity"))
                        for
                            _      <- engine.register(wf1, flow)
                            handle <- engine.workflows.start(wf1)
                            eid = handle.executionId
                            status <- pump(tc, store, eid, _.isTerminal, 400)
                            runs   <- booked.get
                            undone <- released.get
                        yield
                            assert(runs == 3, s"the premise is that the loop converged after three iterations, it ran $runs")
                            assert(status != Flow.Status.Completed, s"the premise is that the step after the loop failed, got $status")
                            assert(
                                undone == 3,
                                s"a loop's handler must receive the value the loop converged on, and it was handed $undone"
                            )
                        end for
                    }
                }
            }
        }
    }

end FlowEngineTest

/** An input type whose encoder raises something encoding never raises on its own.
  *
  * Seeding a start encodes each supplied value through its declared input's schema, and the only failures that are an answer ABOUT the
  * value are the ones encoding itself produces: a wrong type, or a structure the codec refuses. A schema whose write throws an
  * [[kyo.Interrupted]] stands for everything else that can pass through that call, and there is no other way to put one there: the
  * schema the builder uses is derived from the declared type, so the throwing one has to BE the declared type's schema.
  */
private case class SeedProbe(value: Int) derives CanEqual

private given seedProbeSchema: Schema[SeedProbe] =
    Schema[Int].transform[SeedProbe](SeedProbe(_))(_ => throw Interrupted(summon[Frame]))
