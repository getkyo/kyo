package kyo.net

import kyo.*

/** Cross-backend, cross-TLS-implementation round-trip for the public [[Transport]] surface.
  *
  * The TLS round-trip contract is backend- and implementation-agnostic, so it is asserted once over the full backend x TLS-impl matrix via
  * [[eachBackendTls]]: every registered backend (io_uring/epoll/kqueue/nio on JVM, node on JS) drives every TLS implementation the platform
  * registers (BoringSSL and the JDK SslEngine on JVM; BoringSSL/OpenSSL on Native; Node on JS), one cell each. The harness wires the shared test
  * certificate and pins each cell's `tlsProvider`; cells whose backend or TLS impl is unavailable, or whose backend cannot drive the impl, cancel.
  */
class TransportTlsTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Read exactly `target` bytes from a connection's inbound channel, concatenated in order. */
    private def collect(conn: Connection, target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= target then Loop.done(acc)
            else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
        }

    "connect(tls) + listen(tls) round-trips an encrypted message through an echo handler" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            for
                accepted <- Channel.init[Unit](1)
                listener <- transport.listenTls("127.0.0.1", 0, 16, serverTls) { serverConn =>
                    discard(Sync.Unsafe.evalOrThrow {
                        Fiber.initUnscoped {
                            Abort.run[Closed] {
                                accepted.put(()).andThen {
                                    Loop.foreach {
                                        serverConn.inbound.safe.take.map { chunk =>
                                            serverConn.outbound.safe.put(chunk).andThen(Loop.continue)
                                        }
                                    }
                                }
                            }.unit
                        }
                    })
                }.safe.get
                client <- transport.connectTls("127.0.0.1", listener.port, clientTls).safe.get
                message = "kyo-shared-tls-roundtrip".getBytes("UTF-8")
                _      <- client.outbound.safe.put(Span.fromUnsafe(message))
                _      <- accepted.take
                echoed <- collect(client, message.length)
            yield
                client.close()
                listener.close()
                assert(echoed.sameElements(message), s"TLS echo mismatch on this cell: got '${new String(echoed, "UTF-8")}'")
            end for
    }

    "a version-mismatch handshake fails (no silent downgrade)" - eachBackendTls { (transport, serverTls, clientTls) =>
        import NetTlsConfig.Version.*
        // Server accepts only TLS 1.3; client offers only TLS 1.2. There is no common version, so the handshake MUST fail on every cell rather
        // than silently negotiating an unintended version. The cell's provider pin carried by serverTls/clientTls is preserved through copy, so
        // the rejection is asserted on each TLS implementation, not just the platform default. Bounded by Async.timeout so a cell that mishandled
        // the mismatch by hanging fails the guard rather than the suite.
        val srv = serverTls.copy(minVersion = TLS13, maxVersion = TLS13)
        val cli = clientTls.copy(minVersion = TLS12, maxVersion = TLS12)
        transport.listenTls("127.0.0.1", 0, 16, srv)(_ => ()).safe.get.map { listener =>
            Abort.run[NetException | Closed | Timeout](
                Async.timeout(5.seconds)(transport.connectTls("127.0.0.1", listener.port, cli).safe.get)
            ).map { outcome =>
                listener.close()
                assert(outcome.isFailure, s"a TLS version-mismatch handshake must fail on this cell, got $outcome")
            }
        }
    }

    "a TLS client connecting to a plaintext (non-TLS) server fails Closed" - eachBackendTls { (transport, _, clientTls) =>
        // The server is plaintext and closes each accepted connection immediately, so it never performs a TLS handshake. A connect(tls) client
        // must fail (the handshake aborts on EOF), not hang or silently succeed. Bounded so a cell that hung would fail the guard.
        transport.listen("127.0.0.1", 0, 16)(conn => conn.close()).safe.get.map { listener =>
            Abort.run[NetException | Closed | Timeout](
                Async.timeout(5.seconds)(transport.connectTls("127.0.0.1", listener.port, clientTls).safe.get)
            ).map { outcome =>
                listener.close()
                assert(outcome.isFailure, s"a TLS client against a plaintext server must fail on this cell, got $outcome")
            }
        }
    }

end TransportTlsTest
