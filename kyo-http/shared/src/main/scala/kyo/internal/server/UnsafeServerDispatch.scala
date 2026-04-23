package kyo.internal.server

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZonedDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kyo.*
import kyo.internal.codec.*
import kyo.internal.http1.*
import kyo.internal.util.*
import kyo.internal.websocket.*
import kyo.kernel.internal.Context
import kyo.kernel.internal.Trace
import kyo.scheduler.IOTask
import scala.annotation.tailrec

/** Server dispatch using the unsafe parser-driven architecture.
  *
  * Bridges from parser callback (unsafe) to handler invocation (safe Kyo fibers). One instance per server is used as a static entry point —
  * per-connection state lives in the closures passed to Http1Parser and Http1StreamContext, not in this object.
  *
  * The parser calls `onRequestParsed` synchronously in the event-loop callback (unsafe context). Routing and validation happen immediately,
  * then `IOTask` crosses the safe/unsafe boundary: it schedules the handler fiber on the Kyo scheduler without producing a suspended `< S`
  * computation, so it can be called from inside the unsafe callback.
  *
  * Host header validation (RFC 9110 §7.2), Content-Length enforcement, keep-alive idle timeouts, 100-Continue, and HttpWebSocket upgrade
  * are all handled here before the handler is invoked.
  */
private[kyo] object UnsafeServerDispatch:

    // -- Date header caching (RFC 9110 section 6.6.1) --

    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)

    @volatile private var cachedDateSecond: Long  = 0L
    @volatile private var cachedDateValue: String = ""

    /** Returns the current Date header value, cached per second. */
    private[internal] def currentDate(): String =
        val nowSecond = java.lang.System.currentTimeMillis() / 1000
        if nowSecond != cachedDateSecond then
            val dt = ZonedDateTime.ofInstant(
                Instant.ofEpochSecond(nowSecond),
                ZoneOffset.UTC
            )
            cachedDateValue = dateFormatter.format(dt)
            cachedDateSecond = nowSecond
        end if
        cachedDateValue
    end currentDate

    /** Set up parser-driven dispatch for a connection.
      *
      * Called once per accepted connection. Proceeds directly with HTTP/1.1 parsing.
      */
    def serve(
        router: HttpRouter,
        inbound: Channel.Unsafe[Span[Byte]],
        outbound: Channel.Unsafe[Span[Byte]],
        config: HttpServerConfig
    )(using AllowUnsafe, Frame): Unit =
        serveH1(router, inbound, outbound, config, Array.emptyByteArray, 0)

    /** Set up HTTP/1.1 dispatch. Injects any pre-read bytes into the parser. */
    private def serveH1(
        router: HttpRouter,
        inbound: Channel.Unsafe[Span[Byte]],
        outbound: Channel.Unsafe[Span[Byte]],
        config: HttpServerConfig,
        initialBytes: Array[Byte],
        initialLen: Int
    )(using AllowUnsafe, Frame): Unit =
        val builder   = new ParsedRequestBuilder
        val headerBuf = new GrowableByteBuffer
        val lookup    = new RouteLookup(router.maxCaptures)
        val streamCtx = new Http1StreamContext(inbound, outbound, headerBuf)

        // Idle timeout: schedule a timer when waiting for the next keep-alive request.
        // When the timer fires, close the inbound channel which causes the parser to
        // see a closed channel and terminate the connection.
        val idleTimeout                                    = config.idleTimeout
        val idleTimeoutEnabled                             = idleTimeout.isFinite
        var idleTimerFiber: Maybe[Fiber.Unsafe[Unit, Any]] = Absent

        def cancelIdleTimer(): Unit =
            idleTimerFiber match
                case Present(fiber) =>
                    discard(fiber.interrupt(Result.Panic(new Closed("idle timer", summon[Frame], ""))))
                    idleTimerFiber = Absent
                case Absent => ()

        def startIdleTimer(): Unit =
            if idleTimeoutEnabled then
                val fiber = Clock.live.unsafe.sleep(idleTimeout)
                idleTimerFiber = Present(fiber)
                fiber.onComplete { result =>
                    result match
                        case Result.Success(_) =>
                            // Timer fired — connection has been idle too long, close it
                            discard(inbound.close())
                            discard(outbound.close())
                        case _ => () // Timer was interrupted (cancelled), do nothing
                }

        /** Restart the parser for keep-alive with idle timeout. */
        def restartParserKeepAlive(parser: Http1Parser): Unit =
            startIdleTimer()
            parser.reset()
            lookup.reset()
            parser.start()
        end restartParserKeepAlive

        // Note on re-entrancy: onRequestParsed fires inside parse(), which may call
        // parser.start() -> needMoreBytes(). This is correct (tail position) but worth
        // documenting for future maintainers.
        lazy val parser: Http1Parser = new Http1Parser(
            inbound,
            builder,
            maxHeaderSize = config.transportConfig.maxHeaderSize,
            onRequestParsed = (request, bodySpan) =>
                // Cancel idle timer — a request has arrived
                cancelIdleTimer()

                // Host header validation (RFC 9110 section 7.2):
                // - Missing Host header -> 400
                // - Empty Host header value -> 400
                // - Multiple Host headers -> 400
                val hostInvalid = !request.hasHost || request.hasMultipleHost || request.hasEmptyHost
                if hostInvalid then
                    writeBadRequest(streamCtx)
                    if request.isKeepAlive then
                        restartParserKeepAlive(parser)
                    end if
                else
                    val cl  = request.contentLength
                    val max = config.maxContentLength

                    // Content-Length enforcement: reject before routing if body exceeds limit.
                    // Per RFC 9110 section 8.6, when both Content-Length and Transfer-Encoding
                    // are present, Transfer-Encoding wins and Content-Length is ignored.
                    if cl > max && !request.isChunked then
                        if request.expectContinue then
                            writeExpectationFailed(streamCtx)
                        else
                            writePayloadTooLarge(streamCtx)
                        end if
                        if request.isKeepAlive then
                            restartParserKeepAlive(parser)
                        end if
                    else
                        val method = request.method
                        router.findParsed(method, request, lookup) match
                            case Result.Success(()) =>
                                streamCtx.setRequest(request, bodySpan)
                                // Send 100 Continue if client expects it and CL is within limits
                                if request.expectContinue then
                                    writeContinue(streamCtx)
                                dispatchHandler(
                                    router,
                                    lookup,
                                    streamCtx,
                                    request,
                                    config,
                                    parser,
                                    lookup,
                                    () => restartParserKeepAlive(parser)
                                )
                            case Result.Failure(error) =>
                                writeErrorResponse(streamCtx, error)
                                if request.isKeepAlive then
                                    restartParserKeepAlive(parser)
                                end if
                            case Result.Panic(t) =>
                                Log.live.unsafe.error("UnsafeServerDispatch: router panic", t)
                                writeInternalError(streamCtx)
                                if request.isKeepAlive then
                                    restartParserKeepAlive(parser)
                                end if
                        end match
                    end if
                end if
            ,
            onClosed = () =>
                cancelIdleTimer()
        )

        // Inject any pre-read bytes into the parser
        if initialLen > 0 then
            val leftover = if initialLen == initialBytes.length then initialBytes
            else
                val arr = new Array[Byte](initialLen)
                java.lang.System.arraycopy(initialBytes, 0, arr, 0, initialLen)
                arr
            parser.injectLeftover(Span.fromUnsafe(leftover))
        end if
        parser.start()
    end serveH1

    /** Dispatch a matched request to the handler in a new fiber.
      *
      * This is the safe/unsafe boundary. `IOTask` directly schedules a fiber that runs the handler in safe Kyo context. We use IOTask
      * instead of `Fiber.initUnscoped` because the latter returns a suspended computation (`Fiber[...] < Sync`) which cannot be evaluated
      * inside an unsafe callback. IOTask immediately schedules the fiber on the Kyo scheduler. The fiber borrows the connection's channels
      * (does not own resources).
      *
      * @param restartParser
      *   Callback to restart the parser for keep-alive, including idle timeout scheduling.
      */
    private def dispatchHandler(
        router: HttpRouter,
        lookup: RouteLookup,
        streamCtx: Http1StreamContext,
        request: ParsedRequest,
        config: HttpServerConfig,
        parser: Http1Parser,
        routeLookup: RouteLookup,
        restartParser: () => Unit
    )(using AllowUnsafe, Frame): Unit =
        val endpoint = router.endpoint(lookup)
        endpoint match
            case wsHandler: WebSocketHttpHandler if request.isUpgrade =>
                // HttpWebSocket upgrade: take any leftover bytes from the parser buffer,
                // inject them into the inbound channel so WebSocketCodec can consume them,
                // then dispatch to the WS handler. Parser is NOT restarted (WS is terminal).
                val leftover = parser.takeRemainingBytes()
                if !leftover.isEmpty then
                    discard(streamCtx.inbound.offer(leftover))
                dispatchWebSocket(wsHandler, streamCtx, request, parser)
            case _: WebSocketHttpHandler =>
                // WS handler but no upgrade headers -- not a valid WS handshake
                writeNotFound(streamCtx)
                if request.isKeepAlive then
                    restartParser()
                end if
            case _ if request.isUpgrade =>
                // Upgrade request on a non-WS route -- return 404
                writeNotFound(streamCtx)
                if request.isKeepAlive then
                    restartParser()
                end if
            case _ =>
                val fiber = IOTask(serveRequest(router, endpoint, lookup, streamCtx, request, config), Trace.init, Context.empty)
                if request.isKeepAlive then
                    fiber.onComplete { _ =>
                        val leftover = streamCtx.takeLeftover()
                        parser.injectLeftover(leftover)
                        restartParser()
                    }
                end if
        end match
    end dispatchHandler

    /** Dispatch a HttpWebSocket upgrade in a new fiber.
      *
      * Creates a ChannelBackedStream from the connection's channels, sends the 101 Switching Protocols response via
      * WebSocketCodec.acceptUpgrade, then runs the HttpWebSocket session. The HTTP parser is NOT restarted — HttpWebSocket is terminal for
      * the connection.
      */
    private def dispatchWebSocket(
        wsHandler: WebSocketHttpHandler,
        streamCtx: Http1StreamContext,
        request: ParsedRequest,
        parser: Http1Parser
    )(using AllowUnsafe, Frame): Unit =
        val headers = HttpHeaders.fromPacked(request.headersAsPacked)
        val path    = request.pathAsString
        val conn    = new ChannelBackedStream(streamCtx.inbound, streamCtx.outbound)
        discard(IOTask(
            Abort.run[Any](
                WebSocketCodec.acceptUpgrade(conn, headers, wsHandler.wsConfig).andThen {
                    serveWebSocket(conn, streamCtx.inbound, streamCtx.outbound, wsHandler, headers, path)
                }
            ).map {
                case Result.Failure(error) =>
                    Log.error(s"UnsafeServerDispatch: HttpWebSocket upgrade failed: $error")
                case Result.Panic(t) =>
                    Log.error("UnsafeServerDispatch: HttpWebSocket upgrade panic", t)
                case Result.Success(_) => Kyo.unit
            }.unit,
            Trace.init,
            Context.empty
        ))
    end dispatchWebSocket

    /** Three concurrent fibers: read loop, write loop, user handler. Server does NOT mask (RFC 6455 section 5.1).
      *
      * Inbound channel feeds decoded frames to the user handler, outbound channel sends frames written by the user handler to the wire.
      */
    private def serveWebSocket(
        conn: ChannelBackedStream,
        inboundRaw: Channel.Unsafe[Span[Byte]],
        outboundRaw: Channel.Unsafe[Span[Byte]],
        wsHandler: WebSocketHttpHandler,
        headers: HttpHeaders,
        path: String
    )(using Frame): Unit < Async =
        Channel.initUnscopedWith[HttpWebSocket.Payload](wsHandler.wsConfig.bufferSize) { inbound =>
            Channel.initUnscopedWith[HttpWebSocket.Payload](wsHandler.wsConfig.bufferSize) { outbound =>
                AtomicRef.initWith(Absent: Maybe[(Int, String)]) { closeReasonRef =>
                    val closeFn: (Int, String) => Unit < Async = (code, reason) =>
                        closeReasonRef.set(Present((code, reason))).andThen {
                            WebSocketCodec.writeClose(conn, code, reason, mask = false)
                        }
                    val ws = new HttpWebSocket(inbound, outbound, closeReasonRef, closeFn)
                    val wsUrl = HttpUrl.parse(path) match
                        case Result.Success(u) => u
                        case _                 => HttpUrl.parse("/").getOrThrow
                    val request = HttpRequest(HttpMethod.GET, wsUrl, headers, Record.empty)

                    Fiber.initUnscoped {
                        Loop(conn.read) { stream =>
                            WebSocketCodec.readFrameWith(stream, conn) { (frame, remaining) =>
                                inbound.put(frame).andThen(Loop.continue(remaining))
                            }
                        }
                    }.map { readFiber =>
                        Fiber.initUnscoped {
                            readFiber.getResult.map { _ =>
                                inbound.close.unit.andThen(outbound.close.unit)
                            }
                        }.map { monitorFiber =>
                            Fiber.initUnscoped {
                                Abort.run[Closed] {
                                    Loop.foreach {
                                        outbound.take.map { frame =>
                                            WebSocketCodec.writeFrame(conn, frame, mask = false).andThen(Loop.continue)
                                        }
                                    }
                                }.andThen(outbound.close).unit
                            }.map { writeFiber =>
                                Sync.ensure(
                                    readFiber.interrupt.unit
                                        .andThen(writeFiber.interrupt.unit)
                                        .andThen(monitorFiber.interrupt.unit)
                                        .andThen(outbound.close.unit)
                                ) {
                                    Abort.run[Any](wsHandler.wsHandler(request, ws)).map { _ =>
                                        closeReasonRef.get.map {
                                            case Absent =>
                                                readFiber.done.map { isDone =>
                                                    if isDone then Kyo.unit
                                                    else Abort.run[Any](WebSocketCodec.writeClose(conn, 1000, "", mask = false)).unit
                                                }
                                            case _ => Kyo.unit
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    end serveWebSocket

    /** Serve a request inside safe Kyo context.
      *
      * Builds HttpHeaders and path captures from the ParsedRequest + RouteLookup, then delegates to the endpoint's
      * serveBuffered/serveStreaming method (which handles RouteUtil decoding internally). Finally encodes the response and writes it via
      * the StreamContext.
      *
      * Note: `request.method`, `request.pathAsString`, `request.headersAsPacked` are pure reads from the immutable packed byte array.
      * `streamCtx.readBody` accesses the mutable `_bodySpan` field which is safe because the callback runs synchronously -- the body is set
      * before this method is invoked and not modified until the next request.
      */
    private def serveRequest(
        router: HttpRouter,
        endpoint: HttpHandler[?, ?, ?],
        lookup: RouteLookup,
        streamCtx: Http1StreamContext,
        request: ParsedRequest,
        config: HttpServerConfig
    )(using Frame): Unit < Async =
        val method = request.method
        val path   = request.pathAsString
        // Use packed headers directly -- avoids N String decodes per request.
        // The ParsedRequest's header section is extracted as a standalone packed array
        // compatible with HttpHeaders.fromPacked format.
        val headers = HttpHeaders.fromPacked(request.headersAsPacked)
        val isHead  = method == HttpMethod.HEAD

        // Build path captures from lookup indices + ParsedRequest segment strings
        val captureNames = router.captureNames(lookup)
        val captures     = buildCaptures(request, lookup, captureNames)

        // Build query param -- construct HttpUrl directly from components instead of
        // re-parsing via string interpolation to avoid unnecessary allocation.
        val queryParam = buildQueryParam(request, path)

        if lookup.isStreamingRequest && request.isChunked then
            // Chunked streaming: decode chunked framing via ChunkedBodyDecoder
            // into a temporary channel, then stream from that channel.
            val initialBytes = streamCtx.takeBodySpan()
            Channel.initUnscopedWith[Span[Byte]](16) { decodedChan =>
                Fiber.initUnscoped {
                    Abort.run[Closed](
                        ChunkedBodyDecoder.readStreaming(streamCtx.bodyChannel, initialBytes, decodedChan.unsafe)
                    ).map { result =>
                        result match
                            case Result.Panic(t) =>
                                Log.error("UnsafeServerDispatch: chunked decoder panic", t)
                            case Result.Failure(_: Closed) =>
                                Log.warn("UnsafeServerDispatch: chunked decoder channel closed")
                            case Result.Success(_) => Kyo.unit
                    }.andThen(decodedChan.closeAwaitEmpty.unit)
                }.map { decoderFiber =>
                    val bodyStream  = decodedChan.streamUntilClosed()
                    val serveResult = endpoint.serveStreaming(captures, queryParam, headers, bodyStream, path, method)
                    Sync.ensure(decoderFiber.interrupt.unit) {
                        serveResult match
                            case Result.Failure(error) =>
                                val status = error match
                                    case _: HttpUnsupportedMediaTypeException => HttpStatus(415)
                                    case _                                    => HttpStatus(400)
                                writeDecodeError(streamCtx, status, error)
                            case Result.Panic(e) =>
                                Log.error("UnsafeServerDispatch: serve decode panic", e).andThen(
                                    Sync.Unsafe.defer(writeInternalError(streamCtx))
                                )
                            case Result.Success(handlerComputation) =>
                                dispatchHandler(handlerComputation, endpoint, streamCtx, isHead)
                        end match
                    }
                }
            }
        else
            // Body bytes (for buffered requests).
            // readBody accumulates from the inbound channel if Content-Length > initial body span.
            // Uses channel.safe.take which suspends the Kyo fiber without blocking OS threads.
            Abort.run[Closed](streamCtx.readBody()).map {
                case Result.Failure(_: Closed) =>
                    // Channel closed before full body arrived -- connection lost, nothing to respond to
                    Log.error("UnsafeServerDispatch: inbound channel closed before body was fully read")
                case Result.Panic(t) =>
                    Log.error("UnsafeServerDispatch: panic reading body", t).andThen(
                        Sync.Unsafe.defer(writeInternalError(streamCtx))
                    )
                case Result.Success(bodyBytes) =>

                    // Decode + invoke via HttpHandler.serve* -- types resolved through endpoint, no casts
                    val serveResult =
                        if lookup.isStreamingRequest then
                            val bodyStream =
                                if bodyBytes.isEmpty then Stream.empty[Span[Byte]]
                                else Stream.init(Seq(bodyBytes))
                            endpoint.serveStreaming(captures, queryParam, headers, bodyStream, path, method)
                        else
                            endpoint.serveBuffered(captures, queryParam, headers, bodyBytes, path, method)

                    serveResult match
                        case Result.Failure(error) =>
                            val status = error match
                                case _: HttpUnsupportedMediaTypeException => HttpStatus(415)
                                case _                                    => HttpStatus(400)
                            writeDecodeError(streamCtx, status, error)
                        case Result.Panic(e) =>
                            Log.error("UnsafeServerDispatch: serve decode panic", e).andThen(
                                Sync.Unsafe.defer(writeInternalError(streamCtx))
                            )
                        case Result.Success(handlerComputation) =>
                            dispatchHandler(handlerComputation, endpoint, streamCtx, isHead)
                    end match
            }
        end if
    end serveRequest

    /** Runs the handler computation and encodes the response. The `handlerComputation` retains endpoint-specific types from
      * `endpoint.serveBuffered/serveStreaming` so that `endpoint.encodeResponse` can be called with proper types.
      */
    private def dispatchHandler(
        handlerComputation: Any,
        endpoint: HttpHandler[?, ?, ?],
        streamCtx: Http1StreamContext,
        isHead: Boolean
    )(using Frame): Unit < Async =
        Abort.run[Any](handlerComputation).map {
            case Result.Success(response) =>
                endpoint.encodeResponseUnchecked(response)(
                    onEmpty = (status, hdrs) =>
                        Sync.Unsafe.defer {
                            val writer = streamCtx.respond(status, hdrs.add("Content-Length", "0"))
                            writer.finish()
                        },
                    onBuffered = (status, hdrs, responseBody) =>
                        Sync.Unsafe.defer {
                            val withLen = hdrs.add("Content-Length", responseBody.size)
                            if isHead then
                                val writer = streamCtx.respond(status, withLen)
                                writer.finish()
                            else
                                val writer = streamCtx.respond(status, withLen)
                                writer.writeBody(responseBody)
                            end if
                        },
                    onStreaming = (status, hdrs, responseStream) =>
                        Sync.Unsafe.defer {
                            val writer = streamCtx.respond(status, hdrs.add("Transfer-Encoding", "chunked"))
                            Abort.run[Any](
                                responseStream.foreach { chunk =>
                                    // Use safe.put for backpressure instead of offer() which silently drops data when full
                                    val formatted = Http1StreamContext.formatChunkSpan(chunk)
                                    Abort.run[Closed](streamCtx.outbound.safe.put(formatted)).unit
                                }
                            ).map { result =>
                                result match
                                    case Result.Panic(t) =>
                                        Log.error("UnsafeServerDispatch: streaming response error", t).andThen(
                                            Sync.Unsafe.defer(writer.finish())
                                        )
                                    case Result.Failure(e) =>
                                        Log.error(s"UnsafeServerDispatch: streaming response aborted: $e").andThen(
                                            Sync.Unsafe.defer(writer.finish())
                                        )
                                    case Result.Success(_) =>
                                        Sync.Unsafe.defer(writer.finish())
                            }
                        }
                )
            case Result.Failure(error) =>
                error match
                    case halt: HttpResponse.Halt =>
                        Sync.Unsafe.defer {
                            RouteUtil.encodeHalt(halt) { (status, hdrs, haltBody) =>
                                val withLen = hdrs.add("Content-Length", haltBody.size)
                                val writer  = streamCtx.respond(status, withLen)
                                if !isHead && haltBody.size > 0 then writer.writeBody(haltBody)
                                else writer.finish()
                            }
                        }
                    case other =>
                        endpoint.encodeError(other) match
                            case Present((status, hdrs, errorBody)) =>
                                Sync.Unsafe.defer {
                                    val withLen = hdrs.add("Content-Length", errorBody.size)
                                    val writer  = streamCtx.respond(status, withLen)
                                    if !isHead && errorBody.size > 0 then writer.writeBody(errorBody)
                                    else writer.finish()
                                }
                            case Absent =>
                                Log.error(s"UnsafeServerDispatch: unhandled handler error: $other").andThen(
                                    Sync.Unsafe.defer(writeInternalError(streamCtx))
                                )
            case Result.Panic(t) =>
                Log.error("UnsafeServerDispatch: handler panic", t).andThen(
                    Sync.Unsafe.defer(writeInternalError(streamCtx))
                )
        }
    end dispatchHandler

    /** Write a router-level error response (404, 405, OPTIONS). */
    private def writeErrorResponse(
        streamCtx: Http1StreamContext,
        error: HttpRouter.FindError
    )(using AllowUnsafe): Unit =
        error match
            case HttpRouter.FindError.NotFound =>
                val bodyBytes = RouteUtil.encodeErrorBody(HttpStatus(404))
                val writer = streamCtx.respond(
                    HttpStatus(404),
                    HttpHeaders.empty
                        .add("Content-Type", "application/json")
                        .add("Content-Length", bodyBytes.size)
                )
                writer.writeBody(bodyBytes)
            case HttpRouter.FindError.MethodNotAllowed(methods) =>
                val augmented =
                    val base     = methods.toSet
                    val withHead = if base.contains(HttpMethod.GET) then base + HttpMethod.HEAD else base
                    withHead + HttpMethod.OPTIONS
                end augmented
                val allow     = augmented.map(_.name).mkString(", ")
                val bodyBytes = RouteUtil.encodeErrorBody(HttpStatus(405))
                val writer = streamCtx.respond(
                    HttpStatus(405),
                    HttpHeaders.empty
                        .add("Allow", allow)
                        .add("Content-Type", "application/json")
                        .add("Content-Length", bodyBytes.size)
                )
                writer.writeBody(bodyBytes)
            case HttpRouter.FindError.Options(headers) =>
                val writer = streamCtx.respond(HttpStatus(204), headers)
                writer.finish()
    end writeErrorResponse

    /** Write a 404 Not Found response. */
    private def writeNotFound(
        streamCtx: Http1StreamContext
    )(using AllowUnsafe): Unit =
        val bodyBytes = RouteUtil.encodeErrorBody(HttpStatus(404))
        val writer = streamCtx.respond(
            HttpStatus(404),
            HttpHeaders.empty
                .add("Content-Type", "application/json")
                .add("Content-Length", bodyBytes.size)
        )
        writer.writeBody(bodyBytes)
    end writeNotFound

    /** Write a 400 Bad Request response (e.g. missing or invalid Host header per RFC 9110 section 7.2). */
    private def writeBadRequest(
        streamCtx: Http1StreamContext
    )(using AllowUnsafe): Unit =
        val bodyBytes = RouteUtil.encodeErrorBody(HttpStatus(400))
        val writer = streamCtx.respond(
            HttpStatus(400),
            HttpHeaders.empty
                .add("Content-Type", "application/json")
                .add("Content-Length", bodyBytes.size)
        )
        writer.writeBody(bodyBytes)
    end writeBadRequest

    /** Write a 500 Internal Server Error. */
    private def writeInternalError(
        streamCtx: Http1StreamContext
    )(using AllowUnsafe): Unit =
        val bodyBytes = RouteUtil.encodeErrorBody(HttpStatus(500))
        val writer = streamCtx.respond(
            HttpStatus(500),
            HttpHeaders.empty
                .add("Content-Type", "application/json")
                .add("Content-Length", bodyBytes.size)
        )
        writer.writeBody(bodyBytes)
    end writeInternalError

    /** Write a decode error response with the error message. */
    private def writeDecodeError(
        streamCtx: Http1StreamContext,
        status: HttpStatus,
        error: Any
    )(using Frame): Unit < Async =
        val message = error match
            case e: HttpException => e.getMessage
            case e: Throwable     => e.getMessage
            case other            => other.toString
        val bodyBytes = RouteUtil.encodeErrorBodyWithMessage(status, message)
        Sync.Unsafe.defer {
            val writer = streamCtx.respond(
                status,
                HttpHeaders.empty
                    .add("Content-Type", "application/json")
                    .add("Content-Length", bodyBytes.size)
            )
            writer.writeBody(bodyBytes)
        }
    end writeDecodeError

    /** Write a 413 Payload Too Large response. */
    private def writePayloadTooLarge(
        streamCtx: Http1StreamContext
    )(using AllowUnsafe): Unit =
        val bodyBytes = RouteUtil.encodeErrorBody(HttpStatus(413))
        val writer = streamCtx.respond(
            HttpStatus(413),
            HttpHeaders.empty
                .add("Content-Type", "application/json")
                .add("Content-Length", bodyBytes.size)
        )
        writer.writeBody(bodyBytes)
    end writePayloadTooLarge

    /** Write a 417 Expectation Failed response. */
    private def writeExpectationFailed(
        streamCtx: Http1StreamContext
    )(using AllowUnsafe): Unit =
        val bodyBytes = RouteUtil.encodeErrorBody(HttpStatus(417))
        val writer = streamCtx.respond(
            HttpStatus(417),
            HttpHeaders.empty
                .add("Content-Type", "application/json")
                .add("Content-Length", bodyBytes.size)
        )
        writer.writeBody(bodyBytes)
    end writeExpectationFailed

    /** Write 100 Continue interim response. */
    private def writeContinue(
        streamCtx: Http1StreamContext
    )(using AllowUnsafe, Frame): Unit =
        val continueBytes = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII)
        streamCtx.outbound.offer(Span.fromUnsafe(continueBytes)) match
            case Result.Success(_)         => ()
            case Result.Failure(_: Closed) => ()
            case Result.Failure(e) =>
                Log.live.unsafe.error("UnsafeServerDispatch: unexpected failure writing 100 Continue", e)
            case Result.Panic(t) =>
                Log.live.unsafe.error("UnsafeServerDispatch: panic writing 100 Continue", t)
        end match
    end writeContinue

    /** Build path captures Dict from RouteLookup indices + ParsedRequest segments. URL-decodes capture values and reconstructs the full
      * remaining path for rest captures.
      */
    private def buildCaptures(
        request: ParsedRequest,
        lookup: RouteLookup,
        captureNames: Span[String]
    ): Dict[String, String] =
        if lookup.captureCount == 0 then Dict.empty[String, String]
        else
            val builder = DictBuilder.init[String, String]
            @tailrec def loop(i: Int): Unit =
                if i < lookup.captureCount && i < captureNames.size then
                    val segIdx = lookup.captureSegmentIndices(i)
                    val value =
                        if i == lookup.restCaptureIdx then
                            // Rest capture: join all remaining segments with '/'
                            request.restPathAsString(segIdx)
                        else
                            // Regular capture: URL-decode the single segment
                            request.pathSegmentAsStringDecoded(segIdx)
                    discard(builder.add(captureNames(i), value))
                    loop(i + 1)
            loop(0)
            builder.result()
        end if
    end buildCaptures

    /** Build query param from ParsedRequest. Constructs HttpUrl directly from already-parsed components to avoid re-parsing via string
      * interpolation.
      */
    private def buildQueryParam(request: ParsedRequest, path: String): Maybe[HttpUrl] =
        if !request.hasQuery then Absent
        else
            request.queryRawString match
                case Present(query) => Present(HttpUrl(Absent, "", 0, path, Present(query)))
                case Absent         => Absent
    end buildQueryParam

end UnsafeServerDispatch
