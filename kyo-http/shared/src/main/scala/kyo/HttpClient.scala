package kyo

import kyo.*
import kyo.internal.ConnectionPool
import kyo.internal.HttpPlatformBackend

/** HTTP client with connection pooling, retries, redirects, and typed request/response handling.
  *
  * HttpClient manages a per-host connection pool shared across all fibers. Convenience methods like `getJson`, `postText`, and `getSseJson`
  * create an HttpRoute internally and delegate to the typed send path — routes are the underlying abstraction even when users don't
  * interact with them directly. For full control, use `sendWith` with an explicit route.
  *
  * The client and its configuration are stored in fiber-local storage via `Local`. All methods on the companion object (`getJson`,
  * `postJson`, etc.) use a shared client. To scope a custom client or configuration to a block of code:
  *   - `HttpClient.let(client) { ... }` — use a specific client instance
  *   - `HttpClient.withConfig(_.timeout(10.seconds)) { ... }` — apply config transformations (stacks with current config)
  *   - `HttpClient.withConfig(config) { ... }` — replace the config entirely
  *
  * The request lifecycle chains through `retryWith → redirectsWith → timeoutWith → poolWith`, each layer wrapping the next. Retries only
  * activate when a `Schedule` is configured. Redirects follow up to `maxRedirects` hops, changing the method to GET for 303 See Other per
  * RFC 9110. Timeouts cancel the entire operation including any in-progress retries.
  *
  * @see
  *   [[kyo.HttpRoute]] The endpoint contract that drives typed serialization
  * @see
  *   [[kyo.HttpClientConfig]] Configuration for timeouts, retries, redirects, and base URL
  * @see
  *   [[kyo.HttpException]] The error hierarchy for client failures
  * @see
  *   [[kyo.HttpBackend.Client]] The platform-specific backend trait
  */
final class HttpClient private (
    backend: HttpBackend.Client,
    pool: ConnectionPool[backend.Connection],
    maxConnectionsPerHost: Int,
    clientFrame: Frame
):

    import ConnectionPool.HostKey

    /** Sends a typed request using the given route and processes the response with `f`. Applies the current fiber-local configuration (base
      * URL, timeout, retries, redirects) and the route's client-side filters.
      */
    def sendWith[In, Out, A](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In]
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        HttpClient.local.use { (_, config) =>
            sendWithConfig(route, request, config)(f)
        }

    private[kyo] def sendWithConfig[In, Out, A](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClientConfig
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        val resolved = config.baseUrl match
            case Present(base) if request.url.scheme.isEmpty =>
                request.copy(url = HttpUrl(base.scheme, base.host, base.port, request.url.path, request.url.rawQuery))
            case _ => request
        retryWith(route, resolved, config)(f)
    end sendWithConfig

    private def retryWith[In, Out, A](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClientConfig
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        config.retrySchedule match
            case Present(schedule) =>
                def loop(remaining: Schedule): A < (Async & Abort[HttpException]) =
                    redirectsWith(route, request, config) { res =>
                        if !config.retryOn(res.status) then f(res)
                        else
                            Clock.nowWith { now =>
                                remaining.next(now) match
                                    case Present((delay, nextSchedule)) =>
                                        Async.delay(delay)(loop(nextSchedule))
                                    case Absent => f(res)
                            }
                    }
                loop(schedule)
            case Absent =>
                redirectsWith(route, request, config)(f)
    end retryWith

    private def redirectsWith[In, Out, A](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClientConfig
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        if config.followRedirects then
            def loop(req: HttpRequest[In], count: Int, chain: Chunk[String]): A < (Async & Abort[HttpException]) =
                timeoutWith(route, req, config) { res =>
                    if !res.status.isRedirect then f(res)
                    else if count >= config.maxRedirects then
                        Abort.fail(HttpRedirectLoopException(count, req.method.name, req.url.baseUrl, chain))
                    else
                        res.headers.get("Location") match
                            case Present(location) =>
                                HttpUrl.parse(location) match
                                    case Result.Success(newUrl) =>
                                        // Preserve original host/port/scheme for relative redirects
                                        val resolved =
                                            if newUrl.host.nonEmpty then newUrl
                                            else newUrl.copy(scheme = req.url.scheme, host = req.url.host, port = req.url.port)
                                        // RFC 9110 §15.4.4: 303 See Other requires changing method to GET
                                        val nextReq =
                                            if res.status == HttpStatus.SeeOther then req.copy(url = resolved, method = HttpMethod.GET)
                                            else req.copy(url = resolved)
                                        loop(nextReq, count + 1, chain.append(location))
                                    case Result.Failure(err) =>
                                        Abort.fail(err)
                            case Absent => f(res)
                }
            loop(request, 0, Chunk.empty)
        else
            timeoutWith(route, request, config)(f)

    private def timeoutWith[In, Out, A](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClientConfig
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        config.timeout match
            case Present(duration) =>
                Async.timeoutWithError(duration, Result.Failure(HttpTimeoutException(duration, request.method.name, request.url.baseUrl)))(
                    poolWith(route, request, config)(f)
                )
            case Absent =>
                poolWith(route, request, config)(f)

    private def poolWith[In, Out, A](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClientConfig
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        // Client-side filters (e.g. basicAuth, bearerAuth) are Passthrough — they transform the request
        // and forward next's result unchanged. We cast so that next's return type aligns with A
        // (the result of sendWith(f)), preserving connection-holding semantics inside Sync.ensure.
        val filter = route.filter.asInstanceOf[HttpFilter[Any, In, Out, Out, Nothing]]
        filter[In, Out, HttpException](
            request,
            (filteredReq: HttpRequest[In]) =>
                Sync.Unsafe.defer {
                    val key = HostKey(filteredReq.url.host, filteredReq.url.port)
                    def onReleaseUnsafe(conn: backend.Connection): Maybe[Result.Error[Any]] => Unit =
                        error =>
                            pool.untrack(key, conn)
                            error match
                                case Absent => pool.release(key, conn)
                                case _      => pool.discard(conn)
                    end onReleaseUnsafe
                    pool.poll(key) match
                        case Present(conn) =>
                            pool.track(key, conn)
                            backend.sendWith(conn, route, filteredReq, onReleaseUnsafe(conn))(f)
                        case _ =>
                            if pool.tryReserve(key) then
                                Sync.ensure(pool.unreserve(key)) {
                                    backend.connectWith(
                                        filteredReq.url.host,
                                        filteredReq.url.port,
                                        filteredReq.url.ssl,
                                        config.connectTimeout
                                    ) {
                                        conn =>
                                            pool.track(key, conn)
                                            backend.sendWith(conn, route, filteredReq, onReleaseUnsafe(conn))(f)
                                    }
                                }
                            else
                                Abort.fail(HttpPoolExhaustedException(
                                    filteredReq.url.host,
                                    filteredReq.url.port,
                                    maxConnectionsPerHost,
                                    clientFrame
                                ))
                    end match
                }.asInstanceOf[HttpResponse[Out] < (Async & Abort[HttpException | HttpResponse.Halt])]
        ).asInstanceOf[A < (Async & Abort[HttpException])]
    end poolWith

    def close(gracePeriod: Duration)(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            val conns = pool.close()
            Kyo.foreachDiscard(conns)(conn => backend.close(conn, gracePeriod))
        }
    end close
    def close(using Frame): Unit < Async    = close(30.seconds)
    def closeNow(using Frame): Unit < Async = close(Duration.Zero)

end HttpClient

object HttpClient:

    // --- Default client ---

    private lazy val defaultClient: HttpClient =
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        val backend = HttpPlatformBackend.client
        val pool = ConnectionPool.init[backend.Connection](
            100,
            60.seconds,
            conn => backend.isAlive(conn),
            conn => backend.closeNowUnsafe(conn)
        )
        new HttpClient(backend, pool, 100, Frame.internal)
    end defaultClient

    private val local: Local[(HttpClient, HttpClientConfig)] = Local.init((defaultClient, HttpClientConfig()))

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
        backend: HttpBackend.Client,
        maxConnectionsPerHost: Int = 100,
        idleConnectionTimeout: Duration = 60.seconds
    )(using Frame): HttpClient < (Async & Scope) =
        Scope.acquireRelease(initUnscoped(backend, maxConnectionsPerHost, idleConnectionTimeout))(_.closeNow)

    /** Creates a client with its own connection pool that must be closed explicitly via `close()`. Prefer `init` with Scope-based lifecycle
      * unless you need manual control.
      */
    def initUnscoped(
        backend: HttpBackend.Client,
        maxConnectionsPerHost: Int = 100,
        idleConnectionTimeout: Duration = 60.seconds
    )(using frame: Frame): HttpClient < Sync =
        require(maxConnectionsPerHost > 0, s"maxConnectionsPerHost must be positive: $maxConnectionsPerHost")
        require(idleConnectionTimeout > Duration.Zero, s"idleConnectionTimeout must be positive: $idleConnectionTimeout")
        Sync.Unsafe.defer {
            val pool = ConnectionPool.init[backend.Connection](
                maxConnectionsPerHost,
                idleConnectionTimeout,
                conn => backend.isAlive(conn),
                conn => backend.closeNowUnsafe(conn)
            )
            new HttpClient(backend, pool, maxConnectionsPerHost, frame)
        }
    end initUnscoped

    // ==================== JSON methods ====================

    def getJson[A: Json](url: String)(using Frame): A < (Async & Abort[HttpException]) =
        parseAndSend(url, HttpRoute.getJson[A](""))(_.fields.body)
    def getJson[A: Json](url: HttpUrl)(using Frame): A < (Async & Abort[HttpException]) =
        sendUrl(url, HttpRoute.getJson[A](""))(_.fields.body)

    def postJson[A: Json](using Frame)[B: Json](url: String, body: B): A < (Async & Abort[HttpException]) =
        parseAndSend(url, HttpRoute.postJson[A, B](""), body)(_.fields.body)
    def postJson[A: Json](using Frame)[B: Json](url: HttpUrl, body: B): A < (Async & Abort[HttpException]) =
        sendUrl(url, HttpRoute.postJson[A, B](""), body)(_.fields.body)

    def putJson[A: Json](using Frame)[B: Json](url: String, body: B): A < (Async & Abort[HttpException]) =
        parseAndSend(url, HttpRoute.putJson[A, B](""), body)(_.fields.body)
    def putJson[A: Json](using Frame)[B: Json](url: HttpUrl, body: B): A < (Async & Abort[HttpException]) =
        sendUrl(url, HttpRoute.putJson[A, B](""), body)(_.fields.body)

    def patchJson[A: Json](using Frame)[B: Json](url: String, body: B): A < (Async & Abort[HttpException]) =
        parseAndSend(url, HttpRoute.patchJson[A, B](""), body)(_.fields.body)
    def patchJson[A: Json](using Frame)[B: Json](url: HttpUrl, body: B): A < (Async & Abort[HttpException]) =
        sendUrl(url, HttpRoute.patchJson[A, B](""), body)(_.fields.body)

    def deleteJson[A: Json](url: String)(using Frame): A < (Async & Abort[HttpException]) =
        parseAndSend(url, HttpRoute.deleteJson[A](""))(_.fields.body)
    def deleteJson[A: Json](url: HttpUrl)(using Frame): A < (Async & Abort[HttpException]) =
        sendUrl(url, HttpRoute.deleteJson[A](""))(_.fields.body)

    // ==================== Text methods ====================

    def getText(url: String)(using Frame): String < (Async & Abort[HttpException]) =
        parseAndSend(url, HttpRoute.getText(""))(_.fields.body)
    def getText(url: HttpUrl)(using Frame): String < (Async & Abort[HttpException]) =
        sendUrl(url, HttpRoute.getText(""))(_.fields.body)

    def postText(url: String, body: String)(using Frame): String < (Async & Abort[HttpException]) =
        parseAndSend(url, HttpRoute.postText(""), body)(_.fields.body)
    def postText(url: HttpUrl, body: String)(using Frame): String < (Async & Abort[HttpException]) =
        sendUrl(url, HttpRoute.postText(""), body)(_.fields.body)

    def putText(url: String, body: String)(using Frame): String < (Async & Abort[HttpException]) =
        parseAndSend(url, HttpRoute.putText(""), body)(_.fields.body)
    def putText(url: HttpUrl, body: String)(using Frame): String < (Async & Abort[HttpException]) =
        sendUrl(url, HttpRoute.putText(""), body)(_.fields.body)

    def patchText(url: String, body: String)(using Frame): String < (Async & Abort[HttpException]) =
        parseAndSend(url, HttpRoute.patchText(""), body)(_.fields.body)
    def patchText(url: HttpUrl, body: String)(using Frame): String < (Async & Abort[HttpException]) =
        sendUrl(url, HttpRoute.patchText(""), body)(_.fields.body)

    def deleteText(url: String)(using Frame): String < (Async & Abort[HttpException]) =
        parseAndSend(url, HttpRoute.deleteText(""))(_.fields.body)
    def deleteText(url: HttpUrl)(using Frame): String < (Async & Abort[HttpException]) =
        sendUrl(url, HttpRoute.deleteText(""))(_.fields.body)

    // ==================== Binary methods ====================

    def getBinary(url: String)(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        parseAndSend(url, HttpRoute.getBinary(""))(_.fields.body)
    def getBinary(url: HttpUrl)(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        sendUrl(url, HttpRoute.getBinary(""))(_.fields.body)

    def postBinary(url: String, body: Span[Byte])(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        parseAndSend(url, HttpRoute.postBinary(""), body)(_.fields.body)
    def postBinary(url: HttpUrl, body: Span[Byte])(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        sendUrl(url, HttpRoute.postBinary(""), body)(_.fields.body)

    def putBinary(url: String, body: Span[Byte])(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        parseAndSend(url, HttpRoute.putBinary(""), body)(_.fields.body)
    def putBinary(url: HttpUrl, body: Span[Byte])(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        sendUrl(url, HttpRoute.putBinary(""), body)(_.fields.body)

    def patchBinary(url: String, body: Span[Byte])(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        parseAndSend(url, HttpRoute.patchBinary(""), body)(_.fields.body)
    def patchBinary(url: HttpUrl, body: Span[Byte])(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        sendUrl(url, HttpRoute.patchBinary(""), body)(_.fields.body)

    def deleteBinary(url: String)(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        parseAndSend(url, HttpRoute.deleteBinary(""))(_.fields.body)
    def deleteBinary(url: HttpUrl)(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        sendUrl(url, HttpRoute.deleteBinary(""))(_.fields.body)

    // ==================== Streaming methods ====================

    def getSseJson[V: Json: Tag](url: String)(using
        Frame,
        Tag[Emit[Chunk[HttpSseEvent[V]]]]
    ): Stream[HttpSseEvent[V], Async & Abort[HttpException]] =
        Stream(parseAndSend(url, HttpRoute.getRaw("").response(_.bodySseJson[V]))(_.fields.body).map(_.emit))
    def getSseJson[V: Json: Tag](url: HttpUrl)(using
        Frame,
        Tag[Emit[Chunk[HttpSseEvent[V]]]]
    ): Stream[HttpSseEvent[V], Async & Abort[HttpException]] =
        Stream(sendUrl(url, HttpRoute.getRaw("").response(_.bodySseJson[V]))(_.fields.body).map(_.emit))

    def getSseText(url: String)(using
        Frame,
        Tag[Emit[Chunk[HttpSseEvent[String]]]]
    ): Stream[HttpSseEvent[String], Async & Abort[HttpException]] =
        Stream(parseAndSend(url, HttpRoute.getRaw("").response(_.bodySseText))(_.fields.body).map(_.emit))
    def getSseText(url: HttpUrl)(using
        Frame,
        Tag[Emit[Chunk[HttpSseEvent[String]]]]
    ): Stream[HttpSseEvent[String], Async & Abort[HttpException]] =
        Stream(sendUrl(url, HttpRoute.getRaw("").response(_.bodySseText))(_.fields.body).map(_.emit))

    def getNdJson[V: Json: Tag](url: String)(using
        Frame,
        Tag[Emit[Chunk[V]]]
    ): Stream[V, Async & Abort[HttpException]] =
        Stream(parseAndSend(url, HttpRoute.getRaw("").response(_.bodyNdjson[V]))(_.fields.body).map(_.emit))
    def getNdJson[V: Json: Tag](url: HttpUrl)(using
        Frame,
        Tag[Emit[Chunk[V]]]
    ): Stream[V, Async & Abort[HttpException]] =
        Stream(sendUrl(url, HttpRoute.getRaw("").response(_.bodyNdjson[V]))(_.fields.body).map(_.emit))

    // --- Internal ---

    private def sendUrl[Out, A](
        url: HttpUrl,
        route: HttpRoute[Any, Out, Any]
    )(
        extract: HttpResponse[Out] => A
    )(using Frame): A < (Async & Abort[HttpException]) =
        local.use { (client, config) =>
            client.sendWithConfig(route, HttpRequest(route.method, url), config)(extract)
        }

    private def sendUrl[B, Out, A](
        url: HttpUrl,
        route: HttpRoute["body" ~ B, Out, Any],
        body: B
    )(
        extract: HttpResponse[Out] => A
    )(using Frame): A < (Async & Abort[HttpException]) =
        local.use { (client, config) =>
            client.sendWithConfig(route, HttpRequest(route.method, url).addField("body", body), config)(extract)
        }

    private def parseAndSend[Out, A](
        rawUrl: String,
        route: HttpRoute[Any, Out, Any]
    )(
        extract: HttpResponse[Out] => A
    )(using Frame): A < (Async & Abort[HttpException]) =
        HttpUrl.parse(rawUrl) match
            case Result.Success(url) => sendUrl(url, route)(extract)
            case Result.Failure(err) => Abort.fail(err)

    private def parseAndSend[B, Out, A](
        rawUrl: String,
        route: HttpRoute["body" ~ B, Out, Any],
        body: B
    )(
        extract: HttpResponse[Out] => A
    )(using Frame): A < (Async & Abort[HttpException]) =
        HttpUrl.parse(rawUrl) match
            case Result.Success(url) => sendUrl(url, route, body)(extract)
            case Result.Failure(err) => Abort.fail(err)

end HttpClient
