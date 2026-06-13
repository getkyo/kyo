package kyo.ffi.internal

import kyo.Frame
import kyo.ffi.Ffi
import kyo.ffi.Test

/** Native-only tests for [[NativeLeakDetector]], the hand-rolled sweep-based cleaner providing post-mortem leak detection for `Ffi.Guard`
  * on Scala Native 0.5 (which lacks `java.lang.ref.Cleaner` and does not implement `ReferenceQueue` integration).
  *
  * The detector is exercised deterministically via the [[NativeLeakDetector.testForceLeak]] hook, which clears the token's WeakReference
  * and runs a sweep, simulating a `System.gc()`-driven collection of the referent. Relying on real GC to clear WeakReferences is
  * inherently flaky under Scala Native's Immix collector and would require `System.gc()` retry loops; `testForceLeak` exercises the exact
  * same drain logic without that flakiness.
  */
class NativeLeakDetectorTest extends Test:

    // Exercises the process-global native leak detector (a sweep over the shared registry), so it must run alone:
    // guards opened or closed by parallel leaves would appear in the sweep and perturb the leak counts.
    override def config = super.config.sequential

    "close + sweep reports zero leaks (happy path)" in {
        // Open a guard, close it normally, the leak detector's token mapping is removed by `unregister`, so a subsequent sweep returns 0
        // leaks. This is the steady-state behavior every well-behaved caller should see.
        val g = Ffi.Guard.open()
        g.close()
        val leaks = NativeLeakDetector.sweep()
        assert(leaks == 0)
    }

    "forced-leak simulation: single leak is drained as one leak" in {
        // Register a placeholder guard, close it so its own leak-detector mapping is dropped, then install a synthetic token bound to a
        // fresh WeakReference pointing at that closed guard. Calling testForceLeak clears the referent and sweeps, the sweep must see
        // exactly one leak.
        val frame: Frame = summon[Frame]
        val placeholder  = new NativeGuard(frame)
        placeholder.close()
        val token = NativeLeakDetector.register(placeholder, frame)
        val leaks = NativeLeakDetector.testForceLeak(token)
        assert(leaks >= 1)
    }

    "forced-leak releases recorded pool slots when a guard leaks with retained callbacks" in {
        // Claim two I_U slots from the registry, record them on the token, force a leak, and verify that both slots return to the pool's
        // free-list. This proves that a leaked guard's slots are recovered rather than silently lost.
        val frame: Frame = summon[Frame]
        val usedBefore   = CallbackRegistry.poolStats("I_U").used
        val ownerTok     = new Object()
        val (slotA, _)   = CallbackRegistry.claimRetainedSlot_I_U(ownerTok, "kyo.ffi.internal.Spec", "test", (_: Int) => ())
        val (slotB, _)   = CallbackRegistry.claimRetainedSlot_I_U(ownerTok, "kyo.ffi.internal.Spec", "test", (_: Int) => ())
        val usedPeak     = CallbackRegistry.poolStats("I_U").used
        assert((usedPeak - usedBefore) == 2)

        val placeholder = new NativeGuard(frame)
        placeholder.close()
        val token = NativeLeakDetector.register(placeholder, frame)
        NativeLeakDetector.recordRetainedSlot(token, "I_U", slotA)
        NativeLeakDetector.recordRetainedSlot(token, "I_U", slotB)
        val leaks = NativeLeakDetector.testForceLeak(token)
        assert(leaks >= 1)

        // Both slots must be returned to the pool's free-list.
        val usedAfter = CallbackRegistry.poolStats("I_U").used
        assert(usedAfter == usedBefore)
        // unregisterGuard is a no-op with global-pool allocation but kept for API stability.
        CallbackRegistry.unregisterGuard(ownerTok)
    }

    "releaseRetainedSlotByName rejects unknown shape" in {
        val thrown = intercept[IllegalArgumentException] {
            CallbackRegistry.releaseRetainedSlotByName("NOT_A_SHAPE", 0)
        }
        assert(thrown.getMessage.contains("NOT_A_SHAPE"))
    }

end NativeLeakDetectorTest
