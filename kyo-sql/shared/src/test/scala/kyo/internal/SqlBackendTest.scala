package kyo.internal

import kyo.*
import kyo.Test

class SqlBackendTest extends Test:

    "kyo.internal.SqlBackend.Postgres is a subtype of kyo.internal.SqlBackend" in {
        // Verify at compile time that the phantom hierarchy is correct.
        // If kyo.internal.SqlBackend.Postgres did not extend kyo.internal.SqlBackend this would not compile.
        val evidence: kyo.internal.SqlBackend.Postgres <:< kyo.internal.SqlBackend =
            summon[kyo.internal.SqlBackend.Postgres <:< kyo.internal.SqlBackend]
        assert(evidence != null)
    }

    "kyo.internal.SqlBackend.Mysql is a subtype of kyo.internal.SqlBackend" in {
        val evidence: kyo.internal.SqlBackend.Mysql <:< kyo.internal.SqlBackend =
            summon[kyo.internal.SqlBackend.Mysql <:< kyo.internal.SqlBackend]
        assert(evidence != null)
    }

    "SqlClient has no type parameter, bracketed form is a compile error" in {
        // The phantom `[B <: kyo.internal.SqlBackend]` type param is gone. Writing `SqlClient[kyo.internal.SqlBackend.Postgres]`
        // must no longer compile; typeCheckErrors captures that statically.
        val errors = compiletime.testing.typeCheckErrors("val x: SqlClient[kyo.internal.SqlBackend.Postgres] = ???")
        assert(errors.nonEmpty)
    }

    "SqlClient.Postgres and SqlClient.Mysql are subtypes of SqlClient" in {
        // After nesting the concrete client classes under the SqlClient companion, the fully-qualified
        // names must resolve as concrete final classes extending SqlClient.
        val evPg: SqlClient.Postgres <:< SqlClient = summon[SqlClient.Postgres <:< SqlClient]
        val evMy: SqlClient.Mysql <:< SqlClient    = summon[SqlClient.Mysql <:< SqlClient]
        assert(evPg != null && evMy != null)
    }

    "SqlClient.usePostgres fails with SqlConnectionException when no client is active" in {
        // Runtime gate: usePostgres requires an active client. With none, fail with Connection.
        Abort.run[SqlException](SqlClient.usePostgres(_ => 1)).map {
            case Result.Failure(_: SqlConnectionException) => succeed
            case other                                     => fail(s"Expected SqlConnectionException, got $other")
        }
    }

    "SqlClient.useMysql fails with SqlConnectionException when no client is active" in {
        Abort.run[SqlException](SqlClient.useMysql(_ => 1)).map {
            case Result.Failure(_: SqlConnectionException) => succeed
            case other                                     => fail(s"Expected SqlConnectionException, got $other")
        }
    }

    "kyo.internal.SqlBackend pattern-matches correctly as sealed abstract class" in {
        // Verify that the exhaustive match still compiles and dispatches correctly
        // after kyo.internal.SqlBackend / Postgres / Mysql changed from sealed trait to sealed abstract class.
        val pg: kyo.internal.SqlBackend = kyo.internal.SqlBackend.Postgres
        val my: kyo.internal.SqlBackend = kyo.internal.SqlBackend.Mysql
        def describe(b: kyo.internal.SqlBackend): String = b match
            case _: kyo.internal.SqlBackend.Postgres => "postgres"
            case _: kyo.internal.SqlBackend.Mysql    => "mysql"
        assert(describe(pg) == "postgres")
        assert(describe(my) == "mysql")
        succeed
    }

end SqlBackendTest
