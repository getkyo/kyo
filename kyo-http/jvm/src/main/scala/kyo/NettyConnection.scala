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
private[kyo] class NettyConnection(
    channel: NettyChannel,
    host: String,
    port: Int,
    maxResponseSizeBytes: Int
) extends Backend.Connection:

    def send(request: HttpRequest[HttpBody.Bytes])(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        import AllowUnsafe.embrace.danger

        // Build Netty request
        val nettyMethod = HttpMethod.valueOf(request.method.name)
        val bodyData    = request.body.data
        val content     = if bodyData.isEmpty then Unpooled.EMPTY_BUFFER else Unpooled.wrappedBuffer(bodyData)
        val nettyReq    = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, nettyMethod, request.url, content)

        // Set headers
        request.headers.foreach((k, v) => discard(nettyReq.headers().add(k, v)))
        request.contentType.foreach(ct => discard(nettyReq.headers().set("Content-Type", ct)))
        if !nettyReq.headers().contains("Host") then
            if port == 80 || port == 443 then discard(nettyReq.headers().set("Host", host))
            else discard(nettyReq.headers().set("Host", s"$host:$port"))
        if !nettyReq.headers().contains("Content-Length") then
            discard(nettyReq.headers().setInt("Content-Length", content.readableBytes()))

        // Ensure aggregator is in pipeline
        val pipeline = channel.pipeline()
        if pipeline.get("aggregator") == null then
            discard(pipeline.addLast("aggregator", new HttpObjectAggregator(maxResponseSizeBytes)))

        // Remove old response handler if present, add new one
        if pipeline.get("response") != null then
            discard(pipeline.remove("response"))
        val promise = Promise.Unsafe.init[HttpResponse[HttpBody.Bytes], Abort[HttpError]]()
        discard(pipeline.addLast("response", new ResponseHandler(promise, channel, host, port)))

        // Write request and await response
        discard(channel.writeAndFlush(nettyReq))
        promise.safe.get
    end send

    def stream(request: HttpRequest[?])(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Scope & Abort[HttpError]) =
        import AllowUnsafe.embrace.danger

        // Build Netty request
        val nettyMethod = HttpMethod.valueOf(request.method.name)
        val nettyReq = request.body match
            case b: HttpBody.Bytes =>
                val content = if b.isEmpty then Unpooled.EMPTY_BUFFER else Unpooled.wrappedBuffer(b.data)
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, nettyMethod, request.url, content)
            case _: HttpBody.Streamed =>
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, nettyMethod, request.url)

        // Set headers
        request.headers.foreach((k, v) => discard(nettyReq.headers().add(k, v)))
        request.contentType.foreach(ct => discard(nettyReq.headers().set("Content-Type", ct)))
        if !nettyReq.headers().contains("Host") then
            if port == 80 || port == 443 then discard(nettyReq.headers().set("Host", host))
            else discard(nettyReq.headers().set("Host", s"$host:$port"))
        // For streaming request bodies, set Transfer-Encoding: chunked so the server
        // knows to expect body chunks after the headers.
        request.body match
            case _: HttpBody.Streamed =>
                if !nettyReq.headers().contains("Transfer-Encoding") then
                    discard(nettyReq.headers().set("Transfer-Encoding", "chunked"))
            case _ => ()
        end match

        // Remove aggregator if present (streaming doesn't use it)
        val pipeline = channel.pipeline()
        if pipeline.get("aggregator") != null then
            discard(pipeline.remove("aggregator"))
        if pipeline.get("response") != null then
            discard(pipeline.remove("response"))

        // Set up streaming handler
        val headerPromise = Promise.Unsafe.init[StreamingHeaders, Abort[HttpError]]()
        val byteChannel   = Channel.Unsafe.init[Span[Byte]](32)
        discard(pipeline.addLast("response", new ResponseStreamingHandler(headerPromise, byteChannel, host, port)))

        // Write request headers
        discard(channel.writeAndFlush(nettyReq))

        // Write streaming request body if present
        val writeBody: Unit < Async = request.body match
            case streamed: HttpBody.Streamed =>
                streamed.stream.foreach { bytes =>
                    NettyUtil.await(channel.writeAndFlush(
                        new DefaultHttpContent(Unpooled.wrappedBuffer(bytes.toArrayUnsafe))
                    ))
                }.andThen {
                    NettyUtil.await(channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT))
                }
            case _ => ()

        writeBody.andThen {
            // Await headers, return streaming response
            headerPromise.safe.get.map { sh =>
                val bodyStream: Stream[Span[Byte], Async] = Stream[Span[Byte], Async] {
                    Abort.run[Closed](byteChannel.safe.stream().emit).unit
                }
                HttpResponse.initStreaming(sh.status, sh.headers, bodyStream)
            }
        }
    end stream

    def isAlive: Boolean = channel.isActive()

    def close(using Frame): Unit < Async = NettyUtil.await(channel.close())

    private[kyo] def closeAbruptly(): Unit = discard(channel.close())

end NettyConnection
