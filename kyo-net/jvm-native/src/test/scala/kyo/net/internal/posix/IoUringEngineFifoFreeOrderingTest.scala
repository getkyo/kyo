package kyo.net.internal.posix

import java.util.concurrent.CountDownLatch
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.tls.TlsEngine
import kyo.net.internal.tls.TlsEngineLoopback
import kyo.net.internal.tls.TlsRealEngines

/** Probes the [[IoUringDriver]] engine-FIFO single-owner machinery (`submitEngineOp` / `drainEngineOps` / `engineWorkerActive`) under
  * CONCURRENT mixed-kind engine ops plus the FIFO-routed engine free, over a REAL io_uring ring and a REAL post-handshake BoringSSL engine.
  * It is the io_uring twin of the path [[PollerIoDriverWriteRaceTest]] guards on the poller. [[EngineFifoSingleOwnerTest]] pinned the bare
  * single-owner invariant over both drivers; this drives it harder, over the io_uring driver specifically, with real read-decrypt /
  * write-encrypt engine ops AND the engine `free()` routed through `engineFreeSink`.
  *
  * `IoUringDriver` carries no handle guard and never touches the TLS engine directly: the only engine wiring it has is `closeHandle`
  * installing `handle.engineFreeSink = op => submitEngineOp(op)`. So the io_uring engine-vs-free coordination is PURELY the FIFO ordering: the
  * free, enqueued through `engineFreeSink` from `PosixHandle.freeResources`, is FIFO-ordered behind every read/write engine op already submitted
  * for the connection, and the single worker runs them one at a time. The read/write engine ops are submitted exactly as `PosixTransport`
  * submits them on the io_uring arm: `ioDriver.submitEngineOp { () => engine.<op> }`.
  *
  * A [[RecordingTlsEngine]] over a real engine records the live in-flight count, its peak, the run order, whether any op ran after `free()`
  * (the use-after-free signal), and how many times `free()` ran (the double-free signal). Real crypto runs on every op (the engine is
  * post-handshake so `readPlain` / `writePlain` do not crash on an uninitialized session). The leaves assert: (a) max in-flight is 1 (no two
  * engine ops, of any kind, overlap on the one engine); (b) `free()` runs EXACTLY once and ONLY after every read/write engine op has run
  * (FIFO-ordered behind them via `engineFreeSink`, never concurrent with one); (c) ops run in submission order; (d) the `PosixHandle` guard bails
  * a write attempted after close, so a post-close op never runs on freed engine state.
  *
  * The leaf is gated by [[PosixTestSockets.assumeUring]] (cancel off Linux / where the production-depth ring cannot init) and
  * [[TlsRealEngines.assumeTlsReady]] (cancel where no TLS provider is staged). The io_uring engine FIFO drains only on the reap carrier, so the
  * reap loop is started; the ring has no registered fds (pure engine ops), so it only bounded-waits and drains the engine queue. No sleep:
  * ordering is driven by the FIFO and a latch the test releases.
  */
class IoUringEngineFifoFreeOrderingTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Build a driver over a REAL io_uring ring at production depth and a fresh socket handle carrying a real post-handshake engine wrapped in
      * a [[RecordingTlsEngine]], plus a one-byte scratch buffer the engine ops read/write. The engine is attached to `handle.tls` so
      * `PosixHandle.freeResources` (reached via `closeHandle` -> `requestClose`) routes its `free()` through `engineFreeSink`, exactly as a live
      * TLS connection's teardown does. The driver owns the server engine's `free()` (it must run exactly once, the property under test); the
      * client peer is freed by the cleanup thunk, and the server is freed by the cleanup only if the driver did not (which would already fail
      * the freeCount assertion), so neither leaks nor double-frees.
      */
    private def setup()(using
        Frame,
        kyo.test.AssertScope
    ): (IoUringDriver, PosixHandle, RecordingTlsEngine, TlsEngine, Buffer[Byte], () => Unit) =
        val depth     = math.max(256, kyo.net.TransportConfig.default.ioPoolSize * 64)
        val realUring = Ffi.load[IoUringBindings]
        val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
        val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
        if rc.value != 0 then
            realRing.close()
            throw Closed("RecordingIoUringBindings", summon[Frame], s"queue_init failed: rc=${rc.value}")
        val recording = RecordingIoUringBindings(realUring, realRing)
        val driver    = TestDrivers.forBindings(recording, realRing)
        // The io_uring engine FIFO drains only on the reap carrier (submitEngineOp enqueues; drainEngineOps runs from reapLoop), so the reap loop
        // MUST run for an enqueued engine op to execute. The ring has no registered fds (pure engine ops), so the reap loop only bounded-waits and
        // drains the engine queue each cycle; close() signals it to exit.
        discard(driver.start())
        val client = TlsRealEngines.singleEngine(isServer = false)
        val server = TlsRealEngines.singleEngine(isServer = true)
        assert(TlsEngineLoopback.handshake(client, server), "handshake must complete so the engine ops run on a live session")
        val recordingServer = RecordingTlsEngine(server)
        val handle          = PosixHandle.socket(7, PosixHandle.DefaultReadBufferSize, Absent)
        handle.tls = Present(recordingServer)
        val scratch = Buffer.alloc[Byte](1)
        val cleanup = () =>
            driver.close()
            scratch.close()
            // The driver frees the server engine exactly once via the FIFO; if it did not (a bug the freeCount assertion catches) free it here
            // so the native session is reclaimed. The client peer is always freed here.
            if recordingServer.freeCount.get() == 0 then server.free()
            client.free()
        (driver, handle, recordingServer, client, scratch, cleanup)
    end setup

    "IoUringDriver engine FIFO: concurrent mixed ops + FIFO-routed free" - {
        "re-entrant: read+write engine ops then a routed free never overlap; free runs once, last, on a live engine (all platforms)" in {
            // Op A (read-decrypt), while RUNNING on the single worker, re-entrantly submits op B (write-encrypt) and then triggers closeHandle.
            // closeHandle routes the engine free F through engineFreeSink onto the SAME FIFO. The worker is busy running A, so neither B nor F
            // can start until A returns; the FIFO then runs B, then F. No sleep: the FIFO defers them behind A by construction.
            PosixTestSockets.assumeUring()
            TlsRealEngines.assumeTlsReady()
            val (driver, handle, engine, _, scratch, cleanup) = setup()
            val freeRan                                       = Promise.Unsafe.init[Unit, Any]()

            val opB: () => Unit = () => discard(engine.writePlain(scratch, 1))
            val opA: () => Unit = () =>
                discard(engine.readPlain(scratch, 1))
                // Submit B from INSIDE A (worker is busy with A): a correct FIFO defers B until A returns.
                driver.submitEngineOp(opB)
                // Route the engine free exactly as production does: install the engineFreeSink closeHandle installs, then drive
                // PosixHandle.freeResources (via close), which enqueues engine.free() onto the engine FIFO through the sink. The free is
                // submitted SYNCHRONOUSLY here (holders == 0, so requestClose frees now), so it lands in the FIFO behind A and B and ahead of
                // the marker below. closeHandle's full TLS-close path additionally emits close_notify and DEFERS this same freeResources call
                // to the reap carrier (after the close_notify send CQE reaps); that deferral is a separate concern covered by the deferred-close
                // leaf in IoUringDriverTest, so this leaf drives freeResources straight through PosixHandle.close to isolate the FIFO-ordering
                // property. The routing under test (free serialized on the FIFO behind read/write engine ops) is identical either way.
                handle.engineFreeSink = op => driver.submitEngineOp(op)
                PosixHandle.close(handle)
                // Submit a final marker op AFTER the free is enqueued so we have a deterministic "free has run" signal: it runs strictly
                // after F in FIFO order, so when it fires the free has already run on the worker.
                driver.submitEngineOp(() => freeRan.completeDiscard(Result.succeed(())))

            driver.submitEngineOp(opA)
            freeRan.safe.get.map { _ =>
                cleanup()
                assert(engine.maxInFlight.get() == 1, s"engine ops overlapped (max in-flight ${engine.maxInFlight.get()})")
                assert(engine.freeCount.get() == 1, s"free() must run exactly once, ran ${engine.freeCount.get()} times")
                assert(!engine.usedAfterFree.get(), "use-after-free: a read/write engine op ran after free() on the FIFO")
                assert(
                    engine.order == List("read", "write", "free"),
                    s"engine ops ran out of submission order or free was not last: ${engine.order}"
                )
                // (d) the PosixHandle guard bails any op acquired after close, so a post-close write never touches the freed engine.
                assert(!handle.beginWrite(), "beginWrite must bail (false) after the handle is closed")
                assert(handle.tls.isEmpty, "the engine slot must be cleared once the routed free ran")
            }
        }

        "concurrent: many read/write engine ops across fibers + routed free hold single-owner; free is exactly-once and strictly last (JVM)" in {
            // Genuine multi-fiber concurrency stressing the engineWorkerActive CAS: op P0 pins the single worker by blocking on a latch INSIDE
            // the op (a latch the test releases, NOT a sleep), while many separate fibers concurrently submit read/write engine ops. While P0
            // holds the worker none of them may enter (single owner). The free is enqueued (via closeHandle) only after every read/write op has
            // been submitted, so it is FIFO-ordered strictly last; releasing P0 lets the one worker drain them all in order, then the free.
            if !kyo.internal.Platform.isJVM then Sync.defer(succeed)
            else
                PosixTestSockets.assumeUring()
                TlsRealEngines.assumeTlsReady()
                val (driver, handle, engine, _, scratch, cleanup) = setup()
                val opCount                                       = 64
                val gate                                          = new CountDownLatch(1)
                val pinEntered                                    = Promise.Unsafe.init[Unit, Any]()
                val freeRan                                       = Promise.Unsafe.init[Unit, Any]()

                // P0 pins the single FIFO worker: it is a RAW thunk (not an engine op, so it does not appear in engine.order). It signals it
                // has the worker, then blocks on the gate (sanctioned latch, not a sleep). While it blocks, the one worker cannot drain any
                // other entry, so no read/write engine op can run; a second worker (the single-owner-violation) would let one run.
                val pin: () => Unit = () =>
                    pinEntered.completeDiscard(Result.succeed(()))
                    gate.await()

                driver.submitEngineOp(pin)
                for
                    _ <- pinEntered.safe.get
                    // P0 now pins the worker. Submit opCount read/write engine ops from SEPARATE fibers so the submissions are genuinely
                    // concurrent against the single worker and each other.
                    _ <- Async.foreach(0 until opCount, opCount) { i =>
                        Fiber.initUnscoped(Sync.defer {
                            if i % 2 == 0 then driver.submitEngineOp(() => discard(engine.readPlain(scratch, 1)))
                            else driver.submitEngineOp(() => discard(engine.writePlain(scratch, 1)))
                        }).map(_.get)
                    }
                    // While P0 still holds the worker, NO submitted engine op may have entered: the single owner is blocked, so the FIFO is
                    // frozen. A non-empty run order (or non-zero in-flight) here means a second worker ran an op concurrently. Snapshot the
                    // state observed under the pin; the assertion runs only AFTER the gate releases below, so a failed assertion can never
                    // leave the pinned worker parked (which would wedge every later test on this scheduler).
                    inFlightUnderPin = engine.inFlight.get()
                    orderUnderPin    = engine.order
                    // Enqueue the routed free LAST (after every read/write op is already in the FIFO), then a completion marker after it. The
                    // free is routed through the engineFreeSink closeHandle installs and driven via PosixHandle.freeResources synchronously
                    // (holders == 0), so it lands on the FIFO behind every read/write op and ahead of the marker; see the single-op leaf above
                    // for why this drives freeResources directly rather than through closeHandle's reap-deferred close path.
                    _ = (handle.engineFreeSink = op => driver.submitEngineOp(op))
                    _ = PosixHandle.close(handle)
                    _ = driver.submitEngineOp(() => freeRan.completeDiscard(Result.succeed(())))
                    // Release P0; the one worker now drains pin, every read/write op, then the free, then the marker, in FIFO order.
                    _ = gate.countDown()
                    _ = assert(
                        inFlightUnderPin == 0 && orderUnderPin.isEmpty,
                        s"an engine op ran while P0 pinned the single worker (in-flight=$inFlightUnderPin, order=$orderUnderPin)"
                    )
                    _ <- freeRan.safe.get
                yield
                    cleanup()
                    assert(engine.maxInFlight.get() == 1, s"engine ops overlapped (max in-flight ${engine.maxInFlight.get()})")
                    assert(engine.freeCount.get() == 1, s"free() must run exactly once, ran ${engine.freeCount.get()} times")
                    assert(!engine.usedAfterFree.get(), "use-after-free: a read/write engine op ran after free() on the FIFO")
                    // free must be strictly last among the engine ops: opCount read/write ops, then free (the pin is a raw thunk, not recorded).
                    val ops = engine.order
                    assert(
                        ops.length == opCount + 1,
                        s"expected ${opCount + 1} engine ops ($opCount read/write + free), got ${ops.length}: $ops"
                    )
                    assert(ops.last == "free", s"free was not the last engine op: ${ops.takeRight(5)}")
                    assert(ops.count(_ == "free") == 1, s"free appeared more than once in the run order: $ops")
                    assert(!handle.beginWrite(), "beginWrite must bail (false) after the handle is closed")
                    assert(handle.tls.isEmpty, "the engine slot must be cleared once the routed free ran")
                end for
        }
    }

end IoUringEngineFifoFreeOrderingTest
