package kyo.internal

import kyo.concurrent.fibers.Fiber
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
  implicit def derive[T, S](implicit f: Flat[T > S]): Flat[T] =
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
              if (sym == c.typeOf[Nothing > Any].typeSymbol) =>
            Some((t.dealias, u.dealias))
          case _ =>
            None
        }
    }

    def canDerive(t: Type): Boolean = {
      val flatType = appliedType(typeOf[Flat[_]].typeConstructor, List(t))
      val v        = c.inferImplicitValue(flatType, silent = true, withMacrosDisabled = true)
      v != EmptyTree
    }

    def fail(msg: String) = c.abort(c.enclosingPosition, msg)

    def isAny(t: Type) =
      t.typeSymbol == c.typeOf[Any].typeSymbol

    def isConcrete(t: Type) =
      t.typeSymbol.isClass

    def isFiber(t: Type) =
      t.typeSymbol == c.typeOf[Fiber[Any]].typeSymbol

    def check(t: Type): Expr[Flat[T]] =
      if (isAny(t) || (!isConcrete(t) && !canDerive(t) && !isFiber(t))) {
        fail(
            s"Cannot prove ${t} isn't nested."
        )
      } else {
        c.Expr[Flat[T]](q"kyo.Flat.unsafe.checked")
      }

    t match {
      case Kyo(Kyo(_, _), _) =>
        fail("Nested Kyo computations are not allowed.")
      case Kyo(nt, _) =>
        check(nt)
      case t =>
        check(t)
    }
  }
}
