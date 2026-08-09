package kyo.prototype

import kyo.Frame
import kyo.Tag

sealed abstract class Kyo[+A, -S]:
    private[prototype] def append[B, S2](f: Arrow[A, B, S2]): B < (S & S2)

object Kyo:

    final case class Nested[+A](value: A)

    private[prototype] inline def unnest(v: Any): Any =
        v match
            case n: Nested[?] => n.value
            case _            => v

    final class Suspend[I[_], O[_], E <: ArrowEffect[I, O], X, +A, -S](
        val tag: Tag[E],
        val input: I[X],
        val frame: Frame,
        val cont: Arrow[O[X], A, S]
    ) extends Kyo[A, S]:

        private[prototype] def append[B, S2](f: Arrow[A, B, S2]): B < (S & S2) =
            new Suspend(tag, input, frame, cont.andThen(f))

        override def toString = s"Suspend(${tag.show}, ${frame.position.show})"
    end Suspend

    final class Defer[X, +A, -S](
        val value: X < S,
        val cont: Arrow[X, A, S]
    ) extends Kyo[A, S]:

        private[prototype] def append[B, S2](f: Arrow[A, B, S2]): B < (S & S2) =
            new Defer(value, cont.andThen(f))

        override def toString = s"Defer($cont)"
    end Defer

end Kyo
