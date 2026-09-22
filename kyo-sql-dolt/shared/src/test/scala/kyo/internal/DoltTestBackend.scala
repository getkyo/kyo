package kyo.internal

import kyo.*
import kyo.internal.mysql.MysqlConnection

/** The conformance descriptor for Dolt, which is what puts it in front of the cross-engine battery.
  *
  * Every capability answer below starts as MySQL's, since Dolt runs go-mysql-server and answers `8.0.31` to `version()`, and the battery is
  * what tests that hypothesis. An answer that differs from MySQL's carries the measurement that made it differ.
  *
  * Constructed by name by the services scan and the register fallback, so its zero-argument constructor stays public.
  */
final class DoltTestBackend extends SqlTestBackend:

    def id: String        = "dolt"
    def label: String     = "Dolt"
    def urlScheme: String = "dolt"

    /** Backticks, matching MysqlDialect.quoteIdent, which this dialect inherits unchanged. */
    def quoteIdent(name: String): String =
        val escaped = name.replace("`", "``")
        s"`$escaped`"
    end quoteIdent

    def supportsReturning: Boolean    = false
    def supportsRecursiveCte: Boolean = true

    /** Plain TEXT, without MySQL's `COLLATE utf8mb4_0900_as_cs`: Dolt's default collation is already case- and accent-sensitive, where the
      * MySQL server's default is not.
      */
    def textColumnType: String = "TEXT"

    def autoIncrementPrimaryKey: String = "BIGINT AUTO_INCREMENT PRIMARY KEY"

    def columnType(key: SqlTestBackend.ColumnType): String =
        import SqlTestBackend.ColumnType.*
        key match
            case SmallInt         => "SMALLINT"
            case Int              => "INT"
            case BigInt           => "BIGINT"
            case Numeric          => "DECIMAL(38,10)"
            case Boolean          => "BOOLEAN"
            case Float32          => "FLOAT"
            case Float64          => "DOUBLE"
            case Bytes            => "BLOB"
            case Uuid             => "VARCHAR(36)"
            case Date             => "DATE"
            case Time             => "TIME(6)"
            case TimeWithOffset   => "VARCHAR(64)"
            case DateTime         => "DATETIME(6)"
            case Timestamp        => "TIMESTAMP(6)"
            case CalendarInterval => "VARCHAR(64)"
            case Duration         => "TIME(6)"
            case Json             => "JSON"
            case IntArray         => "JSON"
            case TextArray        => "JSON"
            case JsonArray        => "JSON"
        end match
    end columnType

    def typeNameFor(kind: SqlTestBackend.ColumnType): String =
        import SqlTestBackend.ColumnType.*
        kind match
            case Int     => "INT"
            case BigInt  => "BIGINT"
            case Float64 => "DOUBLE"
            case Date    => "DATE"
            case Boolean => "TINYINT"
            case other   => throw new IllegalArgumentException(s"no reported type name pinned for $other on dolt")
        end match
    end typeNameFor

    def bytesLiteral(hexDigits: String): String = s"X'${hexDigits.toUpperCase}'"

    /** The bare form, because this engine REFUSES the offset one. Measured: `'2026-08-25 10:00:00+00:00'` into a `timestamp(6)` answers
      * `is not a valid value for 'timestamp(6)'`, where MySQL takes it. A bare literal is read against the session zone, which the
      * connection layer has already pinned at UTC.
      */
    def instantLiteral(wallClockUtc: String): String = s"'$wallClockUtc'"

    /** This engine SORTS a JSON object's keys. Measured: `{"name":"kyo","count":42}` reads back as `{"count":42,"name":"kyo"}`, where MySQL
      * returns it as written. A declared difference rather than a defect, objects being unordered.
      */
    def jsonPreservesKeyOrder: Boolean = false

    /** Measured: over 1, 2, 2, 3 and NULL a `RANGE BETWEEN 2 PRECEDING AND CURRENT ROW` sum answers 8, the partition total, for every row,
      * where the frame selects 1, 5, 5, 8 and nothing for the absent one. The dialect refuses the construct.
      */
    def computesRangeOffsetFrames: Boolean = false

    /** This engine repeats reads and still loses writes, which is the pair no standard level names. Measured: two concurrent increments
      * from 1 leave the counter at 2 with neither writer refused. Declared rather than worked around, a driver being unable to manufacture
      * the guarantee.
      */
    def defaultPreventsLostUpdate: Boolean = false

    /** This engine's conflict clause SWALLOWS a foreign-key violation. Measured: an insert naming a parent that does not exist fails with
      * 1452 on its own, and under `onConflictDoNothing` it returns a zero-row outcome instead. The other three engines fail either way.
      */
    def conflictClauseEnforcesForeignKeys: Boolean = false

    def outputAffectingSettings: Chunk[String] = Chunk.empty

    def booleanColumnKind: SqlRow.ColumnKind = SqlRow.ColumnKind.Integer

    def instantWireCarriesOffset: Boolean = false

    def hasNativeArrayColumns: Boolean = false

    def caseFoldingReachesPastAscii: Boolean = true

    def likeFollowsColumnCollation: Boolean = true

    def boundedTextColumn(name: String, maxChars: Int): String = s"$name VARCHAR($maxChars) NOT NULL"

    def allowsConcurrentWriteTransactions: Boolean = true

    /** `GET_LOCK` and `RELEASE_LOCK` both answer 1 on a 2.3.4 server, measured, so the named-lock surface is MySQL's. */
    def hasAdvisoryLocks: Boolean = true

    def timeColumnIsSignedSpan: Boolean = true

    def hasCalendarIntervalColumn: Boolean = false
    def hasNetworkAddressColumn: Boolean   = false
    def hasTimeWithOffsetColumn: Boolean   = false
    def hasNonFiniteSpecialValues: Boolean = false

    def windowRangeOffsetHonoursAbsentPlacement: Boolean = false

    def unrenderableColumns: Chunk[(String, String)] = Chunk.empty

    def arrayRenderCases: Chunk[(String, String, String)] = Chunk.empty

    /** Empty, deliberately: this engine's BIT handling is unmeasured, so the MySQL descriptor's case is not copied across. */
    def protocolAgreementCases: Chunk[(String, String, String)] = Chunk.empty

    override def hasSecondSchema: Boolean = true

    /** Dolt speaks the MySQL protocol, so a second schema is a second database, provisioned and removed the same way.
      *
      * Named after the leaf's own database so concurrent leaves cannot collide. No grant is needed here, unlike the MySQL descriptor: this
      * harness connects its leaves as root.
      */
    override def secondSchema(schema: SqlTestBackend.Schema): Maybe[SqlTestBackend.SecondSchema] =
        val name = s"${schema.database}_second"
        Present(SqlTestBackend.SecondSchema(
            name,
            Chunk(s"CREATE DATABASE ${quoteIdent(name)}"),
            Chunk(s"DROP DATABASE IF EXISTS ${quoteIdent(name)}")
        ))
    end secondSchema

    /** `HY000`, which is what this server actually sends, measured: a missing table (1146), a duplicate key (1062) and a syntax error
      * (1105) all arrive under the general-error state where MySQL sends `42S02`, `23000` and `42000`. Declared as the generic state rather
      * than corrected to the standard one, because the driver relays the server's own state untouched and classifies the typed family from
      * the error NUMBER instead. See `DoltErrors`.
      */
    def tableNotFoundSqlState: String = "HY000"

    def uniqueViolationSqlState: String = "HY000"

    def sessionIdSql: Maybe[String] = Present("CONNECTION_ID()")

    /** Dolt has no `performance_schema`, which is where the MySQL descriptor reads the running transaction's level from. Absent rather than
      * `@@transaction_isolation`: that variable reports the session DEFAULT, not the level of the transaction in progress.
      */
    def isolationIntrospectionSql: Maybe[String] = Absent

    /** No session time zone knob. Dolt stores and returns timestamps without the session conversion MySQL applies. */
    def sessionZoneStatements: Maybe[SqlTestBackend.SessionZone] = Absent

    /** One level, because one level is what this engine delivers. Measured, and both halves are divergences: under READ COMMITTED a re-read
      * inside a transaction does not see another transaction's committed write, where the two other servers do, and under SERIALIZABLE two
      * concurrent increments lost one with neither writer refused. The driver refuses every level but this one.
      */
    def honouredIsolationLevels: Set[SqlClient.IsolationLevel] = Set(SqlClient.IsolationLevel.RepeatableRead)

    /** What an unnamed transaction gets, which is the same level a named one has to ask for. */
    def defaultIsolationLevel: SqlClient.IsolationLevel = SqlClient.IsolationLevel.RepeatableRead

    /** The Dolt server fixture, which is kyo-pod's own predef.
      *
      * Held there rather than hand-rolled here because the two things this image needs to be driven safely, the `%` root host and a
      * readiness probe that waits for the entrypoint's initialisation to finish rather than for the server to answer, are properties of
      * the image, not of this suite. `ContainerPredef.Dolt` carries both and explains why.
      */
    private val rootPassword = ContainerPredef.Dolt.Config.default.rootPassword

    def containerConfig: Maybe[Container.Config] =
        Present(ContainerPredef.Dolt.buildContainerConfig(ContainerPredef.Dolt.Config.default))

    /** Roughly a minute of attempts, which is what a cold image pull plus first-time data-dir init has needed. */
    private val connectSchedule: Schedule =
        Schedule.exponentialBackoff(initial = 200.millis, factor = 2, maxBackoff = 2.seconds).take(30)

    private def freshSchemaName(using Frame): String < Sync =
        Random.nextLong.map(v => s"test_${(v & Long.MaxValue).toHexString}")

    def withFreshSchema[A, S](f: SqlTestBackend.Schema => A < S)(using
        Frame
    ): A < (S & Async & Abort[SqlException | ContainerException] & Scope) =
        // Scope kyo-pod's podman/docker HttpClient to the leaf: the ambient client's idle pool accumulates a unix
        // socket per container call and trips the fd leak check.
        HttpClient.init().flatMap(scoped => HttpClient.let(scoped)(withFreshSchemaBody(f)))

    private def withFreshSchemaBody[A, S](f: SqlTestBackend.Schema => A < S)(using
        Frame
    ): A < (S & Async & Abort[SqlException | ContainerException] & Scope) =
        for
            container <- SqlTestContainers.getOrInit(SqlTestContainers.containers, "dolt")(
                SqlTestContainers.initSingleton(containerConfig.get, "dolt")
            )
            port   <- container.mappedPort(3306)
            schema <- freshSchemaName
            host = container.host
            // An AUTHENTICATED connection is the readiness signal: the port is bound while the entrypoint is still initialising, before the
            // root grant exists, so without this retry a fresh container loses the race and every leaf in the run fails on connect.
            admin <- Retry[SqlException](connectSchedule) {
                MysqlConnection.connect(host, port, "root", Present(rootPassword), Absent, Absent, 64, Duration.Infinity)
            }
            _ <- Scope.ensure(Abort.run(admin.quit()).unit)
            _ <- admin.simpleExecute(s"CREATE DATABASE `$schema`")
            _ <- Scope.ensure(Abort.run(admin.simpleExecute(s"DROP DATABASE IF EXISTS `$schema`")).unit)
            ctx = SqlTestBackend.Schema(
                host = host,
                port = port,
                username = "root",
                password = rootPassword,
                database = schema,
                url = s"dolt://root:$rootPassword@$host:$port/$schema"
            )
            result <- f(ctx)
        yield result

end DoltTestBackend
