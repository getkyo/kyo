package kyo.internal

import kyo.Flat
import scala.language.experimental.macros
import scala.reflect.macros.blackbox._

trait FlatImplicits0 {
  implicit def infer[T]: Flat[T] = macro FlatMacros.inferMacro[T]
}

trait FlatImplicits1 extends FlatImplicits0 {
  implicit def derived[T](implicit f: Flat[T]): Flat[T] =
    Flat.unsafe.derived[T]
}

trait FlatImplicits2 extends FlatImplicits1 {
  implicit def product[T <: Product]: Flat[T] =
    Flat.unsafe.checked[T]
}

trait FlatImplicits extends FlatImplicits2 {
  implicit def anyVal[T <: AnyVal]: Flat[T] =
    Flat.unsafe.checked[T]
}

object FlatMacros {
  def inferMacro[T: c.WeakTypeTag](c: Context): c.Expr[Flat[T]] = {
    import c.universe._

    val t = weakTypeOf[T]

    c.abort(c.enclosingPosition, "AAA " + t)

    val isConcrete = t.typeSymbol.isClass

    if (isConcrete || t.toString.startsWith("kyo.concurrent.fibers.Fiber")) {
      c.Expr[Flat[T]](q"Flat.unsafe.checked[$t]")
    } else {
      c.abort(c.enclosingPosition, "not pure: " + t.toString)
    }
  }
}
