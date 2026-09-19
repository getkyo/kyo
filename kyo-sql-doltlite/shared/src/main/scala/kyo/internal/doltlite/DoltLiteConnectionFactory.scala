package kyo.internal.doltlite

import kyo.*
import kyo.db.Connection
import kyo.internal.sqlite.SqliteConnectionFactory

/** Opens DoltLite sessions by opening SQLite ones and wrapping each so it tracks the branch it is checked out on.
  *
  * @param underlying
  *   the SQLite factory, already given this engine's own bindings
  */
final private[kyo] class DoltLiteConnectionFactory(underlying: SqliteConnectionFactory)
    extends Connection.Factory[DoltLiteConnection]:

    def open(address: SqlConfig.Address, password: Maybe[String], config: SqlConfig)(using
        Frame
    ): DoltLiteConnection < (Async & Abort[SqlException]) =
        underlying.open(address, password, config).flatMap { conn =>
            // A file URL carries no branch, so a fresh connection opens on the engine's default and Absent records
            // that nothing was asked for.
            // Unsafe: one reference holding where this session is pointed, initialised before the connection is
            // visible to any caller and only ever read and written through the session that owns it.
            Sync.Unsafe.defer(new DoltLiteConnection(conn, Absent, AtomicRef.Unsafe.init(Absent)))
        }

end DoltLiteConnectionFactory
