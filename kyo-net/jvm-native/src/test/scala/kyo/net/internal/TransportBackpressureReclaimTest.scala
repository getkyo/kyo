package kyo.net.internal

import kyo.*
import kyo.net.*
import kyo.scheduler.IOPromise

/** End-to-end regression guard for the peer-close grace reclaim on the real posix transport (epoll / kqueue / io_uring). A never-draining server
  * handler and a cap-1 inbound channel park the accepted-side ReadPump with no armed read (the first chunk fills the channel, the second overflows);
  * the client then closes, and each backend's `isPeerClosed` observes the FIN so the grace timer reclaims the accepted connection. The in-leaf oracle
  * is the captured accepted connection's `isOpen` (portable); on Linux the fork's `/proc/self/fd` leak check is a second oracle for the reclaimed fd.
  */
class TransportBackpressureReclaimTest extends kyo.net.Test:

    import AllowUnsafe.embrace.danger

    // Poll a condition that settles on the transport's own carriers (the pump parking on the full channel, the grace reclaim closing the
    // connection), out of the test's reach, so there is no latch to await. The bound is a ceiling; every caller asserts on the settled state.
    private def awaitCondition(bound: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            if cond then Loop.done(true)
            else if java.lang.System.nanoTime() >= deadline then Loop.done(false)
            else Async.sleep(5.millis).andThen(Loop.continue(()))
        }
    end awaitCondition

    "an abandoned backpressured connection is reclaimed after the peer FIN within the grace window" in {
        val transport = NetPlatform.transport
        // Cap-1 channel + 64-byte read chunk so 128 bytes become two reads, the second overflowing the channel and parking the accepted-side
        // ReadPump. Short grace so the reclaim lands well within the fork's leak-check drain budget.
        val config    = NetConfig(channelCapacity = 1, readChunkSize = 64, peerCloseGrace = 200.millis)
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
            parked <- awaitCondition(5.seconds)(accepted.inbound.pendingPuts().getOrElse(0) > 0)
            _ = assert(
                parked,
                "the accepted-side ReadPump never parked on the full inbound channel, so the FIN below would not exercise the grace reclaim"
            )
            _         <- Sync.defer(client.close()) // FIN with the accepted-side pump parked
            reclaimed <- awaitCondition(5.seconds)(!accepted.isOpen)
        yield assert(
            reclaimed,
            "the abandoned backpressured accepted connection must be reclaimed (closed) after the client FIN via the peer-close grace"
        )
        end for
    }

end TransportBackpressureReclaimTest
