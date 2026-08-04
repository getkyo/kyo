package kyo.internal

import kyo.Chunk
import kyo.Sql
import scala.quoted.*

/** Macro implementation of the `sql"..."` interpolator, builds a [[kyo.Sql.Fragment]] from a string interpolation, classifying each
  * interpolated argument:
  *
  *   - If the argument is a `Sql.Term[?]` (Column, Query, Aggregate.Call, another Fragment, …) → emits `Fragment.Embed(arg)`, allowing
  *     column / sub-query references to be inlined into the rendered SQL.
  *   - Otherwise, if `SqlSchema.Column[argType]` can be summoned → emits `Fragment.Bind(arg, schema)` for a bound parameter.
  *   - Otherwise → compile error pointing at the argument's position.
  *
  * The string-context literals become `Fragment.Lit(text)` parts between the arg parts.
  */
object SqlFragmentMacro:

    def sqlImpl(sc: Expr[StringContext], args: Expr[Seq[Any]])(using Quotes): Expr[Sql.Fragment[Any]] =
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
        val partExprs: List[Expr[Sql.Fragment.Part]] =
            val buf = List.newBuilder[Expr[Sql.Fragment.Part]]
            literals.zipWithIndex.foreach: (lit, i) =>
                if lit.nonEmpty then buf += '{ Sql.Fragment.Lit(${ Expr(lit) }) }
                if i < argsTerms.size then buf += partForArg(argsTerms(i))
            buf.result()
        end partExprs

        // `Chunk.apply` varargs rather than `Chunk.from(List(...))`: the varargs tree is the shape the
        // `FromExpr[Chunk[A]]` derivation lifts, which is what lets an `sql"..."` fragment fold at compile time.
        '{ Sql.Fragment[Any](Chunk(${ Varargs(partExprs) }*)) }
    end sqlImpl

    /** Classify one interpolated arg as Embed (Term subtype) or Bind (Schema in scope). */
    private def partForArg(using Quotes)(argTerm: quotes.reflect.Term): Expr[Sql.Fragment.Part] =
        import quotes.reflect.*

        val argType         = argTerm.tpe.widen.dealias
        val astTermTypeRepr = TypeRepr.of[Sql.Term[?]]
        if argType <:< astTermTypeRepr then
            val argExpr = argTerm.asExprOf[Sql.Term[?]]
            '{ Sql.Fragment.Embed($argExpr) }
        else
            argType.asType match
                case '[t] =>
                    Expr.summon[kyo.SqlSchema.Column[t]] match
                        case Some(ev) =>
                            val argExpr: Expr[t] = argTerm.asExprOf[t]
                            val typeName         = Expr(Type.show[t])
                            '{ Sql.Fragment.Bind[t]($argExpr, $ev, $typeName) }
                        case None =>
                            report.errorAndAbort(
                                s"${TypeRepr.of[t].show} is not a single-column SQL type for a sql\"…\" bind argument. " +
                                    s"Install a single-column codec (Sql.jsonColumn / " +
                                    s"Sql.enumText / SqlSchema.of), or pass a Term/Column instead.",
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
