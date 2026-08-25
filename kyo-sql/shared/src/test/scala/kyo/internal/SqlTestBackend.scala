package kyo.internal

import kyo.*

/** One engine's conformance test descriptor: everything the backend-agnostic conformance battery needs to run against a live server of that
  * engine, contributed by the backend module rather than named in kyo-sql-tests.
  *
  * A descriptor lives in the kyo-sql core test tree so both kyo-sql-tests and each backend module's test tree can see it: kyo-sql-tests
  * consumes descriptors through [[withFreshSchema]] and the capability flags, and each backend module implements one against its own
  * `ContainerPredef` and connection type. The descriptor set is discovered at run time, so kyo-sql-tests iterates whatever descriptors are on
  * the test classpath and names no engine.
  *
  * Capability flags exist so a conformance body branches on a behavior, never on an engine name: a leaf that needs a recursive CTE reads
  * [[supportsRecursiveCte]], and DDL that differs between engines is generated from [[textColumnType]], [[autoIncrementPrimaryKey]], and
  * [[quoteIdent]] rather than from an inline literal. A flag is added only when a conformance body actually branches on it.
  *
  * @see
  *   [[SqlTestBackend.Schema]] the fresh-schema context [[withFreshSchema]] hands back
  */
abstract class SqlTestBackend:

    /** The stable id this descriptor registers and is looked up under, typically its URL scheme. */
    def id: String

    /** The label naming this backend's conformance leaves, such as the engine's own name. */
    def label: String

    /** The URL scheme a client opens this backend with, such as `postgres`. */
    def urlScheme: String

    /** The container configuration this backend's shared singleton fixture starts from. */
    def containerConfig: Container.Config

    /** Quotes `name` as an identifier the way this engine's dialect does. */
    def quoteIdent(name: String): String

    /** Whether this engine returns a generated key through a RETURNING clause. */
    def supportsReturning: Boolean

    /** Whether this engine accepts the RECURSIVE keyword on a common table expression. */
    def supportsRecursiveCte: Boolean

    /** The DDL column type this engine uses for unbounded text. */
    def textColumnType: String

    /** The DDL column fragment this engine uses to declare an auto-incrementing primary key. */
    def autoIncrementPrimaryKey: String

    /** The DDL column type this engine uses for the given portable column kind, so a conformance body builds a table without an engine
      * literal. See [[SqlTestBackend.ColumnType]].
      */
    def columnType(key: SqlTestBackend.ColumnType): String

    /** The SQLSTATE this engine reports when a statement references a table that does not exist (class 42). */
    def tableNotFoundSqlState: String

    /** The SQLSTATE this engine reports on a unique or primary-key violation (class 23). */
    def uniqueViolationSqlState: String

    /** A SQL expression returning the server's identifier for the current session, so a conformance body proves two statements ran on the same
      * connection without naming an engine session function.
      */
    def sessionIdSql: String

    /** Provisions a fresh schema in this backend's shared container and runs `f` against it, dropping the schema on scope exit even when `f`
      * fails.
      */
    def withFreshSchema[A, S](f: SqlTestBackend.Schema => A < S)(using
        Frame
    ): A < (S & Async & Abort[SqlException | ContainerException] & Scope)

end SqlTestBackend

object SqlTestBackend:

    /** The portable column kinds a conformance body asks a descriptor to spell as DDL, so the codec battery names no engine type. Each maps to
      * the engine column type that carries the matching [[kyo.SqlSchema]] wire codec.
      */
    enum ColumnType derives CanEqual:
        case SmallInt, Int, BigInt, Numeric, Boolean, Float32, Float64, Bytes, Uuid, Date, Time, TimeWithOffset, DateTime, Timestamp,
            CalendarInterval, Duration, Json, IntArray, TextArray, JsonArray

    /** The connection context for a freshly-provisioned schema: the reachable coordinates plus the ready-to-open `url`, which a conformance
      * body passes straight to `SqlClient.init` so it never assembles an engine-specific URL itself.
      */
    final case class Schema(
        host: String,
        port: Int,
        username: String,
        password: String,
        database: String,
        url: String
    ) derives CanEqual

end SqlTestBackend
