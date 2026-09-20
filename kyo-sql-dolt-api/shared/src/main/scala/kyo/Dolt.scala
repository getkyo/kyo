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
  *
  * The records these operations answer with live on the companion, so they read as this type's vocabulary: [[Dolt.Commit]], [[Dolt.Ref]],
  * [[Dolt.Merge]] and the rest.
  */
abstract class Dolt private[kyo] (runtime: Runtime[?]) extends SqlClient(runtime):

    // --- History ---

    /** Stages the named tables, so a later `commit(all = false)` captures those and leaves the rest of the working set alone.
      *
      * Naming no table stages every changed one, which is `git add -A`. A table written again after being staged appears twice in
      * [[status]], once staged and once not, and only the staged half is what that form of [[commit]] takes.
      */
    def add(tables: String*)(using Frame): Unit < (Async & Abort[SqlException])

    /** Turns the branch's working set into a commit, and answers the whole commit rather than only its hash.
      *
      * `all` stages every changed table first, which is `git commit -a`. Passing false commits only what [[add]] staged. Committing nothing
      * is an error rather than a silent no-op.
      */
    def commit(message: String, all: Boolean = true)(using Frame): Dolt.Commit < (Async & Abort[SqlException])

    /** The commits reachable from `ref`, newest first. */
    def log(ref: Dolt.Ref = Dolt.Ref.Head, limit: Maybe[Int] = Absent)(using
        Frame
    ): Chunk[Dolt.Commit] < (Async & Abort[SqlException])

    /** Which tables hold uncommitted changes, and whether each change is staged. */
    def status(using Frame): Chunk[Dolt.Change] < (Async & Abort[SqlException])

    /** How one table differs between two refs, a row per changed row. */
    def diff(from: Dolt.Ref, to: Dolt.Ref, table: String)(using Frame): Chunk[Dolt.Diff] < (Async & Abort[SqlException])

    // --- Branches ---

    /** Every branch in this database. */
    def branches(using Frame): Chunk[Dolt.Branch] < (Async & Abort[SqlException])

    /** Creates `name` pointing at `from` and answers it, leaving the session where it is. [[onBranch]] is what moves a session. */
    def createBranch(name: String, from: Dolt.Ref = Dolt.Ref.Head)(using Frame): Dolt.Branch < (Async & Abort[SqlException])

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

    /** Merges `from` into the current branch and answers what happened. A [[Dolt.Merge.Conflicted]] result leaves the conflicts in the
      * branch's working set: resolve them with [[resolveConflicts]] and [[commit]], or discard them with a hard [[reset]].
      */
    def merge(from: Dolt.Ref)(using Frame): Dolt.Merge < (Async & Abort[SqlException])

    /** Which tables hold merge conflicts, and how many rows each. The per-row detail is in `dolt_conflicts_<table>`. */
    def conflicts(using Frame): Chunk[Dolt.ConflictSummary] < (Async & Abort[SqlException])

    /** Which tables have a SHAPE conflict, which cannot be resolved by choosing a side row by row. */
    def schemaConflicts(using Frame): Chunk[Dolt.SchemaConflict] < (Async & Abort[SqlException])

    /** Resolves every conflict in `table` by keeping one side wholesale, and answers how many rows that cleared. */
    def resolveConflicts(table: String, keeping: Dolt.Resolution)(using Frame): Long < (Async & Abort[SqlException])

    /** Moves the current branch to `to`, rewinding as much as `mode` says. [[Dolt.ResetMode.Hard]] discards uncommitted work. */
    def reset(to: Dolt.Ref, mode: Dolt.ResetMode = Dolt.ResetMode.Mixed)(using Frame): Unit < (Async & Abort[SqlException])

    /** Creates a commit that undoes `ref`, leaving `ref` in the history. */
    def revert(ref: Dolt.Ref)(using Frame): Dolt.Commit < (Async & Abort[SqlException])

    /** Applies the change `ref` introduced onto the current branch as a new commit. */
    def cherryPick(ref: Dolt.Ref)(using Frame): Dolt.Commit < (Async & Abort[SqlException])

    /** Names `ref` permanently and answers the tag. A tag does not move, which is what separates it from a branch. */
    def tag(name: String, ref: Dolt.Ref = Dolt.Ref.Head, message: Maybe[String] = Absent)(using
        Frame
    ): Dolt.Tag < (Async & Abort[SqlException])

    /** Every tag in this database. */
    def tags(using Frame): Chunk[Dolt.Tag] < (Async & Abort[SqlException])

    /** Deletes a tag by name. */
    def deleteTag(name: String)(using Frame): Unit < (Async & Abort[SqlException])

    // --- Remotes ---

    /** Every configured remote. */
    def remotes(using Frame): Chunk[Dolt.Remote] < (Async & Abort[SqlException])

    /** Configures `name` as a remote at `url` and answers it. For DoltHub the url is an `owner/database` name rather than a URL. */
    def addRemote(name: String, url: String)(using Frame): Dolt.Remote < (Async & Abort[SqlException])

    /** Removes a remote's configuration. The commits it fetched stay. */
    def removeRemote(name: String)(using Frame): Unit < (Async & Abort[SqlException])

    /** Sends `branch` to `remote`. Without `force` the engine refuses a push that would drop commits the remote has. */
    def push(remote: String, branch: String, force: Boolean = false)(using Frame): Unit < (Async & Abort[SqlException])

    /** Downloads commits from `remote` without touching any branch, so nothing a caller is working on moves. */
    def fetch(remote: String, branch: Maybe[String] = Absent)(using Frame): Unit < (Async & Abort[SqlException])

    /** Fetches from `remote` and merges into the current branch, answering the merge the way [[merge]] does. */
    def pull(remote: String, branch: Maybe[String] = Absent)(using Frame): Dolt.Merge < (Async & Abort[SqlException])

end Dolt

/** Narrows the ambient client to a versioned one, and holds the vocabulary its operations answer with.
  *
  * No `init` family and no `register`: those name a specific engine, so each backend carries its own (`DoltServer`, `DoltLite`).
  */
object Dolt:

    /** Runs `f` with the ambient client narrowed to a versioned engine.
      *
      * A `DB` installed with a client that has no history fails with a typed mismatch rather than at the first version-control call, and no
      * ambient `DB` at all is a compile error.
      */
    def use[A, S](f: Dolt => A < S)(using Frame): A < (S & Abort[SqlException] & DB) =
        DB.clientAs[Dolt].map(f)

    /** A Dolt commit hash, which is a 32-character base-32 string rather than the hex a git user expects.
      *
      * Opaque over `String` so it cannot be confused with a branch name, a tag, or a message, and widens back to `String` where text is
      * wanted.
      */
    opaque type CommitHash <: String = String

    object CommitHash:

        /** Wraps text the server produced. Unvalidated: a hash the server does not recognise fails at the operation that uses it. */
        def apply(value: String): CommitHash = value

    end CommitHash

    /** A point in a database's history, in the vocabulary Dolt itself accepts.
      *
      * Every version-control operation takes one of these rather than a string, because a branch name and a tag name occupy one namespace
      * on the server and an ancestor is an expression over another ref rather than a name at all. [[Ref.render]] is the only place a ref
      * becomes text.
      */
    enum Ref derives CanEqual:

        /** Wherever the session is pointed now, which is the branch in scope rather than a fixed commit. */
        case Head

        case Branch(name: String)
        case Tag(name: String)
        case Commit(hash: CommitHash)

        /** `generations` commits back along the FIRST parent, which is what Dolt spells with a tilde. At a merge commit that follows the
          * branch that was merged INTO, so `Ancestor(Branch("main"), 1)` is main before the merge.
          */
        case Ancestor(of: Ref, generations: Int)

        /** The text Dolt reads for this ref. `Head` renders as `HEAD`, which resolves against the session's current branch, so a ref built
          * once and used from two branches names two different commits; an operation that must be stable takes a [[Ref.Commit]].
          */
        def render: String =
            // Qualified, because three of these cases share a name with a record on the enclosing object:
            // `Ref.Branch` names a branch, `Branch` IS one.
            this match
                case Ref.Head              => "HEAD"
                case Ref.Branch(name)      => name
                case Ref.Tag(name)         => name
                case Ref.Commit(hash)      => hash
                case Ref.Ancestor(of, gen) => if gen <= 0 then of.render else s"${of.render}~$gen"

    end Ref

    /** One commit in a database's history.
      *
      * Shared by every versioned backend, so the fields an engine may not record are [[kyo.Maybe]]: the embedded engine keeps no author
      * apart from the committer, signs nothing, and keeps no total ordering. [[Commit.parents]] and [[Commit.refs]] are always answerable,
      * so an empty `parents` means the initial commit and nothing else.
      */
    final case class Commit(
        hash: CommitHash,
        message: String,
        /** Who wrote the change, where the engine records that apart from who committed it. */
        author: Maybe[String],
        authorEmail: Maybe[String],
        authorDate: Maybe[Instant],
        committer: String,
        committerEmail: String,
        date: Instant,
        /** Empty only at the initial commit, and holding two entries at a merge. */
        parents: Chunk[CommitHash],
        /** Branches and tags pointing at this commit, empty when nothing names it. */
        refs: Chunk[String],
        /** The GPG signature, absent on an unsigned commit and on an engine with no signing. */
        signature: Maybe[String],
        /** The engine's own total ordering over the commit graph, absent where it keeps none. */
        order: Maybe[Long]
    ) derives CanEqual:

        /** Whether this commit joined two histories. */
        def isMerge: Boolean = parents.size > 1

        /** Whether this is the start of history. */
        def isRoot: Boolean = parents.isEmpty

    end Commit

    /** One branch, carrying every column `dolt_branches` reports.
      *
      * `dirty` says the branch's working set holds changes that are in no commit yet. A working set belongs to the branch rather than to a
      * connection, so those changes are visible to every session on that branch. The `latest*` fields describe the commit the branch points
      * at, and are absent for a branch created at an empty database.
      */
    final case class Branch(
        name: String,
        hash: CommitHash,
        dirty: Boolean,
        latestMessage: Maybe[String],
        latestCommitter: Maybe[String],
        latestCommitterEmail: Maybe[String],
        latestCommitDate: Maybe[Instant],
        latestAuthor: Maybe[String],
        latestAuthorEmail: Maybe[String],
        latestAuthorDate: Maybe[Instant],
        remote: Maybe[String],
        remoteBranch: Maybe[String]
    ) derives CanEqual:

        /** Whether this branch tracks a branch on a remote. */
        def isTracking: Boolean = remote.isDefined

    end Branch

    /** One tag, as `dolt_tags` reports it.
      *
      * A tag names a commit permanently where a branch name moves, though the two share one namespace on the server.
      */
    final case class Tag(
        name: String,
        hash: CommitHash,
        tagger: String,
        email: String,
        date: Instant,
        message: Maybe[String]
    ) derives CanEqual

    /** One configured remote, carrying every column `dolt_remotes` reports.
      *
      * `url` is whatever was configured, which for DoltHub is an `owner/database` name rather than anything resembling a URL. `fetchSpecs`
      * and `params` are the refspec list and the driver parameters, both stored as JSON by the server and handed back as their text.
      */
    final case class Remote(
        name: String,
        url: String,
        fetchSpecs: Chunk[String],
        params: Maybe[String]
    ) derives CanEqual

    /** One table with uncommitted changes, as `dolt_status` reports it.
      *
      * A row per table and staging flag rather than per table: a table appears twice when part of its changes were staged and more were
      * made after. `state` is the server's own word (`modified`, `new table`) carried through as text rather than mapped to an enum,
      * because Dolt adds states and an unmatched enum case would be a decode failure.
      */
    final case class Change(table: String, staged: Boolean, state: String) derives CanEqual

    /** One changed row, with the commits it changed between.
      *
      * The table's own columns arrive twice, prefixed `from_` and `to_`, and stay in [[Diff.row]] for the caller to decode against their own
      * type, because their shape is the shape of the table being diffed. [[Diff.fromCommit]] and [[Diff.toCommit]] echo the refs the diff
      * was ASKED for rather than resolving them, so a diff taken between `HEAD~1` and `HEAD` reports those spellings; the dates pin the
      * actual commits.
      */
    final case class Diff(
        kind: Diff.Kind,
        fromCommit: String,
        fromCommitDate: Maybe[Instant],
        toCommit: String,
        toCommitDate: Maybe[Instant],
        row: SqlRow
    )

    object Diff:

        /** What happened to one row between the two commits. */
        enum Kind derives CanEqual:
            case Added, Modified, Removed

            /** The server's own word for this kind. */
            def label: String =
                this match
                    case Added    => "added"
                    case Modified => "modified"
                    case Removed  => "removed"
        end Kind

        object Kind:
            /** Reads the server's `diff_type` column, which has exactly these three values. */
            def parse(value: String): Maybe[Kind] =
                value match
                    case "added"    => Present(Kind.Added)
                    case "modified" => Present(Kind.Modified)
                    case "removed"  => Present(Kind.Removed)
                    case _          => Absent
        end Kind

    end Diff

    /** What a merge did, with the server's own account of it.
      *
      * A sum type rather than a failure, because a conflict is an ordinary outcome of merging. The engine forces that as much as taste
      * does: measured against a 2.3.4 server, a conflicting merge under autocommit raises an error and rolls back, while the same merge
      * inside an explicit transaction returns a row saying `conflicts found`. So [[Dolt.merge]] runs in a transaction and hands this back.
      */
    enum Merge derives CanEqual:

        /** The target already contained every commit being merged, so nothing moved and no commit was created. */
        case UpToDate(message: String)

        /** The target had no commits of its own since the fork, so its pointer moved forward and no merge commit was created. */
        case FastForward(commit: Commit, message: String)

        /** Both sides had commits and they combined cleanly, producing a merge commit with two parents. */
        case Merged(commit: Commit, message: String)

        /** Both sides changed the same rows or the same schema. Nothing is committed and the conflicts sit in the working set.
          *
          * `data` carries the per-table row counts and `schema` the tables whose SHAPE conflicts, both read inside the same transaction. A
          * schema conflict cannot be resolved by choosing a side row by row, which is why it is a separate field rather than another count.
          */
        case Conflicted(data: Chunk[ConflictSummary], schema: Chunk[SchemaConflict], message: String)

        /** The server's own summary of what happened, whichever case this is. Named apart from the `message` each case carries because an
          * enum cannot expose one accessor over case fields of that same name.
          */
        def summary: String =
            this match
                case UpToDate(m)         => m
                case FastForward(_, m)   => m
                case Merged(_, m)        => m
                case Conflicted(_, _, m) => m

        /** Whether this merge left work for the caller to resolve. */
        def isConflicted: Boolean =
            this match
                case _: Conflicted => true
                case _             => false

        /** The commit this merge produced, absent when it produced none. Named apart from the `commit` two cases carry, for the reason
          * [[Merge.summary]] is.
          */
        def resultingCommit: Maybe[Commit] =
            this match
                case FastForward(c, _) => Present(c)
                case Merged(c, _)      => Present(c)
                case _                 => Absent

        /** How many rows are in conflict across every table, zero on a clean merge. */
        def conflictCount: Long =
            this match
                case Conflicted(data, _, _) => data.map(_.count).sum
                case _                      => 0L

    end Merge

    /** How many rows of one table are in conflict, as `dolt_conflicts` reports it.
      *
      * A count rather than the rows: they live in `dolt_conflicts_<table>`, whose columns are the conflicted table's own prefixed `base_`,
      * `our_` and `their_`, so their shape differs per table and cannot be one type here.
      */
    final case class ConflictSummary(table: String, count: Long) derives CanEqual:

        /** The table holding this conflict's rows. */
        def conflictTable: String = s"dolt_conflicts_$table"

    end ConflictSummary

    /** One table whose SHAPE conflicts, as `dolt_schema_conflicts` reports it.
      *
      * Choosing `ours` or `theirs` row by row means nothing when the two sides disagree about what columns the table has, so this carries
      * the three schemas involved as their `CREATE TABLE` text and leaves the resolution to a caller who can read them.
      */
    final case class SchemaConflict(
        table: String,
        baseSchema: Maybe[String],
        ourSchema: Maybe[String],
        theirSchema: Maybe[String],
        description: Maybe[String]
    ) derives CanEqual

    /** Which side of a conflict to keep when resolving a table wholesale.
      *
      * Wholesale is the only form offered. Keeping some rows from each side is an ordinary UPDATE against the conflict table followed by
      * resolving what remains.
      */
    enum Resolution derives CanEqual:

        /** Keep the branch being merged INTO, discarding the incoming change for every conflicted row. */
        case Ours

        /** Keep the branch being merged FROM, discarding the local change for every conflicted row. */
        case Theirs

        /** The word `DOLT_CONFLICTS_RESOLVE` reads for this side. */
        def flag: String =
            this match
                case Ours   => "--ours"
                case Theirs => "--theirs"

    end Resolution

    /** How far a reset reaches, in git's own three-way vocabulary.
      *
      * The distinction is which of the two places a change can sit gets rewound: the staged set that a commit would capture, and the
      * working set the session reads and writes. [[ResetMode.Hard]] is how uncommitted work is lost, so the mode is named at every call
      * rather than defaulted to the destructive one.
      */
    enum ResetMode derives CanEqual:

        /** Moves the branch pointer and unstages everything, leaving the working set alone. Nothing a caller wrote is lost. */
        case Mixed

        /** Moves the branch pointer and nothing else, so staged and working changes both survive. */
        case Soft

        /** Moves the branch pointer and DISCARDS both the staged set and the working set. Uncommitted work is gone. */
        case Hard

        /** The flag `DOLT_RESET` reads for this mode, absent for the one it spells by taking no flag at all. `--mixed` is not a word this
          * server knows, measured: it answers ``unknown option `mixed'``.
          */
        def flag: Maybe[String] =
            this match
                case Mixed => Absent
                case Soft  => Present("--soft")
                case Hard  => Present("--hard")

    end ResetMode

end Dolt
