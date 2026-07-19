package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.TransportConfig
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.TlsTestCert

/** Reproduction + regression guard (Netty #6611, RFC 5246 7.2 / RFC 8446 6.2): when a TLS handshake FAILS, the fatal
  * alert the engine queued (a `protocol_version` / `bad_certificate` / `handshake_failure` record) must be drained and SENT to the peer before the
  * fd is closed, so the peer learns the real failure reason instead of seeing a bare connection reset.
  *
  * driveHandshake runs handshakeStep then, on every outcome EXCEPT the fatal one, drains the engine's outbound ciphertext and sends it
  * (drainAllDirect): the done arm (`1`), the want-write arm (`-1`), and the want-read arm (`0`) all drain. The fatal arm (`-2`) called `onFailed`
  * directly with no drain, so the queued alert would be dropped and the peer would only ever see the fd close. The `-2` arm drains + sends the
  * queued alert before failing.
  *
  * This drives the exact path with real components: a server pinned to TLS 1.3 only accepts a real connection from a client pinned to TLS 1.2 only.
  * The server's real BoringSSL/OpenSSL engine rejects the lower-version ClientHello, queues a `protocol_version` fatal alert, and returns the fatal
  * outcome (`-2`). The server drains + sends that alert; the client's engine consumes it and the client `connect` fails with a TLS
  * handshake failure ("TLS handshake with <host>:<port> failed", it received and processed the server's alert). A bare-close server would instead
  * make the client read a bare EOF mid-handshake, with the failure cause "peer closed during read" (the dropped-alert symptom). The
  * assertion distinguishes the two: a TLS handshake failure whose cause is NOT "peer closed during read".
  *
  * Gated on a real poller (epoll/kqueue) and a staged TLS provider. A positive control (matching versions complete + round-trip) pins that the
  * version pinning, not a broken setup, is the cause of the negative leaf's failure.
  */
class PosixTransportHandshakeAlertTest extends Test:

    import AllowUnsafe.embrace.danger

    private val transportConfig = TransportConfig.default

    private def assumePollerReady(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PosixTransport TLS handshake tests need epoll (Linux) or kqueue (macOS/BSD)")

    /** Build a transport over a fresh real poller driver, run `body`, then close the transport and the driver.
      *
      * Awaits the driver's own poll-loop-exit fiber after `close()` (rather than discarding it) so the underlying thread and listener socket
      * are provably gone before this computation completes: `close()` itself only requests teardown (`submitEngineOp` + `triggerWake()`) and
      * returns immediately, without waiting for the poll-loop carrier to actually run it. Discarding the exit fiber left a window, under
      * kyo-test's concurrent leaf scheduling, where a not-yet-fully-torn-down driver from an earlier leaf could still be alive
      * when the next leaf's own transport starts.
      */
    private def withTransport[A](body: PosixTransport => A < (Async & Abort[NetException | Closed] & Scope))(using
        Frame
    ): A < (Async & Abort[NetException | Closed] & Scope) =
        val driver     = PollerIoDriver.init(transportConfig)
        val transport  = TestTransports.forTesting(transportConfig, driver, Ffi.load[SocketBindings], backendIsEpoll = false)
        val driverDone = driver.start()
        Abort.run[NetException | Closed](body(transport)).map { result =>
            Sync.defer(transport.close()).andThen(Sync.defer(driver.close())).andThen(
                Abort.run(driverDone.safe.get).unit
            ).andThen(Abort.get(result))
        }
    end withTransport

    private def serverTls(min: NetTlsConfig.Version, max: NetTlsConfig.Version): NetTlsConfig =
        NetTlsConfig(
            certChainPath = Present(TlsTestCert.certPath),
            privateKeyPath = Present(TlsTestCert.keyPath),
            minVersion = min,
            maxVersion = max
        )

    private def clientTls(min: NetTlsConfig.Version, max: NetTlsConfig.Version): NetTlsConfig =
        NetTlsConfig(trustAll = true, minVersion = min, maxVersion = max)

    "PosixTransport server TLS handshake failure" - {

        "a version-mismatch failure sends the engine's fatal alert to the peer before closing the fd" in {
            assumePollerReady()
            TlsRealEngines.assumeTlsReady()
            withTransport { transport =>
                import NetTlsConfig.Version.*
                // Server accepts only TLS 1.3; the client offers only TLS 1.2, so the server's real engine rejects the ClientHello, queues a
                // protocol_version fatal alert, and the accept handshake fails on the fatal (`-2`) arm.
                transport.listen("127.0.0.1", 0, 16, serverTls(TLS13, TLS13)) { _ => () }.safe.get.map { listener =>
                    Abort.run[NetException | Closed](transport.connect("127.0.0.1", listener.port, clientTls(TLS12, TLS12)).safe.get).map {
                        outcome =>
                            val message = outcome match
                                case Result.Failure(e) => e.getMessage
                                case other             => fail(s"expected the version-mismatch handshake to fail, got $other")
                            // The server drains + sends its fatal alert before close; the client's engine consumes it and the connect fails with the
                            // engine-level handshake failure (a NetTlsHandshakeException, "TLS handshake with <host>:<port> failed[: <cause>]"). A
                            // bare-close server would instead make the client read a bare EOF mid-handshake, with the failure cause "peer closed during
                            // read": the dropped-alert symptom. So the failure must be a TLS-handshake failure that is NOT a bare-EOF close.
                            assert(
                                message.contains("TLS handshake with"),
                                s"expected a TLS handshake failure (the client received and processed the server's fatal alert), got: $message"
                            )
                            assert(
                                !message.contains("peer closed during read"),
                                s"the client received a bare close, not the server's fatal alert (the alert was dropped before fd close): $message"
                            )
                    }
                }
            }
        }

        "matching versions complete and round-trip (positive control)" in {
            assumePollerReady()
            TlsRealEngines.assumeTlsReady()
            import NetTlsConfig.Version.*
            withTransport { transport =>
                transport.listen("127.0.0.1", 0, 16, serverTls(TLS12, TLS13)) { serverConn =>
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
                }.safe.get.map { listener =>
                    transport.connect("127.0.0.1", listener.port, clientTls(TLS12, TLS13)).safe.get.map { client =>
                        val message = "version-overlap-roundtrip".getBytes("UTF-8")
                        client.outbound.safe.put(Span.fromUnsafe(message)).andThen {
                            Loop(Array.emptyByteArray) { acc =>
                                if acc.length >= message.length then Loop.done(acc)
                                else client.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
                            }.map { echoed =>
                                client.close()
                                assert(
                                    echoed.sameElements(message),
                                    s"matching-version handshake must complete and round-trip, got '${new String(echoed, "UTF-8")}'"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

end PosixTransportHandshakeAlertTest
