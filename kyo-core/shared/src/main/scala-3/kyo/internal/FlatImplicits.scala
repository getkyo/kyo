package kyo.internal

import kyo._
import scala.quoted._

trait FlatImplicits0 {
  inline implicit def infer[T]: Flat[T] =
    ${ FlatImplicits.inferMacro[T] }
}

trait FlatImplicits1 extends FlatImplicits0 {
  implicit def product[T <: Product]: Flat[T] =
    Flat.unsafe.checked[T]
}

trait FlatImplicits extends FlatImplicits1 {
  implicit def anyVal[T <: AnyVal]: Flat[T] =
    Flat.unsafe.checked[T]
}

object FlatImplicits {
  def inferMacro[T: Type](using Quotes): Expr[Flat[T]] = {
    import quotes.reflect._

    val t = TypeRepr.of[T]

    object Kyo {
      def unapply(tpe: TypeRepr): Option[(TypeRepr, TypeRepr)] =
        tpe match {
          case AppliedType(TypeRef(_, ">"), List(t, u)) =>
            Some((t, u))
          case _ => None
        }
    }

    def code(str: String) =
      s"${Console.YELLOW}'$str'${Console.RESET}"

    def print(t: TypeRepr): String = {
      t match {
        case Kyo(t, s) =>
          s"${print(t)} > ${print(s)}"
        case _ => t.show
      }
    }

    def canDerive(t: TypeRepr) =
      t.asType match {
        case '[nt] =>
          Expr.summon[Flat[nt]]
            .orElse(Expr.summon[Flat[nt > Nothing]])
            .isDefined
      }

    def ok = '{ Flat.unsafe.checked[T] }

    def fail(msg: String) =
      report.errorAndAbort(s"Method doesn't accept nested Kyo computations.\n$msg")

    t match {
      case Kyo(Kyo(nt, s1), s2) =>
        val potentialMismatch =
          if (s1 != s2) {
            s"\nPossible pending effects mismatch: Expected ${code(print(s2))}, found ${code(print(s1))}."
          } else {
            ""
          }
        fail(
            s"Detected: ${code(print(t))}. Consider using ${code("flatten")} to resolve. " + potentialMismatch
        )
      case Kyo(nt, s) =>
        if (!nt.typeSymbol.isClassDef && !canDerive(nt)) {
          fail(
              s"Cannot prove ${code(print(nt))} isn't nested. Provide an implicit evidence ${code(s"kyo.Flat[${print(nt)}]")}."
          )
        } else {
          ok
        }
      case t =>
        if (!t.typeSymbol.isClassDef) {
          fail(
              s"Cannot prove ${code(print(t))} isn't nested. Provide an implicit evidence ${code(s"kyo.Flat[${print(t)}]")}."
          )
        } else {
          ok
        }
    }
  }
}
