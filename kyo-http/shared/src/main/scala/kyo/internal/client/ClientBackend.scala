package kyo.internal.client

import kyo.*

/** What a client is, to the public `HttpClient` surface: a way to send one request and read its response, to open a WebSocket or a raw
  * connection, and a lifecycle.
  *
  * There is more than one, because there is more than one way to reach a server. `HttpClientBackend` opens a socket and speaks HTTP/1
  * itself, which is what the JVM, Scala Native, Node, Bun and Deno offer. A browser page has no socket and offers `fetch` and `WebSocket`
  * instead, where the browser owns the connection, its reuse, TLS and the framing; a page's client implements this same surface over those.
  *
  * The policies a request passes through on its way out (the base URL, the retry schedule, the request timeout, the redirect chain and the
  * route's client filters) belong to none of them in particular: they are the same wherever the bytes go, so an implementation carries them
  * once rather than restating them.
  *
  * @see
  *   [[kyo.HttpClient]] the public surface this backs
  * @see
  *   [[kyo.internal.client.HttpClientBackend]] the socket implementation
  */
private[kyo] trait ClientBackend:

    /** Sends `request` under `config` and hands the response to `f`, having applied every policy `config` carries. */
    def sendWithConfig[In, Out, A](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClientConfig
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException])

    /** Opens a WebSocket to `url` and hands it to `f` for the duration of the connection. */
    def connectWebSocket[A, S](
        url: HttpUrl,
        headers: HttpHeaders,
        config: HttpWebSocket.Config,
        connectTimeout: Duration = Duration.Infinity,
        clientFilter: HttpFilter.Passthrough[Nothing] = HttpFilter.noop,
        autoFilters: Boolean = true
    )(
        f: HttpWebSocket => A < S
    )(using Frame): A < (S & Async & Abort[HttpException])

    /** Opens a connection whose bytes the caller reads and writes itself, after the given request. */
    def connectRaw(
        url: HttpUrl,
        method: HttpMethod,
        body: Span[Byte],
        headers: HttpHeaders,
        connectTimeout: Duration
    )(using Frame): HttpRawConnection < (Async & Abort[HttpException] & Scope)

    /** Closes the client, giving in-flight requests `gracePeriod` to finish. */
    def closeFiber(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any]

    /** Whether the client has been closed. Named for the pool because that is what closing shuts on a socket client. */
    def isPoolClosed(using AllowUnsafe): Boolean

end ClientBackend
