package kyo.proto

import scala.annotation.static

abstract class Effect private[proto] ()

object Effect:

    @static def defer[A, B, S](v: A < S, next: Arrow[A, B, S]): B < S =
        new Kyo.Defer[A, B, B, S]:
            def value = v
            def contA = next
            def contB = Arrow.id[B]

    @static def defer[A, B, C, S](v: A < S, a: Arrow[A, B, S], b: Arrow[B, C, S]): C < S =
        if b eq Arrow.id then
            defer(v, a.asInstanceOf[Arrow[A, C, S]])
        else if a eq Arrow.id then
            defer(v, b.asInstanceOf[Arrow[A, C, S]])
        else
            new Kyo.Defer[A, B, C, S]:
                def value = v
                def contA = a
                def contB = b

end Effect
