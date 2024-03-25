package kyo

import kyo.core.*

opaque type Envs[+V] <: IOs = IOs

class EnvsDsl[V](using tag: Tag[V]) extends AnyVal:
    self =>
    import Envs.*
    def get: V < Envs[V] =
        local.use(_(tag).asInstanceOf[V])
    def use[T, S](f: V => T < S): T < (Envs[V] & S) =
        local.use(m => f(m(tag).asInstanceOf[V]))
    def run[T, S, V2](env: V)(value: T < (Envs[V & V2] & S))(using
        Tag[Envs[V]]
    ): T < (S & Elide[V2]) =
        local.update(_ + (tag.asInstanceOf[Tag[Any]] -> env))(value)
            .asInstanceOf[T < (S & Elide[V2])]
    def layer[Sd](construct: V < Sd): Layer[Envs[V], Sd & IOs] =
        new Layer[Envs[V], Sd & IOs]:
            override def run[T, S](effect: T < (Envs[V] & S))(
                using fl: Flat[T < (Envs[V] & S)]
            ) =
                construct.map(e => self.run[T, S, Nothing](e)(effect))
end EnvsDsl

object Envs:

    def apply[V: Tag]: EnvsDsl[V] = EnvsDsl[V]

    private[kyo] val local =
        Locals.init(Map.empty[Tag[Any], Any])

    type Elide[V] =
        V match
            case Nothing => IOs
            case V       => Envs[V]
end Envs
