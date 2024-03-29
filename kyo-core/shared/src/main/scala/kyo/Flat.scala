package kyo

import internal.FlatImplicits

sealed trait Flat[-T]:
    def derive[S]: Flat[T < S] =
        this.asInstanceOf[Flat[T < S]]

object Flat extends FlatImplicits:

    private val cached = new Flat[Any] {}

    given unit[S]: Flat[Unit < S] = unsafe.bypass[Unit < S]

    object unsafe:

        inline given bypass[T]: Flat[T] =
            cached.asInstanceOf[Flat[T]]
    end unsafe
end Flat
