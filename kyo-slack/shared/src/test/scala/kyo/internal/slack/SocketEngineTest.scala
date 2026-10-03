package kyo.internal.slack

import kyo.*
import kyo.SlackLiterals.*

/** Cross-platform receive-engine tests with real Slack wire frames: connect observes hello,
  * the loop delivers each envelope and emits exactly one real ack from the returned SlackAck,
  * and teardown is observable. Timing is driven by Channel/Fiber/Latch handoffs, never a sleep.
  *
  * The engine mechanics run over an in-memory conduit that keeps its inbound source OPEN after
  * the scripted frames (a real WebSocket stream stays open until close), so the engine's raced
  * sender/receiver does not tear the ack sender down before the test drains the recorded acks.
  * Every handler outcome runs over a local Socket Mode server, which records the ack frames it
  * received, and the `response_url` POST runs against a local kyo-http server.
  */
class SocketEngineTest extends kyo.test.Test[Any]:

    // Socket-only opt-out: this suite runs an HttpServer/HttpClient on the NIO transport, whose closed-channel fd
    // close is deferred to the idle selector's next select() (an opaque socket:[inode] no allowlist matches), the
    // same transport-deferred reason as BaseHttpTest. Thread, fiber, and file-descriptor detection stay on.
    override def config = super.config.leakCheckSockets(false)

    private val cfg = configOf(appLevelOf("xapp-1"), botOf("xoxb-1"))

    private val helloFrame =
        """{"type":"hello","num_connections":1,"connection_info":{"app_id":"A1"}}"""
    private def eventFrame(id: String) =
        s"""{"type":"events_api","envelope_id":"$id","payload":{"type":"event_callback","event_id":"Ev$id","event":{"type":"message","channel":"C1","user":"U1","text":"hi","ts":"1.2"}}}"""
    private val disconnectWarning                                 = """{"type":"disconnect","reason":"warning"}"""
    private val disconnectDisabled                                = """{"type":"disconnect","reason":"link_disabled"}"""
    private val unknownNoIdFrame                                  = """{"type":"workflow_step_execute","payload":{}}"""
    private def eventFrameAccepting(id: String, accepts: Boolean) =
        eventFrame(id).replace(s""""envelope_id":"$id",""", s""""envelope_id":"$id","accepts_response_payload":$accepts,""")

    private def blockActionsFrame(id: String, responseUrl: String) =
        s"""{"type":"interactive","envelope_id":"$id","payload":{"type":"block_actions","response_url":"$responseUrl","user":{"id":"U1","username":"bob"},"trigger_id":"T1","channel":{"id":"C1","name":"general"},"actions":[{"action_id":"a1","block_id":"b1","value":"v1","type":"button"}]}}"""

    private val url = HttpUrl(Present("wss"), "test", 443, "/socket", Absent)

    private case class OkResp(ok: Boolean) derives Schema

    // The envelopes helloFrame and eventFrame(id) decode to.
    private val helloEnvelope: SlackEnvelope[?] = SlackEnvelope.Hello(1, SlackEnvelope.Hello.ConnectionInfo(SlackId.AppId("A1")))
    private def eventEnvelope(id: String): SlackEnvelope[?] = SlackEnvelope.EventsApi(
        SlackId.EnvelopeId(id),
        SlackEnvelope.EventsApi.Payload(
            SlackId.EventId(s"Ev$id"),
            SlackEvent.Message(SlackId.ChannelId("C1"), SlackId.UserId("U1"), "hi", SlackTs("1.2"))
        )
    )

    /** A test conduit: the engine reads `feed` (kept open until `close`) and writes acks
      * into `recorded`. The captured WebSocket config is recorded into `configRef`.
      */
    final private class Conduit(
        val feed: Channel[String],
        val recorded: Channel[String],
        val configRef: AtomicRef[Maybe[HttpWebSocket.Config]],
        val peerClosed: Fiber.Promise[Unit, Any]
    ) extends Transport:
        private[kyo] def connect[A, S](u: HttpUrl, c: HttpWebSocket.Config)(
            f: Transport.Conn => A < (S & Async)
        )(using Frame): A < (S & Async & Abort[SlackTransportException]) =
            configRef.set(Present(c)).andThen {
                val conn = new Transport.Conn:
                    private[kyo] def put(text: String)(using Frame): Unit < (Async & Abort[Closed]) = recorded.put(text)
                    private[kyo] def stream(using Frame): Stream[String, Async]                     = feed.streamUntilClosed()
                    private[kyo] def close(using Frame): Unit < Async                               =
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

    private val ackOf: SlackEnvelope[?] => SlackAck < (Async & Abort[SlackException]) = _ => SlackAck.Ack

    private def connectFailure(using Frame): SlackTransportException =
        SlackTransportException("socket-connect", SlackTransportException.Kind.Connect, "test", 443, Absent)(Absent)

    /** The live transport over a client of its own, closed with the test's scope. */
    private def live(using Frame): Transport < (Async & Scope) =
        HttpClient.init().map(http => Transport.live(http, cfg))

    "closing inbound publishes its buffered residue once; a second close changes nothing" in {
        for
            inbound <- Channel.initUnscoped[String](4)
            residue <- Fiber.Promise.init[Chunk[String], Any]
            _       <- inbound.put("A")
            _       <- inbound.put("B")
            _       <- SocketEngine.closeInbound(inbound, residue)
            first   <- residue.get
            _       <- SocketEngine.closeInbound(inbound, residue)
            second  <- residue.get
            closed  <- inbound.closed
        yield
            assert(first == Chunk("A", "B"))
            assert(second == Chunk("A", "B"))
            assert(closed)
        end for
    }

    // closeNow interrupts the relay, which may be inside its own closeInbound. An interrupt between the channel's close and the
    // residue's completion would leave the channel closed and the residue unpublished forever, and the loop's drain awaits it.
    // The window is narrow, so the scenario repeats on concurrent fibers until an interrupt lands in it. An interrupted fiber's
    // result completes while its uninterruptible part still runs, so a residue read at that moment may not be published yet;
    // a lost one is never published, and awaiting it is a stuck leaf. How many runs closed the channel depends on the scheduler (a
    // single-threaded one may interrupt every closer before it runs), so the count is logged and only the invariant is asserted.
    "a closeInbound interrupted at any point still publishes the residue of the channel it closed" in {
        Async.foreach(1 to 16, 16)(_ => Kyo.foreach(1 to 5000)(_ => interruptedClose)).map(_.flatten).map { all =>
            val residues = all.flatMap(_.toChunk)
            Log.info(s"closeInbound: ${residues.size} of ${all.size} interrupted runs closed the channel").andThen(
                assert(residues.forall(_ == Chunk("A")), s"got: ${residues.distinct}")
            )
        }
    }

    /** The residue of the channel an interrupted `closeInbound` closed, or `Absent` when the interrupt landed before the close. */
    private def interruptedClose(using Frame): Maybe[Chunk[String]] < (Async & Abort[Closed]) =
        for
            inbound <- Channel.initUnscoped[String](4)
            residue <- Fiber.Promise.init[Chunk[String], Any]
            _       <- inbound.put("A")
            closer  <- Fiber.initUnscoped(SocketEngine.closeInbound(inbound, residue))
            _       <- closer.interrupt
            _       <- closer.getResult
            closed  <- inbound.closed
            frames  <- Kyo.when(closed)(residue.get)
        yield frames

    "hello is delivered first, before any event" in {
        for
            c      <- conduit
            engine <- SocketEngine.initUnscoped(c, url, cfg)
            types  <- Channel.init[String](8)
            handler = (env: SlackEnvelope[?]) =>
                val tpe = env match
                    case _: SlackEnvelope.Hello     => "Hello"
                    case _: SlackEnvelope.EventsApi => "EventsApi"
                    case other                      => other.getClass.getSimpleName
                Abort.run[Closed](types.put(tpe)).andThen(SlackAck.Ack: SlackAck)
            _        <- Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, handler)))
            _        <- c.feed.put(helloFrame)
            _        <- c.feed.put(eventFrame("E1"))
            observed <- types.stream().take(2).run
            _        <- engine.closeNow
        yield assert(observed == Chunk("Hello", "EventsApi"))
        end for
    }

    "an opener interrupted at the readiness gate closes the socket the transport already opened" in {
        // The transport opens its socket, then holds before entering the connect body, as a slow
        // TLS handshake would, so the opener is suspended at the readiness gate. The socket is
        // closed when the connect call is interrupted, as a real transport closes it.
        for
            opened <- Latch.init(1)
            closed <- Latch.init(1)
            hold   <- Latch.init(1)
            slowTransport = new Transport:
                private[kyo] def connect[B, S](u: HttpUrl, cc: HttpWebSocket.Config)(
                    f: Transport.Conn => B < (S & Async)
                )(using Frame): B < (S & Async & Abort[SlackTransportException]) =
                    opened.release.andThen(Sync.ensure(closed.release)(hold.await.andThen(Abort.fail(connectFailure))))
            opener <- Fiber.initUnscoped(Abort.run[SlackException](SocketEngine.initUnscoped(slowTransport, url, cfg)))
            _      <- opened.await
            _      <- opener.interrupt
            // Interrupting completes the opener's result before its finalizers run, so the test waits
            // for the close itself; a leaked socket never releases `closed` and the leaf times out.
            _      <- closed.await
            result <- opener.getResult
        yield assert(result.isPanic, s"the opener ends interrupted; got: $result")
        end for
    }

    "closeTransport against a peer that stopped reading ends at the ack deadline and closes the socket" in {
        for
            stuck  <- Latch.init(1)
            closed <- Latch.init(1)
            feed   <- Channel.initUnscoped[String](8)
            stalled = new Transport:
                private[kyo] def connect[B, S](u: HttpUrl, cc: HttpWebSocket.Config)(
                    f: Transport.Conn => B < (S & Async)
                )(using Frame): B < (S & Async & Abort[SlackTransportException]) =
                    f(new Transport.Conn:
                        // The peer stopped reading: a put suspends and never returns.
                        private[kyo] def put(text: String)(using Frame): Unit < (Async & Abort[Closed]) = stuck.await
                        private[kyo] def stream(using Frame): Stream[String, Async]                     = feed.streamUntilClosed()
                        private[kyo] def close(using Frame): Unit < Async       = closed.release.andThen(feed.close.unit)
                        private[kyo] def onPeerClose(using Frame): Unit < Async = closed.await)
            engine <- SocketEngine.initUnscoped(stalled, url, valid(cfg.ackDeadline(1.second)))
            _      <- engine.outbound.put("""{"envelope_id":"E1"}""")
            _      <- engine.outbound.put("""{"envelope_id":"E2"}""")
            result <- Clock.withTimeControl { control =>
                for
                    teardown <- Fiber.initUnscoped(engine.closeTransport)
                    _        <- control.awaitPendingSleepers(1)
                    _        <- control.advance(1.second)
                    done     <- teardown.getResult
                yield done
            }
            closedPending <- closed.pending
        yield
            assert(result == Result.succeed(()))
            assert(closedPending == 0, s"the socket is closed after the bounded flush; still pending: $closedPending")
        end for
    }

    "connect returns only after the readiness gate completes; never returns with no hello and no relay completion" in {
        for
            ready <- Latch.init(1)
            _     <- conduit.map(c => SocketEngine.initUnscoped(c, url, cfg).andThen(ready.release).andThen(c.feed.close))
            _     <- ready.await
            neverTransport = new Transport:
                private[kyo] def connect[B, S](u: HttpUrl, cc: HttpWebSocket.Config)(
                    f: Transport.Conn => B < (S & Async)
                )(using Frame): B < (S & Async & Abort[SlackTransportException]) =
                    Latch.init(1).map(_.await.andThen(Abort.fail(connectFailure)))
            // Under virtual time the timeout fires only when the test advances the clock past it,
            // after the pending sleeper is registered, so the outcome does not depend on how
            // fast the host runs the readiness wait.
            timed <- Clock.withTimeControl { control =>
                for
                    fiber <- Fiber.initUnscoped(Abort.run[Timeout | SlackException](
                        Async.timeout(200.millis)(SocketEngine.initUnscoped(neverTransport, url, cfg))
                    ))
                    _      <- control.awaitPendingSleepers(1)
                    _      <- control.advance(200.millis)
                    result <- fiber.get
                yield result
            }
        yield assert(
            timed.failure.map(t => (t.getClass.getSimpleName, t.getMessage.contains(s"timed out after ${200.millis.show}"))) ==
                Present(("Timeout", true)),
            s"expected the 200ms readiness timeout, got: $timed"
        )
        end for
    }

    "a connect that fails before readiness fails init with that leaf" in {
        val failing = new Transport:
            private[kyo] def connect[B, S](u: HttpUrl, cc: HttpWebSocket.Config)(
                f: Transport.Conn => B < (S & Async)
            )(using Frame): B < (S & Async & Abort[SlackTransportException]) =
                Abort.fail(connectFailure)
        Abort.run[SlackException](SocketEngine.initUnscoped(failing, url, cfg).unit).map { result =>
            assert(result == Result.fail(connectFailure))
        }
    }

    "a connect that panics before readiness panics init with the same throwable" in {
        val boom      = new IllegalStateException("transport defect")
        val panicking = new Transport:
            private[kyo] def connect[B, S](u: HttpUrl, cc: HttpWebSocket.Config)(
                f: Transport.Conn => B < (S & Async)
            )(using Frame): B < (S & Async & Abort[SlackTransportException]) =
                Abort.panic(boom)
        Abort.run[SlackException](SocketEngine.initUnscoped(panicking, url, cfg).unit).map { result =>
            assert(result == Result.panic(boom))
        }
    }

    "one acked envelope produces exactly one ack frame with the matching envelope_id" in {
        for
            c      <- conduit
            engine <- SocketEngine.initUnscoped(c, url, cfg)
            _      <- Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, ackOf)))
            _      <- c.feed.put(eventFrame("E1"))
            acks   <- c.recorded.stream().take(1).run
            _      <- engine.closeNow
        yield assert(acks == Chunk("""{"envelope_id":"E1"}"""))
        end for
    }

    "N acked envelopes produce exactly N acks with matching ids" in {
        for
            c      <- conduit
            engine <- SocketEngine.initUnscoped(c, url, cfg)
            _      <- Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, ackOf)))
            _      <- c.feed.put(eventFrame("E1"))
            _      <- c.feed.put(eventFrame("E2"))
            _      <- c.feed.put(eventFrame("E3"))
            acks   <- c.recorded.stream().take(3).run
            _      <- engine.closeNow
        yield
            val ids = acks.map(a => valid(Json.decode[Wire.AckFrame](a)).envelope_id).toSet
            assert(ids == Set("E1", "E2", "E3"))
            assert(acks.size == 3)
        end for
    }

    "hello and disconnect produce zero ack frames" in {
        for
            c         <- conduit
            engine    <- SocketEngine.initUnscoped(c, url, cfg)
            delivered <- Channel.init[SlackEnvelope[?]](4)
            handler = (env: SlackEnvelope[?]) => Abort.run[Closed](delivered.put(env)).andThen(SlackAck.Ack: SlackAck)
            _ <- Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, handler)))
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
            engine    <- SocketEngine.initUnscoped(c, url, cfg)
            delivered <- Channel.init[SlackEnvelope[?]](4)
            handler = (env: SlackEnvelope[?]) => Abort.run[Closed](delivered.put(env)).andThen(SlackAck.Ack: SlackAck)
            _    <- Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, handler)))
            _    <- c.feed.put(unknownNoIdFrame)
            env  <- delivered.stream().take(1).run
            acks <- Abort.run[Closed](c.recorded.drain)
            _    <- engine.closeNow
        yield
            assert(env ==
                Chunk[SlackEnvelope[?]](SlackEnvelope.UnknownFrame("workflow_step_execute", SlackRawJsonTest.of(unknownNoIdFrame))))
            assert(acks.getOrElse(Chunk.empty) == Chunk.empty[String], s"no ack for an id-less frame, got: $acks")
        end for
    }

    "an Unknown envelope with an envelope_id is delivered and acked with exactly one bare frame" in {
        // A slash command missing its user_id: the envelope is known by its id, its payload is not.
        val frame =
            """{"type":"slash_commands","envelope_id":"U1","payload":{"command":"/deploy","text":"prod","channel_id":"C1","trigger_id":"T8"}}"""
        for
            c         <- conduit
            engine    <- SocketEngine.initUnscoped(c, url, cfg)
            delivered <- Channel.init[SlackEnvelope[?]](4)
            handler = (env: SlackEnvelope[?]) => Abort.run[Closed](delivered.put(env)).andThen(SlackAck.Ack: SlackAck)
            _    <- Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, handler)))
            _    <- c.feed.put(frame)
            _    <- c.feed.put(eventFrame("E1"))
            acks <- c.recorded.stream().take(2).run
            env  <- delivered.stream().take(2).run
            _    <- engine.closeNow
        yield
            assert(acks == Chunk("""{"envelope_id":"U1"}""", """{"envelope_id":"E1"}"""))
            assert(env.take(1) == Chunk[SlackEnvelope[?]](SlackEnvelope.Unknown(
                "slash_commands",
                SlackRawJsonTest.of(frame),
                SlackId.EnvelopeId("U1")
            )))
        end for
    }

    "an ack returned after the engine was torn down ends the loop cleanly, not with a transport failure" in {
        // The handler tears the engine down (closeNow closes `outbound`) and then returns its
        // ack, so the ack meets a closed `outbound`. Only a teardown closes `outbound`, so the
        // loop stops rather than failing.
        for
            c      <- conduit
            engine <- SocketEngine.initUnscoped(c, url, cfg)
            handler = (_: SlackEnvelope[?]) => engine.closeNow.andThen(SlackAck.Ack: SlackAck)
            loop   <- Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, handler)))
            _      <- c.feed.put(eventFrame("E1"))
            result <- loop.get
        yield assert(result == Result.Success(()), s"the loop must end cleanly after a teardown, got: $result")
        end for
    }

    "disconnect(link_disabled) ends the loop with SlackLinkDisabledException; envelope delivered first" in {
        for
            c         <- conduit
            engine    <- SocketEngine.initUnscoped(c, url, cfg)
            delivered <- Channel.init[SlackEnvelope[?]](4)
            handler = (env: SlackEnvelope[?]) => Abort.run[Closed](delivered.put(env)).andThen(SlackAck.Ack: SlackAck)
            loop   <- Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, handler)))
            _      <- c.feed.put(disconnectDisabled)
            result <- loop.get
            env    <- delivered.stream().take(1).run
            _      <- engine.closeNow
        yield
            assert(result == Result.fail(SlackLinkDisabledException()))
            assert(env == Chunk[SlackEnvelope[?]](SlackEnvelope.Disconnect(SlackEnvelope.DisconnectReason.LinkDisabled)))
        end for
    }

    "engine over a local WebSocket server observes hello, delivers the event, and acks over the socket" in {
        // The server pushes hello + one event, then forwards every client frame (the ack)
        // into `acked` so the test observes the real ack bytes that travelled the socket.
        Channel.init[String](8).map { acked =>
            val wsHandler: (HttpRequest[Any], HttpWebSocket) => Unit < (Async & Abort[Closed]) =
                (_, ws) =>
                    ws.put(HttpWebSocket.Payload.Text(helloFrame))
                        .andThen(ws.put(HttpWebSocket.Payload.Text(eventFrame("E1"))))
                        .andThen {
                            ws.stream.foreach {
                                case HttpWebSocket.Payload.Text(s) => Abort.run[Closed](acked.put(s)).unit
                                case _                             => Kyo.unit
                            }
                        }
            HttpServer.init(0, "127.0.0.1")(HttpHandler.webSocket("ws/slack")(wsHandler)).map { server =>
                val wsUrl = HttpUrl(Present("ws"), "127.0.0.1", server.port, "/ws/slack", Absent)
                live.map(transport => SocketEngine.initUnscoped(transport, wsUrl, cfg)).map { engine =>
                    Channel.init[SlackEnvelope[?]](8).map { delivered =>
                        val handler: SlackEnvelope[?] => SlackAck < (Async & Abort[SlackException]) =
                            env => Abort.run[Closed](delivered.put(env)).andThen(SlackAck.Ack: SlackAck)
                        Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, handler))).map { _ =>
                            // The hello is delivered first, then the event; the engine acks E1.
                            delivered.stream().take(2).run.map { envs =>
                                acked.stream().take(1).run.map { acks =>
                                    engine.closeNow.andThen {
                                        assert(envs == Chunk(helloEnvelope, eventEnvelope("E1")), s"hello then the event, got: $envs")
                                        assert(acks == Chunk("""{"envelope_id":"E1"}"""), s"real socket ack, got: $acks")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "an abnormal peer close (feed ends with no disconnect frame) terminates the loop, no hang" in {
        // No disconnect frame: the conduit feed just ends (transport EOF). The relay's
        // receiver leg terminates, the race resolves, and the engine closes inbound, so the
        // receive loop observes a clean stream end and the loop returns rather than blocking
        // forever on a frame that will never arrive.
        for
            c       <- conduit
            engine  <- SocketEngine.initUnscoped(c, url, cfg)
            entered <- Latch.init(1)
            handler = (_: SlackEnvelope[?]) => entered.release.andThen(SlackAck.Ack: SlackAck)
            loop <- Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, handler)))
            _    <- c.feed.put(eventFrame("E1"))
            _    <- entered.await
            // End the feed with no disconnect frame, mimicking an abnormal transport EOF.
            _ <- c.feed.close
            // loop.get IS the termination barrier: it completes exactly when the receive loop returns. A loop that never terminates (a hang) is
            // caught by the suite's per-leaf cap.
            result <- loop.get
            _      <- engine.closeNow
        yield assert(result == Result.Success(()), s"loop must terminate cleanly on abnormal close, got: $result")
        end for
    }

    "closeNow tears down the socket and relay observably; second closeNow is a no-op" in {
        for
            c      <- conduit
            engine <- SocketEngine.initUnscoped(c, url, cfg)
            _      <- c.feed.put(helloFrame)
            _      <- engine.closeNow
            // The feed channel is closed by closeNow -> conn.close; a put now aborts Closed,
            // proving the teardown propagated to the conduit conn.
            putRes <- Abort.run[Closed](c.feed.put("after-close"))
            _      <- engine.relay.getResult
            _      <- engine.closeNow
        yield assert(putRes.failure.map(_.getClass.getSimpleName) == Present("Closed"), s"feed put after closeNow, got: $putRes")
        end for
    }

    "a skipped frame is logged by its size or its parse failure's kind and position, never its text, and the loop continues" in {
        val secretPayload =
            s"""{"response_url":"${WireTest.responseUrl}","token":"${WireTest.tokenSecret}","link":"${WireTest.link}"}"""
        val typeless = s"""{"envelope_id":"E0","payload":$secretPayload}"""
        val notJson  = s"not json $secretPayload"
        for
            sink   <- SocketEngineTest.LogSink.init
            c      <- conduit
            engine <- SocketEngine.initUnscoped(c, url, cfg)
            _      <- Log.let(Log(sink))(Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, ackOf))))
            _      <- c.feed.put(typeless)
            _      <- c.feed.put(notJson)
            _      <- c.feed.put(eventFrame("E1"))
            acks   <- c.recorded.stream().take(1).run
            _      <- engine.closeNow
            lines  <- sink.engineLines
            all    <- sink.everything
        yield
            assert(acks == Chunk("""{"envelope_id":"E1"}"""))
            assert(lines == Chunk(
                SocketEngineTest.Line(
                    "warn",
                    s"SocketEngine: skipping uncorrelatable frame: frame has no type field (${typeless.length} characters)",
                    Absent
                ),
                SocketEngineTest.Line(
                    "warn",
                    "SocketEngine: skipping uncorrelatable frame: frame is not JSON: unparseable input at position 0",
                    Absent
                )
            ))
            assert(WireTest.secrets.forall(s => !all.contains(s)), "a logged line holds part of a skipped frame")
        end for
    }

    "keepAliveInterval is wired into the WebSocket config passed to the transport" in {
        for
            present <- capturedConfig(valid(cfg.keepAliveInterval(Present(45.seconds))))
            absent  <- capturedConfig(valid(cfg.keepAliveInterval(Absent)))
        yield
            // Each result is (configWasCaptured, autoPingInterval); the inner Maybe is NOT
            // nested in an outer Maybe (the Maybe-of-Absent flattening pitfall).
            assert(present == (true, Present(45.seconds)), s"expected (true, Present(45s)), got: $present")
            assert(absent == (true, Absent), s"expected (true, Absent), got: $absent")
        end for
    }

    // --- Handler outcomes over a local Socket Mode server ---

    /** A local Socket Mode endpoint: pushes each frame put on `feed`, records each frame the client
      * sends in `acked`, in order, and releases `closed` once the client socket has closed. `transport`
      * is the live transport on a client of the test's own.
      */
    final private class LocalSocket(
        val url: HttpUrl,
        val transport: Transport,
        val feed: Channel[String],
        val acked: Channel[String],
        val closed: Latch
    )

    private def localSocket(using Frame): LocalSocket < (Async & Scope & Abort[HttpException]) =
        for
            feed      <- Channel.init[String](64)
            acked     <- Channel.init[String](64)
            closed    <- Latch.init(1)
            transport <- live
            server    <- HttpServer.init(0, "127.0.0.1")(HttpHandler.webSocket("ws/slack") { (_, ws) =>
                val push = feed.streamUntilClosed().foreach(f => ws.put(HttpWebSocket.Payload.Text(f)))
                val read = ws.stream.foreach {
                    case HttpWebSocket.Payload.Text(s) => acked.put(s)
                    case _                             => Kyo.unit
                }
                Async.race(Abort.run[Closed](push).unit, Abort.run[Closed](read).unit).andThen(closed.release)
            })
        yield LocalSocket(HttpUrl(Present("ws"), "127.0.0.1", server.port, "/ws/slack", Absent), transport, feed, acked, closed)

    private def ack(id: String): String = s"""{"envelope_id":"$id"}"""

    private def idOf(env: SlackEnvelope[?]): Maybe[String] =
        env match
            case e: SlackEnvelope.EventsApi    => Present(e.envelopeId.value)
            case e: SlackEnvelope.Interactive  => Present(e.envelopeId.value)
            case e: SlackEnvelope.SlashCommand => Present(e.envelopeId.value)
            case _                             => Absent

    private type Handler = SlackEnvelope[?] => SlackAck < (Async & Abort[SlackException])

    /** The receive loop as `run` and `receive` drive it, with a routine disconnect ending it. */
    private def loopOf(engine: SocketEngine, handler: Handler)(using
        Frame
    ): Unit < (Async & Abort[SlackException]) =
        Reconnect.newDedup.map(dedup => engine.receiveLoopWithReconnect(handler, dedup, _ => Reconnect.Reaction.Stop).unit)

    /** The acks the server received once the client socket closed, drained without waiting. */
    private def ackedAfterClose(sock: LocalSocket)(using Frame): Chunk[String] < Async =
        sock.closed.await.andThen(Abort.run[Closed](sock.acked.drain).map(_.getOrElse(Chunk.empty)))

    private val boom = new IllegalStateException("handler defect")

    private def panicking(using Frame): SlackAck < (Async & Abort[SlackException]) = Abort.panic(boom)

    "a handler's typed failure ends the loop with it and leaves its envelope unacknowledged" in {
        val failure = SlackChannelNotFoundException("chat.postMessage", Chunk.empty)
        for
            sock   <- localSocket
            engine <- SocketEngine.initUnscoped(sock.transport, sock.url, cfg)
            handler: Handler = env => if idOf(env) == Present("A") then Abort.fail(failure) else SlackAck.Ack
            loop   <- Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, handler)))
            _      <- sock.feed.put(eventFrame("E0"))
            first  <- sock.acked.stream().take(1).run
            _      <- sock.feed.put(eventFrame("A"))
            result <- loop.get
            _      <- engine.closeNow
            rest   <- ackedAfterClose(sock)
        yield
            assert(result == Result.fail(failure), s"the loop ends with the handler's failure, got: $result")
            assert(first ++ rest == Chunk(ack("E0")), s"no ack for A, got: ${first ++ rest}")
        end for
    }

    "a handler's panic is logged with the envelope's type and id, leaves the envelope unacknowledged, and the loop goes on" in {
        for
            sock   <- localSocket
            sink   <- SocketEngineTest.LogSink.init
            engine <- SocketEngine.initUnscoped(sock.transport, sock.url, cfg)
            handler: Handler = env => if idOf(env) == Present("A") then panicking else SlackAck.Ack
            loop <- Log.let(Log(sink))(Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, handler))))
            _    <- sock.feed.put(eventFrame("A"))
            _    <- sock.feed.put(eventFrame("B"))
            _    <- sock.feed.put(eventFrame("C"))
            // The loop ending first would be the panic propagating; the acks arriving first is the loop going on.
            outcome <- Async.race(loop.getResult.map(r => Chunk(s"loop ended: $r")), sock.acked.stream().take(2).run)
            _       <- engine.closeNow
            rest    <- ackedAfterClose(sock)
            lines   <- sink.engineLines
        yield
            assert(outcome ++ rest == Chunk(ack("B"), ack("C")), s"B and C acked, A not, got: ${outcome ++ rest}")
            assert(lines == Chunk(SocketEngineTest.Line("error", "SocketEngine: handler panicked on events_api envelope A", Present(boom))))
        end for
    }

    "a handler's panic on hello or on a routine disconnect follows the same policy" in {
        for
            sock   <- localSocket
            sink   <- SocketEngineTest.LogSink.init
            engine <- SocketEngine.initUnscoped(sock.transport, sock.url, cfg)
            handler: Handler = {
                case _: SlackEnvelope.Hello | _: SlackEnvelope.Disconnect => panicking
                case _                                                    => SlackAck.Ack
            }
            loop <- Log.let(Log(sink))(Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, handler))))
            _    <- sock.feed.put(helloFrame)
            _    <- sock.feed.put(eventFrame("E1"))
            // Teardown drops acks not yet sent, so the disconnect goes out only once E1's ack arrived.
            first  <- sock.acked.take
            _      <- sock.feed.put(disconnectWarning)
            result <- loop.get
            _      <- engine.closeNow
            rest   <- ackedAfterClose(sock)
            lines  <- sink.engineLines
        yield
            assert(result == Result.succeed(()), s"the routine disconnect still ends the loop as a disconnect, got: $result")
            assert(first +: rest == Chunk(ack("E1")), s"E1 acked after the hello panic, got: ${first +: rest}")
            assert(lines == Chunk(
                SocketEngineTest.Line("error", "SocketEngine: handler panicked on hello envelope", Present(boom)),
                SocketEngineTest.Line("error", "SocketEngine: handler panicked on disconnect envelope", Present(boom))
            ))
        end for
    }

    "a link_disabled disconnect whose handler panics still ends the loop with SlackLinkDisabledException, and the panic is logged" in {
        for
            sock   <- localSocket
            sink   <- SocketEngineTest.LogSink.init
            engine <- SocketEngine.initUnscoped(sock.transport, sock.url, cfg)
            handler = (_: SlackEnvelope[?]) => panicking
            loop   <- Log.let(Log(sink))(Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, handler))))
            _      <- sock.feed.put(disconnectDisabled)
            result <- loop.get
            _      <- engine.closeNow
            lines  <- sink.engineLines
        yield
            assert(result == Result.fail(SlackLinkDisabledException()), s"got: $result")
            assert(lines == Chunk(SocketEngineTest.Line("error", "SocketEngine: handler panicked on disconnect envelope", Present(boom))))
        end for
    }

    "the deadline winning the race sends one bare ack, and the interrupted handler is not logged as a panic" in {
        // The handler parks on a latch nothing releases, so the only ack that can go out is the deadline's bare one.
        // No keepalive: the deadline's sleeper must be the only one on this clock, so that awaiting one pending
        // sleeper awaits exactly it, and the single advance cannot run before it is registered.
        val deadlineCfg = valid(cfg.ackDeadline(3.seconds).flatMap(_.keepAliveInterval(Absent)))
        for
            sock <- localSocket
            sink <- SocketEngineTest.LogSink.init
            acks <- Clock.withTimeControl { control =>
                for
                    engine  <- SocketEngine.initUnscoped(sock.transport, sock.url, deadlineCfg)
                    entered <- Latch.init(1)
                    never   <- Latch.init(1)
                    handler = (_: SlackEnvelope[?]) => entered.release.andThen(never.await).andThen(SlackAck.Ack: SlackAck)
                    _     <- Log.let(Log(sink))(Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, handler))))
                    _     <- sock.feed.put(eventFrame("E1"))
                    _     <- entered.await
                    _     <- control.awaitPendingSleepers(1)
                    _     <- control.advance(deadlineCfg.ackDeadline)
                    first <- sock.acked.take
                    _     <- engine.closeNow
                    rest  <- ackedAfterClose(sock)
                yield first +: rest
            }
            lines <- sink.engineLines
        yield
            assert(acks == Chunk(ack("E1")), s"exactly one bare ack for E1, got: $acks")
            assert(lines == Chunk.empty[SocketEngineTest.Line], s"nothing logged, got: $lines")
        end for
    }

    "a handler returning a payload within the deadline has that payload acked" in {
        // Under time control the deadline cannot fire, since the clock is never advanced.
        Clock.withTimeControl { _ =>
            for
                sock   <- localSocket
                engine <- SocketEngine.initUnscoped(sock.transport, sock.url, cfg)
                handler = (_: SlackEnvelope[?]) =>
                    SlackAck.CommandResponse(SlackAck.CommandResponse.Visibility.InChannel, "done"): SlackAck < (
                        Async & Abort[SlackException]
                    )
                _    <- Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, handler)))
                _    <- sock.feed.put(eventFrame("E1"))
                acks <- sock.acked.stream().take(1).run
                _    <- engine.closeNow
            yield assert(acks == Chunk("""{"envelope_id":"E1","payload":{"response_type":"in_channel","text":"done"}}"""))
            end for
        }
    }

    "a payload-bearing ack is sent unless the envelope says it accepts no response payload, which is logged" in {
        for
            sink   <- SocketEngineTest.LogSink.init
            sock   <- localSocket
            engine <- SocketEngine.initUnscoped(sock.transport, sock.url, cfg)
            handler: Handler = env =>
                if idOf(env).exists(_.startsWith("C")) then
                    SlackAck.CommandResponse(SlackAck.CommandResponse.Visibility.Ephemeral, "queued")
                else SlackAck.ViewResponse(SlackAck.ViewAction.Clear)
            _     <- Log.let(Log(sink))(Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, handler))))
            _     <- sock.feed.put(eventFrameAccepting("E1", accepts = false))
            _     <- sock.feed.put(eventFrameAccepting("E2", accepts = true))
            _     <- sock.feed.put(eventFrame("E3"))
            _     <- sock.feed.put(eventFrameAccepting("C4", accepts = false))
            _     <- sock.feed.put(eventFrameAccepting("C5", accepts = true))
            _     <- sock.feed.put(eventFrame("C6"))
            acks  <- sock.acked.stream().take(6).run
            _     <- engine.closeNow
            lines <- sink.engineLines
        yield
            val command = """"payload":{"response_type":"ephemeral","text":"queued"}"""
            assert(
                acks == Chunk(
                    """{"envelope_id":"E1"}""",
                    """{"envelope_id":"E2","payload":{"response_action":"clear"}}""",
                    """{"envelope_id":"E3","payload":{"response_action":"clear"}}""",
                    """{"envelope_id":"C4"}""",
                    s"""{"envelope_id":"C5",$command}""",
                    s"""{"envelope_id":"C6",$command}"""
                ),
                s"acks: $acks"
            )
            def withheld(id: String, kind: String) = SocketEngineTest.Line(
                "warn",
                s"SocketEngine: envelope $id does not accept a response payload; the $kind payload was not sent",
                Absent
            )
            assert(lines == Chunk(withheld("E1", "ViewResponse"), withheld("C4", "CommandResponse")), s"lines: $lines")
        end for
    }

    "an interrupted loop leaves the in-flight envelope unacknowledged and logs no panic" in {
        for
            sock    <- localSocket
            sink    <- SocketEngineTest.LogSink.init
            engine  <- SocketEngine.initUnscoped(sock.transport, sock.url, cfg)
            entered <- Latch.init(1)
            parked  <- Latch.init(1)
            handler = (_: SlackEnvelope[?]) => entered.release.andThen(parked.await).andThen(SlackAck.Ack: SlackAck)
            loop  <- Log.let(Log(sink))(Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, handler))))
            _     <- sock.feed.put(eventFrame("E1"))
            _     <- entered.await
            _     <- loop.interrupt
            res   <- loop.getResult
            _     <- engine.closeNow
            acks  <- ackedAfterClose(sock)
            lines <- sink.engineLines
        yield
            assert(res.panic.map(_.getClass.getSimpleName) == Present("Interrupted"), s"got: $res")
            assert(acks == Chunk.empty[String], s"no ack on interrupt, got: $acks")
            assert(lines == Chunk.empty[SocketEngineTest.Line], s"nothing logged, got: $lines")
        end for
    }

    "an envelope whose handler panicked is delivered again when Slack pushes its id again, and acked once it is handled" in {
        for
            sock    <- localSocket
            engine  <- SocketEngine.initUnscoped(sock.transport, sock.url, cfg)
            handled <- Channel.init[(Maybe[String], Int)](8)
            attempt <- AtomicInt.init(0)
            handler: Handler = env =>
                attempt.incrementAndGet.map { n =>
                    Abort.run[Closed](handled.put((idOf(env), n))).andThen(if n == 1 then panicking else SlackAck.Ack)
                }
            loop   <- Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, handler)))
            _      <- sock.feed.put(eventFrame("E1"))
            _      <- sock.feed.put(eventFrame("E1"))
            _      <- sock.feed.put(eventFrame("E1"))
            _      <- sock.feed.put(eventFrame("E2"))
            first  <- Async.race(loop.getResult.map(r => Chunk(s"loop ended: $r")), sock.acked.stream().take(3).run)
            _      <- engine.closeNow
            rest   <- ackedAfterClose(sock)
            record <- Abort.run[Closed](handled.drain)
        yield
            assert(first ++ rest == Chunk(ack("E1"), ack("E1"), ack("E2")), s"got: ${first ++ rest}")
            assert(
                record.getOrElse(Chunk.empty) == Chunk((Present("E1"), 1), (Present("E1"), 2), (Present("E2"), 3)),
                s"the third E1 is acked without a delivery, got: $record"
            )
        end for
    }

    "a response_url answer forked from the handler does not hold up the next envelope, and completes after the acks" in {
        // The POST answers only after E2's handler ran, so a loop that waited on the answer before taking E2 would
        // never finish or would get E2 only after the POST timed out: the leaf passes only when E2 is handled and
        // acked while the POST is in flight, and the POST then succeeds.
        for
            sink      <- SocketEngineTest.LogSink.init
            e2Handled <- Latch.init(1)
            answered  <- Fiber.Promise.init[Result[SlackReplaceOriginalFailure, Unit], Any]
            route = HttpRoute.postRaw("hook").response(_.bodyText).handler { _ =>
                e2Handled.await.andThen(HttpResponse(HttpStatus.OK).addField("body", """{"ok":true}"""))
            }
            server <- HttpServer.init(0, "127.0.0.1")(route)
            sock   <- localSocket
            engine <- SocketEngine.initUnscoped(sock.transport, sock.url, cfg)
            replace          = (url: SlackResponseUrl) => Slack.run(cfg)(Slack.replaceOriginal(url, SlackReply("updated")))
            handler: Handler = {
                case SlackEnvelope.Interactive(_, click: SlackInteraction.BlockActions, _, _, _) =>
                    click.responseUrl match
                        case Present(url) =>
                            Fiber.initUnscoped(
                                Abort.run[SlackReplaceOriginalFailure](replace(url))
                                    .map(r => answered.complete(Result.succeed(r)).unit)
                            ).andThen(SlackAck.Ack)
                        case Absent => SlackAck.Ack
                case _ => e2Handled.release.andThen(SlackAck.Ack)
            }
            _      <- Log.let(Log(sink))(Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, handler))))
            _      <- sock.feed.put(blockActionsFrame("E1", s"http://127.0.0.1:${server.port}/hook"))
            _      <- sock.feed.put(eventFrame("E2"))
            acks   <- sock.acked.stream().take(2).run
            result <- answered.get
            _      <- engine.closeNow
            lines  <- sink.engineLines
        yield
            assert(acks == Chunk(ack("E1"), ack("E2")))
            assert(result == Result.unit)
            assert(lines == Chunk.empty[SocketEngineTest.Line], s"lines: $lines")
        end for
    }

    private def capturedConfig(config: SlackConfig)(using
        Frame
    ): (Boolean, Maybe[Duration]) < (Async & Abort[SlackException | Closed] & Scope) =
        for
            c         <- conduit
            engine    <- SocketEngine.initUnscoped(c, url, config)
            delivered <- Channel.init[SlackEnvelope[?]](2)
            handler = (env: SlackEnvelope[?]) => Abort.run[Closed](delivered.put(env)).andThen(SlackAck.Ack: SlackAck)
            _ <- Fiber.initUnscoped(Abort.run[SlackException](loopOf(engine, handler)))
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

end SocketEngineTest

private[kyo] object SocketEngineTest:

    final case class Line(level: String, message: String, error: Maybe[Throwable]) derives CanEqual

    /** A log that keeps every line in order, with its level and the throwable passed beside it. */
    final class LogSink private (lines: AtomicRef.Unsafe[Chunk[Line]]) extends Log.Unsafe:
        val name: String                    = "SocketEngineTest"
        val level: Log.Level                = Log.Level.trace
        def withName(n: String): Log.Unsafe = this

        private def record(level: String, msg: String, t: Maybe[Throwable])(using AllowUnsafe): Unit =
            discard(lines.getAndUpdate(_ :+ Line(level, msg, t)))

        def trace(msg: => String)(using Frame, AllowUnsafe): Unit                  = record("trace", msg, Absent)
        def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record("trace", msg, Present(t))
        def debug(msg: => String)(using Frame, AllowUnsafe): Unit                  = record("debug", msg, Absent)
        def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record("debug", msg, Present(t))
        def info(msg: => String)(using Frame, AllowUnsafe): Unit                   = record("info", msg, Absent)
        def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = record("info", msg, Present(t))
        def warn(msg: => String)(using Frame, AllowUnsafe): Unit                   = record("warn", msg, Absent)
        def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = record("warn", msg, Present(t))
        def error(msg: => String)(using Frame, AllowUnsafe): Unit                  = record("error", msg, Absent)
        def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record("error", msg, Present(t))

        /** The lines the engine wrote, in order. */
        def engineLines(using Frame): Chunk[Line] < Async = linesFrom("SocketEngine")

        /** The lines whose message starts with `prefix`, in order. */
        def linesFrom(prefix: String)(using Frame): Chunk[Line] < Async =
            // Unsafe: reads the unsafe AtomicRef the Log.Unsafe backend records into, after the flush.
            Log.flush.andThen(Sync.Unsafe.defer(lines.get().filter(_.message.startsWith(prefix))))

        /** Every line and every throwable's rendering along its cause chain, as one text. */
        def everything(using Frame): String < Async =
            // Unsafe: reads the unsafe AtomicRef the Log.Unsafe backend records into, after the flush.
            Log.flush.andThen(Sync.Unsafe.defer {
                lines.get().map { line =>
                    val chain = line.error.fold("") { t =>
                        Iterator.iterate[Throwable](t)(
                            _.getCause
                        ).takeWhile(_ != null).take(8).map(e => s"$e ${e.getMessage}").mkString(" ")
                    }
                    s"${line.message} $chain"
                }.mkString("\n")
            })
    end LogSink

    object LogSink:
        // Unsafe: Log.let takes a Log.Unsafe backend, whose methods run with AllowUnsafe and record into an unsafe AtomicRef.
        def init(using Frame): LogSink < Sync =
            Sync.Unsafe.defer(new LogSink(AtomicRef.Unsafe.init(Chunk.empty[Line])))
    end LogSink

end SocketEngineTest
