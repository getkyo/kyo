package kyo.kernel3

import kyo.Const
import kyo.Tag

class ttt:
    trait Var[V] extends ArrowEffect[Const[V => V], Const[V]]

    val a = ArrowEffect.suspend(Tag[Var[Int]], _ + 1)
    val b = a.map(_ + 1).map(_.toString)
end ttt
