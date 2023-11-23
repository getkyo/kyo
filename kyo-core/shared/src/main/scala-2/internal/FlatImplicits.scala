package kyo.internal

import kyo.Flat
import scala.language.experimental.macros
import scala.reflect.macros.blackbox

trait FlatLowImplicits {
  implicit def derived[T, S](implicit f: Flat[T, _]): Flat[T, S] =
    f.asInstanceOf[Flat[T, S]]
}

trait FlatImplicits extends FlatLowImplicits {
  implicit def infer[T, S]: Flat[T, S] = macro FlatImplicits.inferMacro[T, S]
}

object FlatImplicits {
  def inferMacro[T, S](c: blackbox.Context)(implicit
      t: c.WeakTypeTag[T],
      s: c.WeakTypeTag[S]
  ): c.Tree = {
    import c.universe._
    val tpe = weakTypeOf[T]

    if (tpe.typeSymbol.isClass)
      q"kyo.Flat.unsafe.checked[$t, $s]"
    else
      c.abort(c.enclosingPosition, "not pure")
  }
}
