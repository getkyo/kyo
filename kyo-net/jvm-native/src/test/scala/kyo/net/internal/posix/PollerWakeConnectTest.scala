package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.NetException
import kyo.net.Test

/** Deterministic guard for the poll-loop wakeup ([[PollerBackend.wake]] / [[PollerIoDriver.submitChange]]), guarding against the connect
  * write-readiness starvation that surfaces as a `NetConnectTimeoutException` in `TransportHandshakeTimeoutTest`.
  *
  * Background: with the FIFO drain poll-loop-authoritative, the poll loop is the single drainer
  * of the change FIFO, draining it once per cycle AFTER the `epoll_wait` / `kevent` park, which has no timeout. A change submitted while the
  * loop is mid-park (a connect's write-interest arm via `armSocketWritable` -> `submitChange(OpRegisterWrite)`) is registered with the kernel
  * only once the park returns. `submitChange` wakes the poll loop (epoll eventfd / kqueue EVFILT_USER), so a parked poll returns at once and
  * the change is registered and its readiness delivered promptly.
  *
  * These leaves pin the wakeup directly (the higher-level `TransportHandshakeTimeoutTest` exercises it under real connect timing): build a driver
  * over a real epoll/kqueue backend through a [[RecordingPollerBackend]] spy, START the poll loop so it parks, then arm a write-readiness
  * through the public connect path on an already-writable loopback fd. The wakeup must make the parked poll return and deliver the writable;
  * the spy's `wakeCount` proves the wake path ran on submit. A regression (no wake) leaves the writable undelivered, which the leaf timeout
  * fails. No sleep-as-synchronization.
  *
  * Gate: `PosixTestSockets.assumePoller()` (a real epoll/kqueue fd; io_uring uses a different driver and the NIO floor a different transport).
  */
class PollerWakeConnectTest extends Test:

    import AllowUnsafe.embrace.danger

    "the poll-loop wakeup delivers a connect write-readiness while the loop is parked" - {
        "awaitConnect on an already-writable fd completes promptly and triggers a wake" in {
            PosixTestSockets.assumePoller()
            PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
                val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                val real     = PollerBackend.default()
                val pollerFd = real.create()
                val backend  = RecordingPollerBackend(real)
                val driver   = TestDrivers.forBackend(backend, pollerFd, spy)

                // Start the poll loop: it arms the wakeup (registerWake) and parks in the real epoll_wait / kevent. A loopback fd is
                // immediately writable, so once the write interest reaches the kernel the poll returns it; the only question is whether the register
                // reaches the kernel at all, which takes the wake.
                discard(driver.start())

                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                val promise = Promise.Unsafe.init[Unit, Abort[Closed | NetException]]()

                // awaitConnect -> armSocketWritable -> submitChange(OpRegisterWrite); submitChange triggers backend.wake, cutting the park short so
                // the register runs and the (already-writable) fd's readiness is delivered. The wakeCount assertion is what pins the wake path.
                driver.awaitConnect(handle, promise)

                awaitOutcome(promise, s"the connect-writable was never delivered; wakeCount=${backend.wakeCount.get()}").map { outcome =>
                    driver.close()
                    PosixTestSockets.closePeerForEof(spy, clientFd)
                    PosixTestSockets.closePeerForEof(spy, acceptedFd)
                    assert(
                        outcome == Result.succeed(()),
                        s"the write-readiness must be delivered (the connect-writable wake), got $outcome"
                    )
                    assert(
                        backend.wakeCount.get() > 0,
                        s"submitChange must have triggered the poll-loop wake, wakeCount=${backend.wakeCount.get()}"
                    )
                }
            }
        }

        "many sequential connect arms are each delivered (the rapid-connect cadence that starved the deadline)" in {
            PosixTestSockets.assumePoller()
            PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
                val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                val real     = PollerBackend.default()
                val pollerFd = real.create()
                val backend  = RecordingPollerBackend(real)
                val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
                discard(driver.start())

                // Re-arm write-readiness on the same already-writable fd many times in sequence. This mirrors the
                // 30-rapid-connect cadence: every arm must be delivered promptly via the wake, not stranded behind a park.
                // The handle id is bumped each iteration so a prior cycle's stale writable cannot satisfy the next (mirrors a fresh connect fd).
                Loop(0) { i =>
                    if i >= 20 then Loop.done(i)
                    else
                        val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                        val promise = Promise.Unsafe.init[Unit, Abort[Closed | NetException]]()
                        driver.awaitConnect(handle, promise)
                        awaitOutcome(promise, s"iteration $i: the connect-writable was never delivered").map { outcome =>
                            assert(
                                outcome == Result.succeed(()),
                                s"iteration $i: the write-readiness must be delivered via the wake, got $outcome"
                            )
                            Loop.continue(i + 1)
                        }
                }.map { n =>
                    driver.close()
                    PosixTestSockets.closePeerForEof(spy, clientFd)
                    PosixTestSockets.closePeerForEof(spy, acceptedFd)
                    assert(n == 20, s"expected 20 delivered connect arms, completed $n")
                }
            }
        }
    }

end PollerWakeConnectTest
