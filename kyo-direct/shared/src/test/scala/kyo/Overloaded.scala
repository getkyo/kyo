package kyo

import com.eed3si9n.eval.Eval
import scala.quoted.*

object Overloaded:

    inline def resolveEach[A](inline as: A*)(inline body: A => Unit): Unit =
        ${ resolveEachMacro('as, 'body) }

    def resolveEachMacro[A: Type](asExpr: Expr[Seq[A]], bodyExpr: Expr[A => Unit])(using Quotes): Expr[Unit] =
        import quotes.reflect.*

        val (paramSymbol, bodyTree) = bodyExpr.asTerm.underlyingArgument match
            case Block(List(DefDef(_, List(List(param: ValDef)), _, Some(body))), _) =>
                (param.symbol, body)
            case other =>
                report.errorAndAbort(s"Expected simple lambda, got: ${other.show}")

        asExpr match
            case Varargs(args) =>

                def substituted(arg: Expr[?]): Tree =
                    val sub = new TreeMap:
                        override def transformTerm(tree: Term)(owner: Symbol): Term =
                            tree match
                                case Ident(name) if tree.symbol.eq(paramSymbol) =>
                                    arg.asTerm.changeOwner(owner)
                                case other => super.transformTerm(tree)(owner)

                    sub.transformTerm(bodyTree)(Symbol.spliceOwner)
                end substituted

                val calls: Seq[Expr[Any]] = args.map { arg =>

                    val inlinedBody: Tree = substituted(arg)

                    val code: Expr[String] = Expr(inlinedBody.asExpr.show)

                    '{ Eval[Any]($code) }
                }

                Expr.block(calls.toList, '{ () })

            case _ =>
                report.errorAndAbort("Arguments must be statically known (Varargs)")
        end match
    end resolveEachMacro
end Overloaded
