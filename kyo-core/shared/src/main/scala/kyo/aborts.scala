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

  private case class Fail[E](e: E)
  opaque type Abort[E, +T] = Any // T | Fail[E]

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

  final class Aborts[E] private[aborts] (private val tag: Tag[E])
      extends Effect[[T] =>> Abort[E, T]] {

    private given _tag: Tag[E] = tag

    def run[T, S](v: T > (S & Aborts[E])): Abort[E, T] > S =
      v < (this: Aborts[E])

    def get[T, S](v: Abort[E, T] > S): T > (S & Aborts[E]) =
      v > this

    def toOption[T, S](v: T > (S & Aborts[E])): Option[T] > S =
      run[T, S](v).map((_: Abort[E, T]).toOption)

    def toEither[T, S](v: T > (S & Aborts[E])): Either[E, T] > S =
      run[T, S](v).map((_: Abort[E, T]).toEither)

    def catching[T, S](f: => T > S)(using E => Throwable): T > (S & Aborts[E]) =
      Tries.run(f).map {
        case Failure(ex) if tag.closestClass.isAssignableFrom(ex.getClass) =>
          val v: Abort[E, T] = Fail(ex.asInstanceOf[E])
          v > Aborts[E]
        case v =>
          v.get
      }

    override def accepts(other: Effect[_]) =
      other match {
        case other: Aborts[_] =>
          other.tag.tag == tag.tag
        case _ =>
          false
      }
  }

  object Aborts {
    def apply[E](using tag: Tag[E]): Aborts[E] =
      new Aborts(tag)
    /*inline(1)*/
    def apply[T, E]( /*inline(1)*/ ex: E)(using tag: Tag[E]): T > Aborts[E] =
      val v: Abort[E, T] = Fail(ex)
      v > Aborts[E]
  }

  /*inline(1)*/
  private given [E: Tag]: Handler[[T] =>> Abort[E, T], Aborts[E]] =
    new Handler[[T] =>> Abort[E, T], Aborts[E]] {
      def pure[U](v: U) = v
      def apply[U, V, S2](
          m: Abort[E, U],
          f: U => V > (S2 & Aborts[E])
      ): V > (S2 & Aborts[E]) =
        m match {
          case f: Fail[E] @unchecked =>
            val v: Abort[E, V] = f
            v > Aborts[E]
          case _ =>
            f(m.asInstanceOf[U])
        }
    }
}
