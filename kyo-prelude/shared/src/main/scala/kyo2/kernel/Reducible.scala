// package kyo2.kernel

// import scala.quoted.*

// object Reducible:

//     trait Remove[S, E]:
//         type Reduced
//         def apply[A](v: A < (S & E)): A < Reduced = v.asInstanceOf[A < Reduced]

//     object Remove:
//         transparent inline given derived[S, E]: Remove[S, E] = ${ removeImpl[S, E] }

//         private def removeImpl[S: Type, E: Type](using Quotes): Expr[Remove[S, E]] =
//             import quotes.reflect.*

//             def removeTypes(types: List[TypeRepr], toRemove: List[TypeRepr]): List[TypeRepr] =
//                 types.flatMap { tpe =>
//                     toRemove.find(tpe <:< _) match
//                         case Some(toRemove) =>
//                             tpe match
//                                 case AppliedType(p, targ :: Nil) =>
//                                     tpe.typeSymbol.typeMembers match
//                                         case param :: Nil if param.flags.is(Flags.Contravariant) =>
//                                             val r = flattenOr(toRemove.)
//                                             val nargs    = flattenOr(targ).filter(t => r.exists(r => t <:< r))
//                                             List(AppliedType(p, nargs))
//                                         case param :: Nil if param.flags.is(Flags.Covariant) =>
//                                             val toRemove = flattenAnd(rarg)
//                                             val nargs    = flattenAnd(targ).filter(t => toRemove.exists(r => t <:< r))
//                                             List(AppliedType(p, nargs))
//                                 case _ =>
//                                     Nil
//                             end match
//                         case None => List(tpe)
//                     end match
//                 }

//             val remainingTypes = removeTypes(flattenAnd(TypeRepr.of[S]), flattenAnd(TypeRepr.of[E]))
//             val resultType     = fold(remainingTypes)

//             resultType.asType match
//                 case '[reduced] =>
//                     '{
//                         new Remove[S, E]:
//                             type Reduced = reduced
//                     }
//             end match
//         end removeImpl
//     end Remove

//     private def flattenAnd(using Quotes)(tpe: quotes.reflect.TypeRepr): List[quotes.reflect.TypeRepr] =
//         import quotes.reflect.*
//         tpe match
//             case AndType(left, right) => flattenAnd(left) ++ flattenAnd(right)
//             case t                    => List(t)
//     end flattenAnd

//     private def flattenOr(using Quotes)(tpe: quotes.reflect.TypeRepr): List[quotes.reflect.TypeRepr] =
//         import quotes.reflect.*
//         tpe match
//             case OrType(left, right) => flattenOr(left) ++ flattenOr(right)
//             case t                   => List(t)
//     end flattenOr

//     def fold(using Quotes)(types: List[quotes.reflect.TypeRepr]): quotes.reflect.TypeRepr =
//         import quotes.reflect.*
//         types match
//             case Nil           => TypeRepr.of[Any]
//             case single :: Nil => single
//             case first :: rest => rest.foldLeft(first)((acc, t) => AndType(acc, t))
//         end match
//     end fold

// end Reducible
