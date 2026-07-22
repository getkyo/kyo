package kyo.internal

import kyo.*
import kyo.Test
import scala.compiletime.testing.typeCheckErrors

/** Contract audit for [[kyo.internal.SqlStaticMacro]] — source-shape scenarios (non-inline def / val bind / inline def source).
  *
  * Companion to [[SqlStaticMacroAuditTest]] / [[SqlStaticMacroAuditNamingTest]]; see the parent audit for the split rationale.
  */
class SqlStaticMacroAuditSourceShapeTest extends Test:

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

end SqlStaticMacroAuditSourceShapeTest
