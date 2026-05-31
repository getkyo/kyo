// PUBLIC per-type satellite for misc types: Version, Position, Pickle, Constant, SubtypeVerdict
package kyo

/** Per-type satellite for miscellaneous small types. Types live in `object Tasty`; this file satisfies Rule 8b. */
object TastyMisc:
    type Version = Tasty.Version
    val Version = Tasty.Version
    type Position = Tasty.Position
    val Position = Tasty.Position
    type Pickle = Tasty.Pickle
    val Pickle = Tasty.Pickle
    type Constant = Tasty.Constant
    val Constant = Tasty.Constant
    type SubtypeVerdict = Tasty.SubtypeVerdict
    val SubtypeVerdict = Tasty.SubtypeVerdict
end TastyMisc
