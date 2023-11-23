package kyo.internal

import kyo.Flat
import scala.quoted._

trait FlatImplicits0 {
  inline implicit def infer[T, S]: Flat[T, S] =
    ${ FlatImplicits.inferMacro[T, S] }
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

object FlatImplicits {
  def inferMacro[T: Type, S: Type](using Quotes): Expr[Flat[T, S]] = {
    import quotes.reflect._

    val tpe = TypeRepr.of[T]

    val isConcrete = tpe.typeSymbol.isClassDef

    if (isConcrete) {
      '{ Flat.unsafe.checked[T, S] }
    } else {
      report.errorAndAbort("not pure: " + tpe.show)
    }
  }
}
