package kyo.internal

import kyo.*
import scala.quoted.*

/** Macro for deriving Structure.Type from a Scala type at compile time.
  *
  * Schema-driven: the compile-time path handles case classes (capturing field defaults / `optional` flags) and sealed
  * traits / enums (capturing variant enum-case names) at the type level, and applies type-shape detection for the
  * Optional / Collection / Mapping containers whose well-known type symbols are sourced from the Schema given set via
  * `MacroUtils.containerSymbolsFromSchema`. Primitives, transform chains, and the fallback path defer to
  * `Structure.fromSchema` at runtime, which inspects the Schema's `collectionElement` / `optionalInner` /
  * `mappingKey` / `mappingValue` / `transformSource` accessors so both paths agree on the produced Structure.Type.
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

        // 1. Recursion guard.
        val typeName = dealiased.typeSymbol.fullName
        if seen.contains(typeName) && typeName.nonEmpty then
            val name = dealiased.typeSymbol.name
            dealiased.asType match
                case '[a] =>
                    val tagExpr = summonTag[a]
                    '{ Structure.Type.Product(${ Expr(name) }, $tagExpr, kyo.Chunk.empty, kyo.Chunk.empty) }
            end match
        else
            // 2-4. Optional / Map / Collection detected by type-shape — the well-known container symbols are
            //      sourced from the Schema given set so this stays in sync with the Schema accessors used by
            //      `Structure.fromSchema`.
            dealiased match
                case AppliedType(tycon, List(inner)) if optionals.contains(tycon.typeSymbol) =>
                    val name     = dealiased.typeSymbol.name
                    val innerRef = deriveType(inner, seen, collections, optionals, maps)
                    dealiased.asType match
                        case '[a] =>
                            val tagExpr = summonTag[a]
                            '{ Structure.Type.Optional(${ Expr(name) }, $tagExpr, $innerRef) }
                    end match
                case AppliedType(tycon, List(k, v)) if maps.contains(tycon.typeSymbol) =>
                    val name     = dealiased.typeSymbol.name
                    val keyRef   = deriveType(k, seen, collections, optionals, maps)
                    val valueRef = deriveType(v, seen, collections, optionals, maps)
                    dealiased.asType match
                        case '[a] =>
                            val tagExpr = summonTag[a]
                            '{ Structure.Type.Mapping(${ Expr(name) }, $tagExpr, $keyRef, $valueRef) }
                    end match
                case AppliedType(tycon, List(elem)) if collections.contains(tycon.typeSymbol) =>
                    val name    = dealiased.typeSymbol.name
                    val elemRef = deriveType(elem, seen, collections, optionals, maps)
                    dealiased.asType match
                        case '[a] =>
                            val tagExpr = summonTag[a]
                            '{ Structure.Type.Collection(${ Expr(name) }, $tagExpr, $elemRef) }
                    end match
                case _ =>
                    val sym = dealiased.typeSymbol

                    // 5. Sealed trait / enum: type-level enumeration preserves recursion handling + enumValues capture.
                    if sym.isClassDef && sym.flags.is(Flags.Sealed) && sym.children.nonEmpty then
                        deriveSealedTrait(dealiased, sym, seen, collections, optionals, maps)
                    // 6. Case class: type-level fields walk preserves field defaults / optional detection.
                    else if sym.isClassDef && sym.flags.is(Flags.Case) then
                        deriveCaseClass(dealiased, sym, seen, collections, optionals, maps)
                    else
                        // 7. Defer to the Schema instance for primitives, transform chains, and any container shape
                        //    not detected by the type-shape arms above. The runtime walker uses the Schema accessors.
                        dealiased.asType match
                            case '[a] =>
                                Expr.summon[Schema[a]] match
                                    case Some(schemaExpr) =>
                                        '{ Structure.fromSchema[a]($schemaExpr) }
                                    case None =>
                                        val name    = sym.name
                                        val tagExpr = summonTag[a]
                                        '{ Structure.Type.Product(${ Expr(name) }, $tagExpr, kyo.Chunk.empty, kyo.Chunk.empty) }
                        end match
                    end if
            end match
        end if
    end deriveType

    private def deriveSealedTrait(using
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
        val children = sym.children
        val name     = sym.name
        val newSeen  = seen + sym.fullName

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
    end deriveSealedTrait

    private def deriveCaseClass(using
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
        val name    = sym.name
        val newSeen = seen + sym.fullName

        val fieldExprs = sym.caseFields.zipWithIndex.map { (field, idx) =>
            val fieldName = field.name
            val fieldType = dealiased.memberType(field)
            val fieldRef  = deriveType(fieldType, newSeen, collections, optionals, maps)

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

            val isOptional = optionals.contains(
                (fieldType.dealias match
                    case AppliedType(tycon, _) => tycon
                    case _                     => fieldType
                ).typeSymbol
            )

            '{ Structure.Field(${ Expr(fieldName) }, $fieldRef, Maybe.empty[String], $defaultExpr, ${ Expr(isOptional) }) }
        }

        val fieldsSeqExpr = Expr.ofSeq(fieldExprs)

        dealiased.asType match
            case '[a] =>
                val tagExpr = summonTag[a]
                '{ Structure.Type.Product(${ Expr(name) }, $tagExpr, kyo.Chunk.empty, kyo.Chunk.from($fieldsSeqExpr)) }
        end match
    end deriveCaseClass

    private def summonTag[A: Type](using Quotes): Expr[Tag[Any]] =
        import quotes.reflect.*
        Expr.summon[Tag[A]] match
            case Some(tagExpr) => '{ $tagExpr.asInstanceOf[Tag[Any]] }
            case None          => '{ Tag[Any] }
    end summonTag

end StructureMacro
