package kyo

import Flow.Meta
import Flow.internal.*
import kyo.internal.*
import kyo.kernel.Isolate
import scala.compiletime.error

/** A durable workflow definition.
  *
  * A Flow is a plan, not an execution. You describe what should happen (inputs to wait for, values to compute, side effects to perform,
  * branches to take) and the engine handles persistence, crash recovery, and coordination.
  *
  * The three type parameters track workflow structure at compile time:
  *   - `In` accumulates required inputs. Each `.input[V]("name")` refines `In` via `&` intersection, so the engine knows which signals the
  *     execution needs before it can proceed.
  *   - `Out` accumulates produced values the same way. Each `.output("name")(fn)` adds its field, making it available to downstream steps
  *     via `ctx.name`.
  *   - `S` collects effect types from step bodies (e.g., `Async` if a step makes HTTP calls).
  *
  * Start with `Flow.init("name")`, chain steps, then run it:
  *   - `Flow.runServer(flow)`, an HTTP server with an in-memory store (development)
  *   - `Flow.runServer(store, flow)`, an HTTP server with a durable store (production)
  *   - `Flow.runHandlers(store, flow)`, HTTP handlers to compose with your own server
  *   - `FlowEngine.init(store, flow)`, a programmatic engine without HTTP
  *   - `Flow.runLocal(flow, inputs)`, in-memory and blocking, for tests
  *
  * Every step is persisted before the next begins. If the process crashes, another executor resumes from the last checkpoint. Steps that
  * perform side effects (HTTP calls, database writes) must be idempotent, because they may re-execute on recovery.
  *
  * Error handling uses Kyo's `Abort.recover` inside step bodies. Compensation handlers (`.outputCompensated`) fire in reverse order when a
  * later step fails.
  *
  * #### Status transitions
  *
  * ```text
  *   Running ──→ Completed
  *   Running ──→ Compensating(cause) ──→ Failed | Cancelled
  *   Running ──→ Failed (when nothing was registered to compensate)
  *   Running ──→ Cancelled (when nothing was registered to compensate)
  * ```
  *
  * A cancel is a request rather than an act, and the executor observes it at a node boundary. An execution already in
  * `Compensating(Failure(..))` is running the unwind its own failure started, and it ends as that verdict says: the request is not
  * observed inside an unwind, so the edge out of `Compensating` is decided by the cause it began with.
  *
  * Waiting is not a status. An execution that is waiting for an input or serving out a sleep stays `Running`, and what it is waiting FOR
  * is a wait row per waiting node, because a `race` of an input against a sleep waits for both at once. `executions.describe` answers the
  * plural question; see [[kyo.Flow.Wake]].
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
      * The node records a wait for the value and the execution stays `Running`, carrying a row that says what it is waiting for, until
      * a `signal` delivers it. On replay, the stored value is read and the node is skipped. Field-producing: persists the value under
      * `name` in the store.
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
      * Side effects in `fn` must be idempotent: if the executor crashes after computing but before recording, the step re-executes.
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
      *
      * The handler carries the forward step's own effects (`S2`), because a compensation undoes what the forward step did and therefore
      * needs the same resource: a step that wrote a row needs the database to delete it. The engine's runner discharges both, so a
      * compensating flow is built the same way an uncompensated one is, rather than closing over a live client the forward steps never
      * needed and being unbuildable until that client exists.
      */
    def outputCompensated[N <: String & Singleton, V, S2](
        name: N,
        description: String = "",
        timeout: Duration = Duration.Infinity,
        retry: Maybe[Schedule] = Maybe.empty,
        tags: Seq[String] = Seq.empty
    )(fn: Record[Out] => V < S2)(
        compensate: Record[Out & (N ~ V)] => Unit < (S2 & Async & Abort[FlowException])
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

    /** Like `step`, but registers a compensation handler carrying the step's own effects. See [[outputCompensated]]. */
    def stepCompensated[S2](
        name: String,
        description: String = "",
        timeout: Duration = Duration.Infinity,
        retry: Maybe[Schedule] = Maybe.empty,
        tags: Seq[String] = Seq.empty
    )(fn: Record[Out] => Unit < S2)(
        compensate: Record[Out] => Unit < (S2 & Async & Abort[FlowException])
    )(using Frame): Flow[In, Out, S & S2] =
        AndThen(
            this,
            Step(name, fn, Meta(description, tags, timeout, retry), Maybe(compensate))
        )

    /** Pause the execution for the given duration.
      *
      * The wait is durable: the node records a row carrying the instant it is due and the execution stays `Running`, so if the executor
      * restarts the sleep resumes from where it left off rather than starting over. On replay, the sleep is skipped when its
      * `SleepCompleted` event exists. Event-tracked: no stored field.
      *
      * A durable sleep declares neither `timeout` nor `retry`, because it can honour neither. Bounding a wait is what racing the sleep
      * against the thing being waited for is for, and the only failure a sleeping node can have is the store's, which is charged to the
      * engine's health rather than to a node's budget.
      */
    def sleep(
        name: String,
        duration: Duration,
        description: String = "",
        tags: Seq[String] = Seq.empty
    )(using Frame): Flow[In, Out, S] =
        AndThen(this, Sleep(name, duration, Meta(description, tags)))

    /** Start building a conditional dispatch (branching). Chain `.when(condition)(body)` calls and end with `.otherwise(default)`. */
    def dispatch[V](using Tag[V]): Flow.DispatchStarter[In, Out, S, V] =
        Flow.DispatchStarter[In, Out, S, V](this, Tag[V])

    /** Loop without state: execute `body` repeatedly. The body returns `Loop.continue` to iterate or `Loop.done(value)` to finish. The
      * final value is stored as the named output.
      *
      * #### What this loop does not checkpoint
      *
      * The whole loop is one durable node. Nothing is recorded until the body says stop, so an execution that dies at iteration 40 of 50
      * begins again at iteration 1 when another executor recovers it, and every side effect those 40 iterations performed happens twice.
      * That is deliberate rather than missing: a `loop` runs until its own body decides, so its iteration count is unbounded, and
      * recording each one would write a field and an event per iteration for a loop that may converge over thousands.
      *
      * Two constructs do checkpoint per iteration, and choosing between them and this one is a durability choice rather than a scheduling
      * one:
      *   - [[loopOn]] records every iteration through its schedule, and a schedule of zero delays (`Schedule.fixed(Duration.Zero)`) buys
      *     the checkpointing without any waiting.
      *   - Work that is really N units is a [[foreach]] over those units, which records each item and resumes where it stopped.
      *
      * #### The knobs
      *
      * `timeout` bounds ONE iteration rather than the whole convergence, so a loop that legitimately converges over many iterations can
      * still bound the single slow one. `retry` re-asks one iteration that failed for an accidental reason, leaving the iterations before
      * it recorded as they were.
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
        loop[N, Unit, V, S2](name, (), Meta(description, tags, timeout, retry))((_, ctx) => body(ctx))

    /** Loop with 1 state value: execute `body` repeatedly starting from `init`. The body returns `Loop.continue(newState)` to iterate or
      * `Loop.done(value)` to finish. The final value is stored as the named output.
      *
      * Iterations are not checkpointed. See the stateless [[loop]] for what that costs and for the two constructs that do checkpoint.
      */
    def loop[N <: String & Singleton, A: Tag: Schema, V: Tag: Schema, S2](
        name: N,
        init: A
    )(
        body: (A, Record[Out]) => Loop.Outcome[A, V] < S2
    )(using Frame): Flow[In, Out & (N ~ V), S & S2] =
        loop[N, A, V, S2](name, init, Meta())(body)

    /** Loop with 1 state value under a declared `description`, `timeout`, `retry` and `tags`.
      *
      * The knobs arrive as a [[Meta]] rather than as four defaulted parameters because Scala allows only one overload of a name to define
      * default arguments, and the stateless [[loop]] is that overload. `timeout` and `retry` govern one iteration, as they do there.
      */
    def loop[N <: String & Singleton, A: Tag: Schema, V: Tag: Schema, S2](
        name: N,
        init: A,
        meta: Meta
    )(
        body: (A, Record[Out]) => Loop.Outcome[A, V] < S2
    )(using Frame): Flow[In, Out & (N ~ V), S & S2] =
        AndThen(this, LoopNode[Out, N, A, V, S2](name, body, init, Maybe.empty, meta, Maybe.empty))

    /** Loop with 2 state values: execute `body` repeatedly starting from `init1` and `init2`. The body returns `Loop.continue(newA, newB)`
      * to iterate or `Loop.done(value)` to finish. The final value is stored as the named output.
      *
      * Iterations are not checkpointed. See the stateless [[loop]] for what that costs and for the two constructs that do checkpoint.
      */
    def loop[N <: String & Singleton, A: Tag: Schema, B: Tag: Schema, V: Tag: Schema, S2](
        name: N,
        init1: A,
        init2: B
    )(
        body: (A, B, Record[Out]) => Loop.Outcome2[A, B, V] < S2
    )(using Frame, Tag[(A, B)], Schema[(A, B)]): Flow[In, Out & (N ~ V), S & S2] =
        loop[N, A, B, V, S2](name, init1, init2, Meta())(body)

    /** Loop with 2 state values under a declared `description`, `timeout`, `retry` and `tags`. See the one-state [[loop]] overload for why
      * the knobs arrive as a [[Meta]].
      */
    def loop[N <: String & Singleton, A: Tag: Schema, B: Tag: Schema, V: Tag: Schema, S2](
        name: N,
        init1: A,
        init2: B,
        meta: Meta
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
                meta,
                Maybe.empty
            )
        )

    /** Like the stateless [[loop]], but registers a compensation handler that runs (in reverse order) if a later step fails.
      *
      * The handler is NODE level and receives the record the loop's own value is in, which is the value the loop converged on: a loop that
      * booked a shipment per iteration undoes the booking it ended with. It is pushed only once the loop produced that value, so a loop
      * that was cancelled between iterations has nothing to undo and registers nothing. See [[outputCompensated]] for the effect row the
      * handler carries.
      */
    def loopCompensated[N <: String & Singleton, V: Tag: Schema, S2](
        name: N,
        description: String = "",
        timeout: Duration = Duration.Infinity,
        retry: Maybe[Schedule] = Maybe.empty,
        tags: Seq[String] = Seq.empty
    )(
        body: Record[Out] => Loop.Outcome[Unit, V] < S2
    )(
        compensate: Record[Out & (N ~ V)] => Unit < (S2 & Async & Abort[FlowException])
    )(using Frame, Tag[Unit], Schema[Unit]): Flow[In, Out & (N ~ V), S & S2] =
        loopCompensated[N, Unit, V, S2](name, (), Meta(description, tags, timeout, retry))((_, ctx) => body(ctx))(compensate)

    /** Like the one-state [[loop]], but registers a node-level compensation handler. See [[loopCompensated]]. */
    def loopCompensated[N <: String & Singleton, A: Tag: Schema, V: Tag: Schema, S2](
        name: N,
        init: A,
        meta: Meta
    )(
        body: (A, Record[Out]) => Loop.Outcome[A, V] < S2
    )(
        compensate: Record[Out & (N ~ V)] => Unit < (S2 & Async & Abort[FlowException])
    )(using Frame): Flow[In, Out & (N ~ V), S & S2] =
        AndThen(this, LoopNode[Out, N, A, V, S2](name, body, init, Maybe.empty, meta, Maybe(handlerOf(compensate))))

    /** Like the two-state [[loop]], but registers a node-level compensation handler. See [[loopCompensated]]. */
    def loopCompensated[N <: String & Singleton, A: Tag: Schema, B: Tag: Schema, V: Tag: Schema, S2](
        name: N,
        init1: A,
        init2: B,
        meta: Meta
    )(
        body: (A, B, Record[Out]) => Loop.Outcome2[A, B, V] < S2
    )(
        compensate: Record[Out & (N ~ V)] => Unit < (S2 & Async & Abort[FlowException])
    )(using Frame, Tag[(A, B)], Schema[(A, B)]): Flow[In, Out & (N ~ V), S & S2] =
        AndThen(
            this,
            LoopNode[Out, N, (A, B), V, S2](
                name,
                (state, ctx) => body(state._1, state._2, ctx),
                (init1, init2),
                Maybe.empty,
                meta,
                Maybe(handlerOf(compensate))
            )
        )

    /** Process each element of a collection and store all results as a `Chunk[V]` output, in collection order.
      *
      * #### The item is the durable unit
      *
      * Each item's result is recorded under the item's own durable name as it completes, `charges~0`, `charges~1` and so on, and the
      * node's own `Chunk[V]` is assembled from them and written when the last one lands. An execution that dies with 40 of 50 items
      * recorded resumes at item 41: the items already recorded are read back instead of run again, so a fan-out that charges N cards
      * charges each of them once however many attempts recovery takes. The item that was in flight when the process died is the one
      * exception, and it is the same window every step has: it re-runs, which is what its body is required to be idempotent for. What
      * this costs is N field writes for a fan-out of N, which is the price of the guarantee.
      *
      * #### What the collection owes
      *
      * The collection must be a deterministic function of the record. An attempt that re-enters a fan-out recomputes it wherever it
      * still needs the items, because that is what names the items the recorded results belong to, and the count the fan-out started
      * with is recorded beside them: a recomputed collection whose size disagrees with that count fails the node with
      * [[kyo.FlowNondeterministicCollectionException]], naming the node, the count it recorded and the size it recomputed. It is the
      * obligation a step body already carries one level down, and here it is checked rather than assumed.
      *
      * #### The knobs
      *
      * `timeout` bounds ONE item and `retry` re-asks one item that failed for an accidental reason, leaving the items already recorded
      * exactly as they are. A bound over the whole fan-out cannot be sized for the single slow item, which is the one thing a caller
      * bounds.
      *
      * `concurrency` bounds how many items are in flight at once. It defaults to unbounded, so the items of a fan-out run in parallel
      * unless the caller says otherwise; a value of 1 processes them one at a time. Results are ordered by the collection rather than by
      * completion, whatever the bound is.
      *
      * Items above a bound of 1 run in their own fibers. `Sync`, `Async` and `Abort` bodies need nothing for that, and neither does a
      * body reading a context effect such as `Env`, which the kernel carries into a fiber. A body carrying an effect whose state has to
      * be merged back, `Var` or `Emit`, has no per-item state to merge here and should declare `concurrency = 1`.
      *
      * The node persists the whole `Chunk[V]`, under the evidence for `Chunk[V]` rather than for `V`, so `store.getField[Chunk[V]]` reads
      * back what the fan-out produced.
      */
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
    )(using
        Frame,
        Tag[V],
        Schema[V],
        Tag[Chunk[V]]
    ): Flow[In, Out & (N ~ Chunk[V]), S & S2] =
        // The evidence is captured HERE, where `V` is still concrete. The node is erased before the interpreter sees it, and
        // neither `Tag[Chunk[V]]` nor the item's own `Tag[V]` can be rebuilt from a tag that has been erased to `Tag[Any]`.
        AndThen(this, ForEach(name, concurrency, collection, body, Meta(description, tags, timeout, retry), Maybe.empty))

    /** Like [[foreach]], but registers a compensation handler per ITEM, which runs (in reverse order) if a later step fails.
      *
      * The handler receives an item and what that item produced, and it is registered as each item completes, so a fan-out that failed
      * part-way unwinds over exactly the items that ran: a run that charged three of five cards refunds three. That is why the handler
      * belongs to the item rather than to the node. A node-level handler receives the node's stored value, and a fan-out's value exists
      * only once every item has landed, so it would have nothing to hand a handler at the one moment unwinding matters.
      *
      * A resumed fan-out registers a handler for the items it read back as well as for the ones it ran, because an item recorded by an
      * earlier attempt did its work just as much. The one case that registers nothing is a detected nondeterministic collection: a
      * fan-out whose recomputed collection changed size cannot say which item produced which recorded result, so it fails naming the
      * mismatch instead of pairing them, and the items that ran are reported by that failure rather than undone by a guess.
      *
      * Handlers run in reverse order of registration, as every compensation does, and a fan-out's items register in the order they
      * complete rather than the order they were listed. So the order among ONE fan-out's items is a scheduling fact and not something
      * to depend on, which is the same independence between items that makes a per-item handler the right shape in the first place. A
      * fan-out whose items must be undone in a particular order is a batch, and its handler belongs on the node that owns the batch.
      *
      * See [[outputCompensated]] for the effect row the handler carries and why it is the body's own.
      */
    def foreachCompensated[N <: String & Singleton, E, V, S2](
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
    )(
        compensate: (E, V) => Unit < (S2 & Async & Abort[FlowException])
    )(using
        Frame,
        Tag[V],
        Schema[V],
        Tag[Chunk[V]]
    ): Flow[In, Out & (N ~ Chunk[V]), S & S2] =
        AndThen(
            this,
            ForEach(name, concurrency, collection, body, Meta(description, tags, timeout, retry), Maybe(itemHandlerOf(compensate)))
        )

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
      *
      * #### What a schedule buys beyond waiting
      *
      * Every iteration is checkpointed: the state a continuing iteration carries and the value a finishing one produces are both recorded
      * under the iteration's own durable name before the loop moves on. An execution that dies mid-loop resumes at the iteration after the
      * last one recorded, with that iteration's state, rather than starting over as the unscheduled [[loop]] does. A schedule of zero
      * delays (`Schedule.fixed(Duration.Zero)`) is how to get the checkpointing with no waiting at all.
      *
      * #### When the schedule runs out
      *
      * A schedule with no delay left ends the execution as `Failed`, naming the loop and the iterations it ran. A flow declaring `N ~ V`
      * promises a `V`, and a loop whose schedule is spent has none: the last state it carried is the loop's own state type, and nothing is
      * written under the loop's name. Give the schedule more room than the body needs, or make the body return `Loop.done`.
      *
      * #### The knobs
      *
      * `timeout` bounds ONE iteration and `retry` re-asks one iteration that failed for an accidental reason, both of them the iteration's
      * own body rather than the loop's whole convergence.
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
        loopOn[N, Unit, V, S2](name, schedule, (), Meta(description, tags, timeout, retry))((_, ctx) => body(ctx))

    /** Loop on a schedule with 1 state value. Between iterations, the flow durably sleeps for the delay from `Schedule.next`, and the state
      * the next iteration starts from is recorded with the iteration that produced it.
      */
    def loopOn[N <: String & Singleton, A: Tag: Schema, V: Tag: Schema, S2](
        name: N,
        schedule: Schedule,
        init: A
    )(
        body: (A, Record[Out]) => Loop.Outcome[A, V] < S2
    )(using Frame): Flow[In, Out & (N ~ V), S & S2] =
        loopOn[N, A, V, S2](name, schedule, init, Meta())(body)

    /** Loop on a schedule with 1 state value under a declared `description`, `timeout`, `retry` and `tags`.
      *
      * The knobs arrive as a [[Meta]] rather than as four defaulted parameters because Scala allows only one overload of a name to define
      * default arguments, and the stateless [[loopOn]] is that overload.
      */
    def loopOn[N <: String & Singleton, A: Tag: Schema, V: Tag: Schema, S2](
        name: N,
        schedule: Schedule,
        init: A,
        meta: Meta
    )(
        body: (A, Record[Out]) => Loop.Outcome[A, V] < S2
    )(using Frame): Flow[In, Out & (N ~ V), S & S2] =
        AndThen(this, LoopNode[Out, N, A, V, S2](name, body, init, Maybe(schedule), meta, Maybe.empty))

    /** Loop on a schedule with 2 state values. Between iterations, the flow durably sleeps for the delay from `Schedule.next`. */
    def loopOn[N <: String & Singleton, A: Tag: Schema, B: Tag: Schema, V: Tag: Schema, S2](
        name: N,
        schedule: Schedule,
        init1: A,
        init2: B
    )(
        body: (A, B, Record[Out]) => Loop.Outcome2[A, B, V] < S2
    )(using Frame, Tag[(A, B)], Schema[(A, B)]): Flow[In, Out & (N ~ V), S & S2] =
        loopOn[N, A, B, V, S2](name, schedule, init1, init2, Meta())(body)

    /** Loop on a schedule with 2 state values under a declared `description`, `timeout`, `retry` and `tags`. See the one-state [[loopOn]]
      * overload for why the knobs arrive as a [[Meta]].
      */
    def loopOn[N <: String & Singleton, A: Tag: Schema, B: Tag: Schema, V: Tag: Schema, S2](
        name: N,
        schedule: Schedule,
        init1: A,
        init2: B,
        meta: Meta
    )(
        body: (A, B, Record[Out]) => Loop.Outcome2[A, B, V] < S2
    )(using Frame, Tag[(A, B)], Schema[(A, B)]): Flow[In, Out & (N ~ V), S & S2] =
        AndThen(
            this,
            LoopNode[Out, N, (A, B), V, S2](
                name,
                (state, ctx) => body(state._1, state._2, ctx),
                (init1, init2),
                Maybe(schedule),
                meta,
                Maybe.empty
            )
        )

    /** Like the stateless [[loopOn]], but registers a node-level compensation handler. See [[loopCompensated]]. */
    def loopOnCompensated[N <: String & Singleton, V: Tag: Schema, S2](
        name: N,
        schedule: Schedule,
        description: String = "",
        timeout: Duration = Duration.Infinity,
        retry: Maybe[Schedule] = Maybe.empty,
        tags: Seq[String] = Seq.empty
    )(
        body: Record[Out] => Loop.Outcome[Unit, V] < S2
    )(
        compensate: Record[Out & (N ~ V)] => Unit < (S2 & Async & Abort[FlowException])
    )(using Frame, Tag[Unit], Schema[Unit]): Flow[In, Out & (N ~ V), S & S2] =
        loopOnCompensated[N, Unit, V, S2](name, schedule, (), Meta(description, tags, timeout, retry))((_, ctx) => body(ctx))(compensate)

    /** Like the one-state [[loopOn]], but registers a node-level compensation handler. See [[loopCompensated]]. */
    def loopOnCompensated[N <: String & Singleton, A: Tag: Schema, V: Tag: Schema, S2](
        name: N,
        schedule: Schedule,
        init: A,
        meta: Meta
    )(
        body: (A, Record[Out]) => Loop.Outcome[A, V] < S2
    )(
        compensate: Record[Out & (N ~ V)] => Unit < (S2 & Async & Abort[FlowException])
    )(using Frame): Flow[In, Out & (N ~ V), S & S2] =
        AndThen(this, LoopNode[Out, N, A, V, S2](name, body, init, Maybe(schedule), meta, Maybe(handlerOf(compensate))))

    /** Like the two-state [[loopOn]], but registers a node-level compensation handler. See [[loopCompensated]]. */
    def loopOnCompensated[N <: String & Singleton, A: Tag: Schema, B: Tag: Schema, V: Tag: Schema, S2](
        name: N,
        schedule: Schedule,
        init1: A,
        init2: B,
        meta: Meta
    )(
        body: (A, B, Record[Out]) => Loop.Outcome2[A, B, V] < S2
    )(
        compensate: Record[Out & (N ~ V)] => Unit < (S2 & Async & Abort[FlowException])
    )(using Frame, Tag[(A, B)], Schema[(A, B)]): Flow[In, Out & (N ~ V), S & S2] =
        AndThen(
            this,
            LoopNode[Out, N, (A, B), V, S2](
                name,
                (state, ctx) => body(state._1, state._2, ctx),
                (init1, init2),
                Maybe(schedule),
                meta,
                Maybe(handlerOf(compensate))
            )
        )

    // --- Composition ---

    /** Execute two flows in parallel and merge their outputs. Both must complete.
      *
      * Each branch runs in its own fiber, and the `Isolate` is what carries a custom effect row across that boundary: it is
      * captured here, at the only site where the combined row is still visible, each branch runs against the captured state, and
      * completed branches are restored into the caller's context. Pure and `Async` rows derive an isolate automatically. A
      * stateful row needs one in scope, such as `Var.isolate.update`, and a row no isolate can be built for does not compose,
      * deliberately: refusing at the call site is what keeps a branch from suspending inside a fiber that cannot handle its
      * effects. A body that needs such an effect can handle it inside its own step instead.
      */
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
      * ```scala
      * val order = Flow.init("order-processing", description = "Handles orders")
      *     .input[Order]("order")
      *     .output("total")(ctx => ctx.order.qty * ctx.order.price)
      * ```
      */
    def init(
        name: String,
        description: String = "",
        version: String = "",
        tags: Seq[String] = Seq.empty
    )(using Frame): Flow[Any, Any, Any] =
        internal.Init(name, Meta(description = description, version = version, tags = tags))

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
      *
      * ```scala
      * Flow.runServer(orderFlow, shippingFlow)
      * ```
      */
    def runServer(flows: Flow[?, ?, ?]*)(using
        Frame
    ): HttpServer < (Async & Scope & Abort[HttpBindException | FlowDefinitionException | FlowStoreException]) =
        FlowStore.initMemory.map(store => runServer(store, flows*))

    def runServer[S](flows: Flow[?, ?, S]*)(
        runner: [V] => V < S => V < (Async & Scope & Abort[FlowException])
    )(using Frame): HttpServer < (Async & Scope & Abort[HttpBindException | FlowDefinitionException | FlowStoreException]) =
        FlowStore.initMemory.map(store => runServer(store, flows*)(runner))

    /** Start an HTTP server backed by a specific store. */
    def runServer(store: FlowStore, flows: Flow[?, ?, ?]*)(using
        Frame
    ): HttpServer < (Async & Scope & Abort[HttpBindException | FlowDefinitionException | FlowStoreException]) =
        runHandlers(store, flows*).map(h => HttpServer.init(h.toSeq*))

    def runServer[S](store: FlowStore, flows: Flow[?, ?, S]*)(
        runner: [V] => V < S => V < (Async & Scope & Abort[FlowException])
    )(using Frame): HttpServer < (Async & Scope & Abort[HttpBindException | FlowDefinitionException | FlowStoreException]) =
        runHandlers(store, flows*)(runner).map(h => HttpServer.init(h.toSeq*))

    /** Get HTTP handlers without starting a server. Compose with your own endpoints. */
    def runHandlers(store: FlowStore, flows: Flow[?, ?, ?]*)(using
        Frame
    ): Chunk[HttpHandler[?, ?, ?]] < (Async & Scope & Abort[FlowDefinitionException | FlowStoreException]) =
        FlowEngine.initImpl(store, flows = flows).map(engine => kyo.internal.FlowApi.handlers(engine))

    def runHandlers[S](store: FlowStore, flows: Flow[?, ?, S]*)(
        runner: [V] => V < S => V < (Async & Scope & Abort[FlowException])
    )(using Frame): Chunk[HttpHandler[?, ?, ?]] < (Async & Scope & Abort[FlowDefinitionException | FlowStoreException]) =
        FlowEngine.initImpl(store, flows = flows, runner = Maybe(FlowRunner[S](runner)))
            .map(engine => kyo.internal.FlowApi.handlers(engine))

    /** Get HTTP handlers from an existing engine.
      *
      * Answers a pending value, like its two siblings above, rather than a bare `Chunk`. The three are written next to each other in the
      * same for-comprehension, and a bare `Chunk` binds there over `Chunk`'s own `flatMap`: `handlers <- Flow.runHandlers(engine)` would
      * give one handler per iteration and run the rest of the comprehension once per handler, quietly changing what the program does. A
      * type error catches that only when the body's types disagree, which is not the case for a body generic in its element.
      */
    def runHandlers(engine: FlowEngine)(using Frame): Chunk[HttpHandler[?, ?, ?]] < Any =
        kyo.internal.FlowApi.handlers(engine)

    /** Execute a flow locally with an in-memory store. Blocks until the flow completes and returns the full output record.
      *
      * Useful for testing and simple scripts. Pre-populates inputs from the provided record. For durable production execution, use
      * `FlowEngine.init` instead.
      */
    def runLocal[In, Out](
        flow: Flow[In, Out, ?],
        inputs: Record[In] = Record.empty
    )(using Frame): Record[In & Out] < (Async & Scope & Abort[FlowException | FlowStoreException]) =
        runLocalImpl(flow, inputs, Maybe.empty)

    /** Execute a flow locally with a runner that handles custom effects.
      *
      * The runner wraps the entire flow execution, providing effect handlers that step bodies need. The compiler infers `S` from the flow,
      * then requires the runner to handle it.
      *
      * ```scala
      * Flow.runLocal(myFlow, "x" ~ 42)([v] => c => Env.run(config)(c))
      * ```
      */
    def runLocal[In, Out, S](
        flow: Flow[In, Out, S],
        inputs: Record[In]
    )(runner: [V] => V < S => V < (Async & Scope & Abort[FlowException]))(using
        Frame
    ): Record[In & Out] < (Async & Scope & Abort[FlowException | FlowStoreException]) =
        runLocalImpl(flow, inputs, Maybe(FlowRunner[S](runner)))

    private def runLocalImpl[In, Out](
        flow: Flow[In, Out, ?],
        inputs: Record[In],
        runner: Maybe[FlowRunner]
    )(using Frame): Record[In & Out] < (Async & Scope & Abort[FlowException | FlowStoreException]) =
        FlowStore.initMemory.map { store =>
            FlowEngine.initImpl(store, FlowEngine.Config(workerCount = 1, pollTimeout = 100.millis)).map { engine =>
                val wfId = Flow.Id.Workflow("_local")
                engine.registerImpl(wfId, flow, runner).map { _ =>
                    engine.workflows.start(wfId, inputs.asInstanceOf[Record[Any]]).map { handle =>
                        val eid = handle.executionId
                        def await: Record[In & Out] < (Async & Abort[FlowException | FlowStoreException]) =
                            Async.sleep(10.millis).map { _ =>
                                store.getExecution(eid).map {
                                    case Present(state) if state.status == Flow.Status.Completed =>
                                        store.getAllFields(eid).map { fields =>
                                            val schema = WorkflowSchema.of(flow)
                                            val decoded = fields.foldLeft(Dict.empty[String, Any]) { (acc, name, fd) =>
                                                schema.fromStoreName(name) match
                                                    case Present(entry) =>
                                                        entry.decode(fd) match
                                                            case Present(v) => acc.update(name, v)
                                                            case _          => acc
                                                    case _ => acc
                                            }
                                            // A subflow's own field is promised by the returned record's type and persisted by
                                            // nobody: the node writes nothing under its name and the child's nodes write under its
                                            // path, which is what makes two instances of one subflow two sets of fields. So the
                                            // reader that hands back a `Record[In & Out]` is the one that puts them together, and
                                            // it asks the schema, which carries an assembly entry per subflow instance.
                                            val assembled = schema.subflows.foldLeft(decoded) { (acc, subflow) =>
                                                acc.update(subflow.name, subflow.assemble(decoded))
                                            }
                                            new Record[In & Out](assembled)
                                        }
                                    case Present(state) =>
                                        state.status match
                                            case Flow.Status.Failed(err, _) =>
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

    /** Race two flows: the first to complete wins, the other is cancelled. Output type is the union of both flows' outputs.
      *
      * Each branch runs in its own fiber, and the `Isolate` is what carries a custom effect row across that boundary: it is
      * captured here, at the only site where the combined row is still visible, and the winning branch's effects are restored
      * into the caller's context while the cancelled branch's are discarded with it. Pure and `Async` rows derive an isolate
      * automatically; a stateful row needs one in scope, such as `Var.isolate.update`, and a row no isolate can be built for
      * does not compose, deliberately. A body that needs such an effect can handle it inside its own step instead.
      */
    def race[In1, Out1, S1, In2, Out2, S2](left: Flow[In1, Out1, S1], right: Flow[In2, Out2, S2])(
        using
        Frame,
        Isolate[S1 & S2, Abort[FlowException] & Async, S1 & S2]
    ): Flow[In1 & In2, Out1 | Out2, S1 & S2] =
        internal.Race(left, right)

    /** Execute multiple flows in parallel and merge all their outputs. All branches must complete.
      *
      * The branches run in their own fibers, and the `Isolate` carries a custom effect row across that boundary the same way
      * [[zip]]'s does: captured here, restored per completed branch. Pure and `Async` rows derive an isolate automatically; a
      * stateful row needs one in scope, and a row no isolate can be built for does not compose, deliberately. A body that needs
      * such an effect can handle it inside its own step instead.
      */
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
      *   Per-attempt timeout. If a single attempt exceeds this duration, it fails with a timeout error. It bounds this executor's own
      *   attempt only: a node whose previous executor died mid-step is re-executed by the executor that reclaims the lease, never waited
      *   on, because an execution is handed out only once no other executor holds a live lease on it.
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

    /** What a dispatch's branch tells a walk about itself, carried to [[kyo.internal.FlowVisitor.onDispatch]] and nowhere else. */
    private[kyo] case class BranchInfo(name: String, frame: Frame, meta: Meta)

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

    // --- Wake ---

    /** What a waiting node is waiting FOR, as the store records it on that node's wait row.
      *
      * A row exists because a node is waiting for something, so the only cases are things a node can wait for. There is no case for "the
      * lifecycle is runnable" and none for "the execution is over": those are the lifecycle status, which readiness reads directly, and a
      * row could never carry one, leaving a predicate somewhere to agree that it does not.
      *
      * Both conditions are judged by the STORE against its own state, never by the caller: the store is the clock for a sleep, and it is
      * the one that holds the fields for an input.
      *
      * @see
      *   [[kyo.FlowStore.recordWait]] which writes a row, and [[kyo.FlowStore.ExecutionState.waits]] which reads them back
      */
    enum Wake derives CanEqual, Schema:

        /** A durable sleep, satisfied once the store's clock has passed `instant`. */
        case At(instant: Instant)

        /** A wait for an input, satisfied once the field `name` is present on the execution. */
        case OnField(name: String)
    end Wake

    // --- Status ---

    /** Why an execution is unwinding, carried by [[Status.Compensating]] and by [[Event.CompensationStarted]].
      *
      * An unwind that crashes has to be re-enterable, and re-entering means knowing what it was unwinding FROM. Without that the only
      * recovery left is to replay forward and hope the failing step fails again, which for a transient failure means the execution
      * continues and COMPLETES with some of its compensations already run and the work they undid still undone.
      *
      * Two arms rather than an empty-message convention on one: a cancellation and a failure with no message are different facts, and the
      * terminal status the unwind ends with is a total function of this value.
      */
    enum Cause derives CanEqual, Schema:

        /** The forward pass failed. `kind` is [[FlowException.kind]] where the engine had one, as on [[Status.Failed]]. */
        case Failure(error: String, kind: Maybe[String] = Maybe.empty)

        /** Somebody asked for the execution to be cancelled. */
        case Cancellation
    end Cause

    /** The persisted LIFECYCLE of an execution, and nothing else.
      *
      * `Running` spans working and waiting: what an execution is waiting FOR is plural (a `race` of an input against a sleep waits for
      * both at once) and lives on the execution's wait rows, where a reader finds it through [[kyo.FlowEngine.executions.describe]].
      * A single token could only answer one of two simultaneous waits, and any tiebreak hides the other.
      */
    enum Status derives CanEqual, Schema:
        case Running
        case Completed

        /** The execution failed. `kind` names what kind of failure it was, from [[FlowException.kind]] where the engine had one.
          *
          * The persisted shape of a failure is what an operator queries, and a message alone makes "how many failed for payment reasons"
          * a LIKE over free text. A step's own [[FlowDomainException]] therefore lands its class name here, next to the message.
          */
        case Failed(error: String, kind: Maybe[String] = Maybe.empty)

        /** The execution is running its compensation handlers, undoing what `cause` interrupted. */
        case Compensating(cause: Cause)
        case Cancelled

        def show: String = this match
            case Running          => "running"
            case Completed        => "completed"
            case Failed(error, _) => s"failed:$error"
            case Compensating(_)  => "compensating"
            case Cancelled        => "cancelled"

        def isTerminal: Boolean = this match
            case Completed | Failed(_, _) | Cancelled => true
            case _                                    => false
    end Status

    // --- Event ---

    enum EventKind derives Schema:
        case Created, StepStarted, StepCompleted, StepFailed, StepRetried, StepTimedOut,
            InputWaiting, InputReceived, InputDischarged, InputSupplied, SleepStarted, SleepCompleted,
            BranchChosen, ExecutionResumed, ExecutionClaimed, ExecutionReleased,
            Completed, Failed, CompensationStarted, CompensationCompleted, CompensationFailed, NodeCompensated, Cancelled
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

        /** A dispatch entered a branch, recorded before the branch ran. See [[kyo.internal.FlowInterpreter.onChoice]].
          *
          * Replay reads the choice from the field the same write left behind, never from this event: history is what an operator reads
          * to see which way a row went, and deciding control flow from it would make an execution's future depend on how much of its
          * history a store chose to return.
          */
        case BranchChosen(
            flowId: Flow.Id.Workflow,
            executionId: Flow.Id.Execution,
            dispatchName: String,
            branchName: String,
            timestamp: Instant
        )
        case Completed(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, timestamp: Instant)
        case Failed(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, error: String, timestamp: Instant)

        /** The unwind began, carrying what it is unwinding from.
          *
          * The cause is on the event AND on [[Status.Compensating]], because the two have different readers: replay decides control flow
          * from the durable status, and an operator reads the history. A cause in only one of them leaves the other unable to answer.
          */
        case CompensationStarted(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, cause: Cause, timestamp: Instant)
        case CompensationCompleted(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, timestamp: Instant)
        case CompensationFailed(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, error: String, timestamp: Instant)

        /** One node's compensation handler ran to completion.
          *
          * Per NODE, where [[CompensationStarted]], [[CompensationCompleted]] and [[CompensationFailed]] are per UNWIND. An unwind that
          * is interrupted part-way leaves some handlers run and some not, and only a per-node record can tell them apart, which is what
          * a recovered execution reads to re-run exactly the handlers that never landed.
          */
        case NodeCompensated(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, nodeName: String, timestamp: Instant)

        /** One node's work ended in a failure the attempt did not recover from, recorded under the node's own name.
          *
          * **The record exists so a reader does not have to guess which node failed.** The lifecycle carries the message and the
          * kind but no name, and deriving the name from what is missing (a `StepStarted` with no `StepCompleted`) names the wrong node
          * for an interrupted step, for a branch abandoned when its sibling failed, and for the earlier attempt of a step that was
          * re-run after a crash. A progress view built on that guess reports a failure on a node that did not fail, which is the
          * instrument lying about the thing it exists to measure.
          *
          * Written for a failure of the WORK: a declared [[FlowException]] carries its `kind`, and an escaped throwable carries none,
          * because the class name of something that escaped is not a category anybody declared. A store failure writes nothing here,
          * since it is not a verdict on the step and the execution stays exactly as claimable as it was, and neither does an interrupt,
          * since an attempt that was told to stop has reached no verdict at all.
          *
          * A later `StepCompleted` on the same name supersedes it: a step that failed on one attempt and succeeded on the next is
          * completed, and the reader takes the last word rather than the first.
          *
          * `errorKind` rather than `kind`, which every event already answers with its own [[EventKind]]. It is the same value
          * [[Status.Failed.kind]] carries, so "which node failed, and for what kind of reason" is one read of the history.
          */
        case StepFailed(
            flowId: Flow.Id.Workflow,
            executionId: Flow.Id.Execution,
            stepName: String,
            error: String,
            errorKind: Maybe[String],
            timestamp: Instant
        )
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

        /** An input node found its value and went on, which is the transition that clears the wait row it had written.
          *
          * The node's own completion, and distinct from [[InputReceived]], which is the value ARRIVING through `signal` on the other
          * side. A delivery and a consumption are two facts, and one event cannot stand for both: the value can arrive long before an
          * executor replays far enough to use it, and it can arrive for a node the execution never reaches.
          */
        case InputDischarged(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, inputName: String, timestamp: Instant)

        /** A subflow's input mapper supplied a child input's value, recorded at entry before the child's first node ran.
          *
          * The third way a value reaches an input, and the three stay distinguishable in history: a start seed leaves the field and
          * no input event at all, a `signal` leaves [[InputWaiting]], [[InputReceived]] and [[InputDischarged]], and a mapper leaves
          * exactly one of these. `inputName` is the child input's durable path (`review~amount`), which is where the value was
          * written; the field and this event are one transition, so a reader of the history and replay reading the field never
          * disagree about whether the child ran against a recorded value.
          *
          * Not [[InputDischarged]], which is the node's own consumption of a value it waited for. A mapper-fed input never parks, so
          * it has no wait row to clear, and writing a discharge here would put a consumption record before the node was reached.
          */
        case InputSupplied(flowId: Flow.Id.Workflow, executionId: Flow.Id.Execution, inputName: String, timestamp: Instant)

        def kind: EventKind = this match
            case _: Created               => EventKind.Created
            case _: StepStarted           => EventKind.StepStarted
            case _: StepCompleted         => EventKind.StepCompleted
            case _: InputWaiting          => EventKind.InputWaiting
            case _: InputReceived         => EventKind.InputReceived
            case _: SleepStarted          => EventKind.SleepStarted
            case _: SleepCompleted        => EventKind.SleepCompleted
            case _: BranchChosen          => EventKind.BranchChosen
            case _: Completed             => EventKind.Completed
            case _: Failed                => EventKind.Failed
            case _: CompensationStarted   => EventKind.CompensationStarted
            case _: CompensationCompleted => EventKind.CompensationCompleted
            case _: CompensationFailed    => EventKind.CompensationFailed
            case _: NodeCompensated       => EventKind.NodeCompensated
            case _: StepFailed            => EventKind.StepFailed
            case _: StepRetried           => EventKind.StepRetried
            case _: StepTimedOut          => EventKind.StepTimedOut
            case _: ExecutionResumed      => EventKind.ExecutionResumed
            case _: ExecutionClaimed      => EventKind.ExecutionClaimed
            case _: ExecutionReleased     => EventKind.ExecutionReleased
            case _: Cancelled             => EventKind.Cancelled
            case _: InputDischarged       => EventKind.InputDischarged
            case _: InputSupplied         => EventKind.InputSupplied

        def detail: String = this match
            case StepStarted(_, _, name, _, _)           => name
            case StepCompleted(_, _, name, _)            => name
            case InputWaiting(_, _, name, _)             => name
            case InputReceived(_, _, name, _)            => name
            case InputDischarged(_, _, name, _)          => name
            case InputSupplied(_, _, name, _)            => name
            case SleepStarted(_, _, name, _, _)          => name
            case SleepCompleted(_, _, name, _)           => name
            case NodeCompensated(_, _, name, _)          => name
            case BranchChosen(_, _, name, branch, _)     => s"$name ($branch)"
            case Failed(_, _, error, _)                  => error
            case CompensationFailed(_, _, error, _)      => error
            case StepFailed(_, _, name, error, _, _)     => s"$name ($error)"
            case StepRetried(_, _, name, error, n, _, _) => s"$name (attempt $n: $error)"
            case StepTimedOut(_, _, name, _, _)          => name
            case _                                       => ""
    end Event

    // --- Dispatch helpers ---

    /** Builder for conditional dispatch (branching). Chain `.when(cond)(body)` to add branches, then `.otherwise(body)` to complete.
      *
      * ```scala
      * flow.dispatch[String]("decision")
      *     .when(ctx => ctx.amount > 1000, name = "review")(ctx => "needs review")
      *     .when(ctx => ctx.amount > 100, name = "auto")(ctx => "auto-approved")
      *     .otherwise(ctx => "instant", name = "default")
      * ```
      */
    final class PartialDispatch[In, Out, Sf, Sc, N <: String & Singleton, V] private[kyo] (
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
            build(body, name, Maybe.empty)(using frame, schema)

        /** Like [[otherwise]], but registers a compensation handler for the dispatch as a whole.
          *
          * The handler is NODE level, and on a dispatch that is exactly right: one branch runs and produces one value, so the handler
          * receives the record that value is in and undoes whatever the branch that ran did. The branch that did not run wrote nothing,
          * so there is no per-branch question to answer. A dispatch branch that charges a premium fee is otherwise as unrecoverable as
          * a step that charges one without a handler.
          *
          * Handlers run in reverse order when a later step fails, and only on failure, never on suspension. See [[outputCompensated]]
          * for the effect row the handler carries.
          */
        def otherwiseCompensated[S2](body: Record[Out] => V < S2, name: String, description: String = "")(
            compensate: Record[Out & (N ~ V)] => Unit < (Sc & S2 & Async & Abort[FlowException])
        )(
            using
            frame: Frame,
            schema: Schema[V]
        ): Flow[In, Out & (N ~ V), Sf & Sc & S2] =
            build(body, name, Maybe(internal.handlerOf(compensate)))(using frame, schema)

        private def build[S2](
            body: Record[Out] => V < S2,
            name: String,
            compensate: Maybe[internal.Handler[S2 & Sc]]
        )(using frame: Frame, schema: Schema[V]): Flow[In, Out & (N ~ V), Sf & Sc & S2] =
            val dispatch = internal.Dispatch[Out, N, V, S2, Sc](
                this.name,
                branches,
                name,
                body,
                frame,
                meta,
                compensate
            )(using dispatchFrame, vtag, schema)
            internal.AndThen(flow, dispatch)(using dispatchFrame)
        end build

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

    /** An exception's message, or its rendering when it carries none, so a durable field never holds a null. */
    private def messageOf(error: Throwable): String =
        Maybe(error.getMessage).getOrElse(error.toString)

    /** Interprets one attempt at a flow.
      *
      * @param completedEvents
      *   the durable names this execution has already completed, so a replay re-registers their handlers and runs nothing again
      * @param compensatedNodes
      *   the nodes whose compensation handler has already run, read from the same history. An unwind that was interrupted part-way left
      *   some handlers run and some not, and re-running the ones that landed is what an idempotency contract cannot save a caller from:
      *   a refund issued twice is two refunds.
      * @param resumeCause
      *   the verdict an interrupted attempt recorded when it entered its unwind, present exactly when this attempt is resuming one. The
      *   forward pass then re-registers handlers and stops, and this cause is what the unwind re-raises. Without it a resumed
      *   `Compensating` execution can only replay forward and hope the failing step fails again, and a transient failure that succeeds
      *   the second time COMPLETES an execution whose compensations have half run, which is the one transition the module's own
      *   documentation says cannot happen.
      */
    private[kyo] def run[In, Out, S <: Sync](
        flow: Flow[In, Out, ?],
        inputs: Record[Any] = Record.empty,
        completedEvents: Set[String] = Set.empty,
        compensatedNodes: Set[String] = Set.empty,
        resumeCause: Maybe[Cause] = Maybe.empty
    )(
        interpreter: FlowInterpreter[S]
    )(using Frame): Record[In & Out] < S =

        case class Compensation(name: String, ctx: Record[Any], handler: Record[Any] => Any)

        def addField(ctx: Record[Any], name: String, value: Any): Record[Any] =
            new Record[Any](ctx.toDict ++ Dict(name -> value))

        AtomicRef.init[Chunk[Compensation]](Chunk.empty).map { compsRef =>

            def pushComp(
                name: String,
                ctx: Record[Any],
                handler: Record[Any] => Any
            ): Unit < Sync =
                compsRef.getAndUpdate(Compensation(name, ctx, handler) +: _).unit

            /** Runs the handlers this attempt registered, in reverse order of registration, skipping the ones already recorded.
              *
              * **Every registered handler runs, including a race loser's.** A branch that lost a race is not a branch that did
              * nothing: it can have completed a step, written its field and registered the handler that undoes it, and only then lost,
              * because a race is decided by the first branch to SUCCEED. Whatever it reserved is reserved for real, so dropping its
              * handler leaves the reservation standing with nothing left to undo it.
              *
              * A handler that THREW is not recorded, so a later attempt runs it again and the unwind carries on to the next one either
              * way. An unwind that stops at its first failing handler leaves every handler below it un-run, which is the opposite of
              * what an unwind is for.
              */
            def runComps(): Unit < S =
                compsRef.use { comps =>
                    Kyo.foreachDiscard(comps.toSeq) { comp =>
                        if compensatedNodes.contains(comp.name) then ()
                        else
                            Abort.run[Throwable](comp.handler(comp.ctx)).map {
                                case Result.Panic(ex)   => interpreter.onCompensationFailed(ex)
                                case Result.Failure(ex) => interpreter.onCompensationFailed(ex)
                                case _                  => interpreter.onCompensated(comp.name)
                            }
                    }
                }

            def fieldCompleted(ctx: Record[Any], name: String): Boolean =
                ctx.toDict.get(name) match
                    case Present(_) => true
                    case _          => false

            def eventCompleted(name: String): Boolean =
                completedEvents.contains(name)

            // Everything the store already holds for this execution, keyed the way the store keys it: by composition path.
            val durable = inputs.toDict

            /** What a child flow starts from: the inputs this entry supplied, plus everything the store holds under its path.
              *
              * The mapper supplies only the inputs the entry still owed, so without the second half a resumed subflow re-enters with
              * an empty record and re-executes every node it already completed. The fields are re-keyed to the bare names the child's
              * nodes and record type are written in, because a path names a node durably while a record names it structurally.
              *
              * **The durable half wins**, which is what makes the record the truth of what the child ran against: `Dict.concat`
              * gives its argument precedence and the argument here is the store's view. An entry that owed nothing passes an empty
              * mapped record, so such a child starts from the store alone, its recorded inputs included.
              */
            def childRecord(childPath: String, mapped: Record[Any]): Record[Any] =
                val prefix = s"$childPath${NodePath.Separator}"
                val inherited = durable.foldLeft(Dict.empty[String, Any]) { (acc, name, value) =>
                    if name.startsWith(prefix) then acc.update(name.substring(prefix.length), value) else acc
                }
                new Record[Any](mapped.toDict ++ inherited)
            end childRecord

            // path is the composition path the node sits under, empty at the flow's own top level
            def loop(
                flow: Flow[?, ?, ?],
                ctx: Record[Any],
                path: String = ""
            ): Record[Any] < S =
                flow match
                    case _: Init => ctx

                    case n: Output[?, ?, ?, ?, ?] @unchecked =>
                        val e         = n.erased
                        val qualified = NodePath.qualify(path, n.name)
                        if fieldCompleted(ctx, n.name) then
                            e.compensate match
                                case Present(handler) => pushComp(qualified, ctx, handler).andThen(ctx)
                                case _                => ctx
                        else
                            val computation = Sync.defer(e.fn(ctx))
                            interpreter.onOutput(qualified, computation, n.frame, n.meta)(using e.tag, e.schema)
                                .map { v =>
                                    val result = addField(ctx, n.name, v)
                                    e.compensate match
                                        case Present(handler) => pushComp(qualified, result, handler).andThen(result)
                                        case _                => result
                                    end match
                                }
                        end if

                    case n: Step[?, ?] @unchecked =>
                        val e         = n.erased
                        val qualified = NodePath.qualify(path, n.name)
                        if eventCompleted(qualified) then
                            e.compensate match
                                case Present(handler) => pushComp(qualified, ctx, handler).andThen(ctx)
                                case _                => ctx
                        else
                            val computation = Sync.defer(e.fn(ctx))
                            interpreter.onStep(qualified, computation, n.frame, n.meta).map { _ =>
                                e.compensate match
                                    case Present(handler) => pushComp(qualified, ctx, handler).andThen(ctx)
                                    case _                => ctx
                            }
                        end if

                    case n: Input[?, ?] @unchecked =>
                        // The satisfied branch records the node's own discharge, which is what clears the wait row the parked branch
                        // wrote. Nothing else records it: a satisfied input proceeds with the value in hand and writes no progress of
                        // its own, so a row written here would be outstanding forever and keep the execution permanently ready.
                        if fieldCompleted(ctx, n.name) then
                            interpreter.onInputDischarged(NodePath.qualify(path, n.name), n.frame, n.meta).andThen(ctx)
                        else
                            interpreter.onInput(NodePath.qualify(path, n.name), n.frame, n.meta)(using n.erased.tag, n.erased.schema)
                                .map(v => addField(ctx, n.name, v))

                    case n: Sleep =>
                        if eventCompleted(NodePath.qualify(path, n.name)) then ctx
                        else
                            interpreter.onSleep(NodePath.qualify(path, n.name), n.duration, n.frame, n.meta)
                                .andThen(ctx)

                    case n: Dispatch[?, ?, ?, ?, ?] @unchecked =>
                        val d               = n.erased
                        val nameStr: String = n.name
                        val durableName     = NodePath.qualify(path, nameStr)

                        // The handler undoes what the branch that ran did, so it is registered on the replay path too: a dispatch
                        // whose field is already stored ran its branch under an earlier attempt of this same execution.
                        def compensated(result: Record[Any] < S): Record[Any] < S =
                            d.compensate match
                                case Present(handler) =>
                                    result.map(updated => pushComp(durableName, updated, handler).andThen(updated))
                                case _ => result

                        def run(body: Record[Any] => Any < Any): Record[Any] < S =
                            val computation = Sync.defer(body(ctx))
                            interpreter.onOutput(durableName, computation, n.frame, n.meta)(using d.tag, d.schema)
                                .map(v => addField(ctx, nameStr, v))
                        end run

                        // The choice is recorded BEFORE the branch runs, which is what makes the record the truth of what ran.
                        def enter(branch: String, body: Record[Any] => Any < Any): Record[Any] < S =
                            interpreter.onChoice(durableName, branch, n.frame, n.meta).andThen(run(body))

                        def ask(idx: Int): Record[Any] < S =
                            if idx >= n.branches.length then enter(d.defaultName, d.default)
                            else
                                val b = n.branches(idx)
                                Sync.defer(b.cond(ctx)).map {
                                    case true  => enter(b.name, b.body)
                                    case false => ask(idx + 1)
                                }

                        // Replay re-enters the recorded branch and asks no condition, because the record is what ran and running a
                        // second branch beside it is worse than either branch alone. A recorded name this definition does not carry
                        // cannot arrive here: every branch name, the default's included, is part of the structural hash, so an
                        // execution whose branches changed is parked rather than resumed.
                        def reenter(branch: String, idx: Int): Record[Any] < S =
                            if idx >= n.branches.length then
                                if branch == d.defaultName then run(d.default)
                                else
                                    Abort.panic(new IllegalStateException(
                                        s"dispatch '$nameStr' recorded branch '$branch', which this definition does not declare"
                                    ))
                            else
                                val b = n.branches(idx)
                                if b.name == branch then run(b.body) else reenter(branch, idx + 1)

                        if fieldCompleted(ctx, nameStr) then compensated(ctx)
                        else
                            compensated {
                                interpreter.getField[String](FlowInterpreter.chosenKey(durableName)).map {
                                    case Present(branch) => reenter(branch, 0)
                                    case _               => ask(0)
                                }
                            }
                        end if

                    case n: LoopNode[?, ?, ?, ?, ?] @unchecked =>
                        val r               = n.erased
                        val nameStr: String = r.name
                        val durableName     = NodePath.qualify(path, nameStr)

                        import kyo.kernel.Loop.Continue
                        import kyo.kernel.Loop.Continue2

                        // The handler is registered only once the loop produced its value: a loop that stopped between iterations
                        // wrote nothing under its name and has nothing to undo.
                        def compensated(result: Record[Any] < S): Record[Any] < S =
                            r.compensate match
                                case Present(handler) =>
                                    result.map { updated =>
                                        if fieldCompleted(updated, nameStr) then
                                            pushComp(durableName, updated, handler).andThen(updated)
                                        else updated
                                    }
                                case _ => result

                        // `Loop.done(v)` erases to `v` itself, so an outcome that is not a continue IS the loop's value.
                        def continueState(outcome: Any): Maybe[Any] =
                            outcome match
                                case c: Continue2[?, ?] @unchecked => Maybe((c._1, c._2))
                                case c: Continue[?] @unchecked     => Maybe(c._1)
                                case _                             => Maybe.empty

                        // The BODY runs inside the node's bracket, so the iteration is what `timeout` bounds, what `retry` re-asks,
                        // and what the claim check guards. Nothing is recorded here, because what an iteration records depends on
                        // what it returned and a scheduled loop and an unscheduled one record differently.
                        def runIteration(state: Any): Any < S =
                            interpreter.onIteration(durableName, Sync.defer(r.body(state, ctx)), n.frame, n.meta)

                        // The knobs are stripped from the writes below. They govern an iteration's body, and writing down a value
                        // already in hand has nothing to bound and nothing to re-ask.
                        val writeMeta = Meta(n.meta.description, n.meta.tags)

                        def storeValue(value: Any): Record[Any] < S =
                            interpreter.onOutput(durableName, value, n.frame, writeMeta)(using r.tag, r.schema)
                                .andThen(addField(ctx, nameStr, value))

                        // Unscheduled: iterations are bracketed and none of them is recorded. That pairing is deliberate. An
                        // unscheduled loop's iteration count is unbounded, so a checkpoint per iteration is unbounded history,
                        // which is the cost `loop`'s own scaladoc states and declines to pay.
                        def unscheduled: Record[Any] < S =
                            def iterate(state: Any): Record[Any] < S =
                                runIteration(state).map { outcome =>
                                    continueState(outcome) match
                                        case Present(next) => iterate(next)
                                        case _             => storeValue(outcome)
                                }
                            iterate(r.initialState)
                        end unscheduled

                        // Scheduled: a continuing iteration records the state the NEXT one starts from, under the iteration's own
                        // durable name and under the state's own evidence, so a resume picks up where it stopped carrying what it
                        // was carrying. A finishing iteration records nothing there: the loop's own field is its record, and one
                        // write rather than two leaves no window in which the loop is done and cannot say so.
                        def scheduled(schedule: Schedule): Record[Any] < S =
                            def resume(state: Any, iterNum: Int, sched: Schedule): Record[Any] < S =
                                interpreter.checkCancelled.map {
                                    // Cancelled: stop where the loop stands. Nothing is written under the node's name, so no
                                    // compensation is registered for a value that does not exist.
                                    case true => ctx
                                    case false =>
                                        val iterName = IterationName.step(durableName, iterNum)
                                        if eventCompleted(iterName) then
                                            interpreter.getField[Any](iterName)(using r.stateTag, r.stateSchema).map {
                                                case Present(recorded) => afterIteration(recorded, iterNum, sched)
                                                // The checkpoint's event landed without its field, so this iteration runs again
                                                // from the state the last recorded one carried.
                                                case _ => execute(state, iterNum, sched)
                                            }
                                        else execute(state, iterNum, sched)
                                        end if
                                }

                            def execute(state: Any, iterNum: Int, sched: Schedule): Record[Any] < S =
                                runIteration(state).map { outcome =>
                                    continueState(outcome) match
                                        case Present(next) =>
                                            interpreter.onOutput(IterationName.step(durableName, iterNum), next, n.frame, writeMeta)(using
                                                r.stateTag,
                                                r.stateSchema
                                            ).andThen(afterIteration(next, iterNum, sched))
                                        case _ => storeValue(outcome)
                                }

                            def afterIteration(next: Any, iterNum: Int, sched: Schedule): Record[Any] < S =
                                val sleepName = IterationName.sleep(durableName, iterNum)
                                Clock.nowWith { now =>
                                    sched.next(now) match
                                        case Present((delay, nextSched)) =>
                                            val slept: Unit < S =
                                                if eventCompleted(sleepName) then ()
                                                else interpreter.onSleep(sleepName, delay, n.frame, n.meta)
                                            slept.andThen(resume(next, iterNum + 1, nextSched))
                                        // The schedule has no delay left and the body never produced a value. Nothing is written
                                        // under the loop's name: the state it carried is the loop's state type and the name is
                                        // typed for its value, and writing one where the other is declared is sound only where
                                        // the two coincide. The execution fails, naming the loop and what it ran, as a DECLARED
                                        // failure rather than an escaped one, so the status it reaches carries the kind an
                                        // operator groups by. Running out of schedule is a verdict, so nothing re-asks it.
                                        case _ =>
                                            interpreter.onFailure(durableName, FlowLoopExhaustedException(nameStr, iterNum + 1))
                                }
                            end afterIteration

                            resume(r.initialState, 0, schedule)
                        end scheduled

                        if fieldCompleted(ctx, nameStr) then compensated(ctx)
                        else
                            compensated {
                                r.schedule match
                                    case Absent            => unscheduled
                                    case Present(schedule) => scheduled(schedule)
                            }
                        end if

                    case n: ForEach[?, ?, ?, ?, ?] @unchecked =>
                        val r               = n.erased
                        val nameStr: String = n.name
                        val durableName     = NodePath.qualify(path, nameStr)

                        // Items above a bound of 1 run in their own fibers. Nothing is isolated across that boundary because
                        // the AST is erased at `S`: the row is `Any` here, so the isolate is the identity, and what carries an
                        // item's effects is the fiber itself, which is why the DSL says a body needing state merged back
                        // should ask for one item at a time.
                        given itemIsolate: Isolate[Any, Abort[FlowException] & Async, Any] = Isolate.derive

                        // The knobs are stripped from the writes that record a value already in hand. They govern an ITEM's body,
                        // which is the unit a fan-out brackets: a bound over the whole fan-out cannot be sized for the single slow
                        // item, and a retry of the whole fan-out re-runs the items that already landed.
                        val writeMeta = Meta(n.meta.description, n.meta.tags)

                        // The collection is the only thing that says which item produced which recorded result, so a size that
                        // disagrees with what the fan-out recorded leaves every pairing unknowable. The node fails. Re-running the
                        // fan-out whole instead would write onto paths that already carry the previous attempt's results, hand each
                        // handler a value from a run it did not belong to, and re-fire every item's side effects on the way.
                        def mismatch(recorded: Int, recomputed: Int): Record[Any] < S =
                            interpreter.onFailure(durableName, FlowNondeterministicCollectionException(durableName, recorded, recomputed))

                        // The record an entry carries decides one thing, whether a race kept the branch the entry belongs to, and
                        // an item's handler is applied to the item and its result rather than to a record. So the entry carries the
                        // record the fan-out started from: the node's own field does not exist while its items are still landing,
                        // and that is the whole point of registering a handler per item rather than per node.
                        def pushItemComp(index: Int, item: Any, value: Any): Unit < Sync =
                            r.compensate match
                                case Present(handler) =>
                                    pushComp(itemKey(durableName, index), ctx, (_: Record[Any]) => handler(item, value))
                                case _ => ()

                        // One item's durable transition, whole: read back what an earlier attempt recorded, or run the body under
                        // the node's own policy and record it. The handler is registered either way, because an item recorded by an
                        // earlier attempt did its work just as much as one that ran here.
                        def itemResult(index: Int, item: Any, replaying: Boolean): Any < S =
                            val key = itemKey(durableName, index)
                            def compute: Any < S =
                                interpreter.onOutput(key, Sync.defer(r.body(item)), n.frame, n.meta)(using r.itemTag, r.itemSchema)
                            // The count is written before any item is, so a fan-out with no count recorded has no item recorded
                            // either and the read would answer absent for every one of them.
                            val value: Any < S =
                                if !replaying then compute
                                else
                                    interpreter.getField[Any](key)(using r.itemTag, r.itemSchema).map {
                                        case Present(recorded) => recorded
                                        case _                 => compute
                                    }
                            value.map(v => pushItemComp(index, item, v).andThen(v))
                        end itemResult

                        def runItems(items: Seq[Any], replaying: Boolean): Record[Any] < S =
                            // Every item's whole transition runs inside the fan-out's own bound, so a result is recorded as its item
                            // completes rather than after the last one. The row is erased the way an item's body already is: what
                            // these computations carry are the effects a fiber carries anyway.
                            val results: Chunk[Any] < S =
                                Async.foreachIndexed(items, r.concurrency) { (index, item) =>
                                    itemResult(index, item, replaying).asInstanceOf[Any < Any]
                                }.asInstanceOf[Chunk[Any] < S]
                            results.map { values =>
                                interpreter.onOutput(durableName, values, n.frame, writeMeta)(using r.tag, r.schema)
                                    .andThen(addField(ctx, nameStr, values))
                            }
                        end runItems

                        // A fan-out that has not written its own field yet. The count is what an attempt that resumes it checks the
                        // collection against, and it is recorded before the first item so that an absent count means an untouched
                        // fan-out.
                        //
                        // The collection runs outside the claim check the count's write makes, the way a dispatch's conditions run
                        // outside the check its choice makes. Nothing needs it bracketed: a collection is required to be a
                        // deterministic function of the record and is recomputed by every attempt that re-enters the node, so it is
                        // not a computation a claim could protect from running twice. What the claim guards is every write below.
                        def start: Record[Any] < S =
                            interpreter.getField[Int](countKey(durableName)).map { recorded =>
                                Sync.defer(r.collection(ctx)).map { items =>
                                    recorded match
                                        case Present(count) if count != items.size => mismatch(count, items.size)
                                        case Present(_)                            => runItems(items, replaying = true)
                                        case _ =>
                                            interpreter.onOutput(countKey(durableName), items.size, n.frame, writeMeta)
                                                .andThen(runItems(items, replaying = false))
                                }
                            }

                        // The fan-out completed under an earlier attempt, so nothing is left to record. What is left is to register
                        // the handlers again, the way every other compensated node does on replay, and pairing them with their items
                        // is the one thing the collection is still needed for. The node's own field says how many items ran, which is
                        // what the recomputed collection is checked against here: past the last item the count field has no reader.
                        def replayCompleted(results: Seq[Any]): Record[Any] < S =
                            Sync.defer(r.collection(ctx)).map { items =>
                                if items.size != results.size then mismatch(results.size, items.size)
                                else Kyo.foreachDiscard(items.indices)(i => pushItemComp(i, items(i), results(i))).andThen(ctx)
                            }

                        if !fieldCompleted(ctx, nameStr) then start
                        else
                            (r.compensate, ctx.toDict.get(nameStr)) match
                                case (Present(_), Present(results: Seq[Any] @unchecked)) => replayCompleted(results)
                                case _                                                   => ctx
                        end if

                    // A race keeps every handler both branches registered, the loser's included. A branch that lost is not a branch
                    // that did nothing: a race is decided by the first branch to SUCCEED, so a loser can have completed a step,
                    // written its field and registered the handler that undoes it, and only then lost. Filtering the handlers by
                    // whether the pushing context's keys are a subset of the winner's is wrong in both directions at once, and drops
                    // exactly the handlers whose branch did the most work: a compensated output hands its handler its own value in
                    // scope, and that key is never in the winner's set.
                    case n: Race[?, ?, ?, ?, ?, ?] @unchecked =>
                        val leftResult  = loop(n.left, ctx, path)
                        val rightResult = loop(n.right, ctx, path)
                        interpreter.onRace(leftResult, rightResult, n.erased.isolate)

                    case n: Subflow[?, ?, ?, ?, ?, ?] @unchecked =>
                        if fieldCompleted(ctx, n.name) then ctx
                        else
                            val nameStr   = n.name: String
                            val childPath = NodePath.qualify(path, nameStr)
                            // The inputs this entry still owes the store: the ones the record does not already hold. An input the
                            // store holds is what the child ran against and is never written again and never compared with the
                            // mapper's answer, which is the same rule a recorded dispatch branch follows.
                            val owed = n.erased.childInputs.filter(input =>
                                durable.get(NodePath.qualify(childPath, input.name)) match
                                    case Present(_) => false
                                    case _          => true
                            )

                            /** One owed input recorded from the mapper's record, answering what the child will run against.
                              *
                              * A refusal is not a failure. Two arms of a `race` may embed one subflow name, so both entries write
                              * the same path and the store answers the second already-recorded; the race exemption's rule is that
                              * the first write decides the field, so this reads that value back and hands it to the child rather
                              * than running it against a value nothing kept. The read cannot answer absent for a path the store
                              * just refused as occupied, and if it ever did the mapper's own value is the honest fallback.
                              */
                            def record(acc: Dict[String, Any], input: ChildInput): Dict[String, Any] < S =
                                val qualified = NodePath.qualify(childPath, input.name)
                                acc.get(input.name) match
                                    case Present(value) =>
                                        interpreter.onInputSupplied(qualified, value, input.frame, input.meta)(using
                                            input.tag,
                                            input.schema
                                        ).map {
                                            case true => acc
                                            case false =>
                                                interpreter.getField[Any](qualified)(using input.tag, input.schema).map {
                                                    case Present(recorded) => acc.update(input.name, recorded)
                                                    case _                 => acc
                                                }
                                        }
                                    // The mapper handed back no value under a name the child declares, which its own type forbids.
                                    // Nothing is written for it, and the child's input node decides what to do with an absent
                                    // field the way it decides for any other.
                                    case _ => acc
                                end match
                            end record

                            // The mapper runs only for an entry that owes the store something, which is the first entry and a
                            // re-entry after a crash between two of its input writes. An entry that finds every declared input
                            // recorded re-enters the child against the record without asking the mapper at all, exactly as a
                            // dispatch re-enters its recorded branch without re-asking its conditions. It runs before the hook's
                            // own guard, the accepted precedent a dispatch's conditions and a fan-out's collection already set.
                            val mapped: Record[Any] < S =
                                if owed.isEmpty then Record.empty
                                else
                                    n.erased.inputMapper(ctx).map { inputRecord =>
                                        Kyo.foldLeft(owed)(inputRecord.toDict)(record).map(new Record[Any](_))
                                    }

                            mapped
                                .map(inputRecord =>
                                    loop(n.childFlow, childRecord(childPath, inputRecord), childPath)
                                )
                                .map(childResult => addField(ctx, nameStr, childResult))

                    case n: AndThen[?, ?, ?, ?, ?, ?] @unchecked =>
                        loop(n.first, ctx, path).map(ctx2 => loop(n.second, ctx2, path))

                    case n: Zip[?, ?, ?, ?, ?, ?] @unchecked =>
                        val l = loop(n.left, ctx, path)
                        val r = loop(n.right, ctx, path)
                        interpreter.onZip(l, r, ctx, n.erased.isolate)

                    case n: Gather[?, ?, ?] @unchecked =>
                        if n.flows.isEmpty then ctx
                        else
                            n.flows.toSeq.map(f => loop(f, ctx, path))
                                .reduce((l, r) => interpreter.onZip(l, r, ctx, n.erased.isolate))

            val rawResult = loop(flow, new Record[Any](inputs.toDict))

            /** Re-raises what ended the flow, after its compensations have run.
              *
              * A [[FlowException]] goes back out on the typed channel it was raised on, which is what makes a step's own
              * [[FlowDomainException]] survive as a failure rather than arriving at the engine as a panic and losing everything but its
              * message. Anything else is not a declared failure of this flow and stays a panic.
              */
            def unwindFrom(cause: Flow.Cause, reraise: Nothing < S): Nothing < S =
                compsRef.use { comps =>
                    if comps.nonEmpty then
                        interpreter.onCompensationStart(cause)
                            .andThen(runComps())
                            .andThen(interpreter.onCompensationComplete)
                            .andThen(reraise)
                    else reraise
                }

            /** What ended the walk, and whether it was a verdict on the flow at all.
              *
              * **Two endings are not verdicts and unwind nothing.** A [[FlowStoreException]] says the store could not be reached,
              * which is a fact about the infrastructure and says nothing about the work; an [[Interrupted]] says this attempt was
              * told to stop. Neither has decided that what already ran should be undone, so neither may write a status, run a
              * handler or append an event: they go straight back out, and the execution is left exactly as claimable as it was, for
              * the next attempt to carry on from where this one stopped.
              *
              * Entering the unwind for either is enough to lose the execution. The first handler's registration turns a transient
              * blip into `Compensating(Failure(<the store's own message>))` written through the claim with every handler run behind
              * it, and the next attempt reads that row as an unwind to resume and terminalises the saga `Failed` on an outage it did
              * not cause. Classifying the ending correctly further down the engine does not undo that, because by then the row and
              * the handlers have already moved.
              */
            def unwind(ex: Throwable): Nothing < S =
                ex match
                    case _: FlowStoreException | _: Interrupted => interpreter.onUnwind(ex)
                    case _                                      =>
                        // The verdict that started the unwind is recorded with it, because a resumed `Compensating` execution has to
                        // re-enter the unwind rather than replay forward into the step that failed. A resumed one carries the verdict
                        // its interrupted attempt already recorded, so nothing is re-derived from an exception that no longer exists.
                        val cause = ex match
                            case e: FlowResumedUnwindException => e.cause
                            case _: FlowCancelledException     => Flow.Cause.Cancellation
                            case e: FlowException              => Flow.Cause.Failure(messageOf(e), Maybe(e.kind))
                            case e                             => Flow.Cause.Failure(messageOf(e), Maybe.empty)
                        unwindFrom(cause, interpreter.onUnwind(ex))
            end unwind

            Abort.run[Throwable](rawResult).map {
                case Result.Success(record) =>
                    // A forward pass that reached the end while RESUMING an unwind reached it in skip-only mode: every node it walked
                    // was already complete, so it re-registered handlers and computed nothing. Answering with that record would take a
                    // `Compensating` execution to `Completed` with its compensations half run, which is the transition this module
                    // documents as impossible.
                    resumeCause match
                        case Present(cause) => unwindFrom(cause, interpreter.onUnwind(FlowResumedUnwindException(cause)))
                        case _              => record.asInstanceOf[Record[In & Out]]
                case Result.Failure(ex) => unwind(ex)
                case Result.Panic(ex)   => unwind(ex)
            }
        }
    end run

    // --- Internal AST ---

    private[kyo] object internal:

        /** A compensation handler as the interpreter applies it, over the erased record.
          *
          * [[Record]]'s type parameter is invariant by design, so a handler declared over the record the node's value is in cannot be
          * seen as one over `Record[Any]` by subtyping, and the erased AST the interpreter walks has nothing else to hand it. Nodes
          * whose handler is declared at the DSL site therefore store it in this shape.
          */
        type Handler[S] = Record[Any] => Unit < (S & Async & Abort[FlowException])

        /** Stores a DSL-declared compensation handler in the shape the interpreter applies. See [[Handler]] for why a cast is what
          * that takes.
          */
        def handlerOf[C, S](handler: Record[C] => Unit < (S & Async & Abort[FlowException])): Handler[S] =
            handler.asInstanceOf[Handler[S]]

        /** A fan-out's per-item compensation handler as the interpreter applies it, over the erased item and its erased result.
          *
          * A fan-out's unit of work is the item, so its handler takes an item and what that item produced rather than the record a
          * node-level handler takes. The record holds the whole `Chunk`, which exists only once the last item has landed, and a
          * fan-out that failed part-way is the one case where unwinding matters and that value is not there.
          */
        type ItemHandler[S] = (Any, Any) => Unit < (S & Async & Abort[FlowException])

        /** Stores a DSL-declared per-item handler in the shape the interpreter applies. See [[Handler]] for why a cast is what that
          * takes.
          */
        def itemHandlerOf[E, V, S](handler: (E, V) => Unit < (S & Async & Abort[FlowException])): ItemHandler[S] =
            handler.asInstanceOf[ItemHandler[S]]

        /** The durable key a fan-out's item at `index` records its result under.
          *
          * An item is a composition step the way a subflow instance is, one level down, so its key is built the same way: item 2 of
          * `charges` is `charges~2`. See [[kyo.internal.NodePath]].
          */
        def itemKey(foreach: String, index: Int): String = NodePath.qualify(foreach, index.toString)

        /** The durable key a fan-out records how many items it had under.
          *
          * It rides the fan-out's own path, the namespace its items are keyed in, where `count` can be neither an item's index nor a
          * name a user chose: registration refuses the separator in a node name, and a subflow instance sharing the fan-out's name is
          * a duplicate the same refusal catches. It cannot ride `#` instead, because a progress walk reads every `name#...` as an
          * iteration of `name` and would draw the fan-out as done the moment its count landed.
          */
        def countKey(foreach: String): String = NodePath.qualify(foreach, "count")

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

        final case class Output[Ctx, CompCtx, N <: String & Singleton, V, S](
            name: N & String,
            fn: Record[Ctx] => V < S,
            meta: Meta,
            compensate: Maybe[Record[CompCtx] => Unit < (S & Async & Abort[FlowException])]
        )(using val frame: Frame, val tag: Tag[V], val schema: Schema[V]) extends Flow[Any, N ~ V, S]:
            private[kyo] def erased: Output[Any, Any, Nothing, Any, Any] = this.asInstanceOf[Output[Any, Any, Nothing, Any, Any]]
        end Output

        final case class Step[Ctx, S](
            name: String,
            fn: Record[Ctx] => Unit < S,
            meta: Meta,
            compensate: Maybe[Record[Ctx] => Unit < (S & Async & Abort[FlowException])]
        )(using val frame: Frame) extends Flow[Any, Any, S]:
            private[kyo] def erased: Step[Any, Any] = this.asInstanceOf[Step[Any, Any]]
        end Step

        final case class Input[N <: String & Singleton, V](name: N & String, meta: Meta)(
            using
            val frame: Frame,
            val tag: Tag[V],
            val schema: Schema[V]
        ) extends Flow[N ~ V, N ~ V, Any]:
            private[kyo] def erased: Input[Nothing, Any] = this.asInstanceOf[Input[Nothing, Any]]
        end Input

        final case class Sleep(name: String, duration: Duration, meta: Meta)(using val frame: Frame)
            extends Flow[Any, Any, Any]

        final case class Dispatch[Ctx, N <: String & Singleton, V, S, Sb](
            name: N & String,
            branches: Chunk[BranchData[Any, Any, Any]],
            defaultName: String,
            default: Record[Ctx] => V < S,
            defaultFrame: Frame,
            meta: Meta,
            compensate: Maybe[Handler[S & Sb]]
        )(using val frame: Frame, val tag: Tag[V], val schema: Schema[V]) extends Flow[Any, N ~ V, S & Sb]:
            private[kyo] def erased: Dispatch[Any, Nothing, Any, Any, Any] = this.asInstanceOf[Dispatch[Any, Nothing, Any, Any, Any]]
        end Dispatch

        final case class LoopNode[Ctx, N <: String & Singleton, State, V, S](
            name: N & String,
            body: (State, Record[Ctx]) => Any < S, // returns Loop.Outcome[State, V] (erased at S boundary)
            initialState: State,
            schedule: Maybe[Schedule],
            meta: Meta,
            compensate: Maybe[Handler[S]]
        )(using
            val frame: Frame,
            val tag: Tag[V],
            val schema: Schema[V],
            // A scheduled loop checkpoints the state its next iteration starts from, under the iteration's own durable name and
            // under the state's own evidence. That is why the state's tag is part of the flow's version identity: replay decodes
            // the checkpoint back through it.
            val stateTag: Tag[State],
            val stateSchema: Schema[State]
        ) extends Flow[Any, N ~ V, S]:
            private[kyo] def erased: LoopNode[Any, Nothing, Any, Any, Any] = this.asInstanceOf[LoopNode[Any, Nothing, Any, Any, Any]]
        end LoopNode

        final case class ForEach[Ctx, N <: String & Singleton, E, V, S](
            name: N & String,
            concurrency: Int,
            collection: Record[Ctx] => Seq[E] < S,
            body: E => V < S,
            meta: Meta,
            compensate: Maybe[ItemHandler[S]]
        )(using
            val frame: Frame,
            // The node persists the whole collection, so its evidence is for `Chunk[V]` and is captured at the DSL site: `V` is
            // erased to `Any` by the time the interpreter sees the node, and `Tag[Chunk[Any]]` is not the tag a caller reads with.
            val tag: Tag[Chunk[V]],
            val schema: Schema[Chunk[V]],
            // An item's own evidence, captured at the same site and for the same reason. Each item's result is a durable field of
            // its own, written and read back under the element type the caller declared rather than under the collection's.
            val itemTag: Tag[V],
            val itemSchema: Schema[V]
        ) extends Flow[Any, N ~ Chunk[V], S]:
            private[kyo] def erased: ForEach[Any, Nothing, Any, Any, Any] = this.asInstanceOf[ForEach[Any, Nothing, Any, Any, Any]]
        end ForEach

        final case class Race[In1, Out1, S1, In2, Out2, S2](
            left: Flow[In1, Out1, S1],
            right: Flow[In2, Out2, S2]
        )(using val frame: Frame, val isolate: Isolate[S1 & S2, Abort[FlowException] & Async, S1 & S2])
            extends Flow[In1 & In2, Out1 | Out2, S1 & S2]:
            private[kyo] def erased: Race[Any, Any, Any, Any, Any, Any] = this.asInstanceOf[Race[Any, Any, Any, Any, Any, Any]]
        end Race

        /** One input a subflow's child declares directly, carrying everything recording its value takes.
          *
          * The evidence is captured at the input node, because that is the only place the input's own `V` still exists: the
          * interpreter walks an AST erased at `Any` and the value the mapper supplied is written under the input's declared type,
          * which is also the type the schema decodes it back through. The tag is erased the way [[kyo.internal.FlowLint.inputMetas]]
          * erases a top-level input's, so a mapper-supplied field and a start-seeded one are stored identically.
          */
        final case class ChildInput(name: String, tag: Tag[Any], schema: Schema[Any], frame: Frame, meta: Meta)

        final case class Subflow[In, Ctx, N <: String & Singleton, In2, Out2, S](
            name: N & String,
            childFlow: Flow[In2, Out2, ?],
            inputMapper: Record[Ctx] => Record[In2] < S,
            meta: Meta
        )(using val frame: Frame) extends Flow[In, Ctx & (N ~ Record[Out2]), S]:
            private[kyo] def erased: Subflow[Any, Any, Nothing, Any, Any, Any] =
                this.asInstanceOf[Subflow[Any, Any, Nothing, Any, Any, Any]]

            /** The inputs the child declares DIRECTLY, in walk order and without repeats, which entering this instance records.
              *
              * Direct, because a grandchild's inputs belong to the nested instance's own entry: the collector leaves `onSubflow` at
              * its `empty` default, so the walk contributes an inner child's inputs at `outer~inner~ia` when `inner` is entered
              * rather than flattening them under `outer`.
              *
              * Without repeats, because a name can be declared twice in one child: two arms of a `race` may share a written name by
              * the race exemption, and one write per declared occurrence would answer the second already-recorded for no reason.
              * Walk order is kept so the writes land in the order the child declares them, which is what makes a partial seed after
              * a crash a prefix rather than an arbitrary subset.
              *
              * Computed once per node rather than per entry: the answer depends on the child's AST alone, which is immutable, and an
              * execution that resumes a hundred times walks it once.
              */
            private[kyo] lazy val childInputs: Chunk[ChildInput] =
                val collected = FlowFold(childFlow)(new FlowVisitorCollect[Chunk[ChildInput]](Chunk.empty, _ ++ _):
                    override def onInput[V](name: String, frame: Frame, meta: Meta)(using Tag[V], Schema[V]) =
                        Chunk(ChildInput(name, Tag[V].erased, summon[Schema[V]].asInstanceOf[Schema[Any]], frame, meta)))
                collected.foldLeft((Chunk.empty[ChildInput], Set.empty[String])) { case ((acc, seen), input) =>
                    if seen.contains(input.name) then (acc, seen) else (acc.append(input), seen + input.name)
                }._1
            end childInputs
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
