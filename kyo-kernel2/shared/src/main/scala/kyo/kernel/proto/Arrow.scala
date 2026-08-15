package kyo.kernel.proto

import kyo.Frame
import kyo.Loop.Outcome
import kyo.Loop.Outcome2
import kyo.Span
import kyo.Tag
import scala.annotation.nowarn
import scala.language.implicitConversions
import scala.runtime.AbstractFunction1

sealed abstract class Arrow[-A, +B, -S] extends AbstractFunction1[A, B < S] with Boxed:

    def apply(v: A): B < S

    def step: Arrow.Step[A, B, S]

    def chain[C, S2](f: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
        if f eq Arrow.Identity then
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

    object Identity extends Transform[Any, Any, Any]:
        def frame                                       = Frame.internal
        override def chain[C, S2](f: Arrow[Any, C, S2]) = f
        def apply[C, S2](v: Any < S2, next: Arrow[Any, C, S2]): C < S2 =
            if next eq Identity then v.asInstanceOf[C < S2]
            else
                v match
                    case v: Arrow[Any, Any, S2] @unchecked => Chain(v, next)
                    case v =>
                        next match
                            case next: Defer[?, ?, ?] =>
                                Bind(v.asInstanceOf[Any < S2], next.asInstanceOf[Arrow[Any, C, S2]])
                            case next =>
                                val s = next.step
                                s.head(v.asInstanceOf[Any < S2], s.tail)

    end Identity

    sealed abstract class Defer[A, +B, -S] extends Step[A, B, S]:
        type X = A
        final def head = Arrow[A]
        final def tail = this

        def apply(v: A) = Bind(v.asInstanceOf[A < S], this)
    end Defer

    final class Chain[A, XX, +B, -S](
        val a: Arrow[A, XX, S],
        val b: Arrow[XX, B, S]
    ) extends Defer[A, B, S]

    final class Bind[A, +B, -S](
        val value: A < S,
        val cont: Arrow[A, B, S]
    ) extends Defer[Any, B, S]

    final private[proto] class Eval[+A, +B, -S](
        val entries: Span[Arrow[?, ?, ?]],
        val tags: Span[AnyRef],
        val states: Span[AnyRef],
        val value: A < S
    ) extends Defer[Any, B, S]

    abstract class Suspend[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Defer[Any, B, E & S]:
        self =>

        def tag: Tag[E]
        def input: I[A]
        def cont(v: O[A]): B < S

        final override def apply(v: Any) = cont(v.asInstanceOf[O[A]])

        override def chain[C, S2](f: Arrow[B, C, S2]): Arrow[Any, C, E & S & S2] =
            if f eq Identity then this.asInstanceOf[Arrow[Any, C, E & S & S2]]
            else
                new Suspend[I, O, E, A, C, S & S2]:
                    def tag           = self.tag
                    def input         = self.input
                    def cont(v: O[A]) = Arrow[B](self.cont(v), f)
    end Suspend

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
