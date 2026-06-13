package kyo.ffi.internal

import java.util.Collections
import java.util.WeakHashMap
import kyo.discard
import kyo.ffi.Ffi

/** Process-wide registry of open [[Ffi.Guard]]s on JVM.
  *
  * Backed by a synchronized [[java.util.WeakHashMap]]-based set so that open guards are only weakly reachable through the registry. This is
  * the precondition for [[JvmLeakDetector]]'s [[java.lang.ref.Cleaner]]-based leak warnings: if the registry held strong refs, a guard that
  * escaped user code would stay reachable forever and the Cleaner would never fire.
  *
  * The synchronized wrapper is required because `WeakHashMap` is not thread-safe and every public entry point on this registry can be
  * called from arbitrary user threads via `Ffi.Guard.open()` / `close()`. The cost (one monitor enter per operation) is negligible next to
  * the Panama `arena.allocate()` it bookends.
  */
private[ffi] object GuardRegistry:

    private val live: java.util.Set[Ffi.Guard] =
        // Carrier-thread substrate: WeakHashMap is not thread-safe and open/close arrive from arbitrary user threads;
        // one monitor enter per op, off the fiber path. See kyo-ffi/CONTRIBUTING.md.
        Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap[Ffi.Guard, java.lang.Boolean]()).nn
        ).nn

    /** Add `g` to the live set. No-op if already present. */
    def register(g: Ffi.Guard): Unit =
        discard(live.add(g))

    /** Remove `g` from the live set. No-op if not present. */
    def unregister(g: Ffi.Guard): Unit =
        discard(live.remove(g))

    /** Current number of live (unclosed, uncollected) guards. Intended for tests and diagnostics only.
      *
      * Because the backing map is weakly keyed, entries for guards that have been collected without an explicit `close()` may disappear
      * between calls even with no corresponding `unregister` invocation.
      */
    def size: Int = live.size
end GuardRegistry
