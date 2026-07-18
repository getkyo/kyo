package kyo.net.internal

import kyo.*
import kyo.net.Test

/** Smoke tests for [[TlsRealEngines]].
  *
  * Exercises the real engine factories and the realTlsLoopback helper. Each leaf is gated on assumeTlsReady so it cancels cleanly where no
  * TLS provider is staged.
  *
  * Anti-flakiness: engine operations are synchronous (no I/O waits); realTlsLoopback latches on real Fiber.Unsafe completions from the
  * kernel accept.
  */
class TlsRealEnginesTest extends Test:

    import AllowUnsafe.embrace.danger

    "withEngines: real BoringSSL handshake completes and round-trip is byte-equal" in {
        TlsRealEngines.assumeTlsReady().andThen {
            if !TlsRealEngines.boringSslAvailable() then
                cancel("BoringSSL not staged for this host")
            else
                Sync.defer {
                    TlsRealEngines.withEngines { (client, server) =>
                        val done = TlsEngineLoopback.handshake(client, server)
                        assert(done, "BoringSSL handshake did not complete on both sides")
                        val plaintext = "hello-tls".getBytes("UTF-8")
                        val echoed    = TlsEngineLoopback.roundTrip(client, server, plaintext)
                        assert(echoed.sameElements(plaintext), s"plaintext round-trip mismatch: got ${new String(echoed, "UTF-8")}")
                    }
                }
        }
    }

    "realTlsLoopback: real TLS handshake over a real loopback socket succeeds" in {
        TlsRealEngines.assumeTlsReady().andThen {
            TlsRealEngines.realTlsLoopback(kyo.net.TransportConfig.default) { (clientConn, serverConn) =>
                // Connections are established with a full TLS handshake; just verify both ends are open.
                assert(clientConn.isOpen, "client connection is not open after TLS handshake")
                assert(serverConn.isOpen, "server connection is not open after TLS handshake")
                succeed
            }
        }
    }

end TlsRealEnginesTest
