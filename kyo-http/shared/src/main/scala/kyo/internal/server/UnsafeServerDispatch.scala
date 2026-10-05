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
      *
      * @param clock
      *   Clock the server's waits on the peer run on: the idle timer, the bound on each chunk of a streamed answer, and the lingering
      *   drain before a close. Defaults to `Clock.live`, the wall clock the transport runs on.
      * @param drain
      *   The connection's part in a graceful close: once the server drains, the connection ends at once if it is between requests, and
      *   otherwise after answering the requests it has already received, instead of waiting for another.
      */
    def serve(
        router: HttpRouter,
        inbound: Channel.Unsafe[Span[Byte]],
        outbound: Channel.Unsafe[Span[Byte]],
        config: HttpServerConfig,
        onClosing: Maybe[Fiber.Unsafe[Unit, Any]] = Absent,
        closeConnection: Maybe[() => Unit] = Absent,
        clock: Clock = Clock.live,
        drain: Maybe[Drain] = Absent
    )(using AllowUnsafe, Frame): Unit =
        serveH1(router, inbound, outbound, config, Array.emptyByteArray, 0, onClosing, closeConnection, clock, drain)

    /** One served connection's part in a graceful close. The server raises the shared flag and then asks every connection it tracks to
      * end if idle; the connection reads the flag itself as its parser becomes idle, with nothing received left to serve. Both sides
      * publish before they read, so a connection that becomes idle as the drain begins is ended by one side or the other.
      */
    final private[kyo] class Drain(draining: AtomicBoolean.Unsafe):
        @volatile private var endIfIdleFn: () => Unit = () => ()

        def isDraining(using AllowUnsafe): Boolean = draining.get()

        /** Ends the connection if its parser is idle, a no-op before the connection is served. */
        def endIfIdle(): Unit = endIfIdleFn()

        private[server] def onEndIfIdle(f: () => Unit): Unit = endIfIdleFn = f
    end Drain

    /** Set up HTTP/1.1 dispatch. Injects any pre-read bytes into the parser. */
    private def serveH1(
        router: HttpRouter,
        inbound: Channel.Unsafe[Span[Byte]],
        outbound: Channel.Unsafe[Span[Byte]],
        config: HttpServerConfig,
        initialBytes: Array[Byte],
        initialLen: Int,
        onClosing: Maybe[Fiber.Unsafe[Unit, Any]],
        closeConnection: Maybe[() => Unit],
        clock: Clock,
        drain: Maybe[Drain]
    )(using AllowUnsafe, Frame): Unit =
        val builder   = new ParsedRequestBuilder
        val headerBuf = new GrowableByteBuffer
        val lookup    = new RouteLookup(router.maxCaptures)
        val streamCtx = new Http1StreamContext(inbound, outbound, headerBuf)

        // Idle timeout: schedule a timer when waiting for the next keep-alive request.
        // When the timer fires, close the inbound channel which causes the parser to
        // see a closed channel and terminate the connection.
        val idleTimeout        = config.idleTimeout
        val idleTimeoutEnabled = idleTimeout.isFinite
        // Written by the parser's callbacks and, on a re-arm, by the timer's own completion.
        val idleTimerFiber = AtomicRef.Unsafe.init[Maybe[Fiber.Unsafe[Unit, Any]]](Absent)

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
            cancelIdleTimer()
            closeConnection match
                case Present(closeFn) => closeFn()
                case Absent           =>
                    // Inbound only. These paths have just WRITTEN a response, and closing outbound here would discard it
                    // before anything could read it: the peer would be refused with no explanation, which is the failure
                    // this close was added to prevent. Closing inbound already ends the connection logically by stopping
                    // any further read. The connection-backed branch above has no such tension because closeFn flushes
                    // the queued tail through closeAwaitEmpty before reclaiming the fd.
                    discard(inbound.close())
            end match
        end closeConnectionNow

        /** Ends the connection after its last answer: a request body still owed is read and discarded first, within `lingeringTimeout`. */
        def endConnection(): Unit =
            closeAfterDrain(streamCtx, () => closeConnectionNow(), onClosing, clock, config.lingeringTimeout)

        def cancelIdleTimer(): Unit =
            idleTimerFiber.getAndSet(Absent) match
                case Present(fiber) => discard(fiber.interrupt(Result.Panic(IdleTimerClosed)))
                case Absent         => ()

        /** Arms the idle timer for one window. When it fires, what the connection waits for decides: a head still owed closes the
          * connection; a body still owed closes it only when its reader is suspended on the connection with nothing read since the arming
          * and nothing waiting to be read, otherwise another window is armed (a reader parked on its output, or a body already in hand,
          * is the handler's wait); a handler at work on a body already read is not the peer's wait, so nothing happens until the
          * keep-alive restart arms the timer again. The waiting-bytes test covers the instant between a reader announcing its wait and
          * the take that would have returned at once.
          */
        def startIdleTimer(): Unit =
            if idleTimeoutEnabled then armIdleTimer(Absent)

        /** Arms one window. The registered fiber owns the timer: an arming from the parser's callbacks replaces and cancels whatever was
          * registered, while an arming from a fired timer takes effect only if that timer is still the registered one, and a fired timer
          * closes the connection only if it can unregister itself first. A timer that fires as a request head parses therefore never
          * survives the callback's own arming as a second, orphaned timer that would close a later wait early.
          */
        def armIdleTimer(firedTimer: Maybe[Fiber.Unsafe[Unit, Any]]): Unit =
            val progressAtArm = streamCtx.bodyProgress
            val fiber         = clock.unsafe.sleep(idleTimeout)
            val registered    = firedTimer match
                case Absent =>
                    idleTimerFiber.getAndSet(Present(fiber)).foreach(previous => discard(previous.interrupt(Result.Panic(IdleTimerClosed))))
                    true
                case Present(fired) =>
                    val won = idleTimerFiber.compareAndSet(Present(fired), Present(fiber))
                    if !won then discard(fiber.interrupt(Result.Panic(IdleTimerClosed)))
                    won
            if registered then
                fiber.onComplete {
                    case Result.Success(_) =>
                        streamCtx.phase match
                            // A handler at work owns the connection, a marked one included: its completion closes it or restarts the
                            // parser. A response streamed to an HTTP/1.0 request is marked before its body goes out, and a close here
                            // would cut that body, which the peer frames by the close, into one that reads as complete.
                            case Http1StreamContext.ReadPhase.Handling => ()
                            case Http1StreamContext.ReadPhase.BodyPending | Http1StreamContext.ReadPhase.Draining
                                if !streamCtx.awaitingPeer || streamCtx.bodyProgress != progressAtArm || inbound.size().exists(_ > 0) =>
                                armIdleTimer(Present(fiber))
                            case _ if idleTimerFiber.compareAndSet(Present(fiber), Absent) =>
                                // The peer owes bytes and sent none for a whole window: close. Route through the connection's
                                // close when connection-backed: `conn.close()` runs closeFn's win branch, which completes
                                // `onClosing` synchronously (so a request racing the idle expiry with a handler parked on a
                                // foreign await is still interrupted), reclaims the fd synchronously, and flushes the queued
                                // outbound tail via closeAwaitEmpty instead of dropping it. The bare-channel test path (Absent)
                                // closes the channels directly, which is sufficient there since no handler-interrupt watcher is
                                // armed.
                                closeConnection match
                                    case Present(closeFn) => closeFn()
                                    case Absent           =>
                                        discard(inbound.close())
                                        discard(outbound.close())
                            case _ => () // another arming replaced this timer as it fired
                    case _ => () // Timer was interrupted (cancelled), do nothing
                }
            end if
        end armIdleTimer

        /** Restart the parser for keep-alive with idle timeout. While the server drains, a request already received behind the answered one
          * is still served, and the parser's `onIdle` ends the connection once it would wait on the peer for the next.
          */
        def restartParserKeepAlive(parser: Http1Parser): Unit =
            streamCtx.awaitHead()
            startIdleTimer()
            parser.reset()
            lookup.reset()
            parser.start()
        end restartParserKeepAlive

        // Hoisted per-connection closure: parser and restartParserKeepAlive are both connection-scoped,
        // so this val is safe to allocate once and reuse across all requests on the connection.
        lazy val restartParserFn: () => Unit = () => restartParserKeepAlive(parser)

        /** Continues the connection once the peer has taken the last answer. The wait for the peer to read is the peer's wait, so the
          * idle timer is armed over it; the restart arms it again for the next head. A peer that pipelines without ever reading is closed
          * after one window instead of holding the connection behind its full outbound channel.
          */
        def continueWhenRead(afterRead: () => Unit): Unit =
            streamCtx.awaitHead()
            startIdleTimer()
            streamCtx.whenWritable(afterRead)
        end continueWhenRead

        // The keep-alive continuation after a handler's response: the bytes a body reader left for the next request go back to the
        // parser before it restarts.
        lazy val continueKeepAlive: () => Unit = () =>
            continueWhenRead { () =>
                parser.injectLeftover(streamCtx.takeLeftover())
                restartParserKeepAlive(parser)
            }

        /** Answers a rejected request and then either keeps the connection alive or tears it down.
          *
          * A request answered with an error may still have part of its declared body on the wire, and the dispatch is not going to
          * consume it. Reusing the connection would let those unconsumed bytes be parsed as the next request (RFC 9112 section 9.3, the
          * unconsumed-body smuggling class, e.g. Undertow CVE-2020-10719). Keep-alive is preserved only when the request is keep-alive
          * AND no body bytes remain on the wire: a Content-Length body that arrived with its head was received in full and is dropped
          * with `body`; a chunked body is not decoded on this path, so it counts as still arriving. Otherwise the connection is marked
          * to close before `write` runs, so the answer announces it, and is closed after the answer, after the drain when bytes remain.
          */
        def answerAndContinue(request: ParsedRequest, body: Span[Byte], write: () => Unit): Unit =
            val bodyOnTheWire = request.bodyBeyond(body.size)
            if request.isKeepAlive && !bodyOnTheWire then
                write()
                // The answers to requests a peer pipelines without reading would otherwise queue one write per request: the parser
                // continues once the outbound channel has taken this one.
                continueWhenRead(restartParserFn)
            else
                streamCtx.requestConnectionClose()
                write()
                if bodyOnTheWire then drainThenEnd() else closeConnectionNow()
            end if
        end answerAndContinue

        /** Ends a connection whose request was answered while its peer may still be sending the rest of it (a refused head, a body the
          * dispatch will not read): the rest is read and discarded first, so the answer is not lost to the reset a close with unread
          * bytes causes, bounded by the idle timer for a peer that stops and by the lingering bound for one that does not.
          */
        def drainThenEnd(): Unit =
            streamCtx.startDraining()
            startIdleTimer()
            endConnection()
        end drainThenEnd

        lazy val parser: Http1Parser = new Http1Parser(
            inbound,
            builder,
            maxHeaderSize = config.transportConfig.maxHeaderSize,
            onRequestParsed = (request, bodySpan) =>
                // Cancel idle timer — a request has arrived
                cancelIdleTimer()
                // Before any answer: the response head reads the request's close flags, a rejection's included.
                streamCtx.setRequest(request, bodySpan)

                // Host header validation (RFC 9110 section 7.2):
                // - Missing Host header -> 400
                // - Empty Host header value -> 400
                // - Multiple Host headers -> 400
                val hostInvalid = !request.hasHost || request.hasMultipleHost || request.hasEmptyHost
                if hostInvalid then
                    // Answer 400 and continue per the shared rule: keep-alive only when no body bytes remain on the wire,
                    // otherwise Connection: close and tear the connection down.
                    answerAndContinue(request, bodySpan, () => writeBadRequest(streamCtx))
                else
                    val cl  = request.contentLength
                    val max = config.maxContentLength

                    // Reject before routing if the declared body exceeds the limit. Both Content-Length and Transfer-Encoding is already
                    // 400'd upstream (RFC 9112 section 9.5); the `!request.isChunked` guard skips a chunked body, whose length no header declares.
                    if cl > max && !request.isChunked then
                        // The over-limit body is never consumed (417 withholds it, 413 declined it), so the connection
                        // cannot be reused: answerAndContinue closes it (RFC 9112 section 9.3). The 417-vs-413
                        // selection on expectContinue is preserved.
                        answerAndContinue(
                            request,
                            bodySpan,
                            () =>
                                if request.expectContinue then writeExpectationFailed(streamCtx)
                                else writePayloadTooLarge(streamCtx)
                        )
                    else
                        val method = request.method
                        router.findParsed(method, request, lookup) match
                            case Result.Success(()) =>
                                // A body still on the wire is the peer's to deliver: the timer bounds its silence.
                                if streamCtx.phase == Http1StreamContext.ReadPhase.BodyPending then startIdleTimer()
                                // Send 100 Continue if client expects it and CL is within limits. An HTTP/1.0 request's expectation is
                                // ignored (RFC 9110 section 10.1.1).
                                if request.expectContinue && !request.isHttp10 then
                                    writeContinue(streamCtx)
                                dispatchHandler(
                                    router,
                                    lookup,
                                    streamCtx,
                                    request,
                                    config,
                                    clock,
                                    parser,
                                    lookup,
                                    continueKeepAlive,
                                    () => endConnection(),
                                    write => answerAndContinue(request, bodySpan, write),
                                    inflightHandler,
                                    onClosing
                                )
                            case Result.Failure(error) =>
                                answerAndContinue(request, bodySpan, () => writeErrorResponse(streamCtx, error))
                            case Result.Panic(t) =>
                                Log.live.unsafe.error("UnsafeServerDispatch: router panic", t)
                                answerAndContinue(request, bodySpan, () => writeInternalError(streamCtx))
                        end match
                    end if
                end if
            ,
            onClosed = () =>
                // A refusal reports the parser closed while the drain that follows it still owns the timer.
                if streamCtx.phase != Http1StreamContext.ReadPhase.Draining then cancelIdleTimer(),
            // A request the parser refused (a 400 for framing it cannot determine, RFC 9112 section 6.3, a malformed escape or a
            // field that is not a token; a 431 for a head over maxHeaderSize; a 414 for a request line alone over it) is
            // answered before the connection goes away. The connection is not restarted for keep-alive afterwards, unlike
            // the Host-header 400 above: there the message was framed and only its content was wrong, so the next request's
            // boundary is known, whereas here the framing itself is in doubt and any remaining bytes cannot be trusted to
            // start a request.
            onRefused = status =>
                // Answering is only half of it. Not restarting keep-alive is not the same as closing, and answering
                // without closing is worse than staying silent: the peer sees a complete, well-framed answer, keeps a
                // connection it believes is healthy, and sends its next request into a socket nothing is reading. So the
                // answer carries Connection: close (RFC 9112 section 9.6) and the connection is torn down through the same
                // answer-then-close teardown, which delivers the queued answer and reclaims the fd onClosed's cancel left behind.
                writeRefusal(streamCtx, status)
                // A head over the limit is, by construction, still being sent.
                drainThenEnd()
            ,
            onIdle = () =>
                if drain.exists(_.isDraining) then
                    closeConnectionNow()
                    true
                else false
        )

        // A connection between requests when the drain begins ends now; one with requests received ends after answering them, when its
        // parser goes idle.
        drain.foreach(_.onEndIfIdle(() => if parser.idle then closeConnectionNow()))

        // Inject any pre-read bytes into the parser
        if initialLen > 0 then
            val leftover = if initialLen == initialBytes.length then initialBytes
            else
                val arr = new Array[Byte](initialLen)
                java.lang.System.arraycopy(initialBytes, 0, arr, 0, initialLen)
                arr
            parser.injectLeftover(Span.fromUnsafe(leftover))
        end if
        // Armed before the first read: a peer that connects and never completes a head is closed like an idle keep-alive one.
        startIdleTimer()
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
      * @param answerAndContinue
      *   Writes an answer to a request no handler runs for, announcing and performing the close when the request's body would otherwise
      *   be left unconsumed on a reused connection, and restarts the parser otherwise.
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
        clock: Clock,
        parser: Http1Parser,
        routeLookup: RouteLookup,
        restartParser: () => Unit,
        closeNow: () => Unit,
        answerAndContinue: (() => Unit) => Unit,
        inflightHandler: AtomicRef.Unsafe[Maybe[Fiber.Unsafe[Unit, Any]]],
        onClosing: Maybe[Fiber.Unsafe[Unit, Any]]
    )(using AllowUnsafe, Frame): Unit =
        val endpoint = router.endpoint(lookup)
        endpoint match
            case _ if onClosing.exists(_.done()) =>
                // The connection began closing before this request was dispatched (its head, its body and the close arrived together): no
                // handler runs for a peer that is gone, nothing is written, and the connection is closed.
                closeNow()
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
                answerAndContinue(() => writeErrorResponse(streamCtx, HttpRouter.FindError.NotFound))
            case _ if request.isUpgrade =>
                // Upgrade request on a non-WS route -- return 404
                answerAndContinue(() => writeErrorResponse(streamCtx, HttpRouter.FindError.NotFound))
            case _ =>
                // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala); IOTask is an IOPromise
                // subtype, structurally different from that alias even though both erase to the same runtime object. The alias is transparent
                // only inside kyo.Fiber's own defining scope, so exposing the scheduled task as the Fiber.Unsafe[Unit, Any] the inflight slot
                // holds needs this erased-boundary cast. Safe: the task runs serveRequest (a Unit computation) and settles only with its result.
                val fiber = IOTask.detached(serveRequest(router, endpoint, lookup, streamCtx, request, config, clock))
                    .asInstanceOf[Fiber.Unsafe[Unit, Any]]
                // Nothing reads a handler fiber's result, so a panic that is not a connection-lifecycle interrupt
                // (a Closed sentinel) would vanish silently.
                fiber.onComplete {
                    case p: Result.Panic if !p.exception.isInstanceOf[Closed] =>
                        Log.live.unsafe.error("UnsafeServerDispatch: handler fiber panic", p.exception)
                    case _ => ()
                }
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
                            restartParser()
                        end if
                    }
                else
                    // The response to a request that carried Connection: close, or to an HTTP/1.0 request without keep-alive, is the
                    // connection's last (RFC 9112 section 9.6); the close drains a body left unread and delivers the queued response
                    // before reclaiming the fd.
                    fiber.onComplete(_ => closeNow())
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
        val headers = request.headers
        // Carry the query string into the handler's request url: a WebSocket upgrade target
        // may put data in the query (e.g. the Slack Socket Mode connection ticket), so a
        // handler must be able to read it via req.query, exactly as a non-upgrade request can.
        val url  = HttpUrl(Absent, "", 0, request.pathAsString, request.queryRawString)
        val conn = new ChannelBackedStream(streamCtx.inbound, streamCtx.outbound)
        discard(IOTask.detached(
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
            }.unit
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
                        // closeFn routes ws.close through the outbound channel: it sets closeReasonRef and closes outbound
                        // keeping its queued frames, so the write fiber, the single writer to conn, writes every frame put
                        // before the close and then the Close frame.
                        val closeFn: (Int, String) => Unit < Async = (code, reason) =>
                            closeReasonRef.set(Present((code, reason))).andThen {
                                WebSocketCodec.closeKeepingQueued(outbound)
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
                                // Monitor: on read EOF (peer close), close inbound keeping the frames that arrived before
                                // it, and complete peerClosedPromise so ws.onPeerClose fires. Outbound is intentionally NOT
                                // closed here: applications that want the sender fiber to exit promptly on peer close
                                // compose ws.onPeerClose into their own race (see HttpWebSocket.onPeerClose docs).
                                readFiber.getResult.map { result =>
                                    val log = result match
                                        case Result.Failure(_) => Kyo.unit
                                        case Result.Panic(t)   => Log.warn("HttpWebSocket server reader panicked", t)
                                        case Result.Success(_) => Kyo.unit
                                    log.andThen(WebSocketCodec.closeKeepingQueued(inbound)).andThen(peerClosedPromise.completeUnit.unit)
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
                                        }.andThen(outbound.closeDiscard)
                                    }
                                }.map { writeFiber =>
                                    Sync.ensure(
                                        readFiber.interrupt.unit
                                            .andThen(writeFiber.interrupt.unit)
                                            .andThen(monitorFiber.interrupt.unit)
                                            .andThen(outbound.closeDiscard)
                                    ) {
                                        Abort.run[Any](wsHandler.wsHandler(request, ws)).map { _ =>
                                            // After handler returns: if no close reason was registered AND the reader
                                            // hasn't already observed EOF, install a 1000 close reason. Then close outbound in
                                            // every case, since the write fiber exits only once outbound is closed, and await it
                                            // so the queued frames and any close frame hit the wire before the Sync.ensure
                                            // finalizer interrupts it. The wait is bounded by closeTimeout, so a peer that stops
                                            // reading cannot hold the session open.
                                            closeReasonRef.get.map {
                                                case Absent =>
                                                    readFiber.done.map { isDone =>
                                                        if isDone then Kyo.unit
                                                        else closeReasonRef.set(Present((1000, "")))
                                                    }
                                                case _ => Kyo.unit
                                            }.andThen(WebSocketCodec.closeKeepingQueued(outbound))
                                                .andThen(Abort.run[Timeout](
                                                    Async.timeout(wsHandler.wsConfig.closeTimeout)(writeFiber.get)
                                                ).unit)
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
      * Note: `request.method`, `request.pathAsString`, `request.headers` are pure reads from the immutable packed byte array.
      * `streamCtx.readBody` accesses the mutable `_bodySpan` field which is safe because the callback runs synchronously -- the body is set
      * before this method is invoked and not modified until the next request.
      */
    private def serveRequest[In, Out, E](
        router: HttpRouter,
        endpoint: HttpHandler[In, Out, E],
        lookup: RouteLookup,
        streamCtx: Http1StreamContext,
        request: ParsedRequest,
        config: HttpServerConfig,
        clock: Clock
    )(using Frame): Unit < Async =
        val method  = request.method
        val path    = request.pathAsString
        val headers = request.headers
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
            val state        = new ChunkedBodyDecoder.DecoderState
            // Two atomics cross from the decode fiber to the handler's stream and to the dispatch: `fault`, why the decode ended before the
            // terminal chunk, written before the decoded channel is closed and read after it closed or after the handler settled; and
            // `decoded`, whether the decode reached the terminal chunk and recorded the bytes after it as the next request. Until it has,
            // the body's remaining bytes are on the connection, and a keep-alive restart would read them as the next request (RFC 9112
            // section 9.3).
            AtomicRef.initWith[Maybe[Throwable], Unit, Async](Absent) { fault =>
                AtomicBoolean.initWith(false) { decoded =>
                    Channel.initUnscopedWith[Span[Byte]](16) { decodedChan =>
                        Fiber.initUnscoped {
                            Abort.run[Closed | HttpMalformedBodyException | HttpPayloadTooLargeException](
                                ChunkedBodyDecoder.readStreaming(
                                    streamCtx.bodyChannel,
                                    initialBytes,
                                    decodedChan.unsafe,
                                    maxControlBytes = config.maxContentLength,
                                    state,
                                    onProgress = () => streamCtx.noteBodyProgress(),
                                    onAwait = () => streamCtx.awaitPeer()
                                )
                            ).map {
                                case Result.Success(_) =>
                                    // Unsafe: hands the bytes after the terminal chunk to the connection's context and says so in one
                                    // step, so the settle below never reads the flag between the two.
                                    Sync.Unsafe.defer {
                                        streamCtx.setLeftover(state.takePending())
                                        streamCtx.bodyComplete()
                                        decoded.unsafe.set(true)
                                    }
                                case Result.Failure(_: Closed) =>
                                    fault.set(Present(HttpConnectionClosedException(HttpConnectionClosedException.Phase.BodyTruncated)))
                                // A refused framing, an over-limit control plane and a decode that did not end (the handler settled first
                                // and interrupted it) all leave the rest of the body on the wire: the close that follows drains it first.
                                case Result.Failure(malformed: HttpMalformedBodyException)  => fault.set(Present(malformed))
                                case Result.Failure(tooLarge: HttpPayloadTooLargeException) => fault.set(Present(tooLarge))
                                case Result.Panic(t)                                        => fault.set(Present(t))
                            }.andThen(decodedChan.closeAwaitEmpty.unit)
                        }.map { decoderFiber =>
                            // The stream ends with the decode's fault after the bytes that arrived: a body that ends before its terminal
                            // chunk is incomplete (RFC 9112 section 8), and a stream that merely ended would pass for a complete one. An
                            // HttpException is a failure on the stream's row; anything else was a panic and stays one.
                            val bodyStream = Stream[Span[Byte], Async & Abort[HttpException]] {
                                decodedChan.streamUntilClosed().emit.andThen(fault.get).map {
                                    case Present(e: HttpException) => Abort.fail(e)
                                    case Present(t)                => Abort.panic(t)
                                    case Absent                    => Kyo.unit
                                }
                            }
                            val serveResult =
                                endpoint.serveStreaming(
                                    captures,
                                    queryParam,
                                    headers,
                                    bodyStream,
                                    config.maxMultipartPartSize,
                                    path,
                                    method
                                )
                            // Unsafe: marks the connection for closure on the handler's fiber, before its completion reads the mark.
                            // The decode is stopped before `decoded` is read: a decode that had already reached the terminal chunk stays
                            // decoded, one still running is interrupted and the body stays owed.
                            val settle = decoderFiber.interrupt.andThen(decoded.get).map { d =>
                                if !d then Sync.Unsafe.defer(streamCtx.requestConnectionClose()) else Kyo.unit
                            }
                            Sync.ensure(settle) {
                                serveResult match
                                    case Result.Failure(error) =>
                                        writeDecodeError(streamCtx, error)
                                    case Result.Panic(e) =>
                                        Log.error("UnsafeServerDispatch: serve decode panic", e).andThen(
                                            Sync.Unsafe.defer(writeInternalError(streamCtx))
                                        )
                                    case Result.Success(handlerComputation) =>
                                        dispatchHandler(handlerComputation, endpoint, streamCtx, isHead, config, clock, Present(fault))
                                end match
                            }
                        }
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
                    val state = new ChunkedBodyDecoder.DecoderState
                    ChunkedBodyDecoder.readBuffered(
                        streamCtx.bodyChannel,
                        streamCtx.takeBodySpan(),
                        config.maxContentLength,
                        state,
                        () => streamCtx.noteBodyProgress(),
                        () => streamCtx.awaitPeer()
                    ).map { body =>
                        // The bytes the decoder read past the terminal chunk and trailers are the next request.
                        Sync.defer {
                            streamCtx.setLeftover(state.takePending())
                            streamCtx.bodyComplete()
                            body
                        }
                    }
                else
                    streamCtx.readBody()
            Abort.run[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException](readBodyEffect).map {
                case Result.Failure(_: HttpPayloadTooLargeException) =>
                    // The chunked body exceeded maxContentLength. Answer 413 and close: the unread body tail cannot be
                    // left on a reused connection (RFC 9112 section 9.3), so mark the connection for closure.
                    Sync.Unsafe.defer {
                        streamCtx.requestConnectionClose()
                        writePayloadTooLarge(streamCtx)
                    }
                case Result.Failure(malformed: HttpMalformedBodyException) =>
                    // The chunked framing is malformed (embedded CR, bare LF, invalid size, missing CRLF). Answer 400
                    // and close: the body boundary is undeterminable, so the connection cannot be safely reused.
                    Sync.Unsafe.defer(streamCtx.requestConnectionClose()).andThen(writeDecodeError(streamCtx, malformed))
                case Result.Failure(_: Closed) =>
                    // Channel closed before full body arrived -- connection lost, nothing to respond to
                    Log.error("UnsafeServerDispatch: inbound channel closed before body was fully read")
                case Result.Panic(t) =>
                    Log.error("UnsafeServerDispatch: panic reading body", t).andThen(
                        Sync.Unsafe.defer(writeInternalError(streamCtx))
                    )
                case Result.Success(bodyBytes) =>

                    // Decode + invoke via HttpHandler.serve* -- types resolved through endpoint, no casts.
                    //
                    // When several routes are registered on the same node and method
                    // — `/block/{height}` and `/block/{hash}` differ only in what
                    // their captures accept — a path that fails to decode is not an
                    // error yet: it means this candidate did not match. Follow the
                    // chain and try the next. The loop runs at most once per
                    // registered alternative and only on a request that would
                    // otherwise be rejected; the single-route case exits on the
                    // first iteration having done one extra array read.
                    @tailrec def serveCandidate(
                        current: HttpHandler[?, ?, ?],
                        currentCaptures: Dict[String, String]
                    ): Unit < Async =
                        val serveResult =
                            if lookup.isStreamingRequest then
                                val bodyStream =
                                    if bodyBytes.isEmpty then Stream.empty[Span[Byte]]
                                    else Stream.init(Seq(bodyBytes))
                                current.serveStreaming(
                                    currentCaptures,
                                    queryParam,
                                    headers,
                                    bodyStream,
                                    config.maxMultipartPartSize,
                                    path,
                                    method
                                )
                            else
                                current.serveBuffered(currentCaptures, queryParam, headers, bodyBytes, path, method)

                        serveResult match
                            case Result.Failure(error) =>
                                // Only a path decode failure can mean "wrong
                                // candidate". A bad query param, header or body is
                                // a genuine client error on a route that did match,
                                // and must not silently fall through to another.
                                val pathMismatch = error match
                                    case _: HttpPathDecodeException => true
                                    case _                          => false
                                if pathMismatch && router.advanceToNextCandidate(lookup) then
                                    val next = router.endpoint(lookup)
                                    serveCandidate(next, buildCaptures(request, lookup, router.captureNames(lookup)))
                                else writeDecodeError(streamCtx, error)
                                end if
                            case Result.Panic(e) =>
                                Log.error("UnsafeServerDispatch: serve decode panic", e).andThen(
                                    Sync.Unsafe.defer(writeInternalError(streamCtx))
                                )
                            case Result.Success(handlerComputation) =>
                                dispatchHandler(handlerComputation, current, streamCtx, isHead, config, clock)
                        end match
                    end serveCandidate

                    serveCandidate(endpoint, captures)
            }
        end if
    end serveRequest

    /** Runs the handler computation and encodes the response.
      *
      * Generic over the endpoint's types so the computation keeps its pending type: erased to `Any` it re-enters
      * the kernel through the lift, which nests it as data, and the unrun computation is delivered as the response.
      *
      * @param bodyFault
      *   For a streamed chunked body, the fault its decode ended with. A handler that read the stream to that fault settles with it
      *   as a panic, which is answered as the body's fault (a 400 for refused framing, a 413 for a control plane over the limit,
      *   nothing for a peer that closed), not as the handler's own.
      */
    private def dispatchHandler[Out, E](
        handlerComputation: HttpResponse[Out] < (Async & Abort[E | HttpResponse.Halt]),
        endpoint: HttpHandler[?, Out, E],
        streamCtx: Http1StreamContext,
        isHead: Boolean,
        config: HttpServerConfig,
        clock: Clock,
        bodyFault: Maybe[AtomicRef[Maybe[Throwable]]] = Absent
    )(using Frame): Unit < Async =
        // Abort.run[Any] rather than the precise E | Halt: E is abstract here and has no ConcreteTag.
        Abort.run[Any](handlerComputation).map {
            case Result.Success(response) =>
                endpoint.encodeResponse(response)(
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
                        // A response to an HTTP/1.0 request carries no Transfer-Encoding (RFC 9112 section 6.1): its body is the raw
                        // bytes, delimited by the close that follows it, which the head announces whatever the request asked.
                        val http10 = streamCtx.request.isHttp10
                        if isHead then
                            Sync.Unsafe.defer {
                                // HEAD mirrors GET's framing header but writes no body and no last-chunk terminator; a HEAD response
                                // is terminated by the blank line after the head (RFC 9112 section 6.3, RFC 9110 section 9.3.2).
                                val framed = if http10 then hdrs else hdrs.add("Transfer-Encoding", "chunked")
                                discard(streamCtx.respond(status, framed))
                            }
                        else
                            Sync.Unsafe.defer {
                                val framed = if http10 then hdrs else hdrs.add("Transfer-Encoding", "chunked")
                                if http10 then streamCtx.requestConnectionClose()
                                val writer = streamCtx.respond(status, framed)
                                Abort.run[Any](
                                    responseStream.foreach { chunk =>
                                        // Closed is NOT swallowed here: it must propagate so a disconnected client aborts the
                                        // foreach instead of the handler stream being pulled forever into a dead outbound.
                                        val bytes = if http10 then chunk else Http1StreamContext.formatChunkSpan(chunk)
                                        putWithinIdleTimeout(streamCtx.outbound, bytes, config.idleTimeout, clock)
                                    }
                                ).map { result =>
                                    // A stream that fails after the head is written has no way to withdraw the 200: the body stays
                                    // unterminated and the connection is closed, so the peer reads a body cut short (RFC 9112 section
                                    // 8) instead of a complete one ending in the last chunk.
                                    result match
                                        case Result.Panic(t) =>
                                            Log.error("UnsafeServerDispatch: streaming response error", t).andThen(
                                                Sync.Unsafe.defer(streamCtx.requestConnectionClose())
                                            )
                                        case Result.Failure(_: Closed) =>
                                            // Routine peer disconnect mid-stream: not an error, so no log noise.
                                            Sync.Unsafe.defer(if !http10 then writer.finish())
                                        case Result.Failure(_: Timeout) =>
                                            // The peer took nothing for a whole window: closed like a peer that stops reading a
                                            // buffered answer, without an error log.
                                            Sync.Unsafe.defer(streamCtx.requestConnectionClose())
                                        case Result.Failure(e) =>
                                            Log.error(s"UnsafeServerDispatch: streaming response aborted: $e").andThen(
                                                Sync.Unsafe.defer(streamCtx.requestConnectionClose())
                                            )
                                        case Result.Success(_) =>
                                            Sync.Unsafe.defer(if !http10 then writer.finish())
                                }
                            }
                        end if
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
                        isBodyFault(bodyFault, other).map { bodyFailed =>
                            if bodyFailed then answerBodyFault(streamCtx, other)
                            else
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
                        }
            case Result.Panic(t) =>
                isBodyFault(bodyFault, t).map { bodyFailed =>
                    if bodyFailed then answerBodyFault(streamCtx, t)
                    else
                        Log.error("UnsafeServerDispatch: handler panic", t).andThen(
                            Sync.Unsafe.defer(writeInternalError(streamCtx))
                        )
                }
        }
    end dispatchHandler

    /** Puts one span of a streamed response, the wait for the peer to take it bounded by the idle timeout: a peer that takes nothing for a
      * whole window has stopped reading, and the timer that bounds the wait for a buffered answer to be read covers no put made on the
      * handler's fiber. The channel is offered first, so a peer that keeps up never touches the clock.
      */
    private def putWithinIdleTimeout(
        outbound: Channel.Unsafe[Span[Byte]],
        bytes: Span[Byte],
        idleTimeout: Duration,
        clock: Clock
    )(using AllowUnsafe, Frame): Unit < (Async & Abort[Closed | Timeout]) =
        Sync.Unsafe.defer {
            outbound.offer(bytes) match
                case Result.Success(true)  => Kyo.unit
                case Result.Success(false) =>
                    if idleTimeout.isFinite then Clock.let(clock)(Async.timeout(idleTimeout)(outbound.safe.put(bytes)))
                    else outbound.safe.put(bytes)
                case Result.Failure(closed) => Abort.fail(closed)
                case Result.Panic(t)        => Abort.panic(t)
        }

    /** Ends the connection after a handler's final response. A request body the handler left on the wire is read and discarded until the
      * peer's EOF first: a socket closed with unread bytes is reset by the kernel, and a reset discards what the peer has not yet read,
      * the response among it (the lingering close of RFC 9112 section 9.6). The idle timer bounds the drain, since the body is still owed.
      */
    private def closeAfterDrain(
        streamCtx: Http1StreamContext,
        closeNow: () => Unit,
        onClosing: Maybe[Fiber.Unsafe[Unit, Any]],
        clock: Clock,
        lingering: Duration
    )(using AllowUnsafe, Frame): Unit =
        val bodyOwed = streamCtx.phase match
            case Http1StreamContext.ReadPhase.BodyPending | Http1StreamContext.ReadPhase.Draining => true
            case _                                                                                => false
        if bodyOwed && !onClosing.exists(_.done()) then
            streamCtx.startDraining()
            // Bytes already in hand are discarded without announcing a wait, as the body readers take them.
            val drain: Unit < (Async & Abort[Closed]) = Loop.foreach {
                streamCtx.inbound.safe.poll.map {
                    case Present(_) => Sync.Unsafe.defer(streamCtx.noteBodyProgress())
                    case Absent     =>
                        Sync.Unsafe.defer(streamCtx.awaitPeer())
                            .andThen(streamCtx.inbound.safe.take)
                            .map(_ => Sync.Unsafe.defer(streamCtx.noteBodyProgress()))
                }.andThen(Loop.continue[Unit])
            }
            // The bound is total from the drain's start, whatever the idle timer sees: a peer that keeps sending holds a connection
            // with no purpose left, and past the bound the close and its reset are accepted.
            val bounded: Unit < (Async & Abort[Closed | Timeout]) =
                if lingering.isFinite then Clock.let(clock)(Async.timeout(lingering)(drain)) else drain
            // The close runs whatever ended the drain, a panic in a take included: a connection already answered and left open would
            // otherwise wait for the idle timer, or for ever with it disabled.
            discard(IOTask.detached(Sync.ensure(Sync.Unsafe.defer(closeNow()))(Abort.run[Closed | Timeout](bounded))))
        else closeNow()
        end if
    end closeAfterDrain

    /** Whether `error` is the very fault the request body's decode recorded, which a handler that read the body to its end settles with. */
    private def isBodyFault(bodyFault: Maybe[AtomicRef[Maybe[Throwable]]], error: Any)(using Frame): Boolean < Sync =
        bodyFault match
            case Present(fault) =>
                error match
                    case ref: AnyRef => fault.get.map(_.exists(_ eq ref))
                    case _           => false
            case Absent => false

    /** Answers the request body's own fault: refused framing is a 400, an over-limit control plane a 413, both with `Connection: close`
      * since the body's end is unknown; a peer that closed is owed nothing.
      */
    private def answerBodyFault(streamCtx: Http1StreamContext, fault: Any)(using Frame): Unit < Async =
        fault match
            case malformed: HttpMalformedBodyException =>
                Sync.Unsafe.defer(streamCtx.requestConnectionClose()).andThen(writeDecodeError(streamCtx, malformed))
            case _: HttpPayloadTooLargeException =>
                Sync.Unsafe.defer {
                    streamCtx.requestConnectionClose()
                    writePayloadTooLarge(streamCtx)
                }
            case _ => Kyo.unit

    /** Write a router-level error response (404, 405, OPTIONS). */
    private def writeErrorResponse(streamCtx: Http1StreamContext, error: HttpRouter.FindError)(using AllowUnsafe, Frame): Unit =
        error match
            case HttpRouter.FindError.NotFound =>
                writeErrorAnswer(streamCtx, HttpStatus.NotFound, RouteUtil.encodeErrorBody(HttpStatus.NotFound))
            case HttpRouter.FindError.MethodNotAllowed(methods) =>
                val augmented =
                    val base     = methods.toSet
                    val withHead = if base.contains(HttpMethod.GET) then base + HttpMethod.HEAD else base
                    withHead + HttpMethod.OPTIONS
                end augmented
                val bodyBytes = RouteUtil.encodeErrorBody(HttpStatus.MethodNotAllowed)
                val writer    = streamCtx.respond(
                    HttpStatus.MethodNotAllowed,
                    HttpHeaders.empty
                        .add("Allow", augmented.map(_.name).mkString(", "))
                        .add("Content-Type", "application/json")
                        .add("Content-Length", bodyBytes.size)
                )
                writer.writeBody(bodyBytes)
            case HttpRouter.FindError.Options(headers) =>
                // 204 head fully frames the response: no body and no chunked last-chunk terminator.
                discard(streamCtx.respond(HttpStatus.NoContent, headers))
        end match
    end writeErrorResponse

    /** Writes the JSON error answer for `status`, with `body` as its bytes. Every error the dispatch answers on its own converges here; a
      * caller about to end the connection marks it first, so the head announces the close.
      */
    private def writeErrorAnswer(streamCtx: Http1StreamContext, status: HttpStatus, body: Span[Byte])(using AllowUnsafe, Frame): Unit =
        val headers = HttpHeaders.empty
            .add("Content-Type", "application/json")
            .add("Content-Length", body.size)
        streamCtx.respond(status, headers).writeBody(body)
    end writeErrorAnswer

    /** Write a 400 Bad Request response (e.g. missing or invalid Host header per RFC 9110 section 7.2). */
    private def writeBadRequest(streamCtx: Http1StreamContext)(using AllowUnsafe, Frame): Unit =
        writeErrorAnswer(streamCtx, HttpStatus.BadRequest, RouteUtil.encodeErrorBody(HttpStatus.BadRequest))

    /** Answers a request the parser refused with `status`, marking the connection to close; the caller tears it down right after. */
    private def writeRefusal(streamCtx: Http1StreamContext, status: HttpStatus)(using AllowUnsafe, Frame): Unit =
        streamCtx.requestConnectionClose()
        writeErrorAnswer(streamCtx, status, RouteUtil.encodeErrorBody(status))

    /** Write a 500 Internal Server Error. */
    private def writeInternalError(streamCtx: Http1StreamContext)(using AllowUnsafe, Frame): Unit =
        writeErrorAnswer(streamCtx, HttpStatus.InternalServerError, RouteUtil.encodeErrorBody(HttpStatus.InternalServerError))

    /** Answers a request whose body or fields did not decode: 415 for a content type the route does not take, 400 for anything else. */
    private def writeDecodeError(streamCtx: Http1StreamContext, error: HttpException)(using Frame): Unit < Async =
        val status = error match
            case _: HttpUnsupportedMediaTypeException => HttpStatus.UnsupportedMediaType
            case _                                    => HttpStatus.BadRequest
        val bodyBytes = RouteUtil.encodeErrorBodyWithMessage(status, error.getMessage)
        Sync.Unsafe.defer(writeErrorAnswer(streamCtx, status, bodyBytes))
    end writeDecodeError

    /** Write a 413 Payload Too Large response. */
    private def writePayloadTooLarge(streamCtx: Http1StreamContext)(using AllowUnsafe, Frame): Unit =
        writeErrorAnswer(streamCtx, HttpStatus.PayloadTooLarge, RouteUtil.encodeErrorBody(HttpStatus.PayloadTooLarge))

    /** Write a 417 Expectation Failed response. */
    private def writeExpectationFailed(streamCtx: Http1StreamContext)(using AllowUnsafe, Frame): Unit =
        writeErrorAnswer(streamCtx, HttpStatus.ExpectationFailed, RouteUtil.encodeErrorBody(HttpStatus.ExpectationFailed))

    /** Write 100 Continue interim response. */
    private def writeContinue(
        streamCtx: Http1StreamContext
    )(using AllowUnsafe, Frame): Unit =
        streamCtx.writeInterim(Span.fromUnsafe("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII)))

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
            val builder                     = DictBuilder.init[String, String]
            @tailrec def loop(i: Int): Unit =
                if i < lookup.captureCount && i < captureNames.size then
                    val segIdx = lookup.captureSegmentIndices(i)
                    val value  =
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
