package aaa

import kyo.Const
import kyo.Frame
import kyo.Tag
import kyo.kernel3.*

trait Var[V] extends ArrowEffect[Const[V => V], Const[V]]

// class ttt: // extends App:

//     val a: Int < Var[Int] = ArrowEffect.suspend(Tag[Var[Int]], _ + 1)

//     val b: String < Var[Int] = a.map(_ + 1).map(_.toString).map(_ + "a")

//     val c: (Int, String) < Any = ArrowEffect.handleLoop(Tag[Var[Int]], 1, b)(
//         handle = [C] => (state, input, cont) => Loop.continue(input(state), cont(state)),
//         done = (state, r) => (state, r)
//     )

//     val d =
//         c.eval

//     println(c)
// end ttt

class A():
    def a = ArrowEffect.suspend(Tag[Var[Int]], _ + 1)

class B(a: A):
    def b = a.a.map(_ + 1).map(_ + 2).map(_ + 3)

class C(b: B):
    def c = ArrowEffect.handleLoop(Tag[Var[Int]], 1, b.b)(
        handle = [C] => (state, input, cont) => Loop.continue(input(state), cont(state)),
        done = (state, r) => (state, r)
    )
end C

object Main extends App:
    C(B(A())).c.eval
