package kyo.net.internal

import kyo.*
import kyo.net.NetConfig
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.internal.posix.PosixConstants

/** The TLS handshake terminates on jvm-native posix backends.
  *
  * Two scenarios drive the real network path end to end:
  *
  *   - **responsive**: a real BoringSSL or OpenSSL client and server complete the TLS handshake over a loopback socket; both connections are
  *     open when the body runs. Exercises `driveHandshake -> onFinished -> spawnHandler` and the TLS engine attach in [[PosixTransport]].
  *     Uses [[TlsRealEngines.realTlsLoopback]], which returns only after BOTH sides reach `HandshakeState.Done`.
  *
  *   - **stalled**: a raw TCP client (no TLS) connects to a TLS server configured with a finite `handshakeTimeout` (150ms). The client sends
  *     no ClientHello, so the server parks in `WantRead`. At 150ms of the transport's controlled clock the deadline fires:
  *     `armHandshakeDeadline` runs `teardown()` on the engine FIFO worker, closes the server fd, and the raw TCP client observes its inbound
  *     terminating (empty span or Closed). A missing reap hangs the leaf to its cap.
  *
  * Both scenarios are gated on [[assumeTlsAndPoller]], which cancels the leaf when no TLS provider is staged or when the host lacks
  * epoll/kqueue (required by [[PollerIoDriver]]).
  */
class PosixTransportHandshakeLivenessTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumeTlsAndPoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PollerIoDriver needs epoll (Linux) or kqueue (macOS/BSD)")
        if !TlsRealEngines.boringSslAvailable() && !TlsRealEngines.openSslAvailable() then
            cancel("No TLS provider staged for this host")
    end assumeTlsAndPoller

    "handshake liveness" - {
        "handshake-terminates" - {

            // A real TLS handshake between a BoringSSL (or OpenSSL) client and server over a loopback
            // socket completes to Done on both sides. realTlsLoopback returns only after BOTH sides
            // complete the handshake; the body runs with both connections open.
            "responsive: real TLS handshake completes and both connections are open" in {
                assumeTlsAndPoller()
                given Frame = Frame.internal
                TlsRealEngines.realTlsLoopback(NetConfig.default) { (clientConn, serverConn) =>
                    assert(clientConn.isOpen, "client connection must be open after handshake Done")
                    assert(serverConn.isOpen, "server connection must be open after handshake Done")
                    succeed
                }
            }

            // A raw TCP client connects to a TLS server with handshakeTimeout=150ms but sends no ClientHello. The server parks in WantRead;
            // at 150ms the deadline fires teardown(), closing the server fd, and the client's inbound ends (empty span or Closed). The handler
            // is never invoked: it runs only on a successful handshake via onFinished, and the deadline path skips it.
            "stalled: the deadline reaps a TCP-only client that sends no ClientHello, at its instant" - eachBackendOnClock {
                (transport, tc) =>
                    assumeTlsAndPoller()
                    val timeout   = 150.millis
                    val serverTls = NetTlsConfig(
                        certChainPath = Present(TlsTestCert.certPath),
                        privateKeyPath = Present(TlsTestCert.keyPath),
                        handshakeTimeout = timeout
                    )
                    for
                        listener <- transport.listenTls("127.0.0.1", 0, 16, serverTls)(_ => ()).safe.get
                        _        <- Scope.ensure(Sync.defer(listener.close()))
                        client   <- transport.connect("127.0.0.1", listener.port, Duration.Infinity).safe.get
                        _        <- Scope.ensure(Sync.defer(client.close()))
                        _        <- tc.awaitPendingSleepers(1)
                        _        <- tc.advance(timeout.minusOrZero(1.millis))
                        _        <- tc.awaitPendingSleepers(1)
                        _        <- tc.advance(1.millis)
                        outcome  <- Abort.run[Closed](client.inbound.safe.take)
                    yield
                        val reaped = outcome match
                            case Result.Success(span) => span.isEmpty
                            case Result.Failure(_)    => true
                            case _                    => false
                        assert(reaped, s"stalled: the deadline must reap the handshake at $timeout, got $outcome")
                    end for
            }
        }
    }

end PosixTransportHandshakeLivenessTest
