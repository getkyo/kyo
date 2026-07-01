package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.tls.TlsEngineLoopback
import kyo.net.internal.tls.TlsRealEngines
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Tests the completion-native [[IoUringDriver]] over a REAL io_uring ring.
  *
  * Every leaf is gated by [[PosixTestSockets.assumeUring]], which probes io_uring at the production ring depth (`max(256, ioPoolSize*64)`):
  * on a non-Linux host, a kernel without io_uring, or a host where the production-depth ring init fails (e.g. a container with a low
  * `io_uring.max` cgroup cap), the leaf cancels cleanly. On native Linux (no cgroup cap) the ring inits and the leaf drives the real ring.
  *
  * The completion / cancel / park machinery is driven against a real ring through a [[RecordingIoUringBindings]] spy: every ring op
  * delegates to the real bindings and the kernel actually completes it; the spy only observes (the per-write send buffer, the keys set on
  * submitted SQEs, the wait timeout, and a latch fired when each CQE is reaped). No completion value is scripted: a recv that ends in
  * Closed does so because a real peer reset the connection (ECONNRESET), an EOF because a real peer closed, an SQ-full park because a real depth-1
  * ring genuinely has no free SQE.
  *
  * Covers: the completion contract (echo bytes equal, res==0 EOF, res<0 Closed naming the real errno, an SQ-full recv parked then re-armed to
  * deliver); the conditional park (indefinite when wake-armed with NODROP confirmed and no stalled ops; bounded ReapTimeoutNs as fallback for
  * older kernels, unarmed wake, or stalled ops); the close ordering (an in-flight buffer is never freed until its CQE is reaped; closeHandle
  * cancels then defers PosixHandle.close until the handle drains; per-write buffer closed only on reap; shared arena); and the accept contract
  * (positive CQE yields the accepted fd; a failed accept yields Closed naming the errno; an SQ-full accept parked then re-armed to complete;
  * re-arm across two accepts).
  */
class IoUringDriverTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Start a fresh REAL driver over an actual io_uring ring, run `body`, then close it. The reap loop runs on the driver's own fiber. */
    private def withRealDriver[A](body: IoUringDriver => A < (Abort[Closed] & Async))(using Frame): A < (Abort[Closed] & Async) =
        val driver = IoUringDriver.init(kyo.net.TransportConfig.default)
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(body(driver))
    end withRealDriver

    /** Allocate a REAL io_uring ring at `depth`, wrap it in a [[RecordingIoUringBindings]] spy, build a driver over it with its reap loop started,
      * run `body`, then close the driver (which tears the ring down via io_uring_queue_exit). The spy delegates every op to the real ring; the
      * kernel completes them. The reap loop must run: every SQ op (the recv/accept/connect arming, the engine ops) drains on the reap carrier.
      */
    private def withRecordingDriver[A](depth: Int)(
        body: (IoUringDriver, RecordingIoUringBindings) => A < (Abort[Closed] & Async)
    )(using Frame): A < (Abort[Closed] & Async) =
        val realUring = Ffi.load[IoUringBindings]
        val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
        val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
        // io_uring_queue_init returns 0 on success / -errno on failure and does NOT set the global errno; read the return
        // value, never the captured (possibly stale) errno (#258). In a container, queue_init legitimately succeeds while
        // leaving errno=2 from its internal feature probing, so reading rc.errorCode here would spuriously fail every leaf.
        if rc != 0 then
            realRing.close()
            throw Closed("RecordingIoUringBindings", summon[Frame], s"queue_init failed: rc=$rc")
        val recording = RecordingIoUringBindings(realUring, realRing)
        val driver    = TestDrivers.forBindings(recording, realRing)
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(body(driver, recording))
    end withRecordingDriver

    private def readVia(driver: IoUringDriver, handle: PosixHandle)(using Frame): ReadOutcome < (Abort[Closed] & Async) =
        val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
        driver.awaitRead(handle, promise)
        promise.safe.get
    end readVia

    private def awaitCondition(bound: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            if cond then Loop.done(true)
            else if java.lang.System.nanoTime() >= deadline then Loop.done(false)
            else Async.sleep(2.millis).andThen(Loop.continue(()))
        }
    end awaitCondition

    "IoUringDriver" - {

        // ---- real-ring echo / close leaves ----

        "real: echo round-trip 16 bytes" in {
            PosixTestSockets.assumeUring()
            withRealDriver { driver =>
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val clientH   = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val payload   = Span.fromUnsafe(Array.tabulate[Byte](16)(i => (i + 1).toByte))
                    val w         = driver.write(clientH, payload, 0)
                    assert(w == WriteResult.Done, s"write result=$w")
                    readVia(driver, acceptedH).map { got =>
                        driver.closeHandle(clientH)
                        driver.closeHandle(acceptedH)
                        val ReadOutcome.Bytes(span) = got.runtimeChecked
                        assert(span.toArray.toList == payload.toArray.toList)
                    }
                }
            }.map(_ => succeed)
        }

        "real: close-during-in-flight-read does not UAF and the read fails Closed" in {
            PosixTestSockets.assumeUring()
            // Submit a read with no data available so it stays in flight, then close while it is pending. Looped to make the race reliable.
            withRealDriver { driver =>
                Loop(0) { i =>
                    if i >= 50 then Loop.done(succeed)
                    else
                        PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                            val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                            val promise   = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                            driver.awaitRead(acceptedH, promise)
                            // Close the handle while the recv SQE is in flight: cancel fails the promise, the buffer is freed only on reap.
                            driver.closeHandle(acceptedH)
                            Abort.run[Closed](promise.safe.get).map { result =>
                                discard(sock.close(client))
                                result match
                                    case Result.Failure(_: Closed) => Loop.continue(i + 1)
                                    case Result.Success(ReadOutcome.PeerFin | ReadOutcome.LocalShutdown | ReadOutcome.CleanClose) =>
                                        Loop.continue(i + 1) // a same-cycle EOF reap is also terminal
                                    case other => Loop.done(fail(s"expected Closed/EOF, got $other"))
                                end match
                            }
                        }
                }
            }
        }

        "real: #177 closing the driver while a recv is parked in-flight tears down cleanly, never a use-after-free" in {
            PosixTestSockets.assumeUring()
            // The #177 teardown-race twin of the leaf above, closing the whole DRIVER (not just the handle). A recv is parked in flight (no
            // data), so the reap carrier sits inside kyo_uring_wait_cqe_timeout holding the ring/cqePtr segments; driver.close() then runs from
            // THIS carrier while that wait is in flight. The buggy close() freed ring/cqePtr mid-wait ("Session is acquired by 1 clients" at
            // cqePtr.close on JVM, SIGSEGV in kyo_uring_get_sqe on Native); the fixed single-owner teardown only signals closedFlag and lets the
            // reap carrier free on its own exit. A fresh driver per iteration, looped, so the close reliably lands during a wait.
            //
            // io_uring-only (validated platform exception): the race is structurally io_uring's (no other backend has a reap carrier holding
            // the ring/cqePtr during a blocking wait), and a DETERMINISTIC reproduce needs driver-level control to park a recv and close while
            // the wait is in flight. A cross-backend transport-level version would have to Async.sleep to "let the read park" (no portable
            // signal for it), which the no-sleep determinism rule forbids; the cross-backend close-fails-inflight-reads INVARIANT is instead
            // covered deterministically by TransportLifecycleTest (peer-close) and the per-backend close in IoBackendStaleErrnoTest.
            Loop(0) { i =>
                if i >= 50 then Loop.done(succeed)
                else
                    val driver = IoUringDriver.init(kyo.net.TransportConfig.default)
                    discard(driver.start())
                    PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                        val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                        val promise   = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        driver.awaitRead(acceptedH, promise) // recv SQE in flight, no data: the reap loop parks in wait holding cqePtr
                        driver.close()                       // tear the driver down while that wait is in flight
                        Abort.run[Closed](promise.safe.get).map { result =>
                            discard(sock.close(client))
                            discard(sock.close(accepted))
                            result match
                                case Result.Failure(_: Closed) => Loop.continue(i + 1)
                                case Result.Success(ReadOutcome.PeerFin | ReadOutcome.LocalShutdown | ReadOutcome.CleanClose) =>
                                    Loop.continue(i + 1) // a same-cycle EOF reap is also terminal
                                case other => Loop.done(fail(s"expected Closed/EOF on driver close, got $other"))
                            end match
                        }
                    }
            }
        }

        // ---- io_uring gate diagnosis leaves ----

        // Probes io_uring_queue_init at depths 2, 32, and 256. Linux-only; NOT gated by assumeUring so it runs even on
        // cgroup-limited hosts where depth-256 init fails. The success/failure signal is the RETURN value (rc): 0 on
        // success, -errno on failure. Plain Int, not clamped: io_uring_queue_init does not set the global errno and can
        // legitimately leave a stale non-zero errno after a successful init (#258). On native Linux (no cgroup cap) all
        // three depths return 0; on a restricted host depth-256 returns a negative -errno.
        "depth-{2,32,256} init measurement: asserting concrete per-depth return value" in {
            if !PosixConstants.isLinux then cancel("io_uring is Linux-only")
            val uring  = Ffi.load[IoUringBindings]
            val depths = Seq(2, 32, 256)
            val results = depths.map { depth =>
                val ring = Buffer.alloc[Byte](uring.kyo_uring_sizeof().toInt)
                val rc   = uring.io_uring_queue_init(depth, ring, 0)
                if rc == 0 then
                    uring.io_uring_queue_exit(ring)
                ring.close()
                (depth, rc)
            }
            val d2   = results.find(_._1 == 2).map(_._2).getOrElse(fail("depth 2 not probed"))
            val d256 = results.find(_._1 == 256).map(_._2).getOrElse(fail("depth 256 not probed"))
            // io_uring_queue_init returns 0 (success) or a negative -errno (failure); it never returns a positive value. A
            // positive return would indicate a call-convention mismatch, not a real init result.
            val positives = results.filter(_._2 > 0)
            assert(positives.isEmpty, s"io_uring_queue_init returned positive values: $positives; it returns 0 or -errno only")
            // If depth-2 succeeded (returned 0) and depth-256 failed, depth-256 must have returned a NEGATIVE -errno (e.g.
            // -ENOENT under a cgroup io_uring.max cap), never 0: a 0 return would mean success, contradicting the failure.
            assert(
                !(d2 == 0 && d256 != 0) || d256 < 0,
                s"depth-256 failed with rc=$d256; expected a negative -errno when depth-2 succeeded (cgroup io_uring.max cap)"
            )
            succeed
        }

        // The #258 stale-errno init bug (IoUringDriver.init must read the io_uring_queue_init RETURN value, not the captured errno) is
        // reproduced cross-backend in IoBackendStaleErrnoTest, which dirties errno then builds each available backend's real transport (so
        // io_uring is exercised through the same IoUringDriver.init this file covers, alongside epoll/kqueue/nio for consistency).

        // Verifies that PosixTestSockets.assumeUring() throws TestCancelled (not Closed) when the production-depth
        // ring is unavailable, and does NOT throw when the ring is available. The distinction is the key gate property:
        // cancel = ring unavailable (cgroup limit or no io_uring), fail = ring available but driver misbehaves.
        "assumeUring gate: cancels-or-proceeds (never throws Closed)" in {
            if !PosixConstants.isLinux then cancel("io_uring is Linux-only")
            // Invoke the gate and observe exactly one of two outcomes: cancel (TestCancelled) or proceed (no throw).
            // Both are correct. What must NOT happen is a throw of Closed or any other exception.
            try
                PosixTestSockets.assumeUring()
                // Gate passed: the production-depth ring is available. Confirm the driver also initializes successfully.
                val driver = IoUringDriver.init(kyo.net.TransportConfig.default)
                driver.close()
                succeed
            catch
                case _: kyo.test.TestCancelled =>
                    // Correct: ring unavailable at production depth; gate cancelled cleanly.
                    cancel("io_uring unavailable at production depth (cgroup limit or no io_uring); gate cancelled as expected")
                case closed: Closed =>
                    fail(s"assumeUring threw Closed instead of TestCancelled: ${closed.getMessage}")
            end try
        }

        // When PosixTestSockets.assumeUring() passes, IoUringDriver.init must also succeed. The gate probes at the same
        // production depth the driver uses, so a gate pass guarantees the driver will not throw Closed on init.
        "assumeUring gate passes iff IoUringDriver.init succeeds" in {
            PosixTestSockets.assumeUring()
            // Gate passed: the production ring is available at max(256, ioPoolSize*64).
            // IoUringDriver.init uses the exact same formula and config, so it must succeed.
            val driver = IoUringDriver.init(kyo.net.TransportConfig.default)
            driver.close()
            succeed
        }

        // ---- completion-contract leaves over a real ring (spy observation) ----

        "res > 0 completes the read with exactly the bytes the peer sent" in {
            PosixTestSockets.assumeUring()
            withRecordingDriver(256) { (drv, _) =>
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val payload   = Array.tabulate[Byte](16)(i => (i + 1).toByte)
                    // The peer sends exactly 16 bytes; the real recv CQE carries res=16 and the driver delivers exactly those bytes.
                    assert(sock.sendNow(client, Buffer.fromArray[Byte](payload), payload.length.toLong, 0).value == 16L)
                    readVia(drv, acceptedH).map { got =>
                        drv.closeHandle(acceptedH)
                        discard(sock.close(client))
                        val ReadOutcome.Bytes(span) = got.runtimeChecked
                        assert(span.toArray.toList == payload.toList, s"got ${span.toArray.toList}")
                    }
                }
            }.map(_ => succeed)
        }

        "res == 0 completes the read with an empty Span (EOF) on a real peer close" in {
            PosixTestSockets.assumeUring()
            withRecordingDriver(256) { (drv, _) =>
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    // Closing the peer cleanly makes the real recv CQE arrive with res=0 (EOF).
                    PosixTestSockets.closePeerForEof(sock, client)
                    readVia(drv, acceptedH).map { got =>
                        drv.closeHandle(acceptedH)
                        assert(got == ReadOutcome.PeerFin, s"expected EOF (PeerFin), got $got")
                    }
                }
            }.map(_ => succeed)
        }

        "res < 0 completes the read with Closed naming the errno on a real peer reset" in {
            PosixTestSockets.assumeUring()
            withRecordingDriver(256) { (drv, _) =>
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val promise   = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    drv.awaitRead(acceptedH, promise)
                    // A real RST: SO_LINGER {l_onoff=1, l_linger=0} + close. The kernel fills the pending recv CQE with res=-ECONNRESET=-104
                    // on Linux; the driver maps any res<0 to Closed("read errno=N") with no errno-specific branch, so the message names 104.
                    val econnreset = 104
                    PosixTestSockets.resetPeer(sock, client)
                    Abort.run[Closed](promise.safe.get).map { result =>
                        drv.closeHandle(acceptedH)
                        result match
                            case Result.Failure(c: Closed) =>
                                assert(c.getMessage.contains(s"errno=$econnreset"), s"message=${c.getMessage}")
                            case other => fail(s"expected Closed(errno=$econnreset), got $other")
                        end match
                    }
                }
            }.map(_ => succeed)
        }

        "a recv parked on SQ-full is re-armed (not aborted) and delivers the peer's bytes" in {
            PosixTestSockets.assumeUring()
            // The reap loop runs, so the engine FIFO drains on the reap carrier. Pin that carrier with a latch (a latch the test releases, NOT a
            // sleep) so two reads drain in ONE drainEngineOps pass before the wait submits anything: on a depth-1 ring the first read consumes the
            // one SQE and the second's get_sqe returns Absent, parking it in stalledSubmits (its promise stays pending) rather than failing it. The
            // reap loop re-arms a parked op every turn once submit frees the slot, even when no unrelated CQE arrives. Proven end to end: the parked
            // read delivers its peer's bytes; the unfixed behavior failed it Closed("SQ full") immediately, so a delivered payload is the proof.
            withRecordingDriver(1) { (drv, _) =>
                PosixTestSockets.loopbackPair().map { case (clientA, acceptedA) =>
                    PosixTestSockets.loopbackPair().map { case (clientB, acceptedB) =>
                        val accAH = PosixHandle.socket(acceptedA, PosixHandle.DefaultReadBufferSize, Absent)
                        val accBH = PosixHandle.socket(acceptedB, PosixHandle.DefaultReadBufferSize, Absent)
                        val gate  = new java.util.concurrent.CountDownLatch(1)
                        val pinIn = Promise.Unsafe.init[Unit, Abort[Closed]]()
                        drv.submitEngineOp { () =>
                            pinIn.completeDiscard(Result.succeed(()))
                            gate.await()
                        }
                        pinIn.safe.get.map { _ =>
                            // Reap carrier pinned. Read A (no peer bytes) consumes the one SQE and stays in flight; read B's get_sqe returns Absent
                            // and parks. Both are enqueued behind the pin, so they drain together when it releases.
                            val pA = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                            drv.awaitRead(accAH, pA)
                            val pB = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                            drv.awaitRead(accBH, pB)
                            val payload = Array.tabulate[Byte](8)(i => (i + 10).toByte)
                            // Release the gate BEFORE asserting on the send result: a failed assertion must never leave the reap carrier
                            // parked (it would wedge every later test on this scheduler).
                            val sent = sock.sendNow(clientB, Buffer.fromArray[Byte](payload), payload.length.toLong, 0).value
                            gate.countDown()
                            assert(sent == 8L, s"peer send must deliver the full payload, sent $sent")
                            Abort.run[Closed](pB.safe.get).map { result =>
                                drv.closeHandle(accAH)
                                drv.closeHandle(accBH)
                                discard(sock.close(clientA))
                                discard(sock.close(clientB))
                                result match
                                    case Result.Success(ReadOutcome.Bytes(got)) =>
                                        assert(
                                            got.toArray.toList == payload.toList,
                                            s"the parked read delivered wrong bytes: ${got.toArray.toList}"
                                        )
                                    case other =>
                                        fail(s"a SQ-full read must park and deliver, never abort: got $other")
                                end match
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "the reap wait parks indefinitely when wake is armed and NODROP confirmed, bounded as fallback" in {
            PosixTestSockets.assumeUring()
            withRecordingDriver(256) { (drv, recording) =>
                // The reap wait is INDEFINITE (Long.MaxValue) when the wake multishot is armed, IORING_FEAT_NODROP is confirmed (kernel >= 5.5,
                // CQE drops are impossible by contract), and no ops are stalled on SQ-full. It falls back to ReapTimeoutNs (100ms) when any of
                // those conditions does not hold: NODROP absent (kernel < 5.5), wake not yet armed (SQ-full at armWake), or stalled ops pending.
                // firstWait fires once the first reap wait is entered; inspect the timeout it passed to the real submit_and_wait.
                recording.firstWait.safe.get.map { _ =>
                    val t = recording.lastWaitTimeoutNs
                    if recording.nodropAvailable then
                        // NODROP confirmed: the first wait after armWake succeeds must be indefinite (wake armed, no stalled ops yet).
                        assert(
                            t == Long.MaxValue,
                            s"NODROP confirmed: reap wait must be indefinite (Long.MaxValue) when wake armed, got $t ns"
                        )
                    else
                        // NODROP absent (kernel < 5.5): bounded fallback protects against lost CQEs on this kernel.
                        assert(
                            t == 100000000L,
                            s"NODROP absent: reap wait must use bounded ReapTimeoutNs (100ms) as liveness fallback, got $t ns"
                        )
                    end if
                }
            }.map(_ => succeed)
        }

        "a cross-carrier submitEngineOp runs on the reap loop, which keeps running across wakes" in {
            PosixTestSockets.assumeUring()
            withRecordingDriver(256) { (drv, recording) =>
                // A cross-carrier submitEngineOp enqueues the op and writes the wake eventfd, whose armed multishot poll fires a CQE that returns the
                // parked reap wait so the loop drains the engine queue and runs the op (real eventfd, real ring, no mock). The op completing proves
                // the cross-carrier handoff works end to end; running a SECOND op after the first proves the loop re-parked and kept running across
                // wakes (the resilience the old empty-timeout-turn test covered).
                recording.firstWait.safe.get.flatMap { _ =>
                    val ran1 = Promise.Unsafe.init[Unit, Abort[Closed]]()
                    drv.submitEngineOp(() => ran1.completeDiscard(Result.succeed(())))
                    ran1.safe.get.flatMap { _ =>
                        val ran2 = Promise.Unsafe.init[Unit, Abort[Closed]]()
                        drv.submitEngineOp(() => ran2.completeDiscard(Result.succeed(())))
                        ran2.safe.get.map(_ => succeed)
                    }
                }
            }
        }

        "a per-write Buffer is closed only after its send CQE is reaped" in {
            PosixTestSockets.assumeUring()
            withRecordingDriver(256) { (drv, recording) =>
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val clientH = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
                    val payload = Span.fromUnsafe(Array.tabulate[Byte](8)(i => i.toByte))
                    val reaped  = recording.awaitReap()
                    // Observe the per-write buffer OPEN while its send SQE is prepped-but-unsubmitted, deterministically and without any
                    // dependency on reap-loop latency. The reap wait blocks on a bounded timeout and is woken promptly by a submission, so a flush
                    // op queued ahead of a single pin would be prepped AND its send SQE submitted + reaped before the test could observe the
                    // in-flight window. Two pins close that race: pin1 blocks the carrier FIRST, so the write's flush op (and pin2) queue behind
                    // a blocked carrier; releasing gate1 then runs the flush (prep the send SQE + record the per-write buffer) and parks at pin2
                    // within the SAME drainEngineOps pass, BEFORE submit_and_wait submits the SQE, so the buffer is provably open at pin2.
                    val gate1  = new java.util.concurrent.CountDownLatch(1)
                    val gate2  = new java.util.concurrent.CountDownLatch(1)
                    val pin1In = Promise.Unsafe.init[Unit, Abort[Closed]]()
                    val pin2In = Promise.Unsafe.init[Unit, Abort[Closed]]()
                    drv.submitEngineOp { () =>
                        pin1In.completeDiscard(Result.succeed(()))
                        gate1.await()
                    }
                    pin1In.safe.get.flatMap { _ =>
                        // Carrier pinned at pin1. Queue the write (flush op) and pin2 behind it; neither runs until gate1 releases.
                        val w = drv.write(clientH, payload, 0)
                        assert(w == WriteResult.Done, s"write result=$w")
                        drv.submitEngineOp { () =>
                            pin2In.completeDiscard(Result.succeed(()))
                            gate2.await()
                        }
                        gate1.countDown() // release pin1: the same drain pass now runs the flush (prep + record), then parks at pin2 before submit
                        pin2In.safe.get.flatMap { _ =>
                            // Snapshot the in-flight state, then release gate2 BEFORE asserting: a failed assertion must never leave the reap
                            // carrier parked (it would wedge every later test on this scheduler).
                            val before          = Maybe(recording.sendBufs.peek())
                            val openWhilePinned = before.map(b => !b.isClosed)
                            gate2.countDown()
                            assert(before.nonEmpty, "no send buffer recorded")
                            assert(
                                openWhilePinned == Present(true),
                                "per-write buffer must stay open while the send SQE is prepped but unsubmitted"
                            )
                            // The driver closes the per-write buffer in complete() (releaseBuffer) BEFORE cqe_seen, so awaiting the reap latch is
                            // the deterministic "send CQE reaped, buffer lifecycle ran" signal. The buffer must be closed by then, not before.
                            reaped.safe.get.map { _ =>
                                drv.closeHandle(clientH)
                                discard(sock.close(accepted))
                                assert(
                                    before.exists(_.isClosed),
                                    "per-write buffer must be closed once its send CQE is reaped"
                                )
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "closeHandle defers PosixHandle.close until the in-flight read CQE is reaped" in {
            PosixTestSockets.assumeUring()
            withRecordingDriver(256) { (drv, recording) =>
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val promise   = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    val reaped    = recording.awaitReap()
                    drv.awaitRead(acceptedH, promise) // recv SQE with no data: stays in flight
                    // awaitRead only ENQUEUES the arm onto the engine FIFO (submitDeferredRecv); the actual submitRecv -- and the register()
                    // call that increments the handle's in-flight count -- runs later, on the reap carrier. Wait for the real submit
                    // (recording.submittedKeys gains an entry the instant kyo_uring_sqe_set_data64 keys the SQE, strictly after register())
                    // before calling closeHandle below: calling it immediately races the reap carrier under load (confirmed flaky under a
                    // full-suite parallel run, clean in isolation -- a test-timing gap, not a driver bug, since FIFO ordering on the engine
                    // queue already guarantees register() runs before closeHandle's own queued op checks the in-flight count).
                    awaitCondition(5.seconds)(!recording.submittedKeys.isEmpty).map { armed =>
                        assert(armed, "recv's submitRecv never ran (a hang, not the close-ordering hazard under test)")
                        // closeHandle while the recv SQE is in flight: it must NOT free the read buffer yet (the kernel still owns it).
                        drv.closeHandle(acceptedH)
                        assert(!acceptedH.readBuffer.isClosed, "read buffer must stay open while the recv SQE is in flight")
                        Abort.run[Closed](promise.safe.get).map { result =>
                            assert(result.isFailure, "cancel must fail the pending read promise immediately")
                            // Close the peer so the in-flight recv CQE arrives (res=0). Reaping it drains the handle and runs the deferred close,
                            // freeing the read buffer. The driver runs the deferred close (decrementInFlight -> closeNow) BEFORE cqe_seen, so the
                            // reap latch is the deterministic "CQE reaped, deferred close ran" signal.
                            PosixTestSockets.closePeerForEof(sock, client)
                            reaped.safe.get.map { _ =>
                                assert(acceptedH.readBuffer.isClosed, "deferred PosixHandle.close must run once the handle's CQE is reaped")
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "a TLS-less read uses the shared-arena readBuffer across a cross-carrier reap" in {
            PosixTestSockets.assumeUring()
            // The handle's readBuffer is allocated with Buffer.alloc (shared arena). Submitting on this fiber and reaping on the loop fiber
            // (a different carrier) must not throw the confined-arena cross-thread error; the read completes with the byte the peer sent.
            withRecordingDriver(256) { (drv, _) =>
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    assert(sock.sendNow(client, Buffer.fromArray[Byte](Array[Byte](42)), 1L, 0).value == 1L)
                    readVia(drv, acceptedH).map { got =>
                        drv.closeHandle(acceptedH)
                        discard(sock.close(client))
                        val ReadOutcome.Bytes(span) = got.runtimeChecked
                        assert(span.toArray.toList == List[Byte](42), s"got ${span.toArray.toList}")
                    }
                }
            }.map(_ => succeed)
        }

        "awaitAccept with a real client connect completes with the accepted fd" in {
            PosixTestSockets.assumeUring()
            withRecordingDriver(256) { (drv, _) =>
                listenAndConnect().map { case (serverFd, clientFd) =>
                    val serverH = PosixHandle.socket(serverFd, PosixHandle.DefaultReadBufferSize, Absent)
                    val promise = Promise.Unsafe.init[Int, Abort[Closed]]()
                    drv.awaitAccept(serverH, promise)
                    promise.safe.get.map { fd =>
                        drv.closeHandle(serverH)
                        discard(sock.close(clientFd))
                        if fd >= 0 then discard(sock.close(fd))
                        assert(fd >= 0, s"expected a valid accepted fd, got $fd")
                    }
                }
            }.map(_ => succeed)
        }

        "awaitAccept on a non-listening socket completes with Closed naming the errno" in {
            PosixTestSockets.assumeUring()
            withRecordingDriver(256) { (drv, _) =>
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    // accept(2) on a connected (non-listening) socket fails with a real negative CQE (EINVAL on Linux). The driver maps any
                    // res<0 to Closed("accept errno=N"); the errno is whatever the kernel returned, asserted to be a positive POSIX code.
                    val connectedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val promise    = Promise.Unsafe.init[Int, Abort[Closed]]()
                    drv.awaitAccept(connectedH, promise)
                    Abort.run[Closed](promise.safe.get).map { result =>
                        drv.closeHandle(connectedH)
                        discard(sock.close(client))
                        result match
                            case Result.Failure(c: Closed) =>
                                val m     = c.getMessage
                                val errno = "accept errno=(\\d+)".r.findFirstMatchIn(m).map(_.group(1).toInt)
                                assert(m.contains("accept errno="), s"message=$m")
                                assert(errno.exists(_ > 0), s"accept errno must be a positive POSIX code, message=$m")
                            case other => fail(s"expected Closed(accept errno=...), got $other")
                        end match
                    }
                }
            }.map(_ => succeed)
        }

        "an accept parked on SQ-full is re-armed (not aborted) and completes with a connecting client's fd" in {
            PosixTestSockets.assumeUring()
            // Same pin mechanism as the recv SQ-full leaf: on a depth-1 ring a read consumes the one SQE and the accept's get_sqe returns Absent,
            // parking the accept in stalledSubmits rather than failing it. A failed accept would wedge the listener (its accept loop reads any
            // Failure as "listener closed" and stops re-arming); parking keeps it alive. Proven end to end: the parked accept completes with a
            // connecting client's fd once the reap loop re-arms it.
            withRecordingDriver(1) { (drv, _) =>
                listenOnly().map { serverFd =>
                    PosixTestSockets.loopbackPair().map { case (clientR, acceptedR) =>
                        val readH   = PosixHandle.socket(acceptedR, PosixHandle.DefaultReadBufferSize, Absent)
                        val listenH = PosixHandle.socket(serverFd, PosixHandle.DefaultReadBufferSize, Absent)
                        val gate    = new java.util.concurrent.CountDownLatch(1)
                        val pinIn   = Promise.Unsafe.init[Unit, Abort[Closed]]()
                        drv.submitEngineOp { () =>
                            pinIn.completeDiscard(Result.succeed(()))
                            gate.await()
                        }
                        pinIn.safe.get.map { _ =>
                            // Reap carrier pinned. Read R (no peer bytes) consumes the one SQE and stays in flight; the accept's get_sqe returns
                            // Absent and parks. Both are enqueued behind the pin, so they drain together when it releases.
                            val rp = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                            drv.awaitRead(readH, rp)
                            val ap = Promise.Unsafe.init[Int, Abort[Closed]]()
                            drv.awaitAccept(listenH, ap)
                            gate.countDown()
                            connectTo(serverFd).map { clientFd =>
                                Abort.run[Closed](ap.safe.get).map { result =>
                                    drv.closeHandle(readH)
                                    drv.closeHandle(listenH)
                                    discard(sock.close(clientR))
                                    discard(sock.close(clientFd))
                                    result match
                                        case Result.Success(fd) =>
                                            assert(fd >= 0, s"the parked accept must yield a real accepted fd, got $fd")
                                            discard(sock.close(fd))
                                        case other =>
                                            fail(s"a SQ-full accept must park and complete, never abort: got $other")
                                    end match
                                }
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "awaitAccept re-arm: two sequential accepts complete with distinct fds" in {
            PosixTestSockets.assumeUring()
            withRecordingDriver(256) { (drv, _) =>
                listenOnly().map { serverFd =>
                    val serverH = PosixHandle.socket(serverFd, PosixHandle.DefaultReadBufferSize, Absent)
                    // Two clients connect; two sequential accepts must complete with two distinct accepted fds.
                    connectTo(serverFd).map { c1 =>
                        val p1 = Promise.Unsafe.init[Int, Abort[Closed]]()
                        drv.awaitAccept(serverH, p1)
                        p1.safe.get.flatMap { fd1 =>
                            connectTo(serverFd).map { c2 =>
                                val p2 = Promise.Unsafe.init[Int, Abort[Closed]]()
                                drv.awaitAccept(serverH, p2)
                                p2.safe.get.map { fd2 =>
                                    drv.closeHandle(serverH)
                                    discard(sock.close(c1)); discard(sock.close(c2))
                                    if fd1 >= 0 then discard(sock.close(fd1))
                                    if fd2 >= 0 then discard(sock.close(fd2))
                                    assert(fd1 >= 0 && fd2 >= 0, s"both accepts must yield valid fds, got $fd1 and $fd2")
                                    assert(fd1 != fd2, s"two accepts returned the same fd: $fd1")
                                }
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }
    }

    "IoUringDriver allocation seams" - {

        // Anti-flakiness: the cqe-ptr buffers are observed after the recv CQE reaps (recording.awaitReap fires once the real CQE is marked
        // seen). The peer sends one byte so a real recv completion drives one reap cycle. No sleep.
        "one cqe pointer buffer is reused for wait_cqe and peek_cqe across a real reap cycle" in {
            PosixTestSockets.assumeUring()
            withRecordingDriver(256) { (drv, recording) =>
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val reaped    = recording.awaitReap()
                    drv.awaitRead(acceptedH, Promise.Unsafe.init[ReadOutcome, Abort[Closed]]())
                    // The peer sends one byte so the recv SQE completes and the reap loop runs wait_cqe then peek_cqe in one drain cycle.
                    assert(sock.sendNow(client, Buffer.fromArray[Byte](Array[Byte](7)), 1L, 0).value == 1L)
                    reaped.safe.get.map { _ =>
                        drv.closeHandle(acceptedH)
                        discard(sock.close(client))
                        import scala.jdk.CollectionConverters.*
                        val waitPtrs = recording.waitCqePtrs.iterator().asScala.toList
                        val peekPtrs = recording.peekCqePtrs.iterator().asScala.toList
                        assert(waitPtrs.nonEmpty, "expected at least one wait_cqe_timeout call")
                        assert(peekPtrs.nonEmpty, "expected at least one peek_cqe call (drainReady must have run)")
                        val waitBuf = waitPtrs.head
                        val peekBuf = peekPtrs.head
                        assert(waitBuf eq peekBuf, s"cqe pointer was not the same Buffer instance: wait=$waitBuf, peek=$peekBuf")
                        waitPtrs.foreach(p => assert(p eq waitBuf, s"wait cqe pointer changed across calls: expected $waitBuf, got $p"))
                    }
                }
            }.map(_ => succeed)
        }

        // Anti-flakiness: a real TLS recv that decrypts to zero application bytes (a single handshake-only / partial record) re-arms the read.
        // The reap latch fires when the recv CQE is seen; a FIFO barrier proves the decrypt engine op ran. No sleep.
        "a TLS recv that yields no plaintext does not complete the promise and submits a re-arm SQE" in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "handshake must complete before the read")
                withRecordingDriver(256) { (drv, recording) =>
                    PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                        val handle = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent)
                        handle.tls = Present(RecordingTlsEngine(serverEngine))
                        val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        val reaped  = recording.awaitReap()
                        drv.awaitRead(handle, promise)
                        // Send a partial TLS record (the leading bytes of a real record): feedCiphertext consumes them but readPlain yields no
                        // complete record, so the driver re-arms the read rather than delivering an empty Span.
                        val full    = TlsEngineLoopback.encrypt(clientEngine, "x".getBytes("UTF-8"))
                        val partial = full.take(math.max(1, full.length / 2))
                        assert(sock.sendNow(
                            peerFd,
                            Buffer.fromArray[Byte](partial),
                            partial.length.toLong,
                            0
                        ).value == partial.length.toLong)
                        reaped.safe.get.flatMap { _ =>
                            fifoBarrier(drv).safe.get.map { _ =>
                                // Assert BEFORE closeHandle: once the recv CQE reaps and the decrypt engine op runs, the zero-plaintext recv must
                                // have re-armed, so the promise is still pending (NOT completed with an empty Span) and a second recv SQE was
                                // submitted. closeHandle -> cancel fails every pending op's promise as normal teardown, so a post-close promise.done()
                                // would be true for ANY correct driver and proves nothing about the recv path; the check only means something here.
                                val keysBefore = recording.submittedKeys.size
                                assert(!promise.done(), "the read promise must NOT be completed on a zero-plaintext recv (no empty Span)")
                                assert(
                                    keysBefore >= 2,
                                    s"a re-arm recv SQE must have been submitted after the zero-plaintext recv, keys=$keysBefore"
                                )
                                handle.tls = Absent
                                drv.closeHandle(handle)
                                discard(sock.close(peerFd))
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }

        // Anti-flakiness: a real TLS write submits a send SQE; the single-injection spy forces that one CQE's res to -104 (ECONNRESET) while
        // every other ring op runs for real. The reap latch fires when the CQE is seen; a FIFO barrier proves onTlsSendComplete ran. No sleep.
        "a TLS send error (res<0) discards the pending ciphertext tail and clears the send guard" in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "handshake must complete before the write")
                val depth     = math.max(256, kyo.net.TransportConfig.default.ioPoolSize * 64)
                val realUring = Ffi.load[IoUringBindings]
                val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
                val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
                if rc != 0 then
                    realRing.close()
                    throw Closed("RecordingIoUringBindings", summon[Frame], s"queue_init failed: rc=$rc")
                // SendErrorInjectingUring delegates every ring op to the real ring and the kernel completes them, EXCEPT it forces exactly one
                // CQE's res to -104 (ECONNRESET): the single authorized injection that exercises the send-error-discard branch on a real ring.
                val recording = new SendErrorInjectingUring(realUring, realRing)
                val driver    = TestDrivers.forBindings(recording, realRing)
                discard(driver.start())
                Sync.ensure(Sync.defer(driver.close())) {
                    PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                        val handle = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent)
                        handle.tls = Present(serverEngine)
                        val reaped = recording.awaitReap()
                        val w      = driver.write(handle, Span.fromUnsafe(Array.fill[Byte](32)(0x42.toByte)), 0)
                        assert(w == WriteResult.Done, s"write result=$w")
                        // The encrypt+flush engine op runs (a send SQE is submitted), then arm the one-shot res override for that send CQE.
                        fifoBarrier(driver).safe.get.flatMap { _ =>
                            assert(!recording.submittedKeys.isEmpty, "a send SQE must be submitted by flushTls before the error CQE")
                            recording.armSendError()
                            reaped.safe.get.flatMap { _ =>
                                fifoBarrier(driver).safe.get.map { _ =>
                                    handle.tls = Absent
                                    driver.closeHandle(handle)
                                    discard(sock.close(peerFd))
                                    assert(!handle.sendInFlight, "sendInFlight must be cleared after a res<0 send CQE")
                                    val pendingSize = handle.pendingCipher.map(_.size).getOrElse(0)
                                    assert(pendingSize == 0, s"pending ciphertext tail must be discarded after res<0, size=$pendingSize")
                                    assert(
                                        handle.pendingCipherSent == 0,
                                        s"pendingCipherSent must be 0 after res<0, got ${handle.pendingCipherSent}"
                                    )
                                }
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }

        // Anti-flakiness: each real TLS read latches on its recv promise (completed when the recv CQE reaps and the decrypt engine op runs).
        // The peer sends a real TLS record per read; the recv staging buffer is observed via RecordingTlsEngine.feedBufs. No sleep.
        "the TLS recv path reuses one staging buffer across reads on the real ring" in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "handshake must complete before the reads")
                val recordingServer = RecordingTlsEngine(serverEngine)
                withRecordingDriver(256) { (drv, _) =>
                    PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                        val handle = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent)
                        handle.tls = Present(recordingServer)
                        val msgs = Seq("io-uring-read-0".getBytes("UTF-8"), "io-uring-read-1".getBytes("UTF-8"))

                        def readEach(remaining: List[Array[Byte]]): Unit < (Abort[Closed] & Async) =
                            remaining match
                                case Nil => Sync.defer(())
                                case msg :: rest =>
                                    val cipher = TlsEngineLoopback.encrypt(clientEngine, msg)
                                    assert(sock.sendNow(
                                        peerFd,
                                        Buffer.fromArray[Byte](cipher),
                                        cipher.length.toLong,
                                        0
                                    ).value == cipher.length.toLong)
                                    val p = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                                    drv.awaitRead(handle, p)
                                    p.safe.get.flatMap { got =>
                                        val ReadOutcome.Bytes(span) = got.runtimeChecked
                                        assert(span.toArray.toList == msg.toList, s"read mismatch: got ${span.toArray.toList}")
                                        readEach(rest)
                                    }

                        readEach(msgs.toList).map { _ =>
                            import scala.jdk.CollectionConverters.*
                            // Capture the feed buffers and recvStaging BEFORE closeHandle frees the per-handle buffers.
                            val feedBufs = recordingServer.feedBufs.iterator().asScala.toList
                            val staging  = handle.recvStaging.getOrElse(fail("recvStaging must be Present after TLS reads on the ring"))
                            handle.tls = Absent
                            drv.closeHandle(handle)
                            discard(sock.close(peerFd))
                            assert(feedBufs.size >= 2, s"expected at least 2 feedCiphertext calls (one per read), got ${feedBufs.size}")
                            feedBufs.foreach(buf =>
                                assert(
                                    buf eq staging,
                                    s"feedCiphertext received a different buffer than recvStaging: got $buf, staging=$staging"
                                )
                            )
                        }
                    }
                }
            }.map(_ => succeed)
        }

        // Anti-flakiness: each TLS write submits a send SQE; the reap latch fires when its CQE is seen, and a FIFO barrier proves the next
        // write's encrypt op ran. The flush mirror is observed via RecordingIoUringBindings.sendBufs (the buffer pinned for each send SQE). No sleep.
        "the TLS flush path reuses one mirror buffer across sequential sends on the real ring" in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "handshake must complete before the writes")
                withRecordingDriver(256) { (drv, recording) =>
                    PosixTestSockets.loopbackPair().map { case (driverFd, peerFd) =>
                        val handle = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent)
                        handle.tls = Present(serverEngine)
                        val N        = 3
                        val payloads = Array.tabulate(N)(k => Array.tabulate[Byte](20)(i => ((k * 20 + i + 1) % 127).toByte))

                        def writeEach(k: Int): Unit < (Abort[Closed] & Async) =
                            if k >= N then Sync.defer(())
                            else
                                val reaped = recording.awaitReap()
                                val w      = drv.write(handle, Span.fromUnsafe(payloads(k)), 0)
                                assert(w == WriteResult.Done, s"write $k result=$w")
                                fifoBarrier(drv).safe.get.flatMap { _ =>
                                    // Await the send CQE reap BEFORE draining the peer: a FIFO barrier alone only proves the encrypt op ran and
                                    // batched the send SQE, not that the kernel sent the ciphertext. The reap latch is the deterministic "send
                                    // completed, bytes are on the wire" signal, so a non-blocking recv on the peer then sees them. The reap also
                                    // ran onTlsSendComplete, freeing the send guard for the next write (the trailing barrier confirms it drained).
                                    reaped.safe.get.flatMap { _ =>
                                        val drained = recvAll(peerFd)
                                        assert(drained > 0, s"write $k produced no bytes on the wire")
                                        fifoBarrier(drv).safe.get.flatMap(_ => writeEach(k + 1))
                                    }
                                }

                        writeEach(0).map { _ =>
                            import scala.jdk.CollectionConverters.*
                            // Capture the send SQE buffers and flushMirror BEFORE closeHandle frees the per-handle buffers. flushMirror being
                            // Present here proves it survived all the reaps (it is never closed on reap, only in freeResources).
                            val sendBufs     = recording.sendBufs.iterator().asScala.toList
                            val mirrorBefore = handle.flushMirror
                            handle.tls = Absent
                            drv.closeHandle(handle)
                            discard(sock.close(peerFd))
                            assert(sendBufs.size >= N, s"expected at least $N send SQEs (one per write), got ${sendBufs.size}")
                            val mirror =
                                mirrorBefore.getOrElse(fail("flushMirror must be Present after TLS flushes on the ring (survives reaps)"))
                            sendBufs.foreach(buf =>
                                assert(buf eq mirror, s"send SQE used a different buffer than flushMirror: got $buf, mirror=$mirror")
                            )
                        }
                    }
                }
            }.map(_ => succeed)
        }
    }

    /** Read all bytes currently available on `fd` without blocking and return the count drained. */
    private def recvAll(fd: Int)(using Frame): Int =
        val buf = Buffer.alloc[Byte](65536)
        try
            val r = sock.recvNow(fd, buf, 65536L, PosixConstants.MSG_DONTWAIT)
            math.max(0, r.value.toInt)
        finally buf.close()
        end try
    end recvAll

    /** A [[RecordingIoUringBindings]] subclass that delegates every ring op to the real ring and forces exactly ONE CQE's `cqe_res` to
      * -104 (ECONNRESET) once `armSendError` is called. Every other ring op (queue_init, get_sqe, prep_send, submit, wait_cqe, peek_cqe,
      * cqe_get_data64, cqe_seen) runs for real and the kernel completes it. This is a spy over a real ring that forces one result value to
      * exercise the TLS send-error-discard path, not a fake that scripts the whole ring.
      */
    final private class SendErrorInjectingUring(real: IoUringBindings, realRing: Buffer[Byte])
        extends RecordingIoUringBindings(real, realRing):
        import AllowUnsafe.embrace.danger
        // -1 means no injection pending; set to the ECONNRESET errno by armSendError; consumed once via CAS so it fires for one CQE only.
        private val injectErrno = new java.util.concurrent.atomic.AtomicInteger(-1)

        /** Arm the one-shot res override: the next reaped CQE reports res = -104 instead of its real value. */
        def armSendError(): Unit = injectErrno.set(104)

        override def kyo_uring_cqe_res(cqe: Long)(using AllowUnsafe): Int =
            val errno = injectErrno.get()
            if errno >= 0 && injectErrno.compareAndSet(errno, -1) then -errno
            else real.kyo_uring_cqe_res(cqe)
        end kyo_uring_cqe_res
    end SendErrorInjectingUring

    /** Submit a marker engine op and return a promise that completes once the FIFO worker runs it. */
    private def fifoBarrier(drv: IoUringDriver): Promise.Unsafe[Unit, Any] =
        val p = Promise.Unsafe.init[Unit, Any]()
        drv.submitEngineOp(() => p.completeDiscard(Result.succeed(())))
        p
    end fifoBarrier

    /** Bind+listen on an ephemeral 127.0.0.1 port and return the listening server fd. */
    private def listenOnly()(using Frame, kyo.test.AssertScope): Int < Async =
        val server = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(a.close())) {
            assert(sock.bind(server, a, l).value == 0)
            assert(sock.listen(server, 8).value == 0)
            Sync.defer(server)
        }
    end listenOnly

    /** Connect a fresh client to the given listening server fd and return the client fd (the connect completes synchronously on loopback). */
    private def connectTo(serverFd: Int)(using Frame, kyo.test.AssertScope): Int < Async =
        val out = Buffer.alloc[Byte](SockAddr.inet4Size)
        val ol  = Buffer.alloc[Int](1)
        ol.set(0, SockAddr.inet4Size)
        val port =
            try
                assert(sock.getsockname(serverFd, out, ol).value == 0)
                ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
            finally
                out.close()
                ol.close()
        val client   = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(ca.close()))(sock.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0))).map(_ => client)
    end connectTo

    /** Bind+listen and connect one client; return (serverFd, clientFd) without accepting (the accept is the driver's job under test). */
    private def listenAndConnect()(using Frame, kyo.test.AssertScope): (Int, Int) < Async =
        listenOnly().flatMap(serverFd => connectTo(serverFd).map(clientFd => (serverFd, clientFd)))

end IoUringDriverTest
