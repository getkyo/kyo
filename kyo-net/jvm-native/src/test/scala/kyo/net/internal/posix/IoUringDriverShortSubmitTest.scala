package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** Reproduction + regression guard (libuv #4598, CWE-252 mishandled return value) in [[IoUringDriver]].
  *
  * `io_uring_submit` returns the number of SQEs it consumed, or a negative errno (`-EBUSY` / `-EAGAIN` when the completion queue is full and
  * cannot be flushed). Discarding the return of `io_uring_submit(ring)` in [[IoUringDriver.flushSubmits]] throws it away: on a short
  * count (fewer SQEs submitted than prepared) or a negative errno the prepared-but-unsubmitted SQEs would stay in the SQ ring while their `pending`
  * entries persist forever, so the matching CQEs never arrive and the op hangs silently. `pendingSubmits` was already reset to 0 by the
  * `getAndSet`, so no later `flushSubmits` re-attempts the dropped SQEs: a per-connection silent hang, exactly libuv #4598's class.
  *
  * A real short/failed submit (a genuinely full CQ ring under back-pressure) is not deterministically reachable from Scala, so the short count
  * is reproduced at the bindings seam: a [[ShortSubmitInjectingUring]] subclass of [[RecordingIoUringBindings]] forces exactly ONE
  * `io_uring_submit` call to report a short count without actually flushing the SQE (it returns a count below what was prepared and does NOT
  * delegate, so the prepared recv SQE sits unsubmitted), then every later submit delegates to the real ring. The real socket still holds the
  * peer's bytes, so once the stranded SQE is re-submitted the recv reaps and delivers them. Without the re-submit this leaf FAILS for the right
  * reason: the recv SQE is dropped, its `pending` entry never reaps, and the read hangs to the `Async.timeout` ceiling.
  *
  * Gate: [[PosixTestSockets.assumeUring]] (a real io_uring ring at production depth). On a non-Linux host or a cgroup-capped container the leaf
  * cancels cleanly (TestCanceled), so it is CI-validated on native Linux.
  *
  * Anti-flakiness: the one-shot short-submit override is armed BEFORE [[IoUringDriver.awaitRead]] prepares the recv SQE, so the very first
  * submit that flushes this driver's recv SQE is the one forced short; nothing else is in flight. The leaf synchronizes on the read promise
  * resolving (the real reap) rather than a timer; `Async.timeout` is only the deadlock ceiling so a stranded SQE fails the test fast instead of
  * hanging the suite. No sleep, no busy-spin.
  */
class IoUringDriverShortSubmitTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** A [[RecordingIoUringBindings]] subclass that delegates every ring op to the real ring except a one-shot `io_uring_submit` override: once
      * `armShortSubmit` is called, the next `io_uring_submit` returns `shortCount` (a count below the prepared SQE count) WITHOUT delegating, so
      * the prepared SQE is left in the SQ ring unsubmitted (the kernel never sees it). Every later submit delegates to the real ring, so the
      * fix's re-submit of the stranded SQE flushes it for real and the recv completes. This is the single-value-injection style of the test's
      * EintrInjectingUring (which forces -EINTR on one CQE), applied to the submit return.
      */
    final private class ShortSubmitInjectingUring(real: IoUringBindings, realRing: Buffer[Byte])
        extends RecordingIoUringBindings(real, realRing):
        import AllowUnsafe.embrace.danger
        // false means no injection pending; armShortSubmit arms it; consumed once via CAS so it forces a short count for exactly one submit.
        private val injectPending = new java.util.concurrent.atomic.AtomicBoolean(false)
        // The count the forced-short submit reports. 0 means "nothing was submitted" while the SQE stays prepared in the ring.
        private val shortCount = 0

        /** Arm the one-shot short-submit override: the next io_uring_submit reports `shortCount` without flushing the prepared SQE. */
        def armShortSubmit(): Unit = injectPending.set(true)

        override def io_uring_submit(ring: Buffer[Byte])(using AllowUnsafe): Int =
            if injectPending.compareAndSet(true, false) then shortCount
            else real.io_uring_submit(realRing)
        end io_uring_submit
    end ShortSubmitInjectingUring

    /** Allocate a real io_uring ring at production depth, wrap it in a [[ShortSubmitInjectingUring]] spy, build a driver, run `body`, then close
      * the driver (which tears the ring down via io_uring_queue_exit).
      */
    private def withShortSubmitDriver[A](
        body: (IoUringDriver, ShortSubmitInjectingUring) => A < (Abort[Closed] & Async)
    )(using Frame): A < (Abort[Closed] & Async) =
        val depth     = math.max(256, kyo.net.TransportConfig.default.ioPoolSize * 64)
        val realUring = Ffi.load[IoUringBindings]
        val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
        val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
        if rc != 0 then
            realRing.close()
            throw Closed("ShortSubmitInjectingUring", summon[Frame], s"queue_init failed: rc=$rc")
        val recording = new ShortSubmitInjectingUring(realUring, realRing)
        val driver    = TestDrivers.forBindings(recording, realRing)
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(body(driver, recording))
    end withShortSubmitDriver

    "IoUringDriver short submit" - {

        "a recv SQE dropped by a short io_uring_submit is re-submitted and still delivers the data, not stranded" in {
            PosixTestSockets.assumeUring()
            withShortSubmitDriver { (drv, recording) =>
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val payload   = Array.tabulate[Byte](16)(i => (i + 1).toByte)
                    // The peer sends the bytes first; they sit in accepted's recv buffer waiting for a read. Arm the one-shot short-submit
                    // override BEFORE awaitRead so the very first io_uring_submit that would flush this recv SQE reports a short count and does
                    // not flush it; flushSubmits detects the short count and re-submits the stranded SQE, which the kernel then completes with the data.
                    assert(sock.sendNow(client, Buffer.fromArray[Byte](payload), payload.length.toLong, 0).value == 16L)
                    recording.armShortSubmit()
                    val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    drv.awaitRead(acceptedH, promise)
                    Abort.run[Timeout | Closed](Async.timeout(5.seconds)(promise.safe.get)).map { outcome =>
                        drv.closeHandle(acceptedH)
                        discard(sock.close(client))
                        outcome match
                            case Result.Success(ReadOutcome.Bytes(got)) =>
                                assert(
                                    got.toArray.toList == payload.toList,
                                    s"the re-submitted recv must deliver the full payload; got ${got.toArray.toList}"
                                )
                            case Result.Failure(_: Timeout) =>
                                fail(
                                    "the read hung: a short io_uring_submit dropped the recv SQE and it was never re-submitted (libuv #4598)"
                                )
                            case Result.Failure(c: Closed) =>
                                fail(s"the recv was failed Closed instead of re-submitted: $c")
                            case other => fail(s"unexpected read outcome: $other")
                        end match
                    }
                }
            }.map(_ => succeed)
        }
    }

end IoUringDriverShortSubmitTest
