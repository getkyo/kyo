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
import java.nio.charset.StandardCharsets
import kyo.*
import scala.annotation.tailrec
import scala.compiletime.uninitialized

/** Two-phase HTTP request handler without HttpObjectAggregator.
  *
  * Receives the message sequence: HttpRequest -> 0+ HttpContent -> LastHttpContent. Routes via Backend.ServerHandler.reject/isStreaming,
  * then dispatches body handling based on whether the matched handler expects streaming or buffered input.
  *
  * State machine: IDLE -> (receive HttpRequest) -> route lookup -> BUFFERING (accumulate body, invoke handler on LastHttpContent) STREAMING
  * (deliver chunks to Channel.Unsafe, invoke handler immediately) DISCARDING (consume body, send error response on LastHttpContent)
  */
final private[kyo] class HttpServerHandler(
    handler: Backend.ServerHandler,
    maxContentLength: Int
)(using Frame) extends ChannelInboundHandlerAdapter:

    import io.netty.util.ReferenceCountUtil

    // --- Per-connection state ---
    private val STATE_IDLE       = 0
    private val STATE_BUFFERING  = 1
    private val STATE_STREAMING  = 2
    private val STATE_DISCARDING = 3

    private var state: Int = STATE_IDLE

    // BUFFERING state
    private var pendingMethod: String       = uninitialized
    private var pendingUri: String          = uninitialized
    private var pendingHeaders: HttpHeaders = uninitialized
    private var pendingKeepAlive: Boolean   = true
    private var bodyBuf: CompositeByteBuf   = uninitialized
    private var bodySize: Int               = 0

    // STREAMING state
    private var streamingChannel: Channel.Unsafe[Maybe[Span[Byte]]] = uninitialized

    // DISCARDING state
    private var discardResponse: kyo.HttpResponse[?] = uninitialized

    override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit =
        try
            state match
                case STATE_IDLE =>
                    msg match
                        case nettyReq: NettyHttpRequest => handleInitialRequest(ctx, nettyReq)
                        case _                          => ()
                case STATE_BUFFERING =>
                    msg match
                        case content: HttpContent => handleBufferingContent(ctx, content)
                        case _                    => ()
                case STATE_STREAMING =>
                    msg match
                        case content: HttpContent => handleStreamingContent(ctx, content)
                        case _                    => ()
                case STATE_DISCARDING =>
                    msg match
                        case content: HttpContent => handleDiscardingContent(ctx, content)
                        case _                    => ()
                case _ => ()
            end match
        finally
            discard(ReferenceCountUtil.release(msg))
    end channelRead

    private def handleInitialRequest(ctx: ChannelHandlerContext, nettyReq: NettyHttpRequest): Unit =
        import AllowUnsafe.embrace.danger

        val method    = kyo.HttpRequest.Method(nettyReq.method().name())
        val uri       = nettyReq.uri()
        val keepAlive = HttpUtil.isKeepAlive(nettyReq)
        val pathEnd   = uri.indexOf('?')
        val path      = if pathEnd >= 0 then uri.substring(0, pathEnd) else uri
        val headers   = NettyHeaderUtil.extract(nettyReq.headers())

        // Route lookup decides state: DISCARDING (404/405), STREAMING, or BUFFERING
        handler.reject(method, path) match
            case Present(errorResponse) =>
                // Route rejected (404, 405)
                discardResponse = errorResponse
                pendingKeepAlive = keepAlive
                if nettyReq.isInstanceOf[LastHttpContent] then
                    sendBufferedResponse(ctx, errorResponse, keepAlive)
                    resetState()
                else
                    state = STATE_DISCARDING
                end if

            case Absent =>
                if handler.isStreaming(method, path) then
                    // --- STREAMING path ---
                    state = STATE_STREAMING
                    pendingKeepAlive = keepAlive

                    handleExpectContinue(ctx, nettyReq)

                    val byteChannel = Channel.Unsafe.init[Maybe[Span[Byte]]](32)
                    streamingChannel = byteChannel
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
                        }.unit
                    }

                    val request = kyo.HttpRequest.fromRawStreaming(method, uri, headers, bodyStream)
                    invokeHandler(ctx, request, keepAlive)

                    if nettyReq.isInstanceOf[HttpContent] then
                        deliverStreamingContent(ctx, nettyReq.asInstanceOf[HttpContent])
                else
                    // --- BUFFERING path ---
                    state = STATE_BUFFERING
                    pendingMethod = method.name
                    pendingUri = uri
                    pendingHeaders = headers
                    pendingKeepAlive = keepAlive
                    bodyBuf = ctx.alloc().compositeBuffer()
                    bodySize = 0

                    handleExpectContinue(ctx, nettyReq)

                    if nettyReq.isInstanceOf[HttpContent] then
                        handleBufferingContent(ctx, nettyReq.asInstanceOf[HttpContent])
                end if
        end match
    end handleInitialRequest

    private def handleBufferingContent(ctx: ChannelHandlerContext, content: HttpContent): Unit =
        import AllowUnsafe.embrace.danger

        val buf       = content.content()
        val chunkSize = buf.readableBytes()

        if chunkSize > 0 then
            // Switch to DISCARDING if accumulated body exceeds maxContentLength
            bodySize += chunkSize
            if bodySize > maxContentLength then
                if bodyBuf != null then
                    bodyBuf.release()
                    bodyBuf = null
                discardResponse = kyo.HttpResponse(kyo.HttpResponse.Status.PayloadTooLarge)
                state = STATE_DISCARDING
                if content.isInstanceOf[LastHttpContent] then
                    sendBufferedResponse(ctx, discardResponse, pendingKeepAlive)
                    resetState()
                return
            end if
            discard(bodyBuf.addComponent(true, buf.retain()))
        end if

        // LastHttpContent signals the full body is available — materialize and dispatch
        if content.isInstanceOf[LastHttpContent] then
            val bodyData =
                if bodyBuf.readableBytes() == 0 then Array.empty[Byte]
                else
                    val bytes = new Array[Byte](bodyBuf.readableBytes())
                    bodyBuf.readBytes(bytes)
                    bytes
            bodyBuf.release()
            bodyBuf = null

            val method  = kyo.HttpRequest.Method(pendingMethod)
            val request = kyo.HttpRequest.fromRawHeaders(method, pendingUri, pendingHeaders, bodyData)

            val keepAlive = pendingKeepAlive
            resetState()

            invokeHandler(ctx, request, keepAlive)
        end if
    end handleBufferingContent

    private def handleStreamingContent(ctx: ChannelHandlerContext, content: HttpContent): Unit =
        deliverStreamingContent(ctx, content)

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

        // Absent sentinel signals end-of-stream to the consumer
        if content.isInstanceOf[LastHttpContent] then
            if streamingChannel != null then
                discard(streamingChannel.offer(Absent))
                streamingChannel = null
            end if
            state = STATE_IDLE
        end if
    end deliverStreamingContent

    private def handleDiscardingContent(ctx: ChannelHandlerContext, content: HttpContent): Unit =
        if content.isInstanceOf[LastHttpContent] then
            val response  = discardResponse
            val keepAlive = pendingKeepAlive
            resetState()
            sendBufferedResponse(ctx, response, keepAlive)
        end if
    end handleDiscardingContent

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

    private[kyo] def invokeHandler(
        ctx: ChannelHandlerContext,
        request: kyo.HttpRequest[?],
        keepAlive: Boolean
    ): Unit =
        import AllowUnsafe.embrace.danger

        // Run handler in a separate fiber so Netty's I/O thread isn't blocked
        val fiber = Backend.Unsafe.launchFiber {
            val respEffect: kyo.HttpResponse[?] < Async = request.body.use(
                _ => handler.handle(request.asInstanceOf[kyo.HttpRequest[HttpBody.Bytes]]),
                _ => handler.handleStreaming(request.asInstanceOf[kyo.HttpRequest[HttpBody.Streamed]])
            )

            respEffect.map { response =>
                response.body.use(
                    _ => response: kyo.HttpResponse[?],
                    streamed =>
                        val nettyResponse = new DefaultHttpResponse(
                            HttpVersion.HTTP_1_1,
                            HttpResponseStatus.valueOf(response.status.code)
                        )
                        response.resolvedHeaders.foreach { (k, v) =>
                            discard(nettyResponse.headers().add(k, v))
                        }
                        if !nettyResponse.headers().contains("Content-Length") then
                            discard(nettyResponse.headers().set("Transfer-Encoding", "chunked"))
                        if keepAlive then
                            discard(nettyResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE))
                        else
                            discard(nettyResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE))
                        end if
                        discard(ctx.writeAndFlush(nettyResponse))

                        Abort.run[Throwable](Abort.catching[Throwable] {
                            streamed.stream.foreach { bytes =>
                                NettyUtil.await(
                                    ctx.writeAndFlush(new DefaultHttpContent(
                                        Unpooled.wrappedBuffer(bytes.toArrayUnsafe)
                                    ))
                                )
                            }.andThen {
                                NettyUtil.await(
                                    ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                                )
                            }
                        }).map {
                            case Result.Success(_) =>
                                if !keepAlive && ctx.channel().isActive then
                                    discard(ctx.close())
                                response
                            case _ =>
                                if ctx.channel().isActive then discard(ctx.close())
                                response
                        }
                )
            }
        }

        // Interrupt fiber if client disconnects
        discard {
            ctx.channel().closeFuture().addListener { (_: ChannelFuture) =>
                discard(fiber.unsafe.interrupt(Result.Panic(new Exception("Client disconnected"))))
            }
        }

        // Streaming responses are written inside the fiber; buffered responses write here on completion
        fiber.unsafe.onComplete { result =>
            result match
                case Result.Success(r) =>
                    val response = r.asInstanceOf[kyo.HttpResponse[?]]
                    response.body.use(
                        _ => sendBufferedResponse(ctx, response, keepAlive),
                        _ => () // Streaming already handled inline
                    )
                case Result.Failure(e) =>
                    if ctx.channel().isActive then
                        sendBufferedResponse(ctx, kyo.HttpResponse.serverError(e.toString), keepAlive)
                case Result.Panic(e) =>
                    if ctx.channel().isActive then
                        sendBufferedResponse(ctx, kyo.HttpResponse.serverError(e.getMessage), keepAlive)
        }
    end invokeHandler

    private[kyo] def sendBufferedResponse(
        ctx: ChannelHandlerContext,
        response: kyo.HttpResponse[?],
        keepAlive: Boolean
    ): Unit =
        val bodyData  = response.body.use(_.data, _ => Array.empty[Byte])
        val status    = HttpResponseStatus.valueOf(response.status.code)
        val content   = if bodyData.isEmpty then Unpooled.EMPTY_BUFFER else Unpooled.wrappedBuffer(bodyData)
        val nettyResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content)

        // resolvedHeaders merges user headers + Set-Cookie from cookies
        response.resolvedHeaders.foreach((k, v) => discard(nettyResp.headers().add(k, v)))

        if !nettyResp.headers().contains(HttpHeaderNames.CONTENT_LENGTH) then
            discard(nettyResp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes()))

        if keepAlive then
            discard(nettyResp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE))
            discard(ctx.writeAndFlush(nettyResp))
        else
            discard(nettyResp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE))
            discard(ctx.writeAndFlush(nettyResp).addListener(ChannelFutureListener.CLOSE))
        end if
    end sendBufferedResponse

    private def resetState(): Unit =
        state = STATE_IDLE
        pendingMethod = null
        pendingUri = null
        pendingHeaders = HttpHeaders.empty
        discardResponse = null
        if bodyBuf != null then
            bodyBuf.release()
            bodyBuf = null
        bodySize = 0
    end resetState

    override def channelInactive(ctx: ChannelHandlerContext): Unit =
        import AllowUnsafe.embrace.danger
        if streamingChannel != null then
            discard(streamingChannel.offer(Absent))
            discard(streamingChannel.close())
            streamingChannel = null
        end if
        if bodyBuf != null then
            bodyBuf.release()
            bodyBuf = null
        state = STATE_IDLE
        super.channelInactive(ctx)
    end channelInactive

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
        val body      = Maybe(cause.getMessage).getOrElse("Internal Server Error")
        val bodyBytes = body.getBytes(StandardCharsets.UTF_8)
        val nettyResp = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.INTERNAL_SERVER_ERROR,
            Unpooled.wrappedBuffer(bodyBytes)
        )
        discard(nettyResp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bodyBytes.length))
        discard(ctx.writeAndFlush(nettyResp).addListener(ChannelFutureListener.CLOSE))
    end exceptionCaught

end HttpServerHandler
