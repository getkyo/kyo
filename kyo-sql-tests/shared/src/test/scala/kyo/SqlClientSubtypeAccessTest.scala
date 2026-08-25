package kyo

import scala.compiletime.testing.typeChecks

/** Tests for the shape of [[SqlClient]]'s subclass hierarchy and for the typed accessors that reach a subclass.
  *
  * The engine a client talks to is carried by its concrete class, not by a type parameter, which is what makes
  * engine-specific members reachable without a cast. These scenarios pin both halves: the bracketed form does not
  * typecheck, and [[PostgresClient.use]] / [[MysqlClient.use]] hand their block the concrete subclass.
  */
class SqlClientSubtypeAccessTest extends Test:

    "sqlClientCarriesItsEngineInItsSubclassNotATypeParameter" in {
        // The compile-time evidence is the first half of the check: these two summons do not compile unless both
        // concrete classes extend SqlClient.
        summon[PostgresClient <:< SqlClient]
        summon[MysqlClient <:< SqlClient]
        // The second half: SqlClient takes no type parameter, so the bracketed form is rejected while the plain one is
        // accepted. Checking both directions distinguishes a rejected type argument from a snippet that fails for an
        // unrelated reason.
        assert(typeChecks("val x: SqlClient = ???"))
        assert(!typeChecks("val x: SqlClient[kyo.db.Idiom] = ???"))
    }

    // The "no client installed" case is a compile error now, since PostgresClient.use / MysqlClient.use are one-liners
    // over DB.clientAs and carry DB in their row, so there is no run-time failure to observe here; the typed
    // backend-mismatch failure (asking for the wrong engine) is pinned in DBTest against stub clients.

    "postgresClientUseBlockSeesAPostgresClientSoPostgresOnlyMembersCompile" in {
        // Compile-only: if PostgresClient.use passed the abstract SqlClient the notifications call below would not typecheck.
        // The block is never run (its value is discarded), so what is under test is its type.
        val prog: Chunk[PostgresClient.Notification] < (Async & Abort[SqlException] & Scope & DB) =
            PostgresClient.use { (p: PostgresClient) => p.notifications("chan").take(0).run }
        val _ = prog
        succeed
    }

    "mysqlClientUseBlockSeesAMysqlClient" in {
        // Compile-only: the (m: MysqlClient) ascription would fail if MysqlClient.use passed the abstract type.
        val prog: Int < (Abort[SqlException] & DB) =
            MysqlClient.use { (m: MysqlClient) => 42 }
        val _ = prog
        succeed
    }

end SqlClientSubtypeAccessTest
