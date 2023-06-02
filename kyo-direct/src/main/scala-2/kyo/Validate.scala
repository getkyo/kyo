package kyo

import kyo.>
import scala.reflect.macros.whitebox.Context
import scala.collection.mutable.Stack

private[kyo] object Validate {

  def apply(c: Context)(tree: c.Tree): Unit = {
    import c.universe._

    def fail(t: Tree, msg: String) =
      c.abort(t.pos, msg + ": " + t)

    def pure(tree: Tree) =
      !Trees.exists(c)(tree) {
        case q"$pack.await[$t, $s]($v)" => true
      }

    Trees.traverse(c)(tree) {
      case q"$pack.await[$t, $s]($v)" =>
      case q"$pack.defer[$t]($v)" =>
        fail(tree, "nested defer blocks aren't supported")
      case tree if (tree.tpe.typeSymbol == c.typeOf[(Any > Any)].typeSymbol) =>
        fail(tree, "effectful computation must be in an await block")
      case tree @ q"var $name = $body" =>
        fail(tree, "`var` is not allowed in a defer block")
      case tree @ q"return $v" =>
        fail(tree, "`return` are not allowed in a defer block")
      case q"$mods val $name = $body" if mods.hasFlag(Flag.LAZY) =>
        fail(tree, "`lazy val` are not allowed in a defer block")
      case tree @ q"(..$params) => $body" if (!pure(body)) =>
        fail(tree, "functions can't use await blocks")
      case tree @ q"$method[..$t](...$values)" if values.size > 0 && method.symbol.isMethod =>
        val pit = method.symbol.asMethod.paramLists.flatten.iterator
        val vit = values.flatten.iterator
        while (pit.hasNext && vit.hasNext) {
          val param = pit.next()
          val value = vit.next()
          (param.asTerm.isByNameParam, value) match {
            case (true, t) if (!pure(t)) =>
              c.abort(t.pos, "await can't be used in a by-name param: " + t)
            case other => ()
          }
        }
        values.flatten.foreach(Validate(c)(_))
      case tree @ q"$mods def $method[..$tpe](...$params) = $body" if (!pure(body)) =>
        if (tree.symbol.overrides.nonEmpty)
          fail(tree, "can't use await blocks in overriden method bodies")
        else
          Validate(c)(body)
      case tree @ q"$mods def $method[..$t](...$params): $r = $body" if (!pure(body)) =>
        fail(tree, "can't use await blocks in a nested method body")
    }
  }
}
