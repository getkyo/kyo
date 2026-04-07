package kyo

import kyo.internal.*

/** Persistence layer for durable workflow execution state.
  *
  * Most users only need `FlowStore.initMemory` (for development/testing) or pass a store to `Flow.runServer`. Implementing this trait is
  * for integrating a durable database like PostgreSQL — you provide the 15 abstract methods and get crash recovery, multi-executor
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
  * Invariants every implementation must uphold:
  *   - I1: claimReady never returns the same execution to two concurrent callers
  *   - I2: updateStatus writes event + status atomically (no window where one is updated without the other)
  *   - I3: putFieldIfAbsent is an atomic check-and-write (exactly one concurrent writer wins)
  *   - I4: renewClaim returns false if the claim was taken by another executor
  *   - I5: terminal status (Completed/Failed/Cancelled) cannot revert to a non-terminal status
  *   - I6: read-your-writes consistency within a single caller
  *   - I7: getHistory returns events in append order
  *   - I8: claimReady returns only genuinely progressable executions (not terminal, not future-sleeping, not waiting without field)
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
    )(using Frame): Seq[FlowStore.ExecutionState] < Async

    /** Extend the lease. Returns false if the claim was lost (expired or taken by another executor). */
    def renewClaim(
        executionId: Flow.Id.Execution,
        executorId: Flow.Id.Executor,
        lease: Duration
    )(using Frame): Boolean < Async

    /** Release the claim. No-op if not the owner. */
    def releaseClaim(
        executionId: Flow.Id.Execution,
        executorId: Flow.Id.Executor
    )(using Frame): Unit < Async

    // --- Execution state: atomic event + status ---

    /** Create a new execution with an initial status, event, and structural hash for version enforcement. */
    def createExecution(
        executionId: Flow.Id.Execution,
        status: Flow.Status,
        event: Flow.Event,
        hash: String
    )(using Frame): Unit < Async

    /** Write an event AND update execution status atomically. Does not modify the structural hash. */
    def updateStatus(
        executionId: Flow.Id.Execution,
        status: Flow.Status,
        event: Flow.Event
    )(using Frame): Unit < Async

    /** Read execution state. */
    def getExecution(
        executionId: Flow.Id.Execution
    )(using Frame): Maybe[FlowStore.ExecutionState] < Async

    /** List executions for a workflow, optionally filtered by status. */
    def listExecutions(
        flowId: Flow.Id.Workflow,
        status: Maybe[Flow.Status],
        limit: Int,
        offset: Int
    )(using Frame): Chunk[FlowStore.ExecutionState] < Async

    // --- Fields ---

    /** Write a typed field. Overwrites if exists. */
    def putField[V: Tag: Json](
        executionId: Flow.Id.Execution,
        name: String,
        value: V
    )(using Frame): Unit < Async

    /** Atomic check-and-write. Returns true if written (was absent), false if already existed. */
    def putFieldIfAbsent[V: Tag: Json](
        executionId: Flow.Id.Execution,
        name: String,
        value: V
    )(using Frame): Boolean < Async

    /** Read a typed field. Returns Absent if missing or type tag mismatch. */
    def getField[V: Tag: Json](
        executionId: Flow.Id.Execution,
        name: String
    )(using Frame): Maybe[V] < Async

    /** Read all fields for an execution as raw FieldData. */
    def getAllFields(
        executionId: Flow.Id.Execution
    )(using Frame): Dict[String, FlowStore.FieldData] < Async

    // --- Events ---

    /** Append an event without changing execution status. */
    def appendEvent(
        executionId: Flow.Id.Execution,
        event: Flow.Event
    )(using Frame): Unit < Async

    /** Read paginated event history. */
    def getHistory(
        executionId: Flow.Id.Execution,
        limit: Int,
        offset: Int
    )(using Frame): FlowStore.HistoryPage < Async

    // --- Workflows ---

    def putWorkflow(meta: FlowEngine.WorkflowInfo)(using Frame): Unit < Async
    def getWorkflow(id: Flow.Id.Workflow)(using Frame): Maybe[FlowEngine.WorkflowInfo] < Async
    def listWorkflows(using Frame): Seq[FlowEngine.WorkflowInfo] < Async

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

    /** A context field entry: serialized value + runtime type tag. */
    case class FieldData(value: String, tag: Tag[Any]) derives Json

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
    ) derives CanEqual, Json

    /** Paginated event history. */
    case class HistoryPage(events: Chunk[Flow.Event], hasMore: Boolean) derives CanEqual

end FlowStore
