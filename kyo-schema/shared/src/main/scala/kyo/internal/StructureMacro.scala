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
        deriveType(TypeRepr.of[A], Set.empty)
    end deriveImpl

    private def deriveType(using Quotes)(tpe: quotes.reflect.TypeRepr, seen: Set[String]): Expr[Structure.Type] =
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
            val name = dealiased.typeSymbol.name
            dealiased.asType match
                case '[a] =>
                    val tagExpr = summonTag[a]
                    '{ Structure.Type.Primitive(${ Expr(name) }, $tagExpr) }
            end match
        else
            // 3-5. Check optional, map, and collection types
            dealiased match
                case AppliedType(tycon, List(inner)) if isOptionalType(tycon) =>
                    val name     = dealiased.typeSymbol.name
                    val innerRef = deriveType(inner, seen)
                    dealiased.asType match
                        case '[a] =>
                            val tagExpr = summonTag[a]
                            '{ Structure.Type.Optional(${ Expr(name) }, $tagExpr, $innerRef) }
                    end match
                case AppliedType(tycon, List(k, v)) if isMapType(tycon) =>
                    val name     = dealiased.typeSymbol.name
                    val keyRef   = deriveType(k, seen)
                    val valueRef = deriveType(v, seen)
                    dealiased.asType match
                        case '[a] =>
                            val tagExpr = summonTag[a]
                            '{ Structure.Type.Mapping(${ Expr(name) }, $tagExpr, $keyRef, $valueRef) }
                    end match
                case AppliedType(tycon, List(elem)) if isCollectionType(tycon) =>
                    val name    = dealiased.typeSymbol.name
                    val elemRef = deriveType(elem, seen)
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
                                val variantRef = deriveType(childType, newSeen)
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
                            deriveCaseClassOrFallback(dealiased, sym, seen)
                        end if
                    else
                        deriveCaseClassOrFallback(dealiased, sym, seen)
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
        seen: Set[String]
    ): Expr[Structure.Type] =
        import quotes.reflect.*

        // 7. Case class
        if sym.isClassDef && sym.flags.is(Flags.Case) then
            val name    = sym.name
            val newSeen = seen + sym.fullName

            val fieldExprs = sym.caseFields.zipWithIndex.map { (field, idx) =>
                val fieldName = field.name
                val fieldType = dealiased.memberType(field)
                val fieldRef  = deriveType(fieldType, newSeen)

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
                val isOptional = isOptionalType(fieldType.dealias match
                    case AppliedType(tycon, _) => tycon
                    case _                     => fieldType)

                '{ Structure.Field(${ Expr(fieldName) }, $fieldRef, Maybe.empty[String], $defaultExpr, ${ Expr(isOptional) }) }
            }

            val fieldsSeqExpr = Expr.ofSeq(fieldExprs)

            dealiased.asType match
                case '[a] =>
                    val tagExpr = summonTag[a]
                    '{ Structure.Type.Product(${ Expr(name) }, $tagExpr, kyo.Chunk.empty, kyo.Chunk.from($fieldsSeqExpr)) }
            end match
        else
            // 8. Fallback — treat as primitive
            val name = dealiased.typeSymbol.name
            dealiased.asType match
                case '[a] =>
                    val tagExpr = summonTag[a]
                    '{ Structure.Type.Primitive(${ Expr(name) }, $tagExpr) }
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
        MacroUtils.extendedPrimitiveSymbols.contains(tpe.dealias.typeSymbol)
    end isPrimitive

    private def isOptionalType(using Quotes)(tycon: quotes.reflect.TypeRepr): Boolean =
        MacroUtils.optionalSymbols.contains(tycon.typeSymbol)
    end isOptionalType

    private def isCollectionType(using Quotes)(tycon: quotes.reflect.TypeRepr): Boolean =
        MacroUtils.collectionSymbols.contains(tycon.typeSymbol)
    end isCollectionType

    private def isMapType(using Quotes)(tycon: quotes.reflect.TypeRepr): Boolean =
        MacroUtils.mapSymbols.contains(tycon.typeSymbol)
    end isMapType

end StructureMacro
