package kyo

import kyo.internal.tasty.query.UnresolvedRef
import kyo.internal.tasty.symbol.SingleAssign

/** Tests for Phase 21c: UnresolvedRef data class construction and field invariants (T2).
  *
  * UnresolvedRef is a final case class with no factory method. Tests verify that the fqn field is preserved exactly and that the
  * replaceSlot starts unassigned.
  */
class UnresolvedRefTest extends Test:

    import AllowUnsafe.embrace.danger

    // Test 1 (T2): UnresolvedRef carries the supplied fqn and an initially-unset replaceSlot.
    // Given: fqn = "missing.X" and a fresh SingleAssign[Tasty.Type].
    // When: new UnresolvedRef(fqn, slot) is constructed.
    // Then: ref.fqn == "missing.X" and ref.replaceSlot.isSet == false.
    // Pins: T2.
    "UnresolvedRef preserves fqn and starts with an unset replaceSlot" in run {
        Sync.defer {
            val fqn  = "missing.X"
            val slot = new SingleAssign[Tasty.Type]
            val ref  = new UnresolvedRef(fqn, slot)
            assert(ref.fqn == fqn, s"Expected fqn == 'missing.X', got '${ref.fqn}'")
            assert(!ref.replaceSlot.isSet, "Expected replaceSlot.isSet to be false before assignment")
        }
    }

end UnresolvedRefTest
