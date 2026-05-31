// PUBLIC per-type satellite for kyo.Tasty.SymbolKind; re-exported under kyo.Tasty.SymbolKind
package kyo

/** Per-type satellite for the `SymbolKind` enum. Types live in `object Tasty`; this file satisfies Rule 8b. */
object TastySymbolKind:
    type SymbolKind = Tasty.SymbolKind
    val SymbolKind = Tasty.SymbolKind
end TastySymbolKind
