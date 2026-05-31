// flow-allow: PUBLIC per-type satellite for kyo.Tasty.Classpath; re-exported under kyo.Tasty.Classpath
package kyo

/** Per-type satellite for the `Classpath` case class and companion. Types live in `object Tasty`; this file satisfies Rule 8b. */
object TastyClasspath:
    type Classpath = Tasty.Classpath
    val Classpath = Tasty.Classpath
end TastyClasspath
