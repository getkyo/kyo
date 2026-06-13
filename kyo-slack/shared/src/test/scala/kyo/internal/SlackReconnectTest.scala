package kyo.internal

import kyo.*

/** Cross-platform reconnect-controller tests driven through two in-memory transport
  * conduits with real Slack wire frames: the Overlap rollover delivers every envelope
  * exactly once in order, a re-pushed envelope_id during the overlap is acked but not
  * re-delivered (bounded dedup), the new engine's transport is live before the old one
  * stops, Immediate closes the old engine first, Off ends the loop on a routine
  * disconnect, and link_disabled is terminal under every policy. Timing is driven by
  * Channel/Fiber/Latch handoffs and bounded takes, never a sleep; every loop terminates
  * deterministically (a routine disconnect ends a leg, the delivery channel drains a
  * known count, link_disabled aborts).
  */
class SlackReconnectTest extends kyo.test.Test[Any]:

    private val url = "wss://test/socket"

    private def eventFrame(id: String) =
        s"""{"type":"events_api","envelope_id":"$id","payload":{"type":"event_callback","event":{"type":"message","channel":"C1","user":"U1","text":"hi","ts":"1.2"}}}"""
    private val helloFrame =
        """{"type":"hello","num_connections":1,"connection_info":{"app_id":"A1"}}"""
    private val disconnectWarning  = """{"type":"disconnect","reason":"warning"}"""
    private val disconnectDisabled = """{"type":"disconnect","reason":"link_disabled"}"""

    /** A scripted conduit: streams `scripted` frames (then stays open until `close`)
      * and records outbound acks. `closed` releases on `close` so a test can observe
      * teardown order; `ready` releases on connect-body entry so a test can observe
      * the overlap ordering. `tap` (frame, latch) releases the latch when the relay PULLS
      * the named frame from the stream; because the relay's `stream.foreach(inbound.put)`
      * is sequential, every frame BEFORE the tapped one has already been `inbound.put`,
      * which lets a test observe a happens-before "the residue is buffered" without a
      * sleep.
      */
    final private class Conduit(
        scripted: Seq[String],
        val recorded: Channel[String],
        val ready: Latch,
        val closed: Latch,
        val feed: Channel[String],
        tap: Maybe[(String, Latch)]
    ) extends SlackTransport:
        private[kyo] def connect[A, S](u: String, c: HttpWebSocket.Config)(
            f: SlackTransport.Conn => A < (S & Async & Abort[SlackException])
        )(using Frame): A < (S & Async & Abort[SlackException]) =
            // Prime the scripted frames into the feed, then enter the body so the engine's
            // relay copies them into inbound. The feed stays open until close so the loop
            // keeps reading until a disconnect frame or teardown.
            Kyo.foreach(scripted)(fr => Abort.run[Closed](feed.put(fr))).andThen {
                ready.release.andThen {
                    val conn = new SlackTransport.Conn:
                        private[kyo] def put(text: String)(using Frame): Unit < (Async & Abort[Closed]) = recorded.put(text)
                        private[kyo] def stream(using Frame): Stream[String, Async] =
                            tap match
                                case Absent                 => feed.streamUntilClosed()
                                case Present((mark, latch)) =>
                                    // One frame per chunk so the per-element tap runs strictly
                                    // after the PRIOR frame's inbound.put: when the latch fires
                                    // on `mark`, every earlier frame is already buffered in inbound.
                                    feed.streamUntilClosed(maxChunkSize = 1).map(fr =>
                                        (if fr == mark then latch.release else Kyo.unit).andThen(fr)
                                    )
                        private[kyo] def close(using Frame): Unit < Async =
                            // Close only the inbound feed (stopping the receiver). Leave the
                            // recorded ack sink OPEN so the test drains the acks that were
                            // emitted before teardown without racing a Closed on the sink.
                            closed.release.andThen(feed.close.unit)
                        private[kyo] def onPeerClose(using Frame): Unit < Async =
                            // The scripted feed is finite, so the receiver leg ends on its own
                            // when the frames run out; this completes on an explicit close.
                            closed.await
                    f(conn)
                }
            }
    end Conduit

    private def conduit(scripted: Seq[String], tap: Maybe[(String, Latch)] = Absent)(using Frame): Conduit < Sync =
        for
            recorded <- Channel.initUnscoped[String](64)
            ready    <- Latch.init(1)
            closed   <- Latch.init(1)
            feed     <- Channel.initUnscoped[String](64)
        yield Conduit(scripted, recorded, ready, closed, feed, tap)

    private val cfgOverlap = SlackConfig(SlackToken.AppLevel("xapp-1"), SlackToken.Bot("xoxb-1"), reconnect = SlackConfig.Reconnect.Overlap)
    private val cfgImmediate = cfgOverlap.copy(reconnect = SlackConfig.Reconnect.Immediate)
    private val cfgOff       = cfgOverlap.copy(reconnect = SlackConfig.Reconnect.Off)

    /** Build an `open` that returns the engines opened over the given conduits, in
      * order, one per call. The call index is a single cross-fiber `AtomicInt`.
      */
    private def opener(conduits: Conduit*)(config: SlackConfig)(using
        Frame
    ): (() => SlackSocketEngine < (Async & Abort[SlackException])) < Sync =
        AtomicInt.init(0).map { idx => () =>
            idx.getAndIncrement.map { i =>
                if i < conduits.size then SlackSocketEngine.initUnscoped(conduits(i), url, config)
                else Abort.fail(new SlackException.SlackTransportException(s"opener exhausted at call $i"))
            }
        }

    private def envIdOf(env: SlackEnvelope): Maybe[String] =
        env match
            case e: SlackEnvelope.EventsApi => Present(e.meta.envelopeId.value)
            case _                          => Absent

    /** A handler that records each delivered event's envelope_id onto `delivered` and
      * acks. Non-event envelopes (hello) are acked without recording.
      */
    private def recordingHandler(delivered: Channel[String]): SlackEnvelope => SlackAck < (Async & Abort[SlackException]) =
        (env: SlackEnvelope) =>
            envIdOf(env) match
                case Present(id) => Abort.run[Closed](delivered.put(id)).andThen(SlackAck.Ack: SlackAck)
                case Absent      => SlackAck.Ack: SlackAck

    private val ackHandler: SlackEnvelope => SlackAck < (Async & Abort[SlackException]) =
        (_: SlackEnvelope) => SlackAck.Ack: SlackAck

    /** Drive the exact production composition `Slack.connect` uses: open the controller,
      * then run its loop. The tests exercise the real `open`/`start` pair rather than a
      * test-only convenience entry.
      */
    private def runController[S](
        using Isolate[S, Abort[SlackException] & Async, S]
    )(
        open: () => SlackSocketEngine < (Async & Abort[SlackException]),
        config: SlackConfig,
        handler: SlackEnvelope => SlackAck < (S & Async & Abort[SlackException])
    )(using Frame): Unit < (S & Async & Abort[SlackException]) =
        SlackReconnect.open(open, config).map(_.start(handler))

    "Overlap rollover delivers the full sequence exactly once, in order" in {
        for
            old       <- conduit(Seq(helloFrame, eventFrame("E1"), disconnectWarning))
            fresh     <- conduit(Seq(helloFrame, eventFrame("E2"), eventFrame("E3")))
            open      <- opener(old, fresh)(cfgOverlap)
            delivered <- Channel.init[String](16)
            loop      <- Fiber.initUnscoped(Abort.run[SlackException](runController(open, cfgOverlap, recordingHandler(delivered))))
            ids       <- delivered.stream().take(3).run
            _         <- loop.interrupt
        yield assert(ids == Chunk("E1", "E2", "E3"), s"expected [E1,E2,E3] once in order, got: $ids")
        end for
    }

    "Overlap drains engineOld's buffered inbound residue: a frame behind the disconnect is delivered AND acked on engineOld's socket, not lost" in {
        // engineOld's frames are [hello, A, disconnect, B, sentinel] all primed up front, so
        // B sits BEHIND the disconnect as buffered inbound residue when the loop reaches the
        // disconnect. The disconnect handler parks the loop until `residueBuffered` confirms
        // (via the one-frame-per-chunk tap on the trailing sentinel) that B is already in
        // engineOld.inbound. The rotation then drains that residue: B must be delivered
        // exactly once (no loss), and B re-pushed on engineNew is suppressed (no duplicate).
        // B's ack is produced while draining engineOld, so it must reach engineOld's STILL-LIVE
        // socket (old.recorded) before engineOld closes: an envelope delivered on a connection
        // is acked on that connection. A is acked on engineOld too; B's re-push on engineNew is
        // acked on engineNew (dedup ack). So engineOld records A's ack then B's ack, in order.
        val sentinel = """{"type":"workflow_step_execute","payload":{}}""" // id-less Unknown: drained but not recorded
        for
            residueBuffered <- Latch.init(1)
            releaseLoop     <- Latch.init(1)
            old <- conduit(
                Seq(helloFrame, eventFrame("A"), disconnectWarning, eventFrame("B"), sentinel),
                tap = Present((sentinel, residueBuffered))
            )
            fresh     <- conduit(Seq(helloFrame, eventFrame("B")))
            open      <- opener(old, fresh)(cfgOverlap)
            delivered <- Channel.init[String](16)
            handler: (SlackEnvelope => SlackAck < (Async & Abort[SlackException])) = (env: SlackEnvelope) =>
                env match
                    case SlackEnvelope.Disconnect(_) =>
                        // Hold the loop at the disconnect until the residue (B) is buffered, so the
                        // rotation's drain observes it as residue rather than racing the relay.
                        residueBuffered.await.andThen(releaseLoop.await).andThen(SlackAck.Ack: SlackAck)
                    case _ =>
                        envIdOf(env) match
                            case Present(id) => Abort.run[Closed](delivered.put(id)).andThen(SlackAck.Ack: SlackAck)
                            case Absent      => SlackAck.Ack: SlackAck
            loop <- Fiber.initUnscoped(Abort.run[SlackException](runController(open, cfgOverlap, handler)))
            // A is delivered on engineOld before the disconnect parks the loop.
            firstId <- delivered.stream().take(1).run
            _       <- releaseLoop.release
            // B is the residue drained from engineOld, then B re-pushed on engineNew is the
            // dedup-suppressed duplicate. Two total deliveries: A then B, B exactly once.
            secondId <- delivered.stream().take(1).run
            // Both acks engineOld produced (A's, then B's from the residue drain) must have been
            // flushed to engineOld's live socket before it closed. A bounded take of two surfaces
            // a lost residue ack as a timeout rather than passing on a partial record.
            oldAcks <- old.recorded.stream().take(2).run
            _       <- loop.interrupt
        yield
            assert(firstId == Chunk("A"), s"A delivered on engineOld before the disconnect; got: $firstId")
            assert(secondId == Chunk("B"), s"B (the residue behind the disconnect) delivered, not lost; got: $secondId")
            val oldAckIds = oldAcks.map(a => Json.decode[SlackWire.AckFrame](a).getOrThrow.envelope_id)
            assert(oldAckIds == Chunk("A", "B"), s"engineOld acked A then B (residue ack flushed before close); got: $oldAckIds")
        end for
    }

    "a re-pushed envelope_id across the overlap is acked but not re-delivered" in {
        for
            old       <- conduit(Seq(helloFrame, eventFrame("E1"), disconnectWarning))
            fresh     <- conduit(Seq(helloFrame, eventFrame("E1"), eventFrame("E2")))
            open      <- opener(old, fresh)(cfgOverlap)
            delivered <- Channel.init[String](16)
            loop      <- Fiber.initUnscoped(Abort.run[SlackException](runController(open, cfgOverlap, recordingHandler(delivered))))
            // E1 (old) then E2 (new) are the only handler deliveries; the re-pushed E1
            // on the new engine is suppressed by the dedup window.
            ids <- delivered.stream().take(2).run
            // One ack on the old engine (E1 delivery) and two on the new (E1 dedup ack + E2).
            oldAcks <- old.recorded.stream().take(1).run
            newAcks <- fresh.recorded.stream().take(2).run
            _       <- loop.interrupt
        yield
            assert(ids == Chunk("E1", "E2"), s"E1 delivered once, E2 once, no re-delivery; got: $ids")
            val allAcks = (oldAcks ++ newAcks).map(a => Json.decode[SlackWire.AckFrame](a).getOrThrow.envelope_id)
            assert(allAcks.count(_ == "E1") == 2, s"both E1 pushes acked, got acks: $allAcks")
            assert(allAcks.count(_ == "E2") == 1, s"E2 acked once, got acks: $allAcks")
        end for
    }

    "the new engine's transport is live before the old one stops (Overlap, no gap)" in {
        // Overlap brings engineNew up (fresh.ready) BEFORE stopping engineOld (old.closed).
        // The order is observed by a happens-before check, not by racing two observer fibers
        // into a channel (whose put order is a scheduling race): await the LATER event
        // (old.closed) and assert the EARLIER one (fresh.ready) has already fired (pending 0).
        // If the rotation closed the old engine before the new one was ready, fresh.ready would
        // still be pending (1) when old.closed fires, and the assertion fails.
        for
            old          <- conduit(Seq(helloFrame, disconnectWarning))
            fresh        <- conduit(Seq(helloFrame, eventFrame("E2")))
            open         <- opener(old, fresh)(cfgOverlap)
            loop         <- Fiber.initUnscoped(Abort.run[SlackException](runController(open, cfgOverlap, ackHandler)))
            _            <- old.closed.await
            readyPending <- fresh.ready.pending
            _            <- loop.interrupt
        yield assert(readyPending == 0, s"engineNew ready BEFORE engineOld closed (no gap); fresh.ready still pending: $readyPending")
        end for
    }

    "Immediate closes the old engine before opening the new one" in {
        // Immediate stops engineOld (old.closed) BEFORE opening engineNew (fresh.ready). Observe
        // the order by a happens-before check rather than racing two observer fibers into a
        // channel: await the LATER event (fresh.ready) and assert the EARLIER one (old.closed)
        // has already fired (pending 0). If the rotation opened the new engine before closing
        // the old one, old.closed would still be pending (1) when fresh.ready fires.
        for
            old           <- conduit(Seq(helloFrame, disconnectWarning))
            fresh         <- conduit(Seq(helloFrame, eventFrame("E2")))
            open          <- opener(old, fresh)(cfgImmediate)
            loop          <- Fiber.initUnscoped(Abort.run[SlackException](runController(open, cfgImmediate, ackHandler)))
            _             <- fresh.ready.await
            closedPending <- old.closed.pending
            _             <- loop.interrupt
        yield assert(closedPending == 0, s"Immediate closes old BEFORE opening new; old.closed still pending: $closedPending")
        end for
    }

    "an abnormal peer close (feed ends, no disconnect frame) rotates under Overlap" in {
        // engineOld's feed carries no disconnect frame; the test ends it after E1 is
        // delivered, mimicking a transport EOF. The relay closes inbound, the loop reads the
        // Closed as an abnormal drop (not an intentional teardown) and rotates per Overlap to
        // engineNew, which delivers E2. No hang: the delivery channel drains E1 then E2.
        for
            old       <- conduit(Seq(helloFrame, eventFrame("E1")))
            fresh     <- conduit(Seq(helloFrame, eventFrame("E2")))
            open      <- opener(old, fresh)(cfgOverlap)
            delivered <- Channel.init[String](16)
            loop      <- Fiber.initUnscoped(Abort.run[SlackException](runController(open, cfgOverlap, recordingHandler(delivered))))
            firstId   <- delivered.stream().take(1).run
            // End engineOld's feed with no disconnect: an abnormal peer close.
            _        <- old.feed.close
            secondId <- delivered.stream().take(1).run
            _        <- loop.interrupt
        yield
            assert(firstId == Chunk("E1"), s"E1 delivered on engineOld; got: $firstId")
            assert(secondId == Chunk("E2"), s"abnormal close rotated to engineNew (E2), no hang; got: $secondId")
        end for
    }

    "an abnormal peer close drains engineOld's buffered residue: a frame behind the drop is delivered, not lost" in {
        // engineOld's feed is [hello, A, B, sentinel] with no disconnect frame. The handler
        // delivers A and parks until the residue (B, sentinel) is confirmed buffered in
        // engineOld.inbound (via the one-frame-per-chunk tap on the trailing sentinel). The
        // feed then closes (transport EOF) while the loop is still parked at A, so the relay's
        // raced completion closes engineOld.inbound with B behind it. On the abnormal-close
        // rotation the controller drains that residue: B must be delivered exactly once (no
        // loss). This pins the abnormal-close half of the close-coordination race: when the
        // relay wins the close, the residue it captured is still delivered, not silently
        // dropped. B is also re-pushed on engineNew and suppressed by the dedup (no duplicate).
        val sentinel = """{"type":"workflow_step_execute","payload":{}}""" // id-less Unknown: drained but not recorded
        for
            residueBuffered <- Latch.init(1)
            releaseLoop     <- Latch.init(1)
            old <- conduit(
                Seq(helloFrame, eventFrame("A"), eventFrame("B"), sentinel),
                tap = Present((sentinel, residueBuffered))
            )
            fresh     <- conduit(Seq(helloFrame, eventFrame("B")))
            open      <- opener(old, fresh)(cfgOverlap)
            delivered <- Channel.init[String](16)
            firstSeen <- AtomicBoolean.init(false)
            handler: (SlackEnvelope => SlackAck < (Async & Abort[SlackException])) = (env: SlackEnvelope) =>
                envIdOf(env) match
                    case Present(id) =>
                        // Park only the FIRST delivered event (A) until B is buffered behind it,
                        // then the abnormal close is triggered; later deliveries (B) record directly.
                        firstSeen.getAndSet(true).map { wasSeen =>
                            val gate = if wasSeen then Kyo.unit else residueBuffered.await.andThen(releaseLoop.await)
                            gate.andThen(Abort.run[Closed](delivered.put(id))).andThen(SlackAck.Ack: SlackAck)
                        }
                    case Absent => SlackAck.Ack: SlackAck
            loop <- Fiber.initUnscoped(Abort.run[SlackException](runController(open, cfgOverlap, handler)))
            _    <- residueBuffered.await
            // B (and the trailing sentinel) are now buffered in engineOld.inbound while the loop
            // is parked at A. End the feed with no disconnect frame: an abnormal transport EOF.
            _ <- old.feed.close
            _ <- releaseLoop.release
            // A delivered on engineOld, then B drained from engineOld's residue. B re-pushed on
            // engineNew is the dedup-suppressed duplicate. Two total deliveries: A then B once.
            ids <- delivered.stream().take(2).run
            _   <- loop.interrupt
        yield assert(ids == Chunk("A", "B"), s"A then B (the residue behind the abnormal drop) delivered, no loss/dup; got: $ids")
        end for
    }

    "an abnormal peer close ends the loop cleanly under Off; no second engine is opened" in {
        // Under Off, an abnormal drop ends the loop cleanly (Reaction.Stop), exactly as a
        // routine disconnect would, rather than rotating or hanging.
        for
            old       <- conduit(Seq(helloFrame, eventFrame("E1")))
            open      <- opener(old)(cfgOff)
            delivered <- Channel.init[String](8)
            loop      <- Fiber.initUnscoped(Abort.run[SlackException](runController(open, cfgOff, recordingHandler(delivered))))
            firstId   <- delivered.stream().take(1).run
            _         <- old.feed.close
            result    <- loop.get
            ids       <- Abort.run[Closed](delivered.drain)
        yield
            assert(firstId == Chunk("E1"), s"E1 delivered before the abnormal close; got: $firstId")
            assert(result == Result.Success(()), s"Off ends cleanly on an abnormal close, got: $result")
            assert(ids.getOrElse(Chunk.empty) == Chunk.empty[String], s"no further delivery after the clean stop; got: $ids")
        end for
    }

    "Off ends the loop cleanly on a routine disconnect; no second engine is opened" in {
        for
            old       <- conduit(Seq(helloFrame, eventFrame("E1"), disconnectWarning))
            open      <- opener(old)(cfgOff)
            delivered <- Channel.init[String](8)
            loop      <- Fiber.initUnscoped(Abort.run[SlackException](runController(open, cfgOff, recordingHandler(delivered))))
            result    <- loop.get
            ids       <- Abort.run[Closed](delivered.drain)
        yield
            assert(result == Result.Success(()), s"Off ends cleanly on a routine disconnect, got: $result")
            assert(ids.getOrElse(Chunk.empty) == Chunk("E1"), s"E1 delivered then a clean stop; got: $ids")
        end for
    }

    "link_disabled aborts SlackTerminalException under Overlap; no reconnect" in {
        for
            old    <- conduit(Seq(helloFrame, disconnectDisabled))
            open   <- opener(old)(cfgOverlap)
            loop   <- Fiber.initUnscoped(Abort.run[SlackException](runController(open, cfgOverlap, ackHandler)))
            result <- loop.get
        yield result match
            case Result.Failure(_: SlackException.SlackTerminalException) => assert(true)
            case other => assert(false, s"expected SlackTerminalException under Overlap, got: $other")
        end for
    }

    "link_disabled aborts SlackTerminalException under Immediate and Off" in {
        def runDisabled(config: SlackConfig)(using Frame): Result[SlackException, Unit] < (Async & Scope) =
            for
                old    <- conduit(Seq(helloFrame, disconnectDisabled))
                open   <- opener(old)(config)
                loop   <- Fiber.initUnscoped(Abort.run[SlackException](runController(open, config, ackHandler)))
                result <- loop.get
            yield result
        for
            immediate <- runDisabled(cfgImmediate)
            off       <- runDisabled(cfgOff)
        yield
            assert(immediate.isFailure, s"Immediate: terminal, got: $immediate")
            assert(off.isFailure, s"Off: terminal, got: $off")
            immediate match
                case Result.Failure(_: SlackException.SlackTerminalException) => assert(true)
                case other => assert(false, s"Immediate expected SlackTerminalException, got: $other")
            off match
                case Result.Failure(_: SlackException.SlackTerminalException) => assert(true)
                case other => assert(false, s"Off expected SlackTerminalException, got: $other")
        end for
    }

    "the dedup window is bounded: an id re-pushed two rotations later is delivered again" in {
        // E1 is delivered on engine1 before a disconnect. The window rolls forward at
        // each rotation and carries at most the prior generation's ids, so after a
        // SECOND rotation E1 has fallen out of the window. engine3 then delivers E1
        // again and it IS re-delivered (the window is bounded, not a lifetime set).
        for
            engine1   <- conduit(Seq(helloFrame, eventFrame("E1"), disconnectWarning))
            engine2   <- conduit(Seq(helloFrame, eventFrame("M2"), disconnectWarning))
            engine3   <- conduit(Seq(helloFrame, eventFrame("E1")))
            open      <- opener(engine1, engine2, engine3)(cfgOverlap)
            delivered <- Channel.init[String](16)
            loop      <- Fiber.initUnscoped(Abort.run[SlackException](runController(open, cfgOverlap, recordingHandler(delivered))))
            // engine1 E1, engine2 M2, engine3 E1 (post-window) = three deliveries.
            ids <- delivered.stream().take(3).run
            _   <- loop.interrupt
        yield assert(ids == Chunk("E1", "M2", "E1"), s"E1 re-delivered after the window rolled past it; got: $ids")
        end for
    }

end SlackReconnectTest
