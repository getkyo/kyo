package kyo.prototype

import kyo.Frame
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.language.implicitConversions

opaque type <[+A, -S] >: Kyo[A, S] = A | Kyo[A, S]

object `<`:

    implicit inline def lift[A, S](v: A): A < S =
        v match
            case kyo: Kyo[?, ?] => Kyo.Nested(kyo).asInstanceOf[A < S]
            case v              => v

    extension [A, S](self: A < S)

        @nowarn("msg=anonymous")
        inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            val step =
                new Arrow.Transform[A, B, S2]:
                    def frame                                           = _frame
                    def run(v: A, context: Context, handlers: Handlers) = f(v)
            self match
                case kyo: Kyo[A, S] @unchecked => kyo.append(step)
                case v                         => new Kyo.Defer(v.asInstanceOf[A < S], step)
            end match
        end map

        inline def flatMap[B, S2](inline f: A => B < S2)(using inline frame: Frame): B < (S & S2) =
            map(f)

        inline def andThen[B, S2](inline next: => B < S2)(using inline frame: Frame): B < (S & S2) =
            map(_ => next)

        def eval(using S =:= Any): A =
            evalLoop(self.asInstanceOf[A < Any], never) match
                case kyo: Kyo[?, ?] => throw new IllegalStateException(s"unhandled suspension: $kyo")
                case v              => Kyo.unnest(v).asInstanceOf[A]

        def evalPartial(stop: () => Boolean): A < S =
            evalLoop(self.asInstanceOf[A < Any], stop).asInstanceOf[A < S]

    end extension

    private val never: () => Boolean = () => false

    private def evalLoop[A](v: A < Any, stop: () => Boolean): A < Any =
        val sp    = Safepoint.get
        val saved = sp.save()
        try loop(v, stop)
        finally sp.restore(saved)
    end evalLoop

    @tailrec
    private def loop[A](v: A < Any, stop: () => Boolean): A < Any =
        if stop() then v
        else
            v match
                case kyo: Kyo.Defer[?, ?, ?] =>
                    val defer = kyo.asInstanceOf[Kyo.Defer[Any, A, Any]]
                    loop(defer.cont(defer.value, Context.empty, Handlers.empty), stop)
                case v =>
                    v
end `<`
