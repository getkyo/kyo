package kyo.internal.reflect.query

import kyo.AllowUnsafe
import kyo.Reflect

/** Internal test helpers for assigning Classpath home references to symbols.
  *
  * These helpers are used by test code to wire up a dummy or in-memory classpath reference so that resolving accessors (e.g. `sym.parents`,
  * `sym.declaredType`) can call `checkOpen` without failing. Production code uses the full orchestration path in `ClasspathOrchestrator`.
  *
  * Kept separate from `Classpath.scala` to avoid polluting the production API file with test-only surface.
  */
object ClasspathTestHelpers:

    /** Assign homes for all symbols in `cp` to `cp`. For internal test helpers only. */
    private[kyo] def assignHomesForTest(cp: Classpath): Unit =
        // Inside object Reflect, Classpath is transparent: the internal and public opaque types alias at runtime.
        import AllowUnsafe.embrace.danger
        val syms = cp.allSymbols
        val seen = new java.util.HashSet[ClasspathRef]()
        var i    = 0
        while i < syms.length do
            val ref = syms(i).home
            if seen.add(ref) then ref.assign(Reflect.Classpath.wrap(cp))
            i += 1
        end while
    end assignHomesForTest

    /** Assign the given extra symbols' ClasspathRef slots to `cp`. For internal test helpers only. */
    private[kyo] def assignExtraHomes(cp: Reflect.Classpath, extra: Seq[Reflect.Symbol]): Unit =
        for sym <- extra do
            if !sym.home.isAssigned then sym.home.assign(cp)

end ClasspathTestHelpers
