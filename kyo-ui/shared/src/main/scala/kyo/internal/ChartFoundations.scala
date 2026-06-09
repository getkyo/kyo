package kyo.internal

import kyo.*
import kyo.Chart.*
import kyo.ConcreteTag
import scala.annotation.targetName

/** Cross-cutting foundation helpers for the charts lowering.
  *
  * Houses the identity key that replaces `toString` category keying, the finite guard
  * used at every extent fold, the per-chart id prefix for `url(#id)`-bearing gradient defs
  * (so each chart's defs are self-contained and do not collide with sibling charts), and the `Chunk`-native ordered-distinct helper. Kept
  * `private[kyo]`: these are engine internals, never public surface.
  */
private[kyo] object ChartFoundations:

    /** Identity key for a category / series / group / x-key value.
      *
      * Pairs the raw value with the compile-time-derived `ConcreteTag` of its static
      * type so `1: Int` and `"1": String` stay distinct (their `toString` collides)
      * and two enum cases renamed to the same display label stay distinct (keyed by
      * tag + value, the enum case is its own value). The tag is a stable, platform-
      * independent type identity (`ConcreteTag[Int]` is `IntTag` on every platform),
      * unlike a boxed runtime class which diverges between the JVM and Scala.js.
      * Compares by case-class structural equality. A `null` value is keyed by its
      * tag with a `null` value (`CatKey(tag, null)`), never an NPE: two nulls under
      * the same tag compare equal.
      */
    final case class CatKey(tag: ConcreteTag[Any], value: Any) derives CanEqual

    /** Builds the identity key for a statically-typed category value.
      *
      * The `ConcreteTag[C]` is derived by the compiler at the call site, so the key's
      * type identity comes from the static type `C`, not the value's runtime class.
      */
    def categoryKey[C](value: C)(using tag: ConcreteTag[C]): CatKey =
        categoryKey(tag, value)

    /** Builds the identity key for an erased category value carrying an explicit tag.
      *
      * Used at the lowering sites that read a value off an `Encoding[A, ?]` whose
      * element type is erased: the encoding carries the tag captured from its static
      * construction type.
      */
    @targetName("categoryKeyTagged")
    def categoryKey[C](tag: ConcreteTag[C], value: Any): CatKey =
        // `value` may be `null` when a typed encoding accessor yields a null reference. `CatKey` keys it
        // by `(tag, null)`: case-class structural equality treats two nulls under the same tag as equal,
        // and the value is never dereferenced here, so there is no NPE.
        // Unsafe: widening `ConcreteTag[C]` to `ConcreteTag[Any]` is erasure-safe; the tag is used only as a
        // structural identity component of the key, never to reconstruct or constrain `C`.
        CatKey(tag.asInstanceOf[ConcreteTag[Any]], value)

    /** True when `v` is a finite double (not NaN, not +/-Infinity). */
    inline def isFiniteDouble(v: Double): Boolean = java.lang.Double.isFinite(v)

    /** Returns `Absent` when `d` is `Domain.Continuous` with a non-finite value; otherwise returns the input.
      *
      * Used at every lowering site that maps a domain value to a pixel coordinate, so non-finite values
      * are skipped rather than passed to `Scale.apply` (which would produce NaN pixel coordinates).
      */
    def filterFinite(d: Maybe[Domain]): Maybe[Domain] = d match
        case Present(Domain.Continuous(v)) => if isFiniteDouble(v) then d else Absent
        case other                         => other

    /** Insertion-ordered distinct over `rows` keyed by `key`, without any
      * `toSeq` round-trip and with O(1) membership.
      *
      * Returns each first-seen `(CatKey, representativeRow)` in encounter order.
      * Empty and single-element inputs fast-path. Replaces the
      * `acc.toSeq.contains` O(n^2) membership scans in the lowering.
      */
    def distinctKeyed[A](rows: Chunk[A], key: A => CatKey): Chunk[(CatKey, A)] =
        if rows.isEmpty then Chunk.empty
        else
            // Set-backed O(1) membership keeps this O(n); a @tailrec inner def walks the rows.
            val seen = scala.collection.mutable.HashSet.empty[CatKey]
            @scala.annotation.tailrec
            def loop(i: Int, acc: Chunk[(CatKey, A)]): Chunk[(CatKey, A)] =
                if i >= rows.size then acc
                else
                    val row = rows(i)
                    val k   = key(row)
                    if seen.add(k) then loop(i + 1, acc.append((k, row)))
                    else loop(i + 1, acc)
            loop(0, Chunk.empty)

    /** Group `rows` by `key` in one pass, returning a map from each key to the rows with that key in
      * encounter order within each group. Empty input fast-paths to an empty map. Does NOT sort by enum
      * ordinal; callers that need ordinal order must sort the keys separately (e.g. via
      * `collectColorCategoriesWithRaw`).
      */
    def groupByKey[A](rows: Chunk[A], key: A => CatKey): Map[CatKey, Chunk[A]] =
        if rows.isEmpty then Map.empty
        else
            @scala.annotation.tailrec
            def loop(i: Int, acc: Map[CatKey, Chunk[A]]): Map[CatKey, Chunk[A]] =
                if i >= rows.size then acc
                else
                    val row = rows(i)
                    val k   = key(row)
                    val updated = acc.get(k) match
                        case Some(existing) => acc.updated(k, existing.append(row))
                        case None           => acc.updated(k, Chunk(row))
                    loop(i + 1, updated)
            loop(0, Map.empty)

    /** Per-chart deterministic id prefix for `url(#id)`-bearing defs.
      *
      * Content-derived from the spec structural hash so two distinct charts on
      * one page never alias the same `url(#id)`; structurally identical specs
      * alias identical defs (benign). NOT `genId(Frame.internal)` (which collides
      * across all charts in a session).
      */
    def chartIdPrefix(spec: Chart[?]): String =
        "kyo-chart-" + Integer.toHexString(spec.##)

    /** A fresh document-unique id prefix for one lowered chart.
      *
      * Gradient and clip defs are referenced by `url(#id)`, so two charts on one page must not share an id,
      * including two lowerings of the same spec (which would otherwise emit duplicate `id` attributes). A
      * fresh random suffix per call gives each lowered instance a distinct prefix with no shared counter.
      */
    def chartInstancePrefix()(using AllowUnsafe): String =
        // Unsafe: a fresh non-deterministic id for one lowered chart instance, generated inside the Sync
        // lowering boundary so each instance gets its own url(#id) namespace.
        "kyo-chart-" + java.lang.Long.toHexString(scala.util.Random.nextLong())

end ChartFoundations
