package kyo.net

import kyo.*

/** Cross-backend backpressure guarantee for the public [[Transport]] surface.
  *
  * Runs against `NetPlatform.transport`, so the SAME test exercises every backend: NIO on JVM, posix (epoll/kqueue) on Native, and the Node
  * transport on JS. A payload many times larger than the channel buffers and the per-read chunk is streamed through a loopback echo server while
  * a consumer drains it concurrently. The buffers (inbound/outbound channels, kernel socket buffers) fill and the pumps park and resume
  * repeatedly; the behavioral guarantee under that backpressure is that every byte round-trips exactly once, in order, with none lost or
  * duplicated. The `i % 251` byte pattern makes any loss, reordering, or duplication observable. Send and receive run concurrently (Async.zip)
  * so the bounded buffers cannot deadlock.
  *
  * The posix `ReadPumpTest` / `ConnectionInitTest` cover the same guarantee at the driver-wiring level for the posix backend specifically; this
  * is the backend-agnostic statement of the contract through the public API.
  */
class TransportBackpressureTest extends Test:

    import AllowUnsafe.embrace.danger

    /** A loopback listener that echoes every inbound chunk back to its sender, looping until the connection closes. */
    private def echoListener(transport: Transport)(using Frame): Listener < (Async & Abort[Closed]) =
        transport.listen("127.0.0.1", 0, 128) { serverConn =>
            discard(Sync.Unsafe.evalOrThrow {
                Fiber.initUnscoped {
                    Abort.run[Closed] {
                        Loop.foreach {
                            serverConn.inbound.safe.take.map { chunk =>
                                serverConn.outbound.safe.put(chunk).andThen(Loop.continue)
                            }
                        }
                    }.unit
                }
            })
        }.safe.get

    /** Write `payload` to the connection in pieces; each `put` suspends (backpressure) when the outbound channel is full. */
    private def sendAll(conn: Connection, payload: Array[Byte])(using Frame): Unit < (Async & Abort[Closed]) =
        Loop(0) { off =>
            if off >= payload.length then Loop.done(())
            else
                val len = math.min(8192, payload.length - off)
                conn.outbound.safe.put(Span.from(payload.slice(off, off + len))).andThen(Loop.continue(off + len))
        }

    /** Drain `n` bytes from the connection's inbound channel, concatenated in delivery order. */
    private def drainAll(conn: Connection, n: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= n then Loop.done(acc)
            else conn.inbound.safe.take.map(span => Loop.continue(acc ++ span.toArray))
        }

    "Transport backpressure (every backend via NetPlatform.transport)" - {
        "a large payload round-trips through bounded channels with no loss and order preserved" in {
            val transport = NetPlatform.transport
            val payload   = Array.tabulate[Byte](256 * 1024)(i => (i % 251).toByte)
            for
                listener <- echoListener(transport)
                conn     <- transport.connect("127.0.0.1", listener.port).safe.get
                got      <- Async.zip(sendAll(conn, payload), drainAll(conn, payload.length)).map(_._2)
            yield
                conn.close()
                listener.close()
                assert(got.length == payload.length, s"expected ${payload.length} bytes round-tripped, got ${got.length}")
                assert(got.sameElements(payload), "backpressure must preserve every byte in order with none lost or duplicated")
            end for
        }
    }

end TransportBackpressureTest
