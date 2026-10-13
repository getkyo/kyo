package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.TlsEngine
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.transport.WriteResult

/** Deterministic guard for the `flushReArmPending` double-arm coalescing in [[PollerIoDriver.armWritableForFlush]]: while a
  * pending-ciphertext flush is already awaiting writability (`flushReArmPending == true`), a SECOND TLS write that arrives and appends
  * more ciphertext must NOT register a second `awaitWritable`. The already-pending flush re-submits a [[PollerIoDriver.flushPending]]
  * that drains the combined buffer, so a single writable re-arm covers both writes; a second registration would leak interest and could
  * double-drive the flush.
  *
  * Gate: `PosixTestSockets.assumePoller()` (real loopback pair for real EAGAIN) and `TlsRealEngines.assumeTlsReady()` (a real BoringSSL/OpenSSL
  * engine).
  *
  * Coherence (avoiding real-socket + fake-backend incoherence): the backend is a [[RecordingPollerBackend]] over the real
  * epoll/kqueue, the socket is a real `smallBufferedPair` whose PEER NEVER READS, and the engine is a real BoringSSL engine post-handshake. The
  * real engine encrypts each plaintext into ciphertext of roughly the same size, so a 600 KB write overflows the shrunk buffer and EAGAINs.
  *
  * Write interest is withheld from the kernel. A peer that never reads does not keep the socket unwritable: the kernel still moves send-buffer
  * bytes into the peer's receive buffer, and on macOS loopback that fires a real writable, whose completed flush correctly arms again. Each
  * writable readiness the driver sees is therefore one the leaf injects, so the recorded arms count the writes and writables the leaf issued.
  *
  * Anti-flakiness: a real handshake via `TlsEngineLoopback.handshake` driven ON the engine FIFO worker brings the engine to a state where
  * `writePlain` is valid (the session is created, handshaked, and written on one carrier, as the engine-FIFO single-owner contract requires); a
  * `fifoBarrier` after each write proves that write's engine op (encrypt + flush + any arm) has run, and the backend's registerWrite latches
  * prove each arm executed on the change-FIFO worker. No sleep.
  */
class FlushReArmPendingCoalesceTest extends Test:

    import AllowUnsafe.embrace.danger

    private def fifoBarrier(driver: PollerIoDriver)(using AllowUnsafe): Promise.Unsafe[Unit, Any] =
        val p = Promise.Unsafe.init[Unit, Any]()
        driver.submitEngineOp(() => p.completeDiscard(Result.succeed(())))
        p
    end fifoBarrier

    /** Complete the in-memory handshake for `client`/`server` ON the driver's engine FIFO worker, then free the server engine there too, and
      * return a promise that completes once both have run. The real BoringSSL session is created, handshaked, and (for the client) later written
      * on the SAME FIFO worker carrier, matching production's single-owner engine-FIFO contract; driving the handshake on a different carrier than
      * the subsequent writePlain corrupts the native session. The server engine is unused past the handshake here, so it is freed on the worker.
      */
    private def handshakeOnDriver(driver: PollerIoDriver, client: TlsEngine, server: TlsEngine)(using
        AllowUnsafe
    ): Promise.Unsafe[Unit, Any] =
        val done = Promise.Unsafe.init[Unit, Any]()
        driver.submitEngineOp { () =>
            discard(TlsEngineLoopback.handshake(client, server))
            server.free()
            done.completeDiscard(Result.succeed(()))
        }
        done
    end handshakeOnDriver

    /** Two 600 KB TLS writes against a peer that never reads, the second issued while the first one's flush is armed for writability.
      * With `writableBetween`, one writable readiness is injected after the first arm, and the second write waits for the re-arm it causes.
      * Returns how many write arms the backend recorded once the second write's engine op has run.
      */
    private def twoWrites(writableBetween: Boolean)(using Frame, AllowUnsafe, kyo.test.AssertScope): Int < Async =
        TlsRealEngines.assumeTlsReady()
        PosixTestSockets.assumePoller()
        // 600 KB payloads: the flushPending loop sends in ~65 KB chunks and EAGAINs within a few chunks (macOS effective TCP buffer
        // ~520 KB; Linux a few KB). The peer (second element) NEVER READS, so the buffer stays full.
        val clientEngine = TlsRealEngines.singleEngine(isServer = false)
        val serverEngine = TlsRealEngines.singleEngine(isServer = true)
        PosixTestSockets.smallBufferedPair(sndBuf = 64, rcvBuf = 64).map { case (writeFd, peerFd) =>
            val plain1 = Array.fill[Byte](600000)(1.toByte)
            val plain2 = Array.fill[Byte](600000)(2.toByte)

            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val backend  = RecordingPollerBackend(real)
            backend.withholdWriteInterest.set(true)
            val driver = TestDrivers.forBackend(backend, pollerFd, spy)
            val handle = PosixHandle.socket(writeFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
            handle.tls = Present(clientEngine)
            discard(driver.start())

            for
                // Handshake the engines ON the FIFO worker so the client session is created, handshaked, and written on one carrier.
                _ <- handshakeOnDriver(driver, clientEngine, serverEngine).safe.get
                // Write 1: 600 KB. The flushPending loop hits EAGAIN (buffer fills) and arms writability (registerWrite #1).
                w1 <- Sync.defer(driver.write(handle, Span.fromUnsafe(plain1), 0))
                _ = assert(w1 == WriteResult.Done, s"TLS write 1 should return Done, got $w1")
                // The flush runs on the engine FIFO; a fifoBarrier proves it completed (including the submitChange the arm issued).
                _ <- fifoBarrier(driver).safe.get
                // The arm's registerWrite runs on the change-FIFO worker; latch on its execution (no sleep).
                _ <- backend.registeredWrite(writeFd).safe.get
                _ <-
                    if !writableBetween then Kyo.unit
                    else
                        // The writable completes the armed flush, which finds the buffer still full, EAGAINs and arms again.
                        Sync.defer(backend.injectWritable(writeFd)).andThen(backend.registerWriteReached(2).safe.get)
                            .andThen(fifoBarrier(driver).safe.get)
                _ = assert(handle.flushReArmPending, "a flush must be armed for writability before the second write")
                // Write 2 arrives WHILE flushReArmPending is set: appends 600 KB onto the armed flush.
                w2 <- Sync.defer(driver.write(handle, Span.fromUnsafe(plain2), 0))
                _ = assert(w2 == WriteResult.Done, s"TLS write 2 should return Done, got $w2")
                // FIFO barrier: proves write 2's engine op (including its attempted flush) has run.
                _ <- fifoBarrier(driver).safe.get
            yield
                val count = backend.registerWriteCount.get()
                // Free the client engine on the FIFO worker (closeHandle routes the engine free through submitEngineOp) and close the fds.
                driver.closeHandle(handle)
                driver.close()
                PosixTestSockets.closePeerForEof(spy, peerFd)
                count
            end for
        }
    end twoWrites

    "flushReArmPending double-arm coalescing" - {
        "a second write while a flush is awaiting writable does not arm a second awaitWritable" in {
            if kyo.internal.Platform.isJS then Sync.defer(succeed)
            else
                twoWrites(writableBetween = false).map { count =>
                    assert(count == 1, s"a second write while armed must NOT arm a second awaitWritable (registerWriteCount=$count)")
                }
        }

        "a writable between the two writes re-arms exactly once, and the second write coalesces onto that arm" in {
            if kyo.internal.Platform.isJS then Sync.defer(succeed)
            else
                twoWrites(writableBetween = true).map { count =>
                    assert(
                        count == 2,
                        s"one writable must cause exactly one re-arm and the second write none: registerWriteCount=$count, expected 2"
                    )
                }
        }
    }

end FlushReArmPendingCoalesceTest
