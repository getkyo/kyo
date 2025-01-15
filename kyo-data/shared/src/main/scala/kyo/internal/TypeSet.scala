package kyo.internal

import TypeSet.*
import scala.annotation.implicitNotFound
import scala.compiletime.erasedValue
import scala.compiletime.summonAll
import scala.compiletime.summonInline
import scala.quoted.*

/** A type-level utility for decomposing intersection types into their constituent parts.
  *
  * TypeSet addresses the challenge of working with intersection types in a type-safe manner, particularly when implementing type-level
  * operations like equality checking and type class derivation. It provides a way to:
  *
  *   - Break down complex intersection types into simpler components
  *   - Apply type constructors uniformly across all components
  *   - Collect type class instances for all component types
  */
sealed abstract class TypeSet[A]:

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

end TypeSet

object TypeSet:

    // Cached instance used for optimization.
    private val cached: TypeSet[Any] =
        new TypeSet[Any]:
            type AsTuple = EmptyTuple

    private type Join[A <: Tuple] = Tuple.Fold[A, Any, [B, C] =>> B & C]

    /** Returns the TypeSet instance for type A.
      *
      * @tparam A
      *   the type to get the TypeSet for
      * @param ts
      *   the implicit TypeSet instance
      * @return
      *   the TypeSet instance
      */
    transparent inline def apply[A](using inline ts: TypeSet[A]): TypeSet[A] = ts

    /** Summons all instances of type class F for each component type in A.
      *
      * @tparam A
      *   the intersection type to decompose
      * @tparam F
      *   the type class to summon instances for
      * @return
      *   a List of type class instances
      */
    transparent inline def summonAll[A: TypeSet as ts, F[_]]: List[F[Any]] =
        summonAllLoop[ts.AsTuple, F]

    /** Type alias for TypeSet with a specific tuple type.
      *
      * @tparam A
      *   the intersection type
      * @tparam T
      *   the tuple type representing the decomposed types
      */
    type Aux[A, T] =
        TypeSet[A]:
            type AsTuple = T

    /** Derives a TypeSet instance for type A.
      *
      * @tparam A
      *   the type to create a TypeSet for
      * @return
      *   a TypeSet instance for A
      */
    transparent inline given derive[A]: TypeSet[A] =
        ${ deriveImpl[A] }

    private def deriveImpl[A: Type](using Quotes): Expr[TypeSet[A]] =
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
            case '[
                type x <: Tuple; x] =>
                '{
                    cached.asInstanceOf[TypeSet.Aux[A, x]]
                }
        end match
    end deriveImpl

    private transparent inline def summonAllLoop[T <: Tuple, F[_]]: List[F[Any]] =
        inline erasedValue[T] match
            case _: EmptyTuple => Nil
            case _: (h1 *: h2 *: h3 *: h4 *: h5 *: h6 *: h7 *: h8 *: h9 *: h10 *: h11 *: h12 *: h13 *: h14 *: h15 *: h16 *: tail) =>
                summonInline[F[h1]].asInstanceOf[F[Any]] ::
                    summonInline[F[h2]].asInstanceOf[F[Any]] ::
                    summonInline[F[h3]].asInstanceOf[F[Any]] ::
                    summonInline[F[h4]].asInstanceOf[F[Any]] ::
                    summonInline[F[h5]].asInstanceOf[F[Any]] ::
                    summonInline[F[h6]].asInstanceOf[F[Any]] ::
                    summonInline[F[h7]].asInstanceOf[F[Any]] ::
                    summonInline[F[h8]].asInstanceOf[F[Any]] ::
                    summonInline[F[h9]].asInstanceOf[F[Any]] ::
                    summonInline[F[h10]].asInstanceOf[F[Any]] ::
                    summonInline[F[h11]].asInstanceOf[F[Any]] ::
                    summonInline[F[h12]].asInstanceOf[F[Any]] ::
                    summonInline[F[h13]].asInstanceOf[F[Any]] ::
                    summonInline[F[h14]].asInstanceOf[F[Any]] ::
                    summonInline[F[h15]].asInstanceOf[F[Any]] ::
                    summonInline[F[h16]].asInstanceOf[F[Any]] ::
                    summonAllLoop[tail, F]
            case _: (t *: ts) =>
                summonInline[F[t]].asInstanceOf[F[Any]] :: summonAllLoop[ts, F]
end TypeSet
