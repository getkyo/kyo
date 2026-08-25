package kyo

import kyo.internal.*
import kyo.kernel.Isolate

/** Durable workflow engine that executes `Flow` definitions against a persistent store.
  *
  * Create an engine with `FlowEngine.init(store, orderFlow, shippingFlow)` and it immediately starts processing. Each engine runs worker
  * fibers that poll the store for ready executions, claim them via time-limited leases, and interpret the flow AST step by step. Completed
  * steps are persisted before the next begins, so if a process crashes, another engine on the same store resumes from where it left off.
  *
  * The two main API surfaces are `workflows` (start new executions, list registered workflows, render diagrams) and `executions` (deliver
  * input signals, check status, cancel, search, view history). Starting an execution returns a `Handle` — a lightweight reference that
  * wraps the execution ID and delegates to the engine.
  *
  * Multiple engines can share the same store for horizontal scaling. The store's atomic `claimReady` ensures each execution is processed by
  * exactly one engine at a time. If an engine dies, its leases expire and other engines pick up the orphaned executions. This means
  * completed steps are never re-executed, but an in-flight step (started but not completed) may re-execute on a new engine — so
  * side-effecting steps must be idempotent.
  *
  * Executions park when they hit an `.input` node (waiting for a signal) or a `.sleep` node (waiting for time to pass). Parked executions
  * release all in-memory state; only the store holds their state. When the signal arrives or the sleep expires, the next `claimReady` poll
  * discovers the execution and a worker resumes it, replaying from the beginning but skipping all already-completed nodes.
  *
  * @see
  *   [[kyo.Flow]] The workflow definition DSL
  * @see
  *   [[kyo.FlowStore]] The backing persistence layer
  * @see
  *   [[kyo.FlowEngine.Handle]] Typed reference to a running execution
  * @see
  *   [[kyo.FlowEngine.Progress]] Execution progress tracking
  */
final class FlowEngine private (
    private[kyo] val store: FlowStore,
    private[kyo] val defs: AtomicRef[Dict[Flow.Id.Workflow, FlowDefinition]],
    val executorId: Flow.Id.Executor,
    private[kyo] val liveness: FlowEngine.Liveness
)(using Frame):

    /** What the engine's workers are doing: how many are polling, and what the last poll failure was.
      *
      * The reachable answer to "is this engine actually working". A worker that cannot reach the store keeps polling and records the
      * failure here rather than dying, so a health check reads `workersAlive` against `workersConfigured` and the process does not report
      * itself healthy while nothing is being executed.
      */
    def health(using Frame): FlowEngine.Health < Sync =
        liveness.snapshot

    // --- Workflows ---

    /** Workflow management operations: start executions, list/describe registered workflows, render diagrams. */
    object workflows:

        /** Create a new execution of a registered workflow and return a Handle to interact with it.
          *
          * Pre-populated inputs (if any match registered input names) are delivered immediately, potentially allowing the flow to progress
          * past input nodes without separate signal calls.
          */
        def start(
            workflowId: Flow.Id.Workflow,
            inputs: Record[Any] = Record.empty
        )(using
            Frame
        ): FlowEngine.Handle < (Async & Abort[FlowWorkflowNotRegisteredException | FlowSignalTypeMismatchException] & Abort[
            FlowStoreException
        ]) =
            defs.use(_.get(workflowId)).map {
                case Absent => Abort.fail(FlowWorkflowNotRegisteredException(workflowId.value))
                case Present(defn) =>
                    for
                        eid <- Flow.Id.Execution.random
                        now <- Clock.now
                        _ <-
                            store.createExecution(
                                eid,
                                Flow.Status.Running,
                                Flow.Event.Created(workflowId, eid, now),
                                defn.meta.structuralHash
                            )
                        _ <-
                            Kyo.foreachDiscard(defn.inputs) { inputMeta =>
                                inputs.toDict.get(inputMeta.name) match
                                    case Present(value) =>
                                        val valid =
                                            try
                                                discard(inputMeta.schema.encodeString[Json](value)); true
                                            catch case _: Throwable => false
                                        if !valid then
                                            Abort.fail(FlowSignalTypeMismatchException(
                                                inputMeta.name,
                                                inputMeta.tag.show,
                                                value.getClass.getSimpleName
                                            ))
                                        else
                                            store.putField[Any](eid, inputMeta.name, value)(using
                                                inputMeta.tag,
                                                inputMeta.schema
                                            )
                                        end if
                                    case _ => ()
                            }
                    yield new FlowEngine.Handle(eid, FlowEngine.this)
            }

        /** Create an execution with a specific ID. Fails with FlowDuplicateExecutionException if the ID already exists. */
        def start(
            workflowId: Flow.Id.Workflow,
            executionId: Flow.Id.Execution
        )(using
            Frame
        ): Unit < (Async & Abort[FlowWorkflowNotRegisteredException | FlowDuplicateExecutionException] & Abort[FlowStoreException]) =
            defs.use(_.get(workflowId)).map {
                case Absent => Abort.fail(FlowWorkflowNotRegisteredException(workflowId.value))
                case Present(defn) =>
                    store.getExecution(executionId).map {
                        case Present(_) => Abort.fail(FlowDuplicateExecutionException(executionId.value))
                        case _ =>
                            Clock.nowWith { now =>
                                store.createExecution(
                                    executionId,
                                    Flow.Status.Running,
                                    Flow.Event.Created(workflowId, executionId, now),
                                    defn.meta.structuralHash
                                )
                            }
                    }
            }

        /** List all registered workflows with their metadata. */
        def list(using Frame): Seq[FlowEngine.WorkflowInfo] < (Async & Abort[FlowStoreException]) =
            store.listWorkflows

        /** Get metadata for a registered workflow. Fails with FlowWorkflowNotFoundException if not registered. */
        def describe(workflowId: Flow.Id.Workflow)(using
            Frame
        ): FlowEngine.WorkflowInfo < (Async & Abort[FlowWorkflowNotFoundException] & Abort[FlowStoreException]) =
            store.getWorkflow(workflowId).map {
                case Absent     => Abort.fail(FlowWorkflowNotFoundException(workflowId.value))
                case Present(m) => m
            }

        /** List all executions of a workflow, regardless of status. */
        def executions(workflowId: Flow.Id.Workflow)(using Frame): Chunk[FlowStore.ExecutionState] < (Async & Abort[FlowStoreException]) =
            store.listExecutions(workflowId, Maybe.empty, Int.MaxValue, 0)

        /** Render the workflow's structure as a diagram. */
        def diagram(
            workflowId: Flow.Id.Workflow,
            format: Flow.DiagramFormat = Flow.DiagramFormat.Mermaid
        )(using Frame): String < (Async & Abort[FlowWorkflowNotRegisteredException] & Abort[FlowStoreException]) =
            defs.use(_.get(workflowId)).map {
                case Absent => Abort.fail(FlowWorkflowNotRegisteredException(workflowId.value))
                case Present(defn) =>
                    FlowRender.render(defn.flow, format)
            }

    end workflows

    // --- Executions ---

    /** Execution lifecycle operations: signal inputs, check status, cancel, search, view history and diagrams. */
    object executions:

        /** Deliver a typed value to a named input of a running execution.
          *
          * Fails if the execution is terminal, the input name doesn't exist in the workflow definition, the type doesn't match, or the
          * input was already delivered. Delivery is atomic (exactly-once via putFieldIfAbsent).
          */
        def signal[V: Tag: Schema](
            executionId: Flow.Id.Execution,
            name: String,
            value: V
        )(using
            Frame
        ): Unit < (Async & Abort[FlowExecutionStateException | FlowWorkflowNotRegisteredException | FlowSignalException] & Abort[
            FlowStoreException
        ]) =
            store.getExecution(executionId).map {
                case Absent => Abort.fail(FlowExecutionNotFoundException(executionId.value))
                case Present(state) if state.status.isTerminal =>
                    Abort.fail(FlowExecutionTerminalException(executionId.value, state.status))
                case Present(state) =>
                    defs.use(_.get(state.flowId)).map {
                        case Absent => Abort.fail(FlowWorkflowNotRegisteredException(state.flowId.value))
                        case Present(defn) =>
                            Maybe.fromOption(defn.inputs.find(_.name == name)) match
                                case Absent =>
                                    Abort.fail(FlowSignalNotFoundException(name, executionId.value))
                                case Present(inputMeta) =>
                                    if !(Tag[V] =:= inputMeta.tag) then
                                        Abort.fail(FlowSignalTypeMismatchException(name, inputMeta.tag.show, Tag[V].show))
                                    else
                                        store.putFieldIfAbsent[V](executionId, name, value).map {
                                            case true =>
                                                Clock.nowWith(ts =>
                                                    store.appendEvent(
                                                        executionId,
                                                        Flow.Event.InputReceived(state.flowId, executionId, name, ts)
                                                    )
                                                )
                                            case false => Abort.fail(FlowInputAlreadyDeliveredException(executionId.value, name))
                                        }
                    }
            }

        /** Get full execution detail including status, progress, and pending input information. */
        def describe(executionId: Flow.Id.Execution)(using
            Frame
        ): FlowEngine.ExecutionDetail < (Async & Abort[FlowExecutionNotFoundException] & Abort[FlowStoreException]) =
            store.getExecution(executionId).map {
                case Absent => Abort.fail(FlowExecutionNotFoundException(executionId.value))
                case Present(state) =>
                    defs.use(_.get(state.flowId)).map {
                        case Absent =>
                            FlowEngine.ExecutionDetail(state, Seq.empty, FlowEngine.Progress.empty)
                        case Present(defn) =>
                            for
                                fields  <- store.getAllFields(executionId)
                                history <- store.getHistory(executionId, Int.MaxValue, 0)
                                completed       = deriveCompleted(history)
                                deliveredInputs = deliveredInputNames(defn, fields)
                                progress        = FlowEngine.Progress.build(defn.flow, completed ++ deliveredInputs, state.status)
                                inputInfos = defn.inputs.map { im =>
                                    FlowEngine.InputInfo(im.name, im.tag.show, delivered = fields.contains(im.name))
                                }
                            yield FlowEngine.ExecutionDetail(state, inputInfos, progress)
                    }
            }

        /** Cancel a running execution. No-op if already terminal. In-flight steps complete; no new steps start. */
        def cancel(executionId: Flow.Id.Execution)(using Frame): Unit < (Async & Abort[FlowStoreException]) =
            store.getExecution(executionId).map {
                case Absent                                    => ()
                case Present(state) if state.status.isTerminal => ()
                case Present(state) =>
                    Clock.nowWith { now =>
                        store.updateStatus(executionId, Flow.Status.Cancelled, Flow.Event.Cancelled(state.flowId, executionId, now))
                    }
            }

        /** Cancel all non-terminal executions, optionally filtered by workflow. Returns the count cancelled. */
        def cancelAll(wfId: Maybe[Flow.Id.Workflow] = Maybe.empty)(using Frame): Int < (Async & Abort[FlowStoreException]) =
            val wfIds: Seq[Flow.Id.Workflow] < Sync = wfId match
                case Present(id) => Seq(id)
                case _           => defs.use(_.keys.toArray.toSeq)
            wfIds.map { ids =>
                Kyo.foreach(ids) { id =>
                    store.listExecutions(id, Maybe.empty, Int.MaxValue, 0).map { execs =>
                        Kyo.foreach(execs.filter(!_.status.isTerminal).toSeq) { ex =>
                            Clock.nowWith { now =>
                                store.updateStatus(ex.executionId, Flow.Status.Cancelled, Flow.Event.Cancelled(id, ex.executionId, now))
                                    .andThen(1)
                            }
                        }.map(_.sum)
                    }
                }.map(_.sum)
            }
        end cancelAll

        /** Get paginated event history for an execution. Events are in append order. */
        def history(executionId: Flow.Id.Execution, limit: Int = 50, offset: Int = 0)(using
            Frame
        ): FlowStore.HistoryPage < (Async & Abort[FlowStoreException]) =
            store.getHistory(executionId, limit, offset)

        /** List all inputs for an execution, showing which have been delivered and which are still pending. */
        def inputs(executionId: Flow.Id.Execution)(using
            Frame
        ): Seq[FlowEngine.InputInfo] < (Async & Abort[FlowExecutionNotFoundException | FlowWorkflowNotRegisteredException] & Abort[
            FlowStoreException
        ]) =
            store.getExecution(executionId).map {
                case Absent => Abort.fail(FlowExecutionNotFoundException(executionId.value))
                case Present(state) =>
                    defs.use(_.get(state.flowId)).map {
                        case Absent => Abort.fail(FlowWorkflowNotRegisteredException(state.flowId.value))
                        case Present(defn) =>
                            store.getAllFields(executionId).map { fields =>
                                defn.inputs.map { im =>
                                    FlowEngine.InputInfo(im.name, im.tag.show, delivered = fields.contains(im.name))
                                }
                            }
                    }
            }

        /** Search executions across workflows with optional status filter and pagination. */
        def search(
            wfId: Maybe[Flow.Id.Workflow] = Maybe.empty,
            status: Maybe[Flow.Status] = Maybe.empty,
            limit: Int = 25,
            offset: Int = 0
        )(using Frame): FlowEngine.SearchResult < (Async & Abort[FlowStoreException]) =
            val results = wfId match
                case Present(id) => store.listExecutions(id, status, limit, offset)
                case _ =>
                    defs.use(_.keys.toArray.toSeq).map { ids =>
                        Kyo.foreach(ids)(id =>
                            store.listExecutions(id, status, limit, offset)
                        ).map { chunks =>
                            Chunk.from(
                                chunks.foldLeft(Chunk.empty[FlowStore.ExecutionState])(_ ++ _)
                                    .toSeq.sortBy(_.created)(using Ordering[Instant]).reverse
                                    .drop(offset).take(limit)
                            )
                        }
                    }
            results.map(r => FlowEngine.SearchResult(r.toSeq, r.length))
        end search

        /** Render the execution's flow diagram with progress overlay (completed nodes highlighted). */
        def diagram(executionId: Flow.Id.Execution, format: Flow.DiagramFormat = Flow.DiagramFormat.Mermaid)(using
            Frame
        ): String < (Async & Abort[FlowExecutionNotFoundException | FlowWorkflowNotRegisteredException] & Abort[FlowStoreException]) =
            store.getExecution(executionId).map {
                case Absent => Abort.fail(FlowExecutionNotFoundException(executionId.value))
                case Present(state) =>
                    defs.use(_.get(state.flowId)).map {
                        case Absent => Abort.fail(FlowWorkflowNotRegisteredException(state.flowId.value))
                        case Present(defn) =>
                            store.getAllFields(executionId).map { fields =>
                                store.getHistory(executionId, Int.MaxValue, 0).map { history =>
                                    val completed       = deriveCompleted(history)
                                    val deliveredInputs = deliveredInputNames(defn, fields)
                                    val progress        = FlowEngine.Progress.build(defn.flow, completed ++ deliveredInputs, state.status)
                                    FlowRender.render(defn.flow, format, Maybe(progress))
                                }
                            }
                    }
            }

    end executions

    // --- Registration ---

    /** Register a workflow whose step bodies use only effects the engine already handles. */
    def register(id: Flow.Id.Workflow, flow: Flow[?, ?, ?])(using Frame): Unit < (Async & Abort[FlowStoreException]) =
        registerImpl(id, flow, Maybe.empty)

    /** Register a workflow whose step bodies use custom effects.
      *
      * The runner wraps the entire flow execution, providing effect handlers that step bodies need.
      *
      * ```scala
      * engine.register(wfId, flow)([v] => c => Env.run(config)(c))
      * ```
      */
    def register[S](id: Flow.Id.Workflow, flow: Flow[?, ?, S])(
        runner: [V] => V < S => V < (Async & Scope & Abort[FlowException])
    )(using Frame): Unit < (Async & Abort[FlowStoreException]) =
        registerImpl(id, flow, Maybe(FlowRunner[S](runner)))

    private[kyo] def registerImpl(id: Flow.Id.Workflow, flow: Flow[?, ?, ?], runner: Maybe[FlowRunner])(using
        Frame
    ): Unit < (Async & Abort[FlowStoreException]) =
        val meta   = FlowEngine.WorkflowInfo.of(id.value, flow)
        val schema = WorkflowSchema.of(flow)
        val inputs = kyo.internal.FlowLint.inputMetas(flow)
        val defn   = FlowDefinition(id, flow, runner, inputs, meta, schema)
        defs.getAndUpdate(_.update(id, defn)).unit
            .andThen(store.putWorkflow(meta))
            .andThen(release(id, meta.structuralHash))
    end registerImpl

    /** Takes this workflow's parked executions out of that state, for the definition just registered.
      *
      * Registering a definition is the only event that can make a parked execution runnable again, so it is where the un-parking belongs.
      * The alternative, leaving a parked execution claimable and letting it discover the new definition on a later poll, spins: a claim
      * that changes nothing releases at once and the execution is ready again immediately, with two history events per turn.
      *
      * Only the executions whose own structural hash matches this definition are released. One parked under a different hash stays parked,
      * because this registration is not the definition it is waiting for.
      */
    private def release(id: Flow.Id.Workflow, hash: String)(using Frame): Unit < (Async & Abort[FlowStoreException]) =
        store.listExecutions(id, Maybe(Flow.Status.Parked("")), Int.MaxValue, 0).map { parked =>
            Kyo.foreachDiscard(parked.filter(_.hash == hash)) { state =>
                Clock.nowWith { ts =>
                    store.updateStatus(
                        state.executionId,
                        Flow.Status.Running,
                        Flow.Event.ExecutionResumed(id, state.executionId, executorId, ts)
                    )
                }
            }
        }
    end release

    // --- Internal ---

    private def withEvent[A, S2](eid: Flow.Id.Execution, event: Instant => Flow.Event)(body: => A < S2)(using
        Frame
    ): A < (S2 & Async & Abort[FlowStoreException]) =
        Clock.nowWith(ts => store.appendEvent(eid, event(ts))).andThen(body)

    /** One worker's poll loop, wrapped so that a failure reaching the store retries instead of killing the fiber.
      *
      * A store failure raised while EXECUTING was already handled: the engine caught it, recorded it on the execution, and released the
      * claim. A failure raised while CLAIMING was not, and it killed the worker fiber with nothing logged and no status changed, which
      * left a process that answered its health check while no execution ever progressed again. Every failure here is a failure to reach
      * the store, which is the transient kind, so the loop pauses for the poll timeout and goes around again.
      */
    private[kyo] def worker(
        lease: Duration,
        renewEvery: Duration,
        batchSize: Int,
        pollTimeout: Duration
    )(using Frame): Unit < (Async & Scope & Abort[FlowStoreException]) =
        // The loop goes around HERE, in the continuation of the handler, and never inside `pollOnce`. A poll that
        // recursed into the next one from inside `Abort.run` would leave that handler open across every cycle, so a
        // loop that is meant to run for the process's life would nest one more handler per poll, forever.
        Abort.run[Throwable](pollOnce(lease, renewEvery, batchSize, pollTimeout)).map {
            case Result.Success(_) => worker(lease, renewEvery, batchSize, pollTimeout)
            // An interrupt is the engine going away, not a store that cannot be reached, so it stops the loop rather
            // than being counted against the store's health and slept off. It has to be picked out by hand because
            // `Interrupted` is an ordinary exception: `Result.Panic` carries every throwable `NonFatal` admits, and
            // nothing else here tells it apart from a defect worth another attempt. The worker's own interruption is
            // already stopped at its safepoint; what reaches this arm is one raised inside the poll, by a race or a
            // timeout the store ran internally.
            case Result.Panic(interrupted: Interrupted) => Abort.panic(interrupted)
            case failed                                 =>
                // Both channels, because a store is free to panic even though the SPI gives it somewhere typed to put
                // a failure: an implementation is third-party code, and a defect in one poll must not permanently stop
                // the only loop that moves executions forward while the process still answers its health check.
                val error = failed match
                    case Result.Failure(e) => e
                    case Result.Panic(e)   => e
                    case _                 => new IllegalStateException("worker poll failed with no error")
                liveness.recordFailure(error).andThen {
                    Log.error(s"kyo.flow: executor ${executorId.value} could not poll the store, retrying", error)
                        .andThen(Async.sleep(pollTimeout))
                        .andThen(worker(lease, renewEvery, batchSize, pollTimeout))
                }
        }

    private def pollOnce(
        lease: Duration,
        renewEvery: Duration,
        batchSize: Int,
        pollTimeout: Duration
    )(using Frame): Unit < (Async & Scope & Abort[FlowStoreException]) =
        defs.use(_.keys.toArray.toSet).map { wfIds =>
            if wfIds.isEmpty then Async.sleep(pollTimeout)
            else
                store.claimReady(wfIds, executorId, lease, batchSize, pollTimeout).map { batch =>
                    Kyo.foreachDiscard(batch) { state =>
                        val eid = state.executionId
                        store.renewClaim(eid, executorId, lease).map {
                            case false => ()
                            case true =>
                                withEvent(eid, ts => Flow.Event.ExecutionClaimed(state.flowId, eid, executorId, ts)) {
                                    Fiber.init {
                                        def renew: Unit < (Async & Abort[FlowStoreException]) =
                                            Async.sleep(renewEvery).map { _ =>
                                                store.renewClaim(eid, executorId, lease).map {
                                                    case true  => renew
                                                    case false => ()
                                                }
                                            }
                                        renew
                                    }.map { renewFiber =>
                                        Sync.ensure(renewFiber.interrupt) {
                                            Abort.recover[Throwable] { ex =>
                                                Clock.nowWith { ts =>
                                                    store.updateStatus(
                                                        eid,
                                                        Flow.Status.Failed(ex.getMessage),
                                                        Flow.Event.Failed(state.flowId, eid, ex.getMessage, ts)
                                                    )
                                                }
                                            }(executeOne(eid)).andThen {
                                                withEvent(
                                                    eid,
                                                    ts => Flow.Event.ExecutionReleased(state.flowId, eid, executorId, ts)
                                                ) {
                                                    store.releaseClaim(eid, executorId)
                                                }
                                            }
                                        }
                                    }
                                }
                        }
                    }
                }
        }

    private def executeOne(eid: Flow.Id.Execution)(using Frame): Unit < (Async & Abort[FlowStoreException]) =
        store.getExecution(eid).map {
            case Present(state) if state.status.isTerminal => ()
            case Present(state)                            =>
                // The definition is resolved before the resume is recorded: an execution no registered definition matches is not
                // resumed at all, so it is parked without a resume event, and a parked execution reclaimed on every poll does not
                // grow its history by one resume per cycle.
                defs.use(_.get(state.flowId)).map {
                    case Present(defn) if state.hash == defn.meta.structuralHash =>
                        withEvent(eid, ts => Flow.Event.ExecutionResumed(state.flowId, eid, executorId, ts)) {
                            Abort.run[Throwable] {
                                for
                                    _ <- (state.status match
                                        case Flow.Status.Sleeping(name, _) =>
                                            Clock.nowWith(ts =>
                                                store.updateStatus(
                                                    eid,
                                                    Flow.Status.Running,
                                                    Flow.Event.SleepCompleted(state.flowId, eid, name, ts)
                                                )
                                            )
                                        case _ => ()
                                    ): Unit < (Async & Abort[FlowStoreException])
                                    fields  <- store.getAllFields(eid)
                                    history <- store.getHistory(eid, Int.MaxValue, 0)
                                    record    = rebuildRecord(fields, defn.schema)
                                    completed = deriveCompleted(history)
                                    interp    = new StoreInterpreter(store, eid, state.flowId, executorId, defn)
                                    flowExec = Flow.run(defn.flow, record, completed)(interp)
                                        .map(_.asInstanceOf[Record[Any]])
                                    wrappedExec = defn.runner match
                                        case Present(r) => r.erased(flowExec)
                                        case _          => flowExec
                                    result <- Abort.run[FlowSuspension] {
                                        Abort.run[FlowException] {
                                            wrappedExec
                                        }
                                    }
                                    _ <- handleResult(eid, state.flowId, result)
                                yield ()
                            }.map {
                                // Both arms, not the panic alone. The store's own failures travel this handler as
                                // `Result.Failure` now that the SPI has an error channel, so matching only the panic
                                // dropped them: a store that failed while writing an execution's outcome left it at
                                // Running with nothing recorded, to be claimed and re-run on the next poll. This is
                                // what the SPI means by a failure raised while running an execution failing it.
                                case Result.Success(_) => ()
                                case failed            =>
                                    // A store's own failure is recorded with its kind, the way a flow's declared failure is: it
                                    // arrives here typed, and dropping the type at the last step would leave every store failure
                                    // an unclassified message. A panic has no declared kind, so it keeps none.
                                    val (ex, kind) = failed match
                                        case Result.Failure(e: FlowStoreException) => (e, Maybe(e.kind))
                                        case Result.Failure(e)                     => (e, Maybe.empty)
                                        case Result.Panic(e)                       => (e, Maybe.empty)
                                        case _ => (new IllegalStateException("execution failed with no error"), Maybe.empty)
                                    Clock.nowWith { ts =>
                                        store.updateStatus(
                                            eid,
                                            Flow.Status.Failed(ex.getMessage, kind),
                                            Flow.Event.Failed(state.flowId, eid, ex.getMessage, ts)
                                        )
                                    }
                            }
                        } // end withEvent ExecutionResumed
                    case registered => park(eid, state, registered)
                }
            case _ => ()
        }

    /** Holds an execution no registered definition matches, rather than failing it. See [[Flow.Status.Parked]] for why.
      *
      * Re-parking an execution already parked for the same reason writes nothing: an execution stays parked across as many poll cycles as
      * it takes for a matching definition to be registered, and one event per cycle would bury the history it is being kept for.
      */
    private def park(
        eid: Flow.Id.Execution,
        state: FlowStore.ExecutionState,
        registered: Maybe[FlowDefinition]
    )(using Frame): Unit < (Async & Abort[FlowStoreException]) =
        val startedUnder =
            if state.hash.isEmpty then "no structural hash recorded"
            else s"structural hash ${state.hash}"
        val reason = registered match
            case Present(defn) =>
                s"workflow '${state.flowId.value}' is registered with structural hash ${defn.meta.structuralHash}, " +
                    s"and this execution was started under $startedUnder"
            case _ =>
                s"workflow '${state.flowId.value}' has no definition registered here, " +
                    s"and this execution was started under $startedUnder"
        state.status match
            case Flow.Status.Parked(current) if current == reason => ()
            case _ =>
                Clock.nowWith { ts =>
                    store.updateStatus(eid, Flow.Status.Parked(reason), Flow.Event.Parked(state.flowId, eid, reason, ts))
                }
        end match
    end park

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

    private def deriveCompleted(history: FlowStore.HistoryPage): Set[String] =
        history.events.foldLeft(Set.empty[String]) {
            case (acc, Flow.Event.StepCompleted(_, _, name, _))  => acc + name
            case (acc, Flow.Event.SleepCompleted(_, _, name, _)) => acc + name
            case (acc, _)                                        => acc
        }

    private def deliveredInputNames(defn: FlowDefinition, fields: Dict[String, FlowStore.FieldData]): Set[String] =
        defn.inputs.filter(im => fields.contains(im.name)).map(_.name).toSet

    private def handleResult(
        eid: Flow.Id.Execution,
        flowId: Flow.Id.Workflow,
        result: Result[FlowSuspension, Result[FlowException, Record[Any]]]
    )(using Frame): Unit < (Async & Abort[FlowStoreException]) =
        result match
            case Result.Failure(_: FlowSuspension) => ()
            case _ =>
                store.getExecution(eid).map {
                    case Present(s) if s.status == Flow.Status.Cancelled => ()
                    case _ =>
                        Clock.nowWith { ts =>
                            // A failure the flow raised on its typed channel is recorded with its kind, so a persisted
                            // failure can be grouped by what it was rather than matched on the message text. A panic
                            // has no declared kind: it is whatever escaped, and the class name of an escaped throwable
                            // is not a category a caller declared.
                            def failed(message: String, kind: Maybe[String]) =
                                (Flow.Status.Failed(message, kind), Flow.Event.Failed(flowId, eid, message, ts))
                            val (status, event) = result match
                                case Result.Success(Result.Success(_)) =>
                                    (Flow.Status.Completed, Flow.Event.Completed(flowId, eid, ts))
                                case Result.Success(Result.Failure(err)) => failed(err.getMessage, Maybe(err.kind))
                                case Result.Success(Result.Panic(ex))    => failed(ex.getMessage, Maybe.empty)
                                case Result.Panic(ex)                    => failed(ex.getMessage, Maybe.empty)
                                case _                                   => failed("unknown", Maybe.empty)
                            store.updateStatus(eid, status, event)
                        }
                }

end FlowEngine

object FlowEngine:

    // --- Factory ---

    /** Create an engine with worker fibers that poll the store for ready executions.
      *
      * Each flow must have been built with `Flow.init("name")` so the engine can derive the workflow ID. Flows without a name cause an
      * `IllegalArgumentException`.
      */
    def init(
        store: FlowStore,
        flows: Flow[?, ?, ?]*
    )(using Frame): FlowEngine < (Async & Scope & Abort[FlowStoreException]) =
        init(store, flows = flows)

    def init[S](
        store: FlowStore,
        flows: Flow[?, ?, S]*
    )(runner: [V] => V < S => V < (Async & Scope & Abort[FlowException]))(using
        Frame
    ): FlowEngine < (Async & Scope & Abort[FlowStoreException]) =
        initImpl(store, flows = flows, runner = Maybe(FlowRunner[S](runner)))

    def init(
        store: FlowStore,
        workerCount: Int = 2,
        lease: Duration = 30.seconds,
        renewEvery: Duration = 10.seconds,
        batchSize: Int = 4,
        pollTimeout: Duration = 30.seconds,
        flows: Seq[Flow[?, ?, ?]] = Seq.empty
    )(using Frame): FlowEngine < (Async & Scope & Abort[FlowStoreException]) =
        init(store, Config(workerCount, lease, renewEvery, batchSize, pollTimeout), flows*)

    /** Create a tuned engine.
      *
      * The tuning is one [[FlowEngine.Config]] value rather than a parameter list, so it composes with the runner overload below. Every
      * workflow whose steps touch a resource has a non-trivial effect row and therefore needs a runner, and `lease` is the knob that sets
      * how long crash recovery waits, so the two have to be available together.
      */
    def init(
        store: FlowStore,
        config: Config,
        flows: Flow[?, ?, ?]*
    )(using Frame): FlowEngine < (Async & Scope & Abort[FlowStoreException]) =
        initImpl(store, config, flows, Maybe.empty)

    /** Create a tuned engine whose step bodies use custom effects. */
    def init[S](
        store: FlowStore,
        config: Config,
        flows: Flow[?, ?, S]*
    )(runner: [V] => V < S => V < (Async & Scope & Abort[FlowException]))(using
        Frame
    ): FlowEngine < (Async & Scope & Abort[FlowStoreException]) =
        initImpl(store, config, flows, Maybe(FlowRunner[S](runner)))

    private[kyo] def initImpl(
        store: FlowStore,
        config: Config = Config(),
        flows: Seq[Flow[?, ?, ?]] = Seq.empty,
        runner: Maybe[FlowRunner] = Maybe.empty
    )(using Frame): FlowEngine < (Async & Scope & Abort[FlowStoreException]) =
        import config.*
        for
            defs     <- AtomicRef.init(Dict.empty[Flow.Id.Workflow, FlowDefinition])
            eid      <- Flow.Id.Executor.random
            liveness <- Liveness.init(workerCount)
            engine = new FlowEngine(store, defs, eid, liveness)
            _ <- Kyo.foreachDiscard(flows) { flow =>
                val name = FlowFold(flow)(new FlowVisitorCollect[Maybe[String]](Maybe.empty, (a, b) => a.orElse(b)):
                    override def onInit(name: String, frame: Frame, meta: Flow.Meta) = Maybe(
                        name
                    )).getOrElse(
                    throw new IllegalArgumentException("Flow must have a name — use Flow.init(\"name\") to create named workflows")
                )
                engine.registerImpl(Flow.Id.Workflow(name), flow, runner)
            }
            _ <- Kyo.foreachDiscard(1 to workerCount) { _ =>
                // Counted before the fiber starts and uncounted when it ends, so `health` answers for every worker the
                // engine was asked for from the moment `init` returns. Counting inside the fiber would report zero
                // until the scheduler got to it, which is a health check that fails on a healthy engine.
                liveness.workerStarted.andThen(
                    Fiber.init(
                        Sync.ensure(liveness.workerStopped)(engine.worker(lease, renewEvery, batchSize, pollTimeout))
                    )
                )
            }
        yield engine
        end for
    end initImpl

    /** What [[FlowEngine.health]] answers: how many workers are polling, how many were asked for, and the last poll failure.
      *
      * `workersAlive < workersConfigured` is the condition a health check fails on: the engine is up but is not processing everything it
      * was configured to. `lastPollFailure` is the message of the most recent failure to reach the store, present whether or not the
      * worker that hit it recovered, so a store that is failing intermittently is visible rather than silent.
      *
      * @param workersAlive
      *   worker fibers currently running
      * @param workersConfigured
      *   worker fibers the engine was created with
      * @param pollFailures
      *   how many times a poll has failed since the engine started
      * @param lastPollFailure
      *   the message of the most recent poll failure
      */
    case class Health(
        workersAlive: Int,
        workersConfigured: Int,
        pollFailures: Long,
        lastPollFailure: Maybe[String]
    ) derives CanEqual:
        /** Every worker the engine was configured with is polling. */
        def isHealthy: Boolean = workersAlive == workersConfigured
    end Health

    /** The engine's own view of its workers, written by the workers and read by [[FlowEngine.health]]. */
    final private[kyo] class Liveness(
        configured: Int,
        alive: AtomicInt,
        failures: AtomicLong,
        lastFailure: AtomicRef[Maybe[String]]
    ):
        def workerStarted(using Frame): Unit < Sync = alive.incrementAndGet.unit
        def workerStopped(using Frame): Unit < Sync = alive.decrementAndGet.unit

        def recordFailure(error: Throwable)(using Frame): Unit < Sync =
            failures.incrementAndGet.andThen(lastFailure.set(Maybe(Maybe(error.getMessage).getOrElse(error.toString))))

        def snapshot(using Frame): Health < Sync =
            for
                a <- alive.get
                f <- failures.get
                l <- lastFailure.get
            yield Health(a, configured, f, l)
    end Liveness

    private[kyo] object Liveness:
        def init(configured: Int)(using Frame): Liveness < Sync =
            for
                alive       <- AtomicInt.init(0)
                failures    <- AtomicLong.init(0L)
                lastFailure <- AtomicRef.init(Maybe.empty[String])
            yield new Liveness(configured, alive, failures, lastFailure)
    end Liveness

    /** Engine tuning: how many workers poll, how long a claim lives, and how the poll loop is paced.
      *
      * One value rather than a parameter list, so a caller configures the engine the same way whether or not the flows need a runner.
      * `lease` is the one that decides crash-recovery latency: an execution whose executor died is offered to another only once its claim
      * has expired, so it is the first knob anyone running this in production reaches for.
      *
      * @param workerCount
      *   how many worker fibers poll the store for ready executions
      * @param lease
      *   how long a claim on an execution lasts before another executor may take it
      * @param renewEvery
      *   how often a worker renews the claim of an execution it is running
      * @param batchSize
      *   how many executions one poll claims at once
      * @param pollTimeout
      *   how long a poll waits for a ready execution before going around again
      */
    case class Config(
        workerCount: Int = 2,
        lease: Duration = 30.seconds,
        renewEvery: Duration = 10.seconds,
        batchSize: Int = 4,
        pollTimeout: Duration = 30.seconds
    ) derives CanEqual

    object Config:
        /** The tuning the untuned `init` overloads use. */
        val default: Config = Config()

    // --- Handle ---

    /** A typed reference to a running execution, returned by `workflows.start`. Provides direct access to signal, cancel, describe, and
      * query the execution without needing to pass the execution ID separately.
      */
    final class Handle(val executionId: Flow.Id.Execution, engine: FlowEngine):
        def signal[V: Tag: Schema](name: String, value: V)(using
            Frame
        ): Unit < (Async & Abort[FlowExecutionStateException | FlowWorkflowNotRegisteredException | FlowSignalException] & Abort[
            FlowStoreException
        ]) =
            engine.executions.signal[V](executionId, name, value)

        def status(using Frame): Flow.Status < (Async & Abort[FlowExecutionNotFoundException] & Abort[FlowStoreException]) =
            engine.executions.describe(executionId).map(_.status)

        def describe(using Frame): ExecutionDetail < (Async & Abort[FlowExecutionNotFoundException] & Abort[FlowStoreException]) =
            engine.executions.describe(executionId)

        def cancel(using Frame): Unit < (Async & Abort[FlowStoreException]) =
            engine.executions.cancel(executionId)

        def history(limit: Int = 50, offset: Int = 0)(using Frame): FlowStore.HistoryPage < (Async & Abort[FlowStoreException]) =
            engine.executions.history(executionId, limit, offset)
    end Handle

    // --- Types ---

    /** Status of a single input: name, expected type tag, and whether it has been delivered. */
    case class InputInfo(name: String, tag: String, delivered: Boolean) derives CanEqual

    /** Full detail of an execution: store state, input delivery status, and step-by-step progress. Returned by `executions.describe` and
      * `Handle.describe`.
      */
    case class ExecutionDetail(
        state: FlowStore.ExecutionState,
        inputs: Seq[InputInfo],
        progress: FlowEngine.Progress
    ):
        def executionId: Flow.Id.Execution = state.executionId
        def flowId: Flow.Id.Workflow       = state.flowId
        def status: Flow.Status            = state.status
    end ExecutionDetail

    /** Paginated search result with items and total count. */
    case class SearchResult(items: Seq[FlowStore.ExecutionState], total: Int) derives CanEqual

    /** Metadata for a registered workflow: inputs, outputs, structural hash, and node information. Returned by `workflows.describe` and
      * `workflows.list`. Serializable (no closures) for storage in external databases.
      */
    case class WorkflowInfo(
        id: String,
        meta: Flow.Meta,
        nodes: Seq[WorkflowInfo.NodeInfo],
        inputs: Seq[WorkflowInfo.InputInfo],
        outputs: Seq[String],
        structuralHash: String
    ) derives Schema

    object WorkflowInfo:
        case class NodeInfo(name: String, nodeType: String, tag: Tag[Any], location: String, meta: Flow.Meta) derives Schema
        case class InputInfo(name: String, tag: Tag[Any]) derives Schema

        private[kyo] def of(id: String, flow: Flow[?, ?, ?]): WorkflowInfo =
            val inputMetas  = FlowLint.inputMetas(flow)
            val outputNames = FlowLint.outputNames(flow)
            val hash        = WorkflowSchema.structuralHash(flow)
            val workflowMeta = FlowFold(flow)(new FlowVisitorCollect[Maybe[Flow.Meta]](Maybe.empty, (a, b) => a.orElse(b)):
                override def onInit(name: String, frame: Frame, meta: Flow.Meta) = Maybe(meta)).getOrElse(Flow.Meta())
            val nodes = FlowFold(flow)(new FlowVisitorCollect[Seq[NodeInfo]](Seq.empty, _ ++ _):
                override def onOutput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) =
                    Seq(NodeInfo(name, "output", Tag[V].erased, frame.snippetShort, meta))
                override def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) =
                    Seq(NodeInfo(name, "input", Tag[V].erased, frame.snippetShort, meta))
                override def onStep(name: String, frame: Frame, meta: Flow.Meta) =
                    Seq(NodeInfo(name, "step", Tag[Unit].erased, frame.snippetShort, meta))
                override def onSleep(name: String, duration: Duration, frame: Frame, meta: Flow.Meta) =
                    Seq(NodeInfo(name, "sleep", Tag[Unit].erased, frame.snippetShort, meta))
                override def onDispatch(name: String, branches: Seq[Flow.BranchInfo], frame: Frame, meta: Flow.Meta) =
                    Seq(NodeInfo(name, "dispatch", Tag[Any], frame.snippetShort, meta))
                override def onLoop(name: String, frame: Frame, meta: Flow.Meta) =
                    Seq(NodeInfo(name, "loop", Tag[Any], frame.snippetShort, meta))
                override def onForEach(name: String, concurrency: Int, frame: Frame, meta: Flow.Meta) =
                    Seq(NodeInfo(name, "foreach", Tag[Any], frame.snippetShort, meta))
                override def onSubflow(name: String, childFlow: Flow[?, ?, ?], frame: Frame, meta: Flow.Meta) =
                    Seq(NodeInfo(name, "subflow", Tag[Any], frame.snippetShort, meta)))
            val inputs = inputMetas.map(m => WorkflowInfo.InputInfo(m.name, m.tag))
            WorkflowInfo(
                id = id,
                meta = workflowMeta,
                nodes = nodes,
                inputs = inputs,
                outputs = outputNames,
                structuralHash = hash
            )
        end of
    end WorkflowInfo

    // --- Progress ---

    /** Step-by-step progress of an execution: each flow node's name, type, and current status (Completed, Running, Pending,
      * WaitingForInput, Sleeping, Failed). Used for monitoring dashboards and diagram overlays.
      */
    case class Progress(nodes: Seq[Progress.NodeProgress]) derives CanEqual:
        def nodeByName(name: String): Maybe[Progress.NodeProgress] =
            Maybe.fromOption(nodes.find(_.name == name))
        def completedCount: Int = nodes.count(_.status == Progress.NodeStatus.Completed)
        def totalCount: Int     = nodes.size
    end Progress

    object Progress:
        private[kyo] val empty: Progress = Progress(Seq.empty[NodeProgress])

        case class NodeProgress(name: String, nodeType: NodeType, status: NodeStatus, location: String) derives CanEqual

        enum NodeType derives CanEqual:
            case Input, Output, Step, Sleep, Dispatch, Loop, ForEach, Race, Subflow

        enum NodeStatus derives CanEqual:
            case Completed, Running, Pending, WaitingForInput
            case Sleeping(until: Instant)
            case Failed(error: String)

            /** The one-line rendering a monitoring surface shows, in the same shape [[Flow.Status.show]] uses. */
            def show: String = this match
                case Completed       => "completed"
                case Running         => "running"
                case Pending         => "pending"
                case WaitingForInput => "waiting"
                case Sleeping(until) => s"sleeping:$until"
                case Failed(error)   => s"failed:$error"
        end NodeStatus

        private[kyo] def build(flow: Flow[?, ?, ?], completedSteps: Set[String], currentStatus: Flow.Status): Progress =
            import Progress.{NodeProgress, NodeType, NodeStatus}
            def isCompleted(name: String, nodeType: NodeType): Boolean =
                completedSteps.contains(name) ||
                    ((nodeType == NodeType.Loop || nodeType == NodeType.ForEach) &&
                        completedSteps.exists(IterationName.isIteration(_, name)))
            val rawNodes = FlowFold(flow)(new FlowVisitor[Chunk[NodeProgress]]:
                def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) =
                    Chunk(NodeProgress(name, NodeType.Input, NodeStatus.Pending, frame.snippetShort))
                def onOutput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) =
                    Chunk(NodeProgress(name, NodeType.Output, NodeStatus.Pending, frame.snippetShort))
                def onStep(name: String, frame: Frame, meta: Flow.Meta) =
                    Chunk(NodeProgress(name, NodeType.Step, NodeStatus.Pending, frame.snippetShort))
                def onSleep(name: String, duration: Duration, frame: Frame, meta: Flow.Meta) =
                    Chunk(NodeProgress(name, NodeType.Sleep, NodeStatus.Pending, frame.snippetShort))
                def onDispatch(name: String, branches: Seq[Flow.BranchInfo], frame: Frame, meta: Flow.Meta) =
                    Chunk(NodeProgress(name, NodeType.Dispatch, NodeStatus.Pending, frame.snippetShort))
                def onLoop(name: String, frame: Frame, meta: Flow.Meta) =
                    Chunk(NodeProgress(name, NodeType.Loop, NodeStatus.Pending, frame.snippetShort))
                def onForEach(name: String, concurrency: Int, frame: Frame, meta: Flow.Meta) =
                    Chunk(NodeProgress(name, NodeType.ForEach, NodeStatus.Pending, frame.snippetShort))
                def onRace(left: Chunk[NodeProgress], right: Chunk[NodeProgress], frame: Frame) = left ++ right
                def onSubflow(name: String, childFlow: Flow[?, ?, ?], frame: Frame, meta: Flow.Meta) =
                    Chunk(NodeProgress(name, NodeType.Subflow, NodeStatus.Pending, frame.snippetShort))
                def onAndThen(first: Chunk[NodeProgress], second: Chunk[NodeProgress], frame: Frame) = first ++ second
                def onZip(left: Chunk[NodeProgress], right: Chunk[NodeProgress], frame: Frame)       = left ++ right
                def onGather(flows: Seq[Chunk[NodeProgress]], frame: Frame) = flows.foldLeft(Chunk.empty[NodeProgress])(_ ++ _)
                def onInit(name: String, frame: Frame, meta: Flow.Meta)     = Chunk.empty)
            @scala.annotation.tailrec
            def assignStatuses(idx: Int, foundActive: Boolean, acc: Chunk[NodeProgress]): Chunk[NodeProgress] =
                if idx >= rawNodes.length then acc
                else
                    val node = rawNodes(idx)
                    if isCompleted(node.name, node.nodeType) then
                        assignStatuses(idx + 1, foundActive, acc :+ node.copy(status = NodeStatus.Completed))
                    else
                        currentStatus match
                            case Flow.Status.WaitingForInput(n) if n == node.name =>
                                assignStatuses(idx + 1, true, acc :+ node.copy(status = NodeStatus.WaitingForInput))
                            case Flow.Status.Sleeping(n, until) if n == node.name =>
                                assignStatuses(idx + 1, true, acc :+ node.copy(status = NodeStatus.Sleeping(until)))
                            case Flow.Status.Failed(error, _) if !foundActive =>
                                assignStatuses(idx + 1, true, acc :+ node.copy(status = NodeStatus.Failed(error)))
                            case Flow.Status.Compensating if !foundActive =>
                                assignStatuses(idx + 1, true, acc :+ node.copy(status = NodeStatus.Failed("compensating")))
                            case Flow.Status.Running if !foundActive =>
                                assignStatuses(idx + 1, true, acc :+ node.copy(status = NodeStatus.Running))
                            case _ =>
                                assignStatuses(idx + 1, foundActive, acc :+ node.copy(status = NodeStatus.Pending))
                    end if
            end assignStatuses
            Progress(assignStatuses(0, false, Chunk.empty).toSeq)
        end build
    end Progress

end FlowEngine
