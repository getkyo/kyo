package kyo.kernel

import java.util.concurrent.atomic.AtomicBoolean
import kyo.Closed
import kyo.Frame
import kyo.Maybe
import kyo.Result
import kyo.Tag
import kyo.kernel.internal.*
import scala.util.control.NonFatal

/** Binds a resource for the extent of a use and guarantees its release runs, whichever way the extent ends.
  *
  * `Bracket(acquire)(use)(release)` evaluates `acquire`, runs `use` on its result under a region that owns the release, and runs
  * `release` exactly once: when `use` completes, when it throws, or when a parked remainder still holding the region is abandoned.
  * The release is told what it is releasing and how the extent ended: the value the use completed with as a `Success`, and otherwise
  * a `Panic` holding the failure the unwind carried through the region, or the signal that the remainder holding it was discarded.
  * That is what lets one commit on success and roll back otherwise.
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
    sealed private[kyo] trait Finalize extends ContextEffect[Cell[Any]]

    // Contravariant in the use value: a cell that accepts any value stands in for one of a narrower value, which is what lets the
    // one inert cell be handed to every fork.
    final private[kyo] class Cell[-B](fin: Result[Nothing, B] => Unit) extends AtomicBoolean:
        private[kyo] def complete(value: B): Unit = if compareAndSet(false, true) then fin(Result.succeed(value))
        // constructed rather than built through `Result.Panic.apply`, which refuses to hold a fatal: the release is owed the
        // failure that unwound its extent whatever it is, and the fatal itself keeps propagating
        private[kyo] def drain(ex: Throwable): Unit = if compareAndSet(false, true) then fin(new Result.Panic(ex))
    end Cell

    private[kyo] object Cell:
        // the state a bracket hands to an isolated child: nothing completes or drains it, so a copy
        // holding it never runs a release and never refuses a re-entry
        val inert: Cell[Any] = new Cell(_ => ())
    end Cell

    def apply[A, S1](acquire: A < S1)[B, S2](use: A => B < S2)(
        release: (A, Result[Nothing, B]) => Unit
    )(using _frame: Frame): B < (S1 & S2) =
        val ensure = new Arrow.Ensure[A, B, S1 & S2]:
            def frame = _frame
            override def apply(a: A) =
                val cell = new Cell[B](outcome => release(a, outcome))
                val body =
                    try use(a)
                    catch
                        case ex =>
                            try cell.drain(ex)
                            catch case t if NonFatal(t) && (t ne ex) => ex.addSuppressed(t)
                            throw ex
                val h = new Handler.ContextHandler[Cell[B], Finalize, B, S1 & S2]:
                    def tag                           = Tag[Finalize]
                    def derive(outer: Maybe[Cell[B]]) = cell
                    // the bracket belongs to the computation that installed it and closes only with
                    // its own scope: an isolated child, a spawned fiber included, gets an inert copy
                    def fork(parent: Cell[B])                                              = Cell.inert
                    def join(parent: Cell[B], fk: Cell[B], child: Cell[B])                 = parent
                    override private[kyo] def done(state: Cell[B], value: B): Unit         = state.complete(value)
                    override private[kyo] def release(state: Cell[B], ex: Throwable): Unit = state.drain(ex)
                    override private[kyo] def reenter(state: Cell[B]): Unit =
                        if state.get() then
                            throw new Closed("Bracket resource", _frame)(using _frame)
                new Pending.HandleContext[Cell[B], Finalize, B, S1 & S2]:
                    override def frame = _frame
                    def value          = body
                    def handler        = h
                end new
            end apply
        Effect.defer(acquire).chain(ensure)
    end apply
end Bracket
