package kyo

import HttpRoute.*
import java.net.URI
import kyo.internal.ConnectionPool
import kyo.internal.Content
import kyo.internal.NdjsonDecoder
import kyo.internal.SseDecoder
import kyo.internal.UrlParser
import scala.NamedTuple.AnyNamedTuple
import scala.annotation.implicitNotFound
import scala.util.NotGiven

/** HTTP client for sending requests with connection pooling, automatic retries, and redirect following.
  *
  * Dual API: instance methods for per-client operations, companion methods delegate through a shared `Local`-backed client. Most code uses
  * companion convenience methods; explicit `init` only when custom pool config is needed.
  *
  * Connection lifecycle: pooled connections for buffered `send`, non-pooled scoped connections for `stream`. Connections are pooled per
  * (host, port, ssl) triple. Request pipeline: filters → base URL resolution → timeout → redirect following → retry with `Schedule`. Each
  * concern is layered independently.
  *
  * Two API tiers on the companion: `send` returns the response regardless of status code, while typed convenience methods (`get`, `post`,
  * `put`, `delete`) auto-deserialize via `Schema` and fail with `HttpError.StatusError` on error status (4xx/5xx).
  *
  *   - Buffered send with connection pooling and automatic keep-alive
  *   - Streaming responses (SSE, NDJSON, raw byte streams)
  *   - Automatic redirect following with configurable limit
  *   - Retry with `Schedule`-based policies
  *   - Per-request timeout and connect timeout support
  *   - `Config` for base URL, timeout, redirect, and retry settings (request-level via `Local`)
  *   - `warmupUrl`/`warmupUrls` for pre-establishing connections
  *   - Route-based typed client via `call`
  *
  * IMPORTANT: `send` returns any response including errors. The typed convenience methods (`get`, `post`, etc.) fail with
  * `HttpError.StatusError` on error status (4xx/5xx). 3xx responses (when redirects are disabled) pass through without error.
  *
  * IMPORTANT: `stream` bypasses the redirect/retry/timeout pipeline entirely. Streaming requests only get filters, base URL resolution, and
  * connect timeout. They do NOT get redirect following, retry, or request timeout. The connection is non-pooled and scoped to the enclosing
  * `Scope`.
  *
  * Note: `Config` is per-request (applied via `Local` with `withConfig`), not per-client. Pool-level settings (maxConnectionsPerHost, etc.)
  * are set at `init` time.
  *
  * Note: The shared client uses daemon threads so the JVM can exit without explicit shutdown.
  *
  * @see
  *   [[kyo.HttpRequest]]
  * @see
  *   [[kyo.HttpResponse]]
  * @see
  *   [[kyo.HttpFilter]]
  * @see
  *   [[kyo.HttpError]]
  * @see
  *   [[kyo.HttpServer]]
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

    /** Streams the response body. WARNING: bypasses redirect/retry/timeout — only filters, base URL, and connect timeout apply. Uses a
      * non-pooled connection closed when the enclosing Scope exits.
      */
    def stream(request: HttpRequest[?])(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Scope & Abort[HttpError]) =
        HttpFilter.use { filter =>
            HttpClient.configLocal.use { config =>
                def doStream(req: HttpRequest[?]) =
                    def loop(
                        currentReq: HttpRequest[?],
                        redirectCount: Int
                    ): HttpResponse[HttpBody.Streamed] < (Async & Scope & Abort[HttpError]) =
                        val effectiveUrl = HttpClient.buildEffectiveUrl(config, currentReq)
                        HttpClient.parseUrl(effectiveUrl) { (host, port, ssl, rawPath, rawQuery) =>
                            pool.connectDirect(host, port, ssl, config.connectTimeout).map { conn =>
                                val reqWithPath = currentReq.withParsedUrl(rawPath, rawQuery)
                                Scope.ensure(conn.close).andThen(conn.stream(reqWithPath)).map { response =>
                                    // Follow 301/302 redirects (drop body, switch to GET).
                                    // Skip 307/308 — streaming body can't be replayed.
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
                                            case Absent => response
                                    else response
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
                        // A filter can short-circuit with a cached/mocked buffered response.
                        // Wrap the bytes as a single-chunk stream so the streaming contract holds.
                        response.body.use(
                            b => response.withBody(HttpBody.stream(Stream.init(Chunk(b.span)))),
                            s => response.withBody(s)
                        )
                    }
                end if
            }
        }

    def send(url: String)(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        send(HttpRequest.get(url))

    def stream(url: String)(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Scope & Abort[HttpError]) =
        stream(HttpRequest.get(url))

    /** Streams Server-Sent Events from the given URL. */
    def streamSse[V: Schema: Tag](url: String)(using
        Frame,
        Tag[Emit[Chunk[HttpEvent[V]]]]
    ): Stream[HttpEvent[V], Async] < (Async & Scope & Abort[HttpError]) =
        streamSse[V](HttpRequest.get(url))

    /** Streams Server-Sent Events from the given request. */
    def streamSse[V: Schema: Tag](request: HttpRequest[?])(using
        Frame,
        Tag[Emit[Chunk[HttpEvent[V]]]]
    ): Stream[HttpEvent[V], Async] < (Async & Scope & Abort[HttpError]) =
        stream(request).map { response =>
            val decoder = new SseDecoder[V](Schema[V])
            response.bodyStream.mapChunkPure[Span[Byte], HttpEvent[V]] { chunk =>
                val result = Seq.newBuilder[HttpEvent[V]]
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
        Tag[Emit[Chunk[HttpEvent[V]]]]
    ): Stream[HttpEvent[V], Async] < (Async & Scope & Abort[HttpError]) =
        clientLocal.use(_.streamSse[V](url))

    def streamSse[V: Schema: Tag](request: HttpRequest[?])(using
        Frame,
        Tag[Emit[Chunk[HttpEvent[V]]]]
    ): Stream[HttpEvent[V], Async] < (Async & Scope & Abort[HttpError]) =
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

    // --- Route-based client ---

    /** Calls a route with no inputs (no path captures, query, header, or body params). */
    def call[Out <: AnyNamedTuple, Err](route: HttpRoute[Row.Empty, Row.Empty, Out, Err])(using
        Frame,
        BufferedOutput[OutputValue[Out]]
    ): OutputValue[Out] < (Async & Abort[HttpError] & Abort[Err]) =
        callImpl(route, EmptyTuple)

    /** Calls a route with typed inputs. The input tuple contains path captures followed by request params, in declaration order.
      */
    def call[PathIn <: AnyNamedTuple, In <: AnyNamedTuple, Out <: AnyNamedTuple, Err](
        route: HttpRoute[PathIn, In, Out, Err],
        in: InputValue[PathIn, In]
    )(using Frame, BufferedOutput[OutputValue[Out]]): OutputValue[Out] < (Async & Abort[HttpError] & Abort[Err]) =
        callImpl(route, in)

    private def callImpl[Out <: AnyNamedTuple, Err](
        route: HttpRoute[?, ?, Out, Err],
        in: Any
    )(using Frame, BufferedOutput[OutputValue[Out]]): OutputValue[Out] < (Async & Abort[HttpError] & Abort[Err]) =
        val request          = buildRouteRequest(route, in)
        val isStreamingInput = hasStreamingInput(route)
        if isStreamingInput then
            Scope.run {
                stream(request).map { response =>
                    response.ensureBytes.map { buffered =>
                        if buffered.status.isError then
                            handleErrorResponse(route, buffered.status, buffered.bodyText)
                        else
                            decodeBufferedBody[Out](route, buffered)
                    }
                }
            }
        else
            send(request.asInstanceOf[HttpRequest[HttpBody.Bytes]]).map { response =>
                if response.status.isError then
                    handleErrorResponse(route, response.status, response.bodyText)
                else
                    decodeBufferedBody[Out](route, response)
            }
        end if
    end callImpl

    /** Calls a route with streaming output (SSE, NDJSON). Requires `Scope` for the streaming connection lifecycle. */
    def callStream[Out <: AnyNamedTuple, Err](route: HttpRoute[Row.Empty, Row.Empty, Out, Err])(using
        Frame
    ): OutputValue[Out] < (Async & Scope & Abort[HttpError] & Abort[Err]) =
        callStreamImpl(route, EmptyTuple)

    /** Calls a streaming route with typed inputs. */
    def callStream[PathIn <: AnyNamedTuple, In <: AnyNamedTuple, Out <: AnyNamedTuple, Err](
        route: HttpRoute[PathIn, In, Out, Err],
        in: InputValue[PathIn, In]
    )(using Frame): OutputValue[Out] < (Async & Scope & Abort[HttpError] & Abort[Err]) =
        callStreamImpl(route, in)

    private def callStreamImpl[Out <: AnyNamedTuple, Err](
        route: HttpRoute[?, ?, Out, Err],
        in: Any
    )(using Frame): OutputValue[Out] < (Async & Scope & Abort[HttpError] & Abort[Err]) =
        val request = buildRouteRequest(route, in)
        stream(request).map { response =>
            if response.status.isError then
                response.ensureBytes.map { buffered =>
                    handleErrorResponse(route, buffered.status, buffered.bodyText)
                }
            else
                decodeStreamBody[Out](route, response)
        }
    end callStreamImpl

    private def buildRouteRequest(route: HttpRoute[?, ?, ?, ?], in: Any): HttpRequest[?] =
        val (pathStr, pathCount)              = buildRoutePath(route.path, in, 0)
        var queryParts                        = List.empty[String]
        var headers                           = HttpHeaders.empty
        var cookieParts                       = List.empty[String]
        var bodyValue: Any                    = null
        var bodyContent: Content.Input | Null = null

        var fieldIdx = pathCount
        route.request.inputFields.foreach:
            case InputField.Query(name, codec, _, optional, _) =>
                val raw = extractAt(in, fieldIdx)
                if optional then
                    raw.asInstanceOf[Maybe[Any]] match
                        case Present(v) =>
                            queryParts :+= s"$name=${java.net.URLEncoder.encode(codec.asInstanceOf[HttpCodec[Any]].serialize(v), "UTF-8")}"
                        case Absent => ()
                else
                    queryParts :+= s"$name=${java.net.URLEncoder.encode(codec.asInstanceOf[HttpCodec[Any]].serialize(raw), "UTF-8")}"
                end if
                fieldIdx += 1

            case InputField.Header(name, codec, _, optional, _) =>
                val raw = extractAt(in, fieldIdx)
                if optional then
                    raw.asInstanceOf[Maybe[Any]] match
                        case Present(v) => headers = headers.add(name, codec.asInstanceOf[HttpCodec[Any]].serialize(v))
                        case Absent     => ()
                else
                    headers = headers.add(name, codec.asInstanceOf[HttpCodec[Any]].serialize(raw))
                end if
                fieldIdx += 1

            case InputField.Cookie(name, codec, _, optional, _) =>
                val raw = extractAt(in, fieldIdx)
                if optional then
                    raw.asInstanceOf[Maybe[Any]] match
                        case Present(v) => cookieParts :+= s"$name=${codec.asInstanceOf[HttpCodec[Any]].serialize(v)}"
                        case Absent     => ()
                else
                    cookieParts :+= s"$name=${codec.asInstanceOf[HttpCodec[Any]].serialize(raw)}"
                end if
                fieldIdx += 1

            case InputField.Body(content, _) =>
                bodyValue = extractAt(in, fieldIdx)
                bodyContent = content
                fieldIdx += 1

            case InputField.Auth(scheme) =>
                scheme match
                    case AuthScheme.Bearer =>
                        val token = extractAt(in, fieldIdx)
                        headers = headers.add("Authorization", s"Bearer $token")
                    case AuthScheme.BasicUsername =>
                        val username = extractAt(in, fieldIdx)
                        val password = extractAt(in, fieldIdx + 1) // peek ahead — BasicPassword follows
                        val encoded  = java.util.Base64.getEncoder.encodeToString(s"$username:$password".getBytes("UTF-8"))
                        headers = headers.add("Authorization", s"Basic $encoded")
                    case AuthScheme.BasicPassword =>
                        () // already handled by BasicUsername
                    case AuthScheme.ApiKey(name, location) =>
                        val value = extractAt(in, fieldIdx)
                        location match
                            case AuthLocation.Header =>
                                headers = headers.add(name, value.toString)
                            case AuthLocation.Query =>
                                queryParts :+= s"$name=${java.net.URLEncoder.encode(value.toString, "UTF-8")}"
                            case AuthLocation.Cookie =>
                                cookieParts :+= s"$name=$value"
                        end match
                end match
                fieldIdx += 1

        if cookieParts.nonEmpty then
            headers = headers.add("Cookie", cookieParts.mkString("; "))

        val queryStr = queryParts.mkString("&")
        val fullPath = if queryStr.isEmpty then pathStr else s"$pathStr?$queryStr"

        bodyContent match
            case Content.Multipart =>
                HttpRequest.multipart(fullPath, bodyValue.asInstanceOf[Seq[HttpRequest.Part]], headers)
            case Content.ByteStream =>
                HttpRequest.stream(route.method, fullPath, bodyValue.asInstanceOf[Stream[Span[Byte], Async]], headers)
            case Content.MultipartStream =>
                throw new UnsupportedOperationException(
                    "Streaming multipart client calls are not yet supported. Use bodyMultipart for buffered multipart."
                )
            case Content.Json(schema) =>
                val encoded = schema.asInstanceOf[Schema[Any]].encode(bodyValue)
                HttpRequest.initBytes(route.method, fullPath, encoded.getBytes("UTF-8"), headers, "application/json")
            case Content.Text =>
                HttpRequest.initBytes(route.method, fullPath, bodyValue.toString.getBytes("UTF-8"), headers, "text/plain")
            case Content.Binary =>
                val bytes = bodyValue.asInstanceOf[Span[Byte]]
                HttpRequest.initBytes(route.method, fullPath, bytes.toArray, headers, "application/octet-stream")
            case Content.Form(schema) =>
                val encoded = schema.asInstanceOf[Schema[Any]].encode(bodyValue)
                HttpRequest.initBytes(route.method, fullPath, encoded.getBytes("UTF-8"), headers, "application/x-www-form-urlencoded")
            case Content.Ndjson(schema, _) =>
                given Frame = Frame.internal
                val ndjsonStream = bodyValue.asInstanceOf[Stream[Any, Async]].map { v =>
                    Span.fromUnsafe((schema.asInstanceOf[Schema[Any]].encode(v) + "\n").getBytes("UTF-8"))
                }
                HttpRequest.stream(route.method, fullPath, ndjsonStream, headers)
            case null =>
                HttpRequest.initBytes(route.method, fullPath, Array.empty[Byte], headers, "")
        end match
    end buildRouteRequest

    private def handleErrorResponse[Err](
        route: HttpRoute[?, ?, ?, Err],
        responseStatus: HttpStatus,
        body: String
    )(using Frame): Nothing < Abort[Err | HttpError] =
        val statusCode = responseStatus.code
        val errOpt = route.response.errorMappings.collectFirst {
            case mapping if mapping.status.code == statusCode =>
                try Some(mapping.schema.asInstanceOf[Schema[Any]].decode(body))
                catch case _: Exception => None
        }.flatten
        errOpt match
            case Some(err) => Abort.fail(err.asInstanceOf[Err])
            case None      => Abort.fail(HttpError.InvalidResponse(s"HTTP error: $responseStatus"))
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

    // --- Route call helpers ---

    private def hasStreamingInput(route: HttpRoute[?, ?, ?, ?]): Boolean =
        route.request.inputFields.exists:
            case InputField.Body(Content.ByteStream | Content.MultipartStream | Content.Ndjson(_, _), _) => true
            case _                                                                                       => false

    private def buildRoutePath(path: HttpPath[?], in: Any, offset: Int): (String, Int) =
        path match
            case HttpPath.Literal(s) => (s, offset)
            case HttpPath.Capture(_, _, codec) =>
                val value   = extractAt(in, offset)
                val encoded = java.net.URLEncoder.encode(codec.asInstanceOf[HttpCodec[Any]].serialize(value), "UTF-8")
                ("/" + encoded, offset + 1)
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

    private def decodeBufferedBody[Out <: AnyNamedTuple](
        route: HttpRoute[?, ?, Out, ?],
        response: HttpResponse[HttpBody.Bytes]
    ): OutputValue[Out] =
        findBodyContent(route.response.outputFields) match
            case Present(Content.Json(schema)) =>
                schema.decode(response.bodyText).asInstanceOf[OutputValue[Out]]
            case Present(Content.Text) =>
                response.bodyText.asInstanceOf[OutputValue[Out]]
            case Present(Content.Binary) =>
                response.bodyBytes.asInstanceOf[OutputValue[Out]]
            case _ =>
                ().asInstanceOf[OutputValue[Out]]

    private def decodeStreamBody[Out <: AnyNamedTuple](
        route: HttpRoute[?, ?, Out, ?],
        response: HttpResponse[HttpBody.Streamed]
    )(using Frame): OutputValue[Out] < (Async & Scope) =
        findBodyContent(route.response.outputFields) match
            case Present(Content.Sse(schema, emitTag)) =>
                val decoder                            = new SseDecoder[Any](schema)
                given Tag[Emit[Chunk[HttpEvent[Any]]]] = emitTag.asInstanceOf[Tag[Emit[Chunk[HttpEvent[Any]]]]]
                response.bodyStream.mapChunkPure[Span[Byte], HttpEvent[Any]] { chunk =>
                    val result = Seq.newBuilder[HttpEvent[Any]]
                    chunk.foreach(bytes => result ++= decoder.decode(bytes))
                    result.result()
                }.asInstanceOf[OutputValue[Out]]
            case Present(Content.Ndjson(schema, emitTag)) =>
                val decoder                 = new NdjsonDecoder[Any](schema)
                given Tag[Emit[Chunk[Any]]] = emitTag.asInstanceOf[Tag[Emit[Chunk[Any]]]]
                response.bodyStream.mapChunkPure[Span[Byte], Any] { chunk =>
                    val result = Seq.newBuilder[Any]
                    chunk.foreach(bytes => result ++= decoder.decode(bytes))
                    result.result()
                }.asInstanceOf[OutputValue[Out]]
            case Present(Content.Json(schema)) =>
                response.ensureBytes.map { buffered =>
                    schema.decode(buffered.bodyText).asInstanceOf[OutputValue[Out]]
                }
            case _ =>
                ().asInstanceOf[OutputValue[Out]]

    private def findBodyContent(fields: Seq[OutputField]): Maybe[Content.Output] =
        var i = 0
        while i < fields.size do
            fields(i) match
                case OutputField.Body(content, _) => return Present(content)
                case _                            => ()
            i += 1
        end while
        Absent
    end findBodyContent

    /** Evidence that `Out` is not a streaming type. Prevents `call()` from being used with `outputSse`/`outputNdjson` routes. */
    @implicitNotFound("call() cannot be used with streaming output routes (bodySse/bodyNdjson). Use callStream() instead.")
    sealed trait BufferedOutput[A]
    object BufferedOutput:
        given [A](using NotGiven[A <:< Stream[?, ?]]): BufferedOutput[A] with {}

end HttpClient
