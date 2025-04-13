package kyo.kernel3

import <.internal.*
import kyo.Frame

abstract class Effect private[kernel3] ()

object Effect:

    inline def defer[A, S](inline v: => A < S)(using inline frame: Frame): A < S =
        SuspendDefer(Arrow.init(_ => v))

    private[kernel3] def defer[A, B, S](value: A, arrow: Arrow[A, B, S])(using Frame): B < S =
        defer(arrow(value))

end Effect
