package kyo

import caliban.*
import caliban.CalibanError
import caliban.Configurator.ExecutionConfiguration
import caliban.ResponseValue.*
import caliban.Value.*
import caliban.execution.QueryExecution
import caliban.uploads.*
import caliban.ws.CalibanPipe
import caliban.ws.Protocol
import caliban.ws.WebSocketHooks
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import zio.ZEnvironment
import zio.ZIO
import zio.stream.ZStream

/** Effect for interacting with Caliban GraphQL resolvers. */
opaque type Resolvers <: (Abort[CalibanError] & Async) = Abort[CalibanError] & Async

object Resolvers:

    case class Config private (
        path: String,
        filter: HttpFilter.Passthrough[Nothing],
        graphiql: Boolean,
        enableIntrospection: Boolean,
        skipValidation: Boolean,
        queryExecution: QueryExecution,
        allowMutationsOverGetRequests: Boolean,
        webSocketKeepAlive: Maybe[Duration]
    ):
        def path(path: String): Config                              = copy(path = path)
        def filter(filter: HttpFilter.Passthrough[Nothing]): Config = copy(filter = filter)
        def graphiql(graphiql: Boolean): Config                     = copy(graphiql = graphiql)
        def enableIntrospection(enabled: Boolean): Config           = copy(enableIntrospection = enabled)
        def skipValidation(skip: Boolean): Config                   = copy(skipValidation = skip)
        def queryExecution(execution: QueryExecution): Config       = copy(queryExecution = execution)
        def allowMutationsOverGetRequests(allow: Boolean): Config   = copy(allowMutationsOverGetRequests = allow)
        def webSocketKeepAlive(duration: Duration): Config          = copy(webSocketKeepAlive = Present(duration))
        def webSocketKeepAlive(duration: Maybe[Duration]): Config   = copy(webSocketKeepAlive = duration)
    end Config

    object Config:
        val default: Config = Config(
            path = "api/graphql",
            filter = HttpFilter.noop,
            graphiql = true,
            enableIntrospection = true,
            skipValidation = false,
            queryExecution = QueryExecution.Parallel,
            allowMutationsOverGetRequests = false,
            webSocketKeepAlive = Absent
        )
    end Config

    /** Runs a GraphQL server from an interpreter with the given configuration.
      *
      * Serves POST/GET (queries, mutations), SSE (subscriptions), `@defer` (multipart streaming), uploads (multipart), GraphiQL, and a
      * WebSocket endpoint at `${path}/ws` speaking both `graphql-transport-ws` and the legacy `graphql-ws` subprotocols.
      *
      * @param interpreter
      *   The GraphQL interpreter to serve
      * @param config
      *   The server configuration (path, filter, graphiql, etc.)
      * @param webSocketHooks
      *   Lifecycle hooks invoked during the WebSocket subscription flow: beforeInit / afterInit (auth), onAck (init response payload),
      *   onPing / onPong (keep-alive interplay), onMessage (transform outbound subscription messages).
      * @param Frame
      *   Implicit Frame parameter
      * @return
      *   An HttpServer wrapped in Async and Scope effects
      */
    def run(
        interpreter: GraphQLInterpreter[Any, CalibanError],
        config: Config = Config.default,
        webSocketHooks: WebSocketHooks[Any, CalibanError] = WebSocketHooks.empty[Any, CalibanError]
    )(using Frame): HttpServer < (Async & Scope) =
        val wrapped = interpreter.wrapExecutionWith(Configurator.locally(toExecutionConfig(config))(_))
        HttpServer.init(buildHandlers(wrapped, config, ZEnvironment.empty, webSocketHooks)*)
    end run

    /** Runs a GraphQL server with a custom Runner using default configuration.
      *
      * @param interpreter
      *   The GraphQL interpreter to serve
      * @param runner
      *   The custom Runner to handle arbitrary effects
      * @param tag
      *   Implicit Tag for Runner[R]
      * @param Frame
      *   Implicit Frame parameter
      * @return
      *   An HttpServer wrapped in Async and Scope effects
      */
    def run[R](
        interpreter: GraphQLInterpreter[Runner[R], CalibanError],
        runner: Runner[R]
    )(using zio.Tag[Runner[R]], Frame): HttpServer < (Async & Scope) =
        run(interpreter, runner, Config.default, WebSocketHooks.empty[Runner[R], CalibanError])

    /** Runs a GraphQL server with a custom Runner and a custom configuration.
      *
      * @param interpreter
      *   The GraphQL interpreter to serve
      * @param runner
      *   The custom Runner to handle arbitrary effects
      * @param config
      *   The server configuration
      * @param tag
      *   Implicit Tag for Runner[R]
      * @param Frame
      *   Implicit Frame parameter
      * @return
      *   An HttpServer wrapped in Async and Scope effects
      */
    def run[R](
        interpreter: GraphQLInterpreter[Runner[R], CalibanError],
        runner: Runner[R],
        config: Config
    )(using zio.Tag[Runner[R]], Frame): HttpServer < (Async & Scope) =
        run(interpreter, runner, config, WebSocketHooks.empty[Runner[R], CalibanError])

    /** Runs a GraphQL server with a custom Runner, configuration, and WebSocket hooks.
      *
      * @param interpreter
      *   The GraphQL interpreter to serve
      * @param runner
      *   The custom Runner to handle arbitrary effects
      * @param config
      *   The server configuration
      * @param webSocketHooks
      *   Lifecycle hooks invoked during the WebSocket subscription flow: beforeInit / afterInit (auth), onAck (init response payload),
      *   onPing / onPong (keep-alive interplay), onMessage (transform outbound subscription messages).
      * @param tag
      *   Implicit Tag for Runner[R]
      * @param Frame
      *   Implicit Frame parameter
      * @return
      *   An HttpServer wrapped in Async and Scope effects
      */
    def run[R](
        interpreter: GraphQLInterpreter[Runner[R], CalibanError],
        runner: Runner[R],
        config: Config,
        webSocketHooks: WebSocketHooks[Runner[R], CalibanError]
    )(using zio.Tag[Runner[R]], Frame): HttpServer < (Async & Scope) =
        val wrapped = interpreter.wrapExecutionWith(Configurator.locally(toExecutionConfig(config))(_))
        HttpServer.init(buildHandlers(wrapped, config, ZEnvironment(runner), webSocketHooks)*)
    end run

    /** Creates a GraphQL interpreter from an API.
      *
      * @param api
      *   The GraphQL API to be interpreted
      * @param Frame
      *   Implicit Frame parameter
      * @return
      *   A GraphQLInterpreter wrapped in Abort and Async effects
      */
    def get[R](api: GraphQL[R])(using Frame): GraphQLInterpreter[R, CalibanError] < (Abort[CalibanError] & Async) =
        ZIOs.get(api.interpreter)

    // --- internals ---

    private val uploadMapCodec = JsonCodecMaker.make[Map[String, List[String]]]

    private def toExecutionConfig(config: Config): ExecutionConfiguration =
        ExecutionConfiguration(
            skipValidation = config.skipValidation,
            enableIntrospection = config.enableIntrospection,
            queryExecution = config.queryExecution,
            allowMutationsOverGetRequests = config.allowMutationsOverGetRequests
        )

    /** Parses a GraphQL POST/upload body. Returns Left(parseError) on malformed JSON / empty body so the handler can emit a structured
      * GraphQL error envelope instead of letting jsoniter throw out of the request pipeline.
      */
    private def parseRequest(body: Span[Byte], headers: HttpHeaders): Either[CalibanError.ParsingError, GraphQLRequest] =
        val ct = headers.get("content-type")
        val parsed: Either[CalibanError.ParsingError, GraphQLRequest] =
            if ct.exists(_.contains("application/graphql")) then
                Right(GraphQLRequest(query = Some(new String(body.toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8))))
            else
                try
                    if body.size == 0 then
                        Left(CalibanError.ParsingError("Empty request body"))
                    else
                        Right(readFromArray[GraphQLRequest](body.toArrayUnsafe)(using GraphQLRequest.jsoniterCodec))
                catch
                    case scala.util.control.NonFatal(t) =>
                        Left(CalibanError.ParsingError(s"Invalid JSON: ${t.getMessage}"))
        parsed.map { request =>
            if headers.get("apollo-federation-include-trace").exists(_ == "ftv1") then
                request.withFederatedTracing
            else request
        }
    end parseRequest

    private def parseInputMap(json: String): Map[String, caliban.InputValue] =
        readFromString[caliban.InputValue](json)(using caliban.InputValue.jsoniterCodec) match
            case caliban.InputValue.ObjectValue(fields) => fields
            case _                                      => Map.empty

    private def parseGetRequest(
        query: Maybe[String],
        operationName: Maybe[String],
        variables: Maybe[String],
        extensions: Maybe[String]
    ): GraphQLRequest =
        GraphQLRequest(
            query.toOption,
            operationName.toOption,
            variables.toOption.map(parseInputMap),
            extensions.toOption.map(parseInputMap),
            isHttpGetRequest = true
        )
    end parseGetRequest

    private def executeQuery[R](
        interpreter: GraphQLInterpreter[R, CalibanError],
        request: GraphQLRequest,
        env: ZEnvironment[R]
    )(using Frame): GraphQLResponse[CalibanError] < Async =
        ZIOs.get(interpreter.executeRequest(request).provideEnvironment(env))
    end executeQuery

    private def encodeResponse(
        response: GraphQLResponse[CalibanError],
        headers: HttpHeaders
    ): HttpResponse["body" ~ Span[Byte]] =
        val acceptsGql = caliban.HttpUtils.AcceptsGqlEncodings(headers.get("accept").toOption).graphQLJson
        val isBadRequest =
            if acceptsGql then
                response.errors.exists {
                    case _: CalibanError.ParsingError | _: CalibanError.ValidationError => true
                    case _                                                              => false
                }
            else
                response.errors.contains(caliban.HttpUtils.MutationOverGetError)
        val toEncode       = if acceptsGql && isBadRequest then response.copy(data = NullValue) else response
        val bytes          = Span.fromUnsafe(writeToArray(toEncode)(using GraphQLResponse.jsoniterCodec))
        val status         = if isBadRequest then HttpStatus.BadRequest else HttpStatus.OK
        val ct             = if acceptsGql then "application/graphql-response+json" else "application/json"
        val resp           = HttpResponse(status).addField("body", bytes).setHeader("Content-Type", ct)
        val cacheDirective = response.extensions.flatMap(caliban.HttpUtils.computeCacheDirective)
        cacheDirective.fold(resp)(resp.cacheControl(_))
    end encodeResponse

    private def formatDeferPart(rv: ResponseValue): Span[Byte] =
        // InnerBoundary already includes boundary + Content-Type header + blank line
        val json = writeToString(rv)(using caliban.ResponseValue.jsoniterCodec)
        Span.fromUnsafe(s"${caliban.HttpUtils.DeferMultipart.InnerBoundary}$json".getBytes(java.nio.charset.StandardCharsets.UTF_8))
    end formatDeferPart

    private def buildHandlers[R](
        interpreter: GraphQLInterpreter[R, CalibanError],
        config: Config,
        env: ZEnvironment[R],
        hooks: WebSocketHooks[R, CalibanError]
    )(using Frame): Seq[HttpHandler[?, ?, ?]] =
        val path     = config.path
        val filter   = config.filter
        val graphiql = config.graphiql

        // POST (queries & mutations)
        val postRoute = HttpRoute.postRaw(path).request(_.bodyBinary).response(_.bodyBinary).filter(filter)
        val postHandler = postRoute.handler { req =>
            parseRequest(req.fields.body, req.headers) match
                case Left(err) =>
                    encodeResponse(GraphQLResponse(NullValue, List(err)), req.headers)
                case Right(request) =>
                    executeQuery(interpreter, request, env).map(encodeResponse(_, req.headers))
        }

        // GET (queries only)
        val getRoute = HttpRoute.getRaw(path)
            .request(_.queryOpt[String]("query")
                .queryOpt[String]("operationName")
                .queryOpt[String]("variables")
                .queryOpt[String]("extensions"))
            .response(_.bodyBinary)
            .filter(filter)
        val getHandler = getRoute.handler { req =>
            val gqlRequest = parseGetRequest(
                req.fields.query,
                req.fields.operationName,
                req.fields.variables,
                req.fields.extensions
            )
            executeQuery(interpreter, gqlRequest, env).map { response =>
                encodeResponse(response, req.headers)
            }
        }

        // SSE (subscriptions)
        val sseRoute = HttpRoute.postRaw(s"$path/sse").request(_.bodyBinary).response(_.bodySseText).filter(filter)
        val sseHandler = sseRoute.handler { req =>
            // Parse defensively; SSE replies with a single 'next' carrying the error then a 'complete'.
            val request: GraphQLRequest = parseRequest(req.fields.body, req.headers) match
                case Left(_)    => GraphQLRequest()
                case Right(req) => req
            executeQuery(interpreter, request, env).map { response =>
                val zioStream = caliban.HttpUtils.ServerSentEvents.transformResponse(
                    response,
                    rv => HttpSseEvent(writeToString(rv)(using caliban.ResponseValue.jsoniterCodec), event = Present("next")),
                    HttpSseEvent("", event = Present("complete"))
                )
                HttpResponse.ok.addField("body", ZStreams.get(zioStream))
            }
        }

        // @defer (multipart/mixed streaming)
        val deferRoute = HttpRoute.postRaw(s"$path/defer").request(_.bodyBinary).response(_.bodyStream).filter(filter)
        val deferHandler = deferRoute.handler { req =>
            val request: GraphQLRequest = parseRequest(req.fields.body, req.headers) match
                case Left(_)    => GraphQLRequest()
                case Right(req) => req
            executeQuery(interpreter, request, env).map { response =>
                // For an @defer query, the response.data is a StreamValue containing the incremental frames;
                // for a non-deferred query it's a plain ObjectValue. Extract the appropriate ZStream of
                // ResponseValues to feed into the multipart pipeline.
                val source: zio.stream.ZStream[Any, Throwable, ResponseValue] =
                    response.data match
                        case StreamValue(s)                          => s
                        case ObjectValue((_, StreamValue(s)) :: Nil) => s
                        case other                                   => zio.stream.ZStream.succeed(other)
                val pipeline  = caliban.HttpUtils.DeferMultipart.createPipeline(response)
                val zioStream = (source >>> pipeline).catchAll(_ => zio.stream.ZStream.empty)
                val parts     = ZStreams.get(zioStream).map(formatDeferPart)
                val endBytes =
                    Span.fromUnsafe(caliban.HttpUtils.DeferMultipart.EndBoundary.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                val stream = parts.concat(Stream.init(Chunk(endBytes)))
                val params = caliban.HttpUtils.DeferMultipart.DeferHeaderParams.map((k, v) => s"$k=$v").mkString("; ")
                HttpResponse.ok.addField("body", stream).setHeader("Content-Type", s"multipart/mixed; $params")
            }
        }

        // Upload (multipart)
        val uploadRoute = HttpRoute.postRaw(s"$path/upload").request(_.bodyMultipart).response(_.bodyBinary).filter(filter)
        val uploadHandler = uploadRoute.handler { req =>
            val parts = req.fields.body
            parts.find(_.name == "operations") match
                case None =>
                    val errResp: GraphQLResponse[CalibanError] =
                        GraphQLResponse(NullValue, List(CalibanError.ExecutionError("Missing 'operations' part")))
                    encodeResponse(errResp, req.headers)
                case Some(opsPart) =>
                    val parseResult: Either[CalibanError, (GraphQLRequest, Option[Map[String, List[String]]])] =
                        try
                            val ops = readFromArray[GraphQLRequest](opsPart.data.toArrayUnsafe)(using GraphQLRequest.jsoniterCodec)
                            val mapJson = parts.find(_.name == "map").map(p =>
                                readFromArray[Map[String, List[String]]](p.data.toArrayUnsafe)(using uploadMapCodec)
                            )
                            Right((ops, mapJson))
                        catch
                            case scala.util.control.NonFatal(t) =>
                                Left(CalibanError.ParsingError(s"Invalid upload payload: ${t.getMessage}"))
                    parseResult match
                        case Left(err) =>
                            encodeResponse(GraphQLResponse(NullValue, List(err)), req.headers)
                        case Right((operations, mapJson)) =>
                            val fileMap = parts.collect {
                                case part if part.filename.isDefined =>
                                    val fname = part.filename.get
                                    part.name -> FileMeta(
                                        part.name,
                                        part.data.toArrayUnsafe,
                                        part.contentType.toOption,
                                        fname,
                                        part.data.size.toLong
                                    )
                            }.toMap
                            val fileHandle = Uploads.handler(id => ZIO.succeed(fileMap.get(id)))
                            val pathMap = mapJson.getOrElse(Map.empty).map { (k, paths) =>
                                k -> paths.flatMap(_.split("\\.").toList.map(PathValue.parse))
                            }.toList
                            val remapped = GraphQLUploadRequest(operations, pathMap, fileHandle).remap
                            // Provide the Uploads service to the executor so resolvers calling Upload.allBytes / Upload.meta
                            // see the actual file map (without this, args.file.allBytes returns empty even though the file
                            // bytes were correctly multipart-decoded). Mirrors caliban-tapir's HttpUploadInterpreter.
                            val execZio = interpreter.executeRequest(remapped)
                                .provideSomeLayer[R](_root_.zio.ZLayer(fileHandle))
                                .provideEnvironment(env)
                            ZIOs.get(execZio).map(encodeResponse(_, req.headers))
                    end match
            end match
        }

        // WebSocket (subscriptions) at ${path}/ws
        val wsConfig = HttpWebSocket.Config(subprotocols = SupportedWsProtocols)
        val wsHandler = HttpHandler.webSocket(s"$path/ws", wsConfig) { (req, ws) =>
            runWebSocketProtocol(interpreter, env, hooks, config.webSocketKeepAlive, req, ws)
        }

        // GraphiQL
        val graphiqlHandler =
            if graphiql then
                val html          = caliban.HttpUtils.graphiqlHtml(s"/$path", s"/$path", None)
                val htmlBytes     = Span.fromUnsafe(html.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                val graphiqlRoute = HttpRoute.getRaw("graphiql").response(_.bodyBinary)
                Seq(graphiqlRoute.handler { _ =>
                    HttpResponse.ok(htmlBytes).setHeader("Content-Type", "text/html")
                })
            else Seq.empty

        Seq(postHandler, getHandler, sseHandler, deferHandler, uploadHandler, wsHandler) ++ graphiqlHandler
    end buildHandlers

    // ==================== WebSocket subprotocol bridge ====================

    private val SupportedWsProtocols = Seq("graphql-transport-ws", "graphql-ws")

    private def selectProtocolName(headers: HttpHeaders): String =
        // Pick the first supported subprotocol the client offered; default to the modern transport when none match.
        headers.get("Sec-WebSocket-Protocol") match
            case Present(offered) =>
                offered.split(',').iterator.map(_.trim).find(SupportedWsProtocols.contains).getOrElse(Protocol.GraphQLWS.name)
            case Absent => Protocol.GraphQLWS.name
    end selectProtocolName

    /** Bridges kyo-http's `HttpWebSocket` to caliban's `CalibanPipe`.
      *
      * Caliban single-sources the GraphQL-over-WS state machine in `caliban.ws.Protocol`; every official adapter (tapir, http4s, play,
      * zio-http) follows the same recipe: materialize the pipe via `Protocol.fromName(name).make(...)`, feed it a ZStream of
      * `GraphQLWSInput`, and forward the resulting `Either[GraphQLWSClose, GraphQLWSOutput]` stream out as WS frames. We do the same: a ZIO
      * `Queue[GraphQLWSInput]` carries inbound frames into the pipe; the output stream is consumed back into kyo via `ZStreams.get` and
      * written as text frames or a `ws.close`.
      *
      * The reader and writer run as concurrent kyo fibers via `Async.race`. Whichever completes first (peer close, server-initiated close,
      * pipe completion) tears down the other, which closes the ZStream scope inside `ZStreams.get`. That cascades into caliban's internal
      * pipe scope, interrupting subscription fibers and the keep-alive ping fiber.
      */
    private def runWebSocketProtocol[R](
        interpreter: GraphQLInterpreter[R, CalibanError],
        env: ZEnvironment[R],
        hooks: WebSocketHooks[R, CalibanError],
        keepAlive: Maybe[Duration],
        request: HttpRequest[Any],
        ws: HttpWebSocket
    )(using Frame): Unit < (Async & Abort[Closed]) =
        val protocolName = selectProtocolName(request.headers)
        val zioKeepAlive = keepAlive.toOption.map(d => zio.Duration.fromNanos(d.toNanos))

        val setup: ZIO[Any, Nothing, (zio.Queue[GraphQLWSInput], CalibanPipe)] =
            (for
                inputQ <- zio.Queue.unbounded[GraphQLWSInput]
                pipe   <- Protocol.fromName(protocolName).make(interpreter, zioKeepAlive, hooks)
            yield (inputQ, pipe)).provideEnvironment(env)

        ZIOs.get(setup).map { case (inputQ, pipe) =>
            val outputStream: ZStream[Any, Throwable, Either[GraphQLWSClose, GraphQLWSOutput]] =
                pipe(ZStream.fromQueueWithShutdown(inputQ))

            val reader: Unit < Async =
                Abort.recover[Closed](_ => ()) {
                    Loop.foreach {
                        ws.take().map {
                            case HttpWebSocket.Payload.Text(json) =>
                                scala.util.Try(readFromString[GraphQLWSInput](json)(using GraphQLWSInput.jsoniterCodec)).toOption match
                                    case None =>
                                        Abort.recover[Closed](_ => ()) {
                                            ws.close(4400, "Invalid message")
                                        }.andThen(Loop.done)
                                    case Some(input) =>
                                        ZIOs.get(inputQ.offer(input)).andThen(Loop.continue)
                            case HttpWebSocket.Payload.Binary(_) =>
                                // Both subprotocols are text-only; silently ignore binary frames so a misbehaving client can't break the pipe.
                                Loop.continue
                        }
                    }
                }

            val writer: Unit < Async =
                Abort.recover[Closed](_ => ()) {
                    Abort.recover[Throwable](
                        onFail = t => Log.warn(s"WS pipe failure: ${t.getMessage}", t).unit,
                        onPanic = t => Log.error("WS pipe panic", t).unit
                    ) {
                        ZStreams.get(outputStream).foreach {
                            case Right(out) =>
                                val text = writeToString(out)(using GraphQLWSOutput.jsoniterCodec)
                                ws.put(HttpWebSocket.Payload.Text(text))
                            case Left(close) =>
                                ws.close(close.code, close.reason)
                        }
                    }
                }

            Async.race[Nothing, Unit, Any](Seq(reader, writer))
        }
    end runWebSocketProtocol

    given isolate: Isolate[Resolvers, Abort[CalibanError] & Async, Any] =
        Isolate.derive[Abort[CalibanError] & Async, Abort[CalibanError] & Async, Any]

end Resolvers
