package kyo.net

import kyo.*
import kyo.net.internal.TlsProviderPlatform

/** Cross-backend server accept-handshake deadline (`NetTlsConfig.handshakeTimeout`, CWE-400 slowloris), via the PUBLIC
  * `NetPlatform.ownedTransport()` factory so the SAME test runs against every backend: posix (JVM default + Native), the NIO floor and the
  * epoll driver (forced-backend CI legs), and Node (JS).
  *
  * The deadline arms per accepted connection: a plaintext client completes the TCP accept but never sends a ClientHello, so the server
  * handshake parks; a finite `handshakeTimeout` reaps it (closing the accepted fd), which the client observes as its inbound terminating.
  * Reaps and disarms are observed through the public `Connection` surface with generous bounds, never a sleep-as-synchronization (the one
  * fixed sleep is a past-the-deadline survival window in the disarm leaf, not a settle).
  */
class TransportHandshakeTimeoutTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumeTls(): Unit =
        if !TlsProviderPlatform.registered.exists(_.isAvailable) then cancel("no TLS provider available on this backend")

    /** Read exactly `target` bytes from a connection's inbound channel, concatenated. */
    private def collect(conn: Connection, target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= target then Loop.done(acc)
            else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
        }

    "a stalled server TLS handshake is reaped after the deadline on every backend" in {
        assumeTls()
        given Frame = Frame.internal
        TlsTestCertShared.writePems.map { case (certPath, keyPath) =>
            // A finite, short handshakeTimeout. A plaintext client completes the TCP accept but never sends a ClientHello, so the server
            // handshake parks; the deadline reaps it and closes the accepted fd. The await is bounded by a generous guard so a regression
            // (no reap, i.e. the deadline was not honored) fails rather than hangs.
            val serverTls =
                NetTlsConfig(certChainPath = Present(certPath), privateKeyPath = Present(keyPath), handshakeTimeout = 150.millis)
            val transport = NetPlatform.ownedTransport()
            transport.listenTls("127.0.0.1", 0, 16, serverTls) { _ => () }.safe.get.map { listener =>
                transport.connect("127.0.0.1", listener.port).safe.get.map { client =>
                    Abort.run[Timeout](Async.timeout(5.seconds)(Abort.run[Closed](client.inbound.safe.take))).map { outcome =>
                        client.close()
                        listener.close()
                        transport.close()
                        // The reap closes the accepted fd; the client's inbound either fails Closed (channel torn down) or delivers an empty
                        // EOF span, depending on backend. Both are reaps; a Timeout (the window expired) means no reap, the regression symptom.
                        val reaped = outcome match
                            case Result.Success(Result.Success(span)) => span.isEmpty
                            case Result.Success(Result.Failure(_))    => true
                            case _                                    => false
                        assert(reaped, s"expected the finite handshakeTimeout to reap the stalled server handshake, got $outcome")
                    }
                }
            }
        }
    }

    "repeatedly reaping stalled server TLS handshakes does not corrupt memory (io_uring UAF regression guard)" in {
        assumeTls()
        given Frame = Frame.internal
        // Regression guard: a stalled server TLS handshake parks the server in awaitReadCiphertext with an in-flight io_uring recv SQE
        // pointed at handle.readBuffer. When the finite handshakeTimeout fires, the deadline teardown MUST route the readBuffer free + engine free
        // through the driver's UAF-safe ioDriver.closeHandle (deferred until the recv CQE reaps) and force the recv to complete (shutdown), rather
        // than freeing the kernel-owned buffer and the engine directly. Freeing them directly would free memory the kernel is still writing into (Invalid
        // write) and could feed a freed engine. The corruption is silent on most runs, so this loops the stall+reap cycle many times to make the
        // in-flight-recv-vs-free race reliable: under Valgrind / ASan on real io_uring an unsafe teardown reports the UAF here; the deferred teardown is clean.
        // The behavioral assertion (every stalled handshake is reaped) also holds on the poller backends, where the path is already UAF-safe, so the
        // SAME loop is the cross-backend reap proof. Reaps are observed through the public Connection surface with a generous Async.timeout bound,
        // never a sleep-as-synchronization.
        TlsTestCertShared.writePems.map { case (certPath, keyPath) =>
            val serverTls =
                NetTlsConfig(certChainPath = Present(certPath), privateKeyPath = Present(keyPath), handshakeTimeout = 60.millis)
            val transport = NetPlatform.ownedTransport()
            // handshakeTimeout arms ONLY the server accept-handshake reap; a client connect is bounded by the connect operation's own connectTimeout.
            // A 60ms reap deadline is tight enough that under load the loopback connect-readiness delivery can race it and fire a spurious
            // NetConnectTimeoutException unrelated to the reap UAF this guard exercises. The client transport therefore uses a generous
            // connectTimeout (the 30s default) so the loopback connect completes well before any connect deadline, while the server transport
            // keeps the finite 60ms handshakeTimeout that drives the reap. The reap is still asserted 30 times below.
            val clientTransport = NetPlatform.ownedTransport()
            transport.listenTls("127.0.0.1", 0, 64, serverTls) { _ => () }.safe.get.map { listener =>
                // Each iteration: a plaintext client completes the TCP accept but never sends a ClientHello, so the server handshake parks with an
                // in-flight recv; the 60ms deadline reaps it (closing the accepted fd), which the client observes as its inbound terminating. A
                // Timeout (the 5s window expired with no reap) is the regression symptom (the deadline was not honored); a Closed or empty-EOF span
                // is a reap. The clients run sequentially so each stall+reap is a clean, isolated cycle that exercises the teardown path once.
                Loop(0) { i =>
                    if i >= 30 then Loop.done(i)
                    else
                        clientTransport.connect("127.0.0.1", listener.port).safe.get.map { client =>
                            Abort.run[Timeout](Async.timeout(5.seconds)(Abort.run[Closed](client.inbound.safe.take))).map { outcome =>
                                client.close()
                                val reaped = outcome match
                                    case Result.Success(Result.Success(span)) => span.isEmpty
                                    case Result.Success(Result.Failure(_))    => true
                                    case _                                    => false
                                assert(
                                    reaped,
                                    s"iteration $i: expected the finite handshakeTimeout to reap the stalled handshake, got $outcome"
                                )
                                Loop.continue(i + 1)
                            }
                        }
                }.map { n =>
                    listener.close()
                    clientTransport.close()
                    transport.close()
                    assert(n == 30, s"expected 30 stall+reap cycles, completed $n")
                }
            }
        }
    }

    "a handshake that completes within the deadline is NOT reaped on every backend" in {
        assumeTls()
        given Frame = Frame.internal
        TlsTestCertShared.writePems.map { case (certPath, keyPath) =>
            // A generous 1s deadline: the loopback handshake completes well under it (tens of ms), so the timer disarms. Sleeping 1.5s (past
            // the deadline) and then round-tripping proves a still-armed timer would have reaped the connection but did not.
            val serverTls =
                NetTlsConfig(certChainPath = Present(certPath), privateKeyPath = Present(keyPath), handshakeTimeout = 1.second)
            val clientTls = NetTlsConfig(trustAll = true, sniHostname = Present("localhost"))
            val transport = NetPlatform.ownedTransport()
            transport.listenTls("127.0.0.1", 0, 16, serverTls) { serverConn =>
                discard(Sync.Unsafe.evalOrThrow {
                    Fiber.initUnscoped {
                        Abort.run[Closed] {
                            Loop.foreach {
                                serverConn.inbound.safe.take.map(chunk => serverConn.outbound.safe.put(chunk).andThen(Loop.continue))
                            }
                        }.unit
                    }
                })
            }.safe.get.map { listener =>
                transport.connectTls("127.0.0.1", listener.port, clientTls).safe.get.map { client =>
                    Async.sleep(1500.millis).andThen {
                        val message = "completes-within-deadline".getBytes("UTF-8")
                        client.outbound.safe.put(Span.fromUnsafe(message)).andThen {
                            collect(client, message.length).map { echoed =>
                                client.close()
                                listener.close()
                                transport.close()
                                assert(
                                    echoed.sameElements(message),
                                    s"a completed handshake must not be reaped; it round-trips past the deadline, got '${new String(echoed, "UTF-8")}'"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    "a stalled server TLS handshake is not reaped when handshakeTimeout is larger than the observation window" in {
        assumeTls()
        given Frame = Frame.internal
        TlsTestCertShared.writePems.map { case (certPath, keyPath) =>
            val serverTls = NetTlsConfig(certChainPath = Present(certPath), privateKeyPath = Present(keyPath))
            // The default handshakeTimeout is 30s (finite, but much larger than the 500ms observation window). A stalled handshake will not be
            // reaped within 500ms, so the inbound take must NOT complete within the window. The bounded Async.timeout must expire (Failure),
            // proving no early reap. The window is an Async suspension, not a thread block.
            assert(NetTlsConfig.default.handshakeTimeout == 30.seconds)
            val transport = NetPlatform.ownedTransport()
            transport.listenTls("127.0.0.1", 0, 16, serverTls) { _ => () }.safe.get.map { listener =>
                transport.connect("127.0.0.1", listener.port).safe.get.map { client =>
                    Abort.run[Timeout](Async.timeout(500.millis)(Abort.run[Closed](client.inbound.safe.take))).map { outcome =>
                        client.close()
                        listener.close()
                        transport.close()
                        assert(
                            outcome.isFailure,
                            s"a stalled handshake must not be reaped within 500ms when handshakeTimeout is 30s, got $outcome"
                        )
                    }
                }
            }
        }
    }

    "a stalled server TLS handshake is not reaped when handshakeTimeout is Infinity (no timer armed)" in {
        assumeTls()
        given Frame = Frame.internal
        TlsTestCertShared.writePems.map { case (certPath, keyPath) =>
            // With handshakeTimeout = Infinity the transport arms NO timer at all: a stalled handshake parks forever and is not reaped. A
            // bounded observation window (an Async suspension, not a thread block) is the no-reap ceiling; the Async.timeout must expire,
            // proving the inbound did not complete within the window. This is stronger than a finite-but-large default: it asserts the
            // Infinity code path arms nothing, not just that a 30s timer does not fire within 500ms.
            val serverTls = NetTlsConfig(
                certChainPath = Present(certPath),
                privateKeyPath = Present(keyPath),
                handshakeTimeout = Duration.Infinity
            )
            val transport = NetPlatform.ownedTransport()
            transport.listenTls("127.0.0.1", 0, 16, serverTls) { _ => () }.safe.get.map { listener =>
                transport.connect("127.0.0.1", listener.port).safe.get.map { client =>
                    Abort.run[Timeout](Async.timeout(500.millis)(Abort.run[Closed](client.inbound.safe.take))).map { outcome =>
                        client.close()
                        listener.close()
                        transport.close()
                        assert(
                            outcome.isFailure,
                            s"a stalled handshake must not be reaped when handshakeTimeout is Infinity (no timer is armed), got $outcome"
                        )
                    }
                }
            }
        }
    }

    // The CLIENT side of the same guard. A peer that completes the TCP connect and then never speaks TLS leaves the client handshake parked on
    // a read that never arrives, holding the fd and the TLS engine. On the process-shared transport nothing later reclaims them, so without a
    // deadline this is a permanent leak, not merely a slow connect. Reproduced with a plaintext listener: it accepts the TCP connection and
    // sends nothing, which is exactly a silent TLS server from the client's point of view.
    //
    // The outer Async.timeout is the regression detector, not the mechanism under test: if no deadline is armed the connect parks and the outer
    // window expires with a Timeout, which fails the assertion below rather than hanging the suite.
    "a client TLS handshake that stalls is reaped on its own deadline" in {
        assumeTls()
        given Frame   = Frame.internal
        val transport = NetPlatform.ownedTransport()
        transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get.map { silentListener =>
            val clientTls = NetTlsConfig(trustAll = true, sniHostname = Present("localhost"), handshakeTimeout = 150.millis)
            Abort.run[NetException | Closed | Timeout](
                Async.timeout(5.seconds)(transport.connectTls("127.0.0.1", silentListener.port, clientTls).safe.get)
            ).map { outcome =>
                silentListener.close()
                discard(transport.close())
                outcome match
                    case Result.Failure(e: NetTlsHandshakeTimeoutException) =>
                        assert(e.timeout == 150.millis, s"expected the client's own 150ms deadline, got ${e.timeout}")
                    case other =>
                        assert(
                            false,
                            s"expected NetTlsHandshakeTimeoutException(150ms) from the client handshake deadline, got $other " +
                                "(a Timeout means no deadline was armed on the connect path, so the fd and engine leak)"
                        )
                end match
            }
        }
    }

end TransportHandshakeTimeoutTest
