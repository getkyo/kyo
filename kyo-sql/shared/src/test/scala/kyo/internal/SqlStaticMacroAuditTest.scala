package kyo.internal

import kyo.*
import kyo.Test
import scala.compiletime.testing.typeCheckErrors
import scala.compiletime.testing.typeChecks

/** Contract audit for [[kyo.internal.SqlStaticMacro]].
  *
  * Each leaf below drives the macro through the test-only [[kyo.SqlStaticProbe]] entry point and either:
  *
  *   - **POSITIVE** uses `typeChecks` to assert the static fold succeeds, OR
  *   - **NEGATIVE** uses `typeCheckErrors` to capture the compile error and asserts the message text quality.
  *
  * The probe invokes [[SqlStaticMacro.impl]] directly (no schema requirement, no `Frame` requirement), so the tests exercise the lift and
  * render pipeline without the extra constraints that `.runStatic` layers on top (`.runStatic` additionally requires an
  * `SqlSchema[A]` and a `Frame` for its own signature). The goal is to lock down the static contract: every case where the macro should
  * fold MUST fold, and every case where it should reject the input MUST produce a positioned error that names the offending construct
  * and suggests a fix.
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
    // Scenario D, Non-inline `def` source.
    // EXPECTED INITIAL: NEGATIVE-GENERIC. The current walker only detects
    //   SqlNameResolver-driven opacity, not opaque def references.
    // -----------------------------------------------------------------------
    "D, non-inline def source produces SOME compile error" in {
        val errors = typeCheckErrors(
            """
            import kyo.*
            import kyo.SqlAst.*
            case class AuditUserD(id: Long, name: String) derives Schema
            inline given SqlSchema[AuditUserD] = SqlSchema.derived
            def adultsRt = Sql.from[AuditUserD]("u").select(c => c.u.name)
            SqlStaticProbe.render(adultsRt)
            """
        )
        assert(errors.nonEmpty, "expected compile error for non-inline def source")
    }

    // -----------------------------------------------------------------------
    // Scenario E: runtime val bind.
    // EXPECTED: NEGATIVE. The static path requires the AST to lift to a compile-time
    // constant via `FromExpr.derived`. A `Literal[String]` whose value is a runtime
    // `val` reference (not a literal) cannot be lifted: `FromExpr[String]` only
    // recovers `Literal(StringConstant(_))`. The macro must surface a positioned
    // error directing the user to `.run` (which does the same lift opportunistically
    // and falls back to the runtime renderer that binds the value at execution time).
    // -----------------------------------------------------------------------
    "E: runtime val bind reports positioned error" in {
        val errors = typeCheckErrors(
            """
            import kyo.*
            import kyo.SqlAst.*
            case class AuditUserE(id: Long, name: String) derives Schema
            inline given SqlSchema[AuditUserE] = SqlSchema.derived
            val nameVal: String = "Alice"
            SqlStaticProbe.render(Sql.from[AuditUserE]("u").where(c => c.u.name == nameVal))
            """
        )
        assert(errors.nonEmpty, "expected positioned compile error for non-liftable runtime bind")
        val texts = errors.map(_.message).mkString("\n---\n")
        assert(
            texts.contains("cannot be folded at compile time"),
            s"expected the walker to explain the fold failure, got:\n$texts"
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
    // Scenario H, Inline def for the source.
    // EXPECTED: POSITIVE. The inliner can see through inline def.
    //
    // The source uses `Sql.from[T](alias)` on its own (no `.select` chain). The
    // two `.select` overloads (single-`Term[V]` vs `Tup <: Tuple`) both match a
    // `<?> => <?>` lambda at inline-def type-check time (before substitution),
    // so `.select` inside an inline def body is an inherently unresolvable
    // ambiguity in Scala 3, orthogonal to the static-fold this leaf audits. The
    // DSL entry `Sql.from` alone is unambiguous and still exercises the
    // inline-def-through-fold path.
    // -----------------------------------------------------------------------
    "H, inline def source folds successfully" in {
        val errors = typeCheckErrors(
            """
            import kyo.*
            import kyo.SqlAst.*
            case class AuditUserH(id: Long, name: String) derives Schema
            inline given SqlSchema[AuditUserH] = SqlSchema.derived
            inline def allRows = Sql.from[AuditUserH]("u")
            SqlStaticProbe.render(allRows)
            """
        )
        assert(
            errors.isEmpty,
            s"expected static fold to succeed with inline def source; got errors:\n${errors.map(_.message).mkString("\n---\n")}"
        )
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
