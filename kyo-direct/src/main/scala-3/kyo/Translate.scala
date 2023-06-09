package kyo

import direct._
import scala.quoted._
import scala.annotation.targetName

object Translate {

  def apply(using
      Quotes
  )(tree: quotes.reflect.Term, owner: quotes.reflect.Symbol): quotes.reflect.Tree = {
    import quotes.reflect._

    val wildcard = Symbol.newMethod(
        owner,
        "_",
        MethodType(List("x"))(_ => List(TypeRepr.of[Any]), _ => TypeRepr.of[Any])
    )

    def pure(tree: Tree) =
      !Trees.exists(tree, owner) {
        case Apply(TypeApply(Ident(id), _), v) if (id == "await") => true
      }

    def nest(binds: List[(Symbol, Tree)], body: Tree): Tree =
      binds match {
        case Nil =>
          body
        case (name, value) :: t =>
          val mt = MethodType(List(name.name))(_ => List(TypeRepr.of[Any]), _ => TypeRepr.of[Any])
          val newFunc = Lambda(
              owner,
              mt,
              (owner, params) =>
                Trees.transform(nest(t, body), owner) {
                  case Ident(name) =>
                    params.head
                }
          )
          val internalSym = Symbol.requiredModule("kyo.direct.internal")
          val contSym     = internalSym.methodMembers.filter(_.name == "cont").head
          Apply(
              Select(Ref(internalSym), contSym),
              List(value.asExpr.asTerm, newFunc)
          )
      }

    var nextId = 0
    var binds  = List.empty[(Symbol, Tree)]

    val transformer =
      new TreeMap {

        private def boundary(tree: Tree): Tree = {
          val prev = binds
          binds = List.empty
          val value = transformTree(tree)(owner)
          try nest(binds.reverse, value)
          finally binds = prev
        }

        private def await(tree: Tree): Tree = {
          val name = Symbol.newVal(
              Symbol.spliceOwner,
              "await" + nextId,
              TypeRepr.of[Any],
              Flags.EmptyFlags,
              Symbol.noSymbol
          )
          nextId += 1
          binds ::= (name, tree)
          Ref(name)
        }

        private def cont(value: Tree, name: Symbol, body: Tree): Tree =
          await(nest(List((name, boundary(value))), boundary(body)))

        private def branch(cond: Tree, ifTrue: Tree, ifFalse: Tree): Tree =
          await('{
            internal.branch(
                ${ boundary(cond).asExprOf[Boolean > Any] },
                ${ boundary(ifTrue).asExpr },
                ${ boundary(ifFalse).asExpr }
            )
          }.asTerm)

        private def loop(cond: Tree, body: Tree): Tree =
          await('{
            internal.loop(
                ${ boundary(cond).asExprOf[Boolean > Any] },
                ${ boundary(body).asExpr }
            )
          }.asTerm)

        override def transformTree(tree: Tree)(owner: Symbol): Tree = {
          tree match {
            case tree if (pure(tree)) => tree

            case Inlined(_, List(valDef: ValDef), Inlined(_, bindings, expr)) =>
              cont(valDef.rhs.get, valDef.symbol, Block(bindings, expr))

            case Inlined(_, head :: tail, expr) if (tail.nonEmpty) =>
              cont(head, wildcard, Block(tail, expr))

            case If(cond, ifTrue, ifFalse) =>
              branch(cond, ifTrue, ifFalse)

            case Apply(TypeApply(Ident(id), _), v) if (id == "await") =>
              await(v.head)

            case _ =>
              super.transformTree(tree)(owner)
          }
        }
      }

    val result = transformer.transformTree(tree)(owner)
    val r      = nest(binds, result)
    report.error(r.show)
    r
  }

}
