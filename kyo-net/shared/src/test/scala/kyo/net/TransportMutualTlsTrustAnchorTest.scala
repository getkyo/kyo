package kyo.net

import kyo.*

/** Cross-backend, cross-TLS-implementation verification that a mutual-TLS server validates client certificates against `trustStorePath`, with
  * `caCertPath` as the documented fallback.
  *
  * `NetTlsConfig.trustStorePath` documents the server-side CA bundle for verifying client certs, so a `clientAuth = Required` server configured
  * with `trustStorePath` (and `caCertPath` Absent) MUST accept a client whose certificate that CA trusts and reject one it does not. The
  * precedence leaf asserts that when both `trustStorePath` and `caCertPath` are set to different CAs, the server validates against
  * `trustStorePath`. Asserted over the full backend x TLS-impl matrix via [[eachBackendTls]] so every backend wires the same anchor.
  *
  * Like [[TransportMutualTlsTest]], the assertions are on the application round-trip, not on `connect`: under TLS 1.3 a rejected client's
  * `connect` can return before the server validates its certificate, so the security guard is whether the round-trip completes. The first leaf
  * guards that every backend reads `trustStorePath`: a backend that ignored it would leave a server with `caCertPath` Absent no client-cert
  * trust anchor, rejecting even the trusted client.
  */
class TransportMutualTlsTrustAnchorTest extends Test:

    import AllowUnsafe.embrace.danger

    private def collect(conn: Connection, target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= target then Loop.done(acc)
            else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
        }

    /** Listen with a clientAuth=Required server whose accepted connections echo their inbound stream. */
    private def echoListener(transport: Transport, tls: NetTlsConfig)(using Frame): Listener < (Async & Abort[NetException]) =
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

    "a clientAuth=Required server validates client certs against trustStorePath (caCertPath Absent)" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            // Server trusts the client cert via trustStorePath, not caCertPath: the wiring under test. The client presents that same trusted cert.
            val serverMtls = serverTls.copy(
                trustStorePath = serverTls.certChainPath,
                caCertPath = Absent,
                clientAuth = NetTlsConfig.ClientAuth.Required
            )
            val clientWithCert = clientTls.copy(certChainPath = serverTls.certChainPath, privateKeyPath = serverTls.privateKeyPath)
            echoListener(transport, serverMtls).map { listener =>
                val message = "kyo-truststore".getBytes("UTF-8")
                // Generous hang-guard: a mutual-TLS round-trip verifies a cert chain on both ends and is slow on a cold/loaded runner, so 5s timed
                // out on every cell at once. A true deadlock still fails within the suite's 60s leaf budget (see kyo.net.Test); mirrors TransportMutualTlsTest.
                val connectAndEcho: (Connection, Array[Byte]) < (Async & Abort[NetException | Closed]) =
                    transport.connect("127.0.0.1", listener.port, clientWithCert).safe.get.map { client =>
                        client.outbound.safe.put(Span.fromUnsafe(message)).andThen(collect(client, message.length)).map(client -> _)
                    }
                val outcome: Result[NetException | Closed | Timeout, (Connection, Array[Byte])] < Async =
                    Abort.run[NetException | Closed | Timeout](Async.timeout(30.seconds)(connectAndEcho))
                outcome.map { outcome =>
                    listener.close()
                    outcome match
                        case Result.Success((client, echoed)) =>
                            client.close()
                            assert(
                                echoed.sameElements(message),
                                s"trustStorePath-validated mutual-TLS echo mismatch: got '${new String(echoed, "UTF-8")}'"
                            )
                        case other =>
                            assert(false, s"a trustStorePath-trusted client must round-trip on this cell, got $other")
                    end match
                }
            }
    }

    "a clientAuth=Required server rejects a client cert trustStorePath does not trust" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            // Server trusts only the localhost cert (trustStorePath); the client presents the distinct wrong-host cert, which that CA does not trust.
            val serverMtls = serverTls.copy(
                trustStorePath = serverTls.certChainPath,
                caCertPath = Absent,
                clientAuth = NetTlsConfig.ClientAuth.Required
            )
            TlsTestCertShared.writeWrongHostPems.map { case (untrustedCert, untrustedKey) =>
                val untrustedClient = clientTls.copy(certChainPath = Present(untrustedCert), privateKeyPath = Present(untrustedKey))
                echoListener(transport, serverMtls).map { listener =>
                    val message = "kyo-untrusted".getBytes("UTF-8")
                    val connectAndEcho: Array[Byte] < (Async & Abort[NetException | Closed]) =
                        transport.connect("127.0.0.1", listener.port, untrustedClient).safe.get.map { client =>
                            client.outbound.safe.put(Span.fromUnsafe(message)).andThen(collect(client, message.length))
                        }
                    val outcome: Result[NetException | Closed | Timeout, Array[Byte]] < Async =
                        Abort.run[NetException | Closed | Timeout](Async.timeout(5.seconds)(connectAndEcho))
                    outcome.map { outcome =>
                        listener.close()
                        assert(
                            outcome.isFailure,
                            s"a server must reject a client cert trustStorePath does not trust, got $outcome"
                        )
                    }
                }
            }
    }

    "caCertPath is the documented server fallback: trustStorePath takes precedence when both are set" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            // trustStorePath -> the localhost cert (which the client presents); caCertPath -> the distinct wrong-host cert. The client is trusted
            // only by trustStorePath, so it round-trips iff the server validates against trustStorePath (the precedence), not caCertPath.
            TlsTestCertShared.writeWrongHostPems.map { case (fallbackCa, _) =>
                val serverMtls = serverTls.copy(
                    trustStorePath = serverTls.certChainPath,
                    caCertPath = Present(fallbackCa),
                    clientAuth = NetTlsConfig.ClientAuth.Required
                )
                val clientWithCert = clientTls.copy(certChainPath = serverTls.certChainPath, privateKeyPath = serverTls.privateKeyPath)
                echoListener(transport, serverMtls).map { listener =>
                    val message = "kyo-precedence".getBytes("UTF-8")
                    // Generous hang-guard (see the trustStorePath leaf above): a cold/loaded mutual-TLS round-trip exceeds a 5s bound.
                    val connectAndEcho: (Connection, Array[Byte]) < (Async & Abort[NetException | Closed]) =
                        transport.connect("127.0.0.1", listener.port, clientWithCert).safe.get.map { client =>
                            client.outbound.safe.put(Span.fromUnsafe(message)).andThen(collect(client, message.length)).map(client -> _)
                        }
                    val outcome: Result[NetException | Closed | Timeout, (Connection, Array[Byte])] < Async =
                        Abort.run[NetException | Closed | Timeout](Async.timeout(30.seconds)(connectAndEcho))
                    outcome.map { outcome =>
                        listener.close()
                        outcome match
                            case Result.Success((client, echoed)) =>
                                client.close()
                                assert(
                                    echoed.sameElements(message),
                                    s"precedence mutual-TLS echo mismatch: got '${new String(echoed, "UTF-8")}'"
                                )
                            case other =>
                                assert(
                                    false,
                                    s"trustStorePath must take precedence over caCertPath, so the client must round-trip, got $other"
                                )
                        end match
                    }
                }
            }
    }

end TransportMutualTlsTrustAnchorTest
