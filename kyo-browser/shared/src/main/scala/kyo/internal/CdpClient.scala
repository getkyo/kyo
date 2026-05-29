package kyo.internal

import CdpTypes.*
import kyo.*

/** Internal Chrome DevTools Protocol WebSocket client.
  *
  * Constructed internally by [[kyo.Browser.run]] (via [[CdpClient.init]]) and threaded through each [[kyo.internal.BrowserTab]]. This class
  * is the implementation underneath and is not part of the public API.
  *
  * Responsibilities:
  *   - **Exchange multiplexing**: pairs each outbound CDP command with its response over a single WebSocket via [[Exchange]], so concurrent
  *     commands and the response stream never interleave incorrectly.
  *   - **Dialog routing**: registers per-page dialog handlers and drains queued `(accept, promptText, sessionId)` decisions through a
  *     dedicated drainer fiber.
  *   - **Frame-event dispatch**: fans CDP frame events out to per-frame [[CdpEvent.Generic]] dispatchers.
  *   - **In-flight cap**: bounds the number of CDP commands awaiting a response via `cdpMeter` (see [[CdpClient.maxInFlight]]) to keep
  *     reader-fiber pressure within what a single-threaded runtime can sustain.
  *
  * @see
  *   [[CdpBackend]] for the typed command/response surface and [[Exchange]] for the request/response multiplexing primitive.
  */
final class CdpClient private[kyo] (
    private[kyo] val exchange: Exchange[Int => String, String, CdpEvent, Closed],
    private[kyo] val outbound: Channel[String],
    private[kyo] val inbound: Channel[String],
    private[kyo] val relay: Fiber[Unit, Sync],
    private[kyo] val dialogHandlers: AtomicRef[Dict[String, (Boolean, String)]],
    private[kyo] val dialogDrainer: Fiber[Unit, Any],
    private[kyo] val dialogQueue: Channel[(Boolean, String, Maybe[SessionId])],
    private[kyo] val inFlight: AtomicInt,
    private[kyo] val drainSignal: AtomicRef[Fiber.Promise[Unit, Any]],
    private[kyo] val frameEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
    private[kyo] val downloadEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
    private[kyo] val dialogRecorders: AtomicRef[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]],
    private[kyo] val lastEvaluateParams: AtomicRef[Maybe[String]],
    private[kyo] val cdpMeter: Meter,
    private[kyo] val requestTimeout: Duration,
    private[kyo] val sessionId: Maybe[SessionId] = Absent
) extends CdpSender:
    /** Send a CDP command with typed params and return the raw result JSON string. */
    private[kyo] def send[P: Schema](method: String, params: P)(using Frame): String < (Async & Abort[BrowserReadException]) =
        submit(method, params)

    /** Send a CDP command with no params and return the raw result JSON string. */
    private[kyo] def send(method: String)(using Frame): String < (Async & Abort[BrowserReadException]) =
        submit(method, CdpNoParams())

    /** Send a CDP command with typed params, discard result. */
    private[kyo] def sendUnit[P: Schema](method: String, params: P)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        send(method, params).unit

    /** Send a CDP command with no params, discard result. */
    private[kyo] def sendUnit(method: String)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        send(method).unit

    private def submit[P: Schema](method: String, params: P)(using
        Frame
    )
        : String < (Async & Abort[BrowserReadException]) =
        val sid    = sessionId.map(_.value)
        val mkWire = (id: Int) => Json.encode(CdpEnvelope(id, method, params, sid))
        // Record the most recent `Runtime.evaluate` params JSON (in particular whether `contextId`
        // is present and what value it carries) for inspection by callers and tests.
        val record =
            if method == CdpBackend.RuntimeEvaluateMethod then lastEvaluateParams.set(Present(Json.encode(params)))
            else Kyo.unit
        record.andThen {
            // Per-submit drain bookkeeping. On the 0→1 transition, install a fresh `Fiber.Promise` into `drainSignal`;
            // matched in the `Sync.ensure` cleanup which completes the captured promise on the 1→0 transition. The
            // captured-before-decrement snapshot guarantees the decrementer completes the promise its increment installed,
            // so a concurrent re-increment cannot leak waiters on a never-completed promise.
            inFlight.getAndIncrement.map { prev =>
                val refresh: Unit < Async =
                    if prev == 0 then Fiber.Promise.init[Unit, Any].map(drainSignal.set)
                    else Kyo.unit
                refresh.andThen {
                    drainSignal.get.map { snapshot =>
                        Sync.ensure(
                            inFlight.decrementAndGet.map { newCount =>
                                if newCount == 0 then snapshot.completeUnitDiscard
                                else Kyo.unit
                            }
                        ) {
                            // `cdpMeter` caps the number of CDP commands awaiting a response at any instant (see CdpClient.maxInFlight).
                            // The cap bounds reader pressure so Chrome's send buffer never fills enough to tear down the WebSocket;
                            // the JS/Native single-threaded runtimes can't drain unbounded in-flight responses. A meter `Closed` is
                            // recovered identically to the exchange `Closed` below.
                            //
                            // `Async.timeout` is a safety net for Chrome going silent on a single command: it turns a hung CDP call
                            // into a bounded, typed BrowserConnectionLostException. `requestTimeout` is set far above any legitimate
                            // single-call round-trip.
                            Abort.recover[Closed] { (closed: Closed) =>
                                Abort.fail(BrowserConnectionLostException(s"Connection lost: ${closed.getMessage}", Present(closed)))
                            } {
                                Abort.recover[Timeout] { (_: Timeout) =>
                                    Abort.fail(BrowserConnectionLostException(
                                        s"CDP command '$method' received no response within ${requestTimeout}"
                                    ))
                                } {
                                    cdpMeter.run {
                                        Async.timeout(requestTimeout)(exchange(mkWire))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    end submit

    /** Create a session-scoped client that includes sessionId in every request.
      *
      * Shares the parent's `cdpMeter` so the in-flight cap is per-connection, not per-session: every session on a connection multiplexes
      * over the same WebSocket and the same reader fiber, so the flood-prevention bound must span them all.
      */
    private[kyo] def withSession(sid: SessionId): CdpClient =
        new CdpClient(
            exchange,
            outbound,
            inbound,
            relay,
            dialogHandlers,
            dialogDrainer,
            dialogQueue,
            inFlight,
            drainSignal,
            frameEventDispatchers,
            downloadEventDispatchers,
            dialogRecorders,
            lastEvaluateParams,
            cdpMeter,
            requestTimeout,
            Present(sid)
        )

    /** Closes the client, waiting up to `gracePeriod` for in-flight requests to complete before forcing a teardown.
      *
      * If the grace period elapses before the orderly shutdown finishes, the client is closed forcefully (equivalent to [[closeNow]]).
      */
    private[kyo] def close(gracePeriod: Duration)(using Frame): Unit < Async =
        if gracePeriod == Duration.Zero then closeNow
        else
            Abort.run[Timeout](Async.timeout(gracePeriod)(closeOrderly))
                .map {
                    case Result.Success(_)          => Kyo.unit
                    case Result.Failure(_: Timeout) => closeNow
                    case Result.Panic(ex)           => closeNow.andThen(Abort.panic(ex))
                }

    /** Closes the client immediately, interrupting any in-flight requests.
      *
      * Waits for the relay fiber to fully stop before returning. Even though this is a forced close, we must still wait: Chrome keeps the
      * WebSocket connection alive until the client disconnects. If we return before the relay stops, a new connection opened while the old
      * one is still alive gives Chrome two simultaneous clients, which causes dialog events to be silently dropped on the new connection.
      */
    private[kyo] def closeNow(using Frame): Unit < Async =
        exchange.close
            .andThen(cdpMeter.close.unit)
            .andThen(relay.interrupt.andThen(relay.getResult.unit))
            .andThen(dialogQueue.close.unit)
            .andThen(dialogDrainer.interrupt.unit)
            .andThen(outbound.close.unit)
            .andThen(inbound.close.unit)

    /** Orderly close path: await in-flight sends to drain (capped externally by the caller's `Async.timeout`), then close the exchange and
      * tear down the relay/dialog fibers before closing channels. Used by [[close(gracePeriod)]] under the supplied timeout.
      */
    private def closeOrderly(using Frame): Unit < Async =
        awaitDrain
            .andThen(exchange.close)
            .andThen(cdpMeter.close.unit)
            .andThen(relay.interrupt.andThen(relay.getResult.unit))
            .andThen(dialogQueue.close.unit)
            .andThen(dialogDrainer.interrupt.andThen(dialogDrainer.getResult.unit))
            .andThen(outbound.close.unit)
            .andThen(inbound.close.unit)

    /** Waits for all in-flight CDP commands to drain. Returns immediately when none are in flight; otherwise blocks on the current drain
      * signal (a `Fiber.Promise` re-issued per 0→1 in-flight transition in `submit` and completed on the 1→0 transition). Cross-platform
      * (JVM/JS/Native) via `Fiber.Promise`; no tight-spin. Itself unbounded; the caller is responsible for capping the wait via
      * `Async.timeout`.
      */
    private[kyo] def awaitDrain(using Frame): Unit < Async =
        inFlight.get.map { n =>
            if n <= 0 then Kyo.unit
            else drainSignal.get.map(_.get.unit)
        }

end CdpClient

object CdpClient:

    /** Maximum number of CDP commands allowed to be awaiting a response on a single connection at any instant.
      *
      * The CDP WebSocket is drained by exactly one reader fiber. When more responses arrive than that fiber can decode-and-route in time,
      * the inbound channel chain (`HttpWebSocket.inbound` → relay → `CdpClient.inbound` → Exchange reader) saturates, the WebSocket read
      * fiber stops pulling from the socket, Chrome's send buffer fills, and Chrome closes the connection. On JVM the multi-threaded
      * scheduler keeps the reader ahead of the inflow; on JS/Native the single-threaded runtime cannot. Capping in-flight commands bounds
      * the reader's peak backlog to a level it can sustain across all platforms.
      *
      * 8 is comfortably above the depth of any production call path (CDP use is overwhelmingly sequential) and any concurrent fan-out,
      * so the cap throttles only pathological bursts; it does not slow the happy path.
      */
    private[kyo] val maxInFlight: Int = 8

    /** Connects to a CDP endpoint and registers cleanup on scope exit. */
    def init(wsUrl: String, launchCfg: Browser.LaunchConfig)(using
        Frame
    ): CdpClient < (Async & Scope & Abort[BrowserReadException]) =
        Scope.acquireRelease(initUnscoped(wsUrl, launchCfg))(_.close(launchCfg.closeGrace))

    /** Connects to a CDP endpoint without scope-bound cleanup. The caller is responsible for calling [[CdpClient.close]] when finished.
      *
      * Use this to share a client across scopes, for example a test-suite-level connection that outlives individual tests.
      */
    def initUnscoped(wsUrl: String, launchCfg: Browser.LaunchConfig)(using
        Frame
    ): CdpClient < (Async & Abort[BrowserReadException]) =
        for
            outbound       <- Channel.initUnscoped[String](64)
            inbound        <- Channel.initUnscoped[String](64)
            dialogHandlers <- AtomicRef.init[Dict[String, (Boolean, String)]](Dict.empty)
            // dialogQueue receives (accept, promptText, sessionId) tuples from the reader fiber
            // (via Channel.offer, non-blocking) and is drained by a dedicated dialog-handler fiber.
            // Using a queue decouples the reader loop (which must be < Sync) from the async send of
            // Page.handleJavaScriptDialog. Capacity 16 is ample for any realistic test.
            dialogQueue <- Channel.initUnscoped[(Boolean, String, Maybe[SessionId])](16)
            // connectReady gates `init`'s return on the WebSocket actually completing its handshake.
            // The relay fiber completes this promise either with success (when HttpClient.webSocket's body
            // begins, i.e. the WS is open) or with the connect failure (when HttpClient.webSocket itself
            // aborts before the body runs, e.g. unreachable host, refused connection).
            //
            // Without this gate, init would return a "live" CdpClient even when the underlying connection
            // is dead, and the first request would hang waiting for outbound to flow. Promise.complete is
            // idempotent so the success path here cannot conflict with a later relay-termination.
            connectReady <- Fiber.Promise.init[Unit, Abort[BrowserReadException]]
            relay <- Fiber.initUnscoped {
                Abort.run[Throwable] {
                    // CDP responses can carry arbitrarily large payloads (full-page screenshots, PDFs,
                    // DOM dumps, large `Runtime.evaluate` JSON results). The kyo-http default of 16 MiB
                    // in [[HttpWebSocket.Config]] suffices for realistic CDP use; see kyo-http
                    // HttpWebSocket.Config for the cap's rationale.
                    HttpClient.webSocket(wsUrl, HttpHeaders.empty) { ws =>
                        // Mark the connection live BEFORE the sender/receiver run. If we waited until after
                        // Async.race completes, an immediate sender/receiver termination would race with
                        // the connect signal. Doing it first guarantees init sees success as soon as the
                        // ws block is entered.
                        connectReady.completeUnit.andThen {
                            val sender = outbound.stream().foreach { msg =>
                                ws.put(HttpWebSocket.Payload.Text(msg))
                            }
                            val receiver = ws.stream.foreach {
                                case HttpWebSocket.Payload.Text(s) =>
                                    inbound.put(s)
                                case other =>
                                    // CDP protocol is text-only; binary/close frames here indicate either
                                    // a non-CDP peer or a future protocol shift. Surface so production observes.
                                    Log.warn(s"CdpClient: ignoring non-Text WS frame: $other")
                            }
                            Async.race(sender, receiver).unit
                        }
                    }
                }.map { result =>
                    // Forward connect failures to connectReady so init aborts fast. Successful body
                    // completions (or post-connect errors) are no-ops here because connectReady was
                    // already completed inside the ws block above.
                    val fail: BrowserReadException => Unit < Sync = ex =>
                        connectReady.complete(Result.fail(ex)).unit
                    result match
                        case Result.Success(_)                        => Kyo.unit
                        case Result.Failure(ex: BrowserReadException) => fail(ex)
                        case Result.Failure(ex) => fail(BrowserConnectionLostException(s"Connection lost: ${ex.getMessage}", Present(ex)))
                        case Result.Panic(ex)   => fail(BrowserConnectionLostException(s"Connection lost: ${ex.getMessage}", Present(ex)))
                    end match
                }
            }
            // Wait for the WebSocket connect attempt to resolve. On failure this raises
            // Abort[BrowserReadException] and short-circuits init before any caller can wedge
            // on a dead outbound channel.
            _ <- connectReady.get
            // Per-connection registry for frame-context (and other per-session) handlers. Each tab
            // registers a callback under its sessionId; `decodeCdpMessage` dispatches matching events
            // inline (parallel to the dialog-handler pattern) and never forwards them to the event
            // channel. This avoids the single-take race where multiple consumers (one per tab) would
            // compete for the same `exchange.events` stream.
            frameEventDispatchers <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
            // Per-connection registry for `Page.downloadWillBegin` / `Page.downloadProgress` handlers
            // installed via `Browser.onDownload`. Keyed by CDP session ID so concurrent tabs don't
            // cross-talk. Dispatch is ADDITIVE: events continue to flow through the events channel
            // via the whitelist branch so internal consumers (`session.exchange.events`) keep working.
            downloadEventDispatchers <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
            // Per-session dialog recorders installed by `Browser.withDialogs.recorded`. Keyed by CDP session ID so
            // concurrent tabs do not cross-talk. Each entry holds an `AtomicRef[Chunk[DialogEvent]]` updated in
            // arrival order by the CDP reader fiber after the auto-handler decision is made.
            dialogRecorders <- AtomicRef.init[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]](Dict.empty)
            exchange <- Exchange.initUnscoped[Int => String, String, String, CdpEvent, Closed](
                encode = (id, mkWire) => mkWire(id),
                send = wire => outbound.put(wire),
                receive = inbound.stream(),
                decode = wire =>
                    decodeCdpMessage(
                        wire,
                        dialogHandlers,
                        dialogQueue,
                        frameEventDispatchers,
                        downloadEventDispatchers,
                        dialogRecorders
                    )
            )
            // Watcher: when the relay terminates, close the exchange so blocked sends fail with ConnectionLost.
            _ <- Fiber.initUnscoped {
                relay.getResult.andThen(exchange.close)
            }
            // Dialog-handler fiber: drains dialogQueue one request at a time and writes
            // Page.handleJavaScriptDialog directly to `outbound` with a negative request id.
            //
            // Invariant: the drainer must NEVER await Chrome's response to handleJavaScriptDialog.
            // If a browser context is disposed while a dialog is pending, Chrome will not emit a
            // response, and an awaiting drainer would block indefinitely, wedging every subsequent
            // dialog. Using `outbound.put` with a negative id (which the exchange reader matches
            // against no pending promise and silently discards) makes dialog dispatch fire-and-forget
            // while still flowing through the same WebSocket as ordinary requests.
            dialogIdCounter <- AtomicInt.init(Int.MinValue)
            dialogDrainer <- Fiber.initUnscoped {
                Abort.run[Closed] {
                    dialogQueue.stream().foreach { case (accept, promptText, sessionId) =>
                        dialogIdCounter.getAndIncrement.map { id =>
                            val wire = Json.encode(CdpEnvelope(
                                id,
                                "Page.handleJavaScriptDialog",
                                HandleJavaScriptDialogParams(accept, promptText),
                                sessionId.map(_.value)
                            ))
                            Abort.run[Closed](outbound.put(wire)).unit
                        }
                    }
                }.unit
            }
            inFlightCounter    <- AtomicInt.init
            lastEvaluateParams <- AtomicRef.init[Maybe[String]](Absent)
            // Per-connection semaphore that caps concurrent in-flight CDP commands (see `submit`).
            // Unscoped: its lifetime is the CdpClient's, closed explicitly in `closeNow` / `closeOrderly`.
            cdpMeter <- Meter.initSemaphoreUnscoped(maxInFlight)
            // Initial drain signal: an already-completed promise so any awaitDrain issued before any submit
            // returns immediately. `submit` re-issues a fresh promise on every 0→1 in-flight transition.
            initialDrainPromise <- Fiber.Promise.init[Unit, Any]
            _                   <- initialDrainPromise.completeUnit
            drainSignal         <- AtomicRef.init[Fiber.Promise[Unit, Any]](initialDrainPromise)
        yield new CdpClient(
            exchange,
            outbound,
            inbound,
            relay,
            dialogHandlers,
            dialogDrainer,
            dialogQueue,
            inFlightCounter,
            drainSignal,
            frameEventDispatchers,
            downloadEventDispatchers,
            dialogRecorders,
            lastEvaluateParams,
            cdpMeter,
            launchCfg.requestTimeout
        )

    /** Event methods whose frames are forwarded to `exchange.events` as [[CdpEvent.Generic]]. Everything else is dropped.
      *
      * The bounded event channel blocks the reader fiber when full, which stalls responses. Because most tests (and most of the `Browser`
      * API) never subscribe to events, we must be parsimonious about what we forward; otherwise Page/Network/Runtime lifecycle chatter
      * fills the buffer during ordinary navigation and wedges the whole client. Internal wrappers opt their domain in on demand.
      */
    private[kyo] val eventWhitelist: Set[String] = Set(
        "Page.downloadWillBegin",
        "Page.downloadProgress"
    )

    private[kyo] def isWhitelistedEvent(method: String): Boolean = eventWhitelist.contains(method)

    /** Decodes a CDP wire message and routes it.
      *
      * `Page.javascriptDialogOpening` is intercepted UNCONDITIONALLY before the whitelist check:
      *   - Reads `dialogHandlers` atomically to determine `(accept, promptText)` for the event's session.
      *   - Enqueues `(accept, promptText, sessionId)` to `dialogQueue` via non-blocking `offer`.
      *   - The dialog-handler fiber drains `dialogQueue` and sends `Page.handleJavaScriptDialog`.
      *
      * The event is NEVER forwarded to the events channel. Auto-dismisses with `accept = false, promptText = ""` when no `withDialogs`
      * handler is registered for the session (i.e. not present in `dialogHandlers` map).
      *
      * Isolation semantics: `dialogHandlers` is a per-session map (keyed by CDP session ID string). Each concurrent tab gets its own entry,
      * so concurrent `withDialogs` calls in different tabs do not interfere.
      *
      * Nesting semantics: callers use `getAndSet` on `dialogHandlers` to save/restore the previous value for their session (LIFO scope
      * exit), so nested `withDialogs` calls correctly restore the outer handler for that session.
      */
    private[kyo] def decodeCdpMessage(
        wire: String,
        dialogHandlers: AtomicRef[Dict[String, (Boolean, String)]],
        dialogQueue: Channel[(Boolean, String, Maybe[SessionId])],
        frameEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
        downloadEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
        dialogRecorders: AtomicRef[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]]
    )(using Frame): Exchange.Message[Int, String, CdpEvent] < Sync =
        // The dispatcher peeks `{id, method, sessionId, error}` via [[CdpWireMessage]] for routing; the method-
        // specific `result` / `params` shapes are decoded at the caller via [[CdpReply]] / [[CdpEventParams]]
        // against the whole wire string. No manual JSON-substring extraction.
        Json.decode[CdpWireMessage](wire) match
            case Result.Success(env) =>
                val sid: Maybe[SessionId] = env.sessionId.map(SessionId(_))
                env.id match
                    case Present(id) =>
                        Exchange.Message.Response(id, wire)
                    case Absent =>
                        // No id → CDP event (method + params) or malformed frame. We only forward events whose
                        // domain is on the opt-in whitelist [[CdpClient.eventWhitelist]]; everything else is
                        // dropped to avoid filling the bounded event channel when no consumer is subscribed.
                        // Downstream code that needs a new event domain extends the whitelist.
                        env.method match
                            case Present(method) =>
                                // Hand the whole wire to event consumers; they decode the method-specific
                                // [[CdpEventParams]] envelope from it on demand.
                                val paramsStr = wire
                                if method == "Page.javascriptDialogOpening" then
                                    // Page.javascriptDialogOpening is intercepted UNCONDITIONALLY: handled inline,
                                    // never forwarded to the events channel. Auto-dismisses with permissive defaults
                                    // when no withDialogs handler is registered for this session. Non-blocking offer:
                                    // capacity 16 is ample. Abort[Closed] swallowed: if the queue is closed the
                                    // exchange is shutting down anyway, so discard silently.
                                    //
                                    // After the handler decision is enqueued, the per-session dialog recorder (when
                                    // present, installed by `Browser.withDialogs.recorded`) snapshots the event into
                                    // its `AtomicRef[Chunk[DialogEvent]]`. The recorder is a passive observer: it
                                    // does NOT influence the auto-handler decision.
                                    dialogHandlers.use { handlers =>
                                        val sidKey                               = sid.fold("")(_.value)
                                        val handlerOpt: Maybe[(Boolean, String)] = handlers.get(sidKey)
                                        val (accept, promptText)                 = handlerOpt.getOrElse((false, ""))
                                        Abort.run[Closed](dialogQueue.offer((accept, promptText, sid))).andThen {
                                            recordDialogEvent(dialogRecorders, sidKey, paramsStr, handlerOpt, accept, promptText)
                                        }
                                    }
                                        .andThen(Exchange.Message.Skip)
                                else if method == "Runtime.executionContextCreated" || method == "Runtime.executionContextDestroyed" then
                                    // Frame-context events are dispatched UNCONDITIONALLY to the per-session handler
                                    // registered in `frameEventDispatchers` and NEVER forwarded to the event channel.
                                    // This avoids the single-take race where multiple consumers (one per tab) would
                                    // compete for the same channel.
                                    val ev = CdpEvent.Generic(method, paramsStr, sid)
                                    frameEventDispatchers.use { dispatchers =>
                                        sid match
                                            case Present(s) =>
                                                dispatchers.get(s.value) match
                                                    case Present(handler) => handler(ev)
                                                    case Absent           => Kyo.unit
                                            case Absent => Kyo.unit
                                    }.andThen(Exchange.Message.Skip)
                                else if isWhitelistedEvent(method) then
                                    // Whitelisted download events route via the per-session dispatcher when one is
                                    // registered (Browser.onDownload subscriber). When NO dispatcher is registered
                                    // (or the event is non-download), the event is pushed to `exchange.events` so
                                    // internal consumers (via `session.exchange.events`) can observe it.
                                    //
                                    // Mutually exclusive routing: pushing to `exchange.events` regardless would
                                    // stockpile events in a bounded channel (default capacity 16) when no internal
                                    // consumer drains. Once full, the CDP reader fiber parks (backpressure), and
                                    // subsequent download events for ANY tab are blocked from being decoded,
                                    // including the terminal `state="completed"` Progress that downstream code
                                    // (Browser.onDownload's collectEvents) awaits. Concurrent tabs each generate
                                    // their own streams of WillBegin and Progress events; without a dispatcher to
                                    // consume them, events accumulate across tabs until the channel saturates and
                                    // the reader stops draining.
                                    val ev = CdpEvent.Generic(method, paramsStr, sid)
                                    if method == "Page.downloadWillBegin" || method == "Page.downloadProgress" then
                                        downloadEventDispatchers.use { dispatchers =>
                                            sid match
                                                case Present(s) =>
                                                    dispatchers.get(s.value) match
                                                        case Present(handler) =>
                                                            // Dispatcher consumed the event; do NOT also push.
                                                            handler(ev).andThen(Exchange.Message.Skip)
                                                        case Absent =>
                                                            // No dispatcher: push so `exchange.events` consumers see it.
                                                            Exchange.Message.Push(ev)
                                                case Absent =>
                                                    Exchange.Message.Push(ev)
                                        }
                                    else
                                        Exchange.Message.Push(ev)
                                    end if
                                else
                                    Exchange.Message.Skip
                                end if
                            case Absent =>
                                Log.warn(s"CdpClient.decodeCdpMessage: dropped event-shaped frame with no method: wire=$wire")
                                    .andThen(Exchange.Message.Skip)
                end match
            case Result.Failure(err) => fallbackDecode(wire, s"JSON parse failed: ${err.getMessage}")
            case Result.Panic(ex)    => fallbackDecode(wire, s"JSON parse panicked: ${ex.getMessage}")
        end match
    end decodeCdpMessage

    /** Records a single [[Browser.DialogEvent]] in the per-session recorder, when one is installed for the given session id. No-op when no
      * recorder is registered (the recorder is opt-in via [[Browser.withDialogs.recorded]]).
      *
      * `wire` is the entire `Page.javascriptDialogOpening` frame; this method decodes only the `params` slot. A malformed `params` payload
      * yields `Absent` (logged-and-skipped) rather than failing the whole CDP message pump.
      */
    private def recordDialogEvent(
        dialogRecorders: AtomicRef[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]],
        sidKey: String,
        wire: String,
        handlerOpt: Maybe[(Boolean, String)],
        accept: Boolean,
        promptText: String
    )(using Frame): Unit < Sync =
        dialogRecorders.use { recorders =>
            recorders.get(sidKey) match
                case Absent => Kyo.unit
                case Present(recorder) =>
                    Json.decode[CdpEventParams[JavascriptDialogOpeningParams]](wire) match
                        case Result.Success(env) =>
                            val p = env.params
                            val kind = p.`type` match
                                case "alert"        => Browser.DialogType.Alert
                                case "confirm"      => Browser.DialogType.Confirm
                                case "prompt"       => Browser.DialogType.Prompt
                                case "beforeunload" => Browser.DialogType.BeforeUnload
                                case _              => Browser.DialogType.Alert
                            val response: Maybe[String] = handlerOpt match
                                case Present(_) if accept && p.`type` == "prompt" => Present(promptText)
                                case _                                            => Absent
                            recorder.updateAndGet(_.append(Browser.DialogEvent(kind, p.message, response))).unit
                        case other =>
                            Log.warn(
                                s"CdpClient.recordDialogEvent: unexpected wire shape decoding JavascriptDialogOpeningParams: $other; wire=$wire"
                            )
        }
    end recordDialogEvent

    /** Tolerant fallback when the typed [[CdpWireMessage]] decoder rejects the frame. CDP servers occasionally emit malformed `error`
      * payloads (e.g. a string instead of a `{code, message}` object); the contract is "forward what we can identify by id rather than
      * silently drop". When a numeric `id` is recoverable via a permissive [[FallbackIdEnvelope]] re-decode, the whole wire is routed to
      * the awaiting caller so their [[CdpBackend.decodeOrFail]] surfaces a proper [[BrowserProtocolErrorException.decodeFailure]]. Frames
      * with no recoverable id (JSON arrays, top-level scalars) drop to [[Exchange.Message.Skip]] with a warning.
      */
    private def fallbackDecode(wire: String, reason: String)(using Frame): Exchange.Message[Int, String, CdpEvent] < Sync =
        Json.decode[FallbackIdEnvelope](wire) match
            case Result.Success(env) =>
                env.id match
                    case Present(id) => Exchange.Message.Response(id, wire)
                    case Absent =>
                        Log.warn(s"CdpClient.decodeCdpMessage: $reason; wire=$wire").andThen(Exchange.Message.Skip)
            case _ =>
                Log.warn(s"CdpClient.decodeCdpMessage: $reason; wire=$wire").andThen(Exchange.Message.Skip)
    end fallbackDecode

end CdpClient

/** Outgoing CDP request envelope. Schema-encoded directly to the wire string at send time: no AST hop, no pre-serialized params field.
  * `params` carries the typed payload; the caller's `Schema[P]` drives encoding.
  */
final private[kyo] case class CdpEnvelope[P](
    id: Int,
    method: String,
    params: P,
    sessionId: Maybe[String]
) derives Schema

/** Empty params object: encodes as `{}` on the wire. Used by CDP methods that take no params. */
final private[kyo] case class CdpNoParams() derives Schema

/** Header of an incoming CDP wire frame. The dispatcher decodes this to route by id and detect events; the polymorphic `result` / `params`
  * payloads are dropped here (Schema is permissive about unknown fields) and re-decoded at the caller via [[CdpReply]] /
  * [[CdpEventParams]].
  */
final private[kyo] case class CdpWireMessage(
    id: Maybe[Int] = Absent,
    method: Maybe[String] = Absent,
    sessionId: Maybe[String] = Absent,
    error: Maybe[CdpError] = Absent
) derives Schema

/** Typed envelope used by [[CdpBackend.decodeOrFail]] to decode a CDP response. Decoding either yields `result` (success) or `error` (CDP
  * rejection); a payload with neither is wire-shape drift and surfaces via `decodeOrFail`'s fall-through.
  */
final private[kyo] case class CdpReply[A](
    result: Maybe[A] = Absent,
    error: Maybe[CdpError] = Absent
) derives Schema

/** Typed envelope used by event consumers to decode a CDP event's params from the wire. */
final private[kyo] case class CdpEventParams[P](params: P) derives Schema

/** Wire shape for the `Page.javascriptDialogOpening` event's `params` field. Only the fields the dialog recorder and auto-handler need are
  * declared; CDP-emitted fields outside this set are tolerated by the permissive decoder.
  */
final private[kyo] case class JavascriptDialogOpeningParams(
    url: String = "",
    message: String = "",
    `type`: String = "",
    defaultPrompt: Maybe[String] = Absent
) derives Schema

/** Minimal envelope used by the dispatcher's fallback path to recover an `id` from a frame whose `error` field failed the strict
  * [[CdpError]] schema. Only `id` is interpreted; other fields are ignored.
  */
final private[kyo] case class FallbackIdEnvelope(id: Maybe[Int] = Absent) derives Schema

sealed private[kyo] trait CdpEvent
private[kyo] object CdpEvent:
    final case class Generic(method: String, paramsJson: String, sessionId: Maybe[SessionId] = Absent) extends CdpEvent
end CdpEvent

/** CDP error from error responses. */
final private[kyo] case class CdpError(code: Int, message: String) derives Schema, CanEqual

/** Minimal interface for sending CDP commands.
  *
  * Implemented by [[CdpClient]] for production use and by test fakes in unit tests. [[kyo.internal.CdpBackend]] depends only on this trait,
  * not on the concrete class.
  */
private[kyo] trait CdpSender:
    private[kyo] def send[P: Schema](method: String, params: P)(using Frame): String < (Async & Abort[BrowserReadException])
    private[kyo] def send(method: String)(using Frame): String < (Async & Abort[BrowserReadException])
end CdpSender
