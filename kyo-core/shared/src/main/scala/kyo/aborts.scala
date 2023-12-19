package kyo

import izumi.reflect._

import scala.language.higherKinds
import scala.reflect.ClassTag
import scala.util.Failure
import scala.util.Success

import kyo._
import core._
import tries._
import layers._
import scala.util.Try

object aborts {

  type Abort[E] = {
    type Value[T] = Either[E, T]
  }

  object Aborts {

    def apply[E](implicit tag: Tag[E]): Aborts[E] =
      new Aborts(tag)

    def apply[T, E](ex: E)(implicit tag: Tag[E]): T < Aborts[E] =
      Aborts[E].get(Left(ex))
  }

  final class Aborts[E] private[aborts] (private val tag: Tag[E])
      extends Effect[Abort[E]#Value, Aborts[E]] { self =>

    private implicit def _tag: Tag[E] = tag

    def fail[T, S](e: E < S): T < (Aborts[E] with S) =
      e.map(e => suspend(Left(e)))

    def run[T, S](
        v: => T < (Aborts[E] with S)
    )(implicit
        flat: Flat[T < Aborts[E] with S]
    ): Either[E, T] < S =
      handle[T, S, Any](catching(v))

    def get[T, S](v: => Either[E, T] < S): T < (Aborts[E] with S) =
      catching(v).map {
        case Right(value) => value
        case e            => suspend(e)
      }

    def catching[T, S](f: => T < S): T < (Aborts[E] with S) =
      try {
        f
      } catch {
        case ex if (tag.closestClass.isAssignableFrom(ex.getClass)) =>
          fail(ex.asInstanceOf[E])
      }

    override def accepts[M2[_], E2 <: Effect[M2, E2]](other: Effect[M2, E2]) =
      other match {
        case other: Aborts[_] =>
          other.tag.tag == tag.tag
        case _ =>
          false
      }

    implicit def handler[E](implicit tag: Tag[E]): Handler[Abort[E]#Value, Aborts[E], Any] =
      new Handler[Abort[E]#Value, Aborts[E], Any] {

        val aborts = Aborts[E]

        def pure[U](v: U) = Right(v)

        override def handle[T](ex: Throwable): T < Aborts[E] =
          if (tag.closestClass.isAssignableFrom(ex.getClass)) {
            aborts.fail(ex.asInstanceOf[E])
          } else {
            throw ex
          }

        def apply[U, V, S2](
            m: Either[E, U],
            f: U => V < (Aborts[E] with S2)
        ): V < (S2 with Aborts[E]) =
          m match {
            case left: Left[_, _] =>
              aborts.get(left.asInstanceOf[Left[E, V]])
            case Right(v) =>
              f(v)
          }
      }

    override def toString = s"Aborts[${tag.tag.longNameWithPrefix}]"

    def layer[Se](handle: E => Nothing < Se): Layer[Aborts[E], Se] =
      new Layer[Aborts[E], Se] {
        override def run[T, S](
            effect: T < (Aborts[E] with S)
        )(
            implicit flat: Flat[T < (Aborts[E] with S)]
        ): T < (S with Se) =
          self.run[T, S](effect)(flat).map {
            case Left(err) => handle(err)
            case Right(t)  => t
          }
      }
  }

}
