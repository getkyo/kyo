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
  * input signals, check status, cancel, search, view history). Starting an execution returns a `Handle`, a lightweight reference that
  * wraps the execution ID and delegates to the engine.
  *
  * Multiple engines can share the same store for horizontal scaling. The store's atomic `claimReady` ensures each execution is processed by
  * exactly one engine at a time. If an engine dies, its leases expire and the executions it was carrying become claimable again, so
  * another engine picks them up. This means completed steps are never re-executed, but an in-flight step (started but not completed) may
  * re-execute on a new engine, so side-effecting steps must be idempotent.
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
    private[kyo] val defs: AtomicRef[FlowEngine.Definitions],
    val executorId: Flow.Id.Executor,
    private[kyo] val liveness: FlowEngine.Liveness,
    private[kyo] val supervisions: AtomicRef[Maybe[Set[Fiber[Unit, Any]]]]
)(using Frame):

    import FlowEngine.Attempt
    import FlowEngine.Definitions

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
        ): FlowEngine.Handle < (Async & Abort[FlowWorkflowNotRegisteredException | FlowSignalTypeMismatchException | FlowStoreException]) =
            defs.use(_.latest.get(workflowId)).map {
                case Absent        => Abort.fail(FlowWorkflowNotRegisteredException(workflowId.value))
                case Present(defn) =>
                    // Encoded first, so a start that is going to be refused writes nothing at all. Creation then carries the seeds,
                    // which is what keeps a crash from leaving an execution that holds some of its declared inputs and not others.
                    seedFields(defn, inputs).map { seeds =>
                        for
                            eid <- Flow.Id.Execution.random
                            now <- Clock.now
                            created <- store.createExecutionIfAbsent(
                                eid,
                                Flow.Status.Running,
                                Flow.Event.Created(workflowId, eid, now),
                                defn.meta.structuralHash,
                                seeds
                            )
                            _ <- requireCreated(created, eid)
                        yield new FlowEngine.Handle(eid, FlowEngine.this)
                    }
            }

        /** Create an execution with a specific ID. Fails with FlowDuplicateExecutionException if the ID already exists. */
        def start(
            workflowId: Flow.Id.Workflow,
            executionId: Flow.Id.Execution
        )(using
            Frame
        ): Unit < (Async & Abort[FlowWorkflowNotRegisteredException | FlowDuplicateExecutionException | FlowStoreException]) =
            defs.use(_.latest.get(workflowId)).map {
                case Absent => Abort.fail(FlowWorkflowNotRegisteredException(workflowId.value))
                case Present(defn) =>
                    Clock.nowWith { now =>
                        // The store decides, in one operation. A read followed by a write let two concurrent starts on one id both
                        // pass the check, and the loser's create then reset a running execution's history.
                        store.createExecutionIfAbsent(
                            executionId,
                            Flow.Status.Running,
                            Flow.Event.Created(workflowId, executionId, now),
                            defn.meta.structuralHash,
                            Dict.empty
                        ).map {
                            case true  => ()
                            case false => Abort.fail(FlowDuplicateExecutionException(executionId.value))
                        }
                    }
            }

        /** The start-supplied values for the definition's declared inputs, encoded, refusing the whole start on the first that cannot be.
          *
          * Validation runs before anything is written, which is what makes a refused start leave no execution behind.
          *
          * **The width of the catch is forced, and it is not correct, it is honest.** Encoding has no total form: `Schema.encodeString`
          * answers a `String` or throws, so the only way to learn that a supplied value does not match its declared input is to attempt
          * the write and see what comes back. What comes back is not the same thing everywhere. On the JVM a mismatched value raises
          * `ClassCastException` from the generated writer, which a typed catch could name; on JS and Wasm the same cast is UNDEFINED
          * BEHAVIOR, and the linker raises `UndefinedBehaviorError` instead. A narrower catch is therefore not a narrower contract
          * here, it is a contract that holds on one platform and lets the host die on two.
          *
          * **It is caught as a `Result`, and that is load-bearing rather than a matter of taste.** `UndefinedBehaviorError` extends
          * `VirtualMachineError`, so `NonFatal` rejects it, and every catch built on `NonFatal`, `Abort.catching` included, lets it
          * through to kill the host. `Result.catching` is a plain `try`/`catch` around one expression, so it is the only form here that
          * reaches the failure on all four platforms while still answering with a value.
          *
          * **What bounds it is the call, not the catch.** The guarded expression is one synchronous, pure encode with no suspension
          * point inside it, so in practice nothing but the encoder's own failure passes through, and the whole start is refused before
          * anything is written either way. The residual is real and is pinned rather than hidden: a schema whose write throws something
          * of its own is reported to the caller as a type mismatch, which the leaf named for that says out loud.
          *
          * **The closure is upstream.** A total encode in kyo-schema (`Result[EncodeException, String]`, or a generated writer that
          * converts rather than casts) makes the failure a value on every platform, after which this reads it and the catch disappears.
          */
        private def seedFields(defn: FlowDefinition, inputs: Record[Any])(using
            Frame
        ): Dict[String, FlowStore.FieldData] < Abort[FlowSignalTypeMismatchException] =
            val supplied = inputs.toDict
            Kyo.foldLeft(Chunk.from(defn.inputs))(Dict.empty[String, FlowStore.FieldData]) { (acc, inputMeta) =>
                supplied.get(inputMeta.name) match
                    case Present(value) =>
                        Result.catching[Throwable](inputMeta.schema.encodeString[Json](value)) match
                            case Result.Success(text) =>
                                acc.update(inputMeta.name, new FlowStore.FieldData(text, inputMeta.tag))
                            case _ =>
                                Abort.fail(FlowSignalTypeMismatchException(
                                    inputMeta.name,
                                    inputMeta.tag.show,
                                    value.getClass.getSimpleName
                                ))
                    case _ => acc
            }
        end seedFields

        /** Refuses to hand back a handle for an execution this call did not create.
          *
          * [[FlowStore.createExecutionIfAbsent]] answers whether THIS call created the row, and a false says the id was already
          * taken. The handle would then address SOMEBODY ELSE'S execution: every later `signal`, `cancel` and `describe` through it
          * would reach a stranger's, while the execution the caller asked for does not exist and never will.
          *
          * It is raised rather than returned on a typed channel because there is no answer a caller could act on. The id was
          * generated here, so a collision is a defect in the id source or in the store, not something the call did wrong; the
          * start-with-a-given-id overload, where the caller chose the id, answers [[FlowDuplicateExecutionException]] instead.
          */
        private def requireCreated(created: Boolean, executionId: Flow.Id.Execution)(using
            Frame
        ): Unit < Abort[FlowStoreException] =
            if created then ()
            else
                Abort.panic(new IllegalStateException(
                    s"the store already holds an execution under the freshly generated id ${executionId.value}, so no handle " +
                        "can be returned for the execution this call was asked to start"
                ))

        /** List all registered workflows with their metadata. */
        def list(using Frame): Seq[FlowEngine.WorkflowInfo] < (Async & Abort[FlowStoreException]) =
            store.listWorkflows

        /** Get metadata for a registered workflow. Fails with FlowWorkflowNotFoundException if not registered. */
        def describe(workflowId: Flow.Id.Workflow)(using
            Frame
        ): FlowEngine.WorkflowInfo < (Async & Abort[FlowWorkflowNotFoundException | FlowStoreException]) =
            store.getWorkflow(workflowId).map {
                case Absent     => Abort.fail(FlowWorkflowNotFoundException(workflowId.value))
                case Present(m) => m
            }

        /** List all executions of a workflow, regardless of status. */
        def executions(workflowId: Flow.Id.Workflow)(using Frame): Chunk[FlowStore.ExecutionState] < (Async & Abort[FlowStoreException]) =
            store.listExecutions(workflowId, Maybe.empty, Maybe.empty, 0)

        /** Render the workflow's structure as a diagram. */
        def diagram(
            workflowId: Flow.Id.Workflow,
            format: Flow.DiagramFormat = Flow.DiagramFormat.Mermaid
        )(using Frame): String < (Async & Abort[FlowWorkflowNotRegisteredException | FlowStoreException]) =
            defs.use(_.latest.get(workflowId)).map {
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
          * input was already delivered. **Delivery is exactly-once and the store judges it**: the field and the `InputReceived` event
          * are one transition, refused against an execution that is already over, so a value cannot land on an execution that finished
          * while the delivery was in flight and leave the caller told it succeeded.
          */
        def signal[V: Tag: Schema](
            executionId: Flow.Id.Execution,
            name: String,
            value: V
        )(using
            Frame
        ): Unit < (Async & Abort[
            FlowExecutionStateException | FlowWorkflowNotRegisteredException | FlowSignalException | FlowStoreException
        ]) =
            store.getExecution(executionId).map {
                case Absent => Abort.fail(FlowExecutionNotFoundException(executionId.value))
                case Present(state) if state.status.isTerminal =>
                    Abort.fail(FlowExecutionTerminalException(executionId.value, state.status))
                case Present(state) =>
                    defs.use(_.of(state.flowId, state.hash)).map {
                        case Absent => Abort.fail(FlowWorkflowNotRegisteredException(state.flowId.value))
                        case Present(defn) =>
                            Maybe.fromOption(defn.inputs.find(_.name == name)) match
                                case Absent =>
                                    Abort.fail(FlowSignalNotFoundException(name, executionId.value))
                                case Present(inputMeta) =>
                                    if !(Tag[V] =:= inputMeta.tag) then
                                        Abort.fail(FlowSignalTypeMismatchException(name, inputMeta.tag.show, Tag[V].show))
                                    else
                                        Clock.nowWith { ts =>
                                            store.signal[V](
                                                executionId,
                                                name,
                                                value,
                                                Flow.Event.InputReceived(state.flowId, executionId, name, ts)
                                            )
                                        }.map {
                                            case FlowStore.SignalOutcome.Delivered => ()
                                            case FlowStore.SignalOutcome.AlreadyDelivered =>
                                                Abort.fail(FlowInputAlreadyDeliveredException(executionId.value, name))
                                            // The read above found the execution running and the store found it over, which is the
                                            // window a check-then-write leaves open and the store closes by judging both facts at once.
                                            case FlowStore.SignalOutcome.AlreadyTerminal(status) =>
                                                Abort.fail(FlowExecutionTerminalException(executionId.value, status))
                                        }
                    }
            }

        /** Get full execution detail including status, progress, and pending input information. */
        def describe(executionId: Flow.Id.Execution)(using
            Frame
        ): FlowEngine.ExecutionDetail < (Async & Abort[FlowExecutionNotFoundException | FlowStoreException]) =
            store.getExecution(executionId).map {
                case Absent => Abort.fail(FlowExecutionNotFoundException(executionId.value))
                case Present(state) =>
                    defs.use(_.of(state.flowId, state.hash)).map {
                        case Absent =>
                            FlowEngine.ExecutionDetail(state, Seq.empty, FlowEngine.Progress.empty)
                        case Present(defn) =>
                            for
                                fields  <- store.getAllFields(executionId)
                                history <- store.getHistory(executionId, Maybe.empty, 0)
                                completed       = deriveCompleted(history)
                                compensated     = deriveCompensated(history)
                                failed          = deriveFailed(history)
                                deliveredInputs = deliveredInputNames(defn, fields)
                                progress = FlowEngine.Progress.build(
                                    defn.flow,
                                    completed ++ deliveredInputs,
                                    state.status,
                                    state.waits,
                                    compensated,
                                    failed
                                )
                                inputInfos = defn.inputs.map { im =>
                                    FlowEngine.InputStatus(im.name, im.tag.show, delivered = fields.contains(im.name))
                                }
                            yield FlowEngine.ExecutionDetail(state, inputInfos, progress)
                    }
            }

        /** Ask for a running execution to be cancelled, and say what the ask did.
          *
          * **The request returns before the execution stops, and that is the contract.** Cancelling runs the execution's
          * compensations, so the terminal `Cancelled` is written at the END of an unwind: an executor observes the request at the next
          * node boundary, no further step starts, the handlers run in reverse, and only then does the status change. Until then the
          * execution reads as cancelling, which [[FlowStore.ExecutionState.cancelRequested]] carries and
          * [[FlowStore.ExecutionFilter.Cancelling]] selects on.
          *
          * **Which is exactly why the answer is not `Unit`.** Nothing about a cancel is observable on return except this value, so
          * collapsing the three answers leaves no caller able to tell an accepted request from a no-op against an execution that was
          * already over. [[FlowStore.CancelOutcome.Accepted]] means the request is now outstanding and the unwind will run,
          * [[FlowStore.CancelOutcome.AlreadyRequested]] means somebody asked first and it still stands, and
          * [[FlowStore.CancelOutcome.AlreadyTerminal]] means there was nothing to cancel and carries what the execution ended as. The
          * last is an answer and not a failure: a caller that cancels something already finished got what it wanted, and what it
          * needs back is the terminal status rather than an error.
          */
        def cancel(executionId: Flow.Id.Execution)(using Frame): FlowStore.CancelOutcome < (Async & Abort[FlowStoreException]) =
            store.requestCancel(executionId)

        /** Ask for every non-terminal execution to be cancelled, optionally filtered by workflow. Returns how many requests this call
          * put on.
          *
          * **Requests issued, not executions cancelled, and the difference is the whole meaning of the number.** Cancelling runs an
          * execution's compensations, so an execution counted here has been ASKED to stop and may still be unwinding minutes later,
          * and one that finished on its own before the request was observed was never asked at all. A count named for completed work,
          * returned by a method whose work is deferred, tells an operator the sweep is over when it has just begun.
          *
          * Counted exactly once each: an execution somebody had already asked to cancel is not counted again, and one that reached a
          * terminal status in between is not counted at all.
          */
        def cancelAll(wfId: Maybe[Flow.Id.Workflow] = Maybe.empty)(using Frame): Int < (Async & Abort[FlowStoreException]) =
            val wfIds: Seq[Flow.Id.Workflow] < (Async & Abort[FlowStoreException]) = wfId match
                case Present(id) => Seq(id)
                case _           => sweptWorkflowIds
            wfIds.map { ids =>
                Kyo.foreach(ids) { id =>
                    store.listExecutions(id, Maybe.empty, Maybe.empty, 0).map { execs =>
                        Kyo.foreach(execs.filter(!_.status.isTerminal).toSeq) { ex =>
                            store.requestCancel(ex.executionId).map {
                                case FlowStore.CancelOutcome.Accepted => 1
                                case _                                => 0
                            }
                        }.map(_.sum)
                    }
                }.map(_.sum)
            }
        end cancelAll

        /** Get paginated event history for an execution. Events are in append order.
          *
          * A negative `limit` reads as unbounded rather than as an empty page, the contract [[FlowStore.getHistory]] states and every
          * implementation keeps, because an empty page that still claims another follows is the pair a paging caller loops on forever.
          * `offset` is expected non-negative; the HTTP surface refuses a negative one rather than passing it down.
          */
        def history(executionId: Flow.Id.Execution, limit: Int = 50, offset: Int = 0)(using
            Frame
        ): FlowStore.HistoryPage < (Async & Abort[FlowStoreException]) =
            store.getHistory(executionId, Maybe(limit), offset)

        /** List all inputs for an execution, showing which have been delivered and which are still pending. */
        def inputs(executionId: Flow.Id.Execution)(using
            Frame
        ): Seq[FlowEngine.InputStatus] < (Async & Abort[
            FlowExecutionNotFoundException | FlowWorkflowNotRegisteredException | FlowStoreException
        ]) =
            store.getExecution(executionId).map {
                case Absent => Abort.fail(FlowExecutionNotFoundException(executionId.value))
                case Present(state) =>
                    defs.use(_.of(state.flowId, state.hash)).map {
                        case Absent => Abort.fail(FlowWorkflowNotRegisteredException(state.flowId.value))
                        case Present(defn) =>
                            store.getAllFields(executionId).map { fields =>
                                defn.inputs.map { im =>
                                    FlowEngine.InputStatus(im.name, im.tag.show, delivered = fields.contains(im.name))
                                }
                            }
                    }
            }

        /** Search executions, most recently created first, with an optional filter the store evaluates before it selects.
          *
          * **The page is cut once, here, over the matches from every workflow the sweep covers.** Asking each workflow for
          * `(limit, offset)` and then paging the merged answer applies the offset twice, so six executions across two workflows paged
          * at two from an offset of two answer nothing at all; and truncating each workflow before the merge evicts rows that belong
          * in the global page whenever one workflow's executions are newer than another's, which is wrong on the first page too.
          *
          * **`total` is how many matched, not how many came back**, because a field named `total` beside `items`, returned from a
          * method taking `limit` and `offset`, is the size of the set being paged through, and a caller that divides it by its page
          * size or compares it against what it has collected reads it that way. Answering it costs reading every match: the store's
          * vocabulary has no count, so the matches are what is counted.
          *
          * A negative `limit` reads as unbounded rather than as an empty page, the contract [[FlowStore.listExecutions]] states for
          * the same value, because an empty page is the one answer a caller cannot tell from "nothing matched".
          */
        def search(
            wfId: Maybe[Flow.Id.Workflow] = Maybe.empty,
            filter: Maybe[FlowStore.ExecutionFilter] = Maybe.empty,
            limit: Int = 25,
            offset: Int = 0
        )(using Frame): FlowEngine.SearchResult < (Async & Abort[FlowStoreException]) =
            val matched: Chunk[FlowStore.ExecutionState] < (Async & Abort[FlowStoreException]) = wfId match
                case Present(id) => store.listExecutions(id, filter, Maybe.empty, 0)
                case _ =>
                    sweptWorkflowIds.map { ids =>
                        Kyo.foreach(ids)(id => store.listExecutions(id, filter, Maybe.empty, 0)).map { chunks =>
                            chunks.foldLeft(Chunk.empty[FlowStore.ExecutionState])(_ ++ _)
                        }
                    }
            matched.map { all =>
                // Sorted descending in one pass rather than ascending-and-reversed, so the sort is stable over rows sharing a
                // `created` instant and leaves an already-ordered answer from the store exactly as it found it.
                val ordered = all.toSeq.sortBy(_.created)(using Ordering[Instant].reverse)
                val dropped = ordered.drop(offset)
                val page    = if limit < 0 then dropped else dropped.take(limit)
                FlowEngine.SearchResult(page, all.length)
            }
        end search

        /** Render the execution's flow diagram with progress overlay (completed nodes highlighted). */
        def diagram(executionId: Flow.Id.Execution, format: Flow.DiagramFormat = Flow.DiagramFormat.Mermaid)(using
            Frame
        ): String < (Async & Abort[FlowExecutionNotFoundException | FlowWorkflowNotRegisteredException | FlowStoreException]) =
            store.getExecution(executionId).map {
                case Absent => Abort.fail(FlowExecutionNotFoundException(executionId.value))
                case Present(state) =>
                    defs.use(_.of(state.flowId, state.hash)).map {
                        case Absent => Abort.fail(FlowWorkflowNotRegisteredException(state.flowId.value))
                        case Present(defn) =>
                            store.getAllFields(executionId).map { fields =>
                                store.getHistory(executionId, Maybe.empty, 0).map { history =>
                                    val completed       = deriveCompleted(history)
                                    val compensated     = deriveCompensated(history)
                                    val failed          = deriveFailed(history)
                                    val deliveredInputs = deliveredInputNames(defn, fields)
                                    val progress = FlowEngine.Progress.build(
                                        defn.flow,
                                        completed ++ deliveredInputs,
                                        state.status,
                                        state.waits,
                                        compensated,
                                        failed
                                    )
                                    FlowRender.render(defn.flow, format, Maybe(progress))
                                }
                            }
                    }
            }

    end executions

    // --- Registration ---

    /** Register a workflow whose step bodies use only effects the engine already handles.
      *
      * Refuses a definition that cannot be executed durably: one with no name, one using a reserved character in a node name, and one in
      * which two nodes claim a single durable name. See [[FlowDefinitionException]] for what each means and what the answer to it is.
      */
    def register(id: Flow.Id.Workflow, flow: Flow[?, ?, ?])(using
        Frame
    ): Unit < (Async & Abort[FlowDefinitionException | FlowStoreException]) =
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
    )(using Frame): Unit < (Async & Abort[FlowDefinitionException | FlowStoreException]) =
        registerImpl(id, flow, Maybe(FlowRunner[S](runner)))

    private[kyo] def registerImpl(id: Flow.Id.Workflow, flow: Flow[?, ?, ?], runner: Maybe[FlowRunner])(using
        Frame
    ): Unit < (Async & Abort[FlowDefinitionException | FlowStoreException]) =
        validate(id, flow).andThen {
            val meta   = FlowEngine.WorkflowInfo.of(id.value, flow)
            val schema = WorkflowSchema.of(flow)
            val inputs = kyo.internal.FlowLint.inputMetas(flow)
            val defn   = FlowDefinition(id, flow, runner, inputs, meta, schema)
            // The definition goes into the map BEFORE the store hears about it, because `putWorkflow` wakes the pollers and a woken
            // poll asks this map what it serves. Nothing else is needed to recover an execution held for this version: readiness
            // gates on the served set, so registering the version is what makes the row claimable.
            defs.getAndUpdate(_.register(id, defn)).unit
                .andThen(store.putWorkflow(meta))
        }
    end registerImpl

    /** Decides whether a definition can be executed durably at all, and refuses it here if it cannot.
      *
      * Registration is where these belong because it is the first place with both the whole definition and an effect row. A constructor
      * sees one node at a time, so it can neither know that a later node will claim the same name nor report what it does know as anything
      * but a throw, which is the untyped refusal this replaces.
      *
      * The checks are ordered by how much of the definition each one trusts. A flow with no name has no identity to be refused under; a
      * reserved character makes a name unusable on its own terms; only then is it worth asking how the names relate to each other.
      */
    private def validate(id: Flow.Id.Workflow, flow: Flow[?, ?, ?])(using Frame): Unit < Abort[FlowDefinitionException] =
        FlowLint.flowName(flow) match
            case Present(name) if name.nonEmpty =>
                val reserved = FlowLint.reservedNames(flow)
                if reserved.nonEmpty then Abort.fail(FlowReservedNameException(id.value, reserved))
                else
                    val conflicts = FlowLint.nameConflicts(flow)
                    if conflicts.nonEmpty then Abort.fail(FlowDuplicateNameException(id.value, conflicts))
                    else ()
                end if
            case _ => Abort.fail(FlowUnnamedException())

    /** The workflow's non-terminal executions whose version this engine does not serve, each with its own hash and cancel flag.
      *
      * **A report, not a status.** Nothing is written about being held in either direction: an execution is held exactly while no
      * registered definition matches its structural hash, which is a fact about what this engine serves and goes stale the moment an
      * operator registers something. A written status would have to be un-written by whoever noticed, and an execution held under one
      * engine may be running perfectly well under another.
      *
      * **Held rather than failed, and the difference is what an operator can do about it.** A structural change to a deployed flow
      * leaves its in-flight executions matched to a definition that is no longer the one registered under their workflow id, and replaying
      * them against the new one would run a different flow. A held execution keeps its fields, its history and its place, and runs again on
      * its own once a definition with its hash is registered, which makes rolling the deployment back the recovery. Failing it instead is
      * unrecoverable and skips every compensation on the way out, because the handlers live in the definition it can no longer be matched
      * to.
      *
      * Each entry carries the execution's own `hash`, which is the fact an operator needs: an execution held here declares the version it
      * was started under, and whether any engine still serves that version is what registration decides.
      *
      * The predicate is evaluated by the store, before it paginates: computing it here over a fetched page would return pages the
      * predicate then ate.
      *
      * A negative `limit` reads as unbounded rather than as an empty page, the contract [[FlowStore.listExecutions]] states and every
      * implementation keeps. `offset` is expected non-negative.
      */
    def parked(
        workflowId: Flow.Id.Workflow,
        limit: Int = Int.MaxValue,
        offset: Int = 0
    )(using Frame): Chunk[FlowStore.ExecutionState] < (Async & Abort[FlowStoreException]) =
        defs.use(_.servedHashes(workflowId)).map { hashes =>
            store.listExecutions(workflowId, Maybe(FlowStore.ExecutionFilter.Orphaned(hashes)), Maybe(limit), offset)
        }

    // --- Internal ---

    /** Every workflow a sweep over "everything" has to cover: the ones the store holds, plus the ones this engine registered.
      *
      * **Not the engine's own registry, which is the wrong population to be blind to.** An execution written by another engine, or
      * left behind by a workflow this process no longer registers, belongs to a workflow the store knows about and this engine does not
      * serve. Such an execution is also unclaimable here, since readiness gates on the served set, so it makes no progress and emits no
      * events, which makes it exactly the one an operator is hunting; enumerating the registry that does not contain it is how it
      * becomes invisible to every surface at once.
      *
      * The engine's own ids are unioned in rather than trusted away, so a workflow this process registered is still swept if the
      * store's workflow table has not caught up with the registration.
      */
    private def sweptWorkflowIds(using Frame): Seq[Flow.Id.Workflow] < (Async & Abort[FlowStoreException]) =
        store.listWorkflows.map { held =>
            defs.use(_.workflowIds).map { registered =>
                (held.map(w => Flow.Id.Workflow(w.id)) ++ registered).distinct
            }
        }

    /** Appends one event under the claim, answering whether the claim still held.
      *
      * Every event the engine writes about an attempt is a claimed write like every other, so an executor whose row was taken cannot
      * narrate its own progress into an execution somebody else is running. Two workers of one engine share an executor id, which is
      * why an owner-matched append is not even a store no-op for the loser.
      */
    private def claimedEvent(claimed: FlowStore.Claimed, event: Instant => Flow.Event)(using
        Frame
    ): Boolean < (Async & Abort[FlowStoreException]) =
        Clock.nowWith(ts => claimed.appendEvent(event(ts))).map {
            case FlowStore.WriteOutcome.Applied   => true
            case FlowStore.WriteOutcome.ClaimLost => false
        }

    /** One worker's poll loop, wrapped so that a failure reaching the store retries instead of killing the fiber.
      *
      * A store failure raised while EXECUTING reaches no verdict: it is charged to health, nothing is written about the execution and
      * the claim is left to lapse ([[finish]]'s `Infra` arm). A failure raised while CLAIMING has no such home, and left to escape it
      * kills the worker fiber with nothing logged and no status changed, leaving a process that answers its health check while no
      * execution ever progresses again. Every failure here is a failure to reach the store, which is the transient kind, so the loop
      * pauses for the poll timeout and goes around again.
      */
    private[kyo] def worker(
        lease: Duration,
        renewEvery: Duration,
        batchSize: Int,
        pollTimeout: Duration
    )(using Frame): Unit < (Async & Abort[FlowStoreException]) =
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
    )(using Frame): Unit < (Async & Abort[FlowStoreException]) =
        defs.use(identity).map { registered =>
            val served = registered.served
            if served.isEmpty then Async.sleep(pollTimeout)
            else
                // The served set is re-read on every poll, which is what an early empty answer is for: the store returns
                // one when a definition is registered, and it means poll again with the wider set rather than that the
                // timeout elapsed. Nothing here needs to tell the two apart, because an empty batch walks nothing and
                // the loop's next ask is another BLOCKING claim, so one registration buys one re-attempt, never a spin.
                store.claimReady(served, executorId, lease, batchSize, pollTimeout).map { batch =>
                    // The walk CONFIRMS each claim and starts the execution, then moves on. Awaiting the execution here
                    // would make the batch serial: everything past the first would sit claimed with no renewal fiber
                    // until its turn came, so a first member that runs for an hour starves the rest for an hour and lets
                    // their leases lapse while they queue. Each execution already has a fiber and a renewal of its own,
                    // so its supervision belongs beside them rather than in front of the next execution.
                    //
                    // The confirmation stays HERE rather than moving into the fiber, and the difference is observable.
                    // A claim can lapse between being granted and being confirmed, and the row is then claimable by
                    // whoever polls next, this executor included. Asking from the fiber puts that question in a race
                    // with this worker's own next poll: the poll re-claims the row under a fresh lease, and the fiber
                    // would then be renewing a generation the row no longer carries. The renewal answers that correctly
                    // because it is judged against the token rather than against the executor id, and asking here
                    // answers it before the next poll can be made either way.
                    Kyo.foreachDiscard(batch) { claimed =>
                        // The definition is in hand before the attempt starts, and it is the one the execution was STARTED
                        // under: readiness handed this row over because its `(workflow, hash)` is in the set above, and the
                        // map only grows within a process, so the lookup cannot miss. A row that somehow arrives unserved is
                        // left exactly as a refused renewal leaves one, claimed until its lease lapses.
                        registered.of(claimed.state.flowId, claimed.state.hash) match
                            case Absent => ()
                            case Present(defn) =>
                                claimed.renewClaim(lease).map {
                                    // Nothing is written and nothing is released: a release presented on a lapsed claim is
                                    // refused by the same rule that refused the renewal, and the row is already claimable.
                                    case false => ()
                                    case true  => superviseDetached(claimed, defn, lease, renewEvery)
                                }
                        end match
                    }
                }
            end if
        }

    /** Spawns one attempt's supervision, tracked for the engine's shutdown and forgotten the moment it ends.
      *
      * **An attempt's fibers must not outlive the attempt in the engine's bookkeeping.** [[Fiber.init]] is
      * `Scope.acquireRelease(...)(_.interrupt)`, and a scope's finalizer store only ever grows: nothing removes an entry when the
      * fiber it would interrupt has already finished. Spawning the supervision under the engine's own scope therefore leaves one dead
      * entry per attempt, for the life of a process that is meant to run for the life of the process, and an execution that parks and
      * resumes a thousand times pays a thousand of them. Each entry retains its closure and the finished fiber's result.
      *
      * So the supervision is spawned detached and tracked here instead, where an ending removes it. The registry is what shutdown
      * interrupts, which is the one thing the scope entry was for; [[Absent]] is the engine's scope having closed, and a supervision
      * that arrives after that is interrupted at once rather than being tracked by a registry nobody will read again. Registering
      * BEFORE arranging the removal is what keeps a fiber that finishes immediately from leaving its entry behind: `onComplete` on a
      * finished fiber runs its callback now.
      */
    private def superviseDetached(
        claimed: FlowStore.Claimed,
        defn: FlowDefinition,
        lease: Duration,
        renewEvery: Duration
    )(using Frame): Unit < Sync =
        Fiber.initUnscoped(supervise(claimed, defn, lease, renewEvery)).map { fiber =>
            supervisions.updateAndGet {
                case Present(live) => Present(live + fiber)
                case _             => Absent
            }.map {
                case Present(_) => fiber.onComplete(_ => supervisions.updateAndGet(_.map(_ - fiber)).unit)
                case _          => fiber.interrupt.unit
            }
        }

    /** Interrupts every attempt this engine is still carrying, and refuses to track another.
      *
      * Registered on the engine's scope, so closing it stops the executions the engine was running. Nothing is written about any of
      * them: an interrupted attempt reaches no verdict, and each supervision's own scope interrupts the execution and renewal fibers
      * below it on the way out.
      */
    private[kyo] def stopSupervisions(using Frame): Unit < Async =
        supervisions.getAndSet(Absent).map {
            case Present(live) => Kyo.foreachDiscard(live.toSeq)(_.interrupt.unit)
            case _             => ()
        }

    /** Runs one confirmed execution to its outcome and gives the claim back, on a fiber of its own.
      *
      * Every store failure here is answered rather than raised, for the reason [[finish]] answers its own: this runs detached from the
      * poll loop, so a raised failure would reach nobody, and it is a failure to reach the store rather than a verdict on the work. It is
      * charged to health, where a failing store is visible, and the execution is left exactly as claimable as it was.
      *
      * **The execution and renewal fibers belong to the attempt, so they are spawned in a scope of the attempt's own.** Left to the
      * engine's scope they would be two more entries per attempt that nothing removes, and there is no reason for them to live that
      * long: both are finished by the time this returns, one because its result was awaited and the other because it was interrupted.
      * Closing the scope here is also what carries an interrupt down: a supervision the engine stops takes both of them with it.
      */
    private def supervise(
        claimed: FlowStore.Claimed,
        defn: FlowDefinition,
        lease: Duration,
        renewEvery: Duration
    )(using Frame): Unit < Async =
        Scope.run(superviseScoped(claimed, defn, lease, renewEvery))

    private def superviseScoped(
        claimed: FlowStore.Claimed,
        defn: FlowDefinition,
        lease: Duration,
        renewEvery: Duration
    )(using Frame): Unit < (Async & Scope) =
        val state = claimed.state
        val eid   = state.executionId
        val run: Unit < (Async & Scope & Abort[FlowStoreException]) =
            claimedEvent(claimed, ts => Flow.Event.ExecutionClaimed(state.flowId, eid, executorId, ts)).andThen {
                // The execution runs in its own fiber so the renewal can STOP it. The lease is what
                // authorises this executor to work on the execution at all, so a refused renewal means
                // the claim is already someone else's and everything done from that moment is work on
                // an execution this executor does not hold. Noticing is not enough: a step's `timeout`
                // defaults to `Duration.Infinity`, and the next node boundary sits at the far side of
                // the step, so a step that does not return on its own would never reach one.
                Fiber.init(executeOne(claimed, defn)).map { execFiber =>
                    Fiber.init {
                        // A renewal that could not reach the store has not lost the claim; it has
                        // failed to ask. Exiting on the first failure would leave the execution
                        // running with nothing renewing it, so its lease lapses while it works and
                        // another executor takes it over. A failure is charged to health and the
                        // claim is asked for again on the next tick, with the recursion in the
                        // handler's continuation so a loop meant to run for the execution's life does
                        // not nest one more handler per tick.
                        def renew: Unit < Async =
                            Async.sleep(renewEvery).andThen {
                                Abort.run[Throwable](claimed.renewClaim(lease)).map {
                                    case Result.Success(true)  => renew
                                    case Result.Success(false) => execFiber.interrupt.unit
                                    // The renewal fiber's own interrupt, which the engine raises when
                                    // it closes: it stops the loop rather than being counted against
                                    // the store and asked again, exactly as the worker loop treats one.
                                    case Result.Panic(interrupted: Interrupted) => Abort.panic(interrupted)
                                    case failedRenewal =>
                                        val error = failedRenewal match
                                            case Result.Failure(e) => e
                                            case Result.Panic(e)   => e
                                            case _ =>
                                                new IllegalStateException("renewing the claim failed with no error")
                                        liveness.recordFailure(error).andThen(renew)
                                }
                            }
                        renew
                    }.map { renewFiber =>
                        Sync.ensure(renewFiber.interrupt) {
                            // The attempt's outcome is classified rather than discarded. An interrupt
                            // never travels through the handlers inside `executeOne`, so this is the
                            // only place an interrupted attempt is told apart from a panicked one,
                            // and telling them apart is what keeps a shutdown from terminalising
                            // every execution the engine was carrying.
                            Abort.run[Throwable](execFiber.get).map { outcome =>
                                finish(claimed, attemptOf(outcome))
                            }
                        }
                    }
                }
            }
        // Both channels, for the reason the worker loop states about its own: a store is third-party code and is free to
        // panic even though the SPI gives it somewhere typed to put a failure, and a defect in one execution's supervision
        // must not disappear without a trace now that it no longer travels back to the poll.
        Abort.run[Throwable](run).map {
            case Result.Success(_) => ()
            // The engine going away, not a store that could not be reached. It ends this execution's supervision rather
            // than being counted against the store's health, exactly as the worker loop treats one.
            case Result.Panic(interrupted: Interrupted) => Abort.panic(interrupted)
            case refused =>
                val error = refused match
                    case Result.Failure(e) => e
                    case Result.Panic(e)   => e
                    case _                 => new IllegalStateException("supervising a claimed execution failed with no error")
                liveness.recordFailure(error).andThen(
                    Log.error(s"kyo.flow: executor ${executorId.value} could not supervise ${eid.value}", error)
                )
        }
    end superviseScoped

    /** Runs one attempt at an execution and says what happened, without writing anything about it.
      *
      * Classifying rather than concluding is the point. Infrastructure failure and domain verdict must not share one channel: on one
      * channel a store outage is recorded as the workflow having failed, and every site that writes a terminal status is a site free
      * to disagree with the others about what deserves one. [[finish]] is the only thing that writes.
      *
      * The handler is `Abort.run[Throwable]` and not `Abort.recover`, which re-raises panics rather than catching them. That
      * difference is what keeps a deterministic panic on the resume path from leaving the execution at Running, to be reclaimed and
      * panic again on every poll with nothing recorded anywhere.
      */
    private def executeOne(claimed: FlowStore.Claimed, defn: FlowDefinition)(using Frame): Attempt < Async =
        Abort.run[Throwable](attemptOne(claimed, defn)).map(attemptOf)

    /** The attempt itself, run up to the point where its outcome is known.
      *
      * The row it works from is the claim's own snapshot, so a claimed batch costs no reads at all rather than one per row.
      */
    private def attemptOne(
        claimed: FlowStore.Claimed,
        defn: FlowDefinition
    )(using Frame): Attempt < (Async & Abort[FlowStoreException]) =
        val state = claimed.state
        val eid   = state.executionId
        // Nothing for this executor to do or to say: the row was already finished before the attempt began. It holds a claim
        // it learned nothing under, so it neither releases it nor retires a row, and the claim lapses like any other ending
        // with no verdict.
        if state.status.isTerminal then Attempt.NoWork
        else
            claimedEvent(claimed, ts => Flow.Event.ExecutionResumed(state.flowId, eid, executorId, ts)).map {
                case false => Attempt.ClaimLost
                case true =>
                    for
                        _       <- dischargeSleeps(claimed)
                        fields  <- store.getAllFields(eid)
                        history <- store.getHistory(eid, Maybe.empty, 0)
                        record      = rebuildRecord(fields, defn.schema)
                        completed   = deriveCompleted(history)
                        compensated = deriveCompensated(history)
                        retried     = deriveRetries(history)
                        // A `Compensating` lifecycle means a previous attempt was interrupted mid-unwind, and the cause it
                        // recorded is what this one re-enters on. Replaying forward instead would re-run the step that
                        // failed, and a step that succeeds the second time completes an execution whose compensations have
                        // half run.
                        resume = state.status match
                            case Flow.Status.Compensating(cause) => Maybe(cause)
                            case _                               => Maybe.empty
                        interp = new StoreInterpreter(store, claimed, executorId, defn, retried, resume)
                        flowExec = Flow.run(defn.flow, record, completed, compensated, resume)(interp)
                            .map(_.asInstanceOf[Record[Any]])
                        result <- Abort.run[FlowSuspension] {
                            Abort.run[FlowException] {
                                // A runner PRODUCES a scope, because the effects it handles may acquire resources, and the
                                // scope is closed here rather than inherited: a step's file or connection belongs to the
                                // attempt that opened it, and leaving it to the engine's own scope keeps it open until the
                                // process shuts down.
                                defn.runner match
                                    case Present(r) => Scope.run(r.erased(flowExec))
                                    case _          => flowExec
                            }
                        }
                    yield classifyFlowResult(result)
            }
        end if
    end attemptOne

    /** Discharges every sleep the STORE judged due, before the replay reaches the node that recorded it.
      *
      * The satisfied set rides on the claim, so the engine discharges exactly what readiness woke it for. Judging it here against the
      * engine's own clock instead would put the two ends of one decision on two clocks: against a store whose clock runs ahead,
      * readiness returns the execution, the engine finds nothing due, re-parks, and the poll re-claims at full speed until the engine
      * catches up. A row satisfied for some other reason is left alone, and the node it belongs to re-records a wait that keeps its
      * original deadline.
      */
    private def dischargeSleeps(claimed: FlowStore.Claimed)(using Frame): Unit < (Async & Abort[FlowStoreException]) =
        val state = claimed.state
        val due = claimed.satisfied.toSeq.filter { path =>
            state.waits.get(path) match
                case Present(_: Flow.Wake.At) => true
                case _                        => false
        }
        Clock.nowWith { now =>
            Kyo.foreachDiscard(due) { path =>
                claimed.recordProgress(
                    path,
                    Flow.Event.SleepCompleted(state.flowId, state.executionId, path, now)
                )
            }
        }
    end dischargeSleeps

    /** What an attempt's raw outcome was, in the vocabulary [[finish]] reads.
      *
      * Read at two places, and the second is the one that matters: an interrupt never travels through the handlers inside
      * `executeOne`, because interruption short-circuits evaluation at a safepoint rather than being delivered into an
      * in-computation `Abort.run[Throwable]`. It arrives as the attempt FIBER's own outcome, so reading that outcome is the only
      * way an interrupted attempt is told apart from a panicked one.
      */
    private def attemptOf(outcome: Result[Throwable, Attempt]): Attempt =
        outcome match
            case Result.Success(attempt) => attempt
            case Result.Failure(error)   => classify(error)
            case Result.Panic(error)     => classify(error)
            case _                       => Attempt.Panicked(new IllegalStateException("the attempt produced no outcome"))

    /** Which arm an error that ended an attempt belongs to.
      *
      * The two that are not a verdict on the work are worth naming. A [[FlowStoreException]] is a fact about the store, so it is
      * infrastructure and leaves the execution as claimable as it was. An [[Interrupted]] is a designed way for an attempt to end,
      * by the engine closing or by the renewal fiber stopping an executor whose lease lapsed, and it is an ordinary throwable that
      * is no [[FlowException]], so without an arm of its own a shutdown terminalises every execution the engine was carrying.
      *
      * The two unwind arms come first because both are [[FlowException]]s and neither is a domain verdict. A cancellation reached the
      * end of its unwind, so the terminal status is `Cancelled` rather than a `Failed` carrying the cancel exception's own name; a
      * resumed unwind re-raises the verdict its interrupted attempt recorded, which is the only thing that still knows the original
      * failure's message and kind.
      */
    private def classify(error: Throwable): Attempt =
        error match
            case e: FlowResumedUnwindException => Attempt.Unwound(e.cause)
            case _: FlowCancelledException     => Attempt.Unwound(Flow.Cause.Cancellation)
            case e: FlowStoreException         => Attempt.Infra(e)
            case _: Interrupted                => Attempt.Interrupted
            case e: FlowException              => Attempt.DomainFailed(e)
            case e                             => Attempt.Panicked(e)

    /** What the flow's own result says, once its suspension and failure channels have been handled. */
    private def classifyFlowResult(result: Result[FlowSuspension, Result[FlowException, Record[Any]]]): Attempt =
        result match
            case Result.Success(Result.Success(_))                => Attempt.Completed
            case Result.Failure(FlowSuspension.Parked(waitingOn)) => Attempt.Suspended(waitingOn)
            case Result.Failure(FlowSuspension.ClaimLost)         => Attempt.ClaimLost
            case Result.Success(Result.Failure(error))            => classify(error)
            case Result.Success(Result.Panic(error))              => classify(error)
            case Result.Panic(error)                              => classify(error)
            case _ => Attempt.Panicked(new IllegalStateException("the flow produced no outcome"))

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
            case (acc, Flow.Event.StepCompleted(_, _, name, _))   => acc + name
            case (acc, Flow.Event.SleepCompleted(_, _, name, _))  => acc + name
            case (acc, Flow.Event.InputDischarged(_, _, name, _)) => acc + name
            case (acc, Flow.Event.InputSupplied(_, _, name, _))   => acc + name
            case (acc, _)                                         => acc
        }

    /** Each node that failed and has not completed since, with the message it failed on.
      *
      * **The last word wins, which is what the fold is for.** A node that failed on one attempt and succeeded on the next carries
      * both records, in that order, and it is completed; taking the first would report a failure on a node whose work is done. History
      * is in append order (I7), so walking it forward and letting a completion erase a failure gives exactly "failed, with nothing
      * later saying otherwise".
      */
    private def deriveFailed(history: FlowStore.HistoryPage): Map[String, String] =
        history.events.foldLeft(Map.empty[String, String]) {
            case (acc, Flow.Event.StepFailed(_, _, name, error, _, _)) => acc + (name -> error)
            case (acc, Flow.Event.StepCompleted(_, _, name, _))        => acc - name
            case (acc, _)                                              => acc
        }

    /** The nodes whose compensation handler already ran, so a resumed unwind runs only the ones that never landed. */
    private def deriveCompensated(history: FlowStore.HistoryPage): Set[String] =
        history.events.foldLeft(Set.empty[String]) {
            case (acc, Flow.Event.NodeCompensated(_, _, name, _)) => acc + name
            case (acc, _)                                         => acc
        }

    /** Where each node's retry schedule already stood, counted from the retries it durably recorded.
      *
      * Reading the position back is what keeps the policy bounded. An executor that entered at attempt 1 would let a step failing
      * deterministically burn its whole schedule once per resume without ever exhausting it, which turns a bounded retry policy into
      * an unbounded one exactly when the system is already unhealthy.
      */
    private def deriveRetries(history: FlowStore.HistoryPage): Map[String, Int] =
        history.events.foldLeft(Map.empty[String, Int]) {
            case (acc, Flow.Event.StepRetried(_, _, name, _, _, _, _)) => acc + (name -> (acc.getOrElse(name, 0) + 1))
            case (acc, _)                                              => acc
        }

    private def deliveredInputNames(defn: FlowDefinition, fields: Dict[String, FlowStore.FieldData]): Set[String] =
        defn.inputs.filter(im => fields.contains(im.name)).map(_.name).toSet

    /** The one place an attempt's outcome becomes a store transition.
      *
      * A terminal `Failed` written from several sites is several sites disagreeing about what deserves one, which puts a store outage
      * and a business rejection in the same cell and makes the first as irreversible as the second. Every arm's transition is readable
      * here, and the arms that write nothing say so in a line each rather than by an accident of shape somewhere else.
      *
      * **The release is part of the outcome rather than an epilogue after it.** Every arm that reaches a verdict says it through
      * `Claimed.finish`, which writes what happened, settles the wait ledger and makes the claim absent in ONE transition. The arms
      * that reach no verdict (`Infra`, `ClaimLost`, `Interrupted`, `NoWork`) call it not at all and leave the claim to lapse: their
      * ledger describes waits nobody confirmed, and an absent claim is exactly what readiness reads as "these rows are a finished
      * attempt's statement", so releasing there would turn a healable expiry into a permanent wedge.
      *
      * The release EVENT rides the same gate, appended through the claim just before the ending, so an executor whose row was taken
      * cannot write its own release into an execution somebody else is running.
      */
    private def finish(claimed: FlowStore.Claimed, attempt: Attempt)(using Frame): Unit < Async =
        val state  = claimed.state
        val eid    = state.executionId
        val flowId = state.flowId

        def end(outcome: FlowStore.Claimed.Outcome): Unit < (Async & Abort[FlowStoreException]) =
            claimedEvent(claimed, ts => Flow.Event.ExecutionReleased(flowId, eid, executorId, ts)).map {
                case false => ()
                case true =>
                    claimed.finish(outcome).map {
                        case FlowStore.StatusOutcome.Applied   => ()
                        case FlowStore.StatusOutcome.ClaimLost => ()
                        case FlowStore.StatusOutcome.WrongSideOfTerminal =>
                            Abort.panic(new IllegalStateException(
                                s"the attempt at ${eid.value} ended with a status on the wrong side of the lifecycle partition"
                            ))
                    }
            }

        def terminal(status: Flow.Status, event: Instant => Flow.Event): Unit < (Async & Abort[FlowStoreException]) =
            Clock.nowWith(ts => end(FlowStore.Claimed.Outcome.Terminal(status, event(ts))))

        // A failure the flow raised on its typed channel is recorded with its kind, so a persisted failure can be grouped
        // by what it was rather than matched on the message text. A panic has no declared kind: it is whatever escaped,
        // and the class name of an escaped throwable is not a category a caller declared.
        def failed(message: String, kind: Maybe[String]): Unit < (Async & Abort[FlowStoreException]) =
            terminal(Flow.Status.Failed(message, kind), ts => Flow.Event.Failed(flowId, eid, message, ts))

        val transition: Unit < (Async & Abort[FlowStoreException]) =
            attempt match
                case Attempt.Completed           => terminal(Flow.Status.Completed, ts => Flow.Event.Completed(flowId, eid, ts))
                case Attempt.DomainFailed(error) => failed(error.getMessage, Maybe(error.kind))
                case Attempt.Panicked(error)     => failed(error.getMessage, Maybe.empty)
                // The unwind ran, and what it ends as is a total function of the verdict it ran for: a cancellation
                // terminalises `Cancelled`, a failure keeps the message and the kind the forward pass produced. That is what
                // lets a cancel run its compensations at all, since an unwind that began at a cancel request would otherwise
                // have nothing to write but `Failed`.
                case Attempt.Unwound(Flow.Cause.Cancellation) =>
                    terminal(Flow.Status.Cancelled, ts => Flow.Event.Cancelled(flowId, eid, ts))
                case Attempt.Unwound(Flow.Cause.Failure(message, kind)) => failed(message, kind)
                // The parks were recorded by the nodes that made them while the attempt ran, and the ending keeps exactly
                // those: everything else the execution holds a row for is a branch it is no longer waiting on.
                case Attempt.Suspended(waitingOn) => end(FlowStore.Claimed.Outcome.Suspended(waitingOn))
                // Not a verdict on the work: the store is what failed. It is charged to health, and the execution is left
                // exactly as claimable as it was, for the next poll to try again.
                case Attempt.Infra(error) =>
                    liveness.recordFailure(error).andThen(
                        Log.error(
                            s"kyo.flow: executor ${executorId.value} could not reach the store while running ${eid.value}",
                            error
                        )
                    )
                // Nothing at all: not a status, not an event, not a release. The row belongs to somebody else now, and the
                // new owner needs no help from the executor that stopped owning it.
                case Attempt.ClaimLost => ()
                // Also nothing, and not for ClaimLost's reason. There the fence would refuse the write; here the claim may
                // be perfectly valid and nothing would refuse anything. An executor told to stop has reached no verdict,
                // and only saying so keeps a shutdown from terminalising every execution the engine was carrying.
                case Attempt.Interrupted => ()
                // And nothing here either, for a third reason: there was no work to do. Releasing would say the rows are a
                // finished attempt's statement, which this attempt is in no position to make, and it would say it on the
                // strength of having found the row already over.
                case Attempt.NoWork => ()

        // Recording an outcome is a store write like any other, so a store that cannot take it is charged to health and the
        // execution is left where the next poll finds it, rather than killing the poll loop carrying the rest of the batch.
        Abort.run[Throwable](transition).map {
            case Result.Success(_) => ()
            // A store stopped mid-write, not a store that could not be reached. It ends this execution's supervision rather than
            // being counted against the store's health, exactly as the worker and renewal loops treat one.
            case Result.Panic(interrupted: Interrupted) => Abort.panic(interrupted)
            case refused =>
                val error = refused match
                    case Result.Failure(e) => e
                    case Result.Panic(e)   => e
                    case _                 => new IllegalStateException("recording the attempt's outcome failed with no error")
                liveness.recordFailure(error).andThen(
                    Log.error(s"kyo.flow: executor ${executorId.value} could not record the outcome of ${eid.value}", error)
                )
        }
    end finish

end FlowEngine

object FlowEngine:

    /** What an engine serves: every version registered in this process, plus which one a fresh start runs.
      *
      * Keyed by `(workflowId, structuralHash)` because an execution belongs to the version it was STARTED under: a `signal` for an input
      * the current definition renamed must still resolve against the one the execution declares, or the value can never be delivered.
      *
      * Registration is the retention policy and nothing evicts. Within a process the map only grows, an operator who wants a version gone
      * restarts without registering it, and a verb that removed a version an in-flight execution still needed would manufacture exactly the
      * orphan the version gate exists to avoid. What an operator needs instead is the information, and it is on
      * [[FlowEngine.parked]] (which executions this engine does NOT serve) and on each execution's own hash.
      *
      * @param latest
      *   the definition registered LAST for each workflow id, which is what `workflows.start` runs. Refusing an ambiguous start would
      *   punish an operator for registering a second version so their in-flight executions keep running.
      */
    private[kyo] case class Definitions(
        byVersion: Dict[(Flow.Id.Workflow, String), FlowDefinition],
        latest: Dict[Flow.Id.Workflow, FlowDefinition]
    ):
        def of(workflowId: Flow.Id.Workflow, hash: String): Maybe[FlowDefinition] = byVersion.get((workflowId, hash))

        def register(workflowId: Flow.Id.Workflow, defn: FlowDefinition): Definitions =
            Definitions(byVersion.update((workflowId, defn.meta.structuralHash), defn), latest.update(workflowId, defn))

        /** Every `(workflow, version)` pair this engine can interpret, which is what readiness gates on. */
        def served: Set[(Flow.Id.Workflow, String)] =
            byVersion.foldLeft(Set.empty[(Flow.Id.Workflow, String)])((acc, key, _) => acc + key)

        /** The versions of one workflow this engine serves. */
        def servedHashes(workflowId: Flow.Id.Workflow): Set[String] =
            byVersion.foldLeft(Set.empty[String])((acc, key, _) => if key._1 == workflowId then acc + key._2 else acc)

        def workflowIds: Seq[Flow.Id.Workflow] =
            latest.foldLeft(Seq.empty[Flow.Id.Workflow])((acc, id, _) => acc :+ id)
    end Definitions

    private[kyo] object Definitions:
        val empty: Definitions = Definitions(Dict.empty, Dict.empty)

    /** What one attempt at an execution did, said before anything is written about it.
      *
      * It exists so that infrastructure failure and domain verdict never share a channel, where a store outage would be recorded as
      * the workflow having failed, and so that no two sites can write a terminal status while disagreeing about what deserves one.
      * [[FlowEngine.executeOne]] classifies into these arms and one transition function reads them, so what an outcome does to the
      * store is written down once.
      */
    private[kyo] enum Attempt derives CanEqual:

        /** The flow finished. */
        case Completed

        /** It parked, and these are the parks that ended the attempt. */
        case Suspended(waitingOn: Set[String])

        /** A verdict on the work: terminal, and it carries its kind. */
        case DomainFailed(error: FlowException)

        /** Not a declared failure of this flow: terminal, with no kind to record. */
        case Panicked(error: Throwable)

        /** The unwind ran to its end, and what it ends as is a total function of the cause it ran for.
          *
          * `Failure` keeps the message and kind the forward pass produced; `Cancellation` terminalises `Cancelled`. It is separate
          * from [[DomainFailed]] because a cancellation is not a verdict on the work and must not land as a `Failed` carrying the
          * cancel exception's own class name, and because a RESUMED unwind has no exception left to read a kind off: the cause its
          * interrupted attempt recorded is the only thing that still knows.
          */
        case Unwound(cause: Flow.Cause)

        /** There was nothing to run: the row was already terminal when the attempt reached it.
          *
          * It writes nothing and releases nothing, unlike an empty [[Suspended]], which would say the ledger is a finished attempt's
          * statement of what the execution waits for. This attempt learned nothing about the rows and holds a claim it took no work
          * under, so the claim lapses like every other ending with no verdict.
          */
        case NoWork

        /** The store failed. Not terminal, recorded on health, execution left exactly as claimable as it was. */
        case Infra(error: FlowStoreException)

        /** This executor no longer owns the row, so it has nothing to say about it. */
        case ClaimLost

        /** This executor was told to stop while its claim may still be valid. */
        case Interrupted

    end Attempt

    // --- Factory ---

    /** Create an engine with worker fibers that poll the store for ready executions.
      *
      * Each flow must have been built with `Flow.init("name")`, since the engine derives the workflow ID from that name and it is the
      * workflow's durable identity. A flow with no name, or one no engine could execute durably, is refused with a
      * [[FlowDefinitionException]] on the same typed channel a store failure travels.
      */
    def init(
        store: FlowStore,
        flows: Flow[?, ?, ?]*
    )(using Frame): FlowEngine < (Async & Scope & Abort[FlowDefinitionException | FlowStoreException]) =
        initImpl(store, flows = flows)

    def init[S](
        store: FlowStore,
        flows: Flow[?, ?, S]*
    )(runner: [V] => V < S => V < (Async & Scope & Abort[FlowException]))(using
        Frame
    ): FlowEngine < (Async & Scope & Abort[FlowDefinitionException | FlowStoreException]) =
        initImpl(store, flows = flows, runner = Maybe(FlowRunner[S](runner)))

    /** Create an engine with the tuning spelled out.
      *
      * A tuning the engine could not work under is refused with a [[FlowInvalidConfigException]] naming every setting at fault, which
      * is why this overload carries a channel the untuned ones above do not: they supply the tuning themselves and cannot be handed a
      * bad one. Returning an engine that answers its health check while claiming nothing, or one whose every renewal is refused
      * because the claim expires first, is what the checks exist to prevent.
      */
    def init(
        store: FlowStore,
        workerCount: Int = 2,
        lease: Duration = 30.seconds,
        renewEvery: Duration = 10.seconds,
        batchSize: Int = 4,
        pollTimeout: Duration = 30.seconds,
        flows: Seq[Flow[?, ?, ?]] = Seq.empty
    )(using Frame): FlowEngine < (Async & Scope & Abort[FlowInvalidConfigException | FlowDefinitionException | FlowStoreException]) =
        init(store, Config(workerCount, lease, renewEvery, batchSize, pollTimeout), flows*)

    /** Create a tuned engine.
      *
      * The tuning is one [[FlowEngine.Config]] value rather than a parameter list, so it composes with the runner overload below. Every
      * workflow whose steps touch a resource has a non-trivial effect row and therefore needs a runner, and `lease` is the knob that sets
      * how long crash recovery waits, so the two have to be available together.
      *
      * A tuning the engine could not work under is refused with a [[FlowInvalidConfigException]] listing every setting at fault.
      */
    def init(
        store: FlowStore,
        config: Config,
        flows: Flow[?, ?, ?]*
    )(using Frame): FlowEngine < (Async & Scope & Abort[FlowInvalidConfigException | FlowDefinitionException | FlowStoreException]) =
        accept(config).andThen(initImpl(store, config, flows, Maybe.empty))

    /** Create a tuned engine whose step bodies use custom effects. */
    def init[S](
        store: FlowStore,
        config: Config,
        flows: Flow[?, ?, S]*
    )(runner: [V] => V < S => V < (Async & Scope & Abort[FlowException]))(using
        Frame
    ): FlowEngine < (Async & Scope & Abort[FlowInvalidConfigException | FlowDefinitionException | FlowStoreException]) =
        accept(config).andThen(initImpl(store, config, flows, Maybe(FlowRunner[S](runner))))

    /** Refuses a tuning the engine could not work under, before anything is built.
      *
      * It guards the factories that take a tuning FROM A CALLER rather than [[initImpl]], which every factory funnels through,
      * because the untuned ones supply a tuning of their own and cannot be handed a bad one. A channel carrying a failure its caller
      * has no way to produce is a caller made to handle something that never happens, which is what the module's precise error unions
      * exist to avoid.
      */
    private def accept(config: Config)(using Frame): Unit < Abort[FlowInvalidConfigException] =
        Abort.when(config.problems.nonEmpty)(FlowInvalidConfigException(config.problems))

    private[kyo] def initImpl(
        store: FlowStore,
        config: Config = Config(),
        flows: Seq[Flow[?, ?, ?]] = Seq.empty,
        runner: Maybe[FlowRunner] = Maybe.empty
    )(using Frame): FlowEngine < (Async & Scope & Abort[FlowDefinitionException | FlowStoreException]) =
        import config.*
        for
            defs         <- AtomicRef.init(Definitions.empty)
            eid          <- Flow.Id.Executor.random
            liveness     <- Liveness.init(workerCount)
            supervisions <- AtomicRef.init(Maybe(Set.empty[Fiber[Unit, Any]]))
            engine = new FlowEngine(store, defs, eid, liveness, supervisions)
            // A flow with no name is refused by registration rather than here, on the typed channel every other refusal
            // of a definition travels. The name this reads and the name registration checks come from the same fold, so
            // the two cannot disagree about which flows have one.
            _ <- Kyo.foreachDiscard(flows) { flow =>
                engine.registerImpl(Flow.Id.Workflow(FlowLint.flowName(flow).getOrElse("")), flow, runner)
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
            // A scope drains its finalizers LAST REGISTERED FIRST, so registering this after the workers means it runs BEFORE they
            // are interrupted: the attempts are stopped while the poll loops are still live and free to claim another row.
            //
            // That order is safe, and the registry's `Absent` is what makes it so rather than a narrow race being covered. Once
            // `stopSupervisions` has taken the set, every later supervision finds the registry closed and interrupts itself on the
            // spot, so a worker that claims a row in the window between this finalizer and its own leaves nothing running behind it.
            // Registering the other way round would not remove the window either, since a worker is only interrupted at its next
            // safepoint and can spawn on the way there.
            _ <- Scope.ensure(engine.stopSupervisions)
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
    ) derives CanEqual:

        /** Every setting here the engine could not work under, empty for a tuning it accepts.
          *
          * What [[FlowEngine.init]] refuses on, reported all at once so a caller does not pay a process start per problem. A duration is
          * asked to be POSITIVE rather than merely non-negative because [[kyo.Duration]] has no negative values: `-1.seconds` is
          * `Duration.Zero`, so zero is where a caller asking for a negative one arrives.
          */
        private[kyo] def problems: Seq[FlowConfigProblem] =
            def refuse(bad: Boolean, setting: String, value: String, reason: String): Seq[FlowConfigProblem] =
                if bad then Seq(FlowConfigProblem(setting, value, reason)) else Seq.empty
            refuse(
                workerCount < 1,
                "workerCount",
                workerCount.toString,
                "no fiber would poll the store, so no execution would ever be claimed"
            ) ++ refuse(
                batchSize < 1,
                "batchSize",
                batchSize.toString,
                "a poll would ask for no executions, so it would find none however many are ready"
            ) ++ refuse(
                lease <= Duration.Zero,
                "lease",
                lease.show,
                "a claim would be expired the moment it was granted, so the row is reclaimable while it is being worked on"
            ) ++ refuse(
                renewEvery <= Duration.Zero,
                "renewEvery",
                renewEvery.show,
                "the renewal loop would not pause, so it would re-ask the store as fast as the scheduler allows"
            ) ++ refuse(
                pollTimeout <= Duration.Zero,
                "pollTimeout",
                pollTimeout.show,
                "a poll would not wait for work, so an idle worker would spin"
            ) ++ refuse(
                lease > Duration.Zero && renewEvery > Duration.Zero && renewEvery >= lease,
                "renewEvery",
                s"${renewEvery.show} against a lease of ${lease.show}",
                "the claim expires before its first renewal, and a refused renewal interrupts the execution, so every step " +
                    "longer than the lease is interrupted, released, reclaimed and re-run forever"
            )
        end problems
    end Config

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
        ): Unit < (Async & Abort[
            FlowExecutionStateException | FlowWorkflowNotRegisteredException | FlowSignalException | FlowStoreException
        ]) =
            engine.executions.signal[V](executionId, name, value)

        /** The execution's persisted lifecycle.
          *
          * `Running` spans working AND waiting: an execution waiting for an input or serving out a sleep is running, and what it is
          * waiting for is plural, so no single token could answer it without hiding one of two simultaneous waits. Use [[describe]] for
          * that question; it answers with the wait rows and the outstanding cancel request beside the lifecycle.
          */
        def status(using Frame): Flow.Status < (Async & Abort[FlowExecutionNotFoundException | FlowStoreException]) =
            engine.executions.describe(executionId).map(_.status)

        def describe(using Frame): ExecutionDetail < (Async & Abort[FlowExecutionNotFoundException | FlowStoreException]) =
            engine.executions.describe(executionId)

        /** Ask for this execution to be cancelled, and say what the ask did.
          *
          * The three answers reach the holder of a handle for the reason they reach any other caller: the cancel is a request, so this
          * value is the only thing about it that is observable on return. See [[FlowEngine.executions.cancel]].
          */
        def cancel(using Frame): FlowStore.CancelOutcome < (Async & Abort[FlowStoreException]) =
            engine.executions.cancel(executionId)

        /** This execution's event history, in append order. A negative `limit` reads as unbounded, per
          * [[FlowEngine.executions.history]].
          */
        def history(limit: Int = 50, offset: Int = 0)(using Frame): FlowStore.HistoryPage < (Async & Abort[FlowStoreException]) =
            engine.executions.history(executionId, limit, offset)
    end Handle

    // --- Types ---

    /** One input's delivery state on a running execution: its name, the type it expects, and whether a value has arrived.
      *
      * A fact about an EXECUTION, which is what separates it from [[FlowEngine.WorkflowInfo.InputInfo]], the input a definition
      * declares. Naming both `InputInfo` would leave the two told apart only by their enclosing scope, so a reader with one in hand
      * could not tell which question it answers.
      */
    case class InputStatus(name: String, tag: String, delivered: Boolean) derives CanEqual

    /** Full detail of an execution: store state, input delivery status, and step-by-step progress. Returned by `executions.describe` and
      * `Handle.describe`.
      */
    case class ExecutionDetail(
        state: FlowStore.ExecutionState,
        inputs: Seq[InputStatus],
        progress: FlowEngine.Progress
    ):
        def executionId: Flow.Id.Execution = state.executionId
        def flowId: Flow.Id.Workflow       = state.flowId

        /** The persisted lifecycle. See [[FlowEngine.Handle.status]] for why waiting is not one of its cases. */
        def status: Flow.Status = state.status

        /** What each waiting node is waiting for, keyed by the node's composition path. Plural, and empty for an execution that is
          * working rather than waiting.
          */
        def waits: Dict[String, Flow.Wake] = state.waits

        /** Whether somebody has asked for this execution to be cancelled and the unwind has not finished yet.
          *
          * Cancellation is a request rather than an act, so a caller who cancels and then looks sees it here while the compensations run.
          */
        def cancelRequested: Boolean = state.cancelRequested
    end ExecutionDetail

    /** One page of a search, and how many executions the search matched in all.
      *
      * @param items
      *   the executions on this page, most recently created first
      * @param total
      *   how many matched, which is the size of the set being paged through and not the length of `items`. A caller sizing a page
      *   control, or deciding whether to ask for more, is reading this number as the match count.
      */
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
                // Each of these persists a value, so each reports the type it persists, exactly as an input and an output do. That
                // takes evidence on the visitor's arm: without it the only reportable tag is `Tag[Any]`, which tells a caller
                // reading the workflow's description nothing about what a dispatch, a loop or a foreach actually stores.
                override def onDispatch[V](name: String, branches: Seq[Flow.BranchInfo], frame: Frame, meta: Flow.Meta)(using
                    Tag[V],
                    Schema[V]
                ) =
                    Seq(NodeInfo(name, "dispatch", Tag[V].erased, frame.snippetShort, meta))
                override def onLoop[V, State](name: String, frame: Frame, meta: Flow.Meta)(using
                    Tag[V],
                    Schema[V],
                    Tag[State],
                    Schema[State]
                ) =
                    Seq(NodeInfo(name, "loop", Tag[V].erased, frame.snippetShort, meta))
                override def onForEach[V](name: String, concurrency: Int, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) =
                    Seq(NodeInfo(name, "foreach", Tag[V].erased, frame.snippetShort, meta))
                override def onSubflow(name: String, childFlow: Flow[?, ?, ?], child: Seq[NodeInfo], frame: Frame, meta: Flow.Meta) =
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
      * WaitingForInput, Compensated, Sleeping, Failed). Used for monitoring dashboards and diagram overlays.
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

            /** This node ran, and its compensation handler has since run too.
              *
              * What an operator watching an unwind needs: which handlers have already landed, and therefore where the unwind has
              * reached. It is durable rather than inferred, coming from the per-handler completion record the unwind writes, so a
              * resumed unwind reports the same thing the interrupted one did.
              */
            case Compensated
            case Sleeping(until: Instant)
            case Failed(error: String)

            /** The one-line rendering a monitoring surface shows, in the same shape [[Flow.Status.show]] uses. */
            def show: String = this match
                case Completed       => "completed"
                case Running         => "running"
                case Pending         => "pending"
                case WaitingForInput => "waiting"
                case Compensated     => "compensated"
                case Sleeping(until) => s"sleeping:$until"
                case Failed(error)   => s"failed:$error"
        end NodeStatus

        /** The per-node view: what is done, what has been compensated, what is active, and what every waiting node is waiting for.
          *
          * The waits come as rows rather than as one token on the status, which is what lets this mark EVERY waiting node. A `race` of an
          * input against a sleep has two waiting nodes at once, and a single cell had room to say so about one of them.
          *
          * **Every arm here reports something the execution durably recorded**, and nothing is painted on that it did not. Marking an
          * unwinding execution's active node as failed fabricates a node-level fact from an execution-level one: the unwind is a fact
          * about the flow, which the lifecycle already carries, and the node-level fact worth having is which handlers have run, which
          * `compensated` supplies. A cancel request gets no node-level arm for the same reason: it is a fact about the execution, on
          * its row, and `describe` reports it there.
          *
          * **The one node that records nothing is derived from the ones around it rather than read.** A subflow writes nothing under
          * its own name, because its children write under their qualified paths and the arm that runs one calls no verb for itself, so
          * a rule that reads the recorded set can only ever call it unfinished: it takes the active marker from the node that is
          * running and then sits at `pending` for good, which is `completedCount` never reaching `totalCount` on any workflow with a
          * subflow in it. The derivation still rests on recorded facts, one level out: what the child's own nodes recorded. A child's
          * INPUT is among them, because entering an instance records each of the child's inputs under its own path before the child's
          * first node runs, so the input is read from its record like any other node.
          *
          * **A failed node comes from `failed`, never from where it sits in the walk.** Painting the first node that is neither
          * completed nor waiting is right for a straight line and wrong the moment a flow is not one: a `zip` branch that failed
          * beside a sibling that completed, or a failure after a node that never started, both put the paint on the wrong node. Such a
          * rule can also say nothing until the execution terminalises, so the whole unwind goes unreported without the one fact an
          * operator watching it needs. An execution whose history predates the record is painted nowhere rather than somewhere: no
          * node claims a failure that nothing recorded.
          */
        private[kyo] def build(
            flow: Flow[?, ?, ?],
            completedSteps: Set[String],
            currentStatus: Flow.Status,
            waits: Dict[String, Flow.Wake],
            compensated: Set[String] = Set.empty,
            failed: Map[String, String] = Map.empty
        ): Progress =
            import Progress.{NodeProgress, NodeType, NodeStatus}
            // A node is done when the node's OWN completion was recorded, never when a part of it was. Counting any recorded
            // iteration draws a loop on iteration 1 of 50 as finished and gives `Running` to the node after it, which has not
            // started; and it cannot help a fan-out at all, which records one event under its own name.
            def isCompleted(name: String): Boolean =
                completedSteps.contains(name)
            val rawNodes = FlowFold(flow)(new FlowVisitor[Chunk[NodeProgress]]:
                def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) =
                    Chunk(NodeProgress(name, NodeType.Input, NodeStatus.Pending, frame.snippetShort))
                def onOutput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) =
                    Chunk(NodeProgress(name, NodeType.Output, NodeStatus.Pending, frame.snippetShort))
                def onStep(name: String, frame: Frame, meta: Flow.Meta) =
                    Chunk(NodeProgress(name, NodeType.Step, NodeStatus.Pending, frame.snippetShort))
                def onSleep(name: String, duration: Duration, frame: Frame, meta: Flow.Meta) =
                    Chunk(NodeProgress(name, NodeType.Sleep, NodeStatus.Pending, frame.snippetShort))
                def onDispatch[V](name: String, branches: Seq[Flow.BranchInfo], frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) =
                    Chunk(NodeProgress(name, NodeType.Dispatch, NodeStatus.Pending, frame.snippetShort))
                def onLoop[V, State](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V], Tag[State], Schema[State]) =
                    Chunk(NodeProgress(name, NodeType.Loop, NodeStatus.Pending, frame.snippetShort))
                def onForEach[V](name: String, concurrency: Int, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]) =
                    Chunk(NodeProgress(name, NodeType.ForEach, NodeStatus.Pending, frame.snippetShort))
                def onRace(left: Chunk[NodeProgress], right: Chunk[NodeProgress], frame: Frame) = left ++ right
                // The child's nodes appear under the instance's path, because that is what the store knows them by and because a
                // subflow embedded twice would otherwise contribute two entries a renderer cannot tell apart.
                def onSubflow(name: String, childFlow: Flow[?, ?, ?], child: Chunk[NodeProgress], frame: Frame, meta: Flow.Meta) =
                    Chunk(NodeProgress(name, NodeType.Subflow, NodeStatus.Pending, frame.snippetShort)) ++
                        child.map(n => n.copy(name = NodePath.qualify(name, n.name)))
                def onAndThen(first: Chunk[NodeProgress], second: Chunk[NodeProgress], frame: Frame) = first ++ second
                def onZip(left: Chunk[NodeProgress], right: Chunk[NodeProgress], frame: Frame)       = left ++ right
                def onGather(flows: Seq[Chunk[NodeProgress]], frame: Frame) = flows.foldLeft(Chunk.empty[NodeProgress])(_ ++ _)
                def onInit(name: String, frame: Frame, meta: Flow.Meta)     = Chunk.empty)
            // A failure is recorded under the path that failed, and a loop's iteration (`sum#3`) and a fan-out's item (`charges~3`)
            // are paths no node carries: the node is their parent. Resolving against the nodes this flow actually has, rather than
            // by stripping a suffix, is what keeps a subflow's child apart from a fan-out's item, since `review~step` and
            // `charges~3` are the same shape and only one of them is a node. It is the completion side's asymmetry the other way
            // up: one iteration completing does not complete the loop, and one iteration failing does fail it.
            val nodeNames = rawNodes.foldLeft(Set.empty[String])((acc, node) => acc + node.name)
            @scala.annotation.tailrec
            def owner(path: String): Maybe[String] =
                if nodeNames.contains(path) then Maybe(path)
                else
                    val cut = path.lastIndexWhere(c => c == '#' || c == NodePath.Separator)
                    if cut <= 0 then Maybe.empty else owner(path.substring(0, cut))
            // Sorted so that two items of one fan-out failing paint their parent with the same message on every platform, rather
            // than with whichever the map happened to yield first.
            val failedByNode = failed.toSeq.sortBy(_._1).foldLeft(Map.empty[String, String]) { case (acc, (path, message)) =>
                owner(path) match
                    case Present(node) => acc + (node -> message)
                    case _             => acc
            }
            // One node kind records nothing of its own, so its status is derived below rather than read here.
            //
            // A subflow is the name its children hang under: the arm that runs one calls no interpreter verb for the instance itself
            // and writes nothing under that name, by design, since the child's nodes write under `instance~name`. It could never be
            // completed by a rule that reads the recorded set.
            //
            // Leaving it in this walk costs the surface twice over. It takes the active marker whenever it comes first, so an operator
            // watching a running execution sees a subflow `running` and the node actually executing `pending`; and once the execution
            // finishes it reads `pending` forever, so `completedCount` never reaches `totalCount` on any workflow with a subflow in
            // it. It takes no marker here and holds its place until the derivation fills it in.
            //
            // A child's INPUT is not derived: a mapper's output is persisted, under the input's own path and before the child's first
            // node runs, so the node is read from its record like any other and an unrecorded one is genuinely the point the
            // execution has reached.
            def isDerived(node: NodeProgress): Boolean =
                node.nodeType == NodeType.Subflow
            @scala.annotation.tailrec
            def assignStatuses(idx: Int, foundActive: Boolean, acc: Chunk[NodeProgress]): Chunk[NodeProgress] =
                if idx >= rawNodes.length then acc
                else
                    val node = rawNodes(idx)
                    if isDerived(node) then assignStatuses(idx + 1, foundActive, acc :+ node)
                    // Asked before completion, because a compensated node is a completed one whose handler has since run, and the
                    // handler is the later fact. Neither counts as the active node the walk is looking for.
                    else if failedByNode.contains(node.name) then
                        assignStatuses(idx + 1, foundActive, acc :+ node.copy(status = NodeStatus.Failed(failedByNode(node.name))))
                    else if compensated.contains(node.name) then
                        assignStatuses(idx + 1, foundActive, acc :+ node.copy(status = NodeStatus.Compensated))
                    else if isCompleted(node.name) then
                        assignStatuses(idx + 1, foundActive, acc :+ node.copy(status = NodeStatus.Completed))
                    else
                        waits.get(node.name) match
                            case Present(Flow.Wake.OnField(_)) =>
                                assignStatuses(idx + 1, true, acc :+ node.copy(status = NodeStatus.WaitingForInput))
                            case Present(Flow.Wake.At(until)) =>
                                assignStatuses(idx + 1, true, acc :+ node.copy(status = NodeStatus.Sleeping(until)))
                            case Absent =>
                                currentStatus match
                                    case Flow.Status.Running if !foundActive =>
                                        assignStatuses(idx + 1, true, acc :+ node.copy(status = NodeStatus.Running))
                                    case _ =>
                                        assignStatuses(idx + 1, foundActive, acc :+ node.copy(status = NodeStatus.Pending))
                    end if
            end assignStatuses
            val walked = assignStatuses(0, false, Chunk.empty)
            def under(nodes: Chunk[NodeProgress], instance: String): Chunk[NodeProgress] =
                val prefix = s"$instance${NodePath.Separator}"
                nodes.filter(_.name.startsWith(prefix))
            // A recorded failure keeps its place ahead of the derivation, here as everywhere else: it is a fact the execution wrote,
            // and only the resolver decides which node owns it.
            def recordedFailure(node: NodeProgress): Maybe[NodeProgress] =
                if failedByNode.contains(node.name) then Maybe(node.copy(status = NodeStatus.Failed(failedByNode(node.name))))
                else Maybe.empty
            // The nodes a subflow contributed, which are the ones qualified under its path. Nested subflows are skipped rather than
            // ordered around: every node that does real work is a leaf of this tree, so an instance reads the same answer whether it
            // is asked before or after the one beneath it.
            //
            // A child whose nodes are ALL inputs still contributes, because entering an instance records the child's inputs. Deriving
            // such an instance from the lifecycle instead, on the grounds that nothing under it could be recorded, counts a
            // `Completed` execution as having entered every instance it declares, which gets a race's losing arm wrong by calling an
            // instance the loser never reached `Completed`. Reading the record instead, an inputs-only child reports exactly what it
            // recorded and an un-entered one reports `Pending` because nothing was written under it.
            def contributed(instance: String): Chunk[NodeStatus] =
                under(walked, instance).collect {
                    case child if child.nodeType != NodeType.Subflow => child.status
                }
            // What a subflow is, said in its children's terms: done when they are all done, undone when their handlers have since
            // run, and where the execution is while any of them is running or waiting. A subflow with nothing under it has nothing to
            // report, which is the empty case.
            //
            // There is deliberately no failed arm: a child that failed leaves its instance Pending. Adding one would put the same
            // message on two nodes, and a reader asking which node failed would get two answers, which is exactly what the resolver's
            // exact-match-first rule above exists to prevent by giving a recorded failure one owner and one only.
            def derived(children: Chunk[NodeStatus]): NodeStatus =
                if children.isEmpty then NodeStatus.Pending
                else if children.forall(_ == NodeStatus.Completed) then NodeStatus.Completed
                else if children.forall(s => s == NodeStatus.Completed || s == NodeStatus.Compensated) then NodeStatus.Compensated
                else if children.exists {
                        case NodeStatus.Running | NodeStatus.WaitingForInput | NodeStatus.Sleeping(_) => true
                        case _                                                                        => false
                    }
                then NodeStatus.Running
                else NodeStatus.Pending
            Progress(
                walked.map { node =>
                    if node.nodeType != NodeType.Subflow then node
                    else recordedFailure(node).getOrElse(node.copy(status = derived(contributed(node.name))))
                }.toSeq
            )
        end build
    end Progress

end FlowEngine
