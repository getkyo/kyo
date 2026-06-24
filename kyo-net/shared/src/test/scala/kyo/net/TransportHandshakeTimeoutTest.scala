package kyo.net

import kyo.*
import kyo.net.internal.tls.TlsProviderPlatform

/** Cross-backend server accept-handshake deadline (`TransportConfig.handshakeTimeout`, finding #7, CWE-400 slowloris), via the PUBLIC
  * `NetPlatform.transport(config)` factory so the SAME test runs against every backend: posix (JVM default + Native), the NIO floor and the
  * epoll driver (forced-backend CI legs), and Node (JS). It replaces the former per-backend handshake-timeout suites, which had to construct
  * each transport directly only because there was no public way to set the deadline.
  *
  * The deadline arms per accepted connection: a plaintext client completes the TCP accept but never sends a ClientHello, so the server
  * handshake parks; a finite `handshakeTimeout` reaps it (closing the accepted fd), which the client observes as its inbound terminating; the
  * default `Duration.Infinity` arms nothing. Reaps and disarms are observed through the public `Connection` surface with generous bounds, never
  * a sleep-as-synchronization (the one fixed sleep is a past-the-deadline survival window in the disarm leaf, not a settle).
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
            val serverTls = NetTlsConfig(certChainPath = Present(certPath), privateKeyPath = Present(keyPath))
            // A finite, short handshakeTimeout. A plaintext client completes the TCP accept but never sends a ClientHello, so the server
            // handshake parks; the deadline reaps it and closes the accepted fd. The await is bounded by a generous guard so a regression
            // (no reap, i.e. the deadline was not honored) fails rather than hangs.
            val transport = NetPlatform.transport(TransportConfig.default.copy(handshakeTimeout = 150.millis))
            transport.listen("127.0.0.1", 0, 16, serverTls) { _ => () }.safe.get.map { listener =>
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

    "repeatedly reaping stalled server TLS handshakes does not corrupt memory (issue #243 io_uring UAF regression guard)" in {
        assumeTls()
        given Frame = Frame.internal
        // Regression guard for #243: a stalled server TLS handshake parks the server in awaitReadCiphertext with an in-flight io_uring recv SQE
        // pointed at handle.readBuffer. When the finite handshakeTimeout fires, the deadline teardown MUST route the readBuffer free + engine free
        // through the driver's UAF-safe ioDriver.closeHandle (deferred until the recv CQE reaps) and force the recv to complete (shutdown), rather
        // than freeing the kernel-owned buffer and the engine directly. Before the fix this freed memory the kernel was still writing into (Invalid
        // write) and could feed a freed engine. The corruption is silent on most runs, so this loops the stall+reap cycle many times to make the
        // in-flight-recv-vs-free race reliable: under Valgrind / ASan on real io_uring the pre-fix code reports the UAF here; post-fix it is clean.
        // The behavioral assertion (every stalled handshake is reaped) also holds on the poller backends, where the path is already UAF-safe, so the
        // SAME loop is the cross-backend reap proof. Reaps are observed through the public Connection surface with a generous Async.timeout bound,
        // never a sleep-as-synchronization.
        TlsTestCertShared.writePems.map { case (certPath, keyPath) =>
            val serverTls = NetTlsConfig(certChainPath = Present(certPath), privateKeyPath = Present(keyPath))
            val transport = NetPlatform.transport(TransportConfig.default.copy(handshakeTimeout = 60.millis))
            // handshakeTimeout currently arms two deadlines from the one config field: the server accept-handshake reap AND an in-flight client
            // connect deadline. A 60ms reap deadline therefore also caps each client connect at 60ms, and under load the connect-readiness delivery
            // (poll park / completion drain) can exceed 60ms, firing a spurious NetConnectTimeoutException unrelated to the reap UAF this guard
            // exercises. The client connect needs no deadline here, so it runs on a separate default-config transport (handshakeTimeout Infinity, no
            // connect deadline); the server transport keeps the finite 60ms that drives the reap. The reap is still asserted 30 times below.
            val clientTransport = NetPlatform.transport(TransportConfig.default)
            transport.listen("127.0.0.1", 0, 64, serverTls) { _ => () }.safe.get.map { listener =>
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
            val serverTls = NetTlsConfig(certChainPath = Present(certPath), privateKeyPath = Present(keyPath))
            val clientTls = NetTlsConfig(trustAll = true, sniHostname = Present("localhost"))
            // A generous 1s deadline: the loopback handshake completes well under it (tens of ms), so the timer disarms. Sleeping 1.5s (past
            // the deadline) and then round-tripping proves a still-armed timer would have reaped the connection but did not.
            val transport = NetPlatform.transport(TransportConfig.default.copy(handshakeTimeout = 1.second))
            transport.listen("127.0.0.1", 0, 16, serverTls) { serverConn =>
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
                transport.connect("127.0.0.1", listener.port, clientTls).safe.get.map { client =>
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

    "the default (Infinity) handshakeTimeout arms no deadline on every backend" in {
        assumeTls()
        given Frame = Frame.internal
        TlsTestCertShared.writePems.map { case (certPath, keyPath) =>
            val serverTls = NetTlsConfig(certChainPath = Present(certPath), privateKeyPath = Present(keyPath))
            assert(TransportConfig.default.handshakeTimeout == Duration.Infinity)
            // No deadline armed: a stalled handshake parks and the server never closes, so the inbound take must NOT complete within the
            // window. The bounded Async.timeout must expire (Failure), proving no reap. The window is an Async suspension, not a thread block.
            val transport = NetPlatform.transport(TransportConfig.default)
            transport.listen("127.0.0.1", 0, 16, serverTls) { _ => () }.safe.get.map { listener =>
                transport.connect("127.0.0.1", listener.port).safe.get.map { client =>
                    Abort.run[Timeout](Async.timeout(500.millis)(Abort.run[Closed](client.inbound.safe.take))).map { outcome =>
                        client.close()
                        listener.close()
                        transport.close()
                        assert(
                            outcome.isFailure,
                            s"the default Infinity handshakeTimeout must arm no deadline, so a stalled handshake is not reaped, got $outcome"
                        )
                    }
                }
            }
        }
    }

end TransportHandshakeTimeoutTest
