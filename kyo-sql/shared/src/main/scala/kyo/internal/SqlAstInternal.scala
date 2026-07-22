package kyo.internal

import kyo.*
import kyo.SqlAst.*
import scala.compiletime.summonFrom

/** Macro-facing helpers for [[kyo.SqlAst]].
  *
  * These live outside [[kyo.SqlAst]] because they are `transparent inline` scaffolding invoked by the DSL entry points
  * ([[kyo.Sql.from]], [[kyo.Sql.insert]], [[kyo.Sql.update]], [[kyo.Sql.delete]]) and by macro-emitted code, not user-facing surface.
  * User-visible type aliases (e.g. [[kyo.SqlAst.RecordF]]) that used to sit in `SqlAst.internal` alongside them have been promoted to
  * the [[kyo.SqlAst]] top level; the ones here are pure scaffolding whose call sites expand at macro-expansion time and never appear in
  * inferred user types.
  */
private[kyo] object SqlAstInternal:

    /** Per-field SQL-name resolver used by `buildColumns` / `buildRowColumns` to populate `Column.sqlName`.
      *
      * Uses `summonFrom` to find the `SqlSchema[T]` in scope and delegates to runtime `SqlNameResolver.columnName`. The result is a
      * runtime call (not a literal), so `Column.sqlName` is opaque to the static-SQL macro's lift step.
      * `SqlStaticMacro.emitOpaqueCauses` detects this pattern and produces a positioned error directing the user to `.run` for queries
      * whose columns participate in `.runStatic` and have schema-driven name overrides.
      *
      * Why this isn't a macro that constant-folds: the per-field name singleton lives behind a polyfunction parameter
      * `[n <: String & Singleton, v]` passed to `Record.stageNamed`. Macro expansion fires at polyfunction-body type-check time (with
      * `n` still abstract), not at per-field substitution time inside `stageNamedLoop`. Without a Scala 3 mechanism to defer macro
      * expansion past polyfunction substitution, we can't constant-fold the resolved name from this site.
      */
    private[kyo] inline def resolveSqlName[T](scalaName: String): String = summonFrom {
        case s: SqlSchema[T] => kyo.internal.SqlNameResolver.columnName(scalaName, s)
        case _               => scalaName
    }

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
