package kyo.internal.client

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kyo.*
import kyo.internal.codec.*
import kyo.internal.http1.*
import kyo.internal.server.RouteUtil
import kyo.internal.transport.*
import kyo.internal.util.*
import kyo.internal.websocket.WebSocketCodec
import kyo.scheduler.IOPromise

/** HTTP client backend that combines transport, protocol, pool, and orchestration layers.
  *
  * Responsibilities (innermost to outermost):
  *   1. connect/send/receive (unsafe, promise-based) via Transport + Http1ClientConnection
  *   2. Body reading: buffered (readLoopUnsafe + ChunkedBodyDecoder) and streaming
  *   3. Pool management: acquire from ConnectionPool, release on completion, track all connections
  *   4. Orchestration: timeout, redirect, retry (retryWith, timeoutWith, poolWith)
  *
  * All I/O methods that touch sockets are unsafe and named with an "Unsafe" suffix or take AllowUnsafe. The safe orchestration layer
  * bridges via fiber.safe.get and Sync.ensure.
  *
  * sendWithConfig is the main entry point from HttpClient. It applies base URL resolution, then delegates to retryWith -> timeoutWith ->
  * poolWith.
  */
final private[kyo] class HttpClientBackend[Handle] private (
    transport: Transport[Handle],
    transportConfig: HttpTransportConfig,
    defaultTlsConfig: HttpTlsConfig,
    private val pool: ConnectionPool[HttpConnection[Handle]],
    private val allConnections: ConcurrentHashMap[HttpConnection[Handle], Unit],
    val maxConnectionsPerHost: Int,
    val clientFrame: Frame
):
    private val CrLf          = Span.fromUnsafe(Http1StreamContext.CRLF)
    private val TerminalChunk = Span.fromUnsafe(Http1StreamContext.LAST_CHUNK)

    private def hostPort(url: HttpUrl): (String, Int) =
        url.unixSocket match
            case Present(path) => (s"unix:$path", 0)
            case Absent        => (url.host, url.port)

    def connect(url: HttpUrl, connectTimeout: Duration, tlsConfig: HttpTlsConfig)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[HttpConnection[Handle], Abort[HttpException]] =
        // Merge TLS configs: use defaultTlsConfig as base, override with explicitly set fields
        val effectiveTls =
            if tlsConfig == HttpTlsConfig.default then defaultTlsConfig
            else tlsConfig
        val connectFiber = (url.unixSocket, url.ssl) match
            case (Present(path), _) => transport.connectUnix(path)
            case (_, true)          => transport.connect(url.host, url.port, effectiveTls)
            case _                  => transport.connect(url.host, url.port)
        val resultPromise = Promise.Unsafe.init[HttpConnection[Handle], Abort[HttpException]]()
        // Cast to IOPromise to get Result[Closed, transport.Connection] directly (no `< S` wrapper).
        // Fiber.Unsafe is an opaque wrapper over IOPromise - at runtime they are the same object.
        connectFiber.asInstanceOf[IOPromise[Closed, kyo.internal.transport.Connection[Handle]]].onComplete { result =>
            result match
                case Result.Success(transportConn) =>
                    try
                        val http1 = Http1ClientConnection.init(
                            transportConn.inbound,
                            transportConn.outbound,
                            transportConfig.maxHeaderSize
                        )
                        val host            = url.host
                        val port            = url.port
                        val isDefaultPort   = if url.ssl then port == 443 else port == 80
                        val hostHeaderValue = if isDefaultPort || host.isEmpty then host else s"$host:$port"
                        val conn            = new HttpConnection(transportConn, http1, host, port, url.ssl, hostHeaderValue)
                        resultPromise.completeDiscard(Result.succeed(conn))
                    catch
                        case t: Throwable =>
                            resultPromise.completeDiscard(Result.panic(t))
                    end try
                case Result.Failure(closed) =>
                    val (h, p) = hostPort(url)
                    resultPromise.completeDiscard(
                        Result.fail(HttpConnectException(h, p, new IOException(closed.getMessage)))
                    )
                case Result.Panic(t) =>
                    resultPromise.completeDiscard(Result.panic(t))
        }
        resultPromise
    end connect

    def sendBuffered[In, Out](
        conn: HttpConnection[Handle],
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(using AllowUnsafe, Frame): Fiber.Unsafe[HttpResponse[Out], Abort[HttpException]] =
        val resultPromise = Promise.Unsafe.init[HttpResponse[Out], Abort[HttpException]]()
        try
            encodeAndSendDirectWith(conn, route, request) { (responsePromise, path) =>
                // IOPromise.onComplete gives Result[Nothing, ParsedResponse] directly - no `< S` wrapper
                responsePromise.onComplete { parseResult =>
                    parseResult match
                        case Result.Success(parsed) =>
                            readBufferedBody(conn, parsed, request.method, resultPromise, route, request)
                        case Result.Failure(e) =>
                            resultPromise.completeDiscard(Result.fail(HttpConnectionClosedException()))
                        case Result.Panic(t) =>
                            resultPromise.completeDiscard(Result.panic(t))
                }
            }
        catch
            case t: Throwable =>
                resultPromise.completeDiscard(Result.panic(t))
        end try
        resultPromise
    end sendBuffered

    def sendStreaming[In, Out](
        conn: HttpConnection[Handle],
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(using AllowUnsafe, Frame): Fiber.Unsafe[HttpResponse[Out], Abort[HttpException]] =
        val resultPromise = Promise.Unsafe.init[HttpResponse[Out], Abort[HttpException]]()
        try
            encodeAndSendDirectWith(conn, route, request) { (responsePromise, path) =>
                // IOPromise.onComplete gives Result[Nothing, ParsedResponse] directly
                responsePromise.onComplete { parseResult =>
                    parseResult match
                        case Result.Success(parsed) =>
                            try
                                val lastBodySpan = conn.http1.lastBodySpan
                                val bodyStream   = buildBodyStream(conn, parsed, lastBodySpan)
                                RouteUtil.decodeStreamingResponse(
                                    route,
                                    HttpStatus(parsed.statusCode),
                                    parsed.headers,
                                    bodyStream,
                                    route.method.name,
                                    request.url
                                ) match
                                    case Result.Success(response) =>
                                        resultPromise.completeDiscard(Result.succeed(response))
                                    case Result.Failure(e: HttpException) =>
                                        resultPromise.completeDiscard(Result.fail(e))
                                    case Result.Panic(t) =>
                                        resultPromise.completeDiscard(Result.panic(t))
                                end match
                            catch
                                case t: Throwable =>
                                    resultPromise.completeDiscard(Result.panic(t))
                            end try
                        case Result.Failure(e) =>
                            resultPromise.completeDiscard(Result.fail(HttpConnectionClosedException()))
                        case Result.Panic(t) =>
                            resultPromise.completeDiscard(Result.panic(t))
                }
            }
        catch
            case t: Throwable =>
                resultPromise.completeDiscard(Result.panic(t))
        end try
        resultPromise
    end sendStreaming

    /** Safe wrapper for connect — bridges the unsafe fiber with `f`. Used by tests. */
    def connectWith[A](url: HttpUrl, connectTimeout: Duration, tlsConfig: HttpTlsConfig)(
        f: HttpConnection[Handle] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        Sync.Unsafe.defer {
            val fiber = connect(url, connectTimeout, tlsConfig)
            fiber.safe.use(f)
        }

    /** Safe wrapper for send — bridges the unsafe fiber with `f`. Used by tests. */
    def sendWith[In, Out, A](
        conn: HttpConnection[Handle],
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In],
        onRelease: Maybe[Result.Error[Any]] => Unit < Sync = _ => Kyo.unit
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        Sync.Unsafe.defer {
            val fiber =
                if request.method == HttpMethod.HEAD then
                    sendBuffered(conn, route, request)
                else if RouteUtil.isStreamingResponse(route) then
                    sendStreaming(conn, route, request)
                else
                    sendBuffered(conn, route, request)
            Sync.ensure { (error: Maybe[Result.Error[Any]]) =>
                onRelease(error)
            } {
                fiber.safe.use(f)
            }
        }

    def isAliveUnsafe(conn: HttpConnection[Handle])(using AllowUnsafe): Boolean =
        conn.transport.isOpen

    /** Safe wrapper for isAlive - used by tests and connection pool. */
    def isAlive(conn: HttpConnection[Handle])(using Frame): Boolean < Sync =
        Sync.Unsafe.defer(conn.transport.isOpen)

    def closeNowUnsafe(conn: HttpConnection[Handle])(using AllowUnsafe, Frame): Unit =
        conn.http1.close()
        conn.transport.close()

    /** Safe wrapper for closeNow - used by tests and Scope.ensure. */
    def closeNow(conn: HttpConnection[Handle])(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            conn.http1.close()
            conn.transport.close()
        }

    def closeUnsafe(conn: HttpConnection[Handle], gracePeriod: Duration)(using AllowUnsafe, Frame): Unit =
        conn.http1.close()
        conn.transport.close()

    /** Safe wrapper for close - used by tests. */
    def close(conn: HttpConnection[Handle], gracePeriod: Duration)(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            conn.http1.close()
            conn.transport.close()
        }

    // -- Request encoding --

    /** Encode a request and send it over the Http1ClientConnection. Returns the underlying IOPromise (not Fiber.Unsafe) for direct
      * onComplete without `< S` wrapper, plus the path.
      */
    private def encodeAndSendDirectWith[In, Out, A](
        conn: HttpConnection[Handle],
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(
        f: (IOPromise[Nothing, ParsedResponse], String) => A
    )(using AllowUnsafe, Frame): A =
        // Determine the effective host header value for this request.
        // If the request URL has an explicit host (e.g. after a redirect), recompute;
        // otherwise use the pre-cached value on the connection.
        val hostHeader =
            if request.url.host.nonEmpty && request.url.host != conn.targetHost then
                val host          = request.url.host
                val port          = request.url.port
                val ssl           = request.url.ssl
                val isDefaultPort = if ssl then port == 443 else port == 80
                if isDefaultPort || host.isEmpty then host else s"$host:$port"
            else
                conn.hostHeaderValue
        RouteUtil.encodeRequest(route, request)(
            onEmpty = (path, headers) =>
                val promise =
                    conn.http1.sendDirect(request.method, path, headers, Span.empty[Byte], hostHeader, contentLength = 0, chunked = false)
                f(promise, path)
            ,
            onBuffered = (path, headers, body) =>
                val promise =
                    conn.http1.sendDirect(request.method, path, headers, body, hostHeader, contentLength = body.size.toInt, chunked = false)
                f(promise, path)
            ,
            onStreaming = (path, headers, bodyStream) =>
                // For streaming request bodies, first send headers with empty body,
                // then stream the body chunks in chunked encoding
                val promise =
                    conn.http1.sendDirect(request.method, path, headers, Span.empty[Byte], hostHeader, contentLength = -1, chunked = true)
                // Launch streaming body writer as a background fiber
                streamRequestBody(conn, bodyStream)
                f(promise, path)
        )
    end encodeAndSendDirectWith

    /** Stream request body in chunked transfer encoding format. Launched as a background IOTask. */
    private def streamRequestBody(conn: HttpConnection[Handle], bodyStream: Stream[Span[Byte], Async])(using AllowUnsafe, Frame): Unit =
        import kyo.kernel.internal.Context
        import kyo.kernel.internal.Trace
        import kyo.scheduler.IOTask
        val computation: Unit < Async =
            bodyStream.foreachChunk { chunk =>
                Kyo.foreachDiscard(chunk) { span =>
                    if span.nonEmpty then
                        // Write chunk header (hex size + CRLF) + data + CRLF
                        val header = Span.fromUnsafe(Http1StreamContext.formatChunkSize(span.size))
                        Abort.run[Closed](conn.transport.outbound.safe.put(header)).unit
                            .andThen(Abort.run[Closed](conn.transport.outbound.safe.put(span)).unit)
                            .andThen(Abort.run[Closed](conn.transport.outbound.safe.put(CrLf)).unit)
                    else Kyo.unit
                }
            }.andThen {
                // Write terminal chunk: 0\r\n\r\n
                Abort.run[Closed](conn.transport.outbound.safe.put(TerminalChunk)).unit
            }
        discard(IOTask(computation, Trace.init, Context.empty))
    end streamRequestBody

    // -- Buffered response body reading --

    /** Read the complete body for a buffered response, then decode and complete the promise.
      *
      * Per RFC 9110 Section 6.4.1, responses to HEAD requests, 1xx informational, 204, and 304 responses must not include a message body.
      */
    private def readBufferedBody[In, Out](
        conn: HttpConnection[Handle],
        parsed: ParsedResponse,
        method: HttpMethod,
        resultPromise: Promise.Unsafe[HttpResponse[Out], Abort[HttpException]],
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(using AllowUnsafe, Frame): Unit =
        val noBody = method == HttpMethod.HEAD ||
            parsed.statusCode < 200 ||
            parsed.statusCode == 204 ||
            parsed.statusCode == 304
        if noBody then
            decodeAndComplete(Span.empty[Byte], parsed, resultPromise, route, request)
        else
            val lastBodySpan = conn.http1.lastBodySpan
            if parsed.isChunked then
                // Chunked: decode all chunks via callback-based reader
                ChunkedBodyDecoder.readBufferedUnsafe(conn.http1.bodyChannel, lastBodySpan, conn.http1.chunkedDecoderState) { result =>
                    result match
                        case Result.Success(bodyBytes) =>
                            decodeAndComplete(bodyBytes, parsed, resultPromise, route, request)
                        case Result.Failure(_) =>
                            resultPromise.completeDiscard(Result.fail(HttpConnectionClosedException()))
                        case Result.Panic(t) =>
                            resultPromise.completeDiscard(Result.panic(t))
                }
            else if parsed.contentLength > 0 then
                val remaining = parsed.contentLength - lastBodySpan.size
                if remaining <= 0 then
                    // All body bytes were in the header chunk
                    decodeAndComplete(lastBodySpan, parsed, resultPromise, route, request)
                else
                    // Read remaining bytes from the inbound channel
                    val buf = new GrowableByteBuffer
                    if lastBodySpan.nonEmpty then
                        buf.writeBytes(lastBodySpan.toArrayUnsafe, 0, lastBodySpan.size)
                    readLoopUnsafe(conn, buf, remaining, parsed, resultPromise, route, request)
                end if
            else if parsed.contentLength == 0 then
                decodeAndComplete(Span.empty[Byte], parsed, resultPromise, route, request)
            else
                // No Content-Length and not chunked - use whatever we have
                decodeAndComplete(lastBodySpan, parsed, resultPromise, route, request)
            end if
        end if
    end readBufferedBody

    /** Callback-driven loop to read remaining bytes from the inbound body channel. Uses IOPromise.onComplete via cast to get Result[Closed,
      * Span[Byte]] directly.
      */
    private def readLoopUnsafe[In, Out](
        conn: HttpConnection[Handle],
        buf: GrowableByteBuffer,
        remaining: Int,
        parsed: ParsedResponse,
        resultPromise: Promise.Unsafe[HttpResponse[Out], Abort[HttpException]],
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(using AllowUnsafe, Frame): Unit =
        if remaining <= 0 then
            decodeAndComplete(Span.fromUnsafe(buf.toByteArray), parsed, resultPromise, route, request)
        else
            // Cast to IOPromise to get Result[Closed, Span[Byte]] directly (no `< S` wrapper)
            conn.http1.bodyChannel.takeFiber()
                .asInstanceOf[IOPromise[Closed, Span[Byte]]].onComplete { result =>
                    result match
                        case Result.Success(span) =>
                            buf.writeBytes(span.toArrayUnsafe, 0, span.size)
                            readLoopUnsafe(conn, buf, remaining - span.size, parsed, resultPromise, route, request)
                        case Result.Failure(_) =>
                            resultPromise.completeDiscard(Result.fail(HttpConnectionClosedException()))
                        case Result.Panic(t) =>
                            resultPromise.completeDiscard(Result.panic(t))
                }
    end readLoopUnsafe

    /** Decode response body and complete the result promise. */
    private def decodeAndComplete[In, Out](
        bodyBytes: Span[Byte],
        parsed: ParsedResponse,
        resultPromise: Promise.Unsafe[HttpResponse[Out], Abort[HttpException]],
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(using AllowUnsafe, Frame): Unit =
        try
            RouteUtil.decodeBufferedResponse(
                route,
                HttpStatus(parsed.statusCode),
                parsed.headers,
                bodyBytes,
                route.method.name,
                request.url
            ) match
                case Result.Success(response) =>
                    resultPromise.completeDiscard(Result.succeed(response))
                case Result.Failure(e: HttpException) =>
                    resultPromise.completeDiscard(Result.fail(e))
                case Result.Panic(t) =>
                    resultPromise.completeDiscard(Result.panic(t))
        catch
            case t: Throwable =>
                resultPromise.completeDiscard(Result.panic(t))
    end decodeAndComplete

    // -- Streaming response path --

    /** Build a raw body stream from the parsed response metadata. */
    private def buildBodyStream(conn: HttpConnection[Handle], parsed: ParsedResponse, lastBodySpan: Span[Byte])(using
        AllowUnsafe,
        Frame
    ): Stream[Span[Byte], Async] =
        if parsed.isChunked then
            // For chunked streaming, pipe decoded chunks through a Channel.
            // Use closeAwaitEmpty (not close) to avoid dropping buffered items
            // that the consumer hasn't read yet.
            val decodedCh = Channel.Unsafe.init[Span[Byte]](4)
            // Start the chunked decoder in a background fiber
            discard(kyo.scheduler.IOTask(
                Abort.run[Closed](ChunkedBodyDecoder.readStreaming(
                    conn.http1.bodyChannel,
                    lastBodySpan,
                    decodedCh,
                    conn.http1.chunkedDecoderState
                ))
                    .unit
                    .andThen(decodedCh.safe.closeAwaitEmpty),
                kyo.kernel.internal.Trace.init,
                kyo.kernel.internal.Context.empty
            ))
            decodedCh.safe.streamUntilClosed()
        else if parsed.contentLength > 0 then
            val remaining = parsed.contentLength - lastBodySpan.size
            if remaining <= 0 then
                // All body in last span
                if lastBodySpan.nonEmpty then Stream.init(Seq(lastBodySpan))
                else Stream.empty[Span[Byte]]
            else
                // Emit initial bytes, then read remaining from channel
                Stream[Span[Byte], Async] {
                    val emitInitial: Unit < (Emit[Chunk[Span[Byte]]] & Async) =
                        if lastBodySpan.nonEmpty then Emit.value(Chunk(lastBodySpan))
                        else Kyo.unit
                    emitInitial.andThen {
                        readContentLengthStream(conn, remaining)
                    }
                }
            end if
        else
            // No content-length, not chunked - emit whatever we have
            if lastBodySpan.nonEmpty then Stream.init(Seq(lastBodySpan))
            else Stream.empty[Span[Byte]]
        end if
    end buildBodyStream

    /** Emit exactly `remaining` bytes from the inbound channel. */
    private def readContentLengthStream(conn: HttpConnection[Handle], remaining: Int)(using
        Frame
    ): Unit < (Emit[Chunk[Span[Byte]]] & Async) =
        if remaining <= 0 then Kyo.unit
        else
            Abort.run[Closed](conn.http1.bodyChannel.safe.take).map {
                case Result.Success(span) =>
                    Emit.value(Chunk(span)).andThen {
                        readContentLengthStream(conn, remaining - span.size)
                    }
                case Result.Failure(_: Closed) => Kyo.unit
                case Result.Panic(t)           => throw t
            }

    // -- WebSocket support --

    /** Connect to a WebSocket endpoint. Bypasses the HTTP connection pool — WS connections aren't poolable.
      *
      * Uses transport.connect directly with defaultTlsConfig when TLS is needed. Performs the HTTP/1.1 upgrade handshake via
      * WebSocketCodec.requestUpgrade, then runs the three-fiber session loop (read / write / user handler).
      *
      * Client frames are masked (mask=true) per RFC 6455 §5.3.
      */
    def connectWebSocket[A, S](
        url: HttpUrl,
        headers: HttpHeaders,
        config: HttpWebSocket.Config,
        connectTimeout: Duration = Duration.Infinity
    )(
        f: HttpWebSocket => A < S
    )(using Frame): A < (S & Async & Abort[HttpException]) =
        val host     = url.host
        val port     = url.port
        val ssl      = url.ssl
        val (eh, ep) = hostPort(url)

        val connectFiber = Sync.Unsafe.defer {
            (url.unixSocket, ssl) match
                case (Present(path), _) => transport.connectUnix(path)
                case (_, true)          => transport.connect(host, port, defaultTlsConfig)
                case _                  => transport.connect(host, port)
        }
        val connect = connectFiber.map(_.safe.get)
        val timed =
            if connectTimeout == Duration.Infinity then connect
            else Async.timeout(connectTimeout)(connect)
        Abort.runWith[Closed | Timeout](timed) {
            case Result.Success(connection) =>
                runWsSessionWith(connection, url, headers, config)(f)
            case Result.Failure(_: Timeout) =>
                Abort.fail(HttpConnectTimeoutException(eh, ep, connectTimeout))
            case Result.Failure(closed: Closed) =>
                Abort.fail(HttpConnectException(
                    eh,
                    ep,
                    new IOException(Option(closed.getMessage).getOrElse("Connection closed"))
                ))
            case Result.Panic(t) => throw t
        }
    end connectWebSocket

    // -- Raw connection support --

    /** Connect to a server and upgrade the connection to raw bidirectional byte streaming.
      *
      * Bypasses the HTTP connection pool — raw connections aren't poolable. Sends the HTTP request, validates the response status (101 or
      * 2xx), then exposes the transport channels as a raw byte stream. The connection is closed when the enclosing Scope exits.
      */
    def connectRaw(
        url: HttpUrl,
        method: HttpMethod,
        body: Span[Byte],
        headers: HttpHeaders,
        connectTimeout: Duration
    )(using Frame): HttpRawConnection < (Async & Abort[HttpException] & Scope) =
        val host     = url.host
        val port     = url.port
        val ssl      = url.ssl
        val (eh, ep) = hostPort(url)

        val connectFiber = Sync.Unsafe.defer {
            (url.unixSocket, ssl) match
                case (Present(path), _) => transport.connectUnix(path)
                case (_, true)          => transport.connect(host, port, defaultTlsConfig)
                case _                  => transport.connect(host, port)
        }
        val connect = connectFiber.map(_.safe.get)
        val timed =
            if connectTimeout == Duration.Infinity then connect
            else Async.timeout(connectTimeout)(connect)
        Abort.runWith[Closed | Timeout](timed) {
            case Result.Success(connection) =>
                setupRawConnection(connection, url, method, body, headers)
            case Result.Failure(_: Timeout) =>
                Abort.fail(HttpConnectTimeoutException(eh, ep, connectTimeout))
            case Result.Failure(closed: Closed) =>
                Abort.fail(HttpConnectException(
                    eh,
                    ep,
                    new IOException(Option(closed.getMessage).getOrElse("Connection closed"))
                ))
            case Result.Panic(t) => throw t
        }
    end connectRaw

    /** Set up a raw connection after transport connect succeeds.
      *
      * Creates an Http1ClientConnection, sends the HTTP request, validates the response, then wraps the transport channels as a raw byte
      * stream.
      */
    private def setupRawConnection(
        connection: Connection[Handle],
        url: HttpUrl,
        method: HttpMethod,
        body: Span[Byte],
        headers: HttpHeaders
    )(using Frame): HttpRawConnection < (Async & Abort[HttpException] & Scope) =
        // Create Http1ClientConnection and send the request in an unsafe block.
        // Capture http1 in a var so we can access lastBodySpan after the response.
        var http1: Http1ClientConnection = null
        Sync.Unsafe.defer {
            http1 = Http1ClientConnection.init(
                connection.inbound,
                connection.outbound,
                transportConfig.maxHeaderSize
            )
            // Compute host header
            val isDefaultPort   = if url.ssl then url.port == 443 else url.port == 80
            val hostHeaderValue = if isDefaultPort || url.host.isEmpty then url.host else s"${url.host}:${url.port}"
            // Build path with query string
            val path = url.rawQuery match
                case Present(q) => s"${url.path}?$q"
                case Absent     => url.path
            // Send the HTTP request
            val responsePromise = http1.sendDirect(
                method,
                path,
                headers,
                body,
                hostHeaderValue,
                contentLength = body.size.toInt,
                chunked = false
            )
            // Wait for the parsed response
            val responseFiber = responsePromise.asInstanceOf[Fiber.Unsafe[ParsedResponse, Any]]
            responseFiber.safe.get
        }.map { parsed =>
            val status = parsed.statusCode
            // Accept 101 (Switching Protocols) or any 2xx
            if status != 101 && (status < 200 || status >= 300) then
                Abort.fail(HttpStatusException(
                    HttpStatus(status),
                    method.name,
                    url.baseUrl
                ))
            else
                Sync.Unsafe.defer {
                    val lastBody   = http1.lastBodySpan
                    val rawInbound = connection.inbound.safe.streamUntilClosed()
                    val readStream =
                        if lastBody.isEmpty then rawInbound
                        else Stream.init(Seq(lastBody)).concat(rawInbound)
                    val transportStream = new ConnectionBackedStream(connection)
                    new HttpRawConnection(
                        readStream,
                        data => transportStream.write(data)
                    )
                }
            end if
        }.map { rawConn =>
            Scope.ensure {
                Sync.Unsafe.defer {
                    connection.close()
                }
            }.andThen(rawConn)
        }
    end setupRawConnection

    /** Run a HttpWebSocket session: upgrade, then read/write/user fibers.
      *
      * Sends HTTP upgrade request through the connection, reads 101 response, then switches to WS frame codec using ConnectionBackedStream
      * + WebSocketCodec.
      */
    private def runWsSessionWith[A, S](
        connection: Connection[Handle],
        url: HttpUrl,
        headers: HttpHeaders,
        config: HttpWebSocket.Config
    )(
        f: HttpWebSocket => A < S
    )(using Frame): A < (S & Async & Abort[HttpException]) =
        val transportStream = new ConnectionBackedStream(connection)
        Sync.ensure {
            Sync.Unsafe.defer {
                connection.close()
            }
        } {
            WebSocketCodec.requestUpgradeWith(transportStream, url.host, url.path, headers, config) { wsStream =>
                serveWebSocketWith(transportStream, wsStream, config)(f)
            }
        }
    end runWsSessionWith

    /** Three concurrent fibers: read loop, write loop, user handler.
      *
      * Client frames are masked (mask=true) per RFC 6455 section 5.3. Follows the same pattern as UnsafeServerDispatch.serveWebSocketWith
      * and NioUnsafeWsClient.serveWebSocketWith.
      */
    private def serveWebSocketWith[A, S](
        transportStream: TransportStream,
        wsStream: Stream[Span[Byte], Async],
        config: HttpWebSocket.Config
    )(
        f: HttpWebSocket => A < S
    )(using Frame): A < (S & Async & Abort[HttpException]) =
        Channel.initUnscopedWith[HttpWebSocket.Payload](config.bufferSize) { inbound =>
            Channel.initUnscopedWith[HttpWebSocket.Payload](config.bufferSize) { outbound =>
                AtomicRef.initWith(Absent: Maybe[(Int, String)]) { closeReasonRef =>
                    val closeFn: (Int, String) => Unit < Async = (code, reason) =>
                        closeReasonRef.set(Present((code, reason))).andThen {
                            outbound.close.unit
                        }
                    val ws = new HttpWebSocket(inbound, outbound, closeReasonRef, closeFn)

                    Fiber.initUnscoped {
                        Loop(wsStream) { stream =>
                            WebSocketCodec.readFrameWith(stream, transportStream) { (frame, remaining) =>
                                inbound.put(frame).andThen(Loop.continue(remaining))
                            }
                        }
                    }.map { readFiber =>
                        Fiber.initUnscoped {
                            readFiber.getResult.map { _ =>
                                inbound.close.unit
                            }
                        }.map { monitorFiber =>
                            Fiber.initUnscoped {
                                Abort.run[Closed] {
                                    Loop.foreach {
                                        outbound.take.map { frame =>
                                            WebSocketCodec.writeFrame(transportStream, frame, mask = true).andThen(Loop.continue)
                                        }
                                    }
                                }.map { _ =>
                                    closeReasonRef.get.map {
                                        case Present((code, reason)) =>
                                            Abort.run[Any](WebSocketCodec.writeClose(transportStream, code, reason, mask = true)).unit
                                        case Absent => Kyo.unit
                                    }
                                }.andThen(outbound.close).unit
                            }.map { writeFiber =>
                                Sync.ensure(
                                    readFiber.interrupt.unit
                                        .andThen(writeFiber.interrupt.unit)
                                        .andThen(monitorFiber.interrupt.unit)
                                        .andThen(inbound.close.unit)
                                        .andThen(outbound.close.unit)
                                ) {
                                    f(ws)
                                }
                            }
                        }
                    }
                }
            }
        }
    end serveWebSocketWith

    // -- Pool management and orchestration layer --

    @volatile private var clientClosed                 = false
    @volatile private var closingGracePeriod: Duration = Duration.Zero

    /** Track a newly created connection. If the client has already been closed, close it immediately. */
    private def trackConn(conn: HttpConnection[Handle])(using AllowUnsafe, Frame): Unit =
        discard(allConnections.put(conn, ()))
        if clientClosed then
            closeUnsafe(conn, closingGracePeriod)
    end trackConn

    /** Release a connection back to the pool or discard it on error. Does NOT touch allConnections — removal happens only in discardConn
      * (actual close).
      */
    private def releaseConn(key: HttpAddress, conn: HttpConnection[Handle], error: Maybe[Result.Error[Any]])(using AllowUnsafe): Unit =
        error match
            case Absent => pool.release(key, conn)
            case _      => pool.discard(conn)
    end releaseConn

    def sendWithConfig[In, Out, A](
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
                    timeoutWith(route, request, config) { res =>
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
                timeoutWith(route, request, config)(f)
    end retryWith

    private def timeoutWith[In, Out, A](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClientConfig
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        // Apply redirect-aware callback wrapping
        if config.followRedirects then
            def loop(req: HttpRequest[In], count: Int, chain: Chunk[String]): A < (Async & Abort[HttpException]) =
                val inner = poolWith(route, req, config) { res =>
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
                                        // RFC 9110 section 15.4.4: 303 See Other requires changing method to GET
                                        val nextReq =
                                            if res.status == HttpStatus.SeeOther then req.copy(url = resolved, method = HttpMethod.GET)
                                            else req.copy(url = resolved)
                                        loop(nextReq, count + 1, chain.append(location))
                                    case Result.Failure(err) =>
                                        Abort.fail(err)
                            case Absent => f(res)
                }
                if config.timeout == Duration.Infinity then inner
                else
                    Async.timeoutWithError(
                        config.timeout,
                        Result.Failure(HttpTimeoutException(config.timeout, req.method.name, req.url.baseUrl))
                    )(inner)
                end if
            end loop
            loop(request, 0, Chunk.empty)
        else
            val inner = poolWith(route, request, config)(f)
            if config.timeout == Duration.Infinity then inner
            else
                Async.timeoutWithError(
                    config.timeout,
                    Result.Failure(HttpTimeoutException(config.timeout, request.method.name, request.url.baseUrl))
                )(inner)
            end if

    /** Core pool logic: acquire a connection and dispatch the request. Called by poolWith after any filter transforms have been applied. */
    private def poolWithImpl[In, Out, A](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClientConfig
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        val url = request.url
        val key = request.url.address
        Sync.Unsafe.defer {
            pool.poll(key) match
                case Present(conn) =>
                    val responseFiber = sendViaBackend(conn, route, request)
                    Sync.ensure { (error: Maybe[Result.Error[Any]]) =>
                        Sync.Unsafe.defer(releaseConn(key, conn, error))
                    } {
                        responseFiber.safe.use(f)
                    }
                case _ =>
                    val reserved = pool.tryReserve(key)
                    if reserved then
                        Sync.ensure(Sync.Unsafe.defer(pool.unreserve(key))) {
                            val connectFiber = connect(url, config.connectTimeout, config.tls)
                            connectFiber.safe.use { conn =>
                                trackConn(conn)
                                val responseFiber = sendViaBackend(conn, route, request)
                                Sync.ensure { (error: Maybe[Result.Error[Any]]) =>
                                    Sync.Unsafe.defer(releaseConn(key, conn, error))
                                } {
                                    responseFiber.safe.use(f)
                                }
                            }
                        }
                    else
                        val (h, p) = hostPort(url)
                        Abort.fail(HttpPoolExhaustedException(
                            h,
                            p,
                            maxConnectionsPerHost,
                            clientFrame
                        ))
                    end if
        }.asInstanceOf[A < (Async & Abort[HttpException])]
    end poolWithImpl

    /** Pool-based send with connection lifecycle management. Acquires connection from pool (unsafe), dispatches to backend (unsafe), then
      * bridges to safe layer for `f` with connection release via Sync.ensure.
      *
      * When both client and route filters are noop (common case), skips the filter chain entirely to avoid a closure allocation.
      */
    private def poolWith[In, Out, A](
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In],
        config: HttpClientConfig
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        // Client-side filters (e.g. basicAuth, bearerAuth) are Passthrough — they transform the request
        // and forward next's result unchanged.
        // Auto-discovered filters (e.g. W3C trace context from kyo-stats-otlp) are composed first.
        val clientFilter = HttpFilterFactory.composedClient
        val routeFilter  = route.filter
        if (clientFilter eq HttpFilter.noop) && (routeFilter eq HttpFilter.noop) then
            // Fast path: no filters configured — call impl directly without filter closure
            poolWithImpl(route, request, config)(f)
        else
            val filter = clientFilter.andThen(routeFilter)
                .asInstanceOf[HttpFilter[Any, In, Out, Out, Nothing]]
            filter[In, Out, HttpException](
                request,
                (filteredReq: HttpRequest[In]) =>
                    poolWithImpl(route, filteredReq, config)(f)
                        .asInstanceOf[HttpResponse[Out] < (Async & Abort[HttpException | HttpResponse.Halt])]
            ).asInstanceOf[A < (Async & Abort[HttpException])]
        end if
    end poolWith

    /** Dispatch to the appropriate unsafe backend method based on route type. */
    private def sendViaBackend[In, Out](
        conn: HttpConnection[Handle],
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(using AllowUnsafe, Frame): Fiber.Unsafe[HttpResponse[Out], Abort[HttpException]] =
        // HEAD responses never have a body (RFC 9110 Section 9.3.2),
        // so always use the buffered path which skips body reading for HEAD.
        if request.method == HttpMethod.HEAD then
            sendBuffered(conn, route, request)
        else if RouteUtil.isStreamingResponse(route) then
            sendStreaming(conn, route, request)
        else
            sendBuffered(conn, route, request)

    def closeFiber(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        // Mark closed FIRST so any new connection gets closed immediately by trackConn.
        closingGracePeriod = gracePeriod
        clientClosed = true
        // Close pool to stop reuse, then close all tracked connections.
        // allConnections has every connection from creation to close (discardConn removes them).
        // Pool idle connections are a subset — closing from allConnections covers everything.
        discard(pool.close())
        val closePromise = Promise.Unsafe.init[Unit, Any]()
        allConnections.forEach { (conn, _) =>
            closeUnsafe(conn, gracePeriod)
        }
        allConnections.clear()
        closePromise.completeDiscard(Result.succeed(()))
        closePromise
    end closeFiber

end HttpClientBackend

private[kyo] object HttpClientBackend:

    /** Create a fully pooled backend for production use. */
    def init[H](
        transport: Transport[H],
        maxConnsPerHost: Int,
        idleConnectionTimeout: Duration,
        defaultTlsConfig: HttpTlsConfig = HttpTlsConfig.default
    )(using AllowUnsafe, Frame): HttpClientBackend[H] =
        val conns = new ConcurrentHashMap[HttpConnection[H], Unit]()
        val pool = ConnectionPool.init[HttpConnection[H]](
            maxConnsPerHost,
            idleConnectionTimeout,
            conn => conn.transport.isOpen,
            conn =>
                discard(conns.remove(conn))
                conn.http1.close()
                conn.transport.close()
        )
        new HttpClientBackend[H](
            transport,
            HttpTransportConfig.default,
            defaultTlsConfig,
            pool,
            conns,
            maxConnsPerHost,
            summon[Frame]
        )
    end init

end HttpClientBackend
