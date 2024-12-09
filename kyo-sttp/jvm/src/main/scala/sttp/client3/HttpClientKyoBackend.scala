package sttp.client3

import java.io.InputStream
import java.io.UnsupportedEncodingException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublisher
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Flow.Publisher
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import java.util as ju
import kyo.*
import kyo.capabilities.KyoStreams
import kyo.internal.KyoSttpMonad
import kyo.internal.KyoSttpMonad.*
import kyo.interop.Adapters
import scala.collection.mutable.ArrayBuffer
import sttp.capabilities.WebSockets
import sttp.client3.HttpClientBackend.EncodingHandler
import sttp.client3.internal.*
import sttp.client3.internal.NoStreams
import sttp.client3.internal.emptyInputStream
import sttp.client3.internal.httpclient.*
import sttp.client3.testing.SttpBackendStub
import sttp.monad.MonadError
import sttp.ws.WebSocket
import sttp.ws.WebSocketFrame

class HttpClientKyoBackend private (
    client: HttpClient,
    closeClient: Boolean,
    customizeRequest: HttpRequest => HttpRequest,
    customEncodingHandler: EncodingHandler[KyoStreams.BinaryStream]
) extends HttpClientAsyncBackend[M, KyoStreams, WebSockets, Publisher[ju.List[ByteBuffer]], KyoStreams.BinaryStream](
        client,
        KyoSttpMonad,
        closeClient,
        customizeRequest,
        customEncodingHandler
    ):

    override val streams: KyoStreams = KyoStreams

    override protected val bodyToHttpClient = new KyoBodyToHttpClient

    override protected val bodyFromHttpClient = new KyoBodyFromHttpClient

    override protected def createSimpleQueue[A] =
        Channel.init[A](Int.MaxValue).map(new KyoSimpleQueue[A](_))

    override protected def createSequencer =
        Meter.initMutex.map(new KyoSequencer(_))

    override protected def standardEncoding: (KyoStreams.BinaryStream, String) => KyoStreams.BinaryStream = {
        case (_, ce) => throw new UnsupportedEncodingException(s"Unsupported encoding: $ce")
    }

    override protected def createBodyHandler: HttpResponse.BodyHandler[Publisher[ju.List[ByteBuffer]]] =
        BodyHandlers.ofPublisher()

    override protected def bodyHandlerBodyToBody(p: Publisher[ju.List[ByteBuffer]]): KyoStreams.BinaryStream =
        Adapters.publisherToStream(p).mapChunk { chunkList =>
            val builder = ArrayBuffer.newBuilder[Byte]
            chunkList.foreach { list =>
                val iterator = list.iterator()
                while iterator.hasNext() do
                    val bytes = iterator.next().safeRead()
                    builder ++= bytes
            }
            Chunk.from(builder.result().toArray)
        }

    override protected def emptyBody(): KyoStreams.BinaryStream = Stream.empty[Byte]
end HttpClientKyoBackend

object HttpClientKyoBackend:

    type InputStreamEncodingHandler = EncodingHandler[InputStream]

    private def apply(
        client: HttpClient,
        closeClient: Boolean,
        customizeRequest: HttpRequest => HttpRequest,
        customEncodingHandler: EncodingHandler[KyoStreams.BinaryStream]
    ): SttpBackend[KyoSttpMonad.M, WebSockets] =
        new FollowRedirectsBackend(
            new HttpClientKyoBackend(
                client,
                closeClient,
                customizeRequest,
                customEncodingHandler
            )
        )

    def apply(
        options: SttpBackendOptions = SttpBackendOptions.Default,
        customizeRequest: HttpRequest => HttpRequest = identity,
        customEncodingHandler: EncodingHandler[KyoStreams.BinaryStream] = PartialFunction.empty,
        executor: Option[Executor] = Some(r => r.run())
    ): SttpBackend[KyoSttpMonad.M, WebSockets] =
        HttpClientKyoBackend(
            HttpClientBackend.defaultClient(options, executor),
            closeClient = false,
            customizeRequest,
            customEncodingHandler
        )

    def usingClient(
        client: HttpClient,
        customizeRequest: HttpRequest => HttpRequest = identity,
        customEncodingHandler: EncodingHandler[KyoStreams.BinaryStream] = PartialFunction.empty
    ): SttpBackend[KyoSttpMonad.M, WebSockets] =
        HttpClientKyoBackend(
            client,
            closeClient = false,
            customizeRequest,
            customEncodingHandler
        )

    def stub: SttpBackendStub[KyoSttpMonad.M, WebSockets] =
        SttpBackendStub(KyoSttpMonad)
end HttpClientKyoBackend
