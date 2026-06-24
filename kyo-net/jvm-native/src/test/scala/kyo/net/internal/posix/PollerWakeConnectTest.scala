package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test

/** Deterministic guard for the poll-loop wakeup ([[PollerBackend.wake]] / [[PollerIoDriver.submitChange]]), the fix for the connect
  * write-readiness starvation that surfaced as a `NetConnectTimeoutException` in `TransportHandshakeTimeoutTest`.
  *
  * Background: after the FIFO drain was made poll-loop-authoritative (the data-plane lost-wakeup fix), the poll loop became the single drainer
  * of the change FIFO, draining it once per cycle AFTER the bounded `epoll_wait` / `kevent` park. A change submitted while the loop is mid-park
  * (a connect's write-interest arm via `armSocketWritable` -> `submitChange(OpRegisterWrite)`) was not registered with the kernel until the
  * current park timed out (up to ~100ms, longer under load). A short connect deadline (`config.handshakeTimeout`) then fired before the register
  * landed, failing the connect even though the OS connect completed in microseconds. The fix wakes the poll loop on `submitChange` (epoll
  * eventfd / kqueue EVFILT_USER), so a parked poll returns at once and the change is registered and its readiness delivered promptly.
  *
  * These leaves pin the wakeup directly (the higher-level `TransportHandshakeTimeoutTest` exercises it under real connect timing): build a driver
  * over a real epoll/kqueue backend through a [[RecordingPollerBackend]] spy, START the poll loop so it parks in the real bounded wait, then arm
  * a write-readiness through the public connect path on an already-writable loopback fd. The wakeup must make the parked poll return and deliver
  * the writable; the spy's `wakeCount` proves the wake path ran on submit. A bounded `Async.timeout` guard turns a regression (no wake, the
  * writable starved past the guard) into a failure rather than a hang. No sleep-as-synchronization.
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

                // Start the poll loop: it arms the wakeup (registerWake) and parks in the real bounded epoll_wait / kevent. A loopback fd is
                // immediately writable, so once the write interest reaches the kernel the poll returns it; the only question is whether the register
                // reaches the kernel promptly (with the wake) or only after the bounded park (without it).
                discard(driver.start())

                val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val promise = Promise.Unsafe.init[Unit, Abort[Closed]]()

                // awaitConnect -> armSocketWritable -> submitChange(OpRegisterWrite); submitChange triggers backend.wake, cutting the park short so
                // the register runs and the (already-writable) fd's readiness is delivered. Without the wake the writable still arrives, but only
                // after the bounded park; the bounded guard below would still pass, so the wakeCount assertion is what pins the wake path.
                driver.awaitConnect(handle, promise)

                Abort.run[Timeout](Async.timeout(5.seconds)(Abort.run[Closed](promise.safe.get))).map { outcome =>
                    driver.close()
                    PosixTestSockets.closePeerForEof(spy, clientFd)
                    PosixTestSockets.closePeerForEof(spy, acceptedFd)
                    assert(
                        outcome == Result.succeed(Result.succeed(())),
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

                // Re-arm write-readiness on the same already-writable fd many times in sequence, each bounded by a generous guard. This mirrors the
                // 30-rapid-connect cadence of the issue #243 leaf: every arm must be delivered promptly via the wake, not stranded behind a park.
                // The handle id is bumped each iteration so a prior cycle's stale writable cannot satisfy the next (mirrors a fresh connect fd).
                Loop(0) { i =>
                    if i >= 20 then Loop.done(i)
                    else
                        val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                        val promise = Promise.Unsafe.init[Unit, Abort[Closed]]()
                        driver.awaitConnect(handle, promise)
                        Abort.run[Timeout](Async.timeout(5.seconds)(Abort.run[Closed](promise.safe.get))).map { outcome =>
                            assert(
                                outcome == Result.succeed(Result.succeed(())),
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
