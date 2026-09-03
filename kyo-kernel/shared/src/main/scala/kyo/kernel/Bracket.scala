package kyo.kernel

import java.util.concurrent.atomic.AtomicBoolean
import kyo.Closed
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.kernel.internal.*
import scala.util.control.NonFatal

/** Binds a resource for the extent of a use and guarantees its release runs, whichever way the extent ends.
  *
  * `Bracket(acquire)(release)(use)` evaluates `acquire`, runs `use` on its result under a region that owns the release, and runs
  * `release` exactly once: when `use` completes, when it throws, or when a parked remainder still holding the region is abandoned.
  * The release is told what it is releasing and how the extent ended, `Absent` on completion and the failure otherwise, which is
  * what lets one commit on success and roll back otherwise.
  *
  * The release takes no effects because it has to be able to run where nothing is installed to answer for it, which is all an
  * interpreter that is ending can offer, and its result is discarded for the same reason.
  *
  * A bracket belongs to the computation that installed it and closes only with its own scope: an isolated child, a spawned fiber
  * included, gets an inert copy of the region that neither completes, releases, nor refuses.
  */
object Bracket:

    // Not on main: the `Finalize` region a bracket runs its use body under, and the `Cell` that is the region's state and the
    // exactly-once guard on the release.
    sealed private[kyo] trait Finalize extends ContextEffect[Cell]

    final private[kyo] class Cell(fin: Maybe[Throwable] => Unit) extends AtomicBoolean:
        private[kyo] def complete(): Unit           = if compareAndSet(false, true) then fin(Maybe.Absent)
        private[kyo] def drain(ex: Throwable): Unit = if compareAndSet(false, true) then fin(Maybe(ex))
    end Cell

    private[kyo] object Cell:
        // the state a bracket hands to an isolated child: nothing completes or drains it, so a copy
        // holding it never runs a release and never refuses a re-entry
        val inert: Cell = new Cell(_ => ())
    end Cell

    def apply[A, S1](acquire: A < S1)(
        release: (A, Maybe[Throwable]) => Unit
    )[B, S2](use: A => B < S2)(using _frame: Frame): B < (S1 & S2) =
        val ensure = new Arrow.Ensure[A, B, S1 & S2]:
            def frame = _frame
            override def apply(a: A) =
                val cell = new Cell(outcome => release(a, outcome))
                val body =
                    try use(a)
                    catch
                        case ex =>
                            try cell.drain(ex)
                            catch case t if NonFatal(t) && (t ne ex) => ex.addSuppressed(t)
                            throw ex
                val h = new Handler.ContextHandler[Cell, Finalize, B, S1 & S2]:
                    def tag                        = Tag[Finalize]
                    def derive(outer: Maybe[Cell]) = cell
                    // the bracket belongs to the computation that installed it and closes only with
                    // its own scope: an isolated child, a spawned fiber included, gets an inert copy
                    def fork(parent: Cell)                                              = Cell.inert
                    def join(parent: Cell, fk: Cell, child: Cell)                       = parent
                    override private[kyo] def done(state: Cell): Unit                   = state.complete()
                    override private[kyo] def release(state: Cell, ex: Throwable): Unit = state.drain(ex)
                    override private[kyo] def reenter(state: Cell): Unit =
                        if state.get() then
                            throw new Closed("Bracket resource", _frame)(using _frame)
                new Pending.HandleContext[Cell, Finalize, B, S1 & S2]:
                    override def frame = _frame
                    def value          = body
                    def handler        = h
                end new
            end apply
        Effect.defer(acquire).chain(ensure)
    end apply
end Bracket
