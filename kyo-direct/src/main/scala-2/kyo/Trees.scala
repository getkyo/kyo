package kyo

import scala.reflect.macros.blackbox.Context

private[kyo] object Trees {

  object Transform {
    def apply(c: Context)(tree: c.Tree)(pf: PartialFunction[c.Tree, c.Tree]): c.Tree = {
      import c.universe._
      new Transformer {
        override def transform(tree: Tree) =
          pf.lift(tree).getOrElse(super.transform(tree))
      }.transform(tree)
    }
    def unapply(c: Context)(tree: c.Tree)(pf: PartialFunction[c.Tree, c.Tree]): Option[c.Tree] =
      apply(c)(tree)(pf) match {
        case `tree` => None
        case tree   => Some(tree)
      }
  }

  def traverse(c: Context)(tree: c.Tree)(pf: PartialFunction[c.Tree, Unit]) = {
    import c.universe._
    new Traverser {
      override def traverse(tree: Tree) =
        pf.lift(tree).getOrElse(super.traverse(tree))
    }.traverse(tree)
  }
  def exists(c: Context)(tree: c.Tree)(pf: PartialFunction[c.Tree, Boolean]) = {
    var r = false
    traverse(c)(tree) {
      case t if pf.isDefinedAt(t) && !r => r = pf(t)
    }
    r
  }
}
