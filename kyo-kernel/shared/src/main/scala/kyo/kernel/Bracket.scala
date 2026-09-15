package kyo.kernel

import java.util.concurrent.atomic.AtomicBoolean
import kyo.Closed
import kyo.Frame
import kyo.IsFatal
import kyo.Maybe
import kyo.Tag
import kyo.kernel.internal.*

/** Binds a resource for the extent of a use and guarantees its release runs, whichever way the extent ends.
  *
  * `Bracket(acquire)(use)(release)` runs `use` on the acquired resource under a region that owns the release, and runs `release` exactly
  * once: when `use` completes, throws, or a parked remainder holding the region is abandoned. The release is told how the extent ended
  * (`Absent` for a clean end, otherwise the failure the unwind carried), not what the use produced; it takes no effects and its result is
  * discarded, since it runs where nothing is installed to answer for it.
  *
  * A bracket closes with the scope that installed it. An isolated child (a spawned fiber included) gets an inert copy of the region that
  * neither releases nor completes; a bracket inside a remainder a handler hands out as a value travels with it, releasing once where the
  * holder ends, each resumption running against the live resource.
  */
object Bracket:

    // The region a bracket runs its use body under; `Cell` is its state and the exactly-once release guard.
    sealed private[kyo] trait Finalize extends ContextEffect[Cell]

    // The exactly-once release guard, in two shapes rather than one with a flag: a bracket's own, and the inert one
    // handed to an isolated child (a recording instance would carry one crossing's state into the next).
    sealed abstract private[kyo] class Cell extends AtomicBoolean:
        // Fires the release once, told how the extent ended (an unwind's failure, or a drop's Absent).
        private[kyo] def run(failure: Maybe[Throwable]): Unit
        // Extent ran to a clean end in place: records that (so a later refused re-entry can say which way it fired)
        // and fires the release once, told the clean ending.
        private[kyo] def complete(): Unit
        // Whether the extent ran to an end, versus being released when its owning scope ended without it ever running.
        private[kyo] def endedItsExtent: Boolean
    end Cell

    private[kyo] object Cell:

        // compareAndSet makes the release fire once, whichever ending reaches it first: an unwind, a drop, or the clean end.
        final class Live(fin: Maybe[Throwable] => Unit) extends Cell:
            @volatile private var ended              = false
            private[kyo] def endedItsExtent: Boolean = ended
            private[kyo] def run(failure: Maybe[Throwable]): Unit =
                if compareAndSet(false, true) then fin(failure)
            private[kyo] def complete(): Unit =
                ended = true
                if compareAndSet(false, true) then fin(Maybe.Absent)
        end Live

        // Handed to an isolated child: no release, so one instance serves every crossing.
        val inert: Cell =
            new Cell:
                private[kyo] def run(failure: Maybe[Throwable]): Unit = ()
                private[kyo] def complete(): Unit                     = ()
                private[kyo] def endedItsExtent: Boolean              = false
    end Cell

    /** Acquires a resource, runs `use` on it under a region that owns the release, and releases it exactly once.
      *
      * The release is registered as the acquired value arrives, with nothing schedulable in between, so an interrupt lands on one side of the
      * pair or the other, never between acquiring the resource and owing its release. A throw from `use` unwinds the region, running the
      * release before the failure propagates; a failure from the release itself is attached to it as suppressed.
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
      * [[apply]] cannot install its region until the acquire's value arrives (the release is owed that value), so a computation abandoned
      * before it ran has nothing to release. Here the region is a node from the start, and the abandonment walk finds it whether or not a
      * single step ever ran.
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
            // Extent ran to a clean end in place: fire the release told the clean ending and record which way it fired.
            override def complete(state: Cell): Unit = state.complete()
            // A remainder resumed after its bracket's resource was released is a use-after-release: the cell has fired,
            // so refuse rather than run the body against a released resource. The two ways it gets re-entered want
            // different advice, and guessing wrong sends the reader after the wrong cause.
            override def reenter(state: Cell): Unit =
                if state.get() then
                    val why =
                        if state.endedItsExtent then
                            "Its extent already ran to an end, which is what released it, and this is a later " +
                                "resumption of a continuation that re-enters it. A handler that resumes the same " +
                                "continuation more than once, as Choice does, has that effect whenever the bracket " +
                                "sits between the handler and the suspension it answers: the first resumption ends " +
                                "the extent and releases. Acquire inside the branch, so each resumption gets a " +
                                "resource of its own, or put the bracket outside the handler, so its extent is not " +
                                "what gets replayed."
                        else
                            "It was released when the scope that owned it ended, without its extent ever running to " +
                                "an end. That is what happens to a remainder handed out by a peel, such as " +
                                "Stream.splitAt, Emit.runFirst or Batch.capture, when it is consumed after the " +
                                "computation that peeled it has finished, on another fiber included: that scope " +
                                "cannot tell a remainder nobody will resume from one someone else still intends to " +
                                "resume, so it releases at its own exit. Consume the remainder inside the scope " +
                                "that peeled it, or use the confined form, Stream.splitAtWith, whose callback the " +
                                "remainder cannot escape."
                    throw new Closed("Bracket resource", _frame, why)(using _frame)
                end if
            end reenter
        new Pending.HandleContext[Cell, Finalize, B, S]:
            override def frame = _frame
            def value          = body
            def handler        = h
        end new
    end region
end Bracket
