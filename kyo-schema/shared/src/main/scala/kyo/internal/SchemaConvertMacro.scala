package kyo.internal

import kyo.*
import kyo.Record.*
import scala.quoted.*

/** Macro implementations for Convert[A, B] and Schema[A].convert[B].
  *
  * `autoImpl` derives a Convert[A, B] directly by generating a forward (A => B) function.
  *
  * `toTransformedImpl` matches fields from the *transformed* structural type Focused (passed as F) to B's constructor parameters. This is
  * critical for correctness: after transforms like drop/rename/add/map, Focused reflects the actual available fields, not A's original
  * fields.
  */
private[kyo] object SchemaConvertMacro:

    /** Derives a `Convert[A, B]` directly by generating a forward (A => B) total function.
      *
      * The forward direction is total (always succeeds). Validated at compile time.
      */
    def autoImpl[A: Type, B: Type](using Quotes): Expr[Convert[A, B]] =
        import quotes.reflect.*

        def validateAndGetFields(tpeSrc: TypeRepr, tpeTgt: TypeRepr): (Map[String, TypeRepr], List[Symbol]) =
            val symSrc = tpeSrc.typeSymbol
            val symTgt = tpeTgt.typeSymbol

            if !symSrc.isClassDef || !symSrc.flags.is(Flags.Case) then
                report.errorAndAbort(s"Convert requires a case class for source type, got: ${tpeSrc.show}")
            if !symTgt.isClassDef || !symTgt.flags.is(Flags.Case) then
                report.errorAndAbort(s"Convert requires a case class for target type, got: ${tpeTgt.show}")

            val fieldsSrc = symSrc.caseFields.map(f => f.name -> tpeSrc.memberType(f)).toMap
            val fieldsTgt = symTgt.caseFields

            fieldsTgt.zipWithIndex.foreach: (field, idx) =>
                val fieldName  = field.name
                val fieldTypeT = tpeTgt.memberType(field)

                fieldsSrc.get(fieldName) match
                    case Some(fieldTypeS) =>
                        if !(fieldTypeS =:= fieldTypeT) then
                            report.errorAndAbort(
                                s"Convert: field '$fieldName' has type ${fieldTypeS.show} in ${tpeSrc.show} but type ${fieldTypeT.show} in ${tpeTgt.show}. " +
                                    "Change one of the types to match, or use Schema.map to transform the field before converting."
                            )
                    case None =>
                        if !MacroUtils.hasDefault(symTgt, idx) then
                            val availableFields = fieldsSrc.keys.toList.sorted
                            report.errorAndAbort(
                                s"Convert: field '$fieldName' in ${tpeTgt.show} has no corresponding field in ${tpeSrc.show} and no default value. " +
                                    s"Available fields in ${tpeSrc.show}: ${availableFields.mkString(", ")}. " +
                                    "Add a matching field to the source type, add a default value to the target field, or use Schema.rename/add to create the field."
                            )
                        end if
                end match

            (fieldsSrc, fieldsTgt)
        end validateAndGetFields

        def generateArgs(
            fieldsMap: Map[String, TypeRepr],
            targetFields: List[Symbol],
            targetType: TypeRepr,
            srcExpr: Term
        ): List[Term] =
            targetFields.zipWithIndex.map: (field, idx) =>
                val fieldName = field.name
                val fieldType = targetType.memberType(field)

                fieldType.asType match
                    case '[ft] =>
                        fieldsMap.get(fieldName) match
                            case Some(_) =>
                                Select.unique(srcExpr, fieldName)
                            case None =>
                                MacroUtils.getDefault(targetType.typeSymbol, idx).get.asExprOf[ft].asTerm
                end match
        end generateArgs

        def generateApply(targetType: TypeRepr, args: List[Term]): Term =
            MacroUtils.constructCaseClass(targetType.typeSymbol, targetType, args)
        end generateApply

        val tpeA = TypeRepr.of[A].dealias
        val tpeB = TypeRepr.of[B].dealias

        // Validate forward direction only (A => B)
        val (fieldsA, targetFieldsB) = validateAndGetFields(tpeA, tpeB)

        '{
            new Convert[A, B]((a: A) =>
                ${
                    val args = generateArgs(fieldsA, targetFieldsB, tpeB, '{ a }.asTerm)
                    generateApply(tpeB, args).asExprOf[B]
                }
            )
        }
    end autoImpl

    /** Generates a `Convert[A, B]` that:
      *   1. Calls `meta.resultOf(a)` to get a `Record[Focused]` with all transforms applied
      *   2. Constructs B by pulling each of B's constructor params from the Record's dict
      *
      * Validates at compile time that all B fields exist in Focused (or have defaults in B).
      */
    def toTransformedImpl[A: Type, F: Type, B: Type](
        meta: Expr[Schema[A]]
    )(using Quotes): Expr[Convert[A, B]] =
        import quotes.reflect.*

        val tpeF = TypeRepr.of[F]
        val tpeB = TypeRepr.of[B].dealias
        val symB = tpeB.typeSymbol

        // B must be a case class
        if !symB.isClassDef || !symB.flags.is(Flags.Case) then
            report.errorAndAbort(s"Schema.convert requires a case class for target type, got: ${tpeB.show}")

        // Extract F's fields from the structural type
        val expandedF = ExpandMacro.expandType(tpeF)
        val fieldsF   = MacroUtils.collectFields(expandedF)
        val fieldMapF = fieldsF.toMap // name -> TypeRepr

        val fieldsB = symB.caseFields

        // Validate all B fields can be satisfied
        fieldsB.zipWithIndex.foreach: (field, idx) =>
            val fieldName  = field.name
            val fieldTypeB = tpeB.memberType(field)

            fieldMapF.get(fieldName) match
                case Some(fieldTypeF) =>
                    if !(fieldTypeF =:= fieldTypeB) then
                        report.errorAndAbort(
                            s"Schema.convert: field '$fieldName' has type ${fieldTypeF.show} in transformed type but type ${fieldTypeB.show} in ${tpeB.show}. " +
                                "Change one of the types to match, or use Schema.map to transform the field before converting."
                        )
                case None =>
                    // Check if field has a default in B
                    if !MacroUtils.hasDefault(symB, idx) then
                        report.errorAndAbort(
                            s"Schema.convert: field '$fieldName' in ${tpeB.show} has no corresponding field in transformed type and no default value. " +
                                s"Available fields: ${fieldsF.map(_._1).mkString(", ")}"
                        )
                    end if
            end match

        // Summon A <:< Product evidence (case classes always extend Product)
        val evExpr = Expr.summon[A <:< Product].getOrElse(
            report.errorAndAbort(s"Schema.convert requires A to extend Product, but ${TypeRepr.of[A].show} does not")
        )

        // Generate: (a: A) => { val rec = meta.resultOf(a).asInstanceOf[Record[Any]]; B(rec.dict("f1").asInstanceOf[T1], ...) }
        // Cast through Record[Any] to avoid path-dependent type issues with m.Focused
        '{
            val m                     = $meta
            given ev: (A <:< Product) = $evExpr
            new Convert[A, B]((a: A) =>
                val rec = m.resultOf(a).asInstanceOf[Record[Any]]
                ${
                    // Cannot use MacroUtils.constructCaseClass or MacroUtils.getDefault here:
                    // symB/tpeB/fieldsB are bound to the outer Quotes instance, but this splice
                    // introduces a new Quotes context, causing path-dependent type mismatch.
                    val companion = Ref(symB.companionModule)
                    val args: List[Term] = fieldsB.zipWithIndex.map: (field, idx) =>
                        val fieldName  = field.name
                        val fieldTypeB = tpeB.memberType(field)

                        fieldTypeB.asType match
                            case '[ft] =>
                                fieldMapF.get(fieldName) match
                                    case Some(_) =>
                                        // Field exists in F -- pull from record dict
                                        '{ rec.dict(${ Expr(fieldName) }).asInstanceOf[ft] }.asTerm
                                    case None =>
                                        // Field has default -- use it
                                        val defaultMethodName = s"$$lessinit$$greater$$default$$${idx + 1}"
                                        val defaultMethods    = symB.companionModule.methodMember(defaultMethodName)
                                        Ref(symB.companionModule).select(defaultMethods.head).asExprOf[ft].asTerm
                        end match

                    val typeArgs = tpeB match
                        case AppliedType(_, args) => args
                        case _                    => List.empty
                    Select.overloaded(companion, "apply", typeArgs, args).asExprOf[B]
                }
            )
        }
    end toTransformedImpl

end SchemaConvertMacro
