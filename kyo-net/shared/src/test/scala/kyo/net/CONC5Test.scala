package kyo.net

import kyo.*

/** CONC-5: STARTTLS upgrade completes correctly under concurrent write pressure.
  *
  * Drives N clients through simultaneous STARTTLS upgrades on a single transport, all gated by a Latch so they start concurrently.
  * Immediately after the upgrade, each client sends a payload, exercising the cross-tail send wire-order mechanism: the handshake's final
  * raw flight may still be in flight when the first post-upgrade TLS write is submitted, so the TLS write must be deferred until the raw
  * tail drains (rawSendInFlight clear) and then kicked by onRawSendComplete. On io_uring, the concurrent load from N simultaneous
  * handshakes saturates the submission queue and exercises the SQ-full backpressure path alongside the cross-tail defer/kick. On NIO, the
  * concurrent upgrades exercise the keep-key selector-registration path (no CancelledKeyException window). All N upgrades must complete
  * and the echo must round-trip.
  *
  * All leaves run via eachBackendTls. The scenario is distinct from TransportStartTlsConcurrentTest: the Latch maximizes upgrade overlap,
  * the payload is sent immediately post-upgrade (not after a round-trip), and the assertion targets the cross-tail invariant specifically.
  */
class CONC5Test extends Test:

    import AllowUnsafe.embrace.danger

    private val concurrency: Int = 32

    private val upgradeSignal: Span[Byte] = Span.from(Array[Byte]('U'))
    private val upgradeReady: Span[Byte]  = Span.from(Array[Byte]('R'))

    private def collectN(conn: Connection, target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= target then Loop.done(acc)
            else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
        }

    "CONC-5: concurrent STARTTLS upgrades with immediate post-upgrade writes complete without wire-order corruption" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            val cli = clientTls.copy(sniHostname = Present("localhost"))
            for
                listener <- transport.listen("127.0.0.1", 0, concurrency * 2) { serverConn =>
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
                // Latch(1): one release unblocks all N waiters simultaneously.
                latch <- Latch.init(1)
                fibers <- Kyo.foreach(Chunk.from(1 to concurrency)) { i =>
                    val attempt: Boolean < (Async & Abort[NetException | Closed]) =
                        for
                            conn    <- transport.connect("127.0.0.1", listener.port).safe.get
                            _       <- conn.outbound.safe.put(upgradeSignal)
                            _       <- conn.inbound.safe.take
                            tlsConn <- transport.upgradeToTls(conn, cli, 16).safe.get
                            // Post-upgrade write immediately: exercises the cross-tail defer/kick when the
                            // handshake's raw final flight may not yet have reaped its CQE.
                            payload = Array.fill[Byte](1024)((i % 127 + 1).toByte)
                            _      <- tlsConn.outbound.safe.put(Span.fromUnsafe(payload))
                            echoed <- collectN(tlsConn, payload.length)
                        yield
                            tlsConn.close()
                            java.util.Arrays.equals(echoed.take(payload.length), payload)
                        end for
                    end attempt
                    val body: Result[NetException | Timeout | Closed, Boolean] < Async =
                        // All N fibers park here until latch.release fires, maximizing upgrade overlap.
                        latch.await.flatMap { _ =>
                            Abort.run[NetException | Timeout | Closed](Async.timeout(30.seconds)(attempt))
                        }
                    Fiber.init(body)
                }
                _       <- latch.release
                results <- Kyo.foreach(fibers)(_.get)
            yield
                listener.close()
                val failures = results.count {
                    case Result.Success(ok) => !ok
                    case Result.Failure(_)  => true
                }
                assert(
                    failures == 0,
                    s"all $concurrency concurrent STARTTLS upgrades must succeed and echo correctly under write pressure, " +
                        s"but $failures of $concurrency failed"
                )
                succeed
            end for
    }

end CONC5Test
