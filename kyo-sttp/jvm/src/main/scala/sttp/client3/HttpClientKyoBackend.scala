package sttp.client3

import java.io.InputStream
import java.io.UnsupportedEncodingException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.util.concurrent.Executor
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kyo.*
import kyo.internal.KyoSttpMonad
import kyo.internal.KyoSttpMonad.*
import sttp.capabilities.WebSockets
import sttp.client3.HttpClientBackend.EncodingHandler
import sttp.client3.HttpClientFutureBackend.InputStreamEncodingHandler
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
    customEncodingHandler: InputStreamEncodingHandler
) extends HttpClientAsyncBackend[M, Nothing, WebSockets, InputStream, InputStream](
        client,
        KyoSttpMonad,
        closeClient,
        customizeRequest,
        customEncodingHandler
    ):

    override val streams: NoStreams = NoStreams

    override protected val bodyToHttpClient =
        new BodyToHttpClient[KyoSttpMonad.M, Nothing]:
            override val streams: NoStreams                  = NoStreams
            override given monad: MonadError[KyoSttpMonad.M] = KyoSttpMonad
            override def streamToPublisher(stream: Nothing) =
                stream

    override protected val bodyFromHttpClient =
        new InputStreamBodyFromHttpClient[KyoSttpMonad.M, Nothing]:
            override def inputStreamToStream(is: InputStream) =
                KyoSttpMonad.error(new IllegalStateException("Streaming is not supported"))
            override val streams: NoStreams                  = NoStreams
            override given monad: MonadError[KyoSttpMonad.M] = KyoSttpMonad
            override def compileWebSocketPipe(
                ws: WebSocket[KyoSttpMonad.M],
                pipe: streams.Pipe[WebSocketFrame.Data[?], WebSocketFrame]
            ) = pipe

    override protected def createSimpleQueue[A] =
        Channel.init[A](Int.MaxValue).map(new KyoSimpleQueue[A](_))

    override protected def createSequencer =
        Meter.initMutex.map(new KyoSequencer(_))

    override protected def standardEncoding: (InputStream, String) => InputStream = {
        case (body, "gzip")    => new GZIPInputStream(body)
        case (body, "deflate") => new InflaterInputStream(body)
        case (_, ce)           => throw new UnsupportedEncodingException(s"Unsupported encoding: $ce")
    }

    override protected def createBodyHandler: HttpResponse.BodyHandler[InputStream] =
        BodyHandlers.ofInputStream()

    override protected def bodyHandlerBodyToBody(p: InputStream): InputStream = p

    override protected def emptyBody(): InputStream = emptyInputStream()
end HttpClientKyoBackend

object HttpClientKyoBackend:

    type InputStreamEncodingHandler = EncodingHandler[InputStream]

    private def apply(
        client: HttpClient,
        closeClient: Boolean,
        customizeRequest: HttpRequest => HttpRequest,
        customEncodingHandler: InputStreamEncodingHandler
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
        customEncodingHandler: InputStreamEncodingHandler = PartialFunction.empty,
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
        customEncodingHandler: InputStreamEncodingHandler = PartialFunction.empty
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
