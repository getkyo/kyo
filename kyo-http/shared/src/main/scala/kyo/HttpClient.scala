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
  * ==Error handling on non-2xx responses==
  *
  * Body-only methods (`getText`, `getJson`, etc.) always fail with `HttpStatusException` on non-2xx status codes. `*Response` methods
  * (`getTextResponse`, `getJsonResponse`, etc.) also fail by default, but accept `failOnError = false` to return the raw response for
  * manual status handling. `head` and `options` follow the same convention.
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
    private val routeGetStream    = HttpRoute.getRaw("").response(_.bodyStream)
    private val routePostStream   = HttpRoute.postRaw("").request(_.bodyBinary).response(_.bodyStream)

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

    /** Fails with `HttpStatusException` on non-2xx status codes. */
    def getJson[A: Json](
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): A < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u =>
            sendUrlBody(u, HttpRoute.getJson[A](""), resolveHeaders(headers), resolveQuery(query))(_.fields.body)
        )

    /** Fails with `HttpStatusException` on non-2xx by default. Pass `failOnError = false` to receive the response for manual status
      * handling.
      */
    def getJsonResponse[A: Json](
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty,
        failOnError: Boolean = true
    )(using Frame): HttpResponse["body" ~ A] < (Async & Abort[HttpException]) =
        resolveUrl(url).map { u =>
            val route = HttpRoute.getJson[A]("")
            if failOnError then sendUrlBody(u, route, resolveHeaders(headers), resolveQuery(query))(identity)
            else sendUrl(u, route, resolveHeaders(headers), resolveQuery(query))(identity)
        }

    // --- POST ---

    /** Fails with `HttpStatusException` on non-2xx status codes. */
    def postJson[A: Json](using
        Frame
    )[B: Json](
        url: String | HttpUrl,
        body: B,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    ): A < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u =>
            sendUrlBody(u, HttpRoute.postJson[A, B](""), body, resolveHeaders(headers), resolveQuery(query))(_.fields.body)
        )

    /** Fails with `HttpStatusException` on non-2xx by default. Pass `failOnError = false` to receive the response for manual status
      * handling.
      */
    def postJsonResponse[A: Json](using
        Frame
    )[B: Json](
        url: String | HttpUrl,
        body: B,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty,
        failOnError: Boolean = true
    ): HttpResponse["body" ~ A] < (Async & Abort[HttpException]) =
        resolveUrl(url).map { u =>
            val route = HttpRoute.postJson[A, B]("")
            if failOnError then sendUrlBody(u, route, body, resolveHeaders(headers), resolveQuery(query))(identity)
            else sendUrl(u, route, body, resolveHeaders(headers), resolveQuery(query))(identity)
        }

    // --- PUT ---

    /** Fails with `HttpStatusException` on non-2xx status codes. */
    def putJson[A: Json](using
        Frame
    )[B: Json](
        url: String | HttpUrl,
        body: B,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    ): A < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u =>
            sendUrlBody(u, HttpRoute.putJson[A, B](""), body, resolveHeaders(headers), resolveQuery(query))(_.fields.body)
        )

    /** Fails with `HttpStatusException` on non-2xx by default. Pass `failOnError = false` to receive the response for manual status
      * handling.
      */
    def putJsonResponse[A: Json](using
        Frame
    )[B: Json](
        url: String | HttpUrl,
        body: B,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty,
        failOnError: Boolean = true
    ): HttpResponse["body" ~ A] < (Async & Abort[HttpException]) =
        resolveUrl(url).map { u =>
            val route = HttpRoute.putJson[A, B]("")
            if failOnError then sendUrlBody(u, route, body, resolveHeaders(headers), resolveQuery(query))(identity)
            else sendUrl(u, route, body, resolveHeaders(headers), resolveQuery(query))(identity)
        }

    // --- PATCH ---

    /** Fails with `HttpStatusException` on non-2xx status codes. */
    def patchJson[A: Json](using
        Frame
    )[B: Json](
        url: String | HttpUrl,
        body: B,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    ): A < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u =>
            sendUrlBody(u, HttpRoute.patchJson[A, B](""), body, resolveHeaders(headers), resolveQuery(query))(_.fields.body)
        )

    /** Fails with `HttpStatusException` on non-2xx by default. Pass `failOnError = false` to receive the response for manual status
      * handling.
      */
    def patchJsonResponse[A: Json](using
        Frame
    )[B: Json](
        url: String | HttpUrl,
        body: B,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty,
        failOnError: Boolean = true
    ): HttpResponse["body" ~ A] < (Async & Abort[HttpException]) =
        resolveUrl(url).map { u =>
            val route = HttpRoute.patchJson[A, B]("")
            if failOnError then sendUrlBody(u, route, body, resolveHeaders(headers), resolveQuery(query))(identity)
            else sendUrl(u, route, body, resolveHeaders(headers), resolveQuery(query))(identity)
        }

    // --- DELETE ---

    /** Fails with `HttpStatusException` on non-2xx status codes. */
    def deleteJson[A: Json](
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): A < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u =>
            sendUrlBody(u, HttpRoute.deleteJson[A](""), resolveHeaders(headers), resolveQuery(query))(_.fields.body)
        )

    /** Fails with `HttpStatusException` on non-2xx by default. Pass `failOnError = false` to receive the response for manual status
      * handling.
      */
    def deleteJsonResponse[A: Json](
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty,
        failOnError: Boolean = true
    )(using Frame): HttpResponse["body" ~ A] < (Async & Abort[HttpException]) =
        resolveUrl(url).map { u =>
            val route = HttpRoute.deleteJson[A]("")
            if failOnError then sendUrlBody(u, route, resolveHeaders(headers), resolveQuery(query))(identity)
            else sendUrl(u, route, resolveHeaders(headers), resolveQuery(query))(identity)
        }

    // ==================== Text methods ====================

    // --- GET ---

    /** Fails with `HttpStatusException` on non-2xx status codes. */
    def getText(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): String < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u => sendUrlBody(u, routeGetText, resolveHeaders(headers), resolveQuery(query))(_.fields.body))

    /** Fails with `HttpStatusException` on non-2xx by default. Pass `failOnError = false` to receive the response for manual status
      * handling.
      */
    def getTextResponse(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty,
        failOnError: Boolean = true
    )(using Frame): HttpResponse["body" ~ String] < (Async & Abort[HttpException]) =
        resolveUrl(url).map { u =>
            if failOnError then sendUrlBody(u, routeGetText, resolveHeaders(headers), resolveQuery(query))(identity)
            else sendUrl(u, routeGetText, resolveHeaders(headers), resolveQuery(query))(identity)
        }

    // --- POST ---

    /** Fails with `HttpStatusException` on non-2xx status codes. */
    def postText(
        url: String | HttpUrl,
        body: String,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): String < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u =>
            sendUrlBody(u, routePostText, body, resolveHeaders(headers), resolveQuery(query))(_.fields.body)
        )

    /** Fails with `HttpStatusException` on non-2xx by default. Pass `failOnError = false` to receive the response for manual status
      * handling.
      */
    def postTextResponse(
        url: String | HttpUrl,
        body: String,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty,
        failOnError: Boolean = true
    )(using Frame): HttpResponse["body" ~ String] < (Async & Abort[HttpException]) =
        resolveUrl(url).map { u =>
            if failOnError then sendUrlBody(u, routePostText, body, resolveHeaders(headers), resolveQuery(query))(identity)
            else sendUrl(u, routePostText, body, resolveHeaders(headers), resolveQuery(query))(identity)
        }

    // --- PUT ---

    /** Fails with `HttpStatusException` on non-2xx status codes. */
    def putText(
        url: String | HttpUrl,
        body: String,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): String < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u =>
            sendUrlBody(u, routePutText, body, resolveHeaders(headers), resolveQuery(query))(_.fields.body)
        )

    /** Fails with `HttpStatusException` on non-2xx by default. Pass `failOnError = false` to receive the response for manual status
      * handling.
      */
    def putTextResponse(
        url: String | HttpUrl,
        body: String,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty,
        failOnError: Boolean = true
    )(using Frame): HttpResponse["body" ~ String] < (Async & Abort[HttpException]) =
        resolveUrl(url).map { u =>
            if failOnError then sendUrlBody(u, routePutText, body, resolveHeaders(headers), resolveQuery(query))(identity)
            else sendUrl(u, routePutText, body, resolveHeaders(headers), resolveQuery(query))(identity)
        }

    // --- PATCH ---

    /** Fails with `HttpStatusException` on non-2xx status codes. */
    def patchText(
        url: String | HttpUrl,
        body: String,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): String < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u =>
            sendUrlBody(u, routePatchText, body, resolveHeaders(headers), resolveQuery(query))(_.fields.body)
        )

    /** Fails with `HttpStatusException` on non-2xx by default. Pass `failOnError = false` to receive the response for manual status
      * handling.
      */
    def patchTextResponse(
        url: String | HttpUrl,
        body: String,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty,
        failOnError: Boolean = true
    )(using Frame): HttpResponse["body" ~ String] < (Async & Abort[HttpException]) =
        resolveUrl(url).map { u =>
            if failOnError then sendUrlBody(u, routePatchText, body, resolveHeaders(headers), resolveQuery(query))(identity)
            else sendUrl(u, routePatchText, body, resolveHeaders(headers), resolveQuery(query))(identity)
        }

    // --- DELETE ---

    /** Fails with `HttpStatusException` on non-2xx status codes. */
    def deleteText(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): String < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u =>
            sendUrlBody(u, routeDeleteText, resolveHeaders(headers), resolveQuery(query))(_.fields.body)
        )

    /** Fails with `HttpStatusException` on non-2xx by default. Pass `failOnError = false` to receive the response for manual status
      * handling.
      */
    def deleteTextResponse(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty,
        failOnError: Boolean = true
    )(using Frame): HttpResponse["body" ~ String] < (Async & Abort[HttpException]) =
        resolveUrl(url).map { u =>
            if failOnError then sendUrlBody(u, routeDeleteText, resolveHeaders(headers), resolveQuery(query))(identity)
            else sendUrl(u, routeDeleteText, resolveHeaders(headers), resolveQuery(query))(identity)
        }

    // ==================== Binary methods ====================

    // --- GET ---

    /** Fails with `HttpStatusException` on non-2xx status codes. */
    def getBinary(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u =>
            sendUrlBody(u, routeGetBinary, resolveHeaders(headers), resolveQuery(query))(_.fields.body)
        )

    /** Fails with `HttpStatusException` on non-2xx by default. Pass `failOnError = false` to receive the response for manual status
      * handling.
      */
    def getBinaryResponse(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty,
        failOnError: Boolean = true
    )(using Frame): HttpResponse["body" ~ Span[Byte]] < (Async & Abort[HttpException]) =
        resolveUrl(url).map { u =>
            if failOnError then sendUrlBody(u, routeGetBinary, resolveHeaders(headers), resolveQuery(query))(identity)
            else sendUrl(u, routeGetBinary, resolveHeaders(headers), resolveQuery(query))(identity)
        }

    // --- POST ---

    /** Fails with `HttpStatusException` on non-2xx status codes. */
    def postBinary(
        url: String | HttpUrl,
        body: Span[Byte],
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u =>
            sendUrlBody(u, routePostBinary, body, resolveHeaders(headers), resolveQuery(query))(_.fields.body)
        )

    /** Fails with `HttpStatusException` on non-2xx by default. Pass `failOnError = false` to receive the response for manual status
      * handling.
      */
    def postBinaryResponse(
        url: String | HttpUrl,
        body: Span[Byte],
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty,
        failOnError: Boolean = true
    )(using Frame): HttpResponse["body" ~ Span[Byte]] < (Async & Abort[HttpException]) =
        resolveUrl(url).map { u =>
            if failOnError then sendUrlBody(u, routePostBinary, body, resolveHeaders(headers), resolveQuery(query))(identity)
            else sendUrl(u, routePostBinary, body, resolveHeaders(headers), resolveQuery(query))(identity)
        }

    // --- PUT ---

    /** Fails with `HttpStatusException` on non-2xx status codes. */
    def putBinary(
        url: String | HttpUrl,
        body: Span[Byte],
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u =>
            sendUrlBody(u, routePutBinary, body, resolveHeaders(headers), resolveQuery(query))(_.fields.body)
        )

    /** Fails with `HttpStatusException` on non-2xx by default. Pass `failOnError = false` to receive the response for manual status
      * handling.
      */
    def putBinaryResponse(
        url: String | HttpUrl,
        body: Span[Byte],
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty,
        failOnError: Boolean = true
    )(using Frame): HttpResponse["body" ~ Span[Byte]] < (Async & Abort[HttpException]) =
        resolveUrl(url).map { u =>
            if failOnError then sendUrlBody(u, routePutBinary, body, resolveHeaders(headers), resolveQuery(query))(identity)
            else sendUrl(u, routePutBinary, body, resolveHeaders(headers), resolveQuery(query))(identity)
        }

    // --- PATCH ---

    /** Fails with `HttpStatusException` on non-2xx status codes. */
    def patchBinary(
        url: String | HttpUrl,
        body: Span[Byte],
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u =>
            sendUrlBody(u, routePatchBinary, body, resolveHeaders(headers), resolveQuery(query))(_.fields.body)
        )

    /** Fails with `HttpStatusException` on non-2xx by default. Pass `failOnError = false` to receive the response for manual status
      * handling.
      */
    def patchBinaryResponse(
        url: String | HttpUrl,
        body: Span[Byte],
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty,
        failOnError: Boolean = true
    )(using Frame): HttpResponse["body" ~ Span[Byte]] < (Async & Abort[HttpException]) =
        resolveUrl(url).map { u =>
            if failOnError then sendUrlBody(u, routePatchBinary, body, resolveHeaders(headers), resolveQuery(query))(identity)
            else sendUrl(u, routePatchBinary, body, resolveHeaders(headers), resolveQuery(query))(identity)
        }

    // --- DELETE ---

    /** Fails with `HttpStatusException` on non-2xx status codes. */
    def deleteBinary(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        resolveUrl(url).map(u =>
            sendUrlBody(u, routeDeleteBinary, resolveHeaders(headers), resolveQuery(query))(_.fields.body)
        )

    /** Fails with `HttpStatusException` on non-2xx by default. Pass `failOnError = false` to receive the response for manual status
      * handling.
      */
    def deleteBinaryResponse(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty,
        failOnError: Boolean = true
    )(using Frame): HttpResponse["body" ~ Span[Byte]] < (Async & Abort[HttpException]) =
        resolveUrl(url).map { u =>
            if failOnError then sendUrlBody(u, routeDeleteBinary, resolveHeaders(headers), resolveQuery(query))(identity)
            else sendUrl(u, routeDeleteBinary, resolveHeaders(headers), resolveQuery(query))(identity)
        }

    // ==================== Streaming methods ====================

    // --- SSE JSON ---

    def getSseJson[V: Json: Tag](
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame, Tag[Emit[Chunk[HttpSseEvent[V]]]]): Stream[HttpSseEvent[V], Async & Abort[HttpException]] =
        Stream(resolveUrl(url).map(u =>
            sendUrlBody(
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
            sendUrlBody(
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
            sendUrlBody(
                u,
                HttpRoute.getRaw("").response(_.bodyNdjson[V]),
                resolveHeaders(headers),
                resolveQuery(query)
            )(_.fields.body).map(_.emit)
        ))

    // --- Byte Stream ---

    /** Streams the response body as raw byte chunks. Fails with HttpStatusException on non-2xx. */
    def getStreamBytes(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame, Tag[Emit[Chunk[Span[Byte]]]]): Stream[Span[Byte], Async & Abort[HttpException]] =
        Stream(resolveUrl(url).map(u =>
            sendUrlBody(
                u,
                routeGetStream,
                resolveHeaders(headers),
                resolveQuery(query)
            )(_.fields.body).map(_.emit)
        ))

    /** Streams the response body as raw byte chunks. Fails with HttpStatusException on non-2xx. */
    def postStreamBytes(
        url: String | HttpUrl,
        body: Span[Byte],
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty
    )(using Frame, Tag[Emit[Chunk[Span[Byte]]]]): Stream[Span[Byte], Async & Abort[HttpException]] =
        Stream(resolveUrl(url).map(u =>
            sendUrlBody(
                u,
                routePostStream,
                body,
                resolveHeaders(headers),
                resolveQuery(query)
            )(_.fields.body).map(_.emit)
        ))

    // ==================== HEAD methods ====================

    /** Fails with `HttpStatusException` on non-2xx by default. Pass `failOnError = false` to receive the response for manual status
      * handling.
      */
    def head(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty,
        failOnError: Boolean = true
    )(using Frame): HttpResponse[Any] < (Async & Abort[HttpException]) =
        resolveUrl(url).map { u =>
            if failOnError then sendUrlBody(u, routeHeadRaw, resolveHeaders(headers), resolveQuery(query))(identity)
            else sendUrl(u, routeHeadRaw, resolveHeaders(headers), resolveQuery(query))(identity)
        }

    // ==================== OPTIONS methods ====================

    /** Fails with `HttpStatusException` on non-2xx by default. Pass `failOnError = false` to receive the response for manual status
      * handling.
      */
    def options(
        url: String | HttpUrl,
        headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty,
        query: HttpQueryParams | Seq[(String, String)] = HttpQueryParams.empty,
        failOnError: Boolean = true
    )(using Frame): HttpResponse[Any] < (Async & Abort[HttpException]) =
        resolveUrl(url).map { u =>
            if failOnError then sendUrlBody(u, routeOptionsRaw, resolveHeaders(headers), resolveQuery(query))(identity)
            else sendUrl(u, routeOptionsRaw, resolveHeaders(headers), resolveQuery(query))(identity)
        }

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

    /** Sends a request and extracts the body, failing with HttpStatusException on non-2xx. Used by body-only convenience methods (getText,
      * getJson, etc.). Response methods use sendUrl directly with identity — no status check.
      */
    private def sendUrlBody[Out, A](
        url: HttpUrl,
        route: HttpRoute[Any, Out, Any],
        headers: HttpHeaders,
        query: HttpQueryParams
    )(
        extract: HttpResponse[Out] => A
    )(using Frame): A < (Async & Abort[HttpException]) =
        local.use { (client, config) =>
            val req = HttpRequest(route.method, applyQuery(url, query), headers, Record.empty)
            client.sendWithConfig(route, req, config) { resp =>
                if !resp.status.isSuccess then
                    Abort.fail(HttpStatusException(resp.status, route.method.name, url.baseUrl))
                else extract(resp)
            }
        }

    private def sendUrlBody[B, Out, A](
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
            client.sendWithConfig(route, req, config) { resp =>
                if !resp.status.isSuccess then
                    Abort.fail(HttpStatusException(resp.status, route.method.name, url.baseUrl))
                else extract(resp)
            }
        }

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
