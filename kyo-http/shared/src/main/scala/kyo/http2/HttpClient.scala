package kyo.http2

import kyo.<
import kyo.Abort
import kyo.Absent
import kyo.Async
import kyo.Chunk
import kyo.Clock
import kyo.Duration
import kyo.Emit
import kyo.Frame
import kyo.Local
import kyo.Maybe
import kyo.Present
import kyo.Record2.~
import kyo.Result
import kyo.Schedule
import kyo.Scope
import kyo.Span
import kyo.Stream
import kyo.Sync
import kyo.Tag
import kyo.http2.internal.ConnectionPool
import kyo.http2.internal.HttpPlatformBackend
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

    private[http2] def sendWithConfig[In, Out, A, S](
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
                                        loop(req.copy(url = resolved), count + 1)
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
        Sync.Unsafe.defer {
            val key = HostKey(request.url.host, request.url.port)
            pool.poll(key) match
                case Present(conn) =>
                    Sync.ensure(pool.release(key, conn)) {
                        backend.sendWith(conn, route, request)(f)
                    }
                case _ =>
                    if pool.tryReserve(key) then
                        Sync.ensure(pool.unreserve(key)) {
                            backend.connectWith(request.url.host, request.url.port, request.url.ssl, config.connectTimeout) { conn =>
                                Sync.ensure(pool.release(key, conn)) {
                                    backend.sendWith(conn, route, request)(f)
                                }
                            }
                        }
                    else
                        Abort.fail(HttpError.ConnectionPoolExhausted(
                            request.url.host,
                            request.url.port,
                            maxConnectionsPerHost
                        ))
            end match
        }

    def close(gracePeriod: Duration)(using Frame): Unit < Async =
        Sync.Unsafe.defer(pool.closeAll())
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
    end Config

    // --- Default client ---

    private lazy val defaultClient: HttpClient =
        import kyo.AllowUnsafe.embrace.danger
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

    def postJson[A: Schema, B: Schema](url: String, body: B)(using Frame): A < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.postJson[A, B](""), body)(_.fields.body)

    def putJson[A: Schema, B: Schema](url: String, body: B)(using Frame): A < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.putJson[A, B](""), body)(_.fields.body)

    def patchJson[A: Schema, B: Schema](url: String, body: B)(using Frame): A < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.patchJson[A, B](""), body)(_.fields.body)

    def deleteJson[A: Schema](url: String)(using Frame): A < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.deleteJson[A](""))(_.fields.body)

    // ==================== Text methods ====================

    def getText(url: String)(using Frame): String < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.getText(""))(_.fields.body)

    def postText(url: String, body: String)(using Frame): String < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.postText(""), body)(_.fields.body)

    def putText(url: String, body: String)(using Frame): String < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.putText(""), body)(_.fields.body)

    def patchText(url: String, body: String)(using Frame): String < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.patchText(""), body)(_.fields.body)

    def deleteText(url: String)(using Frame): String < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.deleteText(""))(_.fields.body)

    // ==================== Binary methods ====================

    def getBinary(url: String)(using Frame): Span[Byte] < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.getBinary(""))(_.fields.body)

    def postBinary(url: String, body: Span[Byte])(using Frame): Span[Byte] < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.postBinary(""), body)(_.fields.body)

    def putBinary(url: String, body: Span[Byte])(using Frame): Span[Byte] < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.putBinary(""), body)(_.fields.body)

    def patchBinary(url: String, body: Span[Byte])(using Frame): Span[Byte] < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.patchBinary(""), body)(_.fields.body)

    def deleteBinary(url: String)(using Frame): Span[Byte] < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.deleteBinary(""))(_.fields.body)

    // ==================== Streaming methods ====================

    def getSseJson[V: Schema: Tag](url: String)(using
        Frame,
        Tag[Emit[Chunk[HttpEvent[V]]]]
    ): Stream[HttpEvent[V], Async & Scope] < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.getRaw("").response(_.bodySseJson[V]))(_.fields.body)

    def getSseText(url: String)(using
        Frame,
        Tag[Emit[Chunk[HttpEvent[String]]]]
    ): Stream[HttpEvent[String], Async & Scope] < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.getRaw("").response(_.bodySseText))(_.fields.body)

    def getNdJson[V: Schema: Tag](url: String)(using
        Frame,
        Tag[Emit[Chunk[V]]]
    ): Stream[V, Async] < (Async & Abort[HttpError]) =
        parseAndSend(url, HttpRoute.getRaw("").response(_.bodyNdjson[V]))(_.fields.body)

    // --- Internal ---

    private def parseAndSend[Out, A](
        rawUrl: String,
        route: HttpRoute[Any, Out, Any]
    )(
        extract: HttpResponse[Out] => A
    )(using Frame): A < (Async & Abort[HttpError]) =
        HttpUrl.parse(rawUrl) match
            case Result.Success(url) =>
                local.use { (client, config) =>
                    client.sendWithConfig(route, HttpRequest(route.method, url), config)(extract)
                }
            case error: Result.Error[?] => Abort.fail(HttpError.ParseError(error.toString))

    private def parseAndSend[B, Out, A](
        rawUrl: String,
        route: HttpRoute["body" ~ B, Out, Any],
        body: B
    )(
        extract: HttpResponse[Out] => A
    )(using Frame): A < (Async & Abort[HttpError]) =
        HttpUrl.parse(rawUrl) match
            case Result.Success(url) =>
                local.use { (client, config) =>
                    client.sendWithConfig(route, HttpRequest(route.method, url).addField("body", body), config)(extract)
                }
            case error: Result.Error[?] => Abort.fail(HttpError.ParseError(error.toString))

end HttpClient
