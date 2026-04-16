package kyo.internal

import kyo.*

private[kyo] object FlowApi:

    case class CreateRequest(executionId: Option[String] = None) derives Json
    case class CreateResponse(executionId: String) derives Json
    case class OkResponse(ok: Boolean) derives Json
    case class EventDto(kind: String, detail: String, timestamp: String) derives Json
    case class HistoryResponse(events: Seq[EventDto], hasMore: Boolean) derives Json
    case class InputInfoDto(name: String, tag: String, delivered: Boolean) derives Json
    case class SearchRequest(
        workflowId: Option[String] = None,
        status: Option[String] = None,
        limit: Option[Int] = None,
        offset: Option[Int] = None
    ) derives Json
    case class SearchResponse(items: Seq[ExecutionInfoDto], total: Int) derives Json
    case class ExecutionInfoDto(executionId: String, flowId: String, status: String) derives Json
    case class CancelAllRequest(workflowId: Option[String] = None) derives Json
    case class CancelAllResponse(cancelled: Int) derives Json

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
            .response(_.bodyJson[ExecutionInfoDto])
            .handler { req =>
                Abort.run[Throwable] {
                    engine.executions.describe(Flow.Id.Execution(req.fields.eid)).map { state =>
                        HttpResponse.ok(ExecutionInfoDto(state.executionId.value, state.flowId.value, state.status.show))
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

    private def getExecutionHistory(engine: FlowEngine)(using Frame): HttpHandler[?, ?, ?] =
        HttpRoute.getRaw("api" / "v1" / "executions" / Capture[String]("eid") / "history")
            .request(_.queryOpt[Int]("limit"))
            .request(_.queryOpt[Int]("offset"))
            .response(_.bodyJson[HistoryResponse])
            .handler { req =>
                val limit  = req.fields.limit.getOrElse(50)
                val offset = req.fields.offset.getOrElse(0)
                engine.executions.history(Flow.Id.Execution(req.fields.eid), limit, offset).map { page =>
                    HttpResponse.ok(HistoryResponse(
                        page.events.toSeq.map(e => EventDto(e.kind.toString, e.detail, e.timestamp.show)),
                        page.hasMore
                    ))
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
                            engine.defs.use(_.get(detail.flowId)).map {
                                case Present(defn) =>
                                    Maybe.fromOption(defn.inputs.find(_.name == name)) match
                                        case Present(info) =>
                                            info.json.decode(body) match
                                                case Result.Success(value) =>
                                                    engine.store.putFieldIfAbsent[Any](eid, name, value)(using info.tag, info.json).map {
                                                        case true  => HttpResponse.ok(OkResponse(true))
                                                        case false => HttpResponse.halt(HttpResponse(HttpStatus.Conflict))
                                                    }
                                                case _ => HttpResponse.halt(HttpResponse.badRequest)
                                        case Absent => HttpResponse.halt(HttpResponse.badRequest)
                                case Absent => HttpResponse.halt(HttpResponse.notFound)
                            }
                    }
                }.map(mapError)
            }

    private def cancelExecution(engine: FlowEngine)(using Frame): HttpHandler[?, ?, ?] =
        HttpRoute.postRaw("api" / "v1" / "executions" / Capture[String]("eid") / "cancel")
            .response(_.bodyJson[OkResponse])
            .handler { req =>
                engine.executions.cancel(Flow.Id.Execution(req.fields.eid)).andThen(HttpResponse.ok(OkResponse(true)))
            }

    private def cancelAllExecutions(engine: FlowEngine)(using Frame): HttpHandler[?, ?, ?] =
        HttpRoute.postRaw("api" / "v1" / "executions" / "cancel")
            .request(_.bodyJson[CancelAllRequest])
            .response(_.bodyJson[CancelAllResponse])
            .handler { req =>
                val wfId = req.fields.body.workflowId match
                    case Some(id) => Maybe(Flow.Id.Workflow(id))
                    case _        => Maybe.empty
                engine.executions.cancelAll(wfId).map(count => HttpResponse.ok(CancelAllResponse(count)))
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
                val status = body.status match
                    case Some(s) => parseStatus(s)
                    case _       => Maybe.empty
                val limit  = body.limit.getOrElse(25)
                val offset = body.offset.getOrElse(0)
                engine.executions.search(wfId, status, limit, offset).map { result =>
                    val items = result.items.map(s => ExecutionInfoDto(s.executionId.value, s.flowId.value, s.status.show))
                    HttpResponse.ok(SearchResponse(items, result.total))
                }
            }

    private def parseStatus(s: String): Maybe[Flow.Status] =
        s match
            case "running"                     => Maybe(Flow.Status.Running)
            case "completed"                   => Maybe(Flow.Status.Completed)
            case "cancelled"                   => Maybe(Flow.Status.Cancelled)
            case "compensating"                => Maybe(Flow.Status.Compensating)
            case s if s.startsWith("failed:")  => Maybe(Flow.Status.Failed(s.stripPrefix("failed:")))
            case s if s.startsWith("waiting:") => Maybe(Flow.Status.WaitingForInput(s.stripPrefix("waiting:")))
            case _                             => Maybe.empty

end FlowApi
