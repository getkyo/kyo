package kyo

import izumi.reflect._

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import core._
import tries._
import ios._

object sums {

  private case class Add[V](v: V)
  private case object Get

  opaque type Sum[V, +T] = T | Add[V] | Get.type

  final class Sums[V] private[sums] (using private val tag: Tag[_])
      extends Effect[[T] =>> Sum[V, T]] {

    override def accepts(other: Effect[_]) =
      other match {
        case other: Sums[_] =>
          other.tag.tag == tag.tag
        case _ =>
          false
      }
  }

  object Sums {
    def add[V: Tag](v: V): V > Sums[V] =
      Add(v) > Sums[V]

    def get[V: Tag]: V > Sums[V] =
      (Get: Sum[V, V]) > Sums[V]

    class DropDsl[V] {
      def apply[T, S](v: T > (S | Sums[V]))(using
          g: Summer[V],
          tag: Tag[V]
      ): T > (S | IOs) = {
        var curr = g.init
        given Handler[[T] =>> Sum[V, T], Sums[V]] with {
          def pure[U](v: U) = v
          def apply[T, U, S2](
              m: Sum[V, T],
              f: T => U > (S2 | Sums[V])
          ): U > (S2 | Sums[V]) =
            m match {
              case Add(v) =>
                curr = g.add(curr, v.asInstanceOf[V])
                f(curr.asInstanceOf[T])
              case Get =>
                f(curr.asInstanceOf[T])
              case _ =>
                f(m.asInstanceOf[T])
            }
        }
        IOs.ensure(g.drop(curr)) {
          (v < Sums[V]) {
            case Add(v) =>
              curr = g.add(curr, v.asInstanceOf[V])
              curr.asInstanceOf[T]
            case Get =>
              curr.asInstanceOf[T]
            case m =>
              m.asInstanceOf[T]
          }
        }
      }
    }

    def drop[V] = DropDsl[V]
  }

  trait Summer[V] {
    def init: V
    def add(v1: V, v2: V): V
    def drop(v: V): Unit > IOs = IOs.unit
  }
  object Summer {
    def apply[V](_init: V, _add: (V, V) => V): Summer[V] =
      new Summer[V] {
        def init              = _init
        def add(v1: V, v2: V) = _add(v1, v2)
      }
    given Summer[Int]          = Summer(0, _ + _)
    given Summer[Long]         = Summer(0L, _ + _)
    given Summer[Double]       = Summer(0d, _ + _)
    given Summer[Float]        = Summer(0f, _ + _)
    given Summer[String]       = Summer("", _ + _)
    given [T]: Summer[List[T]] = Summer(Nil, _ ++ _)
  }
}
