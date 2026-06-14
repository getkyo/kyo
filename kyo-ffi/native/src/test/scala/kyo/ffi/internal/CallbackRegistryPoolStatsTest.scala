package kyo.ffi.internal

import kyo.discard
import kyo.ffi.Test

/** Native-only tests for the retained-callback pool observability surface, `poolStats(shape)` + per-shape usage counters wired into the
  * `claim*` / `release*` pairs in [[CallbackRegistry]].
  *
  * Coverage is intentionally limited to monotonic claim/release accounting and shape-name validation; the high-watermark warning is
  * exercised only by-product (utilization reads before/after cross-threshold are covered by the full-fill test on a small override pool
  * would require sys-prop injection pre-class-load, not reliable under a shared sbt test JVM). The claim/release invariants here are
  * sufficient to protect the observability contract.
  */
class CallbackRegistryPoolStatsTest extends Test:

    // Touches process-global state (global stderr/system property, or the shared CallbackRegistry pool/hooks) and so
    // must run alone: under the default parallel leaf execution a sibling leaf observes or mutates the same global.
    override def config = super.config.sequential

    "poolStats" - {

        "reports total equal to PoolSize for every supported shape" in {
            val shapes = Seq("V_U", "I_U", "I_I", "II_I", "JJ_I", "P_U", "PI_U")
            shapes.foreach { s =>
                val st = CallbackRegistry.poolStats(s)
                assert(st.total == CallbackRegistry.PoolSize)
                assert(st.used >= 0)
                assert(st.utilizationPercent >= 0.0)
            }
        }

        "increments used-count on claim and decrements on release (I_U shape)" in {
            val tok       = new Object()
            val before    = CallbackRegistry.poolStats("I_U").used
            val (idxA, _) = CallbackRegistry.claimRetainedSlot_I_U(tok, "kyo.ffi.internal.Spec", "test", (_: Int) => ())
            val (idxB, _) = CallbackRegistry.claimRetainedSlot_I_U(tok, "kyo.ffi.internal.Spec", "test", (_: Int) => ())
            val (idxC, _) = CallbackRegistry.claimRetainedSlot_I_U(tok, "kyo.ffi.internal.Spec", "test", (_: Int) => ())
            try
                val duringUsed = CallbackRegistry.poolStats("I_U").used
                assert((duringUsed - before) == 3)
                val pct = CallbackRegistry.poolStats("I_U").utilizationPercent
                assert(pct == (duringUsed * 100.0 / CallbackRegistry.PoolSize))
            finally
                CallbackRegistry.releaseRetainedSlot_I_U(idxA)
                CallbackRegistry.releaseRetainedSlot_I_U(idxB)
                CallbackRegistry.releaseRetainedSlot_I_U(idxC)
                CallbackRegistry.unregisterGuard(tok)
            end try
            assert(CallbackRegistry.poolStats("I_U").used == before)
        }

        "claim/release cycles are monotonic across multiple rounds (V_U shape)" in {
            val tok    = new Object()
            val before = CallbackRegistry.poolStats("V_U").used
            val idxs   = (1 to 5).map(_ => CallbackRegistry.claimRetainedSlot_V_U(tok, "kyo.ffi.internal.Spec", "test", () => ())._1)
            try assert((CallbackRegistry.poolStats("V_U").used - before) == 5)
            finally idxs.foreach(CallbackRegistry.releaseRetainedSlot_V_U)
            assert(CallbackRegistry.poolStats("V_U").used == before)

            // Second cycle, confirm counters actually reset (not drifting upward each round).
            val idxs2 = (1 to 2).map(_ => CallbackRegistry.claimRetainedSlot_V_U(tok, "kyo.ffi.internal.Spec", "test", () => ())._1)
            try assert((CallbackRegistry.poolStats("V_U").used - before) == 2)
            finally
                idxs2.foreach(CallbackRegistry.releaseRetainedSlot_V_U)
                CallbackRegistry.unregisterGuard(tok)
            end try
            assert(CallbackRegistry.poolStats("V_U").used == before)
        }

        "claims on one shape do not affect the used-count of other shapes" in {
            val tok     = new Object()
            val ibefore = CallbackRegistry.poolStats("I_I").used
            val jbefore = CallbackRegistry.poolStats("JJ_I").used
            val (i1, _) = CallbackRegistry.claimRetainedSlot_I_I(tok, "kyo.ffi.internal.Spec", "test", (_: Int) => 0)
            val (i2, _) = CallbackRegistry.claimRetainedSlot_I_I(tok, "kyo.ffi.internal.Spec", "test", (_: Int) => 0)
            try
                assert((CallbackRegistry.poolStats("I_I").used - ibefore) == 2)
                assert((CallbackRegistry.poolStats("JJ_I").used - jbefore) == 0)
            finally
                CallbackRegistry.releaseRetainedSlot_I_I(i1)
                CallbackRegistry.releaseRetainedSlot_I_I(i2)
                CallbackRegistry.unregisterGuard(tok)
            end try
        }

        "throws IllegalArgumentException naming the bad shape" in {
            val thrown = intercept[IllegalArgumentException] {
                discard(CallbackRegistry.poolStats("NOT_A_SHAPE"))
            }
            assert(thrown.getMessage.contains("NOT_A_SHAPE"))
        }

        "default PoolSize is 1024" in {
            assert(CallbackRegistry.PoolSize == 1024)
        }

        "default WarnThresholdPercent is 75" in {
            assert(CallbackRegistry.WarnThresholdPercent == 75)
        }
    }

    "poolUsage" - {

        "returns (used, total) consistent with poolStats" in {
            val tok    = new Object()
            val before = CallbackRegistry.poolUsage("I_U")
            assert(before._2 == CallbackRegistry.PoolSize)
            val (idxA, _) = CallbackRegistry.claimRetainedSlot_I_U(tok, "kyo.ffi.internal.Spec", "test", (_: Int) => ())
            try
                val (used, total) = CallbackRegistry.poolUsage("I_U")
                assert((used - before._1) == 1)
                assert(total == CallbackRegistry.PoolSize)
            finally
                CallbackRegistry.releaseRetainedSlot_I_U(idxA)
                CallbackRegistry.unregisterGuard(tok)
            end try
        }

        "throws IllegalArgumentException for unknown shape" in {
            val thrown = intercept[IllegalArgumentException] {
                discard(CallbackRegistry.poolUsage("UNKNOWN"))
            }
            assert(thrown.getMessage.contains("UNKNOWN"))
        }
    }
end CallbackRegistryPoolStatsTest
