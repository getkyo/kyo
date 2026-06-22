package kyo.internal

import kyo.*

/** Cross-platform receive-engine tests driven through an in-memory transport conduit
  * with real Slack wire frames: connect observes hello, the loop delivers each
  * envelope and emits exactly one real ack from the returned SlackAck, an
  * aborting/interrupted handler leaves the envelope unacked, and teardown is
  * observable. Timing is driven by Channel/Fiber/Latch handoffs, never a sleep.
  *
  * The conduit keeps its inbound source OPEN after the scripted frames (a real
  * WebSocket stream stays open until close), so the engine's raced sender/receiver
  * does not tear the ack sender down before the test drains the recorded acks. The
  * test closes the conduit explicitly to end the loop.
  */
class SlackSocketEngineTest extends kyo.test.Test[Any]:

    private val cfg = SlackConfig(SlackToken.AppLevel("xapp-1"), SlackToken.Bot("xoxb-1"))

    private val helloFrame =
        """{"type":"hello","num_connections":1,"connection_info":{"app_id":"A1"}}"""
    private def eventFrame(id: String) =
        s"""{"type":"events_api","envelope_id":"$id","payload":{"type":"event_callback","event":{"type":"message","channel":"C1","user":"U1","text":"hi","ts":"1.2"}}}"""
    private val disconnectWarning  = """{"type":"disconnect","reason":"warning"}"""
    private val disconnectDisabled = """{"type":"disconnect","reason":"link_disabled"}"""
    private val unknownNoIdFrame   = """{"type":"workflow_step_execute","payload":{}}"""
    private val noTypeFrame        = """{"envelope_id":"E1"}"""
    private def blockActionsFrame(id: String, responseUrl: String) =
        s"""{"type":"interactive","envelope_id":"$id","payload":{"type":"block_actions","response_url":"$responseUrl","user":{"id":"U1","username":"bob"},"trigger_id":"T1","channel":{"id":"C1","name":"general"},"actions":[{"action_id":"a1","block_id":"b1","value":"v1","type":"button"}]}}"""

    private val url = "wss://test/socket"

    /** A test conduit: the engine reads `feed` (kept open until `close`) and writes acks
      * into `recorded`. The captured WebSocket config is recorded into `configRef`.
      */
    final private class Conduit(
        val feed: Channel[String],
        val recorded: Channel[String],
        val configRef: AtomicRef[Maybe[HttpWebSocket.Config]],
        val peerClosed: Fiber.Promise[Unit, Any]
    ) extends SlackTransport:
        private[kyo] def connect[A, S](u: String, c: HttpWebSocket.Config)(
            f: SlackTransport.Conn => A < (S & Async & Abort[SlackException])
        )(using Frame): A < (S & Async & Abort[SlackException]) =
            configRef.set(Present(c)).andThen {
                val conn = new SlackTransport.Conn:
                    private[kyo] def put(text: String)(using Frame): Unit < (Async & Abort[Closed]) = recorded.put(text)
                    private[kyo] def stream(using Frame): Stream[String, Async]                     = feed.streamUntilClosed()
                    private[kyo] def close(using Frame): Unit < Async =
                        peerClosed.completeUnit.andThen(feed.close.andThen(recorded.close.unit))
                    private[kyo] def onPeerClose(using Frame): Unit < Async = peerClosed.get
                f(conn)
            }
    end Conduit

    private def conduit(using Frame): Conduit < Sync =
        for
            feed       <- Channel.initUnscoped[String](64)
            recorded   <- Channel.initUnscoped[String](64)
            configRef  <- AtomicRef.init[Maybe[HttpWebSocket.Config]](Absent)
            peerClosed <- Fiber.Promise.init[Unit, Any]
        yield Conduit(feed, recorded, configRef, peerClosed)

    private val ackOf: SlackEnvelope => SlackAck < (Async & Abort[SlackException]) = _ => SlackAck.Ack

    "hello is delivered first, before any event" in {
        for
            c      <- conduit
            engine <- SlackSocketEngine.initUnscoped(c, url, cfg)
            types  <- Channel.init[String](8)
            handler = (env: SlackEnvelope) =>
                val tpe = env match
                    case _: SlackEnvelope.Hello     => "Hello"
                    case _: SlackEnvelope.EventsApi => "EventsApi"
                    case other                      => other.getClass.getSimpleName
                Abort.run[Closed](types.put(tpe)).andThen(SlackAck.Ack: SlackAck)
            _        <- Fiber.initUnscoped(Abort.run[SlackException](engine.receiveLoop(handler)))
            _        <- c.feed.put(helloFrame)
            _        <- c.feed.put(eventFrame("E1"))
            observed <- types.stream().take(2).run
            _        <- engine.closeNow
        yield assert(observed == Chunk("Hello", "EventsApi"))
        end for
    }

    "connect returns only after the readiness gate completes; never returns with no hello and no relay completion" in {
        for
            ready <- Latch.init(1)
            _     <- conduit.map(c => SlackSocketEngine.initUnscoped(c, url, cfg).andThen(ready.release).andThen(c.feed.close))
            _     <- ready.await
            neverTransport = new SlackTransport:
                private[kyo] def connect[B, S](u: String, cc: HttpWebSocket.Config)(
                    f: SlackTransport.Conn => B < (S & Async & Abort[SlackException])
                )(using Frame): B < (S & Async & Abort[SlackException]) =
                    Latch.init(1).map(_.await.andThen(Abort.fail(new SlackTransportException("unreachable"))))
            timed <- Abort.run[Timeout | SlackException](
                Async.timeout(200.millis)(SlackSocketEngine.initUnscoped(neverTransport, url, cfg))
            )
        yield timed match
            case Result.Failure(_: Timeout) => assert(true)
            case other                      => assert(false, s"expected Timeout (no readiness), got: $other")
        end for
    }

    "one acked envelope produces exactly one ack frame with the matching envelope_id" in {
        for
            c      <- conduit
            engine <- SlackSocketEngine.initUnscoped(c, url, cfg)
            _      <- Fiber.initUnscoped(Abort.run[SlackException](engine.receiveLoop(ackOf)))
            _      <- c.feed.put(eventFrame("E1"))
            acks   <- c.recorded.stream().take(1).run
            _      <- engine.closeNow
        yield assert(acks == Chunk("""{"envelope_id":"E1"}"""))
        end for
    }

    "N acked envelopes produce exactly N acks with matching ids" in {
        for
            c      <- conduit
            engine <- SlackSocketEngine.initUnscoped(c, url, cfg)
            _      <- Fiber.initUnscoped(Abort.run[SlackException](engine.receiveLoop(ackOf)))
            _      <- c.feed.put(eventFrame("E1"))
            _      <- c.feed.put(eventFrame("E2"))
            _      <- c.feed.put(eventFrame("E3"))
            acks   <- c.recorded.stream().take(3).run
            _      <- engine.closeNow
        yield
            val ids = acks.map(a => Json.decode[SlackWire.AckFrame](a).getOrThrow.envelope_id).toSet
            assert(ids == Set("E1", "E2", "E3"))
            assert(acks.size == 3)
        end for
    }

    "hello and disconnect produce zero ack frames" in {
        for
            c         <- conduit
            engine    <- SlackSocketEngine.initUnscoped(c, url, cfg)
            delivered <- Channel.init[SlackEnvelope](4)
            handler = (env: SlackEnvelope) => Abort.run[Closed](delivered.put(env)).andThen(SlackAck.Ack: SlackAck)
            _ <- Fiber.initUnscoped(Abort.run[SlackException](engine.receiveLoop(handler)))
            _ <- c.feed.put(helloFrame)
            _ <- c.feed.put(disconnectWarning)
            // Await both deliveries (hello + disconnect both reach the handler); only then
            // drain the recorded acks. Neither hello nor disconnect is ackable, so a stray
            // ack would already be recorded if the engine acked a non-ackable type.
            envs    <- delivered.stream().take(2).run
            drained <- Abort.run[Closed](c.recorded.drain)
            _       <- engine.closeNow
        yield
            assert(envs.size == 2)
            assert(drained.getOrElse(Chunk.empty) == Chunk.empty[String], s"expected zero acks, got: $drained")
        end for
    }

    "an Unknown envelope with no id produces no ack but is delivered" in {
        for
            c         <- conduit
            engine    <- SlackSocketEngine.initUnscoped(c, url, cfg)
            delivered <- Channel.init[SlackEnvelope](4)
            handler = (env: SlackEnvelope) => Abort.run[Closed](delivered.put(env)).andThen(SlackAck.Ack: SlackAck)
            _    <- Fiber.initUnscoped(Abort.run[SlackException](engine.receiveLoop(handler)))
            _    <- c.feed.put(unknownNoIdFrame)
            env  <- delivered.stream().take(1).run
            acks <- Abort.run[Closed](c.recorded.drain)
            _    <- engine.closeNow
        yield
            env.head match
                case SlackEnvelope.Unknown(t, _) => assert(t == "workflow_step_execute")
                case other                       => assert(false, s"expected Unknown, got: $other")
            end match
            assert(acks.getOrElse(Chunk.empty) == Chunk.empty[String], s"no ack for an id-less Unknown, got: $acks")
        end for
    }

    "a handler that Abort.fails leaves the envelope unacked" in {
        for
            c      <- conduit
            engine <- SlackSocketEngine.initUnscoped(c, url, cfg)
            handler = (_: SlackEnvelope) =>
                Abort.fail(new SlackTransportException("handler boom")): SlackAck < (Async & Abort[SlackException])
            loop   <- Fiber.initUnscoped(Abort.run[SlackException](engine.receiveLoop(handler)))
            _      <- c.feed.put(eventFrame("E1"))
            result <- loop.get
            acks   <- Abort.run[Closed](c.recorded.drain)
            _      <- engine.closeNow
        yield
            result match
                case Result.Failure(ex: SlackTransportException) =>
                    assert(ex.getMessage.contains("handler boom"))
                case other => assert(false, s"expected SlackTransportException failure, got: $other")
            end match
            assert(acks.getOrElse(Chunk.empty) == Chunk.empty[String], s"no ack on handler abort, got: $acks")
        end for
    }

    "an interrupted in-flight handler leaves the envelope unacked" in {
        for
            c       <- conduit
            engine  <- SlackSocketEngine.initUnscoped(c, url, cfg)
            entered <- Latch.init(1)
            parked  <- Latch.init(1)
            handler = (_: SlackEnvelope) => entered.release.andThen(parked.await).andThen(SlackAck.Ack: SlackAck)
            loop <- Fiber.initUnscoped(Abort.run[SlackException](engine.receiveLoop(handler)))
            _    <- c.feed.put(eventFrame("E1"))
            _    <- entered.await
            _    <- loop.interrupt
            res  <- loop.getResult
            acks <- Abort.run[Closed](c.recorded.drain)
            _    <- engine.closeNow
        yield
            assert(res.isPanic || res.isFailure, s"interrupted loop should not succeed, got: $res")
            assert(acks.getOrElse(Chunk.empty) == Chunk.empty[String], s"no ack on interrupt, got: $acks")
        end for
    }

    "a handler that does not return within ackDeadline emits exactly one bare ack within the deadline" in {
        // The handler parks on a latch the test never releases, so the ONLY ack that can
        // appear is the bare ack the engine emits when ackDeadline fires. A tiny deadline
        // drives the Clock-based race deterministically; the latch never releasing proves
        // the ack came from the deadline, not the handler. Exactly one ack, bare, for E1.
        val fastCfg = cfg.copy(ackDeadline = 50.millis)
        for
            c       <- conduit
            engine  <- SlackSocketEngine.initUnscoped(c, url, fastCfg)
            entered <- Latch.init(1)
            never   <- Latch.init(1)
            handler = (_: SlackEnvelope) => entered.release.andThen(never.await).andThen(SlackAck.Ack: SlackAck)
            _    <- Fiber.initUnscoped(Abort.run[SlackException](engine.receiveLoop(handler)))
            _    <- c.feed.put(eventFrame("E1"))
            _    <- entered.await
            acks <- c.recorded.stream().take(1).run
            rest <- Abort.run[Closed](c.recorded.poll)
            _    <- engine.closeNow
        yield
            assert(acks == Chunk("""{"envelope_id":"E1"}"""), s"deadline-fired bare ack for E1, got: $acks")
            assert(rest.getOrElse(Absent) == Absent, s"exactly one ack within the deadline (no double-ack), got: $rest")
        end for
    }

    "a payload ack returned within ackDeadline is emitted as that payload, not the bare ack" in {
        // The handler returns a CommandResponse promptly (well within the deadline), so the
        // emitted ack carries the payload, proving the deadline race does not clobber a
        // timely handler ack with the bare ack.
        val fastCfg = cfg.copy(ackDeadline = 2.seconds)
        for
            c      <- conduit
            engine <- SlackSocketEngine.initUnscoped(c, url, fastCfg)
            handler = (_: SlackEnvelope) =>
                SlackAck.CommandResponse(SlackMessage(SlackId.ChannelId("C1"), "done")): SlackAck < (Async & Abort[SlackException])
            _    <- Fiber.initUnscoped(Abort.run[SlackException](engine.receiveLoop(handler)))
            _    <- c.feed.put(eventFrame("E1"))
            acks <- c.recorded.stream().take(1).run
            _    <- engine.closeNow
        yield
            assert(acks.size == 1)
            assert(acks.head.contains("\"envelope_id\":\"E1\""), s"ack carries E1, got: $acks")
            assert(acks.head.contains("\"text\":\"done\""), s"timely handler payload ack emitted, got: $acks")
        end for
    }

    "disconnect(link_disabled) ends the loop with SlackTerminalException; envelope delivered first" in {
        for
            c         <- conduit
            engine    <- SlackSocketEngine.initUnscoped(c, url, cfg)
            delivered <- Channel.init[SlackEnvelope](4)
            handler = (env: SlackEnvelope) => Abort.run[Closed](delivered.put(env)).andThen(SlackAck.Ack: SlackAck)
            loop   <- Fiber.initUnscoped(Abort.run[SlackException](engine.receiveLoop(handler)))
            _      <- c.feed.put(disconnectDisabled)
            result <- loop.get
            env    <- delivered.stream().take(1).run
            _      <- engine.closeNow
        yield
            result match
                case Result.Failure(_: SlackTerminalException) => assert(true)
                case other                                     => assert(false, s"expected SlackTerminalException, got: $other")
            end match
            env.head match
                case SlackEnvelope.Disconnect(SlackEnvelope.DisconnectReason.LinkDisabled) => assert(true)
                case other => assert(false, s"expected Disconnect(LinkDisabled), got: $other")
        end for
    }

    "BlockActionsResponse emits a bare ack AND POSTs to the captured response_url" in {
        // The socket ack is asserted here (bare, matching the envelope_id); the
        // response_url POST is the JVM live test (it needs a real HTTP endpoint). The
        // handler runs under the bound bot token so postResponseUrl resolves it; the POST
        // target is unreachable, so the engine surfaces a SlackTransportException AFTER
        // the bare socket ack is already recorded.
        for
            c      <- conduit
            engine <- SlackSocketEngine.initUnscoped(c, url, cfg)
            handler = (_: SlackEnvelope) =>
                SlackAck.BlockActionsResponse(SlackMessage(SlackId.ChannelId("C1"), "updated")): SlackAck < (Async & Abort[SlackException])
            _ <- SlackWebApi.baseUrl.let("http://127.0.0.1:1/api") {
                SlackWebApi.local.let(Present(cfg.bot)) {
                    Fiber.initUnscoped(Abort.run[SlackException](engine.receiveLoop(handler)))
                }
            }
            _    <- c.feed.put(blockActionsFrame("E2", "http://127.0.0.1:1/hooks/x"))
            acks <- c.recorded.stream().take(1).run
            _    <- engine.closeNow
        yield assert(acks == Chunk("""{"envelope_id":"E2"}"""))
        end for
    }

    "an abnormal peer close (feed ends with no disconnect frame) terminates the loop, no hang" in {
        // No disconnect frame: the conduit feed just ends (transport EOF). The relay's
        // receiver leg terminates, the race resolves, and the engine closes inbound, so the
        // receive loop observes a clean stream end and the loop returns rather than blocking
        // forever on a frame that will never arrive. A bounded loop.get with a timeout proves
        // termination without a sleep: a hang would surface as a Timeout.
        for
            c       <- conduit
            engine  <- SlackSocketEngine.initUnscoped(c, url, cfg)
            entered <- Latch.init(1)
            handler = (_: SlackEnvelope) => entered.release.andThen(SlackAck.Ack: SlackAck)
            loop <- Fiber.initUnscoped(Abort.run[SlackException](engine.receiveLoop(handler)))
            _    <- c.feed.put(eventFrame("E1"))
            _    <- entered.await
            // End the feed with no disconnect frame, mimicking an abnormal transport EOF.
            _      <- c.feed.close
            result <- Abort.run[Timeout](Async.timeout(2.seconds)(loop.get))
            _      <- engine.closeNow
        yield result match
            case Result.Success(Result.Success(())) => assert(true)
            case other                              => assert(false, s"loop must terminate on abnormal close, got: $other")
        end for
    }

    "closeNow tears down the socket and relay observably; second closeNow is a no-op" in {
        for
            c      <- conduit
            engine <- SlackSocketEngine.initUnscoped(c, url, cfg)
            _      <- c.feed.put(helloFrame)
            _      <- engine.closeNow
            // The feed channel is closed by closeNow -> conn.close; a put now aborts Closed,
            // proving the teardown propagated to the conduit conn.
            putRes <- Abort.run[Closed](c.feed.put("after-close"))
            _      <- engine.relay.getResult
            _      <- engine.closeNow
        yield assert(putRes.isFailure, s"feed put after closeNow should abort Closed, got: $putRes")
        end for
    }

    "a Skip frame (no type) is logged and the loop continues to the next frame" in {
        for
            c      <- conduit
            engine <- SlackSocketEngine.initUnscoped(c, url, cfg)
            _      <- Fiber.initUnscoped(Abort.run[SlackException](engine.receiveLoop(ackOf)))
            _      <- c.feed.put(noTypeFrame)
            _      <- c.feed.put(eventFrame("E1"))
            acks   <- c.recorded.stream().take(1).run
            _      <- engine.closeNow
        yield assert(acks == Chunk("""{"envelope_id":"E1"}"""))
        end for
    }

    "keepAliveInterval is wired into the WebSocket config passed to the transport" in {
        for
            present <- capturedConfig(cfg.copy(keepAliveInterval = Present(45.seconds)))
            absent  <- capturedConfig(cfg.copy(keepAliveInterval = Absent))
        yield
            // Each result is (configWasCaptured, autoPingInterval); the inner Maybe is NOT
            // nested in an outer Maybe (the Maybe-of-Absent flattening pitfall).
            assert(present == (true, Present(45.seconds)), s"expected (true, Present(45s)), got: $present")
            assert(absent == (true, Absent), s"expected (true, Absent), got: $absent")
        end for
    }

    private def capturedConfig(config: SlackConfig)(using
        Frame
    ): (Boolean, Maybe[Duration]) < (Async & Abort[SlackException | Closed] & Scope) =
        for
            c         <- conduit
            engine    <- SlackSocketEngine.initUnscoped(c, url, config)
            delivered <- Channel.init[SlackEnvelope](2)
            handler = (env: SlackEnvelope) => Abort.run[Closed](delivered.put(env)).andThen(SlackAck.Ack: SlackAck)
            _ <- Fiber.initUnscoped(Abort.run[SlackException](engine.receiveLoop(handler)))
            _ <- c.feed.put(helloFrame)
            // Await the hello delivery: the connect body (which records configRef before
            // entering) has fully run by the time a frame round-trips to the handler.
            _    <- delivered.stream().take(1).run
            seen <- c.configRef.get
            _    <- engine.closeNow
        yield seen match
            case Present(cfg) => (true, cfg.autoPingInterval)
            case Absent       => (false, Absent)
    end capturedConfig

end SlackSocketEngineTest
