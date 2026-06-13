package kyo.internal

import kyo.*
import kyo.Codec.Reader
import kyo.Codec.Writer
import kyo.Record.*
import scala.annotation.publicInBinary
import scala.compiletime.summonInline
import scala.quoted.*

/** Macro implementations for Focus navigation, `Schema.apply[A]`, and `Schema.derived[A]`.
  *
  * Focus navigation resolves field access by name using the structural type `F`, delegating to
  * [[NavigationMacro]] for field resolution.
  *
  * Schema derivation walks the type symbol's case fields (for case classes) or children (for sealed
  * traits). For each field/variant it emits a thunk wrapping
  * `scala.compiletime.summonInline[Schema[ft]]` and assembles a runtime field/variant table consumed
  * by [[SchemaCodecRuntime]]. The macro never pattern-matches on a specific container or primitive
  * type symbol: every nested type resolves via `summonInline` at the inline-expansion phase, which
  * sees forward-references to the in-flight `derived$Schema` and so handles recursion without any
  * special-case in the macro.
  */
@publicInBinary private[kyo] object FocusMacro:

    // ==========================================================================
    // Focus navigation (Focus.Select.selectDynamic, Schema.focus / focusChain / foreachChain)
    // ==========================================================================

    /** selectDynamic implementation for Focus.Select[A, F] with state composition.
      *
      * Returns Focus.Select[A, V] where V is the resolved field value type. Composes
      * getter/setter/segments for the navigation chain.
      */
    def focusSelectImpl[A: Type, F: Type, Name <: String: Type](
        focus: Expr[Focus.Select[A, F]],
        name: Expr[Name]
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*

        val resolved  = NavigationMacro.resolve[F, Name]
        val nameStr   = resolved.nameStr
        val valueType = resolved.valueType.asInstanceOf[TypeRepr]
        val isSum     = resolved.isSum

        val nominalType = MacroUtils.deriveNominalType[A, F]

        nominalType.asType match
            case '[n] =>
                if isSum then
                    generateSumFocus[A, F, n](focus, nameStr, valueType)
                else
                    generateProductFocus[A, F, n](focus, nameStr, valueType)
        end match
    end focusSelectImpl

    private def generateProductFocus[A: Type, F: Type, Nominal: Type](using
        Quotes
    )(
        focus: Expr[Focus.Select[A, F]],
        fieldName: String,
        valueType: quotes.reflect.TypeRepr
    ): Expr[Any] =
        import quotes.reflect.*

        val nominalType = TypeRepr.of[Nominal]
        val sym         = nominalType.typeSymbol

        if !sym.isClassDef || !sym.flags.is(Flags.Case) then
            report.errorAndAbort(
                s"Cannot navigate field '$fieldName': type ${nominalType.show} is not a case class. " +
                    "Field navigation is only supported on case class types. Define the type as a case class or navigate through a case class field."
            )
        end if

        val caseFields  = sym.caseFields
        val directField = caseFields.find(_.name == fieldName)

        valueType.asType match
            case '[v] =>
                val nameExpr = Expr(fieldName)

                directField match
                    case Some(_) =>
                        generateDirectFocus[A, F, Nominal, v](focus, fieldName, caseFields, sym, nominalType, nameExpr)
                    case None =>
                        val expandedF     = ExpandMacro.expandType(TypeRepr.of[F])
                        val focusedFields = MacroUtils.collectFields(expandedF)
                        val focusedNames  = focusedFields.map(_._1).toSet

                        val missingFromFocused = caseFields.filterNot(cf => focusedNames.contains(cf.name))
                        val renamedFrom        = missingFromFocused.find(cf => nominalType.memberType(cf) =:= valueType)

                        renamedFrom match
                            case Some(originalField) =>
                                generateRenamedFocus[A, F, Nominal, v](
                                    focus,
                                    fieldName,
                                    originalField.name,
                                    caseFields,
                                    sym,
                                    nominalType,
                                    nameExpr
                                )
                            case None =>
                                generateComputedFocus[A, F, v](focus, fieldName, nameExpr)
                        end match
                end match
        end match
    end generateProductFocus

    private def generateDirectFocus[A: Type, F: Type, Nominal: Type, V: Type](using
        Quotes
    )(
        focus: Expr[Focus.Select[A, F]],
        fieldName: String,
        caseFields: List[quotes.reflect.Symbol],
        sym: quotes.reflect.Symbol,
        nominalType: quotes.reflect.TypeRepr,
        nameExpr: Expr[String]
    ): Expr[Any] =
        import quotes.reflect.*

        val getterExpr = '{ (root: A) =>
            $focus.getter.asInstanceOf[A => Maybe[Any]](root).map { (parent: Any) =>
                ${
                    val typedParent = '{ parent.asInstanceOf[Nominal] }.asTerm
                    Select.unique(typedParent, fieldName).asExprOf[V]
                }
            }
        }

        val setterExpr = '{ (root: A, newValue: V) =>
            $focus.getter.asInstanceOf[A => Maybe[Any]](root) match
                case Maybe.Present(currentParent) =>
                    ${
                        val typedParent   = '{ currentParent.asInstanceOf[Nominal] }
                        val typedNewValue = '{ newValue }
                        val args: List[Term] = caseFields.map: field =>
                            if field.name == fieldName then
                                typedNewValue.asTerm
                            else
                                Select.unique(typedParent.asTerm, field.name)

                        val companion = Ref(sym.companionModule)
                        val typeArgs = nominalType match
                            case AppliedType(_, targs) => targs
                            case _                     => List.empty
                        val constructorCall = Select.overloaded(companion, "apply", typeArgs, args)
                        val setExpr         = constructorCall.asExprOf[Any]
                        '{ $focus.setter.asInstanceOf[(A, Any) => A](root, $setExpr) }
                    }
                case _ => root
        }

        '{ Focus.Select.create[A, V]($getterExpr, $setterExpr, $focus.segments :+ $nameExpr, $focus.isPartial, $focus.schema) }
    end generateDirectFocus

    private def generateRenamedFocus[A: Type, F: Type, Nominal: Type, V: Type](using
        Quotes
    )(
        focus: Expr[Focus.Select[A, F]],
        fieldName: String,
        originalName: String,
        caseFields: List[quotes.reflect.Symbol],
        sym: quotes.reflect.Symbol,
        nominalType: quotes.reflect.TypeRepr,
        nameExpr: Expr[String]
    ): Expr[Any] =
        import quotes.reflect.*

        val getterExpr = '{ (root: A) =>
            $focus.getter.asInstanceOf[A => Maybe[Any]](root).map { (parent: Any) =>
                ${
                    val typedParent = '{ parent.asInstanceOf[Nominal] }.asTerm
                    Select.unique(typedParent, originalName).asExprOf[V]
                }
            }
        }

        val setterExpr = '{ (root: A, newValue: V) =>
            $focus.getter.asInstanceOf[A => Maybe[Any]](root) match
                case Maybe.Present(currentParent) =>
                    ${
                        val typedParent   = '{ currentParent.asInstanceOf[Nominal] }
                        val typedNewValue = '{ newValue }
                        val args: List[Term] = caseFields.map: field =>
                            if field.name == originalName then
                                typedNewValue.asTerm
                            else
                                Select.unique(typedParent.asTerm, field.name)

                        val companion = Ref(sym.companionModule)
                        val typeArgs = nominalType match
                            case AppliedType(_, targs) => targs
                            case _                     => List.empty
                        val constructorCall = Select.overloaded(companion, "apply", typeArgs, args)
                        val setExpr         = constructorCall.asExprOf[Any]
                        '{ $focus.setter.asInstanceOf[(A, Any) => A](root, $setExpr) }
                    }
                case _ => root
        }

        '{ Focus.Select.create[A, V]($getterExpr, $setterExpr, $focus.segments :+ $nameExpr, $focus.isPartial, $focus.schema) }
    end generateRenamedFocus

    private def generateComputedFocus[A: Type, F: Type, V: Type](using
        Quotes
    )(
        focus: Expr[Focus.Select[A, F]],
        fieldName: String,
        nameExpr: Expr[String]
    ): Expr[Any] =
        val fieldNameExpr = Expr(fieldName)

        val getterExpr = '{ (root: A) =>
            $focus.getter.asInstanceOf[A => Maybe[Any]](root).map { (_: Any) =>
                val schema = $focus.schema.get.asInstanceOf[Schema[A]]
                val computeFn = schema.computedFields.toSeq.find(_._1 == $fieldNameExpr)
                    .getOrElse(throw new RuntimeException(s"Computed field '${$fieldNameExpr}' not found in schema"))
                    ._2
                computeFn(root).asInstanceOf[V]
            }
        }

        val setterExpr = '{ (root: A, _: V) => root }

        '{ Focus.Select.create[A, V]($getterExpr, $setterExpr, $focus.segments :+ $nameExpr, $focus.isPartial, $focus.schema) }
    end generateComputedFocus

    private def generateSumFocus[A: Type, F: Type, Nominal: Type](using
        Quotes
    )(
        focus: Expr[Focus.Select[A, F]],
        variantName: String,
        valueType: quotes.reflect.TypeRepr
    ): Expr[Any] =
        valueType.asType match
            case '[v] =>
                val nameExpr = Expr(variantName)

                val getterExpr = '{ (root: A) =>
                    $focus.getter.asInstanceOf[A => Maybe[Any]](root).flatMap { (parent: Any) =>
                        parent match
                            case x: v => Maybe(x)
                            case _    => Maybe.empty[v]
                    }
                }

                val setterExpr = '{ (root: A, value: v) =>
                    $focus.setter.asInstanceOf[(A, Any) => A](root, value)
                }

                '{ Focus.Select.create[A, v]($getterExpr, $setterExpr, $focus.segments :+ $nameExpr, true, $focus.schema) }
        end match
    end generateSumFocus

    // ==========================================================================
    // Schema.apply[A]: structural-projection Schema factory
    // ==========================================================================

    /** Builds `Schema[A] { type Focused = F }` where `F` is the structural expansion of `A`.
      *
      * For case classes and sealed traits, delegates to the same emission path as `Schema.derived`,
      * so the result carries full serialization. For other types (records, primitives), returns a
      * Schema with identity getter/setter and an Open structural shape; serialization is provided by
      * separate givens (`intSchema`, `stringSchema`, etc.).
      */
    def metaApplyImpl[A: Type](using Quotes): Expr[Any] =
        import quotes.reflect.*

        val tpe      = TypeRepr.of[A].dealias
        val sym      = tpe.typeSymbol
        val expanded = ExpandMacro.expandType(TypeRepr.of[A])

        val isCaseClass   = sym.isClassDef && sym.flags.is(Flags.Case)
        val isSealedTrait = sym.flags.is(Flags.Sealed)

        expanded.asType match
            case '[f] =>
                val fieldsExpr = Expr.summon[Fields[A]].getOrElse(
                    report.errorAndAbort(s"Cannot summon Fields[${TypeRepr.of[A].show}]")
                )
                if isCaseClass then
                    rejectPrivateCaseFields(tpe, sym)
                    val derived = emitProductSchema[A](tpe, sym, sourceFields = '{ $fieldsExpr.fields }, focusedType = TypeRepr.of[f])
                    '{ ${ derived }.asInstanceOf[Schema[A] { type Focused = f }] }
                else if isSealedTrait then
                    val derived = emitSealedSchema[A](tpe, sym, sourceFields = '{ $fieldsExpr.fields }, focusedType = TypeRepr.of[f])
                    '{ ${ derived }.asInstanceOf[Schema[A] { type Focused = f }] }
                else
                    '{
                        Schema.create[A, f](
                            ${ MacroUtils.identityGetter[A, f] },
                            ${ MacroUtils.identitySetter[A, f] },
                            Seq.empty,
                            $fieldsExpr.fields,
                            structure = kyo.Structure.Type.Open(kyo.Tag[Any])
                        )
                    }
                end if
        end match
    end metaApplyImpl

    // ==========================================================================
    // Schema.derived[A]: typeclass derivation entry point
    // ==========================================================================

    /** Derives `Schema[A]` for case classes and sealed traits.
      *
      * The emission walks `sym.caseFields` (for case classes) or `sym.children` (for sealed traits)
      * and emits per-field / per-variant thunks that resolve their Schema via
      * `scala.compiletime.summonInline[Schema[ft]]` at inline-expansion time. The macro never
      * pattern-matches on container or primitive type symbols; recursive cycles are handled by the
      * by-name `Structure.Field` thunk plus the `lazy val structure` cycle break on the generated
      * Schema's `structure` member.
      */
    def derivedImpl[A: Type](using Quotes): Expr[Schema[A]] =
        import quotes.reflect.*

        val tpe = TypeRepr.of[A].dealias
        val sym = tpe.typeSymbol

        if sym.isClassDef && sym.flags.is(Flags.Sealed) then
            emitSealedSchema[A](tpe, sym, sourceFields = '{ Seq.empty[kyo.Field[?, ?]] }, focusedType = tpe)
        else if sym.isClassDef && sym.flags.is(Flags.Case) then
            rejectPrivateCaseFields(tpe, sym)
            emitProductSchema[A](tpe, sym, sourceFields = '{ Seq.empty[kyo.Field[?, ?]] }, focusedType = tpe)
        else
            report.errorAndAbort(
                s"Cannot derive Schema for ${tpe.show}: not a case class or sealed trait. " +
                    "Provide a given Schema instance for this type if derivation is not possible."
            )
        end if
    end derivedImpl

    /** Rejects case classes with private case-fields (would otherwise leak storage names on the wire). */
    private def rejectPrivateCaseFields(using
        Quotes
    )(
        tpe: quotes.reflect.TypeRepr,
        sym: quotes.reflect.Symbol
    ): Unit =
        import quotes.reflect.*
        val privateFields = sym.caseFields.filter(f => f.flags.is(Flags.Private))
        if privateFields.nonEmpty then
            val names = privateFields.map(_.name).mkString(", ")
            report.errorAndAbort(
                s"Cannot derive Schema for ${tpe.show}: case-class field(s) $names are private. " +
                    "Private case-fields are not supported by the derivation macro because the macro " +
                    "would emit the private storage name on the wire. Provide a hand-rolled given " +
                    "Schema for this type (see Structure.Field's structureFieldSchema for the pattern)."
            )
        end if
    end rejectPrivateCaseFields

    // ==========================================================================
    // Product (case class) emission
    // ==========================================================================

    /** Emits the new `Schema[A]` for a case class.
      *
      * The emission has constant per-method bytecode: `serializeWrite` / `serializeRead` each call a
      * single runtime helper passing the precomputed field-entry table. The table itself uses
      * `summonInline[Schema[ft]]` thunks that resolve at the inline-expansion phase, with the
      * in-flight `derived$Schema` visible by forward-reference.
      */
    private def emitProductSchema[A: Type](using
        Quotes
    )(
        tpe: quotes.reflect.TypeRepr,
        sym: quotes.reflect.Symbol,
        sourceFields: Expr[Seq[kyo.Field[?, ?]]],
        focusedType: quotes.reflect.TypeRepr
    ): Expr[Schema[A]] =
        import quotes.reflect.*

        val typeName = sym.name
        val fields   = sym.caseFields
        val n        = fields.length

        if n > 64 then
            report.errorAndAbort(
                s"kyo-schema: case class ${tpe.show} has $n fields; the generated decoder uses a Long required-field bitmap and supports at most 64 fields."
            )
        end if

        val maybeSym  = TypeRepr.of[kyo.Maybe[Any]].typeSymbol
        val optionSym = TypeRepr.of[Option[Any]].typeSymbol

        given CanEqual[Symbol, Symbol] = CanEqual.derived

        // Per-field metadata used by emission.
        val isMaybeFlags = fields.map(f =>
            tpe.memberType(f).dealias match
                case AppliedType(tycon, _) => tycon.typeSymbol == maybeSym
                case _                     => false
        )
        val isOptionFlags = fields.map(f =>
            tpe.memberType(f).dealias match
                case AppliedType(tycon, _) => tycon.typeSymbol == optionSym
                case _                     => false
        )

        val hasDefaultFlags = fields.zipWithIndex.map((_, idx) => MacroUtils.hasDefault(sym, idx))

        // Build the field-name → effective-Schema-type list. For Maybe[T] fields, the effective
        // schema is `Schema[T]` (inner type); the runtime adds the Maybe nil-handling on read/write.
        val effectiveSchemaTypes: List[TypeRepr] = fields.zipWithIndex.map { (field, idx) =>
            val rawType = tpe.memberType(field)
            if isMaybeFlags(idx) then
                rawType.dealias match
                    case AppliedType(_, List(inner)) => inner
                    case _                           => rawType
            else rawType
            end if
        }

        // Single thunk producing the full Array[Schema[Any]] for all fields. This collapses N
        // per-field thunks into one synthetic method and one allocation.
        val schemasArrayBuilderExpr: Expr[() => Array[Schema[Any]]] =
            val schemaExprs: List[Expr[Schema[Any]]] = effectiveSchemaTypes.map { effective =>
                effective.asType match
                    case '[t] =>
                        '{
                            given kyo.Frame = kyo.Frame.internal
                            summonInline[Schema[t]].asInstanceOf[Schema[Any]]
                        }
            }
            '{ () => Array[Schema[Any]](${ Varargs(schemaExprs) }*) }
        end schemasArrayBuilderExpr

        // Per-field default thunks. Fields without a default share `nullDefault`.
        val defaultsArrayExpr: Expr[Array[() => Any]] =
            val elems: List[Expr[() => Any]] = fields.zipWithIndex.map { (_, idx) =>
                MacroUtils.getDefault(tpe, idx) match
                    case Some(d) => '{ () => $d }
                    case None    => '{ kyo.internal.SchemaCodecRuntime.nullDefault }
            }
            '{ Array[() => Any](${ Varargs(elems) }*) }
        end defaultsArrayExpr

        // Construct lambda: uses `Mirror.ProductOf[A]` to materialize an A from the field-value
        // array. One shared lambda body (no per-field casts inlined) — the Mirror provides the
        // typed `fromProduct` call.
        val constructExpr: Expr[Array[Any] => A] =
            tpe.asType match
                case '[a] =>
                    Expr.summon[scala.deriving.Mirror.ProductOf[a]] match
                        case Some(mirrorExpr) =>
                            '{
                                val _mirror = $mirrorExpr
                                (args: Array[Any]) =>
                                    _mirror.fromProduct(scala.Tuple.fromArray(args.asInstanceOf[Array[Object]]))
                                        .asInstanceOf[A]
                            }
                        case None =>
                            // Fallback: companion.apply. Only reached when Mirror.ProductOf[A] is
                            // not derivable — generally not for plain case classes.
                            '{ (args: Array[Any]) =>
                                ${
                                    val argTerms: List[Term] = fields.zipWithIndex.map { (field, idx) =>
                                        val ft = tpe.memberType(field)
                                        ft.asType match
                                            case '[t] => '{ args(${ Expr(idx) }).asInstanceOf[t] }.asTerm
                                    }
                                    val companion = Ref(sym.companionModule)
                                    val typeArgs = tpe match
                                        case AppliedType(_, targs) => targs
                                        case _                     => List.empty
                                    Select.overloaded(companion, "apply", typeArgs, argTerms).asExprOf[A]
                                }
                            }
            end match
        end constructExpr

        // Type-parameter structures: one thunk building the full array of typeParam Structure.Types.
        val typeParamStructuresExpr: Expr[() => Array[Structure.Type]] =
            val perParam: List[Expr[Structure.Type]] = tpe.typeArgs.map { tp =>
                tp.asType match
                    case '[t] =>
                        '{
                            given kyo.Frame = kyo.Frame.internal
                            summonInline[Schema[t]].structure
                        }
            }
            '{ () => Array[Structure.Type](${ Varargs(perParam) }*) }
        end typeParamStructuresExpr

        // Per-field default Structure.Value thunks. For fields without a default this returns
        // `Maybe.empty`. The cost: one tiny lambda per field with a default.
        val defaultStructureValuesExpr: Expr[Array[() => kyo.Maybe[kyo.Structure.Value]]] =
            val elems: List[Expr[() => kyo.Maybe[kyo.Structure.Value]]] = fields.zipWithIndex.map { (field, idx) =>
                val rawType = tpe.memberType(field)
                MacroUtils.getDefault(tpe, idx) match
                    case Some(defVal) =>
                        rawType.asType match
                            case '[t] =>
                                Expr.summon[kyo.Tag[t]] match
                                    case Some(tagExpr) =>
                                        '{
                                            () =>
                                                kyo.Maybe(
                                                    Structure.Value.primitive[t]($defVal.asInstanceOf[t])(using $tagExpr)
                                                )
                                        }
                                    case None =>
                                        '{ () => kyo.Maybe.empty[kyo.Structure.Value] }
                    case None =>
                        '{ () => kyo.Maybe.empty[kyo.Structure.Value] }
                end match
            }
            '{ Array[() => kyo.Maybe[kyo.Structure.Value]](${ Varargs(elems) }*) }
        end defaultStructureValuesExpr

        val tagExpr = summonSchemaTag(tpe)

        // Per-field flag: is the EFFECTIVE inner type itself nullable (Maybe[T] / Option[T])? Only
        // applies when the outer is Maybe; it tells the runtime not to short-circuit on isNil for
        // the outer wrapper, since the inner schema owns its own nil semantics.
        val innerIsNullableFlags: List[Boolean] = effectiveSchemaTypes.map { effective =>
            effective.dealias match
                case AppliedType(tycon, _) =>
                    val s = tycon.typeSymbol
                    s == maybeSym || s == optionSym
                case _ => false
        }

        // Pack per-field metadata into a single compile-time string literal. The runtime
        // `ProductFieldsMeta` inflates the string into the parallel arrays once at construction;
        // this keeps the macro emission at a single STRING constant per derivation.
        val encoded =
            fields.zipWithIndex.map { (f, idx) =>
                val m = if isMaybeFlags(idx) then 'm' else '.'
                val o = if isOptionFlags(idx) then 'o' else '.'
                val d = if hasDefaultFlags(idx) then 'd' else '.'
                val n = if innerIsNullableFlags(idx) then 'n' else '.'
                s"${f.name}\t$m$o$d$n"
            }.mkString(";")

        '{
            val _meta = new kyo.internal.SchemaCodecRuntime.ProductFieldsMeta(
                ${ Expr(typeName) },
                ${ Expr(encoded) },
                $defaultsArrayExpr
            )
            kyo.internal.SchemaCodecRuntime.buildProductSchema[A](
                _meta,
                $schemasArrayBuilderExpr,
                $constructExpr,
                $sourceFields,
                $tagExpr,
                $typeParamStructuresExpr,
                $defaultStructureValuesExpr
            )
        }
    end emitProductSchema

    // ==========================================================================
    // Sealed-trait emission
    // ==========================================================================

    /** Emits `Schema[A]` for a sealed trait or enum.
      *
      * Variant schemas are emitted INLINE (not via `summonInline[Schema[Variant]]`) so that variant
      * leaves without their own `derives Schema` resolve correctly. Variant FIELDS that reference
      * the parent type forward-reference through the synthesised `derived$Schema` binding (the user
      * `derives Schema` on the sealed trait provides this binding).
      */
    private def emitSealedSchema[A: Type](using
        Quotes
    )(
        tpe: quotes.reflect.TypeRepr,
        sym: quotes.reflect.Symbol,
        sourceFields: Expr[Seq[kyo.Field[?, ?]]],
        focusedType: quotes.reflect.TypeRepr
    ): Expr[Schema[A]] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived

        val typeName = sym.name
        val children = sym.children
        if children.isEmpty then
            report.errorAndAbort(s"Cannot derive Schema for sealed trait ${sym.name}: no case class or object variants found.")

        val childNames = children.map(_.name.stripSuffix("$"))
        val encoded    = childNames.mkString(";")

        // Single `matchVariant: A => Int` lambda — does the runtime dispatch via a chain of
        // isInstanceOf / `eq singleton` checks. One synthetic method per derived sum (vs N).
        val matchVariantExpr: Expr[A => Int] = '{ (a: A) =>
            ${
                val terms: List[(Term, Term)] = children.zipWithIndex.map { (child, idx) =>
                    val checkTerm: Term =
                        if !child.isType then
                            val singletonRef: Expr[AnyRef] =
                                if child.flags.is(Flags.Module) && child.companionModule != Symbol.noSymbol then
                                    Ref(child.companionModule).asExprOf[AnyRef]
                                else
                                    val parentSym = child.owner
                                    if parentSym.companionModule != Symbol.noSymbol then
                                        Select.unique(Ref(parentSym.companionModule), child.name).asExprOf[AnyRef]
                                    else
                                        Ref(child).asExprOf[AnyRef]
                                    end if
                            '{ a.asInstanceOf[AnyRef] eq $singletonRef }.asTerm
                        else
                            child.typeRef.asType match
                                case '[t] => '{ a.isInstanceOf[t] }.asTerm
                    (checkTerm, Literal(IntConstant(idx)))
                }
                terms.foldRight('{ -1 }.asTerm) { case ((cond, idxLit), elseTerm) =>
                    If(cond, idxLit, elseTerm)
                }.asExprOf[Int]
            }
        }

        // Single thunk producing the full Array[Schema[Any]] for all variants.
        val schemasArrayBuilderExpr: Expr[() => Array[Schema[Any]]] =
            val schemaExprs: List[Expr[Schema[Any]]] = children.map { child =>
                val childType =
                    if child.isType then child.typeRef
                    else if child.flags.is(Flags.Module) then child.termRef.widen
                    else child.typeRef
                childType.asType match
                    case '[t] =>
                        val vSchema = emitVariantSchema[t](childType, child)
                        '{ $vSchema.asInstanceOf[Schema[Any]] }
                end match
            }
            '{ () => Array[Schema[Any]](${ Varargs(schemaExprs) }*) }
        end schemasArrayBuilderExpr

        val enumValues: List[String] = children.collect {
            case child if child.flags.is(Flags.Module) || !child.isClassDef =>
                child.name.stripSuffix("$")
        }
        val enumValuesEncoded = enumValues.mkString(";")

        val nameExpr = Expr(typeName)
        val tagExpr  = summonSchemaTag(tpe)

        '{
            val _meta = new kyo.internal.SchemaCodecRuntime.SumVariantsMeta(
                $nameExpr,
                ${ Expr(encoded) }
            )
            kyo.internal.SchemaCodecRuntime.buildSumSchema[A](
                _meta,
                $matchVariantExpr,
                $schemasArrayBuilderExpr,
                $sourceFields,
                $tagExpr,
                ${ Expr(enumValuesEncoded) }
            )
        }
    end emitSealedSchema

    /** Emits an inline `Schema[T]` for a sealed-trait variant child.
      *
      * Case-class variants delegate to [[emitProductSchema]] to avoid macro re-entry through
      * `summonInline[Schema[ChildType]]` (which would fail when the child does not carry its own
      * `derives Schema`). Case-object / no-arg-enum variants emit a trivial empty-object Schema.
      */
    private def emitVariantSchema[T: Type](using
        Quotes
    )(
        childType: quotes.reflect.TypeRepr,
        child: quotes.reflect.Symbol
    ): Expr[Schema[T]] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived

        val childSym  = childType.typeSymbol
        val childName = child.name.stripSuffix("$")

        // True case objects (modules) and no-arg enum cases emit a trivial empty-object Schema with
        // a singleton ref. Everything else — including a 0-arg case class like `case class Foo()` —
        // delegates to `emitProductSchema` so the constructor is called via the companion `apply`.
        val isSingletonCase = !child.isType || child.flags.is(Flags.Module)

        if isSingletonCase && childSym.caseFields.isEmpty then
            val singletonRef: Expr[T] =
                if child.flags.is(Flags.Module) && child.companionModule != Symbol.noSymbol then
                    Ref(child.companionModule).asExprOf[T]
                else
                    val parentSym = child.owner
                    if parentSym.companionModule != Symbol.noSymbol then
                        Select.unique(Ref(parentSym.companionModule), child.name).asExprOf[T]
                    else
                        Ref(child).asExprOf[T]
                    end if
            '{
                kyo.Schema.init[T](
                    writeFn = (_, w) =>
                        w.objectStart(${ Expr(childName) }, 0)
                        w.objectEnd()
                    ,
                    readFn = r =>
                        kyo.discard(r.objectStart())
                        r.objectEnd()
                        $singletonRef
                    ,
                    structure = kyo.Structure.Type.Product(
                        ${ Expr(childName) },
                        kyo.Tag[Any],
                        kyo.Chunk.empty,
                        kyo.Chunk.empty
                    )
                )
            }
        else
            emitProductSchema[T](
                childType,
                childSym,
                sourceFields = '{ Seq.empty[kyo.Field[?, ?]] },
                focusedType = childType
            )
        end if
    end emitVariantSchema

    /** Returns `Tag[A].asInstanceOf[Tag[Any]]` if a Tag is in scope, otherwise `Tag[Any]`. */
    private def summonSchemaTag(using Quotes)(tpe: quotes.reflect.TypeRepr): Expr[Tag[Any]] =
        import quotes.reflect.*
        tpe.asType match
            case '[t] =>
                Expr.summon[Tag[t]] match
                    case Some(tagExpr) => '{ $tagExpr.asInstanceOf[Tag[Any]] }
                    case None          => '{ Tag[Any] }
        end match
    end summonSchemaTag

    // ==========================================================================
    // Focus.focus / focusChain / foreachChain
    // ==========================================================================

    def focusImpl[A: Type, F: Type, V: Type](
        schema: Expr[Schema[A]],
        f: Expr[Focus.Select[A, F] => Focus.Select[A, V]]
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*

        val fieldNames = extractAllFocusFieldNames(f.asTerm)
        val isPartial  = checkSumNavigation[F](fieldNames)

        if isPartial then
            '{
                val root      = Focus.Select.create[A, Any]($schema.getter, $schema.setter, $schema.segments, false, Maybe($schema))
                val navigated = $f(root.asInstanceOf[Focus.Select[A, F]])
                Focus.createMaybe[A, V](navigated.getter, navigated.setter, navigated.segments, $schema)
            }
        else
            '{
                val root      = Focus.Select.create[A, Any]($schema.getter, $schema.setter, $schema.segments, false, Maybe($schema))
                val navigated = $f(root.asInstanceOf[Focus.Select[A, F]])
                Focus.createId[A, V](
                    root => navigated.getter(root).get,
                    navigated.setter,
                    navigated.segments,
                    $schema
                )
            }
        end if
    end focusImpl

    def focusChainImpl[A: Type, V: Type, M[_]: Type, V2: Type](
        focus: Expr[Focus[A, V, M]],
        f: Expr[Focus.Select[V, V] => Focus.Select[V, V2]]
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*

        val fieldNames = extractAllFocusFieldNames(f.asTerm)
        val expandedV  = ExpandMacro.expandType(TypeRepr.of[V])
        val innerIsSum = checkSumNavigationOnType(expandedV, fieldNames)

        val isChunk = TypeRepr.of[M[Any]] <:< TypeRepr.of[Chunk[Any]]
        val isMaybe = TypeRepr.of[M[Any]] <:< TypeRepr.of[Maybe[Any]]

        if isChunk then
            '{
                val outer     = $focus.asInstanceOf[Focus[A, V, Chunk]]
                val innerRoot = Focus.Select[V]
                val navigated = $f(innerRoot)
                Focus.composeChunkAny[A, V, V2](
                    outer,
                    navigated.getter.asInstanceOf[V => Maybe[V2]],
                    navigated.setter.asInstanceOf[(V, V2) => V],
                    navigated.segments
                )
            }
        else if isMaybe then
            '{
                val outer     = $focus.asInstanceOf[Focus[A, V, Maybe]]
                val innerRoot = Focus.Select[V]
                val navigated = $f(innerRoot)
                Focus.composeMaybeAny[A, V, V2](
                    outer,
                    navigated.getter.asInstanceOf[V => Maybe[V2]],
                    navigated.setter.asInstanceOf[(V, V2) => V],
                    navigated.segments
                )
            }
        else if innerIsSum then
            '{
                val outer     = $focus.asInstanceOf[Focus[A, V, Focus.Id]]
                val innerRoot = Focus.Select[V]
                val navigated = $f(innerRoot)
                Focus.composeIdMaybe[A, V, V2](
                    outer,
                    navigated.getter.asInstanceOf[V => Maybe[V2]],
                    navigated.setter.asInstanceOf[(V, V2) => V],
                    navigated.segments
                )
            }
        else
            '{
                val outer     = $focus.asInstanceOf[Focus[A, V, Focus.Id]]
                val innerRoot = Focus.Select[V]
                val navigated = $f(innerRoot)
                Focus.composeIdId[A, V, V2](
                    outer,
                    navigated.getter.asInstanceOf[V => Maybe[V2]],
                    navigated.setter.asInstanceOf[(V, V2) => V],
                    navigated.segments
                )
            }
        end if
    end focusChainImpl

    def foreachChainImpl[A: Type, V: Type, M[_]: Type, C <: Seq[?]: Type, E: Type](
        focus: Expr[Focus[A, V, M]],
        f: Expr[Focus.Select[V, V] => Focus.Select[V, C]]
    )(using Quotes): Expr[Focus[A, E, Chunk]] =
        import quotes.reflect.*

        val isChunk = TypeRepr.of[M[Any]] <:< TypeRepr.of[Chunk[Any]]
        val isMaybe = TypeRepr.of[M[Any]] <:< TypeRepr.of[Maybe[Any]]

        if isChunk then
            '{
                val outer     = $focus.asInstanceOf[Focus[A, V, Chunk]]
                val innerRoot = Focus.Select[V]
                val navigated = $f(innerRoot)
                Focus.composeChunkForeach[A, V, C, E](
                    outer,
                    navigated.getter.asInstanceOf[V => Maybe[C]],
                    navigated.setter.asInstanceOf[(V, C) => V],
                    navigated.segments
                )
            }
        else if isMaybe then
            '{
                val outer     = $focus.asInstanceOf[Focus[A, V, Maybe]]
                val innerRoot = Focus.Select[V]
                val navigated = $f(innerRoot)
                Focus.composeMaybeForeach[A, V, C, E](
                    outer,
                    navigated.getter.asInstanceOf[V => Maybe[C]],
                    navigated.setter.asInstanceOf[(V, C) => V],
                    navigated.segments
                )
            }
        else
            '{
                val outer     = $focus.asInstanceOf[Focus[A, V, Focus.Id]]
                val innerRoot = Focus.Select[V]
                val navigated = $f(innerRoot)
                Focus.composeIdForeach[A, V, C, E](
                    outer,
                    navigated.getter.asInstanceOf[V => Maybe[C]],
                    navigated.setter.asInstanceOf[(V, C) => V],
                    navigated.segments
                )
            }
        end if
    end foreachChainImpl

    private def extractAllFocusFieldNames(using Quotes)(tree: quotes.reflect.Term): List[String] =
        import quotes.reflect.*

        def extract(term: Tree): List[String] =
            term match
                case Apply(TypeApply(Select(receiver, "selectDynamic"), _), List(Literal(StringConstant(name)))) =>
                    extract(receiver) :+ name
                case Apply(Select(receiver, "selectDynamic"), List(Literal(StringConstant(name)))) =>
                    extract(receiver) :+ name
                case Inlined(Some(call), _, body) =>
                    val fromCall = extract(call)
                    if fromCall.nonEmpty then fromCall
                    else extract(body)
                case Inlined(None, _, body) =>
                    extract(body)
                case Lambda(_, body) =>
                    extract(body)
                case Block(_, expr) =>
                    extract(expr)
                case Typed(inner, _) =>
                    extract(inner)
                case _ => Nil
            end match
        end extract

        extract(tree)
    end extractAllFocusFieldNames

    private def checkSumNavigation[F: Type](using Quotes)(fieldNames: List[String]): Boolean =
        import quotes.reflect.*
        var currentType = ExpandMacro.expandType(TypeRepr.of[F])
        var foundSum    = false
        for name <- fieldNames do
            NavigationMacro.classifyField(currentType, name) match
                case Some((valueType, isSum)) =>
                    if isSum then foundSum = true
                    currentType = ExpandMacro.expandType(valueType)
                case None =>
                    ()
        end for
        foundSum
    end checkSumNavigation

    private def checkSumNavigationOnType(using Quotes)(startType: quotes.reflect.TypeRepr, fieldNames: List[String]): Boolean =
        import quotes.reflect.*
        var currentType = startType
        var foundSum    = false
        for name <- fieldNames do
            NavigationMacro.classifyField(currentType, name) match
                case Some((valueType, isSum)) =>
                    if isSum then foundSum = true
                    currentType = ExpandMacro.expandType(valueType)
                case None =>
                    ()
        end for
        foundSum
    end checkSumNavigationOnType

    // ==========================================================================
    // Schema[A].defaults
    // ==========================================================================

    def defaultsImpl[A: Type, F: Type](using Quotes): Expr[Any] =
        import quotes.reflect.*

        val nominalType = MacroUtils.deriveNominalType[A, F]
        val sym         = nominalType.typeSymbol

        if !sym.isClassDef || !sym.flags.is(Flags.Case) then
            '{ new Record[Any](Dict.empty[String, Any]) }
        else
            val tildeType = TypeRepr.of[Record.~]

            val fieldsWithDefaults = sym.caseFields.zipWithIndex.flatMap: (field, idx) =>
                MacroUtils.getDefault(nominalType, idx).map: defaultExpr =>
                    val fieldName  = field.name
                    val fieldType  = nominalType.memberType(field)
                    val nameType   = ConstantType(StringConstant(fieldName))
                    val tildedType = tildeType.appliedTo(List(nameType, fieldType))
                    (fieldName, tildedType, defaultExpr)

            if fieldsWithDefaults.isEmpty then
                '{ new Record[Any](Dict.empty[String, Any]) }
            else
                val recordType = fieldsWithDefaults.map(_._2).reduce(AndType(_, _))
                val fieldCount = fieldsWithDefaults.size

                recordType.asType match
                    case '[r] =>
                        val nameExprs    = fieldsWithDefaults.map(f => Expr(f._1))
                        val defaultExprs = fieldsWithDefaults.map(_._3)
                        val allExprs     = nameExprs ++ defaultExprs

                        '{
                            val arr = new Array[Any](${ Expr(fieldCount * 2) })
                            ${
                                Expr.block(
                                    allExprs.zipWithIndex.map: (expr, i) =>
                                        '{ arr(${ Expr(i) }) = $expr }.asTerm
                                    .toList.map(_.asExprOf[Unit]),
                                    '{ () }
                                )
                            }
                            new Record[r](Dict.fromArrayUnsafe(arr.asInstanceOf[Array[String | Any]]))
                        }
                end match
            end if
        end if
    end defaultsImpl

end FocusMacro
