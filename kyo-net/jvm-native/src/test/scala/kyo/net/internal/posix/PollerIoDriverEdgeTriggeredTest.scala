package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** Behavioral pins for edge-triggered (ET) registration in [[PollerIoDriver]].
  *
  * Each leaf exercises one aspect of the ET model that differs from the old EPOLLONESHOT / EV_ONESHOT model:
  *
  *   - `residualDrainNoClose`: confirms that a burst larger than one read buffer, sent without any subsequent data or close, delivers ALL
  *     bytes across multiple awaitRead calls. This is the authoritative ET correctness pin: on epoll, a residual after the first recv is
  *     stranded forever unless the driver re-dispatches the read immediately when the filled buffer indicates kernel residual. No half-close,
  *     no new data; the test drives the burst-then-silence case the FIN-edge workaround masks.
  *   - `drainsToEagain`: confirms that the fill-buffer re-dispatch also works when the burst is accompanied by a peer half-close (sends
  *     data then half-closes). The FIN is the guaranteed terminator; this leaf drives a burst that also requires the consumer-paced drain.
  *   - `syscallCountConstant`: confirms that N reads on an ET-armed fd produce exactly N `registerRead` calls (N from application awaitRead
  *     calls, none from the abolished rearmSurvivors path). Under EPOLLONESHOT, N reads produced 2N calls (N application + N survivor re-arm).
  *     A second loop of N2=10 reads (one byte each) verifies the arm count stays exactly N+N2 across the extended run (constant in R, not
  *     merely below 2R). Each read waits for `backend.registeredRead(fd)` after awaitRead and before sending the byte: for iteration 0 this
  *     synchronizes on the change worker executing epoll_ctl(ADD), ensuring the byte's arrival always triggers an edge; for iterations 1+ the
  *     fd is already registered and the promise is already done (no-op wait). Without this sync, a race between the ADD syscall and byte
  *     arrival on ARM64 Linux can drop the edge for the first read, causing a 5-second timeout.
  *   - `halfCloseDrainsRemaining`: confirms that a peer half-close arriving simultaneously with buffered bytes drains the bytes first and
  *     then delivers `Span.empty` without a spurious Closed. On epoll ET, EPOLLRDHUP fires once alongside EPOLLIN; after the data is
  *     consumed via the first recv, the driver sets `readMightHaveMore = true` (because `eofPending` is set) to force re-dispatch on
  *     the next `awaitRead`, where a second recv returns 0 (FIN) and delivers `Span.empty`.
  *   - `changelistBatchingNoDeadlock`: confirms that on kqueue (where `disableWrite` batches EV_DISABLE into the changelist), both write
  *     promises resolve, registerWrite fires exactly twice, and at least one poll cycle carried a non-empty changelist (batch path active).
  *     Gated on kqueue: epoll EPOLLET fires the write edge only on write-buffer transitions; a loopback fd that is always writable fires
  *     once on ADD and not again without a not-writable->writable transition, making a second sequential awaitWritable incompatible with
  *     the epoll ET model.
  *   - `lazyFdDelete`: confirms that `closeHandle` produces a deregister log entry with `fdClosing=true` (kqueue skips EV_DELETE; the OS
  *     auto-removes filters on close), while a live-fd cancel would produce `fdClosing=false` (EV_DELETE executes to prevent stale events).
  *     Extends to a stale-event witness: a new socket reusing the old fd number receives no phantom readiness from the closed filter.
  *   - `droppedEdgeDuringBackpressurePause`: confirms that data arriving on an armed fd while no pending read is present is not stranded.
  *     Under epoll EPOLLET, the empty->ready edge fires once into the `Absent` case of `dispatchRead` (no consumer parked), which pre-fix
  *     silently dropped it. A subsequent consumer `awaitRead` re-registers with MOD-skip (unchanged mask) and parks forever. The fix records
  *     the missed edge in `missedReads` and re-dispatches immediately when the consumer's `awaitRead` arrives. On kqueue this is not
  *     observable: EV_ADD|EV_CLEAR re-evaluates buffered data on every `registerRead`. Gated on `assumePoller()`.
  *   - `wakeLatencyBounded`: confirms that a `awaitRead` submitted while the poll loop is parked wakes the loop (via `backend.wake`) and
  *     delivers the readiness promptly, not just after the 100ms timeout.
  *
  * Gate: `PosixTestSockets.assumePoller()` for most leaves; `changelistBatchingNoDeadlock` uses `assumeKqueue()` because it tests
  * kqueue-specific EV_DISABLE changelist batching and a second sequential awaitWritable that requires EV_CLEAR re-evaluation semantics
  * not available under epoll EPOLLET. No leaf sleeps; all synchronize on real promises.
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
            val p = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
            driver.awaitRead(handle, p)
            Abort.run[Timeout](Async.timeout(10.seconds)(Abort.run[Closed](p.safe.get))).map {
                case Result.Success(Result.Success(ReadOutcome.Bytes(span))) =>
                    acc.write(span.toArrayUnsafe)
                    Loop.continue(())
                case Result.Success(Result.Success(_)) =>
                    Loop.done(acc.toByteArray) // EOF (PeerFin, CleanClose, etc.)
                case Result.Success(Result.Failure(closed: Closed)) =>
                    Abort.fail(closed)
                case _ =>
                    Abort.fail(Closed("PollerIoDriverEdgeTriggeredTest", summon[Frame], "awaitRead timed out or panicked"))
            }
        }
    end collectUntilEof

    /** Collect bytes from successive awaitRead calls on `handle` until exactly `want` bytes have arrived. The peer must send at least
      * `want` bytes before this returns; no close or half-close is required. Each awaitRead has a 10-second timeout so a stranded residual
      * surfaces as a timed-out failure rather than hanging indefinitely.
      */
    private def collectExactBytes(
        driver: PollerIoDriver,
        handle: PosixHandle,
        want: Int
    )(using Frame): Array[Byte] < (Abort[Closed] & Async) =
        val acc = new java.io.ByteArrayOutputStream
        Loop(()) { _ =>
            if acc.size() >= want then Loop.done(acc.toByteArray)
            else
                val p = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                driver.awaitRead(handle, p)
                Abort.run[Timeout](Async.timeout(10.seconds)(Abort.run[Closed](p.safe.get))).map {
                    case Result.Success(Result.Success(ReadOutcome.Bytes(span))) =>
                        acc.write(span.toArrayUnsafe)
                        Loop.continue(())
                    case Result.Success(Result.Success(_)) =>
                        Loop.done(acc.toByteArray) // EOF
                    case Result.Success(Result.Failure(closed: Closed)) =>
                        Abort.fail(closed)
                    case _ =>
                        Abort.fail(Closed(
                            "PollerIoDriverEdgeTriggeredTest",
                            summon[Frame],
                            "awaitRead timed out (residual stranded on ET)"
                        ))
                }
        }
    end collectExactBytes

    "residualDrainNoClose: a burst larger than one read buffer delivers ALL bytes across awaitRead calls with no close" in {
        PosixTestSockets.assumePoller()
        // Authoritative ET correctness pin: the peer sends a burst that spans multiple recv buffers (3x readBufferSize here),
        // then NEVER writes again and NEVER closes. Under epoll ET the kernel signals the empty->ready transition exactly once.
        // Without consumer-paced drain, the first awaitRead delivers one buffer's worth, and the second awaitRead blocks forever
        // waiting for an ET edge that never arrives (no new data, no close, so no new empty->ready transition). With the fix,
        // a filled recv buffer causes the driver to re-dispatch the read immediately on the next awaitRead registration, so
        // the residual drains without waiting for a new kernel edge.
        // On kqueue, registerRead re-issues EV_ADD|EV_CLEAR which re-evaluates buffered data, so the bug is kqueue-invisible;
        // this leaf catches it on real epoll in the container.
        val readBufSize = PosixHandle.DefaultReadBufferSize
        val payloadSize = readBufSize * 3
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
                Abort.run[Closed](collectExactBytes(driver, handle, payloadSize)).map { result =>
                    driver.close()
                    PosixTestSockets.closePeerForEof(spy, clientFd)
                    PosixTestSockets.closePeerForEof(spy, acceptedFd)
                    result match
                        case Result.Success(bytes) =>
                            assert(
                                bytes.length == payload.length,
                                s"byte count must match: got ${bytes.length} of ${payload.length} (residual was stranded if short)"
                            )
                            assert(
                                bytes.toList == payload.toList,
                                s"byte content must match exactly (consumer-paced drain must not lose or reorder bytes)"
                            )
                        case Result.Failure(closed) =>
                            fail(s"reader got Closed: $closed")
                        case other =>
                            fail(s"unexpected result: $other")
                    end match
                }
            }
        }
    }

    "drainsToEagain: a burst larger than one read buffer with a subsequent peer half-close delivers all bytes then surfaces EOF" in {
        PosixTestSockets.assumePoller()
        // The peer sends a payload spanning multiple recv buffers, then half-closes. Both conditions require the consumer-paced
        // drain: the residual after the first recv is re-dispatched by the filled-buffer signal, and the eventual EAGAIN with
        // eofPending surfaces EOF cleanly. The half-close produces a FIN edge, but the bytes must arrive via the drain, not just
        // via the FIN edge. Payload at 4x readBufferSize forces at least 4 recv calls.
        val readBufSize = PosixHandle.DefaultReadBufferSize
        val payloadSize = readBufSize * 4
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
                                s"byte content must match exactly (consumer-paced drain must not lose or reorder bytes)"
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

    "syscallCountConstant: ET keeps fd armed so registerRead fires exactly R times for R sequential reads, constant in R" in {
        PosixTestSockets.assumePoller()
        // Syscall-count proof in two loops, confirming the arm count is CONSTANT in R (not merely below 2R).
        //
        // Under EPOLLONESHOT (old model): each readiness event disarmed the fd; after each read the driver re-armed for the next read
        // and ran rearmSurvivors (a second RegisterRead per event). R reads produced 2R registerRead calls.
        //
        // Under EPOLLET / EV_CLEAR (ET, current model): the fd stays armed in the kernel between reads; no survivor re-arm is issued.
        // R reads produce exactly R registerRead calls: one per application-level awaitRead, none from rearmSurvivors (which is gone).
        //
        // First loop (n=5 single-byte reads): asserts readCount == n and readCount < 2*n.
        // Second loop (n2=10 more single-byte reads): asserts total == n+n2 and total < 2*(n+n2).
        // Together they prove the arm count is R, not 2R, and stays R across an extended run (constant in R).
        PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            discard(driver.start())

            val handle = PosixHandle.socket(acceptedFd, PosixHandle.DefaultReadBufferSize, Absent)
            val n      = 5
            val n2     = 10

            def singleByteReads(count: Int, label: String): Int < (Abort[Closed] & Async) =
                Loop(0) { i =>
                    if i >= count then Loop.done(i)
                    else
                        // Register the read BEFORE sending the byte to avoid a premature-edge-drop race on epoll ET.
                        // On epoll EPOLLET, the first awaitRead enqueues epoll_ctl(ADD) asynchronously; if the byte
                        // arrives before ADD runs, the ADD fires the edge event (epoll detects the ready state at ADD
                        // time). For iterations 1+ the fd is already registered, so the edge fires on the empty->non-empty
                        // transition when the byte arrives.
                        //
                        // Synchronize on backend.registeredRead to ensure the first epoll_ctl(ADD) has run before the byte
                        // is sent. For iteration 0 this suspends until registerRead executes on the change worker; for
                        // iterations 1+ the promise is already done and returns immediately (no-op sync). Using
                        // Abort.run[Any] collapses the Promise.Unsafe[Unit, Any] error channel back to Abort[Closed].
                        val p = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        driver.awaitRead(handle, p)
                        Abort.run[Any](backend.registeredRead(acceptedFd).safe.get).andThen {
                            val sendBuf = Buffer.fromArray[Byte](Array[Byte]((i % 127).toByte))
                            sock.send(clientFd, sendBuf, 1L, PosixConstants.MSG_NOSIGNAL).safe.get.map { _ =>
                                sendBuf.close()
                                Abort.run[Timeout](Async.timeout(5.seconds)(p.safe.get)).map {
                                    case Result.Success(_) => Loop.continue(i + 1)
                                    case other             => fail(s"$label read $i failed: $other")
                                }
                            }
                        }
                }

            singleByteReads(n, "first loop").map { c1 =>
                assert(c1 == n, s"first loop: expected $n reads, completed $c1")
                val r1           = backend.registerReadCount.get()
                val oneshotCount = 2 * n
                assert(r1 == n, s"ET: registerRead must fire exactly N=$n times after first loop; got $r1")
                assert(r1 < oneshotCount, s"ET must be below EPOLLONESHOT count $oneshotCount after first loop; got $r1")
                singleByteReads(n2, "second loop").map { c2 =>
                    driver.close()
                    PosixTestSockets.closePeerForEof(spy, clientFd)
                    PosixTestSockets.closePeerForEof(spy, acceptedFd)
                    assert(c2 == n2, s"second loop: expected $n2 reads, completed $c2")
                    val total        = backend.registerReadCount.get()
                    val expected     = n + n2
                    val oneshotTotal = 2 * expected
                    assert(total == expected, s"ET: registerRead must fire exactly R=${n + n2} times total; got $total")
                    assert(total < oneshotTotal, s"ET must stay below EPOLLONESHOT count $oneshotTotal total; got $total")
                    assert(
                        !backend.callLog.exists(_.startsWith("rearm(")),
                        s"ET must produce no rearm() calls; rearm was the EPOLLONESHOT survivor re-arm path: ${backend.callLog}"
                    )
                }
            }
        }
    }

    "halfCloseDrainsRemaining: bytes buffered at the time of peer half-close are delivered before Span.empty" in {
        PosixTestSockets.assumePoller()
        // The peer sends data AND immediately half-closes so both arrive in the kernel before the first awaitRead.
        // On epoll ET, epoll_ctl(ADD) on the first awaitRead fires the combined EPOLLIN|EPOLLRDHUP edge. The first recv drains the
        // data (n < readBufferSize). Because eofPending is true, dispatchReadPlain sets readMightHaveMore=true regardless of buffer fill.
        // The second awaitRead triggers an immediate re-dispatch (consumer-paced drain), which calls recv again and gets 0 (FIN), delivering
        // Span.empty. Without eofPending forcing readMightHaveMore=true, the MOD-skip on re-register would produce no new edge and the
        // second awaitRead would park until timeout, because EPOLLET fires EPOLLRDHUP only once per transition.
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
        PosixTestSockets.assumeKqueue()
        // The ET kqueue path batches EV_DISABLE into the changelist inside disableWrite (called from dispatchWritable). This test confirms
        // that the batching does not deadlock the poll loop: both write promises resolve, the registerWrite count is exactly 2 (one per
        // awaitWritable call), and at least one poll cycle carried a non-empty changelist (the batched EV_DISABLE submission).
        // Gate: kqueue only. On epoll EPOLLET the fd stays armed and only fires an edge on write-buffer transition (not-writable to
        // writable). A loopback fd that is always writable fires the EPOLLOUT edge exactly once on ADD; a second awaitWritable after
        // dispatchWritable consumed the first edge finds no new transition and parks until timeout. The changelist batch is also
        // kqueue-specific (kqueue accumulates kevent changes and submits them atomically with the wait call; epoll uses separate
        // epoll_ctl syscalls and has no changelist parameter in epoll_wait). Testing this on epoll would require a different
        // protocol (drain the socket buffer to force a real not-writable->writable transition), which is not the subject of this leaf.
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
        // Stale-event witness: after closeHandle, the old fd is closed and the OS may recycle the number.
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
            val p      = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
            driver.awaitRead(handle, p)

            // Wait until the read is registered, then close the handle (close path: fdClosing=true).
            backend.registeredRead(acceptedFd).safe.get.map { _ =>
                driver.closeHandle(handle)
                // Wait until the deregister is actually processed by the poll loop (the OpDeregister command is in the changeQueue;
                // the poll loop drains it asynchronously). The deregisteredFd latch fires when deregister() executes on the backend.
                Abort.run[Timeout](Async.timeout(5.seconds)(backend.deregisteredFd(acceptedFd).safe.get)).map { _ =>
                    driver.close()
                    PosixTestSockets.closePeerForEof(spy, clientFd)
                    // acceptedFd is ALREADY closed: closeHandle above ran closeNow, which closes the OS fd (readFd == writeFd) through the
                    // claimFdClose guard. A second close here is a double-close: under concurrent load the freed fd number is recycled to another
                    // connection between the two closes, and the stale second close lands on that new owner (EBADF / spurious teardown). The
                    // stale-event witness below only needs the number to be free, which closeHandle already guarantees.
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
                        val p2      = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
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
                                        case Result.Success(ReadOutcome.Bytes(span)) =>
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

    "droppedEdgeDuringBackpressurePause: data arriving while no pending read is present is not stranded" in {
        PosixTestSockets.assumePoller()
        // Reproduce the missed-readiness (dropped-edge) regression under epoll EPOLLET register-once.
        //
        // The scenario models ReadPump backpressure: the consumer parks on a channel put (no awaitRead in flight) while the
        // fd stays armed. Data arrives from the peer and fires the EPOLLIN edge. The driver's drainReady calls dispatchRead(fd)
        // which hits pendingReads.remove(fd) -> Absent (no pending read). Pre-fix: the edge is silently dropped. The fd stays
        // armed at the kernel but no new edge fires (EPOLLET: only empty->ready transitions fire). When the consumer resumes and
        // calls awaitRead, the re-registration is a MOD-skip (the mask is unchanged since the fd is already in the epoll set),
        // so no new edge reports the buffered data. The consumer's awaitRead parks forever.
        //
        // The fix records the dropped edge in missedReads and checks it in dispatchCmd's OpRegisterRead branch, re-dispatching
        // immediately so the consumer's first awaitRead after the pause delivers the stranded data.
        //
        // On kqueue this is not observable: EV_ADD|EV_CLEAR re-evaluates buffered data on every registerRead, so a consumer
        // resuming with awaitRead gets the data even without the fix. The leaf catches it on real epoll in the container.
        val readBufSize = PosixHandle.DefaultReadBufferSize
        // First payload: smaller than readBufferSize so n < readBufferSize and readMightHaveMore stays false after the first recv.
        // This is the critical condition: readMightHaveMore=false means the existing consumer-paced drain does NOT re-dispatch.
        val firstPayload = Array.tabulate[Byte](256)(i => (i % 127).toByte)
        // Second payload: arrives while there is NO pending read (the consumer is in its backpressure pause).
        val secondPayload = Array.tabulate[Byte](512)(i => ((i + 1) % 127).toByte)
        PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
            discard(driver.start())

            val handle = PosixHandle.socket(acceptedFd, readBufSize, Absent)

            // Register the first read BEFORE sending to avoid the premature-edge race (the same pattern as syscallCountConstant).
            val p1 = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
            driver.awaitRead(handle, p1)
            // Wait until the ADD has actually executed on the change worker, then send the first payload.
            Abort.run[Any](backend.registeredRead(acceptedFd).safe.get).andThen {
                sendAll(clientFd, firstPayload).map { _ =>
                    // Collect the first payload. Because firstPayload.length < readBufferSize, dispatchReadPlain sets
                    // readMightHaveMore=false after this recv. The consumer-paced drain therefore does NOT re-dispatch.
                    Abort.run[Timeout](Async.timeout(10.seconds)(Abort.run[Closed](p1.safe.get))).map {
                        case Result.Success(Result.Success(ReadOutcome.Bytes(span))) =>
                            assert(
                                span.size == firstPayload.length,
                                s"first delivery must match firstPayload size: got ${span.size}"
                            )
                            assert(
                                span.toArrayUnsafe.toList == firstPayload.toList,
                                "first delivery byte content must match firstPayload"
                            )
                            // The consumer is now in its backpressure pause: no awaitRead in flight.
                            // Send the second payload. Under epoll EPOLLET, the fd is still armed at the kernel.
                            // The EPOLLIN edge fires during the next poll cycle (dispatchRead hits Absent -> drops pre-fix).
                            // On kqueue, this also fires an edge, but registerRead re-evaluates it on re-registration anyway.
                            sendAll(clientFd, secondPayload).map { _ =>
                                // Resume: call awaitRead to collect the second payload.
                                // Pre-fix (epoll): MOD-skip on re-registration produces no new edge; awaitRead times out.
                                // Post-fix (epoll): missedReads records the dropped edge; dispatchCmd re-dispatches immediately.
                                // kqueue: EV_ADD|EV_CLEAR re-evaluates buffered data; passes without the fix.
                                Abort.run[Closed](collectExactBytes(driver, handle, secondPayload.length)).map { result =>
                                    driver.close()
                                    PosixTestSockets.closePeerForEof(spy, clientFd)
                                    PosixTestSockets.closePeerForEof(spy, acceptedFd)
                                    result match
                                        case Result.Success(bytes) =>
                                            assert(
                                                bytes.length == secondPayload.length,
                                                s"second delivery must match secondPayload size: got ${bytes.length} of ${secondPayload.length}" +
                                                    " (data was stranded on ET if short or timed out)"
                                            )
                                            assert(
                                                bytes.toList == secondPayload.toList,
                                                "second delivery byte content must match secondPayload exactly"
                                            )
                                        case Result.Failure(closed) =>
                                            fail(s"second delivery got Closed (timed out waiting for stranded data?): $closed")
                                    end match
                                }
                            }
                        case Result.Success(Result.Failure(closed: Closed)) =>
                            fail(s"first awaitRead got Closed: $closed")
                        case _ =>
                            fail("first awaitRead timed out or panicked")
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
                val p = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
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
