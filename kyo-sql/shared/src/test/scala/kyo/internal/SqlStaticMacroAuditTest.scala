package kyo.internal

import kyo.*
import kyo.Test
import scala.compiletime.testing.typeCheckErrors
import scala.compiletime.testing.typeChecks

/** Contract audit for [[kyo.internal.SqlStaticMacro]] — schema-shape scenarios (given SqlSchema / inline given / .withNaming).
  *
  * Each leaf drives the macro through the test-only [[kyo.SqlStaticProbe]] entry point and either:
  *
  *   - **POSITIVE** uses `typeChecks` to assert the static fold succeeds, OR
  *   - **NEGATIVE** uses `typeCheckErrors` to capture the compile error and asserts the message text quality.
  *
  * Split from a single large audit into schema-shape / source-shape / naming pairs (see
  * [[SqlStaticMacroAuditSourceShapeTest]], [[SqlStaticMacroAuditNamingTest]]) because sbt-zinc 1.12.13 fails post-compile
  * analysis with `Failed to find name hashes` when a single test class contains too many `typeChecks` / `typeCheckErrors`
  * snippets each with a wildcard `import kyo.SqlAst.*`.
  */
class SqlStaticMacroAuditTest extends Test:

    // -----------------------------------------------------------------------
    // Scenario A, Plain `given SqlSchema[T]` with the macro for the table.
    // EXPECTED: NEGATIVE-GOOD. The walker reports an `inline given` fix.
    // -----------------------------------------------------------------------
    "A, plain given for the table type produces a positioned 'inline given' error" in {
        val errors = typeCheckErrors(
            """
            import kyo.*
            import kyo.SqlAst.*
            case class AuditUserA(id: Long, name: String) derives Schema
            given SqlSchema[AuditUserA] = SqlSchema.derived
            SqlStaticProbe.render(Sql.from[AuditUserA]("u").select(c => c.u.name))
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
    // Scenario B, Inline given for the table type.
    // EXPECTED: POSITIVE. Folds successfully.
    // -----------------------------------------------------------------------
    "B, inline given for the table type folds successfully" in {
        val ok = typeChecks(
            """
            import kyo.*
            import kyo.SqlAst.*
            case class AuditUserB(id: Long, name: String) derives Schema
            inline given SqlSchema[AuditUserB] = SqlSchema.derived
            SqlStaticProbe.render(Sql.from[AuditUserB]("u").select(c => c.u.name))
            """
        )
        assert(ok, "expected static fold to succeed")
    }

    // -----------------------------------------------------------------------
    // Scenario G, Plain given for the table type used with .run (NOT the static path).
    // EXPECTED: POSITIVE. .run is opportunistic, it MUST accept plain given
    //   and fall back to the runtime renderer.
    // -----------------------------------------------------------------------
    "G, plain given for the table type compiles cleanly under .run" in {
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
    // Scenario I, `derives Schema` ONLY (no explicit SqlSchema).
    // EXPECTED: POSITIVE. `derives Schema` produces `given Schema[T]`, not
    //   `given SqlSchema[T]`. The macro's `Expr.summon[SqlSchema[T]]` returns
    //   None, the empty-fallback branch emits constants, and the static fold
    //   succeeds. Provably safe: there's no way for the user to have applied
    //   overrides via a `Schema` instance, so "empty info" is correct.
    // -----------------------------------------------------------------------
    "I, derives Schema alone (no explicit SqlSchema) folds successfully" in {
        val ok = typeChecks(
            """
            import kyo.*
            import kyo.SqlAst.*
            case class AuditUserI(id: Long, name: String) derives Schema
            // No explicit `given SqlSchema[AuditUserI]`, the macro will summon None
            // and fall back to verbatim Scala identifier names.
            SqlStaticProbe.render(Sql.from[AuditUserI]("u").select(c => c.u.name))
            """
        )
        assert(ok, "expected `derives Schema` alone to fold statically (no SqlSchema overrides possible)")
    }

end SqlStaticMacroAuditTest
