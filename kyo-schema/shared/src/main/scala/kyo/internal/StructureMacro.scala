package kyo.internal

import kyo.*
import scala.quoted.*

/** Macro for deriving Structure.Type from a Scala type at compile time.
  *
  * Handles case classes (Product), sealed traits (Sum), primitives, collections, optionals, mappings, and recursive types.
  */
object StructureMacro:

    def deriveImpl[A: Type](using Quotes): Expr[Structure.Type] =
        import quotes.reflect.*
        val (collections, optionals, maps) = MacroUtils.containerSymbolsFromSchema
        deriveType(TypeRepr.of[A], Set.empty, collections, optionals, maps)
    end deriveImpl

    private def deriveType(using
        Quotes
    )(
        tpe: quotes.reflect.TypeRepr,
        seen: Set[String],
        collections: Set[quotes.reflect.Symbol],
        optionals: Set[quotes.reflect.Symbol],
        maps: Set[quotes.reflect.Symbol]
    ): Expr[Structure.Type] =
        import quotes.reflect.*

        val dealiased = tpe.dealias

        // 1. Check for recursion
        val typeName = dealiased.typeSymbol.fullName
        if seen.contains(typeName) && typeName.nonEmpty then
            val name = dealiased.typeSymbol.name
            dealiased.asType match
                case '[a] =>
                    val tagExpr = summonTag[a]
                    '{ Structure.Type.Product(${ Expr(name) }, $tagExpr, kyo.Chunk.empty, kyo.Chunk.empty) }
            end match
        // 2. Check primitives
        else if isPrimitive(dealiased) then
            val kindExpr = primitiveKindExpr(dealiased)
            dealiased.asType match
                case '[a] =>
                    val tagExpr = summonTag[a]
                    '{ Structure.Type.Primitive($kindExpr, $tagExpr) }
            end match
        else
            // 3-5. Check optional, map, and collection types
            dealiased match
                case AppliedType(tycon, List(inner)) if isOptionalType(tycon, optionals) =>
                    val name     = dealiased.typeSymbol.name
                    val innerRef = deriveType(inner, seen, collections, optionals, maps)
                    dealiased.asType match
                        case '[a] =>
                            val tagExpr = summonTag[a]
                            '{ Structure.Type.Optional(${ Expr(name) }, $tagExpr, $innerRef) }
                    end match
                case AppliedType(tycon, List(k, v)) if isMapType(tycon, maps) =>
                    val name     = dealiased.typeSymbol.name
                    val keyRef   = deriveType(k, seen, collections, optionals, maps)
                    val valueRef = deriveType(v, seen, collections, optionals, maps)
                    dealiased.asType match
                        case '[a] =>
                            val tagExpr = summonTag[a]
                            '{ Structure.Type.Mapping(${ Expr(name) }, $tagExpr, $keyRef, $valueRef) }
                    end match
                case AppliedType(tycon, List(elem)) if isCollectionType(tycon, collections) =>
                    val name    = dealiased.typeSymbol.name
                    val elemRef = deriveType(elem, seen, collections, optionals, maps)
                    dealiased.asType match
                        case '[a] =>
                            val tagExpr = summonTag[a]
                            '{ Structure.Type.Collection(${ Expr(name) }, $tagExpr, $elemRef) }
                    end match
                case _ =>
                    val sym = dealiased.typeSymbol

                    // 6. Sealed trait / enum
                    if sym.isClassDef && sym.flags.is(Flags.Sealed) then
                        val children = sym.children
                        if children.nonEmpty then
                            val name    = sym.name
                            val newSeen = seen + sym.fullName

                            // Collect enum singleton names
                            val enumValues = children.collect {
                                case child if child.flags.is(Flags.Module) || !child.isClassDef =>
                                    child.name.stripSuffix("$")
                            }

                            val variantExprs = children.map { child =>
                                val childName = child.name.stripSuffix("$")
                                val childType =
                                    if child.isType then child.typeRef
                                    else if child.flags.is(Flags.Module) then child.termRef.widen
                                    else child.typeRef
                                val variantRef = deriveType(childType, newSeen, collections, optionals, maps)
                                '{ Structure.Variant(${ Expr(childName) }, $variantRef) }
                            }

                            val variantsSeqExpr = Expr.ofSeq(variantExprs)
                            val enumValuesExpr  = Expr.ofSeq(enumValues.map(Expr(_)))

                            dealiased.asType match
                                case '[a] =>
                                    val tagExpr = summonTag[a]
                                    '{
                                        Structure.Type.Sum(
                                            ${ Expr(name) },
                                            $tagExpr,
                                            kyo.Chunk.empty,
                                            kyo.Chunk.from($variantsSeqExpr),
                                            kyo.Chunk.from($enumValuesExpr)
                                        )
                                    }
                            end match
                        else
                            deriveCaseClassOrFallback(dealiased, sym, seen, collections, optionals, maps)
                        end if
                    else
                        deriveCaseClassOrFallback(dealiased, sym, seen, collections, optionals, maps)
                    end if
            end match
        end if
    end deriveType

    /** Derives a case class (Product) or falls back to Primitive for non-case-class types. */
    private def deriveCaseClassOrFallback(using
        Quotes
    )(
        dealiased: quotes.reflect.TypeRepr,
        sym: quotes.reflect.Symbol,
        seen: Set[String],
        collections: Set[quotes.reflect.Symbol],
        optionals: Set[quotes.reflect.Symbol],
        maps: Set[quotes.reflect.Symbol]
    ): Expr[Structure.Type] =
        import quotes.reflect.*

        // 7. Case class
        if sym.isClassDef && sym.flags.is(Flags.Case) then
            val name    = sym.name
            val newSeen = seen + sym.fullName

            val fieldExprs = sym.caseFields.zipWithIndex.map { (field, idx) =>
                val fieldName = field.name
                val fieldType = dealiased.memberType(field)
                val fieldRef  = deriveType(fieldType, newSeen, collections, optionals, maps)

                // Check for default value — convert to Structure.Value
                val defaultExpr: Expr[Maybe[Structure.Value]] = MacroUtils.getDefault(sym, idx) match
                    case Some(defVal) =>
                        fieldType.asType match
                            case '[t] =>
                                Expr.summon[Tag[t]] match
                                    case Some(tagExpr) =>
                                        '{ Maybe(Structure.Value.primitive[t]($defVal.asInstanceOf[t])(using $tagExpr)) }
                                    case None =>
                                        '{ Maybe.empty[Structure.Value] }
                    case None =>
                        '{ Maybe.empty[Structure.Value] }

                // Check if the field type is optional
                val isOptional = isOptionalType(
                    fieldType.dealias match
                        case AppliedType(tycon, _) => tycon
                        case _                     => fieldType,
                    optionals
                )

                '{ Structure.Field(${ Expr(fieldName) }, $fieldRef, Maybe.empty[String], $defaultExpr, ${ Expr(isOptional) }) }
            }

            val fieldsSeqExpr = Expr.ofSeq(fieldExprs)

            dealiased.asType match
                case '[a] =>
                    val tagExpr = summonTag[a]
                    '{ Structure.Type.Product(${ Expr(name) }, $tagExpr, kyo.Chunk.empty, kyo.Chunk.from($fieldsSeqExpr)) }
            end match
        else
            // 8. Fallback — treat as a nominal, fieldless Product.
            // We can no longer emit Primitive here: Primitive now carries a typed
            // PrimitiveKind enum and this branch handles arbitrary non-primitive,
            // non-case-class, non-sealed types (e.g. Java classes, opaque types)
            // for which there is no corresponding kind.
            val name = dealiased.typeSymbol.name
            dealiased.asType match
                case '[a] =>
                    val tagExpr = summonTag[a]
                    '{ Structure.Type.Product(${ Expr(name) }, $tagExpr, kyo.Chunk.empty, kyo.Chunk.empty) }
            end match
        end if
    end deriveCaseClassOrFallback

    private def summonTag[A: Type](using Quotes): Expr[Tag[Any]] =
        import quotes.reflect.*
        Expr.summon[Tag[A]] match
            case Some(tagExpr) => '{ $tagExpr.asInstanceOf[Tag[Any]] }
            case None          =>
                // Fallback: use Tag[Any]
                '{ Tag[Any] }
        end match
    end summonTag

    private def isPrimitive(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
        import quotes.reflect.*
        tpe.dealias.asType match
            case '[t] => Expr.summon[kyo.PrimitiveKindFor[t]].isDefined
    end isPrimitive

    /** Maps a primitive TypeRepr to the corresponding `Structure.PrimitiveKind` expression by summoning the `PrimitiveKindFor[T]`
      * typeclass. The set of givens on `PrimitiveKindFor`'s companion is the single source of truth for which scalar types map to a
      * `Structure.PrimitiveKind`.
      */
    private def primitiveKindExpr(using Quotes)(tpe: quotes.reflect.TypeRepr): Expr[Structure.PrimitiveKind] =
        import quotes.reflect.*
        tpe.dealias.asType match
            case '[t] =>
                Expr.summon[kyo.PrimitiveKindFor[t]] match
                    case Some(p) => '{ $p.kind }
                    case None    => report.errorAndAbort(s"No PrimitiveKindFor[${tpe.show}] in scope.")
        end match
    end primitiveKindExpr

    private def isOptionalType(using
        Quotes
    )(
        tycon: quotes.reflect.TypeRepr,
        optionals: Set[quotes.reflect.Symbol]
    ): Boolean =
        optionals.contains(tycon.typeSymbol)
    end isOptionalType

    private def isCollectionType(using
        Quotes
    )(
        tycon: quotes.reflect.TypeRepr,
        collections: Set[quotes.reflect.Symbol]
    ): Boolean =
        collections.contains(tycon.typeSymbol)
    end isCollectionType

    private def isMapType(using
        Quotes
    )(
        tycon: quotes.reflect.TypeRepr,
        maps: Set[quotes.reflect.Symbol]
    ): Boolean =
        maps.contains(tycon.typeSymbol)
    end isMapType

end StructureMacro
