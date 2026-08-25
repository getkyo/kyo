package kyo

import kyo.db.Idiom
import scala.annotation.publicInBinary

/** The client effect: the database a computation runs its statements against.
  *
  * A computation that runs a statement carries `DB` in its effect row and cannot run until [[DB.run]] supplies a client, so the dependency
  * is visible in the type instead of being discovered when the statement executes. The client is always explicit: a statement runs against
  * the pool the enclosing DB effect names, never an ambient or process-global default, so it cannot silently target a pool the caller did
  * not choose.
  *
  * The payload is a [[DB.State]], the client together with the config in force for the operations under the run. [[DB.client]] reads the
  * client back for portable work, and [[DB.clientAs]] narrows it when a program needs a capability only one engine has.
  *
  * Note: `DB` is an [[kyo.Env]] of its state and the bound says so, which is what lets the machinery deciding what to isolate across a
  * fiber boundary recognize the shape. A fiber forked inside a run body therefore sees the same client and the same config.
  *
  * @see
  *   [[DB.run]] to supply a client and discharge the effect
  * @see
  *   [[DB.client]] to read the client the effect carries
  * @see
  *   [[DB.clientAs]] to narrow it to an engine's own client type
  * @see
  *   [[kyo.SqlClient]] for the portable surface every engine answers
  */
opaque type DB <: (Env[DB.State] & Async) = Env[DB.State] & Async

object DB:

    /** What the `DB` effect carries: the client statements run on, and the config in force for them.
      *
      * The two halves are independent. `config` is the settings every operation under the run reads, the answer a `SqlClient.useConfig`
      * read gives; it starts as the client's own open-time config unless a caller supplies a different one. The client's `config` field
      * keeps its own meaning either way, the URL-merged result resolved when the pool opened.
      *
      * @param client
      *   the client whose pool the statements lease from
      * @param config
      *   the settings in force for the operations under the run
      */
    final case class State(client: SqlClient, config: SqlConfig)

    /** Reads the client the effect carries.
      *
      * The portable half of the state, and all an engine-agnostic program ever needs: a program that names no engine never reaches for
      * [[clientAs]].
      */
    def client(using Frame): SqlClient < DB =
        Env.use[State](_.client)

    /** The whole state the effect carries: the client and the config in force.
      *
      * A DSL-internal accessor the `.run` surface reads to reach both the client and the in-force config in one lookup;
      * `@publicInBinary` because [[kyo.internal.SqlRunMacro]] emits a call to it at every `.run` call site.
      */
    def state(using Frame): State < DB =
        Env.get[State]

    /** Narrows the client to `C`, the sanctioned door to a capability only one engine has.
      *
      * A Postgres client's `copyIn`, `copyOut`, `notifications` and `parameters`, and a MySQL client's `loadLocalInfile`, have no portable
      * equivalent, which is why they are not on [[kyo.SqlClient]]. This is how a program reaches them without a cast: it narrows once, at
      * the boundary where it has already committed to an engine, and holds the concrete client from there rather than narrowing per call.
      *
      * The one failure is asking for the wrong engine, and it is typed: [[kyo.SqlConnectionBackendMismatchException]], carrying the type
      * `C` asked for as the required driver and the client's own dialect id as the active one. `DB` is unparameterized, so this stays a
      * runtime check rather than a compile error, and narrowing once at a boundary is what keeps it to a single site.
      *
      * @tparam C
      *   the engine client type the program expects
      */
    def clientAs[C <: SqlClient](using tag: ConcreteTag[C], frame: Frame): C < (DB & Abort[SqlConnectionBackendMismatchException]) =
        client.map {
            case tag(narrowed) => narrowed
            case other =>
                Abort.fail(
                    SqlConnectionBackendMismatchException(tag.showType, other.dialect.id, "DB.clientAs")
                )
        }
    end clientAs

    /** Runs `v` on `client`, with that client's own open-time config as the config in force.
      *
      * The entry point for a program that adjusts no setting: `State(client, client.config)` is what every statement inside `v` then reads.
      *
      * @param client
      *   the client the statements inside `v` run on
      * @param v
      *   the computation requiring `DB`
      */
    def run[A, S](client: SqlClient)(v: A < (DB & S))(using Frame): A < (Async & S) =
        run(State(client, client.config))(v)

    /** Runs `v` on `state`, the client and the config in force in one value.
      *
      * The canonical overload: it supplies the state and discharges `DB` from `v`'s row, leaving `v`'s remaining effects. The other two
      * overloads construct a state and reach through here, so what they seed is the only thing that distinguishes them.
      *
      * @param state
      *   the client and the config in force for the operations inside `v`
      * @param v
      *   the computation requiring `DB`
      */
    def run[A, S](state: State)(v: A < (DB & S))(using Frame): A < (Async & S) =
        Env.run(state)(v)

    /** Runs `v` on `client` with `config` as the config in force.
      *
      * Sugar for `run(state)` with `State(client, config)`. The client's own open-time config is left unread, so `config` is the whole
      * answer every operation inside `v` gets, not an adjustment layered over the client's.
      *
      * @param client
      *   the client the statements inside `v` run on
      * @param config
      *   the settings in force for those operations
      * @param v
      *   the computation requiring `DB`
      */
    def run[A, S](client: SqlClient, config: SqlConfig)(v: A < (DB & S))(using Frame): A < (Async & S) =
        run(State(client, config))(v)

    /** Opens a pooled client for `rawUrl`, runs `v` on it, and closes the client when `v` ends.
      *
      * The connect-and-run entry point: the handler supplies what it provides, so a program that needs one database for one computation
      * never holds a client. The client is opened through [[SqlClient.init]], which keeps the compile-time URL scheme check for a literal
      * URL, and its pool lives exactly as long as `v`: the block runs under a [[Scope]] of its own, so a `run(url)` in a loop opens and
      * closes one pool per iteration rather than accumulating them on an enclosing scope. That also means a scoped resource acquired
      * inside `v` is released when this block ends, the same block lifetime the pool gets.
      *
      * @param rawUrl
      *   database URL in the form `<scheme>://user:pw@host:port/db[?opts]`
      * @param v
      *   the computation requiring `DB`
      */
    inline def run[A, S](inline rawUrl: String)(v: A < (DB & S))(using Frame): A < (S & Async & Abort[SqlException]) =
        Scope.run(SqlClient.init(rawUrl).map(client => run(client)(v)))

    /** Opens a pooled client for `rawUrl` under `config`, runs `v` on it, and closes the client when `v` ends. See the single-argument
      * URL overload.
      */
    inline def run[A, S](inline rawUrl: String, config: SqlConfig)(v: A < (DB & S))(using
        Frame
    ): A < (S & Async & Abort[SqlException]) =
        Scope.run(SqlClient.init(rawUrl, config).map(client => run(client)(v)))

    /** Runs `body` inside a transaction on the installed client, committing on success and rolling back on abort or panic.
      *
      * The ambient form of [[SqlClient.transaction]]: the common path needs no client handle. A nested call takes a savepoint on the
      * same transaction rather than opening a second one, exactly as the client method documents.
      */
    def transaction[A, S](body: A < S)(using Frame): A < (S & DB & Async & Abort[SqlException]) =
        client.map(_.transaction(body))

    /** Runs `body` inside a transaction with an explicit isolation level and read-only hint; see [[SqlClient.transaction]]. */
    def transaction[A, S](
        isolation: Maybe[SqlClient.IsolationLevel],
        readOnly: Boolean = false
    )(body: A < S)(using Frame): A < (S & DB & Async & Abort[SqlException]) =
        client.map(_.transaction(isolation, readOnly)(body))

    /** Runs `body` while holding the server-side advisory lock `key` on the installed client; see [[SqlClient.withAdvisoryLock]]. */
    def withAdvisoryLock[A, S](key: Long, timeout: Maybe[Duration] = Maybe.Absent)(
        body: A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & DB & Async & Abort[SqlException]) =
        client.map(_.withAdvisoryLock(key, timeout)(body))

    /** Executes a raw SQL string on the installed client and returns the affected-row count, the ambient form of
      * [[SqlClient.executeRaw]]. The entry point for DDL, migrations, and multi-statement scripts on the common path.
      */
    def executeRaw(sql: String)(using Frame): Long < (DB & Async & Abort[SqlException]) =
        client.map(_.executeRaw(sql))

    /** The server address of the installed client, host and port as the URL resolved them. */
    def address(using Frame): SqlConfig.Address < DB =
        client.map(_.address)

    /** Runs `v` with the config in force adjusted by `f`, leaving the client and the [[DB]] effect in place.
      *
      * The settings every DSL statement inside `v` reads become `f` applied to the config currently in force, so
      * `withConfig(_.copy(queryTimeout = 1.second))` narrows exactly that setting and leaves the rest of the state's config alone. Nesting
      * composes, the outer adjustment running first. Unlike [[run]] this supplies no client: it re-scopes the config of the client the
      * enclosing [[run]] already installed, so `v` and everything after it keep the same client.
      *
      * The adjustment reaches a `.run` statement through the DSL run surface, which reads [[DB.State.config]] and threads it into the lease.
      * A direct call on a client in hand ([[SqlClient.query]] and the rest) reads the client's own [[SqlClient.config]] and is unaffected.
      *
      * @param f
      *   adjustment applied to the config currently in force
      * @param v
      *   the computation whose statements read the adjusted config
      */
    def withConfig[A, S](f: SqlConfig => SqlConfig)(v: A < (S & DB))(using Frame): A < (S & DB) =
        Env.use[State] { state =>
            Env.run(state.copy(config = f(state.config)))(v)
        }

end DB
