package kyo

import izumi.reflect._
import kyo.core._

import scala.util._

import sumsInternal._

sealed class Sums[V] private[kyo] (implicit private val tag: Tag[_])
    extends Effect[Sum[V]#Value, Sums[V]] {

  val get: V < Sums[V] =
    suspend(Get.asInstanceOf[Sum[V]#Value[V]])

  def add(v: V): V < Sums[V] =
    suspend(AddValue(v).asInstanceOf[Sum[V]#Value[V]])

  def set(v: V): V < Sums[V] =
    update(_ => v)

  def update(f: V => V): V < Sums[V] =
    suspend(UpdateValue(f).asInstanceOf[Sum[V]#Value[V]])

  def run[T, S](v: T < (Sums[V] & S))(implicit
      g: Summer[V],
      f: Flat[T < (Sums[V] & S)]
  ): (T, V) < S =
    run[T, S](g.init)(v)

  def run[T, S](init: V)(v: T < (Sums[V] & S))(implicit
      g: Summer[V],
      f: Flat[T < (Sums[V] & S)]
  ): (T, V) < S = {
    var curr = init
    implicit def handler: Handler[Sum[V]#Value, Sums[V], Any] =
      new Handler[Sum[V]#Value, Sums[V], Any] {
        def pure[U](v: U) = v
        def apply[T, U, S2](
            m: Sum[V]#Value[T],
            f: T => U < (Sums[V] & S2)
        ): U < (S2 & Sums[V]) =
          m match {
            case AddValue(v) =>
              curr = g.add(curr, v.asInstanceOf[V])
              f(curr.asInstanceOf[T])
            case UpdateValue(u) =>
              curr = u.asInstanceOf[V => V](curr)
              f(curr.asInstanceOf[T])
            case Get =>
              f(curr.asInstanceOf[T])
            case _ =>
              f(m.asInstanceOf[T])
          }
      }
    handle[T, S, Any](v).map {
      case AddValue(v) =>
        curr = g.add(curr, v.asInstanceOf[V])
        curr.asInstanceOf[T]
      case UpdateValue(u) =>
        curr = u.asInstanceOf[V => V](curr)
        curr.asInstanceOf[T]
      case Get =>
        curr.asInstanceOf[T]
      case m =>
        m.asInstanceOf[T]
    }.map((_, curr))
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
  implicit val intSummer: Summer[Int]             = Summer(0)(_ + _)
  implicit val longSummer: Summer[Long]           = Summer(0L)(_ + _)
  implicit val doubleSummer: Summer[Double]       = Summer(0d)(_ + _)
  implicit val floatSummer: Summer[Float]         = Summer(0f)(_ + _)
  implicit val stringSummer: Summer[String]       = Summer("")(_ + _)
  implicit def listSummer[T]: Summer[List[T]]     = Summer(List.empty[T])(_ ++ _)
  implicit def setSummer[T]: Summer[Set[T]]       = Summer(Set.empty[T])(_ ++ _)
  implicit def mapSummer[T, U]: Summer[Map[T, U]] = Summer(Map.empty[T, U])(_ ++ _)
}

private[kyo] object sumsInternal {

  private[kyo] case class AddValue[V](v: V)
  private[kyo] case class UpdateValue[V](f: V => V)
  private[kyo] case object Get

  type Sum[V] = {
    type Value[T] >: T // = T | AddValue[V] | UpdateValue[V] | Get.type
  }
}
