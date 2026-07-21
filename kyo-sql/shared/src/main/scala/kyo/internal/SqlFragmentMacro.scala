package kyo.internal

import kyo.Chunk
import kyo.SqlAst
import kyo.SqlSchema
import scala.quoted.*

/** Macro implementation of the `sql"..."` interpolator — builds a [[kyo.SqlAst.Fragment]] from a string interpolation, classifying each
  * interpolated argument:
  *
  *   - If the argument is a `SqlAst.Term[?]` (Column, Query, Aggregate.Call, another Fragment, …) → emits `Fragment.Embed(arg)`, allowing
  *     column / sub-query references to be inlined into the rendered SQL.
  *   - Otherwise, if `SqlSchema[argType]` can be summoned → emits `Fragment.Bind(arg, schema)` for a bound parameter.
  *   - Otherwise → compile error pointing at the argument's position.
  *
  * The string-context literals become `Fragment.Lit(text)` parts between the arg parts.
  */
object SqlFragmentMacro:

    def sqlImpl(sc: Expr[StringContext], args: Expr[Seq[Any]])(using Quotes): Expr[SqlAst.Fragment[Any]] =
        import quotes.reflect.*

        val argsTerms: List[Term] = args match
            case Varargs(as) => as.map(_.asTerm).toList
            case _           => report.errorAndAbort("sql interpolator requires a literal argument list")

        // Extract literal-string parts. Walks past `Inlined` wrappers and matches the synthesised StringContext constructor call.
        val literals: List[String] = extractStringLiterals(sc.asTerm)

        if literals.size != argsTerms.size + 1 then
            report.errorAndAbort(
                s"sql interpolator expected ${argsTerms.size + 1} string parts but got ${literals.size}."
            )
        end if

        // Build a List[Expr[Fragment.Part]] interleaving Lit literals and per-arg Bind/Embed parts.
        val partExprs: List[Expr[SqlAst.Fragment.Part]] =
            val buf = List.newBuilder[Expr[SqlAst.Fragment.Part]]
            literals.zipWithIndex.foreach: (lit, i) =>
                if lit.nonEmpty then buf += '{ SqlAst.Fragment.Lit(${ Expr(lit) }) }
                if i < argsTerms.size then buf += partForArg(argsTerms(i))
            buf.result()
        end partExprs

        '{ SqlAst.Fragment[Any](Chunk.from(${ Expr.ofList(partExprs) })) }
    end sqlImpl

    /** Classify one interpolated arg as Embed (Term subtype) or Bind (SqlSchema in scope). */
    private def partForArg(using Quotes)(argTerm: quotes.reflect.Term): Expr[SqlAst.Fragment.Part] =
        import quotes.reflect.*

        val argType         = argTerm.tpe.widen.dealias
        val astTermTypeRepr = TypeRepr.of[SqlAst.Term[?]]
        if argType <:< astTermTypeRepr then
            val argExpr = argTerm.asExprOf[SqlAst.Term[?]]
            '{ SqlAst.Fragment.Embed($argExpr) }
        else
            argType.asType match
                case '[t] =>
                    Expr.summon[SqlSchema[t]] match
                        case Some(schemaExpr) =>
                            val argExpr: Expr[t] = argTerm.asExprOf[t]
                            '{ SqlAst.Fragment.Bind[t]($argExpr, $schemaExpr) }
                        case None =>
                            report.errorAndAbort(
                                s"No SqlSchema[${TypeRepr.of[t].show}] found for sql\"…\" argument. " +
                                    s"Add 'derives SqlSchema' to the type or define 'given SqlSchema[…]' in scope, " +
                                    s"or pass a Term/Column instead.",
                                argTerm.pos
                            )
        end if
    end partForArg

    /** Pattern matches the synthesised `StringContext.apply(parts*)` (or `new StringContext(parts*)`) call to extract the literal segments,
      * walking through `Inlined` wrappers introduced by inline-method desugaring.
      */
    private def extractStringLiterals(using Quotes)(t: quotes.reflect.Term): List[String] =
        import quotes.reflect.*
        t match
            case Inlined(_, _, inner)                         => extractStringLiterals(inner)
            case Typed(inner, _)                              => extractStringLiterals(inner)
            case Block(_, inner)                              => extractStringLiterals(inner)
            case Select(inner, _)                             => extractStringLiterals(inner)
            case Apply(_, List(Typed(Repeated(elems, _), _))) => readStrings(elems)
            case Apply(_, List(Repeated(elems, _)))           => readStrings(elems)
            case Apply(_, elems)                              => readStrings(elems)
            case Ident(_) =>
                t.symbol.tree match
                    case ValDef(_, _, Some(rhs)) => extractStringLiterals(rhs)
                    case _ =>
                        report.errorAndAbort(s"sql interpolator requires a literal StringContext, got: ${t.show}")
            case other =>
                report.errorAndAbort(s"sql interpolator could not extract string parts from: ${other.show(using Printer.TreeStructure)}")
        end match
    end extractStringLiterals

    private def readStrings(using Quotes)(elems: List[quotes.reflect.Term]): List[String] =
        import quotes.reflect.*
        elems.map:
            case Literal(StringConstant(s)) => s
            case other                      => report.errorAndAbort(s"Expected string literal, got: ${other.show}")
    end readStrings

end SqlFragmentMacro
