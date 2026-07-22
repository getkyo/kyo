package kyo

import kyo.Test

class SqlClientIsolationLevelTest extends Test:

    "SqlClient.IsolationLevel has all four standard levels" in {
        val levels = SqlClient.IsolationLevel.values.toList
        assert(levels.contains(SqlClient.IsolationLevel.ReadUncommitted))
        assert(levels.contains(SqlClient.IsolationLevel.ReadCommitted))
        assert(levels.contains(SqlClient.IsolationLevel.RepeatableRead))
        assert(levels.contains(SqlClient.IsolationLevel.Serializable))
    }

    "SqlClient.IsolationLevel equality works" in {
        assert(SqlClient.IsolationLevel.ReadCommitted == SqlClient.IsolationLevel.ReadCommitted)
        assert(SqlClient.IsolationLevel.Serializable != SqlClient.IsolationLevel.ReadCommitted)
    }

    "SqlClient.IsolationLevel pattern match is exhaustive" in {
        def name(level: SqlClient.IsolationLevel): String = level match
            case SqlClient.IsolationLevel.ReadUncommitted => "READ UNCOMMITTED"
            case SqlClient.IsolationLevel.ReadCommitted   => "READ COMMITTED"
            case SqlClient.IsolationLevel.RepeatableRead  => "REPEATABLE READ"
            case SqlClient.IsolationLevel.Serializable    => "SERIALIZABLE"

        assert(name(SqlClient.IsolationLevel.Serializable) == "SERIALIZABLE")
    }

end SqlClientIsolationLevelTest
