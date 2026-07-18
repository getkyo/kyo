package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** Reproduction + regression guard: [[IoUringDriver]]'s reap loop under-classified a transient `io_uring_enter` errno as a fatal ring fault.
  *
  * The fused submit+wait (`kyo_uring_submit_and_wait_timeout`) can return `-ENOMEM` when the kernel could not allocate memory for THIS call
  * right now, a momentary condition under combined memory pressure from many concurrently-running rings on one host (exactly a full-suite
  * test run), alongside the already-recognized transient `-EINTR`/`-EAGAIN`/`-EBUSY`/`-ETIME`. If `reapRcContinues` did not
  * recognize `-ENOMEM`, the reap loop would classify it as a genuinely fatal ring rc and self-destruct the WHOLE ring
  * (`io_uring_queue_exit`), tearing down every connection it carried, including one with a genuinely in-flight, kernel-owned recv SQE that
  * would otherwise have completed normally moments later.
  *
  * This is the mechanism behind `IoUringDriverTest`'s "closeHandle defers..." leaf flaking under full-suite load (its own comment already
  * documents the symptom without naming the cause): once the ring exits, `closeHandle`'s `ringExited` fast path frees a handle's read buffer
  * immediately, bypassing the in-flight defer discipline entirely. It is also the likely source of a companion `CLOSE_WAIT` leak:
  * a connection that was simply OPEN (not yet closing) when the ring self-destructs has its fd abandoned, with no
  * owner left alive to ever close it once the peer eventually does.
  *
  * A real `-ENOMEM` from the kernel is not deterministically reachable from Scala, so it is reproduced at the bindings seam: a
  * [[ReapFatalRcInjectingUring]] subclass of [[RecordingIoUringBindings]] forces exactly ONE `kyo_uring_submit_and_wait_timeout` call to
  * report `-ENOMEM` instead of delegating to the real ring, AFTER a real recv SQE has already reached the kernel (so the SQE is genuinely
  * in flight when the fake rc arrives); every other call, before and after, delegates to the real ring. Without the `-ENOMEM` recognition,
  * this leaf's recv promise would fail `Closed` ("driver closed") because `teardownRing` ran; with it, the loop treats `-ENOMEM` as a normal
  * continuable turn, the next real wait reaps the SQE for real, and the recv delivers the peer's actual bytes.
  *
  * Anti-flakiness: the one-shot override is armed, then the SAME call that submits the recv SQE (`awaitRead`) wakes the currently-parked
  * reap loop, so the armed rc fires on the very next `kyo_uring_submit_and_wait_timeout` call made after the recv SQE reaches the kernel,
  * not on some earlier or later turn. No sleep, no busy-spin; the leaf synchronizes on the read promise resolving, bounded by
  * `Async.timeout` as the deadlock ceiling, not the pass condition.
  */
class IoUringDriverReapTransientErrnoTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Forces exactly one `kyo_uring_submit_and_wait_timeout` call to report the armed `rc` (as an already-done fiber, matching the real
      * binding's `@Ffi.blocking` inline-completion contract on JVM/Native) without delegating to the real ring; every other call, before
      * arming and after the one-shot fires, delegates normally.
      */
    final private class ReapFatalRcInjectingUring(real: IoUringBindings, realRing: Buffer[Byte])
        extends RecordingIoUringBindings(real, realRing):
        import AllowUnsafe.embrace.danger
        private val injectPending        = new java.util.concurrent.atomic.AtomicBoolean(false)
        @volatile private var injectedRc = 0

        /** Arm the one-shot override: the next `kyo_uring_submit_and_wait_timeout` call returns `rc` instead of delegating. */
        def armFatalRc(rc: Int): Unit =
            injectedRc = rc
            injectPending.set(true)

        override def kyo_uring_submit_and_wait_timeout(ring: Buffer[Byte], cqePtr: Buffer[Long], timeoutNs: Long)(using
            AllowUnsafe
        ): Fiber.Unsafe[Int, Any] =
            if injectPending.compareAndSet(true, false) then Fiber.Unsafe.fromResult(Result.succeed(injectedRc))
            else super.kyo_uring_submit_and_wait_timeout(ring, cqePtr, timeoutNs)
        end kyo_uring_submit_and_wait_timeout
    end ReapFatalRcInjectingUring

    /** Allocate a real io_uring ring at production depth, wrap it in a [[ReapFatalRcInjectingUring]] spy, build a driver, run `body`, then
      * close the driver (which tears the ring down via `io_uring_queue_exit`).
      */
    private def withInjectingDriver[A](
        body: (IoUringDriver, ReapFatalRcInjectingUring) => A < (Abort[Closed] & Async)
    )(using Frame): A < (Abort[Closed] & Async) =
        val depth     = math.max(256, kyo.net.TransportConfig.default.ioPoolSize * 64)
        val realUring = Ffi.load[IoUringBindings]
        val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
        val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
        if rc != 0 then
            realRing.close()
            throw Closed("ReapFatalRcInjectingUring", summon[Frame], s"queue_init failed: rc=$rc")
        val recording = new ReapFatalRcInjectingUring(realUring, realRing)
        val driver    = TestDrivers.forBindings(recording, realRing)
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(body(driver, recording))
    end withInjectingDriver

    "IoUringDriver reap-loop transient errno classification" - {

        "a transient -ENOMEM from the fused submit+wait does not tear the ring down; the in-flight recv still completes" in {
            PosixTestSockets.assumeUring()
            withInjectingDriver { (drv, recording) =>
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val payload   = Array.tabulate[Byte](16)(i => (i + 1).toByte)
                    assert(sock.sendNow(client, Buffer.fromArray[Byte](payload), payload.length.toLong, 0).value == 16L)
                    // Arm BEFORE awaitRead: the recv SQE this call submits reaches the real kernel via the real io_uring_submit (never
                    // overridden); the SAME call's wake returns the currently-parked wait first (a real, unarmed rc), so the armed -ENOMEM
                    // fires on the loop's NEXT submit+wait call, made once this recv SQE is already genuinely in flight.
                    recording.armFatalRc(-PosixConstants.ENOMEM)
                    val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    drv.awaitRead(acceptedH, promise)
                    Abort.run[Timeout | Closed](Async.timeout(5.seconds)(promise.safe.get)).map { outcome =>
                        drv.closeHandle(acceptedH)
                        discard(sock.close(client))
                        outcome match
                            case Result.Success(ReadOutcome.Bytes(got)) =>
                                assert(
                                    got.toArray.toList == payload.toList,
                                    s"the recv must still deliver the full payload after a transient -ENOMEM; got ${got.toArray.toList}"
                                )
                            case Result.Failure(_: Timeout) =>
                                fail("the read hung: a transient -ENOMEM stalled the reap loop instead of retrying")
                            case Result.Failure(c: Closed) =>
                                fail(
                                    s"the recv was failed Closed (\"$c\") instead of completing: a transient -ENOMEM tore the whole ring " +
                                        "down (reapRcContinues did not recognize it), exactly the mechanism behind the closeHandle-defers " +
                                        "flake and its companion CLOSE_WAIT leak under full-suite load"
                                )
                            case other => fail(s"unexpected read outcome: $other")
                        end match
                    }
                }
            }.map(_ => succeed)
        }
    }

end IoUringDriverReapTransientErrnoTest
