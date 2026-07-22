package kyo

import kyo.SqlAst.*
import kyo.SqlConnectionException
import scala.compiletime.testing.typeCheckErrors

/** Verifies the `.run` / `.runStatic` / `.runDynamic` extension surface on `Query[A]` / `Insert` / `Update` / `Delete`.
  *
  * Most container-integration leaves are marked `pending` until a container harness is available. The non-pending leaves verify the
  * macro/shape surface in-place:
  *
  *   - method availability (leaves 1, 4-6, compilation of typed `def shape(...)` bodies)
  *   - `runStatic` failing at compile time (leaf 3, `typeCheckErrors`)
  *   - `.run` outside a `let` block fails with `SqlConnectionException` (leaf 7)
  *   - `SqlClient.use` returns `Abort[SqlException]` and does not panic (leaf 8)
  */
class SqlRunTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives Schema
    case class User(id: Long, email: String) derives Schema

    // `.run` / `.runDynamic` on `Query[A]` require `SqlSchema[A]` for the decode path. Inside the `kyo`
    // package, opacity prevents the `derives Schema` given from satisfying `SqlSchema`; we register
    // explicit givens here so the call sites compile.
    inline given personSqlSchema: SqlSchema[Person] = SqlSchema.derived
    inline given userSqlSchema: SqlSchema[User]     = SqlSchema.derived

    // ── Leaf 1, .run available on Query[A] with the right effect row ──────────

    "Query.run compiles and has effect row Async & Abort[SqlException] & Scope" in {
        // Test by direct typed reference, the assertion is the compilation itself.
        def shape(using Frame): Chunk[String] < (Async & Abort[SqlException] & Scope) =
            Sql.from[Person]("p").select(c => c.p.name).run
        succeed
    }

    // ── Leaf 2, runDynamic works on a non-inline val Query ─────────────────────

    "Query.runDynamic compiles on a non-inline val" in {
        def shape(using Frame): Chunk[String] < (Async & Abort[SqlException] & Scope) =
            val q: Query[String] = Sql.from[Person]("p").select(c => c.p.name)
            q.runDynamic
        succeed
    }

    // ── Leaf 3, runStatic succeeds on a fully-static AST ──────────────────────
    //
    // Phase 7 wired runStatic to the SqlStaticMacro compile-time path. A fully-reducible inline query now compiles
    // successfully. The old "macro always rejects" assertion is replaced with:
    //  (a) positive: a static single-column select compiles and has the right effect row.
    //  (b) negative: runStatic on a non-literal `val` query still fails (non-inline, not reducible).

    "Query.runStatic compiles for a fully-static inline AST" in {
        // The macro now succeeds when the entire AST is liftable at compile time.
        def shape(using Frame): Chunk[String] < (Async & Abort[SqlException] & Scope) =
            Sql.from[Person]("p").select(c => c.p.name).runStatic
        succeed
    }

    "Query.runStatic fails at compile time for a non-inline val reference" in {
        // runStatic is defined only on `inline q: Query[A]`. Binding the query in a non-inline val
        // and then calling .runStatic is a compile error: the inline extension is not applicable.
        val errors = typeCheckErrors(
            """def shape(using kyo.Frame): kyo.Chunk[String] < (kyo.Async & kyo.Abort[kyo.SqlException] & kyo.Scope) = {
  val q: kyo.Query[String] = kyo.Sql.from[Person]("p").select(c => c.p.name)
  q.runStatic
}"""
        )
        assert(errors.nonEmpty)
    }

    // ── Leaf 4, .run on Insert returns SqlClient.InsertOutcome ────────────────────────────

    "Insert.run returns SqlClient.InsertOutcome" in {
        def shape(using Frame): SqlClient.InsertOutcome < (Async & Abort[SqlException] & Scope) =
            Sql.insert[User].values(User(0L, "ada@example.com")).run
        succeed
    }

    // ── Leaf 5, .run on Update returns Long ────────────────────────────────────

    "Update.run returns Long" in {
        def shape(using Frame): Long < (Async & Abort[SqlException] & Scope) =
            Sql.update[User].set(_.email := "x").where(_.id == 1L).run
        succeed
    }

    // ── Leaf 6, .run on Delete returns Long ────────────────────────────────────

    "Delete.run returns Long" in {
        def shape(using Frame): Long < (Async & Abort[SqlException] & Scope) =
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
        // Type mismatch (SqlClient.InsertOutcome vs Long), must produce at least one error.
        assert(errs.nonEmpty)
    }

    "Update.run does NOT return SqlClient.InsertOutcome (negative form)" in {
        val errs = typeCheckErrors(
            """def shape(using kyo.Frame): SqlClient.InsertOutcome < (kyo.Async & kyo.Abort[kyo.SqlException] & kyo.Scope) =
  kyo.Sql.update[User].set(_.email := "x").where(_.id == 1L).run"""
        )
        assert(errs.nonEmpty)
    }

    // ── Leaf 7, runDynamic reads the client from SqlClient.use correctly ──────

    "runDynamic surfaces SqlConnectionException when no client is active" in {
        val q = Sql.from[Person]("p").select(c => c.p.name)
        Abort.run[SqlException](q.runDynamic).map {
            case Result.Failure(_: SqlConnectionException) => succeed
            case other                                     => fail(s"Expected SqlConnectionException, got $other")
        }
    }

    // ── Leaf 8, SqlClient.use returns Abort.fail (not IllegalStateException) ──

    "SqlClient.use returns Abort.fail(SqlConnectionException) when no client active" in {
        Abort.run[SqlException](SqlClient.use(c => c.address)).map {
            case Result.Failure(_: SqlConnectionException) => succeed
            case other                                     => fail(s"Expected SqlConnectionException, got $other")
        }
    }

    // ── Leaf 9, executeBoundQuery decodes via Schema (compile-time shape) ─────

    "SqlClient.executeBoundQuery requires a SqlSchema and returns Chunk[A]" in {
        // executeBoundQuery is `private[kyo]` so we can reference it directly from this in-package test.
        def shape(client: SqlClient)(using Frame): Chunk[Person] < (Async & Abort[SqlException]) =
            client.executeBoundQuery[Person]("SELECT * FROM person", Chunk.empty)
        succeed
    }

    "executeBoundQuery without SqlSchema in scope is a compile error" in {
        // Type `NoSchema2` deliberately lacks a SqlSchema given.
        val errs = typeCheckErrors(
            """class NoSchema2(x: Int)
def shape(client: kyo.SqlClient)(using kyo.Frame): kyo.Chunk[NoSchema2] < (kyo.Async & kyo.Abort[kyo.SqlException]) =
  client.executeBoundQuery[NoSchema2]("SELECT 1", kyo.Chunk.empty)"""
        )
        assert(errs.nonEmpty)
    }

    // ── Leaf 10, every Statement subtype exposes all three extension methods ─

    "Query / Insert / Update / Delete each have all three extension methods" in {
        // Compile-time presence check via direct method calls. Frame is provided as a `using` parameter
        // so the inline macros expand cleanly inside the `kyo` package's Frame.derive gate.
        def queryRun(using Frame): Chunk[String] < (Async & Abort[SqlException] & Scope) =
            Sql.from[Person]("p").select(c => c.p.name).run
        def queryRunDyn(using Frame): Chunk[String] < (Async & Abort[SqlException] & Scope) =
            Sql.from[Person]("p").select(c => c.p.name).runDynamic
        def insertRun(using Frame): SqlClient.InsertOutcome < (Async & Abort[SqlException] & Scope) =
            Sql.insert[User].values(User(0L, "x")).run
        def insertRunDyn(using Frame): SqlClient.InsertOutcome < (Async & Abort[SqlException] & Scope) =
            Sql.insert[User].values(User(0L, "x")).runDynamic
        def updateRun(using Frame): Long < (Async & Abort[SqlException] & Scope) =
            Sql.update[User].set(_.email := "x").where(_.id == 1L).run
        def updateRunDyn(using Frame): Long < (Async & Abort[SqlException] & Scope) =
            Sql.update[User].set(_.email := "x").where(_.id == 1L).runDynamic
        def deleteRun(using Frame): Long < (Async & Abort[SqlException] & Scope) =
            Sql.delete[User].where(_.id == 1L).run
        def deleteRunDyn(using Frame): Long < (Async & Abort[SqlException] & Scope) =
            Sql.delete[User].where(_.id == 1L).runDynamic
        succeed
    }

    // ── Container-integration leaves (deferred, require live container) ───────
    //
    // The plan calls for live-container assertions:
    //   #PG-1   Sql.from[Person]("p").select(c => c.p.name).run on PG container → Chunk[String]
    //   #MY-2   Same on MySQL container                                          → Chunk[String]
    //   #LK-10  Same inline def q rendered through let(pgClient) vs let(mysqlClient), assert two
    //           backend-flavoured strings (lockstep enforcement)
    //   #TX-11  .run inside transaction { ... } uses the bound connection
    //   #SR-13  .runStatic on a select with a literal carries the schema correctly via SqlSchema.BoundValue[?]
    //   #BC-14  .runDynamic does not allocate a kyo.internal.SqlBackendSql (bytecode inspection, doc-level)
    //
    // These leaves require a container harness (PG + MySQL test containers) plus, for SR-13,
    // the static FromExpr reducer. See the prep doc for the full mapping.

    "PG container: Query.run returns decoded Chunk[A]".ignore("pending") - {}
    "MySQL container: Query.run returns decoded Chunk[A]".ignore("pending") - {}
    "Lockstep: same inline def emits PG flavor under let(pgClient) and MySQL flavor under let(mysqlClient)".ignore("pending") - {}
    "transaction { Q.run } participates in the bound transaction (txLocal carries the connection)".ignore("pending") - {}
    "runStatic on a select with a literal carries the schema correctly".ignore("pending") - {}
    "runDynamic does not allocate a kyo.internal.SqlBackendSql (bytecode inspection)".ignore("pending") - {}

end SqlRunTest
