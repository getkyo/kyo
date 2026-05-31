// PUBLIC per-type satellite for kyo.Tasty.Flag and kyo.Tasty.Flags; re-exported under kyo.Tasty.Flag/Flags
package kyo

/** Per-type satellite for the `Flag` and `Flags` types. Types live in `object Tasty`; this file satisfies Rule 8b. */
object TastyFlags:
    type Flag = Tasty.Flag
    val Flag = Tasty.Flag
    type Flags = Tasty.Flags
    val Flags = Tasty.Flags
end TastyFlags
