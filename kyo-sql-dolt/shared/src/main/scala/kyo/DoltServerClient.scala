package kyo

import kyo.db.Backend
import kyo.db.Idiom
import kyo.db.Runtime
import kyo.internal.dolt.DoltBackendFactory
import kyo.internal.dolt.DoltConnection
import kyo.internal.dolt.DoltConnectionFactory
import kyo.internal.dolt.DoltDialect
import kyo.internal.dolt.DoltStatements

/** The [[Dolt]] backed by a Dolt server over the MySQL wire protocol.
  *
  * Every operation is documented once, on [[Dolt]]. What lives here is how this engine performs it: stored procedures answering
  * result sets, table functions taking refs, and system tables.
  */
final private[kyo] class DoltServerClient(runtime: Runtime[DoltConnection]) extends Dolt(runtime):

    override def dialect: Idiom = DoltDialect

    override def add(tables: String*)(using Frame): Unit < (Async & Abort[SqlException]) =
        DoltStatements.add(this, Chunk.from(tables))

    override def commit(message: String, all: Boolean = true)(using Frame): Dolt.Commit < (Async & Abort[SqlException]) =
        DoltStatements.commit(this, message, all)

    override def log(ref: Dolt.Ref = Dolt.Ref.Head, limit: Maybe[Int] = Absent)(using
        Frame
    ): Chunk[Dolt.Commit] < (Async & Abort[SqlException]) =
        DoltStatements.log(this, ref, limit)

    override def status(using Frame): Chunk[Dolt.Change] < (Async & Abort[SqlException]) =
        DoltStatements.status(this)

    override def diff(from: Dolt.Ref, to: Dolt.Ref, table: String)(using Frame): Chunk[Dolt.Diff] < (Async & Abort[SqlException]) =
        DoltStatements.diff(this, from, to, table)

    override def branches(using Frame): Chunk[Dolt.Branch] < (Async & Abort[SqlException]) =
        DoltStatements.branches(this)

    override def createBranch(name: String, from: Dolt.Ref = Dolt.Ref.Head)(using Frame): Dolt.Branch < (Async & Abort[SqlException]) =
        DoltStatements.createBranch(this, name, from)

    override def deleteBranch(name: String, force: Boolean = false)(using Frame): Unit < (Async & Abort[SqlException]) =
        DoltStatements.deleteBranch(this, name, force)

    override def merge(from: Dolt.Ref)(using Frame): Dolt.Merge < (Async & Abort[SqlException]) =
        DoltStatements.merge(this, from)

    override def conflicts(using Frame): Chunk[Dolt.ConflictSummary] < (Async & Abort[SqlException]) =
        DoltStatements.conflicts(this)

    override def schemaConflicts(using Frame): Chunk[Dolt.SchemaConflict] < (Async & Abort[SqlException]) =
        DoltStatements.schemaConflicts(this)

    override def resolveConflicts(table: String, keeping: Dolt.Resolution)(using Frame): Long < (Async & Abort[SqlException]) =
        DoltStatements.resolveConflicts(this, table, keeping)

    override def reset(to: Dolt.Ref, mode: Dolt.ResetMode = Dolt.ResetMode.Mixed)(using Frame): Unit < (Async & Abort[SqlException]) =
        DoltStatements.reset(this, to, mode)

    override def revert(ref: Dolt.Ref)(using Frame): Dolt.Commit < (Async & Abort[SqlException]) =
        DoltStatements.revert(this, ref)

    override def cherryPick(ref: Dolt.Ref)(using Frame): Dolt.Commit < (Async & Abort[SqlException]) =
        DoltStatements.cherryPick(this, ref)

    override def tag(name: String, ref: Dolt.Ref = Dolt.Ref.Head, message: Maybe[String] = Absent)(using
        Frame
    ): Dolt.Tag < (Async & Abort[SqlException]) =
        DoltStatements.tag(this, name, ref, message)

    override def tags(using Frame): Chunk[Dolt.Tag] < (Async & Abort[SqlException]) =
        DoltStatements.tags(this)

    override def deleteTag(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        DoltStatements.deleteTag(this, name)

    override def remotes(using Frame): Chunk[Dolt.Remote] < (Async & Abort[SqlException]) =
        DoltStatements.remotes(this)

    override def addRemote(name: String, url: String)(using Frame): Dolt.Remote < (Async & Abort[SqlException]) =
        DoltStatements.addRemote(this, name, url)

    override def removeRemote(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        DoltStatements.removeRemote(this, name)

    override def push(remote: String, branch: String, force: Boolean = false)(using Frame): Unit < (Async & Abort[SqlException]) =
        DoltStatements.push(this, remote, branch, force)

    override def fetch(remote: String, branch: Maybe[String] = Absent)(using Frame): Unit < (Async & Abort[SqlException]) =
        DoltStatements.fetch(this, remote, branch)

    override def pull(remote: String, branch: Maybe[String] = Absent)(using Frame): Dolt.Merge < (Async & Abort[SqlException]) =
        DoltStatements.pull(this, remote, branch)

end DoltServerClient

/** Opens Dolt servers and registers the backend. */
object DoltServer:

    /** The SQL flavor this backend renders, for a caller rendering a statement ahead of opening a client. */
    val dialect: Idiom = DoltDialect

    /** Opens a client on `url`, unscoped, for the backend factory to hand back.
      *
      * `config` is the portable [[SqlConfig]] and nothing else: this backend attaches no `SqlConfig.Extension`, since every setting it
      * honors is one the MySQL transport beneath already honors, and the revision a session runs against is scoped per fiber by
      * [[Dolt.onBranch]] rather than configured once per client.
      */
    private[kyo] def openUnscoped(url: SqlConfig.Url, config: SqlConfig)(using
        Frame
    ): Dolt < (Async & Abort[SqlException]) =
        Scope.runUnowned(opened(url, config))

    /** Builds a client on `url` under the ambient scope, for the backend factory to hand back.
      *
      * The row carries [[kyo.Scope]] because `Runtime.init` registers the pool's release against it as the pool is allocated.
      */
    private[kyo] def opened(url: SqlConfig.Url, config: SqlConfig)(using Frame): Dolt < (Async & Abort[SqlException] & Scope) =
        Runtime.init(url, config, new DoltConnectionFactory(url.options)).map(rt => new DoltServerClient(rt))

    /** Registers this backend so runtime discovery resolves a computed `dolt://` URL, the explicit counterpart to the
      * `META-INF/services/kyo.db.Backend` entry the JVM reads automatically.
      *
      * Needed only on Scala Native, which embeds a single `META-INF/services/kyo.db.Backend` file when several jars declare the service, so
      * a program opening more than one flavor by computed URL cannot rely on the scan. Redundant but harmless elsewhere.
      */
    def register(): Unit =
        Backend.register(new DoltBackendFactory())

end DoltServer
