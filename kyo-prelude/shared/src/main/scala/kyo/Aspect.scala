package kyo

object Aspect:

    private[kyo] val local = Local.init(Map.empty[Aspect[?, ?, ?], Cut[?, ?, ?]])

    def init[A, B, S](using Frame): Aspect[A, B, S] =
        init(new Cut[A, B, S]:
            def apply[S2](v: A < S2)(f: A => B < S) =
                v.map(f)
        )

    def init[A, B, S](default: Cut[A, B, S])(using Frame): Aspect[A, B, S] =
        new Aspect[A, B, S](default)

    def chain[A, B, S](head: Cut[A, B, S], tail: Seq[Cut[A, B, S]])(using Frame) =
        tail.foldLeft(head)(_.andThen(_))
end Aspect

import Aspect.*

abstract class Cut[A, B, S]:
    def apply[S2](v: A < S2)(f: A => B < S): B < (S & S2)

    def andThen(other: Cut[A, B, S])(using Frame): Cut[A, B, S] =
        new Cut[A, B, S]:
            def apply[S2](v: A < S2)(f: A => B < S) =
                Cut.this(v)(other(_)(f))
end Cut

final class Aspect[A, B, S] private[kyo] (default: Cut[A, B, S])(using Frame) extends Cut[A, B, S]:

    def apply[S2](v: A < S2)(f: A => B < S) =
        local.use { map =>
            map.get(this) match
                case Some(a: Cut[A, B, S] @unchecked) =>
                    local.let(map - this) {
                        a(v)(f)
                    }
                case _ =>
                    default(v)(f)
        }

    def sandbox[S](v: A < S)(using Frame): A < S =
        local.use { map =>
            map.get(this) match
                case Some(a: Cut[A, B, S] @unchecked) =>
                    local.let(map - this) {
                        v
                    }
                case _ =>
                    v
        }

    def let[V, S2](a: Cut[A, B, S])(v: V < S2)(using Frame): V < (S & S2) =
        local.use { map =>
            val cut =
                map.get(this) match
                    case Some(b: Cut[A, B, S] @unchecked) =>
                        b.andThen(a)
                    case _ =>
                        a
            local.let(map + (this -> cut))(v)
        }
end Aspect
