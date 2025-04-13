package kyo.kernel3

import kyo.Const
import kyo.Frame
import kyo.Tag

sealed trait Var[V] extends ArrowEffect[Const[Var.internal.Op[V]], Const[V]]

object Var:

    import internal.*

    inline def get[V](using inline tag: Tag[Var[V]], inline frame: Frame): V < Var[V] =
        ArrowEffect.suspend(tag, Get: Op[V])

    inline def use[V](using Frame)[A, S](inline f: V => A < S)(using inline tag: Tag[Var[V]]): A < (Var[V] & S) =
        ArrowEffect.suspendWith(tag, Get: Op[V])(f)

    inline def set[V](inline value: V)(using inline tag: Tag[Var[V]], inline frame: Frame): V < Var[V] =
        ArrowEffect.suspend(tag, value: Op[V])

    inline def setWith[V](inline value: V)[A, S](inline f: V => A < S)(using
        inline tag: Tag[Var[V]],
        inline frame: Frame
    ): A < (Var[V] & S) =
        ArrowEffect.suspendWith(tag, value: Op[V])(f)

    def runTuple[V, A, S](init: V)(v: A < (Var[V] & S))(using tag: Tag[Var[V]], frame: Frame): (V, A) < S =
        ArrowEffect.handleLoop(tag, init, v)(
            [C] =>
                (state, input, cont) =>
                    input match
                        case input: Get.type =>
                            Loop.continue(state, cont(state))
                        case input: Update[V] @unchecked =>
                            val nst = input(state)
                            Loop.continue(nst, cont(nst))
                        case input: V @unchecked =>
                            Loop.continue(input, cont(state))
            ,
            (state, v) => (state, v)
        )

    object internal:
        type Op[V] = Get.type | V | Update[V]
        object Get
        abstract class Update[V]:
            def apply(v: V): V
    end internal

end Var
