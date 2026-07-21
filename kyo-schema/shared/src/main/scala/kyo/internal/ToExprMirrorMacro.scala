package kyo.internal

import scala.deriving.Mirror
import scala.quoted.*

/** Single-entry ToExpr derivation macro for `kyo.ToExpr` (opaque around `scala.quoted.ToExpr`). Mirrors [[FromExprMirrorMacro]]. */
private[kyo] object ToExprMirrorMacro:

    def derivedImpl[A: Type](using Quotes): Expr[kyo.ToExpr[A]] =
        import quotes.reflect.*
        TypeRepr.of[A] match
            case _: OrType => unionImpl[A]
            case _ =>
                Expr.summon[Mirror.Of[A]] match
                    case None =>
                        report.errorAndAbort(
                            s"Cannot derive kyo.ToExpr for ${TypeRepr.of[A].show}: no Mirror.Of in scope and not a union. " +
                                "Supported shapes: case classes, sealed traits, enums, case objects, and unions. " +
                                "Opaque types require a hand-written `given kyo.ToExpr[X]` in scope."
                        )
                    case Some('{ $m: Mirror.Singleton }) =>
                        singletonImpl[A]
                    case Some('{ $m: Mirror.SumOf[A] { type MirroredElemTypes = elems } }) =>
                        sumImpl[A, elems & Tuple](m)
                    case Some('{ $m: Mirror.ProductOf[A] }) =>
                        productImpl[A]
                    case Some(other) =>
                        report.errorAndAbort(s"Unexpected Mirror shape for kyo.ToExpr derivation: ${other.show}")
                end match
        end match
    end derivedImpl

    private def summonOrDerive[T: Type](using Quotes): Expr[kyo.ToExpr[T]] =
        Expr.summon[kyo.ToExpr[T]].getOrElse {
            Expr.summon[scala.quoted.ToExpr[T]] match
                case Some(stdTe) => '{ kyo.ToExpr.fromStd[T]($stdTe) }
                case None        => derivedImpl[T]
        }

    private def summonOrFailConcreteTag[T: Type](using Quotes): Expr[kyo.ConcreteTag[T]] =
        Expr.summon[kyo.ConcreteTag[T]].getOrElse {
            quotes.reflect.report.errorAndAbort(
                s"ToExpr union dispatch needs a ConcreteTag for ${quotes.reflect.TypeRepr.of[T].show}, none available."
            )
        }

    // --- Union ------------------------------------------------------------------------------

    private def unionImpl[A: Type](using Quotes): Expr[kyo.ToExpr[A]] =
        import quotes.reflect.*
        def flattenOr(t: TypeRepr): List[TypeRepr] = t match
            case OrType(l, r) => flattenOr(l) ++ flattenOr(r)
            case other        => List(other)
        val variants = flattenOr(TypeRepr.of[A])
        val pairExprs: List[Expr[(kyo.ConcreteTag[Any], kyo.ToExpr[Any])]] = variants.map { v =>
            v.asType match
                case '[t] =>
                    val tag = summonOrFailConcreteTag[t]
                    val te  = summonOrDerive[t]
                    '{
                        (
                            ${ tag }.asInstanceOf[kyo.ConcreteTag[Any]],
                            ${ te }.asInstanceOf[kyo.ToExpr[Any]]
                        )
                    }
        }
        val listExpr: Expr[List[(kyo.ConcreteTag[Any], kyo.ToExpr[Any])]] = Expr.ofList(pairExprs)
        '{
            kyo.ToExpr.fromStd[A](
                new scala.quoted.ToExpr[A]:
                    def apply(value: A)(using q: scala.quoted.Quotes): scala.quoted.Expr[A] =
                        val pairs   = $listExpr
                        val matched = pairs.find { case (tag, _) => tag.accepts(value) }
                        matched match
                            case Some((_, te)) => te.apply(value).asInstanceOf[scala.quoted.Expr[A]]
                            case None =>
                                val cls = value.asInstanceOf[AnyRef].getClass.getName
                                throw new IllegalStateException(
                                    s"Union ToExpr: no variant ConcreteTag matched value of class $cls"
                                )
                        end match
                    end apply
            )
        }
    end unionImpl

    private def singletonImpl[A: Type](using Quotes): Expr[kyo.ToExpr[A]] =
        import quotes.reflect.*
        val sym               = TypeRepr.of[A].typeSymbol
        val companionFullName = Expr(sym.companionModule.fullName)
        '{
            kyo.ToExpr.fromStd[A](
                new scala.quoted.ToExpr[A]:
                    def apply(value: A)(using q: scala.quoted.Quotes): scala.quoted.Expr[A] =
                        import q.reflect.*
                        val singleton = Symbol.requiredModule($companionFullName)
                        Ref(singleton).asExpr.asInstanceOf[scala.quoted.Expr[A]]
                    end apply
            )
        }
    end singletonImpl

    private def productImpl[A: Type](using Quotes): Expr[kyo.ToExpr[A]] =
        import quotes.reflect.*
        val typeSym        = TypeRepr.of[A].typeSymbol
        val caseFields     = typeSym.caseFields
        val fieldTypeReprs = caseFields.map(f => TypeRepr.of[A].memberType(f))
        val fieldsTupleType: TypeRepr =
            fieldTypeReprs.foldRight(TypeRepr.of[EmptyTuple])((tpe, acc) => TypeRepr.of[*:].appliedTo(List(tpe, acc)))

        val perFieldToExprsList: Expr[List[kyo.ToExpr[Any]]] =
            fieldsTupleType.asType match
                case '[type elems <: Tuple; elems] => summonOrDeriveAllFields[elems]
                case _                             => '{ Nil }

        val classFullName: Expr[String] = Expr(typeSym.fullName)

        val ownerSym = typeSym.owner
        val ownerIsNestingObject: Boolean =
            !ownerSym.isNoSymbol && ownerSym.isClassDef && !ownerSym.flags.is(Flags.Package) &&
                !ownerSym.companionModule.isNoSymbol
        val outerModuleNameOpt: Expr[Option[String]] =
            if ownerIsNestingObject then Expr(Some(ownerSym.companionModule.fullName))
            else Expr(None)

        '{
            kyo.ToExpr.fromStd[A](
                new scala.quoted.ToExpr[A]:
                    def apply(value: A)(using q: scala.quoted.Quotes): scala.quoted.Expr[A] =
                        import q.reflect.*
                        val instances = $perFieldToExprsList
                        val product   = value.asInstanceOf[Product]
                        val fieldExprs: List[scala.quoted.Expr[Any]] =
                            (0 until product.productArity).toList.zip(instances).map { (i, te) =>
                                te.apply(product.productElement(i))
                            }
                        val classSym = Symbol.requiredClass($classFullName)
                        val companionTerm: Term = $outerModuleNameOpt match
                            case Some(outerName) =>
                                val outerMod = Symbol.requiredModule(outerName)
                                Select.unique(Ref(outerMod), classSym.name)
                            case None =>
                                Ref(classSym.companionModule)
                        Apply(
                            Select.unique(companionTerm, "apply"),
                            fieldExprs.map(_.asTerm)
                        ).asExpr.asInstanceOf[scala.quoted.Expr[A]]
                    end apply
            )
        }
    end productImpl

    private def sumImpl[A: Type, Elems <: Tuple: Type](
        m: Expr[Mirror.SumOf[A]]
    )(using Quotes): Expr[kyo.ToExpr[A]] =
        val variantToExprs: Expr[List[kyo.ToExpr[A]]] = summonOrDeriveAllVariants[A, Elems]
        '{
            kyo.ToExpr.fromStd[A](
                new scala.quoted.ToExpr[A]:
                    def apply(value: A)(using q: scala.quoted.Quotes): scala.quoted.Expr[A] =
                        val variants = $variantToExprs
                        val ord      = ${ m }.ordinal(value)
                        variants(ord).apply(value)
                    end apply
            )
        }
    end sumImpl

    private def summonOrDeriveAllFields[Elems <: Tuple: Type](using Quotes): Expr[List[kyo.ToExpr[Any]]] =
        Type.of[Elems] match
            case '[head *: tail] =>
                val headTe: Expr[kyo.ToExpr[head]] = summonOrDerive[head]
                '{
                    ${ headTe }.asInstanceOf[kyo.ToExpr[Any]] :: ${ summonOrDeriveAllFields[tail] }
                }
            case '[EmptyTuple] => '{ Nil }
            case _             => '{ Nil }
    end summonOrDeriveAllFields

    private def summonOrDeriveAllVariants[A: Type, Variants <: Tuple: Type](using Quotes): Expr[List[kyo.ToExpr[A]]] =
        Type.of[Variants] match
            case '[head *: tail] =>
                val headTe: Expr[kyo.ToExpr[head]] = summonOrDerive[head]
                '{
                    ${ headTe }.asInstanceOf[kyo.ToExpr[A]] :: ${ summonOrDeriveAllVariants[A, tail] }
                }
            case '[EmptyTuple] => '{ Nil }
            case _             => '{ Nil }
    end summonOrDeriveAllVariants

end ToExprMirrorMacro
