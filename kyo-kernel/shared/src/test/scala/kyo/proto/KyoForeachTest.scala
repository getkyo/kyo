package kyo.proto

import kyo.Const
import kyo.Tag
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect

object KyoForeachTest:
    sealed trait TestEffect1 extends ArrowEffect[Const[Int], Const[Int]]
    object TestEffect1:
        def apply(i: Int): Int < TestEffect1 =
            ArrowEffect.suspend[Any](Tag[TestEffect1], i)

        def run[A, S](v: A < (TestEffect1 & S)): A < S =
            ArrowEffect.handleCont(Tag[TestEffect1], v)([C] => (input, cont) => cont(input + 1), a => a)
    end TestEffect1

    sealed trait TestEffect2 extends ArrowEffect[Const[String], Const[String]]
    object TestEffect2:
        def apply(s: String): String < TestEffect2 =
            ArrowEffect.suspend[Any](Tag[TestEffect2], s)

        def run[A, S](v: A < (TestEffect2 & S)): A < S =
            ArrowEffect.handleCont(Tag[TestEffect2], v)([C] => (input, cont) => cont(input.toUpperCase), a => a)
    end TestEffect2
end KyoForeachTest
