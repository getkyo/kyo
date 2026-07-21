package kyo

import kyo.Test

/** Unit tests for [[SqlCancelHandle]], structural hierarchy and pattern-match coverage. */
class SqlCancelHandleTest extends Test:

    "SqlCancelHandle.Pg pattern-matches correctly as sealed abstract class" in {
        // Verify exhaustive match on SqlCancelHandle after the sealed trait → sealed abstract class change.
        val pg: SqlCancelHandle = SqlCancelHandle.Pg(
            SqlAddress("postgres", "localhost", 5432, "testdb", "user"),
            Maybe.Absent,
            processId = 42,
            secretKey = 99
        )
        pg match
            case p: SqlCancelHandle.Pg =>
                assert(p.processId == 42)
                assert(p.secretKey == 99)
                succeed
            case _: SqlCancelHandle.My => fail("Expected Pg but got My")
        end match
    }

    "SqlCancelHandle.My pattern-matches correctly as sealed abstract class" in {
        val my: SqlCancelHandle = SqlCancelHandle.My(
            SqlAddress("mysql", "localhost", 3306, "testdb", "user"),
            connectionId = 7L
        )
        my match
            case _: SqlCancelHandle.Pg => fail("Expected My but got Pg")
            case m: SqlCancelHandle.My =>
                assert(m.connectionId == 7L)
                succeed
        end match
    }

    "SqlCancelHandle.address is accessible on both variants" in {
        val pg: SqlCancelHandle = SqlCancelHandle.Pg(
            SqlAddress("postgres", "localhost", 5432, "testdb", "user"),
            Maybe.Absent,
            processId = 1,
            secretKey = 2
        )
        val my: SqlCancelHandle = SqlCancelHandle.My(
            SqlAddress("mysql", "localhost", 3306, "testdb", "user"),
            connectionId = 3L
        )
        assert(pg.address.host == "localhost")
        assert(my.address.host == "localhost")
        succeed
    }

end SqlCancelHandleTest
