package kyo

import kyo.*
import kyo.db.Backend
import kyo.db.Idiom
import kyo.db.Runtime
import kyo.internal.mysql.MysqlBackendFactory
import kyo.internal.mysql.MysqlDialect
import kyo.internal.mysql.MysqlSqlConnection

/** A [[SqlClient]] backed by MySQL, carrying the one feature only MySQL has.
  *
  * Everything portable is inherited: a `MysqlClient` widens to `SqlClient` wherever portable code takes over, and pattern-matching an
  * `SqlClient` narrows back. What is added here is [[loadLocalInfile]], the client-side bulk load MySQL performs through its `LOCAL INFILE`
  * protocol.
  *
  * Open one with [[MysqlClient.init]] when a program needs that; open with [[SqlClient.init]] when it does not, and the URL decides the
  * engine.
  */
final class MysqlClient private[kyo] (runtime: Runtime[MysqlSqlConnection]) extends SqlClient(runtime):
    self =>

    def dialect: Idiom = MysqlDialect

    /** Executes a `LOAD DATA LOCAL INFILE` statement, streaming `data` bytes to the server.
      *
      * The caller supplies the byte stream, use `Stream.from(span)` for in-memory data, `Path.readBytesStream` for file-backed data, or any
      * other `Stream[Byte, S]` source. The server's filename in the `LOCAL INFILE` SQL is arbitrary; kyo-sql ignores what the server echoes
      * back and always uploads `data` unconditionally.
      *
      * If a transaction is active in the current fiber (via [[SqlClient.txLocal]]), the upload runs on the transaction's connection, so a
      * rollback undoes it. Otherwise it takes a connection from the pool for the duration of the upload, and an enclosing transaction on
      * another connection has no say over it.
      *
      * A failure part-way through `data` does not unload what the server already took: the protocol has no way to abandon a load once bytes
      * are on the wire, so the upload is completed with what was sent and the failure is then raised. Run it inside a transaction when the
      * load has to be all-or-nothing.
      *
      * The CLIENT_LOCAL_FILES capability is negotiated automatically. The server must also have `local_infile=ON` (MySQL system variable);
      * otherwise the server rejects the statement with [[SqlServerException]].
      *
      * @param sql
      *   a `LOAD DATA LOCAL INFILE 'filename' INTO TABLE ...` statement
      * @param data
      *   the byte stream to upload (caller-supplied)
      * @return
      *   the affected-row count from the server's OK packet
      */
    def loadLocalInfile[S](sql: String, data: Stream[Byte, S])(using Frame): Long < (Async & Abort[SqlException] & S) =
        // Routed through the shared helper; see useMysqlConnection.
        self.useMysqlConnection(_.loadLocalInfile(sql, data))

    /** Runs `op` on a pinned MySQL connection, for the operations only this engine has.
      *
      * The portable surface reaches a connection through [[SqlClient.usePinnedConnection]] and sees only the SPI; this reaches the same
      * connection at its concrete type, which is what `LOAD DATA LOCAL INFILE` and the handshake's version string need.
      *
      * Reaching past the SPI also means bypassing the in-flight window it maintains, so an interrupt during one of these operations leaves the
      * pool with no evidence that a request was outstanding and the connection is destroyed rather than reclaimed. That is the conservative
      * answer and the right one here: `LOAD DATA LOCAL INFILE` runs its own cleanup on the channel it uploaded through.
      *
      * Routes like the portable [[SqlClient.usePinnedConnection]] does, so an operation here reaches an enclosing transaction's or lock's session
      * rather than a second one. Two checks stand between the fiber-local and `op`, and they do DIFFERENT jobs: the client comparison inside
      * [[SqlClient.pinnedSession]] is the CORRECTNESS gate, deciding whether joining this session is legitimate at all, while the concrete-type
      * narrowing below is a TYPING necessity, because `TransactionContext` holds the SPI type and this needs the MySQL one. After the client gate the
      * narrowing cannot fail, since this client's pool opens only `MysqlSqlConnection`, so the fall-through leases rather than pretending to decide
      * something. The sibling in `PostgresClient` states the same split at greater length.
      */
    private def useMysqlConnection[A, S](op: kyo.internal.mysql.MysqlConnection => A < (S & Async & Abort[SqlException]))(using
        Frame
    ): A < (S & Async & Abort[SqlException]) =
        self.pinnedSession.flatMap {
            case Present(conn: MysqlSqlConnection) => op(conn.underlying)
            case _ =>
                self.useConfig { config =>
                    runtime.lease(config)(conn => op(conn.underlying))
                }
        }

end MysqlClient

/** Opens MySQL clients, and narrows the ambient client to one.
  *
  * The [[init]] family mirrors [[SqlClient]]'s with `MysqlClient` in every result position, so a program that needs `LOAD DATA LOCAL INFILE`
  * reaches it without a cast. A URL whose scheme this backend does not claim fails with [[kyo.SqlConnectionUrlParseException]]: naming the
  * engine in the factory is a statement about which engine, and a mismatched URL contradicts it rather than silently retargeting. The claimed
  * set is the factory's own, so an alias declared there is accepted here without a second edit.
  */
object MysqlClient:

    /** The flavor a `MysqlClient` renders in. `MysqlClient.dialect.id` names it. */
    val dialect: Idiom = MysqlDialect

    /** Registers the MySQL backend so runtime discovery resolves a computed `mysql://` URL, the explicit counterpart to the
      * `META-INF/services/kyo.db.Backend` entry the JVM reads automatically.
      *
      * Needed only where the services scan cannot reach every backend: a Scala Native program that opens more than one flavor by computed URL,
      * since Native embeds a single `META-INF/services/kyo.db.Backend` file when several jars declare the service. Redundant but harmless on the
      * JVM, and on JS and Wasm where the same registration runs automatically at module load.
      */
    def register(): Unit =
        Backend.register(new MysqlBackendFactory())

    /** Opens a MySQL client bound to the enclosing [[Scope]], which closes it when the scope exits.
      *
      * The returned client is not installed as the ambient client; supply it to [[kyo.DB.run]], or connect and run in one step with `DB.run(rawUrl)(...)`.
      *
      * @param rawUrl
      *   database URL in the form `mysql://user:pw@host:port/db[?opts]`
      */
    def init(rawUrl: String)(using Frame): MysqlClient < (Async & Scope & Abort[SqlException]) =
        init(rawUrl, SqlConfig.default)

    /** Opens a MySQL client under `config`, bound to the enclosing [[Scope]]. See the single-argument [[init]]. */
    def init(rawUrl: String, config: SqlConfig)(using Frame): MysqlClient < (Async & Scope & Abort[SqlException]) =
        parsed(rawUrl).flatMap(url => openScoped(url, config))

    /** Opens a MySQL client bound to the enclosing [[Scope]] and runs `f` with it. The client is NOT installed ambiently; supplying the `DB` effect is [[kyo.DB.run]]'s job alone.
      *
      * Warm-up completes before `f` is called, so the connections `minConnections` asked for are ready.
      *
      * @tparam B
      *   the result type of `f`
      * @tparam S
      *   the effect row of `f`
      * @param rawUrl
      *   database URL in the form `mysql://user:pw@host:port/db[?opts]`
      * @param f
      *   function receiving the opened client
      */
    inline def initWith[B, S](rawUrl: String)(inline f: MysqlClient => B < S)(using Frame): B < (S & Async & Scope & Abort[SqlException]) =
        initWith(rawUrl, SqlConfig.default)(f)

    /** Opens a MySQL client under `config` bound to the enclosing [[Scope]] and runs `f`. See the two-argument [[initWith]]. */
    inline def initWith[B, S](rawUrl: String, config: SqlConfig)(inline f: MysqlClient => B < S)(using
        Frame
    ): B < (S & Async & Scope & Abort[SqlException]) =
        init(rawUrl, config).flatMap(f)

    /** Opens a MySQL client with no cleanup registered. The caller closes it with [[SqlClient.close]]. */
    def initUnscoped(rawUrl: String)(using Frame): MysqlClient < (Async & Abort[SqlException]) =
        initUnscoped(rawUrl, SqlConfig.default)

    /** Opens a MySQL client under `config` with no cleanup registered. See the single-argument [[initUnscoped]]. */
    def initUnscoped(rawUrl: String, config: SqlConfig)(using Frame): MysqlClient < (Async & Abort[SqlException]) =
        parsed(rawUrl).flatMap(url => openUnscoped(url, config))

    /** Opens a MySQL client with no cleanup registered and runs `f` with it. Nothing is installed ambiently; supplying the `DB` effect is [[kyo.DB.run]]'s job alone.
      *
      * The client outlives `f`, so something must call [[SqlClient.close]] on it.
      */
    inline def initUnscopedWith[B, S](rawUrl: String)(inline f: MysqlClient => B < S)(using Frame): B < (S & Async & Abort[SqlException]) =
        initUnscopedWith(rawUrl, SqlConfig.default)(f)

    /** Opens a MySQL client under `config` with no cleanup registered and runs `f`. See the two-argument [[initUnscopedWith]]. */
    inline def initUnscopedWith[B, S](rawUrl: String, config: SqlConfig)(inline f: MysqlClient => B < S)(using
        Frame
    ): B < (S & Async & Abort[SqlException]) =
        initUnscoped(rawUrl, config).flatMap(f)

    /** Runs `f` with the [[DB]] effect's client, narrowed to a `MysqlClient`.
      *
      * A one-liner over [[kyo.DB.clientAs]]: fails with [[kyo.SqlConnectionBackendMismatchException]] when the installed client talks to a
      * different engine, and a program that never supplied a client through [[kyo.DB.run]] is a compile error rather than a runtime failure.
      * `f` receives the concrete class, so [[MysqlClient.loadLocalInfile]] compiles without a cast.
      *
      * @tparam A
      *   the result type of `f`
      * @tparam S
      *   the effect row of `f`
      * @param f
      *   function receiving the MySQL-backed client
      */
    def use[A, S](f: MysqlClient => A < S)(using Frame): A < (S & Abort[SqlException] & DB) =
        DB.clientAs[MysqlClient].map(f)

    /** Parses `rawUrl` and rejects a scheme this backend does not claim.
      *
      * Reads the claimed set from [[MysqlBackendFactory]] rather than spelling a literal, so the first alias added there is honored here with
      * no second edit.
      */
    private def parsed(rawUrl: String)(using Frame): SqlConfig.Url < Abort[SqlException] =
        Abort.get(SqlConfig.Url.parse(rawUrl)).flatMap { url =>
            if !MysqlBackendFactory.claims(url.address.scheme) then Abort.fail(SqlConnectionUrlParseException(rawUrl, url.address.scheme))
            else url
        }

    /** Builds a client for `url` and binds its close to the enclosing [[Scope]], which closes it when the scope exits. */
    private[kyo] def openScoped(url: SqlConfig.Url, config: SqlConfig)(using
        Frame
    ): MysqlClient < (Async & Scope & Abort[SqlException]) =
        opened(url, config).flatMap(client => Scope.ensure(client.close).andThen(client))

    /** Builds a client for `url`, registering no cleanup. */
    private[kyo] def openUnscoped(url: SqlConfig.Url, config: SqlConfig)(using
        Frame
    ): MysqlClient < (Async & Abort[SqlException]) =
        opened(url, config)

    /** Assembles the carrier through [[kyo.db.Runtime.init]] and wraps it in a client.
      *
      * `Runtime.init` merges the URL's options under `config`, builds the pool over the connection factory, and warms it up behind a bracket
      * that closes whatever it opened on any failure edge, so a caller never receives a half-open client to clean up. MySQL has no type-name
      * validation step, so unlike the PostgreSQL path this is assembly alone.
      */
    private def opened(url: SqlConfig.Url, config: SqlConfig)(using Frame): MysqlClient < (Async & Abort[SqlException]) =
        Runtime.init(url, config, MysqlSqlConnection.factory(url.options)).map(rt => new MysqlClient(rt))

end MysqlClient
