package kyo

/** Raised by [[FileSystem.StagedChanges.commit]] when commit validation detects that one or more
  * lower-layer paths have diverged from the entries the staging backend recorded at observation
  * time.
  *
  * Carries every [[Conflict]] as a [[Chunk]] so the caller can inspect each diverging path,
  * compare the ancestor entry against the live lower view, and decide how to proceed. Callers
  * that want to resolve conflicts rather than abort should use [[FileSystem.StagedChanges.commitWith]]
  * instead, which applies a per-conflict resolution function and never raises `CommitConflict`.
  *
  * `CommitConflict` extends `KyoException`; it surfaces through `Abort[CommitConflict]` and is
  * therefore typed and catchable at the call site, not an unchecked JVM exception.
  */
class CommitConflict(val conflicts: Chunk[FileSystem.Conflict], message: String = "Staged writes conflict with the live filesystem")(using
    Frame
) extends KyoException(message)

object CommitConflict:
    def apply(conflicts: Chunk[FileSystem.Conflict])(using Frame): CommitConflict = new CommitConflict(conflicts)
    def unapply(value: CommitConflict): Some[Chunk[FileSystem.Conflict]]          = Some(value.conflicts)

/** One read-set divergence detected at commit: what the staging backend observed when it first saw
  * the lower path (`ancestor`), its staged view (`ours`), and the live lower entry (`theirs`).
  *
  * `ancestor` is a [[Conflict.Ancestor]] rather than a full entry because the backend records what
  * each read actually observed. A caller that only asked whether a path exists never read its
  * contents, so there are no observed contents to report, and handing back an empty entry would
  * present a byte array the backend never saw as though it had. `ours` and `theirs` do carry full
  * entries: the staged upper entry, and a fresh read of the live lower path.
  *
  * Inspect each field to choose a [[Resolution]] in [[FileSystem.StagedChanges.commitWith]], or let
  * [[FileSystem.StagedChanges.commit]] abort with [[CommitConflict]] when any divergence is unacceptable.
  */
final case class Conflict(
    path: Path,
    ancestor: Conflict.Ancestor,
    ours: Maybe[Path.Entry],
    theirs: Maybe[Path.Entry]
) derives CanEqual

object Conflict:

    /** The base view a conflict diverged from, at the strength the staging backend observed it.
      *
      * `Unobserved` and `Missing` used to be indistinguishable, both reported as an absent entry, so
      * a caller resolving a conflict could not tell "the backend never looked at this path" from
      * "the backend saw that it was not there". Those call for different resolutions.
      */
    enum Ancestor derives CanEqual:
        /** The backend never read this path. */
        case Unobserved

        /** The backend saw that the path did not exist. */
        case Missing

        /** The backend saw that the path was there and of this kind, and nothing more: not its
          * size, not its timestamp, not its contents.
          */
        case Presence(isDirectory: Boolean)

        /** The backend listed this directory and saw these child names. */
        case DirectoryListing(children: Set[String])

        /** The backend read this stat and did not read the contents. */
        case Metadata(stat: Path.PathStat, isDirectory: Boolean)

        /** The backend read this entry in full. */
        case Content(entry: Path.Entry)
    end Ancestor
end Conflict

/** A per-conflict resolution returned by the caller-supplied function in
  * [[FileSystem.StagedChanges.commitWith]], applied to each [[Conflict]] the commit validation detects.
  *
  * Four cases:
  *   - `KeepOurs`: replay the staged entry, discarding the live lower value.
  *   - `KeepTheirs`: skip this path in the replay, keeping the live lower value unchanged.
  *   - `Write(entry)`: replace both the staged and live value with the supplied [[Path.Entry]].
  *   - `Remove`: delete the path in the live lower service during replay.
  *
  * The resolution is applied path-by-path; non-conflicting staged entries are always replayed
  * regardless of the resolution chosen for conflicting ones.
  */
enum Resolution derives CanEqual:
    case KeepOurs
    case KeepTheirs
    case Write(entry: Path.Entry)
    case Remove
end Resolution
