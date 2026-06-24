package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.tls.TlsEngine
import kyo.net.internal.tls.TlsEngineLoopback
import kyo.net.internal.tls.TlsRealEngines
import kyo.net.internal.transport.WriteResult

/** Deterministic guard for the `writableArmed` double-arm coalescing in [[PollerIoDriver.armWritableForFlush]]: while a pending-ciphertext flush
  * is already awaiting writability (`writableArmed == true`), a SECOND TLS write that arrives and appends more ciphertext must NOT register a
  * second `awaitWritable`. The already-pending flush re-submits a [[PollerIoDriver.flushPending]] that drains the combined buffer, so a single
  * writable re-arm covers both writes; a second registration would leak interest and could double-drive the flush.
  *
  * Gate: `PosixTestSockets.assumePoller()` (real loopback pair for real EAGAIN) and `TlsRealEngines.assumeTlsReady()` (a real BoringSSL/OpenSSL
  * engine).
  *
  * Coherence (the fix for the prior real-socket + fake-backend incoherence): the backend is a [[RecordingPollerBackend]] over the real
  * epoll/kqueue, the socket is a real `smallBufferedPair` whose PEER NEVER READS, and the engine is a real BoringSSL engine post-handshake. The
  * send buffer fills on the first write and never drains, so the real socket NEVER becomes writable: there is no real writable event, no race,
  * and the test is deterministic. The only registerWrite recorded is the single arm the first flush issues on EAGAIN; the second write coalesces.
  * The real engine encrypts each plaintext into ciphertext of roughly the same size, so a 600 KB write still overflows the shrunk buffer and
  * EAGAINs exactly as the old passthrough engine did.
  *
  * Anti-flakiness: a real handshake via `TlsEngineLoopback.handshake` driven ON the engine FIFO worker brings the engine to a state where
  * `writePlain` is valid (the session is created, handshaked, and written on one carrier, as the engine-FIFO single-owner contract requires); a
  * `fifoBarrier` after each write proves that write's engine op (encrypt + flush + any arm) has run, and `spy.registeredWrite(writeFd).safe.get`
  * latches on the first registerWrite executing on the change-FIFO worker. No sleep, no writable event to race.
  *
  * Uses a real BoringSSL engine via `TlsRealEngines.singleEngine`, handshaked on the driver's engine FIFO. The key assertion is
  * `registerWriteCount == 1`: the second write while armed must not register a second `awaitWritable`.
  */
class WritableArmedCoalesceTest extends Test:

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

    "writableArmed double-arm coalescing" - {
        "a second write while a flush is awaiting writable does not arm a second awaitWritable" in {
            if kyo.internal.Platform.isJS then Sync.defer(succeed)
            else
                TlsRealEngines.assumeTlsReady()
                PosixTestSockets.assumePoller()
                // 600 KB payloads: the flushPending loop sends in ~65 KB chunks and EAGAINs within a few chunks (macOS effective TCP buffer
                // ~520 KB; Linux a few KB). The peer (second element) NEVER READS, so the buffer stays full: the real socket never becomes
                // writable, so no real writable event ever fires and the double-arm coalescing is observed deterministically.
                val clientEngine = TlsRealEngines.singleEngine(isServer = false)
                val serverEngine = TlsRealEngines.singleEngine(isServer = true)
                PosixTestSockets.smallBufferedPair(sndBuf = 64, rcvBuf = 64).map { case (writeFd, peerFd) =>
                    val plain1 = Array.fill[Byte](600000)(1.toByte)
                    val plain2 = Array.fill[Byte](600000)(2.toByte)

                    val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                    val real     = PollerBackend.default()
                    val pollerFd = real.create()
                    val backend  = RecordingPollerBackend(real)
                    val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
                    val handle   = PosixHandle.socket(writeFd, PosixHandle.DefaultReadBufferSize, Absent)
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
                        _ = assert(handle.writableArmed, "the first flush must arm writability before the second write")
                        // Write 2 arrives WHILE writableArmed is set: appends 600 KB; the flush must NOT register a second awaitWritable.
                        w2 <- Sync.defer(driver.write(handle, Span.fromUnsafe(plain2), 0))
                        _ = assert(w2 == WriteResult.Done, s"TLS write 2 should return Done, got $w2")
                        // FIFO barrier: proves write 2's engine op (including its attempted flush) has run.
                        _ <- fifoBarrier(driver).safe.get
                    yield
                        // Coalescing assertion: the second write while armed must NOT arm a second awaitWritable.
                        val count = backend.registerWriteCount.get()
                        // Free the client engine on the FIFO worker (closeHandle routes the engine free through submitEngineOp) and close the fds.
                        driver.closeHandle(handle)
                        driver.close()
                        PosixTestSockets.closePeerForEof(spy, peerFd)
                        assert(
                            count == 1,
                            s"a second write while armed must NOT arm a second awaitWritable (registerWriteCount=$count)"
                        )
                    end for
                }
        }
    }

end WritableArmedCoalesceTest
