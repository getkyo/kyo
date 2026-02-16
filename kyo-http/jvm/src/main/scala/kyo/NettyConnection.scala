package kyo

import io.netty.buffer.Unpooled
import io.netty.channel.{Channel as NettyChannel, *}
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import kyo.internal.NettyUtil
import kyo.internal.ResponseHandler
import kyo.internal.ResponseStreamingHandler
import kyo.internal.StreamingHeaders

/** A Backend.Connection wrapping a Netty channel. */
final private[kyo] class NettyConnection(
    channel: NettyChannel,
    host: String,
    port: Int,
    maxResponseSizeBytes: Int,
    closed: AtomicBoolean
) extends Backend.Connection:

    def send(request: HttpRequest[HttpBody.Bytes])(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        Sync.Unsafe {
            val nettyMethod = HttpMethod.valueOf(request.method.name)
            val bodyData    = request.body.data
            val content     = if bodyData.isEmpty then Unpooled.EMPTY_BUFFER else Unpooled.wrappedBuffer(bodyData)
            val nettyReq    = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, nettyMethod, request.url, content)

            request.headers.foreach((k, v) => discard(nettyReq.headers().add(k, v)))
            request.contentType.foreach(ct => discard(nettyReq.headers().set("Content-Type", ct)))
            if !nettyReq.headers().contains("Host") then
                if port == HttpRequest.DefaultHttpPort || port == HttpRequest.DefaultHttpsPort then
                    discard(nettyReq.headers().set("Host", host))
                else discard(nettyReq.headers().set("Host", s"$host:$port"))
            end if
            if !nettyReq.headers().contains("Content-Length") then
                discard(nettyReq.headers().setInt("Content-Length", content.readableBytes()))

            // Aggregator collects HTTP chunks into a single FullHttpResponse
            val pipeline = channel.pipeline()
            if pipeline.get("aggregator") == null then
                discard(pipeline.addLast("aggregator", new HttpObjectAggregator(maxResponseSizeBytes)))

            // Each send gets a fresh promise — replace handler for pooled connection reuse
            if pipeline.get("response") != null then
                discard(pipeline.remove("response"))
            val promise = Promise.Unsafe.init[HttpResponse[HttpBody.Bytes], Abort[HttpError]]()
            discard(pipeline.addLast("response", new ResponseHandler(promise, channel, host, port)))

            discard(channel.writeAndFlush(nettyReq))
            promise.safe.get
        }
    end send

    def stream(request: HttpRequest[?])(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Scope & Abort[HttpError]) =
        Sync.Unsafe {
            // Buffered body → FullHttpRequest; streaming body → headers-only HttpRequest
            val nettyMethod = HttpMethod.valueOf(request.method.name)
            val nettyReq = request.body.use(
                b =>
                    val content = if b.isEmpty then Unpooled.EMPTY_BUFFER else Unpooled.wrappedBuffer(b.data)
                    new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, nettyMethod, request.url, content)
                ,
                _ => new DefaultHttpRequest(HttpVersion.HTTP_1_1, nettyMethod, request.url)
            )

            request.headers.foreach((k, v) => discard(nettyReq.headers().add(k, v)))
            request.contentType.foreach(ct => discard(nettyReq.headers().set("Content-Type", ct)))
            if !nettyReq.headers().contains("Host") then
                if port == HttpRequest.DefaultHttpPort || port == HttpRequest.DefaultHttpsPort then
                    discard(nettyReq.headers().set("Host", host))
                else discard(nettyReq.headers().set("Host", s"$host:$port"))
            end if
            // For streaming request bodies, set Transfer-Encoding: chunked so the server
            // knows to expect body chunks after the headers.
            request.body.use(
                _ => (),
                _ =>
                    if !nettyReq.headers().contains("Transfer-Encoding") then
                        discard(nettyReq.headers().set("Transfer-Encoding", "chunked"))
            )

            // Streaming bypasses aggregation — we want individual chunks, not a single buffer
            val pipeline = channel.pipeline()
            if pipeline.get("aggregator") != null then
                discard(pipeline.remove("aggregator"))
            if pipeline.get("response") != null then
                discard(pipeline.remove("response"))

            // Two promises: headerPromise completes when response headers arrive,
            // byteChannel delivers body chunks as they arrive
            val headerPromise = Promise.Unsafe.init[StreamingHeaders, Abort[HttpError]]()
            val byteChannel   = Channel.Unsafe.init[Span[Byte]](32)
            discard(pipeline.addLast("response", new ResponseStreamingHandler(headerPromise, byteChannel, host, port)))

            discard(channel.writeAndFlush(nettyReq))

            // Stream request body chunks if present (for chunked uploads)
            val writeBody: Unit < Async = request.body.use(
                _ => (),
                streamed =>
                    streamed.stream.foreach { bytes =>
                        NettyUtil.await(channel.writeAndFlush(
                            new DefaultHttpContent(Unpooled.wrappedBuffer(bytes.toArrayUnsafe))
                        ))
                    }.andThen {
                        NettyUtil.await(channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT))
                    }
            )

            writeBody.andThen {
                // Response headers arrive independently of body — wait for them first
                headerPromise.safe.get.map { sh =>
                    val bodyStream: Stream[Span[Byte], Async] = Stream[Span[Byte], Async] {
                        Abort.run[Closed](byteChannel.safe.stream().emit).unit
                    }
                    HttpResponse.initStreaming(sh.status, sh.headers, bodyStream)
                }
            }
        }
    end stream

    def isAlive(using AllowUnsafe): Boolean = !closed.unsafe.get() && channel.isActive()

    // Connection close is instant (TCP FIN/RST) — no grace period needed
    def close(using Frame): Unit < Async =
        closed.set(true)
            .andThen(NettyUtil.await(channel.close()))

    private[kyo] def closeAbruptly()(using AllowUnsafe): Unit =
        closed.unsafe.set(true)
        discard(channel.close())

end NettyConnection
