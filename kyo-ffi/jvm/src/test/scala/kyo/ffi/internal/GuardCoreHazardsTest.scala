package kyo.ffi.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kyo.AllowUnsafe
import kyo.discard
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.ffi.Ffi.CloseOutcome
import kyo.ffi.Test
import kyo.internal.JvmUnsafeBuffer
import kyo.internal.UnsafeLayout
import scala.concurrent.duration.*

/** Pins the deferred-teardown semantics of [[GuardCore]] under drain timeout and related edges:
  *
  *   1. After the drain timeout expires with `inFlight > 0`, `platformCloser` is NOT invoked synchronously, the JVM arena / Native
  *      retained-slot memory is left valid so in-flight callbacks keep reading good data. `close()` returns [[CloseOutcome.TimedOut]] and
  *      the guard sits in `StateClosing` awaiting the last `endCallback`.
  *   2. When that last `endCallback` arrives, the platform closer runs exactly once (CAS-guarded) and the guard transitions to
  *      `StateClosed`.
  *   3. Documented LIFO buffer close order is asserted explicitly, buffers close regardless of drain outcome since the close side owns
  *      them.
  *   4. Clean-drain, never-opened, and idempotent-close paths are pinned to [[CloseOutcome.Clean]] / [[CloseOutcome.AlreadyClosed]] with
  *      exact-once `platformCloser` invocation.
  */
class GuardCoreHazardsTest extends Test:

    // Every leaf mutates the process-global GuardCore.drainTimeoutNanos and spawns a real closer thread it then waits
    // on, so the leaves must run one at a time rather than under the default parallel execution: a parallel run both
    // races the global timeout knob across leaves and, under CPU load, starves the closer thread past a tight wait.
    override def config = super.config.sequential

    "platformCloser is DEFERRED while in-flight callback has not returned after drain timeout" in {
        val original = GuardCore.drainTimeoutNanos
        try
            GuardCore.drainTimeoutNanos = 10_000_000L // 10 ms

            @volatile var platformCloserRan: Boolean = false
            @volatile var coreRef: GuardCore | Null  = null

            val platformCloser: () => Unit = () => platformCloserRan = true

            val core = new GuardCore(platformCloser, () => ())
            coreRef = core

            // Simulate a retained callback that never returns (e.g. a long-running C op).
            assert(core.beginCallback() == true)
            assert(core.inFlight.get() == 1)

            val latch                               = new CountDownLatch(1)
            @volatile var closeResult: CloseOutcome = CloseOutcome.AlreadyClosed
            val t = new Thread(
                () =>
                    closeResult = core.close()
                    latch.countDown()
                ,
                "uaf-hazard-closer"
            )
            t.setDaemon(true)
            t.start()

            assert(latch.await(30, TimeUnit.SECONDS) == true)

            // SAFETY: platformCloser did NOT run, deferred because a callback is still in flight.
            // On JVM this means the arena holding the callback's upcall stub stays alive.
            assert(platformCloserRan == false)
            assert(closeResult == CloseOutcome.TimedOut)

            // The callback is still "running", inFlight stays at 1 until the caller invokes endCallback.
            assert(core.inFlight.get() == 1)
            // State remains at StateClosing (not StateClosed) until the deferred path fires.
            assert(core.state.get() == GuardCore.StateClosing)
            assert(core.isClosed == true) // isClosed is true for Closing OR Closed
        finally
            GuardCore.drainTimeoutNanos = original
        end try
    }

    "JvmGuard: drain timeout leaves the arena valid while a callback is still in flight" in {
        // End-to-end version of the safety property: uses a real JvmGuard wrapping a real Panama arena
        // (the same arena that would hold upcall stubs and scratch for callbacks). After the drain
        // timeout expires, the arena MUST still be usable, any still-executing retained callback
        // reading from the arena must succeed. The arena is closed later, when the last endCallback
        // drops inFlight to zero.
        val original = GuardCore.drainTimeoutNanos
        try
            GuardCore.drainTimeoutNanos = 10_000_000L // 10 ms

            import kyo.Frame
            given frame: Frame = Frame.internal
            val guard          = Ffi.Guard.open()

            // Grab the arena that the guard manages. This is the same arena the generated
            // retained-callback code would install its upcall stub into.
            val jvmGuard = guard.asInstanceOf[kyo.ffi.internal.JvmGuard]
            val arena    = jvmGuard.unsafeArena
            val seg      = arena.allocate(8L).nn
            import java.lang.foreign.ValueLayout
            seg.set(ValueLayout.JAVA_LONG, 0L, 0x42L)

            // Simulate a retained callback in flight (never ends).
            assert(jvmGuard.core.beginCallback() == true)

            // Close on another thread, drain will timeout and proceed (but NOT tear the arena down).
            val latch                               = new CountDownLatch(1)
            @volatile var closeResult: CloseOutcome = CloseOutcome.AlreadyClosed
            val t = new Thread(
                () =>
                    closeResult = guard.close()
                    latch.countDown()
                ,
                "uaf-e2e-closer"
            )
            t.setDaemon(true)
            t.start()

            assert(latch.await(30, TimeUnit.SECONDS) == true)

            // Callback is still "in flight".
            assert(jvmGuard.core.inFlight.get() == 1)

            // Arena is still alive, the read that a callback would perform succeeds.
            assert(seg.get(ValueLayout.JAVA_LONG, 0L) == 0x42L)
            assert(closeResult == CloseOutcome.TimedOut)

            // Now let the callback complete, the deferred platformCloser fires and the arena closes.
            jvmGuard.core.endCallback()
            assert(jvmGuard.core.state.get() == GuardCore.StateClosed)

            // After the deferred close the arena is gone, reads now fail.
            val ex = intercept[IllegalStateException](seg.get(ValueLayout.JAVA_LONG, 0L))
            assert(ex.getMessage.contains("closed"))
        finally
            GuardCore.drainTimeoutNanos = original
        end try
    }

    "buffers close in LIFO (most-recently-registered first) order" in {
        val events = new java.util.concurrent.ConcurrentLinkedQueue[Int]()

        def trackingBuffer(tag: Int): Buffer[Byte] =
            import AllowUnsafe.embrace.danger
            val closer: () => Unit = () => discard(events.add(tag))
            val seg                = java.lang.foreign.Arena.ofShared().nn.allocate(1L)
            val buf                = new JvmUnsafeBuffer(seg, 1L, closer)
            val bc                 = new BufferCore(1, 1L, owned = true)
            val rawHandle          = Buffer.Raw.wrap(seg)
            new Buffer[Byte](buf, summon[UnsafeLayout[Byte]], bc, rawHandle)
        end trackingBuffer

        val core = new GuardCore(() => (), () => ())
        core.registerBuffer(trackingBuffer(1)) // registered first → closed last
        core.registerBuffer(trackingBuffer(2))
        core.registerBuffer(trackingBuffer(3)) // registered last  → closed first

        assert(core.close() == CloseOutcome.Clean)

        // LIFO = 3, 2, 1
        val order = new java.util.ArrayList[Int]()
        events.forEach(i => discard(order.add(i)))
        assert(order.size == 3)
        assert(order.get(0) == 3)
        assert(order.get(1) == 2)
        assert(order.get(2) == 1)
    }

    "LeakOnTimeout + later endCallback: platformCloser runs exactly once" in {
        val original = GuardCore.drainTimeoutNanos
        try
            GuardCore.drainTimeoutNanos = 10_000_000L // 10 ms

            val platformCloserCount = new AtomicInteger(0)
            val postCloseCount      = new AtomicInteger(0)
            val core = new GuardCore(
                () => discard(platformCloserCount.incrementAndGet()),
                () => discard(postCloseCount.incrementAndGet())
            )

            // Simulate an in-flight callback.
            assert(core.beginCallback() == true)

            // Close on another thread, drain times out.
            val latch                               = new CountDownLatch(1)
            @volatile var closeResult: CloseOutcome = CloseOutcome.AlreadyClosed
            val t = new Thread(
                () =>
                    closeResult = core.close()
                    latch.countDown()
                ,
                "leak-on-timeout-closer"
            )
            t.setDaemon(true)
            t.start()
            assert(latch.await(30, TimeUnit.SECONDS) == true)
            assert(closeResult == CloseOutcome.TimedOut)

            // Neither the platform closer nor the postCloseHook has run yet.
            assert(platformCloserCount.get() == 0)
            assert(postCloseCount.get() == 0)
            assert(core.state.get() == GuardCore.StateClosing)

            // Callback returns, deferred closer fires exactly once.
            core.endCallback()
            assert(platformCloserCount.get() == 1)
            assert(postCloseCount.get() == 1)
            assert(core.state.get() == GuardCore.StateClosed)
            assert(core.isClosed == true)
        finally
            GuardCore.drainTimeoutNanos = original
        end try
    }

    "normal drain: closeAwait returns Clean and platformCloser ran before return" in {
        val platformCloserCount = new AtomicInteger(0)
        val postCloseCount      = new AtomicInteger(0)
        val core = new GuardCore(
            () => discard(platformCloserCount.incrementAndGet()),
            () => discard(postCloseCount.incrementAndGet())
        )

        // Fully-complete callback lifecycle before close.
        assert(core.beginCallback() == true)
        core.endCallback()

        // Drain is trivial (inFlight already 0). closeWithPolicy mirrors Guard.closeAwait's internal path.
        assert(core.closeWithPolicy(5.seconds.toNanos) == CloseOutcome.Clean)
        assert(platformCloserCount.get() == 1)
        assert(postCloseCount.get() == 1)
        assert(core.state.get() == GuardCore.StateClosed)
    }

    "close() on never-opened callbacks: returns Clean, platformCloser runs immediately" in {
        val platformCloserCount = new AtomicInteger(0)
        val postCloseCount      = new AtomicInteger(0)
        val core = new GuardCore(
            () => discard(platformCloserCount.incrementAndGet()),
            () => discard(postCloseCount.incrementAndGet())
        )

        assert(core.close() == CloseOutcome.Clean)
        assert(platformCloserCount.get() == 1)
        assert(postCloseCount.get() == 1)
        assert(core.state.get() == GuardCore.StateClosed)
    }

    "close() twice: first Clean, second AlreadyClosed, platformCloser runs exactly once" in {
        val platformCloserCount = new AtomicInteger(0)
        val postCloseCount      = new AtomicInteger(0)
        val core = new GuardCore(
            () => discard(platformCloserCount.incrementAndGet()),
            () => discard(postCloseCount.incrementAndGet())
        )

        assert(core.close() == CloseOutcome.Clean)
        assert(core.close() == CloseOutcome.AlreadyClosed)
        assert(platformCloserCount.get() == 1)
        assert(postCloseCount.get() == 1)
    }

end GuardCoreHazardsTest
