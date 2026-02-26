package kyo.internal

import io.netty.buffer.Unpooled
import io.netty.channel.{Channel as NettyChannel, *}
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpMethod as NettyHttpMethod
import io.netty.handler.codec.http.HttpObject
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpResponse as NettyHttpResponse
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import kyo.*
import kyo.discard

final private[kyo] class NettyConnection(
    val channel: NettyChannel,
    val host: String,
    val port: Int
):

    // Pre-computed host header value to avoid per-request string interpolation
    private val hostHeaderValue: String =
        if port == 80 || port == 443 then host
        else s"$host:$port"

    // Set by encodeNettyRequest, read and cleared immediately after in the same Sync.Unsafe.defer block.
    // Safe: connection pool ensures sequential access (one request at a time per connection).
    private var encodedNettyReq: Maybe[HttpObject]                  = Absent
    private var encodedStreamBody: Maybe[Stream[Span[Byte], Async]] = Absent

    def sendWith[In, Out, A, S](
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(
        f: HttpResponse[Out] => A < S
    )(using Frame): A < (S & Async & Abort[HttpError]) =
        if RouteUtil.isStreamingResponse(route) then
            sendStreamingWith(route, request)(f)
        else
            sendBufferedWith(route, request)(f)
        end if
    end sendWith

    private def sendBufferedWith[In, Out, A, S](
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(
        f: HttpResponse[Out] => A < S
    )(using Frame): A < (S & Async & Abort[HttpError]) =
        Sync.Unsafe.defer {
            encodeNettyRequest(route, request)
            val nettyReq   = encodedNettyReq.getOrElse(throw new IllegalStateException("No encoded request"))
            val streamBody = encodedStreamBody
            encodedNettyReq = Absent
            encodedStreamBody = Absent

            val pipeline = channel.pipeline()
            if pipeline.get("aggregator") == null then
                discard(pipeline.addLast(
                    "aggregator",
                    // Max aggregated response content length (~2GB). App-level limits apply separately.
                    new HttpObjectAggregator(Int.MaxValue)
                ))
            end if

            if pipeline.get("response") != null then
                discard(pipeline.remove("response"))
            val promise = Promise.Unsafe.init[NettyHttpResponseData, Abort[HttpError]]()
            discard(pipeline.addLast("response", new NettyHttpResponseHandler(promise)))

            discard(channel.writeAndFlush(nettyReq))

            def readResponse =
                promise.safe.use { data =>
                    Abort.get(RouteUtil.decodeBufferedResponse(route, data.status, data.headers, data.body)).map(f)
                }

            streamBody match
                // Request body can be streaming while response is buffered (e.g., upload returning JSON)
                case Present(stream) =>
                    stream.foreach { bytes =>
                        NettyUtil.await(channel.writeAndFlush(
                            // Two allocations required by Netty HTTP codec: ByteBuf wrapper (zero-copy) + HttpContent
                            new DefaultHttpContent(Unpooled.wrappedBuffer(bytes.toArrayUnsafe))
                        ))
                    }.andThen {
                        NettyUtil.awaitWith(channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT))(readResponse)
                    }
                case Absent =>
                    readResponse
            end match
        }
    end sendBufferedWith

    private def sendStreamingWith[In, Out, A, S](
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(
        f: HttpResponse[Out] => A < S
    )(using Frame): A < (S & Async & Abort[HttpError]) =
        Sync.Unsafe.defer {
            encodeNettyRequest(route, request)
            val nettyReq   = encodedNettyReq.getOrElse(throw new IllegalStateException("No encoded request"))
            val streamBody = encodedStreamBody
            encodedNettyReq = Absent
            encodedStreamBody = Absent

            val pipeline = channel.pipeline()
            if pipeline.get("aggregator") != null then
                discard(pipeline.remove("aggregator"))
            if pipeline.get("response") != null then
                discard(pipeline.remove("response"))

            val headerPromise = Promise.Unsafe.init[NettyStreamingHeaderData, Abort[HttpError]]()
            val byteChannel   = Channel.Unsafe.init[Maybe[Span[Byte]]](32)
            discard(pipeline.addLast("response", new NettyStreamingResponseHandler(headerPromise, byteChannel)))

            discard(channel.writeAndFlush(nettyReq))

            def readResponse =
                headerPromise.safe.use { data =>
                    val bodyStream = Stream[Span[Byte], Async] {
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
                    Abort.get(RouteUtil.decodeStreamingResponse(route, data.status, data.headers, bodyStream)).map(f)
                }

            streamBody match
                case Present(stream) =>
                    stream.foreach { bytes =>
                        NettyUtil.await(channel.writeAndFlush(
                            // Two allocations required by Netty HTTP codec: ByteBuf wrapper (zero-copy) + HttpContent
                            new DefaultHttpContent(Unpooled.wrappedBuffer(bytes.toArrayUnsafe))
                        ))
                    }.andThen {
                        NettyUtil.awaitWith(channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT))(readResponse)
                    }
                case Absent => // No request body (e.g., GET to streaming endpoint)
                    readResponse
            end match
        }
    end sendStreamingWith

    private def encodeNettyRequest[In, Out](
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(using AllowUnsafe, Frame): Unit =
        RouteUtil.encodeRequest(route, request)(
            onEmpty = (url, headers) =>
                val flatHeaders = FlatNettyHttpHeaders.acquire()
                val nettyReq =
                    new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        NettyHttpMethod.valueOf(request.method.name),
                        url,
                        Unpooled.EMPTY_BUFFER,
                        flatHeaders,
                        FlatNettyHttpHeaders.acquire()
                    )
                applyHeaders(nettyReq, headers, request)
                if !flatHeaders.contains("Content-Length") then
                    discard(flatHeaders.setInt("Content-Length", 0))
                encodedNettyReq = Present(nettyReq)
                encodedStreamBody =
                    Absent
            ,
            onBuffered = (url, headers, contentType, body) =>
                val flatHeaders = FlatNettyHttpHeaders.acquire()
                val content     = Unpooled.wrappedBuffer(body.toArrayUnsafe)
                val nettyReq = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    NettyHttpMethod.valueOf(request.method.name),
                    url,
                    content,
                    flatHeaders,
                    FlatNettyHttpHeaders.acquire()
                )
                applyHeaders(nettyReq, headers, request)
                discard(flatHeaders.set("Content-Type", contentType))
                if !flatHeaders.contains("Content-Length") then
                    discard(flatHeaders.setInt("Content-Length", content.readableBytes()))
                encodedNettyReq = Present(nettyReq)
                encodedStreamBody =
                    Absent
            ,
            onStreaming = (url, headers, contentType, stream) =>
                val flatHeaders = FlatNettyHttpHeaders.acquire()
                val nettyReq = new DefaultHttpRequest(HttpVersion.HTTP_1_1, NettyHttpMethod.valueOf(request.method.name), url, flatHeaders)
                applyHeaders(nettyReq, headers, request)
                discard(flatHeaders.set("Content-Type", contentType))
                if !flatHeaders.contains("Transfer-Encoding") then
                    discard(flatHeaders.set("Transfer-Encoding", "chunked"))
                encodedNettyReq = Present(nettyReq)
                encodedStreamBody = Present(stream)
        )
    end encodeNettyRequest

    private def applyHeaders[In](
        nettyReq: io.netty.handler.codec.http.HttpRequest,
        headers: HttpHeaders,
        request: HttpRequest[In]
    )(using AllowUnsafe): Unit =
        request.headers.foreach((k, v) => discard(nettyReq.headers().add(k, v)))
        headers.foreach((k, v) => discard(nettyReq.headers().add(k, v)))
        if !nettyReq.headers().contains("Host") then
            discard(nettyReq.headers().set("Host", hostHeaderValue))
        end if
    end applyHeaders

    def isAlive(using AllowUnsafe): Boolean = channel.isActive()

    // gracePeriod is unused — single connection close is immediate
    def close(gracePeriod: Duration)(using Frame): Unit < Async =
        NettyUtil.await(channel.close())

    def closeNowUnsafe()(using AllowUnsafe): Unit =
        discard(channel.close())

end NettyConnection

private[kyo] case class NettyHttpResponseData(
    status: HttpStatus,
    headers: HttpHeaders,
    body: Span[Byte]
)

private[kyo] case class NettyStreamingHeaderData(
    status: HttpStatus,
    headers: HttpHeaders
)

final private[kyo] class NettyHttpResponseHandler(
    promise: Promise.Unsafe[NettyHttpResponseData, Abort[HttpError]]
)(using Frame) extends SimpleChannelInboundHandler[FullHttpResponse]:

    override def channelRead0(ctx: ChannelHandlerContext, msg: FullHttpResponse): Unit =
        import AllowUnsafe.embrace.danger
        val status  = HttpStatus(msg.status().code())
        val headers = msg.headers().asInstanceOf[FlatNettyHttpHeaders].toKyoHeaders
        msg.trailingHeaders() match
            case flat: FlatNettyHttpHeaders => flat.release() // return pooled instance, no HttpHeaders allocation
            case _                          =>                // aggregator uses EmptyHttpHeaders
        val buf = msg.content()
        val body =
            if buf.readableBytes() == 0 then Span.empty[Byte]
            else
                val bytes = new Array[Byte](buf.readableBytes())
                buf.readBytes(bytes)
                Span.fromUnsafe(bytes)
        discard(promise.complete(Result.succeed(NettyHttpResponseData(status, headers, body))))
    end channelRead0

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
        import AllowUnsafe.embrace.danger
        discard(promise.complete(Result.fail(HttpError.ConnectionError(
            s"Error reading response: ${cause.getMessage}",
            cause
        ))))
    end exceptionCaught

end NettyHttpResponseHandler

final private[kyo] class NettyStreamingResponseHandler(
    headerPromise: Promise.Unsafe[NettyStreamingHeaderData, Abort[HttpError]],
    byteChannel: Channel.Unsafe[Maybe[Span[Byte]]]
)(using frame: Frame) extends ChannelInboundHandlerAdapter:

    // Guards against double-completing headerPromise in channelInactive/exceptionCaught.
    // Single-threaded (Netty guarantee, non-@Sharable). Single-use (new instance per request).
    private var headersReceived = false

    override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit =
        import AllowUnsafe.embrace.danger
        msg match
            case resp: NettyHttpResponse if !headersReceived =>
                headersReceived = true
                val status  = HttpStatus(resp.status().code())
                val headers = resp.headers().asInstanceOf[FlatNettyHttpHeaders].toKyoHeaders
                discard(headerPromise.complete(Result.succeed(NettyStreamingHeaderData(status, headers))))
                msg match
                    // FullHttpResponse extends both HttpResponse and LastHttpContent
                    case content: HttpContent =>
                        deliverContent(ctx, content)
                    case _ => // DefaultHttpResponse (headers only), body follows as separate chunks
                end match
            case content: HttpContent =>
                deliverContent(ctx, content)
            case _ =>
                discard(io.netty.util.ReferenceCountUtil.release(msg))
        end match
    end channelRead

    private def deliverContent(ctx: ChannelHandlerContext, content: HttpContent)(using AllowUnsafe): Unit =
        val buf = content.content()
        if buf.readableBytes() > 0 then
            val bytes = new Array[Byte](buf.readableBytes())
            buf.readBytes(bytes)
            val value = Present(Span.fromUnsafe(bytes))
            byteChannel.offer(value) match
                case Result.Success(true) =>
                case Result.Success(false) =>
                    ctx.channel().config().setAutoRead(false)
                    val fiber = byteChannel.putFiber(value)
                    fiber.onComplete { _ =>
                        ctx.channel().config().setAutoRead(true)
                        discard(ctx.read())
                    }
                case _ => // Channel closed — stop reading
                    discard(ctx.channel().config().setAutoRead(false))
            end match
        end if
        if content.isInstanceOf[LastHttpContent] then
            discard(byteChannel.putFiber(Absent))
        // Safe: data already copied to byte array above
        discard(io.netty.util.ReferenceCountUtil.release(content))
    end deliverContent

    override def channelInactive(ctx: ChannelHandlerContext): Unit =
        import AllowUnsafe.embrace.danger
        if !headersReceived then
            discard(headerPromise.complete(Result.fail(HttpError.ConnectionError(
                "Connection closed before headers received",
                new Exception("Connection closed")
            ))))
        end if
        discard(byteChannel.close())
        super.channelInactive(ctx)
    end channelInactive

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
        import AllowUnsafe.embrace.danger
        if !headersReceived then
            discard(headerPromise.complete(Result.fail(HttpError.ConnectionError(
                s"Error reading response: ${cause.getMessage}",
                cause
            ))))
        end if
        discard(byteChannel.close())
    end exceptionCaught
end NettyStreamingResponseHandler
