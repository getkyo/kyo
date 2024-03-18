package kyo

import kyo.core.*

object Vars:
    private case object vars extends Vars[Any]
    def apply[V]: Vars[V] = vars.asInstanceOf[Vars[V]]

class Vars[V] extends Effect[Vars[V]]:
    enum Command[T]:
        case Get               extends Command[V]
        case Set(value: V)     extends Command[Unit]
        case Update(f: V => V) extends Command[Unit]
    end Command

    def get(using Tag[Vars[V]]): V < Vars[V] =
        suspend(this)(Command.Get)

    def set(value: V)(using Tag[Vars[V]]): Unit < Vars[V] =
        suspend(this)(Command.Set(value))

    def update(f: V => V)(using Tag[Vars[V]]): Unit < Vars[V] =
        suspend(this)(Command.Update(f))

    def run[T, S2](state: V)(value: T < (Vars[V] & S2))(
        using
        Flat[T < (Vars[V] & S2)],
        Tag[Vars[V]]
    ): T < S2 =
        handle(handler(state), value)

    private def handler(state: V): Handler[Command, Vars[V], Any] =
        new Handler[Command, Vars[V], Any]:
            def resume[T, U: Flat, S2](command: Command[T], k: T => U < (Vars[V] & S2)) =
                command match
                    case Command.Set(v) =>
                        handle(handler(v), k(()))
                    case Command.Get =>
                        handle(k(state.asInstanceOf[T]))
                    case Command.Update(f) =>
                        handle(handler(f(state)), k(().asInstanceOf[T]))
end Vars
