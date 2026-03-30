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
    val executorId: Flow.Id.Executor
)(using Frame):

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
        )(using Frame): FlowEngine.Handle < (Async & Abort[FlowWorkflowNotRegisteredException | FlowSignalTypeMismatchException]) =
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
                                                discard(inputMeta.json.encode(value)); true
                                            catch case _: Throwable => false
                                        if !valid then
                                            Abort.fail[FlowSignalTypeMismatchException](FlowSignalTypeMismatchException(
                                                inputMeta.name,
                                                inputMeta.tag.show,
                                                value.getClass.getSimpleName
                                            ))
                                        else
                                            store.putField[Any](eid, inputMeta.name, value)(using
                                                inputMeta.tag,
                                                inputMeta.json
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
        )(using Frame): Unit < (Async & Abort[FlowWorkflowNotRegisteredException | FlowDuplicateExecutionException]) =
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
        def list(using Frame): Seq[FlowEngine.WorkflowInfo] < Async =
            store.listWorkflows

        /** Get metadata for a registered workflow. Fails with FlowWorkflowNotFoundException if not registered. */
        def describe(workflowId: Flow.Id.Workflow)(using
            Frame
        ): FlowEngine.WorkflowInfo < (Async & Abort[FlowWorkflowNotFoundException]) =
            store.getWorkflow(workflowId).map {
                case Absent     => Abort.fail(FlowWorkflowNotFoundException(workflowId.value))
                case Present(m) => m
            }

        /** List all executions of a workflow, regardless of status. */
        def executions(workflowId: Flow.Id.Workflow)(using Frame): Chunk[FlowStore.ExecutionState] < Async =
            store.listExecutions(workflowId, Maybe.empty, Int.MaxValue, 0)

        /** Render the workflow's structure as a diagram. */
        def diagram(
            workflowId: Flow.Id.Workflow,
            format: Flow.DiagramFormat = Flow.DiagramFormat.Mermaid
        )(using Frame): String < (Async & Abort[FlowWorkflowNotRegisteredException]) =
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
        def signal[V: Tag: Json](
            executionId: Flow.Id.Execution,
            name: String,
            value: V
        )(using
            Frame
        ): Unit < (Async & Abort[FlowExecutionStateException | FlowWorkflowNotRegisteredException | FlowSignalException]) =
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
        ): FlowEngine.ExecutionDetail < (Async & Abort[FlowExecutionNotFoundException]) =
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
        def cancel(executionId: Flow.Id.Execution)(using Frame): Unit < Async =
            store.getExecution(executionId).map {
                case Absent                                    => ()
                case Present(state) if state.status.isTerminal => ()
                case Present(state) =>
                    Clock.nowWith { now =>
                        store.updateStatus(executionId, Flow.Status.Cancelled, Flow.Event.Cancelled(state.flowId, executionId, now))
                    }
            }

        /** Cancel all non-terminal executions, optionally filtered by workflow. Returns the count cancelled. */
        def cancelAll(wfId: Maybe[Flow.Id.Workflow] = Maybe.empty)(using Frame): Int < Async =
            val wfIds: Seq[Flow.Id.Workflow] < Sync = wfId match
                case Present(id) => Seq(id)
                case _ => defs.use { d =>
                        var ids = Seq.empty[Flow.Id.Workflow]
                        d.foreach((k, _) => ids = ids :+ k) // TODO migrate to a new dict.keys
                        ids
                    }
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
        def history(executionId: Flow.Id.Execution, limit: Int = 50, offset: Int = 0)(using Frame): FlowStore.HistoryPage < Async =
            store.getHistory(executionId, limit, offset)

        /** List all inputs for an execution, showing which have been delivered and which are still pending. */
        def inputs(executionId: Flow.Id.Execution)(using
            Frame
        ): Seq[FlowEngine.InputInfo] < (Async & Abort[FlowExecutionNotFoundException | FlowWorkflowNotRegisteredException]) =
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
        )(using Frame): FlowEngine.SearchResult < Async =
            val results = wfId match
                case Present(id) => store.listExecutions(id, status, limit, offset)
                case _ =>
                    defs.use { d =>
                        var ids = Seq.empty[Flow.Id.Workflow]
                        d.foreach((k, _) => ids = ids :+ k) // TODO Let's add dict.keys
                        Kyo.foreach(ids)(id =>
                            store.listExecutions(id, status, limit, offset)
                        )
                            .map(_.foldLeft(Chunk.empty[FlowStore.ExecutionState])(_ ++ _)
                                .toSeq.sortBy(_.created)(using Ordering[Instant]).reverse
                                .drop(offset).take(limit))
                            .map(Chunk.from)
                    }
            results.map(r => FlowEngine.SearchResult(r.toSeq, r.length))
        end search

        /** Render the execution's flow diagram with progress overlay (completed nodes highlighted). */
        def diagram(executionId: Flow.Id.Execution, format: Flow.DiagramFormat = Flow.DiagramFormat.Mermaid)(using
            Frame
        ): String < (Async & Abort[FlowExecutionNotFoundException | FlowWorkflowNotRegisteredException]) =
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

    // --- Registration (private) ---

    private[kyo] def register(id: Flow.Id.Workflow, flow: Flow[?, ?, ?])(using Frame): Unit < Async =
        val meta   = FlowEngine.WorkflowInfo.of(id.value, flow)
        val schema = WorkflowSchema.of(flow)
        val inputs = kyo.internal.FlowLint.inputMetas(flow)
        val defn   = FlowDefinition(id, flow, inputs, meta, schema)
        defs.getAndUpdate(_.update(id, defn)).unit.andThen(store.putWorkflow(meta))
    end register

    // --- Internal ---

    private def withEvent[A, S2](eid: Flow.Id.Execution, event: Instant => Flow.Event)(body: => A < S2)(using Frame): A < (S2 & Async) =
        Clock.nowWith(ts => store.appendEvent(eid, event(ts))).andThen(body)

    private[kyo] def worker(
        lease: Duration,
        renewEvery: Duration,
        batchSize: Int,
        pollTimeout: Duration
    )(using Frame): Unit < (Async & Scope) =
        defs.use { d =>
            var ids = Set.empty[Flow.Id.Workflow]
            d.foreach((k, _) => ids = ids + k) // TODO use the new dict.keys
            ids
        }.map { wfIds =>
            if wfIds.isEmpty then Async.sleep(pollTimeout).andThen(worker(lease, renewEvery, batchSize, pollTimeout))
            else
                store.claimReady(wfIds, executorId, lease, batchSize, pollTimeout).map { batch =>
                    Kyo.foreachDiscard(batch) { state =>
                        val eid = state.executionId
                        store.renewClaim(eid, executorId, lease).map {
                            case false => ()
                            case true =>
                                withEvent(eid, ts => Flow.Event.ExecutionClaimed(state.flowId, eid, executorId, ts)) {
                                    Fiber.init {
                                        def renew: Unit < Async =
                                            Async.sleep(renewEvery).map { _ =>
                                                store.renewClaim(eid, executorId, lease).map {
                                                    case true  => renew
                                                    case false => ()
                                                }
                                            }
                                        renew
                                    }.map { renewFiber =>
                                        executeOne(eid).map { _ =>
                                            renewFiber.interrupt.andThen {
                                                withEvent(eid, ts => Flow.Event.ExecutionReleased(state.flowId, eid, executorId, ts)) {
                                                    store.releaseClaim(eid, executorId)
                                                }
                                            }
                                        }
                                    }
                                }
                        }
                    }.andThen(worker(lease, renewEvery, batchSize, pollTimeout))
                }
        }

    private def executeOne(eid: Flow.Id.Execution)(using Frame): Unit < Async =
        store.getExecution(eid).map {
            case Present(state) if state.status.isTerminal => ()
            case Present(state) =>
                withEvent(eid, ts => Flow.Event.ExecutionResumed(state.flowId, eid, executorId, ts)) {
                    defs.use(_.get(state.flowId)).map {
                        case Present(defn) if state.hash == defn.meta.structuralHash =>
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
                                    ): Unit < Async
                                    fields  <- store.getAllFields(eid)
                                    history <- store.getHistory(eid, Int.MaxValue, 0)
                                    record    = rebuildRecord(fields, defn.schema)
                                    completed = deriveCompleted(history)
                                    interp    = new StoreInterpreter(store, eid, state.flowId, executorId, defn)
                                    result <- Abort.run[FlowSuspension] {
                                        Abort.run[FlowException] {
                                            Flow.run(defn.flow, record, completed)(interp)
                                                .map(_.asInstanceOf[Record[Any]])
                                        }
                                    }
                                    _ <- handleResult(eid, state.flowId, result)
                                yield ()
                            }.map {
                                case Result.Panic(ex) =>
                                    Clock.nowWith { ts =>
                                        store.updateStatus(
                                            eid,
                                            Flow.Status.Failed(ex.getMessage),
                                            Flow.Event.Failed(state.flowId, eid, ex.getMessage, ts)
                                        )
                                    }
                                case _ => ()
                            }
                        case _ =>
                            // Workflow definition not found or hash mismatch — fail the execution
                            Clock.nowWith { ts =>
                                store.updateStatus(
                                    eid,
                                    Flow.Status.Failed("Workflow definition not found or version mismatch"),
                                    Flow.Event.Failed(state.flowId, eid, "Workflow definition not found or version mismatch", ts)
                                )
                            }
                    }
                } // end withEvent ExecutionResumed
            case _ => ()
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
    )(using Frame): Unit < Async =
        result match
            case Result.Failure(_: FlowSuspension) => ()
            case _ =>
                store.getExecution(eid).map {
                    case Present(s) if s.status == Flow.Status.Cancelled => ()
                    case _ =>
                        Clock.nowWith { ts =>
                            val (status, event) = result match
                                case Result.Success(Result.Success(_)) =>
                                    (Flow.Status.Completed, Flow.Event.Completed(flowId, eid, ts))
                                case Result.Success(Result.Failure(err)) =>
                                    (Flow.Status.Failed(err.getMessage), Flow.Event.Failed(flowId, eid, err.getMessage, ts))
                                case Result.Success(Result.Panic(ex)) =>
                                    (Flow.Status.Failed(ex.getMessage), Flow.Event.Failed(flowId, eid, ex.getMessage, ts))
                                case Result.Panic(ex) =>
                                    (Flow.Status.Failed(ex.getMessage), Flow.Event.Failed(flowId, eid, ex.getMessage, ts))
                                case _ =>
                                    (Flow.Status.Failed("unknown"), Flow.Event.Failed(flowId, eid, "unknown", ts))
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
    )(using Frame): FlowEngine < (Async & Scope) =
        init(store, flows = flows)

    def init(
        store: FlowStore,
        workerCount: Int = 2,
        lease: Duration = 30.seconds,
        renewEvery: Duration = 10.seconds,
        batchSize: Int = 4,
        pollTimeout: Duration = 30.seconds,
        flows: Seq[Flow[?, ?, ?]] = Seq.empty
    )(using Frame): FlowEngine < (Async & Scope) =
        for
            defs <- AtomicRef.init(Dict.empty[Flow.Id.Workflow, FlowDefinition])
            eid  <- Flow.Id.Executor.random
            engine = new FlowEngine(store, defs, eid)
            _ <- Kyo.foreachDiscard(flows) { flow =>
                val name = FlowFold(flow)(new FlowVisitorCollect[Maybe[String]](Maybe.empty, (a, b) => a.orElse(b)):
                    override def onInit(name: String, frame: Frame, meta: Flow.Meta) = Maybe(
                        name
                    )).getOrElse(
                    throw new IllegalArgumentException("Flow must have a name — use Flow.init(\"name\") to create named workflows")
                )
                engine.register(Flow.Id.Workflow(name), flow)
            }
            _ <- Kyo.foreachDiscard(1 to workerCount) { _ =>
                Fiber.init(engine.worker(lease, renewEvery, batchSize, pollTimeout))
            }
        yield engine

    // --- Handle ---

    /** A typed reference to a running execution, returned by `workflows.start`. Provides direct access to signal, cancel, describe, and
      * query the execution without needing to pass the execution ID separately.
      */
    final class Handle(val executionId: Flow.Id.Execution, engine: FlowEngine):
        def signal[V: Tag: Json](name: String, value: V)(using
            Frame
        ): Unit < (Async & Abort[FlowExecutionStateException | FlowWorkflowNotRegisteredException | FlowSignalException]) =
            engine.executions.signal[V](executionId, name, value)

        def status(using Frame): Flow.Status < (Async & Abort[FlowExecutionNotFoundException]) =
            engine.executions.describe(executionId).map(_.status)

        def describe(using Frame): ExecutionDetail < (Async & Abort[FlowExecutionNotFoundException]) =
            engine.executions.describe(executionId)

        def cancel(using Frame): Unit < Async =
            engine.executions.cancel(executionId)

        def history(limit: Int = 50, offset: Int = 0)(using Frame): FlowStore.HistoryPage < Async =
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
    ) derives Json

    object WorkflowInfo:
        case class NodeInfo(name: String, nodeType: String, tag: Tag[Any], location: String, meta: Flow.Meta) derives Json
        case class InputInfo(name: String, tag: Tag[Any]) derives Json

        private[kyo] def of(id: String, flow: Flow[?, ?, ?]): WorkflowInfo =
            val inputMetas  = FlowLint.inputMetas(flow)
            val outputNames = FlowLint.outputNames(flow)
            val hash        = WorkflowSchema.structuralHash(flow)
            val workflowMeta = FlowFold(flow)(new FlowVisitorCollect[Maybe[Flow.Meta]](Maybe.empty, (a, b) => a.orElse(b)):
                override def onInit(name: String, frame: Frame, meta: Flow.Meta) = Maybe(meta)).getOrElse(Flow.Meta())
            val nodes = FlowFold(flow)(new FlowVisitorCollect[Seq[NodeInfo]](Seq.empty, _ ++ _):
                override def onOutput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V]) =
                    Seq(NodeInfo(name, "output", Tag[V].erased, frame.snippetShort, meta))
                override def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V]) =
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
        end NodeStatus

        private[kyo] def build(flow: Flow[?, ?, ?], completedSteps: Set[String], currentStatus: Flow.Status): Progress =
            import Progress.{NodeProgress, NodeType, NodeStatus}
            def isCompleted(name: String, nodeType: NodeType): Boolean =
                completedSteps.contains(name) ||
                    ((nodeType == NodeType.Loop || nodeType == NodeType.ForEach) &&
                        completedSteps.exists(IterationName.isIteration(_, name)))
            val rawNodes = FlowFold(flow)(new FlowVisitor[Chunk[NodeProgress]]:
                def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V]) =
                    Chunk(NodeProgress(name, NodeType.Input, NodeStatus.Pending, frame.snippetShort))
                def onOutput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V]) =
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
                            case Flow.Status.Failed(error) if !foundActive =>
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
