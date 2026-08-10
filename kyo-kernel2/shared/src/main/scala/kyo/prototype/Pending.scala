package kyo.prototype

import kyo.Frame
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.language.implicitConversions

opaque type <[+A, -S] >: Kyo[A, S] = A | Kyo[A, S]

object `<`:

    implicit inline def lift[A, S](v: A): A < S =
        v match
            case boxed: Kyo.Boxed => Kyo.Nested(boxed).asInstanceOf[A < S]
            case v                => v

    extension [A, S](self: A < S)

        @nowarn("msg=anonymous")
        inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            @nowarn("msg=anonymous") def mapLoop[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S & S2 & S3) =
                def arrow =
                    new Arrow.Transform[A, C, S & S2 & S3]:
                        def frame = _frame
                        def apply[D, S4](v: A < S4, next2: Arrow[C, D, S4]) =
                            mapLoop(v, next.chain(next2))
                v match
                    case kyo: Kyo[A, S3] @unchecked =>
                        kyo.map(arrow)
                    case v =>
                        val res       = Kyo.unnest(v)
                        val safepoint = Safepoint.get
                        if !safepoint.enter() then
                            Kyo.Defer(v, arrow)
                        else
                            val step = next.step
                            try step.head(f(res), step.tail)
                            finally safepoint.exit()
                        end if
                end match
            end mapLoop
            mapLoop(self, Arrow[B])
        end map

        inline def flatMap[B, S2](inline f: A => B < S2)(using inline frame: Frame): B < (S & S2) =
            map(f)

        inline def andThen[B, S2](inline next: => B < S2)(using inline frame: Frame): B < (S & S2) =
            map(_ => next)

        def eval(using S =:= Any): A =
            evalLoop(self.asInstanceOf[A < Any], never) match
                case kyo: Kyo[?, ?] => throw new IllegalStateException(s"unhandled suspension: $kyo")
                case v              => Kyo.unnest(v.asInstanceOf[A < Any])

        def evalPartial(stop: () => Boolean): A < S =
            evalLoop(self.asInstanceOf[A < Any], stop).asInstanceOf[A < S]

    end extension

    private val never: () => Boolean = () => false

    private def evalLoop[A](v: A < Any, stop: () => Boolean): A < Any =
        val safepoint = Safepoint.get
        val saved     = safepoint.save()
        try loop(v, stop)
        finally safepoint.restore(saved)
    end evalLoop

    @tailrec
    private def loop[A](v: A < Any, stop: () => Boolean): A < Any =
        if stop() then v
        else
            v match
                case kyo: Kyo.Defer[?, ?, ?] =>
                    val defer = kyo.asInstanceOf[Kyo.Defer[Any, A, Any]]
                    val step  = defer.cont.step
                    loop(step.head(defer.value, step.tail), stop)
                case v =>
                    v
end `<`
