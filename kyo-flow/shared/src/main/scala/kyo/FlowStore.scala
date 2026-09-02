package kyo

import kyo.internal.*

/** Persistence layer for durable workflow execution state.
  *
  * Most users only need `FlowStore.initMemory` (for development/testing) or pass a store to `Flow.runServer`. Implementing this trait is
  * for integrating a durable database like PostgreSQL: you provide the abstract methods, 12 on `FlowStore` and 8 on
  * [[FlowStore.Claimed]] (six verbs and two accessors, `state` and `satisfied`), 20 in all, and get crash recovery, multi-executor
  * coordination, and exactly-once field writes for free.
  *
  * Each FlowStore instance is a stateless client over a shared backing database. All coordination (claiming executions, preventing
  * duplicates, ensuring ordering) happens through atomic operations in the database. The in-memory implementation uses `AtomicRef` as the
  * database and two channels to wake blocked pollers, one carrying ordinary writes and one carrying registrations, which
  * [[FlowStore.claimReady]] treats differently.
  *
  * **The trait splits in two, and which half a verb is on says who may call it.** `FlowStore` carries the readers and the three
  * administrative writes anybody may make ([[FlowStore.createExecutionIfAbsent]], [[FlowStore.signal]], [[FlowStore.requestCancel]]).
  * Every write made while RUNNING an execution is a method on [[FlowStore.Claimed]], the handle [[FlowStore.claimReady]] hands back, and
  * the store judges each one against the claim it was presented under. A write that carries no evidence of the claim it was made under
  * cannot be refused by anything except the writer's own good manners, which is what this split removes.
  *
  * The key concept is the **claim lease**: `claimReady` atomically finds ready executions and assigns them to an executor with a
  * time-limited lease and a generation token. If the executor dies, the lease expires and `claimReady` hands the execution to another
  * executor under a NEW token, and every write the dead executor still had in flight is refused. The engine renews leases periodically to
  * keep long-running steps alive.
  *
  * Every method carries [[FlowStoreException]] as its error channel, so an implementation reports a failure of its backend as a value
  * rather than by panicking. What the engine does with one: a failure while polling is treated as transient, recorded on
  * [[FlowEngine.health]], and retried; a failure while running an execution is recorded there too and leaves the execution exactly as
  * claimable as it was, because a store the engine could not reach is not a verdict on the work. Nothing about such a failure is written
  * to the execution, and the claim is left to lapse rather than released, so the next poll after the lease recovers it. A store that
  * knows a failure is transient marks it [[FlowStoreException.Retryable]].
  *
  * Invariants every implementation must uphold:
  *   - I1: claimReady never returns the same execution to two concurrent callers
  *   - I2: a transition writes its status, its event and its ledger effect atomically, with no window in which a reader sees one without
  *     the others. It binds more than the status writes: the transitions are recording a wait ([[FlowStore.Claimed.recordWait]]),
  *     recording progress and clearing the row it discharges ([[FlowStore.Claimed.recordProgress]]), and ending an attempt by retiring
  *     the rows the execution no longer waits on and consuming the claim ([[FlowStore.Claimed.finish]]). A store that clears a row in a
  *     second write leaves a crash window in which a satisfied row keeps the execution permanently ready, which is the poll spin the
  *     ledger exists to close.
  *   - I3: [[FlowStore.signal]] is an atomic check-and-write against both the field and the lifecycle: exactly one concurrent delivery of
  *     a name wins and every later one is answered [[FlowStore.SignalOutcome.AlreadyDelivered]], and a delivery that arrives when the
  *     execution is already terminal is answered [[FlowStore.SignalOutcome.AlreadyTerminal]] and writes nothing. Reading the status and
  *     then writing is not enough: an execution that terminates between the two accepts a value nobody will ever consume while the
  *     caller is told it succeeded.
  *   - I4: a renewal succeeds only on an ACTIVE, UNEXPIRED claim whose token is the one presented. Expiry is half of that and
  *     supersession is the other half, and naming only supersession is the hole an implementor copies: a claim whose lease ran out while
  *     nobody else took the row still bears the highest token, so an implementation that checked the token alone would let a lapsed
  *     executor renew late and resume writing. An expired claim is dead, and only a new [[FlowStore.claimReady]] generation revives the
  *     execution.
  *   - I5: terminal status (Completed/Failed/Cancelled) cannot revert to a non-terminal status
  *   - I6: read-your-writes consistency within a single caller
  *   - I7: getHistory returns events in append order
  *   - I8: claimReady returns an execution exactly when its lifecycle is not terminal, its claim is free or expired, the caller serves its
  *     version, and ANY of: a cancel request is outstanding, it has no outstanding wait rows, one of its rows is satisfied, or its claim
  *     is active and expired. See [[FlowStore.claimReady]] for what each clause is for.
  *   - I9: a claimed row RECORDS who holds it, until when, and under which generation. `claimReady` stamps the caller's executor id and a
  *     deadline one lease past its own now, and issues a token strictly greater than any that row has carried. The executor id is
  *     recorded so the reporting surfaces can say who is working on an execution; it is NOT a write authority, and no implementation may
  *     judge a write by it. Two workers of one engine share an executor id, so an executor whose lease lapsed while its sibling reclaimed
  *     the row would find a live claim bearing its own name and could not tell the dead generation from the new one. The token is what a
  *     write is judged against.
  *
  * @see
  *   [[kyo.FlowEngine]] The engine that coordinates store operations
  * @see
  *   [[kyo.FlowStore.Claimed]] The capability a claimed execution is written through
  * @see
  *   [[kyo.FlowStore.ExecutionState]] The execution state row
  * @see
  *   [[kyo.FlowStore.FieldData]] Serialized field storage format
  */
abstract class FlowStore:

    // --- Coordination: atomic find-and-claim ---

    /** Atomically find ready executions and claim them. Returns exclusively: no two concurrent callers get the same execution.
      *
      * **Readiness is one total function over everything it depends on**, and the store evaluates all of it. An execution is returned
      * when all four hold:
      *
      *   1. its lifecycle is not terminal, and
      *   1. its claim is free or expired, and
      *   1. the caller SERVES its version, that is, `(flowId, hash)` is in `served`, and
      *   1. any of: a cancel request is outstanding; it has no outstanding wait rows; at least one row's [[Flow.Wake]] is satisfied; or
      *      its claim is active and expired.
      *
      * A row is satisfied by, and only by: [[Flow.Wake.At]] once the STORE's clock has passed the instant, and [[Flow.Wake.OnField]] once
      * that field is present. Both judged by the store against its own state, never by the caller. Which rows the store judged satisfied
      * rides back on [[FlowStore.Claimed.satisfied]], so the caller discharges exactly what the store woke it for rather than re-deciding
      * against a clock of its own.
      *
      * **The third clause stands alone rather than qualifying one arm of the fourth.** Inside the disjunction it leaks twice, because an
      * execution with no rows, or with a cancel request, would then be handed to a caller that cannot resolve its definition and so can
      * neither run it nor unwind it, while holding a perfectly valid claim.
      *
      * **The cancel arm is load-bearing**: without it a suspended execution with a cancel request has no satisfied wait and no executor,
      * so nothing ever claims it and its compensations never run.
      *
      * **The active-and-expired arm is what keeps a crash from wedging an execution permanently.** Rows gate wake-up only when they are
      * a finished attempt's blessed statement of what the execution waits for, and only the transition that ends an attempt makes a claim
      * ABSENT. An active claim that has expired means the last attempt died mid-flight, so its rows may describe waits the execution is no
      * longer in, and no attempt could ever retire them if readiness demanded their satisfaction first: retirement needs the claim the
      * rule would refuse to grant. Return the execution; its replay re-records the real waits and its ending retires the stale ones.
      *
      * **A claim a caller does not receive must not be a claim.** Either the call hands the row over or it leaves the row as it found
      * it: the rows a call returns are exactly the rows whose token it just took. A store that stamps a fresh deadline on a row and then
      * withholds it makes that execution invisible to the whole system for a full lease while its row looks perfectly healthy.
      *
      * Blocks up to `timeout` if nothing is ready, and re-asks as the store's state changes. It answers EARLY AND EMPTY on one change
      * only, a definition being registered: what a caller serves is fixed for the life of the call, so a registration cannot make
      * anything ready for THIS call and the useful answer is to hand control back for a poll with the wider set. Without it an
      * execution held for a version nobody served waits out a whole poll timeout after the definition it needs arrives.
      *
      * **The deadline makes one final attempt and answers what it claimed; an empty answer at the deadline means the row was not
      * ready on that attempt.** Two things become ready with no write to wake anyone: a [[Flow.Wake.At]] row whose instant simply
      * passed, and a claim that simply expired. An implementation that answered empty on the strength of having run out of time would
      * hide either of them until somebody polled again, which is a whole poll cycle of latency on the two paths that have no writer to
      * announce them, and it would make the answer depend on whether the wait or the clock won a race.
      *
      * **An empty answer is therefore never proof that `timeout` elapsed**, and a caller must not read it as one. It is a signal to
      * poll again: re-read what the answer may have changed, which is the served set, and ask again with another BLOCKING call. One
      * wake drives one re-attempt, so an empty answer cannot turn into a spin. Nothing else wakes a blocked call empty: an ordinary
      * write that leaves nothing ready is re-asked inside the call, which keeps waiting.
      */
    def claimReady(
        served: Set[(Flow.Id.Workflow, String)],
        executorId: Flow.Id.Executor,
        lease: Duration,
        limit: Int,
        timeout: Duration
    )(using Frame): Seq[FlowStore.Claimed] < (Async & Abort[FlowStoreException])

    // --- Execution state ---

    /** Create an execution if that id is free, and answer whether this call is the one that created it.
      *
      * The row, its `Created` event, its structural hash and every field the start supplied are one transition, so a crash cannot leave an
      * execution half-seeded: an input written as a separate call after creation is a value the execution may or may not have, and replay
      * cannot tell a missing seed from an input nobody has delivered yet.
      *
      * **An id already taken leaves everything exactly as it was** and answers false. The check and the write are one operation for the
      * same reason: a read followed by a write lets two concurrent starts on one explicit id both pass the check, and a second create that
      * reset the row would wipe a running execution's history, which replay reads to decide what is already done.
      *
      * `fields` are already encoded, because a start seeds inputs of several declared types at once and one call cannot carry a type
      * parameter per field. [[getAllFields]] answers in the same shape.
      */
    def createExecutionIfAbsent(
        executionId: Flow.Id.Execution,
        status: Flow.Status,
        event: Flow.Event,
        hash: String,
        fields: Dict[String, FlowStore.FieldData]
    )(using Frame): Boolean < (Async & Abort[FlowStoreException])

    /** Deliver a value to a named input, and record its arrival, in one transition.
      *
      * **The store judges terminality, because it is the only place that holds both facts at once.** A caller that reads the status,
      * finds it non-terminal and then writes has a window in which the execution finishes in between, and what lands there is a value
      * nobody will ever consume on an execution that is over, with the caller told it succeeded.
      *
      * The answer says WHICH refusal, because the two are different instructions to a caller: [[FlowStore.SignalOutcome.Delivered]] wrote
      * the field and the event, [[FlowStore.SignalOutcome.AlreadyDelivered]] means stop retrying this name, and
      * [[FlowStore.SignalOutcome.AlreadyTerminal]] means stop entirely and read the result. A `Boolean` collapses the last two into one
      * "no", which is the answer a caller can do least with.
      *
      * An input name this execution's definition does not declare, and a value whose type does not match the declared one, are defects in
      * the CALL rather than states of the execution, so they are refused by the engine on its own typed channel before this verb is
      * reached: a caller retrying either unchanged would fail identically forever.
      */
    def signal[V: Tag: Schema](
        executionId: Flow.Id.Execution,
        name: String,
        value: V,
        event: Flow.Event
    )(using Frame): FlowStore.SignalOutcome < (Async & Abort[FlowStoreException])

    /** Ask for an execution to be cancelled, and answer what the ask did.
      *
      * **A request rather than an act.** The terminal status is written after the work stops, because cancelling runs the execution's
      * compensations: an executor observes the request at a node boundary, unwinds, and terminalises at the end of the unwind. So the
      * request has to be carried on the row in between, which is what [[FlowStore.ExecutionState.cancelRequested]] is, and readiness
      * returns a cancel-requested execution regardless of what it is waiting for, since a suspended execution nobody claims never runs
      * its handlers.
      *
      * The answer says which of three things happened rather than collapsing them into a `Boolean`:
      * [[FlowStore.CancelOutcome.Accepted]] means the flag is now set, [[FlowStore.CancelOutcome.AlreadyRequested]] means somebody asked
      * first, and [[FlowStore.CancelOutcome.AlreadyTerminal]] means there is nothing to cancel and carries what the execution ended as.
      * The first two are the difference between "your cancel is in flight" and "someone already asked"; the third is the difference
      * between a caller that should wait and one that should read the terminal result.
      *
      * Requesting a cancel on an execution that does not exist is answered [[FlowStore.CancelOutcome.AlreadyTerminal]] carrying
      * [[Flow.Status.Cancelled]], because a row that is not there cannot be running.
      */
    def requestCancel(
        executionId: Flow.Id.Execution
    )(using Frame): FlowStore.CancelOutcome < (Async & Abort[FlowStoreException])

    /** Read execution state. */
    def getExecution(
        executionId: Flow.Id.Execution
    )(using Frame): Maybe[FlowStore.ExecutionState] < (Async & Abort[FlowStoreException])

    /** List executions for a workflow, optionally filtered.
      *
      * **The filter is evaluated BEFORE pagination**, which is the whole reason it is a parameter rather than something a caller applies
      * to the answer: filtering a returned page answers "the matches among the first 25 rows" to a caller who asked for "the first 25
      * matches", and the two differ by however many non-matching rows the page happened to contain.
      *
      * `limit` bounds how many rows come back; `Absent` means every match. A negative `Present` count is not a meaningful limit, and
      * every implementation answers it the same as `Absent` rather than as an empty page: a caller cannot tell that answer from a
      * workflow that genuinely has nothing matching. `offset` is non-negative.
      */
    def listExecutions(
        flowId: Flow.Id.Workflow,
        filter: Maybe[FlowStore.ExecutionFilter],
        limit: Maybe[Int],
        offset: Int
    )(using Frame): Chunk[FlowStore.ExecutionState] < (Async & Abort[FlowStoreException])

    // --- Fields ---

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

    /** Read paginated event history.
      *
      * `limit` bounds how many events come back; `Absent` means every event. `Absent` rather than an `Int.MaxValue` sentinel for the
      * same request, which would leave every implementation guarding the obvious way of answering `hasMore`: fetching `limit + 1` rows
      * overflows to `Int.MinValue` at that sentinel. A negative `Present` count is not a meaningful limit, and every implementation
      * answers it the same as `Absent`, never as an empty page that still claims another follows: that pair is the one answer a paging
      * caller loops on forever. `offset` is non-negative.
      */
    def getHistory(
        executionId: Flow.Id.Execution,
        limit: Maybe[Int],
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
                Channel.init[Unit](1).map { registrations =>
                    new MemoryFlowStore(ref, channel, registrations)
                }
            }
        }

    // --- The claimed capability ---

    /** The capability to write to one execution, handed over by [[FlowStore.claimReady]] and carrying the generation it was granted under.
      *
      * **One rule decides every write made through this handle, and it has two halves.** A row's claim is either ACTIVE, carrying a
      * token and an expiry, or ABSENT. A write is applied only if the row's claim is active, the presented token equals the active
      * claim's token, and the active claim has not expired against the store's own clock. `claimReady` replaces whatever claim the row
      * has with an active one carrying a token strictly greater than any the row has carried; [[Claimed.finish]] makes the claim absent,
      * and it is the only verb that does; expiry leaves the claim active and expired, which is what lets `claimReady` replace it.
      *
      * Both halves, never either. The token alone refuses a SUPERSEDED writer, one whose row somebody else claimed, and says nothing
      * about an executor whose lease expired while nobody else claimed the row, which still holds the highest token. The expiry alone
      * says nothing about a writer whose row was taken while its lease still had time to run.
      *
      * **Applied means the whole transition, including the event.** A refused write leaves NOTHING: no status change, no field, no
      * history row, no cleared or retired wait. History is where a half-refusal does the most damage, because it is append-only and the
      * operator surfaces treat it as authoritative for failure, so an execution its owner completed must not carry a `Failed` event
      * written by an executor that no longer held it.
      *
      * **An attempt that ends without calling `finish` leaves the claim to expire.** That is not a leak: readiness reads an ACTIVE AND
      * EXPIRED claim as "the last attempt died mid-flight", returns the execution regardless of its rows, and lets the replay heal the
      * ledger. Releasing there instead would bless a partial ledger as a finished attempt's statement, which is a permanent wedge rather
      * than a slow recovery.
      *
      * A store author implements this trait beside [[FlowStore]] and hands instances back from `claimReady`. The handle carries the
      * claimed row's snapshot so the caller can resolve the execution's definition without a read of its own.
      */
    trait Claimed:

        /** The claimed row as it stood when the claim was granted.
          *
          * A snapshot, deliberately: it carries the id, the `flowId` and the structural hash the caller resolves the definition with, so
          * a batch of claimed rows costs no reads at all. It cannot see a change that arrived after the claim, which is why a cancel
          * request is observed by reading the row fresh through [[FlowStore.getExecution]] at a node boundary rather than from here.
          */
        def state: ExecutionState

        /** The paths of the wait rows the store judged satisfied when it granted this claim.
          *
          * Empty when the execution was returned for a reason that is not a row (no rows at all, a cancel request, or a claim that
          * expired mid-flight). The caller discharges from this rather than re-judging the rows against a clock of its own: the store is
          * the clock, and an engine whose clock runs behind a third-party store's would otherwise refuse to discharge what readiness
          * woke it for, re-park, and be handed the execution again at full speed until it caught up.
          */
        def satisfied: Set[String]

        /** Append an event without changing anything else. */
        def appendEvent(event: Flow.Event)(using Frame): WriteOutcome < (Async & Abort[FlowStoreException])

        /** Record that the node at `path` is waiting for `wake`, and append the event that says so, in one transition.
          *
          * **Put-if-absent in shape, and that is load-bearing rather than a convenience.** A row that already exists is left exactly as
          * it is: a sleep re-recorded on a later attempt keeps its ORIGINAL deadline, and a store that overwrote would push the deadline
          * forward on every replay and turn a finite sleep into one that never fires. The EVENT is appended either way, because a
          * replayed re-record is something an operator reading the history should see; only the row is left alone.
          *
          * **Recording a wait does not release the claim, and the two must stay separate.** Rows are per node, so a composition
          * waiting on two conditions at once is two rows; each parking branch calls this while the claim is still held, and the
          * transition that ENDS the attempt releases once. A verb that released on the first branch's suspension would make the second
          * branch's row unwritable by its own acceptance rule, so `race(input, sleep)` would record one row instead of two and lose
          * either the timeout or the wake depending on which branch parked first.
          */
        def recordWait(path: String, wake: Flow.Wake, event: Flow.Event)(using
            Frame
        ): WriteOutcome < (Async & Abort[FlowStoreException])

        /** Extend the lease on this claim. Answers false once the claim is gone, by expiry or by supersession (I4). */
        def renewClaim(lease: Duration)(using Frame): Boolean < (Async & Abort[FlowStoreException])

        /** Move the execution's LIFECYCLE mid-attempt, with the event that says so, in one transition.
          *
          * The one lifecycle transition an attempt makes while it is still running, which is entering [[Flow.Status.Compensating]] with
          * the cause that started the unwind. It refuses a TERMINAL status, and [[finish]] refuses a non-terminal one: the two verbs
          * partition the lifecycle, because a terminal write that bypassed `finish` would bypass the retirement and the claim
          * consumption that make terminality safe.
          */
        def updateStatus(status: Flow.Status, event: Flow.Event)(using
            Frame
        ): StatusOutcome < (Async & Abort[FlowStoreException])

        /** Record a node's progress: write its field under `path`, append its completion event, and clear the wait rows under that path,
          * in one transition.
          *
          * **One transition, all three parts or none.** A field and its completion event written separately leave, on a store that
          * fails between them, a value durably present and a history saying the step never finished; replay decides from the FIELD and
          * the operator surface decides from HISTORY, so that step never runs again and the surface reports it unfinished forever. The
          * clearing rides here for the same reason: a satisfied row that is never removed keeps the execution ready after its branch has
          * moved on. Clearing a path with no row is a no-op, which is what makes the discharge idempotent across replays.
          *
          * **Absent means no field is written, never an absent marker.** A node with no value of its own (a step, or an input being
          * marked discharged) records its completion and its clearing and touches no field. For the input case a marker would clobber
          * the value that was signalled.
          *
          * **Write-once per path, with the refusal split by what the write carries.** A VALUE-CARRYING write is refused when the path
          * already carries a field. A VALUELESS one is refused only by its own recorded completion and never by field presence, because
          * an input's discharge is by construction a valueless progress at a path the signalled value already occupies, and refusing it
          * on field presence would silently delete the discharge event and the row clearing. A refused write is answered
          * [[ProgressOutcome.AlreadyRecorded]] and writes nothing at all.
          *
          * No legitimate node writes its own path twice: iterations, fan-out items and a dispatch's choice all key distinct paths. What
          * the rule buys is the one race the design blesses, two live branches of a `race` completing a shared output name: the first
          * writer decides the field and the loser is answered already-recorded, which is the same answer replay derives from the field's
          * presence, settled by the store instead of by whichever write the scheduler happened to land last.
          *
          * A store must therefore record, per execution, the paths at which a valueless progress has been recorded. A field cannot serve
          * as that mark, since a valueless write is exactly the one that writes no field.
          */
        def recordProgress[V](path: String, value: Maybe[V], event: Flow.Event)(using
            Tag[V],
            Schema[V],
            Frame
        ): ProgressOutcome < (Async & Abort[FlowStoreException])

        /** The valueless progress, for a node whose completion carries no value of its own.
          *
          * Delegates to the canonical write with an absent value. The type parameter it picks is arbitrary and unobservable, because an
          * absent value writes no field and the tag is only ever read beside one.
          */
        final def recordProgress(path: String, event: Flow.Event)(using
            Frame
        ): ProgressOutcome < (Async & Abort[FlowStoreException]) =
            recordProgress[String](path, Maybe.empty, event)

        /** End this attempt: write what happened, settle the wait ledger, and make the claim absent, in one transition.
          *
          * The only verb that makes a claim absent, which is what gives the absence its meaning: when a row's claim is absent, its rows
          * are a finished attempt's blessed statement of what the execution is waiting for, and readiness may gate on them.
          *
          * [[Claimed.Outcome.Terminal]] writes the terminal status and its event and retires EVERY row, because a terminal execution
          * waits for nothing. It refuses a non-terminal status, for the reason [[updateStatus]] refuses a terminal one.
          * [[Claimed.Outcome.Suspended]] writes no status, keeps exactly the rows named in `waitingOn`, and retires the rest: a race's
          * losing branch, a `zip` branch abandoned when its sibling failed, a straggler interrupted between writing its row and being
          * cancelled. None of them was discharged, so no progress write clears them, and a row that outlives the branch that wrote it
          * keeps the execution ready the moment it happens to be satisfiable.
          */
        def finish(outcome: Claimed.Outcome)(using Frame): StatusOutcome < (Async & Abort[FlowStoreException])

    end Claimed

    object Claimed:

        /** What an attempt writes as it ends. Every outcome makes the claim absent in the same transition. */
        enum Outcome derives CanEqual:

            /** The execution is over. Writes the pair and retires every row. */
            case Terminal(status: Flow.Status, event: Flow.Event)

            /** It parked. Writes no status and keeps exactly the paths the attempt ended parked on. */
            case Suspended(waitingOn: Set[String])
        end Outcome
    end Claimed

    /** What the store did with a write presented through a claim. */
    enum WriteOutcome derives CanEqual:

        /** The claim was active, unexpired and this generation, and the whole transition landed. */
        case Applied

        /** The row's claim is absent, expired, or a later generation's. Nothing was written. */
        case ClaimLost
    end WriteOutcome

    /** What the store did with a write that moves the lifecycle: [[Claimed.updateStatus]] or [[Claimed.finish]]. */
    enum StatusOutcome derives CanEqual:

        /** The claim held and the status was on this verb's side of the partition. The whole transition landed. */
        case Applied

        /** The row's claim is absent, expired, or a later generation's. Nothing was written. */
        case ClaimLost

        /** The status is on the other verb's side of the lifecycle partition: a terminal status handed to
          * [[Claimed.updateStatus]], or a non-terminal one handed to [[Claimed.finish]]. Nothing was written.
          */
        case WrongSideOfTerminal
    end StatusOutcome

    /** What the store did with a [[Claimed.recordProgress]] write. */
    enum ProgressOutcome derives CanEqual:

        /** The field, the completion event and the clearing all landed. */
        case Recorded

        /** This path already carries what the write would record, so the first writer stands. Nothing was written. */
        case AlreadyRecorded

        /** The row's claim is absent, expired, or a later generation's. Nothing was written. */
        case ClaimLost
    end ProgressOutcome

    /** What [[FlowStore.signal]] did with a delivery. */
    enum SignalOutcome derives CanEqual:

        /** The field was written and its arrival recorded, in one transition. */
        case Delivered

        /** This input already has a value, so the caller should stop retrying this name. */
        case AlreadyDelivered

        /** The execution is over, so the caller should stop entirely and read the result it ended with. */
        case AlreadyTerminal(status: Flow.Status)
    end SignalOutcome

    /** What [[FlowStore.requestCancel]] did with an ask. */
    enum CancelOutcome derives CanEqual:

        /** The request is now outstanding on the row, and the next attempt observes it and unwinds. */
        case Accepted

        /** Somebody asked first and the request is still outstanding. */
        case AlreadyRequested

        /** There is nothing to cancel, and this is what the execution ended as. */
        case AlreadyTerminal(status: Flow.Status)
    end CancelOutcome

    // --- Types ---

    /** A context field entry: serialized value + runtime type tag.
      *
      * The tag is stored erased, because a store holds fields of every type a workflow declares and one row type cannot be parameterised
      * per field. A store implementing [[FlowStore.Claimed.recordProgress]] is handed a `Tag[V]`, which is not a `Tag[Any]` (`Tag` is
      * invariant), so the companion's [[FieldData.apply]] does the widening once here rather than leaving every implementation to write
      * the cast for it.
      *
      * **A field is written once and then stands.** The two writers are the start's seeded inputs, which land with the row itself, and
      * [[FlowStore.Claimed.recordProgress]], which is refused when the path already carries one; a delivery through
      * [[FlowStore.signal]] is refused the same way. So a reader that has seen a value for a name has seen the value that name will
      * have, which is the fact replay rests on when it decides a node is already done from the field alone.
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

    /** Execution state row.
      *
      * The wait rows and the cancel request ride the row every reader already holds, rather than needing a read of their own: a caller
      * listing a page of executions would otherwise issue one ledger read per row.
      *
      * @param status
      *   the persisted LIFECYCLE, and nothing else. `Running` spans working and waiting; what an execution is waiting FOR is `waits`.
      * @param executor
      *   who holds the claim, recorded so a reporting surface can say who is working on an execution. It is not a write authority: two
      *   workers of one engine share an id, so a write is judged against `claimToken` and never against this.
      * @param claimToken
      *   the generation the active claim was granted under, present exactly when the claim is active. Each `claimReady` issues one
      *   strictly greater than any this row has carried, and [[FlowStore.Claimed.finish]] is what makes it absent again.
      * @param waits
      *   the execution's outstanding wait rows, keyed by the composition path of the node that is waiting
      * @param cancelRequested
      *   whether somebody has asked for this execution to be cancelled. A request rather than an act: the executor observes it at a node
      *   boundary, unwinds, and terminalises some time later, so this is what a caller who cancelled and then looked sees.
      */
    case class ExecutionState(
        executionId: Flow.Id.Execution,
        flowId: Flow.Id.Workflow,
        status: Flow.Status,
        executor: Maybe[Flow.Id.Executor] = Maybe.empty,
        claimExpiry: Maybe[Instant] = Maybe.empty,
        hash: String,
        created: Instant,
        updated: Instant,
        waits: Dict[String, Flow.Wake] = Dict.empty,
        cancelRequested: Boolean = false,
        claimToken: Maybe[Long] = Maybe.empty
    ) derives CanEqual, Schema:

        /** Structural, because the generated comparison is not structural on the one field that carries a collection.
          *
          * **Two rows describing the same execution are equal.** That is what `CanEqual` on this type advertises and what a store's
          * own conformance test rests on: read a row, do something that should change nothing, read again, compare. A generated
          * `equals` would compare `waits` with the `Dict`'s own `==`, and under its threshold a `Dict` is a span over an array, so
          * two ledgers holding the same rows are equal only when they happen to share that array. A store that rebuilds the row,
          * which is every store over a database, never shares it, so the comparison would answer false for a reason that has nothing
          * to do with the store. [[Dict.is]] is the structural test and this uses it.
          *
          * `hashCode` goes with it, and is order-independent over the ledger's entries for the same reason `is` is: two ledgers that
          * differ only in the order their rows were added are equal, so they must hash alike.
          */
        override def equals(other: Any): Boolean =
            other match
                case that: ExecutionState =>
                    executionId == that.executionId &&
                    flowId == that.flowId &&
                    status == that.status &&
                    executor == that.executor &&
                    claimExpiry == that.claimExpiry &&
                    hash == that.hash &&
                    created == that.created &&
                    updated == that.updated &&
                    cancelRequested == that.cancelRequested &&
                    claimToken == that.claimToken &&
                    waits.is(that.waits)
                case _ => false

        override def hashCode: Int =
            // Summed rather than folded in sequence, so the ledger's iteration order cannot reach the answer.
            val ledger = waits.foldLeft(0)((acc, path, wake) => acc + (path.hashCode * 31 + wake.hashCode))
            var result = executionId.hashCode
            result = result * 31 + flowId.hashCode
            result = result * 31 + status.hashCode
            result = result * 31 + executor.hashCode
            result = result * 31 + claimExpiry.hashCode
            result = result * 31 + hash.hashCode
            result = result * 31 + created.hashCode
            result = result * 31 + updated.hashCode
            result = result * 31 + cancelRequested.hashCode
            result = result * 31 + claimToken.hashCode
            result * 31 + ledger
        end hashCode
    end ExecutionState

    /** What [[FlowStore.listExecutions]] can be asked for, evaluated by the store before it paginates.
      *
      * Separate vocabulary from [[Flow.Status]] on purpose, because the two answer different questions. A status is what one execution IS;
      * a filter is what a caller is looking for, and what an operator looks for includes facts that are not the lifecycle: what an
      * execution is waiting on, whether somebody has asked for it to stop, and whether any engine still serves its version.
      *
      * A wait-kind arm matches when ANY of the execution's rows does, so plurality is matches-any rather than a tiebreak.
      */
    enum ExecutionFilter derives CanEqual:
        case Running
        case Compensating
        case Completed
        case Cancelled

        /** Failed executions, narrowed to one [[Flow.Status.Failed.kind]] when a kind is given. */
        case Failed(kind: Maybe[String] = Maybe.empty)

        /** Executions holding at least one sleep row.
          *
          * Payload-free, and the asymmetry with [[WaitingForInput]] is deliberate: input names are the CALLER's vocabulary, since `signal`
          * takes them, while a sleep's name is the flow author's internal business.
          */
        case Sleeping

        /** Executions holding at least one input-wait row, narrowed to the row for `name` when a name is given. */
        case WaitingForInput(name: Maybe[String] = Maybe.empty)

        /** Executions somebody has asked to cancel that have not finished unwinding yet. */
        case Cancelling

        /** Non-terminal executions whose structural hash is not among `servedHashes`.
          *
          * The predicate behind [[kyo.FlowEngine.parked]], pushed into the store so the pagination happens after the filter rather than
          * before it. The served set is one engine's own, which is why this arm has no place in a wire vocabulary.
          */
        case Orphaned(servedHashes: Set[String])
    end ExecutionFilter

    /** Paginated event history. */
    case class HistoryPage(events: Chunk[Flow.Event], hasMore: Boolean) derives CanEqual

end FlowStore
