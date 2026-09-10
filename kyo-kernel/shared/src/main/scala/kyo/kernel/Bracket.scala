package kyo.kernel

import java.util.concurrent.atomic.AtomicBoolean
import kyo.Absent
import kyo.Closed
import kyo.Frame
import kyo.IsFatal
import kyo.Maybe
import kyo.Present
import kyo.Result
import kyo.Tag
import kyo.kernel.internal.*

/** Binds a resource for the extent of a use and guarantees its release runs, whichever way the extent ends.
  *
  * `Bracket(acquire)(use)(release)` evaluates `acquire`, runs `use` on its result under a region that owns the release, and runs
  * `release` exactly once: when `use` completes, when it throws, or when a parked remainder holding the region is abandoned.
  *
  * The release is told how the extent ended (`Absent` for a clean end, otherwise the failure the unwind carried or the discard
  * signal), not what the use produced: an extent a handler replays ends more than once, with more than one value. A caller needing
  * its own failures, such as `Sync.acquireReleaseWith` reifying an `Abort`, routes them itself. The release takes no effects and
  * its result is discarded: it runs where nothing is installed to answer for it.
  *
  * A bracket closes only with the scope that installed it. An isolated child, a spawned fiber included, gets an inert copy of the
  * region that neither completes, releases, nor refuses. A bracket inside a remainder a handler hands out as a value travels with
  * that remainder: it releases when the remainder completes, or at the exit of the scope enclosing the handler if it is never
  * resumed, and refuses a second resumption.
  */
object Bracket:

    // The region a bracket runs its use body under; `Cell` is its state and the exactly-once release guard.
    sealed private[kyo] trait Finalize extends ContextEffect[Cell]

    // Not parameterised by the use value: the release is told how the extent ended, not what it produced.
    //
    // Two shapes rather than one with a flag: a bracket's own state, and the inert one handed to an isolated
    // child. A recording instance would carry the first crossing's ending into every later one.
    sealed abstract private[kyo] class Cell extends AtomicBoolean:
        private[kyo] def borrow(): Unit
        private[kyo] def isBorrowed: Boolean
        private[kyo] def complete(): Unit
        private[kyo] def drain(ex: Throwable): Unit
        private[kyo] def discharge(ex: Throwable): Unit
        private[kyo] def endedItsExtent: Boolean
    end Cell

    private[kyo] object Cell:

        final class Live(fin: Maybe[Throwable] => Unit) extends Cell:
            // Set when the region is re-installed from a continuation the handler above dumped: the continuation can
            // be resumed again, so a release fired at the first ending would run under the resumptions that follow.
            // While set, an ending only records, and the handler that owes this region fires the release when it ends.
            @volatile private var borrowed = false
            // Whether any ending ran to completion: read by a later discharge and by a refused re-entry.
            @volatile private var ended = false

            private[kyo] def borrow(): Unit      = borrowed = true
            private[kyo] def isBorrowed: Boolean = borrowed

            private[kyo] def complete(): Unit =
                ended = true
                if !borrowed && compareAndSet(false, true) then fin(Absent)

            // The release is owed the failure that unwound its extent, and the fatal keeps propagating. An unwind wins
            // over an ending that already ran, or a release that commits on success would commit over it.
            private[kyo] def drain(ex: Throwable): Unit =
                if compareAndSet(false, true) then fin(Maybe(ex))

            // The owner ended normally, so the extent's own endings are final. Never having ended means the discard
            // signal is what the release is owed.
            private[kyo] def discharge(ex: Throwable): Unit =
                if compareAndSet(false, true) then
                    if ended then fin(Absent)
                    else fin(Maybe(ex))

            private[kyo] def endedItsExtent: Boolean = ended
        end Live

        // Handed to an isolated child: no release, no recorded ending, no refusal, so one instance serves every crossing.
        val inert: Cell =
            new Cell:
                private[kyo] def borrow(): Unit                 = ()
                private[kyo] def isBorrowed: Boolean            = false
                private[kyo] def complete(): Unit               = ()
                private[kyo] def drain(ex: Throwable): Unit     = ()
                private[kyo] def discharge(ex: Throwable): Unit = ()
                private[kyo] def endedItsExtent: Boolean        = false
    end Cell

    def apply[A, S1](acquire: A < S1)[B, S2](use: A => B < S2)(
        release: (A, Maybe[Throwable]) => Unit
    )(using _frame: Frame): B < (S1 & S2) =
        val ensure = new Arrow.Ensure[A, B, S1 & S2]:
            def frame = _frame
            override def apply(a: A) =
                val cell = new Cell.Live(outcome => release(a, outcome))
                val body =
                    try use(a)
                    catch
                        case ex =>
                            try cell.drain(ex)
                            catch case t if !IsFatal(t) && (t ne ex) => ex.addSuppressed(t)
                            throw ex
                region(cell, body)
            end apply
        Effect.defer(acquire).chain(ensure)
    end apply

    /** Runs `release` when `body`'s extent ends, with nothing to acquire first.
      *
      * [[apply]] cannot install its region until the acquire's value arrives, because the release is owed that
      * value, so a computation abandoned before it ever ran has no region and nothing to release. Here there is
      * nothing to wait for: the region is a node from the start, and the abandonment walk finds it whether or not
      * a single step ever ran.
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
            def tag                                                             = Tag[Finalize]
            def derive(outer: Maybe[Cell])                                      = cell
            def fork(parent: Cell)                                              = Cell.inert
            def join(parent: Cell, fk: Cell, child: Cell)                       = parent
            override private[kyo] def borrow(state: Cell): Unit                 = state.borrow()
            override private[kyo] def defers(state: Cell): Boolean              = state.isBorrowed
            override private[kyo] def done(state: Cell): Unit                   = state.complete()
            override private[kyo] def release(state: Cell, ex: Throwable): Unit = state.drain(ex)
            override private[kyo] def discharge(state: Cell, ex: Throwable): Unit =
                state.discharge(ex)
            override private[kyo] def reenter(state: Cell): Unit =
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
