package kyo

import kyo.core.*
import scala.util.*
import sumsInternal.*

case class Sums[V] private[kyo] (private val tag: Tag[V])
    extends Effect[Sum[V]#Value, Sums[V]]:

    val get: V < Sums[V] =
        this.suspend(Get.asInstanceOf[Sum[V]#Value[V]])

    def add(v: V): V < Sums[V] =
        this.suspend(AddValue(v).asInstanceOf[Sum[V]#Value[V]])

    def set(v: V): V < Sums[V] =
        this.update(_ => v)

    def update(f: V => V): V < Sums[V] =
        this.suspend(UpdateValue(f).asInstanceOf[Sum[V]#Value[V]])

    def run[T, S](v: T < (Sums[V] & S))(implicit
        g: Summer[V],
        f: Flat[T < (Sums[V] & S)]
    ): (T, V) < S =
        run[T, S](g.init)(v)

    def run[T, S](init: V)(v: T < (Sums[V] & S))(implicit
        g: Summer[V],
        f: Flat[T < (Sums[V] & S)]
    ): (T, V) < S =
        var curr = init
        given handler: Handler[Sum[V]#Value, Sums[V], Any] =
            new Handler[Sum[V]#Value, Sums[V], Any]:
                def pure[U: Flat](v: U) = v
                def apply[T, U: Flat, S2](
                    m: Sum[V]#Value[T],
                    f: T => U < (Sums[V] & S2)
                ): U < (S2 & Sums[V]) =
                    m match
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
        this.handle[T, S, Any](v).map {
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
    end run
end Sums

object Sums:
    def apply[V](using t: Tag[V]): Sums[V] =
        new Sums[V](t)

abstract class Summer[V]:
    def init: V
    def add(v1: V, v2: V): V
object Summer:
    def apply[V](_init: V)(_add: (V, V) => V): Summer[V] =
        new Summer[V]:
            def init              = _init
            def add(v1: V, v2: V) = _add(v1, v2)
    given intSummer: Summer[Int]             = Summer(0)(_ + _)
    given longSummer: Summer[Long]           = Summer(0L)(_ + _)
    given doubleSummer: Summer[Double]       = Summer(0d)(_ + _)
    given floatSummer: Summer[Float]         = Summer(0f)(_ + _)
    given stringSummer: Summer[String]       = Summer("")(_ + _)
    given listSummer[T]: Summer[List[T]]     = Summer(List.empty[T])(_ ++ _)
    given setSummer[T]: Summer[Set[T]]       = Summer(Set.empty[T])(_ ++ _)
    given mapSummer[T, U]: Summer[Map[T, U]] = Summer(Map.empty[T, U])(_ ++ _)
end Summer

private[kyo] object sumsInternal:

    private[kyo] case class AddValue[V](v: V)
    private[kyo] case class UpdateValue[V](f: V => V)
    private[kyo] case object Get

    type Sum[V] = {
        type Value[T] >: T // = T | AddValue[V] | UpdateValue[V] | Get.type
    }
end sumsInternal
