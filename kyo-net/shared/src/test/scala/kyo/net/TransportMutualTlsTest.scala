package kyo.net

import kyo.*

/** Cross-backend, cross-TLS-implementation mutual-TLS (client-certificate authentication) for the public [[Transport]] surface.
  *
  * `NetTlsConfig.clientAuth` is a public option, so its behavior must be identical on every backend AND every TLS implementation: a server that
  * sets `clientAuth = Required` MUST reject a client that presents no certificate, and MUST accept a client that presents a certificate its
  * configured CA trusts. Asserted over the full backend x TLS-impl matrix via [[eachBackendTls]]. Both peers use the shared self-signed test
  * certificate: the server trusts it as the client CA (`caCertPath`) and the client presents it (`certChainPath` + `privateKeyPath`); the cell's
  * provider pin carried by the configs is preserved through copy, so each TLS implementation enforces clientAuth, not just the platform default.
  *
  * The assertions are on the application round-trip, not on `connect`: under TLS 1.3 the client completes its handshake flight and `connect`
  * returns before the server validates the client certificate, so a rejected certless client connects and only fails on use. The negative leaf
  * is the security guard: a backend/impl that silently ignored `clientAuth` would let the certless client round-trip and fail this leaf.
  */
class TransportMutualTlsTest extends Test:

    import AllowUnsafe.embrace.danger

    private def collect(conn: Connection, target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= target then Loop.done(acc)
            else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
        }

    /** The cell's server config, additionally demanding a client certificate signed by the (self-signed) test cert. */
    private def serverMtls(serverTls: NetTlsConfig): NetTlsConfig =
        serverTls.copy(caCertPath = serverTls.certChainPath, clientAuth = NetTlsConfig.ClientAuth.Required)

    /** Listen with a clientAuth=Required server whose accepted connections echo their inbound stream. */
    private def echoListener(transport: Transport, tls: NetTlsConfig)(using Frame): Listener < (Async & Abort[Closed]) =
        transport.listen("127.0.0.1", 0, 16, tls) { serverConn =>
            discard(Sync.Unsafe.evalOrThrow {
                Fiber.initUnscoped {
                    Abort.run[Closed] {
                        Loop.foreach {
                            serverConn.inbound.safe.take.map { chunk =>
                                serverConn.outbound.safe.put(chunk).andThen(Loop.continue)
                            }
                        }
                    }.unit
                }
            })
        }.safe.get

    "a clientAuth=Required server rejects a client that presents no certificate (no round-trip)" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            // The client trusts the server but presents no certificate of its own; the cell pin is preserved on clientTls.
            echoListener(transport, serverMtls(serverTls)).map { listener =>
                val message = "kyo-mtls-reject".getBytes("UTF-8")
                Abort.run[Closed | Timeout](
                    Async.timeout(5.seconds)(
                        transport.connect("127.0.0.1", listener.port, clientTls).safe.get.map { client =>
                            client.outbound.safe.put(Span.fromUnsafe(message)).andThen(collect(client, message.length))
                        }
                    )
                ).map { outcome =>
                    listener.close()
                    assert(
                        outcome.isFailure,
                        s"a clientAuth=Required server must not let a certless client round-trip on this cell, got $outcome"
                    )
                }
            }
    }

    "a clientAuth=Required server accepts a client that presents a trusted certificate and round-trips" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            val clientWithCert = clientTls.copy(certChainPath = serverTls.certChainPath, privateKeyPath = serverTls.privateKeyPath)
            echoListener(transport, serverMtls(serverTls)).map { listener =>
                val message = "kyo-mutual-tls".getBytes("UTF-8")
                Abort.run[Closed | Timeout](
                    Async.timeout(5.seconds)(
                        transport.connect("127.0.0.1", listener.port, clientWithCert).safe.get.map { client =>
                            client.outbound.safe.put(Span.fromUnsafe(message)).andThen(collect(client, message.length)).map(client -> _)
                        }
                    )
                ).map { outcome =>
                    listener.close()
                    outcome match
                        case Result.Success((client, echoed)) =>
                            client.close()
                            assert(echoed.sameElements(message), s"mutual-TLS echo mismatch: got '${new String(echoed, "UTF-8")}'")
                        case other =>
                            assert(false, s"mutual-TLS round-trip must succeed on this cell, got $other")
                    end match
                }
            }
    }

end TransportMutualTlsTest
