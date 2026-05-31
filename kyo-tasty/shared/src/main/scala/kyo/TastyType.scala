// flow-allow: PUBLIC per-type satellite for kyo.Tasty.Type; re-exported under kyo.Tasty.Type
package kyo

/** Per-type satellite for the `Type` enum. Types live in `object Tasty`; this file satisfies Rule 8b. */
object TastyType:
    type Type = Tasty.Type
    val Type = Tasty.Type
end TastyType
