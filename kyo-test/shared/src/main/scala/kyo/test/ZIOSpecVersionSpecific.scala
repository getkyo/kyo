package kyo.test

import scala.quoted.*

private[test] trait KyoSpecVersionSpecific[R]:
    self: KyoSpec[R] =>

    transparent inline def suiteAll(inline name: String)(inline spec: Any): Any =
        ${ KyoSpecVersionSpecificMacros.suiteAllImpl('name, 'spec) }
end KyoSpecVersionSpecific

private[test] object KyoSpecVersionSpecificMacros:

    def suiteAllImpl(name: Expr[String], spec: Expr[Any])(using ctx: Quotes) =
        import ctx.reflect.*

        enum TestOrStatement:
            case SpecCase(term: Term)
            case StatementCase(statement: Statement)

        def collectTests(tree: Tree): List[TestOrStatement] =
            tree match
                case Block(stats, expr) =>
                    stats.flatMap(collectTests) ++ collectTests(expr)

                case spec: Term if spec.tpe <:< TypeRepr.of[kyo.test.Spec[_, _]] =>
                    List(TestOrStatement.SpecCase(spec))

                case vd: Statement =>
                    List(TestOrStatement.StatementCase(vd))

                case other =>
                    throw new Error("UNHANDLED: " + other)

        var idx = 0

        def loop(results: List[TestOrStatement], acc: List[Statement], refs: List[Var]): Term =
            results match
                case TestOrStatement.StatementCase(tree) :: rest =>
                    loop(rest, tree :: acc, refs)
                case TestOrStatement.SpecCase(spec) :: rest =>
                    val name = "spec" + idx
                    idx += 1
                    val symbol = Symbol.newVal(Symbol.spliceOwner, name, spec.tpe, Flags.EmptyFlags, Symbol.noSymbol)
                    val valDef = ValDef(symbol, Some(spec.changeOwner(symbol)))
                    val ref    = Var(symbol)
                    loop(rest, valDef :: acc, ref :: refs)
                case Nil =>
                    val mySuite =
                        val reversedRefs  = refs.reverse
                        val combinedTypes = reversedRefs.map(_.tpe).reduce(OrType(_, _)).widen
                        val names =
                            combinedTypes.asType match
                                case '[specType] =>
                                    Varargs(reversedRefs.map { a =>
                                        a.asExprOf[specType]
                                    }).asExprOf[Seq[specType]]

                        names match
                            case '{ $specNames: Seq[kyo.test.Spec[a, b]] } =>
                                '{ suite($name)($specNames) }
                    end mySuite

                    Block(
                        acc.reverse,
                        mySuite.asTerm
                    )

        spec.asTerm match
            case Inlined(a, b, expr) =>
                val results  = collectTests(expr)
                val combined = Inlined(a, b, loop(results, Nil, Nil))
                // println("--")
                // println(combined.tpe.dealias.widen.simplified)
                combined.asExpr
        end match
    end suiteAllImpl
end KyoSpecVersionSpecificMacros
