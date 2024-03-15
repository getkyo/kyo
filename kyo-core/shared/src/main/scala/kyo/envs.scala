package kyo

import kyo.core.*

object Envs:
    private case object Input

    type Env[E] = {
        type Value[T] >: T // = T | Input.type
    }

    def apply[E](using tag: Tag[E]): Envs[E] =
        new Envs[E](tag)
end Envs
import Envs.*

case class Envs[E] private[kyo] (private val tag: Tag[E])
    extends Effect[Env[E]#Value, Envs[E]]:
    self =>

    val get: E < Envs[E] =
        this.suspend(Input.asInstanceOf[Env[E]#Value[E]])

    def use[T, S](f: E => T < S): T < (Envs[E] & S) =
        get.map(f)

    def run[T, S](e: E)(v: T < (Envs[E] & S))(using f: Flat[T < (Envs[E] & S)]): T < S =
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
        this.handle[T, Envs[E] & S, Any](v).map {
            case Input => e.asInstanceOf[T]
            case r     => r
        }.asInstanceOf[T < S]
    end run

    def layer[Sd](construct: E < Sd): Layer[Envs[E], Sd] =
        new Layer[Envs[E], Sd]:
            override def run[T, S](effect: T < (Envs[E] & S))(implicit
                fl: Flat[T < (Envs[E] & S)]
            ): T < (Sd & S) =
                construct.map(e => self.run[T, S](e)(effect))
end Envs
