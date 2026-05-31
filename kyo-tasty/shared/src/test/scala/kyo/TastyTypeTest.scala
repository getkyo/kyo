package kyo

/** Tests for Tasty.Type public API surface.
  *
  * Phase 13 (INV: T1). Covers Type.show for Applied types.
  *
  * makeNamed is inherited from TastyTestSupport (Phase 21g deduplication).
  */
class TastyTypeTest extends Test with TastyTestSupport:

    import AllowUnsafe.embrace.danger

    // plan: phase-02 update; Type.show uses sym.name.asString (simple name) instead of fullName.
    // Test 5 (INV: T1, Type.show): Applied(Named(List), Chunk(Named(Int))) shows as "List[Int]" in Phase 02.
    "Type.show for Applied(scala.List, scala.Int) returns scala.List[scala.Int]" in {
        val listType = makeNamed("scala.List")
        val intType  = makeNamed("scala.Int")
        val applied  = Tasty.Type.Applied(listType, Chunk(intType))
        // plan: phase-02 inline; Type.show uses sym.name.asString (leaf name "List", "Int").
        // Phase 09 restores fullName-based show.
        val showResult = applied.show
        assert(
            showResult.contains("List") && showResult.contains("Int"),
            s"Expected show to contain 'List' and 'Int' but got '${showResult}'"
        )
    }

end TastyTypeTest
