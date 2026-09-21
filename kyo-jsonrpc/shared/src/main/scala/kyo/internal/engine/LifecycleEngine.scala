package kyo.internal.engine

// ConcurrentHashMap shared concurrent map; cross-platform via JS/Native JDK shim
import java.util.concurrent.ConcurrentHashMap
import kyo.*

private[kyo] object LifecycleEngine:

    /** What a close acts on. */
    final case class Resources(
        work: WorkTracker,
        deliveries: WorkTracker,
        registry: InboundRegistry,
        writerChannel: Channel[WriterMsg],
        writerFiber: Fiber[Unit, Any],
        transport: JsonRpcTransport,
        exchange: Exchange[OutboundReq, Structure.Value, Nothing, JsonRpcError],
        callerRegistry: ConcurrentHashMap[JsonRpcId, CallerInfo],
        progressStreams: ConcurrentHashMap[Structure.Value, Channel[Structure.Value]],
        meter: Maybe[Meter],
        closeStarted: AtomicBoolean,
        closeDone: Fiber.Promise[Unit, Any]
    )

    /** Closes the handler: waits up to `gracePeriod` for in-flight work (outbound calls and inbound handlers) to drain, then finalizes. */
    def closeEffect(gracePeriod: Duration, resources: Resources)(using Frame): Unit < Async =
        if gracePeriod == Duration.Zero then finalizer(resources)
        else
            Abort.run[Timeout](Async.timeout(gracePeriod)(resources.work.awaitIdle)).andThen(finalizer(resources))

    /** Tears the handler down once; a concurrent or later call waits for the first to finish.
      *
      * Output that was accepted before the close is written before the transport closes: replies settled before the close, and
      * notifications and cancel notifications already in the writer channel. Everything still running is stopped.
      */
    def finalizer(resources: Resources)(using Frame): Unit < Async =
        import resources.*
        closeStarted.compareAndSet(false, true).map { first =>
            if !first then closeDone.get
            else
                Sync.ensure(closeDone.completeUnitDiscard) {
                    // Step 1: admit no new inbound work and abort every running inbound request and notification handler,
                    // completing each one's ctx.cancelled before interrupting it. Replies already settled keep their delivery.
                    // Unsafe: bulk abort of inbound handlers from outside their fibers
                    Sync.Unsafe.defer(registry.closeAll())
                        // Step 2: fail outbound callers while the transport is still open (see failOutbound).
                        .andThen(failOutbound(resources))
                        // Step 3: wait until every answer produced before the close is in the writer channel: the answers admission
                        // produced (gate rejections, unknown methods, duplicate ids) and the replies handlers settled, which step 1
                        // counted. A handler settling concurrently with step 1 either lost to its abort (no reply) or was counted.
                        .andThen(deliveries.awaitIdle)
                        .andThen(registry.replies.awaitIdle)
                        // Step 4: stop accepting output and let the writer write what is queued. The writer ends once the channel
                        // is closed and empty; if it already died, there is nothing more it can write.
                        // Unsafe: closeAwaitEmpty is started without awaiting its drain promise; the writer fiber is what is awaited
                        .andThen(Sync.Unsafe.defer(discard(writerChannel.unsafe.closeAwaitEmpty())))
                        .andThen(writerFiber.getResult.unit)
                        // Step 5: fail callers that registered while the writer was flushing, then release everything else.
                        .andThen(failOutbound(resources))
                        .andThen(transport.close)
                        // Exchange.close sets its done promise to Closed for future calls; its pending map is already empty.
                        .andThen(exchange.close)
                        .andThen {
                            // Unsafe: bulk-close progress channels from outside the consumers' fibers
                            Sync.Unsafe.defer {
                                progressStreams.forEach { (_, ch) =>
                                    // channel close from the finalizer, outside the consumer fiber; no safe equivalent
                                    discard(ch.unsafe.close())
                                }
                                progressStreams.clear()
                            }
                        }
                        .andThen {
                            // Release the semaphore so callers parked on maxInFlight unblock.
                            meter match
                                case Absent     => Kyo.unit
                                case Present(m) => m.close.unit
                        }
                }
        }
    end finalizer

    // Fails every Exchange pending promise with a JsonRpcError (not Closed) so a call's raceFirst resolves on the JsonRpcError path,
    // then completes each abortSignal. This must precede transport.close: closing the transport ends the receive stream, and the
    // Exchange reader's clean-stream-end path would otherwise fail the same pending promises with Closed and win the caller's
    // raceFirst, leaking a raw Closed for a call the contract says drains as JsonRpcError. Promise completion is idempotent, so the
    // later reader-end completion is a no-op. Calls not yet registered when Exchange.close runs still see Closed via its done check.
    private def failOutbound(resources: Resources)(using Frame): Unit < Sync =
        import resources.*
        // Unsafe: bulk-fail and complete from outside originating fibers
        Sync.Unsafe.defer {
            // Exchange bulk-fail of pending promises from finalizer; no safe equivalent in Exchange public API
            exchange.unsafe.failAllPending(JsonRpcLifecycleError(JsonRpcLifecycleError.Stage.Close))
            callerRegistry.forEach { (_, info) =>
                // promise completion called from outside originating fiber to signal abort or cancel; no safe equivalent in Promise public API
                info.abortSignal.unsafe.completeDiscard(Result.succeed(JsonRpcLifecycleError(JsonRpcLifecycleError.Stage.Close)))
            }
            callerRegistry.clear()
        }
    end failOutbound

end LifecycleEngine
