package kyo.kernel.proto

import kyo.Frame
import kyo.Loop.Outcome
import kyo.Loop.Outcome2
import kyo.Span
import kyo.Tag
import scala.annotation.nowarn
import scala.language.implicitConversions

opaque type <[+A, -S] = A | Arrow[Any, A, S]

object `<`:
    implicit def lift[A, S](v: Arrow[Any, A, S]): A < S = v
    implicit def liftValue[A](v: A): A < Any            = v

    extension [A, S](self: A < S)

        @nowarn("msg=anonymous")
        inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            def arrow =
                new Arrow.Transform[A, B, S & S2]:
                    def frame = _frame
                    def apply[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S & S2 & S3) =
                        run(v, next)
            def run[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S & S2 & S3) =
                val slot = Safepoint.get()
                if v.isInstanceOf[Arrow[?, ?, ?]] || !Safepoint.enter(slot) then
                    val k = arrow.chain(next)
                    v match
                        case v: Arrow[Any, A, S3] @unchecked => v.chain(k)
                        case v                               => Arrow.Bind(v.asInstanceOf[A], k)
                else
                    val step = next.step
                    val out  = step.head(f(v.asInstanceOf[A]), step.tail)
                    Safepoint.exit(slot)
                    out
                end if
            end run
            run(self: A < S, Arrow[B])

    end extension

end `<`
