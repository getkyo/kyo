package kyo.internal

import io.netty.channel.{Channel as NettyChannel, *}
import kyo.*
import scala.annotation.tailrec

/** Captured response headers from a streaming response. */
private[kyo] case class StreamingHeaders(
    status: HttpResponse.Status,
    headers: Seq[(String, String)]
)

/** Streaming response handler — splits headers from body chunks (no HttpObjectAggregator). */
private[kyo] class StreamingResponseHandler(
    headerPromise: Promise.Unsafe[StreamingHeaders, Abort[HttpError]],
    byteChannel: Channel.Unsafe[Span[Byte]],
    host: String,
    port: Int
)(using Frame) extends ChannelInboundHandlerAdapter:
    override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit =
        import AllowUnsafe.embrace.danger
        // Handle response headers
        if msg.isInstanceOf[io.netty.handler.codec.http.HttpResponse] then
            val response = msg.asInstanceOf[io.netty.handler.codec.http.HttpResponse]
            val status   = HttpResponse.Status(response.status().code())
            if status.isError then
                discard(headerPromise.complete(
                    Result.fail(HttpError.StatusError(status, ""))
                ))
                discard(byteChannel.closeAwaitEmpty())
            else
                // Extract response headers
                val nettyHeaders = response.headers()
                val headerCount  = nettyHeaders.size()
                val headers      = new Array[(String, String)](headerCount)
                val iter         = nettyHeaders.iteratorAsString()
                @tailrec def fillHeaders(i: Int): Unit =
                    if i < headerCount && iter.hasNext then
                        val entry = iter.next()
                        headers(i) = (entry.getKey, entry.getValue)
                        fillHeaders(i + 1)
                fillHeaders(0)
                discard(headerPromise.complete(Result.succeed(StreamingHeaders(status, headers.toSeq))))
            end if
        end if
        // Handle body content
        if msg.isInstanceOf[io.netty.handler.codec.http.HttpContent] then
            val content = msg.asInstanceOf[io.netty.handler.codec.http.HttpContent]
            val buf     = content.content()
            if buf.readableBytes() > 0 then
                val bytes = new Array[Byte](buf.readableBytes())
                buf.readBytes(bytes)
                discard(byteChannel.offer(Span.fromUnsafe(bytes)))
            end if
            if content.isInstanceOf[io.netty.handler.codec.http.LastHttpContent] then
                discard(byteChannel.closeAwaitEmpty())
            discard(io.netty.util.ReferenceCountUtil.release(msg))
        else
            discard(io.netty.util.ReferenceCountUtil.release(msg))
        end if
    end channelRead

    override def channelInactive(ctx: ChannelHandlerContext): Unit =
        import AllowUnsafe.embrace.danger
        discard(headerPromise.complete(
            Result.fail(HttpError.InvalidResponse("Connection closed"))
        ))
        discard(byteChannel.closeAwaitEmpty())
    end channelInactive

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
        import AllowUnsafe.embrace.danger
        discard(headerPromise.complete(
            Result.fail(HttpError.fromThrowable(cause, host, port))
        ))
        discard(byteChannel.closeAwaitEmpty())
    end exceptionCaught
end StreamingResponseHandler
