package kyo.db

import kyo.<
import kyo.Abort
import kyo.Async
import kyo.AtomicBoolean
import kyo.AtomicRef
import kyo.Duration
import kyo.Frame
import kyo.Maybe
import kyo.Scope
import kyo.SqlConfig
import kyo.SqlException
import kyo.Sync
import kyo.internal.client.SqlConnectionPool

/** The assembled state one client runs on: the pooled sessions, the address and settings they were opened under, and the closed and
  * server-version state a client reads through it.
  *
  * A carrier is what [[Runtime.init]] returns and what a backend hands to its own client class. It exists because assembly is effectful,
  * merging settings can fail typed and warming sessions up suspends, while a class constructor cannot carry an effect row, so the assembled
  * state has to arrive at the client's constructor as a value. That constructor is written in the backend's own package, so the value's
  * type has to be nameable there, which is what makes this a public type rather than an internal one.
  *
  * Its public surface is [[openDedicated]] and [[Runtime.init]], and the narrowness is the point. Which session a statement runs on is the
  * client's decision, a transaction's pinned session or a fresh lease, and the lease entries the client's own members delegate to are not
  * published, so no code holding a carrier can take a session and bypass that decision. A backend learns one rule: statements go through
  * the client, and the carrier is what a backend is handed and hands on.
  *
  * Two settings values meet on this type and stay distinct. The carrier's own are the open-time merged value [[Runtime.init]] computed,
  * fixed for its lifetime. Each lease entry takes, per call, the settings in force for that one operation, which is what an adjustment
  * composed into the client's effect state contributes, and it is that parameter which reaches the pool.
  *
  * @tparam C
  *   the session type this carrier leases, the one the backend's [[kyo.db.Connection.Factory]] opens
  * @see
  *   [[Runtime.init]] The assembly a backend's [[kyo.db.Backend.open]] calls
  * @see
  *   [[kyo.db.Connection]] The session type
  * @see
  *   [[kyo.SqlClient]] The tier that owns session routing and the portable statement surface
  */
final class Runtime[C <: Connection] private[kyo] (
    private[kyo] val url: SqlConfig.Url,
    private[kyo] val config: SqlConfig,
    private[kyo] val pool: kyo.internal.client.SqlConnectionPool[C],
    private[kyo] val closedRef: AtomicBoolean,
    private[kyo] val serverVersionRef: AtomicRef[Maybe[Idiom.ServerVersion]]
):

    /** A session opened outside the lease machinery, for listener-style work the caller owns.
      *
      * It takes no pool slot, is never routed, and is closed by whoever asked for it, which is what makes it the door for a session that
      * outlives a statement: an engine's asynchronous notification listener holds one for as long as it listens. Fails with
      * [[kyo.SqlConnectionPoolClosedException]] once this carrier has been closed.
      *
      * Address, credentials and settings come from the carrier. This entry takes no per-operation settings, so the session opens under the
      * open-time merged value.
      */
    def openDedicated(using Frame): C < (Async & Abort[SqlException]) =
        pool.openDedicated(url.address, url.password, config)

    /** Closes this carrier: no further session opens, and the sessions it holds are released once in-flight work has had `gracePeriod` to
      * finish, whatever is left being force-closed at the end of it.
      *
      * Idempotent, a second call does nothing. Mirrors the client tier's close, whose own final delegates here, rather than the drain
      * beneath this carrier.
      *
      * @param gracePeriod
      *   how long in-flight work has to finish; [[kyo.Duration.Zero]] closes immediately
      */
    private[kyo] def close(gracePeriod: Duration)(using Frame): Unit < Async =
        closedRef.compareAndSet(false, true).flatMap {
            case true  => pool.closeAll(gracePeriod)
            case false => ()
        }

    /** Whether [[close]] has been called on this carrier.
      *
      * Mirrors the client tier's predicate rather than the unsafe one beneath it: it reads the flag [[close]] sets and probes neither the
      * sessions this carrier holds nor the network.
      */
    private[kyo] def isClosed(using Frame): Boolean < Sync =
        closedRef.get

    /** Runs `op` on a leased session under the statement policy: retry, the per-statement timeout, and the query instruments.
      *
      * The entry for a statement sent without a session of its own. `op` may not carry pending effects of its own, unlike [[lease]]'s, and
      * that is what makes the retry legal: the policy may run the body more than once, and arbitrary pending effects would be re-issued on
      * every attempt.
      *
      * @param config
      *   the settings in force for this one operation, which is what reaches the pool; the carrier's open-time settings are not read here
      */
    private[kyo] def leaseStatement[A](config: SqlConfig)(
        op: C => A < (Async & Abort[SqlException])
    )(using Frame): A < (Async & Abort[SqlException]) =
        pool.leaseStatement(url.address, url.password, config)(op)

    /** Runs `op` on one session pinned for the whole call, with no retry and no per-statement timeout.
      *
      * The entry for work needing the same session across several statements: a transaction, an advisory lock, a session reset. Retry is
      * wrong here because re-running the body would re-run work the first attempt may have committed, and a per-statement timeout is wrong
      * because the unit of work is the whole body. The body runs once, so it may carry pending effects `S` of its own.
      *
      * @param config
      *   the settings in force for this one operation, as on [[leaseStatement]]
      */
    private[kyo] def lease[A, S](config: SqlConfig)(
        op: C => A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Abort[SqlException]) =
        pool.lease(url.address, url.password, config)(op)

    /** A session held until the enclosing [[kyo.Scope]] exits, returned rather than handed a body.
      *
      * The entry for a read spanning many round trips, a streamed result set being the case that needs it: rows arrive across the lifetime
      * of the scope, so the session cannot be released when one call returns. Retry is absent by construction, since resuming a partly
      * consumed result would need a cursor position a stream carries no way to name.
      *
      * @param config
      *   the settings in force for this one operation, as on [[leaseStatement]]
      */
    private[kyo] def leaseScoped(config: SqlConfig)(using Frame): C < (Async & Abort[SqlException] & Scope) =
        pool.leaseScoped(url.address, url.password, config)

end Runtime

/** Companion of [[Runtime]]: the assembly a backend calls to build one. */
object Runtime:

    /** Assembles a carrier for `url` under `config`.
      *
      * In order: the URL's own declarations are merged under `config`, which fails typed when a TLS mode demands a CA certificate the URL
      * did not name; the pool is built over `connections` under the merged value; and `minConnections` sessions, capped at
      * `maxConnections`, are opened before the carrier is returned, behind a bracket that closes whatever it opened on any failure edge.
      * Warm-up therefore completes before a caller can run a statement, and a partial warm-up leaves no session open.
      *
      * The merged value is the carrier's open-time settings, and it is what an operation supplying none of its own runs under. A backend
      * does not merge the URL itself: resolving TLS, the connect timeout and the rest of the URL's options happens here, once, for every
      * engine.
      *
      * @tparam C
      *   the session type the carrier leases
      * @param url
      *   the address, credentials and URL-declared options to open against
      * @param config
      *   the programmatic settings the URL's declarations are merged under
      * @param connections
      *   how one session to that address is opened, the only thing the pool is given that knows which engine is behind it
      */
    def init[C <: Connection](
        url: SqlConfig.Url,
        config: SqlConfig,
        connections: Connection.Factory[C]
    )(using Frame): Runtime[C] < (Async & Abort[SqlException]) =
        url.toConfig(config).map { merged =>
            // Unsafe: SqlConnectionPool.init allocates the lock-free ring and requires AllowUnsafe; it opens no socket,
            // so assembly stays a pure allocation until warm-up.
            Sync.Unsafe.defer(SqlConnectionPool.init[C](merged, connections, url.options.connectTimeout, summon[Frame]))
                .map { pool =>
                    AtomicBoolean.init(false).map { closedRef =>
                        AtomicRef.init(Maybe.empty[Idiom.ServerVersion]).map { serverVersionRef =>
                            // The clamp lives here so each backend does not carry its own copy.
                            val warmCount = merged.minConnections.min(merged.maxConnections)
                            Scope.run {
                                Scope.ensure { error =>
                                    // Any failure edge closes the pool immediately, so a partial warm-up leaves no
                                    // descriptor open and no pool unreferenced; a clean assembly hands the pool to
                                    // the returned carrier untouched.
                                    if error.isDefined then pool.closeAll(Duration.Zero)
                                    else ()
                                }.andThen(pool.warmUp(url.address, url.password, warmCount, merged))
                            }.andThen(new Runtime[C](url, merged, pool, closedRef, serverVersionRef))
                        }
                    }
                }
        }

end Runtime
