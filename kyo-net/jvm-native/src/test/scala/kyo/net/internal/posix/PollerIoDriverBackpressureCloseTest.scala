package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.util.GrowableByteBuffer

/** Reproduction + regression guard: a write-backpressure waiter parked on a handle while it is being closed must be FAILED with Closed, never
  * left stranded.
  *
  * The write-backpressure tail bound parks the WritePump's writable promise on [[PosixHandle.backpressureWaiter]] when the unsent tail reaches the
  * high-water mark, and relies on [[PollerIoDriver.cancel]] (run first on every close path) to fail it. But a waiter parked in the window AFTER
  * cancel and before [[PosixHandle]] freeResources is only CLEARED by freeResources (set to Absent), not completed, so its promise is stranded and
  * the WritePump fiber that parked on it hangs forever. The close-vs-writable interleaving hits exactly this window; under CPU contention the window
  * widens and the hang reproduces (CloseDuringBackpressuredFlushTest times out). This leaf drives the precise interleaving deterministically:
  * cancel, then park, then teardown, then a non-blocking [[Promise.Unsafe.done]] check, so there is no sleep, no timer, and no load dependency.
  *
  * Runs on every poller host (epoll on Linux, kqueue on macOS/BSD). The poll loop is started because the change and engine FIFOs drain only on its
  * carrier (it bounded-waits on the idle poller fd, no fds registered); the park, cancel, and teardown are sequenced through the FIFO, and the engine
  * FIFO is drained to a barrier so the park's own double-check has run before the teardown, leaving nothing to race it.
  */
class PollerIoDriverBackpressureCloseTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PollerIoDriver needs epoll (Linux) or kqueue (macOS/BSD)")

    /** Submit a marker engine op and return a promise that completes when the FIFO worker runs it. Awaiting it proves the park's double-check
      * (releaseBackpressureWaiter, submitted before this) has already run, leaving no FIFO op to race the teardown.
      */
    private def fifoBarrier(driver: PollerIoDriver)(using AllowUnsafe): Promise.Unsafe[Unit, Any] =
        val p = Promise.Unsafe.init[Unit, Any]()
        driver.submitEngineOp(() => p.completeDiscard(Result.succeed(())))
        p
    end fifoBarrier

    "a write-backpressure waiter parked during close is failed Closed, never stranded" in {
        if kyo.internal.Platform.isJS then Sync.defer(succeed)
        else
            assumePoller()
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val driver   = TestDrivers.forBackend(real, pollerFd)
            discard(driver.start())
            PosixTestSockets.loopbackPair().map { case (writeFd, peerFd) =>
                val handle = PosixHandle.socket(writeFd, PosixHandle.DefaultReadBufferSize, Absent)
                // Put the write tail at the high-water mark so awaitWritable takes the backpressure PARK branch (tail >= low-water).
                val tail = new GrowableByteBuffer()
                tail.writeBytes(Array.fill[Byte](PosixHandle.WriteTailHighWater)(0.toByte), 0, PosixHandle.WriteTailHighWater)
                handle.pendingCipher = Present(tail)
                handle.pendingCipherSent = 0
                assert(
                    handle.unsentTailBytes >= PosixHandle.WriteTailLowWater,
                    "the tail must be at or above the high-water mark for awaitWritable to take the backpressure park branch"
                )
                // The close path's cancel runs FIRST: no waiter is parked yet, so it fails nothing (the window this test exercises).
                driver.cancel(handle)
                // The racing WritePump parks its writable promise AFTER cancel: the tail is high, so this is the backpressure park.
                val waiter = Promise.Unsafe.init[Unit, Abort[Closed]]()
                driver.awaitWritable(handle, waiter)
                assert(
                    handle.backpressurePromise.asInstanceOf[AnyRef] != null,
                    "the waiter must be parked (the tail is at the high-water mark)"
                )
                // Drain the engine FIFO so the park's double-check (releaseBackpressureWaiter) has run (it no-ops: the tail is still high), leaving
                // no FIFO op to race the teardown.
                fifoBarrier(driver).safe.get.map { _ =>
                    // Complete the teardown (the close path's freeResources). With the bug this clears the waiter without failing it: stranded.
                    PosixHandle.close(handle)
                    driver.close()
                    discard(sock.close(writeFd))
                    discard(sock.close(peerFd))
                    // The parked waiter MUST be resolved as a Closed failure, never left pending. A pending waiter is the stranded-WritePump deadlock.
                    assert(
                        waiter.done(),
                        "the parked backpressure waiter was stranded by close (its promise was never completed): the WritePump fiber would hang forever"
                    )
                    waiter.poll() match
                        case Present(Result.Failure(_: Closed)) => succeed
                        case other                              => fail(s"the parked waiter must be failed Closed on teardown, got $other")
                    end match
                }
            }
    }

end PollerIoDriverBackpressureCloseTest
