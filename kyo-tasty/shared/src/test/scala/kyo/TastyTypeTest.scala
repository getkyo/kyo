package kyo

/** Tests for Tasty.Type public API surface.
  *
  * Phase 13 (INV: T1). Covers Type.show for Applied types.
  *
  * makeNamed is inherited from TastyTestSupport (Phase 21g deduplication).
  */
class TastyTypeTest extends Test with TastyTestSupport:

    import AllowUnsafe.embrace.danger

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
