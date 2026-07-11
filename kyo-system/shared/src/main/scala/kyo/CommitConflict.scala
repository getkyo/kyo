package kyo

/** Raised by [[Path.CommitHandle.commit]] when the read-set no longer matches the live lower service.
  * Carries every conflicting path so a caller can inspect or resolve them.
  */
final case class CommitConflict(conflicts: Chunk[Conflict])(using Frame) extends KyoException

/** One read-set divergence detected at commit: the base observation stamped when the overlay first saw
  * the lower entry (`ancestor`), the overlay's staged view (`ours`), and the live lower entry (`theirs`).
  * `ancestor` is a `Maybe[Path.Stamp]`, not a `Path.Entry`, because the read-set records only a stamp at
  * observation (no bytes, by the overlay's one-stat-per-observation cost model), so the base bytes were
  * never stored; `ours` and `theirs` carry full entries because their bytes exist at commit time (the
  * staged upper entry, and a fresh read of the live lower path). `ancestor` is `Absent` only when the
  * conflicting path was never observed.
  */
final case class Conflict(
    path: Path,
    ancestor: Maybe[Path.Stamp],
    ours: Maybe[Path.Entry],
    theirs: Maybe[Path.Entry]
) derives CanEqual

/** A per-conflict resolution supplied to [[Path.CommitHandle.commitWith]]. */
enum Resolution derives CanEqual:
    case KeepOurs
    case KeepTheirs
    case Write(entry: Path.Entry)
    case Remove
end Resolution
