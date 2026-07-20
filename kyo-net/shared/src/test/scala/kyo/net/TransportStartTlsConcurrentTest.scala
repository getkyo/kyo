package kyo.net

import kyo.*

/** Reliable reproduction and regression guard for STARTTLS upgrades under concurrency: many clients upgrade a plaintext connection to TLS on the
  * SAME transport at once (the Postgres SSLRequest flow: a 1-byte signal, the server's ready reply, then a mutual TLS handshake over the same
  * socket, then an encrypted echo). Every concurrent upgrade must complete and round-trip its message; a handshake that corrupts its byte stream
  * (a stray plaintext signal byte fed into the engine, a misrouted read between handles), aborts, or hangs under load corrupts or fails an echo.
  *
  * One transport drives every upgrade, so this exercises the production shape (a single STARTTLS server under concurrent load), not the per-leaf
  * transport churn the rest of the suite uses. Runs over the full backend x TLS-impl matrix via [[eachBackendTls]]; each client's outcome is
  * captured so a partial failure reports the failed count rather than aborting the whole batch on the first.
  */
class TransportStartTlsConcurrentTest extends Test:

    import AllowUnsafe.embrace.danger

    private val upgradeRequest: Span[Byte] = Span.from(Array[Byte]('U'))
    private val upgradeReady: Span[Byte]   = Span.from(Array[Byte]('R'))
    private val concurrency                = 128

    /** A server that, per accepted connection, waits for the 1-byte signal, replies ready, upgrades to TLS in `serverTls`, then echoes. */
    private def startTlsEchoServer(transport: Transport, serverTls: NetTlsConfig)(using Frame): Listener < (Async & Abort[NetException]) =
        transport.listen("127.0.0.1", 0, 256) { serverConn =>
            discard(Sync.Unsafe.evalOrThrow {
                Fiber.initUnscoped {
                    Abort.run[Closed] {
                        serverConn.inbound.safe.take.flatMap { _ =>
                            serverConn.outbound.safe.put(upgradeReady).andThen {
                                transport.upgradeToTls(serverConn, serverTls, 16).safe.get.flatMap { tlsConn =>
                                    Loop.foreach {
                                        tlsConn.inbound.safe.take.flatMap(data =>
                                            tlsConn.outbound.safe.put(data).andThen(Loop.continue)
                                        )
                                    }
                                }
                            }
                        }
                    }.unit
                }
            })
        }.safe.get

    /** Collect inbound bytes until at least `target` bytes have arrived (a TLS record may split the echo across chunks under load). */
    private def collectToLen(conn: Connection, target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= target then Loop.done(acc)
            else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
        }

    /** One client: connect plaintext, signal, await ready, upgrade to TLS, send `msg`, read the echo back, return whether it round-tripped.
      *
      * Both `conn` and `tlsConn` are Scope.ensure-guarded right after acquisition: `conn` may fail the plaintext handshake exchange or the
      * upgrade before ever becoming `tlsConn` (closing it is still safe and idempotent even once it has detached into the upgrade), and
      * `tlsConn` may fail the post-upgrade echo before reaching its own trailing close() in the yield. Without these guards a per-client
      * failure under load leaks that client's connection for the life of the process-shared transport.
      */
    private def runClient(transport: Transport, port: Int, clientTls: NetTlsConfig, msg: Array[Byte])(using
        Frame
    ): Boolean < (Async & Abort[NetException | Closed] & Scope) =
        for
            conn     <- transport.connect("127.0.0.1", port).safe.get
            _        <- Scope.ensure(Sync.defer(conn.close()))
            _        <- conn.outbound.safe.put(upgradeRequest)
            _        <- conn.inbound.safe.take
            tlsConn  <- transport.upgradeToTls(conn, clientTls, 16).safe.get
            _        <- Scope.ensure(Sync.defer(tlsConn.close()))
            _        <- tlsConn.outbound.safe.put(Span.fromUnsafe(msg))
            received <- collectToLen(tlsConn, msg.length)
        yield
            tlsConn.close()
            java.util.Arrays.equals(received.take(msg.length), msg)

    "many concurrent STARTTLS upgrades on one transport all succeed and round-trip" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            val cli = clientTls.copy(sniHostname = Present("localhost"))
            startTlsEchoServer(transport, serverTls).map { listener =>
                // Guards the listener if fillIndexed itself were to fail outside the per-client Abort.run below.
                Scope.ensure(Sync.defer(listener.close())).andThen {
                    Async.fillIndexed(concurrency, concurrency) { i =>
                        // A 24KB per-client payload that spans multiple TLS records (max record ~16KB): a multi-record echo needs the ReadPump to re-arm
                        // after each record, so this guards BOTH the concurrent-upgrade hang AND the multi-record read-rearm path in one test. Each client's
                        // bytes are distinct (header + index-derived fill) so the round-trip equality check confirms no cross-connection misrouting.
                        val header = s"starttls-concurrent-$i-".getBytes("UTF-8")
                        val msg    = header ++ Array.fill[Byte](24576)((i % 251 + 1).toByte)
                        Abort.run[NetException | Closed](runClient(transport, listener.port, cli, msg)).map {
                            case Result.Success(ok) => ok
                            case _                  => false
                        }
                    }.map { results =>
                        listener.close()
                        val failed = results.count(ok => !ok)
                        assert(failed == 0, s"$failed of $concurrency concurrent STARTTLS upgrades failed to upgrade and round-trip")
                    }
                }
            }
    }

end TransportStartTlsConcurrentTest
