package kyo

import kyo.core.*
import varsInternal.*

class Vars[V] extends Effect[Op[V, *], Vars[V]]:

    private[Vars] val get = Op.Get[V]()

    private val handler =
        new ResultHandler[V, [T] =>> Op[V, T], Vars[V], Id, Any]:
            def done[T](st: V, v: T) = v
            def resume[T, U: Flat, S2](st: V, op: Op[V, T], k: T => U < (Vars[V] & S2)) =
                op match
                    case _: Op.Get[V] @unchecked =>
                        Resume(st, k(st.asInstanceOf[T]))
                    case Op.Set(v: V @unchecked) =>
                        Resume(v, k(().asInstanceOf[T]))
                    case Op.Update(f: (V => V) @unchecked) =>
                        Resume(f(st), k(().asInstanceOf[T]))
end Vars

object Vars:
    private case object vars extends Vars[Any]
    private def vars[V]: Vars[V] = vars.asInstanceOf[Vars[V]]

    def get[V](using Tag[Vars[V]]): V < Vars[V] =
        vars[V].suspend[V](vars[V].get)

    class UseDsl[V]:
        inline def apply[T, S](inline f: V => T < S)(using inline tag: Tag[Vars[V]]): T < (Vars[V] & S) =
            vars[V].suspend[V, T, S](vars[V].get, f)

    def use[V >: Nothing]: UseDsl[V] = new UseDsl[V]

    def set[V](value: V)(using Tag[Vars[V]]): Unit < Vars[V] =
        vars[V].suspend[Unit](Op.Set[V](value))

    def update[V](f: V => V)(using Tag[Vars[V]]): Unit < Vars[V] =
        vars[V].suspend[Unit](Op.Update[V](f))

    class RunDsl[V]:
        def apply[T: Flat, S2](state: V)(value: T < (Vars[V] & S2))(
            using Tag[Vars[V]]
        ): T < S2 =
            vars[V].handle(vars[V].handler)(state, value)
    end RunDsl

    def run[V >: Nothing]: RunDsl[V] = new RunDsl[V]

end Vars

// Moving to Vars.internal crashes the compiler
object varsInternal:
    enum Op[-V, +T]:
        case Get[V]()             extends Op[V, V]
        case Set[V](v: V)         extends Op[V, Unit]
        case Update[V](f: V => V) extends Op[V, Unit]
    end Op
end varsInternal
