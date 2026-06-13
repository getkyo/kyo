package kyo

import kyo.internal.SlackSocketEngine
import kyo.internal.SlackSocketHandle
import kyo.internal.SlackTransport

/** Cross-platform tests for the manually-managed `SlackConnection`: `receive` drives
  * the real receive loop over an in-memory transport conduit (real frames, real acks),
  * and `close` is total and idempotent (the row carries no `Abort`). Teardown is
  * observed via a latch released by the conduit `close`, never a sleep. The
  * apps.connections.open HTTP handshake is real I/O, so the full `connectUnscoped`
  * handshake-to-socket path is the JVM live test; here the connection is built over the
  * in-memory transport so the receive/close logic runs on all four platforms.
  */
class SlackConnectionTest extends kyo.test.Test[Any]:

    private val cfg = SlackConfig(SlackToken.AppLevel("xapp-1"), SlackToken.Bot("xoxb-1"))
    private val url = "wss://test/socket"

    private val helloFrame =
        """{"type":"hello","num_connections":1,"connection_info":{"app_id":"A1"}}"""
    private def eventFrame(id: String) =
        s"""{"type":"events_api","envelope_id":"$id","payload":{"type":"event_callback","event":{"type":"message","channel":"C1","user":"U1","text":"hi","ts":"1.2"}}}"""

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

    private def connection(scripted: Seq[String])(using Frame): (SlackConnection, Conduit) < (Async & Abort[SlackException]) =
        for
            recorded <- Channel.initUnscoped[String](64)
            closed   <- Latch.init(1)
            feed     <- Channel.initUnscoped[String](64)
            conduit = Conduit(scripted, recorded, closed, feed)
            engine <- SlackSocketEngine.initUnscoped(conduit, url, cfg)
            handle <- SlackSocketHandle.fromEngine(engine, cfg)
        yield (SlackConnection.fromHandle(handle), conduit)

    "the receive and close extensions resolve on SlackConnection" in {
        typeCheck("(c: SlackConnection) => c.close")
        typeCheck("(c: SlackConnection) => c.receive((_: SlackEnvelope) => SlackAck.Ack)")
    }

    "receive drives the real loop: an event is delivered and acked with its envelope_id" in {
        for
            pair <- connection(Seq(helloFrame, eventFrame("E1")))
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

end SlackConnectionTest
