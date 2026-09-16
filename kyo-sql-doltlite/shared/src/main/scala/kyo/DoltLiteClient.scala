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

/** The [[DoltClient]] backed by an embedded DoltLite file. Every operation is documented once, on [[DoltClient]]; what
  * lives here is how this engine performs it, through SQL functions and per-table virtual tables.
  */
final private[kyo] class DoltLiteClient(runtime: Runtime[DoltLiteConnection]) extends DoltClient(runtime):

    def dialect: Idiom = DoltLiteDialect

    override def commit(message: String, all: Boolean = true)(using Frame): DoltCommit < (Async & Abort[SqlException]) =
        DoltLiteStatements.commit(this, message, all)

    override def log(ref: DoltRef = DoltRef.Head, limit: Maybe[Int] = Absent)(using
        Frame
    ): Chunk[DoltCommit] < (Async & Abort[SqlException]) =
        DoltLiteStatements.log(this, ref, limit)

    override def status(using Frame): Chunk[DoltChange] < (Async & Abort[SqlException]) =
        DoltLiteStatements.status(this)

    override def diff(from: DoltRef, to: DoltRef, table: String)(using
        Frame
    ): Chunk[DoltDiff] < (Async & Abort[SqlException]) =
        DoltLiteStatements.diff(this, from, to, table)

    override def branches(using Frame): Chunk[DoltBranch] < (Async & Abort[SqlException]) =
        DoltLiteStatements.branches(this)

    override def createBranch(name: String, from: DoltRef = DoltRef.Head)(using
        Frame
    ): DoltBranch < (Async & Abort[SqlException]) =
        DoltLiteStatements.createBranch(this, name, from)

    override def deleteBranch(name: String, force: Boolean = false)(using Frame): Unit < (Async & Abort[SqlException]) =
        DoltLiteStatements.deleteBranch(this, name, force)

    override def merge(from: DoltRef)(using Frame): DoltMerge < (Async & Abort[SqlException]) =
        DoltLiteStatements.merge(this, from)

    override def conflicts(using Frame): Chunk[DoltConflictSummary] < (Async & Abort[SqlException]) =
        DoltLiteStatements.conflicts(this)

    override def schemaConflicts(using Frame): Chunk[DoltSchemaConflict] < (Async & Abort[SqlException]) =
        DoltLiteStatements.schemaConflicts(this)

    override def resolveConflicts(table: String, keeping: DoltResolution)(using
        Frame
    ): Long < (Async & Abort[SqlException]) =
        DoltLiteStatements.resolveConflicts(this, table, keeping)

    override def reset(to: DoltRef, mode: DoltResetMode = DoltResetMode.Mixed)(using
        Frame
    ): Unit < (Async & Abort[SqlException]) =
        DoltLiteStatements.reset(this, to, mode)

    override def revert(ref: DoltRef)(using Frame): DoltCommit < (Async & Abort[SqlException]) =
        DoltLiteStatements.revert(this, ref)

    override def cherryPick(ref: DoltRef)(using Frame): DoltCommit < (Async & Abort[SqlException]) =
        DoltLiteStatements.cherryPick(this, ref)

    override def tag(name: String, ref: DoltRef = DoltRef.Head, message: Maybe[String] = Absent)(using
        Frame
    ): DoltTag < (Async & Abort[SqlException]) =
        DoltLiteStatements.tag(this, name, ref, message)

    override def tags(using Frame): Chunk[DoltTag] < (Async & Abort[SqlException]) =
        DoltLiteStatements.tags(this)

    override def deleteTag(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        DoltLiteStatements.deleteTag(this, name)

    override def remotes(using Frame): Chunk[DoltRemote] < (Async & Abort[SqlException]) =
        DoltLiteStatements.remotes(this)

    override def addRemote(name: String, url: String)(using Frame): DoltRemote < (Async & Abort[SqlException]) =
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
    ): DoltMerge < (Async & Abort[SqlException]) =
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
    ): DoltClient < (Async & Abort[SqlException]) =
        // The engine is a compiled library published for some platforms and not others, so failing to load it
        // is this backend being unavailable HERE rather than anything about the URL. Translated into the declared
        // failure type, since the loader raises outside it and would otherwise reach the caller as a panic.
        Abort.catching[FfiLoadError](e => DoltLiteEngineUnavailableException(Maybe(e.getMessage).getOrElse(e.toString))) {
            Sync.Unsafe.defer(Ffi.load[DoltLiteBindings])
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
