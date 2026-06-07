package kyo

import AllowUnsafe.embrace.danger
import kyo.Tasty.SymbolId

/** plan-mandated tests confirming exhaustive match on the Symbol hierarchy under -Xfatal-warnings.
  *
  * Leaves 13-14 per plan 05-plan.yaml id:1. Pins: INV-006.
  */
class SymbolExhaustiveMatchTest extends kyo.test.Test[Any]:

    // ── Leaf 13: exhaustive-match-14-cases-compiles ──────────────────────────

    // Given: a 14-branch match on Symbol with no wildcard.
    // When: the test file compiles under -Xfatal-warnings.
    // Then: compiles cleanly (no "match may not be exhaustive" warning).
    "Leaf 13: exhaustive 14-case match on Symbol compiles cleanly" in {
        def kindLabel(s: Tasty.Symbol): String = s match
            case _: Tasty.Symbol.Class        => "Class"
            case _: Tasty.Symbol.Trait        => "Trait"
            case _: Tasty.Symbol.Object       => "Object"
            case _: Tasty.Symbol.Method       => "Method"
            case _: Tasty.Symbol.Val          => "Val"
            case _: Tasty.Symbol.Var          => "Var"
            case _: Tasty.Symbol.Field        => "Field"
            case _: Tasty.Symbol.TypeAlias    => "TypeAlias"
            case _: Tasty.Symbol.OpaqueType   => "OpaqueType"
            case _: Tasty.Symbol.AbstractType => "AbstractType"
            case _: Tasty.Symbol.TypeParam    => "TypeParam"
            case _: Tasty.Symbol.Parameter    => "Parameter"
            case _: Tasty.Symbol.Package      => "Package"

        val sym: Tasty.Symbol = Tasty.Symbol.Package(SymbolId(-1), Tasty.Name("x"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty)
        assert(kindLabel(sym) == "Package")
        succeed
    }

    // ── Leaf 14: exhaustive-classlike-3-cases ────────────────────────────────

    // Given: a 3-branch match on Symbol.ClassLike with no wildcard.
    // When: the test file compiles under -Xfatal-warnings.
    // Then: compiles cleanly.
    "Leaf 14: exhaustive 3-case match on Symbol.ClassLike compiles cleanly" in {
        def cl(c: Tasty.Symbol.ClassLike): String = c match
            case _: Tasty.Symbol.Class  => "C"
            case _: Tasty.Symbol.Trait  => "T"
            case _: Tasty.Symbol.Object => "O"

        val obj: Tasty.Symbol.ClassLike = Tasty.Symbol.Object(
            id = SymbolId(1),
            name = Tasty.Name("MyObj"),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(0),
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            annotations = Chunk.empty,
            javaAnnotations = Chunk.empty
        )
        assert(cl(obj) == "O")
        succeed
    }

end SymbolExhaustiveMatchTest
