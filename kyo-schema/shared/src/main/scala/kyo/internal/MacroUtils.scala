package kyo.internal

import kyo.*
import kyo.Record.*
import scala.quoted.*

/** Shared macro utility methods used across multiple macro files in kyo-schema.
  *
  * Contains structural type checks, field operations, string literal extraction, Maybe/Option field detection, case class default
  * detection, and constructor call generation.
  */
private[internal] object MacroUtils:

    // ---- Structural type checks ----

    /** Checks if a type is a structural type (composed of ~ applications via & or |). Uses .dealias. */
    private[internal] def isStructuralType(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
        import quotes.reflect.*
        tpe.dealias match
            case AndType(l, r) => isStructuralComponent(l) && isStructuralComponent(r)
            case OrType(l, r)  => isStructuralComponent(l) && isStructuralComponent(r)
            case _             => isTildeApplication(tpe)
        end match
    end isStructuralType

    private[internal] def isStructuralComponent(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
        import quotes.reflect.*
        tpe.dealias match
            case AndType(l, r) => isStructuralComponent(l) && isStructuralComponent(r)
            case OrType(l, r)  => isStructuralComponent(l) && isStructuralComponent(r)
            case _             => isTildeApplication(tpe)
        end match
    end isStructuralComponent

    private[internal] def isTildeApplication(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
        import quotes.reflect.*
        tpe.dealias match
            case AppliedType(tycon, List(ConstantType(StringConstant(_)), _)) =>
                tycon =:= TypeRepr.of[Record.~]
            case _ => false
        end match
    end isTildeApplication

    // ---- Field operations ----

    /** Removes a field from an intersection type (structural type). Returns Any if the type becomes empty. */
    private[internal] def removeField(using Quotes)(tpe: quotes.reflect.TypeRepr, nameStr: String): quotes.reflect.TypeRepr =
        import quotes.reflect.*
        tpe.dealias match
            case AndType(l, r) =>
                val lHasField = containsField(l, nameStr)
                val rHasField = containsField(r, nameStr)
                if lHasField && !rHasField then
                    val newL = removeField(l, nameStr)
                    if newL =:= TypeRepr.of[Any] then r
                    else AndType(newL, r)
                else if !lHasField && rHasField then
                    val newR = removeField(r, nameStr)
                    if newR =:= TypeRepr.of[Any] then l
                    else AndType(l, newR)
                else if lHasField && rHasField then
                    val newL = removeField(l, nameStr)
                    if newL =:= TypeRepr.of[Any] then r
                    else AndType(newL, r)
                else
                    tpe
                end if
            case AppliedType(_, List(ConstantType(StringConstant(n)), _)) if n == nameStr =>
                TypeRepr.of[Any]
            case _ =>
                tpe
        end match
    end removeField

    /** Checks if a type contains a field with the given name. */
    private[internal] def containsField(using Quotes)(tpe: quotes.reflect.TypeRepr, nameStr: String): Boolean =
        import quotes.reflect.*
        tpe.dealias match
            case AndType(l, r) =>
                containsField(l, nameStr) || containsField(r, nameStr)
            case AppliedType(_, List(ConstantType(StringConstant(n)), _)) if n == nameStr =>
                true
            case _ =>
                false
        end match
    end containsField

    /** Collects all (name, valueType) pairs from an expanded structural type. */
    private[internal] def collectFields(using Quotes)(tpe: quotes.reflect.TypeRepr): List[(String, quotes.reflect.TypeRepr)] =
        import quotes.reflect.*
        tpe.dealias match
            case AndType(l, r) =>
                collectFields(l) ++ collectFields(r)
            case AppliedType(_, List(ConstantType(StringConstant(name)), valueType)) =>
                List((name, valueType))
            case _ =>
                Nil
        end match
    end collectFields

    // ---- String literal extraction ----

    /** Extracts a string literal from an Expr[String] at compile time. */
    private[internal] def extractStringLiteral(using Quotes)(expr: Expr[String]): String =
        import quotes.reflect.*
        expr.asTerm match
            case Inlined(_, _, Literal(StringConstant(s))) => s
            case Literal(StringConstant(s))                => s
            case _ => report.errorAndAbort(
                    "Transform field name must be a string literal"
                )
        end match
    end extractStringLiteral

    // ---- Maybe/Option field detection ----

    /** Detects which fields are Maybe[?] or Option[?] types.
      *
      * Returns a tuple of (maybeFieldIndices, optionFieldIndices).
      */
    private[internal] def detectMaybeOptionFields(using
        Quotes
    )(
        tpe: quotes.reflect.TypeRepr,
        fields: List[quotes.reflect.Symbol]
    ): (Set[Int], Set[Int]) =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val maybeSym                   = TypeRepr.of[kyo.Maybe[Any]].typeSymbol
        val optionSym                  = TypeRepr.of[Option[Any]].typeSymbol
        val maybeFields: Set[Int] = fields.zipWithIndex.collect {
            case (field, idx) if tpe.memberType(field).dealias match
                    case AppliedType(tycon, _) => tycon.typeSymbol == maybeSym
                    case _                     => false
                =>
                idx
        }.toSet
        val optionFields: Set[Int] = fields.zipWithIndex.collect {
            case (field, idx) if tpe.memberType(field).dealias match
                    case AppliedType(tycon, _) => tycon.typeSymbol == optionSym
                    case _                     => false
                =>
                idx
        }.toSet
        (maybeFields, optionFields)
    end detectMaybeOptionFields

    // ---- Schema-driven field classifier ----

    /** Schema-driven field kind classifier.
      *
      * Macro consumers read structure from Schema, not from symbol sets.
      *
      * Classification strategy:
      * 1. For the known built-in kyo-schema primitive types (Int, String, Boolean,
      *    Double, Float, Long, Short, Byte, Char, Unit, BigDecimal, BigInt and their
      *    Java equivalents): use TypeRepr symbol comparison. The hand-written Schema
      *    givens for primitives are non-inline, so AST inspection cannot extract their
      *    structure at compile time. TypeRepr comparison is the reliable path.
      * 2. For the known built-in container, optional, and mapping type constructors:
      *    TypeRepr comparison on the applied tycon symbol (same reason).
      * 3. For case classes and sealed traits: ProductOrSum (TypeRepr shape).
      * 4. For other applied types with recognised tycons not in (1)/(2): Generic.
      * 5. For other types: Generic.
      *
      * Note: `fieldKindFor` never calls `Expr.summon[Schema[T]]` because doing so
      * inside a macro that is itself invoked during Schema derivation causes a
      * Scala 3 `SuspendException` (circular macro invocation). The classification
      * is based entirely on TypeRepr shape, which is identical to what the deleted
      * symbol sets did. `readStructureVariant` is provided for callers that already
      * hold an Expr[Schema[?]] and want structure AST inspection (e.g. tests).
      */
    private[internal] object MacroSchemaClassifier:
        enum FieldKind derives CanEqual:
            case Primitive(kind: kyo.Structure.PrimitiveKind)
            case Collection
            case Optional
            case Mapping
            case ProductOrSum
            case Open
            case Generic // unrecognised type
        end FieldKind

        def fieldKindFor(using Quotes)(tpe: quotes.reflect.TypeRepr): FieldKind =
            import quotes.reflect.*
            given CanEqual[Symbol, Symbol] = CanEqual.derived
            val dealiased                  = tpe.dealias

            // Step 1: Check known primitive types by TypeRepr symbol.
            primitiveKind(dealiased) match
                case Some(k) => return FieldKind.Primitive(k)
                case None    =>

            // Step 2: Check known container / optional / mapping type constructors.
            dealiased match
                case AppliedType(tycon, _) =>
                    val sym = tycon.typeSymbol
                    if sym == TypeRepr.of[List].typeSymbol ||
                        sym == TypeRepr.of[Seq].typeSymbol ||
                        sym == TypeRepr.of[Vector].typeSymbol ||
                        sym == TypeRepr.of[Set].typeSymbol ||
                        sym == TypeRepr.of[kyo.Chunk].typeSymbol
                    then return FieldKind.Collection
                    else if sym == TypeRepr.of[Option].typeSymbol ||
                        sym == TypeRepr.of[kyo.Maybe].typeSymbol
                    then return FieldKind.Optional
                    else if sym == TypeRepr.of[Map].typeSymbol then return FieldKind.Mapping
                    end if
                case _ =>
            end match

            // Step 3: Case class or sealed trait => ProductOrSum.
            val sym = dealiased.typeSymbol
            if sym.isClassDef && (sym.flags.is(Flags.Case) || sym.flags.is(Flags.Sealed))
            then FieldKind.ProductOrSum
            // Step 4 and 5: everything else => Generic.
            else FieldKind.Generic
            end if
        end fieldKindFor

        /** Maps a TypeRepr to its `Structure.PrimitiveKind` if it is one of the extended
          * primitive types; returns None otherwise.
          */
        def primitiveKind(using Quotes)(tpe: quotes.reflect.TypeRepr): Option[kyo.Structure.PrimitiveKind] =
            import quotes.reflect.*
            given CanEqual[Symbol, Symbol] = CanEqual.derived
            val sym                        = tpe.dealias.typeSymbol
            if sym == TypeRepr.of[Int].typeSymbol then Some(kyo.Structure.PrimitiveKind.Int)
            else if sym == TypeRepr.of[Long].typeSymbol then Some(kyo.Structure.PrimitiveKind.Long)
            else if sym == TypeRepr.of[Short].typeSymbol then Some(kyo.Structure.PrimitiveKind.Short)
            else if sym == TypeRepr.of[Byte].typeSymbol then Some(kyo.Structure.PrimitiveKind.Byte)
            else if sym == TypeRepr.of[Char].typeSymbol then Some(kyo.Structure.PrimitiveKind.Char)
            else if sym == TypeRepr.of[Float].typeSymbol then Some(kyo.Structure.PrimitiveKind.Float)
            else if sym == TypeRepr.of[Double].typeSymbol then Some(kyo.Structure.PrimitiveKind.Double)
            else if sym == TypeRepr.of[String].typeSymbol then Some(kyo.Structure.PrimitiveKind.String)
            else if sym == TypeRepr.of[Boolean].typeSymbol then Some(kyo.Structure.PrimitiveKind.Boolean)
            else if sym == TypeRepr.of[Unit].typeSymbol then Some(kyo.Structure.PrimitiveKind.Unit)
            else if sym == TypeRepr.of[BigInt].typeSymbol ||
                sym == TypeRepr.of[java.math.BigInteger].typeSymbol
            then Some(kyo.Structure.PrimitiveKind.BigInt)
            else if sym == TypeRepr.of[BigDecimal].typeSymbol ||
                sym == TypeRepr.of[java.math.BigDecimal].typeSymbol
            then Some(kyo.Structure.PrimitiveKind.BigDecimal)
            else None
            end if
        end primitiveKind

        /** Reads the Structure.Type variant from an Inlined schema expression produced by
          * `inline given derived`. Only works when the schema is an inline given whose body is
          * expanded at the call site: the underlying term must be an Apply-of-New for a
          * Structure.Type case-class constructor.
          *
          * For non-inline givens (e.g. intSchema, listSchema) the underlying term is a
          * method call, not a constructor, so this method returns FieldKind.Generic.
          * Callers that need to classify those types should use fieldKindFor instead.
          */
        def readStructureVariant(using Quotes)(schemaExpr: Expr[kyo.Schema[?]]): FieldKind =
            import quotes.reflect.*
            val structureExpr = '{ ${ schemaExpr }.structure }
            structureExpr.asTerm.underlyingArgument match
                case Apply(Select(New(t), _), _)
                    if t.tpe =:= TypeRepr.of[kyo.Structure.Type.Primitive] =>
                    // Use primitiveKind by examining the schema expression's type argument
                    // to avoid relying on the non-literal kindArg term.
                    schemaExpr.asTerm.tpe match
                        case AppliedType(_, List(underlying)) =>
                            primitiveKind(underlying) match
                                case Some(k) => FieldKind.Primitive(k)
                                case None    => FieldKind.Generic
                        case _ => FieldKind.Generic
                case Apply(Select(New(t), _), _)
                    if t.tpe =:= TypeRepr.of[kyo.Structure.Type.Collection] =>
                    FieldKind.Collection
                case Apply(Select(New(t), _), _)
                    if t.tpe =:= TypeRepr.of[kyo.Structure.Type.Optional] =>
                    FieldKind.Optional
                case Apply(Select(New(t), _), _)
                    if t.tpe =:= TypeRepr.of[kyo.Structure.Type.Mapping] =>
                    FieldKind.Mapping
                case Apply(Select(New(t), _), _)
                    if t.tpe =:= TypeRepr.of[kyo.Structure.Type.Product] ||
                        t.tpe =:= TypeRepr.of[kyo.Structure.Type.Sum] =>
                    FieldKind.ProductOrSum
                case Apply(Select(New(t), _), _)
                    if t.tpe =:= TypeRepr.of[kyo.Structure.Type.Open] =>
                    FieldKind.Open
                case _ => FieldKind.Generic
            end match
        end readStructureVariant
    end MacroSchemaClassifier

    // ---- Case class default detection ----

    /** Checks if a case class field at the given index has a default value. */
    private[internal] def hasDefault(using Quotes)(sym: quotes.reflect.Symbol, idx: Int): Boolean =
        import quotes.reflect.*
        val defaultMethodName = s"$$lessinit$$greater$$default$$${idx + 1}"
        sym.companionModule.methodMember(defaultMethodName).nonEmpty
    end hasDefault

    /** Gets the default value expression for a case class field at the given index, if any.
      *
      * For generic case classes, the generated default-value method is itself type-parameterized (`<init>$default$N[A, ...]`); we must
      * apply the case class's type arguments to the method reference before treating it as an expression, otherwise `asExprOf` raises
      * "Expected an expression. This is a partially applied Term" at macro expansion time.
      */
    private[internal] def getDefault(using Quotes)(tpe: quotes.reflect.TypeRepr, idx: Int): Option[Expr[Any]] =
        import quotes.reflect.*
        val sym               = tpe.typeSymbol
        val defaultMethodName = s"$$lessinit$$greater$$default$$${idx + 1}"
        sym.companionModule.methodMember(defaultMethodName).headOption.map { method =>
            val typeArgs = tpe.typeArgs
            val ref      = Ref(sym.companionModule).select(method)
            val applied  = if typeArgs.nonEmpty then ref.appliedToTypes(typeArgs) else ref
            applied.asExprOf[Any]
        }
    end getDefault

    // ---- Identity getter/setter for root-level Schema ----

    /** Creates an identity getter `A => Maybe[F]` that wraps the input in Maybe.
      *
      * At the root level, F is the structural expansion of A -- same runtime representation, different compile-time type. Uses Any as
      * intermediate to avoid JVM class cast checks against the structural type.
      */
    private[internal] def identityGetter[A: Type, F: Type](using Quotes): Expr[A => kyo.Maybe[F]] =
        '{ ((root: Any) => kyo.Maybe(root)).asInstanceOf[A => kyo.Maybe[F]] }

    /** Creates an identity setter `(A, F) => A` that returns the new value unchanged.
      *
      * At the root level, the setter simply replaces the entire value. Uses Any as intermediate to avoid JVM class cast checks.
      */
    private[internal] def identitySetter[A: Type, F: Type](using Quotes): Expr[(A, F) => A] =
        '{ ((_: Any, value: Any) => value).asInstanceOf[(A, F) => A] }

    // ---- Nominal type derivation ----

    /** Derives the nominal parent type from A and F.
      *
      * If F is the structural expansion of A (root level), returns TypeRepr.of[A]. If F is a structural type (post-transform), also returns
      * TypeRepr.of[A] since the underlying data type is still A. Otherwise F is already a nominal type from a previous navigation step, so
      * returns TypeRepr.of[F].
      */
    private[internal] def deriveNominalType[A: Type, F: Type](using Quotes): quotes.reflect.TypeRepr =
        import quotes.reflect.*

        val aType    = TypeRepr.of[A]
        val fType    = TypeRepr.of[F]
        val expanded = ExpandMacro.expandType(aType)

        // Check if F matches the expansion of A (root level)
        if fType =:= expanded then
            aType
        else if isStructuralType(fType) then
            // F is structural but doesn't match A's expansion (post-transform).
            // The underlying data is still A, so use A as the nominal type.
            aType
        else
            fType
        end if
    end deriveNominalType

    // ---- Constructor call generation ----

    /** Constructs a case class instance using its companion object's apply method.
      *
      * Handles both simple and generic (type-parameterized) case classes.
      */
    private[internal] def constructCaseClass(using
        Quotes
    )(
        sym: quotes.reflect.Symbol,
        tpe: quotes.reflect.TypeRepr,
        args: List[quotes.reflect.Term]
    ): quotes.reflect.Term =
        import quotes.reflect.*
        val companion = Ref(sym.companionModule)
        val typeArgs = tpe match
            case AppliedType(_, targs) => targs
            case _                     => List.empty
        Select.overloaded(companion, "apply", typeArgs, args)
    end constructCaseClass

end MacroUtils
