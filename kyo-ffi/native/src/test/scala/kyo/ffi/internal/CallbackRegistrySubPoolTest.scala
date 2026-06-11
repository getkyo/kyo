package kyo.ffi.internal

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kyo.discard
import kyo.ffi.Test

/** Native-only tests for the global retained-callback pool (no per-guard sub-pool partitioning).
  *
  * Any guard can claim any free slot from the global pool (1024 slots per shape by default). When a guard closes, its individual slots are
  * released back to the pool. These tests verify that the global pool operates correctly and that the old per-guard sub-pool cap no longer
  * applies.
  */
class CallbackRegistrySubPoolTest extends Test:

    // Touches process-global state (global stderr/system property, or the shared CallbackRegistry pool/hooks) and so
    // must run alone: under the default parallel leaf execution a sibling leaf observes or mutates the same global.
    override def config = super.config.sequential

    "global pool (no per-guard sub-pool cap)" - {

        "a single guard can claim more than 256 slots (old sub-pool limit)" in {
            // Under the old sub-pool design, a guard was capped at 256 slots. With global-pool allocation, a single guard
            // can use any free slot up to the full pool size.
            val tok    = new Object()
            val held   = new scala.collection.mutable.ArrayBuffer[Int]()
            val target = 300 // exceeds the old 256 sub-pool limit
            try
                var i = 0
                while i < target do
                    val (idx, _) = CallbackRegistry.claimRetainedSlot_I_U(
                        tok,
                        "kyo.ffi.internal.Spec",
                        "largeAlloc",
                        (_: Int) => ()
                    )
                    held += idx
                    i += 1
                end while
                assert(held.length == target)
                // All indices should be unique.
                assert(held.toSet.size == target)
            finally
                held.foreach(CallbackRegistry.releaseRetainedSlot_I_U)
                CallbackRegistry.unregisterGuard(tok)
            end try
        }

        "slots are released on guard close and can be reclaimed" in {
            val tokA       = new Object()
            val tokB       = new Object()
            val heldA      = new scala.collection.mutable.ArrayBuffer[Int]()
            val claimCount = 50
            try
                // Guard A claims slots.
                var i = 0
                while i < claimCount do
                    val (idx, _) = CallbackRegistry.claimRetainedSlot_I_I(
                        tokA,
                        "kyo.ffi.internal.Spec",
                        "A",
                        (_: Int) => 0
                    )
                    heldA += idx
                    i += 1
                end while
                val usedBefore = CallbackRegistry.poolStats("I_I").used
                // Release A's slots.
                heldA.foreach(CallbackRegistry.releaseRetainedSlot_I_I)
                CallbackRegistry.unregisterGuard(tokA)
                val usedAfter = CallbackRegistry.poolStats("I_I").used
                assert((usedBefore - usedAfter) == claimCount)
                // Guard B can now reclaim slots.
                val heldB = new scala.collection.mutable.ArrayBuffer[Int]()
                var j     = 0
                while j < claimCount do
                    val (idx, _) = CallbackRegistry.claimRetainedSlot_I_I(
                        tokB,
                        "kyo.ffi.internal.Spec",
                        "B",
                        (_: Int) => 0
                    )
                    heldB += idx
                    j += 1
                end while
                assert(heldB.length == claimCount)
                heldB.foreach(CallbackRegistry.releaseRetainedSlot_I_I)
                CallbackRegistry.unregisterGuard(tokB)
            finally
                // Defensive cleanup in case of assertion failure.
                heldA.foreach(i =>
                    try CallbackRegistry.releaseRetainedSlot_I_I(i)
                    catch case _: Throwable => ()
                )
                CallbackRegistry.unregisterGuard(tokA)
                CallbackRegistry.unregisterGuard(tokB)
            end try
        }

        "global pool exhaustion throws IllegalStateException with diagnostic message" in {
            // Fill the entire pool for a shape, then verify the next claim throws.
            val tok  = new Object()
            val held = new scala.collection.mutable.ArrayBuffer[Int]()
            try
                var i = 0
                while i < CallbackRegistry.PoolSize do
                    val (idx, _) = CallbackRegistry.claimRetainedSlot_V_U(
                        tok,
                        "kyo.ffi.internal.Spec",
                        "fillAll",
                        () => ()
                    )
                    held += idx
                    i += 1
                end while
                assert(held.length == CallbackRegistry.PoolSize)
                val thrown = intercept[IllegalStateException] {
                    discard(CallbackRegistry.claimRetainedSlot_V_U(
                        tok,
                        "kyo.ffi.internal.Spec",
                        "overflow",
                        () => ()
                    ))
                }
                assert(thrown.getMessage.contains("V_U"))
                assert(thrown.getMessage.contains("exhausted"))
            finally
                held.foreach(CallbackRegistry.releaseRetainedSlot_V_U)
                CallbackRegistry.unregisterGuard(tok)
            end try
        }

        "multiple guards share the global pool without artificial partitioning" in {
            // 10 guards each claim 50 slots from the same shape, all should succeed (total 500 < 1024).
            val guardCount = 10
            val perGuard   = 50
            val tokens     = Array.fill(guardCount)(new Object())
            val held       = scala.collection.mutable.Map[AnyRef, scala.collection.mutable.ArrayBuffer[Int]]()
            try
                tokens.foreach { t =>
                    val buf = scala.collection.mutable.ArrayBuffer[Int]()
                    var i   = 0
                    while i < perGuard do
                        val (idx, _) = CallbackRegistry.claimRetainedSlot_II_I(
                            t,
                            "kyo.ffi.internal.Spec",
                            "multiGuard",
                            (a: Int, b: Int) => a + b
                        )
                        buf += idx
                        i += 1
                    end while
                    held(t) = buf
                }
                // All indices across all guards should be unique.
                val allIndices = held.values.flatten.toSet
                assert(allIndices.size == (guardCount * perGuard))
            finally
                held.foreach { case (_, buf) =>
                    buf.foreach(CallbackRegistry.releaseRetainedSlot_II_I)
                }
                tokens.foreach(CallbackRegistry.unregisterGuard)
            end try
        }

        "concurrent claims from different guards are safe" in {
            val threads   = 8
            val perThread = 100
            val errors    = new ConcurrentLinkedQueue[Throwable]()
            val observed  = new ConcurrentLinkedQueue[Int]()
            val start     = new CountDownLatch(1)
            val done      = new CountDownLatch(threads)
            var t         = 0
            while t < threads do
                val tok = new Object()
                val r: Runnable = () =>
                    try
                        start.await()
                        var j = 0
                        while j < perThread do
                            val (idx, _) = CallbackRegistry.claimRetainedSlot_II_I(
                                tok,
                                "kyo.ffi.internal.Spec",
                                "stress",
                                (a: Int, b: Int) => a + b
                            )
                            observed.add(idx)
                            CallbackRegistry.releaseRetainedSlot_II_I(idx)
                            j += 1
                        end while
                    catch
                        case ex: Throwable =>
                            discard(errors.add(ex))
                    finally
                        CallbackRegistry.unregisterGuard(tok)
                        done.countDown()
                val th = new Thread(r, s"pool-stress-$t")
                th.setDaemon(true)
                th.start()
                t += 1
            end while
            start.countDown()
            assert(done.await(30, TimeUnit.SECONDS) == true)
            assert(errors.isEmpty == true)
        }
    }
end CallbackRegistrySubPoolTest
