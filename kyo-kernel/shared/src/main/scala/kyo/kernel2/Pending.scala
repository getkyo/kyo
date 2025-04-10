package kyo.kernel2

import java.util.ArrayDeque
import java.util.Arrays
import kyo.Const
import kyo.Frame
import kyo.Id
import kyo.Maybe
import kyo.Maybe.*
import kyo.Tag
import kyo.kernel2.internal.Safepoint
import scala.annotation.nowarn
import scala.annotation.static
import scala.annotation.tailrec
import scala.compiletime.erasedValue
import scala.language.implicitConversions

class ttt:
    import `<`.*
    sealed trait Eff1 extends ArrowEffect[Const[Int], Const[Int]]
    sealed trait Eff2 extends ArrowEffect[Const[Int], Const[Int]]
    sealed trait Eff3 extends ArrowEffect[Const[Int], Const[Int]]

    val x = ArrowEffect.suspend(Tag[Eff3], 1)
    // for
    //     v1 <- ArrowEffect.suspend(Tag[Eff1], 1)
    //     a = 1
    //     b = 1
    //     c = 1
    //     v2 <- ArrowEffect.suspend(Tag[Eff2], 1)
    //     v3 <- ArrowEffect.suspend(Tag[Eff3], 1)
    // yield v1 + v2 + v3

    val a = ArrowEffect.handle(Tag[Eff1], x)([C] => (i, c) => c(i + 1)).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1)
    val b = ArrowEffect.handle(Tag[Eff2], a)([C] => (i, c) => c(i + 2)) // .map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1)
    val c =
        ArrowEffect.handle(Tag[Eff3], b)([C] => (i, c) => c(i + 3)) // .map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1)

    println(c.eval)

    // val a = (1: )
end ttt

opaque type <[+A, -S] >: Arrow[Any, A, S] = A | Box[A] | Arrow[Any, A, S]

case class Box[+A](v: A)

object `<`:

    implicit def lift[A, S](v: A): A < S =
        v match
            case _: Arrow[?, ?, ?] => Box(v)
            case _                 => v

    import Arrow.internal.*

    extension [A, S](inline self: A < S)

        inline def map[B, S2](f: Arrow[A, B, S2])(using Safepoint): B < (S & S2) =
            f(self)

        inline def map[B, S2](inline f: A => Safepoint ?=> B < S)(using safepoint: Safepoint, inline frame: Frame): B < (S & S2) =
            map(Arrow.init(f(_)))

        inline def flatMap[B, S2](inline f: A => Safepoint ?=> B < S)(using safepoint: Safepoint, inline frame: Frame): B < (S & S2) =
            map(Arrow.init(f(_)))

        inline def eval(using S =:= Any): A =
            @tailrec def loop(v: A < S): A =
                v.reduce(loop(_))
            loop(self)
        end eval

        private[kyo] inline def reduce(
            inline pending: Arrow[Any, A, S] => A
        ): A =
            reduce(pending, identity)

        private[kyo] inline def reduce[B](
            inline pending: Arrow[Any, A, S] => B,
            inline done: A => B
        ): B =
            self match
                case self: Arrow[Any, A, S] @unchecked =>
                    pending(self)
                case _ =>
                    val r =
                        self match
                            case self: Box[A] @unchecked => self.v
                            case _                       => self.asInstanceOf[A]
                    done(r)

    end extension

    extension [A, S](self: A < S)
        def flatten[B, S2](using ev: A => B < S2)(using safepoint: Safepoint, frame: Frame): B < (S & S2) =
            self.map(Arrow.identity.asInstanceOf[Arrow[A, B, S & S2]])

end `<`

