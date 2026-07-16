package kyo

/** A [[PathService]] extension whose writes stage locally until an explicit commit validates
  * them against the underlying live service. [[PathService.overlay]] is the built-in
  * manual-commit factory; its [[PathService.disposition]] is `Disposition.ManualCommit`.
  *
  * Three commit strategies are available:
  *   - [[commit]]: validates the read-set against the live lower; aborts `CommitConflict` if
  *     any observed entry has diverged, leaving the lower untouched.
  *   - [[commitOverwrite]]: replays unconditionally (last-writer-wins) with no conflict check.
  *   - [[commitWith]]: validates, then calls a caller-supplied `resolve` function for each
  *     conflict to obtain a [[Resolution]] before replaying the resolved entries.
  *
  * [[rollback]] discards all staged writes without touching the lower service.
  *
  * @tparam S the lower service's own effect, propagated to each commit operation's residual
  */
trait CommitHandle[S] extends PathService[S]:
    /** Validates the read-set against the live lower; replays if every observed entry
      * matches, else aborts `CommitConflict` and leaves the lower service untouched.
      */
    def commit(using Frame): Unit < (S & Abort[FileException] & Abort[CommitConflict])

    /** Replays every staged write unconditionally (last-writer-wins). No `CommitConflict` in
      * the row.
      */
    def commitOverwrite(using Frame): Unit < (S & Abort[FileException])

    /** Validates, then resolves each [[Conflict]] through `resolve` before replaying. */
    def commitWith[S2](resolve: Conflict => Resolution < S2)(using Frame): Unit < (S & Abort[FileException] & S2)

    /** Discards all staged writes without touching the lower service. */
    def rollback(using Frame): Unit < S
end CommitHandle
