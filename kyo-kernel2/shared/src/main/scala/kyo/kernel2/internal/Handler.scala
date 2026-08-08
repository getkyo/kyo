package kyo.kernel2.internal

import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.kernel2.<
import kyo.kernel2.Arrow
import kyo.kernel2.ArrowEffect

/** The park-time reification of an effect handler: a delimiter in the continuation chain.
  *
  * A delimiter is an ordinary [[Arrow.Transform]]. On values it is at most a completion step (pass-through for most formats, `done` for
  * shallow handlers), so fused chains flow through it transparently. On suspensions the drive searches the chain for the first delimiter
  * whose tag matches the suspension's effect and applies the handler's format: run the clause in place, discard the prefix, or capture the
  * prefix as a first-class continuation.
  *
  * Because delimiters are chain elements, the prepend machinery carries installed handlers across parks with no re-wrap: once installed, a
  * handler travels with the computation. Clauses are stored at the types the public [[ArrowEffect]] surface established; the erased
  * boundary lives in the drive's dispatch, where each cast is justified by the tag match that precedes it.
  */
sealed abstract private[kyo] class Handler[-A, +B, -S] extends Arrow.Transform[A, B, S]:
    /** The tag at its erased type, the currency of the drive's searches. Subclasses erase where their effect type is bound, so no
      * cast is involved.
      */
    private[kyo] def erasedTag: Tag[Any]
    final override private[kyo] def hasHandler: Boolean = true

    /** Installs this delimiter around a region, purely: nothing evaluates here, and the region's operations dispatch to this delimiter
      * when a drive reaches them. The cast discharges the region's effect from the row, which is the meaning of installation: every
      * operation of `E` inside the region is interpreted by this delimiter, so the result no longer carries `E`.
      */
    final private[kyo] def install[E, S2](v: A < (E & S2)): B < (S2 & S) =
        this(v).asInstanceOf[B < (S2 & S)]
end Handler

private[kyo] object Handler:

    /** Delimiters that interpret arrow-effect operations; context bindings are the sibling kind. */
    sealed abstract class ArrowHandler[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Handler[A, B, S]:
        def effectTag: Tag[E]
        final private[kyo] def erasedTag: Tag[Any] = effectTag.erased

    /** Deep handler with the continuation as a function (ctl format). */
    final class Cont[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        val effectTag: Tag[E],
        val clause: [C] => (I[C], O[C] => A < (E & S & S2)) => A < (E & S & S2),
        val frame: Frame
    ) extends ArrowHandler[I, O, E, A, A, S & S2]:
        def run[C2, S3](v: Any, cont: Arrow[A, C2, S3]): C2 < (S & S2 & S3) =
            cont(Kyo.lift(v).asInstanceOf[A < Any])
    end Cont

    /** Deep handler that answers each operation in place (fun format). */
    final class Resume[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        val effectTag: Tag[E],
        val clause: [C] => I[C] => O[C] < (E & S & S2),
        val frame: Frame
    ) extends ArrowHandler[I, O, E, A, A, S & S2]:
        def run[C2, S3](v: Any, cont: Arrow[A, C2, S3]): C2 < (S & S2 & S3) =
            cont(Kyo.lift(v).asInstanceOf[A < Any])
    end Resume

    /** Deep handler that ends the region at each operation (final ctl format). */
    final class Stop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        val effectTag: Tag[E],
        val clause: [C] => I[C] => A < (E & S & S2),
        val frame: Frame
    ) extends ArrowHandler[I, O, E, A, A, S & S2]:
        def run[C2, S3](v: Any, cont: Arrow[A, C2, S3]): C2 < (S & S2 & S3) =
            cont(Kyo.lift(v).asInstanceOf[A < Any])
    end Stop

    /** Shallow handler: handles only the first operation, then leaves the region. */
    final class First[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        val effectTag: Tag[E],
        val clause: [C] => (I[C], O[C] => A < (E & S)) => B < S2,
        val done: A => B < S2,
        val frame: Frame
    ) extends ArrowHandler[I, O, E, A, B, S2]:
        def run[C2, S3](v: Any, cont: Arrow[B, C2, S3]): C2 < (S2 & S3) =
            cont(done(v.asInstanceOf[A]))
    end First

    /** Deep stateful handler: state threads through the operations, done runs on completion.
      *
      * State evolution never mutates the node: each Outcome.Continue installs a replacement delimiter carrying the next state.
      */
    final class Loop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2, State](
        val effectTag: Tag[E],
        val state: State,
        val clause: [C] => (I[C], State, O[C] => A < (E & S)) => kyo.kernel2.Loop.Outcome2[State, A < (E & S), B] < S2,
        val done: (State, A) => B < (S & S2),
        val frame: Frame
    ) extends ArrowHandler[I, O, E, A, B, S & S2]:
        def run[C2, S3](v: Any, cont: Arrow[B, C2, S3]): C2 < (S & S2 & S3) =
            cont(done(state, v.asInstanceOf[A]))

        /** The replacement delimiter for the next iteration. The cast is the dispatch's state round trip: the value came out of this
          * handler's own clause outcome, which produced it at type State.
          */
        private[kyo] def replaceState(nextState: Any): Loop[I, O, E, A, B, S, S2, State] =
            new Loop(effectTag, nextState.asInstanceOf[State], clause, done, frame)
    end Loop

    /** Scoped binding for a context effect: the delimiter is the binding.
      *
      * A read resolves against the innermost matching delimiter; the transform receives the outer binding (the resolution of matching
      * delimiters further out) and produces the value for this scope.
      */
    final class ContextBinding[A](val effectTag: Tag[Any], val transform: Maybe[Any] => Any, val frame: Frame) extends Handler[A, A, Any]:
        private[kyo] def erasedTag: Tag[Any] = effectTag
        def run[C2, S3](v: Any, cont: Arrow[A, C2, S3]): C2 < (Any & S3) =
            cont(Kyo.lift(v).asInstanceOf[A < Any])
    end ContextBinding

end Handler
