package kyo

import kyo.db.Backend
import kyo.db.Idiom
import kyo.db.Runtime
import kyo.internal.sqlite.SqliteBackendFactory
import kyo.internal.sqlite.SqliteConnection
import kyo.internal.sqlite.SqliteConnectionFactory
import kyo.internal.sqlite.SqliteDialect

/** A client over one SQLite database.
  *
  * Its surface is the portable one and nothing more. SQLite has no engine-only operations worth widening the API for, the way PostgreSQL has
  * notifications: what it has that the others do not is an absence of things, and those are reported through typed refusals rather than
  * through methods.
  *
  * Pooling still applies and means something different from a network pool. Several connections to one file is what SQLite's own locking is
  * built for, and is how readers proceed while a writer holds the write lock. It is not amortising a handshake, because there is none.
  */
final class SqliteClient private[kyo] (runtime: Runtime[SqliteConnection]) extends SqlClient(runtime):

    def dialect: Idiom = SqliteClient.dialect

end SqliteClient

object SqliteClient:

    private[kyo] val dialect: Idiom = new SqliteDialect

    private[kyo] def openUnscoped(url: SqlConfig.Url, config: SqlConfig)(using
        Frame
    ): SqliteClient < (Async & Abort[SqlException]) =
        Runtime.init(url, config, new SqliteConnectionFactory(url)).map(rt => new SqliteClient(rt))

    /** Registers the SQLite backend so runtime discovery resolves a computed `sqlite://` URL, the explicit counterpart to the
      * `META-INF/services/kyo.db.Backend` entry the JVM reads automatically.
      *
      * Needed only where the services scan cannot reach every backend: a Scala Native program that opens more than one flavor by computed
      * URL, since Native embeds a single `META-INF/services/kyo.db.Backend` file when several jars declare the service. Redundant but
      * harmless on the JVM, and on JS and Wasm where the same registration runs automatically at module load.
      */
    def register(): Unit =
        Backend.register(new SqliteBackendFactory())

end SqliteClient
