package kyo

import kyo.db.Backend
import kyo.db.Idiom
import kyo.db.Runtime
import kyo.internal.dolt.DoltBackendFactory
import kyo.internal.dolt.DoltConnection
import kyo.internal.dolt.DoltConnectionFactory
import kyo.internal.dolt.DoltDialect
import kyo.internal.dolt.DoltStatements

/** The [[DoltClient]] backed by a Dolt server over the MySQL wire protocol.
  *
  * Every operation is documented once, on [[DoltClient]]. What lives here is how this engine performs it: stored procedures answering
  * result sets, table functions taking refs, and system tables.
  */
final private[kyo] class DoltServerClient(runtime: Runtime[DoltConnection]) extends DoltClient(runtime):

    override def dialect: Idiom = DoltDialect

    override def commit(message: String, all: Boolean = true)(using Frame): DoltCommit < (Async & Abort[SqlException]) =
        DoltStatements.commit(this, message, all)

    override def log(ref: DoltRef = DoltRef.Head, limit: Maybe[Int] = Absent)(using
        Frame
    ): Chunk[DoltCommit] < (Async & Abort[SqlException]) =
        DoltStatements.log(this, ref, limit)

    override def status(using Frame): Chunk[DoltChange] < (Async & Abort[SqlException]) =
        DoltStatements.status(this)

    override def diff(from: DoltRef, to: DoltRef, table: String)(using Frame): Chunk[DoltDiff] < (Async & Abort[SqlException]) =
        DoltStatements.diff(this, from, to, table)

    override def branches(using Frame): Chunk[DoltBranch] < (Async & Abort[SqlException]) =
        DoltStatements.branches(this)

    override def createBranch(name: String, from: DoltRef = DoltRef.Head)(using Frame): DoltBranch < (Async & Abort[SqlException]) =
        DoltStatements.createBranch(this, name, from)

    override def deleteBranch(name: String, force: Boolean = false)(using Frame): Unit < (Async & Abort[SqlException]) =
        DoltStatements.deleteBranch(this, name, force)

    override def merge(from: DoltRef)(using Frame): DoltMerge < (Async & Abort[SqlException]) =
        DoltStatements.merge(this, from)

    override def conflicts(using Frame): Chunk[DoltConflictSummary] < (Async & Abort[SqlException]) =
        DoltStatements.conflicts(this)

    override def schemaConflicts(using Frame): Chunk[DoltSchemaConflict] < (Async & Abort[SqlException]) =
        DoltStatements.schemaConflicts(this)

    override def resolveConflicts(table: String, keeping: DoltResolution)(using Frame): Long < (Async & Abort[SqlException]) =
        DoltStatements.resolveConflicts(this, table, keeping)

    override def reset(to: DoltRef, mode: DoltResetMode = DoltResetMode.Mixed)(using Frame): Unit < (Async & Abort[SqlException]) =
        DoltStatements.reset(this, to, mode)

    override def revert(ref: DoltRef)(using Frame): DoltCommit < (Async & Abort[SqlException]) =
        DoltStatements.revert(this, ref)

    override def cherryPick(ref: DoltRef)(using Frame): DoltCommit < (Async & Abort[SqlException]) =
        DoltStatements.cherryPick(this, ref)

    override def tag(name: String, ref: DoltRef = DoltRef.Head, message: Maybe[String] = Absent)(using
        Frame
    ): DoltTag < (Async & Abort[SqlException]) =
        DoltStatements.tag(this, name, ref, message)

    override def tags(using Frame): Chunk[DoltTag] < (Async & Abort[SqlException]) =
        DoltStatements.tags(this)

    override def deleteTag(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        DoltStatements.deleteTag(this, name)

    override def remotes(using Frame): Chunk[DoltRemote] < (Async & Abort[SqlException]) =
        DoltStatements.remotes(this)

    override def addRemote(name: String, url: String)(using Frame): DoltRemote < (Async & Abort[SqlException]) =
        DoltStatements.addRemote(this, name, url)

    override def removeRemote(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        DoltStatements.removeRemote(this, name)

    override def push(remote: String, branch: String, force: Boolean = false)(using Frame): Unit < (Async & Abort[SqlException]) =
        DoltStatements.push(this, remote, branch, force)

    override def fetch(remote: String, branch: Maybe[String] = Absent)(using Frame): Unit < (Async & Abort[SqlException]) =
        DoltStatements.fetch(this, remote, branch)

    override def pull(remote: String, branch: Maybe[String] = Absent)(using Frame): DoltMerge < (Async & Abort[SqlException]) =
        DoltStatements.pull(this, remote, branch)

end DoltServerClient

/** Opens Dolt servers and registers the backend. */
object Dolt:

    /** The SQL flavor this backend renders, for a caller rendering a statement ahead of opening a client. */
    val dialect: Idiom = DoltDialect

    /** Opens a client on `url`, unscoped, for the backend factory to hand back.
      *
      * `config` is the portable [[SqlConfig]] and nothing else: this backend attaches no `SqlConfig.Extension`, since every setting it
      * honors is one the MySQL transport beneath already honors, and the revision a session runs against is scoped per fiber by
      * [[DoltClient.onBranch]] rather than configured once per client.
      */
    private[kyo] def openUnscoped(url: SqlConfig.Url, config: SqlConfig)(using
        Frame
    ): DoltClient < (Async & Abort[SqlException]) =
        Runtime.init(url, config, new DoltConnectionFactory(url.options)).map(rt => new DoltServerClient(rt))

    /** Registers this backend so runtime discovery resolves a computed `dolt://` URL, the explicit counterpart to the
      * `META-INF/services/kyo.db.Backend` entry the JVM reads automatically.
      *
      * Needed only on Scala Native, which embeds a single `META-INF/services/kyo.db.Backend` file when several jars declare the service, so
      * a program opening more than one flavor by computed URL cannot rely on the scan. Redundant but harmless elsewhere.
      */
    def register(): Unit =
        Backend.register(new DoltBackendFactory())

end Dolt
