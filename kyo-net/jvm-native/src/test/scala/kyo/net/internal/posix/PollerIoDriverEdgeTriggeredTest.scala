package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test

/** Behavioral pins for edge-triggered (ET) registration in [[PollerIoDriver]].
  *
  * Each leaf exercises one aspect of the ET model that differs from the old EPOLLONESHOT / EV_ONESHOT model:
  *
  *   - `drainsToEagain`: confirms that when `PollFlags.Eof` fires alongside data, the dispatch loop delivers all buffered bytes via multiple
  *     `awaitRead` cycles and then surfaces the half-close as `Span.empty`, never a `Closed` failure.
  *   - `syscallCountConstant`: confirms that N reads on an ET-armed fd produce exactly N `registerRead` calls (N from application awaitRead
  *     calls, none from the abolished rearmSurvivors path). Under EPOLLONESHOT, N reads produced 2N calls (N application + N survivor re-arm).
  *   - `halfCloseDrainsRemaining`: confirms that a peer half-close arriving simultaneously with buffered bytes drains the bytes first (the
  *     `eofPending=true` drain-to-EAGAIN path in `dispatchReadPlain`) and then delivers `Span.empty` without a spurious Closed.
  *   - `changelistBatchingNoDeadlock`: confirms that on kqueue (where `disableWrite` batches EV_DISABLE into the changelist), both write
  *     promises resolve, registerWrite fires exactly twice, and at least one poll cycle carried a non-empty changelist (batch path active).
  *   - `lazyFdDelete`: confirms that `closeHandle` produces a deregister log entry with `fdClosing=true` (kqueue skips EV_DELETE; the OS
  *     auto-removes filters on close), while a live-fd cancel would produce `fdClosing=false` (EV_DELETE executes to prevent stale events).
  *     Extends to a stale-event witness: a new socket reusing the old fd number receives no phantom readiness from the closed filter.
  *   - `wakeLatencyBounded`: confirms that a `awaitRead` submitted while the poll loop is parked wakes the loop (via `backend.wake`) and
  *     delivers the readiness promptly, not just after the 100ms timeout.
  *
  * Gate: `PosixTestSockets.assumePoller()` for all leaves (real epoll/kqueue fd required). No leaf sleeps; all synchronize on real promises.
  */
class PollerIoDriverEdgeTriggeredTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Send all bytes of `data` on `fd` in a loop past short writes. */
    private def sendAll(fd: Int, data: Array[Byte])(using Frame): Unit < Async =
        Loop(0) { sent =>
            if sent >= data.length then Loop.done(())
            else
                val rest = java.util.Arrays.copyOfRange(data, sent, data.length)
                val buf  = Buffer.fromArray[Byte](rest)
                Sync.ensure(Sync.defer(buf.close())) {
                    sock.send(fd, buf, rest.length.toLong, PosixConstants.MSG_NOSIGNAL).safe.get.map { r =>
                        val n = r.value.toInt
                        if n <= 0 then Loop.done(())
                        else Loop.continue(sent + n)
                    }
                }
        }
    end sendAll

    /** Collect all chunks from successive awaitRead calls on `handle` until an empty Span or a Closed failure. Returns accumulated bytes. */
    private def collectUntilEof(
        driver: PollerIoDriver,
        handle: PosixHandle
    )(using Frame): Array[Byte] < (Abort[Closed] & Async) =
        val acc = new java.io.ByteArrayOutputStream
        Loop(()) { _ =>
            val p = Promise.Unsafe.init[Span[Byte], Abort[Closed]]()
            driver.awaitRead(handle, p)
            Abort.run[Timeout](Async.timeout(10.seconds)(Abort.run[Closed](p.safe.get))).map {
                case Result.Success(Result.Success(span: Span[Byte] @unchecked)) =>
                    if span.isEmpty then Loop.done(acc.toByteArray)
                    else
                        acc.write(span.toArrayUnsafe)
                        Loop.continue(())
                    end if
                case Result.Success(Result.Failure(closed: Closed)) =>
                    Abort.fail(closed)
                case _ =>
                    Abort.fail(Closed("PollerIoDriverEdgeTriggeredTest", summon[Frame], "awaitRead timed out or panicked"))
            }
        }
    end collectUntilEof

    "drainsToEagain: ET dispatch delivers multiple awaitRead chunks then surfaces EOF" in {
        PosixTestSockets.assumePoller()
        // Send a payload that exceeds the read buffer size (DefaultReadBufferSize = 8192) several times over so a single
        // readiness event cannot be consumed by one recvNow call. The drain-to-EAGAIN loop must keep calling recvNow until
        // the kernel returns EAGAIN before re-arming; a regression to the one-shot model would surface the first chunk and
        // silently stop without draining to EAGAIN, leaving bytes in the kernel and never delivering EOF.
        val readBufSize = PosixHandle.DefaultReadBufferSize
        val payloadSize = readBufSize * 4 // 4 recvNow calls at minimum; forces multiple-chunk drain
        PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            discard(driver.start())

            val payload = Array.tabulate[Byte](payloadSize)(i => (i % 251).toByte)
            val handle  = PosixHandle.socket(acceptedFd, readBufSize, Absent)

            sendAll(clientFd, payload).map { _ =>
                PosixTestSockets.halfClose(spy, clientFd)
                Abort.run[Closed](collectUntilEof(driver, handle)).map { result =>
                    driver.close()
                    PosixTestSockets.closePeerForEof(spy, clientFd)
                    PosixTestSockets.closePeerForEof(spy, acceptedFd)
                    result match
                        case Result.Success(bytes) =>
                            assert(bytes.length == payload.length, s"byte count must match: got ${bytes.length} of ${payload.length}")
                            assert(
                                bytes.toList == payload.toList,
                                s"byte content must match exactly (drain-to-EAGAIN must not lose or reorder bytes)"
                            )
                        case Result.Failure(closed) =>
                            fail(s"reader got Closed instead of EOF: $closed")
                        case other =>
                            fail(s"unexpected result: $other")
                    end match
                }
            }
        }
    }

    "syscallCountConstant: ET keeps fd armed so registerRead fires N times for N sequential reads, not 2N (EPOLLONESHOT would double)" in {
        PosixTestSockets.assumePoller()
        // Syscall-count contrast: EPOLLONESHOT vs EPOLLET.
        //
        // Under EPOLLONESHOT (old model), each readiness event disarmed the fd; after each read the driver re-armed for the next read
        // (RegisterRead) AND ran rearmSurvivors to keep the surviving direction armed (a second RegisterRead per event). N reads = 2N
        // registerRead calls: N from the application awaitRead submissions, N from the rearmSurvivors survivor re-arm path.
        //
        // Under EPOLLET / EV_CLEAR (ET, current model) the fd stays armed in the kernel between reads; no survivor re-arm is issued.
        // N reads = exactly N registerRead calls: one per application-level awaitRead, none from rearmSurvivors (which is gone). This
        // is the R=N vs R'=2N distinction: edge-triggered halves the interest-change syscall count for the steady read path.
        //
        // Assertions: readCount == n (ET count) AND readCount < 2*n (strictly below the EPOLLONESHOT count). Both are needed: the
        // first pins the ET invariant, the second pins the improvement margin (a regression to 2N would satisfy neither).
        PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            discard(driver.start())

            val handle = PosixHandle.socket(acceptedFd, PosixHandle.DefaultReadBufferSize, Absent)
            val n      = 5

            // Perform n sequential single-byte reads. The client sends one byte before each read so the fd is ready.
            Loop(0) { i =>
                if i >= n then Loop.done(i)
                else
                    val sendBuf = Buffer.fromArray[Byte](Array[Byte]((i % 127).toByte))
                    sock.send(clientFd, sendBuf, 1L, PosixConstants.MSG_NOSIGNAL).safe.get.map { _ =>
                        sendBuf.close()
                        val p = Promise.Unsafe.init[Span[Byte], Abort[Closed]]()
                        driver.awaitRead(handle, p)
                        Abort.run[Timeout](Async.timeout(5.seconds)(p.safe.get)).map {
                            case Result.Success(_) => Loop.continue(i + 1)
                            case other             => fail(s"read $i failed: $other")
                        }
                    }
            }.map { completed =>
                driver.close()
                PosixTestSockets.closePeerForEof(spy, clientFd)
                PosixTestSockets.closePeerForEof(spy, acceptedFd)
                assert(completed == n, s"expected $n reads, completed $completed")
                val readCount    = backend.registerReadCount.get()
                val oneshotCount = 2 * n // what EPOLLONESHOT+rearmSurvivors would have produced
                // ET: exactly N registerRead calls, not the 2N the old EPOLLONESHOT model required.
                assert(
                    readCount == n,
                    s"ET: registerRead must fire exactly N=$n times (one per awaitRead, fd stays armed); got $readCount"
                )
                assert(
                    readCount < oneshotCount,
                    s"ET must use fewer interest-change syscalls than EPOLLONESHOT (N=$n < 2N=$oneshotCount); got $readCount"
                )
                assert(
                    !backend.callLog.exists(_.startsWith("rearm(")),
                    s"ET must produce no rearm() calls; rearm was the EPOLLONESHOT survivor re-arm path: ${backend.callLog}"
                )
            }
        }
    }

    "halfCloseDrainsRemaining: bytes buffered at the time of peer half-close are delivered before Span.empty" in {
        PosixTestSockets.assumePoller()
        // This leaf is the ET-specific variant of PollerIoDriverHalfCloseTest: it confirms the eofPending=true branch in dispatchReadPlain
        // drains bytes first. The peer sends data AND immediately half-closes so both arrive in the kernel before the first awaitRead.
        PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            discard(driver.start())

            val payload = Array.tabulate[Byte](2048)(i => (i % 127).toByte)
            val handle  = PosixHandle.socket(acceptedFd, PosixHandle.DefaultReadBufferSize, Absent)

            // Queue data then half-close before the first awaitRead: the kernel delivers data+EOF together.
            sendAll(clientFd, payload).map { _ =>
                PosixTestSockets.halfClose(spy, clientFd)
                Abort.run[Closed](collectUntilEof(driver, handle)).map { result =>
                    driver.close()
                    PosixTestSockets.closePeerForEof(spy, clientFd)
                    PosixTestSockets.closePeerForEof(spy, acceptedFd)
                    result match
                        case Result.Success(bytes) =>
                            assert(
                                bytes.toList == payload.toList,
                                s"bytes before half-close must be fully delivered, EOF confirmed: got ${bytes.length} of ${payload.length}"
                            )
                        case Result.Failure(closed) =>
                            fail(s"half-close surfaced Closed instead of draining bytes + Span.empty: $closed")
                        case other =>
                            fail(s"unexpected result: $other")
                    end match
                }
            }
        }
    }

    "changelistBatchingNoDeadlock: two sequential write arms each resolve; registerWrite fires exactly twice; kqueue batches interest changes" in {
        PosixTestSockets.assumePoller()
        // The ET kqueue path batches EV_DISABLE into the changelist inside disableWrite (called from dispatchWritable). This test confirms
        // that the batching does not deadlock the poll loop: both write promises resolve, the registerWrite count is exactly 2 (one per
        // awaitWritable call), and on kqueue at least one poll cycle carried a non-empty changelist (the batched EV_DISABLE submission).
        // On epoll the changelist is unused (pollWithChangesCount stays 0 on Linux), so the changelist assertion gates on kqueue only.
        PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            discard(driver.start())

            val handle = PosixHandle.socket(acceptedFd, PosixHandle.DefaultReadBufferSize, Absent)

            // Two sequential write arms on the same fd. Each must resolve (the fd is always writable on a loopback with no backpressure).
            val p1 = Promise.Unsafe.init[Unit, Abort[Closed]]()
            driver.awaitWritable(handle, p1)
            Abort.run[Timeout](Async.timeout(5.seconds)(Abort.run[Closed](p1.safe.get))).map { r1 =>
                assert(r1.isSuccess, s"first write arm must resolve without timeout: $r1")
                // Re-arm after the first resolves.
                val p2 = Promise.Unsafe.init[Unit, Abort[Closed]]()
                driver.awaitWritable(handle, p2)
                Abort.run[Timeout](Async.timeout(5.seconds)(Abort.run[Closed](p2.safe.get))).map { r2 =>
                    driver.close()
                    PosixTestSockets.closePeerForEof(spy, clientFd)
                    PosixTestSockets.closePeerForEof(spy, acceptedFd)
                    assert(r2.isSuccess, s"second write arm must resolve without deadlock or timeout: $r2")
                    // registerWrite fires once per awaitWritable call: 2 write arms must produce exactly 2 registerWrite calls.
                    val wc = backend.registerWriteCount.get()
                    assert(wc == 2, s"exactly 2 registerWrite calls expected for 2 awaitWritable arms; got $wc")
                    // On kqueue, EV_DISABLE from dispatchWritable is batched into the changelist and submitted atomically with the next kevent
                    // call. At least one poll cycle must have carried a non-empty changelist, confirming the batch path is active.
                    // epoll does not use the changelist (nChanges is always 0 there), so this assertion is kqueue-only.
                    if PosixConstants.isMacOrBsd then
                        val batchedCycles = backend.pollWithChangesCount.get()
                        assert(
                            batchedCycles > 0,
                            s"kqueue: at least one poll cycle must carry a non-empty changelist (EV_DISABLE batch); got batchedCycles=$batchedCycles"
                        )
                    end if
                }
            }
        }
    }

    "lazyFdDelete: closeHandle path carries fdClosing=true; live cancel carries fdClosing=false; recycled fd receives no stale event" in {
        PosixTestSockets.assumePoller()
        // closeHandle calls deregisterFds(fdClosing=true) so kqueue skips EV_DELETE (the OS auto-removes filters on close,
        // and issuing EV_DELETE on a recycled fd number would hit the wrong fd). cancel() calls deregisterFds(fdClosing=false)
        // so the live-fd withdrawal explicitly removes the filter. The call log must reflect this distinction.
        //
        // Stale-event witness (the INV-012 outcome): after closeHandle, the old fd is closed and the OS may recycle the number.
        // A new socket at the same fd number must receive no stale readiness event from the old filter, confirming the OS
        // auto-removal of kqueue filters on close makes fdClosing=true safe for reuse.
        PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            discard(driver.start())

            val handle = PosixHandle.socket(acceptedFd, PosixHandle.DefaultReadBufferSize, Absent)
            val p      = Promise.Unsafe.init[Span[Byte], Abort[Closed]]()
            driver.awaitRead(handle, p)

            // Wait until the read is registered, then close the handle (close path: fdClosing=true).
            backend.registeredRead(acceptedFd).safe.get.map { _ =>
                driver.closeHandle(handle)
                // Wait until the deregister is actually processed by the poll loop (the OpDeregister command is in the changeQueue;
                // the poll loop drains it asynchronously). The deregisteredFd latch fires when deregister() executes on the backend.
                Abort.run[Timeout](Async.timeout(5.seconds)(backend.deregisteredFd(acceptedFd).safe.get)).map { _ =>
                    driver.close()
                    PosixTestSockets.closePeerForEof(spy, clientFd)
                    // Close acceptedFd via the real OS close so the fd number becomes available for reuse.
                    // (closeHandle already ran the driver teardown; the OS fd must still be closed.)
                    PosixTestSockets.closePeerForEof(spy, acceptedFd)
                    val log = backend.callLog
                    // The close path must produce deregister(..., fdClosing=true).
                    assert(
                        log.exists(s => s == s"deregister($acceptedFd, fdClosing=true)"),
                        s"closeHandle must log deregister with fdClosing=true for fd=$acceptedFd: $log"
                    )
                    // The live-fd cancel path must produce deregister(..., fdClosing=false), not fdClosing=true.
                    // (No live cancel was issued here, so fdClosing=false must not appear for acceptedFd.)
                    assert(
                        !log.exists(s => s == s"deregister($acceptedFd, fdClosing=false)"),
                        s"closeHandle must NOT log deregister with fdClosing=false for fd=$acceptedFd: $log"
                    )
                    assert(
                        !log.exists(_.startsWith("rearm(")),
                        s"ET must produce no rearm() calls: $log"
                    )
                }.andThen {
                    // Stale-event witness: open a fresh driver + backend. Get a new loopback pair. If the OS recycles the old fd
                    // number (POSIX LIFO: lowest-available-fd), the new accepted fd may equal acceptedFd. Register the new fd for read
                    // on the new driver WITHOUT sending any data first. Confirm no spurious read event fires from a phantom old filter:
                    // send one byte of real data and confirm exactly that byte arrives. A stale kqueue filter from the old fd would have
                    // fired the read interest immediately (before the real data), delivering a zero-length or stale result.
                    PosixTestSockets.loopbackPair().map { case (client2Fd, accepted2Fd) =>
                        val real2     = PollerBackend.default()
                        val pollerFd2 = real2.create()
                        val spy2      = RecordingSocketBindings(Ffi.load[SocketBindings])
                        val backend2  = RecordingPollerBackend(real2)
                        val driver2   = TestDrivers.forBackend(backend2, pollerFd2, spy2)
                        discard(driver2.start())

                        val handle2 = PosixHandle.socket(accepted2Fd, PosixHandle.DefaultReadBufferSize, Absent)
                        val p2      = Promise.Unsafe.init[Span[Byte], Abort[Closed]]()
                        driver2.awaitRead(handle2, p2)
                        // Send exactly one byte of real data after the read is armed (no pre-existing data).
                        backend2.registeredRead(accepted2Fd).safe.get.map { _ =>
                            val sendBuf = Buffer.fromArray[Byte](Array[Byte](0x42.toByte))
                            sock.send(client2Fd, sendBuf, 1L, PosixConstants.MSG_NOSIGNAL).safe.get.map { _ =>
                                sendBuf.close()
                                // The read must deliver exactly the byte we sent, not a stale empty span or a phantom delivery.
                                Abort.run[Timeout](Async.timeout(5.seconds)(p2.safe.get)).map { outcome =>
                                    driver2.close()
                                    PosixTestSockets.closePeerForEof(spy2, client2Fd)
                                    PosixTestSockets.closePeerForEof(spy2, accepted2Fd)
                                    outcome match
                                        case Result.Success(span: Span[Byte]) =>
                                            assert(
                                                span.size == 1 && span(0) == 0x42.toByte,
                                                s"new fd=$accepted2Fd (old=$acceptedFd) must receive exactly the sent byte, not a stale event: got ${span.toArrayUnsafe.toList}"
                                            )
                                        case other =>
                                            fail(s"new fd=$accepted2Fd read failed: $other")
                                    end match
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "wakeLatencyBounded: awaitRead on a data-ready fd completes promptly via the poll-loop wake" in {
        PosixTestSockets.assumePoller()
        // Send a byte to make the accepted fd immediately read-ready. Submit awaitRead while the poll loop may be parked.
        // The wake (backend.wake) must cut the park short so the read delivers within 5 seconds, not just after the 100ms timeout.
        // The wakeCount assertion pins the wake path (a regression that drops the wake would still deliver after the timeout).
        PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            discard(driver.start())

            val handle  = PosixHandle.socket(acceptedFd, PosixHandle.DefaultReadBufferSize, Absent)
            val sendBuf = Buffer.fromArray[Byte](Array[Byte](42.toByte))
            sock.send(clientFd, sendBuf, 1L, PosixConstants.MSG_NOSIGNAL).safe.get.map { _ =>
                sendBuf.close()
                val p = Promise.Unsafe.init[Span[Byte], Abort[Closed]]()
                driver.awaitRead(handle, p)
                Abort.run[Timeout](Async.timeout(5.seconds)(p.safe.get)).map { outcome =>
                    driver.close()
                    PosixTestSockets.closePeerForEof(spy, clientFd)
                    PosixTestSockets.closePeerForEof(spy, acceptedFd)
                    assert(
                        outcome.isSuccess,
                        s"awaitRead on a data-ready fd must deliver promptly via wake: $outcome"
                    )
                    assert(
                        backend.wakeCount.get() > 0,
                        s"submitChange must have triggered the poll-loop wake; wakeCount=${backend.wakeCount.get()}"
                    )
                }
            }
        }
    }

end PollerIoDriverEdgeTriggeredTest
