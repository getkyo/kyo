package kyo.proto.kernel.internal

import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Result
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.Loop.Outcome2
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import kyo.proto.kernel.ContextEffect
import kyo.proto.kernel.Effect

abstract class Handler[E <: Effect, A, B, -S, State]:
    def tag: Tag[E]

    /** Consulted when a NonFatal throw unwinds the region's extent, with the state the region has reached, which is what its clauses have
      * threaded rather than what it was installed with. A Present computation replaces the region's outcome, and what follows the extent
      * still follows; Absent declines, and the failure keeps unwinding through the enclosing regions. A recover that fails itself is the
      * failure those enclosing regions then see.
      *
      * The state is the live one for the same reason [[release]]'s is: a region's state is single-sourced, held by the eval while the region
      * runs and by the node once it is a value, and every consumer reads whichever copy is live when it runs.
      */
    def recover(state: State, ex: Throwable): Maybe[B < S] = Absent

    /** The abandonment notification: consulted when a holder gives up on an extent's continuation, with the state the region has reached. A
      * region only becomes abandonable by being reified into a node, and the reification writes the live state into it, so the node's state
      * is that state rather than a stale copy. It runs where nothing is installed to answer for it, so it takes no effects; the default owes
      * nothing.
      */
    def release(state: State, ex: Throwable): Any < Any = ()
    def done(state: State, v: A): B < S
    override def toString = s"Handler(${tag.show})"
end Handler

object Handler:

    /** `answer` is abstract, and the handling site implements it with its clause inlined.
      *
      * The eval reaches a clause through exactly one call, and that call lands on one implementation per handling site, so it is
      * megamorphic whatever the shape. What the shape decides is which side of it the site's constants sit on. Behind a further abstract
      * `run(input, cont, k)`, the eval passed `Arrow.id` for `k` across that boundary, and the callee received a parameter it had to compose
      * with at runtime. Implemented at the site, `k` is not a parameter at all: the composition it fed is gone, and what remains folds as
      * the site inlines. Same principle as `Arrow.apply`'s one-argument entry going through `head`/`tail`.
      */
    abstract class HandlerCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Handler[E, A, B, S, Unit]:
        def answer[X](input: I[X], next: Arrow[O[X], A, E & S]): A < (E & S)

    /** [[HandlerCont]] whose clause receives the answered operation reified as a value.
      *
      * A region's tag is a subtype of every operation tag it answers, so a region over an intersection answers several effects and the
      * clause alone cannot tell which one an operation belongs to. The eval holds the operation's node and rebuilds it here,
      * continuation-free: the node's own tag rides inside the value, so evaluating it elsewhere re-raises the operation at its true
      * identity, answerable by that effect's own handler (`Mask` tunnels on exactly this). Reifying the value is also what frees the
      * clause of the operation's input and output structure: `X` is the operation's answer type and nothing else about its shape leaks.
      */
    abstract class HandlerContOperation[E <: Effect, A, B, S] extends Handler[E, A, B, S, Unit]:
        def answer[X](operation: X < E, next: Arrow[X, A, E & S]): A < (E & S)

    abstract class HandlerLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State] extends Handler[E, A, B, S, State]:
        def answer[X](state: State, input: I[X], next: Arrow[O[X], A, E & S]): Outcome2[State, O[X] < (E & S), B < S]

    abstract class HandlerContext[State, E <: ContextEffect[State], A, B, S] extends Handler[E, A, B, S, State]:
        /** What this binding installs, given what the enclosing scope binds for its tag, Absent when nothing is bound. A binding resolves
          * at installation, so a re-installed region resolves again from wherever it stands.
          */
        def resolve(outer: Maybe[State]): State
        def fork(current: State): State < S
        def join(current: State, forked: State, result: Result[Nothing, State]): Result[Nothing, State] < S
    end HandlerContext

end Handler
