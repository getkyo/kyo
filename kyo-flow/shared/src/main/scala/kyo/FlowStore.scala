package kyo

import kyo.internal.*

/** Persistence layer for durable workflow execution state.
  *
  * Most users only need `FlowStore.initMemory` (for development/testing) or pass a store to `Flow.runServer`. Implementing this trait is
  * for integrating a durable database like PostgreSQL — you provide the 16 abstract methods and get crash recovery, multi-executor
  * coordination, and exactly-once field writes for free.
  *
  * Each FlowStore instance is a stateless client over a shared backing database. All coordination (claiming executions, preventing
  * duplicates, ensuring ordering) happens through atomic operations in the database. The in-memory implementation uses `AtomicRef` as the
  * database and `Signal.Ref` for notification.
  *
  * The key concept is the **claim lease**: `claimReady` atomically finds ready executions and assigns them to an executor with a
  * time-limited lease. If the executor dies, the lease expires and `claimReady` hands the execution to another executor. The engine renews
  * leases periodically to keep long-running steps alive.
  *
  * Every method carries [[FlowStoreException]] as its error channel, so an implementation reports a failure of its backend as a value
  * rather than by panicking. What the engine does with one: a failure while polling is treated as transient, recorded on
  * [[FlowEngine.health]], and retried; a failure while running an execution fails that execution with the failure's message. A store that
  * knows a failure is transient marks it [[FlowStoreException.Retryable]].
  *
  * Invariants every implementation must uphold:
  *   - I1: claimReady never returns the same execution to two concurrent callers
  *   - I2: updateStatus writes event + status atomically (no window where one is updated without the other)
  *   - I3: putFieldIfAbsent is an atomic check-and-write (exactly one concurrent writer wins)
  *   - I4: renewClaim returns false if the claim was taken by another executor
  *   - I5: terminal status (Completed/Failed/Cancelled) cannot revert to a non-terminal status
  *   - I6: read-your-writes consistency within a single caller
  *   - I7: getHistory returns events in append order
  *   - I8: claimReady returns only genuinely progressable executions (not terminal, not future-sleeping, not waiting without field, not
  *     parked). A parked execution is waiting for a definition to be registered, which claiming it cannot bring about, so returning it
  *     would spin the caller's poll loop: the claim changes nothing and releases at once. The engine takes its own parked executions out
  *     of that state when a matching definition is registered.
  *   - I9: returned executions have executor = caller's ID and claimExpiry = now + lease
  *
  * @see
  *   [[kyo.FlowEngine]] The engine that coordinates store operations
  * @see
  *   [[kyo.FlowStore.ExecutionState]] The execution state row
  * @see
  *   [[kyo.FlowStore.FieldData]] Serialized field storage format
  */
abstract class FlowStore:

    // --- Coordination: atomic find-and-claim ---

    /** Atomically find ready executions and claim them. Blocks up to timeout if nothing ready. Returns exclusively — no two concurrent
      * callers get the same execution.
      */
    def claimReady(
        workflowIds: Set[Flow.Id.Workflow],
        executorId: Flow.Id.Executor,
        lease: Duration,
        limit: Int,
        timeout: Duration
    )(using Frame): Seq[FlowStore.ExecutionState] < (Async & Abort[FlowStoreException])

    /** Extend the lease. Returns false if the claim was lost (expired or taken by another executor). */
    def renewClaim(
        executionId: Flow.Id.Execution,
        executorId: Flow.Id.Executor,
        lease: Duration
    )(using Frame): Boolean < (Async & Abort[FlowStoreException])

    /** Release the claim. No-op if not the owner. */
    def releaseClaim(
        executionId: Flow.Id.Execution,
        executorId: Flow.Id.Executor
    )(using Frame): Unit < (Async & Abort[FlowStoreException])

    // --- Execution state: atomic event + status ---

    /** Create a new execution with an initial status, event, and structural hash for version enforcement. */
    def createExecution(
        executionId: Flow.Id.Execution,
        status: Flow.Status,
        event: Flow.Event,
        hash: String
    )(using Frame): Unit < (Async & Abort[FlowStoreException])

    /** Write an event AND update execution status atomically. Does not modify the structural hash. */
    def updateStatus(
        executionId: Flow.Id.Execution,
        status: Flow.Status,
        event: Flow.Event
    )(using Frame): Unit < (Async & Abort[FlowStoreException])

    /** Read execution state. */
    def getExecution(
        executionId: Flow.Id.Execution
    )(using Frame): Maybe[FlowStore.ExecutionState] < (Async & Abort[FlowStoreException])

    /** List executions for a workflow, optionally filtered by status.
      *
      * The filter matches the status CASE and ignores any payload it carries, so `Present(Sleeping(name, until))` lists every sleeping
      * execution rather than only those sleeping under that name until that exact instant. A caller asking "list the sleeping executions"
      * has no `until` to guess, and two implementations that both read "filtered by status" would otherwise disagree about it.
      *
      * `limit` and `offset` are as [[getHistory]] documents them.
      */
    def listExecutions(
        flowId: Flow.Id.Workflow,
        status: Maybe[Flow.Status],
        limit: Int,
        offset: Int
    )(using Frame): Chunk[FlowStore.ExecutionState] < (Async & Abort[FlowStoreException])

    // --- Fields ---

    /** Write a typed field. Overwrites if exists. */
    def putField[V: Tag: Schema](
        executionId: Flow.Id.Execution,
        name: String,
        value: V
    )(using Frame): Unit < (Async & Abort[FlowStoreException])

    /** Atomic check-and-write. Returns true if written (was absent), false if already existed. */
    def putFieldIfAbsent[V: Tag: Schema](
        executionId: Flow.Id.Execution,
        name: String,
        value: V
    )(using Frame): Boolean < (Async & Abort[FlowStoreException])

    /** Read a typed field. Returns Absent if missing or type tag mismatch. */
    def getField[V: Tag: Schema](
        executionId: Flow.Id.Execution,
        name: String
    )(using Frame): Maybe[V] < (Async & Abort[FlowStoreException])

    /** Read all fields for an execution as raw FieldData. */
    def getAllFields(
        executionId: Flow.Id.Execution
    )(using Frame): Dict[String, FlowStore.FieldData] < (Async & Abort[FlowStoreException])

    // --- Events ---

    /** Append an event without changing execution status. */
    def appendEvent(
        executionId: Flow.Id.Execution,
        event: Flow.Event
    )(using Frame): Unit < (Async & Abort[FlowStoreException])

    /** Read paginated event history.
      *
      * `limit` is a count of events and may be `Int.MaxValue`, which the engine passes for "every event" and an implementation must
      * therefore not add to: the obvious way to answer `hasMore`, fetching `limit + 1` rows, overflows to `Int.MinValue` there and a
      * server rejects the negative limit. Clamp instead. `offset` is non-negative.
      */
    def getHistory(
        executionId: Flow.Id.Execution,
        limit: Int,
        offset: Int
    )(using Frame): FlowStore.HistoryPage < (Async & Abort[FlowStoreException])

    // --- Workflows ---

    def putWorkflow(meta: FlowEngine.WorkflowInfo)(using Frame): Unit < (Async & Abort[FlowStoreException])
    def getWorkflow(id: Flow.Id.Workflow)(using Frame): Maybe[FlowEngine.WorkflowInfo] < (Async & Abort[FlowStoreException])
    def listWorkflows(using Frame): Seq[FlowEngine.WorkflowInfo] < (Async & Abort[FlowStoreException])

end FlowStore

object FlowStore:

    // --- Factory ---

    /** Create a transient in-memory store for development and testing.
      *
      * All state is lost when the process exits. For production, implement the `FlowStore` trait against a durable database (e.g.,
      * PostgreSQL) and pass it to `Flow.runServer(store, flows*)`.
      */
    def initMemory(using Frame): FlowStore < (Sync & Scope) =
        AtomicRef.init(MemoryData.empty).map { ref =>
            Channel.init[Unit](1).map { channel =>
                new MemoryFlowStore(ref, channel)
            }
        }

    // --- Types ---

    /** A context field entry: serialized value + runtime type tag.
      *
      * The tag is stored erased, because a store holds fields of every type a workflow declares and one row type cannot be parameterised
      * per field. A store implementing [[FlowStore.putField]] is handed a `Tag[V]`, which is not a `Tag[Any]` (`Tag` is invariant), so the
      * companion's [[FieldData.apply]] does the widening once here rather than leaving every implementation to write the cast for it.
      *
      * @param value
      *   the value encoded as JSON text
      * @param tag
      *   the erased tag of the value's declared type, compared against the reader's on the way out
      */
    case class FieldData(value: String, tag: Tag[Any]) derives Schema

    object FieldData:
        /** Builds a field entry from the `Tag[V]` the SPI hands an implementation, erasing it here.
          *
          * Named at the JVM level because it erases to the same signature as the case class's own `apply`, which takes the already-erased
          * tag.
          */
        @scala.annotation.targetName("applyErasingTag")
        def apply[V](value: String, tag: Tag[V]): FieldData = new FieldData(value, tag.erased)
    end FieldData

    /** Execution state row. */
    case class ExecutionState(
        executionId: Flow.Id.Execution,
        flowId: Flow.Id.Workflow,
        status: Flow.Status,
        executor: Maybe[Flow.Id.Executor] = Maybe.empty,
        claimExpiry: Maybe[Instant] = Maybe.empty,
        hash: String,
        created: Instant,
        updated: Instant
    ) derives CanEqual, Schema

    /** Paginated event history. */
    case class HistoryPage(events: Chunk[Flow.Event], hasMore: Boolean) derives CanEqual

end FlowStore
