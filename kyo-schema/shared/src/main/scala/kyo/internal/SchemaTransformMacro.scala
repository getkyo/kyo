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
        val newType =
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
        val tildeType = TypeRepr.of[Record.~]
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
      * non-case-class fields pass through unchanged.
      */
    def flattenImpl[A: Type, F: Type](
        meta: Expr[Schema[A]]
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*

        assertNotSealedTrait[A]("flatten")

        val fType    = TypeRepr.of[F]
        val expanded = ExpandMacro.expandType(fType)

        // Collect all fields from expanded F
        val fields = MacroUtils.collectFields(expanded)

        val tildeType = TypeRepr.of[Record.~]

        // For each field, check if its value type is a case class
        val resultFields = fields.flatMap { (name, valueType) =>
            val sym = valueType.dealias.typeSymbol
            if sym.isClassDef && sym.flags.is(Flags.Case) then
                // Expand the nested case class into its sub-fields
                val nestedFields = sym.caseFields.map { field =>
                    val fieldName = field.name
                    val fieldType = valueType.dealias.memberType(field)
                    val nameType  = ConstantType(StringConstant(fieldName))
                    tildeType.appliedTo(List(nameType, fieldType))
                }
                nestedFields
            else
                // Non-case-class: keep as-is
                val nameType = ConstantType(StringConstant(name))
                List(tildeType.appliedTo(List(nameType, valueType)))
            end if
        }

        if resultFields.isEmpty then
            // No fields at all, return same type
            meta
        else
            val newType = resultFields.reduce(AndType(_, _))
            newType.asType match
                case '[f2] =>
                    '{
                        Schema.createFrom[A, f2](
                            $meta,
                            $meta.checks,
                            $meta.computedFields,
                            $meta.renamedFields
                        )
                    }
            end match
        end if
    end flattenImpl

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
                                        val fld = Field[n, v](
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

            // Renamed fields (runtime — erased types)
            // Resolve rename chains: name->userName, userName->displayName => name->displayName
            val forwardMap = theMeta.renamedFields.toMap
            def resolveTarget(name: String): String =
                forwardMap.get(name) match
                    case Some(next) => resolveTarget(next)
                    case None       => name
            val resolvedRenames = theMeta.sourceFields.flatMap { sf =>
                if forwardMap.contains(sf.name) then
                    Some((sf.name, resolveTarget(sf.name)))
                else None
            }
            resolvedRenames.foreach { case (sourceName, targetName) =>
                val originalIdx = theMeta.sourceFields.indexWhere(_.name == sourceName)
                if originalIdx >= 0 then
                    val rawValue     = product.productElement(originalIdx)
                    val renamedField = Field[String, Any](targetName, Tag[Any], Nil, Maybe.empty)
                    acc = theF[String, Any](acc, renamedField, rawValue)
                end if
            }

            // Computed fields (runtime — erased types)
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
                // Inlined with a selectDynamic call source — this is the key pattern
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
            case None =>
                report.errorAndAbort(
                    s"Cannot extract field name from lambda. Use a simple field access like _.fieldName"
                )
        end match
    end extractFocusFieldName

    /** Implements Schema[A].drop(_.field) — lambda overload.
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

    /** Implements Schema[A].rename(_.field, "to") — lambda overload.
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

    /** Implements Schema[A].select(_.a, _.b, ...) — lambda overload.
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
            case _ =>
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
