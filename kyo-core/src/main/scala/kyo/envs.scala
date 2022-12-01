package kyo

import izumi.reflect._

import scala.annotation.targetName
import scala.reflect.ClassTag
import scala.util.NotGiven

import core._

object envs {

  private case object Input
  opaque type Env[+E, +T] = T | Input.type

  final class Envs[+E] private[envs] (private val tag: Tag[_])
      extends Effect[[T] =>> Env[E, T]] {
    override def accepts(other: Effect[_]) =
      other match {
        case other: Envs[_] =>
          other.tag.tag <:< tag.tag
        case _ =>
          false
      }
  }

  object Envs {
    final class Let[E, S] private[Envs] (es: E > S, tag: Tag[E]) {
      def apply[T, S2](v: T > (S2 | Envs[E])): T > (S | S2) =
        es { e =>
          given ShallowHandler[[T] =>> Env[E, T], Envs[E]] =
            new ShallowHandler[[T] =>> Env[E, T], Envs[E]] {
              def pure[U](v: U) = v
              def apply[U, V, S2](
                  m: Env[E, U],
                  f: U => V > (S2 | Envs[E])
              ): V > (S2 | Envs[E]) =
                m match {
                  case Input =>
                    f(e.asInstanceOf[U])
                  case _ =>
                    f(m.asInstanceOf[U])
                }
            }
          (v < (new Envs[E](tag))).asInstanceOf[T > S]
        }
    }

    def apply[E](using tag: Tag[E]): E > Envs[E] =
      (Input: Env[E, E]) > (new Envs(tag))
    def let[E, S](es: E > S)(using tag: Tag[E]): Let[E, S] =
      new Let(es, tag)
  }
}
