package kyo
import kyo.Sql.*
import kyo.db.Idiom
import scala.compiletime.testing.typeCheckErrors

/** Verifies the shape of the `.run` / `.runStatic` / `.runDynamic` surface on `Query[A]` / `Insert` / `Update` / `Delete`: which of the three
  * each statement kind carries, what each returns, and how a call behaves with no client installed.
  *
  * This suite survives alongside [[SqlRunStaticTest]] rather than folding into it, and the split is by subject. Here the subject is the
  * surface: every statement kind exposes all three spellings, each returns its own result type and not another's, a call with nothing
  * installed fails typed instead of panicking, and one call site serves either engine. There the subject is the compile-time fold itself:
  * what folds, what does not, and what the splice contains. Neither suite asserts the other's property, so nothing here is a second copy of
  * a scenario there.
  */
class SqlRunTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives SqlSchema
    case class User(id: Long, email: String) derives SqlSchema

    // ── Leaf 1, .run available on Query[A] with the right effect row ──────────

    "Query.run compiles and has effect row Async & Abort[SqlException] & DB" in {
        // Test by direct typed reference, the assertion is the compilation itself.
        def shape(using Frame): Chunk[String] < (Abort[SqlException] & DB) =
            Sql.from[Person]("p").select(c => c.p.name).run
        succeed
    }

    // ── Leaf 2, runDynamic works on a non-inline val Query ─────────────────────

    "Query.runDynamic compiles on a non-inline val" in {
        def shape(using Frame): Chunk[String] < (Abort[SqlException] & DB) =
            val q: Query[String] = Sql.from[Person]("p").select(c => c.p.name)
            q.runDynamic
        succeed
    }

    // ── Leaf 3, runStatic succeeds on a fully-static AST ──────────────────────
    //
    // runStatic folds through SqlStaticMacro at compile time, so the pair below is both halves of that:
    //  (a) positive: a fully-reducible inline select compiles and has the right effect row.
    //  (b) negative: runStatic on a non-literal `val` query fails, since it is neither inline nor reducible.

    "Query.runStatic compiles for a fully-static inline AST" in {
        // The macro succeeds when the entire AST is liftable at compile time.
        def shape(using Frame): Chunk[String] < (Abort[SqlException] & DB) =
            Sql.from[Person]("p").select(c => c.p.name).runStatic
        succeed
    }

    "Query.runStatic fails at compile time for a non-inline val reference" in {
        // runStatic is defined only on `inline q: Query[A]`. Binding the query in a non-inline val
        // and then calling .runStatic is a compile error: the inline extension is not applicable.
        //
        // The annotation is `kyo.Sql.Query`, not `kyo.Query`. There is no `kyo.Query`, so that spelling gets the
        // snippet rejected for naming a type that does not exist, and a leaf asserting only `errors.nonEmpty`
        // passes without ever reaching `.runStatic`.
        val errors = typeCheckErrors(
            """def shape(using kyo.Frame): kyo.Chunk[String] < (kyo.Async & kyo.Abort[kyo.SqlException] & kyo.DB) = {
  val q: kyo.Sql.Query[String] = kyo.Sql.from[Person]("p").select(c => c.p.name)
  q.runStatic
}"""
        )
        // The snippet is a string literal no compiler checks until typeCheckErrors runs it, so a stale type
        // name inside it produces a compile error and a green test forever. Naming the diagnostic is what
        // separates "rejected for the property under test" from "rejected for anything at all".
        val message = errors.map(_.message).mkString(" ")
        assert(
            message.contains("cannot be folded at compile time"),
            s"the error must be the macro's fold diagnostic, not an unrelated rejection of the snippet; got: $message"
        )
    }

    // ── Leaf 4, .run on Insert returns SqlClient.InsertOutcome ────────────────────────────

    "Insert.run returns SqlClient.InsertOutcome" in {
        def shape(using Frame): SqlClient.InsertOutcome < (Abort[SqlException] & DB) =
            Sql.insert[User].values(User(0L, "ada@example.com")).run
        succeed
    }

    // ── Leaf 5, .run on Update returns Long ────────────────────────────────────

    "Update.run returns Long" in {
        def shape(using Frame): Long < (Abort[SqlException] & DB) =
            Sql.update[User].set(_.email := "x").where(_.id == 1L).run
        succeed
    }

    // ── Leaf 6, .run on Delete returns Long ────────────────────────────────────

    "Delete.run returns Long" in {
        def shape(using Frame): Long < (Abort[SqlException] & DB) =
            Sql.delete[User].where(_.id == 1L).run
        succeed
    }

    // Negative form: each Statement subtype produces its own result shape, not Chunk[A]/Long swapped.

    "Insert.run does NOT return Long (negative form)" in {
        // Compile-time check: assigning Insert.run's result to a `Long` slot must fail.
        val errs = typeCheckErrors(
            """def shape(using kyo.Frame): Long < (kyo.Async & kyo.Abort[kyo.SqlException] & kyo.Scope) =
  kyo.Sql.insert[User].values(User(0L, "ada@example.com")).run"""
        )
        // The rejection must be the InsertOutcome-versus-Long mismatch, naming both types. Asserting only
        // that some error occurred would pass if the snippet stopped compiling for an unrelated reason.
        val message = errs.map(_.message).mkString(" ")
        assert(
            message.contains("InsertOutcome") && message.contains("Long"),
            s"the error must be the InsertOutcome-versus-Long mismatch, naming both types; got: $message"
        )
    }

    "Update.run does NOT return SqlClient.InsertOutcome (negative form)" in {
        val errs = typeCheckErrors(
            """def shape(using kyo.Frame): SqlClient.InsertOutcome < (kyo.Async & kyo.Abort[kyo.SqlException] & kyo.Scope) =
  kyo.Sql.update[User].set(_.email := "x").where(_.id == 1L).run"""
        )
        // The same mismatch read from the other end, so the same two type names must appear.
        val message = errs.map(_.message).mkString(" ")
        assert(
            message.contains("InsertOutcome") && message.contains("Long"),
            s"the error must be the Long-versus-InsertOutcome mismatch, naming both types; got: $message"
        )
    }

    // ── Leaf 7, the run family carries DB, so a statement with no client installed is a compile error ──
    //
    // There is no run-time form of this: DB.run is required to discharge the effect, so a statement with no client
    // supplied is caught at compile time. This pins that property; the general form of it lives in DBTest.

    "a .run does not typecheck unless a DB client is installed" in {
        typeCheckFailure(
            """val _ : Chunk[Person] < (Async & Abort[SqlException] & Scope) =
                 Sql.from[Person]("p").select(c => c.p.name).run"""
        )("DB")
    }

    // -- Leaf 8, internalExecuteQuery decodes via Schema (compile-time shape) --

    "SqlClient.internalExecuteQuery requires a SqlSchema and returns Chunk[A]" in {
        // internalExecuteQuery is `private[kyo]` so we can reference it directly from this in-package test.
        def shape(client: SqlClient)(using Frame): Chunk[Person] < (Async & Abort[SqlException]) =
            client.internalExecuteQuery[Person]("SELECT * FROM person", Chunk.empty, client.config)
        succeed
    }

    "internalExecuteQuery without SqlSchema in scope is a compile error" in {
        // Type `NoSchema2` deliberately lacks a SqlSchema given.
        val errs = typeCheckErrors(
            """class NoSchema2(x: Int)
def shape(client: kyo.SqlClient)(using kyo.Frame): kyo.Chunk[NoSchema2] < (kyo.Async & kyo.Abort[kyo.SqlException]) =
  client.internalExecuteQuery[NoSchema2]("SELECT 1", kyo.Chunk.empty, client.config)"""
        )
        // The snippet declares NoSchema2 inline and so depends on that name never colliding with a real
        // given. Naming SqlSchema in the assertion is what catches the day it does.
        val message = errs.map(_.message).mkString(" ")
        assert(
            message.contains("SqlSchema"),
            s"the error must be the missing SqlSchema given, not an unrelated rejection of NoSchema2; got: $message"
        )
    }

    // ── Leaf 10, every Statement subtype exposes all three extension methods ─

    "Query / Insert / Update / Delete each have all three extension methods" in {
        // Compile-time presence check via direct method calls. Frame is provided as a `using` parameter
        // so the inline macros expand cleanly inside the `kyo` package's Frame.derive gate.
        def queryRun(using Frame): Chunk[String] < (Abort[SqlException] & DB) =
            Sql.from[Person]("p").select(c => c.p.name).run
        def queryRunDyn(using Frame): Chunk[String] < (Abort[SqlException] & DB) =
            Sql.from[Person]("p").select(c => c.p.name).runDynamic
        def insertRun(using Frame): SqlClient.InsertOutcome < (Abort[SqlException] & DB) =
            Sql.insert[User].values(User(0L, "x")).run
        def insertRunDyn(using Frame): SqlClient.InsertOutcome < (Abort[SqlException] & DB) =
            Sql.insert[User].values(User(0L, "x")).runDynamic
        def updateRun(using Frame): Long < (Abort[SqlException] & DB) =
            Sql.update[User].set(_.email := "x").where(_.id == 1L).run
        def updateRunDyn(using Frame): Long < (Abort[SqlException] & DB) =
            Sql.update[User].set(_.email := "x").where(_.id == 1L).runDynamic
        def deleteRun(using Frame): Long < (Abort[SqlException] & DB) =
            Sql.delete[User].where(_.id == 1L).run
        def deleteRunDyn(using Frame): Long < (Abort[SqlException] & DB) =
            Sql.delete[User].where(_.id == 1L).runDynamic
        succeed
    }

    // ── Leaf 11, one call site serves both engines (lockstep) ─────────────────
    //
    // The surface property behind `.run`: a single call site is safe against either engine, because the fold
    // renders the statement once per dialect on the compile classpath and carries every result. Nothing about the
    // call site picks an engine; the client's own dialect id selects an entry at execution time.
    //
    // The divergence asserted here is statement flavor, not parameter syntax: identifier quoting (double quotes
    // against backticks) and the string-concatenation form (an infix operator against a variadic function). Both
    // spellings come from the same AST, so a fold that rendered once and reused the text could not produce them.

    "one call site's fold carries each engine's own statement flavor" in {
        val rendered = SqlStaticProbe.render(Sql.from[Person]("p").select(c => c.p.name ++ c.p.name))
        assert(rendered.sqlFor(Idiom.Id("postgres")).get == """SELECT ("p"."name" || "p"."name") FROM "person" "p"""")
        assert(rendered.sqlFor(Idiom.Id("mysql")).get == "SELECT CONCAT(`p`.`name`, `p`.`name`) FROM `person` `p`")
        assert(rendered.params.isEmpty, "neither flavor introduces a bind here, so the divergence is statement text alone")
    }

end SqlRunTest
