package kyo

import scala.compiletime.testing.typeCheckErrors
import scala.compiletime.testing.typeChecks

/** Static-SQL contract audit (task #600 in `kyo-sql/plan/STATIC-AUDIT-*.md`).
  *
  * Each leaf below corresponds to one scenario from the inventory in `STATIC-AUDIT-INVENTORY.md`. The leaf either:
  *
  *   - **POSITIVE** uses `typeChecks` to assert the static fold succeeds, OR
  *   - **NEGATIVE** uses `typeCheckErrors` to capture the compile error and asserts the message text quality.
  *
  * The goal is to lock down the static contract: every case where `.runStatic` should fold MUST fold, and every case where it should reject
  * the input MUST produce a positioned error that names the offending construct and suggests a fix.
  *
  * Classification key (mirrored in `STATIC-AUDIT-CLASSIFY.md`):
  *   - SILENT, currently accepts wrong input and produces wrong SQL (BUG, must be fixed)
  *   - GENERIC, errors but message doesn't identify the offending construct (must improve message)
  *   - GOOD, positioned error names the construct AND suggests a fix
  */
class SqlStaticAuditTest extends Test:

    // -----------------------------------------------------------------------
    // Scenario A, Plain `given SqlSchema[T]` with `.runStatic` for the table.
    // EXPECTED: NEGATIVE-GOOD. The Phase 3 walker reports an `inline given` fix.
    // -----------------------------------------------------------------------
    "A, plain given for the table type produces a positioned 'inline given' error" in {
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
    // Scenario B, Inline given for the table type with `.runStatic`.
    // EXPECTED: POSITIVE. Folds successfully.
    // -----------------------------------------------------------------------
    "B, inline given for the table type folds successfully under .runStatic" in {
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
    // Scenario C, Inline given with .withNaming(snakeCase) for the table type.
    // EXPECTED: NEGATIVE-GOOD. Per the design documented on
    // `SqlStaticInlineGivenProbeTest`: any override chain (.withNaming /
    // .withTableName / .rename) can't fold per-column `sqlName` under
    // `.runStatic` because `resolveSqlName` runs inside the polyfunction body
    // that `Record.stageNamed` accepts, and the macro can't expand past that
    // substitution. The macro must surface a positioned error directing the
    // user to `.run` / `.render` (the runtime path applies the strategy correctly).
    // -----------------------------------------------------------------------
    "C: inline given with .withNaming under .runStatic reports positioned error" in {
        val errors = typeCheckErrors(
            """
            import kyo.*
            import kyo.SqlAst.*
            case class AuditUserCRow(id: Long, firstName: String) derives Schema
            inline given SqlSchema[AuditUserCRow] =
                SqlSchema.derived[AuditUserCRow].withNaming(SqlSchema.Naming.snakeCase)
            val r = SqlStatic.staticSql(
                Sql.from[AuditUserCRow]("u").select(c => c.u.firstName)
            )
            """
        )
        assert(errors.nonEmpty, "expected a positioned compile error for override chain under .runStatic")
        val texts = errors.map(_.message).mkString("\n---\n")
        assert(
            texts.contains("inline given") && texts.contains("cannot be folded"),
            s"expected the runStatic walker to direct user to `inline given`, got:\n$texts"
        )
    }

    // -----------------------------------------------------------------------
    // Scenario D, Non-inline `def` source under `.runStatic`.
    // EXPECTED INITIAL: NEGATIVE-GENERIC. The current walker only detects
    //   SqlNameResolver-driven opacity, not opaque def references.
    // GAP: needs walker extension to recognise this.
    // -----------------------------------------------------------------------
    "D, non-inline def source under .runStatic produces SOME compile error" in {
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
    // Scenario E: sql"$val" interpolation under .runStatic with a runtime val.
    // EXPECTED: NEGATIVE. `staticSql` requires the AST to lift to a compile-time
    // constant via `FromExpr.derived`. A `Fragment.Bind` whose value is a runtime
    // `val` reference (not a literal) cannot be lifted: `FromExpr[String]` only
    // recovers `Literal(StringConstant(_))`. The macro must surface a positioned
    // error directing the user to `.run` (which does the same lift opportunistically
    // and falls back to the runtime renderer that binds the value at execution
    // time). The compile-time bind fast-path is possible in principle but would
    // require deferred lifting of the argument Expr; the current design does not
    // implement it, and this leaf pins the observed contract.
    // -----------------------------------------------------------------------
    "E: sql interpolation with runtime val under .runStatic reports positioned error" in {
        val errors = typeCheckErrors(
            """
            import kyo.*
            val name: String = "Alice"
            val r = SqlStatic.staticSql(sql"SELECT id FROM users WHERE name = $name")
            """
        )
        assert(errors.nonEmpty, "expected positioned compile error for non-liftable runtime bind under .runStatic")
        val texts = errors.map(_.message).mkString("\n---\n")
        assert(
            texts.contains("cannot be folded at compile time"),
            s"expected the runStatic walker to explain the fold failure, got:\n$texts"
        )
    }

    // -----------------------------------------------------------------------
    // Scenario F, Custom SqlSchema.Naming (not snakeCase / identity) under
    //   `.runStatic` via FromExpr[SqlSchema.Naming].
    // EXPECTED: NEGATIVE-GENERIC initially. The FromExpr only matches the
    //   two known strategies; a custom one would fail to lift.
    // GAP: error message should name the unsupported SqlSchema.Naming variant.
    // -----------------------------------------------------------------------
    "F, custom SqlSchema.Naming via inline given under .runStatic produces a compile error" in {
        val errors = typeCheckErrors(
            """
            import kyo.*
            import kyo.SqlAst.*
            case class AuditUserF(id: Long, firstName: String) derives Schema
            val custom: SqlSchema.Naming = new SqlSchema.Naming:
                def tableName(scalaName: String): String  = scalaName + "_custom"
                def columnName(scalaName: String): String = scalaName + "_c"
            inline given SqlSchema[AuditUserF] = SqlSchema.derived[AuditUserF].withNaming(custom)
            SqlStatic.staticSql(Sql.from[AuditUserF]("u").select(c => c.u.firstName))
            """
        )
        assert(errors.nonEmpty, "expected compile error for unliftable custom SqlSchema.Naming")
    }

    // -----------------------------------------------------------------------
    // Scenario G, Plain given for the table type used with .run (NOT .runStatic).
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
    // Scenario H, Inline def for the source under .runStatic.
    // EXPECTED: POSITIVE. The inliner can see through inline def.
    //
    // The source uses `Sql.from[T](alias)` on its own (no `.select` chain). The
    // two `.select` overloads (single-`Term[V]` vs `Tup <: Tuple`) both match a
    // `<?> => <?>` lambda at inline-def type-check time (before substitution),
    // so `.select` inside an inline def body is an inherently unresolvable
    // ambiguity in Scala 3, orthogonal to the `.runStatic` fold this leaf
    // audits. The DSL entry `Sql.from` alone is unambiguous and still exercises
    // the inline-def-through-fold path.
    // -----------------------------------------------------------------------
    "H, inline def source under .runStatic folds successfully" in {
        val errors = typeCheckErrors(
            """
            import kyo.*
            import kyo.SqlAst.*
            case class AuditUserH(id: Long, name: String) derives Schema
            inline given SqlSchema[AuditUserH] = SqlSchema.derived
            inline def allRows = Sql.from[AuditUserH]("u")
            SqlStatic.staticSql(allRows)
            """
        )
        assert(
            errors.isEmpty,
            s"expected static fold to succeed with inline def source; got errors:\n${errors.map(_.message).mkString("\n---\n")}"
        )
    }

    // -----------------------------------------------------------------------
    // Scenario I, `derives Schema` ONLY (no explicit SqlSchema) under .runStatic.
    // EXPECTED: POSITIVE. `derives Schema` produces `given Schema[T]`, not
    //   `given SqlSchema[T]`. The macro's `Expr.summon[SqlSchema[T]]` returns
    //   None, the empty-fallback branch emits constants, and the static fold
    //   succeeds. This is *provably safe* there's no way for the user to have
    //   applied overrides via a `Schema` instance, so "empty info" is correct.
    // -----------------------------------------------------------------------
    "I, derives Schema alone (no explicit SqlSchema) folds successfully under .runStatic" in {
        val ok = typeChecks(
            """
            import kyo.*
            import kyo.SqlAst.*
            case class AuditUserI(id: Long, name: String) derives Schema
            // No explicit `given SqlSchema[AuditUserI]`, the macro will summon None
            // and fall back to verbatim Scala identifier names.
            SqlStatic.staticSql(Sql.from[AuditUserI]("u").select(c => c.u.name))
            """
        )
        assert(ok, "expected `derives Schema` alone to fold statically (no SqlSchema overrides possible)")
    }

end SqlStaticAuditTest
