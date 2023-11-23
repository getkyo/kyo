package kyo.internal

import kyo.Flat
import scala.quoted._

trait FlatLowImplicits {
  implicit def derived[T, S](implicit f: Flat[T, _]): Flat[T, S] =
    f.asInstanceOf[Flat[T, S]]
}

trait FlatImplicits {
  inline implicit def infer[T, S]: Flat[T, S] = ${ FlatImplicits.inferMacro[T, S] }
}

object FlatImplicits {
  def inferMacro[T: Type, S: Type](using Quotes): Expr[Flat[T, S]] = {
    import quotes.reflect._

    val tpe = TypeRepr.of[T]

    val isConcrete = tpe.typeSymbol.isClassDef
    val canDerive  = Expr.summon[Flat[T, Nothing]].isDefined

    if (isConcrete || canDerive)
      '{ Flat.unsafe.checked[T, S] }
    else
      report.errorAndAbort("not pure: " + tpe.show)
  }
}
