package kyo.net

import kyo.*

/** Cross-backend connection-lifecycle guarantees for the public [[Transport]] surface, asserted once for every backend through
  * `NetPlatform.transport` (NIO on JVM, posix on Native, Node on JS):
  *
  *   - connecting to a port with no listener fails `Closed` (connection refused), rather than hanging or panicking;
  *   - closing one end surfaces `Closed` on the peer connection's next inbound read (peer-close / EOF propagation);
  *   - a connection whose peer has closed reports `isOpen=false`, the health check a connection pool uses to discard a dead keep-alive
  *     connection instead of reusing it (read-after-close guard, Go #22158 / #59310 class).
  *
  * The first two were previously asserted only on the posix backend (PosixTransportSurfaceTest); the contract is backend-agnostic, so it
  * belongs in the shared suite where each platform's real transport runs it.
  */
class TransportLifecycleTest extends Test:

    import AllowUnsafe.embrace.danger

    "Transport lifecycle (every backend via NetPlatform.transport)" - {

        "connecting to a port with no listener fails Closed" in {
            val transport = NetPlatform.transport
            for
                // Bind an ephemeral port through the transport, capture it, then close the listener so nothing is listening on that port.
                listener <- transport.listen("127.0.0.1", 0, 128)(_ => ()).safe.get
                port = listener.port
                _    = listener.close()
                outcome <- Abort.run[Closed](transport.connect("127.0.0.1", port).safe.get)
            yield assert(outcome.isFailure, s"expected Closed connecting to a port with no listener (port $port), got $outcome")
            end for
        }

        "closing one end surfaces Closed on the peer connection" in {
            val transport = NetPlatform.transport
            for
                serverRef <- AtomicRef.init[Maybe[Connection]](Absent)
                accepted  <- Channel.init[Unit](1)
                listener <- transport.listen("127.0.0.1", 0, 128) { serverConn =>
                    discard(Sync.Unsafe.evalOrThrow {
                        Fiber.initUnscoped {
                            Abort.run[Closed] {
                                Sync.Unsafe.defer(serverRef.set(Present(serverConn))).andThen(accepted.put(()))
                            }.unit
                        }
                    })
                }.safe.get
                conn <- transport.connect("127.0.0.1", listener.port).safe.get
                _    <- accepted.take
                server <- serverRef.get.map {
                    case Present(c) => c
                    case Absent     => fail("server connection was never captured")
                }
                // Close the client end; the server's pending inbound read must surface Closed once the peer-close EOF tears it down.
                _       <- Sync.defer(conn.close())
                outcome <- Abort.run[Closed](server.inbound.safe.take)
            yield
                server.close()
                listener.close()
                assert(outcome.isFailure, s"expected Closed on the peer connection after the other end closed, got $outcome")
            end for
        }

        // Read-after-close guard for keep-alive connection reuse (Go #22158 / #59310 class): a pooled connection whose peer (the server)
        // closed it while idle must report isOpen=false, so the connection pool's `isAlive` health check (wired to `transport.isOpen`)
        // discards it instead of handing it back for the next request. kyo-net keeps a standing read on every live connection, so a peer FIN
        // arrives as EOF, tears the connection down, and flips closedFlag; isOpen then reads false. This holds on every backend.
        "a connection whose peer has closed reports isOpen=false (pool health check)" in {
            val transport = NetPlatform.transport
            for
                serverRef <- AtomicRef.init[Maybe[Connection]](Absent)
                accepted  <- Channel.init[Unit](1)
                listener <- transport.listen("127.0.0.1", 0, 128) { serverConn =>
                    discard(Sync.Unsafe.evalOrThrow {
                        Fiber.initUnscoped {
                            Abort.run[Closed] {
                                Sync.Unsafe.defer(serverRef.set(Present(serverConn))).andThen(accepted.put(()))
                            }.unit
                        }
                    })
                }.safe.get
                client <- transport.connect("127.0.0.1", listener.port).safe.get
                _      <- accepted.take
                server <- serverRef.get.map {
                    case Present(c) => c
                    case Absent     => fail("server connection was never captured")
                }
                // The peer (server) closes the idle connection; the client's standing read sees the FIN as EOF and tears down. The teardown
                // sets closedFlag inside closeFn, which the ReadPump runs in the onComplete callback of inbound.closeAwaitEmpty, so closedFlag
                // flips a scheduler turn after inbound terminates rather than synchronously with the take returning Closed. Drain inbound to
                // Closed (EOF observed), then assert the eventual pool-health-check contract: isOpen must become false so a pool discards the
                // dead conn instead of reusing it. A backend that never flips it fails via the per-test timeout.
                _ <- Sync.defer(server.close())
                _ <- Abort.run[Closed](client.inbound.safe.take)
                _ <- assertEventually(Sync.defer(!client.isOpen))
            yield
                client.close()
                listener.close()
            end for
        }
    }

end TransportLifecycleTest
