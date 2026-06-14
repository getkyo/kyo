package kyo.ffi.internal

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kyo.discard
import kyo.ffi.Ffi

/** Process-wide registry of open [[Ffi.Guard]]s on Scala Native.
  *
  * Keeps strong references to open guards so retained-callback closures stay reachable until `close()`. Leak detection is handled
  * independently by [[NativeLeakDetector]] via a [[java.lang.ref.WeakReference]] sweep (see its scaladoc for the rationale); the registry
  * itself does not need to be weakly keyed.
  */
private[ffi] object GuardRegistry:

    private val live =
        Collections.newSetFromMap(new ConcurrentHashMap[Ffi.Guard, java.lang.Boolean]()).nn

    /** Add `g` to the live set. No-op if already present. */
    def register(g: Ffi.Guard): Unit =
        discard(live.add(g))

    /** Remove `g` from the live set. No-op if not present. */
    def unregister(g: Ffi.Guard): Unit =
        discard(live.remove(g))

    /** Current number of live (unclosed) guards. Intended for tests and diagnostics only. */
    def size: Int = live.size
end GuardRegistry
