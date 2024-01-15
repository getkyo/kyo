package kyo.server.internal

import io.netty.buffer.ByteBufUtil
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpContent
import kyo._

import kyo.ios._
import kyo.routes._
import kyo.tries._
import sttp.capabilities.Streams
import sttp.tapir.FileRange
import sttp.tapir.InputStreamRange
import sttp.tapir.RawBodyType
import sttp.tapir.TapirFile
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.interpreter.RawValue
import sttp.tapir.server.interpreter.RequestBody

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kyo.internal.KyoSttpMonad
import kyo.internal.KyoSttpMonad._

private[kyo] class NettyKyoRequestBody(createFile: ServerRequest => TapirFile < Routes)
    extends RequestBody[KyoSttpMonad.M, Any] {

  val streams = new Streams[Any] {
    override type BinaryStream = Nothing
    override type Pipe[A, B]   = Nothing
  }
  def toStream(serverRequest: sttp.tapir.model.ServerRequest) =
    throw new UnsupportedOperationException

  override def toRaw[R](
      serverRequest: ServerRequest,
      bodyType: RawBodyType[R]
  ): RawValue[R] < Fibers = {

    bodyType match {
      case RawBodyType.StringBody(charset) =>
        nettyRequestBytes(serverRequest).map(bs => RawValue(new String(bs, charset)))
      case RawBodyType.ByteArrayBody =>
        nettyRequestBytes(serverRequest).map(RawValue(_))
      case RawBodyType.ByteBufferBody =>
        nettyRequestBytes(serverRequest).map(bs => RawValue(ByteBuffer.wrap(bs)))
      case RawBodyType.InputStreamBody =>
        nettyRequestBytes(serverRequest).map(bs => RawValue(new ByteArrayInputStream(bs)))
      case RawBodyType.InputStreamRangeBody =>
        nettyRequestBytes(serverRequest).map(bs =>
          RawValue(InputStreamRange(() => new ByteArrayInputStream(bs)))
        )
      case RawBodyType.FileBody =>
        throw new UnsupportedOperationException
      case _: RawBodyType.MultipartBody =>
        throw new UnsupportedOperationException
    }
  }

  private def nettyRequestBytes(serverRequest: ServerRequest): Array[Byte] < Fibers =
    serverRequest.underlying match {
      case req: FullHttpRequest => IOs(ByteBufUtil.getBytes(req.content()))
      case other => IOs.fail(new UnsupportedOperationException(
            s"Unexpected Netty request of type ${other.getClass().getName()}"
        ))
    }
}
