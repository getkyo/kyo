package kyo.ffi.internal

import java.lang.ref.Cleaner
import java.util.Collections
import java.util.WeakHashMap
import kyo.Frame
import kyo.discard

/** JVM counterpart to [[NativeLeakDetector]]: emits a stderr warning when an [[kyo.ffi.Ffi.Guard]] is garbage-collected without being
  * closed.
  *
  * Wraps a single shared [[java.lang.ref.Cleaner]]. Registration must happen AFTER the owning [[JvmGuard]]'s constructor finishes
  * publishing its `core` state, the returned [[java.lang.ref.Cleaner.Cleanable]] is stashed on the guard and invoked from `close()` so a
  * legitimately closed guard cannot later fire a false-positive leak warning.
  *
  * The Cleaner's runnable ([[JvmGuard.LeakWarning]]) holds only the open-site [[Frame]] and the guard's state
  * [[java.util.concurrent.atomic.AtomicInteger]]. It must NOT capture the guard itself, that would keep the guard strongly reachable
  * through the Cleaner's phantom reference and prevent the very collection the detector is trying to observe.
  *
  * Additionally, the detector keeps a weakly-keyed `JvmGuard -> LeakWarning` map so that the `testForceLeak` test hook can
  * deterministically invoke the leak-processing path without waiting on the real GC. `testForceLeak` is visible only to `kyo.ffi.internal`
  * tests.
  */
private[ffi] object JvmLeakDetector:

    /** Shared Cleaner for all kyo-ffi JVM guards. One daemon thread backs the entire process; java.lang.ref.Cleaner takes care of lifecycle
      * (spawn lazily, reap on Cleaner GC).
      */
    private val cleaner: Cleaner = Cleaner.create().nn

    /** Weakly-keyed snapshot used only by [[testForceLeak]]. When a guard is collected, the entry disappears automatically. Synchronized
      * wrapper because WeakHashMap is not thread-safe.
      */
    private val warnings: java.util.Map[JvmGuard, JvmGuard.LeakWarning] =
        // Carrier-thread substrate: WeakHashMap is not thread-safe and register/testForceLeak arrive from arbitrary user threads;
        // one monitor enter per op, off the fiber path. See kyo-ffi/CONTRIBUTING.md.
        Collections.synchronizedMap(new WeakHashMap[JvmGuard, JvmGuard.LeakWarning]()).nn

    /** Register `guard` for leak detection with the shared Cleaner.
      *
      * The returned [[Cleaner.Cleanable]] must be invoked by the owning [[JvmGuard]] from `close()` so a legitimately closed guard cannot
      * later trip the leak diagnostic at GC time.
      */
    private[ffi] def register(guard: JvmGuard, frame: Frame): Cleaner.Cleanable =
        val warning = new JvmGuard.LeakWarning(frame, guard.core.state)
        discard(warnings.put(guard, warning))
        cleaner.register(guard, warning).nn
    end register

    /** Test-only hook: invoke the leak-warning logic for `guard` as if its Cleaner had fired. Mirrors [[NativeLeakDetector.testForceLeak]]
      * so cross-platform tests can exercise the leak path deterministically.
      *
      * A no-op if `guard` has no registered warning (already closed-and-cancelled, already swept, or never registered).
      */
    private[ffi] def testForceLeak(guard: JvmGuard): Unit =
        val warning = warnings.get(guard)
        if warning ne null then warning.run()
    end testForceLeak
end JvmLeakDetector
