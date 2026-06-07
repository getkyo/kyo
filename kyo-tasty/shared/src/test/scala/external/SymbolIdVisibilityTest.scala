package external

/** Negative-compilation test: SymbolId.apply is not accessible outside the kyo package.
  *
  * This file lives in package external (outside kyo) so that assertDoesNotCompile verifies the private[kyo] restriction on SymbolId.apply
  * and SymbolId.value. typeCheckErrors called from within package kyo cannot detect this restriction because private[kyo] is accessible
  * from that package.
  */
class SymbolIdVisibilityTest extends kyo.test.Test[Any]:

    // no-public-construction compile pin.
    // Given: caller code outside package kyo.
    // When: attempts kyo.Tasty.SymbolId.apply(0).
    // Then: compilation fails because apply is private[kyo].
    "SymbolId.apply is inaccessible from outside the kyo package" in {
        typeCheckFailure("kyo.Tasty.SymbolId(0)")("does not take parameters")
    }

end SymbolIdVisibilityTest
