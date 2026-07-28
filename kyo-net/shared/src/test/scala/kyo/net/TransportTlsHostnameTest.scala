package kyo.net

import kyo.*

/** Cross-backend, cross-TLS-implementation connect-time hostname-verification guarantees for the public [[Transport]] surface, over the full
  * backend x TLS-impl matrix via [[eachBackendTls]]. A verifying client (the default `hostnameVerification = true`, not `trustAll`) accepts a
  * server whose certificate name matches the connect host, fails closed when it has no reference identity (RFC 9525 6.1), and rejects a
  * name-mismatched certificate. Each cell pins its TLS implementation, so the identity decision is asserted on every implementation, not just the
  * platform default.
  */
class TransportTlsHostnameTest extends Test:

    import AllowUnsafe.embrace.danger

    /** The cell's client config turned into a VERIFYING client: not `trustAll`, pinned to the test cert as CA, hostnameVerification on (default).
      * The cell's `tlsProvider` pin carried by `clientTls` is preserved.
      */
    private def verifyingClient(serverTls: NetTlsConfig, clientTls: NetTlsConfig): NetTlsConfig =
        clientTls.copy(trustAll = false, caCertPath = serverTls.certChainPath)

    "a verifying client accepts a server whose certificate matches the connect host (127.0.0.1)" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            // The cert has SAN IP:127.0.0.1, so connecting to 127.0.0.1 must validate both the chain and the IP identity.
            val cli = verifyingClient(serverTls, clientTls)
            for
                accepted <- Channel.init[Unit](1)
                listener <- transport.listenTls("127.0.0.1", 0, 16, serverTls) { serverConn =>
                    discard(Sync.Unsafe.evalOrThrow {
                        Fiber.initUnscoped {
                            Abort.run[Closed] {
                                accepted.put(()).andThen {
                                    Loop.foreach {
                                        serverConn.inbound.safe.take.map(c => serverConn.outbound.safe.put(c).andThen(Loop.continue))
                                    }
                                }
                            }.unit
                        }
                    })
                }.safe.get
                _      <- Scope.ensure(Sync.defer(listener.close()))
                client <- transport.connectTls("127.0.0.1", listener.port, cli).safe.get
                _      <- Scope.ensure(Sync.defer(client.close()))
                msg = "verified".getBytes("UTF-8")
                _      <- client.outbound.safe.put(Span.fromUnsafe(msg))
                _      <- accepted.take
                echoed <- client.inbound.safe.take
            yield
                client.close()
                listener.close()
                assert(echoed.toArray.sameElements(msg), "a verifying client matching the connect host must round-trip")
            end for
    }

    "a verifying client with no reference identity (empty host) fails closed (RFC 9525 6.1)" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            val cli = verifyingClient(serverTls, clientTls)
            transport.listenTls("127.0.0.1", 0, 16, serverTls)(_ => ()).safe.get.map { listener =>
                Scope.ensure(Sync.defer(listener.close())).andThen {
                    // An empty host gives a verifying client nothing to check the certificate name against. It must NOT silently accept a
                    // chain-valid cert: the connect fails closed (either at the pre-connect identity guard or during the handshake). Bounded.
                    Abort.run[NetException | Closed | Timeout](
                        Async.timeout(5.seconds)(transport.connectTls("", listener.port, cli).safe.get)
                    ).map { outcome =>
                        listener.close()
                        // Defensive: if this ever unexpectedly succeeds (the assertion below would then fail the leaf), the returned
                        // connection must still not leak.
                        outcome.foreach(conn => conn.close())
                        assert(outcome.isFailure, s"a verifying client with an empty host must fail closed, got $outcome")
                    }
                }
            }
    }

    "a verifying client rejects a server whose certificate name does not match the connect host" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            // The server presents a cert for wronghost.example (a fixture distinct from the harness cert), so build fresh configs carrying the
            // cell's provider pin. The client trusts that cert as its CA (chain validates) but connects to 127.0.0.1, which the cert does not
            // cover, so hostname verification must reject the name mismatch on every implementation. Bounded.
            TlsTestCertShared.writeWrongHostPems.map { case (wrongCert, wrongKey) =>
                val srv = NetTlsConfig(
                    certChainPath = Present(wrongCert),
                    privateKeyPath = Present(wrongKey),
                    tlsProvider = serverTls.tlsProvider
                )
                val cli = NetTlsConfig(caCertPath = Present(wrongCert), tlsProvider = clientTls.tlsProvider)
                transport.listenTls("127.0.0.1", 0, 16, srv)(_ => ()).safe.get.map { listener =>
                    Scope.ensure(Sync.defer(listener.close())).andThen {
                        Abort.run[NetException | Closed | Timeout](
                            Async.timeout(5.seconds)(transport.connectTls("127.0.0.1", listener.port, cli).safe.get)
                        ).map { outcome =>
                            listener.close()
                            // Defensive: if this ever unexpectedly succeeds, the returned connection must still not leak.
                            outcome.foreach(conn => conn.close())
                            assert(outcome.isFailure, s"a verifying client must reject a name-mismatched server cert, got $outcome")
                        }
                    }
                }
            }
    }

end TransportTlsHostnameTest
