package kyo

import kyo.*
import kyo.Record2.~
import kyo.internal.ConnectionPool
import kyo.internal.HttpPlatformBackend
import kyo.seconds

final class HttpClient private (
    backend: HttpBackend.Client,
    pool: ConnectionPool[backend.Connection],
    maxConnectionsPerHost: Int
):

    import ConnectionPool.HostKey

    def sendWith[In, Out, A, S](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In]
    )(
        f: HttpResponse[Out] => A < S
    )(using Frame): A < (S & Async & Abort[HttpError]) =
        HttpClient.local.use { (_, config) =>
            sendWithConfig(route, request, config)(f)
        }

    private[kyo] def sendWithConfig[In, Out, A, S](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClient.Config
    )(
        f: HttpResponse[Out] => A < S
    )(using Frame): A < (S & Async & Abort[HttpError]) =
        val resolved = config.baseUrl match
            case Present(base) if request.url.scheme.isEmpty =>
                request.copy(url = HttpUrl(base.scheme, base.host, base.port, request.url.path, request.url.rawQuery))
            case _ => request
        retryWith(route, resolved, config)(f)
    end sendWithConfig

    private def retryWith[In, Out, A, S](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClient.Config
    )(
        f: HttpResponse[Out] => A < S
    )(using Frame): A < (S & Async & Abort[HttpError]) =
        config.retrySchedule match
            case Present(schedule) =>
                def loop(remaining: Schedule): A < (S & Async & Abort[HttpError]) =
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

    private def redirectsWith[In, Out, A, S](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClient.Config
    )(
        f: HttpResponse[Out] => A < S
    )(using Frame): A < (S & Async & Abort[HttpError]) =
        if config.followRedirects then
            def loop(req: HttpRequest[In], count: Int): A < (S & Async & Abort[HttpError]) =
                timeoutWith(route, req, config) { res =>
                    if !res.status.isRedirect then f(res)
                    else if count >= config.maxRedirects then Abort.fail(HttpError.TooManyRedirects(count))
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
                                        loop(nextReq, count + 1)
                                    case Result.Failure(err) =>
                                        Abort.fail(err)
                            case Absent => f(res)
                }
            loop(request, 0)
        else
            timeoutWith(route, request, config)(f)

    private def timeoutWith[In, Out, A, S](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClient.Config
    )(
        f: HttpResponse[Out] => A < S
    )(using Frame): A < (S & Async & Abort[HttpError]) =
        config.timeout match
            case Present(duration) =>
                Async.timeoutWithError(duration, Result.Failure(HttpError.TimeoutError(duration)))(
                    poolWith(route, request, config)(identity)
                ).map(f)
            case Absent =>
                poolWith(route, request, config)(f)

    private def poolWith[In, Out, A, S](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClient.Config
    )(
        f: HttpResponse[Out] => A < S
    )(using Frame): A < (S & Async & Abort[HttpError]) =
        // Client-side filters (e.g. basicAuth, bearerAuth) are Passthrough — they transform the request
        // and forward next's result unchanged. We cast so that next's return type aligns with A
        // (the result of sendWith(f)), preserving connection-holding semantics inside Sync.ensure.
        val filter = route.filter.asInstanceOf[HttpFilter[Any, In, Out, Out, Nothing]]
        filter[In, Out, HttpError](
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
                                Abort.fail(HttpError.ConnectionPoolExhausted(
                                    filteredReq.url.host,
                                    filteredReq.url.port,
                                    maxConnectionsPerHost
                                ))
                    end match
                }.asInstanceOf[HttpResponse[Out] < (Async & Abort[HttpError | HttpResponse.Halt])]
        ).asInstanceOf[A < (S & Async & Abort[HttpError])]
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

    case class Config(
        baseUrl: Maybe[HttpUrl] = Absent,
        timeout: Maybe[Duration] = Present(5.seconds),
        connectTimeout: Maybe[Duration] = Absent,
        followRedirects: Boolean = true,
        maxRedirects: Int = 10,
        retrySchedule: Maybe[Schedule] = Absent,
        retryOn: HttpStatus => Boolean = _.isServerError
    ):
        require(maxRedirects >= 0, s"maxRedirects must be non-negative: $maxRedirects")
        timeout.foreach(d => require(d > Duration.Zero, s"timeout must be positive: $d"))
        connectTimeout.foreach(d => require(d > Duration.Zero, s"connectTimeout must be positive: $d"))

        def baseUrl(url: String)(using Frame): Config = copy(baseUrl = Present(HttpUrl.parse(url).getOrThrow))
        def baseUrl(url: HttpUrl): Config             = copy(baseUrl = Present(url))
        def timeout(d: Duration): Config              = copy(timeout = Present(d))
        def connectTimeout(d: Duration): Config       = copy(connectTimeout = Present(d))
        def followRedirects(v: Boolean): Config       = copy(followRedirects = v)
        def maxRedirects(v: Int): Config              = copy(maxRedirects = v)
        def retry(schedule: Schedule): Config         = copy(retrySchedule = Present(schedule))
        def retryOn(f: HttpStatus => Boolean): Config = copy(retryOn = f)
    end Config

    // --- Default client ---

    private lazy val defaultClient: HttpClient =
        import AllowUnsafe.embrace.danger
        val backend = HttpPlatformBackend.client
        val pool = ConnectionPool.init[backend.Connection](
            100,
            60.seconds,
            conn => backend.isAlive(conn),
            conn => backend.closeNowUnsafe(conn)
        )
        new HttpClient(backend, pool, 100)
    end defaultClient

    private val local: Local[(HttpClient, Config)] = Local.init((defaultClient, Config()))

    // --- Context management ---

    /** Sets the client for the given computation. */
    def let[A, S](client: HttpClient)(v: A < S)(using Frame): A < S =
        local.use { (_, config) => local.let((client, config))(v) }

    /** Accesses the current client. */
    def use[A, S](f: HttpClient => A < S)(using Frame): A < S =
        local.use { (client, _) => f(client) }

    /** Transforms the current client for the given computation. */
    def update[A, S](f: HttpClient => HttpClient)(v: A < S)(using Frame): A < S =
        local.use { (client, config) => local.let((f(client), config))(v) }

    /** Applies a config transformation for the given computation (stacks with current config). */
    def withConfig[A, S](f: Config => Config)(v: A < S)(using Frame): A < S =
        local.use { (client, config) => local.let((client, f(config)))(v) }

    /** Sets the config for the given computation. */
    def withConfig[A, S](config: Config)(v: A < S)(using Frame): A < S =
        local.use { (client, _) => local.let((client, config))(v) }

    // --- Factory methods ---

    def init(
        backend: HttpBackend.Client,
        maxConnectionsPerHost: Int = 100,
        idleConnectionTimeout: Duration = 60.seconds
    )(using Frame): HttpClient < (Async & Scope) =
        Scope.acquireRelease(initUnscoped(backend, maxConnectionsPerHost, idleConnectionTimeout))(_.closeNow)

    def initUnscoped(
        backend: HttpBackend.Client,
        maxConnectionsPerHost: Int = 100,
        idleConnectionTimeout: Duration = 60.seconds
    )(using Frame): HttpClient < Sync =
        require(maxConnectionsPerHost > 0, s"maxConnectionsPerHost must be positive: $maxConnectionsPerHost")
        require(idleConnectionTimeout > Duration.Zero, s"idleConnectionTimeout must be positive: $idleConnectionTimeout")
        Sync.Unsafe.defer {
            val pool = ConnectionPool.init[backend.Connection](
                maxConnectionsPerHost,
                idleConnectionTimeout,
                conn => backend.isAlive(conn),
                conn => backend.closeNowUnsafe(conn)
            )
            new HttpClient(backend, pool, maxConnectionsPerHost)
        }
    end initUnscoped

    // ==================== JSON methods ====================

    def getJson[A: Schema](url: String)(using Frame): A < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.getJson[A](""))(_.fields.body)
    def getJson[A: Schema](url: HttpUrl)(using Frame): A < (Async & Abort[HttpError]) =
        sendUrl(url, HttpRoute.getJson[A](""))(_.fields.body)

    def postJson[A: Schema](using Frame)[B: Schema](url: String, body: B): A < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.postJson[A, B](""), body)(_.fields.body)
    def postJson[A: Schema](using Frame)[B: Schema](url: HttpUrl, body: B): A < (Async & Abort[HttpError]) =
        sendUrl(url, HttpRoute.postJson[A, B](""), body)(_.fields.body)

    def putJson[A: Schema](using Frame)[B: Schema](url: String, body: B): A < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.putJson[A, B](""), body)(_.fields.body)
    def putJson[A: Schema](using Frame)[B: Schema](url: HttpUrl, body: B): A < (Async & Abort[HttpError]) =
        sendUrl(url, HttpRoute.putJson[A, B](""), body)(_.fields.body)

    def patchJson[A: Schema](using Frame)[B: Schema](url: String, body: B): A < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.patchJson[A, B](""), body)(_.fields.body)
    def patchJson[A: Schema](using Frame)[B: Schema](url: HttpUrl, body: B): A < (Async & Abort[HttpError]) =
        sendUrl(url, HttpRoute.patchJson[A, B](""), body)(_.fields.body)

    def deleteJson[A: Schema](url: String)(using Frame): A < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.deleteJson[A](""))(_.fields.body)
    def deleteJson[A: Schema](url: HttpUrl)(using Frame): A < (Async & Abort[HttpError]) =
        sendUrl(url, HttpRoute.deleteJson[A](""))(_.fields.body)

    // ==================== Text methods ====================

    def getText(url: String)(using Frame): String < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.getText(""))(_.fields.body)
    def getText(url: HttpUrl)(using Frame): String < (Async & Abort[HttpError]) =
        sendUrl(url, HttpRoute.getText(""))(_.fields.body)

    def postText(url: String, body: String)(using Frame): String < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.postText(""), body)(_.fields.body)
    def postText(url: HttpUrl, body: String)(using Frame): String < (Async & Abort[HttpError]) =
        sendUrl(url, HttpRoute.postText(""), body)(_.fields.body)

    def putText(url: String, body: String)(using Frame): String < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.putText(""), body)(_.fields.body)
    def putText(url: HttpUrl, body: String)(using Frame): String < (Async & Abort[HttpError]) =
        sendUrl(url, HttpRoute.putText(""), body)(_.fields.body)

    def patchText(url: String, body: String)(using Frame): String < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.patchText(""), body)(_.fields.body)
    def patchText(url: HttpUrl, body: String)(using Frame): String < (Async & Abort[HttpError]) =
        sendUrl(url, HttpRoute.patchText(""), body)(_.fields.body)

    def deleteText(url: String)(using Frame): String < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.deleteText(""))(_.fields.body)
    def deleteText(url: HttpUrl)(using Frame): String < (Async & Abort[HttpError]) =
        sendUrl(url, HttpRoute.deleteText(""))(_.fields.body)

    // ==================== Binary methods ====================

    def getBinary(url: String)(using Frame): Span[Byte] < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.getBinary(""))(_.fields.body)
    def getBinary(url: HttpUrl)(using Frame): Span[Byte] < (Async & Abort[HttpError]) =
        sendUrl(url, HttpRoute.getBinary(""))(_.fields.body)

    def postBinary(url: String, body: Span[Byte])(using Frame): Span[Byte] < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.postBinary(""), body)(_.fields.body)
    def postBinary(url: HttpUrl, body: Span[Byte])(using Frame): Span[Byte] < (Async & Abort[HttpError]) =
        sendUrl(url, HttpRoute.postBinary(""), body)(_.fields.body)

    def putBinary(url: String, body: Span[Byte])(using Frame): Span[Byte] < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.putBinary(""), body)(_.fields.body)
    def putBinary(url: HttpUrl, body: Span[Byte])(using Frame): Span[Byte] < (Async & Abort[HttpError]) =
        sendUrl(url, HttpRoute.putBinary(""), body)(_.fields.body)

    def patchBinary(url: String, body: Span[Byte])(using Frame): Span[Byte] < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.patchBinary(""), body)(_.fields.body)
    def patchBinary(url: HttpUrl, body: Span[Byte])(using Frame): Span[Byte] < (Async & Abort[HttpError]) =
        sendUrl(url, HttpRoute.patchBinary(""), body)(_.fields.body)

    def deleteBinary(url: String)(using Frame): Span[Byte] < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.deleteBinary(""))(_.fields.body)
    def deleteBinary(url: HttpUrl)(using Frame): Span[Byte] < (Async & Abort[HttpError]) =
        sendUrl(url, HttpRoute.deleteBinary(""))(_.fields.body)

    // ==================== Streaming methods ====================

    def getSseJson[V: Schema: Tag](url: String)(using
        Frame,
        Tag[Emit[Chunk[HttpEvent[V]]]]
    ): Stream[HttpEvent[V], Async & Abort[HttpError]] =
        Stream(parseAndSend(url, HttpRoute.getRaw("").response(_.bodySseJson[V]))(_.fields.body).map(_.emit))
    def getSseJson[V: Schema: Tag](url: HttpUrl)(using
        Frame,
        Tag[Emit[Chunk[HttpEvent[V]]]]
    ): Stream[HttpEvent[V], Async & Abort[HttpError]] =
        Stream(sendUrl(url, HttpRoute.getRaw("").response(_.bodySseJson[V]))(_.fields.body).map(_.emit))

    def getSseText(url: String)(using
        Frame,
        Tag[Emit[Chunk[HttpEvent[String]]]]
    ): Stream[HttpEvent[String], Async & Abort[HttpError]] =
        Stream(parseAndSend(url, HttpRoute.getRaw("").response(_.bodySseText))(_.fields.body).map(_.emit))
    def getSseText(url: HttpUrl)(using
        Frame,
        Tag[Emit[Chunk[HttpEvent[String]]]]
    ): Stream[HttpEvent[String], Async & Abort[HttpError]] =
        Stream(sendUrl(url, HttpRoute.getRaw("").response(_.bodySseText))(_.fields.body).map(_.emit))

    def getNdJson[V: Schema: Tag](url: String)(using
        Frame,
        Tag[Emit[Chunk[V]]]
    ): Stream[V, Async & Abort[HttpError]] =
        Stream(parseAndSend(url, HttpRoute.getRaw("").response(_.bodyNdjson[V]))(_.fields.body).map(_.emit))
    def getNdJson[V: Schema: Tag](url: HttpUrl)(using
        Frame,
        Tag[Emit[Chunk[V]]]
    ): Stream[V, Async & Abort[HttpError]] =
        Stream(sendUrl(url, HttpRoute.getRaw("").response(_.bodyNdjson[V]))(_.fields.body).map(_.emit))

    // --- Internal ---

    private def sendUrl[Out, A](
        url: HttpUrl,
        route: HttpRoute[Any, Out, Any]
    )(
        extract: HttpResponse[Out] => A
    )(using Frame): A < (Async & Abort[HttpError]) =
        local.use { (client, config) =>
            client.sendWithConfig(route, HttpRequest(route.method, url), config)(extract)
        }

    private def sendUrl[B, Out, A](
        url: HttpUrl,
        route: HttpRoute["body" ~ B, Out, Any],
        body: B
    )(
        extract: HttpResponse[Out] => A
    )(using Frame): A < (Async & Abort[HttpError]) =
        local.use { (client, config) =>
            client.sendWithConfig(route, HttpRequest(route.method, url).addField("body", body), config)(extract)
        }

    private def parseAndSend[Out, A](
        rawUrl: String,
        route: HttpRoute[Any, Out, Any]
    )(
        extract: HttpResponse[Out] => A
    )(using Frame): A < (Async & Abort[HttpError]) =
        HttpUrl.parse(rawUrl) match
            case Result.Success(url)    => sendUrl(url, route)(extract)
            case error: Result.Error[?] => Abort.fail(HttpError.ParseError(error.toString))

    private def parseAndSend[B, Out, A](
        rawUrl: String,
        route: HttpRoute["body" ~ B, Out, Any],
        body: B
    )(
        extract: HttpResponse[Out] => A
    )(using Frame): A < (Async & Abort[HttpError]) =
        HttpUrl.parse(rawUrl) match
            case Result.Success(url)    => sendUrl(url, route, body)(extract)
            case error: Result.Error[?] => Abort.fail(HttpError.ParseError(error.toString))

end HttpClient
