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

    private def openLocal(address: SqlConfig.Address.Local, config: SqlConfig)(using
        Frame
    ): SqliteConnection < (Async & Abort[SqlException]) =
        Sync.defer {
            given AllowUnsafe = AllowUnsafe.embrace.danger
            val vfs           = config.extensionFor[SqliteVfs].fold(SqliteConnectionFactory.DefaultVfs)(_.name)
            Sync.Unsafe.defer(
                bindings.openV2(address.path, SqliteConnectionFactory.OpenFlags, vfs)
            ).map(_.safe.get).flatMap {
                case Absent =>
                    // NULL comes back only when SQLite could not allocate the handle, which is not a database error
                    // and leaves nothing to read an error off.
                    Abort.fail(SqliteOpenFailedException(address.path, "SQLite could not allocate a connection handle"))
                case Present(db) =>
                    val code = bindings.extendedErrcode(db)
                    if code != SqliteConnection.Ok then
                        // The handle exists even on failure, which is where the error text lives, so it is read before
                        // being closed rather than discarded with it.
                        val message = bindings.errmsg(db).value
                        Sync.Unsafe.defer(bindings.closeV2(db)).map(_.safe.get).andThen {
                            Abort.fail(SqliteOpenFailedException(address.path, message))
                        }
                    else configured(bindings, db, address, config)
                    end if
            }
        }

    private def configured(
        bindings: SqliteBindings,
        db: Ffi.Handle[SqliteDb],
        address: SqlConfig.Address.Local,
        config: SqlConfig
    )(using Frame): SqliteConnection < (Async & Abort[SqlException]) =
        given AllowUnsafe = AllowUnsafe.embrace.danger
        val busyMillis    = config.acquireTimeout.toMillis.toInt
        Sync.Unsafe.defer(bindings.configureConnection(db, busyMillis)).map(_.safe.get).flatMap { rc =>
            if rc != SqliteConnection.Ok then
                val message = bindings.errmsg(db).value
                Sync.Unsafe.defer(bindings.closeV2(db)).map(_.safe.get).andThen {
                    Abort.fail(SqliteOpenFailedException(address.path, s"configuring the connection failed: $message"))
                }
            else
                journalMode(bindings, db, address).andThen {
                    for
                        meter    <- Meter.initMutexUnscoped
                        openFlag <- AtomicBoolean.init(true)
                        // Guards the handle's EXISTENCE, not what is running on it, so an interrupt can take it without waiting for the
                        // statement it was asked to stop. See SqliteConnection.cancelInFlight.
                        lifetime      <- Meter.initMutexUnscoped
                        inFlight      <- AtomicBoolean.init(false)
                        inTransaction <- AtomicBoolean.init(false)
                        id            <- Sync.defer(SqliteConnectionFactory.nextId())
                    yield new SqliteConnection(id, bindings, db, meter, lifetime, openFlag, inFlight, inTransaction)
                }
            end if
        }
    end configured

    /** Puts a file-backed database into WAL, which is persistent in the FILE rather than per connection.
      *
      * Under the default rollback journal a reader inside a transaction blocks every writer outright, turning ordinary concurrency into a
      * lock error. A failure here is not fatal: the mode may already be set by another connection, the filesystem may not support it, and
      * an in-memory database has no file to hold it, and in each case the connection still works with less concurrency.
      */
    private def journalMode(bindings: SqliteBindings, db: Ffi.Handle[SqliteDb], address: SqlConfig.Address.Local)(using
        Frame
    ): Unit < Async =
        if address.path == SqliteUrl.InMemory || address.path == SqliteUrl.Temporary then Kyo.unit
        else Sync.Unsafe.defer(bindings.execSimple(db, "PRAGMA journal_mode = WAL")).map(_.safe.get).unit

end SqliteConnectionFactory

private[sqlite] object SqliteConnectionFactory:

    // SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE: a missing file is created.
    val OpenFlags: Int = 0x00000002 | 0x00000004

    /** The default VFS, spelled empty rather than null: a String argument is marshalled by reading its bytes, so a null one throws before
      * the call is made. The shim translates empty back to NULL.
      */
    val DefaultVfs: String = ""

    private val ids = java.util.concurrent.atomic.AtomicLong(0L)

    def nextId(): Long = ids.incrementAndGet()

end SqliteConnectionFactory
