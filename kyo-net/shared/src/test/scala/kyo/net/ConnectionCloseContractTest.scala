package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection as InternalConnection
import kyo.net.internal.transport.WriteState

/** The connection close contract, clauses C1 to C6 and X1: what `close()` flushes, how long it may take, when the handle is released and how
  * a loss is reported. Each leaf is named after its clause and runs once per backend cell, plain and TLS, on a transport of the cell's own
  * whose timers run on a controlled clock; the peer is a [[RawPeer]], so what a leaf asserts is what reached the wire.
  */
class ConnectionCloseContractTest extends Test:

    import AllowUnsafe.embrace.danger

    private def parkedOnWritable(conn: InternalConnection[?]): Boolean =
        conn.writeState match
            case WriteState.AwaitingWritable(_, _) | WriteState.Backpressured(_, _) => true
            case _                                                                  => false

    /** Offers spans until the outbound channel is full and the write pump is parked on a socket the peer no longer drains. */
    private def fillUntilStalled(conn: InternalConnection[?])(using Frame): Boolean < Async =
        val span = Span.fromUnsafe(Array.fill[Byte](64 * 1024)(7))
        Loop(0) { offered =>
            if offered > 16 * 1024 then Loop.done(false)
            else
                conn.outbound.offer(span) match
                    case Result.Success(true)                            => Loop.continue(offered + 1)
                    case Result.Success(false) if parkedOnWritable(conn) => Loop.done(true)
                    case Result.Success(false)                           => Async.sleep(1.millis).andThen(Loop.continue(offered))
                    case _                                               => Loop.done(false)
        }
    end fillUntilStalled

    "C1" - {

        "every byte written before close() reaches the peer" - eachEndpoint(cell =>
            if cell == "in-memory" then Present("S9: the in-memory close discards the bytes its peer has not taken") else Absent
        ) { (conn, peer) =>
            val payload = Span.fromUnsafe(Array.tabulate[Byte](256 * 1024)(i => (i % 251).toByte))
            for
                _        <- conn.outbound.safe.put(payload)
                _        <- Sync.defer(conn.close())
                received <- peer.recvExactly(payload.size)
            yield assert(
                received.sameElements(payload.toArray),
                s"received ${received.length} of ${payload.size} bytes written before close()"
            )
            end for
        }
    }

    "C2" - {

        "the close-flush grace runs on the transport's clock" - eachBackendOnClock { (transport, control) =>
            val grace             = 7.seconds
            val nonTerminationCap = (timeout.toMillis / 100 / 2).toInt
            val accepted          = Promise.Unsafe.init[Connection, Any]()
            // A parked pump does not prove the send buffer is full: io_uring parks on its own unsent-tail bound while a send is in flight and
            // the kernel can still take bytes. A loopback send buffer grows to megabytes, so a default-depth queue (256 KiB) can flush whole
            // after the close and release with no window. A 32 MiB queue is beyond the largest send buffer Linux and macOS grow by default
            // (4 MiB).
            val config = NetConfig(channelCapacity = 512, closeFlushGrace = grace.grace)
            for
                listener <- transport.listen("127.0.0.1", 0, 16, config) { conn =>
                    accepted.completeDiscard(Result.succeed(conn))
                }.safe.get
                _       <- Scope.ensure(Sync.defer(listener.close()))
                _       <- RawPeer.connect(listener.port, rcvBuf = Present(4096))
                server  <- accepted.safe.get.map(_.asInstanceOf[InternalConnection[?]])
                stalled <- fillUntilStalled(server)
                _ = assert(stalled, "the write pump never parked against the peer that stopped reading")
                _ <- Sync.defer(server.close())
                // Kernel timing decides how many windows still see bytes leave, so the leaf asserts that every window is timed on the transport's
                // clock and that the release comes; the cap turns a release that never comes into a failure carrying the count.
                windows <- Loop(0) { windows =>
                    if server.isReleased || windows >= nonTerminationCap then Loop.done(windows)
                    else
                        armedThenAdvance(control, s"close-flush grace (window ${windows + 1})", grace)
                            .andThen(Loop.continue(windows + 1))
                }
            yield
                assert(windows >= 1, "the close released a pump parked on unsent bytes before any grace window")
                assert(
                    server.isReleased,
                    s"a close whose peer stopped reading must be released by the grace; still held after $windows windows"
                )
            end for
        }
    }

end ConnectionCloseContractTest
