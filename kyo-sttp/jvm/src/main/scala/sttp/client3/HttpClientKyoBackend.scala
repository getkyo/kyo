package sttp.client3

import kyo._

import sttp.capabilities.WebSockets
import sttp.client3.HttpClientBackend.EncodingHandler
import sttp.client3.HttpClientFutureBackend.InputStreamEncodingHandler
import sttp.client3.internal.{NoStreams, emptyInputStream}
import sttp.client3.internal.httpclient._
import sttp.client3.testing.SttpBackendStub
import sttp.monad.MonadError
import sttp.ws.{WebSocket, WebSocketFrame}

import java.io.{InputStream, UnsupportedEncodingException}
import java.net.http.HttpRequest.BodyPublisher
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.zip.{GZIPInputStream, InflaterInputStream}
import kyo.internal.KyoSttpMonad._
import kyo.internal.KyoSttpMonad

class HttpClientKyoBackend private (
    client: HttpClient,
    closeClient: Boolean,
    customizeRequest: HttpRequest => HttpRequest,
    customEncodingHandler: InputStreamEncodingHandler
) extends HttpClientAsyncBackend[M, Nothing, WebSockets, InputStream, InputStream](
        client,
        KyoSttpMonad.instance,
        closeClient,
        customizeRequest,
        customEncodingHandler
    ) {

  override val streams: NoStreams = NoStreams

  override protected val bodyToHttpClient =
    new BodyToHttpClient[KyoSttpMonad.M, Nothing] {
      override val streams: NoStreams                  = NoStreams
      override given monad: MonadError[KyoSttpMonad.M] = KyoSttpMonad.instance
      override def streamToPublisher(stream: Nothing) =
        stream
    }

  override protected val bodyFromHttpClient =
    new InputStreamBodyFromHttpClient[KyoSttpMonad.M, Nothing] {
      override def inputStreamToStream(is: InputStream) =
        KyoSttpMonad.instance.error(new IllegalStateException("Streaming is not supported"))
      override val streams: NoStreams                  = NoStreams
      override given monad: MonadError[KyoSttpMonad.M] = KyoSttpMonad.instance
      override def compileWebSocketPipe(
          ws: WebSocket[KyoSttpMonad.M],
          pipe: streams.Pipe[WebSocketFrame.Data[_], WebSocketFrame]
      ) = pipe
    }

  override protected def createSimpleQueue[T] =
    Channels.init[T](Int.MaxValue).map(new KyoSimpleQueue[T](_))

  override protected def createSequencer =
    Meters.initMutex.map(new KyoSequencer(_))

  override protected def standardEncoding: (InputStream, String) => InputStream = {
    case (body, "gzip")    => new GZIPInputStream(body)
    case (body, "deflate") => new InflaterInputStream(body)
    case (_, ce)           => throw new UnsupportedEncodingException(s"Unsupported encoding: $ce")
  }

  override protected def createBodyHandler: HttpResponse.BodyHandler[InputStream] =
    BodyHandlers.ofInputStream()

  override protected def bodyHandlerBodyToBody(p: InputStream): InputStream = p

  override protected def emptyBody(): InputStream = emptyInputStream()
}

object HttpClientKyoBackend {

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
      customEncodingHandler: InputStreamEncodingHandler = PartialFunction.empty
  ): SttpBackend[KyoSttpMonad.M, WebSockets] = {
    HttpClientKyoBackend(
        HttpClientBackend.defaultClient(options),
        closeClient = false,
        customizeRequest,
        customEncodingHandler
    )
  }

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
    SttpBackendStub(KyoSttpMonad.instance)
}
