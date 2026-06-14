package kyo.ffi.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kyo.discard
import kyo.ffi.Test

/** Native-only concurrency tests for the retained-callback pool.
  *
  * Bitset claim/release: the lock-free `AtomicLongArray` bitset underpinning `claimRetainedSlot_*` / `releaseRetainedSlot_*` must support
  * concurrent claim + release from many threads without data corruption, deadlock, or slot double-allocation.
  *
  * Backpressure: when the backpressure sys-prop is enabled, claims should block until a slot is released by another thread rather than
  * throwing `IllegalStateException` immediately. Default behavior (no sys-prop) is unchanged.
  */
class CallbackRegistryConcurrencyTest extends Test:

    // Mutates process-global state (System.setErr and/or a system property) and restores it, so the leaves must run
    // alone: under the default parallel leaf execution a sibling leaf observes the mutated global.
    override def config = super.config.sequential

    private def isParked(t: Thread): Boolean =
        val s = t.getState
        (s eq Thread.State.WAITING) || (s eq Thread.State.TIMED_WAITING)

    private def setProp(key: String, value: String | Null): Unit =
        discard(
            if value == null then java.lang.System.clearProperty(key)
            else java.lang.System.setProperty(key, value)
        )
    end setProp

    "retained-pool concurrency (#33)" - {

        "16 threads claim + release 200 times each from the same shape with no corruption or deadlock" in {
            val threads   = 16
            val perThread = 200
            val errors    = new java.util.concurrent.ConcurrentLinkedQueue[Throwable]()
            val start     = new CountDownLatch(1)
            val done      = new CountDownLatch(threads)
            val claimed   = new AtomicInteger(0)
            val before    = CallbackRegistry.poolStats("I_I").used
            // Single shared token, all threads claim from the global pool. Validates that the bitset CAS is
            // correct under concurrent contention (16 threads racing on the same shape).
            val tok = new Object()

            val ts: Array[Thread] = new Array[Thread](threads)
            var ti                = 0
            while ti < threads do
                val idx = ti
                val r: Runnable = () =>
                    try
                        start.await()
                        var j = 0
                        while j < perThread do
                            val (slot, _) = CallbackRegistry.claimRetainedSlot_I_I(
                                tok,
                                "kyo.ffi.internal.CallbackRegistryConcurrencyTest",
                                s"thread$idx",
                                (n: Int) => n + 1
                            )
                            discard(claimed.incrementAndGet())
                            val slotCb = CallbackRegistry.retainedSlots_I_I(slot)
                            if slotCb == null then
                                discard(errors.add(new AssertionError(s"slot $slot was null immediately after claim")))
                            CallbackRegistry.releaseRetainedSlot_I_I(slot)
                            j += 1
                        end while
                    catch
                        case t: Throwable =>
                            discard(errors.add(t))
                    finally done.countDown()
                val t = new Thread(r, s"claim-thread-$idx")
                t.setDaemon(true)
                t.start()
                ts(ti) = t
                ti += 1
            end while
            start.countDown()
            val finished = done.await(30, TimeUnit.SECONDS)
            assert(finished == true)
            assert(errors.isEmpty == true)
            assert(claimed.get() == (threads * perThread))
            assert(CallbackRegistry.poolStats("I_I").used == before)
            CallbackRegistry.unregisterGuard(tok)
            var k = 0
            while k < ts.length do
                ts(k).join(1000)
                k += 1
            end while
        }

        "concurrent claims on distinct shapes do not interfere" in {
            val threads   = 8
            val perThread = 100
            val errors    = new java.util.concurrent.ConcurrentLinkedQueue[Throwable]()
            val start     = new CountDownLatch(1)
            val done      = new CountDownLatch(threads)
            val beforeVU  = CallbackRegistry.poolStats("V_U").used
            val beforeII  = CallbackRegistry.poolStats("II_I").used
            val tok       = new Object()

            var ti = 0
            while ti < threads do
                val idx = ti
                val r: Runnable = () =>
                    try
                        start.await()
                        var j = 0
                        while j < perThread do
                            if (idx % 2) == 0 then
                                val (s, _) = CallbackRegistry.claimRetainedSlot_V_U(
                                    tok,
                                    "kyo.ffi.internal.Spec",
                                    "m",
                                    () => ()
                                )
                                CallbackRegistry.releaseRetainedSlot_V_U(s)
                            else
                                val (s, _) = CallbackRegistry.claimRetainedSlot_II_I(
                                    tok,
                                    "kyo.ffi.internal.Spec",
                                    "m",
                                    (a: Int, b: Int) => a + b
                                )
                                CallbackRegistry.releaseRetainedSlot_II_I(s)
                            end if
                            j += 1
                        end while
                    catch
                        case t: Throwable =>
                            discard(errors.add(t))
                    finally done.countDown()
                val t = new Thread(r, s"shape-thread-$idx")
                t.setDaemon(true)
                t.start()
                ti += 1
            end while
            start.countDown()
            assert(done.await(30, TimeUnit.SECONDS) == true)
            assert(errors.isEmpty == true)
            assert(CallbackRegistry.poolStats("V_U").used == beforeVU)
            assert(CallbackRegistry.poolStats("II_I").used == beforeII)
            CallbackRegistry.unregisterGuard(tok)
        }
    }

    "retained-pool backpressure (#14)" - {

        "default (no sysprop) throws IllegalStateException on pool exhaustion" in {
            val prev = java.lang.System.getProperty("kyo.ffi.native.retainedCallbackPoolBackpressure")
            setProp("kyo.ffi.native.retainedCallbackPoolBackpressure", null)
            // Fill the entire global pool. The overflow claim should throw.
            val tok  = new Object()
            val held = new scala.collection.mutable.ArrayBuffer[Int]()
            try
                var i = 0
                while i < CallbackRegistry.PoolSize do
                    val (idx, _) = CallbackRegistry.claimRetainedSlot_V_U(
                        tok,
                        "kyo.ffi.internal.Spec",
                        "fill",
                        () => ()
                    )
                    held += idx
                    i += 1
                end while
                val thrown = intercept[IllegalStateException] {
                    discard(CallbackRegistry.claimRetainedSlot_V_U(
                        tok,
                        "kyo.ffi.internal.Spec",
                        "overflow",
                        () => ()
                    ))
                }
                assert(thrown.getMessage.contains("exhausted"))
            finally
                var k = 0
                while k < held.length do
                    CallbackRegistry.releaseRetainedSlot_V_U(held(k))
                    k += 1
                end while
                CallbackRegistry.unregisterGuard(tok)
                setProp("kyo.ffi.native.retainedCallbackPoolBackpressure", prev)
            end try
        }

        "with Backpressure enabled, claim blocks until another thread releases a slot" in {
            val prev = java.lang.System.getProperty("kyo.ffi.native.retainedCallbackPoolBackpressure")
            setProp("kyo.ffi.native.retainedCallbackPoolBackpressure", "true")
            val tok  = new Object()
            val held = new scala.collection.mutable.ArrayBuffer[Int]()
            try
                var i = 0
                while i < CallbackRegistry.PoolSize do
                    val (idx, _) = CallbackRegistry.claimRetainedSlot_P_U(
                        tok,
                        "kyo.ffi.internal.Spec",
                        "fill",
                        (_: scala.scalanative.unsafe.Ptr[Byte]) => ()
                    )
                    held += idx
                    i += 1
                end while

                @volatile var blockerResult: AnyRef | Null = null
                val blockerStart                           = new CountDownLatch(1)
                val r: Runnable = () =>
                    try
                        blockerStart.countDown()
                        val (idx, _) = CallbackRegistry.claimRetainedSlot_P_U(
                            tok,
                            "kyo.ffi.internal.Spec",
                            "blocker",
                            (_: scala.scalanative.unsafe.Ptr[Byte]) => ()
                        )
                        blockerResult = Integer.valueOf(idx)
                    catch
                        case t: Throwable =>
                            blockerResult = t
                val blocker = new Thread(r, "backpressure-blocker")
                blocker.setDaemon(true)
                blocker.start()
                blockerStart.await()
                val deadline = System.nanoTime() + 2_000_000_000L
                while !isParked(blocker) && System.nanoTime() < deadline do
                    Thread.onSpinWait()
                end while
                assert(blockerResult eq null)
                val releasedIdx = held.remove(held.length - 1)
                CallbackRegistry.releaseRetainedSlot_P_U(releasedIdx)
                blocker.join(5000)
                assert(blockerResult ne null)
                blockerResult match
                    case null                 => fail("blocker did not finish")
                    case n: java.lang.Integer => held += n.intValue()
                    case t: Throwable         => fail(s"blocker failed: $t")
                    case other                => fail(s"unexpected result: $other")
                end match
            finally
                var k = 0
                while k < held.length do
                    CallbackRegistry.releaseRetainedSlot_P_U(held(k))
                    k += 1
                end while
                CallbackRegistry.unregisterGuard(tok)
                setProp("kyo.ffi.native.retainedCallbackPoolBackpressure", prev)
            end try
        }

        "with Backpressure + very short timeout, claim eventually throws exhausted" in {
            val prevEn = java.lang.System.getProperty("kyo.ffi.native.retainedCallbackPoolBackpressure")
            val prevTo = java.lang.System.getProperty("kyo.ffi.native.retainedCallbackPoolBackpressureTimeoutMs")
            setProp("kyo.ffi.native.retainedCallbackPoolBackpressure", "true")
            setProp("kyo.ffi.native.retainedCallbackPoolBackpressureTimeoutMs", "100")
            val tok  = new Object()
            val held = new scala.collection.mutable.ArrayBuffer[Int]()
            try
                var i = 0
                while i < CallbackRegistry.PoolSize do
                    val (idx, _) = CallbackRegistry.claimRetainedSlot_JJ_I(
                        tok,
                        "kyo.ffi.internal.Spec",
                        "fill",
                        (_: Long, _: Long) => 0
                    )
                    held += idx
                    i += 1
                end while
                val thrown = intercept[IllegalStateException] {
                    discard(CallbackRegistry.claimRetainedSlot_JJ_I(
                        tok,
                        "kyo.ffi.internal.Spec",
                        "timeout-claim",
                        (_: Long, _: Long) => 0
                    ))
                }
                assert(thrown.getMessage.contains("exhausted"))
            finally
                var k = 0
                while k < held.length do
                    CallbackRegistry.releaseRetainedSlot_JJ_I(held(k))
                    k += 1
                end while
                CallbackRegistry.unregisterGuard(tok)
                setProp("kyo.ffi.native.retainedCallbackPoolBackpressure", prevEn)
                setProp("kyo.ffi.native.retainedCallbackPoolBackpressureTimeoutMs", prevTo)
            end try
        }
    }
end CallbackRegistryConcurrencyTest
