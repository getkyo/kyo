package kyo

import kyo.core.*
import kyo.internal.Trace

class Envs[+V] extends Effect[Envs[V]]:
    type Command[T] = Tag[T]

object Envs:
    private case object envs extends Envs[Any]
    private def envs[V]: Envs[V] = envs.asInstanceOf[Envs[V]]

    // TODO: Envs.get[String] would normally use a tag of Envs[String]
    // Yet we may provide a Layer with an output of String & Int.
    // This would usually give the handler a tag of Envs[String & Int]
    // However, then handler.tag != kyo.tag in the handle implementation.
    // So we have to use this type erased tag and rely on our `accepts` implementation
    private trait EnvsErased
    private val envsTag: Tag[EnvsErased]   = Tag[EnvsErased]
    private inline given [V]: Tag[Envs[V]] = envsTag.asInstanceOf[Tag[Envs[V]]]

    def get[V](using tag: Tag[V], trace: Trace): V < Envs[V] =
        envs[V].suspend[V](tag)

    def run[V >: Nothing: Tag, T: Flat, S, VS, VR](env: V)(value: T < (Envs[VS] & S))(
        using HasEnvs[V, VS] { type Remainder = VR }
    ): T < (S & VR) =
        envs[V].handle(handler[V])(TypeMap(env), value).asInstanceOf[T < (S & VR)]
    end run

    def runTypeMap[V >: Nothing, T: Flat, S, VS, VR](env: TypeMap[V])(value: T < (Envs[VS] & S))(
        using HasEnvs[V, VS] { type Remainder = VR }
    ): T < (S & VR) =
        envs[V].handle(handler[V])(env, value).asInstanceOf[T < (S & VR)]
    end runTypeMap

    transparent inline def runLayers[T, S, V](inline layers: Layer[?, ?]*)(value: T < (Envs[V] & S)): T < (S & IOs) = {
        Layers.init[V](layers*).run.map: env =>
            runTypeMap(env)(value)
    }.asInstanceOf[T < (S & IOs)]
    end runLayers

    class UseDsl[V >: Nothing]:
        inline def apply[T, S](inline f: V => T < S)(
            using
            inline intersection: Tag.Intersection[V],
            inline tag: Tag[V],
            inline trace: Trace
        ): T < (Envs[V] & S) =
            envs[V].suspend[V, T, S](tag, f)
        end apply
    end UseDsl

    def use[V >: Nothing]: UseDsl[V] =
        new UseDsl[V]

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

    private[kyo] val bypassHasEnvs   = new HasEnvs[Any, Any] {}
    private[kyo] def bypass[A, B, C] = bypassHasEnvs.asInstanceOf[HasEnvs[A, B] { type Remainder = C }]

end Envs
