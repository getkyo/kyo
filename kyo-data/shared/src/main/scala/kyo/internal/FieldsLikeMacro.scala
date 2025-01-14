package kyo.internal

import kyo.Record.~
import kyo.Record.FieldsLike
import scala.quoted.*

object FieldsLikeMacro:
    def fieldsLikeImpl[A: Type](using
        Quotes
    ): Expr[FieldsLike[A]] =
        import quotes.reflect.*

        def decompose(base: TypeRepr): Vector[TypeRepr] =
            base match
                case AndType(l, r) =>
                    decompose(l) ++ decompose(r)
                case AppliedType(constr, _) if (constr =:= TypeRepr.of[~]) =>
                    Vector(base)
                case _ =>
                    if base =:= TypeRepr.of[Any] then Vector()
                    else
                        report.errorAndAbort(s"Unable to decompose param for ${base.show}")

        def tupled(typs: Vector[TypeRepr]): TypeRepr =
            typs match
                case h +: t => TypeRepr.of[*:].appliedTo(List(h, tupled(t)))
                case _      => TypeRepr.of[EmptyTuple]

        tupled(decompose(TypeRepr.of[A].dealias)).asType match
            case '[
                type x <: Tuple; x] =>
                '{
                    (new FieldsLike[A]:
                        type T = x
                    ): FieldsLike.Aux[A, x]
                }
        end match
    end fieldsLikeImpl
end FieldsLikeMacro
