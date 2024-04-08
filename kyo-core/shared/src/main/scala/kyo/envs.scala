package kyo

import kyo.core.*

class Envs[+V] extends Effect[Envs[V]]:
    type Command[T] = Unit

object Envs:
    private case object envs extends Envs[Any]
    def apply[V]: Envs[V] = envs.asInstanceOf[Envs[V]]

    extension [V](self: Envs[V])

        def get(using Tag[Envs[V]]): V < Envs[V] =
            self.suspend[V](())

        inline def use[T, S](inline f: V => T < S)(
            using inline tag: Tag[Envs[V]]
        ): T < (Envs[V] & S) =
            self.suspend[V, T, S]((), f)

        def run[T: Flat, S, VS, VR](env: V)(value: T < (Envs[VS] & S))(
            using
            HasEnvs[V, VS] { type Remainder = VR },
            Tag[Envs[V]]
        ): T < (S & VR) =
            Envs[V].handle(handler[V])(env, value).asInstanceOf[T < (S & VR)]

    end extension

    private def handler[V]: ResultHandler[V, Const[Unit], Envs[V], Id, Any] =
        cachedHandler.asInstanceOf[ResultHandler[V, Const[Unit], Envs[V], Id, Any]]

    private val cachedHandler =
        new ResultHandler[Any, Const[Unit], Envs[Any], Id, Any]:
            def done[T](st: Any, v: T) = v
            def resume[T, U: Flat, S2](st: Any, command: Unit, k: T => U < (Envs[Any] & S2)) =
                Resume(st, k(st.asInstanceOf[T]))

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
