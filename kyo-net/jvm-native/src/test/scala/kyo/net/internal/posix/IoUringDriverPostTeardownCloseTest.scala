package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.TlsRealEngines

/** Deterministic regression coverage for the io_uring driver's post-teardown close path: once `teardownRing` has run (the ring is exited, the
  * reap carrier is gone), a `closeHandle` call for a handle registered after that point must discharge via `closeNow` directly, inline on the
  * calling carrier, and must NEVER touch the ring (a use-after-free class: the queued-closure path calls `cancel`/`shutdownTls`/
  * `registerDeferredClose`, all of which reach `get_sqe` on the exited ring). See [[IoUringDriver.closeHandle]]'s put-then-recheck against
  * [[IoUringDriver.teardownDone]].
  *
  * A driver whose reap loop is never started tears its ring down SYNCHRONOUSLY inside `close()` (no reap carrier to defer to, so
  * `tryTeardown`'s precondition is met immediately) -- the deterministic way to reach `teardownDone == true` without racing a real reap thread.
  */
class IoUringDriverPostTeardownCloseTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    "IoUringDriver post-teardown close" - {

        "closeHandle for a TLS handle after teardownRing runs closeNow inline, with no ring touch" in {
            PosixTestSockets.assumeUring()
            val depth     = math.max(256, kyo.net.ioPoolSize() * 64)
            val realUring = Ffi.load[IoUringBindings]
            val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
            val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
            if rc != 0 then
                realRing.close()
                throw Closed("IoUringDriverPostTeardownCloseTest", summon[Frame], s"queue_init failed: rc=$rc")
            val recording = RecordingIoUringBindings(realUring, realRing)
            val spy       = RecordingSocketBindings(Ffi.load[SocketBindings])
            val driver    = TestDrivers.forBindings(recording, realRing, spy)
            // No driver.start(): with no reap carrier, close() tears the ring down synchronously (teardownDone becomes true within this call).
            driver.close()
            PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                val handle    = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                val rawEngine = TlsRealEngines.singleEngine(isServer = true)
                val engine    = new RecordingTlsEngine(rawEngine)
                handle.tls = Present(engine)
                val keysBefore  = recording.submittedKeys.size
                val sendsBefore = recording.sendBufs.size
                driver.closeHandle(handle)
                discard(sock.close(client))
                assert(
                    spy.closeCounts.getOrDefault(accepted, 0) == 1,
                    s"closeNow must have closed the fd exactly once, counts=${spy.closeCounts}"
                )
                assert(engine.freeCount.get() == 1, s"the engine must be freed exactly once, was ${engine.freeCount.get()}")
                assert(
                    recording.submittedKeys.size == keysBefore,
                    "a post-teardown closeHandle must never submit a new SQE (no ring touch)"
                )
                assert(
                    recording.sendBufs.size == sendsBefore,
                    "a post-teardown closeHandle must never attempt a TLS close_notify send (no ring touch)"
                )
                succeed
            }
        }
    }

end IoUringDriverPostTeardownCloseTest
