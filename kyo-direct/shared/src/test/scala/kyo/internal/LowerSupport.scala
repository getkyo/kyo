package kyo.internal

import kyo.*
import scala.quoted.*

/** Test entry point for the lowering core with kyo-direct's markers: what `direct` becomes once it is switched to the core. */
object LowerSupport:

    transparent inline def lowered[A](inline body: A) = ${ impl[A]('body) }

    private def impl[A: Type](body: Expr[A])(using Quotes): Expr[Any] =
        Validate(body, LowerMarkers)
        Lower(body, LowerMarkers, Policy.direct)

    /** kyo-direct's markers, with `lowered` as a boundary next to `direct`. */
    private object LowerMarkers extends Markers:
        def sequencing(using q: Quotes)(tree: q.reflect.Term): Maybe[(q.reflect.Term, q.reflect.TypeRepr, q.reflect.TypeRepr)] =
            DirectMarkers.sequencing(tree)
        def passThrough(using q: Quotes)(tree: q.reflect.Term): Maybe[q.reflect.Term] =
            DirectMarkers.passThrough(tree)
        def isBoundary(using q: Quotes)(call: q.reflect.Tree): Boolean =
            DirectMarkers.isBoundary(call) ||
                (call.symbol.name == "lowered" &&
                    call.symbol.owner.equals(q.reflect.Symbol.requiredModule("kyo.internal.LowerSupport").moduleClass))
    end LowerMarkers
end LowerSupport
