package kyo.internal.client

import kyo.*
import kyo.internal.codec.*
import kyo.internal.http1.*
import kyo.internal.server.RouteUtil
import kyo.internal.transport.*
import kyo.internal.util.*
import kyo.internal.websocket.WebSocketCodec
import kyo.net.internal.util.GrowableByteBuffer
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
final private[kyo] class HttpClientBackend private (
    transport: kyo.net.Transport,
    transportConfig: HttpTransportConfig,
    defaultTlsConfig: HttpTlsConfig,
    private val pool: ConnectionPool[HttpConnection],
    private val registry: kyo.internal.ConnectionRegistry[HttpConnection],
    val maxConnectionsPerHost: Int,
    ownsTransport: Boolean,
    val clientFrame: Frame
):
    private val CrLf          = Span.fromUnsafe(Http1StreamContext.CRLF)
    private val TerminalChunk = Span.fromUnsafe(Http1StreamContext.LAST_CHUNK)

    private def hostPort(url: HttpUrl): (String, Int) =
        url.unixSocket match
            case Present(path) => (s"unix:$path", 0)
            case Absent        => (url.host, url.port)

    /** Map a transport connect failure to the matching [[HttpException]]. The transport produces a typed [[kyo.net.NetException]] leaf (a DNS
      * failure, a connection refusal, a Unix-socket error), which is translated to the matching HTTP connection exception and kept as the cause
      * so a caller can still recover the specific transport reason. `eh`/`ep` are the display host/port used for a failure with no structured
      * host of its own.
      */
    private def transportConnectFailure(failure: kyo.net.NetException, eh: String, ep: Int)(using Frame): HttpException =
        failure match
            case e: kyo.net.NetDnsResolutionException  => HttpDnsResolutionException(e.host, e)
            case e: kyo.net.NetUnixConnectException    => HttpUnixConnectException(e.path, e)
            case e: kyo.net.NetConnectTimeoutException => HttpConnectTimeoutException(e.host, e.port, e.timeout)
            case e: kyo.net.NetConnectException        => HttpConnectException(e.host, e.port, e)
            case other                                 => HttpConnectException(eh, ep, other)
    end transportConnectFailure

    def connect(url: HttpUrl, connectTimeout: Duration, tlsConfig: HttpTlsConfig)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[HttpConnection, Abort[HttpException]] =
        // Merge TLS configs: use defaultTlsConfig as base, override with explicitly set fields
        val effectiveTls =
            if tlsConfig == HttpTlsConfig.default then defaultTlsConfig
            else tlsConfig
        val connectFiber = (url.unixSocket, url.ssl) match
            case (Present(path), _) => transport.connectUnix(path)
            case (_, true)          => NetConfigTranslation.connectTls(transport, url.host, url.port, effectiveTls)
            case _                  => transport.connect(url.host, url.port)
        val resultPromise = Promise.Unsafe.init[HttpConnection, Abort[HttpException]]()
        // Cast to IOPromise to get Result[NetException, transport.Connection] directly (no `< S` wrapper).
        // Fiber.Unsafe is an opaque wrapper over IOPromise - at runtime they are the same object.
        connectFiber.asInstanceOf[IOPromise[kyo.net.NetException, kyo.net.Connection]].onComplete { result =>
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
                case Result.Failure(netEx) =>
                    val (h, p) = hostPort(url)
                    resultPromise.completeDiscard(Result.fail(transportConnectFailure(netEx, h, p)))
                case Result.Panic(t) =>
                    resultPromise.completeDiscard(Result.panic(t))
        }
        resultPromise
    end connect

    def sendBuffered[In, Out](
        conn: HttpConnection,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In],
        maxResponseLength: Int
    )(using AllowUnsafe, Frame): Fiber.Unsafe[HttpResponse[Out], Abort[HttpException]] =
        val resultPromise = Promise.Unsafe.init[HttpResponse[Out], Abort[HttpException]]()
        try
            encodeAndSendDirectWith(conn, route, request) { responsePromise =>
                // IOPromise.onComplete gives Result[Nothing, ParsedResponse] directly - no `< S` wrapper
                responsePromise.onComplete { parseResult =>
                    parseResult match
                        case Result.Success(parsed) =>
                            readBufferedBody(conn, parsed, request.method, resultPromise, route, request, maxResponseLength)
                        case Result.Failure(e) =>
                            resultPromise.completeDiscard(Result.fail(HttpConnectionClosedException()))
                        case Result.Panic(t) =>
                            resultPromise.completeDiscard(Result.panic(t))
                    end match
                }
            }
        catch
            case t: Throwable =>
                resultPromise.completeDiscard(Result.panic(t))
        end try
        resultPromise
    end sendBuffered

    def sendStreaming[In, Out](
        conn: HttpConnection,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In],
        maxResponseLength: Int
    )(using AllowUnsafe, Frame): Fiber.Unsafe[HttpResponse[Out], Abort[HttpException]] =
        val resultPromise = Promise.Unsafe.init[HttpResponse[Out], Abort[HttpException]]()
        try
            encodeAndSendDirectWith(conn, route, request) { responsePromise =>
                // IOPromise.onComplete gives Result[Nothing, ParsedResponse] directly
                responsePromise.onComplete { parseResult =>
                    parseResult match
                        case Result.Success(parsed) =>
                            try
                                // For error responses on streaming routes, fall back to buffered reading.
                                // Daemons return small JSON error bodies even for stream-typed endpoints
                                // (e.g. podman's `/images/create` returns a JSON error on auth failure
                                // rather than progress events). Streaming the response would trap that
                                // body inside an unconsumed Stream that callers never drain, throwing
                                // away the only diagnostic. Going through the buffered path lets
                                // RouteUtil.decodeBufferedResponse populate HttpStatusException.body.
                                if parsed.statusCode >= 400 then
                                    readBufferedBody(conn, parsed, request.method, resultPromise, route, request, maxResponseLength)
                                else
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
                                end if
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

    /** Safe wrapper for connect, bridges the unsafe fiber with `f`. Used by tests. */
    def connectWith[A](url: HttpUrl, connectTimeout: Duration, tlsConfig: HttpTlsConfig)(
        f: HttpConnection => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        Sync.Unsafe.defer {
            val fiber = connect(url, connectTimeout, tlsConfig)
            fiber.safe.use(f)
        }

    /** Safe wrapper for send, bridges the unsafe fiber with `f`. Used by tests. `maxResponseLength` defaults to the same buffered-response
      * cap as [[HttpClientConfig.maxResponseLength]].
      */
    def sendWith[In, Out, A](
        conn: HttpConnection,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In],
        onRelease: Maybe[Result.Error[Any]] => Unit < Sync = _ => Kyo.unit,
        maxResponseLength: Int = 100 * 1024 * 1024
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        Sync.Unsafe.defer {
            val fiber =
                if request.method == HttpMethod.HEAD then
                    sendBuffered(conn, route, request, maxResponseLength)
                else if RouteUtil.isStreamingResponse(route) then
                    sendStreaming(conn, route, request, maxResponseLength)
                else
                    sendBuffered(conn, route, request, maxResponseLength)
            Sync.ensure { (error: Maybe[Result.Error[Any]]) =>
                onRelease(error)
            } {
                fiber.safe.use(f)
            }
        }

    def isAliveUnsafe(conn: HttpConnection)(using AllowUnsafe): Boolean =
        conn.transport.isOpen

    /** Safe wrapper for isAlive - used by tests and connection pool. */
    def isAlive(conn: HttpConnection)(using Frame): Boolean < Sync =
        Sync.Unsafe.defer(conn.transport.isOpen)

    def closeNowUnsafe(conn: HttpConnection)(using AllowUnsafe, Frame): Unit =
        conn.http1.close()
        conn.transport.close()

    /** Safe wrapper for closeNow - used by tests and Scope.ensure. */
    def closeNow(conn: HttpConnection)(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            conn.http1.close()
            conn.transport.close()
        }

    def closeUnsafe(conn: HttpConnection, gracePeriod: Duration)(using AllowUnsafe, Frame): Unit =
        conn.http1.close()
        conn.transport.close()

    /** Safe wrapper for close - used by tests. */
    def close(conn: HttpConnection, gracePeriod: Duration)(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            conn.http1.close()
            conn.transport.close()
        }

    // -- Request encoding --

    /** Encode a request and send it over the Http1ClientConnection. Passes the underlying IOPromise (not Fiber.Unsafe) to `f` for a direct
      * onComplete without `< S` wrapper.
      */
    private inline def encodeAndSendDirectWith[In, Out, A](
        conn: HttpConnection,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(
        inline f: IOPromise[Nothing, ParsedResponse] => A
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
                f(promise)
            ,
            onBuffered = (path, headers, body) =>
                val promise =
                    conn.http1.sendDirect(request.method, path, headers, body, hostHeader, contentLength = body.size.toInt, chunked = false)
                f(promise)
            ,
            onStreaming = (path, headers, bodyStream) =>
                // For streaming request bodies, first send headers with empty body,
                // then stream the body chunks in chunked encoding
                val promise =
                    conn.http1.sendDirect(request.method, path, headers, Span.empty[Byte], hostHeader, contentLength = -1, chunked = true)
                // Launch streaming body writer as a background fiber
                streamRequestBody(conn, bodyStream)
                f(promise)
        )
    end encodeAndSendDirectWith

    /** Stream request body in chunked transfer encoding format. Launched as a background IOTask. */
    private def streamRequestBody(conn: HttpConnection, bodyStream: Stream[Span[Byte], Async])(using AllowUnsafe, Frame): Unit =
        import kyo.kernel.internal.Context
        import kyo.kernel.internal.Trace
        import kyo.scheduler.IOTask
        val computation: Unit < Async =
            // The whole write is wrapped in a single Abort.run[Closed]: the first Closed (connection torn
            // down) aborts foreachChunk instead of being swallowed per-put, which used to let this loop keep
            // pulling an infinite body stream into a dead connection. A closed connection here is routine
            // (the request is being torn down), so the discard is correct and needs no logging.
            Abort.run[Closed] {
                bodyStream.foreachChunk { chunk =>
                    Kyo.foreachDiscard(chunk) { span =>
                        if span.nonEmpty then
                            // Write chunk header (hex size + CRLF) + data + CRLF
                            val header = Span.fromUnsafe(Http1StreamContext.formatChunkSize(span.size))
                            conn.transport.outbound.safe.put(header)
                                .andThen(conn.transport.outbound.safe.put(span))
                                .andThen(conn.transport.outbound.safe.put(CrLf))
                        else Kyo.unit
                    }
                }.andThen {
                    // Write terminal chunk: 0\r\n\r\n
                    conn.transport.outbound.safe.put(TerminalChunk)
                }
            }.unit
        discard(IOTask(computation, Trace.init, Context.empty))
    end streamRequestBody

    // -- Buffered response body reading --

    /** Read the complete body for a buffered response, then decode and complete the promise.
      *
      * Per RFC 9110 Section 6.4.1, responses to HEAD requests, 1xx informational, 204, and 304 responses must not include a message body.
      */
    private def readBufferedBody[In, Out](
        conn: HttpConnection,
        parsed: ParsedResponse,
        method: HttpMethod,
        resultPromise: Promise.Unsafe[HttpResponse[Out], Abort[HttpException]],
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In],
        maxResponseLength: Int
    )(using AllowUnsafe, Frame): Unit =
        val noBody = method == HttpMethod.HEAD ||
            parsed.statusCode < 200 ||
            parsed.statusCode == 204 ||
            parsed.statusCode == 304
        if noBody then
            decodeAndComplete(conn, Span.empty[Byte], parsed, resultPromise, route, request)
        else
            val lastBodySpan = conn.http1.lastBodySpan
            if parsed.isChunked then
                // Chunked: decode all chunks via callback-based reader, bounding the accumulated body at maxResponseLength
                // (a server streaming an unbounded chunked body would otherwise OOM the client, CWE-400).
                ChunkedBodyDecoder.readBufferedUnsafe(
                    conn.http1.bodyChannel,
                    lastBodySpan,
                    maxResponseLength,
                    conn.http1.chunkedDecoderState
                )(
                    {
                        case Result.Success(bodyBytes) =>
                            decodeAndComplete(conn, bodyBytes, parsed, resultPromise, route, request)
                        case Result.Failure(_) =>
                            resultPromise.completeDiscard(Result.fail(HttpConnectionClosedException()))
                        case Result.Panic(t) =>
                            resultPromise.completeDiscard(Result.panic(t))
                    },
                    size => resultPromise.completeDiscard(Result.fail(HttpPayloadTooLargeException(size, maxResponseLength)))
                )
            else if parsed.contentLength > 0 then
                if parsed.contentLength > maxResponseLength then
                    // Reject before allocating: an enormous declared Content-Length must not size the read buffer (CWE-400).
                    resultPromise.completeDiscard(Result.fail(HttpPayloadTooLargeException(parsed.contentLength, maxResponseLength)))
                else
                    val remaining = parsed.contentLength - lastBodySpan.size
                    if remaining <= 0 then
                        // All body bytes were in the header chunk
                        decodeAndComplete(conn, lastBodySpan, parsed, resultPromise, route, request)
                    else
                        // Read remaining bytes from the inbound channel
                        val buf = new GrowableByteBuffer
                        if lastBodySpan.nonEmpty then
                            buf.writeBytes(lastBodySpan.toArrayUnsafe, 0, lastBodySpan.size)
                        readLoopUnsafe(conn, buf, remaining, parsed, resultPromise, route, request)
                    end if
                end if
            else if parsed.contentLength == 0 then
                decodeAndComplete(conn, Span.empty[Byte], parsed, resultPromise, route, request)
            else
                // Close-framed body (no Content-Length, no Transfer-Encoding: chunked).
                // Per RFC 7230 §3.3.3 item 7, read bytes until the server closes the connection.
                // The parser has already handed us any leading body bytes via lastBodySpan;
                // drain the remaining bytes from the inbound channel until it closes (EOF),
                // then concatenate everything and treat the close as the legitimate end-of-body.
                val buf = new GrowableByteBuffer
                if lastBodySpan.nonEmpty then
                    buf.writeBytes(lastBodySpan.toArrayUnsafe, 0, lastBodySpan.size)
                readUntilCloseUnsafe(conn, buf, parsed, resultPromise, route, request, maxResponseLength)
            end if
        end if
    end readBufferedBody

    /** Callback-driven loop to read remaining bytes from the inbound body channel. Uses IOPromise.onComplete via cast to get Result[Closed,
      * Span[Byte]] directly.
      */
    private def readLoopUnsafe[In, Out](
        conn: HttpConnection,
        buf: GrowableByteBuffer,
        remaining: Int,
        parsed: ParsedResponse,
        resultPromise: Promise.Unsafe[HttpResponse[Out], Abort[HttpException]],
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(using AllowUnsafe, Frame): Unit =
        if remaining <= 0 then
            decodeAndComplete(conn, Span.fromUnsafe(buf.toByteArray), parsed, resultPromise, route, request)
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

    /** Read remaining bytes from the inbound channel until the connection closes (EOF-framed body).
      *
      * Per RFC 7230 §3.3.3 item 7, a response with neither Content-Length nor Transfer-Encoding: chunked is terminated by connection close;
      * the EOF is the legitimate end-of-body signal, not an error. Used e.g. for Podman's `/exec/{id}/start` multiplexed-stream response.
      */
    private def readUntilCloseUnsafe[In, Out](
        conn: HttpConnection,
        buf: GrowableByteBuffer,
        parsed: ParsedResponse,
        resultPromise: Promise.Unsafe[HttpResponse[Out], Abort[HttpException]],
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In],
        maxResponseLength: Int
    )(using AllowUnsafe, Frame): Unit =
        conn.http1.bodyChannel.takeFiber()
            .asInstanceOf[IOPromise[Closed, Span[Byte]]].onComplete { result =>
                result match
                    case Result.Success(span) =>
                        buf.writeBytes(span.toArrayUnsafe, 0, span.size)
                        if buf.size > maxResponseLength then
                            // A close-framed body that keeps growing past the cap would OOM the client (CWE-400).
                            resultPromise.completeDiscard(Result.fail(HttpPayloadTooLargeException(buf.size, maxResponseLength)))
                        else
                            readUntilCloseUnsafe(conn, buf, parsed, resultPromise, route, request, maxResponseLength)
                        end if
                    case Result.Failure(_) =>
                        // EOF is expected for close-framed bodies, deliver what we have.
                        decodeAndComplete(conn, Span.fromUnsafe(buf.toByteArray), parsed, resultPromise, route, request)
                    case Result.Panic(t) =>
                        resultPromise.completeDiscard(Result.panic(t))
            }
    end readUntilCloseUnsafe

    /** Decode response body and complete the result promise. Closes the connection if the server indicated Connection: close
      * (isKeepAlive=false) so it won't be reused from the pool.
      */
    private def decodeAndComplete[In, Out](
        conn: HttpConnection,
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
                    if !parsed.isKeepAlive then conn.transport.close()
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
    private def buildBodyStream(conn: HttpConnection, parsed: ParsedResponse, lastBodySpan: Span[Byte])(using
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
    private def readContentLengthStream(conn: HttpConnection, remaining: Int)(using
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

    /** Connect to a WebSocket endpoint. Bypasses the HTTP connection pool, WS connections aren't poolable.
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
        connectTimeout: Duration = Duration.Infinity,
        clientFilter: HttpFilter.Passthrough[Nothing] = HttpFilter.noop,
        autoFilters: Boolean = true
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
                case (_, true)          => NetConfigTranslation.connectTls(transport, host, port, defaultTlsConfig)
                case _                  => transport.connect(host, port)
        }
        val connect: kyo.net.Connection < (Async & Abort[kyo.net.NetException]) = connectFiber.map(_.safe.get)
        val timed: kyo.net.Connection < (Async & Abort[kyo.net.NetException | Timeout]) =
            if connectTimeout == Duration.Infinity then connect
            else Async.timeout(connectTimeout)(connect)
        Abort.runWith[kyo.net.NetException | Timeout](timed) {
            case Result.Success(connection) =>
                runWsSessionWith(connection, url, headers, config, clientFilter, autoFilters)(f)
            case Result.Failure(_: Timeout) =>
                Abort.fail(HttpConnectTimeoutException(eh, ep, connectTimeout))
            case Result.Failure(netEx: kyo.net.NetException) =>
                Abort.fail(transportConnectFailure(netEx, eh, ep))
            case Result.Panic(t) => throw t
        }
    end connectWebSocket

    // -- Raw connection support --

    /** Connect to a server and upgrade the connection to raw bidirectional byte streaming.
      *
      * Bypasses the HTTP connection pool, raw connections aren't poolable. Sends the HTTP request, validates the response status (101 or
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
                case (_, true)          => NetConfigTranslation.connectTls(transport, host, port, defaultTlsConfig)
                case _                  => transport.connect(host, port)
        }
        val connect: kyo.net.Connection < (Async & Abort[kyo.net.NetException]) = connectFiber.map(_.safe.get)
        val timed: kyo.net.Connection < (Async & Abort[kyo.net.NetException | Timeout]) =
            if connectTimeout == Duration.Infinity then connect
            else Async.timeout(connectTimeout)(connect)
        Abort.runWith[kyo.net.NetException | Timeout](timed) {
            case Result.Success(connection) =>
                setupRawConnection(connection, url, method, body, headers)
            case Result.Failure(_: Timeout) =>
                Abort.fail(HttpConnectTimeoutException(eh, ep, connectTimeout))
            case Result.Failure(netEx: kyo.net.NetException) =>
                Abort.fail(transportConnectFailure(netEx, eh, ep))
            case Result.Panic(t) => throw t
        }
    end connectRaw

    /** Set up a raw connection after transport connect succeeds.
      *
      * Creates an Http1ClientConnection, sends the HTTP request, validates the response, then wraps the transport channels as a raw byte
      * stream.
      */
    private def setupRawConnection(
        connection: kyo.net.Connection,
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
            // Send the HTTP request
            val responsePromise = http1.sendDirect(
                method,
                url.pathWithQuery,
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
                // Drain up to 4KB of the error body to surface the server's explanation.
                Sync.Unsafe.defer {
                    val lastBody = http1.lastBodySpan
                    val bodyText =
                        if lastBody.isEmpty then ""
                        else
                            val bytes = lastBody.take(4096).toArray
                            new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                    Abort.fail(HttpStatusException(
                        HttpStatus(status),
                        method.name,
                        url.baseUrl,
                        bodyText
                    ))
                }
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
        connection: kyo.net.Connection,
        url: HttpUrl,
        headers: HttpHeaders,
        config: HttpWebSocket.Config,
        clientFilter: HttpFilter.Passthrough[Nothing],
        autoFilters: Boolean
    )(
        f: HttpWebSocket => A < S
    )(using Frame): A < (S & Async & Abort[HttpException]) =
        val transportStream = new ConnectionBackedStream(connection)
        Sync.ensure {
            Sync.Unsafe.defer {
                connection.close()
            }
        } {
            val autoFilter =
                if autoFilters then HttpFilter.Factory.composedClient
                else HttpFilter.noop
            val filter = autoFilter.andThen(clientFilter)
            if filter.eq(HttpFilter.noop) then
                WebSocketCodec.requestUpgradeWith(transportStream, url.host, url.pathWithQuery, headers, config) { wsStream =>
                    serveWebSocketWith(transportStream, wsStream, config)(f)
                }
            else
                val request = HttpRequest(HttpMethod.GET, url, headers, Record.empty)
                handleWebSocketFilterResult(
                    url,
                    filter[Any, "body" ~ A, HttpException, S](
                        request,
                        (filteredReq: HttpRequest[Any]) =>
                            WebSocketCodec.requestUpgradeWith(
                                transportStream,
                                filteredReq.url.host,
                                filteredReq.url.pathWithQuery,
                                filteredReq.headers,
                                config
                            ) { wsStream =>
                                serveWebSocketWith(transportStream, wsStream, config)(f).map { result =>
                                    HttpResponse(HttpStatus.SwitchingProtocols).addField("body", result)
                                }
                            }
                    ).map(_.fields.body)
                )
            end if
        }
    end runWsSessionWith

    private def handleWebSocketFilterResult[A, S](
        url: HttpUrl,
        v: A < (S & Async & Abort[HttpException | HttpResponse.Halt])
    )(using Frame): A < (S & Async & Abort[HttpException]) =
        Abort.run[HttpResponse.Halt](v).map {
            case Result.Success(value) => value
            case Result.Failure(halt) =>
                Abort.fail(HttpStatusException(halt.response.status, HttpMethod.GET.name, url.baseUrl, halt.response.rawBody.getOrElse("")))
            case Result.Panic(t) => throw t
        }
    end handleWebSocketFilterResult

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
                    Fiber.Promise.init[Unit, Any].map { peerClosedPromise =>
                        val closeFn: (Int, String) => Unit < Async = (code, reason) =>
                            closeReasonRef.set(Present((code, reason))).andThen {
                                outbound.close.unit
                            }
                        val ws = new HttpWebSocket(inbound, outbound, closeReasonRef, peerClosedPromise, closeFn)

                        Fiber.initUnscoped {
                            Loop(wsStream) { stream =>
                                WebSocketCodec.readFrameWith(
                                    stream,
                                    transportStream,
                                    config.maxFrameSize,
                                    (cr: (Int, String)) => closeReasonRef.set(Present(cr)),
                                    mask = true
                                ) { (frame, remaining) =>
                                    inbound.put(frame).andThen(Loop.continue(remaining))
                                }
                            }
                        }.map { readFiber =>
                            Fiber.initUnscoped {
                                // Signal peer close and close inbound (so consumers of ws.stream see natural termination).
                                // Do NOT close outbound here; the write fiber owns outbound lifecycle (closes on its own exit
                                // via user-initiated ws.close, write failure, or the outer Sync.ensure cleanup). Users that want
                                // to react to peer close compose ws.onPeerClose into their sender/receiver race.
                                readFiber.getResult.map { result =>
                                    val log = result match
                                        case Result.Failure(_) => Kyo.unit
                                        case Result.Panic(t)   => Log.warn("HttpWebSocket client reader panicked", t)
                                        case Result.Success(_) => Kyo.unit
                                    log.andThen(inbound.close.unit).andThen(peerClosedPromise.completeUnit.unit)
                                }
                            }.map { monitorFiber =>
                                Fiber.initUnscoped {
                                    // Write fiber: drains outbound to wire. Exits on outbound.close OR wire-write failure.
                                    // On wire failure (peer-dead, transport error), closes outbound so subsequent ws.put
                                    // calls fail with Closed, preserving the put-after-close contract.
                                    Abort.run[Throwable] {
                                        Loop.foreach {
                                            outbound.take.map { frame =>
                                                WebSocketCodec.writeFrame(transportStream, frame, mask = true).andThen(Loop.continue)
                                            }
                                        }
                                    }.map { result =>
                                        val log = result match
                                            case Result.Failure(_: Closed) => Kyo.unit
                                            case Result.Failure(e)         => Log.warn(s"HttpWebSocket client writer failed: $e")
                                            case Result.Panic(t)           => Log.warn("HttpWebSocket client writer panicked", t)
                                            case Result.Success(_)         => Kyo.unit
                                        log.andThen(closeReasonRef.get).map {
                                            case Present((code, reason)) =>
                                                Abort.run[Any](WebSocketCodec.writeClose(transportStream, code, reason, mask = true)).unit
                                            case Absent => Kyo.unit
                                        }.andThen(outbound.close).unit
                                    }
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
        }
    end serveWebSocketWith

    // -- Pool management and orchestration layer --

    @volatile private var closingGracePeriod: Duration = Duration.Zero

    /** Track a newly created connection. If the client is already closing, register closes it immediately and the
      * in-flight request then fails on the closed connection, the same outcome as an explicit close-after-add but
      * without the race where a concurrent closeAll could drop a connection it had not closed.
      */
    private def trackConn(conn: HttpConnection)(using AllowUnsafe, Frame): Unit =
        discard(registry.register(conn)(c => closeUnsafe(c, closingGracePeriod)))
    end trackConn

    /** Release a connection back to the pool or discard it on error. Does NOT touch the registry; removal happens only when a connection
      * is actually closed (the pool's discard hook calls registry.remove).
      */
    private def releaseConn(key: HttpAddress, conn: HttpConnection, error: Maybe[Result.Error[Any]])(using AllowUnsafe): Unit =
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
                    val responseFiber = sendViaBackend(conn, route, request, config.maxResponseLength)
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
                                val responseFiber = sendViaBackend(conn, route, request, config.maxResponseLength)
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
        // Client-side filters (e.g. basicAuth, bearerAuth) are Passthrough, they transform the request
        // and forward next's result unchanged.
        // Auto-discovered filters (e.g. W3C trace context from kyo-stats-otlp) are composed first.
        val autoFilter =
            if config.autoFilters then HttpFilter.Factory.composedClient
            else HttpFilter.noop
        val clientFilter = autoFilter.andThen(config.clientFilter)
        val routeFilter  = route.filter
        if (clientFilter eq HttpFilter.noop) && (routeFilter eq HttpFilter.noop) then
            // Fast path: no filters configured, call impl directly without filter closure
            poolWithImpl(route, request, config)(f)
        else
            val filter = clientFilter.andThen(routeFilter)
                .asInstanceOf[HttpFilter[Any, In, Out, Out, Nothing]]
            filter[In, Out, HttpException, Any](
                request,
                (filteredReq: HttpRequest[In]) =>
                    poolWithImpl(route, filteredReq, config)(f)
                        .asInstanceOf[HttpResponse[Out] < (Async & Abort[HttpException | HttpResponse.Halt])]
            ).asInstanceOf[A < (Async & Abort[HttpException])]
        end if
    end poolWith

    /** Dispatch to the appropriate unsafe backend method based on route type. */
    private def sendViaBackend[In, Out](
        conn: HttpConnection,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In],
        maxResponseLength: Int
    )(using AllowUnsafe, Frame): Fiber.Unsafe[HttpResponse[Out], Abort[HttpException]] =
        // HEAD responses never have a body (RFC 9110 Section 9.3.2),
        // so always use the buffered path which skips body reading for HEAD.
        if request.method == HttpMethod.HEAD then
            sendBuffered(conn, route, request, maxResponseLength)
        else if RouteUtil.isStreamingResponse(route) then
            sendStreaming(conn, route, request, maxResponseLength)
        else
            sendBuffered(conn, route, request, maxResponseLength)

    /** True once `closeFiber` has closed the pool. For testing the Scope-based `init`'s release path only. */
    private[kyo] def isPoolClosed(using AllowUnsafe): Boolean = pool.isClosed

    /** True when this client built and owns a per-config transport rather than sharing the process-global one. For testing the transport
      * selection in `HttpClient.initUnscoped` only.
      */
    private[kyo] def hasOwnTransport(using AllowUnsafe): Boolean = ownsTransport

    def closeFiber(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        // Mark closing FIRST so any new connection gets closed immediately by trackConn.
        closingGracePeriod = gracePeriod
        registry.markClosing()
        // Close the pool to stop reuse, then close every tracked connection. The registry holds every connection from
        // creation to close (the pool's discard removes them), and idle pooled connections are a subset, so closing from
        // the registry covers everything.
        discard(pool.close())
        val closePromise = Promise.Unsafe.init[Unit, Any]()
        registry.closeAll(conn => closeUnsafe(conn, gracePeriod))
        // Release a per-config transport this client owns (the shared global transport is never closed here).
        if ownsTransport then transport.close()
        closePromise.completeDiscard(Result.succeed(()))
        closePromise
    end closeFiber

end HttpClientBackend

private[kyo] object HttpClientBackend:

    /** Create a fully pooled backend for production use.
      *
      * `transportConfig` carries this client's byte-transport and HTTP-parser tuning (notably `maxHeaderSize`, the HTTP header limit the
      * client parser enforces). `ownsTransport` is true when the caller built a per-config transport for this client (rather than reusing the
      * shared global one); when true, closing the client also closes that transport so its driver and pool are released.
      */
    def init(
        transport: kyo.net.Transport,
        maxConnsPerHost: Int,
        idleConnectionTimeout: Duration,
        defaultTlsConfig: HttpTlsConfig = HttpTlsConfig.default,
        transportConfig: HttpTransportConfig = HttpTransportConfig.default,
        ownsTransport: Boolean = false
    )(using AllowUnsafe, Frame): HttpClientBackend =
        val registry = new kyo.internal.ConnectionRegistry[HttpConnection]
        val pool = ConnectionPool.init[HttpConnection](
            maxConnsPerHost,
            idleConnectionTimeout,
            conn => conn.transport.isOpen,
            conn =>
                registry.remove(conn)
                conn.http1.close()
                conn.transport.close()
        )
        new HttpClientBackend(
            transport,
            transportConfig,
            defaultTlsConfig,
            pool,
            registry,
            maxConnsPerHost,
            ownsTransport,
            summon[Frame]
        )
    end init

end HttpClientBackend
