package kyo.kernel.internal

import kyo.Arrow
import kyo.Frame
import kyo.Tag
import kyo.kernel.*
import kyo.kernel.Loop.Outcome
import scala.annotation.static

sealed trait Boxed

final case class Nested[+A](value: A) extends Boxed

object Nested:

    @static def lift[A, S](v: A): A < S =
        v match
            case boxed: Boxed => Nested(boxed).asInstanceOf[A < S]
            case v            => v.asInstanceOf[A < S]

    @static def unnest[A](v: Any): A =
        v match
            case n: Nested[?] => n.value.asInstanceOf[A]
            case _            => v.asInstanceOf[A]
end Nested

sealed trait Kyo[+A, -S] extends Boxed:
    self =>

    def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2) =
        if f.isIdentity then
            this.asInstanceOf[B < (S & S2)]
        else
            Kyo.defer(this, f)
end Kyo

object Kyo:

    inline def unnest[A, S](inline v: A < S): A =
        inline scala.compiletime.erasedValue[A] match
            case _: (Int | Long | Float | Double | Boolean | Byte | Short | Char | Unit | String) =>
                v.asInstanceOf[A]
            case _ =>
                Nested.unnest(v)

    def defer[A, B, S](v: A < S, f: Arrow[A, B, S]): Kyo[B, S] =
        new Defer[A, B, S]:
            val value = v
            val cont  = f

    abstract class Defer[A, +B, -S] extends Kyo[B, S]:
        self =>

        def value: A < S
        def cont: Arrow[A, B, S]
    end Defer

    abstract class Suspend[I[_], O[_], E <: ArrowEffect[I, O], X, +A, -S] extends Kyo[A, S]:
        self =>

        def tag: Tag[E]
        def input: I[X]
        def frame: Frame
    end Suspend

    abstract class HandleCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2] extends Kyo[B, S & S2]:
        self =>

        def tag: Tag[E]
        def value: A < (E & S)

        def run[X](input: I[X], cont: O[X] => A < (E & S)): B < (S & S2)
        def complete(v: A): B < (S & S2)
    end HandleCont

    abstract class HandleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Kyo[B, S]:
        self =>

        def tag: Tag[E]
        def value: A < (E & S)

        def run[X](input: I[X]): Outcome[O[X] < (E & S), B] < S
        def complete(v: A): B < S
    end HandleLoop
end Kyo
