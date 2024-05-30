package kyo

import kyo.core.*
import kyo.core.internal.*
import kyo.internal.Trace

abstract private[kyo] class Defer[T, S] extends Suspend[Const[Unit], Unit, T, S & Defers]:
    final def command = ()
    final def tag     = Tag[Defers].asInstanceOf[Tag[Any]]

class Defers extends Effect[Defers]:
    type Command[T] = Unit

object Defers extends Defers:

    inline def apply[T, S](inline v: => T < S)(using inline _trace: Trace): T < (S & Defers) =
        fromKyo {
            new Defer[T, S]:
                def trace                                                              = _trace
                def apply(ign: Unit, s: Safepoint[S & Defers], l: Locals.State): T < S = v
        }

    def run[T: Flat, S](v: T < (Defers & S))(using Trace): T < S =
        this.handle(handler)((), v)

    private val handler = new Handler[Const[Unit], Defers, Any]:
        def resume[T, U: Flat, S2](command: Command[T], k: T => U < (Defers & S2))(using Tag[Defers]) =
            Resume((), k(().asInstanceOf[T]))
end Defers
