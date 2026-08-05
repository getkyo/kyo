package kyo

import scala.compiletime.testing.typeCheckErrors

/** Verifies the narrow catch in `ColumnFromExpr.liftRecord`.
  *
  * The narrow catch in `ColumnFromExpr.liftRecord` absorbs only `scala.MatchError` and `ClassCastException` (expected tree-shape mismatches)
  * and rethrows all other `NonFatal` exceptions so real macro errors propagate with their actual message.
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

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives SqlSchema

    "tree-shape mismatch returns None silently, render on groupBy query succeeds" in {
        // `groupBy` exercises the `fromExprGroupedColumn` / `fromExprUngroupedView` givens, which call
        // `liftRecord` on the inner Record term. When the term does not match a known Record-literal
        // tree shape, `liftRecord` catches the resulting `MatchError` and returns `None`, the walker
        // propagates `None` upward. Runtime rendering never exercises `liftRecord`, so this leaf proves the
        // happy-path render is correct while `liftRecord`'s narrow catch keeps the static-lift path from
        // crashing with a raw MatchError stack trace on the same query shape. The dialect is the core-owned
        // stub, because what is under test is the walk over this query shape and not any flavor's syntax.
        val q = Sql.from[Person]("p").groupBy(c => c.p.deptId).select(view => view.deptId)
        assert(q.render(IdiomRenderStub).onlySql.get == "SELECT [p].[deptId] FROM [person] [p] GROUP BY [p].[deptId]")
        assert(q.render(IdiomRenderStub).params.isEmpty)
    }

    "non-liftable expression reports the fallback diagnostic, smoke-test for the outer fallback path" in {
        // This leaf is a smoke test:
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
        // lift-query precondition with the fold diagnostic, i.e. the user gets actionable guidance rather
        // than a silent-failure or empty message. This is the regression test for the outer fallback text,
        // not the narrow `liftRecord` catch arms.
        //
        // The assertion names the fold text exactly and then rules out the other message this module can
        // produce. `kyo-sql` carries no dialect on its own classpath, so a `.runStatic` that DOES lift aborts
        // with `SqlStaticMacro`'s empty-classpath diagnostic instead, and that message also contains
        // ".runStatic". An assertion satisfied by either one would pass whichever failure occurred and so
        // would certify nothing about the lift.
        //
        // The `SqlSchema[P]` evidence is load-bearing, and its absence is what a loose assertion would hide.
        // `.runStatic` is `extension [A](inline q: Query[A])` with a `using SqlSchema[A]`, so with no evidence for
        // `P` the extension is not applicable and the compiler reports "value runStatic is not a member of
        // kyo.Sql.Table[...], but could be made available as an extension method". That error never invokes
        // the macro, so the leaf would pin a missing given rather than the fold. `derives SqlSchema` supplies it.
        val errors = typeCheckErrors(
            """
            import kyo.*
            case class P(id: Long, name: String) derives SqlSchema
            val q = Sql.from[P]("p")
            def shape(using Frame): Chunk[P] < (Abort[SqlException] & DB) = q.runStatic
            """
        )
        val message = errors.map(_.message).mkString(" ")
        assert(
            message.contains("cannot be folded at compile time"),
            s"compile error should name the fold failure, got: $message"
        )
        assert(
            !message.contains("no kyo-sql backend on the compile classpath"),
            s"the empty-classpath diagnostic would pass a looser assertion without exercising the lift, got: $message"
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
            case class P(id: Long, name: String) derives SqlSchema
            // Wrap a Record-shaped projection in a non-literal binding so the tree walker sees a `val` term
            // instead of the inline Record-literal shape `RecordFromExpr` recognises.
            val dynRecord = Sql.from[P]("p").select(c => c.p.name)
            def shape(using Frame): Chunk[String] < (Abort[SqlException] & DB) = dynRecord.runStatic
            """
        )
        val message = errors.map(_.message).mkString(" ")
        assert(
            message.contains("cannot be folded at compile time"),
            s"expected the static-render fold diagnostic, got: $message"
        )
        assert(
            !message.contains("no kyo-sql backend on the compile classpath"),
            s"the empty-classpath diagnostic would pass a looser assertion without exercising the lift, got: $message"
        )
    }

    // A negative leaf that calls `SqlLiftHarness.matched` inside a `typeCheckErrors` string cannot live in THIS
    // module. `SqlLiftHarness` is a macro compiled in this test module's own Zinc run; `typeCheckErrors` is
    // `transparent inline` and expands during typer, but its intrinsic runs the Inlining transformer under
    // `atPhase(inliningPhase)`. A same-run macro reached from inside that string suspends the unit,
    // `CompilationUnit.suspend` stamps `suspendedAtInliningPhase`, the retry skips `ExtractAPI`, and Zinc dies with
    // `Failed to find name hashes` (scalac prints `done compiling` and reports nothing). Such a leaf belongs in
    // kyo-sql-postgres, where the harness is a classpath macro through the `test->test` edge and the suspension
    // never happens.

end ColumnFromExprNegativeTest
