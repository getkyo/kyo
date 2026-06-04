package kyo.internal

import kyo.*
import kyo.UI.Ast.*

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

    /** Per-chart deterministic id prefix for `url(#id)`-bearing defs.
      *
      * Content-derived from the spec structural hash so two distinct charts on
      * one page never alias the same `url(#id)`; structurally identical specs
      * alias identical defs (benign). NOT `genId(Frame.internal)` (which collides
      * across all charts in a session). See catalog #20/#23, Q-002.
      */
    def chartIdPrefix(spec: ChartSpec[?]): String =
        "kyo-chart-" + Integer.toHexString(spec.##)

    /** Monotonic counter assigning each lowered chart a document-unique instance id.
      *
      * `spec.##` is a STRUCTURAL hash: two charts built from identical specs (same size, same marks, same
      * color scale) share it, so two such charts on one page would emit duplicate gradient def ids and the
      * browser would bind every `url(#id)` to the first one. This counter is incremented once per `lower`
      * call so each chart instance gets a distinct id regardless of its structural hash. The literal value is
      * opaque plumbing (not stable across runs); what matters is that within one chart the gradient def id and
      * its `url(#id)` reference match, and two charts in one document get different ids.
      */
    // Unsafe: a module-private atomic counter, the standard document-unique id scheme (D3/vega use the same).
    // Incremented from the synchronous, pure `lower` projection, so a kyo Atomic primitive is the right tool.
    private val instanceCounter: AtomicInt.Unsafe =
        import AllowUnsafe.embrace.danger
        AtomicInt.Unsafe.init(0)

    /** Allocate a fresh document-unique id prefix for one lowered chart instance. */
    def nextChartInstancePrefix(): String =
        import AllowUnsafe.embrace.danger
        "kyo-chart-" + Integer.toHexString(instanceCounter.incrementAndGet())

end ChartFoundations
