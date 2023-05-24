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

  final class Aborts[E] private[aborts] (private val tag: Tag[E])
      extends Effect[[T] =>> Either[E, T]] {

    private given _tag: Tag[E] = tag

    def run[T, S](v: T > (Aborts[E] & S)): Either[E, T] > S =
      v < (this: Aborts[E])

    def get[T, S](v: Either[E, T] > S): T > (Aborts[E] & S) =
      v > this

    def catching[T, S](f: => T > S)(using E => Throwable): T > (Aborts[E] & S) =
      Tries.run(f).map {
        case Failure(ex) if tag.closestClass.isAssignableFrom(ex.getClass) =>
          val v: Either[E, T] = Left(ex.asInstanceOf[E])
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
      Left(ex) > Aborts[E]
  }

  /*inline(1)*/
  private given [E: Tag]: Handler[[T] =>> Either[E, T], Aborts[E]] =
    new Handler[[T] =>> Either[E, T], Aborts[E]] {
      def pure[U](v: U) = Right(v)
      def apply[U, V, S2](
          m: Either[E, U],
          f: U => V > (S2 & Aborts[E])
      ): V > (S2 & Aborts[E]) =
        m match {
          case left: Left[_, _] =>
            left.asInstanceOf[Left[E, V]] > Aborts[E]
          case Right(v) =>
            f(v.asInstanceOf[U])
        }
    }
}
