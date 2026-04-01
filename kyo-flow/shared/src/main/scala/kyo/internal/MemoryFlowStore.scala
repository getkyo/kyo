package kyo.internal

import kyo.*

private[kyo] case class MemoryData(
    executions: Map[Flow.Id.Execution, FlowStore.ExecutionState],
    fields: Map[(Flow.Id.Execution, String), FlowStore.FieldData],
    events: Map[Flow.Id.Execution, Chunk[Flow.Event]],
    workflows: Map[Flow.Id.Workflow, FlowEngine.WorkflowInfo]
)

private[kyo] object MemoryData:
    val empty = MemoryData(Map.empty, Map.empty, Map.empty, Map.empty)

private[kyo] class MemoryFlowStore(
    ref: AtomicRef[MemoryData],
    signal: Signal.SignalRef[Int]
)(using Frame) extends FlowStore:

    private def notify(using Frame): Unit < Sync =
        signal.updateAndGet(_ + 1).unit

    // --- Coordination ---

    def claimReady(
        workflowIds: Set[Flow.Id.Workflow],
        executorId: Flow.Id.Executor,
        lease: Duration,
        limit: Int,
        timeout: Duration
    )(using Frame): Seq[FlowStore.ExecutionState] < Async =
        def tryOnce: Seq[FlowStore.ExecutionState] < Sync =
            Clock.nowWith { now =>
                ref.getAndUpdate { data =>
                    val ready = data.executions.values.filter { ex =>
                        workflowIds.contains(ex.flowId) &&
                        !ex.status.isTerminal &&
                        (ex.executor match
                            case Absent                                               => true
                            case Present(_) if ex.claimExpiry.exists(e => !(now < e)) => true
                            case _                                                    => false) &&
                        (ex.status match
                            case Flow.Status.Running            => true
                            case Flow.Status.Sleeping(_, until) => !(now < until)
                            case Flow.Status.WaitingForInput(name) =>
                                data.fields.contains((ex.executionId, name))
                            case Flow.Status.Cancelled => true
                            case _                     => false)
                    }.take(limit).toSeq

                    val claimed = ready.map(ex =>
                        ex.executionId -> ex.copy(
                            executor = Maybe(executorId),
                            claimExpiry = Maybe(now + lease),
                            updated = now
                        )
                    ).toMap

                    data.copy(executions = data.executions ++ claimed)
                }.map { oldData =>
                    // Return only executions newly claimed by this call (were not already owned by us)
                    ref.use { newData =>
                        newData.executions.values.filter { ex =>
                            ex.executor == Maybe(executorId) &&
                            ex.claimExpiry.exists(e => now < e) &&
                            // Was not already claimed by us before this call
                            oldData.executions.get(ex.executionId).forall(old =>
                                old.executor != Maybe(executorId)
                            )
                        }.toSeq.sortBy(_.created)(using Ordering[Instant]).take(limit)
                    }
                }
            }

        tryOnce.map { results =>
            if results.nonEmpty then results
            else
                Async.race(
                    Async.sleep(timeout).andThen(Seq.empty[FlowStore.ExecutionState]),
                    signal.next.andThen(tryOnce)
                )
        }
    end claimReady

    def renewClaim(
        executionId: Flow.Id.Execution,
        executorId: Flow.Id.Executor,
        lease: Duration
    )(using Frame): Boolean < Async =
        Clock.nowWith { now =>
            ref.updateAndGet { data =>
                data.executions.get(executionId) match
                    case Some(ex) if ex.executor == Maybe(executorId) && ex.claimExpiry.exists(e => now < e) =>
                        data.copy(executions =
                            data.executions +
                                (executionId -> ex.copy(claimExpiry = Maybe(now + lease), updated = now))
                        )
                    case _ => data
            }.map { newData =>
                newData.executions.get(executionId).exists(ex =>
                    ex.executor == Maybe(executorId) && ex.claimExpiry.exists(e => now < e)
                )
            }
        }

    def releaseClaim(
        executionId: Flow.Id.Execution,
        executorId: Flow.Id.Executor
    )(using Frame): Unit < Async =
        ref.getAndUpdate { data =>
            data.executions.get(executionId) match
                case Some(ex) if ex.executor == Maybe(executorId) =>
                    data.copy(executions =
                        data.executions +
                            (executionId -> ex.copy(executor = Maybe.empty, claimExpiry = Maybe.empty))
                    )
                case _ => data
        }.unit.andThen(notify)

    // --- Execution state ---

    def createExecution(
        executionId: Flow.Id.Execution,
        status: Flow.Status,
        event: Flow.Event,
        hash: String
    )(using Frame): Unit < Async =
        Clock.nowWith { now =>
            ref.getAndUpdate { data =>
                val ex = FlowStore.ExecutionState(
                    executionId,
                    event.flowId,
                    status,
                    hash = hash,
                    created = now,
                    updated = now
                )
                val events = Chunk(event)
                data.copy(
                    executions = data.executions + (executionId -> ex),
                    events = data.events + (executionId         -> events)
                )
            }.unit.andThen(notify)
        }

    def updateStatus(
        executionId: Flow.Id.Execution,
        status: Flow.Status,
        event: Flow.Event
    )(using Frame): Unit < Async =
        Clock.nowWith { now =>
            ref.getAndUpdate { data =>
                data.executions.get(executionId) match
                    case Some(ex) if ex.status.isTerminal =>
                        // Terminal status is irreversible — only append the event
                        val events = data.events.getOrElse(executionId, Chunk.empty)
                        data.copy(events = data.events + (executionId -> (events :+ event)))
                    case Some(ex) =>
                        val updated = ex.copy(status = status, updated = now)
                        val events  = data.events.getOrElse(executionId, Chunk.empty)
                        data.copy(
                            executions = data.executions + (executionId -> updated),
                            events = data.events + (executionId         -> (events :+ event))
                        )
                    case None => data
            }.unit.andThen(notify)
        }

    def getExecution(
        executionId: Flow.Id.Execution
    )(using Frame): Maybe[FlowStore.ExecutionState] < Async =
        ref.use(_.executions.get(executionId) match
            case Some(ex) => Maybe(ex)
            case None     => Maybe.empty)

    def listExecutions(
        flowId: Flow.Id.Workflow,
        status: Maybe[Flow.Status],
        limit: Int,
        offset: Int
    )(using Frame): Chunk[FlowStore.ExecutionState] < Async =
        ref.use { data =>
            val filtered = data.executions.values.filter(_.flowId == flowId)
            val byStatus = status match
                case Present(s) => filtered.filter(_.status == s)
                case _          => filtered
            Chunk.from(byStatus.toSeq.sortBy(_.created)(using Ordering[Instant]).reverse.drop(offset).take(limit))
        }

    // --- Fields ---

    def putField[V: Tag: Json](
        executionId: Flow.Id.Execution,
        name: String,
        value: V
    )(using Frame): Unit < Async =
        val data = FlowStore.FieldData(summon[Json[V]].encode(value), Tag[V].erased)
        ref.getAndUpdate(d => d.copy(fields = d.fields + ((executionId, name) -> data))).unit
            .andThen(notify)
    end putField

    def putFieldIfAbsent[V: Tag: Json](
        executionId: Flow.Id.Execution,
        name: String,
        value: V
    )(using Frame): Boolean < Async =
        val data = FlowStore.FieldData(summon[Json[V]].encode(value), Tag[V].erased)
        val key  = (executionId, name)
        ref.getAndUpdate { d =>
            if d.fields.contains(key) then d
            else d.copy(fields = d.fields + (key -> data))
        }.map { oldData =>
            val wasAbsent = !oldData.fields.contains(key)
            if wasAbsent then notify.andThen(true) else false
        }
    end putFieldIfAbsent

    def getField[V: Tag: Json](
        executionId: Flow.Id.Execution,
        name: String
    )(using Frame): Maybe[V] < Async =
        ref.use { data =>
            data.fields.get((executionId, name)) match
                case Some(fd) if fd.tag =:= Tag[V] =>
                    summon[Json[V]].decode(fd.value).toMaybe
                case _ => Maybe.empty
        }

    def getAllFields(
        executionId: Flow.Id.Execution
    )(using Frame): Dict[String, FlowStore.FieldData] < Async =
        ref.use { data =>
            data.fields.foldLeft(Dict.empty[String, FlowStore.FieldData]) { case (acc, ((eid, name), fd)) =>
                if eid == executionId then acc.update(name, fd) else acc
            }
        }

    // --- Events ---

    def appendEvent(
        executionId: Flow.Id.Execution,
        event: Flow.Event
    )(using Frame): Unit < Async =
        ref.getAndUpdate { data =>
            val events = data.events.getOrElse(executionId, Chunk.empty)
            data.copy(events = data.events + (executionId -> (events :+ event)))
        }.unit.andThen(notify)

    def getHistory(
        executionId: Flow.Id.Execution,
        limit: Int,
        offset: Int
    )(using Frame): FlowStore.HistoryPage < Async =
        ref.use { data =>
            val all   = data.events.getOrElse(executionId, Chunk.empty)
            val paged = all.drop(offset).take(limit)
            FlowStore.HistoryPage(paged, all.length > offset + limit)
        }

    // --- Workflows ---

    def putWorkflow(meta: FlowEngine.WorkflowInfo)(using Frame): Unit < Async =
        ref.getAndUpdate(d => d.copy(workflows = d.workflows + (Flow.Id.Workflow(meta.id) -> meta))).unit

    def getWorkflow(id: Flow.Id.Workflow)(using Frame): Maybe[FlowEngine.WorkflowInfo] < Async =
        ref.use(_.workflows.get(id) match
            case Some(m) => Maybe(m)
            case None    => Maybe.empty)

    def listWorkflows(using Frame): Seq[FlowEngine.WorkflowInfo] < Async =
        ref.use(_.workflows.values.toSeq)

end MemoryFlowStore
