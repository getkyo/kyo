package kyo

import izumi.reflect._

import scala.annotation.targetName
import scala.reflect.ClassTag
import scala.util.Failure
import scala.util.NotGiven
import scala.util.Success

import kyo._
import core._
import tries._
import scala.util.Try

object aborts {

  final class Aborts[E] private[aborts] (private val tag: Tag[E])
      extends Effect[[T] =>> Either[E, T], Aborts[E]] {

    private given _tag: Tag[E] = tag

    def run[T, S](v: T > (Aborts[E] with S)): Either[E, T] > S =
      handle(v)

    def get[T, S](v: Either[E, T] > S): T > (Aborts[E] with S) =
      suspend(v)

    def catching[T, S](f: => T > S)(using E => Throwable): T > (Aborts[E] with S) =
      Tries.run(f).map {
        case Failure(ex) if tag.closestClass.isAssignableFrom(ex.getClass) =>
          get(Left(ex.asInstanceOf[E]))
        case v: Try[T] =>
          v.get
      }

    override def accepts[M2[_], E2 <: Effect[M2, E2]](other: Effect[M2, E2]) =
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
      Aborts[E].get(Left(ex))
  }

  /*inline(1)*/
  private given [E: Tag]: Handler[[T] =>> Either[E, T], Aborts[E]] =
    new Handler[[T] =>> Either[E, T], Aborts[E]] {
      def pure[U](v: U) = Right(v)
      def apply[U, V, S2](
          m: Either[E, U],
          f: U => V > (S2 with Aborts[E])
      ): V > (S2 with Aborts[E]) =
        m match {
          case left: Left[_, _] =>
            Aborts[E].get(left.asInstanceOf[Left[E, V]])
          case Right(v) =>
            f(v.asInstanceOf[U])
        }
    }
}
