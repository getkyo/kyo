package kyo

import kyo.db.Backend
import kyo.db.Idiom
import kyo.db.Runtime
import kyo.ffi.Ffi
import kyo.ffi.FfiLoadError
import kyo.internal.dolt.DoltBranchScope
import kyo.internal.doltlite.DoltLiteBackendFactory
import kyo.internal.doltlite.DoltLiteBindings
import kyo.internal.doltlite.DoltLiteConnection
import kyo.internal.doltlite.DoltLiteConnectionFactory
import kyo.internal.doltlite.DoltLiteDialect
import kyo.internal.doltlite.DoltLiteStatements
import kyo.internal.sqlite.SqliteConnectionFactory

/** The [[Dolt]] backed by an embedded DoltLite file. Every operation is documented once, on [[Dolt]]; what
  * lives here is how this engine performs it, through SQL functions and per-table virtual tables.
  */
final private[kyo] class DoltLiteClient(runtime: Runtime[DoltLiteConnection]) extends Dolt(runtime):

    def dialect: Idiom = DoltLiteDialect

    override def add(tables: String*)(using Frame): Unit < (Async & Abort[SqlException]) =
        DoltLiteStatements.add(this, Chunk.from(tables))

    override def commit(message: String, all: Boolean = true)(using Frame): Dolt.Commit < (Async & Abort[SqlException]) =
        DoltLiteStatements.commit(this, message, all)

    override def log(ref: Dolt.Ref = Dolt.Ref.Head, limit: Maybe[Int] = Absent)(using
        Frame
    ): Chunk[Dolt.Commit] < (Async & Abort[SqlException]) =
        DoltLiteStatements.log(this, ref, limit)

    override def status(using Frame): Chunk[Dolt.Change] < (Async & Abort[SqlException]) =
        DoltLiteStatements.status(this)

    override def diff(from: Dolt.Ref, to: Dolt.Ref, table: String)(using
        Frame
    ): Chunk[Dolt.Diff] < (Async & Abort[SqlException]) =
        DoltLiteStatements.diff(this, from, to, table)

    override def branches(using Frame): Chunk[Dolt.Branch] < (Async & Abort[SqlException]) =
        DoltLiteStatements.branches(this)

    override def createBranch(name: String, from: Dolt.Ref = Dolt.Ref.Head)(using
        Frame
    ): Dolt.Branch < (Async & Abort[SqlException]) =
        DoltLiteStatements.createBranch(this, name, from)

    override def deleteBranch(name: String, force: Boolean = false)(using Frame): Unit < (Async & Abort[SqlException]) =
        DoltLiteStatements.deleteBranch(this, name, force)

    override def merge(from: Dolt.Ref)(using Frame): Dolt.Merge < (Async & Abort[SqlException]) =
        DoltLiteStatements.merge(this, from)

    override def conflicts(using Frame): Chunk[Dolt.ConflictSummary] < (Async & Abort[SqlException]) =
        DoltLiteStatements.conflicts(this)

    override def schemaConflicts(using Frame): Chunk[Dolt.SchemaConflict] < (Async & Abort[SqlException]) =
        DoltLiteStatements.schemaConflicts(this)

    override def resolveConflicts(table: String, keeping: Dolt.Resolution)(using
        Frame
    ): Long < (Async & Abort[SqlException]) =
        DoltLiteStatements.resolveConflicts(this, table, keeping)

    override def reset(to: Dolt.Ref, mode: Dolt.ResetMode = Dolt.ResetMode.Mixed)(using
        Frame
    ): Unit < (Async & Abort[SqlException]) =
        DoltLiteStatements.reset(this, to, mode)

    override def revert(ref: Dolt.Ref)(using Frame): Dolt.Commit < (Async & Abort[SqlException]) =
        DoltLiteStatements.revert(this, ref)

    override def cherryPick(ref: Dolt.Ref)(using Frame): Dolt.Commit < (Async & Abort[SqlException]) =
        DoltLiteStatements.cherryPick(this, ref)

    override def tag(name: String, ref: Dolt.Ref = Dolt.Ref.Head, message: Maybe[String] = Absent)(using
        Frame
    ): Dolt.Tag < (Async & Abort[SqlException]) =
        DoltLiteStatements.tag(this, name, ref, message)

    override def tags(using Frame): Chunk[Dolt.Tag] < (Async & Abort[SqlException]) =
        DoltLiteStatements.tags(this)

    override def deleteTag(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        DoltLiteStatements.deleteTag(this, name)

    override def remotes(using Frame): Chunk[Dolt.Remote] < (Async & Abort[SqlException]) =
        DoltLiteStatements.remotes(this)

    override def addRemote(name: String, url: String)(using Frame): Dolt.Remote < (Async & Abort[SqlException]) =
        DoltLiteStatements.addRemote(this, name, url)

    override def removeRemote(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        DoltLiteStatements.removeRemote(this, name)

    override def push(remote: String, branch: String, force: Boolean = false)(using
        Frame
    ): Unit < (Async & Abort[SqlException]) =
        DoltLiteStatements.push(this, remote, branch, force)

    override def fetch(remote: String, branch: Maybe[String] = Absent)(using Frame): Unit < (Async & Abort[SqlException]) =
        DoltLiteStatements.fetch(this, remote, branch)

    override def pull(remote: String, branch: Maybe[String] = Absent)(using
        Frame
    ): Dolt.Merge < (Async & Abort[SqlException]) =
        DoltLiteStatements.pull(this, remote, branch)

end DoltLiteClient

/** Opens DoltLite databases and registers the backend. */
object DoltLite:

    /** The SQL flavor this backend renders, for a caller rendering a statement ahead of opening a client. */
    val dialect: Idiom = DoltLiteDialect

    /** Opens a client on `url`, unscoped, for the backend factory to hand back. The connection layer is
      * kyo-sql-sqlite's, given this engine's own bindings.
      */
    private[kyo] def openUnscoped(url: SqlConfig.Url, config: SqlConfig)(using
        Frame
    ): Dolt < (Async & Abort[SqlException]) =
        // The engine is a compiled library published for some platforms and not others, so failing to reach it is
        // this backend being unavailable HERE rather than anything about the URL. Translated into the declared
        // failure type, since the loader raises outside it and would otherwise reach the caller as a panic.
        //
        // The binding is CALLED, not merely loaded. On the JS runtime a load resolves its dispatch table lazily, so
        // a missing engine leaves the load silent and a caller receives a client that fails at its first statement,
        // which is the panic this translation exists to prevent. `libversionNumber` is the cheapest call there is:
        // no database, no handle, no allocation.
        //
        // Absence does not announce itself as one type. Where the loader can see the native is missing it raises
        // `FfiLoadError.LibraryNotFound`; the JS runtime instead raises a `TypeError` off the null dispatch table.
        // Hence `Throwable` rather than `FfiLoadError` alone. The widening is bounded: `Abort.catching` admits only
        // what `NonFatal` allows, so a `VirtualMachineError` or an interrupt still passes through untranslated.
        // The reason states the situation before quoting the cause, because only some runtimes describe it: the
        // loader names the platform it looked for and every path it tried, while a null dispatch table yields
        // nothing but a property name. The sentence that survives everywhere is the one that matters to a caller.
        Abort.catching[Throwable](e =>
            DoltLiteEngineUnavailableException(
                s"it is not published for this platform. ${Maybe(e.getMessage).getOrElse(e.toString)}"
            )
        ) {
            Sync.Unsafe.defer {
                val loaded = Ffi.load[DoltLiteBindings]
                val _      = loaded.libversionNumber()
                loaded
            }
        }.flatMap { bindings =>
            val factory = new DoltLiteConnectionFactory(new SqliteConnectionFactory(bindings))
            Runtime.init(url, config, factory).map(rt => new DoltLiteClient(rt))
        }

    /** Registers the DoltLite backend so runtime discovery resolves a computed `doltlite://` URL, the explicit
      * counterpart to the `META-INF/services/kyo.db.Backend` entry the JVM reads automatically.
      */
    def register(): Unit =
        Backend.register(new DoltLiteBackendFactory())

end DoltLite
