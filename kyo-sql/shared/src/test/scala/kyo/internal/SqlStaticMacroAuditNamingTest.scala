package kyo.internal

import kyo.*
import kyo.Test
import scala.compiletime.testing.typeCheckErrors

/** Contract audit for [[kyo.internal.SqlStaticMacro]] — naming-strategy scenarios (.withNaming and custom Naming).
  *
  * Companion to [[SqlStaticMacroAuditTest]] / [[SqlStaticMacroAuditSourceShapeTest]]; see the parent audit for the split rationale.
  */
class SqlStaticMacroAuditNamingTest extends Test:

    // -----------------------------------------------------------------------
    // Scenario C, Inline given with .withNaming(snakeCase) for the table type.
    // EXPECTED: NEGATIVE-GOOD. Any override chain (.withNaming / .withTableName / .rename)
    // can't fold per-column `sqlName` because `resolveSqlName` runs inside the polyfunction
    // body that `Record.stageNamed` accepts, and the macro can't expand past that
    // substitution. The macro must surface a positioned error directing the user to `.run`
    // (the runtime path applies the strategy correctly).
    // -----------------------------------------------------------------------
    "C: inline given with .withNaming reports positioned error" in {
        val errors = typeCheckErrors(
            """
            import kyo.*
            import kyo.SqlAst.*
            case class AuditUserCRow(id: Long, firstName: String) derives Schema
            inline given SqlSchema[AuditUserCRow] =
                SqlSchema.derived[AuditUserCRow].withNaming(SqlSchema.Naming.snakeCase)
            SqlStaticProbe.render(Sql.from[AuditUserCRow]("u").select(c => c.u.firstName))
            """
        )
        assert(errors.nonEmpty, "expected a positioned compile error for override chain")
        val texts = errors.map(_.message).mkString("\n---\n")
        assert(
            texts.contains("inline given") && texts.contains("cannot be folded"),
            s"expected the walker to direct user to `inline given`, got:\n$texts"
        )
    }

    // -----------------------------------------------------------------------
    // Scenario F, Custom SqlSchema.Naming (not snakeCase / identity) via FromExpr[SqlSchema.Naming].
    // EXPECTED: NEGATIVE-GENERIC initially. The FromExpr only matches the
    //   two known strategies; a custom one would fail to lift.
    // -----------------------------------------------------------------------
    "F, custom SqlSchema.Naming via inline given produces a compile error" in {
        val errors = typeCheckErrors(
            """
            import kyo.*
            import kyo.SqlAst.*
            case class AuditUserF(id: Long, firstName: String) derives Schema
            val custom: SqlSchema.Naming = new SqlSchema.Naming:
                def tableName(scalaName: String): String  = scalaName + "_custom"
                def columnName(scalaName: String): String = scalaName + "_c"
            inline given SqlSchema[AuditUserF] = SqlSchema.derived[AuditUserF].withNaming(custom)
            SqlStaticProbe.render(Sql.from[AuditUserF]("u").select(c => c.u.firstName))
            """
        )
        assert(errors.nonEmpty, "expected compile error for unliftable custom SqlSchema.Naming")
    }

end SqlStaticMacroAuditNamingTest
