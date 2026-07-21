package kyo

import scala.compiletime.testing.typeCheckErrors

/** Documents the static-SQL contract for `inline given SqlSchema[T]`:
  *
  *   - Plain `SqlSchema.derived` (no overrides applied): folds under `staticSql`, see leaf 1.
  *   - With override transforms (`.withNaming` / `.withTableName` / `.rename`): does NOT fold per-column `sqlName` under `staticSql`.
  *     [[kyo.internal.SqlStaticMacro.emitOpaqueCauses]] produces a positioned error directing the user to `.run`/`.render`.
  *
  * Why the override case can't fold: the per-column `Column.sqlName` is resolved by [[kyo.SqlAst.internal.resolveSqlName]] inside the
  * polyfunction body that `Record.stageNamed` accepts. Macros expand at polyfunction-body type-check time (with `n` still abstract),
  * not at per-field substitution time inside `stageNamedLoop`. Without a Scala 3 mechanism to defer macro expansion past polyfunction
  * substitution, we can't constant-fold the per-column resolved name from this site. The runtime path (`.render` / `.run`) works
  * correctly because `SqlNameResolver.columnName` reads the schema's overrides at runtime.
  */
class SqlStaticInlineGivenProbeTest extends Test:

    case class ProbeRowA(id: Long, name: String) derives Schema
    inline given inlineSqlSchemaProbeRowA: SqlSchema[ProbeRowA] = SqlSchema.derived

    "leaf 1: inline given (no overrides) lets staticSql fold and emit verbatim names" in {
        val r = SqlStatic.staticSql(Sql.from[ProbeRowA]("u").select(c => c.u.name))
        assert(r.sql.postgres == """SELECT "u"."name" FROM "proberowa" "u"""")
    }

    "leaf 2: inline given with .withNaming(snakeCase) does NOT fold under staticSql; reports positioned error" in {
        val errors = typeCheckErrors(
            """
            import kyo.*
            case class ProbeRowB(id: Long, firstName: String) derives Schema
            inline given inlineSqlSchemaProbeRowB: SqlSchema[ProbeRowB] =
                SqlSchema.derived[ProbeRowB].withNaming(SqlSchema.Naming.snakeCase)
            SqlStatic.staticSql(Sql.from[ProbeRowB]("u").select(c => c.u.firstName))
            """
        )
        assert(errors.nonEmpty, "expected a positioned compile error")
        val texts = errors.map(_.message).mkString("\n---\n")
        assert(
            texts.contains("inline given") && texts.contains("cannot be folded"),
            s"expected the runStatic walker to direct user to `inline given`, got:\n$texts"
        )
    }

end SqlStaticInlineGivenProbeTest
