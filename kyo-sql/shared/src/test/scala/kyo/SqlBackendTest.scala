package kyo

import kyo.Test

class SqlBackendTest extends Test:

    "SqlBackend.Postgres is a subtype of SqlBackend" in {
        // Verify at compile time that the phantom hierarchy is correct.
        // If SqlBackend.Postgres did not extend SqlBackend this would not compile.
        val evidence: SqlBackend.Postgres <:< SqlBackend = summon[SqlBackend.Postgres <:< SqlBackend]
        assert(evidence != null)
    }

    "SqlBackend.Mysql is a subtype of SqlBackend" in {
        val evidence: SqlBackend.Mysql <:< SqlBackend = summon[SqlBackend.Mysql <:< SqlBackend]
        assert(evidence != null)
    }

    "SqlClient has no type parameter — bracketed form is a compile error" in {
        // The phantom `[B <: SqlBackend]` type param is gone. Writing `SqlClient[SqlBackend.Postgres]`
        // must no longer compile; typeCheckErrors captures that statically.
        val errors = compiletime.testing.typeCheckErrors("val x: SqlClient[SqlBackend.Postgres] = ???")
        assert(errors.nonEmpty)
    }

    "SqlClient.Postgres / SqlClient.Mysql aliases are removed" in {
        // The plan deletes the type aliases. Referencing them must be a compile error.
        val errsPg = compiletime.testing.typeCheckErrors("val x: SqlClient.Postgres = ???")
        val errsMy = compiletime.testing.typeCheckErrors("val x: SqlClient.Mysql = ???")
        assert(errsPg.nonEmpty)
        assert(errsMy.nonEmpty)
    }

    "SqlClient.usePostgres fails with SqlException.Connection when no client is active" in {
        // Runtime gate: usePostgres requires an active client. With none, fail with Connection.
        Abort.run[SqlException](SqlClient.usePostgres(_ => 1)).map {
            case Result.Failure(_: SqlException.Connection) => succeed
            case other                                      => fail(s"Expected SqlException.Connection, got $other")
        }
    }

    "SqlClient.useMysql fails with SqlException.Connection when no client is active" in {
        Abort.run[SqlException](SqlClient.useMysql(_ => 1)).map {
            case Result.Failure(_: SqlException.Connection) => succeed
            case other                                      => fail(s"Expected SqlException.Connection, got $other")
        }
    }

    "SqlBackend pattern-matches correctly as sealed abstract class" in {
        // Verify that the exhaustive match still compiles and dispatches correctly
        // after SqlBackend / Postgres / Mysql changed from sealed trait to sealed abstract class.
        val pg: SqlBackend = SqlBackend.Postgres
        val my: SqlBackend = SqlBackend.Mysql
        def describe(b: SqlBackend): String = b match
            case _: SqlBackend.Postgres => "postgres"
            case _: SqlBackend.Mysql    => "mysql"
        assert(describe(pg) == "postgres")
        assert(describe(my) == "mysql")
        succeed
    }

end SqlBackendTest
