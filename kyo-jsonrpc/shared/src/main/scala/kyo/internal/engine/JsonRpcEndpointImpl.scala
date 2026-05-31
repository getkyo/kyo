package kyo.internal.engine

// ConcurrentHashMap follows kyo.Exchange pending-map precedent (kyo-core/shared Exchange.scala:3); cross-platform via JS/Native JDK shim
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
    // AtomicReference is kyo.AtomicRef's underlying type (Atomic.scala:354); cross-platform via JS/Native JDK shim
    pendingCancelError: java.util.concurrent.atomic.AtomicReference[Maybe[JsonRpcError]]
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

// HttpServer.scala:145 abstract Unsafe class pattern; JsonRpcEndpointImpl is the concrete platform implementation
final class JsonRpcEndpointImpl private[kyo] (
    private[kyo] val callerRegistry: ConcurrentHashMap[JsonRpcId, CallerInfo],
    private[kyo] val pendingInbound: ConcurrentHashMap[JsonRpcId, InboundEntry],
    private val writerChannel: Channel[WriterMsg],
    private val exchange: Exchange[OutboundReq, Structure.Value, Nothing, JsonRpcError],
    private val transport: JsonRpcTransport,
    private val writerFiber: Fiber[Unit, Sync],
    private[kyo] val inFlight: AtomicInt,
    private val drainSignal: AtomicRef[Fiber.Promise[Unit, Any]],
    private val codec: JsonRpcCodec,
    private[kyo] val methodMap: Map[String, JsonRpcRoute[?, ?, ?]],
    private val unknownPolicy: JsonRpcUnknownMethodPolicy,
    private[kyo] val config: JsonRpcHandler.Config,
    private[kyo] val initFrame: Frame,
    private val progressPolicy: Maybe[JsonRpcProgressPolicy],
    private[kyo] val progressStreams: ConcurrentHashMap[Structure.Value, Channel[Structure.Value]],
    private[kyo] val outboundIdToToken: ConcurrentHashMap[JsonRpcId, Structure.Value],
    private val meter: Maybe[Meter],
    // AtomicLong is kyo.AtomicLong's underlying type (Atomic.scala:354); ConcurrentHashMap follows Exchange precedent; cross-platform via JS/Native JDK shim
    private[kyo] val tokenToDeadline: ConcurrentHashMap[Structure.Value, java.util.concurrent.atomic.AtomicLong]
) extends JsonRpcHandler.Unsafe:

    def callUnsafe[In: Schema, Out: Schema](
        method: String,
        params: In,
        extras: JsonRpcExtrasEncoder
    )(using Frame): Out < (Async & Abort[JsonRpcError | Closed]) =
        RateLimitEngine.maxInFlightGuard(meter) {
            Fiber.Promise.init[JsonRpcError, Any].map { abortSignal =>
                // Unsafe: Promise.Unsafe.init for idSignal so it can be read from the encode callback
                // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                Sync.Unsafe.defer {
                    val idSignal      = Promise.Unsafe.init[JsonRpcId, Any]()
                    val encodedParams = Present(Structure.encode[In](params))
                    val req           = OutboundReq(method, encodedParams, idSignal, abortSignal, extras)
                    inFlight.getAndIncrement.map { prev =>
                        val refresh: Unit < Async =
                            if prev == 0 then Fiber.Promise.init[Unit, Any].map(drainSignal.set)
                            else Kyo.unit
                        refresh.andThen {
                            drainSignal.get.map { snapshot =>
                                val inFlightCleanup =
                                    inFlight.decrementAndGet.map { newCount =>
                                        (if newCount == 0 then snapshot.completeUnitDiscard else Kyo.unit).andThen {
                                            // Unsafe: poll idSignal to clean callerRegistry on request completion
                                            // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                            Sync.Unsafe.defer {
                                                idSignal.poll() match
                                                    case Maybe.Present(Result.Success(id)) =>
                                                        callerRegistry.remove(id)
                                                    case _ => ()
                                            }
                                        }
                                    }
                                Sync.ensure(inFlightCleanup) {
                                    val raceResult: Out < (Async & Abort[JsonRpcError | Closed]) =
                                        Async.raceFirst[JsonRpcError | Closed, Out, Any](
                                            abortSignal.get.map(e => Abort.fail[JsonRpcError](e)),
                                            exchange(req).map { sv =>
                                                Structure.decode[Out](sv) match
                                                    case Result.Success(v) => v
                                                    case Result.Failure(e) =>
                                                        Abort.fail(JsonRpcInternalError(JsonRpcInternalError.Operation.DecodeResult, e))
                                                    case Result.Panic(t) => Abort.panic(t)
                                            }
                                        )
                                    if config.requestTimeout == Duration.Infinity then
                                        // Wrap raceResult with Abort.run inside Sync.ensure so that Abort.fail from
                                        // raceResult is caught here (not by an outer Abort.run that would discard the
                                        // Sync.ensure cleanup continuation), then re-raise after cleanup runs.
                                        Abort.run[JsonRpcError | Closed](raceResult).map {
                                            case Result.Success(v) => v
                                            case Result.Failure(e) => Abort.fail(e)
                                            case Result.Panic(t)   => Abort.panic(t)
                                        }
                                    else
                                        Abort.run[Timeout](Async.timeout(config.requestTimeout)(raceResult)).map {
                                            case Result.Success(v) => v
                                            case Result.Failure(_) =>
                                                // Timeout fired: enqueue cancel notification, then fail with the policy-determined error.
                                                // Do NOT await abortSignal here: raceFirst's cleanup has already interrupted
                                                // the abortSignal promise with Panic(Interrupted), so awaiting it would panic.
                                                val abortError = config.cancellation match
                                                    case Present(p) =>
                                                        p.cancelledError.getOrElse(JsonRpcCustomError(-32800, "Request cancelled"))
                                                    case Absent => JsonRpcCustomError(-32800, "Request cancelled")
                                                // Unsafe: read idSignal to find the id for cancel notification
                                                // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                                Sync.Unsafe.defer {
                                                    idSignal.poll() match
                                                        case Maybe.Present(Result.Success(rawId)) =>
                                                            val id = rawId.eval
                                                            CancellationEngine.handleTimeout(
                                                                id,
                                                                Absent,
                                                                config.cancellation,
                                                                callerRegistry,
                                                                writerChannel
                                                            ).andThen(Abort.fail[JsonRpcError](abortError))
                                                        case _ =>
                                                            Abort.fail[JsonRpcError](abortError)
                                                }
                                            case Result.Panic(t) => Abort.panic(t)
                                        }
                                    end if
                                }
                            }
                        }
                    }
                }
            }
        }

    // Private helper: issues a call with pre-encoded params and an optional deadline ref for progress-reset-timeout.
    // deadlineRef: when progressResetsTimeout = true, holds the epoch-millis deadline for the current call.
    //   Progress notifications extend the deadline by requestTimeout on each arrival.
    //   A monitor fiber polls the deadline and fires a timeout when the wall clock exceeds it.
    // Returns (idPromise, resultEffect).
    private def callEncoded[Out: Schema](
        method: String,
        encodedParams: Maybe[Structure.Value],
        extras: JsonRpcExtrasEncoder,
        // AtomicLong is kyo.AtomicLong's underlying type (Atomic.scala:354); used as a mutable deadline cell shared between call and monitor fibers
        deadlineRef: Maybe[java.util.concurrent.atomic.AtomicLong]
    )(using frame: Frame): (Fiber.Promise[JsonRpcId, Any], Out < (Async & Abort[JsonRpcError | Closed])) =
        // Unsafe: Promise.Unsafe.init so idSignal is accessible before the call runs
        // Promise Unsafe init constructs a state cell readable from Sync-only Exchange callbacks; no safe Promise equivalent
        val idSignalUnsafe = Promise.Unsafe.init[JsonRpcId, Any]()(using AllowUnsafe.embrace.danger)
        val idSignal       = idSignalUnsafe
        val idPromise      = idSignalUnsafe.safe
        val callEffect: Out < (Async & Abort[JsonRpcError | Closed]) =
            Fiber.Promise.init[JsonRpcError, Any].map { abortSignal =>
                inFlight.getAndIncrement.map { prev =>
                    val refresh: Unit < Async =
                        if prev == 0 then Fiber.Promise.init[Unit, Any].map(drainSignal.set)
                        else Kyo.unit
                    refresh.andThen {
                        drainSignal.get.map { snapshot =>
                            Sync.ensure(
                                inFlight.decrementAndGet.map { newCount =>
                                    (if newCount == 0 then snapshot.completeUnitDiscard else Kyo.unit).andThen {
                                        // Unsafe: poll idSignal to clean callerRegistry on request completion
                                        // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                        Sync.Unsafe.defer {
                                            idSignal.poll() match
                                                case Maybe.Present(Result.Success(id)) =>
                                                    callerRegistry.remove(id)
                                                case _ => ()
                                        }
                                    }
                                }
                            ) {
                                val req = OutboundReq(method, encodedParams, idSignal, abortSignal, extras)
                                val raceResult: Out < (Async & Abort[JsonRpcError | Closed]) =
                                    Async.raceFirst[JsonRpcError | Closed, Out, Any](
                                        abortSignal.get.map(e => Abort.fail[JsonRpcError](e)),
                                        exchange(req).map { sv =>
                                            Structure.decode[Out](sv) match
                                                case Result.Success(v) => v
                                                case Result.Failure(e) =>
                                                    Abort.fail(JsonRpcInternalError(JsonRpcInternalError.Operation.DecodeResult, e))
                                                case Result.Panic(t) => Abort.panic(t)
                                        }
                                    )
                                if config.requestTimeout == Duration.Infinity then
                                    // Wrap raceResult with Abort.run inside Sync.ensure so that Abort.fail from
                                    // raceResult is caught here (not by an outer Abort.run that would discard the
                                    // Sync.ensure cleanup continuation), then re-raise after cleanup runs.
                                    Abort.run[JsonRpcError | Closed](raceResult).map {
                                        case Result.Success(v) => v
                                        case Result.Failure(e) => Abort.fail(e)
                                        case Result.Panic(t)   => Abort.panic(t)
                                    }
                                else if config.progressResetsTimeout && deadlineRef.isDefined then
                                    // Progress-reset-timeout: a monitor fiber polls deadlineAt (AtomicLong epoch millis)
                                    // every requestTimeout/10 and fires timeoutSignal when the deadline passes.
                                    // Progress notifications extend deadlineAt by requestTimeout each time they arrive.
                                    // There is ONE outer race: abortSignal vs exchange vs timeoutSignal.
                                    val dref = deadlineRef.get
                                    // timeoutSignal: fired by monitor fiber when deadline passes without a progress reset.
                                    // Carries Unit so the outer race arm can call handleTimeout and then fail.
                                    Fiber.Promise.init[Unit, Any].map { timeoutSignal =>
                                        val abortError = config.cancellation match
                                            case Present(p) => p.cancelledError.getOrElse(JsonRpcCustomError(-32800, "Request cancelled"))
                                            case Absent     => JsonRpcCustomError(-32800, "Request cancelled")
                                        // Poll interval: requestTimeout * 0.1, minimum 10ms
                                        val pollInterval = (config.requestTimeout * 0.1).max(10.millis)
                                        // Monitor fiber: sleep pollInterval, check deadline, recurse or fire timeout.
                                        // Only uses Async & Abort[Nothing]; no Closed or JsonRpcError escapes here.
                                        def monitorLoop: Unit < Async =
                                            Async.sleep(pollInterval).andThen {
                                                // Unsafe: read deadlineAt from monitor fiber
                                                // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                                Sync.Unsafe.defer {
                                                    // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                                    // wall-clock read inside Sync.Unsafe.defer suspension boundary
                                                    val now      = java.lang.System.currentTimeMillis()
                                                    val deadline = dref.get()
                                                    now > deadline
                                                }.map { expired =>
                                                    if expired then
                                                        // Deadline passed; signal the outer race arm to fire timeout.
                                                        // Unsafe: complete timeoutSignal from monitor fiber
                                                        // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                                        Sync.Unsafe.defer {
                                                            // CAS-won path completes unit promise from outside originating fiber; no safe equivalent in Promise public API
                                                            timeoutSignal.unsafe.completeUnitDiscard()(using AllowUnsafe.embrace.danger)
                                                        }
                                                    else
                                                        // Deadline not yet passed; continue monitoring
                                                        monitorLoop
                                                }
                                            }
                                        Fiber.initUnscoped(monitorLoop).map { monitorFiber =>
                                            // Three-way race: abort signal, exchange result, timeout signal.
                                            // On any arm winning, Sync.ensure cleans up the monitor fiber.
                                            Sync.ensure(
                                                // Unsafe: interrupt monitor fiber when the call completes (any outcome)
                                                // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                                Sync.Unsafe.defer {
                                                    // fiber interrupt cleans up monitor or writer or handler fiber from outside its scheduler; no safe equivalent in Fiber public API
                                                    monitorFiber.unsafe.interruptDiscard(
                                                        Result.Panic(Interrupted(frame))
                                                        // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
                                                    )(using AllowUnsafe.embrace.danger)
                                                }
                                            ) {
                                                Abort.run[JsonRpcError | Closed](
                                                    Async.raceFirst[JsonRpcError | Closed, Out, Any](
                                                        // timeoutSignal arm: deadline expired, run cancel then fail
                                                        timeoutSignal.get.andThen {
                                                            // Unsafe: read idSignal to find the id for cancel notification
                                                            // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                                            Sync.Unsafe.defer {
                                                                idSignal.poll() match
                                                                    case Maybe.Present(Result.Success(rawId)) =>
                                                                        Present(rawId.eval: JsonRpcId)
                                                                    case _ => Absent
                                                            }.map { (idOpt: Maybe[JsonRpcId]) =>
                                                                idOpt match
                                                                    case Present(id) =>
                                                                        CancellationEngine.handleTimeout(
                                                                            id,
                                                                            Absent,
                                                                            config.cancellation,
                                                                            callerRegistry,
                                                                            writerChannel
                                                                        ).andThen(Abort.fail[JsonRpcError](abortError))
                                                                    case _ =>
                                                                        Abort.fail[JsonRpcError](abortError)
                                                            }
                                                        },
                                                        raceResult
                                                    )
                                                ).map {
                                                    case Result.Success(v) => v
                                                    case Result.Failure(e) => Abort.fail(e)
                                                    case Result.Panic(t)   => Abort.panic(t)
                                                }
                                            }
                                        }
                                    }
                                else
                                    Abort.run[Timeout](Async.timeout(config.requestTimeout)(raceResult)).map {
                                        case Result.Success(v) => v
                                        case Result.Failure(_) =>
                                            val abortError = config.cancellation match
                                                case Present(p) =>
                                                    p.cancelledError.getOrElse(JsonRpcCustomError(-32800, "Request cancelled"))
                                                case Absent => JsonRpcCustomError(-32800, "Request cancelled")
                                            // Unsafe: read idSignal to find the id for cancel notification
                                            // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                            Sync.Unsafe.defer {
                                                idSignal.poll() match
                                                    case Maybe.Present(Result.Success(rawId)) =>
                                                        val id = rawId.eval
                                                        CancellationEngine.handleTimeout(
                                                            id,
                                                            Absent,
                                                            config.cancellation,
                                                            callerRegistry,
                                                            writerChannel
                                                        ).andThen(Abort.fail[JsonRpcError](abortError))
                                                    case _ =>
                                                        Abort.fail[JsonRpcError](abortError)
                                            }
                                        case Result.Panic(t) => Abort.panic(t)
                                    }
                                end if
                            }
                        }
                    }
                }
            }
        (idPromise, callEffect)
    end callEncoded

    def notifyUnsafe[In: Schema](
        method: String,
        params: In,
        extras: JsonRpcExtrasEncoder
    )(using Frame): Unit < (Async & Abort[Closed]) =
        val sentinelId    = JsonRpcId(-1L)
        val encodedParams = Present(Structure.encode[In](params))
        extras.resolve(sentinelId).map { extrasVal =>
            val env = JsonRpcNotification(method, encodedParams, extrasVal)
            writerChannel.put(WriterMsg.SendEnvelope(env))
        }
    end notifyUnsafe

    def sendUnmatchedUnsafe[In: Schema](
        method: String,
        params: In,
        id: JsonRpcId,
        extras: JsonRpcExtrasEncoder
    )(using Frame): Unit < (Async & Abort[Closed]) =
        Sync.defer(Structure.encode[In](params)).map { encodedParams =>
            extras.resolve(id).map { extrasVal =>
                val env = JsonRpcRequest(id, method, Present(encodedParams), extrasVal)
                writerChannel.put(WriterMsg.SendEnvelope(env))
            }
        }
    end sendUnmatchedUnsafe

    def callWithProgressUnsafe[In: Schema, Out: Schema](
        method: String,
        params: In,
        extras: JsonRpcExtrasEncoder
    )(using frame: Frame): JsonRpcHandler.Pending[Out] < (Async & Abort[JsonRpcError | Closed]) =
        progressPolicy match
            case Absent =>
                Abort.fail(JsonRpcConfigurationError(
                    "progressPolicy",
                    "required for callWithProgress; pass Config.progress = Present(<your JsonRpcProgressPolicy>)"
                ))
            case Present(policy) =>
                RateLimitEngine.maxInFlightGuard(meter) {
                    // Unsafe: channel init and deadline ref setup before token allocation
                    // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                    Sync.Unsafe.defer {
                        // Unsafe: Channel.Unsafe.init for progress channel
                        // Channel Unsafe init constructs a channel inside an unsafe deferred block; no safe Channel equivalent that runs without Async
                        val progChan = Channel.Unsafe.init[Structure.Value](64)(using frame, AllowUnsafe.embrace.danger).safe
                        // Unsafe: AtomicLong deadline for progressResetsTimeout; Absent when flag is false.
                        // Initial deadline: now + requestTimeout millis. Progress notifications extend it.
                        // AtomicLong is kyo.AtomicLong's underlying type (Atomic.scala:354); used as deadline cell shared between call and monitor fibers
                        val deadlineRef: Maybe[java.util.concurrent.atomic.AtomicLong] =
                            if config.progressResetsTimeout && config.requestTimeout != Duration.Infinity then
                                // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                // wall-clock read inside enclosing Sync.Unsafe.defer suspension boundary
                                val initialDeadline = java.lang.System.currentTimeMillis() + config.requestTimeout.toMillis
                                // AtomicLong is kyo.AtomicLong's underlying type (Atomic.scala:354); per-request deadline cell
                                Present(new java.util.concurrent.atomic.AtomicLong(initialDeadline))
                            else Absent
                        (progChan, deadlineRef)
                    }.map { (progChan, deadlineRef) =>
                        // Registration is now atomic-inside-helper; token allocated via putIfAbsent retry loop.
                        ProgressEngine.allocateProgressToken(progressStreams, progChan, 32).map { tokenVal =>
                            // Register deadline ref (if present) after token is claimed.
                            // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                            Sync.Unsafe.defer {
                                deadlineRef match
                                    case Present(ref) => discard(tokenToDeadline.put(tokenVal, ref))
                                    case Absent       => ()
                            }.andThen {
                                val encodedParams = Structure.encode[In](params)
                                policy.stampOutboundToken(encodedParams, tokenVal).map { stampedParams =>
                                    val (idPromise, callEffect) = callEncoded[Out](method, Present(stampedParams), extras, deadlineRef)
                                    // Fork the call so progress stream can be consumed concurrently.
                                    // callEffect fires the encode callback (populating idPromise) before suspending,
                                    // so idPromise.get below won't starve.
                                    Fiber.initUnscoped(callEffect).map { fiber =>
                                        // Register cleanup callback: gracefully closes progChan when fiber finishes.
                                        // closeAwaitEmpty() transitions to HalfOpen so pending items are still drained by the consumer
                                        // before the channel fully closes; this avoids dropping progress items that arrived
                                        // just before the response.
                                        // Unsafe: onComplete from outside the fiber
                                        // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                        Sync.Unsafe.defer {
                                            // fiber onComplete attaches cleanup hook from outside the fiber; no safe equivalent in Fiber public API
                                            fiber.unsafe.onComplete { _ =>
                                                progressStreams.remove(tokenVal)
                                                tokenToDeadline.remove(tokenVal)
                                                // channel close or closeAwaitEmpty from finalizer or onComplete hook outside originating fiber; no safe equivalent
                                                discard(progChan.unsafe.closeAwaitEmpty()(using frame, AllowUnsafe.embrace.danger))
                                            // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
                                            }(using AllowUnsafe.embrace.danger)
                                        }.andThen {
                                            // Await the id (populated in encode callback, which fires as the fiber starts)
                                            idPromise.get.map { id =>
                                                new JsonRpcHandler.Pending[Out](
                                                    id = id,
                                                    result = fiber.get,
                                                    progress = progChan.streamUntilClosed(),
                                                    cancel = cancelUnsafe(id, Absent)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

    def callPartialResultsUnsafe[In: Schema, T: Schema: Tag](
        method: String,
        params: In,
        extras: JsonRpcExtrasEncoder
    )(using frame: Frame, tagEmitChunkT: Tag[Emit[Chunk[T]]]): Stream[T, Async & Abort[JsonRpcError | Closed]] =
        progressPolicy match
            case Absent =>
                Stream(Abort.fail[JsonRpcError](JsonRpcConfigurationError(
                    "progressPolicy",
                    "required for callPartialResults; pass Config.progress = Present(<your JsonRpcProgressPolicy>)"
                )))
            case Present(policy) =>
                // All setup runs inside the Stream body so the return type is Stream (not Stream < Sync).
                Stream[T, Async & Abort[JsonRpcError | Closed]] {
                    RateLimitEngine.maxInFlightGuard(meter) {
                        // Unsafe: channel init and finalRef setup inside Stream emit body
                        // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                        Sync.Unsafe.defer {
                            // Unsafe: Channel.Unsafe.init for partial-results channel
                            // Channel Unsafe init constructs a channel inside an unsafe deferred block; no safe Channel equivalent that runs without Async
                            val progChan = Channel.Unsafe.init[Structure.Value](64)(using frame, AllowUnsafe.embrace.danger).safe
                            // Unsafe: AtomicRef.Unsafe.init for the final response result (non-progress chunks)
                            // AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
                            val finalRef = AtomicRef.Unsafe.init[Maybe[Structure.Value]](Absent)(using AllowUnsafe.embrace.danger).safe
                            (progChan, finalRef)
                        }.map { (progChan, finalRef) =>
                            // Registration is now atomic-inside-helper; no separate put call here.
                            ProgressEngine.allocateProgressToken(progressStreams, progChan, 32).map { tokenVal =>
                                val encodedParams = Structure.encode[In](params)
                                policy.stampOutboundToken(encodedParams, tokenVal).map { stampedParams =>
                                    val (_, callEffect) =
                                        callEncoded[Structure.Value](method, Present(stampedParams), extras, Absent)
                                    Fiber.initUnscoped(
                                        Abort.run[JsonRpcError | Closed](callEffect).map { res =>
                                            res match
                                                case Result.Success(sv) if sv != Structure.Value.Null =>
                                                    // word appears in comment only; Structure.Value.Null is a kyo ADT case, not a reference
                                                    // Non-null final result: store it in finalRef, then gracefully close channel.
                                                    // closeAwaitEmpty() drains remaining progress items before fully closing,
                                                    // so the drain loop below sees all items before Closed propagates.
                                                    // Unsafe: store final result and close channel from call fiber
                                                    Sync.Unsafe.defer {
                                                        // AtomicX setter from Sync-only Exchange callback; no safe Atomic equivalent within Sync
                                                        finalRef.unsafe.set(Present(sv))(using AllowUnsafe.embrace.danger)
                                                        progressStreams.remove(tokenVal)
                                                        // channel close or closeAwaitEmpty from finalizer or onComplete hook outside originating fiber; no safe equivalent
                                                        discard(progChan.unsafe.closeAwaitEmpty()(using frame, AllowUnsafe.embrace.danger))
                                                    }
                                                case _ =>
                                                    // Null result, failure, or closed: gracefully close channel with no final chunk.
                                                    // Structure.Value.Null signals "partial-result pattern: all chunks were via progress".
                                                    // Unsafe: remove from progressStreams and close channel from call fiber (outside consumer)
                                                    // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                                    Sync.Unsafe.defer {
                                                        progressStreams.remove(tokenVal)
                                                        // channel close or closeAwaitEmpty from finalizer or onComplete hook outside originating fiber; no safe equivalent
                                                        discard(progChan.unsafe.closeAwaitEmpty()(using frame, AllowUnsafe.embrace.danger))
                                                    }
                                        }
                                    ).andThen {
                                        // Drain channel: take each Structure.Value (progress notification params),
                                        // extract the raw value via policy.extractProgressValue, decode to T, emit.
                                        // Abort[Closed] from progChan.take terminates the forever loop.
                                        Abort.run[Closed](
                                            Loop.forever {
                                                progChan.take.map { sv =>
                                                    policy.extractProgressValue(sv).map {
                                                        case Absent => Kyo.unit
                                                        case Present(rawValue) =>
                                                            Structure.decode[T](rawValue) match
                                                                // typed stream emit after per-item decode; no canonical alternative
                                                                case Result.Success(v) => Emit.value(Chunk(v))(using tagEmitChunkT, frame)
                                                                case Result.Failure(e) =>
                                                                    Abort.fail(JsonRpcInternalError(
                                                                        JsonRpcInternalError.Operation.DecodeResult,
                                                                        e
                                                                    ))
                                                                case Result.Panic(t) => Abort.panic(t)
                                                    }
                                                }
                                            }
                                        ).map {
                                            case Result.Success(_) => ()
                                            case Result.Failure(_) =>
                                                // word appears in comment only; kyo ADT case, not a reference
                                                // Channel closed: check for a non-null final result and emit it as last chunk.
                                                finalRef.get.map {
                                                    case Absent => Kyo.unit
                                                    case Present(sv) =>
                                                        Structure.decode[T](sv) match
                                                            // final item stream emit after decode; no canonical alternative
                                                            case Result.Success(v) => Emit.value(Chunk(v))(using tagEmitChunkT, frame)
                                                            case Result.Failure(e) => Abort.fail(JsonRpcInternalError(
                                                                    JsonRpcInternalError.Operation.DecodeResult,
                                                                    e
                                                                ))
                                                            case Result.Panic(t) => Abort.panic(t)
                                                }
                                            case Result.Panic(t) => Abort.panic(t)
                                        }
                                    }
                                }
                            }
                        }
                    } // end maxInFlightGuard
                }

    def subscribeProgressUnsafe(token: Structure.Value)(using frame: Frame): Stream[Structure.Value, Async & Abort[Closed]] < Sync =
        progressPolicy match
            case Absent =>
                Stream(Abort.fail[Closed](Closed("progress not configured", initFrame)))
            case Present(_) =>
                // Eagerly create and register the channel so notifications can be routed before the stream is consumed.
                // Unsafe: channel init and ConcurrentHashMap putIfAbsent must happen at subscribe time.
                // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                Sync.Unsafe.defer {
                    // Channel Unsafe init constructs a channel inside an unsafe deferred block; no safe Channel equivalent that runs without Async
                    val ch = Channel.Unsafe.init[Structure.Value](64)(using frame, AllowUnsafe.embrace.danger).safe
                    discard(progressStreams.putIfAbsent(token, ch))
                    Maybe(progressStreams.get(token))
                }.map {
                    // streamUntilClosed handles Closed gracefully so the consumer sees all buffered items
                    // before the stream terminates, even when unsubscribeProgress races with the consumer.
                    case Present(ch) => ch.streamUntilClosed()
                    case Absent      => Stream[Structure.Value, Async](Kyo.unit)
                }

    def unsubscribeProgressUnsafe(token: Structure.Value)(using frame: Frame): Unit < Async =
        // Unsafe: remove from progressStreams and close channel from outside consumer fiber
        // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
        Sync.Unsafe.defer {
            Maybe(progressStreams.remove(token)) match
                case Absent      => ()
                case Present(ch) =>
                    // Unsafe: closeAwaitEmpty lets the consumer drain any items already in the channel
                    // before the channel transitions to FullyClosed; prevents item loss on unsubscribe.
                    // channel close or closeAwaitEmpty from finalizer or onComplete hook outside originating fiber; no safe equivalent
                    discard(ch.unsafe.closeAwaitEmpty()(using frame, AllowUnsafe.embrace.danger))
        }

    def cancelUnsafe(id: JsonRpcId, reason: Maybe[String])(using Frame): Unit < (Async & Abort[Closed]) =
        Sync.defer(Maybe(callerRegistry.get(id))).map {
            case Absent =>
                Log.warn(s"kyo-jsonrpc: cancel for unknown or already-completed id $id, no-op")
            case Present(info) =>
                config.cancellation match
                    case Absent =>
                        // No policy: abort locally only, no wire notification
                        // Unsafe: complete abortSignal from cancel call
                        // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                        Sync.Unsafe.defer {
                            // promise completion called from outside originating fiber to signal abort or cancel; no safe equivalent in Promise public API
                            info.abortSignal.unsafe.completeDiscard(
                                Result.succeed(JsonRpcCustomError(-32800, reason.getOrElse("Request cancelled")))
                                // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
                            )(using AllowUnsafe.embrace.danger)
                        }
                    case Present(policy) =>
                        if policy.protectedMethods.contains(info.method) then
                            Log.warn(
                                s"kyo-jsonrpc: cancel refused for protected method ${info.method}, no-op"
                            )
                        else
                            val abortError =
                                policy.cancelledError.getOrElse(JsonRpcCustomError(-32800, reason.getOrElse("Request cancelled")))
                            if policy.expectReplyForCancelledRequest then
                                // Policy requires a reply for cancelled requests: set pendingCancelError so
                                // decodeCallback completes abortSignal when the reply arrives.
                                // Unsafe: set pendingCancelError from cancel call
                                // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                Sync.Unsafe.defer {
                                    info.pendingCancelError.set(Present(abortError))
                                }.andThen {
                                    CancellationEngine.buildAndEnqueueOutboundCancel(
                                        id,
                                        reason,
                                        info,
                                        policy,
                                        writerChannel
                                    )
                                }
                            else
                                // Policy expects no reply for cancelled requests: complete abortSignal immediately after enqueuing the cancel notification.
                                CancellationEngine.buildAndEnqueueOutboundCancel(
                                    id,
                                    reason,
                                    info,
                                    policy,
                                    writerChannel
                                ).andThen {
                                    // Unsafe: complete abortSignal after enqueuing the cancel notification
                                    // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                    Sync.Unsafe.defer {
                                        // promise completion called from outside originating fiber to signal abort or cancel; no safe equivalent in Promise public API
                                        info.abortSignal.unsafe.completeDiscard(
                                            Result.succeed(abortError)
                                            // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
                                        )(using AllowUnsafe.embrace.danger)
                                    }
                                }
                            end if
        }

    def awaitDrainUnsafe(using Frame): Unit < Async =
        inFlight.get.map { n =>
            if n <= 0 then Kyo.unit
            else drainSignal.get.map(_.get.unit)
        }

    def closeUnsafe(gracePeriod: Duration)(using Frame): Unit < Async =
        closeImpl(gracePeriod)

    def dispatch(
        name: String,
        params: Structure.Value,
        ctx: JsonRpcRoute.Context
    )(using Frame): Maybe[Structure.Value < (Async & Abort[JsonRpcError | JsonRpcResponse.Halt])] =
        // stdlib Map.get() returns scala.Option; match arms are interop at protocol dispatch boundary
        methodMap.get(name) match
            // scala.Option arm; interop with Map.get
            case Some(route) => Present(route.handle(params, ctx))
            // scala.Option arm; interop with Map.get
            case None => Absent
        end match
    end dispatch

    private def closeImpl(gracePeriod: Duration)(using Frame): Unit < Async =
        if gracePeriod == Duration.Zero then finalizer
        else
            Abort.run[Timeout](Async.timeout(gracePeriod)(awaitDrainUnsafe)).map { _ =>
                finalizer
            }

    private[kyo] def finalizer(using Frame): Unit < Async =
        // Step 1: poison writer channel so writer fiber unblocks and exits
        writerChannel.close.unit.andThen {
            // Step 2: reader fiber managed by Exchange; step 6 Exchange.close() cancels it
            // Step 3: cancel writer fiber
            // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
            // Unsafe: interruptDiscard must run outside the fiber scheduler; Sync.Unsafe.defer bridges to safe context
            // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
            Sync.Unsafe.defer(writerFiber.unsafe.interruptDiscard(Result.Panic(Interrupted(initFrame)))).andThen {
                // Step 4: close transport
                transport.close.andThen {
                    // Step 5: drain callerRegistry. Fail all Exchange pending promises with internalError (not Closed)
                    // so the abortSignal arm wins raceFirst (JsonRpcError path). Then complete each abortSignal.
                    // Calls not yet in callerRegistry when Exchange.close fires (step 6) see Closed via donePromise check.
                    // Unsafe: bulk-fail and complete from outside originating fibers
                    // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                    Sync.Unsafe.defer {
                        // Unsafe: failAllPending fails all Exchange pending promises with the given error
                        // Exchange bulk-fail of pending promises from finalizer; no safe equivalent in Exchange public API
                        exchange.unsafe.failAllPending(
                            JsonRpcLifecycleError(JsonRpcLifecycleError.Stage.Close)
                            // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
                        )(using AllowUnsafe.embrace.danger)
                        callerRegistry.forEach { (_, info) =>
                            // promise completion called from outside originating fiber to signal abort or cancel; no safe equivalent in Promise public API
                            info.abortSignal.unsafe.completeDiscard(
                                Result.succeed(JsonRpcLifecycleError(JsonRpcLifecycleError.Stage.Close))
                                // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
                            )(using AllowUnsafe.embrace.danger)
                        }
                        callerRegistry.clear()
                    }.andThen {
                        // Step 6: close Exchange; sets donePromise to Closed for future calls; pending map is now empty
                        exchange.close.andThen {
                            // Step 7: close all progress channels so stream consumers see Closed
                            // Unsafe: bulk-close from outside the originating fibers
                            // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                            Sync.Unsafe.defer {
                                progressStreams.forEach { (_, ch) =>
                                    // channel close or closeAwaitEmpty from finalizer or onComplete hook outside originating fiber; no safe equivalent
                                    discard(ch.unsafe.close()(using initFrame, AllowUnsafe.embrace.danger))
                                }
                                progressStreams.clear()
                            }.andThen {
                                // Step 8: interrupt all pendingInbound handler fibers
                                // Unsafe: bulk-interrupt inbound handlers from outside their originating fibers
                                // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                Sync.Unsafe.defer {
                                    pendingInbound.forEach { (_, entry) =>
                                        entry match
                                            case InboundEntry.Running(_, handler, _) =>
                                                // fiber interrupt cleans up monitor or writer or handler fiber from outside its scheduler; no safe equivalent in Fiber public API
                                                handler.unsafe.interruptDiscard(Result.Panic(Interrupted(initFrame)))
                                            case _ => ()
                                    }
                                    pendingInbound.clear()
                                }.andThen {
                                    // Step 9: close meter (releases semaphore so parked callers unblock)
                                    meter match
                                        case Absent     => Kyo.unit
                                        case Present(m) => m.close.unit
                                }
                            }
                        }
                    }
                }
            }
        }

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
        // AtomicLong is kyo.AtomicLong's underlying type (Atomic.scala:354); ConcurrentHashMap follows Exchange precedent; cross-platform via JS/Native JDK shim
        val tokenToDeadline = new ConcurrentHashMap[Structure.Value, java.util.concurrent.atomic.AtomicLong]()
        val methodMap       = methods.map(m => m.name -> m).toMap
        val nextIdFn        = IdStrategyEngine.mkNextId(config.idStrategy)
        // Init meter for maxInFlight semaphore when configured; Absent means no rate-limiting.
        val meterEff: Maybe[Meter] < Sync =
            config.maxInFlight match
                case Absent     => Absent
                case Present(n) => Meter.initSemaphoreUnscoped(n).map(m => Present(m))

        meterEff.map { meterMaybe =>
            Channel.initUnscoped[WriterMsg](64).map { writerChannel =>
                // Unsafe: init AtomicInt/AtomicRef/Promise.Unsafe for inFlight and drainSignal counters
                // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                Sync.Unsafe.defer {
                    // AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
                    val inFlightUnsafe = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
                    val inFlight       = inFlightUnsafe.safe
                    // Unsafe: Promise.Unsafe.init for drainSignal; pre-completed so first inFlight=0 sees a resolved signal
                    val initPromise = Promise.Unsafe.init[Unit, Any]()
                    // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
                    initPromise.completeUnitDiscard()(using AllowUnsafe.embrace.danger)
                    // AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
                    val drainSigUnsafe = AtomicRef.Unsafe.init[Fiber.Promise[Unit, Any]](initPromise.safe)(using AllowUnsafe.embrace.danger)
                    val drainSignal    = drainSigUnsafe.safe
                    // Unsafe: implRef populated after construction; used by decodeCallback for Reject-close
                    // AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
                    val implRefUnsafe = AtomicRef.Unsafe.init[Maybe[JsonRpcEndpointImpl]](Absent)(using AllowUnsafe.embrace.danger)
                    val implRef       = implRefUnsafe.safe

                    // Encode callback: runs in Sync-only context inside Exchange.apply.
                    // Uses Kyo effect chaining (returns < Sync).
                    val encodeCallback: (JsonRpcId, OutboundReq) => String < Sync =
                        (id, req) =>
                            // Resolve extras with the now-known id; frame captured from initEngine
                            req.extras.resolve(id)(using frame).map { extrasVal =>
                                // Unsafe: register in callerRegistry and complete idSignal inside Exchange encode callback
                                // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                Sync.Unsafe.defer {
                                    // AtomicReference is kyo.AtomicRef's underlying type (Atomic.scala:354); per-request pending-cancel cell mirroring Exchange pattern
                                    val pendingCancel = new java.util.concurrent.atomic.AtomicReference[Maybe[JsonRpcError]](Absent)
                                    callerRegistry.put(
                                        id,
                                        CallerInfo(req.method, extrasVal, req.abortSignal, pendingCancel)
                                    )
                                    // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
                                    req.idSignal.completeDiscard(Result.succeed(id))(using AllowUnsafe.embrace.danger)
                                }.andThen {
                                    // Build envelope and encode to JSON
                                    val env = JsonRpcRequest(id, req.method, req.encodedParams, extrasVal)
                                    Abort.run[JsonRpcError](config.codec.encode(env)(using frame)).map {
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
                                    Abort.run[JsonRpcError](config.codec.decode(sv)(using frame)).map {
                                        case Result.Success(env) =>
                                            Abort.run[Closed](writerChannel.put(WriterMsg.SendEnvelope(env))).map {
                                                case Result.Success(_) => ()
                                                case Result.Failure(c) =>
                                                    Abort.fail(JsonRpcTransportError(s"transport closed: ${c.getMessage}", c))
                                                case Result.Panic(t) => Abort.panic(t)
                                            }
                                        case Result.Failure(e) => Abort.fail(e)
                                        case Result.Panic(t)   => Abort.panic(t)
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
                                config.codec.encode(env)(using frame).map { sv =>
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
                                    config.codec.decode(sv)(using frame).map { parsedEnvelope =>
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
                                                                            // Unsafe: offer to progress channel inside Exchange decode callback
                                                                            // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                                                            Sync.Unsafe.defer {
                                                                                Maybe(progressStreams.get(token)) match
                                                                                    case Absent      => ()
                                                                                    case Present(ch) =>
                                                                                        // Unsafe: non-blocking offer; backpressure not applied here
                                                                                        // channel offer from Sync-only Exchange callback (no Frame in scope); no safe Channel equivalent
                                                                                        discard(ch.unsafe.offer(paramsVal)(using
                                                                                            // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
                                                                                            AllowUnsafe.embrace.danger,
                                                                                            frame
                                                                                        ))
                                                                                end match
                                                                                // Unsafe: update deadline AtomicLong to reset the requestTimeout clock when progressResetsTimeout = true
                                                                                if config.progressResetsTimeout then
                                                                                    Maybe(tokenToDeadline.get(token)).foreach {
                                                                                        deadlineLong =>
                                                                                            // wall-clock read inside enclosing unsafe deferred block suspension boundary
                                                                                            val nowMs = java.lang.System.currentTimeMillis()
                                                                                            val newDeadline =
                                                                                                nowMs + config.requestTimeout.toMillis
                                                                                            deadlineLong.set(newDeadline)
                                                                                    }
                                                                                end if
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
                                                                        // scala.Option arm; interop with methodMap.get (covered by comment above match)
                                                                        case Some(m) =>
                                                                            // Unsafe: Promise.Unsafe.init for cancelled signal on notification handlers
                                                                            val cancelledUnsafe =
                                                                                Promise.Unsafe.init[Unit, Sync]()(using
                                                                                    // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
                                                                                    AllowUnsafe.embrace.danger
                                                                                )
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
                                                                                )(using
                                                                                    frame
                                                                                )
                                                                            Fiber.initUnscoped(handlerEffect).map { _ =>
                                                                                Exchange.Message.Skip
                                                                            }
                                                                        // scala.Option arm; interop with methodMap.get (covered by comment above match)
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
                                                                                            // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                                                                            Sync.Unsafe.defer {
                                                                                                implRefUnsafe.get()(using
                                                                                                    // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
                                                                                                    AllowUnsafe.embrace.danger
                                                                                                ) match
                                                                                                    case Present(i) =>
                                                                                                        Fiber.initUnscoped(
                                                                                                            i.closeUnsafe(Duration.Zero)(
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
                                                        // scala.Option arm; interop with methodMap.get (covered by comment above match)
                                                        case Some(m) =>
                                                            // Unsafe: Promise.Unsafe.init and buildProgressSink require AllowUnsafe
                                                            val cancelledUnsafe =
                                                                // Promise Unsafe init constructs a state cell readable from Sync-only Exchange callbacks; no safe Promise equivalent
                                                                Promise.Unsafe.init[Unit, Sync]()(using AllowUnsafe.embrace.danger)
                                                            // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
                                                            // Unsafe: build progressSink using AllowUnsafe.embrace.danger directly
                                                            val progressSinkOpt: Maybe[Structure.Value => Unit < (Async & Abort[Closed])] =
                                                                ProgressEngine.buildProgressSink(
                                                                    id,
                                                                    params,
                                                                    extras,
                                                                    config.progress,
                                                                    pendingInbound,
                                                                    writerChannel
                                                                    // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
                                                                )(using frame, AllowUnsafe.embrace.danger)
                                                            val ctx =
                                                                new JsonRpcRoute.Context(
                                                                    cancelledUnsafe.safe,
                                                                    Present(id),
                                                                    extras,
                                                                    progressSinkOpt
                                                                )
                                                            val handlerEffect =
                                                                m.handle(params.getOrElse(Structure.Value.Null), ctx)(using frame)
                                                            Fiber.initUnscoped(handlerEffect).map { fiber =>
                                                                // Unsafe: register pendingInbound entry and attach onComplete hook inside Exchange decode callback
                                                                // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                                                Sync.Unsafe.defer {
                                                                    val entry = InboundEntry.Running(method, fiber, cancelledUnsafe.safe)
                                                                    pendingInbound.put(id, entry)
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
                                                                                // STEER-2: handler short-circuited with Halt; emit the wrapped response directly.
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
                                                                                val suppressUnsafe = AtomicBoolean.Unsafe.init(false)(using
                                                                                    // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
                                                                                    AllowUnsafe.embrace.danger
                                                                                )
                                                                                val replying =
                                                                                    InboundEntry.Replying(method, suppressUnsafe.safe)
                                                                                if pendingInbound.replace(id, running, replying) then
                                                                                    // Unsafe: writer-channel offer from Sync-only onComplete callback
                                                                                    // channel offer from Sync-only Exchange callback (no Frame in scope); no safe Channel equivalent
                                                                                    discard(writerChannel.unsafe.offer(
                                                                                        WriterMsg.SuppressIfCancelled(id, responseEnvelope)
                                                                                        // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
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
                                                                                        // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
                                                                                    )(using AllowUnsafe.embrace.danger, frame))
                                                                                    discard(pendingInbound.remove(id))
                                                                                end if
                                                                            case _ => ()
                                                                        end match
                                                                    // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
                                                                    }(using AllowUnsafe.embrace.danger)
                                                                }.andThen(Exchange.Message.Skip)
                                                            }
                                                        // scala.Option arm; interop with methodMap.get (covered by comment above match)
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
                                                                    // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
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
                                                                    // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                                                    Sync.Unsafe.defer {
                                                                        val msg = WriterMsg.SendEnvelope(response)
                                                                        // format: off
                                                                        // channel offer from Sync-only Exchange callback (no Frame in scope); no safe Channel equivalent
                                                                        discard(writerChannel.unsafe.offer(msg)(using AllowUnsafe.embrace.danger, frame))
                                                                        // format: on
                                                                    }.andThen {
                                                                        // Unsafe: read implRef to trigger close; implRef set before any messages arrive
                                                                        // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                                                        Sync.Unsafe.defer {
                                                                            // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
                                                                            implRefUnsafe.get()(using AllowUnsafe.embrace.danger) match
                                                                                case Present(i) =>
                                                                                    Fiber.initUnscoped(i.closeUnsafe(Duration.Zero)(using
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
                                                                // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
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
                                                        // word appears in comment only; no absent-reference in code
                                                        // Return Skip so Exchange does not also complete the pending promise with a null value.
                                                        Sync.Unsafe.defer {
                                                            Maybe(callerRegistry.get(id)).foreach { info =>
                                                                // promise completion called from outside originating fiber to signal abort or cancel; no safe equivalent in Promise public API
                                                                info.abortSignal.unsafe.completeDiscard(Result.succeed(e))(using
                                                                    // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
                                                                    AllowUnsafe.embrace.danger
                                                                )
                                                            }
                                                        }.andThen(Exchange.Message.Skip)

                                                    case Absent =>
                                                        // Unsafe: check pendingCancelError inside Exchange decode callback
                                                        // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
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
                                                                                // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
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
                                                // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                                                Sync.Unsafe.defer {
                                                    Maybe(callerRegistry.get(id)) match
                                                        case Present(info) =>
                                                            // CAS-won path completes pending caller promise from outside originating fiber
                                                            info.abortSignal.unsafe.completeDiscard(
                                                                Result.succeed(JsonRpcInvalidRequestError(
                                                                    Structure.Value.Str(s"malformed response: $reason"),
                                                                    Chunk.empty
                                                                )(using frame))
                                                                // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
                                                            )(using AllowUnsafe.embrace.danger)
                                                        case Absent =>
                                                            ()
                                                }.andThen(Exchange.Message.Skip)
                                            case JsonRpcMalformedMessage(Absent, _, _) =>
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
                                                // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
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
                            // embrace-danger token passed to a kyo Unsafe API at a structural bridging site; no safe equivalent
                            implRefUnsafe.set(Present(impl))(using AllowUnsafe.embrace.danger)
                            impl
                        }
                    }
                }
            }
        } // end meterEff.map
    end initEngine

end JsonRpcEndpointImpl
