package kyo

import kyo.*
import kyo.internal.HttpPlatformTransport
import kyo.internal.client.HttpClientBackend

/** HTTP client with connection pooling, retries, redirects, and typed request/response handling.
  *
  * HttpClient manages a per-host connection pool shared across all fibers. Convenience methods like `getJson`, `postText`, and `getSseJson`
  * create an [[kyo.HttpRoute]] internally and delegate to the typed send path — routes are the underlying abstraction even when users don't
  * interact with them directly. For full control over request and response encoding, use `sendWith` with an explicit route.
  *
  * The active client and its configuration live in fiber-local storage via `Local`. All companion-object methods (`getJson`, `postJson`,
  * etc.) use a shared default client. To scope a custom client or configuration to a block of code:
  *   - `HttpClient.let(client) { ... }` — install a specific client instance for the duration
  *   - `HttpClient.withConfig(_.timeout(10.seconds)) { ... }` — transform the config (stacks with the current config)
  *   - `HttpClient.withConfig(config) { ... }` — replace the config entirely (discards current config)
  *
  * The request lifecycle chains through `retryWith → redirectsWith → timeoutWith → poolWith`, each layer wrapping the next. Retries only
  * activate when a `Schedule` is set in the config. Redirects follow up to `maxRedirects` hops and switch to GET on 303 See Other per RFC
  * 9110. A timeout cancels the entire operation including in-progress retries.
  *
  * Note: The default shared client is created lazily on first use. It holds at most 100 idle connections per host and releases connections
  * after 60 seconds of inactivity. Use `HttpClient.init` when you need isolated connection pools or non-default limits.
  *
  * @see
  *   [[kyo.HttpRoute]] The endpoint contract that drives typed serialization
  * @see
  *   [[kyo.HttpClientConfig]] Configuration for timeouts, retries, redirects, and base URL
  * @see
  *   [[kyo.HttpException]] The error hierarchy for client failures
  * @see
  *   [[kyo.HttpWebSocket]] WebSocket connections opened via `HttpClient.webSocket`
  */
opaque type HttpClient = HttpClientBackend[?]

object HttpClient:

    // Bootstrap boundary: lazy val requires eager evaluation of the suspended pool init.
    private lazy val defaultClient: HttpClient =
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        initUnsafe(HttpPlatformTransport.transport, 100, 60.seconds)
    end defaultClient

    private val local: Local[(HttpClient, HttpClientConfig)] = Local.init((defaultClient, HttpClientConfig()))

    // Cached non-generic routes — avoids per-request allocation for convenience methods.
    private val routeGetText      = HttpRoute.getText("")
    private val routeGetBinary    = HttpRoute.getBinary("")
    private val routePostText     = HttpRoute.postText("")
    private val routePostBinary   = HttpRoute.postBinary("")
    private val routePutText      = HttpRoute.putText("")
    private val routePutBinary    = HttpRoute.putBinary("")
    private val routePatchText    = HttpRoute.patchText("")
    private val routePatchBinary  = HttpRoute.patchBinary("")
    private val routeDeleteText   = HttpRoute.deleteText("")
    private val routeDeleteBinary = HttpRoute.deleteBinary("")
    private val routeHeadRaw      = HttpRoute.headRaw("")
    private val routeOptionsRaw   = HttpRoute.optionsRaw("")
    private val routeSseText      = HttpRoute.getRaw("").response(_.bodySseText)

    extension (self: HttpClient)
        /** Sends a typed request using the given route and processes the response with `f`. Applies the current fiber-local configuration
          * (base URL, timeout, retries, redirects) and the route's client-side filters.
          */
        def sendWith[In, Out, A](
            route: HttpRoute[In, Out, Any],
            request: HttpRequest[In]
        )(
            f: HttpResponse[Out] => A < (Async & Abort[HttpException])
        )(using Frame): A < (Async & Abort[HttpException]) =
            local.use { (_, config) =>
                self.sendWithConfig(route, request, config)(f)
            }

        /** Closes the client with a grace period for in-flight requests. */
        def close(gracePeriod: Duration)(using Frame): Unit < Async =
            Sync.Unsafe.defer(self.closeFiber(gracePeriod).safe.get)

        /** Closes the client with a default grace period (30 seconds). */
        def close(using Frame): Unit < Async = close(30.seconds)

        /** Closes the client immediately without waiting for in-flight requests. */
        def closeNow(using Frame): Unit < Async = close(Duration.Zero)

        /** Reference equality check (for testing scope isolation). */
        private[kyo] def eq(that: HttpClient): Boolean = (self: AnyRef) eq (that: AnyRef)
    end extension

    // --- Context management ---

    /** Sets the client instance for all `HttpClient` calls within the given computation. The current config is preserved. */
    def let[A, S](client: HttpClient)(v: A < S)(using Frame): A < S =
        local.use { (_, config) => local.let((client, config))(v) }

    /** Accesses the current client. */
    def use[A, S](f: HttpClient => A < S)(using Frame): A < S =
        local.use { (client, _) => f(client) }

    /** Transforms the current client for the given computation. */
    def update[A, S](f: HttpClient => HttpClient)(v: A < S)(using Frame): A < S =
        local.use { (client, config) => local.let((f(client), config))(v) }

    /** Applies a config transformation for all `HttpClient` calls within the given computation. Stacks with the current config —
      * `withConfig(_.timeout(5.seconds)) { withConfig(_.retry(schedule)) { ... } }` results in a config with both timeout and retry set.
      */
    def withConfig[A, S](f: HttpClientConfig => HttpClientConfig)(v: A < S)(using Frame): A < S =
        local.use { (client, config) => local.let((client, f(config)))(v) }

    /** Replaces the config entirely for all `HttpClient` calls within the given computation. Unlike the function overload, this does not
      * stack — it discards the current config.
      */
    def withConfig[A, S](config: HttpClientConfig)(v: A < S)(using Frame): A < S =
        local.use { (client, _) => local.let((client, config))(v) }

    // --- Factory methods ---

    /** Creates a scoped client with its own connection pool. The client closes automatically when the enclosing Scope exits. Use
      * `HttpClient.let(client) { ... }` to make it the active client for a block of code.
      */
    def init(
        maxConnectionsPerHost: Int = 100,
        idleConnectionTimeout: Duration = 60.seconds,
        defaultTlsConfig: HttpTlsConfig = HttpTlsConfig.default
    )(using Frame): HttpClient < (Async & Scope) =
        Scope.acquireRelease(initUnscoped(maxConnectionsPerHost, idleConnectionTimeout, defaultTlsConfig))(_.closeNow)

    /** Creates a client with its own connection pool that must be closed explicitly via `close()`. Prefer `init` with Scope-based lifecycle
      * unless you need manual control.
      */
    def initUnscoped(
        maxConnectionsPerHost: Int = 100,
        idleConnectionTimeout: Duration = 60.seconds,
        defaultTlsConfig: HttpTlsConfig = HttpTlsConfig.default
    )(using frame: Frame): HttpClient < Sync =
        require(maxConnectionsPerHost > 0, s"maxConnectionsPerHost must be positive: $maxConnectionsPerHost")
        require(idleConnectionTimeout > Duration.Zero, s"idleConnectionTimeout must be positive: $idleConnectionTimeout")
        Sync.Unsafe.defer {
            initUnsafe(HttpPlatformTransport.transport, maxConnectionsPerHost, idleConnectionTimeout, defaultTlsConfig)
        }
    end initUnscoped

    // ==================== JSON methods ====================

    // --- GET ---

    def getJson[A: Json](
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): A < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, HttpRoute.getJson[A](""), resolveHeaders(headers), resolveQuery(query))(_.fields.body))

    def getJsonResponse[A: Json](
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): HttpResponse["body" ~ A] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, HttpRoute.getJson[A](""), resolveHeaders(headers), resolveQuery(query))(identity))

    // --- POST ---

    def postJson[A: Json](using
        Frame
    )[B: Json](
        url: String | HttpUrl,
        body: B,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    ): A < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u =>
            sendUrl(u, HttpRoute.postJson[A, B](""), body, resolveHeaders(headers), resolveQuery(query))(_.fields.body)
        )

    def postJsonResponse[A: Json](using
        Frame
    )[B: Json](
        url: String | HttpUrl,
        body: B,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    ): HttpResponse["body" ~ A] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, HttpRoute.postJson[A, B](""), body, resolveHeaders(headers), resolveQuery(query))(identity))

    // --- PUT ---

    def putJson[A: Json](using
        Frame
    )[B: Json](
        url: String | HttpUrl,
        body: B,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    ): A < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, HttpRoute.putJson[A, B](""), body, resolveHeaders(headers), resolveQuery(query))(_.fields.body))

    def putJsonResponse[A: Json](using
        Frame
    )[B: Json](
        url: String | HttpUrl,
        body: B,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    ): HttpResponse["body" ~ A] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, HttpRoute.putJson[A, B](""), body, resolveHeaders(headers), resolveQuery(query))(identity))

    // --- PATCH ---

    def patchJson[A: Json](using
        Frame
    )[B: Json](
        url: String | HttpUrl,
        body: B,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    ): A < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u =>
            sendUrl(u, HttpRoute.patchJson[A, B](""), body, resolveHeaders(headers), resolveQuery(query))(_.fields.body)
        )

    def patchJsonResponse[A: Json](using
        Frame
    )[B: Json](
        url: String | HttpUrl,
        body: B,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    ): HttpResponse["body" ~ A] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, HttpRoute.patchJson[A, B](""), body, resolveHeaders(headers), resolveQuery(query))(identity))

    // --- DELETE ---

    def deleteJson[A: Json](
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): A < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, HttpRoute.deleteJson[A](""), resolveHeaders(headers), resolveQuery(query))(_.fields.body))

    def deleteJsonResponse[A: Json](
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): HttpResponse["body" ~ A] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, HttpRoute.deleteJson[A](""), resolveHeaders(headers), resolveQuery(query))(identity))

    // ==================== Text methods ====================

    // --- GET ---

    def getText(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): String < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routeGetText, resolveHeaders(headers), resolveQuery(query))(_.fields.body))

    def getTextResponse(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): HttpResponse["body" ~ String] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routeGetText, resolveHeaders(headers), resolveQuery(query))(identity))

    // --- POST ---

    def postText(
        url: String | HttpUrl,
        body: String,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): String < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routePostText, body, resolveHeaders(headers), resolveQuery(query))(_.fields.body))

    def postTextResponse(
        url: String | HttpUrl,
        body: String,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): HttpResponse["body" ~ String] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routePostText, body, resolveHeaders(headers), resolveQuery(query))(identity))

    // --- PUT ---

    def putText(
        url: String | HttpUrl,
        body: String,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): String < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routePutText, body, resolveHeaders(headers), resolveQuery(query))(_.fields.body))

    def putTextResponse(
        url: String | HttpUrl,
        body: String,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): HttpResponse["body" ~ String] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routePutText, body, resolveHeaders(headers), resolveQuery(query))(identity))

    // --- PATCH ---

    def patchText(
        url: String | HttpUrl,
        body: String,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): String < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routePatchText, body, resolveHeaders(headers), resolveQuery(query))(_.fields.body))

    def patchTextResponse(
        url: String | HttpUrl,
        body: String,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): HttpResponse["body" ~ String] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routePatchText, body, resolveHeaders(headers), resolveQuery(query))(identity))

    // --- DELETE ---

    def deleteText(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): String < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routeDeleteText, resolveHeaders(headers), resolveQuery(query))(_.fields.body))

    def deleteTextResponse(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): HttpResponse["body" ~ String] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routeDeleteText, resolveHeaders(headers), resolveQuery(query))(identity))

    // ==================== Binary methods ====================

    // --- GET ---

    def getBinary(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routeGetBinary, resolveHeaders(headers), resolveQuery(query))(_.fields.body))

    def getBinaryResponse(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): HttpResponse["body" ~ Span[Byte]] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routeGetBinary, resolveHeaders(headers), resolveQuery(query))(identity))

    // --- POST ---

    def postBinary(
        url: String | HttpUrl,
        body: Span[Byte],
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routePostBinary, body, resolveHeaders(headers), resolveQuery(query))(_.fields.body))

    def postBinaryResponse(
        url: String | HttpUrl,
        body: Span[Byte],
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): HttpResponse["body" ~ Span[Byte]] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routePostBinary, body, resolveHeaders(headers), resolveQuery(query))(identity))

    // --- PUT ---

    def putBinary(
        url: String | HttpUrl,
        body: Span[Byte],
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routePutBinary, body, resolveHeaders(headers), resolveQuery(query))(_.fields.body))

    def putBinaryResponse(
        url: String | HttpUrl,
        body: Span[Byte],
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): HttpResponse["body" ~ Span[Byte]] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routePutBinary, body, resolveHeaders(headers), resolveQuery(query))(identity))

    // --- PATCH ---

    def patchBinary(
        url: String | HttpUrl,
        body: Span[Byte],
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routePatchBinary, body, resolveHeaders(headers), resolveQuery(query))(_.fields.body))

    def patchBinaryResponse(
        url: String | HttpUrl,
        body: Span[Byte],
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): HttpResponse["body" ~ Span[Byte]] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routePatchBinary, body, resolveHeaders(headers), resolveQuery(query))(identity))

    // --- DELETE ---

    def deleteBinary(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routeDeleteBinary, resolveHeaders(headers), resolveQuery(query))(_.fields.body))

    def deleteBinaryResponse(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): HttpResponse["body" ~ Span[Byte]] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routeDeleteBinary, resolveHeaders(headers), resolveQuery(query))(identity))

    // ==================== Streaming methods ====================

    // --- SSE JSON ---

    def getSseJson[V: Json: Tag](
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame, Tag[Emit[Chunk[HttpSseEvent[V]]]]): Stream[HttpSseEvent[V], Async & Abort[HttpException]] =
        Stream(resolveUrl(url).map(u =>
            sendUrl(
                u,
                HttpRoute.getRaw("").response(_.bodySseJson[V]),
                resolveHeaders(headers),
                resolveQuery(query)
            )(_.fields.body).map(_.emit)
        ))

    // --- SSE Text ---

    def getSseText(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame, Tag[Emit[Chunk[HttpSseEvent[String]]]]): Stream[HttpSseEvent[String], Async & Abort[HttpException]] =
        Stream(resolveUrl(url).map(u =>
            sendUrl(
                u,
                routeSseText,
                resolveHeaders(headers),
                resolveQuery(query)
            )(_.fields.body).map(_.emit)
        ))

    // --- NDJSON ---

    def getNdJson[V: Json: Tag](
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame, Tag[Emit[Chunk[V]]]): Stream[V, Async & Abort[HttpException]] =
        Stream(resolveUrl(url).map(u =>
            sendUrl(
                u,
                HttpRoute.getRaw("").response(_.bodyNdjson[V]),
                resolveHeaders(headers),
                resolveQuery(query)
            )(_.fields.body).map(_.emit)
        ))

    // ==================== HEAD methods ====================

    def head(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): HttpResponse[Any] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routeHeadRaw, resolveHeaders(headers), resolveQuery(query))(identity))

    // ==================== OPTIONS methods ====================

    def options(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): HttpResponse[Any] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrl(u, routeOptionsRaw, resolveHeaders(headers), resolveQuery(query))(identity))

    // ==================== HttpWebSocket methods ====================

    /** Connects to a HttpWebSocket endpoint. The connection closes when `f` returns. */
    def webSocket[A, S](
        url: String,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        config: HttpWebSocket.Config = HttpWebSocket.Config()
    )(
        f: HttpWebSocket => A < S
    )(using Frame): A < (S & Async & Abort[HttpException]) =
        local.use { (client, clientConfig) =>
            val resolved = clientConfig.baseUrl match
                case Present(base) if !url.contains("://") =>
                    base.toString.stripSuffix("/") + url
                case _ => url
            Abort.get(HttpUrl.parse(resolved)).map(parsed =>
                client.connectWebSocket(parsed, resolveHeaders(headers), config, clientConfig.connectTimeout)(f)
            )
        }

    /** Connects to a HttpWebSocket endpoint from a parsed URL. */
    def webSocket[A, S](url: HttpUrl)(
        f: HttpWebSocket => A < S
    )(using Frame): A < (S & Async & Abort[HttpException]) =
        webSocket(url, HttpHeaders.empty, HttpWebSocket.Config())(f)

    /** Connects to a HttpWebSocket endpoint from a parsed URL with custom headers and configuration. */
    def webSocket[A, S](url: HttpUrl, headers: HttpHeaders, config: HttpWebSocket.Config)(
        f: HttpWebSocket => A < S
    )(using Frame): A < (S & Async & Abort[HttpException]) =
        local.use { (client, clientConfig) =>
            client.connectWebSocket(url, headers, config, clientConfig.connectTimeout)(f)
        }

    // --- Internal helpers ---

    private def resolveUrl(url: String | HttpUrl)(using Frame): HttpUrl < Abort[HttpException] =
        url match
            case s: String  => Abort.get(HttpUrl.parse(s))
            case u: HttpUrl => u

    private def resolveHeaders(h: HttpHeaders | Seq[(String, String)]): HttpHeaders =
        h match
            case h: HttpHeaders @unchecked           => h
            case s: Seq[(String, String)] @unchecked => HttpHeaders.init(s)

    private def resolveQuery(q: HttpQueryParams | Seq[(String, String)]): HttpQueryParams =
        q match
            case q: HttpQueryParams @unchecked       => q
            case s: Seq[(String, String)] @unchecked => HttpQueryParams.init(s*)

    private def applyQuery(url: HttpUrl, query: HttpQueryParams): HttpUrl =
        if query.isEmpty then url
        else
            val qs = query.toQueryString
            val merged = url.rawQuery match
                case Present(existing) => s"$existing&$qs"
                case Absent            => qs
            url.copy(rawQuery = Present(merged))

    private def sendUrl[Out, A](
        url: HttpUrl,
        route: HttpRoute[Any, Out, Any],
        headers: HttpHeaders,
        query: HttpQueryParams
    )(
        extract: HttpResponse[Out] => A
    )(using Frame): A < (Async & Abort[HttpException]) =
        local.use { (client, config) =>
            val req = HttpRequest(route.method, applyQuery(url, query), headers, Record.empty)
            client.sendWithConfig(route, req, config)(extract)
        }

    private def sendUrl[B, Out, A](
        url: HttpUrl,
        route: HttpRoute["body" ~ B, Out, Any],
        body: B,
        headers: HttpHeaders,
        query: HttpQueryParams
    )(
        extract: HttpResponse[Out] => A
    )(using Frame): A < (Async & Abort[HttpException]) =
        local.use { (client, config) =>
            val req = HttpRequest(route.method, applyQuery(url, query), headers, Record.empty).addField("body", body)
            client.sendWithConfig(route, req, config)(extract)
        }

    // --- Private implementation ---

    private def initUnsafe(
        transport: kyo.internal.transport.Transport[?],
        maxConnectionsPerHost: Int,
        idleConnectionTimeout: Duration,
        defaultTlsConfig: kyo.HttpTlsConfig = kyo.HttpTlsConfig.default
    )(using AllowUnsafe, Frame): HttpClientBackend[?] =
        HttpClientBackend.init(transport, maxConnectionsPerHost, idleConnectionTimeout, defaultTlsConfig)

end HttpClient
