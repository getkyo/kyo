package kyo.proto.kernel

import kyo.Frame
import kyo.proto.Arrow
import kyo.proto.kernel.internal.Kyo
import kyo.proto.kernel.internal.Safepoint
import language.implicitConversions
import scala.annotation.nowarn

opaque type <[+A, -S] >: A = A | Arrow[Any, A, S]

object `<`:
    implicit def fromKyo[A, S](kyo: Arrow[Any, A, S]): A < S = kyo

    extension [A, S](self: A < S)
        @nowarn("msg=anonymous")
        inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            def arrow: Arrow[A, B, S2] =
                new Arrow.Transform[A, B, S2]:
                    def apply[C, S3](v: A, cont: Arrow[B, C, S3]) = run(v, cont)
                    override def toString                         = s"Transform(${_frame.snippetShort})"
            def run[C, S3](v: A < S3, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                v match
                    case v: Arrow[Any, A, S3] @unchecked =>
                        v.chain(arrow.chain(cont))
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Kyo.defer(v.asInstanceOf[A], arrow, cont)
                        else
                            val out =
                                f(v.asInstanceOf[A]) match
                                    case r: Arrow[Any, B, S2] @unchecked =>
                                        r.chain(cont)
                                    case r =>
                                        cont.head(r.asInstanceOf[B], cont.tail)
                            Safepoint.exit(slot)
                            out
                        end if
            end run
            run(self, Arrow.id)
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
