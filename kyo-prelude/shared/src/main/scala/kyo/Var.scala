package kyo

import Vars.internal.*
import kyo.core.*
import kyo.internal.Trace

class Vars[V] extends Effect[Vars[V]]:
    type Command[T] = Op[V]

    private val handler =
        new ResultHandler[V, Const[Op[V]], Vars[V], Id, Any]:
            def done[T](st: V, v: T)(using Tag[Vars[V]]) = v
            def resume[T, U: Flat, S2](st: V, op: Op[V], k: T => U < (Vars[V] & S2))(using Tag[Vars[V]]) =
                op match
                    case _: Get.type =>
                        Resume(st, k(st.asInstanceOf[T]))
                    case Set(v) =>
                        Resume(v, k(().asInstanceOf[T]))
                    case Update(f) =>
                        Resume(f(st), k(().asInstanceOf[T]))
end Vars

object Vars:
    private case object vars extends Vars[Any]
    private def vars[V]: Vars[V] = vars.asInstanceOf[Vars[V]]
    object internal:
        case object Get
        case class Set[V](v: V)
        case class Update[V](f: V => V)
        type Op[V] = Get.type | Set[V] | Update[V]
    end internal

    def get[V](using Tag[Vars[V]])(using Trace): V < Vars[V] =
        vars[V].suspend[V](Get)

    class UseDsl[V]:
        inline def apply[T, S](inline f: V => T < S)(using inline tag: Tag[Vars[V]], inline trace: Trace): T < (Vars[V] & S) =
            vars[V].suspend[V, T, S](Get, f)

    def use[V >: Nothing]: UseDsl[V] = new UseDsl[V]

    def set[V](value: V)(using Tag[Vars[V]], Trace): Unit < Vars[V] =
        vars[V].suspend[Unit](Set(value))

    def update[V](f: V => V)(using Tag[Vars[V]], Trace): Unit < Vars[V] =
        vars[V].suspend[Unit](Update(f))

    class RunDsl[V]:
        def apply[T: Flat, S2](state: V)(value: T < (Vars[V] & S2))(
            using
            Tag[Vars[V]],
            Trace
        ): T < S2 =
            vars[V].handle(vars[V].handler)(state, value)
    end RunDsl

    def run[V >: Nothing]: RunDsl[V] = new RunDsl[V]

end Vars
