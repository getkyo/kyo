package kyo

import kyo.Tasty.SymbolId

/** Tests for the SymbolId opaque type.
  *
  * . Covers identity equality, inequality, and pattern-binding (leaves 1-3). The private[kyo] construction constraint (leaf 4)
  * lives in external.SymbolIdVisibilityTest, which is in package external so that assertDoesNotCompile evaluates the snippet outside the
  * kyo package scope. typeCheckErrors from within package kyo would not reject private[kyo] members because the test file itself is inside
  * the kyo package.
  */
class SymbolIdTest extends kyo.test.Test[Any]:

    // Leaf 1: SymbolId identity equality.
    // Given: two SymbolId.apply(7) calls.
    // When: compared via == and via underlying value extension.
    // Then: both return true; underlying Int is 7.
    "SymbolId identity equality: two apply(7) calls are equal" in {
        val a = SymbolId(7)
        val b = SymbolId(7)
        assert(a == b, s"Expected a == b but got: a=$a b=$b")
        assert(a.value == 7, s"Expected value == 7 but got ${a.value}")
        assert(b.value == 7, s"Expected value == 7 but got ${b.value}")
    }

    // Leaf 2: SymbolId inequality.
    // Given: SymbolId.apply(7) and SymbolId.apply(8).
    // When: compared via ==.
    // Then: returns false.
    "SymbolId inequality: apply(7) != apply(8)" in {
        val a = SymbolId(7)
        val b = SymbolId(8)
        assert(a != b, s"Expected a != b but both had the same value")
    }

    // Leaf 3: SymbolId pattern-binding.
    // Given: a SymbolId produced from value 42.
    // When: pattern-matched and the underlying Int extracted via value extension.
    // Then: the extracted Int equals 42.
    "SymbolId pattern-binding: extract underlying Int via match" in {
        val s: SymbolId = SymbolId(42)
        val extracted = s match
            case x: SymbolId => x.value
        assert(extracted == 42, s"Expected extracted == 42 but got $extracted")
    }

end SymbolIdTest
