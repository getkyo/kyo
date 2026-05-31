// flow-allow: PUBLIC per-type satellite for kyo.Tasty.Symbol and kyo.Tasty.SymbolBody; re-exported under kyo.Tasty.Symbol
package kyo

/** Per-type satellite for `Symbol` and `SymbolBody`. Types live in `object Tasty`; this file satisfies Rule 8b. */
object TastySymbol:
    type Symbol = Tasty.Symbol
    val Symbol = Tasty.Symbol
    type SymbolBody = Tasty.SymbolBody
    val SymbolBody = Tasty.SymbolBody
end TastySymbol
