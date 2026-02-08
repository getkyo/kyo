package kyo

import java.net.URI
import kyo.internal.ConnectionPool
import kyo.internal.NdjsonDecoder
import kyo.internal.SseDecoder
import kyo.internal.UrlParser

final class HttpClient private (
    private val pool: ConnectionPool,
    private val factory: Backend.ConnectionFactory
):
    def send(request: HttpRequest[HttpBody.Bytes])(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        HttpFilter.use { filter =>
            HttpClient.configLocal.use { config =>
                filter(
                    request,
                    filteredRequest =>
                        val effectiveUrl = HttpClient.buildEffectiveUrl(config, filteredRequest)
                        HttpClient.sendWithPolicies(
                            pool,
                            effectiveUrl,
                            filteredRequest.asInstanceOf[HttpRequest[HttpBody.Bytes]],
                            config
                        )
                ).map(_.ensureBytes) // Filter can short-circuit with any response type
            }
        }

    /** Streams the response as an HttpResponse with a streaming body.
      *
      * Uses a non-pooled connection. The connection is closed when the enclosing Scope exits. Applies filters to the request/response.
      */
    def stream(request: HttpRequest[?])(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Scope & Abort[HttpError]) =
        HttpFilter.use { filter =>
            HttpClient.configLocal.use { config =>
                filter(
                    request,
                    filteredRequest =>
                        val effectiveUrl = HttpClient.buildEffectiveUrl(config, filteredRequest)
                        val parsed       = HttpClient.parseUrl(effectiveUrl)
                        pool.connectDirect(parsed.host, parsed.port, parsed.ssl, config.connectTimeout).map { conn =>
                            Scope.ensure(conn.close).andThen(conn.stream(filteredRequest.asInstanceOf[HttpRequest[?]]))
                        }
                ).map { response =>
                    // A filter can short-circuit with a cached/mocked buffered response.
                    // Wrap the bytes as a single-chunk stream so the streaming contract holds.
                    response.body.use(
                        b => response.withBody(HttpBody.stream(Stream.init(Chunk(b.span)))),
                        s => response.withBody(s)
                    )
                }
            }
        }

    def send(url: String)(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        send(HttpRequest.get(url))

    def stream(url: String)(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Scope & Abort[HttpError]) =
        stream(HttpRequest.get(url))

    /** Streams Server-Sent Events from the given URL. */
    def streamSse[V: Schema: Tag](url: String)(using
        Frame,
        Tag[Emit[Chunk[ServerSentEvent[V]]]]
    ): Stream[ServerSentEvent[V], Async] < (Async & Scope & Abort[HttpError]) =
        streamSse[V](HttpRequest.get(url))

    /** Streams Server-Sent Events from the given request. */
    def streamSse[V: Schema: Tag](request: HttpRequest[?])(using
        Frame,
        Tag[Emit[Chunk[ServerSentEvent[V]]]]
    ): Stream[ServerSentEvent[V], Async] < (Async & Scope & Abort[HttpError]) =
        stream(request).map { response =>
            val decoder = new SseDecoder[V](Schema[V])
            response.bodyStream.mapChunkPure[Span[Byte], ServerSentEvent[V]] { chunk =>
                val result = Seq.newBuilder[ServerSentEvent[V]]
                chunk.foreach(bytes => result ++= decoder.decode(bytes))
                result.result()
            }
        }

    /** Streams NDJSON values from the given URL. */
    def streamNdjson[V: Schema: Tag](url: String)(using
        Frame,
        Tag[Emit[Chunk[V]]]
    ): Stream[V, Async] < (Async & Scope & Abort[HttpError]) =
        streamNdjson[V](HttpRequest.get(url))

    /** Streams NDJSON values from the given request. */
    def streamNdjson[V: Schema: Tag](request: HttpRequest[?])(using
        Frame,
        Tag[Emit[Chunk[V]]]
    ): Stream[V, Async] < (Async & Scope & Abort[HttpError]) =
        stream(request).map { response =>
            val decoder = new NdjsonDecoder[V](Schema[V])
            response.bodyStream.mapChunkPure[Span[Byte], V] { chunk =>
                val result = Seq.newBuilder[V]
                chunk.foreach(bytes => result ++= decoder.decode(bytes))
                result.result()
            }
        }

    /** Pre-establishes connections to reduce first-request latency.
      *
      * Runs `connections` concurrent HEAD requests in a loop for up to `duration`. Invalid parameters are clamped to safe defaults.
      */
    def warmup(url: String, connections: Int = 1, duration: Duration = 10.seconds)(using Frame): Unit < Async =
        val c = Math.max(1, connections)
        val d = if duration > Duration.Zero then duration else 10.seconds
        val warnConns: Unit < Sync =
            if c != connections then Log.warn(s"warmup: connections clamped from $connections to $c")
            else ()
        val warnDur: Unit < Sync =
            if d != duration then Log.warn(s"warmup: duration clamped from $duration to $d")
            else ()
        warnConns.andThen(warnDur).andThen {
            Abort.run[kyo.Timeout](Async.timeout(d)(
                Async.fill(c)(
                    Loop(()) { _ =>
                        Abort.run[HttpError](send(HttpRequest.head(url)))
                            .andThen(Loop.continue(()))
                    }
                ).unit
            )).unit
        }
    end warmup

    /** Pre-establishes connections to multiple URLs. */
    /** Pre-establishes connections to multiple URLs. */
    def warmup(urls: Seq[String])(using Frame): Unit < Async =
        Async.foreach(urls)(url => warmup(url)).unit

    def closeNow(using Frame): Unit < Async =
        close(Duration.Zero)

    def close(using Frame): Unit < Async =
        close(30.seconds)

    def close(gracePeriod: Duration)(using Frame): Unit < Async =
        pool.close(gracePeriod)
end HttpClient

object HttpClient:

    // --- Convenience methods (use shared client) ---

    def get[A: Schema](url: String)(using Frame): A < (Async & Abort[HttpError]) =
        send(HttpRequest.get(url)).map(checkStatusAndParse[A])

    def post[A: Schema, B: Schema](url: String, body: B)(using Frame): A < (Async & Abort[HttpError]) =
        send(HttpRequest.post(url, body)).map(checkStatusAndParse[A])

    def put[A: Schema, B: Schema](url: String, body: B)(using Frame): A < (Async & Abort[HttpError]) =
        send(HttpRequest.put(url, body)).map(checkStatusAndParse[A])

    def delete[A: Schema](url: String)(using Frame): A < (Async & Abort[HttpError]) =
        send(HttpRequest.delete(url)).map(checkStatusAndParse[A])

    def send(url: String)(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        clientLocal.use(_.send(url))

    def send(request: HttpRequest[HttpBody.Bytes])(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        clientLocal.use(_.send(request))

    def stream(request: HttpRequest[?])(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Scope & Abort[HttpError]) =
        clientLocal.use(_.stream(request))

    def stream(url: String)(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Scope & Abort[HttpError]) =
        clientLocal.use(_.stream(HttpRequest.get(url)))

    def streamSse[V: Schema: Tag](url: String)(using
        Frame,
        Tag[Emit[Chunk[ServerSentEvent[V]]]]
    ): Stream[ServerSentEvent[V], Async] < (Async & Scope & Abort[HttpError]) =
        clientLocal.use(_.streamSse[V](url))

    def streamSse[V: Schema: Tag](request: HttpRequest[?])(using
        Frame,
        Tag[Emit[Chunk[ServerSentEvent[V]]]]
    ): Stream[ServerSentEvent[V], Async] < (Async & Scope & Abort[HttpError]) =
        clientLocal.use(_.streamSse[V](request))

    def streamNdjson[V: Schema: Tag](url: String)(using
        Frame,
        Tag[Emit[Chunk[V]]]
    ): Stream[V, Async] < (Async & Scope & Abort[HttpError]) =
        clientLocal.use(_.streamNdjson[V](url))

    def streamNdjson[V: Schema: Tag](request: HttpRequest[?])(using
        Frame,
        Tag[Emit[Chunk[V]]]
    ): Stream[V, Async] < (Async & Scope & Abort[HttpError]) =
        clientLocal.use(_.streamNdjson[V](request))

    // --- Context management ---

    def let[A, S](client: HttpClient)(v: A < S)(using Frame): A < S =
        clientLocal.let(client)(v)

    def withConfig[A, S](f: Config => Config)(v: A < S)(using Frame): A < S =
        configLocal.use(c => configLocal.let(f(c))(v))

    // --- Factory methods ---

    def init(
        maxConnectionsPerHost: Maybe[Int] = DefaultMaxConnectionsPerHost,
        connectionAcquireTimeout: Duration = DefaultConnectionAcquireTimeout,
        maxResponseSizeBytes: Int = DefaultMaxResponseSizeBytes,
        daemon: Boolean = false,
        backend: Backend = HttpPlatformBackend.default
    )(using Frame): HttpClient < (Sync & Scope) =
        Scope.acquireRelease(initUnscoped(
            maxConnectionsPerHost,
            connectionAcquireTimeout,
            maxResponseSizeBytes,
            daemon,
            backend
        ))(_.closeNow)

    def initUnscoped(
        maxConnectionsPerHost: Maybe[Int] = DefaultMaxConnectionsPerHost,
        connectionAcquireTimeout: Duration = DefaultConnectionAcquireTimeout,
        maxResponseSizeBytes: Int = DefaultMaxResponseSizeBytes,
        daemon: Boolean = false,
        backend: Backend = HttpPlatformBackend.default
    )(using Frame): HttpClient < Sync =
        Sync.Unsafe {
            Unsafe.init(maxConnectionsPerHost, connectionAcquireTimeout, maxResponseSizeBytes, daemon, backend)
        }
    end initUnscoped

    object Unsafe:
        /** Low-level client initialization requiring AllowUnsafe. */
        def init(
            maxConnectionsPerHost: Maybe[Int] = DefaultMaxConnectionsPerHost,
            connectionAcquireTimeout: Duration = DefaultConnectionAcquireTimeout,
            maxResponseSizeBytes: Int = DefaultMaxResponseSizeBytes,
            daemon: Boolean = false,
            backend: Backend = HttpPlatformBackend.default
        )(using AllowUnsafe): HttpClient =
            maxConnectionsPerHost.foreach(n => require(n > 0, s"maxConnectionsPerHost must be positive: $n"))
            require(connectionAcquireTimeout > Duration.Zero, s"connectionAcquireTimeout must be positive: $connectionAcquireTimeout")
            require(maxResponseSizeBytes > 0, s"maxResponseSizeBytes must be positive: $maxResponseSizeBytes")
            val factory = backend.connectionFactory(maxResponseSizeBytes, daemon)
            val pool    = new ConnectionPool(factory, maxConnectionsPerHost, connectionAcquireTimeout)
            new HttpClient(pool, factory)
        end init
    end Unsafe

    // --- Config ---

    case class Config(
        baseUrl: Maybe[String] = Absent,
        timeout: Maybe[Duration] = Absent,
        connectTimeout: Maybe[Duration] = Absent,
        followRedirects: Boolean = true,
        maxRedirects: Int = 10,
        retrySchedule: Maybe[Schedule] = Absent,
        retryOn: HttpResponse[?] => Boolean = _.status.isServerError
    ):
        require(maxRedirects >= 0, s"maxRedirects must be non-negative: $maxRedirects")
        timeout.foreach(d => require(d > Duration.Zero, s"timeout must be positive: $d"))
        connectTimeout.foreach(d => require(d > Duration.Zero, s"connectTimeout must be positive: $d"))

        def baseUrl(url: String): Config =
            copy(baseUrl = Present(url))
        def timeout(d: Duration): Config =
            require(d > Duration.Zero, s"timeout must be positive: $d")
            copy(timeout = Present(d))
        def connectTimeout(d: Duration): Config =
            require(d > Duration.Zero, s"connectTimeout must be positive: $d")
            copy(connectTimeout = Present(d))
        def followRedirects(b: Boolean): Config =
            copy(followRedirects = b)
        def maxRedirects(n: Int): Config =
            require(n >= 0, s"maxRedirects must be non-negative: $n")
            copy(maxRedirects = n)
        def retry(schedule: Schedule): Config =
            copy(retrySchedule = Present(schedule))
        def retryWhen(f: HttpResponse[?] => Boolean): Config =
            copy(retryOn = f)
    end Config

    object Config:
        val default: Config = Config()

        def apply(baseUrl: String): Config =
            require(baseUrl.nonEmpty, "baseUrl cannot be empty")
            try
                new java.net.URI(baseUrl)
            catch
                case e: Exception => throw new IllegalArgumentException(s"Invalid baseUrl: $baseUrl", e)
            end try
            Config(baseUrl = Present(baseUrl))
        end apply
    end Config

    // --- Private implementation ---

    private[kyo] inline def DefaultHttpPort  = 80
    private[kyo] inline def DefaultHttpsPort = 443

    /** Build a URL from host/port/ssl/path components. */
    private[kyo] def buildUrl(host: String, port: Int, ssl: Boolean, path: String): String =
        val scheme      = if ssl then "https" else "http"
        val defaultPort = if ssl then DefaultHttpsPort else DefaultHttpPort
        val portStr     = if port == defaultPort then "" else s":$port"
        s"$scheme://$host$portStr$path"
    end buildUrl
    private[kyo] inline def DefaultMaxConnectionsPerHost     = Maybe(100)
    private[kyo] inline def DefaultConnectionAcquireTimeout  = 30.seconds
    private[kyo] inline def DefaultMaxResponseSizeBytes: Int = 1048576

    // Shared client uses daemon threads so JVM can exit
    private lazy val sharedClient: HttpClient =
        import AllowUnsafe.embrace.danger
        Unsafe.init(daemon = true)

    private val clientLocal      = Local.init(sharedClient)
    private[kyo] val configLocal = Local.init(Config.default)

    private def checkStatusAndParse[A: Schema](response: HttpResponse[HttpBody.Bytes])(using Frame): A < Abort[HttpError] =
        if response.status.isError then
            Abort.fail(HttpError.StatusError(response.status, response.bodyText))
        else
            response.bodyAs[A]

    private[kyo] def buildEffectiveUrl(config: Config, request: HttpRequest[?]): String =
        config.baseUrl match
            case Present(base) =>
                val url = request.url
                if url.startsWith("http://") || url.startsWith("https://") then url
                else
                    val normalizedUrl = if url.startsWith("/") then url else "/" + url
                    val baseUri       = new URI(base)
                    baseUri.resolve(normalizedUrl).toString
                end if
            case Absent =>
                val host = request.host
                val port = request.port
                val path = request.url
                if host.isEmpty then path
                else buildUrl(host, port, port == DefaultHttpsPort, path)

    /** Parse a URL into (host, port, ssl, rawPath, rawQuery) components using manual string parsing. */
    private[kyo] case class ParsedUrl(host: String, port: Int, ssl: Boolean, rawPath: String, rawQuery: Maybe[String])

    private[kyo] def parseUrl(url: String): ParsedUrl =
        UrlParser.parseUrlParts(url) { (scheme, host, port, rawPath, rawQuery) =>
            val ssl           = scheme.contains("https")
            val effectivePort = if port < 0 then (if ssl then DefaultHttpsPort else DefaultHttpPort) else port
            // Strip IPv6 brackets for networking (InetAddress expects raw IP)
            val rawHost = host.getOrElse("") match
                case h if h.startsWith("[") && h.endsWith("]") => h.substring(1, h.length - 1)
                case h                                         => h
            ParsedUrl(rawHost, effectivePort, ssl, rawPath, rawQuery)
        }

    /** Shared redirect/retry/timeout logic — delegates raw I/O to ConnectionPool. */
    private def sendWithPolicies(
        pool: ConnectionPool,
        url: String,
        request: HttpRequest[HttpBody.Bytes],
        config: Config,
        redirectCount: Int = 0,
        retrySchedule: Maybe[Schedule] = Absent,
        attemptCount: Int = 1
    )(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        val currentSchedule = retrySchedule.orElse(config.retrySchedule)

        // 1. Send request via pool
        val doSend: HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
            val parsed = parseUrl(url)
            // Build path+query from parsed components (no duplicate URI allocation)
            val pathAndQuery = parsed.rawQuery match
                case Present(q) => s"${parsed.rawPath}?$q"
                case Absent     => parsed.rawPath
            val reqWithPath = request.withUrl(pathAndQuery)
            pool.acquire(parsed.host, parsed.port, parsed.ssl, config.connectTimeout).map { conn =>
                @volatile var completed = false
                Sync.ensure {
                    import AllowUnsafe.embrace.danger
                    // If request was interrupted mid-flight, close the connection so
                    // the server sees the disconnect. Otherwise return to pool.
                    if !completed then conn.closeAbruptly()
                    pool.release(parsed.host, parsed.port, parsed.ssl, conn)
                } {
                    conn.send(reqWithPath).map { resp =>
                        completed = true
                        resp
                    }
                }
            }
        end doSend

        // 2. Apply request-level timeout
        val withTimeout: HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
            config.timeout match
                case Present(d) =>
                    Abort.run[kyo.Timeout](Async.timeout(d)(doSend)).map {
                        case Result.Success(resp) => resp
                        case Result.Failure(_)    => Abort.fail(HttpError.Timeout(s"Request timed out after $d"))
                        case Result.Panic(e)      => throw e
                    }
                case Absent => doSend

        withTimeout.map { resp =>
            // 3. Handle redirects
            if config.followRedirects && resp.status.isRedirect then
                if redirectCount >= config.maxRedirects then
                    Abort.fail(HttpError.TooManyRedirects(redirectCount))
                else
                    resp.header("Location") match
                        case Present(location) =>
                            val redirectUrl =
                                if location.startsWith("http://") || location.startsWith("https://") then
                                    location
                                else
                                    // Rare path: relative redirect — use URI.resolve()
                                    new URI(url).resolve(location).toString
                            sendWithPolicies(
                                pool,
                                redirectUrl,
                                request,
                                config,
                                redirectCount + 1,
                                currentSchedule,
                                attemptCount
                            )
                        case Absent =>
                            resp
            else
                resp
        }.map { finalResp =>
            // 4. Handle retry based on response
            currentSchedule match
                case Present(schedule) if config.retryOn(finalResp) =>
                    Clock.now.map { now =>
                        schedule.next(now) match
                            case Present((delay, nextSchedule)) =>
                                Async.delay(delay) {
                                    sendWithPolicies(
                                        pool,
                                        url,
                                        request,
                                        config,
                                        0, // Reset redirect count for retry
                                        Present(nextSchedule),
                                        attemptCount + 1
                                    )
                                }
                            case Absent =>
                                Abort.fail(HttpError.RetriesExhausted(attemptCount, finalResp.status, finalResp.bodyText))
                    }
                case _ =>
                    finalResp
        }
    end sendWithPolicies

end HttpClient
