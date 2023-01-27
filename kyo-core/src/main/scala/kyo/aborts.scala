package kyo

import izumi.reflect._

import scala.annotation.targetName
import scala.reflect.ClassTag
import scala.util.NotGiven

import core._

object aborts {

  private case class Fail[+E](e: E)
  opaque type Abort[+E, +T] = T | Fail[E]

  object Abort {
    /*inline(1)*/
    def success[E, T](v: T): Abort[E, T] = v
    /*inline(1)*/
    def failure[E, T](ex: E): Abort[E, T] = Fail(ex)
  }

  extension [E, T](a: Abort[E, T]) {
    def toOption: Option[T] =
      a match {
        case _: Fail[_] =>
          None
        case v =>
          Some(v.asInstanceOf[T])
      }
    def toEither: Either[E, T] =
      a match {
        case f: Fail[E] @unchecked =>
          Left(f.e)
        case v =>
          Right(v.asInstanceOf[T])
      }
  }

  final class Aborts[+E] private[aborts] (private val tag: Tag[_])
      extends Effect[[T] =>> Abort[E, T]] {
    override def accepts(other: Effect[_]) =
      other match {
        case other: Aborts[_] =>
          other.tag.tag <:< tag.tag
        case _ =>
          false
      }
  }

  object Aborts {
    def apply[E](using tag: Tag[E]): Aborts[E] =
      new Aborts(tag)
    /*inline(1)*/
    def apply[T, E]( /*inline(1)*/ ex: E)(using tag: Tag[E]): T > Aborts[E] =
      (Fail(ex): Abort[E, T]) > Aborts[E]
  }

  /*inline(1)*/
  given [E: Tag]: ShallowHandler[[T] =>> Abort[E, T], Aborts[E]] =
    new ShallowHandler[[T] =>> Abort[E, T], Aborts[E]] {
      def pure[U](v: U) = v
      def apply[U, V, S2](
          m: Abort[E, U],
          f: U => V > (S2 | Aborts[E])
      ): V > (S2 | Aborts[E]) =
        m match {
          case f: Fail[E] @unchecked =>
            (f: Abort[E, V]) > Aborts[E]
          case _ =>
            f(m.asInstanceOf[U])
        }
    }
}
