package kyo.net

import kyo.*

/** Cross-backend, cross-TLS-implementation STARTTLS upgrade via the PUBLIC API only (`Transport.upgradeToTls` on both peers), over the full
  * backend x TLS-impl matrix via [[eachBackendTls]]. A real user cannot reach the connection's `private[net]` upgrade internals, so the server
  * side MUST upgrade through `transport.upgradeToTls(serverConn, serverTls)`. The flow mirrors Postgres SSLRequest: the client sends a 1-byte
  * signal, the server replies ready, then both peers upgrade to TLS over the same socket and round-trip an encrypted message. Each cell pins its
  * TLS implementation, so the upgrade, mutual TLS, hostname verification, multi-record transfer, and cert-hash introspection are asserted on every
  * implementation, not just the platform default.
  */
class TransportStartTlsTest extends Test:

    import AllowUnsafe.embrace.danger

    private val upgradeRequest: Span[Byte] = Span.from(Array[Byte]('U'))
    private val upgradeReady: Span[Byte]   = Span.from(Array[Byte]('R'))

    // Focused corrupt-delivery repro gate: when KYO_NET_SUCCESS_ONLY=1 (or -Dkyo.net.successLeavesOnly=true) the expected-failure (reject) leaves
    // cancel every cell, so the suite runs only the success/round-trip upgrade leaves. Then any handshake EngineError or strand is the upgrade-handoff
    // delivery bug rather than an expected reject, making it directly attributable without a per-leaf trace tag. Default (unset) runs every leaf.
    private val successLeavesOnly: Boolean =
        sys.env.get("KYO_NET_SUCCESS_ONLY").contains("1") || sys.props.get("kyo.net.successLeavesOnly").contains("true")
    private val rejectSkip: (String, String) => Maybe[String] =
        (_, _) => if successLeavesOnly then Present("focused corrupt-delivery repro: reject leaf excluded") else Absent

    /** A server that waits for the upgrade signal, replies ready, upgrades to TLS in `serverTls`, then echoes its inbound stream. */
    private def startTlsEchoServer(transport: Transport, serverTls: NetTlsConfig)(using Frame): Listener < (Async & Abort[NetException]) =
        transport.listen("127.0.0.1", 0, 128) { serverConn =>
            discard(Sync.Unsafe.evalOrThrow {
                Fiber.initUnscoped {
                    Abort.run[Closed] {
                        serverConn.inbound.safe.take.flatMap { _ =>
                            serverConn.outbound.safe.put(upgradeReady).andThen {
                                transport.upgradeToTls(serverConn, serverTls, 16).safe.get.flatMap { tlsConn =>
                                    Loop.foreach {
                                        tlsConn.inbound.safe.take.flatMap { data =>
                                            tlsConn.outbound.safe.put(data).andThen(Loop.continue)
                                        }
                                    }
                                }
                            }
                        }
                    }.unit
                }
            })
        }.safe.get

    /** The client side: connect plaintext, signal, await ready, upgrade to TLS in `clientTls`, send `msg`, return the echoed bytes. */
    private def startTlsClient(transport: Transport, port: Int, clientTls: NetTlsConfig, msg: Array[Byte])(using
        Frame
    ): Array[Byte] < (Async & Abort[NetException | Closed]) =
        for
            conn     <- transport.connect("127.0.0.1", port).safe.get
            _        <- conn.outbound.safe.put(upgradeRequest)
            _        <- conn.inbound.safe.take
            tlsConn  <- transport.upgradeToTls(conn, clientTls, 16).safe.get
            _        <- tlsConn.outbound.safe.put(Span.fromUnsafe(msg))
            received <- tlsConn.inbound.safe.take
        yield received.toArray

    /** The cell's server config, additionally demanding a client certificate signed by the (self-signed) test cert. */
    private def serverMtls(serverTls: NetTlsConfig): NetTlsConfig =
        serverTls.copy(caCertPath = serverTls.certChainPath, clientAuth = NetTlsConfig.ClientAuth.Required)

    private def collectN(conn: Connection, target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= target then Loop.done(acc)
            else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
        }

    "a plaintext connection upgrades to TLS on both peers and round-trips an encrypted message" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            val cli = clientTls.copy(sniHostname = Present("localhost"))
            startTlsEchoServer(transport, serverTls).map { listener =>
                startTlsClient(transport, listener.port, cli, "hello-tls".getBytes("UTF-8")).map { echoed =>
                    listener.close()
                    assert(new String(echoed, "UTF-8") == "hello-tls")
                }
            }
    }

    "repeated STARTTLS upgrades on one transport each round-trip (upgrade-handoff drop regression)" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            // The upgrade-handoff race (the retiring plaintext pump dropping or stealing the peer's first TLS flight on the shared handle) is
            // probabilistic: a single upgrade can pass by luck. Looping the tight connect -> signal -> upgrade-immediately -> round-trip cycle makes it
            // surface reliably: every round must echo, or a dropped ClientHello strands the handshake and the bounding timeout fails the leaf. This is
            // the deterministic regression guard for the upgrade-handoff drop the selector/poll-carrier confinement closes on every non-io_uring backend.
            val cli    = clientTls.copy(sniHostname = Present("localhost"))
            val rounds = 20
            startTlsEchoServer(transport, serverTls).map { listener =>
                Abort.run[Timeout] {
                    Async.timeout(60.seconds) {
                        Loop.indexed { i =>
                            if i >= rounds then Loop.done(())
                            else
                                val msg = s"handoff-$i".getBytes("UTF-8")
                                startTlsClient(transport, listener.port, cli, msg).map { echoed =>
                                    assert(
                                        new String(echoed, "UTF-8") == s"handoff-$i",
                                        s"round $i must round-trip after the STARTTLS upgrade"
                                    )
                                    Loop.continue
                                }
                        }
                    }
                }.map { outcome =>
                    listener.close()
                    assert(outcome.isSuccess, s"all $rounds STARTTLS upgrades must round-trip without stranding; got $outcome")
                }
            }
    }

    "a STARTTLS upgrade with mutual TLS (client presents its certificate) round-trips" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            val clientWithCert = clientTls.copy(
                certChainPath = serverTls.certChainPath,
                privateKeyPath = serverTls.privateKeyPath,
                sniHostname = Present("localhost")
            )
            startTlsEchoServer(transport, serverMtls(serverTls)).map { listener =>
                startTlsClient(transport, listener.port, clientWithCert, "hello-mtls".getBytes("UTF-8")).map { echoed =>
                    listener.close()
                    assert(new String(echoed, "UTF-8") == "hello-mtls")
                }
            }
    }

    "a STARTTLS mutual-TLS server rejects a client that presents no certificate (no round-trip)" - eachBackendTlsExcept {
        (transport, serverTls, clientTls) =>
            val clientNoCert = clientTls.copy(sniHostname = Present("localhost"))
            startTlsEchoServer(transport, serverMtls(serverTls)).map { listener =>
                Abort.run[NetException | Closed | Timeout](
                    Async.timeout(5.seconds)(startTlsClient(transport, listener.port, clientNoCert, "hello-mtls".getBytes("UTF-8")))
                ).map { outcome =>
                    listener.close()
                    assert(
                        outcome.isFailure,
                        s"a clientAuth=Required STARTTLS server must not let a certless client round-trip on this cell, got $outcome"
                    )
                }
            }
    }(rejectSkip)

    "a STARTTLS upgrade against a server that never starts TLS fails" - eachBackendTlsExcept { (transport, _, clientTls) =>
        val cli = clientTls.copy(sniHostname = Present("localhost"))
        // A plaintext server that reads the upgrade signal then closes, never performing a TLS handshake. The client's upgrade must fail.
        transport.listen("127.0.0.1", 0, 128) { serverConn =>
            discard(Sync.Unsafe.evalOrThrow {
                Fiber.initUnscoped {
                    Abort.run[Closed](serverConn.inbound.safe.take.map(_ => serverConn.close())).unit
                }
            })
        }.safe.get.map { listener =>
            val attempt: Span[Byte] < (Async & Abort[NetException | Closed]) =
                for
                    conn    <- transport.connect("127.0.0.1", listener.port).safe.get
                    _       <- conn.outbound.safe.put(upgradeRequest)
                    tlsConn <- transport.upgradeToTls(conn, cli, 16).safe.get
                    _       <- tlsConn.outbound.safe.put(Span.from("x".getBytes))
                    r       <- tlsConn.inbound.safe.take
                yield r
            val outcome: Result[NetException | Closed | Timeout, Span[Byte]] < Async =
                Abort.run[NetException | Closed | Timeout](Async.timeout(5.seconds)(attempt))
            outcome.map { outcome =>
                listener.close()
                assert(outcome.isFailure, s"a STARTTLS upgrade against a non-TLS server must fail on this cell, got $outcome")
            }
        }
    }(rejectSkip)

    "a second upgradeToTls on an upgrading connection fails typed and close() still settles the first upgrade" - eachBackendTlsExcept {
        (transport, _, clientTls) =>
            val cli = clientTls.copy(sniHostname = Present("localhost"))
            // A plaintext server that consumes the signal, replies ready, then holds the connection open without ever starting TLS: the
            // client's first upgrade parks awaiting a ServerHello that never comes, so the second call and the close() below land against
            // a genuinely in-flight upgrade. The trailing take loop consumes (and ignores) whatever the abandoned handshake already sent,
            // keeping the socket open so nothing but close() can settle the first upgrade.
            transport.listen("127.0.0.1", 0, 128) { serverConn =>
                discard(Sync.Unsafe.evalOrThrow {
                    Fiber.initUnscoped {
                        Abort.run[Closed] {
                            serverConn.inbound.safe.take.flatMap { _ =>
                                serverConn.outbound.safe.put(upgradeReady).andThen {
                                    Loop.foreach(serverConn.inbound.safe.take.andThen(Loop.continue))
                                }
                            }
                        }.unit
                    }
                })
            }.safe.get.map { listener =>
                for
                    conn <- transport.connect("127.0.0.1", listener.port).safe.get
                    _    <- conn.outbound.safe.put(upgradeRequest)
                    _    <- conn.inbound.safe.take
                    first = transport.upgradeToTls(conn, cli, 16).safe
                    second <-
                        Abort.run[NetException | Closed | Timeout](Async.timeout(5.seconds)(transport.upgradeToTls(conn, cli, 16).safe.get))
                    _ = conn.close()
                    firstOutcome <- Abort.run[NetException | Closed | Timeout](Async.timeout(10.seconds)(first.get))
                yield
                    listener.close()
                    assert(
                        second.failure.exists(_.isInstanceOf[NetAlreadyDetachedException]),
                        s"a second upgradeToTls on an upgrading connection must fail NetAlreadyDetachedException, got $second"
                    )
                    // The second call must not have disarmed the first upgrade's close route: close() settles the still-parked first
                    // upgrade with the typed close leaf and releases what it holds. A Timeout here means the first upgrade was stranded.
                    assert(
                        firstOutcome.failure.exists(_.isInstanceOf[NetConnectionClosedException]),
                        s"close() must settle the first, still-parked upgrade with NetConnectionClosedException, got $firstOutcome"
                    )
                end for
            }
    }(rejectSkip)

    "after a STARTTLS upgrade the original plaintext connection is closed and a multi-record payload round-trips" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            val cli     = clientTls.copy(sniHostname = Present("localhost"))
            val payload = Array.fill[Byte](32768)(42) // spans multiple TLS records (max record ~16KB)
            startTlsEchoServer(transport, serverTls).map { listener =>
                for
                    conn    <- transport.connect("127.0.0.1", listener.port).safe.get
                    _       <- conn.outbound.safe.put(upgradeRequest)
                    _       <- conn.inbound.safe.take
                    tlsConn <- transport.upgradeToTls(conn, cli, 16).safe.get
                    plainOpen = conn.isOpen
                    tlsOpen   = tlsConn.isOpen
                    _      <- tlsConn.outbound.safe.put(Span.fromUnsafe(payload))
                    echoed <- collectN(tlsConn, payload.length)
                yield
                    tlsConn.close()
                    listener.close()
                    assert(!plainOpen, "the original plaintext connection must be closed after upgrade")
                    assert(tlsOpen, "the upgraded TLS connection must be open")
                    assert(
                        echoed.length == payload.length && echoed.sameElements(payload),
                        s"a 32KB payload must round-trip across TLS records, got ${echoed.length} bytes"
                    )
                end for
            }
    }

    "a STARTTLS upgrade with hostname verification accepts a matching server certificate" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            // Verifying client (not trustAll): pin the cert as CA and verify "localhost", which the localhost cert's SAN covers, so it accepts.
            val cli = clientTls.copy(trustAll = false, caCertPath = serverTls.certChainPath, sniHostname = Present("localhost"))
            startTlsEchoServer(transport, serverTls).map { listener =>
                startTlsClient(transport, listener.port, cli, "hello-verified".getBytes("UTF-8")).map { echoed =>
                    listener.close()
                    assert(new String(echoed, "UTF-8") == "hello-verified")
                }
            }
    }

    "a STARTTLS upgrade by a verifying client with no reference identity (empty host) fails closed" - eachBackendTlsExcept {
        (transport, serverTls, clientTls) =>
            // A verifying client (trustAll = false) pins the cert as CA so the chain validates, but leaves sniHostname Absent, so upgradeToTls
            // drives the handshake with host = "", i.e. no reference identity. A chain-valid certificate with no bound name is never acceptable
            // (RFC 9525 6.1; CWE-295): the upgrade must fail closed on every cell. Bounded so a hang fails the guard.
            val verifyingClientNoSni = clientTls.copy(trustAll = false, caCertPath = serverTls.certChainPath, sniHostname = Absent)
            startTlsEchoServer(transport, serverTls).map { listener =>
                Abort.run[NetException | Closed | Timeout](
                    Async.timeout(5.seconds)(startTlsClient(transport, listener.port, verifyingClientNoSni, "x".getBytes("UTF-8")))
                ).map { outcome =>
                    listener.close()
                    assert(
                        outcome.isFailure,
                        s"a verifying STARTTLS client with an empty reference identity must fail closed (RFC 9525 6.1), got $outcome"
                    )
                }
            }
    }(rejectSkip)

    "a STARTTLS upgrade with hostname verification rejects a name-mismatched server certificate" - eachBackendTlsExcept {
        (transport, serverTls, clientTls) =>
            // The server presents a wronghost.example cert (a fixture distinct from the harness cert), so build fresh configs carrying the cell's
            // provider pin. The client trusts it as CA but verifies "localhost", so the name mismatch must reject on every implementation.
            TlsTestCertShared.writeWrongHostPems.map { case (wrongCert, wrongKey) =>
                val srv = NetTlsConfig(
                    certChainPath = Present(wrongCert),
                    privateKeyPath = Present(wrongKey),
                    tlsProvider = serverTls.tlsProvider
                )
                val cli =
                    NetTlsConfig(caCertPath = Present(wrongCert), sniHostname = Present("localhost"), tlsProvider = clientTls.tlsProvider)
                startTlsEchoServer(transport, srv).map { listener =>
                    Abort.run[NetException | Closed | Timeout](
                        Async.timeout(5.seconds)(startTlsClient(transport, listener.port, cli, "x".getBytes("UTF-8")))
                    ).map { outcome =>
                        listener.close()
                        assert(outcome.isFailure, s"a STARTTLS client verifying localhost must reject a wronghost cert, got $outcome")
                    }
                }
            }
    }(rejectSkip)

    "a STARTTLS-upgraded connection reports the server certificate hash" - eachBackendTls { (transport, serverTls, clientTls) =>
        // After a STARTTLS upgrade the client connection must expose the server's RFC 5929 channel-binding hash, exactly as a connect-time TLS
        // connection does, proving the TLS engine is attached to the upgraded handle. The hash is the SHA-256 of the server certificate (32 bytes).
        val cli = clientTls.copy(sniHostname = Present("localhost"))
        startTlsEchoServer(transport, serverTls).map { listener =>
            for
                conn    <- transport.connect("127.0.0.1", listener.port).safe.get
                _       <- conn.outbound.safe.put(upgradeRequest)
                _       <- conn.inbound.safe.take
                tlsConn <- transport.upgradeToTls(conn, cli, 16).safe.get
                _       <- tlsConn.outbound.safe.put(Span.from("hash-check".getBytes("UTF-8")))
                _       <- tlsConn.inbound.safe.take
                certHash = tlsConn.serverCertificateHash
            yield
                tlsConn.close()
                listener.close()
                certHash match
                    case Present(h) => assert(h.size == 32, s"the server cert hash must be 32 bytes (SHA-256), got ${h.size}")
                    case Absent     => fail("an upgraded TLS connection must report the server certificate hash (RFC 5929 channel binding)")
            end for
        }
    }

end TransportStartTlsTest
