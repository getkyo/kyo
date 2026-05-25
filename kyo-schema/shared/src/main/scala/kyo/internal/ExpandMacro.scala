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
                // Union types: flatten nested OrTypes, expand each leg, and rebuild the OrType. Each leg
                // gets the structural `~` tagging with the leg's simple type-symbol name, mirroring the
                // sealed-trait expansion shape so downstream Focus/Navigation logic treats them uniformly.
                case OrType(_, _) =>
                    val legs      = flattenOrType(dealiased)
                    val tildeType = TypeRepr.of[Record.~]
                    val tagged = legs.map { leg =>
                        val sym      = leg.typeSymbol
                        val legName  = if sym.exists then sym.name else leg.show
                        val nameType = ConstantType(StringConstant(legName))
                        val expanded = expandType(leg)
                        tildeType.appliedTo(List(nameType, expanded))
                    }
                    tagged.reduce(OrType(_, _))
                // Intersection types: flatten nested AndTypes, expand each half, and rebuild via Record.~
                // tagging with each half's simple type-symbol name. Mirrors the sealed-trait / union shape so
                // downstream Focus / Navigation logic treats them uniformly.
                case AndType(_, _) =>
                    val halves    = flattenAndType(dealiased)
                    val tildeType = TypeRepr.of[Record.~]
                    val tagged = halves.map { half =>
                        val sym      = half.typeSymbol
                        val halfName = if sym.exists then sym.name else half.show
                        val nameType = ConstantType(StringConstant(halfName))
                        val expanded = expandType(half)
                        tildeType.appliedTo(List(nameType, expanded))
                    }
                    tagged.reduce(AndType(_, _))
                case AppliedType(tycon, args) if isKnownContainer(tycon) =>
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
                    else if sym.flags.is(Flags.JavaDefined) && sym.flags.is(Flags.Enum) then
                        // Java enum: expand as sum of singleton variant names like the Scala sealed branch above.
                        val tildeType = TypeRepr.of[Record.~]
                        val variants = sym.children.map: child =>
                            val nameType = ConstantType(StringConstant(child.name))
                            tildeType.appliedTo(List(nameType, child.typeRef))
                        if variants.nonEmpty then variants.reduce(OrType(_, _))
                        else dealiased
                    else
                        // Fallback: identity
                        dealiased
                    end if
            end match
        end if
    end expandType

    private def isPrimitive(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
        import quotes.reflect.*
        tpe.dealias.asType match
            case '[t] => Expr.summon[kyo.PrimitiveKindFor[t]].isDefined
    end isPrimitive

    private def isStructural(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
        MacroUtils.isStructuralType(tpe)

    /** Flatten a (possibly nested) `OrType` into its leaf legs, drop `Nothing`, and deduplicate by `=:=`.
      *
      * Mirrors `UnionMacro.collectOrTypeLegs` so the structural expansion and the runtime schema both see
      * the same leg list. Rejects fully-degenerate unions (where dedup removed legs that weren't Nothing).
      */
    /** Flatten a (possibly nested) `AndType` into its leaf halves. */
    private def flattenAndType(using Quotes)(tpe: quotes.reflect.TypeRepr): List[quotes.reflect.TypeRepr] =
        import quotes.reflect.*
        def go(t: quotes.reflect.TypeRepr): List[quotes.reflect.TypeRepr] = t.dealias match
            case AndType(a, b) => go(a) ++ go(b)
            case other         => List(other)
        go(tpe)
    end flattenAndType

    private def flattenOrType(using Quotes)(tpe: quotes.reflect.TypeRepr): List[quotes.reflect.TypeRepr] =
        import quotes.reflect.*

        def go(t: TypeRepr): List[TypeRepr] = t.dealias match
            case OrType(a, b) => go(a) ++ go(b)
            case other        => List(other)

        val nothingTpe = TypeRepr.of[Nothing]
        val raw        = go(tpe).filterNot(_ =:= nothingTpe)

        val out = scala.collection.mutable.ListBuffer[TypeRepr]()
        for leg <- raw do
            if !out.exists(_ =:= leg) then out += leg
        val deduped = out.toList

        if deduped.isEmpty then
            report.errorAndAbort(s"Union type ${tpe.show} reduces to Nothing; no schema can be derived.")

        deduped
    end flattenOrType

    private def isKnownContainer(using Quotes)(tycon: quotes.reflect.TypeRepr): Boolean =
        import quotes.reflect.*
        val (collections, optionals, maps) = MacroUtils.containerSymbolsFromSchema
        val sym                            = tycon.typeSymbol
        collections.contains(sym) || optionals.contains(sym) || maps.contains(sym)
    end isKnownContainer

end ExpandMacro
