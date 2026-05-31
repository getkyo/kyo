// flow-allow: PUBLIC per-type satellite for kyo.Tasty.Tree; re-exported under kyo.Tasty.Tree
package kyo

/** Per-type satellite for the `Tree` sealed trait and case classes. Types live in `object Tasty`; this file satisfies Rule 8b. */
object TastyTree:
    type Tree = Tasty.Tree
    val Tree = Tasty.Tree
end TastyTree
