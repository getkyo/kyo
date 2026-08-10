package kyo.prototype

import kyo.Frame
import kyo.Tag

sealed abstract class Kyo[+A, -S]:
    def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2)

object Kyo:

    final case class Nested[+A](value: A)

    private[prototype] inline def unnest[A, S](v: A < S): A =
        (v: @unchecked) match
            case n: Nested[?] => n.value.asInstanceOf[A]
            case _            => v.asInstanceOf[A]

    abstract class Suspend[I[_], O[_], E <: ArrowEffect[I, O], X, +A, -S] extends Kyo[A, S]:
        self =>

        def tag: Tag[E]
        def input: I[X]
        def frame: Frame
        def cont: Arrow[O[X], A, S]

        def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2) =
            new Suspend[I, O, E, X, B, S & S2]:
                def tag   = self.tag
                def input = self.input
                def frame = self.frame
                def cont  = self.cont.chain(f)
    end Suspend

    abstract class Defer[A, +B, -S] extends Kyo[B, S]:
        self =>

        def value: A < S
        def cont: Arrow[A, B, S]

        def map[C, S2](f: Arrow[B, C, S2]) =
            new Defer[A, C, S & S2]:
                def value = self.value
                def cont  = self.cont.chain(f)
    end Defer

    object Defer:
        def apply[A, B, S](v: A < S, next: Arrow[A, B, S]): Defer[A, B, S] =
            new Defer[A, B, S]:
                def value = v
                def cont  = next
    end Defer

end Kyo
