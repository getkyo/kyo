package kyo

import kyo.core.*

object Sums:
    private object sums extends Sums[Any]
    def apply[V]: Sums[V] = sums.asInstanceOf[Sums[V]]

class Sums[V] extends Effect[Sums[V]]:
    type Command[T] = V

    def add(v: V)(using Tag[Sums[V]]): Unit < Sums[V] =
        suspend(Sums[V])(v)

    def run[T, S](v: T < (Sums[V] & S))(
        using
        g: Summer[V],
        f: Flat[T < (Sums[V] & S)],
        t: Tag[Sums[V]]
    ): (V, T) < S =
        run(g.init)(v)

    def run[T, S](init: V)(v: T < (Sums[V] & S))(
        using
        g: Summer[V],
        f: Flat[T < (Sums[V] & S)],
        t: Tag[Sums[V]]
    ): (V, T) < S =
        handle[Const[V], [T] =>> (V, T), Sums[V], T, Any, S](handler(init), v)

    private def handler(state: V)(
        using summer: Summer[V]
    ): ResultHandler[Const[V], [T] =>> (V, T), Sums[V], Any] =
        new ResultHandler[Const[V], [T] =>> (V, T), Sums[V], Any]:
            def pure[T](v: T) = (state, v)
            def resume[T, U: Flat, S](command: V, k: T => U < (Sums[V] & S)) =
                handle(handler(summer.add(state, command)), k(().asInstanceOf[T]))
end Sums

abstract class Summer[V]:
    def init: V
    def add(v1: V, v2: V): V
    def result(v: V): V
end Summer

object Summer:
    def apply[V](_init: V)(_add: (V, V) => V, _result: V => V): Summer[V] =
        new Summer[V]:
            def init              = _init
            def add(v1: V, v2: V) = _add(v1, v2)
            def result(v: V): V   = _result(v)

    given intSummer: Summer[Int]             = Summer(0)(_ + _, identity)
    given longSummer: Summer[Long]           = Summer(0L)(_ + _, identity)
    given doubleSummer: Summer[Double]       = Summer(0d)(_ + _, identity)
    given floatSummer: Summer[Float]         = Summer(0f)(_ + _, identity)
    given stringSummer: Summer[String]       = Summer("")(_ + _, identity)
    given listSummer[T]: Summer[List[T]]     = Summer(List.empty[T])((a, b) => b ++ a, _.reverse)
    given setSummer[T]: Summer[Set[T]]       = Summer(Set.empty[T])(_ ++ _, identity)
    given mapSummer[T, U]: Summer[Map[T, U]] = Summer(Map.empty[T, U])(_ ++ _, identity)
end Summer
