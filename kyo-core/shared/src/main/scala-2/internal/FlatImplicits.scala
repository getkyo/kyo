package kyo.internal

import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context
import kyo._

trait FlatImplicits0 {
  implicit def infer[T]: Flat[T] = macro FlatImplicits.inferMacro[T]
}

trait FlatImplicits1 extends FlatImplicits0 {
  implicit def product[T <: Product]: Flat[T] =
    Flat.unsafe.checked
}

trait FlatImplicits extends FlatImplicits1 {
  implicit def derive[T, S](implicit f: Flat[T < S]): Flat[T] =
    Flat.unsafe.checked
  implicit def anyVal[T <: AnyVal]: Flat[T] =
    Flat.unsafe.checked
}

object FlatImplicits {

  def inferMacro[T: c.WeakTypeTag](c: Context)(implicit t: c.WeakTypeTag[T]): c.Expr[Flat[T]] = {
    import c.universe._

    val t = c.weakTypeTag[T].tpe.dealias

    object Kyo {
      def unapply(tpe: Type): Option[(Type, Type)] =
        tpe match {
          case TypeRef(_, sym, List(t, u))
              if (sym == c.typeOf[Nothing < Any].typeSymbol) =>
            Some((t.dealias, u.dealias))
          case _ =>
            None
        }
    }

    def code(str: String) =
      s"${scala.Console.YELLOW}'$str'${scala.Console.RESET}"

    def print(t: Type): String = {
      t match {
        case Kyo(t, s) =>
          s"${print(t)} > ${print(s)}"
        case _ => t.toString
      }
    }

    def fail(msg: String) =
      c.abort(c.enclosingPosition, s"Method doesn't accept nested Kyo computations.\n$msg")

    def canDerive(t: Type): Boolean = {
      val flatType = appliedType(typeOf[Flat[_]].typeConstructor, List(t))
      val v        = c.inferImplicitValue(flatType, silent = true, withMacrosDisabled = true)
      v != EmptyTree
    }

    def isAny(t: Type) =
      t.typeSymbol == c.typeOf[Any].typeSymbol

    def isConcrete(t: Type) =
      t.typeSymbol.isClass

    def isFiber(t: Type) =
      t.typeSymbol == c.typeOf[Fiber[Any]].typeSymbol

    def check(t: Type): Expr[Flat[T]] =
      if (isAny(t) || (!isConcrete(t) && !canDerive(t) && !isFiber(t))) {
        fail(
            s"Cannot prove ${code(print(t))} isn't nested. Provide an implicit evidence ${code(s"kyo.Flat[${print(t)}]")}."
        )
      } else {
        c.Expr[Flat[T]](q"kyo.Flat.unsafe.checked")
      }

    t match {
      case Kyo(Kyo(nt, s1), s2) =>
        val mismatch =
          if (print(s1) != print(s2)) {
            s"\nPossible pending effects mismatch: Expected ${code(print(s2))}, found ${code(print(s1))}."
          } else {
            ""
          }
        fail(
            s"Detected: ${code(print(t))}. Consider using ${code("flatten")} to resolve. " + mismatch
        )
      case Kyo(nt, _) =>
        check(nt)
      case t =>
        check(t)
    }
  }
}
