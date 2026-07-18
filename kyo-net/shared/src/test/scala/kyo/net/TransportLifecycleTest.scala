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
  * The first two contracts are backend-agnostic, so they belong in the shared suite where each platform's real transport runs them.
  */
class TransportLifecycleTest extends Test:

    import AllowUnsafe.embrace.danger

    "Transport lifecycle (every backend via NetPlatform.transport)" - {

        "connecting to a port with no listener fails Closed" in {
            val transport = NetPlatform.transport
            // Derive a no-listener port by binding an ephemeral port through the transport, capturing it, then closing the listener. Once closed the
            // port is unbound, so a connect to it must be refused (Closed). The catch: the suites run at `parallelism 4`, so between the close and the
            // connect another suite's `listen("127.0.0.1", 0, ...)` can have the kernel re-assign that just-freed ephemeral port to ITS listener, and
            // then the connect lands on that foreign listener and SUCCEEDS, which flaked the single-shot form (a captured run connected to a reused
            // port 40971). Retry on that reuse: a connect that unexpectedly succeeds means the port was rebound by another suite, so close that
            // connection and try a fresh port. The retry never weakens the contract: it asserts that a connect to a genuinely-unbound port fails, and
            // a real bug (the product returning a live connection for an unbound port) would make every attempt "succeed" and exhaust the retries.
            def attempt(remaining: Int): Unit < (Async & Abort[NetException | Closed]) =
                transport.listen("127.0.0.1", 0, 128)(_ => ()).safe.get.map { listener =>
                    val port = listener.port
                    listener.close()
                    Abort.run[NetException | Closed](transport.connect("127.0.0.1", port).safe.get).map { outcome =>
                        val next: Unit < (Async & Abort[NetException | Closed]) =
                            outcome match
                                case Result.Success(conn) =>
                                    // The port was reused by a concurrently-running suite's listener: close the connection and retry a fresh port.
                                    // Past the retry budget, surface it as a failure (a product that hands back a live connection for an unbound port).
                                    conn.close()
                                    if remaining > 0 then attempt(remaining - 1)
                                    else
                                        Sync.defer(assert(
                                            false,
                                            s"connecting to an unbound port kept succeeding (port $port); product returned a live connection"
                                        ))
                                    end if
                                case _ =>
                                    // A Failure (the contract: refused) or a Panic both end the retry; assert the refusal.
                                    Sync.defer(assert(
                                        outcome.isFailure,
                                        s"expected Closed connecting to a port with no listener (port $port), got $outcome"
                                    ))
                        next
                    }
                }
            attempt(remaining = 16)
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
