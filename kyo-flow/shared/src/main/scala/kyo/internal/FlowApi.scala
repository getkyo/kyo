package kyo.internal

import kyo.*

private[kyo] object FlowApi:

    case class CreateRequest(executionId: Option[String] = None) derives Schema
    case class CreateResponse(executionId: String) derives Schema
    case class OkResponse(ok: Boolean) derives Schema
    case class EventDto(kind: String, detail: String, timestamp: String) derives Schema
    case class HistoryResponse(events: Seq[EventDto], hasMore: Boolean) derives Schema
    case class InputInfoDto(name: String, tag: String, delivered: Boolean) derives Schema
    case class SearchRequest(
        workflowId: Option[String] = None,
        status: Option[String] = None,
        limit: Option[Int] = None,
        offset: Option[Int] = None
    ) derives Schema
    case class SearchResponse(items: Seq[ExecutionInfoDto], total: Int) derives Schema

    /** One outstanding wait: which node, what kind, and the condition it is waiting on (a deadline, or an input's name). */
    case class WaitDto(path: String, kind: String, detail: Option[String]) derives Schema

    /** An execution as a listing shows it. `status` is the lifecycle; `waits` is what it is waiting for, because "running" alone would
      * answer a caller asking why an execution is not progressing.
      */
    case class ExecutionInfoDto(executionId: String, flowId: String, status: String, waits: Seq[WaitDto]) derives Schema

    /** One flow node's progress, as the execution-detail response carries it. */
    case class NodeProgressDto(name: String, nodeType: String, status: String, location: String) derives Schema

    /** The execution-detail response: the three scalars a search result carries, plus the two things a caller asks this endpoint for.
      *
      * `progress` is which node the execution is on and what happened to the ones before it, and `inputs` is which of its inputs have been
      * delivered. Both are already part of [[FlowEngine.ExecutionDetail]], which is what the endpoint reads; answering with the scalars
      * alone sends a caller to the store to find out where an execution is.
      */
    case class ExecutionDetailDto(
        executionId: String,
        flowId: String,
        status: String,
        waits: Seq[WaitDto],
        cancelRequested: Boolean,
        inputs: Seq[InputInfoDto],
        progress: Seq[NodeProgressDto]
    ) derives Schema
    case class CancelAllRequest(workflowId: Option[String] = None) derives Schema

    /** What a sweep asked for: the number of cancel REQUESTS this call put on, not the number of executions that have stopped.
      *
      * Named for the ask because that is what the call does. The executions counted here are running their compensations after this
      * answer is written, and a field named for completed work would tell an operator the sweep is over when it has just begun.
      */
    case class CancelAllResponse(requested: Int) derives Schema

    /** What a cancel request did, and what the execution ended as when there was nothing left to cancel.
      *
      * `outcome` is one of `accepted`, `already-requested` or `already-terminal`. `status` is present only for the last, where the
      * caller's next move is to read the result rather than to wait.
      */
    case class CancelResponse(outcome: String, status: Option[String] = None) derives Schema

    def handlers(engine: FlowEngine)(using Frame): Chunk[HttpHandler[?, ?, ?]] =
        Chunk(
            listWorkflows(engine),
            getWorkflow(engine),
            getWorkflowDiagram(engine),
            createExecution(engine),
            getExecution(engine),
            getExecutionInputs(engine),
            getExecutionHistory(engine),
            getExecutionDiagram(engine),
            signalExecution(engine),
            cancelExecution(engine),
            cancelAllExecutions(engine),
            searchExecutions(engine)
        )

    private def mapError[A](result: Result[Throwable, A])(using Frame): A < Abort[HttpResponse.Halt] =
        result match
            case Result.Success(r) => r
            case Result.Failure(e) =>
                e match
                    case _: FlowWorkflowException           => HttpResponse.halt(HttpResponse.notFound)
                    case _: FlowExecutionNotFoundException  => HttpResponse.halt(HttpResponse.notFound)
                    case _: FlowExecutionTerminalException  => HttpResponse.halt(HttpResponse(HttpStatus.Conflict))
                    case _: FlowDuplicateExecutionException => HttpResponse.halt(HttpResponse(HttpStatus.Conflict))
                    case _: FlowSignalException             => HttpResponse.halt(HttpResponse.badRequest)
                    case _                                  => HttpResponse.halt(HttpResponse(HttpStatus.InternalServerError))
            case Result.Panic(_) => HttpResponse.halt(HttpResponse(HttpStatus.InternalServerError))

    private def listWorkflows(engine: FlowEngine)(using Frame): HttpHandler[?, ?, ?] =
        HttpRoute.getRaw("api" / "v1" / "workflows")
            .response(_.bodyJson[Seq[FlowEngine.WorkflowInfo]])
            .handler { _ =>
                engine.workflows.list.map(HttpResponse.ok(_))
            }

    private def getWorkflow(engine: FlowEngine)(using Frame): HttpHandler[?, ?, ?] =
        HttpRoute.getRaw("api" / "v1" / "workflows" / Capture[String]("id"))
            .response(_.bodyJson[FlowEngine.WorkflowInfo])
            .handler { req =>
                Abort.run[Throwable] {
                    engine.workflows.describe(Flow.Id.Workflow(req.fields.id)).map(HttpResponse.ok(_))
                }.map(mapError)
            }

    private def getWorkflowDiagram(engine: FlowEngine)(using Frame): HttpHandler[?, ?, ?] =
        HttpRoute.getRaw("api" / "v1" / "workflows" / Capture[String]("id") / "diagram")
            .request(_.queryOpt[String]("format"))
            .response(_.bodyText)
            .handler { req =>
                Abort.run[Throwable] {
                    engine.workflows.diagram(
                        Flow.Id.Workflow(req.fields.id),
                        Flow.DiagramFormat.fromString(req.fields.format.getOrElse("mermaid"))
                    ).map(HttpResponse.ok(_))
                }.map(mapError)
            }

    private def createExecution(engine: FlowEngine)(using Frame): HttpHandler[?, ?, ?] =
        HttpRoute.postRaw("api" / "v1" / "workflows" / Capture[String]("id") / "executions")
            .response(_.bodyJson[CreateResponse])
            .handler { req =>
                Abort.run[Throwable] {
                    engine.workflows.start(Flow.Id.Workflow(req.fields.id)).map { handle =>
                        HttpResponse.ok(CreateResponse(handle.executionId.value))
                    }
                }.map(mapError)
            }

    private def getExecution(engine: FlowEngine)(using Frame): HttpHandler[?, ?, ?] =
        HttpRoute.getRaw("api" / "v1" / "executions" / Capture[String]("eid"))
            .response(_.bodyJson[ExecutionDetailDto])
            .handler { req =>
                Abort.run[Throwable] {
                    engine.executions.describe(Flow.Id.Execution(req.fields.eid)).map { detail =>
                        HttpResponse.ok(
                            ExecutionDetailDto(
                                detail.executionId.value,
                                detail.flowId.value,
                                detail.status.show,
                                waitsOf(detail.state),
                                detail.cancelRequested,
                                detail.inputs.map(i => InputInfoDto(i.name, i.tag, i.delivered)),
                                detail.progress.nodes.map(n =>
                                    NodeProgressDto(n.name, n.nodeType.toString, n.status.show, n.location)
                                )
                            )
                        )
                    }
                }.map(mapError)
            }

    private def getExecutionInputs(engine: FlowEngine)(using Frame): HttpHandler[?, ?, ?] =
        HttpRoute.getRaw("api" / "v1" / "executions" / Capture[String]("eid") / "inputs")
            .response(_.bodyJson[Seq[InputInfoDto]])
            .handler { req =>
                Abort.run[Throwable] {
                    engine.executions.inputs(Flow.Id.Execution(req.fields.eid)).map { inputs =>
                        HttpResponse.ok(inputs.map(i => InputInfoDto(i.name, i.tag, i.delivered)))
                    }
                }.map(mapError)
            }

    /** A page offset a client cannot have meant, refused here rather than forwarded.
      *
      * **The asymmetry with `limit` is the point, not an oversight.** A negative limit has a reading the whole stack agrees on, that
      * the caller wants everything, and [[FlowStore.getHistory]] states it as a contract every implementation keeps. A negative offset
      * has no reading at all: it is not "start before the beginning", it is a number that should not have been sent. The SPI makes
      * `offset` non-negative a caller obligation, and this handler is the caller, so the check belongs here.
      *
      * It is answered 400 because this is the one layer that can tell a client's bad query parameter from a store fault. Forwarded, it
      * is tolerated by a store that drops from a chunk and rejected by one that writes `OFFSET -1` into SQL, where it would surface as
      * a 500 and send an operator hunting a fault that is not there.
      */
    private def refuseNegativeOffset(offset: Int)(using Frame): Unit < Abort[HttpResponse.Halt] =
        if offset < 0 then HttpResponse.halt(HttpResponse.badRequest) else ()

    private def getExecutionHistory(engine: FlowEngine)(using Frame): HttpHandler[?, ?, ?] =
        HttpRoute.getRaw("api" / "v1" / "executions" / Capture[String]("eid") / "history")
            .request(_.queryOpt[Int]("limit"))
            .request(_.queryOpt[Int]("offset"))
            .response(_.bodyJson[HistoryResponse])
            .handler { req =>
                val limit  = req.fields.limit.getOrElse(50)
                val offset = req.fields.offset.getOrElse(0)
                refuseNegativeOffset(offset).andThen {
                    engine.executions.history(Flow.Id.Execution(req.fields.eid), limit, offset).map { page =>
                        HttpResponse.ok(HistoryResponse(
                            page.events.toSeq.map(e => EventDto(e.kind.toString, e.detail, e.timestamp.show)),
                            page.hasMore
                        ))
                    }
                }
            }

    private def getExecutionDiagram(engine: FlowEngine)(using Frame): HttpHandler[?, ?, ?] =
        HttpRoute.getRaw("api" / "v1" / "executions" / Capture[String]("eid") / "diagram")
            .request(_.queryOpt[String]("format"))
            .response(_.bodyText)
            .handler { req =>
                Abort.run[Throwable] {
                    engine.executions.diagram(
                        Flow.Id.Execution(req.fields.eid),
                        Flow.DiagramFormat.fromString(req.fields.format.getOrElse("mermaid"))
                    ).map(HttpResponse.ok(_))
                }.map(mapError)
            }

    private def signalExecution(engine: FlowEngine)(using Frame): HttpHandler[?, ?, ?] =
        HttpRoute.postRaw("api" / "v1" / "executions" / Capture[String]("eid") / "signal" / Capture[String]("name"))
            .request(_.bodyText)
            .response(_.bodyJson[OkResponse])
            .handler { req =>
                val eid  = Flow.Id.Execution(req.fields.eid)
                val name = req.fields.name
                val body = req.fields.body
                Abort.run[Throwable] {
                    engine.executions.describe(eid).map { detail =>
                        if detail.status.isTerminal then HttpResponse.halt(HttpResponse(HttpStatus.Conflict))
                        else
                            engine.defs.use(_.of(detail.flowId, detail.state.hash)).map {
                                case Present(defn) =>
                                    Maybe.fromOption(defn.inputs.find(_.name == name)) match
                                        case Present(info) =>
                                            info.schema.decodeString[Json](body) match
                                                case Result.Success(value) =>
                                                    // The delivery goes through the engine's own verb rather than around it. Writing
                                                    // the field directly leaves an HTTP-delivered input durable and invisible:
                                                    // nothing in the history says it arrived, so an operator reading the execution
                                                    // sees a value with no story. One verb, one set of guards, one event.
                                                    Abort.run[FlowSignalException | FlowExecutionStateException] {
                                                        engine.executions.signal[Any](eid, name, value)(using info.tag, info.schema)
                                                    }.map {
                                                        case Result.Success(_) => HttpResponse.ok(OkResponse(true))
                                                        // Already delivered, or the execution finished while this delivery was in
                                                        // flight. The pre-checks above have already answered every other refusal.
                                                        case _ => HttpResponse.halt(HttpResponse(HttpStatus.Conflict))
                                                    }
                                                case _ => HttpResponse.halt(HttpResponse.badRequest)
                                        case Absent => HttpResponse.halt(HttpResponse.badRequest)
                                case Absent => HttpResponse.halt(HttpResponse.notFound)
                            }
                    }
                }.map(mapError)
            }

    /** Answers which of the three things a cancel request did, rather than `ok: true` to all of them.
      *
      * A caller cancelling over HTTP has no other way to learn the outcome, since the execution does not stop while the request is in
      * flight, and a boolean that is true whether the request was taken or fell on an execution that finished last week is the answer
      * a caller can do least with.
      *
      * None of the three is a client error. An `already-terminal` caller asked to stop something that had already stopped, which is
      * the outcome it wanted; what it gets back is the terminal status, so its next call is a read rather than a retry.
      */
    private def cancelExecution(engine: FlowEngine)(using Frame): HttpHandler[?, ?, ?] =
        HttpRoute.postRaw("api" / "v1" / "executions" / Capture[String]("eid") / "cancel")
            .response(_.bodyJson[CancelResponse])
            .handler { req =>
                engine.executions.cancel(Flow.Id.Execution(req.fields.eid)).map {
                    // The only answer that changed the row, and 202 is the code for a request taken whose work has not been done:
                    // the compensations run after this response is written.
                    case FlowStore.CancelOutcome.Accepted =>
                        HttpResponse.accepted(CancelResponse("accepted"))
                    case FlowStore.CancelOutcome.AlreadyRequested =>
                        HttpResponse.ok(CancelResponse("already-requested"))
                    case FlowStore.CancelOutcome.AlreadyTerminal(status) =>
                        HttpResponse.ok(CancelResponse("already-terminal", Some(status.show)))
                }
            }

    private def cancelAllExecutions(engine: FlowEngine)(using Frame): HttpHandler[?, ?, ?] =
        HttpRoute.postRaw("api" / "v1" / "executions" / "cancel")
            .request(_.bodyJson[CancelAllRequest])
            .response(_.bodyJson[CancelAllResponse])
            .handler { req =>
                val wfId = req.fields.body.workflowId match
                    case Some(id) => Maybe(Flow.Id.Workflow(id))
                    case _        => Maybe.empty
                engine.executions.cancelAll(wfId).map(count => HttpResponse.ok(CancelAllResponse(requested = count)))
            }

    private def searchExecutions(engine: FlowEngine)(using Frame): HttpHandler[?, ?, ?] =
        HttpRoute.postRaw("api" / "v1" / "executions" / "search")
            .request(_.bodyJson[SearchRequest])
            .response(_.bodyJson[SearchResponse])
            .handler { req =>
                val body = req.fields.body
                val wfId = body.workflowId match
                    case Some(id) => Maybe(Flow.Id.Workflow(id))
                    case _        => Maybe.empty
                val limit  = body.limit.getOrElse(25)
                val offset = body.offset.getOrElse(0)
                // A filter string that parses to nothing is a client error, not "no filter": answering every execution to a caller who
                // asked for a subset reports a typo as a result set.
                val filter: Maybe[FlowStore.ExecutionFilter] < Abort[HttpResponse.Halt] = body.status match
                    case Some(s) =>
                        parseFilter(s) match
                            case Present(f) => Maybe(f)
                            case Absent     => HttpResponse.halt(HttpResponse.badRequest)
                    case _ => Maybe.empty
                refuseNegativeOffset(offset).andThen {
                    filter.map { f =>
                        engine.executions.search(wfId, f, limit, offset).map { result =>
                            val items =
                                result.items.map(s => ExecutionInfoDto(s.executionId.value, s.flowId.value, s.status.show, waitsOf(s)))
                            HttpResponse.ok(SearchResponse(items, result.total))
                        }
                    }
                }
            }

    /** The filter vocabulary as a caller spells it on the wire.
      *
      * `Orphaned` is deliberately absent: its parameter is one engine's own served set, which no HTTP caller can supply, and the operator
      * reaches that predicate through the engine's `parked` report instead.
      *
      * **A narrowing prefix with nothing after it does not parse.** Taking `failed:` and `waiting:` as a narrowing on the empty string
      * matches nothing, since no kind or input name is empty (a kind is a class name), so both would answer an empty result set with a
      * 200. That is the same typo class as an unrecognized filter, telling the opposite lie: nothing matched rather than everything did.
      * A caller who meant the bare form gets a refusal it can act on instead.
      */
    private def parseFilter(s: String): Maybe[FlowStore.ExecutionFilter] =
        s match
            case "running"      => Maybe(FlowStore.ExecutionFilter.Running)
            case "completed"    => Maybe(FlowStore.ExecutionFilter.Completed)
            case "cancelled"    => Maybe(FlowStore.ExecutionFilter.Cancelled)
            case "cancelling"   => Maybe(FlowStore.ExecutionFilter.Cancelling)
            case "compensating" => Maybe(FlowStore.ExecutionFilter.Compensating)
            case "failed"       => Maybe(FlowStore.ExecutionFilter.Failed(Maybe.empty))
            case "sleeping"     => Maybe(FlowStore.ExecutionFilter.Sleeping)
            case "waiting"      => Maybe(FlowStore.ExecutionFilter.WaitingForInput(Maybe.empty))
            case s if s.startsWith("failed:") && s.stripPrefix("failed:").nonEmpty =>
                Maybe(FlowStore.ExecutionFilter.Failed(Maybe(s.stripPrefix("failed:"))))
            case s if s.startsWith("waiting:") && s.stripPrefix("waiting:").nonEmpty =>
                Maybe(FlowStore.ExecutionFilter.WaitingForInput(Maybe(s.stripPrefix("waiting:"))))
            case _ => Maybe.empty

    /** An execution's wait rows on the wire: what each waiting node is waiting for, by the node's path. */
    private def waitsOf(state: FlowStore.ExecutionState): Seq[WaitDto] =
        state.waits.toChunk.sortBy(_._1).map { (path, wake) =>
            wake match
                case Flow.Wake.At(instant)   => WaitDto(path, "sleeping", Some(instant.show))
                case Flow.Wake.OnField(name) => WaitDto(path, "waiting", Some(name))
        }

end FlowApi
