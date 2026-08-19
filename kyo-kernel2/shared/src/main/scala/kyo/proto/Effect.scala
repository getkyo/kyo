package kyo.proto

abstract class Effect private[proto] ()

object Effect:

    def defer[A, B, S](value: A < S, cont: Arrow[A, B, S]): B < S =
        Kyo.Defer(value, cont)

    def defer[A, B, C, S](value: A < S, contA: Arrow[A, B, S], contB: Arrow[B, C, S]): C < S =
        Kyo.Defer(value, contA, contB)

end Effect
