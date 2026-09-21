package kyo.internal

import kyo.*
import kyo.internal.SqlTestBackend
import kyo.internal.sqlite.SqliteErrors

/** The conformance descriptor for SQLite, which is what puts it in front of the cross-engine battery.
  *
  * The DDL names below are multi-word on purpose. A declared type's leading word carries the KIND the codec dispatches on, and a trailing
  * `TEXT` forces TEXT affinity so a value that looks numeric is not rewritten on the way in. Under the natural names a 20-digit decimal
  * becomes a double, a JSON document that is a bare number becomes an integer, and an all-digit date becomes an integer, each silently.
  */
class SqliteTestBackend extends SqlTestBackend:

    def id: String        = "sqlite"
    def label: String     = "SQLite"
    def urlScheme: String = "sqlite"

    def containerConfig: Maybe[Container.Config] = Absent

    def quoteIdent(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    def supportsReturning: Boolean    = true
    def supportsRecursiveCte: Boolean = true

    def textColumnType: String = "TEXT"

    /** SQLite's default BINARY collation is already case- and accent-sensitive, so no collation clause is needed. */
    def autoIncrementPrimaryKey: String = "INTEGER PRIMARY KEY AUTOINCREMENT"

    def columnType(key: SqlTestBackend.ColumnType): String =
        import SqlTestBackend.ColumnType.*
        key match
            case SmallInt => "SMALLINT"
            case Int      => "INTEGER"
            case BigInt   => "BIGINT"
            // The parameters must FOLLOW the whole name: `DECIMAL(38,10) TEXT` is a syntax error.
            case Numeric          => "DECIMAL TEXT(38,10)"
            case Boolean          => "BOOLEAN"
            case Float32          => "FLOAT"
            case Float64          => "DOUBLE"
            case Bytes            => "BLOB"
            case Uuid             => "UUID TEXT"
            case Date             => "DATE TEXT"
            case Time             => "TIME TEXT"
            case TimeWithOffset   => "TIMETZ TEXT"
            case DateTime         => "DATETIME TEXT"
            case Timestamp        => "TIMESTAMP TEXT"
            case CalendarInterval => "INTERVAL TEXT"
            case Duration         => "DURATION TEXT"
            // Neither this engine nor MySQL has an array type, and nothing in the battery reads ColumnKind.Array back.
            case Json | IntArray | TextArray | JsonArray => "JSON TEXT"
        end match
    end columnType

    /** The declared type comes back verbatim through `decltype`, so what this engine reports is what the DDL wrote. */
    def typeNameFor(kind: SqlTestBackend.ColumnType): String = columnType(kind)

    def bytesLiteral(hexDigits: String): String = s"X'$hexDigits'"

    def instantLiteral(wallClockUtc: String): String = s"'$wallClockUtc+00:00'"

    def jsonPreservesKeyOrder: Boolean = true

    def computesRangeOffsetFrames: Boolean = true

    def defaultPreventsLostUpdate: Boolean = true

    def conflictClauseEnforcesForeignKeys: Boolean = true

    def outputAffectingSettings: Chunk[String] = Chunk.empty

    def sessionZoneStatements: Maybe[SqlTestBackend.SessionZone] = Absent

    /** The `BOOLEAN` declaration is what the codec dispatches on, so the column reports as one although SQLite stores an integer. */
    def booleanColumnKind: SqlRow.ColumnKind = SqlRow.ColumnKind.Bool

    def instantWireCarriesOffset: Boolean = true

    def hasNativeArrayColumns: Boolean = false

    /** The built-in upper and lower map only the 26 ASCII letters, and reaching past them needs the ICU extension, a build-time dependency
      * rather than anything the driver can render.
      */
    def caseFoldingReachesPastAscii: Boolean = false

    /** `LIKE` reads neither the column's collation nor the operand's, so no DDL this descriptor could spell would make it match the other
      * engines.
      */
    def likeFollowsColumnCollation: Boolean = false

    /** A declared width is documentation here: `VARCHAR(4)` is TEXT affinity and the parameters are parsed and discarded. The CHECK is what
      * refuses the write, raising SQLITE_CONSTRAINT_CHECK where another engine would refuse on the width.
      */
    def boundedTextColumn(name: String, maxChars: Int): String =
        s"$name VARCHAR($maxChars) NOT NULL CHECK(length($name) <= $maxChars)"

    /** One writer over the whole database, and the driver takes that lock at BEGIN rather than on the first write, so a second write
      * transaction cannot open while the first is live.
      */
    def allowsConcurrentWriteTransactions: Boolean = false

    def hasAdvisoryLocks: Boolean = false

    /** The time column holds whatever the driver renders into it, so both endpoints survive: 24:00:00 and -838:59:59 alike. */
    def timeColumnIsSignedSpan: Boolean = true

    def hasCalendarIntervalColumn: Boolean = false
    def hasNetworkAddressColumn: Boolean   = false
    def hasTimeWithOffsetColumn: Boolean   = false

    /** SQLite splits the non-finite values: it HOLDS the infinities and turns NaN into NULL, which is why the driver refuses NaN at bind. */
    def hasNonFiniteSpecialValues: Boolean = false

    /** SQLite has window functions with RANGE frames and native NULLS FIRST / NULLS LAST, so no placement is lowered away. */
    def windowRangeOffsetHonoursAbsentPlacement: Boolean = true

    def unrenderableColumns: Chunk[(String, String)] = Chunk.empty

    def arrayRenderCases: Chunk[(String, String, String)] = Chunk.empty

    /** One statement API, so `query` and `simpleQuery` compile to the same prepare-and-step path and cannot disagree. */
    def protocolAgreementCases: Chunk[(String, String, String)] = Chunk.empty

    /** SQLite resolves an unqualified name against `main`, the database the connection was opened on. */
    override def defaultSchemaName(schema: SqlTestBackend.Schema): String = "main"

    // `secondSchema` stays Absent, the inherited answer: SQLite's other schemas are ATTACHed per CONNECTION, so a
    // statement run on one pooled connection provisions that connection alone. Reaching a second schema from a whole
    // pool is a connect-time concern rather than a statement, and is covered where that connect-time attach lives.

    def tableNotFoundSqlState: String   = SqliteErrors.UndefinedTableState
    def uniqueViolationSqlState: String = SqliteErrors.UniqueState

    /** SQLite has no server-side session and therefore no identifier for one. Absent rather than a literal: a constant is the same value on
      * every connection, so the leaf comparing it against the pinned one would pass whichever session ran the statement.
      */
    def sessionIdSql: Maybe[String] = Absent

    /** SQLite has no statement that reports an applied isolation level. Absent rather than a literal: answering `SELECT 'REPEATABLE READ'`
      * would be the test asserting a string it wrote itself, passing even if the driver sent a level the engine ignored.
      */
    def isolationIntrospectionSql: Maybe[String] = Absent

    /** REPEATABLE READ, which the engine delivers, and SERIALIZABLE, which it delivers by exceeding: a reader inside a transaction repeats
      * its read under every journal mode, and one writer at a time is refused rather than allowed to lose an update. The two weaker levels
      * are refused because no knob produces them, and accepting one would promise less than what is handed back.
      */
    def honouredIsolationLevels: Set[SqlClient.IsolationLevel] =
        Set(SqlClient.IsolationLevel.RepeatableRead, SqlClient.IsolationLevel.Serializable)

    /** Snapshot isolation is the floor, so an unnamed transaction gets REPEATABLE READ rather than the READ COMMITTED the server engines
      * pin. Nothing is configured to make that so; it is what the engine does.
      */
    def defaultIsolationLevel: SqlClient.IsolationLevel = SqlClient.IsolationLevel.RepeatableRead

    /** A fresh database FILE per run, deleted with its WAL sidecars on exit.
      *
      * A file rather than `:memory:` because the battery opens a SECOND client on the same URL to test concurrency, and every connection
      * opening `:memory:` gets its own private database, so that client would see an empty one.
      */
    def withFreshSchema[A, S](f: SqlTestBackend.Schema => A < S)(using
        Frame
    ): A < (S & Async & Abort[SqlException | ContainerException] & Scope) =
        Scope.acquireRelease(Sync.defer(SqliteTempDatabase.create()))(path => Sync.defer(SqliteTempDatabase.delete(path))).map { path =>
            f(SqlTestBackend.Schema(
                // A local address has none of these. The record is shared with the network descriptors, and a
                // conformance body reads `url` rather than assembling one.
                host = "",
                port = 0,
                username = "",
                password = "",
                database = path,
                url = s"sqlite://$path"
            ))
        }

    /** Reachable wherever a throwaway database file can be made. Overridden rather than derived: the derived answer keys on having a
      * container, and this backend has none, so it would claim even the platforms with no filesystem to hold its database.
      */
    override def reachable: Boolean = SqliteTempDatabase.available

end SqliteTestBackend
