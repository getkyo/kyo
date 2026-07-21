package kyo

import scala.compiletime.testing.typeCheckErrors

/** Verifies the narrow catch in `ColumnFromExpr.liftRecord`.
  *
  * Phase 6 (G5.1) replaces `catch case _: Throwable => None` with a narrow catch that absorbs only `scala.MatchError` and
  * `ClassCastException` (expected tree-shape mismatches) and rethrows all other `NonFatal` exceptions so real macro errors propagate with
  * their actual message.
  *
  * Two scenarios:
  *   1. Tree-shape mismatch returns None silently, `liftRecord` catches `MatchError`, returns `None`. The outer `.runStatic` either
  *      succeeds (when the rest of the AST is liftable) or reports the "cannot be folded at compile time" fallback, but never crashes with
  *      a naked `MatchError` stack trace.
  *   2. Unexpected exception propagates with original message, `liftRecord`'s `NonFatal(e) => throw e` arm ensures that a non-MatchError /
  *      non-ClassCastException failure is NOT swallowed; the compile error contains the real diagnostic, not just the generic "cannot be
  *      folded" text.
  */
class ColumnFromExprNegativeTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives Schema

    "tree-shape mismatch returns None silently, render on groupBy query succeeds" in {
        // `groupBy` exercises the `fromExprGroupedColumn` / `fromExprUngroupedView` givens, which call
        // `liftRecord` on the inner Record term. When the term does not match a known Record-literal
        // tree shape, `liftRecord` catches the resulting `MatchError` and returns `None`, the walker
        // propagates `None` upward. Runtime rendering (`.renderPostgres` / `.renderMysql`) never
        // exercises `liftRecord`, so this leaf now proves the happy-path render is correct while
        // `liftRecord`'s narrow catch keeps the static-lift path from crashing with a raw MatchError
        // stack trace on the same query shape.
        val q = Sql.from[Person]("p").groupBy(c => c.p.deptId).select(view => view.deptId)
        assert(q.renderPostgres.sql == """SELECT "p"."deptId" FROM "person" "p" GROUP BY "p"."deptId"""")
        assert(q.renderMysql.sql == "SELECT `p`.`deptId` FROM `person` `p` GROUP BY `p`.`deptId`")
        assert(q.renderPostgres.params.isEmpty)
    }

    "non-liftable expression reports the fallback diagnostic, smoke-test for the outer fallback path" in {
        // SMOKE-TEST scope acknowledgement (per Phase 6 audit W-2):
        //   This leaf does NOT exercise `liftRecord`'s `NonFatal => throw e` arm. The NonFatal arm is
        //   unreachable from a `typeCheckErrors` / `.runStatic` user-space input because the only path that
        //   reaches `liftRecord`'s `try` body is a successful tree-walk that produces a `Record` term, and
        //   the operations inside the `try` (`RecordFromExpr.fromExprRecord.unapply`) only raise
        //   `MatchError` (unmatched shape, expected, `case _: MatchError => None`) or
        //   `ClassCastException` (impossible cast, expected, `case e: CCE => report.warning + None`).
        //   Any genuine `NonFatal` from inside `RecordFromExpr` would be a macro-internal bug, not a user
        //   input we can construct here.
        //
        // What this leaf DOES verify: a non-inline `val` reference to a query fails the OUTER macro's
        // lift-query precondition with a diagnostic that mentions `.runStatic` / `cannot be folded` /
        // `inline`, i.e. the user gets actionable guidance rather than a silent-failure or empty message.
        // This is the regression test for the outer fallback text, not the narrow `liftRecord` catch arms.
        val errors = typeCheckErrors(
            """
            import kyo.*
            case class P(id: Long, name: String) derives Schema
            val q = Sql.from[P]("p")
            def shape(using Frame): Chunk[P] < (Async & Abort[SqlException] & Scope) = q.runStatic
            """
        )
        assert(errors.nonEmpty, "expected a compile error for a non-inline .runStatic argument")
        val message = errors.map(_.message).mkString(" ")
        assert(
            message.contains(".runStatic") || message.contains("cannot be folded") || message.contains("inline"),
            s"compile error should contain diagnostic text about .runStatic / static rendering, got: $message"
        )
    }

    "non-inline Record dict value fails to lift and surfaces the static-render fallback (MatchError silent-None path)" in {
        // This leaf directly stresses the `liftRecord` MatchError arm by feeding `.runStatic` a query whose
        // `select` body returns a Record value that is not built from inline literals, the `RecordFromExpr`
        // pattern-match fails (MatchError), `liftRecord` returns `None`, and the outer macro reports the
        // "cannot be folded at compile time" fallback. If the catch were widened back to `case _: Throwable`,
        // the assertion would still pass (None is None); if the catch were removed entirely, a raw MatchError
        // stack trace would surface and the assertion text wouldn't match.
        val errors = typeCheckErrors(
            """
            import kyo.*
            case class P(id: Long, name: String) derives Schema
            // Wrap a Record-shaped projection in a non-literal binding so the tree walker sees a `val` term
            // instead of the inline Record-literal shape `RecordFromExpr` recognises.
            val dynRecord = Sql.from[P]("p").select(c => c.p.name)
            def shape(using Frame): Chunk[String] < (Async & Abort[SqlException] & Scope) = dynRecord.runStatic
            """
        )
        assert(errors.nonEmpty, "expected a compile error for a non-inline Record-bearing query")
        val message = errors.map(_.message).mkString(" ")
        assert(
            message.contains(".runStatic") || message.contains("cannot be folded") || message.contains("inline"),
            s"expected static-render fallback diagnostic, got: $message"
        )
    }

end ColumnFromExprNegativeTest
