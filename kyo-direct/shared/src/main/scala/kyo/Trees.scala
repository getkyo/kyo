package kyo

import scala.quoted.*

object Trees:

    class Step(private var _gotoTrees: Seq[Any]):
        def this() = this(Seq.empty)

        protected[Trees] def addTree(using q: Quotes)(tree: q.reflect.Tree): Unit =
            _gotoTrees = _gotoTrees :+ tree

        protected[Trees] def gotoTrees(using q: Quotes): Seq[q.reflect.Tree] = _gotoTrees.asInstanceOf
    end Step

    object Step:
        def goto(using s: Step, q: Quotes)(tree: q.reflect.Tree): Unit = s.addTree(tree)
    end Step

    def traverseGoto(using
        Quotes
    )(tree: quotes.reflect.Tree)(pf: Step ?=> PartialFunction[quotes.reflect.Tree, Unit]): Unit =
        import quotes.reflect.*
        (new TreeTraverser:
            override def traverseTree(tree: Tree)(owner: Symbol): Unit =
                given step: Step = new Step
                pf.lift(tree).getOrElse(super.traverseTree(tree)(owner))
                step.gotoTrees.foreach(t => traverseTree(t)(owner))
            end traverseTree
        ).traverseTree(tree)(Symbol.spliceOwner)
    end traverseGoto

    def traverse(using
        Quotes
    )(tree: quotes.reflect.Tree)(pf: PartialFunction[quotes.reflect.Tree, Unit]): Unit =
        import quotes.reflect.*
        (new TreeTraverser:
            override def traverseTree(tree: Tree)(owner: Symbol): Unit =
                pf.lift(tree).getOrElse(super.traverseTree(tree)(owner))
        ).traverseTree(tree)(Symbol.spliceOwner)
    end traverse

    def transform(using
        Quotes
    )(tree: quotes.reflect.Tree)(pf: PartialFunction[quotes.reflect.Tree, quotes.reflect.Term]): quotes.reflect.Tree =
        import quotes.reflect.*
        (new TreeMap:
            override def transformTerm(tree: Term)(owner: Symbol): Term =
                pf.lift(tree).getOrElse(super.transformTerm(tree)(owner))
        ).transformTree(tree)(Symbol.spliceOwner)
    end transform

    def exists(using
        Quotes
    )(tree: quotes.reflect.Tree)(pf: PartialFunction[quotes.reflect.Tree, Boolean]) =
        var r = false
        traverse(tree) {
            case t if pf.isDefinedAt(t) && !r => r = pf(t)
        }
        r
    end exists
end Trees
