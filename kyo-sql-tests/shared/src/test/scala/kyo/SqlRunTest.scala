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
class SqlRunTest extends SqlBackendTest:

    // The two conformance groups at the end drive a live server once per available backend; every other leaf here is
    // compile-shape or pure. The suite timeout and the sequential container discipline come from SqlBackendTest.

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives SqlSchema
    case class User(id: Long, email: String) derives SqlSchema

    /** One column holding whichever server session wrote the row. See the routing leaf at the end of this suite. */
    case class Probe(pid: Long) derives SqlSchema

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

    // ── What `.run` actually decodes, once per available backend ──────────────
    //
    // The lockstep property is Leaf 11 above, and the schema a static bind carries is asserted by SqlRunStaticTest.
    // Leaf 12 is the routing group below.

    "Query.run returns a decoded Chunk[A]" - forEachBackend() { (_, client, _) =>
        // `deptId` is the one mixed-case column, so it is quoted through the live dialect: an unquoted `deptId` folds
        // to lowercase on a case-folding engine, and the case-preserving reference the renderer emits would then not
        // find it. The other identifiers are lowercase and need no quoting, and `name` is a portable VARCHAR.
        val ddl =
            "CREATE TABLE person (id BIGINT PRIMARY KEY, name VARCHAR(255) NOT NULL, age INT NOT NULL, " +
                s"${client.dialect.quoteIdent("deptId")} BIGINT NOT NULL)"
        client.executeRaw(ddl)
            .andThen(client.executeRaw("INSERT INTO person VALUES (1, 'ada', 36, 7)"))
            .andThen(client.executeRaw("INSERT INTO person VALUES (2, 'bob', 41, 7)"))
            .andThen {
                Sql.from[Person]("p").select(c => (c.p.name, c.p.age)).orderBy(c => c.p.id.asc).run.map { rows =>
                    assert(rows == Chunk(("ada", 36), ("bob", 41)), s"expected both rows decoded in id order, got $rows")
                }
            }
    }

    // ── Leaf 12, .run inside transaction { ... } runs on the transaction's connection ──
    //
    // The routing property behind `.run`: `SqlClient.routed` reads `txLocal` and uses the transaction's pinned
    // connection rather than leasing from the pool, which is the whole reason a `.run` inside a `transaction { ... }`
    // body needs no receiver.
    //
    // Asserted by session identity, not by the query succeeding. A `.run` that leased a fresh connection would still
    // return rows against a committed table and still satisfy a result-only assertion, so the fixture is built so
    // that only the pinned connection can produce the expected value:
    //
    //   - The server records which session wrote the row, and that is precisely what `Connection.id` carries for each
    //     adapter, so the row's value is comparable to the id on the context `txLocal` holds.
    //   - The row is uncommitted, so no other session can read it at all. A `.run` on a pooled connection reads
    //     Chunk.empty and fails on the count before it ever reaches the value.
    //
    // The second assertion closes the one way the first could pass while the routing is broken: if the INSERT had
    // autocommitted, any connection could read the row and report the inserting session's id. Reading through a
    // second client, with `txLocal` cleared so the read cannot be routed onto the pinned connection, is what makes
    // the invisibility a fact rather than an assumption.

    /** Asserts that a `.run` issued inside `client`'s transaction executes on that transaction's pinned connection.
      *
      * `sessionIdSql` is the engine's own name for the current session, which is the value `Connection.id` mirrors. `observer` is a second
      * client with its own pool, so a read issued through it cannot land on `client`'s connection.
      */
    private def assertRunUsesPinnedConnection(client: SqlClient, observer: SqlClient, sessionIdSql: String)(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Async & Abort[SqlException] & Scope & DB) =
        client.transaction {
            SqlClient.txLocal.use { active =>
                val pinned = active.getOrElse(fail("transaction must install a TransactionContext")).connection
                client.executeRaw(s"INSERT INTO probe VALUES ($sessionIdSql)").andThen {
                    Sql.from[Probe]("p").select(c => c.p.pid).run.flatMap { seen =>
                        SqlClient.txLocal.let(Absent)(observer.query("SELECT pid FROM probe")).map { outside =>
                            assert(
                                seen == Chunk(pinned.id),
                                s"Q.run must read the row the transaction's own session ${pinned.id} wrote, it read $seen"
                            )
                            assert(
                                outside.isEmpty,
                                s"the row must stay invisible to any other session while the transaction is open, one saw ${outside.size}"
                            )
                        }
                    }
                }
            }
        }

    "transaction { Q.run } runs on the transaction's connection, not a pooled one" - forEachBackend() { (backend, client, schema) =>
        // The SQL expression yielding the server's own session id has no cross-engine spelling, so the descriptor
        // names it through `backend.sessionIdSql`. `observer` is a second client with its own pool, opened from the
        // same fresh schema, so its reads cannot land on the transaction's pinned connection.
        val sessionIdSql = backend.sessionIdSql
        SqlClient.init(schema.url).flatMap { observer =>
            client.executeRaw("CREATE TABLE probe (pid BIGINT NOT NULL)").andThen {
                assertRunUsesPinnedConnection(client, observer, sessionIdSql)
            }
        }
    }

    // ── Leaf 13, the common path takes no client handle ───────────────────────
    //
    // `DB.executeRaw`, `DB.transaction`, and the fragment run forms all read the client the enclosing `DB.run`
    // installed, which is what lets a unit of work be written without threading a receiver through it. The group
    // runs them together rather than one leaf each, because what needs asserting is that they reach the SAME
    // connection and the same data: four statements that each work in isolation would still be four sessions.

    "the ambient common path: DB.executeRaw, fragment execute, and fragment run inside DB.transaction" - forEachBackend() {
        (backend, _, _) =>
            val first  = "first"
            val second = "second"
            for
                _ <- DB.executeRaw(s"CREATE TABLE note (body ${backend.textColumnType} NOT NULL)")
                affected <- DB.transaction {
                    sql"INSERT INTO note (body) VALUES ($first)".execute.flatMap { one =>
                        sql"INSERT INTO note (body) VALUES ($second)".execute.map(_ + one)
                    }
                }
                rows <- sql"SELECT body FROM note ORDER BY body".as[String].run
            yield
                assert(affected == 2L, s"each fragment execute reports its own affected-row count, got $affected")
                assert(rows == Chunk(first, second), s"a committed transaction's rows must be readable after it, got $rows")
            end for
    }

    // The other half of the transaction contract on the ambient form: an aborted body leaves nothing behind. Without
    // it, a `DB.transaction` that silently never opened one would pass the leaf above, since both inserts commit
    // either way.
    "DB.transaction rolls back its body's writes when the body aborts" - forEachBackend() { (backend, _, _) =>
        val doomed = "doomed"
        for
            _ <- DB.executeRaw(s"CREATE TABLE note (body ${backend.textColumnType} NOT NULL)")
            outcome <- Abort.run[SqlException] {
                DB.transaction {
                    // The second statement names a column the table does not have, so the server rejects it and the
                    // body aborts with a real failure rather than a fabricated one.
                    sql"INSERT INTO note (body) VALUES ($doomed)".execute.andThen(
                        DB.executeRaw("INSERT INTO note (missing_column) VALUES ('x')")
                    )
                }
            }
            rows <- sql"SELECT body FROM note".as[String].run
        yield
            assert(outcome.isFailure, s"the abort must reach the caller rather than being swallowed, got $outcome")
            assert(rows.isEmpty, s"the rolled-back insert must leave no row, got $rows")
        end for
    }

end SqlRunTest
