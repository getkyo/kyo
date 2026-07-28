package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test

/** Deterministic reproduction + regression guard for the wake-fd close-vs-wake race in [[PollerIoDriver]] (the lazyFdDelete cross-fd stale-event
  * failure).
  *
  * [[PollerBackend.wake]] (epoll: `eventfd_write` on the wakeup eventfd) runs on ARBITRARY carriers (any `submitChange` from awaitRead / connect /
  * deregister), while [[PollerBackend.closeWake]] (epoll: `close()` of that eventfd) runs on the poll-loop carrier's terminal exit. With no
  * coordination a wake that has read the wake fd but not yet written it can be preempted while closeWake closes that fd; the OS then recycles the
  * freed number into ANOTHER driver's freshly-opened socket, and the resumed `eventfd_write` writes the 8-byte counter (1) INTO that recycled
  * socket. The peer recv's a phantom `[1,0,0,0,0,0,0,0]` ahead of its real data, which is exactly what
  * [[PollerIoDriverEdgeTriggeredTest]]'s `lazyFdDelete` leaf guards against.
  *
  * closeWake is gated behind an in-flight-wake guard so the eventfd is never closed while a wake holds it (its number cannot then be recycled
  * out from under an `eventfd_write`). This leaf pins that invariant directly rather than under load: it forces the exact interleaving with a
  * [[RecordingPollerBackend]] whose `onWakeEnter` hook fires synchronously INSIDE an in-flight wake (the wake guard holder count is 1 at that
  * moment) and, from there, calls `driver.close()` to drive the poll loop into its terminal `closeWake`. A correct guard defers the close until the
  * in-flight wake releases, so `closeWake` never runs while a wake is mid-flight: the spy's `closeWakeWhileWaking` flag must stay false and the
  * eventfd is closed exactly once. Without the guard the close runs during the parked wake and the flag trips. No sleep: the test awaits the spy's
  * `closeWakeDone` real-event latch.
  *
  * Gate: `PosixTestSockets.assumePoller()` (a real epoll/kqueue fd). The race has a backend-specific shape but one guard. On epoll closeWake closes
  * the wakeup eventfd, whose freed number can be recycled. On kqueue there is no wake fd (the EVFILT_USER filter is released with the kqueue fd), but
  * `wake()` reads `scratch.wakeArmBuf` (the NOTE_TRIGGER changelist `registerWake` pre-encoded once) as the `keventNow` changelist on an arbitrary
  * carrier, so closeWake frees that buffer as the guard's terminal action; without the guard `PollScratch.close` freed it under an in-flight wake, a
  * use-after-close ("Buffer is closed"). The leaf pins the invariant on both backends: closeWake never runs while a wake is in flight.
  */
class PollerWakeCloseRaceTest extends Test:

    import AllowUnsafe.embrace.danger

    "closeWake never runs while a wake is in flight (the wake-fd recycle race)" in {
        PosixTestSockets.assumePoller()
        PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            discard(driver.start())

            // Create the closeWake latch before arming so it is ready when the driver's terminal exit closes the wake fd.
            val closeWakeDone = backend.closeWakeDone()

            // When the first wake fires, we are running synchronously INSIDE that wake (wakeInFlight == 1, and the driver's wake guard holder count is
            // 1). Hold the wake mid-flight while we drive the poll loop into its terminal exit, where it must close the wake fd:
            //   1. driver.close() sets the closed flag, submits the teardown, and wakes the parked poll. close() runs on THIS (the wake) carrier and
            //      returns at once; the poll-loop carrier then exits and reaches freeScratch independently.
            //   2. Spin (bounded, test-only) until the poll-loop carrier has freed the scratch (eventsBuffer closed): that is the point past which the
            //      wake fd has been handled. The guarded path: freeScratch's closeWakeGuarded DEFERS the close because this wake still holds the guard, so
            //      closeWake never runs while we are here (the held wake's release runs it afterward, with wakeInFlight back to 0). An unguarded path would
            //      call backend.closeWake directly BEFORE closing the buffer, so by the time the buffer is closed closeWake would have already
            //      run with wakeInFlight == 1 and the spy's closeWakeWhileWaking flag would have tripped. Either way the buffer-closed condition is the
            //      deterministic release point, and the assertion afterward distinguishes the two.
            // The spin is bounded by a generous cap so a regression that wedges surfaces as the cap rather than a hang; Thread.onSpinWait is a hint,
            // not a thread-block (no sleep, no park).
            backend.onWakeEnter = () =>
                driver.close()
                var spins = 0
                while spins < 50_000_000 && {
                        val s = backend.lastScratch
                        s == null || !s.eventsBuffer.isClosed
                    }
                do
                    Thread.onSpinWait()
                    spins += 1
                end while

            // Arm write-readiness through the public connect path: awaitConnect -> armSocketWritable -> submitChange -> triggerWake -> backend.wake,
            // which fires onWakeEnter. A loopback fd is immediately writable, so the wake path is the one under test (not the readiness delivery).
            val handle  = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
            val promise = Promise.Unsafe.init[Unit, Abort[Closed]]()
            driver.awaitConnect(handle, promise)

            // Synchronize on the wake fd actually being closed (the driver's terminal exit ran closeWake). Bounded so a regression that never closes
            // surfaces as a timeout rather than a hang. After it fires the close-vs-wake race has fully resolved.
            Abort.run[Timeout](Async.timeout(5.seconds)(closeWakeDone.safe.get)).map { outcome =>
                PosixTestSockets.closePeerForEof(spy, clientFd)
                PosixTestSockets.closePeerForEof(spy, acceptedFd)
                assert(outcome.isSuccess, s"the wake fd must be closed at the driver's terminal exit (closeWake must run): $outcome")
                assert(
                    !backend.closeWakeWhileWaking.get(),
                    "closeWake ran while a wake was in flight: the wake fd can be closed and recycled out from under an in-flight eventfd_write " +
                        "(the lazyFdDelete cross-fd stale-event race)"
                )
                assert(
                    backend.closeWakeCount.get() == 1,
                    s"the wake fd must be closed exactly once across the close paths; got ${backend.closeWakeCount.get()}"
                )
            }
        }
    }

end PollerWakeCloseRaceTest
