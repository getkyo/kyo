package kyo.net

import kyo.*

/** Cross-backend concurrent reader+writer stress for the public [[Transport]]/[[Connection]] surface, over `NetPlatform.transport` (posix on
  * JVM/Native, Node on JS).
  *
  * Many connections run at once; each has a writer fiber streaming request frames and a reader fiber reading each response frame and checking
  * it byte for byte against the matching request, with a permit window bounding the writer ahead of the reader so read and write overlap with
  * bounded outstanding bytes. A correctness bug in the per-connection read/write multiplexing (a misrouted read between handles, a lost
  * re-arm, a dropped/duplicated/reordered byte, a deadlock) would corrupt an echo or hang. Determinism: every step gates on a channel/promise
  * completion, never a sleep; there is no real network (in-process loopback). Plaintext only: the TLS concurrent-echo guard stays per-backend
  * (posix `PosixTransportTlsConcurrentEchoTest`, js `JsConcurrentEchoTest`) because the Native TLS-engine-under-contention path is not yet
  * covered and would make a shared TLS variant flaky on Native.
  */
class TransportConcurrentEchoTest extends Test:

    import AllowUnsafe.embrace.danger

    private val connections = 12
    private val rounds      = 20
    private val window      = 4
    private val sizes       = Array(1, 7, 64, 200, 1500)

    private def requestBytes(connId: Int, round: Int): Array[Byte] =
        val len = sizes(round % sizes.length)
        Array.tabulate[Byte](len)(i => ((connId * 31 + round * 7 + i) & 0xff).toByte)

    private def fillTo(conn: Connection, buf: Array[Byte], target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(buf) { acc =>
            if acc.length >= target then Loop.done(acc)
            else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
        }

    private def driveConnection(conn: Connection, connId: Int, permits: Channel[Unit])(using Frame): Boolean < (Async & Abort[Closed]) =
        val write = Loop.indexed[Unit, Async & Abort[Closed]] { round =>
            if round >= rounds then Loop.done(())
            else permits.take.andThen(conn.outbound.safe.put(Span.fromUnsafe(requestBytes(connId, round)))).andThen(Loop.continue)
        }
        val read = Loop.indexed[Array[Byte], Boolean, Async & Abort[Closed]](Array.emptyByteArray) { (round, leftover) =>
            if round >= rounds then Loop.done(true)
            else
                val expected = requestBytes(connId, round)
                fillTo(conn, leftover, expected.length).map { acc =>
                    val frame  = acc.take(expected.length)
                    val remain = acc.drop(expected.length)
                    if !java.util.Arrays.equals(frame, expected) then permits.put(()).andThen(Loop.done(false))
                    else permits.put(()).andThen(Loop.continue(remain))
                }
        }
        Async.zip(write, read).map((_, ok) => ok)
    end driveConnection

    "many plaintext connections each echo concurrently and every response matches its request, on every backend" in {
        val transport = NetPlatform.transport
        val serverHandler: Connection => Unit = serverConn =>
            discard(Sync.Unsafe.evalOrThrow {
                Fiber.initUnscoped {
                    Abort.run[Closed] {
                        Loop.foreach {
                            serverConn.inbound.safe.take.map(chunk => serverConn.outbound.safe.put(chunk).andThen(Loop.continue))
                        }
                    }.unit
                }
            })
        for
            listener <- transport.listen("127.0.0.1", 0, 128)(serverHandler).safe.get
            port = listener.port
            results <- Async.fillIndexed(connections, connections) { connId =>
                transport.connect("127.0.0.1", port).safe.get.map { conn =>
                    Channel.init[Unit](window).map { permits =>
                        Kyo.foreach(0 until window)(_ => permits.put(())).andThen {
                            driveConnection(conn, connId, permits).map { ok =>
                                conn.close()
                                ok
                            }
                        }
                    }
                }
            }
        yield
            listener.close()
            assert(results.forall(identity), "a plaintext connection's echo did not match byte for byte under concurrency")
        end for
    }

end TransportConcurrentEchoTest
