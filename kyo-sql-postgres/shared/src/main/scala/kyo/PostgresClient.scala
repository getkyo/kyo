package kyo

import kyo.*
import kyo.db.Backend
import kyo.db.Idiom
import kyo.db.Runtime
import kyo.internal.postgres.PostgresBackendFactory
import kyo.internal.postgres.PostgresDialect
import kyo.internal.postgres.PostgresSqlConnection

/** A [[SqlClient]] backed by PostgreSQL, carrying the features only PostgreSQL has.
  *
  * Everything portable is inherited: a `PostgresClient` widens to `SqlClient` wherever portable code takes over, and pattern-matching an
  * `SqlClient` narrows back. What is added here is what no other engine can answer: bulk load and unload through `COPY` ([[copyIn]],
  * [[copyOut]]), the asynchronous `LISTEN`/`NOTIFY` channel ([[notifications]]), and the startup parameters the server reported
  * ([[parameters]]).
  *
  * Open one with [[PostgresClient.init]] when a program needs those; open with [[SqlClient.init]] when it does not, and the URL decides the
  * engine.
  */
final class PostgresClient private[kyo] (runtime: Runtime[PostgresSqlConnection]) extends SqlClient(runtime):
    self =>

    def dialect: Idiom = PostgresDialect

    /** Streams data from `data` into the database using `COPY ... FROM STDIN`.
      *
      * If a transaction is active in the current fiber (via [[SqlClient.txLocal]]), uses the bound connection directly so that the COPY
      * participates in the same transaction (enabling `BEGIN`, `COPY`, `ROLLBACK` on the same physical connection). Otherwise acquires a
      * dedicated connection from the pool for the duration of the upload. The connection is returned to the pool after the upload completes
      * (success or failure).
      *
      * On error or cancellation, sends `CopyFail` and drains `ReadyForQuery` before returning the connection.
      *
      * A rejection from the server stops the upload at the next chunk boundary rather than at the end of `data`, so a stream the server
      * refuses on its first row costs one chunk and not the whole transfer. The error it fails with is the server's own.
      *
      * @param sql
      *   a `COPY ... FROM STDIN` statement (with text, CSV, or binary format options as needed)
      * @param data
      *   the byte stream to upload; each of its chunks becomes one or more `CopyData` packets. `Stream.from(span)` covers in-memory data and
      *   `Path.readBytesStream` file-backed data
      * @return
      *   the number of rows loaded by the server (from the "COPY N" command tag)
      */
    def copyIn[S](sql: String, data: Stream[Byte, S])(using Frame): Long < (Async & Abort[SqlException] & S) =
        self.useConfig { config =>
            val cleanupTimeout = PostgresConfig.of(config).copyOutCleanupTimeout
            // Routed by the helper.
            self.usePostgresConnection(_.copyIn(sql, data, cleanupTimeout))
        }

    /** Executes `COPY ... TO STDOUT` and returns a lazy stream of raw data chunks.
      *
      * Acquires a dedicated connection from the pool, NOT an enclosing transaction's; the connection is held for the lifetime of the returned
      * [[Stream]]. Close the stream (or let the enclosing [[Scope]] exit) to release the connection. So a `COPY TO STDOUT` inside a
      * `transaction { ... }` does not see that transaction's uncommitted rows.
      *
      * copyOut holds a per-transfer cleanup latch released only from a `Scope` finalizer, so routing it onto an enclosing transaction's connection
      * would leave the latch outliving the transfer: the transaction's own `COMMIT`, an ordinary statement on that connection, would then wait on
      * it forever. It therefore always takes a connection of its own.
      *
      * If the consumer closes the stream before `CopyDone` is received, the cleanup path sends `CopyFail` and drains `ReadyForQuery` before
      * returning the connection (uninterruptible, bounded by [[PostgresConfig.copyOutCleanupTimeout]]).
      *
      * @param sql
      *   a `COPY ... TO STDOUT` statement
      * @return
      *   a [[Stream]] of the COPY data bytes, chunked as the server framed them; the stream ends when the server sends `CopyDone`
      */
    // copyOut does not join an enclosing transaction: it leases its own connection, so it cannot see
    // rows the transaction has written but not committed.
    def copyOut(sql: String)(using Frame): Stream[Byte, Async & Abort[SqlException] & Scope] =
        Stream[Byte, Async & Abort[SqlException] & Scope](
            self.useConfig { config =>
                // NoRoute: routing this would deadlock the enclosing transaction's COMMIT on the COPY cleanup latch,
                // for the reason the scaladoc above states, so this keeps a session of its own.
                self.useOwnPostgresConnection(_.copyOut(sql, PostgresConfig.of(config).copyOutCleanupTimeout).emit)
            }
        )

    /** Returns the server startup parameters captured during the connection handshake.
      *
      * Returns the ParameterStatus map (server_version, server_encoding, timezone, integer_datetimes, etc.) populated during startup, plus
      * updates from SET commands. Pool-stable: all connections from the same client return the startup parameter set.
      */
    def parameters(using Frame): Map[String, String] < (Async & Abort[SqlException]) =
        self.usePostgresConnection(_.parameters.get)

    /** Subscribes to PostgreSQL `LISTEN`/`NOTIFY` notifications on the named channel.
      *
      * Opens a connection of its own, sends `LISTEN <channel>`, and returns a [[Stream]] that emits one [[PostgresClient.Notification]] per
      * `NOTIFY` message received on that channel. The connection is opened through the pool, so it is refused on the same terms a statement is
      * refused, but it takes no slot and is never lent to anyone else: a session running `LISTEN` needs a fiber pumping its inbound messages,
      * and that pump cannot survive being handed to the next borrower.
      *
      * The subscription lasts as long as the enclosing [[Scope]], and ending it closes the connection rather than returning it. `.take(n)` ends
      * the STREAM and not the scope, so a caller that wants the connection released at that point runs the stream inside a [[Scope.run]] of its
      * own. That scope is the only thing that ends a subscription: because the connection holds no pool slot, [[SqlClient.close]] neither closes
      * it nor waits for it, and a subscription still open at that point keeps delivering until its own scope exits.
      *
      * A channel name may contain any character except NUL, which is refused with [[SqlRequestNotificationChannelNulException]] because the
      * statement carrying it is NUL-terminated on the wire.
      *
      * This method does `LISTEN channel` automatically. To send `NOTIFY`, use a separate client call or a second connection.
      *
      * @param channel
      *   PostgreSQL channel name (case-sensitive; will be quoted to preserve case)
      */
    def notifications(channel: String)(using Frame): Stream[PostgresClient.Notification, Async & Abort[SqlException] & Scope] =
        Stream[PostgresClient.Notification, Async & Abort[SqlException] & Scope](
            self.useConfig { config =>
                PostgresSqlConnection.notificationStream(runtime.pool, self.url.address, self.url.password, channel, config).emit
            }
        )

    /** Runs `op` on a pinned Postgres connection, for the operations only this engine has.
      *
      * The portable surface reaches a connection through [[SqlClient.usePinnedConnection]] and sees only the SPI; this reaches the same
      * connection at its concrete type, which is what `COPY` and the startup parameters need.
      *
      * Routes like the portable [[SqlClient.usePinnedConnection]] does, so an operation here reaches an enclosing transaction's or lock's session
      * rather than a second one. Two checks stand between the fiber-local and `op`, and they do DIFFERENT jobs, which is worth stating because
      * conflating them is the mistake to avoid:
      *
      *   - The client comparison, inside [[SqlClient.pinnedSession]], is the CORRECTNESS gate. It decides whether joining this session is
      *     legitimate at all, and without it a second client's statement lands on this client's connection: a different pool, a possibly different
      *     server.
      *   - The concrete-type narrowing below is a TYPING necessity and nothing more. `TransactionContext` holds the SPI type and this needs the
      *     Postgres one, so the match has to happen whether or not it can fail. After the client gate it cannot: this client's pool opens only
      *     `PostgresSqlConnection`. The fall-through therefore leases rather than pretending to decide something.
      *
      * On the fall-through, reaching past the SPI means bypassing the in-flight window it maintains, so an interrupt during one of these operations
      * leaves the pool with no evidence that a request was outstanding and the connection is destroyed rather than reclaimed. That is the conservative
      * answer and the right one here: `COPY` has its own mid-transfer cleanup, which a generic drain would cut across. It does not apply to the routed
      * branch, whose connection the enclosing transaction or lock already owns and will resolve itself.
      *
      * An operation that must NOT join the enclosing session calls [[useOwnPostgresConnection]] instead.
      */
    private def usePostgresConnection[A, S](op: kyo.internal.postgres.PostgresConnection => A < (S & Async & Abort[SqlException]))(using
        Frame
    ): A < (S & Async & Abort[SqlException]) =
        self.pinnedSession.flatMap {
            case Present(conn: PostgresSqlConnection) => op(conn.underlying)
            case _                                    => self.useOwnPostgresConnection(op)
        }

    /** Runs `op` on a Postgres connection of its own, never an enclosing transaction's or lock's.
      *
      * The engine-side opt-out from [[usePostgresConnection]]'s routing, and the fall-through that helper uses. Only [[copyOut]] asks for it
      * directly, and the reason is recorded there rather than here, because wanting an own session is a property of the operation.
      */
    private def useOwnPostgresConnection[A, S](op: kyo.internal.postgres.PostgresConnection => A < (S & Async & Abort[SqlException]))(using
        Frame
    ): A < (S & Async & Abort[SqlException]) =
        self.useConfig { config =>
            // NoRoute: the explicit own-session path. Callers that want routing take usePostgresConnection.
            runtime.lease(config)(conn => op(conn.underlying))
        }

end PostgresClient

/** Opens PostgreSQL clients, and narrows the ambient client to one.
  *
  * The [[init]] family mirrors [[SqlClient]]'s with `PostgresClient` in every result position, so a program that needs `COPY` or
  * `LISTEN`/`NOTIFY` reaches them without a cast. A URL whose scheme this backend does not claim fails with
  * [[kyo.SqlConnectionUrlParseException]]: naming the engine in the factory is a statement about which engine, and a mismatched URL
  * contradicts it rather than silently retargeting. The claimed set is the factory's own, so `postgresql://` opens a client just as
  * `postgres://` does; only a scheme belonging to another engine is refused.
  */
object PostgresClient:

    /** The flavor a `PostgresClient` renders in. `PostgresClient.dialect.id` names it. */
    val dialect: Idiom = PostgresDialect

    /** Registers the Postgres backend so runtime discovery resolves a computed `postgres://` URL, the explicit counterpart to the
      * `META-INF/services/kyo.db.Backend` entry the JVM reads automatically.
      *
      * Needed only where the services scan cannot reach every backend: a Scala Native program that opens more than one flavor by computed URL,
      * since Native embeds a single `META-INF/services/kyo.db.Backend` file when several jars declare the service. Redundant but harmless on the
      * JVM, and on JS and Wasm where the same registration runs automatically at module load.
      */
    def register(): Unit =
        Backend.register(new PostgresBackendFactory())

    /** A PostgreSQL `LISTEN`/`NOTIFY` message delivered on a subscribed channel.
      *
      * Emitted by [[PostgresClient.notifications]]. Each element corresponds to one `NotificationResponse` message from a server currently
      * listening on the named channel. The internal Postgres connection routes those messages into its per-connection [[kyo.Channel]] and
      * this public type is the value exposed to callers.
      *
      * @param channel
      *   the channel name that was used in `NOTIFY channel [, payload]`
      * @param payload
      *   the optional payload string (empty string when the `NOTIFY` had no payload clause)
      * @param processId
      *   the backend PID of the notifying session
      */
    final case class Notification(channel: String, payload: String, processId: Int) derives CanEqual

    /** Opens a PostgreSQL client bound to the enclosing [[Scope]], which closes it when the scope exits.
      *
      * The returned client is not installed as the ambient client; supply it to [[kyo.DB.run]], or connect and run in one step with `DB.run(rawUrl)(...)`.
      *
      * @param rawUrl
      *   database URL in the form `postgres://user:pw@host:port/db[?opts]`
      */
    def init(rawUrl: String)(using Frame): PostgresClient < (Async & Scope & Abort[SqlException]) =
        init(rawUrl, SqlConfig.default)

    /** Opens a PostgreSQL client under `config`, bound to the enclosing [[Scope]]. See the single-argument [[init]]. */
    def init(rawUrl: String, config: SqlConfig)(using Frame): PostgresClient < (Async & Scope & Abort[SqlException]) =
        parsed(rawUrl).flatMap(url => openScoped(url, config))

    /** Opens a PostgreSQL client bound to the enclosing [[Scope]] and runs `f` with it. The client is NOT installed ambiently; supplying the `DB` effect is [[kyo.DB.run]]'s job alone.
      *
      * Warm-up completes before `f` is called, so the connections `minConnections` asked for are ready.
      *
      * @tparam B
      *   the result type of `f`
      * @tparam S
      *   the effect row of `f`
      * @param rawUrl
      *   database URL in the form `postgres://user:pw@host:port/db[?opts]`
      * @param f
      *   function receiving the opened client
      */
    inline def initWith[B, S](rawUrl: String)(inline f: PostgresClient => B < S)(using
        Frame
    ): B < (S & Async & Scope & Abort[SqlException]) =
        initWith(rawUrl, SqlConfig.default)(f)

    /** Opens a PostgreSQL client under `config` bound to the enclosing [[Scope]] and runs `f`. See the two-argument
      * [[initWith]].
      */
    inline def initWith[B, S](rawUrl: String, config: SqlConfig)(inline f: PostgresClient => B < S)(using
        Frame
    ): B < (S & Async & Scope & Abort[SqlException]) =
        init(rawUrl, config).flatMap(f)

    /** Opens a PostgreSQL client with no cleanup registered. The caller closes it with [[SqlClient.close]]. */
    def initUnscoped(rawUrl: String)(using Frame): PostgresClient < (Async & Abort[SqlException]) =
        initUnscoped(rawUrl, SqlConfig.default)

    /** Opens a PostgreSQL client under `config` with no cleanup registered. See the single-argument [[initUnscoped]]. */
    def initUnscoped(rawUrl: String, config: SqlConfig)(using Frame): PostgresClient < (Async & Abort[SqlException]) =
        parsed(rawUrl).flatMap(url => openUnscoped(url, config))

    /** Opens a PostgreSQL client with no cleanup registered and runs `f` with it. Nothing is installed ambiently; supplying the `DB` effect is [[kyo.DB.run]]'s job alone.
      *
      * The client outlives `f`, so something must call [[SqlClient.close]] on it.
      */
    inline def initUnscopedWith[B, S](rawUrl: String)(inline f: PostgresClient => B < S)(using
        Frame
    ): B < (S & Async & Abort[SqlException]) =
        initUnscopedWith(rawUrl, SqlConfig.default)(f)

    /** Opens a PostgreSQL client under `config` with no cleanup registered and runs `f`. See the two-argument
      * [[initUnscopedWith]].
      */
    inline def initUnscopedWith[B, S](rawUrl: String, config: SqlConfig)(inline f: PostgresClient => B < S)(using
        Frame
    ): B < (S & Async & Abort[SqlException]) =
        initUnscoped(rawUrl, config).flatMap(f)

    /** Runs `f` with the [[DB]] effect's client, narrowed to a `PostgresClient`.
      *
      * A one-liner over [[kyo.DB.clientAs]]: fails with [[kyo.SqlConnectionBackendMismatchException]] when the installed client talks to a
      * different engine, and a program that never supplied a client through [[kyo.DB.run]] is a compile error rather than a runtime failure.
      * `f` receives the concrete class, so [[PostgresClient.copyIn]] and its siblings compile without a cast.
      *
      * @tparam A
      *   the result type of `f`
      * @tparam S
      *   the effect row of `f`
      * @param f
      *   function receiving the PostgreSQL-backed client
      */
    def use[A, S](f: PostgresClient => A < S)(using Frame): A < (S & Abort[SqlException] & DB) =
        DB.clientAs[PostgresClient].map(f)

    /** Parses `rawUrl` and rejects a scheme this backend does not claim.
      *
      * Reads the claimed set from [[PostgresBackendFactory]] rather than spelling a literal, so acceptance here cannot
      * disagree with the factory's declaration.
      */
    private def parsed(rawUrl: String)(using Frame): SqlConfig.Url < Abort[SqlException] =
        Abort.get(SqlConfig.Url.parse(rawUrl)).flatMap { url =>
            if !PostgresBackendFactory.claims(url.address.scheme) then
                Abort.fail(SqlConnectionUrlParseException(rawUrl, url.address.scheme))
            else url
        }

    /** Builds a client for `url` and binds its close to the enclosing [[Scope]], which closes it when the scope exits. */
    private[kyo] def openScoped(url: SqlConfig.Url, config: SqlConfig)(using
        Frame
    ): PostgresClient < (Async & Scope & Abort[SqlException]) =
        opened(url, config).flatMap(client => Scope.ensure(client.close).andThen(client))

    /** Builds a client for `url`, registering no cleanup. */
    private[kyo] def openUnscoped(url: SqlConfig.Url, config: SqlConfig)(using
        Frame
    ): PostgresClient < (Async & Abort[SqlException]) =
        opened(url, config)

    /** Validates the PostgreSQL settings, then assembles the carrier through [[kyo.db.Runtime.init]] and wraps it in a client.
      *
      * `Runtime.init` merges the URL's options under `config`, builds the pool over the connection factory, and warms it up behind a bracket
      * that closes whatever it opened on any failure edge, so a caller never receives a half-open client to clean up.
      */
    private def opened(url: SqlConfig.Url, config: SqlConfig)(using Frame): PostgresClient < (Async & Abort[SqlException]) =
        sanitizeTypeNames(PostgresConfig.of(config).typeNames).andThen {
            Runtime.init(url, config, PostgresSqlConnection.factory(url.options)).map(rt => new PostgresClient(rt))
        }

    /** Validates that each type name in `names` does not contain characters that would break SQL literal interpolation.
      *
      * Single-quote (`'`) and backslash (`\`) are rejected because the type names are embedded directly into a simple-query SQL string
      * (`SELECT typname, oid FROM pg_type WHERE typname IN ('a', 'b')`). Any name containing these characters would corrupt the query or allow
      * injection. Type names are expected to be simple identifiers (e.g. `hstore`, `geometry`, `vector`). PostgreSQL-only, since no other
      * engine has this `pg_type` lookup, so the guard lives here rather than in core.
      */
    private def sanitizeTypeNames(names: Set[String])(using Frame): Unit < Abort[SqlConnectionException] =
        val invalid = names.filter(n => n.contains('\'') || n.contains('\\'))
        if invalid.nonEmpty then
            Abort.fail(SqlConnectionInvalidTypeNameException(Chunk.from(invalid)))
        else ()
        end if
    end sanitizeTypeNames

end PostgresClient
