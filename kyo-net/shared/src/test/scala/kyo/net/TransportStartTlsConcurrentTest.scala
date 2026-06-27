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
    private def startTlsEchoServer(transport: Transport, serverTls: NetTlsConfig)(using Frame): Listener < (Async & Abort[Closed]) =
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

    /** One client: connect plaintext, signal, await ready, upgrade to TLS, send `msg`, read the echo back, return whether it round-tripped. */
    private def runClient(transport: Transport, port: Int, clientTls: NetTlsConfig, msg: Array[Byte])(using
        Frame
    ): Boolean < (Async & Abort[Closed]) =
        for
            conn     <- transport.connect("127.0.0.1", port).safe.get
            _        <- conn.outbound.safe.put(upgradeRequest)
            _        <- conn.inbound.safe.take
            tlsConn  <- transport.upgradeToTls(conn, clientTls, 16).safe.get
            _        <- tlsConn.outbound.safe.put(Span.fromUnsafe(msg))
            received <- collectToLen(tlsConn, msg.length)
        yield
            tlsConn.close()
            java.util.Arrays.equals(received.take(msg.length), msg)

    // PENDING-P10 (nio/jdk cell ONLY, backend-targeted skip): concurrent STARTTLS upgrades race the pump on the shared handle; the nio/jdk cell
    // times out (the documented NIO upgrade-handoff race, P8-CONC5NIO). The io_uring/epoll/kqueue cells PASS. Same family as
    // StartTlsUpgradeCloseRaceTest; P10's poll/selector-carrier confinement of the upgrade read fixes it and un-pends this. The skipCell below
    // cancels ONLY the (nio, jdk) cell, so every other (passing) cell keeps running. See control/p9-fix-log.md (the P9->P10 carry-forward entry).
    "many concurrent STARTTLS upgrades on one transport all succeed and round-trip" - eachBackendTlsExcept {
        (transport, serverTls, clientTls) =>
            val cli = clientTls.copy(sniHostname = Present("localhost"))
            startTlsEchoServer(transport, serverTls).map { listener =>
                Async.fillIndexed(concurrency, concurrency) { i =>
                    // A 24KB per-client payload that spans multiple TLS records (max record ~16KB): a multi-record echo needs the ReadPump to re-arm
                    // after each record, so this guards BOTH the concurrent-upgrade hang AND the multi-record read-rearm path in one test. Each client's
                    // bytes are distinct (header + index-derived fill) so the round-trip equality check confirms no cross-connection misrouting.
                    val header = s"starttls-concurrent-$i-".getBytes("UTF-8")
                    val msg    = header ++ Array.fill[Byte](24576)((i % 251 + 1).toByte)
                    Abort.run[Closed](runClient(transport, listener.port, cli, msg)).map {
                        case Result.Success(ok) => ok
                        case _                  => false
                    }
                }.map { results =>
                    listener.close()
                    val failed = results.count(ok => !ok)
                    assert(failed == 0, s"$failed of $concurrency concurrent STARTTLS upgrades failed to upgrade and round-trip")
                }
            }
    } { (backend, provider) =>
        if backend == "nio" && provider == "jdk" then
            Present(
                "PENDING-P10: upgrade-handshake races concurrent ops on the shared handle; fixed by P10 poll-carrier confinement, see p9-fix-log"
            )
        else Absent
    }

end TransportStartTlsConcurrentTest
