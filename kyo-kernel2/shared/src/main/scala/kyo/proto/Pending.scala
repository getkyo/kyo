package kyo.proto

import kyo.Frame
import language.implicitConversions

opaque type <[+A, -S] = A | Kyo[A, S] | Nested[A]

object `<`:
    implicit private[proto] def fromKyo[A, S](k: Kyo[A, S]): A < S = k

    // a box lifted again is boxed again, so each level of nesting is one Nested and lower strips one
    implicit def lift[A](v: A): A < Any =
        v match
            case v: (Kyo[?, ?] | Nested[?]) => Nested(v)
            case _                          => v

    extension [A, S](self: A < S)

        inline def map[B, S2](inline f: A => B < S2)(using inline frame: Frame): B < (S & S2) =
            Arrow.Transform(f)(self, Arrow.id)

        inline def eval(using S =:= Any): A =
            Eval(self.asInstanceOf[A < Any])

        inline def lower[B](
            inline pending: Kyo[A, S] => B,
            inline done: A => B
        ): B =
            self match
                case self: Kyo[A, S] @unchecked => pending(self)
                case _ =>
                    val value =
                        self match
                            case self: Nested[A] @unchecked => self.value
                            case self                       => self.asInstanceOf[A]
                    done(value)
            end match
        end lower
    end extension
end `<`
