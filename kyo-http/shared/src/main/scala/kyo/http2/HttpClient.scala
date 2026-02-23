package kyo.http2

import kyo.<
import kyo.Abort
import kyo.Absent
import kyo.Async
import kyo.Clock
import kyo.Duration
import kyo.Frame
import kyo.Local
import kyo.Maybe
import kyo.Present
import kyo.Result
import kyo.Schedule
import kyo.Scope
import kyo.Sync
import kyo.http2.internal.ConnectionPool
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
        HttpClient.configLocal.use { config =>
            val resolved = config.baseUrl match
                case Present(base) if request.url.scheme.isEmpty =>
                    request.copy(url = HttpUrl(base.scheme, base.host, base.port, request.url.path, request.url.rawQuery))
                case _ => request
            retryWith(route, resolved, config)(f)
        }

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
                                        loop(req.copy(url = newUrl), count + 1)
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
                        backend.sendWith(conn, route, request, config.connectTimeout)(f)
                    }
                case _ =>
                    if pool.tryReserve(key) then
                        Sync.ensure(pool.unreserve(key)) {
                            backend.connectWith(request.url.host, request.url.port, request.url.ssl, config.connectTimeout) { conn =>
                                Sync.ensure(pool.release(key, conn)) {
                                    backend.sendWith(conn, route, request, config.connectTimeout)(f)
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
        Sync.Unsafe.defer(pool.closeAll()).andThen(backend.close(gracePeriod))
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

    private val configLocal: Local[Config] = Local.init(Config())

    /** Applies a config transformation for the given computation (stacks with current config). */
    def withConfig[A, S](f: Config => Config)(v: A < S)(using Frame): A < S =
        configLocal.update(f)(v)

    /** Sets the config for the given computation. */
    def withConfig[A, S](config: Config)(v: A < S)(using Frame): A < S =
        configLocal.let(config)(v)

    def init(
        backend: HttpBackend.Client,
        maxConnectionsPerHost: Int = 100
    )(using Frame): HttpClient < (Async & Scope) =
        Scope.acquireRelease(initUnscoped(backend, maxConnectionsPerHost))(_.closeNow)

    def initUnscoped(
        backend: HttpBackend.Client,
        maxConnectionsPerHost: Int = 100
    )(using Frame): HttpClient < Sync =
        require(maxConnectionsPerHost > 0, s"maxConnectionsPerHost must be positive: $maxConnectionsPerHost")
        Sync.Unsafe.defer {
            val pool = ConnectionPool.init[backend.Connection](
                maxConnectionsPerHost,
                conn => backend.isAlive(conn),
                conn => backend.closeNowUnsafe(conn)
            )
            new HttpClient(backend, pool, maxConnectionsPerHost)
        }
    end initUnscoped

end HttpClient
