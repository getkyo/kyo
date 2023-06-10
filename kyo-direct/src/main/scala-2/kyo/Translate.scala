package kyo

import kyo.>
import scala.reflect.macros.whitebox.Context
import scala.collection.mutable.Stack
import org.scalamacros.resetallattrs._

private[kyo] object Translate {

  def apply(c: Context)(tree: c.Tree): c.Tree = {
    import c.universe._

    def toVal(name: TermName) = q"val $name = $EmptyTree"

    def pure(tree: Tree) =
      !Trees.exists(c)(tree) {
        case q"$pack.await[$t, $s]($v)" => true
      }

    def nest(binds: List[(TermName, Tree)], body: Tree): Tree =
      binds match {
        case Nil => body
        case (name, value) :: t =>
          q"${c.prefix}.internal.cont($value)(${toVal(name)} => ${nest(t, body)})"
      }

    var nextId = 0
    var binds  = List.empty[(TermName, Tree)]

    val transformer =
      new Transformer {

        private def boundary(tree: Tree): Tree = {
          val prev = binds
          binds = List.empty
          val value = transform(tree)
          try nest(binds.reverse, value)
          finally binds = prev
        }

        private def await(tree: Tree): Tree = {
          val name = TermName("await" + nextId)
          nextId += 1
          binds ::= (name, tree)
          q"$name"
        }

        private def cont(value: Tree, name: TermName, body: Tree): Tree =
          await(nest(List((name, boundary(value))), boundary(body)))

        private def branch(cond: Tree, ifTrue: Tree, ifFalse: Tree): Tree =
          await(
              q"${c.prefix}.internal.branch(${boundary(cond)}, ${boundary(ifTrue)}, ${boundary(ifFalse)})"
          )

        private def loop(cond: Tree, body: Tree): Tree =
          await(q"${c.prefix}.internal.loop(${boundary(cond)}, ${boundary(body)})")

        override def transform(tree: Tree) =
          tree match {

            case tree if (pure(tree)) => tree

            case q"{ $mods val $name: $t = $v; ..$tail }" if (tail.nonEmpty) =>
              cont(v, name, q"{ ..$tail }")

            case q"{ ..${head :: tail} }" if (tail.nonEmpty) =>
              cont(head, TermName("_"), q"{ ..$tail }")

            case q"if($cond) $ifTrue else $ifFalse" =>
              branch(cond, ifTrue, ifFalse)

            case q"$a && $b" =>
              branch(a, b, q"false")

            case q"$a || $b" =>
              branch(a, q"true", b)

            case q"while($cond) $body" =>
              loop(cond, body)

            case q"$value match { case ..$cases }" =>
              val matchValue = TermName(c.freshName("matchValue"))

              def loop(cases: List[CaseDef]): List[Tree] =
                cases match {
                  case Nil =>
                    cq"_ => throw new scala.MatchError($matchValue)" :: Nil
                  case t @ cq"$pattern => $body" :: tail =>
                    cq"$pattern => ${boundary(body)}" :: loop(tail)
                  case t @ cq"$pattern if $cond => $body" :: tail =>
                    cq"""
                      $pattern => 
                        ${boundary(cond)}.map {
                          case true => 
                            ${boundary(body)}
                          case false => 
                            $matchValue match { case ..${loop(tail)} }
                        }
                    """ :: Nil
                }

              cont(value, matchValue, q"$matchValue match { case ..${loop(cases)} }")

            case q"$pack.await[$t, $s]($v)" =>
              await(v)

            case tree =>
              super.transform(tree)
          }
      }

    val result = transformer.transform(tree)
    c.resetAllAttrs(nest(binds, result))
  }
}
