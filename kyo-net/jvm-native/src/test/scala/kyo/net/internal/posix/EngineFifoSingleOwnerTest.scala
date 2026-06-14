package kyo.net.internal.posix

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.IoDriver
import scala.jdk.CollectionConverters.*

/** Deterministic mutual-exclusion guard for the per-driver engine FIFO single-owner invariant (`submitEngineOp` / `drainEngineOps` plus the
  * `engineWorkerActive` CAS), the centerpiece of the engine-serialization design: no two carriers ever run an engine op concurrently for one
  * driver, so a stateful TLS engine is never touched by two ops at once.
  *
  * The existing coverage of this invariant is a real-TLS load test (48 connections x 120 rounds). That is probabilistic: it makes overlap
  * LIKELY but never pins it, so a regression that opened a narrow overlap window (an ABA on the CAS, a second worker spawned) could pass
  * flakily. These leaves pin the invariant directly and the SAME body runs against BOTH drivers ([[PollerIoDriver]] over a real epoll/kqueue
  * backend and [[IoUringDriver]] over a real io_uring ring), so the io_uring engine FIFO, which carries identical machinery yet had ZERO engine
  * coverage, is exercised too. Both drivers drain their engine FIFO the same way: the FIFO is drained ONLY on the always-running loop carrier
  * (submitEngineOp enqueues; the poller's drainEngineOps runs from drainFifos on the poll loop, the io_uring's from reapLoop), so both arms start
  * their loop (it bounded-waits ~100ms on the idle fd/ring, draining the engine queue each cycle) for an enqueued engine op to execute. On a host
  * where the production-depth ring cannot init the io_uring arm is omitted and the backend-independent invariant is still asserted on the poller arm.
  *
  * Two interleavings, both deterministic with no sleep:
  *
  *   - "re-entrant submission while an op is in flight" (every platform): op A, while RUNNING on the worker, re-entrantly submits op B. The
  *     single worker is busy running A, so `drainEngineOps` cannot poll B until A returns. The leaf asserts the max observed in-flight count is
  *     1 (A and B never overlap), B did not start while A held the worker, and the ops ran in submission order. This proves a concurrent
  *     submission during an in-flight op neither starts a second worker nor overlaps the running op.
  *   - "two carriers submit concurrently, A pins the worker" (JVM only, where a second carrier exists): op A blocks on a `CountDownLatch`
  *     INSIDE the op (the sanctioned way to pin the single worker; it is a latch the test releases, not a sleep), while a SEPARATE fiber submits
  *     op B. The leaf asserts that while A holds the worker B has NOT entered (max in-flight 1), then releases A and asserts B then runs, in
  *     submission order. A genuinely-concurrent two-carrier submission is what the `engineWorkerActive` CAS defends; this is the path the
  *     re-entrant variant cannot reach on a single-threaded runtime.
  */
class EngineFifoSingleOwnerTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Records what the test engine ops observe: the live in-flight count, its peak, and the order ops entered, so the leaf can assert both
      * that the ops ran in submission order and that they never overlapped.
      */
    final private class FifoProbe:
        val inFlight    = new AtomicInteger(0)
        val maxInFlight = new AtomicInteger(0)
        val entries     = new ConcurrentLinkedQueue[String]()

        /** Record entry into an engine op: bump the in-flight count, track its peak, append the label to the run order. Returns the in-flight
          * count observed while inside the op (so a re-entrant test can assert no overlap at the exact moment a second op was submitted).
          */
        def enter(label: String): Int =
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet(prev => math.max(prev, now))
            discard(entries.add(label))
            now
        end enter

        def exit(): Unit = discard(inFlight.decrementAndGet())

        def order: List[String] = entries.iterator().asScala.toList
    end FifoProbe

    /** The drivers under test, each paired with a close thunk that releases its resources. Both expose the same `submitEngineOp` FIFO (the
      * invariant under test), so the leaf body is identical across them.
      *
      * The poller arm is built over a real epoll/kqueue backend (jvm-native always has one) with its poll loop started, because the poller engine
      * FIFO drains only on the poll-loop carrier; the poller fd has no registered fds, so the poll loop only bounded-waits and drains the engine
      * queue each cycle. The io_uring arm is built over a REAL io_uring ring through a [[RecordingIoUringBindings]] spy with its reap loop started,
      * the same way (the io_uring engine FIFO drains only on the reap carrier). The io_uring arm is included only when a production-depth ring inits
      * on this host: off Linux, or where the cgroup `io_uring.max` cap blocks it, only the cross-platform poller arm runs (the FIFO invariant is
      * backend-independent, so the poller arm asserts it on every platform; the io_uring arm asserts it additionally on native Linux).
      */
    private def drivers(using Frame): List[(String, IoDriver[PosixHandle], () => Unit)] =
        val real     = PollerBackend.default()
        val pollerFd = real.create()
        val poller   = TestDrivers.forBackend(RecordingPollerBackend(real), pollerFd)
        // The poller engine FIFO is now poll-loop-driven (submitEngineOp enqueues; drainEngineOps runs from drainFifos on the poll loop, mirroring how
        // IoUringDriver drains its engine FIFO on the reap loop), so the reap-loop equivalent MUST run for an enqueued engine op to execute. Start the
        // poll loop: it bounded-waits (~100ms) on the poller fd (no fds registered) and drains the engine queue each cycle; close() signals it to exit.
        discard(poller.start())
        val pollerArm: List[(String, IoDriver[PosixHandle], () => Unit)] =
            List(("PollerIoDriver", poller, () => poller.close()))
        pollerArm ++ uringArm.toList
    end drivers

    /** Build the io_uring arm over a REAL ring at production depth, or Absent if the ring cannot init on this host (off Linux the native
      * io_uring symbol lookup fails; in a cgroup-capped container the production-depth init returns a non-zero errno). The whole construction
      * is guarded so the cross-platform poller arm still runs where io_uring is absent.
      */
    private def uringArm(using Frame): Maybe[(String, IoDriver[PosixHandle], () => Unit)] =
        try
            val depth     = math.max(256, kyo.net.TransportConfig.default.ioPoolSize * 64)
            val realUring = Ffi.load[IoUringBindings]
            val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
            val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
            if rc.value != 0 then
                realRing.close()
                Absent
            else
                val recording = RecordingIoUringBindings(realUring, realRing)
                val uring     = TestDrivers.forBindings(recording, realRing)
                // The io_uring engine FIFO drains only on the reap carrier (submitEngineOp enqueues; drainEngineOps runs from reapLoop), so the
                // reap loop MUST run for an enqueued engine op to execute. It bounded-waits (~100ms) on an idle ring with no registered fds, draining
                // the engine queue each cycle; close() signals it to exit. (The poller arm above starts its poll loop for the same reason: both
                // drivers now drain the engine FIFO only on their always-running loop carrier.)
                discard(uring.start())
                Present(("IoUringDriver", uring, () => uring.close()))
            end if
        catch case _: Throwable => Absent
    end uringArm

    "engine FIFO single-owner mutual exclusion" - {
        "re-entrant submission while an op is in flight never overlaps and runs in submission order (both drivers)" in {
            // Drive the interleave with no sleep: op A, while RUNNING on the single worker, re-entrantly submits op B. drainEngineOps cannot
            // poll B until A returns, so A and B can never overlap; the leaf records the in-flight peak and the run order to prove it.
            // assumePoller: the PollerIoDriver leaf builds a real epoll/kqueue backend (its poll loop is never started). jvm-native always has one.
            PosixTestSockets.assumePoller()
            Kyo.foreach(drivers) { case (name, driver, closeDriver) =>
                val probe       = new FifoProbe
                val bRanDuringA = new java.util.concurrent.atomic.AtomicBoolean(false)
                val bRan        = Promise.Unsafe.init[Unit, Any]()

                val opB: () => Unit = () =>
                    discard(probe.enter(s"$name-B"))
                    probe.exit()
                    bRan.completeDiscard(Result.succeed(()))

                val opA: () => Unit = () =>
                    discard(probe.enter(s"$name-A"))
                    // Submit B from INSIDE A (the worker is busy running A). A correct FIFO defers B until A returns.
                    driver.submitEngineOp(opB)
                    // B must not have run yet: the single worker is still executing A.
                    if probe.order.contains(s"$name-B") then bRanDuringA.set(true)
                    probe.exit()

                driver.submitEngineOp(opA)
                bRan.safe.get.map { _ =>
                    closeDriver()
                    assert(probe.maxInFlight.get() == 1, s"[$name] two engine ops overlapped (max in-flight ${probe.maxInFlight.get()})")
                    assert(!bRanDuringA.get(), s"[$name] op B started while op A still held the worker")
                    assert(
                        probe.order == List(s"$name-A", s"$name-B"),
                        s"[$name] engine ops ran out of submission order: ${probe.order}"
                    )
                }
            }.map(_ => succeed)
        }

        "two carriers submitting concurrently never overlap; B waits for A to release the worker (both drivers, JVM)" in {
            // Genuine two-carrier concurrency: op A pins the single worker by blocking on a CountDownLatch INSIDE the op (a latch the test
            // releases, NOT a sleep), while a separate fiber submits op B. The engineWorkerActive CAS must keep B from running on a second
            // worker: while A holds the latch, B has not entered. After the latch releases, the same worker runs B. JVM-only because a blocked
            // carrier needs a second carrier to make progress; the re-entrant leaf above covers the single-threaded runtimes.
            if !kyo.internal.Platform.isJVM then Sync.defer(succeed)
            else
                PosixTestSockets.assumePoller()
                Kyo.foreach(drivers) { case (name, driver, closeDriver) =>
                    val probe    = new FifoProbe
                    val gate     = new CountDownLatch(1)
                    val aEntered = Promise.Unsafe.init[Unit, Any]()
                    val bRan     = Promise.Unsafe.init[Unit, Any]()

                    val opA: () => Unit = () =>
                        discard(probe.enter(s"$name-A"))
                        aEntered.completeDiscard(Result.succeed(()))
                        // Pin the worker: block until the test releases the gate. Sanctioned blocking latch (not a sleep) used to hold the
                        // exact interleaving where B is submitted concurrently while A owns the worker.
                        gate.await()
                        probe.exit()

                    val opB: () => Unit = () =>
                        discard(probe.enter(s"$name-B"))
                        probe.exit()
                        bRan.completeDiscard(Result.succeed(()))

                    driver.submitEngineOp(opA)
                    for
                        _ <- aEntered.safe.get
                        // A is now running and pinning the worker. Submit B from a SEPARATE fiber so the submission is genuinely concurrent.
                        bFiber <- Fiber.initUnscoped(Sync.defer(driver.submitEngineOp(opB)))
                        _      <- bFiber.get
                        // While A still holds the worker, B must NOT have entered (single owner). Snapshot the state observed under the pin,
                        // then release A BEFORE asserting: a failed assertion must never leave the pinned worker parked (it would wedge every
                        // later test on this scheduler). The same worker then drains B.
                        orderUnderPin    = probe.order
                        inFlightUnderPin = probe.inFlight.get()
                        _                = gate.countDown()
                        _ = assert(
                            !orderUnderPin.contains(s"$name-B"),
                            s"[$name] op B ran on a second worker while op A held the single worker: $orderUnderPin"
                        )
                        _ = assert(
                            inFlightUnderPin == 1,
                            s"[$name] in-flight count was $inFlightUnderPin while A pinned the worker"
                        )
                        _ <- bRan.safe.get
                    yield
                        closeDriver()
                        assert(probe.maxInFlight.get() == 1, s"[$name] engine ops overlapped (max in-flight ${probe.maxInFlight.get()})")
                        assert(
                            probe.order == List(s"$name-A", s"$name-B"),
                            s"[$name] engine ops ran out of submission order: ${probe.order}"
                        )
                    end for
                }.map(_ => succeed)
        }
    }

end EngineFifoSingleOwnerTest
