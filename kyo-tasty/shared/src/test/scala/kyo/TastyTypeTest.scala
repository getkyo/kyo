package kyo

import kyo.internal.tasty.query.ClasspathRef

/** Tests for Tasty.Type public API surface.
  *
  * Phase 13 (INV: T1). Covers Type.show for Applied types.
  */
class TastyTypeTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Build a synthetic Named type for the given dotted FQN. */
    private def makeNamed(fqn: String): Tasty.Type.Named =
        val parts = fqn.split("\\.").toList
        val root = Tasty.Symbol.make(
            Tasty.SymbolKind.Package,
            Tasty.Flags.empty,
            Tasty.Name(""),
            null,
            new ClasspathRef,
            Tasty.Symbol.TastyOrigin.empty,
            Absent
        )
        val finalSym = parts.foldLeft(root) { (owner, part) =>
            Tasty.Symbol.make(
                Tasty.SymbolKind.Class,
                Tasty.Flags.empty,
                Tasty.Name(part),
                owner,
                new ClasspathRef,
                Tasty.Symbol.TastyOrigin.empty,
                Absent
            )
        }
        Tasty.Type.Named(finalSym)
    end makeNamed

    // Test 5 (INV: T1, Type.show): Applied(scala.List, scala.Int) shows as "scala.List[scala.Int]".
    // Given: listSym with fullName "scala.List", intSym with fullName "scala.Int".
    // When: t = Applied(Named(listSym), Chunk(Named(intSym))); t.show evaluated.
    // Then: returns "scala.List[scala.Int]".
    // Pins: T1 (Type.show Applied coverage).
    "Type.show for Applied(scala.List, scala.Int) returns scala.List[scala.Int]" in {
        val listType = makeNamed("scala.List")
        val intType  = makeNamed("scala.Int")
        val applied  = Tasty.Type.Applied(listType, Chunk(intType))
        assert(
            applied.show == "scala.List[scala.Int]",
            s"Expected 'scala.List[scala.Int]' but got '${applied.show}'"
        )
    }

end TastyTypeTest
