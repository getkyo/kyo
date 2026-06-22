package kyo.internal.engine

// ConcurrentHashMap shared concurrent map; cross-platform via JS/Native JDK shim
import java.util.concurrent.ConcurrentHashMap
import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

private[kyo] object ProgressEngine:

    def subscribeProgressEffect(
        token: Structure.Value,
        progressPolicy: Maybe[JsonRpcProgressPolicy],
        progressStreams: ConcurrentHashMap[Structure.Value, Channel[Structure.Value]],
        initFrame: Frame
    )(using frame: Frame): Stream[Structure.Value, Async & Abort[Closed]] < Sync =
        progressPolicy match
            case Absent =>
                Stream(Abort.fail[Closed](Closed("progress not configured", initFrame)))
            case Present(_) =>
                // Eagerly create and register the channel so notifications can be routed before the stream is consumed.
                // Unsafe: channel init and ConcurrentHashMap putIfAbsent must happen at subscribe time.
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

    def unsubscribeProgressEffect(
        token: Structure.Value,
        progressStreams: ConcurrentHashMap[Structure.Value, Channel[Structure.Value]]
    )(using frame: Frame): Unit < Async =
        // Unsafe: remove from progressStreams and close channel from outside consumer fiber
        Sync.Unsafe.defer {
            Maybe(progressStreams.remove(token)) match
                case Absent      => ()
                case Present(ch) =>
                    // Unsafe: closeAwaitEmpty lets the consumer drain any items already in the channel
                    // before the channel transitions to FullyClosed; prevents item loss on unsubscribe.
                    // channel close or closeAwaitEmpty from finalizer or onComplete hook outside originating fiber; no safe equivalent
                    discard(ch.unsafe.closeAwaitEmpty()(using frame, AllowUnsafe.embrace.danger))
        }

    /** Allocates a unique progress token by generating random alphanumeric strings
      * and registering them with `progressStreams.putIfAbsent` until success.
      * Fails fast with `JsonRpcInternalError(Operation.Other, ...)`
      * after `maxAttempts` collisions (default 32).
      */
    private[kyo] def allocateProgressToken(
        progressStreams: ConcurrentHashMap[Structure.Value, Channel[Structure.Value]],
        channel: Channel[Structure.Value],
        maxAttempts: Int
    )(using Frame): Structure.Value < (Sync & Abort[JsonRpcError]) =
        def loop(attemptsLeft: Int): Structure.Value < (Sync & Abort[JsonRpcError]) =
            if attemptsLeft <= 0 then
                Abort.fail(JsonRpcInternalError(JsonRpcInternalError.Operation.Other, new RuntimeException("progress token exhaustion")))
            else
                Random.live.nextStringAlphanumeric(32).map { raw =>
                    val token: Structure.Value = Structure.Value.Str(raw)
                    // putIfAbsent returns Java reference; Absent means null (insert succeeded), Present means collision
                    Sync.defer(Maybe(progressStreams.putIfAbsent(token, channel))).map {
                        case Absent     => (token: Structure.Value)
                        case Present(_) => loop(attemptsLeft - 1)
                    }
                }
        loop(maxAttempts)
    end allocateProgressToken

    /** Builds a per-invocation progress sink closure for a handler.
      * Returns Absent when no token is found in params or no progress policy is configured.
      * Must be called inside an unsafe-deferred block (AllowUnsafe in scope).
      * The closure captures a per-invocation monotonicity ref (per-invocation, not global).
      */
    def buildProgressSink(
        id: JsonRpcId,
        params: Maybe[Structure.Value],
        extras: Maybe[Structure.Value],
        progressPolicy: Maybe[JsonRpcProgressPolicy],
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
                                                    case Absent          => true
                                                    case Present(newVal) =>
                                                        // CAS retry loop: a larger value that loses the initial CAS to a
                                                        // concurrent smaller value re-reads and re-evaluates rather than
                                                        // dropping, so the highest progress always reaches the wire. A bare
                                                        // compareAndSet returned false on a lost race and silently dropped
                                                        // the larger value, emitting only the smaller one.
                                                        def attempt(): Boolean < Sync =
                                                            monoRef.get.map { current =>
                                                                current match
                                                                    case Present(prev) if newVal <= prev =>
                                                                        false
                                                                    case _ =>
                                                                        monoRef.compareAndSet(current, Present(newVal))
                                                                            .map(won => if won then true else attempt())
                                                            }
                                                        attempt()
                                                end match
                                            else
                                                true
                                        proceed.map {
                                            case false => Kyo.unit
                                            case true =>
                                                policy.encodeProgressParams(token, value).map { encoded =>
                                                    val env = JsonRpcNotification(
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
