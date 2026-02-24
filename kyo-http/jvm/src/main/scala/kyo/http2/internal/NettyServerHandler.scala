package kyo.http2.internal

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
import io.netty.util.ReferenceCountUtil
import kyo.<
import kyo.Abort
import kyo.Absent
import kyo.AllowUnsafe
import kyo.Async
import kyo.Channel
import kyo.Chunk
import kyo.Closed
import kyo.Duration
import kyo.Emit
import kyo.Frame
import kyo.Loop
import kyo.Maybe
import kyo.Present
import kyo.Promise
import kyo.Result
import kyo.Scope
import kyo.Span
import kyo.Stream
import kyo.Sync
import kyo.discard
import kyo.http2.*
import kyo.http2.internal.HttpRouter.*

final private[http2] class NettyServerHandler(
    router: HttpRouter
)(using Frame) extends ChannelInboundHandlerAdapter:

    private val STATE_IDLE       = 0
    private val STATE_BUFFERING  = 1
    private val STATE_STREAMING  = 2
    private val STATE_DISCARDING = 3

    private var state: Int = STATE_IDLE

    // BUFFERING state
    private var pendingRouteMatch: Maybe[RouteMatch] = Absent
    private var pendingUri: String                   = ""
    private var pendingPath: String                  = ""
    private var pendingPathEnd: Int                  = -1
    private var pendingHeaders: HttpHeaders          = HttpHeaders.empty
    private var pendingKeepAlive: Boolean            = true
    private var bodyBuf: Maybe[CompositeByteBuf]     = Absent

    // STREAMING state
    private var streamingChannel: Maybe[Channel.Unsafe[Maybe[Span[Byte]]]] = Absent

    // DISCARDING state
    private var discardStatus: HttpStatus        = HttpStatus.NotFound
    private var discardExtraHeaders: HttpHeaders = HttpHeaders.empty

    override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit =
        import AllowUnsafe.embrace.danger
        try
            state match
                case STATE_IDLE =>
                    msg match
                        case nettyReq: NettyHttpRequest => handleInitialRequest(ctx, nettyReq)
                        case _                          => discard(ctx.close())
                case STATE_BUFFERING =>
                    msg match
                        case content: HttpContent => handleBufferingContent(ctx, content)
                        case _                    => discard(ctx.close())
                case STATE_STREAMING =>
                    msg match
                        case content: HttpContent => deliverStreamingContent(ctx, content)
                        case _                    => discard(ctx.close())
                case STATE_DISCARDING =>
                    msg match
                        case content: HttpContent => handleDiscardingContent(ctx, content)
                        case _                    => discard(ctx.close())
                case _ => discard(ctx.close())
            end match
        finally
            discard(ReferenceCountUtil.release(msg))
        end try
    end channelRead

    private def handleInitialRequest(ctx: ChannelHandlerContext, nettyReq: NettyHttpRequest)(using AllowUnsafe): Unit =

        val method    = HttpMethod.unsafe(nettyReq.method().name())
        val uri       = nettyReq.uri()
        val keepAlive = HttpUtil.isKeepAlive(nettyReq)
        val headers   = NettyUtil.extractHeaders(nettyReq.headers())

        val pathEnd = uri.indexOf('?')
        val path    = if pathEnd >= 0 then uri.substring(0, pathEnd) else uri

        router.find(method, path) match
            case Result.Failure(FindError.NotFound) =>
                discardStatus = HttpStatus.NotFound
                discardExtraHeaders = HttpHeaders.empty
                pendingKeepAlive = keepAlive
                if nettyReq.isInstanceOf[LastHttpContent] then
                    sendErrorResponse(ctx, HttpStatus.NotFound, HttpHeaders.empty, keepAlive)
                    resetState()
                else
                    state = STATE_DISCARDING
                end if

            case Result.Failure(FindError.MethodNotAllowed(allowed)) =>
                val allowValue = buildAllowHeaderValue(allowed)
                discardStatus = HttpStatus.MethodNotAllowed
                discardExtraHeaders = HttpHeaders.empty.add("Allow", allowValue)
                pendingKeepAlive = keepAlive
                if nettyReq.isInstanceOf[LastHttpContent] then
                    sendErrorResponse(ctx, HttpStatus.MethodNotAllowed, discardExtraHeaders, keepAlive)
                    resetState()
                else
                    state = STATE_DISCARDING
                end if

            case Result.Success(routeMatch) =>
                handleExpectContinue(ctx, nettyReq)

                if routeMatch.isStreamingRequest then
                    state = STATE_STREAMING
                    pendingKeepAlive = keepAlive

                    val byteChannel = Channel.Unsafe.init[Maybe[Span[Byte]]](32)
                    streamingChannel = Present(byteChannel)
                    val bodyStream = Stream[Span[Byte], Async & Scope] {
                        Abort.run[Closed] {
                            Loop.foreach {
                                byteChannel.safe.takeWith {
                                    case Present(bytes) =>
                                        Emit.valueWith(Chunk(bytes))(Loop.continue)
                                    case Absent =>
                                        Loop.done(())
                                }
                            }
                        }.unit
                    }

                    val queryFn  = makeQueryParam(uri, pathEnd)
                    val endpoint = routeMatch.endpoint
                    val route    = endpoint.route

                    RouteUtil.decodeStreamingRequest(
                        route,
                        routeMatch.pathCaptures,
                        queryFn,
                        headers,
                        bodyStream,
                        path
                    ) match
                        case Result.Success(request) =>
                            invokeHandler(ctx, endpoint, route, request, keepAlive)
                        case Result.Failure(err) =>
                            streamingChannel.foreach(ch => discard(ch.close()))
                            streamingChannel = Absent
                            state = STATE_IDLE
                            sendErrorResponse(ctx, HttpStatus.BadRequest, HttpHeaders.empty, keepAlive)
                        case Result.Panic(e) =>
                            streamingChannel.foreach(ch => discard(ch.close()))
                            streamingChannel = Absent
                            state = STATE_IDLE
                            sendErrorResponse(ctx, HttpStatus.InternalServerError, HttpHeaders.empty, keepAlive)
                    end match

                    if nettyReq.isInstanceOf[HttpContent] then // Body in initial message; rest arrives via STATE_STREAMING
                        deliverStreamingContent(ctx, nettyReq.asInstanceOf[HttpContent])
                else
                    state = STATE_BUFFERING
                    pendingRouteMatch = Present(routeMatch)
                    pendingUri = uri
                    pendingPath = path
                    pendingPathEnd = pathEnd
                    pendingHeaders = headers
                    pendingKeepAlive = keepAlive
                    bodyBuf = Present(ctx.alloc().compositeBuffer())

                    if nettyReq.isInstanceOf[HttpContent] then
                        handleBufferingContent(ctx, nettyReq.asInstanceOf[HttpContent])
                end if

            case Result.Panic(_) =>
                sendErrorResponse(ctx, HttpStatus.InternalServerError, HttpHeaders.empty, keepAlive)
        end match
    end handleInitialRequest

    private def handleBufferingContent(ctx: ChannelHandlerContext, content: HttpContent)(using AllowUnsafe): Unit =
        val buf       = content.content()
        val chunkSize = buf.readableBytes()

        bodyBuf.foreach { bb =>
            if chunkSize > 0 then
                discard(bb.addComponent(true, buf.retain()))
            end if
        }

        if content.isInstanceOf[LastHttpContent] then
            val bodyData = bodyBuf match
                case Present(bb) =>
                    val data =
                        if bb.readableBytes() == 0 then Span.empty[Byte]
                        else
                            val bytes = new Array[Byte](bb.readableBytes())
                            bb.readBytes(bytes)
                            Span.fromUnsafe(bytes)
                    discard(bb.release())
                    data
                case Absent => Span.empty[Byte]
            bodyBuf = Absent

            val routeMatch = pendingRouteMatch
            val uri        = pendingUri
            val path       = pendingPath
            val pathEnd    = pendingPathEnd
            val headers    = pendingHeaders
            val keepAlive  = pendingKeepAlive

            resetState()

            routeMatch match
                case Present(rm) =>
                    val endpoint = rm.endpoint
                    val route    = endpoint.route
                    val queryFn  = makeQueryParam(uri, pathEnd)

                    RouteUtil.decodeBufferedRequest(
                        route,
                        rm.pathCaptures,
                        queryFn,
                        headers,
                        bodyData,
                        path
                    ) match
                        case Result.Success(request) =>
                            invokeHandler(ctx, endpoint, route, request, keepAlive)
                        case Result.Failure(err) =>
                            sendErrorResponse(ctx, HttpStatus.BadRequest, HttpHeaders.empty, keepAlive)
                        case Result.Panic(e) =>
                            sendErrorResponse(ctx, HttpStatus.InternalServerError, HttpHeaders.empty, keepAlive)
                    end match
                case Absent =>
                    sendErrorResponse(ctx, HttpStatus.InternalServerError, HttpHeaders.empty, keepAlive)
            end match
        end if
    end handleBufferingContent

    private def deliverStreamingContent(ctx: ChannelHandlerContext, content: HttpContent)(using AllowUnsafe): Unit =

        val buf = content.content()
        streamingChannel match
            case Present(ch) =>
                if buf.readableBytes() > 0 then
                    val bytes = new Array[Byte](buf.readableBytes())
                    buf.readBytes(bytes)
                    val value = Present(Span.fromUnsafe(bytes))
                    ch.offer(value) match
                        case Result.Success(true) =>
                        case Result.Success(false) =>
                            ctx.channel().config().setAutoRead(false)
                            val fiber = ch.putFiber(value)
                            fiber.onComplete { _ =>
                                ctx.channel().config().setAutoRead(true)
                                discard(ctx.read())
                            }
                        case _ =>
                            discard(ctx.channel().config().setAutoRead(false))
                    end match
                end if
            case Absent =>
                discard(ctx.close())
        end match

        if content.isInstanceOf[LastHttpContent] then
            streamingChannel.foreach { ch =>
                discard(ch.putFiber(Absent))
            }
            streamingChannel = Absent
            state = STATE_IDLE
        end if
    end deliverStreamingContent

    private def handleDiscardingContent(ctx: ChannelHandlerContext, content: HttpContent)(using AllowUnsafe): Unit =
        if content.isInstanceOf[LastHttpContent] then
            val status       = discardStatus
            val extraHeaders = discardExtraHeaders
            val keepAlive    = pendingKeepAlive
            resetState()
            sendErrorResponse(ctx, status, extraHeaders, keepAlive)
        end if
    end handleDiscardingContent

    private def handleExpectContinue(ctx: ChannelHandlerContext, nettyReq: NettyHttpRequest)(using AllowUnsafe): Unit =
        if HttpUtil.is100ContinueExpected(nettyReq) then
            val continueResponse = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.CONTINUE,
                Unpooled.EMPTY_BUFFER
            )
            discard(ctx.writeAndFlush(continueResponse))
        end if
    end handleExpectContinue

    private val disconnectedPanic = Result.Panic(new Exception("Client disconnected"))

    private def makeQueryParam(uri: String, pathEnd: Int): Maybe[HttpUrl] =
        if pathEnd < 0 then Absent
        else Present(HttpUrl.fromUri(uri))

    private def invokeHandler(
        ctx: ChannelHandlerContext,
        handler: HttpHandler[?, ?, ?],
        route: HttpRoute[?, ?, ?],
        request: HttpRequest[?],
        keepAlive: Boolean
    )(using AllowUnsafe): Unit =

        val h   = handler.asInstanceOf[HttpHandler[Any, Any, Any]]
        val req = request.asInstanceOf[HttpRequest[Any]]
        val rt  = route.asInstanceOf[HttpRoute[Any, Any, Any]]

        val fiber = NettyUtil.launchFiber {
            Abort.run[Any](h(req)).map {
                case Result.Success(response) =>
                    RouteUtil.encodeResponse(rt, response)(
                        onEmpty = (status, headers) =>
                            sendBufferedResponse(ctx, status, headers, Span.empty[Byte], keepAlive),
                        onBuffered = (status, headers, contentType, body) =>
                            sendBufferedResponse(ctx, status, headers.add("Content-Type", contentType), body, keepAlive),
                        onStreaming = (status, headers, contentType, stream) =>
                            sendStreamingResponse(ctx, status, headers.add("Content-Type", contentType), stream, keepAlive)
                    )
                case Result.Failure(halt: HttpResponse.Halt) =>
                    sendBufferedResponse(ctx, halt.response.status, halt.response.headers, Span.empty[Byte], keepAlive)
                case Result.Failure(error) =>
                    sendBufferedResponse(ctx, HttpStatus.InternalServerError, HttpHeaders.empty, Span.empty[Byte], keepAlive)
            }
        }

        discard {
            ctx.channel().closeFuture().addListener { (_: ChannelFuture) =>
                discard(fiber.unsafe.interrupt(disconnectedPanic))
            }
        }

        fiber.unsafe.onComplete {
            case Result.Failure(e) =>
                if ctx.channel().isActive then
                    sendErrorResponse(ctx, HttpStatus.InternalServerError, HttpHeaders.empty, keepAlive)
            case Result.Panic(e) =>
                if ctx.channel().isActive then
                    sendErrorResponse(ctx, HttpStatus.InternalServerError, HttpHeaders.empty, keepAlive)
            case _ => ()
        }
    end invokeHandler

    private def sendBufferedResponse(
        ctx: ChannelHandlerContext,
        status: HttpStatus,
        headers: HttpHeaders,
        body: Span[Byte],
        keepAlive: Boolean
    )(using AllowUnsafe): Unit =
        val content   = if body.isEmpty then Unpooled.EMPTY_BUFFER else Unpooled.wrappedBuffer(body.toArrayUnsafe)
        val nettyResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status.code), content)

        headers.foreach((k, v) => discard(nettyResp.headers().add(k, v)))

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

    private def sendStreamingResponse(
        ctx: ChannelHandlerContext,
        status: HttpStatus,
        headers: HttpHeaders,
        stream: Stream[Span[Byte], Async & Scope],
        keepAlive: Boolean
    )(using AllowUnsafe): Unit =

        val nettyResponse = new DefaultHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(status.code)
        )
        headers.foreach((k, v) => discard(nettyResponse.headers().add(k, v)))
        if !nettyResponse.headers().contains("Content-Length") then
            discard(nettyResponse.headers().set("Transfer-Encoding", "chunked"))
        if keepAlive then
            discard(nettyResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE))
        else
            discard(nettyResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE))
        end if
        discard(ctx.writeAndFlush(nettyResponse))

        val fiber = NettyUtil.launchFiber {
            Abort.run[Throwable](Abort.catching[Throwable] {
                Scope.run {
                    stream.foreach { bytes =>
                        NettyUtil.await(
                            ctx.writeAndFlush(new DefaultHttpContent(
                                Unpooled.wrappedBuffer(bytes.toArrayUnsafe)
                            ))
                        )
                    }
                }.andThen {
                    NettyUtil.await(ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT))
                }
            }).map {
                case Result.Success(_) =>
                    if !keepAlive && ctx.channel().isActive then
                        discard(ctx.close())
                case _ =>
                    if ctx.channel().isActive then discard(ctx.close())
            }
        }
        fiber.unsafe.onComplete {
            case Result.Failure(_) | Result.Panic(_) =>
                if ctx.channel().isActive then discard(ctx.close())
            case _ => ()
        }
    end sendStreamingResponse

    private def sendErrorResponse(
        ctx: ChannelHandlerContext,
        status: HttpStatus,
        extraHeaders: HttpHeaders,
        keepAlive: Boolean
    )(using AllowUnsafe): Unit =
        val nettyResp = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(status.code),
            Unpooled.EMPTY_BUFFER
        )
        extraHeaders.foreach((k, v) => discard(nettyResp.headers().add(k, v)))
        discard(nettyResp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0))
        if keepAlive then
            discard(nettyResp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE))
            discard(ctx.writeAndFlush(nettyResp))
        else
            discard(nettyResp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE))
            discard(ctx.writeAndFlush(nettyResp).addListener(ChannelFutureListener.CLOSE))
        end if
    end sendErrorResponse

    private def buildAllowHeaderValue(allowed: Set[HttpMethod]): String =
        import scala.annotation.tailrec
        val sb   = new StringBuilder
        val iter = allowed.iterator
        if iter.hasNext then sb.append(iter.next().name)
        @tailrec def loop(): Unit =
            if iter.hasNext then
                sb.append(", ")
                sb.append(iter.next().name)
                loop()
        loop()
        sb.toString
    end buildAllowHeaderValue

    private def resetState()(using AllowUnsafe): Unit =
        state = STATE_IDLE
        pendingRouteMatch = Absent
        pendingUri = ""
        pendingPath = ""
        pendingPathEnd = -1
        pendingHeaders = HttpHeaders.empty
        discardStatus = HttpStatus.NotFound
        discardExtraHeaders = HttpHeaders.empty
        bodyBuf.foreach { bb =>
            discard(bb.release())
        }
        bodyBuf = Absent
    end resetState

    override def channelInactive(ctx: ChannelHandlerContext): Unit =
        import AllowUnsafe.embrace.danger // Override entry point — cannot use (using AllowUnsafe)
        streamingChannel.foreach { ch =>
            discard(ch.putFiber(Absent))
            discard(ch.close())
        }
        streamingChannel = Absent
        bodyBuf.foreach { bb =>
            discard(bb.release())
        }
        bodyBuf = Absent
        state = STATE_IDLE
        super.channelInactive(ctx)
    end channelInactive

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
        import AllowUnsafe.embrace.danger // Override entry point — cannot use (using AllowUnsafe)
        val nettyResp = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.INTERNAL_SERVER_ERROR,
            Unpooled.EMPTY_BUFFER
        )
        discard(nettyResp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0))
        discard(ctx.writeAndFlush(nettyResp).addListener(ChannelFutureListener.CLOSE))
    end exceptionCaught

end NettyServerHandler
