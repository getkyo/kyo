package kyo.internal.tasty.query

import kyo.AllowUnsafe
import kyo.Tasty
import kyo.internal.tasty.symbol.SingleAssign

/** A forward-reference slot for a `Tasty.Classpath`.
  *
  * Each `Tasty.Symbol` holds one `ClasspathRef` in its `home` field. The slot is unset during Phase 3 pass 1 (symbol allocation). Phase 7
  * classpath orchestration calls `assign` after the Classpath object is fully constructed.
  *
  * Lifecycle constraint: no Phase 3 code may call `get()` or `assign()`. Phase 3 code may only store or forward the `ClasspathRef`
  * reference. The owner-chain walks in `computeFullName` and `computeBinaryName` do NOT call through `home`.
  */
final class ClasspathRef:
    private val slot = new SingleAssign[Tasty.Classpath]

    /** Assign the Classpath. Called by Phase 7 orchestration. Throws if already assigned. */
    def assign(cp: Tasty.Classpath): Unit =
        // Unsafe: SingleAssign.set() is an unsafe-tier helper called inside Phase 7 orchestration.
        import AllowUnsafe.embrace.danger
        slot.set(cp)
    end assign

    /** Retrieve the assigned Classpath. Throws if not yet assigned. */
    def get(): Tasty.Classpath =
        // Unsafe: SingleAssign.get() is an unsafe-tier helper called inside Phase 7 orchestration.
        import AllowUnsafe.embrace.danger
        slot.get()
    end get

    /** Returns true if the Classpath has been assigned, false if the slot is still unset. */
    def isAssigned: Boolean =
        // Unsafe: SingleAssign.isSet reads an AtomicReference.
        import AllowUnsafe.embrace.danger
        slot.isSet
    end isAssigned

end ClasspathRef
