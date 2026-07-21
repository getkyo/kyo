package kyo.internal

import kyo.*
import kyo.SqlAst.*

/** Construction-time post-`groupBy` view builder.
  *
  * Walks the source's `columns` Record and produces a rewritten `Record[F]` where each leaf is wrapped as [[GroupedColumn]] (for keys
  * appearing in the grouping set) or [[UngroupedView]] (for non-key columns). The resulting record's dict is the runtime payload of
  * [[GroupBy.view]]; the type-level `F` is computed by [[internal.RewriteGrouped]] at the `groupBy[…]` call site.
  *
  * Lifted verbatim from the renderer's former `materialiseView` (pre-Phase-6.5) so byte-for-byte output is preserved. The only cast
  * (`Record[Any] → Record[F]`) lives at the AST builder seam where `F` is in scope at the type level — replacing the renderer's former cast
  * on the materialise function (which has now been deleted).
  *
  * Labelled-term keys: `materialiseView` collected key names from `case c: Column[?, ?] => c.name`. `LabelledTerm` keys are NOT in that
  * set, so labelled-keyed columns are treated as ungrouped — this byte-for-byte behaviour is preserved.
  */
// NOT `private[kyo]`: this object is referenced from the `inline def groupBy` methods in
// `SqlAst.Table` / `SqlAst.Where`. A `private[kyo]` modifier drives the Scala 3 compiler to
// generate a broken inline accessor that treats the `kyo.internal` package as a value module
// (`getstatic kyo/internal.MODULE$`), producing `NoClassDefFoundError: kyo/internal` at runtime.
// Residence in the `kyo.internal` package already marks this as implementation-only.
object SqlGroupedView:

    /** Build the post-`groupBy` view from the source query and grouping keys. */
    def buildGroupedView[F](source: Query[?], keys: Chunk[Term[?]]): Record[F] =
        val keyNames: Set[String] = keys.toSeq.collect { case c: Column[?, ?] => c.name }.toSet
        val outerCols             = sourceColumns(source)
        // Unwrap a single-key outer alias wrapper (`Record["alias" ~ Record[innerFields]]`) so the post-groupBy view
        // is flat (`view.deptId` not `view.p.deptId`). If the source has no alias wrap, use it directly.
        val inner: Dict[String, Any] = outerCols.dict.toMap.values.toList match
            case (r: Record[?]) :: Nil => r.dict
            case _                     => outerCols.dict
        val rebuilt = scala.collection.mutable.Map.empty[String, Any]
        inner.foreach: (k, v) =>
            v match
                case c: Column[?, ?] =>
                    if keyNames.contains(c.name) then rebuilt(k) = GroupedColumn(c)
                    else rebuilt(k) = UngroupedView(c)
                case other => rebuilt(k) = other
        // `Record` is phantom in `F` at runtime; the concrete `F` is inferred at the `groupBy` call site.
        MacroSupport.narrowPhantom[Record[F]](new Record[Any](Dict.from(rebuilt.toMap)))
    end buildGroupedView

    private def sourceColumns(q: Query[?]): Record[?] = q match
        case t: Table[?, ?]      => t.columns
        case n: Nested[?, ?]     => n.columns
        case l: Lateral[?, ?]    => l.columns
        case v: ValuesFrom[?, ?] => v.columns
        case j: Join[?, ?]       => j.columns
        case cj: CrossJoin[?, ?] => cj.columns
        case w: Where[?, ?]      => w.columns
        case _                   => new Record[Any](Dict.empty)

end SqlGroupedView
