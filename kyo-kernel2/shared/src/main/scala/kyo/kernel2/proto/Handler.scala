package kyo.kernel2.proto

import kyo.Tag

/** The five handler formats (design 2.4). Clauses are stored AND invoked at their public types: `complete` is the region's completion
  * step, `resume` is the dispatch semantics when a suspension of the region's effect reaches its handler with the captured remainder.
  *
  * The drive trades in erased values (the trampoline currency), so it enters through the final bridge methods below; their casts are
  * justified by the tag match the drive performed first, and inside `complete`/`resume` everything type-checks with no cast.
  */
sealed abstract class Handler[I[_], O[_], E <: ArrowEffect[I, O], A, B, S]:

    def effectTag: Tag[E]

    /** The completion step: the region's inner computation finished with `v`. */
    def complete(v: A): B < S

    /** A suspension of this region's effect reached its handler: the operation's input and the captured remainder, at this handler's own
      * types. Deep formats re-enter the region by wrapping a new [[Handled]] node; shallow ones do not.
      */
    def resume(input: I[Any], cont: O[Any] => A < (E & S)): B < S

    final private[proto] def dispatch(input: Any, cont: Any => Any < Any): Any < Any =
        resume(input.asInstanceOf[I[Any]], o => cont(o).asInstanceOf[A < (E & S)]).asInstanceOf[Any < Any]
    final private[proto] def dispatchComplete(v: Any): Any < Any =
        complete(v.asInstanceOf[A]).asInstanceOf[Any < Any]
    final private[proto] def rewrap(inner: Any < Any, exit: Any => Any < Any): Any < Any =
        Handled[I, O, E, A, B, S, Any, Any](inner.asInstanceOf[A < (E & S)], this, exit).asInstanceOf[Any < Any]

end Handler

object Handler:

    /** Deep handler with the continuation as a function (ctl format). */
    final class Cont[I[_], O[_], E <: ArrowEffect[I, O], A, S](
        val effectTag: Tag[E],
        clause: [C] => (I[C], O[C] => A < (E & S)) => A < (E & S)
    ) extends Handler[I, O, E, A, A, S]:
        def complete(v: A): A < S = pure(v)
        def resume(input: I[Any], cont: O[Any] => A < (E & S)): A < S =
            Handled[I, O, E, A, A, S, A, Any](clause[Any](input, cont), this, a => Pure(a))
    end Cont

    /** Deep handler that answers each operation in place (fun format). The drive resolves these at the operation's SITE with no
      * continuation ever existing (Eval); `resume` is only the uniform fallback for a suspension that traveled.
      */
    final class Resume[I[_], O[_], E <: ArrowEffect[I, O], A, S](
        val effectTag: Tag[E],
        clause: [C] => I[C] => O[C] < (E & S)
    ) extends Handler[I, O, E, A, A, S]:
        def complete(v: A): A < S = pure(v)
        def resume(input: I[Any], cont: O[Any] => A < (E & S)): A < S =
            Handled[I, O, E, A, A, S, A, Any](clause[Any](input).flatMap(cont), this, a => Pure(a))

        /** the in-place site entry: just the clause, at the drive's currency */
        private[proto] def clauseFor(input: Any): Any < Any =
            clause[Any](input.asInstanceOf[I[Any]]).asInstanceOf[Any < Any]
    end Resume

    /** Deep handler that ends the region at each operation (final ctl format). `resume` is never called: the drive unwinds to the owning
      * region without capturing anything (Eval's stop walk) and enters through `stopWith`.
      */
    final class Stop[I[_], O[_], E <: ArrowEffect[I, O], A, S](
        val effectTag: Tag[E],
        clause: [C] => I[C] => A < (E & S)
    ) extends Handler[I, O, E, A, A, S]:
        def complete(v: A): A < S = pure(v)
        def resume(input: I[Any], cont: O[Any] => A < (E & S)): A < S =
            Handled[I, O, E, A, A, S, A, Any](clause[Any](input), this, a => Pure(a))
        private[proto] def stopWith(input: Any): Any < Any =
            Handled[I, O, E, A, A, S, A, Any](clause[Any](input.asInstanceOf[I[Any]]), this, a => Pure(a)).asInstanceOf[Any < Any]
    end Stop

    /** Shallow handler: handles only the first operation, then leaves; the continuation is the raw unhandled remainder. */
    final class First[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        val effectTag: Tag[E],
        clause: [C] => (I[C], O[C] => A < (E & S)) => B < S,
        done: A => B < S
    ) extends Handler[I, O, E, A, B, S]:
        def complete(v: A): B < S = done(v)
        def resume(input: I[Any], cont: O[Any] => A < (E & S)): B < S =
            clause[Any](input, cont)
    end First

    /** Deep stateful handler: state evolves by a replacement handler on a fresh region node, no mutation anywhere. */
    final class Loop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State](
        val effectTag: Tag[E],
        state: State,
        clause: [C] => (I[C], State, O[C] => A < (E & S)) => Loop.Outcome[State, A < (E & S), B] < S,
        done: (State, A) => B < S
    ) extends Handler[I, O, E, A, B, S]:
        def complete(v: A): B < S = done(state, v)
        def resume(input: I[Any], cont: O[Any] => A < (E & S)): B < S =
            clause[Any](input, state, cont).flatMap {
                case Loop.Continue(st, next) =>
                    Handled[I, O, E, A, B, S, B, Any](next, new Loop[I, O, E, A, B, S, State](effectTag, st, clause, done), b => Pure(b))
                case Loop.Done(b) => pure(b)
            }
    end Loop

    object Loop:
        sealed trait Outcome[+State, +A, +B]
        final case class Continue[State, A](state: State, next: A) extends Outcome[State, A, Nothing]
        final case class Done[B](value: B)                         extends Outcome[Nothing, Nothing, B]
    end Loop

end Handler
