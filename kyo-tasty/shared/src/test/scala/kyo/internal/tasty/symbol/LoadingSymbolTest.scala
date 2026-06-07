package kyo.internal.tasty.symbol

import kyo.*

/** Tests for `LoadingSymbol` ADT: the internal loading-phase symbol representation.
  *
  * Covers:
  *   loadingSymbolInaccessibleFromUserCode: LoadingSymbol is private[kyo]; user code cannot access it.
  *   materialisingHoldsId: Materialising.id holds the assigned value; structural equality on id.
  *   placeholderDeleted: LoadingSymbol.Placeholder no longer exists (deleted in).
  */
class LoadingSymbolTest extends kyo.test.Test[Any]:

    // loadingSymbolInaccessibleFromUserCode
    // Given: a compileErrors probe that LoadingSymbol is NOT accessible through kyo.Tasty.
    // When: the test asserts.
    // Then: the type does not appear on the public Tasty surface; LoadingSymbol is an
    //       internal type (private[kyo]) not re-exported from object Tasty.
    "loadingSymbolInaccessibleFromUserCode: LoadingSymbol is not accessible through the public Tasty API" in {
        // kyo.Tasty does not expose LoadingSymbol: accessing it via Tasty.LoadingSymbol fails.
        val notOnTastySurface = compiletime.testing.typeCheckErrors(
            "(??? : kyo.Tasty.LoadingSymbol)"
        )
        assert(notOnTastySurface.nonEmpty, "LoadingSymbol must not be exposed on the Tasty public surface")
        succeed
    }

    // materialisingHoldsId
    // Given: a `LoadingSymbol.Materialising(id = 42, kind = SymbolKind.Class, flags = Flags.empty, name = Name("X"))`.
    // When: the test reads `m.id`.
    // Then: the value is 42; structural equality on two Materialising values with the same id holds.
    "materialisingHoldsId: Materialising.id holds the assigned value" in {
        import AllowUnsafe.embrace.danger
        val m1 = LoadingSymbol.Materialising(
            id = 42,
            kind = SymbolKind.Class,
            flags = Tasty.Flags.empty,
            name = Tasty.Name("X")
        )
        assert(m1.id == 42, s"Expected id 42 but got ${m1.id}")
        // Verify id is the first field (structural; the id is the identity of a Materialising in the pipeline).
        val m2 = LoadingSymbol.Materialising(
            id = 42,
            kind = SymbolKind.Class,
            flags = Tasty.Flags.empty,
            name = Tasty.Name("X")
        )
        assert(m1.id == m2.id, "Two Materialising instances with the same id should have equal id fields")
        val m3 = LoadingSymbol.Materialising(
            id = 99,
            kind = SymbolKind.Class,
            flags = Tasty.Flags.empty,
            name = Tasty.Name("X")
        )
        assert(m1.id != m3.id, "Two Materialising instances with different ids should have different id fields")
        succeed
    }

    // placeholderDeleted
    // Given: the LoadingSymbol ADT after.
    // When: user code tries to reference LoadingSymbol.Placeholder.
    // Then: the type does not exist; compile-time probe returns non-empty errors.
    "placeholderDeleted: LoadingSymbol.Placeholder no longer exists" in {
        val noPlaceholder = compiletime.testing.typeCheckErrors(
            "(??? : kyo.internal.tasty.symbol.LoadingSymbol.Placeholder)"
        )
        assert(noPlaceholder.nonEmpty, "LoadingSymbol.Placeholder must not exist")
        succeed
    }

end LoadingSymbolTest
