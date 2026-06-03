package kyo.internal

import kyo.*

/** Cross-cutting foundation helpers for the charts lowering.
  *
  * Houses the identity key that replaces `toString` category keying (catalog #6),
  * the finite guard used at every extent fold (catalog #2), the per-chart id
  * prefix for `url(#id)`-bearing defs (catalog #20/#23, the N4 constraint), and
  * the `Chunk`-native ordered-distinct helper (catalog #32/#35). Kept `private[kyo]`:
  * these are engine internals, never public surface.
  */
private[kyo] object ChartFoundations:

    /** Identity key for a category / series / group / x-key value.
      *
      * Pairs the raw value with its runtime class so `1: Int` and `"1": String`
      * stay distinct (their `toString` collides) and two enum cases renamed to
      * the same display label stay distinct (keyed by class + value, the enum case
      * is its own value). Compares by case-class structural equality. A `null` raw
      * folds to `CatKey(null, null)`, a single stable bucket, never an NPE.
      */
    final case class CatKey(cls: Class[?], value: Any) derives CanEqual

    /** Builds the identity key for a raw category value. */
    def categoryKey(raw: Any): CatKey =
        // Use eq null (reference equality) to safely handle the null case without NPE
        if raw.asInstanceOf[AnyRef] eq null then CatKey(null, null)
        else CatKey(raw.getClass, raw)

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
      * `toSeq` round-trip and with O(1) membership (catalog #32/#35).
      *
      * Returns each first-seen `(CatKey, representativeRow)` in encounter order.
      * Empty and single-element inputs fast-path. Replaces the
      * `acc.toSeq.contains` O(n^2) membership scans in the lowering.
      */
    def distinctKeyed[A](rows: Chunk[A], key: A => CatKey): Chunk[(CatKey, A)] =
        if rows.isEmpty then Chunk.empty
        else
            val seen = scala.collection.mutable.HashSet.empty[CatKey]
            val buf  = Chunk.newBuilder[(CatKey, A)]
            val it   = rows.iterator
            while it.hasNext do
                val row = it.next()
                val k   = key(row)
                if seen.add(k) then buf += ((k, row))
            end while
            buf.result()

    /** Per-chart deterministic id prefix for `url(#id)`-bearing defs.
      *
      * Content-derived from the spec structural hash so two distinct charts on
      * one page never alias the same `url(#id)`; structurally identical specs
      * alias identical defs (benign). NOT `genId(Frame.internal)` (which collides
      * across all charts in a session). See catalog #20/#23, Q-002.
      */
    def chartIdPrefix(spec: ChartSpec[?]): String =
        "kyo-chart-" + Integer.toHexString(spec.##)

end ChartFoundations
