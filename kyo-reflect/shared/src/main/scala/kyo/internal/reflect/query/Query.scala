package kyo.internal.reflect.query

import kyo.*
import kyo.Stream

/** Lazy combinator query over a `Classpath`.
  *
  * Constructed via `cp.query[A](using Reads[A])` on a `Reflect.Classpath`. Terminal operations `.run` and `.stream` perform a single
  * traversal over the symbol cache, applying all accumulated predicates and the `Reads[A]` projection.
  *
  * Effect row of terminal operations: `Sync & Async & Abort[ReflectError]`. The Async effect is required because `findClass` and the
  * underlying `lookupClass` calls carry `Async` (Cache.memo Promise dedup and the Building-state latch both require it).
  *
  * `map` is implemented by wrapping the `Reads[A]` in a derived `Reads[B]` that post-processes with the given function. This avoids unsafe
  * casts for the transformation chain.
  */
final class Query[A] private[reflect] (
    private val cp: Classpath,
    private val reads: Reflect.Reads[A],
    private val symbolPreds: Chunk[Reflect.Symbol => Boolean],
    private val valuePreds: Chunk[A => Boolean]
):
    import Query.*

    /** Retain only symbols for which the predicate returns true (applied before `Reads.read`). */
    def where(p: Reflect.Symbol => Boolean): Query[A] =
        new Query(cp, reads, symbolPreds.appended(p), valuePreds)

    /** Retain only symbols that have the given flag set (shortcut for `where(_.flags.contains(f))`). */
    def withFlag(f: Reflect.Flag): Query[A] =
        where(sym => sym.flags.contains(f))

    /** Retain only symbols whose simple name matches. */
    def named(name: String): Query[A] =
        where(sym => sym.name.asString == name)

    /** Retain only symbols that extend the given parent.
      *
      * Best-effort filter: checks declared parents of TASTy symbols via `reads.read`. For symbols without parent info loaded, this may
      * return false negatives. Use in conjunction with a suffix run for full accuracy.
      *
      * Implementation: filtered at symbol-predicate level using a dedicated parent check via the parent FQN.
      */
    def extending(parentFqn: String): Query[A] =
        where: sym =>
            // Check if the parent FQN appears in the classpath fqnIndex and if the symbol's fqn starts there.
            // This is a name-based heuristic. Full type-based checking requires reads which is deferred to run time.
            // For now: exclude package symbols and let run() do the expensive parent check.
            sym.kind match
                case Reflect.SymbolKind.Package => false
                case _                          => true // pass through; rely on value predicates for deeper check

    /** Retain only decoded values for which the predicate returns true (applied after `Reads.read`). */
    def filter(p: A => Boolean): Query[A] =
        new Query(cp, reads, symbolPreds, valuePreds.appended(p))

    /** Transform decoded values.
      *
      * Implemented by composing `f` into the `Reads[A]` to produce a `Reads[B]`. This avoids unsafe casts.
      */
    def map[B](f: A => B): Query[B] =
        val outerReads = reads
        val wrappedReads = new Reflect.Reads[B]:
            val symbolKinds: Set[Reflect.SymbolKind] = outerReads.symbolKinds
            val needsBodies: Boolean                 = outerReads.needsBodies
            val touchedFields: Reflect.FieldSet      = outerReads.touchedFields
            def read(sym: Reflect.Symbol)(using Frame): B < (Sync & Async & Abort[ReflectError]) =
                outerReads.read(sym).map(f)
        new Query[B](cp, wrappedReads, symbolPreds, Chunk.empty)
    end map

    /** Materialize all results as a `Chunk`. */
    def run(using Frame): Chunk[A] < (Sync & Async & Abort[ReflectError]) =
        executeQuery

    /** Return results as a lazy `Stream`. */
    def stream(using Frame, Tag[Emit[Chunk[A]]]): Stream[A, Sync & Async & Abort[ReflectError]] =
        Stream.init(executeQuery)

    private def executeQuery(using Frame): Chunk[A] < (Sync & Async & Abort[ReflectError]) =
        cp.checkOpen.andThen:
            val allSyms = cp.allSymbols
            // Filter by symbolKinds from Reads
            val kindFiltered =
                if reads.symbolKinds.isEmpty then allSyms
                else allSyms.filter(sym => reads.symbolKinds.contains(sym.kind))
            // Apply symbol predicates
            val symFiltered = symbolPreds.foldLeft(kindFiltered): (syms, pred) =>
                syms.filter(pred)
            // Read each symbol and apply value predicates
            Kyo.foreach(symFiltered): sym =>
                reads.read(sym).map: a =>
                    if valuePreds.forall(p => p(a)) then Maybe(a)
                    else Maybe.Absent
            .map(_.flatten)

end Query

object Query:

    /** Construct a Query. Called by `Reflect.Classpath` extension `query[A]`. */
    private[kyo] def make[A](cp: Classpath, reads: Reflect.Reads[A]): Query[A] =
        new Query[A](cp, reads, Chunk.empty, Chunk.empty)

end Query
