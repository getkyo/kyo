package kyo

import kyo.Frame
import kyo.Span
import kyo.Tag
import kyo.kernel.ArrowEffect
import kyo.kernel.Boxed
import kyo.kernel.Loop.Outcome
import kyo.kernel.Loop.Outcome2
import scala.runtime.AbstractFunction1

sealed abstract class Arrow[-A, +B, -S] extends AbstractFunction1[A, B < S] with Boxed:

    def apply(v: A): B < S

    def step: Arrow.Step[A, B, S]

    def chain[C, S2](f: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
        if f.isInstanceOf[Arrow.Identity]
        then // TODO I've made Identity a class to use here. Check if this helps perf, convert other uses and measure
            this.asInstanceOf[Arrow[A, C, S & S2]]
        else
            Arrow.Chain(this, f)
end Arrow

object Arrow:

    def apply[A]: Transform[A, A, Any] = Identity.asInstanceOf[Transform[A, A, Any]]

    sealed abstract class Step[-A, +B, -S] extends Arrow[A, B, S]:
        type X
        def head: Transform[A, X, S]
        def tail: Arrow[X, B, S]
        final def step = this
    end Step

    abstract class Transform[-A, B, -S] extends Step[A, B, S]:
        self =>
        type X = B
        final def head = this
        final def tail = Arrow[B]

        def frame: Frame

        def apply(v: A) = this(v, Arrow[B])

        def apply[C, S2](v: A < S2, next: Arrow[B, C, S2]): C < (S & S2)

        override def chain[C, S2](f: Arrow[B, C, S2]) =
            if f eq Identity then
                this.asInstanceOf[Arrow[A, C, S & S2]]
            else
                new Step[A, C, S & S2]:
                    type X = B
                    def head        = self
                    def tail        = f
                    def apply(v: A) = self(v, f)

    end Transform

    sealed abstract class Identity extends Transform[Any, Any, Any]
    object Identity extends Identity:
        def frame                                       = Frame.internal
        override def chain[C, S2](f: Arrow[Any, C, S2]) = f
        def apply[C, S2](v: Any < S2, next: Arrow[Any, C, S2]): C < S2 =
            if next eq Identity then v.asInstanceOf[C < S2]
            else
                v match
                    case v: Arrow[Any, Any, S2] @unchecked => Chain(v, next)
                    case v =>
                        next match
                            case _: Defer[?, ?, ?] =>
                                Bind(v, next)
                            case _ =>
                                val s = next.step
                                s.head(v, s.tail)

    end Identity

    sealed abstract class Defer[A, +B, -S] extends Step[A, B, S]:
        type X = A
        final def head = Arrow[A]
        final def tail = this

        def apply(v: A) = Bind(v, this)
    end Defer

    final class Chain[A, XX, +B, -S](
        val a: Arrow[A, XX, S],
        val b: Arrow[XX, B, S]
    ) extends Defer[A, B, S]

    final class Bind[A, +B, -S](
        val value: A < S,
        val cont: Arrow[A, B, S]
    ) extends Defer[Any, B, S]

    // TODO rename to Park and rename related methods to keep the "park" theme cosnistent
    final private[kyo] class Eval[+A, +B, -S](
        val entries: Span[Arrow[?, ?, ?]],
        val tags: Span[AnyRef],
        val states: Span[AnyRef],
        val value: A < S
    ) extends Defer[Any, B, S]

    // TODO could these be type members instead of params? There's too much noise in the Eval code due to these params. I[_], O[_], E <: ArrowEffect[I, O], A, X
    abstract class Suspend[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Defer[Any, B, E & S]:

        def frame: Frame
        def tag: Tag[E]
        def input: I[A]
        def cont(v: O[A]): B < S

        final override def apply(v: Any) = cont(v.asInstanceOf[O[A]])

        override def chain[C, S2](f: Arrow[B, C, S2]): Arrow[Any, C, E & S & S2] =
            if f eq Identity then this.asInstanceOf[Arrow[Any, C, E & S & S2]]
            else SuspendWith(this, f)
    end Suspend

    final private[kyo] class SuspendWith[I[_], O[_], E <: ArrowEffect[I, O], A, X, B, S, S2](
        val susp: Suspend[I, O, E, A, X, S],
        val cont: Arrow[X, B, S2]
    ) extends Defer[Any, B, E & S & S2]:

        final override def apply(v: Any) =
            cont match
                case c: Chain[X, Any, B, S2] @unchecked =>
                    applyFolded(v, c)
                case cont =>
                    val st = cont.step
                    st.head(susp(v), st.tail)

        // a cont the evaluator folded pending entries into is a Chain, whose step heads
        // with Identity and defers the answer through a Bind; stepping the Chain's left
        // arm instead delivers into the composed tail directly. Kept out of apply so the
        // shape that needs no folding stays the smaller body.
        private def applyFolded(v: Any, c: Chain[X, Any, B, S2]): B < (E & S & S2) =
            val st = c.a.step
            st.head(susp(v), st.tail.chain(c.b))

        override def chain[C, S3](f: Arrow[B, C, S3]): Arrow[Any, C, E & S & S2 & S3] =
            if f eq Identity then this.asInstanceOf[Arrow[Any, C, E & S & S2 & S3]]
            else SuspendWith(susp, cont.chain(f))
    end SuspendWith

    // TODO could these be type members instead of params? There's too much noise in the Eval code due to these params. E <: ArrowEffect[?, ?], A, B,
    // this is also valid for the Handler subclasses
    abstract class Handle[E <: ArrowEffect[?, ?], A, B, +C, -S] extends Defer[Any, C, S]:
        def v: Arrow[Any, A, E & S]
        def handler: Handler[E, A, B, S]
        def cont: Arrow[B, C, S]
    end Handle

    sealed abstract class Handler[E <: ArrowEffect[?, ?], A, +B, -S]:
        def tag: Tag[E]

    object Handler:

        abstract class HandleCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Handler[E, A, B, S]:
            def run[X](input: I[X], cont: O[X] => A < (E & S)): A < (E & S)
            def complete(v: A): B < S

        abstract class HandleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Handler[E, A, B, S]:
            def run[X](input: I[X]): Outcome[O[X] < (E & S), B] < S
            def complete(v: A): B < S

        abstract class HandleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State] extends Handler[E, A, B, S]:
            def initialState: State
            def run[X](state: State, input: I[X]): Outcome2[State, O[X] < (E & S), B] < S
            def complete(state: State, v: A): B < S
        end HandleLoopState

    end Handler

end Arrow
