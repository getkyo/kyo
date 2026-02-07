package kyo

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.{Channel as NettyChannel, *}
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.flush.FlushConsolidationHandler
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kyo.HttpRequest.Method
import kyo.HttpResponse.Status
import kyo.internal.HttpServerHandler
import kyo.internal.NettyTransport
import kyo.internal.NettyUtil
import scala.annotation.nowarn
import scala.annotation.tailrec

final class HttpServer private (
    private val channel: NettyChannel,
    private val bossGroup: MultiThreadIoEventLoopGroup,
    private val workerGroup: MultiThreadIoEventLoopGroup,
    private val boundAddress: InetSocketAddress,
    private val handlers: Seq[HttpHandler[Any]]
):
    def port: Int    = boundAddress.getPort
    def host: String = boundAddress.getHostString

    def stopNow(using Frame): Unit < Async =
        stop(Duration.Zero)

    def stop(using Frame): Unit < Async =
        stop(30.seconds)

    def stop(gracePeriod: Duration)(using Frame): Unit < Async =
        val graceMs = gracePeriod.toMillis
        NettyUtil.continue(channel.close()) { _ =>
            NettyUtil.continue(bossGroup.shutdownGracefully(graceMs, graceMs, TimeUnit.MILLISECONDS)) { _ =>
                NettyUtil.await(workerGroup.shutdownGracefully(graceMs, graceMs, TimeUnit.MILLISECONDS))
            }
        }
    end stop

    def await(using Frame): Unit < Async =
        NettyUtil.await(channel.closeFuture())

    def openApi: HttpOpenApi =
        HttpOpenApi.fromHandlers(handlers*)

    def openApi(title: String, version: String = "1.0.0", description: Maybe[String] = Absent): HttpOpenApi =
        HttpOpenApi.fromHandlers(HttpOpenApi.Config(title, version, description))(handlers*)

end HttpServer

object HttpServer:

    // --- Factory methods ---

    def init(handlers: HttpHandler[Any]*)(using Frame): HttpServer < Async =
        init(Config.default)(handlers*)

    def init(config: Config)(handlers: HttpHandler[Any]*)(using Frame): HttpServer < Async =
        // Capture filter from Local to apply per-request
        HttpFilter.use { filter =>
            Sync.defer {
                // Add OpenAPI handler if configured
                val allHandlers = config.openApi match
                    case Present(openApiConfig) =>
                        val spec = HttpOpenApi.fromHandlers(
                            HttpOpenApi.Config(openApiConfig.title, openApiConfig.version, openApiConfig.description)
                        )(handlers*)
                        val json = spec.toJson
                        val openApiHandler = HttpHandler.get(openApiConfig.path) { (_, _) =>
                            HttpResponse.ok(json).addHeader("Content-Type", "application/json")
                        }
                        handlers :+ openApiHandler
                    case Absent =>
                        handlers

                val bossGroup   = new MultiThreadIoEventLoopGroup(1, NettyTransport.ioHandlerFactory)
                val workerGroup = new MultiThreadIoEventLoopGroup(NettyTransport.ioHandlerFactory)

                val bootstrap = new ServerBootstrap()
                discard {
                    bootstrap
                        .group(bossGroup, workerGroup)
                        .channel(NettyTransport.serverSocketChannelClass)
                        .childHandler(new ChannelInitializer[SocketChannel]:
                            override def initChannel(ch: SocketChannel): Unit =
                                val pipeline = ch.pipeline()
                                discard(pipeline.addLast(new FlushConsolidationHandler(
                                    config.flushConsolidationLimit,
                                    true
                                )))
                                discard(pipeline.addLast(new HttpServerCodec()))
                                discard(pipeline.addLast(new HttpServerHandler(
                                    allHandlers,
                                    config.maxContentLength,
                                    config.strictCookieParsing,
                                    filter
                                ))))
                        .option(ChannelOption.SO_BACKLOG, Integer.valueOf(config.backlog))
                        .childOption(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.valueOf(config.keepAlive))
                        .childOption(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
                }
                if config.tcpFastOpen then
                    NettyTransport.applyTcpFastOpen(bootstrap, config.backlog)

                val bindFuture = bootstrap.bind(config.host, config.port)

                NettyUtil.continue(bindFuture) { channel =>
                    // Safe cast: NioServerSocketChannel always returns InetSocketAddress
                    val address = channel.localAddress().asInstanceOf[InetSocketAddress]
                    new HttpServer(channel, bossGroup, workerGroup, address, allHandlers)
                }
            }
        }
    end init

    def init(
        port: Int = Config.default.port,
        host: String = Config.default.host,
        maxContentLength: Int = Config.default.maxContentLength,
        idleTimeout: Duration = Config.default.idleTimeout
    )(handlers: HttpHandler[Any]*)(using Frame): HttpServer < Async =
        init(Config(port, host, maxContentLength, idleTimeout))(handlers*)

    // --- Config ---

    case class Config(
        port: Int = 8080,
        host: String = "0.0.0.0",
        maxContentLength: Int = 65536,
        idleTimeout: Duration = 60.seconds,
        strictCookieParsing: Boolean = false,
        backlog: Int = 128,
        keepAlive: Boolean = true,
        tcpFastOpen: Boolean = false,
        flushConsolidationLimit: Int = 256,
        openApi: Maybe[Config.OpenApiEndpoint] = Absent
    ):
        require(port >= 0 && port <= 65535, s"Port must be between 0 and 65535: $port")
        require(host.nonEmpty, "Host cannot be empty")
        require(maxContentLength > 0, s"maxContentLength must be positive: $maxContentLength")
        require(idleTimeout > Duration.Zero, s"idleTimeout must be positive: $idleTimeout")
        require(backlog > 0, s"backlog must be positive: $backlog")
        require(flushConsolidationLimit > 0, s"flushConsolidationLimit must be positive: $flushConsolidationLimit")

        def port(p: Int): Config                    = copy(port = p)
        def host(h: String): Config                 = copy(host = h)
        def maxContentLength(n: Int): Config        = copy(maxContentLength = n)
        def idleTimeout(d: Duration): Config        = copy(idleTimeout = d)
        def strictCookieParsing(b: Boolean): Config = copy(strictCookieParsing = b)
        def backlog(n: Int): Config                 = copy(backlog = n)
        def keepAlive(b: Boolean): Config           = copy(keepAlive = b)
        def tcpFastOpen(b: Boolean): Config         = copy(tcpFastOpen = b)
        def flushConsolidationLimit(n: Int): Config = copy(flushConsolidationLimit = n)
        def openApi(path: String = "/openapi.json", title: String = "API", version: String = "1.0.0"): Config =
            copy(openApi = Present(Config.OpenApiEndpoint(path, title, version)))
    end Config

    object Config:
        val default: Config = Config()

        case class OpenApiEndpoint(
            path: String = "/openapi.json",
            title: String = "API",
            version: String = "1.0.0",
            description: Maybe[String] = Absent
        )
    end Config

end HttpServer

sealed abstract class HttpHandler[-S]:
    def route: HttpRoute[?, ?, ?]
    private[kyo] def streamingRequest: Boolean = false
    private[kyo] def apply(request: HttpRequest[?]): kyo.HttpResponse[?] < (Async & S)
end HttpHandler

object HttpHandler:

    // Note on asInstanceOf casts in this method:
    // - Schema[Any] casts are justified due to type erasure (Schema[?] loses type info)
    // - fullInput.asInstanceOf[In] bridges runtime-extracted values to compile-time types
    //   (the route DSL builds In type via match types, but runtime extraction is dynamic)
    // - Abort.run[Any] cast is needed because Err is erased at runtime
    @nowarn("msg=anonymous")
    inline def init[In, Out, Err, S](r: HttpRoute[In, Out, Err])(inline f: In => Out < (Abort[Err] & Async & S))(using
        frame: Frame
    ): HttpHandler[S] =
        new HttpHandler[S]:
            val route: HttpRoute[?, ?, ?] = r
            private[kyo] def apply(request: HttpRequest[?]): kyo.HttpResponse[?] < (Async & S) =
                // Safe cast: server guarantees buffered body for non-streaming handlers
                val req       = request.asInstanceOf[HttpRequest[HttpBody.Bytes]]
                val pathInput = extractPathParams(r.path, req.path)
                // Extract query parameters
                val queryInput = extractQueryParams(r.queryParams, req)
                // Extract header parameters
                val headerInput = extractHeaderParams(r.headerParams, req)
                // Extract body if there's an input schema (cast justified: Schema[?] loses type info)
                val bodyInput = r.inputSchema match
                    case Present(schema) =>
                        schema.asInstanceOf[Schema[Any]].decode(req.bodyText)
                    case Absent =>
                        ()
                // Combine all inputs in order: path, query, headers, body
                val fullInput = combineAllInputs(pathInput, queryInput, headerInput, bodyInput)
                // Call the handler function (cast justified: In is computed via match types at compile time,
                // but fullInput is built dynamically at runtime)
                val computation = f(fullInput.asInstanceOf[In]).map { output =>
                    r.outputSchema match
                        case Present(s) =>
                            // Cast justified: Schema[?] loses type info due to erasure
                            val json = s.asInstanceOf[Schema[Out]].encode(output)
                            kyo.HttpResponse.json(json)
                        case Absent =>
                            kyo.HttpResponse.ok
                }
                // Cast justified: Err is erased at runtime, so Abort.run needs Any as the type parameter.
                // Abort.recover would also need the cast and doesn't handle Panic, so Abort.run is the right choice.
                Abort.run[Any](computation.asInstanceOf[kyo.HttpResponse[?] < (Abort[Any] & Async & S)]).map {
                    case Result.Success(resp) => resp
                    case Result.Failure(err)  =>
                        // Try to find matching error schema and return appropriate response
                        findErrorResponse(err, r.errorSchemas).getOrElse(kyo.HttpResponse.serverError(err.toString))
                    case Result.Panic(ex) => kyo.HttpResponse.serverError(ex.getMessage)
                }
            end apply

    @nowarn("msg=anonymous")
    inline def init[A, S](
        method: Method,
        path: HttpPath[A]
    )(inline f: (A, HttpRequest[HttpBody.Bytes]) => kyo.HttpResponse[?] < (Async & S))(using
        Frame
    ): HttpHandler[S] =
        new HttpHandler[S]:
            val route: HttpRoute[?, ?, ?] = HttpRoute(method, path.asInstanceOf[HttpPath[Any]], Status.OK, Absent, Absent)
            private[kyo] def apply(request: HttpRequest[?]): kyo.HttpResponse[?] < (Async & S) =
                //
                // Safe cast: server guarantees buffered body for non-streaming handlers
                // Note: this overload only works correctly for HttpPath[Unit] (no captures).
                // Paths with captures should use the typed route overload which calls extractPathParams.
                f(().asInstanceOf[A], request.asInstanceOf[HttpRequest[HttpBody.Bytes]])

    def health(path: HttpPath[Unit] = "/health")(using Frame): HttpHandler[Any] =
        init(Method.GET, path)((_, _) => kyo.HttpResponse.ok("healthy"))

    def const[A](method: Method, path: HttpPath[A], status: Status)(using Frame): HttpHandler[Any] =
        init(method, path)((_, _) => kyo.HttpResponse(status))

    def const[A](method: Method, path: HttpPath[A], response: kyo.HttpResponse[?])(using Frame): HttpHandler[Any] =
        init(method, path)((_, _) => response)

    inline def get[A, S](path: HttpPath[A])(inline f: (A, HttpRequest[HttpBody.Bytes]) => kyo.HttpResponse[?] < (Async & S))(using
        Frame
    ): HttpHandler[S] =
        init(Method.GET, path)(f)

    inline def post[A, S](path: HttpPath[A])(inline f: (A, HttpRequest[HttpBody.Bytes]) => kyo.HttpResponse[?] < (Async & S))(using
        Frame
    ): HttpHandler[S] =
        init(Method.POST, path)(f)

    inline def put[A, S](path: HttpPath[A])(inline f: (A, HttpRequest[HttpBody.Bytes]) => kyo.HttpResponse[?] < (Async & S))(using
        Frame
    ): HttpHandler[S] =
        init(Method.PUT, path)(f)

    inline def patch[A, S](path: HttpPath[A])(inline f: (A, HttpRequest[HttpBody.Bytes]) => kyo.HttpResponse[?] < (Async & S))(using
        Frame
    ): HttpHandler[S] =
        init(Method.PATCH, path)(f)

    inline def delete[A, S](path: HttpPath[A])(inline f: (A, HttpRequest[HttpBody.Bytes]) => kyo.HttpResponse[?] < (Async & S))(using
        Frame
    ): HttpHandler[S] =
        init(Method.DELETE, path)(f)

    inline def head[A, S](path: HttpPath[A])(inline f: (A, HttpRequest[HttpBody.Bytes]) => kyo.HttpResponse[?] < (Async & S))(using
        Frame
    ): HttpHandler[S] =
        init(Method.HEAD, path)(f)

    inline def options[A, S](path: HttpPath[A])(inline f: (A, HttpRequest[HttpBody.Bytes]) => kyo.HttpResponse[?] < (Async & S))(using
        Frame
    ): HttpHandler[S] =
        init(Method.OPTIONS, path)(f)

    // --- Streaming request body ---

    /** Creates a handler that receives a streaming request body.
      *
      * The handler receives `HttpRequest[HttpBody.Streamed]` whose `bodyStream` delivers chunks as they arrive from the client, without
      * buffering the entire body in memory.
      */
    @nowarn("msg=anonymous")
    inline def streamingBody[A, S](
        method: Method,
        path: HttpPath[A]
    )(inline f: (A, HttpRequest[HttpBody.Streamed]) => kyo.HttpResponse[?] < (Async & S))(using
        Frame
    ): HttpHandler[S] =
        new HttpHandler[S]:
            val route: HttpRoute[?, ?, ?] = HttpRoute(method, path.asInstanceOf[HttpPath[Any]], Status.OK, Absent, Absent)
            override private[kyo] def streamingRequest: Boolean = true
            private[kyo] def apply(request: HttpRequest[?]): kyo.HttpResponse[?] < (Async & S) =
                val pathInput = extractPathParams(path.asInstanceOf[HttpPath[Any]], request.path)
                // Safe cast: server guarantees streaming body for streaming handlers
                f(pathInput.asInstanceOf[A], request.asInstanceOf[HttpRequest[HttpBody.Streamed]])
            end apply
        end new
    end streamingBody

    // --- SSE streaming ---

    inline def streamSse[A, V: Schema: Tag, S](
        path: HttpPath[A]
    )(
        inline f: (A, HttpRequest[HttpBody.Bytes]) => Stream[ServerSentEvent[V], Async & S]
    )(using Frame): HttpHandler[S] =
        streamSse(Method.GET, path)(f)

    inline def streamSse[A, V: Schema: Tag, S](
        method: Method,
        path: HttpPath[A]
    )(
        inline f: (A, HttpRequest[HttpBody.Bytes]) => Stream[ServerSentEvent[V], Async & S]
    )(using Frame): HttpHandler[S] =
        val schema = Schema[V]
        new HttpHandler[S]:
            val route = HttpRoute(method, path.asInstanceOf[HttpPath[Any]], Status.OK, Absent, Absent)
            private[kyo] def apply(request: HttpRequest[?]) =
                // Safe cast: server guarantees buffered body for non-streaming handlers
                val req       = request.asInstanceOf[HttpRequest[HttpBody.Bytes]]
                val pathInput = extractPathParams(path.asInstanceOf[HttpPath[Any]], req.path)
                val stream    = f(pathInput.asInstanceOf[A], req)
                HttpResponse.streamSse(stream.asInstanceOf[Stream[ServerSentEvent[Any], Async]])(using schema.asInstanceOf[Schema[Any]])
            end apply
        end new
    end streamSse

    // --- NDJSON streaming ---

    inline def streamNdjson[A, V: Schema: Tag, S](
        path: HttpPath[A]
    )(
        inline f: (A, HttpRequest[HttpBody.Bytes]) => Stream[V, Async & S]
    )(using Frame): HttpHandler[S] =
        streamNdjson(Method.GET, path)(f)

    inline def streamNdjson[A, V: Schema: Tag, S](
        method: Method,
        path: HttpPath[A]
    )(
        inline f: (A, HttpRequest[HttpBody.Bytes]) => Stream[V, Async & S]
    )(using Frame): HttpHandler[S] =
        val schema = Schema[V]
        new HttpHandler[S]:
            val route = HttpRoute(method, path.asInstanceOf[HttpPath[Any]], Status.OK, Absent, Absent)
            private[kyo] def apply(request: HttpRequest[?]) =
                // Safe cast: server guarantees buffered body for non-streaming handlers
                val req       = request.asInstanceOf[HttpRequest[HttpBody.Bytes]]
                val pathInput = extractPathParams(path.asInstanceOf[HttpPath[Any]], req.path)
                val stream    = f(pathInput.asInstanceOf[A], req)
                HttpResponse.streamNdjson(stream.asInstanceOf[Stream[Any, Async]])(using schema.asInstanceOf[Schema[Any]])
            end apply
        end new
    end streamNdjson

    /** Creates a stub handler that preserves route metadata but returns a fixed response. For OpenAPI spec generation only. */
    private[kyo] def stub(r: HttpRoute[?, ?, ?]): HttpHandler[Any] =
        new HttpHandler[Any]:
            val route: HttpRoute[?, ?, ?] = r
            private[kyo] def apply(request: HttpRequest[?]): kyo.HttpResponse[?] < Async =
                kyo.HttpResponse.ok

    // --- Private implementation ---

    // Try to encode an error using registered error schemas and return appropriate HTTP response
    private def findErrorResponse(err: Any, errorSchemas: Seq[(HttpResponse.Status, Schema[?])]): Option[kyo.HttpResponse[HttpBody.Bytes]] =
        @tailrec def loop(remaining: Seq[(HttpResponse.Status, Schema[?])]): Option[kyo.HttpResponse[HttpBody.Bytes]] =
            remaining match
                case Seq() => None
                case (status, schema) +: tail =>
                    try
                        val json = schema.asInstanceOf[Schema[Any]].encode(err)
                        Some(kyo.HttpResponse(status, json).addHeader("Content-Type", "application/json"))
                    catch
                        case _: Exception => loop(tail)
        loop(errorSchemas)
    end findErrorResponse

    // Note: isInstanceOf[Unit] is used here because we're working with Any at runtime.
    // At compile time, the Inputs[A, B] match type handles Unit specially, but at runtime
    // we need to detect Unit values to avoid wrapping them in tuples. This is a consequence
    // of the type-level DSL design where compile-time types don't match runtime representations.
    private def combineInputs(a: Any, b: Any): Any =
        if a.isInstanceOf[Unit] then b
        else if b.isInstanceOf[Unit] then a
        else
            (a, b) match
                case (v1: Tuple, v2: Tuple) => Tuple.fromArray((v1.toArray ++ v2.toArray))
                case (v1: Tuple, v2)        => Tuple.fromArray(v1.toArray :+ v2)
                case (v1, v2: Tuple)        => Tuple.fromArray(v1 +: v2.toArray)
                case (v1, v2)               => (v1, v2)

    private def combineAllInputs(pathInput: Any, queryInput: Any, headerInput: Any, bodyInput: Any): Any =
        val combined1 = combineInputs(pathInput, queryInput)
        val combined2 = combineInputs(combined1, headerInput)
        combineInputs(combined2, bodyInput)
    end combineAllInputs

    private def extractQueryParams(queryParams: Seq[HttpRoute.QueryParam[?]], request: HttpRequest[?]): Any =
        val size = queryParams.size
        if size == 0 then ()
        else if size == 1 then
            extractQueryParam(queryParams.head, request)
        else
            val arr = new Array[Any](size)
            @tailrec def loop(i: Int): Unit =
                if i < size then
                    arr(i) = extractQueryParam(queryParams(i), request)
                    loop(i + 1)
            loop(0)
            Tuple.fromArray(arr)
        end if
    end extractQueryParams

    private def extractQueryParam(param: HttpRoute.QueryParam[?], request: HttpRequest[?]): Any =
        request.query(param.name) match
            case Present(value) =>
                // Decode using the schema
                param.schema.asInstanceOf[Schema[Any]].decode(value)
            case Absent =>
                param.default match
                    case Present(d) => d
                    case Absent     => throw new IllegalArgumentException(s"Missing required query parameter: ${param.name}")

    private def extractHeaderParams(headerParams: Seq[HttpRoute.HeaderParam], request: HttpRequest[?]): Any =
        val size = headerParams.size
        if size == 0 then ()
        else if size == 1 then
            extractHeaderParam(headerParams.head, request)
        else
            val arr = new Array[Any](size)
            @tailrec def loop(i: Int): Unit =
                if i < size then
                    arr(i) = extractHeaderParam(headerParams(i), request)
                    loop(i + 1)
            loop(0)
            Tuple.fromArray(arr)
        end if
    end extractHeaderParams

    private def extractHeaderParam(param: HttpRoute.HeaderParam, request: HttpRequest[?]): String =
        request.header(param.name) match
            case Present(value) => value
            case Absent =>
                param.default match
                    case Present(d) => d
                    case Absent     => throw new IllegalArgumentException(s"Missing required header: ${param.name}")

    private def extractPathParams(routePath: HttpPath[Any], requestPath: String): Any =
        routePath match
            case s: String => ()
            case segment: HttpPath.Segment[?] =>
                val parts = HttpPath.parseSegments(requestPath)
                extractFromSegment(segment, parts)._1

    private def extractFromSegment(segment: HttpPath.Segment[?], parts: List[String]): (Any, List[String]) =
        segment match
            case HttpPath.Segment.Literal(v) =>
                // Skip literal parts
                val literalSize = HttpPath.countSegments(v)
                ((), parts.drop(literalSize))
            case HttpPath.Segment.Capture(_, parse) =>
                val value = parse(parts.head)
                (value, parts.tail)
            case HttpPath.Segment.Concat(left, right) =>
                val (leftVal, remaining)   = extractFromSegment(left.asInstanceOf[HttpPath.Segment[?]], parts)
                val (rightVal, remaining2) = extractFromSegment(right.asInstanceOf[HttpPath.Segment[?]], remaining)
                val combined =
                    if leftVal.isInstanceOf[Unit] then rightVal
                    else if rightVal.isInstanceOf[Unit] then leftVal
                    else
                        (leftVal, rightVal) match
                            case (v1: Tuple, v2: Tuple) => Tuple.fromArray((v1.toArray ++ v2.toArray))
                            case (v1: Tuple, v2)        => Tuple.fromArray(v1.toArray :+ v2)
                            case (v1, v2: Tuple)        => Tuple.fromArray(v1 +: v2.toArray)
                            case (v1, v2)               => (v1, v2)
                (combined, remaining2)

end HttpHandler
