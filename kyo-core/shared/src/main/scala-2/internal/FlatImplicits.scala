package kyo.internal

import kyo.Flat
import scala.language.experimental.macros
import scala.reflect.macros.blackbox._

trait FlatImplicits0 {
  implicit def infer[T, S]: Flat[T, S] = macro FlatMacros.inferMacro[T, S]
}

trait FlatImplicits1 extends FlatImplicits0 {
  implicit def derived[T, S](implicit f: Flat[T, _]): Flat[T, S] =
    Flat.unsafe.derived[T, S]
}

trait FlatImplicits2 extends FlatImplicits1 {
  implicit def product[T <: Product, S]: Flat[T, S] =
    Flat.unsafe.checked[T, S]
}

trait FlatImplicits extends FlatImplicits2 {
  implicit def anyVal[T <: AnyVal, S]: Flat[T, S] =
    Flat.unsafe.checked[T, S]
}

object FlatMacros {
  def inferMacro[T: c.WeakTypeTag, S: c.WeakTypeTag](c: Context): c.Expr[Flat[T, S]] = {
    import c.universe._

    val tpe = weakTypeOf[T]

    val isConcrete = tpe.typeSymbol.isClass

    if (isConcrete || tpe.toString.startsWith("kyo.concurrent.fibers.Fiber")) {
      c.Expr[Flat[T, S]](q"Flat.unsafe.checked[$tpe, ${weakTypeOf[S]}]")
    } else {
      c.abort(c.enclosingPosition, "not pure: " + tpe.toString)
    }
  }
}
