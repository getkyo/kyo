package kyo.kernel

import java.util.concurrent.atomic.AtomicBoolean
import kyo.Frame
import kyo.IsFatal
import kyo.Maybe
import kyo.Tag
import kyo.kernel.internal.*

/** Binds a resource for the extent of a use and guarantees its release runs, whichever way the extent ends.
  *
  * `Bracket(acquire)(use)(release)` evaluates `acquire`, runs `use` on its result under a region that owns the release, and runs
  * `release` exactly once: when `use` completes, when it throws, or when a parked remainder holding the region is abandoned.
  *
  * The release is told how the extent ended (`Absent` for a clean end, otherwise the failure the unwind carried), not what the use
  * produced. The release takes no effects and its result is discarded: it runs where nothing is installed to answer for it.
  *
  * A bracket closes with the scope that installed it. An isolated child, a spawned fiber included, gets an inert copy of the region that
  * neither releases nor completes. A bracket inside a remainder a handler hands out as a value travels with that remainder: it releases
  * once, where the holder ends, and each resumption of the remainder runs against the live resource.
  */
object Bracket:

    // The region a bracket runs its use body under; `Cell` is its state and the exactly-once release guard.
    sealed private[kyo] trait Finalize extends ContextEffect[Cell]

    // The exactly-once release guard. Two shapes rather than one with a flag: a bracket's own, and the inert one
    // handed to an isolated child, which a recording instance could not serve without carrying one crossing into
    // the next.
    sealed abstract private[kyo] class Cell extends AtomicBoolean:
        private[kyo] def run(failure: Maybe[Throwable]): Unit

    private[kyo] object Cell:

        // The release runs once, told how the extent ended; the compareAndSet is what makes it once whichever
        // ending reaches it first, an unwind or the clean end.
        final class Live(fin: Maybe[Throwable] => Unit) extends Cell:
            private[kyo] def run(failure: Maybe[Throwable]): Unit =
                if compareAndSet(false, true) then fin(failure)

        // Handed to an isolated child: no release, so one instance serves every crossing.
        val inert: Cell =
            new Cell:
                private[kyo] def run(failure: Maybe[Throwable]): Unit = ()
    end Cell

    /** Acquires a resource, runs `use` on it under a region that owns the release, and releases it exactly once.
      *
      * The release is registered as the acquired value arrives, with nothing schedulable in between, so an interrupt lands on one side of
      * the pair or the other and never between acquiring the resource and owing its release.
      *
      * A throw from `use` unwinds the region, which runs the release before the failure propagates; a failure from the release itself is
      * attached to it as suppressed.
      *
      * @param acquire
      *   Produces the resource, evaluated when the computation runs
      * @param use
      *   The extent the resource is held for
      * @param release
      *   Runs once when that extent ends, told how it ended rather than what `use` produced
      */
    def apply[A, S1](acquire: A < S1)[B, S2](use: A => B < S2)(
        release: (A, Maybe[Throwable]) => Unit
    )(using _frame: Frame): B < (S1 & S2) =
        val ensure = new Arrow.Ensure[A, B, S1 & S2]:
            def frame = _frame
            override def apply(a: A) =
                val cell = new Cell.Live(outcome => release(a, outcome))
                val body =
                    // A throw while building the use body happens before the region is installed, so the unwind
                    // would not reach the release; run it here and re-raise.
                    try use(a)
                    catch
                        case ex =>
                            try cell.run(Maybe(ex))
                            catch case t if !IsFatal(t) && (t ne ex) => ex.addSuppressed(t)
                            throw ex
                region(cell, body)
            end apply
        ensure(Effect.defer(acquire))
    end apply

    /** Runs `release` when `body`'s extent ends, with nothing to acquire first.
      *
      * [[apply]] cannot install its region until the acquire's value arrives, because the release is owed that value, so a computation
      * abandoned before it ever ran has no region and nothing to release. Here there is nothing to wait for: the region is a node from the
      * start, and the abandonment walk finds it whether or not a single step ever ran.
      */
    def ensuring[B, S](release: Maybe[Throwable] => Unit)(body: => B < S)(using _frame: Frame): B < S =
        // A throw while the body is being built is re-raised as the region's own body, so it unwinds with the region
        // installed and fires the release.
        val b =
            try body
            catch case ex => Effect.defer(throw ex)
        region(new Cell.Live(release), b)
    end ensuring

    private def region[B, S](cell: => Cell, body: B < S)(using _frame: Frame): B < S =
        val h = new Handler.ContextHandler[Cell, Finalize, B, S]:
            def tag                                             = Tag[Finalize]
            def derive(outer: Maybe[Cell])                      = cell
            def fork(parent: Cell)                              = Cell.inert
            def join(parent: Cell, fk: Cell, child: Cell)       = parent
            def release(state: Cell, failure: Maybe[Throwable]) = state.run(failure)
        new Pending.HandleContext[Cell, Finalize, B, S]:
            override def frame = _frame
            def value          = body
            def handler        = h
        end new
    end region
end Bracket
