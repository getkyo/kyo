package sttp.client3

import java.net.http.HttpRequest.BodyPublisher
import java.net.http.HttpRequest.BodyPublishers
import java.nio.ByteBuffer
import kyo.*
import kyo.capabilities.KyoStreams
import kyo.internal.KyoSttpMonad
import kyo.internal.KyoSttpMonad.*
import kyo.interop.Adapters
import sttp.client3.internal.httpclient.BodyToHttpClient
import sttp.monad.MonadError

final class KyoBodyToHttpClient extends BodyToHttpClient[KyoSttpMonad.M, KyoStreams]:
    override val streams: KyoStreams                 = KyoStreams
    override given monad: MonadError[KyoSttpMonad.M] = KyoSttpMonad
    override def streamToPublisher(stream: KyoStreams.BinaryStream): KyoSttpMonad.M[BodyPublisher] =
        val byteBufferStream = stream.mapChunk { chunk =>
            Chunk(ByteBuffer.wrap(chunk.toArray))
        }
        Adapters.streamToPublisher(byteBufferStream).map { publisher =>
            BodyPublishers.fromPublisher(publisher)
        }
    end streamToPublisher
end KyoBodyToHttpClient
