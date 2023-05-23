package kyo

import izumi.reflect._

import scala.annotation.targetName
import scala.reflect.ClassTag
import scala.util.NotGiven

import core._

object envs {

  private case object Input
  opaque type Env[E, +T] = T | Input.type

  final class Envs[E] private[envs] (using private val tag: Tag[_])
      extends Effect[[T] =>> Env[E, T]] {

    def get: E > Envs[E] =
      val v: Env[E, E] = Input
      v > this

    def let[T, S](e: E)(v: T > (Envs[E] & S)): T > S = {
      given Handler[[T] =>> Env[E, T], Envs[E]] with {
        def pure[U](v: U) = v
        def apply[U, V, S2](
            m: Env[E, U],
            f: U => V > (S2 & Envs[E])
        ): V > (S2 & Envs[E]) =
          m match {
            case Input =>
              f(e.asInstanceOf[U])
            case _ =>
              f(m.asInstanceOf[U])
          }
      }
      (v < this).asInstanceOf[T > S]
    }

    override def accepts(other: Effect[_]) =
      other match {
        case other: Envs[_] =>
          other.tag.tag == tag.tag
        case _ =>
          false
      }
  }

  object Envs {
    def apply[E](using tag: Tag[E]): Envs[E] =
      new Envs[E]
  }
}
