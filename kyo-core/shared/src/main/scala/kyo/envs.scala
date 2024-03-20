package kyo

import kyo.core.*

object Envs:
    case object envs extends Envs[Any]
    def apply[E]: Envs[E] = envs.asInstanceOf[Envs[E]]

class Envs[V] extends Effect[Envs[V]]:
    self =>
    enum Command[+T]:
        case Get

    def get(using Tag[Envs[V]]): V < Envs[V] =
        suspend(this)(Command.Get)

    def use[T, S](f: V => T < S)(using Tag[Envs[V]]): T < (Envs[V] & S) =
        get.map(f)

    def run[T, S](env: V)(value: T < (Envs[V] & S))(using
        Tag[Envs[V]],
        Flat[T < (Envs[V] & S)]
    ): T < S =
        val handler = new Handler[Command, Envs[V], Any]:
            def resume[T2, U: Flat, S](
                command: Command[T2],
                k: T2 => U < (Envs[V] & S)
            ) = handle(k(env.asInstanceOf[T2]))

        handle(handler, value)
    end run

    def layer[Sd](construct: V < Sd)(using Tag[Envs[V]]): Layer[Envs[V], Sd] =
        new Layer[Envs[V], Sd]:
            override def run[T, S](effect: T < (Envs[V] & S))(implicit
                fl: Flat[T < (Envs[V] & S)]
            ): T < (Sd & S) =
                construct.map(e => self.run[T, S](e)(effect))
end Envs
