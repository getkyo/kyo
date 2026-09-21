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
    pendingCancelError: AtomicRef.Unsafe[Maybe[JsonRpcError]],
    // Completed once this call's request envelope has been handed to writerChannel (or the call has
    // terminated). An outbound cancel for this id waits on it, so a cancel can never be enqueued, and
    // thus delivered, ahead of its own request; the peer would otherwise drop the cancel as an unknown
    // id and the handler would block on ctx.cancelled (or the caller on the reply) until timeout.
    requestEnqueued: Fiber.Promise[Unit, Sync]
)

sealed private[kyo] trait WriterMsg
private[kyo] object WriterMsg:
    /** An envelope written unconditionally: notifications, cancel notifications, unmatched requests, replies that bypass the cancel check.
      * `relatedTo` is the inbound request whose handler sent it, if any.
      */
    case class SendEnvelope(env: JsonRpcEnvelope, relatedTo: Maybe[JsonRpcId] = Absent) extends WriterMsg

    /** An outbound call's request. Skipped once the handler is closing: its caller has already been failed by the close. */
    case class SendCallRequest(env: JsonRpcEnvelope, relatedTo: Maybe[JsonRpcId]) extends WriterMsg

    /** `env` as a writer message, related to the inbound request the calling fiber's handler answers, if any. */
    def related(env: JsonRpcEnvelope)(using Frame): WriterMsg < Sync =
        InboundPipeline.answering.use(id => SendEnvelope(env, id))

    /** A settled inbound reply, written only if [[InboundRequest.commit]] still allows it at the moment of writing. */
    case class SendReply(request: InboundRequest, env: JsonRpcEnvelope) extends WriterMsg
end WriterMsg

final class JsonRpcEndpointImpl private[kyo] (
    private[kyo] val callerRegistry: ConcurrentHashMap[JsonRpcId, CallerInfo],
    private[kyo] val inbound: InboundPipeline,
    private val writerChannel: Channel[WriterMsg],
    private val exchange: Exchange[OutboundReq, Structure.Value, Nothing, JsonRpcError],
    private val transport: JsonRpcTransport,
    private val writerFiber: Fiber[Unit, Any],
    // Outbound calls in flight, mirrored from `outbound` for diagnostics.
    private[kyo] val inFlight: AtomicInt,
    private[kyo] val outbound: WorkTracker,
    private[kyo] val work: WorkTracker,
    private[kyo] val deliveries: WorkTracker,
    private val closeStarted: AtomicBoolean,
    private val closeDone: Fiber.Promise[Unit, Any],
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

    // --- Effectful operations: the safe tier runs these on the calling fiber; the Unsafe methods start them on a carrier ---

    private[kyo] def callEffect[In: Schema, Out: Schema](
        method: String,
        params: In,
        extras: JsonRpcExtrasEncoder
    )(using Frame): Out < (Async & Abort[JsonRpcError | Closed]) =
        CallEngine.callEffect[In, Out](method, params, extras, meter, outbound, work, callerRegistry, writerChannel, exchange, config)

    private[kyo] def notifyEffect[In: Schema](
        method: String,
        params: In,
        extras: JsonRpcExtrasEncoder
    )(using Frame): Unit < (Async & Abort[Closed]) =
        CallEngine.notifyEffect[In](method, params, extras, writerChannel)

    private[kyo] def sendUnmatchedEffect[In: Schema](
        method: String,
        params: In,
        id: JsonRpcId,
        extras: JsonRpcExtrasEncoder
    )(using Frame): Unit < (Async & Abort[Closed]) =
        CallEngine.sendUnmatchedEffect[In](method, params, id, extras, writerChannel)

    private[kyo] def callWithProgressEffect[In: Schema, Out: Schema](
        method: String,
        params: In,
        extras: JsonRpcExtrasEncoder
    )(using Frame): JsonRpcHandler.Pending[Out] < (Async & Abort[JsonRpcError | Closed]) =
        CallEngine.callWithProgressEffect[In, Out](
            method,
            params,
            extras,
            meter,
            outbound,
            work,
            callerRegistry,
            writerChannel,
            exchange,
            config,
            progressPolicy,
            progressStreams,
            tokenToDeadline
        )

    private[kyo] def unsubscribeProgressEffect(token: Structure.Value)(using Frame): Unit < Async =
        ProgressEngine.unsubscribeProgressEffect(token, progressStreams)

    private[kyo] def cancelEffect(id: JsonRpcId, reason: Maybe[String])(using Frame): Unit < (Async & Abort[Closed]) =
        CancellationEngine.cancelEffect(id, reason, callerRegistry, config, writerChannel)

    private[kyo] def awaitDrainEffect(using Frame): Unit < Async =
        outbound.awaitIdle

    // Also referenced by the unknown-method Reject close. Forwards to LifecycleEngine.
    private[kyo] def closeEffect(gracePeriod: Duration)(using Frame): Unit < Async =
        LifecycleEngine.closeEffect(gracePeriod, lifecycle)

    private def lifecycle: LifecycleEngine.Resources =
        LifecycleEngine.Resources(
            work = work,
            deliveries = deliveries,
            registry = inbound.registry,
            writerChannel = writerChannel,
            writerFiber = writerFiber,
            transport = transport,
            exchange = exchange,
            callerRegistry = callerRegistry,
            progressStreams = progressStreams,
            meter = meter,
            closeStarted = closeStarted,
            closeDone = closeDone
        )

    // --- Public Unsafe interface: every method returns Fiber.Unsafe wrapping the effect ---

    def call[In: Schema, Out: Schema](
        method: String,
        params: In,
        extras: JsonRpcExtrasEncoder
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Out, Abort[JsonRpcError | Closed]] =
        Fiber.Unsafe.init(callEffect[In, Out](method, params, extras))

    def notify[In: Schema](
        method: String,
        params: In,
        extras: JsonRpcExtrasEncoder
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
        Fiber.Unsafe.init(notifyEffect[In](method, params, extras))

    def sendUnmatched[In: Schema](
        method: String,
        params: In,
        id: JsonRpcId,
        extras: JsonRpcExtrasEncoder
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
        Fiber.Unsafe.init(sendUnmatchedEffect[In](method, params, id, extras))

    def callWithProgress[In: Schema, Out: Schema](
        method: String,
        params: In,
        extras: JsonRpcExtrasEncoder
    )(using AllowUnsafe, Frame): Fiber.Unsafe[JsonRpcHandler.Pending[Out], Abort[JsonRpcError | Closed]] =
        Fiber.Unsafe.init(callWithProgressEffect[In, Out](method, params, extras))

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
            outbound,
            work,
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
        Fiber.Unsafe.init(unsubscribeProgressEffect(token))

    def cancel(id: JsonRpcId, reason: Maybe[String])(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
        Fiber.Unsafe.init(cancelEffect(id, reason))

    def awaitDrain(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        Fiber.Unsafe.init(awaitDrainEffect)

    def close(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        Fiber.Unsafe.init(closeEffect(gracePeriod))

    def dispatch(
        name: String,
        params: Structure.Value,
        ctx: JsonRpcRoute.Context
    )(using Frame): Maybe[Structure.Value < (Async & Abort[JsonRpcError | JsonRpcResponse.Halt])] =
        DispatchEngine.dispatch(methodMap, name, params, ctx)

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
                // Unsafe: builds the engine's counters, trackers, close state and inbound pipeline under this block's AllowUnsafe
                Sync.Unsafe.defer {
                    // Per-endpoint id allocator; its counter is built under this block's ambient AllowUnsafe.
                    val nextIdFn     = IdStrategyEngine.mkNextId(config.idStrategy)
                    val inFlight     = AtomicInt.Unsafe.init(0)
                    val outbound     = WorkTracker.initMirrored(Present(inFlight))
                    val work         = WorkTracker.init()
                    val deliveries   = WorkTracker.init()
                    val closeStarted = AtomicBoolean.Unsafe.init(false)
                    val closeDone    = Promise.Unsafe.init[Unit, Any]()
                    val pipeline     = new InboundPipeline(methodMap, config, InboundRegistry.init())
                    // Unsafe: implRef populated after construction; used by the admission path for the unknown-method Reject close
                    val implRefUnsafe = AtomicRef.Unsafe.init[Maybe[JsonRpcEndpointImpl]](Absent)

                    // Notifications tied to an inbound request travel the same writer as everything else, so progress reported
                    // by a handler reaches the wire before that handler's reply.
                    val requestEnv = new InboundPipeline.Environment(
                        emit = notification => WriterMsg.related(notification).map(writerChannel.put),
                        isLive = writerChannel.closed.map(!_)
                    )

                    // Hands a message to the writer from a Sync-only callback without dropping it when the writer channel is full:
                    // offer first, and only when the channel is full fork a put that parks until there is room. `handedOff` runs once
                    // the message is in the channel or was refused (after `onRefused`), which is when the caller's accounting ends.
                    def deliver(msg: WriterMsg, onRefused: () => Unit, handedOff: () => Unit)(using AllowUnsafe): Unit =
                        writerChannel.unsafe.offer(msg) match
                            case Result.Success(true)  => handedOff()
                            case Result.Success(false) =>
                                val put = Fiber.Unsafe.init(Abort.run[Closed](writerChannel.put(msg)))
                                // fiber onComplete observes the parked put from outside its fiber; no safe equivalent in Fiber public API
                                put.onComplete { result =>
                                    result match
                                        case Result.Success(outcome) if outcome.eval.isSuccess => ()
                                        case _                                                 => onRefused()
                                    handedOff()
                                }
                            case _ =>
                                onRefused()
                                handedOff()
                    end deliver

                    // Invoked once per dispatched inbound handler, from its completion: route the reply decision to the writer and
                    // release the handler's unit of work. The reply's hand-off is recorded once it is in the writer channel (or refused),
                    // which is what a close that counted the reply waits for before closing that channel.
                    val onSettle: (InboundRequest, InboundPipeline.Settlement) => Unit =
                        (request, settlement) =>
                            // Unsafe: runs in the handler fiber's completion callback, outside any effect.
                            given AllowUnsafe = AllowUnsafe.embrace.danger
                            settlement match
                                case InboundPipeline.Settlement.Reply(response) =>
                                    deliver(WriterMsg.SendReply(request, response), () => request.abandon(), () => request.handOff())
                                case InboundPipeline.Settlement.NoReply => ()
                            end match
                            work.release()

                    // Every inbound request and notification (the cancel notification included) goes through the pipeline. The
                    // admission counts as work until it settles, so close(gracePeriod) waits for inbound handlers too. It also counts
                    // as a delivery from before the pipeline reads the closing flag until any answer it produces is with the writer, so
                    // a close either stops the admission or waits for its answer.
                    def admitInbound(envelope: JsonRpcRequest | JsonRpcNotification)
                        : Exchange.Message[JsonRpcId, Structure.Value, Nothing] < Sync =
                        Sync.Unsafe.defer { work.acquire(); deliveries.acquire() }.andThen {
                            Abort.run[Nothing](pipeline.admit(envelope, requestEnv, onSettle)).map { admitted =>
                                Sync.Unsafe.defer {
                                    admitted match
                                        case Result.Success(InboundPipeline.Admission.Dispatched(_)) =>
                                            // The handler's work is released by onSettle; its reply is counted by the registry.
                                            deliveries.release()
                                        case Result.Success(InboundPipeline.Admission.Ignored) =>
                                            deliveries.release()
                                            work.release()
                                        case Result.Success(InboundPipeline.Admission.Respond(response)) =>
                                            deliver(WriterMsg.SendEnvelope(response), () => (), () => deliveries.release())
                                            work.release()
                                        case Result.Success(InboundPipeline.Admission.Violation(response)) =>
                                            response match
                                                case Present(r) => deliver(WriterMsg.SendEnvelope(r), () => (), () => deliveries.release())
                                                case Absent     => deliveries.release()
                                            work.release()
                                            implRefUnsafe.get().foreach { impl =>
                                                discard(Fiber.Unsafe.init(impl.closeEffect(Duration.Zero)))
                                            }
                                        case Result.Panic(t) =>
                                            deliveries.release()
                                            work.release()
                                            throw t
                                    end match
                                    Exchange.Message.Skip
                                }
                            }
                        }
                    end admitInbound

                    // Encode callback: runs inside Exchange.apply. An envelope the codec cannot encode
                    // (extras carrying a reserved key) aborts JsonRpcError here, so the call fails naming
                    // what actually failed instead of encoding to "" and surfacing as a wire decode error.
                    val encodeCallback: (JsonRpcId, OutboundReq) => String < (Sync & Abort[JsonRpcError]) =
                        (id, req) =>
                            // Resolve extras with the now-known id; frame captured from initEngine
                            req.extras.resolve(id)(using frame).map { extrasVal =>
                                // Unsafe: register in callerRegistry and complete idSignal inside Exchange encode callback
                                Sync.Unsafe.defer {
                                    // AtomicRef.Unsafe aliases java.util.concurrent.atomic.AtomicReference: per-request pending-cancel cell
                                    val pendingCancel   = AtomicRef.Unsafe.init[Maybe[JsonRpcError]](Absent)
                                    val requestEnqueued = Promise.Unsafe.init[Unit, Sync]()
                                    callerRegistry.put(
                                        id,
                                        CallerInfo(req.method, extrasVal, req.abortSignal, pendingCancel, requestEnqueued.safe)
                                    )
                                    req.idSignal.completeDiscard(Result.succeed(id))
                                }.andThen {
                                    // Build envelope and encode to JSON. Structure.encode is pure but throws a
                                    // JsonRpcError for the unencodable cases; Abort.run reifies that so the
                                    // Success/non-Success branch shape is preserved.
                                    val env = JsonRpcRequest(id, req.method, req.encodedParams, extrasVal)
                                    Abort.catching[JsonRpcError](
                                        Structure.encode[JsonRpcEnvelope](env)(using config.codec, frame)
                                    ).map(Json.encode[Structure.Value](_))
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
                                    Abort.run[Closed](
                                        InboundPipeline.answering.use(id => writerChannel.put(WriterMsg.SendCallRequest(env, id)))
                                    ).map { putResult =>
                                        // Now that this request envelope is on writerChannel, release any cancel for its
                                        // id that is waiting on requestEnqueued, so the cancel can only be enqueued behind
                                        // the request. Completed on put failure too, so a racing cancel never hangs.
                                        (env match
                                            case r: JsonRpcRequest =>
                                                Sync.Unsafe.defer {
                                                    Maybe(callerRegistry.get(r.id)).foreach { info =>
                                                        info.requestEnqueued.unsafe.completeUnitDiscard()
                                                    }
                                                }
                                            case _ => Kyo.unit
                                        ).andThen {
                                            putResult match
                                                case Result.Success(_) => ()
                                                case Result.Failure(_) =>
                                                    // Only a close closes the writer channel. The call fails the way every call
                                                    // caught by a close does, with JsonRpcLifecycleError(Close) through its abort
                                                    // signal; the send itself reports success so the Exchange is not shut down
                                                    // with a transport error, which would misreport the close to later calls.
                                                    env match
                                                        case r: JsonRpcRequest =>
                                                            // Unsafe: completes the caller's abort signal from the Exchange send callback
                                                            Sync.Unsafe.defer {
                                                                Maybe(callerRegistry.get(r.id)).foreach { info =>
                                                                    info.abortSignal.unsafe.completeDiscard(
                                                                        Result.succeed(
                                                                            JsonRpcLifecycleError(JsonRpcLifecycleError.Stage.Close)
                                                                        )
                                                                    )
                                                                }
                                                            }
                                                        case _ => Kyo.unit
                                                case Result.Panic(t) => Abort.panic(t)
                                        }
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

                    // Inbound progress for a call this side made (callWithProgress / callPartialResults / subscribeProgress).
                    // Intercepted before the inbound pipeline, as before; the cancel notification is the pipeline's, so it wins
                    // when a policy (unusually) reuses the method name.
                    def isOutboundProgress(method: String): Boolean =
                        config.progress.exists(_.progressMethod == method) &&
                            !config.cancellation.exists(_.cancelMethod == method)

                    def routeOutboundProgress(
                        policy: JsonRpcProgressPolicy,
                        params: Maybe[Structure.Value]
                    ): Exchange.Message[JsonRpcId, Structure.Value, Nothing] < Sync =
                        val paramsVal = params.getOrElse(Structure.Value.Null)
                        policy.extractInboundToken(paramsVal).map { tokenOpt =>
                            tokenOpt match
                                case Absent =>
                                    Exchange.Message.Skip
                                case Present(token) =>
                                    // Unsafe: enqueue progress value into the channel inside the Sync-only Exchange decode
                                    // callback. Pattern-match the Result to surface the buffer-full case loudly (would otherwise
                                    // be a silent drop) while still allowing the legitimate channel-closed race to pass silently
                                    // when the call has just completed and the consumer is gone.
                                    // Capture the ambient clock for the deadline reset on progress arrival.
                                    Clock.use { clock =>
                                        Sync.Unsafe.defer {
                                            // Unsafe: update deadline AtomicLong to reset the requestTimeout clock when progressResetsTimeout = true.
                                            // Reset before the value is published, so a consumer that has seen a progress value knows its
                                            // deadline was already extended.
                                            if config.progressResetsTimeout then
                                                Maybe(tokenToDeadline.get(token)).foreach { deadlineLong =>
                                                    // ambient clock read inside the enclosing unsafe deferred block
                                                    val nowMs       = clock.unsafe.now().toDuration.toMillis
                                                    val newDeadline = nowMs + config.requestTimeout.toMillis
                                                    deadlineLong.set(newDeadline)
                                                }
                                            end if
                                            Maybe(progressStreams.get(token)) match
                                                case Absent      => ()
                                                case Present(ch) =>
                                                    ch.unsafe.offer(paramsVal) match
                                                        case Result.Success(true)  => ()
                                                        case Result.Success(false) =>
                                                            bug(
                                                                s"progress channel offer returned false for token=$token; buffer full or queue race ; the value was silently dropped"
                                                            )
                                                        case Result.Failure(_) => ()
                                                        case Result.Panic(t)   => throw t
                                            end match
                                        }
                                    }.andThen(Exchange.Message.Skip)
                        }
                    end routeOutboundProgress

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
                                            case notification @ JsonRpcNotification(method, params, _) =>
                                                config.progress match
                                                    case Present(policy) if isOutboundProgress(method) =>
                                                        routeOutboundProgress(policy, params)
                                                    case _ =>
                                                        admitInbound(notification)

                                            case request: JsonRpcRequest =>
                                                admitInbound(request)

                                            case JsonRpcResponse(id, result, error, _) =>
                                                error match
                                                    case Present(e) =>
                                                        // Unsafe: complete abortSignal inside Exchange decode callback so raceFirst selects the abort arm.
                                                        // Return Skip so Exchange does not also complete the pending promise with a null value.
                                                        Sync.Unsafe.defer {
                                                            Maybe(callerRegistry.get(id)).foreach { info =>
                                                                // promise completion called from outside originating fiber to signal abort or cancel; no safe equivalent in Promise public API
                                                                info.abortSignal.unsafe.completeDiscard(Result.succeed(e))
                                                            }
                                                        }.andThen(Exchange.Message.Skip)

                                                    case Absent =>
                                                        // Unsafe: check pendingCancelError inside Exchange decode callback
                                                        Sync.Unsafe.defer {
                                                            Maybe(callerRegistry.get(id)) match
                                                                case Present(info) =>
                                                                    info.pendingCancelError.get() match
                                                                        case Present(cancelErr) =>
                                                                            // A reply-demanding cancel was issued; the reply has now arrived but the caller still sees the configured cancel error.
                                                                            // Complete abortSignal with cancel error and Skip the response.
                                                                            // promise completion called from outside originating fiber to signal abort or cancel; no safe equivalent in Promise public API
                                                                            info.abortSignal.unsafe.completeDiscard(
                                                                                Result.succeed(cancelErr)
                                                                            )
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
                                                            )
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
                                    Kyo.foreachDiscard(chunk) {
                                        case WriterMsg.SendEnvelope(env, relatedTo) =>
                                            Abort.run[Closed | JsonRpcError](transport.send(env, relatedTo)(using frame)).unit
                                        case WriterMsg.SendCallRequest(env, relatedTo) =>
                                            // Unsafe: closing flag read in the writer loop
                                            Sync.Unsafe.defer(pipeline.registry.isClosing()).map { closing =>
                                                if closing then Kyo.unit
                                                else Abort.run[Closed | JsonRpcError](transport.send(env, relatedTo)(using frame)).unit
                                            }
                                        case WriterMsg.SendReply(request, env) =>
                                            // Unsafe: the late cancel check, made at the moment of writing
                                            Sync.Unsafe.defer(request.commit()).map { write =>
                                                if write then Abort.run[Closed | JsonRpcError](transport.send(env)(using frame)).unit
                                                else Kyo.unit
                                            }
                                    }
                                }
                            }.unit

                        Fiber.initUnscoped(writerLoop).map { writerFib =>
                            val impl = new JsonRpcEndpointImpl(
                                callerRegistry = callerRegistry,
                                inbound = pipeline,
                                writerChannel = writerChannel,
                                exchange = exchange,
                                transport = transport,
                                writerFiber = writerFib,
                                inFlight = inFlight.safe,
                                outbound = outbound,
                                work = work,
                                deliveries = deliveries,
                                closeStarted = closeStarted.safe,
                                closeDone = closeDone.safe,
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
                            // Unsafe: populate implRef so the unknown-method Reject path can trigger engine close
                            Sync.Unsafe.defer {
                                implRefUnsafe.set(Present(impl))
                                impl
                            }
                        }
                    }
                }
            }
        } // end meterEff.map
    end initEngine

end JsonRpcEndpointImpl
