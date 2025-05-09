package sttp.tapir.server.netty.internal

import io.netty.handler.codec.http.HttpContent
import kyo.*
import kyo.internal.KyoSttpMonad
import org.reactivestreams.Publisher
import scala.concurrent.ExecutionContext
import sttp.capabilities
import sttp.monad.MonadError
import sttp.tapir.TapirFile
import sttp.tapir.capabilities.NoStreams
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.netty.internal.reactivestreams.SimpleSubscriber

private[netty] class NettyKyoRequestBody(val createFile: ServerRequest => KyoSttpMonad.M[TapirFile])
    extends NettyRequestBody[KyoSttpMonad.M, NoStreams]:

    override val streams: capabilities.Streams[NoStreams] = NoStreams
    override given monad: MonadError[KyoSttpMonad.M]      = KyoSttpMonad

    override def publisherToBytes(
        publisher: Publisher[HttpContent],
        contentLength: Option[Long],
        maxBytes: Option[Long]
    ): KyoSttpMonad.M[Array[Byte]] =
        Promise.initWith[Nothing, Array[Byte]] { p =>
            val fut = SimpleSubscriber.processAll(publisher, contentLength, maxBytes)
            fut.onComplete { r =>
                import AllowUnsafe.embrace.danger
                p.unsafe.complete(Result(r.get))
            }(using ExecutionContext.parasitic)
            p.get
        }

    override def writeToFile(
        serverRequest: ServerRequest,
        file: TapirFile,
        maxBytes: Option[Long]
    ): KyoSttpMonad.M[Unit] =
        throw new UnsupportedOperationException()

    override def toStream(
        serverRequest: ServerRequest,
        maxBytes: Option[Long]
    ): streams.BinaryStream =
        throw new UnsupportedOperationException()
end NettyKyoRequestBody
