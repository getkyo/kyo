package kyo

import kyo.core.*

object Vars:
    private case object vars extends Vars[Any]
    def apply[V]: Vars[V] = vars.asInstanceOf[Vars[V]]

class Vars[V] extends Effect[Vars[V]]:
    opaque type Command[T] = Op[T]

    private enum Op[T]:
        case Get               extends Op[V]
        case Set(value: V)     extends Op[Unit]
        case Update(f: V => V) extends Op[Unit]
    end Op

    def get(using Tag[Vars[V]]): V < Vars[V] =
        suspend(this)(Op.Get)

    def use[T, S](f: V => T < S)(using Tag[Vars[V]]): T < (Vars[V] & S) =
        get.map(f)

    def set(value: V)(using Tag[Vars[V]]): Unit < Vars[V] =
        suspend(this)(Op.Set(value))

    def update(f: V => V)(using Tag[Vars[V]]): Unit < Vars[V] =
        suspend(this)(Op.Update(f))

    def run[T: Flat, S2](state: V)(value: T < (Vars[V] & S2))(
        using Tag[Vars[V]]
    ): T < S2 =
        handle(handler(state), value)

    private def handler(state: V): Handler[Op, Vars[V], Any] =
        new Handler[Op, Vars[V], Any]:
            def resume[T, U: Flat, S2](op: Op[T], k: T => U < (Vars[V] & S2)) =
                op match
                    case Op.Set(v) =>
                        handle(handler(v), k(()))
                    case Op.Get =>
                        handle(k(state.asInstanceOf[T]))
                    case Op.Update(f) =>
                        handle(handler(f(state)), k(().asInstanceOf[T]))
end Vars
