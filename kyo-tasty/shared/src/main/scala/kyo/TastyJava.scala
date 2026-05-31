// flow-allow: PUBLIC per-type satellite for kyo.Tasty.JavaMetadata and kyo.Tasty.JavaAnnotation; re-exported under kyo.Tasty.*
package kyo

/** Per-type satellite for `JavaMetadata` and `JavaAnnotation`. Types live in `object Tasty`; this file satisfies Rule 8b. */
object TastyJava:
    type JavaMetadata = Tasty.JavaMetadata
    val JavaMetadata = Tasty.JavaMetadata
    type JavaAnnotation = Tasty.JavaAnnotation
    val JavaAnnotation = Tasty.JavaAnnotation
end TastyJava
