package kyo.net.internal

import kyo.*
import kyo.net.NetConfig
import kyo.net.NetPlatform
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
  *     no ClientHello, so the server parks in `WantRead`. After 150ms the deadline fires: `armHandshakeDeadline` runs `teardown()` on the
  *     engine FIFO worker, closes the server fd, and the raw TCP client observes its inbound terminating (empty span or Closed) within a 5s
  *     window. A Timeout (5s window expired without closure) means the deadline was not honored and is the regression symptom.
  *
  * Both scenarios are gated on [[assumeTlsAndPoller]], which cancels the leaf when no TLS provider is staged or when the host lacks
  * epoll/kqueue (required by [[PollerIoDriver]]).
  *
  * Anti-flakiness: no Thread.sleep. The responsive leaf latches on the real kernel accept (fiber completion inside
  * [[TlsRealEngines.realTlsLoopback]]). The stalled leaf uses [[Async.timeout]](5.seconds) as an upper bound so a missed reap fails rather
  * than hanging; 5s >> 150ms handshakeTimeout on any loopback host.
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

            // A raw TCP client connects to a TLS server with handshakeTimeout=150ms but sends no
            // ClientHello. The server parks in WantRead; after 150ms the deadline fires teardown(),
            // closing the server fd. The client observes its inbound ending (empty span or Closed)
            // within the 5s window. Timeout means no reap (regression). The handler is never invoked
            // (it runs only on a successful handshake via onFinished; the deadline path skips it).
            "stalled: deadline reaps a TCP-only client that sends no ClientHello" in {
                assumeTlsAndPoller()
                given Frame = Frame.internal
                val serverTls = NetTlsConfig(
                    certChainPath = Present(TlsTestCert.certPath),
                    privateKeyPath = Present(TlsTestCert.keyPath)
                )
                // Short deadline: a client that stalls the TLS handshake must be reaped within 150ms.
                val transport = NetPlatform.transport
                transport.listenTls("127.0.0.1", 0, 16, serverTls.copy(handshakeTimeout = 150.millis)) { _ => () }.safe.get.map {
                    listener =>
                        // Plain TCP connect (no TLS): the client completes the TCP handshake but never
                        // sends a ClientHello. The server driveHandshake stays in WantRead until teardown.
                        transport.connect("127.0.0.1", listener.port).safe.get.map { client =>
                            Abort.run[Timeout](Async.timeout(5.seconds)(Abort.run[Closed](client.inbound.safe.take))).map {
                                outcome =>
                                    client.close()
                                    listener.close()
                                    // Reap: inbound ends with an empty EOF span or a Closed abort.
                                    // Timeout (the 5s window expired with no reap) is the regression symptom.
                                    val reaped = outcome match
                                        case Result.Success(Result.Success(span)) => span.isEmpty
                                        case Result.Success(Result.Failure(_))    => true
                                        case _                                    => false
                                    assert(
                                        reaped,
                                        s"stalled: deadline must reap the handshake within 5s (handshakeTimeout=150ms), got $outcome"
                                    )
                            }
                        }
                }
            }
        }
    }

end PosixTransportHandshakeLivenessTest
