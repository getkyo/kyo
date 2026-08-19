package kyo.proto

import kyo.Frame
import kyo.Maybe
import language.implicitConversions
import scala.annotation.targetName
import scala.util.NotGiven

opaque type <[+A, -S] = A | Kyo[A, S] | Nested[A]

object `<`:
    implicit private[proto] def fromKyo[A, S](k: Kyo[A, S]): A < S = k

    // the one lift. A box lifted again is boxed again, so each level of nesting is one Nested and
    // lower strips one. A statically pending value never lifts implicitly: inference would nest a
    // computation where a merged row was meant (issue 903), and a generic function is the way to
    // hold a computation as a value on purpose
    implicit def lift[A](v: A)(using NotGiven[A <:< (Any < Nothing)]): A < Any =
        v match
            case v: (Kyo[?, ?] | Nested[?]) => Nested(v)
            case _                          => v

    extension [A, S](inline self: A < S)

        inline def map[B, S2](inline f: A => B < S2)(using inline frame: Frame): B < (S & S2) =
            Arrow(f)(self, Arrow.id)

        inline def eval(using S =:= Any): A =
            Eval(self.asInstanceOf[A < Any]).asInstanceOf[A]

        inline def evalNow: Maybe[A] =
            self.lower(pending = _ => Maybe.empty, done = a => Maybe(a))

        inline def lower[B](
            inline pending: Kyo[A, S] => B,
            inline done: A => B
        ): B =
            self match
                case self: Kyo[A, S] @unchecked => pending(self)
                case self =>
                    val value =
                        self match
                            case self: Nested[A] @unchecked => self.value
                            case self                       => self.asInstanceOf[A]
                    done(value)
            end match
        end lower
    end extension
end `<`
