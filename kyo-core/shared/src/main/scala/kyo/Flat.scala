package kyo

import internal.FlatImplicits

sealed trait Flat[-T]

object Flat extends FlatImplicits {

  private val cached = new Flat[Any] {}

  given unit[S]: Flat[Unit < S] = unsafe.bypass[Unit < S]

  object unsafe {
    /*inline*/
    given bypass[T]: Flat[T] =
      cached.asInstanceOf[Flat[T]]
  }
}
