package kyo.internal

import scala.quoted.*

object Record2Macros:

    /** Extracts the value type for a given field name from an intersection of `~[Name, Value]` types. Works with open type variables (e.g.
      * `In & "name" ~ String`).
      */
    def selectDynamic[F: Type, Name <: String: Type](
        record: Expr[kyo.Record2[F]],
        name: Expr[Name]
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*

        val nameStr = TypeRepr.of[Name] match
            case ConstantType(StringConstant(s)) => s
            case _ => report.errorAndAbort(s"Field name must be a literal string type, got: ${TypeRepr.of[Name].show}")

        def findValueType(tpe: TypeRepr): Option[TypeRepr] =
            tpe.dealias match
                case AndType(l, r) =>
                    findValueType(l).orElse(findValueType(r))
                case AppliedType(_, List(ConstantType(StringConstant(n)), valueType)) if n == nameStr =>
                    Some(valueType)
                case _ =>
                    try
                        tpe.typeSymbol.tree match
                            case typeDef: TypeDef =>
                                typeDef.rhs match
                                    case bounds: TypeBoundsTree =>
                                        val hi = bounds.hi.tpe
                                        if !(hi =:= TypeRepr.of[Any]) then findValueType(hi)
                                        else None
                                    case _ => None
                            case _ => None
                    catch case _: Exception => None

        findValueType(TypeRepr.of[F]) match
            case Some(valueType) =>
                valueType.asType match
                    case '[v] =>
                        '{ $record.get($name).asInstanceOf[v] }
            case None =>
                report.errorAndAbort(
                    s"Field '$nameStr' not found in Record2[${TypeRepr.of[F].show}]"
                )
        end match
    end selectDynamic

    /** Structural subtyping conversion: only when A is a strict subtype of B (A <: B but not B <: A). */
    def widenImpl[A: Type, B: Type](using Quotes): Expr[Conversion[kyo.Record2[A], kyo.Record2[B]]] =
        import quotes.reflect.*
        val a = TypeRepr.of[A]
        val b = TypeRepr.of[B]
        if !(a <:< b) then
            report.errorAndAbort(s"${a.show} is not a subtype of ${b.show}")
        if b <:< a then
            report.errorAndAbort(s"Record2 conversion requires strict subtyping: ${a.show} =:= ${b.show}")
        '{ _.asInstanceOf[kyo.Record2[B]] }
    end widenImpl

    /** FieldsComparable: decomposes intersection and verifies each field's value type has CanEqual[V, V]. */
    def fieldsComparableImpl[A: Type](using Quotes): Expr[kyo.Record2.FieldsComparable[A]] =
        import quotes.reflect.*

        def decompose(tpe: TypeRepr): Vector[(String, TypeRepr)] =
            tpe.dealias match
                case AndType(l, r) =>
                    decompose(l) ++ decompose(r)
                case AppliedType(_, List(ConstantType(StringConstant(name)), valueType)) =>
                    Vector((name, valueType))
                case _ =>
                    if tpe =:= TypeRepr.of[Any] then Vector()
                    else
                        try
                            tpe.typeSymbol.tree match
                                case typeDef: TypeDef =>
                                    typeDef.rhs match
                                        case bounds: TypeBoundsTree =>
                                            val hi = bounds.hi.tpe
                                            if !(hi =:= TypeRepr.of[Any]) then decompose(hi)
                                            else Vector()
                                        case _ => Vector()
                                case _ => Vector()
                        catch case _: Exception => Vector()

        val fields = decompose(TypeRepr.of[A])

        for (name, valueType) <- fields do
            valueType.asType match
                case '[v] =>
                    Expr.summon[CanEqual[v, v]] match
                        case None =>
                            report.errorAndAbort(
                                s"Cannot compare records: field '$name' of type ${valueType.show} has no CanEqual instance"
                            )
                        case _ => ()
        end for

        '{ kyo.Record2.FieldsComparable.unsafe[A] }
    end fieldsComparableImpl

end Record2Macros
