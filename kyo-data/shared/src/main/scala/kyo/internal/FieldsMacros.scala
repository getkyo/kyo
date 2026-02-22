package kyo.internal

import kyo.*
import kyo.Record2.*
import scala.quoted.*

object FieldsMacros:

    def deriveImpl[A: Type](using Quotes): Expr[Fields[A]] =
        import quotes.reflect.*

        def decompose(tpe: TypeRepr): Vector[TypeRepr] =
            tpe.dealias match
                case AndType(l, r) => decompose(l) ++ decompose(r)
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
                                            else Vector(tpe)
                                        case _ => Vector(tpe)
                                case _ => Vector(tpe)
                        catch case _: Exception => Vector(tpe)

        def tupled(typs: Vector[TypeRepr]): TypeRepr =
            typs match
                case h +: t => TypeRepr.of[*:].appliedTo(List(h, tupled(t)))
                case _      => TypeRepr.of[EmptyTuple]

        val components = decompose(TypeRepr.of[A].dealias)

        case class ComponentInfo(name: String, nameExpr: Expr[String], tagExpr: Expr[Any], nestedExpr: Expr[List[Field[?, ?]]])

        def extractComponent(tpe: TypeRepr): Option[ComponentInfo] =
            tpe match
                case AppliedType(_, List(ConstantType(StringConstant(name)), valueType)) =>
                    val nameExpr = Expr(name)
                    val tagExpr = valueType.asType match
                        case '[v] =>
                            Expr.summon[Tag[v]].getOrElse(
                                report.errorAndAbort(s"Cannot summon Tag for field '$name': ${valueType.show}")
                            )
                    val nestedExpr = valueType.asType match
                        case '[Record2[f]] =>
                            Expr.summon[Fields[f]] match
                                case Some(fields) => '{ $fields.fields }
                                case None         => '{ Nil: List[Field[?, ?]] }
                        case _ => '{ Nil: List[Field[?, ?]] }
                    Some(ComponentInfo(name, nameExpr, tagExpr, nestedExpr))
                case _ => None

        val infos    = components.flatMap(extractComponent)
        val nameArgs = infos.map(_.nameExpr).toList
        val namesSet = '{ Set(${ Varargs(nameArgs) }*) }
        val fieldsList = Expr.ofList(infos.map(ci =>
            '{ Field[String, Any](${ ci.nameExpr }, ${ ci.tagExpr }.asInstanceOf[Tag[Any]], ${ ci.nestedExpr }) }
        ).toList)

        tupled(components).asType match
            case '[type x <: Tuple; x] =>
                '{ Fields.createAux[A, x]($namesSet, $fieldsList) }
        end match
    end deriveImpl

    def haveImpl[F: Type, Name <: String: Type](using Quotes): Expr[Fields.Have[F, Name]] =
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
                    if tpe =:= TypeRepr.of[Any] then None
                    else
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
                        '{ Fields.Have.unsafe[F, Name, v] }
            case None =>
                report.errorAndAbort(
                    s"Field '$nameStr' not found in ${TypeRepr.of[F].show}"
                )
        end match
    end haveImpl

    def comparableImpl[A: Type](using Quotes): Expr[Fields.Comparable[A]] =
        import quotes.reflect.*

        def decompose(tpe: TypeRepr): Vector[(String, TypeRepr)] =
            tpe.dealias match
                case AndType(l, r) => decompose(l) ++ decompose(r)
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

        for (name, valueType) <- decompose(TypeRepr.of[A]) do
            valueType.asType match
                case '[v] =>
                    Expr.summon[CanEqual[v, v]] match
                        case None =>
                            report.errorAndAbort(
                                s"Cannot compare records: field '$name' of type ${valueType.show} has no CanEqual instance"
                            )
                        case _ => ()
        end for

        '{ Fields.Comparable.unsafe[A] }
    end comparableImpl

end FieldsMacros
