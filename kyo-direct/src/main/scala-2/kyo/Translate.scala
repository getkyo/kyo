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

    def nest(binds: List[(TermName, Tree)], value: Tree): Tree =
      binds match {
        case Nil => value
        case (name, body) :: t =>
          value match {
            case Ident(TermName(name)) =>
              body
            case _ =>
              q"$body.map(${toVal(name)} => ${nest(t, value)})"
          }
      }

    val transformer =
      new Transformer {
        var nextId = 0
        var binds  = List.empty[(TermName, Tree)]

        private def boundary(tree: Tree): Tree = {
          val prev = binds
          binds = List.empty
          val value = transform(tree)
          try q"${c.prefix}.internal.lift(${nest(binds.reverse, value)})"
          finally binds = prev
        }

        private def await(tree: Tree): Tree = {
          val name = TermName("await" + nextId)
          nextId += 1
          binds ::= (name, tree)
          q"$name"
        }

        override def transform(tree: Tree) =
          tree match {

            case tree if (pure(tree)) => tree

            case q"{ $mods val $name: $t = $v; ..$tail }" if (tail.nonEmpty) =>
              await(q"${boundary(v)}.map(${toVal(name)} => ${boundary(q"{ ..$tail }")})")

            case q"{ ..${head :: tail} }" if (tail.nonEmpty) =>
              await(q"${boundary(head)}.map(_ => ${boundary(q"{ ..$tail }")})")

            case q"if($cond) $ifTrue else $ifFalse" =>
              await(q""" 
                ${boundary(cond)}.map {
                  case true => ${boundary(ifTrue)}
                  case false => ${boundary(ifFalse)}
                }
              """)

            case q"$a && $b" =>
              await(q""" 
                ${boundary(a)}.map {
                  case true => ${boundary(b)}
                  case false => false
                }
              """)

            case q"$a || $b" =>
              await(q""" 
                ${boundary(a)}.map {
                  case true => true
                  case false => ${boundary(b)}
                }
              """)

            case q"while($cond) $body" =>
              await(q"${c.prefix}.internal.whileLoop(${boundary(cond)}, ${boundary(body)}.unit)")

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

              await(q"""
                ${boundary(value)}.map { ${toVal(matchValue)} => 
                  $matchValue match { case ..${loop(cases)} }
                }
              """)

            case q"$pack.await[$t, $s]($v)" =>
              await(v)

            case tree =>
              super.transform(tree)
          }
      }

    val result = transformer.transform(tree)
    c.resetAllAttrs(nest(transformer.binds, result))
  }
}
