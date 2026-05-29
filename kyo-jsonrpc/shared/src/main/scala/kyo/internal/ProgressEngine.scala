package kyo
package internal

// flow-allow: ConcurrentHashMap is the structural concurrent pending-map; no Kyo-safe equivalent for CAS-based inbound tracking
import java.util.concurrent.ConcurrentHashMap
import kyo.Maybe.Absent
import kyo.Maybe.Present

private[kyo] object ProgressEngine:

    /** Builds a per-invocation progress sink closure for a handler.
      * Returns Absent when no token is found in params or no progress policy is configured.
      * Must be called inside an unsafe-deferred block (AllowUnsafe in scope).
      * The closure captures a per-invocation monotonicity ref (per-invocation, not global).
      */
    def buildProgressSink(
        id: JsonRpcId,
        params: Maybe[Structure.Value],
        extras: Maybe[Structure.Value],
        progressPolicy: Maybe[ProgressPolicy],
        pendingInbound: ConcurrentHashMap[JsonRpcId, InboundEntry],
        writerChannel: Channel[WriterMsg]
    )(using frame: Frame, allow: AllowUnsafe): Maybe[Structure.Value => Unit < (Async & Abort[Closed])] =
        progressPolicy match
            case Absent => Absent
            case Present(policy) =>
                val paramsVal = params.getOrElse(Structure.Value.Null)
                // Unsafe: evalOrThrow runs Sync effect inside AllowUnsafe context
                val tokenOpt: Maybe[Structure.Value] =
                    Sync.Unsafe.evalOrThrow(policy.extractRequestToken(paramsVal))
                tokenOpt match
                    case Absent         => Absent
                    case Present(token) =>
                        // Per-invocation monotonicity ref; lives inside closure (not global)
                        // Unsafe: AtomicRef.Unsafe.init inside AllowUnsafe context
                        val monoRef = AtomicRef.Unsafe.init[Maybe[BigDecimal]](Absent).safe
                        val sink: Structure.Value => Unit < (Async & Abort[Closed]) =
                            value =>
                                Sync.defer(Maybe(pendingInbound.get(id))).map {
                                    case Present(_: InboundEntry.Running) =>
                                        val proceed: Boolean < Sync =
                                            if policy.enforceMonotonic then
                                                val newPct: Maybe[BigDecimal] =
                                                    value match
                                                        case Structure.Value.Record(fields) =>
                                                            Maybe.fromOption(
                                                                fields.iterator.collectFirst {
                                                                    case ("progress", Structure.Value.BigNum(n))  => n
                                                                    case ("progress", Structure.Value.Decimal(n)) => BigDecimal(n)
                                                                    case ("progress", Structure.Value.Integer(n)) => BigDecimal(n)
                                                                }
                                                            )
                                                        case _ => Absent
                                                newPct match
                                                    case Absent => true
                                                    case Present(newVal) =>
                                                        monoRef.get.map { current =>
                                                            current match
                                                                case Present(prev) if newVal <= prev =>
                                                                    false
                                                                case _ =>
                                                                    monoRef.compareAndSet(current, Present(newVal))
                                                        }
                                                end match
                                            else
                                                true
                                        proceed.map {
                                            case false => Kyo.unit
                                            case true =>
                                                policy.encodeProgressParams(token, value).map { encoded =>
                                                    val env = JsonRpcEnvelope.Notification(
                                                        policy.progressMethod,
                                                        Present(encoded),
                                                        extras
                                                    )
                                                    Abort.run[Closed](writerChannel.put(WriterMsg.SendEnvelope(env))).unit
                                                }
                                        }
                                    case _ => Kyo.unit
                                }
                        Present(sink)
                end match

end ProgressEngine
