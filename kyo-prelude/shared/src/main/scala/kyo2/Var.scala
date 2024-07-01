package kyo2

import Var.internal.*
import kyo.Tag
import kyo2.kernel.*

sealed trait Var[V] extends Effect[Input[V, *], Id]

object Var:

    inline def get[V](using inline tag: Tag[Var[V]]): V < Var[V] =
        use[V](identity)

    final class UseOps[V](dummy: Unit) extends AnyVal:
        inline def apply[A, S](inline f: V => A < S)(
            using inline tag: Tag[Var[V]]
        ): A < (Var[V] & S) =
            Effect.suspendMap[V](tag, internal.get[V])(f)
    end UseOps

    inline def use[V]: UseOps[V] = UseOps(())

    inline def set[V](inline value: V)(using inline tag: Tag[Var[V]]): Unit < Var[V] =
        Effect.suspend[Unit](tag, (() => value): Set[V])

    inline def update[V](inline f: V => V)(using inline tag: Tag[Var[V]]): Unit < Var[V] =
        Effect.suspend[Unit](tag, (v => f(v)): Update[V])

    private inline def runWith[V, A, S, B, S2](state: V)(v: A < (Var[V] & S))(
        inline f: (V, A) => B < S2
    )(using inline tag: Tag[Var[V]], inline frame: Frame): B < (S & S2) =
        Effect.handle.state(tag, state, v)(
            [C] =>
                (input, state, cont) =>
                    input match
                        case input: Get[?] =>
                            (state, cont(state))
                        case input: Set[?] =>
                            (input(), cont(()))
                        case input: Update[?] =>
                            (input(state), cont(())),
            done = f
        )

    def run[V, A, S](state: V)(v: A < (Var[V] & S))(using Tag[Var[V]], Frame): A < S =
        runWith(state)(v)((_, result) => result)

    def runTuple[V, A, S](state: V)(v: A < (Var[V] & S))(using Tag[Var[V]], Frame): (V, A) < S =
        runWith(state)(v)((state, result) => (state, result))

    object internal:
        sealed trait Input[V, X]
        case class Get[V]() extends Input[V, V]
        abstract class Set[V] extends Input[V, Unit]:
            def apply(): V
        abstract class Update[V] extends Input[V, Unit]:
            def apply(v: V): V
        private val _get = Get[Any]()
        def get[V]       = _get.asInstanceOf[Get[V]]
    end internal

end Var
