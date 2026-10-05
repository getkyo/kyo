package kyo.internal.slack

import kyo.*

/** The Socket Mode receive engine: bounded `inbound`/`outbound` Channels, a sender fiber,
  * and a relay fiber that runs the transport connect body (the readiness gate awaited
  * during `initUnscoped` is a local promise, not a field). The receive loop decodes each
  * inbound frame via `Wire`, delivers the typed envelope to the handler, and emits
  * exactly one wire ack from the handler's returned `SlackAck`, bounding the handler by
  * `ackDeadline` so the ack always goes out within Slack's window.
  *
  * The connect body forwards over the live socket on two independent fibers. The SENDER
  * fiber drains `outbound` and forwards every ack to the socket; it is NOT a race leg, so
  * an inbound-side event cannot interrupt it mid-forward. A teardown flushes it
  * deterministically: close `outbound`, then await `senderDone`, so every buffered ack
  * reaches the socket before it closes. The RELAY runs the receiver (copying the socket
  * stream into `inbound`) raced with `onPeerClose`, so it resolves promptly on an abnormal
  * drop. Because the sender is off the race, that resolution never cuts a flush short.
  *
  * `hello` is delivered first and is not acked; `disconnect(link_disabled)` ends the
  * loop with `SlackLinkDisabledException`; a routine disconnect ends the loop cleanly. An
  * abnormal peer close (transport EOF with no disconnect frame) closes `inbound`; the
  * loop reads that as a routine drop and rotates per policy (or ends under `Off`),
  * distinguished from an intentional teardown by `intentionalClose`.
  *
  * Closing `inbound` is the single idempotent `closeInbound`, shared by the relay's raced
  * completion and the rotation drain, so exactly one close captures the residue whichever
  * path wins.
  */
final private[kyo] class SocketEngine private[kyo] (
    private[kyo] val conn: Transport.Conn,
    private[kyo] val outbound: Channel[String],
    private[kyo] val inbound: Channel[String],
    // Forwards every `outbound` ack to the socket; its own fiber, never a race leg, so an
    // inbound-side event cannot interrupt it mid-forward. A teardown interrupts it only
    // after the flush has completed (see `senderDone`).
    private[kyo] val sender: Fiber[Unit, Sync],
    // Runs the receiver (socket stream into `inbound`) raced with `onPeerClose`, so it
    // resolves promptly on an abnormal drop. Independent of the sender.
    private[kyo] val relay: Fiber[Unit, Sync],
    private[kyo] val ackDeadline: Duration,
    // Set before any teardown closes `inbound`, so the receive loop tells an intentional
    // close (Stop) apart from an abnormal peer drop (rotate per policy / end under Off).
    private[kyo] val intentionalClose: AtomicBoolean,
    // Completed once, by the single `closeInbound` that wins the channel close, with the
    // buffered residue that one `close` returned (possibly empty). The rotation drain
    // awaits it so it delivers exactly the captured residue regardless of which path won
    // the close; single-completion makes a later `closeInbound` a no-op.
    private[kyo] val inboundResidue: Fiber.Promise[Chunk[String], Any],
    // Completed by the sender fiber when its `outbound` stream ends, i.e. after a teardown
    // closed `outbound` and the sender forwarded EVERY buffered ack to the socket.
    // `closeTransport` awaits it so the socket is closed only after the last ack was sent,
    // never with an ack the sender had polled but not yet put (the loss an interrupt
    // mid-forward would cause).
    private[kyo] val senderDone: Fiber.Promise[Unit, Any]
):

    /** Run the receive loop under the reconnect controller: on a routine
      * `disconnect(warning|refresh_requested)` it RETURNS a `Reaction` the controller
      * acts on (rotate per policy) rather than ending; `link_disabled` aborts
      * terminal; a closed inbound channel (peer gone or engine torn down) is a clean
      * `Stop`. During the overlap window it consults `dedup` so a re-pushed id is
      * acked but not re-delivered. One frame per `Loop` iteration.
      */
    private[kyo] def receiveLoopWithReconnect[E, S](
        using Isolate[S, Abort[E] & Async, S]
    )(
        handler: SlackEnvelope[?] => SlackAck < (S & Async & Abort[E]),
        dedup: Reconnect.OverlapDedup,
        onRoutineDisconnect: SlackEnvelope.DisconnectReason => Reconnect.Reaction
    )(using Frame): Reconnect.Reaction < (S & Async & Abort[SlackLinkDisabledException | E]) =
        Loop.foreach {
            Abort.run[Closed](inbound.take).map {
                case Result.Failure(_: Closed) =>
                    // inbound closed. An intentional teardown stops the loop; an abnormal peer
                    // drop (the relay closed inbound on its raced completion) is treated like a
                    // routine disconnect, so the controller rotates per policy (or ends under Off)
                    // instead of hanging on a frame that will never arrive.
                    intentionalClose.get.map { intentional =>
                        Loop.done[Unit, Reconnect.Reaction](
                            if intentional then Reconnect.Reaction.Stop
                            else onRoutineDisconnect(SlackEnvelope.DisconnectReason.Unspecified)
                        )
                    }
                case Result.Success(frame) =>
                    Wire.decode(frame).map {
                        case Wire.Decoded.Skip(reason) =>
                            Log.warn(s"SocketEngine: skipping uncorrelatable frame: $reason").andThen(Loop.continue[Reconnect.Reaction])
                        case Wire.Decoded.Envelope(env, ackable) =>
                            env match
                                case SlackEnvelope.Disconnect(SlackEnvelope.DisconnectReason.LinkDisabled) =>
                                    runHandler(handler, env).andThen(Abort.fail(SlackLinkDisabledException()))
                                case SlackEnvelope.Disconnect(reason) =>
                                    runHandler(handler, env).andThen(Loop.done[Unit, Reconnect.Reaction](onRoutineDisconnect(reason)))
                                case _: SlackEnvelope.Hello | _: SlackEnvelope.EventsApi | _: SlackEnvelope.Interactive |
                                    _: SlackEnvelope.SlashCommand | _: SlackEnvelope.Unknown | _: SlackEnvelope.UnknownFrame =>
                                    deliverWithDedup(handler, env, ackable, dedup).andThen(Loop.continue[Reconnect.Reaction])
                    }
                case Result.Panic(ex) =>
                    // `take` fails only with `Closed`; anything else is a defect (or an
                    // interruption), so it keeps propagating as a panic.
                    Abort.panic(ex)
            }
        }
    end receiveLoopWithReconnect

    /** Deliver-and-ack with the overlap-window dedup: if the id was already acknowledged
      * in this window, emit the ack (so Slack stops re-pushing) but do NOT invoke the
      * handler again; otherwise deliver, and remember the id once its delivery produced an
      * ack. An id whose delivery produced none (its handler panicked) stays unremembered, so
      * a push of the same id is delivered again, whether or not Slack reuses the id on a
      * redelivery. An id-less envelope (no `envelope_id`) bypasses the dedup.
      */
    private def deliverWithDedup[E, S](
        using Isolate[S, Abort[E] & Async, S]
    )(
        handler: SlackEnvelope[?] => SlackAck < (S & Async & Abort[E]),
        env: SlackEnvelope[?],
        ackable: Boolean,
        dedup: Reconnect.OverlapDedup
    )(using Frame): Unit < (S & Async & Abort[SlackLinkDisabledException | E]) =
        acknowledgedOf(env) match
            case Present(acknowledged) =>
                val id = acknowledged.envelopeId
                dedup.seenBefore(id).map { already =>
                    if already then emitAck(env, SlackAck.Ack).unit
                    else
                        deliverAndAck(handler, env, ackable).map { acked =>
                            if acked then dedup.remember(id) else Kyo.unit
                        }
                }
            case Absent =>
                deliverAndAck(handler, env, ackable).unit
    end deliverWithDedup

    /** Deliver one envelope that is not a disconnect, and answer whether its ack went out. */
    private def deliverAndAck[E, S](
        using Isolate[S, Abort[E] & Async, S]
    )(
        handler: SlackEnvelope[?] => SlackAck < (S & Async & Abort[E]),
        env: SlackEnvelope[?],
        ackable: Boolean
    )(using Frame): Boolean < (S & Async & Abort[SlackLinkDisabledException | E]) =
        if !ackable then runHandler(handler, env).andThen(false)
        else
            // Bound the handler by ackDeadline so exactly one ack always goes out within
            // Slack's window. `raceFirst` returns the first leg to finish and interrupts the
            // other: the handler's ack if it returned first, the bare ack if the deadline fired
            // first (the interrupted handler's late ack never goes out), and none if it panicked.
            // A typed failure, or this fiber's interruption, propagates, so no stray ack goes out.
            Async.raceFirst(
                runHandler(handler, env).map(SocketEngine.Delivery.Handled(_)),
                Async.sleep(ackDeadline).andThen(SocketEngine.Delivery.Late)
            ).map {
                case SocketEngine.Delivery.Handled(returned) =>
                    returned match
                        case Present(ack) => emitAck(env, ack)
                        case Absent       => false
                case SocketEngine.Delivery.Late => emitAck(env, SlackAck.Ack)
            }

    /** The one place a handler runs, so every envelope follows one outcome policy. A typed
      * failure propagates and ends the loop. A panic is logged with the envelope's type and id
      * and answers `Absent`: the envelope is left unacknowledged, so Slack redelivers it, and
      * the loop goes on, since ending it would let one poisoned envelope stop the bot on every
      * redelivery. An `Interrupted` panic is an interruption the handler observed, not a
      * defect, so it propagates.
      */
    private def runHandler[E, S](
        handler: SlackEnvelope[?] => SlackAck < (S & Async & Abort[E]),
        env: SlackEnvelope[?]
    )(using Frame): Maybe[SlackAck] < (S & Async & Abort[E]) =
        Abort.run[Nothing](handler(env)).map {
            case Result.Success(ack)           => Present(ack)
            case Result.Panic(ex: Interrupted) => Abort.panic(ex)
            case Result.Panic(ex)              => Log.error(s"SocketEngine: handler panicked on ${describe(env)}", ex).andThen(Absent)
            case Result.Failure(never)         => never
        }

    private def describe(env: SlackEnvelope[?]): String =
        env match
            case e: SlackEnvelope.EventsApi    => s"events_api envelope ${e.envelopeId.value}"
            case e: SlackEnvelope.Interactive  => s"interactive envelope ${e.envelopeId.value}"
            case e: SlackEnvelope.SlashCommand => s"slash_commands envelope ${e.envelopeId.value}"
            case _: SlackEnvelope.Hello        => "hello envelope"
            case _: SlackEnvelope.Disconnect   => "disconnect envelope"
            case e: SlackEnvelope.Unknown      => s"${e.`type`} envelope ${e.envelopeId.value}"
            case e: SlackEnvelope.UnknownFrame => s"${e.`type`} frame"

    /** Emit exactly one wire ack from the returned `SlackAck` for an acked envelope. Answers
      * whether the ack went out.
      *
      * `outbound` is closed only by `closeTransport` and `closeNow`, and both set
      * `intentionalClose` first, so a `Closed` put means this engine was torn down while the
      * handler ran: the ack is dropped with the connection, and the loop ends on its next take
      * of the closed `inbound`.
      *
      * Socket Mode says `accepts_response_payload` determines whether a response can include a
      * payload, so an envelope that says `false` gets the bare ack for a payload-bearing `SlackAck`,
      * and the line names the kind withheld, never its content. An envelope that does not say gets
      * the payload the handler asked for.
      */
    private[kyo] def emitAck(env: SlackEnvelope[?], ack: SlackAck)(using Frame): Boolean < Async =
        acknowledgedOf(env) match
            case Absent                => false
            case Present(acknowledged) =>
                val withheld: Maybe[String] =
                    if acknowledged.acceptsResponsePayload != Present(false) then Absent
                    else
                        ack match
                            case _: SlackAck.CommandResponse => Present("CommandResponse")
                            case _: SlackAck.ViewResponse    => Present("ViewResponse")
                            case SlackAck.Ack                => Absent
                val id    = acknowledged.envelopeId
                val frame = if withheld.isEmpty then Wire.encodeAck(id, ack) else Wire.encodeAck(id, SlackAck.Ack)
                Abort.run[Closed](outbound.put(frame)).map {
                    case Result.Success(_) =>
                        withheld match
                            case Present(kind) =>
                                Log.warn(
                                    s"SocketEngine: envelope ${id.value} does not accept a response payload; the $kind payload was not sent"
                                ).andThen(true)
                            case Absent => true
                    case Result.Failure(_) => false
                    case Result.Panic(ex)  => Abort.panic(ex)
                }

    private def acknowledgedOf(env: SlackEnvelope[?]): Maybe[SlackEnvelope.Acknowledged] =
        env match
            case e: SlackEnvelope.Acknowledged => Present(e)
            case _: SlackEnvelope.Plain        => Absent

    /** Close `inbound` exactly once and publish its buffered residue, idempotently. The
      * first caller does the plain channel `close` (which fails the loop's pending take
      * with `Closed`, so the receive loop observes a clean end with no consumer required)
      * and completes `inboundResidue` with the returned residue; any later caller's `close`
      * returns `Absent` (the channel is already fully closed) and the already-completed
      * promise rejects the redundant completion.
      *
      * Both the relay's raced completion and the controller's rotation drain call this, so
      * they share one close rather than racing a plain `close` against a `closeAwaitEmpty`
      * that would leave the channel waiting for a consumer the rotation has stopped being.
      */
    private[kyo] def closeInbound(using Frame): Unit < Async =
        SocketEngine.closeInbound(inbound, inboundResidue)

    /** Stop receiving and drain the already-buffered inbound residue through the SAME
      * decode + dedup + deliver + ack path the receive loop uses, so every envelope the
      * socket already delivered (frames Slack handed to this connection before the loop
      * switched away) is delivered exactly once and acked before the socket is torn down.
      *
      * `closeInbound` closes the channel once (failing any pending take with `Closed`, so
      * the residue set is final) and publishes the buffered residue; this drain awaits the
      * `inboundResidue` promise (completed by whichever path won the close) and delivers it.
      * A residue frame whose id was already delivered (e.g. carried into the dedup `prior`
      * window by an `advance` before this drain) is acked but not re-delivered; a routine
      * disconnect in the residue is delivered without triggering another rotation (this
      * engine is being torn down); `link_disabled` stays terminal. The sender fiber is left
      * alive so the drain's acks go out on THIS engine's socket; the caller flushes those
      * acks and closes the socket and fibers after the drain (see `closeTransport`).
      */
    private[kyo] def drainBufferedInbound[E, S](
        using Isolate[S, Abort[E] & Async, S]
    )(
        handler: SlackEnvelope[?] => SlackAck < (S & Async & Abort[E]),
        dedup: Reconnect.OverlapDedup
    )(using Frame): Unit < (S & Async & Abort[SlackLinkDisabledException | E]) =
        // This engine is being torn down at a rotation: mark the close intentional so a
        // concurrent reader would Stop, not rotate, on the resulting Closed. Close inbound
        // once (publishing the residue if the relay's raced completion has not already),
        // then await the published residue and deliver it. closeInbound always runs before
        // the await, so the promise is complete by the time the drain reads it.
        intentionalClose.set(true).andThen {
            closeInbound.andThen {
                inboundResidue.get.map(residue => Kyo.foreachDiscard(residue)(drainFrame(handler, _, dedup)))
            }
        }

    private def drainFrame[E, S](
        using Isolate[S, Abort[E] & Async, S]
    )(
        handler: SlackEnvelope[?] => SlackAck < (S & Async & Abort[E]),
        frame: String,
        dedup: Reconnect.OverlapDedup
    )(using Frame): Unit < (S & Async & Abort[SlackLinkDisabledException | E]) =
        Wire.decode(frame).map {
            case Wire.Decoded.Skip(reason) =>
                Log.warn(s"SocketEngine: skipping uncorrelatable residue frame: $reason")
            case Wire.Decoded.Envelope(env, ackable) =>
                env match
                    case SlackEnvelope.Disconnect(SlackEnvelope.DisconnectReason.LinkDisabled) =>
                        runHandler(handler, env).andThen(Abort.fail(SlackLinkDisabledException()))
                    case SlackEnvelope.Disconnect(_) =>
                        // Already rotating away from this engine: deliver, do not re-rotate.
                        runHandler(handler, env).unit
                    case _: SlackEnvelope.Hello | _: SlackEnvelope.EventsApi | _: SlackEnvelope.Interactive | _: SlackEnvelope.SlashCommand |
                        _: SlackEnvelope.Unknown | _: SlackEnvelope.UnknownFrame =>
                        deliverWithDedup(handler, env, ackable, dedup)
        }

    /** Tear down the socket and fibers AFTER a drain: flush the outbound acks the drain
      * emitted (so they reach the socket while it is still live), close the socket, then
      * interrupt the sender and relay and await their full stop so the old socket is gone
      * before any slot is reused. The inbound channel is already closed by the drain.
      * Idempotent.
      *
      * The flush is the ordering contract: close `outbound` (`closeAwaitEmpty` lets the
      * sender drain the buffer first), then await `senderDone`, which the sender completes
      * only after its `outbound` stream fully ended, i.e. after the last ack's `conn.put`
      * returned. Awaiting `senderDone` rather than just `closeAwaitEmpty` is what makes the
      * flush complete: `closeAwaitEmpty` returns once the channel is empty (the sender polled
      * the last ack) but the sender may still be mid-`conn.put`. Because the sender is its own
      * fiber, no inbound-side event can interrupt it before `senderDone`, so every ack the
      * drain produced reaches the live socket. The sender recovers a `Closed` `conn.put` (an
      * already-gone socket) to a clean stream end, but a live peer that stops reading keeps it
      * suspended in `conn.put`, so the flush is bounded by `ackDeadline`: an ack sent later is
      * past Slack's acknowledgement window, and Slack redelivers its envelope. Then the socket
      * is closed and both fibers interrupted, flushed or not. `closeNow` is the equivalent
      * unconditional final teardown.
      */
    private[kyo] def closeTransport(using Frame): Unit < Async =
        intentionalClose.set(true)
            .andThen(Abort.run[Timeout](Async.timeout(ackDeadline)(outbound.closeAwaitEmpty.unit.andThen(senderDone.get))).unit)
            .andThen(conn.close)
            .andThen(sender.interrupt.andThen(sender.getResult.unit))
            .andThen(relay.interrupt.andThen(relay.getResult.unit))
            .andThen(outbound.close.unit)
            .andThen(closeInbound)

    /** Final teardown (scope finalizer / `Slack.close`): close the socket and
      * interrupt the sender and relay, awaiting their full stop so the old socket is gone
      * before any slot is reused. No residue drain runs here, so the socket is closed first
      * and any unsent acks are dropped (the connection is going away). Idempotent; total.
      */
    private[kyo] def closeNow(using Frame): Unit < Async =
        intentionalClose.set(true)
            .andThen(conn.close)
            .andThen(sender.interrupt.andThen(sender.getResult.unit))
            .andThen(relay.interrupt.andThen(relay.getResult.unit))
            .andThen(outbound.close.unit)
            .andThen(closeInbound)

end SocketEngine

private[kyo] object SocketEngine:

    /** How a delivery's race against `ackDeadline` ended: the handler finished (with its ack, or
      * `Absent` when it panicked), or the deadline fired first.
      */
    private enum Delivery derives CanEqual:
        case Handled(ack: Maybe[SlackAck])
        case Late
    end Delivery

    /** Frames each direction buffers. Bounded so a loop that falls behind stops the receiver reading the socket, and a
      * stalled socket stops the loop's acks, instead of either buffering without limit; the size itself is not tuned.
      */
    inline val FrameCapacity = 64

    /** Close `inbound` once and publish its residue into `residue`: the first caller's plain `close` returns
      * the buffered frames and completes the promise; a later call's `close` is `Absent` and changes nothing.
      * A plain close fails a pending take with `Closed` and needs no consumer, so a rotation that stopped
      * reading never leaves the channel half-closed.
      *
      * The close and the completion run uninterruptibly: `closeNow` interrupts the relay, which may be inside
      * this call, and an interrupt between the two would leave the channel closed with the residue never
      * published, so a later drain awaiting it would wait forever.
      */
    private[kyo] def closeInbound(inbound: Channel[String], residue: Fiber.Promise[Chunk[String], Any])(using
        Frame
    ): Unit < Async =
        Async.uninterruptible {
            inbound.close.map {
                case Present(frames) => residue.complete(Result.succeed(Chunk.from(frames))).unit
                case Absent          => Kyo.unit
            }
        }

    /** Open one engine over the given transport and wss url: bounded channels, the
      * readiness gate, the sender fiber draining `outbound` to the socket, and the relay
      * fiber running the receiver raced with `onPeerClose`. Awaits the readiness gate before
      * returning, so the caller proceeds only on a live connection.
      */
    private[kyo] def initUnscoped(
        transport: Transport,
        wsUrl: HttpUrl,
        config: SlackConfig
    )(using Frame): SocketEngine < (Async & Abort[SlackTransportException]) =
        for
            outbound         <- Channel.initUnscoped[String](FrameCapacity)
            inbound          <- Channel.initUnscoped[String](FrameCapacity)
            connectReady     <- Fiber.Promise.init[(Transport.Conn, Fiber[Unit, Sync]), Abort[SlackTransportException]]
            intentionalClose <- AtomicBoolean.init(false)
            inboundResidue   <- Fiber.Promise.init[Chunk[String], Any]
            senderDone       <- Fiber.Promise.init[Unit, Any]
            closeInbound = SocketEngine.closeInbound(inbound, inboundResidue)
            wsConfig     = HttpWebSocket.Config(autoPingInterval = config.keepAliveInterval)
            relay <- Fiber.initUnscoped {
                Abort.run[SlackTransportException] {
                    transport.connect(wsUrl, wsConfig) { conn =>
                        // `senderDone` completes only after the stream ended, so every ack it polled was put.
                        val senderBody =
                            Abort.run[Closed](outbound.streamUntilClosed().foreach(conn.put))
                                .andThen(senderDone.completeUnit.unit)
                        Fiber.initUnscoped(senderBody).map { senderFiber =>
                            connectReady.complete(Result.succeed((conn, senderFiber))).andThen {
                                // onPeerClose resolves the race on a backend that leaves the stream open after an abnormal close.
                                val receiver = Abort.run[Closed](conn.stream.foreach(inbound.put)).unit
                                Async.race(receiver, conn.onPeerClose).andThen(closeInbound)
                            }
                        }
                    }
                }.map {
                    // A completion after readiness is a no-op: the gate already holds the pair.
                    case Result.Success(_)  => Kyo.unit
                    case Result.Failure(ex) => connectReady.complete(Result.fail(ex)).unit
                    case Result.Panic(ex)   => connectReady.complete(Result.panic(ex)).unit
                }
            }
            built  <- AtomicBoolean.init(false)
            engine <- Scope.run {
                // The relay and the sender are unscoped and own the socket, so an opener that fails or is
                // interrupted before the engine exists releases them here: nothing else holds them.
                Scope.ensure {
                    built.get.map { done =>
                        if done then Kyo.unit
                        else
                            relay.interrupt.andThen(relay.getResult.unit).andThen {
                                connectReady.poll.map {
                                    case Present(Result.Success(ready)) =>
                                        ready.map((conn, sender) => conn.close.andThen(sender.interrupt.andThen(sender.getResult.unit)))
                                    case _ => Kyo.unit
                                }
                            }.andThen(outbound.close.unit).andThen(closeInbound)
                    }
                }.andThen {
                    connectReady.get.map { (conn, sender) =>
                        built.set(true).andThen(new SocketEngine(
                            conn,
                            outbound,
                            inbound,
                            sender,
                            relay,
                            config.ackDeadline,
                            intentionalClose,
                            inboundResidue,
                            senderDone
                        ))
                    }
                }
            }
        yield engine

end SocketEngine
