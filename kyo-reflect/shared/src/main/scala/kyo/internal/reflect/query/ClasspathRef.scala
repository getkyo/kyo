package kyo.internal.reflect.query

import kyo.AllowUnsafe
import kyo.Reflect
import kyo.internal.reflect.symbol.SingleAssign

/** A forward-reference slot for a `Reflect.Classpath`.
  *
  * Each `Reflect.Symbol` holds one `ClasspathRef` in its `home` field. The slot is unset during Phase 3 pass 1 (symbol allocation). Phase 7
  * classpath orchestration calls `assign` after the Classpath object is fully constructed.
  *
  * Lifecycle constraint: no Phase 3 code may call `get()` or `assign()`. Phase 3 code may only store or forward the `ClasspathRef`
  * reference. The owner-chain walks in `computeFullName` and `computeBinaryName` do NOT call through `home`.
  */
final class ClasspathRef:
    private val slot = new SingleAssign[Reflect.Classpath]

    /** Assign the Classpath. Called by Phase 7 orchestration. Throws if already assigned. */
    def assign(cp: Reflect.Classpath): Unit =
        // Unsafe: SingleAssign.set() is an unsafe-tier helper called inside Phase 7 orchestration.
        import AllowUnsafe.embrace.danger
        slot.set(cp)
    end assign

    /** Retrieve the assigned Classpath. Throws if not yet assigned. */
    def get(): Reflect.Classpath =
        // Unsafe: SingleAssign.get() is an unsafe-tier helper called inside Phase 7 orchestration.
        import AllowUnsafe.embrace.danger
        slot.get()
    end get

end ClasspathRef
