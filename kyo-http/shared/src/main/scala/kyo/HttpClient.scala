package kyo

import HttpRoute.*
import java.net.URI
import kyo.internal.ConnectionPool
import kyo.internal.Content
import kyo.internal.UrlParser
import scala.NamedTuple.AnyNamedTuple
import scala.annotation.tailrec

/** HTTP client with connection pooling, automatic retries, and redirect following.
  *
  * The primary API is route-based: define an `HttpRoute` and use `HttpClient.call(route, input)` for type-safe request/response handling.
  * Convenience methods (`get`, `post`, `put`, `delete`) provide shorthand for common JSON request patterns. Streaming methods (`streamSse`,
  * `streamNdjson`) return typed streams via routes.
  *
  *   - Automatic redirect following with configurable limit
  *   - Retry with `Schedule`-based policies
  *   - Per-request timeout and connect timeout support
  *   - `Config` for base URL, timeout, redirect, and retry settings (request-level via `Local`)
  *   - `warmupUrl`/`warmupUrls` for pre-establishing connections
  *
  * Note: `Config` is per-request (applied via `Local` with `withConfig`), not per-client. Pool-level settings (maxConnectionsPerHost, etc.)
  * are set at `init` time.
  *
  * Note: The shared client uses daemon threads so the JVM can exit without explicit shutdown.
  *
  * @see
  *   [[kyo.HttpRoute]]
  * @see
  *   [[kyo.HttpRequest]]
  * @see
  *   [[kyo.HttpResponse]]
  * @see
  *   [[kyo.HttpFilter]]
  * @see
  *   [[kyo.HttpError]]
  * @see
  *   [[kyo.HttpClient.Config]]
  */
final class HttpClient private (
    private val pool: ConnectionPool,
    private val factory: Backend.ConnectionFactory
):
    /** Sends a buffered request through the filter/redirect/retry pipeline. Returns the response regardless of status code. */
    def send(request: HttpRequest[HttpBody.Bytes])(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        HttpFilter.use { filter =>
            HttpClient.configLocal.use { config =>
                if filter eq HttpFilter.noop then
                    // Fast path — no filter, avoid closure allocation
                    val effectiveUrl = HttpClient.buildEffectiveUrl(config, request)
                    HttpClient.sendWithPolicies(pool, effectiveUrl, request, config)
                else
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

    /** Streams the response body. WARNING: bypasses redirect/retry/timeout — only filters, base URL, and connect timeout apply.
      *
      * The connection cleanup finalizer is deferred into the body stream. The caller must consume the stream within a `Scope.run` to ensure
      * the connection is closed. Not consuming the stream will leak the connection.
      */
    def stream(request: HttpRequest[?])(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Abort[HttpError]) =
        HttpFilter.use { filter =>
            HttpClient.configLocal.use { config =>
                def doStream(req: HttpRequest[?]) =
                    def loop(
                        currentReq: HttpRequest[?],
                        redirectCount: Int
                    ): HttpResponse[HttpBody.Streamed] < (Async & Abort[HttpError]) =
                        val effectiveUrl = HttpClient.buildEffectiveUrl(config, currentReq)
                        HttpClient.parseUrl(effectiveUrl) { (host, port, ssl, rawPath, rawQuery) =>
                            pool.connectDirect(host, port, ssl, config.connectTimeout).map { conn =>
                                val reqWithPath = currentReq.withParsedUrl(rawPath, rawQuery)
                                conn.stream(reqWithPath).map { response =>
                                    val code = response.status.code
                                    if config.followRedirects && (code == 301 || code == 302) &&
                                        redirectCount < config.maxRedirects
                                    then
                                        response.header("Location") match
                                            case Present(location) =>
                                                val redirectUrl =
                                                    if location.startsWith("http://") || location.startsWith("https://") then
                                                        location
                                                    else
                                                        new URI(effectiveUrl).resolve(location).toString
                                                conn.close.andThen(
                                                    loop(HttpRequest.get(redirectUrl), redirectCount + 1)
                                                )
                                            case Absent =>
                                                HttpClient.scopeStream(response, conn.close)
                                    else
                                        HttpClient.scopeStream(response, conn.close)
                                    end if
                                }
                            }
                        }
                    end loop
                    loop(req, 0)
                end doStream
                if filter eq HttpFilter.noop then
                    doStream(request)
                else
                    filter(request, doStream).map { response =>
                        response.body.use(
                            b => response.withBody(HttpBody.stream(Stream.init(Chunk(b.span)))),
                            s => response.withBody(s)
                        )
                    }
                end if
            }
        }

    private[kyo] def send(url: String)(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        send(HttpRequest.get(url))

    private[kyo] def stream(url: String)(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Abort[HttpError]) =
        stream(HttpRequest.get(url))

    /** Pre-establishes connections to reduce first-request latency.
      *
      * Runs `connections` concurrent HEAD requests in a loop for up to `duration`. Invalid parameters are clamped to safe defaults.
      */
    def warmupUrl(url: String, duration: Duration, connections: Int = 1)(using Frame): Unit < Async =
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
                Async.fill(c, c)(
                    Loop(()) { _ =>
                        Abort.run[HttpError](send(HttpRequest.head(url)))
                            .andThen(Loop.continue(()))
                    }
                ).unit
            )).unit
        }
    end warmupUrl

    /** Pre-establishes connections to multiple URLs. */
    def warmupUrls(urls: Seq[String], duration: Duration, connections: Int = 1)(using Frame): Unit < Async =
        Async.foreach(urls, urls.size)(url => warmupUrl(url, duration, connections)).unit

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
        send(url).map(decodeResponse[A])

    def post[A: Schema, B: Schema](url: String, body: B)(using Frame): A < (Async & Abort[HttpError]) =
        send(HttpRequest.post(url, body)).map(decodeResponse[A])

    def put[A: Schema, B: Schema](url: String, body: B)(using Frame): A < (Async & Abort[HttpError]) =
        send(HttpRequest.put(url, body)).map(decodeResponse[A])

    def delete[A: Schema](url: String)(using Frame): A < (Async & Abort[HttpError]) =
        send(HttpRequest.delete(url)).map(decodeResponse[A])

    def delete(url: String)(using Frame): Unit < (Async & Abort[HttpError]) =
        send(HttpRequest.delete(url)).map { response =>
            if response.status.isError then
                Abort.fail[HttpError](HttpError.StatusError(response.status, response.bodyText))
            else ()
        }

    def patch[A: Schema, B: Schema](url: String, body: B)(using Frame): A < (Async & Abort[HttpError]) =
        send(HttpRequest.patch(url, body)).map(decodeResponse[A])

    private def decodeResponse[A: Schema](response: HttpResponse[HttpBody.Bytes])(using Frame): A < Abort[HttpError] =
        if response.status.isError then
            Abort.fail(HttpError.StatusError(response.status, response.bodyText))
        else
            Abort.get(Schema[A].decode(response.bodyText).mapFailure(HttpError.ParseError(_)))

    private[kyo] def send(url: String)(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        clientLocal.use(_.send(url))

    def send(request: HttpRequest[HttpBody.Bytes])(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        clientLocal.use(_.send(request))

    def stream(request: HttpRequest[?])(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Abort[HttpError]) =
        clientLocal.use(_.stream(request))

    private[kyo] def stream(url: String)(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Abort[HttpError]) =
        clientLocal.use(_.stream(HttpRequest.get(url)))

    def streamSse[V: Schema: Tag](url: String)(using
        Frame,
        Tag[Emit[Chunk[HttpEvent[V]]]]
    ): Stream[HttpEvent[V], Async & Scope] < (Async & Abort[HttpError]) =
        call(HttpRoute.get(url).response(_.bodySse[V])).map(_.body)

    def streamNdjson[V: Schema: Tag](url: String)(using
        Frame,
        Tag[Emit[Chunk[V]]]
    ): Stream[V, Async & Scope] < (Async & Abort[HttpError]) =
        call(HttpRoute.get(url).response(_.bodyNdjson[V])).map(_.body)

    // --- Route-based client: single `call` method ---

    /** Calls a route with no inputs (no path captures, query, header, or body params).
      *
      * Returns a `Row` with named fields for each declared output plus `"response"` (the raw `HttpResponse`). For example, a route with
      * `.response(_.bodyJson[User])` returns a row accessible as `result.body` and `result.response`.
      *
      * For streaming routes, the returned `Stream` carries `Scope` in its effect type. The caller must consume the stream within a
      * `Scope.run` to ensure the underlying connection is closed. Not consuming the stream will leak the connection.
      */
    def call[Out <: AnyNamedTuple, Err](route: HttpRoute[Row.Empty, Row.Empty, Out, Err])(using
        Frame
    ): Row[FullOutput[Out]] < (Async & Abort[HttpError] & Abort[Err]) =
        callImpl(route, EmptyTuple)

    /** Calls a route with typed inputs. The input tuple contains path captures followed by request params, in declaration order.
      *
      * Returns a `Row` with named fields for each declared output plus `"response"` (the raw `HttpResponse`).
      *
      * For streaming routes, the returned `Stream` carries `Scope` in its effect type. The caller must consume the stream within a
      * `Scope.run` to ensure the underlying connection is closed. Not consuming the stream will leak the connection.
      */
    def call[PathIn <: AnyNamedTuple, In <: AnyNamedTuple, Out <: AnyNamedTuple, Err](
        route: HttpRoute[PathIn, In, Out, Err],
        in: InputValue[PathIn, In]
    )(using Frame): Row[FullOutput[Out]] < (Async & Abort[HttpError] & Abort[Err]) =
        callImpl(route, in)

    private def callImpl[Out <: AnyNamedTuple, Err](
        route: HttpRoute[?, ?, Out, Err],
        in: Any
    )(using Frame): Row[FullOutput[Out]] < (Async & Abort[HttpError] & Abort[Err]) =
        val request         = buildRouteRequest(route, in)
        val hasStreamingOut = hasStreamingOutput(route)
        val hasStreamingIn  = hasStreamingInput(route)

        if hasStreamingOut || hasStreamingIn then
            // Streaming path: connection cleanup is deferred into the body stream
            stream(request).map { response =>
                if response.status.isError then
                    response.ensureBytes.map { buffered =>
                        handleErrorResponse(route, buffered.status, buffered.bodyText)
                    }
                else if hasStreamingOut then
                    decodeStreamBody[Out](route, response).map { body =>
                        buildOutputRow[Out](route, body, response)
                    }
                else
                    response.ensureBytes.map { buffered =>
                        buildOutputRow[Out](route, decodeBufferedBodyRaw[Out](route, buffered), buffered)
                    }
            }
        else
            // Buffered input/output: use pooled connection
            // Safe to cast: we know the request has no streaming body
            send(request.asInstanceOf[HttpRequest[HttpBody.Bytes]]).map { response =>
                if response.status.isError then
                    handleErrorResponse(route, response.status, response.bodyText)
                else
                    buildOutputRow[Out](route, decodeBufferedBodyRaw[Out](route, response), response)
            }
        end if
    end callImpl

    private case class RouteRequestState(
        queryParts: List[String] = Nil,
        headers: HttpHeaders = HttpHeaders.empty,
        cookieParts: List[String] = Nil,
        formParts: List[String] = Nil,
        bodyValue: Maybe[Any] = Absent,
        bodyContent: Maybe[Content] = Absent
    )

    private def buildRouteRequest(route: HttpRoute[?, ?, ?, ?], in: Any)(using Frame): HttpRequest[?] =
        val (pathStr, pathCount) = buildRoutePath(route.path, in, 0)
        val fields               = route.request.inputFields

        @tailrec def loop(i: Int, fieldIdx: Int, s: RouteRequestState): RouteRequestState =
            if i >= fields.size then s
            else
                fields(i) match
                    case field @ InputField.Query(_, _, _, _, _, _) =>
                        val raw = extractAt(in, fieldIdx)
                        val newQuery = field.serialize(raw) match
                            case Present(q) => q :: s.queryParts
                            case Absent     => s.queryParts
                        loop(i + 1, fieldIdx + 1, s.copy(queryParts = newQuery))

                    case field @ InputField.Header(_, _, _, _, _, _) =>
                        val raw = extractAt(in, fieldIdx)
                        val newHeaders = field.serializeHeader(raw) match
                            case Present((n, v)) => s.headers.add(n, v)
                            case Absent          => s.headers
                        loop(i + 1, fieldIdx + 1, s.copy(headers = newHeaders))

                    case field @ InputField.Cookie(_, _, _, _, _, _) =>
                        val raw = extractAt(in, fieldIdx)
                        val newCookies = field.serialize(raw) match
                            case Present(c) => c :: s.cookieParts
                            case Absent     => s.cookieParts
                        loop(i + 1, fieldIdx + 1, s.copy(cookieParts = newCookies))

                    case InputField.FormBody(codec, _) =>
                        val raw      = extractAt(in, fieldIdx)
                        val formBody = codec.serialize(raw)
                        loop(i + 1, fieldIdx + 1, s.copy(formParts = formBody :: s.formParts))

                    case InputField.Body(content, _) =>
                        loop(i + 1, fieldIdx + 1, s.copy(bodyValue = Present(extractAt(in, fieldIdx)), bodyContent = Present(content)))

                    case InputField.Auth(scheme) =>
                        val next = scheme match
                            case AuthScheme.Bearer =>
                                val token = extractAt(in, fieldIdx)
                                s.copy(headers = s.headers.add("Authorization", s"Bearer $token"))
                            case AuthScheme.BasicUsername =>
                                val username = extractAt(in, fieldIdx)
                                val password = extractAt(in, fieldIdx + 1)
                                val encoded  = java.util.Base64.getEncoder.encodeToString(s"$username:$password".getBytes("UTF-8"))
                                s.copy(headers = s.headers.add("Authorization", s"Basic $encoded"))
                            case AuthScheme.BasicPassword =>
                                s // already handled by BasicUsername
                            case AuthScheme.ApiKey(name, location) =>
                                val value = extractAt(in, fieldIdx)
                                location match
                                    case AuthLocation.Header =>
                                        s.copy(headers = s.headers.add(name, value.toString))
                                    case AuthLocation.Query =>
                                        s.copy(queryParts =
                                            s"$name=${java.net.URLEncoder.encode(value.toString, "UTF-8")}" :: s.queryParts
                                        )
                                    case AuthLocation.Cookie =>
                                        s.copy(cookieParts = s"$name=$value" :: s.cookieParts)
                                end match
                        loop(i + 1, fieldIdx + 1, next)

        val state = loop(0, pathCount, RouteRequestState())
        val headers =
            if state.cookieParts.nonEmpty then state.headers.add("Cookie", state.cookieParts.reverse.mkString("; ")) else state.headers
        val queryStr  = state.queryParts.reverse.mkString("&")
        val fullPath  = if queryStr.isEmpty then pathStr else s"$pathStr?$queryStr"
        val bodyValue = state.bodyValue.getOrElse(null)

        if state.formParts.nonEmpty then
            val formBody = Span.fromUnsafe(state.formParts.reverse.mkString("&").getBytes("UTF-8"))
            HttpRequest.initBytes(route.method, fullPath, formBody, headers, "application/x-www-form-urlencoded")
        else
            state.bodyContent match
                case Present(Content.Multipart) =>
                    HttpRequest.multipart(fullPath, bodyValue.asInstanceOf[Seq[HttpRequest.Part]], headers)
                case Present(streamContent: Content.StreamInput) =>
                    HttpRequest.stream(route.method, fullPath, streamContent.encodeStreamTo(bodyValue), headers)
                case Present(content: Content.BytesInput) =>
                    content.encodeTo(bodyValue) match
                        case Present((bytes, contentType)) =>
                            HttpRequest.initBytes(route.method, fullPath, Span.fromUnsafe(bytes), headers, contentType)
                        case Absent =>
                            HttpRequest.initBytes(route.method, fullPath, Span.empty[Byte], headers, "")
                case _ =>
                    HttpRequest.initBytes(route.method, fullPath, Span.empty[Byte], headers, "")
            end match
        end if
    end buildRouteRequest

    private def handleErrorResponse[Err](
        route: HttpRoute[?, ?, ?, Err],
        responseStatus: HttpStatus,
        body: String
    )(using Frame): Nothing < Abort[Err | HttpError] =
        @tailrec def tryMappings(remaining: Seq[ErrorMapping]): Nothing < Abort[Err | HttpError] =
            remaining match
                case Seq() => Abort.fail(HttpError.InvalidResponse(s"HTTP error: $responseStatus"))
                case mapping +: tail =>
                    mapping.decode(responseStatus, body) match
                        // Err is a union type from error mappings — each mapping.decode returns its specific type.
                        // The cast is safe because the decoded value came from a Schema[E] where E is part of Err.
                        case Present(err) => Abort.fail(err.asInstanceOf[Err])
                        case Absent       => tryMappings(tail)
        tryMappings(route.response.errorMappings)
    end handleErrorResponse

    // --- Context management ---

    /** Swaps the client instance for the given computation. */
    def let[A, S](client: HttpClient)(v: A < S)(using Frame): A < S =
        clientLocal.let(client)(v)

    /** Applies a config transformation for the given computation (stacks with current config). */
    def withConfig[A, S](f: Config => Config)(v: A < S)(using Frame): A < S =
        configLocal.use(c => configLocal.let(f(c))(v))

    // --- Factory methods ---

    /** Scope-managed client lifecycle. Automatically shuts down the connection pool on scope exit. */
    def init(
        maxConnectionsPerHost: Maybe[Int] = DefaultMaxConnectionsPerHost,
        connectionAcquireTimeout: Duration = DefaultConnectionAcquireTimeout,
        maxResponseSizeBytes: Int = DefaultMaxResponseSizeBytes,
        daemon: Boolean = false,
        backend: Backend.Client = HttpPlatformBackend.client
    )(using Frame): HttpClient < (Sync & Scope) =
        initWith(maxConnectionsPerHost, connectionAcquireTimeout, maxResponseSizeBytes, daemon, backend)(identity)

    def initWith[B, S](
        maxConnectionsPerHost: Maybe[Int] = DefaultMaxConnectionsPerHost,
        connectionAcquireTimeout: Duration = DefaultConnectionAcquireTimeout,
        maxResponseSizeBytes: Int = DefaultMaxResponseSizeBytes,
        daemon: Boolean = false,
        backend: Backend.Client = HttpPlatformBackend.client
    )(f: HttpClient => B < S)(using Frame): B < (S & Sync & Scope) =
        Scope.acquireRelease(initUnscoped(
            maxConnectionsPerHost,
            connectionAcquireTimeout,
            maxResponseSizeBytes,
            daemon,
            backend
        ))(_.closeNow).map(f)

    /** No automatic shutdown. Caller must close explicitly. */
    def initUnscoped(
        maxConnectionsPerHost: Maybe[Int] = DefaultMaxConnectionsPerHost,
        connectionAcquireTimeout: Duration = DefaultConnectionAcquireTimeout,
        maxResponseSizeBytes: Int = DefaultMaxResponseSizeBytes,
        daemon: Boolean = false,
        backend: Backend.Client = HttpPlatformBackend.client
    )(using Frame): HttpClient < Sync =
        initUnscopedWith(maxConnectionsPerHost, connectionAcquireTimeout, maxResponseSizeBytes, daemon, backend)(identity)

    def initUnscopedWith[B, S](
        maxConnectionsPerHost: Maybe[Int] = DefaultMaxConnectionsPerHost,
        connectionAcquireTimeout: Duration = DefaultConnectionAcquireTimeout,
        maxResponseSizeBytes: Int = DefaultMaxResponseSizeBytes,
        daemon: Boolean = false,
        backend: Backend.Client = HttpPlatformBackend.client
    )(f: HttpClient => B < S)(using Frame): B < (S & Sync) =
        Sync.Unsafe.defer {
            Unsafe.init(maxConnectionsPerHost, connectionAcquireTimeout, maxResponseSizeBytes, daemon, backend)
        }.map(f)
    end initUnscopedWith

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        /** Low-level client initialization requiring AllowUnsafe. */
        def init(
            maxConnectionsPerHost: Maybe[Int] = DefaultMaxConnectionsPerHost,
            connectionAcquireTimeout: Duration = DefaultConnectionAcquireTimeout,
            maxResponseSizeBytes: Int = DefaultMaxResponseSizeBytes,
            daemon: Boolean = false,
            backend: Backend.Client = HttpPlatformBackend.client
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

    /** Per-request configuration for the HTTP client pipeline. Applied via `Local` with `withConfig`.
      *
      * Note: `retryOn` defaults to retrying on server errors (5xx). Override with `retryWhen` for custom logic.
      */
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

    private[kyo] inline def DefaultHttpPort  = HttpRequest.DefaultHttpPort
    private[kyo] inline def DefaultHttpsPort = HttpRequest.DefaultHttpsPort

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

    // Fails on 4xx/5xx (isError = status >= 400). 3xx responses pass through.
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

    /** Parse a URL into (host, port, ssl, rawPath, rawQuery) components without allocating intermediate objects. */
    private[kyo] inline def parseUrl[A](url: String)(
        inline f: (String, Int, Boolean, String, Maybe[String]) => A
    ): A =
        UrlParser.parseUrlParts(url) { (scheme, host, port, rawPath, rawQuery) =>
            val ssl           = scheme.contains("https")
            val effectivePort = if port < 0 then (if ssl then DefaultHttpsPort else DefaultHttpPort) else port
            // Strip IPv6 brackets for networking (InetAddress expects raw IP)
            val rawHost = host.getOrElse("") match
                case h if h.startsWith("[") && h.endsWith("]") => h.substring(1, h.length - 1)
                case h                                         => h
            f(rawHost, effectivePort, ssl, rawPath, rawQuery)
        }

    /** Send request via connection pool, acquiring and releasing a connection. */
    private def sendViaPool(
        pool: ConnectionPool,
        url: String,
        request: HttpRequest[HttpBody.Bytes],
        config: Config
    )(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        parseUrl(url) { (host, port, ssl, rawPath, rawQuery) =>
            val key         = ConnectionPool.PoolKey(host, port, ssl)
            val reqWithPath = request.withParsedUrl(rawPath, rawQuery)
            pool.acquire(key, config.connectTimeout).map { conn =>
                AtomicBoolean.init(false).map { completed =>
                    Sync.ensure {
                        import AllowUnsafe.embrace.danger
                        if !completed.unsafe.get() then conn.closeAbruptly()
                        pool.release(key, conn)
                    } {
                        conn.send(reqWithPath).map { resp =>
                            completed.set(true).andThen(resp)
                        }
                    }
                }
            }
        }

    /** Apply request-level timeout if configured. */
    private def applyTimeout(
        config: Config,
        doSend: HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError])
    )(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        config.timeout match
            case Present(d) =>
                Abort.run[kyo.Timeout](Async.timeout(d)(doSend)).map {
                    case Result.Success(resp) => resp
                    case Result.Failure(_)    => Abort.fail(HttpError.Timeout(s"Request timed out after $d"))
                    case Result.Panic(e)      => throw e
                }
            case Absent => doSend

    /** Follow HTTP redirects if configured. */
    private def handleRedirect(
        pool: ConnectionPool,
        url: String,
        request: HttpRequest[HttpBody.Bytes],
        config: Config,
        resp: HttpResponse[HttpBody.Bytes],
        redirectCount: Int,
        retrySchedule: Maybe[Schedule],
        attemptCount: Int
    )(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
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
                                new URI(url).resolve(location).toString
                        sendWithPolicies(pool, redirectUrl, request, config, redirectCount + 1, retrySchedule, attemptCount)
                    case Absent =>
                        resp
        else
            handleRetry(pool, url, request, config, resp, retrySchedule, attemptCount)

    /** Retry on retriable responses using the configured schedule. */
    private def handleRetry(
        pool: ConnectionPool,
        url: String,
        request: HttpRequest[HttpBody.Bytes],
        config: Config,
        resp: HttpResponse[HttpBody.Bytes],
        retrySchedule: Maybe[Schedule],
        attemptCount: Int
    )(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        retrySchedule match
            case Present(schedule) if config.retryOn(resp) =>
                Clock.now.map { now =>
                    schedule.next(now) match
                        case Present((delay, nextSchedule)) =>
                            Async.delay(delay) {
                                sendWithPolicies(pool, url, request, config, 0, Present(nextSchedule), attemptCount + 1)
                            }
                        case Absent =>
                            Abort.fail(HttpError.RetriesExhausted(attemptCount, resp.status, resp.bodyText))
                }
            case _ =>
                resp

    // Pipeline: send via pool → apply timeout → follow redirects → retry
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
        applyTimeout(config, sendViaPool(pool, url, request, config)).map { resp =>
            handleRedirect(pool, url, request, config, resp, redirectCount, currentSchedule, attemptCount)
        }
    end sendWithPolicies

    /** Attaches a connection finalizer to the response's body stream so it runs when consumed within a Scope. */
    private def scopeStream(
        response: HttpResponse[HttpBody.Streamed],
        finalizer: Unit < Async
    )(using Frame): HttpResponse[HttpBody.Streamed] =
        val original = response.body.stream
        // Avoid inline Stream.apply to prevent Scope from leaking into the enclosing effect type.
        val scoped = new Stream[Span[Byte], Async & Scope]:
            def emit: Unit < (Emit[Chunk[Span[Byte]]] & Async & Scope) =
                Scope.ensure(finalizer).andThen(original.emit)
        response.withBody(HttpBody.stream(scoped))
    end scopeStream

    // --- Route call helpers ---

    private def hasStreamingInput(route: HttpRoute[?, ?, ?, ?]): Boolean =
        route.request.inputFields.exists(_.isStreaming)

    private def hasStreamingOutput(route: HttpRoute[?, ?, ?, ?]): Boolean =
        route.response.outputFields.exists(_.isStreaming)

    private def buildRoutePath(path: HttpPath[?], in: Any, offset: Int): (String, Int) =
        path match
            case HttpPath.Literal(s) => (s, offset)
            case capture: HttpPath.Capture[?, ?] =>
                val value = extractAt(in, offset)
                // Cast is at the boundary: value came from a typed InputValue tuple,
                // but we lost the type when storing in Any. The capture's codec type matches.
                ("/" + capture.serializeValue(value.asInstanceOf), offset + 1)
            case HttpPath.Concat(left, right) =>
                val (leftStr, nextOffset)  = buildRoutePath(left, in, offset)
                val (rightStr, lastOffset) = buildRoutePath(right, in, nextOffset)
                if rightStr.startsWith("/") then (leftStr + rightStr, lastOffset)
                else (leftStr + "/" + rightStr, lastOffset)
            case HttpPath.Rest(_) =>
                val value = extractAt(in, offset)
                ("/" + value, offset + 1)

    private def extractAt(in: Any, idx: Int): Any =
        in match
            case tuple: Tuple => tuple.productElement(idx)
            case other =>
                if idx == 0 then other
                else throw new IllegalStateException(s"Cannot extract input at index $idx from $other")

    private def decodeBufferedBodyRaw[Out <: AnyNamedTuple](
        route: HttpRoute[?, ?, Out, ?],
        response: HttpResponse[HttpBody.Bytes]
    )(using Frame): Any < Abort[HttpError] =
        findBodyField(route.response.outputFields) match
            case Present(bodyField) => bodyField.extract(response)
            case Absent             => ()

    private def decodeStreamBody[Out <: AnyNamedTuple](
        route: HttpRoute[?, ?, Out, ?],
        response: HttpResponse[HttpBody.Streamed]
    )(using Frame): Any < (Async & Abort[HttpError]) =
        findBodyField(route.response.outputFields) match
            case Present(bodyField) => bodyField.extractStream(response)
            case Absent             => ()

    private def buildOutputRow[Out <: AnyNamedTuple](
        route: HttpRoute[?, ?, Out, ?],
        body: Any,
        response: HttpResponse[?]
    ): Row[FullOutput[Out]] =
        val fields    = route.response.outputFields
        val bodyCount = fields.count(_.isInstanceOf[OutputField.Body])
        // Build tuple: output fields + response
        val values = Array.ofDim[Any](fields.size + 1)
        if bodyCount > 0 then values(0) = body
        values(values.length - 1) = response
        Tuple.fromArray(values).asInstanceOf[Row[FullOutput[Out]]]
    end buildOutputRow

    private def findBodyField(fields: Seq[OutputField]): Maybe[OutputField] =
        @tailrec def loop(i: Int): Maybe[OutputField] =
            if i >= fields.size then Absent
            else
                fields(i) match
                    case body @ OutputField.Body(_, _) => Present(body)
                    case _                             => loop(i + 1)
        loop(0)
    end findBodyField

end HttpClient
