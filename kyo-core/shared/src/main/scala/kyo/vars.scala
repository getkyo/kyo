package kyo

import kyo.core.*

object Vars:
    private case object vars extends Vars[Any]
    def apply[V]: Vars[V] = vars.asInstanceOf[Vars[V]]
    object internal:
        case object Get
        case class Set[V](v: V)
        case class Update[V](f: V => V)
        type Op[V] = Get.type | Set[V] | Update[V]
    end internal
end Vars

import Vars.internal.*

class Vars[V] extends Effect[Vars[V]]:
    type Command[T] = Op[V]

    def get(using Tag[Vars[V]]): V < Vars[V] =
        this.suspend[V](Get)

    inline def use[T, S](inline f: V => T < S)(using inline tag: Tag[Vars[V]]): T < (Vars[V] & S) =
        this.suspend[V, T, S](Get, f)

    def set(value: V)(using Tag[Vars[V]]): Unit < Vars[V] =
        this.suspend[Unit](Set(value))

    def update(f: V => V)(using Tag[Vars[V]]): Unit < Vars[V] =
        this.suspend[Unit](Update(f))

    def run[T: Flat, S2](state: V)(value: T < (Vars[V] & S2))(
        using Tag[Vars[V]]
    ): T < S2 =
        this.handle(handler)(state, value)

    private val handler =
        new ResultHandler[V, Const[Op[V]], Vars[V], Id, Any]:
            def done[T](st: V, v: T) = v
            def resume[T, U: Flat, S2](st: V, op: Op[V], k: T => U < (Vars[V] & S2)) =
                op match
                    case _: Get.type =>
                        Resume(st, k(st.asInstanceOf[T]))
                    case Set(v) =>
                        Resume(v, k(().asInstanceOf[T]))
                    case Update(f) =>
                        Resume(f(st), k(().asInstanceOf[T]))
end Vars
