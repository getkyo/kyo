package kyo

import izumi.reflect._

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import core._
import tries._
import ios._

object sums {

  private case class AddValue[V](v: V)
  private case class SetValue[V](v: V)
  private case object Get

  opaque type Sum[V, +T] = Any // T | AddValue[V] | SetValue[V] | Get.type

  final class Sums[V] private[sums] (using private val tag: Tag[_])
      extends Effect[[T] =>> Sum[V, T]] {

    val get: V > Sums[V] =
      val v: Sum[V, V] = Get
      v > this

    def add(v: V): V > Sums[V] =
      val s: Sum[V, V] = AddValue(v)
      s > this

    def set(v: V): V > Sums[V] =
      val s: Sum[V, V] = SetValue(v)
      s > this

    def run[T, S](v: T > (S & Sums[V]))(using
        g: Summer[V],
        tag: Tag[V]
    ): T > (S & IOs) = {
      var curr = g.init
      given Handler[[T] =>> Sum[V, T], Sums[V]] with {
        def pure[U](v: U) = v
        def apply[T, U, S2](
            m: Sum[V, T],
            f: T => U > (S2 & Sums[V])
        ): U > (S2 & Sums[V]) =
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
      IOs.ensure(g.drop(curr)) {
        (v < Sums[V]).map {
          case AddValue(v) =>
            curr = g.add(curr, v.asInstanceOf[V])
            curr.asInstanceOf[T]
          case Get =>
            curr.asInstanceOf[T]
          case m =>
            m.asInstanceOf[T]
        }
      }
    }

    override def accepts(other: Effect[_]) =
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
    given [T]: Summer[Set[T]]  = Summer(Set.empty, _ ++ _)
  }
}
