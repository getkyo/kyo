package kyo.internal

import kyo.*
import kyo.Record.*
import scala.annotation.tailrec
import scala.quoted.*

/** Shared macro utility methods used across multiple macro files in kyo-schema.
  *
  * Contains structural type checks, field operations, string literal extraction, case class default
  * detection, identity getter/setter generation, nominal-type derivation, and constructor call
  * generation. NO classifier specialization for user case-class / sealed-trait field types lives
  * here: the derivation path resolves all field schemas via
  * `scala.compiletime.summonInline[Schema[ft]]`.
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

    // ---- Sum cases ----

    /** The reference derived code reaches `child`, a case of the sealed `parent`, through when the sum is `sumType`.
      *
      * A case object or an enum value is a term, so its reference is its singleton `TermRef`; a class case's is its
      * `TypeRef`. Either is selected from the path `sumType` was reached through, the rule the compiler's own sum mirror
      * follows (`TypeOps.childPrefix`).
      *
      * `child.typeRef` would be wrong twice. On a term it builds a `TypeRef` designating a value, which denotes that
      * value's info in whatever phase reads it: an enum value in a static object is a static field and keeps its type,
      * but one in the companion of a class member becomes a getter whose `=> T` info erases to `Function0`, so a schema
      * typed at it erases to `Schema[Function0]` and loses its bridges. And its prefix is the declaring owner's `this`,
      * which does not exist at a derivation site outside that owner.
      */
    private[internal] def sumCaseReference(using
        Quotes
    )(
        sumType: quotes.reflect.TypeRepr,
        parent: quotes.reflect.Symbol,
        child: quotes.reflect.Symbol
    ): quotes.reflect.TypeRepr =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived

        def unreachable(reason: String): Nothing =
            report.errorAndAbort(
                s"Cannot derive the cases of ${sumType.show}: its case ${child.name.stripSuffix("$")} $reason, " +
                    "so the derived code has no path to it. Provide a given Schema built with Schema.init."
            )

        def ownersFromRoot(sym: Symbol): List[Symbol] =
            @tailrec def loop(s: Symbol, acc: List[Symbol]): List[Symbol] =
                if s.isNoSymbol then acc else loop(s.maybeOwner, s :: acc)
            loop(sym, Nil)
        end ownersFromRoot

        @tailrec def dropCommon(a: List[Symbol], b: List[Symbol]): (List[Symbol], List[Symbol]) =
            (a, b) match
                case (x :: xs, y :: ys) if x == y => dropCommon(xs, ys)
                case _                            => (a, b)

        @tailrec def outward(pre: TypeRepr, steps: Int): Option[TypeRepr] =
            if steps == 0 then Some(pre)
            else
                pre match
                    case ThisType(TypeRef(outer, _)) => outward(outer, steps - 1)
                    case TermRef(outer, _)           => outward(outer, steps - 1)
                    case _                           => None

        def isPath(pre: TypeRepr): Boolean =
            pre match
                case NoPrefix() => true
                case _          => pre.isSingleton

        val sumPrefix = sumType.dealias match
            case TypeRef(pre, _)                 => pre
            case AppliedType(TypeRef(pre, _), _) => pre
            case other                           => unreachable(s"belongs to ${other.show}, which is not a named type")

        val (parentRest, childRest) = dropCommon(ownersFromRoot(parent.owner), ownersFromRoot(child.owner))

        val commonPrefix = outward(sumPrefix, parentRest.size).filter(isPath).getOrElse(
            unreachable(s"cannot be selected from ${sumPrefix.show}")
        )
        val childPrefix = childRest.foldLeft(commonPrefix) { (pre, owner) =>
            if owner.isClassDef && owner.flags.is(Flags.Module) then pre.select(owner.companionModule)
            else unreachable(s"is declared inside ${owner.name}, which is not an object")
        }
        val caseSym = if child.isClassDef && child.flags.is(Flags.Module) then child.companionModule else child
        childPrefix.select(caseSym)
    end sumCaseReference

    /** The type a case's schema is typed at: a case object's module class, otherwise the case's own reference. */
    private[internal] def sumCaseType(using Quotes)(reference: quotes.reflect.TypeRepr): quotes.reflect.TypeRepr =
        import quotes.reflect.*
        reference match
            case ref: TermRef if ref.termSymbol.flags.is(Flags.Module) => ref.widen
            case other                                                 => other
    end sumCaseType

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
            case _                                         => report.errorAndAbort(
                    "Transform field name must be a string literal"
                )
        end match
    end extractStringLiteral

    // ---- Case class default detection ----

    /** Checks if a case class field at the given index has a default value. */
    private[internal] def hasDefault(using Quotes)(sym: quotes.reflect.Symbol, idx: Int): Boolean =
        import quotes.reflect.*
        val defaultMethodName = s"$$lessinit$$greater$$default$$${idx + 1}"
        sym.companionModule.methodMember(defaultMethodName).nonEmpty
    end hasDefault

    /** Gets the default value expression for a case class field at the given index, if any.
      *
      * For generic case classes, the generated default-value method is itself type-parameterized
      * (`<init>$default$N[A, ...]`); we must apply the case class's type arguments to the method
      * reference before treating it as an expression, otherwise `asExprOf` raises "Expected an
      * expression. This is a partially applied Term" at macro expansion time.
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
      * At the root level, F is the structural expansion of A: same runtime representation,
      * different compile-time type. Uses Any as intermediate to avoid JVM class cast checks against
      * the structural type.
      */
    private[internal] def identityGetter[A: Type, F: Type](using Quotes): Expr[A => kyo.Maybe[F]] =
        '{ ((root: Any) => kyo.Maybe(root)).asInstanceOf[A => kyo.Maybe[F]] }

    /** Creates an identity setter `(A, F) => A` that returns the new value unchanged.
      *
      * At the root level, the setter simply replaces the entire value. Uses Any as intermediate to
      * avoid JVM class cast checks.
      */
    private[internal] def identitySetter[A: Type, F: Type](using Quotes): Expr[(A, F) => A] =
        '{ ((_: Any, value: Any) => value).asInstanceOf[(A, F) => A] }

    // ---- Nominal type derivation ----

    /** Derives the nominal parent type from A and F.
      *
      * If F is the structural expansion of A (root level), returns TypeRepr.of[A]. If F is a
      * structural type (post-transform), also returns TypeRepr.of[A] since the underlying data type
      * is still A. Otherwise F is already a nominal type from a previous navigation step, so
      * returns TypeRepr.of[F].
      */
    private[internal] def deriveNominalType[A: Type, F: Type](using Quotes): quotes.reflect.TypeRepr =
        import quotes.reflect.*

        val aType    = TypeRepr.of[A]
        val fType    = TypeRepr.of[F]
        val expanded = ExpandMacro.expandType(aType)

        if fType =:= expanded then aType
        else if isStructuralType(fType) then aType
        else fType
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
        val typeArgs  = tpe match
            case AppliedType(_, targs) => targs
            case _                     => List.empty
        Select.overloaded(companion, "apply", typeArgs, args)
    end constructCaseClass

end MacroUtils
