package kyo.internal

import io.netty.channel.{Channel as NettyChannel, *}
import io.netty.handler.codec.http.FullHttpResponse
import java.nio.charset.StandardCharsets
import kyo.*
import scala.annotation.tailrec

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

        val nettyHeaders = msg.headers()
        val headerCount  = nettyHeaders.size()
        val headers      = new Array[(String, String)](headerCount)
        val iter         = nettyHeaders.iteratorAsString()

        @tailrec def fillHeaders(i: Int): Unit =
            if i < headerCount && iter.hasNext then
                val entry = iter.next()
                headers(i) = (entry.getKey, entry.getValue)
                fillHeaders(i + 1)

        fillHeaders(0)

        @tailrec def addHeaders(resp: HttpResponse[HttpBody.Bytes], i: Int): HttpResponse[HttpBody.Bytes] =
            if i >= headerCount then resp
            else
                val (name, value) = headers(i)
                addHeaders(resp.addHeader(name, value), i + 1)

        val response = addHeaders(HttpResponse(status, new String(body, StandardCharsets.UTF_8)), 0)
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
