package kyo.internal.engine

// ConcurrentHashMap shared concurrent map; cross-platform via JS/Native JDK shim
import java.util.concurrent.ConcurrentHashMap
import kyo.*

private[kyo] object CallEngine:

    // refresh: when this call is the first in flight (prev == 0), install a fresh drain signal.
    private def refresh(
        prev: Int,
        drainSignal: AtomicRef[Fiber.Promise[Unit, Any]]
    )(using Frame): Unit < Async =
        if prev == 0 then Fiber.Promise.init[Unit, Any].map(drainSignal.set)
        else Kyo.unit

    // inFlightCleanup (callEffect variant): decrement in-flight, complete the CURRENT drain signal when
    // reaching zero, and poll idSignal to clean callerRegistry on request completion.
    //
    // The completion reads drainSignal fresh (drainSignal.get) rather than a snapshot captured at call start.
    // A concurrent call's `getAndIncrement` and its `refresh` are not atomic, so two calls starting together
    // can capture different drain promises (the second may capture the pre-refresh promise). Completing a stale
    // captured snapshot would complete a promise no waiter holds, leaving awaitDrain (which reads drainSignal
    // fresh) parked forever on the promise that was actually installed. Reading drainSignal at the moment
    // inFlight hits zero completes exactly the promise active for the just-drained period, which is the one
    // awaitDrain observes. A subsequent 0->1 refresh can only run after this decrement, so it cannot install a
    // newer promise before this completion.
    private def callEffectInFlightCleanup(
        inFlight: AtomicInt,
        drainSignal: AtomicRef[Fiber.Promise[Unit, Any]],
        idSignal: Promise.Unsafe[JsonRpcId, Any],
        callerRegistry: ConcurrentHashMap[JsonRpcId, CallerInfo]
    )(using Frame): Unit < Sync =
        inFlight.decrementAndGet.map { newCount =>
            (if newCount == 0 then drainSignal.get.map(_.completeUnitDiscard) else Kyo.unit).andThen {
                // Unsafe: poll idSignal to clean callerRegistry on request completion
                Sync.Unsafe.defer {
                    idSignal.poll() match
                        case Maybe.Present(Result.Success(id)) =>
                            discard(callerRegistry.remove(id))
                        case _ => ()
                }
            }
        }

    // raceResult: race the abort signal against the exchange round-trip, decoding the result to Out.
    private def raceResult[Out: Schema](
        abortSignal: Fiber.Promise[JsonRpcError, Any],
        req: OutboundReq,
        exchange: Exchange[OutboundReq, Structure.Value, Nothing, JsonRpcError]
    )(using Frame): Out < (Async & Abort[JsonRpcError | Closed]) =
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

    def callEffect[In: Schema, Out: Schema](
        method: String,
        params: In,
        extras: JsonRpcExtrasEncoder,
        meter: Maybe[Meter],
        inFlight: AtomicInt,
        drainSignal: AtomicRef[Fiber.Promise[Unit, Any]],
        callerRegistry: ConcurrentHashMap[JsonRpcId, CallerInfo],
        writerChannel: Channel[WriterMsg],
        exchange: Exchange[OutboundReq, Structure.Value, Nothing, JsonRpcError],
        config: JsonRpcHandler.Config
    )(using Frame): Out < (Async & Abort[JsonRpcError | Closed]) =
        RateLimitEngine.maxInFlightGuard(meter) {
            Fiber.Promise.init[JsonRpcError, Any].map { abortSignal =>
                // Unsafe: Promise.Unsafe.init for idSignal so it can be read from the encode callback
                Sync.Unsafe.defer {
                    val idSignal      = Promise.Unsafe.init[JsonRpcId, Any]()
                    val encodedParams = Present(Structure.encode[In](params))
                    val req           = OutboundReq(method, encodedParams, idSignal, abortSignal, extras)
                    inFlight.getAndIncrement.map { prev =>
                        refresh(prev, drainSignal).andThen {
                            Sync.ensure(callEffectInFlightCleanup(inFlight, drainSignal, idSignal, callerRegistry)) {
                                val raced: Out < (Async & Abort[JsonRpcError | Closed]) =
                                    raceResult[Out](abortSignal, req, exchange)
                                if config.requestTimeout == Duration.Infinity then
                                    // Wrap raceResult with Abort.run inside Sync.ensure so that Abort.fail from
                                    // raceResult is caught here (not by an outer Abort.run that would discard the
                                    // Sync.ensure cleanup continuation), then re-raise after cleanup runs.
                                    Abort.run[JsonRpcError | Closed](raced).map {
                                        case Result.Success(v) => v
                                        case Result.Failure(e) => Abort.fail(e)
                                        case Result.Panic(t)   => Abort.panic(t)
                                    }
                                else
                                    Abort.run[Timeout](Async.timeout(config.requestTimeout)(raced)).map {
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

    // monitorLoop: poll the deadline (AtomicLong epoch millis) every pollInterval and fire timeoutSignal
    // when the wall clock passes the deadline. Only uses Async & Abort[Nothing]; no Closed or JsonRpcError escapes here.
    private def monitorLoop(
        pollInterval: Duration,
        dref: AtomicLong.Unsafe,
        timeoutSignal: Fiber.Promise[Unit, Any],
        clock: Clock
    )(using Frame): Unit < Async =
        Async.sleep(pollInterval).andThen {
            // Unsafe: read the ambient clock and the deadline cell from the monitor fiber
            Sync.Unsafe.defer {
                // ambient wall-clock read inside the Sync.Unsafe.defer suspension boundary
                val now      = clock.unsafe.now()(using AllowUnsafe.embrace.danger).toDuration.toMillis
                val deadline = dref.get()(using AllowUnsafe.embrace.danger)
                now > deadline
            }.map { expired =>
                if expired then
                    // Deadline passed; signal the outer race arm to fire timeout.
                    // Unsafe: complete timeoutSignal from monitor fiber
                    Sync.Unsafe.defer {
                        // CAS-won path completes unit promise from outside originating fiber; no safe equivalent in Promise public API
                        timeoutSignal.unsafe.completeUnitDiscard()(using AllowUnsafe.embrace.danger)
                    }
                else
                    // Deadline not yet passed; continue monitoring
                    monitorLoop(pollInterval, dref, timeoutSignal, clock)
            }
        }

    // Private helper: issues a call with pre-encoded params and an optional deadline ref for progress-reset-timeout.
    // deadlineRef: when progressResetsTimeout = true, holds the epoch-millis deadline for the current call.
    //   Progress notifications extend the deadline by requestTimeout on each arrival.
    //   A monitor fiber polls the deadline and fires a timeout when the wall clock exceeds it.
    // Returns (idPromise, resultEffect).
    def callEncoded[Out: Schema](
        method: String,
        encodedParams: Maybe[Structure.Value],
        extras: JsonRpcExtrasEncoder,
        // AtomicLong.Unsafe: mutable deadline cell shared between call and monitor fibers
        deadlineRef: Maybe[AtomicLong.Unsafe],
        inFlight: AtomicInt,
        drainSignal: AtomicRef[Fiber.Promise[Unit, Any]],
        callerRegistry: ConcurrentHashMap[JsonRpcId, CallerInfo],
        writerChannel: Channel[WriterMsg],
        exchange: Exchange[OutboundReq, Structure.Value, Nothing, JsonRpcError],
        config: JsonRpcHandler.Config
    )(using frame: Frame, allow: AllowUnsafe): (Fiber.Promise[JsonRpcId, Any], Out < (Async & Abort[JsonRpcError | Closed])) =
        // Promise.Unsafe.init so idSignal is accessible before the call runs; it is read from Sync-only Exchange
        // callbacks (no safe Promise equivalent), and is built under the AllowUnsafe propagated by the caller.
        val idSignalUnsafe = Promise.Unsafe.init[JsonRpcId, Any]()
        val idSignal       = idSignalUnsafe
        val idPromise      = idSignalUnsafe.safe
        val callEffect: Out < (Async & Abort[JsonRpcError | Closed]) =
            Fiber.Promise.init[JsonRpcError, Any].map { abortSignal =>
                inFlight.getAndIncrement.map { prev =>
                    refresh(prev, drainSignal).andThen {
                        Sync.ensure(callEffectInFlightCleanup(inFlight, drainSignal, idSignal, callerRegistry)) {
                            val req = OutboundReq(method, encodedParams, idSignal, abortSignal, extras)
                            val raced: Out < (Async & Abort[JsonRpcError | Closed]) =
                                raceResult[Out](abortSignal, req, exchange)
                            if config.requestTimeout == Duration.Infinity then
                                // Wrap raceResult with Abort.run inside Sync.ensure so that Abort.fail from
                                // raceResult is caught here (not by an outer Abort.run that would discard the
                                // Sync.ensure cleanup continuation), then re-raise after cleanup runs.
                                Abort.run[JsonRpcError | Closed](raced).map {
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
                                    // Capture the ambient clock once for the monitor fiber, which inherits it across the fork.
                                    Clock.use(clock => Fiber.initUnscoped(monitorLoop(pollInterval, dref, timeoutSignal, clock))).map {
                                        monitorFiber =>
                                            // Three-way race: abort signal, exchange result, timeout signal.
                                            // On any arm winning, Sync.ensure cleans up the monitor fiber.
                                            Sync.ensure(
                                                // Unsafe: interrupt monitor fiber when the call completes (any outcome)
                                                Sync.Unsafe.defer {
                                                    // fiber interrupt cleans up monitor or writer or handler fiber from outside its scheduler; no safe equivalent in Fiber public API
                                                    monitorFiber.unsafe.interruptDiscard(
                                                        Result.Panic(Interrupted(frame))
                                                    )(using AllowUnsafe.embrace.danger)
                                                }
                                            ) {
                                                Abort.run[JsonRpcError | Closed](
                                                    Async.raceFirst[JsonRpcError | Closed, Out, Any](
                                                        // timeoutSignal arm: deadline expired, run cancel then fail
                                                        timeoutSignal.get.andThen {
                                                            // Unsafe: read idSignal to find the id for cancel notification
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
                                                        raced
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
                                Abort.run[Timeout](Async.timeout(config.requestTimeout)(raced)).map {
                                    case Result.Success(v) => v
                                    case Result.Failure(_) =>
                                        val abortError = config.cancellation match
                                            case Present(p) =>
                                                p.cancelledError.getOrElse(JsonRpcCustomError(-32800, "Request cancelled"))
                                            case Absent => JsonRpcCustomError(-32800, "Request cancelled")
                                        // Unsafe: read idSignal to find the id for cancel notification
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
        (idPromise, callEffect)
    end callEncoded

    def notifyEffect[In: Schema](
        method: String,
        params: In,
        extras: JsonRpcExtrasEncoder,
        writerChannel: Channel[WriterMsg]
    )(using Frame): Unit < (Async & Abort[Closed]) =
        val sentinelId    = JsonRpcId(-1L)
        val encodedParams = Present(Structure.encode[In](params))
        extras.resolve(sentinelId).map { extrasVal =>
            val env = JsonRpcNotification(method, encodedParams, extrasVal)
            writerChannel.put(WriterMsg.SendEnvelope(env))
        }
    end notifyEffect

    def sendUnmatchedEffect[In: Schema](
        method: String,
        params: In,
        id: JsonRpcId,
        extras: JsonRpcExtrasEncoder,
        writerChannel: Channel[WriterMsg]
    )(using Frame): Unit < (Async & Abort[Closed]) =
        Sync.defer(Structure.encode[In](params)).map { encodedParams =>
            extras.resolve(id).map { extrasVal =>
                val env = JsonRpcRequest(id, method, Present(encodedParams), extrasVal)
                writerChannel.put(WriterMsg.SendEnvelope(env))
            }
        }
    end sendUnmatchedEffect

    def callWithProgressEffect[In: Schema, Out: Schema](
        method: String,
        params: In,
        extras: JsonRpcExtrasEncoder,
        meter: Maybe[Meter],
        inFlight: AtomicInt,
        drainSignal: AtomicRef[Fiber.Promise[Unit, Any]],
        callerRegistry: ConcurrentHashMap[JsonRpcId, CallerInfo],
        writerChannel: Channel[WriterMsg],
        exchange: Exchange[OutboundReq, Structure.Value, Nothing, JsonRpcError],
        config: JsonRpcHandler.Config,
        progressPolicy: Maybe[JsonRpcProgressPolicy],
        progressStreams: ConcurrentHashMap[Structure.Value, Channel[Structure.Value]],
        tokenToDeadline: ConcurrentHashMap[Structure.Value, AtomicLong.Unsafe]
    )(using frame: Frame): JsonRpcHandler.Pending[Out] < (Async & Abort[JsonRpcError | Closed]) =
        progressPolicy match
            case Absent =>
                Abort.fail(JsonRpcConfigurationError(
                    "progressPolicy",
                    "required for callWithProgress; pass Config.progress = Present(<your JsonRpcProgressPolicy>)"
                ))
            case Present(policy) =>
                RateLimitEngine.maxInFlightGuard(meter) {
                    // Capture the ambient clock for the initial deadline, then init channel + deadline ref.
                    Clock.use { clock =>
                        Sync.Unsafe.defer {
                            // Unsafe: Channel.Unsafe.init for progress channel
                            // Channel Unsafe init constructs a channel inside an unsafe deferred block; no safe Channel equivalent that runs without Async
                            val progChan = Channel.Unsafe.init[Structure.Value](64)(using frame, AllowUnsafe.embrace.danger).safe
                            // Unsafe: AtomicLong deadline for progressResetsTimeout; Absent when flag is false.
                            // Initial deadline: now + requestTimeout millis. Progress notifications extend it.
                            // AtomicLong.Unsafe: deadline cell shared between call and monitor fibers
                            val deadlineRef: Maybe[AtomicLong.Unsafe] =
                                if config.progressResetsTimeout && config.requestTimeout != Duration.Infinity then
                                    // ambient wall-clock read inside the enclosing Sync.Unsafe.defer suspension boundary
                                    val initialDeadline =
                                        clock.unsafe.now()(using
                                            AllowUnsafe.embrace.danger
                                        ).toDuration.toMillis + config.requestTimeout.toMillis
                                    // AtomicLong.Unsafe: per-request deadline cell
                                    Present(AtomicLong.Unsafe.init(initialDeadline)(using AllowUnsafe.embrace.danger))
                                else Absent
                            (progChan, deadlineRef)
                        }
                    }.map { (progChan, deadlineRef) =>
                        // Registration is now atomic-inside-helper; token allocated via putIfAbsent retry loop.
                        ProgressEngine.allocateProgressToken(progressStreams, progChan, 32).map { tokenVal =>
                            // Register deadline ref (if present) after token is claimed.
                            Sync.Unsafe.defer {
                                deadlineRef match
                                    case Present(ref) => discard(tokenToDeadline.put(tokenVal, ref))
                                    case Absent       => ()
                            }.andThen {
                                val encodedParams = Structure.encode[In](params)
                                policy.stampOutboundToken(encodedParams, tokenVal).map { stampedParams =>
                                    // Unsafe: callEncoded builds the id-signal Promise under AllowUnsafe; discharge it at this defer boundary.
                                    Sync.Unsafe.defer {
                                        callEncoded[Out](
                                            method,
                                            Present(stampedParams),
                                            extras,
                                            deadlineRef,
                                            inFlight,
                                            drainSignal,
                                            callerRegistry,
                                            writerChannel,
                                            exchange,
                                            config
                                        )
                                    }.map { (idPromise, callEffect) =>
                                        // Fork the call so progress stream can be consumed concurrently.
                                        // callEffect fires the encode callback (populating idPromise) before suspending,
                                        // so idPromise.get below won't starve.
                                        Fiber.initUnscoped(callEffect).map { fiber =>
                                            // Register cleanup callback: gracefully closes progChan when fiber finishes.
                                            // closeAwaitEmpty() transitions to HalfOpen so pending items are still drained by the consumer
                                            // before the channel fully closes; this avoids dropping progress items that arrived
                                            // just before the response.
                                            // Unsafe: onComplete from outside the fiber
                                            Sync.Unsafe.defer {
                                                // fiber onComplete attaches cleanup hook from outside the fiber; no safe equivalent in Fiber public API
                                                fiber.unsafe.onComplete { _ =>
                                                    progressStreams.remove(tokenVal)
                                                    tokenToDeadline.remove(tokenVal)
                                                    // channel close or closeAwaitEmpty from finalizer or onComplete hook outside originating fiber; no safe equivalent
                                                    discard(progChan.unsafe.closeAwaitEmpty()(using frame, AllowUnsafe.embrace.danger))
                                                }(using AllowUnsafe.embrace.danger)
                                            }.andThen {
                                                // Await the id (populated in encode callback, which fires as the fiber starts)
                                                idPromise.get.map { id =>
                                                    new JsonRpcHandler.Pending[Out](
                                                        id = id,
                                                        result = fiber.get,
                                                        progress = progChan.streamUntilClosed(),
                                                        cancel =
                                                            CancellationEngine.cancelEffect(
                                                                id,
                                                                Absent,
                                                                callerRegistry,
                                                                config,
                                                                writerChannel
                                                            )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

    def callPartialResultsEffect[In: Schema, T: Schema: Tag](
        method: String,
        params: In,
        extras: JsonRpcExtrasEncoder,
        meter: Maybe[Meter],
        inFlight: AtomicInt,
        drainSignal: AtomicRef[Fiber.Promise[Unit, Any]],
        callerRegistry: ConcurrentHashMap[JsonRpcId, CallerInfo],
        writerChannel: Channel[WriterMsg],
        exchange: Exchange[OutboundReq, Structure.Value, Nothing, JsonRpcError],
        config: JsonRpcHandler.Config,
        progressPolicy: Maybe[JsonRpcProgressPolicy],
        progressStreams: ConcurrentHashMap[Structure.Value, Channel[Structure.Value]]
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
                        Sync.Unsafe.defer {
                            // Unsafe: Channel.Unsafe.init for partial-results channel
                            // Channel Unsafe init constructs a channel inside an unsafe deferred block; no safe Channel equivalent that runs without Async
                            val progChan = Channel.Unsafe.init[Structure.Value](64)(using frame, AllowUnsafe.embrace.danger).safe
                            // Unsafe: AtomicRef.Unsafe.init for the final response result (non-progress chunks)
                            val finalRef = AtomicRef.Unsafe.init[Maybe[Structure.Value]](Absent)(using AllowUnsafe.embrace.danger).safe
                            (progChan, finalRef)
                        }.map { (progChan, finalRef) =>
                            // Registration is now atomic-inside-helper; no separate put call here.
                            ProgressEngine.allocateProgressToken(progressStreams, progChan, 32).map { tokenVal =>
                                val encodedParams = Structure.encode[In](params)
                                policy.stampOutboundToken(encodedParams, tokenVal).map { stampedParams =>
                                    // Unsafe: callEncoded builds the id-signal Promise under AllowUnsafe; discharge it at this defer boundary.
                                    Sync.Unsafe.defer {
                                        callEncoded[Structure.Value](
                                            method,
                                            Present(stampedParams),
                                            extras,
                                            Absent,
                                            inFlight,
                                            drainSignal,
                                            callerRegistry,
                                            writerChannel,
                                            exchange,
                                            config
                                        )
                                    }.map { (_, callEffect) =>
                                        Fiber.initUnscoped(
                                            Abort.run[JsonRpcError | Closed](callEffect).map { res =>
                                                res match
                                                    case Result.Success(sv) if sv != Structure.Value.Null =>
                                                        // Non-null final result: store it in finalRef, then gracefully close channel.
                                                        // closeAwaitEmpty() drains remaining progress items before fully closing,
                                                        // so the drain loop below sees all items before Closed propagates.
                                                        // Unsafe: store final result and close channel from call fiber
                                                        Sync.Unsafe.defer {
                                                            // AtomicX setter from Sync-only Exchange callback; no safe Atomic equivalent within Sync
                                                            finalRef.unsafe.set(Present(sv))(using AllowUnsafe.embrace.danger)
                                                            progressStreams.remove(tokenVal)
                                                            // channel close or closeAwaitEmpty from finalizer or onComplete hook outside originating fiber; no safe equivalent
                                                            discard(progChan.unsafe.closeAwaitEmpty()(using
                                                                frame,
                                                                AllowUnsafe.embrace.danger
                                                            ))
                                                        }
                                                    case _ =>
                                                        // Null result, failure, or closed: gracefully close channel with no final chunk.
                                                        // Structure.Value.Null signals "partial-result pattern: all chunks were via progress".
                                                        // Unsafe: remove from progressStreams and close channel from call fiber (outside consumer)
                                                        Sync.Unsafe.defer {
                                                            progressStreams.remove(tokenVal)
                                                            // channel close or closeAwaitEmpty from finalizer or onComplete hook outside originating fiber; no safe equivalent
                                                            discard(progChan.unsafe.closeAwaitEmpty()(using
                                                                frame,
                                                                AllowUnsafe.embrace.danger
                                                            ))
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
                                                                    case Result.Success(v) =>
                                                                        Emit.value(Chunk(v))(using tagEmitChunkT, frame)
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
                        }
                    } // end maxInFlightGuard
                }

end CallEngine
