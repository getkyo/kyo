package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test

/** Deterministic reproduction + regression guard for the close-during-poll scratch ownership violation in [[PollerIoDriver]].
  *
  * The poll loop carrier uses the per-driver [[PollScratch]] on every cycle inside `backend.poll(pollerFd, 100, pollScratch)`: the bounded
  * `epoll_wait` / `kevent` holds the scratch's off-heap buffer in use for the whole native wait, and the loop only re-checks the closed flag
  * BETWEEN polls. `close()` runs on a different carrier; it must not free a scratch buffer the poll loop is still using mid-cycle. Freeing it
  * then closes the shared off-heap arena while the native wait still has it acquired, which surfaces on JVM as
  * `java.lang.IllegalStateException: Session is acquired by 1 clients` and, on any platform, as a use-after-free if the loop touches the freed
  * buffer after `close()`.
  *
  * The interleave is forced deterministically with a [[RecordingPollerBackend]] over the real epoll/kqueue whose first `poll` fires a one-shot
  * pre-poll latch (latch A: "the poll loop now owns the scratch and is inside the spy's poll()") and then returns a test-controlled pending
  * fiber (latch B) INSTEAD of delegating to the real `epoll_wait` / `kevent` for that one cycle. The poll carrier therefore parks inside the
  * spy's poll() holding the scratch, provably NOT inside a real blocking syscall: this matters because `driver.close()` closes the poller fd,
  * which would WAKE a real in-flight `epoll_wait` and collapse the ownership window. Parking on latch B instead keeps the scratch owned across
  * the close. The test calls `driver.close()` while parked and asserts the scratch buffer is still open: a correct design defers the free to the
  * poll loop's own terminal exit (the loop frees the buffer exactly once when it is provably not in use), so close() never frees a scratch the
  * loop still owns. Completing latch B then lets the loop re-enter and free the scratch at its terminal exit. The substitution of one poll cycle
  * by latch B is the authorized controlled injection for this test (a real `epoll_wait` cannot be held in a Scala-observable parked state). No
  * real sockets are needed: the poll loop runs over an empty real poller.
  *
  * Uses a `RecordingPollerBackend(PollerBackend.default())` with the one-shot prePollLatch (A) + prePollHold (B) to hold the poll loop mid-cycle
  * while the test calls `driver.close()`, confirming the scratch-ownership assertion.
  */
class PollerIoDriverCloseDuringPollTest extends Test:

    import AllowUnsafe.embrace.danger

    "PollerIoDriver close during an in-flight poll" - {
        "close does not free the poll scratch while the poll loop still owns it mid-cycle" in {
            PosixTestSockets.assumePoller()
            // The poll loop enters the spy's poll() (owning the scratch), fires latch A, and parks on latch B (a test-controlled pending fiber)
            // instead of delegating to the real epoll_wait/kevent. With the scratch provably in use AND no real blocking syscall in flight,
            // close() must NOT free it: the buffer must still be open right after close() returns. A close() that freed the scratch mid-cycle
            // would close the shared off-heap arena while the poll loop still has it, which is the ownership violation this guards against.
            //
            // Anti-flakiness: latch A (prePollLatch) and latch B (prePollHold) are real Promise.Unsafe values; the carrier parks on B
            // deterministically until the test completes it. No sleep, and no real epoll_wait that close() could race to wake.
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val backend  = RecordingPollerBackend(real)
            val latchA   = Promise.Unsafe.init[Unit, Any]()
            val latchB   = Promise.Unsafe.init[Int, Any]()
            backend.setPrePollLatch(latchA)
            backend.setPrePollHold(latchB)
            val driver = TestDrivers.forBackend(backend, pollerFd, spy)
            discard(driver.start())

            // Latch A: the poll loop is now inside the spy's poll() holding the scratch and parked on latch B.
            latchA.safe.get.map { _ =>
                // close() runs on this carrier while the poll loop is parked mid-cycle (holding the scratch) on its own carrier.
                driver.close()
                // The poll loop still owns the scratch (it is parked inside the spy's poll()), so the buffer must NOT have been freed by close().
                val scratchOpenAfterClose = backend.lastScratch != null && !backend.lastScratch.eventsBuffer.isClosed
                // Release latch B: the loop re-enters, sees the closed flag, exits, and frees the scratch at its terminal exit.
                latchB.completeDiscard(Result.succeed(0))
                assert(
                    scratchOpenAfterClose,
                    "close() freed the poll scratch while the poll loop still owned it mid-cycle (ownership violation)"
                )
                succeed
            }
        }

        "listener close cannot free its fd before a staged accept registration reaches the kernel" in {
            if !PosixConstants.isMacOrBsd then cancel("kqueue is macOS/BSD-only")
            val spy       = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real      = PollerBackend.default()
            val pollerFd  = real.create()
            val backend   = RecordingPollerBackend(real)
            val driver    = TestDrivers.forBackend(backend, pollerFd, spy)
            val transport = TestTransports.forTesting(driver, spy, backendIsEpoll = false)
            val entered   = Promise.Unsafe.init[Unit, Any]()
            val heldPoll  = Promise.Unsafe.init[Int, Any]()
            backend.setPrePollLatch(entered)
            backend.setPrePollHold(heldPoll)
            Scope.ensure(Sync.defer(driver.close())).andThen {
                // Listen before starting the driver so its first held poll owns the real accept registration.
                transport.listen("127.0.0.1", 0, 4)(_ => ()).safe.get.map { listener =>
                    val stopped = driver.start()
                    Scope.ensure {
                        Sync.defer {
                            listener.close()
                            driver.close()
                            heldPoll.completeDiscard(Result.succeed(0))
                        }.andThen(stopped.safe.get)
                    }.andThen {
                        entered.safe.get.map { _ =>
                            val scratch = backend.lastScratch
                            val kq      = scratch.kqueueData.get
                            assert(kq.nChanges == 1, s"expected one staged accept registration, got ${kq.nChanges}")
                            val listenerFd = KEvent.ident(kq.changelistBuf, 0).toInt
                            val ownerId    = KEvent.udata(kq.changelistBuf, 0)
                            assert(KEvent.filter(kq.changelistBuf, 0) == PosixConstants.EVFILT_READ)
                            // Exercise the actual listener callback, which owns shutdown/close through driver.closeListener. The poll
                            // remains suspended, so no queued cancellation can run before this carrier submits the staged changelist.
                            listener.close()
                            real.poll(pollerFd, 0, kq.changelistBuf, kq.nChanges, scratch).safe.get.map { n =>
                                val errors = (0 until n).filter(i =>
                                    scratch.ids(i) == ownerId && (scratch.flags(i) & PollFlags.Error) != 0
                                ).map(i => KEvent.data(kq.eventsBuffer, i)).toList
                                heldPoll.completeDiscard(Result.succeed(n))
                                assert(
                                    errors == List.empty[Long],
                                    s"poll submitted accept interest after its listener fd was freed: errno=$errors"
                                )
                                driver.close()
                                stopped.safe.get.map { _ =>
                                    assert(
                                        spy.closeCounts.getOrDefault(listenerFd, 0) == 1,
                                        s"the listener fd must close exactly once, got ${spy.closeCounts}"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        "a driver created but never started frees the poll scratch on close (no leak)" in {
            // The poll loop never runs, so the poll-loop-owned free never fires. close() must free the scratch directly in that case, since no
            // loop is using it. The freeScratchOnce CAS ensures this never double-frees with the loop's terminal free.
            PosixTestSockets.assumePoller()
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            // Force the scratch to be allocated (forBackend allocates it eagerly via newPollScratch), then close without ever starting.
            val scratch = backend.lastScratch
            assert(scratch != null, "the driver must have allocated the poll scratch eagerly")
            // Note: start() is never called.
            driver.close()
            assert(scratch.eventsBuffer.isClosed, "close() on a never-started driver must free the poll scratch directly (no leak)")
            succeed
        }
    }

end PollerIoDriverCloseDuringPollTest
