package kyo

import kyo.core.*
import kyo.core.internal.*
import kyo.internal.Trace

class Defers extends Effect[Const[Unit], Const[Unit]]

abstract private[kyo] class Defer[T, S] extends Suspend[Const[Unit], Const[Unit], Defers, T, T, S & Defers]:
    final def input = ()
    final def tag   = Tag[Defers]

object Defers extends Defers:

    inline def apply[T, S](inline v: => T < S)(using inline _trace: Trace): T < (S & Defers) =
        < {
            new Defer[T, S]:
                def trace = _trace
                def apply(ign: Unit, s: Safepoint, l: Locals.State): T < S =
                    v
        }

    def run[T, S](v: T < (Defers & S))(using tag: Tag[Defers], trace: Trace): T < S =
        handle(tag, v)([C] => (_, cont) => cont(()))

end Defers
