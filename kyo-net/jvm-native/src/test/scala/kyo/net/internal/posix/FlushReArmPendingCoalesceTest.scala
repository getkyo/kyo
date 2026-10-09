package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.TlsEngine
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.transport.WriteResult

/** Guard for the `flushReArmPending` double-arm coalescing in [[PollerIoDriver.armWritableForFlush]]: while a
  * pending-ciphertext flush is already awaiting writability (`flushReArmPending == true`), a SECOND TLS write that arrives and appends
  * more ciphertext must NOT register a second `awaitWritable`. The already-pending flush re-submits a [[PollerIoDriver.flushPending]]
  * that drains the combined buffer, so a single writable re-arm covers both writes; a second registration would leak interest and could
  * double-drive the flush.
  *
  * Gate: `PosixTestSockets.assumePoller()` (real loopback pair for real EAGAIN) and `TlsRealEngines.assumeTlsReady()` (a real BoringSSL/OpenSSL
  * engine).
  *
  * Coherence (avoiding real-socket + fake-backend incoherence): the backend is a [[RecordingPollerBackend]] over the real
  * epoll/kqueue, the socket is a real `smallBufferedPair` whose PEER NEVER READS, and the engine is a real BoringSSL engine post-handshake.
  * How much a send takes is the kernel's call (macOS grows a shrunk buffer on demand and can open room after an EAGAIN), so writes are repeated
  * until the flush is parked on writability, and an attempt counts only when the wait armed before the second write never fired.
  *
  * Anti-flakiness: a real handshake via `TlsEngineLoopback.handshake` driven ON the engine FIFO worker brings the engine to a state where
  * `writePlain` is valid (the session is created, handshaked, and written on one carrier, as the engine-FIFO single-owner contract requires); a
  * `fifoBarrier` after each write proves that write's engine op (encrypt + flush + any arm) has run. No sleep.
  *
  * Uses a real BoringSSL engine via `TlsRealEngines.singleEngine`, handshaked on the driver's engine FIFO. The key assertion is that the
  * handle still holds the writable wait armed before the second write: a write while armed must not arm a second one.
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

    /** Returns once the flush is parked on writability with ciphertext unsent, writing a 64 KiB span whenever the tail has fully drained. No
      * write size is sure to park it (macOS grows a shrunk buffer on demand, and a parked flush can resume and drain its whole tail), but the
      * peer never reads, so the kernel runs out of room and the loop ends.
      */
    private def writeUntilFlushParks(driver: PollerIoDriver, handle: PosixHandle, fill: Byte)(using
        Frame,
        AllowUnsafe,
        kyo.test.AssertScope
    ): Unit < Async =
        Loop(()) { _ =>
            if handle.unsentTailBytes == 0 then
                val w = driver.write(handle, Span.fromUnsafe(Array.fill[Byte](64 * 1024)(fill)), 0)
                assert(w == WriteResult.Done, s"a TLS write below the tail bound should return Done, got $w")
            fifoBarrier(driver).safe.get.map { _ =>
                if handle.flushReArmPending && handle.unsentTailBytes > 0 then Loop.done(()) else Loop.continue(())
            }
        }

    /** Parks the flush, writes once more, and returns the writable wait armed before that write with the one the handle holds after it. A
      * parked flush can still resume when the kernel opens room, and the arm it then makes is legitimate, so an attempt counts only when the
      * wait armed before the write never fired.
      */
    private def writeWhileParked(driver: PollerIoDriver, handle: PosixHandle)(using
        Frame,
        AllowUnsafe,
        kyo.test.AssertScope
    ): (AnyRef, AnyRef) < Async =
        Loop(()) { _ =>
            writeUntilFlushParks(driver, handle, 1.toByte).andThen {
                handle.pendingWritablePromise.get() match
                    case Absent         => Loop.continue(())
                    case Present(armed) =>
                        val w = driver.write(handle, Span.fromUnsafe(Array.fill[Byte](64 * 1024)(2.toByte)), 0)
                        assert(w == WriteResult.Done, s"the write while parked should return Done, got $w")
                        fifoBarrier(driver).safe.get.map { _ =>
                            val after = handle.pendingWritablePromise.get()
                            if armed.done() then Loop.continue(())
                            else Loop.done((armed.asInstanceOf[AnyRef], after.asInstanceOf[AnyRef]))
                        }
                end match
            }
        }

    "flushReArmPending double-arm coalescing" - {
        "a second write while a flush is awaiting writable does not arm a second awaitWritable" in {
            if kyo.internal.Platform.isJS then Sync.defer(succeed)
            else
                TlsRealEngines.assumeTlsReady()
                PosixTestSockets.assumePoller()
                // The peer (second element) NEVER READS, so once its buffers fill the real socket stays unwritable and the double-arm
                // coalescing is observed with no writable event to race.
                val clientEngine = TlsRealEngines.singleEngine(isServer = false)
                val serverEngine = TlsRealEngines.singleEngine(isServer = true)
                PosixTestSockets.smallBufferedPair(sndBuf = 64, rcvBuf = 64).map { case (writeFd, peerFd) =>
                    val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                    val real     = PollerBackend.default()
                    val pollerFd = real.create()
                    val backend  = RecordingPollerBackend(real)
                    val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
                    val handle   = PosixHandle.socket(writeFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                    handle.tls = Present(clientEngine)
                    discard(driver.start())

                    for
                        // Handshake the engines ON the FIFO worker so the client session is created, handshaked, and written on one carrier.
                        _              <- handshakeOnDriver(driver, clientEngine, serverEngine).safe.get
                        (armed, after) <- writeWhileParked(driver, handle)
                    yield
                        // Free the client engine on the FIFO worker (closeHandle routes the engine free through submitEngineOp) and close the fds.
                        driver.closeHandle(handle)
                        driver.close()
                        PosixTestSockets.closePeerForEof(spy, peerFd)
                        assert(
                            after eq armed,
                            "a second write while armed must NOT arm a second awaitWritable: the handle holds a different writable wait"
                        )
                    end for
                }
        }
    }

end FlushReArmPendingCoalesceTest
