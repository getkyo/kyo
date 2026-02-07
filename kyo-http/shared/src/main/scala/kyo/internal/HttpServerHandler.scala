package kyo.internal

import io.netty.buffer.CompositeByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.{Channel as NettyChannel, *}
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpRequest as NettyHttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import kyo.*
import scala.compiletime.uninitialized

/** Two-phase HTTP request handler without HttpObjectAggregator.
  *
  * Receives the message sequence: HttpRequest -> 0+ HttpContent -> LastHttpContent. Routes on the initial HttpRequest (headers only), then
  * dispatches body handling based on whether the matched handler expects streaming or buffered input.
  *
  * State machine: IDLE -> (receive HttpRequest) -> route lookup -> BUFFERING (accumulate body in CompositeByteBuf, invoke handler on
  * LastHttpContent) STREAMING (deliver chunks to Channel.Unsafe, invoke handler immediately) DISCARDING (consume and release body chunks,
  * send error response on LastHttpContent)
  *
  * Reference counting: ChannelInboundHandlerAdapter does NOT auto-release messages. Every message received in channelRead must be
  * explicitly released via ReferenceCountUtil.release().
  */
private[kyo] class HttpServerHandler(
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
      * When the channel is full (consumer slower than producer), disables Netty auto-read to apply TCP backpressure, then uses putFiber to
      * async-wait for space. Auto-read is re-enabled once the put completes.
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
    private[kyo] def invokeHandler(
        ctx: ChannelHandlerContext,
        request: kyo.HttpRequest[?],
        handler: HttpHandler[Any],
        keepAlive: Boolean
    ): Unit =
        import AllowUnsafe.embrace.danger

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
                                    NettyUtil.await(
                                        ctx.writeAndFlush(new DefaultHttpContent(
                                            Unpooled.wrappedBuffer(bytes.toArrayUnsafe)
                                        ))
                                    )
                                }.andThen {
                                    // Terminal chunk signals end of stream
                                    NettyUtil.await(
                                        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                                    )
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
    private[kyo] def sendBufferedResponse(
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
