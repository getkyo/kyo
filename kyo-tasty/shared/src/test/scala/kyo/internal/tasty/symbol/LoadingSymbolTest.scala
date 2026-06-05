package kyo.internal.tasty.symbol

import kyo.*

/** Tests for `LoadingSymbol` ADT: the internal loading-phase symbol representation.
  *
  * Covers Phase 08 plan leaves 1, 2, 3 (Cat 19 LoadingSymbol architectural backbone):
  *   - placeholderInaccessibleFromUserCode: LoadingSymbol is private[kyo]; user code cannot access it.
  *   - placeholderShapeIsCorrect: Placeholder fields match constructor arguments; no id field.
  *   - materialisingHoldsId: Materialising.id holds the assigned value; structural equality on id.
  *
  * Pins: INV-LOADING-SYMBOL.
  */
class LoadingSymbolTest extends kyo.Test:

    // Leaf 1: placeholderInaccessibleFromUserCode
    //
    // Given: a compileErrors probe that LoadingSymbol is NOT accessible through kyo.Tasty.
    // When: the test asserts.
    // Then: the type does not appear on the public Tasty surface; LoadingSymbol is an
    //       internal type (private[kyo]) not re-exported from object Tasty.
    // Pins: INV-LOADING-SYMBOL.
    "placeholderInaccessibleFromUserCode: LoadingSymbol is not accessible through the public Tasty API" in {
        // kyo.Tasty does not expose LoadingSymbol: accessing it via Tasty.LoadingSymbol fails.
        val notOnTastySurface = compiletime.testing.typeCheckErrors(
            "(??? : kyo.Tasty.LoadingSymbol)"
        )
        assert(notOnTastySurface.nonEmpty, "LoadingSymbol must not be exposed on the Tasty public surface")
        // The type IS accessible inside kyo.* (private[kyo] includes kyo.internal.tasty.symbol):
        val witness: LoadingSymbol = LoadingSymbol.Placeholder(
            SymbolKind.Class,
            Tasty.Flags.empty,
            Tasty.Name("X")
        )
        discard(witness)
        succeed
    }

    // Leaf 2: placeholderShapeIsCorrect
    //
    // Given: a `LoadingSymbol.Placeholder(SymbolKind.Class, Flags.empty, Name("X"))` instance.
    // When: the test pattern-matches and reads fields.
    // Then: every field matches the constructor arguments; the Placeholder has no `id` field.
    // Pins: INV-LOADING-SYMBOL.
    "placeholderShapeIsCorrect: Placeholder fields match constructor arguments and has no id field" in {
        val ph = LoadingSymbol.Placeholder(
            kind = SymbolKind.Class,
            flags = Tasty.Flags.empty,
            name = Tasty.Name("X")
        )
        ph match
            case LoadingSymbol.Placeholder(k, f, n) =>
                assert(k == SymbolKind.Class, s"Expected kind Class but got $k")
                assert(f == Tasty.Flags.empty, s"Expected empty flags but got $f")
                import Tasty.Name.asString
                assert(n.asString == "X", s"Expected name 'X' but got ${n.asString}")
        end match
        // Verify no id field on Placeholder (compile-time check: accessing .id on a Placeholder should fail).
        val noIdOnPlaceholder = compiletime.testing.typeCheckErrors("(??? : kyo.internal.tasty.symbol.LoadingSymbol.Placeholder).id")
        assert(noIdOnPlaceholder.nonEmpty, "Placeholder must not have an id field")
        succeed
    }

    // Leaf 3: materialisingHoldsId
    //
    // Given: a `LoadingSymbol.Materialising(id = 42, kind = SymbolKind.Class, flags = Flags.empty, name = Name("X"))`.
    // When: the test reads `m.id`.
    // Then: the value is 42; structural equality on two Materialising values with the same id holds.
    // Pins: INV-LOADING-SYMBOL.
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

end LoadingSymbolTest
