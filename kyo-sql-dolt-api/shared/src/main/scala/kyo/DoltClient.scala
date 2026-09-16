package kyo

import kyo.db.Runtime
import kyo.internal.dolt.DoltBranchScope

/** A client over one versioned database, which is a SQL client plus its history.
  *
  * The type a caller holds whichever engine is underneath: `kyo-sql-dolt` opens a MySQL-wire server, `kyo-sql-doltlite` opens an embedded
  * file, and both supply a `private[kyo]` subclass. Everything portable is kyo-sql's and unchanged; what this type adds is version control.
  *
  * A version-control commit is not a SQL commit. A transaction writes to the branch's WORKING SET; [[commit]] turns that working set into a
  * commit in the history. The working set belongs to the BRANCH rather than to a connection, so once a transaction commits its writes are
  * visible to every session on that branch and a [[commit]] from any of them captures the lot. A caller wanting a commit that holds exactly
  * its own writes makes them on a branch of its own, which is what [[onBranch]] is for.
  */
abstract class DoltClient private[kyo] (runtime: Runtime[?]) extends SqlClient(runtime):

    // --- History ---

    /** Turns the branch's working set into a commit, and answers the whole commit rather than only its hash.
      *
      * `all` stages every changed table first, which is `git commit -a`. Passing false commits only what a previous staging call added.
      * Committing nothing is an error rather than a silent no-op.
      */
    def commit(message: String, all: Boolean = true)(using Frame): DoltCommit < (Async & Abort[SqlException])

    /** The commits reachable from `ref`, newest first. */
    def log(ref: DoltRef = DoltRef.Head, limit: Maybe[Int] = Absent)(using Frame): Chunk[DoltCommit] < (Async & Abort[SqlException])

    /** Which tables hold uncommitted changes, and whether each change is staged. */
    def status(using Frame): Chunk[DoltChange] < (Async & Abort[SqlException])

    /** How one table differs between two refs, a row per changed row. */
    def diff(from: DoltRef, to: DoltRef, table: String)(using Frame): Chunk[DoltDiff] < (Async & Abort[SqlException])

    // --- Branches ---

    /** Every branch in this database. */
    def branches(using Frame): Chunk[DoltBranch] < (Async & Abort[SqlException])

    /** Creates `name` pointing at `from` and answers it, leaving the session where it is. [[onBranch]] is what moves a session. */
    def createBranch(name: String, from: DoltRef = DoltRef.Head)(using Frame): DoltBranch < (Async & Abort[SqlException])

    /** Deletes `name`. Without `force` the engine refuses a branch holding commits that are not merged anywhere. */
    def deleteBranch(name: String, force: Boolean = false)(using Frame): Unit < (Async & Abort[SqlException])

    /** Runs `body` against `revision`, which may be a branch, a tag, or a commit hash.
      *
      * The scoped form is the only form: under a pool a free-standing `checkout` has no well-defined extent, since the next statement may
      * run on a different connection. The revision is carried per FIBER and each connection moves itself onto it before running anything,
      * so two fibers can work on two branches through one pool at once. Nesting works and the innermost wins.
      */
    def onBranch[A, S](revision: String)(body: A < S)(using Frame): A < S =
        DoltBranchScope.let(revision)(body)

    // --- Integration ---

    /** Merges `from` into the current branch and answers what happened. A [[kyo.DoltMerge.Conflicted]] result leaves the conflicts in the
      * branch's working set: resolve them with [[resolveConflicts]] and [[commit]], or discard them with a hard [[reset]].
      */
    def merge(from: DoltRef)(using Frame): DoltMerge < (Async & Abort[SqlException])

    /** Which tables hold merge conflicts, and how many rows each. The per-row detail is in `dolt_conflicts_<table>`. */
    def conflicts(using Frame): Chunk[DoltConflictSummary] < (Async & Abort[SqlException])

    /** Which tables have a SHAPE conflict, which cannot be resolved by choosing a side row by row. */
    def schemaConflicts(using Frame): Chunk[DoltSchemaConflict] < (Async & Abort[SqlException])

    /** Resolves every conflict in `table` by keeping one side wholesale, and answers how many rows that cleared. */
    def resolveConflicts(table: String, keeping: DoltResolution)(using Frame): Long < (Async & Abort[SqlException])

    /** Moves the current branch to `to`, rewinding as much as `mode` says. [[kyo.DoltResetMode.Hard]] discards uncommitted work. */
    def reset(to: DoltRef, mode: DoltResetMode = DoltResetMode.Mixed)(using Frame): Unit < (Async & Abort[SqlException])

    /** Creates a commit that undoes `ref`, leaving `ref` in the history. */
    def revert(ref: DoltRef)(using Frame): DoltCommit < (Async & Abort[SqlException])

    /** Applies the change `ref` introduced onto the current branch as a new commit. */
    def cherryPick(ref: DoltRef)(using Frame): DoltCommit < (Async & Abort[SqlException])

    /** Names `ref` permanently and answers the tag. A tag does not move, which is what separates it from a branch. */
    def tag(name: String, ref: DoltRef = DoltRef.Head, message: Maybe[String] = Absent)(using
        Frame
    ): DoltTag < (Async & Abort[SqlException])

    /** Every tag in this database. */
    def tags(using Frame): Chunk[DoltTag] < (Async & Abort[SqlException])

    /** Deletes a tag by name. */
    def deleteTag(name: String)(using Frame): Unit < (Async & Abort[SqlException])

    // --- Remotes ---

    /** Every configured remote. */
    def remotes(using Frame): Chunk[DoltRemote] < (Async & Abort[SqlException])

    /** Configures `name` as a remote at `url` and answers it. For DoltHub the url is an `owner/database` name rather than a URL. */
    def addRemote(name: String, url: String)(using Frame): DoltRemote < (Async & Abort[SqlException])

    /** Removes a remote's configuration. The commits it fetched stay. */
    def removeRemote(name: String)(using Frame): Unit < (Async & Abort[SqlException])

    /** Sends `branch` to `remote`. Without `force` the engine refuses a push that would drop commits the remote has. */
    def push(remote: String, branch: String, force: Boolean = false)(using Frame): Unit < (Async & Abort[SqlException])

    /** Downloads commits from `remote` without touching any branch, so nothing a caller is working on moves. */
    def fetch(remote: String, branch: Maybe[String] = Absent)(using Frame): Unit < (Async & Abort[SqlException])

    /** Fetches from `remote` and merges into the current branch, answering the merge the way [[merge]] does. */
    def pull(remote: String, branch: Maybe[String] = Absent)(using Frame): DoltMerge < (Async & Abort[SqlException])

end DoltClient

/** Narrows the ambient client to a versioned one.
  *
  * No `init` family and no `register`: those name a specific engine, so each backend carries its own.
  */
object DoltClient:

    /** Runs `f` with the ambient client narrowed to a versioned engine.
      *
      * A `DB` installed with a client that has no history fails with a typed mismatch rather than at the first version-control call, and no
      * ambient `DB` at all is a compile error.
      */
    def use[A, S](f: DoltClient => A < S)(using Frame): A < (S & Abort[SqlException] & DB) =
        DB.clientAs[DoltClient].map(f)

end DoltClient
