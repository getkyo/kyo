package kyo.internal

import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context
import kyo._

trait FlatImplicits0 {
  implicit def infer[T]: Flat[T] = macro FlatImplicits.inferMacro[T]
}

trait FlatImplicits1 extends FlatImplicits0 {
  implicit def product[T <: Product]: Flat[T] = Flat.unsafe.checked[T]
}

trait FlatImplicits extends FlatImplicits1 {
  implicit def anyVal[T <: AnyVal]: Flat[T] = Flat.unsafe.checked[T]
}

object FlatImplicits {
  def inferMacro[T](c: Context)(implicit t: c.WeakTypeTag[T]): c.Expr[Flat[T]] = {
    import c.universe._

    println(t)

    object Kyo {
      def unapply(tpe: Type): Option[(Type, Type)] =
        tpe match {
          case TypeRef(_, sym, List(t, u)) if sym.asType.fullName == "kyo.>" =>
            Some((t, u))
          case _ =>
            None
        }
    }

    def canDerive(t: Type): Boolean = {
      val flatType = appliedType(typeOf[Flat[_]].typeConstructor, List(t))
      c.inferImplicitValue(flatType, silent = true) != EmptyTree
    }

    def ok = c.Expr[Flat[T]](q"kyo.Flat.unsafe.checked[$t]")

    def fail(msg: String) = c.abort(c.enclosingPosition, msg)

    t match {
      case Kyo(Kyo(_, _), _) =>
        fail("Nested Kyo computations are not allowed.")
      case Kyo(nt, _) if !nt.typeSymbol.isClass && !canDerive(nt) =>
        fail(s"Cannot prove ${nt.toString} isn't nested. Provide an implicit evidence.")
      case _ =>
        println(t)
        ok
    }
  }
}
