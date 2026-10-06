package kyo.net.internal

import kyo.*
import kyo.net.*
import kyo.scheduler.IOPromise

/** End-to-end regression guard for the peer-close grace reclaim on every backend. A never-draining server handler and a cap-1 inbound channel
  * park the accepted-side ReadPump with no armed read (the first chunk fills the channel, the second overflows); the client then closes, and
  * each backend's `isPeerClosed` observes the FIN so the grace timer reclaims the accepted connection. The grace runs on the transport's
  * controlled clock, so it expires only when the leaf advances it. The in-leaf oracle is the captured accepted connection's `isOpen`
  * (portable); on Linux the fork's `/proc/self/fd` leak check is a second oracle for the reclaimed fd.
  */
class TransportBackpressureReclaimTest extends kyo.net.Test:

    import AllowUnsafe.embrace.danger

    /** Expire the grace until the connection is reclaimed. An expiry that lands before the FIN is observed re-arms the timer, so each round
      * waits for either the reclaim or the re-armed timer, and advances exactly one grace on the latter.
      */
    private def expireUntilReclaimed(tc: Clock.TimeControl, conn: Connection, grace: Duration)(using Frame): Unit < Async =
        Loop(()) { _ =>
            Async.race(
                untilState(!conn.isOpen).andThen(true),
                tc.awaitPendingSleepers(1).andThen(false)
            ).map { reclaimed =>
                if reclaimed then Loop.done(())
                else tc.advance(grace).andThen(Loop.continue(()))
            }
        }

    "an abandoned backpressured connection is reclaimed after the peer FIN once the grace elapses" - eachBackendOnClock { (transport, tc) =>
        val grace = 200.millis
        // Cap-1 channel + 64-byte read chunk so 128 bytes become two reads, the second overflowing the channel and parking the accepted-side
        // ReadPump.
        val config    = NetConfig(channelCapacity = 1, readChunkSize = 64.bytes, peerCloseGrace = grace.grace)
        val acceptedP = new IOPromise[Closed, Connection]
        for
            // Capture the accepted (server) connection; the handler abandons it (never drains inbound, never closes), so its ReadPump fills the cap-1
            // channel and parks on the put. The captured connection's isOpen is the portable reclaim oracle (the fork's fd-leak check is Linux-only).
            listener <- transport.listen("127.0.0.1", 0, 128, config) { conn =>
                acceptedP.completeDiscard(Result.succeed(conn))
            }.safe.get
            _        <- Scope.ensure(Sync.defer(listener.close()))
            client   <- transport.connect("127.0.0.1", listener.port, config = config).safe.get
            accepted <- acceptedP.asInstanceOf[Fiber.Unsafe[Connection, Abort[Closed]]].safe.get
            _        <- Abort.run[Closed](client.outbound.safe.put(Span.fromUnsafe(Array.fill[Byte](128)(1))))
            // An overflowing pump parks by registering a put, so a pending put IS the parked state. Awaiting it guarantees the FIN below lands on a
            // parked pump: a FIN arriving earlier would be observed by an armed read and reclaimed via the EOF path, leaving the grace reclaim untested.
            _ <- untilState(accepted.inbound.pendingPuts().getOrElse(0) > 0)
            // Nothing but the grace reclaims the parked connection, so a missing reclaim hangs the leaf here.
            _ <- Sync.defer(client.close()) // FIN with the accepted-side pump parked
            _ <- expireUntilReclaimed(tc, accepted, grace)
        yield assert(!accepted.isOpen)
        end for
    }

end TransportBackpressureReclaimTest
