package kyo.internal

import io.netty.channel.{Channel as NettyChannel, *}
import kyo.*

/** Streaming response handler — splits headers from body chunks (no HttpObjectAggregator). */
final private[kyo] class ResponseStreamingHandler(
    headerPromise: Promise.Unsafe[StreamingHeaders, Abort[HttpError]],
    byteChannel: Channel.Unsafe[Span[Byte]],
    host: String,
    port: Int
)(using Frame) extends ChannelInboundHandlerAdapter:
    override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit =
        import AllowUnsafe.embrace.danger
        // Netty delivers HttpResponse (headers) then HttpContent chunks — both arrive here
        if msg.isInstanceOf[io.netty.handler.codec.http.HttpResponse] then
            val response = msg.asInstanceOf[io.netty.handler.codec.http.HttpResponse]
            val status   = HttpResponse.Status(response.status().code())
            val headers  = NettyHeaderUtil.extract(response.headers())
            discard(headerPromise.complete(Result.succeed(StreamingHeaders(status, headers))))
        end if
        // Body chunks are forwarded to byteChannel for the streaming consumer
        if msg.isInstanceOf[io.netty.handler.codec.http.HttpContent] then
            val content = msg.asInstanceOf[io.netty.handler.codec.http.HttpContent]
            val buf     = content.content()
            if buf.readableBytes() > 0 then
                val bytes = new Array[Byte](buf.readableBytes())
                buf.readBytes(bytes)
                val value = Span.fromUnsafe(bytes)
                byteChannel.offer(value) match
                    case Result.Success(true)  => // offered successfully
                    case Result.Success(false) =>
                        // Channel full — apply backpressure via Netty auto-read
                        ctx.channel().config().setAutoRead(false)
                        val fiber = byteChannel.putFiber(value)
                        fiber.onComplete { _ =>
                            ctx.channel().config().setAutoRead(true)
                            discard(ctx.read())
                        }
                    case _ => // channel closed, drop
                end match
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
end ResponseStreamingHandler
