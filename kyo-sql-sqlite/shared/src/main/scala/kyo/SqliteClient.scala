package kyo

import kyo.db.Backend
import kyo.db.Idiom
import kyo.db.Runtime
import kyo.ffi.Ffi
import kyo.internal.sqlite.SqliteBackendFactory
import kyo.internal.sqlite.SqliteConnection
import kyo.internal.sqlite.SqliteConnectionFactory
import kyo.internal.sqlite.SqliteDialect
import kyo.internal.sqlite.VendoredSqliteBindings

/** A client over one SQLite database.
  *
  * Its surface is the portable one and nothing more: what SQLite has that the other engines do not is an absence of things, reported through
  * typed refusals rather than through methods. Pooling still applies and amortises no handshake, there being none. Several connections to
  * one file is what SQLite's own locking is built for, and is how readers proceed while a writer holds the write lock.
  */
final class SqliteClient private[kyo] (runtime: Runtime[SqliteConnection]) extends SqlClient(runtime):

    def dialect: Idiom = SqliteClient.dialect

end SqliteClient

/** Opens SQLite clients, and narrows the ambient client to one.
  *
  * Deliberately without the `init` family its two siblings carry: those hand back a client typed as the engine's own, which is worth having
  * only when that type carries operations `SqlClient` does not, and this one carries none. `SqlClient.init` with a `sqlite://` URL is the
  * entry, the same one a program uses for any other engine.
  */
object SqliteClient:

    /** The SQL flavor this backend renders, for a caller rendering a statement ahead of opening a client. */
    val dialect: Idiom = SqliteDialect

    /** Opens a client on `url`, unscoped, for the backend factory to hand back.
      *
      * `config` is the portable [[SqlConfig]] and nothing else: this backend attaches no `SqlConfig.Extension`, the settings one would carry
      * being transport settings and there being no transport. `acquireTimeout` does double duty as the engine's busy timeout.
      */
    private[kyo] def openUnscoped(url: SqlConfig.Url, config: SqlConfig)(using
        Frame
    ): SqliteClient < (Async & Abort[SqlException]) =
        Scope.runUnowned(opened(url, config))

    /** Builds a client on `url` under the ambient scope, for the backend factory to hand back.
      *
      * The row carries [[kyo.Scope]] because `Runtime.init` registers the pool's release against it as the pool is allocated.
      */
    private[kyo] def opened(url: SqlConfig.Url, config: SqlConfig)(using Frame): SqliteClient < (Async & Abort[SqlException] & Scope) =
        // The bindings are loaded once per client rather than per connection and handed to the factory, which is what
        // lets the sibling embedded backend reuse this connection layer against its own library.
        Sync.Unsafe.defer(Ffi.load[VendoredSqliteBindings]).flatMap { bindings =>
            Runtime.init(url, config, new SqliteConnectionFactory(bindings)).map(rt => new SqliteClient(rt))
        }

    /** Runs `f` with the ambient client narrowed to this backend's own type, so a body states which engine it needs: a `DB` installed with
      * another engine's client fails with a typed mismatch rather than at the first statement, and no ambient `DB` at all is a compile
      * error.
      */
    def use[A, S](f: SqliteClient => A < S)(using Frame): A < (S & Abort[SqlException] & DB) =
        DB.clientAs[SqliteClient].map(f)

    /** Registers the SQLite backend so runtime discovery resolves a computed `sqlite://` URL, the explicit counterpart to the
      * `META-INF/services/kyo.db.Backend` entry the JVM reads automatically. Needed only on Scala Native, which embeds a single services
      * file when several jars declare the service, so a program opening more than one flavor by computed URL has to register them.
      * Redundant but harmless on the JVM, and on JS and Wasm where registration runs at module load.
      */
    def register(): Unit =
        Backend.register(new SqliteBackendFactory())

end SqliteClient
