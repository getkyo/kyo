package kyo

import kyo.internal.Trace

object Aspects:

    private[kyo] val local = Locals.init(Map.empty[Aspect[?, ?, ?], Cut[?, ?, ?]])

    def init[T, U, S](using Trace): Aspect[T, U, S] =
        init(new Cut[T, U, S]:
            def apply[S2](v: T < S2)(f: T => U < (IOs & S)) =
                v.map(f)
        )

    def init[T, U, S](default: Cut[T, U, S])(using Trace): Aspect[T, U, S] =
        new Aspect[T, U, S](default)

    def chain[T, U, S](head: Cut[T, U, S], tail: Seq[Cut[T, U, S]])(using Trace) =
        tail.foldLeft(head)(_.andThen(_))
end Aspects

import Aspects.*

abstract class Cut[T, U, S]:
    def apply[S2](v: T < S2)(f: T => U < (IOs & S)): U < (IOs & S & S2)

    def andThen(other: Cut[T, U, S])(using Trace): Cut[T, U, S] =
        new Cut[T, U, S]:
            def apply[S2](v: T < S2)(f: T => U < (IOs & S)) =
                Cut.this(v)(other(_)(f))
end Cut

final class Aspect[T, U, S] private[kyo] (default: Cut[T, U, S])(using Trace) extends Cut[T, U, S]:

    def apply[S2](v: T < S2)(f: T => U < (IOs & S)) =
        local.use { map =>
            map.get(this) match
                case Some(a: Cut[T, U, S] @unchecked) =>
                    local.let(map - this) {
                        a(v)(f)
                    }
                case _ =>
                    default(v)(f)
        }

    def sandbox[S](v: T < S)(using Trace): T < (IOs & S) =
        local.use { map =>
            map.get(this) match
                case Some(a: Cut[T, U, S] @unchecked) =>
                    local.let(map - this) {
                        v
                    }
                case _ =>
                    v
        }

    def let[V, S2](a: Cut[T, U, S])(v: V < (IOs & S2))(using Trace): V < (IOs & S & S2) =
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
