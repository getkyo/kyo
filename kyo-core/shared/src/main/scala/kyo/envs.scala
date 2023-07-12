package kyo

import izumi.reflect._

import scala.reflect.ClassTag

import kyo.core._

object envs {

  private case object Input

  type Env[E] = {
    type Value[T] >: T // = T | Input.type
  }

  final class Envs[E] private[envs] (implicit private val tag: Tag[_])
      extends Effect[Env[E]#Value, Envs[E]] {

    def get: E > Envs[E] =
      suspend(Input.asInstanceOf[Env[E]#Value[E]])

    def run[T, S](e: E)(v: T > (Envs[E] with S)): T > S = {
      implicit val handler: Handler[Env[E]#Value, Envs[E]] =
        new Handler[Env[E]#Value, Envs[E]] {
          def pure[U](v: U) = v
          def apply[U, V, S2](
              m: Env[E]#Value[U],
              f: U => V > (Envs[E] with S2)
          ): V > (S2 with Envs[E]) =
            m match {
              case Input =>
                f(e.asInstanceOf[U])
              case _ =>
                f(m.asInstanceOf[U])
            }
        }
      handle[T, Envs[E] with S](v).asInstanceOf[T > S]
    }

    override def accepts[M2[_], E2 <: Effect[M2, E2]](other: Effect[M2, E2]) =
      other match {
        case other: Envs[_] =>
          other.tag.tag == tag.tag
        case _ =>
          false
      }
  }

  object Envs {
    def apply[E](implicit tag: Tag[E]): Envs[E] =
      new Envs[E]
  }
}
