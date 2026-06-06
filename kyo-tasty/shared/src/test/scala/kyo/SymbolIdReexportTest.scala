package kyo

import kyo.Tasty.SymbolId as InternalSymbolId

/** Tasty.SymbolId type re-export.
  */
class SymbolIdReexportTest extends Test:

    // ── Leaf 172: symbolid-reexport-compiles ──────────────────────────────────
    // Given: an in-test binding val id: Tasty.SymbolId = kyo.Tasty.SymbolId(5)
    // When: compile
    // Then: compiles cleanly; binding structurally equal to the un-re-exported form
    "Leaf 172: Tasty.SymbolId type re-export resolves and is the same as the internal SymbolId" in run {
        val id: Tasty.SymbolId           = InternalSymbolId(5)
        val idInternal: InternalSymbolId = InternalSymbolId(5)
        // Both type aliases point to the same underlying type; structural equality holds.
        import kyo.Tasty.SymbolId.value as idValue
        assert(idValue(id) == 5, s"Expected id.value == 5, got ${idValue(id)}")
        assert(idValue(id) == idValue(idInternal))
        succeed
    }

end SymbolIdReexportTest
