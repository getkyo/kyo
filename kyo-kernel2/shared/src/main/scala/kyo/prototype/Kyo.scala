package kyo.prototype

import kyo.Frame
import kyo.Tag
import scala.annotation.static

sealed trait Boxed

sealed abstract class Kyo[+A, -S] extends Boxed:
    def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2)

final case class Nested[+A](value: A) extends Boxed

object Nested:

    @static private[prototype] def lift[A, S](v: A): A < S =
        v match
            case boxed: Boxed => Nested(boxed).asInstanceOf[A < S]
            case v            => v.asInstanceOf[A < S]

    @static private[prototype] def unnest[A](v: Any): A =
        v match
            case n: Nested[?] => n.value.asInstanceOf[A]
            case _            => v.asInstanceOf[A]
end Nested

object Kyo:

    private[prototype] inline def unnest[A, S](inline v: A < S): A =
        inline scala.compiletime.erasedValue[A] match
            case _: (Int | Long | Float | Double | Boolean | Byte | Short | Char | Unit | String) =>
                v.asInstanceOf[A]
            case _ =>
                Nested.unnest(v)

    abstract class Suspend[I[_], O[_], E <: ArrowEffect[I, O], X, +A, -S] extends Kyo[A, S]:
        self =>

        def tag: Tag[E]
        def input: I[X]
        def frame: Frame
        def cont: Arrow[O[X], A, S]

        final def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2) =
            new Suspend[I, O, E, X, B, S & S2]:
                val tag   = self.tag
                val input = self.input
                val frame = self.frame
                val cont  = self.cont.chain(f)
    end Suspend

    abstract class Defer[A, +B, -S] extends Kyo[B, S]:
        self =>

        def value: A < S
        def cont: Arrow[A, B, S]

        final def map[C, S2](f: Arrow[B, C, S2]) =
            new Defer[A, C, S & S2]:
                val value = self.value
                val cont  = self.cont.chain(f)
    end Defer

    object Defer:
        @static def apply[A, B, S](v: A < S, next: Arrow[A, B, S]): Defer[A, B, S] =
            new Defer[A, B, S]:
                def value = v
                def cont  = next
    end Defer

end Kyo
