package kyo

import Flow.BranchInfo
import Flow.Meta
import Flow.internal.*
import kyo.internal.*
import kyo.kernel.Isolate
import scala.compiletime.error

/** A durable workflow definition.
  *
  * A Flow is a plan, not an execution. You describe what should happen — inputs to wait for, values to compute, side effects to perform,
  * branches to take — and the engine handles persistence, crash recovery, and coordination.
  *
  * The three type parameters track workflow structure at compile time:
  *   - `In` accumulates required inputs. Each `.input[V]("name")` refines `In` via `&` intersection, so the engine knows which signals the
  *     execution needs before it can proceed.
  *   - `Out` accumulates produced values the same way. Each `.output("name")(fn)` adds its field, making it available to downstream steps
  *     via `ctx.name`.
  *   - `S` collects effect types from step bodies (e.g., `Async` if a step makes HTTP calls).
  *
  * Start with `Flow.init("name")`, chain steps, then run it:
  *   - `Flow.runServer(flow)` — HTTP server with in-memory store (development)
  *   - `Flow.runServer(store, flow)` — HTTP server with a durable store (production)
  *   - `Flow.runHandlers(store, flow)` — HTTP handlers to compose with your own server
  *   - `FlowEngine.init(store, flow)` — programmatic engine without HTTP
  *   - `Flow.runLocal(flow, inputs)` — in-memory, blocking, for tests
  *
  * Every step is persisted before the next begins. If the process crashes, another executor resumes from the last checkpoint. Steps that
  * perform side effects (HTTP calls, database writes) must be idempotent — they may re-execute on recovery.
  *
  * Error handling uses Kyo's `Abort.recover` inside step bodies. Compensation handlers (`.outputCompensated`) fire in reverse order when a
  * later step fails.
  *
  * **Status transitions:**
  * {{{
  *   Running ──→ Completed
  *   Running ──→ Failed (compensations run first if registered)
  *   Running ──→ WaitingForInput ──→ Running (on signal)
  *   Running ──→ Sleeping ──→ Running (on expiry)
  *   Any non-terminal ──→ Cancelled
  * }}}
  *
  * @tparam In
  *   Intersection of required input types (accumulated via `.input`)
  * @tparam Out
  *   Intersection of produced output types (accumulated via `.output`, `.dispatch`, etc.)
  * @tparam S
  *   Union of effect types from step computations
  *
  * @see
  *   [[kyo.FlowEngine]] Programmatic engine for running flows
  * @see
  *   [[kyo.FlowStore]] Persistence layer (use `FlowStore.initMemory` for development)
  */
sealed abstract class Flow[In, Out, S] derives CanEqual:

    // --- Node builders ---

    /** Declare a named input that the execution must receive via `signal` before proceeding.
      *
      * The flow parks at this node until a value is delivered. On replay, the stored value is read and the node is skipped.
      * Field-producing: persists the value under `name` in the store.
      */
    def input[V](using
        Frame
    )[N <: String & Singleton](
        name: N,
        description: String = "",
        tags: Seq[String] = Seq.empty
    )(using Tag[V], Schema[V]): Flow[In & (N ~ V), Out & (N ~ V), S] =
        AndThen(this, Input[N, V](name, Meta(description, tags)))

    /** Compute a named value from the current context and persist it to the store.
      *
      * The value becomes available to downstream steps via `ctx.name`. On replay, the stored value is read and the computation is skipped.
      * Side effects in `fn` must be idempotent — if the executor crashes after computing but before recording, the step re-executes.
      * Field-producing: persists the value under `name` in the store.
      */
    def output[N <: String & Singleton, V, S2](
        name: N,
        description: String = "",
        timeout: Duration = Duration.Infinity,
        retry: Maybe[Schedule] = Maybe.empty,
        tags: Seq[String] = Seq.empty
    )(fn: Record[Out] => V < S2)(using Frame, Tag[V], Schema[V]): Flow[In, Out & (N ~ V), S & S2] =
        AndThen(this, Output[Out, Out & (N ~ V), N, V, S2](name, fn, Meta(description, tags, timeout, retry), Maybe.empty))
    end output

    /** Like `output`, but registers a compensation handler that runs (in reverse order) if a later step fails.
      *
      * Compensations fire only on `Throwable` failures, not on suspension (sleep, waiting for input). Handlers must be idempotent. Use
      * `Abort.recover` inside `fn` for error recovery.
      */
    def outputCompensated[N <: String & Singleton, V, S2](
        name: N,
        description: String = "",
        timeout: Duration = Duration.Infinity,
        retry: Maybe[Schedule] = Maybe.empty,
        tags: Seq[String] = Seq.empty
    )(fn: Record[Out] => V < S2)(
        compensate: Record[Out & (N ~ V)] => Unit < (Async & Abort[FlowException])
    )(using Frame, Tag[V], Schema[V]): Flow[In, Out & (N ~ V), S & S2] =
        AndThen(
            this,
            Output[Out, Out & (N ~ V), N, V, S2](name, fn, Meta(description, tags, timeout, retry), Maybe(compensate))
        )

    /** Execute a side-effecting computation that doesn't produce a named value.
      *
      * Use for HTTP calls, database writes, logging, notifications, etc. The step is skipped on replay when its `StepCompleted` event
      * exists in the history. Side effects must be idempotent. Event-tracked: skipped when `StepCompleted` event exists (no stored field).
      */
    def step[S2](
        name: String,
        description: String = "",
        timeout: Duration = Duration.Infinity,
        retry: Maybe[Schedule] = Maybe.empty,
        tags: Seq[String] = Seq.empty
    )(fn: Record[Out] => Unit < S2)(using Frame): Flow[In, Out, S & S2] =
        AndThen(this, Step[Out, S2](name, fn, Meta(description, tags, timeout, retry), Maybe.empty))
    end step

    /** Like `step`, but registers a compensation handler. */
    def stepCompensated[S2](
        name: String,
        description: String = "",
        timeout: Duration = Duration.Infinity,
        retry: Maybe[Schedule] = Maybe.empty,
        tags: Seq[String] = Seq.empty
    )(fn: Record[Out] => Unit < S2)(
        compensate: Record[Out] => Unit < (Async & Abort[FlowException])
    )(using Frame): Flow[In, Out, S & S2] =
        AndThen(
            this,
            Step(name, fn, Meta(description, tags, timeout, retry), Maybe(compensate))
        )

    /** Pause the execution for the given duration.
      *
      * The flow parks durably — if the executor restarts, the sleep resumes from where it left off. On replay, the sleep is skipped when
      * its `SleepCompleted` event exists. Event-tracked: no stored field.
      */
    def sleep(
        name: String,
        duration: Duration,
        description: String = "",
        timeout: Duration = Duration.Infinity,
        retry: Maybe[Schedule] = Maybe.empty,
        tags: Seq[String] = Seq.empty
    )(using Frame): Flow[In, Out, S] =
        AndThen(this, Sleep(name, duration, Meta(description, tags, timeout, retry)))

    /** Start building a conditional dispatch (branching). Chain `.when(condition)(body)` calls and end with `.otherwise(default)`. */
    def dispatch[V](using Tag[V]): Flow.DispatchStarter[In, Out, S, V] =
        Flow.DispatchStarter[In, Out, S, V](this, Tag[V])

    /** Loop without state: execute `body` repeatedly. The body returns `Loop.continue` to iterate or `Loop.done(value)` to finish. The
      * final value is stored as the named output.
      */
    def loop[N <: String & Singleton, V: Tag: Schema, S2](
        name: N,
        description: String = "",
        timeout: Duration = Duration.Infinity,
        retry: Maybe[Schedule] = Maybe.empty,
        tags: Seq[String] = Seq.empty
    )(
        body: Record[Out] => Loop.Outcome[Unit, V] < S2
    )(using Frame, Tag[Unit], Schema[Unit]): Flow[In, Out & (N ~ V), S & S2] =
        AndThen(
            this,
            LoopNode[Out, N, Unit, V, S2](
                name,
                (_, ctx) => body(ctx),
                (),
                Maybe.empty,
                Meta(description, tags, timeout, retry)
            )
        )

    /** Loop with 1 state value: execute `body` repeatedly starting from `init`. The body returns `Loop.continue(newState)` to iterate or
      * `Loop.done(value)` to finish. The final value is stored as the named output.
      */
    def loop[N <: String & Singleton, A: Tag: Schema, V: Tag: Schema, S2](
        name: N,
        init: A
    )(
        body: (A, Record[Out]) => Loop.Outcome[A, V] < S2
    )(using Frame): Flow[In, Out & (N ~ V), S & S2] =
        AndThen(
            this,
            LoopNode[Out, N, A, V, S2](name, body, init, Maybe.empty, Meta())
        )

    /** Loop with 2 state values: execute `body` repeatedly starting from `init1` and `init2`. The body returns `Loop.continue(newA, newB)`
      * to iterate or `Loop.done(value)` to finish. The final value is stored as the named output.
      */
    def loop[N <: String & Singleton, A: Tag: Schema, B: Tag: Schema, V: Tag: Schema, S2](
        name: N,
        init1: A,
        init2: B
    )(
        body: (A, B, Record[Out]) => Loop.Outcome2[A, B, V] < S2
    )(using Frame, Tag[(A, B)], Schema[(A, B)]): Flow[In, Out & (N ~ V), S & S2] =
        AndThen(
            this,
            LoopNode[Out, N, (A, B), V, S2](
                name,
                (state, ctx) => body(state._1, state._2, ctx),
                (init1, init2),
                Maybe.empty,
                Meta()
            )
        )

    /** Process each element of a collection and store all results as a `Chunk[V]` output. */
    def foreach[N <: String & Singleton, E, V, S2](
        name: N,
        description: String = "",
        timeout: Duration = Duration.Infinity,
        retry: Maybe[Schedule] = Maybe.empty,
        tags: Seq[String] = Seq.empty,
        concurrency: Int = Int.MaxValue
    )(
        collection: Record[Out] => Seq[E] < S2
    )(
        body: E => V < S2
    )(using Frame, Tag[V], Schema[V]): Flow[In, Out & (N ~ Chunk[V]), S & S2] =
        AndThen(this, ForEach(name, concurrency, collection, body, Meta(description, tags, timeout, retry)))

    /** Execute a child flow as a sub-workflow. The `inputMapper` transforms the current context into the child's input record. The child's
      * output record is stored as a named field.
      */
    def subflow[N <: String & Singleton, In2, Out2, S2](
        name: N,
        childFlow: Flow[In2, Out2, ?],
        description: String = ""
    )(
        inputMapper: Record[Out] => Record[In2] < S2
    )(using Frame): Flow[In, Out & (N ~ Record[Out2]), S & S2] =
        AndThen(this, Subflow[In, Out, N, In2, Out2, S2](name, childFlow, inputMapper, Meta(description = description)))

    // --- Scheduled loop (loopOn) ---

    /** Loop on a schedule without state. Between iterations, the flow durably sleeps for the delay returned by `Schedule.next`. The body
      * returns `Loop.continue` to iterate or `Loop.done(value)` to finish.
      */
    def loopOn[N <: String & Singleton, V: Tag: Schema, S2](
        name: N,
        schedule: Schedule,
        description: String = "",
        timeout: Duration = Duration.Infinity,
        retry: Maybe[Schedule] = Maybe.empty,
        tags: Seq[String] = Seq.empty
    )(
        body: Record[Out] => Loop.Outcome[Unit, V] < S2
    )(using Frame, Tag[Unit], Schema[Unit]): Flow[In, Out & (N ~ V), S & S2] =
        AndThen(
            this,
            LoopNode[Out, N, Unit, V, S2](name, (_, ctx) => body(ctx), (), Maybe(schedule), Meta(description, tags, timeout, retry))
        )

    /** Loop on a schedule with 1 state value. Between iterations, the flow durably sleeps for the delay from `Schedule.next`. */
    def loopOn[N <: String & Singleton, A: Tag: Schema, V: Tag: Schema, S2](
        name: N,
        schedule: Schedule,
        init: A
    )(
        body: (A, Record[Out]) => Loop.Outcome[A, V] < S2
    )(using Frame): Flow[In, Out & (N ~ V), S & S2] =
        AndThen(
            this,
            LoopNode[Out, N, A, V, S2](name, body, init, Maybe(schedule), Meta())
        )

    /** Loop on a schedule with 2 state values. Between iterations, the flow durably sleeps for the delay from `Schedule.next`. */
    def loopOn[N <: String & Singleton, A: Tag: Schema, B: Tag: Schema, V: Tag: Schema, S2](
        name: N,
        schedule: Schedule,
        init1: A,
        init2: B
    )(
        body: (A, B, Record[Out]) => Loop.Outcome2[A, B, V] < S2
    )(using Frame, Tag[(A, B)], Schema[(A, B)]): Flow[In, Out & (N ~ V), S & S2] =
        AndThen(
            this,
            LoopNode[Out, N, (A, B), V, S2](name, (state, ctx) => body(state._1, state._2, ctx), (init1, init2), Maybe(schedule), Meta())
        )

    // --- Composition ---

    /** Execute two flows in parallel and merge their outputs. Both must complete. */
    def zip[In2, Out2, S2](other: Flow[In2, Out2, S2])(using
        Frame,
        Isolate[S & S2, Abort[FlowException] & Async, S & S2]
    ): Flow[In & In2, Out & Out2, S & S2] = Zip(this, other)

    /** Sequence two flows: the first runs to completion, then the second starts with access to all prior outputs. */
    def andThen[In2, Out2, S2](next: Flow[In2, Out2, S2])(using Frame): Flow[In & In2, Out & Out2, S & S2] =
        AndThen(this, next)

end Flow

object Flow:

    // --- Entry points ---

    /** Start building a named workflow. The name is the workflow's identity used for registration, lookup, and HTTP endpoints.
      *
      * {{{
      * val order = Flow.init("order-processing", description = "Handles orders")
      *     .input[Order]("order")
      *     .output("total")(ctx => ctx.order.qty * ctx.order.price)
      * }}}
      */
    def init(
        name: String,
        description: String = "",
        version: String = "",
        tags: Seq[String] = Seq.empty
    )(using Frame): Flow[Any, Any, Any] =
        require(name != null && name.nonEmpty, "Flow name must not be null or empty")
        internal.Init(name, Meta(description = description, version = version, tags = tags))
    end init

    /** Start building a flow with a named input. Shorthand for `Flow.init(name).input[V](inputName)`. */
    def input[V](using
        Frame
    )[N <: String & Singleton](
        name: N,
        description: String = "",
        tags: Seq[String] = Seq.empty
    )(using Tag[V], Schema[V]): Flow[N ~ V, N ~ V, Any] =
        init(name).input[V](name, description, tags = tags)

    // --- Run ---

    /** Start an HTTP server with REST endpoints for all given workflows, using an in-memory store.
      *
      * This is the fastest way to get a flow server running. For production use with a durable store, use `runServer(store, flows*)`.
      * {{{
      * Flow.runServer(orderFlow, shippingFlow)
      * }}}
      */
    def runServer(flows: Flow[?, ?, ?]*)(using Frame): HttpServer < (Async & Scope) =
        FlowStore.initMemory.map(store => runServer(store, flows*))

    def runServer[S](flows: Flow[?, ?, S]*)(
        runner: [V] => V < S => V < (Async & Scope & Abort[FlowException])
    )(using Frame): HttpServer < (Async & Scope) =
        FlowStore.initMemory.map(store => runServer(store, flows*)(runner))

    /** Start an HTTP server backed by a specific store. */
    def runServer(store: FlowStore, flows: Flow[?, ?, ?]*)(using Frame): HttpServer < (Async & Scope) =
        runHandlers(store, flows*).map(h => HttpServer.init(h.toSeq*))

    def runServer[S](store: FlowStore, flows: Flow[?, ?, S]*)(
        runner: [V] => V < S => V < (Async & Scope & Abort[FlowException])
    )(using Frame): HttpServer < (Async & Scope) =
        runHandlers(store, flows*)(runner).map(h => HttpServer.init(h.toSeq*))

    /** Get HTTP handlers without starting a server. Compose with your own endpoints. */
    def runHandlers(store: FlowStore, flows: Flow[?, ?, ?]*)(using Frame): Chunk[HttpHandler[?, ?, ?]] < (Async & Scope) =
        FlowEngine.initImpl(store, flows = flows).map(engine => kyo.internal.FlowApi.handlers(engine))

    def runHandlers[S](store: FlowStore, flows: Flow[?, ?, S]*)(
        runner: [V] => V < S => V < (Async & Scope & Abort[FlowException])
    )(using Frame): Chunk[HttpHandler[?, ?, ?]] < (Async & Scope) =
        FlowEngine.initImpl(store, flows = flows, runner = Maybe(FlowRunner[S](runner)))
            .map(engine => kyo.internal.FlowApi.handlers(engine))

    /** Get HTTP handlers from an existing engine. */
    def runHandlers(engine: FlowEngine)(using Frame): Chunk[HttpHandler[?, ?, ?]] =
        kyo.internal.FlowApi.handlers(engine)

    /** Execute a flow locally with an in-memory store. Blocks until the flow completes and returns the full output record.
      *
      * Useful for testing and simple scripts. Pre-populates inputs from the provided record. For durable production execution, use
      * `FlowEngine.init` instead.
      */
    def runLocal[In, Out](
        flow: Flow[In, Out, ?],
        inputs: Record[In] = Record.empty
    )(using Frame): Record[In & Out] < (Async & Scope & Abort[FlowException]) =
        runLocalImpl(flow, inputs, Maybe.empty)

    /** Execute a flow locally with a runner that handles custom effects.
      *
      * The runner wraps the entire flow execution, providing effect handlers that step bodies need. The compiler infers `S` from the flow,
      * then requires the runner to handle it.
      *
      * {{{
      * Flow.runLocal(myFlow, "x" ~ 42)([v] => c => Env.run(config)(c))
      * }}}
      */
    def runLocal[In, Out, S](
        flow: Flow[In, Out, S],
        inputs: Record[In]
    )(runner: [V] => V < S => V < (Async & Scope & Abort[FlowException]))(using
        Frame
    ): Record[In & Out] < (Async & Scope & Abort[FlowException]) =
        runLocalImpl(flow, inputs, Maybe(FlowRunner[S](runner)))

    private def runLocalImpl[In, Out](
        flow: Flow[In, Out, ?],
        inputs: Record[In],
        runner: Maybe[FlowRunner]
    )(using Frame): Record[In & Out] < (Async & Scope & Abort[FlowException]) =
        FlowStore.initMemory.map { store =>
            FlowEngine.initImpl(store, workerCount = 1, pollTimeout = 100.millis).map { engine =>
                val wfId = Flow.Id.Workflow("_local")
                engine.registerImpl(wfId, flow, runner).map { _ =>
                    engine.workflows.start(wfId, inputs.asInstanceOf[Record[Any]]).map { handle =>
                        val eid = handle.executionId
                        def await: Record[In & Out] < (Async & Abort[FlowException]) =
                            Async.sleep(10.millis).map { _ =>
                                store.getExecution(eid).map {
                                    case Present(state) if state.status == Flow.Status.Completed =>
                                        store.getAllFields(eid).map { fields =>
                                            val schema = WorkflowSchema.of(flow)
                                            val dict = fields.foldLeft(Dict.empty[String, Any]) { (acc, name, fd) =>
                                                schema.fromStoreName(name) match
                                                    case Present(entry) =>
                                                        entry.decode(fd) match
                                                            case Present(v) => acc.update(name, v)
                                                            case _          => acc
                                                    case _ => acc
                                            }
                                            new Record[In & Out](dict)
                                        }
                                    case Present(state) =>
                                        state.status match
                                            case Flow.Status.Failed(err) =>
                                                Abort.fail(FlowExecutionFailedException(eid.value, err))
                                            case Flow.Status.Cancelled =>
                                                Abort.fail(FlowCancelledException(eid.value))
                                            case _ => await
                                    case _ => await
                                }
                            }
                        await
                    }
                }
            }
        }
    end runLocalImpl

    // --- Combinators ---

    /** Race two flows — the first to complete wins, the other is cancelled. Output type is the union of both flows' outputs. */
    def race[In1, Out1, S1, In2, Out2, S2](left: Flow[In1, Out1, S1], right: Flow[In2, Out2, S2])(
        using
        Frame,
        Isolate[S1 & S2, Abort[FlowException] & Async, S1 & S2]
    ): Flow[In1 & In2, Out1 | Out2, S1 & S2] =
        internal.Race(left, right)

    /** Execute multiple flows in parallel and merge all their outputs. All branches must complete. */
    def gather[In1, Out1, S1, In2, Out2, S2](f1: Flow[In1, Out1, S1], f2: Flow[In2, Out2, S2])(
        using
        Frame,
        Isolate[S1 & S2, Abort[FlowException] & Async, S1 & S2]
    ): Flow[In1 & In2, Out1 & Out2, S1 & S2] =
        internal.Gather[In1 & In2, Out1 & Out2, S1 & S2](Chunk(f1, f2))

    def gather[In1, Out1, S1, In2, Out2, S2, In3, Out3, S3](
        f1: Flow[In1, Out1, S1],
        f2: Flow[In2, Out2, S2],
        f3: Flow[In3, Out3, S3]
    )(using
        Frame,
        Isolate[S1 & S2 & S3, Abort[FlowException] & Async, S1 & S2 & S3]
    ): Flow[In1 & In2 & In3, Out1 & Out2 & Out3, S1 & S2 & S3] =
        internal.Gather[In1 & In2 & In3, Out1 & Out2 & Out3, S1 & S2 & S3](Chunk(f1, f2, f3))

    def gather[In1, Out1, S1, In2, Out2, S2, In3, Out3, S3, In4, Out4, S4](
        f1: Flow[In1, Out1, S1],
        f2: Flow[In2, Out2, S2],
        f3: Flow[In3, Out3, S3],
        f4: Flow[In4, Out4, S4]
    )(using
        Frame,
        Isolate[S1 & S2 & S3 & S4, Abort[FlowException] & Async, S1 & S2 & S3 & S4]
    ): Flow[In1 & In2 & In3 & In4, Out1 & Out2 & Out3 & Out4, S1 & S2 & S3 & S4] =
        internal.Gather[In1 & In2 & In3 & In4, Out1 & Out2 & Out3 & Out4, S1 & S2 & S3 & S4](Chunk(f1, f2, f3, f4))

    def gather[In1, Out1, S1, In2, Out2, S2, In3, Out3, S3, In4, Out4, S4, In5, Out5, S5](
        f1: Flow[In1, Out1, S1],
        f2: Flow[In2, Out2, S2],
        f3: Flow[In3, Out3, S3],
        f4: Flow[In4, Out4, S4],
        f5: Flow[In5, Out5, S5]
    )(using
        Frame,
        Isolate[S1 & S2 & S3 & S4 & S5, Abort[FlowException] & Async, S1 & S2 & S3 & S4 & S5]
    ): Flow[In1 & In2 & In3 & In4 & In5, Out1 & Out2 & Out3 & Out4 & Out5, S1 & S2 & S3 & S4 & S5] =
        internal.Gather[In1 & In2 & In3 & In4 & In5, Out1 & Out2 & Out3 & Out4 & Out5, S1 & S2 & S3 & S4 & S5](Chunk(f1, f2, f3, f4, f5))

    // --- Rendering ---

    export FlowRender.render
    export FlowRender.renderBpmn
    export FlowRender.renderDot
    export FlowRender.renderElk
    export FlowRender.renderJson
    export FlowRender.renderMermaid

    def lint(flow: Flow[?, ?, ?]): Seq[FlowLint.Warning] =
        FlowLint.check(flow)

    // --- Types ---

    /** Strict opaque ID types preventing mix-ups between workflow, execution, and executor identifiers. */
    object Id:
        opaque type Workflow = String
        object Workflow:
            def apply(s: String): Workflow             = s
            given Schema[Workflow]                     = summon[Schema[String]]
            given CanEqual[Workflow, Workflow]         = CanEqual.derived
            extension (id: Workflow) def value: String = id
        end Workflow

        opaque type Execution = String
        object Execution:
            def apply(s: String): Execution = s
            def random(using Frame): Execution < Sync =
                Random.uuid
            given Schema[Execution]                     = summon[Schema[String]]
            given CanEqual[Execution, Execution]        = CanEqual.derived
            extension (id: Execution) def value: String = id
        end Execution

        opaque type Executor = String
        object Executor:
            def apply(s: String): Executor = s
            def random(using Frame): Executor < Sync =
                Random.uuid
            given Schema[Executor]                     = summon[Schema[String]]
            given CanEqual[Executor, Executor]         = CanEqual.derived
            extension (id: Executor) def value: String = id
        end Executor
    end Id

    /** Per-node metadata for flow steps.
      *
      * @param timeout
      *   Per-attempt timeout. If a single attempt exceeds this duration, it fails with a timeout error. Also used as in-flight fencing: how
      *   long a new executor waits for a competing executor's in-progress step before re-executing.
      * @param retry
      *   Retry schedule for transient failures. When present, the engine retries the step computation according to the schedule's delays.
      *   When the schedule exhausts, the last error propagates. Each attempt is independently timed by `timeout`.
      */
    case class Meta(
        description: String = "",
        tags: Seq[String] = Seq.empty,
        timeout: Duration = Duration.Infinity,
        retry: Maybe[Schedule] = Maybe.empty,
        version: String = ""
    ) derives Schema
    object Meta

    case class BranchInfo(name: String, frame: Frame, meta: Meta)

    // --- Diagram format ---

    /** Supported output formats for workflow and execution diagrams. */
    enum DiagramFormat derives CanEqual:
        case Mermaid, Dot, Bpmn, Elk, Json
    end DiagramFormat

    object DiagramFormat:
        /** Parse a format string (case-insensitive). Returns Mermaid for unrecognized input. */
        def fromString(s: String): DiagramFormat =
            s.toLowerCase match
                case "mermaid" => DiagramFormat.Mermaid
                case "dot"     => DiagramFormat.Dot
                case "bpmn"    => DiagramFormat.Bpmn
                case "elk"     => DiagramFormat.Elk
                case "json"    => DiagramFormat.Json
                case _         => DiagramFormat.Mermaid
    end DiagramFormat

    // --- Status ---

    enum Status derives CanEqual, Schema:
        case Running
        case WaitingForInput(name: String)
        case Sleeping(name: String, until: Instant)
        case Completed
        case Failed(error: String)
        case Compensating
        case Cancelled

        def show: String = this match
            case Running               => "running"
            case WaitingForInput(name) => s"waiting:$name"
            case Sleeping(name, until) => s"sleeping:$name"
            case Completed             => "completed"
            case Failed(error)         => s"failed:$error"
            case Compensating          => "compensating"
            case Cancelled             => "cancelled"

        def isTerminal: Boolean = this match
            case Completed | Failed(_) | Cancelled => true
            case _                                 => false

        def isSleeping: Boolean = this match
            case _: Sleeping => true
            case _           => false

        def isWaitingForInput: Boolean = this match
            case _: WaitingForInput => true
            case _                  => false
    end Status

    // --- Event ---

    enum EventKind derives Schema:
        case Created, StepStarted, StepCompleted, StepRetried, StepTimedOut,
            InputWaiting, InputReceived, SleepStarted, SleepCompleted,
            ExecutionResumed, ExecutionClaimed, ExecutionReleased,
            Completed, Failed, CompensationStarted, CompensationCompleted, CompensationFailed, Cancelled
    end EventKind

    enum Event derives Schema:
        def flowId: Flow.Id.Workflow
        def executionId: Flow.Id.Execution
        def timestamp: Instant

        case Created(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, timestamp: Instant)
        case StepStarted(
            flowId: Flow.Id.Workflow,
            executionId: Flow.Id.Execution,
            stepName: String,
            executorId: Flow.Id.Executor,
            timestamp: Instant
        )
        case StepCompleted(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, stepName: String, timestamp: Instant)
        case InputWaiting(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, inputName: String, timestamp: Instant)
        case InputReceived(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, inputName: String, timestamp: Instant)
        case SleepStarted(
            flowId: Flow.Id.Workflow,
            executionId: Flow.Id.Execution,
            stepName: String,
            until: Instant,
            timestamp: Instant
        )
        case SleepCompleted(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, stepName: String, timestamp: Instant)
        case Completed(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, timestamp: Instant)
        case Failed(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, error: String, timestamp: Instant)
        case CompensationStarted(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, timestamp: Instant)
        case CompensationCompleted(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, timestamp: Instant)
        case CompensationFailed(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, error: String, timestamp: Instant)
        case StepRetried(
            flowId: Flow.Id.Workflow,
            executionId: Flow.Id.Execution,
            stepName: String,
            error: String,
            attempt: Int,
            delay: Duration,
            timestamp: Instant
        )
        case StepTimedOut(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, stepName: String, timeout: Duration, timestamp: Instant)
        case ExecutionResumed(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, executorId: Flow.Id.Executor, timestamp: Instant)
        case ExecutionClaimed(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, executorId: Flow.Id.Executor, timestamp: Instant)
        case ExecutionReleased(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, executorId: Flow.Id.Executor, timestamp: Instant)
        case Cancelled(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, timestamp: Instant)

        def kind: EventKind = this match
            case _: Created               => EventKind.Created
            case _: StepStarted           => EventKind.StepStarted
            case _: StepCompleted         => EventKind.StepCompleted
            case _: InputWaiting          => EventKind.InputWaiting
            case _: InputReceived         => EventKind.InputReceived
            case _: SleepStarted          => EventKind.SleepStarted
            case _: SleepCompleted        => EventKind.SleepCompleted
            case _: Completed             => EventKind.Completed
            case _: Failed                => EventKind.Failed
            case _: CompensationStarted   => EventKind.CompensationStarted
            case _: CompensationCompleted => EventKind.CompensationCompleted
            case _: CompensationFailed    => EventKind.CompensationFailed
            case _: StepRetried           => EventKind.StepRetried
            case _: StepTimedOut          => EventKind.StepTimedOut
            case _: ExecutionResumed      => EventKind.ExecutionResumed
            case _: ExecutionClaimed      => EventKind.ExecutionClaimed
            case _: ExecutionReleased     => EventKind.ExecutionReleased
            case _: Cancelled             => EventKind.Cancelled

        def detail: String = this match
            case StepStarted(_, _, name, _, _)           => name
            case StepCompleted(_, _, name, _)            => name
            case InputWaiting(_, _, name, _)             => name
            case InputReceived(_, _, name, _)            => name
            case SleepStarted(_, _, name, _, _)          => name
            case SleepCompleted(_, _, name, _)           => name
            case Failed(_, _, error, _)                  => error
            case CompensationFailed(_, _, error, _)      => error
            case StepRetried(_, _, name, error, n, _, _) => s"$name (attempt $n: $error)"
            case StepTimedOut(_, _, name, _, _)          => name
            case _                                       => ""
    end Event

    // --- Dispatch helpers ---

    /** Builder for conditional dispatch (branching). Chain `.when(cond)(body)` to add branches, then `.otherwise(body)` to complete.
      *
      * {{{
      * flow.dispatch[String]("decision")
      *     .when(ctx => ctx.amount > 1000, name = "review")(ctx => "needs review")
      *     .when(ctx => ctx.amount > 100, name = "auto")(ctx => "auto-approved")
      *     .otherwise(ctx => "instant", name = "default")
      * }}}
      */
    final class PartialDispatch[In, Out, Sf, Sc, N <: String, V] private[kyo] (
        private[kyo] val flow: Flow[In, Out, Sf],
        private[kyo] val name: N,
        private[kyo] val branches: Chunk[internal.BranchData[Any, Any, Any]],
        private[kyo] val meta: Meta,
        private[kyo] val dispatchFrame: Frame,
        private[kyo] val vtag: Tag[V]
    ):
        def when[S2](
            cond: Record[Out] => Boolean < S2,
            name: String,
            description: String = "",
            tags: Seq[String] = Seq.empty
        )(body: Record[Out] => V < S2)(using frame: Frame): PartialDispatch[In, Out, Sf, Sc & S2, N, V] =
            PartialDispatch(
                flow,
                this.name,
                branches :+ internal.BranchData(name, cond, body, frame, Meta(description, tags)).erased,
                meta,
                dispatchFrame,
                vtag
            )

        def when[S2](cond: Record[Out] => Boolean < S2, name: String, meta: Meta)(body: Record[Out] => V < S2)(
            using frame: Frame
        ): PartialDispatch[In, Out, Sf, Sc & S2, N, V] =
            PartialDispatch(
                flow,
                this.name,
                branches :+ internal.BranchData(name, cond, body, frame, meta).erased,
                meta,
                dispatchFrame,
                vtag
            )

        def otherwise[S2](body: Record[Out] => V < S2, name: String, description: String = "")(
            using
            frame: Frame,
            schema: Schema[V]
        ): Flow[In, Out & (N ~ V), Sf & Sc & S2] =
            val dispatch = internal.Dispatch[Out, N, V, S2, Sc](
                this.name,
                branches,
                name,
                body,
                frame,
                meta
            )(using dispatchFrame, vtag, schema)
            internal.AndThen(flow, dispatch)(using dispatchFrame)
        end otherwise

        inline def output[N2 <: String & Singleton, V2, S2](name: N2, meta: Meta = Meta())(fn: Record[Out] => V2 < S2)(
            using
            Frame,
            Tag[V2]
        ): Nothing =
            compiletime.error(
                "dispatch requires .otherwise(...) before continuing the flow."
            )
        inline def step[S2](name: String, meta: Meta = Meta())(fn: Record[Out] => Unit < S2)(using Frame): Nothing =
            compiletime.error("dispatch requires .otherwise(...) before continuing the flow.")
        inline def input[V2](using Frame)[N2 <: String & Singleton](name: N2, meta: Meta = Meta())(using Tag[V2], Schema[V2]): Nothing =
            compiletime.error("dispatch requires .otherwise(...) before continuing the flow.")
        inline def sleep(name: String, duration: Duration, meta: Meta = Meta())(using Frame): Nothing =
            compiletime.error("dispatch requires .otherwise(...) before continuing the flow.")
    end PartialDispatch

    final class DispatchStarter[In, Out, Sf, V] private[kyo] (
        private[kyo] val flow: Flow[In, Out, Sf],
        private[kyo] val vtag: Tag[V]
    ):
        def apply[N <: String & Singleton](
            name: N,
            description: String = "",
            timeout: Duration = Duration.Infinity,
            retry: Maybe[Schedule] = Maybe.empty,
            tags: Seq[String] = Seq.empty
        )(using Frame): PartialDispatch[In, Out, Sf, Any, N, V] =
            PartialDispatch(flow, name, Chunk.empty, Meta(description, tags, timeout, retry), summon[Frame], vtag)
        def apply[N <: String & Singleton](name: N, meta: Meta)(using Frame): PartialDispatch[In, Out, Sf, Any, N, V] =
            PartialDispatch(flow, name, Chunk.empty, meta, summon[Frame], vtag)
    end DispatchStarter

    // --- Run (interpreter loop) ---

    private[kyo] def run[In, Out, S <: Sync](
        flow: Flow[In, Out, ?],
        inputs: Record[Any] = Record.empty,
        completedEvents: Set[String] = Set.empty
    )(
        interpreter: FlowInterpreter[S]
    )(using Frame): Record[In & Out] < S =

        case class Compensation(ctx: Record[Any], handler: Record[Any] => Any, branchId: Maybe[Int])

        def addField(ctx: Record[Any], name: String, value: Any): Record[Any] =
            new Record[Any](ctx.toDict ++ Dict(name -> value))

        AtomicRef.init[Chunk[Compensation]](Chunk.empty).map { compsRef =>

            def pushComp(
                branchId: Maybe[Int],
                ctx: Record[Any],
                handler: Record[Any] => Any
            ): Unit < Sync =
                compsRef.getAndUpdate(Compensation(ctx, handler, branchId) +: _).unit

            def snapshotComps(): Chunk[Compensation] < Sync = compsRef.get

            def restoreCompsForBranch(snapshot: Chunk[Compensation], branchId: Int): Unit < Sync =
                compsRef.getAndUpdate { current =>
                    snapshot ++ current.filter(_.branchId != Maybe(branchId))
                }.unit

            def filterComps(winnerCtx: Record[Any]): Unit < Sync =
                val allKeys = winnerCtx.toDict.foldLeft(Set.empty[String])((acc, k, _) => acc + k)
                compsRef.getAndUpdate { current =>
                    current.filter { comp =>
                        val entryKeys = comp.ctx.toDict.foldLeft(Set.empty[String])((acc, k, _) => acc + k)
                        entryKeys.subsetOf(allKeys)
                    }
                }.unit
            end filterComps

            def runComps(): Unit < S =
                compsRef.use { comps =>
                    Kyo.foreachDiscard(comps.toSeq) { comp =>
                        Abort.run[Throwable](comp.handler(comp.ctx)).map {
                            case Result.Panic(ex)   => interpreter.onCompensationFailed(ex)
                            case Result.Failure(ex) => interpreter.onCompensationFailed(ex)
                            case _                  => ()
                        }
                    }
                }

            def fieldCompleted(ctx: Record[Any], name: String): Boolean =
                ctx.toDict.get(name) match
                    case Present(_) => true
                    case _          => false

            def eventCompleted(name: String): Boolean =
                completedEvents.contains(name)

            // branchId tracks which Recover branch compensations belong to
            // nextBranch is a running counter threaded through recursive calls
            def loop(flow: Flow[?, ?, ?], ctx: Record[Any], branchId: Maybe[Int] = Maybe.empty, nextBranch: Int = 0): Record[Any] < S =
                flow match
                    case _: Init => ctx

                    case n: Output[?, ?, ?, ?, ?] @unchecked =>
                        val e = n.erased
                        if fieldCompleted(ctx, n.name) then
                            e.compensate match
                                case Present(handler) => pushComp(branchId, ctx, handler).andThen(ctx)
                                case _                => ctx
                        else
                            val computation = Sync.defer(e.fn(ctx))
                            interpreter.onOutput(n.name, computation, n.frame, n.meta)(using e.tag, e.schema)
                                .map { v =>
                                    val result = addField(ctx, n.name, v)
                                    e.compensate match
                                        case Present(handler) => pushComp(branchId, result, handler).andThen(result)
                                        case _                => result
                                    end match
                                }
                        end if

                    case n: Step[?, ?] @unchecked =>
                        val e = n.erased
                        if eventCompleted(n.name) then
                            e.compensate match
                                case Present(handler) => pushComp(branchId, ctx, handler).andThen(ctx)
                                case _                => ctx
                        else
                            val computation = Sync.defer(e.fn(ctx))
                            interpreter.onStep(n.name, computation, n.frame, n.meta).map { _ =>
                                e.compensate match
                                    case Present(handler) => pushComp(branchId, ctx, handler).andThen(ctx)
                                    case _                => ctx
                            }
                        end if

                    case n: Input[?, ?] @unchecked =>
                        if fieldCompleted(ctx, n.name) then ctx
                        else
                            interpreter.onInput(n.name, n.frame, n.meta)(using n.erased.tag, n.erased.schema)
                                .map(v => addField(ctx, n.name, v))

                    case n: Sleep =>
                        if eventCompleted(n.name) then ctx
                        else
                            interpreter.onSleep(n.name, n.duration, n.frame, n.meta)
                                .andThen(ctx)

                    case n: Dispatch[?, ?, ?, ?, ?] @unchecked =>
                        if fieldCompleted(ctx, n.name) then ctx
                        else
                            def dispatchBody: Any < Any =
                                def tryBranches(idx: Int): Any < Any =
                                    if idx >= n.branches.length then
                                        n.erased.default(ctx)
                                    else
                                        val b = n.branches(idx)
                                        b.cond(ctx).map {
                                            case true  => b.body(ctx)
                                            case false => tryBranches(idx + 1)
                                        }
                                tryBranches(0)
                            end dispatchBody
                            val computation = Sync.defer(dispatchBody)
                            interpreter.onOutput(n.name, computation, n.frame, n.meta)(using n.erased.tag, n.erased.schema)
                                .map(v => addField(ctx, n.name, v))

                    case n: LoopNode[?, ?, ?, ?, ?] @unchecked =>
                        if fieldCompleted(ctx, n.name) then ctx
                        else
                            val r       = n.erased
                            val nameStr = r.name: String

                            import kyo.kernel.Loop.{Continue, Continue2}

                            def extractDoneValue(outcome: Any): Maybe[Any] =
                                outcome match
                                    case _: Continue[?] @unchecked     => Maybe.empty
                                    case _: Continue2[?, ?] @unchecked => Maybe.empty
                                    case done                          => Maybe(done)

                            def extractContinueState(outcome: Any): Maybe[Any] =
                                outcome match
                                    case c: Continue2[?, ?] @unchecked => Maybe((c._1, c._2))
                                    case c: Continue[?] @unchecked     => Maybe(c._1)
                                    case _                             => Maybe.empty

                            r.schedule match
                                case Absent =>
                                    // Non-scheduled: all iterations in one computation
                                    def loopBody: Any < Any =
                                        def iterate(state: Any, current: Record[Any]): Any < Any =
                                            r.body(state, current).map { outcome =>
                                                extractDoneValue(outcome) match
                                                    case Present(done) => done
                                                    case _ =>
                                                        extractContinueState(outcome) match
                                                            case Present(newState) => iterate(newState, current)
                                                            case _                 => outcome
                                            }
                                        iterate(r.initialState, ctx)
                                    end loopBody
                                    val computation = Sync.defer(loopBody)
                                    interpreter.onOutput(nameStr, computation, n.frame, n.meta)(using r.tag, r.schema)
                                        .map(v => addField(ctx, nameStr, v))

                                case Present(schedule) =>
                                    // Scheduled: iterate at S level with durable sleep between iterations

                                    def iterate(
                                        state: Any,
                                        current: Record[Any],
                                        iterNum: Int,
                                        sched: Schedule
                                    ): Record[Any] < S =
                                        interpreter.checkCancelled.map {
                                            case true => current // cancelled — stop iterating
                                            case false =>
                                                val iterName  = IterationName.step(nameStr, iterNum)
                                                val sleepName = IterationName.sleep(nameStr, iterNum)
                                                if eventCompleted(iterName) then
                                                    // Resuming from checkpoint — this iteration was already recorded
                                                    // If a field was stored the iteration completed with a final value; otherwise it was a continue
                                                    interpreter.getField[Any](iterName)(using r.tag, r.schema).map {
                                                        case Present(result) =>
                                                            // Done iteration — result is stored, this is the final value
                                                            addField(current, nameStr, result)
                                                        case _ =>
                                                            // Continue iteration (was stored as step, no field value)
                                                            if eventCompleted(sleepName) then
                                                                // Sleep also done — advance schedule and continue
                                                                Clock.nowWith { now =>
                                                                    sched.next(now) match
                                                                        case Present((_, nextSched)) =>
                                                                            iterate(state, current, iterNum + 1, nextSched)
                                                                        case _ => current
                                                                }
                                                            else
                                                                // Sleep was not completed — re-execute from this point
                                                                Clock.nowWith { now =>
                                                                    sched.next(now) match
                                                                        case Present((delay, nextSched)) =>
                                                                            interpreter.onSleep(sleepName, delay, n.frame, n.meta)
                                                                                .map(_ =>
                                                                                    iterate(state, current, iterNum + 1, nextSched)
                                                                                )
                                                                        case _ => current
                                                                }
                                                            end if
                                                    }
                                                else executeIteration(state, current, iterNum, sched)
                                                end if
                                        } // end case false (not cancelled)
                                    end iterate

                                    def executeIteration(
                                        state: Any,
                                        current: Record[Any],
                                        iterNum: Int,
                                        sched: Schedule
                                    ): Record[Any] < S =
                                        val iterName  = s"$nameStr#$iterNum"
                                        val sleepName = s"$nameStr##$iterNum"
                                        // Compute the outcome first, then decide what to persist
                                        Sync.defer(r.body(state, current)).map { rawOutcome =>
                                            extractContinueState(rawOutcome) match
                                                case Present(newState) =>
                                                    // Continue — mark iteration, then sleep
                                                    interpreter.onStep(iterName, Kyo.unit, n.frame, n.meta).map { _ =>
                                                        Clock.nowWith { now =>
                                                            sched.next(now) match
                                                                case Present((delay, nextSched)) =>
                                                                    interpreter.onSleep(sleepName, delay, n.frame, n.meta)
                                                                        .andThen(iterate(newState, current, iterNum + 1, nextSched))
                                                                case _ =>
                                                                    val updated = addField(current, nameStr, ())
                                                                    interpreter.onOutput(nameStr, Kyo.unit, n.frame, n.meta)(using
                                                                        r.tag,
                                                                        r.schema
                                                                    )
                                                                        .andThen(updated)
                                                        }
                                                    }
                                                case _ =>
                                                    // Done — store final result
                                                    interpreter.onOutput(iterName, rawOutcome, n.frame, n.meta)(using r.tag, r.schema)
                                                        .andThen {
                                                            val updated = addField(current, nameStr, rawOutcome)
                                                            interpreter.onOutput(nameStr, rawOutcome, n.frame, n.meta)(using
                                                                r.tag,
                                                                r.schema
                                                            )
                                                                .andThen(updated)
                                                        }
                                        }
                                    end executeIteration

                                    iterate(r.initialState, ctx, 0, schedule)
                            end match

                    case n: ForEach[?, ?, ?, ?, ?] @unchecked =>
                        if fieldCompleted(ctx, n.name) then ctx
                        else
                            val nameStr: String = n.name
                            def foreachBody: Any < Any =
                                n.erased.collection(ctx).map { items =>
                                    val seq = items.asInstanceOf[Seq[Any]]
                                    Kyo.foldLeft(seq)(Chunk.empty[Any]) { (acc, item) =>
                                        n.erased.body(item).map(acc :+ _)
                                    }
                                }
                            end foreachBody
                            given Schema[Any] = n.erased.schema
                            val seqSchema     = summon[Schema[Seq[Any]]].asInstanceOf[Schema[Any]]
                            val computation   = Sync.defer(foreachBody)
                            interpreter.onOutput(nameStr, computation, n.frame, n.meta)(using Tag[Any], seqSchema)
                                .map { stored =>
                                    val chunk = stored match
                                        case c: Chunk[?] => c
                                        case s: Seq[?]   => Chunk.from(s)
                                        case other       => Chunk(other)
                                    addField(ctx, nameStr, chunk)
                                }

                    case n: Race[?, ?, ?, ?, ?, ?] @unchecked =>
                        snapshotComps().map { snapshot =>
                            val leftResult  = loop(n.left, ctx, branchId, nextBranch)
                            val rightResult = loop(n.right, ctx, branchId, nextBranch)
                            interpreter.onRace(leftResult, rightResult, n.erased.isolate).map { winnerCtx =>
                                filterComps(winnerCtx).andThen(winnerCtx)
                            }
                        }

                    case n: Subflow[?, ?, ?, ?, ?, ?] @unchecked =>
                        if fieldCompleted(ctx, n.name) then ctx
                        else
                            val nameStr = n.name: String
                            n.erased.inputMapper(ctx)
                                .map(inputRecord => loop(n.childFlow, inputRecord, branchId, nextBranch))
                                .map(childResult => addField(ctx, nameStr, childResult))

                    case n: AndThen[?, ?, ?, ?, ?, ?] @unchecked =>
                        loop(n.first, ctx, branchId, nextBranch).map(ctx2 => loop(n.second, ctx2, branchId, nextBranch))

                    case n: Zip[?, ?, ?, ?, ?, ?] @unchecked =>
                        val l = loop(n.left, ctx, branchId, nextBranch)
                        val r = loop(n.right, ctx, branchId, nextBranch)
                        interpreter.onZip(l, r, ctx, n.erased.isolate)

                    case n: Gather[?, ?, ?] @unchecked =>
                        if n.flows.isEmpty then ctx
                        else
                            n.flows.toSeq.map(f => loop(f, ctx, branchId, nextBranch))
                                .reduce((l, r) => interpreter.onZip(l, r, ctx, n.erased.isolate))

            val rawResult = loop(flow, new Record[Any](inputs.toDict))
            Abort.run[Throwable](rawResult).map {
                case Result.Success(record) =>
                    record.asInstanceOf[Record[In & Out]]
                case Result.Failure(ex) =>
                    compsRef.use { comps =>
                        if comps.nonEmpty then
                            interpreter.onCompensationStart
                                .andThen(runComps())
                                .andThen(interpreter.onCompensationComplete)
                                .andThen(Abort.panic(ex))
                        else Abort.panic(ex)
                    }
                case Result.Panic(ex) =>
                    compsRef.use { comps =>
                        if comps.nonEmpty then
                            interpreter.onCompensationStart
                                .andThen(runComps())
                                .andThen(interpreter.onCompensationComplete)
                                .andThen(Abort.panic(ex))
                        else Abort.panic(ex)
                    }
            }
        }
    end run

    // --- Internal AST ---

    private[kyo] object internal:

        final case class BranchData[Ctx, V, S](
            name: String,
            cond: Record[Ctx] => Boolean < S,
            body: Record[Ctx] => V < S,
            frame: Frame,
            meta: Meta
        ):
            private[kyo] def erased: BranchData[Any, Any, Any] = this.asInstanceOf[BranchData[Any, Any, Any]]
        end BranchData

        final case class Init(name: String, meta: Meta)(using val frame: Frame)
            extends Flow[Any, Any, Any]

        final case class Output[Ctx, CompCtx, N <: String, V, S](
            name: N & String,
            fn: Record[Ctx] => V < S,
            meta: Meta,
            compensate: Maybe[Record[CompCtx] => Unit < (Async & Abort[FlowException])]
        )(using val frame: Frame, val tag: Tag[V], val schema: Schema[V]) extends Flow[Any, N ~ V, S]:
            private[kyo] def erased: Output[Any, Any, String, Any, Any] = this.asInstanceOf[Output[Any, Any, String, Any, Any]]
        end Output

        final case class Step[Ctx, S](
            name: String,
            fn: Record[Ctx] => Unit < S,
            meta: Meta,
            compensate: Maybe[Record[Ctx] => Unit < (Async & Abort[FlowException])]
        )(using val frame: Frame) extends Flow[Any, Any, S]:
            private[kyo] def erased: Step[Any, Any] = this.asInstanceOf[Step[Any, Any]]
        end Step

        final case class Input[N <: String, V](name: N & String, meta: Meta)(
            using
            val frame: Frame,
            val tag: Tag[V],
            val schema: Schema[V]
        ) extends Flow[N ~ V, N ~ V, Any]:
            private[kyo] def erased: Input[String, Any] = this.asInstanceOf[Input[String, Any]]
        end Input

        final case class Sleep(name: String, duration: Duration, meta: Meta)(using val frame: Frame)
            extends Flow[Any, Any, Any]

        final case class Dispatch[Ctx, N <: String, V, S, Sb](
            name: N & String,
            branches: Chunk[BranchData[Any, Any, Any]],
            defaultName: String,
            default: Record[Ctx] => V < S,
            defaultFrame: Frame,
            meta: Meta
        )(using val frame: Frame, val tag: Tag[V], val schema: Schema[V]) extends Flow[Any, N ~ V, S & Sb]:
            private[kyo] def erased: Dispatch[Any, String, Any, Any, Any] = this.asInstanceOf[Dispatch[Any, String, Any, Any, Any]]
        end Dispatch

        final case class LoopNode[Ctx, N <: String, State, V, S](
            name: N & String,
            body: (State, Record[Ctx]) => Any < S, // returns Loop.Outcome[State, V] (erased at S boundary)
            initialState: State,
            schedule: Maybe[Schedule],
            meta: Meta
        )(using val frame: Frame, val tag: Tag[V], val schema: Schema[V], val stateTag: Tag[State], val stateSchema: Schema[State])
            extends Flow[Any, N ~ V, S]:
            private[kyo] def erased: LoopNode[Any, String, Any, Any, Any] = this.asInstanceOf[LoopNode[Any, String, Any, Any, Any]]
        end LoopNode

        final case class ForEach[Ctx, N <: String, E, V, S](
            name: N & String,
            concurrency: Int,
            collection: Record[Ctx] => Seq[E] < S,
            body: E => V < S,
            meta: Meta
        )(using val frame: Frame, val tag: Tag[V], val schema: Schema[V]) extends Flow[Any, N ~ Chunk[V], S]:
            private[kyo] def erased: ForEach[Any, String, Any, Any, Any] = this.asInstanceOf[ForEach[Any, String, Any, Any, Any]]
        end ForEach

        final case class Race[In1, Out1, S1, In2, Out2, S2](
            left: Flow[In1, Out1, S1],
            right: Flow[In2, Out2, S2]
        )(using val frame: Frame, val isolate: Isolate[S1 & S2, Abort[FlowException] & Async, S1 & S2])
            extends Flow[In1 & In2, Out1 | Out2, S1 & S2]:
            private[kyo] def erased: Race[Any, Any, Any, Any, Any, Any] = this.asInstanceOf[Race[Any, Any, Any, Any, Any, Any]]
        end Race

        final case class Subflow[In, Ctx, N <: String, In2, Out2, S](
            name: N & String,
            childFlow: Flow[In2, Out2, ?],
            inputMapper: Record[Ctx] => Record[In2] < S,
            meta: Meta
        )(using val frame: Frame) extends Flow[In, Ctx & (N ~ Record[Out2]), S]:
            private[kyo] def erased: Subflow[Any, Any, String, Any, Any, Any] = this.asInstanceOf[Subflow[Any, Any, String, Any, Any, Any]]
        end Subflow

        final case class AndThen[In1, Out1, In2, Out2, S1, S2](
            first: Flow[In1, Out1, S1],
            second: Flow[In2, Out2, S2]
        )(using val frame: Frame) extends Flow[In1 & In2, Out1 & Out2, S1 & S2]

        final case class Zip[In1, Out1, In2, Out2, S1, S2](
            left: Flow[In1, Out1, S1],
            right: Flow[In2, Out2, S2]
        )(using val frame: Frame, val isolate: Isolate[S1 & S2, Abort[FlowException] & Async, S1 & S2])
            extends Flow[In1 & In2, Out1 & Out2, S1 & S2]:
            private[kyo] def erased: Zip[Any, Any, Any, Any, Any, Any] = this.asInstanceOf[Zip[Any, Any, Any, Any, Any, Any]]
        end Zip

        final case class Gather[In, Out, S](flows: Chunk[Flow[?, ?, ?]])(
            using
            val frame: Frame,
            val isolate: Isolate[S, Abort[FlowException] & Async, S]
        ) extends Flow[In, Out, S]:
            private[kyo] def erased: Gather[Any, Any, Any] = this.asInstanceOf[Gather[Any, Any, Any]]
        end Gather

    end internal

end Flow
