package kyo

/** Raised by [[FileSystem.CommitHandle.commit]] when the commit validation detects that one or more
  * lower-layer paths have diverged from the entries the overlay recorded at observation time.
  *
  * Carries every [[Conflict]] as a [[Chunk]] so the caller can inspect each diverging path,
  * compare the ancestor entry against the live lower view, and decide how to proceed. Callers
  * that want to resolve conflicts rather than abort should use [[FileSystem.CommitHandle.commitWith]]
  * instead, which applies a per-conflict resolution function and never raises `CommitConflict`.
  *
  * `CommitConflict` extends `KyoException`; it surfaces through `Abort[CommitConflict]` and is
  * therefore typed and catchable at the call site, not an unchecked JVM exception.
  */
final case class CommitConflict(conflicts: Chunk[Conflict])(using Frame) extends KyoException

/** One read-set divergence detected at commit: the base entry the overlay recorded when it first saw
  * the lower entry (`ancestor`), the overlay's staged view (`ours`), and the live lower entry (`theirs`).
  *
  * `ancestor` is a `Maybe[Path.Entry]`: the read-set records the full observed entry at observation
  * (bytes and stat for a regular file, stat for a directory), so the base view is available at commit
  * without re-reading the lower path; `ours` and `theirs` likewise carry full entries (the staged upper
  * entry, and a fresh read of the live lower path). `ancestor` is `Absent` only when the conflicting
  * path was never observed.
  *
  * Inspect each field to choose a [[Resolution]] in [[FileSystem.CommitHandle.commitWith]], or let
  * [[Path.transaction]] abort with [[CommitConflict]] when any divergence is unacceptable.
  */
final case class Conflict(
    path: Path,
    ancestor: Maybe[Path.Entry],
    ours: Maybe[Path.Entry],
    theirs: Maybe[Path.Entry]
) derives CanEqual

/** A per-conflict resolution returned by the caller-supplied function in
  * [[FileSystem.CommitHandle.commitWith]], applied to each [[Conflict]] the commit validation detects.
  *
  * Four cases:
  *   - `KeepOurs`: replay the overlay's staged entry, discarding the live lower value.
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
