package kyo.test
// [Converted] This file has been partially converted from zio-test to Kyo effect system.
// All Kyo references have been replaced with equivalent Kyo references where applicable.

import kyo.*
import kyo.test.diff.Diff
import kyo.test.internal.OptionalImplicit
import kyo.test.internal.SmartAssertions
import scala.quoted.*
import scala.reflect.ClassTag

object SmartAssertMacros:
    def smartAssertSingle(expr: Expr[Boolean])(using Quotes): Expr[TestResult] =
        SmartAssertMacros.smartAssertSingle_impl(expr)

    def smartAssert(exprs: Expr[Seq[Boolean]])(using Quotes): Expr[TestResult] =
        SmartAssertMacros.smartAssert_impl(exprs)

    extension (using Quotes)(typeRepr: quotes.reflect.TypeRepr)
        def typeTree: quotes.reflect.TypeTree =
            import quotes.reflect.*
            typeRepr.widen.asType match
                case '[tp] => TypeTree.of[tp]
    end extension

    object MethodCall:
        def unapply(using
            Quotes
        )(
            tree: quotes.reflect.Term
        ): Option[(quotes.reflect.Term, String, List[quotes.reflect.TypeRepr], Option[List[quotes.reflect.Term]])] =
            import quotes.reflect.*
            tree match
                case Select(lhs, name)                               => Some((lhs, name, List.empty, None))
                case TypeApply(Select(lhs, name), tpes)              => Some((lhs, name, tpes.map(_.tpe), None))
                case Apply(Select(lhs, name), args)                  => Some((lhs, name, List.empty, Some(args)))
                case Apply(TypeApply(Select(lhs, name), tpes), args) => Some((lhs, name, tpes.map(_.tpe), Some(args)))
                case _                                               => None
            end match
        end unapply
    end MethodCall

    case class PositionContext(start: Int)

    object PositionContext:
        def apply(using Quotes)(term: quotes.reflect.Term) = new PositionContext(term.pos.start)

    def transformAs[Start: Type, End: Type](
        expr: Expr[TestLens[End]]
    )(start: Expr[TestArrow[Any, Start]])(using PositionContext, Quotes): Expr[TestArrow[Any, End]] =
        val res = expr match
            case '{ TestLensAnyOps($lhs: TestLens[a]).anything } =>
                val arrow = transformAs[Start, a](lhs.asInstanceOf[Expr[TestLens[a]]])(start)
                '{ $arrow >>> SmartAssertions.anything }

            case '{ type a; TestLensAnyOps($lhs: TestLens[`a`]).custom($customAssertion: CustomAssertion[`a`, End]) } =>
                val arrow = transformAs[Start, a](lhs.asInstanceOf[Expr[TestLens[a]]])(start)
                '{ $arrow >>> SmartAssertions.custom[a, End]($customAssertion) }

            case '{ type a >: End; TestLensAnyOps($lhs: TestLens[`a`]).subtype[End] } =>
                val arrow = transformAs[Start, a](lhs.asInstanceOf[Expr[TestLens[a]]])(start)
                Expr.summon[ClassTag[End]] match
                    case Some(tag) =>
                        '{ $arrow >>> SmartAssertions.as[a, End]($tag) }
                    case None =>
                        throw new Error("NEED CLASS TAG")
                end match

            case '{ TestLensOptionOps($lhs: TestLens[Option[End]]).some } =>
                val arrow = transformAs[Start, Option[End]](lhs.asInstanceOf[Expr[TestLens[Option[End]]]])(start)
                '{ $arrow >>> SmartAssertions.isSome }

            case '{ type e; TestLensEitherOps[`e`, End]($lhs: TestLens[Either[`e`, End]]).right } =>
                val arrow = transformAs[Start, Either[e, End]](lhs.asInstanceOf[Expr[TestLens[Either[e, End]]]])(start)
                '{ $arrow >>> SmartAssertions.asRight }

            case '{ type e; TestLensEitherOps[End, `e`]($lhs: TestLens[Either[End, `e`]]).left } =>
                val arrow = transformAs[Start, Either[End, e]](lhs.asInstanceOf[Expr[TestLens[Either[End, e]]]])(start)
                '{ $arrow >>> SmartAssertions.asLeft }

            // case '{ TestLensCauseOps($lhs: TestLens[Cause[End]]).failure } =>
            //     val arrow = transformAs[Start, Cause[End]](lhs.asInstanceOf[Expr[TestLens[Cause[End]]]])(start)
            //     '{ $arrow >>> SmartAssertions.asCauseFailure }

            // case '{ type a; TestLensCauseOps($lhs: TestLens[Cause[`a`]]).panic } =>
            //     val arrow = transformAs[Start, Cause[a]](lhs.asInstanceOf[Expr[TestLens[Cause[a]]]])(start)
            //     '{ $arrow >>> SmartAssertions.asCauseDie }

            // case '{ type a; TestLensCauseOps($lhs: TestLens[Cause[`a`]]).interrupted } =>
            //     val arrow = transformAs[Start, Cause[a]](lhs.asInstanceOf[Expr[TestLens[Cause[a]]]])(start)
            //     '{ $arrow >>> SmartAssertions.asCauseInterrupted }

            case '{ type a; TestLensTryOps($lhs: TestLens[scala.util.Try[`a`]]).success } =>
                val arrow = transformAs[Start, scala.util.Try[a]](lhs.asInstanceOf[Expr[TestLens[scala.util.Try[a]]]])(start)
                '{ $arrow >>> SmartAssertions.asTrySuccess }

            case '{ type a; TestLensTryOps($lhs: TestLens[scala.util.Try[`a`]]).failure } =>
                val arrow = transformAs[Start, scala.util.Try[a]](lhs.asInstanceOf[Expr[TestLens[scala.util.Try[a]]]])(start)
                '{ $arrow >>> SmartAssertions.asTryFailure }

            // case '{ type a; TestLensExitOps($lhs: TestLens[Exit[End, `a`]]).failure } =>
            //     val arrow = transformAs[Start, Exit[End, a]](lhs.asInstanceOf[Expr[TestLens[Exit[End, a]]]])(start)
            //     '{ $arrow >>> SmartAssertions.asExitFailure }

            // case '{ type a; TestLensExitOps($lhs: TestLens[Exit[`a`, End]]).success } =>
            //     val arrow = transformAs[Start, Exit[a, End]](lhs.asInstanceOf[Expr[TestLens[Exit[a, End]]]])(start)
            //     '{ $arrow >>> SmartAssertions.asExitSuccess }

            // case '{ type e; type a; TestLensExitOps($lhs: TestLens[Exit[`e`, `a`]]).panic } =>
            //     val arrow = transformAs[Start, Exit[e, a]](lhs.asInstanceOf[Expr[TestLens[Exit[e, a]]]])(start)
            //     '{ $arrow >>> SmartAssertions.asExitDie }

            // case '{ type e; type a; TestLensExitOps($lhs: TestLens[Exit[`e`, `a`]]).interrupted } =>
            //     val arrow = transformAs[Start, Exit[e, a]](lhs.asInstanceOf[Expr[TestLens[Exit[e, a]]]])(start)
            //     '{ $arrow >>> SmartAssertions.asExitInterrupted }

            // case '{ type e; type a; TestLensExitOps($lhs: TestLens[Exit[`e`, `a`]]).cause } =>
            //     val arrow = transformAs[Start, Exit[e, a]](lhs.asInstanceOf[Expr[TestLens[Exit[e, a]]]])(start)
            //     '{ $arrow >>> SmartAssertions.asExitCause }

            case other =>
                start
        res.asInstanceOf[Expr[TestArrow[Any, End]]]
    end transformAs

    def transform[A: Type](expr: Expr[A])(using PositionContext, Quotes): Expr[TestArrow[Any, A]] =
        import quotes.reflect.*
        def isBool(term: quotes.reflect.Term): Boolean =
            term.tpe.widen.asType match
                case '[Boolean] => true
                case _          => false

        def getSpan(term: quotes.reflect.Term): Expr[(Int, Int)] =
            Expr(term.pos.start - summon[PositionContext].start, term.pos.end - summon[PositionContext].start)

        expr match
            case '{ type t; type v; SmartAssertionOps[`t`](${ something }: `t`).is[`v`](${ Unseal(Lambda(terms, body)) }) } =>
                val lhs = transform(something).asInstanceOf[Expr[TestArrow[Any, t]]]
                val res = transformAs(body.asExprOf[TestLens[v]])(lhs)
                res.asInstanceOf[Expr[TestArrow[Any, A]]]

            case Unseal(tree @ Inlined(a, b, expr)) =>
                // https://github.com/zio/zio/issues/8571
                // always make sure to set the span on an Inlined tree back to its pre-inlining position since
                // the implicit PositionContext gets its 'start' argument from the pre-inlinining position.
                val preMacroExpansionSpan = getSpan(tree)
                val arrow                 = Inlined(a, b, transform(expr.asExprOf[A]).asTerm).asExprOf[kyo.test.TestArrow[Any, A]]
                '{ $arrow.span($preMacroExpansionSpan) }

            case Unseal(Apply(Select(lhs, op @ (">" | ">=" | "<" | "<=")), List(rhs))) =>
                def tpesPriority(tpe: TypeRepr): Int =
                    tpe.toString match
                        case "Byte"   => 0
                        case "Short"  => 1
                        case "Char"   => 2
                        case "Int"    => 3
                        case "Long"   => 4
                        case "Float"  => 5
                        case "Double" => 6
                        case _        => -1

                // `true` for conversion from `lhs` to `rhs`.
                def implicitConversionDirection(lhs: TypeRepr, rhs: TypeRepr): Option[Boolean] =
                    if tpesPriority(lhs) == -1 || tpesPriority(rhs) == -1 then
                        (lhs.asType, rhs.asType) match
                            case ('[l], '[r]) =>
                                Expr.summon[l => r] match
                                    case None =>
                                        Expr.summon[r => l] match
                                            case None => None
                                            case _    => Some(false)
                                    case _ => Some(true)
                    else if tpesPriority(lhs) - tpesPriority(rhs) > 0 then Some(true)
                    else Some(false)

                val span = getSpan(rhs)
                implicitConversionDirection(lhs.tpe.widen, rhs.tpe.widen) match
                    case Some(true) =>
                        (lhs.tpe.widen.asType, rhs.tpe.widen.asType) match
                            case ('[l], '[r]) =>
                                (Expr.summon[Ordering[r]], Expr.summon[l => r]) match
                                    case (Some(ord), Some(conv)) =>
                                        op match
                                            case ">" =>
                                                '{
                                                    ${ transform(lhs.asExprOf[l]) } >>> SmartAssertions
                                                        .greaterThanL(${ rhs.asExprOf[r] })($ord, $conv)
                                                        .span($span)
                                                }.asExprOf[TestArrow[Any, A]]
                                            case ">=" =>
                                                '{
                                                    ${ transform(lhs.asExprOf[l]) } >>> SmartAssertions
                                                        .greaterThanOrEqualToL(${ rhs.asExprOf[r] })($ord, $conv)
                                                        .span($span)
                                                }.asExprOf[TestArrow[Any, A]]
                                            case "<" =>
                                                '{
                                                    ${ transform(lhs.asExprOf[l]) } >>> SmartAssertions
                                                        .lessThanL(${ rhs.asExprOf[r] })($ord, $conv)
                                                        .span($span)
                                                }.asExprOf[TestArrow[Any, A]]
                                            case "<=" =>
                                                '{
                                                    ${ transform(lhs.asExprOf[l]) } >>> SmartAssertions
                                                        .lessThanOrEqualToL(${ rhs.asExprOf[r] })($ord, $conv)
                                                        .span($span)
                                                }.asExprOf[TestArrow[Any, A]]
                                    case _ => throw new Error("NO")
                    case Some(false) =>
                        (lhs.tpe.widen.asType, rhs.tpe.widen.asType) match
                            case ('[l], '[r]) =>
                                (Expr.summon[Ordering[l]], Expr.summon[r => l]) match
                                    case (Some(ord), Some(conv)) =>
                                        op match
                                            case ">" =>
                                                '{
                                                    ${ transform(lhs.asExprOf[l]) } >>> SmartAssertions
                                                        .greaterThanR(${ rhs.asExprOf[r] })($ord, $conv)
                                                        .span($span)
                                                }.asExprOf[TestArrow[Any, A]]
                                            case ">=" =>
                                                '{
                                                    ${ transform(lhs.asExprOf[l]) } >>> SmartAssertions
                                                        .greaterThanOrEqualToR(${ rhs.asExprOf[r] })($ord, $conv)
                                                        .span($span)
                                                }.asExprOf[TestArrow[Any, A]]
                                            case "<" =>
                                                '{
                                                    ${ transform(lhs.asExprOf[l]) } >>> SmartAssertions
                                                        .lessThanR(${ rhs.asExprOf[r] })($ord, $conv)
                                                        .span($span)
                                                }.asExprOf[TestArrow[Any, A]]
                                            case "<=" =>
                                                '{
                                                    ${ transform(lhs.asExprOf[l]) } >>> SmartAssertions
                                                        .lessThanOrEqualToR(${ rhs.asExprOf[r] })($ord, $conv)
                                                        .span($span)
                                                }.asExprOf[TestArrow[Any, A]]
                                    case _ => throw new Error("NO")
                    case None =>
                        lhs.tpe.widen.asType match
                            case '[l] =>
                                Expr.summon[Ordering[l]] match
                                    case Some(ord) =>
                                        op match
                                            case ">" =>
                                                '{
                                                    ${ transform(lhs.asExprOf[l]) } >>> SmartAssertions
                                                        .greaterThan(${ rhs.asExprOf[l] })($ord)
                                                        .span($span)
                                                }.asExprOf[TestArrow[Any, A]]
                                            case ">=" =>
                                                '{
                                                    ${ transform(lhs.asExprOf[l]) } >>> SmartAssertions
                                                        .greaterThanOrEqualTo(${ rhs.asExprOf[l] })($ord)
                                                        .span($span)
                                                }.asExprOf[TestArrow[Any, A]]
                                            case "<" =>
                                                '{
                                                    ${ transform(lhs.asExprOf[l]) } >>> SmartAssertions
                                                        .lessThan(${ rhs.asExprOf[l] })($ord)
                                                        .span($span)
                                                }.asExprOf[TestArrow[Any, A]]
                                            case "<=" =>
                                                '{
                                                    ${ transform(lhs.asExprOf[l]) } >>> SmartAssertions
                                                        .lessThanOrEqualTo(${ rhs.asExprOf[l] })($ord)
                                                        .span($span)
                                                }.asExprOf[TestArrow[Any, A]]
                                    case _ => throw new Error("NO")
                end match

            case Unseal(MethodCall(lhs, "==", tpes, Some(List(rhs)))) =>
                val span = getSpan(rhs)
                lhs.tpe.widen.asType match
                    case '[l] =>
                        Expr.summon[OptionalImplicit[Diff[l]]] match
                            case Some(optDiff) =>
                                '{
                                    ${ transform(lhs.asExpr) } >>> SmartAssertions
                                        .equalTo(${ rhs.asExpr })($optDiff.asInstanceOf[OptionalImplicit[Diff[Any]]])
                                        .span($span)
                                }.asExprOf[TestArrow[Any, A]]
                            case _ => throw new Error("OptionalImplicit should be always available")
                end match

            case Unseal(MethodCall(lhs, "&&", tpes, Some(List(rhs)))) if isBool(lhs) =>
                val span = getSpan(rhs)
                lhs.tpe.widen.asType match
                    case '[l] =>
                        '{ ${ transform(lhs.asExprOf[Boolean]) } && { ${ transform(rhs.asExprOf[Boolean]) } } }
                            .asExprOf[TestArrow[Any, A]]
                end match

            case Unseal(MethodCall(lhs, "||", tpes, Some(List(rhs)))) if isBool(lhs) =>
                val span = getSpan(rhs)
                lhs.tpe.widen.asType match
                    case '[l] =>
                        '{ ${ transform(lhs.asExprOf[Boolean]) } || { ${ transform(rhs.asExprOf[Boolean]) } } }
                            .asExprOf[TestArrow[Any, A]]
                end match

            case Unseal(method @ MethodCall(lhs, name, tpeArgs, args)) =>
                def body(param: Term) =
                    (tpeArgs, args) match
                        case (Nil, None) =>
                            try Select.unique(param, name)
                            catch
                                case _: AssertionError =>
                                    def getFieldOrMethod(s: Symbol) =
                                        s.fieldMembers
                                            .find(f => f.name == name)
                                            .orElse(s.methodMember(name).filter(_.declarations.nonEmpty).headOption)

                                    // Tries to find directly the referenced method on lhs's type (or if lhs is method, on lhs's returned type)
                                    lhs.symbol.tree match
                                        case DefDef(_, _, tpt, _) =>
                                            getFieldOrMethod(tpt.symbol) match
                                                case Some(fieldOrMethod) => Select(param, fieldOrMethod)
                                                case None                => throw new Error(s"Could not resolve $name on $tpt")
                                        case _ =>
                                            getFieldOrMethod(lhs.symbol) match
                                                case Some(fieldOrMethod) => Select(param, fieldOrMethod)
                                                case None                => throw new Error(s"Could not resolve $name on $lhs")
                                    end match
                        case (tpeArgs, Some(args)) => Select.overloaded(param, name, tpeArgs, args)
                        case (tpeArgs, None)       => TypeApply(Select.unique(param, name), tpeArgs.map(_.typeTree))

                val tpe = lhs.tpe.widen

                if tpe.typeSymbol.isPackageDef then
                    '{ TestArrow.succeed($expr).span(${ getSpan(method) }) }
                else
                    tpe.asType match
                        case '[l] =>
                            val selectBody = '{ (from: l) =>
                                ${ body('{ from }.asTerm).asExprOf[A] }
                            }
                            val lhsExpr    = transform(lhs.asExprOf[l]).asExprOf[TestArrow[Any, l]]
                            val assertExpr = '{ TestArrow.fromFunction[l, A](${ selectBody }) }
                            val pos        = summon[PositionContext]
                            val span       = Expr((lhs.pos.end - pos.start, method.pos.end - pos.start))
                            '{ $lhsExpr >>> $assertExpr.span($span) }
                end if

            case Unseal(tree) =>
                val span = getSpan(tree)
                '{ TestArrow.succeed($expr).span($span) }
        end match
    end transform

    def smartAssertSingle_impl(using Quotes)(value: Expr[Boolean]): Expr[TestResult] =
        import quotes.reflect.*
        val (stats, expr) = value.asTerm match
            case Block(stats, tree) => (stats, tree.asExprOf[Boolean])
            case _                  => (Nil, value)

        given PositionContext = PositionContext(expr.asTerm)
        val code              = Expr(Macros.showExpr(expr))
        val arrow             = transform(expr).asExprOf[TestArrow[Any, Boolean]]
        val pos               = expr.asTerm.pos
        val location          = Expr(Some(s"${pos.sourceFile.path}:${pos.endLine + 1}"))
        val result            = '{ TestResult($arrow.withCode($code).meta(location = $location)) }
        if stats.isEmpty then result else Block(stats, result.asTerm).asExprOf[TestResult]
    end smartAssertSingle_impl

    def smartAssert_impl(using Quotes)(values: Expr[Seq[Boolean]]): Expr[TestResult] =
        import quotes.reflect.*

        values match
            case Varargs(head +: tail) =>
                tail.foldLeft(smartAssertSingle_impl(head)) { (acc, expr) =>
                    '{ $acc && ${ smartAssertSingle_impl(expr) } }
                }

            case other =>
                throw new Error(s"Improper Varargs: ${other}")
        end match
    end smartAssert_impl

    object Unseal:
        def unapply(using Quotes)(expr: Expr[?]): Option[quotes.reflect.Term] =
            import quotes.reflect.*
            Some(expr.asTerm)
    end Unseal
end SmartAssertMacros

object Macros:
    def assertKyo_impl[R: Type, E: Type, A: Type](
        effect: Expr[A < Env[R] & Abort[E]]
    )(assertion: Expr[Assertion[A]], trace: Expr[Trace], position: Expr[FramePosition])(using
        Quotes
    ): Expr[TestResult < Env[R] & Abort[E]] =
        import quotes.reflect.*
        val code          = Expr(showExpr(effect))
        val assertionCode = Expr(showExpr(assertion))
        '{ _root_.kyo.test.CompileVariants.assertKyoProxy($effect, $code, $assertionCode)($assertion)($trace, $position) }
    end assertKyo_impl

    def assert_impl[A](
        value: Expr[A]
    )(assertion: Expr[Assertion[A]], trace: Expr[Trace], position: Expr[FramePosition])(using
        Quotes,
        Type[A]
    ): Expr[TestResult] =
        import quotes.reflect.*
        val code          = showExpr(value)
        val assertionCode = showExpr(assertion)
        '{
            _root_.kyo.test.CompileVariants
                .assertProxy($value, ${ Expr(code) }, ${ Expr(assertionCode) })($assertion)($trace, $position)
        }
    end assert_impl

    def showExpr[A](expr: Expr[A])(using Quotes): String =
        import quotes.reflect.*
        expr.asTerm.pos.sourceCode.get

    def showExpression_impl[A](value: Expr[A])(using Quotes): Expr[String] =
        import quotes.reflect.*
        Expr(showExpr(value))
end Macros
