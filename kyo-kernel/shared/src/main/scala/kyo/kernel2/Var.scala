package kyo.kernel2

import kyo.Const
import kyo.Frame
import kyo.Tag

sealed trait Var[V] extends ArrowEffect[Const[Var.internal.Op[V]], Const[V]]

object Var:

    import internal.*

    inline def get[V](using inline tag: Tag[Var[V]], inline frame: Frame): V < Var[V] =
        ArrowEffect.suspend(tag, Get: Op[V])

    inline def set[V](inline value: V)(using inline tag: Tag[Var[V]], inline frame: Frame): V < Var[V] =
        ArrowEffect.suspend(tag, value: Op[V])

    def run[V, A, S](init: V, v: A < (Var[V] & S))(using tag: Tag[Var[V]]): A < S =
        ArrowEffect.handleLoop(tag, v, init)(
            [C] =>
                (input, state, cont) =>
                    input match
                        case input: Get.type =>
                            Loop.continue(cont(state), state)
                        case input: Update[V] @unchecked =>
                            val nst = input(state)
                            Loop.continue(cont(nst), nst)
                        case input: V @unchecked =>
                            Loop.continue(cont(state), input)
        )
    end run

    object internal:
        type Op[V] = Get.type | V | Update[V]
        object Get
        abstract class Update[V]:
            def apply(v: V): V
    end internal

end Var
