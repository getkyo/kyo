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
import kyo.net.internal.util.GrowableByteBuffer
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

    /** Singleton used on the normal idle-timer cancel path (no error message needed). Avoids allocating a new
      * Closed instance on every keep-alive request. Error paths that carry a meaningful message still construct
      * a fresh Closed.
      */
    private[kyo] val IdleTimerClosed: Closed =
        new Closed("idle timer", Frame.internal, "")(using Frame.internal)

    /** Singleton used to interrupt a handler fiber still running when the connection closes (see `inflightHandler` in `serveH1`). Avoids
      * allocating a new Closed instance on every dispatch. Observed only by the keep-alive `onComplete`, which ignores fiber results, so
      * this never surfaces as a logged panic.
      */
    private[kyo] val HandlerConnectionClosed: Closed =
        new Closed("connection", Frame.internal, "")(using Frame.internal)

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
        config: HttpServerConfig,
        onClosing: Maybe[Fiber.Unsafe[Unit, Any]] = Absent,
        closeConnection: Maybe[() => Unit] = Absent
    )(using AllowUnsafe, Frame): Unit =
        serveH1(router, inbound, outbound, config, Array.emptyByteArray, 0, onClosing, closeConnection)

    /** Set up HTTP/1.1 dispatch. Injects any pre-read bytes into the parser. */
    private def serveH1(
        router: HttpRouter,
        inbound: Channel.Unsafe[Span[Byte]],
        outbound: Channel.Unsafe[Span[Byte]],
        config: HttpServerConfig,
        initialBytes: Array[Byte],
        initialLen: Int,
        onClosing: Maybe[Fiber.Unsafe[Unit, Any]] = Absent,
        closeConnection: Maybe[() => Unit] = Absent
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

        // Per-connection in-flight handler slot: HTTP/1.1 dispatch is serial (the next dispatch only
        // happens after the previous handler's onComplete restarts the parser), so one slot suffices.
        // AtomicRef, not a plain var like idleTimerFiber: the slot is read from the close watcher below
        // (fires on a pump/driver thread) and written from dispatchHandler (a parser-callback thread).
        val inflightHandler = AtomicRef.Unsafe.init[Maybe[Fiber.Unsafe[Unit, Any]]](Absent)

        // Connection-close watcher: interrupts whatever handler is currently in flight when the connection
        // begins closing. This is the only trigger that is live WHILE a handler is running (the parser
        // itself is dormant between onRequestParsed and the keep-alive restart), so it is what reclaims a
        // handler parked on a foreign await (a backend call, a promise nobody completes) that never touches
        // inbound/outbound and would otherwise leak for the process lifetime. The signal is the connection's
        // `onClosing` fiber (kyo.net.Connection, completed in closeFn's win branch), passed in by the server;
        // the connection-less bare-channel test path passes Absent and arms no watcher.
        onClosing.foreach { closing =>
            closing.onComplete { _ =>
                inflightHandler.get() match
                    case Present(fiber) => discard(fiber.interrupt(Result.Panic(HandlerConnectionClosed)))
                    case Absent         => ()
            }
        }

        /** Tears the connection down now: flushes whatever is queued, completes onClosing so an in-flight handler is interrupted, and
          * reclaims the fd. Used by every path that answers a request and then ends the connection, since none of them can rely on the
          * idle timer, which is either cancelled by then or was never armed.
          */
        def closeConnectionNow(): Unit =
            closeConnection match
                case Present(closeFn) => closeFn()
                case Absent           =>
                    // Inbound only. These paths have just WRITTEN a response, and closing outbound here would discard it
                    // before anything could read it: the peer would be refused with no explanation, which is the failure
                    // this close was added to prevent. Closing inbound already ends the connection logically by stopping
                    // any further read. The connection-backed branch above has no such tension because closeFn flushes
                    // the queued tail through closeAwaitEmpty before reclaiming the fd.
                    discard(inbound.close())

        def cancelIdleTimer(): Unit =
            idleTimerFiber match
                case Present(fiber) =>
                    discard(fiber.interrupt(Result.Panic(IdleTimerClosed)))
                    idleTimerFiber = Absent
                case Absent => ()

        def startIdleTimer(): Unit =
            if idleTimeoutEnabled then
                val fiber = Clock.live.unsafe.sleep(idleTimeout)
                idleTimerFiber = Present(fiber)
                fiber.onComplete { result =>
                    result match
                        case Result.Success(_) =>
                            // Timer fired — connection has been idle too long, close it. Route through the
                            // connection's close when connection-backed: `conn.close()` runs closeFn's win
                            // branch, which completes `onClosing` synchronously (so a request racing the idle
                            // expiry with a handler parked on a foreign await is still interrupted), reclaims
                            // the fd synchronously, and flushes the queued outbound tail via closeAwaitEmpty
                            // instead of dropping it. The bare-channel test path (Absent) closes the channels
                            // directly, which is sufficient there since no handler-interrupt watcher is armed.
                            closeConnection match
                                case Present(closeFn) => closeFn()
                                case Absent =>
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

        // Hoisted per-connection closure: parser and restartParserKeepAlive are both connection-scoped,
        // so this val is safe to allocate once and reuse across all requests on the connection.
        lazy val restartParserFn: () => Unit = () => restartParserKeepAlive(parser)

        /** Answers a rejected request and then either keeps the connection alive or tears it down.
          *
          * A request answered with an error still has its declared body (Content-Length or chunked) on the wire, and
          * the dispatch is not going to consume it. Reusing the connection would let those unconsumed bytes be parsed
          * as the next request (RFC 9112 section 9.3, the unconsumed-body smuggling class, e.g. Undertow
          * CVE-2020-10719). Keep-alive is preserved only when the request is keep-alive AND carries no body to strand;
          * otherwise the answer carries `Connection: close` (RFC 9112 section 9.6) and the connection is closed. The
          * `write` callback receives the connectionClose flag so it announces the close on the answer it writes.
          */
        def answerAndContinue(request: ParsedRequest, write: Boolean => Unit): Unit =
            if request.isKeepAlive && !hasUnconsumedBody(request) then
                write(false)
                restartParserKeepAlive(parser)
            else
                write(true)
                closeConnectionNow()
            end if
        end answerAndContinue

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
                    // Answer 400 and continue per the shared rule: keep-alive only when there is no body to strand,
                    // otherwise Connection: close and tear the connection down. A request with no headers is the
                    // no-body, non-keep-alive case that used to leak the fd by answering without closing or rearming.
                    answerAndContinue(request, close => writeBadRequest(streamCtx, connectionClose = close))
                else
                    val cl  = request.contentLength
                    val max = config.maxContentLength

                    // Content-Length enforcement: reject before routing if body exceeds limit.
                    // Per RFC 9110 section 8.6, when both Content-Length and Transfer-Encoding
                    // are present, Transfer-Encoding wins and Content-Length is ignored.
                    if cl > max && !request.isChunked then
                        // The over-limit body is never consumed (417 withholds it, 413 declined it), so the connection
                        // cannot be reused: answerAndContinue closes it (RFC 9112 section 9.3). The 417-vs-413
                        // selection on expectContinue is preserved.
                        answerAndContinue(
                            request,
                            close =>
                                if request.expectContinue then writeExpectationFailed(streamCtx, connectionClose = close)
                                else writePayloadTooLarge(streamCtx, connectionClose = close)
                        )
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
                                    restartParserFn,
                                    () => closeConnectionNow(),
                                    inflightHandler,
                                    onClosing
                                )
                            case Result.Failure(error) =>
                                answerAndContinue(request, close => writeErrorResponse(streamCtx, error, connectionClose = close))
                            case Result.Panic(t) =>
                                Log.live.unsafe.error("UnsafeServerDispatch: router panic", t)
                                answerAndContinue(request, close => writeInternalError(streamCtx, connectionClose = close))
                        end match
                    end if
                end if
            ,
            onClosed = () =>
                cancelIdleTimer(),
            // A request the parser refused (RFC 9112 section 6.3 framing it cannot determine, a malformed escape, a
            // field that is not a token) is answered 400 before the connection goes away. The connection is not
            // restarted for keep-alive afterwards, unlike the Host-header 400 above: there the message was framed and
            // only its content was wrong, so the next request's boundary is known, whereas here the framing itself is
            // in doubt and any remaining bytes cannot be trusted to start a request.
            onInvalidRequest = () =>
                // Answering is only half of it. Not restarting keep-alive is not the same as closing, and answering
                // without closing is worse than staying silent: the peer sees a complete, well-framed 400, keeps a
                // connection it believes is healthy, and sends its next request into a socket nothing is reading. So the
                // answer carries Connection: close (RFC 9112 section 9.6) and the connection is actually torn down.
                // Closing through closeFn also flushes the queued 400 rather than dropping it, and reclaims the fd,
                // which the idle timer can no longer do for us because onClosed has just cancelled it.
                writeBadRequest(streamCtx, connectionClose = true)
                closeConnection match
                    case Present(closeFn) => closeFn()
                    case Absent =>
                        discard(inbound.close())
                        discard(outbound.close())
                end match
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
      * @param inflightHandler
      *   Connection-scoped slot tracking the currently running handler fiber, watched by the connection-close watcher armed in `serveH1`
      *   (see the connection's `onClosing`) so a handler parked on a foreign await gets interrupted instead of leaking past connection close.
      * @param onClosing
      *   The connection's close signal (Absent on the bare-channel test path). Used for the register-then-recheck below that closes the
      *   dispatch-vs-close race: if the watcher already fired before this fiber was registered in the slot, the recheck catches it.
      */
    private def dispatchHandler(
        router: HttpRouter,
        lookup: RouteLookup,
        streamCtx: Http1StreamContext,
        request: ParsedRequest,
        config: HttpServerConfig,
        parser: Http1Parser,
        routeLookup: RouteLookup,
        restartParser: () => Unit,
        closeNow: () => Unit,
        inflightHandler: AtomicRef.Unsafe[Maybe[Fiber.Unsafe[Unit, Any]]],
        onClosing: Maybe[Fiber.Unsafe[Unit, Any]]
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
                // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala); IOTask is an IOPromise
                // subtype, structurally different from that alias even though both erase to the same runtime object. The alias is transparent
                // only inside kyo.Fiber's own defining scope, so exposing the scheduled task as the Fiber.Unsafe[Unit, Any] the inflight slot
                // holds needs this erased-boundary cast. Safe: the task runs serveRequest (a Unit computation) and settles only with its result.
                val fiber = IOTask(serveRequest(router, endpoint, lookup, streamCtx, request, config), Trace.init, Context.empty)
                    .asInstanceOf[Fiber.Unsafe[Unit, Any]]
                inflightHandler.set(Present(fiber))
                // Recheck: the watcher may have already fired (and seen Absent, or a prior completed fiber)
                // before this fiber was registered. Consult the connection's close signal directly, not
                // channel.closed() (which reports only FullyClosed): the peer-FIN teardown uses
                // closeAwaitEmpty and sits in HalfOpen with pipelined bytes still queued while it closes,
                // whereas onClosing is completed unconditionally at close-start.
                if onClosing.exists(_.done()) then
                    discard(fiber.interrupt(Result.Panic(HandlerConnectionClosed)))
                // Registered for both keep-alive and Connection: close dispatches: the leak is identical
                // for both, and interrupting an already-completed fiber is a harmless no-op.
                if request.isKeepAlive then
                    fiber.onComplete { _ =>
                        if streamCtx.mustCloseConnection then
                            // A handler-path rejection (e.g. a chunked body over maxContentLength answered 413) left
                            // the declared body unconsumed; reusing the connection would reparse it (RFC 9112 section
                            // 9.3), so close instead of restarting keep-alive.
                            closeNow()
                        else
                            val leftover = streamCtx.takeLeftover()
                            parser.injectLeftover(leftover)
                            restartParser()
                        end if
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
        // Carry the query string into the handler's request url: a WebSocket upgrade target
        // may put data in the query (e.g. the Slack Socket Mode connection ticket), so a
        // handler must be able to read it via req.query, exactly as a non-upgrade request can.
        val url  = HttpUrl(Absent, "", 0, request.pathAsString, request.queryRawString)
        val conn = new ChannelBackedStream(streamCtx.inbound, streamCtx.outbound)
        discard(IOTask(
            Abort.run[Any](
                WebSocketCodec.acceptUpgrade(conn, headers, wsHandler.wsConfig).andThen {
                    serveWebSocket(conn, streamCtx.inbound, streamCtx.outbound, wsHandler, headers, url)
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
        url: HttpUrl
    )(using Frame): Unit < Async =
        Channel.initUnscopedWith[HttpWebSocket.Payload](wsHandler.wsConfig.bufferSize) { inbound =>
            Channel.initUnscopedWith[HttpWebSocket.Payload](wsHandler.wsConfig.bufferSize) { outbound =>
                AtomicRef.initWith(Absent: Maybe[(Int, String)]) { closeReasonRef =>
                    Fiber.Promise.init[Unit, Any].map { peerClosedPromise =>
                        // closeFn routes ws.close through the outbound channel: set closeReasonRef + close outbound.
                        // The write fiber drains remaining frames then emits the close frame inline (single writer to
                        // conn), eliminating the put/close race that surfaced under caliban WS load on arm64 CI.
                        val closeFn: (Int, String) => Unit < Async = (code, reason) =>
                            closeReasonRef.set(Present((code, reason))).andThen {
                                outbound.close.unit
                            }
                        val ws      = new HttpWebSocket(inbound, outbound, closeReasonRef, peerClosedPromise, closeFn)
                        val request = HttpRequest(HttpMethod.GET, url, headers, Record.empty)

                        Fiber.initUnscoped {
                            Loop(conn.read) { stream =>
                                WebSocketCodec.readFrameWith(
                                    stream,
                                    conn,
                                    wsHandler.wsConfig.maxFrameSize,
                                    (cr: (Int, String)) => closeReasonRef.set(Present(cr)),
                                    mask = false
                                ) { (frame, remaining) =>
                                    inbound.put(frame).andThen(Loop.continue(remaining))
                                }
                            }
                        }.map { readFiber =>
                            Fiber.initUnscoped {
                                // Monitor: on read EOF (peer close), close inbound and complete peerClosedPromise so
                                // ws.onPeerClose fires. Outbound is intentionally NOT closed here — applications that
                                // want the sender fiber to exit promptly on peer close compose ws.onPeerClose into
                                // their own race (see HttpWebSocket.onPeerClose docs).
                                readFiber.getResult.map { result =>
                                    val log = result match
                                        case Result.Failure(_) => Kyo.unit
                                        case Result.Panic(t)   => Log.warn("HttpWebSocket server reader panicked", t)
                                        case Result.Success(_) => Kyo.unit
                                    log.andThen(inbound.close.unit).andThen(peerClosedPromise.completeUnit.unit)
                                }
                            }.map { monitorFiber =>
                                Fiber.initUnscoped {
                                    // Write fiber: drains outbound, then emits the close frame inline (single writer
                                    // to conn), then closes outbound. Broader Abort.run[Throwable] preserves the
                                    // pre-existing "log + close outbound on wire failure" contract.
                                    Abort.run[Throwable] {
                                        Loop.foreach {
                                            outbound.take.map { frame =>
                                                WebSocketCodec.writeFrame(conn, frame, mask = false).andThen(Loop.continue)
                                            }
                                        }
                                    }.map { result =>
                                        val log = result match
                                            case Result.Failure(_: Closed) => Kyo.unit
                                            case Result.Failure(e)         => Log.warn(s"HttpWebSocket server writer failed: $e")
                                            case Result.Panic(t)           => Log.warn("HttpWebSocket server writer panicked", t)
                                            case Result.Success(_)         => Kyo.unit
                                        log.andThen {
                                            closeReasonRef.get.map {
                                                case Present((code, reason)) =>
                                                    Abort.run[Any](WebSocketCodec.writeClose(conn, code, reason, mask = false)).unit
                                                case Absent => Kyo.unit
                                            }
                                        }.andThen(outbound.close.unit)
                                    }
                                }.map { writeFiber =>
                                    Sync.ensure(
                                        readFiber.interrupt.unit
                                            .andThen(writeFiber.interrupt.unit)
                                            .andThen(monitorFiber.interrupt.unit)
                                            .andThen(outbound.close.unit)
                                    ) {
                                        Abort.run[Any](wsHandler.wsHandler(request, ws)).map { _ =>
                                            // After handler returns: if no close reason was registered AND the reader
                                            // hasn't already observed EOF, install a 1000 close reason and signal the
                                            // write fiber by closing outbound. Then await writeFiber so the close frame
                                            // hits the wire before the Sync.ensure finalizer interrupts the write fiber.
                                            closeReasonRef.get.map {
                                                case Absent =>
                                                    readFiber.done.map { isDone =>
                                                        if isDone then Kyo.unit
                                                        else closeReasonRef.set(Present((1000, ""))).andThen(outbound.close.unit)
                                                    }
                                                case _ => outbound.close.unit
                                            }.andThen(writeFiber.get.unit)
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
                    Abort.run[Closed | HttpMalformedBodyException | HttpPayloadTooLargeException](
                        ChunkedBodyDecoder.readStreaming(
                            streamCtx.bodyChannel,
                            initialBytes,
                            decodedChan.unsafe,
                            maxControlBytes = config.maxContentLength
                        )
                    ).map { result =>
                        result match
                            case Result.Panic(t) =>
                                Log.error("UnsafeServerDispatch: chunked decoder panic", t)
                            case Result.Failure(malformed: HttpMalformedBodyException) =>
                                // Malformed chunk framing mid-stream: the delivered body is truncated at the fault.
                                // Closing the decoded channel ends the handler's stream; the framing is undeterminable.
                                Log.warn(s"UnsafeServerDispatch: malformed chunked request body: ${malformed.getMessage}")
                            case Result.Failure(tooLarge: HttpPayloadTooLargeException) =>
                                // The chunk control plane (an unterminated size line or trailer) exceeded the bound.
                                Log.warn(s"UnsafeServerDispatch: chunked control bytes over limit: ${tooLarge.getMessage}")
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
            // Body bytes (for buffered requests). A chunked request body carries no Content-Length, so it is
            // dechunked here bounded by maxContentLength (RFC 9112 section 6.1); a Content-Length body is read by
            // readBody, which accumulates from the inbound channel. Both suspend the Kyo fiber (channel.safe.take)
            // without blocking OS threads. readBuffered aborts HttpPayloadTooLargeException when the decoded body
            // exceeds the limit.
            val readBodyEffect: Span[Byte] < (Async & Abort[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException]) =
                if request.isChunked then
                    ChunkedBodyDecoder.readBuffered(streamCtx.bodyChannel, streamCtx.takeBodySpan(), config.maxContentLength)
                else
                    streamCtx.readBody()
            Abort.run[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException](readBodyEffect).map {
                case Result.Failure(_: HttpPayloadTooLargeException) =>
                    // The chunked body exceeded maxContentLength. Answer 413 and close: the unread body tail cannot be
                    // left on a reused connection (RFC 9112 section 9.3), so mark the connection for closure.
                    Sync.Unsafe.defer {
                        writePayloadTooLarge(streamCtx, connectionClose = true)
                        streamCtx.requestConnectionClose()
                    }
                case Result.Failure(malformed: HttpMalformedBodyException) =>
                    // The chunked framing is malformed (embedded CR, bare LF, invalid size, missing CRLF). Answer 400
                    // and close: the body boundary is undeterminable, so the connection cannot be safely reused.
                    writeDecodeError(streamCtx, HttpStatus(400), malformed).andThen(
                        Sync.Unsafe.defer(streamCtx.requestConnectionClose())
                    )
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
                            // The Content-Length: 0 head fully frames the response: no body, and no chunked last-chunk
                            // terminator (which belongs only to a chunked response and would desync the next response).
                            discard(streamCtx.respond(status, hdrs.add("Content-Length", "0")))
                        },
                    onBuffered = (status, hdrs, responseBody) =>
                        Sync.Unsafe.defer {
                            val withLen = hdrs.add("Content-Length", responseBody.size)
                            val writer  = streamCtx.respond(status, withLen)
                            // A HEAD response is bodyless (RFC 9112 section 6.3); the Content-Length head frames it, so
                            // write the body only for a non-HEAD request and never a chunked terminator.
                            if !isHead then writer.writeBody(responseBody)
                        },
                    onStreaming = (status, hdrs, responseStream) =>
                        if isHead then
                            Sync.Unsafe.defer {
                                // HEAD mirrors GET's chunked framing header but writes no body and no last-chunk
                                // terminator; a HEAD response is terminated by the blank line after the head (RFC 9112
                                // section 6.3, RFC 9110 section 9.3.2).
                                discard(streamCtx.respond(status, hdrs.add("Transfer-Encoding", "chunked")))
                            }
                        else
                            Sync.Unsafe.defer {
                                val writer = streamCtx.respond(status, hdrs.add("Transfer-Encoding", "chunked"))
                                Abort.run[Any](
                                    responseStream.foreach { chunk =>
                                        // Use safe.put for backpressure instead of offer() which silently drops data when full.
                                        // Closed is NOT swallowed here: it must propagate so a disconnected client aborts the
                                        // foreach instead of the handler stream being pulled forever into a dead outbound.
                                        val formatted = Http1StreamContext.formatChunkSpan(chunk)
                                        streamCtx.outbound.safe.put(formatted)
                                    }
                                ).map { result =>
                                    result match
                                        case Result.Panic(t) =>
                                            Log.error("UnsafeServerDispatch: streaming response error", t).andThen(
                                                Sync.Unsafe.defer(writer.finish())
                                            )
                                        case Result.Failure(_: Closed) =>
                                            // Routine peer disconnect mid-stream: not an error, so no log noise.
                                            Sync.Unsafe.defer(writer.finish())
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
                                // Content-Length framed; write the body only for a non-HEAD response that has content.
                                // An empty or HEAD response is framed by its head, with no chunked terminator.
                                if !isHead && haltBody.size > 0 then writer.writeBody(haltBody)
                            }
                        }
                    case other =>
                        endpoint.encodeError(other) match
                            case Present((status, hdrs, errorBody)) =>
                                Sync.Unsafe.defer {
                                    val withLen = hdrs.add("Content-Length", errorBody.size)
                                    val writer  = streamCtx.respond(status, withLen)
                                    if !isHead && errorBody.size > 0 then writer.writeBody(errorBody)
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
    /** True when the request declared a body (Content-Length > 0 or Transfer-Encoding: chunked) that a rejecting
      * dispatch is not going to consume, so the connection cannot be safely reused (RFC 9112 section 9.3).
      */
    private def hasUnconsumedBody(request: ParsedRequest): Boolean =
        request.isChunked || request.contentLength > 0

    private def writeErrorResponse(
        streamCtx: Http1StreamContext,
        error: HttpRouter.FindError,
        connectionClose: Boolean = false
    )(using AllowUnsafe, Frame): Unit =
        // Announce a pending connection close (RFC 9112 section 9.6) so the peer can tell this error, after which the
        // connection is gone, from an ordinary one after which it may reuse the connection.
        def withClose(headers: HttpHeaders): HttpHeaders =
            if connectionClose then headers.add("Connection", "close") else headers
        error match
            case HttpRouter.FindError.NotFound =>
                val bodyBytes = RouteUtil.encodeErrorBody(HttpStatus(404))
                val writer = streamCtx.respond(
                    HttpStatus(404),
                    withClose(
                        HttpHeaders.empty
                            .add("Content-Type", "application/json")
                            .add("Content-Length", bodyBytes.size)
                    )
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
                    withClose(
                        HttpHeaders.empty
                            .add("Allow", allow)
                            .add("Content-Type", "application/json")
                            .add("Content-Length", bodyBytes.size)
                    )
                )
                writer.writeBody(bodyBytes)
            case HttpRouter.FindError.Options(headers) =>
                // 204 head fully frames the response: no body and no chunked last-chunk terminator.
                discard(streamCtx.respond(HttpStatus(204), withClose(headers)))
        end match
    end writeErrorResponse

    /** Write a 404 Not Found response. */
    private def writeNotFound(
        streamCtx: Http1StreamContext
    )(using AllowUnsafe, Frame): Unit =
        val bodyBytes = RouteUtil.encodeErrorBody(HttpStatus(404))
        val writer = streamCtx.respond(
            HttpStatus(404),
            HttpHeaders.empty
                .add("Content-Type", "application/json")
                .add("Content-Length", bodyBytes.size)
        )
        writer.writeBody(bodyBytes)
    end writeNotFound

    /** Write a 400 Bad Request response (e.g. missing or invalid Host header per RFC 9110 section 7.2).
      *
      * `connectionClose` adds the `Connection: close` header, and must be set whenever the caller is about to tear the connection down.
      * RFC 9112 section 9.6 requires a sender to announce it, and without it the peer has no way to tell this 400 (after which the
      * connection is gone) from an ordinary one (after which it may reuse the connection).
      */
    private def writeBadRequest(
        streamCtx: Http1StreamContext,
        connectionClose: Boolean = false
    )(using AllowUnsafe, Frame): Unit =
        val bodyBytes = RouteUtil.encodeErrorBody(HttpStatus(400))
        val baseHeaders = HttpHeaders.empty
            .add("Content-Type", "application/json")
            .add("Content-Length", bodyBytes.size)
        val writer = streamCtx.respond(
            HttpStatus(400),
            if connectionClose then baseHeaders.add("Connection", "close") else baseHeaders
        )
        writer.writeBody(bodyBytes)
    end writeBadRequest

    /** Write a 500 Internal Server Error. */
    private def writeInternalError(
        streamCtx: Http1StreamContext,
        connectionClose: Boolean = false
    )(using AllowUnsafe, Frame): Unit =
        val bodyBytes = RouteUtil.encodeErrorBody(HttpStatus(500))
        val baseHeaders = HttpHeaders.empty
            .add("Content-Type", "application/json")
            .add("Content-Length", bodyBytes.size)
        val writer = streamCtx.respond(
            HttpStatus(500),
            if connectionClose then baseHeaders.add("Connection", "close") else baseHeaders
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
        streamCtx: Http1StreamContext,
        connectionClose: Boolean = false
    )(using AllowUnsafe, Frame): Unit =
        val bodyBytes = RouteUtil.encodeErrorBody(HttpStatus(413))
        val baseHeaders = HttpHeaders.empty
            .add("Content-Type", "application/json")
            .add("Content-Length", bodyBytes.size)
        val writer = streamCtx.respond(
            HttpStatus(413),
            if connectionClose then baseHeaders.add("Connection", "close") else baseHeaders
        )
        writer.writeBody(bodyBytes)
    end writePayloadTooLarge

    /** Write a 417 Expectation Failed response. */
    private def writeExpectationFailed(
        streamCtx: Http1StreamContext,
        connectionClose: Boolean = false
    )(using AllowUnsafe, Frame): Unit =
        val bodyBytes = RouteUtil.encodeErrorBody(HttpStatus(417))
        val baseHeaders = HttpHeaders.empty
            .add("Content-Type", "application/json")
            .add("Content-Length", bodyBytes.size)
        val writer = streamCtx.respond(
            HttpStatus(417),
            if connectionClose then baseHeaders.add("Connection", "close") else baseHeaders
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
            case Result.Panic(t) =>
                Log.live.unsafe.error("UnsafeServerDispatch: panic writing 100 Continue", t)
        end match
    end writeContinue

    /** Build path captures Dict from RouteLookup indices + ParsedRequest segments. URL-decodes capture values and reconstructs the full
      * remaining path for rest captures.
      */
    private[internal] def buildCaptures(
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
