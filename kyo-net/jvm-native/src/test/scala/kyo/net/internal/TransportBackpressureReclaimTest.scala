package kyo.net.internal

import kyo.*
import kyo.net.*
import kyo.scheduler.IOPromise

/** End-to-end regression guard for the peer-close grace reclaim on every JVM and Native transport. A never-draining server handler and a cap-1
  * inbound channel park the accepted-side ReadPump with no armed read (the first chunk fills the channel, the second overflows); the client then
  * closes, and each backend's `isPeerClosed` observes the FIN so a grace expiry reclaims the accepted connection. The grace runs on the
  * transport's controlled clock, so each expiry happens exactly when the leaf advances it.
  */
class TransportBackpressureReclaimTest extends kyo.net.Test:

    import AllowUnsafe.embrace.danger

    /** Polls a condition that settles on the transport's own carriers; the leaf timeout turns one that never holds into a failure. */
    private def awaitState(cond: => Boolean)(using Frame): Unit < Async =
        Loop(()) { _ =>
            if cond then Loop.done(())
            else Async.sleep(1.millis).andThen(Loop.continue(()))
        }

    "an abandoned backpressured connection is reclaimed after the peer FIN once a grace window passes" - eachBackendOnClock {
        (transport, control) =>
            val grace             = 3.seconds
            val nonTerminationCap = (timeout.toMillis / 100 / 2).toInt
            // Cap-1 channel + 64-byte read chunk so 128 bytes become two reads, the second overflowing the channel and parking the accepted-side
            // ReadPump.
            val config    = NetConfig(channelCapacity = 1, readChunkSize = 64.bytes, peerCloseGrace = grace.grace)
            val acceptedP = new IOPromise[Closed, Connection]
            for
                // The handler abandons the accepted connection (never drains inbound, never closes), so its ReadPump fills the cap-1 channel and
                // parks on the put.
                listener <- transport.listen("127.0.0.1", 0, 128, config) { conn =>
                    acceptedP.completeDiscard(Result.succeed(conn))
                }.safe.get
                _        <- Scope.ensure(Sync.defer(listener.close()))
                client   <- transport.connect("127.0.0.1", listener.port, config = config).safe.get
                accepted <- acceptedP.asInstanceOf[Fiber.Unsafe[Connection, Abort[Closed]]].safe.get
                _        <- Scope.ensure(Sync.defer(accepted.close()))
                _        <- Abort.run[Closed](client.outbound.safe.put(Span.fromUnsafe(Array.fill[Byte](128)(1))))
                // The pump arms its grace on the transport's clock exactly when it parks, so the pending sleeper IS the parked state. A FIN
                // arriving while a read is still armed takes the ordinary EOF path, leaving the grace reclaim untested.
                _ <- awaitTimer(control, "peer-close grace", grace)
                _ <- awaitState(accepted.inbound.pendingPuts().getOrElse(0) > 0)
                openBeforeExpiry = accepted.isOpen
                _ <- Sync.defer(client.close())
                // An expiry that runs before the FIN reaches the accepted socket finds the peer live and re-arms, so the clock is advanced one
                // window at a time until an expiry observes the FIN. How many windows that takes is kernel timing, so the cap only detects
                // a reclaim that never comes, and is half the leaf budget at the about 100 ms of real time each advance yields, so the
                // failure carries the count.
                windows <- Loop(0) { windows =>
                    if !accepted.isOpen || windows >= nonTerminationCap then Loop.done(windows)
                    else
                        armedThenAdvance(control, s"peer-close grace (window ${windows + 1})", grace)
                            .andThen(Loop.continue(windows + 1))
                }
            yield
                assert(openBeforeExpiry, "the parked connection must stay open until a grace window passes")
                assert(!accepted.isOpen, s"the connection must be reclaimed after the FIN; still open after $windows windows")
            end for
    }

end TransportBackpressureReclaimTest
