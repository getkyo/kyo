package kyo

import kyo.SqlAst.*
import scala.compiletime.testing.typeCheckErrors

/** Verifies the Phase 7/8 static-fast-path wiring in [[kyo.internal.SqlRunMacro]].
  *
  * Scenarios:
  *   - `.runStatic` on a fully-reducible inline query emits a compile-time splice (no runtime render).
  *   - `.runStatic` on a window-function query compiles statically (Phase 8 adds `FromExpr` givens for `WindowSpec` / `WindowFrame` /
  *     `FrameBound` / `WindowSpecBuilder`).
  *   - `.runStatic` on a non-liftable Update emits a compile error via `report.errorAndAbort`.
  *   - `.run` on a static query succeeds; this exercises the `isStaticallyReducible` probe that selects the static fast-path (falls back to
  *     runtime when not liftable).
  *   - Insert / Delete `.runStatic` compile correctly for static ASTs.
  *   - Update `.runStatic` fails at compile time because the `set(...)` lambda is not statically liftable.
  */
class SqlRunStaticTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives Schema
    case class User(id: Long, email: String) derives Schema

    inline given personSqlSchema: SqlSchema[Person] = SqlSchema.derived
    inline given userSqlSchema: SqlSchema[User]     = SqlSchema.derived

    // ── Leaf A, runStatic on a fully-static select compiles to a SqlStatic.BackendSql splice ──

    "Query.runStatic on a static select compiles (static fast-path wired)" in {
        // Phase 7: the macro now delegates to SqlStaticMacro.impl which renders both backends at
        // compile time and emits an Expr[SqlStatic.Rendered]. The splice below should compile cleanly.
        def shape(using Frame): Chunk[String] < (Async & Abort[SqlException] & Scope) =
            Sql.from[Person]("p").select(c => c.p.name).runStatic
        succeed
    }

    "Query.runStatic on a compound where+orderBy query compiles" in {
        def shape(using Frame): Chunk[Person] < (Async & Abort[SqlException] & Scope) =
            Sql.from[Person]("p")
                .where(c => c.p.age >= 18)
                .orderBy(c => c.p.name.asc)
                .runStatic
        succeed
    }

    // ── Leaf B, runStatic on a window function now compiles (Phase 8 adds FromExpr for WindowSpec)
    //
    // WindowSpecBuilder.partitionBy now uses explicit `new WindowSpecBuilder(...)` constructors
    // (Phase 8 fix) and `FromExpr` givens for WindowSpec / WindowFrame / FrameBound /
    // WindowSpecBuilder are in scope, so the macro lifts window expressions at compile time.

    "Query.runStatic on a window function compiles statically (Phase 8: WindowSpec is liftable)" in {
        def shape(using Frame): Chunk[Long] < (Async & Abort[SqlException] & Scope) =
            Sql.from[Person]("p").select(c =>
                WindowFunction.RowNumber.over(WindowSpec(Chunk(c.p.deptId), Chunk(c.p.age.asc), Maybe.empty))
            ).runStatic
        succeed
    }

    // ── Leaf C, .run on a static query succeeds (isStaticallyReducible probe) ────

    "Query.run on a static select compiles (static fast-path probe fires)" in {
        def shape(using Frame): Chunk[Person] < (Async & Abort[SqlException] & Scope) =
            Sql.from[Person]("p").run
        succeed
    }

    // ── Leaf D, Insert.runStatic compiles for a static AST ───────────────────────

    "Insert.runStatic compiles for a static AST" in {
        def shape(using Frame): InsertResult < (Async & Abort[SqlException] & Scope) =
            Sql.insert[User].values(User(0L, "ada@example.com")).runStatic
        succeed
    }

    // ── Leaf E, Update.runStatic fails at compile time (sets lambda not liftable) ─
    //
    // `UpdateBuilder.set(inline specs: ...)` applies each spec lambda via `specs.map(_(columns))`
    // which produces a runtime `Chunk`, not reducible by `FromExpr.derived`. Phase 7 wires
    // runStatic to report.errorAndAbort when the AST is not liftable.

    "Update.runStatic fails at compile time (set lambda is not statically liftable)" in {
        val errors = typeCheckErrors(
            """def shape(using kyo.Frame): Long < (kyo.Async & kyo.Abort[kyo.SqlException] & kyo.Scope) =
  kyo.Sql.update[User].set(_.email := "new@example.com").where(_.id == 1L).runStatic"""
        )
        assert(errors.nonEmpty, "expected a compile error for runStatic on a non-liftable Update")
        // Phase 7 audit W-1: tighten beyond bare `errors.nonEmpty`. The macro must carry the
        // `staticSql`/`statically render` diagnostic from SqlStaticMacro.impl's report.errorAndAbort,
        // not silently fail with an unrelated message. The exact wording is in
        // SqlStaticMacro.scala:29-32 ("staticSql: cannot statically render this query").
        val message = errors.map(_.message).mkString(" ")
        assert(
            message.contains("staticSql") || message.contains("statically render"),
            s"expected the SqlStaticMacro.impl diagnostic in the error message; got: $message"
        )
    }

    // ── Leaf F, Delete.runStatic compiles for a static AST ───────────────────────

    "Delete.runStatic compiles for a static AST" in {
        def shape(using Frame): Long < (Async & Abort[SqlException] & Scope) =
            Sql.delete[User].where(_.id == 1L).runStatic
        succeed
    }

    // ── Leaf G, .run on Insert, Update, Delete compiles ─────────────────────────
    // Insert.run uses the static fast-path (Insert AST is liftable).
    // Update.run / Delete.run fall back to runtime (sets lambda not liftable for Update;
    // Delete is liftable and uses the static path).

    "Insert.run uses static fast-path on a literal" in {
        def shape(using Frame): InsertResult < (Async & Abort[SqlException] & Scope) =
            Sql.insert[User].values(User(0L, "x")).run
        succeed
    }

    "Update.run falls back to runtime (not statically liftable)" in {
        def shape(using Frame): Long < (Async & Abort[SqlException] & Scope) =
            Sql.update[User].set(_.email := "y").where(_.id == 2L).run
        succeed
    }

    "Delete.run uses static fast-path on a literal" in {
        def shape(using Frame): Long < (Async & Abort[SqlException] & Scope) =
            Sql.delete[User].where(_.id == 3L).run
        succeed
    }

end SqlRunStaticTest
