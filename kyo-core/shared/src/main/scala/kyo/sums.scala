package kyo

import izumi.reflect._

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import kyo.core._

object sums {

  private case class AddValue[V](v: V)
  private case class SetValue[V](v: V)
  private case object Get

  type Sum[V] = {
    type Value[T] >: T // = T | AddValue[V] | SetValue[V] | Get.type
  }

  final class Sums[V] private[sums] (implicit private val tag: Tag[_])
      extends Effect[Sum[V]#Value, Sums[V]] {

    val get: V > Sums[V] =
      suspend(Get.asInstanceOf[Sum[V]#Value[V]])

    def add(v: V): V > Sums[V] =
      suspend(AddValue(v).asInstanceOf[Sum[V]#Value[V]])

    def set(v: V): V > Sums[V] =
      suspend(SetValue(v).asInstanceOf[Sum[V]#Value[V]])

    def run[T, S](v: T > (Sums[V] with S))(implicit
        g: Summer[V],
        tag: Tag[V]
    ): T > S = {
      var curr = g.init
      implicit def handler: Handler[Sum[V]#Value, Sums[V]] =
        new Handler[Sum[V]#Value, Sums[V]] {
          def pure[U](v: U) = v
          def apply[T, U, S2](
              m: Sum[V]#Value[T],
              f: T => U > (Sums[V] with S2)
          ): U > (S2 with Sums[V]) =
            m match {
              case AddValue(v) =>
                curr = g.add(curr, v.asInstanceOf[V])
                f(curr.asInstanceOf[T])
              case SetValue(v) =>
                curr = v.asInstanceOf[V]
                f(curr.asInstanceOf[T])
              case Get =>
                f(curr.asInstanceOf[T])
              case _ =>
                f(m.asInstanceOf[T])
            }
        }
      handle[T, S](v).map {
        case AddValue(v) =>
          curr = g.add(curr, v.asInstanceOf[V])
          curr.asInstanceOf[T]
        case Get =>
          curr.asInstanceOf[T]
        case m =>
          m.asInstanceOf[T]
      }
    }

    override def accepts[M2[_], E2 <: Effect[M2, E2]](other: Effect[M2, E2]) =
      other match {
        case other: Sums[_] =>
          other.tag.tag == tag.tag
        case _ =>
          false
      }
  }

  object Sums {
    def apply[V: Tag]: Sums[V] =
      new Sums[V]
  }

  abstract class Summer[V] {
    def init: V
    def add(v1: V, v2: V): V
  }
  object Summer {
    def apply[V](_init: V)(_add: (V, V) => V): Summer[V] =
      new Summer[V] {
        def init              = _init
        def add(v1: V, v2: V) = _add(v1, v2)
      }
    implicit val intSummer: Summer[Int]         = Summer(0)(_ + _)
    implicit val longSummer: Summer[Long]       = Summer(0L)(_ + _)
    implicit val doubleSummer: Summer[Double]   = Summer(0d)(_ + _)
    implicit val floatSummer: Summer[Float]     = Summer(0f)(_ + _)
    implicit val stringSummer: Summer[String]   = Summer("")(_ + _)
    implicit def listSummer[T]: Summer[List[T]] = Summer(List.empty[T])(_ ++ _)
    implicit def setSummer[T]: Summer[Set[T]]   = Summer(Set.empty[T])(_ ++ _)
  }
}
