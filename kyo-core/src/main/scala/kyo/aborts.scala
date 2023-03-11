package kyo

import izumi.reflect._

import scala.annotation.targetName
import scala.reflect.ClassTag
import scala.util.Failure
import scala.util.NotGiven
import scala.util.Success

import core._
import tries._

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

  final class Aborts[+E] private[aborts] (private val _tag: Tag[_])
      extends Effect[[T] =>> Abort[E, T]] {

    given tag[B >: E]: Tag[B] = _tag.asInstanceOf[Tag[B]]

    def run[T, S, B >: E](v: T > (S | Aborts[B])): Abort[B, T] > S =
      v < (this: Aborts[B])

    def toOption[T, S, B >: E](v: T > (S | Aborts[B])): Option[T] > S =
      run[T, S, B](v)((_: Abort[B, T]).toOption)

    def toEither[T, S, B >: E](v: T > (S | Aborts[B])): Either[B, T] > S =
      run[T, S, B](v)((_: Abort[B, T]).toEither)

    def catching[T, S](f: => T > S)(using E => Throwable): T > (S | Aborts[E]) =
      (Tries(f) < Tries) {
        case Failure(ex) if _tag.closestClass.isAssignableFrom(ex.getClass) =>
          (Fail(ex.asInstanceOf[E]): Abort[E, T]) > Aborts[E]
        case v =>
          v.get
      }

    override def accepts(other: Effect[_]) =
      other match {
        case other: Aborts[_] =>
          other.tag.tag == tag.tag || other.tag.tag <:< tag.tag
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
  given [E: Tag]: Handler[[T] =>> Abort[E, T], Aborts[E]] =
    new Handler[[T] =>> Abort[E, T], Aborts[E]] {
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
