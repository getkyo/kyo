package kyo.internal

import kyo.*
import kyo.Sql.*
import scala.compiletime.constValue
import scala.compiletime.erasedValue
import scala.compiletime.summonFrom

/** Macro-facing helpers for [[kyo.Sql]].
  *
  * These live outside [[kyo.Sql]] because they are `transparent inline` scaffolding invoked by the DSL entry points
  * ([[kyo.Sql.from]], [[kyo.Sql.insert]], [[kyo.Sql.update]], [[kyo.Sql.delete]]) and by macro-emitted code, not user-facing surface.
  * The helpers here are pure scaffolding whose call sites expand at macro-expansion time and never appear in inferred user types.
  */
private[kyo] object SqlAstInternal:

    /** Per-field SQL-name resolver used by `buildColumns` / `buildRowColumns` to populate `Column.sqlName`.
      *
      * Delegates to runtime `SqlNameResolver.columnName[T]`, passing the type's lifted `@column` pairs and the in-scope
      * [[kyo.SqlNaming]]. The result is a runtime call rather than a literal, and the static-SQL lift folds it back:
      * `RecordFromExpr.sqlNameValue` matches the single `SqlNameResolver.columnName[T](scalaName, renames, naming)` shape emitted here,
      * re-reads the renames from `T`'s own `@column` annotations, and applies the casing itself. A casing given that is not
      * statically resolvable is the one input that does not fold: the column then does not lift, so the query renders at run time rather
      * than folding an un-cased name.
      *
      * Why this isn't a macro that constant-folds: the per-field name singleton lives behind a polyfunction parameter
      * `[n <: String & Singleton, v]` passed to `Record.stageNamed`. Macro expansion fires at polyfunction-body type-check time (with
      * `n` still abstract), not at per-field substitution time inside `stageNamedLoop`. Without a Scala 3 mechanism to defer macro
      * expansion past polyfunction substitution, we can't constant-fold the resolved name from this site.
      */
    private[kyo] inline def resolveSqlName[T](scalaName: String): String =
        // The SqlNaming summon is inlined AS the argument rather than bound to a `val` first: a leading `val` would make the
        // emitted `SqlNameResolver.columnName(...)` a Block rather than a bare Apply, and the static-lift reconstruction
        // (`RecordFromExpr`) matches only the Apply shape, so a `val` silently breaks the fold for every query's columns.
        kyo.internal.SqlNameResolver.columnName[T](
            scalaName,
            kyo.internal.SqlMacros.sqlRenames[T],
            summonFrom {
                case n: SqlNaming => Maybe(n)
                case _            => Maybe.empty
            }
        )
    end resolveSqlName

    /** Builds the per-field column dictionary the two entry points below wrap in a `Record`.
      *
      * Written as an inline recursion over the field tuple rather than through `Record.stageNamed`, whose
      * API is a staging function. The compiler reduces the per-field application of such a function by
      * binding its type parameters and its argument, and the inline chain above then moves those bindings
      * without re-owning them. Every `.run` on a query reads this tree back through `FromExprDerived`, and
      * reading a tree with a mis-owned definition in it fails under `-Xcheck-macros`. Constructing the
      * columns directly leaves no definitions in the tree to be moved, and the static-SQL lift reads
      * `Column.apply` straight off it rather than beta-reducing a closure first.
      */
    private[kyo] inline def columnsDict[T, Fs <: Tuple](alias: String): Dict[String, Any] =
        inline erasedValue[Fs] match
            case _: EmptyTuple        => Dict.empty[String, Any]
            case _: ((n ~ v) *: rest) =>
                // The name is spelled out at each use rather than bound to a val: `constValue` folds to the
                // same literal at each, and a binding here is a definition the inline chain above would
                // move without re-owning, which is the whole point of building the columns this way.
                columnsDict[T, rest](alias) ++
                    Dict[String, Any](
                        constValue[n & String & Singleton] ->
                            Column[n & String & Singleton, v](
                                alias,
                                constValue[n & String & Singleton],
                                resolveSqlName[T](constValue[n & String & Singleton])
                            )
                    )

    transparent inline def buildColumns[T, N <: String & Singleton](alias: N)(using f: Fields[T]) =
        alias ~ new Record(columnsDict[T, f.AsTuple](alias)).asInstanceOf[Record[f.MapNamed[Column]]]

    transparent inline def buildRowColumns[T](using f: Fields[T]) =
        new Record(columnsDict[T, f.AsTuple]("")).asInstanceOf[Record[f.MapNamed[Column]]]

end SqlAstInternal
