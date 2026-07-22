package kyo

import kyo.Test

/** Unit tests for [[SqlClient.CancelHandle]], structural hierarchy and pattern-match coverage. */
class SqlCancelHandleTest extends Test:

    "SqlClient.CancelHandle.Postgres pattern-matches correctly as sealed abstract class" in {
        // Verify exhaustive match on SqlClient.CancelHandle after the sealed trait → sealed abstract class change.
        val pg: SqlClient.CancelHandle = SqlClient.CancelHandle.Postgres(
            SqlConfig.Address("postgres", "localhost", 5432, "testdb", "user"),
            Maybe.Absent,
            processId = 42,
            secretKey = 99
        )
        pg match
            case p: SqlClient.CancelHandle.Postgres =>
                assert(p.processId == 42)
                assert(p.secretKey == 99)
                succeed
            case _: SqlClient.CancelHandle.Mysql => fail("Expected Pg but got My")
        end match
    }

    "SqlClient.CancelHandle.Mysql pattern-matches correctly as sealed abstract class" in {
        val my: SqlClient.CancelHandle = SqlClient.CancelHandle.Mysql(
            SqlConfig.Address("mysql", "localhost", 3306, "testdb", "user"),
            connectionId = 7L
        )
        my match
            case _: SqlClient.CancelHandle.Postgres => fail("Expected My but got Pg")
            case m: SqlClient.CancelHandle.Mysql =>
                assert(m.connectionId == 7L)
                succeed
        end match
    }

    "SqlClient.CancelHandle.address is accessible on both variants" in {
        val pg: SqlClient.CancelHandle = SqlClient.CancelHandle.Postgres(
            SqlConfig.Address("postgres", "localhost", 5432, "testdb", "user"),
            Maybe.Absent,
            processId = 1,
            secretKey = 2
        )
        val my: SqlClient.CancelHandle = SqlClient.CancelHandle.Mysql(
            SqlConfig.Address("mysql", "localhost", 3306, "testdb", "user"),
            connectionId = 3L
        )
        assert(pg.address.host == "localhost")
        assert(my.address.host == "localhost")
        succeed
    }

end SqlCancelHandleTest
