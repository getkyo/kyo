package kyo.internal

import kyo.*

/** The reference store's whole database.
  *
  * @param progress
  *   the paths at which a valueless [[kyo.FlowStore.Claimed.recordProgress]] has landed, per execution. A field cannot carry that mark,
  *   because a valueless write is exactly the one that writes no field, and write-once still has to refuse a second one.
  * @param nextToken
  *   the next claim generation to issue. One counter for the whole store, which satisfies "strictly greater than any token this row has
  *   carried" for every row at once and survives a claim being made absent, where a token kept on the row alone would not.
  */
private[kyo] case class MemoryData(
    executions: Dict[Flow.Id.Execution, FlowStore.ExecutionState],
    fields: Dict[(Flow.Id.Execution, String), FlowStore.FieldData],
    events: Dict[Flow.Id.Execution, Chunk[Flow.Event]],
    workflows: Dict[Flow.Id.Workflow, FlowEngine.WorkflowInfo],
    progress: Dict[Flow.Id.Execution, Set[String]],
    nextToken: Long
)

private[kyo] object MemoryData:
    val empty = MemoryData(Dict.empty, Dict.empty, Dict.empty, Dict.empty, Dict.empty, 1L)

private[kyo] class MemoryFlowStore(
    ref: AtomicRef[MemoryData],
    channel: Channel[Unit],
    registrations: Channel[Unit]
)(using Frame) extends FlowStore:

    private def notify(using Frame): Unit < Sync =
        Abort.run[Closed](channel.offer(())).unit

    /** A registration is a different kind of change from a write about an execution, and a blocked poll answers it differently.
      *
      * A write may make an execution ready for the caller that is waiting, so that caller re-asks and keeps waiting. A registration
      * cannot: what a caller serves is fixed for the life of one `claimReady` call, so the only useful answer is to hand control back
      * and let the next poll ask with the wider set.
      */
    private def notifyRegistration(using Frame): Unit < Sync =
        Abort.run[Closed](registrations.offer(())).unit

    /** One atomic transition over the whole database, answering what it did.
      *
      * `f` is pure and total: it is handed the current snapshot and the store's own clock, and answers the next snapshot beside the
      * value the caller sees. Returning the snapshot it was given means nothing changed, which skips the compare-and-set and the wake,
      * so a refused write costs no notification and cannot be told from having never been issued.
      */
    private def transact[A](f: (MemoryData, Instant) => (MemoryData, A))(using Frame): A < Async =
        Clock.nowWith { now =>
            def attempt: A < Sync =
                ref.use { data =>
                    val (next, answer) = f(data, now)
                    if next eq data then answer
                    else
                        ref.compareAndSet(data, next).map {
                            case true  => notify.andThen(answer)
                            case false => attempt
                        }
                    end if
                }
            attempt
        }

    /** Whether a write presented under `token` is applied, which is the whole of the acceptance rule.
      *
      * Both halves, never either. The token alone refuses a superseded writer and lets a lapsed one through; the expiry alone refuses a
      * lapsed writer and lets a superseded one through while its lease still runs. The executor id is not consulted at all: two workers
      * of one engine share one, so a claim bearing the writer's own name proves nothing about which generation wrote it.
      */
    private def accepted(ex: FlowStore.ExecutionState, token: Long, now: Instant): Boolean =
        ex.claimToken == Maybe(token) && ex.claimExpiry.exists(e => now < e)

    private def eventsOf(data: MemoryData, eid: Flow.Id.Execution): Chunk[Flow.Event] =
        data.events.getOrElse(eid, Chunk.empty)

    /** The capability over one claimed row, carrying the generation it was granted under.
      *
      * Every verb runs one [[transact]] that re-reads the row, so the acceptance rule is evaluated against the store's state at WRITE
      * time rather than against the snapshot the claim was granted with. That is what makes a write issued before a release and
      * delivered after it refusable: by then the claim is absent, and absence fails the rule whenever the write was issued.
      */
    private class ClaimedRow(
        val state: FlowStore.ExecutionState,
        val satisfied: Set[String],
        token: Long
    ) extends FlowStore.Claimed:

        private val eid = state.executionId

        def appendEvent(event: Flow.Event)(using Frame): FlowStore.WriteOutcome < Async =
            transact { (data, now) =>
                data.executions.get(eid) match
                    case Present(ex) if accepted(ex, token, now) =>
                        (
                            data.copy(events = data.events.update(eid, eventsOf(data, eid) :+ event)),
                            FlowStore.WriteOutcome.Applied
                        )
                    case _ => (data, FlowStore.WriteOutcome.ClaimLost)
            }

        def recordWait(path: String, wake: Flow.Wake, event: Flow.Event)(using Frame): FlowStore.WriteOutcome < Async =
            transact { (data, now) =>
                data.executions.get(eid) match
                    case Present(ex) if accepted(ex, token, now) =>
                        // The row is written only if absent, so a sleep re-recorded on a later attempt keeps the deadline it was
                        // first given. The event is appended either way: a replay that re-records is something the history shows.
                        val waits = if ex.waits.contains(path) then ex.waits else ex.waits.update(path, wake)
                        (
                            data.copy(
                                executions = data.executions.update(eid, ex.copy(waits = waits, updated = now)),
                                events = data.events.update(eid, eventsOf(data, eid) :+ event)
                            ),
                            FlowStore.WriteOutcome.Applied
                        )
                    case _ => (data, FlowStore.WriteOutcome.ClaimLost)
            }

        def renewClaim(lease: Duration)(using Frame): Boolean < Async =
            transact { (data, now) =>
                data.executions.get(eid) match
                    case Present(ex) if accepted(ex, token, now) =>
                        (
                            data.copy(executions =
                                data.executions.update(eid, ex.copy(claimExpiry = Maybe(now + lease), updated = now))
                            ),
                            true
                        )
                    case _ => (data, false)
            }

        def updateStatus(status: Flow.Status, event: Flow.Event)(using Frame): FlowStore.StatusOutcome < Async =
            transact { (data, now) =>
                data.executions.get(eid) match
                    case Present(ex) if accepted(ex, token, now) =>
                        if status.isTerminal then (data, FlowStore.StatusOutcome.WrongSideOfTerminal)
                        else
                            (
                                data.copy(
                                    executions = data.executions.update(eid, ex.copy(status = status, updated = now)),
                                    events = data.events.update(eid, eventsOf(data, eid) :+ event)
                                ),
                                FlowStore.StatusOutcome.Applied
                            )
                    case _ => (data, FlowStore.StatusOutcome.ClaimLost)
            }

        def recordProgress[V](path: String, value: Maybe[V], event: Flow.Event)(using
            Tag[V],
            Schema[V],
            Frame
        ): FlowStore.ProgressOutcome < Async =
            transact { (data, now) =>
                data.executions.get(eid) match
                    case Present(ex) if accepted(ex, token, now) =>
                        val key      = (eid, path)
                        val recorded = data.progress.getOrElse(eid, Set.empty)
                        // A value-carrying write is refused by the field it would overwrite; a valueless one only by its own
                        // recorded completion, because an input's discharge always lands on a path the signalled value occupies.
                        val refused = value match
                            case Present(_) => data.fields.contains(key)
                            case _          => recorded.contains(path)
                        if refused then (data, FlowStore.ProgressOutcome.AlreadyRecorded)
                        else
                            val withField = value match
                                case Present(v) =>
                                    data.fields.update(key, FlowStore.FieldData(summon[Schema[V]].encodeString[Json](v), Tag[V]))
                                case _ => data.fields
                            (
                                data.copy(
                                    executions = data.executions.update(eid, ex.copy(waits = ex.waits.remove(path), updated = now)),
                                    events = data.events.update(eid, eventsOf(data, eid) :+ event),
                                    fields = withField,
                                    progress = data.progress.update(eid, recorded + path)
                                ),
                                FlowStore.ProgressOutcome.Recorded
                            )
                        end if
                    case _ => (data, FlowStore.ProgressOutcome.ClaimLost)
            }

        def finish(outcome: FlowStore.Claimed.Outcome)(using Frame): FlowStore.StatusOutcome < Async =
            transact { (data, now) =>
                data.executions.get(eid) match
                    case Present(ex) if accepted(ex, token, now) =>
                        // The claim becomes absent in the same transition, and this is the only verb that makes it so: an absent
                        // claim is what tells readiness the rows below are a finished attempt's statement rather than a guess.
                        def released(next: FlowStore.ExecutionState) =
                            next.copy(executor = Maybe.empty, claimExpiry = Maybe.empty, claimToken = Maybe.empty, updated = now)
                        outcome match
                            case FlowStore.Claimed.Outcome.Terminal(status, event) =>
                                if !status.isTerminal then (data, FlowStore.StatusOutcome.WrongSideOfTerminal)
                                else
                                    (
                                        data.copy(
                                            executions =
                                                data.executions.update(eid, released(ex.copy(status = status, waits = Dict.empty))),
                                            events = data.events.update(eid, eventsOf(data, eid) :+ event)
                                        ),
                                        FlowStore.StatusOutcome.Applied
                                    )
                            case FlowStore.Claimed.Outcome.Suspended(waitingOn) =>
                                (
                                    data.copy(executions =
                                        data.executions.update(
                                            eid,
                                            released(ex.copy(waits = ex.waits.filter((path, _) => waitingOn.contains(path))))
                                        )
                                    ),
                                    FlowStore.StatusOutcome.Applied
                                )
                        end match
                    case _ => (data, FlowStore.StatusOutcome.ClaimLost)
            }
    end ClaimedRow

    // --- Coordination ---

    def claimReady(
        served: Set[(Flow.Id.Workflow, String)],
        executorId: Flow.Id.Executor,
        lease: Duration,
        limit: Int,
        timeout: Duration
    )(using Frame): Seq[FlowStore.Claimed] < Async =
        // A row nobody is working on RIGHT NOW, judged by whether the claim is live rather than by whose name is on it.
        // Judging it by identity strands executions: an executor that skips a row without releasing it leaves its own
        // name there, so every later poll by that executor re-claims the row, pushes its expiry forward by a full
        // lease, and then filters it out of its own answer. Nobody receives it and no competitor can take it, because
        // from outside the lease never lapses.
        def unclaimed(ex: FlowStore.ExecutionState, now: Instant): Boolean =
            ex.claimExpiry.forall(e => !(now < e))

        // The last attempt died without ending: it never released, so the claim is still active, and its deadline has passed.
        // Its rows describe waits the execution may no longer be in, and nothing but a new attempt can heal them.
        def diedMidFlight(ex: FlowStore.ExecutionState, now: Instant): Boolean =
            ex.claimToken.isDefined && ex.claimExpiry.exists(e => !(now < e))

        def satisfiedBy(data: MemoryData, ex: FlowStore.ExecutionState, wake: Flow.Wake, now: Instant): Boolean =
            wake match
                case Flow.Wake.At(instant)   => !(now < instant)
                case Flow.Wake.OnField(name) => data.fields.contains((ex.executionId, name))

        def satisfiedPaths(data: MemoryData, ex: FlowStore.ExecutionState, now: Instant): Set[String] =
            ex.waits.foldLeft(Set.empty[String]) { (acc, path, wake) =>
                if satisfiedBy(data, ex, wake, now) then acc + path else acc
            }

        def ready(data: MemoryData, ex: FlowStore.ExecutionState, now: Instant): Boolean =
            // The version gate is a separate universal clause rather than one arm of the disjunction below: an execution
            // with no rows, or with a cancel request, would otherwise be handed to an executor that can neither run it nor
            // unwind it, because the definition it was started under is not one this caller serves.
            served.contains((ex.flowId, ex.hash)) &&
                !ex.status.isTerminal &&
                unclaimed(ex, now) &&
                (ex.cancelRequested ||
                    ex.waits.isEmpty ||
                    ex.waits.exists((_, wake) => satisfiedBy(data, ex, wake, now)) ||
                    diedMidFlight(ex, now))

        // The transition answers WHAT IT CLAIMED, and the handles it hands back are exactly the rows whose token it just
        // took. Deriving that afterwards from a second read cannot be made correct: the answer to "which of these are
        // mine" is the same whether this call claimed the row or a previous one did.
        def claim(data: MemoryData, now: Instant): (MemoryData, Seq[FlowStore.Claimed]) =
            val due = data.executions.toChunk.map(_._2).filter(ready(data, _, now))
                .sortBy(_.created)(using Ordering[Instant]).take(limit)
            if due.isEmpty then (data, Seq.empty)
            else
                val claimed = due.zipWithIndex.map { (ex, i) =>
                    val token = data.nextToken + i
                    val row = ex.copy(
                        executor = Maybe(executorId),
                        claimExpiry = Maybe(now + lease),
                        claimToken = Maybe(token),
                        updated = now
                    )
                    (row, new ClaimedRow(row, satisfiedPaths(data, ex, now), token))
                }
                (
                    data.copy(
                        executions = claimed.foldLeft(data.executions)((acc, entry) => acc.update(entry._1.executionId, entry._1)),
                        nextToken = data.nextToken + claimed.size
                    ),
                    claimed.map((_, handle) => handle)
                )
            end if
        end claim

        // A poll that claims nothing writes nothing, so it cannot extend a deadline it is not handing back. Writing the
        // fresh expiry and then withholding the row makes an execution invisible to the whole system for a full lease
        // while its row looks perfectly healthy.
        def tryOnce: Seq[FlowStore.Claimed] < Sync =
            Clock.nowWith { now =>
                def attempt: Seq[FlowStore.Claimed] < Sync =
                    ref.use { data =>
                        val (next, claimed) = claim(data, now)
                        if claimed.isEmpty then Seq.empty
                        else
                            ref.compareAndSet(data, next).map {
                                case true  => claimed
                                case false => attempt
                            }
                        end if
                    }
                attempt
            }

        // What ended a blocked wait. The claim is made OUTSIDE the race that produces this, which is what keeps the call's
        // own racers from interrupting a claiming branch between its commit and its hand-over: a claim a caller does not
        // receive is a row nobody can take for a whole lease, and the rule is that either the call hands the row over or it
        // leaves the row as it found it.
        enum Woke derives CanEqual:
            case Write, Registration, TimedOut

        def awaitChange(remaining: Duration): Woke < Async =
            Abort.run[Closed] {
                Async.race(
                    Async.delay(remaining)(Woke.TimedOut),
                    channel.take.andThen(Woke.Write),
                    registrations.take.andThen(Woke.Registration)
                )
            }.map(_.getOrElse(Woke.TimedOut))

        Clock.nowWith { start =>
            val deadline = start + timeout
            def poll: Seq[FlowStore.Claimed] < Async =
                tryOnce.map { claimed =>
                    if claimed.nonEmpty then claimed
                    else
                        Clock.nowWith { now =>
                            if !(now < deadline) then Seq.empty
                            else
                                awaitChange(deadline - now).map {
                                    // A write about an execution may have made one ready for this caller, so the poll
                                    // re-asks and keeps waiting until its own deadline.
                                    case Woke.Write => poll
                                    // A registration cannot make anything ready for THIS call, whose served set was fixed
                                    // when it was made, so the answer goes back to the caller: the next poll asks with the
                                    // wider set. Without it an execution held for a version nobody served waits out a whole
                                    // poll timeout after the definition it needs arrives.
                                    case Woke.Registration => tryOnce
                                    // The deadline ASKS ONE MORE TIME rather than answering empty on the strength of having
                                    // run out of time. Two things become ready without a write to wake anyone: a sleep row
                                    // whose instant simply passed, and a claim that simply expired, and a caller that
                                    // answered empty while one of them sat ready would hide it for a whole poll cycle. The
                                    // rule the call is held to says either it hands the row over or it leaves the row as it
                                    // found it, and it admits no exception for the last instant of the wait.
                                    case Woke.TimedOut => tryOnce
                                }
                        }
                }
            poll
        }
    end claimReady

    // --- Execution state ---

    def createExecutionIfAbsent(
        executionId: Flow.Id.Execution,
        status: Flow.Status,
        event: Flow.Event,
        hash: String,
        fields: Dict[String, FlowStore.FieldData]
    )(using Frame): Boolean < Async =
        transact { (data, now) =>
            if data.executions.contains(executionId) then (data, false)
            else
                val ex = FlowStore.ExecutionState(
                    executionId,
                    event.flowId,
                    status,
                    hash = hash,
                    created = now,
                    updated = now
                )
                (
                    data.copy(
                        executions = data.executions.update(executionId, ex),
                        events = data.events.update(executionId, Chunk(event)),
                        fields = fields.foldLeft(data.fields)((acc, name, fd) => acc.update((executionId, name), fd))
                    ),
                    true
                )
            end if
        }

    def signal[V: Tag: Schema](
        executionId: Flow.Id.Execution,
        name: String,
        value: V,
        event: Flow.Event
    )(using Frame): FlowStore.SignalOutcome < Async =
        transact { (data, now) =>
            val key = (executionId, name)
            data.executions.get(executionId) match
                // A row that is not there can never consume a value, and no retry will change that. `Cancelled` is the one
                // terminal status that does not claim the execution produced a result or failed to.
                case Absent                                   => (data, FlowStore.SignalOutcome.AlreadyTerminal(Flow.Status.Cancelled))
                case Present(ex) if ex.status.isTerminal      => (data, FlowStore.SignalOutcome.AlreadyTerminal(ex.status))
                case Present(ex) if data.fields.contains(key) => (data, FlowStore.SignalOutcome.AlreadyDelivered)
                case Present(ex) =>
                    (
                        data.copy(
                            fields =
                                data.fields.update(key, FlowStore.FieldData(summon[Schema[V]].encodeString[Json](value), Tag[V])),
                            events = data.events.update(executionId, eventsOf(data, executionId) :+ event),
                            executions = data.executions.update(executionId, ex.copy(updated = now))
                        ),
                        FlowStore.SignalOutcome.Delivered
                    )
            end match
        }

    def requestCancel(executionId: Flow.Id.Execution)(using Frame): FlowStore.CancelOutcome < Async =
        transact { (data, now) =>
            data.executions.get(executionId) match
                case Absent                              => (data, FlowStore.CancelOutcome.AlreadyTerminal(Flow.Status.Cancelled))
                case Present(ex) if ex.status.isTerminal => (data, FlowStore.CancelOutcome.AlreadyTerminal(ex.status))
                case Present(ex) if ex.cancelRequested   => (data, FlowStore.CancelOutcome.AlreadyRequested)
                case Present(ex) =>
                    (
                        data.copy(executions =
                            data.executions.update(executionId, ex.copy(cancelRequested = true, updated = now))
                        ),
                        FlowStore.CancelOutcome.Accepted
                    )
            end match
        }

    def getExecution(
        executionId: Flow.Id.Execution
    )(using Frame): Maybe[FlowStore.ExecutionState] < Async =
        ref.use(_.executions.get(executionId))

    /** Whether one execution answers one filter, judged from the row and the fields the store already holds. */
    private def matches(filter: FlowStore.ExecutionFilter, ex: FlowStore.ExecutionState): Boolean =
        filter match
            case FlowStore.ExecutionFilter.Running   => ex.status == Flow.Status.Running
            case FlowStore.ExecutionFilter.Completed => ex.status == Flow.Status.Completed
            case FlowStore.ExecutionFilter.Cancelled => ex.status == Flow.Status.Cancelled
            case FlowStore.ExecutionFilter.Compensating =>
                ex.status match
                    case _: Flow.Status.Compensating => true
                    case _                           => false
            case FlowStore.ExecutionFilter.Failed(kind) =>
                ex.status match
                    case Flow.Status.Failed(_, k) => kind.forall(want => k.contains(want))
                    case _                        => false
            case FlowStore.ExecutionFilter.Sleeping =>
                ex.waits.exists((_, wake) =>
                    wake match
                        case _: Flow.Wake.At => true
                        case _               => false
                )
            case FlowStore.ExecutionFilter.WaitingForInput(name) =>
                ex.waits.exists((_, wake) =>
                    wake match
                        case Flow.Wake.OnField(field) => name.forall(_ == field)
                        case _                        => false
                )
            case FlowStore.ExecutionFilter.Cancelling       => ex.cancelRequested && !ex.status.isTerminal
            case FlowStore.ExecutionFilter.Orphaned(hashes) => !ex.status.isTerminal && !hashes.contains(ex.hash)

    def listExecutions(
        flowId: Flow.Id.Workflow,
        filter: Maybe[FlowStore.ExecutionFilter],
        limit: Maybe[Int],
        offset: Int
    )(using Frame): Chunk[FlowStore.ExecutionState] < Async =
        ref.use { data =>
            val ofFlow = data.executions.toChunk.map(_._2).filter(_.flowId == flowId)
            // Filtered BEFORE the page is cut, so a caller asking for the first 25 matches gets 25 matches rather than the
            // matches among the first 25 rows.
            val matched = filter match
                case Present(f) => ofFlow.filter(matches(f, _))
                case _          => ofFlow
            val sorted = matched.sortBy(_.created)(using Ordering[Instant]).reverse.drop(offset)
            // A negative count is not a meaningful limit, so it is answered the same as Absent: the alternative, an empty page,
            // is indistinguishable from a workflow that genuinely has nothing matching.
            val paged = limit match
                case Present(n) if n >= 0 => sorted.take(n)
                case _                    => sorted
            Chunk.from(paged)
        }

    // --- Fields ---

    def getField[V: Tag: Schema](
        executionId: Flow.Id.Execution,
        name: String
    )(using Frame): Maybe[V] < Async =
        ref.use { data =>
            data.fields.get((executionId, name)) match
                case Present(fd) if fd.tag =:= Tag[V] =>
                    summon[Schema[V]].decodeString[Json](fd.value).toMaybe
                case _ => Maybe.empty
        }

    def getAllFields(
        executionId: Flow.Id.Execution
    )(using Frame): Dict[String, FlowStore.FieldData] < Async =
        ref.use { data =>
            data.fields.foldLeft(Dict.empty[String, FlowStore.FieldData]) { (acc, key, fd) =>
                if key._1 == executionId then acc.update(key._2, fd) else acc
            }
        }

    // --- Events ---

    def getHistory(
        executionId: Flow.Id.Execution,
        limit: Maybe[Int],
        offset: Int
    )(using Frame): FlowStore.HistoryPage < Async =
        ref.use { data =>
            val all     = data.events.getOrElse(executionId, Chunk.empty)
            val dropped = all.drop(offset)
            // A negative count is not a meaningful limit, so it is answered the same as Absent: an implementation that clamped it
            // to zero instead would still have to answer `hasMore`, and doing so from a page it just emptied is exactly the
            // "empty page that claims more" pair a paging caller loops on forever. Bounding `hasMore` by what is actually left,
            // rather than adding to `limit`, is what keeps this free of the overflow an `Int.MaxValue` sentinel would force.
            limit match
                case Present(n) if n >= 0 => FlowStore.HistoryPage(dropped.take(n), dropped.length > n)
                case _                    => FlowStore.HistoryPage(dropped, hasMore = false)
        }

    // --- Workflows ---

    // Registering a definition is the one change that can widen what a poller serves, so it wakes them. An execution held
    // because no engine served its version becomes claimable the moment one does, and without the notification it would sit
    // until the blocked poll timed out on its own.
    def putWorkflow(meta: FlowEngine.WorkflowInfo)(using Frame): Unit < Async =
        ref.getAndUpdate(d => d.copy(workflows = d.workflows.update(Flow.Id.Workflow(meta.id), meta))).unit
            .andThen(notifyRegistration)

    def getWorkflow(id: Flow.Id.Workflow)(using Frame): Maybe[FlowEngine.WorkflowInfo] < Async =
        ref.use(_.workflows.get(id))

    def listWorkflows(using Frame): Seq[FlowEngine.WorkflowInfo] < Async =
        ref.use(_.workflows.toChunk.map(_._2))

end MemoryFlowStore
