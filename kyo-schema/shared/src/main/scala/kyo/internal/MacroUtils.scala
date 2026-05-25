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

    // ---- Container type classification (source of truth: Schema companion givens) ----

    /** Enumerates container / optional / map tycon symbols by inspecting the `kyo.Schema` companion's `given` declarations directly.
      *
      * Each parameterised `given Schema[F[X]]` declared on `Schema.type` is classified by its return type's shape:
      *   - 1-arg `F[X]` where `F` is `Option` / `kyo.Maybe`: an optional;
      *   - 1-arg `F[X]` otherwise: a collection;
      *   - 2-arg `F[K, V]` where `F` is `Map` / `kyo.Dict` / `SortedMap`: a map.
      *
      * Returns `(collections, optionals, maps)`. Sourced from the givens themselves so adding a new container `given Schema[F[A]]` to the
      * `Schema` companion automatically extends the classifier with no companion list to update.
      */
    private[internal] def containerSymbolsFromSchema(using
        Quotes
    ): (
        Set[quotes.reflect.Symbol],
        Set[quotes.reflect.Symbol],
        Set[quotes.reflect.Symbol]
    ) =
        import quotes.reflect.*

        val optionalTycons: Set[Symbol] = Set(
            TypeRepr.of[Option].typeSymbol,
            TypeRepr.of[kyo.Maybe].typeSymbol
        )
        val mapTycons: Set[Symbol] = Set(
            TypeRepr.of[Map].typeSymbol,
            TypeRepr.of[kyo.Dict].typeSymbol,
            TypeRepr.of[scala.collection.immutable.SortedMap].typeSymbol
        )

        def peel(ret: TypeRepr): Option[TypeRepr] =
            ret.dealias match
                case AppliedType(_, List(target)) => Some(target)
                case Refinement(parent, _, _)     => peel(parent)
                case _                            => None

        val schemaSym    = TypeRepr.of[kyo.Schema.type].typeSymbol
        val givenMembers = schemaSym.declaredMethods.filter(_.flags.is(Flags.Given))

        var collections = Set.empty[Symbol]
        var optionals   = Set.empty[Symbol]
        var maps        = Set.empty[Symbol]
        givenMembers.foreach { m =>
            m.tree match
                case d: DefDef =>
                    peel(d.returnTpt.tpe).foreach {
                        case AppliedType(tycon, args) =>
                            val sym = tycon.typeSymbol
                            args.size match
                                case 1 =>
                                    if optionalTycons.contains(sym) then optionals = optionals + sym
                                    else collections = collections + sym
                                case 2 if mapTycons.contains(sym) =>
                                    maps = maps + sym
                                case _ => ()
                            end match
                        case _ => ()
                    }
                case _ => ()
        }
        (collections, optionals, maps)
    end containerSymbolsFromSchema

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
