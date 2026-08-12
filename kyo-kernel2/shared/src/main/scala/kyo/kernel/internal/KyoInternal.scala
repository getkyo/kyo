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

    final class Defer[A, +B, -S](val value: A < S, val cont: Arrow[A, B, S]) extends Kyo[B, S]:
        def map[C, S2](f: Arrow[B, C, S2]) =
            new Defer(value, cont.chain(f))

        override def toString = s"Kyo(Defer($value))"
    end Defer

    final class Handled[I[_], O[_], E <: ArrowEffect[I, O], A, +B, S, -S2](
        val value: A < (E & S),
        val handler: Handler.Cont[I, O, E, A, S] | Handler.Loop[I, O, E, A, S],
        val cont: Arrow[A, B, S & S2]
    ) extends Kyo[B, S & S2]:
        def map[C, S3](f: Arrow[B, C, S3]) =
            new Handled[I, O, E, A, C, S, S2 & S3](value, handler, cont.chain(f))

        override def toString = s"Kyo(Handled(${handler.tag.show}, $value))"
    end Handled

    final class HandledState[I[_], O[_], E <: ArrowEffect[I, O], A, +B, S, -S2, State](
        val value: A < (E & S),
        val handler: Handler.LoopState[I, O, E, A, S, State],
        val cont: Arrow[A, B, S & S2],
        val state: State
    ) extends Kyo[B, S & S2]:
        def map[C, S3](f: Arrow[B, C, S3]) =
            new HandledState[I, O, E, A, C, S, S2 & S3, State](value, handler, cont.chain(f), state)

        override def toString = s"Kyo(HandledState(${handler.tag.show}, $state, $value))"
    end HandledState

end Kyo
