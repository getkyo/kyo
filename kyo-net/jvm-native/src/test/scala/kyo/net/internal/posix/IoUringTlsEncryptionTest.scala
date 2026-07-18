package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Guards the TLS-over-io_uring parity invariant on a REAL io_uring ring with a REAL BoringSSL engine: a TLS handle served by the
  * [[IoUringDriver]] must encrypt outbound plaintext through the engine before it reaches the wire, and decrypt inbound ciphertext through
  * the engine before it reaches the application. This is the regression guard for the plaintext-leak bug (the io_uring write/read path that
  * once sent raw bytes without consulting `handle.tls`).
  *
  * Both leaves run over a real loopback socket pair, a real ring through a [[RecordingIoUringBindings]] spy (every ring op delegates to the
  * kernel; the spy only records the send buffers and fires a reap latch), and a real BoringSSL client+server engine pair completed via an
  * in-memory handshake. The server engine is attached to the driver handle; the client engine is the peer, decrypting the write leaf's wire
  * bytes and encrypting the read leaf's wire bytes. The "ciphertext is distinguishable from plaintext" property a reversible fake gave for free
  * is now established by DECRYPT-AND-COMPARE: the bytes captured on the wire are NOT the plaintext, and decrypt back to it (real ciphertext is
  * never equal to its plaintext for a non-trivial payload, so the != assertion catches a leak just as a reversible XOR did).
  *
  * Every leaf is gated by [[PosixTestSockets.assumeUring]]: it cancels cleanly off Linux or where the production-depth ring cannot init, and
  * runs the real ring on native Linux.
  *
  * Anti-flakiness: the write leaf awaits the send CQE reap latch (the bytes are on the wire once it fires) and a FIFO barrier (the encrypt
  * engine op has run); the read leaf awaits the read promise (completed only when the recv CQE reaps and the decrypt engine op runs). No sleep
  * or poll-retry.
  */
class IoUringTlsEncryptionTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Allocate a real io_uring ring at production depth, wrap it in a recording spy, build a driver, run `body`, then close the driver. */
    private def withRecordingDriver[A](
        body: (IoUringDriver, RecordingIoUringBindings) => A < (Abort[Closed] & Async)
    )(using Frame): A < (Abort[Closed] & Async) =
        val depth     = math.max(256, kyo.net.TransportConfig.default.ioPoolSize * 64)
        val realUring = Ffi.load[IoUringBindings]
        val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
        val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
        if rc != 0 then
            realRing.close()
            throw Closed("RecordingIoUringBindings", summon[Frame], s"queue_init failed: rc=$rc")
        val recording = RecordingIoUringBindings(realUring, realRing)
        val driver    = TestDrivers.forBindings(recording, realRing)
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(body(driver, recording))
    end withRecordingDriver

    /** Submit a marker engine op and return a promise that completes once the FIFO worker runs it, proving every engine op submitted before it
      * (the write's encrypt) has run.
      */
    private def fifoBarrier(drv: IoUringDriver): Promise.Unsafe[Unit, Any] =
        val p = Promise.Unsafe.init[Unit, Any]()
        drv.submitEngineOp(() => p.completeDiscard(Result.succeed(())))
        p
    end fifoBarrier

    /** Read whatever bytes are currently available on `fd` without blocking, returning them as an array (empty when none). */
    private def recvAvailable(fd: Int): Array[Byte] =
        val buf = Buffer.alloc[Byte](65536)
        try
            val r = sock.recvNow(fd, buf, 65536L, PosixConstants.MSG_DONTWAIT)
            val n = r.value.toInt
            if n > 0 then Buffer.copyToArray[Byte](buf, 0, n) else Array.emptyByteArray
        finally buf.close()
        end try
    end recvAvailable

    "IoUringDriver TLS encryption (real ring, real engine)" - {

        "write on a TLS handle encrypts via the engine; the wire bytes are ciphertext that decrypts back to the plaintext" in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "handshake must complete before the write")
                withRecordingDriver { (drv, recording) =>
                    PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                        val recordingServer = RecordingTlsEngine(serverEngine)
                        val handle          = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent)
                        handle.tls = Present(recordingServer)

                        val plaintext = Array.tabulate[Byte](16)(i => (i + 1).toByte)
                        val reaped    = recording.awaitReap()
                        val w         = drv.write(handle, Span.fromUnsafe(plaintext), 0)
                        assert(w == WriteResult.Done, s"write result=$w")

                        // The encrypt engine op has run once the FIFO barrier fires; the send CQE has reaped (bytes on the wire) once the reap
                        // latch fires. Then read the actual wire image off the peer socket and decrypt it with the peer engine.
                        fifoBarrier(drv).safe.get.andThen(reaped.safe.get).map { _ =>
                            val onWire = recvAvailable(peerFd)
                            handle.tls =
                                Absent // detach so closeHandle frees the per-handle buffers but not the engine (withEngines frees it)
                            drv.closeHandle(handle)
                            discard(sock.close(peerFd))
                            // (1) The engine was driven: writePlain ran at least once.
                            assert(
                                recordingServer.writePlainCalls.get() > 0,
                                "the TLS engine's writePlain was not invoked; plaintext was not encrypted"
                            )
                            // (2) The bytes on the wire are NOT the raw plaintext (no leak).
                            assert(onWire.nonEmpty, "no bytes reached the peer socket")
                            assert(onWire.toList != plaintext.toList, s"raw plaintext reached the wire unencrypted: ${onWire.toList}")
                            // (3) Decrypt-and-compare: the wire ciphertext decrypts back to exactly the plaintext.
                            val decrypted = TlsEngineLoopback.decrypt(clientEngine, onWire)
                            assert(
                                decrypted.toList == plaintext.toList,
                                s"wire ciphertext did not decrypt to the plaintext: got ${decrypted.toList}"
                            )
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "read on a TLS handle decrypts via the engine; the application receives plaintext, never the raw ciphertext" in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "handshake must complete before the read")
                withRecordingDriver { (drv, _) =>
                    PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                        val recordingServer = RecordingTlsEngine(serverEngine)
                        val handle          = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent)
                        handle.tls = Present(recordingServer)

                        val plaintext = Array.tabulate[Byte](16)(i => (i + 10).toByte)
                        // The peer encrypts the plaintext into a real TLS record and sends it on the wire.
                        val ciphertext = TlsEngineLoopback.encrypt(clientEngine, plaintext)
                        assert(ciphertext.nonEmpty, "the peer produced no ciphertext")
                        assert(ciphertext.toList != plaintext.toList, "the peer ciphertext must differ from the plaintext")
                        assert(sock.sendNow(
                            peerFd,
                            Buffer.fromArray[Byte](ciphertext),
                            ciphertext.length.toLong,
                            0
                        ).value == ciphertext.length.toLong)

                        val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        drv.awaitRead(handle, promise)
                        promise.safe.get.map {
                            case ReadOutcome.Bytes(got) =>
                                handle.tls = Absent
                                drv.closeHandle(handle)
                                discard(sock.close(peerFd))
                                // (1) The engine was fed the ciphertext (feedCiphertext ran).
                                assert(
                                    recordingServer.feedCalls.get() > 0,
                                    "the inbound ciphertext was never fed to the engine; it was delivered undecrypted"
                                )
                                // (2) The application received the decrypted plaintext, never the raw ciphertext.
                                assert(
                                    got.toArray.toList != ciphertext.toList,
                                    s"raw ciphertext was delivered undecrypted: ${got.toArray.toList}"
                                )
                                assert(
                                    got.toArray.toList == plaintext.toList,
                                    s"the application must receive the plaintext, got ${got.toArray.toList}"
                                )
                            case other =>
                                fail(s"expected ReadOutcome.Bytes, got $other")
                        }
                    }
                }
            }.map(_ => succeed)
        }
    }

end IoUringTlsEncryptionTest
