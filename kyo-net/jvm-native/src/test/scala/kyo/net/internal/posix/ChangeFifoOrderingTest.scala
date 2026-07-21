package kyo.net.internal.posix

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** Deterministic guard for the per-driver change FIFO ordering invariant (`PollerIoDriver.submitChange` / `drainChanges`, drained by one worker
  * in submission order): `epoll_ctl` and kqueue's `EV_ADD` / `EV_DELETE` are last-write-wins per fd+filter, so a deregister that executes AFTER
  * a re-arm deletes the freshly-armed interest and strands the fd. This is the documented STARTTLS deadlock: an upgrade `cancel`s the plaintext
  * pump's fd (a deregister) and immediately re-arms the same fd for the handshake read; if the deregister landed after the re-arm, the handshake
  * interest is gone and the fd never reports ready again.
  *
  * The existing coverage ([[PollerIoDriverStandingReadTest]], the real-STARTTLS upgrade test) is probabilistic (it conflates a lost re-arm with
  * a reordered one) or a full-stack integration. Neither asserts that a deregister submitted BEFORE a re-arm cannot execute after it. These
  * leaves drive the change FIFO directly through a RecordingPollerBackend over the real epoll/kqueue and assert the recorded execution order is
  * exactly submission order.
  *
  * Gate: `PosixTestSockets.assumePoller()` (real loopback pair for real fd).
  *
  * Anti-flakiness: `backend.registeredRead(targetFd).safe.get` latches on the actual `registerRead` execution (a real
  * Promise.Unsafe completed by the change-FIFO worker). No sleep.
  *
  * Uses a `RecordingPollerBackend(PollerBackend.default())` over the real epoll/kqueue: it observes the change-execution order and per-fd
  * registration latches while delegating each change to the real backend. Socket operations use `RecordingSocketBindings` over a real
  * `loopbackPair`. The FIFO order log assertion confirms submission order is preserved.
  */
class ChangeFifoOrderingTest extends Test:

    import AllowUnsafe.embrace.danger

    "change FIFO ordering (deregister before rearm)" - {
        "a deregister submitted before a re-arm for the same fd executes before it (submission order, not reordered)" in {
            PosixTestSockets.assumePoller()
            // The STARTTLS sequence: cancel(handle) submits a deregister for the fd, then awaitRead submits a registerRead for the SAME fd. The
            // single change worker must run them in submission order, so the deregister precedes the register and cannot delete the freshly-armed
            // read interest. A RecordingPollerBackend over the real epoll/kqueue captures the execution order while delegating each change to the
            // real backend; the test synchronizes on the second change being recorded.
            //
            // Anti-flakiness: backend.registeredRead(targetFd).safe.get latches on the real registerRead execution. No sleep.
            PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
                val targetFd = acceptedFd
                val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                val real     = PollerBackend.default()
                val pollerFd = real.create()
                val backend  = RecordingPollerBackend(real)
                val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
                // The change FIFO is now drained only on the poll-loop carrier (submitChange enqueues; drainChanges runs from drainFifos on the poll
                // loop), so the poll loop MUST run for a submitted change to execute. It bounded-waits on the poller fd (no fds registered) and drains
                // the change queue each cycle; close() signals it to exit.
                discard(driver.start())
                val handle = PosixHandle.socket(targetFd, PosixHandle.DefaultReadBufferSize, Absent)

                // Submit deregister (cancel) THEN register (awaitRead) for the same fd, in that order. The registerRead is the SECOND change, so its
                // execution promise completing means both changes have run; the test then reads the final order with no sleep.
                driver.cancel(handle)
                val readPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                driver.awaitRead(handle, readPromise)

                backend.registeredRead(targetFd).safe.get.map { _ =>
                    driver.close()
                    // driver.close() does NOT close the socket fds; close both ends explicitly.
                    PosixTestSockets.closePeerForEof(spy, clientFd)
                    PosixTestSockets.closePeerForEof(spy, acceptedFd)
                    val log     = backend.callLog
                    val deregIx = log.indexOf(s"deregister($targetFd, fdClosing=false)")
                    val regIx   = log.indexOf(s"registerRead($targetFd)")
                    assert(deregIx >= 0, s"the deregister (fdClosing=false) must have executed: $log")
                    assert(regIx >= 0, s"the registerRead must have executed: $log")
                    assert(
                        deregIx < regIx,
                        s"the deregister (submitted first) must execute before the re-arm (submitted second): $log"
                    )
                }
            }
        }

        "the change worker runs one change to completion before the next (single owner, JVM)" in {
            // Single-owner proof: pin the change worker INSIDE the first change (a registerRead that blocks on a CountDownLatch the test releases,
            // NOT a sleep), submit a second change from a separate fiber, and assert the second has NOT executed while the first holds the worker.
            // Release the latch; the second then runs. JVM-only because a blocked carrier needs a second carrier to make progress.
            if !kyo.internal.Platform.isJVM then Sync.defer(succeed)
            else
                PosixTestSockets.assumePoller()
                PosixTestSockets.loopbackPair().map { case (clientFd1, acceptedFd1) =>
                    PosixTestSockets.loopbackPair().map { case (clientFd2, acceptedFd2) =>
                        val firstFd      = acceptedFd1
                        val secondFd     = acceptedFd2
                        val gate         = new CountDownLatch(1)
                        val firstEntered = Promise.Unsafe.init[Unit, Any]()
                        val started      = new AtomicBoolean(false)
                        val spy          = RecordingSocketBindings(Ffi.load[SocketBindings])
                        val real         = PollerBackend.default()
                        val pollerFd     = real.create()
                        val backend      = RecordingPollerBackend(real)
                        // The FIRST registerRead pins the change worker until the test releases the gate. onRegisterRead runs at the start of
                        // registerRead, before counting / recording / delegating.
                        backend.onRegisterRead = fd =>
                            if fd == firstFd && started.compareAndSet(false, true) then
                                firstEntered.completeDiscard(Result.succeed(()))
                                gate.await()
                        val driver = TestDrivers.forBackend(backend, pollerFd, spy)
                        // The change FIFO is drained only on the poll-loop carrier, so start the poll loop: the FIRST registerRead runs on it and the
                        // latch inside onRegisterRead pins that carrier (the single change consumer), exactly the pin this leaf needs. close() exits it.
                        discard(driver.start())
                        val firstH  = PosixHandle.socket(firstFd, PosixHandle.DefaultReadBufferSize, Absent)
                        val secondH = PosixHandle.socket(secondFd, PosixHandle.DefaultReadBufferSize, Absent)

                        val firstPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        driver.awaitRead(firstH, firstPromise)
                        for
                            _ <- firstEntered.safe.get
                            // The worker is now pinned inside the first change. Submit the second change from a separate fiber (genuinely concurrent).
                            secondPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                            submit <- Fiber.initUnscoped(Sync.defer(driver.awaitRead(secondH, secondPromise)))
                            _      <- submit.get
                            // While the first change holds the worker, the second must NOT have executed (single owner; no second worker).
                            // Snapshot the state observed under the pin, then release the gate BEFORE asserting: a failed assertion must
                            // never leave the pinned change worker parked (it would wedge every later test on this scheduler).
                            logUnderPin = backend.callLog
                            _           = gate.countDown()
                            _ = assert(
                                !logUnderPin.contains(s"registerRead($secondFd)"),
                                s"the second change ran while the first held the worker: $logUnderPin"
                            )
                            // The worker now drains the second change; synchronize on its execution promise (no sleep), then assert the order.
                            _ <- backend.registeredRead(secondFd).safe.get
                        yield
                            driver.close()
                            // driver.close() does NOT close socket fds; close all four ends.
                            PosixTestSockets.closePeerForEof(spy, clientFd1)
                            PosixTestSockets.closePeerForEof(spy, acceptedFd1)
                            PosixTestSockets.closePeerForEof(spy, clientFd2)
                            PosixTestSockets.closePeerForEof(spy, acceptedFd2)
                            val log      = backend.callLog
                            val firstIx  = log.indexOf(s"registerRead($firstFd)")
                            val secondIx = log.indexOf(s"registerRead($secondFd)")
                            assert(firstIx >= 0 && secondIx >= 0, s"both changes must execute: $log")
                            assert(firstIx < secondIx, s"the first change must complete before the second runs: $log")
                        end for
                    }
                }
        }
    }

end ChangeFifoOrderingTest
