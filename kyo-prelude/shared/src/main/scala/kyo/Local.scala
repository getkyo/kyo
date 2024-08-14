package kyo2

import kyo.Tag
import kyo2.kernel.*

abstract class Local[A]:

    import Local.State

    lazy val default: A

    final def get(using Frame): A < Any =
        ContextEffect.suspendMap(Tag[Local.State], Map.empty)(_.getOrElse(this, default).asInstanceOf[A])

    final def use[B, S](f: A => B < S)(using Frame): B < S =
        ContextEffect.suspendMap(Tag[Local.State], Map.empty)(map => f(map.getOrElse(this, default).asInstanceOf[A]))

    final def let[B, S](value: A)(v: B < S)(using Frame): B < S =
        ContextEffect.handle(Tag[Local.State], Map(this -> value), _.updated(this, value.asInstanceOf[AnyRef]))(v)

    final def update[B, S](f: A => A)(v: B < S)(using Frame): B < S =
        ContextEffect.handle(
            Tag[Local.State],
            Map(this -> f(default)),
            map => map.updated(this, f(map.getOrElse(this, default).asInstanceOf[A]).asInstanceOf[AnyRef])
        )(v)
end Local

object Local:

    inline def init[A](inline defaultValue: A): Local[A] =
        new Local[A]:
            lazy val default: A = defaultValue

    sealed private trait State extends ContextEffect[Map[Local[?], AnyRef]]
end Local
