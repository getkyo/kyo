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

    // ---- Case class default detection ----

    /** Checks if a case class field at the given index has a default value. */
    private[internal] def hasDefault(using Quotes)(sym: quotes.reflect.Symbol, idx: Int): Boolean =
        import quotes.reflect.*
        val defaultMethodName = s"$$lessinit$$greater$$default$$${idx + 1}"
        sym.companionModule.methodMember(defaultMethodName).nonEmpty
    end hasDefault

    /** Gets the default value expression for a case class field at the given index, if any. */
    private[internal] def getDefault(using Quotes)(sym: quotes.reflect.Symbol, idx: Int): Option[Expr[Any]] =
        import quotes.reflect.*
        val defaultMethodName = s"$$lessinit$$greater$$default$$${idx + 1}"
        sym.companionModule.methodMember(defaultMethodName).headOption.map { method =>
            Ref(sym.companionModule).select(method).asExprOf[Any]
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

    // ---- Type classifier sets ----

    /** Base primitive type symbols: Int, String, Boolean, Double, Float, Long, Short, Byte, Char, Unit.
      *
      * Used by ExpandMacro.isPrimitive.
      */
    private[internal] def basePrimitiveSymbols(using Quotes): Set[quotes.reflect.Symbol] =
        import quotes.reflect.*
        Set(
            TypeRepr.of[Int].typeSymbol,
            TypeRepr.of[String].typeSymbol,
            TypeRepr.of[Boolean].typeSymbol,
            TypeRepr.of[Double].typeSymbol,
            TypeRepr.of[Float].typeSymbol,
            TypeRepr.of[Long].typeSymbol,
            TypeRepr.of[Short].typeSymbol,
            TypeRepr.of[Byte].typeSymbol,
            TypeRepr.of[Char].typeSymbol,
            TypeRepr.of[Unit].typeSymbol
        )
    end basePrimitiveSymbols

    /** Extended primitive type symbols: base primitives + BigDecimal, BigInt, java.math.BigDecimal, java.math.BigInteger.
      *
      * Used by StructureMacro.isPrimitive.
      */
    private[internal] def extendedPrimitiveSymbols(using Quotes): Set[quotes.reflect.Symbol] =
        import quotes.reflect.*
        basePrimitiveSymbols ++ Set(
            TypeRepr.of[BigDecimal].typeSymbol,
            TypeRepr.of[BigInt].typeSymbol,
            TypeRepr.of[java.math.BigDecimal].typeSymbol,
            TypeRepr.of[java.math.BigInteger].typeSymbol
        )
    end extendedPrimitiveSymbols

    /** Collection type symbols: List, Seq, Vector, Set, Chunk. */
    private[internal] def collectionSymbols(using Quotes): Set[quotes.reflect.Symbol] =
        import quotes.reflect.*
        Set(
            TypeRepr.of[List].typeSymbol,
            TypeRepr.of[Seq].typeSymbol,
            TypeRepr.of[Vector].typeSymbol,
            TypeRepr.of[Set].typeSymbol,
            TypeRepr.of[kyo.Chunk].typeSymbol
        )
    end collectionSymbols

    /** Optional type symbols: Option, Maybe. */
    private[internal] def optionalSymbols(using Quotes): Set[quotes.reflect.Symbol] =
        import quotes.reflect.*
        Set(
            TypeRepr.of[Option].typeSymbol,
            TypeRepr.of[kyo.Maybe].typeSymbol
        )
    end optionalSymbols

    /** Map type symbols: Map. */
    private[internal] def mapSymbols(using Quotes): Set[quotes.reflect.Symbol] =
        import quotes.reflect.*
        Set(
            TypeRepr.of[Map].typeSymbol
        )
    end mapSymbols

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
