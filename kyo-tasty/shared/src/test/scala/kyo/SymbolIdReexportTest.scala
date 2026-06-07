package kyo

import kyo.Tasty.SymbolId as InternalSymbolId

/** Tasty.SymbolId type re-export.
  */
class SymbolIdReexportTest extends kyo.test.Test[Any]:

    "Tasty.SymbolId type re-export resolves and is the same as the internal SymbolId" in {
        val id: Tasty.SymbolId           = InternalSymbolId(5)
        val idInternal: InternalSymbolId = InternalSymbolId(5)
        // Both type aliases point to the same underlying type; structural equality holds.
        import kyo.Tasty.SymbolId.value as idValue
        assert(idValue(id) == 5, s"Expected id.value == 5, got ${idValue(id)}")
        assert(idValue(id) == idValue(idInternal))
        succeed
    }

end SymbolIdReexportTest
