package kyo.db

import kyo.<
import kyo.Abort
import kyo.AllowUnsafe
import kyo.Async
import kyo.AtomicBoolean
import kyo.AtomicRef
import kyo.Cache
import kyo.Chunk
import kyo.Duration
import kyo.Frame
import kyo.Local
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Result
import kyo.Scope
import kyo.Sql
import kyo.SqlClient
import kyo.SqlConfig
import kyo.SqlConnectionConnectFailedException
import kyo.SqlConnectionException
import kyo.SqlConnectionProtocolDecodeException
import kyo.SqlConnectionSocketTimeoutException
import kyo.SqlConnectionUserRequiredException
import kyo.SqlDecodeException
import kyo.SqlException
import kyo.SqlRequestException
import kyo.SqlRow
import kyo.SqlServerException
import kyo.SqlUnsupportedException
import kyo.Stream
import kyo.Sync
import kyo.discard

/** One live database session, in the vocabulary the core speaks.
  *
  * Every statement kyo-sql sends reaches the wire through this type, and nothing above it names a wire type: binds arrive as
  * [[kyo.Sql.BoundValue]] and rows leave as [[kyo.SqlRow]], so [[kyo.SqlClient]] and the pool beneath it compile with no backend on the
  * classpath. Each backend supplies one implementation, which owns the translation in both directions.
  *
  * #### Two halves
  *
  * The statement half (query, execute, stream, transaction, savepoint, advisory lock) is what a caller invokes. The reclaim half
  * ([[inFlight]], [[inOpenTransaction]], [[cancelInFlight]], [[rollbackIfOpenTransaction]], [[drainToIdle]]) is what the pool invokes after
  * a fiber running a statement is interrupted, and no caller ever calls it directly. Splitting them here is what lets the pool run the
  * cancellation sequence without knowing which engine it is talking to.
  *
  * #### The in-flight window
  *
  * [[inFlight]] MUST read `true` before the first request byte of a statement is written, and MUST keep reading `true` until either the
  * terminal response has been drained or the session has been reclaimed. The pool's lease finalizer reads it from outside the interrupted
  * fiber to decide whether a cancel is owed, so a flag that flips late leaks a running server-side query, and one that clears early on an
  * interrupt hands the next borrower a session with a half-consumed response still queued on it.
  *
  * The criterion for lowering it is [[Connection.leftSessionIdle]] and nothing else. A statement that fails with a typed
  * [[kyo.SqlException]] the server itself reported has completed its round trip, so the flag clears: the wire is idle and the session is
  * reusable. A statement that ends in a panic or an interrupt has not, so the flag stays set.
  *
  * #### Which members suspend
  *
  * [[inFlight]] and [[inOpenTransaction]] take [[kyo.AllowUnsafe]] and answer with a bare `Boolean` because the pool's eviction and
  * health-check callbacks that read them run outside any fiber and cannot suspend, and [[closeNow]] exists for the same reason. [[isOpen]]
  * and [[close]] are the forms for a caller that has a suspension available. An implementation therefore answers the two predicates from
  * state it already holds, never from a round trip.
  *
  * @see
  *   [[Connection.Factory]] How the pool opens one of these
  * @see
  *   [[Connection.isProtocolFatal]] Whether a failure leaves a session reusable
  * @see
  *   [[kyo.db.Runtime]] The assembled state a client leases sessions from
  */
abstract class Connection:

    /** The session identifier the server assigned, for log lines that have to name a session. */
    def id: Long

    /** The version this session's server reported at handshake.
      *
      * Where a session learns its version is a fact about the session, and each engine reports it its own way: one in a startup parameter
      * map, another in the handshake capture. The client caches the first answer, so this is read once for the lifetime of a client rather
      * than once per statement.
      */
    def serverVersion(using Frame): Idiom.ServerVersion < (Async & Abort[SqlException])

    // --- Statements ---

    /** Runs a parameterised query through the engine's prepared-statement protocol and returns every row. */
    def extendedQuery(sql: String, params: Chunk[Sql.BoundValue[?]])(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException])

    /** Runs a parameterised DML statement through the engine's prepared-statement protocol and returns the affected-row count. */
    def extendedExecute(sql: String, params: Chunk[Sql.BoundValue[?]])(using Frame): Long < (Async & Abort[SqlException])

    /** Runs a parameterised `INSERT` and reports the affected-row count together with whatever generated key the engine surfaced. */
    def extendedExecuteInsert(sql: String, params: Chunk[Sql.BoundValue[?]])(using
        Frame
    ): SqlClient.InsertOutcome < (Async & Abort[SqlException])

    /** Runs `sql` through the engine's simple-query protocol and returns every row. The SQL carries no placeholders. */
    def simpleQuery(sql: String)(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException])

    /** Runs `sql` through the engine's simple-query protocol and returns the affected-row count. */
    def simpleExecute(sql: String)(using Frame): Long < (Async & Abort[SqlException])

    /** Streams a parameterised query's rows, fetching `batchSize` rows per round trip where the engine's protocol batches cursor
      * reads (PostgreSQL does; MySQL reads rows one by one and treats `batchSize` as informational).
      *
      * The returned stream holds this session for its lifetime, so the [[kyo.Scope]] in its row is what releases it.
      */
    def streamQuery(sql: String, params: Chunk[Sql.BoundValue[?]], batchSize: Int)(using
        Frame
    ): Stream[SqlRow, Async & Abort[SqlException] & Scope]

    /** Runs `stmts` as one logical batch and returns one outcome per statement, in submission order.
      *
      * A per-statement failure is reported as [[kyo.Result.Failure]] and does not stop the statements after it; a session-level failure
      * aborts the whole batch.
      */
    def pipelined(stmts: Chunk[(String, Chunk[Sql.BoundValue[?]])])(using
        Frame
    ): Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < (Async & Abort[SqlException])

    // --- Transactions ---

    /** Opens a transaction, at `isolation` when one is named and in the server's default when it is [[kyo.Absent]]. */
    def beginTransaction(isolation: Maybe[SqlClient.IsolationLevel], readOnly: Boolean)(using Frame): Unit < (Async & Abort[SqlException])

    /** Commits the open transaction. */
    def commitTransaction(using Frame): Unit < (Async & Abort[SqlException])

    /** Rolls back the open transaction. */
    def rollbackTransaction(using Frame): Unit < (Async & Abort[SqlException])

    /** Establishes savepoint `name` inside the open transaction. */
    def savepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException])

    /** Discards savepoint `name`, keeping the work done since it was established. */
    def releaseSavepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException])

    /** Undoes the work done since savepoint `name` was established, leaving the transaction open. */
    def rollbackToSavepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException])

    // --- Session ---

    /** Sends the cheapest round trip the engine offers, to confirm the wire is live. */
    def ping(using Frame): Unit < (Async & Abort[SqlException])

    /** Scrubs per-session state: variables, prepared statements, temporary tables, listeners, and any open transaction. */
    def resetSession(using Frame): Unit < (Async & Abort[SqlException])

    /** Takes the session-scoped advisory lock for `key`, waiting at most `timeout` where the engine can bound the wait.
      *
      * Aborts with [[kyo.SqlRequestAdvisoryLockException]] when the lock is not granted.
      */
    def acquireAdvisoryLock(key: Long, timeout: Maybe[Duration])(using Frame): Unit < (Async & Abort[SqlException])

    /** Releases the session-scoped advisory lock for `key`. */
    def releaseAdvisoryLock(key: Long)(using Frame): Unit < (Async & Abort[SqlException])

    // --- Reclaim, driven by the pool after an interrupt ---

    /** Whether a request has been written whose terminal response has not been drained. See the class scaladoc for the exact window. */
    def inFlight(using AllowUnsafe): Boolean

    /** Whether a transaction this session opened is still open. */
    def inOpenTransaction(using AllowUnsafe): Boolean

    /** Tells the server to stop the statement running on this session, out of band.
      *
      * Best effort by nature: a statement that finishes before the request lands is not an error. Returns without waiting for the
      * interrupted statement's response, which [[drainToIdle]] consumes.
      */
    def cancelInFlight(using Frame): Unit < (Async & Abort[SqlException])

    /** Rolls back the transaction this session opened, if one is still open, and does nothing otherwise. */
    def rollbackIfOpenTransaction(using Frame): Unit < (Async & Abort[SqlException])

    /** Consumes whatever the server still owes this session and reports whether it came back to a reusable state.
      *
      * `false` means the wire cannot be resynchronised and the caller must destroy the session rather than pool it. Engines genuinely
      * differ here: a protocol that frames every message with a type byte and ends every command with a ready marker resynchronises
      * deterministically from an arbitrary mid-response position, while one whose binary result rows open with the same byte an
      * acknowledgement packet does leaves a reader that has lost its place unable to tell a row from a terminator.
      */
    def drainToIdle(using Frame): Boolean < (Async & Abort[SqlException])

    // --- Lifecycle ---

    /** Whether the underlying socket is still open. */
    def isOpen(using Frame): Boolean < Sync

    /** Says goodbye to the server where the protocol has a way to, then closes the socket. Never fails. */
    def close(using Frame): Unit < Async

    /** Closes the socket without any protocol shutdown.
      *
      * The close available to a caller that cannot suspend, which is what the pool's eviction and health-check callbacks are. Prefer
      * [[close]] everywhere a suspension is available.
      */
    def closeNow(using Frame, AllowUnsafe): Unit

end Connection

/** Companion of [[Connection]]: how a session is opened, and the policy every implementation shares.
  *
  * The predicates here are not helpers a backend may reimplement. [[leftSessionIdle]] is the one criterion for lowering
  * [[Connection.inFlight]], [[isProtocolFatal]] is what decides whether a failed exchange leaves a session poolable, and both are read by
  * the pool as well as by the session, so an engine that answered them its own way would be describing a different lifecycle from the one
  * the pool runs.
  */
object Connection:

    /** One lease's connection custody, so a connection is owned by a finalizer from the instant it exists.
      *
      * The producer [[claim]]s when the connection is built and the acquire [[take]]s as it registers the lease's exit finalizer; the pool's
      * orphan finalizer closes one [[claim]]ed but never [[take]]n. [[take]] and [[orphan]] share one flag, so exactly one close fires.
      */
    final private[kyo] class Custody(using AllowUnsafe):
        private val ref   = AtomicRef.Unsafe.init(Maybe.empty[() => Unit < (Async & Abort[Throwable])])
        private val taken = AtomicBoolean.Unsafe.init(false)

        /** Records the close for a connection just delivered into this custody. Idempotent, so it composes with the lease's own close. */
        def claim(close: () => Unit < (Async & Abort[Throwable]))(using AllowUnsafe): Unit = ref.set(Maybe(close))

        /** Marks the lease's own exit finalizer as registered, so [[orphan]] stands down. */
        def take()(using AllowUnsafe): Unit = taken.set(true)

        /** The close for a connection claimed but never taken (the handover dropped it), else [[Absent]]. */
        def orphan()(using AllowUnsafe): Maybe[() => Unit < (Async & Abort[Throwable])] =
            if taken.get() then Absent else ref.get()
    end Custody

    /** The current lease's [[Custody]], bound by the acquire so the [[Factory]] claims into it. Inheritable, so it reaches a factory the
      * connect budget runs in a `timeoutWithError` child fiber.
      */
    private[kyo] val custodyLocal: Local[Maybe[Custody]] = Local.init(Maybe.empty)

    /** How the pool opens one session, and the only thing it is given that knows which engine is behind it.
      *
      * An implementation owns everything that reaching a specific server involves: the TLS negotiation the engine's handshake defines, the
      * authentication exchange, and whatever per-session registration the backend needs before the session is fit to lend. It does not own
      * the timeout. The pool bounds every call by `acquireTimeout`, because how long to wait for a session is a pool policy rather than a
      * protocol detail.
      *
      * @tparam C
      *   the session type this factory opens, and the type the [[kyo.db.Runtime]] built over it is generic in
      */
    abstract class Factory[C <: Connection]:
        /** Opens one session to `address`, authenticating with `password` where the engine's handshake asks for one, under `config`. */
        def open(address: SqlConfig.Address, password: Maybe[String], config: SqlConfig)(using Frame): C < (Async & Abort[SqlException])
    end Factory

    /** Whether an exchange that ended with `error` left the session idle.
      *
      * The one question both halves of the lifecycle ask, which is why it is one predicate. A session asks it to decide whether to lower
      * [[Connection.inFlight]]: the flag comes down only when the exchange reached its end, so what the framework happened to signal an
      * interruption as, a typed failure or a panic, cannot change the answer. The pool asks it to decide whether a finished lease may
      * return its session to the idle ring.
      *
      * [[kyo.Absent]] is a normal exit and left the session idle. A non-protocol-fatal [[kyo.SqlException]] did too: a routine `23505`
      * unique violation arrives after the server drained its response, and a client-side request error never reached the wire. Everything
      * else did not: a protocol-fatal exception, a failure of some other type a caller raised through the exchange, and a panic all leave
      * the reader's position unestablished.
      */
    def leftSessionIdle(error: Maybe[Result.Error[Any]]): Boolean =
        error match
            case Absent                                   => true
            case Present(Result.Failure(e: SqlException)) => !isProtocolFatal(e)
            case Present(_)                               => false

    /** Whether `e` leaves the session in a state nothing can be told about, so a session carrying it must be reclaimed or destroyed rather
      * than pooled.
      *
      * Protocol-fatal:
      *   - [[kyo.SqlConnectionException]], a transport failure or a bound that expired; either way the exchange did not run to its end.
      *   - [[kyo.SqlDecodeException]], a wire-format desync; the framing no longer agrees with the server.
      *   - [[kyo.SqlServerException]] with SQLSTATE class `08` (connection exception); the server terminated the connection.
      *
      * Not fatal, the session is idle and reusable:
      *   - [[kyo.SqlUnsupportedException]], an operation this backend does not offer; the wire never saw it.
      *   - [[kyo.SqlRequestException]], a client-side encoding error; likewise.
      *   - [[kyo.SqlServerException]] with any other SQLSTATE class, a query-level error the server reported after draining its response.
      *
      * Class `25` (invalid transaction state) is deliberately not fatal. A statement issued inside a transaction that has already failed is
      * reported through an ordinary error response followed by the trailing ready marker the session consumes, so the exchange completed
      * and the wire is idle. That is a transaction-state problem, which the reclaim chain's rollback step exists to resolve, not a framing
      * problem. Class `08` is fatal because there the connection itself is gone.
      *
      * This rule reads SQLSTATE, so an engine whose protocol carries no such code synthesizes the closest standard class for its
      * [[kyo.SqlServerException]] leaves, `"HY000"` when nothing closer exists, and carries the native code in a typed field of its own
      * leaf.
      */
    def isProtocolFatal(e: SqlException): Boolean =
        e match
            case _: SqlConnectionException  => true
            case _: SqlDecodeException      => true
            case s: SqlServerException      => s.sqlState.startsWith("08")
            case _: SqlUnsupportedException => false
            case _: SqlRequestException     => false

    /** Runs `body` and closes `socket` if it ends in any failure, leaving it open on success for the value that now owns it.
      *
      * The bracket every handshake needs, and a [[kyo.Scope]] finalizer rather than a `Sync.ensure` one, because on this path almost every
      * failure is a typed [[kyo.SqlException]] handled in the same fiber: authentication rejected, TLS not advertised, a clear-text
      * password refused, secure transport required. `Sync.ensure` does not fire on that edge, since its finalizer parks on the fiber and
      * runs at fiber completion, so a fiber retrying on bad credentials would hold every socket it opened until the program ended. A scope
      * closes as part of evaluating the body, which covers the typed edge as well as the interrupt and the panic.
      */
    def closingOnFailure[A, S](socket: kyo.net.Connection)(
        body: A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Abort[SqlException]) =
        Scope.run {
            Scope.ensure { error =>
                if error.isDefined then
                    // Unsafe: closing a socket we still own on the failure path, from a finalizer that cannot suspend.
                    Sync.Unsafe.defer(if socket.isOpen then socket.close() else ())
                else ()
            }.andThen(body)
        }

    /** The user name a handshake must send, refusing with [[kyo.SqlConnectionUserRequiredException]] when `address` declared none.
      *
      * Offered here rather than performed by core because [[Factory]] assigns the authentication exchange to the backend, and whether a
      * handshake needs a user name is a fact about that exchange: core enforcing it would rule for an engine that authenticates by peer
      * credentials and needs no user at all. What keeps a backend from skipping it is that an engine sending a user name has to obtain one
      * from here or from nothing.
      *
      * A [[kyo.Present]] empty user is returned as it stands. It is what `scheme://@host:port/db` declared, and whether an empty user name
      * names an account is the server's judgment.
      */
    def requireUser(address: SqlConfig.Address)(using Frame): String < Abort[SqlException] =
        address.user match
            case Present(user) => user
            case Absent        => Abort.fail(SqlConnectionUserRequiredException(address.scheme))

    /** The exit-edge view of a handled `outcome`: a success is [[kyo.Absent]], a failure and a panic are each themselves.
      *
      * What a lease or an exchange that resolved its own abort passes to [[leftSessionIdle]], so handling an error does not change what the
      * session's state is judged to be.
      */
    def errorOf[A](outcome: Result[SqlException, A]): Maybe[Result.Error[Any]] =
        outcome match
            case Result.Success(_) => Absent
            case Result.Failure(e) => Present(Result.Failure(e))
            case Result.Panic(t)   => Present(Result.Panic(t))

    /** Reads the leading version out of the string a session's server reports at handshake, aborting when it carries none.
      *
      * Both engines announce a version padded with build detail a comparison must ignore (a build suffix `"8.0.34-log"`, a packaging note
      * `"15.6 (Debian 15.6-1.pgdg120+2)"`, a pre-release letter `"17beta1"`), and both resolve it the same way, so the parsing itself is
      * [[Idiom.ServerVersion.parse]] and lives once. What a session adds is its reading of the empty answer: a string with no leading version
      * at all is a protocol violation rather than a zero version, so it aborts here instead of reading as `0.0.0`.
      */
    private[kyo] def parseServerVersion(reported: String)(using Frame): Idiom.ServerVersion < Abort[SqlConnectionProtocolDecodeException] =
        Idiom.ServerVersion.parse(reported) match
            case Present(version) => version
            case Absent =>
                Abort.fail(
                    SqlConnectionProtocolDecodeException(
                        "server version",
                        s"no leading version number in '$reported'"
                    )
                )

    /** Opens a raw socket to `host`:`port` for a handshake and runs `body` under [[closingOnFailure]], so an abort or interrupt after the
      * socket is owned closes it rather than leaking it. On the successful path the value `body` produces takes ownership and closes the
      * socket itself.
      *
      * The single place both engines open their handshake socket. A refused connect becomes a [[kyo.SqlConnectionConnectFailedException]];
      * a connect that panics is turned into its leaf by `onPanic`, which is the one part that varies between engines (Postgres logs the panic
      * and names the factory, MySQL wraps the throwable directly), so it is the one parameter. Without the bracket the handshake sockets
      * accumulate until GC and the end-of-run FD leak check trips.
      */
    private[kyo] def openSocket[A](
        host: String,
        port: Int,
        onPanic: Throwable => SqlConnectionException < Sync,
        ownerClose: A => Unit < (Async & Abort[Throwable])
    )(
        body: kyo.net.Connection => A < (Async & Abort[SqlException])
    )(using Frame): A < (Async & Abort[SqlException]) =
        // A finalizer on the connect fiber closes a connection an interrupt drops before `body` runs; after `body`, engine `a` is claimed
        // into `custodyLocal` (orphan finalizer closes it if the handover drops). Claim `a`, not the raw socket, whose close a TLS upgrade no-ops.
        Scope.run {
            Sync.Unsafe.defer(kyo.net.NetPlatform.transport.connect(host, port).safe).map { connFiber =>
                Scope.ensure { error =>
                    if error.isDefined then
                        connFiber.interrupt.andThen(connFiber.getResult).map {
                            case Result.Success(rawConn) => Sync.Unsafe.defer(if rawConn.isOpen then rawConn.close() else ())
                            case _                       => ()
                        }
                    else ()
                }.andThen {
                    Abort.run[kyo.net.NetException](connFiber.get).map {
                        case Result.Failure(_) =>
                            Abort.fail(SqlConnectionConnectFailedException(host, port, new Exception("connect refused")))
                        case Result.Panic(t) =>
                            onPanic(t).map(Abort.fail(_))
                        case Result.Success(rawConn) =>
                            closingOnFailure(rawConn)(body(rawConn).map { a =>
                                custodyLocal.use {
                                    case Present(custody) => Sync.Unsafe.defer(custody.claim(() => ownerClose(a)))
                                    case Absent           => ()
                                }.andThen(a)
                            })
                    }
                }
            }
        }

    /** Builds a per-connection prepared-statement cache whose eviction enqueues the released server-side handle into `closesRef`.
      *
      * A plain `Cache.init` silently drops evicted values; the server-side statement then stays allocated for the life of the session (a
      * bounded but real leak on any connection that churns through more distinct SQL than the cache holds). Wiring `onEvict`, `onExpire`, and
      * `onRemove` into `closesRef` enqueues the evicted handle `closeKey` extracts so the next drain releases it. The callback is synchronous,
      * invoked inline on the cache's Sync sweep; it uses the ref's non-suspending `unsafe.updateAndGet`, and the actual close write happens on
      * the next extended-protocol request. The engines differ only in `closeKey`: a Postgres statement is named (`Chunk[String]`), a MySQL one
      * is numbered (`Chunk[Int]`).
      */
    private[kyo] def mkStmtCache[K, V](
        closesRef: AtomicRef[Chunk[K]],
        maxSize: Int,
        ttl: Duration,
        closeKey: V => K
    )(using Frame): Cache[String, V] < Sync =
        Sync.Unsafe.defer:
            val onEvict: (String, V) => Unit = (_, stmt) =>
                discard(closesRef.unsafe.updateAndGet(_ :+ closeKey(stmt)))
            Cache.Unsafe.init[String, V](
                maxSize = maxSize,
                expireAfterAccess = ttl,
                onEvict = onEvict,
                onExpire = onEvict,
                onRemove = onEvict
            ).safe

    /** Applies `socketTimeout` to a `read` that can wait for bytes, passing an [[Duration.Infinity]] budget through unwrapped.
      *
      * The byte-identical inner bound both channels share; where the timeout is read from stays per-engine (a mutable ref on one, a `val` on
      * the other). `read` is by-name so it is constructed inside the branch that runs it: each engine's reader consults its pending buffer as
      * soon as it is called, and a by-value parameter would move that outside the wrapper. [[Duration.Infinity]], the default, passes through
      * unwrapped; `Async.timeoutWithError` would also short-circuit an infinite budget itself, so the explicit branch keeps this behaviour
      * independent of that upstream detail.
      */
    private[kyo] def boundedRead[A](socketTimeout: Duration)(read: => A < (Async & Abort[SqlException]))(using
        Frame
    ): A < (Async & Abort[SqlException]) =
        if socketTimeout == Duration.Infinity then read
        else
            Async.timeoutWithError(
                socketTimeout,
                Result.Failure(SqlConnectionSocketTimeoutException(socketTimeout))
            )(read)

    /** Lowers `requestInFlight` when the exchange that just ended left the session idle, and leaves it raised otherwise.
      *
      * [[leftSessionIdle]] is the whole rule, and keying on it rather than on the shape of the signal is what makes this correct for a
      * timeout: a bound that expires arrives as an ordinary typed failure, and treating that as a completed exchange would hand the next
      * borrower a connection whose response is still on the wire.
      */
    private[kyo] def settle(requestInFlight: AtomicBoolean.Unsafe, error: Maybe[Result.Error[Any]])(using AllowUnsafe): Unit =
        if leftSessionIdle(error) then requestInFlight.set(false)

    /** Lowers `requestInFlight` on the settled edge of `body`, the far end of the in-flight window the pool's reclaim reads.
      *
      * The caller raises the engine's flags before invoking this, so this half closes the window rather than opening it. Two paths lower it,
      * because neither reaches every edge. `Sync.ensure` fires on an interrupt or a panic, where nothing after `body` runs; it does NOT fire
      * on a typed `Abort` whose handler sits outside it, because the failure is a suspension that skips the continuation. So the typed and
      * successful edges are resolved here by handling the abort and re-raising it. Lowering twice is harmless, which is why this needs no
      * guard against both paths running.
      */
    private[kyo] def tracked[A](requestInFlight: AtomicBoolean.Unsafe)(body: A < (Async & Abort[SqlException]))(using
        Frame
    ): A < (Async & Abort[SqlException]) =
        Sync.ensure(error => Sync.Unsafe.defer(settle(requestInFlight, error))) {
            Abort.run[SqlException](body).flatMap { outcome =>
                Sync.Unsafe.defer(settle(requestInFlight, errorOf(outcome))).andThen {
                    outcome match
                        case Result.Success(a) => a
                        case Result.Failure(e) => Abort.fail[SqlException](e)
                        case Result.Panic(t)   => Abort.error(Result.Panic(t))
                }
            }
        }

    /** [[tracked]] for a stream, whose request stays in flight across every batch until the enclosing [[Scope]] exits. The caller raises the
      * engine's flags before invoking this; the [[Scope]] finalizer settles the flag on exit.
      */
    private[kyo] def trackedScoped[A, S](requestInFlight: AtomicBoolean.Unsafe)(
        body: A < (S & Async & Abort[SqlException] & Scope)
    )(using Frame): A < (S & Async & Abort[SqlException] & Scope) =
        Scope.ensure(error => Sync.Unsafe.defer(settle(requestInFlight, error))).andThen(body)

end Connection
