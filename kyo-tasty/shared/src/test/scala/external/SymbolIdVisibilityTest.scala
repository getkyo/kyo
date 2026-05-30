package external

/** Negative-compilation test for INV-001: SymbolId.apply is not accessible outside the kyo package.
  *
  * This file lives in package external (outside kyo) so that assertDoesNotCompile verifies the private[kyo] restriction on SymbolId.apply
  * and SymbolId.value. typeCheckErrors called from within package kyo cannot detect this restriction because private[kyo] is accessible
  * from that package.
  */
class SymbolIdVisibilityTest extends kyo.Test:

    // Leaf 4: no-public-construction compile pin.
    // Given: caller code outside package kyo.
    // When: attempts kyo.internal.tasty.symbol.SymbolId.apply(0).
    // Then: compilation fails because apply is private[kyo].
    // Pins: INV-001 (private[kyo] smart constructor).
    "SymbolId.apply is inaccessible from outside the kyo package" in {
        assertDoesNotCompile("kyo.internal.tasty.symbol.SymbolId(0)")
        succeed
    }

end SymbolIdVisibilityTest
