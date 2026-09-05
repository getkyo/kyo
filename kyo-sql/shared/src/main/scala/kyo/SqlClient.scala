package kyo

import kyo.*
import kyo.Log
import kyo.Sql.BoundValue
import kyo.db.Backend
import kyo.db.Connection
import kyo.db.Idiom
import kyo.db.Runtime
import kyo.internal.TransactionContext
import kyo.internal.client.SqlConnectionPool
import scala.annotation.publicInBinary

/** A connection pool bound to one database, and the surface every engine answers.
  *
  * A program written against `SqlClient` runs unchanged against either engine: the URL scheme picks the backend, and the value handed back
  * speaks only the portable surface. [[SqlClient.init]] returns exactly that, so nothing in a portable program names an engine.
  *
  * A feature only one engine has (Postgres `LISTEN`/`NOTIFY` and `COPY`, MySQL `LOAD DATA LOCAL INFILE`) lives on the concrete subclass and
  * is reachable only through it: open through the concrete client its backend module provides when a program needs one. That value
  * widens to `SqlClient` wherever portable code takes over, so committing to an engine at the edge does not spread inward.
  *
  * ==Lifecycle==
  *
  * [[SqlClient.init]] binds the pool to the enclosing [[Scope]], which closes it on exit. [[SqlClient.initUnscoped]] hands back a pool the
  * caller closes with [[close]]. Neither reaches the network: connections open on first use, or during warm-up when `minConnections` asks
  * for them.
  *
  * ==Ambient client==
  *
  * The ambient client is the [[DB]] effect: [[DB.run]] installs a client for a block so the statements inside it need no receiver, and
  * [[DB.client]] reads it back. [[DB.run]] is the only installer. Every `init` variant just produces a client: `init` returns it,
  * `initWith` hands it to its function without installing anything, and the caller passes the client to [[DB.run]] where it wants one.
  */
abstract class SqlClient(private[kyo] val runtime: Runtime[?]):
    self =>

    /** The URL this client opened, read from the assembled carrier. Its `toString` redacts a declared password and reports an absent one as
      * absent, so a log line never carries a credential and still distinguishes "none was supplied" from "the wrong one was".
      */
    final def url: SqlConfig.Url = runtime.url

    /** The settings this client resolved at open time, the URL's own options merged under the caller's [[SqlConfig]]. Read from the carrier. */
    final def config: SqlConfig = runtime.config

    /** The SQL flavor this client's engine speaks. `dialect.id` names it. The only member a backend's client subclass supplies. */
    def dialect: Idiom

    /** The server address (host, port, database, user) this client's pool connects to. */
    def address: SqlConfig.Address = self.url.address

    /** Runs `f` against the settings in force for an operation on this client: its own merged [[config]].
      *
      * The SPI-tier settings door, and the only settings read a client offers. For a client in hand the settings in force
      * are its own [[config]], the value its factory merged, which is exactly what [[DB.run]] seeds the [[DB.State]] with. An ambient
      * adjustment ([[DB.State.config]], which [[DB.withConfig]] narrows) is carried by the [[DB]] effect and reaches the DSL run
      * surface there, threaded into the internal execute path, rather than through this door: a direct call on a client in hand reads the
      * client's own settings.
      */
    final def useConfig[A, S](f: SqlConfig => A < S)(using Frame): A < S =
        f(self.config)

    /** The session a statement on this client belongs on, or [[Absent]] when it belongs on a pooled one.
      *
      * The whole routing decision, in one place, so every helper answers it the same way. A transaction wins over a lock only in the reading order;
      * the precedence is unobservable because in every reachable state both name the SAME connection. A lock taken inside a transaction is taken on
      * the transaction's connection, and a transaction opened inside a lock runs on the lock's connection, so the two locals never disagree.
      * `txLocal` is read first because it is the inner scope in the first of those cases.
      */
    final def pinnedSession(using Frame): Maybe[Connection] < Any =
        self.pinnedEntry.map(_.map(_._1))

    /** Runs `op` on a connection borrowed for one statement, under retry and the per-statement timeout.
      *
      * The pooled path: the connection goes back as soon as `op` returns, so nothing a later statement does is guaranteed to land on the same
      * session. Use [[usePinnedConnection]] when it has to.
      */
    final def useConnection[A](op: Connection => A < (Abort[SqlException]))(using
        Frame
    ): A < (Async & Abort[SqlException]) =
        self.useConfig { config =>
            // NoRoute: the pooled primitive routing falls THROUGH to, not a site that skipped the decision.
            // `routed` consults the locals and calls this only once they answered Absent, so consulting them
            // again here would ask a question already answered.
            runtime.leaseStatement(config)(op)
        }

    /** Runs `op` on the session this call belongs on, pinned for its whole duration, with no retry and no per-statement timeout.
      *
      * Routes by default: an enclosing transaction's or advisory lock's connection when this fiber is inside one of this client's, and a connection
      * leased for the call otherwise. A caller that must NOT join the enclosing session says so by calling [[useIndependentConnection]] instead.
      *
      * Routing by default rather than leasing unconditionally keeps the dangerous case (a connection that cannot see the enclosing transaction, or
      * that deadlocks a one-connection pool from inside one) the one a caller must opt into, not the one they must remember to avoid.
      *
      * No retry, because re-running a body would re-run work an earlier attempt may have committed. No per-statement timeout, because the unit of
      * work is the whole body.
      */
    final def usePinnedConnection[A, S](op: Connection => A < S)(using Frame): A < (S & Async & Abort[SqlException]) =
        self.pinnedEntry.flatMap {
            case Present((conn, meter)) => self.serialised(meter)(op(conn))
            case Absent                 => self.useIndependentConnection(op)
        }

    /** Runs `op` on a connection of its own, leased for the call, never an enclosing transaction's or lock's.
      *
      * The explicit opt-out from [[usePinnedConnection]]'s routing, for the operations that are about a SESSION rather than about data. Each call
      * site states why, because "wants its own connection" is a claim about the operation's meaning and not a detail: [[reset]] scrubbing session
      * state would destroy an enclosing transaction's own state, [[ping]] would report that transaction's health rather than the pool's, and the
      * outermost branch of a `transaction` is the thing that establishes the pin, so consulting it would be circular.
      */
    final def useIndependentConnection[A, S](op: Connection => A < S)(using
        Frame
    ): A < (S & Async & Abort[SqlException]) =
        self.useConfig { config =>
            // NoRoute: the explicit own-session path. Every caller states its reason at its own call site, which is
            // where wanting a fresh session is a claim about the operation rather than about this helper.
            runtime.lease(config)(op)
        }

    /** The `(major, minor, patch)` version the server reported at handshake.
      *
      * Read from the connection this call routes to, an enclosing transaction's or advisory lock's session when inside one and a leased
      * connection otherwise, then cached so every later read is free. Reaching the server is what makes the value real, which is why this
      * suspends rather than sitting in a field: a client that has never connected has no version to report, and reporting a plausible one
      * instead would silently render SQL for the wrong server. Routing rather than taking a session of its own is load bearing: rendering any
      * statement needs the version, so a `serverVersion` that leased unconditionally would make the first statement inside a one-connection
      * transaction deadlock against the transaction's own permit.
      */
    def serverVersion(using Frame): Idiom.ServerVersion < (Async & Abort[SqlException]) =
        runtime.serverVersionRef.get.flatMap {
            case Present(version) => version
            case Absent =>
                self.routed(_.serverVersion).flatMap { version =>
                    runtime.serverVersionRef.set(Present(version)).andThen(version)
                }
        }

    /** Returns all rows for a parameterised query using the extended protocol.
      *
      * If a transaction is active in the current fiber (via [[SqlClient.txLocal]]), uses the bound connection directly. Otherwise acquires a
      * connection from the pool.
      *
      * NOTE: If the pool has been closed (via [[close]]), this method surfaces [[SqlConnectionException]] rather than [[kyo.Closed]]. See
      * [[close]] for the rationale.
      */
    def query(sql: String)(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        self.query(Sql.Fragment.lit[Any](sql))

    def query[A](executable: Sql.Executable[A])(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        self.renderForWire(executable).map { rendered =>
            self.routed(_.extendedQuery(rendered.sql, rendered.params))
        }

    /** Executes a DML statement and returns the number of affected rows.
      *
      * If a transaction is active in the current fiber (via [[SqlClient.txLocal]]), uses the bound connection directly. Otherwise acquires a
      * connection from the pool.
      */
    def execute(sql: String)(using Frame): Long < (Async & Abort[SqlException]) =
        self.execute(Sql.Fragment.lit[Any](sql))

    def execute[A](executable: Sql.Executable[A])(using Frame): Long < (Async & Abort[SqlException]) =
        self.renderForWire(executable).map { rendered =>
            self.routed(_.extendedExecute(rendered.sql, rendered.params))
        }

    /** Executes an `INSERT` and reports both the affected-row count and whatever generated key the engine surfaced.
      *
      * The portable counterpart of [[execute]] for inserts: [[execute]] discards the generated key because its return type has no slot for
      * one. See [[SqlClient.InsertOutcome]] for the per-engine semantics of that key, which differ for multi-row inserts.
      */
    def executeInsert[T, F](insert: Sql.Insert[T, F])(using Frame): SqlClient.InsertOutcome < (Async & Abort[SqlException]) =
        self.renderForWire(insert).map(rendered => self.internalExecuteInsert(rendered.sql, rendered.params, self.config))

    /** Executes `sql` through the engine's simple-query protocol and returns the rows.
      *
      * Unlike [[query]], the statement is not prepared: no parse / bind / execute round-trip, and no entry in the server's prepared-statement
      * metadata. Use it for one-off SQL that should not pay the prepared-statement cache cost or appear in that metadata.
      *
      * The SQL must carry no placeholders; the simple-query protocol has nowhere to bind them. Use [[query]] for parameterised statements.
      */
    def simpleQuery(sql: String)(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        // Routed. The pin here is a FRAMING requirement, one wire for the statement and the rows it streams back,
        // not a session-state one, and an enclosing transaction's connection satisfies framing just as well.
        self.usePinnedConnection(_.simpleQuery(sql))

    /** Streams rows from a parameterised query.
      *
      * If a transaction is active in the current fiber (via [[SqlClient.txLocal]]), uses the bound connection directly and does NOT release
      * the connection when the stream ends (the connection is held by the enclosing `transaction { ... }`). Otherwise acquires a dedicated
      * connection from the pool for the duration of the stream.
      */
    def streamQuery(sql: String)(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        self.streamQuery(Sql.Fragment.lit[Any](sql))

    def streamQuery[A](executable: Sql.Executable[A])(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        Stream[SqlRow, Async & Abort[SqlException] & Scope](
            self.useConfig { config =>
                self.streamQuery(executable, config.streamBatchSize).emit
            }
        )

    def streamQuery[A](
        executable: Sql.Executable[A],
        batchSize: Int
    )(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        Stream[SqlRow, Async & Abort[SqlException] & Scope](
            self.renderForWire(executable).map { rendered =>
                self.enclosingTransaction.flatMap {
                    case Present(ctx) =>
                        // Inside a transaction the stream runs on the transaction's own connection and does NOT
                        // release it when it ends: the enclosing `transaction { ... }` holds it until it commits.
                        // The session mutex is held for the stream's whole consumption, so a statement forked while
                        // the stream is open queues rather than interleaving with it; the mutex is reentrant, so a
                        // statement issued from the consuming fiber between batches still proceeds.
                        self.serialised(ctx.meter)(ctx.connection.streamQuery(rendered.sql, rendered.params, batchSize).emit)
                    case Absent =>
                        self.useConfig { config =>
                            runtime.leaseScoped(config).flatMap { conn =>
                                conn.streamQuery(rendered.sql, rendered.params, batchSize).emit
                            }
                        }
                }
            }
        )
    end streamQuery

    /** Executes `sql` as a raw simple-query against a pooled connection.
      *
      * Intended for migrations and multi-statement scripts that cannot use Bind/Execute. If a transaction is active, uses the bound
      * connection directly.
      */
    def executeRaw(sql: String)(using Frame): Long < (Async & Abort[SqlException]) =
        self.routed(_.simpleExecute(sql))

    /** Renders `ast` as the SQL this client's engine would receive, in its flavor and for the version it reported at handshake.
      *
      * The client-driven counterpart of [[Sql.render]]: use it when a client is already in hand and the caller wants the text
      * matching that specific server, for logging, migration-script emission, or dry-run inspection. Reaching the server for its version is
      * what puts the [[Async]] in the row; [[Sql.render]] takes an explicit dialect and stays pure.
      *
      * A construct this engine cannot express aborts with [[kyo.SqlUnsupportedDialectFeatureException]]. The pure counterpart raises
      * instead, because it has no effect row to fail in.
      */
    def render[A](ast: Sql[A])(using frame: Frame): Sql.Rendered < (Async & Abort[SqlException]) =
        self.serverVersion.map { version =>
            Abort.catching[SqlException](ast.render(self.dialect, Present(version)))
        }

    /** Releases all pooled connections, waiting up to `gracePeriod` for in-flight queries to complete before force-closing.
      *
      * The canonical close variant. Marks the pool as closed (preventing new connection acquisitions) and then waits up to `gracePeriod` for
      * any in-flight queries to return their connections. After the grace period, any remaining connections are force-closed. Idempotent, a
      * second call is a no-op.
      *
      * @param gracePeriod
      *   maximum time to wait for in-flight queries to complete; `Duration.Zero` forces an immediate close
      *
      * NOTE: Operations attempted after `close` (including operations on other methods of this client) will surface as
      * [[SqlConnectionException]], not [[kyo.Closed]]. This is a deliberate design choice: `SqlClient`'s entire public surface is uniformly
      * typed `Abort[SqlException]`, and `SqlConnectionException` correctly models "cannot reach the database" regardless of whether the cause
      * is a network failure or a closed pool. `kyo.Closed` is the idiom for kernel concurrency primitives (`Channel`/`Queue`/`Hub`); it is
      * not a subtype of `SqlException` and adding it to every method's effect row would double every caller's error-handling burden for one
      * failure mode already covered.
      */
    def close(gracePeriod: Duration)(using Frame): Unit < Async =
        runtime.close(gracePeriod)

    /** Releases all pooled connections using the config's `closeGrace` duration (default 30 seconds).
      *
      * Delegates to `close(config.closeGrace)`. Idempotent, a second call is a no-op.
      */
    def close(using Frame): Unit < Async =
        self.close(self.config.closeGrace)

    /** Returns `true` if [[close]] has been called on this client, `false` otherwise.
      *
      * The predicate reflects only the local closed flag set by [[close]] / [[closeNow]]; it does not probe the underlying pool or network.
      */
    def isClosed(using Frame): Boolean < Sync = runtime.isClosed

    /** Releases all pooled connections immediately without waiting for in-flight queries.
      *
      * Delegates to `close(Duration.Zero)`. Idempotent.
      */
    def closeNow(using Frame): Unit < Async =
        self.close(Duration.Zero)

    /** Runs `body` inside a database transaction using the server default isolation level and read-write mode.
      *
      * Equivalent to `transaction(Absent, false)(body)`.
      *
      * A `body` that raises `Abort[E]` for a non-[[SqlException]] `E` keeps that failure: the rollback fires for it exactly as for an
      * [[SqlException]], and the value surfaces on the row the body already put it on, so a caller matches on its own error.
      *
      * ==Statements reserved to the framework inside a transaction body==
      *
      * Two kinds of statement belong to this method rather than to `body`, because `body` issuing one would move the transaction underneath the
      * bookkeeping that decides what to commit and what to roll back to. Neither is rejected at runtime: the SQL is not parsed, so the
      * reservation is a contract rather than a check.
      *
      * First, any identifier starting with `kyo_sp_`, which is the prefix every savepoint a nested `transaction` establishes carries. A
      * caller's own savepoint under that prefix can collide with one of those.
      *
      * Second, by property rather than by list: any statement that begins, ends, chains, prepares, or implicitly commits a transaction. Named
      * in full, that is `BEGIN`, `START TRANSACTION`, `COMMIT`, `END`, `ROLLBACK`, `ABORT`, `SAVEPOINT`, `RELEASE SAVEPOINT`,
      * `ROLLBACK TO SAVEPOINT`, `PREPARE TRANSACTION`, and every `WORK` and `AND CHAIN` variant of those. MySQL adds a second class that commits
      * without saying so: DDL statements, `SET autocommit=1`, `LOCK TABLES`, and the `XA` family. Nest a `transaction` call instead of writing
      * one of these; that is what the savepoint machinery is for.
      *
      * @param body
      *   the computation to run inside the transaction
      */
    def transaction[A, S](body: A < S)(using Frame): A < (S & Async & Abort[SqlException]) =
        transactionImpl(Absent, false, body)

    /** Runs `body` inside a database transaction with an explicit isolation level or read-only mode.
      *
      * A `body` that raises `Abort[E]` for a non-[[SqlException]] `E` keeps that failure: the rollback fires for it exactly as for an
      * [[SqlException]], and the value surfaces on the row the body already put it on, so a caller matches on its own error.
      *
      * @param isolation
      *   optional isolation level; `Absent` uses the server default
      * @param readOnly
      *   if `true`, sends `BEGIN ... READ ONLY`
      * @param body
      *   the computation to run inside the transaction
      */
    def transaction[A, S](
        isolation: Maybe[SqlClient.IsolationLevel],
        readOnly: Boolean
    )(body: A < S)(using Frame): A < (S & Async & Abort[SqlException]) =
        transactionImpl(isolation, readOnly, body)

    /** Executes multiple statements in a single logical batch.
      *
      * On PostgreSQL, all statements are sent in a single TCP write using the extended-protocol pipeline mode (one Bind/Execute/Sync triple
      * per statement). On MySQL, statements are executed sequentially on the same connection using the extended (binary) protocol; MySQL's
      * wire protocol does not support multi-statement batch writes.
      *
      * The `body` lambda receives a [[SqlClient.PipelineBuilder]] and registers statements via [[SqlClient.PipelineBuilder.execute]] /
      * [[SqlClient.PipelineBuilder.query]]. When the body returns, every registered statement is rendered for this client's engine and
      * server version, then dispatched, and the responses are read in order.
      *
      * Returns one pipeline result per registered statement, in submission order. A per-statement failure is recorded as
      * [[kyo.Result.Failure]] and does NOT abort subsequent statements. A connection-level failure raises `Abort[SqlException]` for the
      * entire pipeline.
      *
      * @param body
      *   lambda that registers statements on the [[SqlClient.PipelineBuilder]]
      * @return
      *   per-statement results in submission order
      */
    def pipeline[S](
        body: SqlClient.PipelineBuilder => Unit < S
    )(using Frame): Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < (Async & Abort[SqlException] & S) =
        // The builder is allocated inside the effect so each run of this value gets one of its own. Sharing a
        // builder across runs would make the second run send the first run's statements again.
        Sync.defer(new SqlClient.PipelineBuilder).map { builder =>
            body(builder).andThen(builder.registered).map { statements =>
                if statements.isEmpty then Chunk.empty
                else
                    Kyo.foreach(statements)(statement => self.renderForWire(statement).map(r => (r.sql, r.params))).map { stmts =>
                        // Routed by the helper rather than hand-rolling the same decision here: a caller that
                        // reimplements routing can reimplement it wrongly.
                        self.usePinnedConnection(_.pipelined(stmts))
                    }
            }
        }
    end pipeline

    /** Probes the server with a minimal round-trip to confirm the client can reach a live database.
      *
      * Acquires a connection from the pool, sends a backend-specific probe (MySQL `COM_PING`; Postgres empty simple-query), and releases the
      * connection back to the pool. Returns `Unit` on success; raises `Abort[SqlException]` on connection, protocol, or server error.
      *
      * Cheaper than `SELECT 1`, MySQL bypasses the SQL parser entirely; Postgres skips parse/plan.
      */
    def ping(using Frame): Unit < (Async & Abort[SqlException]) =
        // NoRoute: a probe wants its own session. Routing this onto an enclosing transaction's connection would
        // report that connection's health rather than the pool's, and would put a round trip on a wire the
        // transaction is using.
        self.useIndependentConnection(_.ping)

    /** Convenience wrapper over [[ping]] that returns `true` when the probe succeeds and `false` when it raises a [[SqlException]].
      *
      * Distinct from [[isClosed]] (a local close-flag predicate), `isAlive` reaches the server.
      */
    def isAlive(using Frame): Boolean < Async =
        Abort.run[SqlException](self.ping).map(_.isSuccess)

    /** Explicitly scrubs per-session state (variables, prepared statements, temp tables, listeners, transactions) on a freshly acquired
      * connection.
      *
      *   - MySQL: sends `COM_RESET_CONNECTION`, protocol-native, no SQL round-trip.
      *   - Postgres: runs `DISCARD ALL`, clears prepared statements, session variables, temp tables, listeners, and any open transaction.
      *
      * The operation runs on the borrowed pool connection and its success or failure is observable to the caller.
      */
    def reset(using Frame): Unit < (Async & Abort[SqlException]) =
        // NoRoute: the one opt-out that is a correctness requirement rather than documentation.
        // `DISCARD ALL` (or MySQL's reset) on an enclosing transaction's connection would destroy that
        // transaction's own state. Routing here would not merely answer the wrong question, it would damage the
        // caller's in-flight work.
        self.useIndependentConnection(_.resetSession)

    /** Takes a database advisory lock for `key`, runs `body` while holding it, and releases the lock however `body` ends.
      *
      * Both engines scope an advisory lock to the session that took it, so the acquire and the release have to reach the same connection or
      * the release silently frees nothing. That is what this pins one for: a connection is held from before the acquire until after the
      * release, with no chance of the pool lending it out in between.
      *
      * PostgreSQL runs `SELECT pg_advisory_lock(key)`, which blocks until the lock is granted, and always finishes with
      * `SELECT pg_advisory_unlock(key)` on that same connection, including after an `Abort` or a panic. Re-entrant per session: two acquires
      * on one connection raise an internal count that two releases lower. MySQL runs `SELECT GET_LOCK('key', timeoutSeconds)`, which answers
      * 1 when granted and 0 or NULL otherwise; anything but 1 aborts with [[kyo.SqlRequestAdvisoryLockException]] and `body` never runs.
      *
      * IMPORTANT: while `body` runs, the locked connection is published as this fiber's session, so a [[SqlClient]] statement inside
      * `body` routes to the lock's own connection rather than taking a second one from the pool. That routing is what lets a pool of
      * one connection make progress (without it the lock would hold the only slot and the first statement inside would wait forever),
      * and it means a session-scoped effect inside `body` (a `SET`, a temporary table) lands on the locked session. Routing buys
      * progress rather than exclusion: only statements run inside `body` on the same fiber are routed; work on another fiber takes
      * its own connection exactly as it would outside.
      *
      * @param key
      *   numeric lock identifier; the `bigint` argument to `pg_advisory_lock` on PostgreSQL, and the lock name `key.toString` on MySQL
      * @param timeout
      *   how long to wait for the lock on MySQL; [[Maybe.Absent]] waits indefinitely. Unused on PostgreSQL, whose session-level advisory locks
      *   take no timeout and always wait; bound the whole call there instead.
      * @param body
      *   the effect run while the lock is held
      */
    def withAdvisoryLock[A, S](key: Long, timeout: Maybe[Duration] = Maybe.Absent)(
        body: A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Abort[SqlException]) =
        // Routed through [[pinnedEntry]] rather than [[usePinnedConnection]], because the latter would hold the
        // session mutex for the WHOLE body: a statement the body forks would then queue behind the composite
        // itself and never run. The lock's leaf statements take the mutex individually inside [[lockedOn]].
        // Taking the lock on an enclosing transaction's connection is safe: `pg_advisory_lock` and MySQL's
        // `GET_LOCK` are both session-scoped and non-transactional, so a ROLLBACK does not free the lock.
        self.pinnedEntry.map {
            case Present((conn, meter)) => self.lockedOn(conn, meter, key, timeout)(body)
            case Absent =>
                self.useIndependentConnection { conn =>
                    Meter.initMutexUnscoped.map(meter => self.lockedOn(conn, meter, key, timeout)(body))
                }
        }

    /** The transaction this fiber is inside ON THIS CLIENT, or [[Absent]].
      *
      * The only sanctioned read of [[SqlClient.txLocal]], and the reason is that the comparison it performs is a correctness gate rather than a
      * convenience. A [[kyo.Local]] belongs to the fiber, so a transaction opened on one client is visible inside its body to every other client
      * too; consuming the context without checking who owns it routes the second client's statement onto the first client's connection, which is a
      * different pool aimed at a possibly different server. The statement would then run against the wrong database, which is worse than not being
      * routed at all.
      *
      * Centralised so that a consumer added later cannot forget it: nothing outside this method and [[enclosingLock]] touches the locals, so the
      * gate is not a rule anyone has to remember.
      */
    private[kyo] def enclosingTransaction(using Frame): Maybe[TransactionContext] < Any =
        SqlClient.txLocal.use {
            case Present(ctx) if ctx.client eq self => Present(ctx)
            case _                                  => Absent
        }

    /** The advisory lock this fiber is inside ON THIS CLIENT, or [[Absent]]. Same gate and same reason as [[enclosingTransaction]]. */
    private[kyo] def enclosingLock(using Frame): Maybe[SqlClient.LockContext] < Any =
        SqlClient.lockLocal.use {
            case Present(lock) if lock.client eq self => Present(lock)
            case _                                    => Absent
        }

    /** [[pinnedSession]] paired with the session's statement mutex, for the routing helpers that serialise on it. */
    final private[kyo] def pinnedEntry(using Frame): Maybe[(Connection, Meter)] < Any =
        self.enclosingTransaction.flatMap {
            case Present(ctx) => Present((ctx.connection, ctx.meter))
            case Absent       => self.enclosingLock.map(_.map(lock => (lock.connection, lock.meter)))
        }

    /** Runs `op` holding `meter`'s single permit, the serialisation point for every statement on a pinned session.
      *
      * The mutex is why concurrent fibers inside a `transaction` or `withAdvisoryLock` body queue on the session
      * instead of interleaving protocol frames on one socket. It is reentrant, so a statement issued from the fiber
      * that already holds the permit (a statement between a stream's batches, a nested savepoint) proceeds.
      *
      * The session mutex is never closed (nothing reachable closes it), so the [[Closed]] arm [[Meter.run]] adds is
      * unreachable; discharging it here keeps the statement rows free of a failure that cannot happen.
      *
      * The `op`'s own `Abort[SqlException]` is peeled to a [[Result]] BEFORE it crosses [[Meter.run]] and re-raised after. A
      * [[Meter]] mutex returns its permit on a normal completion and on a panic, but NOT on a typed `Abort` failure
      * ([[Meter]] releases through `Sync.ensure`, which does not run on the abort channel). A statement that fails with a
      * server error inside the mutex would therefore leak the permit, and the transaction's own rollback, which takes the same
      * mutex, would deadlock acquiring it. Handing `Meter.run` a value it treats as a success is what returns the permit on
      * every exit; the failure is re-raised unchanged once the permit is back.
      */
    private def serialised[A, S](meter: Meter)(op: => A < (S & Abort[SqlException]))(using
        Frame
    ): A < (S & Async & Abort[SqlException]) =
        Abort.run[Closed](meter.run(Abort.run[SqlException](op))).flatMap {
            case Result.Success(inner) => Abort.get(inner)
            case Result.Failure(c)     => bug(s"kyo.sql: session statement mutex unexpectedly closed: $c")
            case Result.Panic(t)       => Abort.error(Result.Panic(t))
        }

    /** Runs `op` on whichever connection the statement belongs on: the transaction's, when a fiber is inside one, or a pooled one otherwise,
      * leasing under this client's own [[config]].
      *
      * The routing every portable statement goes through, and the reason `query` inside a `transaction { ... }` body needs no receiver: the
      * fiber-local says which session, so the statement does not have to.
      */
    private[kyo] def routed[A](op: Connection => A < (Async & Abort[SqlException]))(using
        Frame
    ): A < (Async & Abort[SqlException]) =
        self.routedWith(self.config)(op)

    /** [[routed]] under an explicit in-force `config` rather than this client's own.
      *
      * The DSL run surface threads [[DB.State.config]] here, so a `withConfig` adjustment installed on the [[DB]] effect reaches the pooled
      * lease of a `.run` statement, while a direct call on a client in hand routes under the client's own [[config]] through [[routed]].
      */
    private[kyo] def routedWith[A](config: SqlConfig)(op: Connection => A < (Async & Abort[SqlException]))(using
        Frame
    ): A < (Async & Abort[SqlException]) =
        self.pinnedEntry.flatMap {
            case Present((conn, meter)) => self.serialised(meter)(op(conn))
            case Absent                 => runtime.leaseStatement(config)(op)
        }

    /** Renders `ast` for this client's engine and version, as the statement text and binds a wire call takes.
      *
      * The execution path's renderer, and the reason every statement this client sends targets the server it is actually talking to:
      * rendering against the dialect's capability floor instead would emit a construct a newer server has and, worse, one an older server
      * lacks, which the server would then reject.
      *
      * Distinct from [[render]] in two ways, both of which matter only here. It skips [[Sql.Rendered]]'s per-dialect map, which a
      * single-dialect render fills with one entry that the caller immediately takes back out, so a statement costs no map. And it converts
      * the render's raise into a typed failure, so a construct this engine cannot express aborts with
      * [[kyo.SqlUnsupportedDialectFeatureException]] rather than escaping as a panic.
      */
    private def renderForWire[A](ast: Sql[A])(using
        frame: Frame
    ): Idiom.Rendering < (Async & Abort[SqlException]) =
        self.serverVersion.map { version =>
            Abort.catching[SqlException](self.dialect.renderRaw(ast, Present(version), frame))
        }

    /** Returns the rows of a parameterised query, decoded as `A` through the supplied [[SqlSchema]].
      *
      * A dispatch entry the `.run` family's static path emits at its call site; not part of the user surface. Threads the fiber-local
      * [[SqlClient.txLocal]] so calls inside a `transaction { ... }` block use the bound connection. Each row decodes through the
      * [[SqlRow.Codec]] its backend attached.
      *
      * ==Error types==
      * This method can abort with any [[SqlException]] subtype:
      *   - [[SqlConnectionException]], pool exhausted, TCP failure, or acquire timeout before the query was sent.
      *   - [[SqlRequestException]], SQL serialization or parameter encoding failure before the query was sent.
      *   - [[SqlServerException]], the database rejected the query and returned an error response.
      *   - [[SqlDecodeException]], the query succeeded but a returned column value could not be converted to the target Scala type. Each row
      *     is decoded independently; a decode failure on one row aborts the entire `Chunk` result. Check the [[SqlSchema]] derivation or
      *     widen the column type to diagnose.
      *   - [[SqlUnsupportedException]], the [[SqlSchema]] decoder called a structural read operation (array, map) that the backend does not
      *     yet implement. Re-derive the schema without the unsupported structural type, or supply a custom decoder via
      *     [[SqlCodec.of]].
      *
      * @tparam A
      *   the decoded row type; must have a [[SqlSchema]] instance
      */
    private[kyo] def internalExecuteQuery[A](
        sql: String,
        params: Chunk[BoundValue[?]],
        config: SqlConfig
    )(using
        frame: Frame,
        schema: SqlSchema[A]
    ): Chunk[A] < (Async & Abort[SqlException]) =
        val rowsK: Chunk[SqlRow] < (Async & Abort[SqlException]) =
            self.routedWith(config)(_.extendedQuery(sql, params))
        rowsK.map { rows =>
            Kyo.foreach(rows) { row =>
                // The row carries its own backend codec, so the decode needs no driver check here. The renderer emitted
                // the columns in field order, so the decode is positional by construction and consults no naming: a
                // SqlNaming given at the run site cannot change (or transpose) a DSL read.
                val decodeK: A < Abort[SqlDecodeException] = row.decodeRow[A](schema, Maybe.empty, SqlRow.FieldMatch.Positional)
                Abort.recover[SqlDecodeException](
                    (e: SqlDecodeException) => Abort.fail(e: SqlException),
                    t => Abort.error(Result.Panic(t))
                )(decodeK)
            }
        }
    end internalExecuteQuery

    /** Executes a parameterised DML statement and returns the affected-row count.
      *
      * A dispatch entry the `.run` family's static path emits at its call site; not part of the user surface. Threads the fiber-local
      * [[SqlClient.txLocal]] so calls inside a `transaction { ... }` block use the bound connection.
      */
    private[kyo] def internalExecuteUpdate(sql: String, params: Chunk[BoundValue[?]], config: SqlConfig)(using
        Frame
    ): Long < (Async & Abort[SqlException]) =
        self.routedWith(config)(_.extendedExecute(sql, params))

    /** Executes a parameterised `INSERT` and returns an [[SqlClient.InsertOutcome]].
      *
      * A dispatch entry the `.run` family's static path emits at its call site; not part of the user surface. Threads the fiber-local
      * [[SqlClient.txLocal]] so calls inside a `transaction { ... }` block use the bound connection.
      */
    private[kyo] def internalExecuteInsert(sql: String, params: Chunk[BoundValue[?]], config: SqlConfig)(using
        Frame
    ): SqlClient.InsertOutcome < (Async & Abort[SqlException]) =
        self.routedWith(config)(_.extendedExecuteInsert(sql, params))

    /** Runs `body` inside a database transaction.
      *
      * Acquires a single connection from the pool and binds it to the fiber-local [[SqlClient.txLocal]] for the duration of `body`. All
      * `query`/`execute`/`streamQuery` calls within `body` automatically use the bound connection.
      *
      * On success: sends `COMMIT`. On any failure: sends `ROLLBACK` and re-raises the value on the channel it was raised on, whether that is
      * the declared `Abort[SqlException]`, a typed failure carried by the body's own effect row, or a `Result.Panic`.
      *
      * ==Nested transactions==
      *
      * If `transaction { ... transaction { ... } ... }` is detected (the inner call sees an active [[TransactionContext]] from
      * [[SqlClient.txLocal]]), the inner call uses a `SAVEPOINT` instead of a new `BEGIN`:
      *
      *   - Inner begin issues `SAVEPOINT kyo_sp_<depth>_<suffix>`
      *   - Inner success issues `RELEASE SAVEPOINT kyo_sp_<depth>_<suffix>`
      *   - Inner failure issues `ROLLBACK TO SAVEPOINT kyo_sp_<depth>_<suffix>`, and the outer transaction continues
      *
      * The inner call does NOT acquire a new connection, it reuses the outer's.
      *
      * WARNING: The `isolation` parameter is silently ignored on nested transactions; PostgreSQL and MySQL do not support per-savepoint
      * isolation levels.
      *
      * @param isolation
      *   optional isolation level; [[Absent]] uses the server default (PostgreSQL: `READ COMMITTED`)
      * @param readOnly
      *   if `true`, sends `BEGIN … READ ONLY` (or just reuses the outer connection for nested calls)
      * @param body
      *   the computation to run inside the transaction
      */
    private def transactionImpl[A, S](
        isolation: Maybe[SqlClient.IsolationLevel],
        readOnly: Boolean,
        body: A < S
    )(using Frame): A < (S & Async & Abort[SqlException]) =
        self.enclosingTransaction.map {
            case Present(ctx) =>
                // Nested: a savepoint inside the transaction the outer call opened, on its connection.
                // isolation and readOnly have nowhere to go here, because neither engine supports either
                // per savepoint; the outer transaction's values stand for the whole nest.
                self.savepointName(ctx.depth + 1).map { spName =>
                    val conn     = ctx.connection
                    val innerCtx = ctx.copy(depth = ctx.depth + 1, savepointStack = spName +: ctx.savepointStack)
                    self.serialised(ctx.meter)(conn.savepoint(spName)).andThen {
                        Abort.run[Any](SqlClient.txLocal.let(Present(innerCtx))(body)).map {
                            case Result.Success(a) =>
                                self.serialised(ctx.meter)(conn.releaseSavepoint(spName)).andThen(a)
                            case Result.Failure(e) =>
                                self.serialised(ctx.meter)(conn.rollbackToSavepoint(spName)).andThen(SqlClient.reraise[A](e))
                            case Result.Panic(t) =>
                                self.serialised(ctx.meter)(conn.rollbackToSavepoint(spName)).andThen(Abort.error(Result.Panic(t)))
                        }
                    }
                }

            case Absent =>
                // Two ways to be outermost, and they differ in who OWNS the connection afterwards.
                //
                // Inside one of this client's advisory locks, the transaction runs on the lock's session and must not
                // be released at COMMIT, because the lock still owns that connection past the transaction's end and
                // its release has to reach the same session that took it. Reusing the lease branch below would hand
                // the connection back at COMMIT while the pending unlock still points at it: a use-after-release,
                // with an advisory lock left held on whatever session the pool gave out next.
                //
                // NoRoute: the second branch establishes the pin itself, so it leases independently. Consulting the
                // transaction local here would be circular, and it is Absent by construction anyway; the explicit
                // opt-out is written regardless, because "already guarded upstream" is the reasoning that lets
                // someone delete the upstream guard.
                self.enclosingLock.map {
                    case Present(lock) => self.beginOn(lock.connection, lock.meter, isolation, readOnly)(body)
                    case Absent =>
                        self.useIndependentConnection { conn =>
                            Meter.initMutexUnscoped.map(meter => self.beginOn(conn, meter, isolation, readOnly)(body))
                        }
                }
        }
    end transactionImpl

    /** Opens a real transaction on `conn`, runs `body` with `conn` published as this fiber's transaction, and commits or rolls back.
      *
      * Shared by the lock-held and independently-leased branches. They differ only in where the connection comes from and who releases
      * it, and keeping the BEGIN, the publish, and the three exit edges in one place is what stops the two `transaction` variants drifting apart.
      */
    private def beginOn[A, S](conn: Connection, meter: Meter, isolation: Maybe[SqlClient.IsolationLevel], readOnly: Boolean)(
        body: A < S
    )(using Frame): A < (S & Async & Abort[SqlException]) =
        val ctx = TransactionContext(self, conn, 0, Chunk.empty, meter)
        self.beginLogged(conn, "tx").andThen {
            // The control statements hold the session mutex too: a fiber the body forked and never awaited can
            // still be mid-statement when the body returns, and COMMIT must queue behind it, not interleave.
            self.serialised(meter)(conn.beginTransaction(isolation, readOnly)).andThen {
                Abort.run[Any](SqlClient.txLocal.let(Present(ctx))(body)).flatMap {
                    case Result.Success(a) =>
                        self.serialised(meter)(self.commitLogged(conn, "tx")).andThen(a)
                    case Result.Failure(e) =>
                        self.serialised(meter)(self.rollbackLogged(conn, "tx")).andThen(SqlClient.reraise[A](e))
                    case Result.Panic(t) =>
                        self.serialised(meter)(self.rollbackLogged(conn, "tx")).andThen(Abort.error(Result.Panic(t)))
                }
            }
        }
    end beginOn

    /** Names a savepoint for nesting depth `depth`.
      *
      * `kyo_sp_` is reserved: a savepoint a caller establishes by hand must not collide with one a nested `transaction` established, and a
      * reserved prefix is what makes that checkable by eye in a server log. The depth is there for the same reader, and the random suffix keeps
      * two sibling transactions that reach the same depth on the same connection distinct.
      */
    private def savepointName(depth: Int)(using Frame): String < Sync =
        Random.nextStringAlphanumeric(20).map(suffix => s"kyo_sp_${depth}_$suffix")

    private def beginLogged(conn: Connection, label: String)(using Frame): Unit < Sync =
        Log.debug(s"kyo.sql: $label begin connection=${conn.id}")

    private def commitLogged(conn: Connection, label: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        Log.debug(s"kyo.sql: $label commit connection=${conn.id}")
            .andThen(conn.commitTransaction)
            .andThen(runtime.pool.metrics.recordTransactionCommitted)

    private def rollbackLogged(conn: Connection, label: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        Log.debug(s"kyo.sql: $label rollback connection=${conn.id}")
            .andThen(conn.rollbackTransaction)
            .andThen(runtime.pool.metrics.recordTransactionRolledBack)

    /** Takes the lock on `conn`, publishes `conn` as this fiber's locked session while `body` runs, and releases the lock however `body` ends.
      *
      * Publishing is what makes a statement inside `body` reach the lock's own session rather than a second one. Without it a one-connection pool
      * deadlocks: the lock holds the only slot and the first statement inside waits for a permit that cannot come back until the lock releases.
      * Routing buys progress rather than exclusion; a statement still has to be inside the body to be routed.
      */
    private def lockedOn[A, S](conn: Connection, meter: Meter, key: Long, timeout: Maybe[Duration])(
        body: A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Abort[SqlException]) =
        self.serialised(meter)(conn.acquireAdvisoryLock(key, timeout)).andThen {
            Scope.run {
                // The release is a scope finalizer so it fires on every exit edge, interruption included: the
                // pool's reclaim knows nothing about advisory locks, so an unreleased lock would ride the pooled
                // session to its next borrower. Its own failure is swallowed so a failed unlock cannot replace
                // what `body` reported.
                Scope.ensure(Abort.run[SqlException](self.serialised(meter)(conn.releaseAdvisoryLock(key))).unit).andThen {
                    SqlClient.lockLocal.let(Present(SqlClient.LockContext(self, conn, meter)))(body)
                }
            }
        }
    end lockedOn

end SqlClient

/** Opens clients, and mirrors the client surface for programs that installed one ambiently.
  *
  * [[init]] and its variants are the portable entry point: they resolve the URL's scheme against the backends on the compile classpath and
  * return a plain [[SqlClient]]. A URL whose scheme no backend claims is a compile-time warning when the URL is a literal, naming the schemes that
  * are available, and a typed [[kyo.SqlConnectionUnsupportedSchemeException]] otherwise. A URL the compiler could not read has a second
  * chance before that failure: a scheme no compile-time factory claims is looked for among the backends the running program can discover.
  * See [[kyo.db.Backend]] for the two tiers and which one wins a scheme both claim.
  *
  * The mirrors ([[transaction]], [[executeRaw]], [[address]]) read the [[DB]] effect's client and delegate to it, so a program that supplied
  * one through [[DB.run]] need not thread a receiver through every layer.
  */
object SqlClient:

    /** Result of executing an INSERT statement.
      *
      * @param affectedRows
      *   per-statement row count reported by the backend (`CommandComplete` on Postgres, OK packet `affectedRows` on MySQL).
      * @param generatedKey
      *   [[InsertOutcome.GeneratedKey]] sum distinguishing three cases: a concrete value, the absence of an auto-key column in the schema,
      *   or the backend not reporting one despite the schema having an auto-key column. See [[InsertOutcome.GeneratedKey]] for per-case
      *   backend semantics.
      *
      * IMPORTANT: Multi-row INSERT id semantics differ per backend:
      *   - Postgres: returns the LAST generated id in the batch (the renderer auto-emits `RETURNING <pk>` and the driver reads the final
      *     DataRow).
      *   - MySQL: returns the FIRST generated id in the batch (the OK packet's `lastInsertId` field).
      * Single-row INSERTs are unambiguous on both backends.
      */
    // Multi-row insert generated keys differ by engine: PostgreSQL reports the batch's LAST key (the final
    // RETURNING row) while MySQL reports the FIRST (the OK packet's lastInsertId).
    final case class InsertOutcome(affectedRows: Long, generatedKey: InsertOutcome.GeneratedKey) derives CanEqual

    object InsertOutcome:

        /** Outcome of the generated-key lookup for an INSERT.
          *
          * A sum distinguishing three cases (a concrete value, no auto-key column, or the backend not reporting one), so callers dispatch
          * on the root cause rather than only "present/absent".
          *
          *   - [[GeneratedKey.Value]], backend reported a non-zero generated id. On Postgres the renderer emitted `RETURNING <pk>` for an
          *     auto-key column and decoded the DataRow's id; on MySQL the OK packet's `lastInsertId` field was non-zero.
          *   - [[GeneratedKey.NoAutoKey]], the target table has no auto-incrementing column, so no id was requested. This is Postgres-only:
          *     the renderer did not emit `RETURNING`. MySQL cannot distinguish "no AUTO_INCREMENT column" from "column present but zero"
          *     from the OK packet alone, so MySQL always uses [[GeneratedKey.Unavailable]] instead, never [[GeneratedKey.NoAutoKey]].
          *   - [[GeneratedKey.Unavailable]], the backend did not surface a concrete id. On MySQL this covers both "no AUTO_INCREMENT
          *     column" and "column present but the user supplied an explicit non-zero value, suppressing generation": the OK packet
          *     reports `last_insert_id == 0` in both cases and MySQL surfaces them uniformly as `Unavailable`. On Postgres it applies
          *     when the schema has an auto-key column yet the backend reports no value.
          */
        enum GeneratedKey derives CanEqual:
            case Value(key: Long)
            case NoAutoKey
            case Unavailable
        end GeneratedKey

        object GeneratedKey:
            /** Predicate: a concrete id was reported. */
            def isPresent(gk: GeneratedKey): Boolean = gk match
                case Value(_)                => true
                case NoAutoKey | Unavailable => false

            /** Extracts the id when [[Value]], or returns the default-supplier's result otherwise, with `fold`-style ergonomics. */
            inline def foldKey[A](gk: GeneratedKey)(ifAbsent: => A)(f: Long => A): A = gk match
                case Value(k)                => f(k)
                case NoAutoKey | Unavailable => ifAbsent
        end GeneratedKey

    end InsertOutcome

    /** Standard SQL transaction isolation levels.
      *
      * Controls the visibility of uncommitted data written by concurrent transactions. Higher isolation reduces anomalies at the cost of
      * concurrency.
      *
      * PostgreSQL default: ReadCommitted. MySQL InnoDB default: RepeatableRead.
      */
    enum IsolationLevel derives CanEqual:
        /** Allows reading uncommitted data from other transactions (dirty reads). Lowest isolation; rarely used in practice. */
        case ReadUncommitted

        /** Only committed data is visible. Prevents dirty reads; phantom reads and non-repeatable reads may occur. */
        case ReadCommitted

        /** Snapshot of the database at transaction start. Prevents dirty and non-repeatable reads; phantom reads may occur in some engines. */
        case RepeatableRead

        /** Strictest level: transactions execute as if they were serial. Prevents all read anomalies; lowest concurrency. */
        case Serializable

        /** The standard SQL keyword phrase for this level (e.g. `"READ COMMITTED"`).
          *
          * Shared by every engine's transaction-control exchange: ANSI SQL defines one vocabulary for isolation levels, and both
          * PostgreSQL's `BEGIN ISOLATION LEVEL ...` and MySQL's `SET TRANSACTION ISOLATION LEVEL ...` accept it verbatim.
          */
        def sqlKeyword: String = this match
            case ReadUncommitted => "READ UNCOMMITTED"
            case ReadCommitted   => "READ COMMITTED"
            case RepeatableRead  => "REPEATABLE READ"
            case Serializable    => "SERIALIZABLE"
    end IsolationLevel

    /** Accumulates pipeline statements for execution via [[SqlClient.pipeline]].
      *
      * Obtain an instance via `client.pipeline { builder => ... }`. Call [[execute]] or [[query]] to register each statement; on body
      * completion the caller renders all of them for the server it is talking to and flushes them in one TCP write.
      *
      * A registration is a `Unit < Sync` rather than a plain `Unit` because it writes into the builder: making the write part of the
      * computation is what lets `pipeline`'s result be run more than once, each run registering its statements into a builder of its own.
      *
      * Thread-safety: A [[PipelineBuilder]] is NOT thread-safe. It is always consumed sequentially within the pipeline body before flush.
      */
    final class PipelineBuilder:
        private val stmts = ChunkBuilder.init[Sql.Executable[?]]

        /** Registers a DML statement for later batch execution. The affected-row count is reported by the outer `pipeline` call as part of
          * the result [[Chunk]], not here.
          *
          * Equivalent to [[query]]: both append to the same buffer. The two names document caller intent (DML vs SELECT) without any
          * behavioral difference in the pipeline.
          */
        def execute(sql: String)(using Frame): Unit < Sync =
            register(Sql.Fragment.lit[Any](sql))

        /** Registers a statement built by the DSL, parameters included. The bind values travel inside the [[Sql.Executable]] and are
          * rendered for the executing client's dialect and server version, exactly as [[SqlClient.execute]] renders a single statement.
          */
        def execute[A](executable: Sql.Executable[A])(using Frame): Unit < Sync =
            register(executable)

        /** Registers a query statement for later batch execution. The rows are reported by the outer `pipeline` call as part of the result
          * [[Chunk]], not here.
          *
          * Equivalent to [[execute]]: both append to the same buffer. The two names document caller intent (SELECT vs DML) without any
          * behavioral difference in the pipeline.
          */
        def query(sql: String)(using Frame): Unit < Sync =
            register(Sql.Fragment.lit[Any](sql))

        /** Registers a query built by the DSL, parameters included. See the [[Sql.Executable]] overload of [[execute]]. */
        def query[A](executable: Sql.Executable[A])(using Frame): Unit < Sync =
            register(executable)

        private def register(executable: Sql.Executable[?])(using Frame): Unit < Sync =
            Sync.defer(discard(stmts += executable))

        /** Returns the statements registered so far, in submission order. Read by [[SqlClient.pipeline]] once the body has run. */
        private[kyo] def registered(using Frame): Chunk[Sql.Executable[?]] < Sync =
            Sync.defer(stmts.result())
    end PipelineBuilder

    /** Companion of [[PipelineBuilder]] hosting the per-statement outcome type produced by [[SqlClient.pipeline]]. */
    object PipelineBuilder:

        /** Per-statement outcome: the rows and affected-row count returned for one pipelined statement.
          *
          * Delivered as the success payload of a [[kyo.Result]] element in the [[Chunk]] that
          * [[SqlClient.pipeline]] returns; per-statement failures surface as [[kyo.Result.Failure]]
          * around the raised [[SqlException]], WITHOUT aborting subsequent statements.
          *
          * @param rows
          *   rows returned by the statement (empty for DML statements such as INSERT/UPDATE/DELETE)
          * @param affectedRowCount
          *   number of rows affected (0 for query statements that return rows; populated for DML)
          */
        final case class Outcome(rows: Chunk[SqlRow], affectedRowCount: Long) derives CanEqual

    end PipelineBuilder

    /** Every Stat instrument the pool, connections, leases, transactions, and retry path record through.
      *
      * When `metricsEnabled` is `false`, every method is a no-op (zero allocation beyond the call). When `metricsEnabled` is `true`, metrics
      * are registered under `metricsScope` (default `"kyo.sql"`).
      *
      * Metric names:
      *   - Counters: `connections_acquired`, `connections_released`, `connections_discarded`, `queries_executed`, `queries_failed`,
      *     `retries_attempted`, `leases_acquired`, `cancels_fired`, `cancels_timed_out`, `transactions_committed`,
      *     `transactions_rolled_back`.
      *   - Histograms: `query_duration_ms`, `pool_acquire_wait_ms`, `lease_acquire_latency_ms`.
      *   - Gauges: `leases_in_flight`.
      *
      * The gauge is the one instrument that needs live state rather than a running total, so this class holds that state: a gauge reports
      * what is true now, and the only thing that knows how many leases are open right now is whatever counts them. [[recordLeaseAcquired]]
      * and [[recordLeaseReleased]] move it, and the pool calls them on the two edges of every lease.
      */
    final private[kyo] class Metrics(metricsEnabled: Boolean, metricsScope: Maybe[String]):

        private val scopeName: String = metricsScope.getOrElse("kyo.sql")

        // Split scope name on '.' to build nested Stat scopes.
        private val stat: Stat =
            if metricsEnabled then
                val parts = scopeName.split('.')
                if parts.length == 1 then
                    Stat.initScope(parts(0))
                else
                    Stat.initScope(parts(0), parts.drop(1)*)
                end if
            else
                // Placeholder scope, never instrumented, so the disabled path allocates no real instruments.
                Stat.initScope("__noop__")

        // --- Counters ---

        private val _connectionsAcquired: Counter =
            if metricsEnabled then stat.initCounter("connections_acquired", "Number of connections acquired from the pool")
            else Metrics.noopCounter

        private val _connectionsReleased: Counter =
            if metricsEnabled then stat.initCounter("connections_released", "Number of connections released back to the pool")
            else Metrics.noopCounter

        private val _connectionsDiscarded: Counter =
            if metricsEnabled then stat.initCounter("connections_discarded", "Number of connections discarded from the pool")
            else Metrics.noopCounter

        private val _queriesExecuted: Counter =
            if metricsEnabled then stat.initCounter("queries_executed", "Number of queries successfully executed")
            else Metrics.noopCounter

        private val _queriesFailed: Counter =
            if metricsEnabled then stat.initCounter("queries_failed", "Number of queries that resulted in an error")
            else Metrics.noopCounter

        private val _retriesAttempted: Counter =
            if metricsEnabled then stat.initCounter("retries_attempted", "Number of retry attempts made")
            else Metrics.noopCounter

        private val _leasesAcquired: Counter =
            if metricsEnabled then stat.initCounter("leases_acquired", "Number of connection leases handed to a caller")
            else Metrics.noopCounter

        private val _cancelsFired: Counter =
            if metricsEnabled then stat.initCounter("cancels_fired", "Number of wire cancel routines fired by an interrupt")
            else Metrics.noopCounter

        private val _cancelsTimedOut: Counter =
            if metricsEnabled then stat.initCounter("cancels_timed_out", "Number of reclaim chains that overran the cancel timeout")
            else Metrics.noopCounter

        private val _transactionsCommitted: Counter =
            if metricsEnabled then stat.initCounter("transactions_committed", "Number of transactions committed")
            else Metrics.noopCounter

        private val _transactionsRolledBack: Counter =
            if metricsEnabled then stat.initCounter("transactions_rolled_back", "Number of transactions rolled back")
            else Metrics.noopCounter

        // --- Histograms ---

        private val _queryDurationMs: Histogram =
            if metricsEnabled then stat.initHistogram("query_duration_ms", "Query execution duration in milliseconds")
            else Metrics.noopHistogram

        private val _poolAcquireWaitMs: Histogram =
            if metricsEnabled then
                stat.initHistogram("pool_acquire_wait_ms", "Time spent waiting to acquire a connection from the pool in milliseconds")
            else Metrics.noopHistogram

        private val _leaseAcquireLatencyMs: Histogram =
            if metricsEnabled then
                stat.initHistogram("lease_acquire_latency_ms", "Time from requesting a lease to holding a connection, in milliseconds")
            else Metrics.noopHistogram

        // --- Gauges ---

        // Unsafe: a Gauge is read by the stats registry outside any fiber, so its backing value cannot be a
        // suspended read. The counter is written only by recordLeaseAcquired / recordLeaseReleased, both of
        // which already run inside Sync.
        private val leasesInFlightState: AtomicInt.Unsafe =
            AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)

        private val _leasesInFlight: Gauge =
            if metricsEnabled then
                stat.initGauge("leases_in_flight", "Connection leases currently held by a caller")(
                    leasesInFlightState.get()(using AllowUnsafe.embrace.danger).toDouble
                )
            else Metrics.noopGauge

        // --- Instrument accessors ---

        def connectionsAcquired: Counter     = _connectionsAcquired
        def connectionsReleased: Counter     = _connectionsReleased
        def connectionsDiscarded: Counter    = _connectionsDiscarded
        def queriesExecuted: Counter         = _queriesExecuted
        def queriesFailed: Counter           = _queriesFailed
        def retriesAttempted: Counter        = _retriesAttempted
        def leasesAcquired: Counter          = _leasesAcquired
        def cancelsFired: Counter            = _cancelsFired
        def cancelsTimedOut: Counter         = _cancelsTimedOut
        def transactionsCommitted: Counter   = _transactionsCommitted
        def transactionsRolledBack: Counter  = _transactionsRolledBack
        def queryDurationMs: Histogram       = _queryDurationMs
        def poolAcquireWaitMs: Histogram     = _poolAcquireWaitMs
        def leaseAcquireLatencyMs: Histogram = _leaseAcquireLatencyMs
        def leasesInFlight: Gauge            = _leasesInFlight

        // --- Histogram summary ---

        def queryDurationSummary(using Frame): kyo.stats.internal.Summary < Sync =
            Sync.Unsafe.defer(_queryDurationMs.unsafe.summary())

        def poolAcquireWaitSummary(using Frame): kyo.stats.internal.Summary < Sync =
            Sync.Unsafe.defer(_poolAcquireWaitMs.unsafe.summary())

        // --- Instrumented lifecycle methods ---

        /** Increments `connections_acquired`. */
        def recordAcquire(using Frame): Unit < Sync =
            _connectionsAcquired.inc

        /** Increments `connections_released`. */
        def recordRelease(using Frame): Unit < Sync =
            _connectionsReleased.inc

        /** Increments `connections_discarded`. */
        def recordDiscard(using Frame): Unit < Sync =
            _connectionsDiscarded.inc

        /** Increments `retries_attempted`. */
        def recordRetry(using Frame): Unit < Sync =
            _retriesAttempted.inc

        /** Records `waitMs` in `pool_acquire_wait_ms`. */
        def recordPoolAcquireWait(waitMs: Long)(using Frame): Unit < Sync =
            _poolAcquireWaitMs.observe(waitMs)

        /** Increments `leases_acquired` and `leases_in_flight`, and records `latencyMs` in `lease_acquire_latency_ms`. */
        def recordLeaseAcquired(latencyMs: Long)(using Frame): Unit < Sync =
            Sync.Unsafe.defer(discard(leasesInFlightState.incrementAndGet()))
                .andThen(_leasesAcquired.inc)
                .andThen(_leaseAcquireLatencyMs.observe(latencyMs))

        /** Decrements `leases_in_flight`. Fires on every lease exit edge, including the one that hands the connection to a reclaim. */
        def recordLeaseReleased(using Frame): Unit < Sync =
            Sync.Unsafe.defer(discard(leasesInFlightState.decrementAndGet()))

        /** Increments `cancels_fired`. */
        def recordCancelFired(using Frame): Unit < Sync =
            _cancelsFired.inc

        /** Increments `cancels_timed_out`. */
        def recordCancelTimedOut(using Frame): Unit < Sync =
            _cancelsTimedOut.inc

        /** Increments `transactions_committed`. */
        def recordTransactionCommitted(using Frame): Unit < Sync =
            _transactionsCommitted.inc

        /** Increments `transactions_rolled_back`. */
        def recordTransactionRolledBack(using Frame): Unit < Sync =
            _transactionsRolledBack.inc

        /** Runs a query body, timing its execution and incrementing `queries_executed` or `queries_failed` on exit.
          *
          * When disabled, the body is returned unchanged and widens into the declared row, so the disabled path costs nothing. The Sync effect is
          * always in the result type since callers already have Async in scope (Async subsumes Sync), so this adds nothing to the
          * erasure.
          */
        def timedQuery[A, S](body: A < (S & Abort[SqlException]))(using Frame): A < (S & Abort[SqlException] & Sync) =
            if !metricsEnabled then
                body
            else
                Clock.stopwatch.flatMap { sw =>
                    Abort.run[SqlException](body).flatMap { result =>
                        sw.elapsed.flatMap { dur =>
                            _queryDurationMs.observe(dur.toMillis).andThen {
                                result match
                                    case Result.Success(a) =>
                                        _queriesExecuted.inc.andThen(a: A < (S & Abort[SqlException] & Sync))
                                    case Result.Failure(e) =>
                                        _queriesFailed.inc.andThen(Abort.fail[SqlException](e): A < (S & Abort[SqlException] & Sync))
                                    case Result.Panic(t) =>
                                        _queriesFailed.inc.andThen(Abort.error(Result.Panic(t)): A < (S & Abort[SqlException] & Sync))
                            }
                        }
                    }
                }
    end Metrics

    private[kyo] object Metrics:

        /** No-op counter: every method does nothing. Used when `metricsEnabled = false`. */
        private[Metrics] val noopCounter: Counter = new Counter:
            val unsafe                                 = new kyo.stats.internal.UnsafeCounter
            def get(using Frame): Long < Sync          = 0L
            def inc(using Frame): Unit < Sync          = ()
            def add(v: Long)(using Frame): Unit < Sync = ()

        /** No-op histogram: observations are discarded, and `summary` reports the empty distribution. Used when `metricsEnabled = false`. */
        private[Metrics] val noopHistogram: Histogram = new Histogram:
            val unsafe                                       = new kyo.stats.internal.UnsafeHistogram(Array.empty[Double])
            def observe(v: Long)(using Frame): Unit < Sync   = ()
            def observe(v: Double)(using Frame): Unit < Sync = ()
            // Reads the backing instrument rather than a hand-built zero: nothing ever observes into it, so it
            // answers count/min/max/sum of 0 with empty buckets, and that stays true if Summary gains a field.
            def summary(using Frame): kyo.stats.internal.Summary < Sync = Sync.Unsafe.defer(unsafe.summary())

        /** No-op gauge: always reports zero. Used when `metricsEnabled = false`. */
        private[Metrics] val noopGauge: Gauge = new Gauge:
            val unsafe                              = new kyo.stats.internal.UnsafeGauge(() => 0.0d)
            def collect(using Frame): Double < Sync = 0.0d

        /** Construct with explicit enable flag and optional scope override. */
        def apply(metricsEnabled: Boolean, metricsScope: Maybe[String]): Metrics =
            new Metrics(metricsEnabled, metricsScope)
    end Metrics

    // --- Fiber-local transaction + lock context ---
    //
    // The client is carried by the DB effect (DB.scala), whose DB.State holds the client and the config in force;
    // it is not a fiber-local. Only these two connection-naming locals are, because each names a single socket that
    // tolerates one user at a time: a statement inside a transaction or lock body must reach the pinned connection
    // without threading it through user code.

    /** Fiber-local active transaction context.
      *
      * [[Absent]] when outside a `transaction { ... }` block. [[Present]] when inside one, queries detect this and use the bound connection
      * directly instead of acquiring from the pool.
      *
      * Read it through [[SqlClient.enclosingTransaction]] rather than directly. The value it holds belongs to the fiber and names a connection from
      * ONE client's pool, so consuming it without comparing that client is what would route a second client's statement onto the first client's
      * session.
      *
      * INHERITABLE, so a child fiber inside a `transaction { ... }` body sees the transaction and its statements run on the transaction's connection.
      * That is the correct choice, though the alternative is superficially safer:
      *
      * A child fiber holding a value that names a connection IS a second concurrent user of one socket, so `Async.parallel` inside a transaction body
      * puts N fibers on the transaction's connection and they interleave writes and reads. Making this local non-inheritable removes that, and the
      * reason it is still wrong is ATOMICITY: a child that cannot see the transaction falls through to the pool, gets its own connection, and
      * AUTOCOMMITS. `transaction { Async.parallel(insert(a), insert(b)) }` that then aborts would leave both rows committed, because the rollback
      * reaches only the parent's connection. That trades a protocol desync, which is loud and immediate and commits nothing, for silent loss of the
      * one guarantee the construct exists to provide.
      *
      * The concurrent-user problem the inheritance creates is solved by the context's statement mutex: every statement routed onto the
      * pinned connection runs holding [[kyo.internal.TransactionContext.meter]]'s single permit, so parallel statements in a transaction
      * body queue on the session instead of corrupting each other.
      *
      * The rule, which the sibling below also records: a local naming a CONNECTION creates a concurrency problem, and the fix is a lock rather
      * than hiding the connection from the child.
      */
    private[kyo] val txLocal: Local[Maybe[TransactionContext]] =
        Local.init(Absent)

    /** Fiber-local advisory lock context: the session an enclosing [[SqlClient.withAdvisoryLock]] is holding, and the client holding it.
      *
      * Kept separate from [[txLocal]] rather than reusing it, because a lock is not a transaction. Publishing a lock through [[txLocal]] would make
      * an advisory lock silently join an enclosing transaction, so a rollback would drop the connection the lock lives on, and both engines scope
      * an advisory lock to the session that took it, so the release has to reach that session or it frees nothing.
      *
      * Read it through [[SqlClient.enclosingLock]], for the same reason as [[txLocal]].
      *
      * INHERITABLE, and the same reasoning as [[txLocal]] applies with the loss named differently. Non-inheritable would send a child fiber's
      * statement to its own lease, and because both engines scope an advisory lock to the session that took it, that statement would then run OUTSIDE
      * the lock. Work a caller wrote specifically to be serialised would silently not be, which is the same shape of failure as losing atomicity:
      * quiet, and in the direction of the guarantee the caller asked for.
      *
      * So a child fiber does reach the locked session, and the concurrent statements it can now issue serialise on the context's
      * statement mutex, exactly as inside a transaction body.
      */
    private[kyo] val lockLocal: Local[Maybe[SqlClient.LockContext]] =
        Local.init(Absent)

    /** The session an enclosing [[SqlClient.withAdvisoryLock]] pinned, paired with the client that owns the pool it came from.
      *
      * `client` is compared by reference, which is what "the same client" means here: two clients built from one URL have two pools and two sets of
      * connections, so routing between them would be as wrong as routing between two unrelated ones.
      */
    final private[kyo] case class LockContext(client: SqlClient, connection: Connection, meter: Meter)

    /** Opens a client for `rawUrl`, bound to the enclosing [[Scope]], which closes it when the scope exits.
      *
      * The URL's scheme picks the backend from those on the compile classpath, and the value returned names no engine, so the program that
      * uses it runs against either. Changing `postgres://…` to `mysql://…` retargets the other engine with no other edit, provided its
      * artifact is a dependency.
      *
      * When `rawUrl` is a literal, two mistakes warn at the call site instead of waiting for first connect: no backend artifact on the
      * classpath at all, and a scheme no backend claims. They warn rather than fail, because runtime discovery can still supply the
      * backend. A URL computed at runtime cannot be checked that way, so it resolves in full at open time: the
      * compile-time factories first, then the backends the running program can discover, and
      * [[kyo.SqlConnectionUnsupportedSchemeException]] when neither claims the scheme.
      *
      * The returned client is not installed as the ambient client. Use [[initWith]] when the statements should find the client ambiently, or
      * supply it yourself to [[DB.run]].
      *
      * @param rawUrl
      *   database URL in the form `<scheme>://user:pw@host:port/db[?opts]`
      */
    inline def init(inline rawUrl: String)(using Frame): SqlClient < (Async & Scope & Abort[SqlException]) =
        init(rawUrl, SqlConfig.default)

    /** Opens a client for `rawUrl` under `config`, bound to the enclosing [[Scope]]. See the single-argument [[init]] for the rest.
      *
      * @param config
      *   settings applied over the URL's own options; the merged result is what the returned client reports as its [[SqlClient.config]] and
      *   what every operation on it reads
      */
    inline def init(inline rawUrl: String, config: SqlConfig)(using Frame): SqlClient < (Async & Scope & Abort[SqlException]) =
        kyo.internal.SqlClientInitMacro.checkScheme(rawUrl)
        openScoped(rawUrl, config, Backend.Registry.current)
    end init

    /** Opens a client for `rawUrl` bound to the enclosing [[Scope]] and runs `f` with it.
      *
      * Warm-up completes before `f` is called, so the connections `minConnections` asked for are ready. Exactly [[init]] plus the
      * callback: the client is NOT installed ambiently, because supplying the `DB` effect is [[DB.run]]'s job alone. A body that runs
      * statements through the ambient path wraps itself in `DB.run(client)(...)`, or uses `DB.run(rawUrl)(...)` and never holds a client.
      *
      * @tparam B
      *   the result type of `f`
      * @tparam S
      *   the effect row of `f`
      * @param rawUrl
      *   database URL in the form `<scheme>://user:pw@host:port/db[?opts]`
      * @param f
      *   function receiving the opened client
      */
    inline def initWith[B, S](inline rawUrl: String)(inline f: SqlClient => B < S)(using
        Frame
    ): B < (S & Async & Scope & Abort[SqlException]) =
        initWith(rawUrl, SqlConfig.default)(f)

    /** Opens a client for `rawUrl` under `config`, bound to the enclosing [[Scope]], and runs `f` with it. See the one-argument
      * [[initWith]] for the rest.
      */
    inline def initWith[B, S](inline rawUrl: String, config: SqlConfig)(inline f: SqlClient => B < S)(using
        Frame
    ): B < (S & Async & Scope & Abort[SqlException]) =
        kyo.internal.SqlClientInitMacro.checkScheme(rawUrl)
        openScoped(rawUrl, config, Backend.Registry.current).flatMap(f)
    end initWith

    /** Opens a client for `rawUrl` with no cleanup registered. The caller closes it with [[SqlClient.close]].
      *
      * Everything else matches [[init]], including the compile-time scheme checks and the fact that the returned client is not installed
      * ambiently.
      */
    inline def initUnscoped(inline rawUrl: String)(using Frame): SqlClient < (Async & Abort[SqlException]) =
        initUnscoped(rawUrl, SqlConfig.default)

    /** Opens a client for `rawUrl` under `config` with no cleanup registered. See the single-argument [[initUnscoped]]. */
    inline def initUnscoped(inline rawUrl: String, config: SqlConfig)(using Frame): SqlClient < (Async & Abort[SqlException]) =
        kyo.internal.SqlClientInitMacro.checkScheme(rawUrl)
        openUnscoped(rawUrl, config, Backend.Registry.current)
    end initUnscoped

    /** Opens a client for `rawUrl` with no cleanup registered and runs `f` with it.
      *
      * The client outlives `f`, so `f` (or something after it) must call [[SqlClient.close]]. Use [[initWith]] unless the pool genuinely
      * needs to outlive the block that opened it. Like [[initWith]], nothing is installed ambiently; supplying the `DB` effect is
      * [[DB.run]]'s job alone.
      */
    inline def initUnscopedWith[B, S](inline rawUrl: String)(inline f: SqlClient => B < S)(using
        Frame
    ): B < (S & Async & Abort[SqlException]) =
        initUnscopedWith(rawUrl, SqlConfig.default)(f)

    /** Opens a client for `rawUrl` under `config` with no cleanup registered and runs `f` with it. See the one-argument
      * [[initUnscopedWith]].
      */
    inline def initUnscopedWith[B, S](inline rawUrl: String, config: SqlConfig)(inline f: SqlClient => B < S)(using
        Frame
    ): B < (S & Async & Abort[SqlException]) =
        kyo.internal.SqlClientInitMacro.checkScheme(rawUrl)
        openUnscoped(rawUrl, config, Backend.Registry.current).flatMap(f)
    end initUnscopedWith

    /** Re-raises a transaction body's failure on the channel it was raised on.
      *
      * [[SqlClient.transaction]] catches every failure so the rollback fires for all of them, and that is what loses which channel each came
      * from: `Abort.run[Any]` hands back a value, not an effect. A [[kyo.SqlException]] goes back on the row `transaction` declares for it.
      * Any other value was raised by the body, so it came from the body's own `S`, which the declared result carries, and it goes back there.
      *
      * The cast crosses an erasure boundary and there is no type-level spelling for what it expresses. `Abort.error` suspends under one erased
      * tag for every `E`, and `Abort.run` accepts by inspecting the value rather than by its type, so "re-raise onto whichever enclosing handler
      * accepts this value" cannot be written in types. `kyo.Scope.run` makes the identical crossing, and it is what lets a caller's own typed
      * failure survive a rollback instead of arriving as a panic naming its original identity.
      */
    private[kyo] def reraise[A](e: Any)(using Frame): A < Abort[SqlException] =
        e match
            case sqlEx: SqlException => Abort.fail(sqlEx)
            // Erasure boundary: see the scaladoc above. Abort.run accepts by value, not by type, so the
            // narrowed Result is how the value reaches the caller's own handler.
            case other => Abort.get(Result.Failure(other).asInstanceOf[Result[Nothing, Nothing]])

    // --- Lifecycle ---

    /** Resolves `rawUrl` against `registry` and opens a scope-bound client through the factory that claims its scheme.
      *
      * `@publicInBinary` because the inline `init` family emits a call to it at every user call site, so it is reached from outside this
      * package even though no user names it.
      */
    private[kyo] def openScoped(rawUrl: String, config: SqlConfig, registry: Backend.Registry)(using
        Frame
    ): SqlClient < (Async & Scope & Abort[SqlException]) =
        factoryFor(rawUrl, registry).flatMap((url, backend) =>
            backend.open(url, config).flatMap(client => Scope.ensure(client.close).andThen(client))
        )

    /** Resolves `rawUrl` against `registry` and opens a client the caller closes, through the factory that claims its scheme. Emitted at
      * user call sites by the inline `initUnscoped` family, hence `@publicInBinary`.
      */
    private[kyo] def openUnscoped(rawUrl: String, config: SqlConfig, registry: Backend.Registry)(using
        Frame
    ): SqlClient < (Async & Abort[SqlException]) =
        factoryFor(rawUrl, registry).flatMap((url, backend) => backend.open(url, config))

    /** Parses `rawUrl` and pairs it with the factory claiming its scheme, or fails naming the schemes that are available. */
    private[kyo] def factoryFor(rawUrl: String, registry: Backend.Registry)(using
        Frame
    ): (SqlConfig.Url, Backend) < Abort[SqlException] =
        Abort.get(SqlConfig.Url.parse(rawUrl)).flatMap { url =>
            registry.forScheme(url.address.scheme) match
                case Present(backend) => (url, backend)
                case Absent           => Abort.fail(SqlConnectionUnsupportedSchemeException(url.address.scheme, registry.schemes))
        }

end SqlClient
