package kyo

import kyo.core.*
import kyo.internal.Trace

sealed trait Envs[+V] extends Effect[Tag, Id]

object Envs:

    // TODO: Envs.get[String] would normally use a tag of Envs[String]
    // Yet we may provide a Layer with an output of String & Int.
    // This would usually give the handler a tag of Envs[String & Int]
    // However, then handler.tag != kyo.tag in the handle implementation.
    // So we have to use this type erased tag and rely on our `accepts` implementation
    private trait EnvsErased
    private val _envsTag: Tag[EnvsErased]          = Tag[EnvsErased]
    private inline def envsErased[V]: Tag[Envs[V]] = _envsTag.asInstanceOf[Tag[Envs[V]]]

    inline def get[V](using inline tag: Tag[V], inline trace: Trace): V < Envs[V] =
        suspend[V](envsErased[V], tag)

    def run[V >: Nothing: Tag, T, S, VS, VR](env: V)(value: T < (Envs[VS] & S))(
        using HasEnvs[V, VS] { type Remainder = VR }
    ): T < (S & VR) =
        runTypeMap(TypeMap(env))(value)

    def runTypeMap[V >: Nothing, T, S, VS, VR](env: TypeMap[V])(value: T < (Envs[VS] & S))(
        using HasEnvs[V, VS] { type Remainder = VR }
    ): T < (S & VR) =
        handle(envsErased[VS], value)(
            accept = [C] => input => env <:< input,
            handle = [C] =>
                (input, cont) => cont(env.get(using input.asInstanceOf[Tag[V]]).asInstanceOf[C])
        )
    end runTypeMap

    case class UseDsl[V >: Nothing](ign: Unit) extends AnyVal:
        inline def apply[T, S](inline f: V => T < S)(
            using
            inline intersection: Tag.Intersection[V],
            inline tag: Tag[V],
            inline trace: Trace
        ): T < (Envs[V] & S) =
            suspend[V](envsErased[V], tag, f)
    end UseDsl

    inline def use[V >: Nothing]: UseDsl[V] = UseDsl(())

    /** An effect `Envs[VS]` includes a dependency on `V`, and once `V` has been handled, `Envs[VS]` should be replaced by `Out`
      *
      * @tparam V
      *   the dependency included in `VS`
      * @tparam VS
      *   all of the `Envs` dependencies represented by type intersection
      */
    sealed trait HasEnvs[V, +VS]:
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
    end HasEnvs

end Envs
