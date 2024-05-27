package kyo

import kyo.core.*

class Envs[+V] extends Effect[Envs[V]]:
    type Command[T] = Tag[T]

object Envs:
    private case object envs extends Envs[Any]
    private def envs[V]: Envs[V] = envs.asInstanceOf[Envs[V]]

    // Envs.get[String] would normally use a tag of Envs[String]
    // Yet we may provide a Layer with an output of String & Int.
    // This would usually give the handler a tag of Envs[String & Int]
    // However, then handler.tag != kyo.tag in the handle implementation.
    // So we have to use this type erased tag and rely on our `accepts` implementation
    private trait EnvsErased
    private val envsTag: Tag[EnvsErased]   = Tag[EnvsErased]
    private inline given [V]: Tag[Envs[V]] = envsTag.asInstanceOf[Tag[Envs[V]]]

    def get[V](using tag: Tag[V]): V < Envs[V] =
        envs[V].suspend[V](tag)

    class UseDsl[V]:
        inline def apply[T, S](inline f: V => T < S)(
            using
            inline intersection: Tag.Intersection[V],
            inline tag: Tag[V]
        ): T < (Envs[V] & S) =
            envs[V].suspend[V, T, S](tag, f)
        end apply
    end UseDsl

    def use[V]: UseDsl[V] =
        new UseDsl[V]

    /** Runs the Kyo with the given environment.
      *
      * The environment may either be a value or a [[TypeMap]]
      */
    inline def run[In, V, T: Flat, S, VS, VR](env: In)(value: T < (Envs[VS] & S))(
        using
        inline runInput: RunInput[In] { type Out = TypeMap[V] },
        inline hasEnvs: HasEnvs[V, VS] { type Remainder = VR }
    ): T < (S & VR) =
        envs[V].handle(handler[V])(runInput(env), value).asInstanceOf[T < (S & VR)]
    end run

    trait RunInput[-In]:
        type Out
        inline def apply(in: In): Out

    object RunInput:
        // V => TypeMap[V]
        inline given [V](using tag: Tag[V]): RunInput[V] with
            type Out = TypeMap[V]
            inline def apply(in: V): TypeMap[V] = TypeMap(in)

        // TypeMap[V] => TypeMap[V]
        inline given [V]: RunInput[TypeMap[V]] with
            type Out = TypeMap[V]
            inline def apply(in: TypeMap[V]): TypeMap[V] = in
    end RunInput

    private def handler[V]: ResultHandler[TypeMap[V], Tag, Envs[V], Id, Any] =
        cachedHandler.asInstanceOf[ResultHandler[TypeMap[V], Tag, Envs[V], Id, Any]]

    private val cachedHandler =
        new ResultHandler[TypeMap[Any], Tag, Envs[Any], Id, Any]:
            override def accepts[T](st: TypeMap[Any], command: Tag[T]): Boolean =
                st <:< command

            def done[T](st: TypeMap[Any], v: T)(using Tag[Envs[Any]]) = v

            def resume[T, U: Flat, S2](st: TypeMap[Any], command: Tag[T], k: T => U < (Envs[Any] & S2))(using Tag[Envs[Any]]) =
                Resume(st, k(st.get(using command.asInstanceOf[Tag[Any]]).asInstanceOf[T]))

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
