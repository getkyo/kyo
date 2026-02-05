package kyo

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.FullHttpRequest as NettyFullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.flush.FlushConsolidationHandler
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kyo.HttpRequest.Method
import kyo.HttpResponse.Status
import kyo.HttpRoute.Path
import kyo.internal.HttpRouter
import kyo.internal.NettyTransport
import kyo.internal.NettyUtil
import kyo.internal.OpenApiGenerator
import kyo.internal.PathUtil
import scala.annotation.nowarn
import scala.annotation.tailrec

final class HttpServer private (
    private val channel: Channel,
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
        NettyUtil.channelFuture(channel.close()).andThen {
            NettyUtil.future(bossGroup.shutdownGracefully(graceMs, graceMs, TimeUnit.MILLISECONDS)).andThen {
                NettyUtil.future(workerGroup.shutdownGracefully(graceMs, graceMs, TimeUnit.MILLISECONDS)).unit
            }
        }
    end stop

    def await(using Frame): Unit < Async =
        NettyUtil.channelFuture(channel.closeFuture()).unit

    // TODO let's have an HttpOpenApi impl in the kyo package for this. It'll be a class with the equivalent of OpenApiGenerator.OpenApi (not opauqe type) and a metrhod to serialize it to json. We don't need the Spec and string methods separation here. Return HttpOpenApi and then the user can decide to get the json
    def openApiSpec: OpenApiGenerator.OpenApi =
        OpenApiGenerator.generate(handlers)

    def openApiSpec(title: String, version: String = "1.0.0", description: Maybe[String] = Absent): OpenApiGenerator.OpenApi =
        OpenApiGenerator.generate(handlers, OpenApiGenerator.Config(title, version, description))

    def openApi: String =
        Schema[OpenApiGenerator.OpenApi].encode(openApiSpec)

    def openApi(title: String, version: String = "1.0.0", description: Maybe[String] = Absent): String =
        Schema[OpenApiGenerator.OpenApi].encode(openApiSpec(title, version, description))

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
                        val spec = OpenApiGenerator.generate(
                            handlers,
                            OpenApiGenerator.Config(openApiConfig.title, openApiConfig.version, openApiConfig.description)
                        )
                        val json = Schema[OpenApiGenerator.OpenApi].encode(spec)
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
                                discard(pipeline.addLast(new FlushConsolidationHandler(256, true)))
                                discard(pipeline.addLast(new HttpServerCodec()))
                                discard(pipeline.addLast(new HttpObjectAggregator(config.maxContentLength)))
                                discard(pipeline.addLast(new HttpServerHandler(allHandlers, config.strictCookieParsing, filter))))
                        .option(ChannelOption.SO_BACKLOG, Integer.valueOf(config.backlog))
                        .childOption(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.valueOf(config.keepAlive))
                        .childOption(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
                }
                if config.tcpFastOpen then
                    NettyTransport.applyTcpFastOpen(bootstrap, config.backlog)

                val bindFuture = bootstrap.bind(config.host, config.port)

                // TODO let's implement an optimization here. Add a second function parameter (separate param group for nice syntax) to channelFuture that takes the continuation we're setting here via `.map`
                NettyUtil.channelFuture(bindFuture).map { channel =>
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
        openApi: Maybe[Config.OpenApi] = Absent
    ):
        require(port >= 0 && port <= 65535, s"Port must be between 0 and 65535: $port")
        require(host.nonEmpty, "Host cannot be empty")
        require(maxContentLength > 0, s"maxContentLength must be positive: $maxContentLength")
        require(idleTimeout > Duration.Zero, s"idleTimeout must be positive: $idleTimeout")
        require(backlog > 0, s"backlog must be positive: $backlog")

        def port(p: Int): Config                    = copy(port = p)
        def host(h: String): Config                 = copy(host = h)
        def maxContentLength(n: Int): Config        = copy(maxContentLength = n)
        def idleTimeout(d: Duration): Config        = copy(idleTimeout = d)
        def strictCookieParsing(b: Boolean): Config = copy(strictCookieParsing = b)
        def backlog(n: Int): Config                 = copy(backlog = n)
        def keepAlive(b: Boolean): Config           = copy(keepAlive = b)
        def tcpFastOpen(b: Boolean): Config         = copy(tcpFastOpen = b)
        def openApi(path: String = "/openapi.json", title: String = "API", version: String = "1.0.0"): Config =
            copy(openApi = Present(Config.OpenApi(path, title, version)))
    end Config

    object Config:
        val default: Config = Config()

        case class OpenApi(
            path: String = "/openapi.json",
            title: String = "API",
            version: String = "1.0.0",
            description: Maybe[String] = Absent
        )
    end Config

    // Internal handler for processing HTTP requests
    private class HttpServerHandler(
        handlers: Seq[HttpHandler[Any]],
        strictCookieParsing: Boolean,
        filter: HttpFilter
    )(using Frame) extends SimpleChannelInboundHandler[NettyFullHttpRequest]:

        // Build prefix tree router for O(path-segments) lookup
        private val router = HttpRouter(handlers)

        override def channelRead0(ctx: ChannelHandlerContext, nettyRequest: NettyFullHttpRequest): Unit =
            import AllowUnsafe.embrace.danger

            // retain() increments refcount for async fiber processing (no copy)
            val retained = nettyRequest.retain()

            // Convert Netty request to immutable HttpRequest
            val request =
                val req = kyo.HttpRequest.fromNetty(retained)
                if strictCookieParsing then req.withStrictCookieParsing(true) else req

            // Find matching handler using prefix tree and execute asynchronously
            val method = request.method
            val path   = request.path

            router.find(method, path) match
                case Result.Success(handler) =>
                    // Apply filter captured at server init time
                    val fiber = Sync.Unsafe.evalOrThrow(
                        Fiber.initUnscoped(filter(request, handler.apply))
                    )
                    // Interrupt handler fiber if client disconnects
                    discard {
                        ctx.channel().closeFuture().addListener { (_: ChannelFuture) =>
                            discard(fiber.unsafe.interrupt(Result.Panic(new Exception("Client disconnected"))))
                        }
                    }
                    // Register callback to send response when complete
                    fiber.unsafe.onComplete { result =>
                        val response = result match
                            case Result.Success(r) => r.asInstanceOf[kyo.HttpResponse]
                            case Result.Failure(e) => kyo.HttpResponse.serverError(e.toString)
                            case Result.Panic(e)   => kyo.HttpResponse.serverError(e.getMessage)
                        sendResponse(ctx, retained, response)
                    }
                case Result.Failure(HttpRouter.FindError.MethodNotAllowed(allowed)) =>
                    val allowHeader = allowed.map(_.name).mkString(", ")
                    val response    = kyo.HttpResponse(kyo.HttpResponse.Status.MethodNotAllowed).addHeader("Allow", allowHeader)
                    sendResponse(ctx, retained, response)
                case Result.Failure(HttpRouter.FindError.NotFound) =>
                    sendResponse(ctx, retained, kyo.HttpResponse.notFound)
                case Result.Panic(e) =>
                    sendResponse(ctx, retained, kyo.HttpResponse.serverError(e.getMessage))
            end match
        end channelRead0

        private def sendResponse(
            ctx: ChannelHandlerContext,
            nettyRequest: NettyFullHttpRequest,
            response: kyo.HttpResponse
        ): Unit =
            val nettyResponse = response.toNetty
            val keepAlive     = HttpUtil.isKeepAlive(nettyRequest)

            // Set Content-Length if not already set
            if !nettyResponse.headers().contains(HttpHeaderNames.CONTENT_LENGTH) then
                discard(nettyResponse.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, nettyResponse.content().readableBytes()))

            // Set Connection header based on client request
            if keepAlive then
                discard(nettyResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE))
                discard(ctx.writeAndFlush(nettyResponse))
            else
                discard(nettyResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE))
                discard(ctx.writeAndFlush(nettyResponse).addListener(ChannelFutureListener.CLOSE))
            end if
            discard(nettyRequest.release())
        end sendResponse

        private def extractPath(uri: String): String =
            val idx = uri.indexOf('?')
            if idx >= 0 then uri.substring(0, idx) else uri

        override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
            cause.printStackTrace()
            val response = kyo.HttpResponse.serverError(cause.getMessage).toNetty
            discard(ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE))
        end exceptionCaught

    end HttpServerHandler

end HttpServer

abstract class HttpHandler[-S]:
    def route: HttpRoute[?, ?, ?]
    def apply(request: HttpRequest): kyo.HttpResponse < (Async & S)
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
            private val outputSchema      = r.outputSchema
            private val inputSchema       = r.inputSchema
            private val queryParams       = r.queryParams
            private val headerParams      = r.headerParams
            private val errorSchemas      = r.errorSchemas
            def apply(request: HttpRequest): kyo.HttpResponse < (Async & S) =
                val pathInput = extractPathParams(r.path, request.path)
                // Extract query parameters
                val queryInput = extractQueryParams(queryParams, request)
                // Extract header parameters
                val headerInput = extractHeaderParams(headerParams, request)
                // Extract body if there's an input schema (cast justified: Schema[?] loses type info)
                val bodyInput = inputSchema match
                    case Present(schema) =>
                        schema.asInstanceOf[Schema[Any]].decode(request.bodyText)
                    case Absent =>
                        ()
                // Combine all inputs in order: path, query, headers, body
                val fullInput = combineAllInputs(pathInput, queryInput, headerInput, bodyInput)
                // Call the handler function (cast justified: In is computed via match types at compile time,
                // but fullInput is built dynamically at runtime)
                val computation = f(fullInput.asInstanceOf[In]).map { output =>
                    outputSchema match
                        case Present(s) =>
                            // Cast justified: Schema[?] loses type info due to erasure
                            val json = s.asInstanceOf[Schema[Out]].encode(output)
                            kyo.HttpResponse.json(json)
                        case Absent =>
                            kyo.HttpResponse.ok
                }
                // Cast justified: Err is erased, Abort.run needs concrete type
                Abort.run[Any](computation.asInstanceOf[kyo.HttpResponse < (Abort[Any] & Async & S)]).map {
                    case Result.Success(resp) => resp
                    case Result.Failure(err)  =>
                        // Try to find matching error schema and return appropriate response
                        findErrorResponse(err, errorSchemas).getOrElse(kyo.HttpResponse.serverError(err.toString))
                    case Result.Panic(ex) => kyo.HttpResponse.serverError(ex.getMessage)
                }
            end apply

    // Try to encode an error using registered error schemas and return appropriate HTTP response
    // TODO is this schema transformation mechanism depending on the error well tested?
    private def findErrorResponse(err: Any, errorSchemas: Seq[(HttpResponse.Status, Schema[?])]): Option[kyo.HttpResponse] =
        errorSchemas.collectFirst {
            case (status, schema) =>
                try
                    // Try to encode the error with this schema
                    val json = schema.asInstanceOf[Schema[Any]].encode(err)
                    // If encoding succeeds, return the response with this status
                    Some(kyo.HttpResponse(status, json).addHeader("Content-Type", "application/json"))
                catch
                    case _: Exception => None
        }.flatten

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

    private def extractQueryParams(queryParams: Seq[HttpRoute.QueryParam[?]], request: HttpRequest): Any =
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

    private def extractQueryParam(param: HttpRoute.QueryParam[?], request: HttpRequest): Any =
        request.query(param.name) match
            case Present(value) =>
                // Decode using the schema
                param.schema.asInstanceOf[Schema[Any]].decode(value)
            case Absent =>
                param.default match
                    case Present(d) => d
                    case Absent     => throw new IllegalArgumentException(s"Missing required query parameter: ${param.name}")

    private def extractHeaderParams(headerParams: Seq[HttpRoute.HeaderParam], request: HttpRequest): Any =
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

    private def extractHeaderParam(param: HttpRoute.HeaderParam, request: HttpRequest): String =
        request.header(param.name) match
            case Present(value) => value
            case Absent =>
                param.default match
                    case Present(d) => d
                    case Absent     => throw new IllegalArgumentException(s"Missing required header: ${param.name}")

    private def extractPathParams(routePath: HttpRoute.Path[Any], requestPath: String): Any =
        routePath match
            case s: String => ()
            case segment: HttpRoute.Path.Segment[?] =>
                val parts = parsePathSegments(requestPath)
                extractFromSegment(segment, parts)._1

    private def parsePathSegments(path: String): List[String] =
        PathUtil.parseSegments(path)

    private def countPathSegments(path: String): Int =
        PathUtil.countSegments(path)

    private def extractFromSegment(segment: HttpRoute.Path.Segment[?], parts: List[String]): (Any, List[String]) =
        segment match
            case HttpRoute.Path.Segment.Literal(v) =>
                // Skip literal parts
                val literalSize = countPathSegments(v)
                ((), parts.drop(literalSize))
            case HttpRoute.Path.Segment.Capture(_, parse) =>
                val value = parse(parts.head)
                (value, parts.tail)
            case HttpRoute.Path.Segment.Concat(left, right) =>
                val (leftVal, remaining)   = extractFromSegment(left.asInstanceOf[HttpRoute.Path.Segment[?]], parts)
                val (rightVal, remaining2) = extractFromSegment(right.asInstanceOf[HttpRoute.Path.Segment[?]], remaining)
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

    @nowarn("msg=anonymous")
    // TODO member reorg, most useful public first, privates last
    inline def init[A, S](method: Method, path: Path[A])(inline f: (A, HttpRequest) => kyo.HttpResponse < (Async & S))(using
        Frame
    ): HttpHandler[S] =
        new HttpHandler[S]:
            val route: HttpRoute[?, ?, ?] = HttpRoute(method, path.asInstanceOf[Path[Any]], Status.OK, false, false, Absent, Absent)
            def apply(request: HttpRequest): kyo.HttpResponse < (Async & S) =
                // For simple paths without captures, A is Unit
                f(().asInstanceOf[A], request)

    def health(path: Path[Unit] = "/health")(using Frame): HttpHandler[Any] =
        init(Method.GET, path)((_, _) => kyo.HttpResponse.ok("healthy"))

    def const[A](method: Method, path: Path[A], status: Status)(using Frame): HttpHandler[Any] =
        init(method, path)((_, _) => kyo.HttpResponse(status))

    def const[A](method: Method, path: Path[A], response: kyo.HttpResponse)(using Frame): HttpHandler[Any] =
        init(method, path)((_, _) => response)

    inline def get[A, S](path: Path[A])(inline f: (A, HttpRequest) => kyo.HttpResponse < (Async & S))(using Frame): HttpHandler[S] =
        init(Method.GET, path)(f)

    inline def post[A, S](path: Path[A])(inline f: (A, HttpRequest) => kyo.HttpResponse < (Async & S))(using Frame): HttpHandler[S] =
        init(Method.POST, path)(f)

    inline def put[A, S](path: Path[A])(inline f: (A, HttpRequest) => kyo.HttpResponse < (Async & S))(using Frame): HttpHandler[S] =
        init(Method.PUT, path)(f)

    inline def patch[A, S](path: Path[A])(inline f: (A, HttpRequest) => kyo.HttpResponse < (Async & S))(using Frame): HttpHandler[S] =
        init(Method.PATCH, path)(f)

    inline def delete[A, S](path: Path[A])(inline f: (A, HttpRequest) => kyo.HttpResponse < (Async & S))(using Frame): HttpHandler[S] =
        init(Method.DELETE, path)(f)

    inline def head[A, S](path: Path[A])(inline f: (A, HttpRequest) => kyo.HttpResponse < (Async & S))(using Frame): HttpHandler[S] =
        init(Method.HEAD, path)(f)

    inline def options[A, S](path: Path[A])(inline f: (A, HttpRequest) => kyo.HttpResponse < (Async & S))(using Frame): HttpHandler[S] =
        init(Method.OPTIONS, path)(f)

end HttpHandler
