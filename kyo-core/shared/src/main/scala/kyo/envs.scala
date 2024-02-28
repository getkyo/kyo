package kyo

import izumi.reflect.*
import kyo.core.*

object Envs:
    private case object Input

    type Env[E] = {
        type Value[T] >: T // = T | Input.type
    }

    def apply[E](using tag: Tag[E]): Envs[E] =
        new Envs[E]
end Envs
import Envs.*

final class Envs[E] private[kyo] (using private val tag: Tag[?])
    extends Effect[Env[E]#Value, Envs[E]]:
    self =>

    val get: E < Envs[E] =
        suspend(Input.asInstanceOf[Env[E]#Value[E]])

    def use[T, S](f: E => T < S): T < (Envs[E] & S) =
        get.map(f)

    def run[T, S](e: E < S)(v: T < (Envs[E] & S))(using f: Flat[T < (Envs[E] & S)]): T < S =
        e.map { e =>
            given Handler[Env[E]#Value, Envs[E], Any] =
                new Handler[Env[E]#Value, Envs[E], Any]:
                    def pure[U: Flat](v: U) = v
                    def apply[U, V: Flat, S2](
                        m: Env[E]#Value[U],
                        f: U => V < (Envs[E] & S2)
                    ): V < (S2 & Envs[E]) =
                        m match
                            case Input =>
                                f(e.asInstanceOf[U])
                            case _ =>
                                f(m.asInstanceOf[U])
            handle[T, Envs[E] & S, Any](v).asInstanceOf[T < S]
        }
    end run

    override def accepts[M2[_], E2 <: Effect[M2, E2]](other: Effect[M2, E2]) =
        other match
            case other: Envs[?] =>
                other.tag.tag == tag.tag
            case _ =>
                false

    override def toString = s"Envs[${tag.tag.longNameWithPrefix}]"

    def layer[Sd](construct: E < Sd): Layer[Envs[E], Sd] =
        new Layer[Envs[E], Sd]:
            override def run[T, S](effect: T < (Envs[E] & S))(implicit
                fl: Flat[T < (Envs[E] & S)]
            ): T < (Sd & S) =
                construct.map(e => self.run[T, S](e)(effect))
end Envs
