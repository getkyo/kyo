package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.transport.ReadOutcome

/** io_uring-path parity guard for the fatal-record abort + read-produced-ciphertext drain (mirroring the poller's
  * [[TlsEngineIoCorruptRecordTest]] / [[TlsEngineIoReadDrainTest]]).
  *
  * Both reproductions and fixes for the fatal-record swallow (RFC 5246 §7.2.2: a fatal record-layer error must terminate the connection, not
  * be delivered behind good data) and the read-path WANT_WRITE / KeyUpdate stall (a `SSL_read` that queues outbound ciphertext must be sent)
  * live on the shared [[TlsEngineIo]] decrypt path. The io_uring read CQE completion drives that same path inside its engine FIFO op
  * ([[IoUringDriver]] `complete` -> `feedAndDecrypt`), then checks `PosixHandle.isClosing()` (the io_uring twin of the poller's rearmOwned /
  * endDispatch closing check) to fail the read `Closed` on a fatal record, and calls `flushTls` to send any ciphertext the read produced. This
  * test pins that the io_uring path behaves identically to the poller path on a REAL ring with a REAL BoringSSL engine, with no dedicated
  * io_uring-path coverage before now.
  *
  * Leaf 1 (fatal-record abort) feeds `[good record][corrupted record]` (the corruption flips a body byte of the second record so its AEAD tag
  * fails) coalesced on the wire and reads via the driver. The driver's read CQE feeds the engine; `feedAndDecrypt` reaches the fatal `-2`,
  * calls `requestClose()`, and the completion observes the now-closing handle and fails the read `Closed` rather than delivering the good prefix
  * or re-arming a freed handle. Leaf 2 (read-produced-ciphertext drain) reads a real inbound record through the same CQE path and asserts the
  * engine's write side was drained (`drainCiphertext` ran), the wiring by which a TLS 1.3 KeyUpdate response queued during the read reaches the
  * wire via `flushTls` (a real end-to-end KeyUpdate is not drivable: the shims bind no `SSL_key_update`, the same limitation the poller's
  * read-drain leaf documents).
  *
  * Gate: [[PosixTestSockets.assumeUring]] and [[TlsRealEngines.assumeTlsReady]]; cancels cleanly off Linux, where the production-depth ring
  * cannot init, or where no BoringSSL provider is staged, so this is CI-validated on native Linux.
  *
  * Anti-flakiness: each leaf synchronizes on the read promise (completed only when the recv CQE reaps and the decrypt engine op runs) and a
  * FIFO barrier (the decrypt engine op has run), never a timer. `Async.timeout` is only the deadlock ceiling. The probe engines are freed in a
  * finally; the driver handle's `engineFreeSink` is set to a no-op so `withEngines` owns the single real free (no double-free of the native
  * session when `requestClose` tears the handle down). No sleep, no busy-spin.
  */
class IoUringDriverCorruptRecordTest extends Test:

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
      * (the read's decrypt) has run.
      */
    private def fifoBarrier(drv: IoUringDriver): Promise.Unsafe[Unit, Any] =
        val p = Promise.Unsafe.init[Unit, Any]()
        drv.submitEngineOp(() => p.completeDiscard(Result.succeed(())))
        p
    end fifoBarrier

    "IoUringDriver corrupt-record + read-drain (real ring, real engine)" - {

        "a fatal TLS record on a read CQE fails the read Closed and tears the connection down, never delivering the good prefix" in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.assumeTlsReady()
            TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "handshake must complete before the read")
                withRecordingDriver { (drv, _) =>
                    PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                        val handle = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent)
                        handle.tls = Present(serverEngine)
                        // The driver handle must NOT free the engine on teardown: withEngines owns the single real free. requestClose (fired by
                        // the fatal record) routes the engine free through this sink, so a no-op sink keeps the native session alive for withEngines.
                        handle.engineFreeSink = _ => ()

                        val good = "GOOD-application-record".getBytes("UTF-8")
                        val bad  = "TAMPERED-application-record".getBytes("UTF-8")
                        // One writePlain per record yields one TLS record each.
                        val goodRecord = TlsEngineLoopback.encrypt(clientEngine, good)
                        val badRecord  = TlsEngineLoopback.encrypt(clientEngine, bad)
                        assert(goodRecord.length > 5 && badRecord.length > 5, "expected real TLS records with a 5-byte header plus body")
                        // Corrupt the body of the SECOND record (skip the 5-byte header) so its AEAD tag fails; the first stays intact.
                        val corrupted = badRecord.clone()
                        corrupted(corrupted.length - 1) = (corrupted(corrupted.length - 1) ^ 0xff).toByte
                        // Coalesce [good record][corrupted record] in one wire write, exactly the on-wire batching the driver sees under load.
                        val coalesced = new Array[Byte](goodRecord.length + corrupted.length)
                        java.lang.System.arraycopy(goodRecord, 0, coalesced, 0, goodRecord.length)
                        java.lang.System.arraycopy(corrupted, 0, coalesced, goodRecord.length, corrupted.length)
                        assert(sock.sendNow(
                            peerFd,
                            Buffer.fromArray[Byte](coalesced),
                            coalesced.length.toLong,
                            0
                        ).value == coalesced.length.toLong)

                        val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        drv.awaitRead(handle, promise)
                        Abort.run[Timeout | Closed](Async.timeout(5.seconds)(promise.safe.get)).map { outcome =>
                            // The decrypt engine op (which ran requestClose on the fatal record) has run once the FIFO barrier fires.
                            fifoBarrier(drv).safe.get.map { _ =>
                                val closing = handle.isClosing()
                                handle.tls = Absent
                                drv.closeHandle(handle)
                                discard(sock.close(peerFd))
                                outcome match
                                    case Result.Failure(_: Closed) =>
                                        // RFC 5246 §7.2.2: the fatal record tears the connection down. The driver must NOT have delivered the good
                                        // prefix as a normal read, and the handle must be closing (requestClose fired), not re-armed.
                                        assert(
                                            closing,
                                            "the fatal record must mark the handle closing (requestClose), tearing it down rather than re-arming a freed handle"
                                        )
                                    case Result.Success(ReadOutcome.Bytes(got)) =>
                                        fail(
                                            s"a fatal TLS record was swallowed: the read delivered ${got.size} bytes (${got.toArray.toList}) " +
                                                "instead of failing Closed; RFC 5246 §7.2.2 requires the connection to be torn down"
                                        )
                                    case Result.Success(other) =>
                                        fail(
                                            s"a fatal TLS record was swallowed: the read produced $other " +
                                                "instead of failing Closed; RFC 5246 §7.2.2 requires the connection to be torn down"
                                        )
                                    case Result.Failure(_: Timeout) =>
                                        fail("the read hung on a fatal record: the fatal abort never failed the read Closed")
                                    case other => fail(s"unexpected read outcome: $other")
                                end match
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "a read CQE that decrypts a record drains the engine write side, the wiring that flushes a read-produced KeyUpdate response" in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.assumeTlsReady()
            TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "handshake must complete before the read")
                val recordingServer = RecordingTlsEngine(serverEngine)
                withRecordingDriver { (drv, _) =>
                    PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                        val handle = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent)
                        handle.tls = Present(recordingServer)

                        val plaintext  = "application-data-on-the-io_uring-read-path".getBytes("UTF-8")
                        val ciphertext = TlsEngineLoopback.encrypt(clientEngine, plaintext)
                        assert(ciphertext.nonEmpty, "the peer produced no ciphertext")
                        assert(sock.sendNow(
                            peerFd,
                            Buffer.fromArray[Byte](ciphertext),
                            ciphertext.length.toLong,
                            0
                        ).value == ciphertext.length.toLong)

                        val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        drv.awaitRead(handle, promise)
                        promise.safe.get.flatMap { outcome =>
                            val got = outcome match
                                case ReadOutcome.Bytes(span) => span
                                case other                   => fail(s"expected ReadOutcome.Bytes, got $other")
                            fifoBarrier(drv).safe.get.map { _ =>
                                val drains = recordingServer.drainCalls.get()
                                val feeds  = recordingServer.feedCalls.get()
                                handle.tls = Absent
                                drv.closeHandle(handle)
                                discard(sock.close(peerFd))
                                // (1) The read CQE path decrypted the inbound record (it really ran the engine).
                                assert(
                                    got.toArray.toList == plaintext.toList,
                                    s"the application must receive the plaintext, got ${got.toArray.toList}"
                                )
                                assert(feeds > 0, "the inbound ciphertext was never fed to the engine on the io_uring read CQE path")
                                // (2) Parity with the poller's read-drain fix: the io_uring read CQE path drains the engine write side after the
                                // decrypt (feedAndDecrypt -> drainReadProducedCiphertext), so a KeyUpdate response queued during SSL_read reaches
                                // the wire via flushTls. drainCalls > 0 proves that wiring exists on the io_uring path (a real end-to-end KeyUpdate
                                // is not drivable: the shims bind no SSL_key_update, the same limitation TlsEngineIoReadDrainTest documents).
                                assert(
                                    drains > 0,
                                    s"the io_uring read CQE path made $drains drainCiphertext calls: it must drain the engine write side after the " +
                                        "decrypt so a read-produced KeyUpdate response (or TLS 1.2 renegotiation flight) reaches the wire, not stall"
                                )
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }
    }

end IoUringDriverCorruptRecordTest
