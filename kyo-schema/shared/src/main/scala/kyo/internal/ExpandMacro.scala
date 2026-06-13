package kyo.internal

import kyo.*
import kyo.Record.*
import scala.quoted.*

/** Type expansion for Schema.apply[A] and navigation paths.
  *
  * Expands a case class type `A` to its structural Record shape (intersection of `"field" ~
  * FieldType` pairs). A sealed trait expands to a union of `"Variant" ~ VariantType` pairs. Non-case
  * types (primitives, opaque types, type-parameterized type constructors with no case-class
  * specialization) are returned unchanged so navigation macros can still pattern-match on the raw
  * shape.
  *
  * This module performs NO type-symbol classification of user case-class field types: there is no
  * primitive table, no container table. The classification that does happen is local to the type's
  * structural shape (case-class case fields vs sealed children) and is uniform.
  */
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

        // Structural types and `~` applications are passed through as-is.
        if MacroUtils.isStructuralType(dealiased) then dealiased
        else
            val sym = dealiased.typeSymbol

            // Sealed traits / enums first (an enum case can itself be a case class).
            if sym.isClassDef && sym.flags.is(Flags.Sealed) then
                val children = sym.children
                if children.nonEmpty then
                    val tildeType = TypeRepr.of[Record.~]
                    val variants = children.map: child =>
                        val childName = child.name
                        val nameType  = ConstantType(StringConstant(childName))
                        val childType =
                            if child.isType then child.typeRef
                            else if child.flags.is(Flags.Module) then child.termRef.widen
                            else child.typeRef
                        tildeType.appliedTo(List(nameType, childType))
                    variants.reduce(OrType(_, _))
                else if sym.flags.is(Flags.Case) then
                    expandAsCaseClass(dealiased, sym)
                else dealiased
                end if
            else if sym.isClassDef && sym.flags.is(Flags.Case) then
                expandAsCaseClass(dealiased, sym)
            else
                // Recurse into applied-type arguments so navigation can peek at element types.
                // The recursion is structural; it does NOT introduce any classifier specialization
                // of `tycon` symbols (List, Option, etc. are not enumerated).
                dealiased match
                    case AppliedType(tycon, args) =>
                        tycon.appliedTo(args.map(expandType))
                    case _ =>
                        dealiased
                end match
            end if
        end if
    end expandType

    private def expandAsCaseClass(using Quotes)(dealiased: quotes.reflect.TypeRepr, sym: quotes.reflect.Symbol): quotes.reflect.TypeRepr =
        import quotes.reflect.*
        val tildeType = TypeRepr.of[Record.~]
        val fields = sym.caseFields.map: field =>
            val fieldName = field.name
            val fieldType = dealiased.memberType(field)
            val nameType  = ConstantType(StringConstant(fieldName))
            tildeType.appliedTo(List(nameType, fieldType))
        if fields.nonEmpty then fields.reduce(AndType(_, _))
        else dealiased
    end expandAsCaseClass

end ExpandMacro
