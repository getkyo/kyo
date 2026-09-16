package kyo.internal.sqlite

import kyo.*
import kyo.internal.SqlTestBackend

/** The conformance descriptor for SQLite, which is what puts it in front of the cross-engine battery.
  *
  * Two things here differ in kind from the other two descriptors, and both come from SQLite being embedded.
  *
  * There is no container. `containerConfig` is [[Absent]], which is what makes this backend reachable wherever the test JVM runs rather
  * than only where a daemon is. It is also the first descriptor for which that is true.
  *
  * A fresh schema is a fresh FILE. The other engines create a schema inside a shared server; here the database IS the file, so a temp file
  * per run is the same isolation. Not `:memory:`, which would be a different database per connection: the battery opens a SECOND client on
  * the same URL to test concurrency, and against `:memory:` that client would see an empty database and every such leaf would fail for a
  * reason that has nothing to do with what it tests.
  *
  * The DDL names below are multi-word on purpose. A declared type's leading word carries the KIND the codec dispatches on, and a trailing
  * `TEXT` forces TEXT affinity so a value that looks numeric is not rewritten on the way in. Under the natural names a 20-digit decimal
  * becomes a double, a JSON document that is a bare number becomes an integer, and an all-digit date becomes an integer, each silently.
  */
class SqliteTestBackend extends SqlTestBackend:

    def id: String        = "sqlite"
    def label: String     = "SQLite"
    def urlScheme: String = "sqlite"

    /** Nothing to provision, which is what makes this backend reachable on a host with no container runtime. */
    def containerConfig: Maybe[Container.Config] = Absent

    /** Double quotes with the embedded quote doubled, matching the dialect. */
    def quoteIdent(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    def supportsReturning: Boolean    = true
    def supportsRecursiveCte: Boolean = true

    def textColumnType: String = "TEXT"

    /** SQLite's default collation is BINARY, which is already case- and accent-sensitive, so no collation clause is needed here. MySQL's
      * descriptor has to force that with `COLLATE utf8mb4_0900_as_cs`.
      */
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
            // All three array kinds land on one name, as on MySQL: neither engine has an array type, and nothing in
            // the battery reads ColumnKind.Array back.
            case Json | IntArray | TextArray | JsonArray => "JSON TEXT"
        end match
    end columnType

    /** The declared type comes back verbatim through `decltype`, so what this engine reports IS what the DDL wrote. */
    def typeNameFor(kind: SqlTestBackend.ColumnType): String = columnType(kind)

    def bytesLiteral(hexDigits: String): String = s"X'$hexDigits'"

    /** No session setting respells a stored value. Every rendering is the driver's own, because SQLite holds text verbatim. */
    def outputAffectingSettings: Chunk[String] = Chunk.empty

    /** SQLite has no session time zone. Nothing about a connection changes how a stored instant reads back, which is why the driver can
      * render the offset once and be done.
      */
    def sessionZoneStatements: Maybe[SqlTestBackend.SessionZone] = Absent

    /** A `BOOLEAN` declaration is what the codec dispatches on, so a boolean column reports as one even though SQLite stores an integer. */
    def booleanColumnKind: SqlRow.ColumnKind = SqlRow.ColumnKind.Bool

    /** The instant is stored as the driver's own rendering, which carries its offset. */
    def instantWireCarriesOffset: Boolean = true

    def hasNativeArrayColumns: Boolean = false

    /** The built-in upper and lower map only the 26 ASCII letters. Fixing it needs the ICU extension, which is a build-time dependency
      * rather than anything the driver can render, so what is asserted here is that the rest is left ALONE.
      */
    def caseFoldingReachesPastAscii: Boolean = false

    /** Measured against plain TEXT, TEXT COLLATE BINARY, and an inline COLLATE on the operand: `LIKE` answered the same every time. It reads
      * neither the column's collation nor the operand's, so no DDL this descriptor could spell would make it match the other engines.
      */
    def likeFollowsColumnCollation: Boolean = false

    /** A declared width is documentation here: `VARCHAR(4)` is TEXT affinity and the parameters are parsed and discarded. The refusal has
      * to be spelled, so the column carries a CHECK, which raises SQLITE_CONSTRAINT_CHECK and refuses the write the same way a declared
      * width does on an engine that polices it.
      */
    def boundedTextColumn(name: String, maxChars: Int): String =
        s"$name VARCHAR($maxChars) NOT NULL CHECK(length($name) <= $maxChars)"

    /** One writer over the whole database, and the driver takes that lock at BEGIN rather than on the first write, so a second write
      * transaction cannot open while the first is live.
      */
    def allowsConcurrentWriteTransactions: Boolean = false

    def hasAdvisoryLocks: Boolean = false

    /** The time column is whatever the driver renders into it, so both endpoints hold: 24:00:00 and -838:59:59 alike. */
    def timeColumnIsSignedSpan: Boolean = true

    def hasCalendarIntervalColumn: Boolean = false
    def hasNetworkAddressColumn: Boolean   = false
    def hasTimeWithOffsetColumn: Boolean   = false

    /** SQLite splits the non-finite values rather than having all or none of them: it HOLDS the infinities, and turns NaN into NULL, which
      * is why the driver refuses NaN at bind. `false` is the right answer for the leaf this gates, and the split is worth knowing.
      */
    def hasNonFiniteSpecialValues: Boolean = false

    /** SQLite has window functions with RANGE frames and native NULLS FIRST / NULLS LAST, so no placement is lowered away. */
    def windowRangeOffsetHonoursAbsentPlacement: Boolean = true

    /** Every declared type maps to a renderable neutral value, so nothing is unrenderable. */
    def unrenderableColumns: Chunk[(String, String)] = Chunk.empty

    def arrayRenderCases: Chunk[(String, String, String)] = Chunk.empty

    /** One statement API, so `query` and `simpleQuery` compile to the same prepare-and-step path and no pair of protocols can disagree. */
    def protocolAgreementCases: Chunk[(String, String, String)] = Chunk.empty

    def tableNotFoundSqlState: String   = SqliteError.UndefinedTableState
    def uniqueViolationSqlState: String = SqliteError.UniqueState

    /** SQLite has no server-side session and therefore no identifier for one.
      *
      * Absent rather than a literal. A constant would be the same value on every connection, so a leaf comparing it against the pinned one
      * would pass whichever session ran the statement, which is the opposite of what that leaf exists to catch.
      */
    def sessionIdSql: Maybe[String] = Absent

    /** SQLite has no statement that reports an applied isolation level, so there is nothing to ask.
      *
      * Absent rather than a literal: answering `SELECT 'REPEATABLE READ'` would be the test asserting a string it wrote itself, which agrees
      * with itself by construction and would pass even if the driver sent a level the engine ignored. The property is covered instead by the
      * write-commits leaf and by the refusal leaf below.
      */
    def isolationIntrospectionSql: Maybe[String] = Absent

    /** REPEATABLE READ, which is what the engine delivers, and SERIALIZABLE, which it delivers by exceeding.
      *
      * A reader inside a transaction repeats its read under every journal mode and every BEGIN form, so snapshot isolation is the floor.
      * SERIALIZABLE is accepted because one writer at a time is refused rather than allowed to lose an update, so a caller asking for the
      * strongest level gets it. The two weaker levels are refused: there is no knob that produces them, and accepting one would hand back
      * snapshot isolation under a name that promises less.
      */
    def honouredIsolationLevels: Set[SqlClient.IsolationLevel] =
        Set(SqlClient.IsolationLevel.RepeatableRead, SqlClient.IsolationLevel.Serializable)

    /** Snapshot isolation is the floor, so an unnamed transaction gets REPEATABLE READ rather than the READ COMMITTED the server engines pin.
      * Nothing is configured to make that so; it is what the engine does, and a caller moving a program here sees repeatable reads it never
      * asked for.
      */
    def defaultIsolationLevel: SqlClient.IsolationLevel = SqlClient.IsolationLevel.RepeatableRead

    /** A fresh temp FILE per run, deleted with its WAL sidecars on exit.
      *
      * A file rather than `:memory:` because the battery opens a second client on the same URL, and two connections to `:memory:` are two
      * different databases.
      */
    /** A fresh database FILE per run, deleted with its WAL sidecars on exit.
      *
      * A file rather than `:memory:` because the battery opens a SECOND client on the same URL to test concurrency, and every connection
      * opening `:memory:` gets its own private database: that client would see an empty one and every such leaf would fail for a reason that
      * has nothing to do with what it tests.
      *
      * Where the platform has no filesystem there is no file to share, which is what [[reachable]] reports below.
      */
    def withFreshSchema[A, S](f: SqlTestBackend.Schema => A < S)(using
        Frame
    ): A < (S & Async & Abort[SqlException | ContainerException] & Scope) =
        Scope.acquireRelease(Sync.defer(SqliteTempDatabase.create()))(path => Sync.defer(SqliteTempDatabase.delete(path))).map { path =>
            f(SqlTestBackend.Schema(
                // A local address has none of these. They are present because the record is shared with the network
                // descriptors, and a conformance body reads `url` rather than assembling one.
                host = "",
                port = 0,
                username = "",
                password = "",
                database = path,
                url = s"sqlite://$path"
            ))
        }

    /** Reachable wherever a throwaway database file can be made.
      *
      * Overridden rather than derived: the derived answer keys on having a container, and this backend has none, which would make it claim
      * every platform including the ones with no filesystem to hold its database.
      */
    override def reachable: Boolean = SqliteTempDatabase.available

end SqliteTestBackend
