package kyo.internal

import kyo.*
import kyo.Codec.Reader
import kyo.Codec.Writer
import kyo.Record.*
import scala.annotation.publicInBinary
import scala.quoted.*

/** Macro implementations for Focus navigation, Schema.apply[A], and Schema.derived[A].
  *
  * Focus navigation resolves field access by name using the structural type F, delegating to NavigationMacro for field resolution.
  *
  * Schema.apply expands A to its structural representation and constructs Schema[A] { type Focused = F } with identity getter/setter and
  * serialization support for case classes and sealed traits.
  *
  * Schema.derived provides auto-derivation for case classes and sealed traits during implicit search.
  */
@publicInBinary private[kyo] object FocusMacro:

    /** selectDynamic implementation for Focus.Select[A, F] with state composition.
      *
      * Returns Focus.Select[A, V] where V is the resolved field value type. Composes getter/setter/segments for the navigation chain.
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
        val nominalSym  = nominalType.typeSymbol

        nominalType.asType match
            case '[n] =>
                if isSum then
                    generateSumFocus[A, F, n](focus, nameStr, valueType)
                else
                    generateProductFocus[A, F, n](focus, nameStr, valueType)
        end match
    end focusSelectImpl

    /** Generates a Focus.Select[A, V] for a product field access. */
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
                    case Some(df) =>
                        // Direct: field exists on case class
                        generateDirectFocus[A, F, Nominal, v](focus, fieldName, caseFields, sym, nominalType, nameExpr)
                    case None =>
                        // Field not in case class. Check for rename by comparing with F's fields.
                        val expandedF     = ExpandMacro.expandType(TypeRepr.of[F])
                        val focusedFields = MacroUtils.collectFields(expandedF)
                        val focusedNames  = focusedFields.map(_._1).toSet

                        // Case fields missing from the focused type are rename-from candidates
                        val missingFromFocused = caseFields.filterNot(cf => focusedNames.contains(cf.name))

                        // Match by type: find a missing case field whose type matches valueType
                        val renamedFrom = missingFromFocused.find(cf => nominalType.memberType(cf) =:= valueType)

                        renamedFrom match
                            case Some(originalField) =>
                                // Renamed: use original name for accessor, fieldName for path
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
                                // Computed: runtime lookup through _computedFields
                                generateComputedFocus[A, F, v](focus, fieldName, nameExpr)
                        end match
                end match
        end match
    end generateProductFocus

    /** Generates Focus for a direct (non-transformed) field. */
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

    /** Generates Focus for a renamed field. Uses originalName for accessor, fieldName for path. */
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

    /** Generates Focus for a computed (added) field. Uses runtime lookup through _computedFields. */
    private def generateComputedFocus[A: Type, F: Type, V: Type](using
        Quotes
    )(
        focus: Expr[Focus.Select[A, F]],
        fieldName: String,
        nameExpr: Expr[String]
    ): Expr[Any] =
        import quotes.reflect.*

        val fieldNameExpr = Expr(fieldName)

        val getterExpr = '{ (root: A) =>
            $focus.getter.asInstanceOf[A => Maybe[Any]](root).map { (_: Any) =>
                // Computed field: look up the compute function from the schema
                val schema = $focus.schema.get.asInstanceOf[Schema[A]]
                val computeFn = schema.computedFields.toSeq.find(_._1 == $fieldNameExpr)
                    .getOrElse(throw new RuntimeException(s"Computed field '${$fieldNameExpr}' not found in schema"))
                    ._2
                computeFn(root).asInstanceOf[V]
            }
        }

        // Computed fields are read-only; setter is a no-op
        val setterExpr = '{ (root: A, _: V) => root }

        '{ Focus.Select.create[A, V]($getterExpr, $setterExpr, $focus.segments :+ $nameExpr, $focus.isPartial, $focus.schema) }
    end generateComputedFocus

    /** Generates a Focus.Select[A, V] for a sum variant access. */
    private def generateSumFocus[A: Type, F: Type, Nominal: Type](using
        Quotes
    )(
        focus: Expr[Focus.Select[A, F]],
        variantName: String,
        valueType: quotes.reflect.TypeRepr
    ): Expr[Any] =
        import quotes.reflect.*

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

    /** Macro implementation for Schema.apply[A].
      *
      * Expands A to its structural representation and constructs Schema[A] { type Focused = F } where F is the expanded type. The resulting
      * Schema has identity getter/setter and empty path segments.
      *
      * For case classes and sealed traits, also generates serialization (writeTo/readFrom).
      */
    def metaApplyImpl[A: Type](using Quotes): Expr[Any] =
        import quotes.reflect.*

        val tpe      = TypeRepr.of[A].dealias
        val sym      = tpe.typeSymbol
        val expanded = ExpandMacro.expandType(TypeRepr.of[A])

        // Check if A is a case class or sealed trait that should have serialization
        val isCaseClass   = sym.isClassDef && sym.flags.is(Flags.Case)
        val isSealedTrait = sym.flags.is(Flags.Sealed)

        expanded.asType match
            case '[f] =>
                // Summon Fields[A] to get field descriptors with proper tags and defaults
                val fieldsExpr = Expr.summon[Fields[A]].getOrElse(
                    report.errorAndAbort(s"Cannot summon Fields[${TypeRepr.of[A].show}]")
                )

                if isCaseClass then
                    // Case class: generate Schema with structural expansion AND serialization
                    generateCaseClassSchema[A, f]('{ $fieldsExpr.fields }, checkSerializability = true)
                else if isSealedTrait then
                    // Sealed trait: generate Schema with structural expansion AND serialization
                    generateSealedTraitSchema[A, f]('{ $fieldsExpr.fields }, checkSerializability = true)
                else
                    // Other types (Records, etc.): no serialization, use existing behavior
                    // Identity getter/setter at root level.
                    // F is the structural expansion of A -- same runtime representation,
                    // different compile-time type. Use Any as intermediate to avoid
                    // JVM class cast checks against the structural type (Record.~).
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
    // Shared schema generation (used by both metaApplyImpl and derivedImpl)
    // ==========================================================================

    /** Generates Schema[A] { type Focused = F } for a case class with serialization.
      *
      * @param sourceFieldsExpr
      *   the fields list expression (from Fields[A].fields for metaApply, Nil for derived)
      * @param checkSerializability
      *   if true, checks MacroSchemaClassifier.fieldKindFor and falls back to no-serialization Schema; if false, always generates serialization
      */
    private def generateCaseClassSchema[A: Type, F: Type](
        sourceFieldsExpr: Expr[List[Field[?, ?]]],
        checkSerializability: Boolean
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*

        val tpe         = TypeRepr.of[A].dealias
        val sym         = tpe.typeSymbol
        val fields      = sym.caseFields
        val typeName    = sym.name
        val n           = fields.size
        val isRecursive = fields.exists(f => SerializationMacro.containsType(tpe.memberType(f), tpe))

        // Compute stable field IDs from names
        val fieldIds: List[(String, Int)] = fields.map(f => f.name -> CodecMacro.fieldId(f.name))

        // Detect which fields are Maybe[?] or Option[Any] for special handling
        val (maybeFields, optionFields) = MacroUtils.detectMaybeOptionFields(tpe, fields)

        // Pre-encode field names as raw UTF-8 bytes for fast path
        val preEncodedExprs: List[Expr[Array[Byte]]] = fields.map { field =>
            Expr(field.name.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        }

        // Check if all field schemas can be generated (only when checkSerializability is true)
        val cannotSerialize = checkSerializability && !fields.zipWithIndex.forall { (field, idx) =>
            val fieldType = tpe.memberType(field)
            if maybeFields.contains(idx) then
                fieldType.dealias match
                    case AppliedType(_, List(innerType)) =>
                        MacroUtils.MacroSchemaClassifier.fieldKindFor(innerType) != MacroUtils.MacroSchemaClassifier.FieldKind.Generic
                    case _ => false
            else
                MacroUtils.MacroSchemaClassifier.fieldKindFor(fieldType) != MacroUtils.MacroSchemaClassifier.FieldKind.Generic
            end if
        }

        if cannotSerialize then
            val structureExpr = buildCaseClassStructureExpr[A](tpe, sym, typeName, maybeFields, optionFields)
            '{
                kyo.Schema.create[A, F](
                    ${ MacroUtils.identityGetter[A, F] },
                    ${ MacroUtils.identitySetter[A, F] },
                    Seq.empty,
                    $sourceFieldsExpr,
                    $structureExpr
                )
            }
        else

            val fieldSchemaBuilders = summonFieldSchemaResolvers[A](fields, tpe, maybeFields, optionFields, isRecursive)

            // Extract resolvers from field schema builders
            val fieldResolvers: List[SerializationMacro.SchemaResolver[A]] =
                fieldSchemaBuilders.map(_._3)

            // Extract resolver pairs for read body
            val fieldResolverPairs: List[(String, SerializationMacro.SchemaResolver[A])] =
                fieldSchemaBuilders.map { (name, _, builder) => (name, builder) }

            // The emitted lambda bodies route every field through the resolved per-field sub-schema cached in
            // the `_subSchemas` array, so every slot must be populated.
            val needsSubSchema: List[Boolean] = List.fill(fields.length)(true)

            val structureExpr = buildCaseClassStructureExpr[A](tpe, sym, typeName, maybeFields, optionFields)

            if isRecursive then
                '{
                    lazy val self: kyo.Schema[A] { type Focused = F } =
                        val _fieldBytes: Array[Array[Byte]] = ${ CodecMacro.mkFieldBytesPublic(preEncodedExprs) }
                        val _fieldNames: Array[String]      = ${ Expr(fields.map(_.name).toArray) }
                        // `_subSchemas` is `lazy` because, for recursive schemas, slots may contain `self`. Eager population
                        // of the array while `self` is still being initialized would cause infinite recursion / SOE. Lazy allocation
                        // defers array construction to first serializeWrite/Read call, by which time `self` is fully bound.
                        lazy val _subSchemas: Array[kyo.Schema[Any]] =
                            ${ buildSubSchemasArrayExpr[A](needsSubSchema, fieldResolvers, '{ self }) }
                        kyo.Schema.create[A, F](
                            ${ MacroUtils.identityGetter[A, F] },
                            ${ MacroUtils.identitySetter[A, F] },
                            Seq.empty,
                            $sourceFieldsExpr,
                            (value: A, writer: Writer) =>
                                ${
                                    SerializationMacro.caseClassWriteBody[A](
                                        typeName,
                                        n,
                                        fields.map(_.name),
                                        fieldIds,
                                        maybeFields,
                                        optionFields,
                                        fieldResolvers,
                                        '{ _fieldBytes },
                                        '{ _subSchemas },
                                        '{ self },
                                        '{ value },
                                        '{ writer }
                                    )
                                },
                            (reader: Reader) =>
                                ${
                                    SerializationMacro.caseClassReadBodyResolved[A](
                                        '{ reader },
                                        '{ _fieldBytes },
                                        '{ _fieldNames },
                                        fieldResolverPairs,
                                        '{ _subSchemas },
                                        '{ self }
                                    )
                                },
                            $structureExpr
                        )
                    end self
                    self
                }
            else
                // Simple (non-recursive) case class

                // Extract name -> schema pairs for the non-recursive read body
                val fieldSchemaExprs: List[(String, Expr[Schema[Any]])] =
                    fieldSchemaBuilders.map { (name, _, resolver) =>
                        (name, resolver('{ null.asInstanceOf[Schema[A]] }))
                    }

                '{
                    val _fieldBytes: Array[Array[Byte]] = ${ CodecMacro.mkFieldBytesPublic(preEncodedExprs) }
                    val _fieldNames: Array[String]      = ${ Expr(fields.map(_.name).toArray) }
                    val _subSchemas: Array[kyo.Schema[Any]] =
                        ${ buildSubSchemasArrayExpr[A](needsSubSchema, fieldResolvers, '{ null.asInstanceOf[Schema[A]] }) }
                    kyo.Schema.create[A, F](
                        ${ MacroUtils.identityGetter[A, F] },
                        ${ MacroUtils.identitySetter[A, F] },
                        Seq.empty,
                        $sourceFieldsExpr,
                        (value: A, writer: Writer) =>
                            ${
                                val dummySelf = '{ null.asInstanceOf[Schema[A]] }
                                SerializationMacro.caseClassWriteBody[A](
                                    typeName,
                                    n,
                                    fields.map(_.name),
                                    fieldIds,
                                    maybeFields,
                                    optionFields,
                                    fieldResolvers,
                                    '{ _fieldBytes },
                                    '{ _subSchemas },
                                    dummySelf,
                                    '{ value },
                                    '{ writer }
                                )
                            },
                        (reader: Reader) =>
                            ${
                                SerializationMacro.caseClassReadBody[A](
                                    '{ reader },
                                    '{ _fieldBytes },
                                    '{ _fieldNames },
                                    fieldSchemaExprs,
                                    '{ _subSchemas }
                                )
                            },
                        $structureExpr
                    )
                }
            end if
        end if
    end generateCaseClassSchema

    /** Generates Schema[A] { type Focused = F } for a sealed trait with serialization.
      *
      * @param sourceFieldsExpr
      *   the fields list expression (from Fields[A].fields for metaApply, Nil for derived)
      * @param checkSerializability
      *   if true, checks MacroSchemaClassifier.fieldKindFor and falls back to no-serialization Schema; if false, always generates serialization
      */
    private def generateSealedTraitSchema[A: Type, F: Type](
        sourceFieldsExpr: Expr[List[Field[?, ?]]],
        checkSerializability: Boolean
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived

        val tpe      = TypeRepr.of[A].dealias
        val sym      = tpe.typeSymbol
        val typeName = sym.name
        val children = sym.children

        if children.isEmpty then
            report.errorAndAbort(s"Cannot derive Schema for sealed trait ${sym.name}: no case class or object variants found.")

        // Check if all variant types are serializable (only when checkSerializability is true)
        val cannotSerialize = checkSerializability && !children.forall { child =>
            val childType = child.typeRef
            if childType =:= tpe then true // self-referential - will use self schema
            else MacroUtils.MacroSchemaClassifier.fieldKindFor(childType) != MacroUtils.MacroSchemaClassifier.FieldKind.Generic
        }

        if cannotSerialize then
            val structureExpr = buildSealedTraitStructureExpr[A](tpe, sym, typeName, children)
            '{
                kyo.Schema.create[A, F](
                    ${ MacroUtils.identityGetter[A, F] },
                    ${ MacroUtils.identitySetter[A, F] },
                    Seq.empty,
                    $sourceFieldsExpr,
                    $structureExpr
                )
            }
        else

            // Compute stable variant IDs from names
            val variantIds: List[(String, Int)] = children.map(c => c.name -> CodecMacro.fieldId(c.name))

            val isRecursive = children.exists { child =>
                SerializationMacro.containsType(child.typeRef, tpe)
            }

            // Pre-encode discriminator field name strings
            val preEncodedExprs: List[Expr[Array[Byte]]] = children.map { child =>
                Expr(child.name.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            }

            // Build variant infos, unified for both simple and recursive
            val variants: List[SerializationMacro.VariantInfo[A]] =
                children.map { child =>
                    val childType =
                        if child.isType then child.typeRef
                        else if child.flags.is(Flags.Module) then child.termRef.widen
                        else child.typeRef

                    // Variant check: for class-like children (child.isType) use isInstanceOf[t].
                    // For module children (no-arg enum cases, case objects), `child.termRef.widen`
                    // widens the singleton term-ref to the PARENT enum/sealed type, so
                    // `isInstanceOf[t]` collapses to `isInstanceOf[ParentEnum]`, a tautology
                    // that matches every variant. Use reference equality against the singleton
                    // instead.
                    val checkExpr: Expr[A] => Expr[Boolean] =
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
                            (v: Expr[A]) => '{ $v.asInstanceOf[AnyRef] eq $singletonRef }
                        else
                            childType.asType match
                                case '[t] => (v: Expr[A]) => '{ $v.isInstanceOf[t] }

                    childType.asType match
                        case '[t] =>
                            val schemaResolver: SerializationMacro.SchemaResolver[A] =
                                if isRecursive && childType =:= tpe then
                                    (self: Expr[Schema[A]]) => '{ $self.asInstanceOf[Schema[Any]] }
                                else if isRecursive then
                                    // For recursive sealed traits, generate ALL variant schemas inline
                                    // to avoid circular implicit resolution (summoning Schema for a variant
                                    // triggers Schema.derived which would re-derive the parent sealed trait).
                                    buildVariantSchemaResolver[A, t](childType, tpe, child)
                                else
                                    Expr.summon[Schema[t]] match
                                        case Some(schema) =>
                                            (_: Expr[Schema[A]]) => '{ $schema.asInstanceOf[Schema[Any]] }
                                        case None =>
                                            // Schema not found via summoning; generate inline
                                            // (handles case objects, enum values, etc.)
                                            buildVariantSchemaResolver[A, t](childType, tpe, child)
                            SerializationMacro.VariantInfo[A](
                                child.name,
                                checkExpr,
                                (v: Expr[A]) => '{ $v.asInstanceOf[t].asInstanceOf[Any] },
                                schemaResolver
                            )
                    end match
                }

            val structureExpr = buildSealedTraitStructureExpr[A](tpe, sym, typeName, children)

            if isRecursive then
                '{
                    lazy val self: kyo.Schema[A] { type Focused = F } =
                        val _fieldBytes: Array[Array[Byte]] = ${ CodecMacro.mkFieldBytesPublic(preEncodedExprs) }
                        kyo.Schema.create[A, F](
                            ${ MacroUtils.identityGetter[A, F] },
                            ${ MacroUtils.identitySetter[A, F] },
                            Seq.empty,
                            $sourceFieldsExpr,
                            (value: A, writer: Writer) =>
                                ${
                                    SerializationMacro.sealedWriteBody[A](
                                        typeName,
                                        variantIds,
                                        variants,
                                        '{ _fieldBytes },
                                        '{ self },
                                        '{ value },
                                        '{ writer }
                                    )
                                },
                            (reader: Reader) =>
                                ${
                                    val schemaExprs = variants.map { info =>
                                        (info.name, info.schemaResolver('{ self }))
                                    }
                                    SerializationMacro.sealedReadBody[A]('{ reader }, '{ _fieldBytes }, schemaExprs)
                                },
                            $structureExpr
                        )
                    end self
                    self
                }
            else
                '{
                    val _fieldBytes: Array[Array[Byte]] = ${ CodecMacro.mkFieldBytesPublic(preEncodedExprs) }
                    kyo.Schema.create[A, F](
                        ${ MacroUtils.identityGetter[A, F] },
                        ${ MacroUtils.identitySetter[A, F] },
                        Seq.empty,
                        $sourceFieldsExpr,
                        (value: A, writer: Writer) =>
                            ${
                                val dummySelf = '{ null.asInstanceOf[Schema[A]] }
                                SerializationMacro.sealedWriteBody[A](
                                    typeName,
                                    variantIds,
                                    variants,
                                    '{ _fieldBytes },
                                    dummySelf,
                                    '{ value },
                                    '{ writer }
                                )
                            },
                        (reader: Reader) =>
                            ${
                                val schemaExprs = variants.map { info =>
                                    (info.name, info.schemaResolver('{ null.asInstanceOf[Schema[A]] }))
                                }
                                SerializationMacro.sealedReadBody[A]('{ reader }, '{ _fieldBytes }, schemaExprs)
                            },
                        $structureExpr
                    )
                }
            end if
        end if
    end generateSealedTraitSchema

    // ==========================================================================
    // Schema derivation entry point
    // ==========================================================================

    /** Derives Schema[A] with serialization for case classes and sealed traits.
      *
      * Case classes: fields are walked via `sym.caseFields` and each field's Schema is
      * resolved by `Expr.summon[Schema[t]]` at macro-expansion time, with
      * `buildContainerSchemaOpt` as the fallback for container-field types whose givens
      * carry a `using Frame` constraint (see `derivedEmitProduct` and
      * `summonFieldSchemaResolvers`). Private case-fields are rejected at derivation time
      * via `derivedRejectPrivateCaseFields` to prevent silent wire-name corruption.
      *
      * Sealed traits: delegated to `generateSealedTraitSchema`, which handles variants
      * without explicit Schema instances (via `buildVariantSchemaResolver`), recursive
      * parent-type references, case objects, and Scala 3 enum values.
      *
      * This method lives in FocusMacro (rather than SchemaDerivedMacro) because FocusMacro
      * is already a compile-time dependency of Schema.scala (for other macro calls such as
      * defaultsImpl and metaApplyImpl). Moving the implementation here avoids the cyclic
      * macro dependency that would arise if SchemaDerivedMacro.scala referenced Schema
      * types directly in its quoted output while Schema.scala held a macro call into
      * SchemaDerivedMacro.
      */
    def derivedImpl[A: Type](using Quotes): Expr[Schema[A]] =
        import quotes.reflect.*

        val tpe = TypeRepr.of[A].dealias
        val sym = tpe.typeSymbol

        if sym.isClassDef && sym.flags.is(Flags.Sealed) then
            // Sealed traits use the existing generateSealedTraitSchema path, which correctly handles
            // variants without explicit Schema instances (via buildVariantSchemaResolver), recursive
            // parent-type references, case objects, and Scala 3 enum values.
            val nilExpr = '{ Nil: List[Field[?, ?]] }
            '{ ${ generateSealedTraitSchema[A, A](nilExpr, checkSerializability = false) }.asInstanceOf[kyo.Schema[A]] }
        else if sym.isClassDef && sym.flags.is(Flags.Case) then
            // Case classes resolve each field's Schema via Expr.summon[Schema[t]] at macro-expansion
            // time, with buildContainerSchemaOpt as the fallback for container-field types (Chunk,
            // List, Maybe, Option, ...) whose givens carry a using Frame constraint.
            derivedRejectPrivateCaseFields(tpe, sym)
            derivedEmitProduct[A](tpe, sym)
        else
            report.errorAndAbort(
                s"Cannot derive Schema for ${tpe.show}: not a case class or sealed trait. " +
                    "Provide a given Schema instance for this type if derivation is not possible."
            )
        end if
    end derivedImpl

    /** Guard: a case class with a private case-field is rejected at derivation time. The
      * macro's caseFields walk would otherwise emit the storage member as a wire field,
      * corrupting the on-wire shape silently. The `Structure.Field` case class itself has a
      * private case-field (`_fieldType`) and is the structural failure mode this guard
      * prevents from recurring; its Schema is hand-rolled in `Structure.scala` (see
      * `structureFieldSchema`) so it never reaches this guard.
      */
    private def derivedRejectPrivateCaseFields(using
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
    end derivedRejectPrivateCaseFields

    /** Emits the Schema for a case class.
      *
      * Uses the existing `buildCaseClassStructureExpr` for the `structure` lazy val so that
      * container fields (Chunk, List, Maybe, Option, etc.) are handled correctly without
      * requiring a `Frame` at the `derives Schema` call site. Uses `summonFieldSchemaResolvers`
      * for the serialization resolver list; that method handles recursive fields, container
      * fields (via `buildContainerSchemaOpt`), and non-recursive fields uniformly. Private
      * case-fields are rejected before this method is called via `derivedRejectPrivateCaseFields`.
      *
      * Note: `summonInline[Schema[t]]` cannot be used for container field types (Chunk, List,
      * Maybe, Option, etc.) because those schema givens carry `using Frame` constraints that
      * are not satisfied at the `derives Schema` call site in user code. The existing
      * `summonFieldSchemaResolvers` and `buildCaseClassStructureExpr` helpers handle containers
      * correctly via `buildContainerSchemaOpt`.
      */
    private def derivedEmitProduct[A: Type](using
        Quotes
    )(
        tpe: quotes.reflect.TypeRepr,
        sym: quotes.reflect.Symbol
    ): Expr[Schema[A]] =
        import quotes.reflect.*

        val nameStr  = sym.name
        val typeName = nameStr
        val fields   = sym.caseFields
        val n        = fields.length

        val (maybeFields, optionFields) = MacroUtils.detectMaybeOptionFields(tpe, fields)

        val isRecursive: Boolean = fields.exists { f =>
            SerializationMacro.containsType(tpe.memberType(f), tpe)
        }

        val fieldSchemaBuilders = summonFieldSchemaResolvers[A](fields, tpe, maybeFields, optionFields, isRecursive)
        val fieldResolvers: List[SerializationMacro.SchemaResolver[A]] = fieldSchemaBuilders.map(_._3)
        val namedResolvers: List[(String, SerializationMacro.SchemaResolver[A])] =
            fieldSchemaBuilders.map { (name, _, resolver) => (name, resolver) }
        // Compute stable hash-based field IDs (same as generateCaseClassSchema uses).
        // summonFieldSchemaResolvers returns (name, index, resolver); the second element
        // is the field index, NOT the hash ID. Use CodecMacro.fieldId for the correct hash.
        val derivedFieldIds: List[(String, Int)] = fields.map(f => f.name -> CodecMacro.fieldId(f.name))

        val needsSubSchema: List[Boolean] = List.fill(fields.length)(true)

        val preEncodedExprs: List[Expr[Array[Byte]]] = fields.map { f =>
            Expr(f.name.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        }
        val fieldNamesArr: Array[String] = fields.map(_.name).toArray

        val structureExpr: Expr[Structure.Type] =
            buildCaseClassStructureExpr[A](tpe, sym, typeName, maybeFields, optionFields)

        if isRecursive then
            '{
                lazy val self: kyo.Schema[A] =
                    val _fieldBytes: Array[Array[Byte]] = ${ CodecMacro.mkFieldBytesPublic(preEncodedExprs) }
                    val _fieldNames: Array[String]      = ${ Expr(fieldNamesArr) }
                    lazy val _subSchemas: Array[kyo.Schema[Any]] =
                        ${ buildSubSchemasArrayExpr[A](needsSubSchema, fieldResolvers, '{ self }) }
                    new Schema[A](Seq.empty):
                        import scala.annotation.publicInBinary
                        private lazy val _structure: Structure.Type = $structureExpr
                        override def structure: Structure.Type      = _structure

                        @publicInBinary private[kyo] def serializeWrite(value: A, writer: Writer): Unit =
                            ${
                                SerializationMacro.caseClassWriteBody[A](
                                    typeName,
                                    n,
                                    fields.map(_.name),
                                    derivedFieldIds,
                                    maybeFields,
                                    optionFields,
                                    fieldResolvers,
                                    '{ _fieldBytes },
                                    '{ _subSchemas },
                                    '{ self },
                                    '{ value },
                                    '{ writer }
                                )
                            }

                        @publicInBinary private[kyo] def serializeRead(reader: Reader): A =
                            ${
                                SerializationMacro.caseClassReadBodyResolved[A](
                                    '{ reader },
                                    '{ _fieldBytes },
                                    '{ _fieldNames },
                                    namedResolvers,
                                    '{ _subSchemas },
                                    '{ self }
                                )
                            }

                        @publicInBinary private[kyo] def getter(value: A): Maybe[Any] = Maybe(value)
                        @publicInBinary private[kyo] def setter(value: A, next: Any): A =
                            next.asInstanceOf[A]
                    end new
                end self
                self
            }
        else
            val dummySelf        = '{ null.asInstanceOf[Schema[A]] }
            val subSchemasE      = buildSubSchemasArrayExpr[A](needsSubSchema, fieldResolvers, dummySelf)
            val fieldBytesOuterE = CodecMacro.mkFieldBytesPublic(preEncodedExprs)
            val fieldNamesOuterE = Expr(fieldNamesArr)
            '{
                val _fieldBytes: Array[Array[Byte]]     = ${ fieldBytesOuterE }
                val _fieldNames: Array[String]          = ${ fieldNamesOuterE }
                val _subSchemas: Array[kyo.Schema[Any]] = ${ subSchemasE }
                new Schema[A](Seq.empty):
                    import scala.annotation.publicInBinary
                    private lazy val _structure: Structure.Type = $structureExpr
                    override def structure: Structure.Type      = _structure

                    @publicInBinary private[kyo] def serializeWrite(value: A, writer: Writer): Unit =
                        ${
                            SerializationMacro.caseClassWriteBody[A](
                                typeName,
                                n,
                                fields.map(_.name),
                                derivedFieldIds,
                                maybeFields,
                                optionFields,
                                fieldResolvers,
                                '{ _fieldBytes },
                                '{ _subSchemas },
                                dummySelf,
                                '{ value },
                                '{ writer }
                            )
                        }

                    @publicInBinary private[kyo] def serializeRead(reader: Reader): A =
                        ${
                            SerializationMacro.caseClassReadBodyResolved[A](
                                '{ reader },
                                '{ _fieldBytes },
                                '{ _fieldNames },
                                namedResolvers,
                                '{ _subSchemas },
                                dummySelf
                            )
                        }

                    @publicInBinary private[kyo] def getter(value: A): Maybe[Any] = Maybe(value)
                    @publicInBinary private[kyo] def setter(value: A, next: Any): A =
                        next.asInstanceOf[A]
                end new
            }
        end if
    end derivedEmitProduct

    /** Builds a schema resolver for a sealed trait variant that contains the recursive parent type.
      *
      * Instead of summoning Schema[VariantType] (which would fail due to recursion), this generates the variant's case class schema inline
      * using SerializationMacro infrastructure, resolving recursive fields through the parent sealed trait's `self` reference.
      */
    private def buildVariantSchemaResolver[A: Type, T: Type](using
        Quotes
    )(
        childType: quotes.reflect.TypeRepr,
        parentType: quotes.reflect.TypeRepr,
        child: quotes.reflect.Symbol
    ): SerializationMacro.SchemaResolver[A] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived

        val childSym    = childType.typeSymbol
        val childFields = childSym.caseFields

        if childFields.isEmpty then
            // Case object variant: serialize as empty object
            val singletonRef: Expr[T] =
                if child.flags.is(Flags.Module) then
                    Ref(child.companionModule).asExprOf[T]
                else
                    // Enum case without parameters - access via companion
                    val parentSym = childType.typeSymbol.owner
                    if parentSym.companionModule != Symbol.noSymbol then
                        Select.unique(Ref(parentSym.companionModule), child.name).asExprOf[T]
                    else
                        Ref(child).asExprOf[T]
                    end if

            (self: Expr[Schema[A]]) =>
                '{
                    kyo.Schema.init[T](
                        writeFn = (_, w) =>
                            w.objectStart(${ Expr(child.name) }, 0); w.objectEnd()
                        ,
                        readFn = r =>
                            kyo.discard(r.objectStart()); r.objectEnd(); $singletonRef
                        ,
                        structure = kyo.Structure.Type.Product(${ Expr(child.name) }, kyo.Tag[Any], kyo.Chunk.empty, kyo.Chunk.empty)
                    ).asInstanceOf[Schema[Any]]
                }
        else
            // Case class variant: build field schema resolvers that reference the parent's self
            // for recursive fields, then use SerializationMacro to generate the schema.
            val (maybeFields, optionFields) = MacroUtils.detectMaybeOptionFields(childType, childFields)

            // Build field schema resolvers that resolve recursion through the PARENT type
            val fieldResolvers: List[(String, Int, SerializationMacro.SchemaResolver[A])] =
                childFields.zipWithIndex.map { (field, idx) =>
                    val fieldType = childType.memberType(field)
                    val fieldName = field.name

                    val resolver: SerializationMacro.SchemaResolver[A] = fieldType.asType match
                        case '[ft] =>
                            if fieldType.dealias =:= parentType then
                                // Field is the parent type directly: use self
                                (self: Expr[Schema[A]]) => '{ $self.asInstanceOf[Schema[Any]] }
                            else if containsRecursiveType(fieldType, parentType) then
                                // Field contains the parent type in containers/tuples
                                buildRecursiveResolver[A](fieldType, parentType, fieldName)
                            else if maybeFields.contains(idx) then
                                // Maybe field: extract inner type
                                fieldType.dealias match
                                    case AppliedType(_, List(innerType)) =>
                                        innerType.asType match
                                            case '[inner] =>
                                                val schema = Expr.summon[Schema[inner]].getOrElse(
                                                    report.errorAndAbort(noSchemaMessage(fieldName, innerType.show, fieldType.show))
                                                )
                                                (_: Expr[Schema[A]]) => '{ $schema.asInstanceOf[Schema[Any]] }
                                    case _ =>
                                        report.errorAndAbort(
                                            s"Cannot resolve inner type for Maybe field '$fieldName'. " +
                                                "Ensure the field is declared as Maybe[T] where T is a concrete type with a given Schema."
                                        )
                            else
                                // Normal field: try direct summon, then fall back to explicit container
                                // schema construction for collection/optional types that require Frame.
                                val schemaExpr: Expr[Schema[Any]] =
                                    Expr.summon[Schema[ft]]
                                        .map(s => '{ $s.asInstanceOf[Schema[Any]] })
                                        .orElse(buildContainerSchemaOpt[A](fieldType, fieldName))
                                        .getOrElse(report.errorAndAbort(noSchemaFieldMessage(fieldName, fieldType)))
                                (_: Expr[Schema[A]]) => schemaExpr

                    (fieldName, idx, resolver)
                }

            // Pre-encode field name bytes
            val preEncodedFieldExprs: List[Expr[Array[Byte]]] = childFields.map { f =>
                Expr(f.name.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            }
            val fieldBytesExpr = CodecMacro.mkFieldBytesPublic(preEncodedFieldExprs)
            val fieldNamesExpr: Expr[Array[String]] =
                Expr(childFields.map(_.name).toArray)

            // Compute field IDs
            val fieldIds: List[(String, Int)] = childFields.map(f => f.name -> CodecMacro.fieldId(f.name))

            val childTypeName = childSym.name

            // The emitted lambda bodies route every variant field through the resolved per-field sub-schema
            // cached in the `_subSchemas` array, so every slot must be populated.
            val needsSubSchema: List[Boolean] = List.fill(childFields.length)(true)

            (self: Expr[Schema[A]]) =>
                // Resolve all field schemas eagerly with the parent's self, then create
                // trivial resolvers for [T] that return the pre-resolved schemas.
                // This wraps parent-type resolvers as variant-type resolvers.
                val resolversForT: List[SerializationMacro.SchemaResolver[T]] =
                    fieldResolvers.map { (_, _, resolver) =>
                        val resolved = resolver(self)
                        (_: Expr[Schema[T]]) => resolved
                    }

                val namedResolversForT: List[(String, SerializationMacro.SchemaResolver[T])] =
                    fieldResolvers.map { (name, _, resolver) =>
                        val resolved = resolver(self)
                        (name, (_: Expr[Schema[T]]) => resolved)
                    }

                val fieldNamesList = childFields.map(_.name)
                val dummySelf      = '{ null.asInstanceOf[Schema[T]] }

                // Build the sub-schemas array expression. Cache each variant field's resolved sub-schema
                // at lambda construction rather than resolving it per encode/decode.
                val subSchemasArrExpr: Expr[Array[kyo.Schema[Any]]] =
                    buildSubSchemasArrayFromResolved(
                        needsSubSchema,
                        resolversForT.map(r => r(dummySelf))
                    )

                '{
                    val _subSchemas: Array[kyo.Schema[Any]] = $subSchemasArrExpr
                    kyo.Schema.init[T](
                        writeFn = (value: T, writer: Writer) =>
                            ${
                                SerializationMacro.caseClassWriteBody[T](
                                    childTypeName,
                                    childFields.length,
                                    fieldNamesList,
                                    fieldIds,
                                    maybeFields,
                                    optionFields,
                                    resolversForT,
                                    fieldBytesExpr,
                                    '{ _subSchemas },
                                    dummySelf,
                                    '{ value },
                                    '{ writer }
                                )
                            },
                        readFn = (reader: Reader) =>
                            ${
                                SerializationMacro.caseClassReadBodyResolved[T](
                                    '{ reader },
                                    fieldBytesExpr,
                                    fieldNamesExpr,
                                    namedResolversForT,
                                    '{ _subSchemas },
                                    dummySelf
                                )
                            },
                        structure = kyo.Structure.Type.Open(kyo.Tag[Any])
                    ).asInstanceOf[Schema[Any]]
                }
        end if
    end buildVariantSchemaResolver

    /** Checks if a type contains the recursive parent type anywhere in its type arguments (recursively). */
    private def containsRecursiveType(using Quotes)(fieldType: quotes.reflect.TypeRepr, parentType: quotes.reflect.TypeRepr): Boolean =
        import quotes.reflect.*
        val dealiased = fieldType.dealias
        if dealiased =:= parentType then true
        else
            dealiased match
                case AppliedType(_, args) => args.exists(arg => containsRecursiveType(arg, parentType))
                case _                    => false
        end if
    end containsRecursiveType

    /** Builds a recursive schema resolver for field types that contain the recursive parent type inside containers or tuples. Handles
      * patterns like Chunk[(String, Value)], List[Value], etc.
      */
    private def buildRecursiveResolver[A](using
        Quotes,
        Type[A]
    )(
        fieldType: quotes.reflect.TypeRepr,
        parentType: quotes.reflect.TypeRepr,
        fieldName: String
    ): SerializationMacro.SchemaResolver[A] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val dealiased                  = fieldType.dealias
        dealiased match
            // Direct recursion
            case t if t =:= parentType =>
                (self: Expr[Schema[A]]) => '{ $self.asInstanceOf[Schema[Any]] }

            // Container[RecursiveType] -- e.g. List[Value], Chunk[Value]
            // Use Schema.init with inline structure to avoid the `using frame: Frame` requirement
            // on the public container givens. Frame.derive is blocked inside the kyo package.
            case AppliedType(tycon, List(arg)) if arg.dealias =:= parentType =>
                val containerSym = tycon.typeSymbol
                if containerSym == TypeRepr.of[List].typeSymbol then
                    (self: Expr[Schema[A]]) =>
                        '{
                            kyo.Schema.init[List[A]](
                                writeFn = (value, writer) =>
                                    writer.arrayStart(value.size)
                                    value.foreach($self.asInstanceOf[kyo.Schema[A]].serializeWrite(_, writer))
                                    writer.arrayEnd()
                                ,
                                readFn = reader =>
                                    import scala.annotation.tailrec
                                    discard(reader.arrayStart())
                                    val builder = List.newBuilder[A]
                                    @tailrec def loop(count: Int): Unit =
                                        if reader.hasNextElement() then
                                            reader.checkCollectionSize(count)
                                            builder += $self.asInstanceOf[kyo.Schema[A]].serializeRead(reader)
                                            loop(count + 1)
                                    loop(1)
                                    reader.arrayEnd()
                                    builder.result()
                                ,
                                structure = kyo.Structure.Type.Collection("List", kyo.Tag[Any], $self.asInstanceOf[kyo.Schema[A]].structure)
                            ).asInstanceOf[Schema[Any]]
                        }
                else if containerSym == TypeRepr.of[Option[Any]].typeSymbol then
                    (self: Expr[Schema[A]]) =>
                        '{
                            kyo.Schema.init[Option[A]](
                                writeFn = (value, writer) =>
                                    value match
                                        case Some(v) => $self.asInstanceOf[kyo.Schema[A]].serializeWrite(v, writer)
                                        case None =>
                                            writer.nil()
                                ,
                                readFn = reader =>
                                    if reader.isNil() then None
                                    else Some($self.asInstanceOf[kyo.Schema[A]].serializeRead(reader)),
                                structure = kyo.Structure.Type.Optional("Option", kyo.Tag[Any], $self.asInstanceOf[kyo.Schema[A]].structure)
                            ).asInstanceOf[Schema[Any]]
                        }
                else if containerSym == TypeRepr.of[kyo.Maybe[Any]].typeSymbol then
                    (self: Expr[Schema[A]]) =>
                        '{
                            kyo.Schema.init[kyo.Maybe[A]](
                                writeFn = (value, writer) =>
                                    value match
                                        case kyo.Maybe.Present(v) => $self.asInstanceOf[kyo.Schema[A]].serializeWrite(v, writer)
                                        case _ =>
                                            writer.nil()
                                ,
                                readFn = reader =>
                                    if reader.isNil() then kyo.Maybe.empty
                                    else kyo.Maybe($self.asInstanceOf[kyo.Schema[A]].serializeRead(reader)),
                                structure = kyo.Structure.Type.Optional("Maybe", kyo.Tag[Any], $self.asInstanceOf[kyo.Schema[A]].structure)
                            ).asInstanceOf[Schema[Any]]
                        }
                else if containerSym == TypeRepr.of[kyo.Chunk[Any]].typeSymbol then
                    (self: Expr[Schema[A]]) =>
                        '{
                            kyo.Schema.init[kyo.Chunk[A]](
                                writeFn = (value, writer) =>
                                    writer.arrayStart(value.size)
                                    value.foreach($self.asInstanceOf[kyo.Schema[A]].serializeWrite(_, writer))
                                    writer.arrayEnd()
                                ,
                                readFn = reader =>
                                    import scala.annotation.tailrec
                                    discard(reader.arrayStart())
                                    val builder = kyo.Chunk.newBuilder[A]
                                    @tailrec def loop(count: Int): Unit =
                                        if reader.hasNextElement() then
                                            reader.checkCollectionSize(count)
                                            builder += $self.asInstanceOf[kyo.Schema[A]].serializeRead(reader)
                                            loop(count + 1)
                                    loop(1)
                                    reader.arrayEnd()
                                    builder.result()
                                ,
                                structure =
                                    kyo.Structure.Type.Collection("Chunk", kyo.Tag[Any], $self.asInstanceOf[kyo.Schema[A]].structure)
                            ).asInstanceOf[Schema[Any]]
                        }
                else if containerSym == TypeRepr.of[Vector[Any]].typeSymbol then
                    (self: Expr[Schema[A]]) =>
                        '{
                            kyo.Schema.init[Vector[A]](
                                writeFn = (value, writer) =>
                                    writer.arrayStart(value.size)
                                    value.foreach($self.asInstanceOf[kyo.Schema[A]].serializeWrite(_, writer))
                                    writer.arrayEnd()
                                ,
                                readFn = reader =>
                                    import scala.annotation.tailrec
                                    discard(reader.arrayStart())
                                    val builder = Vector.newBuilder[A]
                                    @tailrec def loop(count: Int): Unit =
                                        if reader.hasNextElement() then
                                            reader.checkCollectionSize(count)
                                            builder += $self.asInstanceOf[kyo.Schema[A]].serializeRead(reader)
                                            loop(count + 1)
                                    loop(1)
                                    reader.arrayEnd()
                                    builder.result()
                                ,
                                structure =
                                    kyo.Structure.Type.Collection("Vector", kyo.Tag[Any], $self.asInstanceOf[kyo.Schema[A]].structure)
                            ).asInstanceOf[Schema[Any]]
                        }
                else if containerSym == TypeRepr.of[Set[Any]].typeSymbol then
                    (self: Expr[Schema[A]]) =>
                        '{
                            kyo.Schema.init[Set[A]](
                                writeFn = (value, writer) =>
                                    writer.arrayStart(value.size)
                                    value.foreach($self.asInstanceOf[kyo.Schema[A]].serializeWrite(_, writer))
                                    writer.arrayEnd()
                                ,
                                readFn = reader =>
                                    import scala.annotation.tailrec
                                    discard(reader.arrayStart())
                                    val builder = Set.newBuilder[A]
                                    @tailrec def loop(count: Int): Unit =
                                        if reader.hasNextElement() then
                                            reader.checkCollectionSize(count)
                                            builder += $self.asInstanceOf[kyo.Schema[A]].serializeRead(reader)
                                            loop(count + 1)
                                    loop(1)
                                    reader.arrayEnd()
                                    builder.result()
                                ,
                                structure = kyo.Structure.Type.Collection("Set", kyo.Tag[Any], $self.asInstanceOf[kyo.Schema[A]].structure)
                            ).asInstanceOf[Schema[Any]]
                        }
                else if containerSym == TypeRepr.of[Seq[Any]].typeSymbol then
                    (self: Expr[Schema[A]]) =>
                        '{
                            kyo.Schema.init[Seq[A]](
                                writeFn = (value, writer) =>
                                    writer.arrayStart(value.size)
                                    value.foreach($self.asInstanceOf[kyo.Schema[A]].serializeWrite(_, writer))
                                    writer.arrayEnd()
                                ,
                                readFn = reader =>
                                    import scala.annotation.tailrec
                                    discard(reader.arrayStart())
                                    val builder = List.newBuilder[A]
                                    @tailrec def loop(): Unit =
                                        if reader.hasNextElement() then
                                            builder += $self.asInstanceOf[kyo.Schema[A]].serializeRead(reader)
                                            loop()
                                    loop()
                                    reader.arrayEnd()
                                    builder.result()
                                ,
                                structure = kyo.Structure.Type.Collection("Seq", kyo.Tag[Any], $self.asInstanceOf[kyo.Schema[A]].structure)
                            ).asInstanceOf[Schema[Any]]
                        }
                else
                    report.errorAndAbort(
                        s"Cannot derive Schema for field '$fieldName': recursive type in unsupported container ${tycon.show}. " +
                            "Supported containers: List, Vector, Set, Seq, Chunk, Option, Maybe, Map[String, _]."
                    )
                end if

            // Container[Tuple2[X, RecursiveType]] or Option[Map[String, RecursiveType]] etc.
            // Use Schema.init with inline structure (same reason as Category 1: Frame.derive blocked in kyo package).
            case AppliedType(tycon, List(inner)) if containsRecursiveType(inner, parentType) =>
                val containerSym = tycon.typeSymbol
                // Build the inner schema resolver recursively, then wrap in the container
                val innerResolver = buildRecursiveResolver[A](inner, parentType, fieldName)
                inner.asType match
                    case '[innerT] =>
                        if containerSym == TypeRepr.of[kyo.Chunk[Any]].typeSymbol then
                            (self: Expr[Schema[A]]) =>
                                val innerSchema = innerResolver(self)
                                '{
                                    val inner = $innerSchema.asInstanceOf[Schema[innerT]]
                                    kyo.Schema.init[kyo.Chunk[innerT]](
                                        writeFn = (value, writer) =>
                                            writer.arrayStart(value.size)
                                            value.foreach(inner.serializeWrite(_, writer))
                                            writer.arrayEnd()
                                        ,
                                        readFn = reader =>
                                            import scala.annotation.tailrec
                                            discard(reader.arrayStart())
                                            val builder = kyo.Chunk.newBuilder[innerT]
                                            @tailrec def loop(count: Int): Unit =
                                                if reader.hasNextElement() then
                                                    reader.checkCollectionSize(count)
                                                    builder += inner.serializeRead(reader)
                                                    loop(count + 1)
                                            loop(1)
                                            reader.arrayEnd()
                                            builder.result()
                                        ,
                                        structure = kyo.Structure.Type.Collection("Chunk", kyo.Tag[Any], inner.structure)
                                    ).asInstanceOf[Schema[Any]]
                                }
                        else if containerSym == TypeRepr.of[List].typeSymbol then
                            (self: Expr[Schema[A]]) =>
                                val innerSchema = innerResolver(self)
                                '{
                                    val inner = $innerSchema.asInstanceOf[Schema[innerT]]
                                    kyo.Schema.init[List[innerT]](
                                        writeFn = (value, writer) =>
                                            writer.arrayStart(value.size)
                                            value.foreach(inner.serializeWrite(_, writer))
                                            writer.arrayEnd()
                                        ,
                                        readFn = reader =>
                                            import scala.annotation.tailrec
                                            discard(reader.arrayStart())
                                            val builder = List.newBuilder[innerT]
                                            @tailrec def loop(count: Int): Unit =
                                                if reader.hasNextElement() then
                                                    reader.checkCollectionSize(count)
                                                    builder += inner.serializeRead(reader)
                                                    loop(count + 1)
                                            loop(1)
                                            reader.arrayEnd()
                                            builder.result()
                                        ,
                                        structure = kyo.Structure.Type.Collection("List", kyo.Tag[Any], inner.structure)
                                    ).asInstanceOf[Schema[Any]]
                                }
                        else if containerSym == TypeRepr.of[Vector[Any]].typeSymbol then
                            (self: Expr[Schema[A]]) =>
                                val innerSchema = innerResolver(self)
                                '{
                                    val inner = $innerSchema.asInstanceOf[Schema[innerT]]
                                    kyo.Schema.init[Vector[innerT]](
                                        writeFn = (value, writer) =>
                                            writer.arrayStart(value.size)
                                            value.foreach(inner.serializeWrite(_, writer))
                                            writer.arrayEnd()
                                        ,
                                        readFn = reader =>
                                            import scala.annotation.tailrec
                                            discard(reader.arrayStart())
                                            val builder = Vector.newBuilder[innerT]
                                            @tailrec def loop(count: Int): Unit =
                                                if reader.hasNextElement() then
                                                    reader.checkCollectionSize(count)
                                                    builder += inner.serializeRead(reader)
                                                    loop(count + 1)
                                            loop(1)
                                            reader.arrayEnd()
                                            builder.result()
                                        ,
                                        structure = kyo.Structure.Type.Collection("Vector", kyo.Tag[Any], inner.structure)
                                    ).asInstanceOf[Schema[Any]]
                                }
                        else if containerSym == TypeRepr.of[Set[Any]].typeSymbol then
                            (self: Expr[Schema[A]]) =>
                                val innerSchema = innerResolver(self)
                                '{
                                    val inner = $innerSchema.asInstanceOf[Schema[innerT]]
                                    kyo.Schema.init[Set[innerT]](
                                        writeFn = (value, writer) =>
                                            writer.arrayStart(value.size)
                                            value.foreach(inner.serializeWrite(_, writer))
                                            writer.arrayEnd()
                                        ,
                                        readFn = reader =>
                                            import scala.annotation.tailrec
                                            discard(reader.arrayStart())
                                            val builder = Set.newBuilder[innerT]
                                            @tailrec def loop(count: Int): Unit =
                                                if reader.hasNextElement() then
                                                    reader.checkCollectionSize(count)
                                                    builder += inner.serializeRead(reader)
                                                    loop(count + 1)
                                            loop(1)
                                            reader.arrayEnd()
                                            builder.result()
                                        ,
                                        structure = kyo.Structure.Type.Collection("Set", kyo.Tag[Any], inner.structure)
                                    ).asInstanceOf[Schema[Any]]
                                }
                        else if containerSym == TypeRepr.of[Seq[Any]].typeSymbol then
                            (self: Expr[Schema[A]]) =>
                                val innerSchema = innerResolver(self)
                                '{
                                    val inner = $innerSchema.asInstanceOf[Schema[innerT]]
                                    kyo.Schema.init[Seq[innerT]](
                                        writeFn = (value, writer) =>
                                            writer.arrayStart(value.size)
                                            value.foreach(inner.serializeWrite(_, writer))
                                            writer.arrayEnd()
                                        ,
                                        readFn = reader =>
                                            import scala.annotation.tailrec
                                            discard(reader.arrayStart())
                                            val builder = List.newBuilder[innerT]
                                            @tailrec def loop(): Unit =
                                                if reader.hasNextElement() then
                                                    builder += inner.serializeRead(reader)
                                                    loop()
                                            loop()
                                            reader.arrayEnd()
                                            builder.result()
                                        ,
                                        structure = kyo.Structure.Type.Collection("Seq", kyo.Tag[Any], inner.structure)
                                    ).asInstanceOf[Schema[Any]]
                                }
                        else if containerSym == TypeRepr.of[Option[Any]].typeSymbol then
                            (self: Expr[Schema[A]]) =>
                                val innerSchema = innerResolver(self)
                                '{
                                    val inner = $innerSchema.asInstanceOf[Schema[innerT]]
                                    kyo.Schema.init[Option[innerT]](
                                        writeFn = (value, writer) =>
                                            value match
                                                case Some(v) => inner.serializeWrite(v, writer)
                                                case None =>
                                                    writer.nil()
                                        ,
                                        readFn = reader =>
                                            if reader.isNil() then None
                                            else Some(inner.serializeRead(reader)),
                                        structure = kyo.Structure.Type.Optional("Option", kyo.Tag[Any], inner.structure)
                                    ).asInstanceOf[Schema[Any]]
                                }
                        else if containerSym == TypeRepr.of[kyo.Maybe[Any]].typeSymbol then
                            (self: Expr[Schema[A]]) =>
                                val innerSchema = innerResolver(self)
                                '{
                                    val inner = $innerSchema.asInstanceOf[Schema[innerT]]
                                    kyo.Schema.init[kyo.Maybe[innerT]](
                                        writeFn = (value, writer) =>
                                            value match
                                                case kyo.Maybe.Present(v) => inner.serializeWrite(v, writer)
                                                case _ =>
                                                    writer.nil()
                                        ,
                                        readFn = reader =>
                                            if reader.isNil() then kyo.Maybe.empty
                                            else kyo.Maybe(inner.serializeRead(reader)),
                                        structure = kyo.Structure.Type.Optional("Maybe", kyo.Tag[Any], inner.structure)
                                    ).asInstanceOf[Schema[Any]]
                                }
                        else
                            report.errorAndAbort(
                                s"Cannot derive Schema for field '$fieldName': recursive type in unsupported container ${tycon.show}. " +
                                    "Supported containers: List, Vector, Set, Seq, Chunk, Option, Maybe, Map[String, _]."
                            )
                        end if
                end match

            // Map[String, RecursiveType] -- e.g. Map[String, SchemaObject]
            case AppliedType(tycon, List(keyArg, valueArg))
                if tycon.typeSymbol == TypeRepr.of[Map[Any, Any]].typeSymbol
                    && keyArg.dealias =:= TypeRepr.of[String]
                    && containsRecursiveType(valueArg, parentType) =>
                val valueResolver = buildRecursiveResolver[A](valueArg, parentType, fieldName)
                valueArg.asType match
                    case '[vt] =>
                        (self: Expr[Schema[A]]) =>
                            val valueSchema = valueResolver(self)
                            '{
                                val inner = $valueSchema.asInstanceOf[Schema[vt]]
                                import scala.annotation.tailrec
                                kyo.Schema.init[Map[String, vt]](
                                    writeFn = (value, writer) =>
                                        writer.mapStart(value.size)
                                        value.iterator.zipWithIndex.foreach { case ((k, v), idx) =>
                                            writer.field(k, idx)
                                            inner.serializeWrite(v, writer)
                                        }
                                        writer.mapEnd()
                                    ,
                                    readFn = reader =>
                                        discard(reader.mapStart())
                                        val builder = Map.newBuilder[String, vt]
                                        @tailrec def loop(count: Int): Unit =
                                            if reader.hasNextEntry() then
                                                reader.checkCollectionSize(count)
                                                val k = reader.field()
                                                val v = inner.serializeRead(reader)
                                                builder += (k -> v)
                                                loop(count + 1)
                                        loop(1)
                                        reader.mapEnd()
                                        builder.result()
                                    ,
                                    structure = kyo.Structure.Type.Mapping(
                                        "Map",
                                        kyo.Tag[Any],
                                        kyo.Structure.Type.Primitive(
                                            kyo.Structure.PrimitiveKind.String,
                                            kyo.Tag[String].asInstanceOf[kyo.Tag[Any]]
                                        ),
                                        inner.structure
                                    )
                                ).asInstanceOf[Schema[Any]]
                            }
                end match

            // Tuple2[A, B] where one or both contain RecursiveType
            case AppliedType(tycon, List(arg1, arg2)) if tycon.typeSymbol == TypeRepr.of[Tuple2[Any, Any]].typeSymbol =>
                val r1ContainsRecursive = containsRecursiveType(arg1, parentType)
                val r2ContainsRecursive = containsRecursiveType(arg2, parentType)
                ((arg1.asType, arg2.asType): @unchecked) match
                    case ('[t1], '[t2]) =>
                        (r1ContainsRecursive, r2ContainsRecursive) match
                            case (true, true) =>
                                val resolver1 = buildRecursiveResolver[A](arg1, parentType, fieldName)
                                val resolver2 = buildRecursiveResolver[A](arg2, parentType, fieldName)
                                (self: Expr[Schema[A]]) =>
                                    val s1 = resolver1(self)
                                    val s2 = resolver2(self)
                                    '{
                                        kyo.Schema.tuple2Schema[t1, t2](using
                                            $s1.asInstanceOf[Schema[t1]],
                                            $s2.asInstanceOf[Schema[t2]]
                                        ).asInstanceOf[Schema[Any]]
                                    }
                            case (true, false) =>
                                val resolver1 = buildRecursiveResolver[A](arg1, parentType, fieldName)
                                val s2 = Expr.summon[Schema[t2]].getOrElse(
                                    report.errorAndAbort(noSchemaMessage(fieldName, arg2.show, fieldType.show))
                                )
                                (self: Expr[Schema[A]]) =>
                                    val s1 = resolver1(self)
                                    '{ kyo.Schema.tuple2Schema[t1, t2](using $s1.asInstanceOf[Schema[t1]], $s2).asInstanceOf[Schema[Any]] }
                            case (false, true) =>
                                val s1 = Expr.summon[Schema[t1]].getOrElse(
                                    report.errorAndAbort(noSchemaMessage(fieldName, arg1.show, fieldType.show))
                                )
                                val resolver2 = buildRecursiveResolver[A](arg2, parentType, fieldName)
                                (self: Expr[Schema[A]]) =>
                                    val s2 = resolver2(self)
                                    '{ kyo.Schema.tuple2Schema[t1, t2](using $s1, $s2.asInstanceOf[Schema[t2]]).asInstanceOf[Schema[Any]] }
                            case (false, false) =>
                                // Neither contains recursive type; shouldn't reach here
                                val schema = Expr.summon[Schema[(t1, t2)]].getOrElse(
                                    report.errorAndAbort(noSchemaMessage(fieldName, fieldType.show))
                                )
                                (_: Expr[Schema[A]]) => '{ $schema.asInstanceOf[Schema[Any]] }
                end match

            case _ =>
                report.errorAndAbort(
                    s"Cannot derive Schema for field '$fieldName' of type ${fieldType.show}: " +
                        "unsupported recursive type structure. " +
                        "Recursive references must be inside a supported container (List, Vector, Set, Seq, Chunk, Option, Maybe, Map[String, _]) or Tuple2."
                )
        end match
    end buildRecursiveResolver

    /** Summons Schema resolvers for each field of a case class.
      *
      * For recursive types, resolvers capture `self` when the field type equals the parent type or contains it inside a container
      * (List/Option/Maybe). For non-recursive types, resolvers ignore `self` and use a directly summoned Schema.
      *
      * Maybe fields always summon Schema for the inner type. Option and other fields summon Schema for the full field type.
      *
      * @return
      *   List of (fieldName, fieldIndex, schemaResolver) triples
      */
    private def summonFieldSchemaResolvers[A](using
        Quotes,
        Type[A]
    )(
        fields: List[quotes.reflect.Symbol],
        tpe: quotes.reflect.TypeRepr,
        maybeFields: Set[Int],
        optionFields: Set[Int],
        isRecursive: Boolean
    ): List[(String, Int, SerializationMacro.SchemaResolver[A])] =
        import quotes.reflect.*

        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val maybeSym                   = TypeRepr.of[kyo.Maybe[Any]].typeSymbol
        val optionSym                  = TypeRepr.of[Option[Any]].typeSymbol

        fields.zipWithIndex.map { (field, idx) =>
            val fieldType = tpe.memberType(field)
            val fieldName = field.name

            val resolver: SerializationMacro.SchemaResolver[A] = fieldType.asType match
                case '[t] =>
                    if isRecursive && fieldType =:= tpe then
                        (self: Expr[Schema[A]]) => '{ $self.asInstanceOf[Schema[Any]] }
                    else if isRecursive && containsRecursiveType(fieldType, tpe) then
                        // Field type contains the recursive type somewhere in its type arguments.
                        // Build a schema resolver that constructs the field schema using `self`.
                        buildRecursiveResolver[A](fieldType, tpe, fieldName)
                    else if isRecursive then
                        fieldType.dealias match
                            // Maybe[X] field with non-recursive inner type - extract inner type X and summon Schema[X]
                            case AppliedType(tycon2, List(innerType2)) if tycon2.typeSymbol == maybeSym =>
                                innerType2.asType match
                                    case '[inner] =>
                                        val schema = Expr.summon[Schema[inner]].getOrElse(
                                            report.errorAndAbort(noSchemaMessage(fieldName, innerType2.show, fieldType.show))
                                        )
                                        (_: Expr[Schema[A]]) => '{ $schema.asInstanceOf[Schema[Any]] }
                            // For Option and other types: try direct summon, then fall back to
                            // explicit container schema construction for types that require Frame.
                            case _ =>
                                val schemaExpr: Expr[Schema[Any]] =
                                    Expr.summon[Schema[t]]
                                        .map(s => '{ $s.asInstanceOf[Schema[Any]] })
                                        .orElse(buildContainerSchemaOpt[A](fieldType, fieldName))
                                        .getOrElse(report.errorAndAbort(noSchemaFieldMessage(fieldName, fieldType)))
                                (_: Expr[Schema[A]]) => schemaExpr
                    else if maybeFields.contains(idx) then
                        // Non-recursive Maybe field - extract inner type and summon Schema for it.
                        // The implicit search falls back to `buildContainerSchemaOpt` for inner types
                        // whose givens require `using Frame` (e.g. nested Chunk[X], Map[String, V]),
                        // matching the non-Maybe fallback below.
                        fieldType.dealias match
                            case AppliedType(_, List(innerType)) =>
                                innerType.asType match
                                    case '[inner] =>
                                        val schemaExpr: Expr[Schema[Any]] =
                                            Expr.summon[Schema[inner]]
                                                .map(s => '{ $s.asInstanceOf[Schema[Any]] })
                                                .orElse(buildContainerSchemaOpt[A](innerType, fieldName))
                                                .getOrElse(
                                                    report.errorAndAbort(noSchemaMessage(fieldName, innerType.show, fieldType.show))
                                                )
                                        (_: Expr[Schema[A]]) => schemaExpr
                            case _ =>
                                report.errorAndAbort(
                                    s"Cannot resolve inner type for Maybe field '$fieldName'. " +
                                        "Ensure the field is declared as Maybe[T] where T is a concrete type with a given Schema."
                                )
                    else
                        // Non-recursive, non-Maybe field: try direct summon, then fall back to
                        // explicit container schema construction for types that require Frame.
                        val schemaExpr: Expr[Schema[Any]] =
                            Expr.summon[Schema[t]]
                                .map(s => '{ $s.asInstanceOf[Schema[Any]] })
                                .orElse(buildContainerSchemaOpt[A](fieldType, fieldName))
                                .getOrElse(report.errorAndAbort(noSchemaFieldMessage(fieldName, fieldType)))
                        (_: Expr[Schema[A]]) => schemaExpr
                    end if

            (fieldName, idx, resolver)
        }
    end summonFieldSchemaResolvers

    /** Builds an `Expr[Array[kyo.Schema[Any]]]` of length `needsSubSchema.size` where slot `i` holds the resolved sub-schema for field `i`,
      * or `null` when the emitted lambda body uses a specialization that does not consult the slot (primitive / primitive-element container
      * / Result[primitive, primitive]).
      *
      * The returned Array is allocated once per Schema materialization and closed over by the write/read lambdas.
      */
    private def buildSubSchemasArrayExpr[A](using
        Quotes,
        Type[A]
    )(
        needsSubSchema: List[Boolean],
        resolvers: List[SerializationMacro.SchemaResolver[A]],
        selfSchema: Expr[Schema[A]]
    ): Expr[Array[kyo.Schema[Any]]] =
        import quotes.reflect.*
        val slotExprs: List[Expr[kyo.Schema[Any]]] =
            needsSubSchema.zipWithIndex.map { (needs, idx) =>
                if needs then resolvers(idx)(selfSchema)
                else '{ null.asInstanceOf[kyo.Schema[Any]] }
            }
        '{ Array[kyo.Schema[Any]](${ Varargs(slotExprs) }*) }
    end buildSubSchemasArrayExpr

    /** Variant of [[buildSubSchemasArrayExpr]] used when the macro already has each field's resolved sub-schema `Expr` in hand (the
      * sealed-trait variant closure pre-resolves via `self`). Slot contents follow the same primitive-vs-generic rule.
      */
    private def buildSubSchemasArrayFromResolved(using
        Quotes
    )(
        needsSubSchema: List[Boolean],
        resolvedSchemas: List[Expr[kyo.Schema[Any]]]
    ): Expr[Array[kyo.Schema[Any]]] =
        import quotes.reflect.*
        val slotExprs: List[Expr[kyo.Schema[Any]]] =
            needsSubSchema.zipWithIndex.map { (needs, idx) =>
                if needs then resolvedSchemas(idx)
                else
                    '{ null.asInstanceOf[kyo.Schema[Any]] }
                end if
            }
        '{ Array[kyo.Schema[Any]](${ Varargs(slotExprs) }*) }
    end buildSubSchemasArrayFromResolved

    /** Macro implementation for unified `focus` that auto-detects sum vs product navigation.
      *
      * Extracts all field names from the lambda body, then re-resolves each through the structural type to check if any step crosses a sum
      * type (OrType). If so, returns `Focus[Root, Value, Maybe]`. Otherwise returns `Focus[Root, Value, Focus.Id]`.
      */
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

    /** Macro implementation for Focus.focus chaining.
      *
      * Inspects Mode at compile time to determine the outer mode, checks inner navigation for sum types, then selects the correct compose
      * factory per the Mode[_] lattice: Id < Maybe < Chunk.
      */
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

    /** Macro implementation for Focus.foreach chaining.
      *
      * Always produces Focus[Root, E, Chunk]. Selects compose factory based on outer Mode.
      */
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

    /** Extracts all field names from a Focus lambda body in navigation order.
      *
      * The lambda `_.shape.MTCircle.radius` contains nested selectDynamic calls: `selectDynamic("radius")` on `selectDynamic("MTCircle")`
      * on `selectDynamic("shape")`. This method collects all names in order: `["shape", "MTCircle", "radius"]`.
      */
    private def extractAllFocusFieldNames(using Quotes)(tree: quotes.reflect.Term): List[String] =
        import quotes.reflect.*

        def extract(term: Tree): List[String] =
            term match
                // selectDynamic("name"): this is the key pattern
                case Apply(TypeApply(Select(receiver, "selectDynamic"), _), List(Literal(StringConstant(name)))) =>
                    extract(receiver) :+ name
                case Apply(Select(receiver, "selectDynamic"), List(Literal(StringConstant(name)))) =>
                    extract(receiver) :+ name

                // Inlined node wrapping a selectDynamic expansion
                case Inlined(Some(call), _, body) =>
                    val fromCall = extract(call)
                    if fromCall.nonEmpty then fromCall
                    else extract(body)
                case Inlined(None, _, body) =>
                    extract(body)

                // Lambda body
                case Lambda(_, body) =>
                    extract(body)

                // Block: recurse into final expr
                case Block(stats, expr) =>
                    extract(expr)

                case Typed(inner, _) =>
                    extract(inner)

                case _ => Nil
            end match
        end extract

        extract(tree)
    end extractAllFocusFieldNames

    /** Checks whether any step in the navigation path crosses a sum type.
      *
      * Starting from the root Focus type F, resolves each field name in sequence. If any resolution finds the field via an OrType (sum
      * variant), returns true.
      */
    private def checkSumNavigation[F: Type](using Quotes)(fieldNames: List[String]): Boolean =
        import quotes.reflect.*

        var currentType = ExpandMacro.expandType(TypeRepr.of[F])
        var foundSum    = false

        for name <- fieldNames do
            NavigationMacro.classifyField(currentType, name) match
                case Some((valueType, isSum)) =>
                    if isSum then foundSum = true
                    // Expand the value type for the next step (it may be a case class/sealed trait)
                    currentType = ExpandMacro.expandType(valueType)
                case None =>
                    // Field not found; will be caught by selectDynamic macro
                    ()
        end for

        foundSum
    end checkSumNavigation

    /** Like checkSumNavigation but takes a TypeRepr directly instead of a type parameter.
      *
      * Used by focusChainImpl where V is known as a TypeRepr (already expanded).
      */
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

    /** Implementation for Schema[A, F].defaults.
      *
      * Inspects the nominal type (derived from A and F) for case class fields with default values. Returns a Record containing only those
      * fields that have defaults, with their default values populated.
      *
      * For non-case-class types or case classes with no defaults, returns an empty Record[Any].
      */
    def defaultsImpl[A: Type, F: Type](using Quotes): Expr[Any] =
        import quotes.reflect.*

        val nominalType = MacroUtils.deriveNominalType[A, F]
        val sym         = nominalType.typeSymbol

        if !sym.isClassDef || !sym.flags.is(Flags.Case) then
            // Non-case-class: return empty Record
            '{ new Record[Any](Dict.empty[String, Any]) }
        else

            val tildeType = TypeRepr.of[Record.~]

            // Collect fields with defaults
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

                // Build the Record type: intersection of "name" ~ Type for each field with a default
                val recordType = fieldsWithDefaults.map(_._2).reduce(AndType(_, _))

                // Build the Dict at runtime from default values
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

    /** Tries to build a Schema expression for a container type that requires Frame.
      *
      * Collection and optional givens (listSchema, vectorSchema, setSchema, chunkSchema, seqSchema,
      * maybeSchema, optionSchema) require `using frame: Frame`. During macro-driven Schema derivation,
      * `Expr.summon[Schema[Container[X]]]` cannot satisfy the Frame requirement because Frame.derive
      * is blocked inside the kyo package and no ambient Frame exists at compile time.
      *
      * This helper builds container schemas using Schema.init with explicit structure arguments.
      * The resulting schemas are used only for field serialization in the derived-schema path;
      * their structure reflects the container type (Collection/Optional/Mapping) with the inner
      * schema's structure as the element type.
      *
      * Returns `Some(expr)` if the type is a recognized single-arg container with a resolvable inner
      * Schema; `None` otherwise.
      */
    /** Builds a Schema[Any] for a container type (List, Vector, Set, Seq, Chunk, Maybe, Option) by
      * constructing the schema inline via Schema.init with an explicit structure parameter.
      * This avoids the `using frame: Frame` requirement on the public container givens, which cannot
      * be satisfied from inside a macro expansion in the kyo package.
      * Returns None if the field type is not a supported container or the inner type has no Schema.
      */
    private def buildContainerSchemaOpt[A](using
        Quotes
    )(
        fieldType: quotes.reflect.TypeRepr,
        fieldName: String
    ): Option[Expr[Schema[Any]]] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val listSym                    = TypeRepr.of[List].typeSymbol
        val vectorSym                  = TypeRepr.of[Vector[Any]].typeSymbol
        val setSym                     = TypeRepr.of[Set[Any]].typeSymbol
        val seqSym                     = TypeRepr.of[Seq[Any]].typeSymbol
        val chunkSym                   = TypeRepr.of[kyo.Chunk[Any]].typeSymbol
        val maybeSym                   = TypeRepr.of[kyo.Maybe[Any]].typeSymbol
        val optionSym                  = TypeRepr.of[Option[Any]].typeSymbol

        fieldType.dealias match
            case AppliedType(tycon, List(innerType)) =>
                val sym = tycon.typeSymbol
                if sym == listSym || sym == vectorSym || sym == setSym ||
                    sym == seqSym || sym == chunkSym || sym == maybeSym || sym == optionSym
                then
                    innerType.asType match
                        case '[inner] =>
                            val innerSchemaOpt: Option[Expr[Schema[inner]]] =
                                Expr.summon[Schema[inner]].orElse(
                                    buildContainerSchemaOpt[A](innerType, fieldName)
                                        .map(anyExpr => '{ $anyExpr.asInstanceOf[Schema[inner]] })
                                )
                            innerSchemaOpt.map { innerSchema =>
                                if sym == listSym then
                                    '{
                                        val is = $innerSchema
                                        import scala.annotation.tailrec
                                        kyo.Schema.init[List[inner]](
                                            writeFn = (value, writer) =>
                                                writer.arrayStart(value.size)
                                                value.foreach(is.serializeWrite(_, writer))
                                                writer.arrayEnd()
                                            ,
                                            readFn = reader =>
                                                discard(reader.arrayStart())
                                                val builder = List.newBuilder[inner]
                                                @tailrec def loop(count: Int): Unit =
                                                    if reader.hasNextElement() then
                                                        reader.checkCollectionSize(count)
                                                        builder += is.serializeRead(reader)
                                                        loop(count + 1)
                                                loop(1)
                                                reader.arrayEnd()
                                                builder.result()
                                            ,
                                            structure = kyo.Structure.Type.Collection("List", kyo.Tag[Any], is.structure)
                                        ).asInstanceOf[Schema[Any]]
                                    }
                                else if sym == vectorSym then
                                    '{
                                        val is = $innerSchema
                                        import scala.annotation.tailrec
                                        kyo.Schema.init[Vector[inner]](
                                            writeFn = (value, writer) =>
                                                writer.arrayStart(value.size)
                                                value.foreach(is.serializeWrite(_, writer))
                                                writer.arrayEnd()
                                            ,
                                            readFn = reader =>
                                                discard(reader.arrayStart())
                                                val builder = Vector.newBuilder[inner]
                                                @tailrec def loop(count: Int): Unit =
                                                    if reader.hasNextElement() then
                                                        reader.checkCollectionSize(count)
                                                        builder += is.serializeRead(reader)
                                                        loop(count + 1)
                                                loop(1)
                                                reader.arrayEnd()
                                                builder.result()
                                            ,
                                            structure = kyo.Structure.Type.Collection("Vector", kyo.Tag[Any], is.structure)
                                        ).asInstanceOf[Schema[Any]]
                                    }
                                else if sym == setSym then
                                    '{
                                        val is = $innerSchema
                                        import scala.annotation.tailrec
                                        kyo.Schema.init[Set[inner]](
                                            writeFn = (value, writer) =>
                                                writer.arrayStart(value.size)
                                                value.foreach(is.serializeWrite(_, writer))
                                                writer.arrayEnd()
                                            ,
                                            readFn = reader =>
                                                discard(reader.arrayStart())
                                                val builder = Set.newBuilder[inner]
                                                @tailrec def loop(count: Int): Unit =
                                                    if reader.hasNextElement() then
                                                        reader.checkCollectionSize(count)
                                                        builder += is.serializeRead(reader)
                                                        loop(count + 1)
                                                loop(1)
                                                reader.arrayEnd()
                                                builder.result()
                                            ,
                                            structure = kyo.Structure.Type.Collection("Set", kyo.Tag[Any], is.structure)
                                        ).asInstanceOf[Schema[Any]]
                                    }
                                else if sym == seqSym then
                                    '{
                                        val is = $innerSchema
                                        import scala.annotation.tailrec
                                        kyo.Schema.init[Seq[inner]](
                                            writeFn = (value, writer) =>
                                                writer.arrayStart(value.size)
                                                value.foreach(is.serializeWrite(_, writer))
                                                writer.arrayEnd()
                                            ,
                                            readFn = reader =>
                                                discard(reader.arrayStart())
                                                val builder = List.newBuilder[inner]
                                                @tailrec def loop(): Unit =
                                                    if reader.hasNextElement() then
                                                        builder += is.serializeRead(reader)
                                                        loop()
                                                loop()
                                                reader.arrayEnd()
                                                builder.result()
                                            ,
                                            structure = kyo.Structure.Type.Collection("Seq", kyo.Tag[Any], is.structure)
                                        ).asInstanceOf[Schema[Any]]
                                    }
                                else if sym == chunkSym then
                                    '{
                                        val is = $innerSchema
                                        import scala.annotation.tailrec
                                        kyo.Schema.init[kyo.Chunk[inner]](
                                            writeFn = (value, writer) =>
                                                writer.arrayStart(value.size)
                                                value.foreach(is.serializeWrite(_, writer))
                                                writer.arrayEnd()
                                            ,
                                            readFn = reader =>
                                                discard(reader.arrayStart())
                                                val builder = kyo.Chunk.newBuilder[inner]
                                                @tailrec def loop(count: Int): Unit =
                                                    if reader.hasNextElement() then
                                                        reader.checkCollectionSize(count)
                                                        builder += is.serializeRead(reader)
                                                        loop(count + 1)
                                                loop(1)
                                                reader.arrayEnd()
                                                builder.result()
                                            ,
                                            structure = kyo.Structure.Type.Collection("Chunk", kyo.Tag[Any], is.structure)
                                        ).asInstanceOf[Schema[Any]]
                                    }
                                else if sym == maybeSym then
                                    '{
                                        val is = $innerSchema
                                        kyo.Schema.init[kyo.Maybe[inner]](
                                            writeFn = (value, writer) =>
                                                value match
                                                    case kyo.Maybe.Present(v) => is.serializeWrite(v, writer)
                                                    case _ =>
                                                        writer.nil()
                                            ,
                                            readFn = reader =>
                                                if reader.isNil() then kyo.Maybe.empty
                                                else kyo.Maybe(is.serializeRead(reader)),
                                            structure = kyo.Structure.Type.Optional("Maybe", kyo.Tag[Any], is.structure)
                                        ).asInstanceOf[Schema[Any]]
                                    }
                                else
                                    '{
                                        val is = $innerSchema
                                        kyo.Schema.init[Option[inner]](
                                            writeFn = (value, writer) =>
                                                value match
                                                    case Some(v) => is.serializeWrite(v, writer)
                                                    case None =>
                                                        writer.nil()
                                            ,
                                            readFn = reader =>
                                                if reader.isNil() then None
                                                else Some(is.serializeRead(reader)),
                                            structure = kyo.Structure.Type.Optional("Option", kyo.Tag[Any], is.structure)
                                        ).asInstanceOf[Schema[Any]]
                                    }
                                end if
                            }
                else None
                end if
            case AppliedType(tycon, List(keyType, valueType)) if tycon.typeSymbol == TypeRepr.of[Map].typeSymbol =>
                // Map[K, V] shape. The non-inline mapSchema given in Schema.scala requires `using Frame`,
                // which the macro-time Expr.summon cannot satisfy at user `derives Schema` call sites
                // inside the kyo package. Build the Schema explicitly for Map[String, V] (the wire shape
                // is an object whose keys are the Map keys). Non-String key types fall through to None.
                if keyType =:= TypeRepr.of[String] then
                    valueType.asType match
                        case '[v] =>
                            val valueSchemaOpt: Option[Expr[Schema[v]]] =
                                Expr.summon[Schema[v]].orElse(
                                    buildContainerSchemaOpt[A](valueType, fieldName)
                                        .map(anyExpr => '{ $anyExpr.asInstanceOf[Schema[v]] })
                                )
                            valueSchemaOpt.map { valueSchema =>
                                '{
                                    val vs = $valueSchema
                                    import scala.annotation.tailrec
                                    kyo.Schema.init[Map[String, v]](
                                        writeFn = (value, writer) =>
                                            writer.mapStart(value.size)
                                            value.iterator.zipWithIndex.foreach { case ((k, vv), idx) =>
                                                writer.field(k, idx)
                                                vs.serializeWrite(vv, writer)
                                            }
                                            writer.mapEnd()
                                        ,
                                        readFn = reader =>
                                            discard(reader.mapStart())
                                            val builder = Map.newBuilder[String, v]
                                            @tailrec def loop(count: Int): Unit =
                                                if reader.hasNextEntry() then
                                                    reader.checkCollectionSize(count)
                                                    val k  = reader.field()
                                                    val vv = vs.serializeRead(reader)
                                                    builder += (k -> vv)
                                                    loop(count + 1)
                                            loop(1)
                                            reader.mapEnd()
                                            builder.result()
                                        ,
                                        structure = kyo.Structure.Type.Mapping(
                                            "Map",
                                            kyo.Tag[Any],
                                            kyo.Structure.Type.Primitive(
                                                kyo.Structure.PrimitiveKind.String,
                                                kyo.Tag[String].asInstanceOf[kyo.Tag[Any]]
                                            ),
                                            vs.structure
                                        )
                                    ).asInstanceOf[Schema[Any]]
                                }
                            }
                else None
                end if
            case _ => None
        end match
    end buildContainerSchemaOpt

    /** Builds a consistent error message for missing Schema instances during derivation.
      *
      * For applied types (List[X], Option[X], Map[K,V], etc.) where the real issue is an unsupported type argument, identifies which type
      * argument has no Schema by trying to summon each one. Also tries buildContainerSchemaOpt to avoid false-positive "missing schema"
      * diagnostics for container types that require Frame.
      */
    private def noSchemaFieldMessage(using Quotes)(fieldName: String, fieldType: quotes.reflect.TypeRepr): String =
        import quotes.reflect.*
        fieldType.dealias match
            case AppliedType(_, args) if args.nonEmpty =>
                // Find the first type argument that has no Schema (via direct summon or container fallback)
                val unsupported = args.find { arg =>
                    arg.asType match
                        case '[t] =>
                            Expr.summon[Schema[t]].isEmpty &&
                            buildContainerSchemaOpt[Any](arg, fieldName).isEmpty
                }
                unsupported match
                    case Some(inner) =>
                        noSchemaMessage(fieldName, inner.show, fieldType.show)
                    case None =>
                        noSchemaMessage(fieldName, fieldType.show)
                end match
            case _ =>
                noSchemaMessage(fieldName, fieldType.show)
        end match
    end noSchemaFieldMessage

    /** Builds a consistent error message for missing Schema instances during derivation. */
    private def noSchemaMessage(fieldName: String, missingType: String): String =
        s"No given Schema[${missingType}] for field '$fieldName'. " +
            s"Define ${missingType} as a case class or sealed trait, or provide a given Schema[${missingType}]."

    /** Overload for wrapped types where the missing type differs from the field type. */
    private def noSchemaMessage(fieldName: String, missingType: String, fieldType: String): String =
        s"No given Schema[${missingType}] for field '$fieldName' (field type: ${fieldType}). " +
            s"Define ${missingType} as a case class or sealed trait, or provide a given Schema[${missingType}]."

    /** Summons `Tag[A].asInstanceOf[Tag[Any]]`, falling back to `Tag[Any]` for abstract or tag-less types.
      *
      * Mirrors the summonTag helper in StructureMacro to handle generic type parameters (e.g. `Changeset[A]`) that have a `Schema[A]` constraint but not
      * necessarily `Tag[A]`.
      */
    private def summonStructureTag[A: Type](using Quotes): Expr[Tag[Any]] =
        import quotes.reflect.*
        Expr.summon[Tag[A]] match
            case Some(tagExpr) => '{ $tagExpr.asInstanceOf[Tag[Any]] }
            case None          => '{ Tag[Any] }
    end summonStructureTag

    /** Walks a type to detect a transitive cycle back to `target`.
      *
      * Returns true if `tpe` is `target`, or if `tpe`'s case-class field graph (including via containers/maps/tuples
      * and sealed trait children) transitively contains `target`. Used as a gate before deciding whether to summon
      * `Schema[ft].structure` (no cycle, preserves INV-6 reference equality) or to inline the structure via
      * `deriveTypeFallback` (cycle, breaks the runtime loop with an empty Product/Sum placeholder).
      *
      * `seen` accumulates the fully-qualified names of types already visited on the current walk to prevent
      * an infinite loop in the cycle-detection itself.
      */
    private def cyclesBackTo(using
        Quotes
    )(
        tpe: quotes.reflect.TypeRepr,
        target: quotes.reflect.TypeRepr,
        seen: Set[String]
    ): Boolean =
        import quotes.reflect.*
        val dealiased = tpe.dealias
        if dealiased =:= target then true
        else
            val sym      = dealiased.typeSymbol
            val fullName = sym.fullName
            val argsCycle = dealiased match
                case AppliedType(_, args) => args.exists(a => cyclesBackTo(a, target, seen))
                case _                    => false
            if argsCycle then true
            else if seen.contains(fullName) then false
            else if sym.isClassDef && (sym.flags.is(Flags.Case) || sym.flags.is(Flags.Sealed)) then
                val newSeen = seen + fullName
                if sym.flags.is(Flags.Sealed) then
                    sym.children.exists { child =>
                        val childType =
                            if child.isType then child.typeRef
                            else if child.flags.is(Flags.Module) then child.termRef.widen
                            else child.typeRef
                        cyclesBackTo(childType, target, newSeen)
                    }
                else
                    sym.caseFields.exists { field =>
                        val fieldType = dealiased.memberType(field)
                        cyclesBackTo(fieldType, target, newSeen)
                    }
                end if
            else false
            end if
        end if
    end cyclesBackTo

    /** Walks the type tree at compile time to produce a `Structure.Type` literal.
      *
      * This is the inlined fallback used for cyclic fields: it carries the compile-time
      * type-tree-walk logic inline because StructureMacro no longer exists as a separate object.
      *
      * The four symbol sets (extendedPrimitiveSymbols, optionalSymbols,
      * collectionSymbols, mapSymbols) are inlined as local vals because there are no
      * shared MacroUtils-level defs for them.
      */
    private def deriveTypeFallback(using
        Quotes
    )(
        tpe: quotes.reflect.TypeRepr,
        seen: Set[String]
    ): Expr[Structure.Type] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived

        val extendedPrimitiveSymbolsLocal: Set[Symbol] = Set(
            TypeRepr.of[Int].typeSymbol,
            TypeRepr.of[String].typeSymbol,
            TypeRepr.of[Boolean].typeSymbol,
            TypeRepr.of[Double].typeSymbol,
            TypeRepr.of[Float].typeSymbol,
            TypeRepr.of[Long].typeSymbol,
            TypeRepr.of[Short].typeSymbol,
            TypeRepr.of[Byte].typeSymbol,
            TypeRepr.of[Char].typeSymbol,
            TypeRepr.of[Unit].typeSymbol,
            TypeRepr.of[BigDecimal].typeSymbol,
            TypeRepr.of[BigInt].typeSymbol,
            TypeRepr.of[java.math.BigDecimal].typeSymbol,
            TypeRepr.of[java.math.BigInteger].typeSymbol
        )
        val optionalSymbolsLocal: Set[Symbol] = Set(
            TypeRepr.of[Option].typeSymbol,
            TypeRepr.of[kyo.Maybe].typeSymbol
        )
        val collectionSymbolsLocal: Set[Symbol] = Set(
            TypeRepr.of[List].typeSymbol,
            TypeRepr.of[Seq].typeSymbol,
            TypeRepr.of[Vector].typeSymbol,
            TypeRepr.of[Set].typeSymbol,
            TypeRepr.of[kyo.Chunk].typeSymbol
        )
        val mapSymbolsLocal: Set[Symbol] = Set(TypeRepr.of[Map].typeSymbol)

        def isPrimLocal(t: TypeRepr): Boolean =
            extendedPrimitiveSymbolsLocal.contains(t.dealias.typeSymbol)

        def isOptLocal(tycon: TypeRepr): Boolean =
            optionalSymbolsLocal.contains(tycon.typeSymbol)

        def isColLocal(tycon: TypeRepr): Boolean =
            collectionSymbolsLocal.contains(tycon.typeSymbol)

        def isMapLocal(tycon: TypeRepr): Boolean =
            mapSymbolsLocal.contains(tycon.typeSymbol)

        def summonTagLocal[A: Type]: Expr[Tag[Any]] =
            Expr.summon[Tag[A]] match
                case Some(tagExpr) => '{ $tagExpr.asInstanceOf[Tag[Any]] }
                case None          => '{ Tag[Any] }

        def primitiveKindExprLocal(t: TypeRepr): Expr[Structure.PrimitiveKind] =
            val sym = t.dealias.typeSymbol
            if sym == TypeRepr.of[Int].typeSymbol then '{ Structure.PrimitiveKind.Int }
            else if sym == TypeRepr.of[Long].typeSymbol then '{ Structure.PrimitiveKind.Long }
            else if sym == TypeRepr.of[Short].typeSymbol then '{ Structure.PrimitiveKind.Short }
            else if sym == TypeRepr.of[Byte].typeSymbol then '{ Structure.PrimitiveKind.Byte }
            else if sym == TypeRepr.of[Char].typeSymbol then '{ Structure.PrimitiveKind.Char }
            else if sym == TypeRepr.of[Float].typeSymbol then '{ Structure.PrimitiveKind.Float }
            else if sym == TypeRepr.of[Double].typeSymbol then '{ Structure.PrimitiveKind.Double }
            else if sym == TypeRepr.of[String].typeSymbol then '{ Structure.PrimitiveKind.String }
            else if sym == TypeRepr.of[Boolean].typeSymbol then '{ Structure.PrimitiveKind.Boolean }
            else if sym == TypeRepr.of[BigInt].typeSymbol || sym == TypeRepr.of[java.math.BigInteger].typeSymbol then
                '{ Structure.PrimitiveKind.BigInt }
            else if sym == TypeRepr.of[BigDecimal].typeSymbol || sym == TypeRepr.of[java.math.BigDecimal].typeSymbol then
                '{ Structure.PrimitiveKind.BigDecimal }
            else if sym == TypeRepr.of[Unit].typeSymbol then '{ Structure.PrimitiveKind.Unit }
            else
                report.errorAndAbort(
                    s"No PrimitiveKind mapping for type: ${t.show}. " +
                        "Add a case to Structure.PrimitiveKind or remove the type from extended primitives."
                )
            end if
        end primitiveKindExprLocal

        def deriveCaseClassOrFallbackLocal(
            dealiased: TypeRepr,
            sym: Symbol,
            seenInner: Set[String]
        ): Expr[Structure.Type] =
            if sym.isClassDef && sym.flags.is(Flags.Case) then
                val name    = sym.name
                val newSeen = seenInner + sym.fullName
                val fieldExprs = sym.caseFields.zipWithIndex.map { (field, idx) =>
                    val fieldName = field.name
                    val fieldType = dealiased.memberType(field)
                    val fieldRef  = go(fieldType, newSeen)
                    val defaultExpr: Expr[Maybe[Structure.Value]] = MacroUtils.getDefault(dealiased, idx) match
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
                    val isOptional = isOptLocal(fieldType.dealias match
                        case AppliedType(tycon, _) => tycon
                        case _                     => fieldType)
                    '{ Structure.Field(${ Expr(fieldName) }, $fieldRef, Maybe.empty[String], $defaultExpr, ${ Expr(isOptional) }) }
                }
                val fieldsSeqExpr = Expr.ofSeq(fieldExprs)
                dealiased.asType match
                    case '[a] =>
                        val tagExpr = summonTagLocal[a]
                        '{ Structure.Type.Product(${ Expr(name) }, $tagExpr, kyo.Chunk.empty, kyo.Chunk.from($fieldsSeqExpr)) }
                end match
            else
                val name = dealiased.typeSymbol.name
                dealiased.asType match
                    case '[a] =>
                        val tagExpr = summonTagLocal[a]
                        '{ Structure.Type.Product(${ Expr(name) }, $tagExpr, kyo.Chunk.empty, kyo.Chunk.empty) }
                end match
            end if
        end deriveCaseClassOrFallbackLocal

        def go(t: TypeRepr, seenInner: Set[String]): Expr[Structure.Type] =
            val dealiased = t.dealias
            val typeName  = dealiased.typeSymbol.fullName
            if seenInner.contains(typeName) && typeName.nonEmpty then
                val name = dealiased.typeSymbol.name
                dealiased.asType match
                    case '[a] =>
                        val tagExpr = summonTagLocal[a]
                        '{ Structure.Type.Product(${ Expr(name) }, $tagExpr, kyo.Chunk.empty, kyo.Chunk.empty) }
                end match
            else if isPrimLocal(dealiased) then
                val kindExpr = primitiveKindExprLocal(dealiased)
                dealiased.asType match
                    case '[a] =>
                        val tagExpr = summonTagLocal[a]
                        '{ Structure.Type.Primitive($kindExpr, $tagExpr) }
                end match
            else
                dealiased match
                    case AppliedType(tycon, List(inner)) if isOptLocal(tycon) =>
                        val name     = dealiased.typeSymbol.name
                        val innerRef = go(inner, seenInner)
                        dealiased.asType match
                            case '[a] =>
                                val tagExpr = summonTagLocal[a]
                                '{ Structure.Type.Optional(${ Expr(name) }, $tagExpr, $innerRef) }
                        end match
                    case AppliedType(tycon, List(k, v)) if isMapLocal(tycon) =>
                        val name     = dealiased.typeSymbol.name
                        val keyRef   = go(k, seenInner)
                        val valueRef = go(v, seenInner)
                        dealiased.asType match
                            case '[a] =>
                                val tagExpr = summonTagLocal[a]
                                '{ Structure.Type.Mapping(${ Expr(name) }, $tagExpr, $keyRef, $valueRef) }
                        end match
                    case AppliedType(tycon, List(elem)) if isColLocal(tycon) =>
                        val name    = dealiased.typeSymbol.name
                        val elemRef = go(elem, seenInner)
                        dealiased.asType match
                            case '[a] =>
                                val tagExpr = summonTagLocal[a]
                                '{ Structure.Type.Collection(${ Expr(name) }, $tagExpr, $elemRef) }
                        end match
                    case _ =>
                        val sym = dealiased.typeSymbol
                        if sym.isClassDef && sym.flags.is(Flags.Sealed) then
                            val children = sym.children
                            if children.nonEmpty then
                                val name    = sym.name
                                val newSeen = seenInner + sym.fullName
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
                                    val variantRef = go(childType, newSeen)
                                    '{ Structure.Variant(${ Expr(childName) }, $variantRef) }
                                }
                                val variantsSeqExpr = Expr.ofSeq(variantExprs)
                                val enumValuesExpr  = Expr.ofSeq(enumValues.map(Expr(_)))
                                dealiased.asType match
                                    case '[a] =>
                                        val tagExpr = summonTagLocal[a]
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
                                deriveCaseClassOrFallbackLocal(dealiased, sym, seenInner)
                            end if
                        else
                            deriveCaseClassOrFallbackLocal(dealiased, sym, seenInner)
                        end if
                end match
            end if
        end go

        go(tpe, seen)
    end deriveTypeFallback

    /** Builds a `Structure.Type` expression for a field type.
      *
      * For non-recursive types: emits `${Schema[ft]}.structure` (the runtime value). This preserves
      * reference equality (INV-6): `Schema[Parent].structure.fields(i).fieldType eq Schema[FieldType].structure`.
      *
      * For recursive types (direct or mutual cycles back to `parentType`): emits a compile-time inlined
      * `Structure.Type` via `deriveTypeFallback`. The inline tree contains empty Product/Sum placeholders
      * at the recursion point, breaking the runtime cycle. INV-6 cannot hold inside a cycle (the inline tree
      * is a fresh allocation), but the cycle would otherwise infinite-recurse.
      */
    private def summonFieldStructure[A: Type](using
        Quotes
    )(
        ft: quotes.reflect.TypeRepr,
        parentType: quotes.reflect.TypeRepr,
        typeName: String
    ): Expr[Structure.Type] =
        import quotes.reflect.*
        val parentFullName = parentType.typeSymbol.fullName
        val seen           = if parentFullName.nonEmpty then Set(parentFullName) else Set.empty[String]
        if cyclesBackTo(ft, parentType, seen) then
            // Cycle: walk the type tree inline at compile time with cycle protection.
            deriveTypeFallback(ft, seen)
        else
            // Non-cyclic: use Schema[ft].structure (preserves reference equality per INV-6).
            ft.asType match
                case '[t] =>
                    Expr.summon[Schema[t]] match
                        case Some(schemaExpr) => '{ ${ schemaExpr }.structure }
                        case None             =>
                            // Soft fallback: emit Open when no explicit Schema is in scope (handles
                            // types derived via `inline given derived` that can't be expanded in a macro).
                            val tagExpr = summonStructureTag[t]
                            '{ Structure.Type.Open($tagExpr) }
        end if
    end summonFieldStructure

    /** Builds a `Structure.Type.Product` expression for a case class type `A`.
      *
      * Each field's `fieldType` is obtained by summoning `Schema[FieldType].structure`. Recursive fields (where the field type equals the
      * parent type) receive `Structure.Type.Open(Tag[A])` as a cycle-safe placeholder.
      * Captures default values via MacroUtils.getDefault
      * and marks Option/Maybe fields as optional.
      */
    private def buildCaseClassStructureExpr[A: Type](using
        Quotes
    )(
        tpe: quotes.reflect.TypeRepr,
        sym: quotes.reflect.Symbol,
        typeName: String,
        maybeFields: Set[Int],
        optionFields: Set[Int]
    ): Expr[Structure.Type] =
        import quotes.reflect.*
        val fields      = sym.caseFields
        val typeParamsT = tpe.typeArgs

        val fieldExprs: List[Expr[Structure.Field]] = fields.zipWithIndex.map { (field, idx) =>
            val rawFieldType = tpe.memberType(field)
            // For Maybe[T] fields: resolve the inner type T for the structure.
            val resolvedType =
                if maybeFields.contains(idx) then
                    rawFieldType.dealias match
                        case AppliedType(_, List(inner)) => inner
                        case _                           => rawFieldType
                else rawFieldType
            val fieldStructureExpr = summonFieldStructure[A](resolvedType, tpe, typeName)
            // Mark both Maybe and Option fields as optional.
            val isOptional = maybeFields.contains(idx) || optionFields.contains(idx)
            // Capture default values for fields that have them (matches StructureMacro default handling).
            val defaultExpr: Expr[kyo.Maybe[Structure.Value]] = MacroUtils.getDefault(tpe, idx) match
                case Some(defVal) =>
                    rawFieldType.asType match
                        case '[t] =>
                            Expr.summon[kyo.Tag[t]] match
                                case Some(tagExpr) =>
                                    '{ kyo.Maybe(Structure.Value.primitive[t]($defVal.asInstanceOf[t])(using $tagExpr)) }
                                case None =>
                                    '{ kyo.Maybe.empty[Structure.Value] }
                case None =>
                    '{ kyo.Maybe.empty[Structure.Value] }
            '{ Structure.Field(${ Expr(field.name) }, $fieldStructureExpr, kyo.Maybe.empty, $defaultExpr, ${ Expr(isOptional) }) }
        }

        val typeParamExprs: List[Expr[Structure.Type]] = typeParamsT.map { tp =>
            tp.asType match
                case '[t] =>
                    Expr.summon[Schema[t]] match
                        case Some(s) => '{ $s.structure }
                        case None =>
                            val tagExpr = summonStructureTag[t]
                            '{ Structure.Type.Open($tagExpr) }
        }

        val fieldsChunkExpr     = '{ kyo.Chunk.from(${ Expr.ofList(fieldExprs) }) }
        val typeParamsChunkExpr = '{ kyo.Chunk.from(${ Expr.ofList(typeParamExprs) }) }

        tpe.asType match
            case '[a] =>
                val tagExpr = summonStructureTag[a]
                '{ Structure.Type.Product(${ Expr(typeName) }, $tagExpr, $typeParamsChunkExpr, $fieldsChunkExpr) }
        end match
    end buildCaseClassStructureExpr

    /** Builds a `Structure.Type.Sum` expression for a sealed trait type `A`.
      *
      * Each variant's `variantType` is obtained by summoning `Schema[VariantType].structure`. Self-referential variants (where the variant
      * type equals the parent type) receive `Structure.Type.Open(Tag[A])` as a cycle-safe placeholder.
      */
    private def buildSealedTraitStructureExpr[A: Type](using
        Quotes
    )(
        tpe: quotes.reflect.TypeRepr,
        sym: quotes.reflect.Symbol,
        typeName: String,
        children: List[quotes.reflect.Symbol]
    ): Expr[Structure.Type] =
        import quotes.reflect.*

        val variantExprs: List[Expr[Structure.Variant]] = children.map { child =>
            val childName = child.name.stripSuffix("$")
            val childType =
                if child.isType then child.typeRef
                else if child.flags.is(Flags.Module) then child.termRef.widen
                else child.typeRef
            val variantStructureExpr = summonFieldStructure[A](childType, tpe, typeName)
            '{ Structure.Variant(${ Expr(childName) }, $variantStructureExpr) }
        }

        // Collect enum singleton names
        val enumValues: List[String] = children.collect {
            case child if child.flags.is(Flags.Module) || !child.isClassDef =>
                child.name.stripSuffix("$")
        }

        val variantsChunkExpr   = '{ kyo.Chunk.from(${ Expr.ofList(variantExprs) }) }
        val enumValuesChunkExpr = '{ kyo.Chunk.from(${ Expr.ofList(enumValues.map(Expr(_))) }) }

        tpe.asType match
            case '[a] =>
                val tagExpr = summonStructureTag[a]
                '{
                    Structure.Type.Sum(
                        ${ Expr(typeName) },
                        $tagExpr,
                        kyo.Chunk.empty,
                        $variantsChunkExpr,
                        $enumValuesChunkExpr
                    )
                }
        end match
    end buildSealedTraitStructureExpr

end FocusMacro
