package kyo.internal.engine

// ConcurrentHashMap shared concurrent map; cross-platform via JS/Native JDK shim
import java.util.concurrent.ConcurrentHashMap
import kyo.*

private[kyo] object LifecycleEngine:

    def awaitDrainEffect(
        inFlight: AtomicInt,
        drainSignal: AtomicRef[Fiber.Promise[Unit, Any]]
    )(using Frame): Unit < Async =
        inFlight.get.map { n =>
            if n <= 0 then Kyo.unit
            else drainSignal.get.map(_.get.unit)
        }

    def closeEffect(
        gracePeriod: Duration,
        inFlight: AtomicInt,
        drainSignal: AtomicRef[Fiber.Promise[Unit, Any]],
        writerChannel: Channel[WriterMsg],
        writerFiber: Fiber[Unit, Sync],
        transport: JsonRpcTransport,
        exchange: Exchange[OutboundReq, Structure.Value, Nothing, JsonRpcError],
        callerRegistry: ConcurrentHashMap[JsonRpcId, CallerInfo],
        progressStreams: ConcurrentHashMap[Structure.Value, Channel[Structure.Value]],
        pendingInbound: ConcurrentHashMap[JsonRpcId, InboundEntry],
        meter: Maybe[Meter],
        initFrame: Frame
    )(using Frame): Unit < Async =
        closeImpl(
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

    def closeImpl(
        gracePeriod: Duration,
        inFlight: AtomicInt,
        drainSignal: AtomicRef[Fiber.Promise[Unit, Any]],
        writerChannel: Channel[WriterMsg],
        writerFiber: Fiber[Unit, Sync],
        transport: JsonRpcTransport,
        exchange: Exchange[OutboundReq, Structure.Value, Nothing, JsonRpcError],
        callerRegistry: ConcurrentHashMap[JsonRpcId, CallerInfo],
        progressStreams: ConcurrentHashMap[Structure.Value, Channel[Structure.Value]],
        pendingInbound: ConcurrentHashMap[JsonRpcId, InboundEntry],
        meter: Maybe[Meter],
        initFrame: Frame
    )(using Frame): Unit < Async =
        if gracePeriod == Duration.Zero then
            finalizer(
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
        else
            Abort.run[Timeout](Async.timeout(gracePeriod)(awaitDrainEffect(inFlight, drainSignal))).map { _ =>
                finalizer(
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
            }

    def finalizer(
        writerChannel: Channel[WriterMsg],
        writerFiber: Fiber[Unit, Sync],
        transport: JsonRpcTransport,
        exchange: Exchange[OutboundReq, Structure.Value, Nothing, JsonRpcError],
        callerRegistry: ConcurrentHashMap[JsonRpcId, CallerInfo],
        progressStreams: ConcurrentHashMap[Structure.Value, Channel[Structure.Value]],
        pendingInbound: ConcurrentHashMap[JsonRpcId, InboundEntry],
        meter: Maybe[Meter],
        initFrame: Frame
    )(using Frame): Unit < Async =
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
                        // Exchange bulk-fail of pending promises from finalizer; no safe equivalent in Exchange public API
                        exchange.unsafe.failAllPending(
                            JsonRpcLifecycleError(JsonRpcLifecycleError.Stage.Close)
                        )(using AllowUnsafe.embrace.danger)
                        callerRegistry.forEach { (_, info) =>
                            // promise completion called from outside originating fiber to signal abort or cancel; no safe equivalent in Promise public API
                            info.abortSignal.unsafe.completeDiscard(
                                Result.succeed(JsonRpcLifecycleError(JsonRpcLifecycleError.Stage.Close))
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
                                    // channel close or closeAwaitEmpty from finalizer or onComplete hook outside originating fiber; no safe equivalent
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

end LifecycleEngine
