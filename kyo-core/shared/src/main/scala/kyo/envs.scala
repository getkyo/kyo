package kyo

import kyo.core.*

class Envs[+V] extends Effect[Envs[V]]:
    type Command[T] = Unit

object Envs:
    private case object envs extends Envs[Any]
    def apply[V]: Envs[V] = envs.asInstanceOf[Envs[V]]

    extension [V](self: Envs[V])

        def get(using Tag[Envs[V]]): V < Envs[V] =
            suspend(self)(())

        def use[T, S](f: V => T < S)(using Tag[Envs[V]]): T < (Envs[V] & S) =
            get.map(f)

        def run[T, S, VS, VR](env: V)(value: T < (Envs[VS] & S))(
            using
            HasEnvs[V, VS] { type Remainder = VR },
            Tag[Envs[V]],
            Flat[T < (Envs[VS] & S)]
        ): T < (S & VR) =
            val handler = new Handler[Const[Unit], Envs[V], Any]:
                def resume[T2, U: Flat, S1](
                    command: Unit,
                    k: T2 => U < (Envs[V] & S1)
                ) = handle(k(env.asInstanceOf[T2]))

            handle(handler, value).asInstanceOf[T < (S & VR)]
        end run
    end extension

    /** An effect `Envs[VS]` includes a dependency on `V`, and once `V` has been handled, `Envs[VS]`
      * should be replaced by `Out`
      *
      * @tparam V
      *   the dependency included in `VS`
      * @tparam VS
      *   all of the `Envs` dependencies represented by type intersection
      */
    sealed trait HasEnvs[V, VS]:
        /** Remaining effect type, once the `V` dependency has been provided
          */
        type Remainder
    end HasEnvs

    trait LowPriorityHasEnvs:
        given hasEnvs[V, VR]: HasEnvs[V, V & VR] with
            type Remainder = Envs[VR]

    object HasEnvs extends LowPriorityHasEnvs:
        given isEnvs[V]: HasEnvs[V, V] with
            type Remainder = Any
end Envs
