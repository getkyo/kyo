package kyo.internal

import kyo.*
import kyo.SqlAst.*
import scala.annotation.tailrec
import scala.quoted.*

/** Custom `FromExpr` instances for AST `Term` leaves that arise from `Record` field projections rather than direct case-class construction.
  *
  * `FromExpr.derived` reconstructs values by walking constructor `Apply` trees. A column reference written as `c.p.age` in DSL code does
  * NOT expand to a `Column.apply(...)` constructor, it expands to a `selectDynamic` projection chain which, after inlining, is a nested
  * `Dict.apply(<record>.inline$dict, "key")` chain. The derived product/sum walker has no arm for that shape, so every WHERE / SELECT /
  * JOIN / groupBy query that references a column fails to lift (worse: the projection-shaped term silently mis-matches a zero-arg AST case
  * class such as `Default()`).
  *
  * `fromExprColumn` ports the deleted `SqlStaticMacro.R.RecordColumnAccess` extractor: it collects the literal key segments of the
  * projection chain. A column reference `c.<alias>.<name>` collects two keys, the outer is the table alias, the inner is the column name,
  * directly yielding `Column(alias, name)`. No `Record` reconstruction is needed because the keys ARE the alias / name. The plain
  * `Column.apply(...)` constructor shape is handled too.
  *
  * `fromExprGroupedColumn` / `fromExprUngroupedView` handle the post-`groupBy` view: `view.<name>` is a single-key projection out of the
  * flat view `Record`. The view `Record` is reconstructed via `RecordFromExpr` (which re-executes `buildGroupedView`) and indexed; the
  * synthetic `GroupTerm.inline$underlying` accessor (emitted by `view.<name>.count` etc.) unwraps the projected `GroupedColumn` /
  * `UngroupedView` to its underlying `Column`.
  *
  * Placement in kyo-sql lets this file reference `Column`, `GroupedColumn`, `UngroupedView` directly, zero reflection.
  *
  * Summon reachability: these givens are NOT in any companion's implicit scope. Call sites that invoke `FromExpr.derived` for AST nodes
  * carrying column terms MUST import them via `import kyo.internal.ColumnFromExpr.given`.
  */
object ColumnFromExpr:

    /** `FromExpr[Column[?, ?]]`, recognises both the `Column.apply` constructor and the `selectDynamic` projection chain. */
    given fromExprColumn[N <: String & Singleton, V]: scala.quoted.FromExpr[Column[N, V]] with
        def unapply(x: Expr[Column[N, V]])(using q: Quotes): Option[Column[N, V]] =
            // `Column` is phantom in `N` / `V` at runtime; the walker produces `Column[String, Any]`.
            MacroSupport.narrowOption(new Walk[q.type].column(x))
    end fromExprColumn

    /** `FromExpr[GroupedColumn[?, ?]]`, a grouped column projected out of the post-`groupBy` view Record. */
    given fromExprGroupedColumn[N <: String & Singleton, V]: scala.quoted.FromExpr[GroupedColumn[N, V]] with
        def unapply(x: Expr[GroupedColumn[N, V]])(using q: Quotes): Option[GroupedColumn[N, V]] =
            // `GroupedColumn` is phantom in `N` / `V` at runtime.
            MacroSupport.narrowOption(new Walk[q.type].groupTermValue(x).collect {
                case gc: GroupedColumn[?, ?] => gc
            })
    end fromExprGroupedColumn

    /** `FromExpr[UngroupedView[?]]`, a non-key column projected out of the post-`groupBy` view Record. */
    given fromExprUngroupedView[V]: scala.quoted.FromExpr[UngroupedView[V]] with
        def unapply(x: Expr[UngroupedView[V]])(using q: Quotes): Option[UngroupedView[V]] =
            // `UngroupedView` is phantom in `V` at runtime.
            MacroSupport.narrowOption(new Walk[q.type].groupTermValue(x).collect {
                case uv: UngroupedView[?] => uv
            })
    end fromExprUngroupedView

    /** `FromExpr[FrameBound]`, lifts all five `FrameBound` variants.
      *
      * `FrameBound` is a sealed trait with case objects (`UnboundedPreceding`, `UnboundedFollowing`, `CurrentRow`) and case classes
      * (`Preceding(Term[Int])`, `Following(Term[Int])`). The `unapply` delegates to a freshly derived `FromExpr[FrameBound]` built at
      * macro-expansion time (inside `unapply`), so that `fromExprColumn` and other column-projection givens imported by the call site
      * (`SqlStaticMacro.impl`, `SqlRunMacro`) are in scope during derivation.
      */
    given fromExprFrameBound: scala.quoted.FromExpr[FrameBound] with
        def unapply(x: Expr[FrameBound])(using Quotes): Option[FrameBound] =
            import kyo.SqlSchema.given
            kyo.FromExpr.derived[FrameBound].unapply(x)
    end fromExprFrameBound

    /** `FromExpr[WindowFrame]`, lifts `WindowFrame(kind, start, end)`.
      *
      * Derives at macro-expansion time so that `fromExprFrameBound` (defined above) is in scope, ensuring `FrameBound`-typed fields lift
      * correctly. `kyo.SqlSchema.given` is needed because `Term[?]` subtypes carry `SqlSchema` fields.
      */
    given fromExprWindowFrame: scala.quoted.FromExpr[WindowFrame] with
        def unapply(x: Expr[WindowFrame])(using Quotes): Option[WindowFrame] =
            import kyo.SqlSchema.given
            kyo.FromExpr.derived[WindowFrame].unapply(x)
    end fromExprWindowFrame

    /** `FromExpr[WindowSpec]`, lifts `WindowSpec(partitionBy, orderBy, frame)`.
      *
      * `partitionBy: Chunk[Term[?]]` and `orderBy: Chunk[OrderSpec]` lift via the Chunk derivation; `frame: Maybe[WindowFrame]` lifts via
      * `fromExprWindowFrame` (above). Column projection trees inside the partition / order fields lift via `fromExprColumn`.
      */
    given fromExprWindowSpec: scala.quoted.FromExpr[WindowSpec] with
        def unapply(x: Expr[WindowSpec])(using Quotes): Option[WindowSpec] =
            import kyo.SqlSchema.given
            kyo.FromExpr.derived[WindowSpec].unapply(x)
    end fromExprWindowSpec

    /** `FromExpr[WindowSpec.Builder]`, lifts `new WindowSpec.Builder(partitions, orderings, frameOpt)` constructor applies.
      *
      * After the Phase 8 fix to `WindowSpec.Builder`'s builder methods (explicit `new WindowSpec.Builder(...)` constructors replacing
      * `.copy(...)` with `Chunk.from` / `:+`), the inlined call-site trees are plain constructor Apply nodes that `kyo.FromExpr.derived`'s
      * product-case walker handles. `fromExprWindowFrame` (above) is in scope for the `frameOpt: Maybe[WindowFrame]` field.
      */
    given fromExprWindowSpecBuilder: scala.quoted.FromExpr[WindowSpec.Builder] with
        def unapply(x: Expr[WindowSpec.Builder])(using Quotes): Option[WindowSpec.Builder] =
            import kyo.SqlSchema.given
            kyo.FromExpr.derived[WindowSpec.Builder].unapply(x)
    end fromExprWindowSpecBuilder

    /** `FromExpr[Cast[?, ?]]`, `Column.cast[B]` expands to `Cast(expr, summon[SqlSchema[B]].sqlTypeName)`; the `sqlTypeName` argument is a
      * method call on a `SqlSchema` given, NOT a string literal, so the stdlib `FromExpr[String]` cannot lift it. This given resolves the
      * `SqlSchema` reference and re-invokes `sqlTypeName` at macro-expansion time.
      */
    given fromExprCast[A, B]: scala.quoted.FromExpr[Cast[A, B]] with
        def unapply(x: Expr[Cast[A, B]])(using q: Quotes): Option[Cast[A, B]] =
            // `Cast` is phantom in `A` / `B` at runtime; the walker produces `Cast[Any, Any]`.
            MacroSupport.narrowOption(new Walk[q.type].cast(x))
    end fromExprCast

    /** Per-invocation walker. Parameterised on the singleton `Quotes` instance (the proven `SqlStaticMacro.R` pattern) so `q.reflect.Term`
      * is a well-formed type in every method signature. One instance per `unapply` call so concurrent macro expansion is safe.
      */
    private class Walk[Q <: Quotes & Singleton](using val q: Q):
        import q.reflect.*
        given CanEqual[String, String] = CanEqual.derived

        // --- Column ---

        /** Reconstructs a `Column` value from either a constructor `Apply` or a `selectDynamic` projection chain. */
        def column(x: Expr[?]): Option[Column[?, ?]] =
            val t = unwrap(kyo.internal.FromExprDerived.resolveBindings(x.asTerm))
            t match
                // `Column[N, V](alias, name, sqlName)`, direct constructor (three-arg form after Phase 3).
                case Apply(TypeApply(Select(qual, "apply"), _), List(aliasE, nameE, sqlNameE))
                    if qual.symbol.name == "Column" || qual.symbol.name == "Column$" =>
                    for
                        alias   <- strLit(aliasE)
                        name    <- strLit(nameE)
                        sqlName <- strLit(sqlNameE)
                    yield Column[String & scala.Singleton, Any](alias, name.asInstanceOf[String & scala.Singleton], sqlName)
                case Apply(Select(qual, "apply"), List(aliasE, nameE, sqlNameE))
                    if qual.symbol.name == "Column" || qual.symbol.name == "Column$" =>
                    for
                        alias   <- strLit(aliasE)
                        name    <- strLit(nameE)
                        sqlName <- strLit(sqlNameE)
                    yield Column[String & scala.Singleton, Any](alias, name.asInstanceOf[String & scala.Singleton], sqlName)
                // `new Column[N, V](alias, name, sqlName)`, constructor form.
                case Apply(TypeApply(Select(New(_), "<init>"), _), List(aliasE, nameE, sqlNameE)) =>
                    for
                        alias   <- strLit(aliasE)
                        name    <- strLit(nameE)
                        sqlName <- strLit(sqlNameE)
                    yield Column[String & scala.Singleton, Any](alias, name.asInstanceOf[String & scala.Singleton], sqlName)
                // `<groupTerm>.kyo$SqlAst$GroupTerm$$inline$underlying`, the synthetic accessor emitted
                // by `view.<name>.sum/avg/min/max/count`; the receiver is a GroupedColumn / UngroupedView
                // projected out of the post-`groupBy` view. Unwrap it to its underlying `Column`.
                case Select(recv, accessor) if accessor.endsWith("inline$underlying") || accessor == "underlying" =>
                    projectedValue(recv).collect {
                        case GroupedColumn(c) => c
                        case UngroupedView(c) => c
                        case c: Column[?, ?]  => c
                    }
                // `<record>.<alias>.<name>` projection, go through Record reconstruction so the stored
                // `sqlName` field (which may differ from `name` when naming transforms are applied) is
                // recovered correctly. Fall back to `Column(alias, name, name)` only when Record
                // reconstruction fails (no transform applied, sqlName == name is safe).
                //
                // A single-key projection `<record>.<name>` arises from the INSERT-columns Record, which
                // is flat (`buildRowColumns` produces `Record[name ~ Column]` directly, no alias wrapper)
                // its values ARE `Column`s, so the bottom Record is reconstructed and indexed by the
                // collected key to recover the `Column` value.
                case other =>
                    projectionKeys(other) match
                        case alias :: name :: Nil =>
                            projectedValue(other).collect { case c: Column[?, ?] => c }
                                .orElse(Some(Column[String & scala.Singleton, Any](
                                    alias,
                                    name.asInstanceOf[String & scala.Singleton],
                                    name.asInstanceOf[String & scala.Singleton]
                                )))
                        case _ :: Nil =>
                            projectedValue(other).collect { case c: Column[?, ?] => c }
                        case _ => None
            end match
        end column

        // --- Cast ---

        /** Reconstructs a `Cast` value from `Cast.apply(expr, <schema>.sqlTypeName)`. The `sqlTypeName` argument is resolved by recovering
          * the `SqlSchema` reference and re-invoking the `sqlTypeName` extension at macro-expansion time.
          */
        def cast(x: Expr[?]): Option[Cast[?, ?]] =
            unwrap(kyo.internal.FromExprDerived.resolveBindings(x.asTerm)) match
                case Apply(TypeApply(Select(qual, "apply"), _), List(exprE, typeNameE))
                    if qual.symbol.name == "Cast" || qual.symbol.name == "Cast$" =>
                    buildCast(exprE, typeNameE)
                case Apply(Select(qual, "apply"), List(exprE, typeNameE))
                    if qual.symbol.name == "Cast" || qual.symbol.name == "Cast$" =>
                    buildCast(exprE, typeNameE)
                case Apply(TypeApply(Select(New(_), "<init>"), _), List(exprE, typeNameE)) =>
                    buildCast(exprE, typeNameE)
                case _ => None

        private def buildCast(exprE: Term, typeNameE: Term): Option[Cast[?, ?]] =
            // `import quotes.reflect.*` shadows `kyo.SqlAst.Term`; qualify it.
            given scala.quoted.FromExpr[SqlAst.Term[?]] = kyo.FromExpr.derived
            for
                inner    <- summon[scala.quoted.FromExpr[SqlAst.Term[?]]].unapply(exprE.asExprOf[SqlAst.Term[?]])
                typeName <- sqlTypeNameOf(typeNameE)
            // `Cast` is phantom in `A`; the lifted `inner` is a `Term[?]`, refine to `Term[Any]`.
            // Safe: SqlAst.Term is phantom in its type parameter; Term[?] and Term[Any] are the same class.
            yield Cast[Any, Any](MacroSupport.narrowPhantom[SqlAst.Term[Any]](inner), typeName)
            end for
        end buildCast

        /** Resolves the `String` value of a `<schema>.sqlTypeName` term, `<schema>` is a stable `SqlSchema` given; the `sqlTypeName`
          * extension is re-invoked at macro-expansion time. Also accepts a direct string literal.
          */
        private def sqlTypeNameOf(t: Term): Option[String] =
            unwrap(t) match
                case Literal(StringConstant(s)) => Some(s)
                case sel @ Select(recv, "sqlTypeName") =>
                    schemaOf(recv).map(s => s.sqlTypeName)
                // The `sqlTypeName` extension surfaces as `SqlSchema.sqlTypeName(<schema>)`.
                case Apply(Select(_, "sqlTypeName"), List(recv)) =>
                    schemaOf(recv).map(s => s.sqlTypeName)
                case Apply(TypeApply(Select(_, "sqlTypeName"), _), List(recv)) =>
                    schemaOf(recv).map(s => s.sqlTypeName)
                case _ => None

        /** Recovers a `SqlSchema[?]` value from a term referencing a stable `SqlSchema` given. */
        private def schemaOf(t: Term): Option[SqlSchema[Any]] =
            kyo.internal.FromExprDerived.resolveStableGiven[SqlSchema[Any]](MacroSupport.expectExpr[SqlSchema[Any]](unwrap(t).asExpr))

        // --- Grouped view ---

        /** Reconstructs a `GroupedColumn` / `UngroupedView` projected out of the post-`groupBy` view Record. The view is FLAT
          * (`buildGroupedView` unwraps the source's alias wrapper), so `view.<name>` is a single-key projection, the view `Record` must be
          * reconstructed to recover the wrapped value (and its source alias).
          */
        def groupTermValue(x: Expr[?]): Option[Any] =
            projectedValue(unwrap(kyo.internal.FromExprDerived.resolveBindings(x.asTerm)))

        /** Reconstructs the value a projection chain projects: lifts the bottom receiver `Record` and indexes it by the collected keys.
          * Also unwraps the synthetic `GroupTerm.inline$underlying` accessor.
          */
        private def projectedValue(t: Term): Option[Any] =
            unwrap(t) match
                // `<groupTerm>.kyo$SqlAst$GroupTerm$$inline$underlying`, receiver is a GroupedColumn / UngroupedView.
                case Select(recv, accessor) if accessor.endsWith("inline$underlying") || accessor == "underlying" =>
                    projectedValue(recv).collect {
                        case GroupedColumn(c) => c
                        case UngroupedView(c) => c
                    }
                case Select(recv, "column") =>
                    projectedValue(recv).collect { case GroupedColumn(c) => c }
                case other =>
                    projectChain(other) match
                        case Some((keys, bottom)) if keys.nonEmpty =>
                            var cur: Option[Any] = liftRecord(bottom)
                            for k <- keys do
                                cur = cur.collect { case r: Record[?] => r }.flatMap(_.dict.toMap.get(k))
                            cur
                        case _ => None

        /** Lifts a term that evaluates to a `Record[?]` via `RecordFromExpr` (handles `buildColumns` / `&` / `buildGroupedView`). */
        private def liftRecord(t: Term): Option[Record[?]] =
            try RecordFromExpr.fromExprRecord[Any].unapply(MacroSupport.expectExpr[Record[Any]](t.asExpr))
            catch
                case _: scala.MatchError => None // expected: tree shape not a Record literal
                case e: ClassCastException =>
                    report.warning(s"liftRecord shape mismatch: ${e.getMessage}")
                    None
                case scala.util.control.NonFatal(e) => throw e // surface the real bug

        // --- Projection-chain key collection ---

        /** Collects the literal key segments of a `selectDynamic` projection chain, left-to-right. Each step is
          * `Dict.apply(<receiver>.inline$dict, "key")`; recursion bottoms out at the receiver `Record` expression. Returns `Nil` when `t`
          * is not a projection chain.
          */
        private def projectionKeys(t: Term): List[String] =
            projectChain(t).map(_._1).getOrElse(Nil)

        /** Collects `(keys, bottomReceiver)` of a `selectDynamic` projection chain. `keys` is left-to-right; `bottomReceiver` is the
          * innermost `Record`-producing term the chain projects out of. Returns `None` when `t` is not a projection chain.
          */
        private def projectChain(t: Term): Option[(List[String], Term)] =
            @tailrec
            def loop(cur: Term, acc: List[String]): Option[(List[String], Term)] =
                unwrap(cur) match
                    case Apply(
                            Apply(TypeApply(Select(dictQual, "apply"), _), List(Select(recv, "inline$dict"))),
                            List(keyE)
                        ) if dictQual.symbol.name == "Dict" || dictQual.symbol.name == "Dict$" =>
                        strLit(keyE) match
                            case Some(k) => loop(recv, k :: acc)
                            case None    => None
                    case bottom =>
                        if acc.isEmpty then None else Some((acc, bottom))
            loop(t, Nil)
        end projectChain

        // --- Generic utilities ---

        // `resolveBindings` has already substituted every `val` binding, so any `Inlined` / `Block`
        // statement list is dead, strip the wrapper regardless of its (now-unused) bindings.
        @tailrec
        private def unwrap(t: Term): Term =
            t match
                case Inlined(_, _, inner) => unwrap(inner)
                case Block(_, inner)      => unwrap(inner)
                case Typed(inner, _)      => unwrap(inner)
                case TypeApply(Select(inner, "asInstanceOf" | "$asInstanceOf$"), _) =>
                    unwrap(inner)
                case Apply(TypeApply(Select(_, "substituteCo" | "substituteContra"), _), List(i)) =>
                    unwrap(i)
                case other => other

        private def strLit(t: Term): Option[String] =
            unwrap(t) match
                case Literal(StringConstant(s)) => Some(s)
                case _                          => None

    end Walk

end ColumnFromExpr
