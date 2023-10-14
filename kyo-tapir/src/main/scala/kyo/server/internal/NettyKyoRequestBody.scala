package kyo.server.internal

import kyo._
import kyo.ios._
import kyo.tries._
import kyo.routes._
import kyo.concurrent.fibers._
import sttp.capabilities.Streams
import com.typesafe.netty.http.StreamedHttpRequest
import io.netty.buffer.ByteBufUtil
import io.netty.handler.codec.http.{FullHttpRequest, HttpContent}
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.interpreter.{RawValue, RequestBody}
import sttp.tapir.{FileRange, InputStreamRange, RawBodyType, TapirFile}

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

private[kyo] class NettyKyoRequestBody(createFile: ServerRequest => TapirFile > Routes)
    extends RequestBody[internal.M, Any] {

  val streams = new Streams[Any] {
    override type BinaryStream = Nothing
    override type Pipe[A, B]   = Nothing
  }
  def toStream(serverRequest: sttp.tapir.model.ServerRequest) =
    throw new UnsupportedOperationException

  override def toRaw[R](
      serverRequest: ServerRequest,
      bodyType: RawBodyType[R]
  ): RawValue[R] > (Fibers with IOs) = {

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

  private def nettyRequestBytes(serverRequest: ServerRequest): Array[Byte] > (Fibers with IOs) =
    serverRequest.underlying match {
      case req: FullHttpRequest => IOs(ByteBufUtil.getBytes(req.content()))
      case other => IOs.fail(new UnsupportedOperationException(
            s"Unexpected Netty request of type ${other.getClass().getName()}"
        ))
    }
}
