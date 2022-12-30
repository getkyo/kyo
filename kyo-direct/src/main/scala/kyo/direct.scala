// package kyo

// import core._
// import scala.quoted._
// import java.awt.Taskbar.State

// object direct {

//   transparent inline def select[T](inline f: T) = ${ macroImpl[T]('f) }

//   inline def from[T, S](v: T > S): T = compiletime.error("must be used within a `select` block")

//   def macroImpl[T: Type](f: Expr[T])(using Quotes): Expr[Any] =
//     import quotes.reflect._
//     import quotes.reflect.report._

//     case class From[T, S](v: Expr[T > S], t: Type[T], s: Type[S])
//     object From {
//       def unapply(e: Expr[_]): Option[From[_, _]] =
//         e match {
//           case '{ from[t, s]($v) } => Some(From(v, Type.of[t], Type.of[s]))
//           case _                   => None
//         }
//     }

//     case class Select(f: Term => Term)

//     object Select {

//       def apply(tree: Term): Term =
//         unapply(tree).getOrElse(tree)

//       def unapply(tree: Term): Option[Term] =
//         tree match {
//           case PureTree(tree)            => None
//           case Typed(tree, tpe)          => unapply(tree).map(Typed(_, tpe))
//           case inlined(None, Nil, block) => unapply(block).map(inlined(None, Nil, _))
//           case Block(stats, expr) =>
//             ???
//           case If(cond, ifTrue, ifFalse) => error("aaaa")
//           case _                         => error("c")
//         }

//         var unbinds = List.empty[(Tree, Type[_], Type[_])]

//         Trees.traverse(tree) {
//           case '{ from[t, s]($v) } =>
//             unbinds ::= (v.asTerm, Type.of[t], Type.of[s])
//         }

//         unbinds.isEmpty {
//           case true => None
//           case false =>
//             unbinds = unbinds.reverse

//             var values = List.empty[Expr[_]]

//             val ss = Types.union(unbinds.map(_._3))

//             def bind(
//                 unbinds: List[(Tree, Type[_], Type[_])],
//                 v: Option[Expr[_]]
//             )(f: => Tree): Tree =
//               v.foreach(values :+= _)
//               unbinds match {
//                 case Nil => f
//                 case (v, t, s) :: tail =>
//                   (t, s, ss) match {
//                     case ('[t], '[s], '[ss]) =>
//                       val x =
//                         '{
//                           import kyo.core._
//                           ${ v.asExprOf[t > s] }((v: t) =>
//                             ${ bind(tail, Some('v))(f).asExprOf[T > ss] }
//                           )
//                         }.asTerm
//                       x
//                   }
//               }

//             val r = bind(unbinds, None) {
//               val r =
//                 Trees.transform(tree) {
//                   case '{ from[t, s]($v) } =>
//                     val v = values.head
//                     values = values.tail
//                     v.asTerm
//                 }
//               val x: Expr[T > Nothing] =
//                 '{
//                   kyo.core.fromPure[T](${ r.asExprOf[T] })
//                 }
//               x.asTerm

//             }
//             Some(r.asExpr.asTerm)
//         }
//     }

//     object PureTree {
//       def unapply(t: Tree): Option[Tree] =
//         Trees.exists(t) {
//           case '{ from[t, s]($v) } => true
//         } match {
//           case true  => None
//           case false => Some(t)
//         }
//     }

//     // object TransformBlock {
//     //   def unapply()
//     // }

//     object Types {
//       def union(l: List[Type[_]]): Type[_] =
//         l.reduce { (a, b) =>
//           (a, b) match {
//             case ('[a], '[b]) =>
//               Type.of[a | b]
//           }
//         }
//     }

//     Select(f.asTerm).asExpr
// }
