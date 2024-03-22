package kyo

import kyo.core.*

class Envs[+V] extends Effect[Envs[V]]:
    type Command[T] = Unit

object Envs:
    case object Envs extends Envs[Any]
    def apply[V]: Envs[V] = Envs.asInstanceOf[Envs[V]]

    type Elide[V] =
        V match
            case Nothing => Any
            case V       => Envs[V]

    extension [V](self: Envs[V])

        def get(using Tag[Envs[V]]): V < Envs[V] =
            suspend(self)(())

        def use[T, S](f: V => T < S)(using Tag[Envs[V]]): T < (Envs[V] & S) =
            get.map(f)

        def run[T, S, V2](env: V)(value: T < (Envs[V & V2] & S))(
            using
            Tag[Envs[V]],
            Flat[T < (Envs[V & V2] & S)]
        ): T < (S & Elide[V2]) =
            val handler = new Handler[Const[Unit], Envs[V], Any]:
                def resume[T2, U: Flat, S](
                    command: Unit,
                    k: T2 => U < (Envs[V] & S)
                ) = handle(k(env.asInstanceOf[T2]))

            handle(handler, value).asInstanceOf[T < (S & Elide[V2])]
        end run

        def layer[Sd](construct: V < Sd)(using Tag[Envs[V]]): Layer[Envs[V], Sd] =
            new Layer[Envs[V], Sd]:
                override def run[T, S](effect: T < (Envs[V] & S))(implicit
                    fl: Flat[T < (Envs[V] & S)]
                ): T < (Sd & S) =
                    construct.map(e => self.run[T, S, Nothing](e)(effect))
    end extension
end Envs
