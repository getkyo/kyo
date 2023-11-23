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

    def formatType(t: TypeRepr): String = {
      t match {
        case AppliedType(TypeRef(_, ">"), List(lhs, rhs)) =>
          s"${formatType(lhs)} > ${formatType(rhs)}"
        case _ => t.show
      }
    }

    val t             = TypeRepr.of[T]
    val formattedType = formatType(t)
    val s             = TypeRepr.of[S]

    if (t.typeSymbol.isClassDef) {
      '{ Flat.unsafe.checked[T, S] }
    } else {
      report.errorAndAbort(
          s"""
          |Unable to prove ${Console.YELLOW}'${formattedType}'${Console.RESET} is not a Kyo computation. Possible reasons:
          |1. Mismatch with expected pending effects ${Console.YELLOW}'${s.show}'${Console.RESET}. Handle any extra effects first.
          |2. Nested computation detected. Use ${Console.YELLOW}'flatten'${Console.RESET} to unnest.
          |3. Generic type parameter. Provide an implicit evidence ${Console.YELLOW}'Flat[${formattedType}, ${s.show}]'${Console.YELLOW}.
          """.stripMargin
      )
    }
  }

}
