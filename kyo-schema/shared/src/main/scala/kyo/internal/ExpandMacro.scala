package kyo.internal

import kyo.*
import kyo.Record.*
import scala.quoted.*

object ExpandMacro:

    def expandImpl[A: Type](using Quotes): Expr[Any] =
        import quotes.reflect.*

        val expanded = expandType(TypeRepr.of[A])
        expanded.asType match
            case '[t] =>
                '{ null.asInstanceOf[t] }
    end expandImpl

    private[internal] def expandType(using Quotes)(tpe: quotes.reflect.TypeRepr): quotes.reflect.TypeRepr =
        import quotes.reflect.*

        val dealiased = tpe.dealias

        // Check primitives
        if isPrimitive(dealiased) then dealiased
        // Check if already structural (contains ~ at top level via AndType/OrType of applied ~)
        else if isStructural(dealiased) then dealiased
        else
            // Check containers BEFORE sealed trait/case class (List, Option, etc. are sealed but should be treated as containers)
            dealiased match
                case AppliedType(tycon, args) if isKnownContainer(dealiased) =>
                    val expandedArgs = args.map(expandType)
                    tycon.appliedTo(expandedArgs)
                case _ =>
                    val sym = dealiased.typeSymbol

                    // Check sealed trait / enum (must be checked before case class since enum cases can be case classes)
                    if sym.isClassDef && sym.flags.is(Flags.Sealed) then
                        val children = sym.children
                        if children.nonEmpty then
                            val tildeType = TypeRepr.of[Record.~]
                            val variants = children.map: child =>
                                val childName = child.name
                                val nameType  = ConstantType(StringConstant(childName))
                                val childType =
                                    if child.isType then
                                        // For type members (enum cases without params), get the type
                                        child.typeRef
                                    else if child.flags.is(Flags.Module) then
                                        // Singleton enum case: use the singleton type
                                        child.termRef.widen
                                    else
                                        // Class case: use the class type, applying parent type args if needed
                                        child.typeRef
                                tildeType.appliedTo(List(nameType, childType))
                            variants.reduce(OrType(_, _))
                        else if sym.flags.is(Flags.Case) then
                            // Case class that is also sealed with no children
                            val tildeType = TypeRepr.of[Record.~]
                            val fields = sym.caseFields.map: field =>
                                val fieldName = field.name
                                val fieldType = dealiased.memberType(field)
                                val nameType  = ConstantType(StringConstant(fieldName))
                                tildeType.appliedTo(List(nameType, fieldType))
                            if fields.nonEmpty then fields.reduce(AndType(_, _))
                            else dealiased
                        else
                            // Fallback: identity
                            dealiased
                        end if
                    // Check case class
                    else if sym.isClassDef && sym.flags.is(Flags.Case) then
                        val tildeType = TypeRepr.of[Record.~]
                        val fields = sym.caseFields.map: field =>
                            val fieldName = field.name
                            val fieldType = dealiased.memberType(field)
                            val nameType  = ConstantType(StringConstant(fieldName))
                            tildeType.appliedTo(List(nameType, fieldType))
                        if fields.nonEmpty then fields.reduce(AndType(_, _))
                        else dealiased
                    else
                        // Fallback: identity
                        dealiased
                    end if
            end match
        end if
    end expandType

    private def isPrimitive(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
        MacroUtils.MacroSchemaClassifier.fieldKindFor(tpe) match
            case _: MacroUtils.MacroSchemaClassifier.FieldKind.Primitive => true
            case _                                                       => false
    end isPrimitive

    private def isStructural(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
        MacroUtils.isStructuralType(tpe)

    // Note: caller passes the full applied type (dealiased), not just the tycon,
    // so that Expr.summon[Schema[List[Int]]] can materialize.
    private def isKnownContainer(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
        MacroUtils.MacroSchemaClassifier.fieldKindFor(tpe) match
            case MacroUtils.MacroSchemaClassifier.FieldKind.Collection => true
            case MacroUtils.MacroSchemaClassifier.FieldKind.Optional   => true
            case MacroUtils.MacroSchemaClassifier.FieldKind.Mapping    => true
            case _                                                     => false
    end isKnownContainer

end ExpandMacro
