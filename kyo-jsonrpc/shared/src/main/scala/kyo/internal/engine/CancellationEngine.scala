package kyo.internal.engine

import java.util.concurrent.ConcurrentHashMap
import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

private[kyo] object CancellationEngine:

    private def extractCancelId(
        policy: JsonRpcCancellationPolicy,
        params: Maybe[Structure.Value]
    )(using Frame): Maybe[JsonRpcId] < Sync =
        params match
            case Absent      => Absent
            case Present(sv) => policy.decodeParams(sv)

    /** Test-accessible alias for extractCancelId. */
    private[kyo] def extractCancelIdForTest(
        policy: JsonRpcCancellationPolicy,
        params: Maybe[Structure.Value]
    )(using Frame): Maybe[JsonRpcId] < Sync =
        extractCancelId(policy, params)

    /** Handles an incoming cancel notification from the remote peer.
      * Called from the reader fiber's decode callback (Sync-only context).
      * Uses CAS on `pendingInbound` to transition Running -> Cancelled and interrupts the
      * handler fiber when the policy does not expect a reply for cancelled requests.
      */
    def handleInboundCancel(
        env: JsonRpcNotification,
        policy: JsonRpcCancellationPolicy,
        pendingInbound: ConcurrentHashMap[JsonRpcId, InboundEntry]
    )(using Frame): Unit < Sync =
        extractCancelId(policy, env.params).map {
            case Absent =>
                Log.warn(s"kyo-jsonrpc: inbound cancel notification missing id, dropping")
            case Present(id) =>
                Sync.defer {
                    Maybe(pendingInbound.get(id)) match
                        case Absent =>
                            // Log.live unsafe-warn inside deferred-sync block; no safe Log equivalent within Sync
                            // format: off
                            discard(Log.live.unsafe.warn(s"kyo-jsonrpc: inbound cancel for unknown id $id, dropping")(using summon[Frame], AllowUnsafe.embrace.danger))
                            // format: on
                        case Present(running: InboundEntry.Running) =>
                            val cancelled = InboundEntry.Cancelled(running.method)
                            if pendingInbound.replace(id, running, cancelled) then
                                // CAS-won path completes promise from outside originating fiber; no safe equivalent in Promise public API
                                running.cancelled.unsafe.completeUnitDiscard()(using AllowUnsafe.embrace.danger)
                                if !policy.expectReplyForCancelledRequest then
                                    // interrupt monitor/cleanup fiber from outside its scheduler; no safe equivalent in Fiber public API
                                    running.handler.unsafe.interruptDiscard(
                                        Result.Panic(Interrupted(summon[Frame]))
                                    )(using AllowUnsafe.embrace.danger)
                                end if
                            else
                                // CAS lost: handler transitioned to Replying before we got here
                                Maybe(pendingInbound.get(id)) match
                                    case Present(r: InboundEntry.Replying) =>
                                        // suppress-flag access from Sync-only Exchange callback; no safe Atomic equivalent inside Sync block
                                        r.suppress.unsafe.set(true)(using AllowUnsafe.embrace.danger)
                                    case _ => ()
                                end match
                            end if
                        case Present(r: InboundEntry.Replying) =>
                            // Cancel arrived after handler completed; set suppress so writer drops the reply
                            // suppress-flag access from Sync-only Exchange callback; no safe Atomic equivalent inside Sync block
                            r.suppress.unsafe.set(true)(using AllowUnsafe.embrace.danger)
                        case Present(_: InboundEntry.Cancelled) =>
                            // Idempotent: already cancelled
                            ()
                }
        }

    /** Builds and enqueues the outbound cancel notification for a call we issued.
      * The caller is responsible for the absent-check on `callerRegistry` and the
      * `protectedMethods` check before invoking this helper.
      */
    def buildAndEnqueueOutboundCancel(
        id: JsonRpcId,
        reason: Maybe[String],
        info: CallerInfo,
        policy: JsonRpcCancellationPolicy,
        writerChannel: Channel[WriterMsg]
    )(using Frame): Unit < (Async & Abort[Closed]) =
        policy.encodeParams(id, reason).map { params =>
            val cancelEnv = JsonRpcNotification(policy.cancelMethod, Present(params), info.extras)
            writerChannel.put(WriterMsg.SendEnvelope(cancelEnv))
        }

    /** Handles timeout auto-fire: fires the cancel notification (if policy present) and completes the abortSignal. */
    def handleTimeout(
        id: JsonRpcId,
        reason: Maybe[String],
        cancellation: Maybe[JsonRpcCancellationPolicy],
        callerRegistry: ConcurrentHashMap[JsonRpcId, CallerInfo],
        writerChannel: Channel[WriterMsg]
    )(using Frame): Unit < (Async & Abort[Closed]) =
        Sync.defer(Maybe(callerRegistry.get(id))).map {
            case Absent => Kyo.unit
            case Present(info) =>
                val abortError = cancellation match
                    case Present(p) => p.cancelledError.getOrElse(JsonRpcCustomError(-32800, reason.getOrElse("Request cancelled")))
                    case Absent     => JsonRpcCustomError(-32800, reason.getOrElse("Request cancelled"))
                cancellation match
                    case Present(policy) =>
                        buildAndEnqueueOutboundCancel(id, reason, info, policy, writerChannel).andThen {
                            // CAS-won path completes promise from outside originating fiber; no safe equivalent in Promise public API
                            Sync.Unsafe.defer {
                                info.abortSignal.unsafe.completeDiscard(
                                    Result.succeed(abortError)
                                )(using AllowUnsafe.embrace.danger)
                            }
                        }
                    case Absent =>
                        // No policy: abort locally only, no wire notification
                        // CAS-won path completes promise from outside originating fiber; no safe equivalent in Promise public API
                        Sync.Unsafe.defer {
                            info.abortSignal.unsafe.completeDiscard(
                                Result.succeed(abortError)
                            )(using AllowUnsafe.embrace.danger)
                        }
                end match
        }

end CancellationEngine
