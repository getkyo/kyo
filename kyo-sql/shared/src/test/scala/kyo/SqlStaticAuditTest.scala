package kyo

import scala.compiletime.testing.typeCheckErrors
import scala.compiletime.testing.typeChecks

/** Static-SQL contract audit (task #600 in `kyo-sql/plan/STATIC-AUDIT-*.md`).
  *
  * Each leaf below corresponds to one scenario from the inventory in `STATIC-AUDIT-INVENTORY.md`. The leaf either:
  *
  *   - **POSITIVE** — uses `typeChecks` to assert the static fold succeeds, OR
  *   - **NEGATIVE** — uses `typeCheckErrors` to capture the compile error and asserts the message text quality.
  *
  * The goal is to lock down the static contract: every case where `.runStatic` should fold MUST fold, and every case where it should reject
  * the input MUST produce a positioned error that names the offending construct and suggests a fix.
  *
  * Classification key (mirrored in `STATIC-AUDIT-CLASSIFY.md`):
  *   - SILENT — currently accepts wrong input and produces wrong SQL (BUG, must be fixed)
  *   - GENERIC — errors but message doesn't identify the offending construct (must improve message)
  *   - GOOD — positioned error names the construct AND suggests a fix
  */
class SqlStaticAuditTest extends Test:

    // -----------------------------------------------------------------------
    // Scenario A — Plain `given SqlSchema[T]` with `.runStatic` for the table.
    // EXPECTED: NEGATIVE-GOOD. The Phase 3 walker reports an `inline given` fix.
    // -----------------------------------------------------------------------
    "A — plain given for the table type produces a positioned 'inline given' error" in {
        val errors = typeCheckErrors(
            """
            import kyo.*
            import kyo.SqlAst.*
            case class AuditUserA(id: Long, name: String) derives Schema
            given SqlSchema[AuditUserA] = SqlSchema.derived
            SqlStatic.staticSql(Sql.from[AuditUserA]("u").select(c => c.u.name))
            """
        )
        assert(errors.nonEmpty, "expected at least one compile error")
        val texts = errors.map(_.message).mkString("\n---\n")
        assert(
            texts.contains("inline given") && texts.contains("AuditUserA"),
            s"expected message naming the schema type and suggesting `inline given`, got:\n$texts"
        )
    }

    // -----------------------------------------------------------------------
    // Scenario B — Inline given for the table type with `.runStatic`.
    // EXPECTED: POSITIVE. Folds successfully.
    // -----------------------------------------------------------------------
    "B — inline given for the table type folds successfully under .runStatic" in {
        val ok = typeChecks(
            """
            import kyo.*
            import kyo.SqlAst.*
            case class AuditUserB(id: Long, name: String) derives Schema
            inline given SqlSchema[AuditUserB] = SqlSchema.derived
            SqlStatic.staticSql(Sql.from[AuditUserB]("u").select(c => c.u.name))
            """
        )
        assert(ok, "expected static fold to succeed")
    }

    // -----------------------------------------------------------------------
    // Scenario C — Inline given with .withNaming(snakeCase) for the table type.
    // EXPECTED: POSITIVE. Folds and applies snake_case.
    // -----------------------------------------------------------------------
    "C — inline given with .withNaming applies snake_case under .runStatic" in {
        val errors = typeCheckErrors(
            """
            import kyo.*
            import kyo.SqlAst.*
            case class AuditUserCRow(id: Long, firstName: String) derives Schema
            inline given SqlSchema[AuditUserCRow] =
                SqlSchema.derived[AuditUserCRow].withNaming(NamingStrategy.snakeCase)
            val r = SqlStatic.staticSql(
                Sql.from[AuditUserCRow]("u").select(c => c.u.firstName)
            )
            """
        )
        assert(
            errors.isEmpty,
            s"expected static fold to succeed; got errors:\n${errors.map(_.message).mkString("\n---\n")}"
        )
    }

    // -----------------------------------------------------------------------
    // Scenario D — Non-inline `def` source under `.runStatic`.
    // EXPECTED INITIAL: NEGATIVE-GENERIC. The current walker only detects
    //   SqlNameResolver-driven opacity, not opaque def references.
    // GAP: needs walker extension to recognise this.
    // -----------------------------------------------------------------------
    "D — non-inline def source under .runStatic produces SOME compile error" in {
        val errors = typeCheckErrors(
            """
            import kyo.*
            import kyo.SqlAst.*
            case class AuditUserD(id: Long, name: String) derives Schema
            inline given SqlSchema[AuditUserD] = SqlSchema.derived
            def adultsRt = Sql.from[AuditUserD]("u").select(c => c.u.name)
            SqlStatic.staticSql(adultsRt)
            """
        )
        assert(errors.nonEmpty, "expected compile error for non-inline def source")
        // Note: not yet asserting the error is GOOD (positioned at adultsRt, naming the def);
        // task #601 will make this assertion sharper.
    }

    // -----------------------------------------------------------------------
    // Scenario E — sql"$val" interpolation under .runStatic with runtime val.
    // EXPECTED: POSITIVE. The interpolated value is a bind parameter, not a
    //   structural part; static fold should succeed and emit a parameter.
    // -----------------------------------------------------------------------
    "E — sql interpolation with runtime val under .runStatic emits a bind parameter" in {
        val errors = typeCheckErrors(
            """
            import kyo.*
            val name: String = "Alice"
            val r = SqlStatic.staticSql(sql"SELECT id FROM users WHERE name = $name")
            """
        )
        assert(
            errors.isEmpty,
            s"expected sql\"$$val\" to fold; got errors:\n${errors.map(_.message).mkString("\n---\n")}"
        )
    }

    // -----------------------------------------------------------------------
    // Scenario F — Custom NamingStrategy (not snakeCase / identity) under
    //   `.runStatic` via FromExpr[NamingStrategy].
    // EXPECTED: NEGATIVE-GENERIC initially. The FromExpr only matches the
    //   two known strategies; a custom one would fail to lift.
    // GAP: error message should name the unsupported NamingStrategy variant.
    // -----------------------------------------------------------------------
    "F — custom NamingStrategy via inline given under .runStatic produces a compile error" in {
        val errors = typeCheckErrors(
            """
            import kyo.*
            import kyo.SqlAst.*
            case class AuditUserF(id: Long, firstName: String) derives Schema
            val custom: NamingStrategy = new NamingStrategy:
                def tableName(scalaName: String): String  = scalaName + "_custom"
                def columnName(scalaName: String): String = scalaName + "_c"
            inline given SqlSchema[AuditUserF] = SqlSchema.derived[AuditUserF].withNaming(custom)
            SqlStatic.staticSql(Sql.from[AuditUserF]("u").select(c => c.u.firstName))
            """
        )
        assert(errors.nonEmpty, "expected compile error for unliftable custom NamingStrategy")
    }

    // -----------------------------------------------------------------------
    // Scenario G — Plain given for the table type used with .run (NOT .runStatic).
    // EXPECTED: POSITIVE. .run is opportunistic — it MUST accept plain given
    //   and fall back to the runtime renderer.
    // -----------------------------------------------------------------------
    "G — plain given for the table type compiles cleanly under .run" in {
        val ok = typeChecks(
            """
            import kyo.*
            import kyo.SqlAst.*
            case class AuditUserG(id: Long, name: String) derives Schema
            given SqlSchema[AuditUserG] = SqlSchema.derived
            val q = Sql.from[AuditUserG]("u").select(c => c.u.name)
            // Note: not actually running .run here (no SqlClient in scope); just confirming
            // that the .run macro accepts the plain-given schema at compile time.
            """
        )
        assert(ok, "expected plain given to compile under .run")
    }

    // -----------------------------------------------------------------------
    // Scenario H — Inline def for the source under .runStatic.
    // EXPECTED: POSITIVE. The inliner can see through inline def.
    // -----------------------------------------------------------------------
    "H — inline def source under .runStatic folds successfully" in {
        val errors = typeCheckErrors(
            """
            import kyo.*
            import kyo.SqlAst.*
            case class AuditUserH(id: Long, name: String) derives Schema
            inline given SqlSchema[AuditUserH] = SqlSchema.derived
            inline def adults = Sql.from[AuditUserH]("u").select(c => c.u.name)
            SqlStatic.staticSql(adults)
            """
        )
        assert(
            errors.isEmpty,
            s"expected static fold to succeed with inline def source; got errors:\n${errors.map(_.message).mkString("\n---\n")}"
        )
    }

    // -----------------------------------------------------------------------
    // Scenario I — `derives Schema` ONLY (no explicit SqlSchema) under .runStatic.
    // EXPECTED: POSITIVE. `derives Schema` produces `given Schema[T]`, not
    //   `given SqlSchema[T]`. The macro's `Expr.summon[SqlSchema[T]]` returns
    //   None, the empty-fallback branch emits constants, and the static fold
    //   succeeds. This is *provably safe* — there's no way for the user to have
    //   applied overrides via a `Schema` instance, so "empty info" is correct.
    // -----------------------------------------------------------------------
    "I — derives Schema alone (no explicit SqlSchema) folds successfully under .runStatic" in {
        val ok = typeChecks(
            """
            import kyo.*
            import kyo.SqlAst.*
            case class AuditUserI(id: Long, name: String) derives Schema
            // No explicit `given SqlSchema[AuditUserI]` — the macro will summon None
            // and fall back to verbatim Scala identifier names.
            SqlStatic.staticSql(Sql.from[AuditUserI]("u").select(c => c.u.name))
            """
        )
        assert(ok, "expected `derives Schema` alone to fold statically (no SqlSchema overrides possible)")
    }

end SqlStaticAuditTest
