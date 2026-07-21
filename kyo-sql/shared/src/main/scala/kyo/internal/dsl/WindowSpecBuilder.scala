package kyo.internal.dsl

import kyo.Chunk
import kyo.Maybe
import kyo.SqlAst.*
import scala.annotation.targetName

/** Window-spec builder. Terminator methods (`rowNumber`, `rank`, etc.) finalise to a `Term[A]`.
  *
  * Builder methods use explicit `new WindowSpecBuilder(...)` constructors rather than `.copy(...)` with `Chunk.from` / `:+` so that the
  * inlined call-site trees are liftable by `FromExpr.derived` (the static-SQL macro). `Chunk(key)` / `Chunk(keys*)` emit a `Chunk.apply`
  * varargs tree which the `FromExpr[Chunk[A]]` derivation recognises; `.copy(...)` and `Chunk.from(keys)` produce runtime method calls that
  * the macro cannot reduce.
  *
  * ===Static-SQL (staticSql) note===
  *
  * The builder chain `Sql.windowSpec.partitionBy(x).rowNumber` lifts cleanly through [[kyo.SqlStatic.staticSql]] (Phase 9b fix to
  * `resolveBindings`'s Select-of-construction fold, the previous limitation requiring an explicit `WindowSpec(...)` constructor at static
  * call sites is gone).
  *
  * ===Replace semantics===
  *
  * `partitionBy(b)` after `partitionBy(a)` replaces the partition list; it does NOT append. The resulting `WindowSpecBuilder.partitions`
  * field is `Chunk(b)`, not `Chunk(a, b)`. Use the vararg overload `partitionBy(a, b)` for multi-key partitions.
  *
  * Similarly, `orderBy(spec2)` after `orderBy(spec1)` replaces the ordering list with `Chunk(spec2)`.
  */
final case class WindowSpecBuilder(
    partitions: Chunk[Term[?]],
    orderings: Chunk[OrderSpec],
    frameOpt: Maybe[WindowFrame]
) derives CanEqual:
    // -- build chain (return fresh builder) ------------------------------------

    /** Replace the partition list with `Chunk(key)`.
      *
      * @note
      *   replace semantic, calling `partitionBy(b)` after `partitionBy(a)` yields `partitions = Chunk(b)`, not `Chunk(a, b)`. Use
      *   `partitionBy(a, b)` for multi-key partitions.
      */
    inline def partitionBy[N <: String & Singleton, V](inline key: Column[N, V]): WindowSpecBuilder =
        new WindowSpecBuilder(Chunk(key), orderings, frameOpt)

    /** Replace the partition list with `Chunk(keys*)`.
      *
      * @note
      *   replace semantic, calling `partitionBy(b)` after `partitionBy(a)` yields `partitions = Chunk(b)`, not `Chunk(a, b)`.
      */
    @targetName("partitionByVarargs")
    inline def partitionBy(inline keys: Term[?]*): WindowSpecBuilder =
        new WindowSpecBuilder(Chunk(keys*), orderings, frameOpt)

    /** Replace the ordering list with `Chunk(spec)`. */
    inline def orderBy(inline spec: OrderSpec): WindowSpecBuilder =
        new WindowSpecBuilder(partitions, Chunk(spec), frameOpt)

    /** Replace the ordering list with `Chunk(specs*)`. */
    @targetName("orderByVarargs")
    inline def orderBy(inline specs: OrderSpec*): WindowSpecBuilder =
        new WindowSpecBuilder(partitions, Chunk(specs*), frameOpt)

    inline def frameRows(inline start: FrameBound, inline end: FrameBound): WindowSpecBuilder =
        new WindowSpecBuilder(partitions, orderings, Maybe(WindowFrame(WindowFrame.Kind.Rows, start, Maybe(end))))
    inline def frameRows(inline start: FrameBound): WindowSpecBuilder =
        new WindowSpecBuilder(partitions, orderings, Maybe(WindowFrame(WindowFrame.Kind.Rows, start, Maybe.empty)))
    inline def frameRange(inline start: FrameBound, inline end: FrameBound): WindowSpecBuilder =
        new WindowSpecBuilder(partitions, orderings, Maybe(WindowFrame(WindowFrame.Kind.Range, start, Maybe(end))))
    inline def frameRange(inline start: FrameBound): WindowSpecBuilder =
        new WindowSpecBuilder(partitions, orderings, Maybe(WindowFrame(WindowFrame.Kind.Range, start, Maybe.empty)))
    inline def frameGroups(inline start: FrameBound, inline end: FrameBound): WindowSpecBuilder =
        new WindowSpecBuilder(partitions, orderings, Maybe(WindowFrame(WindowFrame.Kind.Groups, start, Maybe(end))))
    inline def frameGroups(inline start: FrameBound): WindowSpecBuilder =
        new WindowSpecBuilder(partitions, orderings, Maybe(WindowFrame(WindowFrame.Kind.Groups, start, Maybe.empty)))

    /** Build the underlying [[WindowSpec]] from the accumulated state. */
    inline def build: WindowSpec = WindowSpec(partitions, orderings, frameOpt)

    // -- standalone window-function terminators --------------------------------
    inline def rowNumber: Term[Long]                 = Windowed(WindowFunction.RowNumber, build)
    inline def rank: Term[Long]                      = Windowed(WindowFunction.Rank, build)
    inline def denseRank: Term[Long]                 = Windowed(WindowFunction.DenseRank, build)
    inline def percentRank: Term[Double]             = Windowed(WindowFunction.PercentRank, build)
    inline def cumeDist: Term[Double]                = Windowed(WindowFunction.CumeDist, build)
    inline def ntile(inline n: Int): Term[Int]       = Windowed(WindowFunction.Ntile(intLit(n)), build)
    inline def ntile(inline n: Term[Int]): Term[Int] = Windowed(WindowFunction.Ntile(n), build)
end WindowSpecBuilder
