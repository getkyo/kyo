package kyo2

object Aspect:

    private[kyo2] val local = Local(Map.empty[Aspect[?, ?, ?], Cut[?, ?, ?]])

    def init[T, U, S](using Frame): Aspect[T, U, S] =
        init(new Cut[T, U, S]:
            def apply[S2](v: T < S2)(f: T => U < S) =
                v.map(f)
        )

    def init[T, U, S](default: Cut[T, U, S])(using Frame): Aspect[T, U, S] =
        new Aspect[T, U, S](default)

    def chain[T, U, S](head: Cut[T, U, S], tail: Seq[Cut[T, U, S]])(using Frame) =
        tail.foldLeft(head)(_.andThen(_))
end Aspect

import Aspect.*

abstract class Cut[T, U, S]:
    def apply[S2](v: T < S2)(f: T => U < S): U < (S & S2)

    def andThen(other: Cut[T, U, S])(using Frame): Cut[T, U, S] =
        new Cut[T, U, S]:
            def apply[S2](v: T < S2)(f: T => U < S) =
                Cut.this(v)(other(_)(f))
end Cut

final class Aspect[T, U, S] private[kyo2] (default: Cut[T, U, S])(using Frame) extends Cut[T, U, S]:

    def apply[S2](v: T < S2)(f: T => U < S) =
        local.use { map =>
            map.get(this) match
                case Some(a: Cut[T, U, S] @unchecked) =>
                    local.let(map - this) {
                        a(v)(f)
                    }
                case _ =>
                    default(v)(f)
        }

    def sandbox[S](v: T < S)(using Frame): T < S =
        local.use { map =>
            map.get(this) match
                case Some(a: Cut[T, U, S] @unchecked) =>
                    local.let(map - this) {
                        v
                    }
                case _ =>
                    v
        }

    def let[V, S2](a: Cut[T, U, S])(v: V < S2)(using Frame): V < (S & S2) =
        local.use { map =>
            val cut =
                map.get(this) match
                    case Some(b: Cut[T, U, S] @unchecked) =>
                        b.andThen(a)
                    case _ =>
                        a
            local.let(map + (this -> cut))(v)
        }
end Aspect
