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

    /** Captures the policy-surviving annotation instances on `sym` as an `Expr[Chunk[Any]]`.
      *
      * Markers (subtypes of `kyo.schema.SchemaAnnotation`) are always included; a non-marker is
      * included iff its FQN matches an `include` glob and no `exclude` glob of the summoned policy.
      * Each surviving annotation Term is reified via `reifyAnnotation`; a non-liftable INCLUDED term
      * is silently skipped (no `report.errorAndAbort`, no `report.info`), so a third-party annotation
      * the rail cannot lift is benign. Source-declaration order is preserved.
      *
      * For case-class fields, use `captureFieldAnnotations` instead: in Scala 3, user-placed
      * annotations live on the primary-constructor parameter symbol, not on the accessor-getter symbol
      * returned by `sym.caseFields`.
      */
    private def captureAnnotations(using Quotes)(sym: quotes.reflect.Symbol): Expr[Chunk[Any]] =
        import quotes.reflect.*
        val policy = summonAnnotationPolicy
        val kept: List[Expr[Any]] = sym.annotations.flatMap { term =>
            val fqn      = term.tpe.typeSymbol.fullName
            val isMarker = term.tpe <:< TypeRepr.of[kyo.schema.SchemaAnnotation]
            if isMarker || policyAdmits(policy, fqn) then reifyAnnotation(term)
            else None
        }
        '{ kyo.Chunk.from[Any](Array[Any](${ Varargs(kept) }*)) }
    end captureAnnotations

    /** Captures the policy-surviving annotation instances for a case-class field as an
      * `Expr[Chunk[Any]]`.
      *
      * In Scala 3, annotations placed on a case-class field (e.g. `@ann field: T`) are stored on
      * the primary-constructor parameter symbol, not on the accessor-getter symbol returned by
      * `sym.caseFields`. Additionally, `sym.annotations` returns constructor-parameter annotations
      * in reverse source order (rightmost first); this helper reverses them to restore
      * left-to-right declaration order before merging. Any accessor annotations whose type is not
      * already represented are appended after. Policy filtering and liftability semantics are
      * identical to `captureAnnotations`.
      */
    private def captureFieldAnnotations(using
        Quotes
    )(
        caseField: quotes.reflect.Symbol,
        typeSym: quotes.reflect.Symbol
    ): Expr[Chunk[Any]] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val policy                     = summonAnnotationPolicy
        val ctorParam                  = typeSym.primaryConstructor.paramSymss.flatten.find(_.name == caseField.name)
        val ctorAnnots                 = ctorParam.map(_.annotations.reverse).getOrElse(Nil)
        val accessorAnnots             = caseField.annotations
        val ctorTypeSyms               = ctorAnnots.map(_.tpe.typeSymbol)
        val mergedAnnots               = ctorAnnots ++ accessorAnnots.filterNot(a => ctorTypeSyms.contains(a.tpe.typeSymbol))
        val kept: List[Expr[Any]] = mergedAnnots.flatMap { term =>
            val fqn      = term.tpe.typeSymbol.fullName
            val isMarker = term.tpe <:< TypeRepr.of[kyo.schema.SchemaAnnotation]
            if isMarker || policyAdmits(policy, fqn) then reifyAnnotation(term)
            else None
        }
        '{ kyo.Chunk.from[Any](Array[Any](${ Varargs(kept) }*)) }
    end captureFieldAnnotations

    /** Summons the in-scope `AnnotationPolicy`, falling back to the companion `given default`.
      *
      * Evaluates the summoned expression at macro-expansion time by walking the tree: an
      * `AnnotationPolicy(...)` Apply is decoded field by field; a Ref or Select to a companion val
      * is followed via the symbol's DefDef/ValDef RHS. If a present given cannot be decoded as a
      * compile-time constant, expansion is aborted with a clear error.
      */
    private def summonAnnotationPolicy(using Quotes): kyo.schema.AnnotationPolicy =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived

        def extractStrings(term: Term): Option[List[String]] = term match
            case Inlined(_, _, inner)                                  => extractStrings(inner)
            case Select(_, "empty") | TypeApply(Select(_, "empty"), _) => Some(Nil)
            case Typed(inner, _)                                       => extractStrings(inner)
            case Repeated(elems, _) =>
                val ss = elems.flatMap { case Literal(StringConstant(s)) => Some(s); case _ => None }
                if ss.length == elems.length then Some(ss) else None
            case Apply(_, List(Typed(Repeated(elems, _), _))) =>
                val ss = elems.flatMap { case Literal(StringConstant(s)) => Some(s); case _ => None }
                if ss.length == elems.length then Some(ss) else None
            case Apply(_, args) =>
                val ss = args.flatMap { case Literal(StringConstant(s)) => Some(s); case _ => None }
                if ss.length == args.length then Some(ss) else None
            case _ => None

        def followRhs(sym: Symbol): Option[Term] =
            def fromTree(t: Tree): Option[Term] = t match
                case dd: DefDef => dd.rhs
                case vd: ValDef => vd.rhs
                case _          => None
            def searchOwnerBody: Option[Term] =
                try
                    sym.owner.tree match
                        case cd: ClassDef =>
                            cd.body.collectFirst {
                                case vd: ValDef if vd.symbol == sym => vd.rhs
                                case dd: DefDef if dd.symbol == sym => dd.rhs
                            }.flatten
                        case _ => None
                catch case _: Throwable => None
            try
                fromTree(sym.tree) match
                    case some @ Some(_) => some
                    case None           => searchOwnerBody
            catch case _: Throwable => searchOwnerBody
            end try
        end followRhs

        def decodePolicy(term: Term): Option[kyo.schema.AnnotationPolicy] = term match
            case Inlined(_, _, inner) => decodePolicy(inner)
            case Typed(inner, _)      => decodePolicy(inner)
            case Apply(_, args) if term.tpe <:< TypeRepr.of[kyo.schema.AnnotationPolicy] =>
                val named = args.collect { case NamedArg(n, t) => n -> t }.toMap
                val inc = named.get("include").flatMap(extractStrings)
                    .map(kyo.Chunk.from[String])
                    .getOrElse(kyo.schema.AnnotationPolicy.default.include)
                val exc = named.get("exclude").flatMap(extractStrings)
                    .map(kyo.Chunk.from[String])
                    .getOrElse(kyo.schema.AnnotationPolicy.default.exclude)
                Some(kyo.schema.AnnotationPolicy(inc, exc))
            case Block(_, last) => decodePolicy(last)
            case _ =>
                val sym      = term.symbol
                val ownerFqn = sym.owner.fullName
                if ownerFqn == "kyo.schema.AnnotationPolicy" || ownerFqn == "kyo.schema.AnnotationPolicy$" then
                    sym.name match
                        case "markersOnly" => Some(kyo.schema.AnnotationPolicy.markersOnly)
                        case "default"     => Some(kyo.schema.AnnotationPolicy.default)
                        case _             => followRhs(sym).flatMap(decodePolicy)
                else followRhs(sym).flatMap(decodePolicy)
                end if

        Expr.summon[kyo.schema.AnnotationPolicy] match
            case None => kyo.schema.AnnotationPolicy.default
            case Some(policyExpr) =>
                val term     = policyExpr.asTerm
                val givenSym = term.symbol
                val body     = followRhs(givenSym).getOrElse(term)
                decodePolicy(body) match
                    case Some(policy) => policy
                    case None =>
                        report.errorAndAbort(
                            "A custom AnnotationPolicy must be provided as an `inline given` so its value is " +
                                "readable at derivation time, e.g. `inline given AnnotationPolicy = AnnotationPolicy.markersOnly`. " +
                                "A plain `given` cannot be read by the derivation macro."
                        )
                end match
        end match
    end summonAnnotationPolicy

    /** True iff `fqn` matches some include glob AND no exclude glob (segment-wise `*` wildcard). */
    private def policyAdmits(policy: kyo.schema.AnnotationPolicy, fqn: String): Boolean =
        policy.include.exists(globMatches(_, fqn)) && !policy.exclude.exists(globMatches(_, fqn))

    /** Glob match: `"*"` matches any FQN; `"a.b.*"` matches `a.b` or any `a.b.X`; otherwise exact. */
    private def globMatches(glob: String, fqn: String): Boolean =
        if glob == "*" then true
        else if glob.endsWith(".*") then
            val prefix = glob.dropRight(2)
            fqn == prefix || fqn.startsWith(prefix + ".")
        else glob == fqn

    /** Reifies a stable annotation Term into a runtime `Expr[Any]`, or `None` when non-liftable.
      *
      * A term whose constructor args are all constant (literals, new-applied stable constructors,
      * stable module/val references) is lifted directly. A term containing method-call args or
      * closures is rejected: it cannot be safely embedded in the generated carrier without
      * surprising runtime evaluation semantics.
      */
    private def reifyAnnotation(using Quotes)(term: quotes.reflect.Term): Option[Expr[Any]] =
        import quotes.reflect.*
        def isConstantTerm(t: Term): Boolean = t match
            case Inlined(_, _, inner) => isConstantTerm(inner)
            case Literal(_)           => true
            case Typed(inner, _)      => isConstantTerm(inner)
            case NamedArg(_, inner)   => isConstantTerm(inner)
            case Repeated(elems, _)   => elems.forall(isConstantTerm)
            case New(_)               => true
            // Stable companion-apply: a case-class or enum-case factory (e.g., omit.When(pred))
            // over a stable qualifier and all-constant args. The applied symbol is a synthetic factory
            // method (not a ValDef/Module/Enum), so the general Apply case rejects it via
            // isConstantTerm(fn). Accept here when the result type is a case class or enum case and
            // the qualifier and all args are recursively constant.
            case Apply(Select(qual, _), args)
                if t.tpe.widen.typeSymbol.flags.is(Flags.Case) && isConstantTerm(qual) && args.forall(isConstantTerm) =>
                true
            case Apply(fn, args)          => isConstantTerm(fn) && args.forall(isConstantTerm)
            case TypeApply(fn, _)         => isConstantTerm(fn)
            case Select(New(_), "<init>") => true
            // Compiler-synthesized default-getter: e.g. Select(Ident("omit"), "$lessinit$greater$default$1").
            // These are produced when a constructor parameter with a default value is not supplied
            // at the call site; the name always contains "$default$". The generated accessor is
            // compile-time stable and asExpr lifts it correctly.
            case Select(_, name) if name.contains("$default$") => true
            case Select(_, _) => t.symbol.isValDef || t.symbol.flags.is(Flags.Module) || t.symbol.flags.is(Flags.Enum)
            case i: Ident     => i.symbol.isValDef || i.symbol.flags.is(Flags.Module) || i.symbol.flags.is(Flags.Enum)
            case _            => false
        if isConstantTerm(term) then
            try Some(term.asExpr)
            catch case _: Throwable => None
        else None
        end if
    end reifyAnnotation

    // ==========================================================================
    // Built-in SchemaAnnotation leaf desugar helpers
    // ==========================================================================

    /** Macro-local carrier for product desugar config. Fields map 1:1 onto Schema.init params.
      * Parameterized by the root case-class type A so that `fieldTransforms` carries the correct
      * `Schema.FieldTransform[A]` element type.
      */
    private case class ProductConfig[A](
        renamedFields: Expr[Chunk[(String, String)]],
        droppedFields: Expr[Set[String]],
        documentation: Expr[Maybe[String]],
        fieldDocs: Expr[Map[Seq[String], String]],
        variantNaming: Expr[Schema.VariantNaming],
        omitPolicies: Expr[Chunk[(String, Schema.OmitPolicy)]],
        omitNoneAll: Expr[Boolean],
        omitEmptyCollectionsAll: Expr[Boolean],
        fieldMaterializedDefaults: Expr[Chunk[(String, Structure.Value)]],
        fieldTransforms: Expr[Chunk[(String, Schema.FieldTransform[A])]],
        fieldIdOverrides: Expr[Map[Seq[String], Int]]
    )

    private object ProductConfig:
        def empty[A: Type](using Quotes): ProductConfig[A] = ProductConfig[A](
            renamedFields = '{ kyo.Chunk.empty[(String, String)] },
            droppedFields = '{ Set.empty[String] },
            documentation = '{ kyo.Maybe.empty[String] },
            fieldDocs = '{ Map.empty[Seq[String], String] },
            variantNaming = '{ kyo.Schema.VariantNaming() },
            omitPolicies = '{ kyo.Chunk.empty },
            omitNoneAll = '{ false },
            omitEmptyCollectionsAll = '{ false },
            fieldMaterializedDefaults = '{ kyo.Chunk.empty[(String, kyo.Structure.Value)] },
            fieldTransforms = '{ kyo.Chunk.empty },
            fieldIdOverrides = '{ Map.empty[Seq[String], Int] }
        )
    end ProductConfig

    /** Macro-local carrier for sum desugar config. Fields map 1:1 onto Schema.init params. */
    private case class SumConfig(
        discriminatorField: Expr[Maybe[String]],
        representation: Expr[Schema.UnionRepresentation],
        variantNaming: Expr[Schema.VariantNaming],
        documentation: Expr[Maybe[String]],
        variantEffectivePrimaries: Expr[Set[String]]
    )

    private object SumConfig:
        def empty(using Quotes): SumConfig = SumConfig(
            discriminatorField = '{ kyo.Maybe.empty[String] },
            representation = '{ kyo.Schema.UnionRepresentation.External },
            variantNaming = '{ kyo.Schema.VariantNaming() },
            documentation = '{ kyo.Maybe.empty[String] },
            variantEffectivePrimaries = '{ Set.empty[String] }
        )
    end SumConfig

    /** Reads built-in SchemaAnnotation leaves off a case class's field and type-level annotations
      * and assembles a ProductConfig whose Expr values are spliced into Schema.init.
      *
      * Two-pass design: pass 1 builds a rename map per field so that pass 2 can resolve the
      * effective wire name before processing @alias (E-1 ordering safety).
      *
      * Parameterized by A (the case-class root type) so that `fieldTransforms` carries the correct
      * element type `Schema.FieldTransform[A]`. The caller (emitProductSchemaStatic[A]) passes A.
      */
    private def desugarProductConfig[A: Type](using
        Quotes
    )(
        sym: quotes.reflect.Symbol,
        tpe: quotes.reflect.TypeRepr
    ): ProductConfig[A] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived

        val fields = sym.caseFields

        // Ctor-param annotations in left-to-right source order.
        // Scala 3 returns them in reverse; reversing restores source order.
        def ctorParamAnnots(f: Symbol): List[Term] =
            sym.primaryConstructor.paramSymss.flatten
                .find(_.name == f.name)
                .map(_.annotations.reverse)
                .getOrElse(Nil)

        def firstStringArg(term: Term): Option[String] = term match
            case Apply(_, args) =>
                args.collectFirst {
                    case Literal(StringConstant(s))              => s
                    case NamedArg(_, Literal(StringConstant(s))) => s
                    case Typed(Literal(StringConstant(s)), _)    => s
                }
            case _ => None

        def firstIntArg(term: Term): Option[Int] = term match
            case Apply(_, args) =>
                args.collectFirst {
                    case Literal(IntConstant(n))              => n
                    case NamedArg(_, Literal(IntConstant(n))) => n
                    case Typed(Literal(IntConstant(n)), _)    => n
                }
            case _ => None

        def varargStrings(term: Term): List[String] = term match
            case Apply(_, args) =>
                args.flatMap {
                    case Typed(Repeated(elems, _), _) =>
                        elems.collect { case Literal(StringConstant(s)) => s }
                    case NamedArg(_, Typed(Repeated(elems, _), _)) =>
                        elems.collect { case Literal(StringConstant(s)) => s }
                    case Literal(StringConstant(s))              => List(s)
                    case NamedArg(_, Literal(StringConstant(s))) => List(s)
                    case _                                       => Nil
                }
            case _ => Nil

        // Extract the omit.Mode case name from an @omit annotation term.
        // An empty arg list means omit.WhenAbsent (the default).
        // Handles both positional (@omit(omit.WhenDefault)) and named-arg
        // (@omit(when = omit.WhenDefault)) forms, as well as the two-param form
        // @omit(reason = "...") where the when arg is absent (defaulting to WhenAbsent).
        def omitModeName(term: Term): String =
            def modeFromTerm(t: Term): String = t match
                case Select(_, "WhenNone")    => "WhenNone"
                case Select(_, "WhenEmpty")   => "WhenEmpty"
                case Select(_, "WhenDefault") => "WhenDefault"
                case Select(_, "WhenAbsent")  => "WhenAbsent"
                case Ident("WhenNone")        => "WhenNone"
                case Ident("WhenEmpty")       => "WhenEmpty"
                case Ident("WhenDefault")     => "WhenDefault"
                case _                        => "WhenAbsent"
            term match
                case Apply(_, args) if args.nonEmpty =>
                    // Named "when" arg takes precedence over positional first arg.
                    args.collectFirst { case NamedArg("when", inner) => modeFromTerm(inner) }
                        .getOrElse {
                            args.head match
                                case NamedArg(_, _) => "Absent" // named arg for "reason" only
                                case t              => modeFromTerm(t)
                        }
                case _ => "Absent"
            end match
        end omitModeName

        // Extract the OmitPredicate term from an @omit(omit.When(pred)) annotation term.
        // Returns Some(predTerm) when the when-arg is an omit.When construction.
        // Returns None for all constant omit.Mode cases (WhenAbsent / WhenNone / WhenEmpty / WhenDefault).
        // Checks the named "when" arg first, then the positional first arg, mirroring omitModeName.
        //
        // TASTy representation: Scala 3 stores omit.When(pred) in annotation args as
        // Typed(Apply(Select(companionObj, "apply"), List(pred)), TypeTree("omit.Mode")).
        // The Typed ascription widens the specific omit.Mode.When type to omit.Mode (the formal
        // parameter type). Constant cases like omit.WhenNone are stored as plain Select terms
        // (no Apply, no Typed). The Typed(Apply(...)) shape is therefore the discriminator.
        def extractWhenPredicate(term: Term): Option[Term] =
            def fromInner(t: Term): Option[Term] = t match
                // Companion apply with positional or named pred, without Typed ascription
                case Apply(Select(_, "When"), List(NamedArg(_, p))) => Option(p)
                case Apply(Select(_, "When"), List(p))              => Option(p)
                // TASTy form: Typed(Apply(companionApply, List(pred)), TypeTree("omit.Mode"))
                // Any Typed(Apply(...)) in this position is omit.When because the constant
                // cases (WhenAbsent, WhenNone, WhenEmpty, WhenDefault) are singleton Select terms, never Apply.
                case Typed(Apply(_, List(NamedArg("predicate", p))), _) => Option(p)
                case Typed(Apply(_, List(p)), _)                        => Option(p)
                case _                                                  => scala.None
            end fromInner
            term match
                case Apply(_, args) if args.nonEmpty =>
                    args.collectFirst { case NamedArg("when", inner) => fromInner(inner) }.flatten
                        .orElse(args.head match
                            case NamedArg(_, _) => scala.None
                            case t              => fromInner(t))
                case _ => scala.None
            end match
        end extractWhenPredicate

        // Compile-time collection-type test, mirroring SchemaSerializer.isCollectionOrMapTag.
        def isCollectionType(t: TypeRepr): Boolean =
            t.dealias match
                case AppliedType(tycon, _) =>
                    val s = tycon.typeSymbol
                    s == TypeRepr.of[kyo.Chunk[Any]].typeSymbol ||
                    s == TypeRepr.of[List[Any]].typeSymbol ||
                    s == TypeRepr.of[Vector[Any]].typeSymbol ||
                    s == TypeRepr.of[Set[Any]].typeSymbol ||
                    s == TypeRepr.of[Seq[Any]].typeSymbol ||
                    s == TypeRepr.of[Map[Any, Any]].typeSymbol
                case _ => false

        val maybeSym  = TypeRepr.of[kyo.Maybe[Any]].typeSymbol
        val optionSym = TypeRepr.of[Option[Any]].typeSymbol

        // PASS 1: collect per-field rename map for effective wire name resolution.
        val renameMap: Map[String, String] = fields.flatMap { f =>
            ctorParamAnnots(f)
                .find(_.tpe <:< TypeRepr.of[kyo.schema.rename])
                .flatMap(firstStringArg)
                .map(f.name -> _)
        }.toMap

        def effectiveWire(fieldName: String): String = renameMap.getOrElse(fieldName, fieldName)

        // PASS 2: accumulate config entries across all fields.
        var renamedPairs: List[Expr[(String, String)]]                       = Nil
        var droppedExprs: List[Expr[String]]                                 = Nil
        var fieldDocPairs: List[Expr[(Seq[String], String)]]                 = Nil
        var aliasPairs: List[Expr[(String, String)]]                         = Nil
        var omitEntries: List[Expr[(String, Schema.OmitPolicy)]]             = Nil
        var matDefEntries: List[Expr[(String, Structure.Value)]]             = Nil
        var transformEntries: List[Expr[(String, Schema.FieldTransform[A])]] = Nil
        var fieldIdPairs: List[Expr[(Seq[String], Int)]]                     = Nil
        var typeDocOpt: Option[Expr[String]]                                 = None

        sym.annotations.foreach { term =>
            if term.tpe <:< TypeRepr.of[kyo.schema.doc] then
                firstStringArg(term).foreach(txt => typeDocOpt = Some(Expr(txt)))
        }

        fields.zipWithIndex.foreach { (f, idx) =>
            val anns     = ctorParamAnnots(f)
            val wire     = effectiveWire(f.name)
            val wireExpr = Expr(wire)

            anns.foreach { term =>
                if term.tpe <:< TypeRepr.of[kyo.schema.rename] then
                    firstStringArg(term).foreach { w =>
                        renamedPairs = '{ (${ Expr(f.name) }, ${ Expr(w) }) } :: renamedPairs
                    }
                else if term.tpe <:< TypeRepr.of[kyo.schema.transient] then
                    droppedExprs = Expr(f.name) :: droppedExprs
                else if term.tpe <:< TypeRepr.of[kyo.schema.doc] then
                    firstStringArg(term).foreach { txt =>
                        fieldDocPairs = '{ (Seq($wireExpr), ${ Expr(txt) }) } :: fieldDocPairs
                    }
                else if term.tpe <:< TypeRepr.of[kyo.schema.alias] then
                    varargStrings(term).foreach { a =>
                        aliasPairs = '{ (${ Expr(a) }, $wireExpr) } :: aliasPairs
                    }
                else if term.tpe <:< TypeRepr.of[kyo.schema.omit] then
                    val srcExpr = Expr(f.name)
                    // omit.When is checked first: its shape (Apply(Select(_, "When"), List(pred)))
                    // cannot be distinguished by omitModeName (which falls through to "WhenAbsent").
                    extractWhenPredicate(term) match
                        case Some(predTerm) =>
                            val predExpr = liftObjectArg[kyo.schema.OmitPredicate](predTerm)
                            omitEntries =
                                '{ ($srcExpr, kyo.Schema.OmitPolicy.When(v => $predExpr.test(v))) } :: omitEntries
                        case scala.None =>
                            val mode      = omitModeName(term)
                            val fieldType = tpe.memberType(f).dealias
                            val isOpt = fieldType match
                                case AppliedType(tycon, _) =>
                                    tycon.typeSymbol == maybeSym || tycon.typeSymbol == optionSym
                                case _ => false
                            val isCol = isCollectionType(fieldType)

                            mode match
                                case "WhenNone" =>
                                    omitEntries = '{ ($srcExpr, kyo.Schema.OmitPolicy.WhenNone) } :: omitEntries
                                case "WhenEmpty" =>
                                    omitEntries = '{ ($srcExpr, kyo.Schema.OmitPolicy.WhenEmpty) } :: omitEntries
                                case "WhenDefault" =>
                                    omitEntries =
                                        '{ ($srcExpr, kyo.Schema.OmitPolicy.WhenDefault) } :: omitEntries
                                    // Materialize the Scala default value into a Structure.Value for
                                    // fieldMaterializedDefaults so OmitPolicy.WhenDefault can compare at
                                    // runtime. Keyed by the Scala source name (same key
                                    // SchemaSerializer.omitField uses for the sourceName lookup).
                                    MacroUtils.getDefault(tpe, idx) match
                                        case Some(defVal) =>
                                            tpe.memberType(f).asType match
                                                case '[t] =>
                                                    Expr.summon[kyo.Tag[t]] match
                                                        case Some(tagExpr) =>
                                                            matDefEntries =
                                                                '{
                                                                    (
                                                                        $srcExpr,
                                                                        // Unsafe: the materialized default's static type is erased at
                                                                        // this macro site; the value is the field's own declared default
                                                                        // so it conforms to the field type `t`.
                                                                        kyo.Structure.Value.primitive[t]($defVal.asInstanceOf[t])(using
                                                                            $tagExpr
                                                                        )
                                                                    )
                                                                } :: matDefEntries
                                                        case scala.None => ()
                                        case scala.None => ()
                                    end match
                                case _ => // WhenAbsent: type-aware desugar
                                    if isOpt then
                                        omitEntries =
                                            '{ ($srcExpr, kyo.Schema.OmitPolicy.WhenNone) } :: omitEntries
                                    else if isCol then
                                        omitEntries =
                                            '{ ($srcExpr, kyo.Schema.OmitPolicy.WhenEmpty) } :: omitEntries
                                    // Plain scalar with omit.WhenAbsent: annotation is captured in the
                                    // schema structure for documentation (reason string) but no omit
                                    // policy is applied. Use omit.WhenDefault (with a Scala default)
                                    // to conditionally omit a scalar field.
                            end match
                    end match
                else if term.tpe <:< TypeRepr.of[kyo.schema.transform] then
                    // Extract the transformer constructor arg (positional or named).
                    val argTerm: Term = term match
                        case Apply(_, args) if args.nonEmpty =>
                            args.collectFirst { case NamedArg("transformer", inner) => inner }
                                .getOrElse(args.head)
                        case _ =>
                            report.errorAndAbort(
                                s"Unexpected @transform annotation shape on field '${f.name}': $term"
                            )

                    // Verify transformer element type matches field type.
                    val fieldType      = tpe.memberType(f).dealias
                    val transformerSym = TypeRepr.of[kyo.schema.Transformer[Any]].typeSymbol
                    argTerm.tpe.baseType(transformerSym) match
                        case AppliedType(_, List(elemType)) =>
                            if !(elemType =:= fieldType) then
                                report.errorAndAbort(
                                    s"Type mismatch: @transform on field '${f.name}' of type '${fieldType.show}': " +
                                        s"transformer element type '${elemType.show}' does not match field type"
                                )
                        case _ =>
                            report.errorAndAbort(
                                s"Cannot determine Transformer element type for @transform on field '${f.name}'"
                            )
                    end match

                    // Reify the stable object reference from the annotation AST into an Expr
                    // whose runtime value is the singleton object named in the annotation.
                    fieldType.asType match
                        case '[ft] =>
                            // Build getter lambda: (v: A) => v.fieldName
                            val getExpr: Expr[A => Any] =
                                val mtpe = MethodType(List("_v"))(_ => List(TypeRepr.of[A]), _ => TypeRepr.of[Any])
                                Lambda(
                                    Symbol.spliceOwner,
                                    mtpe,
                                    { (_, params) =>
                                        Select(params.head.asInstanceOf[Term], f)
                                    }
                                ).asExprOf[A => Any]
                            end getExpr

                            // writeDerived: schema-derived write for the field type (the post-read write-back path).
                            val writeDerivedExpr: Expr[(Any, kyo.Codec.Writer) => Unit] =
                                '{
                                    (value: Any, writer: kyo.Codec.Writer) =>
                                        kyo.internal.writeField(
                                            scala.compiletime.summonInline[kyo.Schema[ft]],
                                            // Unsafe: value is extracted from the product by the companion get lambda,
                                            // which guarantees the runtime type is ft; Any here is an erasure artifact.
                                            value.asInstanceOf[ft],
                                            writer
                                        )
                                }

                            val fullSym      = TypeRepr.of[kyo.schema.Transformer.Full[Any]].typeSymbol
                            val writeOnlySym = TypeRepr.of[kyo.schema.Transformer.WriteOnly[Any]].typeSymbol
                            val readOnlySym  = TypeRepr.of[kyo.schema.Transformer.ReadOnly[Any]].typeSymbol
                            val baseClasses  = argTerm.tpe.baseClasses

                            if baseClasses.contains(fullSym) then
                                val fullExpr = liftObjectArg[kyo.schema.Transformer.Full[ft]](argTerm)
                                val writeExpr: Expr[kyo.Maybe[(Any, kyo.Codec.Writer) => Unit]] =
                                    '{
                                        kyo.Maybe[(Any, kyo.Codec.Writer) => Unit]((value: Any, writer: kyo.Codec.Writer) =>
                                            // Unsafe: value is the field extracted by the companion get lambda with static type ft
                                            // at the call site; Any is an erasure boundary artifact.
                                            $fullExpr.write(value.asInstanceOf[ft], writer)
                                        )
                                    }
                                val readExpr: Expr[kyo.Maybe[kyo.Codec.Reader => Any]] =
                                    '{
                                        kyo.Maybe[kyo.Codec.Reader => Any]((reader: kyo.Codec.Reader) =>
                                            // Unsafe: read returns ft at the call site; widening to Any is the erasure boundary
                                            // convention for the lambda's declared return type.
                                            $fullExpr.read(reader).asInstanceOf[Any]
                                        )
                                    }
                                transformEntries =
                                    '{
                                        (
                                            ${ Expr(f.name) },
                                            kyo.Schema.FieldTransform[A](
                                                get = $getExpr,
                                                write = $writeExpr,
                                                read = $readExpr,
                                                writeDerived = $writeDerivedExpr
                                            )
                                        )
                                    } :: transformEntries
                            else if baseClasses.contains(writeOnlySym) then
                                val woExpr = liftObjectArg[kyo.schema.Transformer.WriteOnly[ft]](argTerm)
                                val writeExpr: Expr[kyo.Maybe[(Any, kyo.Codec.Writer) => Unit]] =
                                    '{
                                        kyo.Maybe[(Any, kyo.Codec.Writer) => Unit]((value: Any, writer: kyo.Codec.Writer) =>
                                            // Unsafe: value is the field extracted by the companion get lambda with static type ft
                                            // at the call site; Any is an erasure boundary artifact.
                                            $woExpr.write(value.asInstanceOf[ft], writer)
                                        )
                                    }
                                transformEntries =
                                    '{
                                        (
                                            ${ Expr(f.name) },
                                            kyo.Schema.FieldTransform[A](
                                                get = $getExpr,
                                                write = $writeExpr,
                                                read = kyo.Maybe.empty[kyo.Codec.Reader => Any],
                                                writeDerived = $writeDerivedExpr
                                            )
                                        )
                                    } :: transformEntries
                            else if baseClasses.contains(readOnlySym) then
                                val roExpr = liftObjectArg[kyo.schema.Transformer.ReadOnly[ft]](argTerm)
                                val readExpr: Expr[kyo.Maybe[kyo.Codec.Reader => Any]] =
                                    '{
                                        kyo.Maybe[kyo.Codec.Reader => Any]((reader: kyo.Codec.Reader) =>
                                            // Unsafe: read returns ft at the call site; widening to Any is the erasure boundary
                                            // convention for the lambda's declared return type.
                                            $roExpr.read(reader).asInstanceOf[Any]
                                        )
                                    }
                                transformEntries =
                                    '{
                                        (
                                            ${ Expr(f.name) },
                                            kyo.Schema.FieldTransform[A](
                                                get = $getExpr,
                                                write = kyo.Maybe.empty[(Any, kyo.Codec.Writer) => Unit],
                                                read = $readExpr,
                                                writeDerived = $writeDerivedExpr
                                            )
                                        )
                                    } :: transformEntries
                            else
                                report.errorAndAbort(
                                    s"@transform on field '${f.name}': transformer must extend " +
                                        "Transformer.Full, Transformer.WriteOnly, or Transformer.ReadOnly"
                                )
                            end if
                    end match
                else if term.tpe <:< TypeRepr.of[kyo.schema.proto.fieldNumber] then
                    firstIntArg(term) match
                        case Some(n) =>
                            if n <= 0 then
                                report.errorAndAbort(
                                    s"@proto.fieldNumber on field '${f.name}': field number must be positive, got $n"
                                )
                            end if
                            // Pin keyed by the single-segment Scala field name, the same shape the
                            // programmatic Schema.fieldId(_.field)(n) builder writes, so it is
                            // wire-functional for the Protobuf codec and surfaced by fieldNumberAudit.
                            fieldIdPairs = '{ (Seq(${ Expr(f.name) }), ${ Expr(n) }) } :: fieldIdPairs
                        case scala.None =>
                            // A non-constant pin hard-fails rather than silently skipping like @rename and @doc do:
                            // with no compile-time number, encode would fall back to the hash-derived field number
                            // and silently break the Protobuf wire interop the pin exists to guarantee.
                            report.errorAndAbort(
                                s"@proto.fieldNumber on field '${f.name}': expected a constant Int field number"
                            )
                    end match
            }
        }

        val renamedFieldsExpr: Expr[Chunk[(String, String)]] =
            if renamedPairs.isEmpty then '{ kyo.Chunk.empty[(String, String)] }
            else '{ kyo.Chunk.from[(String, String)](Array[(String, String)](${ Varargs(renamedPairs.reverse) }*)) }

        val droppedFieldsExpr: Expr[Set[String]] =
            if droppedExprs.isEmpty then '{ Set.empty[String] } else '{ Set[String](${ Varargs(droppedExprs.reverse) }*) }

        val documentationExpr: Expr[Maybe[String]] = typeDocOpt match
            case Some(txt) => '{ kyo.Maybe($txt) }
            case None      => '{ kyo.Maybe.empty[String] }

        val fieldDocsExpr: Expr[Map[Seq[String], String]] =
            if fieldDocPairs.isEmpty then '{ Map.empty[Seq[String], String] }
            else '{ Map[Seq[String], String](${ Varargs(fieldDocPairs.reverse) }*) }

        val variantNamingExpr: Expr[Schema.VariantNaming] =
            if aliasPairs.isEmpty then '{ kyo.Schema.VariantNaming() }
            else
                val aliasChunk = '{ kyo.Chunk.from[(String, String)](Array[(String, String)](${ Varargs(aliasPairs.reverse) }*)) }
                '{ kyo.Schema.VariantNaming(fieldAliases = $aliasChunk) }

        val omitPoliciesExpr: Expr[Chunk[(String, Schema.OmitPolicy)]] =
            if omitEntries.isEmpty then '{ kyo.Chunk.empty }
            else
                // The element type is left to inference rather than spelled out: `Schema.OmitPolicy` is
                // `private[kyo]`, so naming it in an explicit type-argument position would make the
                // generated code uncompilable when the annotated type lives outside package `kyo`. The
                // tuple expressions already fix the inferred element type.
                '{
                    kyo.Chunk.from(Array(${
                        Varargs(omitEntries.reverse)
                    }*))
                }

        val matDefaultsExpr: Expr[Chunk[(String, Structure.Value)]] =
            if matDefEntries.isEmpty then '{ kyo.Chunk.empty[(String, kyo.Structure.Value)] }
            else
                '{
                    kyo.Chunk.from[(String, kyo.Structure.Value)](Array[(String, kyo.Structure.Value)](${
                        Varargs(matDefEntries.reverse)
                    }*))
                }

        val fieldTransformsExpr: Expr[Chunk[(String, Schema.FieldTransform[A])]] =
            if transformEntries.isEmpty then '{ kyo.Chunk.empty }
            else
                // Element type left to inference: `Schema.FieldTransform` is `private[kyo]`, so an
                // explicit type-argument naming it would not compile when the annotated type lives
                // outside package `kyo`. The tuple expressions fix the inferred element type.
                '{
                    kyo.Chunk.from(Array(${
                        Varargs(transformEntries.reverse)
                    }*))
                }

        val fieldIdOverridesExpr: Expr[Map[Seq[String], Int]] =
            if fieldIdPairs.isEmpty then '{ Map.empty[Seq[String], Int] }
            else '{ Map[Seq[String], Int](${ Varargs(fieldIdPairs.reverse) }*) }

        ProductConfig[A](
            renamedFields = renamedFieldsExpr,
            droppedFields = droppedFieldsExpr,
            documentation = documentationExpr,
            fieldDocs = fieldDocsExpr,
            variantNaming = variantNamingExpr,
            omitPolicies = omitPoliciesExpr,
            omitNoneAll = '{ false },
            omitEmptyCollectionsAll = '{ false },
            fieldMaterializedDefaults = matDefaultsExpr,
            fieldTransforms = fieldTransformsExpr,
            fieldIdOverrides = fieldIdOverridesExpr
        )
    end desugarProductConfig

    /** Reads built-in SchemaAnnotation leaves off a sealed trait and its children and assembles
      * a SumConfig whose Expr values are spliced into the sum Schema.init.
      *
      * Type-level annotations (@discriminator, @adjacent, @untagged, @doc) come from `sym`.
      * Variant-level annotations (@rename, @alias) come from each child symbol.
      */
    private def desugarSumConfig(using
        Quotes
    )(
        sym: quotes.reflect.Symbol,
        tpe: quotes.reflect.TypeRepr,
        children: List[quotes.reflect.Symbol]
    ): SumConfig =
        import quotes.reflect.*

        def firstStringArg(term: Term): Option[String] = term match
            case Apply(_, args) =>
                args.collectFirst {
                    case Literal(StringConstant(s))              => s
                    case NamedArg(_, Literal(StringConstant(s))) => s
                    case Typed(Literal(StringConstant(s)), _)    => s
                }
            case _ => None

        def varargStrings(term: Term): List[String] = term match
            case Apply(_, args) =>
                args.flatMap {
                    case Typed(Repeated(elems, _), _) =>
                        elems.collect { case Literal(StringConstant(s)) => s }
                    case NamedArg(_, Typed(Repeated(elems, _), _)) =>
                        elems.collect { case Literal(StringConstant(s)) => s }
                    case Literal(StringConstant(s))              => List(s)
                    case NamedArg(_, Literal(StringConstant(s))) => List(s)
                    case _                                       => Nil
                }
            case _ => Nil

        var discriminatorOpt: Option[Expr[String]]                      = None
        var representationOpt: Option[Expr[Schema.UnionRepresentation]] = None
        var docOpt: Option[Expr[String]]                                = None
        var variantPairs: List[Expr[(String, String)]]                  = Nil
        var variantAliasPairs: List[Expr[(String, String)]]             = Nil
        var effectiveWireNames: List[String]                            = Nil

        sym.annotations.foreach { term =>
            if term.tpe <:< TypeRepr.of[kyo.schema.discriminator] then
                firstStringArg(term).foreach { key =>
                    discriminatorOpt = Some(Expr(key))
                    representationOpt = Some('{ kyo.Schema.UnionRepresentation.Internal(${ Expr(key) }) })
                }
            else if term.tpe <:< TypeRepr.of[kyo.schema.adjacent] then
                term match
                    case Apply(_, args) =>
                        def strAt(i: Int): Option[String] = args.lift(i).collectFirst {
                            case Literal(StringConstant(s))              => s
                            case NamedArg(_, Literal(StringConstant(s))) => s
                        }
                        for
                            tagKey     <- strAt(0)
                            contentKey <- strAt(1)
                        do representationOpt = Some('{ kyo.Schema.UnionRepresentation.Adjacent(${ Expr(tagKey) }, ${ Expr(contentKey) }) })
                        end for
                    case _ => ()
            else if term.tpe <:< TypeRepr.of[kyo.schema.untagged] then
                representationOpt = Some('{ kyo.Schema.UnionRepresentation.Untagged })
            else if term.tpe <:< TypeRepr.of[kyo.schema.doc] then
                firstStringArg(term).foreach(txt => docOpt = Some(Expr(txt)))
        }

        // Variant-level: @rename and @alias on each child symbol.
        children.foreach { child =>
            val childName = child.name.stripSuffix("$")
            val childRenameOpt = child.annotations.collectFirst {
                case term if term.tpe <:< TypeRepr.of[kyo.schema.rename] =>
                    firstStringArg(term)
            }.flatten
            val effectiveChildWire = childRenameOpt.getOrElse(childName)

            // Collect effective wire name for the alias-vs-primary collision check at Schema.init
            // time. The check needs the full set without forcing the lazy structure; baking the
            // set here (compile time) mirrors what effectiveVariantWires computes at runtime.
            effectiveWireNames = effectiveChildWire :: effectiveWireNames

            childRenameOpt.foreach { wire =>
                variantPairs = '{ (${ Expr(childName) }, ${ Expr(wire) }) } :: variantPairs
            }

            child.annotations.foreach { term =>
                if term.tpe <:< TypeRepr.of[kyo.schema.alias] then
                    varargStrings(term).foreach { a =>
                        variantAliasPairs = '{ (${ Expr(a) }, ${ Expr(effectiveChildWire) }) } :: variantAliasPairs
                    }
            }
        }

        val discriminatorExpr: Expr[Maybe[String]] = discriminatorOpt match
            case Some(key) => '{ kyo.Maybe($key) }
            case None      => '{ kyo.Maybe.empty[String] }

        val representationExpr: Expr[Schema.UnionRepresentation] =
            representationOpt.getOrElse('{ kyo.Schema.UnionRepresentation.External })

        val documentationExpr: Expr[Maybe[String]] = docOpt match
            case Some(txt) => '{ kyo.Maybe($txt) }
            case None      => '{ kyo.Maybe.empty[String] }

        val variantNamingExpr: Expr[Schema.VariantNaming] =
            if variantPairs.isEmpty && variantAliasPairs.isEmpty then '{ kyo.Schema.VariantNaming() }
            else
                val pairsChunk =
                    if variantPairs.isEmpty then '{ kyo.Chunk.empty[(String, String)] }
                    else '{ kyo.Chunk.from[(String, String)](Array[(String, String)](${ Varargs(variantPairs.reverse) }*)) }
                val aliasChunk =
                    if variantAliasPairs.isEmpty then '{ kyo.Chunk.empty[(String, String)] }
                    else '{ kyo.Chunk.from[(String, String)](Array[(String, String)](${ Varargs(variantAliasPairs.reverse) }*)) }
                '{ kyo.Schema.VariantNaming(variantPairs = $pairsChunk, variantAliases = $aliasChunk) }

        // Bake the effective primary wire names into the check at Schema.init time. This is the
        // same set that effectiveVariantWires computes at runtime, but computed at compile time
        // so that Schema.init does not need to force the lazy structure (which would break
        // recursive-schema initialization cycles).
        val effectivePrimariesExpr: Expr[Set[String]] =
            if effectiveWireNames.isEmpty then '{ Set.empty[String] }
            else
                val nameExprs = effectiveWireNames.reverse.map(Expr(_))
                '{ Set[String](${ Varargs(nameExprs) }*) }

        SumConfig(
            discriminatorField = discriminatorExpr,
            representation = representationExpr,
            variantNaming = variantNamingExpr,
            documentation = documentationExpr,
            variantEffectivePrimaries = effectivePrimariesExpr
        )
    end desugarSumConfig

    /** Rejects sum-only representation annotations placed on a non-sum type (case class or union).
      *
      * If `sym` carries any annotation conforming to `@discriminator`, `@adjacent`, or `@untagged`,
      * this method calls `report.errorAndAbort` with a message that contains the phrase
      * "sum-representation annotation", which `typeCheckErrors` assertions key on.
      */
    private def rejectSumOnlyAnnotations(using
        Quotes
    )(
        tpe: quotes.reflect.TypeRepr,
        sym: quotes.reflect.Symbol
    ): Unit =
        import quotes.reflect.*
        sym.annotations.foreach { term =>
            val annName =
                if term.tpe <:< TypeRepr.of[kyo.schema.discriminator] then Some("@discriminator")
                else if term.tpe <:< TypeRepr.of[kyo.schema.adjacent] then Some("@adjacent")
                else if term.tpe <:< TypeRepr.of[kyo.schema.untagged] then Some("@untagged")
                else None
            annName.foreach { name =>
                report.errorAndAbort(
                    s"$name is a sum-representation annotation and can only be placed on a sealed " +
                        s"trait. It was placed on `${tpe.show}`, which is not a sealed trait."
                )
            }
        }
    end rejectSumOnlyAnnotations

    /** Reifies a stable object-reference term extracted from an annotation constructor argument.
      *
      * Given an annotation like `@transform(UpperCase)`, the caller
      * extracts `argTerm` from the annotation's `Apply(_, List(argTerm))` constructor call and
      * passes it here. Because the Scala front-end has already type-checked that `argTerm.tpe <:<
      * T` before the macro sees it, `asExprOf[T]` is safe and produces an `Expr[T]` whose runtime
      * value is the singleton object the user named in the annotation.
      */
    private def liftObjectArg[T: Type](using Quotes)(argTerm: quotes.reflect.Term): Expr[T] =
        import quotes.reflect.*
        argTerm.asExprOf[T]
    end liftObjectArg

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
            rejectSumOnlyAnnotations(tpe, sym)
            val sourceFieldsExpr: Expr[Seq[kyo.Field[?, ?]]] = Expr.summon[kyo.Fields[A]] match
                case Some(fieldsExpr) => '{ $fieldsExpr.fields }
                case None             => '{ Seq.empty[kyo.Field[?, ?]] }
            emitProductSchemaStatic[A](tpe, sym, sourceFields = sourceFieldsExpr, focusedType = tpe)
        else if isOrType(tpe) then
            // Reject sum-representation annotations placed on a union type alias.
            rejectSumOnlyAnnotations(TypeRepr.of[A], TypeRepr.of[A].typeSymbol)
            emitUnionSchemaStatic[A](tpe)
        else
            report.errorAndAbort(
                s"Cannot derive Schema for ${tpe.show}: not a case class or sealed trait. " +
                    "Provide a given Schema instance for this type if derivation is not possible."
            )
        end if
    end derivedImpl

    /** True iff `tpe` is a Scala type union (`A | B`). */
    private def isOrType(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
        import quotes.reflect.*
        tpe.dealias match
            case _: OrType => true
            case _         => false
    end isOrType

    /** Flattens a (possibly nested) union into its member types in declared first-occurrence order
      * (depth-first, left-to-right), preserving order. A member appearing twice is kept at its
      * first occurrence only. Does not use Set so declaration order is stable.
      */
    private def flattenUnion(using Quotes)(tpe: quotes.reflect.TypeRepr): List[quotes.reflect.TypeRepr] =
        import quotes.reflect.*
        val acc  = scala.collection.mutable.ListBuffer.empty[TypeRepr]
        val seen = scala.collection.mutable.ListBuffer.empty[String]
        def go(t: TypeRepr): Unit = t.dealias match
            case OrType(l, r) => go(l); go(r)
            case other =>
                val key = other.show
                if !seen.contains(key) then
                    seen += key
                    acc += other
        go(tpe)
        acc.toList
    end flattenUnion

    /** The default wire label for a union member: splits on '.' and takes the last segment,
      * giving the simple name for both primitives ("scala.Int" -> "Int") and named types.
      */
    private def memberLabel(using Quotes)(tpe: quotes.reflect.TypeRepr): String =
        tpe.show.split('.').last
    end memberLabel

    /** Emits the `Schema[A]` for a Scala type union `A | B | ...`. Mirrors `emitSealedSchemaStatic`
      * but enumerates flattened OrType members (order-preserving) instead of `sym.children`: each
      * member's `Schema` is summoned via `summonInline[Schema[member]]`, the encode branch selects
      * by a static `isInstanceOf[member]` chain, and the structure is a
      * `Structure.Type.Sum` whose `variants` carry the member names in declared order and whose
      * `enumValues` is empty (a union has no case-object enum values).
      */
    private def emitUnionSchemaStatic[A: Type](using Quotes)(tpe: quotes.reflect.TypeRepr): Expr[Schema[A]] =
        import quotes.reflect.*
        val members     = flattenUnion(tpe)
        val n           = members.size
        val memberNames = members.map(memberLabel)

        val labelCounts = memberNames.groupBy(identity).filter(_._2.size > 1)
        if labelCounts.nonEmpty then
            val collisions = labelCounts.keys.toList.sorted.mkString(", ")
            val involved = memberNames.zip(members).collect {
                case (label, member) if labelCounts.contains(label) =>
                    s"${member.show} (label \"$label\")"
            }.mkString(", ")
            report.errorAndAbort(
                s"Cannot derive Schema for ${tpe.show}: union members produce duplicate wire labels [$collisions]. " +
                    s"Colliding members: $involved. " +
                    "Rename the types so each member has a distinct simple name, or wrap them in distinct named types."
            )
        end if

        val tagExpr = summonSchemaTag(tpe)
        val owner   = Symbol.spliceOwner

        // _uself: Schema[A] (lazy), one _u$idx: Schema[Any] (lazy) per member,
        // one _unb$idx: Array[Byte] per member for the fieldBytes write envelope.
        val selfSym = Symbol.newVal(owner, "_uself", TypeRepr.of[Schema[A]], Flags.Lazy, Symbol.noSymbol)
        val variantSyms: List[Symbol] =
            (0 until n).toList.map(idx => Symbol.newVal(owner, s"_u$idx", TypeRepr.of[Schema[Any]], Flags.Lazy, Symbol.noSymbol))
        val nameByteSyms: List[Symbol] =
            (0 until n).toList.map(idx => Symbol.newVal(owner, s"_unb$idx", TypeRepr.of[Array[Byte]], Flags.EmptyFlags, Symbol.noSymbol))

        val selfRef: Term = Ref(selfSym)

        // Encode branch: static isInstanceOf[member] per member.
        // Each arm wraps the member payload in a one-field object envelope (memberName -> memberValue)
        // mirroring the sealed-sum writeBody. writeWithTransforms -> untaggedEncode strips the outer
        // envelope, leaving the bare member payload on the wire (the untagged default).
        // The cast to Any is sound: the isInstanceOf guard ensures the value is of that member type.
        def writeBody(v: Expr[A], w: Expr[Writer]): Expr[Unit] =
            val chain: Term = (0 until n).foldRight(
                '{ kyo.bug("Schema union write: " + $v.asInstanceOf[Any].getClass.getName + " matched no member") }.asTerm
            ) { (idx, elseTerm) =>
                val cond = members(idx).asType match
                    case '[t] => '{ $v.asInstanceOf[Any].isInstanceOf[t] }.asTerm
                val mName    = Expr(memberNames(idx))
                val mFieldId = Expr(kyo.internal.CodecMacro.fieldId(memberNames(idx)))
                val arm = '{
                    $w.objectStart($mName, 1)
                    $w.fieldBytes(${ Ref(nameByteSyms(idx)).asExprOf[Array[Byte]] }, $mFieldId)
                    ${ Ref(variantSyms(idx)).asExprOf[Schema[Any]] }.serializeWrite($v, $w)
                    $w.objectEnd()
                }.asTerm
                If(cond, arm, elseTerm)
            }
            chain.asExprOf[Unit]
        end writeBody

        val variantDecodersExpr: Expr[Chunk[kyo.Codec.Reader => Any]] =
            val perMember: List[Expr[kyo.Codec.Reader => Any]] = (0 until n).toList.map { idx =>
                '{ (r: kyo.Codec.Reader) => ${ Ref(variantSyms(idx)).asExprOf[Schema[Any]] }.serializeRead(r) }
            }
            '{ kyo.Chunk.from[kyo.Codec.Reader => Any](Array[kyo.Codec.Reader => Any](${ Varargs(perMember) }*)) }
        end variantDecodersExpr

        // External-format variant dispatcher: reads {memberName: payload} and routes to the
        // matching member's serializeRead. Used for tagged representations (adjacent, internal,
        // tuple, tupleFlat) where the representation layer has already resolved the tag and
        // presents the payload in External wrapper format to rawSerializeRead.
        def readBody(r: Expr[kyo.Codec.Reader]): Expr[A] =
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
                    throw kyo.MissingFieldException(Seq.empty, "<member>")(using $r.frame)
                $r.fieldParse()
                val _result: A = ${ matchChain.asExprOf[A] }
                $r.objectEnd()
                _result
            }
        end readBody

        def structureExpr: Expr[Structure.Type] =
            val variantStructs: List[Expr[kyo.Structure.Variant]] = (0 until n).toList.map { idx =>
                val memberAnnots = captureAnnotations(members(idx).typeSymbol)
                '{
                    kyo.Structure.Variant(
                        ${ Expr(memberNames(idx)) },
                        ${ Ref(variantSyms(idx)).asExprOf[Schema[Any]] }.structure,
                        $memberAnnots
                    )
                }
            }
            '{
                Structure.Type.Sum(
                    "Union",
                    $tagExpr,
                    kyo.Chunk.empty[Structure.Type],
                    kyo.Chunk.from[kyo.Structure.Variant](Array[kyo.Structure.Variant](${ Varargs(variantStructs) }*)),
                    kyo.Chunk.empty[String],
                    kyo.Chunk.empty[Any]
                )
            }
        end structureExpr

        val selfRefExpr: Expr[Schema[A]] = selfRef.asExprOf[Schema[A]]
        val selfRhs: Expr[Schema[A]] = '{
            Schema.init[A](
                writeFn = (v, w) => ${ writeBody('v, 'w) },
                readFn = r => ${ readBody('r) },
                variantDecoders = $variantDecodersExpr,
                representation = Schema.UnionRepresentation.Untagged,
                structure = ${ structureExpr }
            )
        }
        val selfDef = ValDef(selfSym, Some(selfRhs.asTerm.changeOwner(selfSym)))

        // Name-byte vals (non-lazy; computed once at schema init, consumed by writeBody).
        val nameBytesValDefs: List[ValDef] = memberNames.zipWithIndex.map { (mn, idx) =>
            val nameExpr = Expr(mn)
            ValDef(nameByteSyms(idx), Some('{ $nameExpr.getBytes(java.nio.charset.StandardCharsets.UTF_8) }.asTerm))
        }

        // Member schema vals (lazy; summon each member's Schema).
        val variantDefs: List[ValDef] = members.zipWithIndex.map { (memberType, idx) =>
            memberType.asType match
                case '[t] =>
                    val rhs = '{ summonInline[Schema[t]].asInstanceOf[Schema[Any]] }.asTerm
                    ValDef(variantSyms(idx), Some(rhs.changeOwner(variantSyms(idx))))
        }

        Block(nameBytesValDefs ++ (selfDef :: variantDefs), selfRef).asExprOf[Schema[A]]
    end emitUnionSchemaStatic

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
      * bitmap, ORs in reader-supplied pre-satisfied field masks before the required check, and throws
      * `MissingFieldException` (carrying `reader.frame`) for a missing required field. Used by both
      * `derivedImpl` (`derives Schema`) and the `metaApplyImpl` Focus path.
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
                if !hasDefaultFlags(idx) && !isMaybeFlags(idx) && !isOptionFlags(idx) then
                    m | (1L << idx)
                else m
            }
            val fieldNames: List[String] = fields.map(_.name)

            val absentDefaultableMaskExpr: Expr[Long] =
                fields.indices.foldLeft('{ 0L }) { (mask, idx) =>
                    if hasDefaultFlags(idx) || isMaybeFlags(idx) || isOptionFlags(idx) then mask
                    else
                        effectiveSchemaTypes(idx).asType match
                            case '[t] =>
                                val s = fieldSchemaExprTyped[t](idx)
                                '{ $mask | kyo.internal.absentDefaultMask($s, ${ Expr(1L << idx) }) }
                }

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
                    effectiveSchemaTypes(idx).asType match
                        case '[t] =>
                            val s = fieldSchemaExprTyped[t](idx)
                            '{ kyo.internal.absentDefaultSeed($s) }
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
                        val _combined =
                            $seenRef |
                                $r.droppedFieldsMask($nExpr) |
                                $r.absentDefaultedFieldsMask($nExpr, $absentDefaultableMaskExpr)
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
            val docSym = TypeRepr.of[kyo.schema.doc].typeSymbol
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
                        val fieldAnnots    = captureFieldAnnotations(f, sym)
                        '{
                            kyo.Structure.Field(
                                $nameExpr,
                                $fieldSchemaRef.structure,
                                $docExpr,
                                $defVal($idxExpr)(),
                                $optExpr,
                                $fieldAnnots
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
            val nameExprS  = Expr(typeName)
            val typeAnnots = captureAnnotations(sym)
            '{
                Structure.Type.Product(
                    $nameExprS,
                    $tagExpr,
                    $tpStructures,
                    kyo.Chunk.from[Structure.Field](Array[Structure.Field](${ Varargs(fieldStructures) }*)),
                    $typeAnnots
                )
            }
        end structureExpr

        // Build the Schema.init term first so every fieldSchemaExprTyped / structure call has
        // populated `hoistedSchemas`, then prepend the hoisted lazy vals as a wrapping block.
        val cfg = desugarProductConfig[A](sym, tpe)
        val schemaInitTerm: Term =
            '{
                Schema.init[A](
                    writeFn = (v, w) => ${ writeBody('v, 'w) },
                    readFn = r => ${ readBody('r) },
                    sourceFields = $sourceFields,
                    renamedFields = ${ cfg.renamedFields },
                    droppedFields = ${ cfg.droppedFields },
                    documentation = ${ cfg.documentation },
                    fieldDocs = ${ cfg.fieldDocs },
                    fieldIdOverrides = ${ cfg.fieldIdOverrides },
                    variantNaming = ${ cfg.variantNaming },
                    omitPolicies = ${ cfg.omitPolicies },
                    omitNoneAll = ${ cfg.omitNoneAll },
                    omitEmptyCollectionsAll = ${ cfg.omitEmptyCollectionsAll },
                    fieldMaterializedDefaults = ${ cfg.fieldMaterializedDefaults },
                    fieldTransforms = ${ cfg.fieldTransforms },
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
                val childAnnots = captureAnnotations(children(idx))
                '{
                    kyo.Structure.Variant(
                        ${ Expr(childNames(idx)) },
                        ${ Ref(variantSyms(idx)).asExprOf[Schema[Any]] }.structure,
                        $childAnnots
                    )
                }
            }
            val sumAnnots = captureAnnotations(sym)
            '{
                Structure.Type.Sum(
                    ${ Expr(typeName) },
                    $tagExpr,
                    $tpStructures,
                    kyo.Chunk.from[kyo.Structure.Variant](Array[kyo.Structure.Variant](${ Varargs(variantStructs) }*)),
                    kyo.Chunk.from[String](Array[String](${ Varargs(enumValues.map(Expr(_))) }*)),
                    $sumAnnots
                )
            }
        end structureExpr

        val nameByteDefs: List[ValDef] = (0 until n).toList.map { idx =>
            val nameExpr = Expr(childNames(idx))
            ValDef(nameByteSyms(idx), Some('{ $nameExpr.getBytes(java.nio.charset.StandardCharsets.UTF_8) }.asTerm))
        }

        val variantDecodersExpr: Expr[Chunk[kyo.Codec.Reader => Any]] =
            val perVariant: List[Expr[kyo.Codec.Reader => Any]] = (0 until n).toList.map { idx =>
                '{ (r: kyo.Codec.Reader) => ${ Ref(variantSyms(idx)).asExprOf[Schema[Any]] }.serializeRead(r) }
            }
            '{ kyo.Chunk.from[kyo.Codec.Reader => Any](Array[kyo.Codec.Reader => Any](${ Varargs(perVariant) }*)) }
        end variantDecodersExpr
        val cfg = desugarSumConfig(sym, tpe, children)
        val selfRhs: Expr[Schema[A]] = '{
            Schema.init[A](
                writeFn = (v, w) => ${ writeBody('v, 'w) },
                readFn = r => ${ readBody('r) },
                sourceFields = $sourceFields,
                variantDecoders = $variantDecodersExpr,
                discriminatorField = ${ cfg.discriminatorField },
                representation = ${ cfg.representation },
                variantNaming = ${ cfg.variantNaming },
                documentation = ${ cfg.documentation },
                variantEffectivePrimaries = ${ cfg.variantEffectivePrimaries },
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
