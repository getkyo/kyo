package kyo.internal

import io.netty.channel.{Channel as NettyChannel, *}
import io.netty.handler.codec.http.FullHttpResponse
import kyo.*

/** Response handler — completes the promise with the parsed response, doesn't manage pool. */
final private[kyo] class ResponseHandler(
    promise: Promise.Unsafe[HttpResponse[HttpBody.Bytes], Abort[HttpError]],
    channel: NettyChannel,
    host: String,
    port: Int
)(using Frame) extends SimpleChannelInboundHandler[FullHttpResponse]:
    override def channelRead0(ctx: ChannelHandlerContext, msg: FullHttpResponse): Unit =
        import AllowUnsafe.embrace.danger
        val status = HttpResponse.Status(msg.status().code())
        val body   = new Array[Byte](msg.content().readableBytes())
        msg.content().readBytes(body)

        val headers  = NettyHeaderUtil.extract(msg.headers())
        val response = HttpResponse.initBytes(status, headers, Span.fromUnsafe(body))
        discard(promise.complete(Result.succeed(response)))
    end channelRead0

    override def channelInactive(ctx: ChannelHandlerContext): Unit =
        import AllowUnsafe.embrace.danger
        discard(promise.complete(Result.fail(HttpError.InvalidResponse("Connection closed by server"))))
        super.channelInactive(ctx)
    end channelInactive

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
        import AllowUnsafe.embrace.danger
        discard(promise.complete(Result.fail(HttpError.fromThrowable(cause, host, port))))
    end exceptionCaught
end ResponseHandler
