package kyo2

import Var.internal.*
import kyo.Tag
import kyo2.kernel.*

sealed trait Var[V] extends Effect[Input[V, *], Id]

object Var:

    inline def get[V](using inline tag: Tag[Var[V]]): V < Var[V] =
        use[V](v => v)

    class UseOps[V](ign: Unit) extends AnyVal:
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

    class RunOps[V](ign: Unit) extends AnyVal:
        def apply[A, S](state: V)(v: A < (Var[V] & S))(using tag: Tag[Var[V]], frame: Frame): A < S =
            Effect.handle.state(tag, state, v) {
                [C] =>
                    (input, state, cont) =>
                        input match
                            case input: Get[?] =>
                                (state, cont(state))
                            case input: Set[?] =>
                                (input(), cont(()))
                            case input: Update[?] =>
                                (input(state), cont(()))
            }
    end RunOps

    inline def run[V >: Nothing]: RunOps[V] = RunOps(())

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
