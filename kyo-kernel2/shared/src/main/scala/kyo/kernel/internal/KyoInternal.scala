package kyo.kernel.internal

import kyo.Arrow
import kyo.Frame
import kyo.Tag
import kyo.kernel.*
import scala.annotation.static

sealed trait Boxed

final case class Nested[+A](value: A) extends Boxed

object Nested:

    @static private[kernel] def lift[A, S](v: A): A < S =
        v match
            case boxed: Boxed => Nested(boxed).asInstanceOf[A < S]
            case v            => v.asInstanceOf[A < S]

    @static private[kernel] def unnest[A](v: Any): A =
        v match
            case n: Nested[?] => n.value.asInstanceOf[A]
            case _            => v.asInstanceOf[A]
end Nested

sealed trait Kyo[+A, -S] extends Boxed:
    def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2)

object Kyo:

    private[kernel] inline def unnest[A, S](inline v: A < S): A =
        inline scala.compiletime.erasedValue[A] match
            case _: (Int | Long | Float | Double | Boolean | Byte | Short | Char | Unit | String) =>
                v.asInstanceOf[A]
            case _ =>
                Nested.unnest(v)

    // TODO no, this is not acceptable, we need to fully review. Let's discuss
    private[kyo] trait Defaulted:
        self: Suspend[?, ?, ?, ?, ?, ?] =>
        def default: Any

    // a fork's boundary crossing: answered in the same find-miss arm as
    // Defaulted, with the standing handler stack rebuilt around the carried
    // child instead of a fixed fallback
    private[kyo] trait Detached:
        self: Suspend[?, ?, ?, ?, ?, ?] =>
        def child: Any

    trait Suspend[I[_], O[_], E <: ArrowEffect[I, O], X, +A, -S] extends Kyo[A, S]:
        self =>

        def tag: Tag[E]
        def input: I[X]
        def frame: Frame
        def cont: Arrow[O[X], A, S]

        private[kernel] def root: Suspend[I, O, E, X, ?, ?] = this

        final override def toString =
            s"Kyo(${tag.show}, Input($input), ${frame.position.show}, ${frame.snippetShort})"

        final def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2) =
            val r = root
            val c = cont
            if c eq Arrow[O[X]] then
                // the identity continuation collapses: c meaning forces A = O[X]
                new Suspend[I, O, E, X, B, S & S2]:
                    override val root = r
                    def tag           = root.tag
                    def input         = root.input
                    def frame         = root.frame
                    val cont          = f.asInstanceOf[Arrow[O[X], B, S & S2]]
            else
                // one object per map: the suspension is its own chain node, meaning the
                // previous continuation followed by f
                new Arrow.AndThen[O[X], A, B, S & S2](c, f) with Suspend[I, O, E, X, B, S & S2]:
                    override val root = r
                    def tag           = root.tag
                    def input         = root.input
                    def frame         = root.frame
                    def cont          = this
            end if
        end map
    end Suspend

    sealed trait Defer[A, +B, -S] extends Kyo[B, S]:
        def value: A < S
        def cont: Arrow[A, B, S]

        final def map[C, S2](f: Arrow[B, C, S2]): C < (S & S2) =
            val v = value
            // one object per map: the node is its own chain arrow, meaning the
            // previous continuation followed by f
            new Arrow.AndThen[A, B, C, S & S2](cont, f) with Defer[A, C, S & S2]:
                val value = v
                def cont  = this
        end map

        final override def toString = s"Kyo(Defer($value))"
    end Defer

    object Defer:
        // not inline: construction sits on the budget-exhausted slow path, and an
        // inline companion apply costs an Inliner cycle and residual tree at every
        // map call site in every program (kyo-compile-bench, MapChainDeep100)
        def apply[A, B, S](value: A < S, cont: Arrow[A, B, S]): Defer[A, B, S] =
            new Impl(value, cont)

        final class Impl[A, +B, -S](val value: A < S, val cont: Arrow[A, B, S]) extends Defer[A, B, S]
    end Defer

    trait Handled[I[_], O[_], E <: ArrowEffect[I, O], A, +B, S, -S2] extends Kyo[B, S & S2]:
        def value: A < (E & S)
        def handler: Handler.Cont[I, O, E, A, S] | Handler.Loop[I, O, E, A, S]
        def exit: Arrow[A, B, S & S2]

        final def map[C, S3](f: Arrow[B, C, S3]): C < (S & S2 & S3) =
            val v = value
            val h = handler
            val e = exit
            if e eq Arrow[A] then
                // the identity exit collapses: e meaning forces B = A
                new Handled.Impl[I, O, E, A, C, S, S2 & S3](v, h, f.asInstanceOf[Arrow[A, C, S & S2 & S3]])
            else
                new Arrow.AndThen[A, B, C, S & S2 & S3](e, f) with Handled[I, O, E, A, C, S, S2 & S3]:
                    val value   = v
                    val handler = h
                    def exit    = this
            end if
        end map

        final override def toString = s"Kyo(Handled(${handler.tag.show}, $value))"
    end Handled

    object Handled:
        // not inline: only the evaluator constructs through this apply, and an
        // inline expansion buys nothing over a constructor call
        def apply[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
            value: A < (E & S),
            handler: Handler.Cont[I, O, E, A, S] | Handler.Loop[I, O, E, A, S],
            exit: Arrow[A, B, S & S2]
        ): Handled[I, O, E, A, B, S, S2] =
            new Impl(value, handler, exit)

        final class Impl[I[_], O[_], E <: ArrowEffect[I, O], A, +B, S, -S2](
            val value: A < (E & S),
            val handler: Handler.Cont[I, O, E, A, S] | Handler.Loop[I, O, E, A, S],
            val exit: Arrow[A, B, S & S2]
        ) extends Handled[I, O, E, A, B, S, S2]
    end Handled

    // a region that answers one operation: its inner result type A and its
    // exit's input type B differ because the two clauses meet at B, the
    // first-operation clause producing it directly and the done clause
    // producing it from the settled inner value
    trait HandledFirst[I[_], O[_], E <: ArrowEffect[I, O], A, B, +C, S, S2, -S3] extends Kyo[C, S & S2 & S3]:
        def value: A < (E & S)
        def handler: Handler.First[I, O, E, A, B, S, S2]
        def exit: Arrow[B, C, S & S2 & S3]

        final def map[D, S4](f: Arrow[C, D, S4]): D < (S & S2 & S3 & S4) =
            val v = value
            val h = handler
            val e = exit
            if e eq Arrow[B] then
                // the identity exit collapses: e meaning forces C = B
                new HandledFirst.Impl[I, O, E, A, B, D, S, S2, S3 & S4](v, h, f.asInstanceOf[Arrow[B, D, S & S2 & S3 & S4]])
            else
                new Arrow.AndThen[B, C, D, S & S2 & S3 & S4](e, f) with HandledFirst[I, O, E, A, B, D, S, S2, S3 & S4]:
                    val value   = v
                    val handler = h
                    def exit    = this
            end if
        end map

        final override def toString = s"Kyo(HandledFirst(${handler.tag.show}, $value))"
    end HandledFirst

    object HandledFirst:
        // not inline: only the catching guard constructs through this apply, and
        // an inline expansion buys nothing over a constructor call
        def apply[I[_], O[_], E <: ArrowEffect[I, O], A, B, C, S, S2, S3](
            value: A < (E & S),
            handler: Handler.First[I, O, E, A, B, S, S2],
            exit: Arrow[B, C, S & S2 & S3]
        ): HandledFirst[I, O, E, A, B, C, S, S2, S3] =
            new Impl(value, handler, exit)

        final class Impl[I[_], O[_], E <: ArrowEffect[I, O], A, B, +C, S, S2, -S3](
            val value: A < (E & S),
            val handler: Handler.First[I, O, E, A, B, S, S2],
            val exit: Arrow[B, C, S & S2 & S3]
        ) extends HandledFirst[I, O, E, A, B, C, S, S2, S3]
    end HandledFirst

    trait HandledState[I[_], O[_], E <: ArrowEffect[I, O], A, B, +C, S, -S2, State] extends Kyo[C, S & S2]:
        def value: A < (E & S)
        def handler: Handler.LoopState[I, O, E, A, B, S, State]
        def exit: Arrow[B, C, S & S2]
        def state: State

        final def map[D, S3](f: Arrow[C, D, S3]): D < (S & S2 & S3) =
            val v  = value
            val h  = handler
            val st = state
            val e  = exit
            if e eq Arrow[B] then
                // the identity exit collapses: e meaning forces C = B
                new HandledState.Impl[I, O, E, A, B, D, S, S2 & S3, State](v, h, f.asInstanceOf[Arrow[B, D, S & S2 & S3]], st)
            else
                new Arrow.AndThen[B, C, D, S & S2 & S3](e, f) with HandledState[I, O, E, A, B, D, S, S2 & S3, State]:
                    val value   = v
                    val handler = h
                    val state   = st
                    def exit    = this
            end if
        end map

        final override def toString = s"Kyo(HandledState(${handler.tag.show}, $state, $value))"
    end HandledState

    object HandledState:
        // not inline: only the evaluator constructs through this apply, and an
        // inline expansion buys nothing over a constructor call
        def apply[I[_], O[_], E <: ArrowEffect[I, O], A, B, C, S, S2, State](
            value: A < (E & S),
            handler: Handler.LoopState[I, O, E, A, B, S, State],
            exit: Arrow[B, C, S & S2],
            state: State
        ): HandledState[I, O, E, A, B, C, S, S2, State] =
            new Impl(value, handler, exit, state)

        final class Impl[I[_], O[_], E <: ArrowEffect[I, O], A, B, +C, S, -S2, State](
            val value: A < (E & S),
            val handler: Handler.LoopState[I, O, E, A, B, S, State],
            val exit: Arrow[B, C, S & S2],
            val state: State
        ) extends HandledState[I, O, E, A, B, C, S, S2, State]
    end HandledState

end Kyo
