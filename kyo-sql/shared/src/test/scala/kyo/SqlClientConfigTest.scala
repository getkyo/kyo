package kyo

import kyo.*
import kyo.Test

class SqlClientConfigTest extends Test:
    "SqlClientConfig defaults are sane" in {
        val cfg = SqlClientConfig.default
        assert(cfg.maxConnections > 0)
        assert(cfg.acquireTimeout > Duration.Zero)
        assert(cfg.queryTimeout > Duration.Zero)
        assert(cfg.idleTimeout > Duration.Zero)
        assert(cfg.retrySchedule == Absent)
    }

    "SqlClientConfig default maxConnections is reasonable" in {
        assert(SqlClientConfig.default.maxConnections == 10)
    }

    "SqlClientConfig retrySchedule is Absent by default, opt-in retries" in {
        assert(SqlClientConfig.default.retrySchedule == Absent)
    }

    "SqlClientConfig retrySchedule Present is accessible" in {
        val schedule = Schedule.fixed(100.millis).take(2)
        val cfg = SqlClientConfig(
            maxConnections = 5,
            acquireTimeout = 3.seconds,
            queryTimeout = 60.seconds,
            idleTimeout = 5.minutes,
            retrySchedule = Present(schedule)
        )
        assert(cfg.maxConnections == 5)
        assert(cfg.retrySchedule == Present(schedule))
    }

    "SqlClientConfig.default typeNames is Set.empty" in {
        assert(SqlClientConfig.default.typeNames == Set.empty)
    }

    "SqlClientConfig.copy preserves typeNames" in {
        val cfg = SqlClientConfig.default.copy(typeNames = Set("hstore", "geometry"))
        assert(cfg.typeNames == Set("hstore", "geometry"))
        val cfg2 = cfg.copy(maxConnections = 5)
        assert(cfg2.typeNames == Set("hstore", "geometry"))
    }

    // Sanitization is checked at SqlClient.init time. A fake URL is used so the error fires before
    // any network I/O (sanitization runs before mergeConfig and backend initialization).

    "SqlClient.init rejects type name containing single quote" in {
        val config = SqlClientConfig.default.copy(typeNames = Set("foo'bar"))
        Abort.run[SqlException.Connection](
            SqlClient.init("postgres://user:pass@127.0.0.1:9999/db", config)
        ).map {
            case Result.Failure(e) =>
                assert(e.message.contains("single-quote") || e.message.contains("must not contain"))
                assert(e.message.contains("foo'bar") || e.message.contains("invalid"))
                succeed
            case other =>
                fail(s"Expected Failure(SqlException.Connection) but got $other")
        }
    }

    "SqlClient.init rejects type name containing backslash" in {
        val config = SqlClientConfig.default.copy(typeNames = Set("foo\\bar"))
        Abort.run[SqlException.Connection](
            SqlClient.init("postgres://user:pass@127.0.0.1:9999/db", config)
        ).map {
            case Result.Failure(e) =>
                assert(e.message.contains("must not contain") || e.message.contains("backslash") || e.message.contains("invalid"))
                succeed
            case other =>
                fail(s"Expected Failure(SqlException.Connection) but got $other")
        }
    }

    // Verify that the zero-config overload (init(url)) and the explicit-config overload (init(url, config))
    // are equivalent: both fail with SqlException when connecting to a non-existent host, and both
    // produce the same failure type. The test fails if the two overloads have different observable behavior.
    "SqlClient.init zero-config and explicit-config overloads are equivalent" in {
        val url = "postgres://user:pass@127.0.0.1:9999/db"
        // Both forms must compile. Neither can reach a real DB (bad host), so both must fail.
        Abort.run[SqlException](Scope.run(SqlClient.init(url))).flatMap { r1 =>
            Abort.run[SqlException](Scope.run(SqlClient.init(url, SqlClientConfig.default))).map { r2 =>
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
                            s"SqlClient.init(url) and SqlClient.init(url, SqlClientConfig.default) behaved differently:\n" +
                                s"  zeroConfig    = $left\n" +
                                s"  explicitConfig = $right"
                        )
            }
        }
    }

    // ---- Phase 23: pool lifecycle fields ----

    "default config has expected defaults for each new field" in {
        val cfg = SqlClientConfig.default
        assert(cfg.maxLifetime == Absent)
        assert(cfg.connectionTestQuery == Absent)
        assert(cfg.connectionInitSql == Absent)
        assert(cfg.keepaliveTime == Absent)
        assert(cfg.connectTimeout == 30.seconds)
        assert(cfg.socketTimeout == Absent)
        assert(cfg.leakDetectionThreshold == Absent)
        assert(cfg.connectionInitTimeout == 30.seconds)
    }

    "config with maxLifetime serializes/round-trips through copy" in {
        val lifetime = 10.minutes
        val cfg      = SqlClientConfig.default.copy(maxLifetime = Present(lifetime))
        assert(cfg.maxLifetime == Present(lifetime))
        val cfg2 = cfg.copy(maxConnections = 5)
        assert(cfg2.maxLifetime == Present(lifetime))
        assert(cfg2.maxConnections == 5)
    }

    "maxLifetime field: explicit construction and access" in {
        val cfg = SqlClientConfig.default.copy(maxLifetime = Present(1.hour))
        assert(cfg.maxLifetime == Present(1.hour))
    }

    "connectionTestQuery field: explicit construction and access" in {
        val cfg = SqlClientConfig.default.copy(connectionTestQuery = Present("SELECT 1"))
        assert(cfg.connectionTestQuery == Present("SELECT 1"))
    }

    "connectionInitSql field: explicit construction and access" in {
        val cfg = SqlClientConfig.default.copy(connectionInitSql = Present("SET search_path = myschema"))
        assert(cfg.connectionInitSql == Present("SET search_path = myschema"))
    }

    "keepaliveTime field: explicit construction and access" in {
        val cfg = SqlClientConfig.default.copy(keepaliveTime = Present(30.seconds))
        assert(cfg.keepaliveTime == Present(30.seconds))
    }

    "connectTimeout field: explicit construction and access" in {
        val cfg = SqlClientConfig.default.copy(connectTimeout = 15.seconds)
        assert(cfg.connectTimeout == 15.seconds)
        val cfg2 = SqlClientConfig.default
        assert(cfg2.connectTimeout == 30.seconds)
    }

    "socketTimeout field: explicit construction and access" in {
        val cfg = SqlClientConfig.default.copy(socketTimeout = Present(5.seconds))
        assert(cfg.socketTimeout == Present(5.seconds))
    }

    "leakDetectionThreshold field: explicit construction and access" in {
        val cfg = SqlClientConfig.default.copy(leakDetectionThreshold = Present(2.minutes))
        assert(cfg.leakDetectionThreshold == Present(2.minutes))
    }

    "connectionInitTimeout field: explicit construction and access" in {
        val cfg = SqlClientConfig.default.copy(connectionInitTimeout = 60.seconds)
        assert(cfg.connectionInitTimeout == 60.seconds)
        val cfg2 = SqlClientConfig.default
        assert(cfg2.connectionInitTimeout == 30.seconds)
    }

    "all new lifecycle fields can be set simultaneously" in {
        val cfg = SqlClientConfig.default.copy(
            maxLifetime = Present(30.minutes),
            connectionTestQuery = Present("DO 1"),
            connectionInitSql = Present("SET time_zone = '+00:00'"),
            keepaliveTime = Present(1.minute),
            connectTimeout = 10.seconds,
            socketTimeout = Present(20.seconds),
            leakDetectionThreshold = Present(5.minutes),
            connectionInitTimeout = 45.seconds
        )
        assert(cfg.maxLifetime == Present(30.minutes))
        assert(cfg.connectionTestQuery == Present("DO 1"))
        assert(cfg.connectionInitSql == Present("SET time_zone = '+00:00'"))
        assert(cfg.keepaliveTime == Present(1.minute))
        assert(cfg.connectTimeout == 10.seconds)
        assert(cfg.socketTimeout == Present(20.seconds))
        assert(cfg.leakDetectionThreshold == Present(5.minutes))
        assert(cfg.connectionInitTimeout == 45.seconds)
    }

    // ---- Phase 25: closeGrace field ----

    "closeGrace defaults to 30 seconds" in {
        assert(SqlClientConfig.default.closeGrace == 30.seconds)
    }

    "closeGrace field: explicit construction and access" in {
        val cfg = SqlClientConfig.default.copy(closeGrace = 5.seconds)
        assert(cfg.closeGrace == 5.seconds)
    }

    "closeGrace field preserves through copy" in {
        val cfg  = SqlClientConfig.default.copy(closeGrace = 10.seconds)
        val cfg2 = cfg.copy(maxConnections = 3)
        assert(cfg2.closeGrace == 10.seconds)
        assert(cfg2.maxConnections == 3)
    }

    // ---- Phase 27: streamBatchSize + copyOutCleanupTimeout fields ----

    "streamBatchSize default is 64" in {
        assert(SqlClientConfig.default.streamBatchSize == 64)
    }

    "copyOutCleanupTimeout default is 5 seconds" in {
        assert(SqlClientConfig.default.copyOutCleanupTimeout == 5.seconds)
    }

    "custom streamBatchSize is preserved through copy" in {
        val cfg = SqlClientConfig.default.copy(streamBatchSize = 500)
        assert(cfg.streamBatchSize == 500)
        val cfg2 = cfg.copy(maxConnections = 3)
        assert(cfg2.streamBatchSize == 500)
        assert(cfg2.maxConnections == 3)
    }

    "custom copyOutCleanupTimeout is preserved through copy" in {
        val cfg = SqlClientConfig.default.copy(copyOutCleanupTimeout = 10.seconds)
        assert(cfg.copyOutCleanupTimeout == 10.seconds)
        val cfg2 = cfg.copy(maxConnections = 5)
        assert(cfg2.copyOutCleanupTimeout == 10.seconds)
        assert(cfg2.maxConnections == 5)
    }

end SqlClientConfigTest
