package kyo.internal

import kyo.*

/** The Socket Mode receive engine: bounded `inbound`/`outbound` Channels, a sender fiber,
  * and a relay fiber that runs the transport connect body (the readiness gate awaited
  * during `initUnscoped` is a local promise, not a field). The receive loop decodes each
  * inbound frame via `SlackWire`, delivers the typed envelope to the handler, and emits
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
  * loop with `SlackTerminalException`; a routine disconnect ends the loop cleanly. An
  * abnormal peer close (transport EOF with no disconnect frame) closes `inbound`; the
  * loop reads that as a routine drop and rotates per policy (or ends under `Off`),
  * distinguished from an intentional teardown by `intentionalClose`.
  *
  * Closing `inbound` is a single idempotent operation (`closeInbound`): a plain channel
  * `close` (the variant that returns the buffered residue and fails the loop's pending
  * take with `Closed`, so no consumer is required) whose residue the first caller
  * publishes into the `inboundResidue` promise. Both the relay's raced completion and
  * the controller's rotation drain go through it, so they never race two different close
  * variants on the same channel: there is exactly one close, exactly one residue
  * capture, and no half-closed channel left waiting for a consumer that has stopped
  * reading at a rotation. The drain awaits the residue promise after calling
  * `closeInbound`, so it delivers exactly the captured residue no matter which path
  * (relay or controller) won the close.
  */
final private[kyo] class SlackSocketEngine private[kyo] (
    private[kyo] val conn: SlackTransport.Conn,
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

    /** Run the receive loop: decode, deliver, and ack exactly once per acked
      * envelope. Ends on a routine disconnect (clean) or aborts on `link_disabled`.
      * The handler runs in this fiber's context (under the connect-bound `local.let`),
      * so the handler's Web API calls resolve the bot token.
      */
    private[kyo] def receiveLoop[S](
        using Isolate[S, Abort[SlackException] & Async, S]
    )(
        handler: SlackEnvelope => SlackAck < (S & Async & Abort[SlackException])
    )(using Frame): Unit < (S & Async & Abort[SlackException]) =
        // Consume the live frames, then deliver the residue the relay's `closeInbound`
        // captured on its raced completion. `closeInbound`'s plain `close` fails this loop's
        // pending take and removes the still-buffered frames as residue, so a stream that
        // ends because the channel closed (peer gone) can leave frames the loop never read;
        // draining `inboundResidue` after the stream delivers exactly those, so no envelope
        // the socket already buffered is lost. The residue is the frames NOT yet taken, so
        // there is no overlap with the ones the stream delivered (no double delivery).
        inbound.streamUntilClosed().foreach { frame =>
            decodeAndDeliver(handler, frame)
        }.andThen {
            inboundResidue.poll.map {
                case Present(Result.Success(residue)) =>
                    residue.map(frames => Kyo.foreachDiscard(frames)(decodeAndDeliver(handler, _)))
                case _ => Kyo.unit
            }
        }

    private def decodeAndDeliver[S](
        using Isolate[S, Abort[SlackException] & Async, S]
    )(
        handler: SlackEnvelope => SlackAck < (S & Async & Abort[SlackException]),
        frame: String
    )(using Frame): Unit < (S & Async & Abort[SlackException]) =
        SlackWire.decode(frame).map {
            case SlackWire.Decoded.Skip(reason) =>
                Log.warn(s"SlackSocketEngine: skipping uncorrelatable frame: $reason")
            case SlackWire.Decoded.Envelope(env, ackable, responseUrl) =>
                deliverAndAck(handler, env, ackable, responseUrl)
        }

    /** Run the receive loop under the reconnect controller: on a routine
      * `disconnect(warning|refresh_requested)` it RETURNS a `Reaction` the controller
      * acts on (rotate per policy) rather than ending; `link_disabled` aborts
      * terminal; a closed inbound channel (peer gone or engine torn down) is a clean
      * `Stop`. During the overlap window it consults `dedup` so a re-pushed id is
      * acked but not re-delivered. One frame at a time, tail-recursive, no `var`.
      */
    private[kyo] def receiveLoopWithReconnect[S](
        using Isolate[S, Abort[SlackException] & Async, S]
    )(
        handler: SlackEnvelope => SlackAck < (S & Async & Abort[SlackException]),
        dedup: SlackReconnect.OverlapDedup,
        onRoutineDisconnect: SlackEnvelope.DisconnectReason => SlackReconnect.Reaction
    )(using Frame): SlackReconnect.Reaction < (S & Async & Abort[SlackException]) =
        def step: SlackReconnect.Reaction < (S & Async & Abort[SlackException]) =
            Abort.run[Closed](inbound.take).map {
                case Result.Failure(_: Closed) =>
                    // inbound closed. An intentional teardown stops the loop; an abnormal peer
                    // drop (the relay closed inbound on its raced completion) is treated like a
                    // routine disconnect, so the controller rotates per policy (or ends under Off)
                    // instead of hanging on a frame that will never arrive.
                    intentionalClose.get.map { intentional =>
                        if intentional then SlackReconnect.Reaction.Stop
                        else onRoutineDisconnect(SlackEnvelope.DisconnectReason.Unknown("peer closed"))
                    }
                case Result.Success(frame) =>
                    SlackWire.decode(frame).map {
                        case SlackWire.Decoded.Skip(reason) =>
                            Log.warn(s"SlackSocketEngine: skipping uncorrelatable frame: $reason").andThen(step)
                        case SlackWire.Decoded.Envelope(env, ackable, responseUrl) =>
                            env match
                                case SlackEnvelope.Disconnect(SlackEnvelope.DisconnectReason.LinkDisabled) =>
                                    handler(
                                        env
                                    ).andThen(Abort.fail(new SlackTerminalException("socket link_disabled (terminal)")))
                                case SlackEnvelope.Disconnect(reason) =>
                                    handler(env).andThen(onRoutineDisconnect(reason))
                                case _ =>
                                    deliverWithDedup(handler, env, ackable, responseUrl, dedup).andThen(step)
                    }
                case Result.Panic(ex) =>
                    Abort.fail(new SlackTransportException(s"receive loop panicked: ${ex.getMessage}", ex))
            }
        step
    end receiveLoopWithReconnect

    /** Deliver-and-ack with the overlap-window dedup: if the id was already delivered
      * in this window, emit the ack (so Slack stops re-pushing) but do NOT invoke the
      * handler again; otherwise remember the id, deliver, and ack. An id-less envelope
      * (no `envelope_id`) bypasses the dedup and delivers normally.
      */
    private def deliverWithDedup[S](
        using Isolate[S, Abort[SlackException] & Async, S]
    )(
        handler: SlackEnvelope => SlackAck < (S & Async & Abort[SlackException]),
        env: SlackEnvelope,
        ackable: Boolean,
        responseUrl: Maybe[String],
        dedup: SlackReconnect.OverlapDedup
    )(using Frame): Unit < (S & Async & Abort[SlackException]) =
        envelopeId(env) match
            case Present(id) =>
                dedup.seenBefore(id).map { already =>
                    if already then emitAck(env, SlackAck.Ack, responseUrl)
                    else dedup.remember(id).andThen(deliverAndAck(handler, env, ackable, responseUrl))
                }
            case Absent =>
                deliverAndAck(handler, env, ackable, responseUrl)
    end deliverWithDedup

    private def deliverAndAck[S](
        using Isolate[S, Abort[SlackException] & Async, S]
    )(
        handler: SlackEnvelope => SlackAck < (S & Async & Abort[SlackException]),
        env: SlackEnvelope,
        ackable: Boolean,
        responseUrl: Maybe[String]
    )(using Frame): Unit < (S & Async & Abort[SlackException]) =
        env match
            case SlackEnvelope.Disconnect(SlackEnvelope.DisconnectReason.LinkDisabled) =>
                handler(env).andThen(Abort.fail(new SlackTerminalException("socket link_disabled (terminal)")))
            case _ if !ackable =>
                // No ack is produced for a non-ackable envelope (hello, disconnect, id-less
                // Unknown), so there is no deadline to honor: just deliver.
                handler(env).unit
            case _ =>
                // Bound the handler by ackDeadline so exactly one ack always goes out within
                // Slack's window. `raceFirst` returns the first leg to finish (success OR
                // failure) and interrupts the other, so:
                //   - handler returns first -> emit its ack (Present);
                //   - the deadline fires first -> emit the bare ack (Absent), and the still-
                //     running handler is race-cancelled (its late payload ack never goes out,
                //     since the bare ack already did);
                //   - the handler aborts or this fiber is interrupted first -> raceFirst
                //     propagates that, so the envelope is left unacked (no stray ack).
                Async.raceFirst(
                    handler(env).map(Present(_): Maybe[SlackAck]),
                    Async.sleep(ackDeadline).andThen(Absent: Maybe[SlackAck])
                ).map {
                    case Present(ack) => emitAck(env, ack, responseUrl)
                    case Absent       => emitAck(env, SlackAck.Ack, responseUrl)
                }

    /** Emit exactly one wire ack from the returned `SlackAck` for an acked envelope,
      * and POST a block_actions message update to the captured `response_url`.
      */
    private[kyo] def emitAck(env: SlackEnvelope, ack: SlackAck, responseUrl: Maybe[String])(using
        Frame
    ): Unit < (Async & Abort[SlackException]) =
        envelopeId(env) match
            case Absent => Kyo.unit
            case Present(id) =>
                val sendAck = SlackWire.encodeAck(id, ack).map { frame =>
                    Abort.recover[Closed] { (c: Closed) =>
                        Abort.fail(new SlackTransportException(s"ack send failed: ${c.getMessage}", c))
                    } {
                        outbound.put(frame)
                    }
                }
                ack match
                    case SlackAck.BlockActionsResponse(message) =>
                        responseUrl match
                            case Present(url) => sendAck.andThen(SlackWebApi.postResponseUrl(url, message))
                            case Absent       => sendAck
                    case _ => sendAck
                end match

    private def envelopeId(env: SlackEnvelope): Maybe[SlackId.EnvelopeId] =
        env match
            case e: SlackEnvelope.EventsApi    => Present(e.meta.envelopeId)
            case e: SlackEnvelope.Interactive  => Present(e.meta.envelopeId)
            case e: SlackEnvelope.SlashCommand => Present(e.meta.envelopeId)
            case _                             => Absent

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
    private[kyo] def closeInbound(using Frame): Unit < Sync =
        inbound.close.map {
            case Present(residue) => inboundResidue.complete(Result.succeed(Chunk.from(residue))).unit
            case Absent           => Kyo.unit
        }

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
    private[kyo] def drainBufferedInbound[S](
        using Isolate[S, Abort[SlackException] & Async, S]
    )(
        handler: SlackEnvelope => SlackAck < (S & Async & Abort[SlackException]),
        dedup: SlackReconnect.OverlapDedup
    )(using Frame): Unit < (S & Async & Abort[SlackException]) =
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

    private def drainFrame[S](
        using Isolate[S, Abort[SlackException] & Async, S]
    )(
        handler: SlackEnvelope => SlackAck < (S & Async & Abort[SlackException]),
        frame: String,
        dedup: SlackReconnect.OverlapDedup
    )(using Frame): Unit < (S & Async & Abort[SlackException]) =
        SlackWire.decode(frame).map {
            case SlackWire.Decoded.Skip(reason) =>
                Log.warn(s"SlackSocketEngine: skipping uncorrelatable residue frame: $reason")
            case SlackWire.Decoded.Envelope(env, ackable, responseUrl) =>
                env match
                    case SlackEnvelope.Disconnect(SlackEnvelope.DisconnectReason.LinkDisabled) =>
                        handler(env).andThen(Abort.fail(new SlackTerminalException("socket link_disabled (terminal)")))
                    case SlackEnvelope.Disconnect(_) =>
                        // Already rotating away from this engine: deliver, do not re-rotate.
                        handler(env).unit
                    case _ =>
                        deliverWithDedup(handler, env, ackable, responseUrl, dedup)
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
      * already-gone socket) to a clean stream end, so `senderDone` always completes and the
      * await is bounded. Only after the flush is the socket closed and both fibers interrupted.
      * `closeNow` is the equivalent unconditional final teardown.
      */
    private[kyo] def closeTransport(using Frame): Unit < Async =
        intentionalClose.set(true)
            .andThen(outbound.closeAwaitEmpty.unit)
            .andThen(senderDone.get)
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

end SlackSocketEngine

private[kyo] object SlackSocketEngine:

    /** Open one engine over the given transport and wss url: bounded channels, the
      * readiness gate, the sender fiber draining `outbound` to the socket, and the relay
      * fiber running the receiver raced with `onPeerClose`. Awaits the readiness gate before
      * returning, so the caller proceeds only on a live connection.
      */
    private[kyo] def initUnscoped(
        transport: SlackTransport,
        wsUrl: String,
        config: SlackConfig
    )(using Frame): SlackSocketEngine < (Async & Abort[SlackException]) =
        for
            outbound         <- Channel.initUnscoped[String](64)
            inbound          <- Channel.initUnscoped[String](64)
            connectReady     <- Fiber.Promise.init[Unit, Abort[SlackException]]
            connRef          <- AtomicRef.init[Maybe[SlackTransport.Conn]](Absent)
            senderRef        <- AtomicRef.init[Maybe[Fiber[Unit, Sync]]](Absent)
            intentionalClose <- AtomicBoolean.init(false)
            inboundResidue   <- Fiber.Promise.init[Chunk[String], Any]
            senderDone       <- Fiber.Promise.init[Unit, Any]
            // Close `inbound` once and publish its residue, idempotently: the first caller
            // (this relay on its raced completion, or the controller's rotation drain) does the
            // plain `close` and completes the residue promise; a later call is a no-op. A plain
            // close fails the loop's pending take with `Closed` and needs no consumer, so a
            // rotation that has stopped reading the old inbound never leaves it half-closed.
            closeInbound = inbound.close.map {
                case Present(residue) => inboundResidue.complete(Result.succeed(Chunk.from(residue))).unit
                case Absent           => Kyo.unit
            }
            wsConfig = HttpWebSocket.Config(autoPingInterval = config.keepAliveInterval)
            relay <- Fiber.initUnscoped {
                Abort.run[Throwable] {
                    transport.connect(wsUrl, wsConfig) { conn =>
                        connRef.set(Present(conn)).andThen {
                            // The sender forwards every outbound ack to the socket and ends when
                            // `outbound` closes (streamUntilClosed drains the buffer first, then stops);
                            // it recovers a `Closed` `conn.put` (an already-gone socket) to a clean end.
                            // It is its OWN fiber, never a race leg, so an inbound-side event cannot
                            // interrupt it mid-forward, and it completes `senderDone` only after its
                            // stream fully ended (every polled ack put). A teardown awaits `senderDone`
                            // to flush with no ack left polled-but-not-sent (the loss an interrupt
                            // mid-forward causes). The handle is published so the engine can interrupt
                            // it after the flush.
                            val senderBody =
                                Abort.run[Closed](outbound.streamUntilClosed().foreach(conn.put))
                                    .andThen(senderDone.completeUnit.unit)
                            Fiber.initUnscoped(senderBody).map { senderFiber =>
                                senderRef.set(Present(senderFiber)).andThen {
                                    // Complete the readiness gate on connect-body entry; the receive
                                    // loop's in-order stream delivers hello first, satisfying the
                                    // hello-first contract by ordering.
                                    connectReady.completeUnit.andThen {
                                        // The receiver copies the socket stream into `inbound`; a closed
                                        // `inbound` (the rotation drain) is a clean receiver end, recovered
                                        // here so it is not a relay failure. Race the peer-close signal
                                        // too: on an abnormal close (transport EOF with no disconnect
                                        // frame) the stream just ends, so the receiver leg terminates;
                                        // including onPeerClose makes the race resolve promptly even when a
                                        // backend leaves the stream open. Then close inbound (capturing its
                                        // residue) so the receive loop observes a clean end and the
                                        // controller rotates per policy (or ends under Off / on an
                                        // intentional teardown) instead of hanging on a frame that will
                                        // never arrive. The single shared `closeInbound` records the
                                        // residue, so the rotation drain delivers every inbound frame the
                                        // socket already received: none is lost on an abnormal drop, and the
                                        // channel is never left waiting for a consumer.
                                        val receiver = Abort.run[Closed](conn.stream.foreach(inbound.put)).unit
                                        Async.race(receiver, conn.onPeerClose).andThen(closeInbound)
                                    }
                                }
                            }
                        }
                    }
                }.map {
                    case Result.Success(_) => Kyo.unit
                    case Result.Failure(ex: SlackException) =>
                        connectReady.complete(Result.fail(ex)).unit
                    case Result.Failure(ex) =>
                        connectReady.complete(Result.fail(new SlackTransportException(
                            s"relay failed: ${ex.getMessage}",
                            ex
                        ))).unit
                    case Result.Panic(ex) =>
                        connectReady.complete(Result.fail(new SlackTransportException(
                            s"relay panicked: ${ex.getMessage}",
                            ex
                        ))).unit
                }
            }
            _ <- connectReady.get
            conn <- connRef.get.map {
                case Present(c) => c: SlackTransport.Conn
                case Absent     => Abort.fail(new SlackTransportException("connection not established after readiness"))
            }
            sender <- senderRef.get.map {
                case Present(s) => s: Fiber[Unit, Sync]
                case Absent     => Abort.fail(new SlackTransportException("sender fiber not started after readiness"))
            }
        yield new SlackSocketEngine(
            conn,
            outbound,
            inbound,
            sender,
            relay,
            config.ackDeadline,
            intentionalClose,
            inboundResidue,
            senderDone
        )

end SlackSocketEngine
