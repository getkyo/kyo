package kyo.internal

import kyo.*
import kyo.internal.postgres.PostgresConnection

/** The postgres implementation of [[SqlTestBackend]]: the descriptor the backend-agnostic conformance battery runs against a live PostgreSQL
  * server.
  *
  * This is the one place the postgres provisioning literals live by design: the `postgres` scheme, the `ContainerPredef.Postgres` fixture, the
  * `"`-doubling identifier quoting, and the `CREATE`/`DROP DATABASE` SQL. kyo-sql-tests names no engine and reaches this behavior only through
  * [[withFreshSchema]] and the capability flags, so the coordinates a conformance body sees are engine-free.
  *
  * The container is shared, not per-test: [[withFreshSchema]] memoizes one postgres container per process through
  * [[SqlTestContainers.getOrInit]] over the core [[SqlTestContainers.containers]] table, keyed by the descriptor id `"postgres"`, so a container
  * inited here shares the single entry with any other caller for that id. Each leaf then provisions a fresh database inside that shared
  * container and drops it on scope exit, so leaves never collide yet pay the container start once.
  *
  * Discovered by the test-backend service scan: a plain class with a public no-arg constructor named in
  * `META-INF/services/kyo.internal.SqlTestBackend`, mirroring how `kyo.internal.postgres.PostgresBackendFactory` is discovered as a production
  * `kyo.db.Backend`.
  */
class PostgresTestBackend extends SqlTestBackend:

    def id: String = "postgres"

    def label: String = "postgres"

    def urlScheme: String = "postgres"

    def containerConfig: Container.Config =
        ContainerPredef.Postgres.buildContainerConfig(ContainerPredef.Postgres.Config.default)

    // Matches PostgresDialect.quoteIdent: wrap in double quotes and double any embedded quote.
    def quoteIdent(name: String): String =
        val escaped = name.replace("\"", "\"\"")
        s""""$escaped""""
    end quoteIdent

    def supportsReturning: Boolean = true

    def supportsRecursiveCte: Boolean = true

    def textColumnType: String = "TEXT"

    // BIGSERIAL (int8), not SERIAL (int4): the generated-key path decodes the returned key as a Long, so the
    // auto-increment column must be eight bytes to round-trip, matching the mysql descriptor's BIGINT AUTO_INCREMENT.
    def autoIncrementPrimaryKey: String = "BIGSERIAL PRIMARY KEY"

    def columnType(key: SqlTestBackend.ColumnType): String =
        import SqlTestBackend.ColumnType.*
        key match
            case SmallInt         => "SMALLINT"
            case Int              => "INTEGER"
            case BigInt           => "BIGINT"
            case Numeric          => "NUMERIC(38,10)"
            case Boolean          => "BOOLEAN"
            case Float32          => "REAL"
            case Float64          => "DOUBLE PRECISION"
            case Bytes            => "BYTEA"
            case Uuid             => "UUID"
            case Date             => "DATE"
            case Time             => "TIME"
            case TimeWithOffset   => "TIMETZ"
            case DateTime         => "TIMESTAMP"
            case Timestamp        => "TIMESTAMPTZ"
            case CalendarInterval => "INTERVAL"
            case Duration         => "INTERVAL"
            case Json             => "JSONB"
            case IntArray         => "INTEGER[]"
            case TextArray        => "TEXT[]"
            case JsonArray        => "JSONB[]"
        end match
    end columnType

    def tableNotFoundSqlState: String = "42P01"

    def uniqueViolationSqlState: String = "23505"

    def sessionIdSql: String = "pg_backend_pid()"

    def withFreshSchema[A, S](f: SqlTestBackend.Schema => A < S)(using
        Frame
    ): A < (S & Async & Abort[SqlException | ContainerException] & Scope) =
        // Scope kyo-pod's podman/docker HttpClient to the leaf. Without this, the ambient process-shared
        // HttpClient's 60-second idle-connection pool accumulates one unix socket per (mappedPort, wait,
        // remove) call and trips the end-of-run file-descriptor leak check on Linux CI.
        HttpClient.init().flatMap(scopedClient => HttpClient.let(scopedClient)(withFreshSchemaBody(f)))

    private def withFreshSchemaBody[A, S](f: SqlTestBackend.Schema => A < S)(using
        Frame
    ): A < (S & Async & Abort[SqlException | ContainerException] & Scope) =
        val predefCfg = ContainerPredef.Postgres.Config.default
        for
            container <- SqlTestContainers.getOrInit(SqlTestContainers.containers, "postgres")(
                SqlTestContainers.initSingleton(ContainerPredef.Postgres.buildContainerConfig(predefCfg), "postgres")
            )
            port   <- container.mappedPort(predefCfg.port)
            schema <- freshSchemaName
            host = container.host
            // Admin connection: connect to the default DB ("test"); used for CREATE/DROP DATABASE.
            admin <- PostgresConnection.connect(
                host,
                port,
                predefCfg.username,
                predefCfg.database,
                Present(predefCfg.password),
                Absent,
                64,
                Duration.Infinity
            )
            _ <- Scope.ensure(Abort.run(admin.terminate).unit)
            _ <- admin.simpleExecute(s"""CREATE DATABASE "$schema"""")
            _ <- Scope.ensure(Abort.run(dropSchema(admin, schema)).unit)
            // Per-test connection: superuser "test" against the freshly-created schema.
            test <- PostgresConnection.connect(
                host,
                port,
                predefCfg.username,
                schema,
                Present(predefCfg.password),
                Absent,
                64,
                Duration.Infinity
            )
            _ <- Scope.ensure(Abort.run(test.terminate).unit)
            url = s"postgres://${predefCfg.username}:${predefCfg.password}@$host:$port/$schema"
            ctx = SqlTestBackend.Schema(host, port, predefCfg.username, predefCfg.password, schema, url)
            result <- f(ctx)
        yield result
        end for
    end withFreshSchemaBody

    private def freshSchemaName(using Frame): String < Sync =
        Random.nextLong.map(v => s"test_${(v & Long.MaxValue).toHexString}")

    // DROP DATABASE retries on SQLSTATE 55006 ("object in use"): the just-terminated per-test connection's backend can
    // still be winding down when DROP runs, and PostgreSQL refuses to drop a database another session is attached to.
    private def dropSchema(
        admin: PostgresConnection,
        schema: String
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        def attempt(remaining: Int): Unit < (Async & Abort[SqlException]) =
            Abort.run[SqlException](
                admin.simpleExecute(s"""DROP DATABASE IF EXISTS "$schema"""")
            ).flatMap {
                case Result.Success(_) => Kyo.unit
                case Result.Failure(s: SqlServerException) if s.sqlState == "55006" && remaining > 0 =>
                    Async.sleep(50.millis).andThen(attempt(remaining - 1))
                case Result.Failure(e) => Abort.fail(e)
                case Result.Panic(t)   => Abort.panic(t)
            }
        attempt(3)
    end dropSchema

end PostgresTestBackend
