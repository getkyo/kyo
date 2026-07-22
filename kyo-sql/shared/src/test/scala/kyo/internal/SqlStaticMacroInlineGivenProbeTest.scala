package kyo.internal

import kyo.*
import kyo.Test
import scala.compiletime.testing.typeCheckErrors

/** Documents the static-fold contract for `inline given SqlSchema[T]` under `.runStatic`:
  *
  *   - Plain `SqlSchema.derived` (no overrides applied): folds under `.runStatic`, see leaf 1.
  *   - With override transforms (`.withNaming` / `.withTableName` / `.rename`): does NOT fold per-column `sqlName` under `.runStatic`.
  *     [[kyo.internal.SqlStaticMacro.emitOpaqueCauses]] produces a positioned error directing the user to `.run` (the runtime path
  *     applies the strategy correctly).
  *
  * Why the override case can't fold: the per-column `Column.sqlName` is resolved by [[kyo.internal.SqlAstInternal.resolveSqlName]] inside the
  * polyfunction body that `Record.stageNamed` accepts. Macros expand at polyfunction-body type-check time (with `n` still abstract),
  * not at per-field substitution time inside `stageNamedLoop`. Without a Scala 3 mechanism to defer macro expansion past polyfunction
  * substitution, we can't constant-fold the per-column resolved name from this site. The runtime path (`.render` / `.run`) works
  * correctly because `SqlNameResolver.columnName` reads the schema's overrides at runtime.
  */
class SqlStaticMacroInlineGivenProbeTest extends Test:

    case class ProbeRowA(id: Long, name: String) derives Schema
    inline given inlineSqlSchemaProbeRowA: SqlSchema[ProbeRowA] = SqlSchema.derived

    "leaf 1: inline given (no overrides) lets .runStatic fold; renderPostgres emits verbatim names" in {
        // Verify the runtime renderer emits verbatim names for the same shape.
        val rp = Sql.from[ProbeRowA]("u").select(c => c.u.name).renderPostgres
        assert(rp.sql == """SELECT "u"."name" FROM "proberowa" "u"""")

        // Verify the static-fold path compiles for the same shape (no runtime execution, just type-check
        // that the .runStatic macro splice succeeds under the inline given).
        def shape(using Frame): Chunk[String] < (Async & Abort[SqlException] & Scope) =
            Sql.from[ProbeRowA]("u").select(c => c.u.name).runStatic
        succeed
    }

    "leaf 2: inline given with .withNaming(snakeCase) does NOT fold under .runStatic; reports positioned error" in {
        val errors = typeCheckErrors(
            """
            import kyo.*
            case class ProbeRowB(id: Long, firstName: String) derives Schema
            inline given inlineSqlSchemaProbeRowB: SqlSchema[ProbeRowB] =
                SqlSchema.derived[ProbeRowB].withNaming(SqlSchema.Naming.snakeCase)
            def shape(using Frame): Chunk[String] < (Async & Abort[SqlException] & Scope) =
                Sql.from[ProbeRowB]("u").select(c => c.u.firstName).runStatic
            """
        )
        assert(errors.nonEmpty, "expected a positioned compile error")
        val texts = errors.map(_.message).mkString("\n---\n")
        assert(
            texts.contains("inline given") && texts.contains("cannot be folded"),
            s"expected the runStatic walker to direct user to `inline given`, got:\n$texts"
        )
    }

end SqlStaticMacroInlineGivenProbeTest
