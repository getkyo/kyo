package kyo.internal.engine

// ConcurrentHashMap shared concurrent map; cross-platform via JS/Native JDK shim
import java.util.concurrent.ConcurrentHashMap
import kyo.*

private[kyo] case class OutboundReq(
    method: String,
    encodedParams: Maybe[Structure.Value],
    // Unsafe: completed inside Exchange encode callback (Sync context, no Frame available)
    idSignal: Promise.Unsafe[JsonRpcId, Any],
    abortSignal: Fiber.Promise[JsonRpcError, Any],
    extras: JsonRpcExtrasEncoder
)

private[kyo] case class CallerInfo(
    method: String,
    extras: Maybe[Structure.Value],
    abortSignal: Fiber.Promise[JsonRpcError, Any],
    // AtomicRef.Unsafe aliases java.util.concurrent.atomic.AtomicReference; cross-platform via JS/Native JDK shim
    pendingCancelError: AtomicRef.Unsafe[Maybe[JsonRpcError]]
)

sealed private[kyo] trait InboundEntry
private[kyo] object InboundEntry:
    case class Running(
        method: String,
        handler: Fiber[Structure.Value, Abort[JsonRpcError | JsonRpcResponse.Halt]],
        cancelled: Fiber.Promise[Unit, Sync]
    ) extends InboundEntry

    case class Replying(
        method: String,
        suppress: AtomicBoolean
    ) extends InboundEntry

    case class Cancelled(method: String) extends InboundEntry
end InboundEntry

sealed private[kyo] trait WriterMsg
private[kyo] object WriterMsg:
    case class SendEnvelope(env: JsonRpcEnvelope)                       extends WriterMsg
    case class SuppressIfCancelled(id: JsonRpcId, env: JsonRpcEnvelope) extends WriterMsg
end WriterMsg

final class JsonRpcEndpointImpl private[kyo] (
    private[kyo] val callerRegistry: ConcurrentHashMap[JsonRpcId, CallerInfo],
    private[kyo] val pendingInbound: ConcurrentHashMap[JsonRpcId, InboundEntry],
    private val writerChannel: Channel[WriterMsg],
    private val exchange: Exchange[OutboundReq, Structure.Value, Nothing, JsonRpcError],
    private val transport: JsonRpcTransport,
    private val writerFiber: Fiber[Unit, Sync],
    private[kyo] val inFlight: AtomicInt,
    private val drainSignal: AtomicRef[Fiber.Promise[Unit, Any]],
    private val codec: Schema[JsonRpcEnvelope],
    private[kyo] val methodMap: Map[String, JsonRpcRoute[?, ?, ?]],
    private val unknownPolicy: JsonRpcUnknownMethodPolicy,
    private[kyo] val config: JsonRpcHandler.Config,
    private[kyo] val initFrame: Frame,
    private val progressPolicy: Maybe[JsonRpcProgressPolicy],
    private[kyo] val progressStreams: ConcurrentHashMap[Structure.Value, Channel[Structure.Value]],
    private[kyo] val outboundIdToToken: ConcurrentHashMap[JsonRpcId, Structure.Value],
    private val meter: Maybe[Meter],
    // AtomicLong.Unsafe aliases java.util.concurrent.atomic.AtomicLong; ConcurrentHashMap cross-platform via JS/Native JDK shim
    private[kyo] val tokenToDeadline: ConcurrentHashMap[Structure.Value, AtomicLong.Unsafe]
) extends JsonRpcHandler.Unsafe:

    // Internal delegator: referenced by initEngine's Reject-close branches. Forwards to LifecycleEngine.
    private[kyo] def closeEffect(gracePeriod: Duration)(using Frame): Unit < Async =
        LifecycleEngine.closeEffect(
            gracePeriod,
            inFlight,
            drainSignal,
            writerChannel,
            writerFiber,
            transport,
            exchange,
            callerRegistry,
            progressStreams,
            pendingInbound,
            meter,
            initFrame
        )

    // --- Public Unsafe interface: every method returns Fiber.Unsafe wrapping the effect ---

    def call[In: Schema, Out: Schema](
        method: String,
        params: In,
        extras: JsonRpcExtrasEncoder
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Out, Abort[JsonRpcError | Closed]] =
        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(CallEngine.callEffect[In, Out](
            method,
            params,
            extras,
            meter,
            inFlight,
            drainSignal,
            callerRegistry,
            writerChannel,
            exchange,
            config
        ))).unsafe

    def notify[In: Schema](
        method: String,
        params: In,
        extras: JsonRpcExtrasEncoder
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(CallEngine.notifyEffect[In](method, params, extras, writerChannel))).unsafe

    def sendUnmatched[In: Schema](
        method: String,
        params: In,
        id: JsonRpcId,
        extras: JsonRpcExtrasEncoder
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(CallEngine.sendUnmatchedEffect[In](method, params, id, extras, writerChannel))).unsafe

    def callWithProgress[In: Schema, Out: Schema](
        method: String,
        params: In,
        extras: JsonRpcExtrasEncoder
    )(using AllowUnsafe, Frame): Fiber.Unsafe[JsonRpcHandler.Pending[Out], Abort[JsonRpcError | Closed]] =
        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(CallEngine.callWithProgressEffect[In, Out](
            method,
            params,
            extras,
            meter,
            inFlight,
            drainSignal,
            callerRegistry,
            writerChannel,
            exchange,
            config,
            progressPolicy,
            progressStreams,
            tokenToDeadline
        ))).unsafe

    def callPartialResults[In: Schema, T: Schema: Tag](
        method: String,
        params: In,
        extras: JsonRpcExtrasEncoder
    )(using AllowUnsafe, Frame, Tag[Emit[Chunk[T]]]): Stream[T, Async & Abort[JsonRpcError | Closed]] =
        CallEngine.callPartialResultsEffect[In, T](
            method,
            params,
            extras,
            meter,
            inFlight,
            drainSignal,
            callerRegistry,
            writerChannel,
            exchange,
            config,
            progressPolicy,
            progressStreams
        )

    def subscribeProgress(token: Structure.Value)(using AllowUnsafe, Frame): Stream[Structure.Value, Async & Abort[Closed]] =
        Sync.Unsafe.evalOrThrow(ProgressEngine.subscribeProgressEffect(token, progressPolicy, progressStreams, initFrame))

    def unsubscribeProgress(token: Structure.Value)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(ProgressEngine.unsubscribeProgressEffect(token, progressStreams))).unsafe

    def cancel(id: JsonRpcId, reason: Maybe[String])(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(CancellationEngine.cancelEffect(
            id,
            reason,
            callerRegistry,
            config,
            writerChannel
        ))).unsafe

    def awaitDrain(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(LifecycleEngine.awaitDrainEffect(inFlight, drainSignal))).unsafe

    def close(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(closeEffect(gracePeriod))).unsafe

    def dispatch(
        name: String,
        params: Structure.Value,
        ctx: JsonRpcRoute.Context
    )(using Frame): Maybe[Structure.Value < (Async & Abort[JsonRpcError | JsonRpcResponse.Halt])] =
        DispatchEngine.dispatch(methodMap, name, params, ctx)

    private[kyo] def closeImpl(gracePeriod: Duration)(using Frame): Unit < Async =
        LifecycleEngine.closeImpl(
            gracePeriod,
            inFlight,
            drainSignal,
            writerChannel,
            writerFiber,
            transport,
            exchange,
            callerRegistry,
            progressStreams,
            pendingInbound,
            meter,
            initFrame
        )

    private[kyo] def finalizer(using Frame): Unit < Async =
        LifecycleEngine.finalizer(
            writerChannel,
            writerFiber,
            transport,
            exchange,
            callerRegistry,
            progressStreams,
            pendingInbound,
            meter,
            initFrame
        )

end JsonRpcEndpointImpl

object JsonRpcEndpointImpl:

    // private[kyo]: called from JsonRpcHandler.init and JsonRpcHandler.initUnscoped in the kyo package
    private[kyo] def initEngine(
        transport: JsonRpcTransport,
        methods: Seq[JsonRpcRoute[?, ?, ?]],
        config: JsonRpcHandler.Config
    )(using frame: Frame): JsonRpcEndpointImpl < (Sync & Async) =
        // Unsafe: ConcurrentHashMap mirrors Exchange's own internal pattern
        val callerRegistry = new ConcurrentHashMap[JsonRpcId, CallerInfo]()
        val pendingInbound = new ConcurrentHashMap[JsonRpcId, InboundEntry]()
        // Unsafe: ConcurrentHashMap for progress streams, reverse id-to-token map, and deadline refs
        val progressStreams   = new ConcurrentHashMap[Structure.Value, Channel[Structure.Value]]()
        val outboundIdToToken = new ConcurrentHashMap[JsonRpcId, Structure.Value]()
        // AtomicLong.Unsafe aliases java.util.concurrent.atomic.AtomicLong; ConcurrentHashMap cross-platform via JS/Native JDK shim
        val tokenToDeadline = new ConcurrentHashMap[Structure.Value, AtomicLong.Unsafe]()
        val methodMap       = methods.map(m => m.name -> m).toMap
        // Init meter for maxInFlight semaphore when configured; Absent means no rate-limiting.
        val meterEff: Maybe[Meter] < Sync =
            config.maxInFlight match
                case Absent     => Absent
                case Present(n) => Meter.initSemaphoreUnscoped(n).map(m => Present(m))

        meterEff.map { meterMaybe =>
            Channel.initUnscoped[WriterMsg](64).map { writerChannel =>
                // Unsafe: init AtomicInt/AtomicRef/Promise.Unsafe for inFlight and drainSignal counters
                Sync.Unsafe.defer {
                    // Per-endpoint id allocator; its counter is built under this block's ambient AllowUnsafe.
                    val nextIdFn       = IdStrategyEngine.mkNextId(config.idStrategy)
                    val inFlightUnsafe = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
                    val inFlight       = inFlightUnsafe.safe
                    // Unsafe: Promise.Unsafe.init for drainSignal; pre-completed so first inFlight=0 sees a resolved signal
                    val initPromise = Promise.Unsafe.init[Unit, Any]()
                    initPromise.completeUnitDiscard()(using AllowUnsafe.embrace.danger)
                    val drainSigUnsafe = AtomicRef.Unsafe.init[Fiber.Promise[Unit, Any]](initPromise.safe)(using AllowUnsafe.embrace.danger)
                    val drainSignal    = drainSigUnsafe.safe
                    // Unsafe: implRef populated after construction; used by decodeCallback for Reject-close
                    val implRefUnsafe = AtomicRef.Unsafe.init[Maybe[JsonRpcEndpointImpl]](Absent)(using AllowUnsafe.embrace.danger)
                    val implRef       = implRefUnsafe.safe

                    // Encode callback: runs in Sync-only context inside Exchange.apply.
                    // Uses Kyo effect chaining (returns < Sync).
                    val encodeCallback: (JsonRpcId, OutboundReq) => String < Sync =
                        (id, req) =>
                            // Resolve extras with the now-known id; frame captured from initEngine
                            req.extras.resolve(id)(using frame).map { extrasVal =>
                                // Unsafe: register in callerRegistry and complete idSignal inside Exchange encode callback
                                Sync.Unsafe.defer {
                                    // AtomicRef.Unsafe aliases java.util.concurrent.atomic.AtomicReference: per-request pending-cancel cell
                                    val pendingCancel = AtomicRef.Unsafe.init[Maybe[JsonRpcError]](Absent)(using AllowUnsafe.embrace.danger)
                                    callerRegistry.put(
                                        id,
                                        CallerInfo(req.method, extrasVal, req.abortSignal, pendingCancel)
                                    )
                                    req.idSignal.completeDiscard(Result.succeed(id))(using AllowUnsafe.embrace.danger)
                                }.andThen {
                                    // Build envelope and encode to JSON. Structure.encode is pure but throws a
                                    // JsonRpcError for the unencodable cases; Abort.catching reifies that so the
                                    // Success/non-Success branch shape is preserved.
                                    val env = JsonRpcRequest(id, req.method, req.encodedParams, extrasVal)
                                    Abort.run[JsonRpcError](
                                        Abort.catching[JsonRpcError](Structure.encode[JsonRpcEnvelope](env)(using config.codec, frame))
                                    ).map {
                                        case Result.Success(sv) => Json.encode[Structure.Value](sv)
                                        case _                  => ""
                                    }
                                }
                            }

                    // Send callback: called by Exchange to dispatch the encoded wire string.
                    // Must match Exchange's send type: Wire => Unit < (Async & Abort[JsonRpcError])
                    val sendCallback: String => Unit < (Async & Abort[JsonRpcError]) =
                        wire =>
                            Json.decode[Structure.Value](wire) match
                                case Result.Success(sv) =>
                                    // Structure.decode is total for the envelope schema: a bad shape decodes to a
                                    // Malformed envelope rather than a Result.Failure, so getOrElse never falls back.
                                    val env = Structure.decode[JsonRpcEnvelope](sv)(using config.codec, frame)
                                        .getOrElse(JsonRpcMalformedMessage(Absent, "decode failed", sv))
                                    Abort.run[Closed](writerChannel.put(WriterMsg.SendEnvelope(env))).map {
                                        case Result.Success(_) => ()
                                        case Result.Failure(c) =>
                                            Abort.fail(JsonRpcTransportError(s"transport closed: ${c.getMessage}", c))
                                        case Result.Panic(t) => Abort.panic(t)
                                    }
                                case Result.Failure(e) => Abort.fail(JsonRpcTransportError(
                                        "wire decode error",
                                        new RuntimeException("wire decode error")
                                    ))
                                case Result.Panic(t) => Abort.panic(t)

                    // Receive stream: converts transport's Abort[Closed] to Abort[JsonRpcError]
                    // and re-encodes envelopes to JSON strings for Exchange's decode callback.
                    // .map preserves the Stream structure so Exchange's reader fiber sees each wire.
                    val receiveStream: Stream[String, Async & Abort[JsonRpcError]] =
                        transport.incoming(using frame)
                            .map { env =>
                                // Structure.encode is pure but throws a JsonRpcError for the unencodable cases;
                                // Abort.catching reifies that into the stream's existing Abort[JsonRpcError] row.
                                Abort.catching[JsonRpcError](Structure.encode[JsonRpcEnvelope](env)(using config.codec, frame)).map { sv =>
                                    Json.encode[Structure.Value](sv)
                                }
                            }
                            .handle(
                                Abort.run[Closed](_).map {
                                    case Result.Success(_) => ()
                                    case Result.Failure(c) =>
                                        Abort.fail(JsonRpcTransportError(s"transport closed: ${c.getMessage}", c))
                                    case Result.Panic(t) => Abort.panic(t)
                                }
                            )

                    // Decode callback: runs in Sync-only context inside Exchange's reader loop.
                    // May use Kyo effects (returns < Sync). Must NOT park.
                    val decodeCallback: String => Exchange.Message[JsonRpcId, Structure.Value, Nothing] < Sync =
                        wire =>
                            Json.decode[Structure.Value](wire) match
                                case Result.Success(sv) =>
                                    // Structure.decode is total for the envelope schema: a bad shape decodes to a
                                    // Malformed envelope rather than a Result.Failure, so getOrElse never falls back.
                                    Sync.defer(
                                        Structure.decode[JsonRpcEnvelope](sv)(using config.codec, frame)
                                            .getOrElse(JsonRpcMalformedMessage(Absent, "decode failed", sv))
                                    ).map { parsedEnvelope =>
                                        parsedEnvelope match

                                            case env @ JsonRpcNotification(method, params, _) =>
                                                // Step 1a: cancellation policy intercept
                                                config.cancellation match
                                                    case Present(policy) if method == policy.cancelMethod =>
                                                        CancellationEngine.handleInboundCancel(
                                                            env,
                                                            policy,
                                                            pendingInbound
                                                        ).andThen(Exchange.Message.Skip)
                                                    case _ =>
                                                        // Step 1b: progress notification intercept
                                                        config.progress match
                                                            case Present(ppolicy) if method == ppolicy.progressMethod =>
                                                                val paramsVal = params.getOrElse(Structure.Value.Null)
                                                                ppolicy.extractInboundToken(paramsVal).map { tokenOpt =>
                                                                    tokenOpt match
                                                                        case Absent =>
                                                                            Exchange.Message.Skip
                                                                        case Present(token) =>
                                                                            // Unsafe: enqueue progress value into the channel inside the
                                                                            // Sync-only Exchange decode callback. Pattern-match the
                                                                            // Result to surface the buffer-full case loudly (would
                                                                            // otherwise be a silent drop) while still allowing the
                                                                            // legitimate channel-closed race to pass silently when the
                                                                            // call has just completed and the consumer is gone.
                                                                            // Capture the ambient clock for the deadline reset on progress arrival.
                                                                            Clock.use { clock =>
                                                                                Sync.Unsafe.defer {
                                                                                    Maybe(progressStreams.get(token)) match
                                                                                        case Absent => ()
                                                                                        case Present(ch) =>
                                                                                            ch.unsafe.offer(paramsVal)(using
                                                                                                AllowUnsafe.embrace.danger,
                                                                                                frame
                                                                                            ) match
                                                                                                case Result.Success(true) => ()
                                                                                                case Result.Success(false) =>
                                                                                                    bug(
                                                                                                        s"progress channel offer returned false for token=$token; buffer full or queue race ; the value was silently dropped"
                                                                                                    )
                                                                                                case Result.Failure(_) => ()
                                                                                                case Result.Panic(t)   => throw t
                                                                                    end match
                                                                                    // Unsafe: update deadline AtomicLong to reset the requestTimeout clock when progressResetsTimeout = true
                                                                                    if config.progressResetsTimeout then
                                                                                        Maybe(tokenToDeadline.get(token)).foreach {
                                                                                            deadlineLong =>
                                                                                                // ambient wall-clock read inside the enclosing unsafe deferred block
                                                                                                val nowMs = clock.unsafe.now()(using
                                                                                                    AllowUnsafe.embrace.danger
                                                                                                ).toDuration.toMillis
                                                                                                val newDeadline =
                                                                                                    nowMs + config.requestTimeout.toMillis
                                                                                                deadlineLong.set(newDeadline)(using
                                                                                                    AllowUnsafe.embrace.danger
                                                                                                )
                                                                                        }
                                                                                    end if
                                                                                }
                                                                            }.andThen(Exchange.Message.Skip)
                                                                }
                                                            case _ =>
                                                                // Step 2: gate intercept (before method dispatch)
                                                                def dispatchNotification
                                                                    : Exchange.Message[
                                                                        JsonRpcId,
                                                                        Structure.Value,
                                                                        Nothing
                                                                    ] < Sync =
                                                                    // stdlib Map.get() returns scala.Option; match arms are interop at protocol dispatch boundary
                                                                    methodMap.get(method) match
                                                                        case Some(m) =>
                                                                            // Build the per-notification cancel signal inside the deferred boundary (ambient AllowUnsafe).
                                                                            Sync.Unsafe.defer {
                                                                                val cancelledUnsafe = Promise.Unsafe.init[Unit, Sync]()
                                                                                val ctx =
                                                                                    new JsonRpcRoute.Context(
                                                                                        cancelledUnsafe.safe,
                                                                                        Absent,
                                                                                        env.extras,
                                                                                        Absent
                                                                                    )
                                                                                val handlerEffect =
                                                                                    m.handle(
                                                                                        env.params.getOrElse(Structure.Value.Null),
                                                                                        ctx
                                                                                    )(using frame)
                                                                                Fiber.initUnscoped(handlerEffect).map(_ =>
                                                                                    Exchange.Message.Skip
                                                                                )
                                                                            }
                                                                        case None =>
                                                                            // Step 3: unknown-method dispatch for notifications
                                                                            if config.unknownMethod.ignoreUnknownNotification(method)
                                                                            then
                                                                                Exchange.Message.Skip
                                                                            else
                                                                                config.unknownMethod.onUnknownNotification match
                                                                                    case JsonRpcUnknownMethodPolicy.UnknownAction.Drop =>
                                                                                        Exchange.Message.Skip
                                                                                    case JsonRpcUnknownMethodPolicy.UnknownAction.ReplyMethodNotFound =>
                                                                                        Exchange.Message.Skip
                                                                                    case JsonRpcUnknownMethodPolicy.UnknownAction.Reject =>
                                                                                        Log.warn(
                                                                                            s"kyo-jsonrpc: unknown notification method '$method' rejected"
                                                                                        ).andThen {
                                                                                            // Unsafe: read implRef to trigger close; implRef set before any messages arrive
                                                                                            Sync.Unsafe.defer {
                                                                                                implRefUnsafe.get()(using
                                                                                                    AllowUnsafe.embrace.danger
                                                                                                ) match
                                                                                                    case Present(i) =>
                                                                                                        Fiber.initUnscoped(
                                                                                                            i.closeEffect(Duration.Zero)(
                                                                                                                using frame
                                                                                                            )
                                                                                                        )
                                                                                                    case Absent => ()
                                                                                            }.andThen(Exchange.Message.Skip)
                                                                                        }
                                                                            end if
                                                                end dispatchNotification
                                                                config.gate match
                                                                    case Absent =>
                                                                        dispatchNotification
                                                                    case Present(g) =>
                                                                        g.beforeDispatch(env)(using frame).map {
                                                                            case JsonRpcMessageGate.Decision.Allow =>
                                                                                dispatchNotification
                                                                            case JsonRpcMessageGate.Decision.Reject(_) =>
                                                                                // Notifications have no id: log WARN, drop silently (no wire response)
                                                                                Log.warn(
                                                                                    s"kyo-jsonrpc: gate rejected notification method '$method'"
                                                                                ).andThen(
                                                                                    Exchange.Message.Skip
                                                                                )
                                                                            case JsonRpcMessageGate.Decision.Drop =>
                                                                                Exchange.Message.Skip
                                                                        }
                                                                end match

                                            case env2 @ JsonRpcRequest(id, method, params, extras) =>
                                                // Step 2: gate intercept (before method dispatch)
                                                def dispatchRequest: Exchange.Message[JsonRpcId, Structure.Value, Nothing] < Sync =
                                                    // stdlib Map.get() returns scala.Option; match arms are interop at protocol dispatch boundary
                                                    methodMap.get(method) match
                                                        case Some(m) =>
                                                            // Synchronous pre-registration: pendingInbound MUST be populated before
                                                            // the next dispatcher frame is processed. Without this, two races break
                                                            // the BidiTest scenarios:
                                                            //
                                                            //  (a) Progress race: the forked handler may run on another thread and
                                                            //      call ctx.progress(v1) BEFORE the .map continuation reaches
                                                            //      pendingInbound.put. The progress sink reads pendingInbound.get(id),
                                                            //      sees Absent, and silently drops the value. 'begin' (and sometimes
                                                            //      'report') would be missing from the consumer's collected chunk.
                                                            //
                                                            //  (b) Cancellation race: a cancel notification can arrive on the wire
                                                            //      right after the request. CancellationEngine.handleInboundCancel
                                                            //      reads pendingInbound.get(id); if the .map continuation has not yet
                                                            //      written the Running entry, the cancel is logged-and-dropped, the
                                                            //      handler's ctx.cancelled is never completed, and the handler hangs.
                                                            //
                                                            // Both are eliminated by registering INSIDE this Sync.Unsafe.defer (which
                                                            // runs to completion before decodeCallback returns Skip to Exchange) with
                                                            // a Promise standing in for the not-yet-forked handler fiber. The Promise
                                                            // is later linked to the real fiber via `become`, which forwards completion
                                                            // from the fiber to the promise and propagates interrupts the other way ;
                                                            // so handleInboundCancel's `running.handler.unsafe.interruptDiscard` still
                                                            // reaches the actual handler fiber for the !expectReplyForCancelledRequest
                                                            // path.
                                                            Sync.Unsafe.defer {
                                                                // Build the per-request cancel signal and progress sink inside the deferred
                                                                // boundary (ambient AllowUnsafe), before registering and forking the handler.
                                                                val cancelledUnsafe = Promise.Unsafe.init[Unit, Sync]()
                                                                val progressSinkOpt
                                                                    : Maybe[Structure.Value => Unit < (Async & Abort[Closed])] =
                                                                    ProgressEngine.buildProgressSink(
                                                                        id,
                                                                        params,
                                                                        extras,
                                                                        config.progress,
                                                                        pendingInbound,
                                                                        writerChannel
                                                                    )
                                                                val ctx =
                                                                    new JsonRpcRoute.Context(
                                                                        cancelledUnsafe.safe,
                                                                        Present(id),
                                                                        extras,
                                                                        progressSinkOpt
                                                                    )
                                                                // Unsafe: Promise.Unsafe.init for the proxy-fiber handle that lives in
                                                                // pendingInbound before the real fiber is forked.
                                                                val handlerProxy =
                                                                    Promise.Unsafe.init[
                                                                        Structure.Value,
                                                                        Abort[JsonRpcError | JsonRpcResponse.Halt]
                                                                    ]()(using AllowUnsafe.embrace.danger)
                                                                val entry =
                                                                    InboundEntry.Running(method, handlerProxy.safe, cancelledUnsafe.safe)
                                                                pendingInbound.put(id, entry)
                                                                val handlerEffect =
                                                                    m.handle(params.getOrElse(Structure.Value.Null), ctx)(using frame)
                                                                Fiber.initUnscoped(handlerEffect).map { fiber =>
                                                                    // Link the proxy to the real fiber so completions mirror and
                                                                    // interrupts propagate.
                                                                    Sync.Unsafe.defer {
                                                                        handlerProxy.becomeDiscard(fiber)(using AllowUnsafe.embrace.danger)
                                                                        // Attach completion hook AFTER putting in pendingInbound
                                                                        // fiber onComplete attaches cleanup hook from outside the fiber; no safe equivalent in Fiber public API
                                                                        fiber.unsafe.onComplete { result =>
                                                                            val responseEnvelope = result match
                                                                                case Result.Success(sv) =>
                                                                                    JsonRpcResponse(
                                                                                        id,
                                                                                        Present(sv.eval(using frame)),
                                                                                        Absent,
                                                                                        extras
                                                                                    )
                                                                                case Result.Failure(halt: JsonRpcResponse.Halt) =>
                                                                                    // Handler short-circuited with Halt; emit the wrapped response directly.
                                                                                    halt.response
                                                                                case Result.Failure(e: JsonRpcError) =>
                                                                                    JsonRpcResponse(id, Absent, Present(e), extras)
                                                                                case Result.Panic(t) =>
                                                                                    JsonRpcResponse(
                                                                                        id,
                                                                                        Absent,
                                                                                        Present(
                                                                                            JsonRpcHandlerPanicError(method, t)(using frame)
                                                                                        ),
                                                                                        extras
                                                                                    )
                                                                            // CAS: Running -> Replying (fails if cancel moved it to Cancelled)
                                                                            pendingInbound.get(id) match
                                                                                case running: InboundEntry.Running =>
                                                                                    // Unsafe: AtomicBoolean.Unsafe.init for suppress flag
                                                                                    val suppressUnsafe =
                                                                                        AtomicBoolean.Unsafe.init(false)(using
                                                                                            AllowUnsafe.embrace.danger
                                                                                        )
                                                                                    val replying =
                                                                                        InboundEntry.Replying(method, suppressUnsafe.safe)
                                                                                    if pendingInbound.replace(id, running, replying) then
                                                                                        // Unsafe: writer-channel offer from Sync-only onComplete callback
                                                                                        // channel offer from Sync-only Exchange callback (no Frame in scope); no safe Channel equivalent
                                                                                        discard(writerChannel.unsafe.offer(
                                                                                            WriterMsg.SuppressIfCancelled(
                                                                                                id,
                                                                                                responseEnvelope
                                                                                            )
                                                                                        )(using AllowUnsafe.embrace.danger, frame))
                                                                                    end if
                                                                                case _: InboundEntry.Cancelled =>
                                                                                    // Cancel won the CAS. If the policy demands a reply for cancelled
                                                                                    // requests, send the response anyway; otherwise the handler was
                                                                                    // interrupted and produces no reply.
                                                                                    val mustReply = config.cancellation match
                                                                                        case Present(p) => p.expectReplyForCancelledRequest
                                                                                        case Absent     => false
                                                                                    if mustReply then
                                                                                        // Unsafe: SendEnvelope bypasses suppress check because the policy demands a reply.
                                                                                        // channel offer from Sync-only Exchange callback (no Frame in scope); no safe Channel equivalent
                                                                                        discard(writerChannel.unsafe.offer(
                                                                                            WriterMsg.SendEnvelope(responseEnvelope)
                                                                                        )(using AllowUnsafe.embrace.danger, frame))
                                                                                        discard(pendingInbound.remove(id))
                                                                                    end if
                                                                                case _ => ()
                                                                            end match
                                                                        }(using AllowUnsafe.embrace.danger)
                                                                    }
                                                                }.andThen(Exchange.Message.Skip)
                                                            }
                                                        case None =>
                                                            // Step 3: unknown-method dispatch for requests
                                                            config.unknownMethod.onUnknownRequest match
                                                                case JsonRpcUnknownMethodPolicy.UnknownAction.ReplyMethodNotFound =>
                                                                    val response = JsonRpcResponse(
                                                                        id,
                                                                        Absent,
                                                                        Present(JsonRpcMethodNotFoundError(
                                                                            method,
                                                                            Chunk.from(methodMap.keys)
                                                                        )(using frame)),
                                                                        Absent
                                                                    )
                                                                    // Unsafe: offer to writerChannel inside Exchange decode callback
                                                                    Sync.Unsafe.defer {
                                                                        val msg = WriterMsg.SendEnvelope(response)
                                                                        // format: off
                                                                        // channel offer from Sync-only Exchange callback (no Frame in scope); no safe Channel equivalent
                                                                        discard(writerChannel.unsafe.offer(msg)(using AllowUnsafe.embrace.danger, frame))
                                                                        // format: on
                                                                    }.andThen(Exchange.Message.Skip)
                                                                case JsonRpcUnknownMethodPolicy.UnknownAction.Drop =>
                                                                    Exchange.Message.Skip
                                                                case JsonRpcUnknownMethodPolicy.UnknownAction.Reject =>
                                                                    // Reject for a Request: send MethodNotFound first (so caller is unblocked), then close.
                                                                    val response = JsonRpcResponse(
                                                                        id,
                                                                        Absent,
                                                                        Present(JsonRpcMethodNotFoundError(
                                                                            method,
                                                                            Chunk.from(methodMap.keys)
                                                                        )(using frame)),
                                                                        Absent
                                                                    )
                                                                    // Unsafe: offer to writerChannel inside Exchange decode callback
                                                                    Sync.Unsafe.defer {
                                                                        val msg = WriterMsg.SendEnvelope(response)
                                                                        // format: off
                                                                        // channel offer from Sync-only Exchange callback (no Frame in scope); no safe Channel equivalent
                                                                        discard(writerChannel.unsafe.offer(msg)(using AllowUnsafe.embrace.danger, frame))
                                                                        // format: on
                                                                    }.andThen {
                                                                        // Unsafe: read implRef to trigger close; implRef set before any messages arrive
                                                                        Sync.Unsafe.defer {
                                                                            implRefUnsafe.get()(using AllowUnsafe.embrace.danger) match
                                                                                case Present(i) =>
                                                                                    Fiber.initUnscoped(i.closeEffect(Duration.Zero)(using
                                                                                        frame
                                                                                    ))
                                                                                case Absent => ()
                                                                        }.andThen(Exchange.Message.Skip)
                                                                    }
                                                end dispatchRequest
                                                config.gate match
                                                    case Absent =>
                                                        dispatchRequest
                                                    case Present(g) =>
                                                        g.beforeDispatch(env2)(using frame).map {
                                                            case JsonRpcMessageGate.Decision.Allow =>
                                                                dispatchRequest
                                                            case JsonRpcMessageGate.Decision.Reject(response) =>
                                                                // Request has an id: send the gate-supplied response so caller is not left hanging
                                                                // Unsafe: offer to writerChannel inside gate decision handler
                                                                Sync.Unsafe.defer {
                                                                    val msg = WriterMsg.SendEnvelope(response)
                                                                    // format: off
                                                                    // channel offer from Sync-only Exchange callback (no Frame in scope); no safe Channel equivalent
                                                                    discard(writerChannel.unsafe.offer(msg)(using AllowUnsafe.embrace.danger, frame))
                                                                    // format: on
                                                                }.andThen(Exchange.Message.Skip)
                                                            case JsonRpcMessageGate.Decision.Drop =>
                                                                Exchange.Message.Skip
                                                        }
                                                end match

                                            case JsonRpcResponse(id, result, error, _) =>
                                                error match
                                                    case Present(e) =>
                                                        // Unsafe: complete abortSignal inside Exchange decode callback so raceFirst selects the abort arm.
                                                        // Return Skip so Exchange does not also complete the pending promise with a null value.
                                                        Sync.Unsafe.defer {
                                                            Maybe(callerRegistry.get(id)).foreach { info =>
                                                                // promise completion called from outside originating fiber to signal abort or cancel; no safe equivalent in Promise public API
                                                                info.abortSignal.unsafe.completeDiscard(Result.succeed(e))(using
                                                                    AllowUnsafe.embrace.danger
                                                                )
                                                            }
                                                        }.andThen(Exchange.Message.Skip)

                                                    case Absent =>
                                                        // Unsafe: check pendingCancelError inside Exchange decode callback
                                                        Sync.Unsafe.defer {
                                                            Maybe(callerRegistry.get(id)) match
                                                                case Present(info) =>
                                                                    info.pendingCancelError.get()(using AllowUnsafe.embrace.danger) match
                                                                        case Present(cancelErr) =>
                                                                            // A reply-demanding cancel was issued; the reply has now arrived but the caller still sees the configured cancel error.
                                                                            // Complete abortSignal with cancel error and Skip the response.
                                                                            // promise completion called from outside originating fiber to signal abort or cancel; no safe equivalent in Promise public API
                                                                            info.abortSignal.unsafe.completeDiscard(
                                                                                Result.succeed(cancelErr)
                                                                            )(using AllowUnsafe.embrace.danger)
                                                                            Exchange.Message.Skip
                                                                        case Absent =>
                                                                            Exchange.Message.Response(
                                                                                id,
                                                                                result.getOrElse(Structure.Value.Null)
                                                                            )
                                                                case Absent =>
                                                                    Exchange.Message.Response(
                                                                        id,
                                                                        result.getOrElse(Structure.Value.Null)
                                                                    )
                                                        }

                                            case JsonRpcMalformedMessage(Present(id), reason, _) =>
                                                Sync.Unsafe.defer {
                                                    Maybe(callerRegistry.get(id)) match
                                                        case Present(info) =>
                                                            // CAS-won path completes pending caller promise from outside originating fiber
                                                            info.abortSignal.unsafe.completeDiscard(
                                                                Result.succeed(JsonRpcInvalidRequestError(
                                                                    Structure.Value.Str(s"malformed response: $reason"),
                                                                    Chunk.empty
                                                                )(using frame))
                                                            )(using AllowUnsafe.embrace.danger)
                                                        case Absent =>
                                                            ()
                                                }.andThen(Exchange.Message.Skip)
                                            case JsonRpcMalformedMessage(_, _, _) =>
                                                // No id to correlate (Absent), or an id with no pending caller: drop. `Maybe` is opaque,
                                                // so this catch-all (not `Absent`) is what makes the match exhaustive under -Werror.
                                                Exchange.Message.Skip
                                    }
                                case Result.Failure(_) =>
                                    Exchange.Message.Skip
                                case Result.Panic(_) =>
                                    Exchange.Message.Skip

                    Exchange.initUnscoped[JsonRpcId, OutboundReq, Structure.Value, String, Nothing, JsonRpcError](
                        nextId = nextIdFn(),
                        encode = encodeCallback,
                        send = sendCallback,
                        receive = receiveStream,
                        decode = decodeCallback
                    ).map { exchange =>
                        val writerLoop: Unit < Async =
                            Abort.run[Closed] {
                                Stream.unfold((), chunkSize = 1) { _ =>
                                    writerChannel.take.map { msg => Present((msg, ())) }
                                }.foreachChunk { chunk =>
                                    Kyo.foreachDiscard(chunk) { msg =>
                                        msg match
                                            case WriterMsg.SendEnvelope(env) =>
                                                Abort.run[Closed](transport.send(env)(using frame)).unit

                                            case WriterMsg.SuppressIfCancelled(id, env) =>
                                                val shouldDrop: Boolean =
                                                    config.cancellation match
                                                        case Present(p) if !p.expectReplyForCancelledRequest =>
                                                            pendingInbound.get(id) match
                                                                case r: InboundEntry.Replying =>
                                                                    // Unsafe: read suppress flag in writer loop
                                                                    // AtomicX getter from Sync-only Exchange callback or monitor fiber; no safe Atomic equivalent within Sync
                                                                    r.suppress.unsafe.get()(using AllowUnsafe.embrace.danger)
                                                                case _ => false
                                                        case _ => false
                                                // Unsafe: remove from pendingInbound in writer loop (outside fiber)
                                                Sync.Unsafe.defer(pendingInbound.remove(id)).andThen {
                                                    if shouldDrop then Kyo.unit
                                                    else Abort.run[Closed](transport.send(env)(using frame)).unit
                                                }
                                    }
                                }
                            }.unit

                        Fiber.initUnscoped(writerLoop).map { writerFib =>
                            val impl = new JsonRpcEndpointImpl(
                                callerRegistry = callerRegistry,
                                pendingInbound = pendingInbound,
                                writerChannel = writerChannel,
                                exchange = exchange,
                                transport = transport,
                                writerFiber = writerFib,
                                inFlight = inFlight,
                                drainSignal = drainSignal,
                                codec = config.codec,
                                methodMap = methodMap,
                                unknownPolicy = config.unknownMethod,
                                config = config,
                                initFrame = frame,
                                progressPolicy = config.progress,
                                progressStreams = progressStreams,
                                outboundIdToToken = outboundIdToToken,
                                meter = meterMaybe,
                                tokenToDeadline = tokenToDeadline
                            )
                            // Unsafe: populate implRef so decodeCallback Reject-close branches can trigger engine close
                            implRefUnsafe.set(Present(impl))(using AllowUnsafe.embrace.danger)
                            impl
                        }
                    }
                }
            }
        } // end meterEff.map
    end initEngine

end JsonRpcEndpointImpl
