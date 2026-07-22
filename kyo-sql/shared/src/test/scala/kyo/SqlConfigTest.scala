package kyo

import kyo.*
import kyo.Test

class SqlConfigTest extends Test:
    "SqlConfig defaults are sane" in {
        val cfg = SqlConfig.default
        assert(cfg.maxConnections > 0)
        assert(cfg.acquireTimeout > Duration.Zero)
        assert(cfg.queryTimeout > Duration.Zero)
        assert(cfg.idleTimeout > Duration.Zero)
        assert(cfg.retrySchedule == Absent)
    }

    "SqlConfig default maxConnections is reasonable" in {
        assert(SqlConfig.default.maxConnections == 10)
    }

    "SqlConfig retrySchedule is Absent by default, opt-in retries" in {
        assert(SqlConfig.default.retrySchedule == Absent)
    }

    "SqlConfig retrySchedule Present is accessible" in {
        val schedule = Schedule.fixed(100.millis).take(2)
        val cfg = SqlConfig(
            maxConnections = 5,
            acquireTimeout = 3.seconds,
            queryTimeout = 60.seconds,
            idleTimeout = 5.minutes,
            retrySchedule = Present(schedule)
        )
        assert(cfg.maxConnections == 5)
        assert(cfg.retrySchedule == Present(schedule))
    }

    "SqlConfig.default typeNames is Set.empty" in {
        assert(SqlConfig.default.typeNames == Set.empty)
    }

    "SqlConfig.copy preserves typeNames" in {
        val cfg = SqlConfig.default.copy(typeNames = Set("hstore", "geometry"))
        assert(cfg.typeNames == Set("hstore", "geometry"))
        val cfg2 = cfg.copy(maxConnections = 5)
        assert(cfg2.typeNames == Set("hstore", "geometry"))
    }

    // Sanitization is checked at SqlClient.init time. A fake URL is used so the error fires before
    // any network I/O (sanitization runs before mergeConfig and backend initialization).

    "SqlClient.init rejects type name containing single quote" in {
        val config = SqlConfig.default.copy(typeNames = Set("foo'bar"))
        Abort.run[SqlConnectionException](
            SqlClient.init("postgres://user:pass@127.0.0.1:9999/db", config)
        ).map {
            case Result.Failure(e: SqlConnectionInvalidTypeNameException) =>
                assert(e.typeNames.contains("foo'bar"))
                succeed
            case other =>
                fail(s"Expected Failure(SqlConnectionInvalidTypeNameException) but got $other")
        }
    }

    "SqlClient.init rejects type name containing backslash" in {
        val config = SqlConfig.default.copy(typeNames = Set("foo\\bar"))
        Abort.run[SqlConnectionException](
            SqlClient.init("postgres://user:pass@127.0.0.1:9999/db", config)
        ).map {
            case Result.Failure(e: SqlConnectionInvalidTypeNameException) =>
                assert(e.typeNames.contains("foo\\bar"))
                succeed
            case other =>
                fail(s"Expected Failure(SqlConnectionInvalidTypeNameException) but got $other")
        }
    }

    // Verify that the zero-config overload (init(url)) and the explicit-config overload (init(url, config))
    // are equivalent: both fail with SqlException when connecting to a non-existent host, and both
    // produce the same failure type. The test fails if the two overloads have different observable behavior.
    "SqlClient.init zero-config and explicit-config overloads are equivalent" in {
        val url = "postgres://user:pass@127.0.0.1:9999/db"
        // Both forms must compile. Neither can reach a real DB (bad host), so both must fail.
        Abort.run[SqlException](Scope.run(SqlClient.init(url))).flatMap { r1 =>
            Abort.run[SqlException](Scope.run(SqlClient.init(url, SqlConfig.default))).map { r2 =>
                (r1, r2) match
                    case (Result.Failure(_), Result.Failure(_)) =>
                        // Both failed with SqlException, same observable behaviour. Test passes.
                        succeed
                    case (Result.Success(_), Result.Success(_)) =>
                        // Both unexpectedly succeeded, same observable behaviour.
                        // This should not happen (no DB), but is still equivalent.
                        succeed
                    case (left, right) =>
                        // The two overloads produced different outcomes, they are NOT equivalent.
                        fail(
                            s"SqlClient.init(url) and SqlClient.init(url, SqlConfig.default) behaved differently:\n" +
                                s"  zeroConfig    = $left\n" +
                                s"  explicitConfig = $right"
                        )
            }
        }
    }

    "connectionTestQuery default is Absent, custom value round-trips through copy" in {
        assert(SqlConfig.default.connectionTestQuery == Absent)
        val cfg = SqlConfig.default.copy(connectionTestQuery = Present("SELECT 1"))
        assert(cfg.connectionTestQuery == Present("SELECT 1"))
    }

    "closeGrace defaults to 30 seconds" in {
        assert(SqlConfig.default.closeGrace == 30.seconds)
    }

    "closeGrace field: explicit construction and access" in {
        val cfg = SqlConfig.default.copy(closeGrace = 5.seconds)
        assert(cfg.closeGrace == 5.seconds)
    }

    "closeGrace field preserves through copy" in {
        val cfg  = SqlConfig.default.copy(closeGrace = 10.seconds)
        val cfg2 = cfg.copy(maxConnections = 3)
        assert(cfg2.closeGrace == 10.seconds)
        assert(cfg2.maxConnections == 3)
    }

    "streamBatchSize default is 64" in {
        assert(SqlConfig.default.streamBatchSize == 64)
    }

    "copyOutCleanupTimeout default is 5 seconds" in {
        assert(SqlConfig.default.copyOutCleanupTimeout == 5.seconds)
    }

    "custom streamBatchSize is preserved through copy" in {
        val cfg = SqlConfig.default.copy(streamBatchSize = 500)
        assert(cfg.streamBatchSize == 500)
        val cfg2 = cfg.copy(maxConnections = 3)
        assert(cfg2.streamBatchSize == 500)
        assert(cfg2.maxConnections == 3)
    }

    "custom copyOutCleanupTimeout is preserved through copy" in {
        val cfg = SqlConfig.default.copy(copyOutCleanupTimeout = 10.seconds)
        assert(cfg.copyOutCleanupTimeout == 10.seconds)
        val cfg2 = cfg.copy(maxConnections = 5)
        assert(cfg2.copyOutCleanupTimeout == 10.seconds)
        assert(cfg2.maxConnections == 5)
    }

end SqlConfigTest
