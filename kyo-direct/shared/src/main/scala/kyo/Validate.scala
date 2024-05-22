package kyo

import scala.quoted.*
import scala.util.control.NonFatal

private[kyo] object Validate:

    def apply(expr: Expr[Any])(using Quotes): Unit =
        import quotes.reflect.*

        val tree = expr.asTerm

        def fail(tree: Tree, msg: String): Unit =
            val show =
                try s" Found: ${tree.show}"
                catch
                    case ex if (NonFatal(ex)) =>
                        ""
            report.error(s"$msg $show", tree.pos)
        end fail

        def pure(tree: Tree): Boolean =
            !Trees.exists(tree) {
                case Apply(TypeApply(Ident("await"), List(t, s)), List(v)) => true
            }

        def show(t: Tree) =
            try t.show
            catch
                case ex: java.lang.Throwable => ""

        Trees.traverse(tree) {
            case Apply(TypeApply(Ident("await"), List(t, s)), List(v)) => // Do nothing
            case Apply(TypeApply(Ident("defer"), List(t)), List(v)) =>
                fail(tree, "Nested `defer` blocks are not allowed.")
            case tree: Term if tree.tpe.typeSymbol.name == "<" =>
                fail(tree, "Effectful computation must be inside an `await` block.")
            case tree @ ValDef(_, _, _) if show(tree).startsWith("var ") =>
                fail(tree, "`var` declarations are not allowed inside a `defer` block.")
            case Return(_, _) =>
                fail(tree, "`return` statements are not allowed inside a `defer` block.")
            case tree @ ValDef(_, _, _) if show(tree).startsWith("lazy val ") =>
                fail(tree, "`lazy val` declarations are not allowed inside a `defer` block.")
            case Lambda(_, body) if !pure(body) =>
                fail(tree, "Lambda functions containing `await` are not supported.")
            case DefDef(_, _, _, Some(body)) if !pure(body) =>
                fail(tree, "`def` declarations containing `await` are not supported.")
            case Try(_, _, _) =>
                fail(tree, "`try/catch` blocks are not supported inside a `defer` block.")
            case ClassDef(_, _, _, _, _) =>
                fail(tree, "`class` declarations are not supported inside a `defer` block.")
        }
    end apply
end Validate
