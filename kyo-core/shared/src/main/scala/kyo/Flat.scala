package kyo

import scala.reflect.ClassTag
import internal.FlatImplicits

sealed trait Flat[T, -S]

object Flat extends internal.FlatImplicits {

  sealed trait Checked[T, S]   extends Flat[T, S]
  sealed trait Unchecked[T, S] extends Flat[T, S]
  sealed trait Derived[T, S]   extends Flat[T, S]

  private val cachedChecked   = new Checked[Any, Any] {}
  private val cachedUnchecked = new Unchecked[Any, Any] {}
  private val cachedDerived   = new Derived[Any, Any] {}

  object unsafe {
    def checked[T, S]: Checked[T, S] =
      cachedChecked.asInstanceOf[Checked[T, S]]
    def derived[T, S]: Derived[T, S] =
      cachedDerived.asInstanceOf[Derived[T, S]]
    implicit def unchecked[T, S]: Unchecked[T, S] =
      cachedUnchecked.asInstanceOf[Unchecked[T, S]]
  }
}
