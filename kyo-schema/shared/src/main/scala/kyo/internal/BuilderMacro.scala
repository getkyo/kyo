package kyo.internal

import kyo.*
import kyo.Record.*
import scala.quoted.*

object BuilderMacro:

    /** Creates a Builder[A, Remaining] where Remaining includes only fields without defaults. */
    def applyImpl[A: Type](using Quotes): Expr[Any] =
        import quotes.reflect.*

        val tpe = TypeRepr.of[A]
        val sym = tpe.typeSymbol

        if !sym.isClassDef || !sym.flags.is(Flags.Case) then
            report.errorAndAbort(
                s"Builder requires a case class type, got: ${tpe.show}. Define ${sym.name} as a case class to use Builder."
            )
        end if

        val caseFields = sym.caseFields

        // Check which fields have defaults
        val fieldsWithoutDefaults = caseFields.zipWithIndex.filter: (field, idx) =>
            !MacroUtils.hasDefault(sym, idx)

        val tildeType = TypeRepr.of[Record.~]

        // Build the construct function: Map[String, Any] => A
        // Use reflect.Lambda to avoid quote-splicing context issues
        val mapType = TypeRepr.of[Map[String, Any]]
        val constructFn: Expr[Map[String, Any] => A] =
            Lambda(
                Symbol.spliceOwner,
                MethodType(List("values"))(
                    _ => List(mapType),
                    _ => tpe
                ),
                (_, params) =>
                    val valuesRef                          = params.head.asInstanceOf[Term]
                    val valuesExpr: Expr[Map[String, Any]] = valuesRef.asExprOf[Map[String, Any]]
                    buildFromValues[A](sym, tpe, caseFields, valuesExpr).asTerm
            ).asExprOf[Map[String, Any] => A]

        if fieldsWithoutDefaults.isEmpty then
            // All fields have defaults, Remaining = Any
            TypeRepr.of[Any].asType match
                case '[r] =>
                    '{ new Builder[A, r](Map.empty, $constructFn) }
        else
            val remainingFields = fieldsWithoutDefaults.map: (field, _) =>
                val nameType  = ConstantType(StringConstant(field.name))
                val fieldType = tpe.memberType(field)
                tildeType.appliedTo(List(nameType, fieldType))

            val remainingType = remainingFields.reduce(AndType(_, _))

            remainingType.asType match
                case '[r] =>
                    '{ new Builder[A, r](Map.empty, $constructFn) }
        end if
    end applyImpl

    /** Builds a Root instance from a values map expression, using defaults for missing fields. */
    private def buildFromValues[Root: Type](using
        Quotes
    )(
        sym: quotes.reflect.Symbol,
        tpe: quotes.reflect.TypeRepr,
        caseFields: List[quotes.reflect.Symbol],
        values: Expr[Map[String, Any]]
    ): Expr[Root] =
        import quotes.reflect.*

        val args: List[Term] = caseFields.zipWithIndex.map: (field, idx) =>
            val fieldName = field.name
            val fieldType = tpe.memberType(field)

            fieldType.asType match
                case '[ft] =>
                    MacroUtils.getDefault(sym, idx) match
                        case Some(defaultExpr) =>
                            val typedDefault = defaultExpr.asExprOf[ft]
                            '{ $values.getOrElse(${ Expr(fieldName) }, $typedDefault).asInstanceOf[ft] }.asTerm
                        case None =>
                            '{ $values(${ Expr(fieldName) }).asInstanceOf[ft] }.asTerm
            end match
        end args

        MacroUtils.constructCaseClass(sym, tpe, args).asExprOf[Root]
    end buildFromValues

    /** Navigates to a field, returning BuilderAt. The field must exist in Root's expanded type. */
    def selectImpl[Root: Type, Remaining: Type, Name <: String: Type](
        builder: Expr[Builder[Root, Remaining]],
        name: Expr[Name]
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*

        val nameStr = NavigationMacro.extractName[Name]

        // Expand Root to find the field type
        val expanded = ExpandMacro.expandType(TypeRepr.of[Root])

        val valueType = NavigationMacro.findValueType(expanded, nameStr).getOrElse {
            val available = MacroUtils.collectFields(expanded).map(_._1)
            report.errorAndAbort(
                s"Field '$nameStr' not found. Available fields: ${available.mkString(", ")}."
            )
        }

        valueType.asType match
            case '[v] =>
                '{ new kyo.internal.BuilderAt[Root, Remaining, v, Name]($builder) }
    end selectImpl

    /** Sets a field value and returns Builder with that field removed from Remaining. */
    def setImpl[Root: Type, Remaining: Type, Focus: Type, Name <: String: Type](
        builderAt: Expr[kyo.internal.BuilderAt[Root, Remaining, Focus, Name]],
        value: Expr[Focus]
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*

        val nameStr = NavigationMacro.extractName[Name]

        // Remove the field from Remaining
        val remainingType = TypeRepr.of[Remaining]
        val newRemaining  = MacroUtils.removeField(remainingType, nameStr)

        newRemaining.asType match
            case '[r] =>
                '{ new Builder[Root, r]($builderAt.builder.values.updated(${ Expr(nameStr) }, $value), $builderAt.builder.construct) }
    end setImpl

    /** Combined selectDynamic + apply: navigates to a field and sets its value in one step. */
    def applyDynamicImpl[Root: Type, Remaining: Type, Name <: String: Type](
        builder: Expr[Builder[Root, Remaining]],
        name: Expr[Name],
        value: Expr[Any]
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*

        val nameStr = NavigationMacro.extractName[Name]

        // Expand Root to find the field type
        val expanded = ExpandMacro.expandType(TypeRepr.of[Root])

        val valueType = NavigationMacro.findValueType(expanded, nameStr).getOrElse {
            val available = MacroUtils.collectFields(expanded).map(_._1)
            report.errorAndAbort(
                s"Field '$nameStr' not found. Available fields: ${available.mkString(", ")}."
            )
        }

        // Remove the field from Remaining
        val remainingType = TypeRepr.of[Remaining]
        val newRemaining  = MacroUtils.removeField(remainingType, nameStr)

        (valueType.asType, newRemaining.asType) match
            case ('[v], '[r]) =>
                '{ new Builder[Root, r]($builder.values.updated(${ Expr(nameStr) }, $value.asInstanceOf[v]), $builder.construct) }
            case _ =>
                report.errorAndAbort(
                    s"Could not resolve the type for field '$nameStr'. " +
                        "Ensure the field type is fully specified and not abstract."
                )
        end match
    end applyDynamicImpl

end BuilderMacro
