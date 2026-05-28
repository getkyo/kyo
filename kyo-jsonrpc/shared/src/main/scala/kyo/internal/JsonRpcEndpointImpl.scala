package kyo
package internal

import java.util.concurrent.ConcurrentHashMap

private[kyo] case class OutboundReq(
    method: String,
    encodedParams: Maybe[Structure.Value],
    // Unsafe: completed inside Exchange encode callback (Sync context, no Frame available)
    idSignal: Promise.Unsafe[JsonRpcId, Any],
    abortSignal: Fiber.Promise[JsonRpcError, Any],
    extras: ExtrasEncoder
)

private[kyo] case class CallerInfo(
    method: String,
    extras: Maybe[Structure.Value],
    abortSignal: Fiber.Promise[JsonRpcError, Any],
    pendingCancelError: java.util.concurrent.atomic.AtomicReference[Maybe[JsonRpcError]]
)

sealed private[kyo] trait InboundEntry
private[kyo] object InboundEntry:
    case class Running(
        method: String,
        handler: Fiber[Structure.Value, Abort[JsonRpcError]],
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
    private val codec: JsonRpcCodec,
    private val methodMap: Map[String, JsonRpcMethod[Async & Abort[JsonRpcError]]],
    private val unknownPolicy: UnknownMethodPolicy,
    private[kyo] val config: JsonRpcEndpoint.Config,
    private[kyo] val initFrame: Frame,
    private val progressPolicy: Maybe[ProgressPolicy],
    private[kyo] val progressStreams: ConcurrentHashMap[Structure.Value, Channel[Structure.Value]],
    private[kyo] val outboundIdToToken: ConcurrentHashMap[JsonRpcId, Structure.Value]
):

    def call[In: Schema, Out: Schema](
        method: String,
        params: In,
        extras: ExtrasEncoder
    )(using Frame): Out < (Async & Abort[JsonRpcError | Closed]) =
        Fiber.Promise.init[JsonRpcError, Any].map { abortSignal =>
            // Unsafe: Promise.Unsafe.init for idSignal so it can be read from the encode callback
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
                            Sync.ensure(
                                inFlight.decrementAndGet.map { newCount =>
                                    (if newCount == 0 then snapshot.completeUnitDiscard else Kyo.unit).andThen {
                                        // Unsafe: poll idSignal to clean callerRegistry on request completion
                                        Sync.Unsafe.defer {
                                            idSignal.poll() match
                                                case Maybe.Present(Result.Success(id)) =>
                                                    callerRegistry.remove(id)
                                                case _ => ()
                                        }
                                    }
                                }
                            ) {
                                val raceResult: Out < (Async & Abort[JsonRpcError | Closed]) =
                                    Async.raceFirst[JsonRpcError | Closed, Out, Any](
                                        abortSignal.get.map(e => Abort.fail[JsonRpcError](e)),
                                        exchange(req).map { sv =>
                                            Structure.decode[Out](sv) match
                                                case Result.Success(v) => v
                                                case Result.Failure(e) => Abort.fail(JsonRpcError.invalidParams(e.getMessage))
                                                case Result.Panic(t)   => Abort.panic(t)
                                        }
                                    )
                                if config.requestTimeout == Duration.Infinity then
                                    raceResult
                                else
                                    Abort.run[Timeout](Async.timeout(config.requestTimeout)(raceResult)).map {
                                        case Result.Success(v) => v
                                        case Result.Failure(_) =>
                                            // Timeout fired: enqueue cancel notification, then fail with the policy-determined error.
                                            // Do NOT await abortSignal here: raceFirst's cleanup has already interrupted
                                            // the abortSignal promise with Panic(Interrupted), so awaiting it would panic.
                                            val abortError: JsonRpcError = config.cancellation match
                                                case Present(p) => p.cancelledError.getOrElse(JsonRpcError.cancelled(Absent))
                                                case Absent     => JsonRpcError.cancelled(Absent)
                                            // Unsafe: read idSignal to find the id for cancel notification
                                            Sync.Unsafe.defer {
                                                idSignal.poll() match
                                                    case Maybe.Present(Result.Success(rawId)) =>
                                                        val id: JsonRpcId = rawId.eval
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

    // Private helper: issues a call with pre-encoded params.
    // Returns a Kyo effect that produces (idPromise, resultEffect) once the idSignal is initialized.
    private def callEncoded[Out: Schema](
        method: String,
        encodedParams: Maybe[Structure.Value],
        extras: ExtrasEncoder
    )(using frame: Frame): (Fiber.Promise[JsonRpcId, Any], Out < (Async & Abort[JsonRpcError | Closed])) =
        // Unsafe: Promise.Unsafe.init so idSignal is accessible before the call runs
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
                                                case Result.Failure(e) => Abort.fail(JsonRpcError.invalidParams(e.getMessage))
                                                case Result.Panic(t)   => Abort.panic(t)
                                        }
                                    )
                                if config.requestTimeout == Duration.Infinity then
                                    raceResult
                                else
                                    Abort.run[Timeout](Async.timeout(config.requestTimeout)(raceResult)).map {
                                        case Result.Success(v) => v
                                        case Result.Failure(_) =>
                                            val abortError: JsonRpcError = config.cancellation match
                                                case Present(p) => p.cancelledError.getOrElse(JsonRpcError.cancelled(Absent))
                                                case Absent     => JsonRpcError.cancelled(Absent)
                                            // Unsafe: read idSignal to find the id for cancel notification
                                            Sync.Unsafe.defer {
                                                idSignal.poll() match
                                                    case Maybe.Present(Result.Success(rawId)) =>
                                                        val id: JsonRpcId = rawId.eval
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

    def notify[In: Schema](
        method: String,
        params: In,
        extras: ExtrasEncoder
    )(using Frame): Unit < (Async & Abort[Closed]) =
        val sentinelId    = JsonRpcId.Num(-1L)
        val encodedParams = Present(Structure.encode[In](params))
        extras.resolve(sentinelId).map { extrasVal =>
            val env = JsonRpcEnvelope.Notification(method, encodedParams, extrasVal)
            writerChannel.put(WriterMsg.SendEnvelope(env))
        }
    end notify

    def callWithProgress[In: Schema, Out: Schema](
        method: String,
        params: In,
        extras: ExtrasEncoder
    )(using frame: Frame): JsonRpcEndpoint.Pending[Out] < (Async & Abort[JsonRpcError | Closed]) =
        progressPolicy match
            case Absent =>
                Abort.fail(JsonRpcError.internalError(
                    "progress not configured: pass Config.progress = Present(ProgressPolicy.lsp / .mcp)"
                ))
            case Present(policy) =>
                // Unsafe: channel init and ConcurrentHashMap put for progress side-channel
                Sync.Unsafe.defer {
                    val tokenStr = java.util.UUID.randomUUID().toString
                    val tokenVal = Structure.Value.Str(tokenStr)
                    // Unsafe: Channel.Unsafe.init for progress channel
                    val progChan = Channel.Unsafe.init[Structure.Value](64)(using frame, AllowUnsafe.embrace.danger).safe
                    (tokenVal, progChan)
                }.map { (tokenVal, progChan) =>
                    val encodedParams = Structure.encode[In](params)
                    policy.stampOutboundToken(encodedParams, tokenVal).map { stampedParams =>
                        // Unsafe: register in progressStreams before issuing call
                        Sync.Unsafe.defer(discard(progressStreams.put(tokenVal, progChan))).andThen {
                            val (idPromise, callEffect) = callEncoded[Out](method, Present(stampedParams), extras)
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
                                    fiber.unsafe.onComplete { _ =>
                                        progressStreams.remove(tokenVal)
                                        discard(progChan.unsafe.closeAwaitEmpty()(using frame, AllowUnsafe.embrace.danger))
                                    }(using AllowUnsafe.embrace.danger)
                                }.andThen {
                                    // Await the id (populated in encode callback, which fires as the fiber starts)
                                    idPromise.get.map { id =>
                                        new JsonRpcEndpoint.Pending[Out](
                                            id = id,
                                            result = fiber.get,
                                            progress = progChan.streamUntilClosed(),
                                            cancel = cancel(id, Absent)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

    def callPartialResults[In: Schema, T: Schema: Tag](
        method: String,
        params: In,
        extras: ExtrasEncoder
    )(using frame: Frame, tagEmitChunkT: Tag[Emit[Chunk[T]]]): Stream[T, Async & Abort[JsonRpcError | Closed]] =
        progressPolicy match
            case Absent =>
                Stream(Abort.fail[JsonRpcError](JsonRpcError.internalError(
                    "progress not configured: pass Config.progress = Present(ProgressPolicy.lsp / .mcp)"
                )))
            case Present(policy) =>
                // All setup runs inside the Stream body so the return type is Stream (not Stream < Sync).
                Stream[T, Async & Abort[JsonRpcError | Closed]] {
                    // Unsafe: channel init and token/stamp inside Stream emit body
                    Sync.Unsafe.defer {
                        val tokenStr = java.util.UUID.randomUUID().toString
                        val tokenVal = Structure.Value.Str(tokenStr)
                        // Unsafe: Channel.Unsafe.init for partial-results channel
                        val progChan = Channel.Unsafe.init[Structure.Value](64)(using frame, AllowUnsafe.embrace.danger).safe
                        // Unsafe: AtomicRef.Unsafe.init for the final response result (non-progress chunks)
                        val finalRef = AtomicRef.Unsafe.init[Maybe[Structure.Value]](Absent)(using AllowUnsafe.embrace.danger).safe
                        (tokenVal, progChan, finalRef)
                    }.map { (tokenVal, progChan, finalRef) =>
                        val encodedParams = Structure.encode[In](params)
                        policy.stampOutboundToken(encodedParams, tokenVal).map { stampedParams =>
                            Sync.Unsafe.defer(discard(progressStreams.put(tokenVal, progChan))).andThen {
                                val (_, callEffect) =
                                    callEncoded[Structure.Value](method, Present(stampedParams), extras)
                                Fiber.initUnscoped(
                                    Abort.run[JsonRpcError | Closed](callEffect).map { res =>
                                        res match
                                            case Result.Success(sv) if sv != Structure.Value.Null =>
                                                // Non-null final result: store it in finalRef, then gracefully close channel.
                                                // closeAwaitEmpty() drains remaining progress items before fully closing,
                                                // so the drain loop below sees all items before Closed propagates.
                                                // Unsafe: store final result and close channel from call fiber
                                                Sync.Unsafe.defer {
                                                    finalRef.unsafe.set(Present(sv))(using AllowUnsafe.embrace.danger)
                                                    progressStreams.remove(tokenVal)
                                                    discard(progChan.unsafe.closeAwaitEmpty()(using frame, AllowUnsafe.embrace.danger))
                                                }
                                            case _ =>
                                                // Null result, failure, or closed: gracefully close channel with no final chunk.
                                                // Structure.Value.Null signals "partial-result pattern: all chunks were via progress".
                                                Sync.Unsafe.defer {
                                                    progressStreams.remove(tokenVal)
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
                                                            case Result.Success(v) => Emit.value(Chunk(v))(using tagEmitChunkT, frame)
                                                            case Result.Failure(e) => Abort.fail(JsonRpcError.invalidParams(e.getMessage))
                                                            case Result.Panic(t)   => Abort.panic(t)
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
                                                        case Result.Success(v) => Emit.value(Chunk(v))(using tagEmitChunkT, frame)
                                                        case Result.Failure(e) => Abort.fail(JsonRpcError.invalidParams(e.getMessage))
                                                        case Result.Panic(t)   => Abort.panic(t)
                                            }
                                        case Result.Panic(t) => Abort.panic(t)
                                    }
                                }
                            }
                        }
                    }
                }

    def subscribeProgress(token: Structure.Value)(using frame: Frame): Stream[Structure.Value, Async & Abort[Closed]] < Sync =
        progressPolicy match
            case Absent =>
                Stream(Abort.fail[Closed](Closed("progress not configured", initFrame)))
            case Present(_) =>
                // Eagerly create and register the channel so notifications can be routed before the stream is consumed.
                // Unsafe: channel init and ConcurrentHashMap putIfAbsent must happen at subscribe time.
                Sync.Unsafe.defer {
                    val ch = Channel.Unsafe.init[Structure.Value](64)(using frame, AllowUnsafe.embrace.danger).safe
                    discard(progressStreams.putIfAbsent(token, ch))
                    Maybe(progressStreams.get(token))
                }.map {
                    // streamUntilClosed handles Closed gracefully so the consumer sees all buffered items
                    // before the stream terminates, even when unsubscribeProgress races with the consumer.
                    case Present(ch) => ch.streamUntilClosed()
                    case Absent      => Stream[Structure.Value, Async](Kyo.unit)
                }

    def unsubscribeProgress(token: Structure.Value)(using frame: Frame): Unit < Async =
        Sync.Unsafe.defer {
            Maybe(progressStreams.remove(token)) match
                case Absent      => ()
                case Present(ch) =>
                    // Unsafe: closeAwaitEmpty lets the consumer drain any items already in the channel
                    // before the channel transitions to FullyClosed; prevents item loss on unsubscribe.
                    discard(ch.unsafe.closeAwaitEmpty()(using frame, AllowUnsafe.embrace.danger))
        }

    def cancel(id: JsonRpcId, reason: Maybe[String])(using Frame): Unit < (Async & Abort[Closed]) =
        Sync.defer(Maybe(callerRegistry.get(id))).map {
            case Absent =>
                Log.warn(s"kyo-jsonrpc: cancel for unknown or already-completed id $id, no-op")
            case Present(info) =>
                config.cancellation match
                    case Absent =>
                        // No policy: abort locally only, no wire notification
                        // Unsafe: complete abortSignal from cancel call
                        Sync.Unsafe.defer {
                            info.abortSignal.unsafe.completeDiscard(
                                Result.succeed(JsonRpcError.cancelled(reason))
                            )(using AllowUnsafe.embrace.danger)
                        }
                    case Present(policy) =>
                        if policy.protectedMethods.contains(info.method) then
                            Log.warn(
                                s"kyo-jsonrpc: cancel refused for protected method ${info.method}, no-op"
                            )
                        else
                            val abortError = policy.cancelledError.getOrElse(JsonRpcError.cancelled(reason))
                            if policy.expectReplyForCancelledRequest then
                                // LSP: server will still reply; set pendingCancelError so decodeCallback
                                // completes abortSignal when the reply arrives.
                                // Unsafe: set pendingCancelError from cancel call
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
                                // MCP/no-reply: complete abortSignal immediately, no reply expected
                                CancellationEngine.buildAndEnqueueOutboundCancel(
                                    id,
                                    reason,
                                    info,
                                    policy,
                                    writerChannel
                                ).andThen {
                                    // Unsafe: complete abortSignal after enqueuing the cancel notification
                                    Sync.Unsafe.defer {
                                        info.abortSignal.unsafe.completeDiscard(
                                            Result.succeed(abortError)
                                        )(using AllowUnsafe.embrace.danger)
                                    }
                                }
                            end if
        }

    def awaitDrain(using Frame): Unit < Async =
        inFlight.get.map { n =>
            if n <= 0 then Kyo.unit
            else drainSignal.get.map(_.get.unit)
        }

    def close(using Frame): Unit < Async =
        finalizer

    private[kyo] def finalizer(using Frame): Unit < Async =
        // Step 1: poison writer channel so writer fiber unblocks and exits
        writerChannel.close.unit.andThen {
            // Step 2: reader fiber managed by Exchange; step 6 Exchange.close() cancels it
            // Step 3: cancel writer fiber
            // Unsafe: interruptDiscard must run outside the fiber scheduler; Sync.Unsafe.defer bridges to safe context
            Sync.Unsafe.defer(writerFiber.unsafe.interruptDiscard(Result.Panic(Interrupted(initFrame)))).andThen {
                // Step 4: close transport
                transport.close.andThen {
                    // Step 5: drain callerRegistry. Fail all Exchange pending promises with internalError (not Closed)
                    // so the abortSignal arm wins raceFirst (JsonRpcError path). Then complete each abortSignal.
                    // Calls not yet in callerRegistry when Exchange.close fires (step 6) see Closed via donePromise check.
                    // Unsafe: bulk-fail and complete from outside originating fibers
                    Sync.Unsafe.defer {
                        // Unsafe: failAllPending fails all Exchange pending promises with the given error
                        exchange.unsafe.failAllPending(
                            JsonRpcError.internalError("endpoint closed", Absent)
                        )(using AllowUnsafe.embrace.danger)
                        callerRegistry.forEach { (_, info) =>
                            info.abortSignal.unsafe.completeDiscard(
                                Result.succeed(JsonRpcError.internalError("endpoint closed", Absent))
                            )(using AllowUnsafe.embrace.danger)
                        }
                        callerRegistry.clear()
                    }.andThen {
                        // Step 6: close Exchange; sets donePromise to Closed for future calls; pending map is now empty
                        exchange.close.andThen {
                            // Step 7: close all progress channels so stream consumers see Closed
                            // Unsafe: bulk-close from outside the originating fibers
                            Sync.Unsafe.defer {
                                progressStreams.forEach { (_, ch) =>
                                    discard(ch.unsafe.close()(using initFrame, AllowUnsafe.embrace.danger))
                                }
                                progressStreams.clear()
                            }.andThen {
                                // Step 8: interrupt all pendingInbound handler fibers
                                // Unsafe: bulk-interrupt inbound handlers from outside their originating fibers
                                Sync.Unsafe.defer {
                                    pendingInbound.forEach { (_, entry) =>
                                        entry match
                                            case InboundEntry.Running(_, handler, _) =>
                                                handler.unsafe.interruptDiscard(Result.Panic(Interrupted(initFrame)))
                                            case _ => ()
                                    }
                                    pendingInbound.clear()
                                }
                            }
                        }
                    }
                }
            }
        }

end JsonRpcEndpointImpl

object JsonRpcEndpointImpl:

    def init(
        transport: JsonRpcTransport,
        methods: Seq[JsonRpcMethod[Async & Abort[JsonRpcError]]],
        config: JsonRpcEndpoint.Config
    )(using frame: Frame): JsonRpcEndpointImpl < (Sync & Async & Scope) =
        Scope.acquireRelease(initEngine(transport, methods, config))(impl => impl.finalizer(using impl.initFrame))

    private def initEngine(
        transport: JsonRpcTransport,
        methods: Seq[JsonRpcMethod[Async & Abort[JsonRpcError]]],
        config: JsonRpcEndpoint.Config
    )(using frame: Frame): JsonRpcEndpointImpl < (Sync & Async) =
        // Unsafe: ConcurrentHashMap mirrors Exchange's own internal pattern
        val callerRegistry = new ConcurrentHashMap[JsonRpcId, CallerInfo]()
        val pendingInbound = new ConcurrentHashMap[JsonRpcId, InboundEntry]()
        // Unsafe: ConcurrentHashMap for progress streams and reverse id-to-token map
        val progressStreams   = new ConcurrentHashMap[Structure.Value, Channel[Structure.Value]]()
        val outboundIdToToken = new ConcurrentHashMap[JsonRpcId, Structure.Value]()
        val methodMap         = methods.map(m => m.name -> m).toMap
        val nextIdFn          = IdStrategy.mkNextId(config.idStrategy)

        Channel.initUnscoped[WriterMsg](64).map { writerChannel =>
            // Unsafe: init AtomicInt/AtomicRef/Promise.Unsafe for inFlight and drainSignal counters
            Sync.Unsafe.defer {
                val inFlightUnsafe = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
                val inFlight       = inFlightUnsafe.safe
                // Unsafe: Promise.Unsafe.init for drainSignal initial placeholder
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
                                val pendingCancel = new java.util.concurrent.atomic.AtomicReference[Maybe[JsonRpcError]](Absent)
                                callerRegistry.put(id, CallerInfo(req.method, extrasVal, req.abortSignal, pendingCancel))
                                req.idSignal.completeDiscard(Result.succeed(id))(using AllowUnsafe.embrace.danger)
                            }.andThen {
                                // Build envelope and encode to JSON
                                val env = JsonRpcEnvelope.Request(id, req.method, req.encodedParams, extrasVal)
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
                                                Abort.fail(JsonRpcError.internalError(s"transport closed: ${c.getMessage}", Absent))
                                            case Result.Panic(t) => Abort.panic(t)
                                        }
                                    case Result.Failure(e) => Abort.fail(e)
                                    case Result.Panic(t)   => Abort.panic(t)
                                }
                            case Result.Failure(_) => Abort.fail(JsonRpcError.internalError("wire decode error", Absent))
                            case Result.Panic(t)   => Abort.panic(t)

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
                                    Abort.fail(JsonRpcError.internalError(s"transport closed: ${c.getMessage}", Absent))
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

                                        case env @ JsonRpcEnvelope.Notification(method, params, _) =>
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
                                                                        Sync.Unsafe.defer {
                                                                            Maybe(progressStreams.get(token)) match
                                                                                case Absent      => ()
                                                                                case Present(ch) =>
                                                                                    // Unsafe: non-blocking offer; backpressure not applied here
                                                                                    discard(ch.unsafe.offer(paramsVal)(using
                                                                                        AllowUnsafe.embrace.danger,
                                                                                        frame
                                                                                    ))
                                                                        }.andThen(Exchange.Message.Skip)
                                                            }
                                                        case _ =>
                                                            // Step 2: gate intercept (before method dispatch)
                                                            def dispatchNotification
                                                                : Exchange.Message[JsonRpcId, Structure.Value, Nothing] < Sync =
                                                                methodMap.get(method) match
                                                                    case Some(m) =>
                                                                        // Unsafe: Promise.Unsafe.init for cancelled signal on notification handlers
                                                                        val cancelledUnsafe =
                                                                            Promise.Unsafe.init[Unit, Sync]()(using
                                                                                AllowUnsafe.embrace.danger
                                                                            )
                                                                        val ctx =
                                                                            new HandlerCtx(cancelledUnsafe.safe, Absent, env.extras, Absent)
                                                                        val handlerEffect =
                                                                            m.handle(env.params.getOrElse(Structure.Value.Null), ctx)(using
                                                                                frame
                                                                            )
                                                                        Fiber.initUnscoped(handlerEffect).map { _ =>
                                                                            Exchange.Message.Skip
                                                                        }
                                                                    case None =>
                                                                        // Step 3: unknown-method dispatch for notifications
                                                                        val isDollarPrefix = method.startsWith("$/")
                                                                        if config.unknownMethod.dollarPrefixOverride && isDollarPrefix then
                                                                            Exchange.Message.Skip
                                                                        else
                                                                            config.unknownMethod.onUnknownNotification match
                                                                                case UnknownMethodPolicy.UnknownAction.Drop =>
                                                                                    Exchange.Message.Skip
                                                                                case UnknownMethodPolicy.UnknownAction.ReplyMethodNotFound =>
                                                                                    Exchange.Message.Skip
                                                                                case UnknownMethodPolicy.UnknownAction.Reject =>
                                                                                    Log.warn(
                                                                                        s"kyo-jsonrpc: unknown notification method '$method' rejected"
                                                                                    ).andThen {
                                                                                        // Unsafe: read implRef to trigger close; implRef set before any messages arrive
                                                                                        Sync.Unsafe.defer {
                                                                                            implRefUnsafe.get()(using
                                                                                                AllowUnsafe.embrace.danger
                                                                                            ) match
                                                                                                case Present(i) =>
                                                                                                    Fiber.initUnscoped(i.close(using frame))
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
                                                                        case MessageGate.Decision.Allow =>
                                                                            dispatchNotification
                                                                        case MessageGate.Decision.Reject(_) =>
                                                                            // Notifications have no id: log WARN, drop silently (no wire response)
                                                                            Log.warn(
                                                                                s"kyo-jsonrpc: gate rejected notification method '$method'"
                                                                            ).andThen(
                                                                                Exchange.Message.Skip
                                                                            )
                                                                        case MessageGate.Decision.Drop =>
                                                                            Exchange.Message.Skip
                                                                    }
                                                            end match

                                        case env2 @ JsonRpcEnvelope.Request(id, method, params, extras) =>
                                            // Step 2: gate intercept (before method dispatch)
                                            def dispatchRequest: Exchange.Message[JsonRpcId, Structure.Value, Nothing] < Sync =
                                                methodMap.get(method) match
                                                    case Some(m) =>
                                                        // Unsafe: Promise.Unsafe.init and buildProgressSink require AllowUnsafe
                                                        val cancelledUnsafe =
                                                            Promise.Unsafe.init[Unit, Sync]()(using AllowUnsafe.embrace.danger)
                                                        // Unsafe: build progressSink using AllowUnsafe.embrace.danger directly
                                                        val progressSinkOpt: Maybe[Structure.Value => Unit < (Async & Abort[Closed])] =
                                                            ProgressEngine.buildProgressSink(
                                                                id,
                                                                params,
                                                                extras,
                                                                config.progress,
                                                                pendingInbound,
                                                                writerChannel
                                                            )(using frame, AllowUnsafe.embrace.danger)
                                                        val ctx = new HandlerCtx(cancelledUnsafe.safe, Present(id), extras, progressSinkOpt)
                                                        val handlerEffect =
                                                            m.handle(params.getOrElse(Structure.Value.Null), ctx)(using frame)
                                                        Fiber.initUnscoped(handlerEffect).map { fiber =>
                                                            // Unsafe: register pendingInbound entry and attach onComplete hook inside Exchange decode callback
                                                            Sync.Unsafe.defer {
                                                                val entry = InboundEntry.Running(method, fiber, cancelledUnsafe.safe)
                                                                pendingInbound.put(id, entry)
                                                                // Attach completion hook AFTER putting in pendingInbound
                                                                fiber.unsafe.onComplete { result =>
                                                                    val responseEnvelope = result match
                                                                        case Result.Success(sv) =>
                                                                            JsonRpcEnvelope.Response(
                                                                                id,
                                                                                Present(sv.eval(using frame)),
                                                                                Absent,
                                                                                extras
                                                                            )
                                                                        case Result.Failure(e) =>
                                                                            JsonRpcEnvelope.Response(id, Absent, Present(e), extras)
                                                                        case Result.Panic(t) =>
                                                                            JsonRpcEnvelope.Response(
                                                                                id,
                                                                                Absent,
                                                                                Present(
                                                                                    JsonRpcError.internalError(
                                                                                        "Internal error",
                                                                                        Present(Structure.Value.Str(t.getMessage))
                                                                                    )
                                                                                ),
                                                                                extras
                                                                            )
                                                                    // CAS: Running -> Replying (fails if cancel moved it to Cancelled)
                                                                    pendingInbound.get(id) match
                                                                        case running: InboundEntry.Running =>
                                                                            // Unsafe: AtomicBoolean.Unsafe.init for suppress flag
                                                                            val suppressUnsafe = AtomicBoolean.Unsafe.init(false)(using
                                                                                AllowUnsafe.embrace.danger
                                                                            )
                                                                            val replying =
                                                                                InboundEntry.Replying(method, suppressUnsafe.safe)
                                                                            if pendingInbound.replace(id, running, replying) then
                                                                                // Unsafe: writer-channel offer from Sync-only onComplete callback
                                                                                discard(writerChannel.unsafe.offer(
                                                                                    WriterMsg.SuppressIfCancelled(id, responseEnvelope)
                                                                                )(using AllowUnsafe.embrace.danger, frame))
                                                                            end if
                                                                        case _: InboundEntry.Cancelled =>
                                                                            // Cancel won CAS; for LSP (expectReplyForCancelledRequest=true)
                                                                            // the handler still produces a reply, so we send it.
                                                                            // For MCP the handler was interrupted and should not reply.
                                                                            val mustReply = config.cancellation match
                                                                                case Present(p) => p.expectReplyForCancelledRequest
                                                                                case Absent     => false
                                                                            if mustReply then
                                                                                // Unsafe: SendEnvelope bypasses suppress check (LSP always replies)
                                                                                discard(writerChannel.unsafe.offer(
                                                                                    WriterMsg.SendEnvelope(responseEnvelope)
                                                                                )(using AllowUnsafe.embrace.danger, frame))
                                                                                discard(pendingInbound.remove(id))
                                                                            end if
                                                                        case _ => ()
                                                                    end match
                                                                }(using AllowUnsafe.embrace.danger)
                                                            }.andThen(Exchange.Message.Skip)
                                                        }

                                                    case None =>
                                                        // Step 3: unknown-method dispatch for requests
                                                        config.unknownMethod.onUnknownRequest match
                                                            case UnknownMethodPolicy.UnknownAction.ReplyMethodNotFound =>
                                                                val response = JsonRpcEnvelope.Response(
                                                                    id,
                                                                    Absent,
                                                                    Present(JsonRpcError.methodNotFound(method)(using frame)),
                                                                    Absent
                                                                )
                                                                // Unsafe: offer to writerChannel inside Exchange decode callback
                                                                Sync.Unsafe.defer {
                                                                    discard(writerChannel.unsafe.offer(WriterMsg.SendEnvelope(response))(
                                                                        using
                                                                        AllowUnsafe.embrace.danger,
                                                                        frame
                                                                    ))
                                                                }.andThen(Exchange.Message.Skip)
                                                            case UnknownMethodPolicy.UnknownAction.Drop =>
                                                                Exchange.Message.Skip
                                                            case UnknownMethodPolicy.UnknownAction.Reject =>
                                                                // Reject for a Request: send MethodNotFound first (so caller is unblocked), then close.
                                                                val response = JsonRpcEnvelope.Response(
                                                                    id,
                                                                    Absent,
                                                                    Present(JsonRpcError.methodNotFound(method)(using frame)),
                                                                    Absent
                                                                )
                                                                // Unsafe: offer to writerChannel inside Exchange decode callback
                                                                Sync.Unsafe.defer {
                                                                    discard(writerChannel.unsafe.offer(WriterMsg.SendEnvelope(response))(
                                                                        using
                                                                        AllowUnsafe.embrace.danger,
                                                                        frame
                                                                    ))
                                                                }.andThen {
                                                                    // Unsafe: read implRef to trigger close; implRef set before any messages arrive
                                                                    Sync.Unsafe.defer {
                                                                        implRefUnsafe.get()(using AllowUnsafe.embrace.danger) match
                                                                            case Present(i) => Fiber.initUnscoped(i.close(using frame))
                                                                            case Absent     => ()
                                                                    }.andThen(Exchange.Message.Skip)
                                                                }
                                            end dispatchRequest
                                            config.gate match
                                                case Absent =>
                                                    dispatchRequest
                                                case Present(g) =>
                                                    g.beforeDispatch(env2)(using frame).map {
                                                        case MessageGate.Decision.Allow =>
                                                            dispatchRequest
                                                        case MessageGate.Decision.Reject(err) =>
                                                            // Request has an id: send error response so caller is not left hanging
                                                            val response = JsonRpcEnvelope.Response(id, Absent, Present(err), Absent)
                                                            // Unsafe: offer to writerChannel inside gate decision handler
                                                            Sync.Unsafe.defer {
                                                                discard(writerChannel.unsafe.offer(WriterMsg.SendEnvelope(response))(using
                                                                    AllowUnsafe.embrace.danger,
                                                                    frame
                                                                ))
                                                            }.andThen(Exchange.Message.Skip)
                                                        case MessageGate.Decision.Drop =>
                                                            Exchange.Message.Skip
                                                    }
                                            end match

                                        case JsonRpcEnvelope.Response(id, result, error, _) =>
                                            error match
                                                case Present(e) =>
                                                    // Unsafe: complete abortSignal inside Exchange decode callback so raceFirst selects the abort arm.
                                                    // Return Skip so Exchange does not also complete the pending promise with a null value.
                                                    Sync.Unsafe.defer {
                                                        Maybe(callerRegistry.get(id)).foreach { info =>
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
                                                                info.pendingCancelError.get() match
                                                                    case Present(cancelErr) =>
                                                                        // LSP cancel was issued; reply arrived but caller should see cancel error.
                                                                        // Complete abortSignal with cancel error and Skip the response.
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

                                        case JsonRpcEnvelope.Malformed(_, _) =>
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
                            outboundIdToToken = outboundIdToToken
                        )
                        // Unsafe: populate implRef so decodeCallback Reject-close branches can trigger engine close
                        implRefUnsafe.set(Present(impl))(using AllowUnsafe.embrace.danger)
                        impl
                    }
                }
            }
        }
    end initEngine

end JsonRpcEndpointImpl
