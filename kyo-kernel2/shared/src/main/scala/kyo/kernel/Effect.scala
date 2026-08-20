package kyo.kernel

import kyo.Arrow
import kyo.Frame
import kyo.kernel.internal.*
import scala.annotation.nowarn
import scala.annotation.static

abstract class Effect private[kernel] ()

object Effect:

    /** Holds a computation unevaluated until a drive reaches it. */
    private[kyo] def defer[A, S](f: => A < S)(using Frame): A < S =
        deferInline(f)

    // the node's payload is a by-name method, so the body runs when the evaluator
    // reads it and not when the node is built
    @nowarn("msg=anonymous")
    private[kyo] inline def deferInline[A, S](inline f: => A < S): A < S =
        new Kyo.Defer[A, A, A, S]:
            def value = f
            def contA = Arrow.id[A]
            def contB = Arrow.id[A]

    @static def defer[A, B, S](v: A < S, next: Arrow[A, B, S]): B < S =
        new Kyo.Defer[A, B, B, S]:
            def value = v
            def contA = next
            def contB = Arrow.id[B]

    @static def defer[A, B, C, S](v: A < S, a: Arrow[A, B, S], b: Arrow[B, C, S]): C < S =
        if b eq Arrow.id then
            defer(v, a.asInstanceOf[Arrow[A, C, S]])
        else
            new Kyo.Defer[A, B, C, S]:
                def value = v
                def contA = a
                def contB = b

    // TODO let's add commented signatures for things still missing in the new impl

end Effect
