package kyo.internal.macros

import kyo.*
import kyo.Sql.*
import scala.annotation.tailrec
import scala.quoted.*

/** Custom `FromExpr` instances for AST `Term` leaves that arise from `Record` field projections rather than direct case-class construction.
  *
  * `FromExpr.derived` reconstructs values by walking constructor `Apply` trees. A column reference written as `c.p.age` in DSL code does
  * NOT expand to a `Column.apply(...)` constructor, it is a `selectDynamic` projection chain of nested `<record>.selectDynamic("key")`
  * calls (an inline field accessor expands each step to `Dict.apply(<record>.inline$dict, "key")` instead). The derived product/sum
  * walker has no arm for that shape, so every WHERE / SELECT /
  * JOIN / groupBy query that references a column fails to lift, and a zero-arg AST case class is the hazard that makes the failure silent
  * rather than loud: the sum walker tries each child in turn and a zero-arg product matches a projection-shaped tree. The only such node is
  * `SetValue.Default()`, which is not a `Term` and so is a candidate in an assignment position alone; every `Term` position is free of it.
  *
  * `fromExprColumn` collects the literal key segments of the
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
  * carrying column terms MUST import them via `import kyo.internal.macros.ColumnFromExpr.given`.
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

    /** `FromExpr[Cast[?, ?]]`. `Term.cast[B]` expands to `Cast(expr, summon[SqlType[B]].columnType)`; the target argument is a reference to
      * a summoned `SqlType` given, for which no `FromExpr` exists, so this given resolves that reference to the given's [[SqlType.Type]] at
      * macro-expansion time.
      */
    given fromExprCast[A, B]: scala.quoted.FromExpr[Cast[A, B]] with
        def unapply(x: Expr[Cast[A, B]])(using q: Quotes): Option[Cast[A, B]] =
            // `Cast` is phantom in `A` / `B` at runtime; the walker produces `Cast[Any, Any]`.
            MacroSupport.narrowOption(new Walk[q.type].cast(x))
    end fromExprCast

    /** Per-invocation walker. Parameterised on the singleton `Quotes` instance, the same shape `SqlStaticMacro.R` carries, so
      * `q.reflect.Term` is a well-formed type in every method signature. One instance per `unapply` call so concurrent macro expansion is safe.
      */
    private class Walk[Q <: Quotes & Singleton](using val q: Q):
        import q.reflect.*
        given CanEqual[String, String] = CanEqual.derived

        // --- Column ---

        /** Reconstructs a `Column` value from either a constructor `Apply` or a `selectDynamic` projection chain. */
        def column(x: Expr[?]): Option[Column[?, ?]] =
            val t = unwrap(kyo.internal.FromExprDerived.resolveBindings(x.asTerm))
            t match
                // `Column[N, V](alias, name, sqlName)`, direct constructor (three-arg form).
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
                // `<groupTerm>.kyo$Sql$GroupTerm$$inline$underlying`, the synthetic accessor emitted
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

        /** Reconstructs a `Cast` value from `Cast.apply(expr, <target>)`, resolving the target `SqlType` given reference to its
          * [[SqlType.Type]] at macro-expansion time.
          */
        def cast(x: Expr[?]): Option[Cast[?, ?]] =
            unwrap(kyo.internal.FromExprDerived.resolveBindings(x.asTerm)) match
                case Apply(TypeApply(Select(qual, "apply"), _), List(exprE, targetE))
                    if qual.symbol.name == "Cast" || qual.symbol.name == "Cast$" =>
                    buildCast(exprE, targetE)
                case Apply(Select(qual, "apply"), List(exprE, targetE))
                    if qual.symbol.name == "Cast" || qual.symbol.name == "Cast$" =>
                    buildCast(exprE, targetE)
                case Apply(TypeApply(Select(New(_), "<init>"), _), List(exprE, targetE)) =>
                    buildCast(exprE, targetE)
                case _ => None

        private def buildCast(exprE: Term, targetE: Term): Option[Cast[?, ?]] =
            // `import quotes.reflect.*` shadows `kyo.Sql.Term`; qualify it.
            import kyo.internal.macros.BindFromExpr.given
            given scala.quoted.FromExpr[Sql.Term[?]] = kyo.FromExpr.derived
            for
                inner  <- summon[scala.quoted.FromExpr[Sql.Term[?]]].unapply(exprE.asExprOf[Sql.Term[?]])
                target <- sqlTypeOf(targetE)
            // `Cast` is phantom in `A`; the lifted `inner` is a `Term[?]`, refine to `Term[Any]`.
            // Safe: Sql.Term is phantom in its type parameter; Term[?] and Term[Any] are the same class.
            yield Cast[Any, Any](MacroSupport.narrowPhantom[Sql.Term[Any]](inner), target)
            end for
        end buildCast

        /** Resolves the `.cast` target: a reference to a stable `SqlType` given, whose portable [[SqlType.Type]] value the render needs.
          * `.cast[B]` inlines to `Cast(expr, summon[SqlType[B]].columnType)`, and `columnType` is an identity unwrap, so the target arg is a
          * reference to the `SqlType` given; `resolveStableGiven` reads its value and `columnType` narrows the opaque wrapper to the enum.
          */
        private def sqlTypeOf(t: Term): Option[SqlType.Type] =
            kyo.internal.FromExprDerived.resolveStableGiven[SqlType[Any]](
                MacroSupport.expectExpr[SqlType[Any]](unwrap(t).asExpr)
            ).map(_.columnType)

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
                // `<groupTerm>.kyo$Sql$GroupTerm$$inline$underlying`, receiver is a GroupedColumn / UngroupedView.
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

        /** Collects the literal key segments of a `selectDynamic` projection chain, left-to-right. Each step is a
          * `<receiver>.selectDynamic("key")` call, or its inline-expanded `Dict.apply(<receiver>.inline$dict, "key")` form; recursion
          * bottoms out at the receiver `Record` expression. Returns `Nil` when `t` is not a projection chain.
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
                    // A field-access step as a bare `selectDynamic` / `getField` call: `<recv>.selectDynamic("key")`
                    // carrying the field-name literal and the `Fields.Have` given. Collect the name and recurse into the receiver.
                    case Apply(Apply(TypeApply(Select(recv, "selectDynamic" | "getField"), _), List(keyE)), _) =>
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
