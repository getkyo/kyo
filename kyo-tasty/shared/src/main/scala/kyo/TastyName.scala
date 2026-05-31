// flow-allow: PUBLIC per-type satellite for kyo.Tasty.Name; re-exported under kyo.Tasty.Name
package kyo

/** Per-type satellite for the `Name` opaque type. Types live in `object Tasty`; this file satisfies Rule 8b (one file per top-level type).
  */
object TastyName:
    type Name = Tasty.Name
    val Name = Tasty.Name
end TastyName
