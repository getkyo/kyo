package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.NetConnectionIoException
import kyo.net.Test
import kyo.net.internal.TlsEngine
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.transport.ReadOutcome

/** io_uring-path parity guard for the fatal-record abort + read-produced-ciphertext drain (mirroring the poller's
  * [[TlsEngineIoCorruptRecordTest]] / [[TlsEngineIoReadDrainTest]]).
  *
  * The fatal-record swallow (RFC 5246 §7.2.2: a fatal record-layer error must terminate the connection, not be delivered behind good data) and
  * the read-path KeyUpdate stall (a `SSL_read` that queues outbound ciphertext must send it) both live on the shared [[TlsEngineIo]] decrypt path.
  * The io_uring read CQE completion drives that same path inside its engine FIFO op
  * ([[IoUringDriver]] `complete` -> `feedAndDecrypt`), detects the fatal record via `feedAndDecrypt`'s `onFatal` signal, and fails the read with
  * the typed decrypt error (`ReadOutcome.Failed(NetConnectionIoException(Decrypt))`) while tearing the connection down, then calls `flushTls` to
  * send any ciphertext the read produced. This test pins that the io_uring path behaves identically to the poller path on a REAL ring with a REAL
  * BoringSSL engine.
  *
  * Leaf 1 (fatal-record abort) feeds `[good record][corrupted record]` (the corruption flips a body byte of the second record so its AEAD tag
  * fails) coalesced on the wire and reads via the driver. The driver's read CQE feeds the engine; `feedAndDecrypt` reaches the fatal record,
  * fires `onFatal` (tearing the connection down), and the completion fails the read with the typed decrypt error rather than delivering the good
  * prefix or re-arming a freed handle. Leaf 2 (read-produced-ciphertext drain) reads a real inbound record through the same CQE path and asserts the
  * engine's write side was drained (`drainCiphertext` ran), the wiring by which a TLS 1.3 KeyUpdate response queued during the read reaches the
  * wire via `flushTls` (a real end-to-end KeyUpdate is not drivable: the shims bind no `SSL_key_update`, the same limitation the poller's
  * read-drain leaf documents).
  *
  * Gate: [[PosixTestSockets.assumeUring]] and [[TlsRealEngines.assumeBoringSslReady]]; cancels cleanly off Linux, where the production-depth ring
  * cannot init, or where BoringSSL is not staged, so this is CI-validated on native Linux.
  *
  * Anti-flakiness: each leaf synchronizes on the read promise (completed only when the recv CQE reaps and the decrypt engine op runs) and a
  * FIFO barrier (the decrypt engine op has run), never a timer; a hang fails by the leaf timeout. The test owns both engines: the handle's
  * `engineFreeSink` is a no-op so `requestClose` does not free the server engine, and [[withTlsHandle]] releases the handle and both engines
  * in `Sync.ensure` as one engine op, behind every op still queued for the connection, so an interrupted leaf releases them too. No sleep, no
  * busy-spin.
  */
class IoUringDriverCorruptRecordTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Allocate a real io_uring ring at production depth, wrap it in a recording spy, build a driver, run `body`, then close the driver. */
    private def withRecordingDriver[A](
        body: (IoUringDriver, RecordingIoUringBindings) => A < (Abort[Closed] & Async)
    )(using Frame): A < (Abort[Closed] & Async) =
        val depth     = math.max(256, kyo.net.ioPoolSize() * 64)
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

    /** Runs `body` over a driver handle on a loopback pair whose engine is `tls(serverEngine)`, with a handshaken engine pair. The handle is
      * detached from its engine and closed, and both engines freed, in one engine op submitted from `Sync.ensure`.
      */
    private def withTlsHandle[T <: TlsEngine, A](tls: TlsEngine => T)(
        body: (IoUringDriver, PosixHandle, Int, TlsEngine, T) => A < (Abort[Closed] & Async)
    )(using Frame, kyo.test.AssertScope): A < (Abort[Closed] & Async) =
        withRecordingDriver { (drv, _) =>
            PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                val clientEngine = TlsRealEngines.singleEngine(isServer = false)
                val serverEngine = TlsRealEngines.singleEngine(isServer = true)
                val server       = tls(serverEngine)
                val handle       = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                handle.tls = Present(server)
                handle.engineFreeSink = _ => ()
                Sync.ensure(Sync.defer {
                    drv.submitEngineOp { () =>
                        handle.tls = Absent
                        drv.closeHandle(handle)
                        serverEngine.free()
                        clientEngine.free()
                    }
                    discard(sock.close(peerFd))
                }) {
                    assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "handshake must complete before the read")
                    body(drv, handle, peerFd, clientEngine, server)
                }
            }
        }
    end withTlsHandle

    private def sendAll(fd: Int, bytes: Array[Byte])(using kyo.test.AssertScope): Unit =
        val buf = Buffer.fromArray[Byte](bytes)
        try assert(sock.sendNow(fd, buf, bytes.length.toLong, 0).value == bytes.length.toLong)
        finally buf.close()
    end sendAll

    /** Submit a marker engine op and return a promise that completes once the FIFO worker runs it, proving every engine op submitted before it
      * (the read's decrypt) has run.
      */
    private def fifoBarrier(drv: IoUringDriver): Promise.Unsafe[Unit, Any] =
        val p = Promise.Unsafe.init[Unit, Any]()
        drv.submitEngineOp(() => p.completeDiscard(Result.succeed(())))
        p
    end fifoBarrier

    "IoUringDriver corrupt-record + read-drain (real ring, real engine)" - {

        "a fatal TLS record on a read CQE fails the read with a typed decrypt error and tears the connection down, never delivering the good prefix" in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.assumeBoringSslReady()
            withTlsHandle(identity) { (drv, handle, peerFd, clientEngine, _) =>
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
                sendAll(peerFd, goodRecord ++ corrupted)

                val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                drv.awaitRead(handle, promise)
                awaitOutcome(promise, "the read hung on a fatal record: the fatal abort never completed the read").map { outcome =>
                    // The decrypt engine op (which ran requestClose on the fatal record) has run once the FIFO barrier fires.
                    fifoBarrier(drv).safe.get.map { _ =>
                        val closing = handle.isClosing()
                        outcome match
                            case Result.Success(ReadOutcome.Failed(e: NetConnectionIoException)) =>
                                // RFC 5246 §7.2.2: the fatal record tears the connection down, surfaced as the typed decrypt failure (never a
                                // misleading Closed, and never the good prefix). The handle must be closing (onFatal tore it down), not re-armed.
                                assert(
                                    e.operation == NetConnectionIoException.Operation.Decrypt,
                                    s"the fatal record must surface as a TLS decrypt failure; got operation ${e.operation}"
                                )
                                assert(
                                    closing,
                                    "the fatal record must mark the handle closing (onFatal), tearing it down rather than re-arming a freed handle"
                                )
                            case Result.Success(ReadOutcome.Bytes(got)) =>
                                fail(
                                    s"a fatal TLS record was swallowed: the read delivered ${got.size} bytes (${got.toArray.toList}) " +
                                        "instead of the typed decrypt failure; RFC 5246 §7.2.2 requires the connection to be torn down"
                                )
                            case other =>
                                fail(s"a fatal TLS record must surface as the typed decrypt failure; got: $other")
                        end match
                    }
                }
            }.map(_ => succeed)
        }

        "a read CQE that decrypts a record drains the engine write side, the wiring that flushes a read-produced KeyUpdate response" in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.assumeBoringSslReady()
            withTlsHandle(RecordingTlsEngine(_)) { (drv, handle, peerFd, clientEngine, recordingServer) =>
                val plaintext  = "application-data-on-the-io_uring-read-path".getBytes("UTF-8")
                val ciphertext = TlsEngineLoopback.encrypt(clientEngine, plaintext)
                assert(ciphertext.nonEmpty, "the peer produced no ciphertext")
                sendAll(peerFd, ciphertext)

                val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                drv.awaitRead(handle, promise)
                promise.safe.get.flatMap { outcome =>
                    val got = outcome match
                        case ReadOutcome.Bytes(span) => span
                        case other                   => fail(s"expected ReadOutcome.Bytes, got $other")
                    fifoBarrier(drv).safe.get.map { _ =>
                        val drains = recordingServer.drainCalls.get()
                        val feeds  = recordingServer.feedCalls.get()
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
            }.map(_ => succeed)
        }
    }

end IoUringDriverCorruptRecordTest
