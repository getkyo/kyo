package kyo

import kyo.Test

class SqlIsolationLevelTest extends Test:

    "SqlIsolationLevel has all four standard levels" in {
        val levels = SqlIsolationLevel.values.toList
        assert(levels.contains(SqlIsolationLevel.ReadUncommitted))
        assert(levels.contains(SqlIsolationLevel.ReadCommitted))
        assert(levels.contains(SqlIsolationLevel.RepeatableRead))
        assert(levels.contains(SqlIsolationLevel.Serializable))
    }

    "SqlIsolationLevel equality works" in {
        assert(SqlIsolationLevel.ReadCommitted == SqlIsolationLevel.ReadCommitted)
        assert(SqlIsolationLevel.Serializable != SqlIsolationLevel.ReadCommitted)
    }

    "SqlIsolationLevel pattern match is exhaustive" in {
        def name(level: SqlIsolationLevel): String = level match
            case SqlIsolationLevel.ReadUncommitted => "READ UNCOMMITTED"
            case SqlIsolationLevel.ReadCommitted   => "READ COMMITTED"
            case SqlIsolationLevel.RepeatableRead  => "REPEATABLE READ"
            case SqlIsolationLevel.Serializable    => "SERIALIZABLE"

        assert(name(SqlIsolationLevel.Serializable) == "SERIALIZABLE")
    }

end SqlIsolationLevelTest
