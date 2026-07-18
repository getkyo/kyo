package kyo.net

import kyo.*

/** Probe: the same high-concurrency shape as TransportStartTlsConcurrentTest but PLAINTEXT (no TLS, no STARTTLS upgrade) -- many clients connect
  * to one echo server on the SAME transport at once and round-trip a message. Answers whether the concurrency failure is TLS/upgrade-specific (this
  * passes) or a fundamental connection-lifecycle bug (this fails too). Runs over every registered backend via [[eachBackend]].
  */
class TransportPlaintextConcurrentTest extends Test:

    import AllowUnsafe.embrace.danger

    private val concurrency = 128

    private def echoServer(transport: Transport)(using Frame): Listener < (Async & Abort[NetException]) =
        transport.listen("127.0.0.1", 0, 256) { serverConn =>
            discard(Sync.Unsafe.evalOrThrow {
                Fiber.initUnscoped {
                    Abort.run[Closed] {
                        Loop.foreach {
                            serverConn.inbound.safe.take.flatMap(d => serverConn.outbound.safe.put(d).andThen(Loop.continue))
                        }
                    }.unit
                }
            })
        }.safe.get

    private def collectToLen(conn: Connection, target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= target then Loop.done(acc)
            else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
        }

    "many concurrent plaintext connections on one transport all echo" - eachBackend { transport =>
        echoServer(transport).map { listener =>
            Async.fillIndexed(concurrency, concurrency) { i =>
                val msg = s"plaintext-concurrent-$i".getBytes("UTF-8")
                Abort.run[NetException | Closed](
                    transport.connect("127.0.0.1", listener.port).safe.get.flatMap { conn =>
                        conn.outbound.safe.put(Span.fromUnsafe(msg)).andThen {
                            collectToLen(conn, msg.length).map { echoed =>
                                conn.close()
                                java.util.Arrays.equals(echoed.take(msg.length), msg)
                            }
                        }
                    }
                ).map {
                    case Result.Success(ok) => ok
                    case _                  => false
                }
            }.map { results =>
                listener.close()
                val failed = results.count(ok => !ok)
                assert(failed == 0, s"$failed of $concurrency concurrent plaintext echoes failed")
            }
        }
    }

end TransportPlaintextConcurrentTest
