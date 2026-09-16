package kyo.internal.client

import kyo.*
import kyo.internal.server.RouteUtil
import scala.scalajs.js
import scala.scalajs.js.typedarray.*

/** The client a browser page gets, over the page's own `fetch`.
  *
  * A page has no socket, so the socket backend's whole layer (connect, HTTP/1 framing, the connection pool) has no counterpart here: the
  * browser owns the connection, its reuse, TLS, the framing and the redirect chain, and hands back a response. What kyo keeps is the part
  * above the wire, which [[PolicyClientBackend]] carries: the base URL, the retry schedule, the request timeout and the filters.
  *
  * Redirects are the browser's here. `fetch` with `redirect: "manual"` answers a cross-origin redirect with an opaque response that carries
  * neither status nor `Location`, so kyo's own redirect chain cannot run in a page; the browser follows them instead and returns the final
  * response, which is why a 3xx never reaches the policy layer's redirect step.
  *
  * What a page cannot do at all fails with [[kyo.HttpUnsupportedOnHostException]] rather than silently doing something else: a unix socket,
  * a raw connection, a trust store of kyo's own, and the header names the fetch specification reserves for the browser, which it drops from
  * a request without saying so.
  */
final private[kyo] class FetchClientBackend extends PolicyClientBackend:

    import FetchClientBackend.*

    private var closedFlag = false

    override protected def dispatchWith[In, Out, A](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClientConfig
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        refusedConfig(config) match
            case Present(ex) => Abort.fail(ex)
            case Absent =>
                RouteUtil.multipartBoundaryForRequest(route, request).map { boundary =>
                    RouteUtil.encodeRequestWithBoundary(route, request, boundary)(
                        onEmpty = (path, headers) => send(route, request, path, headers, Absent)(f),
                        onBuffered = (path, headers, body) => send(route, request, path, headers, Present(body))(f),
                        onStreaming = (path, headers, _) =>
                            // A page can stream a request body only where `duplex: "half"` is available, and only over HTTP/2 in some
                            // browsers. Until that is probed and mapped, refusing says so rather than buffering a stream the caller
                            // meant to keep open.
                            Abort.fail(HttpUnsupportedOnHostException("A streamed request body"))
                    )
                }

    /** The parts of a request's configuration a page cannot honor. */
    private def refusedConfig(config: HttpClientConfig)(using Frame): Maybe[HttpException] =
        if config.tls != HttpTlsConfig.default then Present(HttpUnsupportedOnHostException("A TLS configuration of its own"))
        else Absent

    private def send[In, Out, A](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        path: String,
        headers: HttpHeaders,
        body: Maybe[Span[Byte]]
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        request.url.unixSocket match
            case Present(_) => Abort.fail(HttpUnsupportedOnHostException("A unix socket"))
            case Absent =>
                reservedHeader(headers) match
                    case Present(name) =>
                        Abort.fail(HttpUnsupportedOnHostException(s"The $name header, which the browser sets itself,"))
                    case Absent =>
                        val url = requestUrl(request.url, path)
                        fetch(url, request.method.name, headers, body).map { response =>
                            readBody(response).map { bytes =>
                                RouteUtil.decodeBufferedResponse(
                                    route,
                                    HttpStatus(status(response)),
                                    responseHeaders(response),
                                    bytes,
                                    route.method.name,
                                    request.url
                                ) match
                                    case Result.Success(decoded) => f(decoded)
                                    case Result.Failure(e)       => Abort.fail(e)
                                    case Result.Panic(t)         => Abort.panic(t)
                            }
                        }

    /** The URL to fetch: the request's own when it names a host, and the page-relative path when it does not. */
    private def requestUrl(url: HttpUrl, path: String): String =
        url.scheme match
            case Present(scheme) if url.host.nonEmpty =>
                val port          = url.port
                val isDefaultPort = if url.ssl then port == 443 else port == 80
                val authority     = if isDefaultPort then url.host else s"${url.host}:$port"
                s"$scheme://$authority$path"
            case _ => path

    private def fetch(url: String, method: String, headers: HttpHeaders, body: Maybe[Span[Byte]])(using
        Frame
    ): js.Dynamic < (Async & Abort[HttpException]) =
        Sync.defer {
            val init      = js.Dynamic.literal(method = method, redirect = "follow")
            val jsHeaders = js.Dynamic.literal()
            headers.foreach((name, value) => jsHeaders.updateDynamic(name)(value))
            init.updateDynamic("headers")(jsHeaders)
            body.foreach(span => init.updateDynamic("body")(new Uint8Array(span.toArray.toTypedArray.buffer)))
            js.Dynamic.global.fetch(url, init).asInstanceOf[js.Promise[js.Dynamic]]
        }.map { promise =>
            Abort.run[Throwable](Async.fromFuture(promise.toFuture)).map {
                case Result.Success(response) => response
                // A fetch rejects with a TypeError that names no cause: a refused connection, a DNS failure, a CORS
                // rejection and a certificate the browser will not trust all arrive the same way, by design, so the
                // failure says what kyo knows rather than inventing a distinction.
                case Result.Failure(t) => Abort.fail(HttpConnectException("", 0, t))
                case Result.Panic(t)   => Abort.panic(t)
            }
        }

    private def readBody(response: js.Dynamic)(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        Sync.defer(response.arrayBuffer().asInstanceOf[js.Promise[ArrayBuffer]]).map { promise =>
            Abort.run[Throwable](Async.fromFuture(promise.toFuture)).map {
                case Result.Success(buffer) => Span.from(new Int8Array(buffer).toArray)
                case Result.Failure(t)      => Abort.fail(HttpConnectException("", 0, t))
                case Result.Panic(t)        => Abort.panic(t)
            }
        }

    def connectWebSocket[A, S](
        url: HttpUrl,
        headers: HttpHeaders,
        config: HttpWebSocket.Config,
        connectTimeout: Duration,
        clientFilter: HttpFilter.Passthrough[Nothing],
        autoFilters: Boolean
    )(
        f: HttpWebSocket => A < S
    )(using Frame): A < (S & Async & Abort[HttpException]) =
        Abort.fail(HttpUnsupportedOnHostException("A WebSocket"))

    def connectRaw(
        url: HttpUrl,
        method: HttpMethod,
        body: Span[Byte],
        headers: HttpHeaders,
        connectTimeout: Duration
    )(using Frame): HttpRawConnection < (Async & Abort[HttpException] & Scope) =
        Abort.fail(HttpUnsupportedOnHostException("A raw connection"))

    def closeFiber(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        // A page holds no connection of its own: the browser owns every one of them and its own lifecycle, so closing is
        // the flag and nothing else, and the grace period has nothing to wait for.
        closedFlag = true
        val closePromise = Promise.Unsafe.init[Unit, Any]()
        closePromise.completeDiscard(Result.succeed(()))
        closePromise
    end closeFiber

    def isPoolClosed(using AllowUnsafe): Boolean = closedFlag

end FetchClientBackend

private[kyo] object FetchClientBackend:

    /** The header names the fetch specification reserves for the browser, which it drops from a request without saying so. */
    private val reserved = Set(
        "accept-charset",
        "accept-encoding",
        "access-control-request-headers",
        "access-control-request-method",
        "connection",
        "content-length",
        "cookie",
        "date",
        "dnt",
        "expect",
        "host",
        "keep-alive",
        "origin",
        "referer",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
        "via"
    )

    /** The first reserved header the request carries, or `Absent` when the browser will send them all. */
    private[client] def reservedHeader(headers: HttpHeaders): Maybe[String] =
        var found = Maybe.empty[String]
        headers.foreach { (name, _) =>
            if found.isEmpty then
                val lower = name.toLowerCase
                if reserved.contains(lower) || lower.startsWith("sec-") || lower.startsWith("proxy-") then found = Present(name)
        }
        found
    end reservedHeader

    private[client] def status(response: js.Dynamic): Int = response.status.asInstanceOf[Int]

    /** The response's headers, read through the `Headers` iteration the fetch specification defines. */
    private[client] def responseHeaders(response: js.Dynamic): HttpHeaders =
        var headers = HttpHeaders.empty
        discard(response.headers.forEach({ (value: String, name: String) =>
            headers = headers.add(name, value)
            ()
        }: js.Function2[String, String, Unit]))
        headers
    end responseHeaders

end FetchClientBackend
