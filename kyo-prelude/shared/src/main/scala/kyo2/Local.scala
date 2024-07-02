package kyo2

import kyo.Tag
import kyo2.kernel.*

abstract class Local[T]:

    import Local.State

    val default: T

    def get(using Frame): T < Any =
        ContextEffect.suspendMap(Tag[Local.State], Map.empty)(_.getOrElse(this, default).asInstanceOf[T])

    // TODO Compilation error if inlined
    def use[U, S](f: T => U < S)(using Frame): U < S =
        ContextEffect.suspendMap(Tag[Local.State], Map.empty)(map => f(map.getOrElse(this, default).asInstanceOf[T]))

    def let[U, S](value: T)(v: U < S)(using Frame): U < S =
        ContextEffect.handle(Tag[Local.State], Map(this -> value), _.updated(this, value.asInstanceOf[AnyRef]))(v)

    def update[U, S](f: T => T)(v: U < S)(using Frame): U < S =
        ContextEffect.handle(
            Tag[Local.State],
            Map(this -> f(default)),
            map => map.updated(this, f(map.getOrElse(this, default).asInstanceOf[T]).asInstanceOf[AnyRef])
        )(v)
end Local

object Local:

    inline def init[T](inline defaultValue: T): Local[T] =
        new Local[T]:
            val default: T = defaultValue

    sealed private trait State extends ContextEffect[Map[Local[?], AnyRef]]
end Local
