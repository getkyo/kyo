package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Reproduction + regression guard for the io_uring counterpart of the POSIX recv/send EINTR handling issue (CWE-252 mishandled
  * return value) in [[IoUringDriver]].
  *
  * io_uring surfaces a signal-interrupted recv/send as a completion CQE whose `res` is `-EINTR`: a non-blocking recv/send interrupted by a
  * signal before any byte is transferred. POSIX says to retry such a call (no data moved, the socket is unchanged), exactly as
  * [[PollerIoDriver]] retries `EINTR` in place on the readiness arm and as the accept path retries it. Without `-EINTR` special-casing, the
  * io_uring read CQE would fail the read promise `Closed` on any `res < 0` ("read errno=-res") and the send CQE completions would treat any
  * negative `res` as a hard error and discard the pending tail, so a single interrupted completion would drop a healthy
  * connection (read) or silently lose the outbound bytes (send), the same gap the poller had.
  *
  * A real mid-syscall signal is not deterministically injectable, so the interruption is reproduced at the bindings seam: an
  * [[EintrInjectingUring]] subclass of [[RecordingIoUringBindings]] forces exactly ONE reaped CQE's `res` to `-EINTR` (every other ring op
  * delegates to the real ring and the kernel completes it). The real socket still holds its data, so the driver's retry then delivers the
  * received bytes / re-sends the unsent ciphertext for real. Without the retry these leaves would FAIL for the right reason: the injected `-EINTR` fails
  * the read `Closed` (read leaf) and discards the unsent tail so the peer never receives the bytes (send leaf).
  *
  * Gate: [[PosixTestSockets.assumeUring]] (a real io_uring ring at production depth). On a cgroup-capped host the leaf cancels cleanly
  * (TestCanceled), so these are CI-validated on native Linux.
  *
  * Anti-flakiness: each leaf arms the one-shot `-EINTR` override BEFORE the targeted operation is prepped, so the injection both suppresses
  * the real transfer (a non-blocking recv on an empty socket; a length-0 send) and rewrites that operation's CQE, exactly the no-byte-moved
  * state a real signal interruption produces; nothing else is in flight on the fresh driver, so the one-shot can only hit the targeted op.
  * Leaves synchronize on the real reap (`cqeSeen` / `awaitReap`) and the engine FIFO barrier rather than a timer. `Async.timeout` is only the
  * deadlock ceiling. No sleep, no busy-spin.
  */
class IoUringDriverEintrRetryTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** A [[RecordingIoUringBindings]] subclass that models ONE real io_uring recv interrupted by a signal: a `-EINTR` CQE that moved NO byte.
      *
      * A real io_uring `IORING_OP_RECV` interrupted by a signal is CANCELED before transferring any data (io_uring(7); the socket is unchanged),
      * exactly like POSIX `recv(2)` returning `-EINTR`. A naive res-override on a NORMAL recv cannot model this: io_uring fills the user buffer
      * and drains the socket BEFORE the CQE exists, so forcing that completed-with-data CQE to `-EINTR` would silently consume the bytes and the
      * driver's (correct) re-submit would then read an empty socket and hang. So once `armEintr` is set, the next recv is submitted NON-BLOCKING
      * (`MSG_DONTWAIT`): on the empty socket it completes immediately with `-EAGAIN` having moved nothing, and that CQE's `res` is forced to
      * `-EINTR`. The data the test sends AFTER [[eintrFired]] is then delivered by the driver's re-submitted (normal, blocking) recv, proving the
      * `-EINTR` retry re-reads the untouched socket. Every other ring op runs for real and the kernel completes it.
      */
    final private class EintrInjectingUring(real: IoUringBindings, realRing: Buffer[Byte])
        extends RecordingIoUringBindings(real, realRing):
        import AllowUnsafe.embrace.danger
        // false means no injection pending; armEintr arms it; the recv prep adds MSG_DONTWAIT while set, and the CQE-res override consumes it
        // once via CAS so exactly one recv is forced to a no-data -EINTR.
        private val injectPending = new java.util.concurrent.atomic.AtomicBoolean(false)

        /** Completes when the armed recv's CQE has been forced to `-EINTR`, so the test sends its data only after the first (no-data) recv has
          * been interrupted and the driver's blocking re-submit is in flight. Pre-sending would let the first recv read the bytes (a state a real
          * io_uring `-EINTR` never produces).
          */
        val eintrFired: Promise.Unsafe[Unit, Any] = Promise.Unsafe.init[Unit, Any]()

        /** Arm the one-shot injection: the next recv is submitted non-blocking and its CQE forced to a no-data `-EINTR`. */
        def armEintr(): Unit = injectPending.set(true)

        // While armed, submit the recv non-blocking so it cannot transfer data on the empty socket (a real interrupted recv moves nothing). The
        // re-submit after the -EINTR runs with the driver's normal (blocking) flags because the CQE override below has cleared the arm by then.
        override def kyo_uring_prep_recv(sqe: Ffi.Handle[IoUringSqe], fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using
            AllowUnsafe
        ): Int =
            val effective = if injectPending.get() then flags | PosixConstants.MSG_DONTWAIT else flags
            super.kyo_uring_prep_recv(sqe, fd, buf, len, effective)
        end kyo_uring_prep_recv

        // While armed, prep the send with length 0 so it transfers NOTHING (a real interrupted send moves no byte; rewriting the CQE of a
        // send that actually delivered its bytes would make the driver's correct full-tail re-flush DUPLICATE them on the wire). The re-flush
        // after the -EINTR preps with the real length because the CQE override below has cleared the arm by then, so the peer receives
        // exactly one copy.
        override def kyo_uring_prep_send(sqe: Ffi.Handle[IoUringSqe], fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using
            AllowUnsafe
        ): Int =
            val effective = if injectPending.get() then 0L else len
            super.kyo_uring_prep_send(sqe, fd, buf, effective, flags)
        end kyo_uring_prep_send

        override def kyo_uring_cqe_res(cqe: Long)(using AllowUnsafe): Int =
            if injectPending.compareAndSet(true, false) then
                eintrFired.completeDiscard(Result.succeed(()))
                -PosixConstants.EINTR
            else real.kyo_uring_cqe_res(cqe)
        end kyo_uring_cqe_res
    end EintrInjectingUring

    /** Allocate a real io_uring ring at production depth, wrap it in an [[EintrInjectingUring]] spy, build a driver, run `body`, then close the
      * driver (which tears the ring down via io_uring_queue_exit).
      */
    private def withEintrDriver[A](
        body: (IoUringDriver, EintrInjectingUring) => A < (Abort[Closed] & Async)
    )(using Frame): A < (Abort[Closed] & Async) =
        val depth     = math.max(256, kyo.net.ioPoolSize() * 64)
        val realUring = Ffi.load[IoUringBindings]
        val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
        val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
        // io_uring_queue_init returns 0 on success, -errno on failure. Plain Int, not clamped.
        if rc != 0 then
            realRing.close()
            throw Closed("EintrInjectingUring", summon[Frame], s"queue_init failed: rc=$rc")
        val recording = new EintrInjectingUring(realUring, realRing)
        val driver    = TestDrivers.forBindings(recording, realRing)
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(body(driver, recording))
    end withEintrDriver

    /** Submit a marker engine op and return a promise that completes once the FIFO worker runs it, proving every engine op submitted before it
      * (the write's encrypt/flush) has run.
      */
    private def fifoBarrier(drv: IoUringDriver): Promise.Unsafe[Unit, Any] =
        val p = Promise.Unsafe.init[Unit, Any]()
        drv.submitEngineOp(() => p.completeDiscard(Result.succeed(())))
        p
    end fifoBarrier

    /** Drain `want` bytes from `fd` via recvNow into one running accumulator (the peer is a plain socket, no driver involved). */
    private def recvAll(fd: Int, want: Int)(using AllowUnsafe): Array[Byte] =
        val out = new java.io.ByteArrayOutputStream()
        val buf = Buffer.alloc[Byte](65536)
        try
            while out.size() < want do
                val r = sock.recvNow(fd, buf, 65536L, PosixConstants.MSG_DONTWAIT)
                val n = r.value.toInt
                if n > 0 then out.write(Buffer.copyToArray[Byte](buf, 0, n))
            end while
            out.toByteArray
        finally buf.close()
        end try
    end recvAll

    "IoUringDriver EINTR retry" - {

        "a read CQE that reaps -EINTR is retried (recv re-submitted), delivering the data, not failed Closed" in {
            PosixTestSockets.assumeUring()
            withEintrDriver { (drv, recording) =>
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val payload   = Array.tabulate[Byte](16)(i => (i + 1).toByte)
                    // Arm the one-shot injection BEFORE awaitRead: the driver's first recv is submitted non-blocking (MSG_DONTWAIT, spy override)
                    // so on the still-empty socket it completes immediately moving NO byte, and the spy forces that CQE to -EINTR (a real io_uring
                    // recv interrupted by a signal is canceled before transferring data; the socket is unchanged). The driver re-submits a normal
                    // blocking recv on the same promise. Only after `eintrFired` confirms that -EINTR fired (the re-submit is in flight on the empty
                    // socket) does the peer send, so the retried recv genuinely delivers the data the interrupted recv never touched. Pre-sending
                    // would let the first recv read the bytes (a state a real -EINTR never produces) and the retry would then hang on an empty socket.
                    recording.armEintr()
                    val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    drv.awaitRead(acceptedH, promise)
                    recording.eintrFired.safe.get.flatMap { _ =>
                        assert(sock.sendNow(client, Buffer.fromArray[Byte](payload), payload.length.toLong, 0).value == 16L)
                        Abort.run[Timeout | Closed](Async.timeout(5.seconds)(promise.safe.get)).map { outcome =>
                            drv.closeHandle(acceptedH)
                            discard(sock.close(client))
                            outcome match
                                case Result.Success(ReadOutcome.Bytes(got)) =>
                                    assert(
                                        got.toArray.toList == payload.toList,
                                        s"the retried recv must deliver the full payload; got ${got.toArray.toList}"
                                    )
                                case Result.Failure(_: Closed) =>
                                    fail(
                                        "a -EINTR read CQE was treated as a hard error and failed the read Closed; it must re-submit the recv (POSIX recv)"
                                    )
                                case Result.Failure(_: Timeout) =>
                                    fail("the read hung: an EINTR retry never re-submitted the recv to deliver the data")
                                case other => fail(s"unexpected read outcome: $other")
                            end match
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "a raw send CQE that reaps -EINTR is retried (re-flushed), delivering the bytes to the peer, not discarding the tail" in {
            PosixTestSockets.assumeUring()
            withEintrDriver { (drv, recording) =>
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val clientH = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
                    val payload = Array.tabulate[Byte](16)(i => (i + 1).toByte)
                    val reaped  = recording.awaitReap()
                    // Arm BEFORE the write: the prep-time injection must see the arm so the armed send is prepped with length 0 (it transfers
                    // nothing, like a real signal-interrupted send) and its CQE is forced to -EINTR. onRawSendComplete re-flushes
                    // the (entirely unsent) tail and the peer receives the payload exactly once; without the retry the negative res would discard the tail
                    // and the bytes would be lost. Nothing else is in flight on this fresh driver, so the armed one-shot can only hit this send.
                    recording.armEintr()
                    val w = drv.write(clientH, Span.fromUnsafe(payload), 0)
                    assert(w == WriteResult.Done, s"write result=$w")
                    fifoBarrier(drv).safe.get.flatMap { _ =>
                        reaped.safe.get.flatMap { _ =>
                            // After the -EINTR send CQE reaps, drive the FIFO so the re-flush (an engine op) runs, then drain the peer. The peer
                            // read is bounded by a deadline so the test fails fast rather than hanging if the bytes were dropped.
                            fifoBarrier(drv).safe.get.flatMap { _ =>
                                Abort.run[Timeout](Async.timeout(5.seconds)(Sync.defer(recvAll(accepted, payload.length)))).map { got =>
                                    drv.closeHandle(clientH)
                                    discard(sock.close(accepted))
                                    got match
                                        case Result.Success(bytes) =>
                                            assert(
                                                bytes.toList == payload.toList,
                                                s"the re-flushed send must deliver the full payload to the peer; got ${bytes.toList}"
                                            )
                                        case Result.Failure(_: Timeout) =>
                                            fail(
                                                "a -EINTR send CQE discarded the unsent tail; the peer never received the re-flushed bytes (POSIX send)"
                                            )
                                        case other => fail(s"unexpected drain outcome: $other")
                                    end match
                                }
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }
    }

end IoUringDriverEintrRetryTest
