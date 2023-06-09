package kyo

import scala.quoted._

object Trees {

  def transform(using Quotes)(
      term: quotes.reflect.Tree,
      owner: quotes.reflect.Symbol
  )(pf: PartialFunction[quotes.reflect.Tree, quotes.reflect.Tree]) =
    import quotes.reflect._
    (new TreeMap:
      override def transformTree(tree: Tree)(owner: Symbol): Tree = {
        pf.lift(tree).getOrElse(super.transformTree(tree)(owner))
      }
    ).transformTree(term)(owner)

  def traverse(using Quotes)(
      tree: quotes.reflect.Tree,
      owner: quotes.reflect.Symbol
  )(pf: PartialFunction[quotes.reflect.Tree, Unit]) =
    import quotes.reflect._
    (new TreeTraverser:
      override def traverseTree(tree: Tree)(owner: Symbol): Unit = {
        // if the partial function matches it will run whatever logic is therin. Otherwise we don't care about the value
        pf.applyOrElse(tree, tree => ())
        // In either case, proceed further up the tree
        super.traverseTree(tree)(owner)
      }
    ).traverseTree(tree)(owner)

  def exists(using Quotes)(
      tree: quotes.reflect.Tree,
      owner: quotes.reflect.Symbol
  )(pf: PartialFunction[quotes.reflect.Tree, Boolean]) = {
    import quotes.reflect._
    var r = false
    traverse(using quotes)(tree, owner) {
      case t if pf.isDefinedAt(t) && !r => r = pf(t)
    }
    r
  }
}
