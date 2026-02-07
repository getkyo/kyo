package kyo

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.CompositeByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.{Channel as NettyChannel, *}
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpRequest as NettyHttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.flush.FlushConsolidationHandler
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kyo.HttpRequest.Method
import kyo.HttpResponse.Status
import kyo.internal.HttpRouter
import kyo.internal.NettyTransport
import kyo.internal.NettyUtil
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.compiletime.uninitialized

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
        NettyUtil.channelFuture(channel.close()) { _ =>
            NettyUtil.future(bossGroup.shutdownGracefully(graceMs, graceMs, TimeUnit.MILLISECONDS)) { _ =>
                NettyUtil.future(workerGroup.shutdownGracefully(graceMs, graceMs, TimeUnit.MILLISECONDS))(_ => ())
            }
        }
    end stop

    def await(using Frame): Unit < Async =
        NettyUtil.channelFuture(channel.closeFuture())(_ => ())

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
                                discard(pipeline.addLast(new FlushConsolidationHandler(256, true))) // TODO what's 256? should it be configurable?
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

                NettyUtil.channelFuture(bindFuture) { channel =>
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

        // TODO rename to OpenApiEndpoint to avoid confusion
        case class OpenApi(
            path: String = "/openapi.json",
            title: String = "API",
            version: String = "1.0.0",
            description: Maybe[String] = Absent
        )
    end Config

    /** Two-phase HTTP request handler without HttpObjectAggregator.
      *
      * Receives the message sequence: HttpRequest → 0+ HttpContent → LastHttpContent. Routes on the initial HttpRequest (headers only),
      * then dispatches body handling based on whether the matched handler expects streaming or buffered input.
      *
      * State machine: IDLE → (receive HttpRequest) → route lookup → BUFFERING (accumulate body in CompositeByteBuf, invoke handler on
      * LastHttpContent) STREAMING (deliver chunks to Channel.Unsafe, invoke handler immediately) DISCARDING (consume and release body
      * chunks, send error response on LastHttpContent)
      *
      * Reference counting: ChannelInboundHandlerAdapter does NOT auto-release messages. Every message received in channelRead must be
      * explicitly released via ReferenceCountUtil.release().
      */
    // TODO Let's move to a separate class in kyo.internal
    // TODO this is quite complex. Is it possible to simplify? Is it correct? How's test coverage?
    private class HttpServerHandler(
        handlers: Seq[HttpHandler[Any]],
        maxContentLength: Int,
        strictCookieParsing: Boolean,
        filter: HttpFilter
    )(using Frame) extends ChannelInboundHandlerAdapter:

        import io.netty.util.ReferenceCountUtil

        // Build prefix tree router for O(path-segments) lookup
        private val router = HttpRouter(handlers)

        // --- Per-connection state ---

        // Discriminator for the current connection state
        private val STATE_IDLE       = 0
        private val STATE_BUFFERING  = 1
        private val STATE_STREAMING  = 2
        private val STATE_DISCARDING = 3

        private var state: Int = STATE_IDLE

        // BUFFERING state
        private var pendingNettyRequest: NettyHttpRequest = uninitialized
        private var pendingHandler: HttpHandler[Any]      = uninitialized
        private var pendingKeepAlive: Boolean             = true
        private var bodyBuf: CompositeByteBuf             = uninitialized
        private var bodySize: Int                         = 0

        // STREAMING state — Absent sentinel signals end of body
        private var streamingChannel: Channel.Unsafe[Maybe[Span[Byte]]] = uninitialized

        // DISCARDING state
        private var discardResponse: kyo.HttpResponse[?] = uninitialized

        override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit =
            try
                state match
                    case STATE_IDLE =>
                        msg match
                            case nettyReq: NettyHttpRequest =>
                                handleInitialRequest(ctx, nettyReq)
                            case _ =>
                                // Unexpected message in IDLE state (e.g. stale content), just release
                                ()

                    case STATE_BUFFERING =>
                        msg match
                            case content: HttpContent =>
                                handleBufferingContent(ctx, content)
                            case _ => ()

                    case STATE_STREAMING =>
                        msg match
                            case content: HttpContent =>
                                handleStreamingContent(ctx, content)
                            case _ => ()

                    case STATE_DISCARDING =>
                        msg match
                            case content: HttpContent =>
                                handleDiscardingContent(ctx, content)
                            case _ => ()

                    case _ => ()
                end match
            finally
                discard(ReferenceCountUtil.release(msg))
        end channelRead

        /** Handle the initial HttpRequest (headers only). Routes and transitions to the appropriate state. */
        private def handleInitialRequest(ctx: ChannelHandlerContext, nettyReq: NettyHttpRequest): Unit =
            import AllowUnsafe.embrace.danger

            val method    = kyo.HttpRequest.Method.fromNetty(nettyReq.method())
            val uri       = nettyReq.uri()
            val keepAlive = HttpUtil.isKeepAlive(nettyReq)

            // Extract path for routing (strip query string)
            val pathEnd = uri.indexOf('?')
            val path    = if pathEnd >= 0 then uri.substring(0, pathEnd) else uri

            router.find(method, path) match
                case Result.Success(handler) =>
                    if handler.streamingRequest then
                        // --- STREAMING path ---
                        state = STATE_STREAMING
                        pendingKeepAlive = keepAlive

                        // Send 100 Continue if client expects it
                        handleExpectContinue(ctx, nettyReq)

                        // Create a Kyo channel for delivering body chunks.
                        // Uses Maybe[Span[Byte]] with Absent as end-of-body sentinel
                        // to avoid race conditions with closeAwaitEmpty + streamUntilClosed.
                        val byteChannel = Channel.Unsafe.init[Maybe[Span[Byte]]](32)
                        streamingChannel = byteChannel
                        // Custom stream: takes from the sentinel channel one element at a time.
                        // Cannot use streamUntilClosed() because its emitChunks has a
                        // take+drainUpTo for-comprehension that loses data when the last
                        // take triggers HalfOpen→FullyClosed transition.
                        val bodyStream: Stream[Span[Byte], Async] = Stream[Span[Byte], Async] {
                            Abort.run[Closed] {
                                Loop(()) { _ =>
                                    Channel.take(byteChannel.safe).map {
                                        case Present(bytes) =>
                                            Emit.valueWith(Chunk(bytes))(Loop.continue(()))
                                        case Absent =>
                                            Loop.done(())
                                    }
                                }
                            }.map {
                                case _ => ()
                            }
                        }

                        // Build streaming request from headers
                        val request =
                            val req = kyo.HttpRequest.fromNettyStreaming(nettyReq, bodyStream)
                            if strictCookieParsing then req.withStrictCookieParsing(true) else req

                        // Invoke handler immediately (body arrives via stream)
                        invokeHandler(ctx, request, handler, keepAlive)

                        // Handle case where this message is also content (e.g. non-chunked with body)
                        if nettyReq.isInstanceOf[HttpContent] then
                            val content = nettyReq.asInstanceOf[HttpContent]
                            deliverStreamingContent(ctx, content)
                        end if
                    else
                        // --- BUFFERING path ---
                        state = STATE_BUFFERING
                        pendingNettyRequest = nettyReq
                        pendingHandler = handler
                        pendingKeepAlive = keepAlive
                        bodyBuf = ctx.alloc().compositeBuffer()
                        bodySize = 0

                        // Send 100 Continue if client expects it
                        handleExpectContinue(ctx, nettyReq)

                        // Handle case where this message is also content (e.g. non-chunked with body)
                        if nettyReq.isInstanceOf[HttpContent] then
                            val content = nettyReq.asInstanceOf[HttpContent]
                            handleBufferingContent(ctx, content)
                        end if
                    end if

                case Result.Failure(HttpRouter.FindError.MethodNotAllowed(allowed)) =>
                    // Consume body before sending error
                    val allowHeader = allowed.map(_.name).mkString(", ")
                    discardResponse =
                        kyo.HttpResponse(kyo.HttpResponse.Status.MethodNotAllowed).addHeader("Allow", allowHeader)
                    pendingKeepAlive = keepAlive
                    if nettyReq.isInstanceOf[LastHttpContent] then
                        // No body to discard
                        sendBufferedResponse(ctx, discardResponse, keepAlive)
                        resetState()
                    else
                        state = STATE_DISCARDING
                    end if

                case Result.Failure(HttpRouter.FindError.NotFound) =>
                    discardResponse = kyo.HttpResponse.notFound
                    pendingKeepAlive = keepAlive
                    if nettyReq.isInstanceOf[LastHttpContent] then
                        sendBufferedResponse(ctx, discardResponse, keepAlive)
                        resetState()
                    else
                        state = STATE_DISCARDING
                    end if

                case Result.Panic(e) =>
                    discardResponse = kyo.HttpResponse.serverError(e.getMessage)
                    pendingKeepAlive = keepAlive
                    if nettyReq.isInstanceOf[LastHttpContent] then
                        sendBufferedResponse(ctx, discardResponse, keepAlive)
                        resetState()
                    else
                        state = STATE_DISCARDING
                    end if
            end match
        end handleInitialRequest

        /** Accumulate body chunks in CompositeByteBuf. On LastHttpContent, build the request and invoke the handler. */
        private def handleBufferingContent(ctx: ChannelHandlerContext, content: HttpContent): Unit =
            import AllowUnsafe.embrace.danger

            val buf       = content.content()
            val chunkSize = buf.readableBytes()

            if chunkSize > 0 then
                bodySize += chunkSize
                if bodySize > maxContentLength then
                    // Oversized — release accumulated buffer and switch to DISCARDING with 413
                    if bodyBuf != null then
                        bodyBuf.release()
                        bodyBuf = null
                    discardResponse = kyo.HttpResponse(kyo.HttpResponse.Status.PayloadTooLarge)
                    state = STATE_DISCARDING
                    // If this is also the last content, send response immediately
                    if content.isInstanceOf[LastHttpContent] then
                        sendBufferedResponse(ctx, discardResponse, pendingKeepAlive)
                        resetState()
                    return
                end if
                // Retain and add to composite (ownership transferred to composite)
                discard(bodyBuf.addComponent(true, buf.retain()))
            end if

            if content.isInstanceOf[LastHttpContent] then
                // Body complete — build request and invoke handler
                val bodyData =
                    if bodyBuf.readableBytes() == 0 then Array.empty[Byte]
                    else
                        val bytes = new Array[Byte](bodyBuf.readableBytes())
                        bodyBuf.readBytes(bytes)
                        bytes
                bodyBuf.release()
                bodyBuf = null

                val request =
                    val req = kyo.HttpRequest.fromNettyHeaders(pendingNettyRequest, bodyData)
                    if strictCookieParsing then req.withStrictCookieParsing(true) else req

                val handler   = pendingHandler
                val keepAlive = pendingKeepAlive
                resetState()

                invokeHandler(ctx, request, handler, keepAlive)
            end if
        end handleBufferingContent

        /** Deliver body chunks to the streaming channel. */
        private def handleStreamingContent(ctx: ChannelHandlerContext, content: HttpContent): Unit =
            deliverStreamingContent(ctx, content)

        /** Internal: deliver content bytes to the streaming channel, signal end with Absent sentinel.
          *
          * When the channel is full (consumer slower than producer), disables Netty auto-read to apply TCP backpressure, then uses putFiber
          * to async-wait for space. Auto-read is re-enabled once the put completes.
          */
        private def deliverStreamingContent(ctx: ChannelHandlerContext, content: HttpContent): Unit =
            import AllowUnsafe.embrace.danger

            val buf = content.content()
            if buf.readableBytes() > 0 && streamingChannel != null then
                val bytes = new Array[Byte](buf.readableBytes())
                buf.readBytes(bytes)
                val value = Present(Span.fromUnsafe(bytes))
                streamingChannel.offer(value) match
                    case Result.Success(true)  => // offered successfully
                    case Result.Success(false) =>
                        // Channel full — apply backpressure via Netty auto-read
                        ctx.channel().config().setAutoRead(false)
                        val fiber = streamingChannel.putFiber(value)
                        fiber.onComplete { _ =>
                            ctx.channel().config().setAutoRead(true)
                            discard(ctx.read())
                        }
                    case _ => // channel closed, drop
                end match
            end if

            if content.isInstanceOf[LastHttpContent] then
                if streamingChannel != null then
                    // Signal end of body with Absent sentinel
                    discard(streamingChannel.offer(Absent))
                    streamingChannel = null
                end if
                state = STATE_IDLE
            end if
        end deliverStreamingContent

        /** Consume and release body chunks without processing. Send error response on LastHttpContent. */
        private def handleDiscardingContent(ctx: ChannelHandlerContext, content: HttpContent): Unit =
            // Content is released in the finally block of channelRead
            if content.isInstanceOf[LastHttpContent] then
                val response  = discardResponse
                val keepAlive = pendingKeepAlive
                resetState()
                sendBufferedResponse(ctx, response, keepAlive)
            end if
        end handleDiscardingContent

        /** Send 100-Continue response if the client expects it. */
        private def handleExpectContinue(ctx: ChannelHandlerContext, nettyReq: NettyHttpRequest): Unit =
            if HttpUtil.is100ContinueExpected(nettyReq) then
                val continueResponse = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.CONTINUE,
                    Unpooled.EMPTY_BUFFER
                )
                discard(ctx.writeAndFlush(continueResponse))
            end if
        end handleExpectContinue

        /** Invoke the handler (with filter) and send the response asynchronously. */
        private def invokeHandler(
            ctx: ChannelHandlerContext,
            request: kyo.HttpRequest[?],
            handler: HttpHandler[Any],
            keepAlive: Boolean
        ): Unit =
            import AllowUnsafe.embrace.danger

            // TODO let's add a config that by default doens't fork a fiber and uses the current thread. THe user can configure to fork if they want
            val fiber = Sync.Unsafe.evalOrThrow(
                Fiber.initUnscoped {
                    filter(request, handler.apply).map { response =>
                        // Dispatch based on response body type
                        response.body match
                            case _: HttpBody.Bytes =>
                                response.asInstanceOf[kyo.HttpResponse[HttpBody.Bytes]]: kyo.HttpResponse[?]
                            case streamed: HttpBody.Streamed =>
                                // Write initial response headers (chunked transfer)
                                val nettyResponse = new DefaultHttpResponse(
                                    HttpVersion.HTTP_1_1,
                                    HttpResponseStatus.valueOf(response.status.code)
                                )
                                nettyResponse.headers().set("Transfer-Encoding", "chunked")
                                // Copy headers from the response
                                response.headers.foreach { (k, v) =>
                                    discard(nettyResponse.headers().set(k, v))
                                }
                                discard(ctx.writeAndFlush(nettyResponse))

                                // Stream body chunks — errors after headers are caught here
                                Abort.run[Throwable](Abort.catching[Throwable] {
                                    streamed.stream.foreach { bytes =>
                                        NettyUtil.channelFuture(
                                            ctx.writeAndFlush(new DefaultHttpContent(
                                                Unpooled.wrappedBuffer(bytes.toArrayUnsafe)
                                            ))
                                        )(_ => ())
                                    }.andThen {
                                        // Terminal chunk signals end of stream
                                        NettyUtil.channelFuture(
                                            ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                                        )(_ => ())
                                    }
                                }).map {
                                    case Result.Success(_) => response
                                    case _                 =>
                                        // Stream error after headers — close connection
                                        if ctx.channel().isActive then discard(ctx.close())
                                        response
                                }
                        end match
                    }
                }
            )

            // Interrupt handler fiber if client disconnects
            discard {
                ctx.channel().closeFuture().addListener { (_: ChannelFuture) =>
                    discard(fiber.unsafe.interrupt(Result.Panic(new Exception("Client disconnected"))))
                }
            }

            // Register callback to send response when complete
            fiber.unsafe.onComplete { result =>
                result match
                    case Result.Success(r) =>
                        val response = r.asInstanceOf[kyo.HttpResponse[?]]
                        response.body match
                            case _: HttpBody.Bytes =>
                                sendBufferedResponse(ctx, response, keepAlive)
                            case _: HttpBody.Streamed =>
                                // Streaming already handled inline — connection header already set
                                ()
                        end match
                    case Result.Failure(e) =>
                        if ctx.channel().isActive then
                            sendBufferedResponse(ctx, kyo.HttpResponse.serverError(e.toString), keepAlive)
                    case Result.Panic(e) =>
                        if ctx.channel().isActive then
                            sendBufferedResponse(ctx, kyo.HttpResponse.serverError(e.getMessage), keepAlive)
            }
        end invokeHandler

        /** Send a fully-buffered response. Handles Content-Length and Connection headers. */
        private def sendBufferedResponse(
            ctx: ChannelHandlerContext,
            response: kyo.HttpResponse[?],
            keepAlive: Boolean
        ): Unit =
            val nettyResponse = response.toNetty
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
        end sendBufferedResponse

        /** Reset per-request state back to IDLE. */
        private def resetState(): Unit =
            state = STATE_IDLE
            pendingNettyRequest = null
            pendingHandler = null
            discardResponse = null
            if bodyBuf != null then
                bodyBuf.release()
                bodyBuf = null
            bodySize = 0
        end resetState

        override def channelInactive(ctx: ChannelHandlerContext): Unit =
            import AllowUnsafe.embrace.danger
            // Signal end-of-body and close streaming channel if active
            if streamingChannel != null then
                discard(streamingChannel.offer(Absent))
                discard(streamingChannel.close())
                streamingChannel = null
            end if
            // Clean up buffering state
            if bodyBuf != null then
                bodyBuf.release()
                bodyBuf = null
            state = STATE_IDLE
            super.channelInactive(ctx)
        end channelInactive

        override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
            cause.printStackTrace()
            val response = kyo.HttpResponse.serverError(cause.getMessage).toNetty
            discard(ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE))
        end exceptionCaught

    end HttpServerHandler

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
            // TODO can we remove these vals? they're just aliases
            private val outputSchema      = r.outputSchema
            private val inputSchema       = r.inputSchema
            private val queryParams       = r.queryParams
            private val headerParams      = r.headerParams
            private val errorSchemas      = r.errorSchemas
            private[kyo] def apply(request: HttpRequest[?]): kyo.HttpResponse[?] < (Async & S) =
                // Safe cast: server guarantees buffered body for non-streaming handlers
                val req       = request.asInstanceOf[HttpRequest[HttpBody.Bytes]]
                val pathInput = extractPathParams(r.path, req.path)
                // Extract query parameters
                val queryInput = extractQueryParams(queryParams, req)
                // Extract header parameters
                val headerInput = extractHeaderParams(headerParams, req)
                // Extract body if there's an input schema (cast justified: Schema[?] loses type info)
                val bodyInput = inputSchema match
                    case Present(schema) =>
                        schema.asInstanceOf[Schema[Any]].decode(req.bodyText)
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
                // TODO I think we can use Abort.recover or some other method
                Abort.run[Any](computation.asInstanceOf[kyo.HttpResponse[?] < (Abort[Any] & Async & S)]).map {
                    case Result.Success(resp) => resp
                    case Result.Failure(err)  =>
                        // Try to find matching error schema and return appropriate response
                        findErrorResponse(err, errorSchemas).getOrElse(kyo.HttpResponse.serverError(err.toString))
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
                // TODO this makes no sense to me! ().asInstanceOf[A]?
                f(().asInstanceOf[A], request.asInstanceOf[HttpRequest[HttpBody.Bytes]])

    /** Creates a stub handler that preserves route metadata but returns a fixed response. For OpenAPI spec generation only. */
    // TODO private stuff at the end of the class
    private[kyo] def stub(r: HttpRoute[?, ?, ?]): HttpHandler[Any] =
        new HttpHandler[Any]:
            val route: HttpRoute[?, ?, ?] = r
            private[kyo] def apply(request: HttpRequest[?]): kyo.HttpResponse[?] < Async =
                kyo.HttpResponse.ok

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

    // TODO some NDJSON methods have the suffix, some don't. Consistency is key
    inline def stream[A, V: Schema: Tag, S](
        path: HttpPath[A]
    )(
        inline f: (A, HttpRequest[HttpBody.Bytes]) => Stream[V, Async & S]
    )(using Frame): HttpHandler[S] =
        stream(Method.GET, path)(f)

    inline def stream[A, V: Schema: Tag, S](
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
    end stream

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
