package kyo.server.internal

import sttp.tapir.server.netty.internal.NettyToResponseBody
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.{DefaultHttpContent, HttpContent}
import org.reactivestreams.Publisher
import sttp.model.HasHeaders
import sttp.tapir.server.interpreter.ToResponseBody
import sttp.tapir.server.netty.NettyResponse
import sttp.tapir.server.netty.NettyResponseContent._
import sttp.tapir.{CodecFormat, RawBodyType, WebSocketBodyOutput}

import java.io.InputStream
import java.nio.charset.Charset
import sttp.capabilities.Streams

class NettyKyoToResponseBody(delegate: NettyToResponseBody)
    extends ToResponseBody[NettyResponse, Any] {

  val streams: Streams[Any] = new Streams[Any] {
    override type BinaryStream = Nothing
    override type Pipe[A, B]   = Nothing
  }

  override def fromRawValue[R](
      v: R,
      headers: HasHeaders,
      format: CodecFormat,
      bodyType: RawBodyType[R]
  ): NettyResponse = {
    bodyType match {

      case RawBodyType.InputStreamBody => throw new UnsupportedOperationException

      case RawBodyType.InputStreamRangeBody => throw new UnsupportedOperationException

      case RawBodyType.FileBody => throw new UnsupportedOperationException

      case _: RawBodyType.MultipartBody => throw new UnsupportedOperationException

      case _ => delegate.fromRawValue(v, headers, format, bodyType)
    }
  }

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
}
