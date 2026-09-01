package kyo.proto

import java.util.concurrent.atomic.AtomicBoolean
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Tag
import kyo.proto.kernel.<
import kyo.proto.kernel.ContextEffect
import kyo.proto.kernel.Effect
import scala.util.control.NonFatal

/** Pure suspension of side effects, and the bracketing that guards them.
  *
  * A bare suspension is a deferred computation carrying the row marker. A bracket opens a
  * context region in the same slice the acquire settles: the region's state is the
  * obligation, so every edge the eval owns reaches it, the done hook on completion, the
  * unwind on failure, and the abandonment walk on a discarded remainder. The claim makes
  * the edges exclusive.
  */
sealed trait Sync extends Effect

object Sync:

    /** The bracket region's effect: never suspended, a region exists only to be reached. */
    sealed private[kyo] trait Finalize extends ContextEffect[Cell]

    /** The obligation: a claim around the release thunk. The resource lives in the thunk's
      * closure. Atomic because a parked remainder can be drained on one thread while a
      * captured continuation completes on another.
      */
    final private[kyo] class Cell(fin: Maybe[Throwable] => Unit) extends AtomicBoolean:
        private[kyo] def complete(): Unit           = if compareAndSet(false, true) then fin(Absent)
        private[kyo] def drain(ex: Throwable): Unit = if compareAndSet(false, true) then fin(Maybe(ex))
    end Cell

    private[kyo] object Cell:
        // What a fork installs: a child's view of a bracket it does not own. Already
        // claimed, so a child's death cannot drain the parent's obligation.
        private[kyo] val inert: Cell =
            val cell = new Cell(_ => ())
            cell.set(true)
            cell
        end inert
    end Cell

    /** Suspends a potentially side-effecting computation. */
    inline def defer[A, S](inline f: => A < S)(using inline frame: Frame): A < (Sync & S) =
        Effect.deferInline(f)

    /** Acquires a resource, uses it, and releases it exactly once: on completion, when a
      * failure unwinds past the bracket, or when the computation is abandoned. The region
      * opens through a bind step, in the same slice the acquire settles: no safepoint can
      * separate the two, so an existing resource is never left without its region.
      */
    def acquireReleaseWith[A, S1](acquire: A < (Sync & S1))(
        release: (A, Maybe[Throwable]) => Unit
    )[B, S2](use: A => B < S2)(using _frame: Frame): B < (Sync & S1 & S2) =
        val open = new Arrow.Bind[A, B, Sync & S1 & S2]:
            def frame = _frame
            override def apply(a: A) =
                val cell = new Cell(outcome => release(a, outcome))
                val body =
                    // The region does not exist until the handle below wraps the body, so
                    // a use that throws during application drains here on the way out.
                    try use(a)
                    catch
                        case ex if NonFatal(ex) =>
                            cell.drain(ex)
                            throw ex
                ContextEffect.handle(Tag[Finalize])(
                    (_: Maybe[Cell]) => cell,
                    fork = (_: Cell) => Cell.inert,
                    join = (parent: Cell, _: Cell, _: Cell) => parent,
                    done = (c: Cell) => c.complete(),
                    release = (c: Cell, ex: Throwable) => c.drain(ex)
                )(body)
            end apply
        defer(acquire).chain(open)
    end acquireReleaseWith

    /** Runs a finalizer after the computation: on completion, failure, or abandonment,
      * exactly once. Absent means the computation completed.
      */
    def ensure[A, S](f: Maybe[Throwable] => Unit)(v: => A < S)(using Frame): A < (Sync & S) =
        acquireReleaseWith(())((_, outcome) => f(outcome))(_ => v)

    /** WARNING: Low-level API. Discharges the Sync marker; the deferred effects run when
      * the computation is evaluated.
      */
    object Unsafe:
        def run[A, S](v: => A < (Sync & S))(using Frame): A < S =
            // Sync rows hold only deferred effects and self-closing bracket regions, so
            // discharging the marker is a type-level operation.
            v.asInstanceOf[A < S]
    end Unsafe
end Sync
