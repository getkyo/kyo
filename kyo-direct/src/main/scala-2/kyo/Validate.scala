package kyo

import kyo.>
import scala.reflect.macros.whitebox.Context
import scala.collection.mutable.Stack

private[kyo] object Validate {

  def apply(c: Context)(tree: c.Tree): Unit = {
    import c.universe._

    def fail(t: Tree, msg: String) =
      c.abort(t.pos, msg)

    def pure(tree: Tree) =
      !Trees.exists(c)(tree) {
        case q"$pack.await[$t, $s]($v)" => true
      }

    Trees.traverse(c)(tree) {
      case q"$pack.await[$t, $s]($v)" =>
      case q"$pack.defer[$t]($v)" =>
        fail(tree, "Nested `defer` blocks are not allowed.")
      case tree if (tree.tpe.typeSymbol == c.typeOf[(Any > Any)].typeSymbol) =>
        fail(tree, "Effectful computation must be inside an `await` block.")
      case tree @ q"var $name = $body" =>
        fail(tree, "`var` declarations are not allowed inside a `defer` block.")
      case tree @ q"return $v" =>
        fail(tree, "`return` statements are not allowed inside a `defer` block.")
      case q"$mods val $name = $body" if mods.hasFlag(Flag.LAZY) =>
        fail(tree, "`lazy val` declarations are not allowed inside a `defer` block.")
      case tree @ q"(..$params) => $body" if (!pure(body)) =>
        fail(tree, "Lambda functions containing `await` are not supported.")
      case tree @ q"$mods def $method[..$t](...$params): $r = $body" if (!pure(body)) =>
        fail(tree, "`def` declarations containing `await` are not supported.")
      case tree @ q"try $block catch { case ..$cases }" =>
        fail(tree, "`try/catch` blocks are not supported inside a `defer` block.")
      case tree @ q"try $block catch { case ..$cases } finally $finalizer" =>
        fail(tree, "`try/catch` blocks are not supported inside a `defer` block.")
      case tree @ q"$method[..$t](...$values)" if values.size > 0 && method.symbol.isMethod =>
        method.symbol.asMethod.paramLists.flatten.foreach {
          param =>
            if (param.asTerm.isByNameParam)
              fail(tree, "`await` cannot be used in by-name parameters.")
        }
      case tree @ q"new $t(..$args)" if (args.size > 0) =>
        t.tpe.decls.foreach {
          case m: MethodSymbol if m.paramLists.flatten.exists(_.asTerm.isByNameParam) =>
            fail(tree, "Constructors with by-name parameters are not supported.")
          case _ =>
        }
      case tree @ q"throw $expr" =>
        fail(tree, "Throwing exceptions is not supported inside a `defer` block.")
      case tree @ q"$mods class $tpname[..$tparams] $ctorMods(...$paramss) extends { ..$earlydefns } with ..$parents { $self => ..$stats }" =>
        fail(tree, "`class` declarations are not supported inside a `defer` block.")
      case tree @ q"for (...$enumerators) yield $expr" =>
        fail(tree, "`for yield` comprehensions are not allowed inside a `defer` block.")
      case tree @ q"for (...$enumerators) $expr" =>
        fail(tree, "`for` comprehensions are not allowed inside a `defer` block.")
      case tree @ q"$mods trait $tpname[..$tparams] extends { ..$earlydefns } with ..$parents { $self => ..$stats }" =>
        fail(tree, "`trait` declarations are not supported inside a `defer` block.")
      case tree @ q"$mods object $tname extends { ..$earlydefns } with ..$parents { $self => ..$stats }" =>
        fail(tree, "`object` declarations are not supported inside a `defer` block.")
    }
  }
}
