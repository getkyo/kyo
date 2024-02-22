package kyo.server.internal

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.HttpContent
import java.io.InputStream
import java.nio.charset.Charset
import org.reactivestreams.Publisher
import sttp.capabilities.Streams
import sttp.model.HasHeaders
import sttp.tapir.CodecFormat
import sttp.tapir.RawBodyType
import sttp.tapir.WebSocketBodyOutput
import sttp.tapir.server.interpreter.ToResponseBody
import sttp.tapir.server.netty.NettyResponse
import sttp.tapir.server.netty.NettyResponseContent.*
import sttp.tapir.server.netty.internal.NettyToResponseBody

class NettyKyoToResponseBody(delegate: NettyToResponseBody)
    extends ToResponseBody[NettyResponse, Any]:

    val streams: Streams[Any] = new Streams[Any]:
        override type BinaryStream = Nothing
        override type Pipe[A, B]   = Nothing

    override def fromRawValue[R](
        v: R,
        headers: HasHeaders,
        format: CodecFormat,
        bodyType: RawBodyType[R]
    ): NettyResponse =
        bodyType match

            case RawBodyType.InputStreamBody => throw new UnsupportedOperationException

            case RawBodyType.InputStreamRangeBody => throw new UnsupportedOperationException

            case RawBodyType.FileBody => throw new UnsupportedOperationException

            case _: RawBodyType.MultipartBody => throw new UnsupportedOperationException

            case _ => delegate.fromRawValue(v, headers, format, bodyType)

    override def fromStreamValue(
        v: streams.BinaryStream,
        headers: HasHeaders,
        format: CodecFormat,
        charset: Option[Charset]
    ): NettyResponse =
        throw new UnsupportedOperationException

    override def fromWebSocketPipe[REQ, RESP](
        pipe: streams.Pipe[REQ, RESP],
        o: WebSocketBodyOutput[streams.Pipe[REQ, RESP], REQ, RESP, ?, Any]
    ): NettyResponse =
        throw new UnsupportedOperationException
end NettyKyoToResponseBody
