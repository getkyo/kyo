package kyo.internal

import kyo.*
import kyo.Record.*
import scala.quoted.*

object FieldsMacros:

    /** If `tpe` is a case class, return its fields as `("name" ~ ValueType)` TypeReprs. */
    private def caseClassFields(using Quotes)(tpe: quotes.reflect.TypeRepr): Option[Vector[quotes.reflect.TypeRepr]] =
        import quotes.reflect.*
        val sym = tpe.typeSymbol
        if sym.isClassDef && sym.flags.is(Flags.Case) then
            val tildeType = TypeRepr.of[Record.~]
            val fields = sym.caseFields.map: field =>
                val fieldName = field.name
                val fieldType = tpe.memberType(field)
                val nameType  = ConstantType(StringConstant(fieldName))
                tildeType.appliedTo(List(nameType, fieldType))
            Some(fields.toVector)
        else
            None
        end if
    end caseClassFields

    def deriveImpl[A: Type](using Quotes): Expr[Fields[A]] =
        import quotes.reflect.*

        def decompose(tpe: TypeRepr): Vector[TypeRepr] =
            tpe.dealias match
                case AndType(l, r) => decompose(l) ++ decompose(r)
                case _ =>
                    if tpe =:= TypeRepr.of[Any] then Vector()
                    else
                        caseClassFields(tpe).getOrElse:
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

        def structural(typs: Vector[TypeRepr]): TypeRepr =
            if typs.isEmpty then
                TypeRepr.of[Fields.Structural]
            else
                val structType = typs.foldLeft(Map[String, TypeRepr]()) {
                    case (acc, AppliedType(_, List(ConstantType(StringConstant(name)), valueType))) =>
                        acc.get(name) match
                            case Some(x) => acc + (name -> OrType(x, valueType))
                            case None    => acc + (name -> valueType)
                    case (acc, _) => acc
                }.foldLeft(TypeRepr.of[Any]) {
                    case (acc, (name, valueType)) =>
                        Refinement(acc, name, ByNameType(valueType))
                }
                AndType(TypeRepr.of[Fields.Structural], structType)

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
                    val recordRepr = TypeRepr.of[Record]
                    val nestedExpr = valueType.dealias match
                        case AppliedType(recordRepr, List(f)) =>
                            f.asType match
                                case '[f] =>
                                    Expr.summon[Fields[f]] match
                                        case Some(fields) => '{ $fields.fields }
                                        case None         => '{ Nil: List[Field[?, ?]] }
                        case _ =>
                            '{ Nil: List[Field[?, ?]] }
                    Some(ComponentInfo(name, nameExpr, tagExpr, nestedExpr))
                case _ => None

        val infos = components.flatMap(extractComponent)
        val fieldsList = Expr.ofList(infos.map(ci =>
            '{ Field[String, Any](${ ci.nameExpr }, ${ ci.tagExpr }.asInstanceOf[Tag[Any]], ${ ci.nestedExpr }) }
        ).toList)

        (tupled(components).asType, structural(components).asType) match
            case ('[type x <: Tuple; x], '[type s <: Fields.Structural; s]) =>
                '{ Fields.createAux[A, x, s]($fieldsList) }
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
                        // Check case class fields
                        val sym = tpe.typeSymbol
                        if sym.isClassDef && sym.flags.is(Flags.Case) then
                            sym.caseFields.find(_.name == nameStr).map(f => tpe.memberType(f))
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
                        end if

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
                        val sym = tpe.typeSymbol
                        if sym.isClassDef && sym.flags.is(Flags.Case) then
                            sym.caseFields.map(f => (f.name, tpe.memberType(f))).toVector
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
                        end if

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

    def sameNamesImpl[A: Type, B: Type](using Quotes): Expr[Fields.SameNames[A, B]] =
        import quotes.reflect.*

        def fieldNames(tpe: TypeRepr): Set[String] =
            tpe.dealias match
                case AndType(l, r) => fieldNames(l) ++ fieldNames(r)
                case AppliedType(_, List(ConstantType(StringConstant(name)), _)) =>
                    Set(name)
                case _ =>
                    if tpe =:= TypeRepr.of[Any] then Set.empty
                    else
                        val sym = tpe.typeSymbol
                        if sym.isClassDef && sym.flags.is(Flags.Case) then
                            sym.caseFields.map(_.name).toSet
                        else
                            try
                                tpe.typeSymbol.tree match
                                    case typeDef: TypeDef =>
                                        typeDef.rhs match
                                            case bounds: TypeBoundsTree =>
                                                val hi = bounds.hi.tpe
                                                if !(hi =:= TypeRepr.of[Any]) then fieldNames(hi)
                                                else Set.empty
                                            case _ => Set.empty
                                    case _ => Set.empty
                            catch case _: Exception => Set.empty
                        end if

        val namesA = fieldNames(TypeRepr.of[A])
        val namesB = fieldNames(TypeRepr.of[B])

        if namesA != namesB then
            val onlyA = (namesA -- namesB).toList.sorted
            val onlyB = (namesB -- namesA).toList.sorted
            val parts = List(
                if onlyA.nonEmpty then Some(s"fields only in left: ${onlyA.mkString(", ")}") else None,
                if onlyB.nonEmpty then Some(s"fields only in right: ${onlyB.mkString(", ")}") else None
            ).flatten.mkString("; ")
            report.errorAndAbort(s"Cannot zip records with different fields: $parts")
        end if

        '{ Fields.SameNames.unsafe[A, B] }
    end sameNamesImpl

    def fromProductImpl[A <: Product: Type](value: Expr[A])(using Quotes): Expr[Any] =
        import quotes.reflect.*

        val tpe = TypeRepr.of[A].dealias
        val sym = tpe.typeSymbol

        if !sym.isClassDef || !sym.flags.is(Flags.Case) then
            report.errorAndAbort(s"fromProduct requires a case class, got: ${tpe.show}")

        val fields    = sym.caseFields
        val n         = fields.size
        val tildeType = TypeRepr.of[Record.~]

        val fieldsType =
            if fields.isEmpty then TypeRepr.of[Any]
            else
                fields.map { f =>
                    tildeType.appliedTo(List(ConstantType(StringConstant(f.name)), tpe.memberType(f)))
                }.reduce(AndType(_, _))

        // Build keys-first array: [k0, k1, ..., v0, v1, ...]
        // Uses direct field access (value.name) instead of productElement to avoid boxing
        val arrayExprs: List[Expr[Any]] =
            fields.map(f => Expr(f.name)) ++
                fields.map(f => Select.unique(value.asTerm, f.name).asExprOf[Any])

        fieldsType.asType match
            case '[f] =>
                '{
                    val arr = new Array[Any](${ Expr(n * 2) })
                    ${
                        Expr.block(
                            arrayExprs.zipWithIndex.map: (expr, i) =>
                                '{ arr(${ Expr(i) }) = $expr }.asTerm
                            .toList.map(_.asExprOf[Unit]),
                            '{ () }
                        )
                    }
                    Dict.fromArrayUnsafe(arr.asInstanceOf[Array[Any]]).asInstanceOf[Record[f]] // Record.from leads to cyclic macro error
                }
        end match
    end fromProductImpl

end FieldsMacros
