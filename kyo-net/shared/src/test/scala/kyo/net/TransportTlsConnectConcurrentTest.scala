package kyo.net

import kyo.*

/** Probe: high-concurrency CONNECT-TIME TLS (transport.connect(tls) to a TLS-terminating listener) -- the handshake runs on a FRESH connection
  * at connect/accept, with NO STARTTLS plaintext-detach/handle-reuse. Compared to TransportStartTlsConcurrentTest (same shape, but STARTTLS
  * upgrade): if this passes and the STARTTLS one fails, the bug is in the detach/handle-reuse; if this fails too, it is in the handshake drive
  * itself. Over the full backend x TLS-impl matrix via [[eachBackendTls]].
  */
class TransportTlsConnectConcurrentTest extends Test:

    import AllowUnsafe.embrace.danger

    private val concurrency = 128

    private def collectToLen(conn: Connection, target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= target then Loop.done(acc)
            else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
        }

    "many concurrent connect-time TLS connections on one transport all echo" - eachBackendTls { (transport, serverTls, clientTls) =>
        val cli = clientTls.copy(sniHostname = Present("localhost"))
        transport.listenTls("127.0.0.1", 0, 256, serverTls) { serverConn =>
            discard(Sync.Unsafe.evalOrThrow {
                Fiber.initUnscoped {
                    Abort.run[Closed] {
                        Loop.foreach {
                            serverConn.inbound.safe.take.flatMap(d => serverConn.outbound.safe.put(d).andThen(Loop.continue))
                        }
                    }.unit
                }
            })
        }.safe.get.map { listener =>
            Scope.ensure(Sync.defer(listener.close())).andThen {
                Async.fillIndexed(concurrency, concurrency) { i =>
                    val msg = s"tls-connect-concurrent-$i".getBytes("UTF-8")
                    Abort.run[Closed](
                        // Scope.run: closes conn as soon as THIS task finishes (success or failure), rather than only on the
                        // success continuation as the bare trailing conn.close() below did, which skipped the close whenever
                        // the put/collect step failed. Deferring 128-way to the leaf's own Scope would also hold every
                        // concurrent connection open simultaneously until the whole leaf ends, so each task gets its own.
                        Scope.run(
                            transport.connectTls("127.0.0.1", listener.port, cli).safe.get.flatMap { conn =>
                                Scope.ensure(Sync.defer(conn.close())).andThen {
                                    conn.outbound.safe.put(Span.fromUnsafe(msg)).andThen {
                                        collectToLen(conn, msg.length).map { echoed =>
                                            java.util.Arrays.equals(echoed.take(msg.length), msg)
                                        }
                                    }
                                }
                            }
                        )
                    ).map {
                        case Result.Success(ok) => ok
                        case _                  => false
                    }
                }.map { results =>
                    listener.close()
                    val failed = results.count(ok => !ok)
                    assert(failed == 0, s"$failed of $concurrency concurrent connect-time TLS echoes failed")
                }
            }
        }
    }

end TransportTlsConnectConcurrentTest
