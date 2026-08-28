package kyo.proto.kernel

import kyo.Frame
import kyo.proto.Arrow
import language.implicitConversions

opaque type <[+A, -S] >: A = A | Arrow[Any, A, S]

object `<`:
    implicit def fromKyo[A, S](kyo: Arrow[Any, A, S]): A < S = kyo

    extension [A, S](self: A < S)
        def map[B, S2](f: A => B < S2)(using Frame): B < (S & S2) =
            def run(v: A): B < S2 = f(v)
            self match
                case self: Arrow[Any, A, S] @unchecked =>
                    self.chain(Arrow(run))
                case _ =>
                    run(self.asInstanceOf[A])
            end match
        end map

        def chain[B, S2](cont: Arrow[A, B, S2]): B < (S & S2) =
            self match
                case self: Arrow[Any, A, S] @unchecked =>
                    self.chain(cont)
                case _ =>
                    cont.head(self.asInstanceOf[A], cont.tail)
            end match
        end chain
    end extension
end `<`
