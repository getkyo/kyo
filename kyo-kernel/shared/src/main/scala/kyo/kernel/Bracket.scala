package kyo.kernel

import java.util.concurrent.atomic.AtomicBoolean
import kyo.Absent
import kyo.Closed
import kyo.Frame
import kyo.Maybe
import kyo.Present
import kyo.Result
import kyo.Tag
import kyo.kernel.internal.*
import scala.util.control.NonFatal

/** Binds a resource for the extent of a use and guarantees its release runs, whichever way the extent ends.
  *
  * `Bracket(acquire)(use)(release)` evaluates `acquire`, runs `use` on its result under a region that owns the release, and runs
  * `release` exactly once: when `use` completes, when it throws, or when a parked remainder still holding the region is abandoned.
  * The release is told what it is releasing and how the extent ended: `Absent` when it ran to an end, and otherwise the failure the
  * unwind carried through the region, or the signal that the remainder holding it was discarded. That is what lets one commit on
  * success and roll back otherwise. It is deliberately not told what the use produced: an extent that a handler replays ends more
  * than once, with more than one value, and there is no principled way to choose between them, whereas "did any ending fail" has an
  * answer whatever the number of endings. A caller that needs to interpret its own failures, `Sync.acquireReleaseWith` reifying an
  * `Abort` for instance, knows how to read them and routes them itself.
  *
  * The release takes no effects because it has to be able to run where nothing is installed to answer for it, which is all an
  * interpreter that is ending can offer, and its result is discarded for the same reason.
  *
  * A bracket belongs to the computation that installed it and closes only with its own scope: an isolated child, a spawned fiber
  * included, gets an inert copy of the region that neither completes, releases, nor refuses.
  *
  * A bracket inside a remainder that a handler hands out as a value (the coroutine step the stream combinators are built on) travels
  * with that remainder: it releases when the remainder completes, at the exit of the scope enclosing the handler when the remainder is
  * never resumed, and it refuses a second resumption.
  */
object Bracket:

    // Not on main: the `Finalize` region a bracket runs its use body under, and the `Cell` that is the region's state and the
    // exactly-once guard on the release.
    sealed private[kyo] trait Finalize extends ContextEffect[Cell]

    // The cell is no longer parameterised by the use value: the release is told how the extent ended, not what it
    // produced, so there is nothing about the value left to carry.
    final private[kyo] class Cell(fin: Maybe[Throwable] => Unit) extends AtomicBoolean:
        // Set when the region is re-installed from a continuation the handler above dumped, which is the one situation
        // where the extent ending is not the last word: the same continuation can be resumed again, and a release fired
        // at the first ending would run under the resumptions that follow. While it is set, an ending only records that
        // it happened, and the handler that owes this region fires the release when that handler ends.
        @volatile private var borrowed = false
        // whether any ending of the extent ran to completion, which is what a later discharge reports, and what tells a
        // refused re-entry which of the two ways this cell fired
        @volatile private var ended = false

        private[kyo] def borrow(): Unit      = borrowed = true
        private[kyo] def isBorrowed: Boolean = borrowed

        private[kyo] def complete(): Unit =
            ended = true
            if !borrowed && compareAndSet(false, true) then fin(Absent)

        // The release is owed the failure that unwound its extent whatever it is, and the fatal itself keeps
        // propagating. An unwind wins over any ending that already ran: the extent is being abandoned, and a release
        // that commits on success would commit over a failure.
        private[kyo] def drain(ex: Throwable): Unit =
            if compareAndSet(false, true) then fin(Maybe(ex))

        // the owner ended normally, so the extent's own endings are final. None of them means the extent never ran to
        // an ending at all, and the discard signal is what the release is owed.
        private[kyo] def discharge(ex: Throwable): Unit =
            if compareAndSet(false, true) then
                if ended then fin(Absent)
                else fin(Maybe(ex))

        private[kyo] def endedItsExtent: Boolean = ended
    end Cell

    private[kyo] object Cell:
        // the state a bracket hands to an isolated child: nothing completes or drains it, so a copy
        // holding it never runs a release and never refuses a re-entry
        val inert: Cell = new Cell(_ => ())
    end Cell

    def apply[A, S1](acquire: A < S1)[B, S2](use: A => B < S2)(
        release: (A, Maybe[Throwable]) => Unit
    )(using _frame: Frame): B < (S1 & S2) =
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
                region(cell, body)
            end apply
        Effect.defer(acquire).chain(ensure)
    end apply

    /** Runs `release` when `body`'s extent ends, with nothing to acquire first.
      *
      * [[apply]] cannot install its region until the acquire's value arrives, because the release is owed that
      * value, so a computation abandoned before it ever ran has no region and nothing to release, which is
      * right: nothing was acquired. Here there is nothing to wait for, so the region is a node from the start
      * and the abandonment walk finds it whether or not a single step ever ran. That is the difference between
      * "release what I acquired" and "run this however the extent ends", and only the second can promise to run
      * for a computation that never started.
      */
    def ensuring[B, S](release: Maybe[Throwable] => Unit)(body: => B < S)(using _frame: Frame): B < S =
        // The cell is minted by `derive`, which the evaluator calls once per evaluation of this node, so a
        // value used twice gets a guard of its own rather than the second use re-entering a released one.
        // The body is deferred for the same reason: `Effect.defer` runs it on application, so each
        // evaluation runs it again, which is what a by-name body promises its caller.
        //
        // The deferral is also what keeps the abandonment walk off it. That walk descends into a node's
        // `value`, and a `DeferWith`'s value is a stable unit, so the body is reached by applying the arrow
        // and never by walking it. A body held directly here would run the caller's side effect during a
        // walk that only meant to find what to release.
        //
        // No try/catch around the body, unlike [[apply]]: the evaluator installs the region before it
        // evaluates the node's value, so a throw from the body unwinds with the region already on the
        // stack and reaches the release that way. [[apply]] cannot rely on that, because its use runs as
        // the acquire's value arrives, which is before its region exists.
        region(new Cell(release), Effect.defer(body))
    end ensuring

    // The region both entry points install: the same custody, so a bracket and an `ensuring` behave
    // identically once installed and differ only in when that happens, and in where the cell comes from.
    // A bracket's cell closes over the acquired value and is made once per application, so it hands the
    // same one back; an `ensuring` has nothing to close over and mints one per evaluation.
    private def region[B, S](cell: => Cell, body: B < S)(using _frame: Frame): B < S =
        val h = new Handler.ContextHandler[Cell, Finalize, B, S]:
            def tag                        = Tag[Finalize]
            def derive(outer: Maybe[Cell]) = cell
            // the bracket belongs to the computation that installed it and closes only with
            // its own scope: an isolated child, a spawned fiber included, gets an inert copy
            def fork(parent: Cell)                                              = Cell.inert
            def join(parent: Cell, fk: Cell, child: Cell)                       = parent
            override private[kyo] def borrow(state: Cell): Unit                 = state.borrow()
            override private[kyo] def defers(state: Cell): Boolean              = state.isBorrowed
            override private[kyo] def done(state: Cell, value: B): Unit         = state.complete()
            override private[kyo] def release(state: Cell, ex: Throwable): Unit = state.drain(ex)
            override private[kyo] def discharge(state: Cell, ex: Throwable): Unit =
                state.discharge(ex)
            override private[kyo] def reenter(state: Cell): Unit =
                if state.get() then
                    // the two ways a released bracket gets re-entered want different advice, and guessing
                    // wrong sends the reader after the wrong cause
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
