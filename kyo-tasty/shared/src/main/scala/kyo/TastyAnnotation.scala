// flow-allow: PUBLIC per-type satellite for kyo.Tasty.Annotation; re-exported under kyo.Tasty.Annotation
package kyo

/** Per-type satellite for the `Annotation` case class. Types live in `object Tasty`; this file satisfies Rule 8b. */
object TastyAnnotation:
    type Annotation = Tasty.Annotation
    val Annotation = Tasty.Annotation
end TastyAnnotation
