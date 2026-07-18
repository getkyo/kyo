package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** Regression guard for the error-only readiness event handling in [[PollerIoDriver]], covering both directions it must get right.
  *
  * epoll's `EPOLLERR` / `EPOLLHUP` and kqueue's `EV_ERROR` / `EV_EOF` are set independently of read/write interest, so a peer reset or a
  * connect failure can produce a readiness event carrying ONLY the error bit (no read- or write-ready bit). One earlier bug DROPPED such an
  * event entirely (the [[PollerEvent]] decode ignored the error bits), so a genuine error hung the pending read / write until a later op failed.
  * The opposite over-correction then surfaced: failing the pending op on the BARE error bit, which a stale event for a recycled fd would
  * wrongly apply to a fresh connect on the SAME shared driver (the kyo-http concurrent-connect regression). The driver now reads `SO_ERROR` to
  * confirm a genuine pending error before failing: a real error (non-zero) fails the op, a healthy / still-connecting recycled fd (zero) drops
  * the event.
  *
  * These leaves drive the interleave deterministically with a [[RecordingPollerBackend]] over the real epoll/kqueue that injects ONE synthetic
  * error-only entry for the target fd (the single authorized injection), so the bare-error dispatch path runs against the real `getsockopt` from
  * `Ffi.load[SocketBindings]` which reads the real SO_ERROR from the kernel. Leaf 1 uses a real `loopbackPair` + `resetPeer` so SO_ERROR is a
  * genuine non-zero ECONNRESET; leaf 2 uses a still-connected `loopbackPair` whose SO_ERROR is 0.
  *
  * Gate: `PosixTestSockets.assumePoller()` (real loopback pair). The SO_ERROR != 0 assertion is cross-poller; the test does NOT assert the exact
  * value 104 (ECONNRESET is 54 on macOS, 104 on Linux) to remain cross-platform.
  *
  * Anti-flakiness: per-fd registration latches arm the injection only after both interests have executed on the change worker;
  * `Async.timeout(5.seconds)` is the deadlock ceiling for leaf 1 (the unfixed driver would hang); leaf 2 uses a 2s timeout that expires as the
  * PASS signal.
  *
  * Uses a `RecordingPollerBackend(PollerBackend.default())` with the authorized synthetic error-only injection. SO_ERROR is read from the
  * real kernel via `RecordingSocketBindings`, not a scripted value. Per-fd registration latches arm the injection only after both interests
  * have executed on the change worker.
  */
class PollerIoDriverErrorEventTest extends Test:

    import AllowUnsafe.embrace.danger

    "PollFlags decode" - {
        "an error flag is distinct from read/write, and can combine with them" in {
            // Verify the flag bit encoding used by the poll loop's drainReady.
            val errorOnly = PollFlags.Error
            assert((errorOnly & PollFlags.Read) == 0 && (errorOnly & PollFlags.Write) == 0 && (errorOnly & PollFlags.Error) != 0)
            // A read-ready event with an error bit also set: both flags present.
            val readWithError = PollFlags.Read | PollFlags.Error
            assert((readWithError & PollFlags.Read) != 0 && (readWithError & PollFlags.Error) != 0)
            succeed
        }
    }

    "PollerIoDriver error-only event" - {
        "fails the pending read with Closed and never strands the pending writable when SO_ERROR confirms a real error" in {
            PosixTestSockets.assumePoller()
            // A real loopbackPair + resetPeer produces a genuine ECONNRESET, so getsockopt(SO_ERROR) on the accepted fd reads a
            // real non-zero errno (104 on Linux, 54 on macOS). The RecordingPollerBackend over the real epoll/kqueue ALSO injects ONE synthetic
            // error-only entry for the fd (the authorized injection): a bare EPOLLERR/EV_ERROR the driver routes to dispatchError, which reads
            // the real non-zero SO_ERROR and fails the pending op.
            //
            // Real-vs-fake note: an error-ONLY event would fail BOTH the read and the writable via dispatchError. A
            // real reset socket is genuinely read- AND write-ready, so the real kernel also reports the write bit and the writable is delivered
            // its real readiness (dispatchWritable completes it Success; the subsequent write would then fail). dispatchError gates the pending
            // read removal and the pending writable removal on the SAME `soError != 0` check, so failing the read here exercises the
            // confirmed-real-error branch the writable-removal shares; leaf 2 exercises the complementary SO_ERROR == 0 drop. The writable
            // deterministically completes Success (200/200 empirically, and structurally: the synthetic error-only entry is always appended at
            // index n AFTER all real ready events, so a real write|error event routes to dispatchWritable before the synthetic bare-error entry
            // can run dispatchError; there is no constructible interleaving that yields Closed).
            //
            // Anti-flakiness: registeredRead/registeredWrite latch on the real registrations executing on the change worker, then the injection
            // is armed; Async.timeout(5.seconds) is only the deadlock ceiling (the unfixed driver dropped the event and hung).
            PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
                val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                val real     = PollerBackend.default()
                val pollerFd = real.create()
                val backend  = RecordingPollerBackend(real)
                val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
                val handle   = PosixHandle.socket(acceptedFd, PosixHandle.DefaultReadBufferSize, Absent)

                discard(driver.start())
                // Reset the peer so SO_ERROR is set on acceptedFd; the driver's real getsockopt reads the genuine non-zero errno.
                PosixTestSockets.resetPeer(spy, clientFd)

                val readPromise  = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                val writePromise = Promise.Unsafe.init[Unit, Abort[Closed]]()
                // Register both a pending read and a pending writable so the error dispatch has both in its tables.
                driver.awaitRead(handle, readPromise)
                driver.awaitWritable(handle, writePromise)

                for
                    // Wait until both interests have executed on the change worker, then arm the one-shot synthetic error-only injection.
                    _ <- backend.registeredRead(acceptedFd).safe.get
                    _ <- backend.registeredWrite(acceptedFd).safe.get
                    _ = backend.syntheticErrorFd.set(acceptedFd)
                    // The pending read must be failed Closed by the error dispatch (confirmed real SO_ERROR). Bounded so the unfixed driver
                    // (which dropped the event and left the read pending forever) fails fast with a timeout rather than hanging.
                    readOutcome  <- Abort.run[Timeout | Closed](Async.timeout(5.seconds)(readPromise.safe.get))
                    writeOutcome <- Abort.run[Timeout | Closed](Async.timeout(5.seconds)(writePromise.safe.get))
                    _ <- Sync.defer {
                        driver.close()
                        // Close the accepted fd (driver.close does not close socket fds; closeHandle would, but it wasn't called here).
                        PosixTestSockets.closePeerForEof(spy, acceptedFd)
                    }
                yield
                    readOutcome match
                        case Result.Failure(_: Closed) => succeed
                        case Result.Failure(_: Timeout) =>
                            fail("error event dropped: the pending read was never failed and hung")
                        case other => fail(s"unexpected read outcome: $other")
                    end match
                    writeOutcome match
                        // A real reset fd is genuinely write-ready: the real kernel delivers the write bit and dispatchWritable completes
                        // the pending writable Success before the synthetic error-only entry can reach dispatchError. This is deterministic
                        // (200/200 in an empirical probe; structural guarantee from the append-last ordering in RecordingDecorators).
                        case Result.Success(_) => succeed
                        case Result.Failure(_: Closed) =>
                            fail("a real reset fd is write-ready; the pending writable must complete Success, not Closed")
                        case Result.Failure(_: Timeout) =>
                            fail("error event dropped: the pending writable was stranded and hung")
                        case other => fail(s"unexpected write outcome: $other")
                    end match
                end for
            }
        }

        // Reproduction + regression guard for the kyo-http concurrent-connect failure: a closed connection's fd is recycled into a fresh connect
        // on the SAME shared driver, and a stale error-only event the kernel had queued for the dead connection is still in the poll batch when
        // the recycled fd already has the new connect's writable pending. An unguarded dispatchError would fail that writable on the bare error bit,
        // surfacing a connect that never failed as Closed ("PollerIoDriver ... is closed", wrapped as HttpConnectException). SO_ERROR is the
        // discriminator: a still-connecting / healthy recycled fd reads 0, so the event must be DROPPED and the connect's writable left to its
        // real readiness, NOT failed.
        "drops a stale error-only event (SO_ERROR == 0) instead of failing the pending read (recycled-fd connect guard)" in {
            PosixTestSockets.assumePoller()
            // A RecordingPollerBackend wraps the real epoll/kqueue. The accepted fd is a real, still-connected, idle socket, so
            // getsockopt(SO_ERROR) genuinely returns 0 and the real backend never fires a read event (no data). After read interest is
            // registered, the spy injects ONE synthetic error-only entry for the accepted fd (the single authorized injection): this is the
            // stale error a recycled fd's dead owner left in the poll batch. The driver's dispatchError reads the real SO_ERROR == 0 and must
            // DROP the event, leaving the pending read untouched. dispatchError gates BOTH the pendingReads and the pendingWritables removal on
            // the same `soError != 0` check, so exercising the drop via the pending read covers the writable drop the recycled-connect guard
            // protects (a real connected socket is genuinely writable, so a real writable event is not a stale event and cannot stand in here).
            //
            // Anti-flakiness: the 2s Timeout expiring with the read still pending IS the PASS signal (the unfixed driver completed it Closed).
            PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
                val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                val real     = PollerBackend.default()
                val pollerFd = real.create()
                val backend  = RecordingPollerBackend(real)
                val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
                val handle   = PosixHandle.socket(acceptedFd, PosixHandle.DefaultReadBufferSize, Absent)

                discard(driver.start())
                // Do NOT reset the peer; the accepted fd is still connected, so SO_ERROR == 0.

                val readPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                // Register read interest on the idle fd (no data -> the real backend never fires a read event on its own).
                driver.awaitRead(handle, readPromise)

                for
                    // Wait until read interest has executed on the change worker, then arm the one-shot synthetic error injection for the next
                    // poll cycle: the real getsockopt on the live fd returns 0, so the driver must drop the event.
                    _ <- backend.registeredRead(acceptedFd).safe.get
                    _ = backend.syntheticErrorFd.set(acceptedFd)
                    // The stale error-only event must NOT complete the read. Give the poll loop ample time to fire and (correctly) drop it;
                    // the bounded wait expiring with the read still pending is the PASS signal (the unfixed driver completed it Closed instead).
                    readOutcome <- Abort.run[Timeout | Closed](Async.timeout(2.seconds)(readPromise.safe.get))
                    _ <- Sync.defer {
                        driver.close()
                        PosixTestSockets.closePeerForEof(spy, clientFd)
                        PosixTestSockets.closePeerForEof(spy, acceptedFd)
                    }
                yield
                    readOutcome match
                        case Result.Failure(_: Timeout) => succeed // event dropped: the read is still pending, as it must be.
                        case Result.Failure(c: Closed) =>
                            fail(s"stale error-only event (SO_ERROR=0) failed the live read: ${c.getMessage}")
                        case other => fail(s"unexpected read outcome: $other")
                    end match
                end for
            }
        }
    }

end PollerIoDriverErrorEventTest
