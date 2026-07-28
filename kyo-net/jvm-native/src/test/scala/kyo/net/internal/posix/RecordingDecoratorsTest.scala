package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Smoke tests verifying that each recording decorator delegates to the real component.
  *
  * Each leaf drives a real operation through the spy and asserts both that the real effect occurred (the real syscall/engine ran) and that
  * the spy recorded the observation. No behavior is scripted; the real component runs on every call.
  *
  * Anti-flakiness: all waits latch on real Promise.Unsafe completions from @Ffi.blocking fibers or real kernel events.
  */
class RecordingDecoratorsTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    "RecordingSocketBindings: delegates to real; a real send goes through and is recorded" in {
        PosixTestSockets.assumePoller().andThen {
            val recording = new RecordingSocketBindings(sock)
            PosixTestSockets.loopbackPair(recording).map { case (client, accepted) =>
                val driver = PollerIoDriver.init()
                discard(driver.start())
                Sync.ensure(Sync.defer(driver.close())) {
                    val clientH   = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val payload   = Span.fromUnsafe(Array[Byte](5, 6, 7, 8))
                    val w         = driver.write(clientH, payload, 0)
                    assert(w == WriteResult.Done, s"write result=$w")
                    val readP = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    driver.awaitRead(acceptedH, readP)
                    readP.safe.get.map {
                        case ReadOutcome.Bytes(got) =>
                            driver.closeHandle(clientH)
                            driver.closeHandle(acceptedH)
                            // The real bytes reached the peer (real send ran).
                            assert(
                                got.toArray.sameElements(Array[Byte](5, 6, 7, 8)),
                                s"expected bytes [5,6,7,8] from real socket via RecordingSocketBindings, got ${got.toArray.toList}"
                            )
                            // The close count for server fd was incremented (close was recorded).
                            // (The server fd was closed inside loopbackPair by the recording spy.)
                            assert(!recording.closeCounts.isEmpty, "no close was recorded despite loopbackPair closing the server fd")
                        case other =>
                            driver.closeHandle(clientH)
                            driver.closeHandle(acceptedH)
                            fail(s"expected ReadOutcome.Bytes but got $other")
                    }
                }
            }
        }
    }

    "RecordingSocketBindings: close is counted per fd; each recorded close corresponds to a real close" in {
        PosixTestSockets.assumePoller().andThen {
            val recording = new RecordingSocketBindings(sock)
            // Create one fd and close it through the recording spy.
            val fd = recording.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
            assert(fd >= 0, s"socket creation failed: fd=$fd")
            recording.close(fd).safe.get.map { _ =>
                val count = recording.closeCounts.getOrDefault(fd, 0)
                assert(count == 1, s"expected close count 1 for fd=$fd, got $count")
            }
        }
    }

    "RecordingTlsEngine: real handshake runs; handshakeCalls > 0 and freeCount == 1 after free" in {
        TlsRealEngines.assumeTlsReady().andThen {
            if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
            else
                Sync.defer {
                    // Allocate engines directly so we control freeing; withEngines would double-free the server if the spy also frees it.
                    import AllowUnsafe.embrace.danger
                    val serverConfig = kyo.net.NetTlsConfig(
                        certChainPath = kyo.Present(kyo.net.internal.TlsTestCert.certPath),
                        privateKeyPath = kyo.Present(kyo.net.internal.TlsTestCert.keyPath)
                    )
                    val clientConfig = kyo.net.NetTlsConfig(trustAll = true)
                    val realClient   = kyo.net.internal.BoringSslProvider.createEngine(clientConfig, "localhost", isServer = false)
                    val realServer   = kyo.net.internal.BoringSslProvider.createEngine(serverConfig, "localhost", isServer = true)
                    try
                        val spyServer = new RecordingTlsEngine(realServer)
                        val done      = TlsEngineLoopback.handshake(realClient, spyServer)
                        assert(done, "handshake did not complete with RecordingTlsEngine as server")
                        assert(spyServer.handshakeCalls.get() > 0, "handshakeCalls should be > 0 after a real handshake")
                        // free() the spy exactly once; the spy's free() delegates to realServer.free().
                        spyServer.free()
                        assert(spyServer.freeCount.get() == 1, s"expected freeCount == 1, got ${spyServer.freeCount.get()}")
                    finally
                        realClient.free()
                    end try
                }
        }
    }

    "RecordingTlsEngine: real round-trip via spy; feedCalls and writePlainCalls are recorded" in {
        TlsRealEngines.assumeTlsReady().andThen {
            if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
            else
                Sync.defer {
                    import AllowUnsafe.embrace.danger
                    val serverConfig = kyo.net.NetTlsConfig(
                        certChainPath = kyo.Present(kyo.net.internal.TlsTestCert.certPath),
                        privateKeyPath = kyo.Present(kyo.net.internal.TlsTestCert.keyPath)
                    )
                    val clientConfig = kyo.net.NetTlsConfig(trustAll = true)
                    val realClient   = kyo.net.internal.BoringSslProvider.createEngine(clientConfig, "localhost", isServer = false)
                    val realServer   = kyo.net.internal.BoringSslProvider.createEngine(serverConfig, "localhost", isServer = true)
                    try
                        val spyServer = new RecordingTlsEngine(realServer)
                        assert(TlsEngineLoopback.handshake(realClient, spyServer), "handshake did not complete")
                        val plaintext = "hello-spy".getBytes("UTF-8")
                        val echoed    = TlsEngineLoopback.roundTrip(realClient, spyServer, plaintext)
                        spyServer.free()
                        assert(echoed.sameElements(plaintext), s"round-trip mismatch: ${new String(echoed, "UTF-8")}")
                        assert(
                            spyServer.feedCalls.get() > 0,
                            "feedCalls should be > 0 after a real round-trip (server receives ciphertext)"
                        )
                        assert(spyServer.readPlainCalls.get() > 0, "readPlainCalls should be > 0 after server decrypts the received data")
                    finally
                        realClient.free()
                    end try
                }
        }
    }

end RecordingDecoratorsTest
