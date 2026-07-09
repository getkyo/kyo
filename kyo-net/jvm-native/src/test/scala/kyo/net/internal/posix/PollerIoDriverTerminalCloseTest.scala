package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.tls.TlsRealEngines

/** Deterministic regression coverage for the poller driver's terminal-close path (the CLOSE_WAIT fix): a TLS `closeHandle`'s fd-close obligation
  * must be discharged exactly once no matter which of {the normal deferred engine op, the terminal sweep, the post-terminal self-close} ends up
  * running it first. See [[PollerIoDriver.pendingCloses]] / [[PollerIoDriver.dischargeClose]] / [[PollerIoDriver.sweepPendingCloses]] /
  * [[PollerIoDriver.terminalTeardown]].
  *
  * `RecordingPollerBackend.closeWakeDone()` is the deterministic "the driver's terminal exit has fully completed" signal (`closeWake` runs at
  * the very end of `terminalTeardown` -> `freeScratch`, strictly after `terminal` is set and `sweepPendingCloses` has run), so both leaves
  * await it instead of a sleep or a flag poll.
  */
class PollerIoDriverTerminalCloseTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    "PollerIoDriver terminal fd-close discharge" - {

        "closeHandle on a live TLS handle after the driver has gone terminal self-closes: fd closed and engine freed exactly once" in {
            PosixTestSockets.assumePoller()
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            discard(driver.start())
            PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                val handle    = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                val rawEngine = TlsRealEngines.singleEngine(isServer = true)
                val engine    = new RecordingTlsEngine(rawEngine)
                handle.tls = Present(engine)
                val closeWakeDone = backend.closeWakeDone()
                driver.close()
                closeWakeDone.safe.get.map { _ =>
                    // The driver is now provably terminal (closeWake ran at the tail of the terminal exit). closeHandle's TLS branch must take
                    // the post-terminal self-close fallback: the queued engine op it also submits will never run (the executor is dead), so the
                    // fd/engine must be reclaimed synchronously here instead of stranding.
                    driver.closeHandle(handle)
                    discard(sock.close(client))
                    assert(
                        spy.closeCounts.getOrDefault(accepted, 0) == 1,
                        s"the fd must be closed exactly once by the post-terminal self-close, counts=${spy.closeCounts}"
                    )
                    assert(engine.freeCount.get() == 1, s"the engine must be freed exactly once, was ${engine.freeCount.get()}")
                }
            }
        }

        "an op enqueued before the driver goes terminal is discharged exactly once (sweep or drain, never stranded)" in {
            PosixTestSockets.assumePoller()
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            discard(driver.start())
            PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                val handle    = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                val rawEngine = TlsRealEngines.singleEngine(isServer = true)
                val engine    = new RecordingTlsEngine(rawEngine)
                handle.tls = Present(engine)
                val closeWakeDone = backend.closeWakeDone()
                // Register the fd-close obligation WHILE the driver is still live: this submits the discharge op to engineQueue (and does NOT
                // self-close inline, since the driver has not gone terminal yet).
                driver.closeHandle(handle)
                driver.close()
                closeWakeDone.safe.get.map { _ =>
                    discard(sock.close(client))
                    // Whichever mechanism actually ran it (the loop's own per-cycle drain, or the terminal sweep's catch-all), the obligation
                    // must have been discharged exactly once by the time the terminal exit has fully completed.
                    assert(
                        spy.closeCounts.getOrDefault(accepted, 0) == 1,
                        s"a pre-terminal enqueued close must be discharged exactly once, counts=${spy.closeCounts}"
                    )
                    assert(engine.freeCount.get() == 1, s"the engine must be freed exactly once, was ${engine.freeCount.get()}")
                }
            }
        }
    }

end PollerIoDriverTerminalCloseTest
