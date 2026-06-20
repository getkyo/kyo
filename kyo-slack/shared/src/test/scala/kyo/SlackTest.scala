package kyo

import kyo.internal.SlackSocketEngine
import kyo.internal.SlackSocketHandle
import kyo.internal.SlackTransport
import kyo.internal.SlackWebApi

/** Cross-platform tests for the public `Slack` entry: the `run`/`init` signatures,
  * the bound-token receive path, and the manually-managed connection surface. `receive`
  * drives the real receive loop over an in-memory transport conduit (real frames, real
  * acks), and `close` is total and idempotent (the row carries no `Abort`). Teardown is
  * observed via a latch released by the conduit `close`, never a sleep. The
  * apps.connections.open HTTP handshake is real I/O, so the full `Slack.init`
  * handshake-to-socket path is the JVM live test; here the connection is built over the
  * in-memory transport so the receive/close logic runs on all four platforms.
  */
class SlackTest extends kyo.test.Test[Any]:

    "Slack.run resolves with its public signature" in {
        typeCheck(
            "Slack.run(SlackConfig(SlackToken.AppLevel(\"x\"), SlackToken.Bot(\"y\")))((_: SlackEnvelope) => SlackAck.Ack)"
        )
    }

    "the concise run example reads idiomatically: a typed event match returning a SlackAck" in {
        typeCheck(
            "Slack.run(SlackConfig(SlackToken.AppLevel(\"x\"), SlackToken.Bot(\"y\"))) { case SlackEnvelope.EventsApi(_, SlackEvent.Message(ch, _, text, _, _)) => Slack.chatPostMessage(SlackMessage(ch, s\"echo: $text\")).andThen(SlackAck.Ack); case _ => SlackAck.Ack }"
        )
    }

    private val cfg = SlackConfig(SlackToken.AppLevel("xapp-1"), SlackToken.Bot("xoxb-1"))
    private val url = "wss://test/socket"
    private val helloFrame =
        """{"type":"hello","num_connections":1,"connection_info":{"app_id":"A1"}}"""
    private val eventFrame =
        """{"type":"events_api","envelope_id":"E1","payload":{"type":"event_callback","event":{"type":"message","channel":"C1","user":"U1","text":"hi","ts":"1.2"}}}"""
    private def eventFrameWithId(id: String) =
        s"""{"type":"events_api","envelope_id":"$id","payload":{"type":"event_callback","event":{"type":"message","channel":"C1","user":"U1","text":"hi","ts":"1.2"}}}"""

    // A Web API call from inside the receive-loop handler resolves the bot token bound
    // around the loop body. The loop is driven directly with the SlackWebApi.local binding
    // around the loop body, the exact scope the handler runs under, so the ambient token
    // resolves on the handler fiber. The handler reads the bound token via
    // SlackWebApi.local.use AND issues a real Slack.authTest against an unreachable base
    // url: it fails with a SlackTransportException (the token resolved and the request was
    // attempted), NOT a SlackHandshakeException (which an UNbound token would produce). This
    // gives the init handler path Web-API parity with the scoped run.
    "a Web API call from inside an init + receive handler resolves the bound token" in {
        SlackTransport.inMemory(Chunk(helloFrame, eventFrame)).map { case (transport, _) =>
            SlackSocketEngine.initUnscoped(transport, "wss://test/socket", cfg).map { engine =>
                Channel.init[Maybe[SlackToken.Bot]](4).map { tokenSeen =>
                    Channel.init[SlackException](4).map { apiOutcome =>
                        val handler: SlackEnvelope => SlackAck < (Async & Abort[SlackException]) =
                            case _: SlackEnvelope.EventsApi =>
                                SlackWebApi.local.use { bound =>
                                    Abort.run[Closed](tokenSeen.put(bound)).andThen {
                                        Abort.run[SlackException](Slack.authTest).map {
                                            case Result.Failure(ex: SlackException) =>
                                                Abort.run[Closed](apiOutcome.put(ex)).andThen(SlackAck.Ack: SlackAck)
                                            case _ => SlackAck.Ack: SlackAck
                                        }
                                    }
                                }
                            case _ => SlackAck.Ack: SlackAck
                        end handler
                        // Bind the bot token around the loop body, exactly where receive binds it,
                        // and point the Web API at an unreachable base so authTest attempts a real
                        // request and fails at the transport (proving the token resolved).
                        SlackWebApi.baseUrl.let("http://127.0.0.1:1/api") {
                            SlackWebApi.local.let(Present(cfg.bot)) {
                                Fiber.initUnscoped(Abort.run[SlackException](engine.receiveLoop(handler))).map { _ =>
                                    tokenSeen.stream().take(1).run.map { tokens =>
                                        assert(
                                            tokens.head == Present(cfg.bot),
                                            s"handler must observe the bound token, got: ${tokens.head}"
                                        )
                                        apiOutcome.stream().take(1).run.map { outcomes =>
                                            outcomes.head match
                                                case _: SlackTransportException => assert(true)
                                                case _: SlackHandshakeException =>
                                                    assert(false, "token was NOT bound around the loop (the init Local bug)")
                                                case other => assert(false, s"expected SlackTransportException, got: $other")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** A conduit that streams `scripted` (then stays open until close), records acks,
      * and releases `closed` on `conn.close` so teardown is observable.
      */
    final private class Conduit(
        scripted: Seq[String],
        val recorded: Channel[String],
        val closed: Latch,
        feed: Channel[String]
    ) extends SlackTransport:
        private[kyo] def connect[A, S](u: String, c: HttpWebSocket.Config)(
            f: SlackTransport.Conn => A < (S & Async & Abort[SlackException])
        )(using Frame): A < (S & Async & Abort[SlackException]) =
            Kyo.foreach(scripted)(fr => Abort.run[Closed](feed.put(fr))).andThen {
                val conn = new SlackTransport.Conn:
                    private[kyo] def put(text: String)(using Frame): Unit < (Async & Abort[Closed]) = recorded.put(text)
                    private[kyo] def stream(using Frame): Stream[String, Async]                     = feed.streamUntilClosed()
                    private[kyo] def close(using Frame): Unit < Async =
                        // Close only the inbound feed (stopping the receiver). Leave the
                        // recorded ack sink OPEN so a test can drain pre-teardown acks
                        // without racing a Closed on the sink.
                        closed.release.andThen(feed.close.unit)
                    private[kyo] def onPeerClose(using Frame): Unit < Async = closed.await
                f(conn)
            }
    end Conduit

    private def connection(scripted: Seq[String])(using Frame): (Slack, Conduit) < (Async & Abort[SlackException]) =
        for
            recorded <- Channel.initUnscoped[String](64)
            closed   <- Latch.init(1)
            feed     <- Channel.initUnscoped[String](64)
            conduit = Conduit(scripted, recorded, closed, feed)
            engine <- SlackSocketEngine.initUnscoped(conduit, url, cfg)
            handle <- SlackSocketHandle.fromEngine(engine, cfg)
        yield (Slack.fromHandle(handle), conduit)

    "the receive and close extensions resolve on Slack" in {
        typeCheck("(c: Slack) => c.close")
        typeCheck("(c: Slack) => c.receive((_: SlackEnvelope) => SlackAck.Ack)")
    }

    "receive drives the real loop: an event is delivered and acked with its envelope_id" in {
        for
            pair <- connection(Seq(helloFrame, eventFrameWithId("E1")))
            (conn, conduit) = pair
            delivered <- Channel.init[String](8)
            handler: (SlackEnvelope => SlackAck < (Async & Abort[SlackException])) = (env: SlackEnvelope) =>
                env match
                    case e: SlackEnvelope.EventsApi =>
                        Abort.run[Closed](delivered.put(e.meta.envelopeId.value)).andThen(SlackAck.Ack: SlackAck)
                    case _ => SlackAck.Ack: SlackAck
            loop <- Fiber.initUnscoped(Abort.run[SlackException](conn.receive(handler)))
            ids  <- delivered.stream().take(1).run
            acks <- conduit.recorded.stream().take(1).run
            _    <- conn.close
            _    <- loop.interrupt
        yield
            assert(ids == Chunk("E1"), s"E1 delivered, got: $ids")
            assert(acks == Chunk("""{"envelope_id":"E1"}"""), s"E1 acked, got: $acks")
        end for
    }

    "close is total and idempotent: closing twice tears the loop down once and the second close is a no-op" in {
        for
            pair <- connection(Seq(helloFrame))
            (conn, conduit) = pair
            loop <- Fiber.initUnscoped(Abort.run[SlackException](conn.receive((_: SlackEnvelope) => SlackAck.Ack: SlackAck)))
            // First close releases the teardown latch; the close row is Unit < Async (no Abort),
            // so the test compiles only because close never aborts.
            _ <- conn.close
            _ <- conduit.closed.await
            // Observable post-state: the close propagated to the receive loop. The engine's
            // inbound is now closed, so the loop's next take aborts Closed and it ends cleanly
            // (Reaction.Stop -> Unit), without an interrupt.
            loopResult <- loop.get
            // A second close is a no-op on the already-closed engine and surfaces no error.
            _ <- conn.close
            // Second observable post-state: a receive started AFTER close stops immediately,
            // because the engine is closed (its inbound is closed), proving "behaves as closed".
            postCloseReceive <- conn.receive((_: SlackEnvelope) => SlackAck.Ack: SlackAck)
        yield
            assert(loopResult == Result.Success(()), s"close tears the loop down cleanly; got: $loopResult")
            assert(postCloseReceive == (), "a receive after close returns immediately on the closed engine")
        end for
    }

end SlackTest
