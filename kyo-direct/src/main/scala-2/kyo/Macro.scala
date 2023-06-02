package kyo

import language.higherKinds
import scala.reflect.macros.whitebox.Context
import scala.reflect.macros.TypecheckException

private[kyo] class Macro(val c: Context) {
  import c.universe._

  def defer[T](body: Expr[T]): Tree = {
    Validate(c)(body.tree)
    Translate(c)(body.tree)
  }
}
