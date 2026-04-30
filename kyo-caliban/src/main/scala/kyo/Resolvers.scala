package kyo

import caliban.*
import caliban.CalibanError
import caliban.Configurator.ExecutionConfiguration
import caliban.ResponseValue.*
import caliban.Value.*
import caliban.execution.QueryExecution
import caliban.uploads.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import zio.ZEnvironment
import zio.ZIO

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
        allowMutationsOverGetRequests: Boolean
    ):
        def path(path: String): Config                              = copy(path = path)
        def filter(filter: HttpFilter.Passthrough[Nothing]): Config = copy(filter = filter)
        def graphiql(graphiql: Boolean): Config                     = copy(graphiql = graphiql)
        def enableIntrospection(enabled: Boolean): Config           = copy(enableIntrospection = enabled)
        def skipValidation(skip: Boolean): Config                   = copy(skipValidation = skip)
        def queryExecution(execution: QueryExecution): Config       = copy(queryExecution = execution)
        def allowMutationsOverGetRequests(allow: Boolean): Config   = copy(allowMutationsOverGetRequests = allow)
    end Config

    object Config:
        val default: Config = Config(
            path = "api/graphql",
            filter = HttpFilter.noop,
            graphiql = true,
            enableIntrospection = true,
            skipValidation = false,
            queryExecution = QueryExecution.Parallel,
            allowMutationsOverGetRequests = false
        )
    end Config

    /** Runs a GraphQL server from an interpreter with the given configuration.
      *
      * @param interpreter
      *   The GraphQL interpreter to serve
      * @param config
      *   The server configuration (path, filter, graphiql, etc.)
      * @param Frame
      *   Implicit Frame parameter
      * @return
      *   An HttpServer wrapped in Async and Scope effects
      */
    def run(
        interpreter: GraphQLInterpreter[Any, CalibanError],
        config: Config = Config.default
    )(using Frame): HttpServer < (Async & Scope) =
        val wrapped = CalibanHttpUtils.configuredInterpreter(interpreter, toExecutionConfig(config))
        HttpServer.init(buildHandlers(wrapped, config.path, ZEnvironment.empty, config.filter, config.graphiql)*)
    end run

    /** Runs a GraphQL server with a custom Runner for arbitrary kyo effects.
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
        val wrapped = CalibanHttpUtils.configuredInterpreter(interpreter, toExecutionConfig(config))
        HttpServer.init(buildHandlers(wrapped, config.path, ZEnvironment(runner), config.filter, config.graphiql)*)
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
        run(interpreter, runner, Config.default)
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

    private def parseRequest(body: Span[Byte], headers: HttpHeaders): GraphQLRequest =
        val ct = headers.get("content-type")
        val request =
            if ct.exists(_.contains("application/graphql")) then
                GraphQLRequest(query = Some(new String(body.toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8)))
            else
                readFromArray[GraphQLRequest](body.toArrayUnsafe)(using GraphQLRequest.jsoniterCodec)
        if headers.get("apollo-federation-include-trace").exists(_ == "ftv1") then
            request.withFederatedTracing
        else request
    end parseRequest

    private def parseInputMap(json: String): Map[String, caliban.InputValue] =
        readFromString[caliban.InputValue](json)(using CalibanHttpUtils.inputValueCodec) match
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
        val acceptsGql = CalibanHttpUtils.acceptsGqlJson(headers.get("accept").toOption)
        val isBadRequest =
            if acceptsGql then
                response.errors.exists {
                    case _: CalibanError.ParsingError | _: CalibanError.ValidationError => true
                    case _                                                              => false
                }
            else
                CalibanHttpUtils.isMutationOverGetError(response.errors)
        val toEncode       = if acceptsGql && isBadRequest then response.copy(data = NullValue) else response
        val bytes          = Span.fromUnsafe(writeToArray(toEncode)(using GraphQLResponse.jsoniterCodec))
        val status         = if isBadRequest then HttpStatus.BadRequest else HttpStatus.OK
        val ct             = if acceptsGql then "application/graphql-response+json" else "application/json"
        val resp           = HttpResponse(status).addField("body", bytes).setHeader("Content-Type", ct)
        val cacheDirective = response.extensions.flatMap(CalibanHttpUtils.computeCacheDirective)
        cacheDirective.fold(resp)(resp.cacheControl(_))
    end encodeResponse

    private def formatDeferPart(rv: ResponseValue): Span[Byte] =
        // InnerBoundary already includes boundary + Content-Type header + blank line
        val json = writeToString(rv)(using CalibanHttpUtils.responseValueCodec)
        Span.fromUnsafe(s"${CalibanHttpUtils.DeferMultipart.innerBoundary}$json".getBytes(java.nio.charset.StandardCharsets.UTF_8))
    end formatDeferPart

    private def buildHandlers[R](
        interpreter: GraphQLInterpreter[R, CalibanError],
        path: String,
        env: ZEnvironment[R],
        filter: HttpFilter.Passthrough[Nothing],
        graphiql: Boolean
    )(using Frame): Seq[HttpHandler[?, ?, ?]] =

        // POST (queries & mutations)
        val postRoute = HttpRoute.postRaw(path).request(_.bodyBinary).response(_.bodyBinary).filter(filter)
        val postHandler = postRoute.handler { req =>
            executeQuery(interpreter, parseRequest(req.fields.body, req.headers), env).map { response =>
                encodeResponse(response, req.headers)
            }
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
            executeQuery(interpreter, parseRequest(req.fields.body, req.headers), env).map { response =>
                val zioStream = CalibanHttpUtils.transformSseResponse(
                    response,
                    rv => HttpSseEvent(writeToString(rv)(using CalibanHttpUtils.responseValueCodec), event = Present("next")),
                    HttpSseEvent("", event = Present("complete"))
                )
                HttpResponse.ok.addField("body", ZStreams.get(zioStream))
            }
        }

        // @defer (multipart/mixed streaming)
        val deferRoute = HttpRoute.postRaw(s"$path/defer").request(_.bodyBinary).response(_.bodyStream).filter(filter)
        val deferHandler = deferRoute.handler { req =>
            executeQuery(interpreter, parseRequest(req.fields.body, req.headers), env).map { response =>
                val pipeline  = CalibanHttpUtils.DeferMultipart.createPipeline(response)
                val zioStream = (zio.stream.ZStream(response.data) >>> pipeline).catchAll(_ => zio.stream.ZStream.empty)
                val parts     = ZStreams.get(zioStream).map(formatDeferPart)
                val endBytes =
                    Span.fromUnsafe(CalibanHttpUtils.DeferMultipart.endBoundary.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                val stream = parts.concat(Stream.init(Chunk(endBytes)))
                val params = CalibanHttpUtils.DeferMultipart.headerParams.map((k, v) => s"$k=$v").mkString("; ")
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
                    val operations = readFromArray[GraphQLRequest](opsPart.data.toArrayUnsafe)(using GraphQLRequest.jsoniterCodec)
                    val mapJson = parts.find(_.name == "map").map(p =>
                        readFromArray[Map[String, List[String]]](p.data.toArrayUnsafe)(using uploadMapCodec)
                    )
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
                    executeQuery(interpreter, remapped, env).map(encodeResponse(_, req.headers))
            end match
        }

        // GraphiQL
        val graphiqlHandler =
            if graphiql then
                val html          = CalibanHttpUtils.graphiqlHtml(s"/$path")
                val htmlBytes     = Span.fromUnsafe(html.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                val graphiqlRoute = HttpRoute.getRaw("graphiql").response(_.bodyBinary)
                Seq(graphiqlRoute.handler { _ =>
                    HttpResponse.ok(htmlBytes).setHeader("Content-Type", "text/html")
                })
            else Seq.empty

        Seq(postHandler, getHandler, sseHandler, deferHandler, uploadHandler) ++ graphiqlHandler
    end buildHandlers

    given isolate: Isolate[Resolvers, Abort[CalibanError] & Async, Any] =
        Isolate.derive[Abort[CalibanError] & Async, Abort[CalibanError] & Async, Any]

end Resolvers
