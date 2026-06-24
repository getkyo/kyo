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
  * `scala.compiletime.summonInline[Schema[ft]]` and emits a fully-static per-field codec body (no
  * runtime walk). The macro never pattern-matches on a specific container or primitive
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
                // Cast: Schema and computed-field lambda are stored as Any at the Focus boundary; type is recovered here.
                // Focus.schema is always present for computed focuses: the macro sets it unconditionally in generateComputedFocus.
                val schema = $focus.schema.getOrElse(throw kyo.TransformFailedException(
                    s"Focus.schema is absent: computed-field focus requires a Schema instance"
                )(using summonInline[kyo.Frame])).asInstanceOf[Schema[A]]
                val computeFn = schema.computedFields.toSeq.find(_._1 == $fieldNameExpr)
                    .getOrElse(throw kyo.TransformFailedException(
                        s"focus computed field '${$fieldNameExpr}' not present in Schema[A].computedFields: macro generation invariant violated"
                    )(using summonInline[kyo.Frame]))
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
                    val derived = emitProductSchemaStatic[A](tpe, sym, sourceFields = '{ $fieldsExpr.fields }, focusedType = TypeRepr.of[f])
                    '{ ${ derived }.asInstanceOf[Schema[A] { type Focused = f }] }
                else if isSealedTrait then
                    val derived = emitSealedSchemaStatic[A](tpe, sym, sourceFields = '{ $fieldsExpr.fields }, focusedType = TypeRepr.of[f])
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
            emitSealedSchemaStatic[A](tpe, sym, sourceFields = '{ Seq.empty[kyo.Field[?, ?]] }, focusedType = tpe)
        else if sym.isClassDef && sym.flags.is(Flags.Case) then
            rejectPrivateCaseFields(tpe, sym)
            emitProductSchemaStatic[A](tpe, sym, sourceFields = '{ Seq.empty[kyo.Field[?, ?]] }, focusedType = tpe)
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

    /** Emits the `Schema[A]` for a case class: a fully-static `Schema.init[A]` whose `writeFn` /
      * `readFn` are per-field bodies that read `value.fieldN` directly and dispatch each field through
      * the inline `writeField` / `readField` bridge (primitive fields stay unboxed, everything else
      * routes through the field schema's own codec). No runtime field walk and no boxed per-field schema array.
      *
      * Each per-field schema resolves via `summonInline[Schema[ft]]` at the generated-code typer phase;
      * the in-flight `derived$Schema` is visible by forward-reference, so recursive products close the
      * cycle. The read body seeds defaults / `Maybe.empty` / `None`, tracks a `Long` required-field
      * bitmap, ORs in `droppedFieldsMask` before the required check, and throws `MissingFieldException`
      * (carrying `reader.frame`) for a missing required field. Used by both `derivedImpl`
      * (`derives Schema`) and the `metaApplyImpl` Focus path.
      */
    private def emitProductSchemaStatic[A: Type](using
        Quotes
    )(
        tpe: quotes.reflect.TypeRepr,
        sym: quotes.reflect.Symbol,
        sourceFields: Expr[Seq[kyo.Field[?, ?]]],
        focusedType: quotes.reflect.TypeRepr,
        parentSelf: Option[(quotes.reflect.TypeRepr, quotes.reflect.Term)] = None
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

        // Field-name -> effective-Schema-type list. For Maybe[T] the effective schema is Schema[T]
        // (inner type); the runtime adds the Maybe nil-handling on read/write.
        val effectiveSchemaTypes: List[TypeRepr] = fields.zipWithIndex.map { (field, idx) =>
            val rawType = tpe.memberType(field)
            if isMaybeFlags(idx) then
                rawType.dealias match
                    case AppliedType(_, List(inner)) => inner
                    case _                           => rawType
            else rawType
            end if
        }

        // Hoist each distinct field-schema resolution to one lazy val shared by write, read, and
        // structure. summonInline inline-expands a no-derives nested codec (and at runtime
        // re-constructs it); hoisting fires it once per distinct field type and memoizes it per
        // instance, instead of 3x per field plus a per-call reconstruction at every field site.
        val hoistOwner     = Symbol.spliceOwner
        val hoistedSchemas = scala.collection.mutable.ListBuffer.empty[(TypeRepr, Symbol)]
        def hoistedSchemaSym(t: TypeRepr): Symbol =
            hoistedSchemas.find((tt, _) => tt =:= t).map(_._2).getOrElse {
                val schemaTpe = t.asType match
                    case '[tt] => TypeRepr.of[Schema[tt]]
                val s = Symbol.newVal(hoistOwner, s"_fieldSchema${hoistedSchemas.size}", schemaTpe, Flags.Lazy, Symbol.noSymbol)
                hoistedSchemas += ((t, s))
                s
            }

        val tagExpr = summonSchemaTag(tpe)

        // Per-field flag: is the effective inner type itself nullable (Maybe[T] / Option[T])?
        val innerIsNullableFlags: List[Boolean] = effectiveSchemaTypes.map { effective =>
            effective.dealias match
                case AppliedType(tycon, _) =>
                    val s = tycon.typeSymbol
                    s == maybeSym || s == optionSym
                case _ => false
        }

        // Per-field default Structure.Value thunks, consumed by structureExpr.
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

        // Per-field effective schema term for a given compile-time type T. The tied-knot parentSelf
        // closes the cycle for a variant field whose effective type equals the sealed parent.
        def fieldSchemaExprTyped[T: Type](idx: Int): Expr[Schema[T]] =
            val eff = effectiveSchemaTypes(idx)
            parentSelf match
                case Some((parentTpe, selfRef)) if eff =:= parentTpe =>
                    selfRef.asExprOf[Schema[T]]
                case _ =>
                    // Reference the hoisted lazy val for this field type: summoned once, shared by
                    // write/read/structure, and constructed once per instance rather than rebuilt
                    // at every per-field site.
                    Ref(hoistedSchemaSym(eff)).asExprOf[Schema[T]]
            end match
        end fieldSchemaExprTyped

        // WRITE: static per-field emission, no runtime field walk.
        def writeBody(v: Expr[A], w: Expr[Writer]): Expr[Unit] =
            val owner = Symbol.spliceOwner
            // Pre-encode each field's UTF-8 name bytes once per serialization, mirroring the read side's
            // nameByteSyms and the sealed-variant write path. Schema field keys go through `fieldBytes`
            // (the canonical schema-field key method: the JSON writer's ASCII fast path, the Protobuf
            // field tag, and the MsgPack key-encoding switch). Dynamic `Map` keys and the hand-written
            // sum discriminator keys stay on `field`, which carries the raw String for escaping.
            val nameByteSyms: List[Symbol] = fields.zipWithIndex.map { (f, idx) =>
                Symbol.newVal(owner, s"_wnb${idx}", TypeRepr.of[Array[Byte]], Flags.EmptyFlags, Symbol.noSymbol)
            }
            val nameByteDefs: List[Statement] = fields.zipWithIndex.map { (f, idx) =>
                val nameExpr = Expr(f.name)
                ValDef(nameByteSyms(idx), Some('{ $nameExpr.getBytes(java.nio.charset.StandardCharsets.UTF_8) }.asTerm))
            }
            val header: Term = '{ $w.objectStart(${ Expr(typeName) }, ${ Expr(n) }) }.asTerm
            val perField: List[Term] = fields.zipWithIndex.map { (f, idx) =>
                // Field header tag MUST be the hash-based field ID (CodecMacro.fieldId), NOT the
                // positional index. Computed at macro time as a literal; identical to the runtime's
                // meta.fieldIds(i).
                val idxExpr = Expr(CodecMacro.fieldId(f.name))
                val nbRef   = Ref(nameByteSyms(idx)).asExprOf[Array[Byte]]
                if isMaybeFlags(idx) then
                    effectiveSchemaTypes(idx).asType match
                        case '[t] =>
                            val s   = fieldSchemaExprTyped[t](idx)
                            val acc = Select(v.asTerm, f).asExprOf[kyo.Maybe[t]]
                            '{
                                $acc match
                                    case kyo.Present(inner) =>
                                        $w.fieldBytes($nbRef, $idxExpr)
                                        kyo.internal.writeField($s, inner, $w)
                                    case _ => ()
                                end match
                            }.asTerm
                else if isOptionFlags(idx) then
                    effectiveSchemaTypes(idx).asType match
                        case '[t] =>
                            val s   = fieldSchemaExprTyped[t](idx)
                            val acc = Select(v.asTerm, f).asExprOf[t]
                            '{
                                val _opt = $acc
                                if _opt.asInstanceOf[Option[?]].isDefined then
                                    $w.fieldBytes($nbRef, $idxExpr)
                                    kyo.internal.writeField($s, _opt, $w)
                            }.asTerm
                else
                    effectiveSchemaTypes(idx).asType match
                        case '[t] =>
                            val s   = fieldSchemaExprTyped[t](idx)
                            val acc = Select(v.asTerm, f).asExprOf[t]
                            '{
                                $w.fieldBytes($nbRef, $idxExpr)
                                kyo.internal.writeField($s, $acc, $w)
                            }.asTerm
                end if
            }
            val trailer: Term = '{ $w.objectEnd() }.asTerm
            Block(nameByteDefs ++ (header :: perField), trailer).asExprOf[Unit]
        end writeBody

        // READ: static per-field name-matched loop.
        def readBody(r: Expr[Reader]): Expr[A] =
            val requiredMask: Long = fields.zipWithIndex.foldLeft(0L) { case (m, (_, idx)) =>
                if !hasDefaultFlags(idx) && !isMaybeFlags(idx) && !isOptionFlags(idx) then m | (1L << idx)
                else m
            }
            val fieldNames: List[String] = fields.map(_.name)

            val seedExprs: List[Expr[Any]] = fields.zipWithIndex.map { (f, idx) =>
                val ft = tpe.memberType(f)
                if hasDefaultFlags(idx) then
                    MacroUtils.getDefault(tpe, idx).get
                else if isMaybeFlags(idx) then
                    ft.dealias match
                        case AppliedType(_, List(inner)) =>
                            inner.asType match
                                case '[innerT] => '{ kyo.Maybe.empty[innerT] }
                        case _ => '{ kyo.Maybe.empty }
                else if isOptionFlags(idx) then
                    ft.asType match
                        case '[t] => '{ Option.empty[Any].asInstanceOf[t] }
                else
                    ft.asType match
                        case '[t] => '{ null.asInstanceOf[t] }
                end if
            }

            val perFieldReadExprs: List[Expr[Any]] = fields.zipWithIndex.map { (f, idx) =>
                val innerIsNullable = innerIsNullableFlags(idx)
                if isMaybeFlags(idx) then
                    effectiveSchemaTypes(idx).asType match
                        case '[t] =>
                            val s = fieldSchemaExprTyped[t](idx)
                            if innerIsNullable then
                                '{ kyo.Present(kyo.internal.readField($s, $r)) }
                            else
                                '{
                                    if $r.isNil() then kyo.Maybe.empty[t]
                                    else kyo.Present(kyo.internal.readField($s, $r))
                                }
                            end if
                else
                    effectiveSchemaTypes(idx).asType match
                        case '[t] =>
                            val s = fieldSchemaExprTyped[t](idx)
                            '{ kyo.internal.readField($s, $r) }
                end if
            }

            val owner = Symbol.spliceOwner

            val seenSym = Symbol.newVal(owner, "_seen", TypeRepr.of[Long], Flags.Mutable, Symbol.noSymbol)
            val seenDef = ValDef(seenSym, Some(Literal(LongConstant(0L))))

            val localSyms: List[Symbol] = fields.zipWithIndex.map { (f, idx) =>
                val ft = tpe.memberType(f)
                Symbol.newVal(owner, s"_f${idx}", ft, Flags.Mutable, Symbol.noSymbol)
            }
            val localDefs: List[Statement] = fields.zipWithIndex.map { (f, idx) =>
                ValDef(localSyms(idx), Some(seedExprs(idx).asTerm))
            }

            val seenRef = Ref(seenSym).asExprOf[Long]

            // Dispatch via reader.matchField(nameBytes), NOT lastFieldName(). matchField is the
            // canonical name-bytes comparison that works on every wire format; lastFieldName() is a
            // human-readable surrogate only (a numeric field-ID under Protobuf, never the field name).
            // Pre-encode each field's UTF-8 name bytes into a stable local; a `while j < n && matchedIdx < 0`
            // chain then selects the matching field.
            val nameByteSyms: List[Symbol] = fields.zipWithIndex.map { (f, idx) =>
                Symbol.newVal(owner, s"_nb${idx}", TypeRepr.of[Array[Byte]], Flags.EmptyFlags, Symbol.noSymbol)
            }
            val nameByteDefs: List[Statement] = fields.zipWithIndex.map { (f, idx) =>
                val nameExpr = Expr(f.name)
                ValDef(nameByteSyms(idx), Some('{ $nameExpr.getBytes(java.nio.charset.StandardCharsets.UTF_8) }.asTerm))
            }

            // Per-field arm: assign the typed read result + OR-in the seen bit.
            def fieldArm(idx: Int): Term =
                val readVal = perFieldReadExprs(idx).asTerm
                val assign  = Assign(Ref(localSyms(idx)), readVal)
                val orIn    = Assign(Ref(seenSym), '{ $seenRef | ${ Expr(1L << idx) } }.asTerm)
                Block(List(assign), orIn)
            end fieldArm

            // Build the if/else-if chain: if r.matchField(_nb0) then arm0 else if ... else r.skip().
            val dispatchChain: Term =
                fields.indices.foldRight('{ $r.skip() }.asTerm) { (idx, elseTerm) =>
                    val cond = '{ $r.matchField(${ Ref(nameByteSyms(idx)).asExprOf[Array[Byte]] }) }.asTerm
                    If(cond, fieldArm(idx), elseTerm)
                }

            val whileBody = Block(
                List('{ $r.fieldParse() }.asTerm),
                dispatchChain
            )
            val whileLoop = While('{ $r.hasNextField() }.asTerm, whileBody)

            val requiredCheckOpt: Option[Term] =
                if requiredMask != 0L then
                    val namesExpr = Expr(fieldNames.toArray)
                    val maskExpr  = Expr(requiredMask)
                    val nExpr     = Expr(n)
                    Some('{
                        val _combined = $seenRef | $r.droppedFieldsMask($nExpr)
                        if (_combined & $maskExpr) != $maskExpr then
                            val _missing = java.lang.Long.numberOfTrailingZeros((~_combined) & $maskExpr).toInt
                            val _names   = $namesExpr
                            throw kyo.MissingFieldException(Seq.empty, _names(_missing))(using $r.frame)
                        end if
                    }.asTerm)
                else None

            tpe.asType match
                case '[a] =>
                    val ctorArgs: List[Term] = localSyms.map(Ref(_))
                    val ctor: Term           = Select(New(TypeTree.of[a]), sym.primaryConstructor)
                    val typeArgsList = tpe match
                        case AppliedType(_, targs) => targs
                        case _                     => List.empty
                    val construct: Term =
                        if typeArgsList.isEmpty then Apply(ctor, ctorArgs)
                        else TypeApply(ctor, typeArgsList.map(t => Inferred(t))).appliedToArgs(ctorArgs)

                    val resultSym = Symbol.newVal(owner, "_result", tpe, Flags.EmptyFlags, Symbol.noSymbol)
                    val resultDef = ValDef(resultSym, Some(construct))

                    val nExpr = Expr(n)
                    val allStmts: List[Statement] =
                        List(
                            '{ kyo.discard($r.objectStart()) }.asTerm,
                            '{ kyo.discard($r.initFields($nExpr)) }.asTerm,
                            seenDef
                        ) ++ localDefs ++ nameByteDefs ++
                            List(whileLoop) ++
                            requiredCheckOpt.toList ++
                            List(
                                '{ $r.objectEnd() }.asTerm,
                                resultDef,
                                '{ $r.clearFields($nExpr) }.asTerm
                            )
                    Block(allStmts, Ref(resultSym)).asExprOf[A]
            end match
        end readBody

        // STRUCTURE: inline Chunk of Structure.Field via by-name fieldType thunks.
        def structureExpr: Expr[Structure.Type] =
            // Read each field's @doc off the PRIMARY-CONSTRUCTOR PARAMETER symbol (the case-field getter
            // carries no annotation). Built once before the fields loop so the per-field emission stays
            // branch-free: one buildProductSchema per derivation, no per-field annotation read.
            val docSym = TypeRepr.of[kyo.doc].typeSymbol
            val ctorDocs: Map[String, String] =
                sym.primaryConstructor.paramSymss.flatten.flatMap { p =>
                    p.getAnnotation(docSym).collect {
                        case Apply(_, List(Literal(StringConstant(s)))) => p.name -> s
                    }
                }.toMap
            val fieldStructures: List[Expr[Structure.Field]] = fields.zipWithIndex.map { (f, idx) =>
                val rawType  = tpe.memberType(f)
                val isOpt    = isMaybeFlags(idx) || isOptionFlags(idx)
                val nameExpr = Expr(f.name)
                val optExpr  = Expr(isOpt)
                val defVal   = defaultStructureValuesExpr
                val idxExpr  = Expr(idx)
                val docExpr = ctorDocs.get(f.name) match
                    case Some(s) => '{ kyo.Maybe(${ Expr(s) }) }
                    case None    => '{ kyo.Maybe.empty[String] }
                rawType.asType match
                    case '[ft] =>
                        val fieldSchemaRef = Ref(hoistedSchemaSym(rawType)).asExprOf[Schema[ft]]
                        '{
                            kyo.Structure.Field(
                                $nameExpr,
                                $fieldSchemaRef.structure,
                                $docExpr,
                                $defVal($idxExpr)(),
                                $optExpr
                            )
                        }
                end match
            }
            val tpStructures: Expr[kyo.Chunk[Structure.Type]] =
                if tpe.typeArgs.isEmpty then '{ kyo.Chunk.empty[Structure.Type] }
                else
                    val perParam: List[Expr[Structure.Type]] = tpe.typeArgs.map { tp =>
                        tp.asType match
                            case '[t] =>
                                '{ ${ Ref(hoistedSchemaSym(tp)).asExprOf[Schema[t]] }.structure }
                    }
                    '{ kyo.Chunk.from[Structure.Type](Array[Structure.Type](${ Varargs(perParam) }*)) }
            val nameExprS = Expr(typeName)
            '{
                Structure.Type.Product(
                    $nameExprS,
                    $tagExpr,
                    $tpStructures,
                    kyo.Chunk.from[Structure.Field](Array[Structure.Field](${ Varargs(fieldStructures) }*))
                )
            }
        end structureExpr

        // Build the Schema.init term first so every fieldSchemaExprTyped / structure call has
        // populated `hoistedSchemas`, then prepend the hoisted lazy vals as a wrapping block.
        val schemaInitTerm: Term =
            '{
                Schema.init[A](
                    writeFn = (v, w) => ${ writeBody('v, 'w) },
                    readFn = r => ${ readBody('r) },
                    sourceFields = $sourceFields,
                    structure = ${ structureExpr }
                )
            }.asTerm
        val hoistedValDefs: List[Statement] = hoistedSchemas.toList.map { (t, sym) =>
            t.asType match
                case '[tt] => ValDef(sym, Some('{ summonInline[Schema[tt]] }.asTerm))
        }
        if hoistedValDefs.isEmpty then schemaInitTerm.asExprOf[Schema[A]]
        else Block(hoistedValDefs, schemaInitTerm).asExprOf[Schema[A]]
    end emitProductSchemaStatic

    // ==========================================================================
    // Sealed-trait emission
    // ==========================================================================

    /** Emits a `Schema[T]` for a sealed-trait variant child: a case-class variant delegates to the fully-static
      * [[emitProductSchemaStatic]] (per-field direct dispatch, no runtime walk); a case-object /
      * no-arg variant emits the same trivial empty-object Schema.
      */
    private def emitVariantSchemaStatic[T: Type](using
        Quotes
    )(
        childType: quotes.reflect.TypeRepr,
        child: quotes.reflect.Symbol,
        parentSelf: Option[(quotes.reflect.TypeRepr, quotes.reflect.Term)] = None
    ): Expr[Schema[T]] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived

        val childSym        = childType.typeSymbol
        val childName       = child.name.stripSuffix("$")
        val isSingletonCase = !child.isType || child.flags.is(Flags.Module)

        // Sealed-trait child (an intermediate sealed trait in a multi-level hierarchy): derive its
        // own Schema[T], which routes back through the static sealed emitter, so the parent sum
        // delegates to it instead of mis-treating it as a zero-field product.
        if childSym.flags.is(Flags.Sealed) && childSym.flags.is(Flags.Trait) && !child.flags.is(Flags.Module) then
            '{ kyo.Schema.derived[T] }
        else if isSingletonCase && childSym.caseFields.isEmpty then
            // Case object variant: serialize as empty object
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
            emitProductSchemaStatic[T](
                childType,
                childSym,
                sourceFields = '{ Seq.empty[kyo.Field[?, ?]] },
                focusedType = childType,
                parentSelf = parentSelf
            )
        end if
    end emitVariantSchemaStatic

    /** Emits a fully-static `Schema[A]` for a sealed trait or enum: no runtime walk,
      * no boxed per-field schema array, no megamorphic dispatch.
      *
      * `serializeWrite` is an `if`/`else` chain on the value's variant (an `eq` singleton check for
      * case objects, `isInstanceOf` for case classes); each arm writes the discriminator header and
      * dispatches to that variant's OWN schema, so every dispatch site has one receiver type and stays
      * monomorphic. `serializeRead` matches the discriminator name against each variant's pre-encoded
      * name bytes and dispatches to the matched variant's schema. The whole thing is a block of `lazy
      * val`s: the parent `_self` Schema plus one `_v_i` per variant. The variant schemas are emitted
      * via [[emitVariantSchemaStatic]] with `parentSelf = _self`, so a recursive variant field whose
      * type equals the parent closes over the in-flight `_self` (forced at serialize time, fully
      * constructed by then) instead of re-deriving. The wire shape and structure are byte-identical to
      * the prior runtime sum-dispatch path.
      */
    private def emitSealedSchemaStatic[A: Type](using
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
        val n = children.length

        val childNames: List[String] = children.map(_.name.stripSuffix("$"))
        val childTypes: List[TypeRepr] = children.map { child =>
            if child.isType then child.typeRef
            else if child.flags.is(Flags.Module) then child.termRef.widen
            else child.typeRef
        }

        val tagExpr = summonSchemaTag(tpe)
        val enumValues: List[String] = children.zip(childNames).collect {
            case (child, nm) if child.flags.is(Flags.Module) || !child.isClassDef => nm
        }

        val owner = Symbol.spliceOwner

        // _self: Schema[A] plus one _v_i: Schema[Any] per variant; mutually referenced lazy vals.
        val selfSym = Symbol.newVal(owner, "_self", TypeRepr.of[Schema[A]], Flags.Lazy, Symbol.noSymbol)
        val variantSyms: List[Symbol] =
            (0 until n).toList.map(idx => Symbol.newVal(owner, s"_v$idx", TypeRepr.of[Schema[Any]], Flags.Lazy, Symbol.noSymbol))
        val nameByteSyms: List[Symbol] =
            (0 until n).toList.map(idx => Symbol.newVal(owner, s"_nb$idx", TypeRepr.of[Array[Byte]], Flags.EmptyFlags, Symbol.noSymbol))

        val selfRef: Term = Ref(selfSym)

        // Variant instance check: `v eq Singleton` for objects, `v.isInstanceOf[Child]` for classes.
        def variantCheck(idx: Int, v: Expr[A]): Expr[Boolean] =
            val child = children(idx)
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
                '{ $v.asInstanceOf[AnyRef] eq $singletonRef }
            else
                childTypes(idx).asType match
                    case '[t] => '{ $v.isInstanceOf[t] }
            end if
        end variantCheck

        def writeBody(v: Expr[A], w: Expr[Writer]): Expr[Unit] =
            val chain: Term = (0 until n).foldRight(
                '{
                    kyo.bug("Schema sum write: " + $v.asInstanceOf[Any].getClass.getName + " matched no variant of " + ${ Expr(typeName) })
                }.asTerm
            ) { (idx, elseTerm) =>
                val cond = variantCheck(idx, v).asTerm
                val arm = '{
                    $w.objectStart(${ Expr(typeName) }, 1)
                    $w.fieldBytes(${ Ref(nameByteSyms(idx)).asExprOf[Array[Byte]] }, ${ Expr(CodecMacro.fieldId(childNames(idx))) })
                    ${ Ref(variantSyms(idx)).asExprOf[Schema[Any]] }.serializeWrite($v, $w)
                    $w.objectEnd()
                }.asTerm
                If(cond, arm, elseTerm)
            }
            chain.asExprOf[Unit]
        end writeBody

        def readBody(r: Expr[Reader]): Expr[A] =
            val matchChain: Term = (0 until n).foldRight(
                '{
                    val _parsed = $r.lastFieldName()
                    $r.skip()
                    throw kyo.UnknownVariantException(Seq.empty, _parsed)(using $r.frame)
                }.asTerm
            ) { (idx, elseTerm) =>
                val cond = '{ $r.matchField(${ Ref(nameByteSyms(idx)).asExprOf[Array[Byte]] }) }.asTerm
                val arm  = '{ ${ Ref(variantSyms(idx)).asExprOf[Schema[Any]] }.serializeRead($r).asInstanceOf[A] }.asTerm
                If(cond, arm, elseTerm)
            }
            '{
                kyo.discard($r.objectStart())
                if ! $r.hasNextField() then
                    throw kyo.MissingFieldException(Seq.empty, "<discriminator>")(using $r.frame)
                $r.fieldParse()
                val _result: A = ${ matchChain.asExprOf[A] }
                $r.objectEnd()
                _result
            }
        end readBody

        def structureExpr: Expr[Structure.Type] =
            val tpStructures: Expr[kyo.Chunk[Structure.Type]] =
                if tpe.typeArgs.isEmpty then '{ kyo.Chunk.empty[Structure.Type] }
                else
                    val perParam: List[Expr[Structure.Type]] = tpe.typeArgs.map { tp =>
                        tp.asType match
                            case '[t] => '{ summonInline[Schema[t]].structure }
                    }
                    '{ kyo.Chunk.from[Structure.Type](Array[Structure.Type](${ Varargs(perParam) }*)) }
            val variantStructs: List[Expr[kyo.Structure.Variant]] = (0 until n).toList.map { idx =>
                '{ kyo.Structure.Variant(${ Expr(childNames(idx)) }, ${ Ref(variantSyms(idx)).asExprOf[Schema[Any]] }.structure) }
            }
            '{
                Structure.Type.Sum(
                    ${ Expr(typeName) },
                    $tagExpr,
                    $tpStructures,
                    kyo.Chunk.from[kyo.Structure.Variant](Array[kyo.Structure.Variant](${ Varargs(variantStructs) }*)),
                    kyo.Chunk.from[String](Array[String](${ Varargs(enumValues.map(Expr(_))) }*))
                )
            }
        end structureExpr

        val nameByteDefs: List[ValDef] = (0 until n).toList.map { idx =>
            val nameExpr = Expr(childNames(idx))
            ValDef(nameByteSyms(idx), Some('{ $nameExpr.getBytes(java.nio.charset.StandardCharsets.UTF_8) }.asTerm))
        }

        val selfRhs: Expr[Schema[A]] = '{
            Schema.init[A](
                writeFn = (v, w) => ${ writeBody('v, 'w) },
                readFn = r => ${ readBody('r) },
                sourceFields = $sourceFields,
                structure = ${ structureExpr }
            )
        }
        val selfDef = ValDef(selfSym, Some(selfRhs.asTerm.changeOwner(selfSym)))

        val variantDefs: List[ValDef] = children.zip(childTypes).zipWithIndex.map { case ((child, childType), idx) =>
            childType.asType match
                case '[t] =>
                    val vSchema = emitVariantSchemaStatic[t](childType, child, parentSelf = Some((tpe, selfRef)))
                    val rhs     = '{ $vSchema.asInstanceOf[Schema[Any]] }.asTerm
                    ValDef(variantSyms(idx), Some(rhs.changeOwner(variantSyms(idx))))
        }

        Block(nameByteDefs ++ (selfDef :: variantDefs), selfRef).asExprOf[Schema[A]]
    end emitSealedSchemaStatic

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
