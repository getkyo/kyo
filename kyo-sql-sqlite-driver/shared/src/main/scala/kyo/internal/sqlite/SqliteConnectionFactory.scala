package kyo.internal.sqlite

import kyo.*
import kyo.db.Connection
import kyo.ffi.Ffi

/** Opens one SQLite connection per lease.
  *
  * Every connection is configured before it is handed out. Five settings are applied in the C shim, each off by default and each silently
  * changing an answer; see `kyo_sqlite.c`. The journal mode is the sixth and is set here instead, because it is a property of the FILE
  * rather than of the connection.
  */
final private[kyo] class SqliteConnectionFactory(bindings: SqliteBindings) extends Connection.Factory[SqliteConnection]:

    def open(address: SqlConfig.Address, password: Maybe[String], config: SqlConfig)(using
        Frame
    ): SqliteConnection < (Async & Abort[SqlException]) =
        address match
            case n: SqlConfig.Address.Network =>
                // Unreachable through SqlClient.init, which routes by scheme. Present because the type can say it.
                Abort.fail(SqlConnectionUrlParseException(Render.asString(n: SqlConfig.Address), n.scheme))
            case local: SqlConfig.Address.Local => openLocal(local, config)

    /** Opens and prepares one connection, closing its handle on every exit that does not hand the connection back.
      *
      * The close is owed on the interrupted edge as much as the failed one, which is what puts it in a finalizer rather than in the failure
      * arms: `SqlConnectionPool.connect` runs the whole open under `Async.timeoutWithError`, and a lock or a full worker pool is exactly
      * where that budget runs out. At that point the handle is reachable from nowhere else. The finalizer is armed before the open is
      * submitted and finds the handle through a [[SqliteNativeCalls.Held]], so an interrupt landing at any point after the native call
      * returned still closes it, including one that lands while the open is still in native code.
      */
    private def openLocal(address: SqlConfig.Address.Local, config: SqlConfig)(using
        Frame
    ): SqliteConnection < (Async & Abort[SqlException]) =
        // Unsafe: the handle is this open's own until it hands the connection back, and a finalizer cannot suspend.
        given AllowUnsafe = AllowUnsafe.embrace.danger
        val vfs           = config.extensionFor[SqliteVfs].fold(SqliteConnectionFactory.DefaultVfs)(_.name)
        Sync.Unsafe.defer(SqliteNativeCalls.heldHandle(bindings, address.path)).map { held =>
            Sync.ensure(error => if error.isEmpty then Kyo.unit else Sync.Unsafe.defer(held.releaseHeld())) {
                Connection.OpenProgress.enter("open").andThen {
                    SqliteNativeCalls.open(bindings, address.path, SqliteConnectionFactory.OpenFlags, vfs, held)
                }.flatMap {
                    case Absent =>
                        // NULL comes back only when SQLite could not allocate the handle, which is not a database error
                        // and leaves nothing to read an error off.
                        Abort.fail(SqliteOpenFailedException(address.path, "SQLite could not allocate a connection handle"))
                    case Present(db) =>
                        // The handle exists even on failure, which is where the error text lives, so it is read before the
                        // finalizer closes it.
                        if bindings.extendedErrcode(db) != SqliteConnection.Ok then
                            Abort.fail(SqliteOpenFailedException(address.path, bindings.errmsg(db).value))
                        else configured(bindings, db, address, config)
                }
            }
        }
    end openLocal

    private def configured(
        bindings: SqliteBindings,
        db: Ffi.Handle[SqliteDb],
        address: SqlConfig.Address.Local,
        config: SqlConfig
    )(using Frame): SqliteConnection < (Async & Abort[SqlException]) =
        given AllowUnsafe = AllowUnsafe.embrace.danger
        val busyMillis    = SqliteConnectionFactory.busyTimeoutMillis(config)
        Connection.OpenProgress.enter("configure").andThen(
            SqliteNativeCalls.run(bindings.configureConnection(db, busyMillis))
        ).flatMap { rc =>
            if rc != SqliteConnection.Ok then
                Abort.fail(SqliteOpenFailedException(address.path, s"configuring the connection failed: ${bindings.errmsg(db).value}"))
            else
                journalMode(bindings, db, address, busyMillis).andThen {
                    for
                        meter    <- Meter.initMutexUnscoped
                        openFlag <- AtomicBoolean.init(true)
                        // Guards the handle's EXISTENCE, not what is running on it, so an interrupt can take it without waiting for the
                        // statement it was asked to stop. See SqliteConnection.cancelInFlight.
                        lifetime      <- Meter.initMutexUnscoped
                        inFlight      <- AtomicBoolean.init(false)
                        inTransaction <- AtomicBoolean.init(false)
                        id            <- Sync.defer(SqliteConnectionFactory.nextId())
                        conn = new SqliteConnection(
                            id,
                            bindings,
                            db,
                            meter,
                            lifetime,
                            openFlag,
                            inFlight,
                            inTransaction,
                            busyMillis,
                            address.path
                        )
                        _ <- attachAll(conn, address, config)
                    yield conn
                }
            end if
        }
    end configured

    /** Runs the [[kyo.SqliteAttach]] statements on a freshly opened connection, before it is lent to anyone.
      *
      * Here rather than as a caller's statement because `ATTACH` is per connection: issued through the pool it would reach one connection
      * and leave the rest answering "no such table" for a name that just worked on its neighbour.
      *
      * Any failure fails the open, which closes the handle. Admitting the connection with a schema missing would leave the pool holding
      * sessions that disagree about which schemas exist, so which one a statement drew would decide whether it worked.
      */
    private def attachAll(conn: SqliteConnection, address: SqlConfig.Address.Local, config: SqlConfig)(using
        Frame
    ): Unit < (Async & Abort[SqlException]) =
        val statements = SqliteAttach.statementsFor(config)
        if statements.isEmpty then ()
        else
            Connection.OpenProgress.enter("attach").andThen {
                Abort.run[SqlException](Kyo.foreachDiscard(statements)(conn.simpleExecute(_).unit)).flatMap {
                    case Result.Success(_) => ()
                    // Reported as an open failure, naming the path, because that is what the caller asked for and
                    // a statement-level error says nothing about which database it was configuring. The engine's own
                    // message carries the SQLite code.
                    case Result.Failure(e) =>
                        Abort.fail(SqliteOpenFailedException(
                            address.path,
                            s"attaching a configured database failed: ${e.getMessage}"
                        ))
                    case Result.Panic(t) => Abort.panic(t)
                }
            }
        end if
    end attachAll

    /** Puts a file-backed database into WAL, which is persistent in the FILE rather than per connection.
      *
      * Under the default rollback journal a reader inside a transaction blocks every writer outright, turning ordinary concurrency into a
      * lock error. A failure here is not fatal: the mode may already be set by another connection, the filesystem may not support it, and
      * an in-memory database has no file to hold it, and in each case the connection still works with less concurrency.
      */
    private def journalMode(bindings: SqliteBindings, db: Ffi.Handle[SqliteDb], address: SqlConfig.Address.Local, busyMillis: Int)(using
        Frame
    ): Unit < Async =
        if address.path == SqliteUrl.InMemory || address.path == SqliteUrl.Temporary then Kyo.unit
        else
            Connection.OpenProgress.enter("journal mode").andThen {
                SqliteLockWait.deferring(bindings, db, busyMillis) {
                    SqliteNativeCalls.run(bindings.execSimple(db, "PRAGMA journal_mode = WAL"))
                }(SqliteLockWait.isBusy).unit
            }

    /** The calls every SQLite open in the process shares a worker pool with, at the instant an open ran out of budget. On JS an open behind
      * a full pool has not started, which the open's own phases cannot tell from one that is slow.
      */
    override private[kyo] def openDiagnostics()(using AllowUnsafe): Maybe[String] =
        Present(
            s"${SqliteNativeCalls.inFlight} SQLite calls in native code or queued for a worker, " +
                s"${SqliteLockWait.waiting} statements waiting on a lock"
        )

end SqliteConnectionFactory

private[sqlite] object SqliteConnectionFactory:

    // SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE: a missing file is created.
    val OpenFlags: Int = 0x00000002 | 0x00000004

    /** The default VFS, spelled empty rather than null: a String argument is marshalled by reading its bytes, so a null one throws before
      * the call is made. The shim translates empty back to NULL.
      */
    val DefaultVfs: String = ""

    /** SQLite's busy timeout, which is the pool's `acquireTimeout`, saturated at `Int.MaxValue`: SQLite takes an `int`, and a long timeout
      * that wrapped negative would mean no waiting at all.
      */
    def busyTimeoutMillis(config: SqlConfig): Int =
        math.min(config.acquireTimeout.toMillis, Int.MaxValue.toLong).toInt

    private val ids = java.util.concurrent.atomic.AtomicLong(0L)

    def nextId(): Long = ids.incrementAndGet()

end SqliteConnectionFactory
