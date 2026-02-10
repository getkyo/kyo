package kyo.internal

import scala.NamedTuple.NamedTuple
import scala.quoted.*

/** Macro utilities for extracting field names from source code and building singleton NamedTuple types.
  *
  * Uses `pos.sourceCode` (same pattern as `Debug.Param`) to extract the field name from the argument's source text.
  *
  * Name extraction rules:
  *   - String literal `"id"` → field name `id`
  *   - Simple identifier `userId` → field name `userId`
  *   - Dotted path `obj.field` → field name `obj_field`
  *   - Method call `f()` → compile error
  *   - String interpolation `s"..."` → compile error
  *   - Empty string `""` → compile error
  */
private[kyo] object CaptureNameMacro:

    /** Extracts a field name from the source code of the given expression. */
    def extractFieldName(name: Expr[String])(using Quotes): String =
        import quotes.reflect.*
        val src = name.asTerm.pos.sourceCode.getOrElse(
            report.errorAndAbort("Cannot determine capture name: source code unavailable")
        )
        if src.startsWith("s\"") || src.startsWith("f\"") || src.startsWith("raw\"") then
            report.errorAndAbort(
                s"String interpolations cannot be used as capture names. Use a string literal or a val instead."
            )
        end if
        // Extract contiguous word-character runs (letters, digits, underscore) and join with _
        val fieldName = src.split("[^a-zA-Z0-9_]+").filter(_.nonEmpty).mkString("_")
        if fieldName.isEmpty then
            report.errorAndAbort("Capture name cannot be empty")
        fieldName
    end extractFieldName

    /** Extracts field names from a NamedTuple type. Returns Nil for EmptyTuple or non-NamedTuple types. */
    private def getFieldNames[T: Type](using Quotes): List[String] =
        import quotes.reflect.*

        def extractNames(tupleType: TypeRepr): List[String] =
            tupleType.dealias match
                case tp if tp =:= TypeRepr.of[EmptyTuple] => Nil
                case AppliedType(_, args) if tupleType.dealias <:< TypeRepr.of[NonEmptyTuple] =>
                    val headName = args.head.dealias match
                        case ConstantType(StringConstant(str)) => List(str)
                        case _                                 => Nil
                    headName ++ extractNames(args(1))
                case AppliedType(_, args) if args.size == 2 =>
                    // Handles Tuple.Concat[A, B] which may not be reduced
                    extractNames(args(0)) ++ extractNames(args(1))
                case _ => Nil
        end extractNames

        val repr = TypeRepr.of[T]
        if repr =:= TypeRepr.of[EmptyTuple] then Nil
        else
            val namedTupleSym = TypeRepr.of[NamedTuple[EmptyTuple, EmptyTuple]].typeSymbol
            repr.dealias match
                case AppliedType(tycon, List(names, _)) if tycon.typeSymbol.fullName == namedTupleSym.fullName =>
                    extractNames(names)
                case _ => Nil
            end match
        end if
    end getFieldNames

    private def duplicateFieldError(fieldName: String, existingNames: List[String])(using Quotes): Nothing =
        import quotes.reflect.*
        val fields = existingNames.mkString(", ")
        report.errorAndAbort(
            s"""Duplicate input name '$fieldName'. Current input fields: ($fields)
               |
               |Route methods build a named tuple of handler inputs. Each method adds a field:
               |  Path captures   HttpPath.int("x"), .string("x"), ...  → field 'x'
               |  .query("x")    .header("x"), .cookie("x")            → field 'x'
               |  .input[T]      .inputText, .inputForm[T]             → field 'body'
               |  .inputMultipart                                       → field 'parts'
               |  .authBearer                                           → field 'bearer'
               |  .authBasic                                            → fields 'username', 'password'
               |  .authApiKey("x")                                      → field 'x'
               |
               |All field names must be unique. Rename the conflicting parameter to resolve.""".stripMargin
        )
    end duplicateFieldError

    // --- Core implementation ---

    /** Core: adds one named field to a route's In type. fieldName is already resolved. */
    private def addFieldImpl[Self: Type, In: Type, Out: Type, Err: Type, A: Type](
        value: Expr[Any],
        fieldName: String
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*

        // Check for duplicate field name
        val existingNames = getFieldNames[In]
        if existingNames.contains(fieldName) then
            duplicateFieldError(fieldName, existingNames)
        end if

        val singletonType = ConstantType(StringConstant(fieldName))
        singletonType.asType match
            case '[n] =>
                val fieldType = TypeRepr.of[NamedTuple[n *: EmptyTuple, A *: EmptyTuple]]
                val inRepr    = TypeRepr.of[In]

                val combinedType =
                    if inRepr =:= TypeRepr.of[EmptyTuple] then
                        fieldType
                    else
                        val namedTupleSym = TypeRepr.of[NamedTuple[EmptyTuple, EmptyTuple]].typeSymbol
                        inRepr.dealias match
                            case AppliedType(tycon, List(names, values)) if tycon.typeSymbol.fullName == namedTupleSym.fullName =>
                                val concatNames  = TypeRepr.of[Tuple.Concat].appliedTo(List(names, TypeRepr.of[n *: EmptyTuple]))
                                val concatValues = TypeRepr.of[Tuple.Concat].appliedTo(List(values, TypeRepr.of[A *: EmptyTuple]))
                                TypeRepr.of[NamedTuple].appliedTo(List(concatNames, concatValues))
                            case _ =>
                                report.errorAndAbort(
                                    s"Cannot add field '$fieldName' to route inputs. " +
                                        s"In type ${Type.show[In]} is not EmptyTuple or NamedTuple."
                                )
                        end match

                val selfRepr = TypeRepr.of[Self]
                val typeCtor = selfRepr match
                    case AppliedType(tycon, _) => tycon
                    case _                     => report.errorAndAbort(s"Expected applied type, got: ${selfRepr.show}")
                val resultType = typeCtor.appliedTo(List(
                    combinedType,
                    TypeRepr.of[Out],
                    TypeRepr.of[Err]
                ))
                resultType.asType match
                    case '[result] =>
                        '{ $value.asInstanceOf[result] }
        end match
    end addFieldImpl

    // --- Public API ---

    /** Retypes a value from `Self[Any]` to `Self[NamedTuple[fieldName, T]]`.
      *
      * Used by HttpPath capture methods. Extracts field name from source code.
      */
    def retype[Self: Type, T: Type](value: Expr[Any], name: Expr[String])(using Quotes): Expr[Any] =
        import quotes.reflect.*
        val fieldName     = extractFieldName(name)
        val singletonType = ConstantType(StringConstant(fieldName))
        singletonType.asType match
            case '[n] =>
                val selfRepr = TypeRepr.of[Self]
                val typeCtor = selfRepr match
                    case AppliedType(tycon, _) => tycon
                    case _                     => report.errorAndAbort(s"Expected applied type, got: ${selfRepr.show}")
                val namedTupleType = TypeRepr.of[NamedTuple[n *: EmptyTuple, T *: EmptyTuple]]
                val resultType     = typeCtor.appliedTo(List(namedTupleType))
                resultType.asType match
                    case '[result] =>
                        '{ $value.asInstanceOf[result] }
        end match
    end retype

    /** Adds a named field to a route. Extracts field name from source code via extractFieldName.
      *
      * Used by HttpRoute.query/header/cookie/authApiKey — user-facing methods where the name comes from source.
      */
    def addField[Self: Type, In: Type, Out: Type, Err: Type, A: Type](
        value: Expr[Any],
        name: Expr[String]
    )(using Quotes): Expr[Any] =
        addFieldImpl[Self, In, Out, Err, A](value, extractFieldName(name))

    /** Adds a named field to a route with a fixed field name (no source-code extraction).
      *
      * Used by HttpRoute.input/inputText/inputForm/inputMultipart/authBearer — methods with known field names.
      */
    def addFieldFixed[Self: Type, In: Type, Out: Type, Err: Type, A: Type](
        value: Expr[Any],
        fieldName: String
    )(using Quotes): Expr[Any] =
        addFieldImpl[Self, In, Out, Err, A](value, fieldName)

    /** Adds two named fields at once with fixed names. Used by authBasic (username, password). */
    def addTwoFieldsFixed[Self: Type, In: Type, Out: Type, Err: Type, A: Type, B: Type](
        value: Expr[Any],
        fieldName1: String,
        fieldName2: String
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*

        // Check for duplicate field names
        val existingNames = getFieldNames[In]
        if existingNames.contains(fieldName1) then
            duplicateFieldError(fieldName1, existingNames)
        end if
        if existingNames.contains(fieldName2) then
            duplicateFieldError(fieldName2, existingNames)
        end if

        val singleton1 = ConstantType(StringConstant(fieldName1))
        val singleton2 = ConstantType(StringConstant(fieldName2))
        singleton1.asType match
            case '[n1] => singleton2.asType match
                    case '[n2] =>
                        val twoFieldType = TypeRepr.of[NamedTuple[n1 *: n2 *: EmptyTuple, A *: B *: EmptyTuple]]
                        val inRepr       = TypeRepr.of[In]

                        val combinedType =
                            if inRepr =:= TypeRepr.of[EmptyTuple] then
                                twoFieldType
                            else
                                val namedTupleSym = TypeRepr.of[NamedTuple[EmptyTuple, EmptyTuple]].typeSymbol
                                inRepr.dealias match
                                    case AppliedType(tycon, List(names, values))
                                        if tycon.typeSymbol.fullName == namedTupleSym.fullName =>
                                        val concatNames =
                                            TypeRepr.of[Tuple.Concat].appliedTo(List(names, TypeRepr.of[n1 *: n2 *: EmptyTuple]))
                                        val concatValues =
                                            TypeRepr.of[Tuple.Concat].appliedTo(List(values, TypeRepr.of[A *: B *: EmptyTuple]))
                                        TypeRepr.of[NamedTuple].appliedTo(List(concatNames, concatValues))
                                    case _ =>
                                        report.errorAndAbort(
                                            s"Cannot add fields '$fieldName1', '$fieldName2' to route inputs. " +
                                                s"In type ${Type.show[In]} is not EmptyTuple or NamedTuple."
                                        )
                                end match

                        val selfRepr = TypeRepr.of[Self]
                        val typeCtor = selfRepr match
                            case AppliedType(tycon, _) => tycon
                            case _                     => report.errorAndAbort(s"Expected applied type, got: ${selfRepr.show}")
                        val resultType = typeCtor.appliedTo(List(
                            combinedType,
                            TypeRepr.of[Out],
                            TypeRepr.of[Err]
                        ))
                        resultType.asType match
                            case '[result] =>
                                '{ $value.asInstanceOf[result] }
        end match
    end addTwoFieldsFixed

end CaptureNameMacro
