package kyo

import direct._
import scala.quoted._
import scala.annotation.targetName

object Translate {

  def apply[T: Type](using
      Quotes
  )(tree: quotes.reflect.Term, owner: quotes.reflect.Symbol): quotes.reflect.Tree = {
    import quotes.reflect._

    val wildcard = Symbol.newMethod(
        owner,
        "_",
        MethodType(List("x"))(_ => List(TypeRepr.of[Any]), _ => TypeRepr.of[Any])
    )

    var effects = Set.empty[Type[_]]
    Trees.traverse(tree, owner) {
      case Apply(TypeApply(Ident(id), t :: s :: Nil), v) if (id == "await") =>
        effects = effects + t.tpe.asType
    }

    effects.reduce {
      case ('[t1], '[t2]) =>
        Type.of[t1 with t2]
    } match {
      case '[effects] =>
        def pure(tree: Tree) =
          !Trees.exists(tree, owner) {
            case Apply(TypeApply(Ident(id), t :: s :: Nil), v) if (id == "await") => true
          }

        def nest(binds: List[(Symbol, Term)], body: Term): Term =
          binds match {
            case Nil => body
            case (symbol, value) :: t =>
              body.tpe.asType match {
                case '[tpe] =>
                  val mt = MethodType(List(symbol.name))(
                      _ => List(value.tpe),
                      _ => TypeRepr.of[tpe > effects]
                  )
                  val newFunc = Lambda(
                      owner,
                      mt,
                      (owner, params) =>
                        Trees.transform(nest(t, body), owner) {
                          case Ident(name) =>
                            params.head.asInstanceOf[Term]
                        }
                  )
                  val internalSym = Symbol.requiredModule("kyo.direct.internal")
                  val contSym     = internalSym.methodMembers.filter(_.name == "cont").head
                  Apply(
                      Select(Ref(internalSym), contSym),
                      List(value.asExpr.asTerm, newFunc)
                  )
              }
          }

        var nextId = 0
        var binds  = List.empty[(Symbol, Term)]

        val transformer =
          new TreeMap {

            private def boundary(tree: Term): Term = {
              val prev = binds
              binds = List.empty
              val value = transformTerm(tree)(owner)
              try nest(binds.reverse, value)
              finally binds = prev
            }

            private def await(tree: Term): Term = {
              val name = Symbol.newVal(
                  Symbol.spliceOwner,
                  "await" + nextId,
                  tree.tpe,
                  Flags.EmptyFlags,
                  Symbol.noSymbol
              )
              nextId += 1
              binds ::= (name, tree)
              Ref(name)
            }

            private def cont(value: Term, name: Symbol, body: Term): Term =
              await(nest(List((name, boundary(value))), boundary(body)))

            private def branch(cond: Term, ifTrue: Term, ifFalse: Term): Term =
              await('{
                internal.branch(
                    ${ boundary(cond).asExprOf[Boolean > Any] },
                    ${ boundary(ifTrue).asExpr },
                    ${ boundary(ifFalse).asExpr }
                )
              }.asTerm)

            private def loop(cond: Term, body: Term): Term =
              await('{
                internal.loop(
                    ${ boundary(cond).asExprOf[Boolean > Any] },
                    ${ boundary(body).asExpr }
                )
              }.asTerm)

            override def transformTerm(term: Term)(owner: Symbol): Term = {
              term match {
                // case tree if (pure(tree)) => tree

                // case Inlined(_, List(valDef: ValDef), Inlined(_, bindings, expr)) =>
                //   cont(valDef.rhs.get, valDef.symbol, Block(bindings, expr))

                // case Inlined(_, head :: tail, expr) if (tail.nonEmpty) =>
                //   cont(head, wildcard, Block(tail, expr))

                case If(cond, ifTrue, ifFalse) =>
                  branch(cond, ifTrue, ifFalse)

                case Apply(TypeApply(Ident(id), _), v) if (id == "await") =>
                  await(v.head)

                case _ =>
                  println(tree)
                  super.transformTerm(term)(owner)
              }
            }
          }

        val result = transformer.transformTerm(tree)(owner)
        val r      = nest(binds, result)
        report.info(r.show)
        r
    }
  }

}
