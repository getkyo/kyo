package kyo.internal

import kyo.*
import kyo.Record.*
import scala.quoted.*

/** Macro implementations for Schema[A] transform methods: drop, rename, add, select, flatten.
  *
  * Each transform modifies the Focused type member. The macros validate field names at compile time and compute the new structural type. F
  * is passed as a separate type parameter extracted from Schema.this.Focused at the call site.
  *
  * Runtime behavior:
  *   - drop: removes field from type only, getter/setter unchanged
  *   - rename: changes field name in type, stores rename mapping
  *   - add: adds field to type, stores computed field function
  */
object SchemaTransformMacro:

    private def assertNotSealedTrait[A: Type](opName: String)(using Quotes): Unit =
        import quotes.reflect.*
        val sym = TypeRepr.of[A].typeSymbol
        if sym.flags.is(Flags.Sealed) && !sym.flags.is(Flags.Case) then
            report.errorAndAbort(
                s"Schema.$opName is not supported for sealed traits. " +
                    s"Transforms (drop, rename, add, select, flatten) operate on case class fields. " +
                    s"Apply .$opName to a Schema of a specific case class variant instead."
            )
        end if
    end assertNotSealedTrait

    /** The name of the type a `transformVia` schema is for, as a constant, as the user wrote it.
      *
      * Inside an opaque type's scope the opaque type and its underlying type are the same, and inference instantiates `T` with the
      * underlying one: `given Schema[Port] = summon[Schema[Int]].transformVia(Port.parse)(_.value)` infers `Int`. The definition the
      * call initializes still declares `Schema[Port]`, so its type argument names `T` when it is the same type.
      */
    transparent inline def typeName[T]: String = ${ typeNameImpl[T] }

    def typeNameImpl[T: Type](using Quotes): Expr[String] =
        import quotes.reflect.*
        val tpe                            = TypeRepr.of[T]
        def nameOf(repr: TypeRepr): String =
            repr match
                case ref: TypeRef                         => ref.name
                case AppliedType(constructor: TypeRef, _) => constructor.name
                case other                                => other.typeSymbol.name
        def resultType(repr: TypeRepr): TypeRepr =
            repr match
                case ByNameType(result) => result
                case method: LambdaType => resultType(method.resType)
                case other              => other
        val schemaClass = Symbol.requiredClass("kyo.Schema")
        @scala.annotation.tailrec
        def declared(owner: Symbol): Option[TypeRepr] =
            if owner.isNoSymbol || owner.isClassDef then None
            else
                // The expansion's own synthetic owner and any enclosing lambda declare no Schema, so the walk goes past them.
                val found =
                    if !owner.isValDef && !owner.isDefDef then None
                    else
                        resultType(owner.termRef.widenTermRefByName).baseType(schemaClass) match
                            case AppliedType(_, List(arg)) if arg.dealias =:= tpe.dealias => Some(arg)
                            case _                                                        => None
                if found.nonEmpty then found else declared(owner.maybeOwner)
        Expr(nameOf(declared(Symbol.spliceOwner).getOrElse(tpe)).stripSuffix("$"))
    end typeNameImpl

    /** Implements Schema[A].tagOnly. */
    def tagOnlyImpl[A: Type, F: Type](meta: Expr[Schema[A]])(using Quotes): Expr[Any] =
        import quotes.reflect.*
        val tpe      = TypeRepr.of[A].dealias
        val sym      = tpe.typeSymbol
        val variants = if sym.isClassDef && sym.flags.is(Flags.Sealed) then FocusMacro.sumVariants(tpe, sym) else Nil
        MacroUtils.requireTagOnlyVariants(sym, variants, "Schema.tagOnly", _ => true)
        '{
            Schema.copyWith($meta)(representation = Schema.UnionRepresentation.TagOnly)
                .asInstanceOf[Schema[A] { type Focused = F }]
        }
    end tagOnlyImpl

    /** Implements Schema[A].catchAll("Variant"). */
    def catchAllImpl[A: Type, F: Type](meta: Expr[Schema[A]], variantName: Expr[String], onFailure: Expr[Boolean])(using
        Quotes
    ): Expr[Any] =
        import quotes.reflect.*
        val name = variantName.value.getOrElse(
            report.errorAndAbort("Schema.catchAll takes the variant's Scala name as a literal string.", variantName)
        )
        val tpe = TypeRepr.of[A].dealias
        val sym = tpe.typeSymbol
        if !(sym.isClassDef && sym.flags.is(Flags.Sealed)) then
            report.errorAndAbort(s"Schema.catchAll: ${tpe.show} is not a sealed trait; a catch-all is a variant of a sum.")
        val variants = FocusMacro.sumVariants(tpe, sym)
        val child    = variants.find(_.name.stripSuffix("$") == name).getOrElse(
            report.errorAndAbort(
                s"Schema.catchAll: ${sym.name} has no variant named '$name'. Its variants: " +
                    s"${variants.map(_.name.stripSuffix("$")).mkString(", ")}."
            )
        )
        val carrier = FocusMacro.catchAllCarrier(tpe, sym, child, "Schema.catchAll", onFailure)
        '{
            Schema.copyWith($meta)(catchAll = kyo.Maybe($carrier)).asInstanceOf[Schema[A] { type Focused = F }]
        }
    end catchAllImpl

    /** Implements Schema[A].drop("fieldName").
      *
      * Validates that fieldName exists in F's expanded type, then returns Schema[A] { type Focused = F' } where F' = F minus the named
      * field.
      */
    def dropImpl[A: Type, F: Type](
        meta: Expr[Schema[A]],
        fieldName: Expr[String]
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*

        assertNotSealedTrait[A]("drop")

        val nameStr = MacroUtils.extractStringLiteral(fieldName)
        val fType   = TypeRepr.of[F]

        // Expand F to find the field
        val expanded = ExpandMacro.expandType(fType)

        // Verify field exists
        discard(NavigationMacro.findValueType(expanded, nameStr).getOrElse {
            val available = MacroUtils.collectFields(expanded).map(_._1)
            report.errorAndAbort(
                s"Field '$nameStr' not found. Available fields: ${available.mkString(", ")}."
            )
        })

        // Remove the field from the expanded type
        val newType = MacroUtils.removeField(expanded, nameStr)

        val nameExpr = Expr(nameStr)
        newType.asType match
            case '[f2] =>
                '{
                    Schema.createFrom[A, f2](
                        $meta,
                        $meta.checks,
                        $meta.computedFields,
                        $meta.renamedFields,
                        Set($nameExpr)
                    )
                }
        end match
    end dropImpl

    /** Implements Schema[A].rename("from", "to").
      *
      * Validates that 'from' exists in F and 'to' does not. Returns Schema[A] { type Focused = F' } where F' = (F minus 'from') & ("to" ~
      * ValueType).
      */
    def renameImpl[A: Type, F: Type](
        meta: Expr[Schema[A]],
        from: Expr[String],
        to: Expr[String]
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*

        assertNotSealedTrait[A]("rename")

        val fromStr = MacroUtils.extractStringLiteral(from)
        val toStr   = MacroUtils.extractStringLiteral(to)
        val fType   = TypeRepr.of[F]

        // Expand F to find the field
        val expanded = ExpandMacro.expandType(fType)

        // Verify 'from' field exists and get its value type
        val valueType = NavigationMacro.findValueType(expanded, fromStr).getOrElse {
            val available = MacroUtils.collectFields(expanded).map(_._1)
            report.errorAndAbort(
                s"Field '$fromStr' not found. Available fields: ${available.mkString(", ")}."
            )
        }

        // Verify 'to' field does NOT exist
        NavigationMacro.findValueType(expanded, toStr).foreach { _ =>
            report.errorAndAbort(s"Cannot rename to '$toStr': field already exists in type ${fType.show}")
        }

        // Build new type: (F minus 'from') & ("to" ~ ValueType)
        val withoutField = MacroUtils.removeField(expanded, fromStr)
        val tildeType    = TypeRepr.of[Record.~]
        val toNameType   = ConstantType(StringConstant(toStr))
        val newField     = tildeType.appliedTo(List(toNameType, valueType))
        val newType      =
            if withoutField =:= TypeRepr.of[Any] then newField
            else AndType(withoutField, newField)

        val fromExpr = Expr(fromStr)
        val toExpr   = Expr(toStr)

        newType.asType match
            case '[f2] =>
                '{
                    Schema.createFrom[A, f2](
                        $meta,
                        $meta.checks,
                        $meta.computedFields,
                        $meta.renamedFields :+ ($fromExpr, $toExpr)
                    )
                }
        end match
    end renameImpl

    /** Implements Schema[A].add[V]("name")(f: A => V).
      *
      * Validates that 'name' does NOT already exist in F (Focused). Returns Schema[A] { type Focused = F & ("name" ~ V) }. Stores the
      * computed field function internally.
      */
    def addImpl[A: Type, F: Type, V: Type](
        meta: Expr[Schema[A]],
        name: Expr[String],
        f: Expr[A => V]
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*

        assertNotSealedTrait[A]("add")

        val nameStr = MacroUtils.extractStringLiteral(name)
        val fType   = TypeRepr.of[F]

        if nameStr.isEmpty then
            report.errorAndAbort("Field name must not be empty")

        // Expand F to check for duplicates
        val expanded = ExpandMacro.expandType(fType)

        // Verify field does NOT exist
        NavigationMacro.findValueType(expanded, nameStr).foreach { _ =>
            report.errorAndAbort(s"Cannot add field '$nameStr': field already exists in type ${fType.show}")
        }

        // Build new type: F & ("name" ~ V)
        val tildeType     = TypeRepr.of[Record.~]
        val fieldNameType = ConstantType(StringConstant(nameStr))
        val newField      = tildeType.appliedTo(List(fieldNameType, TypeRepr.of[V]))
        val newType       = AndType(TypeRepr.of[F], newField)

        val nameExpr = Expr(nameStr)

        newType.asType match
            case '[f2] =>
                '{
                    Schema.createFrom[A, f2](
                        $meta,
                        $meta.checks,
                        $meta.computedFields :+ ($nameExpr, $f.asInstanceOf[A => Any]),
                        $meta.renamedFields
                    )
                }
        end match
    end addImpl

    /** Implements Schema[A].select("field1", "field2", ...).
      *
      * Validates that each named field exists in F's (Focused's) expanded type, then returns Schema[A] { type Focused = F' } where F' is
      * the intersection of only the selected fields.
      */
    def selectImpl[A: Type, F: Type](
        meta: Expr[Schema[A]],
        fieldNames: Expr[Seq[String]]
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*

        assertNotSealedTrait[A]("select")

        // Extract string literals from varargs
        val names = fieldNames match
            case Varargs(exprs) =>
                exprs.map(MacroUtils.extractStringLiteral(_)).toList
            case _ =>
                report.errorAndAbort("select requires string literal arguments")

        if names.isEmpty then
            report.errorAndAbort("select requires at least one field name")

        val fType    = TypeRepr.of[F]
        val expanded = ExpandMacro.expandType(fType)

        // Validate each name exists and collect name -> valueType pairs
        val tildeType  = TypeRepr.of[Record.~]
        val fieldTypes = names.map { name =>
            val valueType = NavigationMacro.findValueType(expanded, name).getOrElse {
                val available = MacroUtils.collectFields(expanded).map(_._1)
                report.errorAndAbort(
                    s"Field '$name' not found. Available fields: ${available.mkString(", ")}."
                )
            }
            val nameType = ConstantType(StringConstant(name))
            tildeType.appliedTo(List(nameType, valueType))
        }

        // Build intersection type from kept fields
        val newType = fieldTypes.reduce(AndType(_, _))

        // Compute dropped fields: all fields in F except the selected ones
        val allFieldNames = MacroUtils.collectFields(expanded).map(_._1).toSet
        val selectedSet   = names.toSet
        val droppedNames  = allFieldNames -- selectedSet
        val droppedExpr   = Expr(droppedNames)

        newType.asType match
            case '[f2] =>
                '{
                    Schema.createFrom[A, f2](
                        $meta,
                        $meta.checks,
                        $meta.computedFields,
                        $meta.renamedFields,
                        $droppedExpr
                    )
                }
        end match
    end selectImpl

    /** Implements Schema[A].flatten.
      *
      * For each field in Focused whose value type is a case class, replaces the field with the case class's sub-fields. Primitive and
      * non-case-class fields pass through unchanged. Each flattened field's schema is the one summoned here, and its wire names decide
      * where the flat keys go (`FlattenLayout`). Two fields sharing a name at this level, a flattened field's own name included, cannot
      * share one flat record, so they are rejected here; a collision only the wire names reveal is rejected when the schema is built.
      */
    def flattenImpl[A: Type, F: Type](
        meta: Expr[Schema[A]]
    )(using Quotes): Expr[Any] =
        flattenFieldsImpl[A, F](meta, None)

    /** Flattens the case class fields of F, or only the one named `only`, which must be one. */
    private def flattenFieldsImpl[A: Type, F: Type](
        meta: Expr[Schema[A]],
        only: Option[String]
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*

        assertNotSealedTrait[A]("flatten")

        val fType    = TypeRepr.of[F]
        val expanded = ExpandMacro.expandType(fType)

        // Collect all fields from expanded F
        val fields = MacroUtils.collectFields(expanded)

        def isRecord(valueType: TypeRepr): Boolean =
            val sym = valueType.dealias.typeSymbol
            sym.isClassDef && sym.flags.is(Flags.Case)
        def isSum(valueType: TypeRepr): Boolean =
            val sym = valueType.dealias.typeSymbol
            sym.flags.is(Flags.Sealed) && (sym.flags.is(Flags.Trait) || sym.flags.is(Flags.Abstract) || sym.flags.is(Flags.Enum))
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val maybeSymbol                = TypeRepr.of[Maybe[Any]].typeSymbol
        // Matched by the constructor's symbol: Maybe is opaque, and a quoted `'[Maybe[t]]` pattern does not match it here.
        def maybeOf(valueType: TypeRepr): Option[TypeRepr] =
            valueType.widen match
                case AppliedType(constructor, List(inner)) if constructor.typeSymbol == maybeSymbol => Some(inner)
                case _                                                                              => None
        def isOptionalRecord(valueType: TypeRepr): Boolean = maybeOf(valueType).exists(isRecord)
        only.foreach { name =>
            fields.find(_._1 == name) match
                case Some((_, valueType)) if isRecord(valueType) || isSum(valueType) || isOptionalRecord(valueType) => ()
                case Some((_, valueType)) if maybeOf(valueType).exists(isSum)                                       =>
                    report.errorAndAbort(
                        s"flatten(_.$name): the field '$name' is a ${valueType.show}, an optional sum; only an optional record can move to " +
                            "the parent level, since an absent sum leaves no keys to tell its variants apart."
                    )
                case Some((_, valueType)) =>
                    report.errorAndAbort(
                        s"flatten(_.$name): the field '$name' is a ${valueType.show}, not a case class or a sealed sum; only a record's or a " +
                            "variant's fields can move to the parent level."
                    )
                case None =>
                    report.errorAndAbort(s"flatten(_.$name): no field '$name'. Available fields: ${fields.map(_._1).mkString(", ")}.")
        }

        // A sum's variants are alternatives, so their fields may share names with each other; each one only has to differ from
        // every other field of the parent, since a variant's fields and the parent's fields share one record.
        def variantFields(sum: TypeRepr): List[(String, String)] =
            def children(sym: Symbol): List[Symbol] =
                sym.children.flatMap(c => if c.flags.is(Flags.Sealed) && !c.flags.is(Flags.Case) then children(c) else List(c))
            children(sum.dealias.typeSymbol).flatMap(variant => variant.caseFields.map(f => f.name -> s"${variant.name}.${f.name}"))
        end variantFields

        val tildeType = TypeRepr.of[Record.~]

        // For each field, check if its value type is a case class
        val flattenedSchemas = scala.collection.mutable.ListBuffer.empty[Expr[FlattenedField]]
        val labels           = scala.collection.mutable.ListBuffer.empty[(String, String)]
        val sumLabels        = scala.collection.mutable.ListBuffer.empty[(String, String)]
        def childSchemaOf(valueType: TypeRepr, name: String): Expr[Schema[?]] =
            valueType.asType match
                case '[c] =>
                    Expr.summon[Schema[c]].getOrElse(
                        report.errorAndAbort(s"flatten: no Schema[${valueType.show}] is available for the field '$name'.")
                    )
        // A flattened record is never written under its own name, so the name is no label: one of its fields may carry it.
        def recordLabels(valueType: TypeRepr, name: String): Unit =
            valueType.dealias.typeSymbol.caseFields.foreach(field => labels += field.name -> s"$name.${field.name}")
        val resultFieldEntries = fields.flatMap { (name, valueType) =>
            val sym = valueType.dealias.typeSymbol
            if isRecord(valueType) && only.forall(_ == name) then
                flattenedSchemas += '{ FlattenedField(${ Expr(name) }, ${ childSchemaOf(valueType, name) }, false) }
                recordLabels(valueType, name)
                // Expand the nested case class into its sub-fields
                sym.caseFields.map { field =>
                    val fieldName = field.name
                    val fieldType = valueType.dealias.memberType(field)
                    val nameType  = ConstantType(StringConstant(fieldName))
                    fieldName -> tildeType.appliedTo(List(nameType, fieldType))
                }
            else if isOptionalRecord(valueType) && only.contains(name) then
                // As a flattened sum does, an optional record stays one field of the value: only its keys move on the wire.
                val inner = maybeOf(valueType).get
                flattenedSchemas += '{ FlattenedField(${ Expr(name) }, ${ childSchemaOf(inner, name) }, true) }
                recordLabels(inner, name)
                val nameType = ConstantType(StringConstant(name))
                List(name -> tildeType.appliedTo(List(nameType, valueType)))
            else if isSum(valueType) && only.contains(name) then
                labels += name -> name
                // A flattened sum stays one field of the value; only its keys move on the wire, so Focused keeps it as written.
                flattenedSchemas += '{ FlattenedField(${ Expr(name) }, ${ childSchemaOf(valueType, name) }, false) }
                sumLabels ++= variantFields(valueType).map((field, label) => field -> s"$name.$label")
                val nameType = ConstantType(StringConstant(name))
                List(name -> tildeType.appliedTo(List(nameType, valueType)))
            else
                labels += name -> name
                // Non-case-class: keep as-is
                val nameType = ConstantType(StringConstant(name))
                List(name -> tildeType.appliedTo(List(nameType, valueType)))
            end if
        }
        labels.map(_._1).distinct.foreach { key =>
            val targeting = labels.collect { case (`key`, label) => label }.distinct
            if targeting.size > 1 then
                report.errorAndAbort(
                    s"flatten: Wire name '$key' is targeted by ${targeting.size} fields: ${targeting.mkString(", ")}. " +
                        "Give each field a distinct wire name."
                )
            end if
        }
        sumLabels.foreach { (key, sumLabel) =>
            labels.collectFirst { case (`key`, label) if !sumLabel.startsWith(s"$label.") => label }.foreach { label =>
                report.errorAndAbort(
                    s"flatten: Wire name '$key' is targeted by 2 fields: $label, $sumLabel. Give each field a distinct wire name."
                )
            }
        }
        val resultFields = resultFieldEntries.map(_._2)

        if resultFields.isEmpty then
            // No fields at all, return same type
            meta
        else
            val flattenedSchemasExpr = Expr.ofList(flattenedSchemas.toList)
            val newType              = resultFields.reduce(AndType(_, _))
            newType.asType match
                case '[f2] =>
                    '{
                        Schema.createFrom[A, f2](
                            $meta,
                            $meta.checks,
                            $meta.computedFields,
                            $meta.renamedFields,
                            flattenedFields = Chunk.from($flattenedSchemasExpr)
                        )
                    }
            end match
        end if
    end flattenFieldsImpl

    /** Implements Schema[A].flatten(_.field). */
    def flattenFocusImpl[A: Type, F: Type](
        meta: Expr[Schema[A]],
        focus: Expr[Focus.Select[A, F] => Focus.Select[A, ?]]
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*
        flattenFieldsImpl[A, F](meta, Some(extractFocusFieldName(focus.asTerm)))
    end flattenFocusImpl

    /** Implements Schema[A].foldFields[R](value)(init)(f).
      *
      * Unrolls the fold at compile time so each call to the polymorphic function `f` receives the correct singleton name type N and value
      * type
      *   V. For each case field of A, generates a step function `R => R` that checks runtime transforms and calls `f` with correct types.
      *
      * Runtime transforms (drop, rename, map, add) are handled by wrapping each generated step with the appropriate runtime checks.
      */
    def foldFieldsImpl[A: Type, F: Type, R: Type](
        meta: Expr[Schema[A]],
        value: Expr[A],
        init: Expr[R],
        f: Expr[[N <: String, V] => (R, Field[N, V], V) => R]
    )(using Quotes): Expr[R] =
        import quotes.reflect.*

        val aType = TypeRepr.of[A]
        val sym   = aType.typeSymbol

        if !sym.isClassDef || !sym.flags.is(Flags.Case) then
            report.errorAndAbort(s"foldFields requires a case class type, got: ${aType.show}")

        val caseFields = sym.caseFields

        // Build each step as a function Expr[(R, Product) => R] so all variable
        // references are via parameters, not cross-splice variable capture.
        val stepFns: List[Expr[(R, Product) => R]] = caseFields.zipWithIndex.map { (field, idx) =>
            val fieldName     = field.name
            val fieldType     = aType.memberType(field)
            val nameExpr      = Expr(fieldName)
            val idxExpr       = Expr(idx)
            val nameConstType = ConstantType(StringConstant(fieldName))

            fieldType.asType match
                case '[v] =>
                    val tagExpr = Expr.summon[Tag[v]].getOrElse(
                        report.errorAndAbort(s"Cannot summon Tag for field '$fieldName': ${fieldType.show}")
                    )
                    nameConstType.asType match
                        case '[type n <: String; n] =>
                            '{ (acc: R, product: Product) =>
                                if $meta.droppedFields.contains($nameExpr) then acc
                                else
                                    val isRenamed = $meta.renamedFields.exists(_._1 == $nameExpr)
                                    if isRenamed then acc
                                    else
                                        val rawValue    = product.productElement($idxExpr)
                                        val sourceField = $meta.sourceFields.lift($idxExpr)
                                        val fld         = Field[n, v](
                                            ${ Expr(fieldName).asExprOf[n] },
                                            $tagExpr,
                                            sourceField.map(_.nested).getOrElse(Nil),
                                            sourceField.fold(Maybe.empty[v])(sf => sf.default.asInstanceOf[Maybe[v]])
                                        )
                                        $f[n, v](acc, fld, rawValue.asInstanceOf[v])
                                    end if
                                end if
                            }
                    end match
            end match
        }

        // Chain all step functions together with renamed + computed steps at the end
        '{
            val product = $value.asInstanceOf[Product]
            val theMeta = $meta
            val theF    = $f

            // Apply typed steps for original fields
            var acc = $init
            ${
                Expr.block(
                    stepFns.map(stepFn => '{ acc = $stepFn(acc, product) }),
                    '{ () }
                )
            }

            // Renamed fields (runtime: erased types)
            Schema.resolvedRenames(theMeta.sourceFields.map(_.name), theMeta.renamedFields).foreach { case (sourceName, targetName) =>
                val originalIdx = theMeta.sourceFields.indexWhere(_.name == sourceName)
                if originalIdx >= 0 then
                    val rawValue     = product.productElement(originalIdx)
                    val renamedField = Field[String, Any](targetName, Tag[Any], Nil, Maybe.empty)
                    acc = theF[String, Any](acc, renamedField, rawValue)
                end if
            }

            // Computed fields (runtime: erased types)
            theMeta.computedFields.foreach { case (name, compute) =>
                val computedField = Field[String, Any](name, Tag[Any], Nil, Maybe.empty)
                acc = theF[String, Any](acc, computedField, compute($value))
            }

            acc
        }
    end foldFieldsImpl

    // --- Lambda (Focus) overload implementations ---

    /** Extracts the field name from a Focus lambda at compile time.
      *
      * The lambda `_.fieldName` compiles to a call to `selectDynamic("fieldName")` on the Focus. Since `selectDynamic` is `transparent
      * inline`, the macro sees the unexpanded AST containing `Apply(Select(_, "selectDynamic"), List(Literal(StringConstant(name))))`. This
      * method walks the lambda body to find that call and extract the string constant.
      */
    /** The field names a focus lambda selects, outermost first. Inlining copies each `selectDynamic` call into several trees, so a
      * segment is identified by the position of its name literal, not by its name.
      */
    private def focusPathSegments(using Quotes)(lambda: quotes.reflect.Term): List[String] =
        import quotes.reflect.*
        val found = scala.collection.mutable.LinkedHashMap.empty[Int, String]
        object collector extends TreeTraverser:
            override def traverseTree(tree: Tree)(owner: Symbol): Unit =
                tree match
                    case Apply(TypeApply(Select(_, "selectDynamic"), _), List(lit @ Literal(StringConstant(name)))) =>
                        discard(found.getOrElseUpdate(lit.pos.start, name))
                    case Apply(Select(_, "selectDynamic"), List(lit @ Literal(StringConstant(name)))) =>
                        discard(found.getOrElseUpdate(lit.pos.start, name))
                    case Inlined(Some(call), _, _) =>
                        traverseTree(call)(owner)
                    case _ => ()
                end match
                traverseTreeChildren(tree)(owner)
            end traverseTree
        end collector
        collector.traverseTree(lambda)(Symbol.spliceOwner)
        found.toList.sortBy(_._1).map(_._2)
    end focusPathSegments

    private def extractFocusFieldName(using Quotes)(lambda: quotes.reflect.Term): String =
        import quotes.reflect.*

        // After inline expansion of selectDynamic, the lambda body is wrapped in an
        // Inlined(Some(call), bindings, body) node where `call` preserves the original
        // selectDynamic("fieldName") application from Focus. We search for this pattern.

        def extractFromCall(call: Tree): Option[String] =
            call match
                case Apply(TypeApply(Select(_, "selectDynamic"), _), List(Literal(StringConstant(name)))) =>
                    Some(name)
                case Apply(Select(_, "selectDynamic"), List(Literal(StringConstant(name)))) =>
                    Some(name)
                case _ => None
        end extractFromCall

        def findFieldName(term: Tree): Option[String] =
            term match
                // Inlined with a selectDynamic call source: this is the key pattern
                case Inlined(Some(call), _, body) =>
                    extractFromCall(call).orElse(findFieldName(body))

                // Inlined without call source
                case Inlined(None, _, body) =>
                    findFieldName(body)

                // Pre-inlined selectDynamic
                case Apply(TypeApply(Select(_, "selectDynamic"), _), List(Literal(StringConstant(name)))) =>
                    Some(name)
                case Apply(Select(_, "selectDynamic"), List(Literal(StringConstant(name)))) =>
                    Some(name)

                // Lambda body
                case Lambda(_, body) =>
                    findFieldName(body)

                // Block: check DefDef bodies and the block expr
                case Block(stats, expr) =>
                    stats.flatMap {
                        case ddef: DefDef => ddef.rhs.flatMap(findFieldName)
                        case t: Term      => findFieldName(t)
                        case _            => None
                    }.headOption.orElse(findFieldName(expr))

                case Typed(expr, _) =>
                    findFieldName(expr)

                case _ =>
                    None
            end match
        end findFieldName

        findFieldName(lambda) match
            case Some(name) => name
            case None       =>
                report.errorAndAbort(
                    s"Cannot extract field name from lambda. Use a simple field access like _.fieldName"
                )
        end match
    end extractFocusFieldName

    /** Implements Schema[A].drop(_.field): lambda overload.
      *
      * Extracts the field name from the Focus lambda at compile time, then delegates to the same logic as dropImpl.
      */
    def dropFocusImpl[A: Type, F: Type](
        meta: Expr[Schema[A]],
        focus: Expr[Focus.Select[A, F] => Focus.Select[A, ?]]
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*
        val nameStr = extractFocusFieldName(focus.asTerm)
        dropImpl[A, F](meta, Expr(nameStr))
    end dropFocusImpl

    /** Implements Schema[A].omit(_.field): extracts the field name at compile time and constructs
      * the OmitWhen carrier directly. Returns Schema.OmitWhen[A, F] so the caller can chain
      * .whenEmpty or .whenNone without an intermediate splice.
      */
    def omitFocusImpl[A: Type, F: Type, V: Type](
        meta: Expr[Schema[A]],
        focus: Expr[Focus.Select[A, F] => Focus.Select[A, ?]]
    )(using Quotes): Expr[Schema.OmitWhen[A, F]] =
        import quotes.reflect.*
        val nameStr = extractFocusFieldName(focus.asTerm)
        val schema  = meta.asExprOf[Schema[A] { type Focused = F }]
        '{
            val fieldSchema = scala.compiletime.summonInline[Schema[V]]
            // Materialize the field's compile-time Scala default to a Structure.Value ONCE, through the
            // field's own schema writer (the same path the encode side uses). The default is a constant,
            // so the encode-time whenDefault comparison reuses this value rather than re-materializing it
            // per record. The asInstanceOf[V] is safe: the value comes from Field[?, V].default which the
            // derivation macro produces with the correct V type.
            val materializedDefault: Maybe[Structure.Value] =
                $schema.sourceFields.find(_.name == ${ Expr(nameStr) }) match
                    case Some(field) =>
                        field.default match
                            case Maybe.Present(d) =>
                                val writer = kyo.internal.StructureValueWriter()
                                kyo.internal.writeField[V](fieldSchema, d.asInstanceOf[V], writer)
                                Maybe(writer.getResult)
                            case Maybe.Absent => Maybe.empty
                    case None => Maybe.empty
            new Schema.OmitWhen[A, F]($schema, ${ Expr(nameStr) }, materializedDefault)
        }
    end omitFocusImpl

    def defaultFocusImpl[A: Type, F: Type, V: Type](
        meta: Expr[Schema[A]],
        focus: Expr[Focus.Select[A, F] => Focus.Select[A, V]],
        supplier: Expr[V]
    )(using Quotes): Expr[Schema[A] { type Focused = F }] =
        import quotes.reflect.*
        val nameStr = extractFocusFieldName(focus.asTerm)
        val schema  = meta.asExprOf[Schema[A] { type Focused = F }]
        '{
            val fieldName    = Schema.renamedSource($schema.renamedFields, ${ Expr(nameStr) })
            val fieldSchema  = scala.compiletime.summonInline[Schema[V]]
            val fieldDefault = Schema.FieldDefault(
                () => $supplier,
                (value: Any, writer: Codec.Writer) =>
                    kyo.internal.writeField[V](fieldSchema, value.asInstanceOf[V], writer)
            )
            Schema.copyWith($schema)(
                fieldDefaults = $schema.fieldDefaults.filterNot(_._1 == fieldName) :+ (fieldName -> fieldDefault)
            ).asInstanceOf[Schema[A] { type Focused = F }]
        }
    end defaultFocusImpl

    def transformFieldFocusImpl[A: Type, F: Type, V: Type](
        meta: Expr[Schema[A]],
        focus: Expr[Focus.Select[A, F] => Focus.Select[A, V]],
        write: Expr[(V, Codec.Writer) => Unit],
        read: Expr[Codec.Reader => V]
    )(using Quotes): Expr[Schema[A] { type Focused = F }] =
        transformFieldFocusImpl[A, F, V]("transformField", meta, focus, Maybe(write), Maybe(read))
    end transformFieldFocusImpl

    def transformFieldWriteFocusImpl[A: Type, F: Type, V: Type](
        meta: Expr[Schema[A]],
        focus: Expr[Focus.Select[A, F] => Focus.Select[A, V]],
        write: Expr[(V, Codec.Writer) => Unit]
    )(using Quotes): Expr[Schema[A] { type Focused = F }] =
        transformFieldFocusImpl[A, F, V]("transformFieldWrite", meta, focus, Maybe(write), Maybe.empty)
    end transformFieldWriteFocusImpl

    def transformFieldReadFocusImpl[A: Type, F: Type, V: Type](
        meta: Expr[Schema[A]],
        focus: Expr[Focus.Select[A, F] => Focus.Select[A, V]],
        read: Expr[Codec.Reader => V]
    )(using Quotes): Expr[Schema[A] { type Focused = F }] =
        transformFieldFocusImpl[A, F, V]("transformFieldRead", meta, focus, Maybe.empty, Maybe(read))
    end transformFieldReadFocusImpl

    private def transformFieldFocusImpl[A: Type, F: Type, V: Type](
        opName: String,
        meta: Expr[Schema[A]],
        focus: Expr[Focus.Select[A, F] => Focus.Select[A, V]],
        write: Maybe[Expr[(V, Codec.Writer) => Unit]],
        read: Maybe[Expr[Codec.Reader => V]]
    )(using Quotes): Expr[Schema[A] { type Focused = F }] =
        import quotes.reflect.*
        assertNotSealedTrait[A](opName)
        // A transform is registered under one field name of A and applied to A's own record, so a path into a nested field (or across
        // a sum into a variant) would register it where no record has that field.
        val segments = focusPathSegments(focus.asTerm)
        if segments.size > 1 then
            report.errorAndAbort(
                s"Schema.$opName takes a field of the schema's own type; `_.${segments.mkString(".")}` names a nested field. " +
                    s"Apply .$opName to a Schema of the nested field's type instead."
            )
        end if
        val nameStr                                             = extractFocusFieldName(focus.asTerm)
        val schema                                              = meta.asExprOf[Schema[A] { type Focused = F }]
        val writeExpr: Expr[Maybe[(Any, Codec.Writer) => Unit]] =
            write match
                case Maybe.Present(writeFn) =>
                    '{
                        Maybe((value: Any, writer: Codec.Writer) =>
                            $writeFn(value.asInstanceOf[V], writer)
                        )
                    }
                case Maybe.Absent =>
                    '{ Maybe.empty[(Any, Codec.Writer) => Unit] }
        val readExpr: Expr[Maybe[Codec.Reader => Any]] =
            read match
                case Maybe.Present(readFn) =>
                    '{
                        Maybe((reader: Codec.Reader) => $readFn(reader))
                    }
                case Maybe.Absent =>
                    '{ Maybe.empty[Codec.Reader => Any] }
        val replaceWrite = Expr(write.isDefined)
        val replaceRead  = Expr(read.isDefined)
        '{
            val fieldSchema = scala.compiletime.summonInline[Schema[V]]
            val next        = Schema.FieldTransform[A](
                get = (value: A) =>
                    val selected = $focus($schema.rootSelect)
                    selected.getter(value) match
                        case Maybe.Present(fieldValue) => fieldValue
                        case Maybe.Absent              => kyo.bug("Focused field is not present: " + ${ Expr(nameStr) })
                ,
                write = $writeExpr,
                read = $readExpr,
                writeDerived = (value: Any, writer: Codec.Writer) =>
                    kyo.internal.writeField[V](fieldSchema, value.asInstanceOf[V], writer)
            )
            val merged = Schema.mergeFieldTransform[A](
                $schema.fieldTransforms,
                ${ Expr(nameStr) },
                next,
                $replaceWrite,
                $replaceRead
            )
            Schema.copyWith($schema)(fieldTransforms = merged).asInstanceOf[Schema[A] { type Focused = F }]
        }
    end transformFieldFocusImpl

    /** Implements Schema[A].rename(_.field, "to"): lambda overload.
      *
      * Extracts the field name from the Focus lambda at compile time, then delegates to renameImpl.
      */
    def renameFocusImpl[A: Type, F: Type](
        meta: Expr[Schema[A]],
        focus: Expr[Focus.Select[A, F] => Focus.Select[A, ?]],
        to: Expr[String]
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*
        val nameStr = extractFocusFieldName(focus.asTerm)
        renameImpl[A, F](meta, Expr(nameStr), to)
    end renameFocusImpl

    /** Implements Schema[A].select(_.a, _.b, ...): lambda overload.
      *
      * Extracts field names from each Focus lambda at compile time, then delegates to selectImpl.
      */
    def selectFocusImpl[A: Type, F: Type](
        meta: Expr[Schema[A]],
        focuses: Expr[Seq[Focus.Select[A, F] => Focus.Select[A, ?]]]
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*

        // Extract individual lambda expressions from varargs
        val lambdaExprs = focuses match
            case Varargs(exprs) => exprs
            case _              =>
                report.errorAndAbort("select requires lambda literal arguments")

        if lambdaExprs.isEmpty then
            report.errorAndAbort("select requires at least one field selector")

        // Extract field names from each lambda
        val names = lambdaExprs.map(expr => extractFocusFieldName(expr.asTerm)).toList

        // Build varargs Expr[Seq[String]] from the extracted names
        val nameExprs  = names.map(Expr(_))
        val fieldNames = Varargs(nameExprs)

        selectImpl[A, F](meta, fieldNames)
    end selectFocusImpl

end SchemaTransformMacro
