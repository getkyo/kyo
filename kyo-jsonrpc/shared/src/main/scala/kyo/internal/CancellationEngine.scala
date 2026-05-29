package kyo
package internal

// flow-allow: ConcurrentHashMap is the structural concurrent pending-map; no Kyo-safe equivalent for CAS-based inbound tracking
import java.util.concurrent.ConcurrentHashMap
import kyo.Maybe.Absent
import kyo.Maybe.Present

private[kyo] object CancellationEngine:

    private case class LspCancelParams(id: JsonRpcId) derives Schema, CanEqual
    private case class McpCancelParams(requestId: JsonRpcId, reason: Maybe[String]) derives Schema, CanEqual

    private def extractCancelId(
        policy: CancellationPolicy,
        params: Maybe[Structure.Value]
    )(using Frame): Maybe[JsonRpcId] =
        params.flatMap { sv =>
            if policy.cancelMethod == "$/cancelRequest" then
                Structure.decode[LspCancelParams](sv) match
                    case Result.Success(p) => Present(p.id)
                    case _                 => Absent
            else
                Structure.decode[McpCancelParams](sv) match
                    case Result.Success(p) => Present(p.requestId)
                    case _                 => Absent
        }

    /** Handles an incoming cancel notification from the remote peer.
      * Called from the reader fiber's decode callback (Sync-only context).
      * Implements the CAS sequence from DESIGN §6.5.
      */
    def handleInboundCancel(
        env: JsonRpcEnvelope.Notification,
        policy: CancellationPolicy,
        pendingInbound: ConcurrentHashMap[JsonRpcId, InboundEntry]
    )(using Frame): Unit < Sync =
        extractCancelId(policy, env.params) match
            case Absent =>
                Log.warn(s"kyo-jsonrpc: inbound cancel notification missing id, dropping")
            case Present(id) =>
                Sync.defer {
                    Maybe(pendingInbound.get(id)) match
                        case Absent =>
                            // flow-allow: Log.live unsafe-warn inside deferred-sync block; no safe Log equivalent within Sync
                            // format: off
                            discard(Log.live.unsafe.warn(s"kyo-jsonrpc: inbound cancel for unknown id $id, dropping")(using summon[Frame], AllowUnsafe.embrace.danger))
                            // format: on
                        case Present(running: InboundEntry.Running) =>
                            val cancelled = InboundEntry.Cancelled(running.method)
                            if pendingInbound.replace(id, running, cancelled) then
                                // flow-allow: CAS-won path completes promise from outside originating fiber; no safe equivalent in Promise public API
                                running.cancelled.unsafe.completeUnitDiscard()(using AllowUnsafe.embrace.danger)
                                if !policy.expectReplyForCancelledRequest then
                                    // flow-allow: interrupt monitor/cleanup fiber from outside its scheduler; no safe equivalent in Fiber public API
                                    running.handler.unsafe.interruptDiscard(
                                        Result.Panic(Interrupted(summon[Frame]))
                                    )(using AllowUnsafe.embrace.danger)
                                end if
                            else
                                // CAS lost: handler transitioned to Replying before we got here
                                Maybe(pendingInbound.get(id)) match
                                    case Present(r: InboundEntry.Replying) =>
                                        // flow-allow: suppress-flag access from Sync-only Exchange callback; no safe Atomic equivalent inside Sync block
                                        r.suppress.unsafe.set(true)(using AllowUnsafe.embrace.danger)
                                    case _ => ()
                                end match
                            end if
                        case Present(r: InboundEntry.Replying) =>
                            // Cancel arrived after handler completed; set suppress so writer drops the reply
                            // flow-allow: suppress-flag access from Sync-only Exchange callback; no safe Atomic equivalent inside Sync block
                            r.suppress.unsafe.set(true)(using AllowUnsafe.embrace.danger)
                        case Present(_: InboundEntry.Cancelled) =>
                            // Idempotent: already cancelled
                            ()
                }

    /** Builds and enqueues the outbound cancel notification for a call we issued.
      * Steps 3-4 of DESIGN §7 outbound flow.
      * The caller is responsible for the absent-check (step 2) and protectedMethods check (also step 2).
      */
    def buildAndEnqueueOutboundCancel(
        id: JsonRpcId,
        reason: Maybe[String],
        info: CallerInfo,
        policy: CancellationPolicy,
        writerChannel: Channel[WriterMsg]
    )(using Frame): Unit < (Async & Abort[Closed]) =
        policy.encodeParams(id, reason).map { params =>
            val cancelEnv = JsonRpcEnvelope.Notification(policy.cancelMethod, Present(params), info.extras)
            writerChannel.put(WriterMsg.SendEnvelope(cancelEnv))
        }

    /** Handles timeout auto-fire: fires the cancel notification (if policy present) and completes the abortSignal.
      * DESIGN §7 "Timeout -> cancellation auto-fire".
      */
    def handleTimeout(
        id: JsonRpcId,
        reason: Maybe[String],
        cancellation: Maybe[CancellationPolicy],
        callerRegistry: ConcurrentHashMap[JsonRpcId, CallerInfo],
        writerChannel: Channel[WriterMsg]
    )(using Frame): Unit < (Async & Abort[Closed]) =
        Sync.defer(Maybe(callerRegistry.get(id))).map {
            case Absent => Kyo.unit
            case Present(info) =>
                val abortError = cancellation match
                    case Present(p) => p.cancelledError.getOrElse(JsonRpcError.cancelled(reason))
                    case Absent     => JsonRpcError.cancelled(reason)
                cancellation match
                    case Present(policy) =>
                        buildAndEnqueueOutboundCancel(id, reason, info, policy, writerChannel).andThen {
                            // flow-allow: CAS-won path completes promise from outside originating fiber; no safe equivalent in Promise public API
                            Sync.Unsafe.defer {
                                info.abortSignal.unsafe.completeDiscard(
                                    Result.succeed(abortError)
                                )(using AllowUnsafe.embrace.danger)
                            }
                        }
                    case Absent =>
                        // No policy: abort locally only, no wire notification
                        // flow-allow: CAS-won path completes promise from outside originating fiber; no safe equivalent in Promise public API
                        Sync.Unsafe.defer {
                            info.abortSignal.unsafe.completeDiscard(
                                Result.succeed(abortError)
                            )(using AllowUnsafe.embrace.danger)
                        }
                end match
        }

end CancellationEngine
