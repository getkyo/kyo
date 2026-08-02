package kyo.internal

import kyo.*
import kyo.Sql.*
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

    transparent inline def buildColumns[T, N <: String & Singleton](alias: N)(using Fields[T]) =
        alias ~ Record.stageNamed[T] {
            [n <: String & Singleton, v] =>
                (g: Field[n, v]) =>
                    Column[n & String, v](alias, g.name, resolveSqlName[T](g.name))
        }

    transparent inline def buildRowColumns[T](using Fields[T]) =
        Record.stageNamed[T] {
            [n <: String & Singleton, v] =>
                (g: Field[n, v]) =>
                    Column[n & String, v]("", g.name, resolveSqlName[T](g.name))
        }

end SqlAstInternal
