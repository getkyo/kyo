package kyo.internal

import scala.quoted.*

/** Recognizes `direct`, `.now`, and `.later` in a typed tree by their symbols, which are the top-level definitions of Direct.scala. */
private[kyo] object DirectSyntax:

    /** The module class owning Direct.scala's top-level definitions. */
    private def owner(using q: Quotes): q.reflect.Symbol =
        q.reflect.Symbol.requiredModule("kyo.Direct$package").moduleClass

    private def isDefinition(using q: Quotes)(symbol: q.reflect.Symbol, name: String): Boolean =
        symbol.name == name && symbol.owner.equals(owner)

    /** A `.now` application: the computation it sequences, the exposed result type, and the effect row it records. */
    def now(using q: Quotes)(tree: q.reflect.Tree): Option[(q.reflect.Term, q.reflect.TypeRepr, q.reflect.TypeRepr)] =
        import q.reflect.*
        tree match
            case Apply(TypeApply(fun, List(result, row)), List(computation)) if isDefinition(fun.symbol, "now") =>
                Some((computation, result.tpe, row.tpe))
            case _ => None
        end match
    end now

    /** A `.later` application: the computation it keeps pending. */
    def later(using q: Quotes)(tree: q.reflect.Tree): Option[q.reflect.Term] =
        import q.reflect.*
        tree match
            case Apply(TypeApply(fun, List(_, _)), List(computation)) if isDefinition(fun.symbol, "later") => Some(computation)
            case _                                                                                         => None
    end later

    /** True for the call of a `direct` block, as the `Inlined` node of an already expanded nested block carries it. */
    def isDirect(using q: Quotes)(call: q.reflect.Tree): Boolean =
        isDefinition(call.symbol, "direct")
end DirectSyntax
