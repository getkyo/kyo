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
        Test.isolationEnv("KYO_NET_SUCCESS_ONLY").contains("1") || sys.props.get("kyo.net.successLeavesOnly").contains("true")
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

    /** The client side: connect plaintext, signal, await ready, upgrade to TLS in `clientTls`, send `msg`, return the echoed bytes.
      *
      * Wrapped in its own `Scope.run` (rather than relying on the caller leaf's Scope) so `conn`/`tlsConn` are closed as soon as
      * THIS call finishes, success or failure: several callers invoke this helper in a loop (e.g. the repeated-upgrade regression
      * below), and deferring to the leaf's own Scope would hold every round's connection open simultaneously until the leaf ends.
      */
    private def startTlsClient(transport: Transport, port: Int, clientTls: NetTlsConfig, msg: Array[Byte])(using
        Frame
    ): Array[Byte] < (Async & Abort[NetException | Closed]) =
        Scope.run(
            for
                conn     <- transport.connect("127.0.0.1", port).safe.get
                _        <- Scope.ensure(Sync.defer(conn.close()))
                _        <- conn.outbound.safe.put(upgradeRequest)
                _        <- conn.inbound.safe.take
                tlsConn  <- transport.upgradeToTls(conn, clientTls, 16).safe.get
                _        <- Scope.ensure(Sync.defer(tlsConn.close()))
                _        <- tlsConn.outbound.safe.put(Span.fromUnsafe(msg))
                received <- tlsConn.inbound.safe.take
            yield received.toArray
        )

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
                Scope.ensure(Sync.defer(listener.close())).andThen {
                    startTlsClient(transport, listener.port, cli, "hello-tls".getBytes("UTF-8")).map { echoed =>
                        listener.close()
                        assert(new String(echoed, "UTF-8") == "hello-tls")
                    }
                }
            }
    }

    "repeated STARTTLS upgrades on one transport each round-trip (upgrade-handoff drop regression)" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            // The upgrade-handoff race (the retiring plaintext pump dropping or stealing the peer's first TLS flight on the shared handle) is
            // probabilistic: a single upgrade can pass by luck. Looping the tight connect -> signal -> upgrade-immediately -> round-trip cycle makes it
            // surface reliably: every round must echo, or a dropped ClientHello strands the handshake and the leaf hangs to its cap. This is the
            // deterministic regression guard for the upgrade-handoff drop the selector/poll-carrier confinement closes on every non-io_uring backend.
            val cli    = clientTls.copy(sniHostname = Present("localhost"))
            val rounds = 20
            startTlsEchoServer(transport, serverTls).map { listener =>
                Scope.ensure(Sync.defer(listener.close())).andThen {
                    Loop.indexed { i =>
                        if i >= rounds then Loop.done(i)
                        else
                            val msg = s"handoff-$i".getBytes("UTF-8")
                            startTlsClient(transport, listener.port, cli, msg).map { echoed =>
                                assert(new String(echoed, "UTF-8") == s"handoff-$i", s"round $i must round-trip after the STARTTLS upgrade")
                                Loop.continue
                            }
                    }.map { completed =>
                        listener.close()
                        assert(
                            completed == rounds,
                            s"all $rounds STARTTLS upgrades must round-trip without stranding; completed $completed"
                        )
                    }
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
                Scope.ensure(Sync.defer(listener.close())).andThen {
                    startTlsClient(transport, listener.port, clientWithCert, "hello-mtls".getBytes("UTF-8")).map { echoed =>
                        listener.close()
                        assert(new String(echoed, "UTF-8") == "hello-mtls")
                    }
                }
            }
    }

    "a STARTTLS mutual-TLS server rejects a client that presents no certificate (no round-trip)" - eachBackendTlsExcept {
        (transport, serverTls, clientTls) =>
            val clientNoCert = clientTls.copy(sniHostname = Present("localhost"))
            startTlsEchoServer(transport, serverMtls(serverTls)).map { listener =>
                Scope.ensure(Sync.defer(listener.close())).andThen {
                    Abort.run[NetException | Closed](
                        startTlsClient(transport, listener.port, clientNoCert, "hello-mtls".getBytes("UTF-8"))
                    ).map { outcome =>
                        listener.close()
                        assert(
                            outcome.isFailure,
                            s"a clientAuth=Required STARTTLS server must not let a certless client round-trip on this cell, got $outcome"
                        )
                    }
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
            Scope.ensure(Sync.defer(listener.close())).andThen {
                // Scope.run: this upgrade is EXPECTED to fail (the server never speaks TLS), so conn/tlsConn must be closed on the
                // failure path, not just a hypothetical success one; conn.close() after upgradeToTls has already settled (success
                // or failure) is a documented no-op, so closing both here is safe regardless of which step actually failed.
                val attempt: Span[Byte] < (Async & Abort[NetException | Closed]) =
                    Scope.run(
                        for
                            conn    <- transport.connect("127.0.0.1", listener.port).safe.get
                            _       <- Scope.ensure(Sync.defer(conn.close()))
                            _       <- conn.outbound.safe.put(upgradeRequest)
                            tlsConn <- transport.upgradeToTls(conn, cli, 16).safe.get
                            _       <- Scope.ensure(Sync.defer(tlsConn.close()))
                            _       <- tlsConn.outbound.safe.put(Span.from("x".getBytes))
                            r       <- tlsConn.inbound.safe.take
                        yield r
                    )
                val outcome: Result[NetException | Closed, Span[Byte]] < Async =
                    Abort.run[NetException | Closed](attempt)
                outcome.map { outcome =>
                    listener.close()
                    assert(outcome.isFailure, s"a STARTTLS upgrade against a non-TLS server must fail on this cell, got $outcome")
                }
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
                    _    <- Scope.ensure(Sync.defer(listener.close()))
                    conn <- transport.connect("127.0.0.1", listener.port).safe.get
                    // Safety net only: the deliberate `conn.close()` below is the assertion-relevant call that settles the first
                    // upgrade. This finalizer only fires if an earlier step (the signal put/take) fails before reaching it; once
                    // that close() has run, this is a documented no-op (Connection.scala: a close after the upgrade has settled).
                    _ <- Scope.ensure(Sync.defer(conn.close()))
                    _ <- conn.outbound.safe.put(upgradeRequest)
                    _ <- conn.inbound.safe.take
                    first = transport.upgradeToTls(conn, cli, 16).safe
                    second <- Abort.run[NetException | Closed](transport.upgradeToTls(conn, cli, 16).safe.get)
                    _ = conn.close()
                    firstOutcome <- Abort.run[NetException | Closed](first.get)
                yield
                    listener.close()
                    assert(
                        second.failure.exists(_.isInstanceOf[NetAlreadyDetachedException]),
                        s"a second upgradeToTls on an upgrading connection must fail NetAlreadyDetachedException, got $second"
                    )
                    // The second call must not have disarmed the first upgrade's close route: close() settles the still-parked first
                    // upgrade with the typed close leaf and releases what it holds. A stranded first upgrade hangs the leaf to its cap.
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
                    _       <- Scope.ensure(Sync.defer(listener.close()))
                    conn    <- transport.connect("127.0.0.1", listener.port).safe.get
                    _       <- Scope.ensure(Sync.defer(conn.close()))
                    _       <- conn.outbound.safe.put(upgradeRequest)
                    _       <- conn.inbound.safe.take
                    tlsConn <- transport.upgradeToTls(conn, cli, 16).safe.get
                    _       <- Scope.ensure(Sync.defer(tlsConn.close()))
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
                Scope.ensure(Sync.defer(listener.close())).andThen {
                    startTlsClient(transport, listener.port, cli, "hello-verified".getBytes("UTF-8")).map { echoed =>
                        listener.close()
                        assert(new String(echoed, "UTF-8") == "hello-verified")
                    }
                }
            }
    }

    "a STARTTLS upgrade by a verifying client with no reference identity (empty host) fails closed" - eachBackendTlsExcept {
        (transport, serverTls, clientTls) =>
            // A verifying client (trustAll = false) pins the cert as CA so the chain validates, but leaves sniHostname Absent, so upgradeToTls
            // drives the handshake with host = "", i.e. no reference identity. A chain-valid certificate with no bound name is never acceptable
            // (RFC 9525 6.1; CWE-295): the upgrade must fail closed on every cell.
            val verifyingClientNoSni = clientTls.copy(trustAll = false, caCertPath = serverTls.certChainPath, sniHostname = Absent)
            startTlsEchoServer(transport, serverTls).map { listener =>
                Scope.ensure(Sync.defer(listener.close())).andThen {
                    Abort.run[NetException | Closed](
                        startTlsClient(transport, listener.port, verifyingClientNoSni, "x".getBytes("UTF-8"))
                    ).map { outcome =>
                        listener.close()
                        assert(
                            outcome.isFailure,
                            s"a verifying STARTTLS client with an empty reference identity must fail closed (RFC 9525 6.1), got $outcome"
                        )
                    }
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
                    Scope.ensure(Sync.defer(listener.close())).andThen {
                        Abort.run[NetException | Closed](
                            startTlsClient(transport, listener.port, cli, "x".getBytes("UTF-8"))
                        ).map { outcome =>
                            listener.close()
                            assert(outcome.isFailure, s"a STARTTLS client verifying localhost must reject a wronghost cert, got $outcome")
                        }
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
                _       <- Scope.ensure(Sync.defer(listener.close()))
                conn    <- transport.connect("127.0.0.1", listener.port).safe.get
                _       <- Scope.ensure(Sync.defer(conn.close()))
                _       <- conn.outbound.safe.put(upgradeRequest)
                _       <- conn.inbound.safe.take
                tlsConn <- transport.upgradeToTls(conn, cli, 16).safe.get
                _       <- Scope.ensure(Sync.defer(tlsConn.close()))
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

    // A STARTTLS upgrade is the third handshake role, and it was the one with no deadline on any backend: an upgrade whose peer never sends a
    // ServerHello leaves the handshake parked on a read forever, holding the detached fd and the TLS engine. The plaintext connection has
    // already been detached by then, so nothing else reclaims them, and on the process-shared transport no later close() sweeps them either.
    //
    // The peer here accepts the plaintext connection and simply never upgrades, which is exactly a silent TLS peer from the upgrading side. On
    // the controlled clock the deadline is latched as armed, survives to one millisecond before its instant, and fires at it.
    "a STARTTLS upgrade whose peer never speaks TLS is reaped on its own deadline, not one tick before" - eachBackendTlsOnClock {
        (transport, tc, _, clientTls) =>
            val timeout = 150.millis
            for
                silentListener <- transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get
                _              <- Scope.ensure(Sync.defer(silentListener.close()))
                conn           <- transport.connect("127.0.0.1", silentListener.port, Duration.Infinity).safe.get
                _              <- Scope.ensure(Sync.defer(conn.close()))
                cli = clientTls.copy(sniHostname = Present("localhost"), handshakeTimeout = timeout)
                outcome <- Fiber.init(Abort.run[NetException](transport.upgradeToTls(conn, cli, 16).safe.get))
                _       <- tc.awaitPendingSleepers(1)
                _       <- tc.advance(timeout.minusOrZero(1.millis))
                _       <- tc.awaitPendingSleepers(1)
                _       <- tc.advance(1.millis)
                result  <- outcome.get
            yield result match
                case Result.Failure(e: NetTlsHandshakeTimeoutException) =>
                    assert(e.timeout == timeout, s"expected the upgrade's own $timeout deadline, got ${e.timeout}")
                case other =>
                    fail(s"expected the upgrade handshake deadline to fire, got $other")
            end for
    }

    /** A server that upgrades only AFTER the peer's first TLS flight (the ClientHello) has already landed in the plaintext inbound channel, so the
      * upgrade's replay path (preRead) is guaranteed non-empty.
      */
    private def startTlsEchoServerAfterStaged(transport: Transport, serverTls: NetTlsConfig)(using
        Frame,
        kyo.test.AssertScope
    ): Listener < (Async & Abort[NetException]) =
        transport.listen("127.0.0.1", 0, 128) { serverConn =>
            discard(Sync.Unsafe.evalOrThrow {
                Fiber.initUnscoped {
                    Abort.run[Closed | NetException] {
                        serverConn.inbound.safe.take.flatMap { _ =>
                            serverConn.outbound.safe.put(upgradeReady).andThen {
                                // The detach must find the ClientHello already staged: an empty plaintext channel would exercise the ordinary
                                // upgrade path instead of the replay path this leaf covers. Taking it latches on its arrival; putting it back
                                // into the channel it left, now empty since the client sends nothing more until the server answers, stages it.
                                serverConn.inbound.safe.take.map { hello =>
                                    Sync.Unsafe.defer(serverConn.inbound.offer(hello)).map { staged =>
                                        assert(
                                            staged == Result.succeed(true),
                                            s"the ClientHello must be staged back into the plaintext channel: $staged"
                                        )
                                    }
                                }.andThen {
                                    transport.upgradeToTls(serverConn, serverTls, 16).safe.get.flatMap { tlsConn =>
                                        Loop.foreach {
                                            tlsConn.inbound.safe.take.flatMap { data =>
                                                tlsConn.outbound.safe.put(data).andThen(Loop.continue)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }.unit
                }
            })
        }.safe.get

    "a STARTTLS server upgrading after the peer's first flight is already staged still round-trips (afterDetach replay-drop regression)" -
        eachBackendTls {
            (transport, serverTls, clientTls) =>
                // No handshake deadline: a dropped replay strands the handshake and the leaf hangs to its cap, where a real-clock deadline
                // would turn a slow runner into a false failure.
                val srvCfg = serverTls.copy(handshakeTimeout = Duration.Infinity)
                val cli    = clientTls.copy(sniHostname = Present("localhost"), handshakeTimeout = Duration.Infinity)
                startTlsEchoServerAfterStaged(transport, srvCfg).map { listener =>
                    Scope.ensure(Sync.defer(listener.close())).andThen {
                        Abort.run[NetException | Closed](startTlsClient(
                            transport,
                            listener.port,
                            cli,
                            "staged-flight".getBytes("UTF-8")
                        )).map { r =>
                            listener.close()
                            r match
                                case Result.Success(echoed) =>
                                    assert(new String(echoed, "UTF-8") == "staged-flight")
                                case other =>
                                    fail(
                                        s"upgrade with a pre-staged ClientHello did not round-trip (afterDetach dropped the staged replay?): $other"
                                    )
                            end match
                        }
                    }
                }
        }

end TransportStartTlsTest
