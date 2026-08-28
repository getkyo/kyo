package kyo.proto.kernel

import kyo.proto.Arrow
import kyo.proto.kernel.internal.*
import kyo.proto.kernel.internal.Kyo.*

abstract class Effect private[kernel] ()

object Effect:
    def defer[A, B, S](v: A < S, cont: Arrow[A, B, S]): Kyo[B, S] =
        cont match
            case cont: Arrow.Chain[A, Any, B, S] @unchecked =>
                new Defer[A, Any, B, S]:
                    def value = v
                    def contA = cont.a
                    def contB = cont.b
            case _ =>
                new Defer[A, B, B, S]:
                    def value = v
                    def contA = cont
                    def contB = Arrow.id

    def defer[A, B, C, S](v: A < S, cont1: Arrow[A, B, S], cont2: Arrow[B, C, S]): Kyo[C, S] =
        if cont1.isInstanceOf[Arrow.Id[?]] then
            defer(v, cont2.asInstanceOf[Arrow[A, C, S]])
        else if cont2.isInstanceOf[Arrow.Id[?]] then
            defer(v, cont1.asInstanceOf[Arrow[A, C, S]])
        else
            new Defer[A, B, C, S]:
                def value = v
                def contA = cont1
                def contB = cont2
            end new
    end defer
end Effect
