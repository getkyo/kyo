// PUBLIC per-type satellite for kyo.internal.tasty.symbol.SymbolId; re-exported for user convenience
package kyo

/** Per-type satellite that re-exports the internal `SymbolId` opaque type for user access. */
object TastySymbolId:
    type SymbolId = kyo.internal.tasty.symbol.SymbolId
    val SymbolId = kyo.internal.tasty.symbol.SymbolId
end TastySymbolId
