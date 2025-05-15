package kyo.internal

import TypeIntersection.*
import scala.compiletime.summonInline
import scala.quoted.*

/** A type-level utility for decomposing intersection types into their constituent parts.
  *
  * TypeIntersection addresses the challenge of working with intersection types in a type-safe manner, particularly when implementing
  * type-level operations like equality checking and type class derivation. It provides a way to:
  *
  *   - Break down complex intersection types into simpler components
  *   - Apply type constructors uniformly across all components
  *   - Collect type class instances for all component types
  */
sealed abstract class TypeIntersection[A] extends Serializable:

    /** The tuple representation of the decomposed types.
      *
      * For an intersection type A & B & C, this would be A *: B *: C *: EmptyTuple. The order of types in the tuple matches the order they
      * appear in the intersection.
      */
    type AsTuple <: Tuple

    /** Applies a type constructor F to each component type in the set.
      *
      * @tparam F
      *   the type constructor to apply
      * @return
      *   an intersection type of F applied to each component
      */
    type Map[F[_]] = Join[Tuple.Map[AsTuple, F]]

end TypeIntersection

object TypeIntersection:

    // Cached instance used for optimization.
    private val cached: TypeIntersection[Any] =
        new TypeIntersection[Any]:
            type AsTuple = EmptyTuple

    private type Join[A <: Tuple] = Tuple.Fold[A, Any, [B, C] =>> B & C]

    /** Returns the TypeIntersection instance for type A.
      *
      * @tparam A
      *   the type to get the TypeIntersection for
      * @param ts
      *   the implicit TypeIntersection instance
      * @return
      *   the TypeIntersection instance
      */
    transparent inline def apply[A](using inline ts: TypeIntersection[A]): TypeIntersection[A] = ts

    /** Summons all instances of type class F for each component type in A.
      *
      * @tparam A
      *   the intersection type to decompose
      * @tparam F
      *   the type class to summon instances for
      * @return
      *   a List of type class instances
      */
    transparent inline def summonAll[A: TypeIntersection, F[_]]: List[ForSome[F]] =
        inlineAll[A](new SummonInliner[F])

    class SummonInliner[F[_]] extends Inliner[ForSome[F]]:
        inline def apply[T]: ForSome[F] =
            ForSome(summonInline[F[T]])

    inline def inlineAll[A]: InlineAllOps[A] = new InlineAllOps[A](())

    class InlineAllOps[A](dummy: Unit):
        /** Runs Inliner logic for each component type in A.
          *
          * @tparam A
          *   the intersection type to decompose
          * @tparam R
          *   the result type of inline logic
          * @return
          *   a List of type class instances
          */
        inline def apply[R](inliner: Inliner[R])(using ts: TypeIntersection[A]): List[R] =
            Inliner.inlineAllLoop[R, ts.AsTuple](inliner)
    end InlineAllOps

    /** Type alias for TypeIntersection with a specific tuple type.
      *
      * @tparam A
      *   the intersection type
      * @tparam T
      *   the tuple type representing the decomposed types
      */
    type Aux[A, T] =
        TypeIntersection[A]:
            type AsTuple = T

    /** Derives a TypeIntersection instance for type A.
      *
      * @tparam A
      *   the type to create a TypeIntersection for
      * @return
      *   a TypeIntersection instance for A
      */
    transparent inline given derive[A]: TypeIntersection[A] =
        ${ deriveImpl[A] }

    private def deriveImpl[A: Type](using Quotes): Expr[TypeIntersection[A]] =
        import quotes.reflect.*

        def decompose(tpe: TypeRepr): Vector[TypeRepr] =
            tpe match
                case AndType(l, r) =>
                    decompose(l) ++ decompose(r)
                case _ =>
                    if tpe =:= TypeRepr.of[Any] then Vector()
                    else Vector(tpe)

        def tupled(typs: Vector[TypeRepr]): TypeRepr =
            typs match
                case h +: t => TypeRepr.of[*:].appliedTo(List(h, tupled(t)))
                case _      => TypeRepr.of[EmptyTuple]

        tupled(decompose(TypeRepr.of[A].dealias)).asType match
            case '[type x <: Tuple; x] =>
                '{
                    cached.asInstanceOf[TypeIntersection.Aux[A, x]]
                }
        end match
    end deriveImpl
end TypeIntersection
