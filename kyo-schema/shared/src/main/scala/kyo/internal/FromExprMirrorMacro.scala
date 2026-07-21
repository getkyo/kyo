package kyo.internal

import scala.deriving.Mirror
import scala.quoted.*

/** Single-entry FromExpr derivation macro for `kyo.FromExpr` (opaque around `scala.quoted.FromExpr`).
  *
  * Strategy for any input type T:
  *
  *   - If T is a union (`OrType`): destructure to variants, derive a chain that tries each variant's FromExpr in order.
  *   - If T has `Mirror.Singleton` / `Mirror.SumOf` / `Mirror.ProductOf`: structural derivation.
  *   - Else: error.
  *
  * For each component (union variant, sealed-trait variant, case-class field), the macro:
  *   1. Tries `Expr.summon[kyo.FromExpr[T]]` at macro time, uses a user-provided kyo instance (shim, custom given, recursive derivation).
  *   2. Falls back to `Expr.summon[scala.quoted.FromExpr[T]]`, picks up stdlib's primitive instances (`IntFromExpr`, `StringFromExpr`,
  *      etc.) wrapped via `kyo.FromExpr.fromStd`.
  *   3. Falls back to `derivedImpl[T]`, recursive structural derivation.
  */
private[kyo] object FromExprMirrorMacro:

    def derivedImpl[A: Type](using Quotes): Expr[kyo.FromExpr[A]] =
        import quotes.reflect.*
        TypeRepr.of[A] match
            case _: OrType => unionImpl[A]
            case _ =>
                Expr.summon[Mirror.Of[A]] match
                    case None =>
                        report.errorAndAbort(
                            s"Cannot derive kyo.FromExpr for ${TypeRepr.of[A].show}: no Mirror.Of in scope and not a union. " +
                                "Supported shapes: case classes, sealed traits, enums, case objects, and unions. " +
                                "Opaque types require a hand-written `given kyo.FromExpr[X]` in scope."
                        )
                    case Some('{ $m: Mirror.Singleton }) =>
                        singletonImpl[A]
                    case Some('{ $m: Mirror.SumOf[A] { type MirroredElemTypes = elems } }) =>
                        sumImpl[A, elems & Tuple]
                    case Some('{ $m: Mirror.ProductOf[A] { type MirroredElemTypes = elems } }) =>
                        productImpl[A, elems & Tuple](m)
                    case Some(other) =>
                        report.errorAndAbort(s"Unexpected Mirror shape for kyo.FromExpr derivation: ${other.show}")
                end match
        end match
    end derivedImpl

    /** Lookup chain: kyo.FromExpr first (recursion + user shims), then stdlib's FromExpr (primitives), then structural derivation. */
    private def summonOrDerive[T: Type](using Quotes): Expr[kyo.FromExpr[T]] =
        Expr.summon[kyo.FromExpr[T]].getOrElse {
            Expr.summon[scala.quoted.FromExpr[T]] match
                case Some(stdFe) => '{ kyo.FromExpr.fromStd[T]($stdFe) }
                case None        => derivedImpl[T]
        }

    // --- Union --------------------------------------------------------------------------------

    private def unionImpl[A: Type](using Quotes): Expr[kyo.FromExpr[A]] =
        import quotes.reflect.*
        def flattenOr(t: TypeRepr): List[TypeRepr] = t match
            case OrType(l, r) => flattenOr(l) ++ flattenOr(r)
            case other        => List(other)
        val variants = flattenOr(TypeRepr.of[A])
        val variantFromExprs: List[Expr[kyo.FromExpr[A]]] = variants.map { v =>
            v.asType match
                case '[t] =>
                    val fe = summonOrDerive[t]
                    '{ ${ fe }.asInstanceOf[kyo.FromExpr[A]] }
        }
        val listExpr: Expr[List[kyo.FromExpr[A]]] = Expr.ofList(variantFromExprs)
        '{
            kyo.FromExpr.fromStd[A](
                new scala.quoted.FromExpr[A]:
                    def unapply(expr: scala.quoted.Expr[A])(using q: scala.quoted.Quotes): Option[A] =
                        val fes = $listExpr
                        fes.iterator.flatMap(_.unapply(expr)).nextOption()
            )
        }
    end unionImpl

    // --- Singleton ----------------------------------------------------------------------------

    private def singletonImpl[A: Type](using Quotes): Expr[kyo.FromExpr[A]] =
        import quotes.reflect.*
        val sym                             = TypeRepr.of[A].typeSymbol
        val valueRef                        = Ref(sym.companionModule)
        val captured                        = valueRef.asExpr.asInstanceOf[Expr[A]]
        val singletonFullName: Expr[String] = Expr(sym.companionModule.fullName)
        '{
            kyo.FromExpr.fromStd[A](
                new scala.quoted.FromExpr[A]:
                    def unapply(expr: scala.quoted.Expr[A])(using q: scala.quoted.Quotes): Option[A] =
                        import q.reflect.*
                        val singletonSym = Symbol.requiredModule($singletonFullName)
                        def peel(t: Term): Term = t match
                            case Inlined(_, _, inner) => peel(inner)
                            case Block(_, inner)      => peel(inner)
                            case Typed(inner, _)      => peel(inner)
                            case other                => other
                        val peeled = peel(expr.asTerm)
                        if !peeled.symbol.isNoSymbol && peeled.symbol.fullName == singletonSym.fullName then
                            Some($captured)
                        else None
                    end unapply
            )
        }
    end singletonImpl

    // --- Sum --------------------------------------------------------------------------------

    private def sumImpl[A: Type, Elems <: Tuple: Type](using Quotes): Expr[kyo.FromExpr[A]] =
        val variantFromExprs: Expr[List[kyo.FromExpr[A]]] = summonOrDeriveAllVariants[A, Elems]
        '{
            kyo.FromExpr.fromStd[A](
                new scala.quoted.FromExpr[A]:
                    def unapply(expr: scala.quoted.Expr[A])(using q: scala.quoted.Quotes): Option[A] =
                        val variants = $variantFromExprs
                        variants.iterator.flatMap(_.unapply(expr)).nextOption()
            )
        }
    end sumImpl

    // --- Product -----------------------------------------------------------------------------

    private def productImpl[A: Type, Elems <: Tuple: Type](
        m: Expr[Mirror.ProductOf[A]]
    )(using Quotes): Expr[kyo.FromExpr[A]] =
        val perFieldFromExprs: Expr[List[kyo.FromExpr[Any]]] = summonOrDeriveAllFields[Elems]
        val arity: Expr[Int]                                 = Expr(countTupleArity[Elems])
        '{
            kyo.FromExpr.fromStd[A](
                new scala.quoted.FromExpr[A]:
                    def unapply(expr: scala.quoted.Expr[A])(using q: scala.quoted.Quotes): Option[A] =
                        import q.reflect.*
                        val instances = $perFieldFromExprs
                        val expected  = $arity

                        val bindings = scala.collection.mutable.Map.empty[Symbol, Term]
                        object collector extends TreeTraverser:
                            override def traverseTree(t: Tree)(owner: Symbol): Unit =
                                t match
                                    case vd @ ValDef(_, _, Some(rhs)) => bindings(vd.symbol) = rhs
                                    case _                            =>
                                super.traverseTree(t)(owner)
                            end traverseTree
                        end collector
                        collector.traverseTree(expr.asTerm)(Symbol.spliceOwner)

                        def peel(t: Term): Term = t match
                            case Inlined(_, _, inner)                      => peel(inner)
                            case Block(_, inner)                           => peel(inner)
                            case Typed(inner, _)                           => peel(inner)
                            case id: Ident if bindings.contains(id.symbol) => peel(bindings(id.symbol))
                            case other                                     => other

                        def asConstructionArgs(t: Term): Option[List[Term]] =
                            def collect(cur: Term, acc: List[Term]): (Term, List[Term]) =
                                peel(cur) match
                                    case Apply(fun, args)  => collect(fun, args ::: acc)
                                    case TypeApply(fun, _) => collect(fun, acc)
                                    case head              => (head, acc)
                            val (head, args) = collect(t, Nil)
                            head match
                                case Select(_, "apply")       => Some(args)
                                case Select(New(_), "<init>") => Some(args)
                                case _                        => None
                            end match
                        end asConstructionArgs

                        asConstructionArgs(peel(expr.asTerm)) match
                            case Some(args) if args.length == expected =>
                                val results = args.zip(instances).map { (arg, fe) =>
                                    fe.unapply(arg.asExpr.asInstanceOf[scala.quoted.Expr[Any]])
                                }
                                if results.forall(_.isDefined) then
                                    val tuple = Tuple.fromArray(results.map(_.get).toArray)
                                    Some(${ m }.fromProduct(tuple.asInstanceOf[Product]))
                                else None
                                end if
                            case _ => None
                        end match
                    end unapply
            )
        }
    end productImpl

    // --- Macro-time helpers -----------------------------------------------------------------

    private def summonOrDeriveAllFields[Elems <: Tuple: Type](using Quotes): Expr[List[kyo.FromExpr[Any]]] =
        Type.of[Elems] match
            case '[head *: tail] =>
                val headFe: Expr[kyo.FromExpr[head]] = summonOrDerive[head]
                '{
                    ${ headFe }.asInstanceOf[kyo.FromExpr[Any]] :: ${ summonOrDeriveAllFields[tail] }
                }
            case '[EmptyTuple] => '{ Nil }
            case _             => '{ Nil }
    end summonOrDeriveAllFields

    private def summonOrDeriveAllVariants[A: Type, Variants <: Tuple: Type](using Quotes): Expr[List[kyo.FromExpr[A]]] =
        Type.of[Variants] match
            case '[head *: tail] =>
                val headFe: Expr[kyo.FromExpr[head]] = summonOrDerive[head]
                '{
                    ${ headFe }.asInstanceOf[kyo.FromExpr[A]] :: ${ summonOrDeriveAllVariants[A, tail] }
                }
            case '[EmptyTuple] => '{ Nil }
            case _             => '{ Nil }
    end summonOrDeriveAllVariants

    private def countTupleArity[T <: Tuple: Type](using Quotes): Int =
        Type.of[T] match
            case '[head *: tail] => 1 + countTupleArity[tail]
            case '[EmptyTuple]   => 0
            case _               => 0
    end countTupleArity

end FromExprMirrorMacro
